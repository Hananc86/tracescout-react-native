import Foundation

/// The durable queue seam — production supplies the encrypted frame store; a
/// test supplies an array. Declared at file scope because Swift before 6.0
/// refuses a protocol nested inside another declaration, and the CI toolchain
/// version is not something this file should depend on.
public protocol FrameDeliveryQueue {
    func readAll() -> [Data]
    @discardableResult func append(_ record: Data) -> Bool
    @discardableResult func removeFirst(_ count: Int) -> Int
    /// Remove the record at `index`, preserving the relative order of every
    /// other record.
    ///
    /// REQUIRED, deliberately WITHOUT a protocol-extension default. A
    /// default returning false would make a container that cannot rewrite look
    /// exactly like one that had nothing to remove, and settlement would then
    /// fail silently on the container this exists for. A queue that genuinely
    /// cannot do it must say so, and the caller RETAINS.
    @discardableResult func remove(at index: Int) -> Bool

    /// Can an ABSENCE observed in `readAll` be trusted?
    ///
    /// REQUIRED, and deliberately WITHOUT a default — the same reasoning as
    /// `remove(at:)` above and as `RecordStore.integrity`. A default of `.ok`
    /// is FAIL-OPEN, and a future adapter would inherit it by saying nothing.
    func integrity() -> StoreIntegrity
}

/// The frame delivery state machine — pure, so process death, a missing
/// acknowledgement and the retry path are driven under unit tests exactly as
/// they run on a device. Swift twin of rn-core `FrameDelivery.kt`.
///
/// THE TWO RULES, and why they point the opposite way from the chunk case:
///
/// 1. **Never remove before durable settlement.** A frame is removed only
/// after a valid `accepted`/`duplicate` acknowledgement, a permanent
/// record-level refusal, or exhaustion. A 2xx whose body does not carry a
/// bound `frame-ack-1` is NOT an acknowledgement.
/// 2. **Where a window is unavoidable, prefer DUPLICATION to loss.** Rotating
/// a retried frame to the tail keeps one dead upload from blocking every
/// later frame. It is done append-first, verified, then remove — a crash
/// inside the window leaves a second copy, and `/replay/frames` binds a
/// frame by `contentHash` and de-duplicates, so a duplicate costs nothing
/// while a keyframe nothing can recreate costs everything.
///
/// 3. **Settlement is by IDENTITY, not by POSITION.** The store is no longer a
/// pure FIFO: since PR #128 freshness eviction and current-session-first
/// ordering both rewrite it, legitimately, while a POST is in flight. A
/// settled frame is therefore removed wherever it now resides — see
/// `settle(queue:identity:)`.
///
/// The chunk pipeline reaches the opposite conclusion (freeze the exact prefix,
/// never re-form it) because a BATCH's identity is its composition and a second
/// copy is genuinely different, uncollapsible work. Same principle, different
/// answer, because the unit is different. Do not copy the chunk conclusion.
public enum FrameDelivery {

    /// The server's `frame-ack-1`, as parsed by the transport.
    public struct FrameAck: Equatable {
        public let state: String
        public let sessionId: String
        public let visualFrameId: String
        public let contentHash: String
        public let byteSize: Int64
        public init(state: String, sessionId: String, visualFrameId: String,
                    contentHash: String, byteSize: Int64) {
            self.state = state; self.sessionId = sessionId
            self.visualFrameId = visualFrameId; self.contentHash = contentHash
            self.byteSize = byteSize
        }
    }

    /// What the transport made of one frame POST.
    public struct PostResult {
        public let status: Int
        /// The identity-bound acknowledgement, or nil. A 2xx is NOT this: a
        /// proxy page, a truncated body and an error page served with a 200 are
        /// all 2xx, and none of them is permission to delete durable bytes.
        public let ack: FrameAck?
        public let code: String?
        public init(status: Int, ack: FrameAck? = nil, code: String? = nil) {
            self.status = status; self.ack = ack; self.code = code
        }
    }

    /// Settlement states. `duplicate` is a settlement, not a failure.
    static let ackStates: Set<String> = ["accepted", "duplicate"]

    /// The ONE settlement decision, matching the shared protocol rule
    /// (`mobile-protocol` `evaluateFrameAck`): the acknowledgement must prove
    /// the committed frame belongs to the SAME session and carries the EXACT
    /// content identity and size of the unresolved in-flight frame.
    ///
    /// Returns nil when it settles; otherwise a typed refusal code — never a
    /// message built from the response, so it can be traced without leaking a
    /// body. The codes are the protocol's, not iOS's.
    public static func ackRefusal(_ ack: FrameAck?, _ d: FrameInFlight) -> String? {
        guard let a = ack else { return "ack_absent" }
        if !ackStates.contains(a.state) { return "ack_state_unknown" }
        if a.sessionId != d.sessionId { return "ack_session_mismatch" }
        if a.visualFrameId != d.visualFrameId { return "ack_frame_mismatch" }
        if a.contentHash != d.contentHash { return "ack_content_mismatch" }
        if a.byteSize != Int64(d.byteSize) { return "ack_byte_size_mismatch" }
        return nil
    }

    public struct Outcome: Equatable {
        public var delivered = 0
        public var duplicate = 0
        public var refused = 0
        public var retained = 0
        public var unreadable = 0
        public var exhausted = 0
        /// The server settled the frame but the container could not remove the
        /// bytes. Counted apart from `delivered`/`duplicate` because those
        /// describe the SERVER's answer and this describes OUR queue: reporting
        /// only the delivery would hide that the record is still there and will
        /// be posted again.
        public var unremoved = 0
        /// This pass stopped because the LANE was reclaimed under it, not
        /// because the queue was empty. Its own field so a preempted pass can
        /// never be read as a completed one — a lane-loss and a clean finish
        /// produce the same zero counters otherwise, which is precisely how the
        /// original stall stayed invisible.
        public var laneLost = false
        /// Set when the descriptor could not be persisted: nothing was sent.
        public var failedClosed = false
    }

    /// What removing the settled frame actually did.
    public enum Settlement: Equatable {
        /// The exact record was found and removed once. `index` is where it was
        /// non-zero means the head moved under us, which is a real
        /// concurrency event and is traced rather than swallowed.
        case removed(index: Int)
        /// A lawful concurrent mutation (freshness eviction, a reorder that
        /// dropped it, a previous settlement) already took it. Nothing to do,
        /// and above all nothing to RE-INSERT.
        case alreadyAbsent
        /// It is present and the container refused to rewrite. The caller must
        /// retain: a settlement it could not perform is not a settlement.
        case unremovable
    }

    /// Settle the EXACT record the acknowledgement names, WHEREVER it now
    /// resides — not "if it is still the head".
    ///
    /// ## The measured defect this exists for
    ///
    /// RN iOS 0.3.0 (23) recorded **17 `frame_head_moved` events**: a valid,
    /// identity-bound `frame-ack-1` arrived, the frame the server had committed
    /// was NOT at the head any more, and the old code logged the fact and left
    /// the bytes in place. The head can only have moved because something else
    /// lawfully moved it — and after PR #128 there is exactly such a something:
    /// freshness eviction and current-session-first reordering both rewrite the
    /// store while a POST is in flight. So the frame was posted again, answered
    /// `duplicate`, and each retry burned an attempt against a record that was
    /// already durably stored on the server.
    ///
    /// The identity is `(sessionId, visualFrameId, contentHash, byteSize)` —
    /// the same tuple the descriptor was frozen with and the same one the
    /// acknowledgement is bound to. Position is not part of it, and treating
    /// position as if it were is what made a correct ack unusable.
    ///
    /// ONCE. A crash inside the append-first rotation window can leave two
    /// records with this identity, deliberately (duplication over loss, and
    /// `/replay/frames` collapses them). Removing "every match" would delete a
    /// copy the server never acknowledged; the first match is removed and the
    /// pass moves on.
    ///
    /// A DIFFERENT record at the head is never touched. The only record this
    /// can remove is one whose full identity equals the settled frame's.
    public static func settle(queue: FrameDeliveryQueue, identity: String) -> Settlement {
        // AN ABSENCE FOUND THROUGH A LOSSY READ IS NOT AN ABSENCE, AND
        // THIS IS WHERE THE FRAME LANE GOT IT WRONG.
        //
        // The search below walks `readAll`. On a store with one damaged frame
        // that read is SHORTER than the file, so the frame being settled can be
        // missing from it for two completely different reasons: something
        // lawfully removed it, or it is still on disk and simply could not be
        // decrypted. The first is `alreadyAbsent`, which the pump treats as a
        // successful settlement — it releases the descriptor and counts a
        // DELIVERY. Measured on a real corrupt `EncryptedFileStore`: a frame
        // whose bytes were untouched on disk was reported `delivered`, its
        // attempt history discarded, and the wedge left invisible.
        //
        // The store already knows which case it is. Ask it.
        guard queue.integrity().isTrustworthy else { return .unremovable }
        // `peek`, NOT `decode`. The search needs the record's IDENTITY, and
        // `decode` copies its frame bytes out to produce one — megabytes per
        // settlement against a 4 MiB store, several times a second. Same
        // framing checks, same identity string, no payload copy.
        for (i, record) in queue.readAll().enumerated() {
            guard let (h, n) = FrameRecord.peek(record) else { continue }
            guard FrameRecord.identity(h, payloadBytes: n) == identity else { continue }
            return queue.remove(at: i) ? .removed(index: i) : .unremovable
        }
        return .alreadyAbsent
    }

    /// Sanitized checkpoints. Ids, a digest, a length and a counter only.
    public typealias Trace = (String, [(String, String)]) -> Void

    public static let maxAttempts = 8

    /// The ONE response code that means "the session this frame is addressed to
    /// does not exist on the server yet".
    ///
    /// It is a named constant because the dependency disposition must key off
    /// the BODY CODE and not off HTTP 404 — the frame route answers 404 for a
    /// dark ingest gate and for a disabled visual-frame flag as well, and
    /// neither is a prerequisite a client can wait out. Measured on the deployed
    /// controller: `if (!sessionRow) return res.status(404).json({ error:
    /// 'not_found', code: 'session_not_found' })`.
    public static let sessionDependencyCode = "session_not_found"

    /// - Parameter post: called at most once per frame per pass.
    /// - Parameter nowMs: injected so a test does not depend on the wall clock.
    /// - Parameter stillOwnsLane: the LANE fence. A pass whose ownership was
    /// reclaimed — because its lease expired while a request never came back —
    /// must not mutate a queue a newer worker now owns. It is checked at the
    /// top of every iteration AND again immediately after `post` returns,
    /// because that second point is exactly where a lost-then-resumed
    /// continuation re-enters. Losing the lane there RETAINS: the descriptor
    /// stays frozen and the bytes stay queued, so the new owner resumes the
    /// SAME descriptor and the server collapses the duplicate by content hash.
    /// Default `true` keeps every existing caller and test byte-identical.
    @discardableResult
    public static func drain(queue: FrameDeliveryQueue,
                             inflight: FrameInFlightStore,
                             post: (FrameHeader, Data) -> PostResult,
                             trace: @escaping Trace = { _, _ in },
                             nowMs: () -> Int64 = { 0 },
                             stillOwnsLane: () -> Bool = { true }) -> Outcome {
        var out = Outcome()
        // Build 26 — TRANSPORT STAGE TIMING, diagnostic only. Nothing branches
        // on these; they exist so the ~340-368 ms per-frame floor stops being
        // INFERRED from server settlement spacing and becomes measured on the
        // device. `nowMs` already defaults to `{ 0 }`, so a caller that does not
        // supply a clock records zeros rather than changing behaviour.
        var lastSettleMs: Int64 = -1
        let snapshot = queue.readAll()
        // A rotated frame goes to the TAIL and therefore back into this walk.
        // Without this the pass comes around the ring and posts the same frame
        // again — a retry storm inside one drain, burning the whole attempt
        // budget. One frame, one attempt, per pass.
        var seen = Set<String>()

        for _ in snapshot.indices {
            // STOP BEFORE TOUCHING ANYTHING. If the lane was reclaimed while
            // this pass was working, every later mutation would be a second
            // worker operating on the same durable queue.
            guard stillOwnsLane() else { out.laneLost = true; break }
            // Walk a snapshot but always operate on the LIVE head: the container
            // is FIFO and every disposition either removes the head or rotates it.
            guard let head = queue.readAll().first else { break }
            guard let (h, bytes) = FrameRecord.decode(head) else {
                // Nothing to preserve: an undecodable record cannot be posted
                // and cannot be repaired. Removing it is not a loss.
                // The COUNT is a claim about the store. `removeFirst`
                // returns the number ACTUALLY removed; discarding it counted an
                // `unreadable` for a record still at the head — and since this
                // branch `continue`s, the next iteration met the same head and
                // counted it again, reporting one stuck record as N while every
                // frame behind it starved. The exact-identity settlement paths
                // below already get this right; these three did not.
                guard queue.removeFirst(1) == 1 else {
                    out.unremoved += 1
                    trace("frame_remove_not_committed", [("by", "unreadable")])
                    return out
                }
                out.unreadable += 1
                if inflight.load() != nil { inflight.clear() }
                continue
            }
            let identity = "\(h.sessionId)|\(h.visualFrameId)|\(h.contentHash)|\(bytes.count)"
            let d0 = inflight.load()

            // A descriptor naming something other than the head describes a
            // frame that already settled or rotated: reconcile with NO request.
            if let prior = d0, prior.identity() != identity {
                trace("frame_inflight_reconciled", [("visualFrameId", prior.visualFrameId)])
                inflight.clear()
            }
            if seen.contains(identity) { break }   // came around the ring; done for this pass
            seen.insert(identity)

            let resumed = (d0?.identity() == identity) ? d0 : nil
            if let r = resumed {
                trace("frame_inflight_resumed", [
                    ("sessionId", h.sessionId), ("visualFrameId", h.visualFrameId),
                    ("contentHash", h.contentHash), ("byteSize", String(bytes.count)),
                    ("attempts", String(r.attempts))])
            }
            // The header only advances on a COMPLETED rotation; the descriptor
            // advances before the request. A process dying inside every attempt
            // would otherwise leave the count at zero forever.
            let prior = max(h.attempts, resumed?.attempts ?? 0)
            if prior >= maxAttempts {
                // `exhausted` IS A LOSS COUNT — it says these bytes were
                // given up on and removed. It may not be taken on an
                // uncommitted removal.
                guard queue.removeFirst(1) == 1 else {
                    out.unremoved += 1
                    trace("frame_remove_not_committed", [
                        ("by", "exhausted"), ("visualFrameId", h.visualFrameId)])
                    return out
                }
                out.exhausted += 1; inflight.clear()
                trace("frame_inflight_settled", [
                    ("by", "exhausted"), ("visualFrameId", h.visualFrameId),
                    ("attempts", String(prior))])
                continue
            }

            let d = FrameInFlight(sessionId: h.sessionId, visualFrameId: h.visualFrameId,
                                  contentHash: h.contentHash, byteSize: bytes.count,
                                  attempts: prior + 1,
                                  firstAttemptAtMs: resumed?.firstAttemptAtMs ?? nowMs())
            // FAIL CLOSED. Sending on the strength of a descriptor we could not
            // persist is exactly the unresolved state we cannot recover from.
            guard inflight.save(d) else {
                trace("frame_inflight_freeze_failed", [("visualFrameId", h.visualFrameId)])
                out.failedClosed = true
                return out
            }
            let frozenAtMs = nowMs()
            trace("frame_inflight_frozen", [
                ("sessionId", h.sessionId), ("visualFrameId", h.visualFrameId),
                ("contentHash", h.contentHash), ("byteSize", String(bytes.count)),
                ("attempts", String(d.attempts)),
                // T4: the gap between the previous settlement and this freeze —
                // the client-side scheduling cost, which is exactly the part
                // that cannot be separated from network time server-side.
                ("t4Ms", String(lastSettleMs < 0 ? -1 : frozenAtMs - lastSettleMs))])

            let postStartMs = nowMs()
            let r = post(h, bytes)
            let postEndMs = nowMs()
            trace("rn_frame_post", [("status", String(r.status)), ("code", r.code ?? ""),
                                    ("attempt", String(d.attempts)),
                                    // T1: freeze -> request start (descriptor + I/O)
                                    ("t1Ms", String(postStartMs - frozenAtMs)),
                                    // T2: request start -> response
                                    ("t2Ms", String(postEndMs - postStartMs))])

            // THE FENCE THAT CLOSES THE MEASURED DEFECT. A continuation lost
            // across a suspension can return here long after its lane was
            // reclaimed. Settling, removing or rotating now would mutate a head
            // a newer attempt owns — the late attempt-1 callback stepping on
            // attempt 2. Retaining costs at most one duplicate delivery, which
            // the content-hash endpoint collapses; acting costs a keyframe.
            guard stillOwnsLane() else {
                out.laneLost = true
                trace("frame_lane_lost", [("visualFrameId", h.visualFrameId),
                                          ("attempts", String(d.attempts))])
                break
            }

            // A frame settles ONLY on a frame-ack-1 whose identity matches the
            // descriptor frozen before the request. Everything else — a bare
            // 2xx, a mismatched identity, an unknown state — RETAINS the bytes.
            let ackFail = (200...299).contains(r.status) ? ackRefusal(r.ack, d) : "ack_absent"
            let settled = (200...299).contains(r.status) && ackFail == nil
            let permanent = (400...499).contains(r.status)
                && r.status != 404 && r.status != 408 && r.status != 429

            if settled, let ack = r.ack {
                // Settle the frame the SERVER committed, wherever our own
                // container has since moved it. A concurrent freshness
                // eviction or reorder changes WHERE the record is; it does not
                // change WHICH record was acknowledged.
                switch settle(queue: queue, identity: identity) {
                case .removed(let index):
                    if ack.state == "duplicate" { out.duplicate += 1 } else { out.delivered += 1 }
                    if index != 0 {
                        // Augment, never hide: the concurrency is real and the
                        // trace still says it happened — it is now followed by
                        // a settlement instead of by nothing.
                        trace("frame_settled_after_head_moved", [
                            ("visualFrameId", h.visualFrameId), ("index", String(index))])
                    }
                    let settledAtMs = nowMs()
                    trace("frame_inflight_settled", [
                        ("by", ack.state), ("visualFrameId", h.visualFrameId),
                        ("attempts", String(d.attempts)),
                        // T3: response -> settlement (ack validation + removal)
                        ("t3Ms", String(settledAtMs - postEndMs))])
                    lastSettleMs = settledAtMs
                case .alreadyAbsent:
                    // Evicted or already settled while the POST was in flight.
                    // Re-inserting it would resurrect bytes a lawful mutation
                    // chose to drop, so the descriptor is released and nothing
                    // else happens.
                    if ack.state == "duplicate" { out.duplicate += 1 } else { out.delivered += 1 }
                    trace("frame_settled_already_absent", [
                        ("by", ack.state), ("visualFrameId", h.visualFrameId)])
                case .unremovable:
                    out.unremoved += 1
                    trace("frame_settle_unremovable", [("visualFrameId", h.visualFrameId)])
                }
                inflight.clear()
            } else if permanent {
                // A record-level refusal: these bytes can never succeed, and the
                // metadata event's frame renders as a typed placeholder. Same
                // exact-identity settlement — a refused frame left behind by a
                // moved head would be re-posted for a refusal that can never
                // change.
                if case .unremovable = settle(queue: queue, identity: identity) {
                    out.unremoved += 1
                    trace("frame_settle_unremovable", [("visualFrameId", h.visualFrameId)])
                }
                out.refused += 1; inflight.clear()
                trace("frame_inflight_settled", [
                    ("by", "refused"), ("visualFrameId", h.visualFrameId),
                    ("code", r.code ?? "")])
            } else if (200...299).contains(r.status) {
                // 2xx with NO valid identity-bound acknowledgement. NOT a
                // settlement: retain the bytes exactly where they are and stop
                // the pass, or the loop would re-post the same unchanged head
                // immediately. The typed reason says WHICH check failed — an
                // absent ack and a mismatched one are different facts.
                out.retained += 1
                trace("frame_ack_invalid", [
                    ("status", String(r.status)), ("reason", ackFail ?? "ack_absent"),
                    ("visualFrameId", h.visualFrameId)])
                return out
            } else {
                // Precondition or transport. Rotate APPEND-FIRST so the bytes
                // are durably present twice before they are present once — a
                // crash here duplicates, and the endpoint collapses a duplicate
                // by contentHash.
                let rotated = FrameRecord.encode(
                    FrameHeader(visualFrameId: h.visualFrameId, sessionId: h.sessionId,
                                screenId: h.screenId, contentHash: h.contentHash,
                                width: h.width, height: h.height, byteSize: h.byteSize,
                                format: h.format, attempts: d.attempts),
                    bytes: bytes)
                let appended = queue.append(rotated)
                // DELIBERATELY the head check, NOT exact-identity settlement.
                // The rotated copy carries the SAME identity (attempts is in the
                // header, not in the identity), so an identity search after the
                // append could match the copy we just made and undo the rotation
                // worse, if the original had been evicted meanwhile it would
                // match ONLY the copy and delete it. Failing to remove here
                // leaves a duplicate, which `/replay/frames` collapses.
                if appended && headIdentity(queue) == identity {
                    // `retained` is true either way — the frame IS retained. A
                    // failed removal leaves the deliberate duplicate this
                    // append-first order exists to prefer, which the endpoint
                    // collapses by contentHash; `seen` ends the pass on the next
                    // encounter, so it cannot spin.
                    if queue.removeFirst(1) != 1 {
                        out.unremoved += 1
                        trace("frame_remove_not_committed", [
                            ("by", "rotate"), ("visualFrameId", h.visualFrameId)])
                    }
                    out.retained += 1
                } else if !appended {
                    // Could not rotate: leave the frame at the head untouched.
                    // Retrying a head is bounded by attempts.
                    out.retained += 1
                    trace("frame_rotate_failed", [("visualFrameId", h.visualFrameId)])
                }
                inflight.clear()
                // A transport failure fails every following frame the same way;
                // stop rather than spin the store.
                if r.status == 0 { return out }
            }
        }
        return out
    }

    private static func headIdentity(_ queue: FrameDeliveryQueue) -> String? {
        guard let head = queue.readAll().first,
              let (h, b) = FrameRecord.decode(head) else { return nil }
        return "\(h.sessionId)|\(h.visualFrameId)|\(h.contentHash)|\(b.count)"
    }
}

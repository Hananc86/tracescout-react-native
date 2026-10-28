import Foundation

/// THE WINDOWED FRAME DRAIN, AS A PURE REDUCER.
///
/// ## Why this shape
///
/// The sequential drain calls a BLOCKING `post` once per iteration, so its
/// ceiling is `1 / T2`. Measured physically: T2 p50 768 ms => 1.30 fps, against
/// a 5.5 fps producer. The backlog can only grow.
///
/// A window needs real concurrency, and real concurrency in a unit test is a
/// race in a unit test — the same reason the snapshot-storm loop was never
/// caught by one. So the DECISIONS live here as a reducer with no dispatch, no
/// URLSession and no clock of its own:
///
/// step(.) -> what to start now, what just died of old age
/// complete(.) -> what one finished operation means
///
/// `RnTransport` supplies the concurrency and feeds completions back in; the
/// benchmark supplies a virtual clock and a simulated network. Both drive the
/// SAME code. What that deliberately does NOT cover is the dispatch/URLSession
/// adapter itself, which is stated rather than implied.
///
/// ## What carries over unchanged
///
/// * `FrameDelivery.ackRefusal` — the ONE settlement rule, shared with the
/// single-flight pipeline and with the Kotlin twin. A descriptor is fed to
/// it as a `FrameInFlight`; there is no second copy of the rule.
/// * `FrameDelivery.settle(queue:identity:)` — already position-independent,
/// which is exactly what out-of-order completion needs. It is not ASSUMED to
/// carry over: `FrameWindowTests` drives it under concurrent completion.
/// * Duplication over loss, never removing bytes before a bound
/// acknowledgement, and the typed refusal vocabulary.
///
/// ## What is deliberately GONE
///
/// **The append-first rotation.** It existed so one dead upload could not block
/// every later frame in a strict FIFO. A window makes that structural: a stuck
/// frame occupies one slot, the other N-1 keep moving, and the queue is never
/// rewritten to make progress. Removing it also removes a store rewrite per
/// retry and the crash-window duplicate it deliberately accepted. The bytes now
/// never move until they settle.
public enum FrameWindowPump {

    /// The maximum number of attempts one frame may consume before its bytes
    /// are given up. Unchanged from the sequential pipeline.
    public static let maxAttempts = FrameDelivery.maxAttempts

    /// One operation the caller must now start.
    public struct Start: Equatable {
        public let header: FrameHeader
        public let bytes: Data
        public let descriptor: FrameOperationDescriptor
        public var ref: FrameOperationRef { descriptor.ref }
    }

    public struct Step {
        /// Operations to fire, in queue order.
        public var starts: [Start] = []
        /// Operations whose deadline passed. Their slots are already released
        /// and their refs are already fenced out.
        public var expired: [FrameOperationDescriptor] = []
        /// A descriptor could not be persisted, so NOTHING was sent and no
        /// further operation may be started this step.
        public var failedClosed = false
        /// The lane was reclaimed under this pass.
        public var laneLost = false
        /// Records removed because they could not be decoded.
        public var unreadable = 0
        /// Frames given up after `maxAttempts`.
        public var exhausted = 0
        /// The frame store's read is UNTRUSTWORTHY, so every settlement this
        /// pass could attempt will refuse.
        ///
        /// IT IS REPORTED EVEN WHEN NOTHING ELSE HAPPENS. A store whose
        /// every frame failed authentication reads as EMPTY, so the walk below
        /// finds nothing, admits nothing, settles nothing and would otherwise
        /// return a Step indistinguishable from an idle pass over an empty
        /// queue — while the bytes sit on disk and can never drain. The wedge
        /// must not be silent.
        public var storeUntrustworthy: StoreIntegrity?
        /// Frames whose settlement the STORE refused — the record is still
        /// queued.
        ///
        /// ITS OWN FIELD, never folded into `exhausted`. Giving up on bytes
        /// and being UNABLE to drop them are opposite facts about the same
        /// frame: one says the queue advanced, the other says it did not, and
        /// one number for both hides a wedged store behind an ordinary
        /// give-up.
        public var unremoved = 0
        /// Descriptors released because their frame is no longer in the queue.
        public var reconciled = 0
        /// Frames NOT admitted because their session is known to be PENDING
        /// registration. They spent no attempt, no deadline and no slot.
        public var blockedByRegistration = 0
        /// Frames NOT admitted because they are inside a dependency cooldown.
        public var coolingDown = 0
        /// The earliest cooldown expiry among skipped frames, so a caller can
        /// schedule a wake-up instead of polling. Nil when nothing is cooling.
        public var nextEligibleMs: Int64?
        /// True when the queue holds nothing this step could start — either it
        /// is empty, or every remaining frame already has a live operation, or
        /// every remaining frame is waiting on a dependency.
        ///
        /// A REGISTRATION-BLOCKED OR COOLING FRAME MAKES THE PASS IDLE, not
        /// busy. It cannot be started at any capacity, so treating it as
        /// startable-if-only-there-were-room would spin the pass against work it
        /// is structurally unable to do.
        public var idle = false
    }

    /// What one completion meant. Every case except `fenced` mutated something.
    public enum Disposition: Equatable {
        /// The fence refused it: a superseded generation, or a late callback
        /// from an earlier attempt at the same frame. NOTHING was mutated.
        case fenced
        case delivered
        case duplicate
        /// A record-level permanent refusal. The bytes can never succeed.
        case refused(String)
        /// The server settled it and the container refused to rewrite. The
        /// record is still there and will be posted again.
        case unremovable
        /// Not a settlement: the bytes stay, the slot is freed, the attempt is
        /// spent. The reason is the typed protocol code, never a response body.
        case retained(String)
        /// THE SERVER DOES NOT KNOW THIS SESSION YET.
        ///
        /// Bytes retained, descriptor retained, slot released — and the normal
        /// attempt counter is GIVEN BACK, because the server never evaluated the
        /// frame. It can never settle as `exhausted`. A bounded, escalating
        /// cooldown is written into the descriptor so the fallback cannot become
        /// a zero-delay retry loop.
        case sessionDependencyPending
    }

    /// Running totals a caller reports and a benchmark grades.
    public struct Metrics: Equatable {
        public var delivered = 0
        public var duplicate = 0
        public var refused = 0
        public var retained = 0
        public var unreadable = 0
        public var exhausted = 0
        public var unremoved = 0
        public var reconciled = 0
        /// Operations abandoned by their deadline.
        public var timedOut = 0
        /// Completions refused `404 session_not_found`. These spent NO normal
        /// attempt; the field exists so that fact is reportable rather than
        /// invisible.
        public var sessionDependencyPending = 0
        /// Frames skipped this pass because their session is PENDING.
        public var blockedByRegistration = 0
        /// Frames skipped this pass because of a dependency cooldown.
        public var coolingDown = 0
        /// Earliest cooldown expiry seen, for scheduling a wake-up.
        public var nextEligibleMs: Int64?
        /// Completions the fence refused. MUST stay at zero for a stale
        /// callback to have been harmless — a non-zero value is not a failure,
        /// it is the fence doing its job, and the tests assert the difference.
        public var fenced = 0
        /// The largest number of simultaneously live operations observed.
        public var maxLiveOperations = 0
        public var laneLost = false
        public var failedClosed = false
        /// Folded from every Step in the pass. Non-nil means the frame store
        /// refused, or would refuse, every removal — the lane's half of the
        /// fail-closed rule, reported rather than inferred from a count that
        /// stopped moving.
        public var storeUntrustworthy: StoreIntegrity?
        public init() {}
    }

    public typealias Trace = FrameDelivery.Trace

    // ────────────────────────────────────────────────────────────────── step

    /// Decide what to start at `nowMs`.
    ///
    /// ADMISSION WALKS THE QUEUE IN ORDER AND SKIPS WHAT IS ALREADY LIVE.
    /// It never reorders and never rewrites the store: replay order is
    /// `monotonicNanos -> sequence` and belongs to the RECORDS, not to the
    /// network. A frame that keeps failing simply keeps its position while the
    /// frames behind it are admitted past it.
    ///
    /// THE REGISTRATION BARRIER SKIPS, IT NEVER BREAKS. Session A waiting on
    /// its opener ACK must not stop session B's frames from draining — turning a
    /// dependency barrier into a new head-of-line block would trade one defect
    /// for another. `registration` is consulted per frame and a PENDING answer
    /// only removes THAT frame from this pass.
    ///
    /// `registration` defaults to `.unknown` (no barrier) so a caller that has
    /// not wired a registry is exactly as it was. That default is safe because
    /// the barrier is an OPTIMISATION over the dependency disposition in
    /// `complete`, which is what actually protects the attempt budget.
    public static func step(queue: FrameDeliveryQueue,
                            window: inout FrameWindow,
                            store: FrameWindowStore,
                            nowMs: Int64,
                            stillOwnsLane: () -> Bool = { true },
                            registration: (String) -> SessionRegistrationState = { _ in .unknown },
                            trace: Trace = { _, _ in }) -> Step {
        var out = Step()
        guard stillOwnsLane() else { out.laneLost = true; return out }

        // Read ONCE, before anything else, and carry it: every decision below
        // that removes bytes will refuse, and an operator reading this Step has
        // to be able to see why.
        let integrity = queue.integrity()
        if !integrity.isTrustworthy {
            out.storeUntrustworthy = integrity
            trace("frame_store_untrustworthy", [("reason", integrity.rawValue)])
        }

        // 1. Deadlines first. A slot held by a dead operation is a slot the
        // frames behind it cannot use, and freeing it is the whole reason
        // the deadline exists.
        out.expired = window.expire(nowMs: nowMs)
        for d in out.expired {
            trace("frame_op_deadline", [("visualFrameId", d.visualFrameId),
                                        ("attempt", String(d.attempt)),
                                        ("sequence", String(d.operationSequence))])
        }

        // 2. An undecodable record cannot be posted and cannot be repaired.
        // Removing it is not a loss. Removed by POSITION because it has no
        // identity to remove it by — that is what undecodable means. Done
        // FIRST so every index used below refers to the settled queue.
        while let first = queue.readAll().first, FrameRecord.peek(first) == nil {
            guard queue.removeFirst(1) > 0 else { break }
            out.unreadable += 1
        }

        // 3. Scan the queue ONCE for identities.
        // `peek`, NOT `decode`. The walk needs each record's IDENTITY;
        // `decode` copies its frame bytes out to produce one, so a full walk
        // of a 4 MiB store would copy megabytes on every step. The bytes are
        // read ONLY for the records actually admitted, below.
        let records = queue.readAll()
        var scanned: [(Int, FrameHeader, Int, String)] = []
        var identities = Set<String>()
        for (i, r) in records.enumerated() {
            guard let (h, n) = FrameRecord.peek(r) else { continue }
            scanned.append((i, h, n, FrameRecord.identity(h, payloadBytes: n)))
            identities.insert(FrameRecord.identity(h, payloadBytes: n))
        }

        // 4. Reconcile the durable set against the live queue. A descriptor
        // whose frame is gone describes delivery state for bytes that no
        // longer exist — released with NO request, exactly as the
        // single-flight pipeline reconciles a mismatched descriptor.
        for d in store.loadAll() where !identities.contains(d.frameIdentity()) {
            store.release(frameIdentity: d.frameIdentity())
            out.reconciled += 1
            trace("frame_op_reconciled", [("visualFrameId", d.visualFrameId)])
        }

        // 5. Admit, in queue order.
        let descriptors = Dictionary(uniqueKeysWithValues:
            store.loadAll().map { ($0.frameIdentity(), $0) })
        var admittedAny = false
        var couldAdmit = false
        for (index, h, _, identity) in scanned {
            guard !window.isLive(frameIdentity: identity) else { continue }

            // THE BARRIER, AND THE COOLDOWN, COME BEFORE `couldAdmit`.
            // Neither frame can be started at ANY capacity, so counting them as
            // "would have started if there were room" would make a pass that can
            // do nothing report itself as busy and spin.
            //
            // `continue`, never `break`: the frames behind this one may belong to
            // a different session with nothing to wait for.
            if registration(h.sessionId) == .pending {
                out.blockedByRegistration += 1
                trace("frame_op_awaiting_session", [("visualFrameId", h.visualFrameId),
                                                    ("sessionId", h.sessionId)])
                continue
            }
            if let d = descriptors[identity], d.notBeforeMs > nowMs {
                out.coolingDown += 1
                out.nextEligibleMs = Swift.min(out.nextEligibleMs ?? d.notBeforeMs, d.notBeforeMs)
                continue
            }

            // `couldAdmit` IS SET BEFORE THE CAPACITY GUARD, and the order is
            // the whole meaning of `idle`. A FULL window with queued work has
            // nothing to start RIGHT NOW but is emphatically not idle; ordering
            // the capacity guard first made those two facts identical, so a
            // caller could not tell "the queue is empty" from "every slot is
            // busy". CI caught it on the first run.
            couldAdmit = true
            guard window.hasCapacity else { break }
            let prior = descriptors[identity]?.attempt ?? h.attempts
            if prior >= maxAttempts {
                // Give up these bytes. Settled by IDENTITY, so a store the
                // freshness policy has since reordered still loses the right
                // record.
                //
                // `_ = FrameDelivery.settle(.)` DISCARDED THE ONE FACT
                // THIS RETURNS, and this is the harshest place to discard it.
                // On a store that refuses the rewrite the record is STILL
                // THERE — so releasing the descriptor forgets the attempt
                // history of bytes that will be admitted again on the very next
                // step, and `exhausted` claims we gave up something we did not
                // in fact give up. Both `complete` paths already honour
                // `.unremovable`; this one did not.
                if case .unremovable = FrameDelivery.settle(queue: queue, identity: identity) {
                    out.unremoved += 1
                    trace("frame_op_unremovable", [("by", "exhausted"),
                                                   ("visualFrameId", h.visualFrameId),
                                                   ("attempt", String(prior))])
                    continue
                }
                store.release(frameIdentity: identity)
                out.exhausted += 1
                trace("frame_op_settled", [("by", "exhausted"),
                                           ("visualFrameId", h.visualFrameId),
                                           ("attempt", String(prior))])
                continue
            }
            // The bytes are read HERE, for an admitted frame only. The index is
            // re-validated because the walk snapshotted `readAll`; a record
            // that moved or vanished under us is skipped rather than posted
            // from a stale position.
            guard index < records.count,
                  let (rh, bytes) = FrameRecord.decode(records[index]),
                  FrameRecord.identity(rh, payloadBytes: bytes.count) == identity
            else { continue }
            guard let d = window.admit(sessionId: h.sessionId, visualFrameId: h.visualFrameId,
                                       contentHash: h.contentHash, byteSize: bytes.count,
                                       attempt: prior + 1, nowMs: nowMs,
                                       dependencyWaits: descriptors[identity]?.dependencyWaits ?? 0)
            else { break }
            // FAIL CLOSED, PER OPERATION. Sending on the strength of a
            // descriptor that is not durably readable back is exactly the
            // unresolved state no restart can recover from. The slot is handed
            // back so a refusal cannot leak 1/N of the window.
            guard store.save(d) else {
                window.abandon(d.ref)
                out.failedClosed = true
                trace("frame_op_freeze_failed", [("visualFrameId", h.visualFrameId)])
                break
            }
            trace("frame_op_frozen", [
                ("sessionId", h.sessionId), ("visualFrameId", h.visualFrameId),
                ("contentHash", h.contentHash), ("byteSize", String(bytes.count)),
                ("attempt", String(d.attempt)), ("generation", String(d.generation)),
                ("sequence", String(d.operationSequence))])
            out.starts.append(Start(header: h, bytes: bytes, descriptor: d))
            admittedAny = true
        }
        out.idle = !admittedAny && !couldAdmit
        return out
    }

    // ────────────────────────────────────────────────────────────── complete

    /// Apply ONE completion.
    ///
    /// THE FENCE IS THE FIRST THING THAT HAPPENS AND IT GUARDS EVERY
    /// MUTATION. A refused completion does not settle its frame, does not
    /// release another operation's slot, does not remove queue bytes, does not
    /// touch the durable set and does not release the lane owner.
    ///
    /// ONLY ITS OWN FRAME. The acknowledgement is matched against the
    /// descriptor this operation was frozen with, and settlement removes the
    /// record with THAT identity wherever it now resides. No queue prefix is
    /// advanced because a later frame happened to finish first — out-of-order
    /// completion is the expected case, not an anomaly.
    @discardableResult
    public static func complete(ref: FrameOperationRef,
                                result: FrameDelivery.PostResult,
                                queue: FrameDeliveryQueue,
                                window: inout FrameWindow,
                                store: FrameWindowStore,
                                nowMs: Int64 = 0,
                                /// Product default; the sweep that SELECTED it
                                /// passes its own so it drives the real schedule
                                /// instead of re-implementing one.
                                backoffBaseMs: Int64 = SessionDependencyBackoff.baseMs,
                                trace: Trace = { _, _ in }) -> Disposition {
        guard let d = window.operation(ref), window.complete(ref) else {
            trace("frame_op_fenced", [("generation", String(ref.generation)),
                                      ("sequence", String(ref.sequence))])
            return .fenced
        }
        let identity = d.frameIdentity()

        // THE DEPENDENCY IS RECOGNISED BY THE BODY CODE, NEVER BY THE BARE
        // 404. A 404 is one status with several meanings on this route — a dark
        // ingest gate and a disabled visual-frame flag both answer 404 too, and
        // treating those as a refundable dependency would give a frame an
        // unlimited budget against a refusal that will never change. Only
        // `session_not_found` names the prerequisite the client can actually
        // wait for; every other 404 keeps its existing `retained` semantics and
        // its spent attempt.
        if result.status == 404 && result.code == FrameDelivery.sessionDependencyCode {
            let refunded = d.afterSessionDependencyWait(nowMs: nowMs, backoffBaseMs: backoffBaseMs)
            // Persisting the refund is best-effort: if it fails the frame simply
            // keeps the spent attempt, which is the pre-fix behaviour and is
            // still safe. It is NOT fail-closed, because nothing was sent on the
            // strength of this write.
            store.save(refunded)
            trace("frame_op_session_pending", [
                ("visualFrameId", d.visualFrameId),
                ("sessionId", d.sessionId),
                ("dependencyWaits", String(refunded.dependencyWaits)),
                ("attempt", String(refunded.attempt)),
                ("backoffMs", String(refunded.notBeforeMs - nowMs))])
            return .sessionDependencyPending
        }
        let ackFail = (200...299).contains(result.status)
            ? FrameDelivery.ackRefusal(result.ack, d.asInFlight())
            : "ack_absent"
        let settled = (200...299).contains(result.status) && ackFail == nil
        let permanent = (400...499).contains(result.status)
            && result.status != 404 && result.status != 408 && result.status != 429

        if settled, let ack = result.ack {
            switch FrameDelivery.settle(queue: queue, identity: identity) {
            case .removed(let index):
                store.release(frameIdentity: identity)
                trace("frame_op_settled", [("by", ack.state),
                                           ("visualFrameId", d.visualFrameId),
                                           ("attempt", String(d.attempt)),
                                           ("index", String(index))])
                return ack.state == "duplicate" ? .duplicate : .delivered
            case .alreadyAbsent:
                // A lawful concurrent mutation (freshness eviction, an earlier
                // settlement) already took it. Re-inserting would resurrect
                // bytes something chose to drop.
                store.release(frameIdentity: identity)
                trace("frame_op_settled_absent", [("by", ack.state),
                                                  ("visualFrameId", d.visualFrameId)])
                return ack.state == "duplicate" ? .duplicate : .delivered
            case .unremovable:
                // A settlement we could not perform is not a settlement: the
                // descriptor STAYS so the frame is retried rather than being
                // silently forgotten while its bytes are still queued.
                trace("frame_op_unremovable", [("visualFrameId", d.visualFrameId)])
                return .unremovable
            }
        }
        if permanent {
            if case .unremovable = FrameDelivery.settle(queue: queue, identity: identity) {
                trace("frame_op_unremovable", [("visualFrameId", d.visualFrameId)])
                return .unremovable
            }
            store.release(frameIdentity: identity)
            trace("frame_op_settled", [("by", "refused"),
                                       ("visualFrameId", d.visualFrameId),
                                       ("code", result.code ?? "")])
            return .refused(result.code ?? "permanent")
        }
        // Everything else RETAINS: a bare 2xx with no bound acknowledgement, a
        // mismatched identity, a precondition, a transport failure. The bytes
        // stay exactly where they are, the slot is free, and the attempt the
        // descriptor already recorded is spent.
        let reason = (200...299).contains(result.status)
            ? (ackFail ?? "ack_absent")
            : (result.status == 0 ? "transport" : "status_\(result.status)")
        trace("frame_op_retained", [("visualFrameId", d.visualFrameId),
                                    ("reason", reason),
                                    ("attempt", String(d.attempt))])
        return .retained(reason)
    }

    /// Fold a disposition into the running totals, so a caller cannot invent a
    /// second accounting of what the reducer already decided.
    public static func account(_ disposition: Disposition, into m: inout Metrics) {
        switch disposition {
        case .fenced: m.fenced += 1
        case .delivered: m.delivered += 1
        case .duplicate: m.duplicate += 1
        case .refused: m.refused += 1
        case .unremovable: m.unremoved += 1
        case .retained: m.retained += 1
        // ITS OWN FIELD, never folded into `retained`. A dependency wait and
        // a spent-attempt retention differ in exactly the thing that matters —
        // whether the budget moved — and one number for both would hide the
        // property this tranche exists to establish.
        case .sessionDependencyPending: m.sessionDependencyPending += 1
        }
    }

    public static func account(_ step: Step, into m: inout Metrics) {
        m.unreadable += step.unreadable
        m.exhausted += step.exhausted
        m.unremoved += step.unremoved
        if let u = step.storeUntrustworthy { m.storeUntrustworthy = u }
        m.reconciled += step.reconciled
        m.timedOut += step.expired.count
        m.blockedByRegistration += step.blockedByRegistration
        m.coolingDown += step.coolingDown
        if let n = step.nextEligibleMs {
            m.nextEligibleMs = Swift.min(m.nextEligibleMs ?? n, n)
        }
        if step.laneLost { m.laneLost = true }
        if step.failedClosed { m.failedClosed = true }
    }
}

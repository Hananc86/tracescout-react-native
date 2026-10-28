import Foundation

/// The ONE durable upload state machine, restated for iOS: contiguous
/// session-owned slicing, deterministic batch identity, `mobile-ack-1` identity
/// matching, prefix re-check, and `removeFirst(N)` — nothing else.
///
/// **Producer-agnostic by construction.** It accepts immutable, already
/// serialised record bytes and knows nothing about who produced them: no
/// `sdkFamily`, `sdkVersion`, `platform` or `hostPlatform` appears here, and
/// there is no hook through which a caller could alter batch identity or
/// acknowledgement semantics. Producer identity stays compile-time-owned.
///
/// Every rule below records a defect paid for on a real Android device; the
/// restatement deliberately changes none of them.
public final class DurableUploadPipeline {

    public typealias ChunkPoster = (_ projectId: String, _ body: Data) -> UploadResult

    private let store: RecordStore
    private let park: ParkStore
    private let poster: ChunkPoster
    private let projectId: String
    private let maxRetries: Int
    private let sleeper: (Int64) -> Void
    private let nowMillis: () -> Int64
    private let trace: RecoveryTrace
    /// The unresolved-batch descriptor (see `InFlightStore`). Defaults to
    /// process-lifetime memory so an unwired caller still gets the in-process
    /// half of the invariant and is never worse off; `RnTransport` wires the
    /// durable file store so the crash/relaunch clause holds too.
    private let inflight: InFlightStore
    /// Called with the batch's session id after an `accepted`/`duplicate`
    /// acknowledgement, and ONLY then.
    ///
    /// THIS IS THE SESSION-REGISTRATION SIGNAL, and the placement is the
    /// contract. The server originates a mobile session row DURING chunk
    /// processing, so "the request started" is not something a client may act
    /// on: the request may still fail, and the row may or may not exist. The
    /// acknowledgement is the first client-observable point at which the
    /// prerequisite is established — and because the server refuses a chunk for
    /// an unknown session unless that chunk carries the opener, an ack for
    /// session S is itself proof that S's row exists.
    private let onBatchAcknowledged: (String) -> Void

    public private(set) var quarantinedCount = 0
    public private(set) var parkedCount = 0
    public private(set) var lastStatus = 0
    public private(set) var lastCode: String?

    /// The bounds the NEXT batch is built with. They start at the shared
    /// defaults and are only ever narrowed, by a 413.
    ///
    /// PROCESS-LOCAL, AND RESET BY ANY SUCCESSFUL ACKNOWLEDGEMENT. A 413 can
    /// come from a proxy with a smaller limit than the API's, or from a
    /// misconfiguration corrected an hour later; persisting a shrink would let
    /// one such refusal permanently halve a healthy client's batching with
    /// nothing able to restore it. A successful ack is proof the current bounds
    /// work, so it is the honest place to forget the shrink.
    private var resliceBudgetBytes = ChunkBounds.defaultBodyBudgetBytes
    private var resliceMaxRecords = ChunkBounds.maxRecordsPerChunk

    /// Never wider than the shared defaults — a shrink only ever narrows.
    private var effectiveBudgetBytes: Int {
        min(resliceBudgetBytes, ChunkBounds.defaultBodyBudgetBytes)
    }
    private var effectiveMaxRecords: Int {
        min(resliceMaxRecords, ChunkBounds.maxRecordsPerChunk)
    }

    public init(store: RecordStore,
                park: ParkStore = NoParkStore(),
                poster: @escaping ChunkPoster,
                projectId: String,
                maxRetries: Int = 4,
                trace: RecoveryTrace = .shared,
                sleeper: @escaping (Int64) -> Void = { Thread.sleep(forTimeInterval: Double($0) / 1000.0) },
                nowMillis: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                inflight: InFlightStore = MemoryInFlightStore(),
                onBatchAcknowledged: @escaping (String) -> Void = { _ in }) {
        self.store = store; self.park = park; self.poster = poster
        self.projectId = projectId; self.maxRetries = maxRetries
        self.trace = trace; self.sleeper = sleeper; self.nowMillis = nowMillis
        self.inflight = inflight
        self.onBatchAcknowledged = onBatchAcknowledged
    }

    public enum Outcome {
        case idle
        case delivered
        case retained(String)
        case parked
        case quarantined
        case exhausted
        /// A permanently locally undeliverable slice was archived. The queue
        /// ADVANCED, so the caller may continue immediately rather than sleep.
        case terminalMoved
        /// The server refused the submitted BOUNDARY (413 / too many events).
        /// Nothing was stored, nothing was removed, the durable records are
        /// intact — and the unresolved descriptor has been ABANDONED so the
        /// next pass builds a SMALLER batch from the same bytes. The caller
        /// should continue immediately: sleeping would only delay a batch we
        /// have already narrowed.
        case resliced
        /// The slice is durably classified terminal and its LOCAL archival has
        /// not completed. The retry owed is the local move, never a POST.
        case terminalPending(String)
        /// Durably terminal and locally BLOCKED — preservation capacity
        /// exhausted, or no park store wired. Bytes retained, and owed **no
        /// automatic retry of any kind**: retrying a condition with no
        /// mechanism to change would be an infinite local loop.
        case terminalBlocked(String)
    }

    /// Is this session ALREADY durably known terminal?
    ///
    /// Read from the park store, which is the durable record of that knowledge.
    /// This is what lets a LATER slice of the same dead session be archived
    /// with ZERO network: the device learned the fact once and must not ask a
    /// server that can only ever repeat it.
    private func sessionAlreadyTerminal(_ sessionId: String) -> Bool {
        let manifest = park.entriesRead()
        guard manifest.isTrustworthy else {
            // NO is still the right ANSWER — guessing YES would park a
            // session on ignorance, the one thing this policy promises it can
            // never do. What changes is that the COST is stated: the device
            // re-asks the server for a fact it had already durably learned,
            // which is precisely the zero-network property this lookup exists
            // to create. A silent `false` made that indistinguishable from a
            // session that was simply not terminal.
            trace.log("park_terminal_lookup_untrusted",
                      [("reason", manifest.integrity.rawValue)])
            return false
        }
        return manifest.entries.contains {
            $0.sessionId == sessionId
                && $0.code == SessionOriginPolicy.sessionOriginUnrecoverable
        }
    }

    /// Persist the terminal disposition, THEN move the bytes.
    ///
    /// The order is the whole point. If the process dies after step 1 the next
    /// launch reads the descriptor, sees the reason, and RESUMES THE PARK — it
    /// does not POST again. Persisting after the move would leave a window in
    /// which a restart forgets the classification and re-asks a server that can
    /// only ever say no.
    ///
    /// Returns `.moved` only when the bytes are durably parked AND the active
    /// prefix is gone. Every other path leaves the active bytes exactly where
    /// they are: no delete, no false success, no quarantine classification.
    private func moveTerminal(_ slice: [Data], _ batch: BatchIdentity,
                              _ pending: InFlightBatch?) -> ParkResult {
        let reason = SessionOriginPolicy.sessionOriginUnrecoverable
        if pending?.terminalReason != reason {
            let base = pending ?? InFlightBatch(
                sessionId: batch.sessionId, chunkId: batch.chunkId,
                firstSequence: batch.firstSequence, lastSequence: batch.lastSequence,
                recordCount: slice.count, contentSha256: "",
                // `BatchIdentity.digests` is PRIVATE — recompute from the
                // slice with the same helper the ordinary descriptor path uses,
                // so the boundary this records is the one the retry re-checks.
                digests: slice.map { BatchIdentity.sha256Hex($0) },
                firstAttemptAtMs: nowMillis(), attempts: 0)
            let d = InFlightBatch(
                sessionId: base.sessionId, chunkId: base.chunkId,
                firstSequence: base.firstSequence, lastSequence: base.lastSequence,
                recordCount: base.recordCount, contentSha256: base.contentSha256,
                digests: base.digests, firstAttemptAtMs: base.firstAttemptAtMs,
                attempts: base.attempts, terminalReason: reason)
            guard inflight.save(d) else {
                // The classification is NOT durable, so the move must not
                // proceed: a park completed without a persisted reason could be
                // interrupted and forgotten. Retain and report pending.
                trace.log("terminal_reason_not_persisted",
                          [("chunk", String(batch.chunkId.prefix(12)))])
                return .notParked
            }
            trace.log("terminal_reason_persisted", [
                ("reason", reason), ("session", batch.sessionId), ("chunk", batch.chunkId),
                ("firstSequence", batch.firstSequence), ("lastSequence", batch.lastSequence),
            ])
        }
        return parkPrefix(slice, batch, reason)
    }

    @discardableResult
    public func drainOnce() -> Outcome {
        // Settle any crash-window residue BEFORE reading the active head: an
        // interruption between "parked copy durable" and "removeFirst" would
        // otherwise leave one batch in both stores.
        reconcileParked()

        let records = store.readRecords()
        guard !records.isEmpty else { return .idle }

        // An UNRESOLVED batch owns the head of the queue until it settles.
        // Re-forming the prefix from the live queue is exactly what committed
        // sequences 19–26 twice on a real iPhone: the submission had not
        // settled, three records were appended, and the next drain sent a
        // LONGER batch the server could not recognise as the same work.
        var pending = inflight.load()
        if let p = pending, !resumeStillMatches(p, records) {
            trace.log("inflight_stale_discarded", [("chunk", String(p.chunkId.prefix(12))),
                                                   ("want", String(p.recordCount)),
                                                   ("have", String(records.count))])
            inflight.clear()
            pending = nil
        }

        let owner: String
        let slice: [Data]
        if let p = pending {
            owner = p.sessionId
            slice = Array(records.prefix(p.recordCount))
            trace.log("inflight_resumed", [
                ("session", p.sessionId), ("chunk", p.chunkId),
                ("firstSequence", p.firstSequence), ("lastSequence", p.lastSequence),
                ("recordCount", String(p.recordCount)),
                ("bodySha256", p.contentSha256),
                ("prefixFingerprint", Self.prefixFingerprint(p.digests)),
                ("attempts", String(p.attempts)),
            ])
        } else {
            guard let (o, sl) = BatchIdentity.sessionOwnedPrefix(
                records,
                maxRecords: effectiveMaxRecords,
                budgetBytes: effectiveBudgetBytes) else {
                trace.log("slice_unreadable", [("count", String(records.count))])
                return .idle
            }
            owner = o; slice = sl
        }
        let batch = BatchIdentity.of(sessionId: owner, records: slice)

        // THE CROSS-PROCESS CLOSE. A descriptor carrying a terminal reason
        // was classified on an EARLIER pass — possibly in a process that died
        // before the park completed. Resume the local move; do NOT ask the
        // server again. Without this the loop survives restart, which is
        // exactly where it was surviving.
        if pending?.terminalReason == SessionOriginPolicy.sessionOriginUnrecoverable {
            trace.log("terminal_resume_no_post", [
                ("reason", SessionOriginPolicy.sessionOriginUnrecoverable),
                ("chunk", batch.chunkId), ("recordCount", String(slice.count)),
            ])
            switch parkPrefix(slice, batch, SessionOriginPolicy.sessionOriginUnrecoverable) {
            case .moved:
                inflight.clear(); return .terminalMoved
            case .parkedNotRemoved(let why):
                // Preserved, but the queue did NOT advance. `.terminalMoved`
                // would tell the lane to try the next slice immediately over a
                // head that is still here.
                return .terminalPending(why)
            case .notParked:
                return terminalPendingOutcome("session_origin_park_pending")
            }
        }

        // ZERO HTTP FOR A SESSION ALREADY PROVEN TERMINAL. The device
        // learned this fact once, durably; a later slice of the same dead
        // session must not re-ask. The `firstSequence > 0` clause still applies
        // inside the policy, so a slice carrying the opener is never archived
        // by this path.
        if sessionAlreadyTerminal(owner),
           SessionOriginPolicy.verdict(SessionOriginPolicy.Evidence(
                sessionPreconditionRefusal: false,
                firstSequence: Int64(batch.firstSequence) ?? -1,
                openerPresentInActiveStore: false,
                openerRecoverableElsewhere: false,
                sessionAlreadyTerminal: true)) == .terminalSessionOrigin {
            trace.log("terminal_known_no_post", [
                ("session", owner), ("chunk", String(batch.chunkId.prefix(12))),
                ("recordCount", String(slice.count)),
            ])
            switch moveTerminal(slice, batch, pending) {
            case .moved: return .terminalMoved
            case .parkedNotRemoved(let why): return .terminalPending(why)
            case .notParked: return terminalPendingOutcome("session_origin_park_pending")
            }
        }
        // A RECORD THAT CANNOT FIT IN ANY LEGAL BODY IS PRESERVED, NEVER
        // DELETED — and it is decided BEFORE a request, because the answer is
        // arithmetic about the record and the server's known body limit, not
        // something a server needs to be asked.
        //
        // Re-slicing cannot help it: the batch is already one record, and there
        // is nothing left to split. Retaining it in place would starve every
        // record behind it for the life of the install — the same strict-FIFO
        // starvation `PARKED_PRODUCER` exists to end — so the bytes MOVE to the
        // parked store through the existing crash-safe operation, with a typed
        // reason, and the queue advances.
        //
        // IT SITS AFTER THE TWO TERMINAL-SESSION BRANCHES ON PURPOSE. A
        // descriptor already classified `session_origin_unrecoverable`, or a
        // session already durably known terminal, is archived under THAT reason
        // the more specific and already-persisted one — rather than being
        // relabelled by a size check that happens to run first.
        if slice.count == 1,
           !ChunkBounds.headDeliverable(
                sessionIdUtf8Len: ChunkBounds.encodedSessionIdLength(owner),
                firstRecordSize: slice[0].count) {
            trace.log("record_body_too_large", [
                ("session", owner), ("chunk", String(batch.chunkId.prefix(12))),
                ("recordBytes", String(slice[0].count)),
            ])
            switch parkPrefix(slice, batch, ChunkBounds.recordBodyTooLarge) {
            case .moved:
                inflight.clear()
                return .terminalMoved
            case .parkedNotRemoved(let why):
                // Phase-0 integration: the parked copy is durable but the active
                // head did not move, so the descriptor is deliberately NOT
                // cleared (clearing is how a batch gets extended) and the lane
                // owes the LOCAL move, never a POST.
                return .terminalPending(why)
            case .notParked:
                // Local archival did not complete. The bytes stay exactly where
                // they are; the retry owed is the LOCAL move, never a POST of a
                // body we have already computed to be unacceptable.
                return terminalPendingOutcome("record_body_too_large_park_pending")
            }
        }

        // The canonical chunk envelope is {sessionId, chunkId, events}.
        //
        // THE DEFECT THIS FIXES. The envelope used to carry only
        // {events, chunkId}. The server reads `body.sessionId` to ORIGINATE a
        // native session (a native app has no browser chunk to create its row),
        // so every chunk from a real iPhone hit
        // `sessions.session_id NOT NULL` and came back 503
        // `session_origination_failed`. Auth, allowlist, admission and the
        // opener's self-naming fields all PASSED — only the envelope was wrong,
        // so nothing upstream could see it. Android had always sent the correct
        // shape; the two producers simply disagreed about the container.
        //
        // Nothing was lost: 503 + retriable maps to `.retry`, so the queue was
        // retained rather than quarantined. But no iOS chunk could ever commit.
        //
        // The identity is read from `batch`, NOT from the local `owner`, even
        // though they are the same value here. `batch.chunkId` is derived from
        // it and `batch.matches(ack)` compares against it, so request identity,
        // chunk identity and ack identity are one typed fact with one name —
        // two names for one fact is how this class of bug starts.
        let body = Data(("{\"sessionId\":" + RnEnvelope.str(batch.sessionId) +
            ",\"chunkId\":" + RnEnvelope.str(batch.chunkId) +
            ",\"events\":[" +
            slice.map { String(data: $0, encoding: .utf8) ?? "" }.joined(separator: ",") +
            "]}").utf8)

        if let p = pending, batch.chunkId != p.chunkId ||
            BatchIdentity.sha256Hex(body) != p.contentSha256 {
            // The descriptor and the rebuilt bytes disagree. That must be
            // impossible after the digest re-check above; if it ever happens,
            // sending would break the very invariant this exists to hold.
            trace.log("inflight_rebuild_mismatch", [("chunk", String(p.chunkId.prefix(12)))])
            return .retained("inflight_rebuild_mismatch")
        }
        if pending == nil {
            // Freeze the identity BEFORE the first network attempt. A
            // descriptor written after the request cannot survive a crash
            // during it — the window the defect lives in.
            let d = InFlightBatch(
                sessionId: batch.sessionId, chunkId: batch.chunkId,
                firstSequence: batch.firstSequence, lastSequence: batch.lastSequence,
                recordCount: slice.count, contentSha256: BatchIdentity.sha256Hex(body),
                digests: slice.map { BatchIdentity.sha256Hex($0) },
                firstAttemptAtMs: nowMillis(), attempts: 0)
            if inflight.save(d) {
                // The ONE authoritative record that a batch was frozen, and
                // frozen BEFORE any network attempt. Sanitized: identity,
                // boundary and digests only — never a record body.
                trace.log("inflight_frozen", [
                    ("session", batch.sessionId),
                    ("chunk", batch.chunkId),
                    ("firstSequence", batch.firstSequence),
                    ("lastSequence", batch.lastSequence),
                    ("recordCount", String(slice.count)),
                    ("bodySha256", d.contentSha256),
                    ("prefixFingerprint", Self.prefixFingerprint(d.digests)),
                ])
            }
            guard inflight.save(d) else {
                // Fail CLOSED. Without a durable descriptor a crash during the
                // request would let the next drain extend this batch. Not
                // sending loses nothing: the records stay queued.
                trace.log("inflight_save_refused", [("chunk", String(batch.chunkId.prefix(12)))])
                return .retained("inflight_unavailable")
            }
        }

        trace.log("upload_raw_enter", [("count", String(slice.count)),
                                       ("chunk", String(batch.chunkId.prefix(12)))])
        var attempt = 1
        while attempt <= maxRetries {
            trace.log("transport_enter", [("attempt", String(attempt))])
            trace.log("http_request_started", [("attempt", String(attempt))])
            let r = poster(projectId, body)
            lastStatus = r.status; lastCode = r.code
            trace.log("http_response_received", [
                ("action", String(describing: r.action)),
                ("status", String(r.status)),
                ("code", r.code ?? ""),
                ("ackPresent", r.ack == nil ? "false" : "true"),
            ])
            switch r.action {
            case .success:
                // A 2xx with no ack, or an ack that does not describe THIS
                // batch, is NOT progress. No dequeue.
                //
                // an earlier change: AND IT IS NOT AN IMMEDIATE RETRY EITHER. This used to
                // `return.retained("ack_invalid")` at once, which released the
                // lane with NO backoff — so every enqueue's drain request
                // re-POSTed the identical frozen bytes (measured: the same chunk
                // 36× in ~120 s against a server returning misbound acks). An
                // unusable acknowledgement is a TRANSIENT server condition, so it
                // takes the SAME bounded backoff as `.retry` below: the queue and
                // the frozen descriptor stay exactly as they are, the lane is held
                // through `backoffMillis`, the next attempt re-presents the SAME
                // bytes, and past `maxRetries` the batch is `.exhausted` (retained).
                guard let ack = r.ack, ack.committed, batch.matches(ack) else {
                    trace.log("ack_invalid", [("ackPresent", r.ack == nil ? "false" : "true"),
                                              ("committed", "false")])
                    let wait = backoffMillis(attempt: attempt)
                    attempt += 1
                    if attempt > maxRetries {
                        trace.log("retries_exhausted", [("status", String(r.status)),
                                                        ("reason", "ack_invalid")])
                        return .exhausted
                    }
                    trace.log("retry_scheduled", [("waitMs", String(wait)), ("reason", "ack_invalid")])
                    sleeper(wait)
                    continue
                }
                trace.log("ack_valid", [("state", ack.state)])
                // A successful acknowledgement is proof the CURRENT bounds
                // work, so any shrink a 413 imposed is forgotten here. Without
                // this a single refusal — from a proxy, or from a
                // misconfiguration corrected an hour later — would permanently
                // halve a healthy client's batching with nothing able to
                // restore it.
                resliceBudgetBytes = ChunkBounds.defaultBodyBudgetBytes
                resliceMaxRecords = ChunkBounds.maxRecordsPerChunk
                // BEFORE the dequeue, and unconditionally on a valid ack.
                // The prerequisite is established by the SERVER's acceptance,
                // not by our bookkeeping succeeding afterwards: a
                // `prefix_changed` dequeue failure does not un-create the
                // session row, and gating the signal on it would leave the
                // frame lane blocked over a local store race.
                onBatchAcknowledged(batch.sessionId)
                // SETTLED — released here so records appended while this batch
                // was unresolved form the NEXT batch.
                trace.log("inflight_settled", [
                    ("by", ack.state), ("session", batch.sessionId), ("chunk", batch.chunkId),
                    ("firstSequence", batch.firstSequence), ("lastSequence", batch.lastSequence),
                    ("recordCount", String(slice.count)),
                ])
                inflight.clear()
                // `.delivered` IS A CLAIM ABOUT THE LOCAL STORE AS WELL AS
                // THE SERVER. The server did commit — that is why the
                // descriptor is settled above — but the records are still on
                // disk, so reporting delivery would let the caller advance past
                // a prefix it must re-present. The reason travels with it.
                if let why = dequeueExactly(slice.count, batch) {
                    return .retained(why.rawValue)
                }
                return .delivered
            case .retry:
                let wait = backoffMillis(attempt: attempt)
                attempt += 1
                if attempt > maxRetries {
                    trace.log("retries_exhausted", [("status", String(r.status))])
                    return .exhausted
                }
                trace.log("retry_scheduled", [("waitMs", String(wait))])
                sleeper(wait)
            case .terminal:
                switch terminalDisposition(status: r.status, code: r.code) {
                case .retainAuth:
                    trace.log("auth_rejected", [("status", String(r.status))])
                    return .retained("auth_rejected")
                case .retainFeatureGate:
                    // The in-flight descriptor is deliberately NOT cleared: the
                    // batch is unresolved, so the next drain must re-present
                    // THESE bytes rather than re-slice a queue that has grown.
                    trace.log("feature_gate_refused", [("status", String(r.status)), ("code", r.code ?? "")])
                    return .retained("feature_gate")
                case .retainSessionPrecondition:
                    // THE RESPONSE ALONE IS NOT THE TERMINAL FACT.
                    // `session_not_found` with the opener still queued is
                    // TRANSIENT — the next pass originates the session and
                    // everything drains. It becomes terminal only when this
                    // producer no longer possesses ANY local mechanism able to
                    // create that session, which is a fact about the durable
                    // store, not about the reply.
                    //
                    // The store is re-read HERE, at decision time, and the
                    // opener must be absent from BOTH that read and the slice's
                    // own snapshot. A record appended between them can only be
                    // a LATER record of the session (the opener is sequence 0
                    // and is emitted once), so requiring agreement cannot
                    // produce a false terminal. An in-memory session registry is
                    // deliberately NOT consulted: it is empty on every cold
                    // start, which is exactly the case this defect survives.
                    let live = store.readRecords()
                    // ABSENCE IS ONLY EVIDENCE WHEN THE READ WAS COMPLETE.
                    // `EncryptedFileStore` compactMaps away frames that fail GCM
                    // authentication and returns [] for a file it cannot read at
                    // all, so an opener destroyed by corruption is absent from
                    // this list in exactly the same way as an opener that never
                    // existed. Only `readIsComplete` separates them, and
                    // treating a lossy read as proof of absence is parking on
                    // ignorance — which the policy promises it can never do.
                    let trustworthy = store.readIsComplete()
                    if !trustworthy {
                        trace.log("terminal_evidence_untrustworthy", [("session", owner)])
                    }
                    let openerPresent =
                        !trustworthy
                        || records.contains { SessionOriginPolicy.recordOpensSession([UInt8]($0), owner) }
                        || live.contains { SessionOriginPolicy.recordOpensSession([UInt8]($0), owner) }
                    let ev = SessionOriginPolicy.Evidence(
                        sessionPreconditionRefusal: true,
                        firstSequence: Int64(batch.firstSequence) ?? -1,
                        openerPresentInActiveStore: openerPresent,
                        // The unresolved batch IS this slice, so it holds an
                        // opener only if the slice does — which the
                        // firstSequence clause already covers.
                        openerRecoverableElsewhere: false,
                        sessionAlreadyTerminal: sessionAlreadyTerminal(owner))
                    if SessionOriginPolicy.verdict(ev) == .terminalSessionOrigin {
                        trace.log("session_origin_unrecoverable", [
                            ("session", owner), ("firstSequence", batch.firstSequence),
                            ("chunk", String(batch.chunkId.prefix(12))),
                            ("recordCount", String(slice.count)),
                        ])
                        switch moveTerminal(slice, batch, pending) {
                        case .moved:
                            inflight.clear(); return .terminalMoved
                        case .parkedNotRemoved(let why):
                            return .terminalPending(why)
                        case .notParked:
                            return terminalPendingOutcome("session_origin_park_pending")
                        }
                    }
                    trace.log("session_precondition", [("code", r.code ?? "")])
                    return .retained("session_precondition")
                case .parkProducer:
                    switch parkPrefix(slice, batch, r.code) {
                    case .moved:
                        trace.log("inflight_settled", [("by", "parked"), ("chunk", batch.chunkId),
                                                       ("recordCount", String(slice.count))])
                        inflight.clear(); return .parked
                    case .parkedNotRemoved(let why):
                        // THE DESCRIPTOR IS DELIBERATELY NOT CLEARED. The
                        // active bytes are still the head of the queue, so the
                        // batch is unresolved: the next drain must re-present
                        // THESE bytes rather than re-slice a queue that has
                        // grown behind them. Clearing here is exactly how a
                        // batch gets extended.
                        return .retained(why)
                    case .notParked:
                        return .retained("park_refused")
                    }
                case .resliceBatch:
                    // The BOUNDARY was refused, not the records.
                    let refusedBodyBytes = body.count
                    trace.log("reslice_batch", [
                        ("status", String(r.status)), ("code", r.code ?? ""),
                        ("recordCount", String(slice.count)),
                        ("bodyBytes", String(refusedBodyBytes)),
                        ("chunk", String(batch.chunkId.prefix(12))),
                    ])
                    // THE DESCRIPTOR IS ABANDONED, and this is the ONLY
                    // disposition that abandons one without the bytes having
                    // moved anywhere. It is sound because a 413 on this edge is
                    // provably write-free — the parser refuses before the
                    // handler is entered and the typed event-count refusal
                    // returns before the controller's PREFLIGHT/write boundary
                    // so there is no committed work a later batch could
                    // duplicate. Keeping it would re-present the identical
                    // oversized body forever, which is what makes a plain
                    // retain the wrong answer here.
                    inflight.clear()
                    if slice.count <= 1 {
                        // Already ONE record and still refused. Our own
                        // arithmetic said it would fit; this server says
                        // otherwise, and its answer is the authority. There is
                        // nothing left to split, so the record is PRESERVED —
                        // never deleted, never counted as rejected data.
                        trace.log("record_body_too_large", [
                            ("session", owner),
                            ("chunk", String(batch.chunkId.prefix(12))),
                            ("recordBytes", String(slice[0].count)),
                        ])
                        // Phase-0 integration: `parkPrefix` reports THREE facts, not a
                        // Bool. A parked-but-not-removed head is `.terminalPending(why)`,
                        // exactly as the `.parkProducer` arm above treats it — the
                        // parked copy is durable and `reconcileParked` settles it.
                        switch parkPrefix(slice, batch, ChunkBounds.recordBodyTooLarge) {
                        case .moved:
                            return .terminalMoved
                        case .parkedNotRemoved(let why):
                            return .terminalPending(why)
                        case .notParked:
                            return terminalPendingOutcome("record_body_too_large_park_pending")
                        }
                    }
                    // Halve and rebuild from the SAME durable records. Each
                    // refusal at least halves the admitted count, so the
                    // sequence reaches one record in a bounded number of steps
                    // rather than retrying a size the server has already
                    // refused.
                    let s = ChunkBounds.shrink(refusedRecordCount: slice.count,
                                               refusedBodyBytes: refusedBodyBytes,
                                               currentBudgetBytes: resliceBudgetBytes)
                    resliceMaxRecords = s.maxRecords
                    resliceBudgetBytes = s.budgetBytes
                    trace.log("reslice_bounds", [
                        ("maxRecords", String(s.maxRecords)),
                        ("budgetBytes", String(s.budgetBytes)),
                    ])
                    return .resliced
                case .quarantinePrefix:
                    // ORDER REVERSED, AND THE ORDER IS THE CLAIM. This used
                    // to log `inflight_settled`, clear the descriptor and
                    // return `.quarantined` BEFORE attempting the removal — so
                    // on a store that refused the mutation the pipeline
                    // announced that records had been DESTROYED, discarded the
                    // descriptor that identifies them, and returned the
                    // strongest victim verdict it can make, for records still
                    // sitting on disk. Quarantine is deletion; nothing about it
                    // may be claimed before the deletion commits.
                    guard quarantinePrefix(slice.count, batch, r.code) else {
                        return .retained("quarantine_not_committed")
                    }
                    trace.log("inflight_settled", [("by", "quarantined"), ("chunk", batch.chunkId),
                                                   ("recordCount", String(slice.count))])
                    inflight.clear()   // settled: quarantined
                    return .quarantined
                }
            }
        }
        return .exhausted
    }

    /// Is the live queue still the one the descriptor describes?
    ///
    /// The store may have GROWN while the batch was unresolved — those records
    /// must survive and become the NEXT batch — but the first `recordCount`
    /// records must still be, byte for byte, the ones submitted.
    /// sha256 over the joined per-record digests — the stable fingerprint of the
    /// EXACT prefix boundary. A digest of digests: no record content, no text,
    /// no credential, and the one value that makes "the same records, in the
    /// same order" checkable from a trace line.
    static func prefixFingerprint(_ digests: [String]) -> String {
        BatchIdentity.sha256Hex(Data(digests.joined(separator: "|").utf8))
    }

    private func resumeStillMatches(_ d: InFlightBatch, _ current: [Data]) -> Bool {
        guard current.count >= d.recordCount else { return false }
        for i in 0..<d.recordCount where BatchIdentity.sha256Hex(current[i]) != d.digests[i] {
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ fates

    /// Why a dequeue did not happen. **Three different facts, three names.**
    ///
    /// THE SINGLE `prefix_changed` REASON HID THE ONE CASE AN OPERATOR MUST
    /// ACT ON. A shifted head is an ordinary race that settles itself on the
    /// next pass; a store that REFUSED the mutation because it cannot be read
    /// will refuse identically forever, and it is the whole visible symptom of
    /// the fail-closed rule. Reporting both as "the prefix changed" made a
    /// permanent wedge look like a transient one.
    private enum DequeueRefusal: String {
        /// The store's head is no longer the bytes we submitted.
        case prefixChanged = "prefix_changed"
        /// The store refused the mutation because its read is untrustworthy.
        case storeUntrustworthy = "store_untrustworthy"
        /// The store accepted the request and the atomic rewrite did not
        /// commit — a disk-level failure, not a corruption verdict.
        case removeNotCommitted = "remove_not_committed"
    }

    /// Classify a refused removal.
    ///
    /// THE INTEGRITY READ IS DIAGNOSTIC, NEVER THE DECISION. The decision
    /// was already taken inside the store, under its own lock, from its own
    /// scan; this is a second read taken afterwards purely to NAME what
    /// happened, so a store that recovered in between degrades to the weaker
    /// label rather than to a wrong one.
    private func classifyRemovalRefusal() -> DequeueRefusal {
        store.integrity().isTrustworthy ? .removeNotCommitted : .storeUntrustworthy
    }

    /// Remove EXACTLY the acknowledged prefix, and only after re-checking that
    /// the store's head is still those bytes — records appended while the
    /// request was in flight survive by construction.
    private func dequeueExactly(_ n: Int, _ batch: BatchIdentity) -> DequeueRefusal? {
        let current = store.readRecords()
        guard current.count >= n, batch.prefixMatches(Array(current.prefix(n))) else {
            trace.log("prefix_changed", [("expected", String(n))])
            return .prefixChanged
        }
        // `_ = store.removeFirst(n)` DISCARDED THE ONE FACT THIS RETURNS.
        // The primitive was fixed to return the number ACTUALLY removed (0 on
        // a failed atomic rewrite, and 0 on a refusal); every iOS caller threw
        // it away, so `prefix_removed` and a `true` return were both written
        // for a store that still held the records.
        guard store.removeFirst(n) == n else {
            let why = classifyRemovalRefusal()
            // The pipeline-level half of the correlation: a `store_mutation_refused`
            // line from the store is joined to the delivery outcome here, so
            // one trace shows the refusal AND what it cost.
            trace.log("prefix_remove_not_committed", [("expected", String(n)),
                                                      ("reason", why.rawValue)])
            return why
        }
        trace.log("prefix_removed", [("count", String(n))])
        return nil
    }

    /// PARK: preserve losslessly, then advance. Order is
    /// park → VERIFY by reading back → re-check the active prefix →
    /// removeFirst(n), so no path removes active bytes before the parked copy
    /// is durable. Retaining in place was the honest first answer and it was
    /// wrong: a permanently-refused slice at the head of a strict-FIFO queue
    /// starves every record behind it for the life of the install.
    /// The last park attempt's typed outcome, read only to choose between
    /// `.terminalPending` and `.terminalBlocked` on the same drain path.
    private var lastParkOutcome: ParkOutcome = .parked

    private func terminalPendingOutcome(_ code: String) -> Outcome {
        lastParkOutcome == .blocked ? .terminalBlocked(code) : .terminalPending(code)
    }

    /// What a park attempt actually achieved. **Three states, because the
    /// middle one used to be reported as the first.**
    private enum ParkResult {
        /// Parked durably AND the active prefix is gone. The queue ADVANCED.
        case moved
        /// Parked durably and the active bytes are STILL AUTHORITATIVE. No
        /// advance, no victim count — settled later, without a request, by
        /// `reconcileParked`.
        case parkedNotRemoved(String)
        /// The park itself did not happen. Nothing moved anywhere.
        case notParked
    }

    private func parkPrefix(_ slice: [Data], _ batch: BatchIdentity, _ code: String?) -> ParkResult {
        let entry = ParkedBatch(sessionId: batch.sessionId, chunkId: batch.chunkId,
                                recordCount: slice.count, code: code,
                                parkedAtMillis: nowMillis())
        let outcome = park.park(entry, records: slice)
        lastParkOutcome = outcome
        guard outcome == .parked else {
            trace.log(outcome == .blocked ? "park_capacity_blocked" : "park_retryable_failure",
                      [("code", code ?? "")])
            return .notParked
        }
        let current = store.readRecords()
        guard current.count >= slice.count,
              batch.prefixMatches(Array(current.prefix(slice.count))) else {
            // Parked copy is durable; the active head moved. Never remove bytes
            // we did not verify.
            trace.log("park_prefix_changed", [("chunk", String(batch.chunkId.prefix(12)))])
            return .parkedNotRemoved("park_prefix_changed")
        }
        // THIS USED TO RETURN SUCCESS FOR A PARK THAT DID NOT ADVANCE.
        // `parkedCount` is a VICTIM COUNT — it states these records left the
        // active timeline — and the caller turns success into `.parked` /
        // `.terminalMoved`, which tells the lane the queue moved on and it may
        // immediately try the next slice. On a store that refuses every
        // mutation the head has not moved, so that is an advance loop over a
        // prefix that is still there.
        //
        // Nothing is lost either way: the parked copy IS durable, so the
        // request-free `reconcileParked` settles it on a later pass once the
        // store is mutable again. What changes is that until then the ACTIVE
        // copy stays authoritative and is reported as such.
        guard store.removeFirst(slice.count) == slice.count else {
            let why = classifyRemovalRefusal()
            trace.log("active_remove_failed", [("expected", String(slice.count)),
                                               ("reason", why.rawValue),
                                               ("chunk", String(batch.chunkId.prefix(12)))])
            return .parkedNotRemoved(why.rawValue)
        }
        parkedCount += slice.count
        trace.log("producer_parked", [("count", String(slice.count)),
                                      ("code", code ?? ""),
                                      ("chunk", String(batch.chunkId.prefix(12)))])
        return .moved
    }

    /// Remove any ACTIVE prefix whose bytes are already durably parked — the
    /// request-free settlement of a crash between the parked write and the
    /// removal. Without it that window leaves one batch in both stores.
    private func reconcileParked() {
        // AN UNREADABLE STORE IS NOT AN EMPTY ONE, AND THIS GUARD USED TO
        // SAY IT WAS. This pass is the ONLY thing that settles the duplicate
        // active prefix a crash between "parked copy durable" and "active
        // prefix removed" leaves behind; reading an unreadable store as
        // "nothing is parked" means that duplicate is re-submitted rather than
        // reconciled, for as long as the store stays damaged.
        //
        // Returning unchanged is still the correct ACTION — nothing may be
        // removed on the strength of a read that failed — but it is now a
        // stated refusal, so an operator can tell a quiet store from a broken
        // one.
        let manifest = park.entriesRead()
        guard manifest.isTrustworthy else {
            trace.log("park_reconcile_refused",
                      [("why", "manifest_unreadable"),
                       ("reason", manifest.integrity.rawValue),
                       ("readable_entries", String(manifest.entries.count))])
            return
        }
        let parkedEntries = manifest.entries
        guard !parkedEntries.isEmpty else { return }
        for p in parkedEntries {
            let live = store.readRecords()
            guard live.count >= p.recordCount else { continue }
            let head = Array(live.prefix(p.recordCount))
            guard BatchIdentity.of(sessionId: p.sessionId, records: head).chunkId == p.chunkId
            else { continue }
            guard store.removeFirst(p.recordCount) == p.recordCount else {
                // `park_reconciled` asserts the duplicate active prefix is
                // gone. Logging it for a store that still holds those records
                // would make the next pass report the same reconciliation.
                //
                // AND `return`, NOT `continue`. On an untrustworthy store
                // every later entry would refuse identically, so walking on
                // buys nothing and writes a line per parked batch.
                trace.log("park_reconcile_not_committed",
                          [("expected", String(p.recordCount)),
                           ("reason", classifyRemovalRefusal().rawValue),
                           ("chunk", String(p.chunkId.prefix(12)))])
                return
            }
            trace.log("park_reconciled", [("count", String(p.recordCount)),
                                          ("chunk", String(p.chunkId.prefix(12)))])
        }
    }

    /// Quarantine is DELETION. It is reserved for a rejection the records
    /// themselves caused, and it takes only the submitted prefix — never the
    /// whole store.
    /// - Returns: true ONLY when the records are actually gone.
    @discardableResult
    private func quarantinePrefix(_ n: Int, _ batch: BatchIdentity, _ code: String?) -> Bool {
        let current = store.readRecords()
        guard current.count >= n, batch.prefixMatches(Array(current.prefix(n))) else {
            trace.log("quarantine_prefix_changed", [("expected", String(n))])
            return false
        }
        // QUARANTINE IS DELETION, so `quarantinedCount` is the strongest
        // victim claim this file makes. It may not be taken on an uncommitted
        // removal — that reports destruction of records still on disk.
        guard store.removeFirst(n) == n else {
            let why = classifyRemovalRefusal()
            trace.log("quarantine_not_committed", [("expected", String(n)),
                                                   ("reason", why.rawValue)])
            return false
        }
        quarantinedCount += n
        trace.log("record_quarantined", [("count", String(n)), ("code", code ?? "")])
        return true
    }
}

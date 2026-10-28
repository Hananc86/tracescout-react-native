import Foundation

/// READ-ONLY aggregation of the frame-delivery evidence the SDK ALREADY writes.
///
/// ## Why this exists
///
/// A physical Build-21 run admitted 94 frames to the store and delivered ZERO,
/// while 34 chunks uploaded successfully in the same process with the same
/// credential. The server endpoint, the request contract and the ACK contract
/// were each proven healthy against the exact Build-21 request shape, so the
/// failing branch is inside `FrameDelivery` — and every candidate branch is
/// invisible from the server, because a 4xx is not logged there and a frame that
/// is never posted leaves no trace off-device at all.
///
/// `RecoveryTrace` already records the answer. This type only READS it.
///
/// ## What this must never become
///
/// - It creates NO second counter system. Every number here is derived from
/// trace lines the delivery path already wrote, so a divergence between the
/// diagnostic and the behaviour is impossible by construction.
/// - It mutates nothing: no queue, no retry, no post, no ACK, no credential.
/// - It returns an AGGREGATE, never raw trace lines. Parsing happens natively so
/// no unreviewed string can reach JS.
///
/// ## The honesty rules that make it worth trusting
///
/// 1. **A truncated window is not a zero.** `RecoveryTrace` is bounded at 256 KB
/// and drops the OLDEST half on overflow, so `delivered = 0` may mean "no
/// delivery" or "the delivery scrolled out of the window". The result carries
/// `windowMayBeClipped` and the timestamps to judge it. A diagnostic that
/// reports a clipped window as a confident zero would restart the whole
/// misdiagnosis this build exists to end.
///
/// 2. **Session attribution is proven, never assumed.** Only
/// `frame_inflight_frozen` / `frame_inflight_resumed` carry a `sessionId`;
/// they also carry `visualFrameId`, so later lines for the same frame can be
/// attributed through that id. `rn_frame_enqueued` and `rn_frames_drained`
/// carry NEITHER, so they land in `unknownSession` and are reported as such.
/// Historical and current frame evidence have already been confused twice in
/// this programme; a guessed attribution would make it three.
///
/// 3. **`rn_frames_drained` merges delivered+duplicate into one field.** The
/// separation comes from `frame_inflight_settled`'s `by`, which is the ack
/// state. Reading the merged field as "delivered" would overstate delivery.
///
/// 4. **There is no `recordsSeen` field in the trace**, and this type does not
/// invent one. The F1/F2 separation instead rests on facts that ARE written:
/// `rn_frame_post` is logged unconditionally per attempt, and
/// `rn_frames_drained` is logged only when some disposition occurred. So
/// posts > 0 proves the drain reached records; a drain line with posts == 0
/// proves it reached records and never posted; and no drain line at all,
/// with successful enqueues, is the signature of a drain that saw nothing.
/// ## THE WINDOWED VOCABULARY — the Build-28 attribution defect
///
/// This type was written against the SINGLE-FLIGHT lane (`frame_inflight_*`,
/// `rn_frame_post`). The windowed lane that shipped in Build 28 writes a
/// DIFFERENT vocabulary — `frame_op_frozen` / `frame_op_retained` /
/// `frame_op_settled` — and none of it was in the switch below. Measured on the
/// Build-28 Diagnostics smoke `ts_1789043024017_1bf0dfe431cc`:
///
/// durable tail many frame_op_frozen / retained / settled for THAT session
/// Diagnostics currentSession.posts = 0
/// stageTimings = [:]
/// sessionAttributionAvailable = false
///
/// while the server held four rows created bytes-first BY that session. The
/// numbers were not wrong about what they counted; they counted a vocabulary the
/// lane had stopped speaking. A reader would have concluded the current session
/// posted nothing — the exact misdiagnosis this type exists to end, one lane
/// later.
///
/// Both vocabularies are now read. Where they overlap the counters are shared,
/// because the QUESTION ("did the current session post?") is the same one.
///
/// ATTRIBUTION IS BY FRAME IDENTITY, NOT BY POSITION. The windowed lane runs
/// N operations concurrently, so "the last freeze before this line" — sound for
/// a strictly sequential pass — is no longer a correlation at all: four freezes
/// can precede one settlement. Every windowed line carries `visualFrameId`, and
/// several carry `sessionId` too, so the owner map is built from declarations
/// and a frame whose id maps to MORE THAN ONE session resolves to `unknown`
/// rather than to whichever was seen last.
public enum FrameDeliveryDiagnostics {

    public static let version = "frame-delivery-diag-2"

    /// Where a trace line's evidence belongs. `unknown` is a real answer.
    public enum Attribution: String {
        case current, other, unknown
        /// Every bucket, for totals that must not depend on attribution having
        /// succeeded.
        static let allBuckets: [Attribution] = [.current, .other, .unknown]
    }

    // MARK: - line parsing

    /// One trace line, reduced to the allowlisted fields this type reads.
    struct Line {
        let ts: Int64
        let event: String
        let fields: [String: String]
        var sessionId: String? { fields["sessionId"] }
        var visualFrameId: String? { fields["visualFrameId"] }
    }

    /// Parse a RecoveryTrace JSON line. Every value the writer emits is a
    /// string, so anything else is a line this type does not understand and
    /// deliberately drops rather than coercing.
    static func parse(_ raw: String) -> Line? {
        guard let d = raw.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
              let event = o["event"] as? String else { return nil }
        var fields: [String: String] = [:]
        for (k, v) in o where k != "event" && k != "ts" {
            if let s = v as? String { fields[k] = s }
        }
        let ts = Int64((o["ts"] as? String) ?? "") ?? 0
        return Line(ts: ts, event: event, fields: fields)
    }

    // MARK: - aggregation

    /// - Parameters:
    /// - traceLines: raw lines, oldest first, straight from `RecoveryTrace.tail`.
    /// - currentSessionId: nil when no session is live; every attribution then
    /// resolves to `other`/`unknown` rather than silently claiming lines.
    public static func aggregate(traceLines: [String],
                                 currentSessionId: String?,
                                 sessionStartMs: Int64? = nil,
                                 frameStoreCount: Int,
                                 frameStoreBytes: Int,
                                 parkBatches: Int,
                                 parkRecords: Int,
                                 parkBytes: Int,
                                 registration: SessionRegistry.Snapshot? = nil) -> [String: Any] {

        let lines = traceLines.compactMap(parse)

        // vfId -> the sessionId(s) DECLARED for it. A set, not a value: if two
        // sessions ever claim one frame id the attribution is ambiguous and the
        // honest answer is `unknown`. Collapsing to "last writer wins" would
        // manufacture exactly the confident-but-wrong attribution that has
        // already mixed historical and current frame evidence twice.
        var ownerCandidates: [String: Set<String>] = [:]
        // Every event that carries BOTH ids is a declaration. The windowed lane
        // added four of them; the single-flight pair is kept so an upgraded
        // install can still read a tail written by the previous build.
        let declaringEvents: Set<String> = [
            "frame_inflight_frozen", "frame_inflight_resumed",
            "frame_op_frozen", "frame_op_post",
            "frame_op_awaiting_session", "frame_op_session_pending",
        ]
        for l in lines where declaringEvents.contains(l.event) {
            if let vf = l.visualFrameId, let sid = l.sessionId {
                ownerCandidates[vf, default: []].insert(sid)
            }
        }
        var owner: [String: String] = [:]
        var ambiguousFrames = 0
        for (vf, sids) in ownerCandidates {
            if sids.count == 1 { owner[vf] = sids.first }
            else { ambiguousFrames += 1 }
        }

        // `rn_frame_post` CARRIES NEITHER A SESSION NOR A FRAME ID — only
        // status, code and attempt. Without a correlation it is unattributable,
        // and the readout could not answer "how many posts did the CURRENT
        // session attempt", which is the question F3/F4/F5 turn on.
        //
        // The correlation that exists, and is sound: `FrameDelivery.drain` is a
        // single sequential pass that logs `frame_inflight_frozen` — which DOES
        // carry sessionId and visualFrameId — immediately before `post(h, bytes)`
        // for that same frame. In an append-only log written from one thread,
        // the last freeze before a post IS the frame being posted.
        //
        // It is DISCLOSED as `postAttributionMode: preceding_freeze` rather than
        // presented as if the line named its own session, and a post with no
        // preceding freeze resolves to `unknown` instead of borrowing the
        // current session. Inferring quietly is how historical and current frame
        // evidence got confused twice already.
        var lastFrozen: String?
        func attribute(_ l: Line, inheritFreeze: Bool = false) -> Attribution {
            var sid = l.sessionId ?? l.visualFrameId.flatMap { owner[$0] }
            if sid == nil && inheritFreeze { sid = lastFrozen }
            guard let s = sid else { return .unknown }
            guard let cur = currentSessionId else { return .other }
            return s == cur ? .current : .other
        }

        // Per-bucket counters. A bucket is a dictionary so an unexpected reason
        // enum widens the report instead of being dropped on the floor.
        var enqueueAttempts = 0, enqueueOK = 0, enqueueFailed = 0
        var enqueueByReason: [String: Int] = [:]
        var drainInvocations = 0
        var drained: [String: Int] = ["delivered": 0, "refused": 0, "exhausted": 0,
                                      "retained": 0, "unreadable": 0]
        var drainFailedClosed = 0
        var evictions: [String: Int] = [:]
        var evictedBytes: [String: Int] = [:]
        var evictionsOmitted = 0
        var laneReclaims: [String: Int] = [:]
        // Build 26 — stage timings, accumulated from the lines the delivery
        // lane now stamps. `-1` means the stage had no predecessor (the first
        // freeze of a pass) and is DROPPED rather than counted as zero.
        var stages: [String: [Int64]] = [:]
        func stage(_ k: String, _ raw: String?) {
            guard let v = Int64(raw ?? ""), v >= 0 else { return }
            stages[k, default: []].append(v)
        }

        func newDisp() -> [String: Int] {
            ["frozen": 0, "resumed": 0, "reconciled": 0, "freezeFailed": 0,
             "evictedRecords": 0, "evictedUndecodable": 0, "laneLost": 0,
             "posts": 0, "settledAccepted": 0, "settledDuplicate": 0,
             "settledRefused": 0, "settledExhausted": 0,
             "ackInvalid": 0, "headMoved": 0, "rotateFailed": 0,
             // The windowed lane's own dispositions.
             "retained": 0, "timedOut": 0, "unremovable": 0,
             // The startup-dependency lane. `dependency404` counts REFUSALS
             // observed on the wire; `sessionDependencyPending` counts REFUNDS
             // actually applied. They are separate numbers precisely so the gap
             // between them is measurable — see
             // `normalAttemptsSpentOnSessionNotFound`.
             "dependency404": 0, "sessionDependencyPending": 0,
             "awaitingRegistration": 0]
        }
        var disp: [Attribution: [String: Int]] = [
            .current: newDisp(), .other: newDisp(), .unknown: newDisp()]
        var httpStatus: [Attribution: [String: Int]] = [.current: [:], .other: [:], .unknown: [:]]
        var ackCategory: [Attribution: [String: Int]] = [.current: [:], .other: [:], .unknown: [:]]
        var postCodes: [Attribution: [String: Int]] = [.current: [:], .other: [:], .unknown: [:]]

        func bump(_ m: inout [Attribution: [String: Int]], _ a: Attribution, _ k: String) {
            m[a, default: [:]][k, default: 0] += 1
        }

        var frameLineCount = 0
        var oldestTs: Int64 = .max, newestTs: Int64 = 0
        var currentFirstTs: Int64 = .max

        // Windowed-lane aggregates that have no per-session identity of their
        // own: they describe the WINDOW, not a frame.
        var windowMaxLive = 0, windowTimeouts = 0, windowFenced = 0, windowReconciled = 0
        var sawWindowLine = false
        var registrationReadyAtMs: Int64 = -1
        var registrationPendingAtMs: Int64 = -1

        for l in lines {
            if l.ts > 0 { oldestTs = min(oldestTs, l.ts); newestTs = max(newestTs, l.ts) }
            // THE WINDOWED EVENTS ARE ABSENT FROM THIS SET, DELIBERATELY.
            // `inheritFreeze` is the "last freeze before this line" correlation,
            // which is only sound for a strictly sequential pass. With N
            // concurrent operations it would attribute a settlement to whichever
            // frame happened to be frozen most recently. Every windowed line
            // carries its own `visualFrameId`, so it attributes by identity
            // through `owner` and needs no positional guess.
            let inherits = (l.event == "rn_frame_post" || l.event == "frame_inflight_settled"
                            || l.event == "frame_ack_invalid")
            let a = attribute(l, inheritFreeze: inherits)
            if l.event == "frame_inflight_frozen" || l.event == "frame_inflight_resumed" {
                lastFrozen = l.sessionId ?? l.visualFrameId.flatMap { owner[$0] }
            }
            if a == .current, l.ts > 0 { currentFirstTs = min(currentFirstTs, l.ts) }

            switch l.event {
            case "rn_frame_enqueued":
                frameLineCount += 1
                enqueueAttempts += 1
                if l.fields["ok"] == "true" { enqueueOK += 1 }
                else {
                    enqueueFailed += 1
                    // `store_cap` is the only reason the writer names today; an
                    // unnamed failure is recorded as `unspecified` rather than
                    // being folded into a reason it did not report.
                    enqueueByReason[l.fields["reason"] ?? "unspecified", default: 0] += 1
                }

            case "rn_frames_drained":
                frameLineCount += 1
                drainInvocations += 1
                // the writer's `delivered` is delivered+duplicate MERGED.
                for k in ["delivered", "refused", "exhausted", "retained", "unreadable",
                          "sessionDependencyPending", "blockedByRegistration", "coolingDown"] {
                    drained[k, default: 0] += Int(l.fields[k] ?? "") ?? 0
                }
                if l.fields["failedClosed"] == "true" { drainFailedClosed += 1 }

            case "frame_inflight_frozen":
                frameLineCount += 1; disp[a]?["frozen", default: 0] += 1
                stage("t4_settle_to_freeze", l.fields["t4Ms"])
            case "frame_inflight_resumed":  frameLineCount += 1; disp[a]?["resumed", default: 0] += 1
            case "frame_inflight_reconciled": frameLineCount += 1; disp[a]?["reconciled", default: 0] += 1
            case "frame_inflight_freeze_failed": frameLineCount += 1; disp[a]?["freezeFailed", default: 0] += 1
            case "rn_frame_evicted":
                frameLineCount += 1
                let rel = l.fields["relation"] ?? "unknown"
                evictions[rel, default: 0] += Int(l.fields["records"] ?? "") ?? 0
                evictedBytes[rel, default: 0] += Int(l.fields["bytes"] ?? "") ?? 0

            case "rn_frame_evicted_record":
                frameLineCount += 1
                // Identity-bearing, so it attributes like any other frame line.
                if l.fields["decoded"] == "true" {
                    disp[a]?["evictedRecords", default: 0] += 1
                } else {
                    disp[a]?["evictedUndecodable", default: 0] += 1
                }

            case "rn_frame_evicted_truncated":
                frameLineCount += 1
                evictionsOmitted += Int(l.fields["omitted"] ?? "") ?? 0

            case "rn_lane_reclaimed":
                frameLineCount += 1
                laneReclaims[l.fields["lane"] ?? "unknown", default: 0] += 1

            case "frame_lane_lost":       frameLineCount += 1; disp[a]?["laneLost", default: 0] += 1
            case "frame_head_moved":        frameLineCount += 1; disp[a]?["headMoved", default: 0] += 1
            case "frame_rotate_failed":     frameLineCount += 1; disp[a]?["rotateFailed", default: 0] += 1

            case "rn_frame_post":
                frameLineCount += 1
                disp[a]?["posts", default: 0] += 1
                stage("t1_freeze_to_request", l.fields["t1Ms"])
                stage("t2_request_to_response", l.fields["t2Ms"])
                // status "0" is the writer's value when no HTTPURLResponse came
                // back — a transport failure, not an HTTP answer. Kept as its
                // own bucket so it can never be read as a server reply.
                let st = l.fields["status"] ?? "0"
                bump(&httpStatus, a, st == "0" ? "no_http_response" : st)
                if let c = l.fields["code"], !c.isEmpty { bump(&postCodes, a, c) }

            case "frame_inflight_settled":
                stage("t3_response_to_settle", l.fields["t3Ms"])
                frameLineCount += 1
                switch l.fields["by"] ?? "" {
                case "accepted":  disp[a]?["settledAccepted", default: 0] += 1
                case "duplicate": disp[a]?["settledDuplicate", default: 0] += 1
                case "refused":   disp[a]?["settledRefused", default: 0] += 1
                case "exhausted": disp[a]?["settledExhausted", default: 0] += 1
                default: break
                }

            case "frame_ack_invalid":
                frameLineCount += 1
                disp[a]?["ackInvalid", default: 0] += 1
                bump(&ackCategory, a, l.fields["reason"] ?? "ack_absent")

            // ── the WINDOWED lane ───────────────────────────────────────────
            case "frame_op_frozen":
                frameLineCount += 1; disp[a]?["frozen", default: 0] += 1
            case "frame_op_frozen_stage":
                frameLineCount += 1; stage("t4_settle_to_freeze", l.fields["t4Ms"])
            case "frame_op_settle_stage":
                frameLineCount += 1; stage("t3_response_to_settle", l.fields["t3Ms"])
            case "frame_op_reconciled":
                frameLineCount += 1; disp[a]?["reconciled", default: 0] += 1
            case "frame_op_freeze_failed":
                frameLineCount += 1; disp[a]?["freezeFailed", default: 0] += 1
            case "frame_op_deadline":
                frameLineCount += 1; disp[a]?["timedOut", default: 0] += 1
            case "frame_op_unremovable":
                frameLineCount += 1; disp[a]?["unremovable", default: 0] += 1
            case "frame_op_retained":
                frameLineCount += 1
                disp[a]?["retained", default: 0] += 1
                // The reason is the typed protocol code the pump produced, so it
                // is safe to bucket verbatim.
                bump(&ackCategory, a, l.fields["reason"] ?? "unspecified")

            case "frame_op_post":
                frameLineCount += 1
                disp[a]?["posts", default: 0] += 1
                stage("t1_freeze_to_request", l.fields["t1Ms"])
                stage("t2_request_to_response", l.fields["t2Ms"])
                let st = l.fields["status"] ?? "0"
                bump(&httpStatus, a, st == "0" ? "no_http_response" : st)
                if let c = l.fields["code"], !c.isEmpty { bump(&postCodes, a, c) }
                // THE DEPENDENCY IS IDENTIFIED BY THE BODY CODE, matching the
                // client rule exactly. Counting every 404 here would fold a dark
                // ingest gate into the startup-dependency number.
                if st == "404" && l.fields["code"] == FrameDelivery.sessionDependencyCode {
                    disp[a]?["dependency404", default: 0] += 1
                }

            case "frame_op_session_pending":
                frameLineCount += 1
                disp[a]?["sessionDependencyPending", default: 0] += 1

            case "frame_op_awaiting_session":
                frameLineCount += 1
                disp[a]?["awaitingRegistration", default: 0] += 1

            case "frame_op_settled", "frame_op_settled_absent":
                frameLineCount += 1
                switch l.fields["by"] ?? "" {
                case "accepted":  disp[a]?["settledAccepted", default: 0] += 1
                case "duplicate": disp[a]?["settledDuplicate", default: 0] += 1
                case "refused":   disp[a]?["settledRefused", default: 0] += 1
                case "exhausted": disp[a]?["settledExhausted", default: 0] += 1
                default: break
                }

            case "frame_op_fenced":
                frameLineCount += 1
                // No frame id: a fenced completion is refused BEFORE its
                // descriptor is resolved, so it is a window fact and never a
                // per-session one.
                windowFenced += 1

            case "rn_frame_window":
                frameLineCount += 1
                sawWindowLine = true
                windowMaxLive = max(windowMaxLive, Int(l.fields["maxLive"] ?? "") ?? 0)
                windowTimeouts += Int(l.fields["timedOut"] ?? "") ?? 0
                windowReconciled += Int(l.fields["reconciled"] ?? "") ?? 0

            case "session_registration_pending":
                frameLineCount += 1
                if l.ts > 0 && registrationPendingAtMs < 0 { registrationPendingAtMs = l.ts }
            case "session_registration_ready":
                frameLineCount += 1
                if l.ts > 0 { registrationReadyAtMs = l.ts }

            default: break
            }
        }

        // COMPLETENESS. The trace drops its OLDEST half on overflow, so the
        // earliest evidence of a run can be gone while every counter still looks
        // like a confident number. If the first line attributable to the current
        // session IS the oldest line in the window, the window starts at or after
        // the session's first frame event and may have clipped what came before.
        // THE SECOND CORRECTION, and the first one was not enough.
        //
        // Build 25 replaced a flag that could not FAIL with a tri-state — and
        // the tri-state could still report a confident TRUE. On the physical
        // Build-25 block it said `complete` for a window that begins **57
        // seconds into a 111-second session**:
        //
        // trace.oldestTs 1788938918426
        // currentSessionFirstTs 1788938918434 (8 ms later)
        // session start 1788938861359 (57.07 s EARLIER)
        //
        // The rule compared the oldest retained line against the earliest
        // CURRENT-SESSION line STILL PRESENT, so it could only ever answer "is
        // the window slightly older than its own oldest surviving entry" — a
        // question whose answer is yes almost by construction. Independent
        // proof it was wrong: that same block reported 87 settlements while the
        // server held 123 verified frames, so 36 settlements were missing from
        // a window calling itself complete.
        //
        // The reference must be the DEVICE-SIDE SESSION START, which is known
        // and does not depend on any line surviving:
        //
        // COMPLETE retained evidence reaches session start
        // CLIPPED oldest retained evidence is LATER than session start
        // UNKNOWN session start, or any timestamped evidence, unavailable
        //
        // `currentSessionFirstTs` is still reported — it is useful — but it is
        // no longer allowed to decide completeness.
        let haveTs = oldestTs != .max
        let completeness: String
        if !haveTs {
            completeness = "unknown"            // no timestamped line at all
        } else if let start = sessionStartMs, start > 0 {
            completeness = oldestTs <= start ? "complete" : "clipped"
        } else {
            // No session start supplied: the window cannot be placed. This is
            // the one honest answer, and it is deliberately NOT `complete`.
            completeness = "unknown"
        }
        let clipped = completeness != "complete"

        func dispCount(_ a: Attribution, _ k: String) -> Int { disp[a]?[k] ?? 0 }
        // Across EVERY attribution. The dependency question is "did any frame
        // spend an attempt on a prerequisite", and a frame whose session could
        // not be attributed still spent one.
        let dependency404Total = Attribution.allBuckets.reduce(0) {
            $0 + dispCount($1, "dependency404")
        }
        let dependencyRefundTotal = Attribution.allBuckets.reduce(0) {
            $0 + dispCount($1, "sessionDependencyPending")
        }

        func dict(_ a: Attribution) -> [String: Any] {
            var d: [String: Any] = disp[a] ?? [:]
            d["httpStatusCounts"] = httpStatus[a] ?? [:]
            d["postCodeCounts"] = postCodes[a] ?? [:]
            d["ackCategoryCounts"] = ackCategory[a] ?? [:]
            return d
        }

        return [
            "version": version,
            "currentSessionId": currentSessionId ?? NSNull(),
            "frameStore": ["count": frameStoreCount, "bytes": frameStoreBytes],
            "park": ["batches": parkBatches, "records": parkRecords, "bytes": parkBytes],
            "trace": [
                "linesScanned": traceLines.count,
                "linesParsed": lines.count,
                "frameLinesMatched": frameLineCount,
                "oldestTs": haveTs ? oldestTs : 0,
                "newestTs": newestTs,
                "currentSessionFirstTs": currentFirstTs == .max ? 0 : currentFirstTs,
                // The honest completeness signal — see the note above.
                "windowMayBeClipped": clipped,
                // The explicit answer. `windowMayBeClipped` is derived from it
                // and stays fail-safe; read THIS to tell "clipped" from
                // "could not be determined".
                "completeness": completeness,
                // The reference the verdict was computed against. Absent means
                // the verdict is `unknown` for that reason and nothing else.
                "sessionStartMs": sessionStartMs ?? -1,
                "evictions": evictions,
                "evictedBytes": evictedBytes,
                "evictionsOmitted": evictionsOmitted,
                "laneReclaims": laneReclaims,
                // Stage timings, so the per-frame floor is MEASURED on the
                // device instead of inferred from server settlement spacing.
                "stageTimings": stages.mapValues { v -> [String: Any] in
                    let sorted = v.sorted()
                    func pct(_ p: Double) -> Int64 {
                        guard !sorted.isEmpty else { return -1 }
                        let i = Swift.min(sorted.count - 1,
                                          Swift.max(0, Int((Double(sorted.count - 1) * p).rounded())))
                        return sorted[i]
                    }
                    // THE EXPLICIT TYPE IS THE FIX, AND IT IS LOAD-BEARING.
                    // Written inline as `"max": sorted.last ?? -1` inside a
                    // `[String: Any]` literal, `Any` makes BOTH the `last`
                    // PROPERTY and the unapplied `last(where:)` METHOD viable;
                    // Swift chose the method, promoted it to an optional (so
                    // `?? -1` never fired) and handed Foundation a closure
                    // boxed as `__SwiftValue`. That threw an ObjC
                    // NSInvalidArgumentException past `try?` and aborted the
                    // app — twice, on a real device, on the Build-26
                    // Diagnostics screen. A typed local admits only the
                    // property. Reproduced and named in CI run 34439635253.
                    let maxMs: Int64 = sorted.last ?? -1
                    return ["count": sorted.count, "p50": pct(0.5),
                            "p90": pct(0.9), "max": maxMs]
                },
                "sessionAttributionAvailable": !owner.isEmpty,
                // Frame ids claimed by more than one session. Reported so a
                // reader can tell "no attribution was available" from "some
                // attribution was refused because it was ambiguous".
                "ambiguousFrameAttribution": ambiguousFrames,
                // How a line with no identity of its own was attributed. Stated
                // so a reader never mistakes an inference for a declaration.
                "postAttributionMode": "preceding_freeze",
            ],
            // Neither of these events carries a session id or a frame id, so
            // they are reported WITHOUT attribution rather than assigned.
            "enqueue": [
                "attribution": Attribution.unknown.rawValue,
                "attempts": enqueueAttempts, "ok": enqueueOK, "failed": enqueueFailed,
                "byReason": enqueueByReason,
            ],
            "drainSummary": [
                "attribution": Attribution.unknown.rawValue,
                "invocations": drainInvocations,
                "deliveredPlusDuplicate": drained["delivered"] ?? 0,
                "refused": drained["refused"] ?? 0,
                "exhausted": drained["exhausted"] ?? 0,
                "retained": drained["retained"] ?? 0,
                "unreadable": drained["unreadable"] ?? 0,
                "sessionDependencyPending": drained["sessionDependencyPending"] ?? 0,
                "blockedByRegistration": drained["blockedByRegistration"] ?? 0,
                "coolingDown": drained["coolingDown"] ?? 0,
                "failedClosedInvocations": drainFailedClosed,
            ],
            // The WINDOW, not any one session. `maxLiveObservable` says whether
            // the number below was measured at all — `rn_frame_window` is only
            // written when something timed out or was fenced, so a healthy pass
            // legitimately produces no line and the honest answer is "not
            // observed", never "zero concurrency".
            "window": [
                "maxLiveObservable": sawWindowLine,
                "maxLive": sawWindowLine ? windowMaxLive : -1,
                "timeouts": windowTimeouts,
                "fenced": windowFenced,
                "reconciled": windowReconciled,
            ],
            // ── the startup session-registration barrier ────────────────────
            //
            // `normalAttemptsSpentOnSessionNotFound` IS MEASURED, NOT
            // ASSERTED. Writing a literal 0 here would be a number that cannot
            // fail — the exact shape this programme keeps paying for. It is the
            // gap between dependency refusals SEEN on the wire and refunds
            // actually APPLIED: every `session_not_found` post that did not
            // produce a `frame_op_session_pending` line kept its spent attempt.
            // If the refund path regresses, this goes non-zero on the device.
            "startupDependency": [
                "sessionRegistrationState": registration?.state.rawValue
                    ?? SessionRegistrationState.unknown.rawValue,
                "sessionRegistrationReadyAtMs": registration?.readyAtMs
                    ?? registrationReadyAtMs,
                "sessionRegistrationPendingSinceMs": registration?.pendingSinceMs
                    ?? registrationPendingAtMs,
                "sessionRegistrationWaitedMs": registration?.waitedMs ?? -1,
                "framesWaitingForRegistration": dispCount(.current, "awaitingRegistration"),
                "sessionNotFoundDependencyCount": dependency404Total,
                "sessionDependencyRefunds": dependencyRefundTotal,
                "normalAttemptsSpentOnSessionNotFound":
                    Swift.max(0, dependency404Total - dependencyRefundTotal),
                "backoffBaseMs": SessionDependencyBackoff.baseMs,
                "backoffCapMs": SessionDependencyBackoff.capMs,
            ],
            "currentSession": dict(.current),
            "otherSessions": dict(.other),
            "unknownSession": dict(.unknown),
        ]
    }
}

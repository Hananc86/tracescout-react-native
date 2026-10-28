import Foundation

/// WHEN A REFUSED SLICE IS PERMANENTLY LOCALLY UNDELIVERABLE — the Swift twin
/// of `SessionOriginPolicy.kt`, value-for-value.
///
/// ## The defect, stated precisely
///
/// "Unsliceable record" was the wrong name and it hid the cause. A slice always
/// forms. What is lost is the session's ORIGIN:
///
/// a session is originated server-side ONLY by `mobile_session_start`, which
/// carries sequence 0 and is emitted once, at session start
///
/// the DURABLE store evicts OLDEST-FIRST with no priority awareness, while
/// the in-memory queue's eviction deliberately PROTECTS that same record
///
/// `mobile_session_start` is by construction the oldest record of its
/// session, so durable pressure deletes the one record capable of originating
/// the session while retaining every record that depends on it
///
/// Those retained records are refused `session_not_found` forever, correctly
/// RETAINED (never deleted) — and the settle accounting converted that correct
/// retention into an unbounded 2 s wakeup with one real HTTP POST per cycle,
/// because the scheduler was given only a COUNT, and a count cannot distinguish
/// "retryable bytes remain" from "permanently undeliverable bytes remain".
///
/// ## THE TERMINAL FACT IS NOT THE SERVER RESPONSE
///
/// `session_not_found` ALONE is not terminal: an opener still present anywhere
/// means origin recoverability is NOT DISPROVEN, so there is not enough evidence
/// to declare the slice terminal.
///
/// That is deliberately weaker than "the next pass will originate it". Under
/// strict FIFO an opener BEHIND the refused head never moves ahead of it by
/// retrying, and no reorder path exists. The contract is fail-closed evidence,
/// never a promise of eventual self-recovery. The terminal fact is the
/// CONJUNCTION — the server requires a session origin AND this producer no
/// longer possesses any local mechanism able to create it. Hence
/// `SESSION_ORIGIN_UNRECOVERABLE`, deliberately NOT `session_not_found`:
/// naming it after the response would name the symptom and would eventually be
/// applied to a session whose opener is merely queued.
///
/// ## AND IT IS NOT `PARKED_PRODUCER`
///
/// The host surfaces a producer-capability refusal as "this build is
/// incompatible". That would be FALSE here: the build is fine, the session's
/// opening record is gone. This reason is ADDITIVE; existing parked-producer
/// semantics are untouched.
public enum SessionOriginPolicy {

    /// Written on the in-flight descriptor AND as the parked batch's refusal
    /// code, so the two agree by construction. Twin of the Kotlin constant.
    public static let sessionOriginUnrecoverable = "session_origin_unrecoverable"

    /// The one event type that originates a session server-side.
    public static let openerEventType = "mobile_session_start"

    /// What the caller knows, gathered from DURABLE state under one consistency
    /// boundary.
    ///
    /// `openerPresentInActiveStore` MUST come from the durable record store,
    /// never from an in-memory session registry. Process restart is exactly the
    /// case this defect survives, and a registry is empty there — so a
    /// registry-based check would answer "no opener" on every cold start and
    /// park sessions whose opener is sitting in the queue.
    public struct Evidence: Equatable {
        public let sessionPreconditionRefusal: Bool
        public let firstSequence: Int64
        public let openerPresentInActiveStore: Bool
        /// A caller that CANNOT answer must supply `true`, so ignorance keeps
        /// the slice transient.
        public let openerRecoverableElsewhere: Bool
        public let sessionAlreadyTerminal: Bool

        public init(sessionPreconditionRefusal: Bool, firstSequence: Int64,
                    openerPresentInActiveStore: Bool, openerRecoverableElsewhere: Bool,
                    sessionAlreadyTerminal: Bool) {
            self.sessionPreconditionRefusal = sessionPreconditionRefusal
            self.firstSequence = firstSequence
            self.openerPresentInActiveStore = openerPresentInActiveStore
            self.openerRecoverableElsewhere = openerRecoverableElsewhere
            self.sessionAlreadyTerminal = sessionAlreadyTerminal
        }
    }

    public enum Verdict: Equatable {
        /// Keep retrying through the ordinary bounded backoff. Nothing moves.
        case transient
        /// Permanently locally undeliverable: persist the terminal reason on the
        /// durable descriptor, THEN move the bytes to the park store.
        /// **No deletion, ever.**
        case terminalSessionOrigin
    }

    /// The decision.
    ///
    /// EVERY CLAUSE IS REQUIRED, and the order is the argument:
    ///
    /// - `firstSequence > 0` — a slice BEGINNING at sequence 0 contains the
    /// opener itself, so the origin is by definition not lost.
    /// - the opener is absent from the active store AND not recoverable
    /// elsewhere — if it is queued anywhere, the next pass originates the
    /// session and everything behind it drains.
    /// - the server actually refused on the session precondition.
    ///
    /// Any clause failing yields `.transient`, and every unknown is supplied as
    /// the transient-preserving value by contract, so this can never park on
    /// ignorance.
    public static func verdict(_ e: Evidence) -> Verdict {
        // Already proven terminal on an earlier pass: a LATER record of the
        // same dead session must not need a SECOND server refusal to learn a
        // fact the device already knows durably.
        if e.sessionAlreadyTerminal && e.firstSequence > 0 { return .terminalSessionOrigin }

        if !e.sessionPreconditionRefusal { return .transient }
        if e.firstSequence <= 0 { return .transient }
        if e.openerPresentInActiveStore { return .transient }
        if e.openerRecoverableElsewhere { return .transient }
        return .terminalSessionOrigin
    }

    /// Does this serialized record OPEN `sessionId`?
    ///
    /// Parsed, never string-matched: a substring scan would be defeated by key
    /// order and would also match a record that merely MENTIONS the id.
    ///
    /// AN UNPARSEABLE RECORD RETURNS TRUE. This answer is used only to keep a
    /// slice TRANSIENT: a false positive costs one more bounded retry, a false
    /// negative parks a session whose opener was merely unreadable. Bias to the
    /// safe direction, explicitly — and identically to the Kotlin twin.
    public static func recordOpensSession(_ bytes: [UInt8], _ sessionId: String) -> Bool {
        guard let o = (try? JSONSerialization.jsonObject(with: Data(bytes))) as? [String: Any]
        else { return true }
        return (o["eventType"] as? String) == openerEventType
            && (o["sessionId"] as? String) == sessionId
    }
}

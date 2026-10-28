import Foundation

/// Response classification and the terminal-disposition taxonomy.
///
/// This is a SHARED policy restated for iOS, not an iOS one: the same response
/// semantics apply to the Android and iOS producers, and a per-producer copy
/// would be a second authority over the thing that deletes customer data.
/// Any divergence from `mobile-android./transport/Transport.kt` is a defect.
public enum ResponseAction {
    case success
    case retry
    case terminal
}

/// 4xx is TERMINAL except 408/429. Retrying a genuinely rejected chunk forever
/// is how a client becomes a denial-of-service against its own backend.
/// A `503` carrying `retriable:true` is the backend explicitly asking again.
public func classifyResponse(status: Int, retriable: Bool = false) -> ResponseAction {
    if (200...299).contains(status) { return .success }
    if status == 408 || status == 429 { return .retry }
    if status == 503 && retriable { return .retry }
    if (400...499).contains(status) { return .terminal }
    return .retry
}

public enum TerminalDisposition {
    /// The CREDENTIAL is indicted. Retain; a corrected key delivers these bytes.
    case retainAuth
    /// The BUILD is indicted, and the build that made these bytes is stamped
    /// INTO them at capture time — so no later build can make THESE records
    /// acceptable. Preserve them losslessly in the parked store and move them
    /// OUT of the active queue, so records captured by a compatible build are
    /// not starved behind a refusal that can never clear. Parking is NOT
    /// quarantine — nothing is destroyed.
    case parkProducer
    /// The ADDRESS is indicted — the session does not exist YET. Retain.
    case retainSessionPrecondition
    /// A SERVER FEATURE GATE is indicted. The refusal is about a record CLASS
    /// being switched off, never about these bytes — and a batch prefix is
    /// TYPE-BLIND, so deleting it discards unrelated replay records that merely
    /// shared a chunk with one gated record. Retain; the SAME bytes are valid
    /// the moment the gate opens.
    case retainFeatureGate
    /// The submitted BOUNDARY is indicted — the batch was too large to be
    /// accepted, by bytes or by record count. Nothing is wrong with the records.
    ///
    /// DISTINCT FROM EVERY `retain*` ABOVE, AND THE DIFFERENCE IS THE
    /// IN-FLIGHT DESCRIPTOR. A retain keeps the unresolved batch frozen so the
    /// next drain re-presents the SAME bytes — correct when a later condition
    /// can make that exact batch acceptable (a replaced key, an originated
    /// session, an opened gate). A boundary refusal is the one case where that
    /// is a guarantee of failure: re-presenting the identical oversized body
    /// asks a server that has already refused it, forever. So the descriptor is
    /// ABANDONED and a smaller prefix is built from the SAME durable records.
    ///
    /// AND IT MAY NEVER QUARANTINE. Before this existed, a 413 fell through
    /// to `.quarantinePrefix` — so a client that accumulated records precisely
    /// because it could not reach the server DESTROYED them on the first
    /// request that reached it. That is the defect; see `ChunkBounds`.
    ///
    /// Abandoning the descriptor is sound ONLY because a 413 on this edge is
    /// provably write-free: the parser refuses before the handler is entered,
    /// and the typed event-count refusal returns before the controller's
    /// PREFLIGHT/write boundary. `scripts/verify-server-413-write-free.mjs`
    /// asserts both, and refuses rather than passing when it cannot read the
    /// server it is asserting about.
    case resliceBatch
    /// The RECORDS are indicted. This, and only this, may delete the prefix.
    case quarantinePrefix
}

/// Build/capability refusals: an old build cannot grow fields by retrying.
public let PRODUCER_UNSUPPORTED_CODES: Set<String> = ["unknown_sdk_family", "unsupported_sdk_version"]

/// Session-precondition refusals: the chunk addressed a session that does not
/// exist. A statement about the address, not about the bytes.
public let SESSION_PRECONDITION_CODES: Set<String> = ["session_not_found", "session_precondition_missing"]

/// Feature-gate refusals — EXACTLY ONE CODE, deliberately.
///
/// SCOPE. This is not "network refusals retain". Every other network
/// validation code (`network_field_forbidden`, `network_protocol_hint_invalid`,
/// `batch_sequence_not_contiguous`, …) indicts the BYTES and still quarantines
/// the mixed prefix, unchanged by this fix. Only the capture switch being off
/// is a condition that a later request can satisfy with these same records.
///
/// DISPOSED BY CODE, NEVER BY STATUS OR BY THE SERVER'S `retriable` FLAG.
/// The deployed API's `reject(code, detail, retriable = false)` defaults to
/// PERMANENT, so one omitted argument on the server would otherwise turn a
/// feature flag into a deleter of customer telemetry.
public let FEATURE_GATE_CODES: Set<String> = ["network_capture_disabled"]

/// Boundary refusals — the batch was too large, by bytes or by record count.
///
/// `too_many_events` is the deployed controller's typed code; `chunk_too_large`
/// is the `error` name it pairs with that code, included so a server that ever
/// reports it as the CODE still re-slices rather than falling through to
/// quarantine. The parser's own refusal carries NO code at all, which is why
/// the status arm exists as well and is tried first.
public let RESLICE_CODES: Set<String> = ["too_many_events", "chunk_too_large"]

/// A bare 404 is included deliberately: it does not identify the submitted
/// batch, so it can never justify deleting records. Retaining a batch that will
/// never be accepted costs bounded queue space; quarantining one that WOULD
/// have been accepted costs the customer their data.
///
/// 413 IS DECIDED AFTER AUTH AND BEFORE EVERY CODE SET, and the direction of
/// that ordering is chosen by asymmetry rather than by taste. A 413 shadowed by
/// a code set could fall through to `.quarantinePrefix`, which DELETES records;
/// a code set shadowed by 413 merely re-slices, and the smaller batch then
/// receives the real typed refusal on its own terms. The non-destructive
/// direction wins, exactly as it does for the bare 404 below.
public func terminalDisposition(status: Int, code: String?) -> TerminalDisposition {
    if status == 401 || status == 403 { return .retainAuth }
    if status == 413 { return .resliceBatch }
    if let c = code, RESLICE_CODES.contains(c) { return .resliceBatch }
    if let c = code, PRODUCER_UNSUPPORTED_CODES.contains(c) { return .parkProducer }
    if let c = code, FEATURE_GATE_CODES.contains(c) { return .retainFeatureGate }
    if let c = code, SESSION_PRECONDITION_CODES.contains(c) { return .retainSessionPrecondition }
    if status == 404 { return .retainSessionPrecondition }
    return .quarantinePrefix
}

/// Exponential backoff with a ceiling, computed in Double then clamped: the
/// Android note records that a Long overflow wraps NEGATIVE and turns the
/// ceiling into a hot retry loop.
public func backoffMillis(attempt: Int, baseMs: Int64 = 60_000, ceilingMs: Int64 = 3_600_000) -> Int64 {
    let raw = Double(baseMs) * pow(2.0, Double(max(0, attempt - 1)))
    if raw >= Double(ceilingMs) { return ceilingMs }
    return Int64(raw)
}

/// The server's identity-bound acknowledgement (`mobile-ack-1`).
///
/// Every field describes the batch the server COMMITTED, so the client can
/// check the acknowledgement against what it actually sent before removing
/// anything. The previous Android contract was a bare `accepted:true` boolean
/// that bound to nothing — and a bare HTTP 2xx was then treated as permission
/// to clear the whole durable queue.
public struct Ack {
    public static let VERSION = "mobile-ack-1"
    public static let ACCEPTED = "accepted"
    public static let DUPLICATE = "duplicate"

    public let version: String
    public let state: String
    public let sessionId: String
    public let chunkId: String
    /// Sequences are STRINGS: the protocol types ordering keys as int64 and a
    /// Double-backed JSON number loses precision above 2^53. That is the one
    /// place a rounding error turns into deleted customer data.
    public let firstSequence: String
    public let lastSequence: String
    public let recordCount: Int
    public let contentSha256: String

    /// Both states mean the bytes are durably committed server-side.
    public var committed: Bool {
        version == Ack.VERSION && (state == Ack.ACCEPTED || state == Ack.DUPLICATE)
    }

    static let CANONICAL_SEQ = "^(0|[1-9][0-9]{0,18})$"
    static let SHA256_HEX = "^[0-9a-f]{64}$"

    private static func matches(_ s: String, _ pattern: String) -> Bool {
        s.range(of: pattern, options: .regularExpression) != nil
    }

    /// Parse an ack out of a response body. Returns nil for anything that is
    /// not a COMPLETE, well-typed `mobile-ack-1` — a 2xx with an unparseable
    /// or partial ack is NOT progress and must never dequeue anything.
    public static func parse(_ body: Data) -> Ack? {
        guard let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let a = root["ack"] as? [String: Any] else { return nil }
        guard let version = a["version"] as? String, version == VERSION,
              let state = a["state"] as? String, state == ACCEPTED || state == DUPLICATE,
              let sessionId = a["sessionId"] as? String, !sessionId.isEmpty,
              let chunkId = a["chunkId"] as? String, !chunkId.isEmpty,
              let first = a["firstSequence"] as? String, matches(first, CANONICAL_SEQ),
              let last = a["lastSequence"] as? String, matches(last, CANONICAL_SEQ),
              let count = a["recordCount"] as? Int, count > 0,
              let sha = a["contentSha256"] as? String, matches(sha, SHA256_HEX)
        else { return nil }
        return Ack(version: version, state: state, sessionId: sessionId, chunkId: chunkId,
                   firstSequence: first, lastSequence: last, recordCount: count,
                   contentSha256: sha)
    }
}

/// `frame-ack-1` — the identity-bound acknowledgement of ONE visual frame.
///
/// The endpoint also returns legacy `accepted`/`deduped` booleans. **They are
/// COMPATIBILITY FIELDS for a frozen artifact, not an alternate acknowledgement
/// path**: `accepted:true` binds to nothing, and `deduped` is OBJECT-level (two
/// DIFFERENT frames with identical bytes share one stored object), so reading it
/// as `duplicate` would report a settlement for a frame the server never saw.
/// This parser therefore does not look at either, and no future client may.
/// See `mobile-protocol/ADR-frame-ack-1.md`.
public enum FrameAckWire {
    public static let PROTO = "frame-ack-1"
    static let SHA256_HEX = "^[0-9a-f]{64}$"

    /// Returns nil for anything that is not a COMPLETE, well-typed frame-ack-1.
    /// A 2xx with an unparseable or partial ack is NOT progress and must never
    /// remove durable bytes.
    public static func parse(_ body: Data) -> FrameDelivery.FrameAck? {
        guard let o = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let proto = o["protocol"] as? String, proto == PROTO,
              let state = o["state"] as? String, state == "accepted" || state == "duplicate",
              let session = o["sessionId"] as? String, !session.isEmpty,
              let vf = o["visualFrameId"] as? String, !vf.isEmpty,
              let hash = o["contentHash"] as? String,
              hash.range(of: SHA256_HEX, options: .regularExpression) != nil,
              let size = (o["byteSize"] as? NSNumber)?.int64Value, size > 0
        else { return nil }
        return FrameDelivery.FrameAck(state: state, sessionId: session, visualFrameId: vf,
                                      contentHash: hash, byteSize: size)
    }
}

public struct UploadResult {
    public let action: ResponseAction
    public let status: Int
    /// Typed backend code (never a body dump) — safe to log.
    public let code: String?
    /// Nil when the response carried no parseable acknowledgement.
    public let ack: Ack?
    /// The FRAME acknowledgement, when this result came from a frame POST.
    public let frameAck: FrameDelivery.FrameAck?
    public init(action: ResponseAction, status: Int, code: String? = nil, ack: Ack? = nil,
                frameAck: FrameDelivery.FrameAck? = nil) {
        self.action = action; self.status = status; self.code = code
        self.ack = ack; self.frameAck = frameAck
    }
}

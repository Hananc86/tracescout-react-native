import Foundation

/// The UNRESOLVED-FRAME descriptor — the frame analogue of `InFlightBatch`,
/// and the Swift twin of rn-core `FrameInFlight.kt`.
///
/// WHY. The iOS frame drain removed the head record and only THEN re-appended a
/// copy with an incremented attempt count:
///
/// st.removeFirst(1) // <- bytes gone
/// if h.attempts + 1 < max { st.append(rotated) } // <- crash here loses them
///
/// Between those two calls the frame existed only in process memory. It also
/// settled on a bare 2xx — a proxy page, a truncated body and an error page
/// served with a 200 are all 2xx, and none of them is permission to delete
/// durable bytes. Both are the defects Android fixed; iOS carried them on a
/// path that IS reachable from the product (`TraceScoutRnModule` →
/// `enqueueFrame` → `drainFrames`).
///
/// The invariant, stated as for chunks:
///
/// Once a frame has been submitted and lacks a settled valid
/// acknowledgement, every retry uses the exact same frame identity and the
/// exact same encoded bytes, and those bytes are not removed from the
/// durable store until that acknowledgement arrives.
///
/// WHAT THIS ADDS OVER THE HEADER'S OWN `attempts`: the header only advances on
/// a COMPLETED rotation, so a process dying during every request would leave the
/// count at zero forever and retry without bound — a hazard the header cannot
/// see, because from its point of view no attempt ever finished. The descriptor
/// is written BEFORE the request and survives the death.
///
/// Deliberately NOT encrypted: an id, a digest, a byte length and a counter —
/// no image bytes, no screen text, no credential. A second crypto container
/// beside the proven queue would add a failure mode without protecting anything.
public struct FrameInFlight: Equatable {
    public let sessionId: String
    public let visualFrameId: String
    public let contentHash: String
    public let byteSize: Int
    public let attempts: Int
    public let firstAttemptAtMs: Int64

    public init(sessionId: String, visualFrameId: String, contentHash: String,
                byteSize: Int, attempts: Int, firstAttemptAtMs: Int64) {
        self.sessionId = sessionId; self.visualFrameId = visualFrameId
        self.contentHash = contentHash; self.byteSize = byteSize
        self.attempts = attempts; self.firstAttemptAtMs = firstAttemptAtMs
    }

    /// The identity a retry must reproduce EXACTLY. Same field order and same
    /// separator as Kotlin's, so the two platforms cannot drift into agreeing
    /// about different things.
    public func identity() -> String {
        "\(sessionId)|\(visualFrameId)|\(contentHash)|\(byteSize)"
    }

    public func encode() -> String {
        "{\"sessionId\":" + RnEnvelope.str(sessionId) +
        ",\"visualFrameId\":" + RnEnvelope.str(visualFrameId) +
        ",\"contentHash\":" + RnEnvelope.str(contentHash) +
        ",\"byteSize\":\(byteSize)" +
        ",\"attempts\":\(attempts)" +
        ",\"firstAttemptAtMs\":\(firstAttemptAtMs)}"
    }

    public static func decode(_ s: String) -> FrameInFlight? {
        guard let data = s.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let session = o["sessionId"] as? String,
              let vf = o["visualFrameId"] as? String, !vf.isEmpty,
              let hash = o["contentHash"] as? String, !hash.isEmpty,
              let bytes = o["byteSize"] as? Int,
              // A descriptor that does not describe a frame is not usable —
              // refuse it rather than retry something we cannot reproduce.
              bytes > 0
        else { return nil }
        return FrameInFlight(
            sessionId: session, visualFrameId: vf, contentHash: hash, byteSize: bytes,
            attempts: o["attempts"] as? Int ?? 0,
            firstAttemptAtMs: (o["firstAttemptAtMs"] as? NSNumber)?.int64Value ?? 0)
    }
}

/// Durable storage for the single unresolved frame. There is at most one: the
/// store is strict FIFO and the drain submits one frame at a time.
///
/// `save` must be durable BEFORE the request — a descriptor written after it
/// cannot survive a crash during it, which is exactly the window the defect
/// lives in.
public protocol FrameInFlightStore {
    func load() -> FrameInFlight?
    /// false when the descriptor is not durably readable back.
    func save(_ d: FrameInFlight) -> Bool
    func clear()
}

/// Process-lifetime only. The default for a caller that has not wired durable
/// storage: strictly better than nothing, never worse. It cannot satisfy the
/// crash/relaunch clause.
public final class MemoryFrameInFlightStore: FrameInFlightStore {
    private var held: FrameInFlight?
    private let lock = NSLock()
    public init() {}
    public func load() -> FrameInFlight? { lock.lock(); defer { lock.unlock() }; return held }
    public func save(_ d: FrameInFlight) -> Bool {
        lock.lock(); defer { lock.unlock() }; held = d; return true
    }
    public func clear() { lock.lock(); defer { lock.unlock() }; held = nil }
}

/// File-backed, written atomically and VERIFIED by reading back — the caller
/// submits a network request on the strength of that answer.
public final class FileFrameInFlightStore: FrameInFlightStore {
    private let url: URL
    private let lock = NSLock()
    public init(url: URL) { self.url = url }

    public func load() -> FrameInFlight? {
        lock.lock(); defer { lock.unlock() }
        guard let s = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return FrameInFlight.decode(s)
    }

    public func save(_ d: FrameInFlight) -> Bool {
        lock.lock(); defer { lock.unlock() }
        do {
            // `.atomic` writes to a temporary file and renames, so a crash can
            // never leave a half-written descriptor in place.
            try Data(d.encode().utf8).write(to: url, options: [.atomic])
        } catch { return false }
        guard let s = try? String(contentsOf: url, encoding: .utf8),
              let back = FrameInFlight.decode(s) else { return false }
        return back.identity() == d.identity() && back.attempts == d.attempts
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: url)
    }
}

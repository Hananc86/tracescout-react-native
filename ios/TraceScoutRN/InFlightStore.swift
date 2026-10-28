import Foundation

/// The UNRESOLVED-BATCH descriptor — the Swift twin of Kotlin `InFlightBatch`.
///
/// WHY THIS EXISTS — a real physical-device defect on THIS platform,
/// RN iOS 0.2.0 (7), 2026-08-25, session `ts_1787646516664_11f44b8acc07`:
///
/// chunk 613380715e985f7029c0629256464ada sequences 19–26 (8 records)
/// chunk 2b382959f874b59885cae83d808e0cbc sequences 19–29 (11 records)
///
/// The first submission did not settle. Three records were appended. The next
/// drain re-read the queue, took the longest contiguous session-owned prefix —
/// now ELEVEN records — and submitted it as a NEW batch. Different bytes mean a
/// different content hash, so the server's chunk-identity dedupe could not
/// collapse it and both committed: sequences 19–26 are stored twice.
///
/// Downstream `entry_id` dedupe meant no hierarchy entry materialised twice, so
/// visible correctness survived — but record storage was not exactly-once, and
/// a client must not rely on a server-side materialisation detail to be correct
/// about what it sent.
///
/// The invariant:
///
/// Once a batch has been submitted and lacks a settled valid
/// acknowledgement, every retry uses the exact same session identity, chunk
/// identity, record boundary and encoded bytes. Records appended later
/// cannot extend or alter that unresolved batch.
///
/// `digests` ARE the exact durable prefix boundary; `contentSha256` is the
/// digest of the rebuilt request body and is asserted before any retry, so a
/// byte-identical retry is guaranteed by construction rather than by hope.
public struct InFlightBatch: Equatable {
    public let sessionId: String
    public let chunkId: String
    public let firstSequence: String
    public let lastSequence: String
    public let recordCount: Int
    public let contentSha256: String
    public let digests: [String]
    public let firstAttemptAtMs: Int64
    public let attempts: Int
    /// The DURABLE terminal disposition, or nil while the batch is still
    /// ordinarily deliverable.
    ///
    /// WITHOUT THIS THE CROSS-PROCESS LOOP DOES NOT CLOSE. Records survive
    /// restart and this descriptor survives restart, but the REASON a slice was
    /// refused did not — so a process that died between the refusal and the
    /// completed park would wake up, know nothing, and POST the same impossible
    /// chunk again. Persisting the classification here is what makes "never ask
    /// the server twice" true across process death rather than only within one
    /// process. Twin of the Kotlin field; bounded metadata beside metadata that
    /// is already here — no payload, no URL, no header, no text, no node data.
    public let terminalReason: String?

    public init(sessionId: String, chunkId: String, firstSequence: String, lastSequence: String,
                recordCount: Int, contentSha256: String, digests: [String],
                firstAttemptAtMs: Int64, attempts: Int, terminalReason: String? = nil) {
        self.sessionId = sessionId; self.chunkId = chunkId
        self.firstSequence = firstSequence; self.lastSequence = lastSequence
        self.recordCount = recordCount; self.contentSha256 = contentSha256
        self.digests = digests; self.firstAttemptAtMs = firstAttemptAtMs; self.attempts = attempts
        self.terminalReason = terminalReason
    }

    public func encode() -> String {
        // BUILT IN EXPLICIT PIECES, NOT ONE `+` CHAIN. Adding the optional
        // terminalReason term to the single concatenation made swiftc give up:
        // "the compiler is unable to type-check this expression in reasonable
        // time". Each operand of a long `+` chain multiplies the overload
        // search, and the failure is a BUILD failure with no line that looks
        // wrong. Explicit `String` locals collapse it.
        let digestList: String = digests.map { RnEnvelope.str($0) }.joined(separator: ",")
        let terminal: String
        if let r = terminalReason {
            // Additive and OMITTED when absent, so a descriptor written by an
            // older build decodes unchanged and one written by this build is
            // readable by an older one (it ignores the key).
            terminal = ",\"terminalReason\":" + RnEnvelope.str(r)
        } else {
            terminal = ""
        }
        var out: String = "{\"sessionId\":" + RnEnvelope.str(sessionId)
        out += ",\"chunkId\":" + RnEnvelope.str(chunkId)
        out += ",\"firstSequence\":" + RnEnvelope.str(firstSequence)
        out += ",\"lastSequence\":" + RnEnvelope.str(lastSequence)
        out += ",\"recordCount\":\(recordCount)"
        out += ",\"contentSha256\":" + RnEnvelope.str(contentSha256)
        out += ",\"digests\":[" + digestList + "]"
        out += ",\"firstAttemptAtMs\":\(firstAttemptAtMs)"
        out += ",\"attempts\":\(attempts)"
        out += terminal
        out += "}"
        return out
    }

    public static func decode(_ s: String) -> InFlightBatch? {
        guard let data = s.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let chunkId = o["chunkId"] as? String, !chunkId.isEmpty,
              let digests = o["digests"] as? [String], !digests.isEmpty,
              let recordCount = o["recordCount"] as? Int,
              // A descriptor whose boundary disagrees with its own count is not
              // usable — refuse it rather than retry something we cannot
              // reproduce byte-identically.
              digests.count == recordCount
        else { return nil }
        return InFlightBatch(
            sessionId: o["sessionId"] as? String ?? "",
            chunkId: chunkId,
            firstSequence: o["firstSequence"] as? String ?? "",
            lastSequence: o["lastSequence"] as? String ?? "",
            recordCount: recordCount,
            contentSha256: o["contentSha256"] as? String ?? "",
            digests: digests,
            firstAttemptAtMs: (o["firstAttemptAtMs"] as? NSNumber)?.int64Value ?? 0,
            attempts: o["attempts"] as? Int ?? 0,
            // Absent -> nil -> ordinarily deliverable, which is exactly how
            // every pre-existing descriptor must read.
            terminalReason: (o["terminalReason"] as? String).flatMap { $0.isEmpty ? nil : $0 })
    }
}

/// Durable storage for the single unresolved batch.
///
/// There is at most ONE: the queue is strict FIFO and the pipeline submits one
/// prefix at a time. `save` must be durable BEFORE the first network attempt —
/// a descriptor written after the request cannot survive a crash during it,
/// which is exactly the window the defect lives in.
public protocol InFlightStore {
    func load() -> InFlightBatch?
    /// Returns false when the descriptor is not durably readable back.
    func save(_ batch: InFlightBatch) -> Bool
    func clear()
}

/// Process-lifetime only. The DEFAULT for callers that have not wired durable
/// storage: strictly better than before (the in-process half of the invariant
/// holds), never worse. It cannot satisfy the crash/relaunch clause.
public final class MemoryInFlightStore: InFlightStore {
    private var held: InFlightBatch?
    private let lock = NSLock()
    public init() {}
    public func load() -> InFlightBatch? { lock.lock(); defer { lock.unlock() }; return held }
    public func save(_ batch: InFlightBatch) -> Bool {
        lock.lock(); defer { lock.unlock() }; held = batch; return true
    }
    public func clear() { lock.lock(); defer { lock.unlock() }; held = nil }
}

/// File-backed, written atomically and verified by reading back.
///
/// Deliberately NOT encrypted: the descriptor carries digests, counts, sequence
/// bounds and ids — no record content, no text, no credential. A second crypto
/// container beside the proven queue would add a failure mode without
/// protecting anything.
public final class FileInFlightStore: InFlightStore {
    private let url: URL
    private let lock = NSLock()
    public init(url: URL) { self.url = url }

    public func load() -> InFlightBatch? {
        lock.lock(); defer { lock.unlock() }
        guard let s = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return InFlightBatch.decode(s)
    }

    public func save(_ batch: InFlightBatch) -> Bool {
        lock.lock(); defer { lock.unlock() }
        do {
            // `.atomic` writes to a temporary file and renames, so a crash can
            // never leave a half-written descriptor in place.
            try Data(batch.encode().utf8).write(to: url, options: [.atomic])
        } catch { return false }
        // Verify by reading back: the caller submits a network request on the
        // strength of this answer.
        guard let s = try? String(contentsOf: url, encoding: .utf8),
              let back = InFlightBatch.decode(s) else { return false }
        // `terminalReason` IS PART OF THE VERIFICATION. The caller removes
        // active bytes, or declines to POST, on the strength of this answer — a
        // save that reported success while silently dropping the terminal
        // reason would resurrect the exact cross-process loop this field exists
        // to close, and would do it invisibly. Anything this descriptor is
        // trusted for must be read back.
        return back.chunkId == batch.chunkId &&
            back.recordCount == batch.recordCount &&
            back.contentSha256 == batch.contentSha256 &&
            back.terminalReason == batch.terminalReason
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: url)
    }
}

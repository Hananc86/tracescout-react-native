import Foundation

/// ONE NETWORK OPERATION AGAINST ONE FRAME, IDENTIFIED SEPARATELY FROM THE
/// FRAME ITSELF.
///
/// THE FRAME IDENTITY AND THE OPERATION IDENTITY ARE NOT THE SAME THING, AND
/// CONFLATING THEM IS THE DEFECT THIS TYPE EXISTS TO PREVENT. The frame
/// identity `sessionId|visualFrameId|contentHash|byteSize` is IMMUTABLE — it is
/// what `/replay/frames` binds an acknowledgement to and what
/// `FrameDelivery.settle` searches the store for. The OPERATION identity names
/// one attempt to deliver those bytes. With a single in-flight frame the two
/// could be treated as one, because there was never a second attempt alive at
/// the same time. With a window of N there can be:
///
/// op A/1 times out -> its slot is freed -> op A/2 starts
/// the A/1 callback returns LATE
///
/// A/1 and A/2 name the SAME frame. Only the operation identity can tell them
/// apart, and without it a late A/1 callback would settle A/2's frame, release
/// A/2's slot, and remove bytes a live request is still using.
public struct FrameOperationRef: Equatable, Hashable {
    /// The lane owner's generation. A callback from a superseded generation can
    /// never be current, however fresh its sequence looks.
    public let generation: UInt64
    /// Monotonic within a generation, never reused. Distinguishes attempt N
    /// from attempt N+1 of the SAME frame.
    public let sequence: UInt64

    public init(generation: UInt64, sequence: UInt64) {
        self.generation = generation; self.sequence = sequence
    }
}

/// The durable record of one un-settled frame's delivery state.
///
/// IT IS PER FRAME, NOT PER ATTEMPT. A frame has at most one live operation,
/// so one descriptor carries both the immutable frame identity and the current
/// attempt's operation identity. Keeping one row per attempt would leave a
/// restart unable to say which attempt was the live one.
///
/// `attempt` LIVES HERE, NOT IN THE QUEUE RECORD'S HEADER. The header's
/// counter only advanced on a COMPLETED rotation, so a process dying inside
/// every request left it at zero forever and the frame retried without bound —
/// a hazard the header structurally cannot see. The descriptor is written
/// BEFORE the request and survives the death.
///
/// Deliberately NOT encrypted, and the field set IS the privacy boundary: two
/// ids, a digest, a byte length, two counters and two timestamps. No image
/// bytes, no screen text, no credential, no URL.
public struct FrameOperationDescriptor: Equatable {
    public let sessionId: String
    public let visualFrameId: String
    public let contentHash: String
    public let byteSize: Int
    public let generation: UInt64
    public let operationSequence: UInt64
    public let attempt: Int
    public let startedAtMs: Int64
    public let deadlineAtMs: Int64
    /// How many times this frame has been refused because its SESSION was not
    /// known to the server yet (`404 session_not_found`).
    ///
    /// IT IS NOT PART OF `attempt`, AND THAT SEPARATION IS THE FIX. A
    /// dependency refusal never reached the frame's own merits — the server
    /// never looked at the bytes — so counting it against the eight-attempt
    /// budget makes a transient prerequisite destroy customer data. It is
    /// carried instead as its own bounded counter, which drives the backoff and
    /// is reported by Diagnostics, and NEVER causes an `exhausted` settlement.
    public let dependencyWaits: Int
    /// The earliest moment this frame may be admitted again. Set only by the
    /// dependency path; 0 means "no cooldown".
    public let notBeforeMs: Int64

    public init(sessionId: String, visualFrameId: String, contentHash: String, byteSize: Int,
                generation: UInt64, operationSequence: UInt64, attempt: Int,
                startedAtMs: Int64, deadlineAtMs: Int64,
                dependencyWaits: Int = 0, notBeforeMs: Int64 = 0) {
        self.sessionId = sessionId; self.visualFrameId = visualFrameId
        self.contentHash = contentHash; self.byteSize = byteSize
        self.generation = generation; self.operationSequence = operationSequence
        self.attempt = attempt
        self.startedAtMs = startedAtMs; self.deadlineAtMs = deadlineAtMs
        self.dependencyWaits = dependencyWaits; self.notBeforeMs = notBeforeMs
    }

    /// The descriptor to persist after a `session_not_found` refusal.
    ///
    /// THE ATTEMPT IS GIVEN BACK. `step` spends one when it admits, before the
    /// request; a dependency refusal means that spend was never earned, so the
    /// counter returns to exactly what it was. The frame is then indistinguishable
    /// from one that had never been tried — which is the honest state, because
    /// the server did not evaluate it.
    public func afterSessionDependencyWait(
        nowMs: Int64,
        backoffBaseMs: Int64 = SessionDependencyBackoff.baseMs
    ) -> FrameOperationDescriptor {
        let waits = dependencyWaits + 1
        return FrameOperationDescriptor(
            sessionId: sessionId, visualFrameId: visualFrameId, contentHash: contentHash,
            byteSize: byteSize, generation: generation, operationSequence: operationSequence,
            attempt: Swift.max(0, attempt - 1),
            startedAtMs: startedAtMs, deadlineAtMs: deadlineAtMs,
            dependencyWaits: waits,
            notBeforeMs: nowMs &+ SessionDependencyBackoff.delayMs(waits: waits,
                                                                   base: backoffBaseMs))
    }

    /// The IMMUTABLE frame identity. Same field order and separator as
    /// `FrameInFlight.identity` and as the Kotlin twin, so the three cannot
    /// drift into agreeing about different things.
    public func frameIdentity() -> String {
        "\(sessionId)|\(visualFrameId)|\(contentHash)|\(byteSize)"
    }

    public var ref: FrameOperationRef {
        FrameOperationRef(generation: generation, sequence: operationSequence)
    }

    /// The settlement rule is shared with the single-flight pipeline rather
    /// than re-implemented: `FrameDelivery.ackRefusal` takes a `FrameInFlight`,
    /// and this is the same four identity fields under a different name. One
    /// rule, one place.
    public func asInFlight() -> FrameInFlight {
        FrameInFlight(sessionId: sessionId, visualFrameId: visualFrameId,
                      contentHash: contentHash, byteSize: byteSize,
                      attempts: attempt, firstAttemptAtMs: startedAtMs)
    }

    public func encode() -> String {
        "{\"sessionId\":" + RnEnvelope.str(sessionId) +
        ",\"visualFrameId\":" + RnEnvelope.str(visualFrameId) +
        ",\"contentHash\":" + RnEnvelope.str(contentHash) +
        ",\"byteSize\":\(byteSize)" +
        ",\"generation\":\(generation)" +
        ",\"operationSequence\":\(operationSequence)" +
        ",\"attempt\":\(attempt)" +
        ",\"startedAtMs\":\(startedAtMs)" +
        ",\"deadlineAtMs\":\(deadlineAtMs)" +
        ",\"dependencyWaits\":\(dependencyWaits)" +
        ",\"notBeforeMs\":\(notBeforeMs)}"
    }

    public static func decode(_ o: [String: Any]) -> FrameOperationDescriptor? {
        guard let session = o["sessionId"] as? String,
              let vf = o["visualFrameId"] as? String, !vf.isEmpty,
              let hash = o["contentHash"] as? String, !hash.isEmpty,
              let bytes = o["byteSize"] as? Int, bytes > 0
        else { return nil }
        return FrameOperationDescriptor(
            sessionId: session, visualFrameId: vf, contentHash: hash, byteSize: bytes,
            generation: (o["generation"] as? NSNumber)?.uint64Value ?? 0,
            operationSequence: (o["operationSequence"] as? NSNumber)?.uint64Value ?? 0,
            attempt: o["attempt"] as? Int ?? 0,
            startedAtMs: (o["startedAtMs"] as? NSNumber)?.int64Value ?? 0,
            deadlineAtMs: (o["deadlineAtMs"] as? NSNumber)?.int64Value ?? 0,
            // ABSENT DEFAULTS TO ZERO ON PURPOSE. A descriptor written by the
            // previous build has neither field; reading them as 0 means "no
            // dependency history, no cooldown", which is exactly how an upgraded
            // install should treat a frame it inherited. A default that invented
            // a cooldown would stall inherited frames for a reason nothing
            // measured.
            dependencyWaits: o["dependencyWaits"] as? Int ?? 0,
            notBeforeMs: (o["notBeforeMs"] as? NSNumber)?.int64Value ?? 0)
    }
}

/// The durable BOUNDED SET of un-settled frame operations.
///
/// THIS REPLACES `FrameInFlightStore`, WHOSE CONTRACT SAID "there is at most
/// one". That was true while the drain submitted one frame at a time and is the
/// single largest correctness change in the window: with N outstanding, one
/// shared descriptor cannot say which frame a completion belongs to, what
/// attempt it is, or which of N to resume after a process death.
///
/// `save` must be durable AND READ BACK BEFORE the request — a descriptor
/// written after it cannot survive a crash during it, which is exactly the
/// window the whole design exists to close. A save that cannot be verified
/// returns false and the caller sends NOTHING.
public protocol FrameWindowStore {
    func loadAll() -> [FrameOperationDescriptor]
    /// false when the descriptor is not durably readable back afterwards.
    @discardableResult func save(_ d: FrameOperationDescriptor) -> Bool
    /// The frame settled (delivered, duplicate, refused or exhausted): its
    /// delivery state is finished and must not be resumed.
    func release(frameIdentity: String)
    func clearAll()
}

extension FrameWindowStore {
    public func descriptor(frameIdentity: String) -> FrameOperationDescriptor? {
        loadAll().first { $0.frameIdentity() == frameIdentity }
    }
}

/// Process-lifetime only. The default for a caller that has not wired durable
/// storage: strictly better than nothing, never worse. It cannot satisfy the
/// crash/relaunch clause and the restart tests say so explicitly.
public final class MemoryFrameWindowStore: FrameWindowStore {
    private var held: [String: FrameOperationDescriptor] = [:]
    private let lock = NSLock()
    public init() {}
    public func loadAll() -> [FrameOperationDescriptor] {
        lock.lock(); defer { lock.unlock() }
        return held.values.sorted { $0.operationSequence < $1.operationSequence }
    }
    @discardableResult public func save(_ d: FrameOperationDescriptor) -> Bool {
        lock.lock(); defer { lock.unlock() }
        held[d.frameIdentity()] = d
        return held[d.frameIdentity()] == d
    }
    public func release(frameIdentity: String) {
        lock.lock(); defer { lock.unlock() }
        held.removeValue(forKey: frameIdentity)
    }
    public func clearAll() { lock.lock(); defer { lock.unlock() }; held.removeAll() }
}

/// File-backed. ONE document holding the whole bounded set, written atomically
/// and verified by reading back.
///
/// ONE DOCUMENT, NOT ONE FILE PER OPERATION. `.atomic` is a write-to-temp
/// plus rename, so a single document makes the whole set's transition atomic:
/// a crash leaves the previous complete set, never a half-updated one where two
/// files disagree about which attempt is live. The set is bounded by the window
/// width, so the whole-file rewrite is a few hundred bytes.
public final class FileFrameWindowStore: FrameWindowStore {
    private let url: URL
    private let lock = NSLock()
    /// A hard bound on what will be read back, so a corrupt or hostile file
    /// cannot make the drain allocate without limit.
    public static let maxDescriptors = 32

    public init(url: URL) { self.url = url }

    private func readUnlocked() -> [FrameOperationDescriptor] {
        guard let data = try? Data(contentsOf: url),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]]
        else { return [] }
        return arr.prefix(FileFrameWindowStore.maxDescriptors)
            .compactMap(FrameOperationDescriptor.decode)
    }

    private func writeUnlocked(_ all: [FrameOperationDescriptor]) -> Bool {
        let body = "[" + all.map { $0.encode() }.joined(separator: ",") + "]"
        do { try Data(body.utf8).write(to: url, options: [.atomic]) } catch { return false }
        return true
    }

    public func loadAll() -> [FrameOperationDescriptor] {
        lock.lock(); defer { lock.unlock() }
        return readUnlocked()
    }

    @discardableResult public func save(_ d: FrameOperationDescriptor) -> Bool {
        lock.lock(); defer { lock.unlock() }
        var all = readUnlocked().filter { $0.frameIdentity() != d.frameIdentity() }
        guard all.count < FileFrameWindowStore.maxDescriptors else { return false }
        all.append(d)
        guard writeUnlocked(all) else { return false }
        // READ BACK. The caller submits a network request on the strength of
        // this answer, so "I wrote it" is not good enough.
        guard let back = readUnlocked().first(where: { $0.frameIdentity() == d.frameIdentity() })
        else { return false }
        return back == d
    }

    public func release(frameIdentity: String) {
        lock.lock(); defer { lock.unlock() }
        let all = readUnlocked().filter { $0.frameIdentity() != frameIdentity }
        _ = writeUnlocked(all)
    }

    public func clearAll() {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: url)
    }
}

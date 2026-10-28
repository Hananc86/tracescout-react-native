import Foundation

/// THE BOUNDED OPERATION WINDOW OF ONE LANE OWNER.
///
/// ONE OWNER, ONE GENERATION, N OPERATIONS. There is exactly one
/// `DrainLaneOwner` for the frame lane and it is unchanged; this widens what a
/// single ticket may have outstanding. N independent owners would be N
/// independent leases, N reclaim decisions and N answers to "who owns this
/// queue" — the head-of-line defect solved by making the lanes independent,
/// re-created inside one lane.
///
/// lane generation G
/// +- op A -> frame A
/// +- op B -> frame B
/// +- op C -> frame C
/// +- op D -> frame D
///
/// ## Why the fence has to widen
///
/// `DrainLaneOwner.isCurrent(ticket)` answers "is this GENERATION still the
/// owner". That was a complete fence while one operation existed at a time,
/// because within a live generation there was only ever one thing to be late.
/// With N outstanding a callback can be stale INSIDE a live generation:
///
/// op A/1 times out, its slot is freed, op A/2 starts
/// the A/1 callback returns late, still in generation G
///
/// Generation-only fencing accepts it. `accepts(_:)` below does not: the
/// operation sequence is monotonic and never reused, so A/1's ref names an
/// operation that is no longer live and the callback mutates NOTHING —
/// it cannot settle A/2, cannot release B/C/D, cannot remove queue bytes and
/// cannot release the lane owner.
///
/// ## Why the deadline is not the lease
///
/// `RnTransport.laneLeaseMs` is 90 s and bounds how long the LANE may be
/// considered owned without a sign of life. Using it as an operation timeout
/// would let one dead request hold 1/N of the window for a minute and a half.
/// The operation deadline is a separate, much shorter number, chosen by
/// measurement — see `FrameWindowBenchmark`.
///
/// THE DEADLINE DOES NOT CANCEL ANYTHING. It frees the SLOT and fences the
/// operation out. A late completion is still delivered by the transport and is
/// still refused here, which is the point: cancelling an in-flight HTTP request
/// on iOS is not reliably immediate, so correctness cannot depend on it.
///
/// PURE ON PURPOSE. No dispatch, no URLSession, no clock of its own. The
/// whole window rule is executable on a runner with no simulator, which is what
/// lets the throughput benchmark and the fault injection drive the REAL
/// decisions instead of inferring them from a device.
public struct FrameWindow {

    /// THE WIDTH IS NOT PRESELECTED. The earlier "N=4 is sustainable"
    /// result belonged to a ~4.33 fps producer model; the Build-26/27 physical
    /// evidence (~5.5 fps producer, T2 p50 768 ms) supersedes that input and
    /// puts N=4 at ~5.21 fps — BELOW the producer before tail latency, retries,
    /// settlement cost or contention. The default here is whatever the
    /// benchmark selected; the benchmark itself takes the width as a parameter
    /// and refuses to assume one.
    public static let widthBounds = 1...8

    public let width: Int
    public let operationDeadlineMs: Int64

    private var generation: UInt64 = 0
    private var nextSequence: UInt64 = 0
    /// frameIdentity -> the ONE live operation for that frame.
    private var live: [String: FrameOperationDescriptor] = [:]

    public init(width: Int, operationDeadlineMs: Int64) {
        precondition(FrameWindow.widthBounds.contains(width), "window width out of bounds")
        precondition(operationDeadlineMs > 0, "an operation deadline must be positive")
        self.width = width
        self.operationDeadlineMs = operationDeadlineMs
    }

    // ─────────────────────────────────────────────────────────── generation

    /// Adopt the lane owner's generation. Anything still live from an older one
    /// is dropped: its owner was reclaimed, so it may not mutate anything and
    /// holding its slot would shrink the window for the new owner.
    ///
    /// IT DOES NOT TOUCH THE DURABLE STORE. A superseded operation's FRAME
    /// is still queued and still un-settled; its descriptor is what lets the
    /// new generation resume it with the attempt count intact.
    public mutating func beginGeneration(_ g: UInt64) {
        guard g != generation else { return }
        generation = g
        live.removeAll()
    }

    public var currentGeneration: UInt64 { generation }
    public var liveCount: Int { live.count }
    public var hasCapacity: Bool { live.count < width }
    public func isLive(frameIdentity: String) -> Bool { live[frameIdentity] != nil }
    public var liveOperations: [FrameOperationDescriptor] {
        live.values.sorted { $0.operationSequence < $1.operationSequence }
    }

    // ───────────────────────────────────────────────────────────── admission

    /// Take a slot for one attempt at one frame.
    ///
    /// Returns nil when the window is full, or when this frame already has a
    /// live operation — a second concurrent attempt at the SAME bytes would be
    /// a self-inflicted duplicate, and worse, two refs for one frame.
    ///
    /// `dependencyWaits` IS CARRIED FORWARD, NOT RESET. It is the history that
    /// drives the escalating dependency backoff; restarting it at zero on every
    /// re-admission would make a session that never registers retry at the base
    /// interval forever. `notBeforeMs` deliberately does NOT carry forward — the
    /// caller only admits a frame whose cooldown has already elapsed, so the new
    /// operation starts with a clear one.
    public mutating func admit(sessionId: String, visualFrameId: String,
                               contentHash: String, byteSize: Int,
                               attempt: Int, nowMs: Int64,
                               dependencyWaits: Int = 0) -> FrameOperationDescriptor? {
        let identity = "\(sessionId)|\(visualFrameId)|\(contentHash)|\(byteSize)"
        guard live[identity] == nil, live.count < width else { return nil }
        nextSequence &+= 1
        let d = FrameOperationDescriptor(
            sessionId: sessionId, visualFrameId: visualFrameId, contentHash: contentHash,
            byteSize: byteSize, generation: generation, operationSequence: nextSequence,
            attempt: attempt, startedAtMs: nowMs, deadlineAtMs: nowMs &+ operationDeadlineMs,
            dependencyWaits: dependencyWaits, notBeforeMs: 0)
        live[identity] = d
        return d
    }

    /// A slot taken by `admit` that the caller then decided not to use — above
    /// all when the durable descriptor could not be persisted and the request
    /// was therefore never made. Without this the window would leak a slot on
    /// every fail-closed refusal.
    public mutating func abandon(_ ref: FrameOperationRef) {
        guard let key = key(of: ref) else { return }
        live.removeValue(forKey: key)
    }

    // ───────────────────────────────────────────────────────────── the fence

    /// THE FENCE. Call before EVERY mutating completion action.
    ///
    /// True only when `ref` names an operation that is live RIGHT NOW under the
    /// CURRENT generation. Both axes are checked by construction:
    ///
    /// * a callback from a superseded lane generation — `beginGeneration`
    /// cleared it, so no live entry carries that ref;
    /// * a late callback from an earlier ATTEMPT at the same frame — the
    /// retry replaced the entry with a NEW monotonic sequence, so the old
    /// ref is not found.
    public func accepts(_ ref: FrameOperationRef) -> Bool {
        guard ref.generation == generation else { return false }
        return key(of: ref) != nil
    }

    /// The descriptor `ref` names, or nil when the fence refuses it.
    public func operation(_ ref: FrameOperationRef) -> FrameOperationDescriptor? {
        guard let k = key(of: ref), ref.generation == generation else { return nil }
        return live[k]
    }

    /// Release the slot for a completed operation. Returns false when the fence
    /// refuses — the caller must then mutate NOTHING.
    @discardableResult
    public mutating func complete(_ ref: FrameOperationRef) -> Bool {
        guard accepts(ref), let k = key(of: ref) else { return false }
        live.removeValue(forKey: k)
        return true
    }

    private func key(of ref: FrameOperationRef) -> String? {
        for (k, d) in live where d.operationSequence == ref.sequence
            && d.generation == ref.generation { return k }
        return nil
    }

    // ───────────────────────────────────────────────────────────── deadlines

    /// Operations whose deadline has passed. They lose their slot and are
    /// fenced out; their FRAMES stay queued and un-settled, so the next step
    /// re-admits them under a new operation with the attempt count carried in
    /// the durable descriptor.
    ///
    /// STRICTLY GREATER, matching `DrainLaneOwner`'s lease. An operation is
    /// live THROUGH its deadline millisecond; expiring on the boundary would
    /// discard a completion that arrived exactly on time.
    public mutating func expire(nowMs: Int64) -> [FrameOperationDescriptor] {
        let dead = live.values.filter { nowMs > $0.deadlineAtMs }
        for d in dead { live.removeValue(forKey: d.frameIdentity()) }
        return dead.sorted { $0.operationSequence < $1.operationSequence }
    }

    /// The next moment anything in the window can change on its own. A pump
    /// waiting for a completion should not sleep past this, or a dead operation
    /// holds its slot until some unrelated event happens to wake the loop.
    public func earliestDeadlineMs() -> Int64? {
        live.values.map(\.deadlineAtMs).min()
    }
}

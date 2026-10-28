import Foundation

/// ONE WORKER PER LANE. A REQUEST MAY COALESCE, BUT IT MAY NOT DISAPPEAR —
/// AND OWNERSHIP MAY NOT OUTLIVE THE WORKER THAT HOLDS IT.
///
/// ## Defect 1 — the cross-lane block (RN iOS 0.3.0 (23))
///
/// Session `ts_1788790394238_28a93b194048`: 160 visual frames were admitted to
/// the durable frame store and **zero** were ever posted, while the record/chunk
/// lane drained normally. Both lanes ran under ONE `draining` bool with the
/// frame drain as a tail call after the record drain, so one stalled record
/// request head-of-line blocked an INDEPENDENT durable queue. Two flags plus a
/// REQUESTED bit per lane fixed that:
///
/// request arrives, worker idle -> start exactly one worker
/// request arrives, worker busy -> set requested; start nothing
/// pass ends, requested or work -> run another bounded pass
/// pass ends, neither -> release ownership
///
/// ## Defect 2 — ownership with no bound (RN iOS 0.3.0 (24))
///
/// Session `ts_1788840274608_0549138a24cc`: 199 frames declared, **55 delivered**,
/// then the byte lane went silent for **15.3 hours** while the record lane ran
/// on for another 25 seconds and 205/205 enqueues kept succeeding. The device
/// held a durable in-flight descriptor for `vf_15e2962156484c018713285` at
/// `attempts = 1`; the server had verified those exact bytes at 04:04:47.572Z.
/// Across the whole window: **60 posts, 59 settlements** — exactly ONE post
/// that never produced a settlement and never produced an invalid-ack line.
/// The pass reached the network, the server answered, and control never came
/// back to the continuation that would have released the lane.
///
/// Ownership was released ONLY by `finishPass` or `abandon`, both of which live
/// AFTER that point. So `busy` stayed true, every later `requestDrain` returned
/// `false` from `request`, and the lane could not start again for the life of
/// the process. **The record lane was unaffected because it owns a separate
/// token — the independence added for defect 1 is what kept the session alive
/// long enough to prove that only the frame lane had died.**
///
/// A SEMAPHORE TIMEOUT CANNOT FIX THIS, and the reason is the same one
/// recorded for defect 1. The posters bound their waits with
/// `DispatchSemaphore.wait(timeout:.now + n)`, and `DispatchTime` is
/// MONOTONIC: it does not advance while iOS has the process suspended. A thread
/// parked on that wait when the app is suspended does not time out — defect 1's
/// request did not return for 40 min 51 s. The lease below is therefore
/// deliberately **wall-clock** (`Date`), which DOES advance across suspension,
/// so a resumed app finds the lease expired and can reclaim the lane.
///
/// ## The lease, and why reclaim is safe
///
/// Ownership now carries a GENERATION and a wall-clock DEADLINE. A live owner
/// renews around each unit of work; a request that finds an EXPIRED lease
/// presumes the previous owner lost and takes over under a NEW generation.
///
/// Every mutating call is FENCED on the generation, so a lost worker that
/// returns late cannot release, extend or otherwise mutate a lane a newer
/// worker now holds — its calls are refused and it simply ends. Concurrency is
/// bounded by construction: a reclaim mints exactly one new owner, and no
/// further reclaim is possible until THAT lease expires, so there is at most
/// one live owner plus however many abandoned ones are still unwinding — and an
/// abandoned one can do nothing.
///
/// The cost of a reclaim is a bounded DUPLICATE delivery, never a loss: the
/// frame lane's durable `FrameInFlight` descriptor makes the retry byte-identical
/// and `/replay/frames` is content-hash addressed, so the server collapses it and
/// answers `duplicate`. **Duplication over loss is the correct trade on a queue
/// holding keyframes nothing can recreate** — the same reasoning already written
/// into the append-first rotation.
///
/// THE LEASE IS NOT A REQUEST TIMEOUT. It does not cancel anything and it
/// does not shorten any HTTP attempt. It bounds only how long the LANE may be
/// considered owned without a sign of life. Set it comfortably longer than one
/// unit of work (see `RnTransport`), or a healthy slow pass gets preempted and
/// the duplicates stop being bounded in practice.
///
/// PURE ON PURPOSE. No UIKit, no Security, no dispatch, and the clock is a
/// parameter. The whole ownership rule is executable on a runner with no
/// simulator, which is what lets the liveness regressions drive it directly
/// instead of inferring it from a device.
public struct DrainLaneOwner: Equatable {

    /// Proof that a caller is the owner it claims to be. Opaque on purpose: a
    /// worker cannot forge a newer generation, and comparing tickets is the
    /// ONLY way ownership is established.
    public struct Ticket: Equatable {
        fileprivate let generation: UInt64
        fileprivate init(_ g: UInt64) { generation = g }
    }

    /// What a request produced. `reclaimed` is deliberately DISTINCT from
    /// `started`: a takeover means a previous owner was presumed lost, which is
    /// a real event the trace must be able to record. Collapsing the two would
    /// make a liveness failure indistinguishable from an ordinary start —
    /// exactly the ambiguity that let defect 2 look like silence.
    public enum Grant: Equatable {
        /// The lane was idle. Start a worker with this ticket.
        case started(Ticket)
        /// A live owner exists and has been told there is more to do.
        case coalesced
        /// The previous owner's lease expired. Start a worker with this ticket;
        /// `lostGeneration` identifies the owner presumed lost.
        case reclaimed(Ticket, lostGeneration: UInt64)
    }

    private var busy = false
    private var requested = false
    private var generation: UInt64 = 0
    private var leaseDeadlineMs: Int64 = 0

    public init() {}

    /// Someone asked this lane to drain.
    ///
    /// - Parameters:
    /// - nowMs: WALL-CLOCK milliseconds. Must advance across process
    /// suspension — see the note above about `DispatchTime`.
    /// - leaseMs: how long the granted ownership is honoured without renewal.
    public mutating func request(nowMs: Int64, leaseMs: Int64) -> Grant {
        if busy {
            // STRICTLY GREATER. A lease is live through its deadline
            // millisecond; expiring ON the boundary would let a request that
            // arrives in the same millisecond as a renewal reclaim a lane whose
            // owner just proved it was alive.
            if nowMs > leaseDeadlineMs {
                let lost = generation
                generation &+= 1
                requested = false
                leaseDeadlineMs = nowMs &+ leaseMs
                return .reclaimed(Ticket(generation), lostGeneration: lost)
            }
            requested = true
            return .coalesced
        }
        busy = true
        requested = false
        generation &+= 1
        leaseDeadlineMs = nowMs &+ leaseMs
        return .started(Ticket(generation))
    }

    /// A live owner proving it is still working. Call around each unit of work,
    /// NOT once per pass — a pass that posts many frames sequentially can
    /// legitimately outlive any lease short enough to be useful.
    ///
    /// - Returns: false when the caller is no longer the owner. A worker that
    /// sees false has been reclaimed and MUST stop without mutating anything.
    @discardableResult
    public mutating func renew(_ t: Ticket, nowMs: Int64, leaseMs: Int64) -> Bool {
        guard isCurrent(t) else { return false }
        leaseDeadlineMs = nowMs &+ leaseMs
        return true
    }

    /// A bounded pass finished.
    ///
    /// - Parameter moreDurableWork: the pass made progress, or the lane still
    /// holds undelivered records. Either is a reason to continue without
    /// waiting for an external trigger.
    /// - Returns: true iff the worker must run ANOTHER pass. False means either
    /// ownership was released, or this caller never held it.
    @discardableResult
    public mutating func finishPass(_ t: Ticket,
                                    moreDurableWork: Bool,
                                    nowMs: Int64,
                                    leaseMs: Int64) -> Bool {
        // FENCED. A stale worker returning late must not release a lane a
        // newer owner now holds — that would hand a live queue to two workers
        // and turn one lost callback into unbounded concurrency.
        guard isCurrent(t) else { return false }
        if requested || moreDurableWork {
            requested = false
            leaseDeadlineMs = nowMs &+ leaseMs
            return true
        }
        busy = false
        return false
    }

    /// Give up ownership without completing a pass — for the paths that bail
    /// before any work (no store, no configuration). Without this a refusal
    /// would leave the lane owned by nobody until the lease expired.
    ///
    /// Fenced for the same reason as `finishPass`.
    public mutating func abandon(_ t: Ticket) {
        guard isCurrent(t) else { return }
        busy = false
        requested = false
    }

    /// Whether this ticket still names the current owner. The fence every
    /// mutating step is conditioned on.
    public func isCurrent(_ t: Ticket) -> Bool {
        busy && t.generation == generation
    }

    public var isBusy: Bool { busy }
    public var hasPendingRequest: Bool { requested }
    /// Exposed for the trace only — never for a decision.
    public var currentGeneration: UInt64 { generation }
}

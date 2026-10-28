import Foundation

/// THE DEADLINE OWNER — who actually makes the bounded wait happen in production.
///
/// A CLOCKLESS REGISTRY AND SYNTHETIC TICKS DO NOT PROVE A BOUNDED WAIT.
/// The registry evaluates the budget against an injected clock, which is the
/// right shape for a decision — but a decision nobody asks for is never made.
/// Without a real deadline, expiry would only be discovered on the NEXT
/// navigation or lifecycle call, and the "bounded" wait would be bounded by user
/// behaviour rather than by the budget.
///
/// It owns WHO schedules (an injected `Scheduler`; production is
/// `DispatchQueue.main.asyncAfter`), WHAT fires (one callback), and HOW IT IS
/// CANCELLED — route or session replacement cancels through the same path, so a
/// fired-but-stale callback is impossible rather than filtered later.
///
/// IT DECIDES NOTHING. It only says "the budget may now have elapsed — go and
/// look". The verdict stays in the registry plus the shared rule, so a deadline
/// that fires early or late cannot manufacture a reason.
public final class AttachDeadline {

    public protocol Scheduler {
        /// Run `task` after `delayMs`. Returns a handle for cancellation.
        func schedule(delayMs: Int64, task: @escaping () -> Void) -> AnyObject
        /// Cancel a previously scheduled task. Idempotent.
        func cancel(_ handle: AnyObject)
    }

    /// Production: the main queue, which is also where capture runs.
    public final class MainQueueScheduler: Scheduler {
        public init() {}
        public func schedule(delayMs: Int64, task: @escaping () -> Void) -> AnyObject {
            let item = DispatchWorkItem(block: task)
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs)), execute: item)
            return item
        }
        public func cancel(_ handle: AnyObject) { (handle as? DispatchWorkItem)?.cancel() }
    }

    private let scheduler: Scheduler
    private let lock = NSLock()
    private var handle: AnyObject?

    /// Observability — a deadline that never fires must be visible, not silent.
    public private(set) var scheduledCount = 0
    public private(set) var firedCount = 0
    public private(set) var cancelledCount = 0

    public init(scheduler: Scheduler) { self.scheduler = scheduler }

    /// Arm the deadline. An existing one is REPLACED, not stacked: two live
    /// deadlines for one attempt would call back twice, and "exactly once" would
    /// then rest on the emitter's gate alone rather than on both.
    public func arm(delayMs: Int64, onElapsed: @escaping () -> Void) {
        lock.lock()
        if let h = handle { scheduler.cancel(h); cancelledCount += 1 }
        scheduledCount += 1
        handle = scheduler.schedule(delayMs: delayMs) { [weak self] in
            self?.lock.lock(); self?.handle = nil; self?.firedCount += 1; self?.lock.unlock()
            // OUTSIDE THE LOCK: the callback re-enters the bridge, which may
            // emit and may arm again.
            onElapsed()
        }
        lock.unlock()
    }

    public func cancel() {
        lock.lock()
        if let h = handle { scheduler.cancel(h); cancelledCount += 1 }
        handle = nil
        lock.unlock()
    }

    /// Diagnostic only; never a decision input.
    public func isArmed() -> Bool { lock.lock(); defer { lock.unlock() }; return handle != nil }
}

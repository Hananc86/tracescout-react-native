package com.tracescout.rn

/**
 * THE DEADLINE OWNER — who actually makes the bounded wait happen in production.
 *
 * A CLOCKLESS REGISTRY AND SYNTHETIC TICKS DO NOT PROVE A BOUNDED WAIT.
 * `RouteHostRegistry` evaluates the budget against an injected clock, which is
 * the right shape for a decision — but a decision nobody asks for is never
 * made. Without a real deadline, expiry would only ever be discovered on the
 * NEXT navigation or lifecycle call, so a screen entered before the app goes
 * idle would sit unresolved indefinitely and the "bounded" wait would be
 * bounded by user behaviour rather than by the budget.
 *
 * This is the seam that fires the deadline. It owns:
 *
 * - WHO schedules it an injected [Scheduler] (Android: a Handler on the
 * main looper; iOS: DispatchQueue.main.asyncAfter)
 * - WHAT it does calls back exactly once, so the caller can
 * re-evaluate and emit
 * - HOW IT IS CANCELLED [cancel] removes the pending callback, and route
 * or session replacement cancels through the same
 * path — a fired deadline for a screen the user has
 * left must not reach the emitter at all
 *
 * IT DOES NOT DECIDE ANYTHING. It only says "the budget may now have
 * elapsed — go and look". The verdict stays in `RouteHostRegistry.evaluate`
 * plus the shared rule, so there is still exactly one place the two reasons are
 * derived, and a deadline that fires early or late cannot manufacture a reason.
 */
class AttachDeadline(
    private val scheduler: Scheduler,
) {

    /**
 * The platform's real scheduler. Injected rather than constructed so this
 * type stays JVM-testable — but note the test double is a *scheduler*, not
 * a clock: the production path is exercised as "something scheduled a
 * callback and the callback ran", which is the property that matters.
 */
    interface Scheduler {
        /** Run [task] after [delayMs]. Returns a handle for cancellation. */
        fun schedule(delayMs: Long, task: () -> Unit): Any
        /** Cancel a previously scheduled task. Idempotent. */
        fun cancel(handle: Any)
    }

    private val lock = Any()
    private var handle: Any? = null

    /** Observability — a deadline that never fires must be visible, not silent. */
    @Volatile var scheduledCount: Int = 0
        private set
    @Volatile var firedCount: Int = 0
        private set
    @Volatile var cancelledCount: Int = 0
        private set

    /**
 * Arm the deadline. An existing one is REPLACED, not stacked: two live
 * deadlines for one attempt would call back twice, and "exactly once" would
 * then rest on the emitter's gate alone rather than on both.
 */
    fun arm(delayMs: Long, onElapsed: () -> Unit) = synchronized(lock) {
        handle?.let { scheduler.cancel(it); cancelledCount++ }
        scheduledCount++
        handle = scheduler.schedule(delayMs) {
            synchronized(lock) { handle = null; firedCount++ }
            // OUTSIDE THE LOCK. The callback re-enters the bridge, which may
            // emit and may arm again; holding this lock across that would be a
            // self-deadlock waiting for a real device to find.
            onElapsed()
        }
        Unit
    }

    /**
 * Cancel a pending deadline. Called on route change, session change, and
 * whenever an attempt settles — so a fired-but-stale callback is impossible
 * rather than merely filtered later.
 */
    fun cancel() = synchronized(lock) {
        handle?.let { scheduler.cancel(it); cancelledCount++ }
        handle = null
        Unit
    }

    /** Is a deadline currently armed? Diagnostic only; never a decision input. */
    fun isArmed(): Boolean = synchronized(lock) { handle != null }
}

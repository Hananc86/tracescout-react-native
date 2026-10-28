package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * LAYER 1 of the one lock order: SAME-PROCESS serialisation of a queue domain.
 *
 * ```
 * >> QUEUE REGION LOCK << -> QUEUE FILE FENCE -> STORE MONITOR -> AUTHORITY.
 * ```
 *
 * ## What it preserves, and why that is the point
 *
 * Before this slice every public member of `EncryptedEventStore` was
 * `@Synchronized`: two threads appending SERIALISED, and the loser waited. That
 * behaviour class is correct and must survive the move to an explicit lock —
 * a second thread holding the queue is not a reason to drop a record, and a
 * record dropped for that reason is an unaccounted loss invented by the lock.
 *
 * So ordinary regions BLOCK, interruptibly, for as long as the owner holds
 * them. There is no "give up after N attempts" for [QueueRegion.Acquisition.SERIALIZE];
 * an earlier design applied one uniformly (150 x 20 ms then `Unavailable`) and
 * that is exactly the conversion this class exists to undo.
 *
 * The wait is MEASURED rather than capped — [waitStats] is what M5 reads — and
 * a wait that crosses [VISIBLE_WAIT_MS] is traced, so a pathological hold is
 * observable without being turned into data loss.
 *
 * ## The crash path is the one exception
 *
 * [QueueRegion.CRASH_APPEND] is [QueueRegion.Acquisition.BOUNDED]: a short,
 * ruled try-acquire and then a TYPED refusal. It is racing the process's own
 * death, so it must never wait through a bracket — and the refusal is
 * accounted, never silent.
 *
 * ## Fairness
 *
 * `ReentrantLock(fair = true)`. An unfair lock can starve a waiter indefinitely
 * under sustained append pressure, and the waiter here may be the teardown or
 * recovery pass that has to finish for the queue to be correct at all. Fairness
 * costs throughput; the alternative costs a bounded wait nobody can bound.
 *
 * Reentrant per THREAD, so a nested region (a park write inside a park move, a
 * store mutation inside a bracket) is ONE acquisition held throughout rather
 * than two locks in an order.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class QueueRegionLock private constructor(
    /** The canonical domain path. The same value [QueueFileFence] keys on. */
    val domainPath: String,
) {

    /**
 * Refused, with the reason. There is deliberately no third state and no
 * proceed-unlocked branch.
 *
 * THE REASONS ARE DISTINCT ON PURPOSE. A same-process crash-budget
 * expiry and an interrupted wait are different facts about different
 * conditions, and neither is the cross-process
 * [QueueFileFence.Unavailable]. Folding them into one reason would leave a
 * caller unable to tell "another legitimate owner has the queue" from "this
 * thread was cancelled" from "another PROCESS holds the install".
 */
    class Unavailable(
        val region: QueueRegion,
        val reason: Reason,
        val waitedMs: Long,
    ) : RuntimeException("queue_region_unavailable:${reason.wire}:${region.name.lowercase()}")

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    enum class Reason(val wire: String) {
        /** A BOUNDED region's ruled budget expired. Only the crash path. */
        CRASH_BUDGET_EXPIRED("crash_budget_expired"),

        /** The waiting thread was interrupted. The interrupt flag is restored. */
        INTERRUPTED("interrupted"),
    }

    private val lock = ReentrantLock(true)

    private val contendedAcquisitions = AtomicLong(0)
    private val contendedWaitNanos = AtomicLong(0)
    private val maxWaitNanos = AtomicLong(0)

    /**
 * Cumulative same-process contention, for M5.
 *
 * THIS IS THE MEASUREMENT THAT REPLACES THE CAP. The old design bounded
 * the wait and therefore never had to know how long anything waited; the
 * bound WAS the answer. Here the wait is unbounded by design, so the wait
 * has to be a number somebody can read.
 */
    data class WaitStats(
        val contendedAcquisitions: Long,
        val totalWaitMs: Long,
        val maxWaitMs: Long,
    )

    fun waitStats(): WaitStats = WaitStats(
        contendedAcquisitions.get(),
        contendedWaitNanos.get() / 1_000_000,
        maxWaitNanos.get() / 1_000_000,
    )

    /** True when the calling thread is inside a region on this domain. */
    fun isHeldByCurrentThread(): Boolean = lock.isHeldByCurrentThread

    /** Threads currently waiting. Diagnostics only; never a decision input. */
    fun queuedThreadCount(): Int = lock.queueLength

    /**
 * Run [block] with the region held.
 *
 * @throws Unavailable only for a [QueueRegion.Acquisition.BOUNDED] region
 * whose budget expired, or an interrupted wait. An ordinary region WAITS.
 */
    fun <T> withRegion(region: QueueRegion, block: () -> T): T {
        if (lock.isHeldByCurrentThread) {
            // Already inside a region on this thread: one acquisition, nested
            // use. Re-entering must never consult a budget — a nested crash
            // append inside a region this thread already owns cannot be refused
            // for contention that does not exist.
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
        acquire(region)
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun acquire(region: QueueRegion) {
        val began = System.nanoTime()
        when (region.acquisition) {
            QueueRegion.Acquisition.SERIALIZE -> {
                if (lock.tryLock()) return   // uncontended: no measurement cost
                try {
                    lock.lockInterruptibly()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    val waited = elapsedMs(began)
                    RecoveryTrace.log(
                        "queue_region_interrupted",
                        "region" to region.name, "waitedMs" to waited,
                    )
                    throw Unavailable(region, Reason.INTERRUPTED, waited)
                }
                recordWait(region, began)
            }

            QueueRegion.Acquisition.BOUNDED -> {
                // Uncontended: no measurement, exactly as for SERIALIZE. Without
                // this every crash append was COUNTED as a contended wait, so
                // the statistic that replaced the cap could not tell a crash
                // path that waited from one that never had to.
                if (lock.tryLock()) return
                val got = try {
                    lock.tryLock(BOUNDED_BUDGET_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    val waited = elapsedMs(began)
                    throw Unavailable(region, Reason.INTERRUPTED, waited)
                }
                if (!got) {
                    val waited = elapsedMs(began)
                    RecoveryTrace.log(
                        "queue_region_refused",
                        "region" to region.name, "why" to Reason.CRASH_BUDGET_EXPIRED.wire,
                        "waitedMs" to waited,
                    )
                    throw Unavailable(region, Reason.CRASH_BUDGET_EXPIRED, waited)
                }
                recordWait(region, began)
            }
        }
    }

    private fun recordWait(region: QueueRegion, began: Long) {
        val nanos = System.nanoTime() - began
        contendedAcquisitions.incrementAndGet()
        contendedWaitNanos.addAndGet(nanos)
        maxWaitNanos.updateAndGet { m -> if (nanos > m) nanos else m }
        val ms = nanos / 1_000_000
        if (ms >= VISIBLE_WAIT_MS) {
            RecoveryTrace.log(
                "queue_region_waited", "region" to region.name, "waitedMs" to ms,
            )
        }
    }

    private fun elapsedMs(began: Long): Long = (System.nanoTime() - began) / 1_000_000

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /**
 * The BOUNDED budget, in milliseconds — DERIVED from the one crash
 * acquisition policy, never restated here.
 *
 * NOT A SOURCE. `F4Constants.CrashAcquisition` is the single home of
 * the provisional crash policy for BOTH lock layers; this constant is
 * the region layer's read of it, kept under its own name so the store's
 * documentation and the crash rows can refer to the layer they mean.
 * `QueueOrderTest` A50 asserts the initialiser is exactly that
 * reference and that no numeric crash-policy literal exists in this
 * file. (Why the policy is provisional rather than acceptance-gated is
 * explained on the policy object.)
 */
        const val BOUNDED_BUDGET_MS: Long = F4Constants.CrashAcquisition.REGION_BUDGET_MS

        /** A same-process wait at or above this is traced. Diagnostics only. */
        const val VISIBLE_WAIT_MS: Long = 100L

        /**
 * ITS OWN REGISTRY, INDEPENDENT OF [QueueHome]'s AND
 * [QueueFileFence]'s. `QueueHome.forgetForTest` clears homes so a
 * fresh temp directory starts clean; it must NOT be able to hand out a
 * SECOND lock object for a path some live store is still serialising
 * on, because two locks over one domain serialise nothing. Separate
 * registries make that structural, and `QueueOrderTest` asserts the
 * identity survives a forget by REFERENCE.
 */
        private val instances = ConcurrentHashMap<String, QueueRegionLock>()

        @JvmStatic
        fun forStoreFile(storeFile: File): QueueRegionLock =
            forDomainPath(QueueDomain.pathFor(storeFile))

        /**
 * The lock for an ALREADY-DERIVED domain path.
 *
 * THIS IS [QueueFileFence]'s DOOR, AND IT IS WHY THE FENCE CAN ENFORCE
 * THE ORDER INSTEAD OF DOCUMENTING IT. The fence must only ever be taken
 * by a thread that already owns the region lock for the same domain; to
 * check that, it has to be able to name the region lock for its own
 * path. Same registry, same memoisation, so the object it gets is the
 * one every store on that domain serialises on.
 */
        internal fun forDomainPath(domainPath: String): QueueRegionLock =
            instances.computeIfAbsent(domainPath) { QueueRegionLock(it) }
    }
}

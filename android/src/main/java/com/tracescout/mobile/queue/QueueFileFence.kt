package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap

/**
 * LAYER 2 of the one lock order: CROSS-PROCESS exclusion of a queue domain.
 *
 * ```
 * QUEUE REGION LOCK -> >> QUEUE FILE FENCE << -> STORE MONITOR -> AUTHORITY.
 * ```
 *
 * ## Why this is a SEPARATE type from [QueueRegionLock]
 *
 * Different ownership rules, different contention behaviour, different
 * diagnostics — and one class holding both is how every region came to be "150
 * attempts then drop the record". Here the other owner is a PROCESS: there is
 * no thread to queue behind, no way to ask how long it intends to hold, and no
 * guarantee it is still alive in any sense this process can observe. So
 * acquisition is BOUNDED and an unavailable fence is a typed refusal — which is
 * honest here, and was not honest for the JVM lock.
 *
 * The queue is genuinely written by more than one process: the fixture's
 * `:writer` and `:crash` services construct `EncryptedEventStore` directly, and
 * the UAT diagnostics module reads it. A JVM monitor cannot see any of them.
 *
 * ## Properties that matter
 *
 * * **Only acquired under the region lock.** `withFence` refuses a thread that
 * does not own the [QueueRegionLock] for the same domain
 * ([Reason.REGION_NOT_HELD]) — the order is checked, not assumed.
 * * **No proceed-unlocked branch** — an unavailable fence is a typed refusal,
 * never a weaker mode of the same operation.
 * * **Released by descriptor teardown**, so process death releases it naturally
 * and no shutdown hook is required.
 * * **The lock file is never unlinked** — deleting it is how two processes stop
 * sharing a lock, so `QueueKeyStores.ALL` deliberately does not list it.
 * * **Not durable.** Intra-run exclusivity only; the INTER-run half is the
 * eviction marker, which a later slice installs. Nothing here claims the lock
 * survives a restart.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class QueueFileFence private constructor(
    private val lockFile: File,
    /**
 * The SAME-PROCESS layer for this domain — held as a reference so the order
 * `region lock -> file fence` is CHECKED here, not merely followed by every
 * caller that happens to exist today.
 */
    private val regionLock: QueueRegionLock,
) {

    /**
 * Refused, with the reason.
 *
 * [Reason.LOCK_INVARIANT] IS NOT [Reason.CROSS_PROCESS_BUSY], AND THE
 * RULING SAYS SO. "Another legitimate owner has the install" is ordinary
 * and expected; "this process already holds the OS lock outside the fence"
 * is a defect in our own code, and mapping the two to one reason would hide
 * a bug behind a condition that clears on its own. It is TYPED rather than
 * thrown at production callers — a legitimate second owner must not crash
 * the app — but it is traced as an invariant violation and
 * [strictInvariants] makes it fatal for tests and debug builds, which is
 * where "fail loudly" belongs.
 */
    class Unavailable(
        val region: QueueRegion,
        val reason: Reason,
        val waitedMs: Long,
    ) : RuntimeException("queue_fence_unavailable:${reason.wire}:${region.name.lowercase()}")

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    enum class Reason(val wire: String) {
        /** Another PROCESS holds the install's lock. Ordinary and expected. */
        CROSS_PROCESS_BUSY("cross_process_busy"),

        /**
 * This process already holds an OS lock on the file OUTSIDE the fence
 * the JVM raises `OverlappingFileLockException`. One OS `FileLock`
 * per process per file is the rule; reaching here means some path took
 * one without going through this class.
 */
        LOCK_INVARIANT("lock_invariant"),

        /**
 * The calling thread does NOT own the [QueueRegionLock] for this domain.
 *
 * THE ORDER IS `REGION LOCK -> FILE FENCE`, AND THIS IS WHERE IT IS
 * ENFORCED. A thread that reached the fence without the region lock has
 * skipped the layer that makes same-process contention WAIT — so two
 * threads of one process could meet here, where the answer to
 * contention is a bounded refusal, i.e. a dropped record. That is a
 * defect in our own call path, never a condition that clears on its
 * own, so it is refused rather than served, and it is a DIFFERENT
 * reason from [LOCK_INVARIANT]: that one says the OS lock was taken
 * outside the fence, this one says the fence was taken outside the
 * region.
 */
        REGION_NOT_HELD("region_not_held"),

        /** The waiting thread was interrupted. The interrupt flag is restored. */
        INTERRUPTED("interrupted"),
    }

    // ── reentrancy ──────────────────────────────────────────────────────────
    //
    // ONE OS LOCK PER PROCESS PER FILE, OR THE JVM THROWS. A second
    // `tryLock` on a file this process already locks raises
    // OverlappingFileLockException — so a nested region must not try to lock
    // again, it must observe that this thread already holds the fence. The
    // depth counter is per THREAD because that is the scope a nested sequence
    // has; the FileLock itself is per PROCESS.
    private val depth = ThreadLocal.withInitial { 0 }

    @Volatile private var channel: FileChannel? = null
    @Volatile private var lock: FileLock? = null

    /** Cross-thread guard over the acquire/release of the single OS lock. */
    private val osLockMonitor = Any()

    /**
 * Run [block] with the fence held for [region].
 *
 * Reentrant: a nested call on the same thread runs inside the SAME
 * acquisition and does not touch the OS lock.
 *
 * @throws Unavailable when the fence cannot be acquired within the region's
 * budget. **There is no proceed-unlocked path.**
 */
    fun <T> withFence(region: QueueRegion, block: () -> T): T {
        // FIRST, BEFORE THE REENTRANT FAST PATH AND BEFORE THE OS LOCK. The
        // fence is only ever acquired by a thread that owns the region lock
        // for this domain. Typed at production callers (a shipped app must not
        // crash on a lock condition) and fatal under [strictInvariants].
        if (!regionLock.isHeldByCurrentThread()) {
            RecoveryTrace.log(
                "queue_fence_region_not_held",
                "region" to region.name, "store" to lockFile.name,
            )
            check(!strictInvariants) {
                "queue fence invariant: the file fence for ${lockFile.name} was " +
                    "requested by a thread that does not own the region lock " +
                    "(region ${region.name})"
            }
            throw Unavailable(region, Reason.REGION_NOT_HELD, 0L)
        }
        if (depth.get() > 0) {
            depth.set(depth.get() + 1)
            try {
                return block()
            } finally {
                depth.set(depth.get() - 1)
            }
        }
        acquire(region)
        depth.set(1)
        try {
            return block()
        } finally {
            depth.set(0)
            release()
        }
    }

    /** True when the calling thread is inside a region. For assertions/tests. */
    fun isHeldByCurrentThread(): Boolean = depth.get() > 0

    /** The canonical lock file. Never unlinked. */
    fun lockFilePath(): String = lockFile.path

    private fun acquire(region: QueueRegion) {
        val budget = budgetFor(region)
        val started = System.nanoTime()
        var attempt = 0
        while (true) {
            val invariant = synchronized(osLockMonitor) {
                if (lock != null) {
                    // Another THREAD of this process holds the OS lock. The
                    // region-held check in `withFence` makes this unreachable —
                    // only the thread that owns the region lock gets here, and
                    // it re-enters through the depth counter instead. Kept as a
                    // second line: if that check were ever removed, wait exactly
                    // as for another process rather than corrupting.
                    false
                } else {
                    when (tryLockOnce()) {
                        LockAttempt.ACQUIRED -> return
                        LockAttempt.BUSY -> false
                        LockAttempt.INVARIANT -> true
                    }
                }
            }
            if (invariant) {
                val waited = elapsedMs(started)
                RecoveryTrace.log(
                    "queue_fence_lock_invariant",
                    "region" to region.name, "store" to lockFile.name,
                )
                // Loud where loud is safe; typed where a crash would be worse
                // than the defect.
                check(!strictInvariants) {
                    "queue fence invariant: this process already holds an OS lock on " +
                        "${lockFile.name} outside the fence (region ${region.name})"
                }
                throw Unavailable(region, Reason.LOCK_INVARIANT, waited)
            }
            attempt++
            if (attempt >= budget.attempts) {
                val waited = elapsedMs(started)
                RecoveryTrace.log(
                    "queue_fence_unavailable",
                    "region" to region.name, "attempts" to attempt, "waitedMs" to waited,
                )
                throw Unavailable(region, Reason.CROSS_PROCESS_BUSY, waited)
            }
            try {
                Thread.sleep(budget.sleepMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Unavailable(region, Reason.INTERRUPTED, elapsedMs(started))
            }
        }
    }

    private enum class LockAttempt { ACQUIRED, BUSY, INVARIANT }

    private fun tryLockOnce(): LockAttempt {
        return try {
            lockFile.parentFile?.mkdirs()
            val ch = RandomAccessFile(lockFile, "rw").channel
            val l = try {
                ch.tryLock()
            } catch (o: OverlappingFileLockException) {
                try { ch.close() } catch (_: Throwable) { }
                return LockAttempt.INVARIANT
            } catch (_: Throwable) {
                try { ch.close() } catch (_: Throwable) { }
                return LockAttempt.BUSY
            }
            if (l == null) {
                try { ch.close() } catch (_: Throwable) { }
                LockAttempt.BUSY
            } else {
                channel = ch
                lock = l
                LockAttempt.ACQUIRED
            }
        } catch (_: Throwable) {
            LockAttempt.BUSY
        }
    }

    private fun release() {
        synchronized(osLockMonitor) {
            // RELEASE BY DESCRIPTOR TEARDOWN. Closing the channel releases
            // the lock; doing it in this order means an exception releasing the
            // lock cannot leave the descriptor open holding it.
            try { lock?.release() } catch (_: Throwable) { }
            try { channel?.close() } catch (_: Throwable) { }
            lock = null
            channel = null
        }
    }

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000

    private class Budget(val attempts: Int, val sleepMs: Long)

    /**
 * ACQUISITION POLICIES, NOT THE ACCEPTANCE-GATED OPERATIONAL CONSTANTS.
 * The bracket TIME BUDGET, the victim ceiling and the protection capacity
 * are separate, are machine-visible as UNSET in [F4Constants], and make the
 * affected DESTRUCTIVE OPERATION refuse. These numbers only decide how long
 * a cross-process lock attempt waits before answering a typed refusal; M5
 * tunes them.
 *
 * THE CRASH ARM HAS NO NUMBER OF ITS OWN. It reads
 * [F4Constants.CrashAcquisition] — the one provisional crash policy both
 * lock layers derive from — so the fence cannot be tuned apart from the
 * region lock. The SERIALIZE arm is the ORDINARY cross-process policy, a
 * different policy for a different caller, and stays here.
 */
    private fun budgetFor(region: QueueRegion): Budget = when (region.acquisition) {
        // The dying thread of an uncaught exception. It must NEVER wait through
        // another process's bracket.
        QueueRegion.Acquisition.BOUNDED -> Budget(
            attempts = F4Constants.CrashAcquisition.FENCE_ATTEMPTS,
            sleepMs = F4Constants.CrashAcquisition.FENCE_ATTEMPT_DELAY_MS,
        )
        QueueRegion.Acquisition.SERIALIZE -> Budget(attempts = 150, sleepMs = 20)
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /**
 * When true, a [Reason.LOCK_INVARIANT] is FATAL rather than typed.
 * Tests set it; production never does. Default false so a shipped app
 * can never crash on somebody else's legitimate ownership.
 */
        @JvmStatic
        @Volatile
        var strictInvariants: Boolean = false

        /**
 * ITS OWN REGISTRY, INDEPENDENT OF [QueueHome]'s AND
 * [QueueRegionLock]'s — see the note on the region lock's registry.
 * Two fence objects over one path would each try to take the OS lock,
 * and the second would raise `OverlappingFileLockException` against its
 * own process.
 */
        private val instances = ConcurrentHashMap<String, QueueFileFence>()

        @JvmStatic
        fun forStoreFile(storeFile: File): QueueFileFence {
            val path = QueueDomain.pathFor(storeFile)
            return instances.computeIfAbsent(path) {
                // The region lock comes from ITS OWN registry, by the same
                // domain path — so the fence checks the very object every
                // store on this domain serialises on, and neither registry can
                // hand out a second identity for a live path.
                QueueFileFence(File(it), QueueRegionLock.forDomainPath(it))
            }
        }
    }
}

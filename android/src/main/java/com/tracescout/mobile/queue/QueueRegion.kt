package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import java.io.File

/**
 * The ordered regions of a queue domain, and the ONE derivation of what a
 * "queue domain" is.
 *
 * ## The order
 *
 * ```
 * QUEUE REGION LOCK -> QUEUE FILE FENCE -> ENCRYPTED EVENT STORE MONITOR
 * -> AUTHORITY MONITOR -> AUTHORITY ROOT FILE LOCK
 * ```
 *
 * Two layers sit where an earlier design had one, because the two questions are
 * different and their answers to contention must be different:
 *
 * * [QueueRegionLock] serialises the threads of THIS process. Ordinary
 * contention there WAITS, exactly as the `@Synchronized` model it replaces
 * did — a second thread appending is not a reason to drop a record.
 * * [QueueFileFence] excludes OTHER PROCESSES. There is no thread to wait on
 * and no way to ask how long the other process intends to hold it, so
 * acquisition is bounded and an unavailable fence is a typed refusal.
 *
 * Collapsing them into one class is what made every region "150 x 20 ms then
 * Unavailable", i.e. it converted ordinary intra-process contention into
 * dropped records.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
enum class QueueRegion {
    APPEND,
    REMOVE_FIRST,
    CLEAR,
    COMPACT,

    /** A whole PLAN -> PREPARE -> COMMIT -> SETTLE sequence, one acquisition. */
    BRACKET,

    /** park write -> verify -> re-check -> removeFirst, one acquisition. */
    PARK,
    QUARANTINE,
    RECONCILE_DELIVERED,
    RECONCILE_PARKED,
    DEQUEUE,

    /** scan -> verdicts -> settlement. */
    RECOVERY,

    /**
 * F4-P2 (F4-16 / C14 reconciliation half, a product decision): the ONE
 * enumerated reconciliation region — lossy-image detection -> the durable
 * reconciliation FACT (written and read back) -> exact settlements ->
 * re-verification of every gate -> the governed survivors-only consume ->
 * fsync + read-back -> the fact recorded consumed, one acquisition. The
 * store's `consumeReconciled` is ordered on this same region and is
 * reachable from exactly one production site (the reconciler).
 */
    RECONCILE,

    /** scan -> finalize -> destroy -> clear. */
    TEARDOWN,

    /** The dying thread of an uncaught exception. The ONE bounded region. */
    CRASH_APPEND,

    /**
 * The pipeline's pre-request sequence: live image read -> image
 * precondition -> reconciles -> stale-descriptor discard -> no-progress
 * gate -> freeze the descriptor and ADMIT PROTECTION, one acquisition.
 * The network request itself is NEVER inside it.
 */
    SUBMIT,

    /**
 * The frame-accounting bracket: the whole gate read -> soft-cap read ->
 * durable accounting -> typed-removal sequence, one acquisition.
 *
 * **IT IS A SEPARATE REGION FROM [BRACKET], DELIBERATELY.** [BRACKET] is
 * the record pipeline's PLAN -> PREPARE -> COMMIT -> SETTLE; this is the
 * frame store's. Reusing one name for two sequences would make a trace line
 * unable to say which pipeline was holding the install, and would let a
 * future reader fold two different hold durations into one measurement.
 *
 * **AND THE SOFT-CAP READ MUST BE INSIDE IT**, not before it. A read
 * outside the scope is a check-then-act: the capacity answer can be stale
 * by the time the write happens, which is the `[0b]` defect the accepted
 * contract names.
 */
    FRAME_BRACKET,
    ;

    /**
 * How this region behaves under SAME-PROCESS contention.
 *
 * EXHAUSTIVE AND CLOSED, so a region added later must choose. A default
 * of [Acquisition.BOUNDED] would silently give a new ordinary region the
 * crash path's drop semantics; a default of [Acquisition.SERIALIZE] would
 * silently let a new crash-context region block a dying thread.
 */
    val acquisition: Acquisition
        get() = when (this) {
            CRASH_APPEND -> Acquisition.BOUNDED
            APPEND, REMOVE_FIRST, CLEAR, COMPACT, BRACKET, PARK, QUARANTINE,
            RECONCILE_DELIVERED, RECONCILE_PARKED, DEQUEUE, RECOVERY, TEARDOWN,
            SUBMIT, RECONCILE, FRAME_BRACKET,
            -> Acquisition.SERIALIZE
        }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    enum class Acquisition {
        /**
 * Wait for the owner. This is the behaviour class of the
 * `@Synchronized` model this replaces: contenders queue, and ordinary
 * intra-process contention is NOT an accounted-loss condition. The wait
 * is measured ([QueueRegionLock.waitStats]) so M5 can bound it; it is
 * not capped, because a cap here is a dropped record.
 */
        SERIALIZE,

        /**
 * Try for a short, ruled budget and then REFUSE, typed. Reserved for
 * the crash path, which is racing the process's own death: a refused
 * crash append is an accounted loss, a crash append that waits can lose
 * the record AND the process.
 */
        BOUNDED,
    }
}

/**
 * The canonical identity of one queue domain.
 *
 * **BOTH LOCK LAYERS MUST KEY ON THE SAME DOMAIN, AND THIS IS THE ONE
 * DERIVATION OF IT.** If the region lock were keyed per queue FILE while the
 * fence is keyed per install directory, two threads of one process working on
 * two files in one directory would each pass their own region lock and then
 * meet at the FILE FENCE — where the semantics are bounded refusal. Ordinary
 * same-process contention would be answered by dropping a record, which is
 * precisely the behaviour the two-layer split exists to prevent. Keying both on
 * the same path makes that unreachable rather than merely unintended, and
 * `QueueOrderTest` asserts the two paths are equal for a real store.
 *
 * The domain is the install directory: `filesDir` holds the queue, both park
 * stores, both park manifests and the RN frame store, and a park write plus the
 * queue removal that follows it are two halves of ONE move. A per-file domain
 * would let them race, and would create a lock-order question between two locks
 * that this design does not have. The cost — a park write briefly excluding an
 * unrelated queue append — is real and is measured by M5 rather than assumed
 * away.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object QueueDomain {

    /**
 * The lock file name. Never unlinked, and deliberately absent from
 * `QueueKeyStores.ALL` — deleting it is how two processes stop sharing a
 * lock.
 */
    const val LOCK_FILE: String = "tracescout-queue.lock"

    /**
 * The canonical domain path for a store file.
 *
 * `canonicalFile`, NOT `absoluteFile`. `getCanonicalFile` resolves
 * symlinks and `.` segments; `getAbsoluteFile` does not. Two spellings of
 * one path must reach the SAME domain, and only the canonical form
 * guarantees it.
 */
    @JvmStatic
    fun pathFor(storeFile: File): String = File(parentOf(storeFile), LOCK_FILE).path

    private fun parentOf(storeFile: File): File =
        try {
            storeFile.canonicalFile.parentFile ?: storeFile.absoluteFile.parentFile!!
        } catch (_: Throwable) {
            // A canonicalisation failure must not silently produce a DIFFERENT
            // identity; fall back to the absolute parent, which is the same
            // directory for every path this SDK builds.
            storeFile.absoluteFile.parentFile!!
        }
}

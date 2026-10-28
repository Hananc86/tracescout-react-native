package com.tracescout.mobile.queue

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durable recovery is the SDK's job, not the host app's.
 *
 * WHAT THIS REPLACED. `recoverPreviousProcess` was documented as "called
 * EXPLICITLY BY THE HOST rather than automatically at start, because recovery
 * uploads bytes captured under a session that has already ended". Defensible in
 * the abstract — and **nothing called it**. Not `start`, not the fixture. So
 * records survived process death perfectly and were never re-sent: durable
 * storage with no path out of it. A capability nobody invokes is not a feature,
 * and "the host should decide" is not a decision when no host was ever given
 * the chance.
 *
 * Recovery now runs from the real startup path. The metered-connection concern
 * it was written for is answered properly — by a connectivity-constrained
 * worker — rather than by declining to run at all.
 */
object RecoveryCoordinator {

    /** What startup found, and what happens next. Typed, never a bare boolean. */
    enum class Status {
        RECOVERY_NOT_NEEDED,
        RECOVERY_IN_PROGRESS,
        RECOVERY_COMPLETED,
        RECOVERY_PENDING_NETWORK,
        RECOVERY_RETRIABLE_FAILURE,
        RECOVERY_PERMANENT_REJECTION,

        /**
 * The backend refused the CREDENTIAL (401/403), not the records. The
 * queue is preserved for a corrected key; nothing retries until the
 * host replaces the configuration — a revoked key cannot be fixed by
 * asking again.
 */
        RECOVERY_AUTH_REJECTED,
    }

    private const val LEASE = "tracescout-recovery.lease"
    private const val GENERATION = "tracescout-process-generation"

    /**
 * A lease is STALE after this long. It exists because a process that dies
 * mid-upload cannot release its own lease, and a lease nobody can reclaim
 * would silently disable recovery forever — the same shape as the bug this
 * class exists to fix.
 */
    const val LEASE_STALE_MS = 10 * 60_000L

    /** In-process guard. The file guards ACROSS processes; this guards within. */
    private val held = AtomicBoolean(false)

    /**
 * This PROCESS's lease-owner identity, minted lazily and never persisted
 * beyond the lease file itself. Random per process — carries no device,
 * install or user identity.
 */
    @Volatile private var ownerId: String? = null
    private fun ownerId(): String {
        ownerId?.let { return it }
        synchronized(this) {
            ownerId?.let { return it }
            val v = UUID.randomUUID().toString().replace("-", "").take(16)
            ownerId = v
            return v
        }
    }

    @Volatile
    @JvmStatic
    var status: Status = Status.RECOVERY_NOT_NEEDED
        internal set

    /**
 * This process's generation id, minted once and persisted.
 *
 * Records inherited from an earlier generation are the ones recovery owns.
 * The id is random per process — never a device or install identifier, and
 * never anything that could correlate two users.
 */
    @JvmStatic
    fun processGeneration(ctx: Context): String {
        val f = File(ctx.filesDir, GENERATION)
        return try {
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            f.writeText(id)
            id
        } catch (_: Throwable) {
            "unknown"
        }
    }

    /** The generation recorded by the PREVIOUS process, if any. */
    @JvmStatic
    fun previousGeneration(ctx: Context): String? = try {
        val f = File(ctx.filesDir, GENERATION)
        if (f.exists()) f.readText().trim().ifEmpty { null } else null
    } catch (_: Throwable) { null }

    /**
 * Take the single-uploader lease.
 *
 * Foreground startup and the WorkManager drain must never own the same
 * queue prefix at once — two uploaders removing prefixes concurrently is
 * how records get deleted that a different batch was still relying on.
 */
    @JvmStatic
    fun acquire(ctx: Context): Boolean {
        if (!held.compareAndSet(false, true)) return false
        val f = File(ctx.filesDir, LEASE)
        try {
            if (f.exists()) {
                val leaseOwner = try {
                    f.readText().trim().split("|").getOrNull(1)?.ifEmpty { null }
                } catch (_: Throwable) { null }
                val age = System.currentTimeMillis() - f.lastModified()
                val fresh = age in 0 until LEASE_STALE_MS
                // The lease names its OWNER PROCESS. Only the main process
                // ever takes it (foreground start and the WorkManager drain
                // share that process and are serialised by `held`), so a lease
                // written by a DIFFERENT process belongs to a process that no
                // longer exists — and a dead owner cannot release. Waiting out
                // the staleness window instead strands the records for ten
                // minutes on every launch inside it; found live when the
                // system restarted a force-stopped fixture, its recovery was
                // killed mid-flight holding the lease, and the next cold
                // launch was refused. Reclaim a dead owner's lease
                // immediately. A lease with NO readable owner (legacy or torn
                // write) keeps the staleness rule — unreadable is not
                // evidence of death.
                val deadOwner = leaseOwner != null && leaseOwner != ownerId()
                if (fresh && !deadOwner) { held.set(false); return false }
            }
            f.writeText(System.currentTimeMillis().toString() + "|" + ownerId())
            return true
        } catch (_: Throwable) {
            held.set(false)
            return false
        }
    }

    @JvmStatic
    fun release(ctx: Context) {
        try { File(ctx.filesDir, LEASE).delete() } catch (_: Throwable) {}
        held.set(false)
    }

    /** For tests and diagnostics: is a lease currently held by anyone? */
    @JvmStatic
    fun leaseHeld(ctx: Context): Boolean {
        val f = File(ctx.filesDir, LEASE)
        if (!f.exists()) return false
        val age = System.currentTimeMillis() - f.lastModified()
        return age in 0 until LEASE_STALE_MS
    }
}

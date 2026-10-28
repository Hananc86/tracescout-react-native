package com.tracescout.mobile.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The connectivity-constrained drain.
 *
 * Recovery at startup answers the common case; this answers the one the old
 * "let the host decide" comment was actually worried about — a device with no
 * usable network. Instead of declining to recover, the work is deferred until
 * the system says a network exists, and it survives process death and reboot.
 *
 * UNIQUE work, deliberately: every `start` enqueues the same name with
 * `KEEP`, so ten launches produce one drain rather than ten racing uploaders.
 * The [RecoveryCoordinator] lease then guarantees the worker and a foreground
 * startup can never own the same queue prefix at once.
 */
class RecoveryWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val drain = drainHook ?: return Result.success()
        return try {
            when (drain(applicationContext)) {
                Outcome.DONE -> Result.success()
                // RETRY uses WorkManager's own exponential backoff. Returning
                // success here would silently abandon the queue; returning
                // failure would abandon it permanently.
                Outcome.RETRY -> Result.retry()
                Outcome.PERMANENT -> Result.failure()
            }
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    enum class Outcome { DONE, RETRY, PERMANENT }

    companion object {
        const val UNIQUE_NAME = "tracescout-mobile-recovery"

        /**
 * Set by the SDK at start. A hook rather than a direct call so this
 * class stays free of the SDK's singleton state and can be exercised
 * in isolation — and so a Worker instantiated by the system before the
 * SDK has initialized simply succeeds instead of crashing the host.
 */
        @JvmStatic
        @Volatile
        var drainHook: ((Context) -> Outcome)? = null

        /** Enqueue the ONE drain job. Idempotent by construction. */
        @JvmStatic
        fun enqueue(ctx: Context): Boolean = try {
            val req = OneTimeWorkRequestBuilder<RecoveryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                // Bounded exponential backoff. WorkManager adds its own jitter
                // to the computed delay, so a fleet coming back online together
                // does not arrive as one synchronized burst.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
            true
        } catch (_: Throwable) {
            // WorkManager may be absent or uninitialized in a host that
            // disabled its initializer. Recovery at startup still happened;
            // only the deferred retry is unavailable, and saying so beats
            // crashing the customer's app.
            false
        }

        /**
 * The SAME unique job, armed to run at most `delayMs` from now.
 *
 * Used for the first-pending deadline. Because the work name is
 * identical and the policy is KEEP, this COALESCES with every other
 * trigger rather than adding a second job: whichever arming happened
 * first wins, the rest are absorbed, and there is exactly one uploader.
 * It is a one-shot delay, never a periodic tick — nothing in this class
 * polls.
 */
        @JvmStatic
        fun enqueueDelayed(ctx: Context, delayMs: Long): Boolean = try {
            val req = OneTimeWorkRequestBuilder<RecoveryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
            true
        } catch (_: Throwable) {
            false
        }

        /**
 * Arm a delayed drain that is guaranteed to EXIST even when the caller
 * is itself running inside the unique work.
 *
 * `KEEP` SILENTLY DISCARDS AN ARM ISSUED FROM INSIDE THE WORKER.
 * A running worker is uncompleted work, so `enqueueUniqueWork(…, KEEP,
 * …)` from the drain-completion path is a no-op — and it still returns
 * normally, so the trace read `rn_deadline_armed scheduled=true` for a
 * job that was never created. Measured on device: a drain settled with
 * `remaining=1`, armed, and the record then sat for the whole run with
 * no upload. **`scheduled=true` is WorkManager accepting the call, not
 * a promise that a job exists.**
 *
 * `APPEND_OR_REPLACE` chains the request to run AFTER the current work
 * completes, with the delay measured from then — the same worker, the
 * same uniqueness, the same constraints. It is used ONLY on the path
 * that provably collides; the enqueue path keeps `KEEP`, which is
 * correct there because a second timer is exactly what must not happen.
 */
        @JvmStatic
        fun enqueueAfterCurrent(ctx: Context, delayMs: Long): Boolean = try {
            val req = OneTimeWorkRequestBuilder<RecoveryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
            true
        } catch (_: Throwable) {
            false
        }

        /** Nothing left to drain — do not leave work scheduled for an empty queue. */
        @JvmStatic
        fun cancel(ctx: Context) {
            try { WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME) } catch (_: Throwable) {}
        }
    }
}

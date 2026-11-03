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
import java.io.File
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

    // doWork is a MAPPING and nothing else, so the whole decision — hook or no
    // hook — is reachable from a test without constructing WorkerParameters.
    override fun doWork(): Result =
        when (outcomeFor(applicationContext)) {
            Outcome.DONE -> Result.success()
            // RETRY uses WorkManager's own exponential backoff. Returning
            // success here would silently abandon the queue; returning
            // failure would abandon it permanently.
            Outcome.RETRY -> Result.retry()
            Outcome.PERMANENT -> Result.failure()
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

        /**
 * Construct [drainHook] in a process that has none — the ONE thing the
 * no-hook branch below could not do.
 *
 * **THIS EXISTS BECAUSE RETAINING IS NOT DELIVERING.** The branch
 * below retains the obligation when the hook is absent, which is
 * strictly better than deleting it, and still never delivers: for a
 * host that starts the SDK only from an Activity, every attempt in a
 * woken process finds no hook, retains, and backs off until a launch
 * that may never come.
 *
 * It is a SECOND hook rather than a direct call for the same reason the
 * first one is: this class stays free of the SDK's singleton state and
 * is exercisable in isolation. Installing it is what a host's
 * `Application.onCreate` can do with no credential in hand and no
 * session — and `Application.onCreate` runs on EVERY process start,
 * including the one WorkManager wakes, which is why registration here
 * reaches the process that needs it while a `start` call from an
 * Activity cannot.
 *
 * Returns true when it installed a usable [drainHook]. False is a
 * REFUSAL, not an error: a host whose credential is not available in
 * this process says so by returning false, and the no-hook branch then
 * retains exactly as before.
 */
        @JvmStatic
        @Volatile
        var bootstrapHook: ((Context) -> Boolean)? = null

        /**
 * The RECORD queues this drain is responsible for delivering, by the
 * canonical inventory's own names so a rename follows automatically.
 *
 * Parked records and the frame stores are deliberately NOT here. A
 * parked record has already reached its third durable fate and is
 * accounted; a frame is a different lane with a different (best-effort)
 * posture. Retaining the job for either would retain it for work this
 * drain never delivers.
 *
 * **EVERY PRODUCER THAT OWNS A RECORD QUEUE MUST BE LISTED HERE,
 * AND OMITTING ONE IS SILENT RECORD LOSS — NOT A MISSING FEATURE.** This
 * list is the whole of what [undeliveredRecordBytes] can see, and that
 * predicate is the ONLY thing standing between a hook-less process and
 * `DONE`. A producer absent from it therefore reports ZERO pending
 * bytes however much it has captured, so the no-hook branch below
 * answers `DONE`, WorkManager marks the unique work SUCCEEDED and
 * DELETES it, and the records sit on disk with nothing scheduled to
 * deliver them and no loss accounted — the one outcome the loss
 * contract's disjunction forbids. The Flutter queue was missing for
 * exactly this reason: its three queue-key files
 * lived as private constants inside `FlutterTransport` and never
 * reached the shared inventory.
 */
        private val RECORD_QUEUES = listOf(
            QueueKeyStores.SDK_QUEUE,
            QueueKeyStores.RN_QUEUE,
            QueueKeyStores.FLUTTER_QUEUE,
        )

        /**
 * Bytes still sitting in the record queues, read WITHOUT the queue key.
 *
 * A removal rewrites the file with only the survivors, so an emptied
 * queue is a zero-length file and a cleared one is absent — length is
 * therefore an exact "is there anything here" test. It needs no
 * Keystore key, takes no queue lock and decrypts nothing, which is the
 * point: this runs in a process where the SDK was never initialized and
 * must not be able to disturb a queue it cannot drain.
 *
 * Unreadable is reported as PRESENT. The question being asked is "may I
 * discard the obligation to deliver", and the only safe answer to "I
 * cannot tell" is no.
 */
        internal fun undeliveredRecordBytes(filesDir: File): Long =
            RECORD_QUEUES.sumOf { name ->
                try {
                    File(filesDir, name).let { if (it.isFile) it.length() else 0L }
                } catch (_: Throwable) { 1L }
            }

        /**
 * What this run owes WorkManager.
 *
 * **WITH NO HOOK AND RECORDS ON DISK THIS MUST RETRY, AND RETURNING
 * `DONE` IS THE DEFECT THIS BRANCH EXISTS TO NAME.** The hook is
 * in-memory process state: it is installed by `TraceScoutMobile.start`
 * and by `RnTransport`, and so it is ABSENT in exactly the process this
 * worker was built for — one WorkManager woke to run the deferred drain
 * after connectivity returned, where the host has not started the SDK
 * (it starts from an Activity), or `start` was refused by a typed
 * refusal, or RN tore the hook down. `Result.success` there marks the
 * unique work SUCCEEDED, so WorkManager deletes it: the records survive
 * on disk, nothing is scheduled to deliver them, and no loss is
 * accounted — neither delivered nor accounted, which is precisely what
 * the loss contract forbids. Recovery then waits for the user to open
 * the app again, which may be never.
 *
 * Retrying is the honest answer because it RETAINS the obligation
 * rather than discarding or inventing one: the records are intact, so
 * accounting a loss here would record a loss that did not happen, and
 * this process cannot deliver them itself — the endpoint and ingest key
 * live in the `Config` handed to `start` and are not durable. The
 * cost is bounded by WorkManager's own exponential backoff (capped at
 * five hours between attempts); the work each attempt does while the
 * hook is absent is one file length.
 *
 * **WHAT THIS DOES NOT DO, STATED BECAUSE A REVIEWER ASKED FOR IT
 * AND WAS RIGHT.** Retrying an unchanged state never DELIVERS. For a
 * host that starts the SDK only from an Activity, connectivity can
 * return while the app is never opened: every attempt then finds no
 * hook, retains, and backs off — the records stay undelivered until a
 * launch that may not come. This branch is strictly better than
 * `DONE`, which DELETED the obligation (WorkManager marks a successful
 * unique job finished, so nothing remains scheduled at all) and left no
 * trace; it is not a complete answer to "a record that is enqueued must
 * reach a drain".
 *
 * Closing that needs something this class cannot decide: a DURABLE,
 * host-supplied transport descriptor — endpoint plus a credential
 * reference — so a woken process can construct the drain without an
 * Activity. That is a credential-durability design decision with its
 * own privacy surface, not a branch in a worker, and it is recorded as
 * a finding rather than guessed at here. Until it exists the trace line
 * below (`worker_no_hook` with the pending byte count) is what makes
 * the residual visible instead of silent.
 *
 * An EMPTY queue still succeeds: there is nothing to deliver, so there
 * is no obligation to retain.
 */
        internal fun outcomeFor(ctx: Context): Outcome {
            val drain = drainHook ?: bootstrapThenHook(ctx) ?: run {
                val bytes = try { undeliveredRecordBytes(ctx.filesDir) } catch (_: Throwable) { 1L }
                RecoveryTrace.log("worker_no_hook", "pending_bytes" to bytes)
                return if (bytes > 0L) Outcome.RETRY else Outcome.DONE
            }
            return try { drain(ctx) } catch (_: Throwable) { Outcome.RETRY }
        }

        /**
 * One bounded attempt to obtain a [drainHook] from [bootstrapHook].
 *
 * **NOTHING PENDING MEANS THE HOST IS NEVER ASKED.** The resolver
 * behind the bootstrap reaches a credential, so it must be consulted
 * only when there is actually something to deliver — a woken process
 * with an empty queue returns here having touched no credential at all,
 * and falls through to the `DONE` the empty branch already gives. That
 * ordering is the whole reason the pending read happens twice on this
 * path: the cost is one file length, and the alternative is asking the
 * host for a credential in order to discover there was no work.
 *
 * **A FALSE OR THROWING BOOTSTRAP CHANGES NOTHING.** It returns null
 * and the caller's existing branch retains exactly as it did before, so
 * a host that does not register a resolver — or one whose resolver
 * refuses — is byte-for-byte the behaviour an earlier change shipped. This can
 * only ever ADD a delivery; it can never convert a retained obligation
 * into a discarded one.
 *
 * The hook is re-read rather than returned by the bootstrap so there is
 * one source of truth for "what drains this queue": a bootstrap that
 * reports success without installing a hook still ends up retaining.
 */
        private fun bootstrapThenHook(ctx: Context): ((Context) -> Outcome)? {
            val boot = bootstrapHook ?: return null
            val pending = try { undeliveredRecordBytes(ctx.filesDir) } catch (_: Throwable) { 1L }
            if (pending <= 0L) return null
            val ok = try { boot(ctx) } catch (_: Throwable) { false }
            RecoveryTrace.log("worker_bootstrap", "ok" to ok, "pending_bytes" to pending)
            return if (ok) drainHook else null
        }

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

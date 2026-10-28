package com.tracescout.mobile.capture

import com.tracescout.mobile.queue.EncryptedEventStore

/**
 * Native-core the specification CrashHandler.
 *
 * THREE RULES, EACH OF WHICH HAS A FAILURE MODE WORSE THAN LOSING THE CRASH:
 *
 * 1. **Chain to the host handler, never replace it.** An app's own crash
 * reporter, its Play Console vitals, its custom "sorry" screen — all of them
 * hang off `Thread.getDefaultUncaughtExceptionHandler`. Swallowing it means
 * the app silently loses crash reporting it already had, and nobody notices
 * until they need it.
 *
 * 2. **Persist synchronously, before chaining.** The process is dying. There is
 * no "later". Handing the record to a background thread or a network client
 * means it is gone.
 *
 * 3. **Do the minimum at crash time.** The record is pre-serialised bytes
 * appended to an already-open store. No new allocation-heavy work, no
 * network, no lock that another dying thread might hold.
 *
 * Delivery happens on the NEXT LAUNCH with `deliveredOnNextLaunch: true`, and
 * crash records are delivered REGARDLESS of the sampling decision — sampling
 * crashes away makes crash rate a function of sample rate.
 */
class CrashHandler private constructor(
    private val store: EncryptedEventStore,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val recordBuilder: (Thread, Throwable) -> ByteArray,
    /**
 * F2: told the crash record's append result — BOTH facts, so the sequence
 * it claimed can be settled in the same three states every other append is.
 *
 * THIS USED TO BE ONE BOOLEAN, `stored && durability == COMMITTED`
 * (blocker 2 of the ccdbec35 reviews, SF-5). Collapsing three durability
 * states into two turned `stored = true` + `INDETERMINATE_DIRECTORY_UNSYNCED`
 * bytes that may well be on disk — into `false`, and the caller settled
 * that sequence LOST(APPEND_REFUSED): a durable FALSE LOSS for a crash
 * record the next launch would then upload. `settleAppend`, fifteen lines
 * from that call site, already handled the three states correctly; the
 * crash path now hands it the same two facts.
 */
    private val afterAppend: (stored: Boolean, durability: EncryptedEventStore.Durability) -> Unit,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            // Rule 2 + 3: one pre-shaped append, fsync'd, then get out of the way.
            //
            // THE RESULT WAS DISCARDED (`store.append(.)` as a statement),
            // so a REFUSED crash append was indistinguishable from a stored
            // one — and the crash record exists precisely to be delivered on
            // the next launch, which a refusal silently prevents.
            //
            // WHY A DURABLE TRACE AND NOT A STATIC FLAG: the process is
            // dying, so an in-memory marker cannot survive to the next launch,
            // which is the only place this fact can ever be read. The cost is
            // strictly smaller than what Rule 3 already tolerates on this line
            // `append` performs an fsync; the trace is one short append to a
            // different, small file — and the likeliest refusal (`at_capacity`
            // on a full queue) does not impede it. It stays INSIDE this
            // try/catch, so Rule 1 is untouched: a failure here still cannot
            // stop the host's handler from running.
            // `appendFromCrashHandler`, NOT `append`. The dying thread must
            // never wait through another process's bracket: the crash region's
            // budget is a few tens of milliseconds, and an unavailable fence
            // comes back as a TYPED REFUSAL that the two lines below account
            // for. Calling plain `append` here sent the crash thread through
            // the ordinary region's seconds-long budget, and a thrown
            // `Unavailable` was swallowed by this method's own Rule-1 catch —
            // losing the crash record with no trace and no settlement.
            val r = store.appendFromCrashHandler(recordBuilder(t, e))
            if (!r.stored) {
                com.tracescout.mobile.queue.RecoveryTrace.log(
                    "crash_append_refused", "reason" to (r.reason ?: "unknown"))
            }
            try { afterAppend(r.stored, r.durability) } catch (_: Throwable) {}
        } catch (ignored: Throwable) {
            // Rule 1 outranks everything: a failure here must NEVER stop the
            // host's handler from running. An SDK that eats a crash report
            // because its own persistence broke is worse than an SDK that was
            // never installed.
        }
        // Rule 1: always chain.
        previous?.uncaughtException(t, e)
    }

    companion object {
        /**
 * Install, capturing whatever handler is already registered.
 *
 * Idempotent: installing twice would put this handler in its own chain
 * and write the crash record twice.
 */
        @JvmStatic
        @JvmOverloads
        fun install(
            store: EncryptedEventStore,
            // Declared BEFORE the builder so the existing trailing-lambda call
            // shape `install(store) { t, e -> bytes }` keeps binding the builder.
            afterAppend: (stored: Boolean, durability: EncryptedEventStore.Durability) -> Unit = { _, _ -> },
            recordBuilder: (Thread, Throwable) -> ByteArray,
        ): CrashHandler? {
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            if (existing is CrashHandler) return existing
            val handler = CrashHandler(store, existing, recordBuilder, afterAppend)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            return handler
        }

        /** Restore the previously-registered handler. Used by tests and teardown. */
        @JvmStatic
        fun uninstall() {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(current.previous)
            }
        }
    }
}

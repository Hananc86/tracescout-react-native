package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.queue.EncryptedEventStore

/**
 * The only two store operations the durable upload pipeline performs.
 *
 * LIBRARY-INTERNAL. It exists so the pipeline's guarantees can be PROVEN on
 * the JVM: `EncryptedEventStore` is Keystore-backed and cannot be constructed
 * in a unit test, so without this seam the ack matrix, the in-flight-append
 * survival rule and the no-whole-store-clear rule could only be asserted by
 * reading source text — and a source-grep cannot show that removeFirst was
 * called with the RIGHT count.
 *
 * Deliberately NARROW: `clear` is absent, so no upload, recovery or rejection
 * path can reach it even by mistake. That is the `store.clear` defect made
 * unrepresentable rather than merely forbidden by a comment.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface RecordStore {
    /**
 * How trustworthy was the last read of this container?
 *
 * **A REQUIREMENT WITH NO DEFAULT, AND THE ABSENCE OF THE DEFAULT IS
 * THE POINT.** This used to be `readIsComplete: Boolean = true` — a
 * FAIL-OPEN CONTRACT: a conformer that said nothing at all was recorded as
 * having claimed "my reads are complete", when no implementation had made
 * that claim. The current production adapter overrides it and the current
 * destructive gate is internal, so the hazard was unrealised — but a second
 * Kotlin adapter added later would have inherited `true` silently and
 * re-opened destructive mutation on an untrustworthy read.
 *
 * A default that says TRUSTED on behalf of code that never spoke is not a
 * convenience; it is the failure mode wearing a convenience's clothes.
 * There is now no way to conform without answering, and the compiler — not
 * a review convention and not a grep — is what enforces it.
 *
 * **`integrityClass` IS THE ONE AUTHORITY.** It is deliberately the SAME
 * vocabulary `EncryptedEventStore.ReadResult.integrityClass` already
 * publishes and the same five words the iOS `StoreIntegrity` uses. A second,
 * parallel integrity API would be a second answer to one question, which is
 * how `treatment` vs `privacy` survived for weeks on the hierarchy wire.
 */
    fun integrityClass(): String

    /**
 * DERIVED, never a requirement — no conformer can answer the two
 * differently, because there is only one thing to answer.
 *
 * Kotlin permits overriding an interface method that has a body, so this
 * is additionally pinned by `verify-store-failclosed.mjs`: no conformer
 * anywhere may override `readIsComplete`. On iOS the equivalent lives in a
 * protocol EXTENSION, where overriding it is not a requirement dispatch at
 * all; this guard is what buys Kotlin the same property.
 */
    fun readIsComplete(): Boolean = integrityClass() == INTEGRITY_OK

    fun readRecords(): List<ByteArray>
    fun removeFirst(count: Int): Int

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /** The one healthy value of [integrityClass]. Everything else refuses. */
        const val INTEGRITY_OK = "ok"

        /**
 * The class reported when the container could not be read AT ALL — as
 * distinct from being read and found empty. A store that cannot answer
 * is never trusted.
 */
        const val INTEGRITY_UNREADABLE = "unreadable_file"

        /**
 * Adapter over the production store — the ONLY production implementation.
 *
 * [mutationGate] (F2) is asked about **the exact records being removed**
 * and answers with the fenced sequences that forbid it. Reads are
 * unaffected. Defaults to "nothing fenced" so an unwired caller keeps
 * Phase-0 behaviour exactly.
 *
 * THE GATE TAKES THE SLICE, AND THAT SIGNATURE IS THE FIX.
 *
 * It used to take NOTHING — ` -> List<Long>` — so the caller had to
 * pick one authority to speak for every removal the process would ever
 * make. Both production call sites picked a wrong one: the native
 * producer asked the CURRENT session's authority, and the RN transport
 * asked the authority of whatever session happened to be at the head of
 * the queue when the drain started. Either way, one session's unresolved
 * state refused removals for records belonging to other sessions
 * entirely — a session with a clean, trustworthy read could not dequeue
 * an acknowledged batch because an unrelated session, possibly from a
 * previous process, had something pending. A gate that cannot see which
 * records it is being asked about is not a gate on those records.
 *
 * With the slice in hand the decision is per removal: derive the owning
 * session from the bytes themselves, ask THAT session's authority, and
 * apply only that answer.
 */
        @JvmStatic
        @JvmOverloads
        fun of(
            store: EncryptedEventStore,
            mutationGate: (List<ByteArray>) -> List<Long> = { emptyList() },
        ): RecordStore = object : RecordStore {
            override fun readRecords(): List<ByteArray> = store.readAll().records
            override fun removeFirst(count: Int): Int {
                if (count <= 0) return 0
                // THE GATE IS ASKED ABOUT THE RECORDS THAT WOULD ACTUALLY GO.
                // A read that fails authorises nothing: an unreadable container
                // is the untrustworthy case, where Phase 0 already refuses.
                val slice = try {
                    val read = store.readAll()
                    if (read.integrityClass != INTEGRITY_OK) null else read.records.take(count)
                } catch (_: Throwable) { null }
                if (slice == null) {
                    com.tracescout.mobile.queue.RecoveryTrace.log("store_remove_refused", "op" to "removeFirst",
                        "why" to "slice_unreadable", "want" to count)
                    return 0
                }
                val fenced = try { mutationGate(slice) } catch (_: Throwable) { listOf(-1L) }
                if (fenced.isNotEmpty()) {
                    com.tracescout.mobile.queue.RecoveryTrace.log("store_remove_refused", "op" to "removeFirst",
                        "why" to "mutation_fenced", "want" to count, "fenced" to fenced.size)
                    return 0
                }
                return store.removeFirst(count)
            }

            /**
 * THIS ADAPTER USED TO THROW THE EVIDENCE AWAY.
 *
 * `readAll` returns a typed `ReadResult` carrying `discarded`,
 * `discardReasons` (including `store_unreadable`) and
 * `truncatedTail` — and on a whole-store read problem it does NOT
 * necessarily throw: it returns whatever records it could recover.
 * This adapter exposed only `.records`, so a caller could not tell
 * "the store definitely contains no opener" from "the opener's
 * frame failed GCM authentication and was dropped".
 *
 * That distinction is load-bearing for the terminal classifier: an
 * opener lost to corruption would otherwise look exactly like an
 * opener that never existed, and the slice would be parked on
 * ignorance — the one thing the policy promises it can never do.
 */
            override fun integrityClass(): String = try {
                store.readAll().integrityClass
            } catch (_: Throwable) {
                // An unreadable container is not an empty one, and it is
                // certainly not a healthy one.
                INTEGRITY_UNREADABLE
            }
        }
    }
}

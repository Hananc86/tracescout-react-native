package com.tracescout.mobile.queue

import com.tracescout.mobile.internal.loss.AuthorityKeys
import java.io.File

/**
 * THE ONE HOME FOR DESTROYING THE SDK'S KEY MATERIAL (F3-P0 the specification H3).
 *
 * ## The defect this exists for, measured
 *
 * the specification splits the SDK's storage across TWO Keystore domains — the queue's
 * `tracescout_mobile_queue_v1` and the authority's `tracescout_authority_v2` —
 * and the specification H3 requires that **teardown destroys BOTH**. Before this file:
 *
 * ```
 * StoreKeys.destroyQueueKey call sites in the whole tree: 0
 * AuthorityKeys.destroyAuthorityKey call sites in the whole tree: 0
 * ```
 *
 * **NEITHER HAD A CALLER, so H3 had no subject at all.** The authority half
 * was written for the specification and never wired; the queue half has been unwired since it
 * was written. A property whose proof is "two functions exist" is the
 * dead-preparedness shape this programme keeps paying for.
 *
 * ## Half a destruction is the failure mode, so it is made UNREPRESENTABLE
 *
 * Destroying one domain and not the other does not leave "most of a reset": it
 * leaves an install whose queue is unreadable while its authority still answers,
 * or the reverse — a state no reopen path is designed for and no operator asked
 * for. So the two per-domain destroys are `internal` and **this object is their
 * only caller**; there is no supported way to destroy one alone.
 *
 * **BOTH ARE ALWAYS ATTEMPTED — the second is never conditioned on the
 * first.** A Keystore that refuses one alias must not be able to skip the
 * other; that would re-create the half-destroyed state through an error path
 * rather than through a call site.
 *
 * ## THE INJECTION SEAM IS NOT ON THE PUBLIC SURFACE, AND THAT WAS A REAL DEFECT
 *
 * The predecessor made [KeyDestroyers] public with a public constructor and gave
 * [destroyAllKeyMaterial] a public `@JvmOverloads` parameter. `KeyDestroyers({}, {})`
 * cannot throw, so **any caller could obtain `queueDestroyed = true`,
 * `authorityDestroyed = true`, `complete = true` while deleting nothing** — a
 * fabricated success surface on irreversible erasure behaviour, which is the one
 * place a false success is unrecoverable. Production and proof are now separated:
 *
 * ```
 * public destroyAllKeyMaterial(filesDir) <- platform destroyers, ALWAYS
 * internal destroyAllKeyMaterial(filesDir, d) <- the proof seam
 * internal class KeyDestroyers <- no public constructor
 * ```
 *
 * The seam still exists because `AndroidKeyStore` does not exist on the JVM: a
 * unit test calling the real functions would exercise their failure path and
 * could not see whether BOTH were invoked, which is the whole of H3.
 *
 * ## DESTROYING THE QUEUE KEY ALONE STALLS EVERY QUEUE-KEY STORE FOR EVER
 *
 * This is the X1 finding, and it is the reason teardown does more than destroy
 * keys. Once the queue key is gone, every frame in the queue, both park stores
 * and the RN frame store fails AEAD, so `readAll` reports `discarded > 0` and
 * [EncryptedEventStore] REFUSES every destructive rewrite — `removeFirst` and
 * `compactAndAppend` both stop on `untrustworthy_read`, deliberately, because
 * rewriting from records we failed to read would destroy them. Appends then
 * succeed only until `maxBytes`, after which the bracket's commit refuses too
 * and **append fails permanently**. A teardown that stopped at the key would
 * therefore guarantee forward-progress loss on every install that already had
 * stored data.
 *
 * So a SUCCESSFUL queue-key destruction is followed by removing those
 * now-unrecoverable bytes ([QueueKeyStores]). The ordering is the safety
 * argument and is not incidental:
 *
 * ```
 * 1 destroy the queue key
 * 2 destroy the authority key (independent — ALWAYS attempted)
 * 3 IF AND ONLY IF (1) succeeded, clear the queue-key-backed files
 * ```
 *
 * **STEP 3 IS GATED ON STEP 1 BECAUSE A FAILED DESTROY LEAVES THE BYTES
 * RECOVERABLE.** Clearing them then would be deleting customer data that the
 * existing key can still read — the opposite failure, and the worse one. A
 * control reverses the order and must fail.
 *
 * **THIS IS NOT THE AUTHORITY-HOME DELETION RULING A1 FORBIDS.** Only
 * queue-key-backed compatibility stores are cleared. `session-floors.bin`, the
 * per-session snapshots and the WALs are NOT touched: deleting them would
 * destroy the durable sequence floor that stops a reused session id from
 * allocating from zero. See [QueueKeyStores]'s own note.
 */
object StoreTeardown {

    /**
 * The two platform calls, injectable ONLY so the property can be proven.
 *
 * **`internal`, WITH AN `internal` CONSTRUCTOR.** This type is the seam a
 * no-op teardown would be built from, so it is unreachable from outside the
 * module and there is no public overload that accepts it. `platform` holds
 * references to the real functions and is what every production call uses.
 */
    internal class KeyDestroyers internal constructor(
        val destroyQueue: () -> Unit,
        val destroyAuthority: () -> Unit,
    ) {
        companion object {
            internal val platform: KeyDestroyers = KeyDestroyers(
                destroyQueue = { StoreKeys.destroyQueueKey() },
                destroyAuthority = { AuthorityKeys.destroyAuthorityKey() },
            )
        }
    }

    /**
 * What happened to the queue-key-backed compatibility stores.
 *
 * FOUR DISTINCT FACTS, NEVER ONE BOOLEAN. "Not attempted because the key
 * survived" and "attempted and two files could not be removed" have opposite
 * remedies, and collapsing them is how a caller comes to believe forward
 * progress was restored when nothing was tried.
 */
    sealed class Cleanup {
        /**
 * No cleanup ran, and [reason] says why. `queue_key_not_destroyed` is the
 * SAFE outcome — the bytes are still readable under the surviving key, so
 * removing them would destroy recoverable data.
 */
        data class NotAttempted(val reason: String) : Cleanup()

        /** Every queue-key-backed file is gone; the stores start clean. */
        data class Done(val cleared: List<String>) : Cleanup()

        /** Some files survive. Those stores WILL still stall — this is a failure. */
        data class Incomplete(val cleared: List<String>, val failed: List<String>) : Cleanup()
    }

    /**
 * What actually happened, per domain. Never a single boolean for several facts.
 *
 * [complete] REQUIRES THE CLEANUP, and that is a deliberate strengthening.
 * The predecessor's `complete` meant "both keys destroyed" — which was true of
 * an install left permanently unable to queue anything. A result that says
 * `complete` must mean the teardown did what its documentation promises.
 */
    data class Outcome(
        val queueDestroyed: Boolean,
        val authorityDestroyed: Boolean,
        val cleanup: Cleanup,
    ) {
        /** Both key domains destroyed AND the queue-key stores cleared. */
        val complete: Boolean get() = queueDestroyed && authorityDestroyed && cleanup is Cleanup.Done

        /**
 * The queue key is gone AND nothing queue-key-encrypted remains to stall on,
 * so a regenerated key starts against empty stores.
 *
 * Reported separately from [complete] because it is a different question: an
 * authority-key failure leaves this TRUE, and a caller deciding whether the
 * device can capture again needs that answer rather than the conjunction.
 */
        val forwardProgressRestored: Boolean get() = queueDestroyed && cleanup is Cleanup.Done
    }

    /**
 * Destroy the key material of **both** domains, then clear the queue-key-backed
 * compatibility stores if — and only if — the queue key was actually destroyed.
 *
 * [filesDir] is the app's files directory, where every queue-key-backed store
 * lives. A null one means the cleanup cannot be performed and is reported as
 * [Cleanup.NotAttempted]; the key destruction still happens, because a host app
 * that cannot give us a directory still gets its erasure.
 */
    @JvmStatic
    fun destroyAllKeyMaterial(filesDir: File?): Outcome =
        destroyAllKeyMaterial(filesDir, KeyDestroyers.platform)

    /**
 * The same path with the platform calls injectable — **`internal`, the proof
 * seam**. See the class note: a PUBLIC overload taking this parameter
 * is how a caller fabricates a complete teardown that deletes nothing.
 */
    internal fun destroyAllKeyMaterial(filesDir: File?, destroyers: KeyDestroyers): Outcome {
        // TWO INDEPENDENT ATTEMPTS, NEITHER GUARDING THE OTHER. Writing this as
        // `destroyQueue; destroyAuthority` without the per-domain containment
        // would let a throw from the first skip the second — the half-destroyed
        // state, arrived at through an error path.
        val q = runCatching { destroyers.destroyQueue() }.isSuccess
        val a = runCatching { destroyers.destroyAuthority() }.isSuccess

        // AFTER THE DESTROY, AND ONLY ON ITS SUCCESS. Before it the bytes are
        // readable under the surviving key; clearing them then destroys recoverable
        // customer data. `a` is deliberately NOT consulted: an authority-key failure
        // has nothing to say about whether the QUEUE key is gone.
        val cleanup = when {
            !q -> Cleanup.NotAttempted("queue_key_not_destroyed")
            filesDir == null -> Cleanup.NotAttempted("files_dir_unavailable")
            else -> runCatching { QueueKeyStores.clearAll(filesDir) }
                .getOrElse { Cleanup.Incomplete(emptyList(), QueueKeyStores.ALL) }
        }
        return Outcome(queueDestroyed = q, authorityDestroyed = a, cleanup = cleanup)
    }
}

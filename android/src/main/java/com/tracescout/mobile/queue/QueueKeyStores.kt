package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import java.io.File

/**
 * THE CANONICAL INVENTORY OF EVERY PRODUCTION FILE ENCRYPTED UNDER THE **QUEUE**
 * KEY (`tracescout_mobile_queue_v1`) — one home, read by the construction sites
 * themselves (F3-P0 the specification H3, the X1 forward-progress closure).
 *
 * ## The defect this exists for, and why a hard-coded list would have reproduced it
 *
 * Destroying the queue key makes every byte in these files permanently
 * unreadable. [EncryptedEventStore] then REFUSES every destructive rewrite —
 * `removeFirst` and `compactAndAppend` both stop on `untrustworthy_read`,
 * deliberately, because a rewrite over a lossy read DESTROYS the frames it
 * failed to read. So after a teardown the store accepts appends only until it
 * reaches `maxBytes`, at which point the bracket's commit refuses too and
 * **append fails permanently**. A supported teardown would otherwise be a
 * guaranteed forward-progress loss on any install that already had stored data.
 *
 * The fix is to remove those now-unrecoverable bytes — but only a list that
 * CANNOT DRIFT from the real construction sites is worth having, because a
 * cleanup that misses one file leaves exactly the stall it exists to prevent,
 * silently, on the one store nobody remembered. So:
 *
 * * every production site that opens a queue-key-backed file takes its NAME from
 * this object, and
 * * `QueueKeyStoreInventoryTest` scans the shipped sources and FAILS if a site
 * passes `StoreKeys.queueKey` to a file name this inventory does not carry.
 *
 * **PUBLIC ONLY BECAUSE `internal` IS COMPILATION-MODULE SCOPED.** `:rn` is a
 * separate Gradle module and cannot see an `internal` symbol declared here, so
 * the NAMES are public behind `@RestrictTo`. [clearAll] — the one function that
 * DELETES anything — stays `internal`, because a caller who removed these files
 * WITHOUT destroying the key first would be destroying recoverable customer
 * data. That asymmetry is the point, and it is pinned at the source.
 *
 * **HARNESS FILES ARE DELIBERATELY NOT MEMBERS.** `:fixture` and `:uattest`
 * open queue-key stores at paths of their own; they are test scaffolding, not
 * customer data, and a product teardown that reached into them would be deleting
 * files the product does not own. The inventory scan is scoped to the shipped
 * modules for that reason, and the scope is asserted rather than assumed.
 *
 * **THE AUTHORITY'S OWN FILES ARE NOT MEMBERS EITHER, AND THAT IS RULING A1.**
 * `session-floors.bin`, the per-session snapshots and WALs live in the
 * `tracescout_authority_v2` domain under `AuthorityNaming.ROOT_DIR`. Destroying
 * the authority key makes them unreadable too — but deleting them would destroy
 * the DURABLE SEQUENCE FLOOR that stops a reused session id from allocating from
 * zero, which is sequence reuse. The authority's recovery from an unreadable
 * artifact is its own typed refusal path, not a delete.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object QueueKeyStores {

    // ── the Android producer ─────────────────────────────────────────────────

    /** The live replay queue. */
    const val SDK_QUEUE: String = "tracescout-mobile-queue.bin"

    /** Parked records — a producer-capability refusal's preserved bytes. */
    const val SDK_PARKED_RECORDS: String = "tracescout-mobile-parked.bin"

    /** The parked manifest — which batches are already terminal. */
    const val SDK_PARKED_MANIFEST: String = "tracescout-mobile-parked-manifest.bin"

    // ── the React Native producer ────────────────────────────────────────────

    /** The RN live queue. Separate from the Android producer's by design. */
    const val RN_QUEUE: String = "tracescout-rn-queue.bin"

    /** RN parked records. */
    const val RN_PARKED_RECORDS: String = "tracescout-rn-parked.bin"

    /** RN parked manifest. */
    const val RN_PARKED_MANIFEST: String = "tracescout-rn-parked-manifest.bin"

    /** RN visual frames — a queue-key store with a PRESERVE eviction policy. */
    const val RN_FRAMES: String = "tracescout-rn-frames.bin"

    /**
 * RN frame-content-loss accounting — the durable ledger of frame content
 * this install could not keep.
 *
 * **A MEMBER BEFORE ITS STORE EXISTS, AND THAT IS THE ORDER THE
 * CONTRACT REQUIRES.** The inventory's scan runs site -> inventory: a
 * construction site naming a file this object does not carry is the
 * finding. A member with no site yet is therefore legal, and declaring it
 * first is what lets the prerequisite guards be green BEFORE the first
 * accounting source file exists. The store that opens it arrives in a
 * later, separately reviewed slice.
 *
 * **AND IT IS A MEMBER OF [ALL], NOT MERELY A NAME.** A queue-key file
 * absent from [ALL] survives a supported teardown as ciphertext nothing can
 * open again — and then permanently blocks forward progress on the one
 * store nobody remembered, which is the exact defect this inventory exists
 * for.
 */
    const val RN_FRAME_LOSS: String = "tracescout-rn-frame-loss.bin"

    /**
 * Every queue-key-backed production file, relative to `filesDir`.
 *
 * ORDER IS NOT MEANINGFUL and nothing may depend on it — the cleanup
 * reports per-NAME outcomes precisely so a partial failure names WHICH file,
 * not "the third one".
 */
    val ALL: List<String> = listOf(
        SDK_QUEUE, SDK_PARKED_RECORDS, SDK_PARKED_MANIFEST,
        RN_QUEUE, RN_PARKED_RECORDS, RN_PARKED_MANIFEST, RN_FRAMES,
        RN_FRAME_LOSS,
    )

    /**
 * Remove every queue-key-backed file in [filesDir]; report per name.
 *
 * **`internal`, AND [StoreTeardown] IS ITS ONLY CALLER** — pinned at the
 * source. This function DELETES customer data, and its safety rests entirely
 * on being reached only after the queue key has been destroyed. A second call
 * site is how that ordering stops being a property and becomes a convention.
 *
 * **SUCCESS IS RE-READ FROM THE FILE SYSTEM, NOT INFERRED FROM `delete`.**
 * `File.delete` answers "did I unlink it", which is false for a file that
 * was never there — an outcome that is perfectly fine for the property in
 * hand. The question the caller has is "can the next store make progress",
 * and only `exists` AFTERWARDS answers it. An absent file is therefore
 * CLEARED, and a file still present is FAILED however the delete reported.
 */
    internal fun clearAll(filesDir: File): StoreTeardown.Cleanup {
        val cleared = ArrayList<String>(ALL.size)
        val failed = ArrayList<String>()
        for (name in ALL) {
            val f = File(filesDir, name)
            try { if (f.exists()) f.delete() } catch (_: Throwable) { /* verified below */ }
            if (f.exists()) failed.add(name) else cleared.add(name)
        }
        return if (failed.isEmpty()) StoreTeardown.Cleanup.Done(cleared)
        else StoreTeardown.Cleanup.Incomplete(cleared, failed)
    }
}

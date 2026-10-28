package com.tracescout.mobile.rn

import com.tracescout.mobile.queue.EncryptedEventStore
import com.tracescout.mobile.queue.QueueKeyStores
import com.tracescout.rn.FrameContentLossCodec
import com.tracescout.rn.FrameContentLossLedger
import com.tracescout.rn.FrameContentLossState
import com.tracescout.rn.FrameLossReadState
import java.io.File
import javax.crypto.SecretKey

/** A classified read. [state] is non-null ONLY for [FrameLossReadState.OK]. */
data class FrameLossRead(
    val outcome: FrameLossReadState,
    val state: FrameContentLossState?,
    /** Records present at read time — the trim arithmetic's input. */
    val size: Int,
    /** The store's own integrity class, carried for disclosure rather than re-derived. */
    val integrityClass: String,
)

/** What an append+trim did. */
sealed class FrameLossWrite {
    /** The successor is the authoritative last record and the prefix was trimmed. */
    data class Committed(val trimmed: Int) : FrameLossWrite()

    /**
 * The successor is durable but the prefix was deliberately NOT trimmed.
 * A trim is a destructive act and may only follow a TRUSTWORTHY post-read.
 */
    data class CommittedNoTrim(val reason: String) : FrameLossWrite()

    /** Nothing durable happened. **G6-B when the caller was recording a refusal.** */
    data class NotDurable(val reason: String) : FrameLossWrite()
}

/**
 * THE DURABLE HOME of frame-loss accounting. `:rn`, step 5/7.
 *
 * **ONE durable accounting home, and no second one.** A bare
 * [EncryptedEventStore] over `tracescout-rn-frame-loss.bin` under the EXISTING
 * `StoreKeys` queue key — so the ledger inherits that container's per-frame
 * AES-256-GCM authentication, its fsync-before-return durability, its torn-tail
 * tolerance and its atomic `DurableReplace` compaction. **A sidecar file is
 * forbidden**: a younger crypto container beside a proven one is a liability,
 * and a non-member file would be residue surviving a completed erasure.
 *
 * **`EvictionPolicy.PRESERVE`, EXPLICITLY.** This store must never drop a
 * record to make room — an evicted accounting record is a loss of the record of
 * a loss. The bound is enforced by refusing, never by evicting.
 *
 * **SEQUENCE-FREE.** Nothing here reads, writes or derives a sequence,
 * `throughSequence`, `checkpointId`, high-water or gap arithmetic, and no
 * metadata event is manufactured — that would burn a sequence and create the
 * exact unaccounted gap the foundation exists to remove.
 *
 * **NOT a generic family-keyed dropped carrier, and NOT in
 * `mobile-loss-core`.** The carrier stays the dedicated rn-core type; the
 * frozen `visualFrames` binding is applied once, declaratively, at the
 * reporting boundary outside this module.
 *
 * **CORRECTED — THIS SENTENCE USED TO READ "STEP 5 OWNS THE STORE; STEP 6
 * OWNS ITS CALLERS. Nothing in `FrameDelivery` or `RnFrameStore.enqueue` routes
 * through this yet."** That was true when it was written and is now FALSE:
 * `RnFrameStore.enqueue` builds the gate and its refusal path calls
 * `recordRefusal`, and `FrameDelivery`'s five mutation sites route through
 * `prepare`/`resolve` — all of which reach disk through [appendState]. The
 * sentence is corrected rather than left standing because the tier-2
 * propagation note inside [appendState] depends on exactly the opposite fact,
 * and two adjacent comments disagreeing is how the next reader is misled.
 *
 * **[appendState] IS ALSO THE ONE TIER-2 OWED PROPAGATION POINT** — see the
 * step 0 block there.
 */
internal class RnFrameLossStore(
    private val dir: File,
    private val key: SecretKey,
) {

    companion object {
        /** The durable home. Bound to the inventory, never spelled as a string here. */
        const val FILE: String = QueueKeyStores.RN_FRAME_LOSS

        /**
 * The declared ceiling. The ONE authoritative home for this number is
 * [FrameContentLossCodec.LEDGER_MAX_BYTES]; the construction site below
 * writes the literal because the SC3 guard requires an explicit numeric
 * `maxBytes` at the site (so a reader cannot mistake a declared bound for
 * an omitted argument). `RnFrameLossStoreTest` asserts the two are EQUAL,
 * so the literal cannot drift from the bound the always-fit proof uses.
 */
        const val LEDGER_MAX_BYTES: Long = 65_536L

        /** The crash-tolerance record count the always-fit proof is taken over. */
        const val LEDGER_RECORDS_HARD_MAX: Int = FrameContentLossCodec.LEDGER_RECORDS_HARD_MAX

        /** Carried from the codec — one home, re-exported for the gate's convenience. */
        const val ROWS_MAX: Int = FrameContentLossCodec.ROWS_MAX
        const val CAUSE_FLOOR: Int = FrameContentLossCodec.CAUSE_FLOOR
    }

    private fun file(): File = File(dir, FILE)

    /**
 * The ONE construction site.
 *
 * Bare: the file, the queue key, an explicit `maxBytes` and an explicit
 * `PRESERVE`. No sidecar, no second store, no wrapper that could quietly
 * supply a different disposition.
 */
    private fun open(): EncryptedEventStore = EncryptedEventStore(
        file(),
        key,
        maxBytes = 65_536L,
        evictionPolicy = EncryptedEventStore.EvictionPolicy.PRESERVE,
    )

    /** Does the ledger file exist? The EXISTENCE WITNESS — `File.exists`, never a read. */
    fun present(): Boolean = file().isFile

    /**
 * Read and CLASSIFY. Order is load-bearing.
 *
 * **ABSENT IS NOT UNREADABLE, AND THE DIRECTION MATTERS BOTH WAYS.** An
 * absent file reads *trustworthy empty* from the store, which is correct —
 * but it is only legitimately zero if this process has not already seen a
 * non-empty ledger. `observedNonEmptyLedger` is the only thing that can tell
 * those apart, and it is process-local by necessity (a durable witness would
 * be residue).
 *
 * **A FRAMING OR AUTH FAILURE IS CLASSIFIED BEFORE ANY DECODE.** Decoding
 * a lossy read would produce a definite-looking state from unreliable bytes.
 */
    fun read(): FrameLossRead {
        val exists = present()
        val r = try {
            open().readAll()
        } catch (t: Throwable) {
            return FrameLossRead(FrameLossReadState.UNTRUSTWORTHY_FRAMING, null, 0, "unreadable_file")
        }
        val cls = r.integrityClass

        if (!r.isTrustworthy) {
            val outcome = when {
                cls.contains("auth") -> FrameLossReadState.UNTRUSTWORTHY_AUTH_FAILURE
                else -> FrameLossReadState.UNTRUSTWORTHY_FRAMING
            }
            return FrameLossRead(outcome, null, r.records.size, cls)
        }

        if (r.records.isEmpty()) {
            // Absent-or-empty AND trustworthy. Which of the two it means is a
            // question about THIS PROCESS's history, not about the bytes.
            val outcome =
                if (!exists && RnFrameLossProcessState.observedNonEmptyLedger())
                    FrameLossReadState.VANISHED_AFTER_OBSERVED
                else FrameLossReadState.TRUSTWORTHY_EMPTY
            return FrameLossRead(outcome, null, 0, cls)
        }

        // A non-empty trustworthy read is the one thing that may set the witness.
        RnFrameLossProcessState.setObservedNonEmptyLedger(RnFrameLossProcessState.epoch())

        if (r.records.size > LEDGER_RECORDS_HARD_MAX) {
            return FrameLossRead(FrameLossReadState.INVARIANT_EXCEEDED, null, r.records.size, cls)
        }

        // `size > 1` is the NORMAL post-crash shape, not an error: the last
        // record is the state and the prefix is history awaiting a trim.
        return when (val d = FrameContentLossCodec.decode(String(r.records.last(), Charsets.UTF_8))) {
            is FrameContentLossCodec.Decoded.Ok ->
                FrameLossRead(FrameLossReadState.OK, d.state, r.records.size, cls)
            is FrameContentLossCodec.Decoded.Refused ->
                if (d.reason == FrameContentLossCodec.CodecRefusal.VERSION_UNSUPPORTED)
                    FrameLossRead(FrameLossReadState.VERSION_UNSUPPORTED, null, r.records.size, cls)
                else
                    FrameLossRead(
                        FrameLossReadState.LEDGER_RECORD_UNDECODABLE, null, r.records.size, cls,
                    )
        }
    }

    /**
 * Write [next] as the new authoritative last record, then trim the history.
 *
 * The seven steps, in this order, every one load-bearing:
 *
 * ```
 * 1 trusted read an untrustworthy read authorises nothing
 * 2 admission trim to ONE predecessor keeps the post-append size at 2
 * 3 RE-READ step 2 was destructive; step 4 must not use a stale count
 * 4 append the successor
 * 5 post-read
 * 6 verify the successor IS the authoritative last record
 * 7 trim exactly postSize - 1
 * ```
 *
 * **WHY STEP 7 IS `postSize - 1` AND NOT A REMEMBERED NUMBER.**
 * `removeFirst(count)` returns `minOf(count, size)` and a stale
 * `count >= size` **destroys the whole store and reports success**. Deriving
 * the count from a read taken in the SAME exclusion makes
 * `postSize - 1 < postSize` true for every `postSize >= 1`, so the
 * store-destroying branch is **unreachable by arithmetic** rather than
 * merely unlikely.
 *
 * **TWO NO-TRIM REFUSALS, each returning [FrameLossWrite.CommittedNoTrim]:**
 * an `INDETERMINATE` append (the successor may or may not be durable, so the
 * history may be all that survives) and an untrustworthy post-read (a trim
 * is destructive and may not follow a read that cannot be believed).
 *
 * **AN APPEND THAT DOES NOT STORE IS [FrameLossWrite.NotDurable], AND
 * THAT IS G6-B WHEN THE CALLER WAS RECORDING A REFUSAL** — a disclosure,
 * never a count. Classification is by the OBSERVED LEDGER-WRITE OUTCOME,
 * never by the reason the frame was refused.
 */
    fun appendState(next: FrameContentLossState): FrameLossWrite {
        // ── 0 — TIER-2 OWED PROPAGATION. THE ONE POINT. ──────────────────────
        //
        // **THIS IS THE SINGLE PROPAGATION POINT, AND IT IS HERE BECAUSE
        // THIS IS THE SINGLE DURABLE APPEND.** All four production callers
        // (`reconcileInRegion`'s VANISHED arm, `applyAndMemoise`, `prepare`,
        // `resolve`, `recordRefusal`) reach disk only through this function, so
        // one fold here covers every committed write. Two ad hoc folds at two
        // call sites would be free to drift, and a drift would be INVISIBLE:
        // both would keep working while one of them stopped disclosing.
        //
        // **THE EPOCH IS CAPTURED FIRST, BEFORE ANYTHING DURABLE HAPPENS.**
        // It is the generation the fold is judged against, exactly as
        // `RnFrameStore.enqueue` reads it before its acquisition. A governed
        // erasure during this call advances the epoch and zeroes every flag, so
        // the clear below is refused and this generation cannot discard a
        // disclosure belonging to the next one.
        //
        // **WHAT WAS BROKEN:** the two owed bits were WRITTEN at three sites
        // in `RnFrameStore` and READ BY NOTHING, so the promise in that file —
        // "the next committed write will carry `refusalRecordingIncomplete`" —
        // was not implemented and the tier-2 disclosures never became durable.
        // The already-working `observedNonEmptyLedger` pattern a few lines above
        // in `read` is the shape this mirrors.
        val at = RnFrameLossProcessState.epoch()
        val oweRefusalRecording = RnFrameLossProcessState.refusalRecordingOwed()
        val oweExclusionRefusal = RnFrameLossProcessState.exclusionRefusalOwed()

        // **FOLDED THROUGH THE LEDGER'S OWN MONOTONIC SETTERS, NEVER A RAW
        // `copy`.** Those setters are the one home of the monotonicity rule
        // (`if (s.flag) s else s.copy(flag = true)`); a local copy here would be
        // a second opinion about when a monotonic boolean may be set.
        //
        // **NO NEW FIELD AND NO FORMAT CHANGE.** Both booleans are already
        // declared by `FrameContentLossState` and already serialized by
        // `FrameContentLossCodec` (`put(if (s.refusalRecordingIncomplete) …)`),
        // so this writes existing fields and the on-disk grammar is untouched.
        var folded = next
        if (oweRefusalRecording) {
            folded = FrameContentLossLedger.setRefusalRecordingIncomplete(folded)
        }
        if (oweExclusionRefusal) {
            folded = FrameContentLossLedger.setExclusionRefusalIncomplete(folded)
        }

        /**
 * The ONE discharge point. Reached only from the two `Committed` exits.
 *
 * **ONLY WHAT WAS ACTUALLY FOLDED IS CLEARED.** A refusal arriving
 * AFTER the fold leaves its own bit set for the next write; clearing a
 * bit this call never folded would discard a disclosure that was never
 * recorded. `setExclusionRefusalOwed` is called with NO lock held (the
 * scope it would have taken was refused), so that race is real, not
 * theoretical.
 *
 * **`CommittedNoTrim` DELIBERATELY DOES NOT DISCHARGE.** On
 * `append_indeterminate` the successor may or may not be durable; on the
 * post-read and trim refusals the bytes are probably durable but this
 * process cannot prove it. An unproven write is not a paid debt — the
 * bit stays set and the next committed write folds it again, which is
 * harmless because the field is a monotonic boolean. Erring the other
 * way would silently drop a disclosure.
 */
        fun discharge() {
            if (oweRefusalRecording) RnFrameLossProcessState.clearRefusalRecordingOwed(at)
            if (oweExclusionRefusal) RnFrameLossProcessState.clearExclusionRefusalOwed(at)
        }

        val s = try { open() } catch (t: Throwable) {
            return FrameLossWrite.NotDurable("store_unavailable")
        }

        // 1 — a trusted read, or nothing is authorised.
        val pre = try { s.readAll() } catch (t: Throwable) {
            return FrameLossWrite.NotDurable("read_failed")
        }
        if (!pre.isTrustworthy) return FrameLossWrite.NotDurable("untrustworthy_read")
        if (pre.records.size > LEDGER_RECORDS_HARD_MAX) {
            return FrameLossWrite.NotDurable("invariant_exceeded")
        }

        // 2 — admission trim down to ONE predecessor.
        if (pre.records.size > 1) {
            val admission = pre.records.size - 1
            val rr = s.removeFirstDetailed(admission)
            if (rr.durability != EncryptedEventStore.Durability.COMMITTED) {
                return FrameLossWrite.NotDurable("admission_trim_not_committed")
            }
        }

        // 3 — RE-READ. Step 2 was destructive; a stale count here is the exact
        // defect step 7's arithmetic exists to make unreachable.
        val mid = try { s.readAll() } catch (t: Throwable) {
            return FrameLossWrite.NotDurable("reread_failed")
        }
        if (!mid.isTrustworthy) return FrameLossWrite.NotDurable("untrustworthy_reread")

        // 4 — append the successor. `folded`, never `next`: the disclosure
        // must be inside the bytes that reach disk, not applied afterwards.
        val bytes = FrameContentLossCodec.encode(folded).toByteArray(Charsets.UTF_8)
        val ar = s.append(bytes)
        if (!ar.stored) return FrameLossWrite.NotDurable(ar.reason ?: "append_refused")
        if (ar.durability == EncryptedEventStore.Durability.INDETERMINATE_DIRECTORY_UNSYNCED) {
            // The successor may or may not be durable. The history may be all
            // that survives, so it is NOT trimmed.
            return FrameLossWrite.CommittedNoTrim("append_indeterminate")
        }

        // 5 — post-read.
        val post = try { s.readAll() } catch (t: Throwable) {
            return FrameLossWrite.CommittedNoTrim("post_read_failed")
        }
        if (!post.isTrustworthy) return FrameLossWrite.CommittedNoTrim("untrustworthy_post_read")

        // 6 — the successor must BE the authoritative last record.
        val last = post.records.lastOrNull()
        if (last == null || !last.contentEquals(bytes)) {
            return FrameLossWrite.CommittedNoTrim("successor_not_authoritative")
        }

        // 7 — trim exactly postSize - 1, derived from the read above.
        val toTrim = post.records.size - 1
        if (toTrim <= 0) { discharge(); return FrameLossWrite.Committed(0) }
        val tr = s.removeFirstDetailed(toTrim)
        if (tr.durability != EncryptedEventStore.Durability.COMMITTED) {
            return FrameLossWrite.CommittedNoTrim("trim_not_committed")
        }
        discharge()
        return FrameLossWrite.Committed(tr.removed)
    }

    /** The whole-store fold, for the PENDING discriminator. Never a per-record digest. */
    fun fingerprint(records: List<ByteArray>): String =
        EncryptedEventStore.logicalFingerprint(records)

    /** TEST-ONLY: the live record count, for driving the crash shapes. */
    fun recordCountForTest(): Int = try { open().readAll().records.size } catch (t: Throwable) { -1 }
}

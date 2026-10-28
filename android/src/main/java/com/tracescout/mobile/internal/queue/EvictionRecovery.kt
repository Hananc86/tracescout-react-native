package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.internal.loss.LossAuthority
import com.tracescout.mobile.queue.EncryptedEventStore
import com.tracescout.mobile.queue.QueueHome
import com.tracescout.mobile.queue.QueueRegion
import com.tracescout.mobile.queue.RecoveryTrace

/**
 * F4-P0 Slice D (D1/D2/D4/D5/D8) — THE OPEN-TIME RECOVERY DRIVER for an
 * eviction bracket a previous process left unresolved.
 *
 * ```
 * recover inside ONE QueueRegion.RECOVERY (region lock + file fence)
 * marker absent -> NotNeeded (nothing read, nothing written)
 * marker unreadable / names another queue -> Blocked, marker kept (M-4, M-5)
 * EXACT queue image (trustworthy or not) -> the live records, their fingerprint
 * COMPLETE scan of EVERY authority -> this bracket's unresolved intents (D2)
 * Incomplete -> Blocked, marker kept, nothing settled
 * per intent, the the specification verdict:
 * 1 the authority's own entry is DELIVERED or LOST under another cause -> DID_NOT_HAPPEN
 * 2 the victim's record is still in the queue -> DID_NOT_HAPPEN
 * 3 absent AND the read was trustworthy AND fingerprint == target -> LOST(BYTE_EVICTED)
 * 4 anything else -> UNKNOWABLE (settle nothing)
 * finalize through the ONE attribution seam, bounded attempts per open (F-5)
 * any failure / any UNKNOWABLE -> Blocked, marker kept, fence kept (F-1.F-4)
 * RE-SCAN: COMPLETE ∧ this bracket's set EMPTY — against what is durably FINALIZED
 * remove the marker: delete, parent fsync, verify absent (M-3)
 * rearm the no-progress counter (the blocker transitioned) (D8)
 * ```
 *
 * ## What makes the verdicts decidable
 *
 * `compactAndAppend` is atomic over the whole victim set and refuses unless
 * the OLD fingerprint matched; `logicalFingerprint` is a pure function of the
 * record list. So the live image is the source image (nothing happened), the
 * target image (the commit happened), or something else — and RF4-11 refuses
 * every append and drain while the marker is present, so "something else" is
 * reachable only through the crash path, which this driver answers UNKNOWABLE
 * rather than guesses. Rule 1 runs first so a record the server committed or
 * a later phase already settled is never re-settled under a different cause.
 *
 * ## Fail closed, in every direction
 *
 * An INCOMPLETE scan is not an empty one; a failed finalization is not a
 * resolved intent; a best-effort settlement never lifts the fence — the
 * marker, which IS the durable blocked state every append and drain consults,
 * leaves only after a second COMPLETE scan shows nothing of this bracket is
 * unresolved. Nothing here reads a verdict out of an attempt; the re-scan
 * reads the authorities.
 *
 * ## Idempotent across opens
 *
 * Every settlement is idempotent by `(bracketId, sequence)` (the authority
 * short-circuits an applied intent id); a marker whose intents are all
 * settled is removed on the next open with zero settlements; an absent marker
 * is a no-op. A death at any named seam point leaves a state the next
 * `recover` concludes identically.
 *
 * ## What this deliberately does NOT do
 *
 * No planner call, no victim selection, no new eviction, no append, no removal
 * of a record, no `compactAndAppend`: the only queue mutation this driver can
 * make is the removal of the marker. The settlements go through
 * [EvictionAttribution] — the bracket's own seam — so the facade's writers
 * keep their single caller file.
 *
 * Lock order: `QUEUE REGION LOCK → QUEUE FILE FENCE → STORE MONITOR → AUTHORITY`,
 * one region held across the whole recovery; the scan takes no lock of its
 * own (the region excludes every eviction writer in every process).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class EvictionRecovery(
    private val home: QueueHome,
    /** The attribution seam. The unwired default cannot finalize, so a bracket stays Blocked on it. */
    private val attribution: EvictionAttribution = EvictionAttribution.none(),
    /** The complete scan. Production: `LossAuthority.scanUnresolvedEvictions(filesDir)`. */
    private val scan: () -> LossAuthority.EvictionScan,
    /**
 * FAULT SEAM — inert by default. A test throws
 * [DurableUploadPipeline.SimulatedProcessDeath] at ONE named point and then
 * constructs a new driver over the SAME home, which is what a relaunch is.
 */
    private val faults: (String) -> Unit = { },
) {

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Outcome {
        /** No marker: nothing was read beyond the marker file and nothing was written. */
        object NotNeeded : Outcome()

        /** Every intent of the bracket is durably finalized, the re-scan was COMPLETE and EMPTY, the marker is gone. */
        data class Resolved(val bracketId: String, val lost: Int, val didNotHappen: Int) : Outcome()

        /**
 * The marker stays, every gate stays closed, and NOTHING was settled
 * best-effort. [settled] counts finalizations that are durable this
 * run; [unresolved] what is still owed. Typed recovery-pending.
 */
        data class Blocked(val bracketId: String?, val reason: String, val settled: Int, val unresolved: Int) : Outcome()
    }

    private enum class Verdict { LOST, DID_NOT_HAPPEN, UNKNOWABLE }

    private val store: EncryptedEventStore = home.store()
    private val marker: BracketMarker = home.bracketMarker()

    /** The driver. One region; a lock layer's refusal is a typed Blocked, never a proceed-unlocked path. */
    fun recover(): Outcome = when (val o = home.tryWithQueue(QueueRegion.RECOVERY) { recoverInRegion() }) {
        is QueueHome.Outcome.Ran -> o.value
        is QueueHome.Outcome.Refused -> {
            RecoveryTrace.log("bracket_recovery_region_refused", "why" to o.refusal.wire, "waitedMs" to o.refusal.waitedMs)
            Outcome.Blocked(null, "recovery_queue_" + o.refusal.wire, 0, 0)
        }
    }

    private fun recoverInRegion(): Outcome {
        // ── the marker ──
        val m = when (val r = marker.read()) {
            is BracketMarker.Read.Unreadable -> return blocked(null, "bracket_marker_unreadable:" + r.reason, 0, 0)
            is BracketMarker.Read.Ok -> r.marker ?: return Outcome.NotNeeded
        }
        // M-5: a marker naming another queue is INCONCLUSIVE, never ignored.
        if (m.queueFile != home.queueFile.name) return blocked(m.bracketId, "marker_queue_mismatch", 0, 0)
        // F4-P1: a marker whose cause names no settlement writer is INCONCLUSIVE —
        // finalizing it would invent a cause the bracket never bound.
        if (m.cause != BracketMarker.CAUSE_BYTE_EVICTED && m.cause != BracketMarker.CAUSE_QUARANTINED) {
            return blocked(m.bracketId, "marker_cause_unknown:" + m.cause.take(24), 0, 0)
        }
        faults("after_marker_read")

        // ── the EXACT queue image ──
        val read = store.readAll()
        val live = read.records
        val trustworthy = read.isTrustworthy
        val fp = if (trustworthy) EncryptedEventStore.logicalFingerprint(live) else null
        val present = HashSet<Pair<String, Long>>()
        for (r in live) LossAuthority.RecordFields.parse(r)?.let { present.add(it.sessionId to it.sequence) }

        // ── the COMPLETE scan (D2) ──
        val mine = when (val s = scan()) {
            is LossAuthority.EvictionScan.Incomplete -> return blocked(m.bracketId, "scan_incomplete:" + s.reason, 0, -1)
            is LossAuthority.EvictionScan.Complete -> s.intents.filter { it.intentId == LossAuthority.evictionIntentId(m.bracketId, it.sequence) }
        }
        faults("after_scan")

        // ── verdict + finalization, per intent, through the seam (D4) ──
        var lost = 0; var nh = 0; var unknowable = 0
        val failed = ArrayList<String>()
        for ((sid, intents) in mine.groupBy { it.sessionId }) {
            // The handle: the removal door, exactly as the bracket opened it. A
            // door that cannot answer Ready is a finalization that cannot be made.
            when (val d = attribution.resolve(sid, live, trustworthy)) {
                is AttributionSink.Adoption.Ready, is AttributionSink.Adoption.NotWired -> Unit
                is AttributionSink.Adoption.Refused -> { failed.add("door_refused:" + d.reason); continue }
                is AttributionSink.Adoption.NotDurable -> { failed.add("door_not_durable:" + d.reason); continue }
            }
            for (i in intents) {
                when (verdict(i, m, present, trustworthy, fp)) {
                    Verdict.UNKNOWABLE -> unknowable++
                    Verdict.LOST -> finalize(true, sid, m.bracketId, i.sequence, m.cause)?.let { failed.add(it) } ?: lost++
                    Verdict.DID_NOT_HAPPEN -> finalize(false, sid, m.bracketId, i.sequence)?.let { failed.add(it) } ?: nh++
                }
                faults("after_finalize:${sid.take(8)}:${i.sequence}")
            }
        }
        val settled = lost + nh
        if (unknowable > 0) {
            RecoveryTrace.log("bracket_recovery_unknowable", "bracket" to m.bracketId.take(12), "unknowable" to unknowable, "settled" to settled)
            return blocked(m.bracketId, "unknowable", settled, unknowable + failed.size)
        }
        if (failed.isNotEmpty()) {
            RecoveryTrace.log("bracket_recovery_finalization_failed", "bracket" to m.bracketId.take(12), "failed" to failed.size, "settled" to settled)
            return blocked(m.bracketId, "finalization_failed:" + failed[0].take(40), settled, failed.size)
        }
        faults("before_rescan")

        // ── COMPLETE ∧ EMPTY, read from the authorities, never from the attempts (F-4) ──
        val remaining = when (val s = scan()) {
            is LossAuthority.EvictionScan.Incomplete -> return blocked(m.bracketId, "rescan_incomplete:" + s.reason, settled, -1)
            is LossAuthority.EvictionScan.Complete -> s.intents.count { it.intentId == LossAuthority.evictionIntentId(m.bracketId, it.sequence) }
        }
        if (remaining != 0) return blocked(m.bracketId, "unresolved_after_finalization:$remaining", settled, remaining)
        faults("before_marker_remove")

        // ── M-3: the marker leaves — delete, parent fsync, verified absent ──
        when (val w = marker.remove(m.bracketId)) {
            is BracketMarker.Write.Committed -> Unit
            is BracketMarker.Write.Refused -> return blocked(m.bracketId, "marker_removal_refused:" + w.reason, settled, 0)
            is BracketMarker.Write.NotDurable -> return blocked(m.bracketId, "marker_removal_not_durable:" + w.reason, settled, 0)
        }
        faults("after_marker_remove")
        // D8: the blocker transitioned — the Slice B no-progress epoch rearms through its seam.
        home.protection().localProgress("bracket_recovered")
        RecoveryTrace.log("bracket_recovered", "bracket" to m.bracketId.take(12), "lost" to lost, "didNotHappen" to nh)
        return Outcome.Resolved(m.bracketId, lost, nh)
    }

    /** the capture specification, in order. */
    private fun verdict(
        i: LossAuthority.UnresolvedIntent, m: BracketMarker.Marker,
        present: Set<Pair<String, Long>>, trustworthy: Boolean, fp: String?,
    ): Verdict {
        // 1 — the authority's OWN entry already answers: delivered, or lost under another cause.
        val e = attribution.eligibility(i.sessionId, i.sequence)
        if (e is EvictionAttribution.Eligibility.Ineligible && (e.reason == "already_delivered" || e.reason == "already_lost")) return Verdict.DID_NOT_HAPPEN
        // 2 — the record is still in the queue: the commit did not remove it.
        if ((i.sessionId to i.sequence) in present) return Verdict.DID_NOT_HAPPEN
        // 3 — absent, the read was trustworthy, and the image IS the planned target.
        if (trustworthy && fp == m.targetFingerprint) return Verdict.LOST
        // 4 — anything else.
        return Verdict.UNKNOWABLE
    }

    /** null = durably recorded; else the last failure reason. Bounded (F-5), idempotent by (bracketId, sequence) (F-3). */
    private fun finalize(lost: Boolean, sid: String, bracketId: String, seq: Long, cause: String = BracketMarker.CAUSE_BYTE_EVICTED): String? {
        var last = "unattempted"
        // F4-P1: the marker's cause decides the LOST writer — BYTE_EVICTED or
        // QUARANTINED, never a third value (an unknown cause is refused BEFORE
        // the scan, see `causeKnown`). DID_NOT_HAPPEN has one writer.
        for (k in 0 until FINALIZE_ATTEMPTS_PER_OPEN) {
            val o = when {
                !lost -> attribution.settleDidNotHappen(sid, bracketId, seq)
                cause == BracketMarker.CAUSE_QUARANTINED -> attribution.settleQuarantined(sid, bracketId, seq)
                else -> attribution.settleLost(sid, bracketId, seq)
            }
            last = when (o) {
                is AttributionSink.Outcome.Recorded -> return null
                is AttributionSink.Outcome.Refused -> o.reason
                is AttributionSink.Outcome.NotDurable -> "not_durable:" + o.reason
                is AttributionSink.Outcome.NotWired -> "unwired"
            }
            faults("after_finalize_attempt:$k")
        }
        return last
    }

    private fun blocked(bracketId: String?, reason: String, settled: Int, unresolved: Int): Outcome.Blocked {
        RecoveryTrace.log("bracket_recovery_blocked", "bracket" to (bracketId ?: "none").take(12), "why" to reason.take(40),
            "settled" to settled, "unresolved" to unresolved)
        return Outcome.Blocked(bracketId, reason, settled, unresolved)
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /**
 * Finalization attempts per intent per open. A failure
 * after the bound is a typed `Blocked("finalization_failed…")` — the
 * marker and the fence stay, and the next open retries. Bounded so a
 * refusing authority cannot turn recovery into a loop.
 */
        const val FINALIZE_ATTEMPTS_PER_OPEN: Int = 3

        /**
 * The typed reason NORMAL WORK is refused on [home] right now, or null
 * (D8 / U-2): an erasure request (`erasure_requested`), an unresolved
 * bracket (`bracket_unresolved`) or an unreadable marker
 * (`bracket_marker_unreadable`). Two non-key-backed file reads; opens
 * nothing, locks nothing. Consulted BEFORE an admission so a blocked
 * queue does not burn authority frames on refusals.
 */
        @JvmStatic
        fun blocks(home: QueueHome): String? {
            if (home.erasure().presentOrUnreadable()) return "erasure_requested"
            return when (val r = home.bracketMarker().read()) {
                is BracketMarker.Read.Unreadable -> "bracket_marker_unreadable"
                is BracketMarker.Read.Ok -> if (r.marker != null) "bracket_unresolved" else null
            }
        }
    }
}

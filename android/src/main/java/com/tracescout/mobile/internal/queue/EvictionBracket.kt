package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.internal.loss.LossAuthority
import com.tracescout.mobile.queue.EncryptedEventStore
import com.tracescout.mobile.queue.F4Constants
import com.tracescout.mobile.queue.F4StageAcceptanceGate
import com.tracescout.mobile.queue.QueueHome
import com.tracescout.mobile.queue.QueueRefusal
import com.tracescout.mobile.queue.QueueRegion
import com.tracescout.mobile.queue.RecoveryTrace

/**
 * F4-P0 Slice C — THE ONE DESTRUCTIVE HOME for byte eviction on the active
 * record queue, and the governed append both producers route through.
 *
 * ```
 * append(record)
 * marker present/unreadable? -> refuse `bracket_unresolved` (RF4-11)
 * store.append(record) -> stored, or any refusal but `at_capacity`: return it
 * region BRACKET, ONE acquisition:
 * re-check marker; re-try the plain append (a drain may have made room)
 * PLAN trustworthy image + protected + per-record attribution -> EvictionPlanner (C1)
 * PREFLIGHT door Ready per session, ceiling, eligibility, headroom per session (C3)
 * MARKER install durably, parent-dir fsync, read back — BEFORE the first intent (C2)
 * PREPARE one durable EvictionIntent per victim, every session, all of them (C4)
 * COMMIT ONE compactAndAppend(source, keep, record, target) (C5)
 * SETTLE per the OBSERVED outcome: (C6)
 * COMMITTED -> every victim LOST(BYTE_EVICTED); marker removed
 * REFUSED_* / FAILED -> provably no commit: every prepared victim
 * DID_NOT_HAPPEN; marker removed
 * AMBIGUOUS / INDET. -> UNKNOWABLE: nothing settled, marker KEPT (Slice D)
 * ```
 *
 * `NO ATTRIBUTION ⇒ NO DESTRUCTION` is dominant throughout: a record the
 * authority cannot own is never a victim, an unwired seam refuses the whole
 * bracket, an UNSET ceiling refuses the whole bracket, a protected digest is
 * excluded structurally in the planner, and no step after PREPARE can be
 * reached without the marker durable. Every refusal is typed and mutates
 * nothing; after PREPARE a refusal settles the prepared subset
 * DID_NOT_HAPPEN only because the commit is PROVABLY not reached.
 *
 * ## Why `EncryptedEventStore.append` no longer makes room
 *
 * It used to evict oldest-first inside `append` — a second destructive
 * writer that destroyed records with no durable intent, no attribution and no
 * settlement. That call is deleted (`SliceCSourceTest` pins `append`'s body
 * carries no destructive branch and no `evictOldest` exists in any root), so
 * the only way a record leaves the queue under pressure is through [commit],
 * which is the one production receiver call of `compactAndAppend` in the tree.
 *
 * ## What this slice deliberately does NOT do
 *
 * No recovery of a marker found at open, no scan, no teardown integration, no
 * quarantine settlement, no reconciler. A bracket left UNKNOWABLE (or a
 * process that died mid-bracket) leaves the marker and the intents on disk and
 * every later append and drain on that queue REFUSES typed until Slice D
 * resolves it — fail closed, by ruling, and stated as a cliff.
 *
 * ## F4-P1 — the SECOND governed entry: [quarantineHead] (F4-15, ruling R4)
 *
 * ```
 * quarantineHead(n, prefixDigests, fenced, code) ONE BRACKET region (nested, reentrant, inside the pipeline's QUARANTINE region)
 * erasure / marker gates -> refuse, typed (U-2, RF4-11)
 * PLAN trustworthy image; the head n records ARE the victims (no planner choice — the server
 * indicted exactly these bytes); exact prefix match by digest; the F2 per-slice mutation
 * gate; every victim owned + ADMITTED + eligible through the removal door; protection readable
 * PREFLIGHT headroom per session
 * MARKER cause QUARANTINED, incoming digest "" (no record is added), nonce
 * PREPARE one durable EvictionIntent per victim — the SAME writer the eviction uses
 * COMMIT the ONE compactAndAppend receiver call, RETAIN-ONLY (`record = null`): result = survivors
 * SETTLE COMMITTED -> every victim LOST(QUARANTINED) through the seam's quarantine settlement;
 * refusals / FAILED -> DID_NOT_HAPPEN; AMBIGUOUS / INDETERMINATE -> UNKNOWABLE (recovery)
 * ```
 *
 * The two entries share ONE enactment ([enact]): the same marker discipline,
 * the same PREPARE writer, the same single commit receiver call, the same
 * outcome switch, the same cleanup ordering. What differs is PLAN (chosen
 * victims vs. an indicted prefix), the incoming record (one vs. none) and the
 * cause the marker binds — and the cause is what the settlement writes, so a
 * record can never be settled under a cause its bracket did not name.
 * Protection is NOT a planning exclusion here: the indicted prefix is exactly
 * the batch that is protected in flight, and the pipeline releases that
 * protection only after the committed removal (Slice B, B18).
 *
 * ## Lock order
 *
 * `QUEUE REGION LOCK → QUEUE FILE FENCE → STORE MONITOR → AUTHORITY`, one
 * region held across the whole bracket; every store call nests (both layers
 * are reentrant) and every authority write happens below them.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class EvictionBracket(
    private val home: QueueHome,
    /** The attribution seam. The unwired default REFUSES every bracket. */
    private val attribution: EvictionAttribution = EvictionAttribution.none(),
    /**
 * The effective victim ceiling. Production reads the gated constant,
 * which THROWS while `VICTIMS_OPERATIONAL_MAX` is UNSET and so refuses
 * every bracket (C10); a test injects an explicit provisional value.
 */
    private val victimCeiling: () -> Long = { F4Constants.effectiveVictimCeiling() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /**
 * FAULT SEAM — inert by default. A test throws
 * [DurableUploadPipeline.SimulatedProcessDeath] at ONE named point, or
 * performs a side effect (an append between PREPARE and COMMIT), and then
 * constructs a new bracket over the SAME home, which is what a relaunch is.
 */
    private val faults: (String) -> Unit = { },
) {

    /** What the bracket decided. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Outcome {
        /** The plain append stored the record (or refused it for a non-capacity reason). No bracket ran. */
        data class Plain(val append: EncryptedEventStore.AppendResult) : Outcome()

        /** The bracket COMMITTED and every victim settled LOST under the bracket's cause (BYTE_EVICTED, or QUARANTINED for F4-P1); the marker is gone. */
        data class Evicted(val bracketId: String, val victims: Int) : Outcome()

        /** The bracket COMMITTED; one or more settlements were refused or not durable. Marker KEPT for Slice D. */
        data class SettleIncomplete(val bracketId: String, val victims: Int, val settled: Int, val reasons: List<String>) : Outcome()

        /** Zero mutation (or a provably-unreached commit with every prepared victim settled DID_NOT_HAPPEN). */
        data class Refused(val code: String, val detail: String?) : Outcome()

        /** The commit's outcome cannot be known. NOTHING settled; marker, intents and fence state left for Slice D. */
        data class Unknowable(val bracketId: String, val code: String) : Outcome()
    }

    /**
 * The producer-facing result: the same three facts `AppendResult` carries
 * ([stored], [reason], [durability]) so `settleAppend` needs no new arm,
 * plus the bracket's own [outcome].
 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class Result(
        val stored: Boolean,
        val reason: String?,
        val durability: EncryptedEventStore.Durability,
        val outcome: Outcome,
    )

    private val store: EncryptedEventStore = home.store()
    private val marker: BracketMarker = home.bracketMarker()

    private class Victim(val c: EvictionPlanner.Candidate) {
        val sessionId: String get() = c.sessionId!!
        val sequence: Long get() = c.sequence!!
    }

    /** The governed append. */
    fun append(record: ByteArray): Result {
        // F4-P0 Slice D (U-2): no new append while an erasure of this install
        // is requested — the system quiesces so the teardown can take the
        // fence. An unreadable request is PRESENT. Checked before the marker
        // so the two refusals are reported by their own names.
        erasureBlocks()?.let { return refused(it, null) }
        // RF4-11: while a bracket is unresolved nothing may change the image
        // its recovery will be decided on. An unreadable marker is PRESENT.
        markerBlocks()?.let { return refused(it, null) }
        val plain = store.append(record)
        if (plain.stored || plain.reason != "at_capacity") return plainResult(plain)
        return when (val o = home.tryWithQueue(QueueRegion.BRACKET) { bracket(record) }) {
            is QueueHome.Outcome.Ran -> o.value
            is QueueHome.Outcome.Refused -> {
                traceQueueRefusal(o.refusal)
                refused("bracket_queue_" + o.refusal.wire, null)
            }
        }
    }

    // ── the bracket, inside ONE region ───────────────────────────────────────

    private fun bracket(record: ByteArray): Result {
        erasureBlocks()?.let { return refused(it, null) }
        markerBlocks()?.let { return refused(it, null) }
        // A drain may have run between the refusal and this acquisition.
        val again = store.append(record)
        if (again.stored || again.reason != "at_capacity") return plainResult(again)

        if (!attribution.isWired()) return refused("eviction_refused_attribution_unwired", null)

        // ── PLAN ──
        val read = store.readAll()
        if (!read.isTrustworthy) {
            RecoveryTrace.log("bracket_refused", "why" to "untrustworthy_read", "reason" to read.integrityClass)
            return refused("eviction_refused_untrustworthy_read", read.integrityClass)
        }
        val live = read.records
        val protected = home.protection().protectedDigests()
            ?: return refused("eviction_refused_protection_unreadable", null)
        val ceiling = try {
            victimCeiling()
        } catch (b: F4StageAcceptanceGate.Blocked) {
            RecoveryTrace.log("bracket_refused", "why" to "victim_ceiling_unset")
            return refused("eviction_refused_victim_ceiling_unset", null)
        }
        val candidates = candidates(live)
        val plan = EvictionPlanner.plan(
            candidates, protected, EncryptedEventStore.frameBytes(record.size), store.capacityBytes(), ceiling,
        )
        val victims: List<Victim>
        val keep: List<Int>
        when (plan) {
            is EvictionPlanner.Plan.Refused -> {
                RecoveryTrace.log("bracket_refused", "why" to plan.code.take(40), "need" to plan.neededBytes, "freeable" to plan.freeableBytes)
                return refused(plan.code, null)
            }
            is EvictionPlanner.Plan.Ok -> { victims = plan.victims.map { Victim(it) }; keep = plan.keepIndices }
        }
        if (victims.isEmpty()) return plainResult(store.append(record))

        // ── PREFLIGHT: headroom for PREPARE + SETTLE in EVERY affected session, before the first intent ──
        val perSession = victims.groupBy { it.sessionId }
        for ((sid, vs) in perSession) {
            val room = attribution.headroomVictims(sid)
            if (room == null || room < vs.size.toLong()) {
                val have: Long = room ?: -1L
                RecoveryTrace.log("bracket_refused", "why" to "authority_headroom", "session" to sid.take(24),
                    "need" to vs.size, "have" to have)
                return refused("eviction_refused_authority_headroom", sid.take(24))
            }
        }

        // ── MARKER ──
        val sourceFp = EncryptedEventStore.logicalFingerprint(live)
        val next = keep.map { live[it] } + listOf(record)
        val targetFp = EncryptedEventStore.logicalFingerprint(next)
        val victimDigests = victims.map { it.c.digest }
        val incomingDigest = DeliveryProtection.sha256Hex(record)
        // Slice D: a per-attempt nonce — a re-run of the SAME plan after a
        // recovery is a NEW bracket with NEW intent ids (see BracketMarker.Marker.nonce).
        val nonce = randomNonce()
        val bracketId = BracketMarker.bracketIdOf(
            home.queueFile.name, sourceFp, targetFp, incomingDigest, victimDigests, BracketMarker.CAUSE_BYTE_EVICTED, nonce,
        )
        val m = BracketMarker.Marker(
            bracketId, home.queueFile.name, sourceFp, targetFp, incomingDigest, victimDigests,
            BracketMarker.CAUSE_BYTE_EVICTED, nowMillis(), nonce,
        )
        when (val w = marker.install(m)) {
            is BracketMarker.Write.Committed -> Unit
            is BracketMarker.Write.Refused -> return refused("bracket_marker_refused", w.reason)
            is BracketMarker.Write.NotDurable -> return refused("bracket_marker_not_durable", w.reason)
        }
        RecoveryTrace.log("bracket_marker_installed", "bracket" to bracketId.take(12), "victims" to victims.size)
        faults("after_marker_install")
        return enact(bracketId, victims, keep, sourceFp, targetFp, record, BracketMarker.CAUSE_BYTE_EVICTED)
    }

    // ── F4-P1: the quarantine bracket ────────────────────────────────────────

    /**
 * F4-P1 (F4-15): remove the server-indicted head prefix of [n] records
 * under the SAME bracket discipline as a byte eviction, settling each
 * victim `LOST(QUARANTINED)` only after the commit is PROVEN.
 *
 * @param expectedPrefixDigests sha256 of each head record, in queue order,
 * as the caller observed them — the plan is pinned to EXACTLY these bytes.
 * @param fenced the F2 per-slice mutation gate (the pipeline's): asked
 * about the exact prefix, answers the fenced sequences that forbid the
 * removal. A non-empty answer refuses the bracket before any intent.
 * @param code the server's refusal code, carried into the trace only.
 */
    fun quarantineHead(n: Int, expectedPrefixDigests: List<String>, fenced: (List<ByteArray>) -> List<Long>, code: String?): Result {
        if (n <= 0 || expectedPrefixDigests.size != n) return refused("quarantine_refused_invalid_prefix", null)
        erasureBlocks()?.let { return refused(it, null) }
        markerBlocks()?.let { return refused(it, null) }
        return when (val o = home.tryWithQueue(QueueRegion.BRACKET) { quarantineInRegion(n, expectedPrefixDigests, fenced, code) }) {
            is QueueHome.Outcome.Ran -> o.value
            is QueueHome.Outcome.Refused -> {
                traceQueueRefusal(o.refusal)
                refused("bracket_queue_" + o.refusal.wire, null)
            }
        }
    }

    private fun quarantineInRegion(n: Int, expectedPrefixDigests: List<String>, fenced: (List<ByteArray>) -> List<Long>, code: String?): Result {
        erasureBlocks()?.let { return refused(it, null) }
        markerBlocks()?.let { return refused(it, null) }
        if (!attribution.isWired()) return refused("eviction_refused_attribution_unwired", null)

        // ── PLAN: the indicted prefix IS the victim set; nothing is chosen ──
        val read = store.readAll()
        if (!read.isTrustworthy) {
            RecoveryTrace.log("quarantine_bracket_refused", "why" to "untrustworthy_read", "reason" to read.integrityClass)
            return refused("quarantine_refused_untrustworthy_read", read.integrityClass)
        }
        val live = read.records
        if (live.size < n) return refused("quarantine_refused_prefix_changed", "live=${live.size}")
        val head = live.subList(0, n)
        for ((i, r) in head.withIndex()) {
            if (DeliveryProtection.sha256Hex(r) != expectedPrefixDigests[i]) return refused("quarantine_refused_prefix_changed", "index=$i")
        }
        val fencedSeqs = fenced(head)
        if (fencedSeqs.isNotEmpty()) {
            RecoveryTrace.log("quarantine_bracket_refused", "why" to "mutation_fenced", "count" to fencedSeqs.size)
            return refused("quarantine_refused_mutation_fenced", fencedSeqs.size.toString())
        }
        if (home.protection().protectedDigests() == null) return refused("eviction_refused_protection_unreadable", null)
        // NO ATTRIBUTION ⇒ NO DESTRUCTION: every victim must be owned, ADMITTED and eligible, through the removal door.
        val candidates = candidates(live).subList(0, n)
        val victims = ArrayList<Victim>(n)
        for (c in candidates) {
            if (!c.eligible) {
                val why = c.ineligibleWhy ?: "?"
                RecoveryTrace.log("quarantine_bracket_refused", "why" to "victim_ineligible", "reason" to why.take(40))
                return refused("quarantine_refused_victim_ineligible", c.ineligibleWhy)
            }
            victims.add(Victim(c))
        }
        val keep = (n until live.size).toList()

        // ── PREFLIGHT: headroom for PREPARE + SETTLE in EVERY affected session ──
        for ((sid, vs) in victims.groupBy { it.sessionId }) {
            val room = attribution.headroomVictims(sid)
            if (room == null || room < vs.size.toLong()) {
                val have: Long = room ?: -1L
                RecoveryTrace.log("quarantine_bracket_refused", "why" to "authority_headroom", "session" to sid.take(24),
                    "need" to vs.size, "have" to have)
                return refused("eviction_refused_authority_headroom", sid.take(24))
            }
        }

        // ── MARKER: cause QUARANTINED, no incoming record, a per-attempt nonce ──
        val sourceFp = EncryptedEventStore.logicalFingerprint(live)
        val targetFp = EncryptedEventStore.logicalFingerprint(keep.map { live[it] })
        val victimDigests = victims.map { it.c.digest }
        val nonce = randomNonce()
        val bracketId = BracketMarker.bracketIdOf(
            home.queueFile.name, sourceFp, targetFp, "", victimDigests, BracketMarker.CAUSE_QUARANTINED, nonce,
        )
        val m = BracketMarker.Marker(
            bracketId, home.queueFile.name, sourceFp, targetFp, "", victimDigests,
            BracketMarker.CAUSE_QUARANTINED, nowMillis(), nonce,
        )
        when (val w = marker.install(m)) {
            is BracketMarker.Write.Committed -> Unit
            is BracketMarker.Write.Refused -> return refused("bracket_marker_refused", w.reason)
            is BracketMarker.Write.NotDurable -> return refused("bracket_marker_not_durable", w.reason)
        }
        RecoveryTrace.log("quarantine_marker_installed", "bracket" to bracketId.take(12), "victims" to victims.size, "code" to (code ?: ""))
        faults("after_marker_install")
        return enact(bracketId, victims, keep, sourceFp, targetFp, null, BracketMarker.CAUSE_QUARANTINED)
    }

    /**
 * THE ONE ENACTMENT shared by both governed entries: PREPARE every victim,
 * ONE commit, SETTLE per the OBSERVED outcome. [record] is the incoming
 * record (byte eviction) or `null` (quarantine: retain-only); [cause] is
 * the cause the marker bound, and the ONLY thing that decides which
 * settlement writer runs.
 */
    private fun enact(bracketId: String, victims: List<Victim>, keep: List<Int>, sourceFp: String, targetFp: String, record: ByteArray?, cause: String): Result {
        // ── PREPARE: all victims, every session, before any queue byte changes ──
        val prepared = ArrayList<Victim>(victims.size)
        for ((k, v) in victims.withIndex()) {
            when (val o = attribution.prepare(v.sessionId, bracketId, v.sequence)) {
                is AttributionSink.Outcome.Recorded -> prepared.add(v)
                is AttributionSink.Outcome.Refused -> return abortBeforeCommit(bracketId, prepared, "eviction_prepare_refused", o.reason)
                is AttributionSink.Outcome.NotDurable -> return abortBeforeCommit(bracketId, prepared, "eviction_prepare_not_durable", o.reason)
                is AttributionSink.Outcome.NotWired -> return abortBeforeCommit(bracketId, prepared, "eviction_prepare_unwired", null)
            }
            faults("after_prepare:$k")
        }

        // ── COMMIT ──
        faults("before_commit")
        val c = commit(sourceFp, keep, record, targetFp)
        faults("after_commit")

        // ── SETTLE, per the OBSERVED outcome ──
        return when (c.outcome) {
            EncryptedEventStore.CompactOutcome.COMMITTED -> {
                // The queue image changed by a committed local mutation: the
                // Slice B no-progress counter is rearmed through its seam.
                home.protection().localProgress("bracket_commit")
                settleLost(bracketId, prepared, cause)
            }
            EncryptedEventStore.CompactOutcome.REFUSED_OLD_STATE_MISMATCH,
            EncryptedEventStore.CompactOutcome.REFUSED_UNTRUSTWORTHY_READ,
            EncryptedEventStore.CompactOutcome.REFUSED_OVER_CAPACITY,
            EncryptedEventStore.CompactOutcome.REFUSED_INVALID_PLAN,
            EncryptedEventStore.CompactOutcome.REFUSED_RECONCILIATION_BLOCKED,
            EncryptedEventStore.CompactOutcome.REFUSED_QUEUE_BUSY_CROSS_PROCESS,
            EncryptedEventStore.CompactOutcome.REFUSED_LOCK_INVARIANT,
            EncryptedEventStore.CompactOutcome.REFUSED_REGION_UNAVAILABLE,
            EncryptedEventStore.CompactOutcome.FAILED_OLD_RETAINED,
            -> abortBeforeCommit(bracketId, prepared, "eviction_commit_" + c.outcome.name.lowercase(), null)
            // UNKNOWABLE. The bytes may be the old image or the new one, and
            // may not survive a crash. Settling either way would be a guess;
            // the marker, the intents and the typed state stay for Slice D.
            EncryptedEventStore.CompactOutcome.AMBIGUOUS_POST_COMMIT_MISMATCH,
            EncryptedEventStore.CompactOutcome.INDETERMINATE_DIRECTORY_UNSYNCED,
            -> {
                RecoveryTrace.log("bracket_unknowable", "bracket" to bracketId.take(12), "why" to c.outcome.name)
                Result(true, "eviction_commit_" + c.outcome.name.lowercase(),
                    EncryptedEventStore.Durability.INDETERMINATE_DIRECTORY_UNSYNCED,
                    Outcome.Unknowable(bracketId, c.outcome.name))
            }
        }
    }

    /**
 * THE ONE PRODUCTION RECEIVER CALL OF `compactAndAppend` IN THE TREE. Reached
 * only from [enact], inside the BRACKET region, with the marker durable
 * and every victim's intent written; `record == null` is the retain-only
 * (quarantine) commit. The store re-validates the exact
 * source image itself (`REFUSED_OLD_STATE_MISMATCH`) and verifies the
 * target by read-back; this method adds nothing and decides nothing.
 */
    private fun commit(sourceFp: String, keep: List<Int>, record: ByteArray?, targetFp: String): EncryptedEventStore.CompactResult {
        val r = store.compactAndAppend(sourceFp, keep, record, targetFp)
        RecoveryTrace.log("bracket_commit", "outcome" to r.outcome.name, "removed" to r.removed, "kept" to r.kept)
        return r
    }

    /**
 * COMMITTED: every prepared victim is LOST under [cause] — `BYTE_EVICTED`
 * through the seam's eviction settlement, `QUARANTINED` (F4-P1) through its
 * quarantine settlement, and no third value — the marker leaves only when
 * every settlement is durable.
 */
    private fun settleLost(bracketId: String, prepared: List<Victim>, cause: String): Result {
        val reasons = ArrayList<String>()
        var settled = 0
        for ((k, v) in prepared.withIndex()) {
            val o = when (cause) {
                BracketMarker.CAUSE_BYTE_EVICTED -> attribution.settleLost(v.sessionId, bracketId, v.sequence)
                BracketMarker.CAUSE_QUARANTINED -> attribution.settleQuarantined(v.sessionId, bracketId, v.sequence)
                else -> AttributionSink.Outcome.Refused("unknown_bracket_cause:$cause")
            }
            when (o) {
                is AttributionSink.Outcome.Recorded -> settled++
                is AttributionSink.Outcome.Refused -> reasons.add(o.reason)
                is AttributionSink.Outcome.NotDurable -> reasons.add("not_durable:" + o.reason)
                is AttributionSink.Outcome.NotWired -> reasons.add("unwired")
            }
            faults("after_settle:$k")
        }
        if (reasons.isNotEmpty()) {
            RecoveryTrace.log("bracket_settle_incomplete", "bracket" to bracketId.take(12), "settled" to settled, "of" to prepared.size)
            return Result(true, "eviction_settle_incomplete", EncryptedEventStore.Durability.COMMITTED,
                Outcome.SettleIncomplete(bracketId, prepared.size, settled, reasons))
        }
        return when (val w = marker.remove(bracketId)) {
            is BracketMarker.Write.Committed -> {
                RecoveryTrace.log("bracket_evicted", "bracket" to bracketId.take(12), "victims" to prepared.size)
                Result(true, null, EncryptedEventStore.Durability.COMMITTED, Outcome.Evicted(bracketId, prepared.size))
            }
            is BracketMarker.Write.Refused, is BracketMarker.Write.NotDurable -> {
                val why = if (w is BracketMarker.Write.Refused) w.reason else (w as BracketMarker.Write.NotDurable).reason
                RecoveryTrace.log("bracket_marker_removal_failed", "bracket" to bracketId.take(12), "why" to why.take(40))
                Result(true, "eviction_marker_removal_failed", EncryptedEventStore.Durability.COMMITTED,
                    Outcome.SettleIncomplete(bracketId, prepared.size, settled, listOf("marker:$why")))
            }
        }
    }

    /**
 * The commit was PROVABLY not reached (a PREPARE refusal) or PROVABLY did
 * not happen (a typed store refusal / old-retained): every prepared victim
 * is settled DID_NOT_HAPPEN, and the marker is removed ONLY when every one
 * of those settlements is durable — the ruled cleanup ordering. A
 * settlement that cannot be made keeps the marker for Slice D.
 */
    private fun abortBeforeCommit(bracketId: String, prepared: List<Victim>, code: String, detail: String?): Result {
        var unsettled = 0
        for (v in prepared) {
            when (attribution.settleDidNotHappen(v.sessionId, bracketId, v.sequence)) {
                is AttributionSink.Outcome.Recorded -> Unit
                else -> unsettled++
            }
        }
        if (unsettled > 0) {
            RecoveryTrace.log("bracket_abort_unsettled", "bracket" to bracketId.take(12), "why" to code.take(40), "unsettled" to unsettled)
            return refused(code + "_unsettled", detail)
        }
        when (val w = marker.remove(bracketId)) {
            is BracketMarker.Write.Committed -> Unit
            else -> {
                RecoveryTrace.log("bracket_marker_removal_failed", "bracket" to bracketId.take(12), "why" to code.take(40))
                return refused(code + "_marker_kept", detail)
            }
        }
        RecoveryTrace.log("bracket_did_not_happen", "bracket" to bracketId.take(12), "why" to code.take(40), "prepared" to prepared.size)
        return refused(code, detail)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Every record of the live image, attributed through the removal door ONCE per session, then per record read-only. */
    private fun candidates(live: List<ByteArray>): List<EvictionPlanner.Candidate> {
        val rows = live.map { LossAuthority.RecordFields.parse(it) }
        val trustworthy = true   // the caller established it; an untrustworthy image never reaches here
        val doors = LinkedHashMap<String, AttributionSink.Adoption>()
        for (r in rows) {
            val sid = r?.sessionId ?: continue
            if (sid in doors) continue
            doors[sid] = attribution.resolve(sid, live, trustworthy)
        }
        return live.mapIndexed { i, bytes ->
            val digest = DeliveryProtection.sha256Hex(bytes)
            val frame = EncryptedEventStore.frameBytes(bytes.size)
            val r = rows[i]
            if (r == null) return@mapIndexed EvictionPlanner.Candidate(i, digest, frame, null, null, false, "unparseable")
            val why: String? = when (val d = doors.getValue(r.sessionId)) {
                is AttributionSink.Adoption.Ready, is AttributionSink.Adoption.NotWired -> null
                is AttributionSink.Adoption.NotDurable -> "door_not_durable:" + d.reason
                is AttributionSink.Adoption.Refused -> "door_refused:" + d.reason
            } ?: when (val e = attribution.eligibility(r.sessionId, r.sequence)) {
                is EvictionAttribution.Eligibility.Eligible -> null
                is EvictionAttribution.Eligibility.Ineligible -> e.reason
            }
            EvictionPlanner.Candidate(i, digest, frame, r.sessionId, r.sequence, why == null, why)
        }
    }

    /** Slice D (U-2): an outstanding or unreadable erasure request refuses every new append. */
    private fun erasureBlocks(): String? = if (home.erasure().presentOrUnreadable()) "erasure_requested" else null

    /** 64 random bits, hex: the per-attempt half of the bracket identity. */
    private fun randomNonce(): String {
        val b = ByteArray(8); java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun markerBlocks(): String? = when (val r = marker.read()) {
        is BracketMarker.Read.Unreadable -> "bracket_marker_unreadable"
        is BracketMarker.Read.Ok -> if (r.marker != null) "bracket_unresolved" else null
    }

    private fun plainResult(a: EncryptedEventStore.AppendResult): Result =
        Result(a.stored, a.reason, a.durability, Outcome.Plain(a))

    private fun refused(code: String, detail: String?): Result =
        Result(false, code, EncryptedEventStore.Durability.NOT_COMMITTED, Outcome.Refused(code, detail))

    private fun traceQueueRefusal(r: QueueRefusal) {
        RecoveryTrace.log("bracket_region_refused", "why" to r.wire, "waitedMs" to r.waitedMs)
    }
}

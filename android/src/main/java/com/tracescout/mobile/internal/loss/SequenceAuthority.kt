package com.tracescout.mobile.internal.loss

import com.tracescout.rn.LossFamilies

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. `public` only because Kotlin `internal` is
 * compilation-module scoped.
 */

/**
 * ONE SESSION'S SEQUENCE AND ATTRIBUTION AUTHORITY, AS A PURE STATE MACHINE.
 *
 * Two facts, one commit: the durable sequence high-water and the
 * retained `sequence → canonical family` index. This class decides; it owns no
 * file, no key, no clock and no lock. The `:sdk` store feeds it records in the
 * order they became durable — live appends and WAL replay go through the SAME
 * [validate] / [install] pair, so recovery cannot produce a shape the live
 * path cannot (the old branch's "ONE validator serves both" rule, salvaged).
 *
 * ## The invariants, each a test in `SequenceAuthorityTest`
 *
 * - **A sequence is allocated once and never reused.** `nextSequence` is
 * `highWater + 1`, and `highWater` moves in ONE direction. A LOST entry keeps
 * its number reserved; a DELIVERED entry is folded into the terminal
 * authority, which also remembers it. Nothing lowers the high-water — not
 * recovery, not compaction, not an empty queue.
 * - **The family is persisted at admission and never inferred later.** The
 * entry is RETAINED through ADMITTED (a phase transition, not a release —
 * D1-6) until DELIVERED or a terminal LOST.
 * - **DELIVERED and LOST are mutually exclusive for one sequence**, enforced in
 * [validate] before any byte is written, so a durable record can never
 * discover a contradiction it cannot resolve (the WAL-poison defect).
 * - **An admission's sequence is above the high-water, never on or below it.**
 * Adoption of a pre-F2 queue may leave gaps (records refused before an
 * authority existed) — a gap is honest; a collision is a corrupted timeline.
 * - **`disabled` is monotonic**, with ONE narrow exception ruled for F4-P2 (R11):
 * an OWNER-BOUND `OwnedDisable` (reason `ownerClearable`, today only
 * `UNATTRIBUTABLE_GAP`) may be cleared by an `OwnedDisableCleared` naming the
 * same owner. A legacy `CheckpointDisabled` is never cleared, and one arriving
 * after an owned disable replaces it (the monotonic fact wins).
 *
 * ## Phases
 *
 * admit → PREPARED ──settle ADMITTED──▶ ADMITTED ──DeliveredBatch──▶ DELIVERED
 * └────settle LOST(cause)──▶ LOST (ADMITTED may also become LOST:
 * a stored record later parked,
 * quarantined or evicted)
 *
 * The record kinds F3/F4/F5/F7 will write (`loss`, `evict`, `ack`, `terminal`)
 * are ACCEPTED here so the closed grammar replays whole (F-3); F2 has no
 * production writer for any of them, which a source scan pins.
 */
public class SequenceAuthority private constructor(
    public val sessionId: String,
    private var highWater: Long,
    private var walGeneration: Long,
    private var disabled: DisableReason?,
    /** F4-P2 (R11): the owner of an `OwnedDisable`; null for no disable or a legacy one. Snapshotted inside the disabled field. */
    private var disabledOwner: String?,
    private val owned: LinkedHashMap<Long, Entry>,
    private val intentToSequence: HashMap<String, Long>,
    private var terminal: TerminalAuthority,
    private val batches: LinkedHashMap<String, DeliveredBatchSnapshot>,
    /** Intent ids applied in THIS WAL generation — the live idempotency ledger. */
    private val appliedIntentIds: HashSet<String>,
    /** F3/F4 records carried so the grammar replays; no F2 writer. */
    private val lossEntries: ArrayList<LossRecord.LossRecorded>,
    private val unresolvedEvictions: LinkedHashMap<String, LossRecord.EvictionIntent>,
    private var ackThrough: Long,
    private var ackCounts: MutableMap<String, Long>,
    private var terminalRequested: Boolean,
    private var acknowledgedTerminal: Boolean,
    /**
 * THE DERIVED PENDING CHECKPOINT (F3-P0 the specification) — a requested checkpoint with
 * no settling ack. `null` means none.
 *
 * DERIVED, NOT A SECOND DURABLE FACT. The durable facts are the
 * `CheckpointRequested` record and the `AckApplied` that settles it; this
 * field is what those two records MEAN, projected so a restart can read it
 * without replaying from genesis.
 *
 * FROZEN MEANS FROZEN. No record kind may mutate its `frozenCounts` or its
 * `throughSequence` — a later `LossRecorded`, `AdmissionIntent`,
 * `IntentFinalized` or `EvictionIntent` changes the LIVE state and leaves the
 * frozen claim byte-identical. The natural implementation (recomputing counts
 * when they are read) would silently make a checkpoint claim something it
 * never sent, which is why this is a property with its own test and its own
 * negative control.
 */
    private var pendingCheckpoint: AuthorityCodec.PendingCheckpointRow?,
) {

    public enum class Phase { PREPARED, ADMITTED, DELIVERED, LOST }

    /** One owned sequence. Immutable non-content facts only. */
    public data class Entry(
        val sequence: Long,
        val intentId: String,
        val family: String,
        val phase: Phase,
        val cause: LossCause?,
    )

    public sealed class Validation {
        /** The intent id is already applied: a no-op, and not an error. */
        public object AlreadyApplied : Validation()
        public data class Refused(val reason: String) : Validation()
        /** Proven acceptable AGAINST THE CURRENT STATE; installation cannot fail. */
        public class Accepted internal constructor(
            public val record: LossRecord,
            internal val nextTerminal: TerminalAuthority?,
            internal val snapshot: DeliveredBatchSnapshot?,
        ) : Validation()
    }

    // ─────────────────────────────────────────────────────────────── reads

    public fun highWater(): Long = highWater
    public fun nextSequence(): Long = highWater + 1
    public fun walGeneration(): Long = walGeneration
    public fun disabled(): DisableReason? = disabled
    /** F4-P2 (R11): the durable owner of the current disable, or null (none, or a legacy monotonic disable). */
    public fun disabledOwner(): String? = disabledOwner
    public fun entry(sequence: Long): Entry? = owned[sequence]
    public fun entryByIntent(intentId: String): Entry? = intentToSequence[intentId]?.let { owned[it] }
    public fun ownedSequences(): Map<Long, Entry> = LinkedHashMap(owned)
    public fun terminal(): TerminalAuthority = terminal
    public fun isDelivered(sequence: Long): Boolean = terminal.isDelivered(sequence)
    public fun unresolvedAdmissions(): List<Long> = owned.values.filter { it.phase == Phase.PREPARED }.map { it.sequence }.sorted()
    public fun retainedLost(): List<Entry> = owned.values.filter { it.phase == Phase.LOST }
    public fun deliveredBatches(): Map<String, DeliveredBatchSnapshot> = LinkedHashMap(batches)
    public fun appliedCount(): Int = appliedIntentIds.size
    /** F4's territory, carried for grammar completeness. */
    public fun unresolvedEvictions(): List<Long> = unresolvedEvictions.values.map { it.logicalSequence }.sorted()

    /**
 * F4-P0 Slice D (D2) — the unresolved eviction intents BY IDENTITY:
 * `intentId -> logicalSequence`, in sequence order. A read, nothing more.
 *
 * Recovery needs the intent's identity, not only its sequence: an intent id
 * is derived from the bracket it belongs to, and a recovery deciding a
 * bracket must settle exactly that bracket's intents and leave another's
 * untouched. The sequence alone cannot say which bracket owns it.
 */
    public fun unresolvedEvictionIntents(): Map<String, Long> =
        unresolvedEvictions.values.sortedBy { it.logicalSequence }
            .associateTo(LinkedHashMap()) { it.intentId to it.logicalSequence }
    /**
 * The DERIVED pending checkpoint, or null. Returned as the persisted row
 * shape so a reader cannot recompute the frozen counts by accident — the
 * whole point of the specification's frozen rule is that nobody recomputes them.
 */
    public fun pendingCheckpoint(): AuthorityCodec.PendingCheckpointRow? = pendingCheckpoint

    /**
 * F6 — THE ACKNOWLEDGED AUTHORITY BOUNDARY AND THE COUNTS DECLARED AT IT.
 *
 * READS, NOTHING MORE. They exist because the F6 emitter must PROJECT a
 * `LossLedger.Ledger` value from durable state at call time (an explicit product decision
 * the specification) and both halves of `unacknowledgedLoss = total - acknowledgedTotal`
 * were unreachable: `ackThrough` / `ackCounts` were written into the
 * 14-field snapshot and had no getter, so the ONLY way to read them was to
 * build a snapshot — a WRITE-shaped call whose whole purpose is persistence.
 *
 * THE SNAPSHOT IS NOT WIDENED. the specification pins it at exactly 14 fields; these
 * are accessors over state the snapshot already carries, so an F6 projection
 * adds no durable field and no format version.
 *
 * [ackCounts] is COPIED. The map is the authority's own; handing the live
 * reference out would let a caller edit a declared boundary through it, and
 * "FROZEN MEANS FROZEN" is a property of the value, not of intent.
 */
    public fun ackThrough(): Long = ackThrough
    public fun ackCounts(): Map<String, Long> = LinkedHashMap(ackCounts)

    /**
 * Has a checkpoint claiming terminality been ACKNOWLEDGED by the server?
 *
 * DISTINCT FROM [terminalRequested], and the F6 emitter must not confuse
 * them: `terminalRequested` is the LOCAL durable claim (RULING R3, writer
 * policy only), this is the remote fact. `LossLedger.freeze` reads
 * `ledger.terminal`, which is the local claim — "durable before it may be
 * asserted" — so the projection feeds it [terminalRequested], and this
 * accessor exists for the ack-side reader that must not substitute one for
 * the other.
 */
    public fun acknowledgedTerminal(): Boolean = acknowledgedTerminal

    /**
 * THE LOCAL TERMINAL FLAG — a READ, and nothing more.
 *
 * RULING R3: the `TerminalRequested` PRECONDITION belongs in the writer/facade
 * ONLY, and core/replay validation must continue accepting `TerminalRequested`
 * UNCONDITIONALLY. This accessor exists so the WRITER can consult the flag as policy;
 * `validate` reads it for NO record kind, and a build in which it did would make
 * replay of an identical durable history produce different verdicts depending on the
 * order a flag was set — the one thing a replay must never do.
 *
 * It is also R3's LOAD-BEARING TWIN: *"validate does not read it"* is equally true of
 * a build where nothing writes it at all, so the flag has to be genuinely set and
 * genuinely readable for the absence to mean anything.
 */
    public fun terminalRequested(): Boolean = terminalRequested

    // ────────────────────────────────────────────────────────── transitions

    /**
 * **F4-P1 (Q23) — A SECOND FINALIZATION OF THE SAME EVICTION INTENT UNDER
 * ANOTHER CAUSE IS A CONFLICT, NEVER "ALREADY APPLIED".** Both LOST writers of
 * the facade derive the record id `<intent>-lost` — the id carries the OUTCOME
 * and not the cause — so a `BYTE_EVICTED` finalization arriving after the
 * intent was settled `QUARANTINED` (or vice versa) used to be absorbed by the
 * applied-id check above and answered `AlreadyApplied`: a writer that
 * DISAGREED with the durable cause was told its write had landed. The durable
 * entry was never changed (one finalization), but the disagreement was
 * laundered. The lookup is a PROCESS-LOCAL map populated at install, kept
 * SEPARATE from [intentToSequence] on purpose: putting a resolved eviction
 * intent into that map would let a stale `evictionLost` AFTER a
 * `DID_NOT_HAPPEN` find an ADMITTED entry and settle it LOST without a fresh
 * PREPARE — today that write is refused `unknown_admission`, and it must stay
 * refused. After a compaction the map is empty for pre-compaction intents and
 * the same conflicting write is refused `unknown_admission` by the ordinary
 * branch instead (RF4-15 defers compaction while an intent is unresolved, so
 * no settlement retry can straddle one): refused either way, typed here.
 */
    private val resolvedEvictionSequence = HashMap<String, Long>()

    private fun finalizedUnderOtherCause(r: LossRecord): Validation? {
        if (r !is LossRecord.IntentFinalized || r.outcome != LossRecord.IntentFinalized.Outcome.LOST) return null
        val seq = resolvedEvictionSequence[r.resolvesIntentId] ?: return null
        val e = owned[seq] ?: return null
        if (e.phase == Phase.LOST && e.cause != r.cause) return Validation.Refused("finalized_under_other_cause:${e.cause}")
        return null
    }

    /**
 * PURE. Decides whether [r] may be applied and precomputes any terminal
 * transition. Mutates nothing. Every fallible business rule lives here,
 * BEFORE durability.
 */
    public fun validate(r: LossRecord): Validation {
        if (appliedIntentIds.contains(r.intentId)) {
            checkpointReRequestConflict(r)?.let { return it }
            finalizedUnderOtherCause(r)?.let { return it }
            return Validation.AlreadyApplied
        }
        return when (r) {
            is LossRecord.AdmissionIntent -> {
                if (!LossFamilies.ALL.contains(r.family)) return Validation.Refused("family_not_canonical")
                if (r.logicalSequence < 0) return Validation.Refused("negative_sequence")
                // STRICTLY ABOVE THE HIGH-WATER. Equal would reuse the number
                // the previous admission owns; below would rewrite history.
                if (r.logicalSequence <= highWater) return Validation.Refused("sequence_not_above_high_water")
                if (owned.containsKey(r.logicalSequence)) return Validation.Refused("sequence_already_owned")
                if (terminal.isDelivered(r.logicalSequence)) return Validation.Refused("sequence_already_delivered")
                Validation.Accepted(r, null, null)
            }
            is LossRecord.IntentFinalized -> {
                if ((r.outcome == LossRecord.IntentFinalized.Outcome.LOST) != (r.cause != null))
                    return Validation.Refused("final_cause_mismatch")
                if (r.outcome == LossRecord.IntentFinalized.Outcome.DELIVERED)
                    return Validation.Refused("delivery_is_not_settled_here")
                val ev = unresolvedEvictions[r.resolvesIntentId]
                if (ev != null) {
                    // F4's bracket resolving an eviction intent — grammar carried, exclusivity kept.
                    if (r.outcome == LossRecord.IntentFinalized.Outcome.LOST && terminal.isDelivered(ev.logicalSequence))
                        return Validation.Refused("eviction_conflicts_with_delivered:${ev.logicalSequence}")
                    return Validation.Accepted(r, null, null)
                }
                val seq = intentToSequence[r.resolvesIntentId] ?: return Validation.Refused("unknown_admission")
                val e = owned[seq] ?: return Validation.Refused("unknown_admission")
                when (r.outcome) {
                    LossRecord.IntentFinalized.Outcome.ADMITTED ->
                        if (e.phase != Phase.PREPARED) return Validation.Refused("already_settled:${e.phase}")
                    LossRecord.IntentFinalized.Outcome.LOST -> {
                        if (e.phase == Phase.DELIVERED || terminal.isDelivered(seq)) return Validation.Refused("already_delivered")
                        if (e.phase == Phase.LOST) return Validation.Refused("already_settled:LOST")
                    }
                    LossRecord.IntentFinalized.Outcome.DELIVERED -> return Validation.Refused("delivery_is_not_settled_here")
                }
                Validation.Accepted(r, null, null)
            }
            is LossRecord.DeliveredBatch -> {
                val why = validateDeliveredBatch(r)
                if (why != null) return if (why.isEmpty()) Validation.AlreadyApplied else Validation.Refused(why)
                val snap = DeliveredBatchSnapshot(r.intentId, r.sessionId, r.chunkId, r.recordCount, r.contentSha256, r.sequenceEncoding)
                val next = try { terminal.withBatch(snap) } catch (e: LossFormatException) { return Validation.Refused(e.reason) }
                Validation.Accepted(r, next, snap)
            }
            is LossRecord.HighWaterAdvanced -> {
                if (r.highWater < highWater) return Validation.Refused("high_water_would_lower")
                Validation.Accepted(r, null, null)
            }
            is LossRecord.CheckpointDisabled -> Validation.Accepted(r, null, null)
            // F4-P2 (R11) — the owner-bound disable: a clearable reason only, and never
            // layered on an existing disable (so ownership can never be ambiguous).
            is LossRecord.OwnedDisable -> {
                if (!r.reason.ownerClearable()) return Validation.Refused("disable_reason_not_owner_bindable:${r.reason.name}")
                if (r.ownerId.isEmpty()) return Validation.Refused("disable_owner_empty")
                if (disabled != null) return Validation.Refused("already_disabled:${disabled!!.name}")
                Validation.Accepted(r, null, null)
            }
            // F4-P2 (R11) — the ONE clear: only the exact current owner-bound disable.
            is LossRecord.OwnedDisableCleared -> {
                val d = disabled ?: return Validation.Refused("not_disabled")
                val o = disabledOwner ?: return Validation.Refused("disable_not_owner_bound:${d.name}")
                if (o != r.ownerId) return Validation.Refused("disable_owner_mismatch")
                Validation.Accepted(r, null, null)
            }
            is LossRecord.EvictionIntent -> {
                if (!LossFamilies.ALL.contains(r.family)) return Validation.Refused("family_not_canonical")
                if (terminal.isDelivered(r.logicalSequence)) return Validation.Refused("eviction_conflicts_with_delivered:${r.logicalSequence}")
                // F4 C4 — THE AUTHORITY DERIVES THE FAMILY; THE CALLER INVENTS NOTHING.
                // An intent naming a family other than the one the owned entry was
                // admitted under is refused here, at the authority, so a caller that
                // supplies its own family is refused rather than trusted.
                owned[r.logicalSequence]?.let { e ->
                    if (e.family != r.family) return Validation.Refused("eviction_family_mismatch:${e.family}")
                }
                Validation.Accepted(r, null, null)
            }
            is LossRecord.LossRecorded -> {
                if (!LossFamilies.ALL.contains(r.family)) return Validation.Refused("family_not_canonical")
                Validation.Accepted(r, null, null)
            }
            is LossRecord.DeliveredMarker ->
                // RETIRED, AND DELIBERATELY NOT REINTERPRETED AS A BATCH OF ONE.
                Validation.Refused("unsupported_record:delivered_marker")
            is LossRecord.AckApplied -> {
                if (r.throughSequence < ackThrough) return Validation.Refused("ack_would_regress")
                for (f in r.counts.keys) if (!LossFamilies.ALL.contains(f)) return Validation.Refused("family_not_canonical")
                // ONLY A **BOUND** ACK SETTLES. Under v1 this
                // record carried no checkpoint identity, so there was nothing to
                // bind to and "the only thing that may clear pending loss is a
                // validated ack bound to the exact checkpoint it answers" was
                // unimplementable. the specification I4/I5 are the mandatory pair: the positive
                // row passes against a build that settles on ANY ack, and the
                // negative row passes against one that settles NOTHING AT ALL, so
                // neither is evidence alone.
                val p = pendingCheckpoint ?: return Validation.Refused("ack_unbound_checkpoint")
                if (p.checkpointId != r.settlesCheckpointId) return Validation.Refused("ack_unbound_checkpoint")
                // AGAINST THE **FROZEN** CLAIM, never against the live state.
                // An ack that answers a checkpoint must agree with what that
                // checkpoint actually SENT; comparing it to today's counts would
                // accept an ack for a claim nobody made.
                if (r.throughSequence != p.throughSequence) return Validation.Refused("ack_contradicts_frozen_checkpoint")
                if (LossCounts.encode(r.counts) != LossCounts.encode(p.frozenCounts))
                    return Validation.Refused("ack_contradicts_frozen_checkpoint")
                Validation.Accepted(r, null, null)
            }
            is LossRecord.CheckpointRequested -> {
                // D2: A DISABLED SESSION EMITS NOTHING, SO IT MAY NOT RECORD A
                // CLAIM EITHER. Under F3-P0 a disabled session also cannot ADMIT
                // (RULING A2, the specification), so this refusal is no longer the only thing
                // standing between a disabled session and a durable claim — it is
                // kept because a claim and an allocation are different acts and
                // each is refused on its own terms.
                if (disabled != null) return Validation.Refused("checkpoint_on_disabled_session")
                if (pendingCheckpoint != null) return Validation.Refused("checkpoint_already_pending")
                if (r.throughSequence < ackThrough) return Validation.Refused("checkpoint_would_regress")
                // A claim about numbers that do not exist.
                if (r.throughSequence > highWater) return Validation.Refused("checkpoint_beyond_high_water")
                for (f in r.frozenCounts.keys) if (!LossFamilies.ALL.contains(f)) return Validation.Refused("family_not_canonical")
                Validation.Accepted(r, null, null)
            }
            is LossRecord.TerminalRequested -> Validation.Accepted(r, null, null)
        }
    }

    /**
 * RULING R2 — **THE ONE SEMANTIC RULE, AND IT LIVES HERE.**
 *
 * > same `throughSequence` with different canonical counts is ONE semantic rule in
 * > core. F3-P1 writer responsibility is ORDERING ONLY: REFUSE BEFORE APPEND. No
 * > facade duplicate.
 *
 * THE HAZARD, AND WHY THE SHORT-CIRCUIT ABOVE IS NOT THE DEFECT. Idempotency by
 * `intentId` is correct and accepted, and `AlreadyApplied` is the right answer for a
 * genuinely identical re-request. But it is the FIRST statement of [validate], so for
 * a `CheckpointRequested` whose `intentId` has already been applied, none of the
 * per-kind rules is reached — and a writer that re-requests with the same idempotency
 * key and RECOMPUTED counts is told it succeeded while the durable record still holds
 * the ORIGINAL frozen counts. Because the specification makes an ack compare against the FROZEN
 * claim, an ack agreeing with the caller's SECOND counts is then refused
 * `ack_contradicts_frozen_checkpoint` for a reason the caller cannot see: a correct
 * refusal, arriving as an inexplicable one, about a claim nobody made.
 *
 * WHY `CheckpointRequested` IS THE ONLY EXPOSED KIND. `DeliveredBatch`'s identity
 * IS its content — `:259` refuses `delivered_batch_marker_not_reproducible` unless
 * `DeliveredMarkerId.of(.) == intentId`, so different content ⇒ different
 * `intentId` ⇒ the short-circuit never fires on a conflict. A checkpoint's `intentId`
 * is a CALLER-SUPPLIED idempotency key, not a content address. The exposure is
 * the consequence of one kind having a content-addressed identity and the other not.
 *
 * THE COMPARISON USES THE ACCEPTED CANONICAL ENCODING — the same
 * `LossCounts.encode` `:223` already compares an ack with — so two layers cannot
 * disagree about what "different counts" means.
 *
 * IT IS BOUND TO THE PENDING ROW THIS REQUEST INSTALLED, and that gating is
 * STRUCTURALLY NECESSARY rather than a preference: `install` projects a
 * `CheckpointRequested` into `pendingCheckpoint`, and that row is the ONLY durable
 * projection of the applied request (adding a second durable record of it is out of
 * this stage's scope). After an `AckApplied` settles a claim the row is cleared and a
 * LATER claim may occupy it, so comparing an old `intentId`'s counts against whatever
 * happens to be pending now would compare against a claim the caller never made —
 * a false refusal or a false accept. `checkpointId` is the link that makes the
 * comparison about the same claim.
 *
 * DISCLOSED BOUND, NOT SILENTLY NARROWED. Two re-request shapes therefore still
 * answer `AlreadyApplied`: one whose claim has already been SETTLED (there is no
 * frozen claim left to contradict, and a new claim needs a new key), and one carrying
 * a DIFFERENT `checkpointId` under the same key. Both are outside R2's stated subject
 * same `throughSequence`, different canonical counts — and widening to them would
 * be a second semantic decision this stage is not authorized to add.
 *
 * A differing `throughSequence` is likewise outside R2's narrow subject: the accepted
 * rules already cover it, and R2 names the SAME-`throughSequence` case precisely
 * because that is the one a caller reads as harmless.
 */
    private fun checkpointReRequestConflict(r: LossRecord): Validation? {
        if (r !is LossRecord.CheckpointRequested) return null
        val p = pendingCheckpoint ?: return null
        if (p.checkpointId != r.checkpointId) return null
        if (p.throughSequence != r.throughSequence) return null
        if (LossCounts.encode(p.frozenCounts) == LossCounts.encode(r.frozenCounts)) return null
        return Validation.Refused("checkpoint_contradicts_frozen_claim")
    }

    /**
 * Pure. `""` = the identical batch is already applied; null = may commit.
 * Every sequence must be OWNED and not LOST — delivery of a sequence the
 * authority never admitted is refused rather than adopted, and delivery of
 * a sequence already settled LOST is a contradiction (exclusivity).
 */
    private fun validateDeliveredBatch(r: LossRecord.DeliveredBatch): String? {
        if (r.sessionId != sessionId) return "delivered_batch_foreign_session"
        val seqs = try { DeliveredSeqCodec.decode(r.sequenceEncoding, r.recordCount) } catch (e: LossFormatException) { return e.reason }
        val recomputed = try {
            DeliveredMarkerId.of(r.sessionId, r.chunkId, r.recordCount,
                DeliveredBatchFields.hexToBytes(r.contentSha256), r.sequenceEncoding)
        } catch (e: LossFormatException) { return e.reason }
        if (recomputed != r.intentId) return "delivered_batch_marker_not_reproducible"
        val existing = batches[r.intentId]
        if (existing != null) {
            val same = existing.sessionId == r.sessionId && existing.chunkId == r.chunkId &&
                existing.recordCount == r.recordCount && existing.contentSha256 == r.contentSha256 &&
                existing.sequenceEncoding.contentEquals(r.sequenceEncoding)
            return if (same) "" else "delivered_batch_marker_contents_conflict"
        }
        for (q in seqs) {
            if (q < 0) return "delivered_batch_negative_sequence"
            if (terminal.isDelivered(q)) return "delivered_sequence_claimed_twice:$q"
            val e = owned[q] ?: return "delivered_unowned_sequence:$q"
            if (e.phase == Phase.LOST) return "delivered_conflicts_with_lost:$q"
        }
        return null
    }

    /** INFALLIBLE. Installs an already-validated record. No rule can say no here. */
    public fun install(v: Validation.Accepted) {
        val r = v.record
        appliedIntentIds.add(r.intentId)
        when (r) {
            is LossRecord.AdmissionIntent -> {
                owned[r.logicalSequence] = Entry(r.logicalSequence, r.intentId, r.family, Phase.PREPARED, null)
                intentToSequence[r.intentId] = r.logicalSequence
                if (r.logicalSequence > highWater) highWater = r.logicalSequence
            }
            is LossRecord.IntentFinalized -> {
                val ev = unresolvedEvictions.remove(r.resolvesIntentId)
                if (ev != null) {
                    resolvedEvictionSequence[ev.intentId] = ev.logicalSequence
                    if (r.outcome == LossRecord.IntentFinalized.Outcome.LOST) {
                        lossEntries.add(LossRecord.LossRecorded(ev.intentId, ev.logicalSequence, ev.family, ev.count))
                        owned[ev.logicalSequence]?.let { owned[ev.logicalSequence] = it.copy(phase = Phase.LOST, cause = r.cause) }
                    }
                } else {
                    val seq = intentToSequence[r.resolvesIntentId]!!
                    val e = owned[seq]!!
                    owned[seq] = when (r.outcome) {
                        LossRecord.IntentFinalized.Outcome.ADMITTED -> e.copy(phase = Phase.ADMITTED)
                        LossRecord.IntentFinalized.Outcome.LOST -> e.copy(phase = Phase.LOST, cause = r.cause)
                        LossRecord.IntentFinalized.Outcome.DELIVERED -> e // unreachable: refused in validate
                    }
                }
            }
            is LossRecord.DeliveredBatch -> {
                v.snapshot?.let { batches[r.intentId] = it }
                val seqs = DeliveredSeqCodec.decode(r.sequenceEncoding, r.recordCount)
                for (q in seqs) owned[q]?.let { owned[q] = it.copy(phase = Phase.DELIVERED) }
            }
            is LossRecord.HighWaterAdvanced -> if (r.highWater > highWater) highWater = r.highWater
            // Legacy, monotonic: set once; it also REPLACES an owner-bound disable (R11 —
            // the stronger fact wins and the owner is dropped, so the clear then refuses).
            is LossRecord.CheckpointDisabled -> if (disabled == null || disabledOwner != null) { disabled = r.reason; disabledOwner = null }
            is LossRecord.OwnedDisable -> { disabled = r.reason; disabledOwner = r.ownerId }
            // Clears the disable and NOTHING else: no high-water, no entry, no batch, no sequence.
            is LossRecord.OwnedDisableCleared -> { disabled = null; disabledOwner = null }
            is LossRecord.EvictionIntent -> unresolvedEvictions[r.intentId] = r
            is LossRecord.LossRecorded -> lossEntries.add(r)
            is LossRecord.DeliveredMarker -> Unit // unreachable: refused in validate
            is LossRecord.AckApplied -> {
                if (r.throughSequence >= ackThrough) {
                    ackThrough = r.throughSequence
                    ackCounts = LossCounts.zero().also { m -> r.counts.forEach { m[it.key] = it.value } }
                    if (r.acknowledgedTerminal) acknowledgedTerminal = true
                }
                // CLEARS THE PENDING CHECKPOINT **ONLY** WHEN IT SETTLES THAT
                // EXACT ID. `validate` has already proven the binding, so this is
                // an equality check and not a second authority on the question.
                if (pendingCheckpoint?.checkpointId == r.settlesCheckpointId) pendingCheckpoint = null
            }
            is LossRecord.TerminalRequested -> terminalRequested = true
            is LossRecord.CheckpointRequested ->
                // THE FROZEN QUADRUPLE IS STORED AS IT WAS SENT. Nothing here
                // reads the live counts, and no other `install` arm touches this
                // field — which is what makes "frozen" a structural property
                // rather than a convention.
                pendingCheckpoint = AuthorityCodec.PendingCheckpointRow(
                    r.checkpointId, r.throughSequence, LinkedHashMap(r.frozenCounts), r.claimsTerminal,
                )
        }
        // ONE assignment of a COMPLETE generation, precomputed at validation.
        v.nextTerminal?.let { terminal = it }
    }

    /** Replay path: validate then install, same rules as a live append. Returns a refusal reason, else null. */
    public fun apply(r: LossRecord): String? = when (val v = validate(r)) {
        is Validation.AlreadyApplied -> null
        is Validation.Refused -> v.reason
        is Validation.Accepted -> { install(v); null }
    }

    // ───────────────────────────────────────────────────────────── snapshot

    /**
 * The compaction baseline for the NEXT generation.
 *
 * DELIVERED entries are folded out of the owned map (the terminal authority
 * remembers every delivered sequence, and the derived index is rebuilt on
 * load); PREPARED, ADMITTED and LOST entries are retained — LOST stays until
 * F5's settlement, because "a permanent removal never frees a number" is a
 * fact this snapshot must still be able to state after a restart.
 */
    public fun snapshot(nextGeneration: Long): AuthorityCodec.AuthoritySnapshot = AuthorityCodec.AuthoritySnapshot(
        sessionId = sessionId,
        highWater = highWater,
        walGeneration = nextGeneration,
        disabled = disabled,
        disabledOwner = disabledOwner,
        entries = owned.values.filter { it.phase != Phase.DELIVERED }
            .map { AuthorityCodec.EntryRow(it.sequence, it.intentId, it.family, it.phase.name, it.cause) },
        deliveredBatches = batches.values.toList(),
        ackThrough = ackThrough,
        ackCounts = LinkedHashMap(ackCounts),
        terminalRequested = terminalRequested,
        acknowledgedTerminal = acknowledgedTerminal,
        // SURVIVES COMPACTION. Dropping it here is I2's negative
        // control: a pending checkpoint that vanishes at a compaction would let a
        // later ack settle nothing and a later request claim the same interval.
        pending = pendingCheckpoint?.let { AuthorityCodec.PendingCheckpointRow(it.checkpointId, it.throughSequence, LinkedHashMap(it.frozenCounts), it.claimsTerminal) },
    )

    /**
 * THE LOGICAL FINGERPRINT compaction verification compares (F3-P0 the specification step 3,
 * the specification).
 *
 * **DERIVED FROM THE REBUILT MACHINE, NEVER FROM THE BYTES.** Comparing the
 * bytes to themselves proves the file was WRITTEN, not that it DECODES to the
 * state we meant — and the `compactAndAppend` precedent in the queue compares a
 * *logical* fingerprint for exactly this reason. the specification E3's negative control is
 * "compare bytes to themselves".
 *
 * **`walGeneration` IS DELIBERATELY EXCLUDED.** The two states being compared
 * legitimately differ there: `SNAP[g+1]` names generation `g+1` while the
 * machine rebuilt from `SNAP[g] + WAL[g]` is at `g`. Including it would make
 * every comparison fail and IC2 would never complete an interrupted compaction
 * the permanently-stuck-compaction failure the specification exists to avoid.
 *
 * It is a digest over the machine's OWN READS, in a fixed order, so a codec
 * change cannot make two different states compare equal and a field added to the
 * persisted grammar does not silently drop out of the comparison.
 */
    public fun logicalFingerprint(): String {
        val sb = StringBuilder()
        sb.append("TSAFP2\u0000").append(sessionId).append('\u0000')
        sb.append(highWater).append('\u0000')
        sb.append(disabled?.name ?: "").append('\u0000')
        sb.append(disabledOwner ?: "").append('\u0000')
        // A THE SAME PROJECTION `snapshot` PERSISTS, and this cost a real defect.
        // `snapshot` deliberately drops a DELIVERED entry -- its sequence is accounted
        // by the batch whose `markerId` binds the encoded set, and keeping its intentId
        // and family would be a SECOND durable fact about a settled record. So a
        // DELIVERED entry lives in `owned` before a compaction and is gone after the
        // reopen, and reading it here made E.2 step 3 compare two states the format
        // never claimed were equal: the read-back reported
        // `AMBIGUOUS_POST_COMMIT_SNAPSHOT_MISMATCH` for a snapshot that had decoded
        // BYTE-FOR-BYTE to the intended value (measured -- the two AuthoritySnapshot
        // objects were identical field for field while the fingerprints differed).
        // Nothing else is excluded, so a snapshot that is lossy about anything the
        // format DOES persist still fails the read-back.
        for (e in owned.values.filter { it.phase != Phase.DELIVERED }.sortedBy { it.sequence }) {
            sb.append(e.sequence).append(':').append(e.intentId).append(':').append(e.family)
                .append(':').append(e.phase.name).append(':').append(e.cause?.name ?: "").append(',')
        }
        sb.append('\u0000')
        for (b in batches.values.sortedBy { it.markerId }) {
            sb.append(b.markerId).append(':').append(b.chunkId).append(':').append(b.recordCount)
                .append(':').append(b.contentSha256).append(',')
        }
        sb.append('\u0000')
        sb.append(ackThrough).append('\u0000').append(LossCounts.encode(ackCounts)).append('\u0000')
        sb.append(terminalRequested).append('\u0000').append(acknowledgedTerminal).append('\u0000')
        val p = pendingCheckpoint
        sb.append(p?.checkpointId ?: "").append(':').append(p?.throughSequence ?: -1L).append(':')
            .append(if (p == null) "" else LossCounts.encode(p.frozenCounts)).append(':')
            .append(p?.claimsTerminal ?: false)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return AuthorityContainer.hex(md.digest(sb.toString().toByteArray(Charsets.UTF_8)))
    }

    public companion object {

        /** A session that has never allocated: high-water −1, so the first admission is sequence 0. */
        public fun genesis(sessionId: String): SequenceAuthority = genesisAbove(sessionId, -1L)

        /**
 * A session with NO retained history but a KNOWN durable floor — the
 * audit addendum's RULING A1 shape: the authority directory was pruned,
 * the durable floor survived it, and this session id has been seen
 * before, so its first admission must be `floor + 1` and never 0.
 *
 * There are no entries and no batches: the floor is a statement about
 * ALLOCATION ONLY. It says "these numbers are spent", never "these
 * records were delivered" — inventing entries from a floor would
 * manufacture attribution nobody measured.
 */
        public fun genesisAbove(sessionId: String, floor: Long): SequenceAuthority {
            if (floor < -1L) throw LossFormatException("floor_below_genesis:$floor")
            return SequenceAuthority(
                sessionId, floor, 0L, null, null, LinkedHashMap(), HashMap(), TerminalAuthority.empty(),
                LinkedHashMap(), HashSet(), ArrayList(), LinkedHashMap(), -1L, LossCounts.zero(), false, false,
                null,
            )
        }

        /**
 * Rebuild from a compacted snapshot — ALL-OR-NOTHING, validated before
 * anything is installed. A snapshot whose batches contradict its
 * entries, whose marker ids do not recompute, or whose phase names are
 * unknown is REFUSED whole; a half-loaded authority is a shape no later
 * check could distinguish from a real one.
 */
        public fun fromSnapshot(s: AuthorityCodec.AuthoritySnapshot): SequenceAuthority {
            val owned = LinkedHashMap<Long, Entry>()
            val i2s = HashMap<String, Long>()
            for (row in s.entries) {
                val phase = runCatching { Phase.valueOf(row.phase) }.getOrElse { throw LossFormatException("unknown_phase:${row.phase}") }
                if (phase == Phase.DELIVERED) throw LossFormatException("snapshot_entry_delivered_not_folded")
                if ((phase == Phase.LOST) != (row.cause != null)) throw LossFormatException("snapshot_entry_cause_mismatch")
                if (owned.put(row.sequence, Entry(row.sequence, row.intentId, row.family, phase, row.cause)) != null)
                    throw LossFormatException("snapshot_sequence_claimed_twice:${row.sequence}")
                if (i2s.put(row.intentId, row.sequence) != null) throw LossFormatException("snapshot_intent_claimed_twice")
                if (row.sequence > s.highWater) throw LossFormatException("snapshot_entry_above_high_water")
            }
            val batches = LinkedHashMap<String, DeliveredBatchSnapshot>()
            for (b in s.deliveredBatches) {
                if (b.sessionId != s.sessionId) throw LossFormatException("delivered_batch_foreign_session")
                if (b.recomputedMarkerId() != b.markerId) throw LossFormatException("delivered_batch_marker_not_reproducible")
                batches[b.markerId] = b
            }
            // TerminalAuthority.of derives the sequence index and REFUSES a sequence
            // claimed by two markers or both delivered and lost.
            val lost = owned.values.filter { it.phase == Phase.LOST }.map { it.sequence }.toSet()
            val terminal = TerminalAuthority.of(batches, lost)
            for (q in terminal.deliveredSequences) {
                if (q > s.highWater) throw LossFormatException("snapshot_delivered_above_high_water")
                if (owned.containsKey(q)) throw LossFormatException("snapshot_delivered_and_retained:$q")
            }
            // THE PENDING ROW IS VALIDATED AGAINST THE STATE IT CLAIMS, as
            // part of the SAME all-or-nothing load. The codec has already enforced
            // the all-or-none SHAPE (`snapshot_pending_checkpoint_partial`); what
            // it cannot see is whether the claim is consistent with the high-water
            // and the acknowledged floor in the same snapshot, and a pending
            // checkpoint claiming numbers that do not exist is a shape no later
            // check could distinguish from a real one.
            // F4-P2 (R11): an owner may only ride on an owner-clearable disable (the codec
            // refuses the wire shape; this refuses the in-memory one, all-or-nothing).
            if (s.disabledOwner != null && (s.disabled == null || !s.disabled.ownerClearable()))
                throw LossFormatException("snapshot_owner_on_monotonic_reason")
            s.pending?.let { p ->
                if (p.throughSequence > s.highWater) throw LossFormatException("snapshot_pending_beyond_high_water")
                if (p.throughSequence < s.ackThrough) throw LossFormatException("snapshot_pending_below_ack_through")
                for (f in p.frozenCounts.keys) LossCounts.requireCanonical(f)
            }
            return SequenceAuthority(
                s.sessionId, s.highWater, s.walGeneration, s.disabled, s.disabledOwner, owned, i2s, terminal, batches, HashSet(),
                ArrayList(), LinkedHashMap(), s.ackThrough, LinkedHashMap(s.ackCounts), s.terminalRequested, s.acknowledgedTerminal,
                s.pending?.let { AuthorityCodec.PendingCheckpointRow(it.checkpointId, it.throughSequence, LinkedHashMap(it.frozenCounts), it.claimsTerminal) },
            )
        }
    }
}

package com.tracescout.mobile.internal.loss

import androidx.annotation.RestrictTo
import com.tracescout.mobile.queue.DurableReplace
import com.tracescout.mobile.queue.GcmFrame
import com.tracescout.mobile.queue.RecoveryTrace
import com.tracescout.mobile.queue.StoreKeys
// F6: the PURE model only — projected at call time, never stored. See projectLedger.
import com.tracescout.rn.LossLedger
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * THE SEQUENCE AND ATTRIBUTION AUTHORITY, AS THE PRODUCERS SEE IT (F2).
 *
 * A NARROW FACADE, NOT A SECOND IMPLEMENTATION (salvaged shape,
 * BRANCH_SALVAGE the specification `LossAuthority`). Every rule lives in the pure
 * `SequenceAuthority`; this exists because the store is `internal` to `:sdk`
 * and `:rn` is a different compilation module. Nothing here decides anything.
 *
 * NO CORE TYPE CROSSES THIS SURFACE. Tickets, admissions,
 * settlements and causes are the facade's own types; `LossCause` is mapped
 * INSIDE. The only values that go in are a session id, an event id, an event
 * TYPE, sequences and digests; the only values that come out are a sequence,
 * a canonical family and typed states. No content, ever.
 *
 * ## ONE handle per session per process
 *
 * `open` returns the process-wide handle for `(filesDir, sessionId)`; the
 * native producer, the RN transport and the upload pipeline's attribution seam
 * all reach the SAME instance, which is what makes "two writers cannot create
 * split authority" a property of construction rather than of lock discipline:
 * the store's single monitor serializes every append.
 *
 * ## Fail closed, never a local counter
 *
 * An `Unavailable` open, a fenced handle or a refused admission all mean the
 * event is NOT captured for that session — no number is issued, nothing is
 * stored, and the refusal is traced. Falling back to an in-memory counter
 * would create two authorities that disagree exactly once, silently, on a
 * device, months later.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object LossAuthority {

    /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5) — the canonical
 * `eventType -> lossFamily` answer, for the ONE caller in `:sdk` that is
 * not an admission: the in-memory queue-pressure planner.
 *
 * WHY IT IS HERE AND NOT IN THE PLANNER. `LossVocabularyTest` pins that
 * **this file is the only importer of `com.tracescout.rn.LossFamilies` in
 * `:sdk`** — *"a second consumer is how a second opinion starts"* — and
 * that pin is right: two call sites are two places a fallback could later
 * be added. This is a PASS-THROUGH to the one sanctioned mapper (V5), not
 * a second mapping: it adds no branch, no default and no table.
 *
 * IT RETURNS null FOR AN UNKNOWN TYPE, exactly like the mapper it
 * forwards to. A guessed family is a fabricated attribution, so the planner
 * must refuse the eviction rather than destroy a record it cannot name.
 */
    @JvmStatic
    public fun canonicalFamilyOrNull(eventType: String?): String? =
        com.tracescout.rn.LossFamilies.lossFamilyForEventType(eventType)

    /** A durable claim on one sequence. Immutable non-content facts only. */
    public data class Ticket(
        val intentId: String,
        val sessionId: String,
        val eventId: String,
        val eventType: String,
        val family: String,
        val sequence: Long,
    )

    public sealed class Admission {
        public data class Owned(val ticket: Ticket) : Admission()
        /** Refused BEFORE ownership: nothing claimed, nothing written. Not loss. */
        public data class Refused(val reason: String) : Admission()
        /** The durable write may or may not have landed; ownership is NOT claimed. The handle is fenced. */
        public data class NotDurable(val reason: String) : Admission()
    }

    /**
 * F2's writable causes — EXACTLY TWO. The authority's full closed
 * vocabulary lives in the core; F2 may construct a LOST fact only where it
 * owns a positively committed terminal-loss fact: a refused append (the
 * record never existed) and a DURABLE park commit (a parked slice is never
 * automatically retried).
 *
 * `QUARANTINED` IS NOT HERE, BY RULING (2026-09-25): R4 quarantine
 * terminal-loss settlement belongs to F4 and must be ABSENT from F2
 * production behaviour. The reviewed subject `ccdbec35` carried one; it was
 * F4 leakage and is removed, not kept because it predated the correction.
 * A quarantine now DISCLOSES (see [Handle.discloseAbsent]); the stage-leak
 * scan pins that no production code constructs `Cause.QUARANTINED`.
 */
    public enum class Cause { APPEND_REFUSED, PARKED }

    public sealed class Settle {
        public object Settled : Settle()
        public data class Refused(val reason: String) : Settle()
        public data class NotDurable(val reason: String) : Settle()
    }

    public sealed class Open {
        public data class Ok(val handle: Handle) : Open()
        public data class Unavailable(val reason: String) : Open()
    }

    /**
 * The outcome of an F3-P1a durable WRITE. Deliberately NOT [Settle]: a checkpoint
 * REQUEST and a local terminal flag are not settlements of an admission, and reusing
 * a settlement's vocabulary for them would be one name for two facts.
 */
    public sealed class Write {
        public object Written : Write()
        /** CORE refused. The reason is core's, passed through verbatim — never re-derived here. */
        public data class Refused(val reason: String) : Write()
        /** Durability is uncertain; the handle is fenced. Only a reopen decides. */
        public data class NotDurable(val reason: String) : Write()
        /**
 * WRITER/FACADE POLICY declined to issue a further request (RULING R3). This is
 * NOT a core validation refusal and NOT loss: core accepts the record
 * unconditionally, and this arm exists precisely so that a policy answer can
 * never be mistaken for — or become — a core refusal reason.
 */
        public data class PolicyDeclined(val reason: String) : Write()
    }

    /**
 * THE DERIVED PENDING CHECKPOINT, as the facade exposes it (S2-c).
 *
 * A FACADE TYPE, NOT THE PERSISTED ROW. Returning
 * `AuthorityCodec.PendingCheckpointRow` would put a `mobile-loss-core`-owned type in a
 * PUBLIC `:sdk` signature, which the module-boundary pin forbids — and it would hand
 * the caller the authority's own row.
 *
 * FROZEN MEANS FROZEN: [frozenCounts] is a COPY of the STORED values. A caller that
 * could mutate the returned map would unfreeze a frozen claim from OUTSIDE the
 * authority, where no rule inside it could see the change.
 */
    public class Pending internal constructor(
        public val checkpointId: String,
        public val throughSequence: Long,
        public val frozenCounts: Map<String, Long>,
        public val claimsTerminal: Boolean,
    )

    /** The active queue as the producer read it, for reconciliation at open. Records are the producer's own bytes. */
    public data class QueueSnapshot(val records: List<ByteArray>, val trustworthy: Boolean)

    public class Handle internal constructor(
        private val store: EncryptedAuthorityStore,
        public val sessionId: String,
    ) {
        /**
 * Has this handle been reconciled against a queue view in THIS process?
 * A producer's open (with a queue) sets it; the read-only door
 * (`openExisting`, queue = null) leaves it false. A handle that is
 * cached but unreconciled belongs to NO live producer — both producers
 * open with a queue before their first admission — which is what makes
 * a later in-place reconcile race-free.
 */
        @Volatile internal var reconciledWithQueue: Boolean = false

        public val recoveryNote: String? get() = store.recoveryNote
        public fun isFenced(): Boolean = store.isFenced() != null
        public fun highWater(): Long = store.highWater()
        public fun disabled(): String? = store.disabled()?.name
        /** F4-P2 (R11): the durable owner (a reconciliation fact id) of an owner-bound disable, or null. */
        public fun disabledOwner(): String? = store.disabledOwner()

        /**
 * How many recovery writes (adoption / reconciliation records) the
 * store REFUSED at this handle's open. Non-zero means the durable
 * authority does NOT hold everything the queue proved — the store has
 * already disabled the session (`RECOVERY_WRITE_REFUSED`) or fenced
 * itself — so a removal that relied on that adoption must not proceed:
 * the last adoptable state is still only in the queue bytes.
 */
        public fun recoveryWritesRefused(): Int = store.recoveryWriteFailures().size

        /** The store's reconcile, reachable only by the facade (the in-place reconcile of a cached handle). */
        internal fun reconcileWith(view: AuthorityRecovery.QueueView) { store.reconcileWith(view) }

        /**
 * THIS SESSION's lossy-read fence — the ONE list a destructive mutator
 * may consult, and only for records this session owns.
 */
        public fun mutationFenced(): List<Long> = store.mutationFenced()

        /** Disclosure: settlements a later phase owes. Never a removal gate. */
        public fun owedSettlement(): List<Long> = store.owedSettlement()

        /** DIAGNOSTIC union. Never a mutation gate — see [mutationFenced]. */
        public fun reconciliationPending(): List<Long> = store.reconciliationPending()

        /** Does the durable authority already record [sequence] as DELIVERED? */
        public fun isDelivered(sequence: Long): Boolean = store.isDelivered(sequence)

        /** Retained DeliveredBatch authorities. One per committed batch, never one per drain. */
        public fun deliveredBatchCount(): Int = store.deliveredBatchCount()

        /**
 * Claim the next sequence by writing its admission intent AHEAD of the
 * event. An unmappable event type fails BEFORE ownership, with no
 * fallback family (V4).
 *
 * ONE AUTHORITY OPERATION, NOT TWO. This used to read
 * `store.nextSequence` and then `store.append(.)` — two separate
 * acquisitions of the store's monitor — so two concurrent callers could
 * select the same number and the loser was refused
 * `sequence_not_above_high_water`: an ordinary concurrent capture
 * reported as a pre-admission refusal, which drops a producer event for
 * a reason that has nothing to do with the event. Allocation and the
 * durable append now happen inside ONE critical section in the
 * authoritative store (`admit`), so a concurrent admission can only
 * wait. There is deliberately NO caller-side retry loop: a retry in the
 * facade would be the caller compensating for an authority that is
 * wrong, and the only test that covered this implemented exactly that
 * loop inside itself while calling it "the facade's pattern".
 */
        public fun beginAdmission(intentId: String, eventId: String, eventType: String): Admission {
            val family = com.tracescout.rn.LossFamilies.lossFamilyForEventType(eventType)
                ?: return Admission.Refused("unknown_event_type")
            return when (val a = store.admit(intentId, family)) {
                is EncryptedAuthorityStore.Admit.Owned ->
                    Admission.Owned(Ticket(intentId, sessionId, eventId, eventType, a.family, a.sequence))
                is EncryptedAuthorityStore.Admit.Rejected -> Admission.Refused(a.reason)
                is EncryptedAuthorityStore.Admit.Indeterminate -> Admission.NotDurable(a.reason)
            }
        }

        /** The record reached the durable queue. The entry stays RETAINED (phase ADMITTED). */
        public fun settleAdmitted(ticket: Ticket): Settle =
            finalize("${ticket.intentId}-admitted", ticket.intentId, LossRecord.IntentFinalized.Outcome.ADMITTED, null)

        /** The record will never reach the queue (or left it permanently). ONE loss, in the admission's OWN family. */
        public fun settleLost(ticket: Ticket, cause: Cause): Settle =
            finalize("${ticket.intentId}-lost-${cause.name}", ticket.intentId, LossRecord.IntentFinalized.Outcome.LOST, map(cause))

        /**
 * Records already ADMITTED that a COMMITTED removal took off the active
 * timeline (park / quarantine). Called AFTER the removal committed —
 * victims are counted only after the transaction succeeds.
 * A sequence this authority does not own is skipped and traced, never
 * invented.
 */
        public fun settleLostSequences(sequences: List<Long>, cause: Cause): Settle {
            var worst: Settle = Settle.Settled
            for (q in sequences) {
                val e = store.entry(q)
                if (e == null) { RecoveryTrace.log("authority_lost_unowned", "seq" to q); continue }
                if (e.phase == SequenceAuthority.Phase.LOST || e.phase == SequenceAuthority.Phase.DELIVERED) continue
                val r = finalize("${e.intentId}-lost-${cause.name}", e.intentId, LossRecord.IntentFinalized.Outcome.LOST, map(cause))
                if (r !is Settle.Settled) worst = r
            }
            return worst
        }

        /**
 * The COMMITTED acknowledged dequeue (R1): this exact batch was committed
 * by the server.
 *
 * [recordCount] IS THE BATCH'S TRUE RECORD COUNT — the number of
 * records the server committed — and it is supplied by the CALLER, never
 * derived from `sequences.size`. The first F2 draft passed
 * `sequences.size` here, which made `DeliveredBatchFields`'s
 * count-vs-sequences guard structurally unsatisfiable: a slice holding a
 * record whose sequence could not be read produced fewer sequences than
 * records, and the guard that exists to refuse exactly that batch was
 * handed a count that always agreed. Now such a batch is REFUSED
 * (`delivered_batch_count_mismatch`) and the entries stay ADMITTED —
 * disclosed later, never marked delivered on a set F2 could not read.
 */
        public fun markDeliveredBatch(chunkId: String, recordCount: Int, contentSha256: String, sequences: List<Long>): Settle {
            val fields = try {
                DeliveredBatchFields.of(sessionId, chunkId, recordCount, contentSha256, sequences)
            } catch (e: LossFormatException) { return Settle.Refused(e.reason) }
            val rec = LossRecord.DeliveredBatch(fields.markerId, fields.sessionId, fields.chunkId,
                fields.recordCount, fields.contentSha256, fields.sequenceEncoding)
            return when (val a = store.append(rec)) {
                is EncryptedAuthorityStore.Append.Appended -> Settle.Settled
                is EncryptedAuthorityStore.Append.Rejected -> Settle.Refused(a.reason)
                is EncryptedAuthorityStore.Append.Indeterminate -> Settle.NotDurable(a.reason)
            }
        }

        /**
 * R4 — a COMMITTED quarantine removal took these ADMITTED records off the
 * active timeline. F2 writes NO loss for them (the terminal settlement is
 * F4's); it DISCLOSES them as owed under the frozen nonterminal name
 * `ADMITTED_ABSENT_UNDELIVERED`. Nothing durable changes; a disclosure
 * never fences a removal. Returns how many were disclosable.
 */
        public fun discloseAbsent(sequences: List<Long>): Int = store.discloseOwed(sequences)

        // ───────────────────────────────── F3-P1a (S2): the two writers + the accessor

        /**
 * **S2-a — THE `CheckpointRequested` WRITER. ITS RESPONSIBILITY IS ORDERING
 * ONLY: ASK CORE, AND APPEND NOTHING UNLESS CORE SAYS YES.**
 *
 * IT CARRIES NO SEMANTIC RULE. No counts comparison, no `LossCounts.encode`,
 * no `throughSequence` test, no pending-state pre-check and no copy of any
 * refusal reason. RULING R2 puts the whole semantic decision — including the
 * same-`throughSequence`/different-counts conflict — in
 * `SequenceAuthority.validate`, and *"no facade duplicate"* forbids a second
 * comparison here even if it always agreed: a copy that agrees is invisible to
 * every behavioural proof and becomes visible only when core's rule is removed
 * and one path keeps answering.
 *
 * REFUSE BEFORE APPEND IS STRUCTURAL, NOT A CONVENTION.
 * [EncryptedAuthorityStore.append]'s commit protocol validates at step A2 —
 * *"every fallible semantic decision happens BEFORE durability"* — so a refusal
 * returns with no frame sealed, no WAL byte written, no witness moved. The
 * writer's whole duty is to route through that one door and never around it.
 *
 * THE FROZEN COUNTS ARE COPIED AT THE BOUNDARY. The map a caller hands in is
 * the caller's; freezing means the authority's record is a snapshot of it at
 * request time and cannot be edited afterwards through the caller's own reference.
 *
 * A genuinely identical re-request under the same `intentId` is core's
 * `AlreadyApplied` — one durable record, no second WAL frame — and is reported
 * here as [Write.Written], because the claim IS recorded. A CONFLICTING one is
 * core's typed refusal.
 */
        public fun requestCheckpoint(
            intentId: String,
            checkpointId: String,
            throughSequence: Long,
            frozenCounts: Map<String, Long>,
            claimsTerminal: Boolean,
        ): Write = write(
            LossRecord.CheckpointRequested(
                intentId, checkpointId, throughSequence, LinkedHashMap(frozenCounts), claimsTerminal,
            ),
        )

        /**
 * **S2-b — THE `TerminalRequested` WRITER, AND THE ONLY HOME OF THE R3
 * PRECONDITION.**
 *
 * > RULING R3: the `TerminalRequested` precondition belongs in writer/facade
 * > ONLY. Core/replay validation must continue accepting `TerminalRequested`
 * > unconditionally. Adding R3 to replay/core validation is FORBIDDEN.
 *
 * BOTH SENTENCES ARE LOAD-BEARING, and this is the reading that keeps the
 * second one true: the policy DECLINES to issue a further request and reports
 * [Write.PolicyDeclined]; it does not ask core to refuse one. Core's branch stays
 * `Validation.Accepted(r, null, null)` — unconditional — so an identical durable
 * history replays to identical verdicts whether or not a `TerminalRequested`
 * precedes it.
 *
 * IT IS NOT A DUPLICATE OF A CORE RULE, because there is no core rule here to
 * duplicate. The declined answer therefore travels in its own arm rather than as
 * a refusal string, so a writer policy can never be mistaken for — or migrate
 * into — core's refusal vocabulary.
 *
 * The flag is MONOTONE: `install` only ever sets it, and no record kind clears
 * it, so the decline is stable across a restart.
 */
        public fun recordTerminal(intentId: String): Write {
            // RULING R3 — the precondition, here and nowhere else.
            if (store.terminalRequested()) return Write.PolicyDeclined("terminal_already_requested")
            return write(LossRecord.TerminalRequested(intentId))
        }

        /**
 * **S2-c — THE PENDING ACCESSOR: THE FACADE DOOR ONTO THE DERIVED STATE.**
 *
 * IT RETURNS THE STORED FROZEN VALUES AND RECOMPUTES NOTHING. The natural
 * implementation of *"what are the pending counts?"* is to re-derive them from
 * live state at read time, which would silently make a checkpoint claim something
 * it never sent — the specification names exactly that, and it is why this hands back the
 * persisted quadruple and never touches `LossCounts`, an entry, a batch or the
 * high-water.
 *
 * IT IS A HANDLE READ, SO IT OPENS NOTHING AND CREATES NOTHING. There is
 * deliberately NO object-level convenience door: `open` writes a genesis
 * snapshot for an absent directory, so a read-only question answered by opening
 * would MANUFACTURE an authority for any session it was asked about. A caller
 * that needs a handle takes one through the F2 two-door rule
 * ([openExisting] never creates) — this accessor adds no third door and no second
 * state machine.
 *
 * THE RETURNED MAP IS A COPY (see [Pending]).
 */
        public fun pendingCheckpoint(): Pending? = store.pendingCheckpoint()?.let {
            Pending(it.checkpointId, it.throughSequence, LinkedHashMap(it.frozenCounts), it.claimsTerminal)
        }

        /** RULING R3 — the local terminal flag, readable so the writer's policy is observable. */
        public fun terminalRequested(): Boolean = store.terminalRequested()

        /**
 * **F6 — PROJECT the pure `LossLedger.Ledger` VALUE from durable state.**
 *
 * **A PROJECTION, NOT A SECOND DURABLE HOME, AND THAT IS THE WHOLE
 * POINT OF an explicit product decision the specification** a product decision ruled `LossLedger.acknowledge`
 * UNCALLED and the live settlement path the binder/authority one; the
 * measured consequence was ZERO production references to
 * `com.tracescout.rn.LossLedger` anywhere in `src/main`. So the emitter
 * may use `LossLedger` ONLY as pure arithmetic over a value computed
 * HERE, at call time, from the authority that already owns the facts —
 * never as state that persists between calls. Nothing is cached: the
 * returned value is dead the moment the authority moves.
 *
 * The six fields and where each comes from:
 *
 * ```
 * sessionId this handle's session
 * counts retainedLostByFamily (LOST survives compaction)
 * acknowledgedThrough ackThrough (-1 = no boundary yet)
 * acknowledgedTotal sum(ackCounts) (what was DECLARED at it)
 * acknowledgedTerminal acknowledgedTerminal (the SETTLED server fact)
 * terminal terminalRequested (the LOCAL durable claim)
 * ```
 *
 * `terminal` IS THE LOCAL CLAIM, NOT THE ACKNOWLEDGED ONE.
 * `LossLedger.freeze` freezes `ledger.terminal` into the snapshot, and
 * its own KDoc requires the producer's claim to be DURABLE BEFORE it is
 * asserted — `markTerminal`, persist, then emit. Feeding
 * `acknowledgedTerminal` would make a checkpoint assert a fact the
 * SERVER already holds and the producer never requested.
 *
 * **AND THAT RULE IS EXACTLY WHY THE SETTLED FACT NEEDS ITS OWN
 * FIELD.** `acknowledgedTerminal` is projected BESIDE `terminal`, never
 * into it: the claim stays the claim, and the settled fact becomes
 * readable by the one pure rule that needs it —
 * `checkpointForMetadataOnly`'s "terminality at T is already
 * acknowledged" refusal, which was STRUCTURALLY UNREACHABLE while this
 * projection omitted it (its only other source,
 * `LossLedger.acknowledge`, has zero production callers). A settled
 * terminal transition was therefore re-emitted by the ordinary
 * metadata-only path on every later drain, violating frozen C18.md
 * line 91. Measured as C18-REOPEN FAILING on API 35 and API 26.
 *
 * IT IS A SCALAR, NOT A SNAPSHOT, AND THE DISTINCTION IS THE
 * CONTRACT. `pending` stays null below for the stated a product decision reason — a
 * reconstructed `LossLedger.PendingCheckpoint` would be a SECOND
 * representation of one fact. A boolean qualifying the
 * `acknowledgedThrough` this projection already carries adds no second
 * home for anything: there is still exactly one acknowledged boundary,
 * one durable authority, and one place each is read.
 *
 * `pending` IS DELIBERATELY LEFT NULL, and this is load-bearing. The
 * durable pending checkpoint is the authority's
 * `AuthorityCodec.PendingCheckpointRow`, and the emitter asks about it
 * through [pendingCheckpoint] — the row the binder settles against.
 * Reconstructing a `LossLedger.PendingCheckpoint` here would create a
 * SECOND representation of one fact, which is what a product decision's ruling
 * forbids; `checkpointForDelivery`'s own `CHECKPOINT_PENDING` refusal is
 * therefore never the gate that stops a second claim — the emitter
 * consults the durable row FIRST and retries it by value.
 *
 * A HANDLE READ: opens nothing, creates nothing, writes nothing.
 *
 * **`internal`, AND B10 IS THE REASON — NOT A STYLE CHOICE.** The F1
 * pin forbids a core-owned type in any PUBLIC API signature of `:sdk`,
 * `:rn` or rn-core, and `LossLedger.Ledger` is core-owned. A `public`
 * projection would leak the pure model across the module boundary and
 * make it part of the SDK's surface — exactly what B10 exists to stop.
 * The F6 emitter lives in this module's `internal/loss`, so `internal`
 * is the whole reach it needs.
 */
        internal fun projectLedger(): LossLedger.Ledger = LossLedger.Ledger(
            sessionId = sessionId,
            counts = canonicalCounts(store.retainedLostByFamily()),
            acknowledgedThrough = store.ackThrough(),
            acknowledgedTotal = store.ackCounts().values.sum(),
            acknowledgedTerminal = store.acknowledgedTerminal(),
            terminal = store.terminalRequested(),
        )

        /**
 * **THE COMPLETE CANONICAL FAMILY SET, AND THE FIRST RUN PROVED IT IS
 * AN INVARIANT RATHER THAN A PREFERENCE.** `PendingCheckpoint.of` carries
 * `require(declaredCounts.keys == LossFamilies.ALL)`, and
 * `LossLedger.Ledger.counts` defaults to `LossFamilies.emptyCounts` —
 * so a `Ledger` is only ever well-formed with all 13 families present.
 * The authority's retained-loss map holds ONLY the families that have
 * lost something, so projecting it raw made `freeze` throw
 * `declaredCounts must be exactly the canonical family set` on 13 of 25
 * rows. Completing it HERE, in the projection, is what makes the capture specification's
 * *"the FIRST checkpoint contains the COMPLETE platform family state"*
 * true by construction rather than by a caller remembering.
 *
 * A family with no loss is `0`, stated rather than omitted: an absent
 * key is "we did not say", and the producer is entitled to make the
 * stronger statement.
 */
        private fun canonicalCounts(retained: Map<String, Long>): Map<String, Long> {
            val out = LinkedHashMap<String, Long>()
            for (f in com.tracescout.rn.LossFamilies.ALL) out[f] = retained[f] ?: 0L
            return out
        }

        // ───────────────────────────────── F3-P1b: the settlement writer

        /**
 * **F3-P1b — THE `AckApplied` WRITER. ITS DUTY IS ORDERING ONLY: BIND, THEN
 * APPEND. IT CONTAINS NO COMPARISON OF ITS OWN.**
 *
 * Routing: the parsed ack's values in, the DURABLE pending row via
 * [pendingCheckpoint] (the S2-c accessor — stored frozen values, recomputed from
 * nothing). Ordering: ask the ONE comparison home,
 * `CheckpointAckBinder.bind`, and append ONLY on a Bound decision. The append call:
 * through the one durable-write door every P1a writer uses.
 *
 * NOT A RE-CHECK, NOT A DEFENSIVE DUPLICATE, NOT AN ASSERTION THAT RE-READS A
 * FIELD THE BINDER ALREADY COMPARED. Every M-1 … M-7 predicate has exactly one
 * production home, the core binder (P1b-17, OCH-2); a copy here that agreed would
 * be invisible to every behavioural proof and visible only when one of the two was
 * edited. A Declined decision is passed through as [Write.PolicyDeclined] with the
 * binder's own reason — never re-worded, never a core refusal token.
 *
 * `settlesCheckpointId` HAS NO SOURCE HERE BUT THE BOUND DECISION, which took it
 * from `pending.checkpointId` and from no other expression (D1-b). There is no
 * parameter, setter, builder or overload by which a caller can supply a checkpoint
 * id (D1-c) — the wire ack carries none, so there is nothing to take it from.
 * Likewise the `intentId` is the R4 content address computed in core (R4-e): no
 * production entry point accepts one.
 *
 * REFUSE BEFORE APPEND IS STRUCTURAL. A decline returns with no frame sealed, no
 * WAL byte written, no witness moved — it never reaches the store. And an
 * `AlreadyApplied` (the same content address settled twice: `accepted` then the
 * exact `duplicate`) is absorbed by the store into `Appended` — one durable record,
 * no second frame, no writer-side "already settled?" guard.
 *
 * NO `disabled` READ, WRITE OR GATE (PLAN D2, P1b-15). Settlement clears the
 * pending row through core's own `AckApplied` transition and touches nothing
 * else; it is never rehabilitation.
 *
 * D2 (P1a review) IS RESTATED, NOT NARROWED: this reads the pending row and
 * appends under SEPARATE lock acquisitions, exactly like the P1a writers. Two
 * concurrent settlements of the SAME remote fact carry the same content address
 * and are one record by `AlreadyApplied`; the residual window is two settlements
 * of genuinely different facts. No new lock, no widened critical section.
 */
        public fun settleCheckpoint(
            protocol: String,
            lossAccountingVersion: Int,
            state: String,
            ackSessionId: String,
            acceptedThroughSequence: String?,
            acceptedCounts: Map<String, Long>,
            terminal: Boolean,
        ): Write {
            val pending = pendingCheckpoint()?.let {
                CheckpointAckBinder.Pending(it.checkpointId, it.throughSequence, it.frozenCounts)
            }
            val ack = CheckpointAckBinder.Ack(
                protocol, lossAccountingVersion, state, ackSessionId, acceptedThroughSequence,
                LinkedHashMap(acceptedCounts), terminal,
            )
            return when (val d = CheckpointAckBinder.bind(sessionId, pending, ack)) {
                is CheckpointAckBinder.Decision.Declined -> Write.PolicyDeclined(d.reason)
                is CheckpointAckBinder.Decision.Bound -> write(
                    LossRecord.AckApplied(
                        d.intentId, d.throughSequence, d.counts, d.acknowledgedTerminal, d.settlesCheckpointId,
                    ),
                )
            }
        }

        /**
 * The ONE durable-write route for the F3-P1a writers: ask the authoritative store,
 * and report what it answered. `AlreadyApplied` is absorbed by
 * [EncryptedAuthorityStore.append] into `Appended` — the claim is recorded either
 * way — and no verdict is ever re-derived, cached or second-guessed here.
 */
        private fun write(r: LossRecord): Write = when (val a = store.append(r)) {
            is EncryptedAuthorityStore.Append.Appended -> Write.Written
            is EncryptedAuthorityStore.Append.Rejected -> Write.Refused(a.reason)
            is EncryptedAuthorityStore.Append.Indeterminate -> Write.NotDurable(a.reason)
        }

        // ───────────────────────────────── F4-P0 Slice C: the eviction writer path

        /**
 * Slice C (C1/C3) — may [sequence] be named a victim? `null` = yes; else
 * the typed reason. READ-ONLY, from the OWNED entry: a sequence this
 * authority does not own, an admission not yet settled, a delivered or
 * already-lost record, and a record already inside a pending bracket
 * are each refused by name. The caller supplied the number it READ from
 * the record's own bytes; the authority says whether that number is
 * ITS, and in what state.
 */
        public fun victimIneligibility(sequence: Long): String? {
            val e = store.entry(sequence) ?: return "sequence_not_owned"
            return when (e.phase) {
                SequenceAuthority.Phase.PREPARED -> "admission_unresolved"
                SequenceAuthority.Phase.DELIVERED -> "already_delivered"
                SequenceAuthority.Phase.LOST -> "already_lost"
                SequenceAuthority.Phase.ADMITTED -> when {
                    store.isDelivered(sequence) -> "already_delivered"
                    store.unresolvedEvictions().contains(sequence) -> "eviction_pending"
                    else -> null
                }
            }
        }

        /** The sequences with a durable, unresolved `EvictionIntent`. Diagnostic and test read; Slice D's recovery input. */
        public fun unresolvedEvictions(): List<Long> = store.unresolvedEvictions()

        /**
 * How many victims this authority can PREPARE **and** SETTLE before its
 * WAL refuses `at_capacity` — two frames per victim, at the rung an
 * eviction intent and its finalization both pad to. The bracket's
 * preflight (C3) reserves this for EVERY affected session before the
 * first intent, so a session that lacks room produces zero intents
 * anywhere. The number is advisory: the authority's own append is the
 * guard, atomically with its write.
 */
        public fun evictionHeadroomVictims(): Long =
            store.walHeadroomBytes() / (2L * EVICTION_FRAME_BYTES)

        /**
 * **PREPARE — THE ONE `EvictionIntent` WRITER.** Sequence and family come
 * from the OWNED entry; the caller supplies only the bracket identity and
 * the number it read. Refused (nothing written) unless the entry is
 * ADMITTED. Idempotent by `(bracketId, sequence)`: the intent id is
 * derived from both, so a repeated PREPARE of the same bracket is core's
 * `AlreadyApplied` — one durable record.
 */
        public fun beginEviction(bracketId: String, sequence: Long): Write {
            val e = store.entry(sequence) ?: return Write.Refused("sequence_not_owned")
            if (e.phase != SequenceAuthority.Phase.ADMITTED) return Write.Refused("victim_not_admitted:${e.phase.name}")
            return write(LossRecord.EvictionIntent(evictionIntentId(bracketId, sequence), sequence, e.family, 1L))
        }

        /**
 * **SETTLE after a PROVEN commit — the ONE `BYTE_EVICTED` writer.** One
 * loss, in the victim's own persisted family, resolving its intent. Core
 * refuses a victim it already records DELIVERED
 * (`eviction_conflicts_with_delivered`) and a finalization of an intent
 * that is no longer unresolved (`unknown_admission`) — so a victim can be
 * settled once, and never twice under two causes.
 */
        public fun evictionLost(bracketId: String, sequence: Long): Write {
            val id = evictionIntentId(bracketId, sequence)
            return write(LossRecord.IntentFinalized("$id-lost", id, LossRecord.IntentFinalized.Outcome.LOST, LossCause.BYTE_EVICTED))
        }

        /**
 * **F4-P1 — SETTLE a QUARANTINE after a PROVEN commit: the ONE
 * `QUARANTINED` writer** (F4-15, addendum v2 ruling R4). The SAME
 * intent the bracket PREPARED through [beginEviction] resolves LOST
 * under the cause the marker bound — the record was destroyed by this
 * install's own quarantine, not by capacity pressure, and the two
 * causes are never interchangeable: a bracket whose marker says
 * `QUARANTINED` cannot be settled `BYTE_EVICTED` and vice versa, because
 * each writer names its cause here and nowhere else. Core refuses a
 * victim it already records DELIVERED and a second finalization of the
 * same intent, so a record is settled once and never twice under two
 * causes. Same derived ids as [evictionLost]: idempotent within one
 * bracket.
 */
        public fun quarantineLost(bracketId: String, sequence: Long): Write {
            val id = evictionIntentId(bracketId, sequence)
            return write(LossRecord.IntentFinalized("$id-lost", id, LossRecord.IntentFinalized.Outcome.LOST, LossCause.QUARANTINED))
        }

        /**
 * **SETTLE after a PROVEN no-commit.** The intent resolves with outcome
 * ADMITTED and no cause: the record is exactly where it was and the
 * entry stays ADMITTED. Idempotent by the same derived id.
 */
        public fun evictionDidNotHappen(bracketId: String, sequence: Long): Write {
            val id = evictionIntentId(bracketId, sequence)
            return write(LossRecord.IntentFinalized("$id-nh", id, LossRecord.IntentFinalized.Outcome.ADMITTED, null))
        }

        // ───────────────────────────────── F4-P2: the reconciler's writers (F4-16 / F4-17)

        /**
 * **F4-P2 — SETTLE a sequence whose record could not be read: the ONE
 * `UNREADABLE` writer** (F4-16; GAP_MATRIX F.1/F.2; the capture specification). Reached
 * only from the queue reconciler, for a KNOWN-cardinality lossy read
 * (an auth-failed frame or a torn tail): the sequence is ADMITTED in the
 * retained index, undelivered, and absent from the decoded survivors —
 * `lost = expectedUnsettled − decodedSurvivors`, its family the one the
 * index PERSISTED at admission, never inferred. The admission intent
 * itself resolves LOST (the same shape as the F2 PARKED writer), so the
 * entry is settled once; core refuses a second finalization and a
 * finalization of a delivered sequence. An UNKNOWN-cardinality class
 * (`frame_length_invalid`, an unreadable store, evidence already gone)
 * must never reach this writer — that is [disableUnattributableGap].
 */
        public fun unreadableLost(sequence: Long): Write {
            val e = store.entry(sequence) ?: return Write.Refused("sequence_not_owned")
            if (store.isDelivered(sequence)) return Write.Refused("already_delivered")
            // Idempotent across a relaunch that retries the SAME reconciliation fact: a
            // sequence this writer already settled is a no-op; a sequence settled under
            // ANY OTHER cause (or delivered) is refused — never re-attributed.
            if (e.phase == SequenceAuthority.Phase.LOST) {
                return if (e.cause == LossCause.UNREADABLE) Write.Written else Write.Refused("already_settled:LOST:${e.cause?.name ?: ""}")
            }
            if (e.phase != SequenceAuthority.Phase.ADMITTED) return Write.Refused("victim_not_admitted:${e.phase.name}")
            return write(LossRecord.IntentFinalized("${e.intentId}-lost-UNREADABLE", e.intentId, LossRecord.IntentFinalized.Outcome.LOST, LossCause.UNREADABLE))
        }

        /**
 * **F4-P2 — D2 fail-closed state for a gap whose cardinality cannot be
 * established even with the index**: the session is marked `UNATTRIBUTABLE_GAP` — durable,
 * per session, not reset by a drain; the gap stays in sequence arithmetic
 * (no entry is edited). The record is the OWNER-BOUND
 * `LossRecord.OwnedDisable` naming [ownerId], the durable id of the
 * reconciliation fact that installs it — the only identity a later
 * [clearUnattributableGap] may name. The ONE production writer of this
 * reason. Idempotent for its own owner; an already-disabled session (any
 * reason, any owner) is NEVER overwritten — the core refuses a second
 * owner and this returns `Refused("already_disabled:…")` so the caller
 * records that it installed nothing.
 */
        public fun disableUnattributableGap(ownerId: String): Write {
            val d = store.disabled()
            if (d != null) return if (store.disabledOwner() == ownerId) Write.Written else Write.Refused("already_disabled:${d.name}")
            return write(LossRecord.OwnedDisable("p2dis-$ownerId", DisableReason.UNATTRIBUTABLE_GAP, ownerId))
        }

        /**
 * **F4-P2 — the ONE clear, and it clears ONLY the
 * owner-bound `UNATTRIBUTABLE_GAP` disable whose owner is [ownerId].** The
 * core refuses a session that is not disabled under THAT owner — a legacy
 * `CheckpointDisabled` (including a legacy `UNATTRIBUTABLE_GAP`), any other
 * reason, or another owner — so a non-P2 disable stays monotonic forever
 * and ownership is never guessed from the reason name. The clear writes
 * no high-water, edits no entry and reallocates nothing: the gap stays
 * in arithmetic; the session regains the right to make claims. Idempotent:
 * a session that is no longer disabled answers `Written` (the clear has
 * landed, or there was nothing to clear — the caller only reaches this
 * for a disable the fact proves it owns). The reconciler is its ONE
 * caller and reaches it only after the fact is CONSUMED and a fresh exact
 * re-scan closed the session (the ten R9 conditions live THERE).
 */
        public fun clearUnattributableGap(ownerId: String): Write {
            if (store.disabled() == null) return Write.Written
            return write(LossRecord.OwnedDisableCleared("p2clr-$ownerId", ownerId))
        }

        /**
 * **F6 slice 1 (`an explicit product decision`) — the ONE F6 writer of a
 * durable `checkpointDisabled`, and it writes exactly one reason.**
 *
 * Reached only from `F6CheckpointEmitter`'s `INTERVAL_MISMATCH` arm. The
 * record is the EXISTING monotonic [LossRecord.CheckpointDisabled]: no new
 * record kind, no second durable home, and deliberately NOT
 * [LossRecord.OwnedDisable] — `F6_INTERVAL_MISMATCH` is
 * `ownerClearable == false`, so an owner-bound write of it is refused by
 * the core (`disable_reason_not_owner_bindable`) and by the codec
 * (`snapshot_owner_on_monotonic_reason`).
 *
 * **IT RETURNS THE WRITE RESULT AND CLAIMS NOTHING.** The ruling is that
 * the latch may be set *only after a successful durable write*, so this
 * function must not swallow a refusal into a boolean — the caller has to be
 * able to tell "durably disabled" from "could not record it". A failed write
 * leaves the session exactly as it was and the chunk still proceeds.
 *
 * **IDEMPOTENT, AND IT NEVER LAYERS A SECOND DISABLE.** An already
 * disabled session answers `Written` when the existing reason is this one —
 * the durable fact the caller wanted is on disk — and `Refused` naming the
 * incumbent otherwise, because a session disabled for a DIFFERENT reason
 * must keep that reason: the first cause is the true one, and overwriting it
 * would lose why numbering was ever in doubt. Matches
 * [disableUnattributableGap]'s shape rather than inventing a second
 * convention.
 */
        public fun disableF6IntervalMismatch(): Write {
            val d = store.disabled()
            if (d != null) {
                return if (d == DisableReason.F6_INTERVAL_MISMATCH) Write.Written
                else Write.Refused("already_disabled:${d.name}")
            }
            return write(
                LossRecord.CheckpointDisabled(
                    "f6dis-${DisableReason.F6_INTERVAL_MISMATCH.name}",
                    DisableReason.F6_INTERVAL_MISMATCH,
                ),
            )
        }

        /**
 * Read-only: the sequences this session still EXPECTS a settlement for
 * (PREPARED or ADMITTED, not delivered). The reconciler's `expectedUnsettled`.
 */
        public fun unsettledSequences(): List<Long> = store.ownedSequences().values
            .filter { it.phase == SequenceAuthority.Phase.PREPARED || it.phase == SequenceAuthority.Phase.ADMITTED }
            .filter { !store.isDelivered(it.sequence) }
            .map { it.sequence }.sorted()

        /** Test/diagnostic read: the phase name and family of a sequence, or null. */
        public fun describe(sequence: Long): String? = store.entry(sequence)?.let { "${it.phase.name}:${it.family}:${it.cause?.name ?: ""}" }

        /**
 * TESTS ONLY — force the store's own compaction and report why it did
 * not run (`null` = it compacted). Exists so the RF4-15 deferral can be
 * asserted without a second store opened over a live directory.
 */
        internal fun compactForTest(): String? = when (val c = store.compact()) {
            is EncryptedAuthorityStore.Compaction.Compacted -> null
            is EncryptedAuthorityStore.Compaction.NotCompacted -> c.reason
            is EncryptedAuthorityStore.Compaction.Indeterminate -> "indeterminate:" + c.reason
        }

        private fun finalize(id: String, resolves: String, outcome: LossRecord.IntentFinalized.Outcome, cause: LossCause?): Settle =
            when (val a = store.append(LossRecord.IntentFinalized(id, resolves, outcome, cause))) {
                is EncryptedAuthorityStore.Append.Appended -> Settle.Settled
                is EncryptedAuthorityStore.Append.Rejected -> Settle.Refused(a.reason)
                is EncryptedAuthorityStore.Append.Indeterminate -> Settle.NotDurable(a.reason)
            }

        /**
 * The facade cause IS the core cause of the same name — by construction,
 * never by a hand-kept table. `Cause` is a strict subset of `LossCause`
 * (pinned), so `valueOf` cannot fail for a value that exists here; and a
 * value that does not exist here cannot be constructed at all.
 */
        private fun map(c: Cause): LossCause = LossCause.valueOf(c.name)
    }

    // ───────────────────────────────────────────────────────────── registry

    /**
 * The on-disk bytes of ONE eviction-intent or finalization frame: both pad
 * to the smallest rung (an id of ≤ 128 chars plus a sequence, a family and
 * a count fit far below 1,024 bytes — `SliceCSourceTest` measures it rather
 * than assumes it). Two of these per victim is the preflight's reservation.
 */
    public val EVICTION_FRAME_BYTES: Long =
        AuthorityPadding.onDiskFrameBytes(AuthorityPadding.RUNG1).toLong()

    /** `<bracketId>-ev-<sequence>` — the ONE derivation of an eviction intent's identity. */
    @JvmStatic
    public fun evictionIntentId(bracketId: String, sequence: Long): String = "$bracketId-ev-$sequence"

    private val handles = ConcurrentHashMap<String, Handle>()
    private fun keyOf(filesDir: File, sessionId: String) = filesDir.absolutePath + "|" + sessionId

    /** Sessions to keep on disk beyond the current one, newest first by mtime. */
    public const val RETAINED_SESSION_DIRS: Int = 8

    /**
 * Open the process-wide handle for [sessionId]. On the FIRST open for a
 * session in this process, reconcile against [queue] (or adopt a pre-F2
 * queue when the authority is at genesis) and prune old session
 * directories beyond [RETAINED_SESSION_DIRS], never touching a session
 * still represented in [queue].
 */
    @JvmOverloads
    public fun open(
        filesDir: File,
        sessionId: String,
        queue: QueueSnapshot? = null,
        // SS H.1 — the authority-v2 domain. `AuthorityKeys` records why the QUEUE
        // key SYMBOL is deliberately not migrated, renamed or re-pointed; the
        // symbol is not named here, because a source pin over this file must not
        // match an explanation of the thing it is looking for (the documented
        // prose-matching trap).
        key: () -> SecretKey = { AuthorityKeys.authorityKey() },
        dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
    ): Open {
        handles[keyOf(filesDir, sessionId)]?.let { h -> if (queue == null || h.reconciledWithQueue) return Open.Ok(h) }
        synchronized(handles) {
            val cached = handles[keyOf(filesDir, sessionId)]
            if (cached != null && (queue == null || cached.reconciledWithQueue)) return Open.Ok(cached)
            // ONE key resolution on this path (RULING A4 pin): the floor, the
            // authority and a cached-handle reconcile all use the same `k`.
            // THE REFUSAL NAMES ITS KEY DOMAIN. It used to read
            // `key_unavailable:<domain>:<Exception>`, which names the DOMAIN and the
            // failure and not the
            // domain — and with two Keystore aliases in play "the key is
            // unavailable" is not one fact. A reader could not tell an authority-v2
            // outage from a queue outage, and the design's own negative control for
            // H4 is exactly "one shared reason". The domain token comes from
            // `AuthorityKeys.DOMAIN`, so it cannot drift from the alias it describes.
            val k = try { key() } catch (t: Throwable) {
                return Open.Unavailable("key_unavailable:${AuthorityKeys.DOMAIN}:${t.javaClass.simpleName}")
            }
            if (cached != null) {
                // A CACHED HANDLE THE READ-ONLY DOOR OPENED HAS NOT BEEN
                // RECONCILED. `openExisting` (queue = null) caches a handle that
                // was never compared against the queue, so when a queue-bearing
                // open arrives later in the same process — the removal door, or a
                // producer's `start(sessionId)` — returning that handle as-is
                // would leave a dead process's PREPARED entries unresolved and
                // undisclosed forever (liveness R3, item 3, consequence B2).
                // Reconcile it IN PLACE, once. Race-free: an unreconciled handle
                // belongs to no live producer (see `reconciledWithQueue`).
                reconcileCached(filesDir, cached, queue!!, k, dirSync)
                return Open.Ok(cached)
            }
            // SS K.4 — INJECTIVE v2 NAMING. `dirFor` returns null for an id whose
            // escaped name would exceed one path component, and truncating instead
            // would restore exactly the `x_1` / `x.1` collision v2's naming removes.
            // A FALSE REFUSAL is the one direction SS K.2 says this design may err in.
            val dir = EncryptedAuthorityStore.dirFor(filesDir, sessionId)
                ?: return Open.Unavailable("session_id_not_encodable")
            // SS K.2 — THE v1 CLEAN BREAK, by EXACT PATH and nothing more. It opens
            // nothing, reads no v1 byte, and its only outcome is a refusal.
            if (V3Legacy.v1SessionDirectoryPresent(filesDir, sessionId)) {
                RecoveryTrace.log("authority_unavailable", "why" to "legacy_authority_v1_present", "session" to sessionId.take(24))
                return Open.Unavailable("legacy_authority_v1_present")
            }
            // L0 — THE ROOT LOCK IS TAKEN HERE, BEFORE THE FLOORS ARE READ, AND
            // HELD ACROSS THE WHOLE READ/REOPEN DECISION.
            //
            // The floors read used to sit OUTSIDE the lock because the only
            // acquisition on this path was the one inside `EncryptedAuthorityStore
            //.open`. That left a window: this process reads `session-floors.bin`,
            // another process replaces it (its own `recordFloor` correctly takes the
            // lock we are not holding), and the genesis below is then computed from a
            // floor that is no longer the durable one. A stale floor under a genesis
            // is SEQUENCE REUSE — the exact failure RULING A1 exists to prevent — and
            // the ordering, not the floors file, was the defect.
            //
            // ONE lock, not two. `SequenceFloorStore.readLocked` and
            // `EncryptedAuthorityStore.openLocked` are the locked twins of the
            // acquiring entry points; a second lock around the floors would be a
            // second lock order, which the specification refuses on the grounds that one lock
            // cannot deadlock, invert or be unlinked.
            //
            // ACQUIRED AFTER THE KEY RESOLUTION, DELIBERATELY. `AuthorityLock
            //.acquire` MKDIRS the authority root, and the specification A6 requires an
            // unavailable key to create NOTHING — so the key check stays above it.
            // A key resolution reads no authority byte, so nothing the lock protects
            // has been touched at that point.
            val rootDir = EncryptedAuthorityStore.rootFor(filesDir)
            val rootAcq = AuthorityLock.acquire(rootDir)
            if (rootAcq is AuthorityLock.Acquisition.Unavailable) {
                RecoveryTrace.log("authority_unavailable", "why" to "authority_lock_unavailable", "session" to sessionId.take(24))
                return Open.Unavailable("authority_lock_unavailable")
            }
            val rootLock = rootAcq as AuthorityLock.Acquisition.Ok
            try {
                return openUnderRootLock(filesDir, sessionId, queue, k, dirSync, dir, rootDir)
            } finally {
                rootLock.release()
            }
        }
    }

    /**
 * The body of [open] from the first artifact READ onwards. **THE ROOT LOCK IS
 * ALREADY HELD**, and every floors byte, every authority byte, every repair and
 * both sweeps happen inside it.
 */
    private fun openUnderRootLock(
        filesDir: File,
        sessionId: String,
        queue: QueueSnapshot?,
        k: SecretKey,
        dirSync: DurableReplace.DirSync,
        dir: File,
        rootDir: File,
    ): Open {
        run {
            val fresh = !EncryptedAuthorityStore.authorityFilesPresent(dir)
            val pruned = EncryptedAuthorityStore.isPruned(dir)
            // RULING A1 — the durable floor is consulted BEFORE any genesis.
            // An unreadable floors file FAILS CLOSED for the session that NEEDS
            // it: "I cannot read the floors" and "there are no floors" are
            // different facts, and treating the first as the second is exactly
            // how a reused session id would genesis at 0 after a prune.
            //
            // AND IT FAILS CLOSED PER SESSION, NOT PER INSTALL (ruling on
            // B-F2-FLOORS-UNREADABLE-NO-RECOVERY, 2026-09-25). The first version
            // returned `Unavailable` for EVERY session the moment the floors file
            // could not be read, and nothing in the SDK could clear it. The
            // per-session answer rests on two durable facts that do not live in
            // the floors file: the session's own authority directory (a live
            // authority allocates from its OWN high-water and needs no floor to
            // do so), and the prune tombstone (`pruned` inside the directory,
            // written before the authority files went). So:
            //
            // floors OK -> as before
            // floors ABSENT/FAILED, live authority -> open it; the
            // partial-restore
            // cross-check is
            // disclosed as skipped
            // floors ABSENT/FAILED, PRUNED (tombstone) -> REFUSE this session:
            // its floor is exactly
            // the fact that cannot
            // be read, so no genesis,
            // nothing inferred from
            // the queue, no guessed
            // floor. A fresh sid is
            // the recovery.
            // floors ABSENT/FAILED, never seen (no dir) -> genesis at -1: a
            // session that never
            // allocated cannot reuse
            // anything.
            val floorsRead = SequenceFloorStore.readLocked(filesDir, k)
            var floorNote: String? = null
            val floor: Long = when (floorsRead) {
                is SequenceFloorStore.Read.Ok -> SequenceFloorStore.floorFor(floorsRead.floors, sessionId) ?: -1L
                is SequenceFloorStore.Read.Absent, is SequenceFloorStore.Read.Failed -> {
                    val why = if (floorsRead is SequenceFloorStore.Read.Failed) floorsRead.reason else "absent"
                    if (pruned) {
                        RecoveryTrace.log("authority_unavailable", "why" to "floor_unreadable_for_pruned_session", "detail" to why.take(40), "session" to sessionId.take(24))
                        return Open.Unavailable("floor_unreadable_for_pruned_session:$why")
                    }
                    if (!fresh) floorNote = "floor_cross_check_skipped:$why"
                    else if (floorsRead is SequenceFloorStore.Read.Failed) RecoveryTrace.log("authority_floors_unreadable_fresh_genesis", "why" to why, "session" to sessionId.take(24))
                    -1L
                }
            }
            val store = when (
                val o = EncryptedAuthorityStore.openLocked(
                    dir, sessionId, k, dirSync, SecureRandom(),
                    genesisFloor = floor,
                    root = rootDir,
                )
            ) {
                is EncryptedAuthorityStore.Open.Ok -> o.store
                is EncryptedAuthorityStore.Open.Failed -> {
                    RecoveryTrace.log("authority_unavailable", "why" to o.reason, "session" to sessionId.take(24))
                    return Open.Unavailable(o.reason)
                }
            }
            // A durable floor can only ever be BELOW OR EQUAL to a live
            // authority's high-water. Above it means the authority on disk is
            // older than the floor — a partially restored directory — and
            // continuing would re-issue numbers the floor says are spent.
            if (store.highWater() < floor) {
                RecoveryTrace.log("authority_unavailable", "why" to "high_water_below_durable_floor",
                    "highWater" to store.highWater(), "floor" to floor, "session" to sessionId.take(24))
                return Open.Unavailable("high_water_below_durable_floor:${store.highWater()}<$floor")
            }
            floorNote?.let { RecoveryTrace.log("authority_floor_note", "note" to it, "session" to sessionId.take(24)) }
            if (queue != null) {
                val mine = RecordFields.forSession(queue.records, sessionId)
                // A RECORD THAT CANNOT BE READ MAKES THE READ UNTRUSTWORTHY FOR
                // RECOVERY (blocker 2, SF-7). The first draft dropped unreadable
                // records with `mapNotNull` and reconciled against the rest — so a
                // PREPARED entry whose survivor was simply unparseable to THIS
                // reader was settled LOST(NOT_STORED_AT_RECOVERY): a durable
                // false loss for a record that would be uploaded. A survivor set
                // with a hole in it cannot prove absence, so the view is marked
                // untrustworthy: PREPARED entries stay PREPARED and are FENCED
                // for this session only, and adoption disables rather than
                // guesses. Nothing is settled on a read that could not be
                // completed.
                val n = RecordFields.unreadable(queue.records)
                val trustworthy = queue.trustworthy && n == 0
                if (n > 0) RecoveryTrace.log("authority_queue_records_unreadable", "count" to n, "session" to sessionId.take(24))
                if (fresh && mine.isNotEmpty()) {
                    store.adopt(mine.map { AuthorityRecovery.Survivor(it.sequence, it.eventType) }, trustworthy)
                } else {
                    store.reconcileWith(AuthorityRecovery.QueueView(
                        mine.map { it.sequence }.toSet(), trustworthy, mine.maxOfOrNull { it.sequence }))
                }
                prune(filesDir, keep = RecordFields.sessionIds(queue.records) + sessionId, key = k, dirSync = dirSync)
            }
            val h = Handle(store, sessionId)
            h.reconciledWithQueue = queue != null
            handles[keyOf(filesDir, sessionId)] = h
            // A recovery write the store refused is REPORTED, never hidden: the
            // store has already disabled the session durably (or fenced itself),
            // and the handle says so through `disabled` / `isFenced`.
            store.recoveryWriteFailures().takeIf { it.isNotEmpty() }?.let {
                RecoveryTrace.log("authority_opened_with_refused_recovery_writes", "count" to it.size,
                    "disabled" to (store.disabled()?.name ?: "no"), "fenced" to (store.isFenced() ?: "no"), "session" to sessionId.take(24))
            }
            return Open.Ok(h)
        }
    }

    /**
 * Reconcile a handle the read-only door cached, against the queue view a
 * later queue-bearing open supplied. The authority already exists (the
 * handle is open), so this is the NOT-fresh half of `open`'s reconcile
 * block — `reconcileWith`, never `adopt` — plus the same prune. Sets
 * [Handle.reconciledWithQueue] so it runs once per process per session.
 */
    private fun reconcileCached(filesDir: File, h: Handle, queue: QueueSnapshot, k: SecretKey, dirSync: DurableReplace.DirSync) {
        val mine = RecordFields.forSession(queue.records, h.sessionId)
        val n = RecordFields.unreadable(queue.records)
        val trustworthy = queue.trustworthy && n == 0
        if (n > 0) RecoveryTrace.log("authority_queue_records_unreadable", "count" to n, "session" to h.sessionId.take(24))
        h.reconcileWith(AuthorityRecovery.QueueView(mine.map { it.sequence }.toSet(), trustworthy, mine.maxOfOrNull { it.sequence }))
        h.reconciledWithQueue = true
        RecoveryTrace.log("authority_reconciled_cached_handle", "session" to h.sessionId.take(24), "survivors" to mine.size)
        prune(filesDir, keep = RecordFields.sessionIds(queue.records) + h.sessionId, key = k, dirSync = dirSync)
    }

    /**
 * Open the authority for [sessionId] ONLY IF ONE ALREADY EXISTS ON DISK —
 * never creating a directory, a genesis snapshot or any file.
 *
 * THE NON-CREATING FORM EVERY LOOKUP MUST USE (blocker 3 of the ccdbec35
 * reviews). `open` WRITES a genesis snapshot for an absent directory, so
 * a read-only path — the attribution sink resolving a handle for an
 * inherited pre-F2 session, the delivered-set lookup after a crash — that
 * called it would MANUFACTURE an authority: a `highWater = -1` directory
 * for that sid, after which a later `start(sessionId = <that sid>)` finds
 * `fresh = false`, skips adoption and allocates from 0, re-issuing numbers
 * the server already accepted. A lookup answers from what is there; an
 * absent authority answers `Unavailable("no_authority")` and leaves the
 * file system byte-identical.
 */
    @JvmOverloads
    public fun openExisting(
        filesDir: File,
        sessionId: String,
        // SS H.1 — the authority-v2 domain. `AuthorityKeys` records why the QUEUE
        // key SYMBOL is deliberately not migrated, renamed or re-pointed; the
        // symbol is not named here, because a source pin over this file must not
        // match an explanation of the thing it is looking for (the documented
        // prose-matching trap).
        key: () -> SecretKey = { AuthorityKeys.authorityKey() },
        dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
    ): Open {
        handles[keyOf(filesDir, sessionId)]?.let { return Open.Ok(it) }
        if (!authorityExists(filesDir, sessionId)) return Open.Unavailable("no_authority")
        return open(filesDir, sessionId, null, key, dirSync)
    }

    /**
 * THE REMOVAL-PATH DOOR — the one lookup that MAY CREATE an authority, and
 * only for a session whose trustworthy surviving records are about to be
 * REMOVED (store review R3, binding ruling 2026-09-25: "surviving
 * trustworthy pre-F2 state MUST be adopted before removal").
 *
 * TWO DOORS, TWO NAMES, ON PURPOSE. [openExisting] is the READ-ONLY
 * door: a lookup, a gate or a delivered-set read with no intent to remove
 * must leave the file system byte-identical, and it does. This door exists
 * because a drain that DELIVERS or PARKS the last records of a pre-F2
 * session Y and then removes them destroys the only local evidence that Y
 * ever allocated a sequence — after which a later `start(sessionId = Y)`
 * finds an empty queue, genesis-es at -1 and re-issues sequence 0: exactly
 * the reuse class F2 exists to prevent, reached through the non-creating
 * door doing its job. A read-only lookup answered "no authority" and the
 * bytes went. The fix is not to make the lookup create (that reintroduces
 * blocker 3 of ccdbec35); it is a SEPARATE door whose name says what it
 * may do, called from the ONE place that is about to remove.
 *
 * Order, and every step is load-bearing:
 *
 * 1. NON-CREATING LOOKUP FIRST — a handle already open in this process
 * was reconciled at ITS open and owns the session; nothing is re-run.
 * Re-running `reconcileWith` on a LIVE session would race the
 * producer: an admission is PREPARED before its record is appended,
 * and a reconcile between those two would settle it LOST for a record
 * that arrives a moment later.
 * 2. AUTHORITY EXISTS ON DISK, NO HANDLE → open it and RECONCILE with the
 * trustworthy surviving view of Y (the existing contract: settle what
 * the queue proves, disclose what it cannot, disable if the queue is
 * ahead of the authority). Creates nothing.
 * 3. NO AUTHORITY, AND TRUSTWORTHY SURVIVING Y RECORDS EXIST → CREATE and
 * ADOPT them through the existing adoption contract (`adopt`): the
 * adopted high-water is at least the maximum surviving sequence, every
 * readable record is attributed canonically, an unknown event type
 * disables the session DURABLY (no logs fallback, admission refused
 * across a restart), and a lossy view disables rather than guesses.
 * 4. NO AUTHORITY AND NO SURVIVING Y RECORDS → `Unavailable`, and the file
 * system is byte-identical. This is the IRREDUCIBLE case and it is
 * DISCLOSED, never papered over: a pre-F2 sid whose local queue was
 * already fully empty before F2 existed has no reconstructible
 * historical high-water and cannot be guaranteed locally. It cannot
 * arise on the removal path (the slice being removed IS a surviving
 * record of Y), so a caller reaching it here is not removing.
 *
 * The adoption source is EXACTLY the per-session queue view the existing
 * contract requires — [queue] is the LIVE active queue as the caller read
 * it; this door filters it to Y's records itself and derives trustworthiness
 * from the whole read. Never another session, never the current session's
 * state, never a server assumption, never a local counter.
 *
 * Whether the adoption is DURABLE is the caller's next question, answered
 * by the handle: [Handle.isFenced] and [Handle.recoveryWritesRefused] — a
 * refused or indeterminate adoption write means the last adoptable state
 * has NOT been captured, and the caller must not remove.
 */
    @JvmOverloads
    public fun openForRemoval(
        filesDir: File,
        sessionId: String,
        queue: QueueSnapshot,
        // SS H.1 — the authority-v2 domain. `AuthorityKeys` records why the QUEUE
        // key SYMBOL is deliberately not migrated, renamed or re-pointed; the
        // symbol is not named here, because a source pin over this file must not
        // match an explanation of the thing it is looking for (the documented
        // prose-matching trap).
        key: () -> SecretKey = { AuthorityKeys.authorityKey() },
        dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
    ): Open {
        // 1. non-creating lookup first — a handle already RECONCILED in this
        // process owns the session. A cached handle the read-only door
        // opened is NOT reconciled; it falls through to `open`, which
        // reconciles it in place (see `reconcileCached`).
        handles[keyOf(filesDir, sessionId)]?.let { h -> if (h.reconciledWithQueue) return Open.Ok(h) }
        // 2. an authority on disk: reconcile it with the surviving view (creates nothing)
        if (authorityExists(filesDir, sessionId)) return open(filesDir, sessionId, queue, key, dirSync)
        // 3./4. no authority: adopt ONLY when trustworthy-readable Y records survive
        val mine = RecordFields.forSession(queue.records, sessionId)
        if (mine.isEmpty()) {
            RecoveryTrace.log("authority_removal_adoption_refused", "why" to "no_adoptable_state", "session" to sessionId.take(24))
            return Open.Unavailable("no_authority_no_adoptable_state")
        }
        RecoveryTrace.log("authority_removal_adoption", "session" to sessionId.take(24), "survivors" to mine.size)
        return open(filesDir, sessionId, queue, key, dirSync)
    }

    /** The already-open handle for [sessionId], or null. Never opens — a cheap check for callers that must not re-read the queue per record. */
    public fun peek(filesDir: File, sessionId: String): Handle? = handles[keyOf(filesDir, sessionId)]

    /**
 * Does a durable authority for [sessionId] ALREADY exist on disk?
 *
 * THE PRECONDITION THAT MAKES A READ-ONLY LOOKUP SAFE TO OPEN. `open`
 * WRITES a genesis snapshot when the directory is absent, so a read that
 * opened unconditionally would manufacture an authority for every inherited
 * pre-F2 session a drain touches. An absent directory also has nothing to
 * tell us, so refusing to open one costs no information.
 */
    public fun authorityExists(filesDir: File, sessionId: String): Boolean =
        EncryptedAuthorityStore.dirFor(filesDir, sessionId)
            ?.let { EncryptedAuthorityStore.authorityFilesPresent(it) } ?: false

    /**
 * THE ONE MUTATION GATE, for the EXACT slice about to be removed.
 *
 * PER SLICE, PER SESSION — never the current session, never the drain's
 * head session, never a process-wide bit. The owning session is derived from
 * the records' OWN bytes, and only that session's authority is consulted;
 * every other session's state is irrelevant to whether THESE bytes may go.
 * Both production call sites previously used a single unrelated authority,
 * so one session's pending state froze reconciliation for all of them.
 *
 * AND IT IS THE LOSSY-READ FENCE ONLY (`mutationFenced`), never the
 * diagnostic union. A clean `UNRESOLVED_ABSENT` is an obligation a later
 * phase owes, not a reason the evidence on disk is in danger; treating the
 * two alike is what made an ordinary offline backlog undrainable.
 *
 * IT NEVER OPENS AN AUTHORITY. `peek` only: `open` WRITES a genesis
 * snapshot when a directory is absent, so opening from here would manufacture
 * an authority for every inherited pre-F2 session a drain touches — a write
 * performed to answer a read-only question. A session with no live authority
 * in this process therefore contributes no fence, which is the honest answer:
 * a fence is one authority's statement that ITS OWN history could not be read,
 * and an authority that was never opened has made no such statement.
 */
    public fun mutationFenceFor(filesDir: File, slice: List<ByteArray>): List<Long> {
        if (slice.isEmpty()) return emptyList()
        val out = ArrayList<Long>()
        for (sid in RecordFields.sessionIds(slice)) {
            val h = peek(filesDir, sid) ?: continue
            out.addAll(h.mutationFenced())
        }
        return out
    }

    // ───────────────────────────────────────────── F4-P0 Slice D (D2): the complete scan

    /** One unresolved eviction intent as the scan reports it. Facade types only — no core type crosses (B10). */
    public data class UnresolvedIntent(val sessionId: String, val intentId: String, val sequence: Long)

    /**
 * The scan's verdict. TWO ARMS, AND THE SECOND IS NOT AN EMPTY LIST.
 * `Incomplete` means at least one authority could not be CONCLUSIVELY
 * inspected, so "no unresolved intent was seen" is not a fact this result
 * can state — a recovery must keep its marker and its fence on it.
 */
    public sealed class EvictionScan {
        public data class Complete(val intents: List<UnresolvedIntent>, val inspected: Int) : EvictionScan()
        public data class Incomplete(val reason: String, val inspected: Int) : EvictionScan()
    }

    /**
 * **THE COMPLETE / INCOMPLETE AUTHORITY SCAN (RF4-42, the capture specification).** Every
 * session directory under the authority root, READ-ONLY through
 * `EncryptedAuthorityStore.inspect` — never `open`, so the scan can never
 * become the destructive writer it exists to detect, and it creates no
 * genesis for a session it merely looked at.
 *
 * | inspect result | verdict |
 * |-------------------------------------------------|--------------|
 * | `Absent`, `Pruned`, `Ok` without interior corruption | conclusive |
 * | `Ok` WITH `interiorCorruption` | INCOMPLETE |
 * | `Failed(…)` incl. `dir_name_undecodable` | INCOMPLETE |
 * | key unavailable | INCOMPLETE |
 * | root exists but cannot be listed | INCOMPLETE |
 *
 * UNREADABLE IS NEVER EMPTY. The first wrong implementation of a scan is
 * the one that skips a directory it cannot read and reports the rest; that
 * directory is exactly where a dead process's intent may be. A root that
 * does not exist at all IS conclusive (no authority was ever created, so
 * no intent can exist) — the distinction from an unlistable root is made
 * on `exists`, not on `listFiles` returning null for both.
 *
 * The scan takes no lock of its own. Its caller holds the queue domain
 * region, which excludes every eviction writer (a bracket runs inside a
 * region on the same domain, in this and in every other process), so no
 * `EvictionIntent` can be appended while the scan reads; ordinary
 * admissions of a live session may run concurrently and are irrelevant to
 * what the scan looks for. A frame mid-append reads as a torn tail, which
 * is a disclosed note, never an interior corruption.
 */
    @JvmOverloads
    public fun scanUnresolvedEvictions(
        filesDir: File,
        // NO DEFAULT, ON PURPOSE. The three the specification entry DOORS carry the
        // authority-key default (H6 pins exactly three); the scan is not a door
        // it opens nothing — so production supplies the key through
        // [scanUnresolvedEvictionsAtOpen] and a test supplies its own.
        key: () -> SecretKey,
        dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
    ): EvictionScan {
        val root = EncryptedAuthorityStore.rootFor(filesDir)
        if (!root.exists()) return EvictionScan.Complete(emptyList(), 0)
        val k = runCatching(key).getOrElse { t ->
            return EvictionScan.Incomplete("key_unavailable:${AuthorityKeys.DOMAIN}:${t.javaClass.simpleName}", 0)
        }
        val entries = root.listFiles() ?: return EvictionScan.Incomplete("root_unlistable", 0)
        val out = ArrayList<UnresolvedIntent>()
        var inspected = 0
        for (d in entries.sortedBy { it.name }) {
            if (!d.isDirectory) continue
            inspected++
            when (val i = EncryptedAuthorityStore.inspect(d, null, k, dirSync)) {
                is EncryptedAuthorityStore.Inspect.Absent, is EncryptedAuthorityStore.Inspect.Pruned -> Unit
                is EncryptedAuthorityStore.Inspect.Failed -> {
                    RecoveryTrace.log("eviction_scan_incomplete", "why" to i.reason.take(40), "dir" to d.name.take(24))
                    return EvictionScan.Incomplete("inspect_failed:${i.reason}", inspected)
                }
                is EncryptedAuthorityStore.Inspect.Ok -> {
                    if (i.inspection.interiorCorruption) {
                        RecoveryTrace.log("eviction_scan_incomplete", "why" to "interior_corruption", "dir" to d.name.take(24))
                        return EvictionScan.Incomplete("interior_corruption:${i.inspection.machine.sessionId.take(24)}", inspected)
                    }
                    val m = i.inspection.machine
                    for ((id, seq) in m.unresolvedEvictionIntents()) out.add(UnresolvedIntent(m.sessionId, id, seq))
                }
            }
        }
        return EvictionScan.Complete(out, inspected)
    }

    /** The production scan: the authority-v2 key, the platform directory sync. What both producers and the erasure flow call. */
    public fun scanUnresolvedEvictionsAtOpen(filesDir: File): EvictionScan =
        scanUnresolvedEvictions(filesDir, { AuthorityKeys.authorityKey() })

    // ───────────────────────────────────────── F4-P2: the read-only PER-SESSION INDEX scan

    /**
 * One session's persisted index, as the P2 reconciler needs it. **Facade
 * types only — no core type crosses the module boundary (B10).**
 */
    public data class SessionIndex(
        val sessionId: String,
        /** ADMITTED, undelivered, and therefore owed a settlement. */
        val admittedUndelivered: List<Long>,
        /** PREPARED: an admission whose record may never have reached the queue. */
        val prepared: List<Long>,
        /** Already settled LOST — a governed F4 loss or an F2 one; never re-attributed. */
        val settledLost: List<Long>,
        /** Delivered, by the authority's own terminal record. */
        val delivered: List<Long>,
        /** The durable disable reason's NAME, or null. */
        val disabled: String?,
        /** Unresolved eviction/quarantine intents: an absence another path explains. */
        val unresolvedIntents: Int,
        /** F4-P2 (R11): the durable OWNER of an owner-bound disable, or null (none, or a legacy monotonic one). */
        val disabledOwner: String?,
        /** The durable `HighWaterConfidence` NAME the disable reason classifies to (RULING A2), or null when not disabled. */
        val highWaterConfidence: String?,
        /** The durable high-water — condition 9 of the R9 clear is "unchanged". */
        val highWater: Long,
        /** EVERY owned sequence -> its PERSISTED family (D1-6): the reconciler's only family source, and its "is this sequence indexed" set. */
        val families: Map<Long, String>,
        /** False when any owned entry carries a family outside the canonical vocabulary — an undecodable index. */
        val familiesCanonical: Boolean,
    )

    /** The scan's verdict. INCOMPLETE is never an empty list — see [scanUnresolvedEvictions]. */
    public sealed class IndexScan {
        public data class Complete(val sessions: List<SessionIndex>) : IndexScan()
        public data class Incomplete(val reason: String) : IndexScan()
    }

    /**
 * **F4-P2 (F4-16; a product decision) — EVERY SESSION'S PERSISTED INDEX,
 * READ-ONLY, COMPLETE OR NOT AT ALL.**
 *
 * The reconciler's arithmetic is `missing = unsettled − survivors` PER
 * SESSION, and its only legitimate source for a sequence's family, phase and
 * delivery is the durable index — never the record bytes, never a log. This
 * is the ONE enumeration of the authority root for that purpose, and it lives
 * HERE because the facade owns `EncryptedAuthorityStore`: a scan in
 * `internal/queue` would be a second home for authority-directory knowledge
 * (and `ModuleBoundaryTest` B9 pins that the store's name never leaves this
 * package).
 *
 * Like [scanUnresolvedEvictions] it goes through `inspect`, never `open`,
 * so it creates no genesis for a session it merely looked at; an unreadable
 * or interior-corrupt authority is INCOMPLETE, never "nothing owed" — that
 * directory is exactly where the missing sequence may be owned (O-1). A root
 * that does not exist is Complete-and-empty: no authority was ever created.
 *
 * It takes no lock of its own; the caller holds the queue domain region.
 */
    @JvmOverloads
    public fun scanSessionIndexes(
        filesDir: File,
        // NO DEFAULT, for the same reason the eviction scan has none: a scan is not a door.
        key: () -> SecretKey,
        dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
    ): IndexScan {
        val root = EncryptedAuthorityStore.rootFor(filesDir)
        if (!root.exists()) return IndexScan.Complete(emptyList())
        val k = runCatching(key).getOrElse { t ->
            return IndexScan.Incomplete("key_unavailable:${AuthorityKeys.DOMAIN}:${t.javaClass.simpleName}")
        }
        val entries = root.listFiles() ?: return IndexScan.Incomplete("root_unlistable")
        val out = ArrayList<SessionIndex>()
        for (d in entries.sortedBy { it.name }) {
            if (!d.isDirectory) continue
            when (val i = EncryptedAuthorityStore.inspect(d, null, k, dirSync)) {
                is EncryptedAuthorityStore.Inspect.Absent, is EncryptedAuthorityStore.Inspect.Pruned -> Unit
                is EncryptedAuthorityStore.Inspect.Failed -> {
                    RecoveryTrace.log("index_scan_incomplete", "why" to i.reason.take(40), "dir" to d.name.take(24))
                    return IndexScan.Incomplete("inspect_failed:${i.reason}")
                }
                is EncryptedAuthorityStore.Inspect.Ok -> {
                    if (i.inspection.interiorCorruption) {
                        RecoveryTrace.log("index_scan_incomplete", "why" to "interior_corruption", "dir" to d.name.take(24))
                        return IndexScan.Incomplete("interior_corruption:${i.inspection.machine.sessionId.take(24)}")
                    }
                    val m = i.inspection.machine
                    val admitted = ArrayList<Long>(); val prepared = ArrayList<Long>()
                    val lost = ArrayList<Long>(); val delivered = ArrayList<Long>()
                    for (e in m.ownedSequences().values) {
                        when (e.phase) {
                            SequenceAuthority.Phase.ADMITTED ->
                                if (m.isDelivered(e.sequence)) delivered.add(e.sequence) else admitted.add(e.sequence)
                            SequenceAuthority.Phase.PREPARED -> prepared.add(e.sequence)
                            SequenceAuthority.Phase.LOST -> lost.add(e.sequence)
                            SequenceAuthority.Phase.DELIVERED -> delivered.add(e.sequence)
                        }
                    }
                    val families = LinkedHashMap<Long, String>()
                    var canonical = true
                    for (e in m.ownedSequences().values) { families[e.sequence] = e.family; if (!com.tracescout.rn.LossFamilies.ALL.contains(e.family)) canonical = false }
                    out.add(SessionIndex(m.sessionId, admitted.sorted(), prepared.sorted(), lost.sorted(),
                        delivered.sorted(), m.disabled()?.name, m.unresolvedEvictionIntents().size,
                        m.disabledOwner(), m.disabled()?.highWaterConfidence()?.name, m.highWater(), families, canonical))
                }
            }
        }
        return IndexScan.Complete(out)
    }

    /** The production index scan: the authority-v2 key, the platform directory sync. What the reconciler calls. */
    public fun scanSessionIndexesAtOpen(filesDir: File): IndexScan =
        scanSessionIndexes(filesDir, { AuthorityKeys.authorityKey() })

    /**
 * **F7 R-2 CROSS-LAUNCH — the session ids that have a LIVE authority
 * directory on disk, other than [exclude].** A NAME LIST ONLY: it opens
 * nothing, decrypts nothing, creates nothing and never throws.
 *
 * **IT LIVES HERE BECAUSE THE LAYOUT DOES.** It needs the authority
 * ROOT, the directory encoding and the prune tombstone — all
 * `EncryptedAuthorityStore`'s, whose name `ModuleBoundaryTest` B9 confines
 * to this package. The first draft of this read sat in `internal/queue`
 * and would have broken that guard. The guard was right: a second file
 * that knows the on-disk layout is a second owner of it.
 *
 * **IT ANSWERS "WHICH SESSIONS", NEVER "WHICH ARE FINALIZED", AND THE
 * SPLIT IS THE POINT.** Reading terminality needs the authority KEY, and
 * the one resolver that already holds it is the attribution sink's own
 * non-creating door — the SAME door `f7SessionFinalized` uses. Answering
 * both questions here would mint a second key path and a second opinion on
 * whether a session ended; answering only the first leaves exactly one.
 *
 * **A PRUNED DIRECTORY IS NOT A SESSION.** The tombstone means the
 * authority files are gone and the floor is durable; proposing it would
 * send the caller to open something that no longer exists. And it cannot
 * hide a finalized session: `pruneOne` refuses a directory whose claim is
 * `terminal_requested_unacknowledged`, so an unsettled terminal claim is
 * still here, un-tombstoned, at the next launch.
 *
 * **THE NAME IS DECODED, NOT GUESSED.** `AuthorityNaming` encodes a
 * session id reversibly and refuses rather than truncating, so
 * [AuthorityNaming.decodeSessionDirName] is its documented inverse; a
 * directory whose name does not decode is skipped rather than approximated.
 */
    public fun sessionIdsOnDisk(filesDir: File, exclude: String): List<String> = try {
        val root = EncryptedAuthorityStore.rootFor(filesDir)
        if (!root.isDirectory) emptyList()
        else {
            // ONE PREDICATE, ONE EXCLUSION, ONE LISTING. The first version
            // counted with this predicate and then filtered with it again, and
            // excluded the caller's own session TWICE — once by directory name
            // here and once by decoded id afterwards. A negative control said
            // NOT CAUGHT and was right about the code, not about the suite:
            // with two exclusions neither is load-bearing, so neither can be
            // shown to matter. Now removing this one reddens `R2-12`.
            val mine = EncryptedAuthorityStore.dirFor(filesDir, exclude)?.name
            (root.listFiles() ?: emptyArray())
                .filter { it.isDirectory && !EncryptedAuthorityStore.isPruned(it) && it.name != mine }
                .sortedBy { it.name }
                .mapNotNull { AuthorityNaming.decodeSessionDirName(it.name) }
        }
    } catch (t: Throwable) {
        RecoveryTrace.log("authority_dir_list_failed", "why" to t.javaClass.simpleName)
        emptyList()
    }

    /**
 * F4-P0 Slice D (D6, U-7b) — the GOVERNED erasure of the authority home,
 * reached from exactly ONE production site: the erasure flow, under the
 * queue fence, AFTER the authority key has been destroyed.
 *
 * NOT [destroyAll], AND NOT A PRODUCTION DELETION OF A LIVE AUTHORITY.
 * RULING A1 forbids deleting the authority home in production because it
 * destroys the durable sequence floor a reused session id depends on.
 * That ruling protects READABLE state. Here the authority KEY is gone
 * (the caller asserts it), so every snapshot, WAL and the floors file are
 * ciphertext nothing can ever open again; keeping them would leave every
 * later complete scan INCOMPLETE forever (each directory inspects as
 * unreadable) — a permanent recovery wedge created by an erasure the host
 * explicitly asked for. An erasure is a fresh install; a fresh install has
 * no floors.
 */
    public fun eraseAuthorityHome(filesDir: File, authorityKeyDestroyed: Boolean): Boolean {
        if (!authorityKeyDestroyed) return false
        handles.clear()
        val root = File(filesDir, EncryptedAuthorityStore.ROOT_DIR)
        if (!root.exists()) return true
        root.deleteRecursively()
        return !root.exists()
    }

    /** Forget the process-wide handle (session end / reset). The directory stays. */
    public fun close(filesDir: File, sessionId: String) { handles.remove(keyOf(filesDir, sessionId)) }

    public fun closeAll() { handles.clear() }

    /** Destroy every authority directory — the test/teardown twin of `store.clear`. Never a production path. */
    public fun destroyAll(filesDir: File) {
        handles.clear()
        File(filesDir, EncryptedAuthorityStore.ROOT_DIR).deleteRecursively()
    }

    /**
 * Bound disk: keep the newest [RETAINED_SESSION_DIRS] session directories
 * plus every session in [keep].
 *
 * REWRITTEN FOR AUDIT ADDENDUM RULINGS A1 + A1b. The previous version
 * `deleteRecursively`d the whole directory, which destroyed the last
 * durable fact that the session had ever allocated — so a later
 * `start(sessionId = X)` genesis-ed at 0 and re-issued sequences the server
 * had already accepted. It also pruned on nothing but age, discarding
 * settlement obligations F5/F7 still own.
 *
 * A directory may now be deleted ONLY after BOTH:
 * - **nothing is owed** (A1b): no unresolved admission, no retained LOST
 * entry, no unresolved eviction, no requested-but-unacknowledged
 * terminal, and NO ENTRY OUTSIDE `DELIVERED`. An EMPTY QUEUE IS NOT
 * EVIDENCE, and is never consulted here.
 *
 * It is NOT "`owned` is empty". An earlier draft of this comment said
 * `owned` retains every phase except DELIVERED; it does not —
 * `SequenceAuthority.install` for a `DeliveredBatch` does
 * `owned[q] = it.copy(phase = DELIVERED)` and KEEPS the entry. Reading
 * this comment instead of that code made a fully settled, fully
 * delivered session unprunable forever. The comment was wrong, not the
 * machine.
 * - **the floor is durable** (A1): the session's own `highWater` has been
 * written to [SequenceFloorStore] and reported COMMITTED.
 *
 * Every other outcome SKIPS the directory. Keeping a directory costs disk;
 * deleting one can cost a duplicate sequence, and those are not comparable.
 */
    @JvmOverloads
    public fun prune(
        filesDir: File,
        keep: Set<String>,
        key: SecretKey,
        dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
    ) {
        val root = File(filesDir, EncryptedAuthorityStore.ROOT_DIR)
        // A directory that is already a tombstone holds no authority and takes
        // no retained slot — its mtime is the prune's, and counting it would
        // push a LIVE older session out of the retained set.
        val dirs = root.listFiles()?.filter { it.isDirectory && !EncryptedAuthorityStore.isPruned(it) } ?: return
        val keepDirs = keep.mapNotNull { EncryptedAuthorityStore.dirFor(filesDir, it)?.name }.toSet()
        val candidates = dirs.filter { it.name !in keepDirs }.sortedByDescending { it.lastModified() }
        for (d in candidates.drop(RETAINED_SESSION_DIRS)) {
            val why = pruneOne(filesDir, d, key, dirSync)
            if (why == null) RecoveryTrace.log("authority_pruned", "dir" to d.name.take(40))
            else RecoveryTrace.log("authority_prune_skipped", "dir" to d.name.take(40), "why" to why)
        }
    }

    /** null = pruned; otherwise the reason it was kept. */
    private fun pruneOne(filesDir: File, d: File, key: SecretKey, dirSync: DurableReplace.DirSync): String? {
        // READ-ONLY, AND THROUGH THE STORE'S OWN READER. `inspect` replays
        // the WAL as well as decoding the snapshot, and it writes nothing.
        //
        // The first version of this check read the SNAPSHOT ALONE. A snapshot is
        // a CHECKPOINT — every admission and settlement since the last
        // compaction lives in the WAL — so it saw an empty history and a
        // genesis high-water for sessions that had allocated five sequences and
        // still owed settlements: it deleted them AND recorded no floor. A
        // check that cannot see its own subject, in the exact place RULING A1
        // exists to protect.
        val i = EncryptedAuthorityStore.inspect(d, null, key, dirSync)
        val ins = when (i) {
            is EncryptedAuthorityStore.Inspect.Absent -> return "no_snapshot"
            is EncryptedAuthorityStore.Inspect.Pruned -> return "already_pruned"
            is EncryptedAuthorityStore.Inspect.Failed -> return "unreadable:${i.reason}"
            is EncryptedAuthorityStore.Inspect.Ok -> i.inspection
        }
        // An interior corruption means what was lost is UNKNOWABLE, so "nothing
        // is owed" is not a claim this state can support.
        if (ins.interiorCorruption) return "interior_corruption"
        val machine = ins.machine
        // A DISABLED SESSION IS NEVER PRUNED (D2 retention, guards review
        // 2026-09-25). `CheckpointDisabled` is the durable statement that this
        // session's history cannot be accounted; it is monotonic and no record
        // clears it. Deleting the directory would let a later open of the same
        // session id genesis a CLEAN authority above the floor — the disabled fact
        // gone, the claims it forbids allowed again. An empty queue is not evidence
        // here either: the disabled record outlives the queue by design.
        machine.disabled()?.let { return "disabled:${it.name}" }

        // A1b — owed state blocks pruning, whatever the queue looks like.
        val unresolved = machine.unresolvedAdmissions()
        val lost = machine.retainedLost()
        val evictions = machine.unresolvedEvictions()
        // THESE THREE ARE DIAGNOSTIC, NOT THE GUARD, and that was MEASURED:
        // removing either of the first two leaves the catch-all below to refuse
        // the same directory, so a control on them cannot fail (483/483 passed
        // for both). They exist to NAME the obligation in the trace, which is
        // what a skip reason is for. The catch-all is the guard; it has its own
        // control, whose only reachable subject is an ADMITTED-but-undelivered
        // entry — outside DELIVERED, yet neither PREPARED nor LOST.
        if (unresolved.isNotEmpty()) return "unresolved_admissions:${unresolved.size}"
        if (lost.isNotEmpty()) return "lost_pending:${lost.size}"
        if (evictions.isNotEmpty()) return "unresolved_evictions:${evictions.size}"
        // A DELIVERED ENTRY IS NOT OWED, AND IT IS STILL IN `owned`.
        // `install` for a DeliveredBatch does `owned[q] = it.copy(phase =
        // DELIVERED)` — it does not remove the entry — so "nothing owed" is
        // "no entry outside DELIVERED", never "owned is empty". Reading it as
        // emptiness made a fully settled, fully delivered session unprunable
        // forever, which is the same defect as pruning one that owes work,
        // pointing the other way. The two checks above name the SPECIFIC
        // obligation for the trace; this is the catch-all.
        val open = machine.ownedSequences().values.filter { it.phase != SequenceAuthority.Phase.DELIVERED }
        if (open.isNotEmpty()) return "retained_entries:${open.size}"
        // `terminalRequested` / `acknowledgedTerminal` are private state; the
        // snapshot value object is the machine's own public rendering of them.
        val snap = machine.snapshot(ins.generation)
        if (snap.terminalRequested && !snap.acknowledgedTerminal) return "terminal_requested_unacknowledged"

        // A1 — the floor must be durable BEFORE the directory can go.
        //
        // A session that never ALLOCATED needs no floor: its high-water is
        // the genesis sentinel, a row saying so states nothing, and it would
        // spend one of the store's bounded rows — making a real floor more
        // likely to be dropped. Reopening such a session at 0 is correct.
        if (machine.highWater() >= 0L) {
            val out = SequenceFloorStore.recordFloor(filesDir, machine.sessionId, machine.highWater(), key, dirSync)
            if (out != DurableReplace.Outcome.COMMITTED) return "floor_not_durable:${out.name}"
        }
        // THE TOMBSTONE IS WRITTEN BEFORE THE AUTHORITY FILES GO, and the
        // directory itself is KEPT. "This session id was seen and pruned" then
        // survives outside the floors file, which is what lets an unreadable
        // floors file be answered per session instead of per install. A marker
        // that cannot be made durable keeps the directory exactly as it is.
        if (!writePrunedMarker(d, dirSync)) return "pruned_marker_not_durable"
        val files = d.listFiles()?.filter { it.isFile && it.name != EncryptedAuthorityStore.PRUNED_MARKER } ?: emptyList()
        for (f in files) if (!f.delete()) return "delete_failed:${f.name.take(32)}"
        return null
    }

    private fun writePrunedMarker(d: File, dirSync: DurableReplace.DirSync): Boolean = try {
        val m = File(d, EncryptedAuthorityStore.PRUNED_MARKER)
        java.io.RandomAccessFile(m, "rw").use { raf -> raf.setLength(0); raf.fd.sync() }
        dirSync.sync(d) && m.isFile
    } catch (_: Throwable) { false }

    /**
 * Minimal field reads over the producer's OWN serialized records —
 * sessionId / sequence / eventType only.
 *
 * ONE READER FOR THE THREE FACTS (blocker 2, SF-7). These were read
 * THREE ways — `org.json` in the pipeline's `recordSequences`, `org.json`
 * in `sessionOwnedPrefix`, and REGEXES here — and the readers disagreed:
 * `optLong` reads the TOP-LEVEL key and coerces `"7"`, while the regex took
 * the first match anywhere in the text and required bare digits. A record
 * one reader accepted and the other did not was invisible to reconcile and
 * its PREPARED entry was settled LOST. Every reader in `:sdk` now goes
 * through [parse]: top-level keys, a numeric `sequence`, a non-empty
 * `sessionId`, or NOTHING — never a partial answer.
 */
    internal object RecordFields {
        data class Row(val sessionId: String, val sequence: Long, val eventType: String?)

        fun parse(record: ByteArray): Row? {
            val o = try { org.json.JSONObject(String(record, Charsets.UTF_8)) } catch (_: Throwable) { return null }
            val sid = o.optString("sessionId", "")
            if (sid.isEmpty()) return null
            val seq = sequenceOf(o) ?: return null
            val type = o.optString("eventType", "").ifEmpty { null }
            return Row(sid, seq, type)
        }

        /**
 * The record's OWN stamped sequence, owner or no owner — the one place a
 * sequence is read without requiring a session (the batch identity's
 * range). The numeric rule is the same one [parse] uses: a top-level
 * integer ≥ 0, never a quoted number, never a nested key.
 */
        fun sequenceOf(record: ByteArray): Long? =
            try { sequenceOf(org.json.JSONObject(String(record, Charsets.UTF_8))) } catch (_: Throwable) { null }

        private fun sequenceOf(o: org.json.JSONObject): Long? {
            if (!o.has("sequence") || o.isNull("sequence")) return null
            val seq: Long = when (val seqAny = o.opt("sequence")) {
                is Int -> seqAny.toLong()
                is Long -> seqAny
                is Number -> { val d = seqAny.toDouble(); if (d != Math.floor(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) return null; d.toLong() }
                else -> return null
            }
            return if (seq < 0) null else seq
        }

        /** How many of [records] cannot state an owner and a sequence. */
        fun unreadable(records: List<ByteArray>): Int = records.count { parse(it) == null }

        fun forSession(records: List<ByteArray>, sessionId: String): List<Row> =
            records.mapNotNull { parse(it) }.filter { it.sessionId == sessionId }

        fun sessionIds(records: List<ByteArray>): Set<String> = records.mapNotNull { parse(it)?.sessionId }.toSet()
    }
}

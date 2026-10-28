package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. It is `public` only because Kotlin `internal` is
 * compilation-module scoped and every consumer (`:sdk`) lives across the
 * module boundary. a conformance test pins that no type
 * here ever appears in a public API signature of `:sdk` / `:rn` / rn-core.
 */
/**
 * **F3-P1b — THE ONE COMPARISON HOME: the pure binder of a parsed
 * `loss-checkpoint-ack-1` to the DURABLE pending checkpoint (F3-P1b the specification).**
 *
 * A PURE function: no IO, no `androidx`, no clock, no randomness, no logging, no file,
 * no store handle. Given the same inputs it returns the same decision, on any platform,
 * in a JVM unit test with no Android runtime.
 *
 * ## Every match condition lives HERE and nowhere else (P1b-17, OCH-1)
 *
 * | # | condition |
 * |---|---|
 * | M-1 | a pending checkpoint exists |
 * | M-2 | the ack's `sessionId` is the session whose authority is being settled |
 * | M-3 | `state` ∈ { `accepted`, `duplicate` } — `older` is a REAL deployed state and settles NOTHING |
 * | M-4 | `protocol == "loss-checkpoint-ack-1"` and `lossAccountingVersion == LOSS_ACCOUNTING_VERSION` |
 * | M-5 | `acceptedThroughSequence` is a CANONICAL decimal string AND equals `pending.throughSequence` — asserted on the string and on the parsed value, and both must agree |
 * | M-6 | `acceptedCounts` is canonical-equal to `pending.frozenCounts` (rule stated below) |
 * | M-7 | terminal is CARRIED, NOT MATCHED (RULING Q1) |
 *
 * The facade writer (`LossAuthority.Handle.settleCheckpoint`), the attribution seam and
 * the pipeline call site do ROUTING, ORDERING and the APPEND CALL only. A copy of any of
 * these predicates there would agree with this one right up until one of them was edited
 * the ordinary edit (a new `state` value, a version bump, a canonical-counts fix) —
 * and a copy that agrees is invisible to every behavioural proof. V6 the specification: *"duplication
 * in a facade writer is forbidden"*; RULING R2: *"ONE semantic rule in core … no facade
 * duplicate"*.
 *
 * ## The decline is POLICY, not a core refusal
 *
 * [Decision.Declined] answers *"should we write a record at all?"* — asked once, live.
 * Core's `SequenceAuthority.validate(AckApplied)` answers *"is this durable record
 * consistent with the durable pending row?"* — asked again at every replay, forever.
 * The two are NOT redundant and neither substitutes for the other: this binder makes an
 * unbound `AckApplied` STRUCTURALLY UNCONSTRUCTIBLE on the live path (there is no
 * source for `settlesCheckpointId` here except `pending.checkpointId`, D1-b), while core's
 * `ack_unbound_checkpoint` stays untouched for replay. No token of core's refusal
 * vocabulary is emitted, duplicated, wrapped or re-worded here.
 *
 * ## M-6 — what "canonical-equal" means, stated rather than inherited
 *
 * The deployed server sends FULL counts (`fullCounts(committed.counts)`, MEASURED); the
 * device's frozen claim may be a SUBSET (the request writer accepts any canonical key
 * set). So the comparison is over the canonical family vocabulary with ONE stated
 * asymmetry:
 *
 * ```
 * frozen side an ABSENT family reads as ZERO (an under-specified claim)
 * ack side an ABSENT family is a DECLINE (the contract says full)
 * either side a NON-CANONICAL family is a DECLINE (never dropped, never coerced)
 * ```
 *
 * `LinkedHashMap.equals` would decide this by whatever the wire happened to send; this
 * decides it by the vocabulary.
 *
 * ## M-7 — terminal is CARRIED (RULING Q1, the specification)
 *
 * The deployed `duplicate` ack reports the server's STORED terminality, so a checkpoint
 * frozen with `claimsTerminal = false` and re-sent after the session became terminal is
 * answered `duplicate / terminal = true` — an honest ack for exactly the checkpoint it
 * answers, whose `terminal` legitimately differs from the claim. Strict equality and
 * either implication would decline it, the pending row would never clear, and every
 * later request would be refused `checkpoint_already_pending` forever. So the binder
 * compares `ack.terminal` against NOTHING, carries it verbatim into
 * `AckApplied.acknowledgedTerminal` and into the R4 identity, and never reads
 * `pending.claimsTerminal` — which is exactly why [Pending] does not carry it.
 *
 * ## No `disabled` gate (PLAN D2, P1b-15)
 *
 * Settlement on a disabled session is a fact about the SERVER's commit and is not
 * refused here; and settlement never rehabilitates — the `CheckpointDisabled` marker is
 * set-once in core and this binder has no input for it at all.
 */
public object CheckpointAckBinder {

    /** The deployed protocol identifier of the loss-checkpoint acknowledgement (M-4). */
    public const val LOSS_CHECKPOINT_ACK: String = "loss-checkpoint-ack-1"

    /**
 * **RULING Q2 — the core-owned comparand for `lossAccountingVersion` (M-4).**
 *
 * Provenance, all MEASURED at the accepted P1a base and on the running container:
 * canonical `mobile-protocol/src/loss-families.cjs` (`LOSS_ACCOUNTING_VERSION = 1`),
 * `mobile-protocol/src/loss-checkpoint.schema.json` (`lossAccountingVersion.enum = [1]`),
 * the deployed `/app/src/services/mobile/protocol/mobileLossFamilies.js` (`= 1`), and
 * the RN in-memory ledger's own constant (`= 1`).
 *
 * DELIBERATELY NOT READ OFF THE RN `LossLedger` OBJECT. The durable Android
 * authority's contract must not route through the RN in-memory ledger that R5 keeps
 * it apart from, and a value read from a different authority is a third home for one
 * number. Three parity pins hold the four homes together: the canonical
 * `mobile-protocol` files (repo-relative), a TEST-ONLY RN parity assertion, and the
 * existing API↔SDK guard on this host. The storage versions
 * (`AuthorityCodec.FORMAT_VERSION`, `AuthorityContainer.CONTAINER_VERSION`) mean
 * something else and are not this constant.
 */
    public const val LOSS_ACCOUNTING_VERSION: Int = 1

    /** The two states that settle. Any other state — `older` included — settles nothing. */
    public const val STATE_ACCEPTED: String = "accepted"
    public const val STATE_DUPLICATE: String = "duplicate"

    /**
 * The canonical decimal form of an int64 ordering key: `0` or a digit string with no
 * leading zero, at most 19 digits. Above 2^53 a JSON NUMBER is lossy, and this is the
 * value that decides which interval is settled — so only the STRING form is ever
 * admitted, and `"0007"`, `"+7"`, `" 7"` and `""` are refused as non-canonical.
 */
    private val CANONICAL_SEQ: Regex = Regex("^(0|[1-9][0-9]{0,18})$")

    /** The parsed ack's VALUES — transport-owned DTOs are mapped to this at the facade. */
    public data class Ack(
        val protocol: String,
        val lossAccountingVersion: Int,
        val state: String,
        val sessionId: String,
        /** `null` = the server accepted no boundary (a REAL deployed shape). Never a match. */
        val acceptedThroughSequence: String?,
        val acceptedCounts: Map<String, Long>,
        val terminal: Boolean,
    )

    /**
 * The DURABLE pending row's values, as `Handle.pendingCheckpoint` returns them
 * (stored frozen values, recomputed from nothing — D1-a).
 *
 * `claimsTerminal` IS DELIBERATELY ABSENT. It participates in nothing on the settle
 * path (RULING Q1): not a match input, not an identity input, not rewritten. A binder
 * that cannot read it cannot be "tightened" into comparing it.
 */
    public data class Pending(
        val checkpointId: String,
        val throughSequence: Long,
        val frozenCounts: Map<String, Long>,
    )

    public sealed class Decision {
        /**
 * Bound to the durable pending row. Carries EXACTLY what the writer needs to
 * construct the record — and `settlesCheckpointId` is `pending.checkpointId`, from
 * no other expression (D1-b).
 */
        public data class Bound(
            val intentId: String,
            val settlesCheckpointId: String,
            val throughSequence: Long,
            /** Canonical FULL counts — the ack's counts rendered over the whole vocabulary. */
            val counts: Map<String, Long>,
            /** `ack.terminal`, carried (RULING Q1). */
            val acknowledgedTerminal: Boolean,
        ) : Decision()

        /**
 * A WRITER/FACADE POLICY answer — never a core refusal, never core's vocabulary.
 * The reason names WHICH condition declined; it is one bounded token and carries
 * no wire value.
 */
        public data class Declined(val reason: String) : Decision()
    }

    /**
 * Decide, purely. [sessionId] is the session whose authority is being settled (the
 * handle's own binding, never a caller argument); [pending] is the durable row or
 * `null` when nothing is pending; [ack] is the parsed acknowledgement.
 */
    public fun bind(sessionId: String, pending: Pending?, ack: Ack): Decision {
        // M-1 — a pending checkpoint exists.
        if (pending == null) return Decision.Declined("no_pending_checkpoint")
        // M-2 — session equal.
        if (ack.sessionId != sessionId) return Decision.Declined("session_mismatch")
        // M-3 — a settling state. `older` is well-formed and settles nothing.
        if (ack.state != STATE_ACCEPTED && ack.state != STATE_DUPLICATE) {
            return Decision.Declined("state_not_settling")
        }
        // M-4 — protocol / version exact.
        if (ack.protocol != LOSS_CHECKPOINT_ACK) return Decision.Declined("protocol_mismatch")
        if (ack.lossAccountingVersion != LOSS_ACCOUNTING_VERSION) return Decision.Declined("version_mismatch")
        // M-5 — canonical, and equal to the frozen boundary on BOTH the string and the value.
        val through = ack.acceptedThroughSequence ?: return Decision.Declined("no_accepted_boundary")
        if (!CANONICAL_SEQ.matches(through)) return Decision.Declined("through_sequence_not_canonical")
        val parsed = through.toLongOrNull() ?: return Decision.Declined("through_sequence_not_canonical")
        if (through != pending.throughSequence.toString() || parsed != pending.throughSequence) {
            return Decision.Declined("through_sequence_mismatch")
        }
        // M-6 — canonical-equal counts, over the vocabulary, with the stated asymmetry.
        val canonical = when (val c = canonicalCounts(ack.acceptedCounts)) {
            is Counts.Full -> c.counts
            is Counts.NotCanonical -> return Decision.Declined("counts_family_not_canonical")
            is Counts.Negative -> return Decision.Declined("counts_negative")
            is Counts.NotFull -> return Decision.Declined("counts_not_full")
        }
        val frozenEncoded = try { LossCounts.encode(pending.frozenCounts) } catch (_: LossFormatException) {
            return Decision.Declined("counts_family_not_canonical")
        }
        val ackEncoded = LossCounts.encode(canonical)
        if (ackEncoded != frozenEncoded) return Decision.Declined("counts_mismatch")
        // M-7 — terminal is CARRIED. Nothing is compared.
        val settlesCheckpointId = pending.checkpointId
        val intentId = AckAppliedId.of(sessionId, settlesCheckpointId, through, ackEncoded, ack.terminal)
        return Decision.Bound(intentId, settlesCheckpointId, parsed, canonical, ack.terminal)
    }

    private sealed class Counts {
        class Full(val counts: Map<String, Long>) : Counts()
        object NotCanonical : Counts()
        object Negative : Counts()
        object NotFull : Counts()
    }

    /**
 * The ack's counts rendered over the canonical vocabulary in the shared order. A
 * family the wire did NOT name is [Counts.NotFull] — a decline, never a silent zero —
 * a family outside the vocabulary is [Counts.NotCanonical], and a negative value is
 * [Counts.Negative] — a count below zero is not a count.
 */
    private fun canonicalCounts(counts: Map<String, Long>): Counts {
        val full = LossCounts.zero()
        for ((f, v) in counts) {
            if (!full.containsKey(f)) return Counts.NotCanonical
            if (v < 0L) return Counts.Negative
            full[f] = v
        }
        if (counts.size != full.size) return Counts.NotFull
        return Counts.Full(full)
    }
}

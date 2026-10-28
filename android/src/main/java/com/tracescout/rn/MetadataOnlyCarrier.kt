package com.tracescout.rn

/**
 * **F7 SLICE A — THE NON-TERMINAL METADATA-ONLY CARRIER. PURE, CORE-OWNED, AND
 * THE ONE PLACE THE METADATA-ONLY BOUNDARY IS DERIVED.**
 *
 * Authority: frozen the capture specification (*"metadata-only carrier: the same
 * `/replay/chunks` route with `events: []`, under the metadata-only gap formula
 * (not the chunk-riding one)"*), the frozen the conformance tests Part 5 rows C16 and C17,
 * DECISION-LOG a product decision and a product decision, and the lead's
 * `an explicit product decision` / `an explicit product decision`.
 *
 * **WHY IT IS IN `mobile-loss-core` AND NOWHERE ELSE.**
 * `an explicit product decision` is explicit: *"Keep the F7 metadata-only
 * carrier in mobile-loss-core at `checkpointForMetadataOnly`; `:sdk` may
 * orchestrate/call it but `F7_METADATA_ONLY_CARRIER` is not widened into the
 * `:sdk` ceiling."* The stage-leak gate enforces exactly that — the category's
 * accepted sites may live only in this module — so the ARITHMETIC call lives
 * here and the orchestration (durable reads, the persisted claim, the transport)
 * lives in `:sdk`. A call from `:sdk` would be a stage leak, and the `:sdk`
 * widening ceiling stays at eleven categories because of this split.
 *
 * **IT OWNS EXACTLY ONE RULE: WHERE THE ADVANCE BOUNDARY IS.** Every
 * refusal and every count comes from [LossLedger.checkpointForMetadataOnly];
 * the document comes from `PendingCheckpoint.wire`, the ONE wire writer. The
 * single thing this adds is the derivation
 *
 * ```
 * newThrough = acknowledgedThrough + unacknowledgedLoss
 * ```
 *
 * which is the boundary at which the metadata-only gap formula closes
 * (`covered == declared`). It is derived HERE because the orchestrator must not
 * own arithmetic — a second home for this expression would be invisible to
 * every behavioural proof and would become visible only once core's rule
 * changed and one path kept answering.
 *
 * **SLICE A IS NON-TERMINAL BY CONSTRUCTION, NOT BY CONVENTION, AND THAT
 * IS WHAT MAKES an open product question UNREACHABLE FROM HERE.** `LossLedger.freeze` freezes
 * `ledger.terminal` into the snapshot, so a terminal ledger would produce a
 * document asserting `terminal: true` — the irreversible claim whose PRODUCT
 * TRIGGER is an open product question, open for the maintainer. This carrier therefore REFUSES a terminal
 * ledger before it computes anything, and refuses again if the frozen snapshot
 * asserts terminality. Two independent lines, so removing either leaves the
 * other; a `terminal: true` document is unproducible from this path rather than
 * merely not produced today.
 *
 * **THE FINALITY-ONLY SHAPE IS C18's AND IS REFUSED HERE.**
 * `checkpointForMetadataOnly` has two legitimate shapes — ADVANCE (the boundary
 * moves, every newly covered sequence is accounted) and FINALITY-ONLY (the
 * boundary stands still and only `terminal` becomes true). Slice A implements
 * the first. The second is C18, blocked on an open product question, and its signature is an ABSENT
 * first sequence; both the outstanding-loss guard and the explicit
 * `firstSequence == null` check refuse it.
 */
public object MetadataOnlyCarrier {

    /** The plan for ONE non-terminal metadata-only emission. */
    public sealed class Plan {
        /**
 * [document] is `PendingCheckpoint.wire`'s own map — the ONE wire
 * writer's output, unmodified. [claimsTerminal] is the value the frozen
 * snapshot carries and is `false` on every reachable path through
 * [advance]; it travels as a VALUE rather than as a literal at the call
 * site so the durable claim and the emitted document cannot disagree
 * about terminality.
 */
        public data class Emit(
            val throughSequence: Long,
            val declaredCounts: Map<String, Long>,
            val claimsTerminal: Boolean,
            val document: Map<String, Any>,
        ) : Plan()

        /** Nothing is emitted. A refusal mutates nothing and is never a chunk failure. */
        public data class Refused(val reason: String, val detail: String) : Plan()
    }

    /** The ledger holds, or the frozen snapshot asserts, a durable terminal claim — an open product question's subject. */
    public const val TERMINAL_OUT_OF_SCOPE: String = "terminal_out_of_scope"

    /** There is no loss after the acknowledged boundary, so there is no advance to disclose. */
    public const val NOTHING_TO_ADVANCE: String = "nothing_to_advance"

    /** The derived boundary would overflow int64. */
    public const val BOUNDARY_OVERFLOW: String = "boundary_overflow"

    /** Core produced a shape this slice does not own (finality-only, or a delivery kind). */
    public const val NOT_THE_ADVANCE_SHAPE: String = "not_the_advance_shape"

    /**
 * [finality] was asked for a session whose DURABLE LOCAL claim is not terminal.
 *
 * This is the precondition `LossLedger.freeze`'s own KDoc states — *"the
 * producer's claim must be durable BEFORE it is asserted: call `markTerminal`,
 * persist, then emit"* — enforced at the entry rather than discovered inside
 * core's finality branch, so a caller that has not recorded a terminal cannot
 * reach the arithmetic at all.
 */
    public const val TERMINAL_CLAIM_ABSENT: String = "terminal_claim_absent"

    /** Core produced a shape [finality] does not own (an ADVANCE, or a delivery kind). */
    public const val NOT_THE_FINALITY_SHAPE: String = "not_the_finality_shape"

    /**
 * Derive the non-terminal metadata-only ADVANCE for [ledger], or refuse.
 *
 * [ledger] MUST be a projection of the CURRENT durable authority taken at
 * call time (the F6 `projectLedger` contract, an explicit product decision the specification): this
 * function caches nothing, holds nothing between calls, and the value it is
 * handed is dead the moment the authority moves.
 *
 * Order is load-bearing and each step is the ONE place its question is asked:
 *
 * ```
 * 1 a durable terminal claim -> Refused
 * 2 no loss after the boundary -> Refused (an advance that advances nothing)
 * 3 the boundary would overflow -> Refused
 * 4 checkpointForMetadataOnly -> the ONLY arithmetic, the ONLY refusals
 * 5 the frozen shape is re-read -> ADVANCE, non-terminal, or Refused
 * ```
 */
    /*
 * THE TWO TERMINAL GATES BELOW ARE NOT INTERCHANGEABLE, AND ONE OF THEM
 * IS CURRENTLY UNREACHABLE — MEASURED, NOT ASSUMED. Deleting the unreachable
 * one as "dead code" is how the property is lost later, so the measurement
 * lives here rather than only in a review package.
 *
 * gate 1 = the DURABLE LEDGER read, step 1 below
 * gate 2 = the FROZEN SNAPSHOT read, step 5 below
 *
 * mutation core suite failing rows
 * ------------------ ------------ -------------------------------
 * gate 1 removed 318, 2 failed S2, Q3-2
 * gate 2 removed 318, 0 failed NONE <- unreachable while 1 stands
 * BOTH removed 318, 4 failed Q3-1, Q3-2, Q3-3, S2
 * clean 318, 0 failed —
 *
 * Gate 2 is unreachable because core derives the snapshot's finality FROM
 * the same durable ledger this function has already read, so a ledger that
 * passed gate 1 cannot produce a snapshot that trips gate 2. It is kept as a
 * MODULE-BOUNDARY refusal to trust that derivation: if core ever decided
 * finality from anything other than the ledger it is handed, gate 2 is the
 * only line that still refuses. The property it defends is never lost by a
 * single-line deletion — removing either one alone still refuses — and the
 * pair's removal is caught four ways.
 */
    @JvmStatic
    public fun advance(ledger: LossLedger.Ledger): Plan {
        // 1 — an open product question's SUBJECT, REFUSED FIRST. `freeze` takes `ledger.terminal`, so
        // asking anything else first would mean a terminal ledger had already
        // been measured against a boundary before this refused it.
        if (ledger.terminal) {
            return Plan.Refused(TERMINAL_OUT_OF_SCOPE,
                "the ledger holds a durable terminal claim; terminal emission is out of scope")
        }
        val outstanding = ledger.unacknowledgedLoss()
        // 2 — An ADVANCE must advance. With nothing outstanding the derived
        // boundary equals the acknowledged one, which is the FINALITY-ONLY shape
        // (C18) rather than this slice's.
        if (outstanding <= 0L) {
            return Plan.Refused(NOTHING_TO_ADVANCE,
                "outstanding=$outstanding at authority=${ledger.acknowledgedThrough}")
        }
        // 3 — Checked arithmetic. A corrupted durable ledger could otherwise wrap
        // the boundary and turn "covers N" into "covers everything".
        if (ledger.acknowledgedThrough > Long.MAX_VALUE - outstanding) {
            return Plan.Refused(BOUNDARY_OVERFLOW,
                "authority=${ledger.acknowledgedThrough} outstanding=$outstanding")
        }
        val newThrough = ledger.acknowledgedThrough + outstanding
        // 4 — THE ONLY ARITHMETIC. `covered === declared` lives in core's rule and
        // nowhere else; this neither pre-checks it nor repeats it.
        return when (val r = LossLedger.checkpointForMetadataOnly(ledger, newThrough)) {
            is LossLedger.CheckpointResult.Refused ->
                Plan.Refused("refused_" + r.reason.name.lowercase(), r.detail)
            is LossLedger.CheckpointResult.Emit -> {
                val p = r.pending
                // 5 — The frozen snapshot is re-READ, never re-decided. These are
                // assertions about what core produced, and each one closes a
                // different way a non-advance shape could reach the transport.
                if (p.kind != LossLedger.CheckpointKind.METADATA_ONLY) {
                    return Plan.Refused(NOT_THE_ADVANCE_SHAPE, "kind=${p.kind.name}")
                }
                if (p.firstSequence == null) {
                    return Plan.Refused(NOT_THE_ADVANCE_SHAPE,
                        "an absent first sequence is the finality-only shape, which is out of scope")
                }
                // The SECOND independent terminal line. Step 1 refuses a terminal
                // LEDGER; this refuses a terminal SNAPSHOT, so neither check is
                // load-bearing alone and removing one leaves the other.
                if (p.terminal) {
                    return Plan.Refused(TERMINAL_OUT_OF_SCOPE,
                        "the frozen snapshot asserts terminal; terminal emission is out of scope")
                }
                Plan.Emit(p.throughSequence, p.declaredCounts, p.terminal, r.checkpoint)
            }
        }
    }

    /**
 * Derive the FINALITY-ONLY metadata-only emission for [ledger], or refuse — the
 * C18 shape, and the SIBLING of [advance] rather than a branch inside it.
 *
 * Authority: frozen `C18.md` of the sealed certification plan (digest
 * `44888d93…`), frozen the capture specification (*"recorded once, never contradicted by
 * a later checkpoint"*), and the lead's option-A ruling
 * `an explicit product decision`: **only an explicit authoritative
 * session-end signal may terminalize.** an open product question asked for the TRIGGER; the semantics
 * below were frozen all along.
 *
 * **IT IS A SEPARATE FUNCTION SO THAT [advance] IS BYTE-UNCHANGED, AND THAT
 * IS A CORRECTNESS CHOICE, NOT TIDINESS.** C16 and C17 are FROZEN-ACCEPTED
 * (`an explicit product decision`) against [advance]'s exact behaviour.
 * Routing finality through [advance] would put the accepted non-terminal path and
 * the new terminal path in one body, so every future edit to either would be an
 * edit to both — and [advance]'s two an open product question refusals, which must keep refusing
 * terminality on the NON-TERMINAL path forever, would have to become conditional.
 * They stay unconditional. A terminal document is still unproducible from
 * [advance]; it is producible only from here, and only after a durable claim.
 *
 * **THE BOUNDARY STANDS STILL BY CONSTRUCTION.** The `newThrough` handed to
 * core is `ledger.acknowledgedThrough` ITSELF, which is the one input for which
 * `checkpointForMetadataOnly` takes its finality-only branch. There is no
 * arithmetic here at all — no `+ 1`, no `+ outstanding` — because *"no new
 * sequence exists, so nothing is accounted; only finality becomes known"*, and a
 * build that advanced the boundary to disclose finality would have fabricated a
 * sequence. [advance] owns the one derivation; this owns none.
 *
 * **EVERY REFUSAL C18's CONTROLS REQUIRE IS CORE'S, NOT A COPY HERE.** The
 * finality-only branch of `checkpointForMetadataOnly` already refuses an absent
 * boundary (`acknowledgedThrough < 0` — the `-1` ABSENCE sentinel, which must
 * never reach the wire), terminality already acknowledged at this boundary
 * (*"recorded ONCE"* is a client property too), grown counts
 * (`finality-only requires unchanged counts`), and an occupied pending slot.
 * Re-checking any of them here would be a second home for a rule core owns, free
 * to disagree with it.
 *
 * Order is load-bearing and each step is the ONE place its question is asked:
 *
 * ```
 * 1 no durable terminal claim -> Refused (the claim must precede the assertion)
 * 2 checkpointForMetadataOnly -> the ONLY arithmetic, the ONLY refusals
 * 3 the frozen shape is re-read -> FINALITY-ONLY, terminal, or Refused
 * ```
 */
    @JvmStatic
    public fun finality(ledger: LossLedger.Ledger): Plan {
        // 1 — THE PRECONDITION, AND IT IS READ FROM THE LEDGER, NEVER A PARAMETER.
        // `ledger.terminal` is projected from the authority's `terminalRequested`
        // the LOCAL durable claim written by `recordTerminal`. A boolean argument
        // here would let a caller assert terminality that was never persisted, and
        // a process death after that emission leaves a producer whose durable state
        // says non-terminal while the server has been told otherwise. Server
        // terminality is one-way, so that divergence is unrecoverable.
        if (!ledger.terminal) {
            // THE MESSAGE NAMES THE FACT, NOT THE `:sdk` WRITER THAT PRODUCES IT.
            // This module's production code has NO dependencies, so a `:sdk` symbol is
            // unresolvable here and naming one in prose is a layering claim core cannot
            // back. It also broke `S2`, whose substring scan cannot tell a string literal
            // from a call — the documented prose-matching trap, and a guard that fires on
            // correct code is how guards get deleted rather than fixed. The operator-facing
            // guidance about WHICH writer persists the claim belongs to the `:sdk` seam.
            return Plan.Refused(TERMINAL_CLAIM_ABSENT,
                "no durable terminal claim persisted; terminality must be durable before finality is asserted")
        }
        // 2 — THE ONLY ARITHMETIC, AND IT IS AN IDENTITY. `newThrough` IS the
        // acknowledged boundary, which selects core's finality-only branch.
        return when (val r = LossLedger.checkpointForMetadataOnly(ledger, ledger.acknowledgedThrough)) {
            is LossLedger.CheckpointResult.Refused ->
                Plan.Refused("refused_" + r.reason.name.lowercase(), r.detail)
            is LossLedger.CheckpointResult.Emit -> {
                val p = r.pending
                // 3 — The frozen snapshot is re-READ, never re-decided. These are the
                // MIRROR IMAGES of [advance]'s step 5: each asserts the shape this
                // function owns, so a snapshot belonging to the other shape cannot
                // reach the transport through the wrong door.
                if (p.kind != LossLedger.CheckpointKind.METADATA_ONLY) {
                    return Plan.Refused(NOT_THE_FINALITY_SHAPE, "kind=${p.kind.name}")
                }
                // An ABSENT first sequence IS the finality-only signature — the empty
                // covered interval, expressed as absence rather than as `through + 1`
                // (which overflows at Long.MAX_VALUE).
                if (p.firstSequence != null) {
                    return Plan.Refused(NOT_THE_FINALITY_SHAPE,
                        "a present first sequence is the ADVANCE shape (C17), not finality-only")
                }
                // The SECOND independent terminal line, pointing the opposite way to
                // [advance]'s. There, a terminal snapshot is refused; here, a
                // NON-terminal one is — so neither function can emit the other's
                // document even if step 1 were somehow satisfied wrongly.
                if (!p.terminal) {
                    return Plan.Refused(NOT_THE_FINALITY_SHAPE,
                        "the frozen snapshot does not assert terminal")
                }
                Plan.Emit(p.throughSequence, p.declaredCounts, p.terminal, r.checkpoint)
            }
        }
    }
}

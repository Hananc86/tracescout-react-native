package com.tracescout.mobile.internal.loss

import com.tracescout.mobile.queue.RecoveryTrace
import com.tracescout.rn.LossLedger
import com.tracescout.rn.MetadataOnlyCarrier

/**
 * **F7 SLICE A — THE NON-TERMINAL METADATA-ONLY ORCHESTRATOR. ONE PATH, SHARED
 * BY BOTH ANDROID PRODUCERS, OWNING NO ARITHMETIC AND NO SEMANTIC RULE.**
 *
 * Authority: frozen the capture specification, the frozen the conformance tests Part 5 rows C16
 * and C17, DECISION-LOG a product decision / a product decision, and the lead's
 * `an explicit product decision`,
 * `an explicit product decision` and
 * `an explicit product decision`.
 *
 * **THE OWNERSHIP SPLIT IS THE WHOLE DESIGN, AND IT IS A RULING.** The
 * CARRIER — the `LossLedger.checkpointForMetadataOnly` call, the advance-boundary
 * derivation and the non-terminal gate — lives in
 * `mobile-loss-core`'s [MetadataOnlyCarrier]. This object does the `:sdk`-only
 * work: read the DURABLE authority, consult the carrier, persist the claim, hand
 * back the document. `F7_METADATA_ONLY_CARRIER` is deliberately NOT in the
 * `:sdk` stage-leak widening ceiling (which stays at eleven categories), so a
 * `checkpointForMetadataOnly(` call in this file would be a stage leak and the
 * gate would fail. That is the mechanism, not a convention.
 *
 * **an open product question IS ANSWERED, AND THIS OBJECT STILL CANNOT TERMINALIZE ANYTHING.**
 * `an explicit product decision` fixes the PRODUCT TRIGGER: only an
 * explicit authoritative session-end signal may terminalize, and backgrounding,
 * a drained queue, process shutdown and a final flush may NOT. This object is
 * driven by exactly those events — the ordinary drain — so the separation is
 * what keeps the policy true, and it holds because:
 *
 * * it still never calls `LossAuthority.Handle.recordTerminal` — it names it
 * nowhere, and that absence is pinned. The ONE writer is the explicit seam
 * (`TraceScoutMobile.endSessionAuthoritatively`), which nothing in `src/main`
 * calls, so no lifecycle event can reach it;
 * * it still never writes a `terminal` value of its own. `claimsTerminal`
 * travels as a VALUE from the carrier's plan, so the durable claim and the
 * emitted document cannot disagree about terminality — and it is `true` only
 * when the ledger's own DURABLE `terminalRequested` is already true;
 * * it CHOOSES a shape, it does not create a fact: a terminal ledger is routed
 * to `MetadataOnlyCarrier.finality`, a non-terminal one to `advance`, which is
 * byte-unchanged and still refuses terminality twice. A `terminal: true`
 * document is unproducible from this object for a session that has not
 * explicitly ended, rather than merely not produced today.
 *
 * **AND IT RE-SENDS A DURABLE PENDING CLAIM THAT ASSERTS TERMINAL.** That
 * refusal was correct while the trigger was ungoverned and is a data-loss shape
 * now that it is governed — see the pending branch for the three independent
 * properties that make the re-send safe.
 *
 * **ONE PATH, BOTH PRODUCERS — the same reasoning as F6.**
 * `TraceScoutMobile` (Android Native) and `RnTransport` (RN Android) both
 * construct `DurableUploadPipeline` and share the `AttributionSink`;
 * `mobile-android/rn/src/main` holds ZERO references to `TraceScoutMobile`, so
 * an orchestrator anywhere else would cover one producer and silently skip the
 * other.
 *
 * **IT HOLDS NO STATE AT ALL.** No counters, no caches, no latch, no
 * per-session memo — so *"no second accumulator/cache"* is structural rather
 * than reviewed. F6's process-local no-spin latch exists because F6 is invoked
 * on every delivered chunk; Slice A has no automatic product trigger (see
 * [decide]'s note), so there is nothing for a latch to damp and adding one would
 * be state nothing exercises.
 */
internal object F7MetadataOnlyEmitter {

    /**
 * What one metadata-only emission attempt yields.
 *
 * [Omit] IS NOT A FAILURE. A refusal here is a statement about the loss
 * statement; nothing is removed, nothing is parked, nothing is quarantined,
 * and no record is touched — there are no records in a metadata-only
 * request.
 */
    internal sealed class Decision {
        /**
 * [json] is the exact `lossCheckpoint` document to send with
 * `events: []`. [chunkId] is the claim's own content-addressed identity,
 * reused as the request's chunk id so a retry of the SAME claim produces
 * byte-identical bytes; the metadata-only route reads no chunk id (the
 * deployed handler never consults it), so this is an identity for our
 * own determinism rather than a chunk that exists.
 */
        internal data class Emit(val json: String, val chunkId: String, val retry: Boolean) : Decision()
        internal data class Omit(val reason: String) : Decision()
    }

    /**
 * Decide whether this session may emit a non-terminal metadata-only
 * checkpoint right now, and persist the claim if so.
 *
 * Order is load-bearing and each step is the ONE place its question is asked:
 *
 * ```
 * 1 no authority wired -> Omit (a session with no durable authority
 * has no acknowledged boundary to advance)
 * 2 checkpointDisabled -> Omit (the capture specification's rule, applied here BEFORE
 * anything is projected or measured)
 * 3 a durable pending row -> RE-SEND IT BY VALUE; never a new claim
 * 4 project the ledger -> MetadataOnlyCarrier.advance (core: the ONLY
 * arithmetic and the ONLY refusals)
 * 5 Refused -> Omit, per-reason
 * 6 Emit -> requestCheckpoint, THEN hand back the bytes
 * ```
 *
 * **THERE IS NO AUTOMATIC PRODUCT TRIGGER IN SLICE A, DELIBERATELY.**
 * The frozen authority names none: the capture specification describes the CARRIER,
 * and a conformance test is *"the `events: []` carrier on `/replay/chunks` is
 * accepted and accounted under the metadata-only gap formula"* — a carrier
 * row, with no statement about when a producer should decide to disclose
 * loss with no chunk to ride. Choosing that moment is a product policy
 * decision of the same class as an open product question, so it is reported for a ruling rather
 * than invented here. Every behaviour the dispatch enumerates is implemented
 * and reachable through [DurableUploadPipeline.emitMetadataOnlyCheckpoint];
 * what a later dispatch adds is a call site.
 *
 * **PERSIST BEFORE SEND.** `CheckpointResult.Emit`'s own KDoc: the ledger
 * carrying the frozen `pending` *"MUST be the state persisted BEFORE the
 * request is sent — a pending checkpoint written after the request cannot
 * survive a crash during it."* So `requestCheckpoint` runs first and a
 * non-`Written` answer OMITS; nothing is handed to the transport on the
 * strength of a claim that is not durable.
 */
    /**
 * **Is this session's loss authority DURABLY FINALIZED?** The ONE read
 * behind the R-2 trigger:
 *
 * > *"metadata-only checkpoint ONCE at definitive session finalization when
 * > no replay chunk was durably emitted; use durable idempotency, retry
 * > retained record, no synthetic replay content."*
 *
 * IT READS THE DURABLE CLAIM AND CREATES NOTHING. `ledger.terminal` is
 * projected from the authority's own `terminalRequested`, which only the
 * explicit host seam persists. This function names no writer, takes no
 * boolean parameter and has no inverse — exactly the routing input [decide]
 * already reads one screen below, lifted so a CALLER can ask the question
 * *before* asking for a document. Asking it afterwards would be too late:
 * [decide] PERSISTS a claim before it hands the bytes back, so a caller
 * that filtered on shape after the fact would already have written an
 * advance claim the ruling does not authorise.
 *
 * FAIL-CLOSED, AND THE TWO FALSE ANSWERS ARE NOT THE SAME FACT. A
 * session with no authority, and one whose authority cannot be read, both
 * answer `false` — because the only safe default is to disclose nothing —
 * but the unreadable case is traced, so "nothing was final" and "we could
 * not tell" are distinguishable in the record even though they agree here.
 */
    internal fun sessionFinalized(authority: LossAuthority.Handle?): Boolean {
        val h = authority ?: return false
        return try {
            h.projectLedger().terminal
        } catch (t: Throwable) {
            RecoveryTrace.log("f7_finalized_unreadable", "detail" to t.javaClass.simpleName)
            false
        }
    }

    internal fun decide(authority: LossAuthority.Handle?): Decision {
        val h = authority ?: return Decision.Omit("no_authority")

        val disabled = try {
            h.disabled()
        } catch (t: Throwable) {
            return omit("authority_unreadable", t.javaClass.simpleName)
        }
        // the capture specification: a session marked `checkpointDisabled` emits NOTHING. Asked
        // before the ledger is projected, so a disabled session is not even
        // measured.
        if (disabled != null) return omit("checkpoint_disabled", disabled)

        // THE SINGLE-PENDING INVARIANT IS AUTHORITATIVE AND F7 ADDS NO
        // RECOVERY CONTRACT (`an explicit product decision`). A
        // metadata-only emission may not coexist with, replace, retire or
        // supersede an existing pending delivery checkpoint: while one is
        // pending, F7 emission WAITS — and the one thing it may do is re-send
        // the SAME frozen claim, by value, so a claim whose acknowledgement was
        // lost is not stranded for the life of the install.
        val pending = try {
            h.pendingCheckpoint()
        } catch (t: Throwable) {
            return omit("authority_unreadable", t.javaClass.simpleName)
        }
        if (pending != null) {
            // an open product question IS ANSWERED, SO A TERMINAL CLAIM IS RE-SENT — and the refusal
            // that used to stand here would now be a DATA-LOSS SHAPE, not caution.
            // `an explicit product decision` fixes the trigger: the claim
            // is already DURABLE, so the act an open product question gated has already been authorised
            // and performed by the explicit session-end signal. Refusing to re-send
            // it would strand a terminal claim whose acknowledgement was lost for the
            // life of the install, in a single-pending-slot design — the head-of-queue
            // starvation this programme has paid for twice (PARKED_PRODUCER, and the
            // F4-P2 parked-slice refusal loop).
            //
            // RE-SENDING IS SAFE BY THREE INDEPENDENT PROPERTIES, none of them new:
            // the document is RE-DERIVED from the persisted row through the ONE wire
            // writer, so it is byte-identical; the server's commit is one-way
            // (`!!prev.terminal || assessment.terminal`), so a duplicate terminal is
            // an idempotent `duplicate`, never a second transition; and the boundary
            // and counts travel from the frozen row, so nothing can advance.
            val doc = try {
                // RE-DERIVED, NEVER RE-DECIDED — every field is handed in from
                // the persisted row, through the ONE wire writer. FROZEN MEANS
                // FROZEN: no arithmetic, no interval check, no counts comparison.
                LossLedger.wireForDurablePending(
                    sessionId = h.sessionId,
                    kind = LossLedger.CheckpointKind.METADATA_ONLY,
                    firstSequence = null,
                    throughSequence = pending.throughSequence,
                    declaredCounts = pending.frozenCounts,
                    terminal = pending.claimsTerminal,
                )
            } catch (t: Throwable) {
                return omit("pending_not_rederivable", t.javaClass.simpleName)
            }
            // DISCLOSED LIMITATION, NOT A DEFECT WE CAN CLOSE HERE: the durable
            // pending row does NOT persist the checkpoint KIND, so this cannot
            // tell a claim F7 created from one F6 created for a delivered chunk.
            // Re-sending a DELIVERY-origin claim on this route is ARITHMETICALLY
            // REFUSED by the server every time — the metadata-only gap is
            // `through - previous` while a delivery claim's declared delta covers
            // `[previous+1. first-1]`, and `last >= first` makes the former
            // strictly larger — so it can never be mis-accounted, only refused.
            // A kind field on the durable row is a format change and is NOT in
            // Slice A's scope.
            return Decision.Emit(CheckpointClaim.render(doc), pending.checkpointId, retry = true)
        }

        val ledger = try {
            h.projectLedger()
        } catch (t: Throwable) {
            return omit("authority_unreadable", t.javaClass.simpleName)
        }

        // CORE OWNS THE ARITHMETIC AND THE SHAPE. This object neither pre-checks
        // nor repeats any of it — the ONE thing it decides is WHICH QUESTION to
        // ask, and it decides that from the single durable fact that separates the
        // two shapes.
        //
        // THE ROUTE IS THE DURABLE LOCAL CLAIM, NOT A PARAMETER AND NOT A
        // LIFECYCLE EVENT. `ledger.terminal` is projected from the authority's
        // `terminalRequested`, which ONLY the explicit authoritative session-end
        // signal writes (`TraceScoutMobile.endSessionAuthoritatively` ->
        // `LossAuthority.Handle.recordTerminal`). So this emitter — which is driven
        // by the ordinary drain, i.e. by backgrounding, a queue flush, a lifecycle
        // transition and process start — can reach the finality shape ONLY for a
        // session whose terminality was already explicitly declared and persisted.
        // It cannot create that claim: this object still names `recordTerminal`
        // nowhere, and that absence is pinned.
        //
        // A NON-TERMINAL SESSION IS BYTE-IDENTICAL TO C16/C17's ACCEPTED PATH.
        // `advance` is unchanged and is still the only door a non-terminal ledger
        // can take, so the frozen-accepted rows cannot regress through this branch.
        val plan = if (ledger.terminal) {
            MetadataOnlyCarrier.finality(ledger)
        } else {
            MetadataOnlyCarrier.advance(ledger)
        }
        return when (plan) {
            is MetadataOnlyCarrier.Plan.Refused -> omit("refused_" + plan.reason, plan.detail.take(60))
            is MetadataOnlyCarrier.Plan.Emit -> {
                val id = CheckpointClaim.identity(
                    ledger.sessionId, plan.throughSequence, plan.declaredCounts, plan.claimsTerminal,
                )
                val write = try {
                    // PERSIST BEFORE SEND. An identical re-request under the same
                    // intent is core's `AlreadyApplied` and is reported `Written`,
                    // because the claim IS recorded.
                    //
                    // `claimsTerminal` IS THE CARRIER'S VALUE, NOT A LITERAL
                    // HERE — and since an open product question it is REACHABLY `true`, which makes
                    // taking it from the plan load-bearing rather than merely
                    // tidy. `advance` still refuses a terminal ledger and a
                    // terminal snapshot, so `false` is the only value its branch
                    // can yield; `finality` refuses a NON-terminal snapshot, so
                    // `true` is the only value its branch can yield. Either way
                    // the durable claim persisted below and the emitted document
                    // are ONE fact rather than two that could disagree.
                    h.requestCheckpoint(
                        intentId = id,
                        checkpointId = id,
                        throughSequence = plan.throughSequence,
                        frozenCounts = plan.declaredCounts,
                        claimsTerminal = plan.claimsTerminal,
                    )
                } catch (t: Throwable) {
                    return omit("checkpoint_not_durable", t.javaClass.simpleName)
                }
                if (write !is LossAuthority.Write.Written) {
                    return omit("checkpoint_not_durable", write.javaClass.simpleName)
                }
                Decision.Emit(CheckpointClaim.render(plan.document), id, retry = false)
            }
        }
    }

    private fun omit(reason: String, detail: String? = null): Decision.Omit {
        RecoveryTrace.log("f7_metadata_only_omitted", "reason" to reason, "detail" to (detail ?: ""))
        return Decision.Omit(reason)
    }
}

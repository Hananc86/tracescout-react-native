package com.tracescout.mobile.internal.loss

import com.tracescout.mobile.queue.RecoveryTrace
import com.tracescout.rn.LossFamilies
import com.tracescout.rn.LossLedger

/**
 * **F6 — THE CHECKPOINT EMITTER. ONE ORCHESTRATION PATH, SHARED BY BOTH ANDROID
 * PRODUCERS.**
 *
 * Authority: frozen the capture specification, the conformance tests Part 4b D2-3 / D2-5 emitter halves and
 * Part 2 V1-V4, the pinned `F3_P0_COORDINATED_AUTHORITY_FORMAT` the specification-I.4,
 * DECISION-LOG a product decision (the ownership split) and a product decision (settlement is the
 * binder/authority path), and the lead's `an explicit product decision`.
 *
 * the specification, verbatim, is the whole semantic contract:
 *
 * * the FIRST checkpoint of a session contains the COMPLETE platform family
 * state (all 13 families, canonical order), **not a delta** from an unobserved
 * baseline;
 * * `gap === delta` is computed LOCALLY and asserted BEFORE emission — *"an
 * emitter that cannot prove its own arithmetic does not emit"*;
 * * a session marked `checkpointDisabled` emits **nothing**.
 *
 * **IT OWNS NO SEMANTIC RULE AND NO ARITHMETIC.** Every refusal comes from
 * `LossLedger.checkpointForDelivery`, every durable decision from
 * `SequenceAuthority.validate`, and the document from
 * `PendingCheckpoint.wire`. This object decides WHEN to ask, in WHICH order,
 * and what to do with each answer — nothing else. A second comparison here
 * would be invisible to every behavioural proof and would become visible only
 * when core's rule was removed and one path kept answering (RULING R2's
 * reasoning, applied to a different writer).
 *
 * **ONE PATH, BOTH PRODUCERS — AND THAT IS WHY IT IS HERE.**
 * `TraceScoutMobile` (Android Native) and `RnTransport` (RN Android) both
 * construct `DurableUploadPipeline` and hand it only a `poster` lambda, so the
 * chunk body is serialised in ONE place in `:sdk`. `mobile-android/rn/src/main`
 * holds **zero** references to `TraceScoutMobile`, so an emitter in that class
 * would cover one producer and silently skip the other. rn-core and `:rn` gain
 * nothing; iOS stays NO EMITTER under D4-1.
 */
internal object F6CheckpointEmitter {

    /**
 * **F6's CHECKPOINT-SPECIFIC DROPPED CARRIER, RELOCATED HERE.**
 *
 * **RELOCATED, NOT DUPLICATED — and the measurement is why.** a product decision
 * gives PRE-F6 the ordinary-envelope `dropped` disclosure
 * (`TraceScoutMobile.preF6DroppedFamilies`, which has a live reader) and
 * gives F6 *"the checkpoint-specific dropped carrier/block"*, naming
 * `droppedCounts` + `snapshotDropped` as its surface and the literal
 * subject of the `F6_DROPPED_WIRE_WRITER` pin. That pair lived as PRIVATE
 * members of `TraceScoutMobile` — a class the RN producer never touches —
 * so using it in place would have satisfied the specification's wording and broken the specification's
 * requirement that ONE orchestration path cover both producers. Its own
 * KDoc authorised the move: *"widening or repurposing it is F6's to do, not
 * PRE-F6's."* There is exactly ONE `droppedCounts` in the tree, with the
 * same name, and the pin now fires on the real subject.
 *
 * IT IS NOT THE SOURCE OF THE WIRE `counts`. The declared counts come
 * from the DURABLE authority through `projectLedger`; this carrier records
 * what the checkpoint DISCLOSED, per session, so the block F6 owns is
 * observable and separable from the ordinary envelope's. Merging the two
 * representations is forbidden by the specification and by a product decision.
 */
    private val droppedCounts = HashMap<String, Long>()
    private val lock = Any()

    /**
 * **the specification — "DO NOT SPIN/RETRY INDEFINITELY", as a PROCESS-LOCAL latch.**
 *
 * IT IS NOT A SUBSTITUTE FOR THE DURABLE `checkpointDisabled` the specification
 * asks for — see [refusal] for why that is not shippable in this slice and
 * is reported instead. What this DOES guarantee is the behaviour the
 * instruction names: once a session's arithmetic has been found
 * self-contradictory, this process asks ONCE and never again, so a chunk
 * stream cannot re-refuse on every delivery for the life of the install.
 *
 * PROCESS-LOCAL BY CONSTRUCTION, AND THE CONSEQUENCE IS STATED: a
 * restart asks again. That is the same shape as F2's per-process pending
 * lists — a latch is a statement about what THIS process measured, and the
 * durable statement is the one that is missing.
 */
    private val mismatched = HashSet<String>()

    /** The checkpoint-specific carrier, read back as the block F6 owns. */
    internal fun snapshotDropped(): Map<String, Long> = synchronized(lock) { LinkedHashMap(droppedCounts) }

    /** Test seam only: the carrier is process-scoped state, exactly as the pre-F6 pair was. */
    internal fun resetForTest() = synchronized(lock) { droppedCounts.clear(); mismatched.clear() }

    /**
 * What the pipeline does with one delivery.
 *
 * [Omit] IS NOT A FAILURE AND MUST NEVER FAIL THE CHUNK. A checkpoint
 * refusal is a statement about the loss statement, never about the records;
 * the chunk proceeds un-checkpointed and the reason is traced. A PARTIAL or
 * best-effort checkpoint is forbidden — D2-5's own negative control is
 * *"emit a partial checkpoint anyway"*.
 */
    internal sealed class Decision {
        /** [json] is the exact text to attach under `lossCheckpoint`; [reserveBytes] is what was reserved for it. */
        internal data class Attach(val json: String, val reserveBytes: Int, val retry: Boolean) : Decision()
        internal data class Omit(val reason: String) : Decision()
    }

    /**
 * **PHASE 1 — THE RESERVE, computed BEFORE the slice is chosen.**
 *
 * **THE ORDERING IS CIRCULAR AND THIS IS HOW IT IS BROKEN HONESTLY.**
 * The chunk's record count is chosen against a byte budget, and the
 * checkpoint's own `throughSequence` is the LAST sequence of the chunk — so
 * the document cannot be built before the slice, and the slice cannot be
 * sized without the document's cost. Every field except `throughSequence` is
 * knowable now (session, the durable counts, the local terminal claim), and
 * `throughSequence` is an int64 rendered as a canonical decimal STRING, so
 * its width is bounded at 20 digits. The reserve is therefore the ACTUAL
 * byte length of the REAL document with a 20-digit boundary — never a
 * guessed constant, and never smaller than the document that will be sent.
 *
 * `ChunkBounds.ENVELOPE_PUNCTUATION_BYTES` (41) IS NOT TOUCHED. It
 * describes the frozen three-key envelope and stays frozen; the reserve
 * is subtracted from the `budgetBytes` PARAMETER the bound function already
 * takes, so a chunk is sized with the checkpoint's cost included and the
 * existing minimum body budget still applies.
 *
 * Returns 0 when nothing will be attached, so the ordinary path is sized
 * byte-for-byte as it is today.
 */
    internal fun reserveBytes(authority: LossAuthority.Handle?): Int {
        val h = authority ?: return 0
        if (synchronized(lock) { mismatched.contains(h.sessionId) }) return 0
        val eligible = try {
            h.disabled() == null
        } catch (_: Throwable) {
            return 0
        }
        if (!eligible) return 0
        return try {
            val ledger = h.projectLedger()
            // The real counts, the real session, the real terminal claim; a
            // worst-case 20-digit boundary. `wireForDurablePending` routes
            // through the ONE wire writer, so this is the document's own cost.
            val doc = LossLedger.wireForDurablePending(
                sessionId = ledger.sessionId,
                kind = LossLedger.CheckpointKind.DELIVERY,
                firstSequence = 0L,
                throughSequence = WORST_CASE_THROUGH,
                declaredCounts = completeFamilyState(ledger.counts),
                terminal = ledger.terminal,
            )
            attachmentBytes(encode(doc))
        } catch (_: Throwable) {
            0
        }
    }

    /**
 * **PHASE 2 — THE DECISION, after the slice is known.**
 *
 * Order is load-bearing and each step is the ONE place that question is
 * asked:
 *
 * ```
 * 1 no authority wired -> Omit (today's behaviour, unchanged)
 * 2 checkpointDisabled -> Omit
 * 3 a durable pending row -> RETRY it BY VALUE, never a new claim
 * 4 project the ledger -> checkpointForDelivery (the ONLY arithmetic)
 * 5 Refused -> Omit, per-reason
 * 6 Emit -> persist via requestCheckpoint, THEN attach
 * ```
 *
 * **LOSS IS NOT A PRECONDITION.** A contiguous interval legitimately
 * emits a COMPLETE checkpoint with zero delta — `declared == missing == 0` —
 * and that is what advances the acknowledged authority. Emitting only when
 * loss > 0 would mean the boundary never moves on a healthy session, so the
 * next real gap would be measured against a stale `acknowledgedThrough`; the
 * ruling forbids it and `F6EmitterTest` pins the zero-delta case.
 *
 * **PERSIST BEFORE SEND.** `CheckpointResult.Emit`'s own KDoc: the ledger
 * carrying the frozen `pending` *"MUST be the state persisted BEFORE the
 * request is sent — a pending checkpoint written after the request cannot
 * survive a crash during it."* So `requestCheckpoint` runs first and a
 * non-`Written` answer OMITS; nothing is attached on the strength of a claim
 * that is not durable.
 */
    internal fun decide(
        authority: LossAuthority.Handle?,
        firstSequence: Long,
        lastSequence: Long,
        reserved: Int,
        /**
 * **TRUE WHEN THIS BATCH IS A RESUMED IN-FLIGHT ONE, AND IT IS
 * LOAD-BEARING — A REAL REGRESSION RUN IS WHAT PUT IT HERE.**
 *
 * The unresolved-batch invariant requires every retry of an unsettled
 * batch to rebuild BYTE-IDENTICAL bytes; the pipeline compares
 * `sha256Hex(body)` against the frozen descriptor and STOPS on a
 * mismatch. A checkpoint computed fresh on each attempt breaks that,
 * because the authority can change between them — measured:
 * `InheritedSessionAdoptionTest#B1` seeds a PRE-F2 session, so attempt 1
 * ran with NO authority (adoption happens at `adoptForRemoval`, AFTER
 * the POST) and attached nothing; the restart found an adopted
 * authority, attached a checkpoint, the digests disagreed and the
 * pipeline stopped — `exactly one re-presentation expected:<1> but
 * was:<0>`. A silently undelivered batch, caused by the emitter.
 *
 * So a RESUMED batch may only re-send a checkpoint that is ALREADY
 * DURABLE, and may never create one:
 *
 * ```
 * attempt 1 attached -> a pending row exists -> attempt 2 re-derives it -> identical
 * attempt 1 attached nothing -> no row -> attempt 2 attaches nothing -> identical
 * ```
 *
 * This is the specification/the specification's "do NOT create another one" extended to the one case
 * where creating one is not merely redundant but destructive.
 */
        resuming: Boolean = false,
    ): Decision {
        val h = authority ?: return Decision.Omit("no_authority")

        // the specification "do not spin": a session whose arithmetic this process already
        // found self-contradictory is never re-asked. Checked FIRST, so the
        // refusal cannot cost a projection or a durable read on every chunk.
        if (synchronized(lock) { mismatched.contains(h.sessionId) }) {
            return Decision.Omit("interval_mismatch_latched")
        }

        val disabled = try {
            h.disabled()
        } catch (t: Throwable) {
            return omit("authority_unreadable", t.javaClass.simpleName)
        }
        // the capture specification: a session marked `checkpointDisabled` emits NOTHING. Asked
        // before anything is projected, so a disabled session is not even
        // measured — D2-5's control is "emit a partial checkpoint anyway".
        if (disabled != null) return omit("checkpoint_disabled", disabled)

        // the specification/the specification: a claim is already outstanding, so do NOT create another one —
        // re-send the SAME document by value. The durable row is the only thing
        // that survives a process death, and a pending claim that cannot be
        // re-sent blocks every later one for the life of the install.
        val pending = try {
            h.pendingCheckpoint()
        } catch (t: Throwable) {
            return omit("authority_unreadable", t.javaClass.simpleName)
        }
        if (pending != null) {
            val doc = try {
                LossLedger.wireForDurablePending(
                    sessionId = h.sessionId,
                    kind = LossLedger.CheckpointKind.DELIVERY,
                    firstSequence = firstSequence,
                    throughSequence = pending.throughSequence,
                    declaredCounts = pending.frozenCounts,
                    terminal = pending.claimsTerminal,
                )
            } catch (t: Throwable) {
                return omit("pending_not_rederivable", t.javaClass.simpleName)
            }
            return fit(encode(doc), reserved, retry = true)
        }

        // A RESUMED batch with no durable claim attached nothing on its first
        // attempt, so it must attach nothing now. Creating one here is what
        // broke B1.
        if (resuming) return Decision.Omit("resume_no_pending_claim")

        val ledger = try {
            h.projectLedger()
        } catch (t: Throwable) {
            return omit("authority_unreadable", t.javaClass.simpleName)
        }

        // THE ONLY ARITHMETIC. `gap === delta` lives here and nowhere else.
        return when (val r = LossLedger.checkpointForDelivery(ledger, firstSequence, lastSequence)) {
            is LossLedger.CheckpointResult.Refused -> refusal(h, r)
            is LossLedger.CheckpointResult.Emit -> {
                val id = checkpointIdentity(ledger.sessionId, lastSequence, r.pending.declaredCounts, ledger.terminal)
                val write = try {
                    // PERSIST BEFORE SEND. An identical re-request under the same
                    // intent is core's `AlreadyApplied` and is reported `Written`,
                    // because the claim IS recorded.
                    h.requestCheckpoint(
                        intentId = id,
                        checkpointId = id,
                        throughSequence = r.pending.throughSequence,
                        frozenCounts = r.pending.declaredCounts,
                        claimsTerminal = r.pending.terminal,
                    )
                } catch (t: Throwable) {
                    return omit("checkpoint_not_durable", t.javaClass.simpleName)
                }
                if (write !is LossAuthority.Write.Written) {
                    return omit("checkpoint_not_durable", write.javaClass.simpleName)
                }
                // F6's own carrier: what this checkpoint DISCLOSED, per family.
                synchronized(lock) {
                    for ((f, n) in r.pending.declaredCounts) droppedCounts[f] = n
                }
                fit(encode(r.checkpoint), reserved, retry = false)
            }
        }
    }

    /**
 * the specification — the per-reason refusal behaviour, ruled by the lead.
 *
 * `INTERVAL_MISMATCH` SETS `checkpointDisabled` AND DOES NOT RETRY. The
 * declared loss does not equal the interval it claims to explain, so this
 * producer can no longer make a truthful `gap === delta` statement for the
 * session — and a session that keeps trying would re-refuse on every chunk
 * forever. the capture specification's rule applies: the honest state is recorded durably
 * and visibly rather than retried. The CHUNK still proceeds.
 *
 * `MALFORMED_INTERVAL` / `NOT_AFTER_AUTHORITY` are INVARIANT VIOLATIONS
 * and are NOT an excuse to fabricate a checkpoint: nothing is emitted,
 * nothing is disabled, the reason is traced, the chunk proceeds.
 */
    private fun refusal(h: LossAuthority.Handle, r: LossLedger.CheckpointResult.Refused): Decision {
        if (r.reason == LossLedger.CheckpointRefusal.INTERVAL_MISMATCH) {
            // **DURABLE FIRST, LATCH SECOND — THE ORDER IS THE RULING**
            // (`an explicit product decision`): *"persist durable disable
            // FIRST; only after successful durable write may the process-local
            // no-spin latch be set; if durable write refuses/fails, do NOT
            // claim the session is durably disabled."*
            //
            // The order is load-bearing, not stylistic. Latching first would
            // make THIS process stop asking while nothing on disk says the
            // session may not claim — so a restart would resume emitting
            // against the same inconsistency, and the latch would have
            // converted a durable fact into a process-local one. Setting the
            // latch only on a Written result means the two always agree: if
            // the disable is on disk, this process also stops asking; if it is
            // not, this process keeps asking and will try to record it again.
            //
            // A REFUSED OR FAILED WRITE IS NOT A DISABLE, AND THE TRACE SAYS
            // WHICH. `durable_disable` carries the measured outcome, never a
            // constant — a diagnostic that always printed `true` would be the
            // claim the ruling forbids, and one that always printed `false`
            // would hide the fact when it DID land.
            val w = try {
                h.disableF6IntervalMismatch()
            } catch (t: Throwable) {
                LossAuthority.Write.NotDurable(t.javaClass.simpleName)
            }
            val durable = w is LossAuthority.Write.Written
            if (durable) synchronized(lock) { mismatched.add(h.sessionId) }
            RecoveryTrace.log(
                "f6_checkpoint_interval_mismatch",
                "session" to h.sessionId.take(40),
                "detail" to r.detail.take(40),
                "durable_disable" to durable,
                "reason" to DisableReason.F6_INTERVAL_MISMATCH.name,
                "write" to w.javaClass.simpleName,
            )
            // THE CHUNK PROCEEDS EITHER WAY. the specification: a checkpoint refusal must
            // never fail, drop or block the chunk — including when the refusal
            // is OUR OWN inability to record the disable.
            return Decision.Omit("interval_mismatch")
        }
        // MALFORMED_INTERVAL and NOT_AFTER_AUTHORITY REACH HERE AND DISABLE
        // NOTHING — ruled explicitly ("Do not disable on: MALFORMED_INTERVAL,
        // NOT_AFTER_AUTHORITY"). They are invariant violations too, but the
        // ruling scopes the durable disable to INTERVAL_MISMATCH alone, and a
        // single `if` over the three would be a wider blast radius than the
        // ruling authorises.
        return omit("refused_" + r.reason.name.lowercase(), r.detail.take(40))
    }

    /**
 * the specification — attach ONLY if the chunk was sized with this document's reserve.
 *
 * A document larger than the reserve is OMITTED, not attached. The
 * reserve was computed with a worst-case 20-digit boundary and the real
 * counts, so it cannot be exceeded by construction — which is exactly why
 * the check is here: an assertion that can only fire if a premise broke is
 * the one worth keeping, and the alternative is silently sending a body the
 * slice was not sized for.
 */
    private fun fit(json: String, reserved: Int, retry: Boolean): Decision {
        val need = attachmentBytes(json)
        if (reserved < need) {
            RecoveryTrace.log("f6_checkpoint_over_reserve", "need" to need, "reserved" to reserved)
            return Decision.Omit("over_reserve")
        }
        return Decision.Attach(json, need, retry)
    }

    private fun omit(reason: String, detail: String? = null): Decision.Omit {
        RecoveryTrace.log("f6_checkpoint_omitted", "reason" to reason, "detail" to (detail ?: ""))
        return Decision.Omit(reason)
    }

    /**
 * the capture specification — the FIRST checkpoint carries the COMPLETE family state, all 13
 * families in canonical order, never a delta from an unobserved baseline. A
 * family the authority has no loss for is `0`, stated rather than omitted:
 * an absent key is "we did not say", and the server fills it from
 * `emptyLossCounts` anyway, so omitting it would make the producer's
 * statement weaker than the one it is entitled to make.
 */
    private fun completeFamilyState(counts: Map<String, Long>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (f in LossFamilies.ALL) out[f] = counts[f] ?: 0L
        return out
    }

    /**
 * The durable/local checkpoint identity (an explicit product decision the specification).
 *
 * **IT NEVER GOES ON THE WIRE.** `PendingCheckpoint.wire` emits
 * exactly five keys and `checkpointId` is not one of them; the server's
 * validator ALLOWS the key and never echoes it, and settlement binds
 * locally — `CheckpointAckBinder` sets `settlesCheckpointId =
 * pending.checkpointId` from the DURABLE row. So the identity exists to bind
 * an ack to the claim it answers, and adding it to the document would be a
 * wire-format change for nothing.
 *
 * Content-addressed over the claim, so an identical re-request under an
 * identical claim is core's `AlreadyApplied` — one durable record, no second
 * WAL frame — rather than a second outstanding claim. Hex, 32 chars, which
 * satisfies `AuthorityFieldShape`'s chunk-id shape.
 *
 * **THE DERIVATION MOVED TO `CheckpointClaim.identity` IN CORE AT F7
 * SLICE A — ONE HOME, NOT TWO.** The F7 metadata-only orchestrator needs the
 * SAME content address for the same claim, and a second derivation of one
 * identity is the `treatment` / `privacy` shape this programme has already
 * paid for. It is in CORE rather than beside this file because the digest is
 * taken over the CANONICAL family set in canonical order — core's own
 * vocabulary — and `:sdk` pins that exactly two of its production files may
 * name `LossFamilies`. The behaviour is unchanged: same inputs, same
 * length-prefixed parts, same 32 hex characters.
 */
    private fun checkpointIdentity(
        sessionId: String,
        through: Long,
        counts: Map<String, Long>,
        terminal: Boolean,
    ): String = CheckpointClaim.identity(sessionId, through, counts, terminal)

    /**
 * The exact UTF-8 cost of attaching this document to the chunk body:
 * `,"lossCheckpoint":<json>`.
 *
 * MEASURED, NOT ESTIMATED. The leading comma and the quoted key are part
 * of what the body will carry, so they are part of the reserve; the specification forbids
 * guessing a fixed checkpoint constant.
 */
    private fun attachmentBytes(json: String): Int =
        ATTACH_PREFIX.toByteArray(Charsets.UTF_8).size + json.toByteArray(Charsets.UTF_8).size

    /**
 * Serialise the wire map the ONE wire writer produced.
 *
 * IT IS A RENDERER, NOT A SECOND WIRE WRITER: the map's keys, order and
 * value types are `PendingCheckpoint.wire`'s, and this adds, removes and
 * renames nothing. `counts` is rendered in the map's own iteration order
 * (`wire` hands back a `LinkedHashMap`), so the bytes are deterministic
 * and a retry is byte-identical to the first emission.
 *
 * **THE RENDERER MOVED TO `CheckpointClaim.render` IN CORE AT F7 SLICE
 * A — ONE HOME, NOT TWO.** The F7 metadata-only orchestrator sends the SAME
 * document shape through the SAME `events: []` route, so a second renderer
 * would be two sets of bytes for one wire fact. The behaviour is unchanged:
 * same keys, same order, same value rendering.
 */
    private fun encode(doc: Map<String, Any>): String = CheckpointClaim.render(doc)

    /** `"lossCheckpoint"` is the request key the DEPLOYED server reads (`MobileReplayController`). */
    internal const val ATTACH_PREFIX: String = ",\"lossCheckpoint\":"

    /** An int64 rendered as a canonical decimal string is at most 20 characters. */
    private const val WORST_CASE_THROUGH: Long = Long.MAX_VALUE

    /** The owner this emitter's own disable is bound to (F4-P2 owner-bound disables). */
    internal const val F6_DISABLE_OWNER: String = "f6-checkpoint-emitter"
}

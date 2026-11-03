package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.internal.loss.F6CheckpointEmitter
import com.tracescout.mobile.internal.loss.F7MetadataOnlyEmitter
import com.tracescout.mobile.internal.loss.LossAuthority
import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File

/**
 * The upload pipeline's narrow seam onto the attribution authority (F2 map the specification,
 * the specification). The pipeline decides WHEN a removal committed; the authority decides
 * what that MEANS for each sequence. A pipeline that could reach the ledger
 * would be a second authority over what delivery and loss mean (salvaged rule,
 * BRANCH_SALVAGE the specification `DeliveryJournal`).
 *
 * ## WHEN each fact becomes true — and it is NOT one rule for all of them
 *
 * AN EARLIER VERSION OF THIS COMMENT SAID "CALLED ONLY AFTER A COMMITTED
 * REMOVAL", FOR EVERY OUTCOME. That was the right rule for a DESTRUCTIVE
 * removal and the wrong one for a SERVER-COMMITTED delivery, and reading it as
 * universal is what put the R1 write behind the dequeue — leaving a window in
 * which the queue and the in-flight proof were both gone while the durable
 * authority still said only ADMITTED.
 *
 * | fact | becomes true when | so it is recorded |
 * |---|---|---|
 * | R1 DELIVERED | the SERVER committed the batch (a valid identity-bound ack) | BEFORE the local cleanup — write-ahead |
 * | R2/R3 PARKED | the PARKED COPY is durable, and a parked slice is never automatically retried | after the park commit, before the active removal |
 * | R4 quarantine | the destructive removal committed | DISCLOSED as owed after `removeFirst == n` — NEVER a LOST write in F2 |
 *
 * The distinction is who owns the fact. Delivery and park-preservation are
 * already true when the authority is told; quarantine is a statement about
 * destruction this process performs — and its terminal-loss SETTLEMENT is
 * F4's (prepare/commit/reconcile), not F2's. RULING 2026-09-25: the
 * `recordLost(., QUARANTINED)` the reviewed subject carried was F4 leakage
 * and is REMOVED; F2 leaves the entries ADMITTED and discloses them under the
 * frozen nonterminal name `ADMITTED_ABSENT_UNDELIVERED`.
 *
 * AND THE OUTCOMES MAY NOT BE FOLDED. `Refused` is the authority's own
 * bookkeeping saying no (an unowned sequence, a foreign session) — NOT loss,
 * and never a reason to undo a delivery the server has committed. `NotDurable`
 * is a fenced authority. `NotWired` is the deliberate, visible unwired state a
 * test can tell apart from a real recording.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface AttributionSink {

    public sealed class Outcome {
        public object Recorded : Outcome()
        public data class Refused(val reason: String) : Outcome()
        public data class NotDurable(val reason: String) : Outcome()
        public object NotWired : Outcome()
    }

    /**
 * R1 — the server committed this exact batch. [recordCount] is the batch's
 * TRUE record count (what the server committed), supplied separately from
 * [sequences] so the authority can REFUSE a batch whose sequences it could
 * not fully read instead of being handed a count that always agrees.
 */
    public fun recordDelivered(sessionId: String, chunkId: String, recordCount: Int, contentSha256: String, sequences: List<Long>): Outcome

    /** R2/R3 — a DURABLE park commit took these sequences off the active timeline permanently. */
    public fun recordLost(sessionId: String, sequences: List<Long>, cause: LossAuthority.Cause): Outcome

    /**
 * R4 — a committed quarantine removal took these ADMITTED sequences off the
 * active timeline. F2 writes NO loss: the entries stay ADMITTED and are
 * DISCLOSED as owed (`ADMITTED_ABSENT_UNDELIVERED`). Never a removal gate.
 */
    public fun discloseAbsent(sessionId: String, sequences: List<Long>): Outcome

    /**
 * WHICH of [sequences] does the durable authority ALREADY record as
 * DELIVERED?
 *
 * THIS IS THE R1 CRASH GUARANTEE'S ONLY READ. The delivered authority is
 * now written BEFORE the queue prefix is removed, so a process that dies in
 * between leaves records in the active queue that the server has already
 * committed. Nothing else can tell them apart from records still owed — and
 * re-submitting them is not merely wasteful, it re-enters a window a later
 * refusal could turn into deletion.
 *
 * A read, never a write: an authority that is absent, unopened or unreadable
 * answers EMPTY, so nothing is ever removed on the strength of a lookup that
 * did not happen.
 */
    public fun deliveredSequences(sessionId: String, sequences: List<Long>): Set<Long>

    /**
 * The answer to [adoptForRemoval]. Only [Ready] (or the visible
 * [NotWired]) lets a removal proceed; every other answer keeps the bytes.
 */
    public sealed class Adoption {
        /** The authority for the session owns its surviving records DURABLY (adopted now, reconciled now, or already open). */
        public object Ready : Adoption()
        /** An adoption/reconciliation write was refused or is indeterminate: the last adoptable state is still ONLY in the queue. */
        public data class NotDurable(val reason: String) : Adoption()
        /** The authority could not be opened at all (corrupt, floor unreadable for a pruned session, key unavailable, …). */
        public data class Refused(val reason: String) : Adoption()
        public object NotWired : Adoption()
    }

    /**
 * THE ONE CREATING CALL, AND ITS NAME SAYS SO. Called by the pipeline
 * IMMEDIATELY BEFORE a removal of [sessionId]'s records — R1 delivery,
 * R2/R3 park, R4 quarantine, and the parked-prefix reconcile — with the
 * LIVE active queue exactly as the store read it ([queue],
 * [queueTrustworthy] = the store's own integrity class was OK). The
 * authority filters the view to [sessionId] and applies the EXISTING
 * adoption/reconciliation contract: a pre-F2 session with trustworthy
 * surviving records is adopted BEFORE the bytes that prove its history can
 * be removed (store review R3, 2026-09-25). A read-only lookup
 * ([deliveredSequences], the mutation fence, [recordDelivered] on an
 * absent authority) still creates NOTHING; only this call may.
 *
 * REMOVAL MAY PROCEED ONLY ON [Adoption.Ready] (or [Adoption.NotWired]).
 * `NotDurable` and `Refused` mean the queue bytes are the last copy of the
 * session's allocation history, and destroying them would let a later
 * `start(sessionId)` re-issue sequence 0.
 */
    public fun adoptForRemoval(sessionId: String, queue: List<ByteArray>, queueTrustworthy: Boolean): Adoption

    public fun isWired(): Boolean

    // ───────────────────────────────── F6: the checkpoint emitter's seam

    /**
 * **F6 — the byte cost to RESERVE for a checkpoint on this session, before
 * the chunk is sized.**
 *
 * IT GOES THROUGH THIS SEAM BECAUSE BOTH ANDROID PRODUCERS ALREADY WIRE
 * IT. `TraceScoutMobile` and `RnTransport` each construct
 * `DurableUploadPipeline` with an `AttributionSink`; adding the emitter here
 * gives ONE orchestration path for both, which is what
 * `an explicit product decision` the specification requires. A second emitter in either
 * producer would cover one and silently skip the other.
 *
 * IT RETURNS BYTES, NOT A MODEL. The decision type and the pure ledger
 * live in `internal/loss`; this interface is `public`, and the F1 B10 pin
 * forbids a core-owned type in a public `:sdk` signature. So the seam speaks
 * in an `Int` and a `String?` and the emitter owns every semantic.
 *
 * 0 when nothing will be attached, so an unwired or ineligible session sizes
 * its chunk byte-for-byte as it does today.
 */
    /**
 * DEFAULTED TO 0 / null ON THE INTERFACE, DELIBERATELY. Adding an
 * abstract member to a `public` interface breaks every existing
 * implementer, and the implementers here include test doubles that model
 * the F2 seam and know nothing about F6. A default that attaches NOTHING is
 * also the fail-closed direction: a sink that has not been taught about F6
 * sizes and encodes its chunks exactly as it does today.
 */
    public fun f6ReserveBytes(sessionId: String): Int = 0

    /**
 * **F6 — the checkpoint document to attach, or null to attach nothing.**
 *
 * NULL IS NOT A FAILURE AND MUST NEVER FAIL THE CHUNK. A refusal is
 * a statement about the loss statement, never about the records: the chunk
 * proceeds un-checkpointed and the reason is traced. A PARTIAL checkpoint is
 * forbidden — D2-5's own negative control is *"emit a partial checkpoint
 * anyway"*.
 *
 * [reservedBytes] is what [f6ReserveBytes] returned for the chunk that was
 * actually sized; the emitter attaches only if the real document fits it.
 */
    public fun f6CheckpointJson(
        sessionId: String,
        firstSequence: Long,
        lastSequence: Long,
        reservedBytes: Int,
        /** True when this batch is a RESUMED in-flight one — see `F6CheckpointEmitter.decide`. */
        resuming: Boolean = false,
    ): String? = null

    /**
 * **F7 SLICE A — the NON-TERMINAL metadata-only checkpoint to send with
 * `events: []`, or null to send nothing.**
 *
 * Unlike [f6CheckpointJson] this does NOT ride a delivered chunk: there is
 * no chunk, which is the whole point of the capture specification. The returned
 * pair is the document and the claim's own content-addressed identity (used
 * as the request's chunk id so a retry of the same claim is byte-identical).
 *
 * NULL IS NOT A FAILURE and must never fail anything. A refusal is a
 * statement about the loss statement; a metadata-only request carries no
 * records, so there is nothing to retain, park or quarantine either way.
 *
 * DEFAULT-NULL ON THE INTERFACE, deliberately: every implementer here
 * includes test doubles that model the F2 seam and know nothing about F7,
 * and a default that emits NOTHING is the fail-closed direction.
 */
    public fun f7MetadataOnlyCheckpoint(sessionId: String): MetadataOnlyDocument? = null

    /**
 * **F7 R-2 — is this session DURABLY FINALIZED?** The gate the chunkless
 * trigger asks BEFORE it asks for a document.
 *
 * IT MUST BE ASKED FIRST, and that ordering is the whole reason this
 * exists as its own question. [f7MetadataOnlyCheckpoint] PERSISTS a claim
 * before it returns the bytes, so a caller that asked for a document and
 * then discarded the ones it did not want would already have written an
 * `advance` claim — a disclosure the ruling authorises only at
 * finalization. Reading terminality first means the unauthorised shape is
 * never requested, rather than requested and then dropped.
 *
 * DEFAULT FALSE, for the same reason every other F6/F7 member on this
 * interface defaults to "nothing": a sink that has not been taught about
 * R-2 — and the implementers here include test doubles that model the F2
 * seam — must behave exactly as it does today, and "not finalized" is the
 * answer under which nothing is disclosed.
 */
    public fun f7SessionFinalized(sessionId: String): Boolean = false

    /**
 * **F7 R-2, CROSS-LAUNCH — the session ids OTHER THAN [exclude] that this
 * install still holds an authority for.** A CANDIDATE LIST, not a verdict:
 * whether any of them is finalized is [f7SessionFinalized]'s question, and
 * the caller asks it per candidate.
 *
 * **IT DELIBERATELY DOES NOT FILTER BY TERMINALITY, and a negative
 * control is why.** The first version returned only the finalized ones —
 * and removing that filter changed NO behaviour, because every candidate is
 * put to the gate anyway and the gate is authoritative. A filter that
 * cannot change an outcome is a second opinion on terminality that happens
 * to agree today; deleting it leaves ONE place that decides.
 *
 * **THE DEFECT THIS EXISTS FOR, AND WHY A SESSION ID THE CALLER ALREADY
 * HOLDS CANNOT ANSWER IT.** [f7SessionFinalized] answers about ONE named
 * session, and the chunkless trigger names the session the drain is running
 * under. At process start that is the session `start` has just MINTED —
 * `TraceScoutMobile.start` sets `identity` before it calls
 * `recoverInherited`, and the empty-queue drain passes `identity.sessionId`
 * as its fallback owner — so the gate was asked whether the session born a
 * millisecond ago had ended. It had not, and it structurally cannot have:
 * the only writer of the claim is the host's explicit session-end seam. The
 * question was therefore answerable only `false`, and the case
 * `endSessionAuthoritatively`'s own KDoc promises — *"a host that declares
 * session end and is then killed emits the checkpoint on the next launch"* —
 * could not fire on the one path that reaches it with no chunk to ride.
 *
 * **IT ANSWERS FROM THE DURABLE RECORD, NOT FROM A SECOND POINTER.** The
 * obvious alternative — write "the session that ended" to a file of its own
 * and read it at launch — would be a second durable statement of
 * terminality that can disagree with the authority's. The authority already
 * knows; this enumerates it. `pruneOne` refuses
 * `terminal_requested_unacknowledged`, so a session with an unsettled
 * terminal claim is never pruned and is still there at the next launch —
 * which is what makes enumeration sufficient rather than hopeful.
 *
 * FAIL-CLOSED AND MONOTONE. An unreadable authority yields NOTHING rather
 * than a guess, and terminality is one-way, so the worst a stale or
 * incomplete read can do is defer the disclosure to the next drain. It never
 * asserts an end that was not declared.
 *
 * DEFAULT EMPTY, like every other F6/F7 member here: a sink that has not
 * been taught about the cross-launch case behaves exactly as it does today.
 */
    public fun f7InheritedSessions(exclude: String): List<String> = emptyList()

    /**
 * **F-15 — the DURABLE pending claim's state for [sessionId]: a boundary, a
 * proven absence, or an admission that the authority could not be read.** A
 * READ, and nothing else: it opens no authority that does not exist, writes
 * nothing, and creates nothing.
 *
 * IT EXISTS BECAUSE THE PIPELINE CANNOT ASK THE QUESTION ANY OTHER WAY.
 * `DurableUploadPipeline` holds an `AttributionSink`, never a
 * `LossAuthority.Handle`, so "does this session hold a stranded claim whose
 * boundary is BELOW the batch I am about to deliver?" has no other door. The
 * boundary is the only VALUE carried — not the counts, not the identity —
 * because the decision needs the boundary and the RE-SEND re-derives every
 * other field from the row itself.
 *
 * **IT RETURNED A BARE `Long` AND `-1` MEANT TWO DIFFERENT THINGS. THAT
 * WAS THE DEFECT, AND IT WAS A SAFETY DEFECT RATHER THAN A TIDINESS ONE.**
 * Both "the authority was read and holds no claim" and "the authority could
 * not be read" answered `-1`, and the predecessor's own KDoc justified the
 * conflation with *"in every one of them the only safe action is none"*. That
 * was true while the answer only chose whether to ATTEMPT A CARRIER. It
 * stopped being true the moment the answer also GATES DELIVERY: for an
 * unreadable authority "do nothing" means *deliver the chunk anyway*, and if
 * a stranded claim is in fact outstanding, F6 re-attaches it by value and the
 * deployed ingest refuses the WHOLE chunk permanently
 * (`loss_checkpoint_through_not_chunk_aligned`, `retriable:false`). An
 * unreadable authority is therefore UNKNOWN, never ABSENT — fail-closed now
 * means *refuse to deliver*, which is the opposite of the old sentinel's
 * meaning, so the sentinel had to go rather than be re-documented.
 *
 * DEFAULT [PendingClaim.None] on the interface, like every other F6/F7
 * member here: a sink that has not been taught about F-15 — and the
 * implementers include test doubles that model the F2 seam — reports no claim
 * and therefore gates nothing, which is exactly today's behaviour.
 */
    public fun f7PendingClaim(sessionId: String): PendingClaim = PendingClaim.None

    /**
 * **F-15 — the three ANSWERS a durable loss authority can give about a
 * pending claim, kept apart because two of them demand opposite actions.**
 *
 * There is deliberately no `Long` in this vocabulary other than inside
 * [Pending]: a caller cannot accidentally compare an absence with a boundary,
 * because an absence has no number to compare.
 */
    public sealed class PendingClaim {
        /** The authority WAS read and holds no outstanding claim. Nothing to settle. */
        public object None : PendingClaim()

        /** A claim is durable with this frozen boundary. `>= 0` by construction. */
        public class Pending(public val throughSequence: Long) : PendingClaim()

        /**
 * The authority could not be read. **NOT [None]** — this says nothing
 * about whether a claim exists, so a caller that needs to know whether a
 * delivery is safe must treat it as unsafe.
 */
        public class Unreadable(public val detail: String) : PendingClaim()
    }

    /**
 * **F-15 — RE-SEND the EXISTING frozen claim on the `events: []` carrier, or
 * null. It can never mint a fresh one.**
 *
 * **THE DIFFERENCE FROM [f7MetadataOnlyCheckpoint] IS THE WHOLE POINT,
 * and it is STRUCTURAL rather than a convention.** That member PERSISTS a
 * claim before it returns bytes — which is why R-2 must read terminality
 * first — so a caller that wanted only the re-send and used it would write an
 * `advance` claim whenever the pending row had settled in between. This one
 * calls the same emitter with `pendingOnly = true`, under which
 * `MetadataOnlyCarrier` and `requestCheckpoint` are unreachable: the only two
 * outcomes are the by-value re-send of an already-durable claim, and nothing.
 *
 * IT IS NOT GATED ON TERMINALITY, deliberately. The claim being re-sent is
 * already durable, so the act was authorised when it was frozen; the document
 * carries `terminal` FROM THE ROW, so a non-terminal claim re-sends as
 * non-terminal and this member cannot disclose an end that was not declared.
 * Asking `f7SessionFinalized` here would refuse exactly the F-15 case — a
 * live session whose crash-recovery batch is blocked by its own stranded
 * claim.
 *
 * DEFAULT NULL, for the same fail-closed reason as every member above.
 */
    public fun f7PendingCheckpointRetry(sessionId: String): MetadataOnlyDocument? = null

    /** The F7 document plus the claim identity the request is addressed with. */
    public class MetadataOnlyDocument internal constructor(
        public val json: String,
        public val chunkId: String,
        /** True when this re-sends an ALREADY durable claim rather than a fresh one. */
        public val retry: Boolean,
    )

    /**
 * The answer to [settleLossCheckpoint]. Its own vocabulary — deliberately NOT
 * [Outcome] — because a settlement DECLINED by writer policy is neither a
 * recording nor a refusal, and folding it into either would let a policy
 * answer be mistaken for, or become, a core refusal (RULING R3's boundary,
 * applied to the settle path).
 */
    public sealed class Settlement {
        public object Recorded : Settlement()
        /** The binder declined (no pending, older, mismatch, malformed): nothing was written. */
        public data class PolicyDeclined(val reason: String) : Settlement()
        /** CORE refused the durable record — the reason is core's, verbatim. */
        public data class Refused(val reason: String) : Settlement()
        /** The authority is fenced; only a reopen decides. */
        public data class NotDurable(val reason: String) : Settlement()
        public object NotWired : Settlement()
    }

    /**
 * F3-P1b — ROUTE a parsed `loss-checkpoint-ack-1` to the session's authority for
 * settlement. PURE ROUTING: the seam opens the handle through the NON-CREATING door
 * (`openExisting` never creates — a session with no authority settles nothing and
 * the file system stays byte-identical) and hands the values to
 * `Handle.settleCheckpoint`, which binds then appends. The seam compares NOTHING,
 * decides NOTHING and re-derives NOTHING (P1b-17).
 *
 * CALLED ONLY WHEN A LOSS BLOCK WAS PARSED. An ordinary `mobile-ack-1` reaches
 * [recordDelivered] and NEVER this method: *"`mobile-ack-1` alone must NEVER clear
 * pending loss"*. Reusing the delivery handler for the loss path is
 * the conformance tests Part 4d's named defect.
 */
    public fun settleLossCheckpoint(
        sessionId: String,
        protocol: String,
        lossAccountingVersion: Int,
        state: String,
        ackSessionId: String,
        acceptedThroughSequence: String?,
        acceptedCounts: Map<String, Long>,
        terminal: Boolean,
    ): Settlement

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public companion object {
        /** The unwired sink. Records nothing and SAYS SO. */
        @JvmStatic
        public fun none(): AttributionSink = object : AttributionSink {
            override fun recordDelivered(sessionId: String, chunkId: String, recordCount: Int, contentSha256: String, sequences: List<Long>): Outcome = Outcome.NotWired
            override fun recordLost(sessionId: String, sequences: List<Long>, cause: LossAuthority.Cause): Outcome = Outcome.NotWired
            override fun discloseAbsent(sessionId: String, sequences: List<Long>): Outcome = Outcome.NotWired
            override fun deliveredSequences(sessionId: String, sequences: List<Long>): Set<Long> = emptySet()
            override fun adoptForRemoval(sessionId: String, queue: List<ByteArray>, queueTrustworthy: Boolean): Adoption = Adoption.NotWired
            override fun settleLossCheckpoint(sessionId: String, protocol: String, lossAccountingVersion: Int, state: String, ackSessionId: String, acceptedThroughSequence: String?, acceptedCounts: Map<String, Long>, terminal: Boolean): Settlement = Settlement.NotWired
            override fun isWired(): Boolean = false
            override fun f6ReserveBytes(sessionId: String): Int = 0
            override fun f6CheckpointJson(sessionId: String, firstSequence: Long, lastSequence: Long, reservedBytes: Int, resuming: Boolean): String? = null
            override fun f7MetadataOnlyCheckpoint(sessionId: String): MetadataOnlyDocument? = null
            override fun f7SessionFinalized(sessionId: String): Boolean = false
            override fun f7PendingClaim(sessionId: String): PendingClaim = PendingClaim.None
            override fun f7PendingCheckpointRetry(sessionId: String): MetadataOnlyDocument? = null
        }

        /**
 * The production sink: resolves the process-wide handle per SESSION (a
 * drain may carry a previous session's records), records, traces the
 * answer. A session with no authority (pre-F2 records, an unreadable
 * directory) is `Refused`/`NotDurable` and the delivery proceeds — the
 * server's committed fact stands regardless of local bookkeeping.
 *
 * THE DEFAULT OPENER IS THE NON-CREATING FORM (blocker 3 of the
 * ccdbec35 reviews). It used to be `LossAuthority.open(filesDir, sid)`
 * with `queue = null`, so resolving a handle for an INHERITED pre-F2
 * session WROTE A GENESIS SNAPSHOT for it — after which a later
 * `start(sessionId = <that sid>)` found `fresh = false`, skipped
 * adoption and allocated from 0, re-issuing sequences the server had
 * already accepted. A lookup may never manufacture an authority: an
 * absent one answers `no_authority`, the delivery proceeds, and the file
 * system is byte-identical afterwards (tested).
 */
        @JvmStatic
        @JvmOverloads
        public fun forFilesDir(
            filesDir: File,
            opener: (String) -> LossAuthority.Open = { sid -> LossAuthority.openExisting(filesDir, sid) },
            /**
 * THE ONE CREATING OPENER, used by [adoptForRemoval] ONLY. Its
 * default is the facade's removal-path door, which adopts a pre-F2
 * session's trustworthy surviving records before they can be
 * removed and otherwise creates nothing. It is a SEPARATE parameter
 * so a test that swaps the read-only opener cannot silently swap
 * this one, and vice versa.
 */
            removalOpener: (String, LossAuthority.QueueSnapshot) -> LossAuthority.Open = { sid, q -> LossAuthority.openForRemoval(filesDir, sid, q) },
        ): AttributionSink =
            object : AttributionSink {
                private fun handle(sid: String): LossAuthority.Handle? = when (val o = opener(sid)) {
                    is LossAuthority.Open.Ok -> o.handle
                    is LossAuthority.Open.Unavailable -> { RecoveryTrace.log("attribution_unavailable", "why" to o.reason); null }
                }
                /**
 * AN AUTHORITY THAT IS NOT THERE IS `Refused`, NEVER `NotDurable`.
 * `NotDurable` means "a FENCED authority may or may not have
 * written this" and it is the ONE answer that stops the R1
 * cleanup. An absent authority (an inherited pre-F2 session), or
 * one whose directory cannot be opened, has written NOTHING — the
 * server's fact stands, the cleanup proceeds, and the only thing
 * lost is local bookkeeping the next reconcile discloses.
 */
                private inline fun withHandle(sid: String, f: (LossAuthority.Handle) -> Outcome): Outcome =
                    when (val o = opener(sid)) {
                        is LossAuthority.Open.Ok -> f(o.handle)
                        is LossAuthority.Open.Unavailable -> {
                            RecoveryTrace.log("attribution_unavailable", "why" to o.reason)
                            Outcome.Refused("authority_unavailable:${o.reason}")
                        }
                    }
                private fun map(s: LossAuthority.Settle): Outcome = when (s) {
                    is LossAuthority.Settle.Settled -> Outcome.Recorded
                    is LossAuthority.Settle.Refused -> Outcome.Refused(s.reason)
                    is LossAuthority.Settle.NotDurable -> Outcome.NotDurable(s.reason)
                }
                override fun recordDelivered(sessionId: String, chunkId: String, recordCount: Int, contentSha256: String, sequences: List<Long>): Outcome = withHandle(sessionId) { h ->
                    val out = map(h.markDeliveredBatch(chunkId, recordCount, contentSha256, sequences))
                    RecoveryTrace.log("attribution_delivered", "chunk" to chunkId.take(12), "count" to sequences.size, "outcome" to out.javaClass.simpleName)
                    out
                }
                override fun recordLost(sessionId: String, sequences: List<Long>, cause: LossAuthority.Cause): Outcome = withHandle(sessionId) { h ->
                    val out = map(h.settleLostSequences(sequences, cause))
                    RecoveryTrace.log("attribution_lost", "cause" to cause.name, "count" to sequences.size, "outcome" to out.javaClass.simpleName)
                    out
                }
                override fun discloseAbsent(sessionId: String, sequences: List<Long>): Outcome = withHandle(sessionId) { h ->
                    val n = h.discloseAbsent(sequences)
                    RecoveryTrace.log("attribution_absent_disclosed", "count" to n, "of" to sequences.size)
                    Outcome.Recorded
                }
                /**
 * IT MUST ANSWER ON A FRESH PROCESS, AND IT MUST NOT CREATE AN
 * AUTHORITY. Those two pull in opposite directions and the
 * precondition is what reconciles them.
 *
 * `peek` alone was the first cut, and it could not see its own
 * subject: after the crash this recovery exists for, no handle is
 * open yet for a session whose records a drain inherited, so the
 * pass would sweep nothing and the delivered prefix would be
 * re-submitted — a recovery that only works in the process that
 * did not crash. But opening unconditionally WRITES a genesis
 * snapshot, manufacturing an authority directory to answer a
 * read-only question.
 *
 * So: peek; otherwise open ONLY a directory that already exists.
 * An absent authority has nothing to say, and EMPTY removes
 * nothing.
 */
                override fun deliveredSequences(sessionId: String, sequences: List<Long>): Set<Long> {
                    if (sequences.isEmpty()) return emptySet()
                    val h = LossAuthority.peek(filesDir, sessionId)
                        ?: if (LossAuthority.authorityExists(filesDir, sessionId)) handle(sessionId) else null
                    if (h == null) return emptySet()
                    return try { sequences.filter { h.isDelivered(it) }.toSet() } catch (_: Throwable) { emptySet() }
                }
                /**
 * Ready ONLY when the authority opened AND is unfenced AND every
 * adoption/reconciliation write it attempted landed. A disabled
 * authority (lossy view, unknown event type, queue ahead) whose
 * disable IS durable answers Ready too: the disable and the
 * advanced high-water are the accounting, RULING A2 refuses every
 * later admission for that session, and the queue bytes hold
 * nothing further the authority could adopt — keeping them would
 * strand the strict-FIFO head for the life of the install.
 */
                override fun adoptForRemoval(sessionId: String, queue: List<ByteArray>, queueTrustworthy: Boolean): Adoption {
                    val out: Adoption = when (val o = removalOpener(sessionId, LossAuthority.QueueSnapshot(queue, queueTrustworthy))) {
                        is LossAuthority.Open.Unavailable -> Adoption.Refused(o.reason)
                        is LossAuthority.Open.Ok -> {
                            val h = o.handle
                            when {
                                h.isFenced() -> Adoption.NotDurable("authority_fenced")
                                h.recoveryWritesRefused() > 0 -> Adoption.NotDurable("recovery_writes_refused:${h.recoveryWritesRefused()}")
                                else -> Adoption.Ready
                            }
                        }
                    }
                    RecoveryTrace.log("attribution_removal_adoption", "outcome" to out.javaClass.simpleName, "count" to queue.size)
                    return out
                }
                /**
 * F3-P1b routing only. The read-only opener (`openExisting`): an absent
 * authority is `Refused("authority_unavailable:…")` and nothing is
 * created. The writer's answer is mapped arm-for-arm — a `PolicyDeclined`
 * stays a policy answer and never becomes a `Refused`.
 */
                override fun settleLossCheckpoint(sessionId: String, protocol: String, lossAccountingVersion: Int, state: String, ackSessionId: String, acceptedThroughSequence: String?, acceptedCounts: Map<String, Long>, terminal: Boolean): Settlement {
                    val out: Settlement = when (val o = opener(sessionId)) {
                        is LossAuthority.Open.Unavailable -> {
                            RecoveryTrace.log("attribution_unavailable", "why" to o.reason)
                            Settlement.Refused("authority_unavailable:${o.reason}")
                        }
                        is LossAuthority.Open.Ok -> when (val w = o.handle.settleCheckpoint(
                            protocol, lossAccountingVersion, state, ackSessionId, acceptedThroughSequence, acceptedCounts, terminal,
                        )) {
                            is LossAuthority.Write.Written -> Settlement.Recorded
                            is LossAuthority.Write.PolicyDeclined -> Settlement.PolicyDeclined(w.reason)
                            is LossAuthority.Write.Refused -> Settlement.Refused(w.reason)
                            is LossAuthority.Write.NotDurable -> Settlement.NotDurable(w.reason)
                        }
                    }
                    RecoveryTrace.log("attribution_loss_settlement", "outcome" to out.javaClass.simpleName)
                    return out
                }
                override fun isWired(): Boolean = true

                /**
 * F6 — the SAME read-only door [deliveredSequences] uses: peek,
 * else open ONLY a directory that already exists.
 *
 * IT NEVER CREATES. F2's two-door rule stands: `open` writes
 * a genesis snapshot, so creating one here would manufacture an
 * authority for a session merely because a chunk was about to be
 * sized. A session with no durable authority has no acknowledged
 * boundary to advance, so the honest answer is to attach nothing.
 */
                private fun f6Handle(sessionId: String): LossAuthority.Handle? =
                    LossAuthority.peek(filesDir, sessionId)
                        ?: if (LossAuthority.authorityExists(filesDir, sessionId)) handle(sessionId) else null

                override fun f6ReserveBytes(sessionId: String): Int =
                    F6CheckpointEmitter.reserveBytes(f6Handle(sessionId))

                override fun f6CheckpointJson(
                    sessionId: String,
                    firstSequence: Long,
                    lastSequence: Long,
                    reservedBytes: Int,
                    resuming: Boolean,
                ): String? = when (
                    val d = F6CheckpointEmitter.decide(
                        f6Handle(sessionId), firstSequence, lastSequence, reservedBytes, resuming,
                    )
                ) {
                    is F6CheckpointEmitter.Decision.Attach -> d.json
                    is F6CheckpointEmitter.Decision.Omit -> null
                }

                /**
 * F7 Slice A — the SAME read-only door, deliberately. `f6Handle`
 * is the ONE non-creating loss-authority resolution in this seam
 * (peek, else open only a directory that already exists); its
 * name is historical — F6 introduced it — and giving F7 a second
 * resolver would be a second opinion on whether a session has a
 * durable authority. It never creates: a session with no
 * authority has no acknowledged boundary to advance, so the
 * honest answer is to emit nothing.
 */
                override fun f7MetadataOnlyCheckpoint(sessionId: String): MetadataOnlyDocument? = when (
                    val d = F7MetadataOnlyEmitter.decide(f6Handle(sessionId))
                ) {
                    is F7MetadataOnlyEmitter.Decision.Emit -> MetadataOnlyDocument(d.json, d.chunkId, d.retry)
                    is F7MetadataOnlyEmitter.Decision.Omit -> null
                }

                /**
 * F7 R-2 — the SAME `f6Handle` door again, deliberately. The
 * gate and the document must agree about whether this session
 * HAS a durable authority, and two resolvers would be two
 * opinions on that; `f6Handle` never creates one, so a session
 * with no authority is simply not finalized.
 */
                override fun f7SessionFinalized(sessionId: String): Boolean =
                    F7MetadataOnlyEmitter.sessionFinalized(f6Handle(sessionId))

                /**
 * F7 R-2 CROSS-LAUNCH — the ONE enumeration of the authority
 * root, reused rather than written again.
 *
 * `scanSessionIndexes` is the facade's existing read-only
 * walk (F4-P2): it goes through `inspect`, never `open`, so
 * it creates no genesis for a session it merely looked at, and
 * it reports INCOMPLETE rather than "nothing" when a directory
 * cannot be read. A second walk here would be a second home for
 * authority-directory knowledge — the exact thing that scan's
 * own KDoc says it exists to prevent.
 *
 * **A NAME LIST, AND NOTHING MORE — the verdict stays with
 * the gate.** `LossAuthority.sessionIdsOnDisk` opens and
 * decrypts nothing; it reports which session directories the
 * install holds. Each one is then put to the SAME
 * `f7SessionFinalized` the caller already uses, which resolves
 * through this sink's injected non-creating opener — the one
 * place that holds the authority key. So no second key path is
 * minted and no second opinion on terminality exists.
 *
 * THE LAYOUT KNOWLEDGE STAYS IN THE FACADE'S PACKAGE.
 * `ModuleBoundaryTest` B9 pins that `EncryptedAuthorityStore`'s
 * name never leaves `internal/loss`; the first draft of this
 * method read the authority root here and would have broken it.
 * The guard was right — a sink that knew the on-disk layout
 * would be a second owner of it.
 */
                override fun f7InheritedSessions(exclude: String): List<String> =
                    LossAuthority.sessionIdsOnDisk(filesDir, exclude)

                /**
 * F-15 — the SAME `f6Handle` door a FOURTH time, deliberately.
 * The reconciliation's precondition and its document must agree
 * about whether this session HAS a durable authority, and a
 * second resolver would be a second opinion on that.
 *
 * **UNREADABLE AND ABSENT USED TO COLLAPSE TO `-1` HERE, AND
 * THE PREDECESSOR'S OWN COMMENT SAID THEY "ARE NOT THE SAME
 * FACT" WHILE RETURNING THE SAME VALUE FOR BOTH.** Tracing the
 * difference and then discarding it is not keeping it: the
 * caller got one number and could not tell a proven absence from
 * an admission of ignorance. The three cases are now three
 * values, and the only one carrying a boundary is the one that
 * HAS one:
 *
 * - no authority DIRECTORY for this session → [PendingClaim.None].
 * A session with no authority can hold no claim, and this is
 * the no-authority compatibility path (pre-F2 records).
 * - the directory EXISTS and the handle could not be opened →
 * [PendingClaim.Unreadable]. `f6Handle` answers null for both,
 * which is why the directory is asked about separately.
 * - the authority was read and holds no claim → [PendingClaim.None].
 * - the read THREW → [PendingClaim.Unreadable]. Says nothing
 * about whether a claim exists, so a caller deciding whether a
 * delivery is safe must treat it as unsafe.
 *
 * A reconciliation attempt built on an authority we could not
 * read would still be a request made on a boundary we invented,
 * so `Unreadable` does not attempt one either — but it no longer
 * lets the DATA chunk through.
 */
                override fun f7PendingClaim(sessionId: String): PendingClaim = try {
                    val h = f6Handle(sessionId)
                    if (h == null) {
                        // **`f6Handle` RETURNING NULL HAS TWO MEANINGS AND
                        // ONLY ONE OF THEM IS AN ABSENCE** — it is
                        // `peek ?: if (authorityExists) handle else null`,
                        // so null is EITHER "this session has no authority at
                        // all" OR "it has one and it could not be opened". The
                        // first version of this method called both `None`,
                        // which is the same conflation one layer down from the
                        // `-1` sentinel this change exists to remove — and a
                        // control-driven row found it by corrupting the
                        // authority's own bytes, where the delivery went
                        // straight through. The directory's existence is the
                        // fact that separates them.
                        if (LossAuthority.authorityExists(filesDir, sessionId))
                            PendingClaim.Unreadable("authority_present_not_openable")
                        else PendingClaim.None
                    } else if (h.disabled() != null) {
                        // **A DISABLED SESSION'S PENDING CLAIM CAN STRAND
                        // NOTHING, SO REPORTING IT AS `Pending` BLOCKED THE
                        // SESSION FOR EVER.** `F6CheckpointEmitter.decide`
                        // omits for a disabled session BEFORE it reads the
                        // pending row, so no stale checkpoint can ride that
                        // session's data chunk and the alignment refusal the
                        // gate exists to prevent is unreachable for it.
                        // Meanwhile `F7MetadataOnlyEmitter.decide` ALSO omits
                        // for a disabled session — before its `pendingOnly`
                        // branch — so the carrier can never settle the claim
                        // either, and the reconciliation's `emitter_omitted`
                        // return precedes the burst counter, which means not
                        // even the bounded budget could end the loop. The
                        // records stayed queued for as long as the pending row
                        // and the disabled state both persisted.
                        //
                        // `None` is the honest answer rather than a workaround:
                        // the question this method answers is "can a stranded
                        // claim ride this session's next chunk?", and for a
                        // disabled session the emitter has already decided that
                        // nothing will.
                        PendingClaim.None
                    } else {
                        val through = h.pendingCheckpoint()?.throughSequence
                        if (through == null) PendingClaim.None else PendingClaim.Pending(through)
                    }
                } catch (t: Throwable) {
                    RecoveryTrace.log("f15_pending_claim_unreadable",
                        "detail" to t.javaClass.simpleName, "session" to sessionId.take(24))
                    PendingClaim.Unreadable(t.javaClass.simpleName)
                }

                /**
 * F-15 — the by-value re-send, through the SAME emitter under
 * `pendingOnly = true`. Nothing here decides anything: the
 * emitter owns the refusals and core owns the document.
 */
                override fun f7PendingCheckpointRetry(sessionId: String): MetadataOnlyDocument? = when (
                    val d = F7MetadataOnlyEmitter.decide(f6Handle(sessionId), pendingOnly = true)
                ) {
                    is F7MetadataOnlyEmitter.Decision.Emit -> MetadataOnlyDocument(d.json, d.chunkId, d.retry)
                    is F7MetadataOnlyEmitter.Decision.Omit -> null
                }
            }
    }
}

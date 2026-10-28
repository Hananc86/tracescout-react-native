package com.tracescout.mobile.internal.loss

import com.tracescout.rn.LossFamilies

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. It is `public` only because Kotlin `internal` is
 * compilation-module scoped and every consumer (`:sdk`) lives across the
 * module boundary. a conformance test pins that no type
 * here ever appears in a public API signature of `:sdk` / `:rn` / rn-core.
 */
/**
 * The typed persistence boundary for loss-authority state.
 *
 * THERE IS DELIBERATELY NO `Map<String, Any>` ANYWHERE IN THIS FILE.
 * A generic map boundary makes every future field optional-by-accident: a
 * writer can persist a key no reader understands, and a reader silently
 * ignores a key the writer considered load-bearing. Both failures are
 * invisible — which is exactly the defect class this programme keeps paying
 * for. Every record form below is a closed sealed subclass with a fixed field
 * order, so an unrecognised kind is a typed decode FAILURE, never a shrug.
 *
 * NO USER CONTENT MAY BE REPRESENTED HERE. There is no field for a URL, a
 * request body, a header, an interaction coordinate, view content or a
 * credential — not "sanitised", ABSENT. A field that does not exist cannot be
 * populated by a future caller in a hurry.
 */
public sealed class LossRecord {

    /** Stable, immutable, unique per logical intent. The idempotency key. */
    public abstract val intentId: String

    /**
 * A canonical loss at ONE logical sequence.
 *
 * `logicalSequence` is the AUTHORITY for `countsThrough(E)`, NOT the
 * record's position in the WAL. A later finalization may append
 * information about an earlier sequence, so ordering the file is not
 * ordering the timeline.
 */
    public data class LossRecorded(
        override val intentId: String,
        val logicalSequence: Long,
        val family: String,
        val count: Long,
    ) : LossRecord()

    /** The durable sequence high-water. Recovery may never LOWER it. */
    public data class HighWaterAdvanced(
        override val intentId: String,
        val highWater: Long,
    ) : LossRecord()

    /**
 * An admission whose outcome is not yet known. Unresolved until finalized.
 *
 * THE FAMILY IS PERSISTED, and it has to be. Step 4 converts an
 * unresolved admission into canonical loss, and the family is the whole
 * content of that conversion — deriving it later from an event type that
 * was never written down would mean guessing, and a guessed family
 * attributes a lost frame to a lost interaction.
 */
    public data class AdmissionIntent(
        override val intentId: String,
        val logicalSequence: Long,
        val family: String,
    ) : LossRecord()

    /** An eviction whose outcome is not yet known. Unresolved until finalized. */
    public data class EvictionIntent(
        override val intentId: String,
        val logicalSequence: Long,
        val family: String,
        val count: Long,
    ) : LossRecord()

    /**
 * Resolves a previously-appended intent.
 *
 * `outcome` is a CLOSED enum, not free text — a persisted string
 * outcome would eventually carry a provider message, and a provider
 * message is user-adjacent content.
 */
    public data class IntentFinalized(
        override val intentId: String,
        val resolvesIntentId: String,
        val outcome: Outcome,
        /**
 * WHAT BECAME of the sequence. REQUIRED when [outcome] is `LOST`, must
 * be absent otherwise — validated by the codec and the state machine,
 * so a LOST with no cause is a typed decode failure, never a shrug
 * (a conformance test row 2: the authority remembers what became of N).
 */
        val cause: LossCause? = null,
    ) : LossRecord() {
        public enum class Outcome { ADMITTED, LOST, DELIVERED }
    }

    /**
 * This SESSION's loss authority can no longer make claims.
 *
 * MONOTONIC BY CONSTRUCTION: no record kind clears it ([OwnedDisableCleared]
 * clears only an [OwnedDisable] naming its owner, never this record). A
 * second `CheckpointDisabled` for the same session is idempotent (the first
 * reason is kept), and no compaction folds it away. It lives in the
 * authority store, never in memory alone, so a restart re-reads it before
 * anything could emit. The gap it describes stays in sequence arithmetic —
 * a disabled session keeps its numbers, it simply makes no claim about them.
 */
    public data class CheckpointDisabled(
        override val intentId: String,
        val reason: DisableReason,
    ) : LossRecord()

    /**
 * F4-P2 — this session's loss authority can
 * make no claim, and the fact is BOUND TO ITS OWNER: the exact P2
 * reconciliation (its durable fact id) that installed it.
 *
 * THE ONE NARROW EXCEPTION TO D2-2, AND ONLY HERE. [reason] must be
 * [ownerClearable] (exactly `UNATTRIBUTABLE_GAP` today) and the session must
 * not already be disabled — so a second owner can never be layered on a
 * disable, and a legacy [CheckpointDisabled] (no owner) is never converted
 * into an owned one. It may later be cleared by [OwnedDisableCleared] naming
 * the SAME owner, and by nothing else. A legacy [CheckpointDisabled] arriving
 * afterwards REPLACES it (the stronger, monotonic fact wins and the owner is
 * dropped), which is what makes "no other disable owner/reason remains"
 * decidable from durable state after a compaction.
 */
    public data class OwnedDisable(
        override val intentId: String,
        val reason: DisableReason,
        /** The durable id of the reconciliation that owns this disable — never empty. */
        val ownerId: String,
    ) : LossRecord()

    /**
 * F4-P2 — the ONE record kind that clears a disable, and it
 * clears ONLY the owner-bound disable whose [ownerId] it names. Refused when
 * the session is not disabled, when its disable carries no owner (a legacy
 * `CheckpointDisabled`, including a legacy `UNATTRIBUTABLE_GAP`), or when the
 * owner differs. Touches nothing else: no high-water, no owned entry, no
 * batch, no sequence — the gap the disable described stays in arithmetic; the
 * session merely regains the right to make claims. The record stays in the
 * WAL as the durable clear history.
 */
    public data class OwnedDisableCleared(
        override val intentId: String,
        val ownerId: String,
    ) : LossRecord()

    /** Everything through this sequence is known delivered. */
    /**
 * ONE remote fact: this exact frozen batch was committed.
 *
 * ONE RECORD, ONE APPEND, ONE FSYNC — because the remote fact is
 * BATCH-WIDE. A loop of per-sequence markers would let a crash after eight
 * of twenty leave local state saying eight delivered while the server's
 * truth is twenty, and nothing durable could bridge the gap without asking
 * the server again.
 *
 * Metadata only: no payload, URL, header, event content or credential. The
 * ack TYPE is deliberately absent — accepted and duplicate are two proofs of
 * the same fact and must produce the same record.
 */
    public data class DeliveredBatch(
        override val intentId: String,
        val sessionId: String,
        val chunkId: String,
        val recordCount: Int,
        /** Canonical lowercase hex of the 32-byte content digest. */
        val contentSha256: String,
        /** Canonical [DeliveredSeqCodec] bytes — the exact set, never a range. */
        val sequenceEncoding: ByteArray,
    ) : LossRecord() {
        override fun equals(other: Any?): Boolean =
            other is DeliveredBatch && intentId == other.intentId &&
                sessionId == other.sessionId && chunkId == other.chunkId &&
                recordCount == other.recordCount && contentSha256 == other.contentSha256 &&
                sequenceEncoding.contentEquals(other.sequenceEncoding)
        override fun hashCode(): Int = intentId.hashCode()
    }

    public data class DeliveredMarker(
        override val intentId: String,
        val throughSequence: Long,
    ) : LossRecord()

    /**
 * The SERVER's acknowledged authority. Never the local one.
 *
 * Applying this must not move current state. Acknowledged state that
 * could overwrite a newer local snapshot would let a stale server reply
 * erase locally-known loss.
 */
    public data class AckApplied(
        override val intentId: String,
        val throughSequence: Long,
        val counts: Map<String, Long>,
        val acknowledgedTerminal: Boolean,
        /**
 * THE CHECKPOINT THIS ACK ANSWERS — new in authority container v2.
 *
 * the capture specification requires that *"the only thing that may clear pending loss
 * is a validated `loss-checkpoint-ack-1` **bound to the exact checkpoint
 * it answers**"*. Under v1 this record carried no checkpoint identity at
 * all, so there was nothing to bind to and the specification was unimplementable.
 *
 * REQUIRED, NOT OPTIONAL, AND THERE IS NO "ABSENT MEANS EMPTY".
 * `require(f, 4)` becomes `require(f, 5)` — field-count discipline, not
 * relaxation. An ack that settles nothing has no reason to exist. Under
 * v1 this is a breaking read, which is precisely why it rides the v2
 * container bump rather than arriving as a tolerated extra field.
 */
        val settlesCheckpointId: String,
    ) : LossRecord()

    /** The session's terminal state was REQUESTED locally. Not acknowledged. */
    public data class TerminalRequested(
        override val intentId: String,
    ) : LossRecord()

    /**
 * A CHECKPOINT WAS REQUESTED and is awaiting an ack — the durable
 * fact v1 could not express at all.
 *
 * **NAMED FOR WHAT IT IS.** "Pending" describes a STATE, not a fact; the
 * durable record is that a checkpoint **was requested**, and the pending-ness
 * is DERIVED (a requested checkpoint with no settling ack). v3 of the design
 * called the constant `K_PENDING` and the codec name `"pending"` while naming
 * the record `CheckpointRequested` — three names for one thing, two of which
 * described a state the record does not hold. The snapshot's `pending*`
 * fields keep their names deliberately: they ARE the projection of the
 * derived state, which is exactly what "pending" correctly describes.
 *
 * [TerminalRequested] is **unchanged and not subsumed**. It is the local
 * terminal-state flag; [claimsTerminal] is what a PARTICULAR checkpoint
 * asserts. Two facts, two fields — and "no reuse of `AckApplied` for pending
 * state" is respected in both directions: this record is the pending state,
 * `AckApplied` only settles.
 *
 * **FROZEN MEANS FROZEN.** No record kind may mutate a pending
 * checkpoint's [frozenCounts] or [throughSequence]. A later `LossRecorded`,
 * `AdmissionIntent`, `IntentFinalized` or `EvictionIntent` changes the LIVE
 * state and leaves the frozen claim byte-identical — because the natural
 * implementation (recomputing counts when they are read) would silently make
 * a checkpoint claim something it never sent.
 */
    public data class CheckpointRequested(
        override val intentId: String,
        /** The identity the ack must be bound to. */
        val checkpointId: String,
        /** The interval end this checkpoint claims. */
        val throughSequence: Long,
        /** Canonical counts, FROZEN at request time. */
        val frozenCounts: Map<String, Long>,
        /** Whether this checkpoint claims terminality. */
        val claimsTerminal: Boolean,
    ) : LossRecord()
}

/**
 * Canonical cumulative counts. Exactly the shared family set, always all
 * thirteen keys, always in the shared order.
 *
 * CONSUMES THE MERGED SHARED AUTHORITY (`LossFamilies.ALL`) rather than
 * restating it. A second list is a second opinion, and the two drift.
 */
public object LossCounts {

    public fun zero(): MutableMap<String, Long> {
        val m = LinkedHashMap<String, Long>(LossFamilies.ALL.size)
        for (f in LossFamilies.ALL) m[f] = 0L
        return m
    }

    /** Fails CLOSED on a family outside the canonical set. */
    public fun requireCanonical(family: String) {
        if (!LossFamilies.ALL.contains(family)) {
            throw LossFormatException("family_not_canonical")
        }
    }

    public fun add(into: MutableMap<String, Long>, family: String, count: Long) {
        requireCanonical(family)
        into[family] = (into[family] ?: 0L) + count
    }

    /** Deterministic canonical rendering: every family, shared order. */
    public fun encode(counts: Map<String, Long>): String {
        // A A FOREIGN KEY IS REFUSED, NOT DROPPED. The rendering iterates the canonical set,
        // so a renamed or unknown family used to vanish silently and its count arrived on the
        // wire as ZERO -- a silently wrong count, which is worse than a refusal because the
        // caller believes it recorded something. the specification's whole argument for having NO family-set
        // digest is that the encoding carries NAMES and an unknown name is REFUSED;
        // a silent drop on the WRITE side would leave that argument true only on the read side.
        //
        // A A MISSING key is still tolerated (rendered 0): `zero` guarantees every family is
        // present, and a SUBSET is an under-specified map rather than a wrong one.
        for (k in counts.keys) requireCanonical(k)
        return LossFamilies.ALL.joinToString(",") { "$it:${counts[it] ?: 0L}" }
    }

    public fun decode(s: String): MutableMap<String, Long> {
        val out = zero()
        if (s.isEmpty()) return out
        for (part in s.split(",")) {
            val i = part.lastIndexOf(':')
            if (i <= 0) throw LossFormatException("counts_malformed")
            val f = part.substring(0, i)
            requireCanonical(f)
            out[f] = part.substring(i + 1).toLongOrNull()
                ?: throw LossFormatException("counts_malformed")
        }
        return out
    }
}

public class LossFormatException(public val reason: String) : Exception(reason)

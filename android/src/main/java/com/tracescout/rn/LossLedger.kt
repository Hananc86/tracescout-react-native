package com.tracescout.rn

import java.util.Collections

/**
 * PRODUCER LOSS ACCOUNTING v1 — durable state, the crash contract, the
 * INTERVAL-BOUND checkpoint rule, and the FROZEN-CHECKPOINT acknowledgement
 * contract, as a PURE state machine.
 *
 * WHY PURE. The decisions that matter — "did the pressure transaction
 * commit?", "is this checkpoint arithmetically entailed by the interval it
 * claims?" and "which losses did this acknowledgement actually cover?" — are
 * functions of durable facts and nothing else. Written purely they are
 * testable for every crash window, every interval and every interleaving
 * without a device, a clock or a filesystem. Persistence and encryption live
 * in the Android layer; this file decides.
 *
 * ## The transaction this brackets
 *
 * ledger first, crash before eviction -> a declared loss that never happened
 * queue first, crash before ledger -> a real loss that is undisclosed
 *
 * The PREPARED intent is the write-ahead log, `compactAndAppend` is the one
 * atomic step, and [reconcile] is what a restart consults.
 *
 * ## The acknowledgement this freezes, and what it REQUIRES
 *
 * A checkpoint is EMITTED with a declared total, and losses may be committed
 * while it awaits its acknowledgement. The acknowledgement covers what was
 * DECLARED, never what the ledger holds when the ack happens to arrive — so
 * the emitted checkpoint is frozen as a [PendingCheckpoint] and the
 * acknowledgement rebases authority from that snapshot alone.
 *
 * AND A CHUNK ACK ALONE MAY NEVER CLEAR IT. `mobile-ack-1` proves the
 * EVENTS committed; it says nothing about the loss authority, which is a
 * separate row written in the same transaction and acknowledged by a separate
 * `loss-checkpoint-ack-1`. Clearing a durable loss ledger on the strength of
 * an ack that does not mention loss is how a producer forgets a loss the
 * server never recorded. Settlement therefore REQUIRES the committed loss
 * evidence, on BOTH the delivery and the metadata-only path.
 */
public object LossLedger {

    public const val VERSION: Int = 1

    /**
 * The `protocol` value a `loss-checkpoint-ack-1` must carry.
 *
 * A TWIN, PINNED TO THE CANONICAL MAP. The authority is
 * `mobile-protocol/src/loss-families.cjs`; this constant is pinned to it
 * through the GENERATED `parity-vectors/loss-family-vectors.json`, exactly
 * like the family vocabulary. A hardcoded protocol string nobody compares
 * against the protocol is the second-source-of-truth defect one layer down.
 */
    public const val LOSS_CHECKPOINT_ACK: String = "loss-checkpoint-ack-1"

    // ══════════════════════════════════════════════════════════ the one truth

    /**
 * A single record this plan will destroy.
 *
 * ONE REPRESENTATION, NOT TWO. An earlier shape carried `victimDigests`
 * AND a separately maintained `deltas` map, which is two descriptions of
 * one fact: they agree until a planner bug makes them disagree, and then
 * the ledger counts something the queue did not lose (or vice versa) with
 * nothing able to detect it. Deltas are DERIVED from these, so the
 * disagreement is unrepresentable.
 */
    public data class Victim(
        val sessionId: String,
        val sequence: Long,
        val family: String,
        val digest: String,
    )

    public enum class CheckpointKind {
        /** Rides with a delivered chunk `[first.last]`. */
        DELIVERY,
        /** No chunk; the authority moves, or finality alone is disclosed. */
        METADATA_ONLY,
    }

    /**
 * The FROZEN emitted checkpoint — exactly what the server was told, kept
 * until the server proves it committed.
 *
 * IMMUTABLE BY CONSTRUCTION, NOT BY CONVENTION. The counts are copied
 * and wrapped at construction and [wire] hands out an unmodifiable view, so
 * neither the caller's map nor a returned view can mutate a snapshot after
 * the fact. A snapshot the emitter can still edit is not a record of what
 * was sent. That is why this is a normal class with a private constructor
 * and an [of] factory rather than a data class: a data class would hold the
 * caller's map by reference and the copy would live at one call site,
 * where someone can forget it.
 *
 * Identity is the checkpoint's EXISTING wire identity — session, kind and
 * interval — which the server already echoes. No new wire field exists to
 * make matching convenient.
 */
    public class PendingCheckpoint private constructor(
        public val sessionId: String,
        public val kind: CheckpointKind,
        /**
 * DELIVERY: the chunk's first sequence, never null.
 * METADATA_ONLY advance: `previousAuthority + 1`.
 * METADATA_ONLY finality-only: **null** — the covered interval is EMPTY.
 *
 * NULL, NOT `through + 1`. An empty interval used to be written as
 * `first = through + 1`, which OVERFLOWS at `Long.MAX_VALUE` and turns
 * "covers nothing" into "covers everything from Long.MIN_VALUE". An
 * absent first sequence cannot overflow and says the same thing without
 * arithmetic.
 */
        public val firstSequence: Long?,
        /** DELIVERY: the chunk's last sequence. METADATA_ONLY: the authority. */
        public val throughSequence: Long,
        declaredCountsInput: Map<String, Long>,
        /** The durable terminal flag AT emission. A retry re-sends this value. */
        public val terminal: Boolean,
    ) {
        /** Defensive, unmodifiable copy. Aliasing the caller's map is unrepresentable. */
        public val declaredCounts: Map<String, Long> =
            Collections.unmodifiableMap(LinkedHashMap(declaredCountsInput))

        /**
 * The sum of exactly the counts this snapshot froze.
 *
 * DERIVED, NEVER SUPPLIED. It used to be a separate constructor
 * parameter, so a snapshot could be built with canonical ZERO counts and
 * a total of 2: the loss-evidence check compares the server's
 * `acceptedCounts` against the ZERO counts and passes, and settlement
 * then advances `acknowledgedTotal` by 2 on evidence that declared
 * nothing. Copying the map removed ALIASING; it did not remove that
 * internal inconsistency. Deriving the total makes the disagreement
 * unrepresentable, which is the same rule `PressureIntent.deltas`
 * already follows.
 *
 * Checked arithmetic, because a corrupted durable ledger could otherwise
 * wrap the sum and under-state the loss.
 */
        public val declaredTotal: Long

        init {
            require(kind != CheckpointKind.DELIVERY || firstSequence != null) {
                "a delivery checkpoint always covers a chunk and must carry its first sequence"
            }
            require(declaredCounts.keys.toSortedSet() == LossFamilies.ALL.toSortedSet()) {
                "declaredCounts must be exactly the canonical family set"
            }
            var t = 0L
            for (f in LossFamilies.ALL) {
                val v = declaredCounts[f] ?: 0L
                require(v >= 0L) { "a negative count is not a loss: $f=$v" }
                require(t <= Long.MAX_VALUE - v) { "declared counts overflow at $f" }
                t += v
            }
            declaredTotal = t
        }

        /**
 * The wire document. A retry sends a document EQUAL BY VALUE to this
 * one — map equality over the same keys and values.
 *
 * "Equal by value", never "byte-identical": this is a Map, not
 * serialized bytes. A byte claim belongs only to evidence taken from an
 * actual serializer, and this layer has none.
 */
        public fun wire(): Map<String, Any> = Collections.unmodifiableMap(
            linkedMapOf<String, Any>(
                "lossAccountingVersion" to VERSION,
                "sessionId" to sessionId,
                "counts" to declaredCounts,
                // int64 through a JS number loses precision above 2^53 — the one
                // place a rounding error becomes mis-stated loss.
                "throughSequence" to throughSequence.toString(),
                "terminal" to terminal,
            ),
        )

        /**
 * Does this acknowledgement name THIS checkpoint? DELIVERY matches on
 * the full chunk interval; METADATA_ONLY carries no chunk, so it matches
 * on the authority it names.
 *
 * The identity fields are the ones the producer SENT and the server's
 * responses already carry — session, kind (from the bound request
 * context) and interval. Nothing here is read out of the server's answer
 * except the sequence numbers it echoes.
 *
 * This is CHUNK IDENTITY ONLY. It is necessary and never sufficient:
 * settlement additionally requires the committed loss evidence.
 */
        public fun matches(ack: Acknowledgement): Boolean =
            ack.sessionId == sessionId && ack.kind == kind &&
                ack.throughSequence == throughSequence &&
                (kind == CheckpointKind.METADATA_ONLY || ack.firstSequence == firstSequence)

        override fun equals(other: Any?): Boolean = other is PendingCheckpoint &&
            sessionId == other.sessionId && kind == other.kind &&
            firstSequence == other.firstSequence && throughSequence == other.throughSequence &&
            declaredCounts == other.declaredCounts && terminal == other.terminal
        override fun hashCode(): Int {
            var h = sessionId.hashCode()
            h = h * 31 + kind.hashCode()
            h = h * 31 + (firstSequence?.hashCode() ?: 0)
            h = h * 31 + throughSequence.hashCode()
            h = h * 31 + declaredCounts.hashCode()
            return h * 31 + terminal.hashCode()
        }
        override fun toString(): String =
            "PendingCheckpoint($sessionId, $kind, [${firstSequence ?: "-"}..$throughSequence], " +
                "total=$declaredTotal, terminal=$terminal)"

        public companion object {
            @JvmStatic
            public fun of(
                sessionId: String,
                kind: CheckpointKind,
                firstSequence: Long?,
                throughSequence: Long,
                declaredCounts: Map<String, Long>,
                terminal: Boolean,
            ): PendingCheckpoint = PendingCheckpoint(sessionId, kind, firstSequence, throughSequence,
                declaredCounts, terminal)
        }
    }

    /**
 * `loss-checkpoint-ack-1` as the server sends it — the proof that the loss
 * authority COMMITTED.
 *
 * `kind` IS ABSENT ON PURPOSE. The server does not echo which request
 * shape it answered, and inventing a field so the producer could match more
 * conveniently would be a wire change. The request kind comes from the bound
 * request context ([Acknowledgement.kind]), which is what the producer sent.
 *
 * `acceptedThroughSequence` is a STRING for the same int64 reason the
 * checkpoint's own `throughSequence` is.
 */
    public data class LossCheckpointAck(
        val protocol: String,
        val lossAccountingVersion: Int,
        /** `accepted` | `duplicate` settle; `older` does not; anything else is malformed. */
        val state: String,
        val sessionId: String,
        val acceptedThroughSequence: String?,
        val acceptedCounts: Map<String, Long>,
        val terminal: Boolean,
    )

    /**
 * What the server's response says, in the fields the existing responses
 * ALREADY carry: the chunk identity from `mobile-ack-1` (or the
 * metadata-only boundary), plus the committed loss evidence.
 *
 * [firstSequence] is null for METADATA_ONLY. [loss] is null when NO
 * `loss-checkpoint-ack-1` arrived — which is a real, currently reachable
 * state and must never settle anything.
 */
    public data class Acknowledgement(
        val sessionId: String,
        val kind: CheckpointKind,
        val firstSequence: Long?,
        val throughSequence: Long,
        val loss: LossCheckpointAck?,
    )

    /**
 * The cumulative per-session authority.
 *
 * TWO SEQUENCE FACTS, NEVER ONE VARIABLE. An earlier version used a
 * single `throughSequence` that `commit` advanced from the incoming
 * record while its own comment called it "the sequence through which the
 * ledger is authoritative". Those are different facts — one is local
 * progress, the other is what the SERVER has acknowledged — and a variable
 * holding both is wrong in whichever reading you are not currently using.
 *
 * [acknowledgedThrough] and [acknowledgedTotal] move ONLY from the
 * authoritative ack path ([acknowledge]), and ONLY from a matching
 * [pending] snapshot backed by committed loss evidence.
 *
 * THIS TRANCHE SUPPORTS EXACTLY ONE PENDING CHECKPOINT PER SESSION, and
 * there is NO operation that retires one without evidence. A parked or
 * quarantined chunk, or a request whose outcome is unknown, does NOT
 * establish that the server failed to commit — so the pending snapshot is
 * KEPT as evidence and further emission is refused until it settles. The
 * consequence is deliberate and is stated rather than engineered around:
 * an unsettled checkpoint blocks later checkpoints for that session, and
 * retiring one needs an explicit recovery contract that does not exist yet.
 */
    public data class Ledger(
        val sessionId: String,
        val counts: Map<String, Long> = LossFamilies.emptyCounts(),
        /** Server-acknowledged authority boundary. -1 = none yet. */
        val acknowledgedThrough: Long = -1L,
        /** Cumulative total AT that boundary, as DECLARED to the server. */
        val acknowledgedTotal: Long = 0L,
        /**
 * Does the ACKNOWLEDGED boundary at [acknowledgedThrough] carry
 * terminality? A SETTLED fact about the server's state, never a claim.
 *
 * **THIS IS NOT [terminal], AND MERGING THE TWO WOULD BE A WIRE
 * DEFECT.** [terminal] is the producer's own LOCAL durable claim and is
 * what `freeze` writes into the emitted document; this says the server
 * has already acknowledged such a claim. Folding them would make a
 * checkpoint assert a fact the producer never requested — see
 * `LossAuthority.projectLedger`, whose KDoc states that rule and is the
 * reason this is a separate field rather than a wider [terminal].
 *
 * IT QUALIFIES [acknowledgedThrough]; IT DOES NOT RE-HOME IT. There
 * is exactly one acknowledged boundary and it lives in
 * [acknowledgedThrough] — this is a scalar ABOUT that boundary, which
 * is what keeps it from being a second representation of one fact (the
 * an explicit product decision the specification / a product decision rule that forbids reconstructing a whole
 * [PendingCheckpoint] here).
 *
 * SOUND ONLY BECAUSE TERMINAL IS ONE-WAY AND POST-TERMINAL
 * ACCOUNTING IS REFUSED. "Acknowledged terminal" needs no sequence of
 * its own because once terminality settles at T the server answers
 * `loss_accounting_after_terminal` to anything beyond T, so the
 * acknowledged boundary cannot move past the boundary that carried it.
 * If that server rule ever relaxes, this field needs its own sequence.
 */
        val acknowledgedTerminal: Boolean = false,
        /** Idempotency is INTERNAL: see [commit]. */
        val lastAppliedPlanId: String? = null,
        /** One-way, and durable before it may be asserted. */
        val terminal: Boolean = false,
        /** The emitted, not-yet-acknowledged checkpoint. At most one. */
        val pending: PendingCheckpoint? = null,
        /** The most recently settled snapshot — what a DUPLICATE ack is matched against. */
        val lastAcknowledged: PendingCheckpoint? = null,
        val version: Int = VERSION,
    ) {
        public fun total(): Long = counts.values.sum()
        /** Loss accumulated since the last acknowledged authority boundary. */
        public fun unacknowledgedLoss(): Long = total() - acknowledgedTotal
    }

    /**
 * The PREPARED pressure intent — the write-ahead record.
 *
 * `incomingRecord` IS THE BYTES, NOT A DIGEST. If the process dies after
 * PREPARE and before the atomic rewrite, the incoming event exists only in
 * volatile memory unless the intent preserved it — so a digest-only intent
 * would have lost the very telemetry it was created to protect. Transaction
 * payload: already-masked, encrypted at rest, never logged or exported,
 * discarded once settled.
 */
    public data class PressureIntent(
        val planId: String,
        val sessionId: String,
        val oldFingerprint: String,
        val newFingerprint: String,
        val victims: List<Victim>,
        val incomingRecord: ByteArray,
        val incomingSequence: Long,
    ) {
        /** DERIVED — never stored alongside, never a second opinion. */
        public fun deltas(): Map<String, Long> {
            val m = HashMap<String, Long>()
            for (v in victims) m[v.family] = (m[v.family] ?: 0L) + 1L
            return m
        }

        override fun equals(other: Any?): Boolean = other is PressureIntent
            && planId == other.planId && sessionId == other.sessionId
            && oldFingerprint == other.oldFingerprint && newFingerprint == other.newFingerprint
            && victims == other.victims && incomingSequence == other.incomingSequence
            && incomingRecord.contentEquals(other.incomingRecord)
        override fun hashCode(): Int =
            (((planId.hashCode() * 31 + oldFingerprint.hashCode()) * 31
                + newFingerprint.hashCode()) * 31 + incomingSequence.hashCode()) * 31 +
                incomingRecord.contentHashCode()
    }

    // ══════════════════════════════════════════════════════ the crash contract

    public enum class Verdict {
        /** Store still OLD: the rewrite did not commit. Resume; commit NO loss. */
        RESUME_TRANSACTION,
        /** Store is NEW: the rewrite committed. Finalize EXACTLY once. */
        FINALIZE_LOSS,
        /** Neither, or unreadable. Fail closed: no eviction, no loss commit. */
        FAIL_CLOSED,
    }

    /**
 * INFERRING HISTORY FROM A RECORD COUNT OR A TIMESTAMP CANNOT WORK. A
 * plan that removes one record and appends one leaves the count unchanged;
 * a timestamp says when a file was touched, not which logical state it
 * holds. The fingerprint is the only discriminator.
 */
    @JvmStatic
    public fun reconcile(intent: PressureIntent, currentFingerprint: String?): Verdict = when {
        currentFingerprint == null -> Verdict.FAIL_CLOSED
        // Checked FIRST. A degenerate plan whose old and new states are
        // identical must never FINALIZE — `compactAndAppend` cannot produce one
        // (it always appends), but a planner bug could, and the reconciler must
        // not be what turns a bug into a false loss claim.
        currentFingerprint == intent.oldFingerprint -> Verdict.RESUME_TRANSACTION
        currentFingerprint == intent.newFingerprint -> Verdict.FINALIZE_LOSS
        else -> Verdict.FAIL_CLOSED
    }

    // ═══════════════════════════════════════════════════════════════ commit

    /** Why an intent was refused. A refusal mutates NOTHING. */
    public enum class Refusal {
        UNKNOWN_FAMILY,
        SESSION_MISMATCH,
        VICTIM_SESSION_MISMATCH,
        DUPLICATE_VICTIM,
        EMPTY_PLAN,
        COUNTER_OVERFLOW,
    }

    public sealed class CommitResult {
        public data class Applied(val ledger: Ledger) : CommitResult()
        /** The same plan already applied. The ledger is returned unchanged. */
        public data class Duplicate(val ledger: Ledger) : CommitResult()
        /** ZERO mutation. Never a partial application. */
        public data class Refused(val reason: Refusal, val detail: String) : CommitResult()
    }

    /**
 * Apply a settled plan.
 *
 * IDEMPOTENCY IS INTERNAL NOW. It used to take an `alreadyApplied` set
 * the caller maintained, which is only idempotent if the caller updates
 * that set ATOMICALLY with the ledger — two effects, and a crash between
 * them replays the increment. `lastAppliedPlanId` lives IN the ledger, so
 * one durable state transition carries both.
 *
 * This is sufficient only because the architecture permits exactly ONE
 * outstanding pressure intent at a time (the planner is demand-driven on
 * the single append path). A test pins that serialization; if it ever
 * becomes false, `lastAppliedPlanId` silently stops being enough and an
 * older plan could be re-applied after a newer one.
 *
 * A COMMIT NEVER TOUCHES [Ledger.pending]. A checkpoint in flight was
 * emitted with the counts it declared; the loss landing here belongs to
 * the NEXT interval and stays unacknowledged after the pending ack.
 *
 * FAIL CLOSED ON ANYTHING INVALID. An unknown family used to be SKIPPED,
 * which fails OPEN by omission: the queue loses a record and the ledger
 * under-counts, producing undisclosed loss. The server refuses an unknown
 * family; so does this.
 */
    @JvmStatic
    public fun commit(ledger: Ledger, intent: PressureIntent): CommitResult {
        if (ledger.lastAppliedPlanId == intent.planId) return CommitResult.Duplicate(ledger)

        if (ledger.sessionId != intent.sessionId) {
            return CommitResult.Refused(Refusal.SESSION_MISMATCH,
                "ledger is for another session")
        }
        if (intent.victims.isEmpty()) {
            // A plan that destroys nothing has nothing to declare; treating it
            // as a commit would advance idempotency state for a no-op.
            return CommitResult.Refused(Refusal.EMPTY_PLAN, "no victims")
        }
        val seen = HashSet<String>()
        for (v in intent.victims) {
            if (!ledger.counts.containsKey(v.family)) {
                // A guessed or unrecognised family is a fabricated attribution.
                return CommitResult.Refused(Refusal.UNKNOWN_FAMILY, v.family)
            }
            if (v.sessionId != intent.sessionId) {
                return CommitResult.Refused(Refusal.VICTIM_SESSION_MISMATCH,
                    "victim at sequence ${v.sequence}")
            }
            if (!seen.add(v.digest)) {
                // The same record counted twice would over-declare loss.
                return CommitResult.Refused(Refusal.DUPLICATE_VICTIM, v.digest)
            }
        }

        val deltas = intent.deltas()
        val next = ledger.counts.toMutableMap()
        for ((family, n) in deltas) {
            val cur = next[family] ?: 0L
            // Derived deltas are counts of victims, so they cannot be negative;
            // overflow is still checked because a corrupted durable ledger could
            // make the addition wrap, and a wrapped counter reports a smaller
            // loss than actually occurred.
            if (n < 0L || cur > Long.MAX_VALUE - n) {
                return CommitResult.Refused(Refusal.COUNTER_OVERFLOW, family)
            }
            next[family] = cur + n
        }
        return CommitResult.Applied(
            ledger.copy(counts = next, lastAppliedPlanId = intent.planId))
    }

    // ═════════════════════════════════════════════════ the interval contract

    public enum class CheckpointRefusal {
        /** The declared loss does not equal the interval it claims to explain. */
        INTERVAL_MISMATCH,
        /** The chunk does not begin after the acknowledged boundary. */
        NOT_AFTER_AUTHORITY,
        /** first > last, or a negative sequence. */
        MALFORMED_INTERVAL,
        /** A checkpoint is already in flight; this tranche allows exactly one. */
        CHECKPOINT_PENDING,
        /** A same-boundary disclosure that is neither terminal nor an advance. */
        NOTHING_TO_DISCLOSE,
    }

    public sealed class CheckpointResult {
        /**
 * [ledger] carries the frozen [pending] snapshot and MUST be the state
 * persisted BEFORE the request is sent — a pending checkpoint written
 * after the request cannot survive a crash during it.
 */
        public data class Emit(
            val ledger: Ledger,
            val checkpoint: Map<String, Any>,
            val pending: PendingCheckpoint,
        ) : CheckpointResult()
        public data class Refused(val reason: CheckpointRefusal, val detail: String) : CheckpointResult()
    }

    /**
 * The checkpoint accompanying a normally delivered chunk `[first.last]`.
 *
 * THE OLD SHAPE COULD NOT BE CORRECT. It took only the committed last
 * sequence, so it could emit a checkpoint whose cumulative counters
 * contained losses belonging to some OTHER interval — the numbers looked
 * plausible and were not entailed by anything. The contract is arithmetic:
 *
 * P = previously acknowledged authority
 * chunk = [F. L]
 * missing interval = [P+1. F-1]
 * loss declared since P MUST EQUAL size(missing interval)
 * throughSequence = L
 *
 * A producer that cannot satisfy that has either lost records it did not
 * account for, or accounted for records it did not lose. Both are refusals,
 * not roundings.
 */
    @JvmStatic
    public fun checkpointForDelivery(ledger: Ledger, first: Long, last: Long): CheckpointResult {
        if (ledger.pending != null) {
            return CheckpointResult.Refused(CheckpointRefusal.CHECKPOINT_PENDING,
                "a checkpoint through ${ledger.pending.throughSequence} awaits acknowledgement")
        }
        if (first < 0L || last < first) {
            return CheckpointResult.Refused(CheckpointRefusal.MALFORMED_INTERVAL, "[$first..$last]")
        }
        if (first <= ledger.acknowledgedThrough) {
            return CheckpointResult.Refused(CheckpointRefusal.NOT_AFTER_AUTHORITY,
                "chunk starts at $first, authority is ${ledger.acknowledgedThrough}")
        }
        val missing = first - ledger.acknowledgedThrough - 1
        val declared = ledger.unacknowledgedLoss()
        if (declared != missing) {
            return CheckpointResult.Refused(CheckpointRefusal.INTERVAL_MISMATCH,
                "declared=$declared missing=$missing")
        }
        return freeze(ledger, CheckpointKind.DELIVERY, first, last)
    }

    /**
 * A metadata-only checkpoint. Two legitimate shapes, and the second is the
 * one this used to make impossible:
 *
 * 1. ADVANCE — the authority moves and EVERY newly covered sequence must
 * be accounted as loss.
 * 2. FINALITY-ONLY — the boundary does NOT move and the counts do not
 * change; only `terminal` becomes true.
 *
 * REFUSING SHAPE 2 PREVENTED A SESSION FROM EVER DISCLOSING FINALITY
 * ONCE ITS LAST REAL RECORD HAD BEEN ACKNOWLEDGED. The only way past the
 * old `newThrough <= acknowledgedThrough` refusal would have been to invent
 * a sequence or a loss, i.e. to fabricate a record so that finality could
 * be reported. The server explicitly permits this transition — identical
 * counts, unchanged boundary, `terminal` false → true, answered
 * `terminal_transition` and one-way — so the producer must be able to
 * express it without inventing anything.
 */
    @JvmStatic
    public fun checkpointForMetadataOnly(ledger: Ledger, newThrough: Long): CheckpointResult {
        if (ledger.pending != null) {
            return CheckpointResult.Refused(CheckpointRefusal.CHECKPOINT_PENDING,
                "a checkpoint through ${ledger.pending.throughSequence} awaits acknowledgement")
        }
        if (newThrough < ledger.acknowledgedThrough) {
            return CheckpointResult.Refused(CheckpointRefusal.NOT_AFTER_AUTHORITY,
                "newThrough=$newThrough authority=${ledger.acknowledgedThrough}")
        }
        if (newThrough == ledger.acknowledgedThrough) {
            // FINALITY-ONLY. Three things must hold, and each closed a real hole.
            //
            // 1. THE BOUNDARY MUST BE A REAL, ACKNOWLEDGED SEQUENCE. `-1` is
            // the ABSENCE sentinel, not a sequence, so a fresh terminal ledger
            // used to emit `throughSequence: "-1"` — a document the server must
            // refuse, because its `throughSequence` is a canonical decimal
            // string. A session with no acknowledged boundary has no finality to
            // disclose at one.
            if (ledger.acknowledgedThrough < 0L) {
                return CheckpointResult.Refused(CheckpointRefusal.NOTHING_TO_DISCLOSE,
                    "no acknowledged boundary yet; there is no sequence to be final at")
            }
            // 2. TERMINALITY MUST NOT ALREADY BE ACKNOWLEDGED AT IT. Once a
            // terminal snapshot has settled at this boundary the server holds the
            // fact; re-emitting discloses nothing and would occupy the single
            // pending slot for a claim already committed.
            if (!ledger.terminal) {
                return CheckpointResult.Refused(CheckpointRefusal.NOTHING_TO_DISCLOSE,
                    "boundary unchanged at $newThrough and the ledger is not terminal")
            }
            //
            // TWO STATE SOURCES, ONE QUESTION — AND THE FIRST IS WHY THIS
            // REFUSAL WAS STRUCTURALLY UNREACHABLE IN PRODUCTION. The pure
            // model's own settled snapshot is written ONLY by
            // `LossLedger.acknowledge`, which has ZERO production callers
            // across all five shipped roots (pinned at zero by the
            // F5_ACK_SETTLEMENT stage-leak category), so on every production
            // path `lastAcknowledged` is null and this gate could never fire.
            // The durable fact DOES exist — `EncryptedAuthorityStore
            //.acknowledgedTerminal` — and `projectLedger` now carries it, so
            // the fact and the rule are finally connected. Frozen C18.md line
            // 91: "recorded ONCE is a client property too, not only a server
            // one." Measured as C18-REOPEN FAILING on API 35 and API 26.
            //
            // THE PROJECTED LIMB NEEDS NO BOUNDARY COMPARISON. We are inside
            // `newThrough == ledger.acknowledgedThrough`, and
            // `acknowledgedTerminal` is a scalar ABOUT that boundary, so the
            // equality the snapshot limb must state explicitly is already
            // established by the enclosing branch. Adding a comparison here
            // would invent a second boundary to compare against.
            val settled = ledger.lastAcknowledged
            val settledTerminalHere =
                settled != null && settled.terminal && settled.throughSequence == newThrough
            if (ledger.acknowledgedTerminal || settledTerminalHere) {
                return CheckpointResult.Refused(CheckpointRefusal.NOTHING_TO_DISCLOSE,
                    "terminality at $newThrough is already acknowledged")
            }
            // 3. Identical counts REQUIRED, matching the server's rule.
            // Counted loss owns a sequence, so counters cannot grow while the
            // boundary stands still; declaring one here would assert a boundary
            // no producer ever advanced.
            val outstanding = ledger.unacknowledgedLoss()
            if (outstanding != 0L) {
                return CheckpointResult.Refused(CheckpointRefusal.INTERVAL_MISMATCH,
                    "finality-only requires unchanged counts, outstanding=$outstanding")
            }
            // The covered interval is EMPTY — expressed as an ABSENT first
            // sequence, never `through + 1`, which overflows at Long.MAX_VALUE.
            return freeze(ledger, CheckpointKind.METADATA_ONLY, null, newThrough)
        }
        val covered = newThrough - ledger.acknowledgedThrough
        val declared = ledger.unacknowledgedLoss()
        if (declared != covered) {
            return CheckpointResult.Refused(CheckpointRefusal.INTERVAL_MISMATCH,
                "declared=$declared covered=$covered")
        }
        return freeze(ledger, CheckpointKind.METADATA_ONLY, ledger.acknowledgedThrough + 1, newThrough)
    }

    /**
 * `terminal` COMES FROM THE DURABLE LEDGER, NOT A PARAMETER, and it is
 * FROZEN INTO THE SNAPSHOT. The old signature took an independent boolean,
 * so a checkpoint could assert terminality that had never been persisted —
 * and a process death after that emission leaves a producer whose durable
 * state says non-terminal while the server has been told otherwise. Server
 * terminality is one-way, so the producer's claim must be durable BEFORE it
 * is asserted: call [markTerminal], persist, then emit. A checkpoint
 * emitted with `terminal=false` keeps saying so on every retry, even if the
 * ledger becomes terminal while it is in flight.
 */
    private fun freeze(ledger: Ledger, kind: CheckpointKind, first: Long?, through: Long): CheckpointResult {
        val p = PendingCheckpoint.of(
            sessionId = ledger.sessionId,
            kind = kind,
            firstSequence = first,
            throughSequence = through,
            declaredCounts = ledger.counts,
            terminal = ledger.terminal,
        )
        return CheckpointResult.Emit(ledger.copy(pending = p), p.wire(), p)
    }

    /**
 * The document a RETRY sends: derived from the frozen snapshot and nothing
 * else, and EQUAL BY VALUE to the first emission. Null when nothing is
 * pending — a retry with nothing to retry is a caller bug, not a fresh
 * emission.
 */
    @JvmStatic
    public fun retryCheckpoint(ledger: Ledger): Map<String, Any>? = ledger.pending?.wire()

    /**
 * **F6 — THE RETRY DOCUMENT FOR A CHECKPOINT WHOSE ONLY SURVIVING RECORD IS
 * THE DURABLE AUTHORITY ROW.**
 *
 * **WHY THIS EXISTS, AND WHY IT IS NOT A SECOND WIRE WRITER.**
 * [retryCheckpoint] derives the document from `ledger.pending`, an IN-MEMORY
 * value. The F6 emitter projects its ledger from the durable authority at
 * call time (an explicit product decision the specification) and deliberately leaves `pending` null,
 * because the durable pending checkpoint is the AUTHORITY's row and a second
 * representation of one fact is what a product decision's ruling forbids. So after a
 * process death the frozen claim exists ONLY as that row — and a pending
 * claim that cannot be re-sent is a claim that blocks every later one
 * forever. This re-derives the document FROM the row, and it routes through
 * [PendingCheckpoint.wire] — the ONE wire writer — so the bytes are
 * identical to the first emission by construction rather than by review.
 *
 * IT RE-DERIVES, IT DOES NOT RE-DECIDE. No arithmetic, no interval check,
 * no counts comparison, no terminal decision: every field is handed in from
 * the persisted row exactly as the authority stored it. "FROZEN MEANS
 * FROZEN" is upheld because nothing here can compute a different
 * value — there is nothing to compute.
 *
 * `firstSequence` IS NULLABLE ONLY BECAUSE THE PENDING ROW DOES NOT
 * PERSIST IT. `PendingCheckpoint.of` requires it for `DELIVERY`, so a
 * DELIVERY retry must supply the chunk's own first sequence; `null` is the
 * METADATA_ONLY shape and is F7's, which F6 never constructs.
 */
    @JvmStatic
    public fun wireForDurablePending(
        sessionId: String,
        kind: CheckpointKind,
        firstSequence: Long?,
        throughSequence: Long,
        declaredCounts: Map<String, Long>,
        terminal: Boolean,
    ): Map<String, Any> = PendingCheckpoint.of(
        sessionId = sessionId,
        kind = kind,
        firstSequence = firstSequence,
        throughSequence = throughSequence,
        declaredCounts = declaredCounts,
        terminal = terminal,
    ).wire()

    // ═════════════════════════════════════════════ the acknowledgement contract

    public enum class AckRefusal {
        /** The acknowledgement is for another session. */
        SESSION_MISMATCH,
        /** Nothing is in flight and this is not a duplicate of the last settlement. */
        NO_PENDING_CHECKPOINT,
        /** Something is in flight, and this acknowledgement does not name it. */
        CHECKPOINT_MISMATCH,
        /** Names an authority at or below the current one; would regress or double-apply. */
        STALE_ACKNOWLEDGEMENT,
        /** A chunk ack arrived with NO `loss-checkpoint-ack-1`. Never settles. */
        LOSS_EVIDENCE_MISSING,
        /** Wrong protocol, wrong version, or an unusable state/boundary encoding. */
        LOSS_EVIDENCE_MALFORMED,
        /** The loss ack is for another session. */
        LOSS_EVIDENCE_SESSION_MISMATCH,
        /** `state: older` — the server did NOT advance to this snapshot. */
        LOSS_EVIDENCE_OLDER,
        /** Boundary, per-family counts or terminal disagree with the snapshot. */
        LOSS_EVIDENCE_MISMATCH,
    }

    public sealed class AckResult {
        public data class Applied(val ledger: Ledger) : AckResult()
        /** The same checkpoint, acknowledged again. The ledger is returned unchanged. */
        public data class Duplicate(val ledger: Ledger) : AckResult()
        /** ZERO mutation. The pending snapshot is PRESERVED. */
        public data class Refused(val reason: AckRefusal, val detail: String) : AckResult()
    }

    private val DECIMAL = Regex("^[0-9]{1,20}$")

    /**
 * Does this `loss-checkpoint-ack-1` prove the server committed EXACTLY the
 * snapshot it is being matched against? Returns null when it does.
 *
 * EVERY FAMILY, NOT THE TOTAL. Two count maps can share a total and
 * disagree about which family lost what — "3 frames" acknowledged as "3
 * interactions" is a fabricated attribution the total cannot see. The
 * canonical family set must be present exactly, and every value must match.
 */
    private fun verifyLossEvidence(
        snapshot: PendingCheckpoint,
        loss: LossCheckpointAck?,
    ): AckResult.Refused? {
        if (loss == null) {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MISSING,
                "a chunk acknowledgement alone never clears a loss checkpoint")
        }
        if (loss.protocol != LOSS_CHECKPOINT_ACK) {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MALFORMED,
                "protocol=${loss.protocol}")
        }
        if (loss.lossAccountingVersion != VERSION) {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MALFORMED,
                "lossAccountingVersion=${loss.lossAccountingVersion}")
        }
        if (loss.sessionId != snapshot.sessionId) {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_SESSION_MISMATCH,
                "loss ack names another session")
        }
        // `older` is a real, distinct outcome and it is NOT a settlement:
        // the server says it did not advance to this snapshot.
        if (loss.state == "older") {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_OLDER,
                "the server reports this snapshot superseded")
        }
        if (loss.state != "accepted" && loss.state != "duplicate") {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MALFORMED, "state=${loss.state}")
        }
        val through = loss.acceptedThroughSequence
        if (through == null || !DECIMAL.matches(through)) {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MALFORMED,
                "acceptedThroughSequence=$through")
        }
        val accepted = through.toLongOrNull()
            ?: return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MALFORMED,
                "acceptedThroughSequence out of range")
        if (accepted != snapshot.throughSequence) {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MISMATCH,
                "accepted=$accepted snapshot=${snapshot.throughSequence}")
        }
        if (loss.acceptedCounts.keys.toSortedSet() != LossFamilies.ALL.toSortedSet()) {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MALFORMED,
                "acceptedCounts is not the canonical family set")
        }
        for (f in LossFamilies.ALL) {
            if (loss.acceptedCounts[f] != snapshot.declaredCounts[f]) {
                return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MISMATCH,
                    "family $f accepted=${loss.acceptedCounts[f]} " +
                        "declared=${snapshot.declaredCounts[f]}")
            }
        }
        if (loss.terminal != snapshot.terminal) {
            return AckResult.Refused(AckRefusal.LOSS_EVIDENCE_MISMATCH,
                "terminal accepted=${loss.terminal} declared=${snapshot.terminal}")
        }
        return null
    }

    /**
 * Settle the MATCHING pending snapshot against committed loss evidence.
 *
 * THE DEFECT THE SNAPSHOT REPLACES. `acknowledge(ledger, through)`
 * rebased `acknowledgedTotal` to `ledger.total` AT ACK TIME. A pressure
 * commit landing between emission and acknowledgement was therefore
 * consumed by an acknowledgement that never covered it:
 *
 * emit [0.9] declaring 0 · commit losses 10,11 (total 2) · ack [0.9]
 * old rule: acknowledgedTotal = 2, unacknowledged = 0
 * next chunk [12.12]: missing = 2, declared = 0
 * -> Refused(INTERVAL_MISMATCH) the producer STALLS
 * this rule: acknowledgedTotal = 0, unacknowledged = 2
 * next chunk [12.12]: missing = 2, declared = 2 -> Emit
 *
 * The measured outcome of the old rule was a refusal, not an accepted
 * silent zero — the interval check caught the arithmetic — but a producer
 * that can never emit another checkpoint is a producer whose loss is
 * undisclosed from that point on.
 *
 * AND CHUNK IDENTITY IS NOT ENOUGH. `mobile-ack-1` proves the EVENTS
 * committed. The loss authority is a different row with its own
 * acknowledgement, and a producer that clears its ledger on the event ack
 * alone forgets a loss the server may never have recorded. Both the
 * delivery and the metadata-only path require [LossCheckpointAck].
 *
 * A caller-supplied `(through, declaredTotal)` pair is NOT accepted: the
 * declared side comes from the snapshot this ledger froze, and the accepted
 * side comes from the server's committed evidence.
 */
    @JvmStatic
    public fun acknowledge(ledger: Ledger, ack: Acknowledgement): AckResult {
        if (ack.sessionId != ledger.sessionId) {
            return AckResult.Refused(AckRefusal.SESSION_MISMATCH, "ack is for another session")
        }
        val p = ledger.pending
        if (p != null && p.matches(ack)) {
            verifyLossEvidence(p, ack.loss)?.let { return it }
            return AckResult.Applied(ledger.copy(
                acknowledgedThrough = p.throughSequence,
                acknowledgedTotal = p.declaredTotal,
                pending = null,
                lastAcknowledged = p,
            ))
        }
        val last = ledger.lastAcknowledged
        if (last != null && last.matches(ack)) {
            // A duplicate or late redelivery of a settlement already applied.
            // Verified too, though it mutates nothing either way: an
            // unverifiable ack is reported as unverifiable rather than as a
            // benign duplicate. Nothing moves — not the authority, not the
            // total, and never the losses committed since.
            verifyLossEvidence(last, ack.loss)?.let { return it }
            return AckResult.Duplicate(ledger)
        }
        if (ack.throughSequence <= ledger.acknowledgedThrough) {
            return AckResult.Refused(AckRefusal.STALE_ACKNOWLEDGEMENT,
                "ack through ${ack.throughSequence}, authority is ${ledger.acknowledgedThrough}")
        }
        if (p == null) {
            return AckResult.Refused(AckRefusal.NO_PENDING_CHECKPOINT,
                "nothing in flight for through ${ack.throughSequence}")
        }
        return AckResult.Refused(AckRefusal.CHECKPOINT_MISMATCH,
            "pending is ${p.kind} [${p.firstSequence}..${p.throughSequence}], " +
                "ack names ${ack.kind} [${ack.firstSequence}..${ack.throughSequence}]")
    }

    /** Terminal is one-way; this is the only transition. It never touches [Ledger.pending]. */
    @JvmStatic
    public fun markTerminal(ledger: Ledger): Ledger =
        if (ledger.terminal) ledger else ledger.copy(terminal = true)
}

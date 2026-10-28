package com.tracescout.mobile.internal.loss

import com.tracescout.mobile.queue.DurableReplace
import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * THE ENCRYPTED RETAINED ATTRIBUTION AUTHORITY — one session's durable sequence
 * high-water and `sequence → family` index, in **AUTHORITY CONTAINER V2**
 * (F3-P0 the specification–the specification).
 *
 * ## Files (all under the v2 root, all in the v2 container, all under one key)
 *
 * ```
 * <filesDir>/tracescout-authority-v2/authority.lock the ONE root lock
 * <filesDir>/tracescout-authority-v2/session-floors.bin role = floors
 * <filesDir>/tracescout-authority-v2/<sid>/authority-head.bin role = head
 * <filesDir>/tracescout-authority-v2/<sid>/authority-snapshot-<g>.bin role = snapshot
 * <filesDir>/tracescout-authority-v2/<sid>/authority-wal-<g>.bin role = wal
 * ```
 *
 * **THE AUTHORITY IS NO LONGER AN `EncryptedEventStore`, AND THAT IS A MEASURED
 * REQUIREMENT, NOT A PREFERENCE**. Three facts are true together and only
 * one design satisfies all three:
 *
 * ```
 * 1 the authority needs a CONTENT-UNKNOWN append outcome
 * 2 the queue's `stored` / INDETERMINATE_DIRECTORY_UNSYNCED semantics must NOT
 * change — and that reading is CORRECT for the queue: the content was
 * fsynced, only the directory entry is unconfirmed
 * 3 the F2 authority reached those semantics THROUGH EncryptedEventStore
 * => the v2 authority's WAL and snapshot must stop being EncryptedEventStore
 * instances
 * ```
 *
 * And the container requires it INDEPENDENTLY: an `EncryptedEventStore`-backed
 * file cannot carry a v2 preamble, a role discriminator, a `fileInstanceId`, an
 * authenticated header frame at ordinal 0, per-frame AAD, a rung ladder, or the
 * `Wpresent`/`Wauth` split (`readAll` skips-and-counts and cannot produce it).
 * **Three independent arguments, one design** — and the forbidden alternative,
 * threading a bound AAD through the shared reader, would reach EIGHT
 * compatibility constructions across THREE modules including RN's live event
 * queue and RN's PARKED customer bytes, whose whole contract is "write and read
 * only — no delete, preserved losslessly".
 *
 * ## Fail closed, and say which way (unchanged from F2 in intent)
 *
 * `Append.Indeterminate` FENCES this instance: the record may or may not be on
 * disk, so this process may no longer speak for the authority; a reopen — which
 * re-reads durable truth — is the only way forward. **There is NO local counter
 * fallback** (two authorities that disagree exactly once, silently, months later).
 *
 * ## TWO AMBIGUITIES IN THE FROZEN DESIGN, RESOLVED HERE AND STATED RATHER
 * THAN PICKED SILENTLY
 *
 * the specification of the design records that two of v3's four blockers were INTERNAL
 * CONTRADICTIONS, and that *"both would have been resolved by an implementer
 * picking one side, SILENTLY"*. Two statements in v6 admit more than one reading,
 * and both are resolved toward the reading that leaves every other section
 * literally true. Neither is a redesign.
 *
 * **1. THE ORDINAL SPACE.** the specification puts the header at ordinal 0, the specification titles body
 * frames "ordinal ≥ 1", and the specification A3 / the specification R19 say the new frame carries
 * "ordinal = Wpresent" / "ordinal (= C)". All four hold under one reading:
 * **one ordinal space per file, header = 0, body j (0-based) = ordinal j + 1**, so
 * "ordinal = Wpresent" is a statement about the BODY INDEX — which is exactly what
 * `Wpresent` and `C` count ("framed RECORDS physically present"). See
 * `AuthorityV2ContainerIo`.
 *
 * **2. `walFrameCount` MONOTONICITY.** the specification says a witness write is refused when
 * its "`generation`, `walFrameCount` or `highWaterClaim` is lower". Read as a
 * GLOBAL rule that makes compaction IMPOSSIBLE: the specification step 5 and the specification IC2 both
 * advance to `g+1` with `walFrameCount = 0`, and a session that had ever appended
 * would refuse its own compaction forever — a WAL that grows to `WAL_MAX_BYTES`
 * and stops. The reading that leaves every section true: **`generation` and
 * `highWaterClaim` are monotone ALWAYS; `walFrameCount` is monotone WITHIN a
 * generation**, because it IS the log extent of THAT generation's WAL and a new
 * generation describes a different file. [checkWitnessMonotone] states it in one
 * place and the acceptance row pins all three fields.
 */
internal class EncryptedAuthorityStore private constructor(
    private val root: File,
    private val dir: File,
    val sessionId: String,
    private val key: SecretKey,
    private val dirSync: DurableReplace.DirSync,
    private val machine: SequenceAuthority,
    private var walGeneration: Long,
    private val random: SecureRandom,
    /** Disclosed at open; never hidden. */
    val recoveryNote: String?,
    private val walMaxBytes: Long,
    private val compactAtBytes: Long,
    private val io: AuthorityV2ContainerIo,
    /** The witness this instance believes is on disk. Re-read under the lock before every mutation. */
    private var witness: AuthorityCodec.Witness,
    /** `Wpresent` as tracked by this instance. Healthy invariant: equals `witness.walFrameCount`. */
    private var walPresent: Long,
    private var walPreamble: AuthorityContainer.Preamble,
) {

    /** The durable state a directory holds, reconstructed WITHOUT writing anything. */
    internal class Inspection(
        val machine: SequenceAuthority,
        val generation: Long,
        val note: String?,
        /**
 * A WAL frame in the interior failed authentication: everything that
 * authenticated has been replayed, but what was lost is UNKNOWABLE, so this
 * state may not be treated as a complete history.
 */
        val interiorCorruption: Boolean,
        /** The the specification row this inspection landed in, for the trace and the acceptance rows. */
        val row: String,
    )

    internal sealed class Inspect {
        data class Ok(val inspection: Inspection) : Inspect()
        data class Failed(val reason: String) : Inspect()
        /** No witness, no snapshot, and no WAL of ours either — a genuinely empty directory. */
        object Absent : Inspect()
        /**
 * No authority, but the directory carries the [PRUNED_MARKER]: this session
 * id HAS allocated before and its authority was pruned. The durable floor
 * (or its absence) decides whether it may reopen; it is never a fresh
 * session, whatever the floors file says or fails to say.
 */
        object Pruned : Inspect()
    }

    sealed class Open {
        data class Ok(val store: EncryptedAuthorityStore) : Open()
        data class Failed(val reason: String) : Open()
    }

    sealed class Append {
        object Appended : Append()
        data class Rejected(val reason: String) : Append()
        /** Durability is uncertain; this instance is fenced. Only a reopen decides. */
        data class Indeterminate(val reason: String) : Append()
    }

    sealed class Compaction {
        data class Compacted(val generation: Long) : Compaction()
        data class NotCompacted(val reason: String) : Compaction()
        data class Indeterminate(val reason: String) : Compaction()
    }

    sealed class Admit {
        data class Owned(val sequence: Long, val family: String) : Admit()
        /** Refused BEFORE ownership: nothing allocated, nothing written. Not loss. */
        data class Rejected(val reason: String) : Admit()
        /** The durable write may or may not have landed; ownership is NOT claimed. Fenced. */
        data class Indeterminate(val reason: String) : Admit()
    }

    private val lock = Any()
    @Volatile private var fencedReason: String? = null

    /** See the F2 doc: PER PROCESS, NOT DURABLE, and deliberately so. */
    @Volatile private var mutationFenced: List<Long> = emptyList()
    @Volatile private var owedSettlement: List<Long> = emptyList()
    @Volatile private var recoveryWriteFailures: List<String> = emptyList()

    // ─────────────────────────────────────────────────────────────── reads

    fun isFenced(): String? = fencedReason
    fun highWater(): Long = synchronized(lock) { machine.highWater() }
    /** A READ, not an allocation. Nothing is issued until an `AdmissionIntent` is committed AND witnessed. */
    fun nextSequence(): Long = synchronized(lock) { machine.nextSequence() }
    fun disabled(): DisableReason? = synchronized(lock) { machine.disabled() }
    /** F4-P2 (R11): the durable owner of an owner-bound disable, or null (none / legacy). */
    fun disabledOwner(): String? = synchronized(lock) { machine.disabledOwner() }
    fun entry(sequence: Long): SequenceAuthority.Entry? = synchronized(lock) { machine.entry(sequence) }
    fun entryByIntent(intentId: String): SequenceAuthority.Entry? = synchronized(lock) { machine.entryByIntent(intentId) }
    fun ownedSequences(): Map<Long, SequenceAuthority.Entry> = synchronized(lock) { machine.ownedSequences() }
    fun isDelivered(sequence: Long): Boolean = synchronized(lock) { machine.isDelivered(sequence) }
    fun deliveredBatchCount(): Int = synchronized(lock) { machine.deliveredBatches().size }
    fun walGeneration(): Long = synchronized(lock) { walGeneration }
    fun pendingCheckpoint(): AuthorityCodec.PendingCheckpointRow? = synchronized(lock) { machine.pendingCheckpoint() }
    /**
 * F6 — the acknowledged boundary, the counts declared at it, and the live
 * retained loss by family: the four durable facts an F6 ledger projection
 * needs. Reads under the same monitor every other accessor takes.
 *
 * [retainedLostByFamily] IS the cumulative per-session loss, and that is
 * sound for exactly one measured reason: `snapshot` filters
 * `phase != DELIVERED`, so a LOST entry SURVIVES compaction, and nothing in
 * `SequenceAuthority` removes from `owned`. An acknowledged LOST entry stays
 * in both this map and `ackCounts`, so `total - acknowledgedTotal` is the
 * UNACKNOWLEDGED loss — which is the quantity `gap === delta` compares.
 */
    fun ackThrough(): Long = synchronized(lock) { machine.ackThrough() }
    fun ackCounts(): Map<String, Long> = synchronized(lock) { machine.ackCounts() }
    fun acknowledgedTerminal(): Boolean = synchronized(lock) { machine.acknowledgedTerminal() }
    fun retainedLostByFamily(): Map<String, Long> = synchronized(lock) {
        val out = LinkedHashMap<String, Long>()
        for (e in machine.retainedLost()) out[e.family] = (out[e.family] ?: 0L) + 1L
        out
    }
    /** F4-P0 Slice C: the sequences with a durable, unresolved `EvictionIntent`. */
    fun unresolvedEvictions(): List<Long> = synchronized(lock) { machine.unresolvedEvictions() }
    /** F4-P0 Slice C: bytes the WAL can still take before `at_capacity`. Advisory; the append is the guard. */
    fun walHeadroomBytes(): Long = synchronized(lock) { walMaxBytes - walFile().length() }
    /** RULING R3 — the local terminal flag, for the WRITER's policy. `validate` reads it for no kind. */
    fun terminalRequested(): Boolean = synchronized(lock) { machine.terminalRequested() }

    /** THE ONE READ A DESTRUCTIVE MUTATOR MAY CONSULT — this session's lossy-read fence. */
    fun mutationFenced(): List<Long> = mutationFenced
    /** Disclosure only: settlements a later phase owes. NEVER a removal gate. */
    fun owedSettlement(): List<Long> = owedSettlement
    /** DIAGNOSTIC UNION. NOT A MUTATION GATE — gating on it is the defect the split closes. */
    fun reconciliationPending(): List<Long> = (mutationFenced + owedSettlement).sorted()
    fun recoveryWriteFailures(): List<String> = recoveryWriteFailures

    fun walSizeBytes(): Long = synchronized(lock) { walFile().length() }
    fun snapshotFile(): File = File(dir, AuthorityNaming.snapshot(walGeneration))
    fun walFile(): File = File(dir, AuthorityNaming.wal(walGeneration))
    fun headFile(): File = File(dir, AuthorityNaming.HEAD)
    /** The witness this instance last read or wrote. Exposed for the acceptance rows, never for policy. */
    internal fun witnessForTesting(): AuthorityCodec.Witness = synchronized(lock) { witness }
    internal fun machineForTesting(): SequenceAuthority = machine

    // ───────────────────────────────────────────────────────────── writes

    /**
 * the specification — **THE STRICT APPEND COMMIT PROTOCOL, A1…A9**, and the whole allocation
 * and durability path stated as one ordered sequence. Every step is under the
 * the specification root lock and the in-process monitor.
 *
 * ```
 * A1 ACQUIRE monitor, then the root authority lock (bounded tryLock)
 * unavailable -> Rejected(authority_lock_unavailable)
 * nothing allocated, nothing written, NO fence
 * A1b RE-READ the durable witness, and FENCE if it has moved under us
 * (see below — this is what makes A5's "monotone-checked
 * against the CURRENT witness" mean anything)
 * A2 VALIDATE PURE, FALLIBLE, and BEFORE any durability. The sequence a new
 * admission would take is DERIVED here and is NOT yet issued.
 * A3 SEAL encode, pad to the rung, seal the COMPLETE frame with AAD_B at
 * ordinal = Wpresent. A frame is authenticated IN FULL before
 * any byte of it reaches the file.
 * A4 APPEND AuthorityDurableWrite.append
 * A5 COMPUTE the new witness: walFrameCount = C + 1, chain step,
 * highWaterClaim = the FACTUAL high-water. Monotone-checked.
 * A6 WITNESS AuthorityDurableWrite.replace(head, newWitness)
 * A7 INSTALL install the validated record into the in-memory machine
 * A8 RETURN ONLY NOW may the caller be told Appended / Owned(sequence)
 * A9 RELEASE the lock
 * ```
 *
 * **A1b IS A MECHANISM THE DESIGN'S OWN LOCK SCOPE REQUIRES, AND IT IS
 * FLAGGED RATHER THAN SLIPPED IN.** the specification scopes the lock to each mutation and
 * each open, NOT to a process's lifetime — so two processes may both hold an
 * open store for one session and mutate it in turn. Without re-reading the
 * witness under the lock, this process would seal at an ordinal derived from a
 * `Wpresent` another process has already advanced, and the specification's "monotone-checked
 * against the current witness" could not catch it: the computed `C+1` would
 * still be `>=` the on-disk `C`. Re-reading and FENCING on disagreement is the
 * fail-closed answer — this process stops mutating, and the next open resolves
 * the state from the artifacts, which is exactly what a fence already means.
 *
 * **NO SEQUENCE IS EXPOSED TO THE CALLER BEFORE THE WITNESS COMMIT THAT
 * MAKES IT AUTHORITATIVE** — step A8, a pinned property. F2 already
 * derived the number before appending; what v2 adds is that the RETURN is gated
 * on A6 as well as on A4.
 */
    fun admit(intentId: String, family: String): Admit = synchronized(lock) {
        fencedReason?.let { return Admit.Rejected("authority_fenced:$it") }
        // RULING A2, RESTORED VERBATIM AND NOT SUPERSEDED. This is the
        // ACCEPTED F2 behaviour, and it sits ABOVE `nextSequence` deliberately: a
        // disabled authority may not know its own high-water, so allocating from it
        // can hand out a number the server has already accepted — the reuse RULING
        // A1 forbids, arriving through the other door. There is no
        // `AllocationSafety`, no `AccountingConfidence` and no A2′.
        machine.disabled()?.let { reason ->
            if (reason.highWaterConfidence() == HighWaterConfidence.UNKNOWN) {
                RecoveryTrace.log("authority_refused_disabled", "why" to reason.name, "session" to sessionId.take(24))
                return Admit.Rejected("authority_disabled_high_water_unknown:${reason.name}")
            }
        }
        val seq = machine.nextSequence()
        return when (val a = appendLocked(LossRecord.AdmissionIntent(intentId, seq, family))) {
            is Append.Appended -> {
                val e = machine.entryByIntent(intentId)
                if (e == null) Admit.Rejected("intent_not_owned_after_append") else Admit.Owned(e.sequence, e.family)
            }
            is Append.Rejected -> Admit.Rejected(a.reason)
            is Append.Indeterminate -> Admit.Indeterminate(a.reason)
        }
    }

    fun append(r: LossRecord): Append = synchronized(lock) { appendLocked(r) }

    private fun appendLocked(r: LossRecord): Append {
        fencedReason?.let { return Append.Rejected("fenced:$it") }

        // A1 — monitor already held; now the OS lock. Order is always monitor, then
        // file lock, and there is NO proceed-unlocked branch.
        val acq = AuthorityLock.acquire(root)
        if (acq is AuthorityLock.Acquisition.Unavailable) return Append.Rejected(acq.reason)
        val held = acq as AuthorityLock.Acquisition.Ok
        try {
            // A1b — see the doc above.
            reReadWitnessUnderLock()?.let { return it }

            // A2 — every fallible semantic decision happens BEFORE durability.
            // Fsyncing first and asking afterwards makes a refusal PERMANENT: the
            // frame is on disk and every future replay refuses it identically. The
            // WAL may only contain records that will apply.
            val v = when (val d = machine.validate(r)) {
                is SequenceAuthority.Validation.AlreadyApplied -> return Append.Appended
                is SequenceAuthority.Validation.Refused -> return Append.Rejected(d.reason)
                is SequenceAuthority.Validation.Accepted -> d
            }
            val plain = try {
                AuthorityCodec.encodeRecord(r)
            } catch (e: LossFormatException) {
                return Append.Rejected(e.reason)
            }

            // PRESERVE: the authority never evicts its own authority. At capacity
            // admission is REFUSED (D1-3), which is why the WAL is not an
            // eviction-policy store at all any more — the cap is checked here.
            val walF = walFile()
            val frameBytes = AuthorityPadding.onDiskFrameBytes(AuthorityPadding.rungFor(plain.size)).toLong()
            if (walF.length() + frameBytes > walMaxBytes) return Append.Rejected("at_capacity")

            // A3 — seal at ordinal = body index `walPresent`, i.e. FILE ordinal
            // walPresent + 1 (see the class doc's ordinal ruling).
            val frame = try {
                io.sealBody(walPreamble, walPresent + 1L, walGeneration, sessionId, plain)
            } catch (e: LossFormatException) {
                return Append.Rejected("seal:${e.reason}")
            }

            // A4
            when (AuthorityDurableWrite.append(walF, frame)) {
                AuthorityDurableWrite.Outcome.COMMITTED -> Unit
                AuthorityDurableWrite.Outcome.NOT_COMMITTED_PROVEN_UNCHANGED ->
                    // Proven byte-unchanged, and that proof is itself durable: there
                    // is no residue to resolve and NO fence.
                    return Append.Rejected("wal_not_committed")
                else -> {
                    // INDETERMINATE_CONTENT: possibly a complete frame, possibly a
                    // FRAGMENT, possibly nothing. All three land in a NAMED row of
                    // the specification — R19/R20 for a complete frame, **R23 for a fragment**
                    // (NORMAL, allocation permitted), R24 for nothing.
                    fence("wal_append_indeterminate")
                    return Append.Indeterminate("wal_append_indeterminate")
                }
            }

            // A5 — the FACTUAL high-water after this record installs. NEVER
            // derived from a file's extent or size, and `walFrameCount`
            // is never read as a high-water.
            val probe = SequenceAuthority.fromSnapshot(machine.snapshot(walGeneration))
            val factualHighWater = run {
                // Install into a COPY so the factual high-water is the one this
                // record produces, without mutating the machine before A6.
                val copy = probe
                copy.validate(r).let { pv ->
                    if (pv is SequenceAuthority.Validation.Accepted) copy.install(pv)
                }
                maxOf(copy.highWater(), machine.highWater())
            }
            val newWitness = witness.copy(
                walFrameCount = walPresent + 1L,
                walTailDigest = AuthorityContainer.hex(
                    AuthorityContainer.chainStep(unhexDigest(witness.walTailDigest), frame),
                ),
                highWaterClaim = maxOf(factualHighWater, witness.highWaterClaim),
            )
            checkWitnessMonotone(witness, newWitness)?.let {
                // A witness that can be LOWERED by a bug is not a witness.
                fence(it)
                return Append.Indeterminate(it)
            }

            // A6
            when (installWitnessLocked(newWitness)) {
                AuthorityDurableWrite.Outcome.COMMITTED -> Unit
                AuthorityDurableWrite.Outcome.NOT_COMMITTED_PROVEN_UNCHANGED -> {
                    // The WAL has C+1 frames and the witness says C: R19's exact
                    // one-step repair. Nothing is installed here — installing would
                    // let this process act on a record the next process resolves
                    // differently.
                    fence("witness_not_committed")
                    return Append.Indeterminate("witness_not_committed")
                }
                else -> {
                    // The witness is EITHER the old one (C) or the new one (C+1),
                    // both authentic and complete: R19 if old, R24 if new — both
                    // deterministic.
                    fence("witness_indeterminate")
                    return Append.Indeterminate("witness_indeterminate")
                }
            }

            // A7 / A8
            machine.install(v)
            walPresent += 1L
            witness = newWitness
            maybeCompactLocked()
            return Append.Appended
        } finally {
            // A9
            held.release()
        }
    }

    /**
 * THE MONOTONE WITNESS WRITE CHECK, IN ONE PLACE.
 *
 * `generation` and `highWaterClaim` are monotone ALWAYS. `walFrameCount` is
 * monotone **WITHIN a generation** — see the class doc's ruling 2: read
 * globally, it would make every compaction refuse itself forever, because
 * the specification step 5 and the specification IC2 both advance to `g+1` with `walFrameCount = 0`.
 *
 * `walTailDigest` and the two instance ids are NOT ordered, so they are not
 * monotone-checked — they are re-derived from the artifacts the write describes.
 */
    private fun checkWitnessMonotone(
        current: AuthorityCodec.Witness,
        next: AuthorityCodec.Witness,
    ): String? {
        if (next.generation < current.generation) return "witness_would_regress:generation"
        if (next.highWaterClaim < current.highWaterClaim) return "witness_would_regress:highWaterClaim"
        if (next.generation == current.generation && next.walFrameCount < current.walFrameCount)
            return "witness_would_regress:walFrameCount"
        return null
    }

    private fun installWitnessLocked(w: AuthorityCodec.Witness): AuthorityDurableWrite.Outcome {
        val built = io.build(
            AuthorityContainer.Role.HEAD,
            headInstanceId,
            sessionId,
            w.generation,
            listOf(AuthorityCodec.encodeWitness(w)),
        )
        return AuthorityDurableWrite.replace(headFile(), built.bytes, dirSync)
    }

    /**
 * A1b. Re-read the durable witness under the lock; a disagreement with what this
 * instance tracks means the state moved under us and this process may no longer
 * speak for the authority.
 */
    private fun reReadWitnessUnderLock(): Append? {
        // IT RETURNS THE **REFUSAL VALUE**, not a reason string, and that shape is
        // load-bearing for a GUARD as well as for the caller. The structural
        // admission-trace pin drops a branch whose last statement CONSTRUCTS a
        // refusal value; a `String?` return made every branch here look like an
        // ordinary one, so the `fence(.)` trace inside them read as a trace on the
        // successful admission path. The refusal type makes the branch visible for
        // what it is.
        val r = io.read(headFile(), AuthorityContainer.Role.HEAD, sessionId, null)
        if (r !is AuthorityV2ContainerIo.Read.Ok || r.bodies.size != 1) {
            fence("witness_unreadable_at_append")
            return Append.Indeterminate("witness_unreadable_at_append")
        }
        val onDisk = try {
            AuthorityCodec.decodeWitness(r.bodies[0])
        } catch (e: LossFormatException) {
            fence("witness_malformed_at_append")
            return Append.Indeterminate("witness_malformed_at_append")
        }
        if (onDisk != witness) {
            fence("witness_moved_under_us")
            return Append.Indeterminate("witness_moved_under_us")
        }
        return null
    }

    private var headInstanceId: ByteArray = ByteArray(0)

    private fun fence(reason: String) {
        if (fencedReason == null) {
            fencedReason = reason
            RecoveryTrace.log("authority_fenced", "why" to reason)
        }
    }

    private fun maybeCompactLocked() {
        if (walFile().length() >= compactAtBytes) compactLocked()
    }

    fun compact(): Compaction = synchronized(lock) {
        fencedReason?.let { return Compaction.NotCompacted("fenced:$it") }
        val acq = AuthorityLock.acquire(root)
        if (acq is AuthorityLock.Acquisition.Unavailable) return Compaction.NotCompacted(acq.reason)
        val held = acq as AuthorityLock.Acquisition.Ok
        try { compactLocked() } finally { held.release() }
    }

    /**
 * the specification — **THE NEW COMPACTION ORDER.** Every step under the the specification root lock.
 *
 * ```
 * 0 IC-VERIFY the g+1 residue
 * 1 rebuild the logical state from the in-memory machine
 * 2 mint a NEW fileInstanceId; install authority-snapshot-<g+1>.bin durably
 * 3 READ IT BACK: reopen, authenticate, decode, rebuild, compare a LOGICAL
 * fingerprint against the one computed from the in-memory machine
 * 4 authority-wal-<g+1>.bin with ZERO body frames, via createOrAdoptEmpty
 * 5 advance the witness to g+1 <-- THE ATOMIC COMMIT POINT
 * 6 ONLY NOW retire generation g
 * ```
 *
 * **THE VERIFY IS AT STEP 3, BEFORE THE HIGHER-GENERATION WAL EXISTS** — a
 * deliberate deviation from the ordering the review sketched, and every
 * semantic it required is preserved. The reason: a failed verification then
 * creates **no higher-generation artifact at all**, and the specification Half 4 says nothing
 * at or above `g` may ever be deleted, so a higher-generation WAL born of a
 * failed verify would be PERMANENT UN-SWEEPABLE RESIDUE. Verifying first means
 * the only residue a failure can leave is `snapshot-<g+1>`, which the specification IC3
 * preserves harmlessly.
 *
 * Generation-naming is what makes the read-back USEFUL: with F2's fixed
 * `authority-snapshot.bin`, `replace` had already destroyed the old snapshot by
 * the time the read-back could fail, so a failed verification had nothing to
 * fall back to.
 */
    private fun compactLocked(): Compaction {
        // F4-P0 Slice C: NEVER fold an unresolved
        // eviction intent away. The snapshot carries no unresolved-eviction
        // field by design (AS-3 — the intents ARE the record), so a compaction
        // between PREPARE and SETTLE would retire the only durable evidence
        // that a bracket was open. Deferred, typed, no trace key: a refusal
        // here is maintenance that did not run, not an admission event.
        if (machine.unresolvedEvictions().isNotEmpty()) {
            return Compaction.NotCompacted("eviction_unresolved:${machine.unresolvedEvictions().size}")
        }
        val next = walGeneration + 1

        // ── step 0: IC-verify the g+1 residue (E1.E4). ────────────────────
        icVerifyLocked(next)?.let { return Compaction.NotCompacted("higher_generation_residue:$it") }

        // ── step 1 ─────────────────────────────────────────────────────────
        val snapshotState = machine.snapshot(next)
        val plain = try {
            AuthorityCodec.encodeSnapshot(snapshotState)
        } catch (e: LossFormatException) {
            return Compaction.NotCompacted("snapshot_encode:${e.reason}")
        }

        // ── step 2 ─────────────────────────────────────────────────────────
        val snapInstance = io.newInstanceId()
        val snapBuilt = io.build(AuthorityContainer.Role.SNAPSHOT, snapInstance, sessionId, next, listOf(plain))
        val snapFile = File(dir, AuthorityNaming.snapshot(next))
        val residuePresent = snapFile.exists()
        val outcome = if (residuePresent) {
            // THE ONE BOUNDED EXCEPTION of the specification Half 4, and it is permitted ONLY
            // because step 0 proved E1.E4 — so the bytes being overwritten were
            // NEVER WITNESSED. Its CONTENT must carry the CURRENT state, and the
            // current state is not the earlier attempt's.
            AuthorityDurableWrite.replace(snapFile, snapBuilt.bytes, dirSync)
        } else {
            AuthorityDurableWrite.create(snapFile, snapBuilt.bytes, dirSync)
        }
        when (outcome) {
            AuthorityDurableWrite.Outcome.COMMITTED -> Unit
            AuthorityDurableWrite.Outcome.NOT_COMMITTED_PROVEN_UNCHANGED ->
                return Compaction.NotCompacted("snapshot_not_committed")
            AuthorityDurableWrite.Outcome.REFUSED_TARGET_EXISTS ->
                // `create` refusing a pre-existing target is what makes the preserve
                // rule enforceable AT THE PRIMITIVE. Reaching it here means step 0's
                // verification and this branch disagree, which is a bug, not a state.
                return Compaction.NotCompacted("higher_generation_residue:create_target_exists")
            else -> {
                fence("compaction_snapshot_indeterminate")
                return Compaction.Indeterminate("snapshot_indeterminate")
            }
        }

        // the specification E1/E2/E5's injection point: the snapshot is on disk and has NOT been read
        // back. Inert unless a test arms it.
        AuthorityFaults.at(AuthorityFaults.Point.COMPACT_AFTER_SNAPSHOT_INSTALL)

        // ── step 3: READ IT BACK, and compare a LOGICAL fingerprint. ───────
        // DERIVED FROM THE REBUILT MACHINE, NOT FROM THE BYTES:
        // comparing the bytes to themselves proves the file was written, not that it
        // DECODES to the state we meant.
        val back = io.read(snapFile, AuthorityContainer.Role.SNAPSHOT, sessionId, next)
        val verified = (back as? AuthorityV2ContainerIo.Read.Ok)?.let { ok ->
            if (ok.bodies.size != 1) null
            else try {
                SequenceAuthority.fromSnapshot(AuthorityCodec.decodeSnapshot(ok.bodies[0]))
            } catch (e: LossFormatException) { null }
        }
        if (verified == null || verified.logicalFingerprint() != machine.logicalFingerprint()) {
            // RETIRE **NOTHING**: snapshot-<g> and wal-<g> both survive, so the
            // authority is still fully reconstructible.
            fence("AMBIGUOUS_POST_COMMIT_SNAPSHOT_MISMATCH")
            return Compaction.Indeterminate("AMBIGUOUS_POST_COMMIT_SNAPSHOT_MISMATCH")
        }

        // ── step 4: the empty WAL at g+1, created OR ADOPTED. ──────────────
        val walNextFile = File(dir, AuthorityNaming.wal(next))
        val walInstance = io.newInstanceId()
        val adopt = AuthorityDurableWrite.createOrAdoptEmpty(
            walNextFile,
            { io.build(AuthorityContainer.Role.WAL, walInstance, sessionId, next, emptyList()).bytes },
            AuthorityContainer.hex(walInstance),
            dirSync,
        ) { f -> io.probeAdoptableEmpty(f, AuthorityContainer.Role.WAL, sessionId, next) }
        val walInstanceHex = when (adopt.outcome) {
            AuthorityDurableWrite.Outcome.COMMITTED, AuthorityDurableWrite.Outcome.ADOPTED ->
                adopt.fileInstanceIdHex ?: return Compaction.Indeterminate("wal_instance_unknown")
            else -> {
                fence("compaction_wal_${adopt.outcome.name.lowercase()}")
                return Compaction.Indeterminate("wal_not_created:${adopt.outcome.name}")
            }
        }

        // ── step 5: THE ATOMIC COMMIT POINT. ───────────────────────────────
        // The witness binds whichever WAL instance id step 4 ended up with —
        // that is what keeps R16 meaningful across a retry: an ADOPTED file's OWN id
        // is the id the witness names, so a LATER substitution of that
        // same-generation WAL is still caught.
        val newWitness = AuthorityCodec.Witness(
            sessionId = sessionId,
            generation = next,
            walFrameCount = 0L,
            walTailDigest = AuthorityContainer.hex(
                AuthorityContainer.chainSeed(unhexInstance(walInstanceHex), next),
            ),
            highWaterClaim = maxOf(machine.highWater(), witness.highWaterClaim),
            walFileInstanceIdHex = walInstanceHex,
            snapshotFileInstanceIdHex = snapBuilt.fileInstanceIdHex,
        )
        checkWitnessMonotone(witness, newWitness)?.let {
            fence(it)
            return Compaction.Indeterminate(it)
        }
        when (installWitnessLocked(newWitness)) {
            AuthorityDurableWrite.Outcome.COMMITTED -> Unit
            else -> {
                fence("compaction_witness_not_committed")
                return Compaction.Indeterminate("witness_not_committed")
            }
        }

        walGeneration = next
        walPresent = 0L
        witness = newWitness
        walPreamble = io.preambleOf(walNextFile, AuthorityContainer.Role.WAL)
            ?: run { fence("wal_preamble_unreadable_after_compaction"); return Compaction.Indeterminate("wal_preamble_unreadable") }

        // the specification E4's injection point: the witness NAMES `g+1` and `g` is not yet retired —
        // the one window where a death must leave a state the specification preserves or completes.
        AuthorityFaults.at(AuthorityFaults.Point.COMPACT_AFTER_WITNESS_ADVANCE)

        // ── step 6: ONLY NOW retire generation g. ──────────────────────────
        // `x < g` ONLY, and only AFTER the current state has been read back and
        // verified. Nothing at or above `g` may be deleted —
        // not by the sweep, not by a repair, not because the witness is older.
        retireBelow(next)
        // KEYS ARE EXACTLY {gen, session, file} — the frozen set the maintenance
        // exclusion is pinned over. A "from" key naming the previous generation would
        // widen that set for information `gen - 1` already carries.
        RecoveryTrace.log("authority_compacted", "gen" to next, "session" to sessionId.take(24))
        return Compaction.Compacted(next)
    }

    /**
 * the specification step 0 / the specification Half 4 — **IC-VERIFICATION of the `g+1` residue**. Returns
 * the failing condition, or `null` when the exception is available.
 *
 * ```
 * E1 NO witness names g+1 or any higher generation
 * (so nothing being replaced was ever committed — the whole basis of the
 * exception's safety, and the condition that makes it unable to destroy
 * committed state)
 * E2 NO artifact of ANY role exists at g+2 or above
 * E3 SNAP[g+1] is ABSENT, or PRESENT-and-READABLE at role=snapshot with
 * header generation == g+1 == the filename's
 * E4 WAL[g+1] is ABSENT, or PRESENT-and-READABLE at role=wal with header
 * generation == g+1 == the filename's AND Wpresent == 0
 * ```
 *
 * On a FIRST compaction at this generation the residue is simply ABSENT,
 * which satisfies E3/E4 trivially; this step exists for the RETRY after a crash.
 */
    private fun icVerifyLocked(next: Long): String? {
        // E1 — the witness this instance holds names `walGeneration`, and a witness
        // naming g+1 or above would have been adopted at open, so reaching here with
        // one is impossible. Assert it rather than assume it.
        if (witness.generation >= next) return "E1:witness_names_$next"
        // E2
        dir.listFiles()?.forEach { f ->
            val g = AuthorityNaming.walGenerationOf(f.name) ?: AuthorityNaming.snapshotGenerationOf(f.name)
            if (g != null && g >= next + 1) return "E2:artifact_at_$g"
        }
        // E3
        val snapNext = File(dir, AuthorityNaming.snapshot(next))
        if (snapNext.exists()) {
            val r = io.read(snapNext, AuthorityContainer.Role.SNAPSHOT, sessionId, next)
            if (r !is AuthorityV2ContainerIo.Read.Ok) return "E3:snapshot_unreadable"
        }
        // E4
        val walNext = File(dir, AuthorityNaming.wal(next))
        if (walNext.exists()) {
            val r = io.read(walNext, AuthorityContainer.Role.WAL, sessionId, next)
            if (r !is AuthorityV2ContainerIo.Read.Ok) return "E4:wal_unreadable"
            if (r.present != 0L) return "E4:wal_not_empty"
        }
        return null
    }

    /** The orphan sweep — generations strictly BELOW [adopted], and never anything else. */
    internal fun retireBelow(adopted: Long) {
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val g = AuthorityNaming.walGenerationOf(f.name) ?: AuthorityNaming.snapshotGenerationOf(f.name)
            if (g != null && g < adopted && f.delete())
                RecoveryTrace.log("authority_generation_retired", "file" to f.name)
        }
        AuthorityDurableWrite.sweepStaleTemps(dir)
    }

    // ──────────────────────────────────────────────── recovery (F2, carried)

    private fun recordRecoveryWrite(what: String, a: Append, failures: MutableList<String>) {
        when (a) {
            is Append.Appended -> Unit
            is Append.Rejected -> failures.add("$what:rejected:${a.reason}")
            is Append.Indeterminate -> failures.add("$what:indeterminate:${a.reason}")
        }
    }

    private fun settleRecoveryFailures(failures: List<String>) {
        recoveryWriteFailures = failures
        if (failures.isEmpty()) return
        val why = failures[0]
        RecoveryTrace.log("authority_recovery_write_refused", "count" to failures.size, "why" to why.take(40))
        if (machine.disabled() == null && fencedReason == null) {
            when (val d = appendLocked(LossRecord.CheckpointDisabled("dis-RECOVERY_WRITE_REFUSED", DisableReason.RECOVERY_WRITE_REFUSED))) {
                is Append.Appended -> Unit
                is Append.Rejected -> fence("recovery_disable_rejected:${d.reason}")
                is Append.Indeterminate -> Unit // appendLocked already fenced
            }
        }
    }

    fun reconcileWith(queue: AuthorityRecovery.QueueView): List<AuthorityRecovery.Step> = synchronized(lock) {
        val steps = AuthorityRecovery.reconcile(machine, queue)
        val fenced = ArrayList<Long>()
        val owed = ArrayList<Long>()
        val failures = ArrayList<String>()
        for (s in steps) when (s) {
            is AuthorityRecovery.Step.SettleAdmitted -> recordRecoveryWrite("settle_admitted", appendLocked(LossRecord.IntentFinalized("rec-${s.sequence}-admitted", s.intentId, LossRecord.IntentFinalized.Outcome.ADMITTED)), failures)
            is AuthorityRecovery.Step.SettleLost -> recordRecoveryWrite("settle_lost", appendLocked(LossRecord.IntentFinalized("rec-${s.sequence}-lost", s.intentId, LossRecord.IntentFinalized.Outcome.LOST, s.cause)), failures)
            is AuthorityRecovery.Step.AdvanceHighWater -> recordRecoveryWrite("advance", appendLocked(LossRecord.HighWaterAdvanced("hw-${s.to}", s.to)), failures)
            is AuthorityRecovery.Step.Disable -> recordRecoveryWrite("disable", appendLocked(LossRecord.CheckpointDisabled("dis-${s.reason.name}", s.reason)), failures)
            is AuthorityRecovery.Step.Adopt -> {
                recordRecoveryWrite("adopt", appendLocked(LossRecord.AdmissionIntent(s.intentId, s.sequence, s.family)), failures)
                recordRecoveryWrite("adopt_admitted", appendLocked(LossRecord.IntentFinalized("${s.intentId}-admitted", s.intentId, LossRecord.IntentFinalized.Outcome.ADMITTED)), failures)
            }
            is AuthorityRecovery.Step.Pending -> {
                when (s.reason.pendingClass()) {
                    PendingClass.MUTATION_FENCED -> fenced.addAll(s.sequences)
                    PendingClass.DISCLOSED_OWED -> owed.addAll(s.sequences)
                }
                RecoveryTrace.log("authority_reconcile_pending", "why" to s.reason.name,
                    "class" to s.reason.pendingClass().name, "count" to s.sequences.size)
            }
        }
        mutationFenced = fenced.sorted()
        owedSettlement = owed.sorted()
        settleRecoveryFailures(failures)
        steps
    }

    /** One-time adoption of a pre-F2 queue for this session (the store is at genesis). */
    fun adopt(survivors: List<AuthorityRecovery.Survivor>, trustworthy: Boolean): List<AuthorityRecovery.Step> = synchronized(lock) {
        val plan = AuthorityRecovery.adoptionPlan(survivors, trustworthy) { seq -> "adopt-$seq" }
        val fenced = ArrayList<Long>()
        val owed = ArrayList<Long>()
        val failures = ArrayList<String>()
        for (s in plan) when (s) {
            is AuthorityRecovery.Step.Adopt -> {
                recordRecoveryWrite("adopt", appendLocked(LossRecord.AdmissionIntent(s.intentId, s.sequence, s.family)), failures)
                recordRecoveryWrite("adopt_admitted", appendLocked(LossRecord.IntentFinalized("${s.intentId}-admitted", s.intentId, LossRecord.IntentFinalized.Outcome.ADMITTED)), failures)
            }
            is AuthorityRecovery.Step.AdvanceHighWater -> recordRecoveryWrite("advance", appendLocked(LossRecord.HighWaterAdvanced("hw-${s.to}", s.to)), failures)
            is AuthorityRecovery.Step.Disable -> recordRecoveryWrite("disable", appendLocked(LossRecord.CheckpointDisabled("dis-${s.reason.name}", s.reason)), failures)
            is AuthorityRecovery.Step.Pending -> when (s.reason.pendingClass()) {
                PendingClass.MUTATION_FENCED -> fenced.addAll(s.sequences)
                PendingClass.DISCLOSED_OWED -> owed.addAll(s.sequences)
            }
            else -> Unit
        }
        if (fenced.isNotEmpty()) mutationFenced = (mutationFenced + fenced).sorted()
        if (owed.isNotEmpty()) owedSettlement = (owedSettlement + owed).sorted()
        settleRecoveryFailures(failures)
        RecoveryTrace.log("authority_adopted", "survivors" to survivors.size, "trustworthy" to trustworthy)
        plan
    }

    /** R4 — DISCLOSE, NEVER SETTLE. Unchanged from F2 (quarantine settlement is F4's). */
    fun discloseOwed(sequences: List<Long>): Int = synchronized(lock) {
        val add = sequences.filter { q ->
            val e = machine.entry(q)
            e != null && e.phase == SequenceAuthority.Phase.ADMITTED && !machine.isDelivered(q)
        }
        if (add.isNotEmpty()) {
            owedSettlement = (owedSettlement + add).distinct().sorted()
            RecoveryTrace.log("authority_owed_disclosed", "why" to PendingReason.ADMITTED_ABSENT_UNDELIVERED.name,
                "class" to PendingClass.DISCLOSED_OWED.name, "count" to add.size)
        }
        add.size
    }

    private fun unhexDigest(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = ((hexDigit(hex[2 * i]) shl 4) or hexDigit(hex[2 * i + 1])).toByte()
        return out
    }

    private fun unhexInstance(hex: String): ByteArray = unhexDigest(hex)

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw LossFormatException("hex_malformed")
    }

    companion object {
        const val PRUNED_MARKER = AuthorityNaming.PRUNED_MARKER
        const val ROOT_DIR = AuthorityNaming.ROOT_DIR
        const val HEAD = AuthorityNaming.HEAD

        /**
 * FILE NAMES ARE THIS CLASS'S TO OWN, and
 * these DELEGATE to `AuthorityNaming` rather than restating a pattern.
 * `SNAPSHOT` — F2's FIXED `authority-snapshot.bin` — is GONE: snapshots are
 * generation-named in v2, and that is what makes the the specification
 * read-back verification useful at all (with a fixed name, `replace` had
 * already destroyed the old snapshot by the time a read-back could fail).
 */
        fun snapshot(generation: Long): String = AuthorityNaming.snapshot(generation)
        fun wal(generation: Long): String = AuthorityNaming.wal(generation)

        /**
 * PRESERVE cap of the WAL. At capacity admission is REFUSED — the authority
 * never evicts itself. **D-2c: 14080 KiB**, 4× the compaction trigger, as
 * today; peak 13.75 MiB per session directory. Open cost is
 * frame-count-dominated (M4), so this costs DISK, not latency.
 */
        const val WAL_MAX_BYTES: Long = 14080L * 1024

        /**
 * **D-2b: 3520 KiB** — re-derived to preserve F2's 3404 RECORDS per
 * compaction, hence 0.29 compactions per 1000 admissions and F2's open
 * latency. At F2's byte thresholds, rung 1024 would have given 4.03
 * compactions per 1000 admissions — 13.7× more, each now carrying a the specification
 * read-back verify.
 */
        const val COMPACT_AT_BYTES: Long = 3520L * 1024

        fun rootFor(filesDir: File): File = File(filesDir, AuthorityNaming.ROOT_DIR)

        /**
 * RETURNS `null` FOR A SESSION ID THAT CANNOT BE ENCODED INJECTIVELY
 *. A false refusal is the one direction this design may err in;
 * truncating would restore exactly the collision v2's naming removes.
 */
        fun dirFor(filesDir: File, sessionId: String): File? =
            AuthorityNaming.encodeSessionDirName(sessionId)?.let { File(rootFor(filesDir), it) }

        fun isPruned(dir: File): Boolean =
            File(dir, AuthorityNaming.PRUNED_MARKER).isFile && !File(dir, AuthorityNaming.HEAD).exists() &&
                (dir.listFiles()?.none { AuthorityNaming.isOwnSnapshotName(it.name) } ?: true)

        fun isOwnWalName(name: String): Boolean = AuthorityNaming.isOwnWalName(name)

        /**
 * Does [dir] hold any v2 authority artifact of ours?
 *
 * IT ASKS ABOUT THE **WITNESS OR A GENERATION-NAMED SNAPSHOT**, never
 * about one fixed file name. Under F2 "an authority exists" was
 * `snapshot.bin exists`; with generation-named snapshots a
 * fixed-name test would report ABSENT for every session past its first
 * compaction — and "absent" is the one answer that authorises a GENESIS.
 */
        fun authorityFilesPresent(dir: File): Boolean {
            if (File(dir, AuthorityNaming.HEAD).isFile) return true
            return dir.listFiles()?.any {
                it.isFile && (AuthorityNaming.isOwnSnapshotName(it.name) || AuthorityNaming.isOwnWalName(it.name))
            } ?: false
        }

        /**
 * The typed stage for a SINGLE-BODY role (head, snapshot, floors) whose body
 * count is not exactly one.
 *
 * **A BODY THAT FAILED AEAD IS A BODY VERDICT, NOT A MALFORMED COUNT**
 *. The container band (steps 4–13) opened: the preamble
 * parsed, the header authenticated and its semantics checked. What failed is
 * the frame at ordinal 1 — so the honest stage is `frame_auth_failed`, which
 * becomes `snapshot_frame_auth_failed:<g>` / `witness_frame_auth_failed`, and
 * a reader is pointed at a damaged BODY rather than at a count.
 */
        private fun singleBodyStage(r: AuthorityV2ContainerIo.Read.Ok): String =
            // A `auth_failure`, NOT `frame_auth_failed`. A3 step 12 names the
            // CONTAINER/HEADER stage `frame_auth_failed`; E.1 pins a single-body role
            // whose own BODY frame fails AEAD to `Open.Failed(snapshot_auth_failure:<gen>)`.
            // Two different faults, two different words -- collapsing them would make a
            // transplanted header indistinguishable from a bit-flipped body.
            if (r.present > 0L && r.authenticated < r.present) "auth_failure"
            else "malformed:body_count:${r.bodies.size}"

        /**
 * Gather the the specification input tuple from a directory. **READS ONLY.** Every artifact
 * is opened through the ONE container-band implementation, so each role has
 * the full three-state domain `{ABSENT, UNREADABLE(stage), OK}`.
 */
        private fun gather(
            dir: File,
            sessionId: String,
            io: AuthorityV2ContainerIo,
            v1DirPresent: Boolean,
            keyUnavailable: String?,
            floor: AuthorityReopen.FloorState,
            lockHeld: Boolean,
        ): AuthorityReopen.Inputs {
            // THE v3 SIGNATURE SNIFF **READS BYTES**, so it is inside the lock
            // and it runs BEFORE any AEAD attempt so a KNOWN format is
            // NAMED rather than reported as cryptographic corruption.
            //
            // **SCOPED TO THE ARTIFACTS THIS OPEN WOULD READ, AND THAT IS THE
            // DESIGN'S OWN READING.** the specification is explicitly "the per-FILE order" and
            // step 2 is a per-file sniff; the specification R1's disclosure column says "v1 rule
            // kept", and the accepted rule sniffs the snapshot and the WAL THIS OPEN
            // IS ABOUT TO READ. A directory-wide sniff would refuse a healthy
            // authority because a v3 file sits BESIDE it at a v3 name — and the specification
            // forbids even READING a v1/v3 artifact, so a file that is not ours is
            // not ours to sniff. (The accepted A3-3 property — v3 artifacts beside a
            // live authority are untouched and the authority still opens — is exactly
            // the subject a directory-wide sniff would have broken.)
            var v3 = false
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val ours = f.name == AuthorityNaming.HEAD ||
                    AuthorityNaming.isOwnSnapshotName(f.name) || AuthorityNaming.isOwnWalName(f.name)
                if (ours && V3Legacy.signatureOf(f) != null) v3 = true
            }

            val witness: AuthorityReopen.Artifact<AuthorityReopen.WitnessFacts> = run {
                when (val r = io.read(File(dir, AuthorityNaming.HEAD), AuthorityContainer.Role.HEAD, sessionId, null)) {
                    is AuthorityV2ContainerIo.Read.Absent -> AuthorityReopen.Artifact.Absent
                    is AuthorityV2ContainerIo.Read.ContainerRefused -> AuthorityReopen.Artifact.Unreadable(r.stage)
                    is AuthorityV2ContainerIo.Read.Ok -> {
                        if (r.bodies.size != 1) AuthorityReopen.Artifact.Unreadable(singleBodyStage(r))
                        else try {
                            val w = AuthorityCodec.decodeWitness(r.bodies[0])
                            if (w.sessionId != sessionId) AuthorityReopen.Artifact.Unreadable("session_mismatch")
                            else AuthorityReopen.Artifact.Ok(
                                AuthorityReopen.WitnessFacts(
                                    w.generation, w.walFrameCount, w.walTailDigest, w.highWaterClaim,
                                    w.walFileInstanceIdHex, w.snapshotFileInstanceIdHex,
                                ),
                            )
                        } catch (e: LossFormatException) {
                            AuthorityReopen.Artifact.Unreadable(e.reason)
                        }
                    }
                }
            }

            val snapshots = HashMap<Long, AuthorityReopen.Artifact<AuthorityReopen.SnapshotFacts>>()
            val wals = HashMap<Long, AuthorityReopen.Artifact<AuthorityReopen.WalFacts>>()
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                AuthorityNaming.snapshotGenerationOf(f.name)?.let { g ->
                    snapshots[g] = when (val r = io.read(f, AuthorityContainer.Role.SNAPSHOT, sessionId, g)) {
                        is AuthorityV2ContainerIo.Read.Absent -> AuthorityReopen.Artifact.Absent
                        is AuthorityV2ContainerIo.Read.ContainerRefused -> AuthorityReopen.Artifact.Unreadable(r.stage)
                        is AuthorityV2ContainerIo.Read.Ok ->
                            if (r.bodies.size != 1) AuthorityReopen.Artifact.Unreadable(singleBodyStage(r))
                            else try {
                                AuthorityReopen.Artifact.Ok(
                                    AuthorityReopen.SnapshotFacts(r.fileInstanceIdHex, AuthorityCodec.decodeSnapshot(r.bodies[0])),
                                )
                            } catch (e: LossFormatException) {
                                AuthorityReopen.Artifact.Unreadable(e.reason)
                            }
                    }
                }
                AuthorityNaming.walGenerationOf(f.name)?.let { g ->
                    wals[g] = when (val r = io.read(f, AuthorityContainer.Role.WAL, sessionId, g)) {
                        is AuthorityV2ContainerIo.Read.Absent -> AuthorityReopen.Artifact.Absent
                        is AuthorityV2ContainerIo.Read.ContainerRefused -> AuthorityReopen.Artifact.Unreadable(r.stage)
                        is AuthorityV2ContainerIo.Read.Ok -> AuthorityReopen.Artifact.Ok(
                            AuthorityReopen.WalFacts(
                                r.fileInstanceIdHex, r.present, r.authenticated, r.tailDigest,
                                r.tailDigestBeforeLast, r.tail != AuthorityV2ContainerIo.TailShape.NONE,
                                r.tail.note(), r.bodies,
                            ),
                        )
                    }
                }
            }
            return AuthorityReopen.Inputs(v1DirPresent, v3, keyUnavailable, lockHeld, witness, snapshots, wals, floor)
        }

        /**
 * READ-ONLY reconstruction of an authority directory — the same container
 * band, the same reopen machine, and **it creates, deletes and appends
 * nothing**. `prune` must decide whether a directory may be deleted, and
 * calling [open] to find out would WRITE a genesis snapshot into a directory
 * whose contents have not been understood.
 */
        internal fun inspect(
            dir: File,
            expectSessionId: String?,
            key: SecretKey,
            dirSync: DurableReplace.DirSync,
            walMaxBytes: Long = WAL_MAX_BYTES,
        ): Inspect {
            val io = AuthorityV2ContainerIo(key, SecureRandom())
            val sid = expectSessionId ?: AuthorityNaming.decodeSessionDirName(dir.name) ?: return Inspect.Failed("dir_name_undecodable")
            // F4-P0 Slice D child (emulator gate G10): a directory whose LISTING fails is
            // NOT an absent authority. `listFiles` answers null for an unreadable
            // directory (and for a non-directory); reading that as "no own files" made the
            // eviction scan report COMPLETE and EMPTY over a session whose intents were
            // still unresolved — and the marker was then removed (the capture specification: never
            // unreadable-as-empty). Invisible on the JVM venue, which runs as root.
            val listed = dir.listFiles() ?: return Inspect.Failed("dir_unlistable")
            val hasOwn = listed.any {
                it.isFile && (AuthorityNaming.isOwnWalName(it.name) || AuthorityNaming.isOwnSnapshotName(it.name) ||
                    it.name == AuthorityNaming.HEAD)
            }
            if (!hasOwn) {
                return if (File(dir, AuthorityNaming.PRUNED_MARKER).isFile) Inspect.Pruned else Inspect.Absent
            }
            val inputs = gather(dir, sid, io, false, null, AuthorityReopen.FloorState.Absent, lockHeld = true)
            return when (val d = AuthorityReopen.decide(inputs)) {
                is AuthorityReopen.Decision.Unavailable -> Inspect.Failed(d.reason)
                is AuthorityReopen.Decision.Failed -> Inspect.Failed(d.reason)
                is AuthorityReopen.Decision.Genesis -> Inspect.Absent
                is AuthorityReopen.Decision.RepairWitnessBenign ->
                    Inspect.Ok(Inspection(d.machine, d.generation, d.note, false, d.row))
                is AuthorityReopen.Decision.Open ->
                    Inspect.Ok(Inspection(d.machine, d.generation, d.note,
                        d.disable == DisableReason.AUTHORITY_INTERIOR_CORRUPTION, d.row))
            }
        }

        /**
 * Open (or create) the authority for [sessionId] under [dir], through the
 * the specification total reopen machine.
 *
 * **L0: THE ROOT LOCK IS ACQUIRED BEFORE ANY ARTIFACT BYTE IS READ.** The
 * v3-signature sniff OPENS THE FILE; every preamble parse and every AEAD
 * attempt is a read; every repair and both sweeps are mutations. All of them
 * are inside.
 *
 * This entry point ACQUIRES the lock and delegates to [openLocked]. A caller
 * that must hold ONE lock across a wider boundary — the facade, which reads
 * the durable floors and then decides a genesis from them — calls
 * [openLocked] directly while holding the same root lock.
 */
        fun open(
            dir: File,
            sessionId: String,
            key: SecretKey,
            dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
            random: SecureRandom = SecureRandom(),
            walMaxBytes: Long = WAL_MAX_BYTES,
            compactAtBytes: Long = COMPACT_AT_BYTES,
            genesisFloor: Long = -1L,
            /** The v2 authority ROOT — where the lock and the floors file live. */
            root: File = dir.parentFile ?: dir,
            /** the specification step 1: a PATH test on the v1 root. Reads no authority byte. */
            v1DirectoryPresent: Boolean = false,
            floorState: AuthorityReopen.FloorState = AuthorityReopen.FloorState.Absent,
        ): Open {
            // ── L0 ─────────────────────────────────────────────────────────
            val acq = AuthorityLock.acquire(root)
            if (acq is AuthorityLock.Acquisition.Unavailable) {
                // Reported as R3. NOTHING has been read.
                return Open.Failed("authority_lock_unavailable")
            }
            val held = acq as AuthorityLock.Acquisition.Ok
            try {
                return openLocked(dir, sessionId, key, dirSync, random, walMaxBytes, compactAtBytes,
                    genesisFloor, root, v1DirectoryPresent, floorState)
            } finally {
                held.release()
            }
        }

        /**
 * The open ITSELF. **THE ROOT LOCK MUST ALREADY BE HELD.**
 *
 * THIS ENTRY POINT EXISTS SO THAT ONE LOCK CAN COVER A WIDER DECISION
 * BOUNDARY THAN ONE OPEN. `LossAuthority.open` must read the durable floors
 * and then decide a genesis from them; if the floors read and this open each
 * took their own lock, another process could replace `session-floors.bin`
 * between the two and the genesis would be computed from a floor that is no
 * longer the durable one — a stale-floor genesis, i.e. sequence reuse
 *. It is NOT a second lock and NOT a relaxation: the
 * caller holds the SAME root lock this function's public twin would take.
 */
        fun openLocked(
            dir: File,
            sessionId: String,
            key: SecretKey,
            dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
            random: SecureRandom = SecureRandom(),
            walMaxBytes: Long = WAL_MAX_BYTES,
            compactAtBytes: Long = COMPACT_AT_BYTES,
            genesisFloor: Long = -1L,
            root: File = dir.parentFile ?: dir,
            v1DirectoryPresent: Boolean = false,
            floorState: AuthorityReopen.FloorState = AuthorityReopen.FloorState.Absent,
        ): Open {
            val io = AuthorityV2ContainerIo(key, random)
            run {
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return Open.Failed("dir_not_creatable")
                // Stale unique-temp residue from a crash.
                AuthorityDurableWrite.sweepStaleTemps(dir)

                val wasPruned = isPruned(dir)
                val inputs = gather(dir, sessionId, io, v1DirectoryPresent, null, floorState, lockHeld = true)

                return when (val d = AuthorityReopen.decide(inputs)) {
                    is AuthorityReopen.Decision.Unavailable -> Open.Failed(d.reason)
                    is AuthorityReopen.Decision.Failed -> Open.Failed(d.reason)
                    is AuthorityReopen.Decision.Genesis -> {
                        if (wasPruned && genesisFloor < 0L && d.genesisFloor < 0L)
                            return Open.Failed("pruned_session_without_floor")
                        genesis(root, dir, sessionId, key, dirSync, random, io, walMaxBytes, compactAtBytes,
                            maxOf(genesisFloor, d.genesisFloor), wasPruned)
                    }
                    is AuthorityReopen.Decision.RepairWitnessBenign ->
                        finishBenignRepair(root, dir, sessionId, key, dirSync, random, io, walMaxBytes, compactAtBytes, d, wasPruned)
                    is AuthorityReopen.Decision.Open ->
                        finishOpen(root, dir, sessionId, key, dirSync, random, io, walMaxBytes, compactAtBytes, d, inputs, wasPruned)
                }
            }
        }

        /**
 * the specification — THE BOOTSTRAP DURABILITY SEQUENCE (R6). Every interruption point
 * lands in a row of the specification, and **the witness is installed LAST**: a crash
 * anywhere leaves either nothing (R6) or a genesis snapshot the next open
 * repairs benignly (R8).
 *
 * ```
 * G1 create the session directory; fsync the parent
 * G2 mint the snapshot's fileInstanceId; write authority-snapshot-0.bin durably
 * G3 READ IT BACK: reopen, authenticate, decode, rebuild, compare a LOGICAL fingerprint
 * G4 mint the WAL's fileInstanceId; create authority-wal-0.bin with ZERO body frames
 * G5 install authority-head.bin naming generation 0 <-- THE COMMIT POINT
 * G6 the authority is open at generation 0
 * ```
 *
 * **GENESIS STARTS FROM THE FLOORS ROW, NOT FROM 0** — a session id that
 * was pruned reopens ABOVE its recorded floor (RULING A1). And the floor
 * raises `highWaterClaim`, which is a statement about **ALLOCATION ONLY**:
 * *"the floor says 'these numbers are spent', never 'these records were
 * delivered'"*. It creates no entries and asserts no delivery.
 */
        private fun genesis(
            root: File, dir: File, sessionId: String, key: SecretKey, dirSync: DurableReplace.DirSync,
            random: SecureRandom, io: AuthorityV2ContainerIo, walMaxBytes: Long, compactAtBytes: Long,
            floor: Long, wasPruned: Boolean,
        ): Open {
            val machine = SequenceAuthority.genesisAbove(sessionId, floor)

            // G2
            val snapInstance = io.newInstanceId()
            val snapPlain = AuthorityCodec.encodeSnapshot(machine.snapshot(0L))
            val snapBuilt = io.build(AuthorityContainer.Role.SNAPSHOT, snapInstance, sessionId, 0L, listOf(snapPlain))
            val snapFile = File(dir, AuthorityNaming.snapshot(0L))
            when (AuthorityDurableWrite.create(snapFile, snapBuilt.bytes, dirSync)) {
                AuthorityDurableWrite.Outcome.COMMITTED -> Unit
                AuthorityDurableWrite.Outcome.NOT_COMMITTED_PROVEN_UNCHANGED ->
                    return Open.Failed("genesis_snapshot_not_durable")
                AuthorityDurableWrite.Outcome.REFUSED_TARGET_EXISTS ->
                    // A genesis decision over a directory that already has a
                    // snapshot-0 is a contradiction of the tuple that produced it.
                    return Open.Failed("genesis_snapshot_target_exists")
                else -> return Open.Failed("genesis_snapshot_indeterminate")
            }

            // the specification S14: G2 has committed the snapshot and nothing else exists yet.
            AuthorityFaults.at(AuthorityFaults.Point.GENESIS_AFTER_SNAPSHOT)

            // G3 — READ IT BACK, logical fingerprint, the the specification rule applied to
            // genesis too. A mismatch LEAVES THE FILE: the next open lands R8/R9/R10.
            val back = io.read(snapFile, AuthorityContainer.Role.SNAPSHOT, sessionId, 0L)
            val verified = (back as? AuthorityV2ContainerIo.Read.Ok)?.takeIf { it.bodies.size == 1 }?.let {
                try { SequenceAuthority.fromSnapshot(AuthorityCodec.decodeSnapshot(it.bodies[0])) } catch (e: LossFormatException) { null }
            }
            if (verified == null || verified.logicalFingerprint() != machine.logicalFingerprint())
                return Open.Failed("genesis_snapshot_unverified")

            // the specification S14: G3 has verified the snapshot; the WAL does not exist yet.
            AuthorityFaults.at(AuthorityFaults.Point.GENESIS_AFTER_VERIFY)

            // G4
            val walInstance = io.newInstanceId()
            val walBuilt = io.build(AuthorityContainer.Role.WAL, walInstance, sessionId, 0L, emptyList())
            val walF = File(dir, AuthorityNaming.wal(0L))
            val walOut = AuthorityDurableWrite.createOrAdoptEmpty(
                walF, { walBuilt.bytes }, AuthorityContainer.hex(walInstance), dirSync,
            ) { f -> io.probeAdoptableEmpty(f, AuthorityContainer.Role.WAL, sessionId, 0L) }
            val walHex = when (walOut.outcome) {
                AuthorityDurableWrite.Outcome.COMMITTED, AuthorityDurableWrite.Outcome.ADOPTED ->
                    walOut.fileInstanceIdHex ?: return Open.Failed("genesis_wal_not_durable")
                else -> return Open.Failed("genesis_wal_not_durable")
            }

            // the specification S14: G4 has created the empty WAL; the witness does not exist yet, so a
            // death here is exactly R8's arranged state (a genesis snapshot, no witness).
            AuthorityFaults.at(AuthorityFaults.Point.GENESIS_AFTER_WAL)

            // G5 — THE COMMIT POINT OF BOOTSTRAP.
            val witness = AuthorityCodec.Witness(
                sessionId = sessionId,
                generation = 0L,
                walFrameCount = 0L,
                walTailDigest = AuthorityContainer.hex(AuthorityContainer.chainSeed(hexToBytes(walHex), 0L)),
                highWaterClaim = maxOf(floor, -1L),
                walFileInstanceIdHex = walHex,
                snapshotFileInstanceIdHex = snapBuilt.fileInstanceIdHex,
            )
            val headInstance = io.newInstanceId()
            val headBuilt = io.build(AuthorityContainer.Role.HEAD, headInstance, sessionId, 0L, listOf(AuthorityCodec.encodeWitness(witness)))
            when (AuthorityDurableWrite.replace(File(dir, AuthorityNaming.HEAD), headBuilt.bytes, dirSync)) {
                AuthorityDurableWrite.Outcome.COMMITTED -> Unit
                else -> return Open.Failed("genesis_witness_not_durable")
            }

            // the specification S14: G5 has COMMITTED. A death after this point must reopen NORMALLY.
            AuthorityFaults.at(AuthorityFaults.Point.GENESIS_AFTER_WITNESS)

            if (wasPruned) File(dir, AuthorityNaming.PRUNED_MARKER).delete()
            val preamble = io.preambleOf(walF, AuthorityContainer.Role.WAL) ?: return Open.Failed("genesis_wal_preamble_unreadable")
            val store = EncryptedAuthorityStore(root, dir, sessionId, key, dirSync, machine, 0L, random, null,
                walMaxBytes, compactAtBytes, io, witness, 0L, preamble)
            store.headInstanceId = headInstance
            RecoveryTrace.log("authority_opened", "session" to sessionId.take(24), "gen" to 0L,
                "row" to "R6", "highWater" to machine.highWater(), "note" to "genesis")
            return Open.Ok(store)
        }

        private fun finishBenignRepair(
            root: File, dir: File, sessionId: String, key: SecretKey, dirSync: DurableReplace.DirSync,
            random: SecureRandom, io: AuthorityV2ContainerIo, walMaxBytes: Long, compactAtBytes: Long,
            d: AuthorityReopen.Decision.RepairWitnessBenign, wasPruned: Boolean,
        ): Open {
            // R8: the WAL may be absent (a crash between G3 and G4). Create it, then
            // install the witness — the specification G4 + G5, which is exactly what genesis would
            // have written, so this repair is byte-equivalent rather than inventive.
            val walF = File(dir, AuthorityNaming.wal(d.generation))
            val walInstance = io.newInstanceId()
            val walOut = AuthorityDurableWrite.createOrAdoptEmpty(
                walF,
                { io.build(AuthorityContainer.Role.WAL, walInstance, sessionId, d.generation, emptyList()).bytes },
                AuthorityContainer.hex(walInstance), dirSync,
            ) { f -> io.probeAdoptableEmpty(f, AuthorityContainer.Role.WAL, sessionId, d.generation) }
            val walHex = when (walOut.outcome) {
                AuthorityDurableWrite.Outcome.COMMITTED, AuthorityDurableWrite.Outcome.ADOPTED ->
                    walOut.fileInstanceIdHex ?: return Open.Failed("genesis_wal_not_durable")
                else -> return Open.Failed("genesis_wal_not_durable")
            }
            val witness = AuthorityCodec.Witness(
                sessionId, d.generation, 0L,
                AuthorityContainer.hex(AuthorityContainer.chainSeed(hexToBytes(walHex), d.generation)),
                d.machine.highWater(), walHex, d.snapshotInstanceIdHex,
            )
            val headInstance = io.newInstanceId()
            val headBuilt = io.build(AuthorityContainer.Role.HEAD, headInstance, sessionId, d.generation,
                listOf(AuthorityCodec.encodeWitness(witness)))
            when (AuthorityDurableWrite.replace(File(dir, AuthorityNaming.HEAD), headBuilt.bytes, dirSync)) {
                AuthorityDurableWrite.Outcome.COMMITTED -> Unit
                else -> return Open.Failed("genesis_witness_not_durable")
            }
            if (wasPruned) File(dir, AuthorityNaming.PRUNED_MARKER).delete()
            val preamble = io.preambleOf(walF, AuthorityContainer.Role.WAL) ?: return Open.Failed("genesis_wal_preamble_unreadable")
            val store = EncryptedAuthorityStore(root, dir, sessionId, key, dirSync, d.machine, d.generation, random,
                d.note, walMaxBytes, compactAtBytes, io, witness, 0L, preamble)
            store.headInstanceId = headInstance
            RecoveryTrace.log("authority_opened", "session" to sessionId.take(24), "gen" to d.generation,
                "row" to d.row, "note" to d.note)
            return Open.Ok(store)
        }

        private fun finishOpen(
            root: File, dir: File, sessionId: String, key: SecretKey, dirSync: DurableReplace.DirSync,
            random: SecureRandom, io: AuthorityV2ContainerIo, walMaxBytes: Long, compactAtBytes: Long,
            d: AuthorityReopen.Decision.Open, inputs: AuthorityReopen.Inputs, wasPruned: Boolean,
        ): Open {
            var generation = d.generation
            var note = d.note
            var row = d.row
            val machine = d.machine

            // The witness this open will believe. R19/R10 repair it; every other row
            // uses what is on disk.
            var witness = d.repairWitnessTo?.let {
                AuthorityCodec.Witness(sessionId, it.generation, it.walFrameCount, it.walTailDigest,
                    it.highWaterClaim, it.walFileInstanceIdHex, it.snapshotFileInstanceIdHex)
            } ?: (inputs.witness as? AuthorityReopen.Artifact.Ok<AuthorityReopen.WitnessFacts>)?.value?.let {
                AuthorityCodec.Witness(sessionId, it.generation, it.walFrameCount, it.walTailDigest,
                    it.highWaterClaim, it.walFileInstanceIdHex, it.snapshotFileInstanceIdHex)
            } ?: return Open.Failed("witness_absent_at_finish")

            val headInstance = io.newInstanceId()
            if (d.repairWitnessTo != null) {
                val built = io.build(AuthorityContainer.Role.HEAD, headInstance, sessionId, witness.generation,
                    listOf(AuthorityCodec.encodeWitness(witness)))
                when (AuthorityDurableWrite.replace(File(dir, AuthorityNaming.HEAD), built.bytes, dirSync)) {
                    AuthorityDurableWrite.Outcome.COMMITTED -> Unit
                    else -> return Open.Failed("witness_repair_not_durable")
                }
            }

            var walPresent = (inputs.wals[generation] as? AuthorityReopen.Artifact.Ok<AuthorityReopen.WalFacts>)?.value?.present ?: 0L

            // ── the specification, from R23/R24 ONLY. ───────────────────────────────────
            if (d.runInterruptedCompaction) {
                when (val ic = AuthorityReopen.interruptedCompaction(inputs, generation, machine)) {
                    is AuthorityReopen.IcDecision.Refuse -> return Open.Failed(ic.reason)
                    is AuthorityReopen.IcDecision.Preserve -> ic.note?.let { note = listOfNotNull(note, it).joinToString(";") }
                    is AuthorityReopen.IcDecision.Complete -> {
                        // IC2 — advance the witness to g+1, binding the instance ids
                        // it FOUND. THIS PATH WRITES OVER NOTHING.
                        val adopted = AuthorityCodec.Witness(
                            sessionId, ic.toGeneration, 0L,
                            AuthorityContainer.hex(AuthorityContainer.chainSeed(hexToBytes(ic.walInstanceIdHex), ic.toGeneration)),
                            witness.highWaterClaim, ic.walInstanceIdHex, ic.snapshotInstanceIdHex,
                        )
                        val built = io.build(AuthorityContainer.Role.HEAD, headInstance, sessionId, ic.toGeneration,
                            listOf(AuthorityCodec.encodeWitness(adopted)))
                        when (AuthorityDurableWrite.replace(File(dir, AuthorityNaming.HEAD), built.bytes, dirSync)) {
                            AuthorityDurableWrite.Outcome.COMMITTED -> Unit
                            else -> return Open.Failed("compaction_completion_not_durable")
                        }
                        // Re-enter the specification ONCE at g+1. IC1 has already proven there is
                        // no second candidate, so the single re-entry TERMINATES.
                        val reInputs = gather(dir, sessionId, io, inputs.v1DirectoryPresent, null, inputs.floor, lockHeld = true)
                        val again = AuthorityReopen.decide(reInputs)
                        if (again !is AuthorityReopen.Decision.Open || again.generation != ic.toGeneration)
                            return Open.Failed("compaction_completion_reentry_unresolved")
                        generation = again.generation
                        note = listOfNotNull(note, ic.note).joinToString(";")
                        row = again.row
                        witness = adopted
                        walPresent = 0L
                        return buildStore(root, dir, sessionId, key, dirSync, random, io, walMaxBytes, compactAtBytes,
                            again.machine, generation, note, witness, walPresent, headInstance, again.disable, row, wasPruned)
                    }
                }
            }

            return buildStore(root, dir, sessionId, key, dirSync, random, io, walMaxBytes, compactAtBytes,
                machine, generation, note, witness, walPresent, headInstance, d.disable, row, wasPruned)
        }

        private fun buildStore(
            root: File, dir: File, sessionId: String, key: SecretKey, dirSync: DurableReplace.DirSync,
            random: SecureRandom, io: AuthorityV2ContainerIo, walMaxBytes: Long, compactAtBytes: Long,
            machine: SequenceAuthority, generation: Long, note: String?, witness: AuthorityCodec.Witness,
            walPresent: Long, headInstance: ByteArray, disable: DisableReason?, row: String, wasPruned: Boolean,
        ): Open {
            val walF = File(dir, AuthorityNaming.wal(generation))
            val preamble = io.preambleOf(walF, AuthorityContainer.Role.WAL)
                ?: return Open.Failed("wal_preamble_unreadable:$generation")
            if (wasPruned) File(dir, AuthorityNaming.PRUNED_MARKER).delete()
            val store = EncryptedAuthorityStore(root, dir, sessionId, key, dirSync, machine, generation, random,
                note, walMaxBytes, compactAtBytes, io, witness, walPresent, preamble)
            store.headInstanceId = headInstance
            // THE ORPHAN SWEEP — generations
            // strictly BELOW the adopted one, and ONLY once the current state has
            // been established. **Nothing at or above `g` may be deleted, swept,
            // truncated or OVERWRITTEN** — not by the sweep, not by a repair, not
            // because the witness is older. A non-empty or unreadable
            // higher-generation artifact never even reaches here: R11 refuses first,
            // and the specification IC3 PRESERVES everything it cannot complete.
            store.retireBelow(generation)

            if (disable != null && machine.disabled() == null) {
                // THE DURABILITY RESULT IS READ (the F2 blocker, carried forward).
                // COMMITTED -> the fact exists on disk; open proceeds disabled
                // REFUSED -> the fact could NOT be made durable, so an open
                // saying "disabled" would describe memory and one
                // saying "healthy" would lie: REFUSE
                // INDETERMINATE -> nothing may act on either reading until a reopen
                // re-reads the truth: REFUSE (the reopen IS the fence)
                when (val a = store.append(LossRecord.CheckpointDisabled("dis-${disable.name}", disable))) {
                    is Append.Appended -> Unit
                    is Append.Rejected -> {
                        RecoveryTrace.log("authority_disabled_not_durable", "why" to a.reason, "session" to sessionId.take(24))
                        return Open.Failed("disabled_not_durable:${a.reason}")
                    }
                    is Append.Indeterminate -> {
                        RecoveryTrace.log("authority_disabled_indeterminate", "why" to a.reason, "session" to sessionId.take(24))
                        return Open.Failed("disabled_fact_indeterminate:${a.reason}")
                    }
                }
            }
            RecoveryTrace.log("authority_opened", "session" to sessionId.take(24), "gen" to generation,
                "row" to row, "highWater" to machine.highWater(), "note" to (note ?: "ok"),
                "disabled" to (machine.disabled()?.name ?: "no"))
            return Open.Ok(store)
        }

        internal fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                fun d(c: Char): Int = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> throw LossFormatException("hex_malformed")
                }
                out[i] = ((d(hex[2 * i]) shl 4) or d(hex[2 * i + 1])).toByte()
            }
            return out
        }
    }
}

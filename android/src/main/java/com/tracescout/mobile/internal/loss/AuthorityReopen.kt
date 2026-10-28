package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. `public` only because Kotlin `internal` is
 * compilation-module scoped.
 */

/**
 * THE TOTAL REOPEN STATE MACHINE (F3-P0 the specification) and the interrupted-compaction
 * subroutine — **ORDERED, TOTAL, MUTUALLY EXCLUSIVE. THE FIRST MATCHING
 * ROW DECIDES.**
 *
 * **PURE, AND THAT IS WHY IT LIVES HERE.** It owns no file, no key, no lock and
 * no clock: the `:sdk` store gathers the input tuple under the root lock and
 * PERFORMS whatever this decides. the specification **S20** depends on the purity — R22's tuple
 * (`Wpresent = C`, `Wauth = C`, `D ≠ witness.D`) is handed to this machine
 * DIRECTLY, because the specification proves no filesystem fault can produce it and a
 * fabricated fault path would produce a green row from a mutation that never
 * reached the subject.
 *
 * ## L0 IS A PRE-ROW STEP, AND THE IDS ARE DELIBERATELY NOT RENUMBERED
 *
 * The root lock is acquired **BEFORE ANY ARTIFACT BYTE IS READ**. v3 of the design evaluated `LOCK` at row R3, i.e. AFTER R1's
 * `V3SIG` sniff — and that sniff OPENS AND READS THE FILE, so the v3 order read
 * artifact bytes outside the lock while another process could be mid-`replace`.
 * **The row ORDER is a REFUSAL PRECEDENCE order and was never an acquisition
 * order**; v3 simply never said so, and a reader implementing the table
 * top-to-bottom would have got it wrong. R3 keeps its number and its reason; what
 * changed is that it now REPORTS an L0 failure. Renumbering R0…R24 would require
 * re-pointing every citation in the specification, the specification, the specification, the specification, the specification, the specification and the specification, and a
 * renumbering that misses one leaves a wrong pointer in the one document whose
 * entire value is precision.
 *
 * ## THE HONEST BOUNDARY, STATED AND NOT WORKED AROUND
 *
 * **A coordinated rollback of the WAL *and* its witness *and* every snapshot to
 * mutually consistent older authenticated versions is NOT detectable without a
 * hardware monotonic counter. This machine does not detect it and does not claim
 * to.** What IS claimed: every SINGLE-artifact rollback is NAMED — a rolled-back
 * WAL by R18, a rolled-back or substituted snapshot by R12/R13/R15, a lost witness
 * by R10, a corrupt witness by R5, a rolled-back witness by R11 (when records
 * exist above) or by R15/R16 (when the instance bindings disagree) — and in every
 * one of those states allocation REFUSES. the specification C16 pins BOTH halves from one
 * arranged state, because an absence-only assertion cannot certify that the
 * witness exists at all.
 */
public object AuthorityReopen {

    // ────────────────────────────────────────────────────── the input tuple

    /**
 * THE THREE-STATE CONTAINER DOMAIN, **APPLIED TO EVERY ROLE**.
 *
 * `Unreadable` IS THE V3-B1 INPUT-DOMAIN CLOSURE. v3's WAL input was
 * `ABSENT | present(.)`, i.e. two states where the other three artifacts each
 * had three — so a WAL whose CONTAINER was unreadable had **no row at all**,
 * and the states it would have fallen into (`Wauth < Wpresent` ⇒ R17) write a
 * durable disable that says the record BODY is corrupt, about a file whose
 * PREAMBLE or HEADER never opened. **A refusal that names the wrong layer is
 * worse than an unnamed one, because it is actionable in the wrong direction.**
 */
    public sealed class Artifact<out T> {
        public object Absent : Artifact<Nothing>()
        /** A CONTAINER-layer stage, WITHOUT the role prefix. */
        public data class Unreadable(val stage: String) : Artifact<Nothing>()
        public data class Ok<T>(val value: T) : Artifact<T>()
    }

    /** The authenticated witness facts. */
    public data class WitnessFacts(
        val generation: Long,
        /** `C` — LOG EXTENT AND NOTHING ELSE. */
        val walFrameCount: Long,
        /** `D` — the committed tail chain. */
        val walTailDigest: String,
        /** The FACTUAL high-water. Never derived from a file's extent or size. */
        val highWaterClaim: Long,
        val walFileInstanceIdHex: String,
        val snapshotFileInstanceIdHex: String,
    )

    public data class SnapshotFacts(
        val fileInstanceIdHex: String,
        val snapshot: AuthorityCodec.AuthoritySnapshot,
    )

    public data class WalFacts(
        val fileInstanceIdHex: String,
        /** `Wpresent` — by LENGTH PREFIX, before any AEAD attempt. */
        val present: Long,
        /** `Wauth` — of those, how many authenticated. */
        val authenticated: Long,
        /** `D` recomputed over the `Wpresent` sealed frames. `null` = `not_evaluated`. */
        val tailDigest: String?,
        /**
 * The chain over the FIRST `Wpresent - 1` frames (the seed when
 * `Wpresent == 1`, `null` when `Wpresent == 0`).
 *
 * **TWO VALUES, NOT A LIST, AND THE REASON IS EXACT.** R19's condition 3
 * needs the chain over the first `C` frames, and R19 only ever applies when
 * `Wpresent == C + 1` — so the prefix it needs is always the chain one frame
 * back. Carrying every intermediate digest would be 437 KB of state on a
 * 13,653-frame WAL to answer a question with two possible subjects. Do not
 * "generalise" this into a list.
 */
        val tailDigestBeforeLast: String?,
        val tornTail: Boolean,
        /** The disclosure for a torn tail — `torn_tail` or `torn_tail:length_implausible`. */
        val tornTailNote: String?,
        /** The UNPADDED plaintexts of the frames that authenticated, in file order. */
        val bodies: List<ByteArray>,
    )

    public sealed class FloorState {
        public object Absent : FloorState()
        public data class Present(val value: Long) : FloorState()
        /**
 * "I CANNOT READ THE FLOORS" AND "THERE ARE NO FLOORS" ARE DIFFERENT
 * FACTS, and the conservative cliff is the accepted shape.
 */
        public object Unreadable : FloorState()
    }

    public class Inputs(
        /** A PATH test only — `File.isDirectory` on the v1 root; no authority byte is read. */
        public val v1DirectoryPresent: Boolean,
        /** READS BYTES, so it is inside the lock. */
        public val v3SignaturePresent: Boolean,
        public val keyUnavailableReason: String?,
        /** L0. `false` ⇒ NOTHING has been read; no tuple exists. */
        public val lockHeld: Boolean,
        public val witness: Artifact<WitnessFacts>,
        public val snapshots: Map<Long, Artifact<SnapshotFacts>>,
        public val wals: Map<Long, Artifact<WalFacts>>,
        public val floor: FloorState,
    )

    // ─────────────────────────────────────────────────────────── the verdict

    /**
 * What the caller must DO. Every variant names its the specification row, so a trace and an
 * acceptance row talk about the same thing.
 */
    public sealed class Decision {
        public abstract val row: String

        /** Capture is refused for this session; the caller allocates nothing. */
        public data class Unavailable(override val row: String, val reason: String) : Decision()

        /**
 * The open fails. [preserve] is BINDING where it is set: do not repair
 * over the artifact, do not overwrite it, do not delete it, do not infer a
 * generation from filenames. An artifact that exists and fails
 * authentication is EVIDENCE; repairing over it destroys the only thing
 * that could explain what is wrong, and treating it as absent converts a
 * detected integrity failure into a silent self-heal.
 *
 * [writesDisable] is `false` for every CONTAINER-band failure: a durable claim about the wrong layer outlives the fault and
 * cannot be withdrawn by a later correct read.
 */
        public data class Failed(
            override val row: String,
            val reason: String,
            val preserve: Boolean = true,
            val writesDisable: Boolean = false,
        ) : Decision()

        /** R6 — FRESH GENESIS, per the specification The genesis floor comes from [FloorState]. */
        public data class Genesis(override val row: String, val genesisFloor: Long) : Decision()

        /**
 * R8 — BENIGN: repair the witness from the genesis snapshot. Allocation permitted, EXACT, and **no disable**: the witness this
 * writes is byte-equivalent to what genesis would have written, which is
 * the same reasoning F2 applies to `genesis_snapshot_directory_unsynced`.
 */
        public data class RepairWitnessBenign(
            override val row: String,
            val generation: Long,
            val machine: SequenceAuthority,
            val walInstanceIdHex: String?,
            val snapshotInstanceIdHex: String,
            val note: String,
        ) : Decision()

        /**
 * The authority opens. [disable] is non-null for every row whose extent or
 * witness state is anomalous — and RULING A2 then refuses admission, which
 * is why those rows do not each restate it.
 *
 * [repairWitnessTo] is R19's one-step upward repair and R10's reconstruction;
 * `null` means the witness on disk already describes this state.
 */
        public data class Open(
            override val row: String,
            val generation: Long,
            val machine: SequenceAuthority,
            val disable: DisableReason?,
            val note: String?,
            val repairWitnessTo: RepairWitness?,
            /** EXACT means allocation may proceed from this state. */
            val exact: Boolean,
            /** Set for R23/R24 only: the caller must run the specification next. */
            val runInterruptedCompaction: Boolean,
        ) : Decision()
    }

    /** The witness a repair must install. Computed here so the caller does not re-derive it. */
    public data class RepairWitness(
        val generation: Long,
        val walFrameCount: Long,
        val walTailDigest: String,
        val highWaterClaim: Long,
        val walFileInstanceIdHex: String,
        val snapshotFileInstanceIdHex: String,
    )

    // ─────────────────────────────────────────────────────────────── decide

    /**
 * EVALUATION ORDER IS: **L0 → R0 → R1 → R2 → R4 → R5 → R6…R10 → R11 →
 * R12/R13 → R14/R14b → R15/R16 → R17…R24.**
 *
 * L0 first because the lock precedes the tuple; the rest in the table's own
 * precedence order.
 */
    public fun decide(i: Inputs): Decision {
        // ── L0 ─────────────────────────────────────────────────────────────
        // Reported as R3. NOTHING has been read when this fires: no tuple exists,
        // no repair, no sweep.
        if (!i.lockHeld) return Decision.Failed("R3", "authority_lock_unavailable", preserve = true)

        // ── R0 ─────────────────────────────────────────────────────────────
        // REFUSAL IS WHAT PREVENTS REUSE HERE. A v1 directory's
        // high-water is not in the v1 floors file (floors are written only at
        // prune), so v2 cannot know how far that session allocated. Genesising it
        // at 0 would be the exact RULING A1 violation; refusing allocates nothing,
        // so there is nothing to collide.
        if (i.v1DirectoryPresent) return Decision.Unavailable("R0", "legacy_authority_v1_present")

        // ── R1 ─────────────────────────────────────────────────────────────
        if (i.v3SignaturePresent) return Decision.Failed("R1", "format_version_unsupported:3")

        // ── R2 ─────────────────────────────────────────────────────────────
        // A MISSING KEY IS NOT CORRUPTION, and it is evaluated at the specification step 3 —
        // BEFORE any AEAD attempt — so it can never be reported as one.
        i.keyUnavailableReason?.let { return Decision.Failed("R2", "key_unavailable:$it") }

        // ── R4 ─────────────────────────────────────────────────────────────
        if (i.floor is FloorState.Unreadable) return Decision.Unavailable("R4", "legacy_floors_unreadable")
        val floorValue = (i.floor as? FloorState.Present)?.value ?: -1L

        // ── R5 ─────────────────────────────────────────────────────────────
        // a product decision, BINDING, AND NOT THIS DESIGN'S TO RELAX. Evaluated FIRST among
        // the witness rows because an unreadable witness NAMES NO GENERATION, so
        // nothing below it can be evaluated.
        (i.witness as? Artifact.Unreadable)?.let {
            return Decision.Failed("R5", "witness_${it.stage}", preserve = true, writesDisable = false)
        }

        val snapsOk = i.snapshots.filterValues { it is Artifact.Ok<*> }
            .mapValues { (_, v) -> (v as Artifact.Ok<SnapshotFacts>).value }

        if (i.witness is Artifact.Absent) return decideWitnessAbsent(i, snapsOk, floorValue)

        val w = (i.witness as Artifact.Ok<WitnessFacts>).value
        val g = w.generation

        // ── R11 ────────────────────────────────────────────────────────────
        // EVALUATED BEFORE THE AT-`g` ARTIFACT ROWS because it is the STRONGEST
        // AVAILABLE EVIDENCE and it EXPLAINS their absences. A non-empty
        // higher-generation WAL means a compaction committed and records were
        // written at `h` while the witness now names `g` — a witness rollback.
        // v2 asserted such a WAL "is not producible by a correct sequence"; that
        // statement is WITHDRAWN — an interrupted compaction produces it.
        // The conclusion (never sweep it) was right; the reason was wrong, and a
        // rule resting on a false premise is one somebody later "corrects" by
        // deleting the rule.
        for ((h, a) in i.wals.entries.sortedBy { it.key }) {
            if (h <= g) continue
            when (a) {
                // THE UNREADABLE SUB-CASE: an unreadable higher-generation WAL
                // cannot be shown to be EMPTY, so it cannot be shown to be the
                // harmless interrupted-compaction residue the specification completes. Fail
                // closed and preserve — and note that an empty-but-READABLE
                // `WAL[g+1]` deliberately does NOT match this row: it is IC2's
                // subject.
                is Artifact.Unreadable ->
                    return Decision.Failed("R11", "higher_generation_wal_unreadable:$h,${a.stage}")
                is Artifact.Ok<WalFacts> ->
                    if (a.value.present > 0L) return Decision.Failed("R11", "higher_generation_wal_nonempty:$h")
                is Artifact.Absent -> Unit
            }
        }

        // ── R12 / R13 ──────────────────────────────────────────────────────
        // **NEVER GENESIS**, and never fall back to an older snapshot — that IS
        // the P3 rollback. This single rule is what turns P3's measured "genesis at
        // 0 while 0.6 were issued" into a refusal.
        when (val s = i.snapshots[g]) {
            null, is Artifact.Absent ->
                return Decision.Failed("R12", "snapshot_missing_for_witness_generation:$g")
            is Artifact.Unreadable ->
                return Decision.Failed("R13", "snapshot_${s.stage}:$g")
            is Artifact.Ok<SnapshotFacts> -> Unit
        }
        val snap = snapsOk.getValue(g)

        // ── R14 / R14b ─────────────────────────────────────────────────────
        val walAt = i.wals[g]
        when (walAt) {
            null, is Artifact.Absent ->
                // THE ROW v2 LET FALL THROUGH. Every adopted generation has an
                // EXPLICIT WAL file — created with zero body frames at bootstrap
                // and at compaction — so its absence is the
                // DELETION of an authority artifact the witness names. Fail closed
                // for BOTH sub-cases: `C > 0` is unambiguously destroyed history,
                // and `C == 0` is still an unexplained deletion. **Recreating it is
                // refused**: a repair would need a NEW instance id, which would then
                // contradict `witness.walFileInstanceId` and require a second write,
                // to paper over a file nobody can explain the loss of.
                // `claimedFrames` is disclosed so the two sub-cases are
                // distinguishable in the trace.
                return Decision.Failed("R14", "wal_missing_for_witness_generation:$g,claimedFrames=${w.walFrameCount}")
            is Artifact.Unreadable ->
                // R14b — THE V3-B1 ROW. `Wpresent`, `Wauth` and `D` are
                // `not_evaluated` and every extent row below is UNREACHABLE for this
                // state. Three things are forbidden and each has its own control
                //: no extent, not interior corruption, and **NO durable
                // disable**. Not repaired and not recreated — for R14's reason and
                // for a product decision's. A second open must land this SAME row.
                return Decision.Failed("R14b", "wal_${walAt.stage}:$g", preserve = true, writesDisable = false)
            is Artifact.Ok<WalFacts> -> Unit
        }
        val wal = (walAt as Artifact.Ok<WalFacts>).value

        // ── R15 / R16 ──────────────────────────────────────────────────────
        // A DIFFERENT, INTERNALLY SELF-CONSISTENT, CORRECTLY-NAMED ARTIFACT has
        // been substituted at this generation (a restore, or an orphan from a
        // failed compaction attempt). the specification cannot see it; the witness binding can.
        if (snap.fileInstanceIdHex != w.snapshotFileInstanceIdHex)
            return Decision.Failed("R15", "witness_snapshot_instance_mismatch:$g")
        if (wal.fileInstanceIdHex != w.walFileInstanceIdHex)
            return Decision.Failed("R16", "witness_wal_instance_mismatch:$g")

        // ── the extent band. Reachable ONLY for WAL[g] = PRESENT. ────
        val machine = try {
            SequenceAuthority.fromSnapshot(snap.snapshot)
        } catch (e: LossFormatException) {
            return Decision.Failed("R13", "snapshot:${e.reason}")
        }

        // ── R17 ────────────────────────────────────────────────────────────
        if (wal.authenticated < wal.present) {
            // Replay what authenticates. THE DIGEST IS `not_evaluated`, NOT
            // MISMATCHED: the chain is over the sealed bytes as they
            // lie on disk, so under interior corruption it mismatches AS A
            // CONSEQUENCE — one physical fault must not satisfy two acceptance rows
            // with different expected reasons.
            replay(machine, wal.bodies)?.let { return Decision.Failed("R17", "replay:$it") }
            return Decision.Open(
                "R17", g, machine, DisableReason.AUTHORITY_INTERIOR_CORRUPTION,
                // the specification R17's note is `interior:<class>` — a FAULT CLASS, not a count. A bare
                // count answers "how many" and never "what went wrong", and
                // `snapshot_auth_failure` is the design's own word for this class,
                // so using it here keeps ONE vocabulary for one fault across both roles.
                // The count is appended because it is free information, never instead of
                // the class.
                "interior:auth_failure:${wal.present - wal.authenticated}", null, exact = false, runInterruptedCompaction = false,
            )
        }

        // ── R18 ────────────────────────────────────────────────────────────
        if (wal.present < w.walFrameCount) {
            // Replay what is there; NEVER lower the floor. `highWaterClaim` from the
            // witness is the FACTUAL high-water as of the committed extent, and it
            // is the thing a truncation is trying to erase.
            replay(machine, wal.bodies)?.let { return Decision.Failed("R18", "replay:$it") }
            return Decision.Open(
                "R18", g, machine, DisableReason.AUTHORITY_TAIL_ROLLBACK,
                "wal_behind_witness:${w.walFrameCount - wal.present}", null, exact = false, runInterruptedCompaction = false,
            )
        }

        // ── R19 / R20 ──────────────────────────────────────────────────────
        if (wal.present == w.walFrameCount + 1L) {
            // R19's FIVE conditions — all required, and the third is the
            // load-bearing one. Conditions 1 and 2 are counts; 5 is R15/R16, which
            // have already passed; 3 is the PREFIX PROOF and 4 is a replay fact.
            val prefixOk = (wal.tailDigestBeforeLast != null && wal.tailDigestBeforeLast == w.walTailDigest)
            if (!prefixOk) {
                // v2's R14 asserted only condition 1 and "the frames are ours and
                // authenticated". Condition 3 is what makes the repair DETERMINISTIC
                // rather than a plausible guess: without it a coincidental COUNT
                // match over a DIFFERENT history repairs upward.
                replay(machine, wal.bodies)?.let { return Decision.Failed("R20", "replay:$it") }
                return Decision.Open(
                    "R20", g, machine, DisableReason.AUTHORITY_WITNESS_BEHIND_WAL,
                    "witness_behind_wal_unverified:1", null, exact = false, runInterruptedCompaction = false,
                )
            }
            // Condition 4: the extra frame decodes to a record the machine ACCEPTS
            // when replayed IN ORDER. It was validated before it was written, so a
            // refusal here means it is NOT OURS.
            val committed = wal.bodies.dropLast(1)
            replay(machine, committed)?.let { return Decision.Failed("R19", "replay:$it") }
            val extra = wal.bodies.last()
            val extraOk = try {
                val r = AuthorityCodec.decodeRecord(extra)
                machine.validate(r).let { v ->
                    when (v) {
                        is SequenceAuthority.Validation.Refused -> null
                        is SequenceAuthority.Validation.AlreadyApplied -> r
                        is SequenceAuthority.Validation.Accepted -> { machine.install(v); r }
                    }
                }
            } catch (e: LossFormatException) {
                null
            }
            if (extraOk == null) {
                return Decision.Open(
                    "R20", g, machine, DisableReason.AUTHORITY_WITNESS_BEHIND_WAL,
                    "witness_behind_wal_unverified:1", null, exact = false, runInterruptedCompaction = false,
                )
            }
            // The extra frame is ours, authenticated, and was NEVER REPORTED
            // COMMITTED TO ANYONE — so replaying it and repairing the witness upward
            // is an exact one-step repair, not a guess. A NOTE, not a failure.
            return Decision.Open(
                "R19", g, machine, null, "witness_behind_wal_repaired:1",
                RepairWitness(
                    g, wal.present,
                    wal.tailDigest ?: return Decision.Failed("R20", "witness_behind_wal_digest_unavailable"),
                    machine.highWater(), wal.fileInstanceIdHex, snap.fileInstanceIdHex,
                ),
                exact = true, runInterruptedCompaction = true,
            )
        }

        // ── R21 ────────────────────────────────────────────────────────────
        if (wal.present > w.walFrameCount + 1L) {
            // OUTSIDE THE FAULT MODEL. Under STRICT only one append can be in
            // flight per process and the root lock serialises processes, so `> C+1`
            // cannot be produced by a correct sequence.
            replay(machine, wal.bodies)?.let { return Decision.Failed("R21", "replay:$it") }
            return Decision.Open(
                "R21", g, machine, DisableReason.AUTHORITY_WITNESS_BEHIND_WAL,
                "witness_behind_wal:${wal.present - w.walFrameCount}", null, exact = false, runInterruptedCompaction = false,
            )
        }

        // ── Wpresent == C from here. R22 / R23 / R24. ──────────────────────
        replay(machine, wal.bodies)?.let { return Decision.Failed("R24", "replay:$it") }

        // ── R22 ────────────────────────────────────────────────────────────
        // PURE INVARIANT / DEFENCE IN DEPTH — the specification proves no external fault
        // reaches this tuple. Reachable only by a key-authorized re-seal at the
        // same ordinal (which the the specification protocol never performs) or by an INTERNAL
        // BUG in the chain computation — and (b) is the honest reason the row
        // exists, because every OTHER row is about counts and AEAD and would keep
        // passing while a refactor silently broke the chain.
        if (wal.tailDigest != null && wal.tailDigest != w.walTailDigest) {
            return Decision.Open(
                "R22", g, machine, DisableReason.AUTHORITY_TAIL_DIGEST_MISMATCH,
                "tail_digest_mismatch", null, exact = false, runInterruptedCompaction = false,
            )
        }

        // ── R23 / R24 ──────────────────────────────────────────────────────
        // R23 IS THE ORDINARY RESIDUE OF A POWER LOSS MID-APPEND, and it is
        // NORMAL with allocation PERMITTED. The torn append never returned
        // COMMITTED, so `C` never included it. **No healthy committed state may be
        // durably disabled because an uncommitted tail fragment exists**.
        val row = if (wal.tornTail) "R23" else "R24"
        return Decision.Open(
            row, g, machine, null, if (wal.tornTail) wal.tornTailNote else null, null,
            exact = true, runInterruptedCompaction = true,
        )
    }

    // ───────────────────────────────────────────── the witness-absent band

    private fun decideWitnessAbsent(
        i: Inputs,
        snapsOk: Map<Long, SnapshotFacts>,
        floorValue: Long,
    ): Decision {
        val anySnapshot = i.snapshots.isNotEmpty()
        val anyWal = i.wals.values.any { it !is Artifact.Absent }

        // ── R6 ─────────────────────────────────────────────────────────────
        if (!anySnapshot && !anyWal) return Decision.Genesis("R6", floorValue)

        // ── R7 ─────────────────────────────────────────────────────────────
        // A WAL ALONE NAMES NO GENERATION AND HAS NO BASELINE; adopting it as
        // complete is the P3 shape.
        if (!anySnapshot) return Decision.Failed("R7", "wal_without_witness_or_snapshot")

        // ── R8 ─────────────────────────────────────────────────────────────
        // BENIGN, AND **BOTH SUB-CASES** MATTER: a crash between the specification G4 and G5
        // leaves snapshot-0 AND an empty wal-0 with no witness, so the condition is
        // written `WAL[0] absent-or-empty` precisely so that state is benign rather
        // than falling into R10 and disabling a session that never allocated
        // anything.
        if (snapsOk.size == 1) {
            val (gen, s) = snapsOk.entries.first()
            val wal0 = i.wals[gen]
            val walEmptyOrAbsent = when (wal0) {
                null, is Artifact.Absent -> true
                is Artifact.Ok<WalFacts> -> wal0.value.present == 0L
                is Artifact.Unreadable -> false
            }
            if (gen == 0L && isGenesisShape(s.snapshot) && walEmptyOrAbsent) {
                val machine = try {
                    SequenceAuthority.fromSnapshot(s.snapshot)
                } catch (e: LossFormatException) {
                    return Decision.Failed("R9", "no_readable_snapshot")
                }
                val walInst = (wal0 as? Artifact.Ok<WalFacts>)?.value?.fileInstanceIdHex
                return Decision.RepairWitnessBenign(
                    "R8", 0L, machine, walInst, s.fileInstanceIdHex, "witness_absent_repaired_genesis",
                )
            }
        }

        // ── R9 ─────────────────────────────────────────────────────────────
        // **NEVER GENESIS.**
        if (snapsOk.isEmpty()) return Decision.Failed("R9", "no_readable_snapshot")

        // ── R10 ────────────────────────────────────────────────────────────
        // Adopt `g*` = the highest generation whose SNAP is OK. Its authenticated
        // header generation already EQUALS its filename's, because the container read
        // refuses otherwise (`container_generation_name_mismatch`) — that check is
        // what AUTHORIZES selecting a generation from a file name at all. Without it
        // "adopt the highest-numbered snapshot" would be inference from a name a
        // restore tool controls.
        val gStar = snapsOk.keys.max()
        val s = snapsOk.getValue(gStar)
        val machine = try {
            SequenceAuthority.fromSnapshot(s.snapshot)
        } catch (e: LossFormatException) {
            return Decision.Failed("R9", "no_readable_snapshot")
        }
        val walStar = (i.wals[gStar] as? Artifact.Ok<WalFacts>)?.value
        if (walStar != null) replay(machine, walStar.bodies)?.let { return Decision.Failed("R10", "replay:$it") }
        // REPAIRING HERE WRITES OVER NOTHING (the witness is ABSENT), which is
        // the whole a product decision asymmetry: absent may be repaired, UNREADABLE may not. And
        // the session is DISABLED because a deleted witness is indistinguishable
        // from a lost one — exactly the state a rollback manufactures.
        return Decision.Open(
            "R10", gStar, machine, DisableReason.AUTHORITY_WITNESS_ABSENT,
            "witness_absent_repaired:$gStar",
            walStar?.let {
                RepairWitness(
                    gStar, it.present, it.tailDigest ?: return@let null,
                    machine.highWater(), it.fileInstanceIdHex, s.fileInstanceIdHex,
                )
            },
            exact = false, runInterruptedCompaction = false,
        )
    }

    /**
 * The genesis SHAPE — generation 0 and an empty state. The high-water may be a
 * FLOOR rather than the genesis sentinel (the specification: genesis starts from the floors
 * row, not from 0, because a session id that was pruned reopens ABOVE its
 * recorded floor — RULING A1).
 */
    public fun isGenesisShape(s: AuthorityCodec.AuthoritySnapshot): Boolean =
        s.walGeneration == 0L && s.entries.isEmpty() && s.deliveredBatches.isEmpty() &&
            s.disabled == null && s.ackThrough == -1L && !s.terminalRequested &&
            !s.acknowledgedTerminal && s.pending == null

    /** Replay in order through the SAME validate/install pair a live append uses. */
    private fun replay(machine: SequenceAuthority, bodies: List<ByteArray>): String? {
        for (raw in bodies) {
            val rec = try {
                AuthorityCodec.decodeRecord(raw)
            } catch (e: LossFormatException) {
                return "wal_decode:${e.reason}"
            }
            machine.apply(rec)?.let { return it }
        }
        return null
    }

    // ───────────────────────────── the specification interrupted compaction: IC1→IC2→IC3

    /**
 * THE INTERRUPTED-COMPACTION SUBROUTINE — **FIRST-MATCH, AND THE
 * RE-ENTRY GUARD IS EVALUATED FIRST.**
 *
 * **INVOKED ONLY FROM the specification R23/R24**, i.e. only when the extent at the
 * adopted generation `g` is EXACT. That guard is load-bearing: IC2
 * compares a fingerprint against a state REBUILT from `SNAP[g] + WAL[g]`, and
 * if `WAL[g]` is corrupt or short the rebuilt state is not the true
 * pre-compaction state and the comparison is meaningless.
 *
 * **ORDER IS EXPLICIT AND FIRST-MATCH, AND IT IS THE V3 CORRECTION.** v3
 * listed completion above the multiple-candidate refusal while CLAIMING the
 * refusal "is what bounds the re-entry to one pass" — but with completion first
 * and first-match semantics, a state holding candidates at BOTH `g+1` and `g+2`
 * matches completion, completes `g+1`, re-enters at `g+1`, and then meets the
 * same fork again. **The bound v3 asserted was not the bound its own ordering
 * produced.** the specification D10 is the row, and D6+D10 are a REQUIRED PAIR: D6 asserts
 * the refusal exists, D10 asserts it is reachable BEFORE completion.
 *
 * **THE IDS ARE NUMBERED IN EVALUATION ORDER, NOT v3's ORDER**, and
 * they were renamed from `H1`/`H2`/`H3` because those collided with the the specification
 * the specification key-domain property ids and both were cited bare — so "H1" meant two
 * different things in one document.
 */
    public sealed class IcDecision {
        public abstract val id: String

        /** IC1 — refuse, PRESERVE, delete nothing. */
        public data class Refuse(override val id: String, val reason: String) : IcDecision()

        /**
 * IC2 — COMPLETE the interrupted compaction: advance the witness to `g+1`,
 * binding the instance ids it FOUND. **THIS PATH WRITES OVER NOTHING**,
 * so it needs no part of the the specification Half-4 exception. Only after the witness
 * commits may `g`'s artifacts be retired.
 */
        public data class Complete(
            override val id: String,
            val toGeneration: Long,
            val walInstanceIdHex: String,
            val snapshotInstanceIdHex: String,
            val note: String,
        ) : IcDecision()

        /** IC3 — PRESERVE, delete nothing, proceed at `g`. */
        public data class Preserve(override val id: String, val note: String?) : IcDecision()
    }

    public fun interruptedCompaction(
        i: Inputs,
        g: Long,
        /** The state rebuilt from `SNAP[g] + WAL[g]` — the fingerprint's left-hand side. */
        currentMachine: SequenceAuthority,
    ): IcDecision {
        val next = g + 1
        val snapNext = i.snapshots[next]
        val walNext = i.wals[next]
        val candidateAtNext = (snapNext != null && snapNext !is Artifact.Absent) ||
            (walNext != null && walNext !is Artifact.Absent)

        // ── IC1_REENTRY_GUARD — FIRST ──────────────────────────────────────
        // THIS IS WHAT BOUNDS THE RE-ENTRY TO ONE PASS AND MAKES THE MACHINE
        // TERMINATE, and in v4+ it is actually in a POSITION to do so. Compaction
        // holds the root lock and its own commit point is the witness, so two
        // uncommitted compactions cannot be produced by a correct sequence; the
        // refusal NAMES the state rather than looping. It is also `E2` of the the specification
        // Half-4 exception, so the exception can never be taken over a
        // multi-generation residue.
        if (candidateAtNext) {
            val above = (i.snapshots.keys + i.wals.keys).filter { it >= g + 2 }
                .filter { k -> (i.snapshots[k] ?: Artifact.Absent) !is Artifact.Absent || (i.wals[k] ?: Artifact.Absent) !is Artifact.Absent }
            if (above.isNotEmpty()) {
                return IcDecision.Refuse(
                    "IC1_REENTRY_GUARD",
                    "multiple_uncommitted_compactions:$next,${above.min()}",
                )
            }
        }

        // ── IC2_COMPLETE — SECOND ──────────────────────────────────────────
        if (snapNext is Artifact.Ok<SnapshotFacts> && walNext is Artifact.Ok<WalFacts>) {
            val sn = snapNext.value
            val wn = walNext.value
            // An UNREADABLE or non-empty `WAL[g+1]` never reaches this subroutine at
            // all — the specification R11 refuses first, because neither can be shown to be the
            // harmless empty residue this completes.
            val emptyWal = wn.present == 0L && !wn.tornTail
            val fingerprintMatches = try {
                SequenceAuthority.fromSnapshot(sn.snapshot).logicalFingerprint() == currentMachine.logicalFingerprint()
            } catch (e: LossFormatException) {
                false
            }
            // THE FINGERPRINT IS DERIVED FROM THE REBUILT MACHINE, NOT FROM THE
            // BYTES: comparing the bytes to themselves proves the file was
            // written, not that it DECODES to the state we meant.
            //
            // F4-P0 Slice D (D3, the capture specification / RF4-17) — THE g+1 GUARD. The
            // fingerprint DELIBERATELY excludes the unresolved eviction intents
            // (a snapshot is written before the intents that follow it are
            // applied, so a widened fingerprint would fail every compaction's
            // own read-back — the specification rejects the expansion). That is exactly why
            // fingerprint equality alone must NOT complete a residue while an
            // intent is unresolved: `SNAP[g+1]` was written BEFORE those intents
            // existed (compaction refuses while one is unresolved, RF4-15), and
            // adopting it with an empty `WAL[g+1]` would DISCARD them — a
            // durable PREPARE erased by a reopen that never looked at it.
            // PRESERVE instead: the residue is kept, nothing is retired, and the
            // bounded-overwrite exception replaces it on the next compaction,
            // which can only run once the intents are settled. Evaluated after
            // the re-entry guard (IC1 still bounds the machine) and BEFORE
            // completion, so no fingerprint can walk past it.
            if (emptyWal && fingerprintMatches && currentMachine.unresolvedEvictions().isNotEmpty()) {
                return IcDecision.Preserve(
                    "IC3_PRESERVE_EVICTION_UNRESOLVED",
                    "higher_generation_artifact_preserved:$next:eviction_unresolved:${currentMachine.unresolvedEvictions().size}",
                )
            }
            if (emptyWal && fingerprintMatches) {
                return IcDecision.Complete(
                    "IC2_COMPLETE", next, wn.fileInstanceIdHex, sn.fileInstanceIdHex,
                    "compaction_completed_at_open:$next",
                )
            }
            val why = if (!emptyWal) "wal_not_empty" else "fingerprint_mismatch"
            return IcDecision.Preserve("IC3_PRESERVE", "higher_generation_artifact_preserved:$next:$why")
        }

        // ── IC3_PRESERVE — THIRD ───────────────────────────────────────────
        // A SNAPSHOT-`g+1` WITH NO WAL-`g+1` IS IC3, NOT IC2, DELIBERATELY. It
        // is the residue of a crash between the specification step 3 and step 4. Completing it
        // would mean CREATING a file during a READ path — and R14 refuses to
        // recreate a missing WAL for exactly that reason. Preserving costs nothing,
        // because nothing was retired: the witness still names `g`, `SNAP[g]` and
        // `WAL[g]` are intact, and no record was ever acknowledged at `g+1` because
        // the witness never named it.
        if (!candidateAtNext) {
            val other = (i.snapshots.keys + i.wals.keys).filter { it > g }
                .filter { k -> (i.snapshots[k] ?: Artifact.Absent) !is Artifact.Absent || (i.wals[k] ?: Artifact.Absent) !is Artifact.Absent }
            if (other.isEmpty()) return IcDecision.Preserve("IC3_PRESERVE", null)
            return IcDecision.Preserve("IC3_PRESERVE", "higher_generation_artifact_preserved:${other.min()}:not_adjacent")
        }
        val why = when {
            snapNext is Artifact.Unreadable -> "snapshot_unreadable"
            snapNext == null || snapNext is Artifact.Absent -> "snapshot_absent"
            else -> "wal_absent"
        }
        return IcDecision.Preserve("IC3_PRESERVE", "higher_generation_artifact_preserved:$next:$why")
    }
}

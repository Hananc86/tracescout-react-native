package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo

/**
 * The F4-P0 operational constants, and the ones that are deliberately **UNSET**.
 *
 * ## Why UNSET is a value
 *
 * Three of these bound a DESTRUCTIVE operation, and every one of them was
 * previously chosen by extrapolating from a measurement of a different
 * operation. A number that looks derived but is not is worse than an absent
 * one, because nothing downstream can tell the difference. So the absent ones
 * are absent in a machine-visible way:
 *
 * * [UNSET] is a **distinct sentinel**, not `0` and not `-1`. A constant that is
 * merely small must never read as measured.
 * * A production path that needs an UNSET constant **refuses the destructive
 * operation**. It never falls back to unbounded behaviour — that is the whole
 * point of the sentinel.
 * * `QueueOrderTest` A17 FAILS while any of them is UNSET, so F4 stage
 * acceptance is impossible until they are measured. The gate is a test, not a
 * sentence in a document. (All five were measured by M1–M5 and RATIFIED on
 * 2026-10-01 — see the ratified block below; the sentinel and the gate stay,
 * so an un-ratified value can be put back to UNSET and production refuses
 * again.)
 *
 * ## Provisional values
 *
 * Tests and development builds may inject explicit values. That is what
 * [DEV_PROVISIONAL] names exist for, and they are never read by a production
 * path — a source pin asserts it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object F4Constants {

    /**
 * The absent-value sentinel.
 *
 * `Long.MIN_VALUE` is chosen precisely because no arithmetic on it produces
 * a plausible bound: a budget comparison against it cannot accidentally
 * succeed the way a comparison against `0` or `-1` can.
 */
    const val UNSET: Long = Long.MIN_VALUE

    // ── DERIVED: these ARE known, from constants in the tree ─────────────────

    /**
 * The absolute storage maximum for one bracket's victim set.
 *
 * ```
 * WAL_MAX_BYTES 14080 * 1024 = 14,417,920 EncryptedAuthorityStore
 * COMPACT_AT_BYTES 3520 * 1024 = 3,604,480
 * headroom = 10,813,440
 * per victim 2 * onDiskFrameBytes(RUNG1=1024)
 * = 2 * (1024+4+12+16) = 2 * 1056 = 2,112 PREPARE + SETTLE frame
 * 10,813,440 / 2,112 = 5,120 exact, no rounding
 * ```
 *
 * This is an UPPER BOUND the storage cannot exceed. It is NOT a safe
 * operational bracket size, and it must never be used as one.
 */
    const val VICTIMS_STORAGE_MAX: Long = 5_120L

    /**
 * The hard upper bound on distinct protected record digests.
 *
 * A digest can only be protected while its record is in the queue, and
 * `DEFAULT_MAX_BYTES / smallest encoded record = 8,388,608 / 33 = 254,200`.
 * So protection capacity is bounded above by the queue itself; what is
 * unmeasured is the serialized-state cost and its write latency.
 */
    const val PROTECTED_DIGESTS_REPRESENTABLE_MAX: Long = 254_200L

    // ── RATIFIED 2026-10-01 from M1–M5 (F4-P0 certification; a product decision) ────
    //
    // Measured on BOTH emulators (API 26 + API 35), worst p100 across both,
    // ×2.0 safety margin, then the smallest clean bound above the margined
    // value. The raw samples and the derivation live in the SEALED stage
    // package an internal record (measurements/,
    // CONSTANTS.md); `F4RatifiedConstantsTest` pins the arithmetic so a value
    // cannot drift away from its derivation without a test naming it.
    //
    // These are conservative STAGE-ACCEPTANCE bounds, revisitable by later
    // measurement (a physical device would tighten them, never loosen a
    // ruling). They are NOT product semantics. The UNSET machinery stays:
    // a future re-measurement that cannot justify a value puts it back to
    // UNSET and production refuses again.

    /**
 * Total fence hold for one eviction bracket.
 * p100(M5c total bracket hold) = 28,715 ms → ×2 = 57,430 → 60,000 (ruled).
 * The FIXED cost of a full-queue bracket (p100 17,022 ms, ×2 = 34,179)
 * is a function of QUEUE size, not victims — a budget below it refuses
 * every full-queue bracket. 60 s clears it.
 */
    const val BRACKET_BUDGET_MS: Long = 60_000L

    /**
 * Victims per bracket, operationally. `min(storage_max, this)`.
 * `floor((BRACKET_BUDGET_MS − fixed) / per_victim)` with
 * fixed = (p100(M3) 17,022.3 + p100(M4@1000) 67.0) × 2 = 34,178.6 ms and
 * per_victim = (p100(M1) 457.4 + p100(M2) 475.7) × 2 = 1,866.1 ms
 * = floor(25,821.4 / 1,866.1) = 13. The storage maximum (5,120) does not
 * bind. A bracket needing more than 13 victims is REFUSED typed
 * (`eviction_refused_victim_ceiling`), never truncated.
 */
    const val VICTIMS_OPERATIONAL_MAX: Long = 13L

    /**
 * Distinct protected digests. M4 at 1,000 digests: admit-write p100
 * 67 ms → ×2 = 134 ms, under the 150 ms admission-latency acceptance
 * budget. NOT extrapolated beyond the measured point: 1,000 is the
 * measured capacity, not a derived one. At capacity a NEW distinct digest
 * is refused; existing protection is never deleted; a ref to an
 * already-protected digest consumes no capacity.
 */
    const val PROTECTION_CAPACITY_MAX: Long = 1_000L

    /**
 * The crash thread's fence-acquisition ACCEPTANCE ceiling.
 * p100(crash path, same-process) = 30.58 ms → ×2 = 61.16 → 65 (ruled, with
 * explicit headroom; NOT 60 with an implicit 1.96 margin). The runtime
 * policy is [CrashAcquisition] (worst case 60 ms) and may finish faster —
 * this is the ceiling an acceptance run is judged against, not an
 * instruction to wait.
 */
    const val CRASH_FENCE_BUDGET_MS: Long = 65L

    /**
 * Teardown's fence-acquisition ACCEPTANCE ceiling.
 * Covers the longest legitimate hold with margin: bracket p100 28,715 ms
 * (×2 = 57,430) and recovery p100 22,588 ms (×2 = 45,177) → 60,000.
 * Typed Pending / fail-closed behaviour on a refused acquisition is
 * unchanged; the teardown path keeps its ordinary acquisition policy.
 */
    const val TEARDOWN_FENCE_BUDGET_MS: Long = 60_000L

    /** Every gated constant, so the gate cannot be quietly shrunk. */
    @JvmStatic
    fun gatedConstants(): Map<String, Long> = linkedMapOf(
        "BRACKET_BUDGET_MS" to BRACKET_BUDGET_MS,
        "VICTIMS_OPERATIONAL_MAX" to VICTIMS_OPERATIONAL_MAX,
        "PROTECTION_CAPACITY_MAX" to PROTECTION_CAPACITY_MAX,
        "CRASH_FENCE_BUDGET_MS" to CRASH_FENCE_BUDGET_MS,
        "TEARDOWN_FENCE_BUDGET_MS" to TEARDOWN_FENCE_BUDGET_MS,
    )

    /** The names that are still UNSET. Empty ⇒ the stage gate can pass. */
    @JvmStatic
    fun unsetConstantNames(): List<String> =
        gatedConstants().filterValues { it == UNSET }.keys.toList()

    @JvmStatic
    fun isUnset(v: Long): Boolean = v == UNSET

    /**
 * The bound the delivery pipeline ADMITS protection against (Slice B, an open product question).
 *
 * NOT A DESTRUCTIVE BOUND, so the UNSET rule does not turn it into a
 * refusal of every drain. Admitting protection is what makes a POST
 * possible; refusing it while the operational ceiling is unmeasured would
 * refuse delivery itself, which is strictly worse than the parent commit.
 * So while [PROTECTION_CAPACITY_MAX] is UNSET the admission ceiling is the
 * DERIVED hard bound [PROTECTED_DIGESTS_REPRESENTABLE_MAX] — the queue
 * cannot hold more records than that, so protection cannot need more
 * entries — and once the operational ceiling is measured it is the
 * smaller of the two. The operational ceiling itself stays UNSET and
 * keeps blocking F4 stage acceptance; nothing here narrows it.
 */
    @JvmStatic
    fun protectionAdmissionCeiling(): Long =
        if (isUnset(PROTECTION_CAPACITY_MAX)) PROTECTED_DIGESTS_REPRESENTABLE_MAX
        else minOf(PROTECTION_CAPACITY_MAX, PROTECTED_DIGESTS_REPRESENTABLE_MAX)

    /**
 * The victim ceiling the eviction bracket plans against (Slice C, C1/C10).
 *
 * A DESTRUCTIVE BOUND, SO THE UNSET RULE APPLIES IN FULL: it is
 * `min(storage maximum, operational maximum)` and the operational half
 * goes through [F4StageAcceptanceGate.requireSet], which THROWS while
 * [VICTIMS_OPERATIONAL_MAX] is UNSET. The bracket turns that into a typed
 * refusal of the whole bracket — zero mutation, zero intents — so until
 * M1/M2 are measured the production bracket never runs with an unbounded
 * victim set: an append at capacity is refused and accounted, never made
 * room for. A test injects an explicit provisional [operational] value;
 * no production path passes anything but the default.
 */
    @JvmStatic
    @JvmOverloads
    fun effectiveVictimCeiling(operational: Long = VICTIMS_OPERATIONAL_MAX): Long =
        minOf(VICTIMS_STORAGE_MAX, F4StageAcceptanceGate.requireSet("VICTIMS_OPERATIONAL_MAX", operational))

    /**
 * True when F4 stage acceptance is impossible because a gated constant is
 * still UNSET. Read by the acceptance gate test; **not** by any production
 * path, which refuses per-operation instead.
 */
    @JvmStatic
    fun stageAcceptancePossible(): Boolean = unsetConstantNames().isEmpty()

    // ── APPROVED by the lead, recorded as a safety bound ─────────────────────

    /**
 * Consecutive drains that POSTED and removed nothing before the pipeline
 * refuses to post again.
 *
 * Approved as a **P0 implementation safety bound, not product semantics**,
 * and revisitable at F4 stage review. The sixth attempt refuses BEFORE the
 * request is built. A server `duplicate` is an acknowledgement, not local
 * progress: only a committed removal resets this.
 */
    const val MAX_NO_PROGRESS_DRAINS: Int = 5

    // ── THE ONE PROVISIONAL CRASH-ACQUISITION POLICY ─────────────────────────

    /**
 * How long the dying thread of an uncaught exception tries for each lock
 * layer before it gives up and ACCOUNTS the loss of the crash record.
 *
 * ONE SOURCE, TWO LAYERS. Before this object the crash budget lived as
 * THREE unrelated literals — `QueueRegionLock` said 30 ms, `QueueFileFence`
 * said 3 × 10 ms, and a `DEV_PROVISIONAL.CRASH_FENCE_BUDGET_MS = 25` that
 * nothing read said something else again. Three numbers for one policy is
 * how a tuning lands in one layer and not the other. Both layers now READ
 * this object (`QueueOrderTest` A50 pins that no numeric crash-policy
 * literal exists anywhere else), and the crash rows derive their expected
 * bounds from it, so changing a value here moves the behaviour AND the
 * tests together.
 *
 * PROVISIONAL ACQUISITION POLICY, NOT AN ACCEPTANCE-GATED OPERATIONAL
 * CONSTANT. [CRASH_FENCE_BUDGET_MS] above is the MEASURED ceiling M5(b)
 * will produce and stays UNSET until then; failing a crash append closed
 * on an UNSET value would lose EVERY crash record, which is strictly worse
 * than the parent commit. This object is the ruled interim: a short,
 * bounded attempt, then a typed refusal. The two layers are deliberately
 * NOT forced to one scalar — a JVM lock and an OS file lock are waited on
 * differently — but they are one policy, defined once.
 */
    object CrashAcquisition {
        /** Region layer: one `tryLock(timeout)` against this process's own threads. */
        const val REGION_BUDGET_MS: Long = 30L

        /** Fence layer: attempts against another PROCESS's OS lock… */
        const val FENCE_ATTEMPTS: Int = 3

        /** …with this sleep between attempts. */
        const val FENCE_ATTEMPT_DELAY_MS: Long = 10L

        /**
 * The worst case the policy implies for the whole crash path, derived —
 * region budget plus every fence attempt's delay. Tests bound the
 * measured crash wait against THIS, so the bound moves with the policy.
 */
        const val WORST_CASE_MS: Long = REGION_BUDGET_MS + FENCE_ATTEMPTS * FENCE_ATTEMPT_DELAY_MS
    }

    // ── development-only provisional values ──────────────────────────────────

    /**
 * NEVER READ BY A PRODUCTION PATH. These exist so a slice can be written
 * and its boundary tests can run before the measurement exists. A source
 * pin asserts no production file references them.
 */
    object DEV_PROVISIONAL {
        const val BRACKET_BUDGET_MS: Long = 1_000L
        const val VICTIMS_OPERATIONAL_MAX: Long = 128L
        const val PROTECTION_CAPACITY_MAX: Long = 1_024L
        // NO CRASH VALUE HERE, ON PURPOSE. The crash acquisition policy has
        // exactly one home, [CrashAcquisition]; a second number under this
        // object contradicted it while being read by nothing.
        const val TEARDOWN_FENCE_BUDGET_MS: Long = 3_000L
    }
}

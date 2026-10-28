package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo

/**
 * F4-P0 Slice C (C1) — the PURE, deterministic eviction planner.
 *
 * Given the fenced queue image (as [Candidate]s, in queue order), the Slice B
 * protected digest set, the incoming record's frame size, the store's byte cap
 * and the effective victim ceiling, it answers the MINIMUM oldest-first victim
 * set that makes the incoming record fit — or a typed refusal. It reads no
 * file, holds no lock, knows no session and writes nothing; every decision it
 * takes is a function of its arguments, so two calls with the same arguments
 * answer the same plan, on any thread, in any process.
 *
 * ## The rules (each a row in `EvictionPlannerTest`)
 *
 * * **protected and in-flight records are excluded** — a digest in
 * [plan]'s `protected` set is NEVER a victim, whatever the candidate's own
 * `eligible` flag says (C7: a Slice-B protected digest may never appear in
 * the victim set; the exclusion is structural here, not a caller's duty);
 * * **unattributable records are excluded** — a candidate the bracket could
 * not attribute (unparseable, unowned, not ADMITTED, authority fenced,
 * door not Ready) arrives with `eligible = false` and is skipped;
 * * **deterministic oldest-first over the eligible remainder** — victims are
 * taken in queue order, oldest first, never by size, never by session;
 * * **the minimum set** — the walk stops at the first eligible record whose
 * removal makes the plan fit;
 * * **kept order preserved** — `keepIndices` is the ascending complement;
 * * **insufficient eligible victims ⇒ typed refusal, no plan** — and NO
 * substitute outside the eligibility rules;
 * * **above the ceiling ⇒ typed refusal, no plan** — never a truncated set
 * (RF4-14): a bracket that cannot fit under its ceiling does not happen;
 * * **a record that would not fit an EMPTY store ⇒ typed refusal** — no
 * victim set can help, and destroying everything to try would be worse.
 *
 * The byte arithmetic is `EncryptedEventStore.encodedSize`'s — the same
 * formula `compactAndAppend` enforces — so a plan that fits here fits there.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object EvictionPlanner {

    /** One queue record as the planner sees it: position, identity, cost, attributability. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class Candidate(
        /** Position in the queue image. */
        val index: Int,
        /** sha256 hex of the record bytes. */
        val digest: String,
        /** On-disk frame bytes of this record (`EncryptedEventStore.frameBytes`). */
        val frameBytes: Long,
        /** The owning session, when the record could be attributed. */
        val sessionId: String?,
        /** The owned sequence, when the record could be attributed. */
        val sequence: Long?,
        /** True ONLY when the bracket established ownership and an ADMITTED, unfenced entry. */
        val eligible: Boolean,
        /** Why not, when not. Diagnostics only. */
        val ineligibleWhy: String? = null,
    )

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Plan {
        /**
 * The victims, in queue order, and the ascending keep plan. `victims`
 * is empty when the record already fits — the caller then has no
 * bracket to run.
 */
        data class Ok(val victims: List<Candidate>, val keepIndices: List<Int>) : Plan()

        /** Zero mutation. [code] is the typed refusal; the two numbers are diagnostics. */
        data class Refused(val code: String, val neededBytes: Long, val freeableBytes: Long) : Plan()
    }

    const val REFUSED_RECORD_EXCEEDS_CAPACITY: String = "record_exceeds_capacity"
    const val REFUSED_INSUFFICIENT_ELIGIBLE: String = "eviction_refused_insufficient_eligible"
    const val REFUSED_VICTIM_CEILING: String = "eviction_refused_victim_ceiling"

    /**
 * @param candidates the queue image, in queue order, indices `0 until size`.
 * @param protected the Slice B protected digest set — a hard exclusion.
 * @param incomingFrameBytes the incoming record's on-disk frame size.
 * @param capacityBytes the store's byte cap.
 * @param ceiling the effective victim ceiling (already resolved; an UNSET
 * ceiling is refused BEFORE this function by the bracket).
 */
    @JvmStatic
    fun plan(
        candidates: List<Candidate>,
        protected: Set<String>,
        incomingFrameBytes: Long,
        capacityBytes: Long,
        ceiling: Long,
    ): Plan {
        require(candidates.withIndex().all { (i, c) -> c.index == i }) { "candidates must be the queue image in order" }
        if (incomingFrameBytes > capacityBytes) {
            return Plan.Refused(REFUSED_RECORD_EXCEEDS_CAPACITY, incomingFrameBytes - capacityBytes, 0L)
        }
        val total = candidates.sumOf { it.frameBytes }
        val needed = total + incomingFrameBytes - capacityBytes
        if (needed <= 0L) return Plan.Ok(emptyList(), candidates.map { it.index })

        val victims = ArrayList<Candidate>()
        var freed = 0L
        var freeable = 0L
        for (c in candidates) {
            val usable = c.eligible && c.digest !in protected
            if (!usable) continue
            freeable += c.frameBytes
            if (freed >= needed) continue   // already enough: only tally what COULD be freed
            victims.add(c)
            freed += c.frameBytes
        }
        if (freed < needed) return Plan.Refused(REFUSED_INSUFFICIENT_ELIGIBLE, needed, freeable)
        if (victims.size.toLong() > ceiling) return Plan.Refused(REFUSED_VICTIM_CEILING, needed, freed)
        val victimIdx = victims.map { it.index }.toHashSet()
        return Plan.Ok(victims, candidates.map { it.index }.filterNot { it in victimIdx })
    }
}

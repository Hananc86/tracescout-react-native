package com.tracescout.mobile.internal.queue

/**
 * THE ONE RULE that turns a durable park manifest entry into the exact set of
 * sequences a `LOST(PARKED)` settlement may name (liveness B3, blocker 5 of the
 * ccdbec35 reviews).
 *
 * A manifest entry carries `firstSequence` / `lastSequence` as DECIMAL STRINGS
 * (int64 precision — a JS Number would round exactly here, where a rounding
 * error becomes a wrong loss) and an integer `recordCount`. The range is
 * derivable from the manifest ALONE only when
 *
 * first + recordCount - 1 == last
 *
 * with a positive count, overflow-safe arithmetic and digits-only inputs.
 * Anything else is a REFUSAL: a guessed set of sequences settled LOST is a
 * fabricated loss.
 *
 * IT IS PURE AND SEPARATELY TESTED SO THAT THE PREDICATE IS KNOWN TO BE
 * SATISFIABLE. A guard that can only reject is indistinguishable from one that
 * works, until the day a real parked batch is never settled and nobody can say
 * why. `ParkedRangeRuleTest` drives the legitimate shape to PASS and every
 * illegitimate one to a NAMED refusal.
 */
internal object ParkedRangeRule {

    sealed class Verdict {
        data class Range(val first: Long, val last: Long, val count: Int) : Verdict()
        data class Refused(val reason: String) : Verdict()
    }

    /**
 * An upper bound on a count this rule will expand into a list. The live
 * bound is `ChunkBounds.MAX_RECORDS_PER_CHUNK` (64); a manifest written
 * BEFORE the byte/count bounds existed could carry a larger legitimate
 * batch, so the bound here is generous — it exists only so a corrupt count
 * cannot make the settlement allocate an unbounded list.
 */
    const val MAX_COUNT: Int = 1_000_000

    /** Digits only — no sign, no whitespace, no exponent, no leading `+`. */
    private val DECIMAL = Regex("^[0-9]{1,19}$")

    fun contiguous(firstSequence: String, lastSequence: String, recordCount: Int): Verdict {
        if (recordCount <= 0) return Verdict.Refused("count_not_positive")
        if (recordCount > MAX_COUNT) return Verdict.Refused("count_implausible")
        if (!DECIMAL.matches(firstSequence) || !DECIMAL.matches(lastSequence)) return Verdict.Refused("range_not_decimal")
        // 19 digits can still exceed Long.MAX_VALUE; toLongOrNull refuses that.
        val first = firstSequence.toLongOrNull() ?: return Verdict.Refused("range_not_decimal")
        val last = lastSequence.toLongOrNull() ?: return Verdict.Refused("range_not_decimal")
        val span = (recordCount - 1).toLong()
        if (first > Long.MAX_VALUE - span) return Verdict.Refused("range_overflow")
        if (first + span != last) return Verdict.Refused("range_not_contiguous")
        return Verdict.Range(first, last, recordCount)
    }
}

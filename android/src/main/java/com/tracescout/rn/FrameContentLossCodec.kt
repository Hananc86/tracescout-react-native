package com.tracescout.rn

/**
 * The FCL wire codec and the ONE home of every bound the format depends on.
 * rn-core, pure, step 4/7.
 *
 * **ON-DISK MAGIC `FCL1`, `version = 1` — THE FIRST ON-DISK FORMAT, WHICH HAS
 * NEVER SHIPPED.** The architecture revision has a different generation number,
 * and the two must not be confused: **no migration has been skipped.** A reader
 * who sees "v1" here and remembers an earlier architecture draft would otherwise
 * conclude a format was retired without a migration. There was never a v0 on
 * disk to migrate from.
 *
 * **EVERY BOUND IS DERIVED, NOT WRITTEN DOWN TWICE.** [ROWS_MAX] is checked
 * against [derivedRowsMax], which is COMPUTED from the byte budget and the field
 * maxima — so moving any constant re-derives the bound rather than invalidating
 * a comment. [CAUSE_FLOOR] is `ROWS_MAX / 2`, never a literal. A test recomputes
 * the always-fit proof from these constants rather than asserting the numbers.
 *
 * **THERE IS NO UNBOUNDED FIELD.** Every numeric field is bounded by its
 * Kotlin type and encoded as canonical decimal; `sessionId` is length-validated
 * before it can enter a row; `rowCount <= ROWS_MAX`; `reasonCount` is fixed by
 * the enum; `pending` is 0 or 1 **by type**. That is what makes the always-fit
 * proof a proof rather than an expectation.
 *
 * **`sumOf` IS BANNED ON COUNTER FIELDS AND [checkedSum] IS THE ONE
 * SUMMATION HOME.** `sumOf` WRAPS on overflow — silently, to a negative number,
 * in a carrier whose whole purpose is to be honest about counts. A wrapped total
 * is worse than a refused one: it is a confident wrong answer.
 */
object FrameContentLossCodec {

    /** The on-disk magic. Never the architecture revision. */
    const val MAGIC: String = "FCL1"

    /** The on-disk schema version. */
    const val VERSION: Int = 1

    /** The field delimiter. A NUL cannot occur in a canonical decimal, a validated session id or a hex digest. */
    const val DELIM: Char = '\u0000'

    // ── the frozen arithmetic ────────────────────────────────────────────────
    //
    // Each term is `maximum encoded characters + 1` for the delimiter.

    /** Counted refusal reasons — the positional vector's length. */
    const val R: Int = 13

    /** `cause(17+1) + sessionId(128+1) + occurrences(19+1)`. */
    const val ROW_MAX: Int = 167

    /** `cause(18) + preFingerprint(65) + expectedPostFingerprint(65) + preCount(11) + sessionId(129)`. */
    const val PENDING_MAX: Int = 288

    /**
 * `MAGIC(5) + version(11) + 7 Longs(140) + 3 booleans(6) + reasonCount(3)
 * + 13 Longs(260) + rowCount(3) + pendingPresent(2) + pending(288)`.
 */
    const val FIXED_MAX: Int = 718

    /** The ledger store's declared ceiling. */
    const val LEDGER_MAX_BYTES: Int = 65_536

    /** The crash-tolerance record count the always-fit proof is taken over. */
    const val LEDGER_RECORDS_HARD_MAX: Int = 8

    /** GCM frame overhead: `length prefix(4) + IV(12) + tag(16)`. */
    const val FRAME_OVERHEAD: Int = 32

    /**
 * Attributed rows. **The largest power of two at or below [derivedRowsMax]**
 * a power of two so [CAUSE_FLOOR] is exact.
 */
    const val ROWS_MAX: Int = 32

    /** DERIVED, never a literal. Neither cause can be pushed below this many attributed rows. */
    const val CAUSE_FLOOR: Int = ROWS_MAX / 2

    /** The per-record byte budget: the store ceiling divided by the crash-tolerance record count. */
    fun perRecordBudget(maxBytes: Int = LEDGER_MAX_BYTES, hardMax: Int = LEDGER_RECORDS_HARD_MAX): Int =
        maxBytes / hardMax

    /**
 * How many rows actually fit the budget — **computed, so a moved constant
 * re-derives the bound.** [ROWS_MAX] must be `<=` this.
 */
    fun derivedRowsMax(
        maxBytes: Int = LEDGER_MAX_BYTES,
        hardMax: Int = LEDGER_RECORDS_HARD_MAX,
        fixedMax: Int = FIXED_MAX,
        rowMax: Int = ROW_MAX,
    ): Int = (perRecordBudget(maxBytes, hardMax) - FRAME_OVERHEAD - fixedMax) / rowMax

    /** Worst encoded state: the fixed block plus a full row table. */
    fun worstStateBytes(rows: Int = ROWS_MAX): Int = FIXED_MAX + rows * ROW_MAX

    /** Worst frame on disk. */
    fun worstFrameBytes(rows: Int = ROWS_MAX): Int = worstStateBytes(rows) + FRAME_OVERHEAD

    /** Worst whole file at the crash-tolerance record count. */
    fun worstFileBytes(
        rows: Int = ROWS_MAX,
        hardMax: Int = LEDGER_RECORDS_HARD_MAX,
    ): Int = worstFrameBytes(rows) * hardMax

    // ── checked arithmetic ───────────────────────────────────────────────────

    /** Why a counter operation refused. [COUNTER_OVERFLOW] is the authority's own name. */
    enum class CounterRefusal { COUNTER_OVERFLOW, NEGATIVE_INPUT }

    /** A counter result that can REFUSE. Never wraps, never saturates. */
    sealed class Counted {
        data class Ok(val value: Long) : Counted()
        data class Refused(val reason: CounterRefusal) : Counted()
    }

    /**
 * THE ONE SUMMATION HOME. Pre-checked addition — `cur > Long.MAX_VALUE - n`
 * so an overflow REFUSES rather than wrapping.
 *
 * **A negative input is refused, not clamped.** Every counter in this
 * carrier is an occurrence count; a negative one is a defect upstream, and
 * silently treating it as zero would hide that defect inside a total that
 * still looked plausible.
 */
    fun checkedSum(values: List<Long>): Counted {
        var acc = 0L
        for (v in values) {
            if (v < 0L) return Counted.Refused(CounterRefusal.NEGATIVE_INPUT)
            if (acc > Long.MAX_VALUE - v) return Counted.Refused(CounterRefusal.COUNTER_OVERFLOW)
            acc += v
        }
        return Counted.Ok(acc)
    }

    /** Checked accumulation of [n] onto [cur]. The same rule, for a single step. */
    fun checkedAdd(cur: Long, n: Long): Counted = checkedSum(listOf(cur, n))

    // ── encode / decode ──────────────────────────────────────────────────────

    /** Why a decode refused. Each is a REFUSAL — never a silently repaired read. */
    enum class CodecRefusal {
        MAGIC_MISMATCH,
        VERSION_UNSUPPORTED,
        TRUNCATED,
        TRAILING_BYTES,
        NEGATIVE_NUMERIC_FIELD,
        NOT_A_NUMBER,
        REASON_COUNT_MISMATCH,
        ROW_COUNT_EXCEEDS_MAX,
        UNKNOWN_CAUSE,
        BAD_BOOLEAN,
        BAD_PENDING_FLAG,
        FIELD_TOO_LONG,
    }

    sealed class Decoded {
        data class Ok(val state: FrameContentLossState) : Decoded()
        data class Refused(val reason: CodecRefusal) : Decoded()
    }

    /**
 * **A FIELD CONTAINING `-` IS REFUSED BEFORE IT IS PARSED.** The encoder
 * cannot produce one (every numeric field is a non-negative count), so a `-`
 * on disk means the bytes are not what this codec wrote. Refusing on the
 * CHARACTER rather than on the parsed value is what makes
 * `Long.MIN_VALUE`'s unnegatable magnitude unrepresentable rather than
 * merely unlikely.
 */
    private fun nonNegative(raw: String): Long? {
        if (raw.isEmpty() || raw.contains('-')) return null
        return raw.toLongOrNull()?.takeIf { it >= 0L }
    }

    private fun bool(raw: String): Boolean? = when (raw) {
        "0" -> false
        "1" -> true
        else -> null
    }

    fun encode(s: FrameContentLossState): String {
        val b = StringBuilder()
        fun put(v: Any) { b.append(v.toString()).append(DELIM) }
        put(MAGIC)
        put(s.version)
        put(s.g9Occurrences)
        put(s.g6Collapsed)
        put(s.g10Collapsed)
        put(s.g6SessionsCollapsedUpperBound)
        put(s.g10SessionsCollapsedUpperBound)
        put(s.g9IndeterminateDisclosed)
        put(s.g10IndeterminateDisclosed)
        put(if (s.priorStateLost) 1 else 0)
        put(if (s.refusalRecordingIncomplete) 1 else 0)
        put(if (s.exclusionRefusalIncomplete) 1 else 0)
        put(s.refusalCounts.size)
        for (c in s.refusalCounts) put(c)
        put(s.rows.size)
        for (r in s.rows) { put(r.cause.name); put(r.sessionId); put(r.occurrences) }
        val p = s.pending
        if (p == null) {
            put(0)
        } else {
            put(1)
            put(p.cause.name)
            put(p.preFingerprint)
            put(p.preCount)
            put(p.expectedPostFingerprint)
            // An empty string IS null: a validated session id can never be empty,
            // so the two are unambiguous and no extra presence flag is needed.
            put(p.sessionId ?: "")
        }
        return b.toString()
    }

    fun decode(text: String): Decoded {
        if (text.isEmpty()) return Decoded.Refused(CodecRefusal.TRUNCATED)
        // The encoder terminates EVERY field, so the split's last element is the
        // empty tail. Anything else means the bytes were cut mid-field.
        val parts = text.split(DELIM)
        if (parts.isEmpty() || parts.last().isNotEmpty()) return Decoded.Refused(CodecRefusal.TRUNCATED)
        val f = parts.dropLast(1)
        var i = 0
        fun next(): String? = if (i < f.size) f[i++] else null

        val magic = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
        if (magic != MAGIC) return Decoded.Refused(CodecRefusal.MAGIC_MISMATCH)

        val verRaw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
        val ver = nonNegative(verRaw) ?: return Decoded.Refused(
            if (verRaw.contains('-')) CodecRefusal.NEGATIVE_NUMERIC_FIELD else CodecRefusal.NOT_A_NUMBER
        )
        // REFUSE, never migrate and never read anyway. An unsupported version
        // is bytes this codec does not understand; reading them as v1 is how a
        // field means two different things in two different writers.
        //
        // COMPARED AS A `Long`, AND THAT IS THE WHOLE POINT. `nonNegative`
        // admits anything up to `Long.MAX_VALUE`; `toInt` keeps only the low
        // 32 bits, so `4294967297` truncated to `1` and PASSED the check whose
        // own comment two lines up says an unsupported version is never read
        // anyway. Narrowing before validating is how a refusal stops refusing
        // (CodeRabbit an earlier change finding [2]).
        if (ver != VERSION.toLong()) return Decoded.Refused(CodecRefusal.VERSION_UNSUPPORTED)

        val longs = ArrayList<Long>(7)
        repeat(7) {
            val raw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
            val v = nonNegative(raw) ?: return Decoded.Refused(
                if (raw.contains('-')) CodecRefusal.NEGATIVE_NUMERIC_FIELD else CodecRefusal.NOT_A_NUMBER
            )
            longs.add(v)
        }

        val bools = ArrayList<Boolean>(3)
        repeat(3) {
            val raw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
            bools.add(bool(raw) ?: return Decoded.Refused(CodecRefusal.BAD_BOOLEAN))
        }

        val reasonCountRaw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
        val reasonCount = nonNegative(reasonCountRaw) ?: return Decoded.Refused(
            if (reasonCountRaw.contains('-')) CodecRefusal.NEGATIVE_NUMERIC_FIELD else CodecRefusal.NOT_A_NUMBER
        )
        // The vector is POSITIONAL over a CLOSED enum, so its length is fixed by
        // the enum. A different length is a different vocabulary, not a shorter list.
        // IN `Long`, for the same reason as the version above: `R + 2^32`
        // truncates to `R` and would admit a vocabulary this codec cannot read.
        if (reasonCount != R.toLong()) return Decoded.Refused(CodecRefusal.REASON_COUNT_MISMATCH)
        val counts = ArrayList<Long>(R)
        repeat(R) {
            val raw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
            val v = nonNegative(raw) ?: return Decoded.Refused(
                if (raw.contains('-')) CodecRefusal.NEGATIVE_NUMERIC_FIELD else CodecRefusal.NOT_A_NUMBER
            )
            counts.add(v)
        }

        val rowCountRaw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
        val rowCount = nonNegative(rowCountRaw) ?: return Decoded.Refused(
            if (rowCountRaw.contains('-')) CodecRefusal.NEGATIVE_NUMERIC_FIELD else CodecRefusal.NOT_A_NUMBER
        )
        if (rowCount > ROWS_MAX.toLong()) return Decoded.Refused(CodecRefusal.ROW_COUNT_EXCEEDS_MAX)
        val rows = ArrayList<FrameLossRow>(rowCount.toInt())
        repeat(rowCount.toInt()) {
            val causeRaw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
            val cause = causeOf(causeRaw) ?: return Decoded.Refused(CodecRefusal.UNKNOWN_CAUSE)
            val sid = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
            if (sid.length > MAX_SESSION_ID) return Decoded.Refused(CodecRefusal.FIELD_TOO_LONG)
            val occRaw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
            val occ = nonNegative(occRaw) ?: return Decoded.Refused(
                if (occRaw.contains('-')) CodecRefusal.NEGATIVE_NUMERIC_FIELD else CodecRefusal.NOT_A_NUMBER
            )
            rows.add(FrameLossRow(cause, sid, occ))
        }

        val pendingFlag = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
        var pending: FrameLossPending? = null
        when (pendingFlag) {
            "0" -> Unit
            "1" -> {
                val causeRaw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
                val cause = causeOf(causeRaw) ?: return Decoded.Refused(CodecRefusal.UNKNOWN_CAUSE)
                val preFp = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
                if (preFp.length > MAX_FINGERPRINT) return Decoded.Refused(CodecRefusal.FIELD_TOO_LONG)
                val preCountRaw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
                val preCount = nonNegative(preCountRaw) ?: return Decoded.Refused(
                    if (preCountRaw.contains('-')) CodecRefusal.NEGATIVE_NUMERIC_FIELD
                    else CodecRefusal.NOT_A_NUMBER
                )
                // THE ONE THAT SURVIVES INTO STORED STATE. `preCount` is
                // written into `FrameLossPending.preCount`, an `Int`, and
                // `decide` reads that value — so `toInt` on an out-of-range
                // `Long` does not merely mis-compare, it WRAPS: `2^32` becomes
                // `0` and `Int.MAX_VALUE + 1` becomes `Int.MIN_VALUE`, which is
                // a NEGATIVE count in the one field `nonNegative` exists to
                // keep non-negative. Refused here, before the narrowing.
                //
                // `FIELD_TOO_LONG` is the right existing code rather than a new
                // one: this codec declares an 11-character budget for
                // `preCount` (see the frame-size comment above), and
                // `Int.MAX_VALUE` is ten digits — a value needing more does not
                // fit the field this codec writes (CodeRabbit an earlier change finding [2]).
                if (preCount > Int.MAX_VALUE.toLong()) return Decoded.Refused(CodecRefusal.FIELD_TOO_LONG)
                val postFp = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
                if (postFp.length > MAX_FINGERPRINT) return Decoded.Refused(CodecRefusal.FIELD_TOO_LONG)
                val sidRaw = next() ?: return Decoded.Refused(CodecRefusal.TRUNCATED)
                if (sidRaw.length > MAX_SESSION_ID) return Decoded.Refused(CodecRefusal.FIELD_TOO_LONG)
                pending = FrameLossPending(
                    cause = cause,
                    preFingerprint = preFp,
                    preCount = preCount.toInt(),
                    expectedPostFingerprint = postFp,
                    sessionId = sidRaw.ifEmpty { null },
                )
            }
            else -> return Decoded.Refused(CodecRefusal.BAD_PENDING_FLAG)
        }

        if (i != f.size) return Decoded.Refused(CodecRefusal.TRAILING_BYTES)

        return Decoded.Ok(
            FrameContentLossState(
                version = ver.toInt(),
                g9Occurrences = longs[0],
                refusalCounts = counts,
                rows = rows,
                g6Collapsed = longs[1],
                g10Collapsed = longs[2],
                g6SessionsCollapsedUpperBound = longs[3],
                g10SessionsCollapsedUpperBound = longs[4],
                g9IndeterminateDisclosed = longs[5],
                g10IndeterminateDisclosed = longs[6],
                priorStateLost = bools[0],
                refusalRecordingIncomplete = bools[1],
                exclusionRefusalIncomplete = bools[2],
                pending = pending,
            )
        )
    }

    /** `sessionId`'s bound, as the row arithmetic assumes. */
    const val MAX_SESSION_ID: Int = 128

    /** A whole-store fold is a 64-character hex digest. */
    const val MAX_FINGERPRINT: Int = 64

    private fun causeOf(name: String): FrameLossCause? =
        FrameLossCause.values().firstOrNull { it.name == name }
}

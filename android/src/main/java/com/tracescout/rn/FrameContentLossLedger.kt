package com.tracescout.rn

/**
 * The PURE FCL state machine. rn-core, step 4/7.
 *
 * No IO, no clock, no `androidx`, no `android`. Every transition is a function
 * from a state and some facts to a new state, so process death, a crash inside
 * a removal and the reconciliation that follows can all be driven under a JVM
 * test exactly as they run on a device.
 *
 * **TOTALS ARE DERIVED, NEVER STORED.** One home per fact. A stored total is
 * a second authority that can disagree with the rows, and the disagreement will
 * be discovered by a customer rather than by a test.
 *
 * **NO TRANSITION EVER FABRICATES A COUNT.** `INDETERMINATE` is disclosed in
 * its own field and never promoted into a total; an unrecordable occurrence sets
 * a monotonic boolean and is never counted; a refused removal counts nothing,
 * because nothing was destroyed.
 *
 * **EVERY ACCUMULATION IS CHECKED.** [FrameContentLossCodec.checkedAdd]
 * refuses rather than wrapping or saturating, and a refusal is TYPED
 * ([FrameContentLossCodec.CounterRefusal.COUNTER_OVERFLOW]) so the caller can
 * disclose it instead of publishing a wrapped negative count.
 */
object FrameContentLossLedger {

    /** The result of a transition. A transition can REFUSE; it never silently clamps. */
    sealed class Update {
        data class Ok(val state: FrameContentLossState) : Update()
        data class Refused(val reason: Reason) : Update()
    }

    enum class Reason {
        /** A counter would have overflowed. Never wrapped, never saturated. */
        COUNTER_OVERFLOW,
        /** An occurrence count was negative — a defect upstream, not something to clamp. */
        NEGATIVE_INPUT,
        /** `ENQUEUE_REFUSED` (G6) can never have a PENDING: a refused enqueue destroys nothing. */
        CAUSE_HAS_NO_PENDING,
        /** A PENDING already exists. The schema holds 0 or 1 **by type**. */
        PENDING_ALREADY_PRESENT,
        /** A resolution was asked for with no PENDING to resolve. */
        NO_PENDING,
        /** The positional refusal vector did not match the closed enum. */
        REASON_VECTOR_MISMATCH,
    }

    /** What the whole-store discriminator concluded. */
    enum class Resolution {
        /** The head is still there: the removal has not happened. Keep the PENDING. */
        REMOVAL_STILL_OWED,
        /** The head is gone exactly as predicted: the removal committed. */
        REMOVAL_COMMITTED,
        /** It cannot be told. Disclosed in its own field, NEVER promoted, NEVER folded. */
        INDETERMINATE,
    }

    /** A first-install state. Every field is stated, because [FrameContentLossState] has no defaults. */
    fun fresh(): FrameContentLossState = FrameContentLossState(
        version = FrameContentLossCodec.VERSION,
        g9Occurrences = 0L,
        refusalCounts = List(FrameContentLossCodec.R) { 0L },
        rows = emptyList(),
        g6Collapsed = 0L,
        g10Collapsed = 0L,
        g6SessionsCollapsedUpperBound = 0L,
        g10SessionsCollapsedUpperBound = 0L,
        g9IndeterminateDisclosed = 0L,
        g10IndeterminateDisclosed = 0L,
        priorStateLost = false,
        refusalRecordingIncomplete = false,
        exclusionRefusalIncomplete = false,
        pending = null,
    )

    // ── session identity ─────────────────────────────────────────────────────

    /**
 * The one session-id shape, pinned to the envelope's own
 * `^ts_[0-9]+_[0-9a-f]{12}$` and the schema's 128-character ceiling.
 *
 * **NO AMBIENT SESSION, NO FALLBACK, NEVER A GUESS.** An occurrence whose
 * session cannot be validated goes to its cause's own collapsed accumulator
 * so the cause's TOTAL stays exact — rather than being attributed to
 * whichever session happened to be current.
 */
    private val SESSION_RE = Regex("^ts_[0-9]+_[0-9a-f]{12}$")

    fun isValidSessionId(id: String?): Boolean =
        id != null && id.length <= FrameContentLossCodec.MAX_SESSION_ID && SESSION_RE.matches(id)

    // ── derived totals: one home per fact ────────────────────────────────────

    /**
 * **`totalG6` MEANS "EXACTLY COUNTED G6-A OCCURRENCES", NEVER "ALL G6
 * OCCURRENCES".** G6-B and G6-C are uncounted by construction; the two
 * disclosure booleans exist to say so. A reader who takes this for "all"
 * will under-report and not know it.
 */
    fun totalG6(s: FrameContentLossState): FrameContentLossCodec.Counted =
        causeTotal(s, FrameLossCause.ENQUEUE_REFUSED, s.g6Collapsed)

    fun totalG10(s: FrameContentLossState): FrameContentLossCodec.Counted =
        causeTotal(s, FrameLossCause.RETRY_EXHAUSTED, s.g10Collapsed)

    /** G9 is WHOLLY UNATTRIBUTED: one exact install-scoped total, no rows at all. */
    fun totalG9(s: FrameContentLossState): FrameContentLossCodec.Counted =
        FrameContentLossCodec.Counted.Ok(s.g9Occurrences)

    private fun causeTotal(
        s: FrameContentLossState,
        cause: FrameLossCause,
        collapsed: Long,
    ): FrameContentLossCodec.Counted {
        val terms = ArrayList<Long>()
        for (r in s.rows) if (r.cause == cause) terms.add(r.occurrences)
        terms.add(collapsed)
        return FrameContentLossCodec.checkedSum(terms)
    }

    /**
 * The pinned cross-check `refusalCounts.sum == totalG6`. **BOTH SIDES
 * COUNT G6-A ONLY**, which is what makes it exact rather than approximate.
 * It is a test-time invariant, never a runtime tie-break: `totalG6` is
 * derived from rows plus collapsed, and a mismatch is a defect to fix, not
 * a number to reconcile at runtime.
 */
    fun refusalCountsTotal(s: FrameContentLossState): FrameContentLossCodec.Counted =
        FrameContentLossCodec.checkedSum(s.refusalCounts)

    // ── G6: a counted refusal ────────────────────────────────────────────────

    fun recordRefusal(
        s: FrameContentLossState,
        reason: FrameRefusalReason,
        sessionId: String?,
    ): Update {
        if (s.refusalCounts.size != FrameContentLossCodec.R) {
            return Update.Refused(Reason.REASON_VECTOR_MISMATCH)
        }
        val idx = reason.ordinal
        val bumped = when (val c = FrameContentLossCodec.checkedAdd(s.refusalCounts[idx], 1L)) {
            is FrameContentLossCodec.Counted.Ok -> c.value
            is FrameContentLossCodec.Counted.Refused -> return Update.Refused(map(c.reason))
        }
        val counts = s.refusalCounts.toMutableList().also { it[idx] = bumped }
        return addOccurrence(s.copy(refusalCounts = counts), FrameLossCause.ENQUEUE_REFUSED, sessionId)
    }

    // ── G9 / G10: PREPARE -> resolve ─────────────────────────────────────────

    /**
 * Commit the intent to remove the head. **G9/G10 only** — `ENQUEUE_REFUSED`
 * is refused by type, because a refused enqueue destroys nothing and so has
 * nothing to reconcile.
 */
    fun prepare(
        s: FrameContentLossState,
        cause: FrameLossCause,
        preFingerprint: String,
        preCount: Int,
        expectedPostFingerprint: String,
        sessionId: String?,
    ): Update {
        if (cause == FrameLossCause.ENQUEUE_REFUSED) return Update.Refused(Reason.CAUSE_HAS_NO_PENDING)
        if (s.pending != null) return Update.Refused(Reason.PENDING_ALREADY_PRESENT)
        // UNREADABLE_RECORD carries NO id at all — not an empty one, not a
        // guessed one. The record could not be decoded, so there is no session
        // to name.
        val sid = if (cause == FrameLossCause.UNREADABLE_RECORD) null
        else sessionId?.takeIf { isValidSessionId(it) }
        return Update.Ok(
            s.copy(
                pending = FrameLossPending(
                    cause = cause,
                    preFingerprint = preFingerprint,
                    preCount = preCount,
                    expectedPostFingerprint = expectedPostFingerprint,
                    sessionId = sid,
                )
            )
        )
    }

    /**
 * Apply what the typed removal actually did.
 *
 * **ONLY `COMMITTED` COUNTS.** `NOT_COMMITTED` means the bytes are still
 * in the store, so counting it would report a loss that did not happen;
 * `INDETERMINATE_DIRECTORY_UNSYNCED` means it cannot be told, and a count
 * taken on a maybe is a fabricated number.
 */
    fun resolve(s: FrameContentLossState, removal: FrameRemoval): Update {
        val p = s.pending ?: return Update.Refused(Reason.NO_PENDING)
        return when (removal) {
            FrameRemoval.COMMITTED -> countResolved(s, p)
            FrameRemoval.NOT_COMMITTED -> Update.Ok(s.copy(pending = null))
            FrameRemoval.INDETERMINATE_DIRECTORY_UNSYNCED -> discloseIndeterminate(s, p)
        }
    }

    /**
 * The whole-store discriminator, as a PURE decision over facts the caller
 * measured. Order is load-bearing and is the order of the specification
 *
 * **THE EXISTENCE WITNESS COMES FIRST.** A committed removal to empty
 * leaves the file PRESENT at length 0, while an ABSENT file reads
 * trustworthy-empty — present-and-empty is not absent, and the witness is
 * `File.exists`, never the read.
 *
 * **AN UNTRUSTWORTHY READ IS `INDETERMINATE`, NOT A SMALLER STORE.** A
 * lossy read's size and fold describe the records that survived decoding,
 * which is not the store.
 *
 * **THE TWO SIZE TESTS ARE MUTUALLY EXCLUSIVE**, so the predicate is
 * never ambiguous — including the identical-record shift `[victim, dup]`,
 * where the fold differs even though the size matches.
 *
 * **DISCLOSED READING.** The sealed authority spells this
 * `reconcile(storePresent, size, wholeStoreFingerprint)` — three facts —
 * while the same section's own predicate also requires the READ
 * CLASSIFICATION (`discarded != 0 || truncatedTail`). [trustworthyRead] is
 * therefore a FOURTH parameter rather than being folded into
 * [storePresent]: conflating "the file is gone" with "the read was lossy"
 * would report one as the other, and they are different facts with the same
 * conclusion only by coincidence.
 */
    fun decide(
        pending: FrameLossPending,
        storePresent: Boolean,
        trustworthyRead: Boolean,
        size: Int,
        wholeStoreFingerprint: String,
    ): Resolution {
        if (!storePresent) return Resolution.INDETERMINATE
        if (!trustworthyRead) return Resolution.INDETERMINATE
        val owed = size == pending.preCount && wholeStoreFingerprint == pending.preFingerprint
        val done = size == pending.preCount - 1 && wholeStoreFingerprint == pending.expectedPostFingerprint
        return when {
            owed && !done -> Resolution.REMOVAL_STILL_OWED
            done && !owed -> Resolution.REMOVAL_COMMITTED
            else -> Resolution.INDETERMINATE
        }
    }

    /** Resolve a surviving PENDING against the live store. */
    fun reconcile(
        s: FrameContentLossState,
        storePresent: Boolean,
        trustworthyRead: Boolean,
        size: Int,
        wholeStoreFingerprint: String,
    ): Update {
        val p = s.pending ?: return Update.Refused(Reason.NO_PENDING)
        return when (decide(p, storePresent, trustworthyRead, size, wholeStoreFingerprint)) {
            // Keep the PENDING: the next drain re-attempts the removal.
            Resolution.REMOVAL_STILL_OWED -> Update.Ok(s)
            Resolution.REMOVAL_COMMITTED -> countResolved(s, p)
            Resolution.INDETERMINATE -> discloseIndeterminate(s, p)
        }
    }

    private fun countResolved(s: FrameContentLossState, p: FrameLossPending): Update {
        val cleared = s.copy(pending = null)
        return when (p.cause) {
            // G9 has NO rows: one exact install-scoped total is its only home.
            FrameLossCause.UNREADABLE_RECORD ->
                when (val c = FrameContentLossCodec.checkedAdd(cleared.g9Occurrences, 1L)) {
                    is FrameContentLossCodec.Counted.Ok -> Update.Ok(cleared.copy(g9Occurrences = c.value))
                    is FrameContentLossCodec.Counted.Refused -> Update.Refused(map(c.reason))
                }
            FrameLossCause.RETRY_EXHAUSTED ->
                addOccurrence(cleared, FrameLossCause.RETRY_EXHAUSTED, p.sessionId)
            FrameLossCause.ENQUEUE_REFUSED -> Update.Refused(Reason.CAUSE_HAS_NO_PENDING)
        }
    }

    private fun discloseIndeterminate(s: FrameContentLossState, p: FrameLossPending): Update {
        val cleared = s.copy(pending = null)
        val cur = when (p.cause) {
            FrameLossCause.UNREADABLE_RECORD -> cleared.g9IndeterminateDisclosed
            FrameLossCause.RETRY_EXHAUSTED -> cleared.g10IndeterminateDisclosed
            FrameLossCause.ENQUEUE_REFUSED -> return Update.Refused(Reason.CAUSE_HAS_NO_PENDING)
        }
        val next = when (val c = FrameContentLossCodec.checkedAdd(cur, 1L)) {
            is FrameContentLossCodec.Counted.Ok -> c.value
            is FrameContentLossCodec.Counted.Refused -> return Update.Refused(map(c.reason))
        }
        return Update.Ok(
            when (p.cause) {
                FrameLossCause.UNREADABLE_RECORD -> cleared.copy(g9IndeterminateDisclosed = next)
                FrameLossCause.RETRY_EXHAUSTED -> cleared.copy(g10IndeterminateDisclosed = next)
                FrameLossCause.ENQUEUE_REFUSED -> return Update.Refused(Reason.CAUSE_HAS_NO_PENDING)
            }
        )
    }

    // ── attributed rows + the deterministic fair collapse ────────────────────

    /**
 * Add one occurrence for (cause, validated session).
 *
 * An unattributable session goes to the cause's OWN collapsed accumulator,
 * so the cause's total stays exact. It does **not** touch
 * `sessionsCollapsedUpperBound`: that field's documented meaning is **FOLD
 * EVENTS**, and an occurrence that never had a row was never folded.
 * Incrementing it would overstate how many distinct sessions were lost to
 * folding — disclosed here rather than decided silently.
 */
    private fun addOccurrence(
        s: FrameContentLossState,
        cause: FrameLossCause,
        sessionId: String?,
    ): Update {
        if (!isValidSessionId(sessionId)) return bumpCollapsed(s, cause, foldEvent = false)
        val sid = sessionId!!
        val idx = s.rows.indexOfFirst { it.cause == cause && it.sessionId == sid }
        if (idx >= 0) {
            val bumped = when (val c = FrameContentLossCodec.checkedAdd(s.rows[idx].occurrences, 1L)) {
                is FrameContentLossCodec.Counted.Ok -> c.value
                is FrameContentLossCodec.Counted.Refused -> return Update.Refused(map(c.reason))
            }
            val rows = s.rows.toMutableList()
            rows[idx] = rows[idx].copy(occurrences = bumped)
            return Update.Ok(s.copy(rows = rows))
        }
        val base = if (s.rows.size < FrameContentLossCodec.ROWS_MAX) s
        else when (val folded = collapse(s, cause)) {
            is Update.Ok -> folded.state
            is Update.Refused -> return folded
        }
        return Update.Ok(base.copy(rows = base.rows + FrameLossRow(cause, sid, 1L)))
    }

    /**
 * **Deterministic fair collapse with a per-cause floor**, so one cause can
 * neither starve nor corrupt the other.
 *
 * **NO TERM EVER CROSSES CAUSES** — a victim's occurrences go to the
 * VICTIM's own collapsed accumulator. That is what keeps `totalG6` and
 * `totalG10` each exact at all times, which is stronger than fairness and is
 * the property that matters: neither cause can make the other's total
 * inexact.
 *
 * **THE BRANCH IS TOTAL.** At `rows.size == ROWS_MAX` with
 * `n_c < CAUSE_FLOOR`, the other cause holds `ROWS_MAX - n_c > CAUSE_FLOOR`
 * rows, so a victim always exists.
 *
 * The victim is the minimum by `(occurrences, index)` — fully deterministic,
 * so the same history always folds the same way.
 */
    fun collapse(s: FrameContentLossState, incoming: FrameLossCause): Update {
        val n = s.rows.count { it.cause == incoming }
        val victimCause =
            if (n >= FrameContentLossCodec.CAUSE_FLOOR) incoming else otherRowCause(incoming)
        var victimIdx = -1
        for ((i, r) in s.rows.withIndex()) {
            if (r.cause != victimCause) continue
            if (victimIdx < 0 || r.occurrences < s.rows[victimIdx].occurrences) victimIdx = i
        }
        if (victimIdx < 0) {
            // No row of the chosen cause exists. Fall back to the incoming
            // cause's own collapsed accumulator rather than folding a row that
            // belongs to neither — the total must stay exact.
            return bumpCollapsed(s, incoming, foldEvent = false)
        }
        val victim = s.rows[victimIdx]
        val rows = s.rows.toMutableList().also { it.removeAt(victimIdx) }
        return bumpCollapsed(s.copy(rows = rows), victim.cause, foldEvent = true, add = victim.occurrences)
    }

    /**
 * The only two causes that have rows are `ENQUEUE_REFUSED` (G6) and
 * `RETRY_EXHAUSTED` (G10); `UNREADABLE_RECORD` (G9) never does.
 */
    private fun otherRowCause(c: FrameLossCause): FrameLossCause = when (c) {
        FrameLossCause.ENQUEUE_REFUSED -> FrameLossCause.RETRY_EXHAUSTED
        FrameLossCause.RETRY_EXHAUSTED -> FrameLossCause.ENQUEUE_REFUSED
        FrameLossCause.UNREADABLE_RECORD -> FrameLossCause.ENQUEUE_REFUSED
    }

    private fun bumpCollapsed(
        s: FrameContentLossState,
        cause: FrameLossCause,
        foldEvent: Boolean,
        add: Long = 1L,
    ): Update {
        val curCollapsed = when (cause) {
            FrameLossCause.ENQUEUE_REFUSED -> s.g6Collapsed
            FrameLossCause.RETRY_EXHAUSTED -> s.g10Collapsed
            FrameLossCause.UNREADABLE_RECORD -> return Update.Refused(Reason.CAUSE_HAS_NO_PENDING)
        }
        val nextCollapsed = when (val c = FrameContentLossCodec.checkedAdd(curCollapsed, add)) {
            is FrameContentLossCodec.Counted.Ok -> c.value
            is FrameContentLossCodec.Counted.Refused -> return Update.Refused(map(c.reason))
        }
        var out = when (cause) {
            FrameLossCause.ENQUEUE_REFUSED -> s.copy(g6Collapsed = nextCollapsed)
            FrameLossCause.RETRY_EXHAUSTED -> s.copy(g10Collapsed = nextCollapsed)
            FrameLossCause.UNREADABLE_RECORD -> return Update.Refused(Reason.CAUSE_HAS_NO_PENDING)
        }
        if (foldEvent) {
            val curBound = when (cause) {
                FrameLossCause.ENQUEUE_REFUSED -> out.g6SessionsCollapsedUpperBound
                FrameLossCause.RETRY_EXHAUSTED -> out.g10SessionsCollapsedUpperBound
                FrameLossCause.UNREADABLE_RECORD -> return Update.Refused(Reason.CAUSE_HAS_NO_PENDING)
            }
            val nextBound = when (val c = FrameContentLossCodec.checkedAdd(curBound, 1L)) {
                is FrameContentLossCodec.Counted.Ok -> c.value
                is FrameContentLossCodec.Counted.Refused -> return Update.Refused(map(c.reason))
            }
            out = when (cause) {
                FrameLossCause.ENQUEUE_REFUSED -> out.copy(g6SessionsCollapsedUpperBound = nextBound)
                FrameLossCause.RETRY_EXHAUSTED -> out.copy(g10SessionsCollapsedUpperBound = nextBound)
                FrameLossCause.UNREADABLE_RECORD -> return Update.Refused(Reason.CAUSE_HAS_NO_PENDING)
            }
        }
        return Update.Ok(out)
    }

    // ── the three disclosure setters ─────────────────────────────────────────

    /**
 * **MONOTONIC, AND NEVER CLEARED IN ORDINARY OPERATION.** No ack, drain,
 * collapse, trim, reporting or reconciliation may clear one. They are
 * cleared only by a governed erasure, which drops the whole ledger — which
 * is what makes a post-erasure ledger structurally incapable of carrying
 * pre-erasure disclosure.
 *
 * **A BOOLEAN, NOT A COUNT.** A count of events you could not record is
 * itself unrecordable; publishing one would be a fabricated number in the
 * one place honesty matters most.
 */
    fun setPriorStateLost(s: FrameContentLossState): FrameContentLossState =
        if (s.priorStateLost) s else s.copy(priorStateLost = true)

    /** G6-B: the refusal happened in scope and its ledger write was not durable. */
    fun setRefusalRecordingIncomplete(s: FrameContentLossState): FrameContentLossState =
        if (s.refusalRecordingIncomplete) s else s.copy(refusalRecordingIncomplete = true)

    /** G6-C: the outer acquisition was refused, so the scope never existed. Structurally unrecordable. */
    fun setExclusionRefusalIncomplete(s: FrameContentLossState): FrameContentLossState =
        if (s.exclusionRefusalIncomplete) s else s.copy(exclusionRefusalIncomplete = true)

    private fun map(r: FrameContentLossCodec.CounterRefusal): Reason = when (r) {
        FrameContentLossCodec.CounterRefusal.COUNTER_OVERFLOW -> Reason.COUNTER_OVERFLOW
        FrameContentLossCodec.CounterRefusal.NEGATIVE_INPUT -> Reason.NEGATIVE_INPUT
    }
}

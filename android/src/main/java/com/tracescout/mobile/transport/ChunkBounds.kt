package com.tracescout.mobile.transport

import androidx.annotation.RestrictTo

/**
 * How large ONE semantic chunk may be, and what to do when the server says the
 * boundary was wrong.
 *
 * THE DEFECT THIS EXISTS FOR. `sessionOwnedPrefix` took the LONGEST
 * contiguous session-owned prefix with no size bound at all, so an offline or
 * outage accumulation produced one request as large as the queue. The deployed
 * API parses the chunk route with `express.json({ limit: '2mb' })` and
 * separately refuses `events.length > 2000`, so a large enough batch is
 * answered **413** — and 413 is a 4xx, which `classifyResponse` calls TERMINAL,
 * which fell through `terminalDisposition` to `QUARANTINE_PREFIX`. Quarantine
 * is DELETION. A client that accumulated records precisely because it could not
 * reach the server would then destroy them on the first request that reached it.
 *
 * **This file is a restatement of one shared policy, not an Android one.** Any
 * divergence from `mobile-react-native/ios/Sources/TraceScoutRN/ChunkBounds.swift`
 * is a defect; both are pinned to `mobile-native-core/fixtures/chunk-bounds.json`
 * so a disagreement fails on BYTES rather than on a reviewer noticing.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object ChunkBounds {

    /**
 * The normal budget for the ACTUAL encoded HTTP request body — envelope
 * included, not a sum of record payload bytes.
 *
 * 1 MiB against a measured 2 MiB parser boundary is a deliberate 2x margin:
 * the number a client computes and the number a proxy or a gateway counts
 * are not obliged to be the same number, and a budget set at the boundary
 * would make every disagreement a refusal.
 */
    const val DEFAULT_BODY_BUDGET_BYTES: Long = 1L * 1024 * 1024

    /**
 * The server's hard body limit, MEASURED rather than assumed:
 * `express.json({ limit: '2mb' })` on the chunk route, i.e. `bytes('2mb')`
 * = 2 * 1024 * 1024. A body of exactly this size is accepted; one byte more
 * is refused before the handler runs.
 *
 * It is used for exactly ONE question — whether a single record is
 * individually undeliverable — and never as a send budget.
 */
    const val SERVER_HARD_BODY_LIMIT_BYTES: Long = 2L * 1024 * 1024

    /**
 * The record-count cap. iOS already enforced 64 in its own prefix builder;
 * Android enforced nothing. Keeping 64 makes the deployed 2000-event cliff
 * unreachable by a factor of 31 and leaves the two producers saying the
 * same thing.
 */
    const val MAX_RECORDS_PER_CHUNK: Int = 64

    /**
 * The floor a repeated 413 may shrink the byte budget to. The shrink
 * terminates on the record COUNT (which bottoms out at one), so this exists
 * to stop the budget spiralling toward zero on the way there rather than to
 * terminate anything itself.
 */
    const val MIN_BODY_BUDGET_BYTES: Long = 64L * 1024

    /**
 * The exact encoded length of everything in the chunk body that is not a
 * record or a separator.
 *
 * The canonical envelope is
 * `{"sessionId":"<s>","chunkId":"<c>","events":[r1,…,rn]}` and the literal
 * punctuation is 14 + 13 + 12 + 2 = 41 bytes. `chunkId` is ALWAYS 32
 * lowercase hex characters — `BatchIdentity.of` takes `substring(0, 32)` of
 * a sha256 hex digest — so it contributes a constant 32 and the overhead is
 * computable without knowing which records are in the batch.
 *
 * IT TAKES A BYTE LENGTH, NOT A STRING. Session ids match
 * `^ts_[0-9]+_[0-9a-f]{12}$` and need no JSON escaping, but a length is the
 * honest input: a caller that ever escapes must pass the ESCAPED length,
 * and this function must not be the place that decides it did not have to.
 */
    const val ENVELOPE_PUNCTUATION_BYTES: Int = 41
    const val CHUNK_ID_BYTES: Int = 32

    @JvmStatic
    fun envelopeOverheadBytes(sessionIdUtf8Len: Int): Int =
        ENVELOPE_PUNCTUATION_BYTES + CHUNK_ID_BYTES + sessionIdUtf8Len

    /**
 * The exact encoded body length for a batch of these record sizes.
 *
 * Records are joined with `,`, so there are `n - 1` separators — clamped at
 * zero, because an empty list must not contribute a negative byte.
 */
    @JvmStatic
    fun bodyBytes(sessionIdUtf8Len: Int, recordSizes: List<Int>): Long {
        var total = envelopeOverheadBytes(sessionIdUtf8Len).toLong()
        for (s in recordSizes) total += s.toLong()
        if (recordSizes.size > 1) total += (recordSizes.size - 1).toLong()
        return total
    }

    /**
 * How many records of this contiguous prefix may go in ONE chunk.
 *
 * THE HEAD IS ALWAYS ADMITTED, whatever its size. A strict-FIFO queue
 * whose head cannot be admitted is a queue that never moves again: every
 * record behind a single oversized one would be starved for the life of the
 * install, which is the same starvation `PARKED_PRODUCER` was introduced to
 * end. Whether that head is DELIVERABLE is a separate question, answered by
 * [headDeliverable]; a head that is not is preserved, never deleted.
 *
 * The comparison is `<=`: a body of exactly the budget is admitted. The
 * fixture carries that boundary and the byte one past it, so the two cases
 * together prove which comparison is in the code.
 */
    @JvmStatic
    fun boundedCount(
        sessionIdUtf8Len: Int,
        recordSizes: List<Int>,
        budgetBytes: Long = DEFAULT_BODY_BUDGET_BYTES,
        maxRecords: Int = MAX_RECORDS_PER_CHUNK,
    ): Int {
        if (recordSizes.isEmpty()) return 0
        val cap = if (maxRecords < 1) 1 else minOf(maxRecords, recordSizes.size)
        var running = envelopeOverheadBytes(sessionIdUtf8Len).toLong()
        var n = 0
        while (n < cap) {
            val next = running + recordSizes[n].toLong() + (if (n > 0) 1L else 0L)
            // The head is admitted whatever it costs; every later record must
            // fit inside the budget.
            //
            // THIS AND THE CLAMP BELOW ARE REDUNDANT DEFENCES OF ONE
            // CONTRACT — the head is never stranded — AND EITHER ALONE
            // PRESERVES IT. Neither is "the" load-bearing site, and an earlier
            // version of this comment claimed the clamp was; three negative
            // controls over the whole 331-test `:sdk` suite say otherwise:
            //
            // this exemption removed alone 331 / 0 NOT caught
            // the clamp removed alone 331 / 0 NOT caught
            // BOTH removed 331 / 2 caught
            //
            // Remove one and a reader sees no failure — which is exactly the
            // shape that makes a doubled guard dangerous to tidy up. Removing
            // either is therefore a silent narrowing of defence, not a
            // simplification, and only removing both expresses "the head is
            // stranded".
            if (n > 0 && next > budgetBytes) break
            running = next
            n++
        }
        // THE SECOND OF THE TWO REDUNDANT HEAD ADMISSIONS — see the control
        // table above. A non-empty run always yields at least one record; the
        // empty case returned 0 above, so this cannot invent a record out of
        // nothing.
        return if (n < 1) 1 else n
    }

    /**
 * Can this record be delivered AT ALL — alone, in the smallest legal body
 * that can carry it?
 *
 * Re-slicing cannot help a record that fails this: there is nothing left to
 * split. The record store admits a single record up to 4 MiB
 * (`EncryptedEventStore.MAX_FRAME_BYTES`) while the server's body limit is
 * 2 MiB, so this state is genuinely REACHABLE and is not dead preparedness.
 */
    @JvmStatic
    fun headDeliverable(
        sessionIdUtf8Len: Int,
        firstRecordSize: Int,
        hardLimitBytes: Long = SERVER_HARD_BODY_LIMIT_BYTES,
    ): Boolean = bodyBytes(sessionIdUtf8Len, listOf(firstRecordSize)) <= hardLimitBytes

    /**
 * The next bounds to try after a 413.
 *
 * A RESLICE THAT RETRIES THE SAME SIZE IS A RETAIN WEARING A NEW NAME.
 * The case this exists for is an oversized in-flight descriptor written by
 * a client that predates the bounds: rebuilding it at the same size would
 * ask a server that has already refused it, forever. Each refusal at least
 * HALVES the admitted record count, so the sequence reaches one record in a
 * bounded number of steps; a single record still refused is individually
 * undeliverable at this server and is preserved by the caller rather than
 * retried again.
 *
 * The byte budget is halved from whichever is smaller — the current budget
 * or the body that was actually refused — so a server with a limit far
 * below ours converges instead of stepping down from a number that was
 * never the binding one.
 */
    data class Shrunk(val maxRecords: Int, val budgetBytes: Long)

    @JvmStatic
    fun shrink(
        refusedRecordCount: Int,
        refusedBodyBytes: Long,
        currentBudgetBytes: Long,
    ): Shrunk = Shrunk(
        maxRecords = maxOf(1, refusedRecordCount / 2),
        budgetBytes = maxOf(MIN_BODY_BUDGET_BYTES, minOf(currentBudgetBytes, refusedBodyBytes) / 2),
    )

    /** The typed reason a single record is preserved rather than delivered. */
    const val RECORD_BODY_TOO_LARGE = "record_body_too_large"
}

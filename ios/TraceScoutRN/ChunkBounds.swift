import Foundation

/// How large ONE semantic chunk may be, and what to do when the server says the
/// boundary was wrong.
///
/// THE DEFECT THIS EXISTS FOR. The shared prefix builder bounded only the
/// record COUNT (64 on iOS, nothing at all on Android), so a batch of large
/// records could still exceed the deployed API's body limit. That route parses
/// with `express.json({ limit: "2mb" })` and separately refuses
/// `events.length > 2000`, so a large enough batch is answered **413** — and
/// 413 is a 4xx, which `classifyResponse` calls terminal, which fell through
/// `terminalDisposition` to `.quarantinePrefix`. Quarantine is DELETION. A
/// client that accumulated records precisely because it could not reach the
/// server would then destroy them on the first request that reached it.
///
/// **This file is a restatement of one shared policy, not an iOS one.** Any
/// divergence from `mobile-android/./transport/ChunkBounds.kt` is a defect;
/// both are pinned to `mobile-native-core/fixtures/chunk-bounds.json` so a
/// disagreement fails on BYTES rather than on a reviewer noticing.
public enum ChunkBounds {

    /// The normal budget for the ACTUAL encoded HTTP request body — envelope
    /// included, not a sum of record payload bytes.
    ///
    /// 1 MiB against a measured 2 MiB parser boundary is a deliberate 2x
    /// margin: the number a client computes and the number a proxy or gateway
    /// counts are not obliged to be the same number, and a budget set at the
    /// boundary would make every disagreement a refusal.
    public static let defaultBodyBudgetBytes: Int = 1 * 1024 * 1024

    /// The server's hard body limit, MEASURED rather than assumed:
    /// `express.json({ limit: "2mb" })` on the chunk route, i.e. `bytes("2mb")`
    /// = 2 * 1024 * 1024. A body of exactly this size is accepted; one byte
    /// more is refused before the handler runs.
    ///
    /// It answers exactly ONE question — whether a single record is
    /// individually undeliverable — and is never used as a send budget.
    public static let serverHardBodyLimitBytes: Int = 2 * 1024 * 1024

    /// The record-count cap. iOS already enforced 64 in its own prefix builder;
    /// Android enforced nothing. Keeping 64 makes the deployed 2000-event cliff
    /// unreachable by a factor of 31 and leaves the two producers saying the
    /// same thing.
    public static let maxRecordsPerChunk: Int = 64

    /// The floor a repeated 413 may shrink the byte budget to. The shrink
    /// terminates on the record COUNT (which bottoms out at one), so this
    /// exists to stop the budget spiralling toward zero on the way there rather
    /// than to terminate anything itself.
    public static let minBodyBudgetBytes: Int = 64 * 1024

    /// The literal punctuation of the canonical envelope
    /// `{"sessionId":<s>,"chunkId":<c>,"events":[r1,…,rn]}`, counting the
    /// quotes around both identifiers: 13 + 11 + 11 + 2 + 4 = 41.
    public static let envelopePunctuationBytes = 41
    /// `chunkId` is ALWAYS 32 lowercase hex characters — `BatchIdentity.of`
    /// takes `prefix(32)` of a sha256 hex digest — so the overhead is
    /// computable without knowing which records are in the batch.
    public static let chunkIdBytes = 32

    /// IT TAKES A BYTE LENGTH, NOT A STRING, and the length must be the one
    /// THIS platform's encoder produces. The iOS encoder escapes through
    /// `RnEnvelope.str`, the Android one concatenates raw; session ids match
    /// `^ts_[0-9]+_[0-9a-f]{12}$` and so need no escaping, which makes the two
    /// agree today — but a function that assumed that would be deciding
    /// something it is not entitled to decide. Use `encodedSessionIdLength`.
    public static func envelopeOverheadBytes(sessionIdUtf8Len: Int) -> Int {
        envelopePunctuationBytes + chunkIdBytes + sessionIdUtf8Len
    }

    /// The encoded length of a session id as THIS platform's encoder writes it,
    /// WITHOUT its surrounding quotes (those are counted in the punctuation).
    public static func encodedSessionIdLength(_ sessionId: String) -> Int {
        max(0, RnEnvelope.str(sessionId).utf8.count - 2)
    }

    /// The exact encoded body length for a batch of these record sizes.
    ///
    /// Records are joined with `,`, so there are `n - 1` separators — clamped
    /// at zero, because an empty list must not contribute a negative byte.
    public static func bodyBytes(sessionIdUtf8Len: Int, recordSizes: [Int]) -> Int {
        var total = envelopeOverheadBytes(sessionIdUtf8Len: sessionIdUtf8Len)
        for s in recordSizes { total += s }
        if recordSizes.count > 1 { total += recordSizes.count - 1 }
        return total
    }

    /// How many records of this contiguous prefix may go in ONE chunk.
    ///
    /// THE HEAD IS ALWAYS ADMITTED, whatever its size. A strict-FIFO queue
    /// whose head cannot be admitted is a queue that never moves again: every
    /// record behind a single oversized one would be starved for the life of
    /// the install, which is the same starvation `PARKED_PRODUCER` was
    /// introduced to end. Whether that head is DELIVERABLE is a separate
    /// question, answered by `headDeliverable`; a head that is not is
    /// preserved, never deleted.
    ///
    /// The comparison is `<=`: a body of exactly the budget is admitted. The
    /// fixture carries that boundary and the byte one past it, so the two cases
    /// together prove which comparison is in the code.
    public static func boundedCount(sessionIdUtf8Len: Int,
                                    recordSizes: [Int],
                                    budgetBytes: Int = defaultBodyBudgetBytes,
                                    maxRecords: Int = maxRecordsPerChunk) -> Int {
        if recordSizes.isEmpty { return 0 }
        let cap = min(max(1, maxRecords), recordSizes.count)
        var running = envelopeOverheadBytes(sessionIdUtf8Len: sessionIdUtf8Len)
        var n = 0
        while n < cap {
            let next = running + recordSizes[n] + (n > 0 ? 1 : 0)
            // The head is admitted whatever it costs; every later record must
            // fit inside the budget.
            //
            // THIS AND THE CLAMP BELOW ARE REDUNDANT DEFENCES OF ONE
            // CONTRACT — the head is never stranded — AND EITHER ALONE
            // PRESERVES IT. Neither is "the" load-bearing site, and an earlier
            // version of this comment claimed the clamp was; three negative
            // controls on the Kotlin twin, over its whole 331-test suite, say
            // otherwise:
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
            if n > 0 && next > budgetBytes { break }
            running = next
            n += 1
        }
        // THE SECOND OF THE TWO REDUNDANT HEAD ADMISSIONS — see the control
        // table above. A non-empty run always yields at least one record; the
        // empty case returned 0 above, so this cannot invent a record out of
        // nothing.
        return max(1, n)
    }

    /// Can this record be delivered AT ALL — alone, in the smallest legal body
    /// that can carry it?
    ///
    /// Re-slicing cannot help a record that fails this: the batch is already
    /// one record and there is nothing left to split. The record store admits a
    /// single record far above the server's 2 MiB body limit, so this state is
    /// genuinely REACHABLE and is not dead preparedness.
    public static func headDeliverable(sessionIdUtf8Len: Int,
                                       firstRecordSize: Int,
                                       hardLimitBytes: Int = serverHardBodyLimitBytes) -> Bool {
        bodyBytes(sessionIdUtf8Len: sessionIdUtf8Len, recordSizes: [firstRecordSize])
            <= hardLimitBytes
    }

    public struct Shrunk: Equatable {
        public let maxRecords: Int
        public let budgetBytes: Int
    }

    /// The next bounds to try after a 413.
    ///
    /// A RESLICE THAT RETRIES THE SAME SIZE IS A RETAIN WEARING A NEW NAME.
    /// The case this exists for is an oversized in-flight descriptor written by
    /// a client that predates the bounds: rebuilding it at the same size would
    /// ask a server that has already refused it, forever. Each refusal at least
    /// HALVES the admitted record count, so the sequence reaches one record in
    /// a bounded number of steps; a single record still refused is
    /// individually undeliverable at this server and is preserved by the
    /// caller rather than retried again.
    ///
    /// The byte budget is halved from whichever is smaller — the current budget
    /// or the body that was actually refused — so a server with a limit far
    /// below ours converges instead of stepping down from a number that was
    /// never the binding one.
    public static func shrink(refusedRecordCount: Int,
                              refusedBodyBytes: Int,
                              currentBudgetBytes: Int) -> Shrunk {
        Shrunk(maxRecords: max(1, refusedRecordCount / 2),
               budgetBytes: max(minBodyBudgetBytes,
                                min(currentBudgetBytes, refusedBodyBytes) / 2))
    }

    /// The typed reason a single record is preserved rather than delivered.
    public static let recordBodyTooLarge = "record_body_too_large"
}

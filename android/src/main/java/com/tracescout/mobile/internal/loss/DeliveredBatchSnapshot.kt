package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. It is `public` only because Kotlin `internal` is
 * compilation-module scoped and every consumer (`:sdk`) lives across the
 * module boundary. a conformance test pins that no type
 * here ever appears in a public API signature of `:sdk` / `:rn` / rn-core.
 */
/**
 * One delivered batch, as the snapshot retains it.
 *
 * TERMINAL IDENTITY, NOT A COUNT. `baselineCounts` answers *how many were
 * lost*; this answers *what lifecycle state owns sequence S*. Folding the second
 * question into the first is what made the v2 snapshot unable to support
 * delivery at all: after a compaction the identities were gone while the
 * quantities remained, so nothing could refuse a second marker claiming an
 * already-delivered sequence.
 *
 * Metadata only — no payload, URL, header, event content or credential.
 */
public data class DeliveredBatchSnapshot(
    val markerId: String,
    val sessionId: String,
    val chunkId: String,
    val recordCount: Int,
    /** Canonical lowercase hex of the 32-byte content digest. */
    val contentSha256: String,
    /** The canonical [DeliveredSeqCodec] bytes — the stored set, not a range. */
    val sequenceEncoding: ByteArray,
) {
    /** The exact ordered set, decoded and count-verified. */
    public fun sequences(): List<Long> = DeliveredSeqCodec.decode(sequenceEncoding, recordCount)

    /** Recompute the identity from the embedded facts; it must equal [markerId]. */
    public fun recomputedMarkerId(): String = DeliveredMarkerId.of(
        sessionId, chunkId, recordCount,
        DeliveredBatchFields.hexToBytes(contentSha256), sequenceEncoding)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeliveredBatchSnapshot) return false
        return markerId == other.markerId && sessionId == other.sessionId &&
            chunkId == other.chunkId && recordCount == other.recordCount &&
            contentSha256 == other.contentSha256 &&
            sequenceEncoding.contentEquals(other.sequenceEncoding)
    }

    override fun hashCode(): Int = markerId.hashCode()
}

/**
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK (mobile-loss-core).
 * NOT a supported public surface; NO compatibility guarantee across versions.
 * `public` only because its consumers live in `:sdk`, across the module boundary
 * (formerly `@RestrictTo(LIBRARY_GROUP)` — stripped, decision PU; B10 pins the boundary).
 *
 * THE IMMUTABLE PRODUCTION VIEW OF ONE TERMINAL DELIVERED BATCH.
 *
 * WHY A VIEW AND NOT THE SNAPSHOT. `DeliveredBatchSnapshot.sequenceEncoding`
 * is a live `ByteArray`; handing it out IS a mutable alias, and a recovery
 * caller that mutated it would be editing terminal authority in place. This
 * carries only decoded, immutable values.
 *
 * WHAT IT PROVES, STATED PRECISELY. Identity here is
 * **session + exact ordered sequence set + chunk + frozen batch content
 * digest**. It is NOT byte-level per-record digest identity — the persisted
 * snapshot has never carried per-record digests. Within one session sequences
 * are unique and monotonic, which is what makes this sufficient for physical
 * reconciliation; when an InFlight descriptor is also present its per-record
 * digests are an ADDITIONAL cross-check, never a substitute for this.
 */
public data class DeliveredBatchView(
    val sessionId: String,
    val markerId: String,
    val chunkId: String,
    val recordCount: Int,
    /** The exact ordered set — decoded, never a range. */
    val sequences: List<Long>,
    /**
 * The frozen batch content identity, as stored.
 *
 * NAMED `frozen…` ON PURPOSE. This is the CLIENT's exact frozen batch
 * identity. The server ack's `contentSha256` is the canonical-gzip
 * stored-object digest and is a DIFFERENT number over DIFFERENT bytes; the
 * two are never compared and the server's is never persisted across a
 * retry. Two facts, two names.
 */
    val frozenContentSha256: String,
)

/**
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK (mobile-loss-core).
 * NOT a supported public surface; NO compatibility guarantee across versions.
 * `public` only because its consumers live in `:sdk`, across the module boundary
 * (formerly `@RestrictTo(LIBRARY_GROUP)` — stripped, decision PU; B10 pins the boundary).
 *
 * THE THREE-STATE ANSWER. Absent and Unknown are DIFFERENT FACTS.
 *
 * WHY THIS IS NOT A NULLABLE VIEW. `null` was safe only while it meant "do
 * no cleanup". The moment startup recovery reads it as "there is no terminal
 * DeliveredBatch, so generic stale handling and ordinary upload may continue",
 * internally inconsistent terminal authority becomes observationally identical
 * to definitely-nothing-was-delivered — the exact absent-vs-unreadable defect
 * class this programme has now paid for three times (`absent` vs `unreadable`
 * in the policy reader, `Blocked` vs `Unknown` in the eviction barrier, and
 * `NotMutated` vs `Indeterminate` at the store seam).
 */
public sealed class TerminalLookup {
    /** Well-formed query; the authority index establishes there is no match. */
    public object Absent : TerminalLookup()
    /** Complete, marker-coherent, immutable authority. */
    public data class Found(val batch: DeliveredBatchView) : TerminalLookup()
    /** Absence could not be established. FAILS CLOSED — never treated as Absent. */
    public data class Unknown(val reason: String) : TerminalLookup()
}

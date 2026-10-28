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
 * A VALIDATED delivered-batch descriptor. The only way to obtain a marker id.
 *
 * VALIDATION BY CONSTRUCTION, NOT BY CONVENTION. `DeliveredMarkerId.of` will
 * happily hash any inputs it is given, so a caller could otherwise mint a
 * perfectly well-formed identity for an INVALID batch — `recordCount = 3` beside
 * four sequences, say. Getting a different hash for a different count is not
 * enough: a mismatched count is not a second legitimate identity, it is a batch
 * that does not exist. This type refuses to come into being at all in that case,
 * so no downstream code has to remember to check.
 *
 * THE CONTENT DIGEST IS CANONICALIZED TO ITS 32 RAW BYTES BEFORE HASHING.
 * `"AB…"` and `"ab…"` are the same digest; hashing the TEXT would give one
 * logical batch two marker identities, and both would apply. The hex is
 * validated as exactly a SHA-256 digest and decoded — an alternate textual
 * representation cannot survive into the identity.
 */
public class DeliveredBatchFields private constructor(
    public val sessionId: String,
    public val chunkId: String,
    public val recordCount: Int,
    /** The canonical lowercase hex form, for storage and reporting. */
    public val contentSha256: String,
    public val sequences: List<Long>,
    public val sequenceEncoding: ByteArray,
    public val markerId: String,
) {
    public companion object {
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        public fun of(
            sessionId: String,
            chunkId: String,
            recordCount: Int,
            contentSha256: String,
            sequences: List<Long>,
        ): DeliveredBatchFields {
            if (sessionId.isEmpty()) throw LossFormatException("delivered_batch_session_empty")
            if (chunkId.isEmpty()) throw LossFormatException("delivered_batch_chunk_empty")
            if (!HEX64.matches(contentSha256))
                throw LossFormatException("delivered_batch_content_sha_not_sha256")
            if (recordCount != sequences.size)
                throw LossFormatException(
                    "delivered_batch_count_mismatch:$recordCount!=${sequences.size}")
            // Throws on empty, negative, duplicate or decreasing — the exact-set
            // rules live in one place and are not restated here.
            val enc = DeliveredSeqCodec.encode(sequences)
            // Decode-verify: the stored bytes must reproduce exactly the set we
            // were handed. An encoder that quietly normalised its input would
            // otherwise produce a record describing a different batch.
            val back = DeliveredSeqCodec.decode(enc, recordCount)
            if (back != sequences) throw LossFormatException("delivered_batch_encoding_not_faithful")
            val canonical = contentSha256.lowercase()
            val id = DeliveredMarkerId.of(sessionId, chunkId, recordCount, hexToBytes(canonical), enc)
            return DeliveredBatchFields(
                sessionId, chunkId, recordCount, canonical, sequences, enc, id)
        }

        public fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i ->
                ((digit(hex[2 * i]) shl 4) or digit(hex[2 * i + 1])).toByte()
            }

        private fun digit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw LossFormatException("delivered_batch_content_sha_not_hex")
        }
    }
}

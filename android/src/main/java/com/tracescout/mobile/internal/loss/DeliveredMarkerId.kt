package com.tracescout.mobile.internal.loss

import java.security.MessageDigest

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. It is `public` only because Kotlin `internal` is
 * compilation-module scoped and every consumer (`:sdk`) lives across the
 * module boundary. a conformance test pins that no type
 * here ever appears in a public API signature of `:sdk` / `:rn` / rn-core.
 */
/**
 * The deterministic identity of one delivered batch.
 *
 * IT IDENTIFIES THE FROZEN BATCH, NOT THE RESPONSE THAT PROVED IT. An
 * `accepted` ack and an exact `duplicate` ack are two proofs of the SAME remote
 * fact — that this exact batch was committed — so they must derive the SAME id.
 * Folding the ack type, a retry number or a timestamp into the identity would
 * create marker A and marker B for one delivery, and both would apply.
 *
 * CANONICAL LENGTH-PREFIXED ENCODING, NEVER DELIMITERS. With a separator
 * scheme `("ab", "c")` and `("a", "bc")` hash identically, so two different
 * batches could collide on identity. Every variable field is preceded by its
 * byte length; the count is fixed-width big-endian.
 *
 * DOMAIN SEPARATION. The prefix keeps this hash from ever equalling a hash
 * computed for another purpose over similar inputs.
 */
public object DeliveredMarkerId {

    private const val DOMAIN = "tracescout-delivered-batch-v1"

    /**
 * @param contentDigest the 32 RAW digest bytes, never hex text. `"AB…"` and
 * `"ab…"` are the same digest, so hashing the TEXT would give one logical
 * batch two marker identities and both would apply.
 * @param sequenceEncoding the CANONICAL bytes from [DeliveredSeqCodec.encode],
 * not a re-derivation. Binding the identity to the stored encoding means a
 * record and its id cannot disagree about which set was delivered.
 *
 * Callers obtain this through [DeliveredBatchFields], which refuses an
 * inconsistent batch before an identity can exist for it.
 */
    public fun of(
        sessionId: String,
        chunkId: String,
        recordCount: Int,
        contentDigest: ByteArray,
        sequenceEncoding: ByteArray,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(DOMAIN.toByteArray(Charsets.UTF_8))
        lp(md, sessionId.toByteArray(Charsets.UTF_8))
        lp(md, chunkId.toByteArray(Charsets.UTF_8))
        md.update(be8(recordCount.toLong()))
        if (contentDigest.size != 32) throw LossFormatException("marker_content_digest_not_32_bytes")
        lp(md, contentDigest)
        lp(md, sha256(sequenceEncoding))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun lp(md: MessageDigest, b: ByteArray) {
        md.update(be8(b.size.toLong()))
        md.update(b)
    }

    private fun be8(v: Long) = ByteArray(8) { i -> ((v ushr (56 - 8 * i)) and 0xFF).toByte() }

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)
}

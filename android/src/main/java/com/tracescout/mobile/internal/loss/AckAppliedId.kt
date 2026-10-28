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
 * **R4 — THE CONTENT-ADDRESSED PRODUCTION IDENTITY OF AN `AckApplied` (F3-P1b the specification).**
 *
 * The `intentId` of a settlement is DETERMINISTIC over exactly five facts:
 *
 * ```
 * sessionId · settlesCheckpointId · acceptedThroughSequence · canonical acceptedCounts · terminal
 * ```
 *
 * and over NOTHING else — not the ack `state`, not a clock, a counter, a nonce, an
 * attempt number, a chunk id or a host. the capture specification: *"an `accepted` ack and an exact
 * `duplicate` ack are two proofs of one remote fact and must produce the SAME marker.
 * The ack type is deliberately excluded from the identity."* Idempotency is therefore a
 * property of the durable record (core's `AlreadyApplied` by `intentId`), not a
 * "have we already settled?" guard in a writer.
 *
 * `terminal` IS THE SERVER'S `ack.terminal` (RULING Q1), never the device's
 * `pending.claimsTerminal` and never a relation derived from the two.
 *
 * CANONICAL LENGTH-PREFIXED ENCODING, NEVER DELIMITERS — the [DeliveredMarkerId]
 * precedent. With a separator scheme `("ab","c")` and `("a","bc")` hash identically, and
 * `sessionId` / `settlesCheckpointId` are adjacent here. Every variable field is preceded
 * by its 8-byte big-endian byte length; the boolean is one fixed byte.
 *
 * DOMAIN SEPARATION. The prefix keeps this hash from ever equalling a hash computed
 * for another purpose over similar inputs — a delivered-batch marker in particular.
 *
 * PINNED BY A LITERAL EXPECTED DIGEST for a fixed input tuple (`AckAppliedIdTest`), so
 * a refactor that changes the rendering fails loudly instead of silently minting a second
 * identity for one remote fact — which would produce TWO durable records where the capture specification
 * requires one (F3-P1b the specification, trap 2).
 */
public object AckAppliedId {

    private const val DOMAIN: String = "tracescout-ack-applied-v1"

    /**
 * @param acceptedThroughSequence the CANONICAL DECIMAL STRING exactly as the ack
 * carried it (the binder has already proven it canonical and equal to the frozen
 * `Long`; rendering the `Long` would be a second rendering of one fact).
 * @param canonicalCounts the canonical rendering from [LossCounts.encode] — every
 * family, shared order — so an absent family and an explicit zero cannot mint two
 * identities for one remote fact (R4-d).
 */
    public fun of(
        sessionId: String,
        settlesCheckpointId: String,
        acceptedThroughSequence: String,
        canonicalCounts: String,
        terminal: Boolean,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(DOMAIN.toByteArray(Charsets.UTF_8))
        lp(md, sessionId.toByteArray(Charsets.UTF_8))
        lp(md, settlesCheckpointId.toByteArray(Charsets.UTF_8))
        lp(md, acceptedThroughSequence.toByteArray(Charsets.UTF_8))
        lp(md, canonicalCounts.toByteArray(Charsets.UTF_8))
        md.update(byteArrayOf(if (terminal) 1 else 0))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun lp(md: MessageDigest, b: ByteArray) {
        md.update(be8(b.size.toLong()))
        md.update(b)
    }

    private fun be8(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (56 - 8 * i)) and 0xFF).toByte() }
}

package com.tracescout.mobile.core

import java.math.BigInteger

/**
 * The boundary where in-memory types become protocol types.
 *
 * Kotlin holds `monotonicNanos` as a `Long` because that is the correct native
 * type. The WIRE form is a canonical unsigned decimal STRING, because JSON has
 * exactly one number type and a conforming parser on the other end may realise
 * it as a double. 2^53 nanoseconds is about 104 days of uptime — a threshold
 * real devices cross — so a JSON number silently loses precision on precisely
 * the long-lived sessions that matter most, in the one field the protocol calls
 * authoritative for ordering.
 *
 * This is deliberately a separate, tiny surface rather than a convention: the
 * conversion is easy to get right once and easy to forget at the fifth call
 * site, and forgetting it produces data that looks fine and orders wrong.
 */
object WireFormat {

    /** Canonical unsigned decimal: no sign, no leading zeros, ≤20 digits. */
    private val CANONICAL = Regex("^(0|[1-9][0-9]{0,19})$")

    /**
 * Encode a monotonic reading for the wire.
 *
 * A NEGATIVE value is a programming error, not a datum: `System.nanoTime`
 * is monotonic from an arbitrary origin and differences are what matter, so
 * a negative reading means someone passed a delta or a sentinel. Throwing
 * beats emitting an event that fails ingest validation on the far side,
 * where the cause is no longer visible.
 */
    @JvmStatic
    fun monotonicToWire(nanos: Long): String {
        require(nanos >= 0) { "monotonicNanos must be non-negative, got $nanos" }
        return nanos.toString()
    }

    /**
 * Decode a wire value back to a `Long`.
 *
 * Parsed through [BigInteger] first so a value in the unsigned-64 range
 * above `Long.MAX_VALUE` is REFUSED rather than silently wrapping to a
 * negative number — wrapping would reverse the ordering of the two events
 * either side of the boundary.
 */
    @JvmStatic
    fun monotonicFromWire(wire: String): Long {
        require(CANONICAL.matches(wire)) { "monotonicNanos is not canonical decimal: '$wire'" }
        val v = BigInteger(wire)
        require(v.bitLength() < 64) { "monotonicNanos exceeds Long range: '$wire'" }
        return v.toLong()
    }

    /** True when a received string is a value this binding can carry exactly. */
    @JvmStatic
    fun isCanonicalMonotonic(wire: String): Boolean =
        CANONICAL.matches(wire) && BigInteger(wire).bitLength() < 64
}

package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. `public` only because Kotlin `internal` is
 * compilation-module scoped.
 */

/**
 * ADDITIONAL AUTHENTICATED DATA for one AEAD frame — a DISTINCT STATIC TYPE,
 * never a `ByteArray` (F3-P0 the specification, X1-B2).
 *
 * ## Why this type exists at all
 *
 * The accepted signature is `seal(key, plaintext: ByteArray, random)`. Adding a
 * `ByteArray` AAD puts **two adjacent parameters of the same static type**:
 *
 * ```
 * seal(key, plaintext: ByteArray, aad: ByteArray, random) <-- REJECTED DESIGN
 * ^^^^^^^^^^^^^^^^^^^^^^^^^^
 * transposing these two COMPILES.
 * ```
 *
 * A transposition that compiles seals the AAD as the record and authenticates
 * the record as the AAD. The frame then opens only for a reader that makes the
 * same mistake, and the record's real bytes are never stored — **silent data
 * loss, with a green build.** A distinct type makes the transposition a
 * COMPILE ERROR, which is the property under test, not the
 * annotation this class happens to carry.
 *
 * ## Why it lives HERE and not beside `GcmFrame`
 *
 * The module dependency runs ONE way: `:sdk` declares
 * `implementation("com.tracescout.mobile:mobile-loss-core")` and this module
 * declares no dependency on `:sdk` at all. the specification puts AAD assembly in core (it
 * is pure byte work with no key), so core is where the builder that RETURNS an
 * `Aad` lives — and a type declared `internal` in `:sdk` would be invisible to
 * it. Inverting the dependency to make `Aad` `:sdk`-internal would destroy D1
 * ("the pure module never sees a key") and make the pure module depend on an
 * Android module, for a visibility modifier. **FORBIDDEN**.
 *
 * ## Contents
 *
 * AUTHENTICATION-CONTEXT BYTES ONLY. Never a key, never a secret, never key
 * material of any kind. D1 is preserved, and it is preserved BECAUSE an `Aad`
 * carries no key.
 *
 * ## The copy is deliberate
 *
 * A value class over a `ByteArray` still holds a shared mutable reference, so
 * [of] **copies on construction**. An AAD here is 46–190 bytes
 * against a 1.5–3.4 ms strict admission, so the copy is unmeasurable on that
 * path and it removes an aliasing hazard in which a caller mutates the array
 * after sealing and the reader then recomputes a different AAD. [bytes] copies
 * on the way out for the same reason.
 */
@JvmInline
public value class Aad private constructor(private val raw: ByteArray) {

    /** The authenticated bytes. A COPY — see the class doc. */
    public fun bytes(): ByteArray = raw.copyOf()

    public val size: Int get() = raw.size

    /**
 * True for ANY zero-length AAD. The empty path skips `updateAAD` entirely.
 *
 * **NOT "the canonical none-value only" — the earlier wording here said that
 * and it was FALSE.** `Aad.of(ByteArray(0))` is also empty and is also
 * indistinguishable from [NONE] at this predicate, which is exactly why "is this
 * frame bound?" must NEVER be answered by asking a value whether it is empty.
 * Behaviourally the two are identical and that is correct — an empty AAD is an
 * unbound seal however it was spelled. What keeps the question answerable is the
 * SOURCE PIN: an inline `Aad.of(ByteArray(0))` is forbidden at
 * every call site, so the canonical name is the only way an unbound seal is
 * written. That pin is the load-bearing rule; this predicate is not, and a
 * reader who believed the old sentence would have thought it was.
 */
    public fun isEmpty(): Boolean = raw.isEmpty()

    public companion object {
        /**
 * The ONLY constructor. Copies, so a later mutation of [src] cannot
 * change what a reader recomputes.
 */
        public fun of(src: ByteArray): Aad = Aad(src.copyOf())

        /**
 * THE CANONICAL NONE-VALUE, and the only way to express "unbound".
 *
 * Every unbound call site names this. An inline `Aad.of(ByteArray(0))`
 * is FORBIDDEN and has its own control: one value, one
 * home, one name at the call site — otherwise "is this frame bound?"
 * becomes a question about a call site's spelling.
 *
 * `GcmFrame.NO_AAD` in `:sdk` aliases THIS value; it is not a second
 * none-value. `:rn` cannot name `GcmFrame` at all (`internal object`,
 * consumed through a non-transitive `implementation` dependency), which
 * is why the compatibility constructions need no source change.
 */
        public val NONE: Aad = Aad(ByteArray(0))
    }
}

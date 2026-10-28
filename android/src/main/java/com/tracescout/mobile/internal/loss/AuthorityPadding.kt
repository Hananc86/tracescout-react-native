package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. `public` only because Kotlin `internal` is
 * compilation-module scoped.
 */

/**
 * FRAME-SIZE PRIVACY — the in-AEAD size ladder (F3-P0 the specification, decision a product decision).
 *
 * ## What P7 measured, and why a ladder is the answer
 *
 * Keyless, with no key at all, a reader of the app data directory learns every
 * frame's exact plaintext length. Measured on the accepted format:
 *
 * ```
 * F2 HighWaterAdvanced 18 · AdmissionIntent 32/34 · IntentFinalized 48/50 ·
 * CheckpointDisabled 53 · DeliveredBatch 225 band [18.225]
 * F3 TerminalRequested 8/13 AckApplied 192/193
 * F3 sizes colliding with any F2 size: NONE
 * ```
 *
 * `TerminalRequested` is the only 1-field kind, so it is the smallest record the
 * format can produce and sits **strictly below the whole F2 band** — and it
 * discloses **terminal state**. Both F3 kinds would be uniquely identifiable by
 * frame size alone.
 *
 * ## The layout
 *
 * ```
 * [realLen:4 BE][payload realLen bytes][zero padding to the rung]
 * ```
 *
 * * padding is **inside** the AEAD — outside it, it would be editable;
 * * the **real length is authenticated**, because it is inside the ciphertext;
 * * padding bytes **must be zero and are verified**. Non-zero padding is a
 * covert channel a future writer could smuggle content through, and zeros also
 * keep the encoding **deterministic**, which the the specification tail chain needs.
 *
 * ## a product decision, and why 1024 is MINIMAL rather than conservative
 *
 * M3 measured the grammar maximum over [BOUNDED_KINDS] at **698 B**; `+ 4` for
 * the in-AEAD length prefix is 702, and the smallest ladder bucket ≥ 702 is
 * **1024**. Rung 256 and rung 512 are arithmetically REFUTED under BOTH the
 * accepted encoding and the rejected compact one. Frame size is irrelevant to
 * the strict-admission cost in this range (measured: < 11 % and non-monotonic
 * across 77/288/1056-byte frames), so the rung choice does not feed back into
 * a product decision.
 *
 * ## THE ABSOLUTE RULE, AND ITS THREE DISCLOSED EXEMPTIONS
 *
 * *Every record kind's grammar-maximum plaintext must fit rung 1.* A kind that
 * does not is a TEST FAILURE, not a silent second band. Three subjects are
 * outside [BOUNDED_KINDS] and outside that rule, each disclosed where it is
 * argued:
 *
 * ```
 * DeliveredBatch hex(sequenceEncoding) is bounded only by MAX_FIELD_BYTES the specification
 * the snapshot frame `entries` and `deliveredBatches` grow with retained state the specification
 * the floors body 22 + 4096 x (20 + 128) = 606,230 B the specification
 * ```
 *
 * **A CONSUMER MAY NEVER DERIVE A FRAME COUNT BY DIVIDING FILE SIZE BY A RUNG
 * SIZE**. `Wpresent` is obtained by PARSING the length prefixes and `C`
 * comes from the authenticated witness. The honest keyless statement is
 * `size / (largestRung + 32) <= count <= size / (rung1 + 32)`, collapsing to an
 * exact value only when every frame is rung 1.
 */
public object AuthorityPadding {

    /** D-2a. The smallest ladder bucket that admits the 702-byte bounded maximum. */
    public const val RUNG1: Int = 1024

    /**
 * The ladder. The top rung is 1,048,576 because `DeliveredBatch`'s grammar
 * maximum is 262,566 B and a padded frame must have a rung to land in — and
 * because the floors body frame's is 606,230 B.
 */
    public val RUNGS: List<Int> = listOf(1024, 4096, 32768, 262144, 1048576)

    /** The in-AEAD real-length prefix. */
    public const val LENGTH_PREFIX_BYTES: Int = 4

    /**
 * THE SUBJECT OF the specification's ABSOLUTE RULE — every record kind EXCEPT
 * `DeliveredBatch`. Named here so the acceptance property is computed over a
 * production set rather than over a literal a test transcribes, and so the
 * control that ADDS `DeliveredBatch` has something to mutate.
 */
    public val BOUNDED_KINDS: List<String> = AuthorityCodec.RECORD_KINDS.filter { it != "dbatch" }

    /** The smallest rung that admits [plaintextLength] bytes of payload. */
    public fun rungFor(plaintextLength: Int): Int =
        RUNGS.firstOrNull { plaintextLength + LENGTH_PREFIX_BYTES <= it }
            ?: throw LossFormatException("padding_no_rung_for_length:$plaintextLength")

    /** Pad [plaintext] to its rung. The result is what the AEAD seals. */
    public fun pad(plaintext: ByteArray): ByteArray {
        val rung = rungFor(plaintext.size)
        val out = ByteArray(rung)
        AuthorityContainer.writeIntBE(out, 0, plaintext.size)
        System.arraycopy(plaintext, 0, out, LENGTH_PREFIX_BYTES, plaintext.size)
        return out
    }

    /**
 * Recover the payload from an opened (decrypted) padded plaintext.
 *
 * EVERY CHECK IS A TYPED REFUSAL, and the rung check comes FIRST: a
 * length that is plausible for an unknown rung must not be read as a valid
 * payload of a rung the format does not have.
 */
    public fun unpad(padded: ByteArray): ByteArray {
        if (!RUNGS.contains(padded.size)) throw LossFormatException("padding_rung_unknown:${padded.size}")
        val realLen = AuthorityContainer.readIntBE(padded, 0)
        if (realLen < 0 || realLen > padded.size - LENGTH_PREFIX_BYTES)
            throw LossFormatException("padding_length_invalid")
        for (i in LENGTH_PREFIX_BYTES + realLen until padded.size)
            if (padded[i].toInt() != 0) throw LossFormatException("padding_not_zero")
        return padded.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + realLen)
    }

    /**
 * The on-disk size of one padded frame at [rung]: rung + the 4-byte outer
 * length prefix + the 12-byte IV + the 16-byte GCM tag. At rung 1 that is
 * **1056 B**, which is what makes the padded witness file
 * `32 + 1056 + 1056 = 2144 B` — the exact `witnessBytes` configuration M2
 * measured, so the strict-cost measurement already covers the shipped size.
 */
    public fun onDiskFrameBytes(rung: Int): Int = rung + 4 + 12 + 16
}

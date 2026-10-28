package com.tracescout.mobile.queue

import com.tracescout.mobile.internal.loss.Aad
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * THE ONE AES-GCM FRAME. `[len:4 BE][iv:12][ciphertext + 16-byte tag]`.
 *
 * EXTRACTED, NOT INVENTED (F2). `EncryptedEventStore` sealed and opened
 * frames inline in three places (append, rewrite, readAll) and the F2
 * attribution authority needs the SAME framing for its snapshot and WAL — the
 * D1 rule is "one key discipline, not a second crypto container", and a second
 * copy of the seal is how two containers start to differ. The layout is
 * BYTE-IDENTICAL to what the store wrote before the extraction: pinned by
 * `GcmFrameCompatTest`, which opens a queue file the PRE-extraction code wrote
 * (`src/test/resources/gcmframe/queue.bin`, generated 2026-09-25 on the F1
 * tree) through `readAll`, and opens frames sealed here through the same
 * reader.
 *
 * Every frame carries its own IV, so a frame decrypts independently of its
 * neighbours; the auth tag is the integrity check — a truncated or bit-flipped
 * frame fails authentication, never partially parses.
 */
internal object GcmFrame {
    /**
 * THE CANONICAL NONE-VALUE for the [Aad] parameter, and the ONLY name an
 * unbound call site uses (F3-P0 the specification).
 *
 * IT IS NOT A SECOND NONE-VALUE. It aliases `Aad.NONE`, which is declared
 * in `mobile-loss-core` because the module dependency runs ONE way and AAD
 * assembly is pure byte work with no key. One value, one home, one
 * name at the call site — an inline `Aad.of(ByteArray(0))` at a call site is
 * FORBIDDEN and has its own control, because otherwise "is
 * this frame bound?" becomes a question about a call site's spelling.
 *
 * `:rn` CANNOT NAME THIS AT ALL. `GcmFrame` is `internal object` to `:sdk`
 * and `:rn` depends on `:sdk` with `implementation`, which is not transitive
 * for compilation — so the five shipped compatibility constructions and the
 * three harness ones need NO SOURCE CHANGE WHATSOEVER. They are
 * affected only transitively through `EncryptedEventStore`'s own three call
 * sites, which is precisely why "the class is byte-unchanged" is the
 * load-bearing assertion and not "the call sites were updated".
 */
    val NO_AAD: Aad = Aad.NONE

    const val IV_BYTES: Int = 12
    const val TAG_BITS: Int = 128
    const val TRANSFORM: String = "AES/GCM/NoPadding"
    /** A frame larger than this is treated as corruption, not a huge record. */
    const val MAX_FRAME_BYTES: Int = 4 * 1024 * 1024

    /**
 * Seal [plaintext] as one complete frame, including the 4-byte length prefix,
 * authenticated against [aad].
 *
 * **THE AAD PARAMETER IS A DISTINCT STATIC TYPE, NEVER A `ByteArray`**
 * (F3-P0 the specification, X1-B2). With a `ByteArray` AAD the signature would carry two
 * ADJACENT parameters of the same static type, and a transposition of those
 * two COMPILES — sealing the AAD as the record and authenticating the record
 * as the AAD, so the frame opens only for a reader that makes the same
 * mistake and the record's real bytes are never stored. Silent data loss,
 * with a green build. The strong type makes that a compile error.
 *
 * **NO DEFAULT**, for the reason `AppendResult.durability` has no default:
 * a default of "unbound" would silently give every future call site the
 * behaviour the specification exists to remove. Every construction site is in-tree, so
 * requiring the argument costs nothing and buys a compiler check.
 */
    fun seal(key: SecretKey, plaintext: ByteArray, aad: Aad, random: SecureRandom): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        // THE EMPTY PATH SKIPS `updateAAD` ENTIRELY — see `applyAad`.
        applyAad(cipher, aad)
        val ct = cipher.doFinal(plaintext)
        val body = ByteArray(IV_BYTES + ct.size)
        System.arraycopy(iv, 0, body, 0, IV_BYTES)
        System.arraycopy(ct, 0, body, IV_BYTES, ct.size)
        val frame = ByteArray(4 + body.size)
        writeInt(frame, 0, body.size)
        System.arraycopy(body, 0, frame, 4, body.size)
        return frame
    }

    /**
 * Open one frame BODY (the bytes after the length prefix), authenticated
 * against [aad]. Throws on any authentication failure.
 *
 * A frame sealed with one `Aad` cannot be opened with another, IN BOTH
 * DIRECTIONS — and the observable difference is the **TAG**, not the
 * ciphertext body: GCM's confidentiality is CTR over `(key, IV)`, which the
 * AAD does not enter, so the body is IDENTICAL and only the tag differs
 *. A test written as "the AAD changes the ciphertext" would be
 * asserting a bug — it passes today only because the tag is appended to the
 * body, and it would fail the moment anyone compared the body alone. the specification B15
 * asserts the honest triple: body identical, tag differs, cross-open refused
 * both ways.
 */
    fun open(key: SecretKey, body: ByteArray, offset: Int, length: Int, aad: Aad): ByteArray {
        val iv = body.copyOfRange(offset, offset + IV_BYTES)
        val ct = body.copyOfRange(offset + IV_BYTES, offset + length)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        applyAad(cipher, aad)
        return cipher.doFinal(ct)
    }

    /**
 * **THE `NO_AAD` COMPATIBILITY CONTRACT IS STRUCTURAL, NOT A PROVIDER
 * MEASUREMENT**.
 *
 * ```
 * IF the Aad is EMPTY, take literally today's crypto path:
 * DO NOT call cipher.updateAAD AT ALL.
 * seal/open are then byte-for-byte the current behaviour, BY CONSTRUCTION.
 * ```
 *
 * **WHY THE MEASUREMENT WAS NOT ENOUGH.** It is true and reproducible that
 * `updateAAD(empty)` yields the same tag as supplying no AAD — but that was
 * measured on the JVM, whose provider is **SunJCE**, and the DEVICE provider
 * is **Conscrypt**. A provider is free to treat "AAD supplied, length zero"
 * differently from "no AAD supplied", and `GcmFrameCompatTest` runs on the JVM
 * while the shipped code runs on Conscrypt — so the byte test could pass on
 * the provider that was never at risk while the device differed. Skipping the
 * call makes the compatibility TRUE ON EVERY PROVIDER, because it is not a
 * different call, it is the same call sequence the accepted code already
 * makes: over both whole function bodies the accepted implementation contains
 * no `updateAAD` at all.
 *
 * **ONLY A SOURCE PIN CAN CATCH THE REGRESSION.** Swapping this skip for
 * `updateAAD(empty)` produces byte-identical output on SunJCE, so every
 * byte-comparison row stays green; the specification B14's negative control is exactly
 * that mutation, and the pin is what sees it.
 */
    private fun applyAad(cipher: Cipher, aad: Aad) {
        if (aad.isEmpty()) return
        cipher.updateAAD(aad.bytes())
    }

    fun writeInt(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 24).toByte()
        buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte()
        buf[off + 3] = v.toByte()
    }

    fun readInt(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 24) or
            ((buf[off + 1].toInt() and 0xff) shl 16) or
            ((buf[off + 2].toInt() and 0xff) shl 8) or
            (buf[off + 3].toInt() and 0xff)
}

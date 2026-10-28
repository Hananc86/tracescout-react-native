package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. `public` only because Kotlin `internal` is
 * compilation-module scoped.
 */

/**
 * THE AUTHORITY-V2 CONTAINER — the cleartext preamble, the four file roles, and
 * the AAD assembly every v2 frame is authenticated against (F3-P0 the specification, the specification, the specification).
 *
 * PURE BYTE WORK, NO KEY, NO FILE. This object owns the magic, the roles, the
 * preamble layout and the AAD profiles; `:sdk`'s `AuthorityV2ContainerIo` owns
 * the AEAD and the file, `EncryptedAuthorityStore` owns file names, and
 * `AuthorityDurableWrite` owns durability. One owner per name, and this
 * one is the one that can be tested on the JVM with no crypto at all.
 *
 * ## The rule this object exists to make structural
 *
 * A v2 reader decides **what kind of file this is** from a cleartext,
 * structurally-parsed preamble, **before any AEAD attempt**, and never retries a
 * failed open with different parameters. There is no "try with AAD, then
 * without", no "try the authority key, then the queue key", no "try v2, then
 * v1". The precedent is already accepted in F2: `V3Legacy.signatureOf` runs
 * before the AEAD attempt precisely so a known format is *named* rather than
 * reported as cryptographic corruption.
 *
 * ## Preamble — 32 bytes, cleartext, authenticated WHOLESALE
 *
 * ```
 * off len field notes
 * 0 8 MAGIC ASCII 'T','S','A','U','T','H','0','2'
 * 8 1 containerVersion = 2
 * 9 1 fileRole 1=snapshot 2=wal 3=head 4=floors
 * 10 1 keyDomain 1 = authority-v2 alias
 * 11 1 reserved = 0
 * 12 16 fileInstanceId 128 random bits, set once when the file is created
 * 28 4 preambleLength = 32, big-endian
 * ```
 *
 * **THE WHOLE 32 BYTES GO INTO THE AAD OF EVERY FRAME, VERBATIM** — including
 * `reserved` and `preambleLength`, and including any byte a future reader does
 * not yet interpret. A field-by-field AAD is only as complete as its list, and
 * the list goes stale the first time the preamble grows; binding the BYTES makes
 * "editing any preamble byte breaks authentication" literally true by
 * construction.
 *
 * **The preamble is cleartext but NOT TRUSTED.** It is read so the diagnosis
 * can be typed instead of cryptographic; every claim it makes is re-stated
 * inside the authenticated header frame and checked there ([checkHeader]), so an
 * edit is reported as `container_instance_mismatch` rather than as a generic
 * auth failure — the same "name it, do not call it corruption" rule.
 */
public object AuthorityContainer {

    /** ASCII `TSAUTH02`. */
    public val MAGIC: ByteArray = byteArrayOf(0x54, 0x53, 0x41, 0x55, 0x54, 0x48, 0x30, 0x32)

    public const val CONTAINER_VERSION: Int = 2

    /** The ONE key domain this format admits — the dedicated authority-v2 alias. */
    public const val KEY_DOMAIN_AUTHORITY_V2: Int = 1

    public const val PREAMBLE_LENGTH: Int = 32

    /** The AAD domain separator. ASCII `TSAAD2`. */
    private val AAD_DOMAIN: ByteArray = byteArrayOf(0x54, 0x53, 0x41, 0x41, 0x44, 0x32)

    /** The tail-chain domain separator. ASCII `TSAWAL2`. */
    private val CHAIN_DOMAIN: ByteArray = byteArrayOf(0x54, 0x53, 0x41, 0x57, 0x41, 0x4C, 0x32)

    public const val FILE_INSTANCE_BYTES: Int = 16

    /**
 * The four file roles. **Distinct file kinds, not a hint**: a `snapshot`
 * frame moved into a `head` file fails the specification cryptographically, and the role
 * mismatch is refused structurally first.
 */
    public enum class Role(public val wire: Int, public val label: String) {
        SNAPSHOT(1, "snapshot"),
        WAL(2, "wal"),
        HEAD(3, "head"),
        FLOORS(4, "floors"),
        ;

        public companion object {
            public fun ofWire(n: Int): Role? = values().firstOrNull { it.wire == n }
            public fun ofLabel(s: String): Role? = values().firstOrNull { it.label == s }
        }
    }

    /** A parsed preamble, plus the exact 32 bytes it was parsed from. */
    public class Preamble internal constructor(
        public val role: Role,
        public val fileInstanceIdHex: String,
        /** The verbatim 32 bytes. A COPY on the way out — this is AAD input. */
        private val raw: ByteArray,
    ) {
        public fun bytes(): ByteArray = raw.copyOf()
    }

    /**
 * THE CONTAINER BAND of the specification (steps 4–11), as a total function.
 *
 * ONE IMPLEMENTATION, PARAMETERISED BY THE EXPECTED ROLE — applied to
 * **EVERY** role: snapshot, wal, head AND floors. v3 stated the order once and then gave the reopen machine a
 * WAL input with only two states, so a WAL whose CONTAINER was unreadable
 * had no row at all and fell into an extent verdict that names the record
 * BODY. A refusal that names the wrong layer is worse than an unnamed one,
 * because it is actionable in the wrong direction. A new container check
 * added for any role gets the other three for free, and a role that quietly
 * skips the band is what the specification A7 catches.
 *
 * Returns the reason on refusal and `null` on success (the parsed preamble
 * is handed back through [out]); the caller maps the reason into its role's
 * `<role>_<stage>` family, so the grammar mirrors by construction.
 */
    public sealed class PreambleResult {
        public data class Ok(val preamble: Preamble) : PreambleResult()
        /** A typed container-band stage, WITHOUT the role prefix — the caller adds it. */
        public data class Refused(val stage: String) : PreambleResult()
    }

    public fun parsePreamble(bytes: ByteArray, expect: Role): PreambleResult {
        // 4 length >= 32
        if (bytes.size < PREAMBLE_LENGTH) return PreambleResult.Refused("preamble_truncated")
        // 5 MAGIC
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return PreambleResult.Refused("not_authority_container")
        // 6 containerVersion
        val ver = bytes[8].toInt() and 0xff
        if (ver != CONTAINER_VERSION) return PreambleResult.Refused("container_version_unsupported:$ver")
        // 7 fileRole in 1.4
        val roleByte = bytes[9].toInt() and 0xff
        val role = Role.ofWire(roleByte) ?: return PreambleResult.Refused("container_role_unknown:$roleByte")
        // 8 role == expected
        if (role != expect) return PreambleResult.Refused("container_role_mismatch:${role.label}!=${expect.label}")
        // 9 keyDomain
        val domain = bytes[10].toInt() and 0xff
        if (domain != KEY_DOMAIN_AUTHORITY_V2) return PreambleResult.Refused("container_key_domain_unknown:$domain")
        // 10 reserved != 0
        if (bytes[11].toInt() != 0) return PreambleResult.Refused("container_reserved_nonzero")
        // 11 preambleLength self-describing, so a LONGER future preamble is refused
        // by length rather than misread as a short one whose trailing bytes parse.
        val declared = readIntBE(bytes, 28)
        if (declared != PREAMBLE_LENGTH) return PreambleResult.Refused("container_preamble_length:$declared")
        val raw = bytes.copyOfRange(0, PREAMBLE_LENGTH)
        return PreambleResult.Ok(Preamble(role, hex(raw, 12, FILE_INSTANCE_BYTES), raw))
    }

    /** Build the 32 cleartext preamble bytes for a NEW file. */
    public fun writePreamble(role: Role, fileInstanceId: ByteArray): ByteArray {
        if (fileInstanceId.size != FILE_INSTANCE_BYTES)
            throw LossFormatException("file_instance_id_length:${fileInstanceId.size}")
        val out = ByteArray(PREAMBLE_LENGTH)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        out[8] = CONTAINER_VERSION.toByte()
        out[9] = role.wire.toByte()
        out[10] = KEY_DOMAIN_AUTHORITY_V2.toByte()
        out[11] = 0
        System.arraycopy(fileInstanceId, 0, out, 12, FILE_INSTANCE_BYTES)
        writeIntBE(out, 28, PREAMBLE_LENGTH)
        return out
    }

    // ───────────────────────────────────────────────────────── AAD profiles

    /**
 * AAD PROFILE H — the header frame, ordinal 0.
 *
 * ```
 * AAD_H = 'TSAAD2' || preamble[0.31] || ordinal:8 BE (= 0)
 * ```
 *
 * Deliberately **no session id and no generation** — they are the header's
 * own payload, so including them would be circular.
 */
    public fun aadHeader(preamble: Preamble): Aad {
        val p = preamble.bytes()
        val out = ByteArray(AAD_DOMAIN.size + PREAMBLE_LENGTH + 8)
        System.arraycopy(AAD_DOMAIN, 0, out, 0, AAD_DOMAIN.size)
        System.arraycopy(p, 0, out, AAD_DOMAIN.size, PREAMBLE_LENGTH)
        writeLongBE(out, AAD_DOMAIN.size + PREAMBLE_LENGTH, 0L)
        return Aad.of(out)
    }

    /**
 * AAD PROFILE B — body frames, ordinal ≥ 1.
 *
 * ```
 * AAD_B = 'TSAAD2' || preamble[0.31] || ordinal:8 BE || generation:8 BE
 * || sessionIdLen:2 BE || sessionId UTF-8
 * ```
 *
 * `generation` and `sessionId` come from the **AUTHENTICATED HEADER**,
 * never from a file name and never from the caller's hope. The AAD itself is
 * never stored — the reader recomputes it from the container and the header —
 * so it discloses nothing to a keyless observer and costs no bytes.
 *
 * `familySetDigest` is GONE: with the accepted
 * name-carrying counts encoding retained, a family reorder is harmless and a
 * foreign family name is already refused `family_not_canonical`, so the
 * digest would newly make a pure reorder of `LossFamilies.ALL` a hard format
 * break for no measured safety gain.
 */
    public fun aadBody(preamble: Preamble, ordinal: Long, generation: Long, sessionId: String): Aad {
        val sid = sessionId.toByteArray(Charsets.UTF_8)
        if (sid.size > 0xFFFF) throw LossFormatException("aad_session_id_too_long:${sid.size}")
        val p = preamble.bytes()
        val out = ByteArray(AAD_DOMAIN.size + PREAMBLE_LENGTH + 8 + 8 + 2 + sid.size)
        var o = 0
        System.arraycopy(AAD_DOMAIN, 0, out, o, AAD_DOMAIN.size); o += AAD_DOMAIN.size
        System.arraycopy(p, 0, out, o, PREAMBLE_LENGTH); o += PREAMBLE_LENGTH
        writeLongBE(out, o, ordinal); o += 8
        writeLongBE(out, o, generation); o += 8
        out[o] = ((sid.size ushr 8) and 0xff).toByte(); out[o + 1] = (sid.size and 0xff).toByte(); o += 2
        System.arraycopy(sid, 0, out, o, sid.size)
        return Aad.of(out)
    }

    // ────────────────────────────────────────────────── header frame payload

    /**
 * THE AUTHENTICATED HEADER at ordinal 0 — **exactly 4 fields**.
 *
 * ```
 * fileRole name | sessionId (empty for role=floors) | generation | fileInstanceId hex
 * ```
 *
 * 4, not 5. v2 carried a `familySetDigest`; the specification removes it.
 *
 * `generation` in the header is what AUTHORIZES reading a generation out
 * of a filename at all. the specification R10 selects a CANDIDATE generation from the
 * file name when no witness is available; the candidate is accepted only
 * after [checkHeader] passes. The filename is a hint; the authenticated
 * header is the authority. Without this field, "adopt the highest-numbered
 * snapshot" would be inference from a name a restore tool controls.
 */
    public data class Header(
        val role: Role,
        /** Empty for [Role.FLOORS] — floors are per INSTALL, not per session. */
        val sessionId: String,
        val generation: Long,
        val fileInstanceIdHex: String,
    )

    /**
 * The semantic checks of the specification step 13, run after the header AEAD-opens and
 * BEFORE any body frame is decrypted. Returns the typed refusal, or `null`.
 *
 * This is what refuses a **whole-file transplant**: copying A's entire WAL
 * (preamble + frames) into B's directory keeps the instance id consistent
 * with its frames, so AAD alone cannot see it — the authenticated header's
 * session id can.
 *
 * [nameGeneration] is the generation encoded in the FILE NAME for the roles
 * whose name carries one (`snapshot`, `wal`); pass `null` for `head` and
 * `floors`, whose names do not.
 */
    public fun checkHeader(
        header: Header,
        preamble: Preamble,
        expectSessionId: String,
        nameGeneration: Long?,
    ): String? {
        if (header.fileInstanceIdHex != preamble.fileInstanceIdHex) return "container_instance_mismatch"
        if (header.role != preamble.role) return "container_role_mismatch:${header.role.label}!=${preamble.role.label}"
        if (preamble.role == Role.FLOORS) {
            if (header.sessionId.isNotEmpty()) return "floors_header_session_present"
        } else {
            if (header.sessionId != expectSessionId) return "session_mismatch"
        }
        if (nameGeneration != null && header.generation != nameGeneration)
            return "container_generation_name_mismatch:$nameGeneration!=${header.generation}"
        return null
    }

    // ─────────────────────────────────────────────────────────── tail chain

    /**
 * THE WAL TAIL CHAIN — an incremental chain over the SEALED bytes as
 * they lie on disk, so an append does not have to re-read the WAL.
 *
 * ```
 * digest_0 = SHA-256( "TSAWAL2" || walFileInstanceId || generation )
 * digest_n = SHA-256( digest_{n-1} || SHA-256( sealed frame n ) )
 * ```
 *
 * Over **CIPHERTEXT**, not plaintext, because the question is "are these
 * exact bytes still on disk" — and because a plaintext-derived digest stored
 * anywhere is the sidecar D1 rejected.
 *
 * Domain-separated and instance-bound, so a chain from another file
 * cannot be spliced. The instance-id seed is the SECOND of the two
 * mechanisms that name a same-generation WAL substitution, and at `C == 0`
 * it is the one that makes the pair load-bearing: with the id out of the
 * seed, `digest_0` is IDENTICAL for two instances of the same generation, so
 * a foreign empty WAL silently becomes the live one.
 *
 * PREFIX-VERIFIABLE, and the specification R19 depends on that: recomputing over the
 * first `C` frames must reproduce `witness.walTailDigest` exactly, which is
 * what proves the first `C` frames are byte-identical to what the witness
 * committed rather than merely the same NUMBER of frames over a different
 * history.
 *
 * THE SEED USES THE RAW 16 INSTANCE BYTES, not their hex. The preamble
 * holds them raw; one spelling, pinned here, so the chain a writer computes
 * and the chain a reader recomputes cannot differ by an encoding choice.
 */
    public fun chainSeed(walFileInstanceId: ByteArray, generation: Long): ByteArray {
        if (walFileInstanceId.size != FILE_INSTANCE_BYTES)
            throw LossFormatException("file_instance_id_length:${walFileInstanceId.size}")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(CHAIN_DOMAIN)
        md.update(walFileInstanceId)
        val g = ByteArray(8); writeLongBE(g, 0, generation); md.update(g)
        return md.digest()
    }

    /** One chain step over a COMPLETE sealed frame (length prefix included). */
    public fun chainStep(previous: ByteArray, sealedFrame: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(previous)
        md.update(java.security.MessageDigest.getInstance("SHA-256").digest(sealedFrame))
        return md.digest()
    }

    // ──────────────────────────────────────────────────────────── utilities

    public fun hex(b: ByteArray): String = hex(b, 0, b.size)

    public fun hex(b: ByteArray, off: Int, len: Int): String {
        val sb = StringBuilder(len * 2)
        for (i in off until off + len) {
            val v = b[i].toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0xf])
        }
        return sb.toString()
    }

    public fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

    public fun writeIntBE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    public fun writeLongBE(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (56 - 8 * i)) and 0xff).toByte()
    }
}

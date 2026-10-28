package com.tracescout.mobile.internal.loss

import com.tracescout.mobile.queue.GcmFrame
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * THE ONE AUTHORITY-V2 LOW-LEVEL CONTAINER IO (F3-P0 the specification) — the X1-B1 closure.
 *
 * the specification measured *why* the authority must leave `EncryptedEventStore`; the specification
 * measures *who else is on that shared reader*; this class is *what replaces it*,
 * and its own doc is the bound on what the replacement is allowed to be.
 *
 * ```
 * internal not public, not published, not exported
 * OWNED BY the SAME single EncryptedAuthorityStore authority implementation
 * NOT a second authority it holds no authority state, decides no allocation,
 * owns no high-level policy
 * NOT a new public storage it is not a sibling of EncryptedEventStore in
 * authority anyone else's dependency graph
 * ONLY the low-level v2 file/framing IO that replaces the authority's current
 * dependency on EncryptedEventStore
 *
 * roles it handles: snapshot · wal · head/witness · floors
 * it calls GcmFrame DIRECTLY, with the role / session / generation /
 * file-instance Aad the specification and the specification require
 * ```
 *
 * ## What it owns, and what it must NOT own
 *
 * | owns (MECHANISM) | does NOT own (POLICY) |
 * |---|---|
 * | the 32-byte preamble: write, parse, and the the specification container band per role | which generation is current (the WITNESS decides) |
 * | the authenticated header frame at ordinal 0, and its semantic checks | whether allocation is permitted (RULING A2) |
 * | body frames: seal at an ordinal with `AAD_B`, open, and the `Wpresent`/`Wauth` split | any `DisableReason`, any repair, any sweep |
 * | the rung ladder and the in-AEAD padding | the reopen state machine and the IC subroutine |
 * | the tail-chain computation over sealed bytes | the witness's monotone write check and its contents |
 *
 * **THE BOUNDARY INVARIANT**:
 * ```
 * ONE high-level authority owner EncryptedAuthorityStore
 * ONE v2 low-level container IO AuthorityV2ContainerIo
 * A SECOND authority store / state authority REMAINS FORBIDDEN.
 * ```
 * **The temptation X1-B1 exists to forbid is the OPPOSITE move** — extracting a
 * general-purpose "v2 store" that the park store or RN frames could also use
 * later, which is how a second state authority is born from something that started
 * as file IO. F20's second control is the subtle one: a POLICY decision moved
 * INTO this class produces a system that still behaves correctly, which is exactly
 * why that row is a source/ownership pin and not a behavioural one.
 *
 * **IT DOES NOT DO ITS OWN DURABILITY AND MUST NOT.** It builds bytes and hands
 * them to `AuthorityDurableWrite`. A container IO that also fsyncs is two
 * contracts in one object, and the specification's per-operation outcome subsets are exactly
 * what would blur.
 *
 * ## THE ORDINAL SPACE — ONE PER FILE, AND THE READING IS STATED
 *
 * the specification puts the header at **ordinal 0**; the specification titles body frames **"ordinal ≥ 1"**;
 * the specification A3 says the new frame is sealed **"at ordinal = Wpresent"** and the specification R19
 * condition 2 says the extra frame carries the **"right ordinal (= C)"**. Those
 * four statements are all satisfiable together under exactly one reading, and this
 * is it:
 *
 * ```
 * ONE ordinal space per FILE.
 * header -> ordinal 0
 * body j (0-based) -> ordinal j + 1
 *
 * So "ordinal = Wpresent" and "ordinal = C" are statements about the BODY INDEX,
 * which is precisely what Wpresent and C count. Body number Wpresent lands at FILE ordinal Wpresent + 1.
 * ```
 *
 * The reading is written down rather than left implicit because the alternative
 * (0-based body ordinals) would make the specification's own title false and would put a body
 * and the header at the same number — and a silent choice here is how an
 * implementation and a reviewer's reading diverge on a value that is inside every
 * AAD in the format.
 */
internal class AuthorityV2ContainerIo(
    private val key: SecretKey,
    private val random: SecureRandom,
) {

    /** A bound, so a corrupt length cannot ask for an arbitrary allocation. */
    private val maxBodyBytes: Int = AuthorityPadding.RUNGS.last() + GcmFrame.IV_BYTES + 16

    // ─────────────────────────────────────────────────────────── read result

    /**
 * THE THREE TAIL SHAPES OF the specification R23, AND **NONE OF THEM IS COUNTED IN
 * `Wpresent`**. Counting a partial frame is what manufactures a false
 * `Wauth < Wpresent` and durably disables a session whose committed history is
 * intact — the most ordinary residue in the fault model (a power loss
 * mid-append) permanently disabling a healthy authority. the specification S19's negative
 * control is a parser that increments `Wpresent` for a partial frame, and a
 * `CAUGHT_BY_OTHER` there is a FINDING, never a pass.
 */
    enum class TailShape {
        /** No torn bytes at all. */
        NONE,
        /** (a) fewer than the 4 length-prefix bytes remain. */
        SHORT_PREFIX,
        /** (b) a complete length prefix whose declared length exceeds the bytes that remain. */
        DECLARED_BEYOND_END,
        /**
 * (c) a complete length prefix whose declared length is structurally
 * impossible (`> largestRung + 28`). Disclosed as
 * `torn_tail:length_implausible` so it is diagnosable — but STILL NOT
 * COUNTED, because counting it is what manufactures the false interior
 * corruption above.
 */
        LENGTH_IMPLAUSIBLE,
        ;

        fun note(): String? = when (this) {
            NONE -> null
            SHORT_PREFIX, DECLARED_BEYOND_END -> "torn_tail"
            LENGTH_IMPLAUSIBLE -> "torn_tail:length_implausible"
        }
    }

    sealed class Read {
        /** The file does not exist. An absent artifact gives no information. */
        object Absent : Read()

        /**
 * A **CONTAINER-LAYER** VERDICT, reached at the specification steps 4–13 — BEFORE
 * any body frame is decrypted, and therefore before `Wpresent`, `Wauth` or
 * `D` exist.
 *
 * Three things are forbidden for the caller here, each with its own
 * control:
 * 1. DO NOT derive `Wpresent`/`Wauth`/`D` — there is nothing to count: the
 * body frames' AAD is assembled FROM the authenticated header, so
 * without a header there is no AAD and therefore no authentication
 * verdict to report;
 * 2. DO NOT fall into interior corruption — `Wauth < Wpresent` is a
 * statement about a body that was READ; a container failure means it
 * was not;
 * 3. DO NOT write a durable `DisableReason` — every extent row names the
 * BODY, and a durable claim about the wrong layer outlives the fault and
 * cannot be withdrawn by a later correct read.
 *
 * [stage] carries NO role prefix; the caller adds it, which is what makes
 * the `wal_<stage>` / `snapshot_<stage>` / `witness_<stage>` families mirror
 * BY CONSTRUCTION rather than by three hand-written lists.
 */
        data class ContainerRefused(val stage: String) : Read()

        /**
 * The container opened and its header authenticated. `bodies` holds the
 * UNPADDED plaintexts of the frames that authenticated, in file order.
 */
        data class Ok(
            val header: AuthorityContainer.Header,
            val fileInstanceIdHex: String,
            /**
 * `Wpresent` — framed records physically present, counted by the
 * LENGTH PREFIX, **BEFORE any AEAD attempt**. A frame that is on disk
 * but fails AEAD WAS still appended, so `C` compares to this.
 */
            val present: Long,
            /** `Wauth` — of those, how many authenticated. */
            val authenticated: Long,
            /**
 * `D` — the recomputed tail chain over the `Wpresent` sealed frames.
 *
 * **`null` MEANS `not_evaluated`, AND THAT SUPPRESSION IS
 * STRUCTURAL HERE**. The chain is over the sealed bytes
 * as they lie on disk, so under interior corruption it mismatches AS A
 * CONSEQUENCE — one physical fault would otherwise satisfy
 * `AUTHORITY_INTERIOR_CORRUPTION` and `AUTHORITY_TAIL_DIGEST_MISMATCH`,
 * two rows with different expected reasons, and the acceptance matrix
 * would be green whichever one fired.
 */
            val tailDigest: String?,
            /**
 * The chain over the FIRST `present - 1` frames — the SEED when
 * `present == 1`, `null` when `present == 0`.
 *
 * **TWO VALUES, NOT A LIST, AND THE REASON IS EXACT.** the specification R19's
 * condition 3 needs the chain over the first `C` frames, and R19 only
 * ever applies when `Wpresent == C + 1` — so the prefix it needs is
 * ALWAYS the chain one frame back. Carrying every intermediate digest
 * would be 437 KB of state on a 13,653-frame WAL to answer a question
 * with two possible subjects. Do not "generalise" this into a list.
 *
 * Suppressed to `null` under interior corruption for the same
 * the specification stage-15 reason as [tailDigest].
 */
            val tailDigestBeforeLast: String?,
            val tail: TailShape,
            val bodies: List<ByteArray>,
        ) : Read()
    }

    /**
 * THE PER-FILE OPEN ORDER OF the specification, **APPLIED TO EVERY ROLE FROM ONE
 * IMPLEMENTATION** (the specification — the V3-B1 correction, and it is structural, not
 * editorial). Steps 1–3 (the v1 path test, the v3 signature sniff and key
 * availability) belong to the caller and happen BEFORE this; steps 4–16 are
 * here, except the extent comparison (16), which is the reopen machine's.
 *
 * **NO FALLBACK ANYWHERE.** A failed open is never retried with different
 * parameters: no "try with AAD then without", no "try the authority key then
 * the queue key", no "try v2 then v1".
 */
    fun read(
        file: File,
        role: AuthorityContainer.Role,
        expectSessionId: String,
        nameGeneration: Long?,
    ): Read {
        if (!file.isFile) return Read.Absent
        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            return Read.ContainerRefused("unreadable:${t.javaClass.simpleName}")
        }

        // ── steps 4.11: the container band ────────────────────────────────
        val preamble = when (val p = AuthorityContainer.parsePreamble(bytes, role)) {
            is AuthorityContainer.PreambleResult.Refused -> return Read.ContainerRefused(p.stage)
            is AuthorityContainer.PreambleResult.Ok -> p.preamble
        }

        // ── frame scan. The header is frame 0; bodies are 1.n. ────────────
        val frames = scanFrames(bytes, AuthorityContainer.PREAMBLE_LENGTH)
        if (frames.spans.isEmpty()) return Read.ContainerRefused("header_malformed:truncated")

        // ── step 12: header frame AEAD at ordinal 0 ────────────────────────
        val h0 = frames.spans[0]
        val headerPlain = try {
            AuthorityPadding.unpad(
                GcmFrame.open(key, bytes, h0.bodyOffset, h0.bodyLength, AuthorityContainer.aadHeader(preamble)),
            )
        } catch (e: LossFormatException) {
            // A padding refusal on the header is a header-band fact, not a body one.
            return Read.ContainerRefused("header_malformed:${e.reason}")
        } catch (t: Throwable) {
            return Read.ContainerRefused("header_auth_failed")
        }
        val header = try {
            AuthorityCodec.decodeHeader(headerPlain)
        } catch (e: LossFormatException) {
            return Read.ContainerRefused(e.reason)
        }

        // ── step 13: header semantics, BEFORE any body frame is decrypted ──
        AuthorityContainer.checkHeader(header, preamble, expectSessionId, nameGeneration)?.let {
            return Read.ContainerRefused(it)
        }

        // ── steps 14/15: body frames. ──────────────────────────────────────
        var chain = if (role == AuthorityContainer.Role.WAL) {
            AuthorityContainer.chainSeed(unhex16(preamble.fileInstanceIdHex), header.generation)
        } else {
            null
        }
        var chainBeforeLast: ByteArray? = null
        var present = 0L
        var authed = 0L
        val out = ArrayList<ByteArray>(frames.spans.size - 1)
        for (i in 1 until frames.spans.size) {
            val s = frames.spans[i]
            present++
            if (chain != null) {
                // Keep the value one step back — R19's PREFIX PROOF, and nothing more.
                chainBeforeLast = chain
                chain = AuthorityContainer.chainStep(
                    chain,
                    bytes.copyOfRange(s.frameOffset, s.bodyOffset + s.bodyLength),
                )
            }
            val aad = AuthorityContainer.aadBody(preamble, i.toLong(), header.generation, header.sessionId)
            val plain = try {
                AuthorityPadding.unpad(GcmFrame.open(key, bytes, s.bodyOffset, s.bodyLength, aad))
            } catch (t: Throwable) {
                // A FRAME THAT FAILS IS **STILL COUNTED IN `Wpresent`** — it was
                // appended. Continuing to attempt the rest is what produces the
                // honest `Wauth < Wpresent` split; F2's `readAll` skips-and-counts
                // and cannot produce it at all.
                continue
            }
            authed++
            out.add(plain)
        }

        // the specification stage 15: under interior corruption the chain mismatches AS A
        // CONSEQUENCE, so it is `not_evaluated` and NOT mismatched — one physical
        // fault must not satisfy two acceptance rows with different expected reasons.
        val suppress = chain == null || authed < present
        val digest = if (suppress) null else AuthorityContainer.hex(chain!!)
        val digestBefore = if (suppress || present == 0L) null else chainBeforeLast?.let { AuthorityContainer.hex(it) }

        return Read.Ok(header, preamble.fileInstanceIdHex, present, authed, digest, digestBefore, frames.tail, out)
    }

    /**
 * The `createOrAdoptEmpty` probe: is [file] an authentic,
 * correctly-roled, correctly-generationed container with **ZERO body frames**?
 * Returns its OWN `fileInstanceId` hex when it is, else `null`.
 *
 * IT IS THE CONTAINER IO THAT ANSWERS THIS, not the durability primitive —
 * deciding what a v2 container IS is this class's job, and durability is the
 * other's.
 */
    fun probeAdoptableEmpty(
        file: File,
        role: AuthorityContainer.Role,
        expectSessionId: String,
        expectGeneration: Long,
    ): String? {
        val r = read(file, role, expectSessionId, expectGeneration)
        if (r !is Read.Ok) return null
        if (r.present != 0L) return null
        if (r.header.generation != expectGeneration) return null
        if (r.tail != TailShape.NONE) return null
        return r.fileInstanceIdHex
    }

    // ────────────────────────────────────────────────────────────── writing

    /** A file under construction: the preamble, the header frame and zero or more bodies. */
    class Built(val bytes: ByteArray, val fileInstanceIdHex: String, val tailDigestHex: String?)

    fun newInstanceId(): ByteArray = ByteArray(AuthorityContainer.FILE_INSTANCE_BYTES).also { random.nextBytes(it) }

    /**
 * Build a COMPLETE v2 file: preamble + authenticated header + [bodies].
 *
 * For [AuthorityContainer.Role.WAL] the returned [Built.tailDigestHex] is the
 * chain over the bodies actually written, so the witness the caller installs
 * describes the file the caller is about to install — one computation, not two
 * that could disagree.
 */
    fun build(
        role: AuthorityContainer.Role,
        instanceId: ByteArray,
        sessionId: String,
        generation: Long,
        bodies: List<ByteArray>,
    ): Built {
        val preambleBytes = AuthorityContainer.writePreamble(role, instanceId)
        val preamble = when (val p = AuthorityContainer.parsePreamble(preambleBytes, role)) {
            is AuthorityContainer.PreambleResult.Ok -> p.preamble
            is AuthorityContainer.PreambleResult.Refused -> throw LossFormatException("own_preamble_refused:${p.stage}")
        }
        val header = AuthorityContainer.Header(
            role = role,
            // EMPTY FOR `floors`, AND ONLY FOR `floors` — the floors file is per
            // INSTALL, not per session, and `checkHeader` refuses a session id
            // there (`floors_header_session_present`).
            sessionId = if (role == AuthorityContainer.Role.FLOORS) "" else sessionId,
            generation = generation,
            fileInstanceIdHex = AuthorityContainer.hex(instanceId),
        )
        val out = java.io.ByteArrayOutputStream()
        out.write(preambleBytes)
        out.write(GcmFrame.seal(key, AuthorityPadding.pad(AuthorityCodec.encodeHeader(header)),
            AuthorityContainer.aadHeader(preamble), random))

        var chain = if (role == AuthorityContainer.Role.WAL)
            AuthorityContainer.chainSeed(instanceId, generation) else null
        bodies.forEachIndexed { j, plain ->
            val frame = sealBody(preamble, (j + 1).toLong(), generation, header.sessionId, plain)
            out.write(frame)
            if (chain != null) chain = AuthorityContainer.chainStep(chain!!, frame)
        }
        return Built(out.toByteArray(), header.fileInstanceIdHex, chain?.let { AuthorityContainer.hex(it) })
    }

    /**
 * Seal ONE body frame at [ordinal] (a FILE ordinal — see the class doc).
 *
 * the specification **A3**: a frame is authenticated IN FULL before any byte of it
 * reaches the file. The padding is inside the AEAD, so a reader cannot edit
 * the real length or the padding without failing authentication.
 */
    fun sealBody(
        preamble: AuthorityContainer.Preamble,
        ordinal: Long,
        generation: Long,
        sessionId: String,
        plaintext: ByteArray,
    ): ByteArray = GcmFrame.seal(
        key,
        AuthorityPadding.pad(plaintext),
        AuthorityContainer.aadBody(preamble, ordinal, generation, sessionId),
        random,
    )

    /**
 * Re-parse an existing file's preamble so an APPEND can compute AAD_B without
 * re-reading the whole container. Returns null when the container band refuses
 * an append to a file whose container does not open must not happen.
 */
    fun preambleOf(file: File, role: AuthorityContainer.Role): AuthorityContainer.Preamble? {
        val head = try {
            val b = ByteArray(AuthorityContainer.PREAMBLE_LENGTH)
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < AuthorityContainer.PREAMBLE_LENGTH) return null
                raf.readFully(b)
            }
            b
        } catch (t: Throwable) {
            return null
        }
        return when (val p = AuthorityContainer.parsePreamble(head, role)) {
            is AuthorityContainer.PreambleResult.Ok -> p.preamble
            is AuthorityContainer.PreambleResult.Refused -> null
        }
    }

    // ───────────────────────────────────────────────────────── frame scanner

    private class Span(val frameOffset: Int, val bodyOffset: Int, val bodyLength: Int)
    private class Scan(val spans: List<Span>, val tail: TailShape)

    /**
 * Count frames BY THE LENGTH PREFIX, before any AEAD attempt — and refuse to
 * count a partial one.
 *
 * AN IMPLAUSIBLE LENGTH IN THE **INTERIOR** IS TREATED AS A TEAR AT THAT
 * OFFSET, and that is the honest reading: once a declared length is
 * structurally impossible the scanner cannot know where the NEXT frame starts,
 * so everything from there on is unframed bytes. The alternative — guessing a
 * resynchronisation point — would invent frames.
 */
    private fun scanFrames(bytes: ByteArray, from: Int): Scan {
        val spans = ArrayList<Span>()
        var off = from
        while (true) {
            val remaining = bytes.size - off
            if (remaining == 0) return Scan(spans, TailShape.NONE)
            if (remaining < 4) return Scan(spans, TailShape.SHORT_PREFIX)
            val len = GcmFrame.readInt(bytes, off)
            if (len < GcmFrame.IV_BYTES + 16 || len > maxBodyBytes) return Scan(spans, TailShape.LENGTH_IMPLAUSIBLE)
            if (off + 4 + len > bytes.size) return Scan(spans, TailShape.DECLARED_BEYOND_END)
            spans.add(Span(off, off + 4, len))
            off += 4 + len
        }
    }

    private fun unhex16(hex: String): ByteArray {
        if (hex.length != AuthorityContainer.FILE_INSTANCE_BYTES * 2)
            throw LossFormatException("file_instance_id_hex_length:${hex.length}")
        val out = ByteArray(AuthorityContainer.FILE_INSTANCE_BYTES)
        for (i in out.indices) out[i] = ((digit(hex[2 * i]) shl 4) or digit(hex[2 * i + 1])).toByte()
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw LossFormatException("file_instance_id_hex_malformed")
    }
}

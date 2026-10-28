package com.tracescout.mobile.internal.loss

import java.io.File
import java.io.FileInputStream

/**
 * Recognition of the OLD branch's v3 loss-authority framing — audit addendum
 * RULING A3.2/A3.3.
 *
 * The signatures are **MEASURED from the branch itself** (`b615cd50`
 * `internal/loss/LossCodec.kt`), never inferred from how a v3 file "probably"
 * looks:
 *
 * v3 WAL `wal-<gen>.log` `[MAGIC 4]["TSLW"] [ver 1] [len 4 BE] [crc32 4 BE] [payload]`
 * → bytes 0.3 are `TSLW`, byte 4 is the version (3)
 * v3 snapshot `snapshot.bin` PLAINTEXT TSV whose first row is `row("v", "3")` + '\n'
 * → the file begins with the exact bytes `v \t 3 \n`
 *
 * WHY THIS EXISTS AT ALL, given F2 now lives in its own directory
 * ([EncryptedAuthorityStore.ROOT_DIR]) under its own filenames. The map's
 * original answer was to let a v3 file "fail AEAD open" and be reported as
 * unreadable. That is a WRONG DIAGNOSIS of a knowable state: a reader told
 * "cryptographic corruption" will go looking for a damaged device or a rotated
 * key, when the truth is a format this build deliberately does not read. The
 * sniff runs BEFORE any AEAD attempt so the typed answer is the first answer.
 *
 * THIS OBJECT NEVER WRITES, DELETES, RENAMES OR SWEEPS ANYTHING. A v3 file
 * is historical input; F2's only permitted action is to read enough of its head
 * to name it and then leave it byte-for-byte as it was. There is deliberately
 * no `migrate`, no `convert` and no `discard` here — a function that does not
 * exist cannot be called by mistake later.
 */
internal object V3Legacy {

    /** The typed refusal RULING A3.2 mandates. Never a generic corruption code. */
    const val REFUSAL: String = "format_version_unsupported:3"

    /**
 * The v3 authority home. F2 never writes here, never prunes here and never
 * sweeps here — it is named only so a reader can see that it is excluded.
 */
    const val LEGACY_ROOT_DIR: String = "tracescout-loss"

    private val WAL_MAGIC = byteArrayOf('T'.code.toByte(), 'S'.code.toByte(), 'L'.code.toByte(), 'W'.code.toByte())
    private val SNAPSHOT_HEAD = "v\t3\n".toByteArray(Charsets.UTF_8)

    /** The v3 WAL name shape, so a sweep can prove it never matches one. */
    private val LEGACY_WAL_RE = Regex("^wal-(\\d+)\\.log$")

    fun isLegacyWalName(name: String): Boolean = LEGACY_WAL_RE.matches(name)

    /**
 * A short READ-ONLY sniff of the file head. Returns the signature name, or
 * null when nothing v3 is recognised.
 *
 * An unreadable file returns null rather than a signature: "I could not
 * look" must not be reported as "I recognised v3" — the caller's own
 * integrity path then refuses it for what it actually is.
 */
    fun signatureOf(f: File): String? {
        if (!f.isFile) return null
        val head = ByteArray(8)
        val n = try { FileInputStream(f).use { it.read(head) } } catch (_: Throwable) { return null }
        if (n >= WAL_MAGIC.size + 1 && startsWith(head, WAL_MAGIC)) return "v3_wal_magic_TSLW_declared_version_${head[4].toInt()}"
        if (n >= SNAPSHOT_HEAD.size && startsWith(head, SNAPSHOT_HEAD)) return "v3_snapshot_tsv_version_row"
        return null
    }

    private fun startsWith(buf: ByteArray, prefix: ByteArray): Boolean {
        for (i in prefix.indices) if (buf[i] != prefix[i]) return false
        return true
    }

    // ───────────────── F3-P0 the specification — the v1 clean break, by EXACT PATH ───────

    /**
 * THE F2 AUTHORITY ROOT, WHICH IS **v1** FROM F3-P0's POINT OF VIEW.
 * v2 lives under `tracescout-authority-v2` and **never reads, migrates,
 * renames, sweeps or deletes anything under this one**.
 */
    const val V1_ROOT_DIR: String = "tracescout-authority-v1"

    /**
 * **DETECTION IS A PATH TEST AND NOTHING MORE**. It answers ONE
 * question — *does a v1 session directory exist for this path* — and its only
 * outcome is a refusal. It **opens nothing, reads no v1 byte**, and has no
 * branch that produces v2 state from v1 state. `File.isDirectory` is the whole
 * mechanism, which is why it is the one step the specification permits outside the lock
 * (though L0 precedes it in practice).
 *
 * **IT REUSES THE LEGACY `sanitize` DELIBERATELY**. Computing the
 * v1 path with v2's injective naming would look for a directory v1 could
 * never have created, so a real v1 directory would be MISSED — which is K3's
 * negative control.
 *
 * **`v1sanitize` IS NOT INJECTIVE, AND THE CONSEQUENCE IS CARRIED
 * EXPLICITLY.** The repro measured it: `x_1` and `x.1` collide, and two
 * 104-character ids sharing a 96-character prefix collide. So a v1 directory
 * found for session id X may in fact have been written by a different session
 * id Y that sanitizes to the same name, and this check **can refuse a session
 * whose v1 directory is not its own**. That is a FALSE REFUSAL — denial of
 * capture — and NEVER a false admission, which is the direction this design is
 * allowed to err in. It is recorded rather than "fixed" by a cleverer
 * probe: reading the v1 snapshot to learn the real session id would be READING A
 * v1 ARTIFACT, which the specification forbids.
 *
 * **REFUSAL IS WHAT PREVENTS REUSE HERE.** A v1 directory's high-water is not
 * in the v1 floors file (floors are written only at prune), so v2 cannot know how
 * far that session allocated. Genesising it at 0 would be the exact RULING A1
 * violation; refusing allocates nothing, so there is nothing to collide.
 */
    fun v1SessionDirectoryPresent(filesDir: File, sessionId: String): Boolean =
        File(File(filesDir, V1_ROOT_DIR), v1Sanitize(sessionId)).isDirectory

    /**
 * F2's OWN `sanitize`, reproduced exactly: map every non-`[A-Za-z0-9_-]` byte to
 * `_`, then truncate to 96 characters.
 *
 * This is a DELIBERATE copy of a retired function, because the question it
 * answers is "what name would the LEGACY writer have produced?" — and that
 * answer must not drift when v2's own naming improves. the specification K3's control is
 * computing this path with v2's naming instead.
 */
    fun v1Sanitize(s: String): String =
        s.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("").take(96)
}

package com.tracescout.mobile.internal.loss

/**
 * AUTHORITY-V2 FILE AND DIRECTORY NAMES — one owner (F3-P0 the specification, the specification, the specification).
 *
 * `EncryptedAuthorityStore` owns file names per the specification's "one owner per name"
 * split; this object is that ownership made addressable, so the sweep regex, the
 * reopen enumeration and the writer all read ONE definition. Two copies of a name
 * pattern is how a sweep starts matching a file it must never touch.
 */
internal object AuthorityNaming {

    /**
 * A **NEW ROOT**, distinct from F2's `tracescout-authority-v1` and from the
 * branch's v3 root. v2 lives under its own root with
 * its own floors file, its own key alias, its own root lock and its own file
 * names — and **v1 artifacts are never read, migrated, renamed, swept or
 * deleted**. There is deliberately no `migrate`, no `convert` and no `discard`
 * function, so none can be called by mistake later.
 */
    const val ROOT_DIR: String = "tracescout-authority-v2"

    /** The head witness — the generation POINTER. Its name carries no generation. */
    const val HEAD: String = "authority-head.bin"

    /** The floors file, at the ROOT: outside every session directory, so `pruneOne` cannot reach it. */
    const val FLOORS: String = "session-floors.bin"

    /**
 * THE PRUNE TOMBSTONE, carried forward from F2 unchanged: written inside the
 * session directory after the floor is durable and BEFORE the authority files
 * go, and left in place. "This session id was seen and pruned" then survives as
 * a durable fact that does not live in the floors file, so an unreadable or
 * missing floors file can be answered per SESSION.
 */
    const val PRUNED_MARKER: String = "pruned"

    /**
 * GENERATION-NAMED. Under F2 the snapshot had a FIXED name, so
 * `replace` had already destroyed the old snapshot by the time a read-back
 * could fail — a failed verification had nothing to fall back to. The
 * generation in the name is only a CANDIDATE: every snapshot and WAL header
 * carries an AUTHENTICATED `generation` which must equal it, so a RENAMED file
 * is refused `container_generation_name_mismatch`.
 */
    fun snapshot(generation: Long): String = "authority-snapshot-$generation.bin"

    fun wal(generation: Long): String = "authority-wal-$generation.bin"

    /**
 * **THE SWEEP REGEXES ARE PINNED TO PROVE THEY CAN NEVER MATCH** a v1 name, a
 * v3 `wal-<gen>.log`, the head witness, the root-level lock file, the floors
 * file or a stale unique temp. RULING A3.4 already establishes
 * why: **a sweep is the one place a too-broad match is destructive rather than
 * merely noisy**, and the v2 sweep has six more names to exclude than the v1
 * sweep did. Anchored at both ends, digits only.
 */
    private val WAL_RE: Regex = Regex("^authority-wal-(\\d+)\\.bin$")
    private val SNAPSHOT_RE: Regex = Regex("^authority-snapshot-(\\d+)\\.bin$")

    fun walGenerationOf(name: String): Long? = WAL_RE.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
    fun snapshotGenerationOf(name: String): Long? = SNAPSHOT_RE.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

    /** Exposed for the guard that asserts the sweep can never name a v1 or v3 file. */
    fun isOwnWalName(name: String): Boolean = WAL_RE.matches(name)
    fun isOwnSnapshotName(name: String): Boolean = SNAPSHOT_RE.matches(name)

    /**
 * **INJECTIVE DIRECTORY NAMING FOR v2's OWN ROOT.**
 *
 * F2's `sanitize` maps every non-`[A-Za-z0-9_-]` byte to `_` and then truncates
 * to 96 characters, and it is NOT injective — measured: `x_1` and `x.1`
 * collide, and two 104-character ids sharing a 96-character prefix collide.
 * Under v1 the second session is refused `session_mismatch`, i.e. a collision
 * is DENIAL OF CAPTURE rather than an integrity vector — but a name that cannot
 * be read back is a name a later reader has to guess at.
 *
 * v2 fixes it for its OWN root with a REVERSIBLE escape and **NO TRUNCATION**:
 *
 * ```
 * every byte in [A-Za-z0-9_-] passes through
 * anything else '~' + two lowercase hex digits
 * ```
 *
 * **`~` NEEDS NO ESCAPE OF ITS OWN, AND THAT IS A CONSEQUENCE OF THE SHAPE
 * RULE RATHER THAN A HOPE.** `AuthorityFieldShape.IDENTIFIER` admits only
 * `[A-Za-z0-9_.-]`, so a shape-valid session id cannot contain `~` at all —
 * which makes `~` unambiguous as an introducer. The escape is nonetheless
 * written for the GENERAL byte set, so widening the shape later cannot silently
 * make two ids collide.
 *
 * **A PATHOLOGICAL ID IS REFUSED, NEVER TRUNCATED.** A 128-character id of
 * all-escapable bytes encodes to 384 characters, past the 255-byte limit a
 * single path component has on this platform. Truncating would restore exactly
 * the collision this function exists to remove, so the encoder REFUSES —
 * a FALSE REFUSAL (denial of capture), never a false admission, which is the
 * one direction the specification says this design may err in.
 */
    const val MAX_DIR_NAME_BYTES: Int = 255

    fun encodeSessionDirName(sessionId: String): String? {
        val sb = StringBuilder(sessionId.length)
        for (b in sessionId.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '_' || c == '-') {
                sb.append(c)
            } else {
                val v = b.toInt() and 0xff
                sb.append('~').append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0xf])
            }
        }
        if (sb.isEmpty()) return null
        return if (sb.length > MAX_DIR_NAME_BYTES) null else sb.toString()
    }

    /** The inverse. Exposed so the specification K8's injectivity property is checkable by ROUND TRIP, not by assertion. */
    fun decodeSessionDirName(name: String): String? {
        val out = java.io.ByteArrayOutputStream(name.length)
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c == '~') {
                if (i + 2 >= name.length) return null
                val hi = hexVal(name[i + 1]) ?: return null
                val lo = hexVal(name[i + 2]) ?: return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                out.write(c.code)
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hexVal(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> null
    }
}

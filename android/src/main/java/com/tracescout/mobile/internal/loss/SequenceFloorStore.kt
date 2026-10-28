package com.tracescout.mobile.internal.loss

import com.tracescout.mobile.queue.DurableReplace
import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * THE DURABLE SEQUENCE FLOOR — audit addendum RULING A1.
 *
 * The last durable fact establishing that a session id has already allocated,
 * kept OUTSIDE the per-session authority directory so that pruning that
 * directory cannot remove it. A reused `sessionId` reopens strictly above its
 * floor and can never genesis at 0 after it has allocated.
 *
 * ```
 * <filesDir>/tracescout-authority-v2/session-floors.bin role = floors, ONE body frame
 * <filesDir>/tracescout-authority-v2/authority.lock the ONE root lock (SS F.2)
 * <filesDir>/tracescout-authority-v2/<escaped-sid>/. the per-session authority — prunable
 * ```
 *
 * Every property the ruling demands, and where it comes from:
 * - **authenticated / sealed** — in the AUTHORITY-V2 CONTAINER at `role = floors`,
 * under the DEDICATED `tracescout_authority_v2` alias (F3-P0 SS F.8, SS H.1). It
 * used to be one bare sealed frame under the QUEUE's key, and leaving it there
 * while the snapshot and WAL moved is the exact failure SS H.1 names: the floors
 * file would keep opening, keep authenticating and keep passing every test that
 * asks *"does the authority work?"* — while the one artifact whose whole purpose
 * is to stop a reused session id from allocating from zero after a prune sat in
 * the queue's key domain. **All four v2 roles move together or the isolation
 * claim is not the claim it reads as.**
 * - **versioned** — `FORMAT_VERSION` is the first plaintext byte and a foreign
 * version is refused BY VERSION, naming the version found.
 * - **durable** — installed through `DurableReplace.replace`, and the caller is
 * only permitted to delete a directory once the write reports COMMITTED.
 * - **monotonic, NEVER LOWERED** — [recordFloor] takes the maximum; there is no
 * setter that can lower a floor and no path that removes one entry.
 * - **NOT inferred from queue survivors** — the value comes from the pruned
 * authority's OWN durable `highWater`. The queue is exactly what has been
 * drained, so a floor derived from it would read zero at the moment it matters.
 *
 * ## THE FLOORS RESIDUAL, MEASURED AND ACCEPTED (SS G.5.1)
 *
 * The whole map is ONE plaintext blob in ONE body frame, so the file is
 * `preamble | ONE authenticated header frame | ONE body frame` and its **frame
 * count is CONSTANT at 2** whether it holds 1 row or 4096 — the frame count
 * discloses nothing about the entry count, so the review's one-record-per-entry
 * premise has no subject here. What IS keylessly visible, as file size, is the
 * **RUNG** that one body frame lands in, i.e. a monotone UPPER BOUND on the row
 * count (n <= 20 at rung 1024, <= 83 at 4096, <= 668 at 32768 for the product's own
 * 29-char session-id shape) — and even that bound is LOOSE, because `sidLen` is
 * caller-controlled in [1,128], so one rung is consistent with a whole family of
 * `(n, sidLen)` pairs. `maxEverFloor`, the `dropped` flag, `nextOrdinal`, every
 * session id and every floor value are all inside the AEAD. **This is a disclosure
 * the design ACCEPTS and states; it is not a defect being fixed here** — hiding it
 * would need a fixed top rung (1 MiB per install) or fixed-size shards, i.e.
 * traffic-flow confidentiality, which SS G.5 explicitly does not provide.
 *
 * The floors body frame is the **THIRD disclosed exemption** from SS G.2's "every
 * grammar maximum fits rung 1" absolute: its grammar maximum is
 * `22 + 4096 x (20 + 128)` = **606,230 B**, so it needs the TOP ladder rung. Like
 * `DeliveredBatch` and the snapshot frame it is not in `BOUNDED_KINDS`, and
 * SS J.2 G3's third control asserts that adding it FAILS the build rather than
 * silently passing.
 *
 * ## The bound, and the fail-safe that makes the bound safe
 *
 * One tiny row per session id would otherwise grow without limit on a long-lived
 * install. The file holds at most [MAX_FLOORS] rows; at capacity the OLDEST are
 * dropped and `dropped` is latched true — and from that moment on a session id
 * with NO row is no longer assumed fresh. It is given [Floors.maxEverFloor],
 * which is never lowered and never dropped, so a dropped row can cost a *gap*
 * (legal: the envelope's contract is strictly-monotonic-per-session, gaps legal)
 * but can never cost a *collision*.
 *
 * That fail-safe has a stated consequence: after the first drop, a genuinely
 * fresh session id no longer starts at 0, so the `mobile_session_start` opener
 * convention holds only while `dropped` is false (the first [MAX_FLOORS] session
 * ids on an install). That is the conservative direction the ruling asks for —
 * "prefer conservative refusal unless the high-water is positively known" — and
 * it is disclosed here rather than discovered later.
 */
internal object SequenceFloorStore {

    // A THE NAME HAS ONE HOME. A second literal for one on-disk name is how two
    // readers of one layout start to disagree, and the specification's injective naming rests on the
    // whole layout being stated in exactly one place.
    const val FILE_NAME: String = AuthorityNaming.FLOORS
    const val FORMAT_VERSION: Int = 1

    /**
 * At most this many rows, and the fail-safe below covers overflow.
 *
 * SIZE, COMPUTED RATHER THAN GUESSED: a real session id is
 * `ts_<13 digits>_<12 hex>` = 29 bytes, so a row is 4 + 29 + 8 + 8 = 49 B
 * and a full file is ~200 KB sealed (an earlier version of this comment
 * said "tens of KB", which was wrong by an order of magnitude). That is
 * read-modify-written once per prune — i.e. once per session open, not per
 * record — so the cost is one fsync of 200 KB on a path that already
 * performs several.
 */
    const val MAX_FLOORS: Int = 4096

    private const val FLAG_DROPPED = 0x01

    /**
 * Invoked as the FIRST statement of [readLocked], for tests only.
 *
 * OBSERVED, NEVER CONSULTED — see [readLocked]. A read is invisible on
 * disk, so proving that the floors container was NOT touched before the root
 * lock was taken needs an in-process observation; a byte-identical or
 * mtime-only assertion cannot distinguish "not read" from "read".
 */
    @Volatile
    var readAttemptObserverForTest: (() -> Unit)? = null

    /** One session's durable floor and the ordinal that decides drop order. */
    data class Row(val sessionId: String, val floor: Long, val ordinal: Long)

    data class Floors(
        val rows: Map<String, Row>,
        /** The highest floor ever recorded here, never lowered, never dropped. */
        val maxEverFloor: Long,
        /** Latched once any row has been dropped: an unknown sid can no longer be assumed fresh. */
        val dropped: Boolean,
        val nextOrdinal: Long,
    ) {
        companion object { fun empty(): Floors = Floors(emptyMap(), -1L, false, 0L) }
    }

    sealed class Read {
        data class Ok(val floors: Floors) : Read()
        /** No floors file has ever been written here. Every session id is genuinely new. */
        object Absent : Read()
        /**
 * The file exists and could not be believed. NEVER collapsed into
 * [Absent]: "I cannot read the floors" and "there are no floors" are
 * different facts, and treating the first as the second is exactly how a
 * reused session id would genesis at 0.
 */
        data class Failed(val reason: String) : Read()
    }

    fun file(filesDir: File): File = File(File(filesDir, AuthorityNaming.ROOT_DIR), AuthorityNaming.FLOORS)

    /**
 * Read through the **ONE** container-band implementation, at `role = floors` —
 * so this role gets the same three-state domain, the same typed `<stage>`
 * grammar and the same "name it, do not call it corruption" rule as the other
 * three (SS A.4, SS J.2 A7). A role that quietly skipped the band is what that
 * row catches.
 *
 * The header's `sessionId` MUST be EMPTY here: floors are per INSTALL, not per
 * session, and `AuthorityContainer.checkHeader` refuses a session id with
 * `floors_header_session_present`.
 */
    fun read(filesDir: File, key: SecretKey, random: SecureRandom = SecureRandom()): Read {
        // SS F.2 / SS J.2 F17+S16 — the ROOT LOCK COVERS THE FLOORS READ, and it is
        // acquired BEFORE the first byte of the floors container is touched. A caller
        // that already holds the root lock must use [readLocked]; the lock is
        // reference-counted per process, so a nested acquire here would be correct but
        // wasteful, and — more importantly — a reader that acquires its OWN lock
        // cannot be composed into a caller's read/reopen decision boundary. See
        // `LossAuthority.open`, which holds ONE lock across floors-read AND authority
        // open precisely so nothing can replace the floors file in between.
        val acq = AuthorityLock.acquire(File(filesDir, AuthorityNaming.ROOT_DIR))
        if (acq is AuthorityLock.Acquisition.Unavailable) return Read.Failed("floors_lock_unavailable")
        val heldLock = acq as AuthorityLock.Acquisition.Ok
        try {
            return readLocked(filesDir, key, random)
        } finally {
            heldLock.release()
        }
    }

    /**
 * The floors read ITSELF. **THE ROOT LOCK MUST ALREADY BE HELD.**
 *
 * EVERY BYTE THIS FUNCTION TOUCHES IS INSIDE THE LOCK, INCLUDING THE ONES
 * THAT ARE NOT AEAD. `f.isFile` is a metadata probe, `V3Legacy.signatureOf`
 * OPENS AND READS THE FILE, and `io.read` parses the preamble and attempts the
 * AEAD — SS J.2 F17 pins acquisition before the FIRST READ, not before the
 * first write, so all three are here rather than split across the boundary.
 *
 * [readAttemptObserverForTest] IS OBSERVED AND NEVER CONSULTED. It is the
 * only way a test can prove a NEGATIVE — that zero floors reads occurred before
 * a lock refusal — because a read leaves no trace on disk: an mtime does not
 * move and the bytes are byte-identical either way, so "the file was not read"
 * is unfalsifiable from the file system alone. It is invoked as the FIRST
 * statement so that ANY entry is counted, including one that stops at `isFile`.
 * No production branch reads it (pinned at the source in
 * `AuthorityOwnershipPinsTest`); a hook that could steer behaviour would be a
 * second, test-only control path through a durability boundary.
 */
    fun readLocked(filesDir: File, key: SecretKey, random: SecureRandom = SecureRandom()): Read {
        readAttemptObserverForTest?.invoke()
        val f = file(filesDir)
        if (!f.isFile) return Read.Absent
        // BEFORE the AEAD attempt: a KNOWN format is NAMED, never reported as
        // cryptographic corruption (SS A.3 step 2).
        V3Legacy.signatureOf(f)?.let { return Read.Failed(V3Legacy.REFUSAL) }
        val io = AuthorityV2ContainerIo(key, random)
        return when (val r = io.read(f, AuthorityContainer.Role.FLOORS, "", null)) {
            is AuthorityV2ContainerIo.Read.Absent -> Read.Absent
            is AuthorityV2ContainerIo.Read.ContainerRefused -> Read.Failed("floors_${r.stage}")
            is AuthorityV2ContainerIo.Read.Ok -> {
                if (r.bodies.size != 1) Read.Failed("floors_body_count:${r.bodies.size}")
                else try { Read.Ok(decode(r.bodies[0])) } catch (e: LossFormatException) { Read.Failed("floors:${e.reason}") }
            }
        }
    }

    /**
 * The floor that applies to [sessionId] — or null when it is genuinely a
 * session that has never allocated on this install.
 */
    fun floorFor(floors: Floors, sessionId: String): Long? {
        floors.rows[sessionId]?.let { return it.floor }
        // Unknown id AFTER a drop: we cannot prove freshness, so take the
        // conservative bound rather than assume 0 is unused.
        return if (floors.dropped) floors.maxEverFloor.takeIf { it >= 0L } else null
    }

    /**
 * Raise [sessionId]'s floor to at least [highWater], durably.
 *
 * Returns the [DurableReplace.Outcome]; the caller may only treat a
 * COMMITTED result as permission to delete the session's directory. A
 * request that would LOWER a floor is a no-op reported as COMMITTED — the
 * durable fact already says at least as much.
 */
    fun recordFloor(
        filesDir: File,
        sessionId: String,
        highWater: Long,
        key: SecretKey,
        dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
        random: SecureRandom = SecureRandom(),
    ): DurableReplace.Outcome {
        // THE FLOORS READ-MODIFY-WRITE IS **MUTATION SITE 8** OF SS F.2's
        // ENUMERATION AND MUST HOLD THE ROOT LOCK. P5 measured the defect it closes:
        // 550 barrier-started concurrent races, **550/550 lost a row**. And SS F.3
        // measured why a unique temp name WITHOUT the lock is strictly worse than the
        // fixed name — both writers report COMMITTED, one row is silently lost, and
        // both are then authorised to delete their session directories, so the
        // session whose row vanished later genesises at 0 and REUSES SEQUENCES.
        val acq = AuthorityLock.acquire(File(filesDir, AuthorityNaming.ROOT_DIR))
        if (acq is AuthorityLock.Acquisition.Unavailable)
            return DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED
        val heldLock = acq as AuthorityLock.Acquisition.Ok
        try {
            return recordFloorLocked(filesDir, sessionId, highWater, key, dirSync, random)
        } finally {
            heldLock.release()
        }
    }

    private fun recordFloorLocked(
        filesDir: File,
        sessionId: String,
        highWater: Long,
        key: SecretKey,
        dirSync: DurableReplace.DirSync,
        random: SecureRandom,
    ): DurableReplace.Outcome {
        // The lock is ALREADY held by `recordFloor`; take the locked read, never the
        // acquiring one — one lock, one acquisition, one critical section.
        val current = when (val r = readLocked(filesDir, key, random)) {
            is Read.Ok -> r.floors
            is Read.Absent -> Floors.empty()
            // Refuse to write over a file we could not read: overwriting it
            // would destroy every floor it holds, which is the whole defect.
            is Read.Failed -> return DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED
        }
        val existing = current.rows[sessionId]
        // TWO GUARDS OF ONE CONTRACT ("a floor is NEVER LOWERED"), AND
        // NEITHER ALONE IS LOAD-BEARING — measured, not assumed:
        //
        // mutation:sdk 483 tests
        // the early return removed alone 483 / 0 NOT caught
        // the maxOf removed alone 483 / 0 NOT caught
        // BOTH removed caught
        //
        // The early return makes the `maxOf` unreachable for a lowering request,
        // and the `maxOf` makes the early return's absence harmless. Removing
        // either is a silent narrowing of defence that no test can see, which is
        // exactly what makes a doubled guard dangerous to "simplify" — the
        // tidier sees no failure. Both stay, and the control removes both.
        if (existing != null && existing.floor >= highWater) return DurableReplace.Outcome.COMMITTED

        val rows = LinkedHashMap(current.rows)
        rows[sessionId] = Row(sessionId, maxOf(highWater, existing?.floor ?: -1L), current.nextOrdinal)
        var dropped = current.dropped
        if (rows.size > MAX_FLOORS) {
            val victims = rows.values.sortedBy { it.ordinal }.take(rows.size - MAX_FLOORS)
            for (v in victims) rows.remove(v.sessionId)
            dropped = true
            RecoveryTrace.log("authority_floor_dropped", "count" to victims.size, "kept" to rows.size)
        }
        val next = Floors(
            rows = rows,
            maxEverFloor = maxOf(current.maxEverFloor, highWater),
            dropped = dropped,
            nextOrdinal = current.nextOrdinal + 1,
        )
        // THROUGH THE AUTHORITY-V2 CONTAINER AND THE AUTHORITY-ONLY DURABILITY
        // PRIMITIVE. `DurableReplace` is NOT called from any authority path
        // (SS J.2 F10), and `DurableReplace.kt` itself is byte-unchanged (SS J.2 F11).
        val io = AuthorityV2ContainerIo(key, random)
        // The instance id is re-minted on every whole-file replace, which is correct
        // for a role whose file is only ever installed atomically and whose identity
        // no witness binds — the two instance bindings in the witness are the
        // snapshot's and the WAL's (SS C.2).
        val built = io.build(AuthorityContainer.Role.FLOORS, io.newInstanceId(), "", 0L, listOf(encode(next)))
        // The caller's contract is F2's three-state `DurableReplace.Outcome`, and it
        // may only treat COMMITTED as permission to delete a directory. The mapping
        // is deliberately CONSERVATIVE: anything not provably committed is reported
        // as not-committed, so `pruneOne` refuses rather than deleting on an
        // uncertain floor.
        val out = when (AuthorityDurableWrite.replace(file(filesDir), built.bytes, dirSync)) {
            AuthorityDurableWrite.Outcome.COMMITTED -> DurableReplace.Outcome.COMMITTED
            AuthorityDurableWrite.Outcome.INDETERMINATE_DIRECTORY -> DurableReplace.Outcome.INDETERMINATE_DIRECTORY_UNSYNCED
            else -> DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED
        }
        RecoveryTrace.log(
            "authority_floor_recorded", "session" to sessionId.take(24), "floor" to highWater,
            "rows" to rows.size, "outcome" to out.name,
        )
        return out
    }

    // ───────────────────────────────────────────── codec (length-prefixed, never delimited)

    /**
 * `[ver:1][flags:1][maxEverFloor:8][nextOrdinal:8][count:4]` then per row
 * `[sidLen:4][sid utf8][floor:8][ordinal:8]`.
 *
 * LENGTH-PREFIXED SEGMENTS, NOT DELIMITED FIELDS — the same rule
 * `AuthorityCodec.encodeBatches` states: a separator scheme makes
 * ("ab","c") and ("a","bc") indistinguishable, and a session id is
 * caller-supplied text.
 */
    private fun encode(f: Floors): ByteArray {
        val rows = f.rows.values.sortedBy { it.ordinal }
        var size = 1 + 1 + 8 + 8 + 4
        val sids = rows.map { it.sessionId.toByteArray(Charsets.UTF_8) }
        for (b in sids) size += 4 + b.size + 8 + 8
        val out = ByteArray(size)
        var o = 0
        out[o++] = FORMAT_VERSION.toByte()
        out[o++] = (if (f.dropped) FLAG_DROPPED else 0).toByte()
        writeLong(out, o, f.maxEverFloor); o += 8
        writeLong(out, o, f.nextOrdinal); o += 8
        AuthorityContainer.writeIntBE(out, o, rows.size); o += 4
        for (i in rows.indices) {
            val b = sids[i]
            AuthorityContainer.writeIntBE(out, o, b.size); o += 4
            System.arraycopy(b, 0, out, o, b.size); o += b.size
            writeLong(out, o, rows[i].floor); o += 8
            writeLong(out, o, rows[i].ordinal); o += 8
        }
        return out
    }

    private fun decode(p: ByteArray): Floors {
        if (p.size < 22) throw LossFormatException("floors_header_truncated")
        val ver = p[0].toInt() and 0xFF
        if (ver != FORMAT_VERSION) throw LossFormatException("floors_format_version_unsupported:$ver")
        val dropped = (p[1].toInt() and FLAG_DROPPED) != 0
        var o = 2
        val maxEver = readLong(p, o); o += 8
        val nextOrdinal = readLong(p, o); o += 8
        val count = AuthorityContainer.readIntBE(p, o); o += 4
        if (count < 0 || count > MAX_FLOORS) throw LossFormatException("floors_count_invalid:$count")
        val rows = LinkedHashMap<String, Row>()
        repeat(count) {
            if (o + 4 > p.size) throw LossFormatException("floors_row_truncated")
            val n = AuthorityContainer.readIntBE(p, o); o += 4
            if (n < 0 || o + n + 16 > p.size) throw LossFormatException("floors_row_truncated")
            val sid = String(p, o, n, Charsets.UTF_8); o += n
            val floor = readLong(p, o); o += 8
            val ord = readLong(p, o); o += 8
            if (rows.put(sid, Row(sid, floor, ord)) != null) throw LossFormatException("floors_session_twice")
            if (floor > maxEver) throw LossFormatException("floors_row_above_max_ever")
        }
        if (o != p.size) throw LossFormatException("floors_trailing_bytes")
        return Floors(rows, maxEver, dropped, nextOrdinal)
    }

    private fun writeLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (56 - 8 * i)) and 0xFF).toByte()
    }

    private fun readLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}

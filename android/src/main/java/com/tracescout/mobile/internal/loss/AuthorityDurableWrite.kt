package com.tracescout.mobile.internal.loss

import com.tracescout.mobile.queue.DurableReplace
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * THE AUTHORITY-ONLY DURABILITY PRIMITIVE (F3-P0 the specification) — the V2-B1 resolution.
 *
 * **`DurableReplace.kt` IS NOT EDITED AT ALL, AND THAT IS THE WHOLE POINT.**
 * v2 of the design proposed correcting the SHARED outcome classification for
 * every caller and adding a shared `tempName` parameter; both are WITHDRAWN. The
 * ruling is that F3-P0 must not change queue result semantics, and the specification is the
 * measured reason it must not: the F2 authority reaches the queue's
 * content-certain `stored` flag THROUGH `EncryptedEventStore`, and
 * `INDETERMINATE_DIRECTORY_UNSYNCED → AppendResult(stored = true, …)` is CORRECT
 * for that state (the content was fsynced; only the directory entry is
 * unconfirmed) — which is precisely why a **content-unknown** append reported in
 * that state would tell a caller a record is in the store when it may not be.
 *
 * ## The duplication cost, stated rather than glossed
 *
 * This is a second copy of a proven durability primitive beside the original, and
 * that cost is real. It is paid deliberately, because the only way to share the
 * code is to modify the function the queue executes, and a queue whose executed
 * code path changed is exactly what the review forbade. The mitigations are PINS,
 * not a promise:
 *
 * * the two primitives have **different contracts by design** — a shared
 * implementation returning two different classifications is harder to reason
 * about than two implementations with one classification each;
 * * a source pin asserts **no authority path calls `DurableReplace`** and **no
 * queue path calls `AuthorityDurableWrite`**, over the ACTUAL inventory rather
 * than a count;
 * * a source pin asserts `DurableReplace.kt` is **byte-unchanged** from the
 * subject of record;
 * * the outcome contracts below are pinned independently, with a fault-injection
 * row per untrue-report case.
 *
 * ## The untrue report this primitive is written NOT to make
 *
 * Read at the subject of record, `NOT_COMMITTED_OLD_RETAINED` is not a safe
 * statement in three ways: a `setLength(before)` that THROWS leaves a FRAGMENT at
 * the tail while the outcome still says old-retained; a `setLength(before)` that
 * SUCCEEDS leaves the restored length in the page cache and **never fsyncs it**,
 * so a power loss can resurrect the partial append; and a `raf.fd.sync` that
 * throws means the bytes were fully written and only the durability is unknown,
 * while the outcome says old-retained. F3-P0 does NOT fix that in the shared
 * primitive — this one is written correctly from the start, so the authority is
 * never exposed to it, and the queue's residual uncertainty is recorded as
 * out-of-scope post-acceptance queue debt rather than inherited silently or
 * quietly fixed.
 */
internal object AuthorityDurableWrite {

    /**
 * FOUR STATES, AND THE TWO INDETERMINATE ONES ARE DIFFERENT FACTS.
 */
    enum class Outcome {
        /** The bytes AND their reachability under this name are durable. */
        COMMITTED,

        /**
 * The file is PROVEN byte-unchanged, and that proof is ITSELF durable.
 *
 * MAY BE RETURNED **ONLY** WHEN:
 * ```
 * - nothing was written at all (open/mkdirs/seek failed before the
 * first byte), OR
 * - bytes were written AND the old length was restored AND that
 * restoration was ITSELF made durable (setLength succeeded AND a
 * subsequent fd.sync succeeded)
 * Anything else -> INDETERMINATE_CONTENT.
 * ```
 */
        NOT_COMMITTED_PROVEN_UNCHANGED,

        /** A write may have reached the file and the outcome is not provable. */
        INDETERMINATE_CONTENT,

        /**
 * The content is durable; the directory entry that makes it reachable
 * under this name is not.
 */
        INDETERMINATE_DIRECTORY,

        /**
 * [create] REFUSES A PRE-EXISTING TARGET — and that refusal is what
 * makes the specification's Half-4 preserve rule enforceable **AT THE PRIMITIVE**
 * rather than by reviewer discipline. An unaudited future call site
 * cannot clobber a higher generation by accident, because the function it
 * would reach for says no. [createOrAdoptEmpty] is the ONLY operation
 * permitted to encounter an existing target.
 */
        REFUSED_TARGET_EXISTS,

        /**
 * [createOrAdoptEmpty] found an authentic, correctly-roled,
 * correctly-generationed, `Wpresent == 0` file and **wrote not one byte**.
 *
 * ADOPTION IS PREFERRED OVER TRUNCATE-AND-RECREATE DELIBERATELY.
 * Truncating an existing authority artifact is a destructive write on a
 * path the default rule forbids touching; adopting one is not a write at
 * all. It also removes a failure mode: a truncate that half-succeeds
 * leaves a file whose length is neither the old nor the new one, and this
 * operation cannot express `INDETERMINATE_CONTENT`, so that outcome would
 * have no vocabulary to describe it.
 */
        ADOPTED,

        /** [createOrAdoptEmpty] met a target that is not an adoptable empty one. */
        REFUSED_NOT_ADOPTABLE,
    }

    /** Unique temp names are INTERNAL to this primitive — never a parameter anyone can get wrong. */
    private val tempRandom = SecureRandom()

    /**
 * UNIQUE TEMP NAMES ARE SAFE **ONLY** UNDER THE the specification ROOT LOCK, and the
 * measured interleaving is why:
 *
 * ```
 * Fixed temp, no lock (today): two writers collide on one temp file; the
 * loser returns NOT_COMMITTED_OLD_RETAINED and pruneOne
 * correctly refuses to delete. A row is lost, BUT the loser knows its write
 * did not land, so no directory is deleted and no session can genesis at 0.
 * The shared temp name is accidentally serialising by failure.
 *
 * Unique temp, no lock: both writers succeed, BOTH report COMMITTED, one row
 * is silently lost — and both are now authorised to delete their session
 * directories. The session whose row vanished later genesises at 0 and
 * REUSES SEQUENCES. A sequence-reuse vector created by a change that looks
 * like pure hygiene.
 * ```
 *
 * So the lock lands **with or before** this primitive, never after, and the P5 acceptance row asserts the LOST UPDATE is
 * prevented, not merely that the floors file is readable.
 */
    private fun tempFor(target: File): File {
        val b = ByteArray(16).also { tempRandom.nextBytes(it) }
        return File(target.parentFile, target.name + ".tmp." + AuthorityContainer.hex(b))
    }

    /** The stale-temp sweep's subject — `<name>.tmp.<32 hex>` and NOTHING else. */
    val TEMP_RE: Regex = Regex("^.+\\.tmp\\.[0-9a-f]{32}$")

    /**
 * Append one complete frame. The file MUST already exist.
 *
 * ```
 * can return COMMITTED · NOT_COMMITTED_PROVEN_UNCHANGED · INDETERMINATE_CONTENT
 * CANNOT return INDETERMINATE_DIRECTORY
 * ```
 *
 * The exclusion is structural, not a preference: the WAL is created as a
 * separate [create] / [createOrAdoptEmpty] operation,
 * so an append never adds a directory entry and there is no directory state
 * to be uncertain about. the specification F13 pins that the vocabulary is unable to
 * express the queue's content-certain state on an append — it is not enough
 * that the authority HAS its own vocabulary.
 */
    fun append(target: File, frame: ByteArray): Outcome {
        if (!target.isFile) return Outcome.NOT_COMMITTED_PROVEN_UNCHANGED
        var wroteAnything = false
        try {
            RandomAccessFile(target, "rw").use { raf ->
                val before = raf.length()
                raf.seek(before)
                try {
                    AuthorityFaults.at(AuthorityFaults.Point.WRITE_AT_APPEND_WRITE)
                    raf.write(frame)
                    wroteAnything = true
                } catch (t: Throwable) {
                    if (t is AuthorityFaults.SimulatedFault) throw t
                    // THE RESTORATION MUST ITSELF BE DURABLE, or the claim
                    // "proven unchanged" is a statement about the page cache.
                    return restoreDurably(raf, before)
                }
                // `true` = metadata too, so the new LENGTH is on disk, not only
                // the bytes. A THROW here means the bytes were fully written and
                // only their durability is unknown — which is INDETERMINATE
                // CONTENT, never old-retained.
                try {
                    AuthorityFaults.at(AuthorityFaults.Point.WRITE_AT_DATA_SYNC)
                    raf.fd.sync()
                } catch (t: Throwable) {
                    if (t is AuthorityFaults.SimulatedFault) throw t
                    return Outcome.INDETERMINATE_CONTENT
                }
            }
        } catch (t: Throwable) {
            if (t is AuthorityFaults.SimulatedFault) throw t
            // A throw from `use{}`'s own close can follow a COMPLETED write.
            return if (wroteAnything) Outcome.INDETERMINATE_CONTENT else Outcome.NOT_COMMITTED_PROVEN_UNCHANGED
        }
        return Outcome.COMMITTED
    }

    private fun restoreDurably(raf: RandomAccessFile, before: Long): Outcome {
        try {
            AuthorityFaults.at(AuthorityFaults.Point.WRITE_AT_SETLENGTH)
            raf.setLength(before)
        } catch (t: Throwable) {
            if (t is AuthorityFaults.SimulatedFault) throw t
            // the specification case 1: a FRAGMENT remains at the tail.
            return Outcome.INDETERMINATE_CONTENT
        }
        try {
            AuthorityFaults.at(AuthorityFaults.Point.WRITE_AT_RESTORE_SYNC)
            raf.fd.sync()
        } catch (t: Throwable) {
            if (t is AuthorityFaults.SimulatedFault) throw t
            // the specification case 2: the restored length is in the page cache and is NOT on
            // disk, so a power loss can resurrect the partial append.
            return Outcome.INDETERMINATE_CONTENT
        }
        return Outcome.NOT_COMMITTED_PROVEN_UNCHANGED
    }

    /**
 * Whole-file write to a path that **MUST NOT** exist.
 *
 * ```
 * can return COMMITTED · NOT_COMMITTED_PROVEN_UNCHANGED ·
 * INDETERMINATE_DIRECTORY · REFUSED_TARGET_EXISTS
 * CANNOT return INDETERMINATE_CONTENT
 * ```
 *
 * The target is only ever installed by an ATOMIC MOVE, so it is never
 * partially written: a partial result is removed and its absence fsynced, or
 * the outcome is the directory one. `ATOMIC_MOVE` either performs the
 * replacement or throws — a PLATFORM property, stated rather than assumed,
 * and pinned.
 */
    fun create(target: File, bytes: ByteArray, dirSync: DurableReplace.DirSync): Outcome {
        if (target.exists()) return Outcome.REFUSED_TARGET_EXISTS
        return installWhole(target, bytes, dirSync)
    }

    /**
 * Replace [target]'s entire contents atomically.
 *
 * ```
 * can return COMMITTED · NOT_COMMITTED_PROVEN_UNCHANGED · INDETERMINATE_DIRECTORY
 * CANNOT return INDETERMINATE_CONTENT
 * ```
 */
    fun replace(target: File, bytes: ByteArray, dirSync: DurableReplace.DirSync): Outcome =
        installWhole(target, bytes, dirSync)

    private fun installWhole(target: File, bytes: ByteArray, dirSync: DurableReplace.DirSync): Outcome {
        val parent = target.parentFile
        // THE TEMP IS IN THE TARGET'S OWN DIRECTORY. `ATOMIC_MOVE` is only
        // defined within one filesystem, and a temp elsewhere would silently
        // degrade to copy-then-unlink — the exact non-atomic behaviour being
        // eliminated. A temp under /tmp is a DIFFERENT filesystem on this
        // platform, which is how that degradation gets in unnoticed.
        val tmp = tempFor(target)
        try {
            parent?.mkdirs()
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                raf.write(bytes)
                // Contents first. A directory entry pointing at bytes that are
                // not on disk is worse than no entry at all.
                raf.fd.sync()
            }
        } catch (t: Throwable) {
            if (t is AuthorityFaults.SimulatedFault) throw t
            tmp.delete()
            return Outcome.NOT_COMMITTED_PROVEN_UNCHANGED
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            if (t is AuthorityFaults.SimulatedFault) throw t
            // The OLD file remains authoritative and is PROVEN byte-unchanged: an
            // atomic move either replaced it or did not. A `copyTo` fallback here
            // would trade a failed operation for a corrupted store.
            tmp.delete()
            return Outcome.NOT_COMMITTED_PROVEN_UNCHANGED
        }
        // The move has happened. From here the operation CANNOT be reported as
        // unchanged, whatever else fails.
        //
        // A DIRECTORY SYNC THAT **THROWS** IS THE SAME FACT AS ONE THAT RETURNS
        // FALSE: the entry may or may not be durable. Letting it propagate would
        // make the caller's `when` unreachable and turn a NAMED indeterminate state
        // into an exception nobody classified — and every caller of this primitive
        // is on a path whose whole contract is that its outcome is one of four
        // named states.
        val synced = try {
            parent != null && dirSync.sync(parent)
        } catch (t: Throwable) {
            if (t is AuthorityFaults.SimulatedFault) throw t
            false
        }
        return if (synced) Outcome.COMMITTED else Outcome.INDETERMINATE_DIRECTORY
    }

    /** What [createOrAdoptEmpty] ended up with, and — on adoption — WHOSE instance id the witness must bind. */
    class CreateOrAdopt(val outcome: Outcome, val fileInstanceIdHex: String?)

    /**
 * THE ONLY OPERATION PERMITTED TO MEET AN EXISTING HIGHER-GENERATION TARGET
 * and reachable ONLY from the compaction
 * writer, after IC-verification has proven E1.E4.
 *
 * ```
 * absent -> mint a NEW fileInstanceId and create it -> COMMITTED
 * present and empty -> ADOPT IT UNCHANGED, read its OWN
 * fileInstanceId from its preamble;
 * NOT ONE BYTE IS WRITTEN -> ADOPTED
 * anything else -> REFUSED_NOT_ADOPTABLE
 * ```
 *
 * **IT NEVER TRUNCATES.** A truncate is a destructive write on a path the specification
 * forbids touching, and a half-done truncate would need an
 * `INDETERMINATE_CONTENT` this operation cannot express.
 *
 * **THE WITNESS BINDS WHICHEVER INSTANCE ID THIS ENDS UP WITH**. That is what keeps R16 meaningful across a compaction retry: an adopted
 * file's OWN id is the id the witness names, so a LATER substitution of that
 * same-generation WAL is still caught. Minting a fresh id while adopting the
 * existing file is the specification D9's fourth control — it makes R16 dead for that
 * generation.
 *
 * [probe] answers "is this existing file an authentic, correctly-roled,
 * correctly-generationed container with ZERO body frames?" and returns its
 * `fileInstanceId` hex when it is. It is supplied by the container IO rather
 * than implemented here, because deciding what a v2 container IS is the
 * container IO's job and durability is this object's (the specification: a container IO
 * that also fsyncs is two contracts in one object).
 */
    fun createOrAdoptEmpty(
        target: File,
        bytes: () -> ByteArray,
        newInstanceIdHex: String,
        dirSync: DurableReplace.DirSync,
        probe: (File) -> String?,
    ): CreateOrAdopt {
        if (!target.exists()) {
            val o = installWhole(target, bytes(), dirSync)
            return CreateOrAdopt(o, if (o == Outcome.COMMITTED || o == Outcome.INDETERMINATE_DIRECTORY) newInstanceIdHex else null)
        }
        val existing = probe(target) ?: return CreateOrAdopt(Outcome.REFUSED_NOT_ADOPTABLE, null)
        return CreateOrAdopt(Outcome.ADOPTED, existing)
    }

    /**
 * The stale-temp sweep. Unique temp names leave
 * residue after a crash (the fixed name was self-cleaning by overwrite).
 *
 * IT MATCHES **ONLY** `<name>.tmp.<32 hex>` and is pinned never to match an
 * authority file, the root lock file, the floors file, the head witness, a
 * generation-named snapshot, a v1 file or a v3 file. It runs under
 * the the specification lock, because a sweep is the one place a too-broad match is
 * destructive rather than merely noisy.
 */
    fun sweepStaleTemps(dir: File): Int {
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && TEMP_RE.matches(f.name) && f.delete()) n++
        }
        return n
    }
}

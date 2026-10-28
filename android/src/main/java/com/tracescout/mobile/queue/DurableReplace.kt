package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The ONE durable whole-file replacement primitive.
 *
 * ## The two gaps this closes
 *
 * The store already wrote a temp file, fsynced its CONTENTS and renamed it. Two
 * things were missing, and both are about the DIRECTORY rather than the file:
 *
 * 1. **A rename is a directory operation.** `fd.sync` on the temp file
 * commits the temp file's bytes; it says nothing about the directory entry
 * that makes those bytes reachable under the target name. After a power
 * loss the directory can still name the OLD inode — so a removal the caller
 * was told committed silently un-happens, and the records it reported as
 * delivered-and-dropped come back.
 * 2. **`File.renameTo` is not required to be atomic.** It returns a bare
 * `false` that cannot distinguish "the filesystem refused" from "this
 * filesystem does not do atomic renames", which is the one property the
 * whole temp-file dance is buying. `Files.move(…, ATOMIC_MOVE)` either
 * performs an atomic replacement or throws — no silent third behaviour.
 *
 * ## Why the result is three-valued
 *
 * Because the state after a failed directory sync is genuinely a THIRD state,
 * and collapsing it destroys the only information a caller could act on:
 *
 * ```
 * COMMITTED the new contents are durable AND reachable
 * NOT_COMMITTED_OLD_RETAINED nothing changed; the old file is authoritative
 * INDETERMINATE_DIRECTORY_UNSYNCED
 * the move SUCCEEDED and the directory entry
 * was not confirmed durable. After a crash the
 * file may be the new one OR the old one.
 * ```
 *
 * **INDETERMINATE MUST NOT BE REPORTED AS EITHER NEIGHBOUR.** Reported as
 * committed, a caller finalises a loss that may not have happened and drops
 * records that may still exist. Reported as old-retained, a caller re-does an
 * operation that may already have taken effect. Both are defensible-looking
 * and both are wrong; the only honest answer is the one that says so.
 *
 * ## What this deliberately is NOT
 *
 * Not a write-ahead log, not a journal, not a transaction manager. It replaces
 * one file's contents and reports what it knows. The PREPARED/COMMITTED
 * machinery the loss foundation will need belongs in F1, above this.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object DurableReplace {

    enum class Outcome {
        COMMITTED,
        NOT_COMMITTED_OLD_RETAINED,
        INDETERMINATE_DIRECTORY_UNSYNCED,
    }

    /**
 * Fsync a DIRECTORY, returning whether it is confirmed durable.
 *
 * **A SEAM SO THE GUARANTEE IS OBSERVABLE.** P0-14 is
 * *"parent-directory fsync happens"* with the negative control *"drop the
 * directory fsync"*; a recording implementation is what makes that a
 * measurement of WHICH directory was synced and WHEN, rather than a
 * source-grep that cannot see either.
 *
 * **AN EARLIER VERSION OF THIS COMMENT WAS WRONG AND IS CORRECTED
 * HERE.** It asserted that `FileInputStream` and `FileChannel.open` both
 * refuse a directory on a plain JVM, and concluded the production path
 * could only be `android.system.Os`. Measured on this host's JVM:
 * `FileInputStream` does refuse (`Is a directory`), and
 * `FileChannel.open(dir, READ).force(true)` **succeeds**. Had the wrong
 * belief stood, every JVM-venue rewrite would have reported
 * INDETERMINATE — the third state becoming the default in the only venue
 * that can test it, which would have made it worthless.
 *
 * **IT FAILS CLOSED BY RETURNING FALSE, NEVER BY THROWING.** A throw
 * here would propagate out of a replacement that had already succeeded,
 * and the caller would classify a committed move as a failure — the worse
 * of the two collapses.
 */
    fun interface DirSync {
        fun sync(dir: File): Boolean
    }

    /**
 * The production implementation: **`android.system.Os.fsync` first, the
 * portable `FileChannel` second, `false` only if neither worked.**
 *
 * `Os` is the canonical Android route and is tried first because it is the
 * one the platform documents. The `FileChannel` fallback is what makes the
 * SAME code path real in a JVM unit test — so the durability guarantee is
 * exercised in the venue that tests it, rather than being a device-only
 * claim nothing verifies.
 *
 * The `Os` half is reflective ON PURPOSE: `:sdk` unit tests run on a plain
 * JVM where `android.system.Os` is a stub that throws, and a hard
 * reference would make this file unloadable there.
 */
    val platformDirSync = DirSync { dir ->
        osFsync(dir) || channelFsync(dir)
    }

    private fun channelFsync(dir: File): Boolean = try {
        java.nio.channels.FileChannel
            .open(dir.toPath(), java.nio.file.StandardOpenOption.READ)
            .use { it.force(true) }
        true
    } catch (_: Throwable) {
        false
    }

    private fun osFsync(dir: File): Boolean {
        return try {
            val os = Class.forName("android.system.Os")
            val consts = Class.forName("android.system.OsConstants")
            val rdonly = consts.getField("O_RDONLY").getInt(null)
            val open = os.getMethod("open", String::class.java, Int::class.java, Int::class.java)
            val fd = open.invoke(null, dir.path, rdonly, 0)
            try {
                os.getMethod("fsync", java.io.FileDescriptor::class.java).invoke(null, fd)
                true
            } finally {
                try {
                    os.getMethod("close", java.io.FileDescriptor::class.java).invoke(null, fd)
                } catch (_: Throwable) {
                    // The sync already happened or already failed; a failed
                    // close cannot retract either answer.
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
 * Replace [target]'s entire contents with whatever [writeAll] writes.
 *
 * The temp file is created in the TARGET'S OWN DIRECTORY — `ATOMIC_MOVE`
 * is only defined within one filesystem, and a temp elsewhere would
 * silently degrade to copy-then-unlink, which is the exact non-atomic
 * behaviour being eliminated.
 */
    fun replace(
        target: File,
        dirSync: DirSync = platformDirSync,
        writeAll: (RandomAccessFile) -> Unit,
    ): Outcome {
        val parent = target.parentFile
        val tmp = File(parent, target.name + ".tmp")
        try {
            parent?.mkdirs()
            tmp.delete()
            RandomAccessFile(tmp, "rw").use { raf ->
                writeAll(raf)
                // Contents first. A directory entry pointing at bytes that are
                // not on disk is worse than no entry at all.
                raf.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (t: Throwable) {
                // The OLD file remains authoritative. A `copyTo` fallback here
                // would trade a failed operation for a corrupted store.
                tmp.delete()
                return Outcome.NOT_COMMITTED_OLD_RETAINED
            }
        } catch (t: Throwable) {
            tmp.delete()
            return Outcome.NOT_COMMITTED_OLD_RETAINED
        }

        // The move has happened. From here the operation CANNOT be reported as
        // old-retained, whatever else fails.
        return if (parent != null && dirSync.sync(parent)) {
            Outcome.COMMITTED
        } else {
            Outcome.INDETERMINATE_DIRECTORY_UNSYNCED
        }
    }

    /**
 * Append [frame] to [target] durably, reporting the same three states.
 *
 * The directory sync is required ONLY when this call CREATES the file: an
 * append to an existing file adds no directory entry, so the entry is
 * already whatever it was and there is nothing new to commit. Syncing the
 * directory on every append would put a second fsync on the hot capture
 * path to confirm a fact that has not changed.
 */
    fun appendDurably(
        target: File,
        frame: ByteArray,
        dirSync: DirSync = platformDirSync,
    ): Outcome {
        val parent = target.parentFile
        val created = !target.exists()
        try {
            parent?.mkdirs()
            RandomAccessFile(target, "rw").use { raf ->
                val before = raf.length()
                raf.seek(before)
                try {
                    raf.write(frame)
                } catch (t: Throwable) {
                    // PREVENT CREATING CORRUPTION. A partial write leaves a
                    // FRAGMENT at the tail, which the next read classifies as a
                    // torn tail — and under the fail-closed rule that wedges
                    // every destructive operation on this store. Truncating
                    // back to the exact pre-write length is the narrow hygiene
                    // that stops a failed append from manufacturing a corrupt
                    // store. It reclaims nothing and is not a step toward
                    // doing so.
                    try { raf.setLength(before) } catch (_: Throwable) { }
                    return Outcome.NOT_COMMITTED_OLD_RETAINED
                }
                // `true` = metadata too, so the new LENGTH is on disk and not
                // only the bytes.
                raf.fd.sync()
            }
        } catch (t: Throwable) {
            return Outcome.NOT_COMMITTED_OLD_RETAINED
        }
        if (!created) return Outcome.COMMITTED
        return if (parent != null && dirSync.sync(parent)) {
            Outcome.COMMITTED
        } else {
            Outcome.INDETERMINATE_DIRECTORY_UNSYNCED
        }
    }
}

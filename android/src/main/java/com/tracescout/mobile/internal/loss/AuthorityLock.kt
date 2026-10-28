package com.tracescout.mobile.internal.loss

import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * THE ONE ROOT-LEVEL CROSS-PROCESS AUTHORITY LOCK (F3-P0 the specification).
 *
 * ```
 * <filesDir>/tracescout-authority-v2/authority.lock
 * ```
 *
 * ## What P5 measured, and the fact that makes it reachable
 *
 * 550 barrier-started concurrent `recordFloor` races: **550/550 lost a row**,
 * 0/550 had both writers report COMMITTED. In-process every caller is serialised
 * by the `handles` monitor, **so the race needs the host app to initialise the SDK
 * in more than one process — and the SDK has no main-process gate** (grep for
 * `processName` / `isMainProcess` / `cmdline` in `:sdk`/`:rn` main: zero hits).
 * Confirmed independently: no `FileLock` / `tryLock` / `.lock` anywhere in
 * `:sdk` or core main sources.
 *
 * **DO NOT CLOSE THIS WITH A SINGLE-PROCESS CONTRACT.** There is no product
 * contract saying the host app initialises the SDK once, and no enforcement; an
 * assumption with neither is a hope.
 *
 * the specification is justified on the **LOST UPDATE** (550/550 measured), not on the
 * corruption (observed once in 550 and explicitly not reproduced). Readability is
 * the once-observed symptom; the lost update is the defect.
 *
 * ## Scope: EVERY v2 authority and floors mutation, AND EVERY OPEN
 *
 * An open reads, may repair the witness (R8/R10/R19), may complete an interrupted
 * compaction and may sweep — so it is a mutation window.
 *
 * **ACQUIRED BEFORE ANY ARTIFACT BYTE IS READ — NOT MERELY BEFORE ANY WRITE**
 *. v3 of the design placed `LOCK` at row R3, i.e.
 * AFTER R1's `V3SIG` sniff — and `V3Legacy.signatureOf` **OPENS AND READS THE
 * FILE**. So the v3 order read artifact bytes outside the lock while another
 * process could be mid-`replace`: exactly the interleaving the specification exists to remove.
 * The row ORDER is a REFUSAL PRECEDENCE order and was never an acquisition order;
 * v3 simply never said so, and a reader implementing the table top-to-bottom would
 * have got it wrong. the specification F17 pins acquisition before the first read and S16 is
 * widened: no read, no repair and no sweep outside the lock.
 *
 * ```
 * inside the lock the v3-signature sniff (it OPENS THE FILE)
 * every preamble parse and every container-band check
 * every header and body AEAD attempt
 * the Wpresent / Wauth / D derivation
 * every repair (R8, R10, R19), IC2's witness advance
 * the orphan sweep and the stale-temp sweep
 * the floors read-modify-write
 * outside the lock the v1 PATH test ONLY (File.isDirectory on the v1 root — it
 * reads no authority byte, the specification), and it is inside in practice
 * because L0 precedes it
 * ```
 *
 * ## THE LOCK FILE IS AT THE ROOT, NEVER INSIDE A SESSION DIRECTORY
 *
 * The reason is MEASURED. `LossAuthority.pruneOne` deletes by EXCLUSION:
 *
 * ```kotlin
 * val files = d.listFiles?.filter { it.isFile && it.name != PRUNED_MARKER } ?: emptyList
 * for (f in files) if (!f.delete) return "delete_failed:${f.name.take(32)}"
 * ```
 *
 * Everything in the session directory except the `pruned` marker is deleted. A
 * `<sessionDir>/session.lock` — which is what v1 of this design specified — would
 * therefore be **UNLINKED BY PRUNE WHILE ANOTHER PROCESS HELD A LOCK ON IT**,
 * which makes the lock stop excluding anything. That is the classic
 * unlink-the-lockfile bug, created by the design's own placement, in the one
 * routine that enumerates by exclusion so no amount of naming discipline would
 * have saved it. A root-level file is structurally out of `pruneOne`'s reach
 *.
 *
 * ## Why ONE lock and not a hierarchy
 *
 * A single lock cannot deadlock (there is no order), cannot invert (there is no
 * second lock), leaves no per-session residue, and cannot be unlinked by a routine
 * that deletes a session directory. Those are exactly the four hazards a
 * finer-grained scheme introduces, and v1 of this design hit three.
 *
 * **The cost, stated rather than glossed:** authority mutation serializes ACROSS
 * SESSIONS within an install. An app capturing several sessions concurrently in
 * more than one process will contend on one lock. The mitigation is that the
 * critical section is short — an append plus a witness replace, measured at
 * 1.5–1.7 ms (API 35) and 3.2–3.4 ms (API 26) — not that the cost is zero.
 * **If a physical measurement shows the serialized critical section is the
 * dominant admission cost, the answer is to reopen a product decision (SG-1), NOT to introduce a
 * finer lock:** re-introducing per-session locks brings back all four hazards.
 */
internal object AuthorityLock {

    const val LOCK_FILE: String = "authority.lock"

    /** Bounded acquisition. NEVER a blocking wait. */
    private const val ATTEMPTS: Int = 10
    private const val SLEEP_MS: Long = 20L

    /**
 * One entry per lock FILE path. Held **once per process** and
 * reference-counted.
 *
 * **THE FILE LOCK DOES NOT REPLACE THE IN-PROCESS MONITOR; IT COMPOSES WITH
 * IT.** Within one JVM a second `FileLock` on the same file throws
 * `OverlappingFileLockException`, so the OS lock is acquired once and counted,
 * with the existing monitor still serialising in-process callers. Order is
 * always **monitor, then file lock**.
 */
    private class Held(val raf: RandomAccessFile, val channel: FileChannel, val lock: FileLock) {
        var refs: Int = 0
    }

    private val monitor = Any()
    private val held = HashMap<String, Held>()

    /**
 * TEST-ONLY. Every successful acquisition and every release is announced, so a
 * test can assert the SHAPE of the acquisition — not merely that a lock was
 * held at some instant.
 *
 * **"THE LOCK WAS HELD WHEN THE FLOORS WERE READ" IS A STRICTLY WEAKER
 * CLAIM THAN the specification F17/S16 MAKE, AND THE DIFFERENCE IS MEASURED.** A build that
 * reads the floors through the ACQUIRING wrapper before the open takes its own
 * lock holds a lock at every read and still splits the decision boundary
 * across two acquisitions — the exact blocker shape. Run as a negative control
 * against a read-count/held-state assertion it comes back **NOT_CAUGHT**; only
 * an assertion over the acquire/release SEQUENCE can see it.
 *
 * Null in production; no call site outside tests assigns it.
 */
    @Volatile
    internal var eventObserverForTest: ((String) -> Unit)? = null

    private fun ok(path: String): Acquisition.Ok {
        eventObserverForTest?.invoke("acquire")
        return Acquisition.Ok(path)
    }

    sealed class Acquisition {
        /** Release exactly once. */
        class Ok(private val path: String) : Acquisition() {
            private var released = false
            fun release() {
                if (released) return
                released = true
                releaseOne(path)
            }
        }

        /**
 * A TYPED REFUSAL, AND **THERE IS NO "PROCEED UNLOCKED" BRANCH**
 *. An SDK that blocks the capture path forever is worse than one
 * that refuses; an SDK that proceeds unlocked is worse than both.
 */
        data class Unavailable(val reason: String) : Acquisition()
    }

    /**
 * Take the lock for the authority ROOT under [root], with a bounded retry.
 *
 * `java.nio.channels.FileChannel.tryLock` — an **OS-LEVEL** lock.
 * `synchronized` is process-local and cannot see the other process, which is
 * the whole defect.
 */
    fun acquire(root: File): Acquisition {
        synchronized(monitor) {
            if (!root.isDirectory && !root.mkdirs() && !root.isDirectory)
                return Acquisition.Unavailable("authority_lock_unavailable:root_not_creatable")
            val file = File(root, LOCK_FILE)
            val path = file.absolutePath
            held[path]?.let { h -> h.refs++; return ok(path) }

            var lastWhy = "tryLock_refused"
            for (attempt in 0 until ATTEMPTS) {
                try {
                    // THE FILE IS CREATED ONCE AND NEVER DELETED OR TRUNCATED,
                    // including by `resetForTest` / `destroyAll`: those release the
                    // reference-counted handle and leave the zero-byte file in
                    // place. Unlinking it while another process holds a lock on it
                    // is the bug this placement exists to avoid.
                    val raf = RandomAccessFile(file, "rw")
                    val ch = raf.channel
                    val l = try {
                        ch.tryLock()
                    } catch (t: Throwable) {
                        try { raf.close() } catch (_: Throwable) {}
                        lastWhy = t.javaClass.simpleName
                        null
                    }
                    if (l != null) {
                        val h = Held(raf, ch, l); h.refs = 1
                        held[path] = h
                        return ok(path)
                    }
                    if (l == null && lastWhy == "tryLock_refused") {
                        // tryLock returned null: another PROCESS holds it.
                        try { raf.close() } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    lastWhy = t.javaClass.simpleName
                }
                if (attempt < ATTEMPTS - 1) {
                    try { Thread.sleep(SLEEP_MS) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return Acquisition.Unavailable("authority_lock_unavailable:interrupted")
                    }
                }
            }
            RecoveryTrace.log("authority_lock_unavailable", "why" to lastWhy.take(24))
            return Acquisition.Unavailable("authority_lock_unavailable")
        }
    }

    private fun releaseOne(path: String) {
        eventObserverForTest?.invoke("release")
        synchronized(monitor) {
            val h = held[path] ?: return
            h.refs--
            if (h.refs > 0) return
            held.remove(path)
            // THE FILE IS NOT DELETED. Only the handle is closed.
            try { h.lock.release() } catch (_: Throwable) {}
            try { h.channel.close() } catch (_: Throwable) {}
            try { h.raf.close() } catch (_: Throwable) {}
        }
    }

    /**
 * **PROCESS DEATH RELEASES THE LOCK BY fd / PROCESS TEARDOWN — THERE IS NO
 * CLEANUP HANDLER AND THERE MUST NOT BE ONE**.
 *
 * An advisory `FileLock` is held by the OPEN FILE DESCRIPTION; the kernel drops
 * every lock a process holds when its fds are torn down, whether the process
 * exited normally, was killed, or died on a signal. So a crash
 * mid-critical-section leaves the lock FREE and the on-disk state in one of
 * the specification's named rows — never a stale lock that wedges the store for the life of
 * the install.
 *
 * A `shutdownHook`-based release would be **strictly worse**: it does not
 * run on SIGKILL, so it would add a path that can fail while IMPLYING the lock
 * needs releasing by us. This function exists for `resetForTest` only, and it
 * releases the handle — it does not unlink the file.
 */
    fun releaseAllForTest() {
        synchronized(monitor) {
            for ((_, h) in held) {
                try { h.lock.release() } catch (_: Throwable) {}
                try { h.channel.close() } catch (_: Throwable) {}
                try { h.raf.close() } catch (_: Throwable) {}
            }
            held.clear()
        }
    }

    /** Diagnostic: is this process holding the root lock? */
    fun isHeldForTest(root: File): Boolean = synchronized(monitor) {
        held.containsKey(File(root, LOCK_FILE).absolutePath)
    }
}

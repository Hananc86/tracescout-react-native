package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import java.io.File

/**
 * F4-P0 Slice D (D6, the capture specification U-1) — the ONE durable, NON-KEY-BACKED record
 * that an erasure of this install has been requested and not yet completed:
 * `<filesDir>/tracescout-erasure.requested`.
 *
 * ## Why it is not key-backed
 *
 * The erasure destroys BOTH key domains (U-6). A request sealed under either
 * key would become unreadable at the exact step it exists to survive, so the
 * next process could not tell "an erasure is half done" from "nothing was
 * ever requested" — and would resume normal capture over a queue whose key is
 * gone. The record therefore carries no record content, no credential and no
 * ciphertext: a version, a wall-clock stamp, a phase name and a bounded note.
 * It is deliberately absent from `QueueKeyStores.ALL` and is cleared
 * explicitly at U-8, after the cleanup that `ALL` drives.
 *
 * ## Fail closed
 *
 * An UNREADABLE request is PRESENT (M-4's rule, applied to the request): a
 * torn or undecodable file blocks normal work exactly as a readable request
 * does, and the resume path treats it as the `requested` phase and rewrites
 * it durably. Absent means absent only when the file does not exist.
 *
 * ## Phases
 *
 * * [PHASE_REQUESTED] — U-1 has run; U-2 refuses every new append, bracket
 * and drain; U-3.U-8 are owed.
 * * [PHASE_TERMINAL_TEARDOWN] — U-5 ran under the fence: the recovery of an
 * eviction bracket could NOT be concluded and the store is being erased,
 * so those intents will never be resolved. A later resume does NOT
 * re-attempt resolution (W-36) and proceeds at U-6.
 *
 * Durability: `DurableReplace.replace` (temp → `fd.sync` → atomic rename →
 * parent-directory fsync) and a READ-BACK; removal is delete → parent fsync →
 * verified absent. Both halves say so in their typed result; neither is
 * assumed from a return code.
 *
 * Lock order: a caller holds the queue region (and fence) BEFORE entering
 * this monitor where the decision matters (the resume path); the read-only
 * `presentOrUnreadable` gate is a file read and takes nothing.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class ErasureRequest internal constructor(
    private val file: File,
    private val dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
) {

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class Record(
        val requestedAtMs: Long,
        /** [PHASE_REQUESTED] or [PHASE_TERMINAL_TEARDOWN]. */
        val phase: String,
        /** A bounded typed note (why the teardown became terminal). Never content. */
        val note: String?,
    )

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Read {
        /** `record == null` means NO erasure is requested. */
        data class Ok(val record: Record?) : Read()

        /** The file exists and could not be read or parsed. PRESENT, never absent. */
        data class Unreadable(val reason: String) : Read()
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Write {
        object Committed : Write()

        /** The write (or removal) could not be verified durable. The caller must not proceed on it. */
        data class NotDurable(val reason: String) : Write()
    }

    fun path(): String = file.path

    @Synchronized
    fun read(): Read = readFile()

    /** True when a request is on disk OR the file cannot be read (fail closed). The U-2 gate. */
    @Synchronized
    fun presentOrUnreadable(): Boolean = when (val r = readFile()) {
        is Read.Ok -> r.record != null
        is Read.Unreadable -> true
    }

    /** Install (or refresh) [r] durably and read it back. */
    @Synchronized
    fun install(r: Record): Write {
        if (r.phase != PHASE_REQUESTED && r.phase != PHASE_TERMINAL_TEARDOWN) return Write.NotDurable("phase_unknown")
        val bytes = encode(r).toByteArray(Charsets.UTF_8)
        val outcome = try {
            DurableReplace.replace(file, dirSync) { raf -> raf.write(bytes) }
        } catch (t: Throwable) {
            return Write.NotDurable(t.javaClass.simpleName)
        }
        return when (outcome) {
            DurableReplace.Outcome.COMMITTED -> {
                val back = readFile()
                if (back is Read.Ok && back.record == r) Write.Committed else Write.NotDurable("readback_mismatch")
            }
            DurableReplace.Outcome.INDETERMINATE_DIRECTORY_UNSYNCED -> Write.NotDurable("directory_unsynced")
            DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED -> Write.NotDurable("not_committed")
        }
    }

    /** U-8: delete, parent-directory fsync, verify absent. Absent is Committed. */
    @Synchronized
    fun clear(): Write {
        if (!file.exists()) return Write.Committed
        try { file.delete() } catch (_: Throwable) { /* verified below */ }
        if (file.exists()) return Write.NotDurable("delete_failed")
        val parent = file.parentFile
        if (parent == null || !dirSync.sync(parent)) {
            RecoveryTrace.log("erasure_request_removal_unsynced")
            return Write.NotDurable("directory_unsynced")
        }
        return Write.Committed
    }

    private fun readFile(): Read {
        if (!file.exists()) return Read.Ok(null)
        return try {
            val r = decode(file.readText(Charsets.UTF_8)) ?: return Read.Unreadable("undecodable")
            Read.Ok(r)
        } catch (t: Throwable) {
            Read.Unreadable(t.javaClass.simpleName)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        private const val VERSION = 1

        /** The request lives in the install's files directory, beside both queues, outside `QueueKeyStores.ALL`. */
        const val FILE_NAME: String = "tracescout-erasure.requested"

        const val PHASE_REQUESTED: String = "requested"
        const val PHASE_TERMINAL_TEARDOWN: String = "terminal_teardown"

        /** The request for the install whose queues live in [filesDir]. Both queue homes resolve this same file. */
        @JvmStatic
        @JvmOverloads
        fun forFilesDir(filesDir: File, dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync): ErasureRequest =
            ErasureRequest(File(filesDir, FILE_NAME), dirSync)

        @JvmStatic
        fun encode(r: Record): String = org.json.JSONObject()
            .put("v", VERSION)
            .put("requestedAtMs", r.requestedAtMs)
            .put("phase", r.phase)
            .put("note", r.note ?: org.json.JSONObject.NULL)
            .toString()

        @JvmStatic
        fun decode(text: String): Record? = try {
            val o = org.json.JSONObject(text)
            if (o.optInt("v", -1) != VERSION) null else {
                val phase = o.getString("phase")
                if (phase != PHASE_REQUESTED && phase != PHASE_TERMINAL_TEARDOWN) null
                else Record(o.getLong("requestedAtMs"), phase, if (o.isNull("note")) null else o.getString("note"))
            }
        } catch (_: Throwable) { null }
    }
}

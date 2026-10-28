package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.queue.DurableReplace
import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File

/**
 * F4-P0 Slice C (C2) — the ONE durable marker of an eviction bracket on a queue
 * home: `<queue file>.bracket.json`, beside the queue, owned by `QueueHome`.
 *
 * ## What it binds
 *
 * One [Marker] binds, by digest, everything a later recovery needs to decide a
 * bracket's outcome WITHOUT guessing:
 * the queue it governs, the EXACT source image (`logicalFingerprint` before),
 * the EXACT planned target image (after), the incoming record's identity, the
 * ordered victim digests and their count, and the intended cause. The
 * [Marker.bracketId] is a digest OVER all of those, so two brackets that differ
 * in any bound fact have different ids and an intent written under one can
 * never be mistaken for the other's.
 *
 * ## Durability (M-2)
 *
 * Installed through `DurableReplace.replace` — temp file in the same directory
 * → `fd.sync` → atomic rename → PARENT-DIRECTORY fsync — and then READ BACK
 * and compared. Only [Write.Committed] permits the first PREPARE; a rename is a
 * directory operation, and `fd.sync` on the file says nothing about the entry
 * that makes it reachable. Removal is delete → parent-directory fsync → verified
 * absent, for the same reason in the other direction.
 *
 * ## Fail closed (M-4, M-7)
 *
 * An UNREADABLE marker is PRESENT, never absent: `read` answers
 * [Read.Unreadable] and every caller refuses. One marker per queue: [install]
 * refuses a different bracket while one is on disk, so a second concurrent
 * bracket is not representable.
 *
 * ## Not key-backed, deliberately
 *
 * Digests, fingerprints, a count and a cause name — no record content, no text,
 * no credential — so it is readable without the queue key, exactly like the
 * protection file beside it, and it is absent from `QueueKeyStores.ALL`: a
 * teardown that destroys the queue key must still be able to see that a
 * bracket was open (Slice D decides what it then does; this slice only makes
 * the evidence survive).
 *
 * Lock order: a caller holds the queue region (and fence) BEFORE entering this
 * monitor; this class never calls into the store, the region lock or the fence.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class BracketMarker internal constructor(
    private val file: File,
    private val dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
) {

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class Marker(
        /** sha256 over every other field, hex — the bracket's identity. */
        val bracketId: String,
        /** The queue file NAME this marker governs. */
        val queueFile: String,
        /** `EncryptedEventStore.logicalFingerprint` of the image the plan was made against. */
        val sourceFingerprint: String,
        /** The fingerprint the commit must produce — the same value `compactAndAppend` verifies. */
        val targetFingerprint: String,
        /** sha256 of the incoming record's bytes. */
        val incomingDigest: String,
        /** sha256 of each victim's bytes, in queue order. */
        val victimDigests: List<String>,
        /** The intended loss cause: `BYTE_EVICTED` (F4-P0, Slice C) or `QUARANTINED` (F4-P1). Bound into the id. */
        val cause: String,
        val createdAtMs: Long,
        /**
 * F4-P0 Slice D — a per-ATTEMPT nonce, part of the bracket identity.
 *
 * WITHOUT IT, TWO BRACKETS OVER THE SAME PLAN WERE ONE BRACKET, AND
 * THE SECOND DESTROYED WITHOUT ATTRIBUTION. Found by Slice D's D2 row:
 * a process died mid-PREPARE, recovery settled the intent
 * DID_NOT_HAPPEN, and the app retried the identical append at the
 * identical capacity — identical source image, target image, incoming
 * digest and victims, so the identical bracket id and the identical
 * intent ids. The authority's idempotency ledger (`appliedIntentIds`)
 * then answered the NEW PREPARE `AlreadyApplied` — nothing written, the
 * intent long since finalized — the commit ran, and the LOST settlement
 * was refused `unknown_admission`: records destroyed with no durable
 * intent and no loss record. A re-run of a plan is a NEW destructive
 * act and gets a new identity; idempotency holds WITHIN one bracket
 * (a repeated PREPARE of the same bracket is still one record, C6).
 * Defaults to empty only so Slice C's hand-built fixtures still parse.
 */
        val nonce: String = "",
    ) {
        val victimCount: Int get() = victimDigests.size
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Read {
        /** `marker == null` means NO bracket is open. */
        data class Ok(val marker: Marker?) : Read()

        /** The file exists and could not be read or parsed. A PRESENT marker, never an absent one. */
        data class Unreadable(val reason: String) : Read()
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Write {
        object Committed : Write()

        /** Nothing changed — a typed reason. */
        data class Refused(val reason: String) : Write()

        /** The write (or removal) could not be verified durable. The caller must not proceed on it. */
        data class NotDurable(val reason: String) : Write()
    }

    /** Where this marker lives. Diagnostics only. */
    fun path(): String = file.path

    @Synchronized
    fun read(): Read = readFile()

    /** True when a marker is on disk OR the file cannot be read (fail closed). */
    @Synchronized
    fun presentOrUnreadable(): Boolean = when (val r = readFile()) {
        is Read.Ok -> r.marker != null
        is Read.Unreadable -> true
    }

    /**
 * Install [m] durably. Idempotent for the SAME bracket id; refuses while a
 * DIFFERENT bracket's marker is on disk (M-7) or the file is unreadable.
 */
    @Synchronized
    fun install(m: Marker): Write {
        when (val r = readFile()) {
            is Read.Unreadable -> return Write.Refused("marker_unreadable:" + r.reason)
            is Read.Ok -> {
                val cur = r.marker
                if (cur != null) {
                    if (cur.bracketId == m.bracketId) return Write.Committed
                    return Write.Refused("marker_present")
                }
            }
        }
        if (m.bracketId != bracketIdOf(m)) return Write.Refused("bracket_id_mismatch")
        return writeFile(m)
    }

    /**
 * Remove the marker for [bracketId]: delete, parent-directory fsync, verify
 * absent. Refuses to remove ANOTHER bracket's marker; absent is Committed.
 */
    @Synchronized
    fun remove(bracketId: String): Write {
        when (val r = readFile()) {
            is Read.Unreadable -> return Write.Refused("marker_unreadable:" + r.reason)
            is Read.Ok -> {
                val cur = r.marker ?: return Write.Committed
                if (cur.bracketId != bracketId) return Write.Refused("marker_of_other_bracket")
            }
        }
        try { file.delete() } catch (_: Throwable) { /* verified below */ }
        if (file.exists()) return Write.NotDurable("delete_failed")
        val parent = file.parentFile
        if (parent == null || !dirSync.sync(parent)) {
            RecoveryTrace.log("bracket_marker_removal_unsynced")
            return Write.NotDurable("directory_unsynced")
        }
        return Write.Committed
    }

    private fun readFile(): Read {
        if (!file.exists()) return Read.Ok(null)
        return try {
            val m = decode(file.readText(Charsets.UTF_8)) ?: return Read.Unreadable("undecodable")
            if (m.bracketId != bracketIdOf(m)) return Read.Unreadable("bracket_id_mismatch")
            Read.Ok(m)
        } catch (t: Throwable) {
            Read.Unreadable(t.javaClass.simpleName)
        }
    }

    private fun writeFile(m: Marker): Write {
        val bytes = encode(m).toByteArray(Charsets.UTF_8)
        val outcome = try {
            DurableReplace.replace(file, dirSync) { raf -> raf.write(bytes) }
        } catch (t: Throwable) {
            return Write.NotDurable(t.javaClass.simpleName)
        }
        return when (outcome) {
            DurableReplace.Outcome.COMMITTED -> {
                val back = readFile()
                if (back is Read.Ok && back.marker == m) Write.Committed
                else Write.NotDurable("readback_mismatch")
            }
            DurableReplace.Outcome.INDETERMINATE_DIRECTORY_UNSYNCED -> Write.NotDurable("directory_unsynced")
            DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED -> Write.NotDurable("not_committed")
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        private const val VERSION = 1

        /** The one cause Slice C writes. */
        const val CAUSE_BYTE_EVICTED: String = "BYTE_EVICTED"

        /** F4-P1: the cause a quarantine bracket writes. The CLOSED cause vocabulary is exactly these two constants. */
        const val CAUSE_QUARANTINED: String = "QUARANTINED"

        /** The bracket identity: a digest over every bound fact, in a fixed order. */
        @JvmStatic
        @JvmOverloads
        fun bracketIdOf(
            queueFile: String,
            sourceFingerprint: String,
            targetFingerprint: String,
            incomingDigest: String,
            victimDigests: List<String>,
            cause: String,
            nonce: String = "",
        ): String = DeliveryProtection.sha256Hex(
            ("evb1|" + queueFile + "|" + nonce + "|" + sourceFingerprint + "|" + targetFingerprint + "|" + incomingDigest +
                "|" + victimDigests.size + "|" + victimDigests.joinToString("|") + "|" + cause).toByteArray(Charsets.UTF_8),
        )

        @JvmStatic
        fun bracketIdOf(m: Marker): String =
            bracketIdOf(m.queueFile, m.sourceFingerprint, m.targetFingerprint, m.incomingDigest, m.victimDigests, m.cause, m.nonce)

        @JvmStatic
        fun encode(m: Marker): String = org.json.JSONObject()
            .put("v", VERSION)
            .put("bracketId", m.bracketId)
            .put("queueFile", m.queueFile)
            .put("sourceFingerprint", m.sourceFingerprint)
            .put("targetFingerprint", m.targetFingerprint)
            .put("incomingDigest", m.incomingDigest)
            .put("victimDigests", org.json.JSONArray(m.victimDigests))
            .put("victimCount", m.victimDigests.size)
            .put("cause", m.cause)
            .put("createdAtMs", m.createdAtMs)
            .put("nonce", m.nonce)
            .toString()

        @JvmStatic
        fun decode(text: String): Marker? = try {
            val o = org.json.JSONObject(text)
            if (o.optInt("v", -1) != VERSION) null else {
                val vd = o.getJSONArray("victimDigests").let { a -> (0 until a.length()).map { a.getString(it) } }
                if (o.getInt("victimCount") != vd.size) null else Marker(
                    o.getString("bracketId"), o.getString("queueFile"), o.getString("sourceFingerprint"),
                    o.getString("targetFingerprint"), o.getString("incomingDigest"), vd,
                    o.getString("cause"), o.getLong("createdAtMs"), o.optString("nonce", ""),
                )
            }
        } catch (_: Throwable) { null }
    }
}

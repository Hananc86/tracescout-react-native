package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File

/**
 * The UNRESOLVED-BATCH descriptor: what was submitted, and has not yet been
 * settled by an identity-bound acknowledgement.
 *
 * WHY THIS EXISTS — a real physical-device defect, RN iOS 0.2.0 (7),
 * 2026-08-25, session `ts_1787646516664_11f44b8acc07`:
 *
 * chunk 613380715e985f7029c0629256464ada sequences 19–26 (8 records)
 * chunk 2b382959f874b59885cae83d808e0cbc sequences 19–29 (11 records)
 *
 * The first submission was not settled. Three more records were appended. The
 * next drain read the queue fresh, took the longest contiguous session-owned
 * prefix — now ELEVEN records — and submitted it as a NEW batch. Different
 * bytes mean a different content hash, so the server's chunk-identity dedupe
 * could not collapse it and both committed. Sequences 19–26 are stored twice.
 *
 * Downstream `entry_id` dedupe meant no hierarchy entry was materialised twice,
 * so visible correctness survived — but **record storage was not exactly-once**,
 * and a client must not depend on a server-side materialisation detail to be
 * correct about what it sent. That is the defect this closes.
 *
 * The invariant it enforces:
 *
 * Once a batch has been submitted and lacks a settled valid acknowledgement,
 * every retry uses the exact same session identity, chunk identity, record
 * boundary and encoded bytes. Records appended later cannot extend or alter
 * that unresolved batch.
 *
 * The descriptor carries the per-record digests, which ARE the exact durable
 * prefix boundary: on any later drain — including after process death — the
 * first `recordCount` records of the queue are re-checked against them and the
 * body is rebuilt, so the retry is byte-identical by construction rather than
 * by hope. `contentSha256` is the digest of that rebuilt request body and is
 * asserted before the retry goes out; a mismatch means the queue is not what
 * the descriptor describes, and the pipeline refuses to send it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class InFlightBatch(
    val sessionId: String,
    val chunkId: String,
    val firstSequence: String,
    val lastSequence: String,
    val recordCount: Int,
    /** sha256 of the exact encoded request body. */
    val contentSha256: String,
    /** Per-record sha256, oldest first — the exact durable prefix boundary. */
    val digests: List<String>,
    val firstAttemptAtMs: Long,
    val attempts: Int,
    /**
 * The DURABLE terminal disposition, or null while the batch is still
 * ordinarily deliverable.
 *
 * WITHOUT THIS THE CROSS-PROCESS LOOP DOES NOT CLOSE. Records survive
 * restart and this descriptor survives restart, but the REASON a slice was
 * refused did not — so a process that died between the refusal and the
 * completed park would wake up, know nothing, and POST the same impossible
 * chunk again. Persisting the classification here is what makes "never ask
 * the server twice" true across process death rather than only within one
 * process.
 *
 * Set ONLY to [SessionOriginPolicy.SESSION_ORIGIN_UNRECOVERABLE] today.
 * It is bounded metadata beside metadata that is already here — no payload
 * copy, no URL, no header, no text, no node data.
 */
    val terminalReason: String? = null,
) {
    fun encode(): String = org.json.JSONObject()
        .put("sessionId", sessionId)
        .put("chunkId", chunkId)
        .put("firstSequence", firstSequence)
        .put("lastSequence", lastSequence)
        .put("recordCount", recordCount)
        .put("contentSha256", contentSha256)
        .put("digests", org.json.JSONArray(digests))
        .put("firstAttemptAtMs", firstAttemptAtMs)
        .put("attempts", attempts)
        // Additive and OMITTED when absent, so a descriptor written by an
        // older build decodes unchanged and one written by this build is
        // readable by an older one (it ignores the key).
        .also { if (terminalReason != null) it.put("terminalReason", terminalReason) }
        .toString()

    companion object {
        @JvmStatic
        fun decode(s: String): InFlightBatch? = try {
            val o = org.json.JSONObject(s)
            val chunkId = o.optString("chunkId")
            val arr = o.optJSONArray("digests")
            val digests = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) digests.add(arr.optString(i))
            // A descriptor that cannot name its batch, or whose boundary does
            // not agree with its own count, is not usable. Refuse it rather
            // than retry something we cannot reproduce byte-identically.
            if (chunkId.isEmpty() || digests.isEmpty() ||
                digests.size != o.optInt("recordCount", -1)
            ) null
            else InFlightBatch(
                sessionId = o.optString("sessionId"),
                chunkId = chunkId,
                firstSequence = o.optString("firstSequence"),
                lastSequence = o.optString("lastSequence"),
                recordCount = o.optInt("recordCount"),
                contentSha256 = o.optString("contentSha256"),
                digests = digests,
                firstAttemptAtMs = o.optLong("firstAttemptAtMs"),
                attempts = o.optInt("attempts"),
                // Absent -> null -> ordinarily deliverable, which is exactly
                // how every pre-existing descriptor must read.
                terminalReason = o.optString("terminalReason").ifEmpty { null },
            )
        } catch (_: Throwable) { null }
    }
}

/**
 * Durable storage for the single unresolved batch.
 *
 * There is at most ONE — the queue is strict FIFO and the pipeline submits one
 * prefix at a time, so a second concurrent unresolved batch is not
 * representable. `save` must be durable BEFORE the first network attempt: a
 * descriptor written after the request cannot survive a crash during it, which
 * is precisely the window the defect lives in.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface InFlightStore {
    fun load(): InFlightBatch?

    /** Returns false when the descriptor is not durably readable back. */
    fun save(batch: InFlightBatch): Boolean

    fun clear()

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /**
 * Process-lifetime only.
 *
 * The DEFAULT, deliberately: a caller that has not wired durable
 * storage still gets the invariant within a process — which is where
 * the observed defect occurred — and never gets behaviour worse than
 * before. It cannot satisfy the crash/relaunch clause, so the real
 * transports wire [file].
 */
        @JvmStatic
        fun memory(): InFlightStore = object : InFlightStore {
            @Volatile private var held: InFlightBatch? = null
            override fun load(): InFlightBatch? = held
            override fun save(batch: InFlightBatch): Boolean { held = batch; return true }
            override fun clear() { held = null }
        }

        /**
 * File-backed, fsync-on-write, verified by reading back.
 *
 * Deliberately NOT encrypted: the descriptor carries digests, counts,
 * sequence bounds and ids — no record content, no text, no
 * credential. Adding a second crypto container beside the proven queue
 * would add a failure mode without protecting anything.
 */
        @JvmStatic
        fun file(f: File): InFlightStore = object : InFlightStore {
            override fun load(): InFlightBatch? = try {
                if (!f.exists()) null else InFlightBatch.decode(f.readText(Charsets.UTF_8))
            } catch (_: Throwable) { null }

            override fun save(batch: InFlightBatch): Boolean {
                return try {
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(batch.encode(), Charsets.UTF_8)
                    java.io.FileOutputStream(tmp, true).use { it.fd.sync() }
                    // A rename within one directory is atomic, so a crash can
                    // never leave a half-written descriptor in place.
                    if (!tmp.renameTo(f)) {
                        tmp.delete()
                    } else {
                        // Verify by reading back: the caller submits a network
                        // request on the strength of this answer.
                        val back = load()
                        // `terminalReason` IS PART OF THE VERIFICATION. The
                        // caller removes active bytes, or declines to POST,
                        // on the strength of this answer — a save that
                        // reported success while silently dropping the
                        // terminal reason would resurrect the exact
                        // cross-process loop this field exists to close, and
                        // would do it invisibly. Anything this descriptor is
                        // trusted for must be read back.
                        return back != null && back.chunkId == batch.chunkId &&
                            back.recordCount == batch.recordCount &&
                            back.contentSha256 == batch.contentSha256 &&
                            back.terminalReason == batch.terminalReason
                    }
                    false
                } catch (t: Throwable) {
                    RecoveryTrace.log("inflight_save_failed", "code" to t.javaClass.simpleName)
                    false
                }
            }

            override fun clear() { try { f.delete() } catch (_: Throwable) { } }
        }
    }
}

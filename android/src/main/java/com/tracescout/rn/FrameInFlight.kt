package com.tracescout.rn

import java.io.File

/**
 * The UNRESOLVED-FRAME descriptor — the frame analogue of the chunk pipeline's
 * `InFlightBatch`, and it exists for the same reason.
 *
 * WHY. The Phase-2 frame drain removed the bytes from the head of the queue and
 * only then re-appended a copy with an incremented attempt count. Between those
 * two calls the frame existed only in process memory: a crash, an OOM kill or a
 * force-stop in that window lost it permanently. On a CONTENT-ADDRESSED endpoint
 * that is the wrong trade in the wrong direction — `/replay/frames` binds a
 * frame by `contentHash` and de-duplicates identical bytes, so **duplication
 * costs nothing and loss costs a keyframe nothing can recreate.**
 *
 * The invariant, stated the same way as for chunks:
 *
 * Once a frame has been submitted and lacks a settled valid
 * acknowledgement, every retry uses the exact same frame identity and the
 * exact same encoded bytes, and those bytes are not removed from the durable
 * store until that acknowledgement arrives.
 *
 * WHAT THE DESCRIPTOR ADDS OVER THE HEADER'S OWN `attempts`. The header's count
 * is only rewritten when a rotation COMPLETES. If the process dies during every
 * request, the count never advances and the frame is retried forever — an
 * unbounded-retry hazard the header cannot see, because from its point of view
 * no attempt ever finished. The descriptor is written BEFORE the request and
 * survives the death, so `attempts` is the max of what the header and the
 * descriptor remember.
 *
 * Deliberately NOT encrypted: it carries an id, a digest, a byte length and a
 * counter — no image bytes, no screen text, no credential. A second crypto
 * container beside the proven one would add a failure mode without protecting
 * anything.
 */
data class FrameInFlight(
    val sessionId: String,
    val visualFrameId: String,
    val contentHash: String,
    val byteSize: Int,
    val attempts: Int,
    val firstAttemptAtMs: Long,
) {
    /** The identity a retry must reproduce exactly. */
    fun identity(): String = "$sessionId|$visualFrameId|$contentHash|$byteSize"

    fun encode(): String =
        "{\"sessionId\":\"$sessionId\",\"visualFrameId\":\"$visualFrameId\"," +
        "\"contentHash\":\"$contentHash\",\"byteSize\":$byteSize," +
        "\"attempts\":$attempts,\"firstAttemptAtMs\":$firstAttemptAtMs}"

    companion object {
        private val FIELD = Regex("\"([A-Za-z]+)\":(\"([^\"]*)\"|(-?[0-9]+))")

        fun decode(s: String): FrameInFlight? {
            val m = HashMap<String, String>()
            for (f in FIELD.findAll(s)) m[f.groupValues[1]] = f.groupValues[3].ifEmpty { f.groupValues[4] }
            return try {
                val bytes = m["byteSize"]?.toInt() ?: return null
                // A descriptor that does not describe a frame is not usable —
                // refuse it rather than retry something we cannot reproduce.
                if (bytes <= 0) return null
                FrameInFlight(
                    sessionId = m["sessionId"] ?: return null,
                    visualFrameId = m["visualFrameId"]?.ifEmpty { null } ?: return null,
                    contentHash = m["contentHash"]?.ifEmpty { null } ?: return null,
                    byteSize = bytes,
                    attempts = m["attempts"]?.toInt() ?: 0,
                    firstAttemptAtMs = m["firstAttemptAtMs"]?.toLong() ?: 0L,
                )
            } catch (_: NumberFormatException) { null }
        }
    }
}

/**
 * Durable storage for the single unresolved frame. There is at most one: the
 * queue is strict FIFO and the drain submits one frame at a time.
 *
 * `save` must be durable BEFORE the request — a descriptor written after it
 * cannot survive a crash during it, which is precisely the window the defect
 * lives in.
 */
interface FrameInFlightStore {
    fun load(): FrameInFlight?
    /** false when the descriptor is not durably readable back. */
    fun save(d: FrameInFlight): Boolean
    fun clear()

    companion object {
        /** Process-lifetime only. The default for a caller that has not wired
 * durable storage: strictly better than nothing, never worse; it
 * cannot satisfy the crash/relaunch clause. */
        @JvmStatic
        fun memory(): FrameInFlightStore = object : FrameInFlightStore {
            private var held: FrameInFlight? = null
            @Synchronized override fun load(): FrameInFlight? = held
            @Synchronized override fun save(d: FrameInFlight): Boolean { held = d; return true }
            @Synchronized override fun clear() { held = null }
        }

        /** File-backed, written via a temp file + atomic rename, then VERIFIED
 * by reading it back — the caller submits a network request on the
 * strength of that answer. */
        @JvmStatic
        fun file(f: File): FrameInFlightStore = object : FrameInFlightStore {
            @Synchronized override fun load(): FrameInFlight? = try {
                if (!f.exists()) null else FrameInFlight.decode(f.readText())
            } catch (_: Throwable) { null }

            @Synchronized override fun save(d: FrameInFlight): Boolean = try {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(d.encode())
                if (!tmp.renameTo(f)) { tmp.delete(); false }
                else FrameInFlight.decode(f.readText())?.let {
                    it.identity() == d.identity() && it.attempts == d.attempts
                } ?: false
            } catch (_: Throwable) { false }

            @Synchronized override fun clear() { try { f.delete() } catch (_: Throwable) {} }
        }
    }
}

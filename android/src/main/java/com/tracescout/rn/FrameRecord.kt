package com.tracescout.rn

/**
 * The durable FRAME record: a small JSON header followed by the encoded,
 * ALREADY-REDACTED image bytes, stored in the same encrypted container as the
 * event queue (Swift twin: FrameRecord.swift — byte-identical framing).
 *
 * [4 bytes big-endian header length][header JSON][image bytes]
 *
 * The header carries only what the frame endpoint needs as query parameters
 * plus the retry bookkeeping. No screen text, no node content.
 */
data class FrameHeader(
    val visualFrameId: String,
    val sessionId: String,
    val screenId: String,
    val contentHash: String,
    val width: Int,
    val height: Int,
    val byteSize: Int,
    val format: String,
    val attempts: Int,
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"visualFrameId\":\"").append(visualFrameId).append("\",")
        append("\"sessionId\":\"").append(sessionId).append("\",")
        append("\"screenId\":\"").append(screenId).append("\",")
        append("\"contentHash\":\"").append(contentHash).append("\",")
        append("\"width\":").append(width).append(',')
        append("\"height\":").append(height).append(',')
        append("\"byteSize\":").append(byteSize).append(',')
        append("\"format\":\"").append(format).append("\",")
        append("\"attempts\":").append(attempts)
        append('}')
    }

    companion object {
        private val FIELD = Regex("\"([A-Za-z]+)\":(\"([^\"]*)\"|(-?[0-9]+))")

        /** Minimal, closed parser: only the fields above, only safe shapes. */
        fun parse(json: String): FrameHeader? {
            val m = HashMap<String, String>()
            for (f in FIELD.findAll(json)) m[f.groupValues[1]] = f.groupValues[3].ifEmpty { f.groupValues[4] }
            return try {
                FrameHeader(
                    visualFrameId = m["visualFrameId"] ?: return null,
                    sessionId = m["sessionId"] ?: return null,
                    screenId = m["screenId"] ?: return null,
                    contentHash = m["contentHash"] ?: return null,
                    width = m["width"]?.toInt() ?: return null,
                    height = m["height"]?.toInt() ?: return null,
                    byteSize = m["byteSize"]?.toInt() ?: return null,
                    format = m["format"] ?: return null,
                    attempts = m["attempts"]?.toInt() ?: 0,
                )
            } catch (_: NumberFormatException) { null }
        }
    }
}

object FrameRecord {
    const val MAX_HEADER = 4096

    fun encode(header: FrameHeader, bytes: ByteArray): ByteArray {
        val h = header.toJson().toByteArray(Charsets.UTF_8)
        require(h.size <= MAX_HEADER) { "frame header too large" }
        val out = ByteArray(4 + h.size + bytes.size)
        out[0] = (h.size ushr 24).toByte(); out[1] = (h.size ushr 16).toByte()
        out[2] = (h.size ushr 8).toByte(); out[3] = h.size.toByte()
        System.arraycopy(h, 0, out, 4, h.size)
        System.arraycopy(bytes, 0, out, 4 + h.size, bytes.size)
        return out
    }

    /** null = unreadable (torn/foreign record) — never an empty frame. */
    fun decode(record: ByteArray): Pair<FrameHeader, ByteArray>? {
        if (record.size < 4) return null
        val n = ((record[0].toInt() and 0xff) shl 24) or ((record[1].toInt() and 0xff) shl 16) or
            ((record[2].toInt() and 0xff) shl 8) or (record[3].toInt() and 0xff)
        if (n <= 0 || n > MAX_HEADER || 4 + n > record.size) return null
        val header = FrameHeader.parse(String(record, 4, n, Charsets.UTF_8)) ?: return null
        val bytes = record.copyOfRange(4 + n, record.size)
        if (bytes.size != header.byteSize) return null
        return header to bytes
    }
}

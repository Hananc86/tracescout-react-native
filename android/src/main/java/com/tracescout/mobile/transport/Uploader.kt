package com.tracescout.mobile.transport

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The one place mobile replay bytes leave the device.
 *
 * `HttpURLConnection` deliberately — no OkHttp, no Retrofit. An SDK that drags
 * a networking library into a host app can collide with the app's own version
 * of it, and a version collision inside someone else's build is the single
 * worst failure an observability SDK can cause: it breaks the app it was
 * installed to observe. The platform client is always present and always
 * compatible.
 *
 * WHAT THIS CLASS WILL NOT DO:
 * - It never logs the ingest key, or a body, or a URL with a query string.
 * - It never retries a TERMINAL response (see [classifyResponse]): a 4xx is
 * the backend saying "this will never be valid", and repeating it turns a
 * client bug into a self-inflicted denial of service.
 * - It never invents success. A response it cannot classify is treated as
 * retriable, because dropping a chunk we might have been able to deliver is
 * a silent data loss and keeping it is merely a queue entry.
 */
class Uploader(
    private val baseUrl: String,
    private val ingestKey: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val opener: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {

    /**
 * The server's identity-bound acknowledgement (`mobile-ack-1`).
 *
 * Every field describes the batch the server COMMITTED, so the client can
 * check the acknowledgement against what it actually sent before removing
 * anything. The previous contract was a bare `accepted: true` boolean that
 * bound to nothing — and a bare HTTP 2xx was then treated as permission to
 * clear the whole durable queue.
 *
 * Sequences are STRINGS: the protocol types ordering keys as int64, and a
 * Double-backed JSON number loses integer precision above 2^53. That is the
 * one place a rounding error turns into deleted customer data.
 */
    private val CANONICAL_SEQ: java.util.regex.Pattern =
        java.util.regex.Pattern.compile("^(0|[1-9][0-9]{0,18})$")
    private val SHA256_HEX: java.util.regex.Pattern =
        java.util.regex.Pattern.compile("^[0-9a-f]{64}$")

    data class Ack(
        val version: String,
        val state: String,
        val sessionId: String,
        val chunkId: String,
        val firstSequence: String,
        val lastSequence: String,
        val recordCount: Int,
        val contentSha256: String,
    ) {
        companion object {
            const val VERSION = "mobile-ack-1"
            const val ACCEPTED = "accepted"
            const val DUPLICATE = "duplicate"
        }

        /** Both states mean the bytes are durably committed server-side. */
        val committed: Boolean get() =
            version == VERSION && (state == ACCEPTED || state == DUPLICATE)
    }

    data class Result(
        val action: ResponseAction,
        val status: Int,
        /** Typed backend code (never a body dump) — safe to log. */
        val code: String?,
        /**
 * Null when the response carried no parseable acknowledgement. A 2xx
 * with a null ack is NOT progress and must never dequeue anything.
 */
        val ack: Ack? = null,
        /**
 * The FRAME endpoint's identity-bound acknowledgement (`frame-ack-1`).
 *
 * Null when the body carried no parseable frame-ack-1. The endpoint's
 * previous answer was `{"accepted":true,"deduped":<bool>}` — a BOOLEAN
 * naming neither the frame nor its digest, so nothing bound it to the
 * request. A client that deletes durable bytes on that is deleting them
 * on the strength of an HTTP status, and a proxy page or a truncated
 * body is a 2xx too.
 */
        val frameAck: FrameAck? = null,
        /**
 * The server's `loss-checkpoint-ack-1`, parsed from the TOP-LEVEL SIBLING
 * `lossCheckpointAck` of `ack` (F3-P1b Part 1).
 *
 * Null when NO parseable loss block arrived — a real, currently reachable
 * state that must NEVER settle anything (the capture specification: *"`mobile-ack-1` alone
 * must NEVER clear pending loss"*). Two acks, two facts, two names.
 */
        val lossAck: LossAck? = null,
    )

    /**
 * `loss-checkpoint-ack-1` — the transport-owned DTO of the loss-checkpoint
 * acknowledgement, exactly as the deployed server emits it, STRICTLY typed.
 *
 * THIS IS SHAPE, NOT MATCH. The parser answers *"is this body a well-formed
 * loss-checkpoint ack?"* and carries the values verbatim; whether the ack SETTLES
 * anything — session, state, version, boundary, counts — is decided in ONE place,
 * the core binder, and nowhere else (F3-P1b the specification). A parser that decided `state`
 * would erase the difference between *"the server superseded your snapshot"*
 * (`older`, a real state) and *"the server said nothing parseable"*.
 *
 * `acceptedThroughSequence` IS A STRING OR NULL, NEVER A NUMBER. A Double-backed
 * JSON number loses integer precision above 2^53, and this is the value that decides
 * which interval is settled — so a JSON number is REFUSED outright, never coerced.
 * `null` on the wire is a well-formed *"no accepted boundary"* (the deployed server
 * emits it), distinct from malformed.
 */
    data class LossAck(
        val protocol: String,
        val lossAccountingVersion: Int,
        /** Read VERBATIM. `accepted` / `duplicate` / `older` are all well-formed here. */
        val state: String,
        val sessionId: String,
        val acceptedThroughSequence: String?,
        val acceptedCounts: Map<String, Long>,
        val terminal: Boolean,
    )

    /**
 * `frame-ack-1`. Every field is derived by the SERVER from the committed
 * row; the client's job is to check it against the frame it actually sent.
 */
    data class FrameAck(
        val state: String,
        val sessionId: String,
        val visualFrameId: String,
        val contentHash: String,
        val byteSize: Long,
    ) {
        companion object {
            const val PROTOCOL = "frame-ack-1"
            val STATES = setOf("accepted", "duplicate")

            /** Parse with a real JSON parser — never a substring scan. The chunk
 * path's original digit-scanner read `"accepted":true` as 0. */
            @JvmStatic
            fun parse(text: String): FrameAck? = try {
                val o = org.json.JSONObject(text)
                if (o.optString("protocol") != PROTOCOL) null
                else {
                    val state = o.optString("state")
                    if (state !in STATES) null else FrameAck(
                        state = state,
                        sessionId = o.optString("sessionId"),
                        visualFrameId = o.optString("visualFrameId"),
                        contentHash = o.optString("contentHash"),
                        byteSize = o.optLong("byteSize", -1L),
                    )
                }
            } catch (_: Throwable) { null }
        }
    }

    /**
 * POST one chunk. Returns the CLASSIFICATION; the caller owns the retry
 * schedule, so a caller under a lifecycle constraint (say, a 5-second
 * background window) can decide to give up without this class blocking.
 */
    fun postChunk(projectId: String, body: ByteArray): Result {
        val url = URL("$baseUrl/api/mobile/projects/$projectId/replay/chunks")
        var conn: HttpURLConnection? = null
        try {
            conn = opener(url).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // The ingest key is a REQUEST HEADER and nothing else: never a
                // query parameter (proxies and access logs record those), never
                // written to the queue file, never included in an error string.
                setRequestProperty("X-Ingest-Key", ingestKey)
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
            if (conn is HttpsURLConnection) {
                // Platform trust store + platform pinning. No custom
                // TrustManager: an SDK that installs its own trust policy inside
                // a customer's app can only ever weaken theirs.
            }
            conn.outputStream.use { it.write(body) }

            val status = conn.responseCode
            val text = readBounded(
                if (status in 200..299) conn.inputStream else conn.errorStream
            )
            val retriable = text.contains("\"retriable\":true")
            return Result(
                action = classifyResponse(status, retriable),
                status = status,
                code = extractCode(text),
                ack = parseAck(text),
                lossAck = parseLossAck(text),
            )
        } catch (t: Throwable) {
            // Transport failure is NOT terminal. Airplane mode, a captive
            // portal, a dropped socket — all of those are "try later", and a
            // client that discarded a chunk on the first tunnel would lose the
            // sessions recorded exactly where connectivity is worst.
            return Result(ResponseAction.RETRY, 0, "transport_error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
 * POST one visual frame's BYTES.
 *
 * Identity + privacy metadata travel as query parameters, not in the body:
 * the body IS the image, and the backend must be able to reject a frame on
 * its declared identity BEFORE reading megabytes of it. The content hash is
 * declared here and RE-COMPUTED server-side — a mismatch is a rejection,
 * so a corrupted upload can never be promoted.
 */
    fun postFrame(
        projectId: String,
        sessionId: String,
        visualFrameId: String,
        bytes: ByteArray,
        width: Int,
        height: Int,
        contentSha256: String,
        format: String,
        privacyState: String,
        captureReason: String,
        screenId: String?,
        scope: String = "screen",
    ): Result {
        val q = StringBuilder()
        fun add(k: String, v: String) {
            q.append(if (q.isEmpty()) "?" else "&")
                .append(k).append('=')
                .append(java.net.URLEncoder.encode(v, "UTF-8"))
        }
        add("sessionId", sessionId); add("visualFrameId", visualFrameId)
        add("contentHash", contentSha256)
        add("width", width.toString()); add("height", height.toString())
        add("byteSize", bytes.size.toString())
        add("format", format); add("privacyState", privacyState)
        add("captureReason", captureReason); add("scope", scope)
        if (screenId != null) add("screenId", screenId)

        val url = URL("$baseUrl/api/mobile/projects/$projectId/replay/frames$q")
        var conn: HttpURLConnection? = null
        try {
            conn = opener(url).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-Ingest-Key", ingestKey)
                setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val text = readBounded(
                if (status in 200..299) conn.inputStream else conn.errorStream
            )
            return Result(
                action = classifyResponse(status, text.contains("\"retriable\":true")),
                status = status, code = extractCode(text),
                frameAck = if (status in 200..299) FrameAck.parse(text) else null,
            )
        } catch (t: Throwable) {
            return Result(ResponseAction.RETRY, 0, "transport_error")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** Probe whether mobile replay is enabled for this project (dark → 404). */
    fun availability(projectId: String): Int {
        val url = URL("$baseUrl/api/mobile/projects/$projectId/replay/availability")
        var conn: HttpURLConnection? = null
        return try {
            conn = opener(url).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("X-Ingest-Key", ingestKey)
            }
            conn.responseCode
        } catch (t: Throwable) {
            0
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
 * Read at most 8 KiB of the response.
 *
 * Bounded because the response is only ever consulted for a typed code, and
 * an unbounded read of a body we do not need is a memory risk driven by a
 * remote party.
 */
    private fun readBounded(stream: java.io.InputStream?, max: Int = 8 * 1024): String {
        if (stream == null) return ""
        return try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(2048)
            var total = 0
            while (total < max) {
                val n = stream.read(buf)
                if (n <= 0) break
                out.write(buf, 0, minOf(n, max - total))
                total += n
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        } finally {
            try { stream.close() } catch (_: Throwable) {}
        }
    }

    /**
 * Pull the typed `code` out of a JSON error body WITHOUT parsing it.
 *
 * A tiny scan rather than a parser: this runs on an error path where the
 * body may be a proxy's HTML page rather than our JSON, and a parser that
 * throws there would convert a classifiable failure into an unclassified
 * one. It reads a bounded token and stops.
 */
    private fun extractCode(text: String): String? {
        val k = "\"code\":\""
        val i = text.indexOf(k)
        if (i < 0) return null
        val j = text.indexOf('"', i + k.length)
        if (j < 0 || j - (i + k.length) > 64) return null
        return text.substring(i + k.length, j)
    }

    /**
 * Parse the acknowledgement with a real JSON parser.
 *
 * The previous code scanned for DIGITS after `"accepted":` — against a
 * field the server sends as the BOOLEAN `true`. It therefore returned 0
 * every time, which read as "the server accepted nothing" when the server
 * had said the opposite. A boolean must never be interpreted by an integer
 * extractor; and a field's meaning must never be inferred from its name.
 *
 * Returns null on anything unexpected. A null ack is not an error here —
 * the CALLER decides, and its rule is simple: no matching ack, no dequeue.
 */
    // `internal` so the contract can be tested against REAL response bodies —
    // including the old `{accepted:true}` shape, which must be rejected.
    internal fun parseAck(text: String): Ack? {
        return try {
            val root = org.json.JSONObject(text)
            val a = root.optJSONObject("ack") ?: return null
            // Types are checked, not coerced. `optString` would happily turn a
            // number or a boolean into a string and hide a contract change.
            val version = a.opt("version") as? String ?: return null
            val state = a.opt("state") as? String ?: return null
            val sessionId = a.opt("sessionId") as? String ?: return null
            val chunkId = a.opt("chunkId") as? String ?: return null
            val first = a.opt("firstSequence") as? String ?: return null
            val last = a.opt("lastSequence") as? String ?: return null
            val sha = a.opt("contentSha256") as? String ?: return null
            val count = when (val c = a.opt("recordCount")) {
                is Int -> c
                is Long -> c.toInt()
                else -> return null
            }
            if (version != Ack.VERSION) return null
            if (state != Ack.ACCEPTED && state != Ack.DUPLICATE) return null
            if (!CANONICAL_SEQ.matcher(first).matches()) return null
            if (!CANONICAL_SEQ.matcher(last).matches()) return null
            if (!SHA256_HEX.matcher(sha).matches()) return null
            if (count < 0) return null
            Ack(version, state, sessionId, chunkId, first, last, count, sha)
        } catch (_: Throwable) {
            null
        }
    }

    /**
 * **F3-P1b Part 1 — parse the top-level sibling `lossCheckpointAck` (PA-1 … PA-7).**
 *
 * A real JSON parser, never a substring or digit scan (the chunk path's original
 * scanner read `"accepted":true` as `0`). Types are CHECKED, never coerced: `opt*`
 * would happily turn a number into a string and hide a contract change.
 *
 * ```
 * block absent, not an object, wrong protocol, wrong-typed field -> null
 * acceptedThroughSequence JSON null or absent -> DTO with null (well-formed)
 * acceptedThroughSequence a STRING that is not canonical decimal -> null
 * acceptedThroughSequence a JSON NUMBER -> null (NEVER coerced)
 * acceptedCounts a value that is not a non-negative integer -> null
 * ```
 *
 * IT DOES NOT TOUCH `parseAck` (two acks, two facts, two names), it holds NO
 * durable state and validates against NOTHING pending — that is the binder's, and
 * one home only. The ONE value it compares is `protocol`, against the core-owned
 * constant (the literal has one home): an unversioned body is not this contract.
 *
 * Returns null on anything unexpected — a partially-populated DTO is the most
 * dangerous possible value, because it looks like evidence. `internal` so the
 * contract can be tested against REAL response bodies.
 */
    internal fun parseLossAck(text: String): LossAck? {
        return try {
            val root = org.json.JSONObject(text)
            val a = root.optJSONObject("lossCheckpointAck") ?: return null
            val protocol = a.opt("protocol") as? String ?: return null
            if (protocol != com.tracescout.mobile.internal.loss.CheckpointAckBinder.LOSS_CHECKPOINT_ACK) return null
            val version = when (val v = a.opt("lossAccountingVersion")) {
                is Int -> v
                else -> return null
            }
            val state = a.opt("state") as? String ?: return null
            val sessionId = a.opt("sessionId") as? String ?: return null
            val through: String? = when (val t = a.opt("acceptedThroughSequence")) {
                null, org.json.JSONObject.NULL -> null
                is String -> if (CANONICAL_SEQ.matcher(t).matches()) t else return null
                else -> return null
            }
            val countsObj = a.opt("acceptedCounts") as? org.json.JSONObject ?: return null
            val counts = LinkedHashMap<String, Long>()
            for (k in countsObj.keys()) {
                val n: Long = when (val v = countsObj.opt(k)) {
                    is Int -> v.toLong()
                    is Long -> v
                    else -> return null
                }
                if (n < 0L) return null
                counts[k] = n
            }
            val terminal = a.opt("terminal") as? Boolean ?: return null
            LossAck(protocol, version, state, sessionId, through, counts, terminal)
        } catch (_: Throwable) {
            null
        }
    }
}

package com.tracescout.rn

/**
 * M3.3 — the PURE core of the Android network producer.
 *
 * WHY THIS IS OKHTTP-FREE. Every rule that decides what leaves a device —
 * what a start context freezes, how a failure is classified, what the event may
 * contain, when the budget refuses — is platform-independent, so it lives here
 * and is unit-testable on the JVM without an emulator. The OkHttp `EventListener`
 * in the Android module is a thin adapter that observes and calls in; it owns no
 * policy. A rule that lived in the adapter could only be tested on a device,
 * which is how untested rules happen.
 *
 * THE EVENT'S FIELD SET IS THE PRIVACY BOUNDARY. `NetworkEvent.wire` emits
 * exactly the v1 contract keys and there is no field, parameter or constructor
 * argument on this path capable of carrying a body, a header, an Authorization
 * or Cookie value, an API key, a raw URL, a query string or userinfo — absent,
 * not sanitized-then-stored. The adapter never receives them either: it is
 * handed byte COUNTS and an exception CLASS NAME, never bytes and never a
 * message.
 */
object NetworkCaptureCore {

    /** The one place a failure becomes a bounded vocabulary word. */
    enum class ErrorClass(val wire: String) {
        DNS("dns"), TLS("tls"), TIMEOUT("timeout"),
        CONNECTION("connection"), CANCELLED("cancelled"), OTHER("other")
    }

    enum class Outcome(val wire: String) {
        SUCCESS("success"), FAILURE("failure"), CANCELLED("cancelled"), UNKNOWN("unknown")
    }

    /**
 * Frozen at logical request START and never recomputed.
 *
 * A request begun on screen A and completed after the user navigated to
 * screen B belongs to A. Resolving the screen at completion would file every
 * slow request against whatever the user happened to be looking at when it
 * landed — the attribution would be systematically wrong exactly for the
 * requests worth investigating.
 */
    data class StartContext(
        val networkRequestId: String,
        val startedWallTime: Long,
        val startedMonotonicNanos: Long,
        val method: String,
        val scheme: String?,
        val host: String,
        val routeTemplate: String?,
        val screenId: String?,
        val gestureId: String?,
        val traceId: String?,
        val spanId: String?,
    ) {
        val sourceLayer: String get() = "okhttp"
    }

    /** Why no start context was created. Each is a REFUSAL, never a silent drop. */
    enum class Refusal(val reason: String) {
        CAPTURE_DISABLED("capture_disabled"),
        INTERNAL_REQUEST("internal_request"),
        URL_NOT_REPRESENTABLE("url_not_representable"),
        BUDGET_EXHAUSTED("budget_exhausted"),
        NO_SESSION("no_session"),
    }

    data class StartResult(val context: StartContext?, val refusal: Refusal?) {
        val captured: Boolean get() = context != null
    }

    /**
 * The closed method vocabulary. An unlisted method yields NO event rather
 * than a free-form string on the busiest event type a session produces.
 */
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    /**
 * @param internalRequest TraceScout's own transport, marked by an IN-PROCESS
 * OkHttp tag. Never a header: a marker on the wire would be a header we
 * add for our own convenience, visible to the customer's server and to
 * anyone on the path. Excluded BEFORE any telemetry object exists, so
 * recursion is structurally impossible rather than filtered later.
 */
    fun startContext(
        enabled: Boolean,
        internalRequest: Boolean,
        hasSession: Boolean,
        rawUrl: String?,
        method: String?,
        networkRequestId: String,
        startedWallTime: Long,
        startedMonotonicNanos: Long,
        screenId: String?,
        gestureId: String?,
        traceId: String?,
        spanId: String?,
        budget: NetworkBudget,
    ): StartResult {
        if (!enabled) return StartResult(null, Refusal.CAPTURE_DISABLED)
        // Self-exclusion is checked FIRST and before anything reads the URL, so
        // our own transport never even reaches the normalizer.
        if (internalRequest) return StartResult(null, Refusal.INTERNAL_REQUEST)
        if (!hasSession) return StartResult(null, Refusal.NO_SESSION)

        val m = (method ?: "").uppercase()
        if (m !in METHODS) return StartResult(null, Refusal.URL_NOT_REPRESENTABLE)

        // THE ONE normalizer, shared with Swift and the JS reference. The raw
        // URL exists only as this argument and is never retained.
        val n = UrlNormalizer.normalize(rawUrl)
        if (!n.representable) return StartResult(null, Refusal.URL_NOT_REPRESENTABLE)

        if (!budget.admit()) return StartResult(null, Refusal.BUDGET_EXHAUSTED)

        return StartResult(
            StartContext(
                networkRequestId = networkRequestId,
                startedWallTime = startedWallTime,
                startedMonotonicNanos = startedMonotonicNanos,
                method = m,
                scheme = n.scheme,
                host = n.host!!,
                routeTemplate = n.routeTemplate,
                screenId = screenId,
                gestureId = gestureId,
                traceId = traceId,
                spanId = spanId,
            ),
            null,
        )
    }

    /**
 * Classify a transport failure from the exception's TYPE.
 *
 * NEVER FROM `e.message`. A provider message is free text from outside
 * our control and is exactly where a URL, a host or a credential leaks into
 * telemetry — `connect ECONNREFUSED 10.0.0.1:443`, `Hostname api.x.com not
 * verified`. The adapter passes the class NAME, so a message cannot reach
 * this function even by accident.
 */
    fun classifyFailure(exceptionClassName: String?, canceled: Boolean): ErrorClass {
        val n = (exceptionClassName ?: "").substringAfterLast('.')
        // THE EXCEPTION TYPE WINS OVER THE CANCELLED FLAG, AND A REAL OKHTTP
        // CLIENT IS WHY. OkHttp implements `callTimeout` BY CANCELLING the call,
        // so `call.isCanceled` is true for a timeout — checking the flag first
        // reported every timed-out request as "the user cancelled", which is
        // materially the wrong answer for the one class of request an engineer
        // opens a replay to investigate. The core's own unit tests could not see
        // this: they are handed `canceled` as a boolean, so only driving a real
        // client exposed it.
        if (n == "SocketTimeoutException" || n == "InterruptedIOException") return ErrorClass.TIMEOUT
        if (canceled) return ErrorClass.CANCELLED
        return when {
            n == "UnknownHostException" -> ErrorClass.DNS
            n.startsWith("SSL") || n.contains("CertPath") || n == "CertificateException" -> ErrorClass.TLS
            n == "SocketTimeoutException" || n == "InterruptedIOException" -> ErrorClass.TIMEOUT
            n == "ConnectException" || n == "NoRouteToHostException"
                || n == "PortUnreachableException" || n == "SocketException" -> ErrorClass.CONNECTION
            else -> ErrorClass.OTHER
        }
    }

    /**
 * ONE completed event per logical call.
 *
 * `status` is null when no HTTP response was received. **Status 0 is
 * unrepresentable** — the classic "0 means the network failed" encoding
 * conflates two different facts, and the wire contract, the planner and the
 * SQL CHECK all refuse it.
 */
    data class NetworkEvent(
        val start: StartContext,
        val durationMs: Long,
        val status: Int?,
        val outcome: Outcome,
        val errorClass: ErrorClass?,
        val requestBytes: Long?,
        val responseBytes: Long?,
    ) {
        /** The wire map. EXACTLY the v1 keys — the field set IS the boundary. */
        fun wire(): Map<String, Any?> = linkedMapOf(
            "eventType" to "mobile_network_request",
            "networkRequestId" to start.networkRequestId,
            "requestStartedWallTime" to start.startedWallTime,
            "requestStartedMonotonicNanos" to start.startedMonotonicNanos.toString(),
            "durationMs" to durationMs.toInt(),
            "method" to start.method,
            "scheme" to start.scheme,
            "host" to start.host,
            "routeTemplate" to start.routeTemplate,
            "status" to status,
            "outcome" to outcome.wire,
            "errorClass" to errorClass?.wire,
            "requestBytes" to requestBytes,
            "responseBytes" to responseBytes,
            "sourceLayer" to start.sourceLayer,
        )
    }

    /** A response arrived. */
    fun completeWithResponse(
        start: StartContext, endMonotonicNanos: Long, status: Int,
        requestBytes: Long?, responseBytes: Long?,
    ): NetworkEvent? {
        if (status < 100 || status > 599) return null   // unrepresentable, so no event at all
        return NetworkEvent(
            start = start,
            durationMs = durationMs(start, endMonotonicNanos),
            status = status,
            outcome = if (status in 100..599) Outcome.SUCCESS else Outcome.UNKNOWN,
            errorClass = null,
            requestBytes = requestBytes,
            responseBytes = responseBytes,
        )
    }

    /** No response. */
    fun completeWithFailure(
        start: StartContext, endMonotonicNanos: Long,
        exceptionClassName: String?, canceled: Boolean,
        requestBytes: Long?, responseBytes: Long?,
    ): NetworkEvent {
        val ec = classifyFailure(exceptionClassName, canceled)
        return NetworkEvent(
            start = start,
            durationMs = durationMs(start, endMonotonicNanos),
            status = null,
            outcome = if (ec == ErrorClass.CANCELLED) Outcome.CANCELLED else Outcome.FAILURE,
            errorClass = ec,
            requestBytes = requestBytes,
            responseBytes = responseBytes,
        )
    }

    private fun durationMs(start: StartContext, endMonotonicNanos: Long): Long {
        val d = (endMonotonicNanos - start.startedMonotonicNanos) / 1_000_000
        // A monotonic clock cannot go backwards, but a defensive clamp costs
        // nothing and the schema refuses a negative duration outright.
        return if (d < 0) 0 else if (d > 3_600_000) 3_600_000 else d
    }
}

/**
 * The network-telemetry budget.
 *
 * EXHAUSTION DROPS TELEMETRY ONLY. It never blocks, delays or alters the
 * application's request, and it never touches hierarchy or frame capture — a
 * noisy app must cost the customer nothing but its own network events. Drops
 * are counted through the envelope's EXISTING `dropped.network` counter rather
 * than a new signal.
 *
 * THE LIMITS ARE NOT SET HERE. They are measured on the fixture first and
 * then frozen; a number chosen from intuition is a number nobody can defend.
 */
class NetworkBudget(
    val maxEventsPerSession: Int,
    val maxSerializedBytes: Int,
) {
    private var admitted = 0
    private var dropped = 0

    /** Called at request START, so a refused request never builds a context. */
    fun admit(): Boolean {
        if (admitted >= maxEventsPerSession) { dropped += 1; return false }
        admitted += 1
        return true
    }

    /**
 * A serialized event over the byte cap is dropped rather than truncated:
 * truncating a structured event produces a record that is syntactically
 * valid and semantically a lie.
 */
    fun admitSerialized(byteCount: Int): Boolean {
        if (byteCount > maxSerializedBytes) {
            dropped += 1
            // The slot the start context reserved is returned, so one oversized
            // event does not silently consume the session's remaining budget.
            if (admitted > 0) admitted -= 1
            return false
        }
        return true
    }

    fun droppedCount(): Int = dropped
    fun admittedCount(): Int = admitted
    fun reset() { admitted = 0; dropped = 0 }
}

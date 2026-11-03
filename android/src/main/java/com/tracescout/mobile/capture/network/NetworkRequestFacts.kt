package com.tracescout.mobile.capture.network

/**
 * S6/S5 — the NATIVE `mobile_network_request` payload, as a closed type.
 *
 * ## Why this type exists at all
 *
 * The OkHttp producer already exists and is already tested: `NetworkCaptureCore`
 * (OkHttp-free policy — the opt-in, self-exclusion, URL normalization, failure
 * classification, the budget, the field set) plus `RnNetworkCapture` (the
 * `EventListener` adapter). What did NOT exist was a way for a NATIVE Android
 * host to put the result into THIS SDK's durable queue: `:sdk` has an envelope
 * builder for ten event types and none of them is a network request, so a
 * native app's own HTTP traffic could not reach the store at all.
 *
 * `:sdk` MUST NEVER DEPEND ON rn-core (a conformance test — the settings file
 * says so, and a guard enforces it). So this cannot simply re-use
 * `NetworkCaptureCore.NetworkEvent`: it is a TWIN, exactly as
 * `RnEnvelope.swift` is the Swift twin of the Kotlin one. A twin is a second
 * statement of a field set, which is a second thing to forget to update — so
 * `NetworkRequestFactsContractTest` DERIVES the field set, the `required` set
 * and every enum from `mobile-protocol/src/events/mobile_network_request.schema.json`
 * and fails when this file and the contract disagree. The schema is the
 * authority; this is a projection of it.
 *
 * ## The field set IS the privacy boundary
 *
 * There is no parameter for a URL, a query string, a path, a header name or
 * value, a request or response body, a credential, or an exception MESSAGE.
 * A caller cannot pass one in, which is a stronger guarantee than remembering
 * not to. [host] and [routeTemplate] are the already-normalized values the
 * policy produced; [errorClass] is a closed vocabulary, never a provider
 * string.
 *
 * ## `sourceLayer` cannot say `webview`
 *
 * The protocol declares `webview` in the enum and says of it, in so many
 * words, that it is NOT produced in v1: in-page traffic is the browser SDK's
 * to record, and a native producer claiming it would double-count the very
 * requests the Model B / N+S join exists to carry. [NATIVE_SOURCE_LAYERS]
 * therefore omits it and [refusalOrNull] refuses it — so the native producer
 * is structurally incapable of emitting a WebView-layer row.
 */
data class NetworkRequestFacts(
    /** Native, unguessable, one per request. Never derived from the URL. */
    val networkRequestId: String,
    /** Epoch millis at request START. Display/correlation only — may jump (NTP). */
    val requestStartedWallTime: Long,
    /** The AUTHORITATIVE placement clock, at request START. */
    val requestStartedMonotonicNanos: Long,
    val durationMs: Int,
    val method: String,
    val host: String,
    val outcome: String,
    val sourceLayer: String,
    val scheme: String? = null,
    /** A template, never a concrete path: `/products/{id}`, not `/products/42`. */
    val routeTemplate: String? = null,
    val status: Int? = null,
    val errorClass: String? = null,
    val requestBytes: Long? = null,
    val responseBytes: Long? = null,
    val protocolHint: String? = null,
) {
    companion object {
        /** `mobile_network_request.method` — the contract's closed set. */
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        /** `mobile_network_request.outcome`. */
        val OUTCOMES = setOf("success", "failure", "cancelled", "unknown")

        /** `mobile_network_request.errorClass` — a CLASS, never a message. */
        val ERROR_CLASSES = setOf("dns", "tls", "timeout", "connection", "cancelled", "other")

        /** `mobile_network_request.protocolHint`. */
        val PROTOCOL_HINTS = setOf("h1", "h2", "h3", "unknown")

        /** `mobile_network_request.scheme`. */
        val SCHEMES = setOf("http", "https")

        /**
 * The source layers a NATIVE producer may claim.
 *
 * `webview` is DELIBERATELY ABSENT — see the class comment. `fetch`
 * and `xhr` are absent for the same kind of reason: they name the
 * JavaScript layer, which this SDK does not observe.
 */
        val NATIVE_SOURCE_LAYERS = setOf("okhttp", "urlsession", "native_other")

        // The schema's own bounds. A value past one of these is refused here
        // rather than emitted, because the server rejects the WHOLE chunk and a
        // permanently-refused record parks at the head of the queue.
        const val ID_MAX = 64
        const val HOST_MAX = 255
        const val ROUTE_TEMPLATE_MAX = 512
        const val DURATION_MS_MAX = 3_600_000
        const val STATUS_MIN = 100
        const val STATUS_MAX = 599
    }

    /**
 * The reason this cannot go on the wire, or null when it can.
 *
 * Returns a CLOSED reason token, never free text and never the offending
 * value — a refusal that quoted the value would be the one place a URL or
 * a credential re-enters telemetry through the back door.
 */
    fun refusalOrNull(): String? = when {
        networkRequestId.isEmpty() || networkRequestId.length > ID_MAX -> "network_request_id_invalid"
        requestStartedWallTime <= 0L -> "started_wall_time_invalid"
        requestStartedMonotonicNanos < 0L -> "started_monotonic_nanos_invalid"
        durationMs < 0 || durationMs > DURATION_MS_MAX -> "duration_out_of_range"
        method !in METHODS -> "method_unknown"
        host.isEmpty() || host.length > HOST_MAX -> "host_invalid"
        outcome !in OUTCOMES -> "outcome_unknown"
        sourceLayer !in NATIVE_SOURCE_LAYERS -> "source_layer_not_native"
        scheme != null && scheme !in SCHEMES -> "scheme_unknown"
        routeTemplate != null && routeTemplate.length > ROUTE_TEMPLATE_MAX -> "route_template_too_long"
        status != null && (status < STATUS_MIN || status > STATUS_MAX) -> "status_out_of_range"
        errorClass != null && errorClass !in ERROR_CLASSES -> "error_class_unknown"
        requestBytes != null && requestBytes < 0L -> "request_bytes_negative"
        responseBytes != null && responseBytes < 0L -> "response_bytes_negative"
        protocolHint != null && protocolHint !in PROTOCOL_HINTS -> "protocol_hint_unknown"
        else -> null
    }
}

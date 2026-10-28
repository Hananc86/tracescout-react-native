package com.tracescout.mobile.transport

import java.net.URI
import java.util.Locale

/**
 * Native-core the specification NetworkInterceptor redaction and the specification Uploader retry
 * policy. Validated against the SHARED golden fixtures.
 */

// ---------------------------------------------------------------------------
// the specification Uploader
// ---------------------------------------------------------------------------

enum class ResponseAction { SUCCESS, RETRY, TERMINAL }

/**
 * Deterministic backoff. Jitter is applied by the caller ON TOP of this, so the
 * schedule itself stays comparable across bindings and fixture-testable.
 */
fun backoffMillis(
    attempt: Int,
    baseMs: Long = 1_000,
    factor: Double = 2.0,
    ceilingMs: Long = 300_000,
): Long {
    if (attempt < 1) return 0
    val raw = baseMs * Math.pow(factor, (attempt - 1).toDouble())
    // Compare in Double before narrowing: at attempt ~40 the exponential
    // overflows Long, and a Long overflow wraps NEGATIVE — which would return a
    // backoff of zero and turn the ceiling into a hot retry loop.
    return if (raw >= ceilingMs.toDouble()) ceilingMs else raw.toLong()
}

/**
 * 4xx is TERMINAL except 408/429. Retrying a genuinely rejected chunk forever
 * is how a client becomes a denial-of-service against its own backend.
 *
 * The one exception worth spelling out: a `503` carrying `retriable: true` is
 * the backend's typed refusal for a chunk whose security metadata could not be
 * recorded. It is explicitly asking for the chunk again — discarding it loses
 * data the backend WANTS, and leaves the session's privacy posture unrecorded.
 */
fun classifyResponse(status: Int, retriable: Boolean = false): ResponseAction = when {
    status in 200..299 -> ResponseAction.SUCCESS
    status == 408 || status == 429 -> ResponseAction.RETRY
    status == 503 && retriable -> ResponseAction.RETRY
    status in 400..499 -> ResponseAction.TERMINAL
    else -> ResponseAction.RETRY
}

// ---------------------------------------------------------------------------
// the specification NetworkInterceptor — redaction
// ---------------------------------------------------------------------------

/**
 * NOT configurable, by design. An allowlist that can name `authorization` is a
 * credential exfiltration switch with a friendly name.
 */
val ALWAYS_EXCLUDED_HEADERS = listOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "x-auth-token",
    "x-ingest-key",
)

private val SECRET_QUERY_KEY = Regex(
    "^(token|access_token|id_token|refresh_token|api_?key|apikey|secret|password|passwd|pwd|signature|sig|auth|session|sid|code)$",
    RegexOption.IGNORE_CASE,
)

/**
 * Strip URL credentials and redact secret-ish query VALUES, keeping the keys so
 * the shape of the request stays legible.
 *
 * A URL that cannot be parsed cannot be proven redacted, so it is replaced
 * rather than buffered — an unparseable string is exactly where a credential
 * would hide.
 */
fun redactUrl(url: String): String {
    val uri = try {
        URI(url)
    } catch (t: Throwable) {
        return "[unparseable]"
    }
    if (uri.scheme == null || uri.host == null) return "[unparseable]"

    val query = uri.rawQuery?.split("&")?.joinToString("&") { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) return@joinToString pair
        val key = pair.substring(0, idx)
        val decodedKey = try {
            java.net.URLDecoder.decode(key, "UTF-8")
        } catch (t: Throwable) {
            key
        }
        if (SECRET_QUERY_KEY.matches(decodedKey)) "$key=%5BREDACTED%5D" else pair
    }

    val port = if (uri.port == -1) "" else ":${uri.port}"
    val path = uri.rawPath ?: ""
    val q = if (query.isNullOrEmpty()) "" else "?$query"
    // Userinfo is dropped entirely — never re-emitted, not even masked.
    return "${uri.scheme}://${uri.host}$port$path$q"
}

/**
 * Header capture is opt-in per key AND the always-excluded set wins over the
 * allowlist unconditionally.
 */
fun filterHeaders(
    headers: Map<String, String>,
    allowlist: List<String> = emptyList(),
): Map<String, String> {
    val allowed = allowlist.map { it.lowercase(Locale.ROOT) }.toSet()
    return headers.entries
        .map { it.key.lowercase(Locale.ROOT) to it.value }
        .filter { (k, _) -> k !in ALWAYS_EXCLUDED_HEADERS && k in allowed }
        .toMap()
}

// ---------------------------------------------------------------------------
// Terminal disposition — WHO is indicted by a permanent refusal
// ---------------------------------------------------------------------------

/**
 * What a TERMINAL response says about the RECORDS, as opposed to about the
 * credential, the build, or the address they were sent to.
 *
 * `classifyResponse` answers "may we send this again?". That is not the same
 * question as "may we DELETE these records?", and conflating the two is how a
 * missing precondition destroyed six perfectly good records on a real device
 * (`404 session_not_found` → TERMINAL → quarantine; proven 2026-08-09).
 *
 * **Only a refusal that identifies the SUBMITTED BATCH may quarantine.** A
 * response that indicts the key, the build, or the session address says nothing
 * about the bytes, and a later corrected condition can deliver the very same
 * records. Quarantine is deletion; it is reserved for a rejection the records
 * themselves caused.
 *
 * This is a SHARED policy, not an Android one: the same response semantics
 * apply to the Android and iOS producers, and a per-producer copy would be a
 * second authority over the thing that deletes customer data. Any divergence
 * from `mobile-react-native/ios/Sources/TraceScoutRN/Transport.swift` is a
 * defect — the two files are restatements of one policy, not two policies.
 */
enum class TerminalDisposition {
    /** The CREDENTIAL is indicted. Retain; a corrected key delivers these bytes. */
    RETAIN_AUTH,
    /**
 * The BUILD is indicted, and the build that made these bytes is stamped
 * INTO them at capture time — so no later build can make THESE records
 * acceptable. Preserve them losslessly in the parked store and move them
 * OUT of the active queue, so records captured by a compatible build are
 * not starved behind a refusal that can never clear.
 *
 * Retaining them in place was the honest first answer and it was wrong in
 * a way only a device showed: a permanently-refused slice at the head of a
 * strict-FIFO queue blocks every record behind it for the life of the
 * install. Parking is not quarantine — nothing is destroyed.
 */
    PARK_PRODUCER,
    /** The ADDRESS is indicted — the session does not exist YET. Retain. */
    RETAIN_SESSION_PRECONDITION,
    /**
 * A SERVER FEATURE GATE is indicted. The refusal is about a record CLASS
 * being switched off, never about these bytes — and a batch prefix is
 * TYPE-BLIND, so deleting it discards unrelated replay records that merely
 * shared a chunk with one gated record. Retain; the SAME bytes are valid
 * the moment the gate opens.
 */
    RETAIN_FEATURE_GATE,
    /**
 * The submitted BOUNDARY is indicted — the batch was too large to be
 * accepted, by bytes or by record count. Nothing is wrong with the records.
 *
 * DISTINCT FROM EVERY `RETAIN_*` ABOVE, AND THE DIFFERENCE IS THE
 * IN-FLIGHT DESCRIPTOR. A retain keeps the unresolved batch frozen so the
 * next drain re-presents the SAME bytes — correct when a later condition
 * can make that exact batch acceptable (a replaced key, an originated
 * session, an opened gate). A boundary refusal is the one case where that
 * is a guarantee of failure: re-presenting the identical oversized body
 * asks a server that has already refused it, forever. So the descriptor is
 * ABANDONED and a smaller prefix is built from the SAME durable records.
 *
 * AND IT MAY NEVER QUARANTINE. Before this existed, a 413 fell through
 * to `QUARANTINE_PREFIX` — so a client that accumulated records precisely
 * because it could not reach the server DESTROYED them on the first request
 * that reached it. That is the defect; see `ChunkBounds`.
 *
 * Abandoning the descriptor is sound ONLY because a 413 on this edge is
 * provably write-free: the parser refuses before the handler is entered,
 * and the typed event-count refusal returns before the controller's
 * PREFLIGHT/write boundary. `scripts/verify-server-413-write-free.mjs`
 * asserts both, and refuses rather than passing when it cannot read the
 * server it is asserting about.
 */
    RESLICE_BATCH,
    /** The RECORDS are indicted. This, and only this, may delete the prefix. */
    QUARANTINE_PREFIX,
}

/** Build/capability refusals: an old build cannot grow fields by retrying. */
val PRODUCER_UNSUPPORTED_CODES: Set<String> =
    setOf("unknown_sdk_family", "unsupported_sdk_version")

/**
 * Session-precondition refusals: the chunk addressed a session that does not
 * exist. The mobile chunk edge ADDRESSES an existing session row and originates
 * one only from a self-naming `mobile_session_start`, so this is a statement
 * about the address, not about the bytes.
 */
val SESSION_PRECONDITION_CODES: Set<String> =
    setOf("session_not_found", "session_precondition_missing")

/**
 * Feature-gate refusals — EXACTLY ONE CODE, deliberately.
 *
 * SCOPE. This is not "network refusals retain". Every other network
 * validation code (`network_field_forbidden`, `network_protocol_hint_invalid`,
 * `batch_sequence_not_contiguous`, …) indicts the BYTES and still quarantines
 * the mixed prefix, unchanged by this fix. Only the capture switch being off
 * is a condition that a later request can satisfy with these same records.
 *
 * DISPOSED BY CODE, NEVER BY STATUS OR BY THE SERVER'S `retriable` FLAG.
 * The deployed API's `reject(code, detail, retriable = false)` defaults to
 * PERMANENT, so one omitted argument on the server would otherwise turn a
 * feature flag into a deleter of customer telemetry.
 */
val FEATURE_GATE_CODES: Set<String> = setOf("network_capture_disabled")

/**
 * Boundary refusals — the batch was too large, by bytes or by record count.
 *
 * `too_many_events` is the deployed controller's typed code; `chunk_too_large`
 * is the `error` name it pairs with that code, included so a server that ever
 * reports it as the CODE still re-slices rather than falling through to
 * quarantine. The parser's own refusal carries NO code at all, which is why the
 * status arm exists as well and is tried first.
 */
val RESLICE_CODES: Set<String> = setOf("too_many_events", "chunk_too_large")

/**
 * A bare 404 is included deliberately. It does not identify the submitted
 * batch — it says the thing being addressed is absent — so under the rule
 * above it can never justify deleting records. Retaining a batch that will
 * never be accepted costs bounded queue space; quarantining one that WOULD
 * have been accepted costs the customer their data. The asymmetry decides it.
 */
/**
 * 413 IS DECIDED AFTER AUTH AND BEFORE EVERY CODE SET, and the direction of
 * that ordering is chosen by asymmetry rather than by taste. A 413 shadowed by
 * a code set could fall through to `QUARANTINE_PREFIX`, which DELETES records;
 * a code set shadowed by 413 merely re-slices, and the smaller batch then
 * receives the real typed refusal on its own terms. The non-destructive
 * direction wins, exactly as it does for the bare 404 below.
 */
fun terminalDisposition(status: Int, code: String?): TerminalDisposition = when {
    status == 401 || status == 403 -> TerminalDisposition.RETAIN_AUTH
    status == 413 -> TerminalDisposition.RESLICE_BATCH
    code != null && code in RESLICE_CODES -> TerminalDisposition.RESLICE_BATCH
    code != null && code in PRODUCER_UNSUPPORTED_CODES -> TerminalDisposition.PARK_PRODUCER
    code != null && code in FEATURE_GATE_CODES -> TerminalDisposition.RETAIN_FEATURE_GATE
    code != null && code in SESSION_PRECONDITION_CODES ->
        TerminalDisposition.RETAIN_SESSION_PRECONDITION
    status == 404 -> TerminalDisposition.RETAIN_SESSION_PRECONDITION
    else -> TerminalDisposition.QUARANTINE_PREFIX
}

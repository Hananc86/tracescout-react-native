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

// ---------------------------------------------------------------------------
// HOST-FACING TRANSPORT STATE
//
// EXTRACTED, NOT INVENTED. This is the decision `RnTransport.classify` and
// `RnTransport.retryIsOwedToWorker` have always made, moved to sit beside
// [terminalDisposition] — the refusal classification it is the host-facing half
// of. It exists here because a SECOND producer binding (Flutter) needs the same
// answer, and two copies of "which refusals may auto-retry" is two policies
// that can drift about when the SDK spins against a credential only the app can
// fix.
//
// The DURABILITY decision stays with [terminalDisposition] and the pipeline;
// this says only what a HOST may be told and who owes the retry. Both are read
// from the typed CODE, never from an HTTP status or the server's `retriable`
// flag — the deployed API's `reject(code, detail, retriable = false)` defaults
// to permanent, so one omitted argument on the server must never become a
// client policy input.

/** Typed states a host may surface. Deliberately never queue contents. */
enum class HostTransportState {
    NOT_CONFIGURED,
    READY,
    /** A BUILD fact: this producer is not admitted. No retry can clear it. */
    PRODUCER_UNSUPPORTED,
    /** A CREDENTIAL fact: the queue is intact and only the app can fix it. */
    AUTH_REJECTED,
    /**
 * The session this batch addresses does not exist YET. The queue is
 * retained byte-for-byte and the constrained worker retries the SAME
 * records under bounded backoff — unlike AUTH_REJECTED and
 * PRODUCER_UNSUPPORTED, this condition can clear by itself.
 */
    SESSION_PRECONDITION,
    PENDING_NETWORK,
    /**
 * F4-P0 Slice D: the pipeline refused before any request because an
 * eviction bracket is unresolved or its marker is unreadable. The next
 * OPEN retries recovery; no automatic worker retry is owed.
 */
    STORAGE_RECOVERY_BLOCKED,
    /** An erasure was requested; it completes under the fence at the next open. */
    ERASURE_PENDING,
    /**
 * The queue could not be READ at all, so records may still be there.
 *
 * NOT A SERVER CODE — no refusal string maps here, because this is a
 * LOCAL drain condition the transport observes before any request. It is
 * in this enum because it is a state a host may be surfaced and because
 * [retryOwedToWorker] must have an answer for it; `hostTransportState`
 * deliberately cannot produce it.
 *
 * Transient, NOT a Slice D condition: Slice D declines a schedule because
 * only the next OPEN can conclude a bracket or an erasure, whereas a READ
 * has no such restriction — the very next drain can succeed once the key
 * or the file is available again. Reporting this as a finished drain is
 * the F-6/F-42 loss class (the obligation released with records on disk).
 */
    QUEUE_UNREADABLE,
    /**
 * Another holder owns the drain lease, so this attempt never looked.
 *
 * NOT A SERVER CODE, for the same reason as [QUEUE_UNREADABLE]. Owed a
 * retry in the same transient sense: the holder releases, and the very
 * next drain can read and deliver. `false` here would be the caller
 * discarding the obligation precisely when it could not discharge it.
 */
    DRAIN_LEASE_BUSY,
}

/**
 * The typed state a transport result must produce for the host.
 *
 * `producer_unsupported` and `producer_parked` are the SAME fact here: a
 * producer-capability refusal no retry can clear. They differ only in whether
 * the bytes moved out of the active queue, which is an internal durability
 * detail and not a state an app should branch on.
 *
 * A null code is READY: nothing refused.
 *
 * This function can NEVER answer [HostTransportState.QUEUE_UNREADABLE] or
 * [HostTransportState.DRAIN_LEASE_BUSY], and that is deliberate: both are local
 * drain conditions observed before any request, so no server code may be read
 * as either. The `else` arm below sends an unknown code to PENDING_NETWORK,
 * which keeps that property true for any code the server grows later.
 */
fun hostTransportState(code: String?): HostTransportState = when (code) {
    "producer_unsupported", "producer_parked" -> HostTransportState.PRODUCER_UNSUPPORTED
    "auth_rejected" -> HostTransportState.AUTH_REJECTED
    "session_precondition_missing" -> HostTransportState.SESSION_PRECONDITION
    // The same two pre-request refusals RnTransport.classify names — one
    // mapping, so the Flutter binding never reads them as a network blip.
    "bracket_unresolved", "bracket_marker_unreadable" -> HostTransportState.STORAGE_RECOVERY_BLOCKED
    "erasure_requested" -> HostTransportState.ERASURE_PENDING
    null -> HostTransportState.READY
    else -> HostTransportState.PENDING_NETWORK
}

/**
 * Who owes the retry for a finished drain.
 *
 * DERIVED FROM THE HOST STATE, NOT FROM `Disposition.RETRYABLE_REMAINING`.
 * Auth uses that disposition internally — correctly, since a replaced
 * credential delivers the same records — but the HOST contract deliberately
 * treats AUTH_REJECTED as non-auto-retrying: the SDK must not spin against a
 * credential only the app can fix. Scheduling from the disposition alone would
 * quietly make auth auto-retry.
 */
fun retryOwedToWorker(state: HostTransportState): Boolean = when (state) {
    // Transient and self-clearing: an opener may originate the session, a
    // network may return.
    HostTransportState.SESSION_PRECONDITION, HostTransportState.PENDING_NETWORK -> true
    // A credential or a build fact. Neither clears by retrying.
    HostTransportState.AUTH_REJECTED, HostTransportState.PRODUCER_UNSUPPORTED -> false
    HostTransportState.READY, HostTransportState.NOT_CONFIGURED -> false
    // Owed no automatic retry — the next OPEN retries recovery, and an erasure
    // completes under the fence at the next open (as RnTransport rules).
    HostTransportState.STORAGE_RECOVERY_BLOCKED, HostTransportState.ERASURE_PENDING -> false
    // Local drain conditions, both transient in the same sense: the key or the
    // file becomes readable again, or the lease holder releases. Owed a retry,
    // because the alternative is a drain that could not look reporting itself
    // finished — the F-6/F-42 loss class.
    HostTransportState.QUEUE_UNREADABLE, HostTransportState.DRAIN_LEASE_BUSY -> true
}

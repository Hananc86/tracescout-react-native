package com.tracescout.mobile.core

import java.security.SecureRandom

/**
 * Native-core the specification Clock, the specification IdGenerator, the specification InteractionCapture bounds,
 * the specification VisualFrameCapturer gate, the specification FrameEncoder validation.
 *
 * Validated against the SHARED golden fixtures — see `NativeCoreConformanceTest`.
 */

// ---------------------------------------------------------------------------
// the specification Clock
// ---------------------------------------------------------------------------

interface Clock {
    /** Unix epoch millis. MAY jump backwards (NTP, user change). Display only. */
    fun wallMillis(): Long

    /** Nanoseconds from an arbitrary fixed origin. MUST NOT decrease in-process. */
    fun monotonicNanos(): Long
}

object SystemClock : Clock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    /**
 * `nanoTime` is the JVM's monotonic source. Deliberately NOT
 * `currentTimeMillis * 1_000_000` — a wall clock correction would then
 * reorder a session, which is the exact failure `ordering.json` pins.
 */
    override fun monotonicNanos(): Long = System.nanoTime()
}

/**
 * Canonical event ordering: by monotonic nanos ONLY, with the event id as a
 * deterministic tie-break so two bindings agree on an exact-instant tie.
 */
fun <T> orderByMonotonic(
    events: List<T>,
    monotonic: (T) -> Long,
    eventId: (T) -> String,
): List<T> = events.sortedWith(compareBy({ monotonic(it) }, { eventId(it) }))

// ---------------------------------------------------------------------------
// the specification IdGenerator
// ---------------------------------------------------------------------------

object Ids {
    private val SESSION_ID = Regex("^ts_[0-9]+_[0-9a-f]{12}$")
    private val secureRandom = SecureRandom()

    fun isValidSessionId(s: String?): Boolean = s != null && SESSION_ID.matches(s)

    /**
 * `ts_<wallMillis>_<12 lowercase hex>`. The backend keys the session row on
 * this exact string.
 *
 * Uses [SecureRandom] deliberately: a guessable session id is a
 * cross-session read. If a secure source were unavailable the contract says
 * to fail session start rather than fall back — on the JVM SecureRandom
 * always resolves, so the fallback branch does not exist to be got wrong.
 *
 * The id carries NO device identifier, advertising id or user id — it is a
 * correlation handle, not a fingerprint.
 */
    fun newSessionId(clock: Clock = SystemClock): String {
        val bytes = ByteArray(6)
        secureRandom.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "ts_${clock.wallMillis()}_$hex"
    }
}

// ---------------------------------------------------------------------------
// the specification InteractionCapture — viewport validation
// ---------------------------------------------------------------------------

data class Viewport(val width: Int, val height: Int)

data class InteractionValidity(val valid: Boolean, val reason: String?)

/**
 * Out-of-bounds coordinates are EXCLUDED and counted, never clamped. Clamping
 * invents a tap at the screen edge the user never made, and a heatmap built
 * from invented taps is worse than one with a disclosed gap.
 */
fun validateInteraction(x: Double?, y: Double?, viewport: Viewport?): InteractionValidity {
    if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
        return InteractionValidity(false, "viewport_unknown")
    }
    if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
        return InteractionValidity(false, "coordinate_invalid")
    }
    if (x < 0 || y < 0 || x > viewport.width || y > viewport.height) {
        return InteractionValidity(false, "coordinate_out_of_bounds")
    }
    return InteractionValidity(true, null)
}

// ---------------------------------------------------------------------------
// the specification VisualFrameCapturer — the capture decision
// ---------------------------------------------------------------------------

object CaptureReasons {
    val ALL = listOf(
        "explicit_allow",
        "unsupported_surface_fallback",
        "wireframe_insufficient",
        "manual_api",
    )
}

data class FrameDecision(val capture: Boolean, val refusal: String?)

/**
 * Decide whether a frame may be captured. Every precondition must be PROVEN;
 * an unproven one refuses, because "probably fine" is a leak.
 *
 * A platform-secure surface is EXCLUDED, not blurred — blurring it is still
 * capturing it.
 */
fun frameCaptureDecision(
    captureMode: String?,
    privacyProcessed: Boolean,
    platformSecure: Boolean,
    captureReason: String?,
): FrameDecision = when {
    captureMode != "semantic_with_frames" -> FrameDecision(false, "capture_mode_disallows_frames")
    platformSecure -> FrameDecision(false, "platform_secure_surface")
    !privacyProcessed -> FrameDecision(false, "privacy_not_processed")
    captureReason !in CaptureReasons.ALL -> FrameDecision(false, "capture_reason_invalid")
    else -> FrameDecision(true, null)
}

// ---------------------------------------------------------------------------
// the specification FrameEncoder — declared vs actual
// ---------------------------------------------------------------------------

const val MAX_FRAME_DIM = 8192
const val MAX_FRAME_BYTES = 8 * 1024 * 1024

data class EncodingCheck(val ok: Boolean, val code: String?)

/**
 * The backend re-checks all of this at its promotion gate. Validating here
 * means a binding that reports the PRE-compression size fails locally with a
 * named code rather than at an unexplained upload rejection.
 */
fun validateFrameEncoding(
    width: Int,
    height: Int,
    declaredByteSize: Int,
    actualByteSize: Int,
    declaredFormat: String,
    sniffedFormat: String,
): EncodingCheck = when {
    width < 1 || height < 1 || width > MAX_FRAME_DIM || height > MAX_FRAME_DIM ->
        EncodingCheck(false, "frame_dimensions_invalid")
    declaredByteSize < 1 || declaredByteSize > MAX_FRAME_BYTES ->
        EncodingCheck(false, "frame_byte_size_invalid")
    declaredByteSize != actualByteSize ->
        EncodingCheck(false, "frame_byte_size_mismatch")
    declaredFormat != sniffedFormat ->
        EncodingCheck(false, "frame_format_mismatch")
    else -> EncodingCheck(true, null)
}

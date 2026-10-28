package com.tracescout.mobile.capture

import com.tracescout.mobile.privacy.Posture
import com.tracescout.mobile.privacy.PrivacyLevel
import com.tracescout.mobile.privacy.worstPosture
import java.util.UUID
import java.util.regex.Pattern

/**
 * Native-core the specification WebViewBridge — native ↔ Browser-SDK correlation.
 *
 * **This is a correlation channel, NOT a JS execution channel.** It carries a
 * segment identifier and a privacy posture, and nothing else. That restraint is
 * the design: an `addJavascriptInterface` that can be reached from page content
 * is a remote-code-execution surface inside the customer's app, and "we only
 * call safe methods" is not a boundary — the page decides what to call.
 *
 * So the accepted message vocabulary is a CLOSED set of two shapes, every field
 * is validated against a strict pattern, and anything unrecognised is dropped
 * with a typed reason rather than forwarded.
 *
 * **The WebView's posture is a contributing segment for the session posture.**
 * A WebView that masks less than the native side makes the WHOLE session
 * weaker — the resolved posture is the worst across segments, so a permissive
 * embedded page cannot be hidden behind a strict native shell.
 */
object WebViewBridge {

    /** Only these two message kinds exist. Anything else is refused. */
    const val KIND_HELLO = "ts_webview_hello"
    const val KIND_POSTURE = "ts_webview_posture"

    private val SEGMENT_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$")
    private val SESSION_ID = Pattern.compile("^ts_[0-9]+_[0-9a-f]{12}$")

    /** The closed set of frame roles a participating document may claim. */
    val FRAME_ROLES = setOf(
        "parent", "same_origin_child", "cross_origin_child", "nested_outer", "nested_inner",
    )

    data class Segment(
        val segmentId: String,
        val nativeSessionId: String,
        val posture: Posture,
        /** The browser side's own coarse posture string, recorded verbatim for audit. */
        val browserPrivacyStatus: String?,
        /** Which frame of the WebView spoke, from the closed [FRAME_ROLES] set. */
        val frameRole: String? = null,
        /** The frame's normalized origin, or null when no policy was in force. */
        val origin: String? = null,
        /**
 * How [origin] was learned. An AUTHENTICATED origin came from the
 * browser; a DECLARED one came from the page and a hostile frame can
 * claim any admitted value. Recorded so a report can never present the
 * weaker evidence as the stronger one.
 */
        val originTrust: OriginTrust? = null,
    )

    sealed class Result {
        data class Accepted(val segment: Segment) : Result()
        data class Refused(val reason: String) : Result()
    }

    /** Mint a segment id natively. The PAGE never chooses its own identifier. */
    @JvmStatic
    fun newSegmentId(): String = UUID.randomUUID().toString().replace("-", "").take(32)

    /**
 * Handle one inbound bridge message.
 *
 * [expectedSegmentId] and [nativeSessionId] are held by the NATIVE side. A
 * message claiming a different segment is refused — otherwise any frame in
 * the WebView could attach itself to, or overwrite, another segment's
 * posture.
 */
    @JvmStatic
    @JvmOverloads
    fun handle(
        kind: String?,
        fields: Map<String, Any?>,
        expectedSegmentId: String,
        nativeSessionId: String,
        /**
 * The host app's admitted-origin allowlist. When null, no origin model
 * is in force (the pre-origin behaviour, kept only so existing callers
 * that embed a single hermetic document keep compiling).
 */
        policy: WebViewOriginPolicy? = null,
        /**
 * The origin the BROWSER attributed to the calling frame, when the
 * channel can supply one. Null on `addJavascriptInterface`, which
 * carries no frame identity at all.
 */
        authenticatedOrigin: String? = null,
    ): Result {
        if (kind != KIND_HELLO && kind != KIND_POSTURE) {
            return Result.Refused("unknown_message_kind")
        }
        if (!SESSION_ID.matcher(nativeSessionId).matches()) {
            return Result.Refused("native_session_invalid")
        }

        // ORIGIN ADMISSION COMES FIRST, before anything the frame claims about
        // the session. Whether this speaker may participate at all is a
        // different question from whether what it said is well-formed, and
        // deciding it second means an unadmitted frame's refusal is reported
        // with whatever unrelated reason it happened to trip on — an
        // ad iframe would be turned away for a segment-id mismatch, which
        // reads like a bug in our own page rather than a policy decision.
        var origin: String? = null
        var trust: OriginTrust? = null
        if (policy != null) {
            val declared = fields["frameOrigin"] as? String
            if (authenticatedOrigin != null) {
                val auth = WebViewOriginPolicy.normalize(authenticatedOrigin)
                    ?: return Result.Refused("origin_malformed")
                // A frame that names an origin OTHER than the one the browser
                // attributed to it is lying. Only this channel can catch that.
                if (declared != null && WebViewOriginPolicy.normalize(declared) != auth) {
                    return Result.Refused("origin_spoofed")
                }
                origin = auth
                trust = OriginTrust.AUTHENTICATED
            } else {
                if (declared == null) return Result.Refused("origin_missing")
                origin = WebViewOriginPolicy.normalize(declared)
                    ?: return Result.Refused("origin_malformed")
                trust = OriginTrust.DECLARED
            }
            if (!policy.isAdmitted(origin)) return Result.Refused("origin_not_admitted")

            val role = fields["frameRole"] as? String ?: return Result.Refused("frame_role_missing")
            if (role !in FRAME_ROLES) return Result.Refused("frame_role_invalid")
        } else if (fields.containsKey("frameRole") && fields["frameRole"] !in FRAME_ROLES) {
            return Result.Refused("frame_role_invalid")
        }

        val claimed = fields["segmentId"] as? String
            ?: return Result.Refused("segment_id_missing")
        if (!SEGMENT_ID.matcher(claimed).matches()) return Result.Refused("segment_id_invalid")
        // The page cannot address a segment the native side did not open.
        if (claimed != expectedSegmentId) return Result.Refused("segment_id_mismatch")

        // Anything outside the closed vocabulary is DROPPED, not forwarded. A
        // bridge that passes through unknown keys is a bridge whose surface is
        // defined by whoever is on the other end.
        val allowed = setOf(
            "segmentId", "level", "maskText", "maskInputs", "browserPrivacyStatus",
            "frameRole", "frameOrigin",
        )
        val unknown = fields.keys - allowed
        if (unknown.isNotEmpty()) return Result.Refused("unknown_fields:${unknown.sorted().joinToString(",")}")

        // An ABSENT or unrecognised level resolves to `unknown` — the worst —
        // never to a convenient default of strict.
        val level = PrivacyLevel.fromWire(fields["level"] as? String)
        val posture = Posture(
            level = level,
            maskText = fields["maskText"] == true,
            maskInputs = fields["maskInputs"] == true,
        )

        return Result.Accepted(
            Segment(
                segmentId = claimed,
                nativeSessionId = nativeSessionId,
                posture = posture,
                browserPrivacyStatus = (fields["browserPrivacyStatus"] as? String)?.take(32),
                frameRole = fields["frameRole"] as? String,
                origin = origin,
                originTrust = trust,
            ),
        )
    }

    /**
 * The session posture including every WebView segment.
 *
 * Exposed as its own function so the "a weaker WebView weakens the session"
 * rule is a single call site rather than something each caller re-derives.
 */
    @JvmStatic
    fun sessionPosture(native: Posture, segments: List<Segment>): Posture =
        worstPosture(listOf(native) + segments.map { it.posture })
}

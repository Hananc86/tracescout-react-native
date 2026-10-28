package com.tracescout.rn

import java.util.Locale
import java.util.regex.Pattern

/**
 * MODEL B — the React Native native↔WebView CORRELATION decision.
 *
 * The charter's the specification is frozen and this object is its executable form:
 *
 * - Model B: native session **N**, WebView segment **S**, browser content
 * **B**; the correlation claim is **N + S**, never S alone.
 * - A WebView present but whose content is not captured must appear as an
 * **honest absence**: a BLOCKED node with a TYPED reason, never a
 * structurally-identical copy of the previous screen and never nothing.
 * - Arbitrary third-party WebView capture **must remain blocked**. Embedded
 * capture depends on the installed WebView **PROVIDER** (a runtime
 * preflight), not the OS API level; an unsupported provider yields
 * **no segment row at all**.
 *
 * It is the Kotlin side of the shared contract, pinned to
 * `parity-vectors/webview-segment-vectors.json` and consumed by the JS
 * reference in `mobile-react-native/src/webViewBridge.js`. A platform
 * disagreement fails on BYTES, not on three people reading one paragraph —
 * which is the defect the hand-written Swift hierarchy twin produced before
 * anyone compared it.
 *
 * ## THE ORDER OF THE RULES IS THE CONTRACT
 *
 * Capability → message kind → native session → origin admission → segment
 * identity → field vocabulary → admit. Several vectors exist ONLY to pin that
 * order, because every reordering produces a refusal that misdescribes its own
 * cause:
 *
 * - Deciding origin admission AFTER the segment id turns an unadmitted
 * third-party frame away for a `segment_id_mismatch`, which reads like a
 * bug in our own page rather than a policy decision. (This is the
 * Android native bridge's own measured lesson; the order is copied from
 * it deliberately, not re-derived.)
 * - Deciding capability LAST reports a FEATURE refusal for a 2018 engine,
 * which describes the symptom and sends the reader after a feature flag
 * instead of an engine update.
 *
 * ## THIS IS A CORRELATION CHANNEL, NOT A JS EXECUTION CHANNEL
 *
 * It carries a segment identifier and a privacy posture and NOTHING else. The
 * accepted message vocabulary is a CLOSED set of two shapes, every field is
 * validated, and anything unrecognised is refused with a typed reason rather
 * than forwarded. The page can never choose its own segment id: the native
 * side holds [expectedSegmentId] and a message naming anything else is
 * refused, so a frame cannot attach itself to — or overwrite — another
 * segment's posture.
 *
 * ## TWO DELIBERATE DIVERGENCES FROM THE ANDROID NATIVE BRIDGE
 *
 * 1. **The capability preflight is INSIDE this decision**, not upstream at the
 * route. On native it lives at the call site
 * (`webViewSegmentId = if (capability.supported) segmentId else null`),
 * which is one call site that has to remember. the specification says an unsupported
 * provider yields no segment row AT ALL, and the only way to guarantee
 * that is for the gate to be in the same decision that produces the
 * segment. A route that forgets cannot produce one.
 * 2. **A missing origin policy FAILS CLOSED.** The native bridge tolerates a
 * null policy, and says why: *"kept only so existing callers that embed a
 * single hermetic document keep compiling"*. React Native is a new surface
 * with no such callers, so there is nothing to preserve and the fail-closed
 * answer is the right one. An undeclared allowlist is refused
 * `origin_policy_absent`.
 */
object RnWebViewSegment {

    /** Only these two message kinds exist. Anything else is refused. */
    const val KIND_HELLO = "ts_webview_hello"
    const val KIND_POSTURE = "ts_webview_posture"

    /**
 * The TYPED node reasons, taken from the protocol's CLOSED `blockedReason`
 * enum (`mobile-protocol/src/node.schema.json`).
 *
 * THE ENUM IS CLOSED AND THE DEPLOYED SERVER REFUSES ANYTHING OUTSIDE
 * IT. Inventing a value like `captured_by_browser_segment` is the
 * `mobile_screen_never_record` mistake — an event type the protocol never
 * declared, refused permanently by the API and quarantining the records
 * that carried it. So an ADMITTED segment's node keeps
 * [BLOCKED_THIRD_PARTY]: the native walker still does not traverse web
 * content, and what says content exists ELSEWHERE is the presence of
 * `webViewSegmentId` on the node, never a different reason.
 */
    const val BLOCKED_THIRD_PARTY = "third_party_webview"

    /** An unsupported PROVIDER is a different fact and gets its own reason. */
    const val BLOCKED_UNSUPPORTED = "unsupported_surface"

    /** The closed set of frame roles a participating document may claim. */
    val FRAME_ROLES = setOf(
        "parent", "same_origin_child", "cross_origin_child", "nested_outer", "nested_inner",
    )

    /**
 * The accepted field vocabulary. A bridge that passes through unknown keys
 * is a bridge whose surface is defined by whoever is on the other end.
 */
    val ALLOWED_FIELDS = setOf(
        "segmentId", "level", "maskText", "maskInputs", "browserPrivacyStatus",
        "frameRole", "frameOrigin",
    )

    /**
 * The browser side's own coarse status — bounded to the WIRE ENUM
 * (`envelope.schema.json: webViewBrowserPrivacyStatus`).
 *
 * AN UNRECOGNISED VALUE IS **ABSENT**, NEVER COERCED TO `unknown`. The
 * deployed API refuses a present-but-invalid value with a PERMANENT
 * (non-retriable) `webview_browser_privacy_status_invalid`, which
 * quarantines the whole chunk — so a value that would be refused
 * downstream is dropped at the source. It is not coerced to `unknown`
 * either, because `unknown` is a CLAIM about the browser recorder's state
 * and we do not have one; absent means "not reported". This mirrors the
 * Android native producer, which bounds it at the same boundary for the
 * same stated reason.
 */
    val BROWSER_PRIVACY_STATUSES = setOf("enabled", "partial", "disabled", "unknown")

    /**
 * Weakest → strongest, the SAME ordering as
 * `mobile-protocol/src/posture.cjs PRIVACY_LEVELS`. Restating the ordering
 * is the defect that file exists to prevent, so this list is pinned
 * against it by a parity assertion rather than trusted.
 */
    val PRIVACY_LEVELS = listOf("unknown", "relaxed", "strict")

    /** Whether embedded-WebView capture can run on THIS device right now. */
    enum class Capability(val wire: String) {
        SUPPORTED("webview_capture_supported"),
        PROVIDER_UNSUPPORTED("webview_provider_unsupported"),
        FEATURE_UNSUPPORTED("webview_feature_unsupported"),
        PROVIDER_UNAVAILABLE("webview_provider_unavailable");

        companion object {
            /**
 * ANYTHING UNRECOGNISED IS `PROVIDER_UNAVAILABLE`, never
 * SUPPORTED. An unreadable verdict is not evidence of a good one.
 */
            fun from(s: String?): Capability =
                entries.firstOrNull { it.wire == s } ?: PROVIDER_UNAVAILABLE
        }
    }

    /**
 * How the frame's origin was learned.
 *
 * An AUTHENTICATED origin came from the browser and is unforgeable; a
 * DECLARED one came from the page and a hostile frame can claim any
 * admitted value. Recorded so a report can never present the weaker
 * evidence as the stronger one.
 */
    enum class OriginTrust(val wire: String) {
        AUTHENTICATED("authenticated"), DECLARED("declared")
    }

    enum class Outcome(val wire: String) {
        /** N + S established. The envelope may carry the segment. */
        ADMIT("admit"),

        /** A message we received and rejected. A segment may still exist. */
        REFUSE("refuse"),

        /** the specification: no segment row may exist at all on this device. */
        ABSENT("absent"),
    }

    /** What the envelope carries. Nothing here is page-chosen except the posture claim. */
    data class Segment(
        val segmentId: String,
        val nativeSessionId: String,
        val level: String,
        val maskText: Boolean,
        val maskInputs: Boolean,
        /** Null when the page reported nothing recognisable. */
        val browserPrivacyStatus: String?,
        val frameRole: String?,
        val origin: String?,
        val originTrust: OriginTrust?,
    )

    data class Decision(
        val outcome: Outcome,
        /** A typed code. Never free text, never built from a page value. */
        val code: String,
        /**
 * The honest-absence reason for the NATIVE node, present in every
 * outcome — including ADMIT, because the native walker never captures
 * web content whatever the segment says.
 */
        val nodeBlockedReason: String,
        val segment: Segment?,
    )

    private val SEGMENT_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$")
    private val SESSION_ID = Pattern.compile("^ts_[0-9]+_[0-9a-f]{12}$")

    /**
 * Whether a string is a well-formed native session identifier.
 *
 * [SESSION_ID] is object-private, so the bind site ([RnWebViewBinding])
 * would otherwise have had to carry a SECOND copy of the pattern — and a
 * duplicated identity grammar is the one-fact-two-homes defect this
 * programme keeps paying for, with the copy that drifts always being the
 * one nobody is looking at. [decide] still asks this question itself; this
 * is the same question, not a second answer.
 */
    @JvmStatic
    fun isValidSessionId(s: String): Boolean = SESSION_ID.matcher(s).matches()

    /** scheme://host[:port] — nothing else. */
    private val ORIGIN = Pattern.compile("^https?://[a-z0-9.-]+(:[0-9]{1,5})?$")
    private val LOCAL = Pattern.compile("^https?://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?$")

    /**
 * Normalize one origin, or null when it is unusable. Fails CLOSED on every
 * ambiguity, because every guess here is a WIDENING.
 *
 * `*`, `null` and `about:blank` are the opaque origins: admitting one
 * admits ALL of them at once, since they are indistinguishable from each
 * other. `http://` is acceptable only for local development.
 */
    @JvmStatic
    fun normalizeOrigin(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        // A DOUBLED GUARD, AND NEITHER HALF IS LOAD-BEARING ON ITS OWN FOR
        // THESE THREE VALUES. Measured both directions on the JS twin, not
        // reasoned:
        //
        // this line removed -> `*` / `null` / `about:blank` still refused
        // ([ORIGIN] rejects them: none is scheme://host)
        // [ORIGIN] removed -> those three are still refused by this line,
        // while a PATH-bearing origin
        // (`https://host/docs`) stops being refused —
        // which is what the pattern's control catches
        //
        // So removing EITHER half shows no failure for the opaque triple,
        // which is exactly what makes a redundant pair dangerous to tidy up.
        // Keep both: a future widening of [ORIGIN] (another scheme, a looser
        // host set) would otherwise silently admit an opaque origin, and
        // admitting ONE admits ALL of them — a sandboxed, `data:` or `about:`
        // document reports `null` and they are indistinguishable from each
        // other. Do not delete this on the strength of a control passing.
        if (s == "*" || s.equals("null", true) || s.equals("about:blank", true)) return null
        val lower = s.lowercase(Locale.ROOT)
        if (!ORIGIN.matcher(lower).matches()) return null
        if (lower.startsWith("http://") && !LOCAL.matcher(lower).matches()) return null
        return lower
    }

    /**
 * Normalize a declared allowlist, or null when it is unusable.
 *
 * A SINGLE UNUSABLE ENTRY DISABLES THE WHOLE POLICY rather than
 * admitting the entries that happened to parse — a typo must not silently
 * shrink the intended set into something the operator never reviewed. An
 * EMPTY declared list is a policy that admits nothing, which is different
 * from no policy at all and is reported differently.
 */
    @JvmStatic
    fun normalizePolicy(origins: List<String>?): List<String>? {
        if (origins == null) return null
        val out = LinkedHashSet<String>()
        for (o in origins) {
            val n = normalizeOrigin(o) ?: return null
            out.add(n)
        }
        return out.toList()
    }

    private fun refuse(code: String) =
        Decision(Outcome.REFUSE, code, BLOCKED_THIRD_PARTY, null)

    /**
 * Decide ONE inbound bridge message.
 *
 * [expectedSegmentId] and [nativeSessionId] are held by the NATIVE side.
 * [authenticatedOrigin] is the origin the BROWSER attributed to the calling
 * frame, available only on a channel that can supply one; null means the
 * page's own claim is all there is, and the decision records that
 * difference in [Segment.originTrust] rather than hiding it.
 */
    @JvmStatic
    @JvmOverloads
    fun decide(
        capability: Capability,
        /** The host app's declared allowlist. Null = none declared. */
        policy: List<String>?,
        kind: String?,
        fields: Map<String, Any?>,
        expectedSegmentId: String,
        nativeSessionId: String,
        authenticatedOrigin: String? = null,
    ): Decision {
        // (1) CAPABILITY FIRST. An unsupported provider yields no segment row
        // at all, so nothing below this line can produce one — and the reason
        // names the PROVIDER rather than whatever the page happened to send.
        if (capability != Capability.SUPPORTED) {
            return Decision(Outcome.ABSENT, capability.wire, BLOCKED_UNSUPPORTED, null)
        }

        // (2) The closed message vocabulary.
        if (kind != KIND_HELLO && kind != KIND_POSTURE) return refuse("unknown_message_kind")

        // (3) Our own session identity must be well-formed before we attach
        // anything to it.
        if (!isValidSessionId(nativeSessionId)) return refuse("native_session_invalid")

        // (4) ORIGIN ADMISSION, before anything the frame claims about the
        // session. See the ordering note in the class docs.
        val admitted = normalizePolicy(policy) ?: return refuse("origin_policy_absent")
        val declared = fields["frameOrigin"] as? String
        val origin: String
        val trust: OriginTrust
        if (authenticatedOrigin != null) {
            val auth = normalizeOrigin(authenticatedOrigin) ?: return refuse("origin_malformed")
            // A frame that names an origin OTHER than the one the browser
            // attributed to it is lying. Only this channel can catch that.
            if (declared != null && normalizeOrigin(declared) != auth) {
                return refuse("origin_spoofed")
            }
            origin = auth
            trust = OriginTrust.AUTHENTICATED
        } else {
            if (declared == null) return refuse("origin_missing")
            origin = normalizeOrigin(declared) ?: return refuse("origin_malformed")
            trust = OriginTrust.DECLARED
        }
        if (origin !in admitted) return refuse("origin_not_admitted")

        val role = fields["frameRole"] as? String ?: return refuse("frame_role_missing")
        if (role !in FRAME_ROLES) return refuse("frame_role_invalid")

        // (5) SEGMENT IDENTITY. The page may only ECHO the id the native side
        // minted; it can never choose one.
        val claimed = fields["segmentId"] as? String ?: return refuse("segment_id_missing")
        if (!SEGMENT_ID.matcher(claimed).matches()) return refuse("segment_id_invalid")
        if (claimed != expectedSegmentId) return refuse("segment_id_mismatch")

        // (6) FIELD VOCABULARY. Refused, not filtered — a dropped unknown key
        // is a surface we did not notice growing.
        val unknown = fields.keys - ALLOWED_FIELDS
        if (unknown.isNotEmpty()) {
            return refuse("unknown_fields:" + unknown.sorted().joinToString(","))
        }

        // (7) ADMIT. An absent or unrecognised level resolves to `unknown` —
        // the weakest, which `isShareable` refuses — never to a convenient
        // default of strict. A protection flag is true only when the page says
        // exactly true; anything else is the unprotected answer, which the
        // session-wide worst-posture resolution then has to account for.
        val lvl = fields["level"] as? String
        return Decision(
            Outcome.ADMIT,
            "admitted",
            BLOCKED_THIRD_PARTY,
            Segment(
                segmentId = claimed,
                nativeSessionId = nativeSessionId,
                level = if (lvl in PRIVACY_LEVELS) lvl!! else "unknown",
                maskText = fields["maskText"] == true,
                maskInputs = fields["maskInputs"] == true,
                browserPrivacyStatus = (fields["browserPrivacyStatus"] as? String)
                    ?.takeIf { it in BROWSER_PRIVACY_STATUSES },
                frameRole = role,
                origin = origin,
                originTrust = trust,
            ),
        )
    }
}

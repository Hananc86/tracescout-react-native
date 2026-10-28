package com.tracescout.rn

/**
 * Privacy resolution for the React Native tree.
 *
 * Contract the specification: privacy is monotonic — the strongest restriction wins, and the
 * NATIVE surface always overrides the JS declaration. There is deliberately no
 * direction in which a JS declaration can loosen a native restriction.
 */
enum class Treatment {
    /** Serialised with content. */
    ALLOWED,

    /** Serialised as a redaction: no text, only `textMasked` + `textLength`. */
    MASKED,

    /** Typed placeholder only. No descendants, and none are INSPECTED. */
    BLOCKED,
    ;

    companion object {
        /**
 * Monotonic join. Order is the documentation: ALLOWED < MASKED < BLOCKED.
 * Used for JS-vs-native and for ancestor-vs-self.
 */
        fun strongest(a: Treatment, b: Treatment): Treatment =
            if (a.ordinal >= b.ordinal) a else b

        /**
 * A value that does not parse is BLOCKED, never ALLOWED.
 *
 * An unreadable declaration is not evidence of a permissive one — the
 * same reasoning that makes an unreadable lease owner keep the
 * staleness rule rather than be reclaimed.
 */
        fun parse(raw: String?): Treatment = when (raw?.trim()?.lowercase()) {
            "allowed" -> ALLOWED
            "masked" -> MASKED
            "blocked", "secure" -> BLOCKED
            else -> BLOCKED
        }
    }
}

/** Why a subtree is blocked. Typed so the renderer draws a labelled placeholder. */
enum class BlockedReason {
    JS_DECLARED,
    SECURE_TEXT_ENTRY,
    NATIVE_SECURE_SURFACE,
    CAMERA_PREVIEW,
    THIRD_PARTY_WEBVIEW,
    SCREEN_NEVER_RECORD,
}

/**
 * Node bounds in density-independent units in the window's coordinate space.
 * A rectangle carries no content; the renderer needs it to draw a correctly
 * sized wireframe or a labelled placeholder for a blocked node.
 */
data class NodeBounds(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * One node as the JS layer and the native host each describe it.
 *
 * `nativeTreatment` is what the platform reports about the rendered surface
 * (e.g. `secureTextEntry` on a TextInput, a camera preview, a secure window).
 * It is a PLATFORM fact, never a host-app hint.
 */
data class RawNode(
    val componentType: String,
    val siblingOrdinal: Int,
    val jsTreatment: Treatment = Treatment.MASKED,
    val nativeTreatment: Treatment? = null,
    val nativeBlockedReason: BlockedReason? = null,
    val replayId: String? = null,
    val textLength: Int? = null,
    val bounds: NodeBounds? = null,
    val children: List<RawNode> = emptyList(),
    /**
 * Is this node the WEB CONTENT SURFACE itself — a `WebView` / `WKWebView`?
 *
 * IT IS A SURFACE FACT, NOT A TREATMENT, AND THAT IS THE WHOLE POINT.
 * The obvious marker for "this is the web view" is
 * `nativeBlockedReason == THIRD_PARTY_WEBVIEW`, and it is INCOMPLETE:
 * Android always blocks web content, but iOS under the Lab visibility
 * exemption returns an ALLOWED opaque leaf with `nativeBlockedReason =
 * nil` (`HierarchySnapshotter.rawNode`, the "AN ALLOWED WEB VIEW IS AN
 * OPAQUE LEAF" branch). So on iOS the one node the node-level segment link
 * belongs to carried NOTHING identifying it as web content, and keying the
 * link on the blocked reason would have silently skipped exactly the
 * configuration the Lab runs.
 *
 * AND THE ROLE IS NOT AN ALTERNATIVE. `componentType` is a per-platform
 * class name (`RNCWebView`, `RNCWKWebView`, …); the identity rules forbid
 * deriving evidence from one, and a an earlier change negative control (`c06`) pins
 * exactly that mistake on the Viewer side — "the WebView marker becomes the
 * component TYPE (a per-platform guess)".
 *
 * Both walkers already compute this through their ONE `isWebContent` home
 * and were DISCARDING it in the allowed case; this carries it.
 *
 * LAST AND DEFAULTED, like `webViewSegmentId` on `RnEnvelope.envelope`
 * and for the same reason: inserting it in the middle renumbers every
 * positional call site (65 of them construct a `RawNode` positionally),
 * and a caller that has not been taught about web content must get the
 * pre-existing answer — not web content — never a silently widened one.
 */
    val webContent: Boolean = false,
) {
    /** Contract the specification: the strongest of the two wins. */
    val resolved: Treatment =
        if (nativeTreatment == null) jsTreatment
        else Treatment.strongest(jsTreatment, nativeTreatment)
}

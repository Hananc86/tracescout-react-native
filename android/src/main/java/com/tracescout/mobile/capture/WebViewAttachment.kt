package com.tracescout.mobile.capture

import android.webkit.WebView
import java.util.WeakHashMap

/**
 * The opt-in binding between ONE attached `WebView` instance and the
 * native-minted segment running inside it — the identity a WebView-landed
 * interaction is attributed to (S5, an earlier change).
 *
 * ## Why this exists at all
 *
 * [WebViewBridge] already admits a segment and folds its posture, but it
 * learns the segment from a *message*: it never sees which `View` the page is
 * rendered in. So the SDK knew a segment existed and could not say that THIS
 * touch landed in it. `InteractionTargetResolver` has always computed the
 * hit-test fact (`t is WebView || hasWebViewAncestor(t)`) and discarded it.
 * This registry is the missing third of the join; nothing else changes.
 *
 * ## It is a LOOKUP, never a channel
 *
 * No method here is reachable from page content, injects a script, evaluates
 * JavaScript, reads a URL, a title, a cookie or a DOM. The host attaches, the
 * host detaches, and the only thing that can be read back is an opaque segment
 * id the native side minted itself. An un-attached WebView yields nothing, for
 * ever — that is the default and it is empty.
 *
 * ## Fail-closed, and one segment is one view
 *
 * [decide] is pure so every refusal is JVM-testable. A refusal stores NOTHING:
 * an unsupported WebView provider, an origin the policy does not admit, a
 * segment whose `nativeSessionId` is not the live session, or a segment id
 * already bound to a different view are each a typed `Refuse`. The last one
 * matters: a renderer models a segment as one span in one view, so letting one
 * id name two views would describe a session that never happened.
 *
 * WEAK KEYS. An attachment never keeps a `WebView` — and therefore never
 * keeps its Activity — alive. A host that forgets [detach] leaks nothing; it
 * only keeps a stale binding reachable until the view is collected, which is
 * why [detach] on navigation and on destroy is still part of the contract.
 */
object WebViewAttachment {

    /**
 * What rides the envelope of an interaction that landed in this view.
 *
 * The field set IS the boundary: an opaque segment id and the browser's own
 * coarse privacy status, both already bounded by [WebViewBridge]. There is
 * nowhere to put an origin, a URL, a title, a frame name or a selector.
 */
    data class Carrier(
        val segmentId: String,
        val browserPrivacyStatus: String?,
    )

    sealed interface Decision {
        data class Admit(val carrier: Carrier) : Decision
        data class Refuse(val reason: String) : Decision
    }

    /** Reasons, as a closed set, so a caller can assert on them. */
    const val REFUSED_UNSUPPORTED = "webview_capture_unsupported"
    const val REFUSED_ORIGIN = "origin_not_admitted"
    const val REFUSED_NO_SESSION = "no_live_session"
    const val REFUSED_SESSION_MISMATCH = "segment_session_mismatch"
    const val REFUSED_SEGMENT_BOUND = "segment_already_bound"

    /**
 * The whole admission rule, with no Android in it.
 *
 * DEFENCE IN DEPTH, not the primary gate. The primary gate is
 * injection-time admission (`WebViewOriginPolicy.originRules` handed to
 * `addWebMessageListener`/`addDocumentStartJavaScript`, so an unadmitted
 * frame never obtains the object) plus `WebViewBridge.handle`, which has
 * already validated this segment's origin, role and claimed id. Re-asking
 * here costs nothing and means a host that hand-builds a `Segment` cannot
 * attach one the policy would have refused.
 */
    fun decide(
        capabilitySupported: Boolean,
        policy: WebViewOriginPolicy,
        segment: WebViewBridge.Segment,
        liveSessionId: String?,
        segmentBoundElsewhere: Boolean,
    ): Decision {
        if (!capabilitySupported) return Decision.Refuse(REFUSED_UNSUPPORTED)
        if (!policy.isAdmitted(segment.origin)) return Decision.Refuse(REFUSED_ORIGIN)
        if (liveSessionId == null) return Decision.Refuse(REFUSED_NO_SESSION)
        if (segment.nativeSessionId != liveSessionId) return Decision.Refuse(REFUSED_SESSION_MISMATCH)
        if (segmentBoundElsewhere) return Decision.Refuse(REFUSED_SEGMENT_BOUND)
        return Decision.Admit(Carrier(segment.segmentId, segment.browserPrivacyStatus))
    }

    // WEAK KEYS — see the class comment. Guarded by `lock`, not
    // `synchronized(this)`: a host may attach from the UI thread while the
    // capture seam reads from the same thread, and the read must be cheap.
    private val lock = Any()
    private val bound = WeakHashMap<WebView, Carrier>()

    /**
 * Bind [webView] to [segment] for the rest of this page's life.
 *
 * Idempotent for the same (view, segment): re-attaching the same pair is an
 * `Admit` and changes nothing. Attaching a DIFFERENT segment to a view that
 * already has one replaces it — a navigation inside the same view mints a
 * new segment, and that is the supported way to express it.
 */
    fun attach(
        webView: WebView,
        segment: WebViewBridge.Segment,
        policy: WebViewOriginPolicy,
        liveSessionId: String?,
        capabilitySupported: Boolean = true,
    ): Decision = synchronized(lock) {
        val boundElsewhere = bound.entries.any { (v, c) ->
            c.segmentId == segment.segmentId && v !== webView
        }
        val d = decide(capabilitySupported, policy, segment, liveSessionId, boundElsewhere)
        if (d is Decision.Admit) bound[webView] = d.carrier
        d
    }

    /** Forget one view's binding. Call on navigation and on destroy. */
    fun detach(webView: WebView) { synchronized(lock) { bound.remove(webView) } }

    /** Forget every binding — session end, or a host turning capture off. */
    fun detachAll() { synchronized(lock) { bound.clear() } }

    /**
 * The carrier for a view, or null.
 *
 * This is the ONLY read path, and it answers for an attached view only.
 * A null view, an un-attached view and a detached view are one answer.
 */
    fun carrierFor(webView: WebView?): Carrier? {
        val v = webView ?: return null
        return synchronized(lock) { bound[v] }
    }

    /** Host-visible "is anything attached" counter. Never names a view. */
    val attachedCount: Int get() = synchronized(lock) { bound.size }
}

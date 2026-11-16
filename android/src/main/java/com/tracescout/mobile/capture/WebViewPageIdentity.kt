package com.tracescout.mobile.capture

/**
 * The S leg of the S5 WebView-network join on Android Native (Model B, N + S):
 * telling an ADMITTED document which native-minted WebView segment it is
 * running in, so the Browser SDK inside it can stamp that id on its own
 * request metadata.
 *
 * ## Why this exists, and why it is NOT a network producer
 *
 * The in-page requests of a WebView are already captured, with completion and
 * failure metadata, by the Browser SDK running in that page — `duration`,
 * `status`, outcome and sizes, all inside the existing browser v1 contract.
 * What was missing is the pointer that lets a consumer place those requests on
 * the NATIVE timeline.
 *
 * `mobile_network_request.sourceLayer` declares the value `webview` and says of
 * it, verbatim: *"`webview` is declared so the vocabulary is stable, and is NOT
 * produced in v1 — in-page traffic is already covered by the browser SDK and
 * capturing it here would double-count."* So the native side must NOT emit a
 * webview-layer network event; it must supply S and let the consumer join.
 *
 * Two independent facts make that the only available shape on Android anyway:
 *
 * - `WebView` has ONE client slot (`setWebViewClient`), so a native observer
 * would DISPLACE the host app's own client; and `shouldInterceptRequest`
 * returning non-null REPLACES the response. Observing this way means
 * altering the page's networking, which this SDK does not do.
 * - `WebViewClient` exposes no per-request SUCCESS completion callback at all
 * (`onReceivedError` / `onReceivedHttpError` are failures; `onLoadResource`
 * and `shouldInterceptRequest` are starts). `durationMs` and
 * `outcome` are REQUIRED by the contract, so a native hook could only ever
 * describe failures — and a producer that is silent on every success would
 * assert that no requests occurred. `androidx.webkit` adds no
 * network-observation API; its request classes are compat wrappers of those
 * same callbacks.
 *
 * ## an earlier change minted a segment the page was never told about
 *
 * [WebViewBridge] mints the id natively and refuses any hello that claims a
 * different one, and [WebViewAttachment] binds it to one view. But the Browser
 * SDK reads the join key from exactly one place — an own data property
 * `segmentId` on `window.`[IDENTITY_GLOBAL] — and nothing in this SDK ever
 * wrote it. The only existing injection lived in the Android fixture, under a
 * DIFFERENT global, so the consumer read `undefined` and stamped nothing.
 *
 * ## It carries the segment id and NOTHING else
 *
 * No session id, no project id, no posture, no ingest key, no origin. Model B
 * correlates N and S natively precisely so the page never has to be told N: a
 * page that knew the native session could claim one. This is deliberately
 * stricter than the Android fixture's own `__TS_NATIVE__` handshake payload,
 * which also carries the project id because the fixture's pages call
 * `TraceScout.init` themselves.
 *
 * ## It is a PLAN, not an injection
 *
 * This object returns the script text and the origin rules; the host performs
 * the `WebViewCompat.addDocumentStartJavaScript` call. That keeps the SDK free
 * of an `androidx.webkit` dependency (it has none, by construction — even
 * [WebViewCapability] names the required features as strings) and leaves the
 * host app owning its own `WebView`, exactly as [WebViewAttachment] does.
 *
 * A STAMP IS A CLAIM, VERIFIED BY THE CONSUMER. Page script can write this
 * global itself. What makes it a correlation is the consumer's N + S check —
 * the id must be one the native session's own segment registry lists, which
 * only happens when the native side admitted a hello carrying it. An id nobody
 * minted joins nothing. `Object.freeze` is not a security boundary; it stops an
 * accidental overwrite by the page's own code from looking like a native fault.
 *
 * ANDROID INJECTS INTO EVERY ADMITTED FRAME, not only the main one.
 * `addDocumentStartJavaScript` takes origin rules and has no main-frame-only
 * switch, so an admitted subframe of the same `WebView` also learns the id.
 * That is correct for this model — one view is one segment — and it is the same
 * surface the React Native producer uses on Android. It is recorded here
 * because the consumer's own documentation describes a main-frame-only rule,
 * which is accurate for iOS (a `location.origin`-gated `WKUserScript`) and not
 * for either Android producer.
 */
object WebViewPageIdentity {

    /**
 * The page-world global the Browser SDK reads.
 *
 * PINNED TO THE CONSUMER, NOT CHOSEN HERE. It must equal
 * `RN_IDENTITY_GLOBAL` in `browser-sdk/src/core/webViewSegment.js`, which is
 * the only name `readHostWebViewSegmentId` looks at, and which is already
 * published. `WebViewPageIdentityContractTest` reads that file and asserts
 * the two agree, so this cannot drift into a name no consumer reads.
 *
 * The `Rn` in the name is historical — it was introduced by the React
 * Native producer — and is shared rather than forked deliberately: a second
 * global would need a change to an already-published consumer before any
 * native host could join at all.
 */
    const val IDENTITY_GLOBAL = "__tsRnNative"

    /** What the host should install, or why it must not. */
    sealed interface Plan {
        /**
 * Install [script] at document start, restricted to [originRules].
 *
 * [originRules] is a PROJECTION of the policy's admitted origins, never
 * a second parser — a second parser is how the injection rules and the
 * admission decision start disagreeing about one allowlist.
 */
        data class Inject(val script: String, val originRules: Set<String>) : Plan

        /** Install nothing, with a typed reason, so a refusal is observable. */
        data class Refuse(val reason: String) : Plan
    }

    /** Reasons, as a closed set, so a caller can assert on them. */
    const val REFUSED_UNSUPPORTED = "webview_capture_unsupported"
    const val REFUSED_NO_ADMITTED_ORIGINS = "no_admitted_origins"
    const val REFUSED_SEGMENT_INVALID = "segment_id_invalid"

    /**
 * Decide one WebView's page identity.
 *
 * THE REFUSAL ORDER MIRRORS [WebViewAttachment.decide]'S — capability,
 * then the origin material, then the segment material. Two sites answering
 * the same questions in different orders is two different stories about one
 * fault.
 *
 * Fail-closed at every step: an unsupported provider has no
 * `addDocumentStartJavaScript` to call; a policy that admits nothing has no
 * frame to inject into, and handing an empty rule set to the platform would
 * either throw or silently inject nowhere, neither of which is a decision a
 * caller could observe; and an id outside [WebViewBridge.isSegmentId] is one
 * the consumer would refuse to stamp, so emitting it is noise, never a join.
 */
    @JvmStatic
    fun plan(
        capabilitySupported: Boolean,
        policy: WebViewOriginPolicy,
        segmentId: String?,
    ): Plan {
        if (!capabilitySupported) return Plan.Refuse(REFUSED_UNSUPPORTED)
        val rules = policy.originRules()
        if (rules.isEmpty()) return Plan.Refuse(REFUSED_NO_ADMITTED_ORIGINS)
        if (!WebViewBridge.isSegmentId(segmentId)) return Plan.Refuse(REFUSED_SEGMENT_INVALID)
        return Plan.Inject(script(segmentId!!), rules)
    }

    /**
 * The document-start script, for a segment id already validated by
 * [WebViewBridge.isSegmentId].
 *
 * BUILT BY CONCATENATION, NOT BY A JSON LIBRARY — and that is safe for a
 * measured reason, not a hopeful one. The segment-id alphabet is
 * `[A-Za-z0-9_-]` only, so no quote, backslash, angle bracket, whitespace or
 * control character can reach the interpolation. The caller is the gate;
 * this function restates the check rather than trusting it.
 *
 * The output is byte-identical in shape to the React Native producer's, so
 * the consumer reads one text from every platform.
 */
    @JvmStatic
    fun script(segmentId: String): String {
        require(WebViewBridge.isSegmentId(segmentId)) { REFUSED_SEGMENT_INVALID }
        return "window.$IDENTITY_GLOBAL=Object.freeze({segmentId:\"$segmentId\"});"
    }
}

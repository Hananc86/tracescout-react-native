package com.tracescout.rn.bridge

import com.tracescout.rn.RnEnvelope

/**
 * an earlier change — the SCREEN-level WebView carrier gate, the Kotlin
 * twin of iOS `RnBridge.screenCarrierFor`.
 *
 * A navigation / screen-snapshot / view-hierarchy event carries the host's
 * segment ONLY while the screen it describes holds web content, as observed by
 * that screen's own hierarchy capture (`HierarchyResult.webViewSurfaceCount`,
 * computed through the ONE `isWebContent` home). `null` = never observed, which
 * is NOT web content. Deliberately separate from the interaction gate
 * (`RnInteractionCapture.carrierFor`): a tap is decided by where the finger
 * landed, a screen by what it contains. Both answer with a PROVIDER that is
 * still read at emit time, so nothing here mints or caches a carrier.
 */
fun screenCarrierFor(
    screenHoldsWebContent: Boolean?,
    webViewRef: (() -> RnEnvelope.WebViewRef?)?,
): (() -> RnEnvelope.WebViewRef?)? = if (screenHoldsWebContent == true) webViewRef else null

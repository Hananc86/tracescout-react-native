package com.tracescout.mobile.rn

import android.content.Context
import com.tracescout.rn.RnWebViewBinding
import com.tracescout.rn.RnWebViewCorrelation
import com.tracescout.rn.RnWebViewSegment
import com.tracescout.rn.RnWebViewTrustLifetime

/**
 * G3 — THE ANDROID PRODUCTION CALL SITE for the host-declared WebView origin
 * allowlist, and the Android counterpart of Swift's
 * `RnWebViewBinding.bindFromConfiguration`.
 *
 * It is deliberately THIN. Every rule lives one layer down, in `:rn-core`,
 * where it is a pure decision provable in a plain JVM test:
 *
 * ```
 * RnWebViewOriginPolicy.admit may a HOST DECLARE this entry?
 * RnWebViewBinding.decide capability -> session -> origin, in that order
 * RnWebViewTrustLifetime.decide is an admission still entitled to its trust?
 * RnWebViewSegment.decide the shared Model B message decision
 * ```
 *
 * What this file adds is the two things that genuinely need Android: reading
 * the NATIVE-OWNED configuration, and re-reading it LIVE so a revocation can
 * reach a running session.
 *
 * ## IT DOES NOT MEASURE THE WEBVIEW PREFLIGHT, AND THAT IS A MEASURED
 * ## CONSTRAINT RATHER THAN AN OMISSION
 *
 * `androidx.webkit` appears in NEITHER `:sdk` NOR `:rn` — it is declared by the
 * fixture (a host app) alone, and `WebViewCapability.evaluate` is a PURE
 * function taking the provider package, the provider version and a
 * `featureSupported` callback. The host performs the Android calls and hands
 * the facts in. That is the same reasoning the iOS side applies to WebKit (the
 * hierarchy walker identifies web content by CLASS NAME so that linking WebKit
 * is never forced on a host app): an app that embeds no WebView must not be
 * made to carry the WebView support library because the SDK wanted to ask a
 * question about it.
 *
 * So the capability arrives as a parameter. This is a DELIBERATE divergence
 * from iOS's `RnWebViewCapability.measured`, where the web engine ships with
 * the OS and there is nothing optional to link.
 *
 * ## NO JS PATH
 *
 * This module carries no `com.facebook.react` dependency at all, so nothing
 * here is JS-callable. The React-facing adapter is a thin delegate in the RN
 * package's own `android/` folder and owns no rule; an origin allowlist is the
 * definition of a native-owned fact, and a policy a page's own runtime could
 * supply would be a policy the page could widen.
 */
object RnWebViewHost {

    /**
 * Translate the native preflight's verdict into the SHARED capability
 * vocabulary.
 *
 * IT TAKES THE WIRE STRING, NOT `WebViewCapability.Status`. `:rn`
 * depends on `:sdk` with `implementation` scope, so a `:sdk` type in a
 * public signature here would be a type a consumer of this module cannot
 * name. The wire string is the vocabulary that is genuinely shared — it is
 * what [RnWebViewSegment.Capability.from] consumes, what the protocol
 * defines, and what crosses platforms.
 *
 * THE TWO ENUMS AGREE BY CONSTRUCTION, AND THE AGREEMENT IS PINNED
 * RATHER THAN TRUSTED. `WebViewCapability.Status.<X>.name.lowercase` IS
 * `RnWebViewSegment.Capability.<Y>.wire` for every value, so this is ONE
 * derivation and not a second mapping table that could drift. A RULE over
 * both enums (`RnWebViewHostTest`) asserts that every `Status` maps to a
 * distinct capability and that none of them lands on the fallback — so a
 * rename on either side fails loudly instead of silently degrading every
 * device to `PROVIDER_UNAVAILABLE`.
 *
 * ANYTHING UNRECOGNISED IS `PROVIDER_UNAVAILABLE`, never SUPPORTED —
 * [RnWebViewSegment.Capability.from]'s own rule. An unreadable verdict is
 * not evidence of a good one.
 */
    @JvmStatic
    fun capabilityOfPreflight(wire: String?): RnWebViewSegment.Capability =
        RnWebViewSegment.Capability.from(wire)

    /**
 * Bind one web view's correlation from the SDK's own persisted native
 * configuration.
 *
 * THE ALLOWLIST IS READ FROM [RnSecureConfig], THE NATIVE-OWNED
 * CONFIGURATION HOME — never from an argument a JS caller could reach and
 * never from the React props. This is the "native-owned SDK configuration
 * at initialization" half of the contract, and the read is here so there is
 * exactly one of it.
 *
 * THE POLICY IS CAPTURED AT THE BIND and the correlation carries it, so
 * nothing a later caller does can widen the policy a live session admits
 * under. Revocation is the separate question [revalidate] answers.
 */
    @JvmStatic
    fun bindFromConfiguration(
        ctx: Context,
        nativeSessionId: String,
        capability: RnWebViewSegment.Capability,
    ): RnWebViewBinding.Result = RnWebViewBinding.bind(
        nativeSessionId = nativeSessionId,
        declared = RnSecureConfig.webViewOriginAllowlist(ctx),
        capability = capability,
    )

    /**
 * Revalidate an admission against the LIVE configuration and the web view's
 * current origin.
 *
 * THE LIVE POLICY IS RE-READ HERE, NOT CACHED. That read is the whole
 * mechanism by which a revocation reaches a running session; a cached copy
 * would make this unable to observe the one event it exists for.
 *
 * THE CURRENT ORIGIN IS SUPPLIED BY THE CALLER, for the same reason the
 * capability is: reading it means touching a `WebView`, and this module
 * carries no WebView dependency. A caller that cannot establish it passes
 * null, which DROPS — "I cannot establish that this is still current" IS
 * stale trust, and the honest outcome of an unprovable correlation is no
 * segment row at all.
 */
    @JvmStatic
    fun revalidate(
        ctx: Context,
        correlation: RnWebViewCorrelation,
        currentOrigin: String?,
    ): RnWebViewTrustLifetime.Verdict = correlation.revalidate(
        currentOrigin = currentOrigin,
        livePolicy = RnSecureConfig.webViewOriginAllowlist(ctx),
    )
}

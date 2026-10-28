package com.tracescout.rn

/**
 * G2-ATTACH — THE WEBVIEW CAPABILITY PREFLIGHT, AS A PURE FUNCTION OVER FACTS
 * THE HOST APP MEASURES.
 *
 * an earlier change made the capability a PARAMETER of the Android bind and said why: an
 * app that embeds no WebView must not be made to carry `androidx.webkit`
 * because the SDK wanted to ask a question about it. an earlier change then measured the
 * consequence — the existing preflight (`WebViewCapability.evaluate` in
 * `:sdk`) is STRUCTURALLY UNREACHABLE from the RN host app, which depends only
 * on `rn` / `rn-bridge` / `rn-core`, so every RN session recorded an honest
 * `webview_provider_unavailable` refusal and no correlation was possible.
 *
 * This closes that gap WITHOUT moving the boundary: the host performs the two
 * `androidx.webkit` reads it alone can perform — the provider package/version
 * and one `isFeatureSupported` answer per feature the bridge actually calls —
 * and hands the FACTS in. Everything that is a DECISION happens here, in a
 * plain `kotlin("jvm")` module with no Android, no WebView and no support
 * library, so every branch is provable in an ordinary JVM test.
 *
 * ## IT IS A TWIN OF `WebViewCapability.evaluate`, NOT A SECOND OPINION
 *
 * The native Android SDK already owns this evaluation for its own fixture.
 * This is the same rule, in the one place the RN host app can reach, and the
 * agreement is PINNED rather than trusted: [WIRE_SUPPORTED] and its three
 * siblings are asserted byte-equal to [RnWebViewSegment.Capability]'s own
 * `wire` values, every value is asserted reachable, and none may land on
 * `Capability.from`'s fallback. A rename on either side fails loudly instead
 * of silently degrading every device to `provider_unavailable`.
 *
 * ## THE ORDER IS LOAD-BEARING AND IS COPIED DELIBERATELY
 *
 * ```
 * absent provider -> provider_unavailable
 * unparseable version -> provider_unavailable (unreadable is not good)
 * version below floor -> provider_unsupported
 * missing feature -> feature_unsupported
 * otherwise -> capture_supported
 * ```
 *
 * Checking features FIRST would report `feature_unsupported` for a 2018
 * engine, which describes the symptom and sends the reader after a feature
 * flag instead of an engine update. Android System WebView is a separately
 * updateable system component — the API-26 emulator image ships WebView 69 —
 * so on this platform the ENGINE really is the common case, and
 * `RnWebViewBinding.decide` orders its own refusals (capability → session →
 * origin) for the same reason.
 */
object RnWebViewPreflight {

    /**
 * The MEASURED boundary the full origin matrix actually passed on, carried
 * across from the native SDK's `WebViewCapability.MIN_VALIDATED_WEBVIEW_VERSION`.
 *
 * IT IS NOT DERIVED FROM THE FEATURE FLOOR. `WEB_MESSAGE_LISTENER`
 * landed in WebView 88, so 88 is what a release note would suggest — and
 * nothing has validated 88.123 end to end on this producer. Lowering this
 * constant means RUNNING the matrix, not reading a release date.
 */
    const val MIN_VALIDATED_WEBVIEW_VERSION = 124

    /**
 * Every `androidx.webkit` feature the RN attach actually CALLS.
 *
 * THE NAMES ARE THE SUPPORT LIBRARY'S OWN CONSTANTS, AS STRINGS. This
 * module may not name `WebViewFeature`, so the host app resolves
 * `WebViewFeature.WEB_MESSAGE_LISTENER` / `DOCUMENT_START_SCRIPT` and
 * reports the answers keyed by these strings. Android's own reference
 * states that invoking a `WebViewCompat` API whose feature check is false
 * is unsafe, so this list must stay exactly the set the attach invokes —
 * not a wishlist, and not a shortened set that would let an unsupported
 * call through.
 */
    val REQUIRED_FEATURES = listOf("WEB_MESSAGE_LISTENER", "DOCUMENT_START_SCRIPT")

    const val WIRE_SUPPORTED = "webview_capture_supported"
    const val WIRE_PROVIDER_UNSUPPORTED = "webview_provider_unsupported"
    const val WIRE_FEATURE_UNSUPPORTED = "webview_feature_unsupported"
    const val WIRE_PROVIDER_UNAVAILABLE = "webview_provider_unavailable"

    /**
 * The facts the host app measures. Deliberately only four, and every one of
 * them is something `androidx.webkit` answers directly.
 *
 * `featureSupported` IS A MAP, NOT TWO BOOLEANS, so [REQUIRED_FEATURES]
 * can grow without changing this type — and an ABSENT key is NOT `false`.
 * A feature the host did not report is a feature whose state we do not
 * know, and an unknown answer on a capability gate is refused for the same
 * reason an unreadable version is: an unreadable verdict is not evidence of
 * a good one.
 */
    data class HostFacts(
        /** `WebViewCompat.getCurrentWebViewPackage(ctx)?.packageName`. */
        val providerPackage: String?,
        /** `…?.versionName`, verbatim — never parsed by the host. */
        val providerVersion: String?,
        /** One `WebViewFeature.isFeatureSupported(X)` answer per required name. */
        val featureSupported: Map<String, Boolean>,
    )

    /** The verdict, carrying the wire string AND why, for an observable refusal. */
    data class Verdict(
        val wire: String,
        /** The measured major version, when one could be read. */
        val majorVersion: Int? = null,
        /** The required feature that was missing or unreported, when that is the fact. */
        val missingFeature: String? = null,
    ) {
        val capability: RnWebViewSegment.Capability
            get() = RnWebViewSegment.Capability.from(wire)
    }

    /**
 * Evaluate the preflight.
 *
 * NOTHING HERE CAN RETURN `capture_supported` ON A DOUBT. Every early
 * return is a refusal; the supported answer is reachable only after a
 * present provider, a parseable version at or above the validated floor,
 * and an explicit `true` for every required feature.
 */
    @JvmStatic
    fun evaluate(facts: HostFacts): Verdict {
        // (1) THE PROVIDER MUST EXIST. `getCurrentWebViewPackage` returns null
        // when no provider is installed or selected, which is a different fact
        // from "too old" and gets its own answer.
        val pkg = facts.providerPackage?.trim()
        if (pkg.isNullOrEmpty()) return Verdict(WIRE_PROVIDER_UNAVAILABLE)

        // (2) THE VERSION MUST BE READABLE. A provider whose version we cannot
        // parse is `unavailable`, never `supported` — the same rule
        // `Capability.from` applies to an unrecognised wire string.
        val major = majorOf(facts.providerVersion)
            ?: return Verdict(WIRE_PROVIDER_UNAVAILABLE)

        // (3) THE VERSION MUST CLEAR THE MEASURED FLOOR, before any feature is
        // consulted — see the ordering note on the object.
        if (major < MIN_VALIDATED_WEBVIEW_VERSION) {
            return Verdict(WIRE_PROVIDER_UNSUPPORTED, majorVersion = major)
        }

        // (4) EVERY REQUIRED FEATURE MUST BE EXPLICITLY TRUE. An absent key is
        // unreported, not false, and is refused naming the feature so an
        // operator can tell "the engine says no" from "the host forgot to ask".
        for (f in REQUIRED_FEATURES) {
            if (facts.featureSupported[f] != true) {
                return Verdict(WIRE_FEATURE_UNSUPPORTED, majorVersion = major, missingFeature = f)
            }
        }

        return Verdict(WIRE_SUPPORTED, majorVersion = major)
    }

    /**
 * The leading integer of a WebView `versionName` (`"124.0.6367.179"` → 124).
 *
 * IT IS DELIBERATELY STRICT ABOUT SHAPE. A version that does not BEGIN
 * with digits yields null, which the caller turns into
 * `provider_unavailable`; a lenient scan that found a number anywhere
 * inside the string would read `"dev-build-88"` as 88 and admit an engine
 * nobody validated.
 *
 * AND IT IS DELIBERATELY TOLERANT OF SURROUNDING WHITESPACE, which is
 * the OPPOSITE call from `RnWebViewAttachPlan`'s segment-id check — where a
 * trim would be a silent repair of an identity. The difference is what the
 * value is FOR: this one is parsed to an integer and compared against a
 * floor, so whitespace cannot change the answer and cannot admit an engine
 * below the floor; a segment id is compared byte-for-byte against a page's
 * echo and interpolated into script source, where a repaired value is
 * either a guaranteed mismatch or a wrong script. The asymmetry is stated
 * at both ends rather than left to be discovered.
 */
    @JvmStatic
    fun majorOf(versionName: String?): Int? {
        val s = versionName?.trim() ?: return null
        // THIS IS A DOUBLED GUARD AND THE REDUNDANCY IS MEASURED, NOT
        // GUESSED — a negative control came back NOT_CAUGHT and was right.
        // Replacing this early return with "contains a digit anywhere" leaves
        // the output IDENTICAL on every input tried (`"x124"`, `"v124"`,
        // `"dev-build-88"`, `""`, `"124.0.6367.179"`, `"69"`), because
        // `takeWhile` then yields an empty string and `toIntOrNull` answers
        // null. So the LOAD-BEARING half is `takeWhile` + the length bound
        // below; this line is DEFENCE IN DEPTH.
        //
        // IT IS KEPT DELIBERATELY. It is not structurally dead: it states
        // "must BEGIN with a digit" explicitly, and it is exactly what still
        // refuses `"x124"` if someone ever changes `takeWhile` to a
        // digit-scanning `filter` — a plausible edit, and one a control proves
        // is otherwise caught only by the happy path. Removing it is a silent
        // narrowing of defence that a tidier sees no failure for.
        if (s.isEmpty() || !s[0].isDigit()) return null
        // `takeWhile`, NEVER `filter`. A scan that collected every digit in
        // the string would read `"124.0.6367.179"` as `12406367179` (refused by
        // the bound below, so every supported device would read as unavailable)
        // and `"dev-build-88"` as 88 — admitting an engine nobody validated.
        val digits = s.takeWhile { it.isDigit() }
        // A leading run longer than nine digits cannot be a WebView major and
        // would overflow; refused rather than truncated.
        if (digits.length > 9) return null
        return digits.toIntOrNull()
    }
}

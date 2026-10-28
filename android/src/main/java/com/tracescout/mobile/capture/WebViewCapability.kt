package com.tracescout.mobile.capture

/**
 * Whether embedded-WebView capture can run on THIS device right now.
 *
 * **Android System WebView is a separately updateable system component.** The OS
 * API level says nothing about which engine is installed: the API-26 emulator
 * image ships WebView 69 (2018) while an API-26 phone with an updated provider
 * may run a current one. Support therefore depends on BOTH, and has to be a
 * runtime question.
 *
 * Two support contracts, deliberately separate:
 *
 * ```
 * Android native capture minimum OS: API 26
 * Embedded WebView capture minimum OS: API 26
 * + a supported WebView provider
 * + the runtime features the bridge actually uses
 * ```
 *
 * The empirical reason this exists: WebView **69** constructs a
 * `WebResourceResponse` from the real HTTP response and passes its reason phrase
 * into `setStatusCodeAndReasonPhrase`. **HTTP/2 carries no reason phrase**, so
 * the constructor throws `IllegalArgumentException` on the main thread and the
 * host app dies — measured on device against three consecutive builds, and NOT
 * caused by any callback we register (removing them changed nothing). It is not
 * catchable after navigation, because the throw happens on the engine's own
 * message handler. The only correct response is to never enter the path.
 */
object WebViewCapability {

    /**
 * The oldest provider version this project has EMPIRICALLY validated —
 * parent load over HTTPS/HTTP2, real cross-origin iframe, nested iframe,
 * `WEB_MESSAGE_LISTENER`, origin-authenticated messages, reload and remount,
 * no crash.
 *
 * It is a measured boundary, not a library release date. Raise it only by
 * running the provider matrix; lower it only by validating an older
 * provider end to end.
 */
    const val MIN_VALIDATED_WEBVIEW_VERSION = 124

    /**
 * Every androidx.webkit feature the bridge actually CALLS. Android's own
 * reference states that invoking a `WebViewCompat` API whose feature check
 * is false is unsafe, so this list and the call sites must stay in step —
 * a feature that is checked but never used would make the gate stricter
 * than reality, and one that is used but never checked is a crash.
 */
    val REQUIRED_FEATURES = listOf(
        "WEB_MESSAGE_LISTENER",
        "DOCUMENT_START_SCRIPT",
    )

    enum class Status {
        /** Everything the bridge needs is present. */
        WEBVIEW_CAPTURE_SUPPORTED,

        /** A provider is installed, but it is older than the validated floor. */
        WEBVIEW_PROVIDER_UNSUPPORTED,

        /** A recent-enough provider, but a required runtime feature is absent. */
        WEBVIEW_FEATURE_UNSUPPORTED,

        /** No WebView provider is installed, or it could not be inspected. */
        WEBVIEW_PROVIDER_UNAVAILABLE,
    }

    /**
 * The preflight verdict.
 *
 * [providerPackage] / [providerVersion] are technical metadata about a
 * SYSTEM COMPONENT — they identify the engine, never the user, and carry no
 * user data. They are recorded so an unsupported verdict can be explained
 * without a repro.
 */
    data class Result(
        val status: Status,
        val providerPackage: String? = null,
        val providerVersion: String? = null,
        val providerMajor: Int? = null,
        val missingFeature: String? = null,
    ) {
        val supported: Boolean get() = status == Status.WEBVIEW_CAPTURE_SUPPORTED

        /** A short, non-identifying description for technical details. */
        fun describe(): String = buildString {
            append(status.name.lowercase())
            if (providerPackage != null) append(" · ").append(providerPackage)
            if (providerVersion != null) append(' ').append(providerVersion)
            if (missingFeature != null) append(" · missing ").append(missingFeature)
        }
    }

    /** The major component of a provider version string, or null. */
    @JvmStatic
    fun majorOf(versionName: String?): Int? =
        versionName?.substringBefore('.')?.trim()?.toIntOrNull()

    /**
 * Decide from already-gathered facts.
 *
 * Split out from the Android calls so the decision is testable without a
 * device AND so the ORDER is explicit: an absent provider, then a provider
 * below the validated floor, then a missing feature. Checking features
 * first would report `feature_unsupported` for an ancient engine, which
 * describes the symptom instead of the cause.
 */
    @JvmStatic
    fun evaluate(
        providerPackage: String?,
        providerVersion: String?,
        featureSupported: (String) -> Boolean,
    ): Result {
        if (providerPackage.isNullOrBlank() || providerVersion.isNullOrBlank()) {
            return Result(Status.WEBVIEW_PROVIDER_UNAVAILABLE)
        }
        val major = majorOf(providerVersion)
            ?: return Result(
                Status.WEBVIEW_PROVIDER_UNAVAILABLE, providerPackage, providerVersion,
            )
        if (major < MIN_VALIDATED_WEBVIEW_VERSION) {
            return Result(
                Status.WEBVIEW_PROVIDER_UNSUPPORTED, providerPackage, providerVersion, major,
            )
        }
        for (f in REQUIRED_FEATURES) {
            if (!featureSupported(f)) {
                return Result(
                    Status.WEBVIEW_FEATURE_UNSUPPORTED, providerPackage, providerVersion,
                    major, f,
                )
            }
        }
        return Result(
            Status.WEBVIEW_CAPTURE_SUPPORTED, providerPackage, providerVersion, major,
        )
    }
}

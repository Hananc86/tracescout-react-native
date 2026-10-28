package com.tracescout.mobile.capture

import java.util.Locale
import java.util.regex.Pattern

/**
 * Which embedded-web origins may contribute to a mobile replay session.
 *
 * **The host app declares this, and it is a closed allowlist.** An
 * `addJavascriptInterface` object is injected into EVERY frame of a WebView,
 * including cross-origin iframes the host app never chose to embed — so
 * "the page can reach the bridge" says nothing about whether that page is
 * allowed to participate. The admission decision has to be made natively,
 * against an explicit list, or a third-party ad frame can contribute posture
 * to the customer's session.
 *
 * There is no wildcard and no "same-site" relaxation. `*`, `null`, `about:`,
 * `data:`, `javascript:`, an origin carrying a path/query/fragment, and plain
 * `http://` (outside localhost) are all rejected at CONSTRUCTION, so a policy
 * cannot be silently widened by a malformed entry.
 */
class WebViewOriginPolicy private constructor(
    /** Normalized, admitted origins in declaration order. */
    val admitted: List<String>,
) {

    fun isAdmitted(origin: String?): Boolean {
        val n = normalize(origin) ?: return false
        return admitted.contains(n)
    }

    /**
 * Origin rules for `WebViewCompat.addWebMessageListener` /
 * `addDocumentStartJavaScript`, which enforce admission at INJECTION time —
 * an unadmitted frame never obtains the object at all.
 */
    fun originRules(): Set<String> = admitted.toSet()

    override fun toString(): String = "WebViewOriginPolicy(${admitted.joinToString(",")})"

    companion object {
        /** scheme://host[:port] — nothing else. */
        private val ORIGIN = Pattern.compile("^https?://[a-z0-9.-]+(:[0-9]{1,5})?$")
        private val LOCAL = Pattern.compile("^https?://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?$")

        /**
 * Normalize one origin, or null when it is unusable.
 *
 * Fails CLOSED on every ambiguity rather than guessing, because every
 * guess here is a widening.
 */
        @JvmStatic
        fun normalize(raw: String?): String? {
            val s = raw?.trim() ?: return null
            if (s.isEmpty()) return null
            // The opaque origins. `null` is what a sandboxed or data:/about:
            // document reports, and admitting it would admit ALL of them at
            // once — they are indistinguishable from each other.
            if (s == "*" || s.equals("null", true) || s.equals("about:blank", true)) return null
            val lower = s.lowercase(Locale.ROOT)
            if (!ORIGIN.matcher(lower).matches()) return null
            // http is only ever acceptable for local development.
            if (lower.startsWith("http://") && !LOCAL.matcher(lower).matches()) return null
            return lower
        }

        /**
 * Build a policy. A single unusable entry disables the WHOLE policy
 * (returns an empty one) rather than admitting the entries that
 * happened to parse — a typo must not silently shrink the intended set
 * into something the operator never reviewed.
 */
        @JvmStatic
        fun of(vararg origins: String): WebViewOriginPolicy = of(origins.toList())

        @JvmStatic
        fun of(origins: List<String>): WebViewOriginPolicy {
            if (origins.isEmpty()) return WebViewOriginPolicy(emptyList())
            val out = LinkedHashSet<String>()
            for (o in origins) {
                val n = normalize(o) ?: return WebViewOriginPolicy(emptyList())
                out.add(n)
            }
            return WebViewOriginPolicy(out.toList())
        }

        /** Admits nothing. The default when a host app declares no policy. */
        @JvmStatic
        fun closed(): WebViewOriginPolicy = WebViewOriginPolicy(emptyList())
    }
}

/**
 * How the native side learned a frame's origin.
 *
 * This is recorded on every accepted segment because the two are NOT
 * equivalent evidence and must never be reported as though they were.
 */
enum class OriginTrust {
    /**
 * The browser told us, via `WebMessageListener.onPostMessage(sourceOrigin)`.
 * Unforgeable by page content. Requires `WebViewFeature.WEB_MESSAGE_LISTENER`
 * (WebView 88+).
 */
    AUTHENTICATED,

    /**
 * The page said so. This is all `addJavascriptInterface` can offer — it
 * carries no frame identity whatsoever — so on old WebViews the allowlist
 * is defense in depth against ACCIDENTAL embedding, not against a hostile
 * frame, which can simply claim an admitted origin.
 */
    DECLARED,
}

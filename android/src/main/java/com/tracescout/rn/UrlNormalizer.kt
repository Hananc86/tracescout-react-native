package com.tracescout.rn

/**
 * THE CANONICAL ROUTE-TEMPLATE NORMALIZER — Kotlin twin.
 *
 * ONE RULE OVERRIDES EVERY OTHER: **a raw path is never an output.** When the
 * classifier is not confident the answer degrades — `:segment`, then host-only,
 * then no event at all. It never falls back to the value it could not classify,
 * because "I could not tell what this was" is exactly when the value is most
 * likely to be an identifier, a token or an address.
 *
 * The raw URL exists only as the caller's transient input. Query, fragment and
 * userinfo are discarded BEFORE templating, so they are gone before the output
 * is computed and no later code path can reintroduce them.
 *
 * Pinned to `parity-vectors/url-normalization-vectors.json` together with the
 * Swift twin and the JS reference: the same synthetic URL must produce the same
 * host and routeTemplate on every platform, checked on BYTES rather than by
 * three people reading one paragraph. This is deliberately hand-parsed rather
 * than delegating to `java.net.URI`/`Uri` — the platform parsers disagree with
 * Foundation on userinfo, empty hosts and percent-encoding, and a parity
 * contract cannot rest on two different libraries agreeing by luck.
 */
object UrlNormalizer {

    const val MAX_SEGMENTS = 10
    const val MAX_SEGMENT_LEN = 48

    data class Normalized(val host: String?, val routeTemplate: String?, val scheme: String?) {
        /** A URL we cannot represent yields NO event at all. */
        val representable: Boolean get() = host != null
        companion object { val NONE = Normalized(null, null, null) }
    }

    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val ALL_DIGITS_RE = Regex("^[0-9]+$")
    private val LONG_HEX_RE = Regex("^[0-9a-fA-F]{24,}$")
    private val NAME_RE = Regex("^[A-Za-z][A-Za-z0-9._-]*$")
    private val HOST_RE = Regex("^[A-Za-z0-9._:-]+$")
    private val OPAQUE_CHARS = Regex("[@%=+;:]")

    internal fun classify(seg: String): String? {
        if (seg.isEmpty()) return null
        // CONFIDENT IDENTIFIERS BEFORE THE LENGTH DEGRADE. A 64-hex content
        // digest is longer than MAX_SEGMENT_LEN, so checking length first
        // reports `:segment` for the one shape we can name with certainty.
        if (UUID_RE.matches(seg) || ALL_DIGITS_RE.matches(seg) || LONG_HEX_RE.matches(seg)) return ":id"
        if (seg.length > MAX_SEGMENT_LEN) return ":segment"
        if (OPAQUE_CHARS.containsMatchIn(seg)) return ":segment"
        if (!NAME_RE.matches(seg)) return ":segment"
        // THE FOUR-CHARACTER FLOOR IS LOAD-BEARING. Without it `v2` is
        // digit-dominated (one digit of two characters) and every versioned API
        // collapses to `/:segment/.` — useless for the routes customers have.
        val alnum = seg.filter { it.isLetterOrDigit() }
        val digits = seg.count { it.isDigit() }
        if (alnum.length >= 4 && digits > 0 && digits * 2 >= alnum.length) return ":segment"
        val dots = seg.split(".")
        if (dots.size >= 3 && dots.all { it.length >= 4 }) return ":segment"
        return seg
    }

    fun normalize(raw: String?): Normalized {
        if (raw.isNullOrEmpty()) return Normalized.NONE
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return Normalized.NONE
        val scheme = raw.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return Normalized.NONE

        var rest = raw.substring(schemeEnd + 3)
        // Fragment and query die FIRST, before anything reads a path.
        rest = rest.substringBefore('#').substringBefore('?')

        val slash = rest.indexOf('/')
        var authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else "/"

        // userinfo is dropped by taking what follows the LAST '@'.
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        val host = authority.lowercase()
        if (host.isEmpty() || !HOST_RE.matches(host)) return Normalized.NONE

        val segs = path.split("/").filter { it.isNotEmpty() }
        if (segs.isEmpty()) return Normalized(host, "/", scheme)
        if (segs.size > MAX_SEGMENTS) return Normalized(host, null, scheme)
        val out = ArrayList<String>(segs.size)
        for (s in segs) {
            val c = classify(s) ?: return Normalized(host, null, scheme)
            out.add(c)
        }
        return Normalized(host, "/" + out.joinToString("/"), scheme)
    }
}

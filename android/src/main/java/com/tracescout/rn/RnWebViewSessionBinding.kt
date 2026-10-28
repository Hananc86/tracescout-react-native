package com.tracescout.rn

/**
 * G2-CALLSITE — THE SESSION-SCOPED OWNER OF A WEBVIEW CORRELATION, and the
 * Kotlin twin of Swift's `RnWebViewSessionBinding`.
 *
 * an earlier change measured the exact gap this closes, identically on both platforms:
 *
 * ```
 * bindFromConfiguration production callers. 0 (iOS)
 * bindFromConfiguration production callers. 0 (RN Android)
 * ```
 *
 * Every piece existed — the host-declared allowlist (this task's own
 * [RnWebViewOriginPolicy]), the capability preflight, the shared Model B
 * decision, the trust lifetime, the bind — and nothing called the bind, so no
 * session could correlate and nothing above it could be demonstrated.
 *
 * ## PURE, AND IN `rn-core` RATHER THAN IN `:rn`, FOR A MEASURED REASON
 *
 * The allowlist read needs an Android `Context` ([RnSecureConfig]) and the
 * capability preflight needs the host app's `androidx.webkit`. Both of those are
 * already owned by `RnWebViewHost` in `:rn`. What is left — "which session does
 * this correlation belong to, and may THIS session read it?" — is a pure state
 * machine, so it lives where every branch is provable in a plain JVM test with
 * no emulator, no Keystore and no WebView. The same split the bind itself uses
 * ([RnWebViewBinding.decide] separated from [RnWebViewBinding.bind]).
 *
 * ## IT DECIDES NO ADMISSION
 *
 * Admission is [RnWebViewSegment.decide], reached through
 * [RnWebViewCorrelation.receive]. This holder never reads a page, never mints a
 * carrier from the minted segment id, and has no path that admits anything — a
 * carrier exists only when a REAL inbound handshake was ADMITTED. "N + S, never
 * S alone."
 *
 * ## NO JS PATH
 *
 * `rn-core` carries no `com.facebook.react` dependency at all, so nothing here
 * is JS-callable. An origin allowlist is the definition of a native-owned fact,
 * and a policy a page's own runtime could supply would be a policy the page
 * could widen.
 */
class RnWebViewSessionBinding {

    /** What happened to one adopt request. */
    sealed class BindOutcome {
        /**
 * A correlation now exists for this session. Carries the minted segment
 * id — which is NOT yet a correlation on the wire.
 */
        data class Bound(val segmentId: String) : BindOutcome()

        /** This session already has one; nothing was re-minted. */
        data class AlreadyBound(val segmentId: String) : BindOutcome()

        /** No correlation is possible for this session, with the typed reason. */
        data class Refused(val why: String) : BindOutcome()
    }

    private val lock = Any()
    /**
 * The session this correlation belongs to. A different session id can never
 * read it — see [carrier].
 */
    private var boundSessionId: String? = null
    private var correlation: RnWebViewCorrelation? = null
    /**
 * The last refusal, so "no correlation" can be reported WITH its cause.
 * Diagnostics only; nothing decides on it.
 */
    private var lastRefusal: String? = null

    /**
 * Adopt the result of [RnWebViewBinding.bind] (or of `:rn`'s
 * `RnWebViewHost.bindFromConfiguration`, which is that bind reading the
 * native-owned configuration) as THIS session's correlation.
 *
 * IT TAKES AN ALREADY-DECIDED RESULT AND NEVER RE-DECIDES. The allowlist,
 * the capability order and every refusal code are the bind's; a second
 * decision here would be a second answer to "may this session correlate?"
 * and the copy that drifts is always the one nobody is looking at.
 *
 * IDEMPOTENT PER SESSION, AND A NEW SESSION NEVER INHERITS. Re-adopting
 * for the same session would replace a live correlation — possibly one that
 * has an ADMITTED segment — with a freshly minted one, silently detaching a
 * correlation the wire is already carrying. A DIFFERENT id replaces the
 * state outright: a correlation admitted for session A must never ride
 * session B's events, which is the "N + S" claim itself.
 */
    fun adopt(nativeSessionId: String, result: RnWebViewBinding.Result): BindOutcome =
        synchronized(lock) {
            val existing = correlation
            if (boundSessionId == nativeSessionId && existing != null) {
                return BindOutcome.AlreadyBound(existing.segmentId)
            }
            boundSessionId = nativeSessionId
            val c = result.correlation
            if (c != null) {
                correlation = c
                lastRefusal = null
                BindOutcome.Bound(c.segmentId)
            } else {
                correlation = null
                val why = result.refusal ?: "bind_refused"
                lastRefusal = why
                BindOutcome.Refused(why)
            }
        }

    /**
 * Retire everything bound to the previous session.
 *
 * CALLED ON A SESSION CHANGE, AND IT IS NOT MERELY TIDINESS. [carrier]
 * already refuses a session-id mismatch, so this is the second line; the
 * first is that a stale correlation cannot be read at all.
 */
    fun reset(reason: String) = synchronized(lock) {
        boundSessionId = null
        correlation = null
        lastRefusal = reason
    }

    /**
 * The envelope carrier for THIS session, or null.
 *
 * THE SESSION ID IS COMPARED, NOT ASSUMED. The emit path asks for a
 * named session; answering with another session's correlation would put a
 * segment id on the wire for events it never covered.
 *
 * NULL IS THE ORDINARY ANSWER AND COSTS NOTHING. No binding, a refused
 * binding, or a binding with no ADMITTED handshake all answer null, and the
 * envelope then omits both correlation keys exactly as it did before this
 * file existed.
 */
    fun carrier(nativeSessionId: String?): RnEnvelope.WebViewRef? = synchronized(lock) {
        if (nativeSessionId == null || boundSessionId != nativeSessionId) null
        else correlation?.carrier
    }

    /**
 * The minted segment id for this session, or null. Diagnostics: it is NOT a
 * correlation, and it never reaches the wire on its own.
 */
    fun mintedSegmentId(nativeSessionId: String?): String? = synchronized(lock) {
        if (nativeSessionId == null || boundSessionId != nativeSessionId) null
        else correlation?.segmentId
    }

    /**
 * The last refusal observed, for a report that has to say WHY a session
 * carries no segment.
 */
    fun refusalReason(): String? = synchronized(lock) { lastRefusal }

    /** The bound correlation for this session, or null. */
    fun correlationFor(nativeSessionId: String): RnWebViewCorrelation? = synchronized(lock) {
        if (boundSessionId != nativeSessionId) null else correlation
    }

    /**
 * Revalidate this session's admission against the LIVE policy and the web
 * view's current origin.
 *
 * IT DELEGATES; IT DOES NOT RE-IMPLEMENT. [RnWebViewCorrelation.revalidate]
 * consults the live policy ONLY to retire — a widening mid-session is never
 * applied, a revocation is applied at once, and an unreadable current origin
 * DROPS. A second comparison here would be free to disagree with the one
 * that decides.
 *
 * THE CALLER SUPPLIES BOTH LIVE FACTS, for the same reason `:rn`'s
 * `RnWebViewHost.revalidate` does: reading the current origin means touching
 * a `WebView`, and this module carries no WebView dependency at all. A
 * caller that cannot establish the origin passes null, which DROPS.
 */
    fun revalidate(
        nativeSessionId: String,
        currentOrigin: String?,
        livePolicy: List<String>?,
    ): RnWebViewTrustLifetime.Verdict {
        val c = correlationFor(nativeSessionId) ?: return RnWebViewTrustLifetime.Verdict.NoSegment
        val v = c.revalidate(currentOrigin = currentOrigin, livePolicy = livePolicy)
        if (v is RnWebViewTrustLifetime.Verdict.Drop) {
            synchronized(lock) { lastRefusal = "retired:" + v.why }
        }
        return v
    }

    companion object {
        /**
 * Process-wide, because a native session is process-wide. Not a
 * singleton for convenience: the correlation's identity IS the
 * session's, so two holders would be two answers to one question.
 */
        @JvmField
        val shared = RnWebViewSessionBinding()
    }
}

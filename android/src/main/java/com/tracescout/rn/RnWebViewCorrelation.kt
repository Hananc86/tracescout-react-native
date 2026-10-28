package com.tracescout.rn

import java.security.SecureRandom

/**
 * Mints the segment identifier — the Kotlin twin of Swift's `RnWebViewMint`.
 *
 * THE NATIVE SIDE MINTS, ALWAYS. The whole correlation claim rests on the
 * page being able only to ECHO an id it was given: [RnWebViewSegment.decide]
 * refuses anything that is not byte-equal to the `expectedSegmentId` the native
 * side holds, so a frame cannot attach itself to — or overwrite — another
 * segment's posture. A page-chosen id would make that refusal meaningless.
 */
object RnWebViewMint {

    /**
 * `SecureRandom` IS THE CRYPTOGRAPHIC SOURCE, and the choice is not
 * cosmetic. A segment id an observer could predict would let a hostile
 * frame echo an id it was never given — so this is not a place for a
 * counter, a timestamp, `Random` or `UUID.nameUUIDFromBytes`.
 *
 * NO SESSION, DEVICE OR INSTALL IDENTITY IS DERIVED INTO IT. It is
 * opaque randomness: the correlation is established by the native session
 * carrying the segment id (N + S), never by the id itself encoding N.
 */
    private val RNG = SecureRandom()

    /** The alphabet is base64url's, so no `+`, `/` or `=` can ever appear. */
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
 * 128 bits, base64url, unpadded → 22 characters, inside the protocol's
 * `^[A-Za-z0-9_-]{8,64}$`.
 *
 * THE SAME LENGTH AND ENCODING AS THE SWIFT MINT, deliberately. The two
 * halves of ONE SDK minting identifiers of different shapes would be a
 * gratuitous difference a reader has to discover; the native Android SDK's
 * own 32-hex form is a THIRD shape and is not copied here for the same
 * reason.
 */
    @JvmStatic
    fun mintSegmentId(): String {
        val bytes = ByteArray(16)
        RNG.nextBytes(bytes)
        return base64url(bytes)
    }

    /**
 * Unpadded base64url, written out rather than post-processing a standard
 * encoder, so no character outside [ALPHABET] can reach a value the
 * protocol's identifier pattern would refuse.
 */
    @JvmStatic
    fun base64url(bytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n shr 18) and 63])
            out.append(ALPHABET[(n shr 12) and 63])
            out.append(ALPHABET[(n shr 6) and 63])
            out.append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n shr 18) and 63])
                out.append(ALPHABET[(n shr 12) and 63])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n shr 18) and 63])
                out.append(ALPHABET[(n shr 12) and 63])
                out.append(ALPHABET[(n shr 6) and 63])
            }
        }
        return out.toString()
    }
}

/**
 * ONE WebView's correlation state — THE PRODUCTION CALLER of
 * [RnWebViewSegment.decide] on Android, and the Kotlin twin of Swift's
 * `RnWebViewCorrelation`.
 *
 * It holds what only the native side may hold (the minted segment id, the
 * native session id, the declared origin allowlist, the preflight verdict) and
 * exposes exactly three things to the rest of the producer: the decision for an
 * inbound message, the envelope carrier for an admitted segment, and the
 * revalidation that retires a stale admission.
 *
 * THE ADMITTED SEGMENT IS REPLACED ONLY BY A LATER ADMITTED DECISION, so a
 * refused message can never clear a correlation that was already established —
 * a hostile frame must not be able to detach a legitimate one by sending
 * rubbish.
 *
 * NOT THREAD-SAFE BY ITSELF, AND THAT IS THE SAME CONTRACT AS THE SWIFT
 * TWIN. One instance belongs to one WebView and is driven from the thread that
 * receives that WebView's messages; the native Android bridge's own
 * `WebViewBridge.handle` is likewise a pure decision whose caller owns the
 * sequencing. A lock here would state a guarantee the surrounding producer does
 * not currently need and would hide where the real ordering lives.
 */
class RnWebViewCorrelation @JvmOverloads constructor(
    val nativeSessionId: String,
    /**
 * The host app's declared allowlist, as it was at BIND time. Null = none
 * declared, which [RnWebViewSegment.decide] refuses `origin_policy_absent`
 * fail closed.
 */
    val policy: List<String>?,
    /** Measured once per WebView, at the moment the bridge would be installed. */
    val capability: RnWebViewSegment.Capability,
    /** Minted natively at construction. A page never supplies it. */
    val segmentId: String = RnWebViewMint.mintSegmentId(),
) {

    /** The last ADMITTED segment, or null while none has been established. */
    private var admitted: RnWebViewSegment.Segment? = null

    /**
 * SHOULD THE BRIDGE BE INSTALLED AT ALL? the specification says an unsupported
 * engine yields no segment row AT ALL. [RnWebViewSegment.decide] enforces
 * that on every message, so this is a cheap pre-answer for the attach site
 * and NOT the gate — the gate is inside the decision, where a route that
 * forgets cannot bypass it.
 */
    val shouldInstallBridge: Boolean
        get() = capability == RnWebViewSegment.Capability.SUPPORTED

    /**
 * Decide one inbound bridge message and remember an admission.
 *
 * The ONE production call into [RnWebViewSegment.decide] on this platform.
 */
    @JvmOverloads
    fun receive(
        kind: String?,
        fields: Map<String, Any?>,
        authenticatedOrigin: String? = null,
    ): RnWebViewSegment.Decision {
        val d = RnWebViewSegment.decide(
            capability = capability,
            policy = policy,
            kind = kind,
            fields = fields,
            expectedSegmentId = segmentId,
            nativeSessionId = nativeSessionId,
            authenticatedOrigin = authenticatedOrigin,
        )
        // ONLY AN ADMISSION WRITES. A refusal leaves an established
        // correlation exactly as it was.
        if (d.outcome == RnWebViewSegment.Outcome.ADMIT) {
            d.segment?.let { admitted = it }
        }
        return d
    }

    /** The admitted segment, or null. */
    val segment: RnWebViewSegment.Segment?
        get() = admitted

    /**
 * DROP AN ADMISSION THAT IS NO LONGER ENTITLED TO ITS TRUST — the
 * "revocation and navigation must not retain stale trust" half of the
 * origin-policy contract.
 *
 * THE GAP THIS CLOSES. Admission is per message and was already
 * correct; what had no answer was the LIFETIME of an admission after it was
 * granted. [admitted] is replaced only by a LATER ADMITTED decision —
 * deliberately, so a hostile frame cannot detach a legitimate correlation
 * by sending rubbish — and the consequence was that nothing could ever
 * retire one. [carrier] is read for EVERY event of the session, so a
 * segment admitted for one origin kept riding events after the web view
 * navigated elsewhere: a false datum in the exact row this lane produces.
 *
 * THE ASYMMETRY IS THE DESIGN, AND IT IS WHAT MAKES BOTH CLAUSES OF THE
 * CONTRACT TRUE AT ONCE:
 *
 * ```
 * WIDENING mid-session -> NOT applied. Admission uses [policy], the list
 * this session was BOUND with, or "bound to the
 * native session" would mean nothing and a
 * runtime change could admit an origin the
 * session was never configured for.
 * REVOCATION mid-session -> applied AT ONCE. A narrowing can only ever
 * refuse, so honouring it immediately is always
 * the fail-closed direction.
 * ```
 *
 * So [livePolicy] is consulted ONLY to retire an admission, never to grant
 * one. There is no path here that admits anything.
 *
 * IT RETURNS THE VERDICT RATHER THAN SWALLOWING IT, so a caller can
 * record WHY a correlation was retired. A silent drop and a correlation
 * that was never established look identical from the outside.
 */
    fun revalidate(
        currentOrigin: String?,
        livePolicy: List<String>?,
    ): RnWebViewTrustLifetime.Verdict {
        val verdict = RnWebViewTrustLifetime.decide(
            admittedOrigin = admitted?.origin,
            currentOrigin = currentOrigin,
            livePolicy = livePolicy,
        )
        if (verdict is RnWebViewTrustLifetime.Verdict.Drop) admitted = null
        return verdict
    }

    /**
 * The envelope carrier for every event of this hybrid session, or null when
 * nothing has been admitted.
 *
 * THE CARRIER IS DERIVED FROM THE ADMITTED SEGMENT, NEVER FROM
 * [segmentId]. Returning a carrier built from the minted id alone would put
 * a correlation on the wire that no message ever established — which is the
 * entire failure Model B's "N + S, never S alone" forbids. The private
 * constructor on [RnEnvelope.WebViewRef] is what makes that structural.
 */
    val carrier: RnEnvelope.WebViewRef?
        get() = admitted?.let { RnEnvelope.WebViewRef.of(it) }

    /**
 * The identity payload a document-start script injects into an ADMITTED
 * world so the page can ECHO it back.
 *
 * IT CARRIES THE SEGMENT ID AND NOTHING ELSE — no session id, no project
 * id, no posture, no credential. The page's only job is to echo the id and
 * report its own posture; it is never told anything about the native
 * session, because [RnWebViewSegment.decide] correlates N and S natively
 * and a page that knew N could claim one.
 *
 * NULL WHEN THE BRIDGE MUST NOT BE INSTALLED. There is no payload for an
 * unsupported engine, so the attach site cannot inject one by forgetting to
 * check.
 */
    val identityScriptPayload: Map<String, String>?
        get() = if (shouldInstallBridge) mapOf("segmentId" to segmentId) else null
}

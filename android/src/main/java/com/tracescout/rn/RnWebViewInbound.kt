package com.tracescout.rn

/**
 * G2-ATTACH — WHAT THE BROWSER TELLS US ABOUT A MESSAGE, CHECKED AGAINST WHAT
 * THE PAGE CLAIMS ABOUT ITSELF.
 *
 * `WebViewCompat.addWebMessageListener`'s callback carries two facts the page
 * cannot forge, because the browser supplies them rather than the document:
 *
 * ```
 * sourceOrigin the frame's real origin, browser-attributed
 * isMainFrame whether that frame is the top-level document
 * ```
 *
 * [RnWebViewSegment.decide] already consumes the first as `authenticatedOrigin`
 * and refuses a frame whose claimed `frameOrigin` contradicts it. The SECOND
 * had no consumer on either platform, so a subframe claiming `frameRole:
 * "parent"` was believed — the role was a page assertion with nothing behind
 * it. This closes that: the role is cross-checked against the framework's own
 * answer, so **no frame role is ever guessed or taken on trust**.
 *
 * ## THE PAGE'S ONLY JOB IS TO ECHO
 *
 * The document-start script writes the natively minted segment id into an
 * admitted world; the page sends it back. [RnWebViewSegment.decide] compares it
 * byte-for-byte against the id the native side holds, so a page cannot attach
 * itself to — or overwrite — a segment it was never given. There is no
 * handshake to forge here and none is synthesized on the native side: if a page
 * never echoes, nothing is admitted and no segment row exists. An absent
 * correlation is the honest outcome, and the specification's third outcome.
 *
 * ## IT DOES NOT RE-IMPLEMENT THE DECISION
 *
 * This module answers only what the FRAMEWORK FACTS make answerable. The closed
 * field vocabulary, the closed role set, the segment-id comparison, the origin
 * cross-check, the posture bounds and the capability gate all stay in
 * [RnWebViewSegment.decide] — the one shared home, pinned to the Swift twin by
 * frozen parity vectors. A second copy of any of those rules here would be free
 * to drift from the one the other platform runs.
 */
object RnWebViewInbound {

    /**
 * The role a top-level document may claim.
 *
 * DERIVED AS A PARTITION OF [RnWebViewSegment.FRAME_ROLES], NEVER
 * RE-LISTED. A role added to the shared vocabulary without being classified
 * here would otherwise land in neither set and silently skip the
 * cross-check; [CHILD_ROLES] is the complement and a test asserts the two
 * cover the shared set exactly.
 */
    val PARENT_ROLES = setOf("parent")

    /** Every other declared role is, by definition, not the top-level document. */
    val CHILD_ROLES: Set<String> = RnWebViewSegment.FRAME_ROLES - PARENT_ROLES

    /** What the host app must do with one inbound message. */
    sealed class Verdict {
        /**
 * Hand these to [RnWebViewCorrelation.receive]. The fields are passed
 * through UNCHANGED — this module filters nothing, because the field
 * vocabulary is `decide`'s to own.
 */
        data class Deliver(
            val kind: String?,
            val fields: Map<String, Any?>,
            /** The browser's own attribution, never the page's claim. */
            val authenticatedOrigin: String?,
        ) : Verdict()

        /**
 * Do not deliver. Carries the typed reason.
 *
 * A REFUSAL HERE NEVER TOUCHES AN ESTABLISHED CORRELATION.
 * [RnWebViewCorrelation] replaces its admitted segment only on a later
 * ADMITTED decision, and not calling `receive` at all cannot do even
 * that — so a hostile frame cannot detach a legitimate correlation by
 * sending rubbish, which is the same guarantee the decision itself
 * gives.
 */
        data class Drop(val why: String) : Verdict()
    }

    /**
 * Decide one inbound bridge message.
 *
 * @param sourceOrigin the browser's `WebMessageListener` origin, as text.
 * @param isMainFrame the browser's own answer. **Not optional**: a caller
 * that cannot state it must not call this, because "I do not know which
 * frame this came from" is exactly the guess this function exists to
 * remove.
 */
    @JvmStatic
    fun decide(
        kind: String?,
        fields: Map<String, Any?>,
        sourceOrigin: String?,
        isMainFrame: Boolean,
    ): Verdict {
        // (1) THE BROWSER MUST HAVE ATTRIBUTED AN ORIGIN. On this channel it
        // always does; an absent or opaque one means we are not on the channel
        // we think we are, and `decide` would refuse it anyway. Dropping here
        // keeps the reason specific instead of surfacing as an origin mismatch.
        val origin = sourceOrigin?.trim()
        if (origin.isNullOrEmpty()) return Verdict.Drop("source_origin_absent")
        // The opaque origins. A sandboxed, `data:` or `about:blank` document
        // reports one of these, and they are indistinguishable from each other —
        // admitting one would admit all of them at once.
        if (origin == "*" || origin.equals("null", true) ||
            origin.equals("about:blank", true)
        ) {
            return Verdict.Drop("source_origin_opaque")
        }

        // (2) A CLAIMED ROLE MUST AGREE WITH THE FRAMEWORK. An unrecognised
        // role is passed through DELIBERATELY: the closed set belongs to
        // `RnWebViewSegment.decide`, which refuses it there, and a second
        // membership check here would be a second home for one vocabulary.
        val role = (fields["frameRole"] as? String)?.trim()
        if (!role.isNullOrEmpty()) {
            if (role in PARENT_ROLES && !isMainFrame) {
                return Verdict.Drop("frame_role_not_main_frame")
            }
            if (role in CHILD_ROLES && isMainFrame) {
                return Verdict.Drop("frame_role_not_subframe")
            }
        }

        return Verdict.Deliver(kind = kind, fields = fields, authenticatedOrigin = origin)
    }
}

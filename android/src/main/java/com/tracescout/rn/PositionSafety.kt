package com.tracescout.rn

/**
 * MOBILE INTERACTION POSITION SAFETY v1 — EXPLICIT, NODE-LOCAL, OPT-IN.
 *
 * ## Why a second dimension exists at all
 *
 * Content privacy and position privacy are DIFFERENT DECISIONS, and conflating
 * them produced a real dead end: the RN hierarchy walker masks every node by
 * design (a Phase-1 tree contains no readable text by construction), so a
 * target could never resolve to `Treatment.ALLOWED`, so an interaction could
 * never carry coordinates — the seam, the policy and the walker were each
 * correct and the product requirement was unreachable anyway.
 *
 * The masking default protects TEXT. A coordinate is not text. An SDK may hide
 * a button's label while still showing that the user tapped that button's
 * position — but ONLY when the application explicitly authorises it.
 *
 * ## The default is REDACTED, and there is no way to make it otherwise
 *
 * No declaration means no coordinates. A node is position-allowed only when the
 * app author says so ON THAT EXACT NODE. Nothing is promoted by class, role,
 * component type, "button-ness", `Pressable`, or any heuristic: those facts may
 * RESTRICT, never promote. Absence of a declaration in a customer app therefore
 * yields coordinate-free interaction replay, permanently and by construction.
 */
enum class PositionTreatment {
    /** Affirmatively denied. A declaration CANNOT override this. */
    BLOCKED,

    /** The default. No declaration, so no coordinates. */
    REDACTED,

    /** The app author declared THIS node's position recordable. */
    ALLOWED,
    ;

    companion object {
        /**
 * PRECEDENCE IS BLOCKED > REDACTED > ALLOWED, and it is not a
 * `maxOf`. The whole point is that the strongest restriction wins, so
 * `resolve` can only ever move DOWN from a declaration, never up.
 */
        fun strongest(a: PositionTreatment, b: PositionTreatment): PositionTreatment =
            if (a.ordinal <= b.ordinal) a else b

        /**
 * Resolve one node's position treatment.
 *
 * @param declaredOnThisNode the app author's declaration, read from THIS
 * node — never inherited. A parent's allow must not make its children
 * position-safe: the canonical hazard is a safe-position container
 * holding a secure or unannotated child, and inheritance would leak
 * the child's location through the parent's consent.
 * @param contentTreatment the node's own content treatment. BLOCKED
 * content (secure surface, WebView, camera, password field) denies the
 * position channel outright — a declaration cannot buy it back.
 * @param inBlockedContext an ancestor is blocked, or the screen is
 * never-record. Also unconditional.
 */
        fun resolve(
            declaredOnThisNode: Boolean,
            contentTreatment: Treatment?,
            inBlockedContext: Boolean = false,
        ): PositionTreatment {
            if (inBlockedContext) return BLOCKED
            if (contentTreatment == Treatment.BLOCKED) return BLOCKED
            // MASKED content does NOT block position. That is the entire
            // point of the second dimension: a masked label with a declared
            // position is exactly the case the product needs.
            return if (declaredOnThisNode) ALLOWED else REDACTED
        }
    }
}

/**
 * The declaration CARRIER on React Native.
 *
 * ## Why `nativeID`, audited rather than assumed
 *
 * RN 0.85.3 `BaseViewManager` stores `nativeID` in a DEDICATED keyed tag slot
 * (`view.setTag(R.id.view_tag_native_id, …)`), readable from native without
 * reflection, carrying no user content, set only by the app author. It is RN's
 * own channel for native-side annotation.
 *
 * The alternatives were rejected on inspection, not by preference:
 *
 * - `testID` sets BOTH `R.id.react_test_id` AND the plain `view.setTag(.)`,
 * so it collides with e2e tooling and with this programme's own harness.
 * - accessibility label, hint, role and value are CONTENT.
 * - class name / component type is a heuristic, which the contract forbids.
 * - a dedicated TraceScout prop needs a ViewManager and a wrapper view, which
 * inserts a real `View` into the customer's layout — a behaviour change.
 *
 * THE LIMITATION, STATED RATHER THAN DISCOVERED LATER: `nativeID` is
 * single-valued, so an app already using it would otherwise have to choose. The
 * declaration is therefore a TOKEN within a whitespace/comma separated list, so
 * an existing identifier survives:
 *
 * nativeID="checkout-cta ts-position-safe"
 *
 * AND IT IS A TOKEN MATCH, NEVER A SUBSTRING. `contains` would let
 * `notes-about-ts-position-safe-policy` silently authorise a coordinate — an
 * accidental declaration is exactly the failure this dimension exists to make
 * impossible.
 */
object PositionDeclaration {

    /** The reserved token. Exact, lowercase, matched as a whole word. */
    const val TOKEN = "ts-position-safe"

    private val SEPARATORS = Regex("[\\s,]+")

    fun isDeclared(nativeId: String?): Boolean {
        if (nativeId.isNullOrEmpty()) return false
        return nativeId.trim().split(SEPARATORS).any { it == TOKEN }
    }
}

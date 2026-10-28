package com.tracescout.rn

/**
 * Maps the AUTHORITATIVE hierarchy privacy state onto the interaction policy's
 * target class.
 *
 * THIS IS NOT A SECOND CLASSIFIER, AND THE DISTINCTION MATTERS. It computes
 * nothing about privacy: it CONSUMES `Treatment` — the same value the hierarchy
 * and frame paths resolve, with the same monotonic JS-vs-native join — and
 * translates it into the vocabulary `InteractionPolicy` speaks. A second,
 * interaction-specific classifier would eventually disagree with the one that
 * decides what gets masked in the picture, and the weaker of two answers is the
 * one an attacker gets.
 *
 * When sources disagree the MORE RESTRICTIVE result wins, in every direction.
 */
object InteractionTargetClass {

    /**
 * @param treatment the resolved hierarchy treatment of the hit node,
 * or null when hit-testing produced no trustworthy
 * target.
 * @param blockedReason why it is blocked, when it is.
 * @param editable the platform reports the view accepts typed text.
 * @param secure the platform reports a secure surface
 * (`isSecureTextEntry`, FLAG_SECURE, camera).
 *
 * `null` treatment is UNKNOWN, never SAFE. "We could not resolve the
 * target" and "the target is fine" are different facts, and defaulting the
 * first to the second is how a coordinate over a masked field gets
 * recorded.
 */
    fun of(
        treatment: Treatment?,
        blockedReason: BlockedReason?,
        editable: Boolean,
        secure: Boolean,
    ): InteractionPolicy.TargetClass {
        // Most restrictive first, and each of these is independent of the
        // others on purpose: SECURE must win even if the hierarchy has not yet
        // resolved a treatment, because a focus-state or layout race must not
        // be able to downgrade a password field.
        if (secure) return InteractionPolicy.TargetClass.SECURE
        if (blockedReason == BlockedReason.SECURE_TEXT_ENTRY
            || blockedReason == BlockedReason.NATIVE_SECURE_SURFACE
        ) return InteractionPolicy.TargetClass.SECURE
        if (editable) return InteractionPolicy.TargetClass.EDITABLE

        return when (treatment) {
            null -> InteractionPolicy.TargetClass.UNKNOWN
            Treatment.BLOCKED -> InteractionPolicy.TargetClass.REDACTED
            Treatment.MASKED -> InteractionPolicy.TargetClass.REDACTED
            Treatment.ALLOWED -> InteractionPolicy.TargetClass.SAFE
        }
    }

    /**
 * Join two independently-derived classes. Used when the hierarchy and the
 * frame path disagree — the directive's example is "hierarchy says safe,
 * frame classifier says unknown → unknown".
 *
 * The order is the documentation: SAFE is the weakest claim, SECURE the
 * strongest, and UNKNOWN sits ABOVE safe because failing closed is the
 * whole design.
 */
    private val RANK = mapOf(
        InteractionPolicy.TargetClass.SAFE to 0,
        InteractionPolicy.TargetClass.UNKNOWN to 1,
        InteractionPolicy.TargetClass.REDACTED to 2,
        InteractionPolicy.TargetClass.EDITABLE to 3,
        InteractionPolicy.TargetClass.SECURE to 4,
    )

    fun mostRestrictive(
        a: InteractionPolicy.TargetClass,
        b: InteractionPolicy.TargetClass,
    ): InteractionPolicy.TargetClass =
        if ((RANK[a] ?: 4) >= (RANK[b] ?: 4)) a else b
}

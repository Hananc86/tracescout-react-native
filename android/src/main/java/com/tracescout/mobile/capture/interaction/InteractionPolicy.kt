package com.tracescout.mobile.capture.interaction

/**
 * MOBILE INTERACTION v1 — the Android NATIVE twin of
 * `mobile-protocol/src/interaction-policy.cjs`.
 *
 * Pinned to the SAME vectors as the JS reference, the RN Kotlin twin
 * (`com.tracescout.rn.InteractionPolicy`) and the Swift twin:
 * `mobile-react-native/parity-vectors/interaction-policy-vectors.json`. A
 * platform disagreement fails on bytes, in `NativeInteractionPolicyVectorTest`.
 *
 * WHY A TWIN AND NOT A DEPENDENCY. The RN copy lives in `rn-core`, and `:rn`
 * depends on `:sdk` — the native SDK depending on an RN artifact would invert
 * that graph and make the published native AAR carry an RN library. The
 * established pattern for this contract is one implementation per runtime,
 * all pinned to one vector file, so that is what this is.
 *
 * THE ORDER OF THE RULES IS THE CONTRACT. Most-restrictive first; every
 * unknown resolves to the more restrictive branch.
 */
object InteractionPolicy {

    enum class TargetClass(val wire: String) {
        SAFE("safe"), REDACTED("redacted"), EDITABLE("editable"),
        SECURE("secure"), UNKNOWN("unknown");

        companion object {
            fun from(s: String?): TargetClass = entries.firstOrNull { it.wire == s } ?: UNKNOWN

            /** Restriction rank, most restrictive highest. Used to JOIN two snapshots. */
            internal fun rank(t: TargetClass): Int = when (t) {
                SAFE -> 0
                REDACTED -> 1
                UNKNOWN -> 2
                EDITABLE -> 3
                SECURE -> 4
            }

            internal fun stricter(a: TargetClass, b: TargetClass): TargetClass =
                if (rank(a) >= rank(b)) a else b
        }
    }

    enum class Outcome(val wire: String) {
        EMIT_POSITIONAL("emit_positional"), EMIT_REDACTED("emit_redacted"), SUPPRESS("suppress")
    }

    enum class Suppression(val wire: String) {
        NOT_CONTENT_WINDOW("not_content_window"),
        NEVER_RECORD("never_record"),
        TEXT_ENTRY_ACTIVE("text_entry_active"),
        EDITABLE_TARGET("editable_target"),
        SECURE_TARGET("secure_target"),
        UNSUPPORTED_GESTURE("unsupported_gesture"),
    }

    /** Position privacy — the SECOND dimension. Precedence BLOCKED > REDACTED > ALLOWED. */
    enum class PositionTreatment(val wire: String) {
        BLOCKED("blocked"), REDACTED("redacted"), ALLOWED("allowed");

        companion object {
            /** The strongest restriction wins. Never a `maxOf` over a numeric "safety". */
            fun strongest(a: PositionTreatment, b: PositionTreatment): PositionTreatment =
                if (a.ordinal <= b.ordinal) a else b

            fun from(s: String?): PositionTreatment = when (s) {
                "allowed" -> ALLOWED
                "blocked" -> BLOCKED
                else -> REDACTED
            }

            /** `resolvePositionTreatment` from the JS reference, rule for rule. Never inherited. */
            fun resolve(
                declaredOnThisNode: Boolean,
                contentBlocked: Boolean,
                inBlockedContext: Boolean,
            ): PositionTreatment = when {
                inBlockedContext -> BLOCKED
                contentBlocked -> BLOCKED
                declaredOnThisNode -> ALLOWED
                else -> REDACTED
            }
        }
    }

    /** The reserved declaration token — matched as a WHOLE token, never a substring. */
    const val POSITION_TOKEN = "ts-position-safe"

    fun isPositionDeclared(declaration: String?): Boolean {
        if (declaration.isNullOrEmpty()) return false
        return declaration.trim().split(Regex("[\\s,]+")).any { it == POSITION_TOKEN }
    }

    const val COORDINATE_SPACE = "logical_viewport"

    val POSITIONAL_FIELDS = listOf("interaction", "gestureId", "phase", "x", "y",
        "pointerCount", "targetRole", "targetTestTag", "onRedactedTarget")
    val REDACTED_FIELDS = listOf("interaction", "gestureId", "phase", "onRedactedTarget")
    val FORBIDDEN_FIELDS = listOf("text", "value", "targetText", "targetValue", "label",
        "contentDescription", "keyCode", "key", "character", "inputText")

    data class Context(
        val fromContentWindow: Boolean,
        val screenNeverRecord: Boolean,
        val textEntryActive: Boolean,
        val targetClass: TargetClass,
        val pointerCount: Int,
        val isTap: Boolean,
        val positionTreatment: PositionTreatment = PositionTreatment.REDACTED,
        val isSwipe: Boolean = false,
    )

    data class Decision(
        val outcome: Outcome,
        val reason: Suppression?,
        val fields: List<String>,
        val includeCoordinates: Boolean,
        val coordinateSpace: String?,
    )

    private fun none(r: Suppression) = Decision(Outcome.SUPPRESS, r, emptyList(), false, null)

    fun decide(c: Context): Decision {
        if (!c.fromContentWindow) return none(Suppression.NOT_CONTENT_WINDOW)
        if (c.screenNeverRecord) return none(Suppression.NEVER_RECORD)
        if (c.textEntryActive) return none(Suppression.TEXT_ENTRY_ACTIVE)
        if (c.targetClass == TargetClass.SECURE) return none(Suppression.SECURE_TARGET)
        if (c.targetClass == TargetClass.EDITABLE) return none(Suppression.EDITABLE_TARGET)
        // Exactly ONE single-pointer class. scroll/pan/fling/pinch/rotate are
        // protocol values this producer does NOT claim (it did not measure them).
        val classes = (if (c.isTap) 1 else 0) + (if (c.isSwipe) 1 else 0)
        if (classes != 1 || c.pointerCount != 1) return none(Suppression.UNSUPPORTED_GESTURE)
        if (c.positionTreatment == PositionTreatment.ALLOWED) {
            return Decision(Outcome.EMIT_POSITIONAL, null, POSITIONAL_FIELDS, true, COORDINATE_SPACE)
        }
        return Decision(Outcome.EMIT_REDACTED, null, REDACTED_FIELDS, false, null)
    }
}

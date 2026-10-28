package com.tracescout.rn

/**
 * MOBILE INTERACTION v1 — PRIVACY-BOUNDED TAP REPLAY.
 *
 * The Kotlin twin of `mobile-protocol/src/interaction-policy.cjs`, pinned to
 * `parity-vectors/interaction-policy-vectors.json`. A platform disagreement
 * fails on BYTES, not on three people reading one paragraph.
 *
 * THE ORDER OF THE RULES IS THE CONTRACT. Most-restrictive first, and every
 * unknown resolves to the more restrictive branch, so a state nobody
 * anticipated cannot fall through into "emit coordinates".
 *
 * v1 is ONE logical tap: single pointer, recorded at touch START. Not generic
 * pointer recording — no continuous move path, no keyboard or key events, no
 * text-change events, no gesture reconstruction from JS, and no coordinate
 * inferred from element geometry (an inferred coordinate is a guess wearing the
 * authority of a measurement).
 *
 * WHY TEXT ENTRY IS SUPPRESSED ENTIRELY, not just on secure fields: an
 * in-app custom keyboard is an ordinary part of the view tree, so taps on it
 * would otherwise be recorded as a key sequence with timing. While ANY editable
 * control holds focus, capture stops — making keystroke timing structurally
 * unavailable rather than merely filtered.
 */
object InteractionPolicy {

    enum class TargetClass(val wire: String) {
        SAFE("safe"), REDACTED("redacted"), EDITABLE("editable"),
        SECURE("secure"), UNKNOWN("unknown");

        companion object {
            /** Anything unrecognised is UNKNOWN, which the policy treats as redacted. */
            fun from(s: String?): TargetClass =
                entries.firstOrNull { it.wire == s } ?: UNKNOWN
        }
    }

    /**
 * `EMIT_SAFE` WAS RENAMED `EMIT_POSITIONAL`, and the name change is the
 * contract change. "Safe" invited the reading that the TARGET is safe —
 * i.e. that its content may be disclosed. It never meant that. It means
 * exactly one thing: this interaction may carry a POSITION. Content
 * authorisation is a separate dimension and is unaffected.
 */
    enum class Outcome(val wire: String) {
        EMIT_POSITIONAL("emit_positional"), EMIT_REDACTED("emit_redacted"), SUPPRESS("suppress")
    }

    /** Every reason a touch is suppressed. Closed set — never free text. */
    enum class Suppression(val wire: String) {
        NOT_CONTENT_WINDOW("not_content_window"),
        NEVER_RECORD("never_record"),
        TEXT_ENTRY_ACTIVE("text_entry_active"),
        EDITABLE_TARGET("editable_target"),
        SECURE_TARGET("secure_target"),
        UNSUPPORTED_GESTURE("unsupported_gesture"),
    }

    /**
 * COORDINATES ARE IN THE LOGICAL VIEWPORT SPACE the Viewer already uses
 * to place hierarchy and frame geometry — never raw Android pixels, iOS
 * points, bitmap pixels or CSS pixels without an explicit conversion. A
 * producer that has not proven its mapping must not send x/y at all.
 */
    const val COORDINATE_SPACE = "logical_viewport"

    /** The field set IS the boundary: what is not listed is absent, not merely discouraged. */
    val POSITIONAL_FIELDS = listOf("interaction", "gestureId", "phase", "x", "y",
        "pointerCount", "targetRole", "targetTestTag", "onRedactedTarget")

    /** NO COORDINATES, NO TARGET IDENTITY — either reconstructs the masked subject. */
    val REDACTED_FIELDS = listOf("interaction", "gestureId", "phase", "onRedactedTarget")

    /** May never appear on ANY interaction event, in any branch. */
    val FORBIDDEN_FIELDS = listOf("text", "value", "targetText", "targetValue", "label",
        "contentDescription", "keyCode", "key", "character", "inputText")

    data class Context(
        /**
 * The touch came from the app's OWN replay/content window. A producer
 * must never install a global input hook able to observe system
 * keyboard windows; this is how that is asserted.
 */
        val fromContentWindow: Boolean,
        val screenNeverRecord: Boolean,
        /** An editable control holds focus ANYWHERE in the app. */
        val textEntryActive: Boolean,
        val targetClass: TargetClass,
        val pointerCount: Int,
        /** A single-pointer tap, not a move path. */
        val isTap: Boolean,
        /**
 * The SECOND privacy dimension. Default REDACTED: no declaration means
 * no coordinates. Resolved per node by `PositionTreatment.resolve` and
 * NEVER inherited from a parent.
 *
 * LAST, AND DEFAULTED, DELIBERATELY. Adding it in the middle
 * renumbered every positional call site; a source-compatible addition
 * must not do that. Defaulting to REDACTED also means an existing
 * caller that has not been taught about position safety gets the SAFE
 * answer, not coordinates.
 */
        val positionTreatment: PositionTreatment = PositionTreatment.REDACTED,
        /**
 * A single-pointer MOVEMENT gesture — the sticky classification a
 * lifecycle takes once its touch travels beyond the tap slop, and keeps
 * for the rest of that touch even if the finger returns near where it
 * started.
 *
 * LAST AND DEFAULTED, for the same reason `positionTreatment` is: a
 * source-compatible addition must not renumber existing positional call
 * sites, and a caller that has not been taught about the movement lane
 * must get the pre-existing answer (a swipe suppressed as an
 * unsupported gesture), never a silently widened one.
 */
        val isSwipe: Boolean = false,
    )

    data class Decision(
        val outcome: Outcome,
        val reason: Suppression?,
        val fields: List<String>,
        val includeCoordinates: Boolean,
        val coordinateSpace: String?,
    )

    private fun none(r: Suppression) =
        Decision(Outcome.SUPPRESS, r, emptyList(), false, null)

    fun decide(c: Context): Decision {
        // 1. only the application's own content window
        if (!c.fromContentWindow) return none(Suppression.NOT_CONTENT_WINDOW)
        // 2. never-record emits NOTHING — enforced before serialization/queueing
        if (c.screenNeverRecord) return none(Suppression.NEVER_RECORD)
        // 3. any focused editable control stops capture
        if (c.textEntryActive) return none(Suppression.TEXT_ENTRY_ACTIVE)
        // 4. the target itself accepts typed text
        if (c.targetClass == TargetClass.SECURE) return none(Suppression.SECURE_TARGET)
        if (c.targetClass == TargetClass.EDITABLE) return none(Suppression.EDITABLE_TARGET)
        // 5. EXACTLY ONE single-pointer gesture class this producer can
        // truthfully name: a tap, or a swipe. Both true is a contradictory
        // input — one physical touch has one sticky classification — and a
        // contradiction fails closed rather than picking the permissive
        // reading, which would let a caller emit a tap row AND a swipe row
        // for one gesture.
        //
        // SCROLL / PAN / FLING REMAIN PROTOCOL VALUES THIS PRODUCER DOES
        // NOT CLAIM. The host UI moving is not evidence of which gesture the
        // finger performed.
        val classes = (if (c.isTap) 1 else 0) + (if (c.isSwipe) 1 else 0)
        if (classes != 1 || c.pointerCount != 1) return none(Suppression.UNSUPPORTED_GESTURE)
        // ── Every global suppression above runs BEFORE any coordinate is
        // considered, so a position declaration can never rescue a
        // never-record screen, a focused editable, or a secure target.
        //
        // 6. THE POSITION DIMENSION. Coordinates require an explicit,
        // node-local declaration and nothing else. There is deliberately no
        // branch here keyed on content treatment, component class, role or
        // "button-ness": those may restrict, never promote.
        //
        // A MASKED label with a declared position IS the case the product
        // needs — the two dimensions are independent, and `onRedactedTarget`
        // describes the POSITION channel, not the text channel.
        if (c.positionTreatment == PositionTreatment.ALLOWED) {
            return Decision(Outcome.EMIT_POSITIONAL, null, POSITIONAL_FIELDS, true, COORDINATE_SPACE)
        }
        // 7. Undeclared, or affirmatively position-blocked: the interaction is
        // still worth recording IN TIME, with nothing that locates it.
        return Decision(Outcome.EMIT_REDACTED, null, REDACTED_FIELDS, false, null)
    }

    /**
 * Whether a stored interaction may be drawn as a touch pulse. Only an event
 * that POSITIVELY carries coordinates — a redacted event has none by
 * construction, and drawing an approximate one would tell a viewer where a
 * masked thing was touched.
 */
    fun mayRenderPulse(onRedactedTarget: Boolean, x: Double?, y: Double?): Boolean {
        if (onRedactedTarget) return false
        return x != null && y != null && x.isFinite() && y.isFinite()
    }
}

package com.tracescout.mobile.capture.interaction

import com.tracescout.mobile.capture.interaction.InteractionPolicy.Outcome

/**
 * The ONE place a lifecycle observation meets the shared policy.
 *
 * It decides nothing of its own beyond two platform facts the policy cannot
 * see — "is there a route" and "is the point inside the captured viewport" —
 * and both can only make the answer MORE restrictive.
 */
object InteractionProducer {

    /** The server refuses a logical-viewport coordinate above this (`INT_COORD_MAX`). */
    const val COORDINATE_CEILING = 100_000f

    /**
 * An admitted interaction, shaped for the wire. The field set IS the
 * privacy boundary: there is nowhere to put text, a value, a label, a
 * content description, a view id or a key.
 */
    data class Emission(
        val interaction: String,
        /** Null for a tap row (one row, no phase — the RN Android tap shape). */
        val phase: String?,
        val gestureId: String,
        val screenId: String,
        val onRedactedTarget: Boolean,
        /** Both or neither. Present only on a positional emission. */
        val x: Float?,
        val y: Float?,
        val pointerCount: Int?,
        val monotonicNanos: Long,
        val wallMillis: Long,
    )

    sealed interface Result {
        data class Emit(val emission: Emission) : Result
        data class Refused(val reason: String) : Result
    }

    fun produce(p: GestureLifecycle.Persist): Result {
        val f = p.facts
        // No guessed route: without a screen there is no replayable subject.
        val screen = f.screenId?.takeIf { it.isNotEmpty() } ?: return Result.Refused("no_route")
        val d = InteractionPolicy.decide(InteractionPolicy.Context(
            fromContentWindow = f.fromContentWindow,
            screenNeverRecord = f.screenNeverRecord,
            textEntryActive = f.textEntryActive,
            targetClass = f.targetClass,
            pointerCount = 1,
            isTap = p.kind == GestureLifecycle.Kind.TAP,
            positionTreatment = f.positionTreatment,
            isSwipe = p.kind == GestureLifecycle.Kind.SWIPE,
        ))
        if (d.outcome == Outcome.SUPPRESS) return Result.Refused(d.reason?.wire ?: "suppressed")

        var positional = d.includeCoordinates
        val x = p.sample.x; val y = p.sample.y
        // NEVER CLAMP. A point we cannot place truthfully loses its POSITION,
        // not its existence — the interaction still happened, in time.
        if (positional && !(x.isFinite() && y.isFinite() && x >= 0f && y >= 0f &&
                f.viewportWidth > 0 && f.viewportHeight > 0 &&
                x <= f.viewportWidth && y <= f.viewportHeight &&
                x <= COORDINATE_CEILING && y <= COORDINATE_CEILING)) {
            positional = false
        }
        return Result.Emit(Emission(
            interaction = p.kind.wire,
            phase = p.phase?.wire,
            gestureId = p.gestureId,
            screenId = screen,
            onRedactedTarget = !positional,
            x = if (positional) x else null,
            y = if (positional) y else null,
            pointerCount = if (positional) 1 else null,
            monotonicNanos = p.sample.monotonicNanos,
            wallMillis = p.sample.wallMillis,
        ))
    }

    /**
 * The interaction-specific JSON members, in emission order. The envelope
 * (identity, sequence, `screen`, clocks, posture) is composed by the caller
 * from the SDK's one envelope builder; this owns only the payload.
 */
    fun payloadFields(e: Emission): List<String> = buildList {
        add("\"interaction\":\"${e.interaction}\"")
        e.phase?.let { add("\"phase\":\"$it\"") }
        add("\"gestureId\":\"${e.gestureId}\"")
        add("\"onRedactedTarget\":${e.onRedactedTarget}")
        if (e.x != null && e.y != null) {
            e.pointerCount?.let { add("\"pointerCount\":$it") }
            add("\"x\":${num(e.x)}")
            add("\"y\":${num(e.y)}")
        }
    }

    /** A finite float as a JSON number, never `NaN`/`Infinity`, never an exponent. */
    private fun num(v: Float): String =
        java.math.BigDecimal(v.toDouble()).setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
}

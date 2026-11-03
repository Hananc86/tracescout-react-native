package com.tracescout.mobile.capture.interaction

import com.tracescout.mobile.capture.interaction.InteractionPolicy.PositionTreatment
import com.tracescout.mobile.capture.interaction.InteractionPolicy.TargetClass

/**
 * A HOST-OBSERVED interaction, reported BY the embedder — the public producer
 * surface `:sdk` did not have.
 *
 * ## The gap this closes, measured rather than assumed
 *
 * `:sdk` already owns the envelope builder (`Envelope.interaction`, S4/an earlier change)
 * and the shared policy ([InteractionPolicy], pinned to the 36 parity vectors).
 * What it did not own was a way for a SEPARATE MODULE to hand it an
 * interaction: `TraceScoutMobile.recordInteraction` is `internal`, so the only
 * reachable producer was [InteractionCapture] — an `Activity` window callback
 * observing real `MotionEvent`s.
 *
 * That is sufficient for a native app and structurally insufficient for an
 * embedder:
 *
 * - a Cordova app's taps are DOM events inside one `SystemWebView`, so the
 * native view tree resolves every touch to that one `View` and the native
 * side cannot name the DOM target or the DOM route;
 * - the host already knows both, and already tells us the route through
 * `TraceScoutMobile.routeChanged`;
 * - an `internal` function is not callable across a Gradle module boundary at
 * all, so a plugin consuming the published AAR had no entry point and the
 * only alternatives were forking the envelope or duplicating the RN
 * emitter — both refused.
 *
 * So this is a REPORTING surface, not a second capture mechanism. It observes
 * nothing, installs nothing, and starts no session.
 *
 * ## A HOST-REPORTED INTERACTION CARRIES NO POSITION, BY CONSTRUCTION
 *
 * There is no `x`/`y` parameter and no way to add one through this path. A
 * coordinate on the wire is a point in the CAPTURED NATIVE VIEWPORT — the basis
 * the hierarchy and the frames use — and an embedder's coordinate is a point in
 * a layout the native side never measured (a WebView's CSS viewport, a Flutter
 * canvas). Accepting one would publish a number the renderer would place
 * confidently and wrongly, and nothing downstream could detect it.
 *
 * The policy already has the right answer for "position cannot be established":
 * [PositionTreatment.REDACTED] is its default and its restrictive branch, so
 * [InteractionPolicy.decide] resolves to [InteractionPolicy.Outcome.EMIT_REDACTED]
 * and the field set is exactly [InteractionPolicy.REDACTED_FIELDS] —
 * `interaction`, `gestureId`, `phase`, `onRedactedTarget`. A host-reported row
 * therefore says WHEN an interaction of WHICH CLASS happened on WHICH SCREEN,
 * and nothing about where it landed or what it touched. Every field in
 * [InteractionPolicy.FORBIDDEN_FIELDS] stays unrepresentable, as it is on the
 * native path.
 *
 * ## THE GESTURE ID IS BOUNDED HERE BECAUSE THE SERIALIZER DOES NOT ESCAPE IT
 *
 * MEASURED: `InteractionProducer.payloadFields` writes
 * `"gestureId":"${e.gestureId}"` with no escaping, and the protocol schema
 * bounds the field by `maxLength` only — no pattern. That is harmless while the
 * only producer MINTS the id (32 lowercase hex), and it stops being harmless the
 * moment a host supplies one: a value carrying a quote or a backslash would
 * emit a malformed document and `org.json` rejects the whole chunk. So a
 * host-supplied id must match [GESTURE_ID_SHAPE] — a charset that needs no
 * escaping and fits inside the schema's 64-character bound — and is otherwise
 * refused by name rather than repaired. An opaque grouping token loses nothing
 * to a charset restriction.
 *
 * ## What the host declares, and what it may not
 *
 * | fact | authority | why |
 * |---|---|---|
 * | gesture class + phase | host | it observed the gesture; bounded to the two classes the policy admits |
 * | gesture id | host (bounded) or minted | a swipe's rows must group; a tap is one row |
 * | screen | **SDK** | the route the host already named through `routeChanged` / `start(initialScreen)` |
 * | never-record | **SDK** | `ScreenPolicy` is the one authority; a host cannot talk its way past a declared screen |
 * | content window | **SDK** (always true) | the embedder's own window IS the content window; that suppression exists for touches observed from outside one, which cannot happen here |
 * | target class | **SDK** (always `UNKNOWN`) | the honest value, and the policy's fail-closed default: a host-reported row can never be classified `safe`, so nothing can promote it to positional later |
 * | position | **nobody** | not expressible — see above |
 * | text entry active | host | DISCLOSED LIMIT: on an embedder's surface the focused field is a DOM node, so the host is the only place this fact exists. A `true` adds the suppression; a `false` is merely the absence of a claim and relaxes nothing the SDK itself knows. |
 */
object HostInteraction {

    /** The two single-pointer classes [InteractionPolicy.decide] admits. */
    const val WIRE_TAP = "tap"
    const val WIRE_SWIPE = "swipe"

    /**
 * A host-supplied gesture id must be representable WITHOUT escaping and fit
 * the protocol's 64-character bound. See the serializer note above.
 */
    val GESTURE_ID_SHAPE = Regex("^[A-Za-z0-9_-]{1,64}$")

    const val REFUSED_NOT_ENABLED = "host_interactions_not_enabled"
    const val REFUSED_NO_SESSION = "no_session"

    /**
 * The SDK itself faulted — never a statement about the host's request.
 *
 * IT IS DISTINCT FROM [REFUSED_NO_SESSION] ON PURPOSE. The entry point
 * runs inside a never-throw guard whose caught-exception value is `null`,
 * and folding that into `no_session` tells a host that no session exists —
 * so a host doing the reasonable thing would WAIT for one that is already
 * there, and an SDK fault would read as ordinary lifecycle.
 */
    const val REFUSED_INTERNAL_ERROR = "internal_error"
    const val REFUSED_UNSUPPORTED_GESTURE = "unsupported_gesture"
    const val REFUSED_PHASE_ON_TAP = "phase_not_allowed_on_tap"
    const val REFUSED_PHASE_REQUIRED = "phase_required_on_swipe"
    const val REFUSED_UNSUPPORTED_PHASE = "unsupported_phase"
    const val REFUSED_GESTURE_ID = "gesture_id_not_representable"

    /** The typed answer a host logs instead of assuming success. */
    sealed interface Decision {
        /**
 * The row passed the policy and was handed to the SDK's queue writer.
 *
 * NOT A DELIVERY RECEIPT. The writer can still drop an unnumbered
 * record (authority missing or the queue recovery-blocked) and the
 * durable append runs on a worker thread, so storage and upload are
 * both outside what this value can promise. [gestureId] is the id that
 * was handed on, which is the id a stored row would carry.
 */
        data class Recorded(val gestureId: String) : Decision
        /** Nothing was queued. [reason] is a stable wire word, never content. */
        data class Refused(val reason: String) : Decision
    }

    /** What [prepare] produced: one observation for the shared producer, or a refusal. */
    sealed interface Prepared {
        data class Observation(val persist: GestureLifecycle.Persist) : Prepared
        data class Refused(val reason: String) : Prepared
    }

    /**
 * The phases a swipe may carry, exactly [GestureLifecycle.Phase]'s wire
 * words — never a second vocabulary.
 */
    private fun phaseOf(wire: String): GestureLifecycle.Phase? =
        GestureLifecycle.Phase.entries.firstOrNull { it.wire == wire }

    /**
 * Shape ONE host-reported observation for [InteractionProducer.produce].
 *
 * Pure: no Android type, no clock of its own, no queue. Every refusal is a
 * stable word so a host can act on it; nothing is silently repaired.
 *
 * It builds a [GestureLifecycle.Persist] DIRECTLY and deliberately does
 * not run [GestureLifecycle]. That state machine classifies raw touch
 * samples into gestures — the host already did that on samples we never
 * saw, so running a second recognizer over its conclusions would invent a
 * lifecycle rather than record one.
 */
    fun prepare(
        interaction: String?,
        phase: String?,
        gestureId: String?,
        screenId: String?,
        screenNeverRecord: Boolean,
        textEntryActive: Boolean,
        monotonicNanos: Long,
        wallMillis: Long,
        newGestureId: () -> String,
    ): Prepared {
        val kind = when (interaction) {
            WIRE_TAP -> GestureLifecycle.Kind.TAP
            WIRE_SWIPE -> GestureLifecycle.Kind.SWIPE
            // Every other protocol value (pan, pinch, scroll, fling, …) is a
            // class this producer has not measured; the policy refuses it with
            // the same word, so the boundary uses the policy's word.
            else -> return Prepared.Refused(REFUSED_UNSUPPORTED_GESTURE)
        }

        // A tap row has NO phase and a swipe row always has one — the shape the
        // two shipped producers emit. A mismatch is refused, never defaulted:
        // defaulting a swipe to `begin` would fabricate a lifecycle.
        val resolvedPhase: GestureLifecycle.Phase? = when (kind) {
            GestureLifecycle.Kind.TAP ->
                if (phase != null) return Prepared.Refused(REFUSED_PHASE_ON_TAP) else null
            GestureLifecycle.Kind.SWIPE -> {
                if (phase == null) return Prepared.Refused(REFUSED_PHASE_REQUIRED)
                phaseOf(phase) ?: return Prepared.Refused(REFUSED_UNSUPPORTED_PHASE)
            }
        }

        val id = when {
            gestureId == null -> newGestureId()
            GESTURE_ID_SHAPE.matches(gestureId) -> gestureId
            else -> return Prepared.Refused(REFUSED_GESTURE_ID)
        }

        return Prepared.Observation(
            GestureLifecycle.Persist(
                kind = kind,
                phase = resolvedPhase,
                gestureId = id,
                sample = GestureLifecycle.TouchSample(
                    // The action is the producer's input only through the
                    // lifecycle, which this path does not run; a single
                    // pointer is the one thing the policy requires.
                    action = GestureLifecycle.Action.UP,
                    pointerId = 0,
                    // NOT A POSITION. `InteractionProducer` reads x/y only
                    // when the decision includes coordinates, and a redacted
                    // decision never does — so these are unreachable and the
                    // emission's x/y/pointerCount come out null. They are NaN
                    // rather than 0 so that any future path which did consult
                    // them would fail the producer's own finiteness guard
                    // instead of publishing the origin as a real point.
                    x = Float.NaN,
                    y = Float.NaN,
                    monotonicNanos = monotonicNanos,
                    wallMillis = wallMillis,
                ),
                facts = GestureLifecycle.SampleFacts(
                    screenId = screenId,
                    screenNeverRecord = screenNeverRecord,
                    textEntryActive = textEntryActive,
                    fromContentWindow = true,
                    targetClass = TargetClass.UNKNOWN,
                    positionTreatment = PositionTreatment.REDACTED,
                    // Zero on purpose: a viewport is only consulted to bound a
                    // coordinate, and this path has none to bound.
                    viewportWidth = 0,
                    viewportHeight = 0,
                ),
            )
        )
    }
}

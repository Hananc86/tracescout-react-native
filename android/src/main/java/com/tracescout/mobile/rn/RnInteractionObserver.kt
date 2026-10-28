package com.tracescout.mobile.rn

import android.app.Activity
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.tracescout.rn.TapRecognizer

/**
 * The observer the seam hands events to: recognise one logical tap, take the
 * fail-closed DOWN and UP snapshots, and ask the shared policy once.
 *
 * NO DURABLE EMISSION. This deliberately stops at the policy decision. The
 * producer is wired only after the coordinate proof — emitting now, merely to
 * make the privacy matrix measurable, would be shipping an unproven coordinate
 * to make a test non-vacuous.
 */
internal class RnInteractionObserver(
    private val screenIdProvider: () -> String?,
    private val neverRecordProvider: (String?) -> Boolean,
    private val captureEnabled: (Activity) -> Boolean,
    /**
 * Emits one admitted interaction. Separated so the observer can be driven
 * without a transport in unit tests, and so the ONLY path to the durable
 * queue is this single call site.
 */
    private val emit: (
        Activity,
        RnInteractionDecider.Final,
        RnInteractionDecider.Snapshot,
        com.tracescout.rn.InteractionKind,
    ) -> Unit,
) : RnInteractionCapture.Observer {

    private var recognizer: TapRecognizer? = null
    private var down: RnInteractionDecider.Snapshot? = null
    /**
 * ONE id per LOGICAL GESTURE, minted at ACTION_DOWN. It correlates a tap
 * with anything else the same gesture caused; it is not a target identity
 * and cannot locate anything.
 */
    private var gestureId: String = ""

    override fun onWindowTouch(activity: Activity, event: MotionEvent) {
        // The preference gates the CREATION of telemetry — never the seam, and
        // never the application's dispatch.
        if (!captureEnabled(activity)) return

        val r = recognizer ?: TapRecognizer(
            // THE PLATFORM'S OWN THRESHOLD, already density-corrected and
            // already what every Android view uses to separate a tap from a
            // scroll. An invented number would disagree with the app's own
            // gesture handling on some device, and the disagreement would look
            // like a capture bug rather than a units bug.
            ViewConfiguration.get(activity).scaledTouchSlop
        ).also { recognizer = it }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nanos = System.nanoTime()
                r.onDown(event.getPointerId(0), event.x, event.y, nanos)
                gestureId = java.util.UUID.randomUUID().toString().take(32)
                val screen = screenIdProvider()
                down = RnInteractionDecider.snapshot(
                    activity, event, screen, neverRecordProvider(screen), gestureId, nanos)
            }
            MotionEvent.ACTION_POINTER_DOWN -> r.onExtraPointer()
            MotionEvent.ACTION_MOVE -> r.onMove(event.getPointerId(0), event.x, event.y)
            MotionEvent.ACTION_CANCEL -> { r.onCancel(); down = null }
            MotionEvent.ACTION_UP -> {
                val result = r.onUp(event.getPointerId(0), event.x, event.y)
                val d = down; down = null
                // THE CLASS COMES FROM THE RECOGNISER AND NOWHERE ELSE, and
                // the `when` is EXHAUSTIVE over the two admitted results, so a
                // third class added to the recogniser would fail to compile
                // here rather than being silently dropped — which is how the
                // swipe lane stayed unreachable while every layer below it was
                // already written: this line read `!is Result.Tap -> return`,
                // so a correctly recognised swipe was discarded at its release
                // point with nothing recording that it had been observed.
                val kind = when (result) {
                    is TapRecognizer.Result.Tap -> com.tracescout.rn.InteractionKind.TAP
                    is TapRecognizer.Result.Swipe -> com.tracescout.rn.InteractionKind.SWIPE
                    else -> return
                }
                if (d == null) return
                // THE UP SNAPSHOT IS TAKEN BEFORE THE APP RECEIVES THE UP.
                // Observation runs first in the wrapper, so the state here is
                // the state at the moment the gesture completed — not whatever
                // the app's own UP handler has since changed it to.
                val screen = screenIdProvider()
                val up = RnInteractionDecider.snapshot(
                    activity, event, screen, neverRecordProvider(screen),
                    gestureId, d.downMonotonicNanos)
                val decision = RnInteractionDecider.decide(d, up, kind)
                // FINALISED BEFORE ANYTHING IS ALLOCATED. Sequence is issued
                // inside RnTransport.enqueue, in the same critical section as
                // the append — so a decision that never reaches enqueue
                // consumes no sequence, and a suppressed private touch creates
                // no unexplained hole in the replay stream. The final shape is
                // therefore settled HERE, not mutated downstream.
                val fin = RnInteractionDecider.finalise(decision, d)
                // SUPPRESS RETURNS HERE, BEFORE ANY ALLOCATION. Sequence is
                // issued inside RnTransport.enqueue in the same critical
                // section as the append, so a path that never reaches enqueue
                // consumes no number — and a private touch leaves no gap in the
                // replay stream announcing that something was observed and
                // withheld.
                if (fin.outcome == com.tracescout.rn.InteractionPolicy.Outcome.SUPPRESS) return
                emit(activity, fin, d, kind)
            }
        }
    }

    /**
 * FAIL CLOSED FOR TELEMETRY, FAIL OPEN FOR THE APPLICATION. A failure in
 * hit-testing, the snapshot or the policy call drops the gesture rather
 * than guessing at it; the seam has already guaranteed the application's
 * event is delegated regardless.
 */
    override fun onObservationFailure(t: Throwable) {
        recognizer?.reset()
        down = null
    }
}

package com.tracescout.rn

/**
 * The v1 tap lifecycle, as a PURE state machine.
 *
 * Deliberately free of `MotionEvent` and of every Android type: the recognizer
 * is the part with the interesting logic, and a rule that can only be exercised
 * on a device is a rule that gets exercised once.
 *
 * IT HOLDS NO EVENT. The state below is the minimum needed to classify the
 * CURRENT gesture — pointer id, down position, down time, maximum displacement,
 * and two flags. A `MotionEvent` is a recycled, mutable object owned by the
 * platform; retaining one is both a use-after-recycle bug and a way for raw
 * input to reach durable state. Nothing here is written to disk or logged.
 *
 * v1 recognises the TWO single-pointer gesture classes the frozen interaction
 * policy admits — a tap and a swipe — and nothing else. Scroll, pan, fling,
 * pinch, rotate and velocity are all representable in the protocol and are all
 * deliberately NOT recorded: a schema field is not a reason to collect
 * something, and the host UI moving is not evidence of which gesture the finger
 * performed.
 *
 * A SWIPE IS THE SAME LIFECYCLE AS A TAP, CLASSIFIED DIFFERENTLY — never a
 * second recogniser and never a path. It carries exactly what a tap carries:
 * the DOWN position and the DOWN time. No end point, no intermediate sample, no
 * direction, no distance and no velocity is retained or emitted, because none
 * of those is needed to say "a swipe happened here, then" and each of them
 * locates the gesture more precisely than the single point the policy's
 * position dimension authorises.
 */
class TapRecognizer(
    /**
 * `ViewConfiguration.get(context).scaledTouchSlop` — the PLATFORM's own
 * notion of "the finger did not really move", already density-corrected and
 * already what every Android view uses to separate a tap from a scroll.
 * Inventing a pixel number here would disagree with the app's own gesture
 * handling on some device, and the disagreement would look like a capture
 * bug rather than a units bug.
 */
    private val touchSlopPx: Int,
) {

    enum class Reject(val wire: String) {
        CANCELLED("cancelled"),
        MULTI_POINTER("multi_pointer"),
        /**
 * RETAINED VOCABULARY, NO LONGER PRODUCED BY `onUp`. Passing the slop
 * used to be a rejection, because a tap was the only class this
 * producer could truthfully name; it is now the SWIPE threshold and
 * `onUp` answers [Result.Swipe] instead. The value is kept rather than
 * deleted because it is the wire name of a refusal reason and removing
 * it is a wider change than the one authorised — but a test pins that
 * `onUp` never returns it, so the vocabulary cannot rot into something
 * a reader believes is still reachable.
 */
        MOVED_BEYOND_SLOP("moved_beyond_slop"),
        NO_DOWN("no_down"),
        POINTER_CHANGED("pointer_changed"),
    }

    sealed interface Result {
        /** Not a decision yet — the gesture is still in flight. */
        data object Pending : Result

        /** Exactly one logical tap. `downMonotonicNanos` is the replay time. */
        data class Tap(
            val downX: Float,
            val downY: Float,
            val downMonotonicNanos: Long,
        ) : Result

        /**
 * Exactly one single-pointer MOVEMENT gesture.
 *
 * IDENTICAL PAYLOAD TO [Tap], DELIBERATELY. The replay position and
 * time are the DOWN — where the user aimed and when — exactly as for a
 * tap. Adding an end point, a displacement or a duration here would be
 * new locating information arriving through the gesture class rather
 * than through the policy's position dimension, which is the one place
 * entitled to grant it.
 */
        data class Swipe(
            val downX: Float,
            val downY: Float,
            val downMonotonicNanos: Long,
        ) : Result

        data class Rejected(val reason: Reject) : Result
    }

    private var active = false
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var downNanos = 0L

    /**
 * THE TWO STICKY FACTS ARE SEPARATE FIELDS, AND THAT SEPARATION IS THE
 * CORRECTNESS ARGUMENT.
 *
 * The single-slot `poisoned` this replaced was last-write-wins, so the
 * ORDER of two unrelated events decided the verdict: a second finger
 * followed by movement overwrote `MULTI_POINTER` with `MOVED_BEYOND_SLOP`.
 * While passing the slop was itself a rejection that was harmless — both
 * values refused. The moment movement became an ADMISSION it stopped being
 * harmless: a two-finger gesture that moves would have been reported as a
 * supported single-pointer swipe, which the frozen policy refuses by two
 * parity vectors and which no amount of care in `onUp` could recover,
 * because the information had already been overwritten.
 *
 * [disqualified] can never be cleared by movement because movement cannot
 * write to it.
 */
    private var disqualified: Reject? = null
    /** Sticky: the touch passed the slop at some point in this lifecycle. */
    private var moved = false

    fun onDown(pointerId: Int, x: Float, y: Float, monotonicNanos: Long) {
        active = true
        this.pointerId = pointerId
        downX = x; downY = y; downNanos = monotonicNanos
        disqualified = null
        moved = false
    }

    /**
 * THE MOVED FLAG IS STICKY, AND THAT IS THE WHOLE MECHANISM. Once a
 * gesture has passed the slop it can never become a tap again by the finger
 * returning near its origin — a drag out and back is a swipe, not a tap.
 * There is deliberately no `else` clearing [moved]: that single missing
 * branch is the difference between a swipe and a false tap.
 *
 * An earlier version also tracked a running maximum displacement. It was
 * REMOVED: given stickiness it changed no outcome, and retained state that
 * looks load-bearing and is not misleads the next reader into thinking it
 * protects something.
 */
    fun onMove(pointerId: Int, x: Float, y: Float) {
        if (!active) return
        if (pointerId != this.pointerId) { disqualified = Reject.POINTER_CHANGED; return }
        val dx = x - downX; val dy = y - downY
        if (dx * dx + dy * dy > touchSlopPx.toFloat() * touchSlopPx) {
            moved = true
        }
    }

    /**
 * A second finger ends the gesture's eligibility permanently — for BOTH
 * classes. A two-finger swipe is not a supported gesture, and the frozen
 * policy refuses it independently on `pointerCount`; this is the producer
 * side of the same rule, so the refusal does not depend on one layer alone.
 */
    fun onExtraPointer() { if (active) disqualified = Reject.MULTI_POINTER }

    fun onCancel(): Result {
        val had = active
        reset()
        return if (had) Result.Rejected(Reject.CANCELLED) else Result.Rejected(Reject.NO_DOWN)
    }

    /**
 * THE ORDER IS THE CONTRACT, AND IT IS DISQUALIFIERS FIRST.
 *
 * A disqualified lifecycle is refused before the class is considered, so no
 * movement can promote a cancelled, multi-pointer or pointer-changed
 * gesture into a swipe. Only after every refusal has had its chance does
 * the remaining question get asked, and it has exactly two answers.
 */
    fun onUp(pointerId: Int, x: Float, y: Float): Result {
        if (!active) { reset(); return Result.Rejected(Reject.NO_DOWN) }
        val d = disqualified
        if (d != null) { reset(); return Result.Rejected(d) }
        if (pointerId != this.pointerId) { reset(); return Result.Rejected(Reject.POINTER_CHANGED) }
        // The UP position matters too: a slow drag can pass slop between the
        // last MOVE sample and UP, and the platform does not guarantee a MOVE
        // for every pixel. Without this a sparsely-sampled drag would be
        // classified a tap.
        val dx = x - downX; val dy = y - downY
        val passedSlopAtUp = dx * dx + dy * dy > touchSlopPx.toFloat() * touchSlopPx
        val r =
            if (moved || passedSlopAtUp) Result.Swipe(downX, downY, downNanos)
            else Result.Tap(downX, downY, downNanos)
        reset()
        return r
    }

    fun reset() {
        active = false; pointerId = -1; disqualified = null; moved = false
        downX = 0f; downY = 0f; downNanos = 0L
    }

    val isActive: Boolean get() = active
}

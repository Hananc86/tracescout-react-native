package com.tracescout.mobile.rn

import android.app.Activity
import android.view.MotionEvent
import com.tracescout.rn.InteractionPolicy
import com.tracescout.rn.InteractionTargetClass
import com.tracescout.rn.PositionTreatment
import com.tracescout.rn.ReplayCoordinates
import com.tracescout.rn.Treatment

/**
 * Turns an observed gesture into ONE shared-policy decision.
 *
 * It decides nothing itself. Every rule lives in `InteractionPolicy` (#124);
 * this assembles the facts, applies the fail-closed DOWN/UP join, and hands the
 * policy's own output onward. An adapter that restated the decision tree would
 * be a second policy, and the two would disagree exactly once.
 */
/**
 * The probe surface, at package level so the FIXTURE can implement it without
 * `RnInteractionDecider` itself becoming public. Production code never
 * registers one.
 */
interface RnInteractionDeciderProbe {
    fun onDecision(
        screenId: String?,
        targetFound: Boolean,
        contentTreatment: Treatment?,
        positionTreatment: PositionTreatment,
        focusedEditable: Boolean,
        targetEditable: Boolean,
        targetSecure: Boolean,
        neverRecord: Boolean,
        outcome: InteractionPolicy.Outcome,
        suppression: InteractionPolicy.Suppression?,
        includeCoordinates: Boolean,
    )
}

/**
 * UAT-only registration point. `internal` state, public setter, so a customer
 * build has no way to attach one from outside this module except deliberately.
 */
object RnInteractionUat {
    @Volatile var probe: RnInteractionDeciderProbe? = null

    /**
 * ISOLATES THE SECURE DEFENCE, AND NOTHING ELSE.
 *
 * Tapping a secure field focuses it, so `text_entry_active` — which sits
 * ABOVE `secure_target` in the frozen order — can fire first and mask a
 * missing secure rule entirely. This forces that ONE boolean false at the
 * snapshot boundary so the secure defence has to stand on its own.
 *
 * It alters the focus signal ONLY. Target classification, hit-testing, the
 * position declaration, Never-Record and gesture validity are untouched —
 * a hook that could reach any of those would be proving a different system.
 */
    @Volatile var forceFocusedEditableFalse = false

    /**
 * Fail-injection for the seam non-interference gate: `hit_test`,
 * `snapshot`, `policy`, or null. UAT only; production never sets it.
 */
    @Volatile var injectFailure: String? = null

    /**
 * UAT-ONLY GEOMETRY REPORTING, for the C6 raw coordinate inventory.
 *
 * SEPARATE FROM THE DECISION PROBE, DELIBERATELY. The decision probe is
 * a privacy surface and must never learn to carry a coordinate. This one
 * exists solely to MEASURE, is registered only by the fixture, and is
 * consumed before any transform exists — the inventory is measurements, not
 * a conversion.
 *
 * It carries the RESOLVED TARGET'S bounds because those are the oracle a
 * later transform is checked against; it carries no target identity, no
 * nativeID and no declaration token.
 */
    interface GeometryProbe {
        fun onGeometry(
            screenId: String?,
            targetLeft: Int, targetTop: Int, targetWidth: Int, targetHeight: Int,
            positionAllowed: Boolean,
            /**
 * ONE JSON-FREE BAG OF INTS, captured on the REAL observer path.
 * The first version captured window geometry inside the fixture's
 * own observer — which was never in the chain, because
 * `RnInteractionCapture.install` is idempotent and the production
 * observer had already been installed. Every geometry field came
 * back null while the decision fields were correct, so the rows
 * looked measured and were not.
 */
            geom: Map<String, Int>,
            eventX: Float, eventY: Float, rawX: Float, rawY: Float,
            /**
 * The PRODUCTION conversion's output, or null when it refused.
 * Recorded so a certification compares the real function's answer
 * rather than a harness re-derivation of the same arithmetic.
 */
            replayX: Float?, replayY: Float?,
        )
    }

    @Volatile var geometryProbe: GeometryProbe? = null

    /**
 * UAT-ONLY: the durable queue's PLAINTEXT records, read through the
 * production decrypt path. Returns null when the store could not be read —
 * "could not read" and "no records" are different answers and must not
 * collapse into one.
 */
    fun uatQueueRecords(ctx: android.content.Context): List<String>? = runCatching {
        val r = RnTransport.uatReadAll(ctx)
        r.records.map { String(it, Charsets.UTF_8) }
    }.getOrNull()
}

internal object RnInteractionDecider {

    /**
 * EVALUATED AT BOTH ENDS OF THE GESTURE, AND JOINED MOST-RESTRICTIVELY.
 *
 * A single snapshot at DOWN is not enough: state can change while the
 * finger is down, and every one of those changes must be able to make the
 * outcome stricter but never looser.
 *
 * safe at DOWN, editable or secure at UP -> SUPPRESS
 * positional at DOWN, undeclared/unknown at UP -> no coordinates
 * ordinary screen at DOWN, never-record at UP -> SUPPRESS
 * focus acquired by an editable mid-gesture -> SUPPRESS
 *
 * The DOWN screen and DOWN monotonic time remain the interaction's replay
 * ownership point for a valid tap — where the user aimed and when.
 */
    internal data class Snapshot(
        /**
 * Identity and time of the gesture, fixed at ACTION_DOWN — where the
 * user aimed and when. The UP snapshot carries its own copies but the
 * DOWN one is authoritative for replay ownership.
 */
        val gestureId: String = "",
        val downMonotonicNanos: Long = 0L,
        /** UAT geometry captured at the same instant as the privacy facts. */
        val geom: Map<String, Int> = emptyMap(),
        val eventX: Float = 0f, val eventY: Float = 0f,
        val rawX: Float = 0f, val rawY: Float = 0f,
        val fromContentWindow: Boolean,
        val screenId: String?,
        val neverRecord: Boolean,
        val focusedEditable: Boolean,
        val target: RnInteractionTarget.Resolved,
    ) {
        companion object {
            /** The fail-closed value: used when a snapshot could not be taken. */
            val CLOSED = Snapshot(
                fromContentWindow = false, screenId = null, neverRecord = true,
                focusedEditable = true, target = RnInteractionTarget.Resolved.UNRESOLVED)
        }
    }

    fun snapshot(
        activity: Activity, e: MotionEvent, screenId: String?, neverRecord: Boolean,
        gestureId: String = "", downMonotonicNanos: Long = 0L,
    ): Snapshot {
        if (RnInteractionUat.injectFailure == "snapshot") error("injected snapshot failure")
        val target =
            if (RnInteractionUat.injectFailure == "hit_test") error("injected hit-test failure")
            else RnInteractionTarget.resolve(activity, e.x, e.y)
        return Snapshot(
            gestureId = gestureId,
            downMonotonicNanos = downMonotonicNanos,
            geom = if (RnInteractionUat.geometryProbe != null) windowGeometry(activity) else emptyMap(),
            eventX = e.x, eventY = e.y, rawX = e.rawX, rawY = e.rawY,
            fromContentWindow = true,   // the seam only ever sees the app's own window
            screenId = screenId,
            neverRecord = neverRecord,
            focusedEditable = if (RnInteractionUat.forceFocusedEditableFalse) false
                              else RnInteractionTarget.anyEditableFocused(activity),
            target = target,
        )
    }

    /**
 * The join. Each field takes its stricter value; the target class takes the
 * more restrictive of the two classes, and the position treatment the
 * stronger restriction.
 *
 * THE GESTURE CLASS IS AN INPUT, NOT A CONSTANT. `isTap = true` used to
 * be written here, which meant this function ASSERTED a tap for every
 * lifecycle the recogniser admitted — so the frozen policy's swipe lane was
 * unreachable from the producer no matter what the finger did, and
 * `isSwipe` could only ever take its default of false. The class now
 * arrives from [com.tracescout.rn.TapRecognizer], the one thing that
 * observed the touch.
 */
    fun join(
        down: Snapshot,
        up: Snapshot,
        kind: com.tracescout.rn.InteractionKind,
    ): InteractionPolicy.Context =
        InteractionPolicy.Context(
            fromContentWindow = down.fromContentWindow && up.fromContentWindow,
            screenNeverRecord = down.neverRecord || up.neverRecord,
            textEntryActive = down.focusedEditable || up.focusedEditable,
            targetClass = InteractionTargetClass.mostRestrictive(
                down.target.targetClass, up.target.targetClass),
            // ONE, BECAUSE THE RECOGNISER REFUSED EVERYTHING ELSE. A second
            // finger disqualifies the lifecycle permanently, so no multi-pointer
            // gesture reaches this function at all. It is stated rather than
            // read off the event because the event's pointer count at UP is not
            // the lifecycle's — a two-finger gesture whose second finger has
            // already lifted reports one.
            pointerCount = 1,
            // EXACTLY ONE OF THESE IS TRUE, BY CONSTRUCTION. The policy
            // fails closed on a contradiction (both true, or neither), and
            // deriving both from one closed enum is what makes that
            // unreachable from here rather than merely unlikely.
            isTap = kind == com.tracescout.rn.InteractionKind.TAP,
            isSwipe = kind == com.tracescout.rn.InteractionKind.SWIPE,
            positionTreatment = PositionTreatment.strongest(
                down.target.positionTreatment, up.target.positionTreatment),
        )

    /**
 * The UAT decision probe.
 *
 * NOT A SECOND TELEMETRY PATH. It is non-durable, reports only typed
 * enums and booleans, and exists so the privacy matrix can be NON-VACUOUS
 * before any producer is wired: "zero interaction events" is trivially true
 * when nothing emits, and a green built on that would be worthless.
 *
 * Everything it may carry is an enum, a boolean or a screen id. It carries
 * no coordinate, no `nativeID`, no declaration token, no target text, value
 * or label, no accessibility content, no view dump and no `MotionEvent`
 * string — the type of every field is the guarantee, not a review promise.
 */
    private val probe: RnInteractionDeciderProbe? get() = RnInteractionUat.probe

    /**
 * Evaluate and report. Returns the policy decision so a producer can later
 * consume the SAME object rather than re-deriving one.
 */
    fun decide(
        down: Snapshot,
        up: Snapshot,
        kind: com.tracescout.rn.InteractionKind,
    ): InteractionPolicy.Decision {
        if (RnInteractionUat.injectFailure == "policy") error("injected policy failure")
        val ctx = join(down, up, kind)
        val d = InteractionPolicy.decide(ctx)
        // UAT geometry, reported alongside the decision so the inventory row and
        // the policy result describe the SAME tap rather than two taps that
        // happened to look alike.
        RnInteractionUat.geometryProbe?.let { g ->
            runCatching {
                val converted = ReplayCoordinates.convert(
                    ReplayCoordinates.NativeTouchPoint(down.eventX, down.eventY),
                    ReplayCoordinates.ReplayViewport(
                        down.geom["decorW"] ?: 0, down.geom["decorH"] ?: 0))
                g.onGeometry(
                    screenId = down.screenId,
                    targetLeft = down.target.left, targetTop = down.target.top,
                    targetWidth = down.target.width, targetHeight = down.target.height,
                    positionAllowed = ctx.positionTreatment == PositionTreatment.ALLOWED,
                    geom = down.geom,
                    eventX = down.eventX, eventY = down.eventY,
                    rawX = down.rawX, rawY = down.rawY,
                    // THE REPLAY VIEWPORT IS THE APPLICATION WINDOW. The
                    // hierarchy expresses node bounds via getLocationInWindow,
                    // so that is the space a replay point must be in — the same
                    // semantic ground on which window-local coordinates were
                    // chosen over raw ones.
                    replayX = converted?.replayX, replayY = converted?.replayY,
                )
            }
        }
        probe?.let { p ->
            // A PROBE FAILURE MUST NOT CHANGE THE DECISION. It is reporting,
            // not deciding.
            runCatching {
                p.onDecision(
                    screenId = down.screenId,
                    targetFound = down.target.found && up.target.found,
                    contentTreatment = worst(down.target.contentTreatment, up.target.contentTreatment),
                    positionTreatment = ctx.positionTreatment,
                    focusedEditable = ctx.textEntryActive,
                    targetEditable = down.target.editable || up.target.editable,
                    targetSecure = down.target.secure || up.target.secure,
                    neverRecord = ctx.screenNeverRecord,
                    outcome = d.outcome,
                    suppression = d.reason,
                    includeCoordinates = d.includeCoordinates,
                )
            }
        }
        return d
    }

    /**
 * UAT-only window geometry, gathered ONLY when a geometry probe is
 * registered so a production build never walks the tree for it.
 *
 * The ReactRootView is located by CLASS NAME: React Native assigns it no
 * stable id, and a guessed one would silently report the wrong rectangle
 * forever — a measurement that is confidently wrong.
 */
    private fun windowGeometry(activity: Activity): Map<String, Int> = runCatching {
        val m = HashMap<String, Int>()
        val decor = activity.window.decorView
        m["decorW"] = decor.width; m["decorH"] = decor.height
        val content = activity.findViewById<android.view.View>(android.R.id.content)
        val loc = IntArray(2)
        if (content != null) {
            content.getLocationInWindow(loc)
            m["contentX"] = loc[0]; m["contentY"] = loc[1]
            m["contentW"] = content.width; m["contentH"] = content.height
        }
        var rrv: android.view.View? = null
        fun walk(v: android.view.View) {
            if (rrv != null) return
            if (v.javaClass.simpleName.contains("ReactRootView")) { rrv = v; return }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(decor)
        rrv?.let { r ->
            val rl = IntArray(2); r.getLocationInWindow(rl)
            m["rrvX"] = rl[0]; m["rrvY"] = rl[1]; m["rrvW"] = r.width; m["rrvH"] = r.height
        }
        val dm = activity.resources.displayMetrics
        m["screenW"] = dm.widthPixels; m["screenH"] = dm.heightPixels
        // THE ELIGIBILITY INPUTS, measured from platform metrics only. The
        // certified class is "the window IS the display" — a relationship
        // between two runtime rectangles, never a list of device dimensions.
        m["displayW"] = dm.widthPixels; m["displayH"] = dm.heightPixels
        val wl = IntArray(2); decor.getLocationOnScreen(wl)
        m["windowLeft"] = wl[0]; m["windowTop"] = wl[1]

        // CANDIDATE FULL-DISPLAY SOURCES, MEASURED BEFORE ANYTHING DEPENDS
        // ON THEM. `DisplayMetrics` reports the app-USABLE area — on API 26 it
        // is 1080x1794 against a fullscreen decor of 1080x1920, because the
        // navigation bar is excluded. Using it as "the display" made a genuinely
        // fullscreen window look REDUCED and silently downgraded every API-26
        // positional interaction.
        //
        // `maximumWindowMetrics` is the documented full-display reference and
        // `getRealSize` its pre-30 equivalent — but the names sounding right is
        // not evidence. Both are recorded here so their behaviour in freeform
        // can be checked against the current window before the predicate is
        // allowed to consume either.
        runCatching {
            val wm = activity.getSystemService(android.content.Context.WINDOW_SERVICE)
                as android.view.WindowManager
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val maxB = wm.maximumWindowMetrics.bounds
                m["maxDisplayW"] = maxB.width(); m["maxDisplayH"] = maxB.height()
                val curB = wm.currentWindowMetrics.bounds
                m["curWindowW"] = curB.width(); m["curWindowH"] = curB.height()
            } else {
                @Suppress("DEPRECATION")
                run {
                    val pt = android.graphics.Point()
                    wm.defaultDisplay.getRealSize(pt)
                    m["maxDisplayW"] = pt.x; m["maxDisplayH"] = pt.y
                }
            }
        }
        // Defence in depth only — the rectangle relation stays the primary rule.
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                m["multiWindow"] = if (activity.isInMultiWindowMode) 1 else 0
            }
        }
        m["densityDpi"] = dm.densityDpi
        val insets = decor.rootWindowInsets
        if (insets != null) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                m["insetTop"] = b.top; m["insetBottom"] = b.bottom
                m["insetLeft"] = b.left; m["insetRight"] = b.right
            } else {
                @Suppress("DEPRECATION")
                run {
                    m["insetTop"] = insets.systemWindowInsetTop
                    m["insetBottom"] = insets.systemWindowInsetBottom
                    m["insetLeft"] = insets.systemWindowInsetLeft
                    m["insetRight"] = insets.systemWindowInsetRight
                }
            }
        }
        m
    }.getOrDefault(emptyMap())

    /**
 * The FINAL shape of an interaction, decided BEFORE anything is allocated.
 *
 * THE ORDER IS THE CONTRACT: recognise the tap, join DOWN/UP privacy,
 * ask #124, and only then — if the answer is POSITIONAL — check that the
 * window geometry is inside the certified class and run the conversion. A
 * positional event is never allocated and then mutated into a redacted one
 * downstream, and no intermediate coordinate-bearing state is persisted.
 */
    internal data class Final(
        val outcome: InteractionPolicy.Outcome,
        val replayX: Float?,
        val replayY: Float?,
        /** Set when POSITIONAL was downgraded. Typed, never free text. */
        val geometryRefusal: ReplayCoordinates.GeometryIneligibility?,
        val conversionRefused: Boolean,
    )

    /**
 * `POSITIONAL GEOMETRY v1: FULLSCREEN-ELIGIBLE OR COORDINATE-FREE`.
 *
 * DOCUMENTING THE MULTI-WINDOW LIMITATION IS NOT ENFORCING IT. The
 * coordinate mapping is certified for full-screen Activity windows; a
 * measured freeform run produced `event.x/y` that were not window-local
 * relative to the window origin. Without this downgrade a tap on a
 * `ts-position-safe` target in split-screen would emit a confidently wrong
 * coordinate — a false pulse, which is worse than no pulse.
 *
 * The interaction still exists in time; it simply carries no position.
 */
    fun finalise(d: InteractionPolicy.Decision, down: Snapshot): Final {
        if (d.outcome != InteractionPolicy.Outcome.EMIT_POSITIONAL) {
            return Final(d.outcome, null, null, null, false)
        }
        val g = ReplayCoordinates.WindowGeometry(
            windowLeft = down.geom["windowLeft"] ?: 0,
            windowTop = down.geom["windowTop"] ?: 0,
            windowWidth = down.geom["decorW"] ?: 0,
            windowHeight = down.geom["decorH"] ?: 0,
            // The FULL display, from maximumWindowMetrics / getRealSize —
            // never DisplayMetrics (app-usable) and never currentWindowMetrics
            // (the window itself). Both were measured to collapse in freeform.
            displayWidth = down.geom["maxDisplayW"] ?: 0,
            displayHeight = down.geom["maxDisplayH"] ?: 0,
            multiWindow = (down.geom["multiWindow"] ?: 0) == 1,
        )
        val refusal = ReplayCoordinates.positionalEligibility(g)
        if (refusal != null) {
            return Final(InteractionPolicy.Outcome.EMIT_REDACTED, null, null, refusal, false)
        }
        val p = ReplayCoordinates.convert(
            ReplayCoordinates.NativeTouchPoint(down.eventX, down.eventY),
            ReplayCoordinates.ReplayViewport(g.windowWidth, g.windowHeight),
        ) ?: return Final(InteractionPolicy.Outcome.EMIT_REDACTED, null, null, null, true)
        return Final(InteractionPolicy.Outcome.EMIT_POSITIONAL, p.replayX, p.replayY, null, false)
    }

    /** Null is the weakest claim here, so an unknown side cannot loosen. */
    private fun worst(a: Treatment?, b: Treatment?): Treatment? = when {
        a == null -> b
        b == null -> a
        else -> Treatment.strongest(a, b)
    }
}

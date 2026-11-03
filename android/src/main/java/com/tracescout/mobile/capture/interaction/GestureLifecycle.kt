package com.tracescout.mobile.capture.interaction

import com.tracescout.mobile.capture.interaction.InteractionPolicy.PositionTreatment
import com.tracescout.mobile.capture.interaction.InteractionPolicy.TargetClass

/**
 * ONE PHYSICAL TOUCH, ONE STICKY CLASSIFICATION — the Android native lifecycle.
 *
 * Platform-neutral on purpose: it consumes [TouchSample]s and per-sample
 * [SampleFacts], never a `MotionEvent` or a `View`, so every rule here runs on
 * the JVM and the Android glue ([InteractionCapture]) stays a thin adapter.
 *
 * Semantics follow the two shipped producers, without inventing a third:
 *
 * - **tap** (RN Android `TapRecognizer` + `RnInteractionDecider`): ONE row at
 * release, carrying the DOWN position and DOWN time — where the user aimed
 * and when — and the DOWN/UP facts JOINED most-restrictively. No phase:
 * a tap row is byte-shaped exactly like the RN Android tap row.
 * - **swipe** (RN iOS `InteractionGestureLifecycle`, certified physical):
 * nothing is persisted until the touch passes the platform touch slop; at
 * that moment the withheld BEGIN is materialised from the ORIGINAL down,
 * then bounded MOVE samples, then an END (never throttled) or a CANCEL.
 * Stickiness: once a swipe, never a tap again.
 *
 * TWO AUTHORITIES (the iOS build-23 lesson). Screen, never-record,
 * text-entry and window are LIFECYCLE authority — a change mid-gesture fails
 * closed and nothing more is persisted, not even the END. Target class and
 * position treatment are PER SAMPLE; the coordinate authority may only tighten.
 *
 * A SECOND POINTER ENDS ELIGIBILITY for both classes (pointerCount must be
 * 1). A swipe that has already been persisted is closed with a CANCEL rather
 * than left unterminated; a tap candidate just disappears.
 */
class GestureLifecycle(
    /** `ViewConfiguration.scaledTouchSlop` — the platform's own tap/scroll split. */
    private val touchSlopPx: Float,
    /** A move sample is persisted when this much time OR [minMoveDistancePx] has passed. */
    private val minMoveIntervalMs: Long = 100,
    private val minMoveDistancePx: Float = 8f,
    private val newGestureId: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "").take(32) },
) {

    enum class Action { DOWN, MOVE, UP, CANCEL, EXTRA_POINTER }

    /** Coordinates are in the SAME screen-pixel basis the native hierarchy uses. */
    data class TouchSample(
        val action: Action,
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val monotonicNanos: Long,
        val wallMillis: Long,
    )

    data class SampleFacts(
        val screenId: String?,
        val screenNeverRecord: Boolean,
        val textEntryActive: Boolean,
        val fromContentWindow: Boolean,
        val targetClass: TargetClass,
        val positionTreatment: PositionTreatment,
        /** The captured display extent, for the out-of-viewport refusal. */
        val viewportWidth: Int,
        val viewportHeight: Int,
    ) {
        internal fun authority(): String =
            "${screenId ?: ""}|$screenNeverRecord|$textEntryActive|$fromContentWindow"
    }

    enum class Kind(val wire: String) { TAP("tap"), SWIPE("swipe") }
    enum class Phase(val wire: String) { BEGIN("begin"), MOVE("move"), END("end"), CANCEL("cancel") }

    /** One observation to hand to the producer. `phase == null` is the tap row. */
    data class Persist(
        val kind: Kind,
        val phase: Phase?,
        val gestureId: String,
        val sample: TouchSample,
        val facts: SampleFacts,
    )

    enum class Classification { TAP_CANDIDATE, SWIPE, INVALIDATED }

    private class Live(
        val gestureId: String,
        val pointerId: Int,
        val down: TouchSample,
        val downFacts: SampleFacts,
        var classification: Classification,
        var lastPersisted: TouchSample?,
        var authority: PositionTreatment,
    )

    private var live: Live? = null

    val currentClassification: Classification? get() = live?.classification

    fun reset() { live = null }

    private fun passedSlop(l: Live, s: TouchSample): Boolean {
        val dx = s.x - l.down.x; val dy = s.y - l.down.y
        return dx * dx + dy * dy > touchSlopPx * touchSlopPx
    }

    private fun withAuthority(f: SampleFacts, a: PositionTreatment) =
        if (f.positionTreatment == a) f else f.copy(positionTreatment = a)

    private fun shouldPersistMove(l: Live, s: TouchSample): Boolean {
        val last = l.lastPersisted ?: return true
        val dtMs = (s.monotonicNanos - last.monotonicNanos) / 1_000_000
        val dx = s.x - last.x; val dy = s.y - last.y
        return dtMs >= minMoveIntervalMs || dx * dx + dy * dy >= minMoveDistancePx * minMoveDistancePx
    }

    /** Promote to SWIPE: emit the withheld BEGIN from the ORIGINAL down. */
    private fun promote(l: Live): Persist {
        l.classification = Classification.SWIPE
        l.lastPersisted = l.down
        return Persist(Kind.SWIPE, Phase.BEGIN, l.gestureId, l.down, withAuthority(l.downFacts, l.authority))
    }

    fun observe(s: TouchSample, facts: SampleFacts): List<Persist> {
        when (s.action) {
            Action.DOWN -> {
                live = Live(newGestureId(), s.pointerId, s, facts, Classification.TAP_CANDIDATE,
                    null, facts.positionTreatment)
                return emptyList()
            }
            Action.EXTRA_POINTER -> {
                val l = live ?: return emptyList()
                val wasSwipe = l.classification == Classification.SWIPE
                l.classification = Classification.INVALIDATED
                // A persisted swipe is CLOSED, never left unterminated. The
                // cancel row describes the single-pointer gesture that ended.
                return if (wasSwipe && l.downFacts.authority() == facts.authority()) {
                    listOf(Persist(Kind.SWIPE, Phase.CANCEL, l.gestureId, s.copy(pointerId = l.pointerId),
                        withAuthority(facts, PositionTreatment.strongest(l.authority, facts.positionTreatment))))
                } else emptyList()
            }
            Action.MOVE -> {
                val l = live ?: return emptyList()
                if (s.pointerId != l.pointerId) { l.classification = Classification.INVALIDATED; return emptyList() }
                when (l.classification) {
                    Classification.INVALIDATED -> return emptyList()
                    Classification.TAP_CANDIDATE -> {
                        if (!passedSlop(l, s)) return emptyList()
                        if (l.downFacts.authority() != facts.authority()) {
                            l.classification = Classification.INVALIDATED; return emptyList()
                        }
                        val out = mutableListOf(promote(l))
                        l.authority = PositionTreatment.strongest(l.authority, facts.positionTreatment)
                        if (shouldPersistMove(l, s)) {
                            out += Persist(Kind.SWIPE, Phase.MOVE, l.gestureId, s, withAuthority(facts, l.authority))
                            l.lastPersisted = s
                        }
                        return out
                    }
                    Classification.SWIPE -> {
                        if (l.downFacts.authority() != facts.authority()) {
                            l.classification = Classification.INVALIDATED; return emptyList()
                        }
                        l.authority = PositionTreatment.strongest(l.authority, facts.positionTreatment)
                        if (!shouldPersistMove(l, s)) return emptyList()
                        l.lastPersisted = s
                        return listOf(Persist(Kind.SWIPE, Phase.MOVE, l.gestureId, s, withAuthority(facts, l.authority)))
                    }
                }
            }
            Action.UP -> {
                val l = live ?: return emptyList()
                live = null
                if (s.pointerId != l.pointerId) return emptyList()
                when (l.classification) {
                    Classification.INVALIDATED -> return emptyList()
                    Classification.SWIPE -> {
                        if (l.downFacts.authority() != facts.authority()) return emptyList()
                        val a = PositionTreatment.strongest(l.authority, facts.positionTreatment)
                        return listOf(Persist(Kind.SWIPE, Phase.END, l.gestureId, s, withAuthority(facts, a)))
                    }
                    Classification.TAP_CANDIDATE -> {
                        // A sparse drag can pass the slop between the last MOVE and UP.
                        if (passedSlop(l, s)) {
                            if (l.downFacts.authority() != facts.authority()) return emptyList()
                            val begin = promote(l)
                            val a = PositionTreatment.strongest(l.authority, facts.positionTreatment)
                            return listOf(begin, Persist(Kind.SWIPE, Phase.END, l.gestureId, s, withAuthority(facts, a)))
                        }
                        // A different screen at release is not the gesture that began.
                        if (l.downFacts.screenId != facts.screenId) return emptyList()
                        return listOf(Persist(Kind.TAP, null, l.gestureId, l.down, join(l.downFacts, facts)))
                    }
                }
            }
            Action.CANCEL -> {
                val l = live ?: return emptyList()
                live = null
                if (l.classification != Classification.SWIPE) return emptyList()
                if (l.downFacts.authority() != facts.authority()) return emptyList()
                val a = PositionTreatment.strongest(l.authority, facts.positionTreatment)
                return listOf(Persist(Kind.SWIPE, Phase.CANCEL, l.gestureId, s.copy(pointerId = l.pointerId),
                    withAuthority(facts, a)))
            }
        }
    }

    /**
 * DOWN/UP join, most restrictive on every axis: state may tighten while the
 * finger is down, never loosen. Position/time stay the DOWN's.
 */
    internal fun join(d: SampleFacts, u: SampleFacts) = d.copy(
        screenNeverRecord = d.screenNeverRecord || u.screenNeverRecord,
        textEntryActive = d.textEntryActive || u.textEntryActive,
        fromContentWindow = d.fromContentWindow && u.fromContentWindow,
        targetClass = TargetClass.stricter(d.targetClass, u.targetClass),
        positionTreatment = PositionTreatment.strongest(d.positionTreatment, u.positionTreatment),
    )
}

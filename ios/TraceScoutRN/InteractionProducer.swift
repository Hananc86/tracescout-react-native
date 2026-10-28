import Foundation

/// THREE LAYERS, KEPT APART ON PURPOSE.
///
/// NativeTouchObservation purely mechanical UIKit facts
/// InteractionPolicy the SHARED semantic decision (27 vectors)
/// RnEnvelope.interaction the EXISTING wire, unchanged
///
/// THE SEPARATION IS THE PRIVACY AUDIT. A mechanical observation may hold
/// x/y in memory; if the policy says suppress, nothing is built, nothing is
/// logged and nothing is persisted — there is no path from an observation to
/// storage that does not pass through the shared decision.
///
/// NO UIKit HERE. The recognizer converts touches into observations; this
/// type decides and builds. That is what makes every case below executable on
/// a runner without a simulator.
public enum InteractionProducer {

    /// Purely mechanical facts. Nothing derived, nothing named, nothing read
    /// from a label.
    public struct NativeTouchObservation {
        /// From the SAME helper visual frames use — one boot-relative domain.
        public let monotonicNanos: Int64
        public let wallMillis: Int64
        /// LOGICAL VIEWPORT POINTS, in the basis of the view the REPLAY FRAME
        /// IS DRAWN FROM -- the key window, which is what `FrameRasterizer`
        /// captures. NOT the attached RN surface: wherever that surface is
        /// inset from the window, the two disagree and every ripple would land
        /// off the pixel actually touched. Never device pixels, never
        /// multiplied by UIScreen.scale.
        public let x: Double
        public let y: Double
        public let viewportWidth: Double
        public let viewportHeight: Double
        /// From the bridge's CURRENT ROUTE. Never an accessibility label, a
        /// view-controller name, a hit-tested element or a UIKit class name.
        public let screenId: String?
        public let phase: Phase
        public let pointerCount: Int
        public let gestureId: String
        public let targetClass: InteractionPolicy.TargetClass
        public let positionTreatment: InteractionPolicy.PositionTreatment
        public let fromContentWindow: Bool
        public let screenNeverRecord: Bool
        public let textEntryActive: Bool
        /// FAIL-CLOSED WINDOW IDENTITY. True only when the coordinates were
        /// measured in the SAME window whose pixels the rasterizer captures.
        /// False on a nil window, a different window, or a different scene --
        /// and false must never be translated into the captured window's space
        /// by some conversion, because a replay coordinate is useful only when
        /// it describes pixels that were actually photographed. Defaults to
        /// FALSE so a caller that forgets it gets the restrictive answer.
        public let basisIsCapturedWindow: Bool
        /// WITHOUT THIS, `unsupported_gesture` COULD NEVER FIRE FROM THIS
        /// PRODUCER. `isTap` was derived from the phase alone, and a DRAG's
        /// `end` is the same phase as a TAP's `end` — so the policy's stated
        /// rule that v1 emits for ONE logical tap was unreachable, and a real
        /// swipe produced an interaction at its release point. Defaults to
        /// FALSE so an unaware caller gets the permissive-for-taps answer it
        /// had before, and the recognizer supplies the truth.
        public let movedBeyondSlop: Bool
        /// an earlier change — the hit view IS web content or lies beneath it
        /// (`TouchTargetResolver.insideWebContent`). Decides ONLY whether the
        /// host's WebView carrier is asked for this interaction; it is not read
        /// by the privacy policy and grants nothing. Defaults to FALSE: a
        /// caller that does not state it makes no WebView claim.
        public let insideWebContent: Bool

        public enum Phase: String { case begin, move, end, cancel, instant }

        public init(monotonicNanos: Int64, wallMillis: Int64,
                    x: Double, y: Double, viewportWidth: Double, viewportHeight: Double,
                    screenId: String?, phase: Phase, pointerCount: Int, gestureId: String,
                    targetClass: InteractionPolicy.TargetClass,
                    positionTreatment: InteractionPolicy.PositionTreatment,
                    fromContentWindow: Bool, screenNeverRecord: Bool, textEntryActive: Bool,
                    /// Defaults to FALSE: a caller that forgets the window
                    /// identity must get the restrictive answer, not the
                    /// permissive one.
                    basisIsCapturedWindow: Bool = false,
                    movedBeyondSlop: Bool = false,
                    insideWebContent: Bool = false) {
            self.monotonicNanos = monotonicNanos; self.wallMillis = wallMillis
            self.x = x; self.y = y
            self.viewportWidth = viewportWidth; self.viewportHeight = viewportHeight
            self.screenId = screenId; self.phase = phase
            self.pointerCount = pointerCount; self.gestureId = gestureId
            self.targetClass = targetClass; self.positionTreatment = positionTreatment
            self.fromContentWindow = fromContentWindow
            self.screenNeverRecord = screenNeverRecord
            self.basisIsCapturedWindow = basisIsCapturedWindow
            self.movedBeyondSlop = movedBeyondSlop
            self.insideWebContent = insideWebContent
            self.textEntryActive = textEntryActive
        }
    }

    /// Why nothing was produced. Aggregate-safe: it names a RULE, never a
    /// coordinate, a target or a screen.
    public enum Refusal: String {
        case consentDisabled = "consent_disabled"
        case noRoute         = "no_route"
        case outOfViewport   = "out_of_viewport"
        case policySuppressed = "policy_suppressed"
    }

    /// THE STICKY CLASSIFICATION OF ONE PHYSICAL TOUCH.
    ///
    /// IT IS A LIFECYCLE FACT, NOT A PER-PHASE ONE. The mechanical
    /// observation stays truthful — a `begin` genuinely has not moved — so the
    /// class travels beside it rather than being written back into it. That is
    /// what lets a swipe's BEGIN be materialized retroactively from the
    /// ORIGINAL begin observation, timestamp and point intact, once the touch
    /// later crosses the slop.
    ///
    /// The only two classes this producer can truthfully name. `scroll`, `pan`
    /// and `fling` remain protocol values it does not claim.
    public enum GestureClass: String {
        case tap, swipe
    }

    public struct Emission: Equatable {
        public let outcome: InteractionPolicy.Outcome
        /// The wire's `interaction` value — `tap` or `swipe`, never inferred
        /// downstream. One fact, one place.
        public let interaction: String
        public let includeCoordinates: Bool
        public let x: Double?
        public let y: Double?
        public let viewportWidth: Double
        public let viewportHeight: Double
        public let screenId: String
        public let phase: String
        public let gestureId: String
        public let onRedactedTarget: Bool
        public let pointerCount: Int
        public let monotonicNanos: Int64
        public let wallMillis: Int64
    }

    public enum Result: Equatable {
        case emit(Emission)
        case refuse(Refusal)
    }

    /// CONSENT IS A GATE, NOT A SECOND POLICY. It answers "may this app
    /// produce replay interactions at all", and it is checked BEFORE the
    /// semantic decision. `consent == true` never implies positional output —
    /// the shared policy still decides the treatment.
    ///
    /// `gestureClass` IS THE AUTHORITY WHEN SUPPLIED, AND THE PRODUCTION
    /// PATH ALWAYS SUPPLIES IT. `nil` falls back to the pre-movement-lane
    /// inference (`a tap is an end/instant that did not travel`) so every
    /// existing direct caller and vector keeps its exact meaning; it is NOT a
    /// second rule running in production. `InteractionGestureLifecycle` owns
    /// the sticky classification and hands it in explicitly — a source guard
    /// asserts `RnBridge` never calls this without one, because two ways to
    /// decide one fact is how a producer starts disagreeing with itself.
    public static func produce(_ o: NativeTouchObservation,
                               consentEnabled: Bool,
                               gestureClass: GestureClass? = nil) -> Result {
        guard consentEnabled else { return .refuse(.consentDisabled) }

        // NO GUESSED SCREEN ID. Without a current route there is no
        // replayable subject, so the persisted output fails closed. (The
        // caller may still have extended ephemeral cadence activity — that
        // stores nothing.)
        guard let screen = o.screenId, !screen.isEmpty else { return .refuse(.noRoute) }

        // A tap is an end/instant that did NOT travel — the inference retained
        // for callers predating the movement lane. When a class IS supplied it
        // wins outright, and exactly one of the two flags is ever set.
        let inferredTap = (o.phase == .end || o.phase == .instant) && !o.movedBeyondSlop
        let isTap   = gestureClass.map { $0 == .tap }   ?? inferredTap
        let isSwipe = gestureClass.map { $0 == .swipe } ?? false

        let d = InteractionPolicy.decide(.init(
            fromContentWindow: o.fromContentWindow,
            screenNeverRecord: o.screenNeverRecord,
            textEntryActive: o.textEntryActive,
            targetClass: o.targetClass,
            isTap: isTap,
            pointerCount: o.pointerCount,
            positionTreatment: effectivePositionTreatment(o),
            isSwipe: isSwipe))

        guard d.outcome != .suppress else { return .refuse(.policySuppressed) }

        var x: Double? = nil
        var y: Double? = nil
        if d.includeCoordinates {
            // FAIL CLOSED, NEVER CLAMP. A silently clamped coordinate is an
            // invented one: it would place a ripple somewhere the user did not
            // touch, and it would do so most often exactly when the touch left
            // the surface — a transition, i.e. the moment we understand least.
            guard o.viewportWidth > 0, o.viewportHeight > 0,
                  o.x.isFinite, o.y.isFinite,
                  o.x >= 0, o.x <= o.viewportWidth,
                  o.y >= 0, o.y <= o.viewportHeight else {
                return .refuse(.outOfViewport)
            }
            x = o.x; y = o.y
        }

        return .emit(Emission(
            outcome: d.outcome,
            interaction: (isSwipe ? GestureClass.swipe : GestureClass.tap).rawValue,
            includeCoordinates: d.includeCoordinates,
            x: x, y: y,
            viewportWidth: o.viewportWidth, viewportHeight: o.viewportHeight,
            screenId: screen,
            phase: o.phase.rawValue,
            gestureId: o.gestureId,
            // The wire's own name for "the coordinate was withheld".
            onRedactedTarget: d.outcome == .emitRedacted,
            pointerCount: o.pointerCount,
            monotonicNanos: o.monotonicNanos,
            wallMillis: o.wallMillis))
    }

    /// WINDOW IDENTITY IS A PRE-POLICY NARROWING, NOT A POLICY BRANCH.
    /// The shared policy is parity-pinned to Kotlin and to 27 vectors; this is
    /// an iOS-platform fact (which UIWindow/scene the points were measured in)
    /// and belongs above it. It can only ever make the answer MORE
    /// restrictive: a declared position-safe target whose coordinates were
    /// measured in a window nobody photographed becomes `.blocked`, which the
    /// policy already routes to `emit_redacted` with no coordinates.
    ///
    /// There is deliberately NO cross-window conversion. Translating points
    /// into the captured window's space would produce a number that looks like
    /// a position and describes pixels that were never captured.
    public static func effectivePositionTreatment(_ o: NativeTouchObservation)
    -> InteractionPolicy.PositionTreatment {
        o.basisIsCapturedWindow ? o.positionTreatment : .blocked
    }

    /// Build the EXISTING wire event. Only an `Emission` can reach here, so a
    /// suppressed touch has no path to an envelope.
    public static func envelope(_ e: Emission,
                                id: RnEnvelope.Identity,
                                eventId: String,
                                sequence: Int64,
                                posture: RnEnvelope.Posture) -> String {
        RnEnvelope.interaction(
            id: id, eventId: eventId, sequence: sequence,
            timestampMillis: e.wallMillis, monotonicNanos: e.monotonicNanos,
            posture: posture, screenId: e.screenId,
            interaction: e.interaction, phase: e.phase, gestureId: e.gestureId,
            onRedactedTarget: e.onRedactedTarget,
            replayX: e.x, replayY: e.y, pointerCount: e.pointerCount)
    }

    /// The durable-bridge counterpart of `envelope(_:)`: the same body, handed
    /// to the queue's own envelope writer so the sequence is assigned by the
    /// durable path. Both go through `RnEnvelope.interactionFields`.
    public static func envelopeFields(_ e: Emission) -> [(String, RnEnvelope.Value)] {
        RnEnvelope.interactionFields(
            screenId: e.screenId, interaction: e.interaction, phase: e.phase,
            gestureId: e.gestureId, onRedactedTarget: e.onRedactedTarget,
            replayX: e.x, replayY: e.y, pointerCount: e.pointerCount)
    }

    // ── bounded move sampling ───────────────────────────────────────────────

    /// ACTIVITY SIGNALLING AND PERSISTED SAMPLING ARE DIFFERENT CHANNELS.
    /// Every meaningful move may extend the cadence window; only a bounded
    /// subset becomes a stored row. `begin` and `end`/`cancel` are ALWAYS
    /// preserved — losing them to a move throttle would destroy the very
    /// endpoints a gesture is identified by.
    public enum MoveSampling {
        public static let minIntervalMs: Int64 = 100
        public static let minDistancePoints: Double = 8

        public static func shouldPersist(phase: NativeTouchObservation.Phase,
                                         sinceLastPersistedMs: Int64,
                                         distanceFromLastPersisted: Double) -> Bool {
            switch phase {
            case .begin, .end, .cancel, .instant: return true
            case .move:
                if sinceLastPersistedMs < 0 { return true }
                return sinceLastPersistedMs >= minIntervalMs
                    || distanceFromLastPersisted >= minDistancePoints
            }
        }
    }
}

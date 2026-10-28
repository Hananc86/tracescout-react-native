#if canImport(UIKit)
import UIKit

/// THE OBSERVER. IT OWNS NOTHING.
///
/// ## Why a recognizer, and why this one
///
/// RN 0.85.3 with Fabric routes touches through `RCTSurfaceTouchHandler`,
/// which is itself a plain `UIGestureRecognizer` configured observationally
/// (`RCTSurfaceTouchHandler.mm:150-158`: `cancelsTouchesInView = NO`,
/// `delaysTouchesBegan = NO`, `delaysTouchesEnded = NO`). Attaching a SECOND
/// recognizer with the same configuration therefore sits alongside RN's own
/// and changes nothing about delivery.
///
/// WHAT THIS DELIBERATELY IS NOT: no `hitTest` override, no
/// `UIApplication.sendEvent` interception, no swizzling of anything, no
/// subclass or replacement of RN's handler, no responder-chain takeover. Each
/// of those was rejected in the Phase-3A audit because none can be *proven*
/// observational.
///
/// IT NEVER RECOGNIZES. It reports `.failed` at the end of a sequence
/// purely so UIKit resets it and the NEXT sequence is observed — a recognizer
/// pinned at `.possible` forever stops receiving touches. `.failed` is a
/// non-owning terminal state: it cannot cancel, delay or claim anything. The
/// forbidden states are `.began`, `.changed`, `.ended` and `.recognized`.
public final class PassiveInteractionRecognizer: UIGestureRecognizer {

    /// One mechanical observation per UIKit callback. No policy, no naming.
    public struct Observation {
        public let phase: InteractionProducer.NativeTouchObservation.Phase
        public let x: Double
        public let y: Double
        public let viewportWidth: Double
        public let viewportHeight: Double
        public let pointerCount: Int
        public let gestureId: String
        /// From the SAME helper visual frames use.
        public let monotonicNanos: Int64
        /// A MECHANICAL FACT, NOT A CLASSIFICATION. This is only "which view
        /// UIKit says the touch landed on". The recognizer does not decide
        /// whether that view is secure, editable or position-safe -- that is a
        /// policy question and it is answered a layer up, so this type can stay
        /// something with no opinions to get wrong.
        public weak var targetView: UIView?
        /// The window the coordinates above were measured in. Carried so the
        /// layer above can ask the ONE question that matters: is this the same
        /// window whose pixels the rasterizer captures? A coordinate measured
        /// in a window nobody photographed describes nothing.
        public weak var basisWindow: UIWindow?
        /// MECHANICAL, AND THE REASON `unsupported_gesture` CAN FIRE AT ALL.
        /// Whether this gesture has travelled beyond the tap slop. Without it a
        /// DRAG's `end` and a TAP's `end` are the same phase, so the policy's
        /// "v1 is ONE logical single-pointer tap" rule was unreachable and a
        /// swipe emitted an interaction at its release point.
        public let movedBeyondSlop: Bool
    }

    /// UIKit's own tap slop is ~10pt; a finger never holds perfectly still.
    public static let tapSlopPoints: CGFloat = 10

    /// Where observations go. The recognizer decides nothing about them.
    public var onObservation: ((Observation) -> Void)?

    private let clock: () -> Int64
    private var currentGestureId: String?
    private var gestureStart: CGPoint?
    private var gestureMoved = false
    private var gestureCounter: UInt64 = 0

    /// THE OWNERSHIP MARKER. "Is one already attached?" must be a FACT, not
    /// inferred from a recognizer count — a host may add and remove its own
    /// recognizers at any time, so counting them answers a different question.
    public static let markerTag = "tracescout.passive.interaction.observer"
    public let marker = PassiveInteractionRecognizer.markerTag

    public init(clock: @escaping () -> Int64) {
        self.clock = clock
        super.init(target: nil, action: nil)
        // The audited passive contract, applied at construction so no code path
        // can produce an unconfigured instance.
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        requiresExclusiveTouchType = false
        delegate = self
    }

    // ── the observation points ──────────────────────────────────────────────

    public override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesBegan(touches, with: event)
        gestureCounter &+= 1
        currentGestureId = "ios_g_\(gestureCounter)"
        gestureStart = touches.first?.location(in: view?.window ?? view)
        gestureMoved = false
        report(touches, .begin)
    }

    public override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        // Sticky: once a gesture has travelled it is a drag for the rest of its
        // life, even if the finger returns near where it started. A
        // last-sample distance would let a scroll that ends where it began be
        // reported as a tap.
        if let start = gestureStart,
           let now = touches.first?.location(in: view?.window ?? view) {
            let dx = now.x - start.x, dy = now.y - start.y
            if (dx * dx + dy * dy).squareRoot() > PassiveInteractionRecognizer.tapSlopPoints {
                gestureMoved = true
            }
        }
        super.touchesMoved(touches, with: event)
        report(touches, .move)
    }

    public override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesEnded(touches, with: event)
        report(touches, .end)
        // RESET, NOT RECOGNITION. `.failed` returns the recognizer to
        // `.possible` for the next sequence without ever claiming this one.
        state = .failed
        currentGestureId = nil
    }

    public override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesCancelled(touches, with: event)
        report(touches, .cancel)
        state = .failed
        currentGestureId = nil
    }

    public override func reset() {
        super.reset()
        currentGestureId = nil
    }

    private func report(_ touches: Set<UITouch>, 
                        _ phase: InteractionProducer.NativeTouchObservation.Phase) {
        guard let cb = onObservation, let touch = touches.first else { return }
        // THE BASIS IS THE VIEW THE FRAME IS DRAWN FROM, AND THAT IS THE
        // KEY WINDOW — NOT THE VIEW WE ARE ATTACHED TO.
        //
        // `FrameRasterizer` captures `window.drawHierarchy(in: window.bounds)`,
        // so a replay frame IS the window. Measuring in the attached RN
        // surface's space would be correct-looking and wrong: wherever that
        // surface is inset from the window — a safe area, a nav container, a
        // split view — every ripple would land offset from the pixel the user
        // actually touched, and it would be offset by an amount no downstream
        // consumer could recover.
        //
        // Attachment stays SCOPED to the RN surface (this is not a global
        // window hook); only the measurement is taken in the frame's own basis.
        // Logical UIKit points throughout — never multiplied by UIScreen.scale.
        let frameBasis: UIView? = view?.window ?? view
        let p = touch.location(in: frameBasis)
        let b = frameBasis?.bounds.size ?? .zero
        // THE SAME MONOTONIC HELPER AS VISUAL FRAMES, read at observation.
        // `UITouch.timestamp` lives in the systemUptime domain, which is a
        // DIFFERENT API from DispatchTime.uptimeNanoseconds; converting between
        // them and assuming alignment is exactly the clock mixing that would
        // make a tap and its resulting frame incomparable.
        cb(Observation(phase: phase,
                       x: Double(p.x), y: Double(p.y),
                       viewportWidth: Double(b.width), viewportHeight: Double(b.height),
                       pointerCount: touches.count,
                       gestureId: currentGestureId ?? "ios_g_0",
                       monotonicNanos: clock(),
                       targetView: touches.first?.view,
                       basisWindow: view?.window,
                       movedBeyondSlop: gestureMoved))
    }
}

extension PassiveInteractionRecognizer: UIGestureRecognizerDelegate {
    /// Always simultaneous: the observer must never be the reason a host
    /// gesture does not fire.
    public func gestureRecognizer(_ g: UIGestureRecognizer,
                                  shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer)
    -> Bool { true }

    /// NO FAILURE DEPENDENCY IN EITHER DIRECTION. Returning true for either
    /// of these would make a host recognizer wait on us, or us on it — a
    /// delivery change wearing an observer's clothes.
    public func gestureRecognizer(_ g: UIGestureRecognizer,
                                  shouldRequireFailureOf other: UIGestureRecognizer)
    -> Bool { false }

    public func gestureRecognizer(_ g: UIGestureRecognizer,
                                  shouldBeRequiredToFailBy other: UIGestureRecognizer)
    -> Bool { false }
}

/// ATTACHMENT, WITH AN EXPLICIT OWNERSHIP MARKER.
///
/// A DUPLICATE OBSERVER IS RED: it would double every tap, double every
/// cadence signal and double every gesture path. "Already attached" is answered
/// by finding OUR instance on the view, never by a recognizer count.
public enum PassiveInteractionAttachment {

    @discardableResult
    public static func attach(to surface: UIView,
                              clock: @escaping () -> Int64,
                              onObservation: @escaping (PassiveInteractionRecognizer.Observation) -> Void)
    -> PassiveInteractionRecognizer {
        if let existing = existing(on: surface) {
            existing.onObservation = onObservation
            return existing                     // idempotent: no second observer
        }
        let r = PassiveInteractionRecognizer(clock: clock)
        r.onObservation = onObservation
        surface.addGestureRecognizer(r)
        return r
    }

    public static func detach(from surface: UIView) {
        for r in surface.gestureRecognizers ?? [] {
            if let ours = r as? PassiveInteractionRecognizer {
                ours.onObservation = nil        // break the callback retain path
                surface.removeGestureRecognizer(ours)
            }
        }
    }

    public static func existing(on surface: UIView) -> PassiveInteractionRecognizer? {
        (surface.gestureRecognizers ?? []).compactMap { $0 as? PassiveInteractionRecognizer }.first
    }

    public static func count(on surface: UIView) -> Int {
        (surface.gestureRecognizers ?? []).filter { $0 is PassiveInteractionRecognizer }.count
    }

    /// Move the single observer to a new surface — remount, not duplication.
    @discardableResult
    public static func reattach(from old: UIView?, to new: UIView,
                                clock: @escaping () -> Int64,
                                onObservation: @escaping (PassiveInteractionRecognizer.Observation) -> Void)
    -> PassiveInteractionRecognizer {
        if let o = old, o !== new { detach(from: o) }
        return attach(to: new, clock: clock, onObservation: onObservation)
    }
}
#endif

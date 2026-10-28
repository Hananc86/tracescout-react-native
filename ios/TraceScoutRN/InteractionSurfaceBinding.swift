#if canImport(UIKit)
import UIKit

/// OWNERSHIP OF THE PASSIVE OBSERVER'S ATTACHMENT, KEYED BY EXPLICIT SURFACE
/// IDENTITY.
///
/// A RECOGNIZER COUNT IS NOT AN OWNERSHIP PROOF. `count(on:) == 1` is
/// equally true of "we attached once", "we attached, the surface was replaced,
/// and we are now observing a view nobody is rendering", and "somebody else's
/// recognizer happens to be there". So this type records WHICH surface it is
/// bound to, by object identity, and every outcome is a distinct named value
/// rather than a bool that collapses several different facts.
///
/// The binding holds the surface WEAKLY. A surface that is torn down without
/// an explicit unbind must read as unbound -- if it read as bound we would
/// silently keep "observing" a deallocated view, and the count on the
/// replacement surface would be 0 while this type claimed everything was fine.
public final class InteractionSurfaceBinding {

    public static let shared = InteractionSurfaceBinding()
    public init() {}

    /// Each case is a FACT, not a severity. `alreadyBound` is a correct,
    /// expected no-op; `refusedNoSurface` is a refusal; `rebound` records that
    /// a remount happened. Collapsing them into a Bool is how a remount that
    /// silently failed would read as success.
    public enum Outcome: String {
        case attached
        case alreadyBound
        case rebound
        case detached
        case notBound
        /// No React root could be found. The locator disclosed the window as a
        /// fallback for the FRAME lane; the interaction lane refuses it.
        case refusedFallbackWindow
    }

    private let lock = NSLock()
    private weak var surface: UIView?
    private var identity: ObjectIdentifier?

    /// The identity we believe we are bound to, or nil. Exposed so a test can
    /// assert on WHO, not on HOW MANY.
    public var boundIdentity: ObjectIdentifier? {
        lock.lock(); defer { lock.unlock() }
        // A deallocated surface is not a binding, whatever we recorded.
        return surface == nil ? nil : identity
    }

    public var isBound: Bool { boundIdentity != nil }

    public func isBound(to candidate: UIView) -> Bool {
        boundIdentity == ObjectIdentifier(candidate)
    }

    @discardableResult
    public func bind(to newSurface: UIView,
                     clock: @escaping () -> Int64,
                     onObservation: @escaping (PassiveInteractionRecognizer.Observation) -> Void) -> Outcome {
        lock.lock()
        let previous = surface
        let previousIdentity = surface == nil ? nil : identity
        let newIdentity = ObjectIdentifier(newSurface)

        if previousIdentity == newIdentity,
           PassiveInteractionAttachment.existing(on: newSurface) != nil {
            // Idempotent by IDENTITY *and* by the recognizer actually still
            // being there. Believing our own bookkeeping alone would let a
            // recognizer removed by someone else read as still attached.
            lock.unlock()
            return .alreadyBound
        }

        surface = newSurface
        identity = newIdentity
        lock.unlock()

        // Detach from the OLD surface even when it is a different view: a
        // remount must not leave a recognizer observing the retired one.
        if let previous, previous !== newSurface {
            PassiveInteractionAttachment.detach(from: previous)
        }
        PassiveInteractionAttachment.attach(to: newSurface, clock: clock, onObservation: onObservation)

        return previousIdentity == nil ? .attached
             : (previousIdentity == newIdentity ? .attached : .rebound)
    }

    @discardableResult
    public func unbind() -> Outcome {
        lock.lock()
        let previous = surface
        surface = nil
        identity = nil
        lock.unlock()
        guard let previous else { return .notBound }
        PassiveInteractionAttachment.detach(from: previous)
        return .detached
    }

    /// THE SURFACE-SCOPED ENTRY POINT, and the one production should use.
    ///
    /// IT LIVES HERE, NOT IN THE REACT MODULE, BECAUSE THE MODULE IS NOT IN
    /// THE TEST TARGET. `TraceScoutRnModule` imports React and is compiled only
    /// by the Xcode project, so a decision made there is reachable by neither
    /// `swift test` nor the simulator suite — the same shape as every
    /// unreachable mechanism this programme has paid for. The module is a thin
    /// caller; the rule is here, where it executes under test.
    ///
    /// `fellBackToWindow` is DIAGNOSTIC for the frame lane and DISQUALIFYING
    /// for this one: attaching a touch observer to a whole UIWindow is a global
    /// input hook, which is precisely what the policy's first branch exists to
    /// make impossible, and with multiple windows or scenes it would observe
    /// surfaces that are not the replay subject at all.
    @discardableResult
    public func bindSurfaceScoped(_ located: RnSurfaceLocator.Located,
                                  clock: @escaping () -> Int64,
                                  onObservation: @escaping (PassiveInteractionRecognizer.Observation) -> Void)
    -> Outcome {
        guard !located.fellBackToWindow else {
            // Release any previous binding rather than leave it pointing at a
            // surface we can no longer find.
            _ = unbind()
            return .refusedFallbackWindow
        }
        return bind(to: located.view, clock: clock, onObservation: onObservation)
    }

    public func resetForTesting() { _ = unbind() }
}
#endif

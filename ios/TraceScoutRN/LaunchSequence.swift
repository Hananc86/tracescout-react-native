import Foundation

/// THE ORDER IN WHICH STARTUP ACTUALLY HAPPENED, RECORDED.
///
/// ## The defect this exists for
///
/// Build 19 shipped `LabPostureAutoPersist` and reached a real TestFlight run
/// with rung H, because `apply` was called from exactly ONE place — the config
/// screen's Save button. A launch that never opened the config screen never
/// applied the approved Lab baseline, and nothing in the build could tell the
/// difference between "the posture is correct" and "the posture was never
/// asked for". the maintainer's session confirmed it: the opener carried
/// `effectivePolicy = lab_max_visibility` only AFTER a manual Save.
///
/// ASSERTING THE FINAL PREFERENCE VALUE IS NOT ENOUGH, and that is precisely
/// the shape of the build-19 failure. A test that applies the baseline AFTER
/// the opener is constructed still observes the right preference afterwards
/// while the SESSION was opened with the wrong one. The value is a fact about
/// the end state; the defect is a fact about ORDER. So the order is recorded
/// and asserted directly.
///
/// It is a diagnostic seam. It decides nothing, it is bounded, and it holds no
/// configuration value — only which named step ran and when.
public enum LaunchSequence {

    /// The named startup steps, in the order the contract requires them.
    public enum Step: String, Equatable {
        case configLoaded        = "config_loaded"
        case labPostureApplied   = "lab_posture_applied"
        /// A SEPARATE STEP FROM THE POSTURE. Interaction capture is its own
        /// consent, so the ordering proof must show it normalized in its own
        /// right rather than assumed to ride along with the fidelity rung.
        case interactionConsentNormalized = "interaction_consent_normalized"
        case policyResolved      = "policy_resolved"
        case sessionOpenerCreated = "session_opener_created"
        case captureStarted      = "capture_started"
    }

    private static let lock = NSLock()
    private static var steps: [Step] = []

    /// BOUNDED. A session may resolve policy and open many times; the
    /// recorder must not grow without limit in a long-running process.
    private static let maxSteps = 64

    public static func record(_ s: Step) {
        lock.lock(); defer { lock.unlock() }
        guard steps.count < maxSteps else { return }
        steps.append(s)
    }

    public static func recorded() -> [Step] {
        lock.lock(); defer { lock.unlock() }
        return steps
    }

    /// Did `a` first occur strictly before `b` first occurred?
    ///
    /// FALSE WHEN EITHER NEVER RAN. "b happened before a" and "a never
    /// happened at all" are different failures and must not collapse into one
    /// answer — the build-19 defect was exactly the second.
    public static func firstOccurred(_ a: Step, before b: Step) -> Bool {
        let s = recorded()
        guard let ia = s.firstIndex(of: a), let ib = s.firstIndex(of: b) else { return false }
        return ia < ib
    }

    /// Test-only reset. Never called by production code.
    public static func resetForTesting() {
        lock.lock(); steps = []; lock.unlock()
    }
}

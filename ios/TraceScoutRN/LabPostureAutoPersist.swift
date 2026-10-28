import Foundation

/// THE SCOPED EXCEPTION TO "LADDER MODE IS NEVER AUTO-ENABLED".
///
/// ## The generic rule still stands
///
/// Ordinary SDK and runtime behaviour never turns ladder mode on. Build 16 was
/// eleven home-only sessions because ladder mode was off and *invisible*, and
/// the answer then was to make the input observable and explicitly chosen — not
/// to make it automatic. That reasoning is still right for every generic or
/// operator-selected fidelity experiment.
///
/// ## Why this exception exists
///
/// It is too broad for the one configuration that is no longer an experiment.
/// The synthetic Mobile Replay Lab is now a PRODUCT UAT fixture with an
/// APPROVED baseline (`LAB FIDELITY BASELINE = MAXIMUM VISIBILITY`), and build
/// 18 shipped that machinery while selecting rung H, so every ordinary the development fixture
/// screen came back 100% masked. Requiring an operator to remember a segmented
/// control before every session makes the approved baseline depend on a step
/// nothing verifies — which is how the blue-bar regression reached a real
/// TestFlight run.
///
/// SO THIS IS NOT A GENERIC SDK BEHAVIOUR. It is the exact internal fixture
/// configuration contract, and it fires ONLY when the configuration being saved
/// IS the Mobile Replay Lab: exact project AND exact bundle. A wildcard, a
/// prefix, an environment string, a build identity or a route would each turn a
/// fixture contract into a policy.
///
/// ## What it does NOT do
///
/// It writes two persisted settings. It cannot unblock anything: Never-Record,
/// secure input, web content and camera surfaces are decided upstream and
/// combined with `Treatment.strongest`, so the rung it selects is only ever the
/// weaker vote. It adds no bridge method, no `@objc` export and no JS-reachable
/// surface — the manual controls and the config status line remain exactly as
/// they were, now as an operator override and diagnostic rather than the
/// prerequisite for ordinary Lab capture.
public enum LabPostureAutoPersist {

    /// What a save should do to the persisted fidelity posture.
    public enum Action: Equatable {
        /// The exact Lab fixture: persist the approved baseline.
        case applyLabBaseline
        /// Any other configuration: return the persisted posture to strict.
        ///
        /// RESET RATHER THAN "LEAVE ALONE". The resolver already makes a
        /// stale rung A inert for a different project, so this is the SECOND
        /// line — but leaving `LADDER MODE: ON · RUNG: A` on the status screen
        /// while capture is strict would be a display that contradicts the
        /// behaviour, and an operator reading it would reasonably conclude the
        /// Lab posture was active.
        case resetToStrict
    }

    /// Pure, UIKit-free and Keychain-free so the rule is testable directly.
    public static func decide(projectId: String, bundleIdentifier: String?) -> Action {
        guard projectId == ResolvedFidelityPolicy.labProjectId else { return .resetToStrict }
        guard let bundle = bundleIdentifier,
              bundle == ResolvedFidelityPolicy.labBundleId else { return .resetToStrict }
        return .applyLabBaseline
    }

    /// Apply the decision to the persisted settings.
    ///
    /// IDEMPOTENT BY CONSTRUCTION — it writes absolute values, never toggles.
    /// A second save of the same configuration produces the same state, and a
    /// save of a DIFFERENT project cannot leave the Lab baseline behind.
    /// THE BUILD-19 FIX. `apply` had exactly ONE caller — the config
    /// screen's Save button — so a launch that never opened that screen never
    /// normalized the approved Lab baseline, and build 19 reached a real
    /// TestFlight run at rung H. This is the launch-time entry point.
    ///
    /// IT RUNS BEFORE THE POLICY IS RESOLVED, BY CONSTRUCTION. The adapter
    /// calls it at the top of the only two functions that can resolve a policy,
    /// so the policy CANNOT be materialized without this having run first. An
    /// app-launch hook that merely happens to run early would be a hope; this
    /// is an ordering the type system enforces.
    ///
    /// ONCE PER PROCESS, AND IDEMPOTENT ANYWAY. `apply` writes the same two
    /// values every time for the same input, so a second call is harmless — but
    /// re-writing persisted settings on every policy resolution would be
    /// needless state churn on a hot path.
    ///
    /// IT TOUCHES NO CREDENTIAL. It reads a project id and writes exactly
    /// two fidelity preferences. It cannot read out, replace, clear or rotate
    /// the stored key, and it adds no JS-reachable surface.
    ///
    /// A missing project id resolves to NOTHING — not to the Lab baseline. An
    /// unconfigured or malformed install must never accidentally activate Lab
    /// posture.
    public static func applyAtLaunchOnce(
        projectId: String?,
        bundleIdentifier: String? = Bundle.main.bundleIdentifier
    ) {
        launchLock.lock()
        if didApplyAtLaunch { launchLock.unlock(); return }
        didApplyAtLaunch = true
        launchLock.unlock()

        LaunchSequence.record(.configLoaded)
        // FAIL SAFE, NOT FAIL OPEN. With no configured project there is
        // nothing to normalize; `decide` would answer `resetToStrict`, but
        // writing strict over an unconfigured install is state churn with no
        // subject. The step is still recorded so the ordering test can see that
        // launch reached this point and made a decision.
        guard let pid = projectId, !pid.isEmpty else {
            LaunchSequence.record(.labPostureApplied)
            LaunchSequence.record(.interactionConsentNormalized)
            return
        }
        apply(projectId: pid, bundleIdentifier: bundleIdentifier)
        LaunchSequence.record(.labPostureApplied)
        LaunchSequence.record(.interactionConsentNormalized)
    }

    private static let launchLock = NSLock()
    private static var didApplyAtLaunch = false

    /// Test-only reset of the once-per-process latch.
    public static func resetLaunchLatchForTesting() {
        launchLock.lock(); didApplyAtLaunch = false; launchLock.unlock()
    }

    public static func apply(projectId: String,
                             bundleIdentifier: String? = Bundle.main.bundleIdentifier) {
        switch decide(projectId: projectId, bundleIdentifier: bundleIdentifier) {
        case .applyLabBaseline:
            SecureConfig.setFidelityLadderMode(true)
            SecureConfig.setFidelityRung(ResolvedFidelityPolicy.labRung)
            // NORMALIZED ONLY FOR THE EXACT SYNTHETIC FIXTURE, and only
            // because `decide` has already proven project AND bundle match.
            // The certification run must produce interactions with NO config
            // screen and NO Save; this is the one automatic enablement, and it
            // is scoped by the same rule that scopes the fidelity baseline.
            // It is NOT derived from frames, the rung, ladder mode or replay.
            SecureConfig.setInteractionCaptureEnabled(true)
        case .resetToStrict:
            SecureConfig.setFidelityLadderMode(false)
            SecureConfig.setFidelityRung(.h_allMasked)
            // RESET RATHER THAN LEAVE ALONE, for the same reason as the rung:
            // a stale "interactions on" carried into a DIFFERENT project or
            // bundle would be a consent the operator never gave for that
            // configuration.
            SecureConfig.setInteractionCaptureEnabled(false)
        }
    }
}

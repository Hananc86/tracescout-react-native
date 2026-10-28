import Foundation

/// THE ONE PLACE THE ACTIVE CAPTURE POSTURE IS DECIDED.
///
/// ## Why it exists
///
/// A real TestFlight session (`ts_1788531095320_4833f8e079dd`, 2026-09-04)
/// stored frames in which every ordinary the development fixture screen was a wall of blue
/// rectangles: `home` 204/204 masked, `markets` 196/196, `activity` 198/198,
/// `profile` 252/252, and the session's own privacy summary reported
/// `maskText: true, allowedViewCount: 0`. The build was not missing the
/// machinery — it is the fidelity-ladder lineage, which has `SemanticPrivacy`,
/// the rungs and the rasterizer. It ran at rung **H** because nothing selected
/// otherwise, and H masks every node before any category is consulted.
///
/// The capture call site read `SecureConfig.fidelityRung` and
/// `SecureConfig.fidelityLadderMode` as two INDEPENDENT values with
/// independent defaults. Two switches that must agree are two switches that can
/// disagree, and the failure is silent: the posture that results is a
/// well-formed one, just not the one anyone chose.
///
/// ## What it decides
///
/// Maximum visibility requires ALL FOUR, and any absence, mismatch or
/// unreadable value yields the strict default:
///
/// 1. the configured project is EXACTLY the Mobile Replay Lab
/// 2. the running bundle is EXACTLY the synthetic fixture
/// 3. the persisted Lab switch is on
/// 4. the persisted rung is EXPLICITLY rung A
///
/// NOT SUFFICIENT, INDIVIDUALLY OR TOGETHER: a TestFlight/debug build
/// identity, a route name, an environment string, a wildcard project, or any
/// "allow all". A build identity is not a privacy boundary — every customer
/// build of a beta is one. And route identity was the old scope, which is
/// exactly how maximum visibility ended up reaching one screen out of six.
///
/// THIS RESOLVER NEVER UNBLOCKS ANYTHING. It selects a RUNG. Never-Record,
/// secure input and the platform-blocked surfaces are decided upstream and
/// combined with `Treatment.strongest`, so a rung can only ever be the weaker
/// of the two votes. There is deliberately no parameter here that could reach
/// them.
public struct ResolvedFidelityPolicy: Equatable {

    /// The exact synthetic Mobile Replay Lab project.
    public static let labProjectId = "b253551c-45bd-4b17-b237-76bb03b64f2f"
    /// The exact synthetic fixture bundle.
    public static let labBundleId = "com.tracescout.mobile.fixture.rn"
    /// The rung the approved Lab baseline names.
    public static let labRung: FidelityRung = .a_maxVisibility

    public let ladderMode: Bool
    public let rung: FidelityRung
    /// True only when all four predicates held. Reported as evidence so the
    /// gate can assert the ACTIVE policy instead of inferring it from "less
    /// masking" — an inference that would also be satisfied by a build that
    /// simply stopped masking.
    public let labScopeActive: Bool

    /// The certified customer posture. Every refusal path returns exactly this.
    public static let strictDefault = ResolvedFidelityPolicy(
        ladderMode: false, rung: .h_allMasked, labScopeActive: false)

    /// Resolve from the persisted configuration and the running bundle.
    ///
    /// - Parameters:
    /// - configuredProjectId: the project in the persisted config, or nil.
    /// - bundleIdentifier: the RUNNING bundle, read from the host bundle.
    /// - persistedLadderMode: `SecureConfig.fidelityLadderMode`.
    /// - persistedRung: `SecureConfig.fidelityRung`.
    public static func resolve(configuredProjectId: String?,
                               bundleIdentifier: String?,
                               persistedLadderMode: Bool,
                               persistedRung: FidelityRung) -> ResolvedFidelityPolicy {
        guard let project = configuredProjectId, project == labProjectId else { return strictDefault }
        guard let bundle = bundleIdentifier, bundle == labBundleId else { return strictDefault }
        guard persistedLadderMode else { return strictDefault }
        // THE RUNG MUST BE EXPLICITLY A. "Not H" would silently accept every
        // exploratory rung as the approved Lab baseline, and the baseline is a
        // specific posture, not "anything more visible than certified".
        guard persistedRung == labRung else { return strictDefault }
        return ResolvedFidelityPolicy(ladderMode: true, rung: labRung, labScopeActive: true)
    }

    /// The live resolution, for the one production capture call site.
    public static func current() -> ResolvedFidelityPolicy {
        resolve(configuredProjectId: SecureConfig.currentProjectId(),
                bundleIdentifier: Bundle.main.bundleIdentifier,
                persistedLadderMode: SecureConfig.fidelityLadderMode(),
                persistedRung: SecureConfig.fidelityRung())
    }

    /// Evidence for the session envelope and the host gate. CONTAINS NO USER
    /// CONTENT — three facts about which policy ran.
    public var evidence: [String: String] {
        ["fidelityLadderMode": ladderMode ? "true" : "false",
         "fidelityRung": rung.rawValue,
         "labScopeActive": labScopeActive ? "true" : "false"]
    }
}

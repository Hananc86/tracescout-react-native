import Foundation

/// the specification CaptureOutcome — the capture-outcome decision.
///
/// NORMATIVE SOURCE IS THE PROTOCOL, NOT A PARAPHRASE OF IT.
/// `mobile-protocol/src/events/mobile_screen_snapshot.schema.json`
/// (`captureUnavailableReason.description`) and `mobile-protocol/CHANGELOG.md`
/// define these two values independently and identically, and the API vendors
/// that schema. So:
///
/// route_surface_not_attached no qualifying active route host for the
/// REQUESTED ROUTE existed BEFORE THE ATTACH
/// BUDGET EXPIRED
/// route_surface_not_ready a qualifying active host existed but its
/// DECLARED READINESS never became true, or it
/// REPORTED FAILURE
///
/// AN INSTANTANEOUS OBSERVATION PROVES NEITHER, AND THAT IS THE WHOLE POINT.
/// A surface that is not in a window right now may be a moment later, so "no
/// key window at t=0" is not "no host before the budget expired". Zero measured
/// bounds is a measurement WE took, not a readiness statement the HOST made.
///
/// OWNERSHIP IS PART OF THE CONDITION, AND iOS MAKES THIS SHARPER THAN
/// ANDROID. `keyWindow == nil` is not sufficient on its own: an attached route
/// can live in a NON-KEY window, or in another scene entirely. Resolving "the
/// key window" therefore resolves neither the route's host nor its absence.
/// Unknown ownership yields NO reason.
///
/// BACKGROUND AND PRIVACY REFUSALS ARE A SEPARATE AXIS and carry no reason.
///
/// Pinned by the shared fixture `mobile-native-core/fixtures/capture-outcome.json`.
public enum CaptureOutcome {

    /// The CLOSED failure vocabulary, copied from the protocol schema.
    public static let notAttached = "route_surface_not_attached"
    public static let notReady = "route_surface_not_ready"
    /// A qualifying host declared ready inside the budget and every bounded
    /// late capture of the route produced no usable hierarchy. NOT derived by
    /// `decide`; emitted by a producer that owns a bounded late capture, after
    /// exhaustion. RN iOS has no such path today and never emits it — the
    /// constant exists so the wire vocabulary is one list.
    public static let captureEmpty = "route_surface_capture_empty"
    /// Every value the wire admits — what the boundary checks against.
    public static let reasons: [String] = [notAttached, notReady, captureEmpty]
    /// The subset `decide` can DERIVE from host facts (the fixture's decide cases).
    public static let hostDerivedReasons: [String] = [notAttached, notReady]

    public enum Decision: String {
        case neverRecord = "never_record"
        case unavailable
        case capture
        case noEmission = "no_emission"
    }

    public struct Result: Equatable {
        public let decision: Decision
        public let captureUnavailableReason: String?
        public init(_ decision: Decision, _ reason: String? = nil) {
            self.decision = decision
            self.captureUnavailableReason = reason
        }
    }

    /// What a ROUTE-HOST REGISTRY would report. Every field is optional and
    /// every `nil` means "we could not establish this" — never "false".
    ///
    /// RN iOS CANNOT POPULATE THIS TODAY. There is no route-host registry,
    /// no attach budget and no host readiness declaration. `RnSurfaceLocator`
    /// finds a React ROOT VIEW — which hosts the whole RN surface, not a
    /// particular route — and discloses when it fell back to the window. So the
    /// call site passes `.unknown` and NO reason is emitted. That is the correct
    /// answer, not a gap: the alternative is asserting a protocol condition from
    /// an observation that cannot establish it.
    public struct RouteHost: Equatable {
        public let resolved: Bool?
        public let attachBudgetExpired: Bool?
        public let readinessDeclaredTrue: Bool?
        public let hostReportedFailure: Bool?

        public init(resolved: Bool? = nil, attachBudgetExpired: Bool? = nil,
                    readinessDeclaredTrue: Bool? = nil, hostReportedFailure: Bool? = nil) {
            self.resolved = resolved
            self.attachBudgetExpired = attachBudgetExpired
            self.readinessDeclaredTrue = readinessDeclaredTrue
            self.hostReportedFailure = hostReportedFailure
        }

        /// No route-host registry exists on this platform.
        public static let unknown = RouteHost()
    }

    public static func decide(neverRecord: Bool,
                              host: RouteHost = .unknown,
                              threw: Bool = false) -> Result {
        // An intentional exclusion is not a capture failure, and it is decided
        // BEFORE anything about the surface is inspected.
        if neverRecord { return Result(.neverRecord) }

        // A generic failure is not a reason.
        if threw { return Result(.noEmission) }

        // Unknown is not false.
        guard let resolved = host.resolved else { return Result(.noEmission) }

        if !resolved {
            // A missing host is only a verdict once the budget we promised to
            // wait has actually elapsed.
            return host.attachBudgetExpired == true
                ? Result(.unavailable, notAttached)
                : Result(.noEmission)
        }

        // A host exists. Only the HOST's own statements can make it not-ready.
        if host.hostReportedFailure == true { return Result(.unavailable, notReady) }
        switch host.readinessDeclaredTrue {
        case .some(true): return Result(.capture)
        case .some(false): return Result(.unavailable, notReady)
        // Readiness was never declared either way: we know nothing.
        case .none: return Result(.noEmission)
        }
    }
}

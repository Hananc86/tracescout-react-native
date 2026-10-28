import Foundation

/// THE SESSION'S ACTIVE CAPTURE-FIDELITY POLICY, AS EVIDENCE.
///
/// ## Why it exists
///
/// Build 18 carried the whole fidelity ladder and ran at rung **H**, so a real
/// TestFlight session stored frames in which every ordinary screen was 100%
/// masked. A gate could see the masking but could not see the DECISION, and
/// *"less masking"* and *"rung A was active"* are different claims: a build can
/// have the capability and select the strict rung, and a build that simply
/// stopped masking would satisfy an inference drawn from node counts.
///
/// So the session states the policy directly. The gate asserts BOTH — this, and
/// the product facts — because policy says A does not prove the capture
/// implementation honoured A, and a visible screen does not prove A was chosen.
///
/// ## What it is not
///
/// No user content. No route name, no label, no text. No build, debug or
/// TestFlight identifier — a build identity is not a privacy boundary and every
/// customer build of a beta carries one. No duplicate of the project or app
/// identity, which the envelope already carries separately.
public struct FidelityPolicyEvidence: Equatable {

    /// Did the exact internal fixture scope qualify — project AND bundle?
    public let labScopeQualified: Bool
    /// The persisted UAT-only ladder switch, as READ by the capture path.
    public let ladderMode: Bool
    /// The persisted rung the capture path selected.
    public let rung: FidelityRung

    /// THE RESOLVED CONSENT, TAKEN AT THE SAME MOMENT AS THE POLICY. It
    /// lives on this object rather than beside it so the five certification
    /// facts are one value that cannot be assembled from two different reads —
    /// which is exactly how evidence and capture come to disagree.
    public let interactionCaptureEnabled: Bool

    public init(labScopeQualified: Bool, ladderMode: Bool, rung: FidelityRung,
                interactionCaptureEnabled: Bool) {
        self.interactionCaptureEnabled = interactionCaptureEnabled
        self.labScopeQualified = labScopeQualified
        self.ladderMode = ladderMode
        self.rung = rung
    }

    /// DERIVED, AND THERE IS NO INITIALIZER THAT ACCEPTS IT. An inconsistent
    /// object — `rung:.h_allMasked` alongside `effectivePolicy:
    /// "lab_max_visibility"` — is not something a caller can construct wrongly;
    /// it is unrepresentable. That matters more than a validation rule would:
    /// the whole point of this evidence is that it cannot disagree with the
    /// policy capture actually ran under.
    public var effectivePolicy: String {
        (labScopeQualified && ladderMode && rung == .a_maxVisibility)
            ? "lab_max_visibility" : "strict"
    }

    /// The wire object declared in `envelope.schema.json` as `fidelityPolicy`.
    /// Exactly five keys; the schema pins `additionalProperties: false`, so a
    /// key added here without the schema following is a refused envelope.
    public var wireFields: [(String, String)] {
        [("labScopeQualified", labScopeQualified ? "true" : "false"),
         ("ladderMode", ladderMode ? "true" : "false"),
         ("rung", rung.letter),
         ("effectivePolicy", effectivePolicy),
         ("interactionCaptureEnabled", interactionCaptureEnabled ? "true" : "false")]
    }
}

public extension FidelityRung {
    /// The single letter the wire enum uses (`A`.`H`).
    ///
    /// DERIVED FROM THE RAW VALUE, never a second table. Every case is
    /// `<LETTER>_<description>`, so a new rung cannot be added with a letter
    /// that disagrees with its own name — which a parallel switch statement
    /// would happily allow.
    var letter: String { String(rawValue.prefix(1)) }
}

public extension ResolvedFidelityPolicy {
    /// The evidence for THIS resolution.
    ///
    /// Built from the resolved policy, so the fact reported and the policy
    /// capture runs under are the same object. Re-reading `SecureConfig` here,
    /// or resolving a second time, would let capture say A while the evidence
    /// independently says H — the exact disagreement this exists to prevent.
    /// CONSENT IS A PARAMETER, NOT A RE-READ. Reading it in here would make
    /// this evidence describe a state resolved at a different instant from the
    /// policy beside it — the same disagreement the doc comment above warns
    /// about, arriving through the other half of the object.
    func fidelityEvidence(interactionCaptureEnabled: Bool) -> FidelityPolicyEvidence {
        FidelityPolicyEvidence(labScopeQualified: labScopeActive,
                               ladderMode: ladderMode,
                               rung: rung,
                               interactionCaptureEnabled: interactionCaptureEnabled)
    }
}

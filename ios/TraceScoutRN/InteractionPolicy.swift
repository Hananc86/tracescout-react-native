import Foundation

/// THE iOS TWIN OF THE ONE INTERACTION POLICY.
///
/// IT IS NOT A SECOND POLICY. The semantic authority is
/// `parity-vectors/interaction-policy-vectors.json` — 27 vectors that the JS
/// reference (`mobile-protocol/src/interaction-policy.cjs`), the Kotlin twin
/// (`rn-core/./InteractionPolicy.kt`) and this file are ALL executed against.
/// Three implementations, one contract; the vectors are what make that true
/// rather than aspirational. `InteractionPolicyVectorTests` runs every vector
/// through this type and a missing or unreadable vector is RED, never skipped.
///
/// NO iOS-ONLY SEMANTIC EXCEPTION MAY BE ADDED HERE. If iOS needs different
/// behaviour, the vectors change first and all three implementations follow.
///
/// The ordering below is the contract's, not a convenience: the most
/// restrictive facts are answered first, and every unknown resolves to the more
/// restrictive branch.
public enum InteractionPolicy {

    public enum TargetClass: String, CaseIterable {
        case safe, redacted, editable, secure, unknown
    }

    public enum PositionTreatment: String {
        case blocked, redacted, allowed
    }

    public enum Outcome: String {
        case emitPositional = "emit_positional"
        case emitRedacted   = "emit_redacted"
        case suppress
    }

    /// A COORDINATE IS ONLY EVER IN THE LOGICAL VIEWPORT SPACE the replay
    /// frame is drawn in. Raw device pixels, window-global points and CSS
    /// pixels are three different spaces, and a viewer that guessed which one
    /// it had would land the ripple somewhere the user never touched.
    public static let coordinateSpace = "logical_viewport"

    public static let positionToken = "ts-position-safe"

    /// The fields each outcome is allowed to carry — nothing else may be sent.
    public static let positionalFields = ["interaction", "gestureId", "phase", "x", "y",
                                          "pointerCount", "targetRole", "targetTestTag",
                                          "onRedactedTarget"]
    public static let redactedFields = ["interaction", "gestureId", "phase", "onRedactedTarget"]

    /// NEVER PRODUCIBLE, ON ANY OUTCOME. Text is not evidence of intent, it
    /// IS the content, and a tap that carried it would be a keylogger with a
    /// replay UI.
    public static let forbiddenFields = ["text", "value", "targetText", "targetValue", "label",
                                         "contentDescription", "keyCode", "key", "character",
                                         "inputText"]

    public struct Context {
        public var fromContentWindow: Bool
        public var screenNeverRecord: Bool
        public var textEntryActive: Bool
        public var targetClass: TargetClass
        public var isTap: Bool
        public var pointerCount: Int
        public var positionTreatment: PositionTreatment
        /// A single-pointer MOVEMENT gesture — the sticky classification a
        /// lifecycle takes once its touch travels beyond the tap slop, and
        /// keeps for the rest of that touch even if the finger returns near
        /// where it started.
        ///
        /// LAST AND DEFAULTED, so a caller that has not been taught about
        /// the movement lane gets the pre-existing answer (a swipe suppressed
        /// as an unsupported gesture), never a silently widened one.
        public var isSwipe: Bool

        public init(fromContentWindow: Bool = false,
                    screenNeverRecord: Bool = false,
                    textEntryActive: Bool = false,
                    targetClass: TargetClass = .unknown,
                    isTap: Bool = false,
                    pointerCount: Int = 0,
                    positionTreatment: PositionTreatment = .redacted,
                    isSwipe: Bool = false) {
            self.isSwipe = isSwipe
            self.fromContentWindow = fromContentWindow
            self.screenNeverRecord = screenNeverRecord
            self.textEntryActive = textEntryActive
            self.targetClass = targetClass
            self.isTap = isTap
            self.pointerCount = pointerCount
            self.positionTreatment = positionTreatment
        }
    }

    public struct Decision: Equatable {
        public let outcome: Outcome
        public let reason: String?
        public let fields: [String]
        public let includeCoordinates: Bool
        public let coordinateSpace: String?
    }

    private static func none(_ reason: String) -> Decision {
        Decision(outcome: .suppress, reason: reason, fields: [],
                 includeCoordinates: false, coordinateSpace: nil)
    }

    /// A DECLARATION IS A WHOLE TOKEN, NEVER A SUBSTRING. A nativeID such as
    /// `notes-about-ts-position-safe-policy` must NOT authorise a coordinate.
    public static func isPositionDeclared(_ nativeId: String?) -> Bool {
        guard let s = nativeId, !s.isEmpty else { return false }
        return s.split(whereSeparator: { $0 == " " || $0 == "," || $0 == "\t" || $0 == "\n" })
            .contains { $0 == Substring(positionToken) }
    }

    public static func resolvePositionTreatment(declaredOnThisNode: Bool,
                                                contentTreatment: String?,
                                                inBlockedContext: Bool) -> PositionTreatment {
        if inBlockedContext { return .blocked }
        if contentTreatment == "blocked" { return .blocked }
        return declaredOnThisNode ? .allowed : .redacted
    }

    /// The decision. Order is the contract.
    public static func decide(_ c: Context) -> Decision {
        // A global input hook — the OS keyboard lives in another window — is
        // what must be impossible, so this is answered first.
        if c.fromContentWindow != true { return none("not_content_window") }
        if c.screenNeverRecord == true { return none("never_record") }
        // SUPPRESSED WHILE ANY EDITABLE HOLDS FOCUS, not merely on secure
        // fields: an in-app custom keyboard is an ordinary view tree, so its
        // keys are ordinary safe targets and a sequence of taps on them is a
        // key sequence with timing. This makes keystroke timing STRUCTURALLY
        // unavailable rather than filtered.
        if c.textEntryActive == true { return none("text_entry_active") }
        if c.targetClass == .secure { return none("secure_target") }
        if c.targetClass == .editable { return none("editable_target") }
        // EXACTLY ONE single-pointer gesture class this producer can truthfully
        // name: a tap, or a swipe. Both true is a contradictory input — one
        // physical touch has one sticky classification — and a contradiction
        // fails closed rather than picking the permissive reading, which would
        // let a caller emit a tap row AND a swipe row for one gesture.
        //
        // `scroll` / `pan` / `fling` REMAIN PROTOCOL VALUES THIS PRODUCER
        // DOES NOT CLAIM. The host UI moving is not evidence of which gesture
        // the finger performed.
        let classes = (c.isTap ? 1 : 0) + (c.isSwipe ? 1 : 0)
        if classes != 1 || c.pointerCount != 1 { return none("unsupported_gesture") }

        if c.positionTreatment == .allowed {
            return Decision(outcome: .emitPositional, reason: nil,
                            fields: positionalFields,
                            includeCoordinates: true, coordinateSpace: coordinateSpace)
        }
        // REDACTED AND UNKNOWN BOTH LAND HERE. A target the classifier could
        // not resolve is exactly the one whose coordinate cannot be justified.
        return Decision(outcome: .emitRedacted, reason: nil,
                        fields: redactedFields,
                        includeCoordinates: false, coordinateSpace: nil)
    }
}

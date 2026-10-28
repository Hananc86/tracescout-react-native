import Foundation

// G2-CALLSITE — the production bind: NATIVE SESSION + HOST-DECLARED POLICY
// -> correlation -> (attachment, receive).
//
// an earlier change recorded this slice as blocked on a FACT rather than on effort: the
// attach needed the host app's declared origin allowlist, and no such
// configured fact existed anywhere in the SDK. Board event #1388 is that
// fact. This file is the one place the two native inputs meet.
//
// ## WHY A BINDING REFUSES INSTEAD OF BINDING WITH AN EMPTY POLICY
//
// `RnWebViewSegment.decide` already fails closed on a nil policy, so a bridge
// installed without one could never admit anything. an earlier change's own record named
// the consequence: "a wired attach with no declared policy would install
// bridges that can never admit anything". A bridge that cannot admit is not
// harmless — it is a message handler and a document-start script in every
// qualifying web view, doing nothing, with no refusal anybody sees. So the
// decision is taken BEFORE the attachment, and a session that cannot possibly
// correlate installs nothing at all and says why.
//
// ## NO JS PATH, AND IT IS A MEASURED CONSTRAINT, NOT A PREFERENCE
//
// `rn-ios.yml` pins `RCT_EXTERN_METHOD` in `TraceScoutRnModule.m` at EXACTLY 11
// and separately forbids a JS-callable name carrying a native-owned fact. An
// origin allowlist is the definition of a native-owned fact: a policy a page's
// own runtime could supply would be a policy the page could widen. Everything
// here is reachable from native code only and the JS surface is byte-unchanged.
public enum RnWebViewBinding {

    /// The pure decision. Separated from the construction so every branch is
    /// provable in a venue with no WebKit and no Keychain.
    public enum Admission: Equatable {
        /// A bridge may be bound, with this reviewed allowlist.
        case admit([String])
        /// No bridge may be bound. Carries the typed reason.
        case refuse(String)
    }

    /// THE REFUSAL ORDER IS `RnWebViewSegment.decide`'S OWN ORDER, COPIED
    /// DELIBERATELY RATHER THAN RE-DERIVED: capability -> session -> origin.
    ///
    /// Asking the policy first would report a configuration problem on a device
    /// whose web engine cannot carry a bridge at all — naming the operator's
    /// allowlist where the ENGINE is the fact. Two sites that answer the same
    /// questions in different orders give the same operator two different
    /// stories about one device.
    public static func decide(nativeSessionId: String,
                              declared: [String]?,
                              capability: RnWebViewSegment.Capability) -> Admission {
        // (1) CAPABILITY. the specification: an unsupported engine yields no segment row at
        // all, so the reason names the engine.
        if capability != .supported { return .refuse(capability.rawValue) }

        // (2) OUR OWN IDENTITY, before we mint anything to attach to it. Shares
        // the shared decision's vocabulary: one fact, one code.
        if !RnWebViewSegment.isValidSessionId(nativeSessionId) {
            return .refuse("native_session_invalid")
        }

        // (3) THE HOST'S DECLARATION.
        switch RnWebViewOriginPolicy.admit(declared) {
        case .absent:
            return .refuse("origin_policy_absent")
        case .refused(let why):
            return .refuse(why)
        case .declared(let origins):
            // A DELIBERATELY EMPTY ALLOWLIST IS NOT A CONFIGURATION ERROR,
            // AND IT IS STILL NOT A BRIDGE. It is a reviewed decision that no
            // origin may correlate, so the honest outcome is no bridge and a
            // reason that is distinguishable from "nothing was configured".
            if origins.isEmpty { return .refuse("origin_policy_empty") }
            return .admit(origins)
        }
    }

    /// The result of a bind. Not `Equatable` — it carries a reference type, and
    /// an identity comparison on a freshly minted correlation would compare two
    /// things that are never meant to be equal.
    public struct Result {
        /// The bound correlation, or nil when the bind was refused.
        public let correlation: RnWebViewCorrelation?
        /// The typed refusal, or nil when a correlation was bound.
        public let refusal: String?

        /// Exactly one of the two is always present.
        public var isBound: Bool { correlation != nil }
    }

    /// Bind one web view's correlation from native state.
    ///
    /// THE POLICY IS CAPTURED HERE AND THE CORRELATION CARRIES IT. That is
    /// the contract's "policy bound to native session": the allowlist that
    /// admits a message is the one this session was bound with, so nothing a
    /// later caller does can widen the policy a live session is admitting
    /// under. Revocation is a separate question with a separate answer — see
    /// `RnWebViewCorrelation.revalidate`.
    public static func bind(nativeSessionId: String,
                            declared: [String]?,
                            capability: RnWebViewSegment.Capability) -> Result {
        switch decide(nativeSessionId: nativeSessionId,
                      declared: declared, capability: capability) {
        case .refuse(let why):
            return Result(correlation: nil, refusal: why)
        case .admit(let origins):
            return Result(correlation: RnWebViewCorrelation(
                nativeSessionId: nativeSessionId,
                policy: origins,
                capability: capability), refusal: nil)
        }
    }

    /// Bind from the SDK's own persisted native configuration.
    ///
    /// THE ALLOWLIST IS READ FROM `SecureConfig`, THE NATIVE-OWNED
    /// CONFIGURATION HOME — never from an argument a JS caller could reach and
    /// never from the React props. This is the "native-owned SDK configuration
    /// at initialization" half of the contract, and the read is here so there
    /// is exactly one of it.
    public static func bindFromConfiguration(
        nativeSessionId: String,
        capability: RnWebViewSegment.Capability = RnWebViewCapability.measured()
    ) -> Result {
        bind(nativeSessionId: nativeSessionId,
             declared: SecureConfig.webViewOriginAllowlist(),
             capability: capability)
    }
}

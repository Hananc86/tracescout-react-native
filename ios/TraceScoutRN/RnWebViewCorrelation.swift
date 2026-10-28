import Foundation

// MODEL B — THE PRODUCTION WIRING: mint → preflight → decide → carrier.
//
// `RnWebViewSegment.decide` is the contract; until this file existed it had
// ZERO production callers, and an earlier change recorded that as the honest status rather
// than hiding it. This is the call path that changes it.
//
// ## WHY THIS IS NATIVE-ONLY, AND IT IS A MEASURED CONSTRAINT
//
// `rn-ios.yml` pins `RCT_EXTERN_METHOD` in `TraceScoutRnModule.m` at EXACTLY
// 11 and separately forbids any JS-callable method whose name contains
// `privacy`, `posture`, `secure`, `mask`, `producer`, `key`, `credential`,
// `sequence` … So a WebView-correlation bridge method is doubly impossible: the
// exact count forbids a twelfth method at all, and the property forbids a
// JS-callable name for a native-owned fact. That is not an inconvenience to
// work around — it is the same rule that keeps the segment id native-minted.
// Everything here is reachable from native code only; the JS surface is
// byte-unchanged.
//
// ## WHAT IS PROVEN HERE AND WHAT IS NOT
//
// The mint, the preflight DECISION and the coordinator are pure Foundation, so
// they run under `swift test` on macOS exactly as they run on a device. The
// part that cannot be proven in that venue is the ATTACHMENT — finding the real
// `WKWebView`, installing a user script and a message handler on it, and
// receiving a real `WKScriptMessage`. That needs UIKit + WebKit + a laid-out
// view, and it is named as the remaining gate rather than claimed.

/// The iOS WebView capability preflight.
///
/// THE VOCABULARY IS SHARED WITH ANDROID ON PURPOSE, AND ONE OF ITS STATES
/// IS NOT PRODUCIBLE HERE. `webview_provider_unsupported` describes a too-old
/// *updateable system component*, which is an Android fact: Android System
/// WebView ships separately from the OS (the API-26 emulator carries WebView 69
/// and dies inside its own engine on any HTTP/2 document). iOS has no such
/// component — the web engine ships with the OS — so this preflight can return
/// `supported`, `featureUnsupported` or `providerUnavailable`, and NEVER
/// `providerUnsupported`. Stating that is better than inventing an iOS
/// condition to fill the slot, and better than minting a second set of codes
/// that would make an iOS refusal and an Android refusal incomparable in a
/// report that has to cover both.
public enum RnWebViewCapability {

    /// The runtime facts the verdict is computed from, injected rather than
    /// read, so the DECISION is testable in a venue with no WebKit at all.
    ///
    /// EVERY FIELD DEFAULTS TO THE PESSIMISTIC ANSWER. A fact we failed to
    /// gather must never read as a capability we have.
    public struct Facts: Equatable {
        /// Is a web engine class present at runtime at all?
        public let webEngineAvailable: Bool
        /// Can a script message handler be installed in an ISOLATED content
        /// world? See `isolatedWorldAvailable`'s note — this is the feature the
        /// bridge actually calls, and the only one that matters on iOS.
        public let isolatedWorldAvailable: Bool
        /// Can a user script be injected at DOCUMENT START? Identity must be in
        /// place before the document's own scripts run, or a page could observe
        /// or forge — the handshake.
        public let documentStartInjectionAvailable: Bool

        public init(webEngineAvailable: Bool = false,
                    isolatedWorldAvailable: Bool = false,
                    documentStartInjectionAvailable: Bool = false) {
            self.webEngineAvailable = webEngineAvailable
            self.isolatedWorldAvailable = isolatedWorldAvailable
            self.documentStartInjectionAvailable = documentStartInjectionAvailable
        }
    }

    /// ORDER IS LOAD-BEARING, AND IT IS THE ANDROID PREFLIGHT'S ORDER,
    /// COPIED DELIBERATELY RATHER THAN RE-DERIVED: absent engine → missing
    /// feature. Checking features first reports `feature_unsupported` for an
    /// engine that is not there at all, which describes the symptom and sends
    /// the reader after a feature flag instead of the engine.
    public static func decide(_ f: Facts) -> RnWebViewSegment.Capability {
        if !f.webEngineAvailable { return .providerUnavailable }
        if !f.isolatedWorldAvailable || !f.documentStartInjectionAvailable {
            return .featureUnsupported
        }
        return .supported
    }

    /// The facts as measured on THIS runtime.
    ///
    /// `canImport(WebKit)` IS NOT A LINK AGAINST WEBKIT IN THE HOST APP'S
    /// PRODUCT. The hierarchy walker deliberately identifies web content by
    /// CLASS NAME so that linking WebKit is never forced on a host app, and
    /// this file keeps that rule: the import is confined to the measurement,
    /// and the pessimistic answer is what a build without WebKit gets.
    public static func measured() -> RnWebViewSegment.Capability {
        #if canImport(WebKit)
        // An isolated content world and document-start injection are the two
        // runtime features the bridge calls. Both are iOS 14 / macOS 11 and
        // later; `#available` is the measurement, not a version string compared
        // by hand.
        if #available(iOS 14.0, macOS 11.0, *) {
            return decide(Facts(webEngineAvailable: true,
                                isolatedWorldAvailable: true,
                                documentStartInjectionAvailable: true))
        }
        return decide(Facts(webEngineAvailable: true,
                            isolatedWorldAvailable: false,
                            documentStartInjectionAvailable: true))
        #else
        return decide(Facts())
        #endif
    }
}

/// Mints the segment identifier.
///
/// THE NATIVE SIDE MINTS, ALWAYS. The whole correlation claim rests on the
/// page being able only to ECHO an id it was given: `decide` refuses anything
/// that is not byte-equal to the `expectedSegmentId` the native side holds, so
/// a frame cannot attach itself to — or overwrite — another segment's posture.
/// A page-chosen id would make that refusal meaningless.
public enum RnWebViewMint {

    /// 128 bits, base64url, unpadded → 22 characters, inside the protocol's
    /// `^[A-Za-z0-9_-]{8,64}$`.
    ///
    /// `SystemRandomNumberGenerator` IS THE CRYPTOGRAPHIC SOURCE on Apple
    /// platforms (documented as such; it wraps the platform CSPRNG). A segment
    /// id that an observer could predict would let a hostile frame echo an id
    /// it was never given — so this is not a place for a counter, a timestamp
    /// or `arc4random_uniform` over a small alphabet.
    ///
    /// NO SESSION, DEVICE OR INSTALL IDENTITY IS DERIVED INTO IT. It is
    /// opaque randomness: the correlation is established by the native session
    /// carrying the segment id (N + S), never by the id itself encoding N.
    public static func mintSegmentId() -> String {
        var g = SystemRandomNumberGenerator()
        var bytes = [UInt8]()
        bytes.reserveCapacity(16)
        for _ in 0..<2 {
            var v = UInt64.random(in: UInt64.min...UInt64.max, using: &g)
            for _ in 0..<8 { bytes.append(UInt8(truncatingIfNeeded: v)); v >>= 8 }
        }
        return base64url(bytes)
    }

    /// Unpadded base64url. Written out rather than post-processing
    /// `Data.base64EncodedString` so no `+`, `/` or `=` can reach a value the
    /// protocol's identifier pattern would refuse.
    static func base64url(_ bytes: [UInt8]) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        var out = ""
        var i = 0
        while i + 2 < bytes.count {
            let n = (UInt32(bytes[i]) << 16) | (UInt32(bytes[i + 1]) << 8) | UInt32(bytes[i + 2])
            out.append(alphabet[Int((n >> 18) & 63)])
            out.append(alphabet[Int((n >> 12) & 63)])
            out.append(alphabet[Int((n >> 6) & 63)])
            out.append(alphabet[Int(n & 63)])
            i += 3
        }
        let rem = bytes.count - i
        if rem == 1 {
            let n = UInt32(bytes[i]) << 16
            out.append(alphabet[Int((n >> 18) & 63)])
            out.append(alphabet[Int((n >> 12) & 63)])
        } else if rem == 2 {
            let n = (UInt32(bytes[i]) << 16) | (UInt32(bytes[i + 1]) << 8)
            out.append(alphabet[Int((n >> 18) & 63)])
            out.append(alphabet[Int((n >> 12) & 63)])
            out.append(alphabet[Int((n >> 6) & 63)])
        }
        return out
    }
}

/// ONE WebView's correlation state — THE PRODUCTION CALLER OF
/// `RnWebViewSegment.decide`.
///
/// It holds what only the native side may hold (the minted segment id, the
/// native session id, the declared origin allowlist, the preflight verdict) and
/// exposes exactly two things to the rest of the producer: the decision for an
/// inbound message, and the envelope carrier for an admitted segment.
///
/// NOT A `class` WITH MUTABLE PAGE-DERIVED STATE BY ACCIDENT. The admitted
/// segment is replaced only by a LATER ADMITTED decision, so a refused message
/// can never clear a correlation that was already established — a hostile frame
/// must not be able to detach a legitimate one by sending rubbish.
public final class RnWebViewCorrelation {

    /// Minted natively at construction. A page never supplies it.
    public let segmentId: String
    public let nativeSessionId: String
    /// The host app's declared allowlist. Nil = none declared, which
    /// `decide` refuses `origin_policy_absent` — fail closed.
    public let policy: [String]?
    /// Measured once per WebView, at the moment the bridge would be installed.
    public let capability: RnWebViewSegment.Capability

    /// The last ADMITTED segment, or nil while none has been established.
    private var admitted: RnWebViewSegment.Segment?

    public init(nativeSessionId: String,
                policy: [String]?,
                capability: RnWebViewSegment.Capability,
                segmentId: String = RnWebViewMint.mintSegmentId()) {
        self.nativeSessionId = nativeSessionId
        self.policy = policy
        self.capability = capability
        self.segmentId = segmentId
    }

    /// SHOULD THE BRIDGE BE INSTALLED AT ALL? the specification says an unsupported
    /// engine yields no segment row AT ALL. `decide` enforces that on every
    /// message, so this is a cheap pre-answer for the attach site and NOT the
    /// gate — the gate is inside the decision, where a route that forgets
    /// cannot bypass it.
    public var shouldInstallBridge: Bool { capability == .supported }

    /// Decide one inbound bridge message and remember an admission.
    ///
    /// The ONE production call into `RnWebViewSegment.decide`.
    @discardableResult
    public func receive(kind: String?,
                        fields: [String: RnWebViewSegment.Field],
                        authenticatedOrigin: String?) -> RnWebViewSegment.Decision {
        let d = RnWebViewSegment.decide(capability: capability,
                                        policy: policy,
                                        kind: kind,
                                        fields: fields,
                                        expectedSegmentId: segmentId,
                                        nativeSessionId: nativeSessionId,
                                        authenticatedOrigin: authenticatedOrigin)
        // ONLY AN ADMISSION WRITES. A refusal leaves an established
        // correlation exactly as it was.
        if d.outcome == .admit, let s = d.segment { admitted = s }
        return d
    }

    /// The admitted segment, or nil.
    public var segment: RnWebViewSegment.Segment? { admitted }

    /// DROP AN ADMISSION THAT IS NO LONGER ENTITLED TO ITS TRUST — the
    /// "revocation and navigation must not retain stale trust" half of the
    /// origin-policy contract.
    ///
    /// THE GAP THIS CLOSES. Admission is per message and was already
    /// correct; what had no answer was the LIFETIME of an admission after it
    /// was granted. `admitted` is replaced only by a LATER ADMITTED decision —
    /// deliberately, so a hostile frame cannot detach a legitimate correlation
    /// by sending rubbish — and the consequence was that nothing could ever
    /// retire one. `carrier` is read for EVERY event of the session, so a
    /// segment admitted for one origin kept riding events after the web view
    /// navigated elsewhere: a false datum in the exact row this lane produces.
    ///
    /// THE ASYMMETRY IS THE DESIGN, AND IT IS WHAT MAKES BOTH CLAUSES OF THE
    /// CONTRACT TRUE AT ONCE. The contract says the policy is bound to the
    /// native session AND that revocation must not retain stale trust:
    ///
    /// WIDENING mid-session -> NOT applied. Admission uses the policy this
    /// session was BOUND with, or "bound to the
    /// native session" would mean nothing and a
    /// runtime change could admit an origin the
    /// session was never configured for.
    /// REVOCATION mid-session -> applied AT ONCE. A narrowing can only ever
    /// refuse, so honouring it immediately is always
    /// the fail-closed direction.
    ///
    /// So `livePolicy` is consulted ONLY to retire an admission, never to grant
    /// one. There is no path here that admits anything.
    ///
    /// IT RETURNS THE VERDICT RATHER THAN SWALLOWING IT, so a caller can
    /// record WHY a correlation was retired. A silent drop and a correlation
    /// that was never established look identical from the outside.
    @discardableResult
    public func revalidate(currentOrigin: String?,
                           livePolicy: [String]?) -> RnWebViewTrustLifetime.Verdict {
        let verdict = RnWebViewTrustLifetime.decide(
            admittedOrigin: admitted?.origin,
            currentOrigin: currentOrigin,
            livePolicy: livePolicy)
        if case .drop = verdict { admitted = nil }
        return verdict
    }

    /// Revalidate against the SDK's own persisted configuration and a live web
    /// view, both read natively.
    ///
    /// THE LIVE POLICY IS RE-READ HERE, NOT CACHED. That read is the whole
    /// mechanism by which a revocation reaches a running session; a cached copy
    /// would make `revalidate` unable to observe the one event it exists for.
    @discardableResult
    public func revalidate(webView: AnyObject) -> RnWebViewTrustLifetime.Verdict {
        revalidate(currentOrigin: RnWebViewObjC.currentOrigin(of: webView),
                   livePolicy: SecureConfig.webViewOriginAllowlist())
    }

    /// The envelope carrier for every event of this hybrid session, or nil when
    /// nothing has been admitted.
    ///
    /// THE CARRIER IS DERIVED FROM THE ADMITTED SEGMENT, NEVER FROM
    /// `segmentId`. Returning a carrier built from the minted id alone would
    /// put a correlation on the wire that no message ever established — which
    /// is the entire failure Model B's "N + S, never S alone" forbids.
    public var carrier: RnEnvelope.WebViewRef? {
        admitted.map { RnEnvelope.WebViewRef.of($0) }
    }

    /// The identity payload a document-start user script injects into an
    /// ADMITTED world so the page can ECHO it back.
    ///
    /// IT CARRIES THE SEGMENT ID AND NOTHING ELSE — no session id, no
    /// project id, no posture, no credential. The page's only job is to echo
    /// the id and report its own posture; it is never told anything about the
    /// native session, because `decide` correlates N and S natively and a page
    /// that knew N could claim one.
    ///
    /// NIL WHEN THE BRIDGE MUST NOT BE INSTALLED. There is no payload for an
    /// unsupported engine, so the attach site cannot inject one by forgetting
    /// to check.
    public var identityScriptPayload: [String: String]? {
        guard shouldInstallBridge else { return nil }
        return ["segmentId": segmentId]
    }
}

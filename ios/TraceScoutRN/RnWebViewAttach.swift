import Foundation
import ObjectiveC

// MODEL B — G2-ATTACH: finding a real WKWebView, installing the correlation
// bridge in an ISOLATED content world, and routing a real `WKScriptMessage`
// into `RnWebViewCorrelation.receive`.
//
// ## THIS FILE NAMES NO WEBKIT TYPE, AND THAT IS THE WHOLE POINT
//
// `HierarchySnapshotter.isWebContent` identifies web content by CLASS NAME so
// that "importing WebKit for a type check would make WebKit a hard dependency
// of every host app" (its own words). `TraceScoutRN.podspec` ships
// `Sources/TraceScoutRN/**/*.swift` as ONE pod, so a single `import WebKit`
// anywhere here autolinks WebKit into every host that installs the pod —
// including the ones with no WebView at all. So every WebKit touch below goes
// through the Objective-C runtime, and `grep -c "import WebKit" Sources/` stays
// at zero. The TEST target may import WebKit: a test plays the host app, and a
// host that has a WKWebView already links it.
//
// ## EVERY RUNTIME IDIOM HERE WAS MEASURED BEFORE IT WAS WRITTEN
//
// On the dedicated CI simulator (iOS 26.3, Xcode 26.3), in a bare SwiftPM
// xctest bundle with no app host and no key window:
//
// WKWebView / WKUserScript / WKContentWorld resolve by name YES
// `configuration` -> `userContentController` through KVC YES, and it
// IS the live object (identity-compared against the typed property)
// `+[WKContentWorld worldWithName:]` YES
// 4-arg `initWithSource:injectionTime:forMainFrameOnly:inContentWorld:`
// through the runtime -> injectionTime 0, forMainFrameOnly true
// a real `WKScriptMessage` delivered into a plain NSObject handler YES
// `frameInfo.securityOrigin` -> protocol `https`, host `help.example.com`
//
// Two measurements CHANGED the design and are recorded where they bite:
// `objc_getProtocol("WKScriptMessageHandler")` is **absent** at runtime and
// conformance is **not required** (see `RnWebViewMessageRouter`), and the
// deprecated `preferences.javaScriptEnabled` **lies** (see `pageFacts`).

// MARK: - The host-page gate

/// Is this page one a BROWSER SDK could run on at all?
///
/// THIS IS NOT "CAN WE INJECT OUR SCRIPT" — MEASURED, AND THE DIFFERENCE IS
/// THE WHOLE REASON THE GATE EXISTS. A document-start `WKUserScript` runs even
/// when `allowsContentJavaScript` is false: that setting governs the PAGE's own
/// scripts, not ours. So we
/// could always install. What we could not have is **B** — the browser
/// recorder's content — because the page's own SDK can never run. Installing
/// there would mint a segment id and admit a correlation pointing at a recorder
/// that does not exist, which is claiming capture on a page that cannot be
/// captured.
public enum RnWebViewHostPage {

    /// EVERY FIELD DEFAULTS TO THE ANSWER THAT REFUSES. A fact we failed to
    /// read must never read as a capability the page has.
    public struct Facts: Equatable {
        /// The page's OWN scripts may run. Read natively from
        /// `defaultWebpagePreferences.allowsContentJavaScript`.
        public let javaScriptEnabled: Bool
        /// The scheme of the document currently loaded, or nil for no document.
        public let urlScheme: String?

        public init(javaScriptEnabled: Bool = false, urlScheme: String? = nil) {
            self.javaScriptEnabled = javaScriptEnabled
            self.urlScheme = urlScheme
        }
    }

    public enum Verdict: Equatable {
        case supportsBrowserSdk
        /// A typed reason, never a bare `false`.
        case refused(String)
    }

    /// The schemes a browser SDK can be served on. CLOSED on purpose.
    ///
    /// `file:` IS DELIBERATELY ABSENT even though a bundled page could carry
    /// the SDK: a `file:` document's origin is opaque, and the shared decision
    /// refuses every opaque origin (`origin_malformed`). Installing where the
    /// handshake can never be admitted is the same false promise as installing
    /// on a JS-disabled page.
    static let WEB_SCHEMES: Set<String> = ["http", "https"]

    /// ORDER IS LOAD-BEARING. No document first: reporting
    /// `javascript_disabled` for a WebView with nothing loaded describes a
    /// setting on a page that is not there. Scheme before JavaScript: an
    /// `about:` document cannot carry the browser SDK even with JavaScript
    /// switched on, so the scheme is the more actionable fact — and it is
    /// exactly the fixture's `webview_help` route, which is `about:` AND
    /// JS-disabled.
    public static func decide(_ f: Facts) -> Verdict {
        guard let scheme = f.urlScheme?.lowercased(), !scheme.isEmpty else {
            return .refused("no_document")
        }
        if !WEB_SCHEMES.contains(scheme) { return .refused("scheme_not_web") }
        if !f.javaScriptEnabled { return .refused("javascript_disabled") }
        return .supportsBrowserSdk
    }
}

// MARK: - The Objective-C runtime seam

/// Every WebKit touch in the SDK, and nothing else.
enum RnWebViewObjC {

    /// The ISOLATED content world the bridge lives in.
    ///
    /// A NAMED WORLD, NOT `defaultClientWorld`. Every client script shares
    /// the default client world, so a second SDK in the same app could read or
    /// overwrite the identity we inject. A named world is ours alone, and the
    /// page's own scripts cannot see into either.
    static let worldName = "com.tracescout.rn.webview"

    /// The handler name the page-side bridge posts to.
    static let handlerName = "tsWebViewBridge"

    static func cls(_ n: String) -> AnyClass? { NSClassFromString(n) }

    /// One KVC hop, guarded by `responds(to:)`.
    ///
    /// THE GUARD IS NOT TIDINESS. KVC for an unknown key raises
    /// `NSUnknownKeyException`, and an Objective-C exception cannot be caught
    /// from Swift — it would terminate the host app. So an absent key must be
    /// answered before it is asked.
    static func kv(_ obj: AnyObject, _ key: String) -> AnyObject? {
        guard let o = obj as? NSObject,
              o.responds(to: NSSelectorFromString(key)) else { return nil }
        return o.value(forKey: key) as AnyObject?
    }

    /// A one-argument class method returning an object (+0 autoreleased).
    static func classObject(_ c: AnyClass, _ sel: String, _ arg: String) -> AnyObject? {
        let s = NSSelectorFromString(sel)
        guard class_getClassMethod(c, s) != nil else { return nil }
        return (c as AnyObject).perform(s, with: arg as NSString)?.takeUnretainedValue()
    }

    /// `+[WKContentWorld worldWithName:]`, or nil where WebKit is not loaded.
    static func isolatedWorld() -> AnyObject? {
        guard let c = cls("WKContentWorld") else { return nil }
        return classObject(c, "worldWithName:", worldName)
    }

    /// `+[WKContentWorld pageWorld]` — the world the DOCUMENT's own scripts run
    /// in, and therefore the only world the page's browser SDK can read from.
    /// Nil where WebKit is not loaded.
    static func pageWorld() -> AnyObject? {
        guard let c = cls("WKContentWorld") else { return nil }
        let s = NSSelectorFromString("pageWorld")
        guard class_getClassMethod(c, s) != nil else { return nil }
        return (c as AnyObject).perform(s)?.takeUnretainedValue()
    }

    /// The LIVE `WKUserContentController` of a web view.
    ///
    /// `configuration` IS DOCUMENTED AS RETURNING A COPY, SO THIS WAS
    /// MEASURED RATHER THAN ASSUMED: the controller reached this way is
    /// identity-equal to the typed `webView.configuration.userContentController`,
    /// which is the object WebKit itself serves scripts and handlers from.
    static func userContentController(of webView: AnyObject) -> AnyObject? {
        guard let cfg = kv(webView, "configuration") else { return nil }
        return kv(cfg, "userContentController")
    }

    /// `[[WKUserScript alloc] initWithSource:injectionTime:forMainFrameOnly:inContentWorld:]`
    ///
    /// THE RETAIN ARITHMETIC, WRITTEN OUT, BECAUSE AN UNSAFE CALL THAT IS
    /// NEARLY RIGHT CRASHES SOMEBODY ELSE'S APP. `alloc` returns +1 that nobody
    /// owns. `takeUnretainedValue` adds an ARC retain, so the object is at 2.
    /// `init` returns `self` without changing the count (the "init consumes
    /// self" convention is about OWNERSHIP, not a release), and
    /// `takeRetainedValue` claims one of the two for ARC — exactly balancing
    /// `alloc`'s. The local's own retain is released at scope end, leaving one
    /// owner. Neither a leak nor an over-release, and the simulator run that
    /// reads `injectionTime` and `isForMainFrameOnly` back off the result is
    /// what proves the call landed.
    ///
    /// `injectionTime` 0 == `WKUserScriptInjectionTimeAtDocumentStart`. Document
    /// START is load-bearing: the identity must be in place before the
    /// document's own scripts run, or a page could observe — or forge — the
    /// handshake.
    static func makeDocumentStartScript(source: String,
                                        mainFrameOnly: Bool,
                                        world: AnyObject) -> AnyObject? {
        guard let c = cls("WKUserScript") else { return nil }
        let initSel = NSSelectorFromString(
            "initWithSource:injectionTime:forMainFrameOnly:inContentWorld:")
        guard let m = class_getInstanceMethod(c, initSel) else { return nil }
        guard let allocd = (c as AnyObject)
            .perform(NSSelectorFromString("alloc"))?.takeUnretainedValue() else { return nil }
        typealias Init4 = @convention(c)
            (AnyObject, Selector, NSString, Int, ObjCBool, AnyObject) -> Unmanaged<AnyObject>?
        let f = unsafeBitCast(method_getImplementation(m), to: Init4.self)
        guard let out = f(allocd, initSel, source as NSString, 0,
                          ObjCBool(mainFrameOnly), world) else { return nil }
        return out.takeRetainedValue()
    }

    /// `-[WKWebView evaluateJavaScript:inFrame:inContentWorld:completionHandler:]`
    /// into the MAIN FRAME of the document CURRENTLY committed, in `world`.
    /// Returns false when the selector is unavailable (nothing was evaluated).
    ///
    /// G2-FIRSTDOC. A document-start `WKUserScript` reaches only the
    /// documents that START after it is added, and `attach` refuses
    /// `no_document` before the first navigation — so the ordinary attach (a
    /// web view the capture walk found already showing a page) left that very
    /// page untold until its next navigation. This one-shot call gives the
    /// committed document the SAME two scripts, in the SAME two worlds; each
    /// script still carries its own gate (main frame only; the page-world one
    /// also its in-script `location.origin` allowlist check), so the evaluation
    /// widens nothing a document-start install would not have done.
    ///
    /// `inFrame: nil` IS THE MAIN FRAME (WebKit's documented meaning).
    /// The completion handler is nil unless a caller asks for the result —
    /// only the an earlier change page-identity read-back does, and it receives the result
    /// alone (an evaluation error reads as nil, i.e. unreadable). Evaluation is
    /// asynchronous; a navigation that replaces the document first is covered
    /// by the document-start script instead.
    @discardableResult
    static func evaluateInCommittedMainFrame(_ webView: AnyObject,
                                             source: String,
                                             world: AnyObject,
                                             completion: ((AnyObject?) -> Void)? = nil) -> Bool {
        let sel = NSSelectorFromString("evaluateJavaScript:inFrame:inContentWorld:completionHandler:")
        guard let o = webView as? NSObject, o.responds(to: sel),
              let m = class_getInstanceMethod(type(of: o), sel) else { return false }
        typealias Eval4 = @convention(c)
            (AnyObject, Selector, NSString, AnyObject?, AnyObject, AnyObject?) -> Void
        let f = unsafeBitCast(method_getImplementation(m), to: Eval4.self)
        // The result only; an evaluation error is an UNREADABLE answer (nil),
        // never a guess. The block is materialised as a heap object first and
        // passed as an object pointer — the same shape every other runtime
        // call in this file uses — so no compiler-chosen block lifetime is
        // involved (a direct block-typed parameter crashed the host, an earlier change).
        var block: AnyObject?
        if let c = completion {
            typealias Done = @convention(block) (AnyObject?, AnyObject?) -> Void
            let done: Done = { v, e in c(e == nil ? v : nil) }
            block = unsafeBitCast(done, to: AnyObject.self)
        }
        f(o, sel, source as NSString, nil, world, block)
        return true
    }

    /// The page-support facts, read NATIVELY.
    ///
    /// `preferences.javaScriptEnabled` IS NOT A FALLBACK, IT IS A WRONG
    /// ANSWER. Measured on one web view configured with
    /// `allowsContentJavaScript = false`: the modern key reads `false` and the
    /// deprecated one reads **`true`**. Treating the legacy key as a pre-iOS-14
    /// fallback would therefore report a JS-disabled page as JS-enabled — which
    /// is precisely the "claim capture on an unsupported page" this gate exists
    /// to prevent. Where the modern key is unreadable we FAIL CLOSED instead.
    static func pageFacts(of webView: AnyObject) -> RnWebViewHostPage.Facts {
        let cfg = kv(webView, "configuration")
        let js = cfg
            .flatMap { kv($0, "defaultWebpagePreferences") }
            .flatMap { kv($0, "allowsContentJavaScript") } as? Bool
        let scheme = (kv(webView, "URL") as? NSURL)?.scheme
        return RnWebViewHostPage.Facts(javaScriptEnabled: js ?? false, urlScheme: scheme)
    }

    /// `scheme://host[:port]`, in the shape the shared decision's `ORIGIN`
    /// pattern accepts, from WebKit's OWN attribution of the calling frame.
    ///
    /// THE DEFAULT PORT IS OMITTED. `WKSecurityOrigin.port` is 0 for a
    /// default port, and `https://host:443` is not what the pattern or the
    /// host app's declared allowlist would carry.
    static func origin(of securityOrigin: AnyObject) -> String? {
        guard let proto = kv(securityOrigin, "protocol") as? String, !proto.isEmpty,
              let host = kv(securityOrigin, "host") as? String, !host.isEmpty
        else { return nil }
        let port = (kv(securityOrigin, "port") as? NSNumber)?.intValue ?? 0
        return spell(scheme: proto, host: host, port: port)
    }

    /// ONE HOME FOR HOW AN ORIGIN IS SPELLED. Two callers now ask it —
    /// WebKit's `WKSecurityOrigin` (per message) and the web view's own current
    /// `URL` (per revalidation) — and they MUST agree character for character,
    /// because `RnWebViewTrustLifetime` compares the two strings. A second
    /// default-port rule would make a navigation to the same origin look like a
    /// navigation away from it, dropping a correlation that never moved.
    static func spell(scheme: String, host: String, port: Int) -> String {
        let s = scheme.lowercased()
        let isDefault = (s == "https" && port == 443) || (s == "http" && port == 80)
        if port == 0 || isDefault { return "\(s)://\(host.lowercased())" }
        return "\(s)://\(host.lowercased()):\(port)"
    }

    /// The origin of the document CURRENTLY loaded in this web view, or nil
    /// when it cannot be established.
    ///
    /// READ THROUGH THE OBJC RUNTIME, LIKE EVERY OTHER WEBKIT TOUCH HERE.
    /// The podspec ships `Sources/TraceScoutRN` as ONE pod, so a single
    /// `import WebKit` would autolink WebKit into every host app that installs
    /// it — including hosts with no web view at all. `URL` is a plain KVC read
    /// of an `NSURL`, guarded by `responds(to:)` through `kv`.
    ///
    /// NIL IS "I COULD NOT ESTABLISH IT", NEVER "THERE IS NO ORIGIN", and
    /// the caller treats it as fail-closed. A `nil` port from `NSURL` means the
    /// URL carried no explicit port, which `spell` renders as the default — not
    /// a missing fact.
    static func currentOrigin(of webView: AnyObject) -> String? {
        guard let url = kv(webView, "URL") as? NSURL,
              let scheme = url.scheme, !scheme.isEmpty,
              let host = url.host, !host.isEmpty
        else { return nil }
        return spell(scheme: scheme, host: host, port: url.port?.intValue ?? 0)
    }
}

/// The two methods the bridge calls on a `WKUserContentController`.
///
/// AN `@objc` PROTOCOL PLUS `unsafeBitCast` IS WHAT MAKES THIS TYPED WITHOUT
/// NAMING WEBKIT. The cast is sound because an `@objc` protocol existential is
/// one object pointer; dispatch is an ordinary `objc_msgSend` on the selectors
/// below, so the compiler — not hand-written ARC — handles the conventions.
@objc protocol RnWebViewUserContent {
    func addUserScript(_ script: AnyObject)
    @objc(addScriptMessageHandler:contentWorld:name:)
    func addScriptMessageHandler(_ handler: AnyObject, contentWorld: AnyObject, name: String)
}

// MARK: - The page-world identity (S5 Model B, the browser half of the join)

/// The ONE thing the PAGE world is told: which natively minted WebView segment
/// this document belongs to — so the page's own browser SDK can stamp it on its
/// v1 envelopes (`browser-sdk/src/core/webViewSegment.js`) and the server can
/// join a browser request to the native replay timeline.
///
/// THE SAME GLOBAL, THE SAME SHAPE, THE SAME RULE AS ANDROID.
/// `RnWebViewAttachPlan.identityScript` writes
/// `window.__tsRnNative=Object.freeze({segmentId:"…"})`, injected by
/// `addDocumentStartJavaScript` into ADMITTED origins only. Until an earlier change iOS
/// wrote nothing into the page world (its hello is posted from an ISOLATED
/// world), so on iOS the browser SDK's reader always answered null and no iOS
/// browser request could ever join the native timeline.
///
/// THE ORIGIN GATE MOVES INTO THE SCRIPT, BECAUSE `WKUserScript` HAS NO
/// ORIGIN RULES. Android's `allowedOriginRules` is enforced by the browser;
/// WebKit injects a user script into every main-frame document. So the script
/// compares `location.origin` — which a page cannot forge, and which at
/// document start no page script has yet run to observe — against the
/// admitted allowlist baked in at install time. An unadmitted document is
/// never told the segment id. The allowlist is normalized by the SAME
/// `RnWebViewSegment.normalizePolicy` the per-message decision uses, so the
/// two cannot disagree about one origin.
///
/// ONLY THE GLOBAL CROSSES, NEVER THE CHANNEL. The hello channel stays
/// registered in the isolated world alone, so a page-world script still has no
/// `window.webkit.messageHandlers.tsWebViewBridge` and still cannot post a
/// forged hello — an open product question's "segment_id_mismatch is unreachable from a page on
/// iOS" is unchanged. The page learning the id is what Android already does:
/// the stamp is a CLAIM, verified by the consumer's N + S check.
///
/// MAIN FRAME ONLY, LIKE THE HELLO. Subframe correlation is G2-SUBFRAME.
///
/// IT CARRIES THE SEGMENT ID AND NOTHING ELSE — no session id, no project
/// id, no posture.
public enum RnWebViewPageIdentity {

    /// `RnWebViewAttachPlan.IDENTITY_GLOBAL`, verbatim.
    public static let IDENTITY_GLOBAL = "__tsRnNative"

    /// `RnWebViewAttachPlan.isSafeIdentityValue`, verbatim: unpadded base64url,
    /// 1.64. A value outside it is REFUSED, never escaped — it is interpolated
    /// into script source, and an escape is a guess about a hostile value.
    public static func isSafeIdentityValue(_ s: String) -> Bool {
        guard !s.isEmpty, s.utf8.count <= 64 else { return false }
        return s.unicodeScalars.allSatisfy {
            ($0 >= "A" && $0 <= "Z") || ($0 >= "a" && $0 <= "z")
                || ($0 >= "0" && $0 <= "9") || $0 == "-" || $0 == "_"
        }
    }

    /// The document-start script for the PAGE world, or nil when nothing may
    /// be told: an unsafe id, an absent / unusable / empty allowlist. Nil means
    /// "install no page-world script", never "install one that tells nobody".
    public static func scriptSource(segmentId: String, policy: [String]?) -> String? {
        guard isSafeIdentityValue(segmentId) else { return nil }
        guard let admitted = RnWebViewSegment.normalizePolicy(policy),
              !admitted.isEmpty else { return nil }
        // Every normalized entry matches `^https?://[a-z0-9.-]+(:[0-9]{1,5})?$`,
        // so no entry can carry a quote or a backslash; the id passed the
        // alphabet above. Nothing below needs escaping, by construction.
        let list = admitted.map { "\"\($0)\"" }.joined(separator: ",")
        return "(function(){try{"
            + "if(window!==window.top){return;}"
            + "var o=String(location.origin).toLowerCase();"
            + "if([\(list)].indexOf(o)<0){return;}"
            + "window.\(IDENTITY_GLOBAL)=Object.freeze({segmentId:\"\(segmentId)\"});"
            + "}catch(e){}})();"
    }

    // MARK: G2-FIRSTDOC read-back

    /// What the COMMITTED document's page world holds after the one-shot.
    ///
    /// WHY THIS EXISTS. A `WKUserScript` runs before any page script, so a
    /// fresh document is always told. The document already committed at attach
    /// (G2-FIRSTDOC, an earlier change) is told by a one-shot that runs AFTER the page's
    /// own scripts — and a page that `defineProperty`'d a non-writable,
    /// non-configurable `__tsRnNative` first keeps its value: the assignment
    /// fails silently in sloppy mode. Before an earlier change the native side never knew;
    /// the browser SDK stamped the page's value and the only trace was that
    /// nothing joined.
    ///
    /// IT IS NOT A TRUST DECISION AND MUST NEVER BECOME ONE. Everything in
    /// the page world is page-controlled on EVERY path — the document-start
    /// global is a plain writable property the page may overwrite after load,
    /// and the browser SDK itself runs in the page. Binding a browser event to
    /// a native segment is therefore always a CLAIM, made a correlation only by
    /// the consumer's N + S check (the id must be in the native session's own
    /// registry). This read-back changes nothing about admission, the hello or
    /// the carrier; it records what the page's browser SDK will see, so a
    /// `conflict` is reported as a typed outcome instead of a silent absence.
    /// A hostile page can make it read `told` only by arranging for its reader
    /// to return the minted id — which is the correct outcome for the join.
    public enum CommittedOutcome: Equatable {
        /// The page world holds the minted id under the browser SDK's own rule.
        case told
        /// An admitted main-frame document that does NOT hold the minted id:
        /// the page defined the global first (or read-back saw another value).
        /// `seen` is true when the reader returned SOME well-formed id.
        case conflict(seen: Bool)
        /// The committed document's origin is not admitted — never told, by design.
        case notAdmitted
        /// The read-back did not answer in a usable shape.
        case unreadable
    }

    /// The read-back expression, evaluated in the PAGE world after the
    /// one-shot. It applies `readHostWebViewSegmentId`'s rule verbatim (own
    /// data property, `SEGMENT_ID` pattern) so it answers what the browser SDK
    /// will stamp, and returns only the origin and that value — nothing else
    /// of the page is read.
    public static let readbackSource: String =
        "(function(){var o=null,v=null;try{o=String(location.origin).toLowerCase();"
        + "var g=window.\(IDENTITY_GLOBAL);"
        + "if(g&&typeof g==='object'){var d=Object.getOwnPropertyDescriptor(g,'segmentId');"
        + "if(d&&('value' in d)&&typeof d.value==='string'&&/^[A-Za-z0-9_-]{8,64}$/.test(d.value)){v=d.value;}}"
        + "}catch(e){}return JSON.stringify({o:o,v:v});})();"

    /// Classify the read-back against the minted id and the SAME normalized
    /// allowlist the page-identity script was gated on. Pure; unit-tested.
    public static func classify(readback: AnyObject?, minted: String,
                                policy: [String]?) -> CommittedOutcome {
        guard let s = readback as? String, let d = s.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
              let origin = obj["o"] as? String else { return .unreadable }
        guard let admitted = RnWebViewSegment.normalizePolicy(policy),
              admitted.contains(origin) else { return .notAdmitted }
        let v = obj["v"] as? String
        if v == minted { return .told }
        return .conflict(seen: v != nil)
    }
}

// MARK: - The router

/// What happened to ONE inbound `WKScriptMessage`.
public enum RnWebViewMessageOutcome: Equatable {
    /// It reached the shared decision, which answered this.
    case decided(RnWebViewSegment.Decision)
    /// It never reached the decision, because it could not be represented
    /// faithfully. A typed reason, never a silent drop.
    case rejected(String)
}

/// Receives a real `WKScriptMessage` and routes it into the ONE shared
/// decision.
///
/// NO PROTOCOL CONFORMANCE IS DECLARED OR REGISTERED, AND THAT IS MEASURED,
/// NOT AN OVERSIGHT. `addScriptMessageHandler:contentWorld:name:` accepted a
/// plain `NSObject` subclass that merely responds to the selector, and
/// `objc_getProtocol("WKScriptMessageHandler")` returns **nil** at runtime, so
/// a `class_addProtocol` call would be a permanent no-op. It was written,
/// measured dead and deleted rather than kept as insurance that would mislead
/// the next reader into thinking it is what makes delivery work.
public final class RnWebViewMessageRouter: NSObject {

    private let correlation: RnWebViewCorrelation
    /// Every outcome, in arrival order. Diagnostics only; the correlation's own
    /// admitted segment is the authority.
    public private(set) var outcomes: [RnWebViewMessageOutcome] = []
    /// What the document COMMITTED at attach ended up holding in its page
    /// world (G2-FIRSTDOC read-back, an earlier change). Nil until the read-back answers,
    /// and nil for every document told at document start. Diagnostics only:
    /// it never feeds the decision, the carrier or the registry.
    public private(set) var committedPageIdentity: RnWebViewPageIdentity.CommittedOutcome?

    func recordCommittedPageIdentity(_ o: RnWebViewPageIdentity.CommittedOutcome) {
        committedPageIdentity = o
    }

    public init(correlation: RnWebViewCorrelation) {
        self.correlation = correlation
        super.init()
    }

    /// `{ kind, fields }`, NOT A FLAT OBJECT, AND THE NESTING PROTECTS
    /// ITSELF. `kind` is not in the shared `ALLOWED_FIELDS`, so a flat message
    /// carrying it would be refused `unknown_fields:kind` — a confusing refusal
    /// for a correctly formed message. Nesting also means a page cannot smuggle
    /// a field in by naming it `kind`.
    static let KEY_KIND = "kind"
    static let KEY_FIELDS = "fields"

    /// One body value as the closed scalar the shared decision takes.
    ///
    /// CFBoolean IS TESTED FIRST, AND THE ORDER IS THE WHOLE POINT. Measured
    /// on the real channel: a JS `true` arrives as `__NSCFBoolean` and a JS `1`
    /// as `__NSCFNumber`, and BOTH answer `is NSNumber`. Testing `NSNumber`
    /// first would turn every number into a `Bool` — the Darwin coercion the
    /// closed `Field` enum exists to make unrepresentable, and the one that
    /// would have made "a non-boolean protection flag is the UNPROTECTED
    /// answer" silently false.
    static func field(_ v: Any) -> RnWebViewSegment.Field? {
        if CFGetTypeID(v as CFTypeRef) == CFBooleanGetTypeID() {
            return .bool((v as? NSNumber)?.boolValue ?? false)
        }
        if let n = v as? NSNumber { return .number(n.doubleValue) }
        if let s = v as? String { return .string(s) }
        return nil
    }

    /// Decide one message. Separated from the delivery callback so the whole
    /// routing rule is testable without a live WebView.
    @discardableResult
    public func route(name: String,
                      body: Any?,
                      authenticatedOrigin: String?) -> RnWebViewMessageOutcome {
        let out = decideOutcome(name: name, body: body,
                               authenticatedOrigin: authenticatedOrigin)
        outcomes.append(out)
        return out
    }

    private func decideOutcome(name: String,
                               body: Any?,
                               authenticatedOrigin: String?) -> RnWebViewMessageOutcome {
        // A message on another handler's channel is not ours to interpret.
        guard name == RnWebViewObjC.handlerName else {
            return .rejected("handler_name_mismatch")
        }
        guard let dict = body as? [String: Any] else {
            return .rejected("body_not_an_object")
        }
        let kind = dict[Self.KEY_KIND] as? String
        var fields: [String: RnWebViewSegment.Field] = [:]
        if let raw = dict[Self.KEY_FIELDS] {
            guard let obj = raw as? [String: Any] else {
                return .rejected("fields_not_an_object")
            }
            // SORTED, so one message cannot produce different reasons on
            // different runtimes — the same rule the shared decision applies to
            // its unknown-field list.
            for k in obj.keys.sorted() {
                guard let f = Self.field(obj[k]!) else {
                    // REJECTED, NEVER DROPPED. A value we cannot represent
                    // would otherwise vanish before the vocabulary check and
                    // the message would be judged on a different set of fields
                    // from the one the page sent.
                    return .rejected("field_unrepresentable:\(k)")
                }
                fields[k] = f
            }
        }
        return .decided(correlation.receive(kind: kind, fields: fields,
                                            authenticatedOrigin: authenticatedOrigin))
    }

    /// The real WebKit callback. Selector-compatible with
    /// `WKScriptMessageHandler` without naming it.
    ///
    /// THE ORIGIN COMES FROM `frameInfo.securityOrigin` AND NOWHERE ELSE.
    /// That is WebKit's own attribution of the calling frame; the body's
    /// `frameOrigin` is the page's CLAIM, and the shared decision exists to
    /// catch the two disagreeing (`origin_spoofed`). Reading the claim here
    /// would destroy the only check that can.
    @objc(userContentController:didReceiveScriptMessage:)
    public func userContentController(_ controller: AnyObject,
                                      didReceiveScriptMessage message: AnyObject) {
        let name = RnWebViewObjC.kv(message, "name") as? String ?? ""
        let body = RnWebViewObjC.kv(message, "body")
        var auth: String?
        if let frame = RnWebViewObjC.kv(message, "frameInfo"),
           let so = RnWebViewObjC.kv(frame, "securityOrigin") {
            auth = RnWebViewObjC.origin(of: so)
        }
        route(name: name, body: body as Any?, authenticatedOrigin: auth)
    }
}

// MARK: - The attachment

/// What happened to ONE candidate web view.
public enum RnWebViewAttachOutcome: Equatable {
    case installed
    /// A typed reason. `capability`/page reasons are facts about the subject;
    /// `bridge_*` reasons are facts about this runtime.
    case refused(String)
}

#if canImport(UIKit)
import UIKit

/// Finds real web views and installs the correlation bridge on the ones that
/// can carry a browser segment.
public enum RnWebViewAttachment {

    /// Candidate web views under `root`, OUTERMOST first.
    ///
    /// ONE HOME FOR "IS THIS WEB CONTENT": `HierarchySnapshotter.isWebContent`.
    /// A second class-name predicate here would be the one fact with two homes
    /// this programme keeps paying for, and the copy that drifts is always the
    /// one nobody is looking at.
    ///
    /// THAT PREDICATE MATCHES `WKContentView` TOO, WHICH IS NOT ATTACHABLE.
    /// `WKContentView` is WebKit's own subview of a `WKWebView` and has no
    /// `configuration`, so the candidate is additionally required to RESPOND to
    /// that selector — asking the object instead of matching more class names.
    /// The walk also stops at a web view rather than descending into it, which
    /// is the same leaf rule the snapshotter applies.
    public static func candidates(in root: UIView) -> [UIView] {
        var out: [UIView] = []
        func walk(_ v: UIView) {
            if HierarchySnapshotter.isWebContent(v) {
                if v.responds(to: NSSelectorFromString("configuration")) { out.append(v) }
                return   // leaf: never descend into web content
            }
            for sub in v.subviews { walk(sub) }
        }
        walk(root)
        return out
    }

    /// Install the bridge on one web view, or refuse with a typed reason.
    ///
    /// THE CAPABILITY GATE IS ASKED FIRST AND IT IS NOT THE REAL GATE. The
    /// shared decision re-checks capability on EVERY message, so a route that
    /// forgot to ask here could still never admit a segment. This is the cheap
    /// pre-answer; the authority is inside `decide`.
    ///
    /// CALL THIS WITH A DOCUMENT LOADED. The page gate reads the CURRENTLY
    /// loaded document, so a web view that has never navigated is refused
    /// `no_document` — there is no page yet to decide about. A `WKUserScript`
    /// runs at the START of a document, so on its own it would leave the
    /// document already on screen untold until the next navigation (measured
    /// on the simulator before an earlier change). That document is now told by a one-shot
    /// evaluation of the same two scripts into the same two worlds
    /// (`evaluateInCommittedMainFrame`, G2-FIRSTDOC); every document after it
    /// is told by the document-start scripts, including a reload.
    @discardableResult
    public static func attach(to webView: UIView,
                              correlation: RnWebViewCorrelation,
                              router: RnWebViewMessageRouter) -> RnWebViewAttachOutcome {
        guard correlation.shouldInstallBridge else {
            return .refused(correlation.capability.rawValue)
        }
        // The page gate: can a browser SDK run here at all?
        let facts = RnWebViewObjC.pageFacts(of: webView)
        if case .refused(let why) = RnWebViewHostPage.decide(facts) {
            return .refused(why)
        }
        guard let payload = correlation.identityScriptPayload,
              let segmentId = payload["segmentId"] else {
            return .refused("identity_payload_absent")
        }
        guard let ucc = RnWebViewObjC.userContentController(of: webView) else {
            return .refused("bridge_no_user_content_controller")
        }
        guard let world = RnWebViewObjC.isolatedWorld() else {
            return .refused("bridge_no_isolated_world")
        }
        // MAIN FRAME ONLY, AND IT IS A CORRECTNESS DECISION, NOT A DEFAULT.
        // See `identityScriptSource`.
        guard let script = RnWebViewObjC.makeDocumentStartScript(
            source: identityScriptSource(segmentId: segmentId),
            mainFrameOnly: true, world: world) else {
            return .refused("bridge_no_user_script")
        }
        let shim = unsafeBitCast(ucc, to: RnWebViewUserContent.self)
        // THE HANDLER GOES IN FIRST. A document-start script that posted
        // before its channel existed would throw inside the page and the
        // handshake would be lost with nothing to show for it.
        shim.addScriptMessageHandler(router, contentWorld: world,
                                     name: RnWebViewObjC.handlerName)
        shim.addUserScript(script)
        // The browser half of the join: tell the PAGE world which
        // segment it belongs to, gated in-script on the SAME bound allowlist
        // the per-message decision admits against. Optional by construction:
        // no source (unsafe id / unusable policy) or no page world installs
        // nothing, and the native correlation above is unaffected either way.
        var pageIdentity: (source: String, world: AnyObject)?
        if let pageSource = RnWebViewPageIdentity.scriptSource(
                segmentId: segmentId, policy: correlation.policy),
           let page = RnWebViewObjC.pageWorld(),
           let pageScript = RnWebViewObjC.makeDocumentStartScript(
                source: pageSource, mainFrameOnly: true, world: page) {
            shim.addUserScript(pageScript)
            pageIdentity = (pageSource, page)
        }
        // G2-FIRSTDOC: the document ALREADY committed when the bridge
        // is installed is told too — the identity first, then the hello, so
        // the page world holds the global by the time the native decision
        // admits the segment. Exactly the scripts installed above, nothing
        // more; every later document is served by the document-start scripts.
        if let pi = pageIdentity {
            RnWebViewObjC.evaluateInCommittedMainFrame(webView, source: pi.source, world: pi.world)
            // an earlier change: same world, queued after the one-shot, so it observes the
            // result of exactly that write. Diagnostics only (see CommittedOutcome).
            let policy = correlation.policy
            RnWebViewObjC.evaluateInCommittedMainFrame(
                webView, source: RnWebViewPageIdentity.readbackSource, world: pi.world
            ) { [weak router] v in
                let o = RnWebViewPageIdentity.classify(readback: v, minted: segmentId, policy: policy)
                if Thread.isMainThread { router?.recordCommittedPageIdentity(o) }
                else { DispatchQueue.main.async { router?.recordCommittedPageIdentity(o) } }
            }
        }
        RnWebViewObjC.evaluateInCommittedMainFrame(
            webView, source: identityScriptSource(segmentId: segmentId), world: world)
        return .installed
    }

    /// The document-start script, in OUR isolated world.
    ///
    /// IT CARRIES THE SEGMENT ID AND NOTHING ELSE — no session id, no
    /// project id, no posture, no credential. The page is never told anything
    /// about the native session, because the correlation is made natively
    /// (N + S) and a page that knew N could claim one.
    ///
    /// `parent` IS A FACT HERE, NOT A GUESS, BECAUSE THE SCRIPT IS MAIN-FRAME
    /// ONLY — AND MY FIRST VERSION SHIPPED A VALUE THAT COULD BE FALSE. It ran in
    /// every frame and reported `window === window.top ? 'parent':
    /// 'same_origin_child'`, so a CROSS-origin child would have been stored as a
    /// same-origin one: a wrong datum in the very row this slice exists to
    /// produce. Distinguishing the two from inside a frame is possible (reading
    /// `window.top.location` throws cross-origin) but the `nested_outer` /
    /// `nested_inner` half of the closed vocabulary is not reliably computable
    /// at all, and this venue cannot load a second origin to exercise either
    /// branch. Bridging only the main frame makes the one value we emit TRUE by
    /// construction, and leaves every subframe as an honest absence — which is
    /// also what the specification asks for: arbitrary third-party frame capture stays
    /// blocked, and an ad frame is exactly the subframe that must not be
    /// correlated. Subframe correlation is a named gate (G2-SUBFRAME), not a
    /// silently wrong field.
    ///
    /// IT REPORTS NO PAGE CONTENT. Frame position and the segment id it was
    /// handed; it reads no DOM, no text and no storage.
    static func identityScriptSource(segmentId: String) -> String {
        // The id is minted from a closed alphabet (base64url), so it cannot
        // carry a quote — but it is still emitted through JSON rather than
        // interpolated raw, because "cannot" is a property of today's minter.
        let quoted = jsonString(segmentId)
        return """
        (function () {
          try {
            if (window !== window.top) { return; }
            window.webkit.messageHandlers.\(RnWebViewObjC.handlerName).postMessage({
              kind: '\(RnWebViewSegment.KIND_HELLO)',
              fields: { segmentId: \(quoted), frameRole: 'parent' }
            });
          } catch (e) { /* a page we cannot reach is an absence, never a throw */ }
        })();
        """
    }

    /// Minimal JSON string literal. `JSONSerialization` refuses a bare string
    /// on older platforms, so this is written out.
    static func jsonString(_ s: String) -> String {
        var out = "\""
        for ch in s.unicodeScalars {
            switch ch {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if ch.value < 0x20 {
                    out += String(format: "\\u%04x", ch.value)
                } else {
                    out.unicodeScalars.append(ch)
                }
            }
        }
        return out + "\""
    }
}
#endif

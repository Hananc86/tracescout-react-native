import Foundation
import ObjectiveC

// G2-CALLSITE — THE PRODUCTION WIRING OF THE BIND.
//
// an earlier change measured the exact gap this file closes, on both platforms:
//
// bindFromConfiguration production callers. 0 (iOS)
// bindFromConfiguration production callers. 0 (RN Android)
//
// Every piece existed — the host-declared allowlist, the capability
// preflight, the shared Model B decision, the trust lifetime, the real
// WKWebView attachment — and nothing called the bind, so no session
// could correlate and nothing above it could be demonstrated.
//
// This holder is the SESSION-SCOPED owner of that correlation. It is the one
// place a native session id meets the host's declared policy, and the one place
// the rest of the producer asks "does this session have an admitted WebView
// segment?".
//
// ## NO JS PATH, AND THE COUNT IS PINNED
//
// `rn-ios.yml` pins `RCT_EXTERN_METHOD` in `TraceScoutRnModule.m` at EXACTLY 11
// and separately forbids a JS-callable name carrying a native-owned fact. This
// file adds NO bridge method: an origin allowlist is the definition of a
// native-owned fact, and a policy a page's own runtime could supply would be a
// policy the page could widen. The adapter reaches this type from native code
// only.
//
// ## WHAT THIS FILE DOES NOT DO
//
// It does not decide admission (that is `RnWebViewSegment.decide`, through
// `RnWebViewCorrelation.receive`), it does not read a page, and it does not
// mint a carrier from the minted segment id. A carrier exists only when a REAL
// inbound handshake was ADMITTED — "N + S, never S alone".
public final class RnWebViewSessionBinding {

    /// Process-wide, because a native session is process-wide. Not a singleton
    /// for convenience: the correlation's identity IS the session's, so two
    /// holders would be two answers to one question.
    public static let shared = RnWebViewSessionBinding()

    /// What happened to one bind request.
    public enum BindOutcome: Equatable {
        /// A correlation now exists for this session. Carries the minted
        /// segment id — which is NOT yet a correlation on the wire.
        case bound(String)
        /// This session already has one; nothing was re-minted.
        case alreadyBound(String)
        /// No correlation is possible for this session, with the typed reason
        /// the existing bind produced.
        case refused(String)
    }

    /// What happened to one attach request.
    public enum AttachRequestOutcome: Equatable {
        case installed
        /// Already installed on this exact web view; nothing was done.
        case alreadyInstalled
        /// A typed reason — either the attachment's own, or one of this
        /// holder's (`no_binding`, `session_mismatch`, `not_main_thread`).
        case refused(String)
    }

    private let lock = NSLock()
    /// The session this correlation belongs to. A different session id can
    /// never read it — see `carrier(nativeSessionId:)`.
    private var boundSessionId: String?
    private var correlation: RnWebViewCorrelation?
    /// The last refusal, so "no correlation" can be reported WITH its cause.
    /// Diagnostics only; nothing decides on it.
    private var lastRefusal: String?

    /// `internal`, NOT `public`, AND NOT `private`. Production has exactly
    /// one holder — `shared` — and a public initializer would invite a host app
    /// to make a second answer to "which correlation does this session have".
    /// It is not private because each test needs an ISOLATED subject: a
    /// process-wide singleton shared across test methods would let one method's
    /// admission be read by the next, and a suite whose cases contaminate each
    /// other proves nothing about either. Swift's `@testable import` reaches
    /// internal, so no test-only public surface is added. (Kotlin's twin is
    /// public because its test lives in a DIFFERENT compilation module.)
    init() {}

    // MARK: - The session lifecycle call site

    /// Bind this native session's WebView correlation from the SDK's OWN
    /// persisted configuration.
    ///
    /// IT READS NOTHING BUT THE EXISTING BIND. The allowlist comes from
    /// `SecureConfig.webViewOriginAllowlist` and the capability from
    /// `RnWebViewCapability.measured`, both inside
    /// `RnWebViewBinding.bindFromConfiguration` — so there is exactly one home
    /// for "what did the host declare" and exactly one for "what can this
    /// engine do". This file adds no second reader of either.
    ///
    /// IDEMPOTENT PER SESSION, AND A NEW SESSION NEVER INHERITS. Re-binding
    /// the same session would mint a second segment id and silently orphan the
    /// first, so the same id answers `alreadyBound`. A DIFFERENT id replaces
    /// the state outright: a correlation admitted for session A must never ride
    /// session B's events, which is the "N + S" claim itself.
    @discardableResult
    public func bind(nativeSessionId: String) -> BindOutcome {
        lock.lock()
        if boundSessionId == nativeSessionId, let c = correlation {
            let id = c.segmentId
            lock.unlock()
            return .alreadyBound(id)
        }
        lock.unlock()

        // Read OUTSIDE the lock: `bindFromConfiguration` reaches UserDefaults
        // and the capability preflight, and holding a lock across platform
        // reads is how a deadlock gets written.
        return adopt(nativeSessionId: nativeSessionId,
                     result: RnWebViewBinding.bindFromConfiguration(
                        nativeSessionId: nativeSessionId))
    }

    /// Adopt an already-decided bind result as THIS session's correlation.
    ///
    /// THE PURE HALF, AND THE TWIN OF KOTLIN'S `adopt`. The Kotlin holder
    /// lives in `rn-core`, which has no `Context` and no WebView dependency, so
    /// its only entry is this one; keeping the same entry here is what lets one
    /// sentence describe the session-scoping rule on both platforms instead of
    /// two that can drift.
    ///
    /// IT TAKES AN ALREADY-DECIDED RESULT AND NEVER RE-DECIDES. The
    /// allowlist, the capability order and every refusal code are the bind's.
    @discardableResult
    public func adopt(nativeSessionId: String,
                      result: RnWebViewBinding.Result) -> BindOutcome {
        lock.lock(); defer { lock.unlock() }
        if boundSessionId == nativeSessionId, let c = correlation {
            // AN EXISTING CORRELATION IS NEVER REPLACED FOR THE SAME
            // SESSION. It may already hold an ADMITTED segment the wire is
            // carrying, and replacing it would silently detach a correlation
            // that is live.
            return .alreadyBound(c.segmentId)
        }
        boundSessionId = nativeSessionId
        if let c = result.correlation {
            correlation = c
            lastRefusal = nil
            return .bound(c.segmentId)
        }
        correlation = nil
        let why = result.refusal ?? "bind_refused"
        lastRefusal = why
        return .refused(why)
    }

    /// Retire everything bound to the previous session.
    ///
    /// CALLED ON A SESSION CHANGE, AND IT IS NOT MERELY TIDINESS. `carrier`
    /// already refuses a session-id mismatch, so this is the second line; the
    /// first is that a stale correlation cannot be read at all.
    public func reset(reason: String) {
        lock.lock(); defer { lock.unlock() }
        boundSessionId = nil
        correlation = nil
        lastRefusal = reason
    }

    // MARK: - The emit call site

    /// The envelope carrier for THIS session, or nil.
    ///
    /// THE SESSION ID IS COMPARED, NOT ASSUMED. The emit path asks for a
    /// named session; answering with another session's correlation would put a
    /// segment id on the wire for events it never covered.
    ///
    /// NIL IS THE ORDINARY ANSWER AND COSTS NOTHING. No binding, a refused
    /// binding, or a binding with no ADMITTED handshake all answer nil, and the
    /// envelope then omits both correlation keys exactly as it did before this
    /// file existed.
    public func carrier(nativeSessionId: String?) -> RnEnvelope.WebViewRef? {
        guard let sid = nativeSessionId else { return nil }
        lock.lock(); defer { lock.unlock() }
        guard boundSessionId == sid, let c = correlation else { return nil }
        return c.carrier
    }

    /// The minted segment id for this session, or nil. Diagnostics: it is NOT
    /// a correlation, and it never reaches the wire on its own.
    public func mintedSegmentId(nativeSessionId: String?) -> String? {
        guard let sid = nativeSessionId else { return nil }
        lock.lock(); defer { lock.unlock() }
        guard boundSessionId == sid else { return nil }
        return correlation?.segmentId
    }

    /// The last refusal observed, for a report that has to say WHY a session
    /// carries no segment.
    public func refusalReason() -> String? {
        lock.lock(); defer { lock.unlock() }
        return lastRefusal
    }

    /// The bound correlation for this session, or nil. Internal: the attach and
    /// the revalidation need the object itself.
    func correlation(for nativeSessionId: String) -> RnWebViewCorrelation? {
        lock.lock(); defer { lock.unlock() }
        guard boundSessionId == nativeSessionId else { return nil }
        return correlation
    }

    // MARK: - Fail-closed revocation

    /// Revalidate this session's admission against the LIVE configuration and
    /// a live web view.
    ///
    /// IT DELEGATES; IT DOES NOT RE-IMPLEMENT. `RnWebViewCorrelation`
    /// re-reads `SecureConfig.webViewOriginAllowlist` itself and consults it
    /// ONLY to retire — a widening mid-session is never applied, a revocation
    /// is applied at once, and an unreadable current origin DROPS. A second
    /// comparison here would be free to disagree with the one that decides.
    @discardableResult
    public func revalidate(nativeSessionId: String,
                           webView: AnyObject) -> RnWebViewTrustLifetime.Verdict {
        guard let c = correlation(for: nativeSessionId) else { return .noSegment }
        let v = c.revalidate(webView: webView)
        if case .drop(let why) = v {
            lock.lock(); lastRefusal = "retired:" + why; lock.unlock()
        }
        return v
    }
}

#if canImport(UIKit)
import UIKit

extension RnWebViewSessionBinding {

    /// The marker that says "this exact web view already carries our bridge".
    ///
    /// AN ASSOCIATED OBJECT, NOT A SET OF OBJECT IDENTITIES, AND THE REASON
    /// IS A CRASH. `addScriptMessageHandler(_:contentWorld:name:)` raises an
    /// Objective-C exception when a handler with the same name is already
    /// registered in the same world, and an ObjC exception cannot be caught
    /// from Swift — it would terminate the HOST APP. A set keyed on
    /// `ObjectIdentifier` cannot be trusted to prevent that: an address is
    /// reused after dealloc, so a fresh web view could read as already
    /// attached (and be skipped) or a dead one could keep a live entry. An
    /// associated object is scoped to the object's own lifetime by
    /// construction, leaks nothing, and cannot be confused by address reuse.
    private static var attachedKey: UInt8 = 0

    private static func isAttached(_ v: AnyObject) -> Bool {
        (objc_getAssociatedObject(v, &attachedKey) as? NSNumber)?.boolValue ?? false
    }

    private static func markAttached(_ v: AnyObject, router: RnWebViewMessageRouter) {
        objc_setAssociatedObject(v, &attachedKey, NSNumber(value: true),
                                 .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        // THE ROUTER IS RETAINED HERE TOO, DELIBERATELY.
        // `WKUserContentController` retains a script message handler, so this
        // is belt and braces — but the holder is per SESSION and the router is
        // per WEB VIEW, so hanging it off the view is the only place whose
        // lifetime is exactly right. Stored under its own key so the
        // attached-marker read cannot be satisfied by the router's presence
        // alone.
        objc_setAssociatedObject(v, &routerKey, router,
                                 .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }

    private static var routerKey: UInt8 = 0

    /// The router installed on this web view, or nil. Test/diagnostics seam:
    /// the outcomes it recorded are how a run shows what a real page sent.
    public static func router(of webView: AnyObject) -> RnWebViewMessageRouter? {
        objc_getAssociatedObject(webView, &routerKey) as? RnWebViewMessageRouter
    }

    // MARK: - The capture call site

    /// Install the correlation bridge on one real web view.
    ///
    /// MAIN THREAD ONLY, AND IT REFUSES RATHER THAN HOPPING. Every caller is
    /// already on the main thread with a verified binding (that is what makes
    /// the candidate walk safe at all), and a hop here would attach against a
    /// view hierarchy the caller has already stopped looking at.
    @discardableResult
    public func attach(webView: UIView, nativeSessionId: String) -> AttachRequestOutcome {
        guard Thread.isMainThread else { return .refused("not_main_thread") }
        guard let c = correlation(for: nativeSessionId) else {
            // Either nothing is bound, or something is bound for ANOTHER
            // session. Both are "no correlation for this session", and the
            // stored refusal already carries the cause of the first.
            return .refused(lastRefusal ?? "no_binding")
        }
        if Self.isAttached(webView) { return .alreadyInstalled }
        let router = RnWebViewMessageRouter(correlation: c)
        switch RnWebViewAttachment.attach(to: webView, correlation: c, router: router) {
        case .installed:
            // MARKED ONLY ON SUCCESS. A refused attach installed nothing, so
            // marking it would permanently skip a web view that becomes
            // eligible later — the page gate refuses `no_document` before the
            // first navigation, which is the ordinary case for a web view that
            // has only just been mounted.
            Self.markAttached(webView, router: router)
            return .installed
        case .refused(let why):
            return .refused(why)
        }
    }

    /// Bind the session if needed, retire a stale admission, and attach every
    /// candidate under `root`.
    ///
    /// ONE ENTRY FOR THE WHOLE CAPTURE-PATH STEP, so the ORDER is stated
    /// once: bind (so a correlation exists) → revalidate (so a stale admission
    /// is retired before anything reads the carrier) → attach (so the next
    /// document carries the identity). Revalidating after attaching would let
    /// one emit read a carrier whose origin had already navigated away.
    ///
    /// Returns the per-candidate outcomes, outermost first, for the trace.
    @discardableResult
    public func ensure(root: UIView, nativeSessionId: String) -> [AttachRequestOutcome] {
        guard Thread.isMainThread else { return [.refused("not_main_thread")] }
        bind(nativeSessionId: nativeSessionId)
        let candidates = RnWebViewAttachment.candidates(in: root)
        var out: [AttachRequestOutcome] = []
        for v in candidates {
            // Fail-closed revocation, per candidate, BEFORE the attach.
            revalidate(nativeSessionId: nativeSessionId, webView: v)
            out.append(attach(webView: v, nativeSessionId: nativeSessionId))
        }
        return out
    }
}
#endif

import Foundation

/// NATIVE OBSERVATION OF THE ALLOWED LAB WEB VIEW'S SCROLL POSITION.
///
/// ## What this closes, precisely
///
/// `FrameCadenceDriver.Signal.visibleMutation` already existed and had NO
/// PRODUCTION EMITTER. The automatic driver is fed by navigation and by touch,
/// so it disarms after its trailing period — and a WebView keeps moving after
/// the finger leaves (deceleration), can move with no finger at all (a
/// programmatic `scrollTo`), and can fill an empty rectangle with a whole
/// document seconds after everyone has stopped touching it (a slow or deferred
/// load). None of the three changes the opaque leaf's hierarchy fingerprint,
/// because an allowed `WKWebView` is ONE node, ONE rectangle and NO children at
/// every scroll position and at every stage of loading.
///
/// This type is that emitter, and it emits exactly one thing: `.visibleMutation`.
///
/// IT CAPTURES NOTHING FROM THE CALLBACK. A KVO notification arrives at
/// display rate; rasterizing there would be a second, unscheduled capture path
/// with no budget, which is precisely what the cadence exists to prevent. The
/// callback moves a timestamp and returns. Every decision about WHETHER to
/// capture stays with `FrameCadenceDriver` and `FramePolicy.decide`, and the
/// Lab session bound of `labMaxFramesPerSession` is untouched.
///
/// KVO, NOT A DELEGATE AND NOT A SWIZZLE. `UIScrollView.delegate` belongs to
/// the host — taking it is a behaviour change wearing an observer's clothes.
/// `contentOffset` is a documented KVO-compliant property, and Swift's
/// block-based observation is invalidated by releasing its token, so there is
/// no `removeObserver:` to forget.
///
/// SCOPE, STATED NARROWLY. This observes SCROLL GEOMETRY — the position
/// (`contentOffset`) and the laid-out document extent (`contentSize`). It
/// therefore covers gesture scrolling, hands-off deceleration, programmatic
/// offset changes, and a document whose layout ARRIVES or CHANGES SIZE after
/// the capture window has closed, which is what a delayed load is.
///
/// It does **not** establish automatic capture of arbitrary DOM animation or
/// in-place content updates. A marquee, a CSS transition, an image swapped for
/// another of identical dimensions, or a re-render that leaves both the offset
/// and the extent where they were, produces no signal here. Claiming otherwise
/// would be claiming a capability nothing in this file has.
public enum WebScrollGate {

    /// The identity an observation is bound to. A callback carries the copy it
    /// was installed with; the observer holds the current one.
    public struct Binding: Equatable {
        public let generation: UInt64
        public let routeId: String
        public let sessionId: String
        public init(generation: UInt64, routeId: String, sessionId: String) {
            self.generation = generation; self.routeId = routeId; self.sessionId = sessionId
        }
    }

    /// THE SAME CONJUNCTION AS THE VISIBILITY EXCEPTION, BY DELEGATION.
    /// Re-stating the four conditions here would be a second copy free to
    /// disagree with the one that decides whether the web view is drawn at all
    /// and an observation running on a route whose pixels are redacted would
    /// be waking the capture path for a rectangle nobody can see.
    public static func qualifies(labScopeQualified: Bool, ladderMode: Bool,
                                 rung: FidelityRung, routeId: String) -> Bool {
        UatFidelityExemption.allowsLabWebContent(
            labScopeQualified: labScopeQualified, ladderMode: ladderMode,
            rung: rung, routeId: routeId)
    }

    /// May a callback act?
    ///
    /// EVERY TERM IS REQUIRED, AND THE GENERATION IS THE ONE THAT CANNOT BE
    /// SPOOFED BY COINCIDENCE. A remounted web view on the SAME route in the
    /// SAME session is a different subject — its old observation must fall
    /// silent even though route and session still match — so the monotonic
    /// generation is what actually retires it. Route and session are checked as
    /// well because they are the facts a reader reasons about.
    public static func callbackMayAct(callback: Binding, current: Binding?,
                                      qualifiesNow: Bool, attached: Bool) -> Bool {
        guard qualifiesNow, attached, let cur = current else { return false }
        return cur == callback
    }

    /// COALESCING, NOT SAMPLING. Two different things could be meant by
    /// "rate limit" here and only one is safe: dropping a NOTIFICATION is fine
    /// (the next one is milliseconds away and the window is 1 500 ms long),
    /// while dropping the LAST notification of a movement would close the
    /// window early. It cannot: the interval is two orders of magnitude below
    /// the trailing budget, so the final notification of any real movement is
    /// always forwarded within 50 ms of the movement ending.
    public static let minForwardIntervalMs: Int64 = 50

    public static func shouldForward(lastForwardMs: Int64, nowMs: Int64) -> Bool {
        if lastForwardMs < 0 { return true }
        if nowMs < lastForwardMs { return true }          // clock moved back
        return nowMs - lastForwardMs >= minForwardIntervalMs
    }

    /// A NOTIFICATION IS NOT A MOVEMENT, AND THE HOSTED TEST PROVED IT.
    /// This file previously assumed UIKit suppresses the KVO notification when
    /// `contentOffset` is re-assigned an equal value. It does not: twenty
    /// identical assignments produced twenty notifications. A host that
    /// restores a saved offset on every layout pass — an ordinary thing to do
    /// would then hold the capture window open forever on a screen where
    /// nothing has moved, which is exactly the "video recorder" this cadence
    /// exists not to be.
    ///
    /// So movement is decided on the VALUE, against the same whole-point
    /// threshold `VisibleState` uses. It also removes the sub-point jitter a
    /// resting scroll view produces.
    public static let motionThresholdPoints = VisibleState.motionThresholdPoints

    public static func moved(fromX: Int, fromY: Int, toX: Int, toY: Int) -> Bool {
        abs(toX - fromX) >= motionThresholdPoints || abs(toY - fromY) >= motionThresholdPoints
    }

    /// The scroll surface's whole visible geometry, as four integers.
    ///
    /// THE OFFSET ALONE COULD NOT SEE A DOCUMENT ARRIVING. A WKWebView that
    /// finishes loading after the capture window has closed grows its scroll
    /// view's `contentSize` from nothing to the document's full height while
    /// `contentOffset` stays exactly where it was — at the top, because nobody
    /// has scrolled. So the previous observation, which watched the position
    /// and only the position, produced no signal for the one event a delayed
    /// load consists of, and the driver stayed disarmed with a screen full of
    /// new content on it.
    ///
    /// THIS IS STILL NOT DOM TRAVERSAL. `contentSize` is a UIKit property of
    /// a UIKit object; it says how tall the laid-out document is and says
    /// nothing whatever about what the document contains. The type is four
    /// integers — there is no text, no label, no URL and no element identity in
    /// it, and none of it is ever serialised.
    public struct Geometry: Equatable {
        public let offsetX: Int
        public let offsetY: Int
        public let contentW: Int
        public let contentH: Int
        public init(offsetX: Int, offsetY: Int, contentW: Int, contentH: Int) {
            self.offsetX = offsetX; self.offsetY = offsetY
            self.contentW = contentW; self.contentH = contentH
        }
    }

    /// Did the picture MOVE between two geometries?
    ///
    /// THE SAME RULE `VisibleState.moved` USES, TERM FOR TERM, AND THAT IS
    /// THE POINT. Two mechanisms now answer "did the picture move?" — the
    /// tick-sampled probe, which can only EXTEND an open window, and this
    /// event-driven emitter, which can OPEN one. If they disagreed about what
    /// motion is, a change would be motion or not depending purely on whether
    /// the driver happened to be running at the time, which is the least
    /// debuggable failure available. Offsets are compared against the
    /// whole-point threshold (a resting scroll view jitters below it); content
    /// dimensions are compared for equality, because a re-layout is a visible
    /// change at any magnitude and does not jitter.
    public static func moved(from a: Geometry, to b: Geometry) -> Bool {
        if moved(fromX: a.offsetX, fromY: a.offsetY, toX: b.offsetX, toY: b.offsetY) { return true }
        return a.contentW != b.contentW || a.contentH != b.contentH
    }

    /// Why an `ensure` did not result in a live observation. Diagnostics only.
    public enum Outcome: String, Equatable {
        case observing
        case alreadyObserving
        case notQualified
        case noScrollSurface
        case notMainThread
        case noSession
    }
}

#if canImport(UIKit)
import UIKit

/// THE OBSERVER. It owns one token, one generation and no policy.
public final class WebScrollObserver {

    public static let shared = WebScrollObserver()

    private var generation: UInt64 = 0
    private var current: WebScrollGate.Binding?
    /// TWO TOKENS, ONE BINDING, ONE DECISION. Both callbacks funnel into the
    /// SAME handler, which re-reads the live geometry and decides on the VALUE
    /// so which key path happened to fire is not an input to anything.
    ///
    /// AND THE SECOND TOKEN IS NOT WHAT MAKES A DELAYED LOAD VISIBLE TODAY —
    /// MEASURED, because the first version of this comment claimed it was. With
    /// `sizeToken` reverted to observe `contentOffset` (i.e. the shipped
    /// offset-only observation) the delayed-load suite still passed 18/0: UIKit
    /// RE-CLAMPS `contentOffset` when the content extent changes, and a
    /// re-assignment of an equal value was already measured to emit a
    /// notification. So the offset token alone still wakes the handler, and the
    /// handler sees the new extent because it re-reads the LIVE geometry rather
    /// than the change payload.
    ///
    /// What actually closes the gap is therefore `WebScrollGate.moved`
    /// including the extent — reverting THAT fails exactly the three new cases.
    /// `sizeToken` is defence in depth: it removes the dependency on an
    /// undocumented UIKit re-clamp, which is not a behaviour to build a capture
    /// path on. Both are kept, and which one is load-bearing is stated rather
    /// than guessed, so a later reader cannot "simplify" away the half that is.
    private var offsetToken: NSKeyValueObservation?
    private var sizeToken: NSKeyValueObservation?
    private weak var observed: UIScrollView?
    private var lastForwardMs: Int64 = -1
    /// The geometry the last FORWARDED notification described, so "did it
    /// move?" is measured against what the cadence was last told — not against
    /// the previous notification, which would let a slow drift forward forever
    /// in sub-threshold steps.
    private var lastForwarded: WebScrollGate.Geometry?
    /// Bumped on every forwarded notification, so a report can say whether the
    /// emitter fired at all rather than inferring it from the frames.
    public private(set) var forwardedCount: Int = 0
    public private(set) var lastOutcome: WebScrollGate.Outcome = .noScrollSurface

    private init() {}

    /// The allowed web view's scroll surface, if this root has one.
    ///
    /// THE SAME CLASS RULE THE WALKER USES, AND NO WEBKIT IMPORT. Importing
    /// WebKit for a type check would make it a hard dependency of every host
    /// app; `HierarchySnapshotter` already identifies web content by class name
    /// for exactly that reason. The scroll surface is then the first
    /// `UIScrollView` INSIDE that view — `WKWebView.scrollView` is a subview,
    /// so this finds the same object the property would return without naming
    /// the type.
    /// PUBLIC BECAUSE THE HOSTED TEST TARGET IS A DIFFERENT MODULE. The
    /// hosted suite links the pod as a consumer, so `internal` is invisible to
    /// it and the surface-resolution rule — including its control, that an
    /// ordinary scroll view outside a web host is NOT the subject — could not
    /// be exercised at all. It reads geometry and returns a view; it decides
    /// nothing and mutates nothing.
    public static func webScrollSurface(in root: UIView) -> UIScrollView? {
        func isWeb(_ v: UIView) -> Bool {
            let n = String(describing: Swift.type(of: v))
            return n.contains("WKWebView") || n.contains("WKContentView")
        }
        func firstScrollView(_ v: UIView, depth: Int) -> UIScrollView? {
            if depth > 8 { return nil }
            if let s = v as? UIScrollView { return s }
            for sub in v.subviews {
                if let s = firstScrollView(sub, depth: depth + 1) { return s }
            }
            return nil
        }
        func find(_ v: UIView, depth: Int) -> UIScrollView? {
            if depth > 40 { return nil }
            if isWeb(v) { return firstScrollView(v, depth: 0) }
            for sub in v.subviews {
                if let s = find(sub, depth: depth + 1) { return s }
            }
            return nil
        }
        return find(root, depth: 0)
    }

    /// Install, re-point or retire the observation for `root`/`routeId`.
    ///
    /// Idempotent: called on every recorded navigation and on every capture
    /// candidate, it is a no-op when the same live surface is already observed.
    @discardableResult
    public func ensure(root: UIView?,
                       routeId: String,
                       sessionId: String?,
                       qualifies: Bool,
                       nowMs: @escaping () -> Int64,
                       qualifiesNow: @escaping () -> Bool,
                       onMutation: @escaping () -> Void) -> WebScrollGate.Outcome {
        guard Thread.isMainThread else { lastOutcome = .notMainThread; return .notMainThread }
        guard qualifies else { invalidate(reason: "not_qualified"); lastOutcome = .notQualified; return .notQualified }
        guard let sid = sessionId, !sid.isEmpty else {
            invalidate(reason: "no_session"); lastOutcome = .noSession; return .noSession
        }
        guard let root = root, root.window != nil,
              let sv = WebScrollObserver.webScrollSurface(in: root) else {
            invalidate(reason: "no_scroll_surface"); lastOutcome = .noScrollSurface; return .noScrollSurface
        }
        if let cur = current, let obs = observed, obs === sv, obs.window != nil,
           cur.routeId == routeId, cur.sessionId == sid,
           offsetToken != nil, sizeToken != nil {
            lastOutcome = .alreadyObserving
            return .alreadyObserving
        }
        // RETIRE FIRST. Bumping the generation BEFORE the new token exists is
        // what makes an in-flight callback from the previous surface inert
        // rather than racing the replacement.
        invalidate(reason: "rebind")
        generation &+= 1
        let binding = WebScrollGate.Binding(generation: generation, routeId: routeId, sessionId: sid)
        current = binding
        observed = sv
        lastForwardMs = -1
        lastForwarded = nil
        let handle: (UIScrollView?) -> Void = { [weak self] maybe in
            guard Thread.isMainThread, let self = self else { return }
            self.handleNotification(from: maybe, binding: binding, nowMs: nowMs,
                                    qualifiesNow: qualifiesNow, onMutation: onMutation)
        }
        offsetToken = sv.observe(\.contentOffset, options: [.new]) { [weak sv] _, _ in handle(sv) }
        sizeToken = sv.observe(\.contentSize, options: [.new]) { [weak sv] _, _ in handle(sv) }
        lastOutcome = .observing
        return .observing
    }

    /// The ONE decision both key paths reach.
    ///
    /// IT RE-READS THE LIVE GEOMETRY RATHER THAN USING THE CHANGE PAYLOAD.
    /// KVO hands each observer only its own key path's new value, so a handler
    /// built from the payload would have to remember the other half — and the
    /// two key paths fire independently and out of order during a load. Reading
    /// both properties from the live view at decision time means the emitter
    /// compares whole states and can never act on half of one.
    private func handleNotification(from maybe: UIScrollView?,
                                    binding: WebScrollGate.Binding,
                                    nowMs: () -> Int64,
                                    qualifiesNow: () -> Bool,
                                    onMutation: () -> Void) {
        // A surface that has left the window is detached; retire rather than
        // keep reporting movement nobody can see.
        guard let live = maybe, live.window != nil else {
            invalidate(reason: "detached"); return
        }
        guard WebScrollGate.callbackMayAct(callback: binding, current: current,
                                           qualifiesNow: qualifiesNow(),
                                           attached: observed === live)
        else { return }
        let t = nowMs()
        guard WebScrollGate.shouldForward(lastForwardMs: lastForwardMs, nowMs: t) else { return }
        let now = WebScrollGate.Geometry(
            offsetX: Int(live.contentOffset.x.rounded()),
            offsetY: Int(live.contentOffset.y.rounded()),
            contentW: Int(live.contentSize.width.rounded()),
            contentH: Int(live.contentSize.height.rounded()))
        // THE VALUE DECIDES, NOT THE NOTIFICATION. See `WebScrollGate.moved`.
        // The very first notification of an observation always forwards (there
        // is no previous geometry), so a freshly installed observation is never
        // silent about a movement it was installed to see.
        if let prev = lastForwarded, !WebScrollGate.moved(from: prev, to: now) { return }
        lastForwardMs = t
        lastForwarded = now
        forwardedCount &+= 1
        onMutation()
    }

    /// Retire the current observation. Safe to call repeatedly.
    public func invalidate(reason: String) {
        offsetToken?.invalidate()
        sizeToken?.invalidate()
        offsetToken = nil
        sizeToken = nil
        observed = nil
        current = nil
        lastForwardMs = -1
        lastForwarded = nil
    }

    /// Test/diagnostic view of the live binding. Carries no pixels and no URL.
    public func currentBinding() -> WebScrollGate.Binding? { current }
    /// BOTH TOKENS ARE REQUIRED. Reporting "observing" while only one key
    /// path is live would be reporting a half-installed emitter as installed,
    /// and the half most likely to be missing is the one a delayed load needs.
    public func isObserving() -> Bool { offsetToken != nil && sizeToken != nil && current != nil }
    public func resetCountersForTesting() { forwardedCount = 0 }
}
#endif

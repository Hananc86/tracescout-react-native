import Foundation
// The install uses class_addMethod / class_respondsToSelector. Foundation
// re-exports ObjectiveC on Darwin, but the dependency is real — naming it keeps
// a future module change from failing for a reason that looks unrelated.
import ObjectiveC

/// THE PRODUCTION iOS COMPLETION SEAM.
///
/// Exactly ONE thing is installed:
///
/// URLSession:task:didFinishCollectingMetrics:
///
/// on React Native's own `RCTHTTPRequestHandler`. **Not** a `URLProtocol`,
/// **not** `RCTSetCustomNSURLSessionConfigurationProvider`, **not**
/// `RCTSetCustomHTTPRequestInterceptor`, **not** a global `NSURLSession`
/// swizzle, and **no method exchange of any kind**. Supplying a method the
/// delegate does not implement is additive — it returns void, runs after
/// completion, and owns no part of the load. Everything else on that list
/// either performs the load or occupies a singleton slot the host application
/// may already own.
///
/// ## Installation is a real gate, not a detail
///
/// CORRECTED 2026-09-11 — READ THIS BEFORE REPEATING THE OLD CLAIM. This
/// comment used to state that `NSURLSession` caches its delegate's
/// `respondsToSelector:` answers at session creation, so a late install is
/// silently inert. **That was asserted without measurement and the measurement
/// disagreed**: on macos-15 Foundation a selector added AFTER the session was
/// created WAS delivered (`SelectorCacheControlTests`, CI 34553760174).
///
/// Installation is still anchored at image load (`TraceScoutRNObserverLoad.m`),
/// on the weaker and far more durable justification: `+load` runs before
/// `main`, so it is unconditionally earlier than any host request and the
/// ordering cannot be got wrong at all. It does NOT rest on an undocumented
/// caching behaviour that one OS version declines to exhibit — a behaviour
/// Apple has never promised is not something to build a capture path on, in
/// either direction.
///
/// ## Fail closed, never override
///
/// If the class already implements the selector, RN has started implementing it
/// upstream. Adding ours would be a **last-writer-wins override with an
/// undefined winner**, silently deciding whether RN's own metrics handling
/// runs. In that case network observation is DISABLED and RN's networking is
/// left exactly as it was. That is also what `verify-rn-metrics-selector-free.mjs`
/// guards at build time; this is the runtime half of the same rule.
public enum RnNetworkObserver {

    public static let selectorName = "URLSession:task:didFinishCollectingMetrics:"

    public enum InstallOutcome: String {
        /// The selector was absent and we added it. Observation is possible.
        case installed = "installed"
        /// `bootstrap` ran again in the same process. Installation happens once.
        case alreadyDone = "already_done"
        /// RN (or something else) already implements it. We do NOT replace it,
        /// and network observation stays off for the life of the process.
        case refusedSelectorPresent = "refused_selector_present"
        /// RN's networking class is not in this binary at all.
        case refusedClassAbsent = "refused_class_absent"
        /// The runtime refused the addition. Nothing was changed.
        case refusedAddFailed = "refused_add_failed"

        /// Only one outcome permits observation. Everything else means the
        /// producer is inert — which is the point: a failure disables network
        /// capture rather than altering RN networking.
        public var observationPossible: Bool { self == .installed || self == .alreadyDone }
    }

    // MARK: - state

    /// WHEN the installation decision was taken. This is not decoration: the
    /// whole correctness argument is an ORDERING argument, so the phase is
    /// recorded and asserted rather than assumed.
    public enum InstallPhase: String {
        /// `+load`, i.e. dyld image load — before `main`, therefore before
        /// any application JS can issue a request. The only phase that is
        /// unconditionally early enough.
        case imageLoad = "image_load"
        /// A later explicit call. Correct only if it precedes RN's first
        /// request, which no SDK can promise about arbitrary host code.
        case deferred = "deferred"
        case notAttempted = "not_attempted"
    }

    private static let lock = NSLock()
    private static var outcome: InstallOutcome?
    private static var phase: InstallPhase = .notAttempted
    /// Written at `+load` (where durable file IO does not belong) and flushed
    /// the first time an ordinary SDK entry point runs.
    private static var pendingTrace: (outcome: InstallOutcome, phase: InstallPhase)?
    private static var authorityProvider: (() -> CaptureAuthority?)?
    private static var sink: ((NetworkCaptureCore.NetworkEvent) -> Void)?
    /// Resolves the screen current at a request's START instant. See
    /// `NetworkScreenTimeline` for why a completion-time read would be wrong.
    private static var screenResolver: ((Int64) -> String?)?
    private static var budget = NetworkBudget.frozenV1()
    private static var refusalsReported = Set<String>()
    /// INCREMENTED BEFORE THE CAPTURE GATE, AND THAT IS THE POINT. It is the
    /// only way to tell "the observer never fired" from "the observer fired and
    /// correctly emitted nothing" — two states with identical event counts and
    /// opposite meanings. Same shape as `budgetCounters`: a bare number, no
    /// request data of any kind, native-only, and NOT a JS method.
    private static var callbacks = 0

    /// The rolling wall+mono anchor the divergence detector brackets a task with.
    public static let anchors = NetworkClockGuard.RollingAnchor()

    /// Take an adjacent (wall, mono) reading. Called wherever the SDK already
    /// reads both clocks, and once at bootstrap so a task that completes before
    /// any event is still bracketed.
    public static func markAnchor() {
        anchors.update(NetworkClockGuard.Anchor(
            wallMs: Date().timeIntervalSince1970 * 1000,
            monoNs: Int64(DispatchTime.now().uptimeNanoseconds)))
    }

    // MARK: - bootstrap

    /// THE ENTRY POINT THAT ACTUALLY RUNS IN PRODUCTION — called from
    /// `TraceScoutRNObserverLoad.m`'s `+load`, i.e. at dyld image load, BEFORE
    /// `main`. It takes no parameter and returns nothing because `+load` is
    /// not a place to make decisions; it exists so the installation cannot be
    /// forgotten by a host app and cannot be raced by host startup traffic.
    ///
    /// Deliberately performs NO durable file IO: `RecoveryTrace` writes to
    /// Application Support, and doing that before `main` would put a file
    /// system round-trip on every app launch. The line is buffered and flushed
    /// by the first ordinary entry point.
    public static func bootstrapAtImageLoad() {
        _ = install(phase: .imageLoad, trace: false)
    }

    /// The deferred entry point. Idempotent, one INSTALLATION per process —
    /// but see the retry rule below: a `refusedClassAbsent` is not a decision,
    /// it is the absence of a subject.
    @discardableResult
    public static func bootstrap() -> InstallOutcome {
        install(phase: .deferred, trace: true)
    }

    @discardableResult
    private static func install(phase attemptPhase: InstallPhase, trace: Bool) -> InstallOutcome {
        lock.lock()
        let settled = outcome
        lock.unlock()
        // ONLY A REAL DECISION IS REMEMBERED. `refusedClassAbsent` means RN's
        // networking class was not registered yet — nothing was inspected and
        // nothing was changed — so caching it would let one early probe disable
        // network capture for the life of the process. Installed / collision /
        // runtime-refusal are terminal; class-absent is retried.
        if let settled, settled != .refusedClassAbsent {
            if trace { flushPendingTrace() }
            return settled == .installed ? .alreadyDone : settled
        }

        let result = attemptInstall(into: handlerClassNameOverrideForTesting ?? rnHandlerClassName)
        lock.lock()
        outcome = result
        if result != .refusedClassAbsent { phase = attemptPhase }
        pendingTrace = (result, attemptPhase)
        lock.unlock()
        markAnchor()
        if trace { flushPendingTrace() }
        return result
    }

    private static func flushPendingTrace() {
        lock.lock()
        let p = pendingTrace
        pendingTrace = nil
        lock.unlock()
        guard let p else { return }
        RecoveryTrace.shared.log("rn_net_observer_bootstrap",
                                 [("outcome", p.outcome.rawValue), ("phase", p.phase.rawValue)])
    }

    /// The phase the SUCCESSFUL (or terminally refused) attempt was made in.
    /// The fixture asserts this is `image_load`, which is the whole point of
    /// the ordering gate — an installation that merely happened is not the
    /// same fact as an installation that happened early enough.
    public static func installPhase() -> InstallPhase {
        lock.lock(); defer { lock.unlock() }; return phase
    }

    /// RN's own `NSURLSession` delegate. Named once, so the production entry
    /// point and the collision tests exercise the SAME code path — an installer
    /// that only tests exist for is an installer nobody has tested.
    public static let rnHandlerClassName = "RCTHTTPRequestHandler"

    /// The whole installation decision, parameterised by class name ONLY so a
    /// test can present a class that already implements the selector. Nothing
    /// else differs between this and the production call.
    static func attemptInstall(into className: String) -> InstallOutcome {
        guard let cls = NSClassFromString(className) else { return .refusedClassAbsent }
        let sel = NSSelectorFromString(selectorName)
        // FAIL CLOSED, AND NEVER REPLACE. `class_addMethod` would already
        // refuse to overwrite, but relying on that would leave the INTENT
        // unstated: if something already implements this selector, our observer
        // would be an override with an undefined winner, so network observation
        // is disabled and RN's networking is left exactly as it was. No
        // `method_setImplementation`, no `method_exchangeImplementations`, no
        // fallback of any kind.
        if class_respondsToSelector(cls, sel) { return .refusedSelectorPresent }
        guard let m = class_getInstanceMethod(RnNetworkObserverDonor.self, sel) else {
            return .refusedAddFailed
        }
        return class_addMethod(cls, sel, method_getImplementation(m),
                               method_getTypeEncoding(m)) ? .installed : .refusedAddFailed
    }

    public static func installOutcome() -> InstallOutcome? {
        lock.lock(); defer { lock.unlock() }; return outcome
    }

    /// Whether the class currently responds — asked of the RUNTIME, never
    /// remembered, so a test can tell "we installed it" from "it was there".
    public static var selectorPresentOnRN: Bool {
        guard let cls = NSClassFromString(rnHandlerClassName) else { return false }
        return class_respondsToSelector(cls, NSSelectorFromString(selectorName))
    }

    // MARK: - configuration

    /// # THE ELIGIBILITY EPOCH — WHY `captureEnabled == true` IS NOT ENOUGH
    ///
    /// iOS observes a request at COMPLETION. A boolean read at completion says
    /// nothing about whether the request was ever eligible: a request that
    /// STARTED while capture was off, or under a session that has since ended,
    /// would be attributed to whatever session happens to be active when its
    /// metrics arrive. That is cross-session attribution and, worse,
    /// pre-consent attribution — a request the user made before capture existed
    /// appearing in a capture record.
    ///
    /// So authority is a MOMENT, not a flag:
    ///
    /// enabled capture is authorised right now
    /// activatedMonotonicNanos when THIS authority began — the later of
    /// (network capture enabled, session began)
    /// epochToken identifies the exact epoch, so an epoch
    /// that turned over mid-projection is
    /// detectable rather than invisible
    ///
    /// A request qualifies only if it is enabled at completion AND its own
    /// `taskInterval.start` is at or after the activation boundary AND the epoch
    /// has not turned over while the event was being built.
    ///
    /// WHEN THAT CANNOT BE PROVEN THERE IS NO EVENT. A missing row is
    /// preferable to a row attributed to the wrong session, and the refusal
    /// reason names the RULE, never the request.
    public struct CaptureAuthority: Equatable {
        public let enabled: Bool
        public let activatedMonotonicNanos: Int64
        public let epochToken: String
        public init(enabled: Bool, activatedMonotonicNanos: Int64, epochToken: String) {
            self.enabled = enabled
            self.activatedMonotonicNanos = activatedMonotonicNanos
            self.epochToken = epochToken
        }
    }

    /// Default OFF. The observer may be installed and simply not emit — that is
    /// the production shape, and it is tested as its own leg.
    ///
    /// THE SINK TAKES THE EVENT, NOT ITS WIRE PAIRS. Handing over the pairs
    /// would mean the envelope builder receives a field list it cannot
    /// attribute, and `screenId` — which lives on the start context and not on
    /// the wire projection — would be unreachable. The Kotlin twin takes the
    /// event for the same reason.
    ///
    /// `screenAt` HAS NO DEFAULT. It takes the request's START instant and
    /// answers with the screen that covered it, or nil. A default of
    /// "always nil" would silently reproduce the pre-timeline behaviour at any
    /// call site that forgot it, and the whole point of the parameter is that
    /// the caller must state where start-time screen truth comes from — the
    /// same reason `CapturePrivacyEvidence` and `RnNetworkHost` refuse defaults
    /// for the facts they exist to carry. A caller that genuinely has no screen
    /// history passes `{ _ in nil }`, which READS as the choice it is.
    public static func configure(authority: @escaping () -> CaptureAuthority?,
                                 sink: @escaping (NetworkCaptureCore.NetworkEvent) -> Void,
                                 screenAt: @escaping (Int64) -> String?) {
        lock.lock()
        Self.authorityProvider = authority
        Self.sink = sink
        Self.screenResolver = screenAt
        lock.unlock()
        // The load-time line reaches the durable trace here, at a point where
        // file IO is ordinary.
        flushPendingTrace()
    }

    /// EXISTS ONLY SO A SUITE CAN RETURN THE OBSERVER TO ITS SHIPPED STATE.
    /// There is no production caller: production installs authority once, in
    /// `RnTransport.start`.
    static func unconfigureForTesting() {
        lock.lock(); defer { lock.unlock() }
        authorityProvider = nil; sink = nil; screenResolver = nil
    }

    public static func isCaptureEnabled() -> Bool {
        lock.lock(); let a = authorityProvider; lock.unlock()
        return a?()?.enabled ?? false
    }

    public static func resetForTesting() {
        lock.lock(); defer { lock.unlock() }
        authorityProvider = nil; sink = nil; screenResolver = nil
        budget = NetworkBudget.frozenV1(); refusalsReported.removeAll()
        callbacks = 0
    }

    /// Only the install memory, so a suite can exercise the retry rule without
    /// disturbing capture state.
    static func resetInstallMemoryForTesting() {
        lock.lock(); defer { lock.unlock() }
        outcome = nil; phase = .notAttempted; pendingTrace = nil
        handlerClassNameOverrideForTesting = nil
    }

    /// EXISTS SO THE RETRY RULE IS TESTED THROUGH THE MEMOIZED ENTRY POINT.
    /// Calling `attemptInstall` directly would prove only that the raw
    /// installer works — it says nothing about whether `bootstrap` consults
    /// its cache and gives up, which is the actual invariant. `internal`, so no
    /// production caller and no JS surface can reach it.
    static var handlerClassNameOverrideForTesting: String?

    public static func budgetCounters() -> (admitted: Int, dropped: Int) {
        (budget.admittedCount(), budget.droppedCount())
    }

    /// How many times the donated delivery actually reached us. Carries no
    /// request information — a count cannot describe a URL.
    public static func observedCallbackCount() -> Int {
        lock.lock(); defer { lock.unlock() }; return callbacks
    }

    // MARK: - the observation itself

    /// Called by the donated implementation. `self` there is the RN delegate, so
    /// nothing may touch donor instance state — everything lands here.
    ///
    /// WITH CAPTURE DISABLED THIS RETURNS ON THE FIRST LINE. No allocation,
    /// no URL read, no clock read, no durable write, no queue obligation.
    static func observe(session: URLSession, task: URLSessionTask,
                        metrics: URLSessionTaskMetrics) {
        lock.lock()
        // Counted FIRST, so "it fired" is recorded even when capture is off and
        // the very next line returns.
        callbacks += 1
        let auth = authorityProvider
        let out = sink
        let resolveScreen = screenResolver
        lock.unlock()
        guard let authority = auth?(), authority.enabled, let emit = out else { return }

        // Anchor FIRST and adjacently.
        let completion = NetworkClockGuard.Anchor(
            wallMs: Date().timeIntervalSince1970 * 1000,
            monoNs: Int64(DispatchTime.now().uptimeNanoseconds))
        let before = anchors.snapshot()

        let projection = NetworkClockGuard.project(
            taskStartWallMs: metrics.taskInterval.start.timeIntervalSince1970 * 1000,
            before: before,
            completion: completion)

        let last = metrics.transactionMetrics.last
        let hint = NetworkCaptureCore.protocolHint(last?.networkProtocolName)

        // `networkRequestId` IS MINTED HERE, AT COMPLETION, AND THAT IS A
        // STATED DEVIATION FROM THE SCHEMA'S DESCRIPTION ("client-minted at
        // request START"). iOS v1 installs no start hook by design, so there is
        // no earlier moment that exists. The id's PURPOSE — a stable row
        // identity other events can correlate to — is unaffected, because iOS
        // v1 emits no other event carrying it. It must be revisited the day a
        // composed host interceptor gives us a real start.
        let requestId = UUID().uuidString.lowercased()

        guard case let .accepted(startMono) = projection else {
            // A refusal is REPORTED, once per reason per process, and produces
            // no event. A per-request checkpoint would write a trace line for
            // every HTTP request a customer's app makes.
            reportOnce(NetworkCaptureCore.Refusal.clockDivergence.rawValue,
                       detail: projection.refusalReason ?? "unknown")
            return
        }

        // THE ELIGIBILITY BOUNDARY, BEFORE ANY EVENT IS BUILT. The request's
        // own start is compared against the moment this authority began. A
        // request that started before capture was enabled, or under a session
        // that has since ended, fails here and produces NOTHING — it is never
        // re-attributed to whichever session happens to be active now.
        guard startMono >= authority.activatedMonotonicNanos else {
            reportOnce(NetworkCaptureCore.Refusal.outsideCaptureEpoch.rawValue, detail: "")
            return
        }

        let startResult = NetworkCaptureCore.startContext(
            enabled: true,
            // Structural on iOS: the uploader posts through `URLSession.shared`,
            // which has NO delegate, so its tasks can never reach RN's handler.
            internalRequest: false,
            // AUTHORITY ALREADY IS THE SESSION CHECK. `networkAuthority`
            // returns non-nil only when a session's opener is durably
            // established in the current generation, so a second, independent
            // "do we have a session?" predicate would be a different question
            // asked a moment later — two sources for one fact, which is how the
            // sequence defect happened.
            hasSession: true,
            rawUrl: task.originalRequest?.url?.absoluteString,
            method: task.originalRequest?.httpMethod,
            networkRequestId: requestId,
            startedWallTime: Int64((metrics.taskInterval.start.timeIntervalSince1970 * 1000).rounded()),
            startedMonotonicNanos: startMono,
            // RESOLVED FROM `startMono`, NEVER FROM "NOW". This callback
            // fires at COMPLETION, so the current screen here is the screen the
            // user navigated to, not the one the request belongs to. `startMono`
            // is the projected start in the same uptime domain the timeline
            // stamps, so this is a lookup over recorded history; it answers nil
            // when that history cannot cover the instant, which is the honest
            // answer and the one the field already permitted.
            screenId: resolveScreen?(startMono),
            budget: budget)

        guard let start = startResult.context else {
            if let r = startResult.refusal { reportOnce(r.rawValue, detail: "") }
            return
        }

        let reqBytes = Int64(metrics.transactionMetrics.reduce(0) { $0 + $1.countOfRequestBodyBytesSent })
        let respBytes = Int64(metrics.transactionMetrics.reduce(0) { $0 + $1.countOfResponseBodyBytesReceived })

        let event: NetworkCaptureCore.NetworkEvent?
        if let http = last?.response as? HTTPURLResponse, task.error == nil {
            event = NetworkCaptureCore.completeWithResponse(
                start: start, endMonotonicNanos: completion.monoNs,
                status: http.statusCode, requestBytes: reqBytes,
                responseBytes: respBytes, protocolHint: hint)
        } else if let err = task.error as NSError? {
            event = NetworkCaptureCore.completeWithFailure(
                start: start, endMonotonicNanos: completion.monoNs,
                domain: err.domain, code: err.code,
                requestBytes: reqBytes, responseBytes: respBytes, protocolHint: hint)
        } else {
            // Neither a response nor an error: nothing truthful to say.
            budget.refund()
            reportOnce("no_outcome", detail: "")
            return
        }
        guard let e = event else { budget.refund(); reportOnce("status_unrepresentable", detail: ""); return }
        // RE-READ AFTER BUILDING. A session can end, or capture be disabled,
        // while the projection is being assembled; publishing then would write
        // a completion from the old epoch into the new one. The token makes an
        // epoch turnover DETECTABLE rather than invisible, and a turnover
        // refunds the reservation instead of silently consuming budget.
        guard let still = auth?(), still.enabled, still.epochToken == authority.epochToken else {
            budget.refund()
            reportOnce(NetworkCaptureCore.Refusal.captureEpochChanged.rawValue, detail: "")
            return
        }
        emit(e)
        markAnchor()
    }

    private static func reportOnce(_ reason: String, detail: String) {
        lock.lock()
        let fresh = refusalsReported.insert(reason).inserted
        lock.unlock()
        guard fresh else { return }
        RecoveryTrace.shared.log("rn_net_refused", [("reason", reason), ("detail", detail)])
    }
}

/// THE OBJECTIVE-C-VISIBLE DOOR THE `+load` SHIM KNOCKS ON.
///
/// `RnNetworkObserver` is a Swift enum and therefore invisible to the Objective-C
/// runtime. The shim resolves this class BY NAME (`NSClassFromString`) and calls
/// the selector BY NAME, so the `.m` file carries **no link-time dependency on
/// Swift symbols and no generated header** — which is what lets the same source
/// tree keep building as a plain SwiftPM target for `swift test` while the pod
/// compiles both languages together.
@objc(TSRNNetworkObserverBootstrap)
public final class RnNetworkObserverBootstrap: NSObject {
    /// Called from `+load`. Name-sensitive: `TraceScoutRNObserverLoad.m` and
    /// `NetworkObserverLoadShimTests` both pin this exact string.
    @objc public static func installFromImageLoad() {
        RnNetworkObserver.bootstrapAtImageLoad()
    }
}

/// The donor. Its only job is to own a real IMP with the right selector and type
/// encoding; the implementation hands off to a static immediately, because once
/// donated `self` is an `RCTHTTPRequestHandler`.
final class RnNetworkObserverDonor: NSObject, URLSessionTaskDelegate {
    @objc(URLSession:task:didFinishCollectingMetrics:)
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didFinishCollecting metrics: URLSessionTaskMetrics) {
        RnNetworkObserver.observe(session: session, task: task, metrics: metrics)
    }
}

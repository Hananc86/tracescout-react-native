import Foundation
import React

/// The React-facing adapter. Six semantic methods and `state` — byte-for-byte
/// the surface the certified Android module exposes.
///
/// Note what has NO `@objc` export here: producer family, SDK version,
/// platform, host platform, session id, project id, the ingest key, the
/// encryption key, the queue, the parked store, and the diagnostics surface.
/// The absence is the guarantee — a JS-settable identity would let a producer
/// misreport which capture code produced its bytes, and the server's capability
/// boundary would mean nothing.
@objc(TraceScoutRn)
final class TraceScoutRnModule: NSObject {

    /// THE ROUTE-HOST REGISTRY for this app.
    ///
    /// ITS PRESENCE IS THE INTEGRATION. An app that does not build one gets a
    /// bridge with `routeHosts: nil`, cannot register a host, and can never emit
    /// a capture-unavailable reason — byte-for-byte today's behaviour.
    /// THE PRODUCTION MONOTONIC CLOCK. `DispatchTime.uptimeNanoseconds` is
    /// CLOCK_UPTIME_RAW: it cannot be moved by an NTP step or a user changing the
    /// date, which a wall clock can. A budget measured on a wall clock could be
    /// "expired" by a time change the app had nothing to do with.
    static let routeHosts = RouteHostRegistry {
        Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
    }

    /// THE PRODUCTION DEADLINE OWNER — the main queue, which is also where
    /// capture runs, so the callback cannot race the view hierarchy.
    static let attachDeadline = AttachDeadline(scheduler: AttachDeadline.MainQueueScheduler())


    /// The frame co-captured with the most recent hierarchy walk (same pass),
    /// plus WHY there is no frame when there is none. The reason travels with
    /// the result because only the capture layer knows it, and the bridge must
    /// not guess whether a refusal was transient.
    private var pendingFrame: (HierarchyResult, FrameRasterizer.Frame?, String?)?

    /// The route JS last navigated to, written from the JS thread and read on
    /// the main thread during phase C. This is what makes "JS navigated away
    /// while we were rasterizing" observable natively — a genuinely
    /// cross-thread signal, not a comparison of a value with itself.
    private let routeLock = NSLock()
    private var navRouteId: String?
    private func currentNavRoute() -> String? {
        routeLock.lock(); defer { routeLock.unlock() }; return navRouteId
    }
    private func setNavRoute(_ r: String?) {
        routeLock.lock(); navRouteId = r; routeLock.unlock()
        // THE SCREEN TIMELINE IS FED HERE AND NOWHERE ELSE, because this is
        // already the ONE place the current route is set — the initial route and
        // every RECORDED navigation both arrive through it, and a refused
        // navigation never reaches it. A per-call-site note would be two sources
        // for one fact, and the next call site added would silently miss one.
        //
        // A network request that completes later is attributed to the screen
        // that covered its START, so this is what makes `screenId` resolvable on
        // iOS at all; see `NetworkScreenTimeline`.
        //
        // No call site passes nil today (both supply a route id), so there is no
        // "the route ended" interval to invent here: a nil is simply not a
        // transition, and a request starting before the first route still
        // resolves to nil because the timeline cannot cover that instant.
        if let route = r { RnTransport.shared.noteScreen(route) }
    }

    // ONE RESOLUTION PER SESSION, SHARED BY BOTH CONSUMERS. The capture path
    // and the session-start evidence must never resolve the scope separately —
    // that is exactly how capture could run at A while the evidence reported H,
    // which is the disagreement the evidence exists to make impossible.
    private let policyLock = NSLock()
    private var sessionPolicy: ResolvedFidelityPolicy?

    /// The session's resolved policy, resolving once on first use.
    private func activePolicy() -> ResolvedFidelityPolicy {
        policyLock.lock(); defer { policyLock.unlock() }
        if let p = sessionPolicy { return p }
        // BEFORE THE POLICY IS READ, NOT AFTER. This is the build-19 fix:
        // `apply` used to run only from the config screen's Save button, so a
        // launch that never opened that screen resolved its policy from a stale
        // persisted rung. Placing it here makes the ordering STRUCTURAL — the
        // policy cannot be materialized without the launch normalization having
        // run first.
        LabPostureAutoPersist.applyAtLaunchOnce(projectId: SecureConfig.currentProjectId())
        let p = ResolvedFidelityPolicy.current()
        LaunchSequence.record(.policyResolved)
        sessionPolicy = p
        return p
    }

    /// Re-resolve for a NEW session. Called from the session opener only, so a
    /// configuration saved between sessions takes effect at the next start —
    /// and never mid-session, where capture and evidence would disagree.
    private func beginPolicySession() -> ResolvedFidelityPolicy {
        policyLock.lock()
        // Same ordering guarantee on the new-session path.
        LabPostureAutoPersist.applyAtLaunchOnce(projectId: SecureConfig.currentProjectId())
        let p = ResolvedFidelityPolicy.current()
        LaunchSequence.record(.policyResolved)
        sessionPolicy = p
        policyLock.unlock()
        return p
    }

    /// THE OPENER SNAPSHOT, IN THE REQUIRED ORDER, IN ONE CRITICAL SECTION.
    ///
    /// persisted config -> Lab normalization -> resolved fidelity policy
    /// -> resolved interaction consent -> THIS value -> session_start
    ///
    /// NOTHING DOWNSTREAM RE-READS. The alternative — open the session, then
    /// consult UserDefaults again and reconstruct what we believe happened —
    /// describes a LATER state, and a certification that accepted it would be
    /// accepting a reconstruction as evidence of an event.
    private func beginOpenerSnapshot() -> OpenerSnapshot {
        let policy = beginPolicySession()
        // Read ONCE, here, beside the policy it belongs with.
        let consent = SecureConfig.interactionCaptureEnabled()
        return OpenerSnapshot(
            fidelity: policy.fidelityEvidence(interactionCaptureEnabled: consent),
            identity: AppIdentitySnapshot.resolve(),
            // Read ONCE, here, beside the policy and the consent it belongs with.
            // THE DECLARATION ASKS THE CAPTURE LAYER WHETHER IT CAN TRY.
            // Posture and the opt-in are what the operator asked for; only the
            // capture path knows whether a frame is attemptable at all under
            // this app's binding. Build 31 declared `semantic_with_frames` from
            // the first two alone and produced zero frames on six screens.
            // `policy.labScopeActive` IS NO LONGER PASSED, AND ITS ABSENCE
            // IS THE FIX. The declaration used to require it while the capture
            // path never consulted it, so a non-Lab session with the opt-in on
            // declared `wireframe_only` and uploaded frames — measured, four of
            // them, across two preserved sessions. `policy` is still read here
            // for `fidelity:` above, which is the axis lab scope genuinely
            // belongs to.
            captureMode: OpenerSnapshot.declaredCaptureMode(
                framesEnabled: SecureConfig.framesEnabled(),
                capability: FrameCapability.forCapturePath(
                    // THIS IS "IS ROUTE-HOST SUPPORT WIRED", NOT "IS A ROUTE
                    // BOUND YET" — `isIntegrated` reads `routeHosts != nil`.
                    // The opener runs before the first route can possibly have
                    // bound, and the declaration records REQUESTED capability,
                    // so it must never be downgraded because a binding has not
                    // happened yet. A capability that varied with binding state
                    // would report `wireframe_only` on every cold start of a
                    // perfectly capable app.
                    integrated: bridge.isIntegrated(),
                    // TRUE since scoped rasterization landed: a bound capture
                    // root is photographed through
                    // `FrameRasterizer.capture(root:window:…)`. Pinned at the
                    // source by RouteOwnershipTests — if the walker ever
                    // reintroduces a blanket bound-root refusal, this literal
                    // is what has to come back to false with it.
                    boundRootsRasterizable: true)))
    }

    private lazy var bridge: RnBridge = {
        RnBridge(
            identity: { RnTransport.shared.currentIdentity() },
            // ONE owner of session creation: RnBridge.startSession. The adapter
            // supplies the collaborators and calls neither itself. It used to
            // pre-call beginSession here AND let the bridge call it again,
            // minting two ids per semantic start and stamping the opener with
            // the second while the first was orphaned — and the route the
            // caller supplied was discarded by the closure's `nil`.
            mintCandidate: { RnTransport.shared.mintCandidateSession(initialRoute: $0) },
            publish: { RnTransport.shared.publishSession($0, generation: $1) },
            configured: { RnTransport.shared.isConfigured() },
            sink: TransportSink(),
            clock: SystemClock(),
            ids: UuidEventIds(),
            // Phase 1 hierarchy capture. NATIVE wiring only — presence of this
            // closure is the enablement, and no @objc export can reach or
            // replace it, so the JS surface stays exactly six semantic
            // methods. The walker refuses (nil) in background / no-window
            // states and the bridge then records an honest metadata_only
            // snapshot.
            routeHosts: Self.routeHosts,
            attachDeadline: Self.attachDeadline,
            hierarchy: { [unowned self] routeId in
                // Phase 2: with frames opted in, the frame is rasterized in the
                // SAME main-thread pass as the walk and parked here for that
                // exact result; the frame closure hands it over only then.
                // The rung comes from the NATIVE store and defaults to the certified
                // posture. It is read here, at the one call site, so there is no
                // ambient state a JS caller could reach.
                // The refusal is recorded HERE, where the capture layer
                // returns it, and cleared FIRST — reading it from the frame
                // hand-over would leave a stale reason behind whenever the walk
                // itself refused (no window, background) and there was no
                // hand-over at all.
                self.lastFrameRefusal = nil
                self.pendingFrame = nil
                // ONE RESOLVED POLICY, NOT TWO INDEPENDENT READS. The rung
                // and the ladder switch used to be read here separately, each
                // with its own default — two values that must agree, and so two
                // values that can disagree. Build 18 shipped with both at their
                // defaults and every ordinary Lab screen came back 100% masked.
                // `ResolvedFidelityPolicy` decides once, against the exact
                // project + bundle scope, and NOTHING here may pass a rung the
                // resolver did not choose.
                let policy = self.activePolicy()
                // ONE observation, reported completely: the capture if it
                // happened, and a reason ONLY for the two conditions the walker
                // actually observed. Its other refusals carry none.
                // OWNERSHIP IS ASKED OF THE REGISTRY, NOT GUESSED FROM THE
                // WINDOW. An integrated app must name a capture root for the
                // route or nothing is walked; an unintegrated app keeps the
                // legacy key-window walk unchanged.
                let bound = self.bridge.boundRoot(routeId)
                let binding: HierarchySnapshotter.CaptureRootBinding = {
                    if !self.bridge.isIntegrated() { return .unintegrated }
                    if let b = bound { return .bound(b) }
                    return .unowned
                }()
                // PASSIVE ACCOUNTING — one meter per capture, threaded through
                // the walk and the raster path so the phases are measured
                // around the SAME calls that produce the capture. It changes
                // nothing about what is captured.
                let meter = CaptureAccounting.PhaseMeter()
                let observed = HierarchySnapshotter.observe(
                    routeId: routeId,
                    wantFrame: SecureConfig.framesEnabled(),
                    rung: policy.rung,
                    ladderMode: policy.ladderMode,
                    // THE RESOLVER'S OWN ANSWER, NOT A SECOND DERIVATION.
                    // `policy` is ONE `ResolvedFidelityPolicy` read once per
                    // session, so the scope the walker enforces and the scope
                    // the session's `fidelityPolicy` evidence declares cannot
                    // disagree — which is exactly how evidence and capture come
                    // apart when each computes its own answer.
                    labScopeQualified: policy.labScopeActive,
                    currentRoute: { [unowned self] in self.currentNavRoute() },
                    binding: binding,
                    // REACT TAG -> UIView THROUGH THE UIManager, ON THE MAIN
                    // THREAD. A tag for a replaced or unmounted host resolves to
                    // nil, which is exactly the staleness signal the walker
                    // needs. Nothing here can name a view JS does not own.
                    resolver: { tag in TraceScoutRnModule.viewForReactTag(tag) },
                    bindingStillCurrent: { [weak self] in
                        guard let self = self, let b = bound else { return false }
                        return self.bridge.bindingIsCurrent(routeId, b)
                    },
                    meter: meter)
                    let ownershipNodes: Int = observed.capture.map { c in
                        func walk(_ n: CanonicalNode) -> Int { 1 + n.children.reduce(0) { $0 + walk($1) } }
                        return c.result.nodes.reduce(0) { $0 + walk($1) }
                    } ?? 0
                    TraceScoutRnModule.rhTrace("rh_ownership route=\(routeId) binding=\(String(describing: binding).split(separator: "(").first.map(String.init) ?? "") host=\(bound?.hostId ?? "<none>") refusal=\(HierarchySnapshotter.lastOwnershipRefusal.rawValue) roots=\(observed.capture?.result.nodes.count ?? 0) total=\(ownershipNodes) roles=\(observed.capture.map { c in c.result.nodes.flatMap { n -> [String] in func f(_ x: CanonicalNode) -> [String] { [x.role] + x.children.flatMap(f) }; return f(n) }.prefix(8).joined(separator: ",") } ?? "-") rootBounds=\(observed.capture?.result.nodes.first?.bounds.map { "\($0.x),\($0.y),\($0.w)x\($0.h)" } ?? "-") frame=\(observed.capture.map { $0.frameRefusal ?? ($0.frame != nil ? "captured" : "none") } ?? "-")")
                let mapped = observed.capture.map { c -> RnBridge.HierarchyCapture in
                    self.pendingFrame = (c.result, c.frame, c.frameRefusal)
                    self.lastFrameRefusal = c.frameRefusal
                    return RnBridge.HierarchyCapture(
                        result: c.result, durationMs: c.durationMs,
                        // The capture transaction's own phase-A digest. Surfaced
                        // so the frame cadence can be keyed on the SUBJECT; it is
                        // never recomputed here, because a parallel digest would
                        // be free to disagree with the one that gates the frame.
                        coherenceFingerprint: c.coherenceFingerprint,
                        // Accounting only. PRODUCED bytes are the rasterizer's
                        // own count, taken here before `frame` decides anything.
                        walkNanos: c.walkNanos, phases: c.phases,
                        producedFrameBytes: c.frame?.bytes.count)
                }
                // MODEL B — THE CAPTURE PATH CALL SITE. `screenSnapshot` is the
                // ONE capture entry: the React method AND the owned frame
                // cadence's `driverTick` both reach it, so putting the bind and
                // the attach here covers every capture candidate without adding
                // a second loop. It hops to the main thread itself and does
                // nothing at all for an unowned route, so a capture is never
                // delayed or changed by it — `observed` above is already
                // computed and is not read here.
                self.ensureWebViewCorrelationIfPossible(routeId: routeId)
                return RnBridge.HierarchyOutcome(
                    capture: mapped,
                    captureUnavailableReason: observed.captureUnavailableReason)
            },
            // Phase 2 visual frames. OPT-IN is the persisted native switch
            // (never JS); the rasterizer refuses in background and paints the
            // hierarchy's masked/blocked rects BEFORE encoding; the bytes go to
            // the durable frame store and drain through the transport. A
            // refusal at any step yields nil → the snapshot stays wireframe_only.
            frame: { [unowned self] routeId, hierarchy in
                // Hand over ONLY the frame rasterized with THIS exact result.
                guard let p = self.pendingFrame else { return nil }
                self.pendingFrame = nil
                guard p.0.nodes.count == hierarchy.nodes.count, p.0.totalNodes == hierarchy.totalNodes,
                      p.0.allowedIdentities == hierarchy.allowedIdentities, let f = p.1 else { return nil }
                guard let sid = RnTransport.shared.currentIdentity()?.sessionId else { return nil }
                let vfId = "vf_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
                let header = FrameHeader(visualFrameId: vfId, sessionId: sid, screenId: routeId,
                                         contentHash: f.contentSha256, width: f.width, height: f.height,
                                         byteSize: f.bytes.count, format: f.format, attempts: 0)
                guard RnTransport.shared.enqueueFrame(header, bytes: f.bytes) else { return nil }
                return RnBridge.FrameCapture(visualFrameId: vfId, contentHash: f.contentSha256,
                                             width: f.width, height: f.height, byteSize: f.bytes.count,
                                             format: f.format, quality: FrameBudgets.quality,
                                             downscale: f.downscale)
            },
            // Why the last decided frame was not obtained. Read straight from
            // the capture layer's own typed refusal — never inferred.
            frameRefusal: { [unowned self] in self.lastFrameRefusal },
            // Build 26 — DURABLE capture observability. Injected, native only
            // (no @objc export reaches it, so the JS surface stays exactly six
            // semantic methods), and it decides nothing: every record is written
            // after its branch and no caller reads the result.
            captureLog: RnTransport.captureLog,
            // Subject-keyed frame cadence, LAB SCOPE ONLY. Resolved from the same
            // single authority the rung comes from — never a route name, never a
            // build identity. False (the default when this closure is absent)
            // keeps the certified build-18 session cadence byte for byte.
            labCadence: { [unowned self] in self.activePolicy().labScopeActive },
            // THE MOTION OBSERVATION — scroll geometry of the OWNED capture
            // root, and nothing else.
            //
            // IT ASKS THE REGISTRY THE SAME QUESTION THE WALKER ASKS, and
            // refuses on the same answers. An unintegrated host, an unowned
            // route, a superseded binding or a handle that no longer names a
            // live view all yield nil — so a screen can never be kept awake, or
            // keyed, by scrolling that belongs to something it does not own.
            // Nil is never motion and never enters the cadence subject, so
            // every refusal here lands on the certified Build-26 behaviour.
            visibleState: { [unowned self] routeId in
                guard Thread.isMainThread, self.bridge.isIntegrated(),
                      let b = self.bridge.boundRoot(routeId),
                      self.bridge.bindingIsCurrent(routeId, b),
                      let v = TraceScoutRnModule.viewForReactTag(b.rootHandle)
                else { return nil }
                // REPAIR HERE TOO, NOT ONLY ON NAVIGATION. A web view that
                // is remounted without a navigation — a reload, a re-render
                // that replaces the host — would otherwise leave the
                // observation retired until the next route change. This call
                // site already holds a VERIFIED current binding on the main
                // thread, and `ensure` is a no-op when the same live surface
                // is already observed.
                self.ensureWebScrollObservationIfPossible(routeId: routeId)
                return VisibleStateProbe.sample(root: v)
            },
            // Session policy evidence — the INTERNAL FIXTURE BUNDLE ONLY, so an
            // ordinary customer envelope is byte-identical to before. The gate
            // is the bundle alone, deliberately: a fixture pointed at the wrong
            // project must still report `labScopeQualified: false` +
            // `effectivePolicy: strict`, which is direct evidence that the
            // fallback works rather than an absence we would have to interpret.
            fidelityEvidence: { [unowned self] in
                guard Bundle.main.bundleIdentifier == ResolvedFidelityPolicy.labBundleId
                else { return nil }
                return self.beginOpenerSnapshot()
            },
            // Bounded, non-blocking. asyncAfter, never sleep: a capture must not
            // be able to hang UI rendering while it waits for the UI to settle.
            schedule: { ms, work in
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(ms)), execute: work)
            },
            // MODEL B — the native↔WebView correlation for the CURRENT session.
            //
            // IT ASKS THE HOLDER EVERY EVENT AND CACHES NOTHING. An
            // admission is retired by a navigation or a revoked policy, so a
            // value read once would keep riding events after the correlation
            // was dropped — the exact false datum this lane exists to produce
            // correctly.
            //
            // THE SESSION IS NAMED, NOT ASSUMED. The holder compares the id
            // it was bound with, so a session that replaced another can never
            // read the previous one's segment.
            webViewRef: {
                RnWebViewSessionBinding.shared.carrier(
                    nativeSessionId: RnTransport.shared.currentIdentity()?.sessionId)
            },
            // Last: Swift requires call-site order to match the signature.
            trace: { TraceScoutRnModule.rhTrace($0) })
    }()

    private var lastFrameRefusal: String?

    private struct TransportSink: RnBridge.DurableSink {
        func enqueue(_ build: (Int64) -> Data) -> Int64? {
            RnTransport.shared.enqueue(build)
        }
        func flushHint(_ reason: String) { RnTransport.shared.requestDrain(reason: reason) }
        func stateName() -> String { RnTransport.shared.state().rawValue }
        func currentGeneration() -> UInt64 { RnTransport.shared.currentGeneration() }
    }

    private struct SystemClock: RnBridge.NativeClock {
        func wallMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
        /// A boot-stable monotonic source: wall time may jump, and the ordering
        /// authority must not.
        func monotonicNanos() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds) }
    }

    private struct UuidEventIds: RnBridge.EventIdSource {
        func newEventId() -> String {
            "evt_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        }
    }

    @objc static func requiresMainQueueSetup() -> Bool { false }

    @objc(startSession:resolver:rejecter:)
    func startSession(_ initialRouteId: String?, resolver: RCTPromiseResolveBlock,
                      rejecter: RCTPromiseRejectBlock) {
        // The bridge owns the whole act — mint, durably append the opener,
        // publish. The adapter only forwards, and it forwards the caller's
        // route rather than dropping it.
        let r = bridge.startSession(initialRouteId: initialRouteId)
        if r.outcome == .recorded {
            setNavRoute(initialRouteId)
#if canImport(UIKit)
            installInteractionObserverIfPossible()
            // A new session retires any observation bound to the previous one;
            // `ensure` then re-installs only if the opening route qualifies.
            invalidateWebScrollObservation("session_start")
            ensureWebScrollObservationIfPossible(routeId: initialRouteId)
            // MODEL B — THE SESSION LIFECYCLE CALL SITE. A new session may
            // never inherit the previous one's correlation: the holder's own
            // session comparison already refuses it, and the reset is the
            // second line. The bind then happens inside `ensure`, so the
            // declared-allowlist decision is taken once, at session start,
            // whether or not this route has a web view.
            RnWebViewSessionBinding.shared.reset(reason: "session_start")
            ensureWebViewCorrelationIfPossible(routeId: initialRouteId)
#endif
        }
        resolver(r.outcome.rawValue)
    }

    @objc(navigate:toRouteId:toRouteName:transition:resolver:rejecter:)
    func navigate(_ fromRouteId: String?, toRouteId: String, toRouteName: String?,
                  transition: String, resolver: RCTPromiseResolveBlock,
                  rejecter: RCTPromiseRejectBlock) {
        let r = bridge.navigate(fromRouteId: fromRouteId, toRouteId: toRouteId,
                                toRouteName: toRouteName, transition: transition)
        // Only a RECORDED navigation moves the coherence route: a refused one
        // did not happen, and treating it as if it had would make phase C
        // report route_changed for a navigation the SDK rejected.
        if r.outcome == .recorded {
            setNavRoute(toRouteId)
#if canImport(UIKit)
            // Re-run on every recorded navigation: if the surface was remounted
            // this rebinds to the new one, and if it was not this is an
            // `alreadyBound` no-op. Binding only at session start would leave
            // the observer on a retired view after a reload, still reporting
            // coordinates, still looking healthy.
            installInteractionObserverIfPossible()
            // THE ROUTE IS PART OF THE BINDING, so leaving the qualifying
            // route must silence the observation rather than leave it feeding
            // `visibleMutation` from a surface the session is no longer on.
            // `ensure` retires it for a non-qualifying route by construction.
            ensureWebScrollObservationIfPossible(routeId: toRouteId)
            // MODEL B — a navigation is the moment an admission can stop being
            // entitled to its trust, and `ensure` revalidates BEFORE it
            // attaches. A web view mounted by the arriving route is also
            // attached here, so the identity is in place for its first
            // document rather than its second.
            ensureWebViewCorrelationIfPossible(routeId: toRouteId)
#endif
        }
        resolver(r.outcome.rawValue)
    }

    @objc(screenSnapshot:resolver:rejecter:)
    func screenSnapshot(_ routeId: String, resolver: RCTPromiseResolveBlock,
                        rejecter: RCTPromiseRejectBlock) {
        resolver(bridge.screenSnapshot(routeId: routeId).outcome.rawValue)
    }

    @objc(lifecycle:resolver:rejecter:)
    func lifecycle(_ transition: String, resolver: RCTPromiseResolveBlock,
                   rejecter: RCTPromiseRejectBlock) {
        let r = bridge.lifecycle(transition)
#if canImport(UIKit)
        // BACKGROUND DETACHES. A recognizer on a view nobody is showing can
        // only produce coordinates for a screen the user is not looking at, and
        // the frame side already refuses to capture when not foreground -- so
        // keeping it attached would let the two disagree about whether a
        // session is happening.
        // The SAME rule the frame side uses (`RnBridge.lifecycle` suspends the
        // capture driver on background OR inactive). Detaching on only one of
        // them would leave the observer live while frames are suspended --
        // producing taps for a stretch of session that has no picture.
        switch transition {
        case "background", "inactive":
            removeInteractionObserver()
            // The frame side refuses to capture outside the foreground, so an
            // observation that kept waking the driver there would be arming a
            // loop whose every candidate is refused.
            invalidateWebScrollObservation(transition)
        case "foreground", "active":
            installInteractionObserverIfPossible()
            ensureWebScrollObservationIfPossible(routeId: currentNavRoute())
            // MODEL B — returning to the foreground is the other moment a web
            // view may have navigated while we were not looking, so the
            // revalidation inside `ensure` runs before anything reads the
            // carrier again.
            ensureWebViewCorrelationIfPossible(routeId: currentNavRoute())
        default: break
        }
#endif
        resolver(r.outcome.rawValue)
    }

    @objc(declareNeverRecord:never:resolver:rejecter:)
    func declareNeverRecord(_ routeId: String, never: Bool, resolver: RCTPromiseResolveBlock,
                            rejecter: RCTPromiseRejectBlock) {
        resolver(bridge.declareNeverRecord(routeId: routeId, never: never).rawValue)
    }

    // ── THE ROUTE-HOST INTEGRATION CONTRACT, EXPOSED TO JS ─────────────────
    // FOUR SEMANTIC DECLARATIONS, NOTHING MORE. A host may say where it is
    // and whether it became ready. It cannot set producer identity, privacy
    // state, a reason, a budget or a capture mode — the JS surface stays
    // semantics-only, which is what makes a producer unable to misreport which
    // capture code produced its bytes.
    //
    // AN APP THAT CALLS NONE OF THESE IS UNAFFECTED. No host, no verdict.

    /// REACT TAG -> UIView.
    ///
    /// SEARCHED, NOT LOOKED UP THROUGH `RCTUIManager`, AND DELIBERATELY SO.
    /// `RCTUIManager.view(forReactTag:)` is the OLD-architecture registry. RN
    /// 0.85 makes the New Architecture mandatory, where mounted views live
    /// under `RCTSurfacePresenter`/Fabric and that lookup is not the authority.
    /// Reaching for the bridge is also blocked here by a name collision: RN
    /// injects into a property literally named `bridge`, and `bridge` on this
    /// module is already the SDK's `RnBridge`. Walking the live view tree for
    /// `reactTag` works under both architectures and asks the thing that
    /// actually exists on screen.
    ///
    /// THE SEARCH DECIDES NOTHING. It only FINDS a candidate; whether that
    /// candidate may be captured — content window, foreground-active scene,
    /// still the current binding — is the walker's decision. Keeping find and
    /// authorize apart is what stops a permissive lookup from becoming a
    /// permissive capture.
    ///
    /// Main thread only: `reactTag` and the view tree are UIKit state.
    /// THE ONE DURABLE ROUTE-HOST TRACE SINK: a file in Application Support,
    /// read back through `simctl get_app_container`, plus NSLog. Unified logging
    /// alone is not evidence — it rotates, and a transition we cannot read
    /// afterwards is a transition we did not record. Mirrors the Android module's
    /// `rhTrace` so the two traces read identically. Carries only what the bridge
    /// composed: attempt id, route, timings, typed reasons.
    static func rhTrace(_ line: String) {
        NSLog("TSRouteHost %@", line)
        let ms = Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
        guard let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("routehost-trace.log")
        let data = Data("\(ms) \(line)\n".utf8)
        if let h = try? FileHandle(forWritingTo: url) { defer { try? h.close() }; _ = try? h.seekToEnd(); try? h.write(contentsOf: data) }
        else { try? data.write(to: url) }
    }

    static func viewForReactTag(_ tag: Int) -> UIView? {
        guard tag > 0 else { return nil }
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        for w in windows {
            if let hit = findReactTag(tag, in: w, depth: 0) { return hit }
        }
        return nil
    }

    /// Bounded depth so a pathological tree cannot turn a capture attempt into
    /// an unbounded main-thread walk. 64 is far deeper than any real RN screen;
    /// exceeding it returns nil, which refuses the capture rather than
    /// truncating it into a partial one.
    private static func findReactTag(_ tag: Int, in view: UIView, depth: Int) -> UIView? {
        if depth > 64 { return nil }
        // FABRIC IDENTIFIES A VIEW BY `UIView.tag`, NOT THE LEGACY `reactTag`.
        // `RCTComponentViewRegistry` sets `componentViewDescriptor.view.tag = tag`
        // and the mounting manager reads `view.tag` back; the `reactTag`
        // associated object belongs to the Paper renderer. The first simulator
        // drive matched only `reactTag` and every BOUND route resolved to
        // `unresolved` — a correct refusal of a lookup that could never succeed
        // on the New Architecture. Both are checked; a match on either is the
        // same view identity `findNodeHandle` handed JS.
        if view.tag == tag { return view }
        if let t = view.reactTag, t.intValue == tag { return view }
        for sub in view.subviews {
            if let hit = findReactTag(tag, in: sub, depth: depth + 1) { return hit }
        }
        return nil
    }

    // THE ROOT HANDLE IS A REACT TAG, NOT A VIEW. JS can only name a node it
    // already owns; it cannot hand us a UIView, and it cannot widen the capture
    // to a subtree it does not own — the UIManager resolves the tag and the
    // walker verifies the result is live, in a content window, and in a
    // foreground-active scene.
    @objc(hostAttached:hostId:rootHandle:resolver:rejecter:)
    func hostAttached(_ routeId: String, hostId: String, rootHandle: NSNumber,
                      resolver: RCTPromiseResolveBlock,
                      rejecter: RCTPromiseRejectBlock) {
        let h = rootHandle.intValue
        TraceScoutRnModule.rhTrace("rh_declare hostAttached route=\(routeId) host=\(hostId) root=\(h > 0 ? String(h) : "0")")
        resolver(bridge.hostAttached(routeId: routeId, hostId: hostId,
                                     rootHandle: h > 0 ? h : nil).rawValue)
    }

    @objc(hostReadinessPending:hostId:resolver:rejecter:)
    func hostReadinessPending(_ routeId: String, hostId: String, resolver: RCTPromiseResolveBlock,
                              rejecter: RCTPromiseRejectBlock) {
        TraceScoutRnModule.rhTrace("rh_declare hostReadinessPending route=\(routeId) host=\(hostId)")
        resolver(bridge.hostReadinessPending(routeId: routeId, hostId: hostId).rawValue)
    }

    @objc(hostReady:hostId:resolver:rejecter:)
    func hostReady(_ routeId: String, hostId: String, resolver: RCTPromiseResolveBlock,
                   rejecter: RCTPromiseRejectBlock) {
        TraceScoutRnModule.rhTrace("rh_declare hostReady route=\(routeId) host=\(hostId)")
        resolver(bridge.hostReady(routeId: routeId, hostId: hostId).rawValue)
    }

    @objc(hostFailed:hostId:resolver:rejecter:)
    func hostFailed(_ routeId: String, hostId: String, resolver: RCTPromiseResolveBlock,
                    rejecter: RCTPromiseRejectBlock) {
        TraceScoutRnModule.rhTrace("rh_declare hostFailed route=\(routeId) host=\(hostId)")
        resolver(bridge.hostFailed(routeId: routeId, hostId: hostId).rawValue)
    }

    @objc(hostDetached:hostId:resolver:rejecter:)
    func hostDetached(_ routeId: String, hostId: String, resolver: RCTPromiseResolveBlock,
                      rejecter: RCTPromiseRejectBlock) {
        TraceScoutRnModule.rhTrace("rh_declare hostDetached route=\(routeId) host=\(hostId)")
        resolver(bridge.hostDetached(routeId: routeId, hostId: hostId).rawValue)
    }

    @objc(state:rejecter:)
    func state(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) {
        resolver(bridge.state())
    }

    // ── passive interaction observation ─────────────────────────────────────
    //
    // NOTHING HERE IS `@objc`. The JS surface stays exactly the six semantic
    // methods plus `state`. Interaction capture is a NATIVE concern with a
    // native consent gate; a JS-callable entry point would let the layer we are
    // observing decide what gets observed.

#if canImport(UIKit)
    /// Where the observer last bound, and whether we had to settle for the
    /// window. Kept so the lifecycle can be asserted on WHO we bound to rather
    /// than on how many recognizers exist somewhere.
    struct Wiring {
        let identity: ObjectIdentifier?
        let matchedClassName: String
        let fellBackToWindow: Bool
        /// Whether an observer was actually attached. `fellBackToWindow` alone
        /// does not say — the fallback is DISCLOSED but never attached to.
        let attached: Bool
    }
    private(set) var wiring: Wiring?

    /// Idempotent by design: called from every point that could plausibly be
    /// the first moment a surface exists, because there is no single reliable
    /// "the RN surface is now up" callback and a wiring that happens only on
    /// one of several paths is a wiring that sometimes does not happen.
    /// UIKit IS MAIN-THREAD-ONLY AND THIS IS CALLED FROM THE TURBOMODULE
    /// QUEUE. React invokes `startSession` / `navigate` / `lifecycle` on
    /// `com.meta.react.turbomodulemanager.queue`, and the first version of this
    /// wiring read `UIApplication.shared.connectedScenes` and attached a
    /// UIGestureRecognizer straight from there. Main Thread Checker aborted the
    /// process with SIGABRT inside `RnSurfaceLocator.keyWindow`.
    ///
    /// The crash is the GOOD outcome. In Release the checker is absent, so the
    /// same code would have raced the view hierarchy instead — intermittent
    /// corruption on a customer's device rather than a loud failure in CI. This
    /// was reachable only once configuration succeeded, which is why four
    /// earlier CI failures hid it.
    ///
    /// Synchronous when already on main, so the unit tests' semantics and the
    /// returned Outcome are unchanged; marshalled otherwise, where no caller
    /// can use a synchronous answer anyway.
    @discardableResult
    func installInteractionObserverIfPossible() -> InteractionSurfaceBinding.Outcome? {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in
                _ = self?.installInteractionObserverOnMain()
            }
            return nil
        }
        return installInteractionObserverOnMain()
    }

    @discardableResult
    private func installInteractionObserverOnMain() -> InteractionSurfaceBinding.Outcome? {
        guard let located = RnSurfaceLocator.locate() else { return nil }

        // THE FALLBACK IS DIAGNOSTIC FOR THE FRAME LANE AND DISQUALIFYING
        // FOR THIS ONE. When no React root can be found, the locator reports
        // the key window so the visual side still knows what it is looking at
        // but attaching a touch observer to a whole UIWindow is a global
        // input hook, which is exactly the thing the policy's first branch
        // exists to make impossible. With multiple windows or scenes it would
        // also observe surfaces that are not the replay subject at all. So the
        // interaction lane stays surface-scoped: no root view, no observer, no
        // positional output — and any previous binding is released rather than
        // left pointing at a surface we can no longer find.
        // The RULE lives in InteractionSurfaceBinding, which is in the test
        // target; this module is not. Keeping the decision here would put it
        // where neither Swift suite can execute it.
        let outcome = InteractionSurfaceBinding.shared.bindSurfaceScoped(
            located,
            clock: { Int64(DispatchTime.now().uptimeNanoseconds) }
        ) { [weak self] observation in
            self?.handle(observation, in: located.view)
        }
        let attached = outcome != .refusedFallbackWindow
        wiring = Wiring(identity: attached ? ObjectIdentifier(located.view) : nil,
                        matchedClassName: located.matchedClassName,
                        fellBackToWindow: located.fellBackToWindow,
                        attached: attached)
        return outcome
    }

    // ── the visible-mutation emitter ────────────────────────────────────────

    /// Install / re-point / retire the allowed Lab web view's scroll
    /// observation for `routeId`.
    ///
    /// THE SAME OWNERSHIP QUESTION THE WALKER ASKS, AND THE SAME REFUSALS.
    /// An unintegrated host, an unowned route, a superseded binding or a handle
    /// that no longer names a live view all retire the observation — so a
    /// screen can never be woken by scrolling that belongs to something it does
    /// not own. Qualification is the visibility exception's own conjunction,
    /// delegated rather than restated.
    ///
    /// IT SCHEDULES NOTHING AND CAPTURES NOTHING. The callback's entire
    /// effect is `bridge.noteActivity(.visibleMutation)`, which moves one
    /// timestamp and arms the ONE existing driver loop. Budgets, privacy,
    /// coherence and the Lab session bound are untouched.
    func ensureWebScrollObservationIfPossible(routeId: String?) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in
                self?.ensureWebScrollObservationIfPossible(routeId: routeId)
            }
            return
        }
        guard let routeId = routeId else {
            WebScrollObserver.shared.invalidate(reason: "no_route"); return
        }
        let policy = activePolicy()
        let qualifies = WebScrollGate.qualifies(
            labScopeQualified: policy.labScopeActive, ladderMode: policy.ladderMode,
            rung: policy.rung, routeId: routeId)
        guard qualifies else {
            WebScrollObserver.shared.invalidate(reason: "not_qualified"); return
        }
        guard bridge.isIntegrated(), let b = bridge.boundRoot(routeId),
              bridge.bindingIsCurrent(routeId, b),
              let root = TraceScoutRnModule.viewForReactTag(b.rootHandle) else {
            WebScrollObserver.shared.invalidate(reason: "unowned_or_stale"); return
        }
        let outcome = WebScrollObserver.shared.ensure(
            root: root, routeId: routeId,
            sessionId: RnTransport.shared.currentIdentity()?.sessionId,
            qualifies: true,
            nowMs: { Int64(Date().timeIntervalSince1970 * 1000) },
            // RE-EVALUATED ON EVERY FORWARDED NOTIFICATION, not captured
            // once. A rung change, a project change or a route change between
            // installation and a callback must silence it, and a boolean copied
            // at install time cannot express that.
            qualifiesNow: { [weak self] in
                guard let self = self else { return false }
                let p = self.activePolicy()
                return WebScrollGate.qualifies(labScopeQualified: p.labScopeActive,
                                               ladderMode: p.ladderMode, rung: p.rung,
                                               routeId: routeId)
                    && self.currentNavRoute() == routeId
            },
            onMutation: { [weak self] in self?.bridge.noteActivity(.visibleMutation) })
        if outcome == .observing {
            TraceScoutRnModule.rhTrace("vm_observing route=\(routeId)")
        }
    }

    func invalidateWebScrollObservation(_ reason: String) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in
                self?.invalidateWebScrollObservation(reason)
            }
            return
        }
        WebScrollObserver.shared.invalidate(reason: reason)
    }

    /// G2-CALLSITE — THE PRODUCTION CALL SITE OF THE WEBVIEW BIND.
    ///
    /// Bind this session's correlation from the host's declared allowlist,
    /// retire a stale admission, and install the bridge on every real web view
    /// under the route's OWNED capture root.
    ///
    /// IT ASKS THE REGISTRY THE SAME QUESTION THE WALKER AND THE SCROLL
    /// OBSERVATION ASK, AND REFUSES ON THE SAME ANSWERS. An unintegrated host,
    /// an unowned route, a superseded binding or a handle that no longer names
    /// a live view all stop here — so a bridge can never be installed into a
    /// view this route does not own, and a correlation can never be established
    /// through somebody else's web view.
    ///
    /// IT IS NOT GATED ON THE LAB FIDELITY SCOPE, AND THAT IS DELIBERATE.
    /// `WebScrollGate` exists because observing scroll geometry of an ALLOWED,
    /// drawn web view is a fidelity question. This is not: the correlation
    /// carries no page content at all — a natively minted opaque id echoed back
    /// and the the specification honest-absence rule applies to every session, not only to
    /// an internal one. The gates that DO apply are the host's declared
    /// allowlist, the capability preflight and the shared decision, all of them
    /// inside the bind and `RnWebViewSegment.decide`.
    func ensureWebViewCorrelationIfPossible(routeId: String?) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in
                self?.ensureWebViewCorrelationIfPossible(routeId: routeId)
            }
            return
        }
        guard let routeId = routeId else { return }
        // THE SESSION LIFECYCLE INPUT. No session, no correlation: a segment id
        // has nothing to correlate to, and minting one would be the "S alone"
        // the Model B contract forbids.
        guard let sid = RnTransport.shared.currentIdentity()?.sessionId else { return }
        guard bridge.isIntegrated(), let b = bridge.boundRoot(routeId),
              bridge.bindingIsCurrent(routeId, b),
              let root = TraceScoutRnModule.viewForReactTag(b.rootHandle) else { return }
        let outcomes = RnWebViewSessionBinding.shared.ensure(root: root, nativeSessionId: sid)
        guard !outcomes.isEmpty else { return }
        let rendered = outcomes.map { o -> String in
            switch o {
            case .installed: return "installed"
            case .alreadyInstalled: return "already"
            case .refused(let why): return "refused:" + why
            }
        }.joined(separator: ",")
        TraceScoutRnModule.rhTrace(
            "wv_ensure route=\(routeId) candidates=\(outcomes.count) outcomes=\(rendered) "
            + "segment=\(RnWebViewSessionBinding.shared.mintedSegmentId(nativeSessionId: sid) != nil) "
            + "correlated=\(RnWebViewSessionBinding.shared.carrier(nativeSessionId: sid) != nil)")
    }

    /// Same rule: detaching a recognizer touches the view hierarchy.
    func removeInteractionObserver() {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.removeInteractionObserver() }
            return
        }
        _ = InteractionSurfaceBinding.shared.unbind()
        // A detach ends every in-flight touch: the next `begin` must not be
        // able to inherit a previous gesture's remembered start.
        bridge.resetGestureLifecycle()
        wiring = nil
    }

    /// The classification layer. The recognizer supplied mechanics only; the
    /// screen identity comes from the SDK's own route, never from the view.
    private func handle(_ o: PassiveInteractionRecognizer.Observation, in surface: UIView) {
        let target = o.targetView
        let native = InteractionProducer.NativeTouchObservation(
            monotonicNanos: o.monotonicNanos,
            wallMillis: Int64(Date().timeIntervalSince1970 * 1000),
            x: o.x, y: o.y,
            viewportWidth: o.viewportWidth, viewportHeight: o.viewportHeight,
            screenId: bridge.currentRouteId(),
            phase: o.phase,
            pointerCount: o.pointerCount,
            gestureId: o.gestureId,
            targetClass: TouchTargetResolver.classify(target),
            positionTreatment: TouchTargetResolver.positionTreatment(target),
            fromContentWindow: TouchTargetResolver.isContentWindow(target?.window ?? surface.window),
            screenNeverRecord: bridge.currentRouteId().map { bridge.isNeverRecord($0) } ?? false,
            textEntryActive: TouchTargetResolver.textEntryActive(in: surface.window ?? surface),
            basisIsCapturedWindow: RnSurfaceLocator.basisIsCapturedWindow(o.basisWindow),
            movedBeyondSlop: o.movedBeyondSlop,
            // an earlier change — decided by the SAME hit-tested view the classification
            // above reads; gates only whether the WebView carrier is asked.
            insideWebContent: TouchTargetResolver.insideWebContent(target))

        // ONE call. It signals the existing cadence driver and, only if consent
        // and policy both allow, produces the durable row. No timer is started
        // here and no second driver exists.
        bridge.recordInteraction(native,
                                 consentEnabled: SecureConfig.interactionCaptureEnabled())
    }
#endif
}

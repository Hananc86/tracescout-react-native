import Foundation
#if canImport(UIKit)
import UIKit

/// Walks the LIVE UIKit view tree into the RawNode privacy model.
///
/// This file is the only place the SDK touches views, and it runs ONLY on the
/// main thread and ONLY while the app is active — a background walk is refused,
/// not deferred (contract: no traversal or capture in background).
///
/// What it reads per view: class name, frame, hidden/alpha, and the PLATFORM
/// privacy facts (secure text entry, web content, camera preview layers). What
/// it NEVER reads: text content, accessibility values, or anything typed into
/// an input. Under the strict default every text-bearing node is MASKED with a
/// length only, and the length is read from a COUNT, never the string bytes
/// being copied anywhere.
public enum HierarchySnapshotter {

    public struct Capture {
        public let result: HierarchyResult
        public let durationMs: Int64
        /// Phase 2: the frame rasterized IN THE SAME main-thread pass as the
        /// walk, from the SAME result — so the redaction geometry describes
        /// exactly the pixels drawn. Two separate hops let the tree change in
        /// between (a list appended rows with no rect → readable text).
        public let frame: FrameRasterizer.Frame?
        public let frameRefusal: String?
        /// The PHASE-A structural digest — the same value phase C compares
        /// against, surfaced so the cadence budget can be keyed on the subject
        /// instead of the session clock (`FrameCadence`). `nil` for a walk-only
        /// capture, where no coherence transaction runs at all.
        ///
        /// Surfaced, never recomputed. A second digest taken in the bridge
        /// would be free to disagree with the one that actually gates the frame.
        /// It is content-free by construction (see `CaptureCoherence`), so it
        /// carries no user content off this layer.
        public let coherenceFingerprint: String?
        /// PASSIVE ACCOUNTING. The walk's duration in nanoseconds (the same
        /// interval `durationMs` reports, unrounded) and, when a meter was
        /// supplied, the raster-path phases measured around the same calls
        /// that produced `frame`. `nil` meter = nothing measured, nothing
        /// changed.
        public let walkNanos: Int64
        public let phases: CaptureAccounting.Phases?
        public init(result: HierarchyResult, durationMs: Int64,
                    frame: FrameRasterizer.Frame?, frameRefusal: String?,
                    coherenceFingerprint: String?,
                    walkNanos: Int64 = 0, phases: CaptureAccounting.Phases? = nil) {
            self.result = result; self.durationMs = durationMs
            self.frame = frame; self.frameRefusal = frameRefusal
            self.coherenceFingerprint = coherenceFingerprint
            self.walkNanos = walkNanos; self.phases = phases
        }
    }

    /// Capture the key window's tree for `routeId`. Returns nil when capture
    /// is not permitted (background, no window) — nil means "no hierarchy
    /// event", never an empty fabricated one.
    /// `rung` DEFAULTS to `.h_allMasked`, which is byte-for-byte the
    /// certified behaviour. Every other rung is an exploratory Lab posture and
    /// has to be asked for explicitly at the call site — there is no ambient
    /// setting that could drift a production build into one.
    /// ONE bounded main-thread capture transaction — PHASE A prepare, PHASE B
    /// render, PHASE C verify. See FRAME-COHERENCE in `CaptureCoherence`.
    ///
    /// `currentRoute` is the route the SDK believes it is on RIGHT NOW, read
    /// again in phase C. It exists so "JS navigated away while we were
    /// rasterizing" is a signal the native side can actually observe — a
    /// cross-thread event that a second walk alone would only ever report as a
    /// generic geometry change. Absent (nil) it degrades to the captured route,
    /// which cannot produce a false positive.
    /// `ladderMode` is the UAT-only native switch. It DEFAULTS to false, so a
    /// caller that does not opt in gets the production input floor unchanged —
    /// the exemption cannot be reached by forgetting to pass something.
    /// `labScopeQualified` is the resolver's OWN answer that project AND
    /// bundle are the exact synthetic fixture. It DEFAULTS to false, so a
    /// caller that does not opt in cannot reach the Lab web-content exemption
    /// at all — the relaxation is unreachable by forgetting an argument, which
    /// is the only defaulting direction a privacy boundary may have.
    public static func capture(routeId: String, wantFrame: Bool = false,
                               rung: FidelityRung = .h_allMasked,
                               ladderMode: Bool = false,
                               labScopeQualified: Bool = false,
                               currentRoute: (() -> String?)? = nil,
                               webView: RnEnvelope.WebViewRef? = nil) -> Capture? {
        observe(routeId: routeId, wantFrame: wantFrame, rung: rung,
                ladderMode: ladderMode, labScopeQualified: labScopeQualified,
                currentRoute: currentRoute, webView: webView).capture
    }

    /// The complete outcome of ONE capture attempt: the capture if it happened,
    /// and the CLOSED capture-unavailable reason ONLY when one of the two
    /// protocol conditions was actually OBSERVED.
    ///
    /// THIS EXISTS BECAUSE `nil` MEANT FIVE DIFFERENT THINGS. Background, no
    /// key window, a window with no scene, a zero-sized window and a hidden
    /// root all folded into one `nil`, and only three of them are protocol
    /// conditions. Reporting a reason for the others would be inventing a
    /// cause; reporting none for all five loses the ones we can establish.
    public struct Outcome {
        public let capture: Capture?
        public let captureUnavailableReason: String?
        public init(capture: Capture? = nil, captureUnavailableReason: String? = nil) {
            self.capture = capture
            self.captureUnavailableReason = captureUnavailableReason
        }
    }

    // ── ROUTE OWNERSHIP ─────────────────────────────────────────────────────
    //
    // WHICH VIEWS ARE THIS ROUTE'S? The walker could not ask before, and
    // that is the defect it shipped: it was handed a routeId, walked the KEY
    // WINDOW, and `CanonicalHierarchy.build(routeId:root:)` stamped the
    // requested route's label onto whatever that window held. Measured on the
    // Android twin: a route that was NEVER RENDERED produced 70 nodes of
    // another route's content, emitted under its label.
    //
    // READINESS IS NOT OWNERSHIP. A host declaring itself ready establishes
    // that it is ready. Only a capture root, verified at capture time, can
    // establish which views are its.

    public enum CaptureRootBinding {
        /// No route-host registry exists — the app has not integrated.
        /// LEGACY, DELIBERATELY UNCHANGED AND DELIBERATELY SEPARATE. The
        /// key-window walk is what unintegrated apps have always got; silently
        /// changing it would break every app that never opted in.
        case unintegrated
        /// Integrated, and the registry named a capture root for this route.
        case bound(RouteHostRegistry.BoundRoot)
        /// Integrated, and ownership CANNOT be established — no host, no
        /// declared capture root, or two hosts claiming one route.
        case unowned
    }

    /// Platform resolution of an opaque root handle. On React Native this is a
    /// react tag resolved through the UIManager; nil means the handle no longer
    /// names a live view — a replaced or unmounted host.
    public typealias RootResolver = (Int) -> UIView?

    /// Why a bound capture root was refused. Observability only — none of these
    /// is a protocol reason and none reaches the wire.
    public enum OwnershipRefusal: String {
        case none, unresolved, detached, foreignWindow, backgroundScene
        case overlayWindow, bindingChanged, ambiguousOrAbsent
    }

    public private(set) static var lastOwnershipRefusal: OwnershipRefusal = .none

    public static func observe(routeId: String, wantFrame: Bool = false,
                               rung: FidelityRung = .h_allMasked,
                               ladderMode: Bool = false,
                               labScopeQualified: Bool = false,
                               currentRoute: (() -> String?)? = nil,
                               binding: CaptureRootBinding = .unintegrated,
                               resolver: RootResolver? = nil,
                               bindingStillCurrent: @escaping () -> Bool = { true },
                               /// The envelope's OWN WebView carrier, or nil — threaded to
                               /// `CanonicalHierarchy.build` so the node-level segment link and
                               /// the envelope-level one come from ONE read of ONE admitted
                               /// segment.
                               ///
                               /// LAST AND DEFAULTED, and there is no production caller yet:
                               /// the correlation bind has none on either platform, so every
                               /// shipping path passes nil and emits a tree with no node link —
                               /// byte-identical to before.
                               webView: RnEnvelope.WebViewRef? = nil,
                               meter: CaptureAccounting.PhaseMeter? = nil) -> Outcome {
        // OBSERVATION AND TRAVERSAL ARE ONE SYNCHRONOUS MAIN-THREAD BLOCK,
        // AND THAT IS THE EXACT SCOPE OF THE CLAIM. UIKit owns these facts on
        // the main thread, so an off-main read is a value that was true at some
        // unknown moment, not an observation. `DispatchQueue.main.sync` puts the
        // reads and the walk in one block, so no other main-thread work
        // interleaves within it. It does NOT mean the surface "cannot change" in
        // general — the platform may lay out again the moment the block returns.
        // Whatever the walk produced IS the capture; no reason is attached
        // after the fact.
        // `meter` is forwarded at BOTH hops. An unforwarded meter would
        // report a zero-length walk as a measurement.
        if Thread.isMainThread {
            return observeOnMain(routeId: routeId, wantFrame: wantFrame, rung: rung,
                                 ladderMode: ladderMode, labScopeQualified: labScopeQualified,
                                 currentRoute: currentRoute,
                                 binding: binding, resolver: resolver,
                                 bindingStillCurrent: bindingStillCurrent,
                                 webView: webView, meter: meter)
        }
        var out = Outcome()
        DispatchQueue.main.sync {
            out = observeOnMain(routeId: routeId, wantFrame: wantFrame, rung: rung,
                                ladderMode: ladderMode, labScopeQualified: labScopeQualified,
                                currentRoute: currentRoute,
                                binding: binding, resolver: resolver,
                                bindingStillCurrent: bindingStillCurrent,
                                webView: webView, meter: meter)
        }
        return out
    }

    /// The key window, or nil. One resolver, used by both phases — two
    /// different spellings of "which window" is how the subjects diverge.
    private static func keyWindow() -> UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap({ $0.windows })
            .first(where: { $0.isKeyWindow }) ?? UIApplication.shared.windows.first
    }

    /// Per-process, hashed. Never serialised; it exists only so "a different
    /// window" is distinguishable from "the same window, changed".
    private static func windowToken(_ w: UIWindow) -> String {
        String(format: "%016lx", UInt(bitPattern: ObjectIdentifier(w).hashValue))
    }

    private static func observeOnMain(routeId: String, wantFrame: Bool = false,
                                      rung: FidelityRung = .h_allMasked,
                                      ladderMode: Bool = false,
                                      labScopeQualified: Bool = false,
                                      currentRoute: (() -> String?)? = nil,
                                      binding: CaptureRootBinding = .unintegrated,
                                      resolver: RootResolver? = nil,
                                      bindingStillCurrent: @escaping () -> Bool = { true },
                                      webView: RnEnvelope.WebViewRef? = nil,
                                      meter: CaptureAccounting.PhaseMeter? = nil) -> Outcome {
        // ===== PHASE A — prepare =====
        // Foreground only. `.inactive` (transitioning) is refused too: the
        // contract says no capture in background, and the transition window is
        // where snapshot APIs start returning stale or composited-over content.
        // THIS PLATFORM CANNOT ESTABLISH EITHER PROTOCOL CONDITION, SO IT
        // EMITS NO REASON — deliberately.
        //
        // `keyWindow == nil` is NOT sufficient evidence of route-surface
        // detachment: an attached route can live in a non-key window or in
        // another scene entirely, so resolving "the key window" resolves
        // neither the route's host nor its absence. And there is no attach
        // budget and no host readiness declaration to consult. Asked with
        // `.unknown`, the shared rule refuses — and refusing to NAME a cause is
        // not refusing to capture.
        //
        // Background and FLAG_SECURE-equivalent refusals stay exactly where
        // they were, on their own axis, carrying no reason.
        //
        // When a route-host registry with a real budget and a real readiness
        // signal exists, THIS is the line that changes; the shared fixture
        // already describes exactly what it must supply.
        let decision = CaptureOutcome.decide(neverRecord: false, host: .unknown)
        if decision.decision == .unavailable {
            return Outcome(captureUnavailableReason: decision.captureUnavailableReason)
        }

        // Foreground only, and no window means no capture root — both unchanged
        // and both reasonless.
        guard UIApplication.shared.applicationState == .active else { return Outcome() }

        // OWNERSHIP IS DECIDED HERE, BEFORE ANYTHING IS READ, AND THERE IS
        // NO FALL-BACK TO A WINDOW-WIDE CAPTURE. Unknown, ambiguous, detached
        // and stale bindings all refuse.
        // THE KEY-WINDOW LOOKUP BELONGS TO THE LEGACY BRANCH ONLY. It used to run
        // before this switch for EVERY binding, so a bound capture carried an
        // unrelated prerequisite (including the UIApplication.shared.windows.first
        // fallback). A bound route resolves entirely from its own view and window.
        let captureRoot: UIView
        let rootWindow: UIWindow
        switch binding {
        case .unintegrated:
            guard let window = keyWindow() else { return Outcome() }   // legacy only
            lastOwnershipRefusal = .none
            captureRoot = window
            rootWindow = window
        case .unowned:
            // REFUSE TO EMIT, NOT REFUSE TO REPORT. No hierarchy is
            // produced, so no foreign content can be labelled with this route.
            // The screen still gets its metadata-only record and the registry
            // still decides the outcome axis on its own facts — an ownership
            // failure is NOT one of the two protocol conditions and must never
            // be dressed up as one.
            lastOwnershipRefusal = .ambiguousOrAbsent
            return Outcome()
        case .bound(let b):
            guard let v = resolver?(b.rootHandle) else {
                // The handle no longer names a live view: the host was replaced
                // or unmounted between registration and capture.
                lastOwnershipRefusal = .unresolved
                return Outcome()
            }
            // iOS ATTACHMENT IS `view.window`, NOT A BOOLEAN. A view with no
            // window is in no hierarchy at all.
            guard let vw = v.window else {
                lastOwnershipRefusal = .detached
                return Outcome()
            }
            // WINDOW AND SCENE ASSOCIATION IS AN iOS-SPECIFIC CHECK AND
            // ANDROID'S DOES NOT TRANSLATE. Android has one window token per
            // activity; iOS has multiple windows per scene and multiple scenes
            // per process, so a view can be perfectly attached and belong to a
            // window this capture has nothing to do with — a different scene,
            // or a system/overlay window layered above the app's content.
            //
            // AND IT IS DELIBERATELY NOT "must be the key window". The
            // protocol note on this platform is explicit that an attached route
            // can exist in a NON-KEY window; requiring key-ness would refuse
            // legitimate captures. What is required is that the root lives in a
            // CONTENT window of a FOREGROUND-ACTIVE scene.
            guard TouchTargetResolver.isContentWindow(vw) else {
                lastOwnershipRefusal = .overlayWindow
                return Outcome()
            }
            guard let scene = vw.windowScene, scene.activationState == .foregroundActive else {
                lastOwnershipRefusal = .backgroundScene
                return Outcome()
            }
            lastOwnershipRefusal = .none
            captureRoot = v
            rootWindow = vw          // the view's OWN window — never the key window
        }
        // The window the capture root actually lives in — not necessarily the
        // key window, and every subsequent read must use this one. Two
        // spellings of "which window" is how the subjects diverge.

        let started = DispatchTime.now()
        // Settle the layout we are about to WALK so it is the layout that will
        // be RENDERED. This is a layout pass on the main thread, not a render;
        // it is the cheap half of making the two phases agree, and without it
        // phase C would refuse constantly for differences we caused ourselves.
        // Settle the layout we are about to WALK, in the capture root's own
        // window.
        rootWindow.layoutIfNeeded()
        // A HIDDEN OR FULLY TRANSPARENT ROOT IS NOT ONE OF THE TWO
        // CONDITIONS. The window is attached and has dimensions; it is simply
        // not drawing. No reason.
        guard let raw = rawNode(for: captureRoot, ordinal: 0, in: rootWindow, rung: rung, inherited: nil, routeId: routeId, ladderMode: ladderMode, labScopeQualified: labScopeQualified) else { return Outcome() }
        // VALIDATE AFTER TRAVERSAL AS WELL AS BEFORE IT. A host can be
        // replaced or detached while the tree is being read; content gathered
        // under a binding that no longer exists is not this route's content,
        // and emitting it would be the same misattribution through a narrower
        // window.
        if case .bound = binding {
            guard bindingStillCurrent(), captureRoot.window === rootWindow else {
                lastOwnershipRefusal = .bindingChanged
                return Outcome()
            }
        }
        let result = CanonicalHierarchy.build(routeId: routeId, root: raw, webView: webView)
        // The walk interval, ONCE, unrounded; `durationMs` is the same number
        // rounded down. The meter (if any) is told the same value.
        let walkNs = Int64(DispatchTime.now().uptimeNanoseconds &- started.uptimeNanoseconds)
        let ms = walkNs / 1_000_000
        meter?.note(.walk, nanos: walkNs)
        guard wantFrame else {
            return Outcome(capture: Capture(result: result, durationMs: ms, frame: nil,
                                            frameRefusal: nil, coherenceFingerprint: nil,
                                            walkNanos: walkNs, phases: meter?.snapshot()))
        }
        // THE SAME OWNERSHIP RULE APPLIES TO RASTER CAPTURE, AND A PICTURE IS
        // THE MORE LEGIBLE LEAK OF THE TWO — so the PIXELS come from the SAME
        // capture root the walk used, never from the window that contains it.
        // The CANVAS is still the window's size, because that is the coordinate
        // system the wire's node bounds are expressed in; see `FrameGeometry`.
        //
        // THIS REPLACES A BLANKET REFUSAL, AND THE REFUSAL WAS CORRECT WHILE
        // IT STOOD. `FrameRasterizer` used to photograph the app's WINDOW only,
        // so under a bound root it would have stored foreign content labelled
        // with this route — the node-level misattribution this binding exists to
        // prevent, in pixels. Refusing `capture_root_not_rasterizable` was the
        // honest answer to a rasterizer that could not be scoped.
        //
        // The cost of leaving it in place was total and was measured: RN iOS
        // 0.3.0 (31), production session ts_1789720550908_e525ff71daf2, declared
        // `semantic_with_frames` at session_start (so the frames opt-in WAS on)
        // and stored ZERO visual frames across six screens and 229 hierarchy
        // entries — every one of them `wireframe_only`, because every
        // route-owned screen is a bound root and every frame was refused here
        // before the rasterizer was ever called. A skeletal outline replay with
        // Frame and Hybrid greyed out is what that looks like to an operator.
        //
        // What had to exist before it could be lifted is a raster whose SUBJECT
        // is a view while its COORDINATE SYSTEM stays the window's
        // (`FrameRasterizer.capture(root:window:…)` + `FrameGeometry`): the
        // owned root's pixels drawn, clipped, at their window-space position on
        // a window-sized canvas whose remainder is an opaque neutral fill.
        //
        // AND THE OBVIOUS SCOPING — A ROOT-SIZED IMAGE — WOULD HAVE BEEN A
        // SECOND DEFECT, which is worth stating because it is what this code
        // did first. The wire carries window-space node bounds and the Viewer
        // sizes its surface from the frame and draws those bounds RAW; the
        // protocol has no root-offset field. A root-sized image therefore moves
        // every wireframe rectangle away from the pixels it describes — masks
        // included — while reporting success.
        //
        // Unintegrated capture is unaffected: there the window IS the declared
        // root, the placement is the identity, the unowned region is empty, and
        // the output is unchanged.
        let rasterRoot = captureRoot
        let subjectA = CaptureCoherence.Subject(routeId: routeId,
                                                windowToken: windowToken(rootWindow),
                                                rootW: Int(rasterRoot.bounds.width),
                                                rootH: Int(rasterRoot.bounds.height))
        let prepared = CaptureCoherence.Observation(
            subject: subjectA,
            fingerprint: CaptureCoherence.fingerprint(subject: subjectA,
                                                      hierarchy: result,
                                                      redaction: FramePolicy.redaction(result)))

        // ===== PHASE B (inside) then PHASE C (the closure) =====
        // The redacted raster exists as a local inside the rasterizer; this
        // closure runs after it and before the encoder. A non-nil return
        // discards it — no encode, no return, no store, and no frame budget
        // consumed, because the bridge only counts a frame it actually got.
        let verdict: () -> String? = {
            let verified: CaptureCoherence.Observation? = {
                guard UIApplication.shared.applicationState == .active else { return nil }
                // RE-RESOLVE THE SAME KIND OF SUBJECT THAT WAS PREPARED.
                // This closure used to re-resolve the KEY WINDOW unconditionally
                // which is not the bound root's subject, so comparing the two
                // would have answered a question nobody asked. The binding is
                // re-established here, which is also what makes the
                // stale-binding check run AFTER the raster as well as before it.
                let root2: UIView
                let win2: UIWindow
                switch binding {
                case .unintegrated:
                    guard let w2 = keyWindow() else { return nil }
                    root2 = w2; win2 = w2
                case .unowned:
                    // Unreachable — an unowned binding returns long before any
                    // raster. Refusing here rather than assuming keeps that an
                    // invariant instead of a comment.
                    return nil
                case .bound(let b):
                    guard bindingStillCurrent(),
                          let v2 = resolver?(b.rootHandle),
                          let w2 = v2.window,
                          TouchTargetResolver.isContentWindow(w2),
                          let sc2 = w2.windowScene, sc2.activationState == .foregroundActive
                    else { return nil }
                    root2 = v2; win2 = w2
                }
                // THE SUBJECT MUST BE THE SAME OBJECT, NOT AN EQUIVALENT ONE.
                // Everything below re-walks and re-fingerprints, and a
                // fingerprint is STRUCTURAL — a replacement view with the same
                // route, the same window, the same bounds and the same node
                // geometry produces an IDENTICAL digest and would be accepted.
                // That is precisely the stale-binding case this exists to
                // catch: the handle now resolves to a different instance and
                // the raster in hand is a picture of the object that used to be
                // there. Identity is the only thing a re-walk cannot fake.
                guard root2 === rasterRoot else { return nil }
                // The window must be the same one the canvas was sized from and
                // the placement computed against; and the root must still be IN
                // it. A root MOVED TO ANOTHER WINDOW between the draw and this
                // check is not the subject that was prepared, even though it is
                // the same object — the attachment predicate is what catches
                // that, and `win2 === rootWindow` catches the resolver handing
                // back a different window for the same view.
                //
                // THE SAME PREDICATE THE PRE-RASTER PATH USES, AND FIXING ONE
                // WITHOUT THE OTHER WOULD HAVE CHANGED NOTHING. This line used
                // to read `root2.window === rootWindow`, which under
                // `.unintegrated` — where `root2 = win2 = keyWindow` two dozen
                // lines above — evaluates `nil === keyWindow` and discards the
                // raster after it was drawn. So the legacy path was refused
                // TWICE for the same wrong reason, once before the draw and once
                // after it, and a corrected first guard alone would simply have
                // moved the refusal later.
                guard win2 === rootWindow,
                      FrameRasterizer.rootAttachmentRefusal(root2, rootWindow) == nil
                else { return nil }
                // AND THE MAPPING MUST STILL BE THE ONE THE PIXELS WERE
                // PLACED UNDER. A transform applied between the draw and the
                // encode moves the root's content while the redaction — which
                // is in window space and was computed before the draw — stays
                // exactly where it was. The SAME predicate the pre-raster path
                // used, so the two cannot drift.
                guard FrameRasterizer.untransformedChainRefusal(root2, win2) == nil else {
                    return nil
                }
                guard let raw2 = rawNode(for: root2, ordinal: 0, in: win2, rung: rung,
                                         inherited: nil, routeId: routeId, ladderMode: ladderMode,
                                         labScopeQualified: labScopeQualified)
                else { return nil }
                let route2 = currentRoute?() ?? routeId
                // THE CARRIER IS PASSED HERE TOO, ON PURPOSE AND NOT BECAUSE
                // THE FINGERPRINT NEEDS IT. `CaptureCoherence.fingerprint`
                // digests id|role|privacy|bounds plus the painted geometry and
                // does NOT read `webViewSegmentId`, so phase C would agree with
                // phase A either way TODAY. It is passed
                // so the two phases stay SYMMETRIC: if the fingerprint ever grew
                // to include the link, an asymmetric phase C would make every
                // correlated capture refuse `geometryChanged` — a coherence
                // refusal manufactured by the coherence check itself.
                let r2 = CanonicalHierarchy.build(routeId: route2, root: raw2, webView: webView)
                let s2 = CaptureCoherence.Subject(routeId: route2, windowToken: windowToken(win2),
                                                  rootW: Int(root2.bounds.width),
                                                  rootH: Int(root2.bounds.height))
                return CaptureCoherence.Observation(
                    subject: s2,
                    fingerprint: CaptureCoherence.fingerprint(subject: s2, hierarchy: r2,
                                                              redaction: FramePolicy.redaction(r2)))
            }()
            return CaptureCoherence.verify(prepared: prepared, verified: verified)?.rawValue
        }

        switch FrameRasterizer.capture(root: rasterRoot, window: rootWindow,
                                       hierarchy: result, verify: verdict, meter: meter) {
        case .ok(let f):
            return Outcome(capture: Capture(result: result, durationMs: ms, frame: f, frameRefusal: nil,
                                            coherenceFingerprint: prepared.fingerprint,
                                            walkNanos: walkNs, phases: meter?.snapshot()))
        case .refused(let why):
            // A REFUSED FRAME IS NOT AN UNAVAILABLE SCREEN. The hierarchy
            // was captured; only the raster was declined. The snapshot stays
            // wireframe_only and carries no reason.
            return Outcome(capture: Capture(result: result, durationMs: ms, frame: nil, frameRefusal: why,
                                            coherenceFingerprint: prepared.fingerprint,
                                            walkNanos: walkNs, phases: meter?.snapshot()))
        }
    }

    /// One view → one RawNode. Hidden or fully transparent subtrees are
    /// omitted entirely — they are not on screen, and serialising them would
    /// leak layout the user cannot see.
    /// `inherited` is the category declared by the nearest ancestor. A category
    /// declared on a container applies to its whole subtree unless a descendant
    /// declares its own — without that, marking an RN `<Text>` would leave its
    /// internal `RCTParagraphTextView` child masked and painted right over the
    /// content the declaration was meant to reveal.
    static func rawNode(for view: UIView, ordinal: Int, in window: UIWindow,
                        rung: FidelityRung = .h_allMasked,
                        inherited: SemanticCategory? = nil,
                        routeId: String = "",
                        ladderMode: Bool = false,
                        labScopeQualified: Bool = false) -> RawNode? {
        if view.isHidden || view.alpha < 0.01 { return nil }

        let frame = view.convert(view.bounds, to: window)
        let bounds = NodeBounds(x: Int(frame.origin.x), y: Int(frame.origin.y),
                                w: Int(frame.size.width), h: Int(frame.size.height))
        let type = componentType(of: view)

        // The DECLARED semantic category, own or inherited. `unknown` when the
        // app said nothing — which every rung below G masks anyway.
        //
        // RESOLVED BEFORE the platform facts, because the ONE controlled
        // relaxation below is keyed on an EXPLICIT declaration. An undeclared
        // field reads as `.unknown` and is never exempted, so the exemption
        // cannot spread by omission.
        let declared = SemanticPrivacy.parse(view.accessibilityIdentifier) ?? inherited
        let category = declared ?? .unknown

        // PLATFORM privacy facts — these can only tighten, never loosen, with
        // exactly ONE audited exception: `UatFidelityExemption` lifts the
        // ORDINARY-input floor for the explicitly tagged synthetic input on the
        // deterministic UAT screen while native ladder mode is on. Every other
        // branch here is untouched, and a production install cannot satisfy the
        // exemption's first condition at all.
        var native: Treatment? = nil
        var reason: BlockedReason? = nil
        let isTextInput = (view is UITextField) || (view is UITextView)
        let isSecureEntry = (view as? UITextField)?.isSecureTextEntry ?? false
        if isTextInput {
            // Secure entry is blocked outright — its subtree must never be
            // inspected — and the exemption is structurally unable to reach it.
            if let floor = UatFidelityExemption.inputFloor(
                isTextInput: true, isSecureTextEntry: isSecureEntry,
                ladderMode: ladderMode, routeId: routeId, category: declared) {
                native = floor.0; reason = floor.1
            }
        } else if isWebContent(view) {
            // THE DEFAULT IS STILL THE CERTIFIED BLOCK. `webContentFloor`
            // returns `.blocked/.thirdPartyWebView` for every caller that is
            // not the exact synthetic Lab fixture at the approved baseline on
            // the one bundled-document route; only that conjunction yields
            // `.allowed`, and the leaf rule below is what keeps "allowed" from
            // ever meaning "traverse the web view's own view tree".
            if let floor = UatFidelityExemption.webContentFloor(
                isWebContent: true, labScopeQualified: labScopeQualified,
                ladderMode: ladderMode, rung: rung, routeId: routeId) {
                native = floor.0; reason = floor.1
            }
        } else if isCameraPreview(view) {
            native = .blocked; reason = .cameraPreview
        }

        // Text length comes from a character COUNT. The string is never copied,
        // concatenated, logged or stored — under strict posture the only thing
        // that reaches the wire is this integer.
        // The rung decides only whether a RECTANGLE gets painted. It never
        // decides whether a string is read: the text-length read below is
        // unchanged and no branch here reaches for content.
        let js = SemanticPrivacy.treatment(rung, category)

        var textLength: Int? = nil
        if let label = view as? UILabel { textLength = label.text?.count }
        else if let tf = view as? UITextField { textLength = tf.text?.count }
        else if let tv = view as? UITextView { textLength = tv.text?.count }

        // Blocked: return the placeholder node WITHOUT visiting children —
        // never descend, never inspect.
        if native == .blocked {
            return RawNode(componentType: type, siblingOrdinal: ordinal,
                           jsTreatment: Treatment.strongest(js, .masked), nativeTreatment: .blocked,
                           nativeBlockedReason: reason,
                           textLength: nil, bounds: bounds, children: [],
                           // FROM THE SAME `isWebContent` CALL the floor was
                           // derived from, not a second question. A blocked web
                           // view and an allowed one are the same surface.
                           webContent: isWebContent(view))
        }

        // AN ALLOWED WEB VIEW IS AN OPAQUE LEAF, NOT AN OPEN SUBTREE.
        // Lifting the block changes ONE thing — the rasterizer stops painting a
        // redaction over its rectangle, so the document appears as pixels. It
        // must NOT also start walking WebKit's internal views: that would put
        // document-derived STRUCTURE on the wire, which is a different and
        // much larger claim than "the frame shows the page". The two facts are
        // separated here so neither can be granted by accident.
        if isWebContent(view) {
            return RawNode(componentType: type, siblingOrdinal: ordinal,
                           jsTreatment: js, nativeTreatment: native,
                           nativeBlockedReason: nil,
                           textLength: nil, bounds: bounds, children: [],
                           // THE CASE A BLOCKED-REASON MARKER MISSES. This
                           // node carries NO blockedReason by design, so until
                           // `webContent` existed nothing on the wire
                           // identified the one node the segment link belongs
                           // to under the Lab visibility exemption.
                           webContent: true)
        }

        var kids: [RawNode] = []
        var i = 0
        for sub in view.subviews {
            if let k = rawNode(for: sub, ordinal: i, in: window, rung: rung, inherited: declared, routeId: routeId, ladderMode: ladderMode, labScopeQualified: labScopeQualified) {
                kids.append(k); i += 1
            }
        }
        // The strict default is now expressed by the RUNG rather than by a
        // hardcoded `.masked`: at `.h_allMasked` — the default everywhere — this
        // resolves to exactly `.masked` for every category, so the certified
        // behaviour is unchanged. A Lab rung can resolve `.allowed`, and native
        // facts still tighten it below.
        return RawNode(componentType: type, siblingOrdinal: ordinal,
                       jsTreatment: js, nativeTreatment: native,
                       nativeBlockedReason: nil,
                       textLength: textLength, bounds: bounds, children: kids)
    }

    /// A bounded, class-derived role. Class names are code identifiers, not
    /// user data; RN's host classes (RCTView, RCTParagraphComponentView, …)
    /// give the renderer enough to draw meaningful wireframes.
    static func componentType(of view: UIView) -> String {
        String(String(describing: Swift.type(of: view)).prefix(32))
    }

    /// ONE HOME FOR "IS THIS WEB CONTENT", AND IT IS INTERNAL RATHER THAN
    /// PRIVATE SO THE ATTACH SITE CAN ASK THE SAME QUESTION. `RnWebViewAttachment`
    /// needs exactly this predicate; a second class-name list there would be the
    /// one fact with two homes, and the copy that drifts is always the one nobody
    /// is looking at.
    static func isWebContent(_ view: UIView) -> Bool {
        // Name-based on purpose: importing WebKit for a type check would make
        // WebKit a hard dependency of every host app.
        let n = String(describing: Swift.type(of: view))
        return n.contains("WKWebView") || n.contains("WKContentView")
    }

    private static func isCameraPreview(_ view: UIView) -> Bool {
        String(describing: Swift.type(of: view.layer)).contains("AVCaptureVideoPreviewLayer")
    }
}
#endif

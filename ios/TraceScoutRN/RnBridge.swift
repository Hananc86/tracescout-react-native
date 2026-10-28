import Foundation

/// The React-facing SEMANTIC layer, restated for iOS with the same vocabulary
/// the certified Android bridge exposes.
///
/// JS may say: a route was entered, a navigation happened, the app
/// backgrounded, this route must never be recorded. That is the whole
/// vocabulary. Producer identity, protocol version, session, project,
/// environment, sequence, both clocks and the posture are NATIVE and have no
/// parameter to set — the server's capability boundary only means something if
/// a producer cannot misreport which capture code produced its bytes, so the
/// guard is structural, not a review convention.
///
/// Note what has NO method here: producer family, SDK version, platform,
/// host platform, credentials, the queue, the parked store, diagnostics.
public final class RnBridge {

    public enum Outcome: String {
        case recorded = "RECORDED"
        case notConfigured = "NOT_CONFIGURED"
        case noSession = "NO_SESSION"
        case invalidInput = "INVALID_INPUT"
        /// The input was VALID and the system was healthy -- the interaction
        /// policy or the app's consent declined to store it. Reporting that as
        /// INVALID_INPUT would blame the caller for a deliberate refusal, and
        /// reporting it as RECORDED would claim a row that does not exist.
        /// Native-only today: no JS method returns it.
        case suppressed = "SUPPRESSED"
        /// The capability the caller asked for is NOT INTEGRATED in this
        /// application.
        ///
        /// DELIBERATELY ITS OWN CASE. Reusing `notConfigured` (the SDK has no
        /// persisted configuration) or `suppressed` (policy declined to store a
        /// valid input) would make distinct situations indistinguishable to a
        /// caller trying to work out why nothing happened. Matches Kotlin's
        /// `NOT_INTEGRATED` exactly, so the two platforms answer alike.
        case notIntegrated = "NOT_INTEGRATED"
    }

    public struct Result {
        public let outcome: Outcome
        public let sequence: Int64?
        public init(_ outcome: Outcome, sequence: Int64? = nil) {
            self.outcome = outcome; self.sequence = sequence
        }
    }

    public protocol DurableSink {
        /// Takes a BUILDER: the sequence is assigned natively and stamped into
        /// the bytes the builder returns, assignment and append in ONE critical
        /// section. Two unreconciled sources for one sequence produced a batch
        /// the server permanently refuses (`batch_sequence_not_contiguous`).
        ///
        /// Returns NIL when the record did not reach the durable queue.
        func enqueue(_ build: (Int64) -> Data) -> Int64?
        func flushHint(_ reason: String)
        func stateName() -> String
        /// The durable generation a record is being written under. A reset
        /// bumps it, so a publication racing a reset can be refused instead of
        /// resurrecting a session whose queue no longer exists.
        func currentGeneration() -> UInt64
    }

    public protocol NativeClock {
        func wallMillis() -> Int64
        func monotonicNanos() -> Int64
    }

    public protocol EventIdSource {
        func newEventId() -> String
    }

    private let identity: () -> RnEnvelope.Identity?
    /// Mints a CANDIDATE identity. It is not usable until `publish` accepts it.
    private let mintCandidate: (String?) -> RnEnvelope.Identity?
    /// Publishes a candidate as active. Returns false if the generation moved
    /// (a reset happened between the opener append and this call).
    private let publish: (RnEnvelope.Identity, UInt64) -> Bool
    private let configured: () -> Bool
    private let sink: DurableSink
    private let clock: NativeClock
    private let ids: EventIdSource
    /// NATIVE hierarchy capture. Presence of this closure IS the enablement:
    /// no provider, no capture, no wire change — which is what keeps hierarchy
    /// capture out of JS entirely. The provider returns an already-resolved
    /// `HierarchyResult` (privacy applied, count derived, budgets enforced) or
    /// nil when capture is not permitted (background, no window).
    /// THE ROUTE-HOST REGISTRY. Absent = today's behaviour exactly: no host can
    /// be registered, every attempt is `.unknown`, and no reason is ever
    /// emitted. An unintegrated application is unaffected because there is
    /// nothing to ask — not because a flag happens to be off.
    private let routeHosts: RouteHostRegistry?
    /// THE DEADLINE OWNER. Without it the attach budget would only be discovered
    /// on the next navigation or lifecycle call — a wait bounded by user
    /// behaviour rather than by the budget.
    private let attachDeadline: AttachDeadline?
    /// PASSED, NOT GUESSED: a deadline armed for a different duration than the
    /// registry measures would fire early or late, and the two would disagree
    /// about what "the budget" means.
    private let attachBudgetMs: Int64
    private let hierarchy: ((String) -> HierarchyOutcome)?
    private let trace: ((String) -> Void)?
    /// Phase 2 NATIVE frame capture. Presence enables the CAPABILITY; the
    /// opt-in (persisted native config) and platform facts (foreground) are
    /// enforced inside the provider/rasterizer. The bridge owns the pure
    /// budgets via FramePolicy and never sees pixels. Absent = Phase 1 exactly.
    private let frame: ((String, HierarchyResult) -> FrameCapture?)?
    /// Why the LAST decided frame was not obtained. Supplied by the same native
    /// layer that produced the refusal; the bridge never guesses a reason.
    /// Absent = the bridge cannot tell a transient refusal from a permanent
    /// one and therefore does not retry, which is the safe default.
    private let frameRefusal: (() -> String?)?
    /// Build 26 — DURABLE capture observability. Absent means the
    /// certified Build-25 behaviour, byte for byte: this records and
    /// decides nothing, so a nil recorder removes evidence and never
    /// changes an outcome.
    private let captureLog: CaptureDecisionRecorder?
    /// Is the resolved Mobile Replay Lab maximum-visibility scope active?
    ///
    /// INJECTED, AND ABSENT MEANS FALSE. The subject-keyed cadence applies
    /// ONLY under the resolved Lab policy; every other caller keeps the
    /// certified build-18 session cadence byte for byte. Widening the customer
    /// cadence is a separate decision with its own evidence, not a side effect
    /// of this correction — and a closure the adapter forgets to pass must land
    /// on the certified behaviour, not the new one.
    private let labCadence: (() -> Bool)?
    /// Scroll geometry of the OWNED capture root for a route — the part of
    /// "did the picture change?" that the structural fingerprint cannot answer.
    ///
    /// INJECTED, AND ABSENT MEANS THE CERTIFIED BEHAVIOUR BYTE FOR BYTE.
    /// A nil provider yields a nil sample, a nil sample is never motion and
    /// never enters the subject key, so a host that does not supply one gets
    /// exactly the Build-26 cadence.
    ///
    /// IT MUST APPLY THE SAME ROUTE/BINDING CHECKS THE WALKER DOES. A sample
    /// taken from a view this route does not own would let another screen's
    /// scrolling hold this screen's capture window open. The provider returns
    /// nil rather than guessing a root.
    private let visibleState: ((String) -> VisibleState?)?
    /// The session's resolved capture-fidelity policy, or nil to emit nothing.
    ///
    /// INJECTED, AND ABSENT MEANS ABSENT. Ordinary customer envelope bytes
    /// must not change for internal Lab diagnostics, so the adapter supplies
    /// this ONLY for the internal fixture bundle; every other app emits no
    /// `fidelityPolicy` key at all.
    private let fidelityEvidence: (() -> OpenerSnapshot?)?
    /// MODEL B — the native↔WebView correlation for THIS session, asked once
    /// per emitted event.
    ///
    /// INJECTED, AND ABSENT MEANS ABSENT. A host that supplies no provider
    /// emits byte-identical envelopes to before this existed — both
    /// correlation keys omitted — so the wiring cannot change a customer's
    /// bytes by being present in the SDK.
    ///
    /// IT RETURNS AN ALREADY-TYPED `WebViewRef`, WHICH IS THE WHOLE POINT.
    /// The only way to obtain one is `WebViewRef.of(admittedSegment)`, so this
    /// closure cannot hand the envelope a segment id for a message the shared
    /// decision refused. A `String` parameter here would have re-opened exactly
    /// the door the typed carrier exists to close.
    ///
    /// IT IS NOT A CACHE AND MUST NOT BECOME ONE. The provider re-asks the
    /// session's holder on every event, because an admission can be RETIRED
    /// between two events (a navigation, a revoked policy) and a value captured
    /// once would keep riding events after the correlation was dropped.
    private let webViewRef: (() -> RnEnvelope.WebViewRef?)?
    /// Bounded, non-blocking deferred work. `nil` = no retries at all, which is
    /// what unit tests want: a scheduler in a test is a race in a test.
    /// NEVER a sleep and NEVER a loop — a capture must not be able to hang UI
    /// rendering while it waits for the UI to stop changing.
    private let schedule: ((Int64, @escaping () -> Void) -> Void)?
    /// ONE physical touch, one sticky classification. Owns the retroactive
    /// swipe BEGIN and the bounded move sampling; see
    /// `InteractionGestureLifecycle`.
    private var gestures = InteractionGestureLifecycle()
    private var lastFrameWallMs: Int64 = -1
    /// an earlier change — the LAST OBSERVED answer to "does route R's
    /// screen hold web content?", per route, for THIS session. Written only
    /// from a hierarchy capture's own `webViewSurfaceCount` (the ONE
    /// `HierarchySnapshotter.isWebContent` home), or `false` for a
    /// never-record route; cleared on session start. A route never observed
    /// has NO entry and is treated as not holding web content (fail closed).
    private var screenWebContent: [String: Bool] = [:]
    /// The subject of the last frame that actually entered the delivery path.
    ///
    /// THE DEFECT THIS FIELD EXISTS FOR. `lastFrameWallMs` alone is
    /// SESSION-scoped, so a 10 s interval measured from any screen suppressed
    /// every OTHER screen. Six screens in 13.35 s produced two frames, both on
    /// whichever screen happened to follow a 10 s boundary — the arithmetic left
    /// no residual, and nothing about the captured screen made it eligible.
    /// One key and one timestamp, never a map: see `FrameCadence.State`.
    private var lastAcceptedSubjectKey: String? = nil
    /// The activity window that decides whether the idle floor applies.
    ///
    /// THE DEFECT THIS FIELD EXISTS FOR. The frame decision lives inside
    /// `screenSnapshot`, whose only callers were the app's per-navigation
    /// `telemetry.screen(.)` calls — so `frames == navigations` and replay
    /// was an image slider. The 10 s floor was NOT the cause and never fired
    /// (six different routes = six different subject keys = `since == -1`).
    /// What was missing was anything to ASK for a frame between navigations.
    private var driverState = FrameCadenceDriver.State.empty
    /// The scroll geometry the PREVIOUS candidate observed, so the next one can
    /// answer "did it move?". One sample, never a history.
    private var lastVisibleSample: VisibleState? = nil
    /// Counted, so a report can say how many candidates were kept alive by
    /// observed motion rather than by a finger. Diagnostics only.
    private var visibleMutationSignals = 0
    /// ONE OWNED LOOP, NEVER A TIMER PER SIGNAL. Navigation, interaction and
    /// mutation all move the same timestamp; a second timer would be a second
    /// cadence nobody reconciles, and cancelling one would not stop the other.
    private var driverArmed = false
    /// The route the driver would capture. Set by the two calls that already
    /// know it; the driver never guesses a route.
    private var driverRouteId: String? = nil
    /// Every candidate's fate, so a hole in a replay has a reason.
    private var funnel = FrameFunnelCounters()
    /// Frames accepted since the CURRENT activity window opened. Reset when the
    /// cadence returns to idle — see `FrameCadenceDriver.effectiveFramesThisSession`.
    private var windowFrames = 0
    private var framesThisSession = 0
    private var neverRecordRoutes = Set<String>()
    /// Retry accounting is PER SCREEN ENTRY, not per session: a route that
    /// cannot settle spends its two attempts and stops, and entering it again
    /// starts fresh.
    private var frameRetryRoute: String? = nil
    private var frameRetryAttempts = 0
    /// PASSIVE ACCOUNTING — which candidate on the current route this is, so
    /// route-entry and steady-state observations are recorded apart. Updated
    /// exactly where `driverRouteId` is, read by nothing that decides.
    private var acctRoute = CaptureAccounting.RouteTracker()
    private let lock = NSLock()

    /// Coherence refusals are EXPECTED states while the UI is changing — a
    /// navigation transition is a normal thing for an app to be doing. They are
    /// the only refusals worth re-attempting: everything else is either a
    /// decision (opt-in, never-record, budgets) or a permanent property of the
    /// frame, and re-running the whole snapshot for those would only add
    /// snapshot events that can never succeed.
    static let RETRYABLE_FRAME_REFUSALS: Set<String> = [
        CaptureCoherence.Mismatch.routeChanged.rawValue,
        CaptureCoherence.Mismatch.windowChanged.rawValue,
        CaptureCoherence.Mismatch.dimensionsChanged.rawValue,
        CaptureCoherence.Mismatch.geometryChanged.rawValue,
        CaptureCoherence.Mismatch.subjectUnstable.rawValue,
        // TRANSITION-CLASS, LIKE THE FIVE ABOVE. The capture root was
        // re-parented or detached between the walk and the raster — a normal
        // thing for an app to be doing mid-navigation, and the next settled
        // opportunity can succeed.
        //
        // `capture_root_transformed` is deliberately NOT here: a scaled or
        // rotated host is a STABLE property of that host, so a retry would
        // spend the bounded budget on something that will not change and
        // silence the screen for the rest of the cadence window.
        "capture_root_detached",
        // Same class: a root laid out entirely off its own window is a state an
        // app passes THROUGH (a presentation animating in, a host measured
        // before its first layout lands), not a state it rests in.
        "capture_root_offscreen",
    ]
    static let MAX_FRAME_RETRIES = 2
    static let FRAME_RETRY_DELAYS_MS: [Int64] = [400, 1200]

    public init(identity: @escaping () -> RnEnvelope.Identity?,
                mintCandidate: @escaping (String?) -> RnEnvelope.Identity?,
                publish: @escaping (RnEnvelope.Identity, UInt64) -> Bool,
                configured: @escaping () -> Bool,
                sink: DurableSink,
                clock: NativeClock,
                ids: EventIdSource,
                routeHosts: RouteHostRegistry? = nil,
                attachDeadline: AttachDeadline? = nil,
                attachBudgetMs: Int64 = RouteHostRegistry.defaultAttachBudgetMs,
                hierarchy: ((String) -> HierarchyOutcome)? = nil,
                frame: ((String, HierarchyResult) -> FrameCapture?)? = nil,
                frameRefusal: (() -> String?)? = nil,
                captureLog: CaptureDecisionRecorder? = nil,
                labCadence: (() -> Bool)? = nil,
                visibleState: ((String) -> VisibleState?)? = nil,
                fidelityEvidence: (() -> OpenerSnapshot?)? = nil,
                schedule: ((Int64, @escaping () -> Void) -> Void)? = nil,
                  /// MODEL B. Last-but-one and DEFAULTED, like every other
                  /// provider: a caller that has not been taught about WebView
                  /// correlation must get the pre-existing answer — no segment
                  /// never a silently widened one.
                  webViewRef: (() -> RnEnvelope.WebViewRef?)? = nil,
                  /// Durable route-host transition trace: attempt id, route, timings,
                  /// typed reasons — never a credential, never captured content.
                  trace: ((String) -> Void)? = nil) {
        self.identity = identity; self.mintCandidate = mintCandidate
        self.publish = publish
        self.configured = configured; self.sink = sink
        self.clock = clock; self.ids = ids
        self.routeHosts = routeHosts
        self.attachDeadline = attachDeadline
        self.attachBudgetMs = attachBudgetMs
        self.hierarchy = hierarchy
        self.trace = trace
        self.frame = frame
        self.frameRefusal = frameRefusal
        self.captureLog = captureLog
        self.labCadence = labCadence
        self.visibleState = visibleState
        self.fidelityEvidence = fidelityEvidence
        self.schedule = schedule
        self.webViewRef = webViewRef
    }

    /// An already-redacted, encoded frame the native layer produced.
    public struct FrameCapture {
        public let visualFrameId: String
        public let contentHash: String
        public let width: Int
        public let height: Int
        public let byteSize: Int
        public let format: String
        public let quality: Int
        public let downscale: Double
        public init(visualFrameId: String, contentHash: String, width: Int, height: Int,
                    byteSize: Int, format: String, quality: Int, downscale: Double) {
            self.visualFrameId = visualFrameId; self.contentHash = contentHash
            self.width = width; self.height = height; self.byteSize = byteSize
            self.format = format; self.quality = quality; self.downscale = downscale
        }
    }

    /// What the native capture layer hands the bridge per snapshot.
    /// What the native capture layer reports per snapshot: the capture if it
    /// happened, and a reason ONLY for the two observed protocol conditions.
    ///
    /// BOTH NIL IS A REAL, HONEST ANSWER — "no capture, and we will not say
    /// why". That is the case for every refusal that is not one of the two
    /// conditions (background, a hidden root, a refused raster), and it is
    /// exactly the behaviour that existed before this axis.
    public struct HierarchyOutcome {
        public let capture: HierarchyCapture?
        public let captureUnavailableReason: String?
        public init(capture: HierarchyCapture? = nil, captureUnavailableReason: String? = nil) {
            // A reason alongside a successful capture is not interpretable: the
            // content is right there. A reason outside the closed vocabulary can
            // never reach the wire. Both are dropped at the boundary rather than
            // trapping — a producer must not be able to crash a host app over a
            // telemetry field, and a dropped reason degrades to the honest
            // metadata-only contract.
            self.capture = capture
            if let r = captureUnavailableReason,
               capture == nil, CaptureOutcome.reasons.contains(r) {
                self.captureUnavailableReason = r
            } else {
                self.captureUnavailableReason = nil
            }
        }
    }

    public struct HierarchyCapture {
        public let result: HierarchyResult
        public let durationMs: Int64
        /// The capture transaction's own PHASE-A structural digest, surfaced so
        /// the cadence budget can be keyed on the SUBJECT rather than on the
        /// session clock. `nil` when no coherence transaction ran (frames not
        /// opted in) — and `nil` is never treated as a new subject, so a missing
        /// digest can only ever be more conservative. See `FrameCadence`.
        public let coherenceFingerprint: String?
        /// PASSIVE ACCOUNTING, carried from the capture layer: the walk's
        /// unrounded duration, the raster-path phases (nil when no meter ran)
        /// and the bytes the rasterizer ENCODED for this capture — counted at
        /// capture, before any admission, so a produced-but-refused frame is
        /// still visible as produced. Nothing in the bridge decides on them.
        public let walkNanos: Int64
        public let phases: CaptureAccounting.Phases?
        public let producedFrameBytes: Int?
        public init(result: HierarchyResult, durationMs: Int64,
                    coherenceFingerprint: String? = nil,
                    walkNanos: Int64 = 0, phases: CaptureAccounting.Phases? = nil,
                    producedFrameBytes: Int? = nil) {
            self.result = result; self.durationMs = durationMs
            self.coherenceFingerprint = coherenceFingerprint
            self.walkNanos = walkNanos; self.phases = phases
            self.producedFrameBytes = producedFrameBytes
        }
    }

    static let NAV_TRANSITIONS: Set<String> = ["push", "pop", "replace", "reset", "tab", "modal", "initial"]
    static let LIFECYCLE_TRANSITIONS: Set<String> = ["foreground", "background", "active", "inactive"]

    /// Route identifiers only — never a resolved URL, never route parameters.
    /// A deep link's parameters are exactly where customer data (an order id,
    /// an email, a token) shows up, and a navigation event is not a place
    /// anyone inspects for a privacy leak.
    static func isSafeId(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 64 &&
            s.range(of: "^[A-Za-z0-9_.-]+$", options: .regularExpression) != nil
    }

    static func isSafeLabel(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 64 &&
            s.range(of: "^[A-Za-z0-9 _.-]+$", options: .regularExpression) != nil
    }

    // ------------------------------------------------------------ policy

    /// Declaring never-record is a POLICY, not an event: it has no wire effect
    /// of its own. Withdrawing is deliberately possible — a route can stop
    /// being sensitive — but it only affects captures made AFTER the withdrawal.
    @discardableResult
    public func declareNeverRecord(routeId: String, never: Bool) -> Outcome {
        guard RnBridge.isSafeId(routeId) else { return .invalidInput }
        lock.lock(); defer { lock.unlock() }
        if never { neverRecordRoutes.insert(routeId) } else { neverRecordRoutes.remove(routeId) }
        return .recorded
    }

    public func isNeverRecord(_ routeId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return neverRecordRoutes.contains(routeId)
    }

    // ---------------------------------------------------------- emission

    /// The first event of a session, and the SOLE owner of session creation —
    /// one act, so a session without a `mobile_session_start` cannot exist.
    ///
    /// Ordering is the contract, in this exact sequence:
    /// 1. mint a CANDIDATE identity (not usable, not observable),
    /// 2. build the canonical opener and DURABLY append it,
    /// 3. only on a successful append, publish the candidate as active.
    /// A failed append leaves NO active session, so a later navigation or
    /// lifecycle call gets a typed NO_SESSION and writes nothing — rather than
    /// enqueuing under an identity the queue has no beginning for.
    public func startSession(initialRouteId: String?) -> Result {
        if let r = initialRouteId, !RnBridge.isSafeId(r) { return Result(.invalidInput) }
        guard let id = mintCandidate(initialRouteId) else { return Result(.notConfigured) }
        // A new session invalidates hosts AND attempts from the previous one.
        routeHosts?.sessionChanged(id.sessionId)
        routeHosts?.routeChanged(initialRouteId)
        lock.lock(); pendingOutcomes.removeAll(); lock.unlock()
        attachDeadline?.cancel()
        lock.lock(); lastFrameWallMs = -1; lastAcceptedSubjectKey = nil
        framesThisSession = 0; screenWebContent.removeAll(); lock.unlock()
        let generation = sink.currentGeneration()
        let r = emitWith(id, "mobile_session_start", neverRecord: false) {
            var extra: [(String, RnEnvelope.Value)] = [
                // Schema-REQUIRED. "manual" because the public semantic
                // operation is an explicit startSession call and this bridge
                // has no trustworthy native lifecycle signal to claim
                // cold/warm/hot — never fabricate precision the producer does
                // not actually know.
                //
                // captureMode IS NO LONGER HARDCODED. The protocol defines it
                // as "declares up front what visual fidelity this session will
                // carry, so the renderer never guesses", and `metadata_only`
                // was stated on every opener — including Lab sessions that go
                // on to carry frames within the second. It now comes from the
                // resolved opener; absent an opener it stays metadata_only,
                // which is the truthful floor for a session that declares
                // nothing.
                ("captureMode", .string(self.fidelityEvidence?()?.captureMode ?? "metadata_only")),
                ("startReason", .string("manual")),
            ]
            if let r = initialRouteId {
                extra.append(("screen", .screen(RnEnvelope.ScreenRef(screenId: r))))
            }
            // SESSION POLICY, ON THE SESSION OPENER ONLY. This is one fact
            // about the whole session; repeating it on every hierarchy, frame,
            // navigation, lifecycle and network event would pay bytes forever
            // for a value that cannot change within the session.
            // ONE SNAPSHOT, SERIALIZED AS IT IS. Both the artifact identity
            // and the resolved activation come from the SAME opener value, so
            // the session cannot report a policy resolved at one instant beside
            // an identity read at another.
            if let snap = self.fidelityEvidence?() {
                extra.append(("fidelityPolicy", .fidelityPolicy(snap.fidelity)))
                let id = snap.identity
                if let v = id.appVersion { extra.append(("appVersion", .string(v))) }
                // `buildNumber` IS THE CANONICAL CROSS-PLATFORM WIRE NAME.
                // The protocol declares it, Android already emits it, and the
                // server's column and read-model property are the ones named
                // `app_build_number` / `appBuildNumber`. Emitting a second
                // spelling from iOS would have forked one fact across two
                // producers for the convenience of matching a downstream name —
                // exactly the divergence the two latent mismatches came from.
                // ONE key on the wire; the server maps it.
                if let b = id.appBuildNumber { extra.append(("buildNumber", .string(b))) }
                // NO `sdkVersion` HERE. `RnEnvelope.envelope` already emits
                // it on EVERY envelope; appending it again would be a duplicate
                // JSON key — the exact collision that made a parser reject
                // whole documents when `captureMode` existed at two levels. It
                // was already on the wire for Build 20 and the server simply
                // never mapped it.
                // `osVersion` LIVES IN `device`, per the protocol and the
                // Android fixtures. Emitting a top-level one would be a second
                // representation of a fact the schema already has a home for.
                var dev: [(String, RnEnvelope.Value)] = []
                if let n = id.osName { dev.append(("osName", .string(n))) }
                if let o = id.osVersion { dev.append(("osVersion", .string(o))) }
                // `model` IS THE DEVICE CLASS, AND IT IS WHAT THE SERVER
                // DERIVES `device_type` FROM. The deployed `deriveDeviceType`
                // reads geometry first and falls back to this string
                // (`/\b(ipad|tablet)\b/i` → tablet, `/\b(iphone|ipod)\b/i` →
                // mobile); RN iOS emits no geometry, so without this the
                // column is NULL on every session and the S8 device filter
                // cannot match an iOS row. The protocol already declares
                // `device.model` (string, maxLength 64), so nothing on the
                // wire or in the schema changes shape.
                // APPENDED LAST, so the two keys that have shipped since
                // Build 20 keep their serialized positions — this is an
                // ADDITION to the device object, not a reordering of it.
                if let m = id.model { dev.append(("model", .string(m))) }
                if !dev.isEmpty { extra.append(("device", .object(dev))) }
            }
            return extra
        }
        guard r.outcome == .recorded else { return r }
        guard publish(id, generation) else {
            // The opener is durable but the generation moved — a reset landed
            // between the append and this publication. Refusing is correct: the
            // queue that opener lives in has been destroyed.
            return Result(.noSession)
        }
        return r
    }

    public func navigate(fromRouteId: String?, toRouteId: String,
                         toRouteName: String?, transition: String) -> Result {
        guard RnBridge.isSafeId(toRouteId) else { return Result(.invalidInput) }
        if let f = fromRouteId, !RnBridge.isSafeId(f) { return Result(.invalidInput) }
        if let n = toRouteName, !RnBridge.isSafeLabel(n) { return Result(.invalidInput) }
        guard RnBridge.NAV_TRANSITIONS.contains(transition) else { return Result(.invalidInput) }
        // AFTER VALIDATION, NEVER BEFORE. A refused navigation must not open
        // a capture window — that would let a malformed route id from JS drive
        // native cadence.
        // RESOLVE BEFORE THE ROUTE MOVES. After `routeChanged` the old attempt
        // is stale by construction and can never emit — so a verdict already due
        // would be silently lost if these were reordered.
        resolvePendingCaptureOutcomes()
        // THE MOTION BASELINE IS PER ROUTE. Comparing the new screen's
        // scroll surfaces against the old screen's would report motion for a
        // navigation — true of the pixels, but attributed to a scroll that did
        // not happen, and the navigation has already noted activity on its own
        // account. A cleared baseline makes the first candidate on a new route
        // motionless by construction.
        lock.lock(); driverRouteId = toRouteId; lastVisibleSample = nil; lock.unlock()
        // A route change invalidates every in-flight attempt: without this, a
        // verdict for the screen the user just left could land on the new one.
        routeHosts?.routeChanged(toRouteId)
        // A fired-but-stale callback is made impossible here, not filtered later.
        attachDeadline?.cancel()
        noteActivity(.navigation)
        // an earlier change: the destination screen's last observed fact decides; a route
        // never observed holding web content carries nothing.
        return emit("mobile_navigation", neverRecord: false,
                    webView: screenCarrier(routeId: toRouteId)) {
            var extra: [(String, RnEnvelope.Value)] = [
                ("transition", .string(transition)),
                ("toScreenId", .string(toRouteId)),
            ]
            if let f = fromRouteId { extra.append(("fromScreenId", .string(f))) }
            if let n = toRouteName { extra.append(("toScreenName", .string(n))) }
            return extra
        }
    }

    /// A screen snapshot. With a native hierarchy provider wired this emits
    /// the metadata snapshot AND a `mobile_view_hierarchy` event carrying the
    /// masked wireframe tree; without one it stays the metadata-only contract.
    /// `screenNeverRecord` separates "we chose not to" from "we could not" —
    /// and a never-record route NEVER reaches the provider: the short-circuit
    /// is before capture, so the tree is not traversed, not merely not sent.
    /// Record an activity signal — navigation, an interaction phase, or an
    /// observed visible mutation.
    ///
    /// IT CAPTURES NOTHING AND DECIDES NOTHING. It moves a timestamp. The
    /// host still has to call `screenSnapshot`; this only changes whether the
    /// idle floor will apply when it does. Keeping the signal and the capture
    /// separate is what lets a passive touch observer feed the cadence without
    /// gaining any authority over what is captured.
    public func noteActivity(_ signal: FrameCadenceDriver.Signal) {
        let now = clock.wallMillis()
        lock.lock(); driverState = driverState.noting(now); lock.unlock()
        armDriverIfNeeded()
    }

    /// Arm the ONE scheduler loop, if it is not already running.
    ///
    /// THIS IS THE PART THAT ACTUALLY FIXES THE DEFECT. A state machine that
    /// answers `nextCandidateDelayMs = 125` changes nothing unless something
    /// calls back. The reference session's replay was an image slider precisely
    /// because the only caller of the capture path was the app's per-navigation
    /// `screenSnapshot`. This loop is that missing caller.
    ///
    /// `schedule` ABSENT MEANS NO LOOP, deliberately: a scheduler in a unit
    /// test is a race in a unit test, and the pure state machine is tested
    /// directly. Absence degrades to the certified per-navigation behaviour —
    /// it never degrades to an unbounded one.
    private func armDriverIfNeeded() {
        guard let sched = schedule else { return }
        lock.lock()
        if driverArmed { lock.unlock(); return }
        driverArmed = true
        let delay = FrameCadenceDriver.reArmDelayMs(
            state: driverState, nowMs: clock.wallMillis())
        lock.unlock()
        scheduleAccounted(sched, delay, .driverTick) { [weak self] in self?.driverTick() }
    }

    /// PASSIVE ACCOUNTING around the ONE scheduler. Records how late the
    /// callback fired against the delay that was asked for, then runs `work`
    /// unchanged. Without a recorder it IS `sched(delay, work)`.
    ///
    /// Scheduling latency is recorded apart from execution cost: this
    /// measures what the platform charged between the request and the
    /// callback, and nothing about what the callback then did.
    private func scheduleAccounted(_ sched: (Int64, @escaping () -> Void) -> Void,
                                   _ delay: Int64,
                                   _ kind: CaptureAccounting.ScheduleKind,
                                   _ work: @escaping () -> Void) {
        guard let log = captureLog else { sched(delay, work); return }
        let requestedAt = clock.wallMillis()
        sched(delay) { [weak self] in
            if let self = self {
                log.recordScheduling(CaptureAccounting.Scheduling(
                    kind: kind, requestedMs: delay,
                    observedMs: self.clock.wallMillis() - requestedAt))
            }
            work()
        }
    }

    /// One candidate offer, then re-arm ONLY while the window is still open.
    ///
    /// IT DISARMS ON IDLE RATHER THAN TICKING FOREVER AT 1 Hz. A loop that
    /// never stops is a timer leak and a battery cost for a screen nobody is
    /// touching; the idle path is deliberately EVENT-DRIVEN — the next
    /// navigation or interaction re-arms it. `nextCandidateDelayMs` still
    /// answers 1000 ms for idle so a host that chooses to poll gets a truthful
    /// answer; this owned loop simply does not poll.
    ///
    /// IT ALSO DISARMS WHEN THE SESSION IS GONE, so a backgrounded or ended
    /// session cannot leave a live callback behind.
    private func driverTick() {
        lock.lock()
        let armedRoute = driverRouteId
        let st = driverState
        lock.unlock()
        let now = clock.wallMillis()
        let act = FrameCadenceDriver.activity(state: st, nowMs: now)
        let live = configured() && identity() != nil
        guard live, act != .idle, let route = armedRoute else {
            lock.lock(); driverArmed = false; lock.unlock()
            return
        }
        // The EXISTING capture path. The driver adds a caller, never a second
        // way to capture: policy, privacy, coherence and budgets are unchanged.
        _ = screenSnapshot(routeId: route)
        lock.lock()
        let st2 = driverState
        lock.unlock()
        let nowAfter = clock.wallMillis()
        let act2 = FrameCadenceDriver.activity(state: st2, nowMs: nowAfter)
        guard act2 != .idle, let sched = schedule else {
            lock.lock(); driverArmed = false; lock.unlock()
            return
        }
        let delay = FrameCadenceDriver.reArmDelayMs(state: st2, nowMs: nowAfter)
        scheduleAccounted(sched, delay, .driverTick) { [weak self] in self?.driverTick() }
    }

    /// Stop the loop deterministically — background, session end, shutdown.
    /// The route the driver is currently armed for. Read-only: an interaction
    /// takes the screen identity the SDK already believes it is on, and never
    /// derives one from the touched view -- an accessibility label or a UIKit
    /// class name is not replay identity, and inventing one here would put a
    /// tap on a screen no frame was ever captured for.
    public func currentRouteId() -> String? {
        lock.lock(); defer { lock.unlock() }
        return driverRouteId
    }

    // ── test-only observation probe ─────────────────────────────────────────

    /// A NON-SENSITIVE SUMMARY OF WHAT THE PRODUCTION PATH DECIDED.
    ///
    /// IT IS DERIVED FROM THE EMISSION, NOT FROM THE RAW TOUCH. That is the
    /// whole safety property: coordinates exist on this type only when the
    /// shared policy already decided to emit them, so a Never-Record, secure,
    /// text-entry, unknown or redacted target CANNOT carry x/y here — not by
    /// convention, but because there is no code path that could put them there.
    /// A probe fed from the raw observation would have had to re-implement the
    /// policy to stay safe, and would have been wrong the first time the policy
    /// changed.
    public struct InteractionProbeFact {
        public let gestureId: String
        public let phase: String
        /// "emitted" or "suppressed" — the outcome the producer reached.
        public let outcome: String
        public let onRedactedTarget: Bool
        public let x: Double?
        public let y: Double?
        public let viewportWidth: Double
        public let viewportHeight: Double
        /// WHY, NOT JUST WHETHER. "suppressed" is the same word for a
        /// never-record screen, a secure field, an absent route, an unmatched
        /// window and a gesture that is not a v1 tap — five different facts
        /// about a system that is working correctly in four of them. The
        /// refusal code plus the decision INPUTS name the branch. All of it is
        /// enum-valued or boolean: no coordinate, no text, no identifier.
        public let reason: String?
        public let inputs: String
    }

    /// The decision INPUTS, all enum-valued or boolean. Never a coordinate,
    /// never text, never an identifier — `hasRoute` is deliberately a boolean
    /// rather than the route name.
    static func probeInputs(_ o: InteractionProducer.NativeTouchObservation,
                            consentEnabled: Bool) -> String {
        "consent=\(consentEnabled)"
        + " hasRoute=\((o.screenId?.isEmpty == false))"
        + " target=\(o.targetClass.rawValue)"
        + " pos=\(o.positionTreatment.rawValue)"
        + " basis=\(o.basisIsCapturedWindow)"
        + " contentWin=\(o.fromContentWindow)"
        + " neverRec=\(o.screenNeverRecord)"
        + " textEntry=\(o.textEntryActive)"
        + " pointers=\(o.pointerCount)"
        + " moved=\(o.movedBeyondSlop)"
    }

    /// Set ONLY by the fixture app, and only under an explicit launch
    /// argument. Nil in every shipping configuration; it starts nothing,
    /// records nothing durable, and cannot inject an interaction — it is
    /// invoked AFTER the decision and its return value is discarded.
    ///
    /// STATIC because the fixture has no handle on the module's private bridge
    /// instance, and because the alternative — an `@objc` accessor — would put
    /// interaction control on the JS surface, which guard W2 forbids and which
    /// would let the layer being observed decide what gets observed.
    public static var interactionProbe: ((InteractionProbeFact) -> Void)?

    // ── interaction ingestion ───────────────────────────────────────────────

    /// TWO CHANNELS, DELIBERATELY SEPARATE, AND ONLY ONE OF THEM STORES
    /// ANYTHING.
    ///
    /// * CADENCE ACTIVITY fires for EVERY touch, including one the interaction
    /// policy suppresses and one the app has not consented to record. It
    /// stores no touch data whatsoever -- it only tells the frame driver a
    /// human is doing something, so the visual capture speeds up. Gating it
    /// on interaction consent would mean an app that declines interaction
    /// recording also silently gets a worse VISUAL replay, which is a
    /// consequence nobody agreed to and nobody could see.
    /// * PERSISTED EMISSION is gated by consent AND by the shared policy, and
    /// is the only channel that can produce a row.
    ///
    /// There is ONE scheduler. This routes into the existing
    /// `FrameCadenceDriver` through `noteActivity`; it starts no timer of its
    /// own and owns no driver state.
    @discardableResult
    public func recordInteraction(_ o: InteractionProducer.NativeTouchObservation,
                                  consentEnabled: Bool) -> Result {
        // Channel 1 -- unconditional, ephemeral, stores nothing.
        switch o.phase {
        case .begin, .instant: noteActivity(.interactionStart)
        case .move:            noteActivity(.interactionMove)
        case .end, .cancel:    noteActivity(.interactionEnd)
        }

        // Channel 2 -- gated, and routed through the ONE gesture lifecycle.
        //
        // ONE OBSERVATION MAY PRODUCE 0, 1 OR 2 PERSISTED EVENTS. Zero is
        // the ordinary case for a `begin` and for every move inside the tap
        // slop or the sampling bound — exactly the pre-movement-lane
        // behaviour, where those phases were suppressed as unsupported
        // gestures. Two is the retroactive case ONLY: the moment a touch
        // crosses the slop, the withheld BEGIN is materialized from the
        // ORIGINAL observation and the crossing move follows it, so replay has
        // a real start point instead of a path that begins mid-drag.
        lock.lock()
        let steps = gestures.observe(o)
        lock.unlock()

        var last = Result(.suppressed)
        for step in steps {
            last = recordInteractionStep(step.observation, step.gestureClass,
                                         consentEnabled: consentEnabled)
        }
        if steps.isEmpty {
            // Nothing persisted for this observation. The probe still records
            // WHY, so a bounded move is distinguishable from a refusal.
            RnBridge.interactionProbe?(InteractionProbeFact(
                gestureId: o.gestureId, phase: o.phase.rawValue, outcome: "suppressed",
                onRedactedTarget: true, x: nil, y: nil,
                viewportWidth: o.viewportWidth, viewportHeight: o.viewportHeight,
                reason: "gesture_lifecycle_withheld",
                inputs: RnBridge.probeInputs(o, consentEnabled: consentEnabled)))
        }
        return last
    }

    /// One lifecycle-approved observation, with its settled class, through the
    /// SHARED policy and onto the wire.
    private func recordInteractionStep(_ o: InteractionProducer.NativeTouchObservation,
                                       _ gestureClass: InteractionProducer.GestureClass?,
                                       consentEnabled: Bool) -> Result {
        let produced = InteractionProducer.produce(o, consentEnabled: consentEnabled,
                                                   gestureClass: gestureClass)
        let inputs = RnBridge.probeInputs(o, consentEnabled: consentEnabled)
        guard case .emit(let e) = produced else {
            var why = "unknown"
            if case .refuse(let r) = produced { why = r.rawValue }
            RnBridge.interactionProbe?(InteractionProbeFact(
                gestureId: o.gestureId, phase: o.phase.rawValue, outcome: "suppressed",
                onRedactedTarget: true, x: nil, y: nil,
                viewportWidth: o.viewportWidth, viewportHeight: o.viewportHeight,
                reason: why, inputs: inputs))
            return Result(.suppressed)
        }
        RnBridge.interactionProbe?(InteractionProbeFact(
            gestureId: e.gestureId, phase: e.phase, outcome: "emitted",
            onRedactedTarget: e.onRedactedTarget,
            x: e.includeCoordinates ? e.x : nil,
            y: e.includeCoordinates ? e.y : nil,
            viewportWidth: e.viewportWidth, viewportHeight: e.viewportHeight,
            reason: nil, inputs: inputs))
        guard let id = identity() else {
            return Result(configured() ? .noSession : .notConfigured)
        }
        return emitAt(id, "mobile_interaction", neverRecord: o.screenNeverRecord,
                      wallMillis: e.wallMillis, monotonicNanos: e.monotonicNanos,
                      webView: RnBridge.carrierFor(insideWebContent: o.insideWebContent,
                                                   webViewRef: webViewRef)) {
            InteractionProducer.envelopeFields(e)
        }
    }

    /// Drop any in-flight gesture — called when the passive observer detaches
    /// or the surface remounts, so a new touch can never inherit a stale
    /// begin. Belt and braces: the lifecycle already keys on `gestureId` and a
    /// fresh `begin` resets it, but a remount is exactly the moment where
    /// "already correct by construction" is worth stating.
    public func resetGestureLifecycle() {
        lock.lock(); gestures.reset(); lock.unlock()
    }

    public func suspendCaptureDriver() {
        lock.lock(); driverArmed = false; driverState = .empty; lock.unlock()
    }

    /// Should the host offer a capture candidate right now?
    public func shouldOfferCandidate() -> Bool {
        let now = clock.wallMillis()
        lock.lock(); let st = driverState; lock.unlock()
        return FrameCadenceDriver.shouldOfferCandidate(state: st, nowMs: now)
    }

    /// Milliseconds until the next candidate is due. The host schedules on this.
    public func nextCandidateDelayMs() -> Int64 {
        let now = clock.wallMillis()
        lock.lock(); let st = driverState; lock.unlock()
        return FrameCadenceDriver.nextCandidateDelayMs(state: st, nowMs: now)
    }

    /// The current activity window, for diagnostics and the durable trace.
    public func currentActivity() -> FrameCadenceDriver.Activity {
        let now = clock.wallMillis()
        lock.lock(); let st = driverState; lock.unlock()
        return FrameCadenceDriver.activity(state: st, nowMs: now)
    }

    /// The capture funnel so far. Read-only snapshot.
    public func frameFunnel() -> FrameFunnelCounters {
        lock.lock(); let f = funnel; lock.unlock(); return f
    }

    // ── THE ROUTE-HOST INTEGRATION CONTRACT ────────────────────────────────
    // SEMANTIC DECLARATIONS BY THE HOST ABOUT ITSELF, and nothing more. A
    // host may say "I am here", "I became ready" and "I failed". It carries no
    // producer identity, no privacy state and no capture control, and a host
    // that says nothing leaves the outcome UNKNOWN.

    public func hostAttached(routeId: String, hostId: String, rootHandle: Int? = nil) -> Outcome {
        guard RnBridge.isSafeId(routeId), RnBridge.isSafeId(hostId) else { return .invalidInput }
        guard let r = routeHosts else { return .notIntegrated }
        // THE CAPTURE ROOT IS A SEPARATE FACT FROM READINESS, AND IT IS THE
        // ONE THAT AUTHORIZES A WALK. A host may attach and declare readiness
        // without ever naming a root; it is then a legitimate participant on
        // the outcome axis and authorizes no capture at all, because nothing
        // establishes which views are its.
        r.hostAttached(routeId: routeId, hostId: hostId, rootHandle: rootHandle)
        return .recorded
    }

    /// The route's capture root, if ownership can be established. Nil for an
    /// unintegrated app AND for an integrated route whose ownership is absent
    /// or ambiguous — callers must separate those with `isIntegrated`.
    public func boundRoot(_ routeId: String) -> RouteHostRegistry.BoundRoot? {
        routeHosts?.boundRoot(routeId)
    }

    /// Is a route-host registry present at all? Separates legacy unintegrated
    /// behaviour from an integrated app's unowned route.
    public func isIntegrated() -> Bool { routeHosts != nil }

    public func bindingIsCurrent(_ routeId: String, _ b: RouteHostRegistry.BoundRoot) -> Bool {
        routeHosts?.bindingIsCurrent(routeId, b) ?? false
    }

    /// The host declares it is PREPARING and WILL report readiness.
    /// Missing from every surface until now while the registry implemented
    /// it — so the readiness CONTRACT was unreachable from an application.
    public func hostReadinessPending(routeId: String, hostId: String) -> Outcome {
        guard RnBridge.isSafeId(routeId), RnBridge.isSafeId(hostId) else { return .invalidInput }
        guard let r = routeHosts else { return .notIntegrated }
        r.hostReadinessPending(routeId: routeId, hostId: hostId)
        return .recorded
    }

    public func hostReady(routeId: String, hostId: String) -> Outcome {
        guard RnBridge.isSafeId(routeId), RnBridge.isSafeId(hostId) else { return .invalidInput }
        guard let r = routeHosts else { return .notIntegrated }
        r.hostReady(routeId: routeId, hostId: hostId)
        return .recorded
    }

    public func hostFailed(routeId: String, hostId: String) -> Outcome {
        guard RnBridge.isSafeId(routeId), RnBridge.isSafeId(hostId) else { return .invalidInput }
        guard let r = routeHosts else { return .notIntegrated }
        r.hostFailed(routeId: routeId, hostId: hostId)
        return .recorded
    }

    public func hostDetached(routeId: String, hostId: String) -> Outcome {
        guard RnBridge.isSafeId(routeId), RnBridge.isSafeId(hostId) else { return .invalidInput }
        guard let r = routeHosts else { return .notIntegrated }
        r.hostDetached(routeId: routeId, hostId: hostId)
        return .recorded
    }

    /// One attempt still waiting out its attach budget. The entry keeps the
    /// ATTEMPT and its ROUTE — no snapshot identifier; a deferred marker mints
    /// its own. The wait is bounded by `AttachDeadline`, armed once per attempt.
    private struct PendingOutcome {
        let attempt: RouteHostRegistry.Attempt
        let routeId: String
    }
    private var pendingOutcomes: [PendingOutcome] = []

    /// Observability only — never a protocol reason and never on the wire.
    public private(set) var droppedStaleOutcomes = 0
    /// Diagnostics for tests: what is actually queued. Never a decision input.
    public func pendingOutcomeCount() -> Int { lock.lock(); defer { lock.unlock() }; return pendingOutcomes.count }
    public func pendingAttemptIds() -> [String] { lock.lock(); defer { lock.unlock() }; return pendingOutcomes.map { $0.attempt.attemptId } }

    /// Re-evaluate every waiting attempt and emit at most ONE marker for each.
    @discardableResult
    public func resolvePendingCaptureOutcomes() -> Int {
        guard let r = routeHosts else { return 0 }
        lock.lock()
        let batch = pendingOutcomes
        pendingOutcomes = []
        lock.unlock()

        var emitted = 0
        var keep: [PendingOutcome] = []
        for p in batch {
            // PRIVACY IS RE-CHECKED AT EXECUTION, NOT ONLY AT QUEUEING. A route
            // declared never-record AFTER an attempt was queued must not receive
            // a marker at delivery; the attempt is abandoned outright.
            if isNeverRecord(p.routeId) {
                r.cancel(p.attempt); droppedStaleOutcomes += 1
                trace?("rh_dropped id=\(p.attempt.attemptId) reason=never_record_at_delivery")
                continue
            }
            let d = CaptureOutcome.decide(neverRecord: false, host: r.evaluate(p.attempt))
            trace?("rh_callback_entry id=\(p.attempt.attemptId) route=\(p.routeId) stale=\(r.isStale(p.attempt)) settled=\(p.attempt.isSettled)")
            switch d.decision {
            case .unavailable:
                // THE EXACTLY-ONCE GATE IS THE REGISTRY'S, NOT A LOCAL FLAG —
                // it is per ATTEMPT, so a requeue, a racing caller and a retry
                // all collapse to one emission.
                if r.claimResolution(p.attempt), let reason = d.captureUnavailableReason {
                    let res = emitUnavailableMarker(routeId: p.routeId, reason: reason)
                    trace?("rh_emitted id=\(p.attempt.attemptId) reason=\(reason) outcome=\(res.outcome)")
                    emitted += 1
                } else {
                    trace?("rh_claim_refused id=\(p.attempt.attemptId) — already resolved or stale")
                }
            case .noEmission:
                // `isSettled` ALONE RE-QUEUES A STALE ATTEMPT FOREVER.
                // A stale attempt is neither resolved nor cancelled, so it stays
                // unsettled, is kept on every pass, and the queue never drains.
                // This is the same defect the Android twin shipped and had
                // fixed; iOS never received it, and the test that would have
                // caught it could not compile.
                if !p.attempt.isSettled && !r.isStale(p.attempt) {
                    keep.append(p); trace?("rh_still_waiting id=\(p.attempt.attemptId) decision=\(d.decision)")
                } else {
                    droppedStaleOutcomes += 1
                    trace?("rh_dropped id=\(p.attempt.attemptId) settled=\(p.attempt.isSettled) stale=\(r.isStale(p.attempt))")
                }
            default:
                // CAPTURE DISCOVERED BY THE DRAIN SETTLES THE ATTEMPT. The host
                // became ready with no capture call in between: nothing is
                // emitted here, but the attempt must not stay OPEN in the
                // registry — the next capture would reuse an already-
                // expired budget and report not_attached at once.
                r.cancel(p.attempt)
                trace?("rh_settled_by_drain id=\(p.attempt.attemptId) decision=\(d.decision)")
            }
        }
        if !keep.isEmpty { lock.lock(); pendingOutcomes.append(contentsOf: keep); lock.unlock() }
        // Nothing left to wait for: cancel rather than fire into an empty queue.
        else { attachDeadline?.cancel() }
        return emitted
    }

    /// The marker, emitted on its own when the verdict arrives AFTER the snapshot.
    ///
    /// A SECOND EVENT, DELIBERATELY — BUT NOT A DUPLICATE SNAPSHOT. The
    /// ordinary snapshot is emitted once per `screenSnapshot` call; the OUTCOME
    /// is emitted at most once per ATTEMPT. A verdict not yet known at snapshot
    /// time cannot be back-dated into an event already appended — the durable
    /// sink has no un-append. The server models exactly this: `mobile_screens`
    /// carries `metadata_only_at_sequence` and `unavailable_at_sequence` as
    /// SEPARATE marks, so a later outcome refines a screen rather than
    /// duplicating it. The invariant that matters: for one attempt, AT MOST ONE
    /// event ever carries `captureUnavailableReason`.
    @discardableResult
    private func emitUnavailableMarker(routeId: String, reason: String) -> Result {
        emit("mobile_screen_snapshot", neverRecord: false,
             webView: screenCarrier(routeId: routeId)) {
            [
                ("screen", .screen(RnEnvelope.ScreenRef(screenId: routeId))),
                ("snapshotId", .string(RnBridge.newSnapshotId())),
                // The TRIGGER stays the trigger.
                ("reason", .string("screen_entered")),
                ("captureMode", .string("metadata_only")),
                ("captureUnavailableReason", .string(reason)),
                // No counters: nothing was walked, and 0 is a measurement.
            ]
        }
    }

    public func screenSnapshot(routeId: String) -> Result {
        lock.lock()
        driverRouteId = routeId
        let position = acctRoute.note(routeId: routeId, nowMs: clock.wallMillis())
        lock.unlock()
        guard RnBridge.isSafeId(routeId) else { return Result(.invalidInput) }
        // SETTLE PRIOR ATTEMPTS FIRST. Every public entry point re-evaluates
        // waiting attempts, AND the attach deadline (`AttachDeadline`, the
        // platform's own scheduler) fires the same drain when the budget
        // elapses — an app that goes idle still learns the verdict on time.
        resolvePendingCaptureOutcomes()
        // THE OFFER CLOCK ADVANCES FOR EVERY CANDIDATE, INCLUDING A REFUSED
        // ONE. Measured on the Build-26 physical drive: 288 candidates/second
        // with a 3.4 ms median gap, 527 of 539 `activity` snapshots
        // `metadata_only`, and 70–75 % of every delivered record a
        // `mobile_screen_snapshot`. The cause was that `offering(now)` sat
        // INSIDE the `if let f = frame, let h = cap?.result` success branch, so
        // a backgrounded app — where the hierarchy provider correctly returns
        // nil — never advanced `lastCandidateMs`. `nextCandidateDelayMs` then
        // answered 0 forever and the driver re-armed at its 1 ms floor.
        //
        // The driver's job is to pace OFFERS. A refused offer is still an
        // offer, and treating it as if it never happened is what made the clock
        // stand still.
        //
        // THIS IS NOT THE FRAME-CADENCE ACCEPTANCE CLOCK, AND THE TWO MUST
        // NOT BE MERGED. `lastAcceptedSubjectKey` / `lastFrameWallMs` are
        // advanced ONLY where a frame was actually obtained — a failed capture
        // must not silence a screen for ten seconds, which is a separate
        // deliberate rule proven by FrameCadenceTests. Two clocks, two
        // meanings; conflating them would either re-break the cadence fix or
        // leave the storm in place.
        defer { lock.lock(); driverState = driverState.offering(clock.wallMillis()); lock.unlock() }
        let never = isNeverRecord(routeId)
        // the specification Never-Record short-circuit BEFORE traversal, and background/no-
        // window inside the provider returns nil — both fold to "no capture".
        // ── THE ROUTE-HOST FACTS, ASKED BEFORE THE WALK ────────────────────
        // A never-record route never reaches the registry, exactly as it never
        // reaches the provider: an intentional exclusion is decided first and
        // must not become a capture-failure verdict.
        let attempt: RouteHostRegistry.Attempt? = never ? nil
            : routeHosts.flatMap { r in
                identity().map { r.beginAttempt(routeId: routeId, sessionId: $0.sessionId) }
            }
        let hostFacts = attempt.map { routeHosts!.evaluate($0) } ?? .unknown
        let hostDecision = CaptureOutcome.decide(neverRecord: never, host: hostFacts)
        trace?("rh_attempt id=\(attempt?.attemptId ?? "<none>") route=\(routeId) never=\(never) registry=\(routeHosts != nil) identity=\(identity() != nil) decision=\(hostDecision.decision)")
        let outcome: HierarchyOutcome =
            (never || hierarchy == nil) ? HierarchyOutcome() : (hierarchy?(routeId) ?? HierarchyOutcome())
        let cap: HierarchyCapture? = outcome.capture
        // RECORDED, NOT DECIDED — and recorded HERE, before admission, so
        // the execution cost and the produced bytes of a capture that the
        // policy goes on to refuse are still measured. A meter-less provider
        // reports its walk only, with every raster phase at zero.
        if let log = captureLog, let c = cap {
            log.recordExecution(CaptureAccounting.Execution(
                screenId: routeId, position: position,
                phases: c.phases ?? CaptureAccounting.Phases(walkNanos: c.walkNanos),
                producedBytes: c.producedFrameBytes))
        }
        // THE REASON IS THE PROVIDER'S OBSERVATION, NEVER THE BRIDGE'S
        // INFERENCE. The bridge cannot see a surface, so it must not deduce a
        // cause from the absence of a capture — "no capture" has several
        // sources here and only two of them are protocol conditions. A
        // never-record route never reaches the provider, so it can never carry
        // one.
        // THE REGISTRY IS THE ONLY SOURCE OF A REASON. The provider reports a
        // capture or its absence; it has no route-host facts and therefore no
        // standing to name a cause. Its `captureUnavailableReason` is retained
        // in the type for a platform that one day DOES observe the protocol
        // conditions natively, and is deliberately not consulted here.
        var unavailableReason: String? = nil
        if !never, let a = attempt, let r = routeHosts {
            switch hostDecision.decision {
            case .unavailable:
                if r.claimResolution(a) { unavailableReason = hostDecision.captureUnavailableReason }
            case .noEmission:
                // AN UNATTRIBUTED CAPTURE MUST NOT SETTLE THE ATTEMPT.
                // This read `if cap == nil` — a capture of ANY content cancelled
                // the attempt as a success. But the capture root is the
                // application's window and the routeId is only a LABEL, so
                // content belonging to a different route could settle, and
                // suppress, the verdict for the route actually requested.
                // Measured on the Android twin: a route NEVER rendered returned
                // 70 nodes of whatever the window was showing.
                //
                // Ownership is established ONLY where the shared rule says
                // CAPTURE — the `default:` branch below, i.e. a qualifying host
                // declared itself ready. Here ownership is UNKNOWN, so the
                // capture is still emitted as content exactly as before and the
                // attempt keeps running its budget.
                lock.lock(); let alreadyQueued = pendingOutcomes.contains { $0.attempt === a }; lock.unlock()
                if alreadyQueued {
                    // An attempt already queued and armed: queueing
                    // again would drain it twice, arming again would reset the
                    // deadline by the capture cadence — forever.
                    trace?("rh_attempt_reused id=\(a.attemptId) route=\(routeId) queue=\(pendingOutcomeCount())")
                } else {
                    lock.lock(); pendingOutcomes.append(PendingOutcome(attempt: a, routeId: routeId)); lock.unlock()
                    trace?("rh_queued id=\(a.attemptId) route=\(routeId) budget=\(attachBudgetMs)")
                    // ARM A REAL DEADLINE. The queue alone would only be
                    // drained by the NEXT bridge call, so an app that goes idle
                    // here would never learn the budget elapsed.
                    if attachDeadline == nil { trace?("rh_no_deadline_owner id=\(a.attemptId) — the wait is unbounded") }
                    attachDeadline?.arm(delayMs: attachBudgetMs) { [weak self] in
                        self?.trace?("rh_deadline_fired id=\(a.attemptId)")
                        self?.resolvePendingCaptureOutcomes()
                    }
                    if attachDeadline != nil { trace?("rh_deadline_armed id=\(a.attemptId) inMs=\(attachBudgetMs)") }
                }
            default:
                r.cancel(a)
            }
        }
        // Phase 2: a frame is considered ONLY with a hierarchy to redact from,
        // never for a never-record route, and inside the pure budgets. The
        // native provider applies opt-in/foreground itself and may refuse
        // (nil) — then the snapshot honestly stays wireframe_only.
        var fr: FrameCapture? = nil
        var frameRefused: String? = nil
        if let f = frame, let h = cap?.result {
            let now = clock.wallMillis()
            // THE MOTION OBSERVATION, TAKEN WHERE IT IS USED AND ONCE PER
            // CANDIDATE. Sampling here rather than on a timer of its own is
            // what makes it impossible for the sample and the decision to
            // describe different moments — and it adds no mechanism: the
            // driver's existing tick is already the thing that arrives.
            //
            // IT EXTENDS AN OPEN WINDOW; IT DOES NOT OPEN ONE FROM IDLE.
            // The measured defect is the tail of a flick, where the window IS
            // open and closes on `trailingMs` while the document is still
            // decelerating. Observed motion re-notes activity, so the window
            // now closes when the picture stops instead of when the clock says
            // the finger has been up for 1.5 s — bounded by stillness, which is
            // the bound this cadence has always been built on.
            let sampled = visibleState?(routeId)
            lock.lock()
            let previousSample = lastVisibleSample
            if sampled != nil { lastVisibleSample = sampled }
            lock.unlock()
            let movedSinceLast = VisibleState.moved(from: previousSample, to: sampled)
            if movedSinceLast {
                lock.lock(); visibleMutationSignals += 1; funnel.visibleMutation += 1; lock.unlock()
                noteActivity(.visibleMutation)
            }
            // The subject a frame here would describe: screen identity, the
            // coherence transaction's OWN structural digest, and the scroll
            // geometry that digest structurally cannot see. No second hash of
            // the hierarchy — the third component is a different FACT, not a
            // second opinion about the same one.
            let subjectKey = FrameCadence.subjectKey(
                routeId: routeId, coherenceFingerprint: cap?.coherenceFingerprint,
                visibleStateDigest: sampled?.digest)
            let labActive = labCadence?() ?? false
            lock.lock()
            let state = FrameCadence.State(lastAcceptedSubjectKey: lastAcceptedSubjectKey,
                                           lastAcceptedWallMs: lastFrameWallMs)
            let count = framesThisSession
            lock.unlock()
            // ONLY the meaning of `since` changes. `FramePolicy.decide` is a
            // SHARED contract pinned to the Kotlin twin by the parity vectors,
            // and moving it would move Android's certified output; the session
            // frame ceiling is likewise untouched and still absolute.
            // THE IDLE ANSWER IS THE CERTIFIED ONE, UNCHANGED. Only an
            // ACTIVE/TRAILING window answers differently, and only under the
            // resolved Lab scope. `FramePolicy.decide` itself is untouched, so
            // Android's certified parity output does not move.
            lock.lock(); let dstate = driverState; lock.unlock()
            let act = FrameCadenceDriver.activity(state: dstate, nowMs: now)
            let since = FrameCadenceDriver.effectiveSinceMs(labActive: labActive,
                                                            activity: act,
                                                            subjectKey: subjectKey,
                                                            cadence: state, nowMs: now)
            lock.lock()
            funnel.candidate += 1
            // A window that has closed takes its count with it.
            if act == .idle { windowFrames = 0 }
            let wf = windowFrames
            lock.unlock()
            // THE LAB LANE'S OWN ABSOLUTE BOUND, CHECKED BEFORE `decide`.
            // Re-scoping the shared 40-frame ceiling to the activity window
            // without this would make a Lab session unbounded.
            // The SAME number `FramePolicy.decide` receives — computed once,
            // used by the decision AND by the record, so the artifact can never
            // describe a value the policy did not actually see.
            let budgetCountForLog = FrameCadenceDriver.effectiveFramesThisSession(
                labActive: labActive, activity: act,
                windowFrames: wf, sessionFrames: count)
            // RECORDED, NOT DECIDED. Every `note(.)` below runs AFTER the
            // branch it describes has already been taken, and nothing reads its
            // result. Build 26 makes the decision provable without moving it.
            let budgetInForce = FrameCadenceDriver.budgetInForce(labActive: labActive,
                                                                 activity: act)
            func note(_ outcome: CaptureDecisionLog.Outcome,
                      policyCapture: Bool = false, policyRefusal: String? = nil,
                      providerCalled: Bool = false, providerReason: String? = nil,
                      visualFrameId: String? = nil) {
                guard let log = captureLog else { return }
                log.record(CaptureDecisionLog.Decision(
                    sessionId: identity()?.sessionId ?? "-", screenId: routeId,
                    monotonicNanos: clock.monotonicNanos(), wallMs: now,
                    labActive: labActive, activityState: act.rawValue,
                    sessionFrames: count, windowFrames: wf, effectiveFrames: budgetCountForLog,
                    budgetSelected: budgetInForce,
                    policyCapture: policyCapture, policyRefusal: policyRefusal,
                    providerCalled: providerCalled, providerReason: providerReason,
                    admission: nil, visualFrameId: visualFrameId, outcome: outcome))
            }
            if FrameCadenceDriver.labSessionBudgetExhausted(labActive: labActive,
                                                            sessionFrames: count) {
                lock.lock(); funnel.labSessionBudget += 1; lock.unlock()
                frameRefused = "lab_session_frame_budget"
                note(.labSessionBudget)
            } else {
            // ONLY THE SCOPE OF THE NUMBER CHANGES. `decide` is untouched and
            // still applies the same ceiling rule; outside the Lab it receives
            // the session count exactly as before.
            let budgetCount = budgetCountForLog
            let d = FramePolicy.decide(enabled: true, neverRecord: never, foreground: true,
                                       windowSecure: false, hierarchy: h,
                                       sinceLastFrameMs: since, framesThisSession: budgetCount)
            if d.capture {
                lock.lock(); funnel.policyAdmitted += 1; lock.unlock()
                if let got = f(routeId, h) {
                    fr = got
                    lock.lock(); funnel.captured += 1; funnel.encoded += 1
                    funnel.queued += 1; lock.unlock()
                    // The budget is consumed by a frame we ACTUALLY GOT. A
                    // coherence refusal must not spend the cadence slot as
                    // though a frame had been captured — that would let one
                    // unstable moment silence the screen for 10 seconds.
                    // THE ONE DEFINITION OF ACCEPTED, unchanged: `f` returned,
                    // which means the frame entered the Phase-2 delivery path.
                    // The subject key is recorded HERE and nowhere else, so a
                    // coherence/rasterization/enqueue failure leaves the screen
                    // eligible for another attempt rather than burning its slot.
                    lock.lock(); lastFrameWallMs = now
                    lastAcceptedSubjectKey = subjectKey
                    framesThisSession += 1; windowFrames += 1; lock.unlock()
                    lock.lock(); frameRetryRoute = nil; frameRetryAttempts = 0; lock.unlock()
                    note(.captured, policyCapture: true, providerCalled: true,
                         visualFrameId: got.visualFrameId)
                } else {
                    frameRefused = frameRefusal?()
                    // THE CASE BUILD 25 COULD NOT ANSWER. The policy admitted,
                    // the provider was called, and it returned nil — the exact
                    // shape of the unexplained ACTIVE windows at 102.5/103.0/
                    // 111.1 s. Build 25 wrote nothing here at all.
                    note(.providerNil, policyCapture: true, providerCalled: true,
                         providerReason: frameRefused)
                }
            } else if d.refusal == "cadence_budget" {
                // TWO DISTINCT THROTTLES, NEVER CONFLATED IN A TRACE: the
                // driver's activity window and the shared policy's idle floor.
                lock.lock()
                if act == .idle { funnel.throttledIdle += 1 }
                else { funnel.throttledActive += 1 }
                lock.unlock()
                note(.policyRefused, policyRefusal: d.refusal)
            } else if !d.capture {
                // Every other policy refusal — `session_budget` above all, the
                // primary candidate for the Build-25 idle window.
                note(.policyRefused, policyRefusal: d.refusal)
            }
            }
            // The offer clock is advanced ONCE, in the `defer` at the top of
            // this function, for every candidate. A second writer here would be
            // two sources for one fact.
        }
        // The two boundaries a candidate never reaches, recorded so a reader
        // can tell "no frame was considered" from "a frame was considered and
        // refused". Build 25 rendered both as identical silence.
        if let log = captureLog, frame != nil, cap?.result == nil {
            log.record(CaptureDecisionLog.Decision(
                sessionId: identity()?.sessionId ?? "-", screenId: routeId,
                monotonicNanos: clock.monotonicNanos(), wallMs: clock.wallMillis(),
                labActive: labCadence?() ?? false, activityState: currentActivity().rawValue,
                sessionFrames: framesThisSession, windowFrames: windowFrames,
                effectiveFrames: -1, budgetSelected: -1,
                policyCapture: false, policyRefusal: nil,
                providerCalled: false, providerReason: nil,
                admission: nil, visualFrameId: nil,
                outcome: never ? .neverRecord : .noHierarchy))
        }
        let mode = fr != nil ? FrameBudgets.captureModeWithFrames
            : (cap != nil ? "wireframe_only" : "metadata_only")
        // an earlier change: THIS capture is the newest observation of this screen, so it
        // is recorded before the snapshot asks for its carrier. A never-record
        // route is never traversed and is recorded as not holding web content.
        observeScreen(routeId: routeId, neverRecord: never, capture: cap)
        let snap = emit("mobile_screen_snapshot", neverRecord: never,
                        webView: screenCarrier(routeId: routeId)) {
            var extra: [(String, RnEnvelope.Value)] = [
                // Canonical nested screen identity. A flat top-level screenId is
                // a legacy alias the server's mobile_screens writer never reads:
                // on Android it stored chunks that materialized NO screen row
                // and rendered zero viewer chips (SDK PR #94).
                ("screen", .screen(RnEnvelope.ScreenRef(screenId: routeId))),
                ("snapshotId", .string(RnBridge.newSnapshotId())),
                ("reason", .string("screen_entered")),
                ("captureMode", .string(mode)),
            ]
            // The ingestion folds these into mobile_screens counters — they
            // come from the SAME HierarchyResult the nodes will come from, so
            // the counters and the payload cannot disagree. Absent in
            // metadata_only: a snapshot that captured nothing counts nothing.
            if let c = cap {
                extra.append(("wireframeNodeCount", .int(c.result.wireframeNodeCount)))
                extra.append(("blockedNodeCount", .int(c.result.blockedCount)))
            }
            // THE FAILURE AXIS, AND IT CARRIES NO COUNTERS.
            // The server detects counters by OWNERSHIP
            // (capture_unavailable_reason_with_counters), so emitting them as 0
            // is emitting them — and a marker claiming the content could not be
            // captured while reporting how many nodes it walked is not
            // interpretable. `cap` is NOT nil by construction here: a bound root
            // and a host that already reported failure produce BOTH, so the
            // reason is attached only when nothing was captured; with content it
            // is reported SEPARATELY, in its own marker, after the content.
            if let r = unavailableReason, cap == nil {
                extra.append(("captureUnavailableReason", .string(r)))
            }
            return extra
        }
        guard snap.outcome == .recorded, let capture = cap else { return snap }
        // A hierarchy entry above the size budget is DROPPED with the snapshot
        // kept — a partial tree that lies about its own count is worse than an
        // honest metadata event. 64 KiB per entry, explicit.
        let r = emitHierarchy(routeId: routeId, capture: capture, after: snap)
        if let got = fr { emitFrameEvent(routeId: routeId, frame: got) }
        else if let why = frameRefused { scheduleFrameRetryIfCoherence(routeId: routeId, reason: why) }
        // VALID CONTENT AND A KNOWN FAILURE COEXIST AS TWO RECORDS: the
        // content above is preserved exactly; the outcome is a metadata-only
        // marker with its own identifiers and no counters.
        if let reason = unavailableReason {
            let m = emitUnavailableMarker(routeId: routeId, reason: reason)
            trace?("rh_emitted_alongside_content route=\(routeId) reason=\(reason) outcome=\(m.outcome)")
        }
        return r
    }

    /// the specification — a transition is an expected state, not an error. A coherence
    /// refusal fails closed for THIS opportunity and buys a later ordinary one:
    /// the same snapshot is re-run once the screen has had a chance to settle.
    ///
    /// Bounded by construction — at most `MAX_FRAME_RETRIES` per screen entry,
    /// scheduled (never slept, never looped), and only for refusals the native
    /// layer named as coherence-class. A route that never settles spends two
    /// attempts and stops; it cannot pin the main thread and it cannot emit
    /// snapshots forever.
    private func scheduleFrameRetryIfCoherence(routeId: String, reason: String) {
        guard RnBridge.RETRYABLE_FRAME_REFUSALS.contains(reason), let sched = schedule else { return }
        lock.lock()
        if frameRetryRoute != routeId { frameRetryRoute = routeId; frameRetryAttempts = 0 }
        let n = frameRetryAttempts
        guard n < RnBridge.MAX_FRAME_RETRIES else { lock.unlock(); return }
        frameRetryAttempts = n + 1
        lock.unlock()
        let delay = RnBridge.FRAME_RETRY_DELAYS_MS[min(n, RnBridge.FRAME_RETRY_DELAYS_MS.count - 1)]
        scheduleAccounted(sched, delay, .frameRetry) { [weak self] in
            _ = self?.screenSnapshot(routeId: routeId)
        }
    }

    /// The `mobile_visual_frame` METADATA event; the bytes travel separately.
    private func emitFrameEvent(routeId: String, frame fr: FrameCapture) {
        guard let id = identity() else { return }
        let wall = clock.wallMillis(), mono = clock.monotonicNanos()
        let eventId = ids.newEventId()
        let posture = RnEnvelope.Posture(allowedViewCount: 0, screenNeverRecord: false)
        _ = sink.enqueue { assigned in
            Data(RnEnvelope.visualFrame(
                id: id, eventId: eventId, sequence: assigned, timestampMillis: wall,
                monotonicNanos: mono, posture: posture, visualFrameId: fr.visualFrameId,
                screenId: routeId, contentHash: fr.contentHash, width: fr.width,
                height: fr.height, byteSize: fr.byteSize, format: fr.format,
                quality: fr.quality, downscale: fr.downscale).utf8)
        }
    }

    private func emitHierarchy(routeId: String, capture: HierarchyCapture,
                               after snap: Result) -> Result {
        guard let id = identity() else { return snap }
        let wall = clock.wallMillis()
        let mono = clock.monotonicNanos()
        let eventId = ids.newEventId()
        let snapshotId = RnBridge.newSnapshotId()
        // THIS EVENT's derived count — the same HierarchyResult the nodes come
        // from, so the scalar and the payload cannot disagree.
        let posture = RnEnvelope.Posture(allowedViewCount: capture.result.allowedViewCount,
                                         screenNeverRecord: false)
        // Budget check BEFORE the durable append: the sink has no un-append,
        // so a veto after enqueue is impossible. The probe build uses sequence
        // 0 — the real sequence changes the byte count by at most a few digits,
        // which the 512-byte margin absorbs.
        // an earlier change: carried only when THIS capture saw web content — asked once,
        // and the same value goes into the probe and into the durable bytes.
        let wv = RnBridge.screenCarrierFor(
            screenHoldsWebContent: capture.result.webViewSurfaceCount > 0,
            webViewRef: webViewRef)?()
        let probe = RnEnvelope.viewHierarchy(
            id: id, eventId: eventId, sequence: 0,
            timestampMillis: wall, monotonicNanos: mono, posture: posture,
            snapshotId: snapshotId, screenId: routeId,
            result: capture.result, captureDurationMs: capture.durationMs, webView: wv)
        guard probe.utf8.count <= 65_536 - 512 else { return snap }
        _ = sink.enqueue { assigned in
            Data(RnEnvelope.viewHierarchy(
                id: id, eventId: eventId, sequence: assigned,
                timestampMillis: wall, monotonicNanos: mono, posture: posture,
                snapshotId: snapshotId, screenId: routeId,
                result: capture.result,
                captureDurationMs: capture.durationMs, webView: wv).utf8)
        }
        return snap
    }

    public func lifecycle(_ transition: String) -> Result {
        guard RnBridge.LIFECYCLE_TRANSITIONS.contains(transition) else { return Result(.invalidInput) }
        resolvePendingCaptureOutcomes()
        // BACKGROUNDING ABANDONS AN IN-FLIGHT ATTEMPT, IT DOES NOT TIME IT
        // OUT. Once the app is backgrounded, "no host appeared" stopped being
        // evidence about the ROUTE and became evidence about the APP — a
        // verdict drawn from it would describe the wrong subject. The Android
        // twin has always done this; iOS only flushed the transport.
        if transition == "background" {
            attachDeadline?.cancel()
            lock.lock(); let abandoned = pendingOutcomes; pendingOutcomes = []; lock.unlock()
            for p in abandoned { routeHosts?.cancel(p.attempt); droppedStaleOutcomes += 1 }
            if !abandoned.isEmpty { trace?("rh_abandoned_on_background count=\(abandoned.count)") }
        }
        let r = emit("mobile_lifecycle", neverRecord: false) {
            [("transition", .string(transition))]
        }
        // Backgrounding is the one moment iOS reliably gives us to ask for a
        // drain. It is a HINT: the transport decides whether to act.
        if transition == "background" || transition == "inactive" {
            sink.flushHint(transition)
        }
        return r
    }

    public func state() -> String { sink.stateName() }

    /// Canonical snapshotId: generated NATIVELY per snapshot, 37 chars against
    /// the schema's 64-char bound. Never JS-supplied — an identifier a caller
    /// chooses is an identifier a caller can collide.
    static func newSnapshotId() -> String {
        "snap_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    private func emit(_ eventType: String, neverRecord: Bool,
                      webView carrier: (() -> RnEnvelope.WebViewRef?)? = nil,
                      _ extra: () -> [(String, RnEnvelope.Value)]) -> Result {
        guard let id = identity() else {
            return Result(configured() ? .noSession : .notConfigured)
        }
        return emitWith(id, eventType, neverRecord: neverRecord, webView: carrier, extra)
    }

    private func emitWith(_ id: RnEnvelope.Identity, _ eventType: String, neverRecord: Bool,
                          webView carrier: (() -> RnEnvelope.WebViewRef?)? = nil,
                          _ extra: () -> [(String, RnEnvelope.Value)]) -> Result {
        // The clocks are read ONCE, here, and the same values go into the bytes
        // whichever sequence the durable path assigns. Reading them inside the
        // builder would make a retry of the same logical event carry a
        // different timestamp than the one the caller observed.
        return emitAt(id, eventType, neverRecord: neverRecord,
                      wallMillis: clock.wallMillis(), monotonicNanos: clock.monotonicNanos(),
                      webView: carrier, extra)
    }

    /// AN OBSERVED EVENT CARRIES THE CLOCK OF ITS OBSERVATION, NOT OF ITS
    /// ENQUEUE. A touch is timestamped when the finger moved; stamping it when
    /// the durable queue got round to it would place the ripple at the wrong
    /// moment in the replay -- late by however long the app was busy, which is
    /// exactly when a user is most likely to be tapping. The two clocks agree
    /// closely enough that a wrong choice looks right until the device is under
    /// load, which is the only time it matters.
    /// an earlier change — the iOS twin of Android's an earlier change
    /// `RnInteractionCapture.carrierFor`: the host's carrier rides an
    /// interaction ONLY when the touch landed on web content.
    ///
    /// The holder's carrier answers "is a segment live for this session", so
    /// without this gate every tap on every screen of a session with a live
    /// segment carried it (an earlier change, driven through the real server: a tap on
    /// `accounts_native` was stored WebView-attributed and registered a
    /// segment naming that native screen). `mobile_interaction` carries no
    /// coordinate under the strict posture, so no consumer can undo it — the
    /// hit test exists only at capture time.
    ///
    /// Returns a PROVIDER, still read at emit time. Outside web content
    /// the host is NEVER ASKED and the row stays UNATTRIBUTED (an earlier change's third
    /// state) — never NATIVE, never WEBVIEW. Inside, the host's answer passes
    /// through unchanged: nothing here mints, caches or substitutes a carrier.
    static func carrierFor(insideWebContent: Bool,
                           webViewRef: (() -> RnEnvelope.WebViewRef?)?)
    -> (() -> RnEnvelope.WebViewRef?)? {
        insideWebContent ? webViewRef : nil
    }

    /// an earlier change — the SCREEN-level twin of `carrierFor`: a
    /// navigation / snapshot / hierarchy event carries the host's segment ONLY
    /// while the screen it describes holds web content. `nil` (never
    /// observed) is NOT web content. Deliberately a separate function: a tap
    /// is decided by where the finger landed, a screen by what it contains.
    static func screenCarrierFor(screenHoldsWebContent: Bool?,
                                 webViewRef: (() -> RnEnvelope.WebViewRef?)?)
    -> (() -> RnEnvelope.WebViewRef?)? {
        screenHoldsWebContent == true ? webViewRef : nil
    }

    private func observeScreen(routeId: String, neverRecord: Bool, capture: HierarchyCapture?) {
        lock.lock(); defer { lock.unlock() }
        if neverRecord { screenWebContent[routeId] = false; return }
        if let c = capture { screenWebContent[routeId] = c.result.webViewSurfaceCount > 0 }
    }

    private func screenCarrier(routeId: String) -> (() -> RnEnvelope.WebViewRef?)? {
        lock.lock(); let holds = screenWebContent[routeId]; lock.unlock()
        return RnBridge.screenCarrierFor(screenHoldsWebContent: holds, webViewRef: webViewRef)
    }

    /// Diagnostics/tests: the last observed web-content fact for a route.
    func observedScreenHoldsWebContent(routeId: String) -> Bool? {
        lock.lock(); defer { lock.unlock() }
        return screenWebContent[routeId]
    }

    private func emitAt(_ id: RnEnvelope.Identity, _ eventType: String, neverRecord: Bool,
                        wallMillis: Int64, monotonicNanos: Int64,
                        webView carrier: (() -> RnEnvelope.WebViewRef?)? = nil,
                        _ extra: () -> [(String, RnEnvelope.Value)]) -> Result {
        let body = extra()
        let wall = wallMillis
        let mono = monotonicNanos
        let eventId = ids.newEventId()
        let posture = RnEnvelope.Posture(allowedViewCount: 0, screenNeverRecord: neverRecord)
        // MODEL B — asked ONCE per event, HERE, beside the clocks and for the
        // same reason: the value that goes into the bytes must be the one
        // observed at the moment the event was emitted, whichever sequence the
        // durable path later assigns. Asking inside the builder would let a
        // retry of the same logical event carry a correlation the original did
        // not have — or lose one it did.
        // an earlier change + an earlier change: EVERY event names its own gated
        // provider. An interaction is gated on the hit test, a screen-level
        // event on the screen's web-content fact, and session_start /
        // lifecycle pass nothing — so no event inherits the session's carrier
        // by default. The local deliberately SHADOWS the property, so the one
        // read below is still the only place an event asks for its carrier.
        let webViewRef = carrier
        let wv = webViewRef?()
        guard let seq = sink.enqueue({ assigned in
            Data(RnEnvelope.envelope(id: id, eventId: eventId, eventType: eventType,
                                     sequence: assigned, timestampMillis: wall,
                                     monotonicNanos: mono, posture: posture,
                                     extra: body, webView: wv).utf8)
        }) else {
            // Not durable, therefore not recorded. Saying RECORDED here would
            // be the same class of lie as publishing a session with no opener.
            return Result(.notConfigured)
        }
        return Result(.recorded, sequence: seq)
    }
}

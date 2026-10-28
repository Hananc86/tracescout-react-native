import Foundation
#if canImport(UIKit)
import UIKit
#endif
#if os(iOS)
import BackgroundTasks
#endif

/// The iOS transport coordinator: owns the durable store, the session it is
/// currently writing, the drain triggers, and nothing JS can name.
///
/// **iOS recovery is NOT equivalent to Android's.** Android schedules a
/// constrained `WorkManager` job that the OS will run. iOS offers
/// `BGTaskScheduler`, which the system may defer indefinitely — it is a
/// REQUEST, not a guarantee, and it does not run at all if the user
/// force-quits the app. This class therefore treats foreground/background
/// lifecycle as the RELIABLE trigger and BGTaskScheduler as a best-effort
/// supplement. No claim of deterministic or immediate background execution is
/// made anywhere in this file, and none should be made in evidence.
public final class RnTransport {
    public static let shared = RnTransport()

    public enum State: String {
        case notConfigured = "NOT_CONFIGURED"
        case ready = "READY"
        case draining = "DRAINING"
        case retained = "RETAINED"
        case delivered = "DELIVERED"
    }

    public static let backgroundTaskIdentifier = "com.tracescout.rn.drain"

    private let lock = NSLock()
    private var started = false
    private var sequence: Int64 = 0
    private var store: RecordStore?
    private var park: ParkStore?
    /// Phase 2: durable, encrypted store for ALREADY-REDACTED frame bytes.
    private var frames: RecordStore?
    private var lastState: State = .notConfigured
    private var pendingSinceDrain = 0
    /// A session identity that is USABLE, i.e. one whose canonical
    /// `mobile_session_start` has been durably appended for the CURRENT store
    /// generation.
    ///
    /// This type is the fix. The previous shape was a bare
    /// `private var sessionId: String?` that nothing ever cleared: a
    /// fixture reset deleted the queue, the parked store, the trace and the
    /// Keychain item while the PROCESS survived, so the id outlived the opener
    /// that made it valid. The next records — emitted by React's mount effects
    /// before `startSession` — were stamped with it, the drained slice
    /// therefore carried no opener, and the server correctly refused to conjure
    /// a session with no beginning. Head-of-line starvation followed, forever.
    ///
    /// Clearing the field in more places would have been a list of sites to
    /// remember. The GENERATION makes stale identity unrepresentable instead:
    /// every store open or configuration replace bumps it, and an
    /// `ActiveSession` from an older generation can never be returned, even by
    /// a path that forgets to clear anything.
    private struct ActiveSession {
        let identity: RnEnvelope.Identity
        /// Proof the opener for THIS identity reached the durable queue.
        let openerDurable: Bool
        /// The store/config generation the opener was written under.
        let generation: UInt64
        /// Monotonic moment this session became the active one. Half of the
        /// network capture eligibility epoch: a request that started before it
        /// belongs to an earlier session, never to this one.
        let startedMonotonicNanos: Int64
    }
    private var active: ActiveSession?
    private var generation: UInt64 = 0
    /// NETWORK CAPTURE IS OFF BY DEFAULT AND HAS NO JS SURFACE. It mirrors
    /// the Android internal configuration boundary: a native host-app decision,
    /// never a value React Native can set — a JS-settable capture switch would
    /// let application code turn on collection the host app never authorised.
    private var networkCaptureEnabled = false
    /// Whether the persisted opt-in has been APPLIED in this process. Latched on
    /// the first configured `start` and never cleared, so "what the host app
    /// persisted" and "what this process is running with" are two readable
    /// facts instead of one assumed one.
    private var networkOptInApplied = false
    /// Network records that reached the ONE shared durable append in this
    /// process. A bare count: it cannot describe a request, and it says nothing
    /// about whether a server ever accepted them.
    private var networkRecordsAppended = 0
    private var networkEnabledMonotonicNanos: Int64 = 0
    /// Bumped on every enable, so an off→on cycle is a NEW epoch even within
    /// one session and a request straddling the gap is refused.
    private var networkEpochCounter: UInt64 = 0
    /// Which screen was current when, so a network request completing at time T
    /// is attributed to the screen that covered its START rather than to
    /// whatever is on screen by the time the metrics callback fires. Owned here
    /// because the session owns it: `publishSession` resets it, so an interval
    /// can never cross a session boundary.
    private let screenTimeline = NetworkScreenTimeline()
    // `private var draining = false` WAS HERE AND IS DELIBERATELY GONE.
    // Build 29 replaced the single drain mutex with the TWO independent
    // lane owners below, because one bool let a suspended record request
    // head-of-line block the frame queue and swallow every later trigger.
    // This is the ONE field where the Build-29 mechanism SUPERSEDES
    // master's rather than sitting beside it; every other field on both
    // sides is kept.
    /// The first-pending obligation. ONE at a time, and a later record never
    /// postpones it — see `FlushSchedule`.
    private var deadlineArmed = false
    private var deadlineGeneration: UInt64 = 0
    let flushSchedule = FlushSchedule()
    /// TWO INDEPENDENT LANES, NOT ONE MUTEX. A single `draining` bool let a
    /// suspended record request head-of-line block the frame queue and drop
    /// every later trigger — see `DrainLaneOwner` for the measured failure.
    /// The record queue and the frame store are separate durable authorities
    /// and neither may wait on the other to finish.
    private var recordOwner = DrainLaneOwner()
    private var frameOwner = DrainLaneOwner()
    /// The frame lane's bounded operation window. ONE window under the ONE
    /// `frameOwner` — see `FrameWindow` for why N owners would re-create the
    /// head-of-line defect inside a single lane.
    private var frameWindow = FrameWindow(width: RnTransport.frameWindowWidth,
                                          operationDeadlineMs: RnTransport.frameOperationDeadlineMs)

    /// The server-session prerequisite the frame lane depends on.
    ///
    /// IT IS NOT BEHIND `lock`. `SessionRegistry` carries its own lock, and
    /// the frame pump consults it from inside `drainFramesWindowed`'s critical
    /// section — nesting it under this one would be a lock-order edge for a
    /// value that is a single word. It is also read from the record lane's
    /// worker, which holds no transport lock at all.
    private let sessionRegistry = SessionRegistry()

    /// READ-ONLY view for Diagnostics. There is deliberately no setter and no
    /// bridge method: a JS-settable registration state would let a caller
    /// declare a server prerequisite satisfied.
    public func sessionRegistrationSnapshot() -> SessionRegistry.Snapshot {
        sessionRegistry.snapshot()
    }

    /// How long a lane may be considered owned without a sign of life.
    ///
    /// SIZED AGAINST ONE UNIT OF WORK, NOT ONE PASS. A pass can post many
    /// records or frames sequentially, so the worker RENEWS around each unit;
    /// the lease only has to comfortably exceed a single attempt. The frame
    /// poster bounds one attempt at `timeoutInterval = 30` with a 40 s wait, and
    /// the chunk poster at 20/30 — so 90 s leaves a wide margin above any
    /// healthy attempt while still letting a lost one be reclaimed within a
    /// session. Too short preempts healthy work and the duplicates stop being
    /// bounded in practice; too long is the defect this replaces.
    static let laneLeaseMs: Int64 = 90_000

    /// Bounded attempts at a LOCAL archival move before the lane is released.
    ///
    /// Small on purpose. A park that keeps failing is a disk condition, not
    /// something more attempts fix — and the bytes are safe either way, because
    /// nothing is removed until the parked copy verifies. The next ordinary
    /// trigger resumes it from the durable terminal descriptor.
    static let maxLocalArchiveAttempts = 3

    /// THE OPERATION DEADLINE IS NOT THE LANE LEASE, AND MUST NOT BE.
    ///
    /// The lease (90 s) bounds how long the LANE may be considered owned without
    /// a sign of life. This bounds how long ONE of the N window slots may be
    /// held by a request that is not coming back. Using the lease here would let
    /// a single dead operation hold 1/N of the throughput for a minute and a
    /// half — which at N=4 is a 25 % capacity loss for 90 seconds.
    ///
    /// IT IS NOT p90 EITHER. Cancelling at p90 (1314 ms measured) aborts one
    /// healthy request in ten, and every abort costs a retry and a duplicate
    /// delivery — trading throughput for exactly the work the window exists to
    /// avoid. The deadline belongs far above the healthy tail and far below the
    /// lease. The value is SELECTED BY MEASUREMENT in
    /// `FrameThroughputBenchmarkTests`, over candidates spanning the practical
    /// range, on the criteria the benchmark reports: healthy timeout rate,
    /// duplicate rate, retry rate, delivered fps, delivery lag and store depth.
    ///
    /// IT CANCELS NOTHING. Freeing the slot and fencing the operation out is
    /// all it does; the transport's own `timeoutInterval` still bounds the HTTP
    /// attempt. A late completion is still delivered and is still refused by
    /// `FrameWindow.accepts`, which is why correctness never depends on a
    /// cancellation actually taking effect.
    static let frameOperationDeadlineMs: Int64 = 5_000

    /// THE WIDTH IS A MEASURED CHOICE, NOT A GUESS, AND N=4 IS NOT ASSUMED.
    ///
    /// The sequential drain's ceiling is `1 / T2` — measured p50 768 ms, so
    /// 1.30 fps against a ~5.5 fps producer. At p50 a window of N reaches
    /// `N / 0.768`: N=3 3.91, N=4 5.21, N=5 6.51, N=6 7.81 fps. N=4 is BELOW
    /// the producer before tail latency, retries, settlement cost or
    /// contention, so the earlier "N=4 is sustainable" result — which belonged
    /// to a ~4.33 fps producer model — does not carry over.
    /// `FrameThroughputBenchmarkTests` measures 3/4/5/6 on this implementation
    /// and selects the smallest N that sustains the producer with real
    /// headroom, a steady store depth and zero healthy-envelope evictions.
    static let frameWindowWidth = 6

    /// WALL CLOCK, DELIBERATELY. `DispatchTime` is monotonic and does NOT
    /// advance while iOS has the process suspended — which is why the posters'
    /// own semaphore timeouts could not rescue a parked thread (RN iOS 0.3.0
    /// (23): 40 min 51 s). A resumed app must find an abandoned lease already
    /// expired, so the lease is measured in `Date`.
    private func nowWallMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private init() {}

    // ------------------------------------------------------------- lifecycle

    /// Idempotent. Installs the durable trace FIRST — before the worker hook,
    /// before the queue opens, and NOT behind the started-once latch: a process
    /// losing that race would otherwise run untraced, silently and only
    /// sometimes.
    @discardableResult
    public func start() -> State {
        let dir = RnTransport.supportDirectory()
        RecoveryTrace.shared.install(directory: dir)
        RecoveryTrace.shared.log("rn_trace_installed")
        lock.lock()
        let alreadyStarted = started
        started = true
        lock.unlock()
        RecoveryTrace.shared.log("rn_start_enter", [("restart", alreadyStarted ? "true" : "false")])

        #if canImport(Security)
        guard let cfg = SecureConfig.load() else {
            setState(.notConfigured)
            RecoveryTrace.shared.log("rn_not_configured")
            return .notConfigured
        }
        #if canImport(CryptoKit)
        let s = EncryptedFileStore(url: dir.appendingPathComponent("tracescout-rn-queue.bin"),
                                   key: cfg.storeKey)
        let p = EncryptedFileStore(url: dir.appendingPathComponent("tracescout-rn-parked.bin"),
                                   key: cfg.storeKey)
        let f = EncryptedFileStore(url: dir.appendingPathComponent("tracescout-rn-frames.bin"),
                                   key: cfg.storeKey)
        lock.lock(); store = s; park = p; frames = f
        // A NEW durable generation. Anything published under the previous one
        // is now unusable by construction, which is exactly what a reset needs.
        generation &+= 1
        active = nil
        // Continue numbering ABOVE anything already durable: a restart that
        // restarted the counter would produce duplicate sequences the server
        // refuses for the whole batch.
        sequence = (s.readRecords().map { BatchIdentity.sequenceOf($0) }.max() ?? -1) + 1
        lock.unlock()
        RecoveryTrace.shared.log("queue_opened", [("count", String(s.readRecords().count))])
        #endif
        setState(.ready)
        // THE PRODUCTION WIRING. Before this existed the observer was
        // installed at image load, fired on every real request, and had a nil
        // sink — so the shipped SDK could not produce a single durable network
        // record, while both simulator harnesses looked green because THEY
        // supplied the sink. Installation is the image-load concern and is not
        // repeated here; this only hands the already-installed observer its
        // real authority and its real destination.
        configureNetworkObserver()
        // The persisted native opt-in is applied HERE and nowhere else, so a
        // cold launch honours what the host app last decided. Absent == false:
        // this line can only ever turn capture on when the host app said so.
        //
        // ONCE PER PROCESS. `start` also runs on every fixture Save, so an
        // unlatched apply flipped capture mid-process on a reconfigure — while
        // the screen that flipped it had no way to say which value the running
        // process was actually using. The opt-in is a STARTUP setting: a change
        // persists immediately and takes effect at the next launch, and
        // `networkCaptureStatus` reports the two values separately so
        // "restart required" is a measured state, never a caption.
        lock.lock()
        let firstApply = !networkOptInApplied
        networkOptInApplied = true
        lock.unlock()
        if firstApply { setNetworkCaptureEnabled(SecureConfig.networkCaptureEnabled()) }
        observeLifecycle()
        // Recovery is anchored HERE, in start, before the new session's first
        // event — never in a public "recoverPreviousProcess" entry point whose
        // only caller in existence would be a test app.
        requestDrain(reason: "start")
        return .ready
        #else
        setState(.notConfigured)
        return .notConfigured
        #endif
    }

    // --------------------------------------------------- network capture

    /// The native opt-in. OFF by default; there is no JS path to it.
    ///
    /// Enabling starts a NEW eligibility epoch, so a request already in flight
    /// when capture is switched on is refused rather than back-dated into it.
    public func setNetworkCaptureEnabled(_ on: Bool) {
        lock.lock()
        let changed = (networkCaptureEnabled != on)
        networkCaptureEnabled = on
        if on && changed {
            networkEnabledMonotonicNanos = Int64(DispatchTime.now().uptimeNanoseconds)
            networkEpochCounter &+= 1
        }
        lock.unlock()
        if changed { RecoveryTrace.shared.log("rn_net_capture", [("enabled", on ? "true" : "false")]) }
    }

    public func isNetworkCaptureEnabled() -> Bool {
        lock.lock(); defer { lock.unlock() }; return networkCaptureEnabled
    }

    /// Record that `screenId` became the current screen NOW.
    ///
    /// Called from the RN bridge wherever a route becomes current. Stamped here
    /// rather than by the caller so the entry uses the same clock read the
    /// producer projects request starts into — a caller-supplied timestamp is a
    /// second source for one fact.
    ///
    /// TAKES NO TRANSPORT LOCK. `NetworkScreenTimeline` is a leaf: it calls
    /// nothing back, so `publishSession` may reset it while holding `lock`
    /// without a lock-order cycle. Acquiring `lock` here would put the screen
    /// path behind every drain and enqueue for a fact none of them read.
    @discardableResult
    public func noteScreen(_ screenId: String) -> Bool {
        screenTimeline.note(screenId: screenId,
                            atMonotonicNanos: Int64(DispatchTime.now().uptimeNanoseconds))
    }

    /// The screen that covered `monotonicNanos`, or nil. The observer's
    /// `screenAt` is bound to this.
    func screenAtMonotonic(_ monotonicNanos: Int64) -> String? {
        screenTimeline.screen(at: monotonicNanos)
    }

    /// The authority the observer consults at completion.
    ///
    /// The activation boundary is the LATER of "capture was enabled" and "this
    /// session began" — both must already be true for a request to be eligible,
    /// so the later of the two is when eligibility actually started. Taking the
    /// earlier one would admit a request from before the session existed.
    func networkAuthority() -> RnNetworkObserver.CaptureAuthority? {
        lock.lock(); defer { lock.unlock() }
        guard networkCaptureEnabled,
              let a = active, a.openerDurable, a.generation == generation else { return nil }
        return RnNetworkObserver.CaptureAuthority(
            enabled: true,
            activatedMonotonicNanos: max(networkEnabledMonotonicNanos, a.startedMonotonicNanos),
            epochToken: "\(a.identity.sessionId)#\(networkEpochCounter)#\(a.generation)")
    }

    /// CALLED FROM `start` AND NOWHERE ELSE. It is idempotent by
    /// construction — it replaces two closures — and it installs no selector:
    /// installation is the image-load concern, and a second start must not
    /// add a second method to React Native's class.
    private func configureNetworkObserver() {
        RnNetworkObserver.configure(
            authority: { RnTransport.shared.networkAuthority() },
            sink: { event in RnTransport.shared.appendNetworkEvent(event) },
            screenAt: { startMono in RnTransport.shared.screenAtMonotonic(startMono) })
    }

    /// The production sink: canonical projection -> canonical envelope -> the
    /// ONE shared durable append.
    ///
    /// NO NETWORK-SPECIFIC SEQUENCE GENERATOR, QUEUE, STORE OR UPLOADER.
    /// `enqueue` assigns the sequence and appends inside the single existing
    /// critical section, so a network record is numbered by exactly the same
    /// authority as every other record — two unreconciled sources for one
    /// sequence is the defect that made the server refuse whole batches.
    func appendNetworkEvent(_ event: NetworkCaptureCore.NetworkEvent) {
        guard let id = currentIdentity() else { return }
        let wall = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        let mono = Int64(DispatchTime.now().uptimeNanoseconds)
        let eventId = UUID().uuidString.lowercased()
        // A network event describes a request, not a view tree: there is no
        // captured view to count and nothing to declare never-record about.
        let posture = RnEnvelope.Posture(allowedViewCount: 0, screenNeverRecord: false)
        let appended = enqueue { assigned in
            Data(RnEnvelope.networkRequest(
                id: id, eventId: eventId, sequence: assigned,
                timestampMillis: wall, monotonicNanos: mono,
                posture: posture, event: event).utf8)
        }
        // Counted ONLY on a successful durable append — a failed append is not
        // a produced record, and counting it would let the status below claim
        // local production that never happened.
        if appended != nil {
            lock.lock(); networkRecordsAppended += 1; lock.unlock()
        }
    }

    /// A read-only, native-only snapshot of LOCAL network-capture state.
    ///
    /// EVERYTHING HERE IS A FACT ABOUT THIS PROCESS. Nothing in it is a
    /// statement about a server: a record counted in `recordsAppendedSinceProcessStart` has
    /// been appended to the durable queue and may still be refused, retried or
    /// never uploaded. Not exported to JS and not `@objc`.
    public func networkCaptureStatus() -> NetworkCaptureLocalState.Inputs {
        lock.lock()
        let applied = networkCaptureEnabled
        let evaluated = networkOptInApplied
        let queued = networkRecordsAppended
        lock.unlock()
        // The installer's OWN verdict, never `respondsToSelector`: a class that
        // responds because React Native implemented the selector itself is the
        // REFUSED case, and would read as installed.
        let outcome = RnNetworkObserver.installOutcome()
        let installed = (outcome == .installed || outcome == .alreadyDone)
        return NetworkCaptureLocalState.Inputs(
            persistedOptIn: SecureConfig.networkCaptureOptInPersisted(),
            appliedThisProcess: applied,
            optInEvaluatedThisProcess: evaluated,
            observerInstalled: installed,
            observerCallbacks: RnNetworkObserver.observedCallbackCount(),
            recordsAppendedSinceProcessStart: queued)
    }

    static func supportDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = base.appendingPathComponent("tracescout-rn", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                 attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        return dir
    }

    private func setState(_ s: State) {
        lock.lock(); lastState = s; lock.unlock()
    }

    public func state() -> State {
        lock.lock(); defer { lock.unlock() }
        return lastState
    }

    // --------------------------------------------------------------- session

    /// Reads the CURRENT session identity. Read-only: it never creates one, so
    /// a headless session (records with no opener) is unrepresentable.
    /// The INVARIANT, in one place: an identity is returned only if its opener
    /// is durably established AND it belongs to the current store generation.
    /// A nil answer makes the caller emit a typed NO_SESSION and write nothing.
    public func currentIdentity() -> RnEnvelope.Identity? {
        lock.lock(); defer { lock.unlock() }
        guard let a = active, a.openerDurable, a.generation == generation else { return nil }
        return a.identity
    }

    /// Mint a CANDIDATE session identity. Deliberately does NOT publish it:
    /// publication is `publishSession(_:)`, and it happens only after the
    /// opener for this exact identity is durably appended. Minting and
    /// publishing were the same act before, which is how an identity could
    /// exist with no opener behind it.
    public func mintCandidateSession(initialRoute: String?) -> RnEnvelope.Identity? {
        #if canImport(Security)
        guard let cfg = SecureConfig.load() else { return nil }
        let millis = Int64(Date().timeIntervalSince1970 * 1000)
        var hex = ""
        for _ in 0..<12 { hex.append("0123456789abcdef".randomElement()!) }
        let sid = "ts_\(millis)_\(hex)"
        return RnEnvelope.Identity(projectId: cfg.projectId, sessionId: sid,
                                   anonymousId: RnTransport.anonymousId(),
                                   environment: cfg.environment,
                                   appIdentifier: RnTransport.appIdentifier(),
                                   initialScreen: initialRoute)
        #else
        return nil
        #endif
    }

    /// Publish a candidate as THE active session. Called only by the bridge,
    /// only after the opener append returned success, and only for the
    /// generation the opener was written under — a generation that has moved
    /// on (a reset between the two) refuses the publication rather than
    /// resurrecting a session whose queue no longer exists.
    @discardableResult
    public func publishSession(_ identity: RnEnvelope.Identity, generation g: UInt64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard g == generation else {
            RecoveryTrace.shared.log("session_publish_stale_generation")
            return false
        }
        active = ActiveSession(identity: identity, openerDurable: true, generation: g,
                               startedMonotonicNanos: Int64(DispatchTime.now().uptimeNanoseconds))
        // Screen ids are session-scoped: an interval recorded under a previous
        // session must not be able to answer for this one.
        screenTimeline.reset()
        // PENDING STARTS HERE, NOT AT THE FIRST FRAME. The opener is durably
        // queued and the server has not acknowledged a chunk carrying it, so
        // from this instant every frame this session captures is addressed to a
        // session row that does not exist yet. Marking it later would leave the
        // frames captured in between eligible — and those are exactly the ones
        // Build 28 lost.
        sessionRegistry.markPending(sessionId: identity.sessionId, nowMs: nowWallMs())
        RecoveryTrace.shared.log("session_registration_pending",
                                 [("sid", String(identity.sessionId.suffix(12)))])
        RecoveryTrace.shared.log("session_begun",
                                 [("sid", String(identity.sessionId.suffix(12)))])
        // THE SESSION START IS BOUND HERE, ONCE. Every capture-decision
        // timestamp is then placeable without depending on a trace line that
        // the 256 KB bound may have dropped — which is exactly how the Build-25
        // completeness verdict came to be computed against the wrong reference.
        // The id carries the device-side start millis by construction
        // (`ts_<millis>_<12hex>`), so there is no second source to disagree.
        Self.captureLog.begin(sessionId: identity.sessionId,
                              sessionStartMs: Self.startMillis(of: identity.sessionId))
        return true
    }

    /// The generation an opener is about to be written under.
    public func currentGeneration() -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        return generation
    }

    /// Invalidate all in-memory session/drain state. Used by the FIXTURE reset
    /// path after it has deleted the durable stores, so capture cannot resume
    /// under an identity whose queue was just destroyed. Not exported to JS.
    public func resetRuntimeState() {
        lock.lock()
        generation &+= 1
        active = nil
        store = nil
        park = nil
        sequence = 0
        pendingSinceDrain = 0
        deadlineArmed = false
        deadlineGeneration &+= 1   // any in-flight deadline belongs to a dead generation
        started = false
        lock.unlock()
        // A destroyed queue must not leave a claim about the server behind it.
        sessionRegistry.reset()
        RecoveryTrace.shared.log("runtime_state_reset")
    }

    /// NATIVE app identity. `Bundle.main.bundleIdentifier` and nothing else —
    /// a JS-settable value would let a producer claim to be a different app.
    static func appIdentifier() -> String {
        Bundle.main.bundleIdentifier ?? "unknown.bundle"
    }

    static func anonymousId() -> String { "rnfixture" }

    public func isConfigured() -> Bool {
        #if canImport(Security)
        return SecureConfig.load() != nil
        #else
        return false
        #endif
    }

    // ----------------------------------------------------------------- queue

    /// Assignment and append in ONE critical section, numbering from 0.
    /// Returns the assigned sequence, or NIL when the record did not reach the
    /// durable queue. A caller that cannot tell the difference cannot claim
    /// anything was established — which is precisely how an opener-less
    /// session became publishable.
    @discardableResult
    public func enqueue(_ build: (Int64) -> Data) -> Int64? {
        lock.lock()
        let assigned = sequence
        sequence += 1
        let s = store
        lock.unlock()
        let bytes = build(assigned)
        guard let st = s, st.append(bytes) else {
            RecoveryTrace.shared.log("enqueue_failed", [("seq", String(assigned))])
            return nil
        }
        // REACHED ONLY ON A SUCCESSFUL APPEND — the failure path above returns
        // before this. A failed append must produce no obligation: there is
        // nothing to deliver, and a timer firing forever on an empty store is an
        // obligation that outlived its subject.
        lock.lock()
        pendingSinceDrain += 1
        let pending = pendingSinceDrain
        let armed = deadlineArmed
        lock.unlock()

        // THE STRANDING FIX. Before this, the ONLY trigger was the batch
        // threshold, so one record appended while the transport was idle and the
        // app foregrounded got no future drain at all — measured on a Release
        // simulator: queueTail 1, 0 rows stored, no upload in the trace. The
        // decision is record-class blind by construction: `FlushSchedule` is
        // never handed an event type and has no way to ask for one.
        let d = flushSchedule.onEnqueued(pending: pending, deadlineArmed: armed)
        // THE DECISION IS MASTER'S, THE DRAIN IS BUILD 29'S. `drainAsync` no
        // longer exists: Build 29 replaced the single drain entry point with
        // `requestDrain`, which drives the record and frame lanes
        // independently through their own `DrainLaneOwner`. Keeping master's
        // call here would have re-introduced the one mutex it removed.
        if d.drainNow { requestDrain(reason: "batch_threshold") }
        if d.armDeadline { armFirstPendingDeadline() }
        return assigned
    }

    static let batchThreshold = 8

    /// Read-only snapshot of the durable queue. See `Diagnostics.durableRecords`.
    func durableRecordSnapshot() -> [Data] {
        lock.lock(); let s = store; lock.unlock()
        return s?.readRecords() ?? []
    }

    /// Arm ONE first-pending obligation.
    ///
    /// IT IS NOT RE-ARMED AND NOT POSTPONED. `deadlineArmed` is the single
    /// source of that fact and the generation makes a deadline from a previous
    /// runtime state unrepresentable — a stale timer firing into a reset
    /// transport is exactly how the Android accounting regression stranded
    /// everything behind it.
    /// Map the PIPELINE's outcome onto the scheduler's disposition.
    ///
    /// PURE AND `static` SO A GUARD CAN CALL IT. The Android twin's mapping
    /// lives in its transport and is pinned by `RnTransportWiringTest`; iOS had
    /// no equivalent, which is exactly how the whole iOS scheduler half shipped
    /// as dead code in the first review — a correct state machine sitting inert
    /// beside production, with every unit test green.
    ///
    /// THE DISPOSITION IS NEVER INFERRED FROM A QUEUE COUNT. `remaining` only
    /// distinguishes "nothing left" from "something left"; WHY it is left comes
    /// from the pipeline, because a count cannot tell retryable bytes from
    /// permanently undeliverable ones — which is the entire defect.
    static func disposition(for outcome: DurableUploadPipeline.Outcome,
                            remaining: Int?) -> FlushSchedule.DrainDisposition {
        if let r = remaining, r <= 0 { return .empty }
        switch outcome {
        case .terminalPending:
            // The retry owed is a LOCAL move. Arming a network deadline for it
            // would be the old flat 2 s loop wearing a new name.
            return .terminalMovePending
        case .terminalBlocked:
            // Preservation capacity exhausted, or no store wired. Retaining the
            // bytes is identical; being owed a retry is not — `ParkStore` has
            // no delete, clear or reactivate path, so its capacity never
            // shrinks and any automatic retry is an infinite local loop.
            return .terminalMoveBlocked
        case .terminalMoved:
            return .terminalMovedAndMoreRemain
        case .retained, .exhausted:
            // Owned by the transport's own retained/background path, not by a
            // second un-backed-off scheduler beside it.
            return .retryableRemaining
        case .idle:
            return .empty
        case .resliced:
            // The queue is INTACT and a smaller batch is already the plan, so
            // this is ordinary deliverable work — never `retryableRemaining`,
            // which would hand it to the backed-off retained path and delay a
            // batch we have already narrowed.
            return .deliverableRemaining
        case .delivered, .parked, .quarantined:
            return .deliverableRemaining
        }
    }

    private func armFirstPendingDeadline() {
        lock.lock()
        if deadlineArmed { lock.unlock(); return }
        deadlineArmed = true
        let gen = deadlineGeneration
        let ms = flushSchedule.firstPendingDeadlineMs
        lock.unlock()
        RecoveryTrace.shared.log("rn_deadline_armed", [("ms", String(ms))])
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + .milliseconds(ms)) { [weak self] in
            guard let me = self else { return }
            me.lock.lock()
            let stale = (gen != me.deadlineGeneration)
            me.deadlineArmed = false
            me.lock.unlock()
            if stale {
                RecoveryTrace.shared.log("rn_deadline_stale")
                return
            }
            // A deadline that fires on an already-empty store is HARMLESS and is
            // recorded as such rather than suppressed — silence here would make
            // a real missed drain and a benign no-op look the same.
            RecoveryTrace.shared.log("rn_deadline_fired")
            me.requestDrain(reason: "first_pending_deadline")
        }
    }

    /// The durable count, or NIL when it could not be read. The distinction is
    /// the whole point: "unknown" must never collapse into "empty".
    private func durableRecordCountOrNil() -> Int? {
        lock.lock(); let s = store; lock.unlock()
        guard let st = s else { return nil }
        return st.readRecords().count
    }

    /// Test-only reads of the obligation state. No production caller.
    var deadlineIsArmedForTesting: Bool {
        lock.lock(); defer { lock.unlock() }; return deadlineArmed
    }


    /// `integrity` IS THE ONLY LOCAL SYMPTOM OF A WEDGE. Under the
    /// fail-closed rule a corrupt store keeps serving reads and refuses every
    /// destructive operation, so `count` and `bytes` look entirely healthy
    /// while nothing ever drains — the queue simply stops going down. Reporting
    /// it beside the counts is what turns "the numbers are stuck" into a named
    /// class of damage, on the device, without a wire field.
    public func queueStatus() -> (count: Int, bytes: Int, integrity: StoreIntegrity) {
        lock.lock(); let s = store; lock.unlock()
        guard let st = s else { return (0, 0, .ok) }
        return (st.readRecords().count, st.byteSize, st.integrity())
    }

    public func parkStatus() -> ParkStatus {
        lock.lock(); let p = park; lock.unlock()
        return p?.status() ?? ParkStatus(batches: 0, records: 0, bytes: 0)
    }

    // ---------------------------------------------------------------- drains

    /// Phase 2: store an ALREADY-REDACTED, encoded frame for upload. Native only;
    /// refused when the store cap would be exceeded.
    public func enqueueFrame(_ header: FrameHeader, bytes: Data) -> Bool {
        lock.lock(); let f = frames; lock.unlock()
        guard let st = f else { return false }
        let records = st.readRecords()
        let record = FrameRecord.encode(header, bytes: bytes)

        // THIS USED TO BE DROP-NEWEST, AND BUILD 22 MEASURED WHAT THAT COST:
        // 357 fresh enqueue failures, all `store_cap`, against a store 97% full
        // of a September session whose frames the server had ALREADY ACCEPTED
        // (112 of its posts came back 202 duplicate). The session actually being
        // certified could not get a single frame in. Refusing the newest frame
        // sacrifices the only evidence that can still describe what the user is
        // doing now, in favour of evidence that is finished and often already
        // delivered.
        let entries = records.map { r -> FrameFreshness.Entry in
            FrameFreshness.Entry(sessionId: FrameRecord.decode(r)?.0.sessionId,
                                 bytes: r.count)
        }
        // PASSIVE ACCOUNTING — the ENQUEUED and PENDING quantities, recorded
        // after the admission decision on every exit, with the store occupancy
        // read at that moment. `records.count` is the occupancy the decision
        // saw; an eviction below narrows it before the append.
        var pendingRecords = records.count
        func account(_ ok: Bool) {
            Self.captureLog.recordEnqueue(bytes: bytes.count, ok: ok,
                                          pendingBytes: st.byteSize,
                                          pendingRecords: pendingRecords)
        }
        switch FrameFreshness.admit(entries: entries,
                                    currentSessionId: currentIdentity()?.sessionId,
                                    incomingBytes: record.count,
                                    capBytes: FrameBudgets.storeCapBytes,
                                    overhead: 512) {
        case .refuse(let reason):
            RecoveryTrace.shared.log("rn_frame_enqueued", [("ok", "false"), ("reason", reason)])
            account(false)
            return false

        case .admit:
            break

        case .evictThenAdmit(let evictions):
            let drop = Set(evictions.flatMap(\.indices))
            let keep = records.indices.filter { !drop.contains($0) }
            // THE EVICTION MUST COMMIT BEFORE THE APPEND. If the rewrite
            // fails we refuse rather than append into a store we could not make
            // room in — an append that pushes past the cap is the bound this
            // whole policy exists to hold.
            // AN UNTRUSTWORTHY FRAME STORE REFUSES EVICTION, AND THE NEW
            // FRAME IS THEN REFUSED TOO. That is the deliberate direction: the
            // alternative is destroying old frames on a positional plan
            // computed from a read that already lost records. Old frames are
            // not destroyed; the newest one is simply not admitted, which is
            // visible and recoverable.
            guard st.rewrite(keepingIndices: keep) else {
                RecoveryTrace.shared.log("rn_frame_enqueued",
                                         [("ok", "false"), ("reason", "store_evict_failed"),
                                          ("integrity", st.integrity().rawValue)])
                account(false)
                return false
            }
            pendingRecords = keep.count
            // A real loss event. It is traced as its own thing so it can never
            // be read as successful delivery, and `relation` says whether we
            // sacrificed a finished session or our own live evidence.
            for e in evictions {
                RecoveryTrace.shared.log("rn_frame_evicted", [
                    ("reason", "store_cap_freshness"),
                    ("relation", e.relation.rawValue),
                    ("records", String(e.records)),
                    ("bytes", String(e.bytes))])

                // AN AGGREGATE CANNOT BE CORRELATED TO A DECLARATION. The
                // metadata event for a frame reaches the server on the record
                // lane, so the server holds a `pending` row for a frame whose
                // bytes this line just discarded — and until now nothing on
                // either side could name WHICH. Without identity, "144 pending"
                // and "some frames were evicted" are two facts that can never be
                // joined, and a capacity claim stays unmeasurable.
                //
                // Identity only — session, frame id, byte length. No content, no
                // digest preimage, no URL: the same rule the park manifest keeps.
                let cap = 32
                for (n, i) in e.indices.enumerated() {
                    guard n < cap else {
                        RecoveryTrace.shared.log("rn_frame_evicted_truncated", [
                            ("relation", e.relation.rawValue),
                            ("omitted", String(e.indices.count - cap))])
                        break
                    }
                    guard i < records.count, let (h, b) = FrameRecord.decode(records[i]) else {
                        // Unreadable is its own answer, never silence: a record
                        // we could not decode was still evicted.
                        RecoveryTrace.shared.log("rn_frame_evicted_record", [
                            ("relation", e.relation.rawValue), ("decoded", "false")])
                        continue
                    }
                    RecoveryTrace.shared.log("rn_frame_evicted_record", [
                        ("relation", e.relation.rawValue),
                        ("sessionId", h.sessionId),
                        ("visualFrameId", h.visualFrameId),
                        ("byteSize", String(b.count)),
                        ("decoded", "true")])
                }
            }
        }

        let ok = st.append(record)
        RecoveryTrace.shared.log("rn_frame_enqueued", [("ok", ok ? "true" : "false"), ("bytes", String(bytes.count))])
        if ok { pendingRecords += 1 }
        account(ok)
        return ok
    }

    /// Safe frame-store diagnostics (count, bytes, integrity). Native only.
    ///
    /// `integrity` IS WHY A WEDGE IS DIAGNOSABLE FROM THE DEVICE. Under the
    /// fail-closed rule a corrupt store keeps serving reads and refuses every
    /// destructive operation, so `count` and `bytes` look entirely healthy
    /// while nothing ever drains. Without this field the only observable
    /// symptom is a number that stops going down.
    public func framesStatus() -> (count: Int, bytes: Int, integrity: StoreIntegrity) {
        lock.lock(); let f = frames; lock.unlock()
        let r = f?.readRecords() ?? []
        return (r.count, r.reduce(0) { $0 + $1.count }, f?.integrity() ?? .ok)
    }


    /// Build 26 — the durable capture-decision summary, beside the trace.
    ///
    /// ITS OWN ARTIFACT, deliberately. `RecoveryTrace` is bounded at 256 KB
    /// and drops its OLDEST HALF on overflow; the Build-25 block proved that in
    /// the field, with a window that began 57 s into a 111 s session. An answer
    /// to "why did capture stop" that lives only in trace lines is an answer the
    /// session can throw away.
    /// The device-side session start, read from the id the SDK minted.
    ///
    /// ONE FACT, ONE SOURCE. `ts_<millis>_<12hex>` already carries it, so
    /// storing a second copy would create two values that must agree and can
    /// therefore disagree. Returns -1 when the shape is not the SDK's, which the
    /// completeness rule reports as `unknown` rather than guessing.
    static func startMillis(of sessionId: String) -> Int64 {
        let parts = sessionId.split(separator: "_")
        guard parts.count == 3, parts[0] == "ts", let ms = Int64(parts[1]), ms > 0
        else { return -1 }
        return ms
    }

    static func captureSummaryURL() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory,
                                                 in: .userDomainMask,
                                                 appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("tracescout-rn-capture-summary.txt")
    }

    /// BUILD 27 — where the rendered diagnostics block is committed before the
    /// UI ever sees it. Same directory, same naming rule as the summary.
    static func diagnosticsRenderURL() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory,
                                                 in: .userDomainMask,
                                                 appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("tracescout-rn-last-diagnostics-render.txt")
    }

    /// The ONE recorder. Native only — no `@objc` export reaches it, so the JS
    /// surface stays exactly six semantic methods.
    ///
    /// BUILD 27 — bound to a BOUNDED history so a session start archives the
    /// outgoing summary instead of destroying it. Build 26 kept one generation
    /// and the only reader (Diagnostics) required a launch, which is what
    /// overwrote it.
    public static let captureLog = CaptureDecisionRecorder(
        store: FileCaptureSummaryStore(url: RnTransport.captureSummaryURL()),
        history: FileCaptureSummaryHistoryStore(summaryURL: RnTransport.captureSummaryURL()))

    static func frameInflightURL() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory,
                                                 in: .userDomainMask,
                                                 appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("tracescout-rn-frame-inflight.json")
    }

    /// The durable BOUNDED SET of un-settled frame operations. A separate file
    /// from the single-flight `frame-inflight.json` on purpose: the old file's
    /// shape is one descriptor, and silently reinterpreting it as a set would
    /// make an upgrade read a stale document as an empty window.
    static func frameWindowURL() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory,
                                                 in: .userDomainMask,
                                                 appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("tracescout-rn-frame-window.json")
    }

    /// Adapts the encrypted frame store to the pure state machine's seam.
    ///
    /// `internal`, NOT `private`, and NOT `public`. The corruption controls
    /// must drive THIS adapter over a real `EncryptedFileStore` — a
    /// hand-written test adapter with the same shape would prove the test
    /// agrees with itself and say nothing about the seam that ships. Widening
    /// to `internal` reaches `@testable import` and adds no public API.
    struct FrameStoreQueue: FrameDeliveryQueue {
        let store: RecordStore
        func readAll() -> [Data] { store.readRecords() }
        /// Straight through to the store. The seam adds no judgement of its
        /// own — a second opinion about trust is a second place for it to be
        /// wrong.
        func integrity() -> StoreIntegrity { store.integrity() }
        @discardableResult func append(_ record: Data) -> Bool { store.append(record) }
        @discardableResult func removeFirst(_ count: Int) -> Int { store.removeFirst(count) }
        /// Removal by identity needs positional removal, and the encrypted
        /// store's ONE existing primitive for that is the same atomic rewrite
        /// the freshness eviction uses — no second mutation path is introduced.
        /// It returns false when the rewrite could not commit, which the state
        /// machine turns into RETAIN rather than a claimed settlement.
        @discardableResult func remove(at index: Int) -> Bool {
            let live = store.readRecords()
            guard live.indices.contains(index) else { return false }
            return store.rewrite(keepingIndices: live.indices.filter { $0 != index })
        }
    }

    /// Drain the durable frame store through the SHARED delivery rules.
    ///
    /// **This method used to be the defect.** It settled on a bare 2xx and it
    /// removed the head record before re-appending the retry copy:
    ///
    /// if (200.299).contains(r.status) { st.removeFirst(1) } // no ack checked
    ///.
    /// st.removeFirst(1); if attempts+1 < max { st.append(rotated) }
    ///
    /// Both are the mistakes the chunk pipeline already paid for, on a queue that
    /// holds keyframes nothing can recreate. The rules now live in
    /// `FrameDelivery` — pure, so process death, a missing acknowledgement and
    /// the retry path are driven under unit tests exactly as they run here.
    /// - Returns: true iff this pass DELIVERED something, so the lane owner
    /// can continue without waiting for another lifecycle trigger. A pass
    /// that delivered nothing releases ownership rather than spinning.
    @discardableResult
    private func drainFrames(cfg: SecureConfig.Config, ticket: DrainLaneOwner.Ticket) -> Bool {
        lock.lock(); let f = frames; lock.unlock()
        guard let st = f else { return false }

        // ADMISSION ALONE IS NOT ENOUGH. Build 22 caught the drain still
        // walking a September session while the new one was already active, so
        // a fresh frame that DID get in would still have sat behind days of
        // historical FIFO. Order the pass current-session-first, each group
        // keeping its own order, and let the unchanged walk continue into the
        // historical records afterwards so they still make progress — priority
        // must never become permanent starvation.
        //
        // `drainOrder` returns nil when the order is already correct, so at
        // steady state this costs nothing rather than rewriting the store on
        // every drain.
        if let sid = currentIdentity()?.sessionId {
            let records = st.readRecords()
            let entries = records.map {
                FrameFreshness.Entry(sessionId: FrameRecord.decode($0)?.0.sessionId, bytes: $0.count)
            }
            if let order = FrameFreshness.drainOrder(entries: entries, currentSessionId: sid) {
                if st.rewrite(keepingIndices: order) {
                    RecoveryTrace.shared.log("rn_frames_prioritised",
                                             [("current", String(entries.filter { $0.sessionId == sid }.count)),
                                              ("other", String(entries.filter { $0.sessionId != sid }.count))])
                } else {
                    // THE DRAIN CONTINUES IN THE EXISTING ORDER. Priority is
                    // an optimisation, not a precondition — refusing to reorder
                    // costs freshness, refusing to drain would cost delivery.
                    // `rn_frames_prioritised` is NOT logged, because that line
                    // asserts a reordering that did not happen.
                    RecoveryTrace.shared.log("rn_frames_prioritise_refused",
                                             [("integrity", st.integrity().rawValue)])
                }
            }
        }
        let poster = HttpFramePoster(endpoint: cfg.endpoint, ingestKey: cfg.ingestKey)
        let out = drainFramesWindowed(store: st, poster: poster, projectId: cfg.projectId,
                                      ticket: ticket)
        if out.delivered + out.duplicate + out.refused + out.retained
            + out.unreadable + out.exhausted > 0 || out.failedClosed {
            RecoveryTrace.shared.log("rn_frames_drained", [
                ("delivered", String(out.delivered + out.duplicate)),
                // `refused` is a record-level rejection; `exhausted` is us giving
                // up. Reporting one as the other would attribute our own
                // give-up to the server, so they stay separate fields.
                ("refused", String(out.refused)),
                ("exhausted", String(out.exhausted)),
                ("retained", String(out.retained)),
                ("unreadable", String(out.unreadable)),
                // The startup-dependency lane, reported separately from every
                // other disposition. `sessionDependencyPending` spent NO normal
                // attempt; `blockedByRegistration` never even reached the wire.
                ("sessionDependencyPending", String(out.sessionDependencyPending)),
                ("blockedByRegistration", String(out.blockedByRegistration)),
                ("coolingDown", String(out.coolingDown)),
                // The lane's half of the fail-closed rule. `unremoved` says
                // settlements were refused; this says WHY, and is present even
                // when a totally unreadable store made the pass look idle.
                ("unremoved", String(out.unremoved)),
                ("storeIntegrity", (out.storeUntrustworthy ?? .ok).rawValue),
                ("failedClosed", out.failedClosed ? "true" : "false")])
        }
        // A COOLING FRAME NEEDS A TRIGGER, NOT A SPIN. The pass goes idle
        // rather than holding the lane through the backoff, so something has to
        // wake it; without this the retained frames would sit until an unrelated
        // lifecycle event. Scheduled once per pass, at the EARLIEST expiry, and
        // coalesced by the lane owner if a drain is already running.
        if let next = out.nextEligibleMs {
            let delay = max(0, next - nowWallMs())
            DispatchQueue.global(qos: .utility)
                .asyncAfter(deadline: .now() + .milliseconds(Int(min(delay, 60_000)))) { [weak self] in
                    self?.requestDrain(reason: "session_dependency_backoff")
                }
        }
        // PROGRESS, NOT ACTIVITY. Only a frame that actually left the store
        // justifies another pass; a pass that merely RETAINED would otherwise
        // spin against the same unreachable server until the bound.
        return out.delivered + out.duplicate > 0
    }

    /// A bound on one windowed pass, so a pathological store cannot make a
    /// single pass unbounded. `finishPass` runs another one if there is more.
    static let maxFrameWindowIterations = 4_096
    /// The longest a pass sleeps when nothing is due. It never sleeps past the
    /// earliest operation deadline — a dead operation would otherwise hold its
    /// slot until some unrelated event happened to wake the loop.
    static let frameWindowPollMs: Int64 = 250

    /// Completions arriving from N concurrent posters.
    ///
    /// `NSCondition.wait(until:)` TAKES A WALL-CLOCK `Date`, deliberately.
    /// `DispatchSemaphore.wait(timeout:)` is MONOTONIC and does not advance
    /// while iOS has the process suspended — the exact reason the posters' own
    /// semaphore timeouts could not rescue a thread parked for 40 min 51 s.
    private final class FrameCompletionInbox {
        /// One completion, with the two instants only the poster thread can
        /// observe. They are carried rather than re-derived because a stage
        /// timing measured on the pump thread would include however long the
        /// completion waited in this inbox — which is not what T1/T2 mean.
        struct Item {
            let ref: FrameOperationRef
            let result: FrameDelivery.PostResult
            let requestStartMs: Int64
            let responseMs: Int64
            /// From the DESCRIPTOR this operation was frozen with, never from
            /// the acknowledgement — a refused completion carries no ack, and a
            /// stage timing that could only be attributed on success would be
            /// missing on exactly the responses worth measuring.
            let visualFrameId: String
        }
        private let cond = NSCondition()
        private var items: [Item] = []

        func push(_ item: Item) {
            cond.lock(); items.append(item); cond.signal(); cond.unlock()
        }

        /// Take everything that has arrived, waiting up to `ms` for the first.
        func drain(waitingUpToMs ms: Int64) -> [Item] {
            cond.lock(); defer { cond.unlock() }
            if items.isEmpty && ms > 0 {
                _ = cond.wait(until: Date().addingTimeInterval(Double(ms) / 1000.0))
            }
            let out = items
            items.removeAll()
            return out
        }
    }

    /// THE WINDOWED FRAME DRAIN — the product path.
    ///
    /// THE DECISIONS ARE NOT HERE. Admission, deadlines, fencing, settlement
    /// and accounting all live in `FrameWindowPump`, which is pure; this method
    /// supplies the concurrency and nothing else. That split is what lets the
    /// throughput benchmark and the fault injection drive the REAL decisions on
    /// a runner with no simulator — and it is stated rather than implied that
    /// the dispatch/URLSession adapter below is the part those tests do not
    /// cover.
    ///
    /// ONE OWNER, ONE GENERATION. `frameWindow` adopts `frameOwner`'s
    /// generation before the first step, so a reclaim drops every operation the
    /// lost owner had outstanding and the new owner starts with a full window.
    /// Ownership is re-read and the lease RENEWED at the top of every
    /// iteration — around each unit of work, never once per pass.
    private func drainFramesWindowed(store st: RecordStore,
                                     poster: HttpFramePoster,
                                     projectId: String,
                                     ticket: DrainLaneOwner.Ticket) -> FrameWindowPump.Metrics {
        let queue = FrameStoreQueue(store: st)
        let opStore = FileFrameWindowStore(url: Self.frameWindowURL())
        let inbox = FrameCompletionInbox()
        let trace: FrameWindowPump.Trace = { e, f in RecoveryTrace.shared.log(e, f) }
        var metrics = FrameWindowPump.Metrics()

        lock.lock()
        frameWindow.beginGeneration(frameOwner.currentGeneration)
        lock.unlock()

        // T4 is "settle -> next freeze", so it needs the previous settlement's
        // instant. -1 means "no predecessor in this pass" and the stage is
        // DROPPED rather than reported as zero.
        var lastSettleMs: Int64 = -1
        // PASSIVE ACCOUNTING — the UPLOADED quantity. The byte size of each
        // started operation, keyed by frame id, so a settlement can be credited
        // with the bytes the server actually took. Read at completion, never
        // inferred from the queue.
        var startedBytes: [String: Int] = [:]

        var iterations = 0
        while iterations < Self.maxFrameWindowIterations {
            iterations += 1

            lock.lock()
            let owns = frameOwner.isCurrent(ticket)
            if owns { _ = frameOwner.renew(ticket, nowMs: nowWallMs(), leaseMs: Self.laneLeaseMs) }
            let stepAtMs = nowWallMs()
            let step = FrameWindowPump.step(queue: queue, window: &frameWindow, store: opStore,
                                            nowMs: stepAtMs,
                                            stillOwnsLane: { owns },
                                            // THE BARRIER. Per frame, so a
                                            // session waiting on its opener ACK
                                            // never blocks another session's.
                                            registration: { [weak self] sid in
                                                self?.sessionRegistry.state(of: sid) ?? .unknown
                                            },
                                            trace: trace)
            FrameWindowPump.account(step, into: &metrics)
            let liveNow = frameWindow.liveCount
            metrics.maxLiveOperations = max(metrics.maxLiveOperations, liveNow)
            let nextDeadline = frameWindow.earliestDeadlineMs()
            lock.unlock()

            if step.laneLost { break }

            for s in step.starts {
                startedBytes[s.descriptor.visualFrameId] = s.bytes.count
                let t4: Int64 = lastSettleMs < 0 ? -1 : max(0, s.descriptor.startedAtMs - lastSettleMs)
                trace("frame_op_frozen_stage", [("visualFrameId", s.descriptor.visualFrameId),
                                                ("t4Ms", String(t4))])
                DispatchQueue.global(qos: .utility).async { [weak self] in
                    let reqAt = self?.nowWallMs() ?? 0
                    let r = poster.post(projectId: projectId, header: s.header, bytes: s.bytes)
                    let respAt = self?.nowWallMs() ?? 0
                    // THE WINDOWED LANE WRITES ITS OWN POST EVIDENCE.
                    // Diagnostics had NO windowed post line at all, which is why
                    // Build 28 reported `currentSession.posts = 0` for a session
                    // that plainly posted. It carries the frame id so it is
                    // ATTRIBUTABLE by identity rather than by "the last freeze",
                    // and the two stage timings only this thread can measure.
                    trace("frame_op_post", [
                        ("visualFrameId", s.descriptor.visualFrameId),
                        ("sessionId", s.descriptor.sessionId),
                        ("status", String(r.status)),
                        ("code", r.code ?? ""),
                        ("attempt", String(s.descriptor.attempt)),
                        ("t1Ms", String(max(0, reqAt - s.descriptor.startedAtMs))),
                        ("t2Ms", String(max(0, respAt - reqAt)))])
                    inbox.push(FrameCompletionInbox.Item(
                        ref: s.ref,
                        result: FrameDelivery.PostResult(status: r.status, ack: r.frameAck,
                                                         code: r.code),
                        requestStartMs: reqAt, responseMs: respAt,
                        visualFrameId: s.descriptor.visualFrameId))
                }
            }

            // Nothing running and nothing startable: the pass is finished. A
            // fail-closed step also ends the pass once its in-flight work has
            // settled — sending more on a store we cannot write to would be the
            // unresolved state this design exists to prevent.
            if liveNow == 0 && (step.idle || step.failedClosed) { break }

            let waitMs: Int64 = {
                guard let d = nextDeadline else { return Self.frameWindowPollMs }
                return min(max(d - nowWallMs() + 1, 1), Self.frameWindowPollMs)
            }()
            for item in inbox.drain(waitingUpToMs: waitMs) {
                let settleAt = nowWallMs()
                lock.lock()
                let disp = FrameWindowPump.complete(ref: item.ref, result: item.result,
                                                    queue: queue,
                                                    window: &frameWindow, store: opStore,
                                                    nowMs: settleAt,
                                                    trace: trace)
                FrameWindowPump.account(disp, into: &metrics)
                lock.unlock()
                trace("frame_op_settle_stage", [
                    ("visualFrameId", item.visualFrameId),
                    ("t3Ms", String(max(0, settleAt - item.responseMs)))])
                lastSettleMs = settleAt
                // Credited ONLY on a settlement the pump itself classified;
                // a retained, refused, fenced or dependency-pending completion
                // uploaded nothing the server will keep.
                let ackedBytes = startedBytes.removeValue(forKey: item.visualFrameId) ?? 0
                switch disp {
                case .delivered: Self.captureLog.recordAcknowledged(bytes: ackedBytes, duplicate: false)
                case .duplicate: Self.captureLog.recordAcknowledged(bytes: ackedBytes, duplicate: true)
                default: break
                }
            }
        }

        // THE STARTUP LANE IS ADDED TO THIS CONDITION DELIBERATELY. The line
        // used to be written only when something timed out or was fenced, so a
        // pass that spent its whole time waiting on a session prerequisite left
        // no window evidence at all — and "no line" is exactly what Build 28's
        // Diagnostics could not distinguish from "no concurrency".
        if metrics.timedOut > 0 || metrics.fenced > 0
            || metrics.sessionDependencyPending > 0 || metrics.blockedByRegistration > 0 {
            // SEPARATE FIELDS. A fenced completion is the fence WORKING; a
            // timed-out operation is a slot that had to be reclaimed. Folding
            // either into `retained` would hide the concurrency events this
            // lane exists to make visible.
            RecoveryTrace.shared.log("rn_frame_window", [
                ("width", String(Self.frameWindowWidth)),
                ("deadlineMs", String(Self.frameOperationDeadlineMs)),
                ("maxLive", String(metrics.maxLiveOperations)),
                ("timedOut", String(metrics.timedOut)),
                ("fenced", String(metrics.fenced)),
                ("reconciled", String(metrics.reconciled)),
                ("sessionDependencyPending", String(metrics.sessionDependencyPending)),
                ("blockedByRegistration", String(metrics.blockedByRegistration))])
        }
        return metrics
    }

    // ── DEBUG-ONLY TEST SEAMS ───────────────────────────────────────────────
    //
    // THESE EXIST BECAUSE THE ADAPTER CANNOT OTHERWISE BE MEASURED, AND THAT
    // GAP WAS A REAL ONE. The throughput benchmark drives the pure reducer, so
    // it proves every DECISION and nothing about the dispatch/URLSession
    // adapter below it — which is where "six operations are actually in flight"
    // either is or is not true. `drainFramesWindowed` is private and the lane
    // owner and window are private state, so a test cannot reach the shipping
    // adapter without a seam.
    //
    // They add NO behaviour: each one calls the same production code with the
    // same state. `#if DEBUG` keeps them out of any release build, and they are
    // named so that no call site could be mistaken for product code.

    /// an earlier change: the record lane's substitutable dependencies. ALWAYS nil in a
    /// release build — only `__testInstallRecordDrainSeam` (DEBUG) writes it.
    struct RecordDrainSeam {
        let projectId: String
        let poster: DurableUploadPipeline.ChunkPoster
    }
    private var recordDrainSeam: RecordDrainSeam?

    #if DEBUG
    /// an earlier change: how many times the record lane reached its external-dependency
    /// resolution (the step that may read the Keychain). DEBUG-only observer.
    private var recordSourceResolutions = 0
    var recordSourceResolutionsForTesting: Int {
        lock.lock(); defer { lock.unlock() }
        return recordSourceResolutions
    }
    func __testResetRecordSourceResolutions() {
        lock.lock(); recordSourceResolutions = 0; lock.unlock()
    }
    #endif

    private func recordDrainSeamSnapshot() -> RecordDrainSeam? {
        #if DEBUG
        lock.lock(); defer { lock.unlock() }
        return recordDrainSeam
        #else
        return nil
        #endif
    }

    #if DEBUG
    /// Drive the PRODUCT's own registration transitions. Not a setter for the
    /// state: `markPending` / `markReady` are the exact two transitions the
    /// product uses, so an adapter test exercises the shipped rule rather than a
    /// state a test invented.
    func __testMarkSessionPending(_ sessionId: String) {
        sessionRegistry.markPending(sessionId: sessionId, nowMs: nowWallMs())
    }

    @discardableResult
    func __testMarkSessionReady(_ sessionId: String) -> Bool {
        sessionRegistry.markReady(sessionId: sessionId, nowMs: nowWallMs())
    }

    func __testResetSessionRegistry() { sessionRegistry.reset() }

    /// an earlier change: route the REAL record lane (`drainRecordsAsync`: real
    /// `DrainLaneOwner`, real 8-pass bound, real abandon, real settle, real
    /// `FlushSchedule`, real deadline timer) at a test store and poster. The
    /// store becomes the transport's durable store, so the settle's count
    /// read sees it too. Bumps the deadline generation so a timer armed by an
    /// earlier test can never fire into this one.
    func __testInstallRecordDrainSeam(store: RecordStore, projectId: String,
                                      poster: @escaping DurableUploadPipeline.ChunkPoster) {
        lock.lock()
        self.store = store
        self.park = nil
        recordDrainSeam = RecordDrainSeam(projectId: projectId, poster: poster)
        deadlineGeneration &+= 1
        deadlineArmed = false
        pendingSinceDrain = 0
        lock.unlock()
    }

    func __testRemoveRecordDrainSeam() {
        lock.lock()
        recordDrainSeam = nil
        self.store = nil
        deadlineGeneration &+= 1
        deadlineArmed = false
        lock.unlock()
    }

    /// Drive the REAL windowed adapter — real `DrainLaneOwner`, real
    /// `FrameWindow`, real `FrameWindowPump`, real `HttpFramePoster`, real
    /// `URLSession`, real `DispatchQueue` concurrency.
    func __testDriveFrameWindow(store: RecordStore,
                                poster: HttpFramePoster,
                                projectId: String) -> FrameWindowPump.Metrics? {
        // ASK WITH THE LEASE ALREADY EXPIRED, THEN RENEW WITH THE REAL CLOCK.
        //
        // A deliberate reclaim (`__testReclaimFrameLane`) orphans its ticket, so
        // the lane can be left owned by nobody; a serial test seam takes it back
        // the same way the product would after a lost owner. But a future-dated
        // request also DATES THE LEASE from that future instant — so the next
        // probe, at a real wall clock, is no longer past the deadline and gets
        // `coalesced` forever. The first version of this seam did exactly that
        // and every adapter test failed to start the lane. Renewing immediately
        // with the REAL clock keeps the deadline anchored to real time.
        lock.lock()
        let grant = frameOwner.request(nowMs: nowWallMs() &+ Self.laneLeaseMs &+ 1_000,
                                       leaseMs: Self.laneLeaseMs)
        let ticket: DrainLaneOwner.Ticket
        switch grant {
        case .started(let t), .reclaimed(let t, _): ticket = t
        case .coalesced: lock.unlock(); return nil
        }
        _ = frameOwner.renew(ticket, nowMs: nowWallMs(), leaseMs: Self.laneLeaseMs)
        lock.unlock()
        let out = drainFramesWindowed(store: store, poster: poster,
                                      projectId: projectId, ticket: ticket)
        lock.lock()
        _ = frameOwner.finishPass(ticket, moreDurableWork: false,
                                  nowMs: nowWallMs(), leaseMs: Self.laneLeaseMs)
        frameOwner.abandon(ticket)
        lock.unlock()
        return out
    }

    /// Force the REAL reclaim path by asking the owner with the lease already
    /// expired. Used to prove that a pass whose lane was taken stops mutating.
    @discardableResult
    func __testReclaimFrameLane() -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        let g = frameOwner.request(nowMs: nowWallMs() &+ Self.laneLeaseMs &+ 1_000,
                                   leaseMs: Self.laneLeaseMs)
        // RE-ANCHOR, THEN ORPHAN. The ticket is deliberately discarded — that
        // IS the lost-owner state this models — but the future-dated request
        // also dated the LEASE from that future instant, so every later probe
        // was inside it and coalesced. That poisoned the shared lane for the
        // rest of the suite: eight adapter tests failed to start it, and the
        // symptom (`could not start the lane`) named the wrong thing entirely.
        switch g {
        case .started(let t), .reclaimed(let t, _):
            _ = frameOwner.renew(t, nowMs: nowWallMs(), leaseMs: Self.laneLeaseMs)
        case .coalesced: break
        }
        return frameOwner.currentGeneration
    }

    /// Return the frame lane to an unowned state between tests. The singleton
    /// is shared, so a test that left the lane owned would make the NEXT test
    /// coalesce and read as a product failure.
    func __testResetFrameLane() {
        lock.lock()
        // Probe with progressively later clocks: a lease left dated into the
        // future by an earlier test must not be able to make the reset itself
        // coalesce, or the lane stays owned for the rest of the suite.
        var g = DrainLaneOwner.Grant.coalesced
        for k in 1...4 {
            g = frameOwner.request(nowMs: nowWallMs() &+ Int64(k) &* (Self.laneLeaseMs &+ 1_000),
                                   leaseMs: Self.laneLeaseMs)
            if case .coalesced = g { continue }
            break
        }
        switch g {
        case .started(let t), .reclaimed(let t, _):
            // Re-anchor the lease to real time before releasing, for the same
            // reason as above: a future-dated deadline left behind would make
            // the next probe coalesce.
            _ = frameOwner.renew(t, nowMs: nowWallMs(), leaseMs: Self.laneLeaseMs)
            frameOwner.abandon(t)
        case .coalesced: break
        }
        lock.unlock()
    }

    /// The number of operations the window currently holds — read-only.
    func __testLiveFrameOperations() -> Int {
        lock.lock(); defer { lock.unlock() }
        return frameWindow.liveCount
    }
    #endif

    /// One reason, both lanes. Each decides for itself whether to start a
    /// worker; neither can refuse the other.
    public func requestDrain(reason: String) {
        RecoveryTrace.shared.log("rn_drain_requested", [("reason", reason)])
        drainRecordsAsync()
        drainFramesAsync()
    }

    // ── the RECORD lane ─────────────────────────────────────────────────────

    private func drainRecordsAsync() {
        lock.lock()
        let grant = recordOwner.request(nowMs: nowWallMs(), leaseMs: Self.laneLeaseMs)
        let s = store
        let p = park
        // THE GENERATION THIS DRAIN BELONGS TO. A drain that settles AFTER a
        // reset must owe nothing: its store has been torn down, so the count it
        // reads is UNKNOWN, and "unknown arms" would re-create an obligation for
        // a queue that no longer exists. Caught by the reset test — the reset
        // cleared the flag and the in-flight drain immediately set it again.
        let drainGeneration = deadlineGeneration
        lock.unlock()
        let ticket: DrainLaneOwner.Ticket
        switch grant {
        case .coalesced: return          // a worker is running and now knows
        case .started(let t): ticket = t
        case .reclaimed(let t, let lost):
            ticket = t
            RecoveryTrace.shared.log("rn_lane_reclaimed",
                                     [("lane", "record"), ("lostGeneration", String(lost))])
        }
        #if canImport(Security)
        // The record lane's three EXTERNAL dependencies — project, upload
        // channel, unresolved-batch descriptor — resolved in ONE place. In a
        // release build this is always the Keychain configuration and the real
        // HTTP poster; a DEBUG test may substitute them so the REAL
        // pass loop, bound, abandon and settle below can be driven end to end.
        // Nothing after this point knows which source it got.
        // an earlier change: the store is checked FIRST, exactly as the predecessor's
        // `guard let st = s, let cfg = SecureConfig.load` short-circuited —
        // no store ⇒ abandon without touching the Keychain.
        guard let st = s else {
            lock.lock(); recordOwner.abandon(ticket); lock.unlock()
            return
        }
        #if DEBUG
        lock.lock(); recordSourceResolutions &+= 1; lock.unlock()
        #endif
        let source: (projectId: String, inflight: InFlightStore,
                     poster: DurableUploadPipeline.ChunkPoster)
        if let seam = recordDrainSeamSnapshot() {
            source = (seam.projectId, MemoryInFlightStore(), seam.poster)
        } else if let cfg = SecureConfig.load() {
            source = (cfg.projectId, FileInFlightStore(url: Self.inflightURL()),
                      { projectId, body in
                          HttpChunkPoster(endpoint: cfg.endpoint, ingestKey: cfg.ingestKey)
                              .post(projectId: projectId, body: body)
                      })
        } else {
            lock.lock(); recordOwner.abandon(ticket); lock.unlock()
            return
        }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            self?.setState(.draining)
            let pipeline = DurableUploadPipeline(
                store: st,
                park: p ?? NoParkStore(),
                poster: source.poster,
                projectId: source.projectId,
                // The unresolved-batch descriptor, durable so the invariant
                // survives process death: a submitted-but-unsettled batch is
                // retried with the SAME bytes and identity, and records
                // appended afterwards cannot extend it. RN iOS 0.2.0 (7)
                // committed sequences 19–26 twice without this.
                inflight: source.inflight,
                // THE BARRIER LIFTS HERE. A chunk for this session was
                // acknowledged, so the server row exists and its frames may
                // spend attempts. The transition fires exactly once, and it
                // triggers a drain: the frames that were waiting are already in
                // the store and would otherwise sit until some unrelated
                // lifecycle event happened to wake the lane.
                onBatchAcknowledged: { [weak self] sid in
                    guard let self else { return }
                    guard self.sessionRegistry.markReady(sessionId: sid,
                                                         nowMs: self.nowWallMs()) else { return }
                    let snap = self.sessionRegistry.snapshot()
                    RecoveryTrace.shared.log("session_registration_ready", [
                        ("sid", String(sid.suffix(12))),
                        ("waitedMs", String(snap.waitedMs))])
                    self.requestDrain(reason: "session_registered")
                })

            // STILL BOUNDED. Independent ownership is not permission to
            // drain forever; the per-lane pass bound is unchanged.
            var outcome = DurableUploadPipeline.Outcome.idle
            var again = true
            var passes = 0
            // Bounded budget for retrying a LOCAL archival move, deliberately
            // separate from `passes` so a hopeless disk condition cannot
            // consume the lane budget ordinary delivery also needs.
            var localArchiveAttempts = 0
            while again && passes < 8 {
                passes += 1
                var guardCount = 0
                outcome = pipeline.drainOnce()
                // A TERMINAL MOVE ADVANCES THE QUEUE, so it continues the
                // pass exactly as a delivery does. Otherwise a healthy session
                // sitting behind an archived dead one would wait for an
                // unrelated future trigger. `guardCount` still bounds it, so a
                // large parked session cannot monopolise the lane.
                //
                // AND THIS IS ALSO THE LOCAL-ARCHIVAL RETRY OWNER. A park
                // that fails returns `.terminalPending` and STOPS this loop;
                // the bounded outer pass loop (`passes < 8`) re-enters the
                // drain, retrying the LOCAL move with no network request. No
                // new timer, and deliberately NOT the network-required
                // background task — a disk-local copy must not wait on radio.
                var advancing = true
                while advancing, guardCount < 64 {
                    switch outcome {
                    case .delivered, .terminalMoved, .resliced:
                        // `.resliced` ADVANCES THE PLAN, not the queue: the
                        // bytes are untouched and the NEXT pass builds a
                        // smaller batch from them. `guardCount` still bounds
                        // it, and the shrink at least halves the admitted
                        // record count each time, so this cannot spin — it
                        // reaches one record and then either delivers or
                        // preserves.
                        guardCount += 1
                        outcome = pipeline.drainOnce()
                    default:
                        advancing = false
                    }
                }
                self?.lock.lock()
                // NOT `pendingSinceDrain = 0`. Zeroing here ERASES records
                // appended WHILE this pass was running: their contribution to
                // the threshold vanishes and, with no deadline armed, they have
                // nothing else to ride. The settled value is read from the
                // STORE after the loop, where an unreadable store is treated as
                // non-empty rather than as empty.
                // MY PREVIOUS COMMENT CLAIMED A MECHANISM THAT DOES NOT
                // EXIST. It said `.terminalPending` "stops the inner loop; the
                // bounded outer pass loop re-enters the drain". It does not:
                // `finishPass(moreDurableWork: false)` with no pending request
                // sets `busy = false` and returns FALSE, so the outer loop
                // EXITS. Combined with the deliberate absence of a network
                // deadline and of the background task, a failed local park got
                // NO retry at all and waited for an unrelated future trigger.
                // A comment asserting a guarantee the code does not provide is
                // worse than no comment.
                //
                // The obligation is now real and bounded: a pending local move
                // asks for one more pass, up to `maxLocalArchiveAttempts`. Each
                // re-entry resumes the park from the DURABLE terminal descriptor
                // and issues ZERO network requests. When the budget is spent the
                // lane is released with NO timer armed, and the next ordinary
                // lifecycle/start trigger picks it up — safe because
                // `terminalReason` is durable.
                //
                // NO SLEEP. This runs on the record lane's queue, and a
                // blocking pause here would hold the lane against unrelated
                // work to wait on a disk condition that a bounded re-entry
                // already gives a second chance.
                var owesLocalRetry = false
                // `.terminalBlocked` DELIBERATELY DOES NOT ENTER HERE. It
                // retains the bytes exactly as `.terminalPending` does, but
                // spending local attempts on a condition with no mechanism to
                // change is the same infinite-loop contract in a smaller box.
                if case .terminalPending = outcome {
                    localArchiveAttempts += 1
                    owesLocalRetry = localArchiveAttempts < Self.maxLocalArchiveAttempts
                    if !owesLocalRetry {
                        RecoveryTrace.shared.log("local_archive_budget_spent",
                            [("attempts", String(localArchiveAttempts))])
                    }
                }
                again = self?.recordOwner.finishPass(ticket, moreDurableWork: owesLocalRetry,
                                                     nowMs: self?.nowWallMs() ?? 0,
                                                     leaseMs: Self.laneLeaseMs) ?? false
                self?.lock.unlock()
            }
            // THE BOUND MUST NOT LEAK OWNERSHIP. Exiting on `passes` while a
            // request is still outstanding would leave the lane owned by a
            // worker that has stopped, and every later trigger would be
            // coalesced into a pass that never runs — the same lost-trigger
            // failure this whole change exists to remove, one level down.
            // an earlier change: `abandon` CLEARS the outstanding request, so the bound
            // exit is remembered and the settle below owes it one deadline.
            let boundAbandoned = again
            if again { self?.lock.lock(); self?.recordOwner.abandon(ticket); self?.lock.unlock() }
            // THE SECOND GAP THE AUDIT FOUND. This used to zero the counter
            // unconditionally, which ERASES records appended WHILE the drain was
            // running: their contribution to the threshold vanished, and with no
            // deadline they had nothing else to ride. The settled state is now
            // read from the STORE, and a store that cannot be read is treated as
            // non-empty rather than as empty.
            let remaining = self?.durableRecordCountOrNil()
            self?.lock.lock()
            self?.pendingSinceDrain = remaining ?? 0
            self?.lock.unlock()
            if let me = self {
                me.lock.lock()
                let stale = (drainGeneration != me.deadlineGeneration)
                me.lock.unlock()
                if stale {
                    RecoveryTrace.shared.log("rn_drain_settled_stale_generation")
                } else {
                    // THE TWO-ARGUMENT PATH. The single-argument overload
                    // delegates to `.deliverableRemaining`, i.e. it tells the
                    // scheduler "whatever happened == ordinary work" — which is
                    // precisely the count-only defect A exists to close. A
                    // pipeline that had just refused to archive a permanently
                    // undeliverable head would still have armed the 2 s network
                    // deadline, forever. The disposition comes from the PIPELINE,
                    // never inferred from a queue count.
                    let disp = Self.disposition(for: outcome, remaining: remaining)
                    let after = me.flushSchedule.onDrainSettled(
                        storeRemaining: remaining, disposition: disp,
                        boundAbandonedRequest: boundAbandoned)
                    if boundAbandoned {
                        RecoveryTrace.shared.log("rn_drain_bound_abandoned_request",
                            [("remaining", remaining.map { String($0) } ?? "unknown"),
                             ("armDeadline", String(after.armDeadline))])
                    }
                    if after.armDeadline { me.armFirstPendingDeadline() }
                    // A terminal head was archived and ordinary work remains:
                    // continue now rather than sleeping 2 s behind a dead
                    // session. `requestDrain` keeps the existing lane bounds.
                    if after.drainNow { me.requestDrain(reason: "terminal_moved_continue") }
                }
            }
            switch outcome {
            case .delivered: self?.setState(.delivered)
            case .retained, .exhausted:
                self?.setState(.retained)
                // Best-effort supplement ONLY — see the class comment.
                self?.scheduleBackgroundDrain()
            case .terminalMoved:
                // The bytes are preserved and the queue advanced. Nothing is
                // owed to the network.
                self?.setState(.ready)
            case .terminalBlocked:
                // Retained, and owed nothing automatic. A later ordinary launch
                // may try once — an app lifecycle event, never a schedule.
                RecoveryTrace.shared.log("park_capacity_blocked_lane_released")
                self?.setState(.retained)
            case .terminalPending:
                // RETAINED, NOT READY. `default:.ready` used to swallow this
                // and report a healthy transport while permanently
                // undeliverable bytes sat at the head with nothing owed to
                // them.
                //
                // AND DELIBERATELY NO `scheduleBackgroundDrain`. That is a
                // NETWORK-REQUIRED BGProcessingTask; making a disk-local copy
                // wait on radio availability would be the wrong owner for the
                // retry. The bounded pass loop above owns it, and the next
                // ordinary trigger owns it after that.
                self?.setState(.retained)
            case .resliced:
                // Nothing was stored and nothing was lost; the transport is
                // healthy and a narrower batch is next. Reporting `.retained`
                // here would say bytes are waiting on a condition outside our
                // control, which is the opposite of what happened.
                self?.setState(.ready)
            case .idle, .parked, .quarantined:
                self?.setState(.ready)
            }
        }
        #else
        lock.lock(); recordOwner.abandon(ticket); lock.unlock()
        #endif
    }

    // ── the FRAME lane ──────────────────────────────────────────────────────

    /// ITS OWN WORKER, ITS OWN OWNERSHIP, ITS OWN DISPATCH. This is the
    /// whole cross-lane fix: frame bytes no longer wait behind a record
    /// request that may be suspended for forty minutes. Phase 2 already proved
    /// bytes-first and metadata-first arrival are BOTH supported by the server,
    /// so there is no ordering reason for one lane to gate the other.
    private func drainFramesAsync() {
        lock.lock()
        let grant = frameOwner.request(nowMs: nowWallMs(), leaseMs: Self.laneLeaseMs)
        lock.unlock()
        let ticket: DrainLaneOwner.Ticket
        switch grant {
        case .coalesced: return
        case .started(let t): ticket = t
        case .reclaimed(let t, let lost):
            ticket = t
            // ITS OWN CHECKPOINT. A reclaim means a previous owner was
            // presumed lost — the event the 15.3-hour stall had no way to
            // report. Logging it as an ordinary start would restore exactly
            // that silence.
            RecoveryTrace.shared.log("rn_lane_reclaimed",
                                     [("lane", "frame"), ("lostGeneration", String(lost))])
        }
        #if canImport(Security)
        guard let cfg = SecureConfig.load() else {
            lock.lock(); frameOwner.abandon(ticket); lock.unlock()
            return
        }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            var again = true
            var passes = 0
            while again && passes < 8 {
                passes += 1
                let progressed = self?.drainFrames(cfg: cfg, ticket: ticket) ?? false
                self?.lock.lock()
                again = self?.frameOwner.finishPass(ticket, moreDurableWork: progressed,
                                                    nowMs: self?.nowWallMs() ?? 0,
                                                    leaseMs: Self.laneLeaseMs) ?? false
                self?.lock.unlock()
            }
            // Same reasoning as the record lane: the pass bound releases
            // ownership rather than parking it. Fenced, so a worker that was
            // reclaimed mid-pass cannot release the lane its successor holds.
            if again { self?.lock.lock(); self?.frameOwner.abandon(ticket); self?.lock.unlock() }
        }
        #else
        lock.lock(); frameOwner.abandon(ticket); lock.unlock()
        #endif
    }

    private func observeLifecycle() {
        #if canImport(UIKit)
        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification,
                                               object: nil, queue: nil) { [weak self] _ in
            self?.requestDrain(reason: "app_background")
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification,
                                               object: nil, queue: nil) { [weak self] _ in
            self?.requestDrain(reason: "app_foreground")
        }
        #endif
    }

    /// Registers and submits a BGProcessingTask. Deliberately best-effort:
    /// iOS may defer or never run it, and it does not run after a force-quit.
    public func scheduleBackgroundDrain() {
        #if os(iOS)
        if #available(iOS 13.0, *) {
            let req = BGProcessingTaskRequest(identifier: RnTransport.backgroundTaskIdentifier)
            req.requiresNetworkConnectivity = true
            req.requiresExternalPower = false
            do {
                try BGTaskScheduler.shared.submit(req)
                RecoveryTrace.shared.log("bg_task_submitted")
            } catch {
                RecoveryTrace.shared.log("bg_task_submit_failed")
            }
        }
        #endif
    }

    /// Host apps call this from `application(_:didFinishLaunchingWithOptions:)`.
    public func registerBackgroundTask() {
        #if os(iOS)
        if #available(iOS 13.0, *) {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: RnTransport.backgroundTaskIdentifier,
                                            using: nil) { [weak self] task in
                RecoveryTrace.shared.log("bg_task_fired")
                self?.requestDrain(reason: "bg_task")
                task.setTaskCompleted(success: true)
            }
        }
        #endif
    }

    /// Beside the queue, in the app's own Application Support directory.
    static func inflightURL() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory,
                                                 in: .userDomainMask,
                                                 appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("tracescout-rn-inflight.json")
    }
}

/// The one place the ingest key becomes a request HEADER — never a query
/// parameter (proxies and access logs record those), never written to the
/// queue file, never included in an error string.
struct HttpChunkPoster {
    let endpoint: String
    let ingestKey: String

    func post(projectId: String, body: Data) -> UploadResult {
        guard let url = URL(string: "\(endpoint)/api/mobile/projects/\(projectId)/replay/chunks") else {
            return UploadResult(action: .terminal, status: 0, code: "endpoint_invalid")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue(ingestKey, forHTTPHeaderField: "X-Ingest-Key")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = body
        req.timeoutInterval = 20

        var out = UploadResult(action: .retry, status: 0, code: nil)
        let sem = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: req) { data, response, _ in
            defer { sem.signal() }
            guard let http = response as? HTTPURLResponse else { return }
            var code: String?
            var retriable = false
            if let d = data,
               let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                code = o["code"] as? String
                retriable = (o["retriable"] as? Bool) ?? false
            }
            let action = classifyResponse(status: http.statusCode, retriable: retriable)
            let ack = data.flatMap { Ack.parse($0) }
            out = UploadResult(action: action, status: http.statusCode, code: code, ack: ack)
        }.resume()
        _ = sem.wait(timeout: .now() + 30)
        return out
    }
}

/// Posts ONE frame's already-redacted bytes to the existing frame endpoint.
/// Metadata travels in query parameters exactly as the certified Android
/// native uploader sends it; the body is the raw image.
struct HttpFramePoster {
    let endpoint: String
    let ingestKey: String

    func post(projectId: String, header h: FrameHeader, bytes: Data) -> UploadResult {
        var comps = URLComponents(string: "\(endpoint)/api/mobile/projects/\(projectId)/replay/frames")
        comps?.queryItems = [
            URLQueryItem(name: "sessionId", value: h.sessionId),
            URLQueryItem(name: "visualFrameId", value: h.visualFrameId),
            URLQueryItem(name: "contentHash", value: h.contentHash),
            URLQueryItem(name: "width", value: String(h.width)),
            URLQueryItem(name: "height", value: String(h.height)),
            URLQueryItem(name: "byteSize", value: String(bytes.count)),
            URLQueryItem(name: "format", value: h.format),
            URLQueryItem(name: "privacyState", value: FrameBudgets.privacyState),
            URLQueryItem(name: "captureReason", value: FrameBudgets.captureReason),
            URLQueryItem(name: "scope", value: FrameBudgets.scope),
            URLQueryItem(name: "screenId", value: h.screenId),
        ]
        guard let url = comps?.url else { return UploadResult(action: .terminal, status: 0, code: "endpoint_invalid") }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        req.setValue(ingestKey, forHTTPHeaderField: "X-Ingest-Key")
        req.httpBody = bytes
        req.timeoutInterval = 30
        var out = UploadResult(action: .retry, status: 0, code: nil)
        let sem = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: req) { data, response, _ in
            defer { sem.signal() }
            guard let http = response as? HTTPURLResponse else { return }
            var code: String?
            if let d = data, let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                code = o["code"] as? String
            }
            // A bare 2xx is not an acknowledgement. The delivery state machine
            // additionally checks this identity against the descriptor it froze
            // BEFORE the request.
            let fack = data.flatMap { FrameAckWire.parse($0) }
            out = UploadResult(action: classifyResponse(status: http.statusCode, retriable: false),
                               status: http.statusCode, code: code, ack: nil, frameAck: fack)
        }.resume()
        _ = sem.wait(timeout: .now() + 40)
        return out
    }
}

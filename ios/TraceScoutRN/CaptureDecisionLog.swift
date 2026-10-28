import Foundation

/// WHY CAPTURE STOPPED MUST BE ANSWERABLE AFTER THE FACT.
///
/// ## The defect this exists for, measured
///
/// RN iOS 0.3.0 (25), session `ts_1788938861359_4dcc6ce06893`: the last
/// `mobile_visual_frame` declaration is at replay 73,397.2 ms and the app then
/// ran for a further **37.7 s** on `activity`, producing 25 hierarchy snapshots
/// and 23 interaction events and **zero** frame declarations. The device
/// diagnostics could not say why, and no further device read could ever say
/// why, because:
///
/// grep -nE "RecoveryTrace|\.log\(" RnBridge.swift -> NO MATCHES
///
/// The capture path wrote NOTHING durable. Every `FramePolicy` refusal, every
/// coherence refusal, every provider-nil outcome and the whole `funnel` were
/// in-memory only, and `frameFunnel` is not reachable from the diagnostics
/// surface at all. An in-memory counter read from a freshly started process is
/// not evidence — this programme has already paid for that once, when a
/// startup trace reported `uploadAttempted: false` about a process that had
/// never run the code being asked about.
///
/// So the delivery lane was richly observable and the capture lane was not
/// observable AT ALL. That asymmetry is the subject here.
///
/// ## What this type is, and what it deliberately is not
///
/// IT DECIDES NOTHING. It is written strictly AFTER a decision has been
/// made, takes no part in making it, and returns nothing any caller branches
/// on. Build 26 exists to make the physical capture decision PROVABLE without
/// changing it; an observer that can move its subject observes something else.
/// The cadence, the 40-frame shared budget, the 600-frame Lab budget, the store
/// size, the delivery concurrency and the encoding quality are all untouched.
///
/// IT CARRIES NO CUSTOMER CONTENT. Screen ids, frame ids, enum reasons,
/// counters and timestamps only. No text, no accessibility values, no pixels,
/// no hierarchy payload, no credential, no URL. `sanitized` is applied to
/// every string that reaches the file, and a test asserts the field set.
///
/// ## Why a SUMMARY and not only trace lines
///
/// `RecoveryTrace` is bounded at 256 KB and drops its OLDEST HALF on overflow.
/// The Build-25 block proved that in the field: its window began **57 s into a
/// 111 s session**, and 36 settlements that demonstrably happened were simply
/// not in it. A "why did capture stop" answer that lives only in trace lines is
/// an answer that can be thrown away by the very session it describes.
///
/// The summary below is therefore its own durable artifact with its own bound:
/// per-reason counters that never lose a reason, first/last timestamps per
/// reason, the activity-state transitions, the last successful decision, and a
/// bounded ring of the last N significant records. A run of 10,000 identical
/// refusals costs one counter and two timestamps.
public enum CaptureDecisionLog {

    // ── the vocabulary ──────────────────────────────────────────────────────

    /// The decision boundary a candidate stopped at. Ordered as the code
    /// evaluates them, so a reader can tell how far a candidate got.
    ///
    /// CLOSED AND EXACT. A free-text reason would let the next unexplained
    /// stop be described in words nobody can group or count, which is how
    /// "provider returned nil" became an answer that answers nothing.
    public enum Outcome: String, Equatable, CaseIterable {
        /// A frame was produced and entered the delivery path.
        case captured
        /// The route is declared never-record: no traversal, no candidate.
        case neverRecord = "never_record"
        /// No hierarchy to redact from, so a frame is not even considered.
        case noHierarchy = "no_hierarchy"
        /// The Lab lane's own absolute session bound.
        case labSessionBudget = "lab_session_budget"
        /// `FramePolicy.decide` refused. The exact policy reason is carried
        /// separately in `policyRefusal` — `session_budget`, `cadence_budget`, …
        case policyRefused = "policy_refused"
        /// The policy admitted, the provider was called, and it returned nil.
        /// The provider's own reason is carried in `providerReason`.
        case providerNil = "provider_nil"
        /// The provider produced a frame and the durable store refused it.
        case admissionRefused = "admission_refused"
    }

    /// One decision, fully described. Every field is a fact the reader needs to
    /// tell one boundary from another; none is derived at read time.
    public struct Decision: Equatable {
        public let sessionId: String
        public let screenId: String
        public let monotonicNanos: Int64
        public let wallMs: Int64

        public let labActive: Bool
        /// `active` / `trailing` / `idle`.
        public let activityState: String

        public let sessionFrames: Int
        public let windowFrames: Int
        /// What `FrameCadenceDriver.effectiveFramesThisSession` actually handed
        /// to `FramePolicy.decide`. The single most important number for the
        /// open question: in the Lab it is 0 while ACTIVE and the session count
        /// while IDLE, and the shared ceiling is 40.
        public let effectiveFrames: Int
        /// The ceiling that was in force: 40, 600, or another value if either
        /// constant moves. Recorded rather than inferred from `labActive`.
        public let budgetSelected: Int

        public let policyCapture: Bool
        public let policyRefusal: String?

        public let providerCalled: Bool
        public let providerReason: String?

        public let admission: String?
        public let visualFrameId: String?

        public let outcome: Outcome

        public init(sessionId: String, screenId: String, monotonicNanos: Int64, wallMs: Int64,
                    labActive: Bool, activityState: String,
                    sessionFrames: Int, windowFrames: Int, effectiveFrames: Int,
                    budgetSelected: Int,
                    policyCapture: Bool, policyRefusal: String?,
                    providerCalled: Bool, providerReason: String?,
                    admission: String?, visualFrameId: String?, outcome: Outcome) {
            self.sessionId = sessionId; self.screenId = screenId
            self.monotonicNanos = monotonicNanos; self.wallMs = wallMs
            self.labActive = labActive; self.activityState = activityState
            self.sessionFrames = sessionFrames; self.windowFrames = windowFrames
            self.effectiveFrames = effectiveFrames; self.budgetSelected = budgetSelected
            self.policyCapture = policyCapture; self.policyRefusal = policyRefusal
            self.providerCalled = providerCalled; self.providerReason = providerReason
            self.admission = admission; self.visualFrameId = visualFrameId
            self.outcome = outcome
        }

        /// The grouping key a counter is kept under. An outcome alone is too
        /// coarse — `policy_refused` covers both the session ceiling and the
        /// idle cadence floor, and telling those apart IS the open question.
        public var reasonKey: String {
            func reason(_ x: String?) -> String { CaptureDecisionLog.reason(x) }
            switch outcome {
            // THE KEY IS A STRING THAT REACHES THE FILE. The first version
            // sanitized the RECORD and not the key, so a reason carrying an
            // address or a token survived in the reason line; the second
            // sanitized both and still kept `tsm_live_abcdef`, because a
            // credential prefix is shaped like an identifier. Reasons are a
            // closed vocabulary — see `CaptureDecisionLog.reason`.
            case .captured:          return "captured"
            case .policyRefused:     return "policy:" + reason(policyRefusal)
            case .providerNil:       return "provider:" + reason(providerReason)
            case .admissionRefused:  return "admission:" + reason(admission)
            default:                 return outcome.rawValue
            }
        }
    }

    // ── the bounded durable summary ─────────────────────────────────────────

    /// How many significant records the ring keeps. Bounded so a long session
    /// cannot grow the artifact without limit; large enough that the run-up to
    /// a stop is legible.
    public static let recentCapacity = 24

    public struct ReasonWindow: Equatable {
        public var count: Int
        public var firstMonotonicNanos: Int64
        public var lastMonotonicNanos: Int64
        public var firstScreenId: String
        public var lastScreenId: String
    }

    public struct Summary: Equatable {
        public var sessionId: String = ""
        /// The device-side session start, so a reader can place every timestamp
        /// without needing a trace line that may have been dropped.
        public var sessionStartMs: Int64 = -1

        public var candidateCount = 0
        public var capturedCount = 0

        /// Keyed by `Decision.reasonKey`, so `policy:session_budget` and
        /// `policy:cadence_budget` are never merged.
        public var reasons: [String: ReasonWindow] = [:]

        public var firstRefusalMonotonicNanos: Int64 = -1
        public var lastRefusalMonotonicNanos: Int64 = -1

        /// `idle->active` style transitions with the monotonic time they
        /// happened. Bounded to the same ring capacity.
        public var activityTransitions: [String] = []
        /// The last decision whose outcome was `captured`, rendered.
        public var lastCapture: String = ""
        /// The last `recentCapacity` significant records, oldest first.
        public var recent: [String] = []

        /// Transport stage timings, accumulated from the delivery lane. Kept in
        /// the SAME artifact so a diagnostics read has one durable subject.
        public var transport: [String: StageStats] = [:]
        /// How often a reason fell outside `knownReasons`. A non-zero value
        /// means the vocabulary is behind the code, not that nothing happened.
        public var unrecognizedReasons = 0
        /// Passive capture accounting — execution phases, scheduling latency
        /// and the four distinct byte quantities. Same artifact, same bound,
        /// same rule: written after decisions, read by nothing that decides.
        public var accounting = CaptureAccounting.Summary()
    }

    /// A bounded stage-timing accumulator. Keeps a reservoir rather than every
    /// sample, so p50/p90 stay honest without unbounded growth.
    public struct StageStats: Equatable {
        public var count = 0
        public var max: Int64 = 0
        /// Bounded, oldest-evicted. Percentiles are computed over THIS window
        /// and the count says how many samples it represents.
        public var samples: [Int64] = []
        public static let sampleCapacity = 64

        public mutating func add(_ ms: Int64) {
            guard ms >= 0 else { return }
            count += 1
            if ms > max { max = ms }
            samples.append(ms)
            if samples.count > StageStats.sampleCapacity { samples.removeFirst() }
        }
        public func percentile(_ p: Double) -> Int64 {
            guard !samples.isEmpty else { return -1 }
            let s = samples.sorted()
            let idx = Swift.min(s.count - 1, Swift.max(0, Int((Double(s.count - 1) * p).rounded())))
            return s[idx]
        }
    }

    // ── sanitization ────────────────────────────────────────────────────────

    /// A SHAPE FILTER CANNOT EXCLUDE A TOKEN-SHAPED STRING — CI PROVED IT.
    ///
    /// The first version of this artifact filtered by CHARACTER CLASS, which
    /// correctly stripped spaces, quotes and `<script>` and just as correctly
    /// KEPT `tsm_live_abcdef`, because a credential prefix is made entirely of
    /// characters an identifier is allowed to contain. The test wrote that
    /// exact string into a provider reason and it landed in the file.
    ///
    /// So the reason is a CLOSED VOCABULARY, not a filtered string. Every value
    /// below is a constant this SDK itself emits — `FramePolicy.decide`'s
    /// refusals, the coherence-class refusals, the rasterizer/window states and
    /// the store's own admission reasons. Anything else becomes
    /// `unrecognized`, and its text is DROPPED rather than carried: a reason we
    /// did not produce is not a reason we can vouch for, and an artifact that
    /// can hold arbitrary text is one that will eventually hold the wrong text.
    ///
    /// The cost is stated rather than hidden: a NEW legitimate refusal reads
    /// `unrecognized` until it is added here, and `unrecognizedReasons` counts
    /// how often that happened so the gap is visible instead of silent.
    public static let knownReasons: Set<String> = [
        // FramePolicy.decide
        "frames_not_enabled", "screen_never_record", "not_foreground",
        "platform_secure_surface", "hierarchy_unavailable", "cadence_budget",
        "session_frame_budget",
        // driver / Lab bounds
        "lab_session_frame_budget", "throttled_active", "throttled_idle",
        // coherence class (a transition is an expected state, not an error)
        "route_changed", "window_changed", "dimensions_changed",
        "geometry_changed", "subject_unstable", "coherence_unverified",
        // capture surface
        "no_window", "background", "rasterizer_failed", "window_secure",
        // durable store admission
        "store_cap", "store_cap_freshness", "store_evict_failed",
        "frame_byte_budget",
        // the absence cases
        "unspecified", "unrecognized",
    ]

    /// A reason, reduced to the closed vocabulary.
    public static func reason(_ s: String?) -> String {
        let v = sanitized(s)
        return knownReasons.contains(v) ? v : "unrecognized"
    }

    /// Ids and states still go through the shape filter — a screen id or a
    /// frame id is OURS and is not drawn from a fixed set.
    public static func sanitized(_ s: String?) -> String {
        guard let s = s, !s.isEmpty else { return "-" }
        let kept = s.unicodeScalars.filter {
            CharacterSet.alphanumerics.contains($0) || "_-.:".unicodeScalars.contains($0)
        }
        let out = String(String.UnicodeScalarView(kept))
        return out.isEmpty ? "-" : String(out.prefix(64))
    }
}

// ── the recorder ────────────────────────────────────────────────────────────

/// Where a summary is persisted. A protocol so the model is testable without a
/// filesystem, and so a caller that has not wired storage degrades to
/// process-lifetime memory rather than to silence.
public protocol CaptureSummaryStore {
    func load() -> String?
    @discardableResult func save(_ text: String) -> Bool
}

public final class MemoryCaptureSummaryStore: CaptureSummaryStore {
    private var held: String?
    private let lock = NSLock()
    public init() {}
    public func load() -> String? { lock.lock(); defer { lock.unlock() }; return held }
    @discardableResult public func save(_ text: String) -> Bool {
        lock.lock(); held = text; lock.unlock(); return true
    }
}

public final class FileCaptureSummaryStore: CaptureSummaryStore {
    private let url: URL
    private let lock = NSLock()
    public init(url: URL) { self.url = url }
    public func load() -> String? {
        lock.lock(); defer { lock.unlock() }
        return try? String(contentsOf: url, encoding: .utf8)
    }
    /// Atomic: a torn summary is worse than a slightly stale one, because a
    /// reader cannot tell the difference.
    @discardableResult public func save(_ text: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        do { try text.write(to: url, atomically: true, encoding: .utf8); return true }
        catch { return false }
    }
}

// ── BUILD 27: bounded history ───────────────────────────────────────────────

/// A bounded ring of PREVIOUS sessions' summaries.
///
/// THE DEFECT THIS EXISTS FOR. Build 26 kept exactly ONE generation:
/// `begin` assigned a fresh `Summary` and immediately overwrote the file.
/// The only reader is the Diagnostics screen, and reaching it requires a
/// launch, which publishes a session, which calls `begin`. So the artifact
/// was destroyed by the act of reading it — a diagnostic that cannot see its
/// own subject. Physically observed: a Build-26 drive whose capture decisions
/// could not be recovered after two relaunches.
///
/// TWO SLOTS ARE PROVABLY INSUFFICIENT, so the depth is 3. The observed
/// scenario was TWO relaunches after the run; with `current + previous` the
/// second shift would have evicted the drive. But depth alone is the weaker
/// half — see `isMeaningful`.
public protocol CaptureSummaryHistoryStore {
    /// How many previous generations are retained. Bounded, never unbounded.
    var depth: Int { get }
    /// Slot 1 is the most recent previous generation. Out of range → nil.
    func loadSlot(_ index: Int) -> String?
    /// Shift every slot one step older and place `text` in slot 1.
    @discardableResult func shift(inserting text: String) -> Bool
}

public extension CaptureDecisionLog {
    /// How many PREVIOUS generations `FileCaptureSummaryHistoryStore` keeps.
    static let historyDepth = 3

    /// Does this rendered summary carry capture evidence worth retaining?
    ///
    /// THIS IS THE STRONGER HALF OF THE RETENTION FIX. A launch that
    /// publishes a session and then dies before any capture decision produces a
    /// summary with `candidateCount == 0` and no reason lines — it carries no
    /// information BY DEFINITION, and archiving it would evict the valuable
    /// one. With this rule a crash loop of any length cannot displace real
    /// history; without it, three crashes beat a depth-3 ring.
    ///
    /// FAIL SAFE, DELIBERATELY. Anything this cannot confidently read AS
    /// EMPTY is treated as meaningful and archived. Discarding an artifact we
    /// do not understand is the destructive choice, and a parser that silently
    /// throws away what it cannot parse is the exact defect class this whole
    /// programme keeps paying for.
    static func isMeaningful(_ text: String) -> Bool {
        if text.isEmpty { return false }
        var sawCounter = false
        for raw in text.split(separator: "\n") {
            if raw.hasPrefix("reason ") { return true }
            if raw.hasPrefix("stage ") { return true }
            if raw.hasPrefix("recent ") { return true }
            guard raw.hasPrefix("candidateCount=") || raw.hasPrefix("capturedCount=") else { continue }
            sawCounter = true
            let value = raw.drop(while: { $0 != "=" }).dropFirst()
            if let n = Int(value), n > 0 { return true }
        }
        // Counters were READ and were zero, with no reason/stage/recent line:
        // genuinely empty. No counter found at all means unreadable → keep.
        return !sawCounter
    }

    /// The session id a rendered summary names, for an unambiguous heading.
    /// Never blank: an unreadable artifact says so.
    static func sessionId(of text: String) -> String {
        for raw in text.split(separator: "\n") where raw.hasPrefix("sessionId=") {
            let v = String(raw.dropFirst("sessionId=".count))
            return v.isEmpty ? "unreadable" : v
        }
        return "unreadable"
    }
}

/// In-memory history, for tests and for a caller that has not wired storage.
public final class MemoryCaptureSummaryHistoryStore: CaptureSummaryHistoryStore {
    private var slots: [String]
    private let lock = NSLock()
    public let depth: Int
    public init(depth: Int = CaptureDecisionLog.historyDepth) {
        self.depth = Swift.max(1, depth)
        self.slots = []
    }
    public func loadSlot(_ index: Int) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard index >= 1, index <= slots.count else { return nil }
        return slots[index - 1]
    }
    @discardableResult public func shift(inserting text: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        slots.insert(text, at: 0)
        if slots.count > depth { slots.removeLast(slots.count - depth) }
        return true
    }
}

/// File-backed history: one file per slot, each written atomically.
public final class FileCaptureSummaryHistoryStore: CaptureSummaryHistoryStore {
    private let base: URL
    private let lock = NSLock()
    public let depth: Int
    /// `summaryURL` is the CURRENT summary's URL; slots sit beside it as
    /// `<name>.prev1`, `.prev2`, … so there is one naming rule, not two.
    public init(summaryURL: URL, depth: Int = CaptureDecisionLog.historyDepth) {
        self.base = summaryURL
        self.depth = Swift.max(1, depth)
    }
    private func url(_ i: Int) -> URL {
        base.deletingLastPathComponent()
            .appendingPathComponent(base.lastPathComponent + ".prev\(i)")
    }
    public func loadSlot(_ index: Int) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard index >= 1, index <= depth else { return nil }
        return try? String(contentsOf: url(index), encoding: .utf8)
    }
    /// OLDEST FIRST, AND THAT ORDER IS THE CRASH SAFETY.
    ///
    /// slot3 <- slot2; slot2 <- slot1; slot1 <- text
    ///
    /// A process death after any step leaves a DUPLICATE, never a hole: the
    /// value being moved still exists in its old slot when the new copy is
    /// written. Writing newest-first would overwrite slot1 before slot2 had
    /// been given its contents, and one crash would erase a generation.
    /// Duplicates are self-identifying (the same `sessionId=` in two slots) and
    /// are the correct trade — a duplicate is legible, a hole is not.
    @discardableResult public func shift(inserting text: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        var ok = true
        var i = depth
        while i >= 2 {
            if let older = try? String(contentsOf: url(i - 1), encoding: .utf8) {
                do { try older.write(to: url(i), atomically: true, encoding: .utf8) }
                catch { ok = false }
            }
            i -= 1
        }
        do { try text.write(to: url(1), atomically: true, encoding: .utf8) }
        catch { ok = false }
        return ok
    }
}

/// Accumulates decisions and persists a bounded summary.
///
/// FLUSH POLICY, STATED. Writing the file on every candidate would be file
/// I/O at the candidate rate (~8/s while active) for no gain, since a stop is a
/// RUN of identical refusals. It flushes when the answer could change:
/// the first occurrence of any reason, every activity transition, every
/// capture, and every `flushEvery` records otherwise. So the LAST timestamp of
/// an ongoing run may lag by up to `flushEvery` candidates — disclosed here and
/// asserted in the tests, rather than presented as exact.
public final class CaptureDecisionRecorder {
    private let store: CaptureSummaryStore
    private let flushEvery: Int
    private let lock = NSLock()
    private var summary = CaptureDecisionLog.Summary()
    private var sinceFlush = 0
    private var lastActivityState = ""

    /// BUILD 27. Absent (nil) keeps the Build-26 behaviour exactly — no
    /// history, destructive `begin`. Every production wiring supplies one.
    private let history: CaptureSummaryHistoryStore?

    public init(store: CaptureSummaryStore,
                history: CaptureSummaryHistoryStore? = nil,
                flushEvery: Int = 16) {
        self.store = store
        self.history = history
        self.flushEvery = Swift.max(1, flushEvery)
    }

    /// The retained previous generations, slot 1 = most recent. Bounded.
    public func historySlots() -> [String?] {
        guard let h = history else { return [] }
        return (1...h.depth).map { h.loadSlot($0) }
    }

    /// Bind the session BEFORE any decision, so every timestamp has an origin
    /// that does not depend on a trace line surviving.
    ///
    /// BUILD 27 — ARCHIVE, THEN REPLACE. The outgoing summary is read from the
    /// STORE (not from `self.summary`): a fresh process has an empty in-memory
    /// summary and the whole point is to preserve the PREVIOUS process's work.
    ///
    /// ORDER IS LOAD-BEARING. The archive completes before the current slot
    /// is overwritten, so a process death between them leaves the outgoing
    /// summary readable in BOTH places rather than in neither.
    public func begin(sessionId: String, sessionStartMs: Int64) {
        if let h = history, let outgoing = store.load(),
           CaptureDecisionLog.isMeaningful(outgoing) {
            h.shift(inserting: outgoing)
        }
        lock.lock()
        summary = CaptureDecisionLog.Summary()
        summary.sessionId = CaptureDecisionLog.sanitized(sessionId)
        summary.sessionStartMs = sessionStartMs
        sinceFlush = 0
        lastActivityState = ""
        let text = render(summary)
        lock.unlock()
        store.save(text)
    }

    /// Record ONE decision. Returns nothing: nothing may branch on this.
    public func record(_ d: CaptureDecisionLog.Decision) {
        var flush = false
        lock.lock()
        summary.candidateCount += 1
        if summary.sessionId == "" { summary.sessionId = CaptureDecisionLog.sanitized(d.sessionId) }

        if lastActivityState != d.activityState {
            if !lastActivityState.isEmpty {
                append(&summary.activityTransitions,
                       "\(lastActivityState)->\(d.activityState)@\(d.monotonicNanos)")
                flush = true
            }
            lastActivityState = d.activityState
        }

        let key = d.reasonKey
        if key.hasSuffix(":unrecognized") { summary.unrecognizedReasons += 1 }
        if var w = summary.reasons[key] {
            w.count += 1
            w.lastMonotonicNanos = d.monotonicNanos
            w.lastScreenId = CaptureDecisionLog.sanitized(d.screenId)
            summary.reasons[key] = w
        } else {
            summary.reasons[key] = CaptureDecisionLog.ReasonWindow(
                count: 1,
                firstMonotonicNanos: d.monotonicNanos, lastMonotonicNanos: d.monotonicNanos,
                firstScreenId: CaptureDecisionLog.sanitized(d.screenId),
                lastScreenId: CaptureDecisionLog.sanitized(d.screenId))
            // A NEW REASON ALWAYS FLUSHES. The first appearance of a refusal
            // is the single most valuable line in the artifact — it is the
            // boundary between "capture was working" and "capture stopped".
            flush = true
        }

        if d.outcome == .captured {
            summary.capturedCount += 1
            summary.lastCapture = render(d)
            flush = true
        } else {
            if summary.firstRefusalMonotonicNanos < 0 {
                summary.firstRefusalMonotonicNanos = d.monotonicNanos
            }
            summary.lastRefusalMonotonicNanos = d.monotonicNanos
        }

        append(&summary.recent, render(d))

        sinceFlush += 1
        if sinceFlush >= flushEvery { flush = true }
        if flush { sinceFlush = 0 }
        let text = flush ? render(summary) : nil
        lock.unlock()
        if let t = text { store.save(t) }
    }

    /// A transport stage sample. Same artifact, same bound.
    public func recordStage(_ stage: String, ms: Int64) {
        lock.lock()
        var s = summary.transport[stage] ?? CaptureDecisionLog.StageStats()
        s.add(ms)
        summary.transport[stage] = s
        sinceFlush += 1
        let flush = sinceFlush >= flushEvery
        if flush { sinceFlush = 0 }
        let text = flush ? render(summary) : nil
        lock.unlock()
        if let t = text { store.save(t) }
    }

    // ── passive capture accounting ──────────────────────────────────────────
    // Same artifact, same flush cadence as a transport stage. Nothing here is
    // read back by the capture path: `snapshot` and `persisted` are the
    // only readers and both are diagnostics.

    /// One capture's measured execution (phases + produced bytes).
    public func recordExecution(_ e: CaptureAccounting.Execution) {
        account { $0.record(e) }
    }

    /// One scheduled callback's requested-vs-observed delay.
    public func recordScheduling(_ s: CaptureAccounting.Scheduling) {
        account { $0.record(s) }
    }

    /// One durable-store enqueue attempt, with the store occupancy read right
    /// after it. `bytes` is the frame's encoded size whether or not `ok`.
    public func recordEnqueue(bytes: Int, ok: Bool, pendingBytes: Int, pendingRecords: Int) {
        account { $0.recordEnqueue(bytes: bytes, ok: ok,
                                   pendingBytes: pendingBytes, pendingRecords: pendingRecords) }
    }

    /// One server-settled frame, accepted or duplicate.
    public func recordAcknowledged(bytes: Int, duplicate: Bool) {
        account { $0.recordAcknowledged(bytes: bytes, duplicate: duplicate) }
    }

    private func account(_ mutate: (inout CaptureAccounting.Summary) -> Void) {
        lock.lock()
        mutate(&summary.accounting)
        sinceFlush += 1
        let flush = sinceFlush >= flushEvery
        if flush { sinceFlush = 0 }
        let text = flush ? render(summary) : nil
        lock.unlock()
        if let t = text { store.save(t) }
    }

    /// Force the current state to storage — for a lifecycle boundary, where the
    /// process may not get another chance.
    public func flush() {
        lock.lock(); sinceFlush = 0; let text = render(summary); lock.unlock()
        store.save(text)
    }

    public func snapshot() -> CaptureDecisionLog.Summary {
        lock.lock(); defer { lock.unlock() }; return summary
    }

    /// The persisted text, read back. This is what a post-restart Diagnostics
    /// read consumes — deliberately NOT the in-memory summary, because the
    /// whole point is that a fresh process can answer the question.
    public func persisted() -> String? { store.load() }

    private func append(_ a: inout [String], _ s: String) {
        a.append(s)
        if a.count > CaptureDecisionLog.recentCapacity { a.removeFirst() }
    }

    // ── rendering: flat, greppable, no customer content ─────────────────────

    private func render(_ d: CaptureDecisionLog.Decision) -> String {
        let sane = CaptureDecisionLog.sanitized
        return "mono=\(d.monotonicNanos) screen=\(sane(d.screenId))"
            + " out=\(d.outcome.rawValue) act=\(sane(d.activityState)) lab=\(d.labActive)"
            + " sf=\(d.sessionFrames) wf=\(d.windowFrames) eff=\(d.effectiveFrames)"
            + " budget=\(d.budgetSelected) policyCapture=\(d.policyCapture)"
            + " policyRefusal=\(CaptureDecisionLog.reason(d.policyRefusal))"
            + " providerCalled=\(d.providerCalled)"
            + " providerReason=\(CaptureDecisionLog.reason(d.providerReason))"
            + " admission=\(CaptureDecisionLog.reason(d.admission)) vf=\(sane(d.visualFrameId))"
    }

    private func render(_ s: CaptureDecisionLog.Summary) -> String {
        var out = "capture-summary-1\n"
        out += "sessionId=\(s.sessionId)\n"
        out += "sessionStartMs=\(s.sessionStartMs)\n"
        out += "candidateCount=\(s.candidateCount)\n"
        out += "capturedCount=\(s.capturedCount)\n"
        out += "firstRefusalMono=\(s.firstRefusalMonotonicNanos)\n"
        out += "lastRefusalMono=\(s.lastRefusalMonotonicNanos)\n"
        out += "unrecognizedReasons=\(s.unrecognizedReasons)\n"
        for k in s.reasons.keys.sorted() {
            let w = s.reasons[k]!
            out += "reason \(k) count=\(w.count)"
                + " firstMono=\(w.firstMonotonicNanos) lastMono=\(w.lastMonotonicNanos)"
                + " firstScreen=\(w.firstScreenId) lastScreen=\(w.lastScreenId)\n"
        }
        for k in s.transport.keys.sorted() {
            let t = s.transport[k]!
            out += "stage \(k) count=\(t.count) p50=\(t.percentile(0.5))"
                + " p90=\(t.percentile(0.9)) max=\(t.max) window=\(t.samples.count)\n"
        }
        out += "transitions=\(s.activityTransitions.joined(separator: ","))\n"
        out += "lastCapture=\(s.lastCapture)\n"
        for r in s.recent { out += "recent \(r)\n" }
        out += s.accounting.render()
        return out
    }
}

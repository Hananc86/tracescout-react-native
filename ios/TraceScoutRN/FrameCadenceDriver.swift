import Foundation

/// THE MISSING CAPTURE DRIVER.
///
/// ## The defect this exists for, measured
///
/// Session `ts_1788675925459_67c45d873dd7` (RN iOS 0.3.0 (19), physical
/// iPhone, Mobile Replay Lab) visited six screens in 21.7575 s and stored
/// **six** frames — exactly one per screen. Median gap 4.4318 s, max 8.8296 s,
/// best 1.4028 s. Replay was an image slider.
///
/// THE CAUSE WAS NOT THE 10 s FLOOR, AND SAYING SO WOULD HAVE SENT THE FIX
/// TO THE WRONG PLACE. `FrameCadence.effectiveSinceMs` already returns `-1` —
/// "no previous frame", hence no cadence refusal — whenever the subject key
/// differs, and the subject key carries the coherence fingerprint. The six
/// frames were six different routes, so six different subject keys, so
/// `cadence_budget` NEVER FIRED. Lowering the floor would have changed nothing.
///
/// The real cause: **nothing ever asked for a frame except navigation.** The
/// decision lives inside `RnBridge.screenSnapshot`, whose only callers are the
/// app's explicit per-navigation `telemetry.screen(.)` calls. There is no
/// periodic driver, no interaction-triggered driver and no mutation-triggered
/// driver anywhere in the iOS lane.
///
/// frames == explicit screenSnapshot calls == navigations
/// 6 screens -> 6 calls -> 6 frames. No residual.
///
/// ## What this type is, and what it deliberately is not
///
/// It is a PURE state machine over timestamps. It performs no capture, holds no
/// pixels, starts no timer and touches no UIKit. The host drives it; it only
/// answers *"what state are we in, and when should the next candidate be
/// offered?"*.
///
/// `FramePolicy.decide` IS NOT TOUCHED. It is a shared contract pinned to
/// the Kotlin twin by `parity-vectors/frame-vectors.json`; changing it would
/// move Android's certified vc9 output. This driver only decides what `since`
/// MEANS during an active window, and hands the answer to the same unchanged
/// `decide`. During IDLE the existing semantics apply byte for byte, including
/// the 10 s same-subject floor, which is exactly the right idle storm control.
///
/// IT IS NOT A VIDEO RECORDER. Activity ends, the trailing window closes and
/// the cadence collapses back to idle. A fixed continuous capture rate is
/// explicitly not the design.
public enum FrameCadenceDriver {

    /// Engineering targets, not the product contract.
    public enum Budgets {
        /// Candidate tick while ACTIVE — ~8 fps.
        public static let activeTickMs: Int64 = 125
        /// The certification ceiling for an ordinary active candidate gap.
        /// A gap above this on a genuinely changing screen is the acceptance
        /// failure; duplicate-frame dedupe on a static screen is not.
        public static let activeCeilingMs: Int64 = 250
        /// How long activity keeps the window open after the last signal.
        public static let trailingMs: Int64 = 1_500
        /// Candidate tick once activity has fully settled.
        public static let idleTickMs: Int64 = 1_000
        /// THE LAB LANE'S OWN ABSOLUTE BOUND, replacing nothing.
        ///
        /// `FrameBudgets.maxFramesPerSession` is 40 and `framesThisSession` is
        /// SESSION-GLOBAL, reset only by `startSession`. At the driver's ~8 fps
        /// that is 40/8 = FIVE SECONDS: the new cadence would have been smooth
        /// for five seconds and then refused `session_frame_budget` for the
        /// rest of the session — a worse artifact than the slider it replaces,
        /// and invisible to every existing test because nothing had ever asked
        /// for a 41st frame.
        ///
        /// The shared ceiling is NOT raised and `FramePolicy.decide` is NOT
        /// touched, so customer and Android behaviour is byte-identical. The
        /// Lab lane instead counts frames PER ACTIVITY WINDOW and carries its
        /// own session bound here, so it is bounded — never unbounded.
        ///
        /// 600 frames is ~75 s of continuous 8 fps capture. The certification
        /// drive is 25-30 s of activity, so this is headroom, not a licence.
        public static let labMaxFramesPerSession = 600
    }

    /// THREE STATES, NOT TWO. `trailing` exists because the visible result
    /// of a tap usually lands AFTER the touch ends — a driver that dropped
    /// straight to idle on touch-up would systematically miss the very frame
    /// that shows what the tap did.
    public enum Activity: String, Equatable {
        case idle
        case active
        case trailing
    }

    /// Why a signal opened or extended the window. Diagnostics only; it selects
    /// nothing. `navigation` is listed because navigation both captures
    /// immediately AND opens a window — the screen keeps settling afterwards.
    public enum Signal: String, Equatable {
        case navigation
        case interactionStart
        case interactionMove
        case interactionEnd
        case visibleMutation
    }

    /// Everything the decision may read. Two timestamps — no history, no map.
    public struct State: Equatable {
        /// Wall time of the most recent activity signal. Negative = none yet.
        public let lastSignalMs: Int64
        /// Wall time of the last candidate the driver OFFERED. Negative = none.
        public let lastCandidateMs: Int64

        public init(lastSignalMs: Int64 = -1, lastCandidateMs: Int64 = -1) {
            self.lastSignalMs = lastSignalMs
            self.lastCandidateMs = lastCandidateMs
        }
        public static let empty = State()

        /// Record an activity signal.
        public func noting(_ nowMs: Int64) -> State {
            State(lastSignalMs: nowMs, lastCandidateMs: lastCandidateMs)
        }
        /// Record that a candidate was offered at `nowMs`.
        public func offering(_ nowMs: Int64) -> State {
            State(lastSignalMs: lastSignalMs, lastCandidateMs: nowMs)
        }
    }

    /// ACTIVE IS THE WINDOW ITSELF, NOT "a touch is down right now". A tap is
    /// instantaneous; keying the window on liveness would make it close between
    /// two taps of the same interaction and produce exactly the gaps this fix
    /// exists to remove.
    public static func activity(state: State, nowMs: Int64) -> Activity {
        guard state.lastSignalMs >= 0 else { return .idle }
        let since = nowMs - state.lastSignalMs
        if since < 0 { return .active }          // clock went backwards: be generous
        if since <= Budgets.activeTickMs * 2 { return .active }
        if since <= Budgets.trailingMs { return .trailing }
        return .idle
    }

    /// Milliseconds until the next candidate should be offered. `0` = now.
    public static func nextCandidateDelayMs(state: State, nowMs: Int64) -> Int64 {
        let want: Int64
        switch activity(state: state, nowMs: nowMs) {
        case .active, .trailing: want = Budgets.activeTickMs
        case .idle:              want = Budgets.idleTickMs
        }
        guard state.lastCandidateMs >= 0 else { return 0 }
        let elapsed = nowMs - state.lastCandidateMs
        if elapsed < 0 { return 0 }
        return elapsed >= want ? 0 : want - elapsed
    }

    /// Should a candidate be offered right now?
    public static func shouldOfferCandidate(state: State, nowMs: Int64) -> Bool {
        nextCandidateDelayMs(state: state, nowMs: nowMs) == 0
    }

    /// How long the owned driver loop must wait before its NEXT tick.
    ///
    /// `nextCandidateDelayMs` answers a different question. It says "is a
    /// candidate due", and `0` means DUE NOW — a correct answer to a caller
    /// deciding whether to offer. The loop then used `max(delay, 1)`, which
    /// turned "due now" into "run again in one millisecond", forever, whenever
    /// the offer clock could not advance. Measured on the Build-26 physical
    /// drive: 288 candidates/second, 3.4 ms median gap, 356 records inside a
    /// single 3.5 s background window.
    ///
    /// The re-arm floor is therefore the TICK INTERVAL for the current
    /// activity, never one millisecond. A driver that is behind catches up at
    /// its intended cadence rather than spinning.
    public static func reArmDelayMs(state: State, nowMs: Int64) -> Int64 {
        let floorMs: Int64
        switch activity(state: state, nowMs: nowMs) {
        case .active, .trailing: floorMs = Budgets.activeTickMs
        case .idle:              floorMs = Budgets.idleTickMs
        }
        return Swift.max(nextCandidateDelayMs(state: state, nowMs: nowMs), floorMs)
    }

    /// The value to hand `FramePolicy.decide` as `sinceLastFrameMs`.
    ///
    /// IT DELEGATES. The idle answer is `FrameCadence.effectiveSinceMs`
    /// UNCHANGED — the certified build-18/19 path, including the 10 s
    /// same-subject floor. Only the ACTIVE and TRAILING answers differ, and
    /// only under the resolved Lab scope.
    ///
    /// `-1` IS THE EXISTING SENTINEL, NOT A NEW ONE. `decide` already reads
    /// it as "there is no previous frame", so the unchanged shared contract
    /// applies no idle floor during an active window. Nothing about `decide`
    /// moves, so Android parity vectors do not move.
    ///
    /// THIS IS NOT A PRIVACY OR BUDGET BYPASS. `decide` still evaluates
    /// opt-in, Never-Record, foreground, secure window, hierarchy availability
    /// and the absolute 40-frame session ceiling, in that order, exactly as
    /// before. Only the interval question is answered differently.
    public static func effectiveSinceMs(labActive: Bool,
                                        activity: Activity,
                                        subjectKey: String?,
                                        cadence: FrameCadence.State,
                                        nowMs: Int64) -> Int64 {
        guard labActive else {
            return FrameCadence.effectiveSinceMs(labActive: false, subjectKey: subjectKey,
                                                 state: cadence, nowMs: nowMs)
        }
        switch activity {
        case .active, .trailing:
            return -1
        case .idle:
            return FrameCadence.effectiveSinceMs(labActive: true, subjectKey: subjectKey,
                                                 state: cadence, nowMs: nowMs)
        }
    }

    /// The frame count to hand `FramePolicy.decide` as `framesThisSession`.
    ///
    /// NOT LAB: THE SESSION COUNT, UNCHANGED. Customer and Android
    /// behaviour is byte-identical; the 40-frame ceiling applies exactly as it
    /// always has.
    ///
    /// LAB, ACTIVE/TRAILING: THE SHARED CEILING IS NOT THIS LANE'S BOUND, so
    /// nothing has been counted against it — `0`.
    ///
    /// A WINDOW-SCOPED COUNT WAS TRIED FIRST AND WAS WRONG. Returning
    /// "frames accepted since this activity window opened" looks like the exact
    /// analogue of `effectiveSinceMs`, and CI measured that it is not: a window
    /// closes only when the cadence returns to IDLE, and during a genuine drive
    /// activity never stops, so ONE window absorbs the whole session. The 30 s
    /// throughput test admitted exactly 40 frames and then refused 201 —
    /// precisely the failure the re-scope was meant to remove. The window is
    /// the right scope for an INTERVAL and the wrong scope for a COUNT.
    ///
    /// `0` is honest here in the same way `-1` is honest in `effectiveSinceMs`:
    /// it is the value the shared rule already understands for "this bound has
    /// not been consumed", and it is TRUE — the Lab lane's consumption is
    /// tracked against `labMaxFramesPerSession`, which `labSessionBudgetExhausted`
    /// enforces BEFORE `decide` is ever called. The lane is bounded; it is
    /// simply bounded somewhere else.
    ///
    /// IDLE STILL REPORTS THE SESSION COUNT, so a settled screen is governed
    /// by the certified shared ceiling exactly as before.
    public static func effectiveFramesThisSession(labActive: Bool,
                                                  activity: Activity,
                                                  windowFrames: Int,
                                                  sessionFrames: Int) -> Int {
        guard labActive else { return sessionFrames }
        switch activity {
        case .active, .trailing: return 0
        case .idle:              return sessionFrames
        }
    }

    /// WHICH ceiling is actually in force for this candidate.
    ///
    /// REPORTING ONLY — nothing branches on it. It exists because Build 25
    /// could not answer "40 or 600?" from any device read, and inferring it
    /// from `labActive` alone would be wrong: inside the Lab an ACTIVE window
    /// bypasses the shared ceiling by handing `decide` a zero, so the number
    /// actually governing the candidate is the Lab bound; once the window goes
    /// IDLE the shared 40 governs again. A reader must be told which, not left
    /// to re-derive a rule that has already surprised us once.
    public static func budgetInForce(labActive: Bool, activity: Activity) -> Int {
        guard labActive else { return FrameBudgets.maxFramesPerSession }
        switch activity {
        case .active, .trailing: return Budgets.labMaxFramesPerSession
        case .idle:              return FrameBudgets.maxFramesPerSession
        }
    }

    /// Has the Lab lane spent its own absolute session bound?
    ///
    /// THE BOUND STILL EXISTS. Re-scoping the shared ceiling without adding
    /// one here would make a Lab session unbounded, which is the failure this
    /// whole budget family exists to prevent.
    public static func labSessionBudgetExhausted(labActive: Bool,
                                                 sessionFrames: Int) -> Bool {
        labActive && sessionFrames >= Budgets.labMaxFramesPerSession
    }

    /// The refusal name for a candidate the DRIVER declined before the shared
    /// policy was consulted. Kept distinct from `FramePolicy`'s
    /// `cadence_budget` so the two throttles can never be confused in a trace.
    public static func driverThrottleReason(_ a: Activity) -> String {
        switch a {
        case .active, .trailing: return "throttled_active"
        case .idle:              return "throttled_idle"
        }
    }
}

/// THE CAPTURE FUNNEL, COUNTED AT EVERY STAGE.
///
/// EVERY CANDIDATE MUST END SOMEWHERE. The point is that a frame missing
/// from a replay has an explainable reason rather than an absence. A counter
/// that only records successes cannot explain a hole.
public struct FrameFunnelCounters: Equatable {
    public var candidate = 0
    public var policyAdmitted = 0
    public var captured = 0
    public var dedupedExact = 0
    public var throttledActive = 0
    public var throttledIdle = 0
    public var encoded = 0
    public var queued = 0
    /// Refused by the LAB lane's own absolute session bound — never conflated
    /// with the shared `session_frame_budget`.
    public var labSessionBudget = 0
    /// Candidates at which the OWNED capture root was observed to have moved
    /// since the previous candidate — the signal that keeps a window open
    /// through a flick's deceleration, when no finger is down and the
    /// structural fingerprint is unchanged.
    ///
    /// NOT A STAGE OF THE FUNNEL, so it is absent from `unresolved`. It
    /// records why a candidate EXISTS, not what became of it.
    public var visibleMutation = 0

    public init() {}

    /// Candidates that did not become a queued frame, and are therefore
    /// accounted for by one of the refusal counters.
    public var unresolved: Int {
        candidate - (queued + dedupedExact + throttledActive + throttledIdle
                     + (policyAdmitted - min(policyAdmitted, captured)))
    }

    public var asPairs: [(String, Int)] {
        [("candidate", candidate), ("policy_admitted", policyAdmitted),
         ("captured", captured), ("deduped_exact", dedupedExact),
         ("throttled_active", throttledActive), ("throttled_idle", throttledIdle),
         ("encoded", encoded), ("queued", queued),
         ("lab_session_budget", labSessionBudget),
         ("visible_mutation", visibleMutation)]
    }
}

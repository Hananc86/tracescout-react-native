import Foundation

/// THE SERVER-SIDE PREREQUISITE A FRAME UPLOAD DEPENDS ON, MODELLED LOCALLY.
///
/// ## The defect this exists for
///
/// Build 28, physical drive `ts_1789039752994_d955ded35cf3` and the Diagnostics
/// smoke `ts_1789043024017_1bf0dfe431cc`, measured on production rows:
///
/// the frame byte route does `findSessionRow(projectId, sessionId)` and
/// 404s `session_not_found` when the row is absent.
///
/// ONLY the record-chunk route can ORIGINATE a mobile session row, and only
/// from a chunk that CARRIES `mobile_session_start`.
///
/// So every frame captured before that chunk is accepted is addressed to a
/// session the server does not know yet. The client classified the 404 correctly
/// transient, retained, never quarantined — but each refusal still SPENT one
/// of the eight attempts, and eight attempts are consumed in under ~2 s. In the
/// smoke, 3 of 7 frames exhausted before the prerequisite appeared 2.3 s in; in
/// the physical drive the prerequisite took 21.5 s and 19 frames exhausted.
///
/// ## Two protections, not one
///
/// 1. **This barrier.** While a session is known to be PENDING, its frames are
/// not admitted at all — they spend no attempt, no operation deadline and no
/// window slot, and their bytes stay durably queued.
/// 2. **The body-coded dependency disposition** in `FrameWindowPump.complete`.
/// A barrier depends on local state that a process restart does not carry;
/// the fallback makes correctness independent of that state.
///
/// The barrier makes the fallback RARE for a new current session. The fallback
/// makes the barrier UNNECESSARY for correctness. Neither is sufficient alone,
/// which is why both ship together.
///
/// ## The state model is deliberately the smallest one that works
///
/// One session, in memory, no durable ledger:
///
/// publishSession(S) -> S is PENDING
/// a chunk ACK for S -> S is READY
/// any other session id -> UNKNOWN
///
/// **UNKNOWN never blocks.** A frame belonging to a historical session, or to a
/// session whose registration this process never observed, is admitted and — if
/// the server does not know it — takes the dependency path. Blocking on UNKNOWN
/// would need a durable "ready forever" ledger, and a persisted ready flag is
/// stale authority the moment a session is purged server-side. Nothing here is
/// written to disk, so a restart cannot resurrect a claim about the server.
///
/// ## Why a chunk ACK is the right READY signal
///
/// NOT "the record request started". The row is created during request
/// processing, so a request that is in flight, or that failed after the insert,
/// tells the client nothing it can act on. The ACK is the first CLIENT-OBSERVABLE
/// point at which the prerequisite is established.
///
/// ANY acknowledged batch for S proves it, not only the opener's batch. The
/// server refuses a chunk for an unknown session unless that chunk carries the
/// opener, so an `accepted`/`duplicate` acknowledgement for session S is itself
/// proof that the row for S exists. Requiring "the batch that contained
/// `mobile_session_start`" would need the opener's sequence threaded through the
/// bridge to say something the ACK already says.
public enum SessionRegistrationState: String {
    /// The opener is durably queued; the server has not acknowledged a chunk for
    /// this session yet. Frame operations for it are NOT eligible.
    case pending
    /// A chunk for this session was acknowledged, so the session row exists.
    case ready
    /// This process has no observation about this session. Not a blocker — the
    /// dependency disposition covers it.
    case unknown
}

/// Thread-safe holder for the CURRENT session's registration state.
///
/// It knows about one session at a time on purpose: the current session is the
/// only one whose registration this process can observe, and a map keyed by
/// session id would accumulate authority about sessions nobody is publishing.
public final class SessionRegistry {

    private let lock = NSLock()
    private var sessionId: String?
    private var value: SessionRegistrationState = .unknown
    private var pendingSinceMs: Int64 = -1
    private var readyAtMs: Int64 = -1

    public init() {}

    /// A session was published: its opener is durably queued and a chunk
    /// carrying it has not been acknowledged yet.
    public func markPending(sessionId s: String, nowMs: Int64) {
        lock.lock(); defer { lock.unlock() }
        sessionId = s
        value = .pending
        pendingSinceMs = nowMs
        readyAtMs = -1
    }

    /// A chunk for `s` was acknowledged `accepted` or `duplicate`.
    ///
    /// - Returns: true only on the PENDING -> READY transition, so the caller can
    /// trigger exactly one drain of the frames that were waiting. A repeat ACK
    /// for an already-ready session returns false rather than re-triggering.
    @discardableResult
    public func markReady(sessionId s: String, nowMs: Int64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard sessionId == s else { return false }
        guard value != .ready else { return false }
        value = .ready
        readyAtMs = nowMs
        return true
    }

    /// The state of ONE session id. Anything that is not the current session is
    /// `unknown` — see the type comment for why that is the whole point.
    public func state(of s: String) -> SessionRegistrationState {
        lock.lock(); defer { lock.unlock() }
        guard let cur = sessionId, cur == s else { return .unknown }
        return value
    }

    /// A frame operation may spend an HTTP attempt unless its session is known
    /// to be pending. Written as its own name because this is the rule the pump
    /// consumes, and reading `!=.pending` at the call site would invite someone
    /// to "tighten" it to `==.ready` and starve every historical frame.
    public func mayAttempt(sessionId s: String) -> Bool {
        state(of: s) != .pending
    }

    /// Cleared when the runtime state is reset (the fixture's reset path), so a
    /// destroyed queue cannot leave a claim about a session that no longer has
    /// one.
    public func reset() {
        lock.lock(); defer { lock.unlock() }
        sessionId = nil; value = .unknown
        pendingSinceMs = -1; readyAtMs = -1
    }

    public struct Snapshot: Equatable {
        public let sessionId: String?
        public let state: SessionRegistrationState
        public let pendingSinceMs: Int64
        public let readyAtMs: Int64

        public init(sessionId: String?, state: SessionRegistrationState,
                    pendingSinceMs: Int64, readyAtMs: Int64) {
            self.sessionId = sessionId; self.state = state
            self.pendingSinceMs = pendingSinceMs; self.readyAtMs = readyAtMs
        }

        /// -1 when either end is unknown. The measured cost of the barrier.
        public var waitedMs: Int64 {
            guard pendingSinceMs >= 0, readyAtMs >= 0 else { return -1 }
            return readyAtMs - pendingSinceMs
        }
    }

    public func snapshot() -> Snapshot {
        lock.lock(); defer { lock.unlock() }
        return Snapshot(sessionId: sessionId, state: value,
                        pendingSinceMs: pendingSinceMs, readyAtMs: readyAtMs)
    }
}

/// The dependency backoff schedule for the `session_not_found` fallback.
///
/// IT ESCALATES, AND THAT IS NOT DECORATION. A flat delay bounds the rate at
/// `frames / delay` — with the smoke's 7 frames and a 250 ms delay that is 28
/// requests per second against a prerequisite that may never arrive (a purged
/// historical session). The base handles the ordinary race in one step; the
/// escalation handles the case where nothing is coming.
///
/// The BASE is chosen by measurement, not by preference — see
/// `SessionDependencyBenchmarkTests`.
public enum SessionDependencyBackoff {

    /// SELECTED BY THE MEASURED SWEEP, and it is not the value that was
    /// preselected. `SessionDependencyBenchmarkTests` drove 250 / 500 / 1000 ms
    /// against the real schedule with the prerequisite arriving at 2.3 s:
    ///
    /// base dep reqs peak/s attempts spent recovery
    /// 250 28 21 0 1450 ms
    /// 500 21 14 0 1200 ms
    /// 1000 14 7 0 700 ms
    ///
    /// THE RESULT IS COUNTER-INTUITIVE AND THE REASON MATTERS. A SHORTER base
    /// recovers SLOWER, because the frame accumulates more waits before the
    /// prerequisite arrives and the escalation has therefore already pushed its
    /// cooldown further out. 1000 ms is not a compromise between rate and
    /// latency — it dominates both other candidates on both axes. 500 was the
    /// value this file shipped with before the sweep ran, and the sweep is what
    /// changed it.
    public static let baseMs: Int64 = 1_000
    /// A hard ceiling, so a session that never registers settles into one
    /// request per frame per 30 s instead of a permanent stream.
    public static let capMs: Int64 = 30_000

    /// `waits` is the number of dependency refusals this frame has already had,
    /// INCLUDING the one being handled — so the first wait is `base`.
    ///
    /// `base` AND `cap` ARE PARAMETERS WITH PRODUCT DEFAULTS, and the seam is
    /// deliberate: the sweep that CHOOSES the base has to drive the real
    /// schedule, and a benchmark that re-implemented it would be measuring its
    /// own arithmetic. No production call site passes them.
    public static func delayMs(waits: Int,
                               base: Int64 = SessionDependencyBackoff.baseMs,
                               cap: Int64 = SessionDependencyBackoff.capMs) -> Int64 {
        guard waits > 0 else { return base }
        // Doubling, saturating. `waits` is bounded in practice by the cap, and
        // the shift is clamped so a pathological counter cannot overflow.
        let steps = Swift.min(waits - 1, 16)
        let scaled = base << Int64(steps)
        return scaled <= 0 ? cap : Swift.min(scaled, cap)
    }
}

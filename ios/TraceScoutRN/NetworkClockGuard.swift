import Foundation

/// THE REPLAY-CLOCK START CONVERSION, WITH A FAIL-CLOSED DIVERGENCE DETECTOR.
///
/// `URLSessionTaskMetrics.taskInterval.start` is the SYSTEM's own measurement of
/// when the task started — but it is a **wall-clock `Date`**, and the replay
/// timeline is monotonic. The conversion is:
///
/// projectedStartMono = anchorMono − (anchorWall − taskStartWall)
///
/// with the (wall, mono) anchor taken ADJACENTLY, from the same two clocks the
/// SDK already reads together on every single event
/// (`Date` / `DispatchTime.now.uptimeNanoseconds`).
///
/// THIS IS NOT STAMPING THE COMPLETION CALLBACK AS THE START. The instant is
/// real and system-measured; only its clock domain changes. With the conversion
/// removed the honest output is REFUSAL, never "now".
///
/// AND THE SIMULATOR COULD NOT EXERCISE THE CASE THAT BREAKS IT.
/// `DispatchTime.uptimeNanoseconds` is `CLOCK_UPTIME_RAW`: it **pauses while the
/// device is asleep** while the wall clock does not, and the wall clock can also
/// be stepped by NTP or by the user. A simulator never sleeps and its clock
/// never jumps, so the measured 3.091 µs worst-case conversion error covers the
/// ARITHMETIC and nothing else. Emitting the projection blindly on real hardware
/// would place a request on the replay clock by however long the device slept.
///
/// So the projection is only accepted when the wall↔monotonic RELATIONSHIP is
/// demonstrably unchanged across the task's own window.
public enum NetworkClockGuard {

    /// One adjacent reading of both clocks. The SDK takes these constantly; the
    /// guard needs two of them bracketing the task.
    public struct Anchor: Equatable {
        public let wallMs: Double
        public let monoNs: Int64
        public init(wallMs: Double, monoNs: Int64) {
            self.wallMs = wallMs
            self.monoNs = monoNs
        }
        /// wall − mono, in milliseconds. Constant while both clocks agree;
        /// a device sleep or a clock step moves it.
        var offsetMs: Double { wallMs - Double(monoNs) / 1_000_000.0 }
    }

    /// EVERY NUMBER HERE IS DERIVED, NOT CHOSEN.
    ///
    /// `baseToleranceMs` — the measured adjacent-pair noise on a real Apple
    /// runtime was **worst 3.091 µs, mean 0.943 µs over 200 samples / 506 ms**
    /// (S4-A run 34548196474). 50 ms is ~16,000× that worst case: far above any
    /// arithmetic or scheduling jitter, and far below the smallest event the
    /// guard exists to catch (a suspend or a clock step is seconds).
    ///
    /// `driftPpm` — the wall clock is legitimately SLEWED by time
    /// synchronisation, so the offset drifts even when nothing is wrong, and a
    /// fixed tolerance would false-refuse a long task. NTP's own maximum slew is
    /// 500 ppm; 2000 ppm is 4× that, so ordinary correction can never trip the
    /// guard while a real discontinuity still does.
    ///
    /// Allowed divergence over a window of W ms is `baseToleranceMs + W × 2e-3`,
    /// e.g. 50 ms for an instant task, 70 ms across 10 s, 170 ms across a minute.
    public static let baseToleranceMs: Double = 50.0
    public static let driftPpm: Double = 2000.0

    public enum Refusal: String {
        case wallJumpForward = "wall_jump_forward"
        case wallJumpBackward = "wall_jump_backward"
        case startAfterCompletion = "start_after_completion"
        case impossibleDuration = "impossible_duration"
        case missingAnchor = "missing_anchor"
    }

    public enum Result: Equatable {
        case accepted(startMonoNs: Int64)
        case refused(String)

        public var startMonoNs: Int64? {
            if case let .accepted(v) = self { return v }
            return nil
        }
        public var refusalReason: String? {
            if case let .refused(r) = self { return r }
            return nil
        }
    }

    /// - Parameters:
    /// - before: the most recent anchor taken AT OR BEFORE the task started.
    /// Using the newest such anchor rather than the session's first is the
    /// whole mitigation: it shrinks the window the clocks must agree over
    /// from the age of the session to the lifetime of this one task.
    /// - completion: a fresh adjacent pair taken inside the metrics callback.
    /// - taskStartWallMs: `metrics.taskInterval.start`, the system's own
    /// measurement.
    public static func project(
        taskStartWallMs: Double,
        before: Anchor?,
        completion: Anchor
    ) -> Result {
        guard let before = before else {
            // No bracketing anchor means the relationship cannot be checked at
            // all. Unknown is not permission to emit.
            return .refused(Refusal.missingAnchor.rawValue)
        }

        // The window the two clocks must agree over. Measured in the WALL
        // domain because that is the domain the task-start timestamp lives in.
        let windowMs = max(0.0, completion.wallMs - min(before.wallMs, taskStartWallMs))
        let allowed = baseToleranceMs + windowMs * (driftPpm / 1_000_000.0)
        let delta = completion.offsetMs - before.offsetMs
        if delta > allowed {
            // Wall advanced relative to uptime: a forward clock step, or the
            // device slept (uptime pauses, wall does not).
            return .refused(Refusal.wallJumpForward.rawValue)
        }
        if delta < -allowed {
            return .refused(Refusal.wallJumpBackward.rawValue)
        }

        let backMs = completion.wallMs - taskStartWallMs
        let projected = completion.monoNs - Int64((backMs * 1_000_000.0).rounded())

        // A start after the completion instant is impossible and would place the
        // request in the future on the replay clock.
        if projected > completion.monoNs { return .refused(Refusal.startAfterCompletion.rawValue) }
        let durationMs = (completion.monoNs - projected) / 1_000_000
        if durationMs < 0 || durationMs > 3_600_000 {
            return .refused(Refusal.impossibleDuration.rawValue)
        }
        if projected < 0 { return .refused(Refusal.impossibleDuration.rawValue) }
        return .accepted(startMonoNs: projected)
    }

    /// A rolling store of the newest anchor, updated wherever the SDK already
    /// reads both clocks. Deliberately tiny and lock-guarded: it is read from
    /// the URLSession delegate queue and written from the capture path.
    public final class RollingAnchor {
        private var latest: Anchor?
        private let lock = NSLock()
        public init() {}

        public func update(_ a: Anchor) {
            lock.lock(); defer { lock.unlock() }
            // Monotonic by construction — never let a stale reading overwrite a
            // newer one if two threads race.
            if let cur = latest, cur.monoNs >= a.monoNs { return }
            latest = a
        }

        /// The newest anchor at or before `monoNs`, or the newest we have.
        /// Returns nil when nothing has ever been recorded, and the caller
        /// REFUSES on nil rather than falling back to the completion anchor —
        /// bracketing a window with itself would make the check vacuous.
        public func snapshot() -> Anchor? {
            lock.lock(); defer { lock.unlock() }
            return latest
        }
    }
}

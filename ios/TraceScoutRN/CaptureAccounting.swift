import Foundation

/// PASSIVE CAPTURE ACCOUNTING — the measurements a customer cadence decision
/// needs, taken INSIDE each actual capture and recorded AFTER every decision.
///
/// ## Why this exists
///
/// The cadence plan (`PLAN-customer-cadence-v3`) needs numbers nobody has:
/// what a capture COSTS to execute, how late the driver's tick actually fires
/// against the delay it asked for, and how many encoded bytes a session
/// produces — and it needs route-entry observations separated from
/// steady-state ones, because the one measured route-entry interval (2876 ms
/// in `webview_enter`) is a SCHEDULING interval on a main thread that is busy
/// laying out the incoming screen, not a capture duration. Reading it as a
/// capture cost would size a budget from the wrong quantity.
///
/// ## What it is, and what it deliberately is not
///
/// IT DECIDES NOTHING. Every record is written after the branch it describes
/// has been taken; nothing reads a value from here to admit, refuse, deliver or
/// drop anything. `FramePolicy.decide`, `FrameCadence`, the driver, the store
/// admission and the delivery pipeline are untouched. An absent recorder costs
/// a `nil` check and measures nothing.
///
/// FOUR BYTE QUANTITIES, NEVER DERIVED FROM ONE ANOTHER:
///
/// produced bytes the rasterizer ENCODED, counted at capture, whether or
/// not the frame was later admitted or enqueued
/// enqueued bytes actually APPENDED to the durable frame store
/// pending the store's occupancy SAMPLED AT EACH ENQUEUE (last, and the
/// maximum OBSERVED at those samples). It is NOT a continuous
/// high-water mark: occupancy between enqueues is never read,
/// so the true peak may be higher than the reported maximum.
/// acked ENCODED bytes of frames the server ACKNOWLEDGED (accepted /
/// duplicate, apart). This is NOT transmission volume: a retry
/// re-sends the same bytes and is credited once, and a request
/// whose acknowledgement was lost was transmitted and is
/// credited nothing. Bytes on the wire are not measured here.
///
/// A refused enqueue is counted on its own; a produced-but-refused frame is
/// therefore visible as `produced - enqueued`, which a single "frame bytes"
/// counter could never show. None of these is inferred from a count of frames
/// times an average.
///
/// SCHEDULING LATENCY IS NOT EXECUTION COST. The driver asks `sched(delay)`
/// and the callback fires when the main thread gets to it; the difference is
/// what the platform charged, and it is recorded per tick kind, separately
/// from the phases the capture itself spent.
///
/// IT CARRIES NO CUSTOMER CONTENT. Route ids (shape-filtered), nanosecond
/// counts, byte counts, closed-vocabulary labels. No text, no pixels, no
/// hierarchy, no identifiers beyond the screen id the decision log already
/// carries.
public enum CaptureAccounting {

    // ── phase timing ────────────────────────────────────────────────────────

    /// The phases one capture executes. `walk` is the hierarchy traversal;
    /// the other four are the raster path inside `FrameRasterizer`.
    public enum Phase: String, CaseIterable, Equatable {
        case walk, redaction, raster, verify, encode
    }

    /// An opt-in accumulator threaded through ONE capture. `nil` at every
    /// call site means "measure nothing", which is the default everywhere.
    ///
    /// THE METER MUST BE FORWARDED AT EVERY HOP. `observe` → `observeOnMain`
    /// → `FrameRasterizer.capture` → `captureOnMain`; a hop that drops it
    /// silently measures zero and reports it as a real number. A source guard
    /// (`verify-capture-accounting.mjs`) pins each forwarding.
    public final class PhaseMeter {
        public private(set) var walkNanos: Int64 = 0
        public private(set) var redactionNanos: Int64 = 0
        public private(set) var rasterNanos: Int64 = 0
        public private(set) var verifyNanos: Int64 = 0
        public private(set) var encodeNanos: Int64 = 0
        public init() {}

        public func note(_ phase: Phase, nanos: Int64) {
            let ns = Swift.max(0, nanos)
            switch phase {
            case .walk:      walkNanos      += ns
            case .redaction: redactionNanos += ns
            case .raster:    rasterNanos    += ns
            case .verify:    verifyNanos    += ns
            case .encode:    encodeNanos    += ns
            }
        }

        /// Redaction + raster + verify + encode: everything after the walk.
        public var rasterPathNanos: Int64 { redactionNanos + rasterNanos + verifyNanos + encodeNanos }
        /// The whole capture: walk + raster path.
        public var executionNanos: Int64 { walkNanos + rasterPathNanos }

        public func snapshot() -> Phases {
            Phases(walkNanos: walkNanos, redactionNanos: redactionNanos, rasterNanos: rasterNanos,
                   verifyNanos: verifyNanos, encodeNanos: encodeNanos)
        }
    }

    /// The meter's values, frozen. What crosses from the capture layer to the
    /// bridge, so the bridge cannot observe a meter still being written to.
    public struct Phases: Equatable {
        public let walkNanos: Int64
        public let redactionNanos: Int64
        public let rasterNanos: Int64
        public let verifyNanos: Int64
        public let encodeNanos: Int64
        public init(walkNanos: Int64 = 0, redactionNanos: Int64 = 0, rasterNanos: Int64 = 0,
                    verifyNanos: Int64 = 0, encodeNanos: Int64 = 0) {
            self.walkNanos = walkNanos; self.redactionNanos = redactionNanos
            self.rasterNanos = rasterNanos; self.verifyNanos = verifyNanos
            self.encodeNanos = encodeNanos
        }
        public var rasterPathNanos: Int64 { redactionNanos + rasterNanos + verifyNanos + encodeNanos }
        public var executionNanos: Int64 { walkNanos + rasterPathNanos }
    }

    /// Time `body` under `phase` when a meter is present; run it unchanged
    /// when not. The measured body is the ONLY thing between the two clock
    /// reads, so the number is the phase and nothing else.
    @inline(__always)
    public static func timed<T>(_ meter: PhaseMeter?, _ phase: Phase, _ body: () -> T) -> T {
        guard let m = meter else { return body() }
        let t0 = DispatchTime.now().uptimeNanoseconds
        let out = body()
        m.note(phase, nanos: Int64(DispatchTime.now().uptimeNanoseconds &- t0))
        return out
    }

    // ── route entry vs steady state ─────────────────────────────────────────

    /// Which regime a candidate belongs to. Three labels, so a reader can
    /// merge the first two into "route entry" or keep them apart.
    public enum Regime: String, Equatable, CaseIterable {
        /// The FIRST candidate on a route — the navigation-triggered snapshot,
        /// taken while the incoming screen is still being laid out.
        case routeEntry = "route_entry"
        /// A later candidate still inside `routeEntryWindowMs` of the route
        /// change — the driver's continuation ticks on a settling screen.
        case routeSettle = "route_settle"
        /// Everything after the window.
        case steadyState = "steady_state"
    }

    /// A CLASSIFICATION WINDOW, NOT A BUDGET. Chosen from the one measured
    /// route-entry continuation interval (2876 ms, `webview_enter`, 2026-09-18)
    /// so that the tick which follows a navigation lands in `route_settle`
    /// rather than being averaged into steady state. Nothing reads it to
    /// decide anything; changing it re-labels observations and nothing else.
    public static let routeEntryWindowMs: Int64 = 3_000

    /// One route's candidate position. Pure; the bridge holds one under its
    /// lock and updates it where it already records the driver route.
    public struct RouteTracker: Equatable {
        public private(set) var routeId: String? = nil
        public private(set) var entryWallMs: Int64 = -1
        public private(set) var ordinal: Int = -1
        public init() {}

        /// Note a candidate on `routeId` at `nowMs`. A different route starts
        /// a new entry at ordinal 0; the same route advances the ordinal.
        public mutating func note(routeId: String, nowMs: Int64) -> Position {
            if self.routeId != routeId {
                self.routeId = routeId; entryWallMs = nowMs; ordinal = 0
            } else {
                ordinal += 1
            }
            let since = Swift.max(0, nowMs - entryWallMs)
            return Position(ordinal: ordinal, msSinceEntry: since,
                            regime: CaptureAccounting.regime(ordinal: ordinal, msSinceEntry: since))
        }
    }

    public struct Position: Equatable {
        public let ordinal: Int
        public let msSinceEntry: Int64
        public let regime: Regime
    }

    /// THE ONE classification rule.
    public static func regime(ordinal: Int, msSinceEntry: Int64) -> Regime {
        if ordinal <= 0 { return .routeEntry }
        if msSinceEntry < routeEntryWindowMs { return .routeSettle }
        return .steadyState
    }

    // ── records ─────────────────────────────────────────────────────────────

    /// One capture's execution, as measured inside it.
    public struct Execution: Equatable {
        public let screenId: String
        public let position: Position
        public let phases: Phases
        /// Bytes the rasterizer encoded for THIS capture; nil when no frame was
        /// produced (walk-only, refused raster). Counted before any admission.
        public let producedBytes: Int?
        public init(screenId: String, position: Position, phases: Phases, producedBytes: Int?) {
            self.screenId = screenId; self.position = position
            self.phases = phases; self.producedBytes = producedBytes
        }
    }

    /// Which loop asked for the delay.
    public enum ScheduleKind: String, Equatable, CaseIterable {
        case driverTick = "driver_tick"
        case frameRetry = "frame_retry"
    }

    /// One scheduled callback: what was asked for and what was delivered.
    public struct Scheduling: Equatable {
        public let kind: ScheduleKind
        public let requestedMs: Int64
        public let observedMs: Int64
        public init(kind: ScheduleKind, requestedMs: Int64, observedMs: Int64) {
            self.kind = kind; self.requestedMs = requestedMs; self.observedMs = observedMs
        }
        /// Positive = the callback fired late. Never clamped: an early fire is
        /// a fact about the scheduler too.
        public var latencyMs: Int64 { observedMs - requestedMs }
    }

    // ── the bounded summary ─────────────────────────────────────────────────

    /// A closed set of totals. Int64 so a long session cannot overflow, and a
    /// dictionary keyed by this enum so a typo cannot mint a fifth quantity.
    public enum Total: String, CaseIterable, Equatable {
        case producedBytes = "produced_bytes"
        case producedFrames = "produced_frames"
        case enqueuedBytes = "enqueued_bytes"
        case enqueuedFrames = "enqueued_frames"
        case enqueueRefusedBytes = "enqueue_refused_bytes"
        case enqueueRefusedFrames = "enqueue_refused_frames"
        case pendingBytesLast = "pending_bytes_last"
        case pendingBytesMaxAtEnqueue = "pending_bytes_max_at_enqueue"
        case pendingRecordsLast = "pending_records_last"
        case acknowledgedAcceptedBytes = "acked_accepted_bytes"
        case acknowledgedAcceptedFrames = "acked_accepted_frames"
        case acknowledgedDuplicateBytes = "acked_duplicate_bytes"
        case acknowledgedDuplicateFrames = "acked_duplicate_frames"
        case executions = "executions"
        case schedulings = "schedulings"
    }

    public struct Summary: Equatable {
        /// `<regime>.<stage>` → bounded stats in MILLISECONDS. Stages:
        /// `walk_ms`, `redaction_ms`, `raster_ms`, `verify_ms`, `encode_ms`,
        /// `raster_path_ms`, `execution_ms`.
        public var execution: [String: CaptureDecisionLog.StageStats] = [:]
        /// `<kind>.latency_ms` and `<kind>.requested_ms` → bounded stats.
        public var scheduling: [String: CaptureDecisionLog.StageStats] = [:]
        /// `<regime>.produced_bytes` → bounded per-frame byte-size stats, so the
        /// SPREAD is visible and not only the total.
        public var producedBytesByRegime: [String: CaptureDecisionLog.StageStats] = [:]
        public var totals: [Total: Int64] = [:]
        public init() {}

        public func total(_ t: Total) -> Int64 { totals[t] ?? 0 }
        mutating func add(_ t: Total, _ v: Int64) { totals[t] = total(t) + v }
        mutating func set(_ t: Total, _ v: Int64) { totals[t] = v }
        mutating func max(_ t: Total, _ v: Int64) { totals[t] = Swift.max(total(t), v) }

        /// STATIC, NOT `mutating`. A mutating method taking `inout` to one
        /// of `self`'s own properties is an overlapping access to `self`, and
        /// the compiler refuses it (CI run 35473658285 — 20 errors on the
        /// first push). Operating on the table alone keeps the access exclusive.
        private static func sample(_ table: inout [String: CaptureDecisionLog.StageStats],
                                   _ key: String, _ v: Int64) {
            var s = table[key] ?? CaptureDecisionLog.StageStats()
            s.add(v)
            table[key] = s
        }

        public mutating func record(_ e: Execution) {
            let r = e.position.regime.rawValue
            let p = e.phases
            func ms(_ ns: Int64) -> Int64 { ns / 1_000_000 }
            Summary.sample(&execution, "\(r).walk_ms", ms(p.walkNanos))
            Summary.sample(&execution, "\(r).redaction_ms", ms(p.redactionNanos))
            Summary.sample(&execution, "\(r).raster_ms", ms(p.rasterNanos))
            Summary.sample(&execution, "\(r).verify_ms", ms(p.verifyNanos))
            Summary.sample(&execution, "\(r).encode_ms", ms(p.encodeNanos))
            Summary.sample(&execution, "\(r).raster_path_ms", ms(p.rasterPathNanos))
            Summary.sample(&execution, "\(r).execution_ms", ms(p.executionNanos))
            add(.executions, 1)
            if let b = e.producedBytes {
                Summary.sample(&producedBytesByRegime, "\(r).produced_bytes", Int64(b))
                add(.producedBytes, Int64(b))
                add(.producedFrames, 1)
            }
        }

        public mutating func record(_ s: Scheduling) {
            Summary.sample(&scheduling, "\(s.kind.rawValue).latency_ms", s.latencyMs)
            Summary.sample(&scheduling, "\(s.kind.rawValue).requested_ms", s.requestedMs)
            add(.schedulings, 1)
        }

        /// An enqueue attempt against the durable frame store.
        public mutating func recordEnqueue(bytes: Int, ok: Bool, pendingBytes: Int, pendingRecords: Int) {
            if ok {
                add(.enqueuedBytes, Int64(bytes)); add(.enqueuedFrames, 1)
            } else {
                add(.enqueueRefusedBytes, Int64(bytes)); add(.enqueueRefusedFrames, 1)
            }
            set(.pendingBytesLast, Int64(pendingBytes))
            max(.pendingBytesMaxAtEnqueue, Int64(pendingBytes))
            set(.pendingRecordsLast, Int64(pendingRecords))
        }

        /// A server-settled frame. `duplicate` is kept apart from `accepted`:
        /// both are uploads, only one is new storage.
        public mutating func recordAcknowledged(bytes: Int, duplicate: Bool) {
            if duplicate {
                add(.acknowledgedDuplicateBytes, Int64(bytes)); add(.acknowledgedDuplicateFrames, 1)
            } else {
                add(.acknowledgedAcceptedBytes, Int64(bytes)); add(.acknowledgedAcceptedFrames, 1)
            }
        }

        /// Flat, greppable lines for the `capture-summary-1` artifact. Every
        /// line is prefixed `acct` so a reader can `grep '^acct '`.
        public func render() -> String {
            var out = ""
            func stats(_ kind: String, _ table: [String: CaptureDecisionLog.StageStats]) {
                for k in table.keys.sorted() {
                    let t = table[k]!
                    out += "acct \(kind) \(k) count=\(t.count) p50=\(t.percentile(0.5))"
                        + " p90=\(t.percentile(0.9)) max=\(t.max) window=\(t.samples.count)\n"
                }
            }
            stats("exec", execution)
            stats("sched", scheduling)
            stats("bytes", producedBytesByRegime)
            for t in Total.allCases where totals[t] != nil {
                out += "acct total \(t.rawValue)=\(totals[t]!)\n"
            }
            return out
        }
    }
}

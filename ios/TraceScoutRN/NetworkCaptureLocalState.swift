import Foundation

/// WHAT THIS PROCESS CAN TRUTHFULLY SAY ABOUT NETWORK CAPTURE — AND NOTHING MORE.
///
/// Pure and UIKit-free, so the rule is unit-tested rather than read off a screen.
///
/// THREE DISTINCTIONS THIS EXISTS TO KEEP APART:
///
/// 1. DISABLED is not BROKEN. With capture off the observer returns on its
/// first line and produces nothing — identical, by event count, to an
/// observer that never fired. `observerCallbacks` is incremented BEFORE the
/// capture gate, so "off, and the seam is alive" is a readable state.
/// 2. ENABLED is not VERIFIED. An enabled process that has produced no record
/// has proven nothing in either direction: an empty list means "no eligible
/// request completed yet", never "capture is broken".
/// 3. LOCAL is not SERVER. A record counted here reached the durable queue. It
/// may still be refused (the server gate is a separate authority), retried
/// or never uploaded. No state in this file claims acceptance, and
/// `serverAcceptance` has exactly one value so that cannot drift.
public enum NetworkCaptureLocalState: String, Equatable {
    /// Persisted OFF and this process runs with it OFF.
    case disabled = "DISABLED"
    /// The persisted value differs from the one this process applied at start.
    case restartRequired = "RESTART_REQUIRED"
    /// This process has not reached a configured `start` yet.
    case notStarted = "NOT_STARTED"
    /// Applied ON; no network record has reached the local queue.
    case enabledUnverified = "ENABLED_UNVERIFIED"
    /// Applied ON and at least one record was APPENDED to the local durable queue
    /// since this process started. Says nothing about what the queue holds now.
    case enabledProducingLocally = "ENABLED_PRODUCING_LOCALLY"

    public struct Inputs: Equatable {
        public let persistedOptIn: Bool
        public let appliedThisProcess: Bool
        public let optInEvaluatedThisProcess: Bool
        public let observerInstalled: Bool
        public let observerCallbacks: Int
        public let recordsAppendedSinceProcessStart: Int
        public init(persistedOptIn: Bool, appliedThisProcess: Bool,
                    optInEvaluatedThisProcess: Bool, observerInstalled: Bool,
                    observerCallbacks: Int, recordsAppendedSinceProcessStart: Int) {
            self.persistedOptIn = persistedOptIn
            self.appliedThisProcess = appliedThisProcess
            self.optInEvaluatedThisProcess = optInEvaluatedThisProcess
            self.observerInstalled = observerInstalled
            self.observerCallbacks = observerCallbacks
            self.recordsAppendedSinceProcessStart = recordsAppendedSinceProcessStart
        }
    }

    /// The only thing this process knows about the server.
    public static let serverAcceptance = "NOT KNOWN FROM THIS DEVICE"

    /// ORDER IS THE RULE. A mismatch is reported before anything else, because
    /// every other line on the screen would otherwise describe a setting the
    /// running process is not using.
    public static func resolve(_ i: Inputs) -> NetworkCaptureLocalState {
        if !i.optInEvaluatedThisProcess { return .notStarted }
        if i.persistedOptIn != i.appliedThisProcess { return .restartRequired }
        if !i.appliedThisProcess { return .disabled }
        return i.recordsAppendedSinceProcessStart > 0 ? .enabledProducingLocally : .enabledUnverified
    }

    /// Operator-facing lines. Counts and booleans only: no URL, host, route,
    /// status or header can appear because none is an input.
    public static func describe(_ i: Inputs) -> String {
        let state = resolve(i)
        var lines = ["NETWORK CAPTURE: \(state.rawValue)"]
        lines.append("  saved opt-in: \(i.persistedOptIn ? "ON" : "OFF")"
                     + " · this process: \(i.optInEvaluatedThisProcess ? (i.appliedThisProcess ? "ON" : "OFF") : "not started")")
        switch state {
        case .restartRequired:
            lines.append("  RESTART REQUIRED — the opt-in is applied at the first configured start of a process; the saved value applies after a restart.")
        case .disabled:
            lines.append("  Off by choice. Zero network records is the expected result, not a fault.")
        case .enabledUnverified:
            lines.append("  On, but UNVERIFIED: no network record has been produced yet.")
            lines.append("  An empty list means no eligible request completed — not that capture is broken.")
        case .enabledProducingLocally:
            lines.append("  On, and producing records into the LOCAL queue.")
        case .notStarted:
            lines.append("  The SDK has not started in this process. Saving a configuration will apply the saved setting.")
        }
        lines.append("  observer installed: \(i.observerInstalled ? "yes" : "NO")"
                     + " · requests seen by observer: \(i.observerCallbacks)")
        // NOT queue depth: it never decreases when records upload, and a fixture
        // reset that empties the queue does not reset it.
        lines.append("  network records appended locally since process start: \(i.recordsAppendedSinceProcessStart)")
        lines.append("    (not current queue depth; survives a fixture reset; not server acceptance)")
        lines.append("  server acceptance: \(serverAcceptance)")
        return lines.joined(separator: "\n")
    }
}

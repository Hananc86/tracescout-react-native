import Foundation

/// Which screen a NETWORK REQUEST belongs to, resolved from the instant the
/// request STARTED.
///
/// WHY THIS EXISTS — THE iOS SEAM IS COMPLETION-ONLY.
/// `NetworkCaptureCore.StartContext.screenId` has been `nil` on iOS since M3.2
/// and its own comment states the reason: the only seam the producer installs
/// is `URLSession:task:didFinishCollectingMetrics:`, which arrives when the
/// request FINISHES. A request begun on screen A and completed after the user
/// navigated to B belongs to A, so reading "the current screen" in the callback
/// would be SYSTEMATICALLY WRONG for exactly the slow requests an engineer
/// opens a replay to investigate. Android has no such problem: its seam is an
/// OkHttp interceptor, so `RnNetworkHost` resolves `host.currentScreenId` at
/// request START and is correct by construction.
///
/// The fix is therefore not a later read — it is a HISTORY.
/// `NetworkClockGuard.project` already hands the observer `startMonoNs`: the
/// request's start expressed in the SAME `DispatchTime.uptimeNanoseconds`
/// domain this timeline stamps its entries with (both are
/// `CLOCK_UPTIME_RAW`, which pauses while the device sleeps — consistently for
/// the entry and the lookup, because it is one clock). The screen that was
/// current at that instant becomes a LOOKUP rather than a guess.
///
/// IT FAILS CLOSED, AND THAT IS THE POINT. Every answer is either the screen
/// that genuinely covered the instant, or `nil`. It never returns the nearest
/// screen, never the newest, and never the oldest retained one. A plausible
/// wrong route is worse than a missing one: a missing one is visibly missing,
/// and `screenId` is already an optional the server accepts as absent.
public final class NetworkScreenTimeline {

    /// One screen interval: the screen, and the instant it became current.
    /// The interval it covers ends where the next entry begins — it is never
    /// stored, so two entries can never disagree about one boundary.
    struct Entry: Equatable {
        let screenId: String
        let enteredMonotonicNanos: Int64
    }

    /// A STATED TRADE, NOT A DERIVED LIMIT. At `capacity` retained intervals
    /// a request that is still in flight across more than that many screen
    /// changes resolves to `nil` instead of a wrong screen. 64 is chosen as a
    /// depth no realistic request outlives, while keeping the structure small
    /// enough to be read on the URLSession delegate queue; it is not a product
    /// limit derived from a measurement, and naming it here is how it stays
    /// reviewable rather than becoming a literal inside the ring logic.
    public static let defaultCapacity = 64

    private let capacity: Int
    private var entries: [Entry] = []
    private let lock = NSLock()

    public init(capacity: Int = NetworkScreenTimeline.defaultCapacity) {
        // A non-positive capacity would make every lookup nil while still
        // accepting notes — a structure that silently holds nothing. Refuse it
        // here rather than let a caller configure the feature off by accident.
        self.capacity = max(1, capacity)
    }

    /// Record that `screenId` became current at `monotonicNanos`.
    ///
    /// Returns `true` when the timeline changed, so a caller (and a test) can
    /// tell a recorded transition from an ignored one. Three inputs are
    /// deliberately ignored:
    ///
    /// * an empty screen id — `InteractionProducer` already treats that as
    /// "no route", and a blank interval would shadow the real one;
    /// * a repeat of the screen already newest — the interval CONTINUES, and
    /// keeping the ORIGINAL entry time is load-bearing: moving the boundary
    /// forward on a re-snapshot of the same route would orphan a request
    /// that started just before it;
    /// * a note at or before the newest entry — uptime never moves backwards,
    /// so this is a racing or replayed caller, and admitting it would
    /// corrupt the ordering every lookup depends on.
    @discardableResult
    public func note(screenId: String, atMonotonicNanos monotonicNanos: Int64) -> Bool {
        guard !screenId.isEmpty else { return false }
        lock.lock(); defer { lock.unlock() }
        if let newest = entries.last {
            if newest.screenId == screenId { return false }
            if monotonicNanos <= newest.enteredMonotonicNanos { return false }
        }
        entries.append(Entry(screenId: screenId, enteredMonotonicNanos: monotonicNanos))
        if entries.count > capacity { entries.removeFirst(entries.count - capacity) }
        return true
    }

    /// The screen that was current at `monotonicNanos`, or `nil` when the
    /// timeline cannot answer for that instant — nothing recorded yet, the
    /// instant precedes the first recorded screen, or its interval has been
    /// evicted.
    public func screen(at monotonicNanos: Int64) -> String? {
        lock.lock(); defer { lock.unlock() }
        // Newest entry at or before the instant. Walked backwards because the
        // answer for a just-completed request is almost always the last entry,
        // and the ring is bounded so the worst case is `capacity` steps.
        //
        // THE FALL-THROUGH IS THE FAIL-CLOSED RULE, AND IT IS THE ONLY ONE.
        // An instant before the oldest RETAINED entry matches nothing and
        // returns nil — nothing recorded yet, before the first screen, and an
        // evicted interval are ONE case, because the retained entries ARE the
        // window this type can answer for. A first draft also kept a separate
        // oldest-retained watermark to refuse those instants explicitly; a
        // mutation control removed it and no answer changed, and an exhaustive
        // comparison over every ordering of up to five intervals (540,000
        // cases) found 0 disagreements. A second gate here would be dead state
        // that reads like a safeguard.
        for entry in entries.reversed() where entry.enteredMonotonicNanos <= monotonicNanos {
            return entry.screenId
        }
        return nil
    }

    /// Drop every interval. Called when a session begins: screen ids are
    /// session-scoped, so carrying one across a session boundary could attribute
    /// a request to a screen from a session that has ended.
    public func reset() {
        lock.lock(); defer { lock.unlock() }
        entries.removeAll()
    }

    /// The retained intervals, oldest first. Test-only reader: production has
    /// exactly one question for this type and it is `screen(at:)`.
    var retainedForTesting: [Entry] {
        lock.lock(); defer { lock.unlock() }
        return entries
    }
}

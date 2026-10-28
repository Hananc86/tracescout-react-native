import Foundation

/// Freshness policy for the bounded frame store: WHO gets evicted when it is
/// full, and WHOSE frames drain first.
///
/// ## The measured defect this exists for
///
/// Build 22 read the real device store during a live session:
///
/// frameStore 149 records · 4,069,122 B of a 4,194,304 B cap (~97% full)
/// enqueue 496 attempts · 139 ok · 357 FAILED, all reason=store_cap
/// drain still walking a SEPTEMBER-6 session while the new session
/// was already active; 112 of its posts came back 202 DUPLICATE
/// current 0 frozen · 0 posts
///
/// A bounded GLOBAL FIFO with no freshness or session priority retained stale
/// records, drained them ahead of the active session, and — once full —
/// DROP-NEWEST sacrificed the fresh frames. The frames it was spending the
/// store and the drain on were ones the server ALREADY HAD: 112 duplicates.
///
/// So the store was ~97% full of evidence with no remaining value, and the
/// session actually being certified could not enqueue at all.
///
/// ## The two rules
///
/// 1. **Admission preserves freshness.** When a new frame does not fit, make
/// room by evicting the OLDEST records, preferring records that belong to
/// some OTHER session — the ones whose session has ended and whose value is
/// lowest. Only if the store holds nothing but the current session do we
/// evict our own oldest frames. Newest-wins, because the newest frame is the
/// only one that can still describe what the user is doing now.
///
/// 2. **The active session drains first.** Admission alone is not enough: a
/// fresh frame that gets in must not then sit behind days of historical
/// FIFO. Current-session records are served first, each group keeping its own
/// order, and the SAME pass continues into the historical records afterwards
/// so they still make progress. Freshness priority must never become
/// permanent historical starvation.
///
/// ## What this deliberately does NOT do
///
/// - **It does not raise the 4 MiB cap.** Making the box bigger would hide the
/// policy defect and postpone it to a larger backlog.
/// - **It adds no TTL.** Age alone is not the problem — unbounded retention of
/// already-delivered evidence is, and priority plus eviction addresses that
/// without inventing a clock nobody has to keep.
/// - **It never reorders frames WITHIN a session.** Replay order is the
/// product; only the order BETWEEN sessions is a scheduling choice.
/// - **It is pure.** No file I/O, no store, no clock. Every decision here is
/// testable without a device, which is what lets the eviction rule be
/// negative-controlled against the old drop-newest behaviour.
public enum FrameFreshness {

    /// One stored record, reduced to what the policy is allowed to consider.
    /// Deliberately not the bytes: a policy that can read frame content is a
    /// policy that can leak it.
    public struct Entry: Equatable {
        public let sessionId: String?
        public let bytes: Int
        public init(sessionId: String?, bytes: Int) {
            self.sessionId = sessionId
            self.bytes = bytes
        }
    }

    /// Why a record was evicted. `historical` and `current` are kept apart
    /// because sacrificing someone else's finished session and sacrificing our
    /// own live evidence are different events, and a single "evicted" number
    /// would hide which one happened.
    public enum Relation: String { case historical, current }

    public struct Eviction: Equatable {
        public let indices: [Int]
        public let relation: Relation
        public let records: Int
        public let bytes: Int
    }

    public enum Decision: Equatable {
        /// Room already exists; nothing is sacrificed.
        case admit
        /// Evict these indices (store order), then admit.
        case evictThenAdmit([Eviction])
        /// The frame cannot be admitted at any occupancy — refused normally.
        case refuse(String)
    }

    /// - Parameters:
    /// - entries: stored records in STORE ORDER, oldest first.
    /// - incomingBytes: the encoded record's size, not the raw frame's.
    /// - overhead: the same slack the caller reserves today.
    public static func admit(entries: [Entry],
                             currentSessionId: String?,
                             incomingBytes: Int,
                             capBytes: Int,
                             overhead: Int) -> Decision {
        // THE PER-FRAME REFUSAL IS UNCHANGED AND COMES FIRST. A frame that
        // cannot fit an EMPTY store is not an occupancy problem, and evicting
        // the whole store to discover that would destroy every other frame for
        // nothing.
        if incomingBytes + overhead > capBytes { return .refuse("frame_byte_budget") }

        var held = entries.reduce(0) { $0 + $1.bytes }
        if held + incomingBytes + overhead <= capBytes { return .admit }

        // Oldest first within each class; historical exhausted before current.
        // `nil` sessionId is treated as historical: an unattributable record is
        // not evidence for the session being certified, and we would rather
        // sacrifice an unknown than a known-current frame.
        func indices(_ wantCurrent: Bool) -> [Int] {
            entries.enumerated().compactMap { i, e in
                let isCurrent = currentSessionId != nil && e.sessionId == currentSessionId
                return isCurrent == wantCurrent ? i : nil
            }
        }

        var out: [Eviction] = []
        for (relation, pool) in [(Relation.historical, indices(false)),
                                 (Relation.current, indices(true))] {
            var taken: [Int] = []
            var freed = 0
            for i in pool {
                if held + incomingBytes + overhead <= capBytes { break }
                taken.append(i)
                freed += entries[i].bytes
                held -= entries[i].bytes
            }
            if !taken.isEmpty {
                out.append(Eviction(indices: taken, relation: relation,
                                    records: taken.count, bytes: freed))
            }
            if held + incomingBytes + overhead <= capBytes { break }
        }

        // Every record is now a candidate, so if it still does not fit the
        // arithmetic above is wrong rather than the store being unlucky. Refuse
        // rather than evict everything and admit nothing.
        guard held + incomingBytes + overhead <= capBytes else {
            return .refuse("store_cap")
        }
        return .evictThenAdmit(out)
    }

    /// The drain order for one pass: current-session records first, then the
    /// rest, each group keeping its own relative order.
    ///
    /// Returns nil when the order is ALREADY correct, so a caller can skip a
    /// pointless rewrite of the store — the common case once the backlog has
    /// drained, and the difference between a policy that costs nothing at
    /// steady state and one that rewrites a 4 MiB file on every drain.
    public static func drainOrder(entries: [Entry],
                                  currentSessionId: String?) -> [Int]? {
        guard let cur = currentSessionId else { return nil }
        let current = entries.enumerated().filter { $0.element.sessionId == cur }.map(\.offset)
        let rest = entries.enumerated().filter { $0.element.sessionId != cur }.map(\.offset)
        // Nothing to prioritise, or nothing to prioritise it over.
        if current.isEmpty || rest.isEmpty { return nil }
        let order = current + rest
        return order == Array(entries.indices) ? nil : order
    }
}

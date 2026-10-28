import Foundation

/// # THE FIRST-PENDING DRAIN OBLIGATION — A PURE DECISION, DELIBERATELY
///
/// ## The defect this exists for, measured on a real iOS simulator
///
/// `RnTransport.enqueue` triggered a drain on exactly one condition:
/// `pendingSinceDrain >= batchThreshold` (8). So a single durable record
/// appended while the transport was idle and the app in the foreground got **no
/// future drain opportunity at all**. Measured on a Release simulator against
/// the production API: transport quiescent, store empty, one ordinary request
/// captured — `queueTail 1`, `0` rows stored, and the trace carries no
/// `upload_raw_enter` after the append. The record stranded for the life of the
/// process.
///
/// THIS IS NOT A NETWORK DEFECT, AND THE FIX MUST NOT BE ONE. Network
/// capture merely produced the first record class that routinely arrives alone
/// while nothing else is happening. Any single record of any class — a
/// navigation, a lifecycle transition, a snapshot — strands the same way. So
/// the decision below is **record-class blind**: it is not handed an event type,
/// and it has no way to ask for one.
///
/// ## Why a pure struct rather than logic inside the transport
///
/// The stranded state is a STATE MACHINE fact, and the previous shape hid it
/// inside a method that also does file IO, locking and networking — which is
/// exactly why it was invisible for as long as it was. Everything here is
/// data in, data out: the whole machine can be driven in a unit test with no
/// Foundation, no store, no clock and no simulator.
public struct FlushSchedule {

    /// What the caller must do as a result. Both may be false — "nothing to do"
    /// is a real answer and must be representable, or a caller is forced to
    /// invent an action.
    public struct Decision: Equatable {
        /// Arm ONE deadline. Never an instruction to re-arm or to postpone.
        public let armDeadline: Bool
        /// Drain now (the threshold path, which may legitimately win a race
        /// with a deadline that is already armed).
        public let drainNow: Bool
        public init(armDeadline: Bool, drainNow: Bool) {
            self.armDeadline = armDeadline
            self.drainNow = drainNow
        }
        public static let nothing = Decision(armDeadline: false, drainNow: false)
    }

    /// # MEASURED ON iOS. NOT INHERITED FROM ANDROID.
    ///
    /// Release iOS Simulator, quiescent transport, 4 candidates x 3 repetitions.
    /// Every repetition started from a torn-down durable state with no drain in
    /// flight and no prior obligation. All 12 completed, none stranded, one
    /// chunk of one record each, queue back to zero each time.
    ///
    /// candidate worst slop projected real gate margin
    /// 1000 ms 461.4 ms 4961 ms 3039 ms
    /// 2000 ms 317.4 ms 5817 ms 2183 ms <- SELECTED
    /// 3000 ms 211.4 ms 6711 ms 1289 ms rejected: margin
    /// 4000 ms 264.9 ms 7765 ms 235 ms rejected: margin
    ///
    /// "Projected real gate" adds the production downstream the local loopback
    /// sweep cannot see — upload + server commit ~1.1 s and
    /// commit -> SSE -> refresh -> DOM ~2.4 s — against the 8 s capture-to-Viewer
    /// gate.
    ///
    /// 3000 ms COMPLETED EVERY REPETITION AND IS STILL REJECTED. Its 1289 ms
    /// of margin is smaller than the uncertainty in the downstream estimate
    /// itself, only part of which has been measured on iOS — a gate resting on
    /// that would rest on a number no iOS run has produced.
    ///
    /// THAT THIS COINCIDES WITH ANDROID'S 2 s IS A COINCIDENCE, NOT AN
    /// INHERITANCE. The rejected candidates were rejected on THIS platform's
    /// numbers, and 1000 ms — what a "fastest wins" rule would pick — was beaten
    /// by 2000 ms on scheduler slop (461 ms vs 317 ms).
    ///
    /// The real bounded H rerun remains the final latency authority.
    public static let firstPendingDeadlineMsMeasured = 2000

    public let firstPendingDeadlineMs: Int
    public let batchThreshold: Int

    public init(firstPendingDeadlineMs: Int = FlushSchedule.firstPendingDeadlineMsMeasured,
                batchThreshold: Int = 8) {
        self.firstPendingDeadlineMs = firstPendingDeadlineMs
        self.batchThreshold = batchThreshold
    }

    /// A record was DURABLY APPENDED. Call this only after the append succeeded.
    ///
    /// A FAILED APPEND MUST NOT PRODUCE AN OBLIGATION. There is nothing to
    /// deliver, and arming for it would mean a timer firing on an empty store
    /// forever — an obligation that outlives its subject is how a "self-healing"
    /// mechanism becomes a battery drain nobody can explain.
    ///
    /// - Parameters:
    /// - pending: records appended since the last settled drain, INCLUDING
    /// this one.
    /// - deadlineArmed: whether an obligation already exists.
    public func onEnqueued(pending: Int, deadlineArmed: Bool) -> Decision {
        if pending >= batchThreshold {
            // The threshold path may supersede a deadline that is already
            // armed; the deadline firing later on an empty store is harmless.
            return Decision(armDeadline: false, drainNow: true)
        }
        // A LATER RECORD MUST NEVER POSTPONE THE FIRST ONE'S DEADLINE. If the
        // window slid forward on every arrival, a steady trickle of records
        // below the threshold would hold delivery off indefinitely — the
        // stranding defect wearing a timer.
        if deadlineArmed { return .nothing }
        return Decision(armDeadline: true, drainNow: false)
    }

    /// A drain SETTLED.
    ///
    /// - Parameter storeRemaining: how many records are still durable, or `nil`
    /// when that could not be read.
    ///
    /// UNKNOWN IS NOT EMPTY. A store whose count could not be read is the one
    /// case where guessing costs data: treating it as empty clears the
    /// obligation and the records — if there are any — strand again, silently
    /// and only sometimes. It arms.
    public func onDrainSettled(storeRemaining: Int?) -> Decision {
        onDrainSettled(storeRemaining: storeRemaining, disposition: .deliverableRemaining)
    }

    /// WHY THE COUNT ALONE WAS THE WRONG SUBJECT.
    ///
    /// `remaining > 0` is the RIGHT question for the defect this function was
    /// written to fix (records stranded with no trigger) and the WRONG question
    /// after a refusal, where "does the queue still hold anything" is
    /// permanently yes. A permanently undeliverable head therefore re-armed a
    /// flat 2 s deadline forever, one real HTTP POST per cycle.
    ///
    /// The invariant: **remaining > 0 is NOT sufficient to arm the first-pending
    /// deadline.** That schedule exists for records that can plausibly become
    /// deliverable through ORDINARY RETRY — not for a terminal item awaiting
    /// local archival, and not for a slice whose retry is already owned by the
    /// worker's bounded backoff. Twin of the Kotlin enum.
    public enum DrainDisposition {
        case empty
        /// The ONLY disposition that arms the first-pending deadline.
        case deliverableRemaining
        /// Head retained by a TRANSIENT refusal; the bounded backoff owns it.
        case retryableRemaining
        /// Head durably terminal, local archival incomplete. The retry owed is
        /// a LOCAL move, never a network POST.
        case terminalMovePending
        /// Durably terminal and locally BLOCKED (preservation capacity
        /// exhausted). Arms no deadline, and is owed no automatic retry.
        case terminalMoveBlocked
        /// A terminal head was archived and more remains. Continue IMMEDIATELY:
        /// making a healthy later session wait 2 s behind a dead one it has
        /// nothing to do with is latency with no purpose.
        case terminalMovedAndMoreRemain
    }

    /// A drain SETTLED, with the reason it ended.
    ///
    /// UNKNOWN IS STILL NON-EMPTY AND DELIVERABLE. Re-arming costs one
    /// bounded, coalesced job; assuming empty would strand records we could not
    /// see. Unknown is never evidence of nothing — and never evidence of
    /// terminal either, so it takes the retrying branch.
    public func onDrainSettled(storeRemaining: Int?,
                               disposition: DrainDisposition) -> Decision {
        if let r = storeRemaining, r <= 0 { return .nothing }
        switch disposition {
        case .empty:
            return .nothing
        case .terminalMovedAndMoreRemain:
            return Decision(armDeadline: false, drainNow: true)
        case .retryableRemaining, .terminalMovePending, .terminalMoveBlocked:
            return .nothing
        case .deliverableRemaining:
            return Decision(armDeadline: true, drainNow: false)
        }
    }

    /// A drain SETTLED after its pass bound ABANDONED an outstanding request.
    ///
    /// an earlier change/an earlier change. The record lane stops at its pass bound and `abandon`
    /// CLEARS the coalesced `requested` bit — a request that arrived during the
    /// last pass (a batch-threshold enqueue, which arms no deadline of its own,
    /// or the `app_background` drain) was never honoured. If that pass read the
    /// store before the record landed, the pipeline says `.idle` ⇒ `.empty`
    /// and the two-argument rule arms nothing: a durable record with ZERO
    /// obligations until some unrelated trigger.
    ///
    /// The dropped request is honoured ONCE, as one first-pending deadline,
    /// whatever the disposition: the request would have produced exactly one
    /// more pass had the bound not cut it off. It cannot loop — the deadline's
    /// own drain makes one pass and reaches the bound again only if eight more
    /// external requests arrive. An empty store owes nothing (a request made
    /// after the abandon starts its own worker, and `storeRemaining` is read
    /// after the abandon).
    public func onDrainSettled(storeRemaining: Int?,
                               disposition: DrainDisposition,
                               boundAbandonedRequest: Bool) -> Decision {
        let base = onDrainSettled(storeRemaining: storeRemaining, disposition: disposition)
        guard boundAbandonedRequest else { return base }
        if let r = storeRemaining, r <= 0 { return base }
        if base.armDeadline || base.drainNow { return base }
        return Decision(armDeadline: true, drainNow: false)
    }
}

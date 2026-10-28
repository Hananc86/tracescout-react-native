package com.tracescout.rn

/**
 * WHEN THE DURABLE QUEUE IS GUARANTEED A FUTURE DRAIN — the whole decision, in
 * one pure place, so the enqueue path, the drain-completion path and the guard
 * cannot disagree about it.
 *
 * THE DEFECT THIS REPLACES, PRECISELY. `pendingSinceDrain` / `pendingBytes`
 * / `deadlineArmed` were reset ONLY by `requestDrain` — the HINT that asks for
 * a drain. A drain that actually RAN (through the armed deadline, or through
 * the worker) settled nothing, so after the first deadline fired:
 *
 * pendingSinceDrain stayed > 0 -> `n == 1L` could never hold again
 * deadlineArmed stayed true -> the flag could never be re-taken
 * => NO further deadline was ever armed for the life of the process
 *
 * Measured on a real active emulator session: a captured network request was
 * enqueued (`network_observed … emitted=true`), and then nothing — no
 * `rn_deadline_armed`, no `upload_raw_enter` — for **3 minutes 43 seconds**
 * with the app FOREGROUNDED and idle. It drained 3307 ms after an unrelated
 * lifecycle trigger. The counters were named "since the last drain REQUEST"
 * and were being used to answer "is the queue idle"; those are not the same
 * question, and the naming is what made the gap invisible.
 *
 * Not a network-specific bug — network capture is simply the only event class
 * that arrives while an app sits idle in the foreground. Navigation and
 * lifecycle events call `requestDrain` themselves, which is why every earlier
 * gate passed.
 *
 * THE INVARIANT:
 *
 * a successful durable enqueue
 * -> the record pipeline is guaranteed a future drain trigger
 *
 * and its four boundaries, each a test below:
 *
 * first pending record -> arms the deadline ONCE
 * more records arrive -> MUST NOT push that deadline forward
 * threshold reached -> the immediate drain may win
 * queue drains/empties -> the deadline obligation is cleared
 * failed enqueue -> NO drain obligation is created
 */
object FlushSchedule {

    /** 32 records pending triggers an immediate drain. */
    const val RECORD_THRESHOLD: Long = 32L

    /** 64 KiB of pending serialized payload triggers an immediate drain. */
    const val BYTE_THRESHOLD: Long = 64L * 1024L

    /**
 * The coalescing flush bound for the FIRST pending record after an idle
 * queue.
 *
 * MEASURED, NOT GUESSED. The old value was 10_000 ms, chosen when the
 * only consumer was "a session that never backgrounds must not grow without
 * limit". Live delivery has a different requirement — a captured request
 * must reach the Viewer inside the Live target — and the measured
 * product-owned remainder is `drain trigger -> chunk commit` ~3.7 s, so a
 * 10 s bound puts an isolated request at ~14 s end to end. Candidates were
 * benchmarked on device at 1000/2000/3000/4000 ms and this is the LARGEST
 * that held the real capture->Viewer path inside 8 s with margin — chosen
 * that way deliberately, because the shortest possible timer buys latency
 * with upload frequency nobody measured.
 *
 * See an internal record for the raw numbers.
 */
    const val FIRST_PENDING_DEADLINE_MS: Long = 2_000L

    /**
 * What must happen after a state change. Deliberately DATA, not an action:
 * the caller owns WorkManager, and a decision that scheduled its own job
 * could not be unit-tested without an Android runtime — which is exactly
 * how this decision escaped testing in the first place.
 */
    data class Decision(
        /** Arm the first-pending deadline. At most one may be armed at a time. */
        val armDeadline: Boolean = false,
        /** Ask for an immediate drain, with this reason. Null = do not. */
        val drainReason: String? = null,
        /** Zero the pending counters and release the armed flag. */
        val resetCounters: Boolean = false,
    ) {
        /** True when this decision guarantees the queue a FUTURE drain. */
        val guaranteesDrain: Boolean get() = armDeadline || drainReason != null
    }

    /**
 * A record was durably stored. `pendingAfter` and `bytesAfter` are the
 * post-increment counts; `deadlineArmed` is the flag's state BEFORE this
 * decision.
 *
 * The arm is conditioned on `pendingAfter == 1L` AND the flag being
 * free. Both matter: the count identifies the empty->non-empty transition,
 * and the flag is what stops a second record in the same window from
 * arming a second timer. Neither alone is sufficient, and a later record
 * can never move a deadline that is already ticking.
 */
    fun onEnqueued(pendingAfter: Long, bytesAfter: Long, deadlineArmed: Boolean): Decision {
        // Threshold wins when both apply: an immediate drain makes the pending
        // deadline moot, and the caller's requestDrain resets the flag anyway.
        val reason = when {
            pendingAfter >= RECORD_THRESHOLD -> "record_threshold"
            bytesAfter >= BYTE_THRESHOLD -> "byte_threshold"
            else -> null
        }
        val arm = pendingAfter == 1L && !deadlineArmed
        return Decision(armDeadline = arm, drainReason = reason)
    }

    /**
 * The enqueue FAILED — nothing durable was written.
 *
 * A refusal must not manufacture a drain obligation: scheduling an upload
 * for a record that does not exist is a job that finds an empty queue and
 * a trace line that says work was pending when none was.
 */
    fun onEnqueueFailed(): Decision = Decision()

    /**
 * A drain COMPLETED. `remainingRecords` is what the store still holds —
 * `-1` when the store could not be read.
 *
 * Re-establishing the obligation from what REMAINS, rather than zeroing
 * blindly, is what closes the race: a record enqueued WHILE the drain was
 * in flight is not in the in-memory counter this resets, but it IS in the
 * store, so the re-arm covers it. Zeroing alone would recreate the same
 * defect in a narrower window.
 *
 * This is NOT the deadline sliding. The previous deadline was CONSUMED
 * by the drain that just ran; at most one is armed at a time, and a record
 * arriving mid-window still cannot postpone it (that path is `onEnqueued`,
 * whose `!deadlineArmed` refuses).
 *
 * UNREADABLE IS TREATED AS NON-EMPTY. Re-arming costs one bounded,
 * coalesced job; assuming empty would strand records we could not see.
 * Unknown is never evidence of nothing.
 */
    fun onDrainSettled(remainingRecords: Int): Decision =
        onDrainSettled(remainingRecords, DrainDisposition.DELIVERABLE_REMAINING)

    /**
 * WHY THE COUNT ALONE WAS THE WRONG SUBJECT.
 *
 * `remainingRecords != 0` is the RIGHT question for the defect this
 * function was written to fix (records stranded with no trigger) and the
 * WRONG question after a refusal, where "does the queue still hold
 * anything" is permanently yes. A permanently undeliverable head therefore
 * re-armed a flat 2 s deadline forever, one real HTTP POST per cycle — and
 * it defeated the careful `Outcome.PERMANENT` / `Outcome.RETRY` mapping the
 * worker already makes, because the re-arm is a SEPARATE work request that
 * terminating or backing off the current one cannot cancel.
 *
 * So the decision is given its actual subject. The invariant:
 *
 * remainingRecords > 0 is NOT sufficient to arm firstPendingDeadline
 *
 * The 2 s first-pending schedule exists for records that can plausibly
 * become deliverable through ORDINARY RETRY — not for a terminal item
 * awaiting local archival, and not for a slice whose retry is already owned
 * by the worker's bounded exponential backoff.
 */
    enum class DrainDisposition {
        /** Nothing remains. */
        EMPTY,

        /**
 * Ordinary work remains and nothing is blocking it. This is the ONLY
 * disposition that arms the first-pending deadline.
 */
        DELIVERABLE_REMAINING,

        /**
 * The head is retained by a TRANSIENT refusal — auth, or a session
 * precondition an opener may still satisfy. The worker's own bounded,
 * jittered backoff owns this retry; a 2 s deadline beside it is a
 * second, un-backed-off scheduler for the same work.
 */
        RETRYABLE_REMAINING,

        /**
 * The head is durably terminal and its local archival has not
 * completed. The retry that is owed is a LOCAL park, never a network
 * POST, so this must not arm a network-shaped deadline.
 */
        TERMINAL_MOVE_PENDING,

        /**
 * The head is durably terminal and its local archival CANNOT succeed
 * without an external change (preservation capacity exhausted). Like
 * PENDING it never arms a network deadline — and unlike PENDING it is
 * owed no automatic retry at all, which the worker enforces.
 */
        TERMINAL_MOVE_BLOCKED,

        /**
 * A terminal head was archived and more records remain behind it.
 * Continue IMMEDIATELY — making a healthy later session wait 2 s
 * behind a dead one it has nothing to do with would be a latency cost
 * with no purpose.
 */
        TERMINAL_MOVED_AND_MORE_REMAIN,
    }

    /**
 * A drain COMPLETED. [remainingRecords] is what the store still holds
 * (`-1` when unreadable); [disposition] is WHY.
 *
 * UNREADABLE IS STILL TREATED AS NON-EMPTY AND DELIVERABLE. Re-arming
 * costs one bounded, coalesced job; assuming empty would strand records we
 * could not see. Unknown is never evidence of nothing — and it is never
 * evidence of terminal either, so it takes the retrying branch.
 */
    fun onDrainSettled(remainingRecords: Int, disposition: DrainDisposition): Decision = when {
        // THE STORE COUNT IS THE AUTHORITY FOR "SOMETHING REMAINS", NOT THE
        // PIPELINE'S DISPOSITION. `EMPTY` describes the
        // pipeline's OWN SNAPSHOT, which was read when the drain began; a
        // record enqueued WHILE that drain was in flight is not in it. This
        // branch used to read `remainingRecords == 0 || disposition == EMPTY`,
        // so the disposition overrode the measured count and a mid-drain record
        // got NO re-arm — measured on device: the app_background lifecycle
        // record, enqueued ~130 ms into a drain, sat in the queue 6.5 h until
        // the next enqueue. That is the exact race the KDoc above says the
        // re-arm closes. Only a store that READS empty clears the obligation.
        remainingRecords == 0 ->
            Decision(resetCounters = true)

        // Snapshot drained, store still holds records (or could not be read):
        // they arrived during the drain and are ordinary work. One bounded,
        // coalesced deadline — the same arm DELIVERABLE_REMAINING takes.
        disposition == DrainDisposition.EMPTY ->
            Decision(armDeadline = true, resetCounters = true)

        // The one immediate-continuation case: the blockage was removed, and
        // what is behind it is ordinary work.
        disposition == DrainDisposition.TERMINAL_MOVED_AND_MORE_REMAIN ->
            Decision(drainReason = "terminal_moved_continue", resetCounters = true)

        // Owned by another, correctly-bounded scheduler. Arming here would add
        // a flat 2 s loop beside an exponential backoff.
        disposition == DrainDisposition.RETRYABLE_REMAINING ||
            disposition == DrainDisposition.TERMINAL_MOVE_PENDING ||
            disposition == DrainDisposition.TERMINAL_MOVE_BLOCKED ->
            Decision(resetCounters = true)

        else -> Decision(armDeadline = true, resetCounters = true)
    }

    /**
 * How a drain REQUEST (a flush hint: app_background, a threshold, a
 * terminal-moved continuation) must be scheduled.
 *
 * `KEEP` DISCARDS A REQUEST ISSUED WHILE THE DRAIN IS RUNNING, and still
 * reports success (an earlier change, measured: `upload_raw_enter` at 38.539, the
 * app_background hint at 38.670 logged `scheduled=true`, and no job ran).
 * A running worker is uncompleted unique work, so `KEEP` keeps IT — and it
 * is already past the snapshot the new record would need to be in.
 *
 * So the request is CHAINED after the running drain (`APPEND_OR_REPLACE`,
 * zero delay) exactly when one is in flight, and coalesces with `KEEP`
 * otherwise — N hints on an idle queue are still ONE job. Chaining cannot
 * loop: it fires only on a request made during a drain, and a drain makes
 * no request of its own except the bounded terminal-moved continuation.
 */
    enum class RequestPolicy { KEEP, AFTER_CURRENT }

    /** No drain tail is known (no drain has reached its final read yet). */
    const val NO_DRAIN_TAIL: Long = -1L

    /**
 * THE DRAIN TAIL — the residual an earlier change named and left open.
 *
 * A drain's LAST look at the store is its settle re-read (or, for an empty
 * queue, the read that found nothing). A record enqueued AFTER that read is
 * not covered by the settle re-arm, and the unique work is still RUNNING
 * until WorkManager commits the worker's result — which happens only after
 * `doWork` returns, i.e. after `drainOnce`'s `finally` has cleared
 * `drainInFlight`. In that window `KEEP` silently discards BOTH the flush
 * hint and the enqueue path's first-pending deadline, and the deadline's
 * `deadlineArmed` flag is left `true`, so no later record re-arms it either.
 * Reproduced deterministically in `DrainTailRaceTest` (RED arm = an earlier change's
 * decisions verbatim): the record is stranded with zero jobs scheduled.
 *
 * Nothing in this process can observe WorkManager's commit, so the tail is
 * a BOUNDED GRACE measured from the moment the final read began: inside it,
 * both requests are chained (`APPEND_OR_REPLACE`) instead of `KEEP`. The
 * cost of being inside the grace when the worker has in fact already
 * finished is at most one extra, empty drain — never a loop: the grace
 * opens only when a drain reaches its final read, and a drain's own settle
 * arms at most one deadline.
 *
 * A residual remains and is stated rather than hidden: if WorkManager
 * took longer than this grace to commit a finished worker, a request after
 * the grace would still meet `KEEP` on RUNNING work. The commit is one
 * database transaction issued as the worker returns; the grace is three
 * orders of magnitude above that.
 */
    const val DRAIN_TAIL_GRACE_MS: Long = 2_000L

    /** True while a request may still meet the RUNNING worker of a drain past its final read. */
    fun inDrainTail(msSinceDrainTail: Long): Boolean =
        msSinceDrainTail >= 0L && msSinceDrainTail < DRAIN_TAIL_GRACE_MS

    fun onDrainRequested(drainInFlight: Boolean): RequestPolicy =
        onDrainRequested(drainInFlight, NO_DRAIN_TAIL)

    fun onDrainRequested(drainInFlight: Boolean, msSinceDrainTail: Long): RequestPolicy =
        if (drainInFlight || inDrainTail(msSinceDrainTail)) RequestPolicy.AFTER_CURRENT
        else RequestPolicy.KEEP

    /**
 * How the ENQUEUE path's first-pending deadline must be scheduled.
 *
 * Before a drain's final read, `KEEP` is still right: a record enqueued
 * mid-drain is in the store when settle re-reads it, and settle re-arms
 * with `APPEND`. Only in the tail can nothing else cover the record, so
 * only there is the deadline chained. Keeping mid-drain arms on `KEEP` is
 * deliberate: chaining them would add a deadline job beside the one settle
 * already arms.
 */
    fun onDeadlineArmed(msSinceDrainTail: Long): RequestPolicy =
        if (inDrainTail(msSinceDrainTail)) RequestPolicy.AFTER_CURRENT else RequestPolicy.KEEP
}

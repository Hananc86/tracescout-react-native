package com.tracescout.mobile.internal.loss

import com.tracescout.rn.LossFamilies

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. `public` only because Kotlin `internal` is
 * compilation-module scoped.
 */

/**
 * RECOVERY DECISIONS, AS DATA. Pure: given the authority and a view of the
 * active queue, say what must be written. The `:sdk` store performs the writes
 * and owns nothing of the reasoning.
 *
 * ## What F2 decides, and what it leaves to F4 (F2 map the specification)
 *
 * | situation | decision |
 * |-------------------------------------------------------------|--------------------------------------------|
 * | queue trustworthy · PREPARED · survivor present | settle ADMITTED |
 * | queue trustworthy · PREPARED · survivor absent | settle LOST(NOT_STORED_AT_RECOVERY) |
 * | ADMITTED · survivor absent · not delivered | PENDING(DISCLOSED_OWED) — F4's reconciler |
 * | queue read lossy (discarded or torn) | PENDING(MUTATION_FENCED) — attribute NOTHING|
 * | max surviving sequence ABOVE the durable high-water | ADVANCE the high-water to it AND DISABLE |
 *
 * THE LAST ROW DOES TWO THINGS ON PURPOSE. "Never adopt the queue's number"
 * forbids treating queue reconstruction as the COUNT; it does not
 * license handing out a number a surviving record already carries. The
 * high-water only ever moves UP, so advancing it to the queue's maximum is the
 * one adoption that cannot reuse anything — and the session is marked disabled
 * in the same pass, because an authority that was behind its queue cannot say
 * what happened in between.
 *
 * "Pending" is never silent: the store records it, discloses it and carries it
 * into the snapshot. Whether it additionally FENCES a destructive mutator is
 * `PendingReason.pendingClass`'s answer — P0-15's rule applies to the LOSSY
 * class only, and only to the session the fence belongs to.
 */
public object AuthorityRecovery {

    /** The active queue as the store read it. `trustworthy` = no discarded frame and no torn tail. */
    public data class QueueView(
        val survivorSequences: Set<Long>,
        val trustworthy: Boolean,
        /** null when the queue is empty. */
        val maxSurviving: Long?,
    )

    public sealed class Step {
        public data class SettleAdmitted(val sequence: Long, val intentId: String) : Step()
        public data class SettleLost(val sequence: Long, val intentId: String, val cause: LossCause) : Step()
        public data class Disable(val reason: DisableReason) : Step()
        public data class AdvanceHighWater(val to: Long) : Step()
        /** Adoption of a pre-F2 survivor: admit + ADMITTED in one bracket. */
        public data class Adopt(val sequence: Long, val intentId: String, val family: String) : Step()
        /**
 * Something is unresolved and F2 may not decide it.
 *
 * WHETHER IT STOPS A REMOVAL IS THE REASON'S CLASS, NOT THE FACT OF
 * BEING PENDING. `reason.pendingClass` is the one authority: only
 * [PendingClass.MUTATION_FENCED] fences, and it fences only THIS
 * session. The first F2 draft carried a free-text `why` and one
 * undifferentiated set, so a clean `ADMITTED_ABSENT_UNDELIVERED` on one
 * session refused removals for every session in the process.
 */
        public data class Pending(val sequences: List<Long>, val reason: PendingReason) : Step()
    }

    public fun reconcile(auth: SequenceAuthority, queue: QueueView): List<Step> {
        val out = ArrayList<Step>()
        val hw = auth.highWater()
        val maxQ = queue.maxSurviving
        if (maxQ != null && maxQ > hw) {
            out.add(Step.AdvanceHighWater(maxQ))
            out.add(Step.Disable(DisableReason.QUEUE_AHEAD_OF_AUTHORITY))
        }
        if (!queue.trustworthy) {
            val open = auth.ownedSequences().values
                .filter { it.phase == SequenceAuthority.Phase.PREPARED }.map { it.sequence }
            out.add(Step.Pending(open.sorted(), PendingReason.QUEUE_READ_UNTRUSTWORTHY))
            return out
        }
        val pendingAdmitted = ArrayList<Long>()
        for (e in auth.ownedSequences().values) {
            when (e.phase) {
                SequenceAuthority.Phase.PREPARED ->
                    if (queue.survivorSequences.contains(e.sequence)) out.add(Step.SettleAdmitted(e.sequence, e.intentId))
                    else out.add(Step.SettleLost(e.sequence, e.intentId, LossCause.NOT_STORED_AT_RECOVERY))
                SequenceAuthority.Phase.ADMITTED ->
                    if (!queue.survivorSequences.contains(e.sequence) && !auth.isDelivered(e.sequence)) pendingAdmitted.add(e.sequence)
                else -> Unit
            }
        }
        if (pendingAdmitted.isNotEmpty()) out.add(Step.Pending(pendingAdmitted.sorted(), PendingReason.ADMITTED_ABSENT_UNDELIVERED))
        return out
    }

    /** One surviving queue record: its sequence and the event type its own bytes declare (null = unreadable). */
    public data class Survivor(val sequence: Long, val eventType: String?)

    /**
 * ONE-TIME ADOPTION of a queue that predates any authority for its session
 * (a pre-F2 build upgraded in place). Deterministic and idempotent: the
 * same survivors always yield the same records, and a second run finds
 * every sequence already owned (`sequence_already_owned`).
 *
 * A LOSSY READ CANNOT BE ADOPTED. If the queue could not be read
 * trustworthily the session opens `disabled` — its history cannot be
 * accounted — and the high-water is still advanced past every survivor
 * that WAS read, so nothing is reused (the unreadable frames' sequences are
 * unknown and unknowable; that is exactly what `disabled` states).
 *
 * AN UNKNOWN EVENT TYPE FAILS CLOSED (V4): no fabricated family, the
 * number is reserved by advancing the high-water past it, and the sequence
 * is reported PENDING rather than owned under a guess.
 *
 * AND IT DISABLES THE SESSION DURABLY (guards review, 2026-09-25). The
 * first F2 draft reserved the number and disclosed the sequence, and then
 * let the session keep admitting above it. A queue that holds a record this
 * build cannot classify was written by a producer whose vocabulary the
 * authority does not know; what that producer allocated beyond the survivors
 * is not something the authority can bound, so continuing to number above
 * the adopted maximum is the sequence REUSE RULING A1 forbids, reached
 * through adoption. `Disable(ADOPTION_UNKNOWN_EVENT_TYPE)` is planned ONCE,
 * after every survivor that CAN be adopted has been (so nothing readable is
 * discarded), and the store writes it as a durable `CheckpointDisabled` —
 * monotonic, per session, refusing every later admission through the same
 * RULING A2 gate every other disabled reason uses.
 */
    public fun adoptionPlan(survivors: List<Survivor>, trustworthy: Boolean, intentIdFor: (Long) -> String): List<Step> {
        val out = ArrayList<Step>()
        val max = survivors.maxOfOrNull { it.sequence }
        if (!trustworthy) {
            if (max != null) out.add(Step.AdvanceHighWater(max))
            out.add(Step.Disable(DisableReason.ADOPTION_LOSSY_READ))
            return out
        }
        var unknownSeen = false
        for (s in survivors.sortedBy { it.sequence }) {
            val family = LossFamilies.lossFamilyForEventType(s.eventType)
            if (family == null) {
                unknownSeen = true
                out.add(Step.AdvanceHighWater(s.sequence))
                out.add(Step.Pending(listOf(s.sequence), PendingReason.ADOPTION_UNKNOWN_EVENT_TYPE))
            } else {
                out.add(Step.Adopt(s.sequence, intentIdFor(s.sequence), family))
            }
        }
        if (unknownSeen) out.add(Step.Disable(DisableReason.ADOPTION_UNKNOWN_EVENT_TYPE))
        return out
    }
}

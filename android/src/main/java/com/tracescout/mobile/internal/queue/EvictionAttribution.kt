package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.internal.loss.LossAuthority
import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File

/**
 * F4-P0 Slice C — the eviction bracket's narrow seam onto the attribution
 * authority. The bracket decides WHEN a record is a victim and WHEN the queue
 * mutation committed; the authority decides what each of those MEANS for the
 * sequence it owns. The bracket never constructs a loss record, never names a
 * family, never invents a sequence (C4: *"caller does not invent authority
 * facts"*) — it hands this seam a session id and a sequence it READ from the
 * record's own bytes, and the authority answers from its OWNED entry.
 *
 * ## The order of calls, and why
 *
 * 1. [resolve] — the EXISTING removal door (`LossAuthority.openForRemoval`),
 * once per session in the image: a pre-F2 session's trustworthy surviving
 * records are ADOPTED before any of them can be named a victim (store
 * review R3's rule applies to this removal exactly as to a dequeue). Only
 * `Ready` (or the visible `NotWired`) lets a session's records be
 * considered at all.
 * 2. [eligibility] — read-only, per record, from the handle the door opened:
 * owned, ADMITTED, not delivered, not already a pending victim, and the
 * authority neither fenced nor fencing that sequence.
 * 3. [headroomVictims] — how many victims this session's WAL can PREPARE and
 * SETTLE (two frames each) before refusing `at_capacity`.
 * 4. [prepare] — the durable `EvictionIntent`, through the ONE facade writer.
 * 5. [settleLost] / [settleDidNotHappen] — the finalization, through the one
 * facade writer for each outcome, idempotent by `(bracketId, sequence)`.
 *
 * ## Why this is its own interface and not more methods on `AttributionSink`
 *
 * `AttributionSink` is the PIPELINE's seam and is implemented by four test
 * doubles; widening it would make every removal test also answer for eviction.
 * The bracket's seam is separate so an unwired eviction seam REFUSES the
 * bracket (`NO ATTRIBUTION ⇒ NO DESTRUCTION`) without touching delivery, and
 * so the three calls into the facade's writer path have exactly ONE home —
 * the stage-leak scanner admits them here by structural identity.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface EvictionAttribution {

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Eligibility {
        object Eligible : Eligibility()
        data class Ineligible(val reason: String) : Eligibility()
    }

    /** The removal door, for ONE session, with the LIVE queue as the store read it. */
    fun resolve(sessionId: String, queue: List<ByteArray>, queueTrustworthy: Boolean): AttributionSink.Adoption

    /** Read-only: may this owned sequence be named a victim? Never opens, never writes. */
    fun eligibility(sessionId: String, sequence: Long): Eligibility

    /** How many victims this session's authority can PREPARE and SETTLE today, or null when it has no open handle. */
    fun headroomVictims(sessionId: String): Long?

    /** PREPARE: the durable eviction intent for one victim. Idempotent by (bracketId, sequence). */
    fun prepare(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome

    /** SETTLE after a PROVEN commit: the victim is LOST(BYTE_EVICTED). Idempotent. */
    fun settleLost(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome

    /** F4-P1 — SETTLE a prepared victim `LOST(QUARANTINED)` after a PROVEN commit of a quarantine bracket. */
    fun settleQuarantined(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome

    /** SETTLE after a PROVEN no-commit: the victim is back to ADMITTED, the intent resolved. Idempotent. */
    fun settleDidNotHappen(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome

    fun isWired(): Boolean

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /** The unwired seam. The bracket REFUSES on it — an unattributable victim set destroys nothing. */
        @JvmStatic
        fun none(): EvictionAttribution = object : EvictionAttribution {
            override fun resolve(sessionId: String, queue: List<ByteArray>, queueTrustworthy: Boolean): AttributionSink.Adoption = AttributionSink.Adoption.NotWired
            override fun eligibility(sessionId: String, sequence: Long): Eligibility = Eligibility.Ineligible("attribution_unwired")
            override fun headroomVictims(sessionId: String): Long? = null
            override fun prepare(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome = AttributionSink.Outcome.NotWired
            override fun settleLost(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome = AttributionSink.Outcome.NotWired
            override fun settleQuarantined(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome = AttributionSink.Outcome.NotWired
            override fun settleDidNotHappen(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome = AttributionSink.Outcome.NotWired
            override fun isWired(): Boolean = false
        }

        /**
 * The production seam over the process-wide facade. Every handle is
 * obtained through the removal door FIRST ([resolve]); the per-record
 * reads and writes then use `peek`, which never opens and never creates.
 */
        @JvmStatic
        @JvmOverloads
        fun forFilesDir(
            filesDir: File,
            removalOpener: (String, LossAuthority.QueueSnapshot) -> LossAuthority.Open = { sid, q -> LossAuthority.openForRemoval(filesDir, sid, q) },
        ): EvictionAttribution = object : EvictionAttribution {
            private fun handle(sid: String): LossAuthority.Handle? = LossAuthority.peek(filesDir, sid)

            private fun map(w: LossAuthority.Write): AttributionSink.Outcome = when (w) {
                is LossAuthority.Write.Written -> AttributionSink.Outcome.Recorded
                is LossAuthority.Write.Refused -> AttributionSink.Outcome.Refused(w.reason)
                is LossAuthority.Write.NotDurable -> AttributionSink.Outcome.NotDurable(w.reason)
                is LossAuthority.Write.PolicyDeclined -> AttributionSink.Outcome.Refused(w.reason)
            }

            override fun resolve(sessionId: String, queue: List<ByteArray>, queueTrustworthy: Boolean): AttributionSink.Adoption {
                val out: AttributionSink.Adoption = when (val o = removalOpener(sessionId, LossAuthority.QueueSnapshot(queue, queueTrustworthy))) {
                    is LossAuthority.Open.Unavailable -> AttributionSink.Adoption.Refused(o.reason)
                    is LossAuthority.Open.Ok -> {
                        val h = o.handle
                        when {
                            h.isFenced() -> AttributionSink.Adoption.NotDurable("authority_fenced")
                            h.recoveryWritesRefused() > 0 -> AttributionSink.Adoption.NotDurable("recovery_writes_refused:${h.recoveryWritesRefused()}")
                            else -> AttributionSink.Adoption.Ready
                        }
                    }
                }
                RecoveryTrace.log("eviction_session_resolved", "outcome" to out.javaClass.simpleName,
                    "session" to sessionId.take(24), "count" to queue.size)
                return out
            }

            override fun eligibility(sessionId: String, sequence: Long): Eligibility {
                val h = handle(sessionId) ?: return Eligibility.Ineligible("no_authority_handle")
                if (h.isFenced()) return Eligibility.Ineligible("authority_fenced")
                if (sequence in h.mutationFenced()) return Eligibility.Ineligible("mutation_fenced")
                val why = h.victimIneligibility(sequence) ?: return Eligibility.Eligible
                return Eligibility.Ineligible(why)
            }

            override fun headroomVictims(sessionId: String): Long? = handle(sessionId)?.evictionHeadroomVictims()

            override fun prepare(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome {
                val h = handle(sessionId) ?: return AttributionSink.Outcome.Refused("no_authority_handle")
                val out = map(h.beginEviction(bracketId, sequence))
                RecoveryTrace.log("eviction_prepared", "outcome" to out.javaClass.simpleName,
                    "session" to sessionId.take(24), "bracket" to bracketId.take(12))
                return out
            }

            override fun settleLost(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome {
                val h = handle(sessionId) ?: return AttributionSink.Outcome.Refused("no_authority_handle")
                val out = map(h.evictionLost(bracketId, sequence))
                RecoveryTrace.log("eviction_settled_lost", "outcome" to out.javaClass.simpleName,
                    "session" to sessionId.take(24), "bracket" to bracketId.take(12))
                return out
            }

            override fun settleQuarantined(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome {
                val h = handle(sessionId) ?: return AttributionSink.Outcome.Refused("no_authority_handle")
                val out = map(h.quarantineLost(bracketId, sequence))
                RecoveryTrace.log("quarantine_settled_lost", "outcome" to out.javaClass.simpleName,
                    "session" to sessionId.take(24), "bracket" to bracketId.take(12))
                return out
            }

            override fun settleDidNotHappen(sessionId: String, bracketId: String, sequence: Long): AttributionSink.Outcome {
                val h = handle(sessionId) ?: return AttributionSink.Outcome.Refused("no_authority_handle")
                val out = map(h.evictionDidNotHappen(bracketId, sequence))
                RecoveryTrace.log("eviction_settled_not_happened", "outcome" to out.javaClass.simpleName,
                    "session" to sessionId.take(24), "bracket" to bracketId.take(12))
                return out
            }

            override fun isWired(): Boolean = true
        }
    }
}

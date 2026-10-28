package com.tracescout.mobile.queue

// THE FAMILY IS ASKED FOR, NEVER COMPUTED HERE (V5).
//
// The ONE canonical `eventType -> lossFamily` mapper lives in
// `mobile-loss-core` — the shared pure module BOTH mobile worlds consume — and
// is NOT reached through `rn-core`, which frozen pin B1 forbids `:sdk` naming
// at all. It is reached through `LossAuthority`, because `LossVocabularyTest`
// pins that file as the ONLY importer of the vocabulary in `:sdk`: "a second
// consumer is how a second opinion starts". A `when (eventType)` here would be
// a second opinion about what was lost, and the two would agree right up until
// a new event type was added.
import com.tracescout.mobile.internal.loss.LossAuthority

/**
 * Native-core the specification EventQueue — bounded buffering with DETERMINISTIC,
 * DISCLOSED eviction.
 *
 * The disclosure half matters as much as the bounding half: the backend's
 * registry-completeness check treats an unaccounted sequence gap as
 * `incomplete`, which BLOCKS secure sharing. So a silent drop does not merely
 * lose an event — it quietly makes the whole session unshareable, with no
 * signal saying why. Every eviction here increments a counter.
 */

data class QueuedEvent(
    val eventId: String,
    val eventType: String,
    val sequence: Long,
)

/**
 * Priority classes, highest retained last.
 *
 * Crash and session-lifecycle events sit above everything else because losing
 * them makes a session look like it simply stopped — the saturation that
 * caused the flood would erase the crash that explains it.
 */
object Priority {
    private val CLASSES = mapOf(
        "mobile_crash" to 3,
        "mobile_hang" to 3,
        "mobile_session_start" to 2,
        "mobile_session_end" to 2,
        "mobile_view_hierarchy" to 1,
        "mobile_screen_snapshot" to 1,
        "mobile_view_mutation" to 1,
        "mobile_visual_frame" to 1,
        "mobile_interaction" to 1,
        "mobile_navigation" to 1,
        "mobile_lifecycle" to 1,
        "mobile_performance" to 0,
    )

    /** An unknown type is lowest priority — a new event type must never be able
 * to push out a crash by being unrecognised. */
    fun of(eventType: String): Int = CLASSES[eventType] ?: 0
}

data class EvictionResult(
    val kept: List<String>,
    /**
 * RENAMED FROM `dropped`, AND THE NAME IS THE FIX.
 *
 * These keys are raw PROTOCOL EVENT TYPES (`mobile_interaction`,
 * `mobile_visual_frame`, …), not canonical loss families. Called `dropped`
 * it read like the envelope's `dropped` block, and the caller duly folded
 * it straight in — producing keys the wire contract cannot represent
 * (`dropped` is `additionalProperties: false` over exactly the 13
 * families). The name now states the keyspace, so the mapping a caller
 * still owes cannot be skipped by mistaking one map for the other.
 *
 * The only sanctioned `eventType -> family` mapping is
 * `lossFamilyForEventType`, and it returns null for an unknown type rather
 * than a fallback — so a caller MUST fail closed, never guess.
 */
    val droppedByEventType: Map<String, Int>,
    val reason: String,
    /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5) — the SAME losses, keyed by the
 * canonical loss family, through the ONE sanctioned mapper.
 *
 * This is the keyspace the envelope's `dropped` block admits (exactly
 * the 13 families, `additionalProperties: false`). [droppedByEventType] is
 * kept beside it unchanged, because the two answer different questions and
 * collapsing them is how the original defect happened: the raw map is what
 * the planner decided on, this map is what may be DISCLOSED.
 *
 * `logs` is RESERVED and stays at zero. Nothing is mapped to it as a
 * fallback — a counter with no producer is honest; a counter made non-empty
 * by pointing something else at it is invented evidence.
 *
 * Empty whenever [refused] is non-null: a refused eviction destroyed
 * nothing, so there is nothing to disclose.
 */
    val droppedByFamily: Map<String, Int> = emptyMap(),
    /**
 * Non-null when the eviction was REFUSED and the queue is returned
 * UNCHANGED — [kept] is the full input, both drop maps are empty, and not
 * one record was destroyed.
 *
 * FAIL CLOSED, NEVER GUESS. The only sanctioned `eventType -> family`
 * mapping returns null for a type it does not know, and
 * `lossFamilyForEventType`'s own contract spells out what a caller must
 * then do: *"for the pressure planner that means refusing the eviction,
 * not evicting and attributing the loss to a guess."* A kept record is
 * always still recoverable; a record destroyed under a guessed family is
 * not, and the guess would be a fabricated attribution ("1 interaction
 * dropped" when a frame was lost).
 */
    val refused: String? = null,
)

/**
 * Evict until the queue fits: lowest priority first, oldest first within a
 * class. Returns the kept ids AND the per-EVENT-TYPE drop counters.
 *
 * The counters are NOT canonical loss families and may not be written into
 * the envelope's `dropped` block as they are — see [EvictionResult.droppedByEventType].
 */
fun evictToFit(
    queue: List<QueuedEvent>,
    maxCount: Int,
    reason: String = "queue_full",
): EvictionResult {
    val kept = queue.toMutableList()
    val dropped = mutableMapOf<String, Int>()
    val byFamily = mutableMapOf<String, Int>()

    while (kept.size > maxCount) {
        var victim = 0
        for (i in 1 until kept.size) {
            val pv = Priority.of(kept[victim].eventType)
            val pi = Priority.of(kept[i].eventType)
            if (pi < pv || (pi == pv && kept[i].sequence < kept[victim].sequence)) {
                victim = i
            }
        }
        // PRE-F6 GAP REMEDIATION (C5 / G5 / E5): THE FAMILY IS RESOLVED
        // BEFORE ANYTHING IS DESTROYED, AND AN UNKNOWN TYPE REFUSES THE WHOLE
        // EVICTION. `removeAt` first and map afterwards would mean the record
        // is already gone by the time we discover we cannot name what was
        // lost — the exact ordering that makes a loss unattributable.
        val family = LossAuthority.canonicalFamilyOrNull(kept[victim].eventType)
            ?: return EvictionResult(
                kept = queue.map { it.eventId },
                droppedByEventType = emptyMap(),
                reason = reason,
                droppedByFamily = emptyMap(),
                refused = "unknown_event_type",
            )
        val gone = kept.removeAt(victim)
        dropped[gone.eventType] = (dropped[gone.eventType] ?: 0) + 1
        byFamily[family] = (byFamily[family] ?: 0) + 1
    }

    return EvictionResult(kept.map { it.eventId }, dropped, reason, byFamily)
}

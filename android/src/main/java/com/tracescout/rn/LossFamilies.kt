package com.tracescout.rn

/**
 * The canonical loss-family vocabulary, Kotlin side.
 *
 * THIS IS A TWIN, NOT A SECOND SOURCE OF TRUTH. The authority is
 * `mobile-protocol/src/loss-families.cjs`. This file exists only because Kotlin
 * cannot require a `.cjs`, and it is pinned to that authority by
 * `parity-vectors/loss-family-vectors.json`, which is GENERATED from the
 * canonical map. `LossFamilyParityTest` fails if the two ever disagree, so a
 * change made on one side alone is caught rather than shipped.
 *
 * AND THERE IS EXACTLY ONE `eventType -> lossFamily` MAPPING IN THE PRODUCER.
 * A second `when (eventType)` somewhere in the pressure planner would be a
 * second opinion about what was lost, and the two would agree right up until
 * the moment a new event type is added. Everything that needs the family calls
 * [lossFamilyForEventType].
 */
public object LossFamilies {

    /**
 * `logs` is RESERVED: no current event type maps to it, and nothing may
 * be mapped to it as a fallback. A counter with no producer is allowed to
 * stay zero — that is honest. Making it non-empty by pointing something
 * else at it would be inventing evidence.
 */
    @JvmField
    public val ALL: List<String> = listOf(
        "crashes",
        "hangs",
        "hierarchy",
        "interactions",
        "lifecycle",
        "logs",
        "mutations",
        "navigation",
        "network",
        "performance",
        "screenSnapshots",
        "sessionEvents",
        "visualFrames",
    )

    /** TOTAL over the protocol's event types. The parity test proves it. */
    @JvmField
    public val EVENT_TYPE_TO_FAMILY: Map<String, String> = mapOf(
        "mobile_session_start" to "sessionEvents",
        "mobile_screen_snapshot" to "screenSnapshots",
        "mobile_view_hierarchy" to "hierarchy",
        "mobile_view_mutation" to "mutations",
        "mobile_interaction" to "interactions",
        "mobile_navigation" to "navigation",
        "mobile_lifecycle" to "lifecycle",
        "mobile_visual_frame" to "visualFrames",
        "mobile_network_request" to "network",
        "mobile_performance" to "performance",
        "mobile_crash" to "crashes",
        "mobile_hang" to "hangs",
        "mobile_session_end" to "sessionEvents",
    )

    /**
 * RETURNS null FOR AN UNKNOWN TYPE, NEVER A FALLBACK FAMILY.
 *
 * A guessed family is a fabricated attribution: reporting "1 interaction
 * dropped" when a frame was lost is worse than reporting nothing at all.
 * Callers MUST fail closed — for the pressure planner that means refusing
 * the eviction, not evicting and attributing the loss to a guess.
 */
    @JvmStatic
    public fun lossFamilyForEventType(eventType: String?): String? =
        if (eventType == null) null else EVENT_TYPE_TO_FAMILY[eventType]

    /** All families at zero. The starting point of a cumulative session ledger. */
    @JvmStatic
    public fun emptyCounts(): Map<String, Long> = ALL.associateWith { 0L }
}

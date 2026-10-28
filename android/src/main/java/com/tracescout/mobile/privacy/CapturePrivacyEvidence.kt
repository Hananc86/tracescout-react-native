package com.tracescout.mobile.privacy

/**
 * The complete privacy evidence for ONE emitted event.
 *
 * WHY THIS TYPE EXISTS
 * --------------------
 * `Envelope.postureProjection` could always emit all eight wire fields, but its
 * only production caller was `postureJson(p: Posture)` — which never overrode
 * the `allowedViewCount = 0` / `screenNeverRecord = false` defaults. So every
 * chunk from a real device reported zero allowed views and never-record=false
 * regardless of what the app had actually done, and a screen the app marked
 * never-record went on the wire as recordable.
 *
 * The guarding test could not fail: it called `postureProjection` DIRECTLY with
 * `allowedViewCount = 3`, proving the projection can carry a value while the
 * production path never supplied one. A documented mechanism that is
 * structurally unreachable, guarded by a test that invokes it directly.
 *
 * The fix is not "pass the arguments" — it is to make it IMPOSSIBLE not to. The
 * envelope builder takes this object and nothing else, it has no default
 * constructor, and every factory below forces the caller to say which kind of
 * event it is building. There is no expression in production that yields the old
 * constants by omission.
 */
data class CapturePrivacyEvidence(
    val posture: Posture,
    /**
 * Distinct ALLOWED view identities carried by THIS event. Derived from the
 * nodes that will actually be serialised — never a hand-passed number, and
 * never a running total: the server owns cross-event and cross-chunk
 * de-duplication through its evidence index, and a producer that tried to
 * reproduce that would be a second, unverifiable source of the same truth.
 */
    val allowedViewCount: Int,
    val screenNeverRecord: Boolean,
) {
    init {
        // The wire schema bounds this; emitting a value the schema would reject
        // is a producer defect, and it is cheaper to catch here than to have the
        // server refuse the chunk permanently.
        require(allowedViewCount in 0..100_000) {
            "allowedViewCount out of schema range: $allowedViewCount"
        }
    }

    companion object {

        /**
 * Evidence for a HIERARCHY-bearing event, derived from the exact nodes
 * about to be serialised.
 *
 * Counts DISTINCT node ids whose treatment is `allowed`. Repetition
 * inside one event — the same node reached twice by a traversal quirk,
 * or restated across a snapshot and a mutation in the same event —
 * collapses, because a count that grows when a producer says the same
 * thing again counts nothing real. Masked and blocked nodes contribute
 * zero by construction: they are not allowed views.
 *
 * Order-independent: a Set of ids cannot depend on traversal order.
 */
        @JvmStatic
        fun forHierarchy(
            posture: Posture,
            canonical: CanonicalHierarchy,
            screenNeverRecord: Boolean,
        ): CapturePrivacyEvidence = CapturePrivacyEvidence(
            posture = posture,
            // FROM THE PRUNED TREE, never the raw flat list. Counting the flat
            // list credited allowed nodes sitting under a blocked ancestor —
            // nodes the serializer drops and the server never stores — so the
            // scalar exceeded the payload and the API refused the chunk
            // permanently (422 allowed_view_count_mismatch, accepted=0). Found
            // on a real device, because ancestry is invisible to a flat list
            // and therefore invisible to every flat-list test.
            allowedViewCount = canonical.allowedCount,
            screenNeverRecord = screenNeverRecord,
        )

        /**
 * Evidence for an event that carries NO hierarchy — session start,
 * lifecycle, incidents, visual-frame metadata.
 *
 * `allowedViewCount` is 0 because the event declares no views, not
 * because we do not know. That distinction is the whole reason this is a
 * named factory rather than a default argument: reading
 * `nonHierarchy(.)` at a call site says the zero was chosen.
 */
        @JvmStatic
        fun nonHierarchy(
            posture: Posture,
            screenNeverRecord: Boolean,
        ): CapturePrivacyEvidence = CapturePrivacyEvidence(
            posture = posture,
            allowedViewCount = 0,
            screenNeverRecord = screenNeverRecord,
        )
    }
}

/**
 * Which screens the application has declared unrecordable.
 *
 * A never-record screen is a PROHIBITION the app asserts, so it is held in the
 * SDK rather than inferred per capture: inference would make the answer depend
 * on when you asked, and a screen that is sometimes recordable is not a
 * never-record screen.
 *
 * Thread-safe because screen transitions and capture run on different threads —
 * a lifecycle callback marking a screen while a snapshot is mid-walk is the
 * normal case, not the edge case.
 */
object ScreenPolicy {

    private val neverRecord = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Declare [screenId] unrecordable. Idempotent. */
    @JvmStatic
    fun markNeverRecord(screenId: String) {
        if (screenId.isNotEmpty()) neverRecord.add(screenId)
    }

    /** Withdraw the declaration for [screenId]. Idempotent. */
    @JvmStatic
    fun clearNeverRecord(screenId: String) {
        neverRecord.remove(screenId)
    }

    @JvmStatic
    fun isNeverRecord(screenId: String?): Boolean =
        screenId != null && neverRecord.contains(screenId)

    /**
 * Test/lifecycle reset. Deliberately NOT called on screen exit — leaving a
 * screen clears only that screen, so a second never-record screen elsewhere
 * in the app keeps its policy.
 */
    @JvmStatic
    fun clearAll() = neverRecord.clear()

    @JvmStatic
    fun declaredCount(): Int = neverRecord.size
}

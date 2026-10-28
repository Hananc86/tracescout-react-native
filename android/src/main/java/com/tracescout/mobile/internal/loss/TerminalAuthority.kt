package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. It is `public` only because Kotlin `internal` is
 * compilation-module scoped and every consumer (`:sdk`) lives across the
 * module boundary. a conformance test pins that no type
 * here ever appears in a public API signature of `:sdk` / `:rn` / rn-core.
 */
/**
 * ALL TERMINAL LIFECYCLE AUTHORITY AS ONE COHERENT GENERATION.
 *
 * THREE INDEPENDENTLY PUBLISHED REFERENCES ARE NOT ONE STATE. The previous
 * shape published `deliveredBatches`, the sequence index and `evictedLost`
 * through three separate volatile assignments. Each was individually
 * none-or-all, and the whole was still able to contradict itself: a reader
 * could observe the new batch map beside the old index and be told, in one
 * breath, that batch M exists and that its sequences are undelivered. Which
 * permutation you saw depended on where the reader happened to interleave.
 *
 * So the aggregate is one immutable object behind one `@Volatile` reference.
 * A commit builds a COMPLETE new generation and installs it with a single
 * assignment; a reader takes one reference and answers every terminal question
 * from that one generation.
 *
 * AND THE INDEX IS DERIVED, NEVER SUPPLIED. `sequenceToMarker` is computed
 * from the batches inside this class, so "batch present, sequence missing" is
 * not a state a caller can construct wrongly — it is a state that does not
 * exist. Deriving beats agreeing: two maps handed in together can disagree,
 * and no amount of testing makes that impossible.
 *
 * DELIVERED AND EVICTED_LOST ARE MUTUALLY EXCLUSIVE, and that is enforced
 * HERE rather than only at the call sites that happen to check. A sequence that
 * was both successfully uploaded and destroyed for space is not a race we lost;
 * it is an accounting claim that cannot be true.
 *
 * Every collection this object holds is frozen at construction from a fresh
 * local builder that is then discarded — no caller and no later mutation can
 * reach the backing store through an alias.
 */
public class TerminalAuthority private constructor(
    /** markerId -> the delivered batch it identifies. */
    public val batches: Map<String, DeliveredBatchSnapshot>,
    /** logical sequence -> the markerId of the batch that delivered it. */
    public val sequenceToMarker: Map<Long, String>,
    /** identities destroyed to make room; disjoint from the delivered set. */
    public val evictedLost: Set<Long>,
) {
    public val deliveredSequences: Set<Long> get() = sequenceToMarker.keys

    public fun isDelivered(q: Long): Boolean = sequenceToMarker.containsKey(q)
    public fun markerFor(q: Long): String? = sequenceToMarker[q]
    public fun isEvictedLost(q: Long): Boolean = evictedLost.contains(q)
    public fun batch(markerId: String): DeliveredBatchSnapshot? = batches[markerId]

    /** A COMPLETE new generation carrying one more delivered batch. */
    public fun withBatch(snap: DeliveredBatchSnapshot): TerminalAuthority =
        of(LinkedHashMap(batches).apply { put(snap.markerId, snap) }, evictedLost)

    /** A COMPLETE new generation carrying one more destroyed identity. */
    public fun withEvictedLost(q: Long): TerminalAuthority =
        of(batches, LinkedHashSet(evictedLost).apply { add(q) })

    public companion object {
        private val EMPTY = TerminalAuthority(
            java.util.Collections.unmodifiableMap(LinkedHashMap()),
            java.util.Collections.unmodifiableMap(LinkedHashMap()),
            java.util.Collections.unmodifiableSet(LinkedHashSet()),
        )

        public fun empty(): TerminalAuthority = EMPTY

        /**
 * Build one coherent generation, or refuse.
 *
 * The builders below are FRESH LOCALS that nothing retains. Wrapping
 * a collection the caller still holds would leave the "unmodifiable"
 * view changing underneath every reader that already took it — an
 * immutability that is true only until someone writes through the
 * alias.
 */
        public fun of(
            batches: Map<String, DeliveredBatchSnapshot>,
            evictedLost: Set<Long>,
        ): TerminalAuthority {
            val frozenBatches = LinkedHashMap<String, DeliveredBatchSnapshot>()
            val index = LinkedHashMap<Long, String>()
            for ((marker, b) in batches) {
                if (marker != b.markerId)
                    throw LossFormatException("terminal_batch_key_mismatch:$marker")
                frozenBatches[marker] = b
                for (q in b.sequences()) {
                    val prior = index.put(q, marker)
                    if (prior != null && prior != marker)
                        throw LossFormatException("terminal_sequence_claimed_twice:$q")
                }
            }
            val frozenEvicted = LinkedHashSet<Long>()
            for (q in evictedLost) {
                if (index.containsKey(q))
                    throw LossFormatException("terminal_delivered_and_evicted:$q")
                frozenEvicted.add(q)
            }
            return TerminalAuthority(
                java.util.Collections.unmodifiableMap(frozenBatches),
                java.util.Collections.unmodifiableMap(index),
                java.util.Collections.unmodifiableSet(frozenEvicted),
            )
        }
    }
}

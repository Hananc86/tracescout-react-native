package com.tracescout.mobile.privacy

/**
 * The ONE ancestry-aware hierarchy transformation.
 *
 * WHY THIS TYPE EXISTS — A LIVE DEVICE DEFECT
 * -------------------------------------------
 * The wire serializer already pruned blocked subtrees at write time (a secure
 * node emits no children). `CapturePrivacyEvidence.forHierarchy` did not: it
 * walked the RAW FLAT list and counted every node whose own `privacy` said
 * `allowed` — including nodes sitting beneath a blocked ancestor, which the
 * serializer would then drop and the server would never store.
 *
 * So the producer's scalar exceeded what the payload could support, and the
 * API's mismatch guard refused those chunks **permanently**:
 *
 * Result(action=TERMINAL, status=422, code=allowed_view_count_mismatch, accepted=0)
 *
 * The fixture's `ViewsActivity` builds exactly that shape on purpose — an
 * allow-tagged parent holding a platform-secure child — so the route could
 * never upload. The guard was right; the producer was wrong.
 *
 * No unit test could have caught it, because every one of them passed a FLAT
 * node list. Ancestry is invisible to a flat list, so the bug was invisible to
 * the tests. It took a real view tree on a real device.
 *
 * THE RULE
 * --------
 * There is now one transformation, and both consumers read its output:
 *
 * raw native hierarchy
 * -> privacy classification (already done by MaskEngine)
 * -> blocked-subtree pruning (HERE)
 * -> canonical emitted nodes (HERE)
 * -> allowed identities FROM THOSE (HERE)
 * -> envelope
 *
 * The count is never computed from the original flat list again. An allowed
 * child beneath a blocked ancestor is **not an allowed view** — it is content
 * the customer's own privacy rule removed, and calling it "allowed" would
 * report an exposure that does not exist.
 *
 * Platform-secure (`blocked`) and application-declared (`never_record`)
 * ancestry use the SAME fail-closed rule. A single reason for a node to
 * disappear keeps the model explainable; two nearly-identical rules is how the
 * next inversion gets in.
 */
data class CanonicalHierarchy(
    /** Pruned, in the original pre-order. This is what gets serialised. */
    val nodes: List<EmittedNode>,
    /** Distinct node ids that SURVIVED pruning and are `allowed`. */
    val allowedIdentities: Set<Long>,
    val maskedCount: Int,
    val blockedCount: Int,
    /** Descendants removed because an ancestor was blocked. Reported, not hidden. */
    val prunedCount: Int,
) {
    val allowedCount: Int get() = allowedIdentities.size

    companion object {

        private fun secure(privacy: String) =
            privacy == Treatment.BLOCKED.wire || privacy == Treatment.NEVER_RECORD.wire

        /**
 * Prune every descendant of a blocked/never-record node, then derive
 * the statistics from what SURVIVED.
 *
 * Orphans — nodes whose parent is not in the list at all — are dropped
 * with their subtree. A node we cannot place cannot be shown to have a
 * safe ancestry, and the safe reading of "unknown ancestry" is to omit
 * it, exactly as `Envelope.roots` already does at serialisation.
 */
        @JvmStatic
        fun of(raw: List<EmittedNode>): CanonicalHierarchy {
            if (raw.isEmpty()) return CanonicalHierarchy(emptyList(), emptySet(), 0, 0, 0)

            val byId = HashMap<Long, EmittedNode>(raw.size)
            for (n in raw) byId.putIfAbsent(n.nodeId, n)

            // A node survives when it has an unbroken chain to a root in which
            // no ANCESTOR is secure. Memoised, so a deep tree stays linear.
            val survives = HashMap<Long, Boolean>(raw.size)

            fun keep(n: EmittedNode): Boolean {
                survives[n.nodeId]?.let { return it }
                val p = n.parentId
                val ok = when {
                    p == null -> true                      // a root is always placeable
                    p == n.nodeId -> false                 // self-parent: malformed, drop
                    else -> {
                        val parent = byId[p]
                        when {
                            parent == null -> false        // orphan: unknown ancestry
                            secure(parent.privacy) -> false // the rule this class exists for
                            else -> keep(parent)
                        }
                    }
                }
                survives[n.nodeId] = ok
                return ok
            }

            val kept = ArrayList<EmittedNode>(raw.size)
            val allowed = LinkedHashSet<Long>()
            var masked = 0
            var blocked = 0
            for (n in raw) {
                if (!keep(n)) continue
                kept.add(n)
                when (n.privacy) {
                    Treatment.ALLOWED.wire -> allowed.add(n.nodeId)
                    Treatment.MASKED.wire -> masked += 1
                    else -> if (secure(n.privacy)) blocked += 1
                }
            }

            return CanonicalHierarchy(
                nodes = kept,
                allowedIdentities = allowed,
                maskedCount = masked,
                blockedCount = blocked,
                prunedCount = raw.size - kept.size,
            )
        }
    }
}

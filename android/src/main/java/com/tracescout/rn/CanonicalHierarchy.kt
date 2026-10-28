package com.tracescout.rn

/**
 * A node as it reaches the wire: identity + treatment + geometry, never raw
 * content. NESTED — the server's sanitiser consumes a `children` tree and
 * prunes beneath blocked ancestors itself; we never give it the chance.
 *
 * The Swift twin is `ios/Sources/TraceScoutRN/CanonicalHierarchy.swift`; the
 * two implementations are pinned to each other by the shared parity vectors
 * (`mobile-react-native/parity-vectors/hierarchy-vectors.json`): the same
 * input tree must produce byte-identical identities, order, treatments and
 * counts on both platforms.
 */
data class CanonicalNode(
    val id: String,
    val role: String,
    val privacy: String,
    val textMasked: Boolean? = null,
    val textLength: Int? = null,
    val blockedReason: String? = null,
    val bounds: NodeBounds? = null,
    val children: List<CanonicalNode> = emptyList(),
    /**
 * MODEL B, AT NODE LEVEL — the browser-SDK segment whose recording fills
 * THIS rectangle. Declared by the frozen `node.schema.json`
 * (`string`, `maxLength: 64`, *"On a web_view node, links to the
 * browser-SDK segment so the two capture layers render as one screen"*)
 * and, until this change, emitted by no producer on any platform: an earlier change
 * taught the DEPLOYED server to keep the link and an earlier change/an earlier change taught the
 * Viewer to read it, while `CanonicalNode` had no such field, so the
 * capability had no producer and no value ever reached either end.
 *
 * IT IS DERIVED FROM THE ENVELOPE CARRIER, NEVER FROM A MINTED ID —
 * [CanonicalHierarchy.build] takes an [RnEnvelope.WebViewRef], whose
 * constructor is private and whose only factory consumes an
 * [RnWebViewSegment.Segment]: the type `decide` returns on an ADMITTED
 * decision. So a bare segment id CANNOT reach this field, which is exactly
 * the mistake the carrier's own header warns about ("a correlation on the
 * wire that no message ever established"). STATED PRECISELY: `Segment`
 * is a public data class, so an in-process caller could hand-build one —
 * the guarantee is that a STRING is not enough, not that the type is
 * unforgeable. "N + S, never S alone" therefore holds at node level for
 * the same reason, and to the same degree, as it holds at envelope level.
 * And the node id equals the envelope id by construction rather than by
 * coincidence — which is what
 * the deployed server requires (an earlier change: a node link is kept only when it
 * equals the same event's envelope `webViewSegmentId`, otherwise dropped
 * and counted, never refused).
 *
 * LAST AND DEFAULTED — see `RawNode.webContent`.
 */
    val webViewSegmentId: String? = null,
)

/**
 * The single transformation from a raw RN tree to the wire, and the ONLY place
 * `allowedViewCount` is derived.
 *
 * The payload and the scalar that describes it are produced by one call, so
 * they cannot disagree — the Android lesson: a count computed from a different
 * list than the one serialised is a count of nothing.
 */
data class HierarchyResult(
    /** ROOT nodes of the nested canonical tree. */
    val nodes: List<CanonicalNode>,
    val allowedIdentities: Set<String>,
    val prunedSubtrees: Int,
    val totalNodes: Int,
    val truncated: Boolean,
    /**
 * Per-class counts from the SAME walk that serialised the nodes — the
 * snapshot event's wireframeNodeCount/blockedNodeCount are derived here,
 * never recomputed from a different list.
 */
    val maskedCount: Int = 0,
    val blockedCount: Int = 0,
    /**
 * How many WEB CONTENT SURFACES this walk serialised.
 *
 * DISCLOSURE, NOT DECORATION. A session holds exactly ONE admitted
 * segment (`RnWebViewCorrelation.admitted`), and the correlation cannot say
 * WHICH view's page echoed the id — so with two web views on one screen,
 * attaching that one id to both would be a confident wrong answer on at
 * least one of them, and `RnWebViewAttach.findWebViews` returns a LIST, so
 * the case is structurally reachable rather than hypothetical. The link is
 * therefore attached only when this count is exactly 1, and this field is
 * what lets a caller or a test see the refusal instead of inferring it from
 * an absent field.
 */
    val webViewSurfaceCount: Int = 0,
) {
    /**
 * Nodes the renderer draws as wireframe boxes (masked + allowed) — the
 * server folds this into `mobile_screens.wireframe_node_count`.
 */
    val wireframeNodeCount: Int get() = totalNodes - blockedCount

    /**
 * Contract the specification: the number of canonical allowed nodes in THIS event, after
 * privacy resolution. Distinct identities — a node restated within one
 * event is the same view, not two.
 */
    val allowedViewCount: Int get() = allowedIdentities.size

    /** Pre-order flattening — for tests and counters, never for the wire. */
    fun flatten(): List<CanonicalNode> {
        val out = ArrayList<CanonicalNode>(totalNodes)
        fun go(n: CanonicalNode) { out += n; n.children.forEach(::go) }
        nodes.forEach(::go)
        return out
    }
}

object CanonicalHierarchy {

    /** Resource budgets — explicit, adjusted only on measured device evidence. */
    const val MAX_NODES = 400
    const val MAX_DEPTH = 24

    /**
 * Walks the resolved tree ONCE.
 *
 * At a BLOCKED node the walk emits the typed placeholder and **returns** —
 * descendants are not serialised and, more importantly, are never
 * INSPECTED. The prohibition is written against inspection because
 * descending past a blocked node is trivially expressible in a JS walk,
 * unlike the Android walk that returns first and therefore made the case
 * unreachable (and so untested for a long time).
 *
 * An allowed node beneath a blocked ancestor is NOT an allowed view: it is
 * content the customer's own rule removed, and counting it would report an
 * exposure that does not exist.
 */
    fun build(
        routeId: String,
        root: RawNode,
        screenNeverRecord: Boolean = false,
        /**
 * The envelope's OWN WebView carrier, or null. See
 * [CanonicalNode.webViewSegmentId]: passing the carrier rather than a
 * `String` is what makes the node link unforgeable and what makes it
 * equal to the envelope's by construction.
 *
 * LAST AND DEFAULTED — a caller that has not been taught about the
 * node link gets a tree with no link, byte-identical to before.
 */
        webView: RnEnvelope.WebViewRef? = null,
    ): HierarchyResult {
        // the specification: a never-record route is resolved BEFORE capture, so nothing is
        // traversed and no content enters process memory.
        //
        // AND NO SEGMENT LINK EITHER, whatever the carrier says: a screen
        // the app declared never-record emits no node to carry one.
        if (screenNeverRecord) return HierarchyResult(emptyList(), emptySet(), 0, 0, false, 0, 0, 0)

        val allowed = LinkedHashSet<String>()
        val webViewIds = LinkedHashSet<String>()
        var pruned = 0
        var count = 0
        var truncated = false
        var masked = 0
        var blocked = 0

        fun walk(node: RawNode, path: List<CanonicalIdentity.PathSegment>, depth: Int): CanonicalNode? {
            if (count >= MAX_NODES || depth > MAX_DEPTH) { truncated = true; return null }
            count++
            val here = path + CanonicalIdentity.PathSegment(node.componentType, node.siblingOrdinal)
            val id = CanonicalIdentity.identity(
                routeId, CanonicalIdentity.structuralPath(here), node.replayId,
            )
            // RECORDED ONCE, ABOVE THE `when`, AND DELIBERATELY SO: web
            // content reaches the wire BLOCKED on Android and can reach it
            // ALLOWED on iOS under the Lab visibility exemption, so a
            // per-arm marker would be three rules that must agree — and the
            // iOS arm is the one a blocked-reason marker silently misses.
            if (node.webContent) webViewIds += id
            return when (node.resolved) {
                Treatment.BLOCKED -> {
                    blocked++
                    if (node.children.isNotEmpty()) pruned++
                    CanonicalNode(
                        id = id, role = node.componentType, privacy = "blocked",
                        blockedReason = (node.nativeBlockedReason ?: BlockedReason.JS_DECLARED)
                            .name.lowercase(),
                        bounds = node.bounds,
                        // never descend, never inspect
                    )
                }
                Treatment.MASKED -> { masked++; CanonicalNode(
                    id = id, role = node.componentType, privacy = "masked",
                    textMasked = true, textLength = node.textLength,
                    bounds = node.bounds,
                    children = node.children.mapNotNull { c -> walk(c, here, depth + 1) },
                ) }
                Treatment.ALLOWED -> {
                    allowed += id
                    CanonicalNode(
                        id = id, role = node.componentType, privacy = "allowed",
                        bounds = node.bounds,
                        children = node.children.mapNotNull { c -> walk(c, here, depth + 1) },
                    )
                }
            }
        }

        val rootNode = walk(root, emptyList(), 0)
        val serialised = if (rootNode != null) listOf(rootNode) else emptyList()
        // A POST-PASS OVER THE CANONICAL TREE, NEVER A PRE-PASS OVER THE
        // RAW ONE. The decision needs the TOTAL number of web surfaces, which
        // a single pre-order walk does not know until it ends — and counting
        // it by pre-walking `root` would need its own copy of the pruning rule
        // and would therefore be free to DESCEND PAST A BLOCKED NODE, which
        // the walk's own contract forbids by inspection and not merely by
        // serialisation. The canonical tree contains only what was already
        // serialised, so reading it again inspects nothing new.
        val linked =
            if (webView != null && webViewIds.size == 1)
                attachSegment(serialised, webViewIds.first(), webView.segmentId)
            else serialised
        return HierarchyResult(
            nodes = linked,
            allowedIdentities = allowed,
            prunedSubtrees = pruned,
            totalNodes = count,
            truncated = truncated,
            maskedCount = masked,
            blockedCount = blocked,
            webViewSurfaceCount = webViewIds.size,
        )
    }

    /**
 * Rewrites exactly the node whose identity is [id] with [segmentId].
 *
 * Bounded by [MAX_NODES], so this is a copy of at most 400 nodes. It
 * matches on the canonical IDENTITY — the same string
 * `allowedIdentities` is keyed by — never on a role, a class name or a
 * position, so there is no second way to decide which node is the web
 * surface.
 */
    private fun attachSegment(
        nodes: List<CanonicalNode>,
        id: String,
        segmentId: String,
    ): List<CanonicalNode> = nodes.map { n ->
        if (n.id == id) n.copy(webViewSegmentId = segmentId)
        else if (n.children.isEmpty()) n
        else n.copy(children = attachSegment(n.children, id, segmentId))
    }
}

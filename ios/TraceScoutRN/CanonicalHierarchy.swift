import Foundation
import CryptoKit

/// The RN hierarchy privacy model — the Swift twin of
/// `android/rn-core/./{Privacy,CanonicalIdentity,CanonicalHierarchy}.kt`.
/// The two implementations are pinned to each other by the shared parity
/// vectors (`mobile-react-native/parity-vectors/hierarchy-vectors.json`): the
/// same input tree must produce byte-identical identities, order, treatments
/// and counts on both platforms.
///
/// Contract the specification: privacy is monotonic — the strongest restriction wins, and the
/// NATIVE surface always overrides the JS declaration. There is deliberately
/// no direction in which a JS declaration can loosen a native restriction.
public enum Treatment: Int, Comparable {
    case allowed = 0
    case masked = 1
    case blocked = 2

    public static func < (a: Treatment, b: Treatment) -> Bool { a.rawValue < b.rawValue }

    /// Monotonic join. Order is the documentation: ALLOWED < MASKED < BLOCKED.
    public static func strongest(_ a: Treatment, _ b: Treatment) -> Treatment { max(a, b) }

    /// A value that does not parse is BLOCKED, never ALLOWED. An unreadable
    /// declaration is not evidence of a permissive one.
    public static func parse(_ raw: String?) -> Treatment {
        switch raw?.trimmingCharacters(in: .whitespaces).lowercased() {
        case "allowed": return .allowed
        case "masked": return .masked
        case "blocked", "secure": return .blocked
        default: return .blocked
        }
    }

    var wire: String {
        switch self {
        case .allowed: return "allowed"
        case .masked: return "masked"
        case .blocked: return "blocked"
        }
    }
}

/// Why a subtree is blocked. Typed so the renderer draws a labelled
/// placeholder — never a blank, never a plausible-but-wrong surface.
public enum BlockedReason: String {
    case jsDeclared = "js_declared"
    case secureTextEntry = "secure_text_entry"
    case nativeSecureSurface = "native_secure_surface"
    case cameraPreview = "camera_preview"
    case thirdPartyWebView = "third_party_webview"
    case screenNeverRecord = "screen_never_record"
}

/// Node bounds in POINTS in the window's coordinate space. A rectangle carries
/// no content; the renderer needs it to draw a correctly-sized wireframe or a
/// labelled placeholder for a blocked node.
public struct NodeBounds {
    public let x: Int, y: Int, w: Int, h: Int
    public init(x: Int, y: Int, w: Int, h: Int) { self.x = x; self.y = y; self.w = w; self.h = h }
}

/// One node as the JS layer and the native host each describe it.
/// `nativeTreatment` is a PLATFORM fact (secureTextEntry, a camera preview, a
/// secure window), never a host-app hint.
public struct RawNode {
    public let componentType: String
    public let siblingOrdinal: Int
    public let jsTreatment: Treatment
    public let nativeTreatment: Treatment?
    public let nativeBlockedReason: BlockedReason?
    public let replayId: String?
    public let textLength: Int?
    public let bounds: NodeBounds?
    public let children: [RawNode]
    /// Is this node the WEB CONTENT SURFACE itself — a `WKWebView`?
    ///
    /// A SURFACE FACT, NOT A TREATMENT, and on THIS platform that is not a
    /// nicety. `HierarchySnapshotter.rawNode` returns an ALLOWED opaque leaf
    /// with `nativeBlockedReason = nil` under the Lab visibility exemption, so
    /// keying the node-level segment link on `.thirdPartyWebView` would have
    /// silently skipped the one configuration the Lab actually runs. The role
    /// is not an alternative either: `componentType` is a per-platform class
    /// name and a an earlier change negative control pins that exact mistake.
    ///
    /// LAST AND DEFAULTED — a caller that has not been taught about web
    /// content gets the pre-existing answer, never a silently widened one.
    public let webContent: Bool

    public init(componentType: String, siblingOrdinal: Int,
                jsTreatment: Treatment = .masked,
                nativeTreatment: Treatment? = nil,
                nativeBlockedReason: BlockedReason? = nil,
                replayId: String? = nil,
                textLength: Int? = nil,
                bounds: NodeBounds? = nil,
                children: [RawNode] = [],
                webContent: Bool = false) {
        self.componentType = componentType
        self.siblingOrdinal = siblingOrdinal
        self.jsTreatment = jsTreatment
        self.nativeTreatment = nativeTreatment
        self.nativeBlockedReason = nativeBlockedReason
        self.replayId = replayId
        self.textLength = textLength
        self.bounds = bounds
        self.children = children
        self.webContent = webContent
    }

    /// Contract the specification: the strongest of the two wins.
    public var resolved: Treatment {
        guard let n = nativeTreatment else { return jsTreatment }
        return Treatment.strongest(jsTreatment, n)
    }
}

/// A node as it reaches the wire: identity + treatment + geometry, never raw
/// content. Nested — the server's sanitiser consumes a `children` tree and
/// prunes beneath blocked ancestors itself; we never give it the chance.
public struct CanonicalNode {
    public let id: String
    public let role: String
    public let privacy: String
    public let textMasked: Bool?
    public let textLength: Int?
    public let blockedReason: String?
    public let bounds: NodeBounds?
    public let children: [CanonicalNode]
    /// MODEL B, AT NODE LEVEL — the browser-SDK segment whose recording fills
    /// THIS rectangle. Declared by the frozen `node.schema.json` (`string`,
    /// `maxLength: 64`) and, until this change, emitted by no producer on any
    /// platform: the deployed server keeps the link and the Viewer
    /// reads it, while `CanonicalNode` had no such field.
    ///
    /// DERIVED FROM THE ENVELOPE CARRIER, NEVER FROM A MINTED ID — see the
    /// Kotlin twin. The carrier's initializer is private and its only factory
    /// consumes a `RnWebViewSegment.Segment`, the type `decide` returns on an
    /// ADMITTED decision, so a bare segment id cannot reach this field.
    /// STATED PRECISELY: `Segment` is a public struct, so an in-process
    /// caller could hand-build one — the guarantee is that a STRING is not
    /// enough, not that the type is unforgeable. The node id equals the
    /// envelope id by construction rather than by coincidence.
    public let webViewSegmentId: String?

    /// EXPLICIT, SO THE NEW FIELD CAN BE DEFAULTED. Swift's synthesized
    /// memberwise initializer carries no default for a `let` without one, so
    /// adding `webViewSegmentId` to a struct that relied on it would have
    /// broken every existing construction site instead of leaving them
    /// byte-identical.
    public init(id: String, role: String, privacy: String,
                textMasked: Bool?, textLength: Int?,
                blockedReason: String?, bounds: NodeBounds?,
                children: [CanonicalNode],
                webViewSegmentId: String? = nil) {
        self.id = id
        self.role = role
        self.privacy = privacy
        self.textMasked = textMasked
        self.textLength = textLength
        self.blockedReason = blockedReason
        self.bounds = bounds
        self.children = children
        self.webViewSegmentId = webViewSegmentId
    }
}

/// Evidence identity for a node — contract the specification
///
/// identity:= hash( routeId ‖ canonicalStructuralPath [ ‖ replayId ] )
///
/// FORBIDDEN as inputs: RN view tags, Fiber IDs, native instance IDs, object
/// identity, pointers, ephemeral mount IDs, arbitrary React keys, and any
/// application content. A React `key` is excluded even though it looks stable:
/// it is routinely a database id — application data wearing a convenient shape.
///
/// The path is canonical for the SERIALISED EVENT, not for the lifetime of
/// a native view instance. Android's `identityHashCode` fallback minted fresh
/// identities every capture, the cross-event union outgrew the per-event max,
/// and the server refused honest chunks. RN re-renders far more often, so the
/// same mistake would fire harder here.
public enum CanonicalIdentity {
    /// U+001F unit separator — cannot occur in a component type name.
    static let sep = "\u{1F}"

    public struct PathSegment {
        public let componentType: String
        public let siblingOrdinal: Int
        public init(componentType: String, siblingOrdinal: Int) {
            self.componentType = componentType
            self.siblingOrdinal = siblingOrdinal
        }
    }

    public static func structuralPath(_ segments: [PathSegment]) -> String {
        segments.map { "\($0.componentType)[\($0.siblingOrdinal)]" }.joined(separator: "/")
    }

    /// `replayId`, when the developer supplied one, REPLACES the structural
    /// path so a deliberate identity survives refactoring. It must be static.
    public static func identity(routeId: String, path: String, replayId: String? = nil) -> String {
        let preImage = replayId.map { routeId + sep + "replay:" + $0 } ?? (routeId + sep + path)
        let digest = SHA256.hash(data: Data(preImage.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

public struct HierarchyResult {
    /// ROOT nodes of the nested canonical tree.
    public let nodes: [CanonicalNode]
    public let allowedIdentities: Set<String>
    public let prunedSubtrees: Int
    public let totalNodes: Int
    public let truncated: Bool
    /// Per-class counts from the SAME walk that serialised the nodes — the
    /// snapshot event's wireframeNodeCount/blockedNodeCount are derived here,
    /// never recomputed from a different list.
    public let maskedCount: Int
    public let blockedCount: Int
    /// How many WEB CONTENT SURFACES this walk serialised.
    ///
    /// DISCLOSURE, NOT DECORATION. A session holds exactly ONE admitted
    /// segment and the correlation cannot say WHICH view's page echoed the id,
    /// so with two web views on one screen attaching that one id to both would
    /// be a confident wrong answer on at least one of them. The link is
    /// attached only when this count is exactly 1, and this field is what lets
    /// a caller or a test SEE the refusal rather than infer it from an absent
    /// field.
    public let webViewSurfaceCount: Int

    /// EXPLICIT, SO THE NEW FIELD CAN BE DEFAULTED — see `CanonicalNode`.
    public init(nodes: [CanonicalNode], allowedIdentities: Set<String>,
                prunedSubtrees: Int, totalNodes: Int, truncated: Bool,
                maskedCount: Int, blockedCount: Int,
                webViewSurfaceCount: Int = 0) {
        self.nodes = nodes
        self.allowedIdentities = allowedIdentities
        self.prunedSubtrees = prunedSubtrees
        self.totalNodes = totalNodes
        self.truncated = truncated
        self.maskedCount = maskedCount
        self.blockedCount = blockedCount
        self.webViewSurfaceCount = webViewSurfaceCount
    }

    /// Nodes the renderer draws as wireframe boxes (masked + allowed) — the
    /// server folds this into `mobile_screens.wireframe_node_count`.
    public var wireframeNodeCount: Int { totalNodes - blockedCount }

    /// Contract the specification: distinct allowed identities in THIS event, after privacy
    /// resolution. Derived from the exact nodes serialised, in the same walk —
    /// the Android lesson: a count computed from a different list than the one
    /// serialised is a count of nothing.
    public var allowedViewCount: Int { allowedIdentities.count }
}

/// The single transformation from a raw RN tree to the wire, and the ONLY
/// place `allowedViewCount` is derived.
public enum CanonicalHierarchy {

    /// Resource budgets — explicit, adjusted only on measured device evidence.
    public static let maxNodes = 400
    public static let maxDepth = 24

    /// Walks the resolved tree ONCE.
    ///
    /// At a BLOCKED node the walk emits the typed placeholder and RETURNS —
    /// descendants are not serialised and, more importantly, are never
    /// INSPECTED. An allowed node beneath a blocked ancestor is NOT an allowed
    /// view: it is content the customer's own rule removed, and counting it
    /// would report an exposure that does not exist.
    ///
    /// the specification: a never-record route is resolved BEFORE capture — the caller must
    /// not even construct the RawNode tree for one; this early return is the
    /// second lock on the same door.
    /// - Parameter webView: the envelope's OWN WebView carrier, or nil —
    /// threaded straight through so the node-level segment link and the
    /// envelope-level one come from ONE read of ONE admitted segment.
    ///
    /// LAST AND DEFAULTED, and there is no production caller yet: the
    /// correlation bind still has none on either platform, so every shipping
    /// path passes nil and emits a tree with no node link — byte-identical
    /// to before.
    public static func build(routeId: String, root: RawNode,
                             screenNeverRecord: Bool = false,
                             webView: RnEnvelope.WebViewRef? = nil) -> HierarchyResult {
        // AND NO SEGMENT LINK EITHER, whatever the carrier says: a screen
        // the app declared never-record emits no node to carry one.
        if screenNeverRecord {
            return HierarchyResult(nodes: [], allowedIdentities: [], prunedSubtrees: 0,
                                   totalNodes: 0, truncated: false,
                                   maskedCount: 0, blockedCount: 0,
                                   webViewSurfaceCount: 0)
        }

        var allowed = Set<String>()
        var webViewIds: [String] = []
        var pruned = 0
        var count = 0
        var truncated = false
        var masked = 0
        var blocked = 0

        func walk(_ node: RawNode, _ path: [CanonicalIdentity.PathSegment],
                  depth: Int) -> CanonicalNode? {
            if count >= maxNodes || depth > maxDepth { truncated = true; return nil }
            count += 1
            let here = path + [CanonicalIdentity.PathSegment(
                componentType: node.componentType, siblingOrdinal: node.siblingOrdinal)]
            let id = CanonicalIdentity.identity(
                routeId: routeId,
                path: CanonicalIdentity.structuralPath(here),
                replayId: node.replayId)

            // RECORDED ONCE, ABOVE THE `switch`, AND DELIBERATELY SO: web
            // content reaches the wire BLOCKED by default and ALLOWED under
            // the Lab visibility exemption, so a per-case marker would be
            // three rules that must agree — and the allowed case is the one a
            // blocked-reason marker silently misses.
            if node.webContent && !webViewIds.contains(id) { webViewIds.append(id) }

            switch node.resolved {
            case .blocked:
                blocked += 1
                if !node.children.isEmpty { pruned += 1 }
                return CanonicalNode(
                    id: id, role: node.componentType, privacy: Treatment.blocked.wire,
                    textMasked: nil, textLength: nil,
                    blockedReason: (node.nativeBlockedReason ?? .jsDeclared).rawValue,
                    bounds: node.bounds, children: [])   // never descend, never inspect
            case .masked:
                masked += 1
                var kids: [CanonicalNode] = []
                for c in node.children {
                    if let k = walk(c, here, depth: depth + 1) { kids.append(k) }
                }
                return CanonicalNode(
                    id: id, role: node.componentType, privacy: Treatment.masked.wire,
                    textMasked: true, textLength: node.textLength,
                    blockedReason: nil, bounds: node.bounds, children: kids)
            case .allowed:
                allowed.insert(id)
                var kids: [CanonicalNode] = []
                for c in node.children {
                    if let k = walk(c, here, depth: depth + 1) { kids.append(k) }
                }
                return CanonicalNode(
                    id: id, role: node.componentType, privacy: Treatment.allowed.wire,
                    textMasked: nil, textLength: nil,
                    blockedReason: nil, bounds: node.bounds, children: kids)
            }
        }

        let root2 = walk(root, [], depth: 0)
        let serialised = root2.map { [$0] } ?? []
        // A POST-PASS OVER THE CANONICAL TREE, NEVER A PRE-PASS OVER THE
        // RAW ONE. The decision needs the TOTAL number of web surfaces, which
        // a single pre-order walk does not know until it ends — and counting
        // it by pre-walking `root` would need its own copy of the pruning rule
        // and would therefore be free to DESCEND PAST A BLOCKED NODE, which
        // the walk's own contract forbids by INSPECTION and not merely by
        // serialisation. The canonical tree contains only what was already
        // serialised, so reading it again inspects nothing new.
        var linked = serialised
        if let wv = webView, webViewIds.count == 1 {
            linked = attachSegment(serialised, id: webViewIds[0], segmentId: wv.segmentId)
        }
        return HierarchyResult(nodes: linked,
                               allowedIdentities: allowed,
                               prunedSubtrees: pruned,
                               totalNodes: count,
                               truncated: truncated,
                               maskedCount: masked,
                               blockedCount: blocked,
                               webViewSurfaceCount: webViewIds.count)
    }

    /// Rewrites exactly the node whose identity is `id` with `segmentId`.
    ///
    /// Bounded by `maxNodes`, so this is a copy of at most 400 nodes. It
    /// matches on the canonical IDENTITY — the same string `allowedIdentities`
    /// is keyed by — never on a role, a class name or a position, so there is
    /// no second way to decide which node is the web surface.
    private static func attachSegment(_ nodes: [CanonicalNode],
                                      id: String,
                                      segmentId: String) -> [CanonicalNode] {
        nodes.map { n in
            if n.id == id {
                return CanonicalNode(
                    id: n.id, role: n.role, privacy: n.privacy,
                    textMasked: n.textMasked, textLength: n.textLength,
                    blockedReason: n.blockedReason, bounds: n.bounds,
                    children: n.children, webViewSegmentId: segmentId)
            }
            if n.children.isEmpty { return n }
            return CanonicalNode(
                id: n.id, role: n.role, privacy: n.privacy,
                textMasked: n.textMasked, textLength: n.textLength,
                blockedReason: n.blockedReason, bounds: n.bounds,
                children: attachSegment(n.children, id: id, segmentId: segmentId),
                webViewSegmentId: n.webViewSegmentId)
        }
    }
}

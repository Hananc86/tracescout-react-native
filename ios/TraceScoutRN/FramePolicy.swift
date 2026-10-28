import Foundation

/// Phase 2 — sparse, posture-gated VISUAL FRAMES. The decision and the
/// redaction geometry are PURE and shared (Kotlin twin: rn-core FramePolicy.kt),
/// pinned by `parity-vectors/frame-vectors.json`.
///
/// Non-negotiables encoded here, not in prose: frames are OPT-IN (a native
/// persisted config, default false); Never-Record short-circuits BEFORE
/// rasterization; foreground only; a secure window is excluded outright; a
/// frame exists only with a hierarchy result to redact FROM (the masked and
/// blocked rectangles come from the SAME nodes the wireframe serialised);
/// strict budgets. `privacyState` has exactly one producible value.
public enum FrameBudgets {
    public static let minIntervalMs: Int64 = 10_000
    public static let maxFramesPerSession = 40
    public static let maxFrameBytes = 512 * 1024
    public static let maxDim = 1080
    public static let storeCapBytes = 4 * 1024 * 1024
    public static let quality = 60
    public static let privacyState = "masked_before_capture"
    public static let captureReason = "explicit_allow"
    public static let scope = "screen"
    public static let captureModeWithFrames = "semantic_with_frames"
}

public struct FrameDecision: Equatable {
    public let capture: Bool
    public let refusal: String?
}

public enum FramePolicy {
    /// Ordered so the FIRST applicable refusal is reported — opt-in, then
    /// privacy, then availability, then budgets.
    public static func decide(enabled: Bool, neverRecord: Bool, foreground: Bool,
                              windowSecure: Bool, hierarchy: HierarchyResult?,
                              sinceLastFrameMs: Int64, framesThisSession: Int) -> FrameDecision {
        if !enabled { return FrameDecision(capture: false, refusal: "frames_not_enabled") }
        if neverRecord { return FrameDecision(capture: false, refusal: "screen_never_record") }
        if !foreground { return FrameDecision(capture: false, refusal: "not_foreground") }
        if windowSecure { return FrameDecision(capture: false, refusal: "platform_secure_surface") }
        guard let h = hierarchy, h.totalNodes > 0 else {
            return FrameDecision(capture: false, refusal: "hierarchy_unavailable")
        }
        if sinceLastFrameMs >= 0 && sinceLastFrameMs < FrameBudgets.minIntervalMs {
            return FrameDecision(capture: false, refusal: "cadence_budget")
        }
        if framesThisSession >= FrameBudgets.maxFramesPerSession {
            return FrameDecision(capture: false, refusal: "session_frame_budget")
        }
        return FrameDecision(capture: true, refusal: nil)
    }

    public struct Redaction {
        public let masked: [NodeBounds]
        public let blocked: [NodeBounds]
    }

    /// Image-class host components — their pixels ARE content (posture
    /// blockImages=true), so a masked image is painted even without a text
    /// length. Retained because it is a POSITIVE signal; it is no longer the
    /// thing that decides, only one of several ways to become mask-required.
    static let contentClassSubstrings = ["imageview", "imagecomponentview", "fastimage", "rnsvg",
                                         "svgview", "skia", "canvas", "chart", "lottie", "video", "map"]

    /// FAIL-CLOSED CLASSIFIER — the inversion of the defect that made iOS
    /// frames unredacted (build 10, 2026-08-27).
    ///
    /// The old rule PAINTED a masked node only when it was RECOGNISED as
    /// content: `textLength != nil` (set by the walker for UILabel /
    /// UITextField / UITextView only) or an image-class role. React Native
    /// renders text with `RCTParagraphComponentView` / `RCTParagraphTextView`,
    /// neither of which is a UILabel — so no RN text node was ever painted and
    /// the stored frame was a plain screenshot. Measured: 0.1% of the frame
    /// masked, 43 OCR words, and the ONLY painted rect was the fixture's own
    /// "UAT" UIButtonLabel.
    ///
    /// The rule is now inverted: **a node is mask-required UNLESS its class is
    /// on an explicit allowlist of known-inert structural containers.** An
    /// unknown view class — a future RN renderer, a third-party control, a
    /// UIKit internal nobody enumerated — is MASKED. Recognition can no longer
    /// be the thing standing between customer content and the encoder.
    ///
    /// Matching is on the EXACT lowercased class name, not a substring: a
    /// substring allowlist is open-ended negative matching wearing a list's
    /// clothes ("view" would admit `RCTParagraphComponentView`).
    ///
    /// The allowlist is derived from the REAL runtime hierarchy of the
    /// candidate (session ts_1787813945608_2cd250082e12, 196 nodes) plus the
    /// UIKit chrome around it — never guessed. Each entry is a container that
    /// draws no text and no imagery of its own; its content lives in child
    /// nodes, which are classified on their own terms.
    static let inertContainerClasses: Set<String> = [
        // Our own window + UIKit presentation chrome.
        "tracescoutfixturewindow", "uiwindow", "uitransitionview", "uidropshadowview",
        "uilayoutcontainerview", "uinavigationtransitionview", "uiviewcontrollerwrapperview",
        // React Native surface/root plumbing.
        "rctsurfacehostingproxyrootview", "rctsurfaceview", "rctrootcomponentview",
        "rctrootview", "rncsafeareaprovidercomponentview", "rncsafeareaview",
        // Plain containers and scroll hosts. RN's <View> draws no text; its
        // children carry the content and are classified individually.
        "uiview", "rctviewcomponentview", "rctview",
        "uiscrollview", "rctscrollviewcomponentview", "rctenhancedscrollview", "rctscrollview",
    ]

    /// Positive signals that force masking regardless of the allowlist. Typed
    /// facts from the walker come FIRST — a class name is the weakest evidence
    /// available and must never be able to override a native property.
    public static func isMaskRequired(_ n: CanonicalNode) -> Bool {
        if n.textLength != nil { return true }                 // typed: carries text
        let r = n.role.lowercased()
        if contentClassSubstrings.contains(where: { r.contains($0) }) { return true }
        return !inertContainerClasses.contains(r)               // UNKNOWN -> masked
    }

    /// Kept so the SHARED parity vectors still describe the Android contract
    /// unchanged. iOS no longer decides with it. Android's walker classifies RN
    /// text correctly (ReactTextView extends TextView), so it is not exposed to
    /// this defect and its certified output must not move in this tranche.
    public static func isContentBearing(_ n: CanonicalNode) -> Bool {
        if n.textLength != nil { return true }
        let r = n.role.lowercased()
        return contentClassSubstrings.contains { r.contains($0) }
    }

    /// Every BLOCKED node is painted, and every node that is not a known-inert
    /// container. A masked inert container is left unpainted so the frame stays
    /// non-vacuous — painting the root (the strict walker masks everything,
    /// root included) would turn the frame into a single fill: no privacy
    /// gained, all value lost.
    public static func redaction(_ result: HierarchyResult) -> Redaction {
        var masked: [NodeBounds] = []
        var blocked: [NodeBounds] = []
        func go(_ n: CanonicalNode) {
            if let b = n.bounds, b.w > 0, b.h > 0 {
                if n.privacy == "masked" { if isMaskRequired(n) { masked.append(b) } }
                else if n.privacy == "blocked" { blocked.append(b) }
            }
            n.children.forEach(go)
        }
        result.nodes.forEach(go)
        return Redaction(masked: masked, blocked: blocked)
    }

    /// Encoded-frame validation, the same rules the server's promotion gate applies.
    public static func validateEncoded(width: Int, height: Int, byteSize: Int) -> String? {
        if width < 1 || height < 1 || width > 8192 || height > 8192 { return "frame_dimensions_invalid" }
        if byteSize < 1 { return "frame_byte_size_invalid" }
        if byteSize > FrameBudgets.maxFrameBytes { return "frame_byte_budget" }
        return nil
    }
}

extension NodeBounds: Equatable {
    public static func == (a: NodeBounds, b: NodeBounds) -> Bool {
        a.x == b.x && a.y == b.y && a.w == b.w && a.h == b.h
    }
}

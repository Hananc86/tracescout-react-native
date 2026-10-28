package com.tracescout.rn

/**
 * Phase 2 — sparse, posture-gated VISUAL FRAMES. The decision and the
 * redaction geometry are PURE and shared (Swift twin: FramePolicy.swift),
 * pinned by `parity-vectors/frame-vectors.json`.
 *
 * Non-negotiables encoded here, not in prose:
 * - frames are OPT-IN (`enabled` is a native persisted config, default false);
 * - Never-Record short-circuits BEFORE rasterization;
 * - foreground only; a secure window is excluded outright (not blurred);
 * - a frame exists only with a hierarchy result to redact FROM — the masked
 * and blocked rectangles are derived from the SAME nodes the wireframe
 * serialised, so the redaction cannot describe a different screen than the
 * hierarchy does;
 * - strict budgets: cadence, per-session count, bytes, dimensions.
 * There is no code path that yields an unmasked frame; `privacyState` has one
 * producible value.
 */
object FrameBudgets {
    const val MIN_INTERVAL_MS = 10_000L
    const val MAX_FRAMES_PER_SESSION = 40
    const val MAX_FRAME_BYTES = 512 * 1024
    const val MAX_DIM = 1080
    const val STORE_CAP_BYTES = 4 * 1024 * 1024
    const val QUALITY = 60
    const val PRIVACY_STATE = "masked_before_capture"
    const val CAPTURE_REASON = "explicit_allow"
    const val SCOPE = "screen"
    const val CAPTURE_MODE_WITH_FRAMES = "semantic_with_frames"
}

data class FrameDecision(val capture: Boolean, val refusal: String?)

object FramePolicy {
    /**
 * Ordered so the FIRST applicable refusal is the one reported — the order is
 * the documentation: opt-in, then privacy, then availability, then budgets.
 */
    fun decide(
        enabled: Boolean,
        neverRecord: Boolean,
        foreground: Boolean,
        windowSecure: Boolean,
        hierarchy: HierarchyResult?,
        sinceLastFrameMs: Long,
        framesThisSession: Int,
    ): FrameDecision = when {
        !enabled -> FrameDecision(false, "frames_not_enabled")
        neverRecord -> FrameDecision(false, "screen_never_record")
        !foreground -> FrameDecision(false, "not_foreground")
        windowSecure -> FrameDecision(false, "platform_secure_surface")
        hierarchy == null || hierarchy.totalNodes == 0 -> FrameDecision(false, "hierarchy_unavailable")
        sinceLastFrameMs in 0 until FrameBudgets.MIN_INTERVAL_MS -> FrameDecision(false, "cadence_budget")
        framesThisSession >= FrameBudgets.MAX_FRAMES_PER_SESSION -> FrameDecision(false, "session_frame_budget")
        else -> FrameDecision(true, null)
    }

    /** Masked and blocked rectangles, pre-order, from the serialised tree. */
    data class Redaction(val masked: List<NodeBounds>, val blocked: List<NodeBounds>)

    /**
 * Image-class host components. Their pixels ARE content (posture
 * `blockImages=true`), so a masked image is painted even though it carries
 * no text length. Matched by component class name — a code identifier,
 * never user data.
 */
    /**
 * Rendered-content host components: image views, SVG/Skia/canvas/chart/
 * Lottie surfaces (which can rasterize TEXT as paths — a leak a text-length
 * rule cannot see). Any of these, when masked, is painted.
 */
    val IMAGE_CLASSES = Regex("ImageView|ImageComponentView|FastImage|RNSVG|SvgView|Skia|Canvas|Chart|Lottie|Video|Map", RegexOption.IGNORE_CASE)

    fun isContentBearing(n: CanonicalNode): Boolean =
        n.textLength != null || IMAGE_CLASSES.containsMatchIn(n.role)

    /**
 * ONLY CONTENT-BEARING masked nodes are painted: text-bearing nodes (a
 * measured textLength) and image-class nodes. A masked CONTAINER carries no
 * text and no pixels of its own beyond background/border — painting it
 * would cover the whole screen (the strict walker masks every node, root
 * included) and turn a frame into a single fill: no privacy gained, all
 * visual value lost. Blocked nodes are ALWAYS painted, whatever they are.
 */
    fun redaction(result: HierarchyResult): Redaction {
        val masked = ArrayList<NodeBounds>()
        val blocked = ArrayList<NodeBounds>()
        fun go(n: CanonicalNode) {
            val b = n.bounds
            if (b != null && b.w > 0 && b.h > 0) {
                when (n.privacy) {
                    "masked" -> if (isContentBearing(n)) masked += b
                    "blocked" -> blocked += b
                }
            }
            n.children.forEach(::go)
        }
        result.nodes.forEach(::go)
        return Redaction(masked, blocked)
    }

    /** Encoded-frame validation, the same rules the server's promotion gate applies. */
    fun validateEncoded(width: Int, height: Int, byteSize: Int): String? = when {
        width < 1 || height < 1 || width > 8192 || height > 8192 -> "frame_dimensions_invalid"
        byteSize < 1 -> "frame_byte_size_invalid"
        byteSize > FrameBudgets.MAX_FRAME_BYTES -> "frame_byte_budget"
        else -> null
    }
}

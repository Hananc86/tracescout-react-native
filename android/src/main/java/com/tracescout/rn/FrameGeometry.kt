package com.tracescout.rn

/**
 * Places the OWNED capture root inside a WINDOW-SIZED frame canvas, and says
 * which part of that canvas the owner does not own.
 *
 * THE KOTLIN TWIN OF `FrameGeometry.swift`, AND DELIBERATELY NOT A NEW
 * DESIGN. iOS reached a view-scoped rasterizer first and its review corrected
 * two things a fresh Android design would have got wrong — the canvas size and
 * the basis check (both recorded below in their own words). Writing a second,
 * independently-reasoned geometry would put two answers to "where do the owned
 * pixels go" in one product, and the parity vectors could then only pin one of
 * them. One fact, one home, two languages.
 *
 * THE CANVAS IS THE WINDOW, AND THAT IS A PROTOCOL FACT, NOT A PREFERENCE.
 * `RnHierarchySnapshotter.rawNode` records every node's bounds in WINDOW space
 * (`view.getLocationInWindow`) and that is what reaches the wire. The Viewer
 * then takes its surface size from an available frame and draws those bounds in
 * their RAW coordinates — there is no root-offset field anywhere in the
 * protocol for it to compensate with. So a root-SIZED image silently redefines
 * the origin of the coordinate system the wireframe is already expressed in:
 * every node would be drawn `root.origin` away from the pixels it describes, on
 * the one surface where a misplaced redaction is the most legible possible leak.
 *
 * The frame is therefore the window's size, and the OWNED ROOT'S PIXELS ARE
 * DRAWN AT THEIR WINDOW-SPACE POSITION. The rest of the canvas is filled with
 * an opaque neutral colour and never with app pixels: this build owns one
 * route host, and the chrome above or below it belongs to someone else.
 *
 * CONSEQUENTLY THE REDACTION IS NOT TRANSFORMED AT ALL. It is already in
 * window space, the canvas is window space, so it is painted as-is. The
 * transform that remains is the ROOT'S, applied once when its pixels are placed.
 *
 * PURE AND ANDROID-FREE ON PURPOSE. `:rn`'s unit tests are plain JVM tests with
 * no Robolectric, so anything that imports `android.view` can only ever be
 * source-scanned. The arithmetic that decides where the owned region lands —
 * and therefore which pixels a mask covers — must be EXECUTABLE in tests, so it
 * lives out here in the shared pure core beside `FramePolicy`.
 */
object FrameGeometry {

    /**
 * The opaque neutral fill for every part of the canvas the capture root
 * does not own.
 *
 * A SINGLE FLAT COLOUR, DELIBERATELY. The unowned region has to be
 * auditable — "contains no app pixels" is only checkable if there is
 * exactly one value it is allowed to hold. A gradient, a blur or a
 * system-background colour would each be a plausible-looking region whose
 * purity nothing could assert.
 */
    data class Fill(val r: Int, val g: Int, val b: Int)

    /** Byte-for-byte the iOS value, so a cross-platform pixel audit has one
 * constant to look for. */
    val unownedFill = Fill(0x21, 0x24, 0x2B)

    /**
 * The root→window mapping expressed as the images of the two unit basis
 * vectors. `(dxx, dxy)` is where `(1, 0)` goes; `(dyx, dyy)` is where
 * `(0, 1)` goes.
 *
 * THIS REPLACED A BOUNDING-BOX SIZE COMPARISON, WHICH WAS BLIND TO
 * THREE REAL TRANSFORMS. The previous check asked only whether the mapped
 * width and height still equalled the root's, and an axis-aligned bounding
 * box is invariant under all of:
 *
 * 180 degree rotation w,h unchanged -> was ACCEPTED, mispainted
 * mirroring (sx = -1) w,h unchanged -> was ACCEPTED, mispainted
 * 90 degrees on a SQUARE root -> was ACCEPTED, mispainted
 *
 * Each of those maps the redaction — which is NOT transformed, because it
 * is already in window space — onto pixels that are no longer the pixels
 * it describes, while the capture reports success. A basis is the smallest
 * thing that cannot be fooled by any of them.
 */
    data class Basis(val dxx: Double, val dxy: Double, val dyx: Double, val dyy: Double) {
        companion object { val IDENTITY = Basis(1.0, 0.0, 0.0, 1.0) }
    }

    /**
 * Whether the root→window mapping is a pure TRANSLATION — i.e. the basis
 * is the identity, so only the origin differs.
 *
 * FAIL CLOSED ON ANYTHING ELSE. Any rotation, mirroring, scale or shear
 * puts the root's pixels and the window-space redaction in different
 * places. The caller refuses `capture_root_transformed`. A transformed
 * route host is a rare thing to lose a frame over; a frame whose masks are
 * in the wrong place is not a thing to ship at all.
 *
 * The tolerance absorbs floating-point noise in the coordinate conversion
 * ONLY. It is deliberately far tighter than any real transform: the
 * smallest rotation this must reject moves a basis component by orders of
 * magnitude more than this.
 */
    fun isIdentityBasis(b: Basis, tolerance: Double = 0.001): Boolean =
        kotlin.math.abs(b.dxx - 1) <= tolerance && kotlin.math.abs(b.dxy) <= tolerance &&
            kotlin.math.abs(b.dyx) <= tolerance && kotlin.math.abs(b.dyy - 1) <= tolerance

    /**
 * The bounding-box size comparison, RETAINED AS AN ADDITIONAL SANITY
 * CHECK ONLY.
 *
 * THIS MUST NEVER AGAIN BE THE SAFETY DECISION. On its own it is blind
 * to 180 degree rotation, to mirroring, and to 90 degrees on a square
 * root, because an axis-aligned bounding box is invariant under all three
 * and it was the decision until the iOS review caught it. It is kept
 * because a disagreement between it and the basis would mean something is
 * true of the view tree that none of the reasoning here covers, which is
 * worth refusing on. `isIdentityBasis` decides; this only ever adds a
 * refusal.
 */
    fun sizeUnchanged(rootW: Int, rootH: Int, mappedW: Int, mappedH: Int, tolerance: Int = 1): Boolean =
        kotlin.math.abs(rootW - mappedW) <= tolerance && kotlin.math.abs(rootH - mappedH) <= tolerance

    /**
 * Where the owned root's pixels go on the canvas, in canvas (== window)
 * coordinates, clipped to the canvas.
 *
 * Returns `null` — and the caller refuses rather than guessing — when the
 * canvas or the root has no area, when the root→window mapping is not a
 * pure translation, or when the root lies entirely off the canvas.
 *
 * THE CLIP IS TO THE CANVAS, NEVER TO A SMALLER GUESS. It removes only
 * pixels that are not in the image at all. A root that straddles an edge
 * keeps its whole visible portion; shrinking further would leave owned
 * content undrawn while the redaction, which is computed from the same
 * window-space geometry, still expected it to be there.
 */
    fun destination(
        rootW: Int, rootH: Int,
        originX: Int, originY: Int,
        basis: Basis,
        canvasW: Int, canvasH: Int,
    ): NodeBounds? {
        if (canvasW <= 0 || canvasH <= 0 || rootW <= 0 || rootH <= 0) return null
        if (!isIdentityBasis(basis)) return null
        // The basis is the identity, so the root's own size IS its size on the
        // canvas — it is never re-derived from a mapped bounding box, which is
        // what could not tell a rotation from an identity.
        val x0 = maxOf(originX, 0); val y0 = maxOf(originY, 0)
        val x1 = minOf(originX + rootW, canvasW); val y1 = minOf(originY + rootH, canvasH)
        if (x1 <= x0 || y1 <= y0) return null
        return NodeBounds(x0, y0, x1 - x0, y1 - y0)
    }

    /**
 * The bands of the canvas the capture root does NOT own — the rectangles
 * that must hold nothing but [unownedFill].
 *
 * Returned as up-to-four non-overlapping bands (above, below, left, right)
 * so a pixel audit can walk them exhaustively instead of sampling.
 */
    fun unownedRegions(canvasW: Int, canvasH: Int, owned: NodeBounds): List<NodeBounds> {
        if (canvasW <= 0 || canvasH <= 0) return emptyList()
        val out = ArrayList<NodeBounds>(4)
        val top = maxOf(0, minOf(owned.y, canvasH))
        val bottom = maxOf(0, minOf(owned.y + owned.h, canvasH))
        if (top > 0) out += NodeBounds(0, 0, canvasW, top)
        if (bottom < canvasH) out += NodeBounds(0, bottom, canvasW, canvasH - bottom)
        val left = maxOf(0, minOf(owned.x, canvasW))
        val right = maxOf(0, minOf(owned.x + owned.w, canvasW))
        val bandH = bottom - top
        if (bandH > 0) {
            if (left > 0) out += NodeBounds(0, top, left, bandH)
            if (right < canvasW) out += NodeBounds(right, top, canvasW - right, bandH)
        }
        return out
    }
}

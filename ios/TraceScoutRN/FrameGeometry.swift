import Foundation

/// Whether the ACTIVE capture path can attempt a raster at all — asked BEFORE a
/// session declares what fidelity it will carry.
///
/// THIS IS A CAPABILITY, NOT A PREDICTION. `canAttempt` promises only that
/// the path will try; cadence, the session budget, Never-Record, a secure
/// surface, background, a detached root and coherence may each still refuse an
/// individual frame, and none of those make the declaration false. What it
/// rules out is the state build 31 shipped in: a path that refuses EVERY frame
/// categorically, while the opener promises frames anyway.
///
/// It lives here — beside the geometry, outside the UIKit island — so the
/// declaration contract is testable where `swift test` can actually run it.
public enum FrameCapability: Equatable {
    case canAttempt
    /// The reason, in the capture layer's own refusal vocabulary.
    case incapable(String)

    public var canAttempt: Bool { if case .canAttempt = self { return true }; return false }
    public var refusal: String? { if case .incapable(let r) = self { return r }; return nil }

    /// THE ONE RULE, and both consumers read it here.
    ///
    /// `integrated` — the app named route hosts, so every capture will run
    /// against a BOUND capture root rather than the legacy key window.
    /// `boundRootsRasterizable` — whether THIS BUILD's raster path can
    /// photograph such a root. It is a real parameter and not a constant
    /// precisely so the build-31 state stays EXPRESSIBLE: an incapable path
    /// that could not be written down could not be tested against either, and
    /// the control would be asserting over a case the type system had removed.
    ///
    /// THE ASYMMETRY IS DELIBERATE. An UNINTEGRATED app is always capable —
    /// there the window IS the declared root, which is the path that shipped
    /// frames in builds 28 and 29. Only the integrated path was ever at risk.
    public static func forCapturePath(integrated: Bool,
                                      boundRootsRasterizable: Bool) -> FrameCapability {
        if integrated && !boundRootsRasterizable {
            return .incapable("capture_root_not_rasterizable")
        }
        return .canAttempt
    }
}

/// Places the OWNED capture root inside a WINDOW-SIZED frame canvas, and says
/// which part of that canvas the owner does not own.
///
/// THE CANVAS IS THE WINDOW, AND THAT IS A PROTOCOL FACT, NOT A PREFERENCE.
/// `HierarchySnapshotter.rawNode` records every node's bounds in WINDOW space
/// (`view.convert(view.bounds, to: window)`) and that is what reaches the wire.
/// The Viewer then takes its surface size from an available frame and draws
/// those bounds in their RAW coordinates — there is no root-offset field
/// anywhere in the protocol for it to compensate with. So a root-SIZED image
/// silently redefines the origin of the coordinate system the wireframe is
/// already expressed in: every node would be drawn `root.origin` away from the
/// pixels it describes, on the one surface where a misplaced redaction is the
/// most legible possible leak.
///
/// The frame is therefore the window's size, and the OWNED ROOT'S PIXELS ARE
/// DRAWN AT THEIR WINDOW-SPACE POSITION. The rest of the canvas is filled with
/// an opaque neutral colour and never with app pixels: this build owns one
/// route host, and the chrome above or below it belongs to someone else.
///
/// CONSEQUENTLY THE REDACTION IS NOT TRANSFORMED AT ALL. It is already in
/// window space, the canvas is window space, so it is painted as-is. An earlier
/// iteration of this file translated it into root-local space; that was correct
/// for a root-sized image and is exactly wrong for this one. The transform that
/// remains is the ROOT'S, applied once when its pixels are placed.
///
/// PURE AND UIKit-FREE ON PURPOSE. `swift test` runs on macOS, where
/// `canImport(UIKit)` is false and `FrameRasterizer` / `HierarchySnapshotter`
/// are not compiled at all. The arithmetic that decides where the owned region
/// lands — and therefore which pixels a mask covers — must be executable in
/// tests, so it lives out here rather than inside the `#if canImport(UIKit)`
/// island where it could only ever be source-scanned.
// `NodeBounds: Equatable` — which the geometry suite compares whole rectangles
// with — already lives in `FramePolicy.swift`. Do not re-declare it here; a
// second conformance is a compile error, which is the good outcome, but the
// reason it does not belong here is that there is one fact and it has a home.

public enum FrameGeometry {

    /// The opaque neutral fill for every part of the canvas the capture root
    /// does not own.
    ///
    /// A SINGLE FLAT COLOUR, DELIBERATELY. The unowned region has to be
    /// auditable — "contains no app pixels" is only checkable if there is
    /// exactly one value it is allowed to hold. A gradient, a blur or a
    /// system-background colour would each be a plausible-looking region whose
    /// purity nothing could assert.
    public struct Fill: Equatable {
        public let r: Int, g: Int, b: Int
        public init(r: Int, g: Int, b: Int) { self.r = r; self.g = g; self.b = b }
    }
    public static let unownedFill = Fill(r: 0x21, g: 0x24, b: 0x2B)

    /// The root→window mapping expressed as the images of the two unit basis
    /// vectors. `(dxx, dxy)` is where `(1, 0)` goes; `(dyx, dyy)` is where
    /// `(0, 1)` goes.
    ///
    /// THIS REPLACED A BOUNDING-BOX SIZE COMPARISON, WHICH WAS BLIND TO
    /// THREE REAL TRANSFORMS. The previous check asked only whether the mapped
    /// width and height still equalled the root's, and an axis-aligned bounding
    /// box is invariant under all of:
    ///
    /// 180 degree rotation w,h unchanged -> was ACCEPTED, mispainted
    /// mirroring (sx = -1) w,h unchanged -> was ACCEPTED, mispainted
    /// 90 degrees on a SQUARE root -> was ACCEPTED, mispainted
    ///
    /// Each of those maps the redaction — which is NOT transformed, because it
    /// is already in window space — onto pixels that are no longer the pixels
    /// it describes, while the capture reports success. A basis is the smallest
    /// thing that cannot be fooled by any of them.
    public struct Basis: Equatable {
        public let dxx: Double, dxy: Double, dyx: Double, dyy: Double
        public init(dxx: Double, dxy: Double, dyx: Double, dyy: Double) {
            self.dxx = dxx; self.dxy = dxy; self.dyx = dyx; self.dyy = dyy
        }
        public static let identity = Basis(dxx: 1, dxy: 0, dyx: 0, dyy: 1)
    }

    /// Whether the root→window mapping is a pure TRANSLATION — i.e. the basis
    /// is the identity, so only the origin differs.
    ///
    /// FAIL CLOSED ON ANYTHING ELSE. Any rotation, mirroring, scale or shear
    /// puts the root's pixels and the window-space redaction in different
    /// places. The caller refuses `capture_root_transformed`. A transformed
    /// route host is a rare thing to lose a frame over; a frame whose masks are
    /// in the wrong place is not a thing to ship at all.
    ///
    /// The tolerance absorbs floating-point noise in the coordinate conversion
    /// ONLY. It is deliberately far tighter than any real transform: the
    /// smallest rotation this must reject moves a basis component by orders of
    /// magnitude more than this.
    public static func isIdentityBasis(_ b: Basis, tolerance: Double = 0.001) -> Bool {
        abs(b.dxx - 1) <= tolerance && abs(b.dxy) <= tolerance
            && abs(b.dyx) <= tolerance && abs(b.dyy - 1) <= tolerance
    }

    /// The bounding-box size comparison, RETAINED AS AN ADDITIONAL SANITY
    /// CHECK ONLY.
    ///
    /// THIS MUST NEVER AGAIN BE THE SAFETY DECISION. On its own it is blind
    /// to 180 degree rotation, to mirroring, and to 90 degrees on a square
    /// root, because an axis-aligned bounding box is invariant under all three
    /// and it was the decision until the review caught it. It is kept because
    /// a disagreement between it and the basis would mean something is true of
    /// the view tree that none of the reasoning here covers, which is worth
    /// refusing on. `isIdentityBasis` decides; this only ever adds a refusal.
    public static func sizeUnchanged(rootW: Int, rootH: Int,
                                     mappedW: Int, mappedH: Int,
                                     tolerance: Int = 1) -> Bool {
        abs(rootW - mappedW) <= tolerance && abs(rootH - mappedH) <= tolerance
    }

    /// Where the owned root's pixels go on the canvas, in canvas (== window)
    /// coordinates, clipped to the canvas.
    ///
    /// Returns `nil` — and the caller refuses rather than guessing — when the
    /// canvas or the root has no area, when the root→window mapping is not a
    /// pure translation, or when the root lies entirely off the canvas.
    ///
    /// THE CLIP IS TO THE CANVAS, NEVER TO A SMALLER GUESS. It removes only
    /// pixels that are not in the image at all. A root that straddles an edge
    /// keeps its whole visible portion; shrinking further would leave owned
    /// content undrawn while the redaction, which is computed from the same
    /// window-space geometry, still expected it to be there.
    public static func destination(rootW: Int, rootH: Int,
                                   originX: Int, originY: Int,
                                   basis: Basis,
                                   canvasW: Int, canvasH: Int) -> NodeBounds? {
        guard canvasW > 0, canvasH > 0, rootW > 0, rootH > 0 else { return nil }
        guard isIdentityBasis(basis) else { return nil }
        // The basis is the identity, so the root's own size IS its size on the
        // canvas — it is never re-derived from a mapped bounding box, which is
        // what could not tell a rotation from an identity.
        let x0 = max(originX, 0), y0 = max(originY, 0)
        let x1 = min(originX + rootW, canvasW), y1 = min(originY + rootH, canvasH)
        guard x1 > x0, y1 > y0 else { return nil }
        return NodeBounds(x: x0, y: y0, w: x1 - x0, h: y1 - y0)
    }

    /// The bands of the canvas the capture root does NOT own — the rectangles
    /// that must hold nothing but `unownedFill`.
    ///
    /// Returned as up-to-four non-overlapping bands (above, below, left, right)
    /// so a pixel audit can walk them exhaustively instead of sampling.
    public static func unownedRegions(canvasW: Int, canvasH: Int,
                                      owned: NodeBounds) -> [NodeBounds] {
        guard canvasW > 0, canvasH > 0 else { return [] }
        var out: [NodeBounds] = []
        let top = max(0, min(owned.y, canvasH))
        let bottom = max(0, min(owned.y + owned.h, canvasH))
        if top > 0 { out.append(NodeBounds(x: 0, y: 0, w: canvasW, h: top)) }
        if bottom < canvasH {
            out.append(NodeBounds(x: 0, y: bottom, w: canvasW, h: canvasH - bottom))
        }
        let left = max(0, min(owned.x, canvasW))
        let right = max(0, min(owned.x + owned.w, canvasW))
        let bandH = bottom - top
        if bandH > 0 {
            if left > 0 { out.append(NodeBounds(x: 0, y: top, w: left, h: bandH)) }
            if right < canvasW {
                out.append(NodeBounds(x: right, y: top, w: canvasW - right, h: bandH))
            }
        }
        return out
    }

}

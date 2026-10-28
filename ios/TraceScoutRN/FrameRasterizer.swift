import Foundation
#if canImport(UIKit)
import UIKit
import CryptoKit

/// Rasterizes the RN screen into a VISUAL FRAME — after privacy treatment,
/// never before (iOS twin of Android `RnFrameRasterizer`).
///
/// THE ORDERING IS THE WHOLE DESIGN. The masked and blocked rectangles — taken
/// from the SAME `HierarchyResult` the wireframe serialised — are painted OVER
/// the drawing in the same renderer pass that produces it, before any encoder
/// sees a byte. The un-redacted intermediate never leaves this function: not
/// written, not returned, not handed to a callback. There is no code path here
/// that yields an unmasked frame; `privacyState` has one value.
///
/// A window that reports secure content (`UIScreen.isCaptured` semantics do
/// not apply; iOS has no FLAG_SECURE) — the platform-secure decision on iOS is
/// per-node (secureTextEntry BLOCKED in the walker) and the redaction paints it.
public enum FrameRasterizer {

    public struct Frame {
        public let bytes: Data
        public let width: Int
        public let height: Int
        public let contentSha256: String
        public let format: String
        public let downscale: Double
        public let maskedRects: Int
        public let blockedRects: Int
    }

    public enum Result {
        case ok(Frame)
        case refused(String)
    }

    private static let maskColor = UIColor(red: 0x25/255, green: 0x63/255, blue: 0xEB/255, alpha: 1)
    private static let blockColor = UIColor(red: 0x92/255, green: 0x40/255, blue: 0x0E/255, alpha: 1)
    /// The one colour the unowned part of the canvas is allowed to hold. Read
    /// from the shared, UIKit-free constant so the pixel audit and the painter
    /// cannot disagree about what "no app pixels here" looks like.
    private static let unownedColor = UIColor(
        red: CGFloat(FrameGeometry.unownedFill.r) / 255,
        green: CGFloat(FrameGeometry.unownedFill.g) / 255,
        blue: CGFloat(FrameGeometry.unownedFill.b) / 255, alpha: 1)

    // THE WHOLE-WINDOW ENTRY POINT `capture(hierarchy:)` IS DELETED, NOT
    // DEPRECATED AND NOT MADE INTERNAL.
    //
    // It resolved the key window itself and photographed it. It had ZERO
    // production callers — the unintegrated path reaches
    // `capture(root:window:)` with the window as its declared root, which is
    // the same picture arrived at through the ownership checks instead of
    // around them. What it was, therefore, was a correct-looking API that
    // takes no root and asks nobody's permission: the exact shape a future
    // caller reaches for when a scoped capture is inconvenient, and the exact
    // leak `capture_root_not_rasterizable` was invented to prevent.
    //
    // An unused footgun is still a footgun, and `internal` only narrows who
    // may pull the trigger. Deleting it makes the whole-window picture
    // UNREACHABLE rather than merely unrecommended.

    /// Capture the pixels of ONE CAPTURE ROOT onto a WINDOW-SIZED canvas,
    /// redacting every masked/blocked rectangle of `hierarchy` in the window
    /// coordinates it is already expressed in.
    ///
    /// THIS IS THE ONLY ENTRY POINT, AND IT IS WHY THE BOUND-ROOT REFUSAL
    /// COULD BE LIFTED. The deleted whole-window entry point described above
    /// drew a WINDOW. Under a route-host binding the hierarchy is correctly
    /// scoped to the host's subtree while the window holds the whole app, so
    /// drawing the window's CONTENT is a misattribution in pixels — foreign
    /// content stored under this route's label. `HierarchySnapshotter` refused
    /// rather than ship that, and the refusal stood until the SOURCE of the
    /// pixels could be scoped to a view: that is this.
    ///
    /// WINDOW-SIZED CANVAS, ROOT-SIZED SUBJECT — the two are different
    /// questions and only one of them was ever the leak. What must never be
    /// drawn is the window's CONTENT; what must be preserved is the window's
    /// COORDINATE SYSTEM, because the wire's node bounds live in it and the
    /// Viewer has no field with which to compensate for anything else.
    ///
    /// THERE IS NO FALL-BACK TO DRAWING THE WINDOW, and there must never be
    /// one. A scoped capture that quietly widened its subject on any difficulty
    /// would reintroduce the leak precisely in the cases nobody looked at.
    /// Every failure here is a refusal.
    /// - Parameter meter: OPT-IN phase accounting (`CaptureAccounting`). `nil`
    /// the default — measures nothing and changes nothing. A meter times
    /// redaction, raster, verify and encode around the SAME calls, and is
    /// forwarded to `captureOnMain` because a meter dropped at this hop
    /// would report zero as a measurement.
    public static func capture(root: UIView, window: UIWindow,
                               hierarchy: HierarchyResult,
                               verify: (() -> String?)? = nil,
                               meter: CaptureAccounting.PhaseMeter? = nil) -> Result {
        if Thread.isMainThread { return captureOnMain(root, window, hierarchy, verify, meter) }
        var out: Result = .refused("main_thread_unavailable")
        DispatchQueue.main.sync { out = captureOnMain(root, window, hierarchy, verify, meter) }
        return out
    }

    /// Is every view from the capture root up to the window UNTRANSFORMED?
    /// Returns `nil` when it is, or the reason it is not.
    ///
    /// THREE INDEPENDENT QUESTIONS, AND THE ORDER IS NOT AN ACCIDENT.
    ///
    /// 1. **The transforms THEMSELVES.** `view.transform` and
    /// `view.layer.transform` are read directly and must be the identity,
    /// for the root AND for every ancestor up to the window. This is the
    /// primary decision: it asks the platform what the transform IS instead
    /// of inferring it from where some points landed.
    /// 2. **The SAMPLED basis.** The origin and both unit basis vectors are
    /// converted into window space and the basis must come back as the
    /// identity. This catches anything the property reads cannot see — a
    /// transform applied through a mechanism neither property exposes — and
    /// is what makes the two checks genuinely independent rather than the
    /// same question twice.
    /// 3. **The bounding-box SIZE.** Kept ONLY as an additional sanity check,
    /// and it is the one that was wrong on its own: an axis-aligned box is
    /// invariant under 180 degree rotation, under mirroring, and under 90
    /// degrees on a SQUARE root, so as a decision it accepted all three and
    /// every mask then landed on pixels that had moved underneath it.
    ///
    /// A PURE-TRANSLATION `CGAffineTransform` IS ALSO REFUSED. It is
    /// geometrically harmless, so this is stricter than the mapping requires —
    /// deliberately. The cost of refusing is one frame at one moment; the cost
    /// of a mapping nobody re-derived correctly is a redaction in the wrong
    /// place, and the two are not comparable.
    ///
    /// Shared with phase C so the post-raster re-check asks the SAME question
    /// as the pre-raster one. A second copy is how the two would drift.
    static func untransformedChainRefusal(_ root: UIView, _ window: UIWindow) -> String? {
        var node: UIView? = root
        while let v = node {
            // `UIView.convert` FLATTENS a 3D layer transform, so on a
            // perspective or X/Y-rotated ancestor the sampled basis would come
            // back looking perfectly ordinary. An unrepresentable mapping is
            // refused, not approximated.
            guard CATransform3DIsAffine(v.layer.transform) else { return "layer_not_affine" }
            guard CATransform3DIsIdentity(v.layer.transform) else { return "layer_transformed" }
            guard v.transform.isIdentity else { return "view_transformed" }
            if v === window { break }
            node = v.superview
        }
        if node == nil { return "root_not_in_window" }

        let r = root.bounds
        let o  = root.convert(CGPoint(x: r.minX, y: r.minY), to: window)
        let ux = root.convert(CGPoint(x: r.minX + 1, y: r.minY), to: window)
        let uy = root.convert(CGPoint(x: r.minX, y: r.minY + 1), to: window)
        let basis = FrameGeometry.Basis(dxx: Double(ux.x - o.x), dxy: Double(ux.y - o.y),
                                        dyx: Double(uy.x - o.x), dyy: Double(uy.y - o.y))
        guard FrameGeometry.isIdentityBasis(basis) else { return "basis_not_identity" }

        // The additional sanity check. It can only ever agree with the two
        // above; if it ever disagreed, something is true of this view tree that
        // none of this reasoning covers, and refusing is the right answer.
        let box = root.convert(r, to: window)
        guard FrameGeometry.sizeUnchanged(rootW: Int(r.width.rounded()),
                                          rootH: Int(r.height.rounded()),
                                          mappedW: Int(box.width.rounded()),
                                          mappedH: Int(box.height.rounded()))
        else { return "mapped_size_changed" }
        return nil
    }

    /// Is the capture root still ATTACHED to the window it was prepared against?
    ///
    /// THE PRODUCT PRODUCES TWO SHAPES OF CAPTURE ROOT AND `view.window`
    /// ANSWERS THIS QUESTION FOR ONLY ONE OF THEM. Under `.bound` the root is a
    /// real subview and `root.window === window` is exactly right. Under
    /// `.unintegrated` the snapshotter sets `captureRoot = rootWindow = window`
    /// deliberately — `HierarchySnapshotter` says so in its own words: *"there
    /// the window IS the declared root, the placement is the identity, the
    /// unowned region is empty, and the output is unchanged."*
    ///
    /// AND `UIWindow.window` IS **NOT** `self`. The previous guard's comment
    /// asserted that it is — *"`UIWindow.window` is itself, so the legacy
    /// subject satisfies this trivially"* — and a window census inside the
    /// fixture app on iPhone 17 Pro / Xcode 26.3 measured the opposite on the
    /// app's own VISIBLE, KEY window with a root view controller:
    ///
    /// [TraceScoutFixtureWindow scene=true hidden=false key=true rvc=true
    /// selfWindow=FALSE]
    ///
    /// So for a window-as-root the guard evaluated `nil === window` and refused
    /// `capture_root_detached` — every frame, unconditionally. A comment that
    /// asserted a platform behaviour nobody had measured is what made four
    /// diagnostic rounds read as a test defect.
    ///
    /// The question is not "does this view report a window?" but "is this root
    /// still the subject that was prepared, in the window it was prepared
    /// against?". Stated for both shapes:
    ///
    /// - root IS the designated window -> it is trivially in itself, and what
    /// can still change is whether that window is attached to a scene at
    /// all. A window removed from its scene renders nothing.
    /// - root is a subview -> unchanged: `root.window === window`.
    ///
    /// IT DELIBERATELY ADDS NOTHING ELSE. Foreground state, content-window
    /// eligibility and binding currency are decided by their OWN checks at their
    /// own sites and are NOT duplicated here; `isKeyWindow` is not required
    /// (the snapshotter refuses to require key-ness of a bound root, and
    /// requiring it of a legacy one would refuse a legitimate subject whenever
    /// key-ness moved); and `isHidden` is not consulted, because a hidden root
    /// is explicitly "attached and has dimensions; it is simply not drawing"
    /// and carries no reason. Widening an attachment predicate into an
    /// eligibility predicate is how one refusal code starts meaning four things.
    ///
    /// Shared with phase C so the post-raster re-check asks the SAME question as
    /// the pre-raster one. A second copy is how the two would drift — and here
    /// a drifted copy would be invisible, because BOTH sites refused the same
    /// legitimate subject for the same wrong reason.
    static func rootAttachmentRefusal(_ root: UIView, _ window: UIWindow) -> String? {
        if root === window {
            guard window.windowScene != nil else { return "capture_root_detached" }
            return nil
        }
        guard root.window === window else { return "capture_root_detached" }
        return nil
    }

    private static func captureOnMain(_ root: UIView, _ window: UIWindow,
                                      _ hierarchy: HierarchyResult,
                                      _ verify: (() -> String?)?,
                                      _ meter: CaptureAccounting.PhaseMeter? = nil) -> Result {
        guard UIApplication.shared.applicationState == .active else { return .refused("not_foreground") }
        // THE OWNERSHIP RE-CHECK, IN PIXELS. The snapshotter established that
        // this root lives in this window before the walk; a root that has since
        // been re-parented or detached is not the subject that was prepared, and
        // rasterizing it anyway would photograph whatever now occupies the
        // space.
        //
        // THE ATTACHMENT TEST IS `rootAttachmentRefusal`, NOT A BARE
        // `root.window === window`. This line used to carry the latter plus a
        // comment claiming `UIWindow.window` is itself; it is not, and the
        // consequence was that the `.unintegrated` frame path — where the root
        // IS the designated window by construction — refused EVERY frame. See
        // that function for the measurement and for what it deliberately does
        // not check.
        if let r = rootAttachmentRefusal(root, window) { return .refused(r) }

        // THE CANVAS IS THE WINDOW. See `FrameGeometry`: the wire carries
        // window-space node bounds and the Viewer sizes its surface from the
        // frame, so a root-sized image would move every wireframe rectangle off
        // the pixels it describes. The frame is window-sized; only the OWNED
        // ROOT'S PIXELS are ever drawn into it.
        let canvas = window.bounds
        let cw = Int(canvas.width.rounded()), ch = Int(canvas.height.rounded())
        guard cw > 0, ch > 0 else { return .refused("frame_dimensions_invalid") }

        let rootRect = root.bounds
        let w = Int(rootRect.width.rounded()), h = Int(rootRect.height.rounded())
        guard w > 0, h > 0 else { return .refused("frame_dimensions_invalid") }

        // THE MAPPING IS INSPECTED AT THE SOURCE **AND** SAMPLED. See
        // `untransformedChainRefusal` — three independent questions, and the
        // bounding-box size comparison is the WEAKEST of them and is kept only
        // as an additional sanity check, never as the decision.
        guard untransformedChainRefusal(root, window) == nil else {
            return .refused("capture_root_transformed")
        }
        let o  = root.convert(CGPoint(x: rootRect.minX, y: rootRect.minY), to: window)
        let ux = root.convert(CGPoint(x: rootRect.minX + 1, y: rootRect.minY), to: window)
        let uy = root.convert(CGPoint(x: rootRect.minX, y: rootRect.minY + 1), to: window)
        let basis = FrameGeometry.Basis(dxx: Double(ux.x - o.x), dxy: Double(ux.y - o.y),
                                        dyx: Double(uy.x - o.x), dyy: Double(uy.y - o.y))
        guard let owned = FrameGeometry.destination(
                rootW: w, rootH: h,
                originX: Int(o.x.rounded()), originY: Int(o.y.rounded()),
                basis: basis, canvasW: cw, canvasH: ch)
        else { return .refused("capture_root_offscreen") }
        let ownedRect = CGRect(x: owned.x, y: owned.y, width: owned.w, height: owned.h)

        let maxSide = max(cw, ch)
        let scale: CGFloat = maxSide > FrameBudgets.maxDim ? CGFloat(FrameBudgets.maxDim) / CGFloat(maxSide) : 1
        let outW = max(1, Int(CGFloat(cw) * scale)), outH = max(1, Int(CGFloat(ch) * scale))
        // THE REDACTION IS NOT TRANSFORMED, AND THAT IS THE POINT.
        // `FramePolicy.redaction` yields WINDOW-space rectangles because that is
        // the space the walker records and the wire carries — and the canvas is
        // now that same space. One coordinate system end to end: the pixels the
        // Viewer draws a wireframe box over are the pixels this mask covers.
        // THE NEXT LINE IS KEPT VERBATIM. `RouteOwnershipTests` anchors its
        // canvas -> redaction -> fill -> draw ORDERING proof on this exact
        // statement; wrapping it in `timed { }` broke that anchor (CI run
        // 35474889883). The redaction phase is therefore bracketed by two
        // explicit clock reads, taken only when a meter is present.
        let redactionT0 = meter == nil ? 0 : DispatchTime.now().uptimeNanoseconds
        let red = FramePolicy.redaction(hierarchy)
        meter?.note(.redaction, nanos: Int64(DispatchTime.now().uptimeNanoseconds &- redactionT0))

        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1   // logical points → one pixel each; the budget is in pixels
        fmt.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: outW, height: outH), format: fmt)
        // The image is produced INSIDE the closure with the redaction already
        // painted; no un-redacted UIImage exists as a separate object.
        // (`timed` only brackets the SAME call with two clock reads.)
        let image = CaptureAccounting.timed(meter, .raster) { renderer.image { ctx in
            ctx.cgContext.scaleBy(x: scale, y: scale)
            // afterScreenUpdates: TRUE. It was `false` until 2026-08-31, with
            // the comment "we draw what is on screen, we do not force a layout
            // pass" — a deliberate performance choice whose consequence was
            // never stated: `false` renders the LAST COMMITTED screen update,
            // while the walker that produced `hierarchy` read the CURRENT tree.
            // Whenever the tree had changed since the last presented frame, the
            // two halves of this "same pass" described different subjects, and
            // the rectangles were painted over a screen whose own content no
            // rectangle covered. Build 14 stored seven such frames.
            //
            // `true` is necessary and NOT sufficient — it narrows the window, it
            // does not close it, because the tree can still move between this
            // draw and the encode. The `verify` phase below is what closes it.
            // THE WHOLE CANVAS IS FILLED FIRST, WITH ONE FLAT NEUTRAL
            // COLOUR. Two reasons, both load-bearing: an untouched context has
            // undefined contents, and the region this build does not own must
            // be provably free of app pixels rather than merely unlikely to
            // contain them.
            unownedColor.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: cw, height: ch))

            // THE ROOT DRAWS ITSELF, CLIPPED TO THE RECTANGLE IT OWNS — the
            // window is never asked to draw, and there is no fall-back that
            // would ask it. The clip is not decoration: it makes "no unowned
            // chrome in this image" a property of the CONTEXT rather than a
            // property of what `drawHierarchy` happens to render for a subview.
            // If that ever renders more than the receiver, the extra pixels are
            // discarded by the clip instead of being stored under this route.
            //
            // `in: ownedRect` places the root at its window-space position;
            // `ownedRect` has the root's own size (a pure translation was
            // required above), so no scaling occurs here and the redaction —
            // computed in the same window space — lands on the same pixels.
            ctx.cgContext.saveGState()
            ctx.cgContext.clip(to: ownedRect)
            root.drawHierarchy(in: ownedRect, afterScreenUpdates: true)
            ctx.cgContext.restoreGState()

            maskColor.setFill()
            for b in red.masked { ctx.fill(CGRect(x: b.x, y: b.y, width: b.w, height: b.h)) }
            // Blocked LAST and unconditionally — the stronger treatment wins.
            blockColor.setFill()
            for b in red.blocked { ctx.fill(CGRect(x: b.x, y: b.y, width: b.w, height: b.h)) }
        } }
        // PHASE C — coherence, before the encoder sees a byte. The redacted
        // `image` is a local; refusing here drops it on the floor.
        guard let v = verify else { return .refused("coherence_unverified") }
        if let why = CaptureAccounting.timed(meter, .verify, v) { return .refused(why) }
        // JPEG: the one encoder UIKit ships on every supported iOS; the server
        // sniffs FFD8FF and accepts `jpeg`. WebP would need a third-party codec.
        guard let bytes = CaptureAccounting.timed(meter, .encode, {
            image.jpegData(compressionQuality: CGFloat(FrameBudgets.quality) / 100)
        }) else { return .refused("frame_encode_failed") }
        if let bad = FramePolicy.validateEncoded(width: outW, height: outH, byteSize: bytes.count) {
            return .refused(bad)
        }
        let sha = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        return .ok(Frame(bytes: bytes, width: outW, height: outH, contentSha256: sha,
                         format: "jpeg", downscale: Double(scale),
                         maskedRects: red.masked.count, blockedRects: red.blocked.count))
    }
}
#endif

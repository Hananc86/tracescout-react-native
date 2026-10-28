package com.tracescout.mobile.rn

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.tracescout.rn.FrameBudgets
import com.tracescout.rn.FrameGeometry
import com.tracescout.rn.FramePolicy
import com.tracescout.rn.HierarchyResult
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Rasterizes ONE CAPTURE ROOT into a VISUAL FRAME — after privacy treatment,
 * never before (the Android-native `VisualFrameCapturer` design, restated for
 * the RN transport).
 *
 * THE ORDERING IS THE WHOLE DESIGN. The masked and blocked rectangles — taken
 * from the SAME `HierarchyResult` the wireframe serialised — are painted OVER
 * the bitmap in the same pass that produces it, before any encoder sees a
 * byte. The un-redacted intermediate never leaves this function's stack: not
 * written, not returned, not handed to a callback on any path. There is no
 * code path here that yields an unmasked frame; `privacyState` has one value.
 *
 * A FLAG_SECURE window is EXCLUDED, not blurred — the OS forbids capturing it.
 *
 * THE WHOLE-WINDOW ENTRY POINT `capture(activity, hierarchy)` IS DELETED,
 * NOT DEPRECATED AND NOT MADE PRIVATE.
 *
 * It resolved `act.window.decorView` itself and photographed it. The
 * unintegrated path now reaches [capture] with the decor view as its DECLARED
 * root, which is the same picture arrived at THROUGH the ownership checks
 * instead of around them. What the old entry point was, therefore, was a
 * correct-looking API that takes no root and asks nobody's permission: the
 * exact shape a future caller reaches for when a scoped capture is
 * inconvenient, and the exact leak `capture_root_not_rasterizable` was
 * invented to prevent. Deleting it makes the whole-window picture UNREACHABLE
 * rather than merely unrecommended.
 *
 * WINDOW-SIZED CANVAS, ROOT-SIZED SUBJECT — the two are different questions
 * and only one of them was ever the leak. What must never be drawn is the
 * window's CONTENT; what must be preserved is the window's COORDINATE SYSTEM,
 * because `RnHierarchySnapshotter.rawNode` records every node's bounds with
 * `getLocationInWindow` and the Viewer draws them raw against a surface it
 * sizes from the frame. See `FrameGeometry` for the full argument.
 *
 * THERE IS NO FALL-BACK TO DRAWING THE WINDOW, and there must never be one.
 * A scoped capture that quietly widened its subject on any difficulty would
 * reintroduce the leak precisely in the cases nobody looked at. Every failure
 * here is a refusal.
 */
object RnFrameRasterizer {

    /** Opaque redaction fills — never alpha, never a blur. */
    private val MASK_PAINT = Paint().apply { color = Color.rgb(0x25, 0x63, 0xEB); isAntiAlias = false; style = Paint.Style.FILL }
    private val BLOCK_PAINT = Paint().apply { color = Color.rgb(0x92, 0x40, 0x0E); isAntiAlias = false; style = Paint.Style.FILL }

    /**
 * The one colour the unowned part of the canvas is allowed to hold. Read
 * from the shared, Android-free constant so the pixel audit and the painter
 * cannot disagree about what "no app pixels here" looks like.
 */
    private val UNOWNED_PAINT = Paint().apply {
        color = Color.rgb(FrameGeometry.unownedFill.r, FrameGeometry.unownedFill.g, FrameGeometry.unownedFill.b)
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    data class Frame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val contentSha256: String,
        val format: String,
        val downscale: Double,
        val maskedRects: Int,
        val blockedRects: Int,
    )

    sealed class Result {
        data class Ok(val frame: Frame) : Result()
        data class Refused(val code: String) : Result()
    }

    private const val MAIN_THREAD_WAIT_MS = 400L

    /**
 * Every refusal code this object can produce. BOUNDED AND CLOSED: the
 * observability hook in `RnHierarchySnapshotter` validates against this set
 * and reports `other` for anything else, so a future refusal cannot reach a
 * trace line as free text.
 *
 * NONE OF THESE IS A PROTOCOL REASON AND NONE REACHES THE WIRE. They are
 * code identifiers, carrying no user content, no URL and no credential.
 */
    val REFUSAL_CODES: Set<String> = setOf(
        "no_activity",
        "main_thread_timeout",
        "platform_secure_surface",
        "no_window",
        "canvas_origin_not_window",
        "frame_dimensions_invalid",
        "capture_root_detached",
        "capture_root_zero_size",
        "capture_root_transformed",
        "capture_root_offscreen",
        "frame_encode_failed",
        "frame_byte_budget",
        "frame_byte_size_invalid",
        "frame_capture_failed",
    )

    /** Whether the activity's window carries FLAG_SECURE. Unknown = secure. */
    fun windowSecure(activity: Activity?): Boolean = try {
        val flags = activity?.window?.attributes?.flags ?: return true
        (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    } catch (_: Throwable) { true }

    /**
 * Capture the pixels of ONE CAPTURE ROOT onto a WINDOW-SIZED canvas,
 * redacting every masked/blocked rectangle of [hierarchy] in the window
 * coordinates it is already expressed in.
 *
 * [root] is the OWNED capture root: the view the walk started from. Under
 * `CaptureRootBinding.Bound` it is the registered host's view; under
 * `Unintegrated` it is [canvasRoot] itself, where the window IS the
 * declared root — the placement is the identity, the unowned region is
 * empty, and the output is unchanged from the certified whole-window
 * behaviour.
 *
 * [canvasRoot] is the window's own root view (the decor view). Its extent
 * IS the canvas, and its coordinate space IS the space the wire's node
 * bounds live in.
 *
 * Runs on the main thread with a bounded wait; a timeout is a refused
 * frame, never a partial one.
 */
    fun capture(activity: Activity?, root: View, canvasRoot: View, hierarchy: HierarchyResult): Result {
        val act = activity ?: return Result.Refused("no_activity")
        if (Looper.myLooper() == Looper.getMainLooper()) return captureOnMain(act, root, canvasRoot, hierarchy)
        var out: Result = Result.Refused("main_thread_timeout")
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { out = captureOnMain(act, root, canvasRoot, hierarchy) } finally { latch.countDown() }
        }
        return if (latch.await(MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)) out
        else Result.Refused("main_thread_timeout")
    }

    /**
 * Is the capture root still ATTACHED to the window it was prepared against?
 * Returns null when it is, or the refusal code when it is not.
 *
 * THE IOS DEFECT DOES NOT REPRODUCE HERE, AND THE REASON IS THE API
 * SHAPE RATHER THAN LUCK. On iOS the equivalent guard asked
 * `root.window === window`, and `UIWindow.window` was MEASURED to be nil on
 * a live, key, visible window — so the window-as-root (legacy) shape
 * refused every frame. Android's decor view is an ordinary `View`:
 * `isAttachedToWindow` is defined for it exactly as for any subview, and
 * `windowToken` is non-null once attached. Both shapes are therefore asked
 * the same two questions and neither is a special case.
 *
 * IT DELIBERATELY ADDS NOTHING ELSE. Foreground state, FLAG_SECURE,
 * never-record and binding currency are decided by their OWN checks at
 * their own sites and are NOT duplicated here. Visibility is not consulted:
 * an invisible root is explicitly "attached and has dimensions; it is
 * simply not drawing" and carries no reason of its own. Widening an
 * attachment predicate into an eligibility predicate is how one refusal
 * code starts meaning four things.
 */
    internal fun rootAttachmentRefusal(root: View, canvasRoot: View): String? {
        if (!root.isAttachedToWindow) return "capture_root_detached"
        if (root === canvasRoot) return null
        val token = root.windowToken ?: return "capture_root_detached"
        // SAME WINDOW, ASKED OF THE VIEWS THEMSELVES. A view from a previous
        // activity can still satisfy isAttachedToWindow while belonging to a
        // window this capture has nothing to do with.
        if (token !== canvasRoot.windowToken) return "capture_root_detached"
        return null
    }

    /**
 * The root -> canvas mapping, composed from PUBLIC view geometry, or null
 * when the chain does not reach [canvasRoot].
 *
 * Mirrors the platform's own `View.transformMatrixToGlobal` composition —
 * per view: its own matrix, then its offset within its parent, then the
 * parent's scroll — using only `getMatrix` / `left` / `top` /
 * `scrollX` / `scrollY` / `parent`.
 */
    private fun composeRootToCanvas(root: View, canvasRoot: View): Matrix? {
        val m = Matrix()
        if (root === canvasRoot) return m
        var v: View = root
        while (true) {
            if (!v.matrix.isIdentity) m.postConcat(v.matrix)
            m.postTranslate(v.left.toFloat(), v.top.toFloat())
            val p = v.parent
            if (p !is View) return null
            m.postTranslate(-p.scrollX.toFloat(), -p.scrollY.toFloat())
            if (p === canvasRoot) return m
            v = p
        }
    }

    /**
 * Is every view from the capture root up to the canvas root UNTRANSFORMED?
 * Returns null when it is, or the reason it is not.
 *
 * TWO INDEPENDENT QUESTIONS PLUS A SANITY CHECK, AND THE ORDER IS NOT AN
 * ACCIDENT.
 *
 * 1. **The transforms THEMSELVES.** `View.getMatrix` is read directly and
 * must be the identity, for the root AND for every ancestor up to the
 * canvas root. This is the primary decision: it asks the platform what
 * the transform IS instead of inferring it from where some points
 * landed. (`getMatrix` already folds in scale, rotation, pivot and the
 * translationX/Y properties.)
 * 2. **The ORIGIN, asked of a DIFFERENT platform mechanism.** Our composed
 * translation must agree with `getLocationInWindow` — the call the
 * hierarchy walker itself uses for every node's bounds. That is what
 * makes this genuinely independent of (1) rather than the same question
 * twice: if the composition and the walker disagree about where the root
 * is, the frame and the wire disagree about where everything is.
 * 3. **The bounding-box SIZE.** Kept ONLY as an additional sanity check,
 * and it is the one that was wrong on its own: an axis-aligned box is
 * invariant under 180 degree rotation, under mirroring, and under 90
 * degrees on a SQUARE root, so as a decision it accepted all three and
 * every mask then landed on pixels that had moved underneath it.
 *
 * AN HONEST ASYMMETRY WITH THE SWIFT TWIN, STATED RATHER THAN GLOSSED.
 * iOS samples the basis through `UIView.convert(_:to:)`, a platform point
 * conversion with no Android public equivalent that walks a view chain, so
 * there the sampled basis is a third independent question. Here the basis
 * is computed from OUR composition, which makes it a consistency check on
 * (1) rather than an independent oracle — and the independent oracle is
 * (2) instead. Both platforms refuse anything that is not a pure
 * translation; only the third witness differs.
 *
 * A PURE-TRANSLATION `translationX/Y` IS ALSO REFUSED. It is
 * geometrically harmless, so this is stricter than the mapping requires —
 * deliberately. The cost of refusing is one frame at one moment; the cost
 * of a mapping nobody re-derived correctly is a redaction in the wrong
 * place, and the two are not comparable.
 */
    internal fun untransformedChainRefusal(root: View, canvasRoot: View): String? {
        var node: View? = root
        while (node != null) {
            if (!node.matrix.isIdentity) return "view_transformed"
            if (node === canvasRoot) break
            val p = node.parent
            node = if (p is View) p else null
        }
        if (node == null) return "root_not_in_window"

        val m = composeRootToCanvas(root, canvasRoot) ?: return "root_not_in_window"
        val pts = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        m.mapPoints(pts)
        val basis = FrameGeometry.Basis(
            dxx = (pts[2] - pts[0]).toDouble(), dxy = (pts[3] - pts[1]).toDouble(),
            dyx = (pts[4] - pts[0]).toDouble(), dyy = (pts[5] - pts[1]).toDouble(),
        )
        if (!FrameGeometry.isIdentityBasis(basis)) return "basis_not_identity"

        // (2) The independent origin oracle: the walker's own call.
        val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
        val canvasLoc = IntArray(2); canvasRoot.getLocationInWindow(canvasLoc)
        val composedX = Math.round(pts[0])
        val composedY = Math.round(pts[1])
        val platformX = rootLoc[0] - canvasLoc[0]
        val platformY = rootLoc[1] - canvasLoc[1]
        if (Math.abs(composedX - platformX) > 1 || Math.abs(composedY - platformY) > 1) {
            return "origin_disagreement"
        }

        // (3) The additional sanity check. It can only ever agree with the two
        // above; if it ever disagreed, something is true of this view tree that
        // none of this reasoning covers, and refusing is the right answer.
        val box = android.graphics.RectF(0f, 0f, root.width.toFloat(), root.height.toFloat())
        m.mapRect(box)
        if (!FrameGeometry.sizeUnchanged(
                rootW = root.width, rootH = root.height,
                mappedW = Math.round(box.width()), mappedH = Math.round(box.height()),
            )
        ) return "mapped_size_changed"
        return null
    }

    private fun captureOnMain(act: Activity, root: View, canvasRoot: View, hierarchy: HierarchyResult): Result {
        if (windowSecure(act)) return Result.Refused("platform_secure_surface")

        // THE OWNERSHIP RE-CHECK, IN PIXELS. The snapshotter established that
        // this root lives in this window before the walk; a root that has since
        // been re-parented or detached is not the subject that was prepared, and
        // rasterizing it anyway would photograph whatever now occupies the space.
        rootAttachmentRefusal(root, canvasRoot)?.let { return Result.Refused(it) }

        // THE CANVAS IS THE WINDOW. See `FrameGeometry`: the wire carries
        // window-space node bounds and the Viewer sizes its surface from the
        // frame, so a root-sized image would move every wireframe rectangle off
        // the pixels it describes.
        val cw = canvasRoot.width
        val ch = canvasRoot.height
        if (cw <= 0 || ch <= 0) return Result.Refused("frame_dimensions_invalid")

        // THE CANVAS ORIGIN IS ASSERTED, NOT ASSUMED. Node bounds are
        // ABSOLUTE window coordinates, so the canvas must start at window
        // (0, 0). The decor view is the root of the window's view hierarchy, so
        // that holds — but an assumption about a platform behaviour nobody
        // measured is exactly what made the iOS attachment guard refuse every
        // legacy frame, so it is checked rather than believed.
        val canvasLoc = IntArray(2)
        canvasRoot.getLocationInWindow(canvasLoc)
        if (canvasLoc[0] != 0 || canvasLoc[1] != 0) return Result.Refused("canvas_origin_not_window")

        val rw = root.width
        val rh = root.height
        if (rw <= 0 || rh <= 0) return Result.Refused("capture_root_zero_size")

        // THE MAPPING IS INSPECTED AT THE SOURCE **AND** CROSS-CHECKED.
        if (untransformedChainRefusal(root, canvasRoot) != null) {
            return Result.Refused("capture_root_transformed")
        }
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val owned = FrameGeometry.destination(
            rootW = rw, rootH = rh,
            originX = rootLoc[0], originY = rootLoc[1],
            basis = FrameGeometry.Basis.IDENTITY,
            canvasW = cw, canvasH = ch,
        ) ?: return Result.Refused("capture_root_offscreen")

        val scale = if (maxOf(cw, ch) > FrameBudgets.MAX_DIM) FrameBudgets.MAX_DIM.toFloat() / maxOf(cw, ch) else 1f
        val outW = maxOf(1, (cw * scale).toInt()); val outH = maxOf(1, (ch * scale).toInt())

        // THE REDACTION IS NOT TRANSFORMED, AND THAT IS THE POINT.
        // `FramePolicy.redaction` yields WINDOW-space rectangles because that is
        // the space the walker records and the wire carries — and the canvas is
        // now that same space. One coordinate system end to end: the pixels the
        // Viewer draws a wireframe box over are the pixels this mask covers.
        val red = FramePolicy.redaction(hierarchy)

        var bmp: Bitmap? = null
        try {
            bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)

            // THE WHOLE CANVAS IS FILLED FIRST, WITH ONE FLAT NEUTRAL
            // COLOUR. Two reasons, both load-bearing: a fresh ARGB_8888 bitmap
            // is transparent rather than defined-opaque, and the region this
            // capture does not own must be provably free of app pixels rather
            // than merely unlikely to contain them.
            canvas.drawRect(Rect(0, 0, cw, ch), UNOWNED_PAINT)

            // THE ROOT DRAWS ITSELF, CLIPPED TO THE RECTANGLE IT OWNS — the
            // window is never asked to draw, and there is no fall-back that
            // would ask it. The clip is not decoration: it makes "no unowned
            // chrome in this image" a property of the CANVAS rather than a
            // property of what `View.draw` happens to render for a subview
            // whose children overflow its bounds. If it ever renders more than
            // the receiver, the extra pixels are discarded by the clip instead
            // of being stored under this route.
            //
            // The translate places the root at its window-space position;
            // `owned` has the root's own size (a pure translation was required
            // above), so no scaling occurs here and the redaction — computed in
            // the same window space — lands on the same pixels.
            val saved = canvas.save()
            try {
                canvas.clipRect(owned.x, owned.y, owned.x + owned.w, owned.y + owned.h)
                canvas.translate(rootLoc[0].toFloat(), rootLoc[1].toFloat())
                root.draw(canvas)
            } finally {
                canvas.restoreToCount(saved)
            }

            // Redact IN THE SAME PASS, in WINDOW coordinates — the space the
            // walker recorded them in and the space this canvas is.
            for (b in red.masked) canvas.drawRect(Rect(b.x, b.y, b.x + b.w, b.y + b.h), MASK_PAINT)
            // Blocked LAST and unconditionally — the stronger treatment wins.
            for (b in red.blocked) canvas.drawRect(Rect(b.x, b.y, b.x + b.w, b.y + b.h), BLOCK_PAINT)

            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
            if (!bmp.compress(fmt, FrameBudgets.QUALITY, out)) return Result.Refused("frame_encode_failed")
            val bytes = out.toByteArray()
            FramePolicy.validateEncoded(outW, outH, bytes.size)?.let { return Result.Refused(it) }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return Result.Ok(Frame(bytes, outW, outH, sha, "webp", scale.toDouble(),
                red.masked.size, red.blocked.size))
        } catch (_: Throwable) {
            return Result.Refused("frame_capture_failed")
        } finally {
            try { bmp?.recycle() } catch (_: Throwable) {}
        }
    }
}

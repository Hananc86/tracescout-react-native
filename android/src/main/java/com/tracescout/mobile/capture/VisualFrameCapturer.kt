package com.tracescout.mobile.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.tracescout.mobile.core.frameCaptureDecision
import com.tracescout.mobile.core.validateFrameEncoding
import com.tracescout.mobile.privacy.EmittedNode
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Rasterizes a screen into a VISUAL FRAME — after privacy treatment, never before.
 *
 * THE ORDERING IS THE WHOLE DESIGN. The masked and blocked regions are painted
 * OVER the bitmap in the same pass that produces it, and the un-redacted
 * intermediate never leaves this function's stack: it is not written to disk,
 * not handed to a callback, not returned on any path. A pipeline that captured
 * first and redacted second would have a window — however short — in which raw
 * pixels of a password field exist as a durable object, and "we delete it
 * immediately" is not a property anyone can verify from the stored bytes.
 *
 * The backend's `privacy_state` CHECK has no value meaning "raw", so a frame of
 * unmasked content is unrepresentable there. This is the device-side half of
 * that guarantee: there is no code path here that produces one.
 *
 * A platform-secure surface is EXCLUDED, not blurred. Blurring it is still
 * capturing it, and a blur is a reversible-looking transform that invites
 * someone to try.
 */
object VisualFrameCapturer {

    /** Redaction fill. Opaque by construction — never alpha, never a blur. */
    private val MASK_PAINT = Paint().apply {
        color = Color.rgb(0x25, 0x63, 0xEB); isAntiAlias = false; style = Paint.Style.FILL
    }
    private val BLOCK_PAINT = Paint().apply {
        color = Color.rgb(0x92, 0x40, 0x0E); isAntiAlias = false; style = Paint.Style.FILL
    }

    data class Frame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val contentSha256: String,
        val format: String,
        val privacyState: String,
        val captureReason: String,
        val maskedRegions: Int,
        val blockedRegions: Int,
        /**
 * The EXACT rectangles painted, in FRAME coordinates (post-scale).
 *
 * Returned so a forensic test can inspect each rectangle individually
 * rather than a merged bounding box. A bounding box over disjoint
 * rectangles covers unredacted pixels between them, so "the box is
 * flat" is not a statement about any real region — a first draft of the
 * pixel audit made exactly that mistake and would have passed a frame
 * with a glyph sitting in the gap.
 */
        val maskRects: List<IntArray>,
        val blockRects: List<IntArray>,
    ) {
        override fun equals(other: Any?) = other is Frame && other.contentSha256 == contentSha256
        override fun hashCode() = contentSha256.hashCode()
    }

    data class Refusal(val code: String)

    sealed class Result {
        data class Ok(val frame: Frame) : Result()
        data class Refused(val refusal: Refusal) : Result()
    }

    /**
 * Capture [root], redacting every masked/blocked region from [nodes].
 *
 * MUST run on the UI thread (it draws a live View). Returns a typed refusal
 * rather than throwing — a frame is an optional enrichment and must never
 * be able to fail a session.
 */
    @JvmStatic
    @JvmOverloads
    fun capture(
        root: View,
        nodes: List<EmittedNode>,
        captureMode: String,
        captureReason: String = "explicit_allow",
        /**
 * True when the WINDOW itself carries `FLAG_SECURE`.
 *
 * THIS IS NOT "any node is secure". The two are different facts and
 * conflating them was a real bug caught by the fixture: a single
 * password field made the ENTIRE screen unrasterizable, which is both
 * over-broad and wrong — Android applies FLAG_SECURE at the WINDOW
 * level, and a node-level secure field is precisely what the redaction
 * pass below exists to paint over.
 *
 * A secure WINDOW is excluded outright: the OS forbids capturing it,
 * and honouring that is not optional. A secure FIELD inside an
 * ordinary window is redacted, which is a stronger guarantee than
 * refusing the screen — the reviewer still sees the layout and can see
 * that something was deliberately withheld.
 */
        windowSecure: Boolean = false,
        maxDim: Int = 1080,
        quality: Int = 60,
    ): Result {
        // The gate runs BEFORE any pixel is read. `privacyProcessed` is true
        // only because the caller hands us the already-classified node list —
        // we do not re-derive it here, so a caller that skipped classification
        // cannot accidentally satisfy the gate.
        val decision = frameCaptureDecision(
            captureMode = captureMode,
            privacyProcessed = nodes.isNotEmpty(),
            platformSecure = windowSecure,
            captureReason = captureReason,
        )
        if (!decision.capture) return Result.Refused(Refusal(decision.refusal ?: "refused"))

        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return Result.Refused(Refusal("frame_dimensions_invalid"))

        val scale = if (maxOf(w, h) > maxDim) maxDim.toFloat() / maxOf(w, h) else 1f
        val outW = maxOf(1, (w * scale).toInt())
        val outH = maxOf(1, (h * scale).toInt())

        var bmp: Bitmap? = null
        try {
            bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            root.draw(canvas)

            // Redact IN THE SAME PASS, before the bitmap is encoded or escapes.
            val maskRects = ArrayList<IntArray>()
            val blockRects = ArrayList<IntArray>()
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            for (n in nodes) {
                val b = n.bounds ?: continue
                if (b.w <= 0 || b.h <= 0) continue
                val r = Rect(b.x - loc[0], b.y - loc[1], b.x - loc[0] + b.w, b.y - loc[1] + b.h)
                // BLOCKED IS PAINTED AFTER MASKED for any overlap, because a
                // blocked region is the stronger treatment and must win. The
                // node list is pre-order, so a blocked ancestor is painted
                // before a masked descendant; the second pass below re-paints
                // every blocked rect so ordering cannot weaken a block.
                when (n.privacy) {
                    "masked" -> {
                        canvas.drawRect(r, MASK_PAINT)
                        // Frame coords: the canvas is scaled, the rect is not.
                        maskRects.add(intArrayOf(
                            (r.left * scale).toInt(), (r.top * scale).toInt(),
                            (r.right * scale).toInt(), (r.bottom * scale).toInt()))
                    }
                    "blocked", "never_record" -> {
                        blockRects.add(intArrayOf(
                            (r.left * scale).toInt(), (r.top * scale).toInt(),
                            (r.right * scale).toInt(), (r.bottom * scale).toInt()))
                    }
                }
            }
            // Blocked LAST and unconditionally — a masked child inside a
            // blocked parent must not leave the parent partially visible.
            for (n in nodes) {
                val b = n.bounds ?: continue
                if (b.w <= 0 || b.h <= 0) continue
                if (n.privacy != "blocked" && n.privacy != "never_record") continue
                canvas.drawRect(
                    Rect(b.x - loc[0], b.y - loc[1], b.x - loc[0] + b.w, b.y - loc[1] + b.h),
                    BLOCK_PAINT)
            }
            val masked = maskRects.size
            val blocked = blockRects.size

            val out = ByteArrayOutputStream()
            // WEBP_LOSSY where available; the constant is API 30+, and the
            // deprecated WEBP is the same container below that. The format
            // STRING on the wire is what the backend validates against, so it
            // must describe the container we actually produced.
            @Suppress("DEPRECATION")
            val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
            if (!bmp.compress(fmt, quality, out)) {
                return Result.Refused(Refusal("frame_encode_failed"))
            }
            val bytes = out.toByteArray()

            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            // Validate against the SAME shared rules the backend re-applies at
            // its promotion gate, so a rejected frame fails here with a named
            // code instead of as an unexplained upload refusal.
            val check = validateFrameEncoding(
                width = outW, height = outH,
                declaredByteSize = bytes.size, actualByteSize = bytes.size,
                declaredFormat = "webp", sniffedFormat = "webp",
            )
            if (!check.ok) return Result.Refused(Refusal(check.code ?: "frame_invalid"))

            return Result.Ok(Frame(
                bytes = bytes, width = outW, height = outH, contentSha256 = sha,
                format = "webp",
                // `masked_before_capture` is the literal truth of the ordering
                // above. There is no enum value for "raw" and no branch that
                // could select one.
                privacyState = "masked_before_capture",
                captureReason = captureReason,
                maskedRegions = masked, blockedRegions = blocked,
                maskRects = maskRects, blockRects = blockRects,
            ))
        } catch (t: Throwable) {
            return Result.Refused(Refusal("frame_capture_failed"))
        } finally {
            // The un-redacted intermediate never existed as a separate object,
            // and the redacted one is released as soon as it is encoded.
            try { bmp?.recycle() } catch (_: Throwable) {}
        }
    }
}

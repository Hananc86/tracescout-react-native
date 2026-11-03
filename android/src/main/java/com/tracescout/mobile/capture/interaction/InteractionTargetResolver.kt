package com.tracescout.mobile.capture.interaction

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import com.tracescout.mobile.capture.ViewHierarchySnapshotter
import com.tracescout.mobile.capture.interaction.InteractionPolicy.PositionTreatment
import com.tracescout.mobile.capture.interaction.InteractionPolicy.TargetClass
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hit-test + classify the View under a screen point, producing the FACTS the
 * shared policy consumes — never a decision.
 *
 * IT READS NO TEXT. Not `getText`, not `contentDescription`, not a hint,
 * not an id name. The only things read from the target are its class, its
 * input type, its visibility/bounds, its `tag` (the SDK's existing privacy
 * vocabulary) and whether it was declared position-safe.
 *
 * Coordinates are SCREEN pixels (`getLocationOnScreen`) — the exact basis
 * `ViewHierarchySnapshotter.classify` uses for node bounds, so a positional
 * interaction and the node it landed on share one coordinate space.
 */
object InteractionTargetResolver {

    data class Target(
        val targetClass: TargetClass,
        val positionTreatment: PositionTreatment,
        val found: Boolean,
        /**
 * The `WebView` the point landed in, if any — the INSTANCE, never
 * anything about its content (S5, an earlier change).
 *
 * The hit test already had to answer this to classify web content as
 * blocked; it used to compute the answer and throw it away, so the SDK
 * could know a WebView segment existed and never say that THIS touch
 * landed in it. Carrying the instance lets
 * `WebViewAttachment.carrierFor` name the segment — and nothing else
 * about the view is read, here or there.
 */
        val webView: WebView? = null,
    )

    /**
 * Node-local, never-inherited position declarations, for hosts whose `tag`
 * already carries a privacy token (`ts-allow` is an equality match, so it
 * cannot share the field). Weak so a declaration never keeps a View alive.
 */
    private val declared: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    fun declarePositionSafe(view: View) { synchronized(declared) { declared.add(view) } }
    fun clearPositionSafe(view: View) { synchronized(declared) { declared.remove(view) } }

    private fun isDeclared(v: View): Boolean =
        synchronized(declared) { declared.contains(v) } ||
            InteractionPolicy.isPositionDeclared(v.tag as? String)

    /** Any editable control holding focus anywhere in this window. */
    fun textEntryActive(root: View): Boolean = try {
        root.findFocus()?.let { it is EditText || it.onCheckIsTextEditor() } ?: false
    } catch (_: Throwable) { true }   // unknown -> the restrictive answer

    /** A window the app marked FLAG_SECURE is treated as never-record for touches. */
    fun windowSecure(root: View): Boolean = try {
        val p = root.layoutParams as? WindowManager.LayoutParams
        p != null && (p.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    } catch (_: Throwable) { true }

    fun resolve(root: View, screenX: Float, screenY: Float): Target = try {
        resolveUnsafe(root, screenX, screenY)
    } catch (_: Throwable) {
        Target(TargetClass.UNKNOWN, PositionTreatment.BLOCKED, false)
    }

    private fun resolveUnsafe(root: View, x: Float, y: Float): Target {
        var view: View? = if (contains(root, x, y)) root else null
        var blockedContext = false
        var hit: View? = null
        while (view != null) {
            hit = view
            if (blockedByTag(view)) blockedContext = true
            val g = view as? ViewGroup ?: break
            // Top-most child first: later children draw over earlier ones.
            var next: View? = null
            for (i in g.childCount - 1 downTo 0) {
                val c = g.getChildAt(i)
                if (c.visibility == View.VISIBLE && contains(c, x, y)) { next = c; break }
            }
            if (next == null) break
            if (view is WebView) break     // web content is S5's lane, never descended
            view = next
        }
        val t = hit ?: return Target(TargetClass.UNKNOWN, PositionTreatment.REDACTED, false)
        val secure = isPassword(t)
        val editable = !secure && (t is EditText || t.onCheckIsTextEditor())
        // ONE question, asked once. `webContent` used to be a bare Boolean
        // derived from the same two checks; deriving both from the resolved
        // instance is what stops the classification and the carrier from ever
        // disagreeing about whether the touch was in a WebView.
        val webView = t as? WebView ?: webViewAncestor(t)
        val webContent = webView != null
        val contentBlocked = blockedContext || secure || webContent
        val cls = when {
            secure -> TargetClass.SECURE
            editable -> TargetClass.EDITABLE
            contentBlocked -> TargetClass.REDACTED
            else -> TargetClass.SAFE
        }
        // THIS NODE's declaration only. A declared parent never lends its
        // consent to an undeclared child.
        val pos = PositionTreatment.resolve(
            declaredOnThisNode = isDeclared(t),
            contentBlocked = contentBlocked,
            inBlockedContext = blockedContext,
        )
        return Target(cls, pos, true, webView)
    }

    private fun blockedByTag(v: View): Boolean {
        val tag = v.tag as? String ?: return false
        return tag == ViewHierarchySnapshotter.TAG_BLOCK || tag == ViewHierarchySnapshotter.TAG_NEVER_RECORD
    }

    /** The nearest `WebView` ancestor, or null. Reads only view identity. */
    private fun webViewAncestor(v: View): WebView? {
        var p = v.parent
        while (p is View) { if (p is WebView) return p; p = p.parent }
        return null
    }

    private fun isPassword(v: View): Boolean {
        val et = v as? EditText ?: return false
        val variation = et.inputType and InputType.TYPE_MASK_VARIATION
        val cls = et.inputType and InputType.TYPE_MASK_CLASS
        return (cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private val loc = IntArray(2)
    private fun contains(v: View, x: Float, y: Float): Boolean {
        v.getLocationOnScreen(loc)
        return x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
    }
}

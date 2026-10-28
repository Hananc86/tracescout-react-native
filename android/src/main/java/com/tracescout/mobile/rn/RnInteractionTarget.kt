package com.tracescout.mobile.rn

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.tracescout.rn.InteractionPolicy
import com.tracescout.rn.InteractionTargetClass
import com.tracescout.rn.PositionDeclaration
import com.tracescout.rn.PositionTreatment
import com.tracescout.rn.RawNode
import com.tracescout.rn.Treatment

/**
 * Resolve a touch point to the EXACT replay node that owns it, and to that
 * node's privacy facts.
 *
 * ## One classification authority
 *
 * The per-view platform facts come from `RnHierarchySnapshotter.classifyView`
 * the same function the hierarchy walker uses. The rule is *same view + same
 * privacy state ⇒ same content Treatment*, and it holds because there is only
 * one implementation, not because two of them are kept in agreement.
 * Interaction adds exactly one thing the walker does not need: the independent
 * `PositionTreatment`.
 *
 * ## Hit-testing reads no content
 *
 * It uses geometry, visibility and the platform type/flags the replay
 * classifier already consumes. It does NOT read target text, an input value, an
 * accessibility label, hint, value or `contentDescription`, and it never
 * serialises a view to a string. Locating a tap is not a reason to start
 * inspecting content.
 *
 * ## No safe-ancestor fallback
 *
 * The deepest hit node owns position consent. There is deliberately no upward
 * walk looking for a `ts-position-safe` ancestor — that walk is what would let
 * a declared container disclose the location of a secure child inside it.
 */
internal object RnInteractionTarget {

    /**
 * TYPED, AND CARRYING NO STRINGS FROM THE VIEW. `role` is the bounded
 * class-derived component type the walker already emits (a code identifier,
 * not user data). There is no field here that could hold a label, a value
 * or a `nativeID` — the declaration is resolved to an ENUM at the point of
 * reading, so the string never survives the call.
 */
    internal data class Resolved(
        val found: Boolean,
        val role: String?,
        val contentTreatment: Treatment?,
        val editable: Boolean,
        val secure: Boolean,
        val positionTreatment: PositionTreatment,
        /** Window-space bounds of the resolved node, for the later transform. */
        val left: Int, val top: Int, val width: Int, val height: Int,
        /**
 * an earlier change — the hit view IS web content or lies BENEATH web content on
 * the descent path, by the ONE `RnWebViewAttach.isWebContent` home.
 *
 * A FACT FROM THE HIT TEST, NOT A CARRIER: it names no segment, it
 * only decides whether the host's carrier is ASKED for this tap.
 * Defaulted false, so an unresolved hit (and every construction that
 * does not name it) makes no WebView claim — UNREPORTED, never WEBVIEW.
 */
        val insideWebContent: Boolean = false,
    ) {
        val targetClass: InteractionPolicy.TargetClass
            get() = if (!found) InteractionPolicy.TargetClass.UNKNOWN
                    else InteractionTargetClass.of(contentTreatment, null, editable, secure)

        companion object {
            /** An unresolved hit is UNKNOWN and REDACTED — never promoted. */
            val UNRESOLVED = Resolved(false, null, null, false, false,
                PositionTreatment.REDACTED, 0, 0, 0, 0)
        }
    }

    /** The app's own content root. Nothing outside it is ever traversed. */
    private fun contentRoot(activity: Activity): View? =
        activity.findViewById(android.R.id.content)

    /**
 * Resolve the deepest VISIBLE view containing the window-space point.
 *
 * Children are examined in REVERSE order because the last child is drawn on
 * top; taking the first match would resolve to whatever happens to be
 * underneath an overlay, which is both wrong and a privacy hazard when the
 * overlay is the secure one.
 */
    fun resolve(activity: Activity, windowX: Float, windowY: Float): Resolved {
        val root = contentRoot(activity) ?: return Resolved.UNRESOLVED
        val path = ArrayList<View>()
        val hit = deepest(root, windowX, windowY, path) ?: return Resolved.UNRESOLVED
        val facts = RnHierarchySnapshotter.classifyView(hit)
        val loc = IntArray(2)
        hit.getLocationInWindow(loc)

        // THE DECLARATION IS READ FROM THIS EXACT NODE. No ancestor is
        // consulted, and the string is converted to an enum here so it cannot
        // travel further.
        // THE SAME RESOLUTION THE WALKER PERFORMS, not the raw platform fact.
        // `classifyView` returns only the PLATFORM treatment, which is null for
        // an ordinary View — the MASKED default is applied by `RawNode.resolved`
        // as `strongest(jsTreatment = MASKED, native)`. Passing the raw null
        // here made an interaction see "unknown content" where the hierarchy
        // sees "masked", breaking the one rule this shared classifier exists to
        // guarantee: same view + same state => same content Treatment.
        val resolvedContent = RawNode(
            componentType = "", siblingOrdinal = 0,
            jsTreatment = Treatment.MASKED, nativeTreatment = facts.nativeTreatment,
        ).resolved

        val declared = PositionDeclaration.isDeclared(nativeIdOf(hit))
        val position = PositionTreatment.resolve(
            declaredOnThisNode = declared,
            contentTreatment = resolvedContent,
        )

        return Resolved(
            found = true,
            role = RnHierarchySnapshotter.componentType(hit),
            contentTreatment = resolvedContent,
            editable = facts.editable || hit is EditText,
            secure = facts.secure,
            positionTreatment = position,
            left = loc[0], top = loc[1], width = hit.width, height = hit.height,
            // Decided over the DESCENT path the hit test already walked (root
            // to hit), never by climbing from the hit: there is deliberately no
            // upward walk in this file.
            insideWebContent = path.any { RnWebViewAttach.isWebContent(it) },
        )
    }

    /**
 * The deepest visible view under (x, y). [path] receives the views from
 * [view] down to the returned hit, in descent order, so a caller
 * can ask a question of the hit's containment without walking upward.
 */
    private fun deepest(view: View, x: Float, y: Float, path: MutableList<View>): View? {
        if (view.visibility != View.VISIBLE || view.alpha < 0.01f) return null
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val l = loc[0]; val t = loc[1]
        if (x < l || y < t || x > l + view.width || y > t + view.height) return null
        path.add(view)
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val c = deepest(view.getChildAt(i), x, y, path) ?: continue
                return c
            }
        }
        return view
    }

    /**
 * RN's dedicated keyed tag for `nativeID`.
 *
 * RESOLVED BY NAME, NOT BY A HARDCODED ID. `R.id.view_tag_native_id`
 * belongs to ReactAndroid and its numeric value is not stable across RN
 * versions; baking one in would silently read the wrong tag after an
 * upgrade — and reading the wrong tag would silently stop authorising
 * anything, which is the safe direction but an invisible failure.
 */
    private var nativeIdTag: Int = 0
    private var nativeIdTagResolved = false

    private fun nativeIdOf(view: View): String? {
        if (!nativeIdTagResolved) {
            nativeIdTagResolved = true
            nativeIdTag = view.resources.getIdentifier(
                "view_tag_native_id", "id", view.context.packageName)
        }
        if (nativeIdTag == 0) return null
        return view.getTag(nativeIdTag) as? String
    }

    /**
 * Does ANY editable control in this window currently hold focus?
 *
 * THIS IS THE ANTI-KEYLOGGING SIGNAL and it is deliberately window-wide,
 * not target-local: while a text field owns focus, an in-app custom
 * keyboard's keys are ordinary safe views in the same window, and recording
 * taps on them would be recording a key sequence with timing.
 */
    fun anyEditableFocused(activity: Activity): Boolean {
        // `currentFocus` ALONE IS DEFEATED BY THE TAP ITSELF. Android moves
        // focus on ACTION_DOWN, and React Native marks Pressables focusable, so
        // tapping any button while a text field is active reassigns focus
        // BEFORE either snapshot is taken — the signal read false at DOWN and
        // at UP, and an authorised control tapped mid-typing produced
        // `emit_positional`. Measured on device; it is not a hypothetical.
        //
        // `isAcceptingText` asks the input-method manager whether an input
        // connection is live, which survives a tap on a non-input view. The two
        // are OR'd because either is sufficient and neither is complete:
        // `currentFocus` catches a focused field before the IME has opened, and
        // `isAcceptingText` catches an active session after focus has moved.
        val f = activity.window?.currentFocus
        if (f is EditText) return true
        return runCatching {
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.isAcceptingText == true
        }.getOrDefault(false)
    }
}

package com.tracescout.mobile.capture

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.tracescout.mobile.privacy.Bounds
import com.tracescout.mobile.privacy.EmittedNode
import com.tracescout.mobile.privacy.InheritedPolicy
import com.tracescout.mobile.privacy.MaskEngine
import com.tracescout.mobile.privacy.PolicyContext
import com.tracescout.mobile.privacy.PolicyNode
import com.tracescout.mobile.privacy.PrivacyPolicy
import com.tracescout.mobile.privacy.Treatment

/**
 * Native-core the specification ViewHierarchySnapshotter — the Android Views implementation.
 *
 * THE ORDER HERE IS THE WHOLE SAFETY STORY. Every node goes
 * classify → PrivacyPolicy.resolve → MaskEngine.emit, and only the EMITTED node
 * is ever appended to the tree. A raw `TextView.text` never reaches a buffer,
 * not even transiently — masking is not a rendering step applied later.
 *
 * The walk carries [InheritedPolicy] DOWNWARD for the RAISING flags only
 * (neverRecord, block). `allow` is carried too, but `PrivacyPolicy` deliberately
 * ignores an inherited allow for precedence 1/2 — which is the rule a naive
 * inherit-downward walk breaks, and why the fixture app exists.
 */
object ViewHierarchySnapshotter {

    /**
 * How long the post-truncation accounting sweep may run beyond the capture
 * budget. 2ms measured comfortably over the real cost for any tree a device
 * can actually lay out, while capping total UI-thread occupancy.
 */
    private const val ACCOUNTING_GRACE_NANOS = 2L * 1_000_000

    /** Host-app opt-in tags, matching the browser SDK's `ts-mask` family. */
    const val TAG_MASK = "ts-mask"
    const val TAG_BLOCK = "ts-block"
    const val TAG_ALLOW = "ts-allow"
    const val TAG_NEVER_RECORD = "ts-never-record"

    /** Per-snapshot bounds so a runaway tree cannot occupy the UI thread. */
    data class Budget(
        val maxNodes: Int = 3_000,
        val maxDepth: Int = 60,
        val maxMillis: Long = 8,
    )

    data class Snapshot(
        val nodes: List<EmittedNode>,
        val truncated: Boolean,
        val truncationReason: String?,
        val droppedNodes: Int,
        val elapsedMillis: Long,
        /**
 * Time until CAPTURE stopped. [elapsedMillis] additionally includes the
 * accounting sweep that counts un-walked nodes for the `dropped` field.
 * Separated because the 8ms gate is about how long the SDK occupies the
 * UI thread doing CAPTURE, and conflating the two hid which one was
 * actually over budget.
 */
        val captureMillis: Long,
        /**
 * False when [droppedNodes] is a LOWER BOUND rather than an exact count.
 *
 * Counting un-walked nodes is itself O(n) — on a 4000-view tree it cost
 * ~3ms of UI thread AFTER capture had already stopped at its budget, so
 * the SDK was occupying the main thread past its own limit to produce a
 * more precise number for a field nobody needs precisely. The accounting
 * is bounded too; when the bound is hit the remainder is reported as a
 * true lower bound and this flag says so, rather than a smaller number
 * that reads as exact.
 */
        val droppedExact: Boolean,
    )

    /**
 * Walk a view tree into emitted (already-masked) nodes.
 *
 * Exceeding any budget TRUNCATES and reports — it never blocks the app and
 * never emits a partial tree without saying so. `droppedNodes` feeds the
 * envelope's `dropped` counters, because an undisclosed drop makes the
 * backend treat the session as `incomplete` and refuse to share it.
 */
    @JvmStatic
    fun snapshot(
        root: View,
        classDefaults: Map<String, Treatment> = defaultClassDefaults(),
        budget: Budget = Budget(),
        nowNanos: () -> Long = System::nanoTime,
    ): Snapshot {
        val out = ArrayList<EmittedNode>()
        val start = nowNanos()
        var dropped = 0
        var truncationReason: String? = null
        var captureEndNanos = 0L
        var droppedExact = true

        fun elapsedMs() = (nowNanos() - start) / 1_000_000
        // Compared in NANOS: integer-millisecond granularity meant
        // `elapsedMs > 8` could not trip until 9ms had already elapsed, so the
        // budget was structurally unreachable by a whole millisecond.
        val budgetNanos = budget.maxMillis * 1_000_000
        fun overBudget() = (nowNanos() - start) >= budgetNanos

        // Counting what we DIDN'T walk, without walking it. Once the budget is
        // blown, descending further just to increment a counter costs real time
        // measured at 11ms against an 8ms budget on API 26, i.e. the budget
        // was exceeded by the act of accounting for exceeding it. Subtree sizes
        // are computed arithmetically instead, so truncation is O(1) per node
        // skipped rather than a full traversal.
        // The accounting sweep gets its OWN bound. Past it, a ViewGroup
        // contributes `1 + childCount` — a true lower bound, no descent — and
        // the result is flagged inexact.
        val accountingDeadline = budgetNanos + ACCOUNTING_GRACE_NANOS
        fun subtreeSize(view: View): Int {
            if (view !is ViewGroup) return 1
            if ((nowNanos() - start) >= accountingDeadline) {
                droppedExact = false
                return 1 + view.childCount
            }
            var n = 1
            val count = view.childCount
            for (i in 0 until count) {
                // The deadline must be re-checked INSIDE the loop, not only on
                // entry. A WIDE flat tree -- one ViewGroup with thousands of
                // leaf children -- never re-enters this function (a leaf returns
                // immediately), so an entry-only check let exactly the shape
                // that costs the most run entirely unbounded. Measured on API 35:
                // 3233 leaves swept for ~3ms with the bound never consulted.
                // Checked every 64 children so the clock read is amortised.
                if ((i and 63) == 0 && (nowNanos() - start) >= accountingDeadline) {
                    droppedExact = false
                    return n + (count - i)
                }
                n += subtreeSize(view.getChildAt(i))
            }
            return n
        }

        fun stopCapture(reason: String) {
            if (truncationReason == null) {
                truncationReason = reason
                captureEndNanos = nowNanos()
            }
        }

        fun walk(view: View, inherited: InheritedPolicy, depth: Int, parentId: Long?) {
            if (out.size >= budget.maxNodes) { stopCapture("node_budget"); dropped += subtreeSize(view); return }
            if (depth > budget.maxDepth) { stopCapture("depth_budget"); dropped += subtreeSize(view); return }
            if (overBudget()) { stopCapture("time_budget"); dropped += subtreeSize(view); return }

            val node = classify(view)
            val treatment = PrivacyPolicy.resolve(
                node,
                PolicyContext(inherited = inherited, classDefaults = classDefaults),
            )
            // ONLY the emitted (masked) node is retained. `node.text` dies here.
            out.add(MaskEngine.emit(node, treatment, parentId))

            // A subtree under a blocked/never-record parent is not walked at all:
            // its children cannot become MORE visible than their parent, so
            // walking them can only cost budget and risk a mistake.
            if (treatment == Treatment.BLOCKED || treatment == Treatment.NEVER_RECORD) return

            // A Compose root has no child Views — its content lives in the
            // semantics tree. Without this hand-off the walk stops here and the
            // whole Compose subtree collapses into the single opaque node above,
            // which is how `Modifier.tsPrivacy(.)` came to have no runtime
            // effect at all: the resolution existed, nothing invoked it.
            //
            // The delegated nodes are re-parented onto this node so the flat
            // pre-order list still rebuilds into one tree, and the inherited
            // flags carry across the boundary exactly as they do between Views —
            // a Compose subtree under a blocked ancestor stays blocked.
            if (ComposeSemanticsBridge.isComposeRoot(view)) {
                val d = ComposeSemanticsBridge.descriptorsFor(view, budget.maxNodes - out.size)
                if (d != null) {
                    val composeInherited = InheritedPolicy(
                        neverRecord = inherited.neverRecord || node.neverRecord,
                        block = inherited.block || node.block,
                        allow = inherited.allow || node.allow,
                    )
                    val emitted = ComposeSemanticsSnapshotter.snapshot(
                        rootId = d.rootId,
                        byId = d.byId,
                        classDefaults = classDefaults,
                        inherited = composeInherited,
                    )
                    for (e in emitted) {
                        if (out.size >= budget.maxNodes) { stopCapture("node_budget"); break }
                        out.add(if (e.nodeId == d.rootId) e.copy(parentId = node.nodeId) else e)
                    }
                }
                // Either way there are no child Views to walk.
                return
            }

            if (view is ViewGroup) {
                val childInherited = InheritedPolicy(
                    neverRecord = inherited.neverRecord || node.neverRecord,
                    block = inherited.block || node.block,
                    allow = inherited.allow || node.allow,
                )
                for (i in 0 until view.childCount) {
                    // Stop descending the moment the budget blows; the remaining
                    // siblings are counted, not visited.
                    if (truncationReason != null) {
                        for (j in i until view.childCount) dropped += subtreeSize(view.getChildAt(j))
                        break
                    }
                    walk(view.getChildAt(i), childInherited, depth + 1, node.nodeId)
                }
            }
        }

        walk(root, InheritedPolicy(), 0, null)
        if (captureEndNanos == 0L) captureEndNanos = nowNanos()

        return Snapshot(
            nodes = out,
            truncated = truncationReason != null,
            truncationReason = truncationReason,
            droppedNodes = dropped,
            elapsedMillis = elapsedMs(),
            captureMillis = (captureEndNanos - start) / 1_000_000,
            droppedExact = droppedExact,
        )
    }

    /**
 * Turn a platform View into the policy's platform-neutral node.
 *
 * `platformSecure` is the highest-precedence signal and is derived from the
 * PLATFORM, never from a host-app hint: a password `inputType` or a window
 * carrying `FLAG_SECURE`. An app cannot opt out of it.
 */
    @JvmStatic
    fun classify(view: View): PolicyNode {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val tag = view.tag as? String

        return PolicyNode(
            nodeId = view.id.toLong().takeIf { it != View.NO_ID.toLong() } ?: idFor(view),
            type = typeOf(view),
            platformSecure = isPlatformSecure(view),
            neverRecord = tag == TAG_NEVER_RECORD,
            block = tag == TAG_BLOCK,
            // A `ts-mask` tag does NOT set `allow`; it simply declines the
            // allow, leaving the global masked default to apply.
            allow = tag == TAG_ALLOW,
            text = (view as? TextView)?.text?.toString(),
            bounds = Bounds(loc[0], loc[1], view.width, view.height),
            blockedReason = when {
                isPlatformSecure(view) -> "secure_flag"
                tag == TAG_BLOCK -> "app_blocked"
                else -> null
            },
        )
    }

    private fun isPlatformSecure(view: View): Boolean {
        // 1. A password/PIN input, by its INPUT TYPE — the platform's own
        // declaration that the content is a credential.
        val et = view as? EditText
        if (et != null) {
            val variation = et.inputType and InputType.TYPE_MASK_VARIATION
            val cls = et.inputType and InputType.TYPE_MASK_CLASS
            val isTextPassword = cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )
            val isNumberPassword = cls == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            if (isTextPassword || isNumberPassword) return true
        }
        // 2. A window the app marked FLAG_SECURE. Reading this is best-effort —
        // a failure resolves SECURE, not insecure.
        return try {
            val params = view.rootView?.layoutParams as? WindowManager.LayoutParams
            params != null && (params.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        } catch (t: Throwable) {
            true
        }
    }

    private fun typeOf(view: View): String = when (view) {
        is EditText -> "input"
        is TextView -> "text"
        is ImageView -> "image"
        is ViewGroup -> "container"
        else -> "view"
    }

    /** Stable within a snapshot; identity hash, never a device identifier. */
    private fun idFor(view: View): Long = System.identityHashCode(view).toLong()

    /**
 * Defaults chosen so the GLOBAL default stays `masked`: containers and
 * decorative views carry no content, so allowing them costs nothing and
 * keeps the replay's structure legible.
 */
    @JvmStatic
    fun defaultClassDefaults(): Map<String, Treatment> = mapOf(
        "container" to Treatment.ALLOWED,
        "view" to Treatment.ALLOWED,
        // text / input / image deliberately ABSENT — they fall through to the
        // masked global default.
    )
}

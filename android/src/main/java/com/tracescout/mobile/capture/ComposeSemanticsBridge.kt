package com.tracescout.mobile.capture

import android.view.View
import com.tracescout.mobile.privacy.Bounds

/**
 * Turns a live Jetpack Compose root into the [ComposeSemanticsSnapshotter.SemanticsDescriptor]
 * map that the snapshotter consumes.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * [ComposeSemanticsSnapshotter] already contained the entire Compose privacy
 * resolution — including `allow = privacyIntent == ALLOWED` — and it was
 * covered by tests. It had **no production caller**. `snapshot` is a pure
 * function over descriptors, and nothing ever built descriptors from a running
 * app, so `Modifier.tsPrivacy(.)` wrote a semantics property that nothing
 * read.
 *
 * That was measured, not suspected: on one binary, in one session, under one
 * posture, a `ts-allow` View kept its public label while the equivalent
 * `ComposePrivacy.ALLOWED` composable did not. The Views walker has no Compose
 * awareness at all, so a Compose subtree fell through to default masking.
 *
 * It failed SAFE — Compose text was masked, never leaked — but the direction
 * that matters is the other one: an app marking a field `BLOCKED` or
 * `NEVER_RECORD` received *masking*, a weaker treatment than it asked for and
 * than we document. A privacy control that silently degrades is worse than one
 * that does not exist, because the app author stops looking.
 *
 * The defect class is worth naming, because this is its third appearance in
 * this program: **a documented mechanism that is structurally unreachable,
 * guarded by a test that invokes it directly.** Such a test cannot fail. The
 * guard has to assert the CALLER exists — see `ComposeSemanticsBridgeTest`.
 *
 * HOW IT REACHES THE TREE
 * -----------------------
 * Through `androidx.compose.ui.node.RootForTest`, which every Compose root
 * implements and which exposes `semanticsOwner`. That is public API and is the
 * same door the official UI-test framework uses. `AndroidComposeView` itself is
 * internal and deliberately not referenced.
 *
 * Compose is a `compileOnly` dependency: an app that does not use Compose must
 * not be forced to ship it. So every entry point here is guarded against
 * `NoClassDefFoundError`/`LinkageError` and degrades to "no Compose here",
 * which returns the walker to exactly its previous behaviour.
 *
 * MERGED vs UNMERGED
 * ------------------
 * The **unmerged** tree is used. The merged tree collapses a composable and its
 * descendants into one node, which would fuse a masked child into an allowed
 * parent and silently publish it. Privacy resolution has to see the same
 * granularity the app author declared its intent at.
 */
internal object ComposeSemanticsBridge {

    /** Root id plus every descriptor reachable from it. */
    data class Descriptors(
        val rootId: Long,
        val byId: Map<Long, ComposeSemanticsSnapshotter.SemanticsDescriptor>,
    )

    /**
 * True when this view is a Compose root. Cheap, and safe on a device where
 * Compose is absent entirely.
 */
    fun isComposeRoot(view: View): Boolean = try {
        view is androidx.compose.ui.node.RootForTest
    } catch (t: Throwable) {
        // NoClassDefFoundError on a Compose-free app. Not an error condition.
        false
    }

    /**
 * Build descriptors for a Compose root, or null if this is not one / the
 * tree could not be read.
 *
 * Never throws: a capture problem must degrade the snapshot, never fail the
 * session. A null return puts the walker back on its original path, where
 * the Compose root is emitted as a single opaque node under default
 * (strict) treatment — the safe outcome, not a leak.
 */
    fun descriptorsFor(view: View, maxNodes: Int = 3_000): Descriptors? = try {
        val root = (view as? androidx.compose.ui.node.RootForTest)
            ?.semanticsOwner
            ?.unmergedRootSemanticsNode
        if (root == null) null else collect(root, maxNodes)
    } catch (t: Throwable) {
        null
    }

    private fun collect(
        root: androidx.compose.ui.semantics.SemanticsNode,
        maxNodes: Int,
    ): Descriptors {
        val byId = HashMap<Long, ComposeSemanticsSnapshotter.SemanticsDescriptor>()
        val stack = ArrayDeque<androidx.compose.ui.semantics.SemanticsNode>()
        stack.addLast(root)
        while (stack.isNotEmpty() && byId.size < maxNodes) {
            val n = stack.removeLast()
            val children = try { n.children } catch (t: Throwable) { emptyList() }
            // A child beyond the budget must not be referenced by its parent —
            // a descriptor map with a dangling child id would make the
            // snapshotter's walk silently drop the rest of the subtree while
            // reporting a complete tree.
            val room = maxNodes - byId.size - 1
            val kept = if (children.size > room && room >= 0) children.take(room) else children
            byId[n.id.toLong()] = describe(n, kept)
            for (c in kept) stack.addLast(c)
        }
        return Descriptors(rootId = root.id.toLong(), byId = byId)
    }

    private fun describe(
        n: androidx.compose.ui.semantics.SemanticsNode,
        children: List<androidx.compose.ui.semantics.SemanticsNode>,
    ): ComposeSemanticsSnapshotter.SemanticsDescriptor {
        val cfg = n.config
        fun <T> get(key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>): T? =
            try { cfg.getOrElseNullable(key) { null } } catch (t: Throwable) { null }

        val text = buildString {
            get(androidx.compose.ui.semantics.SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
                ?.let { append(it) }
            get(androidx.compose.ui.semantics.SemanticsProperties.EditableText)
                ?.text
                ?.let { if (isNotEmpty()) append(' '); append(it) }
        }.ifEmpty { null }

        // Compose's OWN password declaration — a PLATFORM fact, never a host
        // hint, so an app cannot opt out of it. Precedence 1 in the policy.
        val isPassword = try {
            cfg.contains(androidx.compose.ui.semantics.SemanticsProperties.Password)
        } catch (t: Throwable) {
            false
        }

        val b = try { n.boundsInWindow } catch (t: Throwable) { null }

        return ComposeSemanticsSnapshotter.SemanticsDescriptor(
            id = n.id.toLong(),
            testTag = get(androidx.compose.ui.semantics.SemanticsProperties.TestTag),
            privacyIntent = get(ComposePrivacy.Key),
            text = text,
            isPassword = isPassword,
            isContainer = children.isNotEmpty(),
            bounds = if (b == null) Bounds(0, 0, 0, 0) else Bounds(
                x = b.left.toInt(), y = b.top.toInt(),
                w = (b.right - b.left).toInt(), h = (b.bottom - b.top).toInt(),
            ),
            childIds = children.map { it.id.toLong() },
        )
    }
}

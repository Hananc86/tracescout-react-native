package com.tracescout.mobile.capture

import com.tracescout.mobile.privacy.Bounds
import com.tracescout.mobile.privacy.EmittedNode
import com.tracescout.mobile.privacy.InheritedPolicy
import com.tracescout.mobile.privacy.MaskEngine
import com.tracescout.mobile.privacy.PolicyContext
import com.tracescout.mobile.privacy.PolicyNode
import com.tracescout.mobile.privacy.PrivacyPolicy
import com.tracescout.mobile.privacy.Treatment

/**
 * Native-core the specification for Compose.
 *
 * Compose renders its ENTIRE tree into a single `AndroidComposeView`, so
 * [ViewHierarchySnapshotter] sees one opaque node and nothing inside it. A
 * masking rule that holds in Views can therefore be COMPLETELY ABSENT in
 * Compose — which is why the mandate requires the privacy matrix proven in
 * both, and why this is a separate snapshotter rather than a special case.
 *
 * The platform adapter (which needs the Compose runtime) walks the semantics
 * tree and produces [SemanticsDescriptor]s; THIS class owns the policy. That
 * split keeps the privacy decision in one place for Views and Compose alike —
 * two copies of a precedence rule is two chances to get it wrong.
 */
object ComposeSemanticsSnapshotter {

    // Privacy intent arrives via the DEDICATED semantics key in [ComposePrivacy],
    // never via `testTag` — see the rationale there. `testTag` is carried on the
    // descriptor for diagnostics only and is NEVER read as a privacy directive.

    /**
 * A platform-neutral view of one semantics node.
 *
 * `isPassword` comes from Compose's OWN password semantics — the
 * `Password` semantics property or a password keyboard type. It is a
 * PLATFORM declaration, never a host-app hint, so an app cannot opt out
 * of it.
 */
    data class SemanticsDescriptor(
        val id: Long,
        /** Diagnostics only. NEVER interpreted as privacy intent. */
        val testTag: String?,
        /** Host-app intent from the dedicated semantics key, if declared. */
        val privacyIntent: String?,
        val text: String?,
        val isPassword: Boolean,
        val isContainer: Boolean,
        val bounds: Bounds,
        val childIds: List<Long> = emptyList(),
    )

    /**
 * Apply the policy over a semantics tree.
 *
 * [byId] must contain every descriptor reachable from [rootId]; the walk
 * carries the raising flags downward exactly as the Views walk does.
 */
    @JvmStatic
    fun snapshot(
        rootId: Long,
        byId: Map<Long, SemanticsDescriptor>,
        classDefaults: Map<String, Treatment> = defaultClassDefaults(),
        /**
 * What the enclosing VIEW hierarchy already asserted. A Compose root
 * nested under a blocked or never-record View must not become more
 * visible by crossing the boundary; defaulting this to an empty policy
 * would silently launder the subtree.
 */
        inherited: InheritedPolicy = InheritedPolicy(),
    ): List<EmittedNode> {
        val out = ArrayList<EmittedNode>()

        fun walk(id: Long, inherited: InheritedPolicy) {
            val d = byId[id] ?: return
            val node = toPolicyNode(d)
            val treatment = PrivacyPolicy.resolve(
                node,
                PolicyContext(inherited = inherited, classDefaults = classDefaults),
            )
            out.add(MaskEngine.emit(node, treatment))

            // Same rule as Views: a subtree under a blocked/never-record parent
            // cannot become more visible than its parent, so it is not walked.
            if (treatment == Treatment.BLOCKED || treatment == Treatment.NEVER_RECORD) return

            val childInherited = InheritedPolicy(
                neverRecord = inherited.neverRecord || node.neverRecord,
                block = inherited.block || node.block,
                allow = inherited.allow || node.allow,
            )
            for (child in d.childIds) walk(child, childInherited)
        }

        walk(rootId, inherited)
        return out
    }

    @JvmStatic
    fun toPolicyNode(d: SemanticsDescriptor): PolicyNode = PolicyNode(
        nodeId = d.id,
        type = when {
            d.isPassword -> "input"
            d.isContainer -> "container"
            d.text != null -> "text"
            else -> "view"
        },
        platformSecure = d.isPassword,
        neverRecord = d.privacyIntent == ComposePrivacy.NEVER_RECORD,
        block = d.privacyIntent == ComposePrivacy.BLOCKED,
        allow = d.privacyIntent == ComposePrivacy.ALLOWED,
        text = d.text,
        bounds = d.bounds,
        blockedReason = when {
            d.isPassword -> "secure_flag"
            d.privacyIntent == ComposePrivacy.BLOCKED -> "app_blocked"
            else -> null
        },
    )

    /** Identical to the Views defaults — one policy, two adapters. */
    @JvmStatic
    fun defaultClassDefaults(): Map<String, Treatment> =
        ViewHierarchySnapshotter.defaultClassDefaults()
}

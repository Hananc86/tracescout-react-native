package com.tracescout.mobile.privacy

/**
 * Native-core the specification PrivacyPolicy, the specification MaskEngine, the specification PostureResolver.
 *
 * This is the Kotlin implementation of the SHARED contract in
 * `mobile-native-core/SPEC.md`. It is validated against the SAME golden
 * fixtures the JavaScript reference runs — see `NativeCoreConformanceTest`.
 * If this file and the fixtures ever disagree, this file is wrong.
 *
 * NOTHING HERE MAY BE "IMPROVED" FOR KOTLIN ERGONOMICS. The contract is shared
 * across four bindings; local awkwardness is local.
 */

enum class Treatment(val wire: String) {
    ALLOWED("allowed"),
    MASKED("masked"),
    BLOCKED("blocked"),
    NEVER_RECORD("never_record");

    companion object {
        fun fromWire(s: String?): Treatment? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * Privacy levels, ordered WORST FIRST.
 *
 * The ordinal order is load-bearing for [worstPosture], but the ordinal is
 * NEVER serialised — only [wire] crosses a boundary. An ordinal is a promise
 * about declaration order that a future insertion silently breaks.
 */
enum class PrivacyLevel(val wire: String) {
    UNKNOWN("unknown"),
    RELAXED("relaxed"),
    STRICT("strict");

    companion object {
        /** An unrecognised level is UNKNOWN — the worst — never a default of STRICT. */
        fun fromWire(s: String?): PrivacyLevel = entries.firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

/** A node as the policy sees it. Platform-agnostic so the fixtures can drive it. */
data class PolicyNode(
    val nodeId: Long,
    val type: String,
    val platformSecure: Boolean = false,
    val neverRecord: Boolean = false,
    val block: Boolean = false,
    val allow: Boolean = false,
    val text: String? = null,
    val bounds: Bounds? = null,
    val blockedReason: String? = null,
)

data class Bounds(val x: Int, val y: Int, val w: Int, val h: Int)

/** What an ANCESTOR asserted. Only the raising flags inherit; `allow` never lowers. */
data class InheritedPolicy(
    val neverRecord: Boolean = false,
    val block: Boolean = false,
    val allow: Boolean = false,
)

data class PolicyContext(
    val inherited: InheritedPolicy = InheritedPolicy(),
    val classDefaults: Map<String, Treatment> = emptyMap(),
)

object PrivacyPolicy {

    /**
 * Resolve one node's treatment. Precedence is ABSOLUTE and ordered:
 *
 * 1. platform-secure surface
 * 2. explicit host neverRecord (this node OR any ancestor)
 * 3. explicit host block (this node OR any ancestor)
 * 4. explicit allow on THIS EXACT node
 * 5. class/type default
 * 6. global default — MUST be masked
 *
 * The load-bearing property: 1 and 2 cannot be lowered by an ancestor's
 * `allow`, by a class default, or by remote config. A relaxed parent must
 * never unmask a secure child — which is exactly what a naive
 * inherit-downward walk gets wrong.
 */
    @JvmStatic
    fun resolve(node: PolicyNode, ctx: PolicyContext = PolicyContext()): Treatment = try {
        when {
            node.platformSecure -> Treatment.NEVER_RECORD
            node.neverRecord || ctx.inherited.neverRecord -> Treatment.NEVER_RECORD
            node.block || ctx.inherited.block -> Treatment.BLOCKED
            node.allow -> Treatment.ALLOWED
            ctx.classDefaults.containsKey(node.type) -> ctx.classDefaults.getValue(node.type)
            else -> Treatment.MASKED
        }
    } catch (t: Throwable) {
        // Failing to a MORE private state is the only acceptable direction.
        Treatment.BLOCKED
    }
}

/**
 * The emitted node. `text` is a nullable field that is OMITTED from
 * serialisation when null — the absence of a masked value has to be provable
 * from the serialised form, so it is never blanked and never nulled-in-place.
 */
data class EmittedNode(
    val nodeId: Long,
    /**
 * The walk emits a FLAT pre-order list; `parentId` is what lets the wire
 * layer rebuild the tree without a second traversal of live Views (which
 * would be both slower and racy — the view tree can change between passes).
 * Null for the root only.
 */
    val parentId: Long? = null,
    val type: String,
    val bounds: Bounds?,
    val privacy: String,
    val text: String? = null,
    val textMasked: Boolean? = null,
    val textLength: Int? = null,
    val blockedReason: String? = null,
)

object MaskEngine {

    @JvmStatic
    @JvmOverloads
    fun emit(node: PolicyNode, treatment: Treatment, parentId: Long? = null): EmittedNode {
        val base = EmittedNode(
            nodeId = node.nodeId,
            parentId = parentId,
            type = node.type,
            bounds = node.bounds,
            privacy = treatment.wire,
        )
        return when (treatment) {
            Treatment.ALLOWED -> base.copy(text = node.text)
            Treatment.MASKED -> base.copy(
                textMasked = true,
                // Character count, not byte count — the redaction bar is sized
                // in glyphs. Surrogate pairs count once.
                textLength = node.text?.codePointCount(0, node.text.length) ?: 0,
            )
            // Blocked / never-record keep their TRUE bounds so the replay shows a
            // correctly sized redacted region rather than a hole in the layout.
            Treatment.BLOCKED -> base.copy(blockedReason = node.blockedReason ?: "app_blocked")
            Treatment.NEVER_RECORD -> base.copy(blockedReason = node.blockedReason ?: "platform_secure")
        }
    }
}

/** One segment's asserted posture. Absent booleans are false, never assumed true. */
data class Posture(
    val level: PrivacyLevel = PrivacyLevel.UNKNOWN,
    val maskText: Boolean = false,
    val maskInputs: Boolean = false,
    val maskAccessibilityLabels: Boolean = false,
    val blockImages: Boolean = false,
    val blockCameraSurfaces: Boolean = false,
)

/**
 * Reduce many per-surface postures to one session posture.
 *
 * Worst level wins. A boolean protection holds ONLY if every segment asserted
 * it. An EMPTY set resolves to UNKNOWN — never STRICT: absence of evidence is
 * not evidence of masking, and UNKNOWN is what blocks sharing.
 */
fun worstPosture(postures: List<Posture>): Posture {
    if (postures.isEmpty()) return Posture(level = PrivacyLevel.UNKNOWN)
    return Posture(
        level = postures.minBy { it.level.ordinal }.level,
        maskText = postures.all { it.maskText },
        maskInputs = postures.all { it.maskInputs },
        maskAccessibilityLabels = postures.all { it.maskAccessibilityLabels },
        blockImages = postures.all { it.blockImages },
        blockCameraSurfaces = postures.all { it.blockCameraSurfaces },
    )
}

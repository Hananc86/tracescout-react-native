package com.tracescout.rn

/**
 * THE PRODUCTION BIND: NATIVE SESSION + HOST-DECLARED POLICY -> correlation —
 * the Kotlin twin of Swift's `RnWebViewBinding`.
 *
 * This is the one place the two native inputs meet. The shared decision
 * ([RnWebViewSegment.decide]) already fails closed on a null policy, so a
 * bridge installed without one could never admit anything — but a bridge that
 * CANNOT ADMIT is not harmless. It is a message listener and a document-start
 * script in every qualifying web view, doing nothing, with no refusal anybody
 * sees. So the decision is taken BEFORE the attachment, and a session that
 * cannot possibly correlate installs nothing at all and says why.
 *
 * ## NO JS PATH, AND IT IS A STRUCTURAL CONSTRAINT RATHER THAN A PREFERENCE
 *
 * An origin allowlist is the definition of a native-owned fact: a policy a
 * page's own runtime could supply would be a policy the page could widen. The
 * React-facing surface is a fixed set of semantic methods and nothing here is
 * reachable from it — the whole module is `kotlin("jvm")` with no
 * `com.facebook.react` dependency at all, so a JS-callable entry point is
 * unrepresentable from this file.
 */
object RnWebViewBinding {

    /**
 * The pure decision. Separated from the construction so every branch is
 * provable in a plain JVM test with no Android, no WebView and no
 * Keystore.
 */
    sealed class Admission {
        /** A bridge may be bound, with this reviewed allowlist. */
        data class Admit(val origins: List<String>) : Admission()

        /** No bridge may be bound. Carries the typed reason. */
        data class Refuse(val why: String) : Admission()
    }

    /**
 * THE REFUSAL ORDER IS [RnWebViewSegment.decide]'S OWN ORDER, COPIED
 * DELIBERATELY RATHER THAN RE-DERIVED: capability -> session -> origin.
 *
 * Asking the policy first would report a configuration problem on a device
 * whose web engine cannot carry a bridge at all — naming the operator's
 * allowlist where the ENGINE is the fact. Two sites that answer the same
 * questions in different orders give the same operator two different
 * stories about one device, and on Android the engine really is the common
 * case: Android System WebView is a separately updateable component, and
 * the API-26 emulator image ships WebView 69.
 */
    @JvmStatic
    fun decide(
        nativeSessionId: String,
        declared: List<String>?,
        capability: RnWebViewSegment.Capability,
    ): Admission {
        // (1) CAPABILITY. the specification: an unsupported engine yields no segment row at
        // all, so the reason names the engine.
        if (capability != RnWebViewSegment.Capability.SUPPORTED) {
            return Admission.Refuse(capability.wire)
        }

        // (2) OUR OWN IDENTITY, before we mint anything to attach to it. Shares
        // the shared decision's vocabulary: one fact, one code.
        if (!RnWebViewSegment.isValidSessionId(nativeSessionId)) {
            return Admission.Refuse("native_session_invalid")
        }

        // (3) THE HOST'S DECLARATION.
        return when (val d = RnWebViewOriginPolicy.admit(declared)) {
            is RnWebViewOriginPolicy.Declared.Absent ->
                Admission.Refuse("origin_policy_absent")
            is RnWebViewOriginPolicy.Declared.Refused ->
                Admission.Refuse(d.why)
            is RnWebViewOriginPolicy.Declared.Allowlist ->
                // A DELIBERATELY EMPTY ALLOWLIST IS NOT A CONFIGURATION
                // ERROR, AND IT IS STILL NOT A BRIDGE. It is a reviewed
                // decision that no origin may correlate, so the honest outcome
                // is no bridge and a reason that is distinguishable from
                // "nothing was configured".
                if (d.origins.isEmpty()) Admission.Refuse("origin_policy_empty")
                else Admission.Admit(d.origins)
        }
    }

    /**
 * The result of a bind.
 *
 * NOT a `data class` — it carries a reference type whose identity is
 * never meant to be compared, and a generated `equals` on a freshly minted
 * correlation would invite exactly that comparison.
 */
    class Result(
        /** The bound correlation, or null when the bind was refused. */
        val correlation: RnWebViewCorrelation?,
        /** The typed refusal, or null when a correlation was bound. */
        val refusal: String?,
    ) {
        /** Exactly one of the two is always present. */
        val isBound: Boolean get() = correlation != null
    }

    /**
 * Bind one web view's correlation from native state.
 *
 * THE POLICY IS CAPTURED HERE AND THE CORRELATION CARRIES IT. That is
 * the contract's "policy bound to native session": the allowlist that
 * admits a message is the one this session was bound with, so nothing a
 * later caller does can widen the policy a live session is admitting
 * under. Revocation is a separate question with a separate answer — see
 * [RnWebViewCorrelation.revalidate].
 */
    @JvmStatic
    fun bind(
        nativeSessionId: String,
        declared: List<String>?,
        capability: RnWebViewSegment.Capability,
    ): Result = when (val a = decide(nativeSessionId, declared, capability)) {
        is Admission.Refuse -> Result(correlation = null, refusal = a.why)
        is Admission.Admit -> Result(
            correlation = RnWebViewCorrelation(
                nativeSessionId = nativeSessionId,
                policy = a.origins,
                capability = capability,
            ),
            refusal = null,
        )
    }
}

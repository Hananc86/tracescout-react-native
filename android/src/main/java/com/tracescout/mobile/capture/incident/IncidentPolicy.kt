package com.tracescout.mobile.capture.incident

/**
 * MOBILE INCIDENT v1 — the Android NATIVE twin of
 * `mobile-protocol/src/incident-policy.cjs`.
 *
 * Pinned to the SAME vectors as the JS reference, the RN Kotlin twin
 * (`com.tracescout.rn.IncidentPolicy`) and the Swift twin:
 * `mobile-react-native/parity-vectors/incident-policy-vectors.json`. A
 * platform disagreement fails on bytes, in `NativeIncidentPolicyVectorTest`.
 *
 * WHY A TWIN AND NOT A DEPENDENCY — the same reason the sibling
 * `capture/interaction/InteractionPolicy` gives, and it is a hard graph fact
 * rather than a preference: the RN copy lives in `rn-core`, `:rn` depends on
 * `:sdk`, and `:sdk`'s own build file pins `:sdk NEVER depends on rn-core
 *`. A native SDK reaching for an RN artifact would invert
 * that graph and ship an RN library inside the published native AAR. The
 * established pattern for this contract is one implementation per runtime,
 * all pinned to one vector file, so that is what this is.
 *
 * **SCOPE: THE CRASH PATH ONLY.** `hangKindFor` / `hangIsEmittable` /
 * `iosCrashClass` / `boundScreenId` are deliberately ABSENT, not forgotten.
 * The native hang builder already emits its canonical `hangKind`/`blockedMs`
 * (and its own comment records the production run that taught it to), and the
 * native producer does not emit a screen id on an incident. Porting rules this
 * module cannot reach would be unexercised code wearing the authority of a
 * pinned twin — the shape this programme has paid for repeatedly. A later unit
 * that needs one adds it WITH its vector group.
 */
object IncidentPolicy {

    /** `mobile_crash.crashClass` — the closed enum, in schema order. */
    val CRASH_CLASSES: List<String> = listOf(
        "java_uncaught", "kotlin_uncaught", "objc_nsexception", "swift_fatal",
        "mach_exception", "cpp_exception", "ndk_signal", "js_uncaught",
        "dart_uncaught", "oom", "watchdog_termination", "unknown",
    )

    const val MAX_FRAMES = 128
    const val MAX_SYMBOL = 400
    const val MAX_MODULE = 200
    const val MAX_TYPE = 200
    const val MAX_THREAD_NAME = 128

    private const val RN_JS_EXCEPTION = "com.facebook.react.common.JavascriptException"
    private const val RN_JS_NATIVE_MODULE =
        "com.facebook.react.bridge.JSApplicationCausedNativeModuleException"

    /**
 * The classes the KOTLIN runtime alone produces.
 *
 * A GENERIC `NullPointerException` IS NOT EVIDENCE OF KOTLIN. Kotlin
 * compiles down to the same JVM throwables Java raises, so attributing one
 * to the Kotlin runtime would make the crash-class breakdown a function of
 * which exception type the app happened to throw. `kotlin_uncaught` is
 * reserved for the classes where the attribution is a MEASUREMENT.
 */
    private val KOTLIN_ONLY = setOf(
        "kotlin.KotlinNullPointerException",
        "kotlin.NotImplementedError",
        "kotlin.UninitializedPropertyAccessException",
        "kotlin.TypeCastException",
    )

    /**
 * `mobile_crash.crashClass` for an Android throwable class name.
 *
 * MOST SPECIFIC FIRST, AND THE ORDER IS THE CONTRACT. A JS exception is
 * also a Java class, so the React Native wrapper has to be tested BEFORE
 * the generic JVM answer or it can never be reached — and a React Native
 * crash reported as `java_uncaught` would hide the entire class of crash
 * the RN SDK exists to record. The native producer tests it too: the rule
 * is one rule, and a twin that quietly dropped a branch would agree with
 * the vectors only until someone embedded RN.
 */
    fun androidCrashClass(className: String?): String {
        if (className.isNullOrEmpty()) return "unknown"
        if (className == RN_JS_EXCEPTION ||
            className == RN_JS_NATIVE_MODULE ||
            className.startsWith(RN_JS_EXCEPTION)
        ) return "js_uncaught"
        if (className == "java.lang.OutOfMemoryError") return "oom"
        if (className in KOTLIN_ONLY) return "kotlin_uncaught"
        return "java_uncaught"
    }

    /** How many captured frames may be emitted, and how many are dropped. */
    data class FramePlan(val emitted: Int, val dropped: Int)

    fun framePlan(captured: Int): FramePlan {
        val c = if (captured < 0) 0 else captured
        val emitted = if (c > MAX_FRAMES) MAX_FRAMES else c
        return FramePlan(emitted, c - emitted)
    }

    /**
 * BOUNDED IN CODE POINTS, NOT `String.length`.
 *
 * The canonical bound is a JSON Schema `maxLength`, which counts CODE
 * POINTS, while Kotlin's `length` counts UTF-16 units — so a symbol full
 * of astral characters measures up to twice its true length and would be
 * truncated where the contract accepts it. The same four-units-for-one-
 * quantity trap the identity contract paid for.
 */
    private fun bound(s: String?, max: Int): String? {
        // AN EMPTY STRING IS `null`, NOT `""` — the canonical `boundString`
        // returns null for a zero-length input, so a producer that emitted `""`
        // would put an empty `type` or `symbol` on the wire where the contract
        // says the field is ABSENT. My first draft of this twin returned `""`;
        // the shared vectors carry `{"input": "", "expected": null}` for all
        // four bounds, which is the whole reason the twin is pinned to them
        // rather than reviewed by eye.
        if (s.isNullOrEmpty()) return null
        val cps = s.codePointCount(0, s.length)
        if (cps <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max))
    }

    fun boundSymbol(s: String?): String? = bound(s, MAX_SYMBOL)
    fun boundModule(s: String?): String? = bound(s, MAX_MODULE)
    fun boundType(s: String?): String? = bound(s, MAX_TYPE)
    fun boundThreadName(s: String?): String? = bound(s, MAX_THREAD_NAME)
}

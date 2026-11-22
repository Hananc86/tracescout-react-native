package com.tracescout.rn

/**
 * MOBILE INCIDENT v1 — the crash / hang decision, in ONE place.
 *
 * The Kotlin twin of `mobile-protocol/src/incident-policy.cjs`, pinned to
 * `parity-vectors/incident-policy-vectors.json`. A platform disagreement fails
 * on BYTES, not on three people reading one paragraph.
 *
 * THE ORDER OF THE RULES IS THE CONTRACT. A React Native JavaScript
 * exception arrives wrapped in a Java class, so the wrapper has to be tested
 * BEFORE the generic JVM answer or `js_uncaught` can never be reached — and a
 * React Native crash reported as `java_uncaught` would hide the entire class of
 * crash this producer exists to record.
 *
 * THE MESSAGE IS NEVER EMITTED. `mobile_crash.schema.json` declares an
 * optional `message` and this object has no function that produces one. That is
 * inherited, not invented: the Android native producer excludes it for the
 * stated reason that "application code interpolates values into messages, and a
 * crash report is not an exemption from the privacy model", and there is no
 * value-pattern sanitizer in either mobile runtime that could redact one.
 */
object IncidentPolicy {

    /** `mobile_crash.crashClass` — the closed enum, in schema order. */
    val CRASH_CLASSES: List<String> = listOf(
        "java_uncaught",
        "kotlin_uncaught",
        "objc_nsexception",
        "swift_fatal",
        "mach_exception",
        "cpp_exception",
        "ndk_signal",
        "js_uncaught",
        "dart_uncaught",
        "oom",
        "watchdog_termination",
        "unknown",
    )

    /** `mobile_hang.hangKind` — the closed enum, in schema order. */
    val HANG_KINDS: List<String> = listOf("anr", "app_hang", "watchdog_warning", "slow_frame_burst")

    /**
 * The frame cap this producer applies — STRICTLY BELOW the schema's
 * `maxItems: 512`, and deliberately so. It is applied on a thread racing the
 * process's death, so it bounds WORK and not just bytes; and a producer that
 * emitted exactly the schema maximum would be refused by any future
 * tightening of it.
 */
    const val MAX_FRAMES = 128

    /** `frames[].symbol` max length, from the schema. */
    const val MAX_SYMBOL = 400

    /** `frames[].module` max length, from the schema. */
    const val MAX_MODULE = 200

    /**
 * `errorId` max length. The DEPLOYED server rejects past it with
 * `incident_identity_invalid` — a permanent 422 on the whole chunk — so this
 * is a bound the producer must respect rather than discover.
 */
    const val MAX_ERROR_ID = 64

    /** `mobile_crash.type` max length, from the schema. */
    const val MAX_TYPE = 200

    /**
 * `mobile_crash.threadName` max length, from the schema.
 *
 * IT IS 128, NOT 200, AND THIS BUILDER HAD IT WRONG. A thread name of
 * 129–200 characters passed a 200-bound unchanged and reached a field the
 * schema caps at 128 — a schema-invalid crash record, which the deployed
 * server refuses with a PERMANENT 422 on the WHOLE chunk. Found by review,
 * confirmed by reading `mobile_crash.schema.json`; the conformance suite
 * now pins this constant to the schema rather than to a literal.
 */
    const val MAX_THREAD_NAME = 128

    /**
 * Screen identity max length — `envelope.screen.screenId` (crash) and
 * `mobile_hang.screenId` (hang), both 64.
 *
 * NEITHER BUILDER BOUNDED IT AT ALL, so an RN route id past 64 characters
 * produced a schema-invalid record on BOTH event types. Review found the
 * `threadName` case; this one came from reading the schema for EVERY string
 * the two builders emit, which is the only way that question gets a
 * complete answer.
 */
    const val MAX_SCREEN_ID = 64

    /** React Native's own wrappers for a JavaScript exception that reached native. */
    private const val RN_JS_EXCEPTION = "com.facebook.react.common.JavascriptException"
    private const val RN_JS_NATIVE_MODULE =
        "com.facebook.react.bridge.JSApplicationCausedNativeModuleException"

    /**
 * Classes the KOTLIN runtime alone produces. Everything else from a JVM
 * uncaught handler is `java_uncaught`, because the SEAM is the JVM: a plain
 * `NullPointerException` thrown from a Kotlin file is indistinguishable from
 * a Java one, and reporting a guess as `kotlin_uncaught` would make the
 * crash-class breakdown a function of which exception type the app threw.
 */
    private val KOTLIN_ONLY = setOf(
        "kotlin.KotlinNullPointerException",
        "kotlin.NotImplementedError",
        "kotlin.UninitializedPropertyAccessException",
        "kotlin.TypeCastException",
    )

    /** Classify an Android uncaught throwable by its fully-qualified class name. */
    fun androidCrashClass(className: String?): String {
        if (className.isNullOrEmpty()) return "unknown"
        // Most specific first — see the ordering note in the class KDoc.
        if (className == RN_JS_EXCEPTION ||
            className == RN_JS_NATIVE_MODULE ||
            className.startsWith(RN_JS_EXCEPTION)
        ) {
            return "js_uncaught"
        }
        if (className == "java.lang.OutOfMemoryError") return "oom"
        if (className in KOTLIN_ONLY) return "kotlin_uncaught"
        return "java_uncaught"
    }

    /**
 * Classify an iOS incident by the SEAM that observed it.
 *
 * THE SEAM, NOT THE SYMPTOM, AND THAT BOUNDS WHAT iOS CAN CLAIM.
 * `NSSetUncaughtExceptionHandler` observes an `NSException` only; a Swift
 * `fatalError`, a nil force-unwrap, an array bound and an integer overflow
 * all trap with `SIGTRAP`/`SIGILL` and never reach it. So this producer
 * cannot report `swift_fatal` and does not pretend to.
 */
    fun iosCrashClass(seam: String?): String =
        if (seam == "nsexception") "objc_nsexception" else "unknown"

    /**
 * The hang vocabulary, per platform.
 *
 * `anr` ON ANDROID IS A CLAIM ABOUT THE MAIN THREAD, NOT ABOUT THE SYSTEM
 * DIALOG. The platform's own ANR is declared by the system after ~5 s and
 * the app is not reliably alive to report it; what this producer observes is
 * its own main looper failing to service a probe past a threshold, which is
 * the measurable antecedent.
 */
    fun hangKindFor(platform: String?): String = when (platform) {
        "android" -> "anr"
        "ios" -> "app_hang"
        else -> "anr"
    }

    /**
 * Should this hang observation be emitted at all?
 *
 * A ZERO-OR-NEGATIVE DURATION IS REFUSED, and the schema is why:
 * `blockedMs` is `minimum: 0` and the DEPLOYED server reads it as
 * `typeof === 'number'` with NO range, so a negative value computed across
 * an NTP step would be STORED — and a hang lane that can show a negative
 * block is worse than one that shows nothing. A non-finite value is refused
 * for the same reason: it serialises to `null` and would reach a
 * `double precision` column as a null nobody chose.
 */
    fun hangIsEmittable(blockedMs: Double, thresholdMs: Double): Boolean {
        if (!blockedMs.isFinite() || blockedMs <= 0.0) return false
        if (!thresholdMs.isFinite() || thresholdMs <= 0.0) return false
        return blockedMs >= thresholdMs
    }

    /** Convenience for the millisecond-integer call sites. */
    fun hangIsEmittable(blockedMs: Long, thresholdMs: Long): Boolean =
        hangIsEmittable(blockedMs.toDouble(), thresholdMs.toDouble())

    /**
 * Bound one string to a schema length. Null for absent/empty.
 *
 * IT COUNTS UNICODE CODE POINTS, because that is what JSON Schema
 * `maxLength` counts. `substring`/`take` count UTF-16 code UNITS, which is
 * never BELOW the code-point count — safe, but not EQUAL to the Swift twin,
 * whose `prefix(n)` counts extended grapheme CLUSTERS and can therefore
 * exceed the bound outright. One definition, the schema's, in all three
 * implementations; a non-ASCII case is pinned per language because JSON
 * cannot carry the distinction into the shared vectors.
 */
    private fun bound(s: String?, max: Int): String? {
        if (s.isNullOrEmpty()) return null
        val cps = s.codePointCount(0, s.length)
        if (cps <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max))
    }

    /** Bound one `frames[].symbol` to the schema length. Null for absent/empty. */
    fun boundSymbol(s: String?): String? = bound(s, MAX_SYMBOL)

    /** Bound one `frames[].module` to the schema length. Null for absent/empty. */
    fun boundModule(s: String?): String? = bound(s, MAX_MODULE)

    /** Bound `mobile_crash.type` to the schema length. Null for absent/empty. */
    fun boundType(s: String?): String? = bound(s, MAX_TYPE)

    /** Bound `mobile_crash.threadName` to the schema length — 128, NOT 200. */
    fun boundThreadName(s: String?): String? = bound(s, MAX_THREAD_NAME)

    /** Bound the screen identity to 64: `screen.screenId` and `mobile_hang.screenId`. */
    fun boundScreenId(s: String?): String? = bound(s, MAX_SCREEN_ID)

    /** `emitted` frames and how many were `dropped` by the cap. */
    data class FramePlan(val emitted: Int, val dropped: Int)

    /**
 * How many of `captured` frames will be emitted, and whether that is lossy.
 *
 * Returned as a pair rather than applied here so the caller can DISCLOSE the
 * drop instead of silently shortening a stack — a stack that is quietly 128
 * frames deep reads as a complete stack.
 *
 * THE DISCLOSURE CHANNEL IS LOCAL, NOT THE WIRE. The envelope's `dropped`
 * map is a PER-FAMILY LOSS counter whose own schema description says
 * intentional suppression "is NOT loss and must never appear here", and a
 * within-event frame truncation is neither loss nor a declared field — so
 * the producer logs it through `RecoveryTrace` instead. Inventing a wire
 * field for it is exactly what the native producer did with `exceptionType`
 * / `stackFrames`.
 */
    fun framePlan(captured: Int): FramePlan {
        val total = if (captured > 0) captured else 0
        val emitted = if (total > MAX_FRAMES) MAX_FRAMES else total
        return FramePlan(emitted, total - emitted)
    }
}

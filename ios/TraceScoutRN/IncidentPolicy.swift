import Foundation

/// MOBILE INCIDENT v1 — the crash / hang decision, in ONE place.
///
/// The Swift twin of `mobile-protocol/src/incident-policy.cjs`, pinned to
/// `parity-vectors/incident-policy-vectors.json`. A platform disagreement fails
/// on BYTES, not on three people reading one paragraph.
///
/// THE MESSAGE IS NEVER EMITTED. `mobile_crash.schema.json` declares an
/// optional `message` and this type has no function that produces one. That is
/// inherited, not invented: the Android native producer excludes it for the
/// stated reason that "application code interpolates values into messages, and
/// a crash report is not an exemption from the privacy model", and there is no
/// value-pattern sanitizer in either mobile runtime that could redact one.
public enum IncidentPolicy {

    /// `mobile_crash.crashClass` — the closed enum, in schema order.
    public static let crashClasses: [String] = [
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
    ]

    /// `mobile_hang.hangKind` — the closed enum, in schema order.
    public static let hangKinds: [String] = ["anr", "app_hang", "watchdog_warning", "slow_frame_burst"]

    /// The frame cap this producer applies — STRICTLY BELOW the schema's
    /// `maxItems: 512`, and deliberately so. It bounds WORK on a thread racing
    /// the process's death, not just bytes; and a producer that emitted exactly
    /// the schema maximum would be refused by any future tightening of it.
    public static let maxFrames = 128

    /// `frames[].symbol` max length, from the schema.
    public static let maxSymbol = 400

    /// `frames[].module` max length, from the schema.
    public static let maxModule = 200

    /// `errorId` max length. The DEPLOYED server rejects past it with
    /// `incident_identity_invalid` — a permanent 422 on the WHOLE chunk — so
    /// this is a bound the producer must respect rather than discover.
    public static let maxErrorId = 64

    /// `mobile_crash.type` max length, from the schema.
    public static let maxType = 200

    /// `mobile_crash.threadName` max length, from the schema.
    ///
    /// IT IS 128, NOT 200, AND THIS BUILDER HAD IT WRONG. A thread name of
    /// 129–200 code points passed a 200-bound unchanged and reached a field the
    /// schema caps at 128 — a schema-invalid crash record, which the deployed
    /// server refuses with a PERMANENT 422 on the WHOLE chunk. Found by review,
    /// confirmed by reading `mobile_crash.schema.json`; the conformance suite
    /// pins this to the schema rather than to a literal.
    public static let maxThreadName = 128

    /// Screen identity max length — `envelope.screen.screenId` (crash) and
    /// `mobile_hang.screenId` (hang), both 64.
    ///
    /// NEITHER BUILDER BOUNDED IT AT ALL, so an RN route id past 64 code
    /// points produced a schema-invalid record on BOTH event types.
    public static let maxScreenId = 64

    private static let rnJsException = "com.facebook.react.common.JavascriptException"
    private static let rnJsNativeModule =
        "com.facebook.react.bridge.JSApplicationCausedNativeModuleException"

    /// Classes the KOTLIN runtime alone produces. Carried on iOS too, because
    /// the vectors are SHARED: a twin that implemented only the branches its own
    /// platform reaches could not be checked against the same corpus, which is
    /// the whole point of having one.
    private static let kotlinOnly: Set<String> = [
        "kotlin.KotlinNullPointerException",
        "kotlin.NotImplementedError",
        "kotlin.UninitializedPropertyAccessException",
        "kotlin.TypeCastException",
    ]

    /// Classify an Android uncaught throwable by its fully-qualified class name.
    ///
    /// MOST SPECIFIC FIRST. A React Native JavaScript exception arrives
    /// wrapped in a Java class, so the wrapper must be tested BEFORE the generic
    /// JVM answer or `js_uncaught` can never be reached.
    public static func androidCrashClass(_ className: String?) -> String {
        guard let c = className, !c.isEmpty else { return "unknown" }
        if c == rnJsException || c == rnJsNativeModule || c.hasPrefix(rnJsException) {
            return "js_uncaught"
        }
        if c == "java.lang.OutOfMemoryError" { return "oom" }
        if kotlinOnly.contains(c) { return "kotlin_uncaught" }
        return "java_uncaught"
    }

    /// Classify an iOS incident by the SEAM that observed it.
    ///
    /// THE SEAM, NOT THE SYMPTOM, AND THAT BOUNDS WHAT iOS CAN CLAIM.
    /// `NSSetUncaughtExceptionHandler` observes an `NSException` only. A Swift
    /// `fatalError`, a nil force-unwrap, an array bound and an integer overflow
    /// all trap the process with `SIGTRAP`/`SIGILL` and NEVER reach it — so this
    /// producer cannot report `swift_fatal`, and does not pretend to. The enum
    /// member stays in the vocabulary because the contract is shared with
    /// producers that may install signal handlers; this one does not, because a
    /// signal handler in a library competes for a process-global seam the host
    /// app may already own.
    public static func iosCrashClass(_ seam: String?) -> String {
        seam == "nsexception" ? "objc_nsexception" : "unknown"
    }

    /// The hang vocabulary, per platform.
    ///
    /// iOS names the observation `app_hang`, matching Apple's own term for
    /// the MetricKit measurement; Android names the same observation `anr`.
    public static func hangKindFor(_ platform: String?) -> String {
        switch platform {
        case "android": return "anr"
        case "ios": return "app_hang"
        default: return "anr"
        }
    }

    /// Should this hang observation be emitted at all?
    ///
    /// A ZERO-OR-NEGATIVE DURATION IS REFUSED, and the schema is why:
    /// `blockedMs` is `minimum: 0` and the DEPLOYED server reads it as a bare
    /// number with NO range, so a negative value computed across a clock step
    /// would be STORED — and a hang lane that can show a negative block is worse
    /// than one that shows nothing. A non-finite value is refused for the same
    /// reason: it serialises to `null` and would reach a `double precision`
    /// column as a null nobody chose.
    public static func hangIsEmittable(blockedMs: Double, thresholdMs: Double) -> Bool {
        guard blockedMs.isFinite, blockedMs > 0 else { return false }
        guard thresholdMs.isFinite, thresholdMs > 0 else { return false }
        return blockedMs >= thresholdMs
    }

    /// Bound one string to a schema length. Nil for absent/empty.
    ///
    /// IT COUNTS UNICODE SCALARS, NOT `Character`s, AND THAT WAS A REAL
    /// DEFECT. JSON Schema `maxLength` counts code points; `String.prefix(n)`
    /// counts extended GRAPHEME CLUSTERS, and one cluster can hold many code
    /// points (a ZWJ emoji sequence, stacked combining marks) — so a
    /// 200-`Character` result can be far past a 200-code-point bound, which is
    /// a schema violation this producer believed it had prevented. The Kotlin
    /// and JS twins counted their own units (UTF-16, never BELOW the code-point
    /// count — safe, but not equal); all three now count code points, the
    /// schema's own unit.
    ///
    /// The shared vectors are ASCII and cannot see the difference, so each twin
    /// pins a non-ASCII case in its own language.
    private static func bound(_ s: String?, _ max: Int) -> String? {
        guard let s = s, !s.isEmpty else { return nil }
        let scalars = s.unicodeScalars
        guard scalars.count > max else { return s }
        return String(String.UnicodeScalarView(scalars.prefix(max)))
    }

    /// Bound one `frames[].symbol` to the schema length. Nil for absent/empty.
    public static func boundSymbol(_ s: String?) -> String? { bound(s, maxSymbol) }

    /// Bound one `frames[].module` to the schema length. Nil for absent/empty.
    public static func boundModule(_ s: String?) -> String? { bound(s, maxModule) }

    /// Bound `mobile_crash.type` to the schema length. Nil for absent/empty.
    public static func boundType(_ s: String?) -> String? { bound(s, maxType) }

    /// Bound `mobile_crash.threadName` to the schema length — 128, NOT 200.
    public static func boundThreadName(_ s: String?) -> String? { bound(s, maxThreadName) }

    /// Bound the screen identity to 64: `screen.screenId` and `mobile_hang.screenId`.
    public static func boundScreenId(_ s: String?) -> String? { bound(s, maxScreenId) }

    /// `emitted` frames and how many were `dropped` by the cap.
    public struct FramePlan: Equatable {
        public let emitted: Int
        public let dropped: Int
        public init(emitted: Int, dropped: Int) {
            self.emitted = emitted
            self.dropped = dropped
        }
    }

    /// How many of `captured` frames will be emitted, and whether that is lossy.
    ///
    /// Returned as a pair rather than applied here so the caller can DISCLOSE
    /// the drop instead of silently shortening a stack — a stack that is quietly
    /// 128 frames deep reads as a complete stack.
    ///
    /// THE DISCLOSURE CHANNEL IS LOCAL, NOT THE WIRE. The envelope's
    /// `dropped` map is a PER-FAMILY LOSS counter whose own schema description
    /// says intentional suppression "is NOT loss and must never appear here",
    /// and a within-event frame truncation is neither loss nor a declared
    /// field — so the producer logs it through `RecoveryTrace` instead.
    /// Inventing a wire field for it is exactly what the native producer did
    /// with `exceptionType` / `stackFrames`.
    public static func framePlan(captured: Int) -> FramePlan {
        let total = captured > 0 ? captured : 0
        let emitted = total > maxFrames ? maxFrames : total
        return FramePlan(emitted: emitted, dropped: total - emitted)
    }
}

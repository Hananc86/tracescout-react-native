package com.tracescout.rn

/**
 * The producer triple identifies the CAPTURE CODE that produced the bytes.
 *
 * These are compile-time constants for the same reason the Android SDK's are
 * (see `mobile-android` `emit/Envelope`): the server's capability boundary is
 * only meaningful if a producer cannot misreport which code produced its bytes.
 * There is deliberately **no setter and no mutable holder** — a runtime seam
 * would let this producer claim `tracescout-android`, which the contract
 * forbids outright.
 *
 * Contract: TraceScout-API `docs/mobile/REACT_NATIVE_PRODUCER_POSTURE_V1.md`.
 */
object Producer {

    const val PROTOCOL_VERSION = "mobile-1.0.0"
    const val PLATFORM = "react_native"

    /** RN always runs on a host; the envelope requires it. */
    const val HOST_PLATFORM_ANDROID = "android"

    const val SDK_FAMILY = "tracescout-react-native"

    /**
 * Exactly `1.0.0` — NOT `1.0.0-internal`.
 *
 * The deployed parser treats a prerelease tag as a label with no ordering
 * meaning ("prerelease tags are labels here, not ordering"). `1.0.0-internal`
 * does classify as >= 1.0.0 today, but relying on that is relying on a
 * detail the policy explicitly declines to define. The fixture APP version
 * is separate and unrelated to this constant.
 */
    const val SDK_VERSION = "1.0.0"
}

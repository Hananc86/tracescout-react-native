package com.tracescout.rn

/**
 * the specification CaptureOutcome — the capture-outcome decision.
 *
 * NORMATIVE SOURCE IS THE PROTOCOL, NOT A PARAPHRASE OF IT.
 * `mobile-protocol/src/events/mobile_screen_snapshot.schema.json`
 * (`captureUnavailableReason.description`) and `mobile-protocol/CHANGELOG.md`
 * define these two values independently and identically, and the API vendors
 * that schema. So:
 *
 * route_surface_not_attached no qualifying active route host for the
 * REQUESTED ROUTE existed BEFORE THE ATTACH
 * BUDGET EXPIRED
 * route_surface_not_ready a qualifying active host existed but its
 * DECLARED READINESS never became true, or it
 * REPORTED FAILURE
 * route_surface_capture_empty a qualifying host DECLARED READY inside the
 * budget and EVERY BOUNDED LATE CAPTURE of the
 * route produced no usable hierarchy. NOT
 * derived by [decide]: the rule answers
 * `capture` for a ready host and never sees the
 * walk; the producer that owns the bounded late
 * capture emits it after exhaustion, once per
 * attempt (RnBridge).
 *
 * AN INSTANTANEOUS OBSERVATION PROVES NEITHER, AND THAT IS THE WHOLE POINT.
 * A surface that is not attached right now may attach a millisecond later, so
 * "detached at t=0" is not "no host before the budget expired". Zero measured
 * dimensions is a measurement WE took, not a readiness statement the HOST made.
 * A producer that maps either observation onto a reason asserts a condition it
 * never established.
 *
 * OWNERSHIP IS PART OF THE CONDITION. Both values are about the host OF THE
 * REQUESTED ROUTE. A capture root that is merely the current window or the
 * current activity decor view is not authoritatively associated with a route,
 * so observing it establishes nothing about that route's host. Unknown
 * ownership yields NO reason.
 *
 * BACKGROUND AND PRIVACY REFUSALS ARE A SEPARATE AXIS and carry no reason.
 *
 * Pinned by the shared fixture `mobile-native-core/fixtures/capture-outcome.json`.
 */
object CaptureOutcome {

    /** The CLOSED failure vocabulary, copied from the protocol schema. */
    const val NOT_ATTACHED = "route_surface_not_attached"
    const val NOT_READY = "route_surface_not_ready"
    const val CAPTURE_EMPTY = "route_surface_capture_empty"

    /** Every value the wire admits — what the boundary checks against. */
    @JvmField
    val REASONS: List<String> = listOf(NOT_ATTACHED, NOT_READY, CAPTURE_EMPTY)

    /**
 * The subset [decide] can DERIVE from host facts. [CAPTURE_EMPTY] is
 * deliberately absent: no host-fact input yields it, so the shared fixture's
 * decide cases never expect it and the conformance suite pins THIS list as
 * "every reason the rule can produce occurs in the fixture".
 */
    @JvmField
    val HOST_DERIVED_REASONS: List<String> = listOf(NOT_ATTACHED, NOT_READY)

    enum class Decision { NEVER_RECORD, UNAVAILABLE, CAPTURE, NO_EMISSION }

    data class Result(val decision: Decision, val captureUnavailableReason: String? = null)

    /**
 * What a ROUTE-HOST REGISTRY would report. Every field is nullable and every
 * null means "we could not establish this" — never "false".
 *
 * NO PLATFORM IN THIS REPOSITORY CAN POPULATE THIS TODAY. There is no
 * route-host registry, no attach budget and no host readiness declaration on
 * Android, RN Android or RN iOS; the capture roots available are the current
 * activity decor view and the current key window, neither of which is
 * authoritatively associated with a route. So every call site passes the
 * all-unknown value and NO reason is emitted. That is the correct answer,
 * not a gap being papered over: the alternative is to assert a protocol
 * condition from an observation that cannot establish it.
 */
    data class RouteHost(
        val resolved: Boolean? = null,
        val attachBudgetExpired: Boolean? = null,
        val readinessDeclaredTrue: Boolean? = null,
        val hostReportedFailure: Boolean? = null,
    ) {
        companion object {
            /** No route-host registry exists on this platform. */
            @JvmField
            val UNKNOWN = RouteHost()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun decide(
        neverRecord: Boolean,
        host: RouteHost = RouteHost.UNKNOWN,
        threw: Boolean = false,
    ): Result {
        // An intentional exclusion is not a capture failure, and it is decided
        // BEFORE anything about the surface is inspected.
        if (neverRecord) return Result(Decision.NEVER_RECORD)

        // A generic failure is not a reason.
        if (threw) return Result(Decision.NO_EMISSION)

        // Unknown is not false. Without an authoritative answer about the
        // route's own host, nothing below can be established.
        val resolved = host.resolved ?: return Result(Decision.NO_EMISSION)

        if (!resolved) {
            // A missing host is only a verdict once the budget we promised to
            // wait has actually elapsed. Before that, the honest answer is
            // "not yet".
            return if (host.attachBudgetExpired == true) {
                Result(Decision.UNAVAILABLE, NOT_ATTACHED)
            } else {
                Result(Decision.NO_EMISSION)
            }
        }

        // A host exists. Only the HOST's own statements can make it not-ready.
        if (host.hostReportedFailure == true) return Result(Decision.UNAVAILABLE, NOT_READY)
        return when (host.readinessDeclaredTrue) {
            true -> Result(Decision.CAPTURE)
            false -> Result(Decision.UNAVAILABLE, NOT_READY)
            // Readiness was never declared either way: we know nothing.
            null -> Result(Decision.NO_EMISSION)
        }
    }
}

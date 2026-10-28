// GENERATED FROM THE rn-core TWIN — DO NOT EDIT BY HAND.
// Source: mobile-react-native/android/rn-core/src/main/kotlin/com/tracescout/rn/RouteHostRegistry.kt
// The standalone SDK has no dependency on rn-core (it must not drag a React
// Native module into a plain Android app), so the file is COPIED. A copy that
// is edited independently drifts, and the drift is invisible because both sides
// stay green — so `RouteHostParityTest` REGENERATES this from the twin and
// byte-compares. Change the twin; this follows.
package com.tracescout.mobile.capture

/**
 * THE ROUTE-HOST REGISTRY — the facts the specification requires, and the only honest way to
 * obtain them on a platform whose native layer cannot see the UI framework.
 *
 * WHY THIS HAS TO BE AN INTEGRATION CONTRACT, NOT A DETECTION.
 * The protocol's two reasons are about the HOST OF A REQUESTED ROUTE:
 *
 * route_surface_not_attached no qualifying active route host existed BEFORE
 * THE ATTACH BUDGET EXPIRED
 * route_surface_not_ready a qualifying host existed but its DECLARED
 * readiness never became true, or it REPORTED
 * FAILURE
 *
 * React Native routes are JavaScript components. The native layer can see an
 * Activity, a decor view, a window and a React root — and NONE of those is
 * associated with a particular route. Every "detection" available is therefore
 * a guess, and a guess dressed as a measurement is exactly what this axis
 * exists to prevent. So the host TELLS us, and if nobody tells us we say
 * nothing.
 *
 * UNINTEGRATED APPLICATIONS ARE UNCHANGED, BY CONSTRUCTION.
 * An app that never registers a host produces `RouteHost.UNKNOWN` for every
 * attempt, the shared rule refuses, and no reason is ever emitted — which is
 * precisely today's behaviour. Integration ADDS the ability to report; it
 * cannot change what an unintegrated app already does.
 *
 * NO CLOCK OF ITS OWN, AND NO SLEEP ANYWHERE.
 * The budget is evaluated against an INJECTED time source, and the caller drives
 * evaluation. A registry that slept would be untestable except by waiting, and
 * "we waited 400 ms" is not the same fact as "the budget elapsed" — the second
 * is what the protocol asks about.
 *
 * A CALLBACK FOR AN OLD ROUTE MUST NEVER RESOLVE THE CURRENT ONE.
 * Every attempt carries a generation, and every host statement is matched
 * against the (routeId, hostId, generation) it was made for. A late `ready` from
 * a screen the user already left is DISCARDED and counted, never applied.
 */
class RouteHostRegistry(
    /**
 * How long to wait for a qualifying host before `route_surface_not_attached`
 * becomes a verdict. This is the number the protocol calls the attach
 * budget; it is a policy input, never a hidden constant.
 */
    private val attachBudgetMs: Long = DEFAULT_ATTACH_BUDGET_MS,
    /** Monotonic milliseconds. Injected so the budget is testable without waiting. */
    private val nowMs: () -> Long,
) {

    companion object {
        /**
 * Chosen to be longer than a normal mount+layout and shorter than a
 * user would wait before deciding the screen is broken. It is a
 * DEFAULT, not a law: an integration that knows its own screens can
 * pass its own.
 */
        const val DEFAULT_ATTACH_BUDGET_MS = 2_000L
    }

    /**
 * What a host says about itself. A host may only report its OWN state.
 *
 * `ATTACHED` AND `PENDING` ARE DIFFERENT HOSTS, AND CONFLATING THEM WAS A
 * REAL CONTRACT ERROR. The protocol's `route_surface_not_ready` covers a
 * qualifying host whose "DECLARED readiness never became true" — which
 * includes a host that PROMISED to report readiness and never did. But a
 * host with no readiness contract at all has promised nothing, so its
 * silence is not a broken promise and cannot be timed out.
 *
 * ATTACHED "I am here." No readiness contract. Silence is
 * UNKNOWN forever — there is no
 * declaration to have failed.
 * PENDING "I am here and I WILL A readiness contract. Silence past the
 * report readiness." budget IS `route_surface_not_ready`:
 * the declaration never became true.
 */
    private enum class HostState { ATTACHED, PENDING, READY, FAILED }

    private data class Host(
        val hostId: String,
        var state: HostState,
        /**
 * The capture root — a SEPARATE fact from readiness. A host declaring
 * readiness establishes that it is ready and nothing about which nodes
 * are its; only a root, verified at capture time, can do that.
 *
 * On standalone Android the handle is the app-supplied root View's
 * identity, resolved by the platform. It is an opaque Int here so the
 * two registries stay one implementation.
 */
        var rootHandle: Int? = null,
        /** Identity of the CURRENT binding; re-binding issues a new one, so a
 * binding read before a walk can be proven still current after it. */
        var bindingId: Long = 0L,
    )

    /** What the platform needs in order to walk a route's OWN content. */
    data class BoundRoot(val hostId: String, val rootHandle: Int, val bindingId: Long)

    /** An in-flight capture attempt. Resolvable exactly once. */
    class Attempt internal constructor(
        val routeId: String,
        val sessionId: String,
        internal val generation: Long,
        internal val startedMs: Long,
    ) {
        /**
 * A STABLE, CONTENT-FREE identity for this attempt, so one attempt can
 * be followed across creation, queueing, scheduling, callback, decision
 * and emission.
 *
 * DERIVED, NOT RANDOM, AND IT CARRIES NOTHING SENSITIVE. It is the
 * generation plus the start time plus a hash of the route — never the
 * session id, never a credential, never captured content. A trace that
 * cannot be correlated is a pile of unrelated lines; one that leaks
 * identity is worse than no trace.
 */
        val attemptId: String =
            "a${generation}_${startedMs % 1_000_000}_${routeId.hashCode().toUInt().toString(16).take(4)}"
        @Volatile internal var resolved: Boolean = false
        @Volatile internal var cancelled: Boolean = false

        /** Settled = it can never emit again, so a queue may drop it. */
        fun isSettled(): Boolean = resolved || cancelled
    }

    private val lock = Any()

    /** routeId -> the hosts that have announced themselves for it. */
    private val hosts = LinkedHashMap<String, MutableList<Host>>()

    private var generation: Long = 0
    /** The unsettled attempt for the current route/session/generation, if any. */
    private var openAttempt: Attempt? = null
    /** Captures that re-used the open attempt instead of minting one. Observability only. */
    @Volatile var coalescedAttempts: Int = 0
    private var currentRoute: String? = null
    private var currentSession: String? = null

    /** Observability: statements we DISCARDED, and why. Never silent. */
    @Volatile var discardedStaleStatements: Int = 0
    /** Monotonic source of binding identities; never reused. */
    private var bindingSeq: Long = 0
    /** A host re-bound to a different capture root. */
    @Volatile var rebindings: Int = 0
    /** Two hosts bound to one route — refused rather than guessed between. */
    @Volatile var ambiguousBindings: Int = 0
        private set
    @Volatile var discardedForeignRoute: Int = 0
        private set

    // ── the integration contract ────────────────────────────────────────────

    /**
 * A host for [routeId] has attached. Idempotent per (route, host).
 *
 * ATTACHED IS NOT READY, AND IT IS ALSO NOT PENDING. `hostAttached`
 * alone creates NO readiness contract, so silence can never be timed out
 * into `not_ready`. A host that intends to report readiness must say so with
 * [hostReadinessPending] — that promise is what a timeout can break.
 */
    fun hostAttached(routeId: String, hostId: String, rootHandle: Int? = null) = synchronized(lock) {
        val list = hosts.getOrPut(routeId) { mutableListOf() }
        val existing = list.firstOrNull { it.hostId == hostId }
        if (existing == null) {
            list.add(Host(hostId, HostState.ATTACHED, rootHandle,
                if (rootHandle != null) ++bindingSeq else 0L))
        } else if (rootHandle != null && rootHandle != existing.rootHandle) {
            // A replacement must invalidate the old binding.
            existing.rootHandle = rootHandle
            existing.bindingId = ++bindingSeq
            rebindings++
        }
    }

    /**
 * The host declares it is PREPARING and WILL report readiness.
 *
 * THIS IS THE READINESS CONTRACT, AND IT IS WHAT MAKES A TIMEOUT
 * MEANINGFUL. Declaring pending is a promise; failing to keep it within the
 * budget is exactly "its declared readiness never became true". A host that
 * never calls this has made no promise, and we must not invent one for it.
 *
 * THE BUDGET RUNS FROM THE CAPTURE ATTEMPT, NOT FROM THIS CALL, AND
 * REPEATING THIS CALL BUYS NOTHING. The clock starts at [beginAttempt] —
 * the moment the app asked for a capture — and every host statement is
 * measured against that one origin.
 *
 * Two consequences, both deliberate:
 *
 * - A host that attaches LATE has less of the budget left. That is correct:
 * the question the protocol asks is whether a capture could be taken
 * when it was REQUESTED, not whether the host eventually got there.
 * - Declaring pending again — or attaching a second host, or any other
 * repeated declaration — CANNOT extend the deadline. If it could, a
 * host that re-declared in a loop would hold a capture open forever and
 * the bound would be a fiction. The only thing that starts a new budget
 * is a new ATTEMPT.
 */
    fun hostReadinessPending(routeId: String, hostId: String) =
        setState(routeId, hostId, HostState.PENDING)

    /** The host declares it became ready. */
    fun hostReady(routeId: String, hostId: String) = setState(routeId, hostId, HostState.READY)

    /** The host declares it failed to become ready. */
    fun hostFailed(routeId: String, hostId: String) = setState(routeId, hostId, HostState.FAILED)

    /**
 * The host went away. Its statements stop counting immediately — a detached
 * host is not a qualifying ACTIVE host.
 */
    fun hostDetached(routeId: String, hostId: String) = synchronized(lock) {
        hosts[routeId]?.removeAll { it.hostId == hostId }
        if (hosts[routeId]?.isEmpty() == true) hosts.remove(routeId)
        Unit
    }

    /** The route's capture root, or null when ownership cannot be established:
 * no host, no declared root, or TWO hosts bound to one route — the last is
 * ambiguity, and guessing between them is the misattribution this stops. */
    fun boundRoot(routeId: String): BoundRoot? = synchronized(lock) {
        val bound = hosts[routeId].orEmpty().filter { it.rootHandle != null }
        if (bound.isEmpty()) return@synchronized null
        if (bound.size > 1) { ambiguousBindings++; return@synchronized null }
        val h = bound[0]
        BoundRoot(h.hostId, h.rootHandle!!, h.bindingId)
    }

    /** Asked AFTER the walk as well as before it: a host can be replaced while
 * the tree is read, and content gathered under a binding that no longer
 * exists is not that route's content. */
    fun bindingIsCurrent(routeId: String, b: BoundRoot): Boolean = synchronized(lock) {
        val h = hosts[routeId].orEmpty().firstOrNull { it.hostId == b.hostId }
            ?: return@synchronized false
        h.rootHandle == b.rootHandle && h.bindingId == b.bindingId
    }

    /** Separates an integrated app's unregistered route from no registry. */
    fun hasAnyHost(routeId: String): Boolean = synchronized(lock) {
        hosts[routeId].orEmpty().isNotEmpty()
    }

    private fun setState(routeId: String, hostId: String, s: HostState) = synchronized(lock) {
        val h = hosts[routeId]?.firstOrNull { it.hostId == hostId }
        if (h == null) {
            // A statement from a host that never attached, or has already
            // detached. It describes a world that no longer exists.
            discardedStaleStatements++
        } else {
            h.state = s
        }
        Unit
    }

    // ── route and session lifecycle ─────────────────────────────────────────

    /**
 * The app moved to [routeId]. Every in-flight attempt for another route is
 * invalidated: the generation advances, so a late host statement or a late
 * poll cannot resolve against the new route.
 */
    fun routeChanged(routeId: String?) = synchronized(lock) {
        if (currentRoute != routeId) {
            currentRoute = routeId
            generation++
        }
        Unit
    }

    /**
 * A new session began. Everything from the previous one is invalid — hosts
 * included, because a host registered against a dead session cannot qualify
 * for the new one.
 */
    fun sessionChanged(sessionId: String?) = synchronized(lock) {
        if (currentSession != sessionId) {
            currentSession = sessionId
            currentRoute = null
            hosts.clear()
            generation++
        }
        Unit
    }

    // ── capture scheduling ──────────────────────────────────────────────────

    /** Begin an attempt for [routeId]. Also makes it the current route. */
    fun beginAttempt(routeId: String, sessionId: String): Attempt = synchronized(lock) {
        if (currentSession != sessionId) {
            currentSession = sessionId
            hosts.clear()
            generation++
        }
        if (currentRoute != routeId) {
            currentRoute = routeId
            generation++
        }
        openAttempt?.let { o ->
            if (!o.isSettled() && o.generation == generation &&
                o.routeId == routeId && o.sessionId == sessionId) {
                coalescedAttempts++
                return@synchronized o
            }
        }
        Attempt(routeId, sessionId, generation, nowMs()).also { openAttempt = it }
    }

    /** Abandon an attempt without resolving it. Idempotent. */
    fun cancel(attempt: Attempt) = synchronized(lock) {
        attempt.cancelled = true
        Unit
    }

    /**
 * Evaluate [attempt] against the facts as they stand NOW, and against the
 * budget. Returns the shared rule's input — never a decision, so the rule
 * stays the single place the two reasons are derived.
 *
 * RETURNS UNKNOWN WHILE THERE IS STILL SOMETHING TO WAIT FOR. That is the
 * caller's signal to poll again, and it is why a bounded wait does not need
 * a sleep: nothing is asserted until the budget has genuinely elapsed.
 */
    fun evaluate(attempt: Attempt): CaptureOutcome.RouteHost = synchronized(lock) {
        // A STALE ATTEMPT RESOLVES NOTHING. If the route or session moved on,
        // this attempt describes a screen that is no longer current; applying it
        // would attribute an outcome to the wrong route.
        if (attempt.cancelled || attempt.generation != generation) {
            discardedForeignRoute++
            return CaptureOutcome.RouteHost.UNKNOWN
        }
        if (attempt.sessionId != currentSession || attempt.routeId != currentRoute) {
            discardedForeignRoute++
            return CaptureOutcome.RouteHost.UNKNOWN
        }

        val list = hosts[attempt.routeId].orEmpty()
        val elapsed = nowMs() - attempt.startedMs
        val budgetExpired = elapsed >= attachBudgetMs

        if (list.isEmpty()) {
            // No qualifying host yet. Only the elapsed budget turns that into a
            // verdict; before then we genuinely do not know.
            return CaptureOutcome.RouteHost(
                resolved = false,
                attachBudgetExpired = budgetExpired,
            )
        }

        // A host exists. Its own statements decide, and FAILURE outranks
        // readiness: a host that reported failure has told us something
        // definite, and a sibling that is merely ready does not erase it.
        if (list.any { it.state == HostState.FAILED }) {
            return CaptureOutcome.RouteHost(resolved = true, hostReportedFailure = true)
        }
        if (list.any { it.state == HostState.READY }) {
            return CaptureOutcome.RouteHost(resolved = true, readinessDeclaredTrue = true)
        }
        // A BROKEN PROMISE IS A VERDICT; SILENCE WITHOUT A PROMISE IS NOT.
        // A PENDING host declared it would report readiness. If the budget has
        // elapsed and it still has not, its DECLARED readiness never became
        // true — which is the protocol's own wording for
        // `route_surface_not_ready`. Before the budget it is simply still
        // working, and we say nothing.
        if (list.any { it.state == HostState.PENDING }) {
            return if (budgetExpired) {
                CaptureOutcome.RouteHost(resolved = true, readinessDeclaredTrue = false)
            } else {
                CaptureOutcome.RouteHost(resolved = true, readinessDeclaredTrue = null)
            }
        }
        // Attached with no readiness contract at all. It promised nothing, so
        // there is no declaration to have failed and no timeout to apply — the
        // host exists (so `not_attached` is false) and never declared (so
        // `not_ready` is unproven). UNKNOWN, deliberately and forever.
        return CaptureOutcome.RouteHost(resolved = true, readinessDeclaredTrue = null)
    }

    /**
 * Is [attempt] no longer the attempt the app is making?
 *
 * THIS IS THE EXECUTION-TIME IDENTITY CHECK, AND CANCELLING A TIMER IS NOT
 * A SUBSTITUTE FOR IT. `Handler.removeCallbacks` and
 * `DispatchWorkItem.cancel` cannot retract a callback that has already been
 * dequeued and is running, and neither can stop one already delivered. So
 * the callback must ask, at the moment it RUNS, whether the world it was
 * scheduled for still exists. Cancellation is an optimisation; THIS is the
 * guarantee.
 */
    fun isStale(attempt: Attempt): Boolean = synchronized(lock) {
        attempt.cancelled ||
            attempt.generation != generation ||
            attempt.sessionId != currentSession ||
            attempt.routeId != currentRoute
    }

    /**
 * Claim the single resolution of [attempt]. Returns false if it was already
 * resolved or cancelled.
 *
 * THIS IS THE EXACTLY-ONCE GATE. The emission path must call it and honour
 * the answer, so a retry, a late poll and a racing caller cannot produce two
 * outcome events for one capture attempt.
 */
    fun claimResolution(attempt: Attempt): Boolean = synchronized(lock) {
        if (attempt.resolved || attempt.cancelled) return false
        if (attempt.generation != generation) return false
        attempt.resolved = true
        true
    }

    /** Test/diagnostic view. Never used to make a decision. */
    fun hostCount(routeId: String): Int = synchronized(lock) { hosts[routeId].orEmpty().size }
}

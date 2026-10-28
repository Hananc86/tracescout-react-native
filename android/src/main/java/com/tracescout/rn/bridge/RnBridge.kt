package com.tracescout.rn.bridge

import com.tracescout.rn.RnEnvelope
import java.util.concurrent.ConcurrentHashMap

/**
 * The React-facing semantic layer.
 *
 * **What JS is allowed to say.** A route was entered. A navigation happened.
 * The app went to the background. This route must never be recorded. That is
 * the whole vocabulary. Producer identity, the protocol version, the session,
 * the project, the environment, the sequence, both clocks and the privacy
 * posture are supplied natively and are not reachable from any parameter here.
 *
 * The reason is not tidiness. The server's producer-capability boundary is only
 * meaningful if a producer cannot misreport which capture code produced its
 * bytes; a JS-settable family or version would let this producer claim
 * `tracescout-android`, which the contract forbids outright. So the guard is
 * structural — there is no parameter to set — rather than a review convention.
 *
 * **What this class does NOT own.** Durability. It never touches the queue, the
 * batch identity, the ack, the lease or the retry policy; it hands a builder to
 * [DurableSink] and the shared pipeline owns everything after that. A second
 * copy of any of those rules here would be a silently diverging authority over
 * the thing that deletes customer data.
 *
 * Nothing here throws into the host app. A React Native app that crashes
 * because analytics disagreed with an argument is a worse outcome than an
 * unrecorded event, so every path returns a typed [Result].
 */
class RnBridge(
    /**
 * The CURRENT session identity, or null when none is live. This lambda
 * must never CREATE one: a session that exists because a lifecycle or
 * navigation event happened to be the first caller has no
 * `mobile_session_start`, the server refuses to originate it, and its
 * records park at the head of the durable queue where they starve
 * everything behind them — permanently, because the missing opener can
 * never arrive. Proven live: an app that booted unconfigured, was
 * configured through the first-run screen, and then fired
 * `lifecycle('foreground')` minted exactly such a headless session and
 * blocked the whole queue with one record.
 */
    private val identity: () -> RnEnvelope.Identity?,
    /**
 * The ONE creator. Only [startSession] calls it — the opener event and
 * session creation are a single act, so a session without an opener is
 * unrepresentable rather than merely discouraged.
 */
    private val beginSession: () -> RnEnvelope.Identity?,
    /** Whether a persisted configuration exists — types the refusal. */
    private val configured: () -> Boolean,
    private val sink: DurableSink,
    private val clock: NativeClock,
    private val ids: EventIdSource,
    /**
 * NATIVE hierarchy capture. Presence of this closure IS the enablement:
 * no provider, no capture, no wire change — which is what keeps hierarchy
 * capture out of JS entirely. The provider returns an already-resolved
 * [com.tracescout.rn.HierarchyResult] (privacy applied, count derived,
 * budgets enforced), together with the CLOSED capture-unavailable reason
 * when — and only when — the provider actually OBSERVED one of the two
 * protocol conditions. Its other refusals (a main-thread timeout, a
 * FLAG_SECURE window, an invisible root, no activity at all) carry no
 * reason and stay the honest metadata-only contract.
 */
    /**
 * THE ROUTE-HOST REGISTRY. Absent = today's behaviour exactly: no host can
 * be registered, every attempt is UNKNOWN, and no reason is ever emitted.
 *
 * AN UNINTEGRATED APPLICATION MUST BE UNAFFECTED, AND NULL IS HOW THAT IS
 * GUARANTEED — not by a flag that could be flipped, but by there being
 * nothing to ask.
 */
    private val routeHosts: com.tracescout.rn.RouteHostRegistry? = null,
    /**
 * THE DEADLINE OWNER. Without it the attach budget would only ever be
 * discovered on the next navigation or lifecycle call — a wait bounded by
 * user behaviour rather than by the budget. With it, expiry fires on its
 * own and needs no further interaction.
 *
 * Absent = the previous behaviour (settle-on-next-call) and no self-firing
 * expiry, which is what an unintegrated app gets anyway.
 */
    private val attachDeadline: com.tracescout.rn.AttachDeadline? = null,
    /**
 * A SANITIZED transition trace, so one attempt can be followed through the
 * REAL device path. Absent = no tracing at all.
 *
 * IT RECEIVES ONLY WHAT THIS FILE COMPOSES, and this file composes only
 * attempt id, route id, generation, monotonic-ish timings and typed
 * reasons. No credential and no captured content can reach it, because
 * neither is in scope here.
 */
    private val trace: ((String) -> Unit)? = null,
    /**
 * The same budget the registry evaluates. PASSED, NOT GUESSED: a deadline
 * armed for a different duration than the registry measures would fire
 * early (and find nothing decided) or late (and leave the wait unbounded in
 * practice), and either way the two would disagree about what "the budget"
 * means.
 */
    private val attachBudgetMs: Long = com.tracescout.rn.RouteHostRegistry.DEFAULT_ATTACH_BUDGET_MS,
    private val hierarchy: ((String) -> HierarchyOutcome)? = null,
    /**
 * Phase 2 NATIVE frame capture. Presence is enablement of the CAPABILITY;
 * the OPT-IN itself (a persisted native config) and the platform facts
 * (foreground, secure window) are enforced inside the provider/rasterizer.
 * The bridge owns the pure budgets (cadence, per-session count) via
 * FramePolicy and never sees pixels. Absent = Phase 1 behaviour exactly.
 */
    private val frame: ((String, com.tracescout.rn.HierarchyResult) -> FrameCapture?)? = null,
    /**
 * MODEL B — the native↔WebView correlation for THIS session, asked once per
 * emitted event.
 *
 * INJECTED, AND ABSENT MEANS ABSENT. A host that supplies no provider
 * emits byte-identical envelopes to before this existed — both correlation
 * keys omitted — so the wiring cannot change a customer's bytes by being
 * present in the SDK.
 *
 * IT RETURNS AN ALREADY-TYPED [com.tracescout.rn.RnEnvelope.WebViewRef],
 * WHICH IS THE WHOLE POINT. The only way to obtain one is
 * `WebViewRef.of(admittedSegment)` behind a private constructor, so this
 * closure cannot hand the envelope a segment id for a message the shared
 * decision refused. A `String` parameter here would have re-opened exactly
 * the door the typed carrier exists to close.
 *
 * IT IS NOT A CACHE AND MUST NOT BECOME ONE. The provider re-asks the
 * session's holder on every event, because an admission can be RETIRED
 * between two events (a navigation, a revoked policy) and a value captured
 * once would keep riding events after the correlation was dropped.
 */
    private val webViewRef: (() -> RnEnvelope.WebViewRef?)? = null,
) {

    /** An already-redacted, encoded frame the native layer produced. */
    data class FrameCapture(
        val visualFrameId: String,
        val contentHash: String,
        val width: Int,
        val height: Int,
        val byteSize: Int,
        val format: String,
        val quality: Int,
        val downscale: Double,
    )

    // Per-session frame budget state — reset by startSession.
    @Volatile private var lastFrameWallMs: Long = -1
    @Volatile private var framesThisSession: Int = 0
    /**
 * an earlier change — the LAST OBSERVED answer to "does route R's
 * screen hold web content?", per route, for THIS session. Written only from
 * a hierarchy capture's own `webViewSurfaceCount` (the ONE `isWebContent`
 * home), or `false` for a never-record route; reset on session start. A
 * route never observed has NO entry and does not carry (fail closed).
 */
    private val screenWebContent = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
 * What the native capture layer reports per snapshot: the capture if it
 * happened, and a reason ONLY for the two observed protocol conditions.
 *
 * BOTH NULL IS A REAL, HONEST ANSWER — "no capture, and we will not say
 * why". That is the case for every refusal that is not one of the two
 * conditions, and it is exactly the behaviour that existed before this axis.
 */
    data class HierarchyOutcome(
        val capture: HierarchyCapture? = null,
        val captureUnavailableReason: String? = null,
    ) {
        init {
            val r = captureUnavailableReason
            // A reason alongside a successful capture is not interpretable: the
            // content is right there. Refuse it at the boundary rather than let
            // it reach the wire.
            require(r == null || capture == null) {
                "a capture-unavailable reason cannot accompany a capture"
            }
            require(r == null || r in com.tracescout.rn.CaptureOutcome.REASONS) {
                "capture-unavailable reason outside the closed vocabulary"
            }
        }
    }

    /** What the native capture layer hands the bridge per snapshot. */
    data class HierarchyCapture(
        val result: com.tracescout.rn.HierarchyResult,
        val durationMs: Long,
    )

    /** The durable path, as much of it as the bridge is permitted to see. */
    interface DurableSink {
        /**
 * Accept ONE record. The sequence is assigned by the durable path and
 * handed to [build], so the number inside the bytes and the number the
 * store ordered them by are the same number by construction.
 *
 * Taking finished bytes instead would put the sequence in the caller's
 * hands and leave the store merely counting — two unreconciled sources
 * for one value, and the server permanently refuses a batch whose
 * sequences are not contiguous.
 *
 * Returns the assigned sequence, or -1 when the record was refused.
 */
        fun enqueue(build: (Long) -> ByteArray): Long

        /**
 * Report a SEMANTIC MOMENT at which uploading would be appropriate.
 *
 * A hint, not a command: the bridge knows the app just backgrounded,
 * the transport knows what to do about it. Naming this `flush` and
 * having the bridge decide would put a durability policy — when
 * customer data leaves the device — in the layer whose whole job is
 * that it owns no durability policy.
 *
 * It exists because a live RN session had NO drain trigger at all:
 * records accumulated for the whole app lifetime and could only upload
 * on the next cold launch. Found on the first device run, where the
 * queue read the same size before and after a force-stop.
 */
        fun flushHint(reason: String)

        /** Typed transport state. Never queue contents, never the credential. */
        fun stateName(): String

        /**
 * Record, DURABLY, that [sessionId] is over. Writes no record to the
 * queue and emits nothing.
 *
 * **THIS IS THE ONE EXCEPTION TO "THIS CLASS OWNS NO DURABILITY",
 * AND IT IS NARROWER THAN IT LOOKS.** The bridge still owns no
 * durability POLICY: it does not choose the claim's identity, its
 * encoding, its idempotence rule or when it is emitted. It decides
 * exactly one thing — WHICH session the host declared over — which is
 * the only part of this fact JS is allowed to supply, and the same
 * thing it decides for every other event. Everything after that is the
 * transport's, as `enqueue`'s is.
 *
 * **SEPARATE FROM [enqueue] BECAUSE IT IS NOT A RECORD.** Routing it
 * through `enqueue` would put bytes in the queue, and a queue with
 * bytes in it is precisely the case the zero-chunk finality checkpoint
 * does not cover — the declaration would defeat the thing it exists to
 * trigger.
 *
 * @return the transport's outcome NAME. A name because the enum lives
 * in a module this one deliberately cannot see — see [stateName].
 */
        fun finalizeSession(sessionId: String): String
    }

    /** Both clocks are native. Wall time may jump; monotonic is the order. */
    interface NativeClock {
        fun wallMillis(): Long
        fun monotonicNanos(): Long
    }

    /** Event ids are minted natively so JS cannot collide or replay one. */
    interface EventIdSource {
        fun newEventId(): String
    }

    enum class Outcome {
        RECORDED,
        /** No persisted configuration — the queue is untouched, nothing is lost. */
        NOT_CONFIGURED,
        /**
 * Configured, but no session is live — the caller must startSession
 * first. The event is DROPPED, never queued: a durable record whose
 * session has no opener can never be accepted by the server, and a
 * refused-but-queued event is exactly the head-of-line stall this
 * outcome exists to prevent.
 */
        NO_SESSION,
        /** The argument could not be represented on the wire. Nothing was written. */
        INVALID_INPUT,
        /** The durable path refused the record. */
        REFUSED,
        /**
 * The capability the caller asked for is NOT INTEGRATED in this
 * application.
 *
 * DELIBERATELY NOT `REFUSED` AND NOT `NOT_CONFIGURED`. `REFUSED`
 * means the DURABLE PATH declined a record — a different subsystem and
 * a different fact. `NOT_CONFIGURED` means the SDK has no persisted
 * configuration. Reusing either would make two distinct situations
 * indistinguishable to a caller trying to work out why nothing
 * happened, which is the exact failure this event vocabulary exists to
 * avoid.
 */
        NOT_INTEGRATED,

        /**
 * The session was ALREADY declared over, so nothing was written.
 *
 * DELIBERATELY NOT FOLDED INTO `RECORDED`. Both mean "this session
 * is terminal", but only this one means "and it was not this call that
 * made it so" — the difference between a host ending a session and a
 * host retrying because it did not see the first answer. Terminality is
 * one-way and recorded ONCE, so a second declaration is a no-op rather
 * than an error; that is exactly what makes the call safe to retry, and
 * a caller cannot know retrying is safe if the two look identical.
 *
 * Produced by [endSessionAuthoritatively] only; no other path can
 * return it, because no other path writes a one-way fact.
 */
        ALREADY_RECORDED,
    }

    data class Result(
        val outcome: Outcome,
        val sequence: Long = -1,
        /** True when the record states an intentional absence of content. */
        val neverRecord: Boolean = false,
    )

    // ------------------------------------------------------------ never record

    private val neverRecordRoutes = ConcurrentHashMap.newKeySet<String>()

    /** Set only on an ACCEPTED navigation/opener/snapshot. Never from a setter. */
    @Volatile private var currentRoute: String? = null

    /**
 * Declare (or withdraw) a route as never-recorded.
 *
 * Held natively and consulted BEFORE a capture is attempted, not filtered
 * out afterwards. On Android the equivalent policy is read before the view
 * walk specifically so a declared screen's content never enters process
 * memory; a bridge that captured first and discarded second would satisfy
 * the wire contract and violate the promise the contract exists to encode.
 *
 * Withdrawing is deliberately possible — a route can stop being sensitive —
 * but it only affects captures made after the withdrawal.
 */
    fun declareNeverRecord(routeId: String, never: Boolean): Outcome {
        if (!isSafeId(routeId)) return Outcome.INVALID_INPUT
        if (never) neverRecordRoutes.add(routeId) else neverRecordRoutes.remove(routeId)
        return Outcome.RECORDED
    }

    /** Whether a route is currently declared never-record. No wire effect. */
    fun isNeverRecord(routeId: String): Boolean = routeId in neverRecordRoutes

    /**
 * The route this bridge last ACCEPTED, or null.
 *
 * Read-only, and deliberately not a setter: the only way to change it is to
 * perform a navigation the bridge validated, so it can never disagree with
 * what was recorded. A separate "set the current screen" method would be a
 * second, unreconciled source for the same fact — the defect shape that put
 * a caller-chosen sequence next to a store-assigned one.
 *
 * A NEVER-RECORD ROUTE HAS NO REPORTABLE IDENTITY. A route the app
 * declared never-record is one whose presence the customer asked us not to
 * record; attributing an unrelated signal to it by name would record it
 * anyway, through a field nobody thinks of as screen data. Callers get
 * null, and the signal is attributed to no screen rather than suppressed
 * entirely — a network failure is a fact about the app, not content of the
 * screen it happened on.
 */
    fun currentRouteId(): String? = currentRoute?.takeIf { it !in neverRecordRoutes }

    /**
 * NEEDED BECAUSE `currentRouteId` DELIBERATELY RETURNS NULL ON A
 * NEVER-RECORD ROUTE. That is right for attribution — naming the route
 * would record it through a field nobody thinks of as screen data — but it
 * makes "we are on a never-record screen" indistinguishable from "we do not
 * know the screen", and those must not collapse: the first is an emphatic
 * SUPPRESS and the second is merely unknown.
 *
 * Returns the FACT, never the route id.
 */
    fun currentRouteIsNeverRecord(): Boolean =
        currentRoute?.let { it in neverRecordRoutes } ?: false

    /**
 * Canonical snapshotId: generated NATIVELY per snapshot, 37 chars against
 * the schema's 64-char bound. Never JS-supplied — an identifier a caller
 * chooses is an identifier a caller can collide.
 */
    private fun newSnapshotId(): String =
        "snap_" + java.util.UUID.randomUUID().toString().replace("-", "")

    // ---------------------------------------------------------------- emission

    /** The first event of a session. Carries sequence 0 when the store is fresh. */
    fun startSession(initialRouteId: String?): Result {
        if (initialRouteId != null && !isSafeId(initialRouteId)) {
            return Result(Outcome.INVALID_INPUT)
        }
        // The opener is the ONLY event allowed to create the session — one
        // act, so a session without a `mobile_session_start` cannot exist.
        val id = beginSession() ?: return Result(Outcome.NOT_CONFIGURED)
        lastFrameWallMs = -1; framesThisSession = 0
        screenWebContent.keys.toList().forEach { screenWebContent.remove(it) }
        if (initialRouteId != null) currentRoute = initialRouteId
        // A new session invalidates hosts AND attempts from the previous one.
        routeHosts?.sessionChanged(identity()?.sessionId)
        routeHosts?.routeChanged(initialRouteId)
        // DRAINED, NOT `.clear`. A repo guard forbids `.clear` anywhere in
        // this file and it is RIGHT to: `store.clear` on the durable queue is
        // the exact call that destroyed captured records, and a reviewer
        // scanning for it must not have to decide which `clear` is which. This
        // is a local queue of in-flight attempts, but the guard is a blunt
        // instrument on purpose — so drain it explicitly instead of weakening
        // the guard to admit a "safe" case.
        while (pendingOutcomes.poll() != null) { /* discard */ }
        attachDeadline?.cancel()
        return emitWith(id, "mobile_session_start", neverRecord = false) {
            buildMap {
                // Schema-REQUIRED fields (mobile_session_start.schema.json).
                // metadata_only is honest for RN v1; "manual" because the
                // public semantic operation is an explicit startSession call
                // and this bridge has no trustworthy native lifecycle signal
                // to claim cold/warm/hot — never fabricate precision the
                // producer does not actually know.
                put("captureMode", "metadata_only")
                put("startReason", "manual")
                // Canonical carrier for the opening route: the envelope
                // `screen` object — never a flat top-level screenId.
                if (initialRouteId != null) {
                    put("screen", RnEnvelope.ScreenRef(initialRouteId))
                }
            }
        }
    }

    /**
 * A route change.
 *
 * Only route IDENTIFIERS travel — never a resolved URL, never route
 * parameters. A deep link's parameters are exactly where customer data
 * (an order id, an email, a token) shows up, and a navigation event is not
 * a place anyone inspects for a privacy leak.
 */
    fun navigate(
        fromRouteId: String?,
        toRouteId: String,
        toRouteName: String?,
        transition: String,
    ): Result {
        if (!isSafeId(toRouteId)) return Result(Outcome.INVALID_INPUT)
        if (fromRouteId != null && !isSafeId(fromRouteId)) return Result(Outcome.INVALID_INPUT)
        if (toRouteName != null && !isSafeLabel(toRouteName)) return Result(Outcome.INVALID_INPUT)
        if (transition !in NAV_TRANSITIONS) return Result(Outcome.INVALID_INPUT)
        // RESOLVE BEFORE THE ROUTE MOVES. After `routeChanged` the old
        // attempt is stale by construction and can never emit — so a verdict
        // that was already due would be silently lost if we reordered these.
        resolvePendingCaptureOutcomes()
        currentRoute = toRouteId
        // A ROUTE CHANGE INVALIDATES EVERY IN-FLIGHT ATTEMPT. Without this a
        // verdict for the screen the user just left could be emitted against
        // the one they are now on.
        routeHosts?.routeChanged(toRouteId)
        // CANCEL *AND* DRAIN. Cancelling the timer does not retract a callback
        // already queued or running, and it does not remove the attempt from the
        // queue — so without the drain every abandoned attempt accumulated and
        // was re-evaluated forever. `isStale` is what makes the callback safe;
        // this is what keeps the queue bounded.
        attachDeadline?.cancel()
        dropStaleOutcomes()
        // an earlier change: the destination screen's last observed fact decides.
        return emit("mobile_navigation", neverRecord = false, webView = screenCarrier(toRouteId)) {
            buildMap {
                put("transition", transition)
                put("toScreenId", toRouteId)
                if (fromRouteId != null) put("fromScreenId", fromRouteId)
                if (toRouteName != null) put("toScreenName", toRouteName)
            }
        }
    }

    /**
 * A screen snapshot. With a native hierarchy provider wired this emits the
 * metadata snapshot AND a `mobile_view_hierarchy` event carrying the masked
 * wireframe tree; without one it stays the metadata-only contract.
 * `screenNeverRecord` separates "we chose not to" from "we could not" —
 * and a never-record route NEVER reaches the provider: the short-circuit is
 * before capture, so the tree is not traversed, not merely not sent.
 */
    // ── THE ROUTE-HOST INTEGRATION CONTRACT ────────────────────────────────
    // THESE ARE SEMANTIC DECLARATIONS BY THE HOST ABOUT ITSELF, and nothing
    // more. They carry no producer identity, no privacy state and no capture
    // control — a host may say "I am here", "I became ready" and "I failed",
    // and a host that says nothing leaves the outcome UNKNOWN.

    fun hostAttached(routeId: String, hostId: String, rootHandle: Int? = null): Outcome {
        if (!isSafeId(routeId) || !isSafeId(hostId)) return Outcome.INVALID_INPUT
        val r = routeHosts ?: return Outcome.NOT_INTEGRATED
        // THE CAPTURE ROOT IS A SEPARATE FACT FROM READINESS, AND IT IS THE
        // ONE THAT AUTHORIZES A WALK. A host may attach and declare readiness
        // without ever naming a root; it is then a legitimate participant on
        // the outcome axis and authorizes no capture at all, because nothing
        // establishes which nodes are its.
        r.hostAttached(routeId, hostId, rootHandle)
        return Outcome.RECORDED
    }

    /** The route's verified-at-capture-time capture root, if ownership can be
 * established. Null for an unintegrated app AND for an integrated route
 * whose ownership is absent or ambiguous — the caller must distinguish
 * those with [isIntegrated]. */
    fun boundRoot(routeId: String): com.tracescout.rn.RouteHostRegistry.BoundRoot? =
        routeHosts?.boundRoot(routeId)

    /** Is a route-host registry present at all? Separates legacy unintegrated
 * behaviour from an integrated app's unowned route. */
    fun isIntegrated(): Boolean = routeHosts != null

    fun bindingIsCurrent(routeId: String, b: com.tracescout.rn.RouteHostRegistry.BoundRoot): Boolean =
        routeHosts?.bindingIsCurrent(routeId, b) ?: false

    /**
 * The host declares it is PREPARING and WILL report readiness.
 *
 * THIS WAS MISSING FROM EVERY SURFACE while existing on the registry, so
 * the readiness CONTRACT — the thing that makes a timeout into
 * `route_surface_not_ready` — was unreachable from an application. The JS
 * wrapper named it, the registry implemented it, and nothing in between
 * carried it: the exact `lab_home` / `PostureProjectionTest` shape, where a
 * mechanism is guarded by tests that invoke it directly while nothing in the
 * app can reach it.
 */
    fun hostReadinessPending(routeId: String, hostId: String): Outcome {
        if (!isSafeId(routeId) || !isSafeId(hostId)) return Outcome.INVALID_INPUT
        val r = routeHosts ?: return Outcome.NOT_INTEGRATED
        r.hostReadinessPending(routeId, hostId)
        return Outcome.RECORDED
    }

    fun hostReady(routeId: String, hostId: String): Outcome {
        if (!isSafeId(routeId) || !isSafeId(hostId)) return Outcome.INVALID_INPUT
        val r = routeHosts ?: return Outcome.NOT_INTEGRATED
        r.hostReady(routeId, hostId)
        // READINESS IS AN ENTRY POINT. A queued attempt whose entry pass
        // captured nothing is waiting for exactly this declaration; draining
        // here lets the late capture land at readiness rather than only when
        // the attach deadline fires two seconds later.
        resolvePendingCaptureOutcomes()
        return Outcome.RECORDED
    }

    fun hostFailed(routeId: String, hostId: String): Outcome {
        if (!isSafeId(routeId) || !isSafeId(hostId)) return Outcome.INVALID_INPUT
        val r = routeHosts ?: return Outcome.NOT_INTEGRATED
        r.hostFailed(routeId, hostId)
        return Outcome.RECORDED
    }

    fun hostDetached(routeId: String, hostId: String): Outcome {
        if (!isSafeId(routeId) || !isSafeId(hostId)) return Outcome.INVALID_INPUT
        val r = routeHosts ?: return Outcome.NOT_INTEGRATED
        r.hostDetached(routeId, hostId)
        return Outcome.RECORDED
    }

    /**
 * One attempt still waiting out its attach budget.
 *
 * The pending entry keeps the ATTEMPT and its ROUTE — nothing else. No
 * snapshot identifier is retained: the deferred marker, if one is ever
 * emitted, mints its own. The wait is bounded by `AttachDeadline`, armed
 * once per attempt; until the budget genuinely elapses nothing is asserted.
 */
    private class Pending(
        val attempt: com.tracescout.rn.RouteHostRegistry.Attempt,
        val routeId: String,
        /**
 * Whether the ENTRY pass already produced content for this route. When it
 * did, the content is already out (unattributed) and a CAPTURE decision
 * at the drain only settles the attempt. When it did NOT, a CAPTURE
 * decision is a promise the drain must keep — see [LATE_CAPTURE_MAX_TRIES].
 */
        val capturedAtEntry: Boolean,
    ) {
        var lateTries: Int = 0
    }

    /**
 * A CAPTURE DECISION WITH NO CAPTURE IS A BLANK SCREEN, MEASURED.
 * vc16 Leg A (2026-09-23): `webview_help` entered, the entry walk found the
 * bound root UNRESOLVED (the host's native view was not yet resolvable
 * 3.9 ms after the push), the registry read readiness `null`, the attempt
 * was queued — and when the host declared ready the drain decided CAPTURE
 * and merely cancelled the attempt, because "content travels with a
 * capture" and nothing ever asked for one again (`useRouteCapture` issues
 * ONE snapshot per route entry). The stored screen was `metadata_only`
 * with no hierarchy, no blocked node and no reason — the misleading blank
 * WV10 exists to refuse. So a CAPTURE decision for an attempt whose entry
 * pass captured nothing now RE-ASKS the provider, at readiness or at the
 * deadline, at most this many times, and emits through the SAME tail the
 * entry path uses. Two tries: one at readiness, one at the deadline. When
 * BOTH stay empty the attempt is EXHAUSTED and that is disclosed with one
 * `route_surface_capture_empty` marker (see the drain) — never left as a
 * silent `metadata_only` blank.
 */

    /** Observability: late captures that produced content (diagnostic, never a decision input). */
    @Volatile var lateCaptures: Int = 0
        private set

    /**
 * Observability: attempts whose bounded late capture was EXHAUSTED and were
 * disclosed with one `route_surface_capture_empty` marker. Diagnostic, never
 * a decision input. Distinct from [droppedStaleOutcomes] on purpose: a
 * dropped attempt emitted NOTHING; an exhausted one emitted its verdict.
 */
    @Volatile var exhaustedLateCaptures: Int = 0
        private set

    private val pendingOutcomes = java.util.concurrent.ConcurrentLinkedQueue<Pending>()

    /** Observability: attempts dropped without emitting, and never silently. */
    @Volatile var droppedStaleOutcomes: Int = 0
        private set
    /** Diagnostics for tests: what is actually queued. Never a decision input. */
    fun pendingOutcomeCount(): Int = pendingOutcomes.size
    fun pendingAttemptIds(): List<String> = pendingOutcomes.map { it.attempt.attemptId }

    /**
 * Re-evaluate every waiting attempt and emit at most ONE marker for each.
 * Safe to call at any cadence: an attempt that is still inside its budget
 * stays queued, and one whose route or session moved on is dropped without
 * emitting — a verdict for a screen the user left is not a verdict.
 */
    fun resolvePendingCaptureOutcomes(): Int {
        val r = routeHosts ?: return 0
        var emitted = 0
        val keep = mutableListOf<Pending>()
        while (true) {
            val p = pendingOutcomes.poll() ?: break
            // PRIVACY IS RE-CHECKED AT EXECUTION, NOT ONLY AT QUEUEING.
            // A route can be declared never-record AFTER an attempt is queued;
            // emitting its marker afterwards would report on a screen the app
            // has since forbidden. Never-record outranks the whole axis, so the
            // attempt is dropped outright rather than merely refused.
            if (p.routeId in neverRecordRoutes) {
                r.cancel(p.attempt); droppedStaleOutcomes++; continue
            }
            trace?.invoke("rh_callback_entry id=${p.attempt.attemptId} route=${p.routeId} stale=${r.isStale(p.attempt)} settled=${p.attempt.isSettled()}")
            val host = r.evaluate(p.attempt)
            val d = com.tracescout.rn.CaptureOutcome.decide(neverRecord = false, host = host)
            when (d.decision) {
                com.tracescout.rn.CaptureOutcome.Decision.UNAVAILABLE -> {
                    // THE EXACTLY-ONCE GATE IS THE REGISTRY'S, NOT A LOCAL FLAG.
                    // A local boolean would be per-queue-entry; this is per
                    // ATTEMPT, so a requeue, a racing caller and a retry all
                    // collapse to one emission.
                    if (r.claimResolution(p.attempt)) {
                        val res = emitUnavailableMarker(p.routeId, d.captureUnavailableReason!!)
                        trace?.invoke("rh_emitted id=${p.attempt.attemptId} reason=${d.captureUnavailableReason} outcome=${res.outcome}")
                        emitted++
                    } else {
                        trace?.invoke("rh_claim_refused id=${p.attempt.attemptId} — already resolved or stale")
                    }
                }
                com.tracescout.rn.CaptureOutcome.Decision.NO_EMISSION -> {
                    // DROPPED IF STALE, NOT RE-QUEUED. `evaluate` correctly
                    // REFUSES a stale attempt — but refusing is not discarding,
                    // and "not settled" is true of a stale attempt forever. The
                    // previous version therefore kept every abandoned attempt in
                    // the queue for the life of the process: unbounded growth,
                    // and a queue that never empties so the deadline was never
                    // cancelled. Ask the registry the identity question
                    // explicitly.
                    if (!p.attempt.isSettled() && !r.isStale(p.attempt)) {
                        keep.add(p)
                        trace?.invoke("rh_still_waiting id=${p.attempt.attemptId} decision=${d.decision}")
                    } else {
                        droppedStaleOutcomes++
                        trace?.invoke("rh_dropped id=${p.attempt.attemptId} settled=${p.attempt.isSettled()} stale=${r.isStale(p.attempt)}")
                    }
                }
                else -> {
                    // CAPTURE DISCOVERED BY THE DRAIN SETTLES THE ATTEMPT — AND, IF
                    // THE ENTRY PASS CAPTURED NOTHING, IT MUST ALSO CAPTURE. The host
                    // became ready with no capture call in between; "content travels
                    // with a capture" was true only when the entry pass had content.
                    // Dropping the queue entry alone left the attempt OPEN in the
                    // registry, and the next capture then coalesced onto an
                    // already-expired budget and reported not_attached at once.
                    if (d.decision == com.tracescout.rn.CaptureOutcome.Decision.CAPTURE &&
                        !p.capturedAtEntry && p.lateTries < LATE_CAPTURE_MAX_TRIES) {
                        p.lateTries++
                        val late = hierarchy?.invoke(p.routeId)
                        val cap = late?.capture
                        if (cap != null) {
                            val res = emitScreenRecords(p.routeId, never = false, cap = cap, unavailableReason = null)
                            r.cancel(p.attempt)
                            lateCaptures++
                            trace?.invoke("rh_late_capture id=${p.attempt.attemptId} route=${p.routeId} try=${p.lateTries} outcome=${res.outcome}")
                        } else if (p.lateTries < LATE_CAPTURE_MAX_TRIES && !p.attempt.isSettled() && !r.isStale(p.attempt)) {
                            // Nothing yet — the host declared ready but the walk still
                            // produced no content. Keep waiting; the deadline (or the
                            // next entry point) gets the last try.
                            keep.add(p)
                            trace?.invoke("rh_late_capture_empty id=${p.attempt.attemptId} route=${p.routeId} try=${p.lateTries} — kept for one more try")
                        } else if (p.lateTries >= LATE_CAPTURE_MAX_TRIES && !r.isStale(p.attempt)) {
                            // EXHAUSTED — AND DISCLOSED, NOT SETTLED IN SILENCE.
                            // Measured on the sealed vc17 tree: both late walks empty
                            // left the route with ONLY its entry `metadata_only`
                            // snapshot and no explanation — the blank WV10 exists to
                            // refuse, bounded to this case. A ready host that stayed
                            // empty is NEITHER `not_attached` (a host existed) NOR
                            // `not_ready` (it declared ready, reported nothing), so
                            // it carries its own protocol value. Attribution is the
                            // attempt's: `isStale` proved the session AND route are
                            // still current, `claimResolution` is the registry's
                            // exactly-once gate (a requeue, a racing entry point or a
                            // second deadline fire cannot produce a second marker),
                            // and the marker goes through the SAME emitter the other
                            // two reasons use — metadata_only, no counters, trigger
                            // unchanged. Never-record was checked at the top of this
                            // loop; a route the user left never reaches here.
                            if (r.claimResolution(p.attempt)) {
                                val res = emitUnavailableMarker(p.routeId, com.tracescout.rn.CaptureOutcome.CAPTURE_EMPTY)
                                if (res.outcome == Outcome.RECORDED) exhaustedLateCaptures++
                                emitted++
                                trace?.invoke("rh_late_capture_exhausted id=${p.attempt.attemptId} route=${p.routeId} try=${p.lateTries} reason=${com.tracescout.rn.CaptureOutcome.CAPTURE_EMPTY} outcome=${res.outcome} seq=${res.sequence}")
                            } else {
                                trace?.invoke("rh_claim_refused id=${p.attempt.attemptId} — exhausted but already resolved")
                            }
                        } else {
                            // Not exhausted yet but no longer current (session or route
                            // moved on between the decision and here): a verdict for a
                            // screen the user left is not a verdict.
                            r.cancel(p.attempt); droppedStaleOutcomes++
                            trace?.invoke("rh_late_capture_empty id=${p.attempt.attemptId} route=${p.routeId} try=${p.lateTries} — stale, dropped without content")
                        }
                    } else {
                        r.cancel(p.attempt)
                        trace?.invoke("rh_settled_by_drain id=${p.attempt.attemptId} decision=${d.decision} capturedAtEntry=${p.capturedAtEntry}")
                    }
                }
            }
        }
        pendingOutcomes.addAll(keep)
        // Nothing left to wait for: the deadline is cancelled rather than left
        // to fire into an empty queue.
        if (keep.isEmpty()) attachDeadline?.cancel()
        return emitted
    }

    /**
 * Drop every attempt that can no longer produce a verdict for the world the
 * app is now in. Cancellation of a timer does not do this; only asking the
 * registry does.
 */
    private fun dropStaleOutcomes() {
        val r = routeHosts ?: return
        val keep = mutableListOf<Pending>()
        while (true) {
            val p = pendingOutcomes.poll() ?: break
            if (!p.attempt.isSettled() && !r.isStale(p.attempt)) keep.add(p)
            else { r.cancel(p.attempt); droppedStaleOutcomes++ }
        }
        pendingOutcomes.addAll(keep)
    }

    /**
 * The marker, emitted on its own when the verdict arrives AFTER the snapshot.
 *
 * THIS IS A SECOND EVENT, AND THAT IS DELIBERATE — BUT NOT A DUPLICATE
 * SNAPSHOT. The distinction is exact:
 *
 * - the ORDINARY snapshot is emitted once per `screenSnapshot` call, as
 * it always has been, carrying the trigger and the screen identity;
 * - the OUTCOME is emitted at most once per ATTEMPT, gated by the
 * registry's `claimResolution`.
 *
 * A verdict that is not yet known at snapshot time cannot be back-dated into
 * an event already appended — the durable sink has no un-append, which is
 * the same property that makes the upload pipeline safe. So the honest
 * choices are to withhold the snapshot until the budget elapses (delaying
 * the screen record and its trigger, which the server needs) or to report
 * the outcome when it is actually known. The server models exactly this:
 * `mobile_screens` carries `metadata_only_at_sequence` and
 * `unavailable_at_sequence` as SEPARATE marks on one screen, so a later
 * outcome refines a screen rather than duplicating it.
 *
 * The invariant that matters, and that the tests assert: for one attempt, AT
 * MOST ONE event ever carries `captureUnavailableReason`.
 */
    private fun emitUnavailableMarker(routeId: String, reason: String): Result =
        emit("mobile_screen_snapshot", neverRecord = false, webView = screenCarrier(routeId)) {
            buildMap {
                put("screen", RnEnvelope.ScreenRef(routeId))
                put("snapshotId", newSnapshotId())
                // The TRIGGER stays the trigger. This marker exists because a
                // screen was entered; the failure axis is a separate field.
                put("reason", "screen_entered")
                put("captureMode", "metadata_only")
                put("captureUnavailableReason", reason)
                // No counters: nothing was walked, and 0 is a measurement.
            }
        }

    fun screenSnapshot(routeId: String): Result {
        if (!isSafeId(routeId)) return Result(Outcome.INVALID_INPUT)
        // SETTLE PRIOR ATTEMPTS FIRST. Every public entry point re-evaluates
        // waiting attempts, AND the attach deadline (`AttachDeadline`, the
        // platform's own scheduler) fires the same drain when the budget
        // elapses — so an app that goes idle after entering a screen still
        // learns the verdict on time.
        resolvePendingCaptureOutcomes()
        currentRoute = routeId
        val never = routeId in neverRecordRoutes
        // the specification Never-Record short-circuit BEFORE traversal; background/no-window
        // inside the provider returns null — both fold to "no capture".
        // ── THE ROUTE-HOST FACTS, ASKED BEFORE THE WALK ────────────────────
        // A never-record route never reaches the registry, exactly as it
        // never reaches the provider: an intentional exclusion is decided first
        // and must not become a capture-failure verdict.
        val attempt = if (never) null
                      else routeHosts?.let { r -> identity()?.let { r.beginAttempt(routeId, it.sessionId) } }
        val hostFacts = attempt?.let { routeHosts!!.evaluate(it) }
            ?: com.tracescout.rn.CaptureOutcome.RouteHost.UNKNOWN
        val hostDecision = com.tracescout.rn.CaptureOutcome.decide(neverRecord = never, host = hostFacts)
        trace?.invoke(
            "rh_attempt id=${attempt?.attemptId ?: "<none>"} route=$routeId never=$never " +
            "registry=${routeHosts != null} identity=${identity() != null} decision=${hostDecision.decision}")

        val outcome: HierarchyOutcome =
            if (never || hierarchy == null) HierarchyOutcome() else hierarchy.invoke(routeId)
        val cap: HierarchyCapture? = outcome.capture
        // THE REASON IS THE PROVIDER'S OBSERVATION, NEVER THE BRIDGE'S
        // INFERENCE. The bridge cannot see a surface, so it must not deduce a
        // cause from the absence of a capture — "no capture" has seven sources
        // here and only two of them are protocol conditions. A never-record
        // route never reaches the provider at all, so it can never carry one.
        // THE REGISTRY IS THE ONLY SOURCE OF A REASON. The provider reports a
        // capture or its absence; it has no route-host facts and therefore no
        // standing to name a cause. Its `captureUnavailableReason` is retained
        // in the type for a platform that one day DOES observe the protocol
        // conditions natively, and is deliberately not consulted here.
        var unavailableReason: String? = null
        if (!never && attempt != null) {
            when (hostDecision.decision) {
                com.tracescout.rn.CaptureOutcome.Decision.UNAVAILABLE ->
                    // Verdict already available at snapshot time.
                    if (routeHosts!!.claimResolution(attempt)) {
                        unavailableReason = hostDecision.captureUnavailableReason
                    }
                com.tracescout.rn.CaptureOutcome.Decision.NO_EMISSION ->
                    // AN UNATTRIBUTED CAPTURE MUST NOT SETTLE THE ATTEMPT.
                    // This branch used to read `if (cap == null)` — i.e. a
                    // capture of ANY content cancelled the attempt as a success.
                    // But the RN walker walks the ACTIVITY'S DECOR VIEW, the
                    // whole application window, and the routeId is only a LABEL.
                    // Measured on device: asking for `rh_foreign_unrendered`, a
                    // route NEVER rendered and with no host, returned 70 nodes
                    // of whatever the window was showing — and that settled the
                    // attempt, suppressing any verdict about the route actually
                    // requested.
                    //
                    // Ownership is established ONLY when the shared rule says
                    // CAPTURE, which on this platform means a qualifying host
                    // declared itself ready. That case is the `else ->` branch
                    // below. Here ownership is UNKNOWN, so the capture is
                    // emitted as content exactly as before — nothing is dropped,
                    // no privacy behaviour changes — while the attempt keeps
                    // running its budget and the registry, not the window,
                    // decides.
                    //
                    // AN UNINTEGRATED APP IS UNAFFECTED: with no registry
                    // `attempt` is null and this block is never entered.
                    if (true) {
                        if (pendingOutcomes.any { it.attempt === attempt }) {
                            // AN ATTEMPT ALREADY QUEUED AND ALREADY ARMED (reused by the registry).
                            // Queueing it again would drain it twice; arming again
                            // would RESET the deadline and extend the budget by
                            // exactly the capture cadence — forever, under a
                            // periodic snapshot. Neither is allowed to happen.
                            trace?.invoke("rh_attempt_reused id=${attempt.attemptId} route=$routeId queue=${pendingOutcomes.size}")
                        } else {
                            pendingOutcomes.add(Pending(attempt, routeId, capturedAtEntry = cap != null))
                            trace?.invoke("rh_queued id=${attempt.attemptId} route=$routeId budget=$attachBudgetMs")
                            // ARM A REAL DEADLINE. The queue alone would only be
                            // drained by the NEXT bridge call, so an app that goes
                            // idle here would never learn the budget elapsed. The
                            // deadline fires on the platform's own scheduler and
                            // needs no navigation, no lifecycle event and no tick.
                            if (attachDeadline == null) {
                                trace?.invoke("rh_no_deadline_owner id=${attempt.attemptId} — the wait is unbounded")
                            } else {
                                attachDeadline.arm(attachBudgetMs) {
                                    trace?.invoke("rh_deadline_fired id=${attempt.attemptId}")
                                    resolvePendingCaptureOutcomes()
                                }
                                trace?.invoke("rh_deadline_armed id=${attempt.attemptId} inMs=$attachBudgetMs")
                        }
                        }
                    }
                else ->
                    // A capture happened, or never-record: nothing to wait for.
                    routeHosts!!.cancel(attempt)
            }
        }
        return emitScreenRecords(routeId, never, cap, unavailableReason)
    }

    /**
 * ONE EMITTER FOR A CAPTURED (OR EMPTY) SCREEN, shared by the entry pass in
 * [screenSnapshot] and by the drain's late capture. A second copy that "does
 * the same thing" is how a late capture would start describing something the
 * entry path does not emit — frame policy, counters, the failure axis and the
 * hierarchy companion all live here, once.
 */
    private fun emitScreenRecords(
        routeId: String,
        never: Boolean,
        cap: HierarchyCapture?,
        unavailableReason: String?,
    ): Result {
        // Phase 2: a frame is considered ONLY with a hierarchy to redact from,
        // never for a never-record route, and inside the pure budgets. The
        // native provider applies opt-in/foreground/secure-window itself and
        // may still refuse (null) — then the snapshot honestly stays
        // wireframe_only.
        val fr: FrameCapture? = run {
            val f = frame ?: return@run null
            val h = cap?.result ?: return@run null
            val now = clock.wallMillis()
            val since = if (lastFrameWallMs < 0) -1L else now - lastFrameWallMs
            val d = com.tracescout.rn.FramePolicy.decide(
                enabled = true, neverRecord = never, foreground = true, windowSecure = false,
                hierarchy = h, sinceLastFrameMs = since, framesThisSession = framesThisSession,
            )
            if (!d.capture) return@run null
            f.invoke(routeId, h)?.also { lastFrameWallMs = now; framesThisSession += 1 }
        }
        val mode = when {
            fr != null -> com.tracescout.rn.FrameBudgets.CAPTURE_MODE_WITH_FRAMES
            cap != null -> "wireframe_only"
            else -> "metadata_only"
        }
        // The failure axis never rewrites the capture axis: an unavailable
        // screen is metadata_only, which is what it already was.
        // an earlier change: THIS capture is the newest observation of the screen, so it is
        // recorded before the snapshot asks for its carrier.
        observeScreen(routeId, never, cap)
        val snap = emit("mobile_screen_snapshot", neverRecord = never, webView = screenCarrier(routeId)) {
            buildMap {
                // Canonical nested screen identity — the flat top-level
                // screenId was a non-schema alias the server's mobile_screens
                // writer (correctly) never read, so RN sessions stored chunks
                // that never materialized a screen row.
                put("screen", RnEnvelope.ScreenRef(routeId))
                // Schema-REQUIRED for mobile_screen_snapshot.
                put("snapshotId", newSnapshotId())
                put("reason", "screen_entered")
                put("captureMode", mode)
                // The ingestion folds these into mobile_screens counters —
                // derived from the SAME HierarchyResult the nodes come from,
                // so the counters and the payload cannot disagree. Absent in
                // metadata_only: a snapshot that captured nothing counts
                // nothing.
                if (cap != null) {
                    put("wireframeNodeCount", cap.result.wireframeNodeCount)
                    put("blockedNodeCount", cap.result.blockedCount)
                }
                // THE FAILURE AXIS, AND IT CARRIES NO COUNTERS.
                // The server detects counters by OWNERSHIP
                // (capture_unavailable_reason_with_counters), so emitting them
                // as 0 is emitting them — and a marker claiming the content
                // could not be captured while reporting how many nodes it
                // walked is not interpretable. `cap` is NOT null by construction
                // here: a bound root and a host that already reported failure
                // produce BOTH, so the reason is attached only when nothing was
                // captured; with content it is reported SEPARATELY, in its own
                // marker, after the content.
                if (unavailableReason != null && cap == null) put("captureUnavailableReason", unavailableReason)
            }
        }
        if (snap.outcome != Outcome.RECORDED || cap == null) return snap
        val r = emitHierarchy(routeId, cap, snap)
        if (fr != null) emitFrameEvent(routeId, fr)
        // VALID CONTENT AND A KNOWN FAILURE COEXIST AS TWO RECORDS. The
        // content above is preserved exactly; the outcome is a metadata-only
        // marker with its own identifiers and no counters, so the ingest
        // contract (a reason never travels with counters) holds for both.
        val ur = unavailableReason
        if (ur != null) {
            val m = emitUnavailableMarker(routeId, ur)
            trace?.invoke("rh_emitted_alongside_content route=$routeId reason=$ur outcome=${m.outcome}")
        }
        return r
    }

    /** The `mobile_visual_frame` METADATA event; the bytes travel separately. */
    private fun emitFrameEvent(routeId: String, fr: FrameCapture) {
        val id = identity() ?: return
        val wall = clock.wallMillis(); val mono = clock.monotonicNanos()
        val eventId = ids.newEventId()
        val posture = RnEnvelope.Posture(allowedViewCount = 0, screenNeverRecord = false)
        sink.enqueue { assigned ->
            RnEnvelope.visualFrame(
                id, eventId, assigned, wall, mono, posture, fr.visualFrameId, routeId,
                fr.contentHash, fr.width, fr.height, fr.byteSize, fr.format, fr.quality, fr.downscale,
            ).toByteArray(Charsets.UTF_8)
        }
    }

    /**
 * The hierarchy companion to a wireframe snapshot. A hierarchy entry above
 * the size budget is DROPPED with the snapshot kept — a partial tree that
 * lies about its own count is worse than an honest metadata event.
 */
    private fun emitHierarchy(routeId: String, capture: HierarchyCapture, snap: Result): Result {
        val id = identity() ?: return snap
        val wall = clock.wallMillis()
        val mono = clock.monotonicNanos()
        val eventId = ids.newEventId()
        val snapshotId = newSnapshotId()
        // THIS EVENT's derived count — the same HierarchyResult the nodes come
        // from, so the scalar and the payload cannot disagree.
        val posture = RnEnvelope.Posture(
            allowedViewCount = capture.result.allowedViewCount,
            screenNeverRecord = false,
        )
        // Budget check BEFORE the durable append: the sink has no un-append,
        // so a veto after enqueue is impossible. The probe build uses sequence
        // 0 — the real sequence changes the byte count by at most a few digits,
        // which the 512-byte margin absorbs.
        // MODEL B — asked ONCE, and the SAME value goes into the probe and into
        // the durable bytes. Asking twice could size the budget against a
        // carrier the real event does not have (or the reverse), and the
        // 512-byte margin is not the place to absorb a key appearing between
        // two reads.
        // an earlier change: carried only when THIS capture saw web content. The local
        // deliberately SHADOWS the property so the one read below is the only
        // place this event asks for its carrier.
        val webViewRef = screenCarrierFor(capture.result.webViewSurfaceCount > 0, webViewRef)
        val wv = webViewRef?.invoke()
        val probe = RnEnvelope.viewHierarchy(
            id, eventId, 0L, wall, mono, posture, snapshotId, routeId,
            capture.result, capture.durationMs, wv,
        )
        if (probe.toByteArray(Charsets.UTF_8).size > 65_536 - 512) return snap
        sink.enqueue { assigned ->
            RnEnvelope.viewHierarchy(
                id, eventId, assigned, wall, mono, posture, snapshotId, routeId,
                capture.result, capture.durationMs, wv,
            ).toByteArray(Charsets.UTF_8)
        }
        return snap
    }

    /**
 * Backgrounding is NOT a navigation and must never be recorded as one —
 * a session that reads as a route change on every app switch produces
 * journeys the user never took.
 */
    fun lifecycle(transition: String): Result {
        resolvePendingCaptureOutcomes()
        // BACKGROUNDING ABANDONS AN IN-FLIGHT ATTEMPT, IT DOES NOT TIME IT OUT.
        // The attach budget is about an app actively trying to show a route.
        // Once the app is backgrounded, "no host appeared" stopped being
        // evidence about the ROUTE and became evidence about the APP — so a
        // verdict drawn from it would describe the wrong subject.
        if (transition == "background") {
            attachDeadline?.cancel()
            var p = pendingOutcomes.poll()
            while (p != null) { routeHosts?.cancel(p.attempt); droppedStaleOutcomes++; p = pendingOutcomes.poll() }
        }
        if (transition !in LIFECYCLE_TRANSITIONS) return Result(Outcome.INVALID_INPUT)
        val r = emit("mobile_lifecycle", neverRecord = false) {
            mapOf("transition" to transition, "appState" to transition)
        }
        // Backgrounding is the moment a mobile session is most likely to end
        // without warning, so it is the moment at which un-uploaded records are
        // most likely to be lost. The hint is emitted only AFTER the record is
        // durable — hinting first would ask for a drain of a queue that does
        // not yet contain the event that prompted it.
        if (r.outcome == Outcome.RECORDED && transition in FLUSH_TRANSITIONS) {
            runCatching { sink.flushHint("app_$transition") }
        }
        return r
    }

    /**
 * **The host declares this session OVER.** Board ruling #2760's "definitive
 * session finalization", expressed as the one thing JS is allowed to say
 * about it.
 *
 * **THIS IS NOT [lifecycle], AND THE TWO MUST NEVER BE WIRED
 * TOGETHER.** `lifecycle("background")` is the moment a session is most
 * likely to end — which is exactly why it is not evidence that it DID.
 * Backgrounding is resumable; terminality is one-way on the server, so a
 * session reported final cannot be un-reported. The app going away, the
 * React instance tearing down, a `flushHint`, a drained queue and process
 * death are all NOT this signal. Only the host saying so is, and the host
 * has to call this to say it.
 *
 * **THE LIVE SESSION, AND NOTHING ELSE.** The id comes from the SAME
 * [identity] supplier every emit uses — never from a parameter — so a JS
 * caller cannot name a session it is not running, including an inherited
 * one whose records are still in the queue. That is the structural
 * equivalent of the native seam taking no session argument at all; this
 * producer cannot copy that literally, because here the identity is
 * supplied rather than held.
 *
 * **IT EMITS NO EVENT, and that is the whole point.** The protocol has a
 * `mobile_session_end` event and appending one here would put bytes in the
 * queue, so the drain that followed would carry a chunk — and a chunk is
 * the case the R-2 ruling does NOT cover. The zero-chunk finality
 * checkpoint exists for sessions that durably emitted nothing; a
 * declaration that manufactured a record would make that case unreachable
 * by construction. "No synthetic replay content" is the ruling's own
 * wording for the same rule.
 *
 * Idempotent: a second call is [Outcome.ALREADY_RECORDED]. There is no
 * inverse. Never throws into the host app.
 */
    fun endSessionAuthoritatively(): Result {
        // `identity`, NEVER `beginSession`. A session that exists because
        // its own END was the first caller has no `mobile_session_start`, which
        // the server refuses to originate — and declaring a session over is the
        // one call for which creating one would be absurd on its face.
        val id = identity() ?: return Result(Outcome.NO_SESSION)
        val name = try {
            sink.finalizeSession(id.sessionId)
        } catch (_: Throwable) {
            // The transport is documented never to throw; if it does, the host
            // app still must not. A failure to record is REFUSED, never a
            // silent RECORDED.
            return Result(Outcome.REFUSED)
        }
        return Result(
            when (name) {
                "RECORDED" -> Outcome.RECORDED
                "ALREADY_RECORDED" -> Outcome.ALREADY_RECORDED
                "NO_SESSION" -> Outcome.NO_SESSION
                // NOT_DURABLE AND REFUSED ARE FOLDED, AND ALREADY_RECORDED IS
                // NOT — the vocabulary separates outcomes a CALLER ACTS ON
                // DIFFERENTLY, not every outcome the transport can name.
                // "could not be made durable" and "the durable path declined"
                // are both "it did not happen, try later"; `RECORDED` and
                // `ALREADY_RECORDED` are both successes whose difference tells
                // a caller whether its retry was the one that counted.
                "NOT_DURABLE", "REFUSED" -> Outcome.REFUSED
                // An unknown name can only mean the transport's vocabulary
                // moved. Fail CLOSED: report that it did not happen rather than
                // inferring success from a string this version does not know.
                else -> Outcome.REFUSED
            },
        )
    }

    /** Safe status for the host app. Never queue contents, never a credential. */
    fun state(): String = sink.stateName()

    // ----------------------------------------------------------------- private

    private fun emit(
        eventType: String,
        neverRecord: Boolean,
        webView: (() -> RnEnvelope.WebViewRef?)? = null,
        extra: () -> Map<String, Any?>,
    ): Result {
        val id = identity()
            ?: return Result(if (configured()) Outcome.NO_SESSION else Outcome.NOT_CONFIGURED)
        return emitWith(id, eventType, neverRecord, webView, extra)
    }

    /**
 * an earlier change — the SCREEN-level carrier gate, the twin of iOS
 * `RnBridge.screenCarrierFor`. Separate from the interaction gate
 * (`RnInteractionCapture.carrierFor`): a tap is decided by where the finger
 * landed, a screen by what it contains. `null` (never observed) is NOT web
 * content.
 */
    private fun observeScreen(routeId: String, never: Boolean, cap: HierarchyCapture?) {
        if (never) { screenWebContent[routeId] = false; return }
        if (cap != null) screenWebContent[routeId] = cap.result.webViewSurfaceCount > 0
    }

    private fun screenCarrier(routeId: String): (() -> RnEnvelope.WebViewRef?)? =
        screenCarrierFor(screenWebContent[routeId], webViewRef)

    /** Diagnostics/tests: the last observed web-content fact for a route. */
    fun observedScreenHoldsWebContent(routeId: String): Boolean? = screenWebContent[routeId]

    private fun emitWith(
        id: RnEnvelope.Identity,
        eventType: String,
        neverRecord: Boolean,
        webView: (() -> RnEnvelope.WebViewRef?)? = null,
        extra: () -> Map<String, Any?>,
    ): Result {
        val body = extra()
        // The clocks are read ONCE, here, and the same values go into the bytes
        // whichever sequence the durable path assigns. Reading them inside the
        // builder would make a retry of the same logical event carry a
        // different timestamp than the one the caller observed.
        val wall = clock.wallMillis()
        val mono = clock.monotonicNanos()
        val eventId = ids.newEventId()
        // MODEL B — asked ONCE per event, HERE, beside the clocks and for the
        // same reason: the value that goes into the bytes must be the one
        // observed at the moment the event was emitted, whichever sequence the
        // durable path later assigns. Asking inside the builder would let a
        // retry of the same logical event carry a correlation the original did
        // not have — or lose one it did.
        // an earlier change: every event names its own gated provider;
        // session_start and lifecycle pass none, so nothing inherits the
        // session's carrier by default.
        val webViewRef = webView
        val wv = webViewRef?.invoke()
        val seq = sink.enqueue { assigned ->
            RnEnvelope.envelope(
                id = id,
                eventId = eventId,
                eventType = eventType,
                // The assigned value, not a local counter. This is the whole
                // point of the builder handoff.
                sequence = assigned,
                timestampMillis = wall,
                monotonicNanos = mono,
                posture = RnEnvelope.Posture(
                    // Strict defaults; a metadata-only record allows no views.
                    allowedViewCount = 0,
                    screenNeverRecord = neverRecord,
                ),
                extra = body,
                webView = wv,
            ).toByteArray(Charsets.UTF_8)
        }
        return if (seq < 0) Result(Outcome.REFUSED)
        else Result(Outcome.RECORDED, seq, neverRecord)
    }

    private companion object {
        /** Late-capture tries per attempt: one at readiness, one at the deadline. */
        const val LATE_CAPTURE_MAX_TRIES = 2
        /**
 * Bounded and closed. Every string that reaches the wire from JS is
 * matched against this; the protocol declares no unbounded string, and
 * an identifier that admits arbitrary characters is how a URL, a query
 * string or a token arrives in a field nobody audits.
 */
        val SAFE_ID = Regex("""[A-Za-z0-9_.:/-]{1,128}""")
        val SAFE_LABEL = Regex("""[\p{L}\p{N} _.:/-]{1,128}""")

        val NAV_TRANSITIONS = setOf(
            "push", "pop", "replace", "reset", "tab_change", "deep_link",
        )
        val LIFECYCLE_TRANSITIONS = setOf(
            "foreground", "background", "active", "inactive",
        )

        /**
 * The lifecycle moments that warrant a drain hint. Foregrounding is
 * deliberately absent: the session is continuing, nothing is at risk,
 * and hinting on every app switch would double the scheduling for no
 * change in what survives.
 */
        val FLUSH_TRANSITIONS = setOf("background", "inactive")
    }

    private fun isSafeId(s: String) = SAFE_ID.matches(s)
    private fun isSafeLabel(s: String) = SAFE_LABEL.matches(s)
}

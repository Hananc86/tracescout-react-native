package com.tracescout.mobile.rn

import android.content.Context
import com.tracescout.mobile.queue.RecoveryTrace
import com.tracescout.rn.NetworkBudget
import com.tracescout.rn.NetworkCaptureCore
import com.tracescout.rn.RnEnvelope
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * M3.3 — the NARROW host the OkHttp adapter is given.
 *
 * THIS IS PLUMBING, NOT A SECOND DECISION ENGINE. Every rule about what may
 * be captured lives in [NetworkCaptureCore] — the opt-in's meaning,
 * self-exclusion, URL normalization, failure classification, the field set,
 * the budget arithmetic. This class only *fetches* the facts the core asks for
 * and *carries* the finished event to the durable path. A rule implemented
 * here would be a rule reachable only from a device, which is how untested
 * rules happen.
 *
 * **What it can reach, exhaustively:** the persisted opt-in, whether a session
 * is live, the current route id, both clocks, a freshly minted request id, the
 * budget, the durable queue, and a bounded checkpoint writer.
 *
 * **What it cannot reach, by construction:** the ingest credential (it never
 * touches `RnSecureConfig.load`, only the two boolean/status accessors),
 * request or response headers, body bytes, and the raw URL — which exists only
 * as an argument inside the core and is never handed to this class in any form.
 * There is no generic logger: [callbackFailed] takes a closed enum and
 * [refused] takes a reason produced by the core's own [NetworkCaptureCore.Refusal],
 * so no call site can pass a free-form string.
 *
 * @param identity the CURRENT session, or null. Must never create one — a
 * session with no `mobile_session_start` opener is permanently refused by the
 * server and its records park at the head of the queue.
 * @param screen the route to attribute a request to, resolved at request START.
 * @param budget stated by the caller with NO default. The limits are measured
 * on the fixture and frozen afterwards; a default here would be a product
 * limit chosen by whoever wrote this file.
 */
class RnNetworkHost(
    context: Context,
    private val identity: () -> RnEnvelope.Identity?,
    private val screen: () -> String?,
    private val gesture: () -> String? = { null },
    private val budget: NetworkBudget,
) : RnNetworkCapture.Host {

    private val app: Context = context.applicationContext

    /**
 * Refusal reasons already recorded in THIS process.
 *
 * A checkpoint per refused request would write a trace line for every
 * HTTP request a customer's app makes while capture is off — real I/O on
 * the network callback thread, for a fact that is identical every time.
 * The first occurrence of each reason is what carries the information
 * ("the seam ran and refused, for this reason"); the rest are repetition.
 *
 * The consequence, stated rather than hidden: a gate proving the disabled
 * state must COLD-LAUNCH between legs, because a second leg in the same
 * process will legitimately find the reason already recorded.
 */
    private val refusalsSeen = ConcurrentHashMap.newKeySet<String>()

    // ---------------------------------------------------------------- facts

    /**
 * The persisted client opt-in. Read PER REQUEST, deliberately: a user who
 * turns capture off expects the next request not to be captured, not the
 * next process to stop capturing.
 */
    override fun captureEnabled(): Boolean = try {
        RnSecureConfig.networkCaptureEnabled(app)
    } catch (_: Throwable) {
        // Unreadable preferences are not evidence of consent.
        false
    }

    override fun hasSession(): Boolean = identity() != null

    override fun currentScreenId(): String? = try { screen() } catch (_: Throwable) { null }

    override fun currentGestureId(): String? = try { gesture() } catch (_: Throwable) { null }

    /** Native, unguessable, per request. JS can neither supply nor observe it. */
    override fun newNetworkRequestId(): String =
        "nrq_" + UUID.randomUUID().toString().replace("-", "").take(24)

    override fun nowWallMillis(): Long = System.currentTimeMillis()

    override fun nowMonotonicNanos(): Long = System.nanoTime()

    override fun budget(): NetworkBudget = budget

    // ------------------------------------------------------------- outcomes

    /**
 * A completed event reaches the durable queue.
 *
 * MUST NOT throw: this runs on OkHttp's callback thread inside the
 * application's own request. Losing one telemetry event is a different
 * outcome from the customer's request failing because of our observability.
 */
    override fun emit(event: NetworkCaptureCore.NetworkEvent) {
        try {
            val id = identity()
            if (id == null) {
                // The session ended between request start and completion. The
                // event cannot be addressed, so it is dropped rather than
                // queued: a record whose session has no opener can never be
                // accepted and would park at the head of the queue.
                checkpoint(event, emitted = false, drop = "no_session")
                return
            }
            val eventId = "evt_" + UUID.randomUUID().toString().replace("-", "")
            val wall = nowWallMillis()
            val mono = nowMonotonicNanos()

            // MEASURED WITH A PLACEHOLDER ORDERING VALUE. The sequence is
            // assigned inside the durable path and stamped into the bytes it
            // orders, so the finished size is not knowable before the append.
            // Sequence digits are the only difference between this probe and
            // the record, so the measurement is exact to within the width of
            // one integer — and an event whose admission turned on nineteen
            // characters was never safely inside the cap.
            val probe = bytes(id, eventId, 0L, wall, mono, event)
            if (!budget.admitSerialized(probe.size)) {
                checkpoint(event, emitted = false, drop = "serialized_too_large")
                return
            }

            val seq = RnTransport.enqueue(app) { assigned ->
                bytes(id, eventId, assigned, wall, mono, event)
            }
            checkpoint(event, emitted = seq >= 0, drop = if (seq >= 0) null else "queue_refused")
        } catch (_: Throwable) {
            checkpoint(event, emitted = false, drop = "emit_failed")
        }
    }

    /**
 * The core declined to create a start context. Observational only — the
 * reason comes from [NetworkCaptureCore.Refusal], never from a caller.
 *
 * This exists so a DISABLED state is distinguishable from a BROKEN seam. A
 * host that recorded nothing when capture is off makes "the listener ran
 * and refused" look exactly like "the listener was never installed", which
 * is the failure this programme keeps paying for.
 *
 * `reason` is read from the enum, never from a caller — the Host has no
 * method that accepts a free-form string.
 */
    override fun refused(refusal: NetworkCaptureCore.Refusal) {
        try {
            val reason = refusal.reason
            if (!refusalsSeen.add(reason)) return
            RecoveryTrace.log(
                "network_observed",
                "emitted" to false,
                "drop" to reason,
                "first" to true,
                // THE COUNT MUST TRAVEL WITH THE REFUSAL. It was omitted, and
                // the boundary gate caught what that costs: at limit+1 the
                // refusal was correctly typed `budget_exhausted` while
                // `dropped.network` read 0, because the running count was
                // carried ONLY on the emit checkpoint — and after the cap is
                // reached there are no more emits. The counter was right; the
                // reporting could not see it.
                "droppedTotal" to budget.droppedCount(),
            )
        } catch (_: Throwable) {
        }
    }

    override fun failed(site: RnNetworkCapture.FailureSite) {
        try {
            RecoveryTrace.log("network_capture_failed", "site" to site.wire)
        } catch (_: Throwable) {
        }
    }

    /** Safe status. A COUNT, never the events. */
    fun droppedNetwork(): Int = budget.droppedCount()

    // -------------------------------------------------------------- private

    private fun bytes(
        id: RnEnvelope.Identity, eventId: String, sequence: Long,
        wall: Long, mono: Long, event: NetworkCaptureCore.NetworkEvent,
    ): ByteArray = RnEnvelope.networkRequest(
        id = id,
        eventId = eventId,
        sequence = sequence,
        timestampMillis = wall,
        monotonicNanos = mono,
        // A network event carries no views and describes no screen CONTENT.
        posture = RnEnvelope.Posture(allowedViewCount = 0, screenNeverRecord = false),
        event = event,
    ).toByteArray(Charsets.UTF_8)

    /**
 * The durable execution checkpoint.
 *
 * EVERY FIELD IS ALREADY NORMALIZED OR TYPED. `host` and `routeTemplate`
 * are the normalizer's own output — the raw URL, its query, its fragment
 * and its userinfo were discarded before the start context existed, so
 * there is no path by which one could arrive here. `outcome`, `errorClass`
 * and `drop` are enum wire values. `status` is an integer the schema pins
 * to 100.599. The request id is a SHA-256 prefix, not the id, so a trace
 * cannot be used to correlate back to a specific captured request.
 *
 * Bounded by construction: the checkpoint is written only for a request the
 * budget ADMITTED, so emitted checkpoints per session cannot exceed
 * `maxEventsPerSession`.
 */
    private fun checkpoint(
        event: NetworkCaptureCore.NetworkEvent, emitted: Boolean, drop: String?,
    ) {
        try {
            val s = event.start
            RecoveryTrace.log(
                "network_observed",
                "rid" to shortHash(s.networkRequestId),
                "method" to s.method,
                "host" to s.host,
                "route" to s.routeTemplate,
                "layer" to s.sourceLayer,
                "screen" to s.screenId,
                "outcome" to event.outcome.wire,
                "err" to event.errorClass?.wire,
                "status" to event.status,
                "ms" to event.durationMs,
                "emitted" to emitted,
                "drop" to drop,
                "droppedTotal" to budget.droppedCount(),
            )
        } catch (_: Throwable) {
        }
    }

    private fun shortHash(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
}

package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo

/**
 * WHEN A REFUSED SLICE IS PERMANENTLY LOCALLY UNDELIVERABLE — the whole
 * decision, in one pure place, so it can be tested with no store, no network
 * and no Android runtime, and so the Swift twin has one thing to mirror.
 *
 * ## The defect, stated precisely
 *
 * "Unsliceable record" was the wrong name and it hid the cause. A slice always
 * forms — `sessionOwnedPrefix` starts at one record and carries no size bound.
 * What is lost is the session's ORIGIN:
 *
 * a session is originated server-side ONLY by `mobile_session_start`, which
 * carries sequence 0 and is emitted once, at session start
 *
 * the DURABLE store evicts OLDEST-FIRST with no priority awareness, while
 * the in-memory queue's eviction deliberately PROTECTS that same record
 * (`mobile_session_start` = priority 2, above hierarchy/frame/network = 1)
 *
 * `mobile_session_start` is by construction the oldest record of its
 * session, so durable pressure deletes the one record capable of originating
 * the session while retaining every record that depends on it
 *
 * Those retained records are then addressed to a session the server will never
 * have, refused `session_not_found` forever, correctly RETAINED (never
 * deleted) — and the settle accounting converted that correct retention into
 * an unbounded 2 s wakeup with one real HTTP POST per cycle, because
 * `onDrainSettled` was given only a COUNT, and a count cannot distinguish
 * "retryable bytes remain" from "permanently undeliverable bytes remain".
 *
 * ## THE TERMINAL FACT IS NOT THE SERVER RESPONSE
 *
 * `session_not_found` ALONE is not terminal: an opener still present anywhere
 * means origin recoverability is NOT DISPROVEN, so there is not enough evidence
 * to declare the slice terminal.
 *
 * That is deliberately weaker than "the next pass will originate it". Under
 * strict FIFO an opener BEHIND the refused head never moves ahead of it by
 * retrying, and no reorder path exists. The contract is fail-closed evidence,
 * never a promise of eventual self-recovery. The terminal fact is the
 * CONJUNCTION —
 *
 * the server requires a session origin
 * AND this producer no longer possesses any local mechanism able to create it
 *
 * which is why the reason is [SESSION_ORIGIN_UNRECOVERABLE] and deliberately
 * NOT `session_not_found`. Naming it after the response would name the symptom,
 * and would eventually be applied to a session whose opener is merely queued.
 *
 * ## AND IT IS NOT `PARKED_PRODUCER`
 *
 * The host surfaces a producer-capability refusal as `PRODUCER_UNSUPPORTED`,
 * i.e. "this build is incompatible". That statement would be FALSE here: the
 * build is fine, the session's opening record is gone. Reusing that reason
 * would tell an application something untrue about itself. This reason is
 * ADDITIVE — `PARKED_PRODUCER` semantics, bytes and behaviour are untouched.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object SessionOriginPolicy {

    /**
 * The durable terminal reason. Written on the in-flight descriptor AND as
 * the parked batch's refusal code, so the two agree by construction.
 */
    const val SESSION_ORIGIN_UNRECOVERABLE = "session_origin_unrecoverable"

    /** The one event type that originates a session server-side. */
    const val OPENER_EVENT_TYPE = "mobile_session_start"

    /**
 * What the caller knows about the refused slice, gathered from DURABLE
 * state under one consistency boundary.
 *
 * `openerPresentInActiveStore` MUST come from the durable record store,
 * never from an in-memory session registry. Process restart is exactly the
 * case this defect survives, and an in-memory registry is empty there — so
 * a registry-based check would answer "no opener" on every cold start and
 * park sessions whose opener is sitting in the queue. The store is the
 * authority because the store is what survives.
 */
    data class Evidence(
        /** The server's canonical classification of the refusal. */
        val sessionPreconditionRefusal: Boolean,
        /** The refused slice's first sequence. */
        val firstSequence: Long,
        /** A `mobile_session_start` for THIS session exists in the active store. */
        val openerPresentInActiveStore: Boolean,
        /**
 * An opener for this session is recoverable anywhere else in the active
 * delivery domain. A caller that CANNOT answer must supply `true`, so
 * ignorance keeps the slice transient.
 */
        val openerRecoverableElsewhere: Boolean,
        /** This session is ALREADY durably known terminal (a parked batch names it). */
        val sessionAlreadyTerminal: Boolean,
    )

    /** The disposition of one slice. */
    enum class Verdict {
        /** Keep retrying through the ordinary bounded backoff. Nothing moves. */
        TRANSIENT,

        /**
 * Permanently locally undeliverable: persist the terminal reason on the
 * durable descriptor, THEN move the bytes to the park store.
 * **No deletion, ever.**
 */
        TERMINAL_SESSION_ORIGIN,
    }

    /**
 * The decision.
 *
 * EVERY CLAUSE IS REQUIRED, and the order is the argument:
 *
 * - `firstSequence > 0` — a slice BEGINNING at sequence 0 contains the
 * opener itself, so the origin is by definition not lost. Parking it
 * would move out the very record that can still originate the session.
 * - the opener is absent from the active store AND not recoverable
 * elsewhere — if it is queued anywhere, the next pass originates the
 * session and everything behind it drains.
 * - the server actually refused on the session precondition — a network
 * error, a 500 or an auth rejection says nothing about session origin.
 *
 * Any clause failing yields [Verdict.TRANSIENT], and every unknown is
 * supplied as the transient-preserving value by contract, so this can never
 * park on ignorance.
 */
    @JvmStatic
    fun verdict(e: Evidence): Verdict {
        // Already proven terminal on an earlier pass. A LATER record of the
        // same dead session must not need a SECOND server refusal to learn a
        // fact the device already knows durably — that is what removes the
        // repeat network cost for a multi-slice dead session. The
        // `firstSequence > 0` guard still applies: if a slice of that session
        // somehow begins at 0 it carries an opener and is deliverable.
        if (e.sessionAlreadyTerminal && e.firstSequence > 0L) return Verdict.TERMINAL_SESSION_ORIGIN

        if (!e.sessionPreconditionRefusal) return Verdict.TRANSIENT
        if (e.firstSequence <= 0L) return Verdict.TRANSIENT
        if (e.openerPresentInActiveStore) return Verdict.TRANSIENT
        if (e.openerRecoverableElsewhere) return Verdict.TRANSIENT
        return Verdict.TERMINAL_SESSION_ORIGIN
    }

    /**
 * Does this serialized record OPEN [sessionId]?
 *
 * Parsed, never string-matched: a substring scan would be defeated by key
 * order and would also match a record that merely MENTIONS the id. The
 * pipeline already reads `sessionId` and `sequence` from these bytes with
 * the same parser, so this introduces no new dependency and no second
 * reading of the same fact.
 *
 * AN UNPARSEABLE RECORD RETURNS TRUE. This answer is used only to keep a
 * slice TRANSIENT: a false positive costs one more bounded retry, a false
 * negative parks a session whose opener was merely unreadable. Bias to the
 * safe direction, explicitly.
 */
    @JvmStatic
    fun recordOpensSession(bytes: ByteArray, sessionId: String): Boolean = try {
        val o = org.json.JSONObject(String(bytes, Charsets.UTF_8))
        o.optString("eventType") == OPENER_EVENT_TYPE && o.optString("sessionId") == sessionId
    } catch (_: Throwable) {
        true
    }
}

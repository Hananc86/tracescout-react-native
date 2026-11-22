package com.tracescout.mobile.identity

/**
 * The typed answer `TraceScoutMobile.identify` returns — the public surface
 * of G2 for a NATIVE Android host app.
 *
 * ## THERE IS NO ENABLEMENT FLAG, AND THAT IS A DECISION
 *
 * Every other producer surface `:sdk` has gained is OPT-IN behind an explicit
 * switch — `enableInteractionCapture`, `enableNetworkCapture`,
 * `enableHostReportedInteractions`. Copying that shape here by reflex would be
 * wrong, and the difference is structural rather than a matter of taste:
 *
 * - each of those switches arms an OBSERVER that then captures on its own
 * (a window callback, a derived OkHttp client, a reporting channel), so the
 * host needs a way to say "do not start watching";
 * - `identify` observes nothing and starts nothing. It stamps a field the
 * envelope has DECLARED all along onto events the session was already going
 * to send. There is no new row, no new queue entry, no new chunk, no new
 * drain and no new byte path;
 * - the per-call argument IS the consent. The SDK cannot discover a user id —
 * there is no `Context.getUserId` — so this value exists on the wire only
 * because the host app chose, at that call site, to hand it over. A flag
 * would be a second gate in front of a door the host is already holding
 * open, and a host that set the flag would still have to make the call.
 *
 * And the failure mode of guessing the other way is the exact state G2 exists
 * to end: a host integrates `identify`, gets [Decision.Refused] with
 * `identify_not_enabled`, and `sessions.user_id` stays NULL — which is what it
 * has been for all 1,363 stored mobile sessions. The browser producer, which
 * writes the same column, also has no such flag.
 *
 * ## The one thing a host may need to switch is the SESSION, not the feature
 *
 * An identity belongs to the session that was live when it was named. A new
 * session starts ANONYMOUS — see `TraceScoutMobile.identify` for why the SDK
 * deliberately does not persist a user id across sessions — so a host that
 * wants every session attributed calls `identify` after `start`, which is
 * one line and carries no stored secret.
 */
object Identify {

    /** No `start` has been made, or the session has already ended. */
    const val REFUSED_NO_SESSION = "no_session"

    /**
 * The candidate named nobody — empty, or every code point in
 * [IdentityPolicy.BLANK_CODE_POINTS].
 *
 * NOT A SIGN-OUT, and the word is chosen so a host cannot read it as
 * one. Sign-out is not expressible through this field: the server's merge
 * is incoming-wins-if-higher-sequence over a non-null identity, so an
 * envelope naming no user does not clear a stored one.
 */
    const val REFUSED_BLANK = "blank"

    /** More than [IdentityPolicy.USER_ID_MAX] CODE POINTS. Never truncated. */
    const val REFUSED_TOO_LONG = "too_long"

    /**
 * The candidate was not a string at all — the caller handed `null`.
 *
 * AN EARLIER VERSION OF THIS LINE SAID "Kotlin callers cannot reach
 * this", and that is false: the entry point is `identify(userId: String?)`,
 * so `null` is an ordinary Kotlin argument. The fixture's own
 * never-named-anybody control passes one from Kotlin. A Java caller
 * reaches it too, with or without a nullness annotation.
 */
    const val REFUSED_NOT_A_STRING = "not_a_string"

    /**
 * The SDK itself faulted — never a statement about the host's request.
 *
 * IT IS DISTINCT FROM [REFUSED_NO_SESSION] ON PURPOSE, for the reason
 * `HostInteraction.REFUSED_INTERNAL_ERROR` records: the entry point runs
 * inside a never-throw guard whose caught-exception value is `null`, and
 * folding that into `no_session` tells a host that no session exists — so a
 * host doing the reasonable thing would WAIT for one that is already there,
 * and an SDK fault would read as ordinary lifecycle.
 */
    const val REFUSED_INTERNAL_ERROR = "internal_error"

    /** The typed answer a host logs instead of assuming success. */
    sealed interface Decision {
        /**
 * The identity was applied to the live session and will ride the
 * envelope of every event it sends from here on.
 *
 * NOT A DELIVERY RECEIPT, and [length] is disclosed instead of the
 * value for the same reason [IdentityPolicy.Decision] withholds it: a
 * host that logs this result must not accidentally log an end user's
 * key. Whether any event is then stored or uploaded depends on the
 * queue and the network, neither of which this value can promise.
 *
 * [length] is the measured code-point count, so a host can see that
 * the key it passed is the length it expected.
 */
        data class Applied(val length: Int) : Decision

        /** Nothing was applied. [reason] is a stable wire word, never content. */
        data class Refused(val reason: String) : Decision
    }

    /**
 * Map a policy verdict onto the public refusal word.
 *
 * ONE TRANSLATION SITE. The words are deliberately the policy's own
 * (`blank`, `too_long`, `not_a_string`) so a reader comparing a host log
 * against `identity-policy-vectors.json` sees the same vocabulary, and a
 * new verdict added to the canonical module cannot be silently swallowed
 * here — `when` over the enum is exhaustive, so it stops compiling.
 */
    fun refusalWord(verdict: IdentityPolicy.Verdict): String = when (verdict) {
        IdentityPolicy.Verdict.BLANK -> REFUSED_BLANK
        IdentityPolicy.Verdict.TOO_LONG -> REFUSED_TOO_LONG
        IdentityPolicy.Verdict.NOT_A_STRING -> REFUSED_NOT_A_STRING
        // Reaching here means a caller asked for the refusal word of an
        // ACCEPTED decision, which is a programming error in `:sdk` and not a
        // host fact — so it answers with the SDK-fault word rather than
        // inventing a refusal the host could act on.
        IdentityPolicy.Verdict.ACCEPTED -> REFUSED_INTERNAL_ERROR
    }
}

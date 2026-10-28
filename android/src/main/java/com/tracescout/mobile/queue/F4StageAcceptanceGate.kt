package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo

/**
 * The machine-visible F4 stage-acceptance gate.
 *
 * ## Why this is an object and not a sentence in a document
 *
 * Five operational constants are UNSET pending the M1–M5 measurement, and "F4
 * stage acceptance is impossible until they are measured" has to be something a
 * program can be stopped by. Prose cannot stop anything.
 *
 * **AND WHY IT IS NOT A PERMANENTLY FAILING TEST.** A test that fails while a
 * constant is UNSET makes the whole suite red for a known, intended reason —
 * which is indistinguishable from a real regression, and a suite nobody can
 * read green is a suite nobody reads. Instead:
 *
 * * [assertAcceptable] **throws** while any gated constant is UNSET. Stage
 * closure calls it, so closure cannot succeed by omission.
 * * A test asserts that it throws TODAY and names every blocked constant, so the
 * blocked state is itself verified rather than assumed.
 * * The day the measurement lands, that test's expectation flips deliberately —
 * in the same change that sets the constants, by someone who had to look at it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object F4StageAcceptanceGate {

    class Blocked(val unset: List<String>) :
        IllegalStateException(
            "f4_stage_acceptance_blocked: operational constants are UNSET: " +
                unset.joinToString(", "),
        )

    /**
 * @throws Blocked while any gated operational constant is UNSET.
 */
    @JvmStatic
    fun assertAcceptable() {
        val unset = F4Constants.unsetConstantNames()
        if (unset.isNotEmpty()) throw Blocked(unset)
    }

    /**
 * The per-operation fail-closed helper.
 *
 * A DESTRUCTIVE OPERATION WHOSE BOUND IS UNSET MUST REFUSE, NOT RUN
 * UNBOUNDED. This is the difference between "we have not measured it yet"
 * and "we destroy customer records without a bound". Callers use the typed
 * refusal; nothing falls back.
 */
    @JvmStatic
    fun requireSet(name: String, value: Long): Long {
        if (F4Constants.isUnset(value)) throw Blocked(listOf(name))
        return value
    }
}

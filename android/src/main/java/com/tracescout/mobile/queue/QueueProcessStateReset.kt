package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import java.util.concurrent.atomic.AtomicReference

/**
 * THE GENERIC SINGLE-SLOT PROCESS-LOCAL RESET SEAM.
 *
 * A supported erasure destroys durable state. Any state a PROCESS is holding
 * about that durable state is now stale, and an erasure that left it in place
 * would let the next write disclose a fact about a store that no longer exists.
 * This is the one seam through which the erasure flow asks a process-local
 * holder to drop it.
 *
 * **GENERIC BY CONSTRUCTION — IT NAMES NOTHING IT RESETS.** It is in `:sdk`,
 * which may never reference `rn-core` or any frame / loss / cause vocabulary
 * (frozen pin B1). The holder registers itself; this object does not know what
 * it is, only that something asked to be told.
 *
 * ## Why ONE slot, and why `compareAndSet(null, r)`
 *
 * A second, different registrant would mean two holders each believing it owns
 * the process-local state for this concern — and an erasure would reset
 * whichever one happened to register first, silently leaving the other's state
 * behind. So the slot is claimed exactly once:
 *
 * * the first registrant wins;
 * * the SAME instance registering again is idempotent and succeeds (a repeated
 * `init` must not be a failure);
 * * a DIFFERENT instance is REFUSED, and the refusal is traced.
 *
 * **A BARE ASSIGNMENT IS THE DEFECT, NOT A SIMPLIFICATION.** `slot.set(r)`
 * passes every behavioural test a single registrant can write and silently
 * replaces an existing holder under concurrency — exactly the state this seam
 * exists to make unrepresentable. The dedicated control performs that
 * substitution and requires the refusal pin to FAIL.
 *
 * ## THE RESET IS PROVABLY INFALLIBLE, AND THERE IS NO `runCatching`
 *
 * B2 option (1), as the accepted contract adopts it. [Resettable] is a
 * CONTRACT, and its contract is that `dropProcessLocalState` cannot fail:
 *
 * * no `throw`, no I/O, no lock acquisition, no logging, no allocation —
 * atomic-state operations only;
 * * process-local only. It must not touch a durable store, a file, a key or
 * any state another process can observe.
 *
 * Because it cannot fail, there is nothing to catch, and catching would be
 * worse than useless: a swallowed failure is indistinguishable from a reset
 * that happened, so a `runCatching` here would let the erasure report
 * `Completed` while process-local state survived. the specification-207's fallible model is
 * WITHDRAWN. The erasure flow therefore keeps **exactly five `why` arms** and
 * gains no veto — a sixth arm is the OTHER option, and it is not this one.
 *
 * **CONSEQUENCE, STATED RATHER THAN HIDDEN.** A registrant that violates the
 * contract and throws anyway does NOT get converted into an outcome value: the
 * throw propagates out of the erasure flow, the request is NOT cleared, and
 * `Outcome.Completed` is never constructed. That is the fail-closed direction —
 * an erasure that did not finish must not be reported as finished — and it is
 * what the behavioural control asserts. There is deliberately no
 * `ResetOutcome.Threw` value, because a value would require the catch this
 * option removes.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object QueueProcessStateReset {

    /**
 * A process-local holder that can be told to drop its state.
 *
 * **THE IMPLEMENTATION MUST NOT THROW, BLOCK, ALLOCATE, LOG OR TOUCH
 * DURABLE STATE.** It runs inside the erasure flow's fence, where a block
 * would starve an operation the user requested and a throw would leave the
 * erasure unfinished. Atomic-state operations only, and nothing another
 * process can observe.
 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun interface Resettable {
        fun dropProcessLocalState()
    }

    private val slot = AtomicReference<Resettable?>(null)

    /**
 * Claim the single slot.
 *
 * @return `true` when this instance owns the slot afterwards — either
 * because it just claimed it, or because it already did. `false` when a
 * DIFFERENT instance holds it; nothing is replaced.
 */
    fun register(r: Resettable): Boolean {
        if (slot.compareAndSet(null, r)) return true
        val held = slot.get()
        if (held === r) return true
        RecoveryTrace.log("queue_process_state_register_refused", "why" to "slot_already_held")
        return false
    }

    /** Whether a holder currently owns the slot. Diagnostics only. */
    fun isRegistered(): Boolean = slot.get() != null

    /**
 * Drop process-local state IF the erasure restored forward progress.
 *
 * [forwardProgressRestored] is `StoreTeardown.Result.forwardProgressRestored`
 * the queue key destroyed AND the cleanup `Done`. It is passed in rather
 * than derived here, because the teardown's own result is the one authority
 * on what the erasure achieved and a second derivation could disagree with
 * it.
 *
 * @return `true` when a holder was present and was reset; `false` when the
 * erasure did not restore forward progress, or no holder is registered.
 * `false` is never an error — an unwired caller is a supported state.
 */
    fun dropIfErased(forwardProgressRestored: Boolean): Boolean {
        if (!forwardProgressRestored) return false
        val r = slot.get() ?: return false
        r.dropProcessLocalState()
        return true
    }

    /**
 * Release the slot.
 *
 * **TEST-ONLY, AND IT HAS ZERO PRODUCTION CALLERS — PINNED AT THE
 * SOURCE, ON THIS RECEIVER.** A production caller could hand the slot to a
 * second holder while the first still believes it owns the process-local
 * state, which is the condition [register] exists to refuse. The pin is
 * receiver-anchored because `QueueHome.forgetForTest` is a different
 * function with a legitimate production caller, and a pin on the bare name
 * would fire on it.
 */
    fun forgetForTest() {
        slot.set(null)
    }
}

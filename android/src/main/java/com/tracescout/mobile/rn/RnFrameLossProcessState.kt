package com.tracescout.mobile.rn

import com.tracescout.mobile.queue.QueueProcessStateReset
import java.util.concurrent.atomic.AtomicLong

/**
 * THE ONE HOME of every process-local FCL witness, and of the erasure epoch.
 * `:rn`, step 5/7.
 *
 * **ONE `AtomicLong`. ZERO PERSISTED BYTES. NO SECOND WITNESS HOME.**
 * The three witnesses and the epoch share a single 64-bit word, so a witness
 * and the epoch it is scoped to are read and written **simultaneously, in one
 * atomic operation**. An earlier shape used four `@Volatile` fields plus an
 * `if (at == epoch)` check and is SUPERSEDED: between reading the epoch and
 * writing the flag, an erasure could advance the epoch, and the flag would then
 * be attributed to a ledger generation that no longer exists — a pre-erasure
 * disclosure surviving into a post-erasure ledger, which is precisely what the
 * erasure clause forbids.
 *
 * ```
 * bit 0 refusalRecordingOwed G6-B an occurrence happened in scope and its ledger write was not durable
 * bit 1 exclusionRefusalOwed G6-C refused before the scope existed, so it was structurally unrecordable
 * bit 2 observedNonEmptyLedger this process has read a non-empty ledger
 * bits 3-63 epoch advanced by a governed erasure, never by anything else
 * ```
 *
 * **`AtomicReference` WAS CONSIDERED AND NOT ADOPTED** — it couples this
 * design to a fallible-reset model that the accepted contract withdrew.
 * `synchronized` and lock-sharing were refused on measurement: the G6-C setter
 * runs when the outer acquisition has ALREADY been refused, so there is no lock
 * to share and taking one would be a second concurrency model in the one path
 * that cannot rely on the first.
 *
 * **ZERO PERSISTED BYTES IS THE WHOLE POINT.** A durable witness would be
 * either a `QueueKeyStores.ALL` member — deleted by the same erasure, hence
 * useless — or a non-member, i.e. residue surviving a completed erasure, which
 * is forbidden. So the limitation is accepted and stated rather than engineered
 * around: **immediate process death before the next committed ledger write may
 * lose an owed bit.** No count is fabricated in any branch, and "no recorded
 * occurrences" is never reported as "zero occurrences ever".
 *
 * **CROSS-PROCESS IS NAMED DEBT, NOT HANDLED HERE.** A surviving second
 * process holds its own copy of this word. `FU-FCL-CROSS-PROCESS-WITNESS` is
 * carried deliberately; nothing here attempts IPC, a shared file or residue
 * suppression, because every available mechanism is one of the two forbidden
 * shapes above.
 *
 * **STEP 5 OWNS THE STATE; STEP 6 OWNS ITS WIRING.** The epoch-guarded
 * setters exist here and are called by nothing yet — the `enqueue` epoch read
 * and the two destructive-path setters are step 6.
 */
object RnFrameLossProcessState : QueueProcessStateReset.Resettable {

    private const val BIT_REFUSAL_RECORDING_OWED = 1L shl 0
    private const val BIT_EXCLUSION_REFUSAL_OWED = 1L shl 1
    private const val BIT_OBSERVED_NON_EMPTY = 1L shl 2

    /** Every flag bit. The reset clears exactly these and nothing else. */
    private const val FLAG_MASK = BIT_REFUSAL_RECORDING_OWED or
        BIT_EXCLUSION_REFUSAL_OWED or BIT_OBSERVED_NON_EMPTY

    private const val EPOCH_SHIFT = 3

    /** One word. There is no second word, and no field beside it. */
    private val word = AtomicLong(0L)

    init {
        // REGISTERS IN ITS OWN `init`, so the one home of the state is also
        // the one thing that can be told to drop it. A registration performed by
        // a caller could be forgotten by a caller.
        QueueProcessStateReset.register(this)
    }

    /**
 * The current erasure epoch. **Read this BEFORE acquiring the exclusion**,
 * then pass it to a setter: that is what makes an erasure that happens
 * during the attempt discard the witness rather than mis-attribute it.
 */
    fun epoch(): Long = word.get() ushr EPOCH_SHIFT

    /** G6-B. Returns whether the bit was applied to the epoch it was scoped to. */
    fun setRefusalRecordingOwed(at: Long): Boolean = setFlag(at, BIT_REFUSAL_RECORDING_OWED)

    /**
 * G6-C. **CALLED WITH NO LOCK HELD, BY DESIGN** — the outer acquisition
 * was just refused, so no exclusion exists. A CAS is safe there; a
 * `synchronized` block would be a second concurrency model reachable only on
 * the path that proves the first one was unavailable.
 */
    fun setExclusionRefusalOwed(at: Long): Boolean = setFlag(at, BIT_EXCLUSION_REFUSAL_OWED)

    /** This process has read a non-empty ledger. Dropped by a governed erasure. */
    fun setObservedNonEmptyLedger(at: Long): Boolean = setFlag(at, BIT_OBSERVED_NON_EMPTY)

    fun refusalRecordingOwed(): Boolean = word.get() and BIT_REFUSAL_RECORDING_OWED != 0L
    fun exclusionRefusalOwed(): Boolean = word.get() and BIT_EXCLUSION_REFUSAL_OWED != 0L
    fun observedNonEmptyLedger(): Boolean = word.get() and BIT_OBSERVED_NON_EMPTY != 0L

    /**
 * G6-B — the owed bit has been FOLDED INTO A COMMITTED durable state, so the
 * debt is discharged. **The ONLY caller is the single propagation point in
 * [RnFrameLossStore.appendState], and only on a `Committed` result.**
 *
 * **EPOCH-GUARDED FOR THE SAME REASON THE SETTER IS.** A governed erasure
 * between the fold and the commit has already zeroed every flag and advanced
 * the epoch; clearing against a stale epoch would reach into the NEW
 * generation and discard a disclosure that belongs to it. A stale epoch is
 * therefore refused, exactly as [setFlag] refuses one.
 */
    fun clearRefusalRecordingOwed(at: Long): Boolean = clearFlag(at, BIT_REFUSAL_RECORDING_OWED)

    /** G6-C. Same contract as [clearRefusalRecordingOwed]. */
    fun clearExclusionRefusalOwed(at: Long): Boolean = clearFlag(at, BIT_EXCLUSION_REFUSAL_OWED)

    /**
 * Clear [bit] ONLY if the word still carries epoch [at].
 *
 * **THE OBSERVED-NON-EMPTY BIT HAS NO CLEAR AND MUST NOT GET ONE.** It is
 * a statement that this process has seen a non-empty ledger, and the only
 * thing entitled to retract that is a governed erasure through
 * [dropProcessLocalState]. The two owed bits are different: they are DEBTS,
 * and a debt discharged into a committed durable state is paid.
 *
 * Idempotent on an already-clear bit, and it cannot disturb the epoch or the
 * other flags — `bit.inv` has every other bit set.
 */
    private fun clearFlag(at: Long, bit: Long): Boolean {
        while (true) {
            val cur = word.get()
            if ((cur ushr EPOCH_SHIFT) != at) return false
            if (cur and bit == 0L) return true
            if (word.compareAndSet(cur, cur and bit.inv())) return true
        }
    }

    /**
 * Apply [bit] ONLY if the word still carries epoch [at].
 *
 * **A STALE EPOCH IS REFUSED, NOT APPLIED TO THE CURRENT ONE.** The
 * attempt was about a ledger generation a governed erasure has since
 * destroyed; carrying its disclosure forward would make a post-erasure
 * ledger capable of reporting pre-erasure state.
 *
 * The loop re-reads rather than failing on contention: a concurrent setter
 * of a DIFFERENT bit must not cause this one to be lost. It exits when the
 * epoch moves (refuse) or the bit is already set (idempotent success).
 */
    private fun setFlag(at: Long, bit: Long): Boolean {
        while (true) {
            val cur = word.get()
            if ((cur ushr EPOCH_SHIFT) != at) return false
            if (cur and bit != 0L) return true
            if (word.compareAndSet(cur, cur or bit)) return true
        }
    }

    /**
 * The governed-erasure reset: **advance the epoch and zero every flag in ONE
 * CAS.** Invoked only by [QueueProcessStateReset.dropIfErased], which is
 * itself gated on the teardown's own `forwardProgressRestored`.
 *
 * **INFALLIBLE BY CONSTRUCTION — it cannot throw, block, allocate, log or
 * touch IO**, which is the contract its registration slot requires. The
 * accepted design removed the `runCatching` that used to wrap it, so a throw
 * here would propagate out of the erasure flow and leave the request
 * uncleared; there is nothing here that can throw.
 *
 * **ADVANCING THE EPOCH IS WHAT MAKES AN IN-FLIGHT ATTEMPT SAFE.** A
 * concurrent attempt holding the old epoch will have its setter refused, so
 * its disclosure cannot land in the post-erasure generation. Wrap is
 * unreachable: 61 epoch bits at one advance per governed erasure.
 */
    override fun dropProcessLocalState() {
        while (true) {
            val cur = word.get()
            val next = ((cur ushr EPOCH_SHIFT) + 1L) shl EPOCH_SHIFT
            if (word.compareAndSet(cur, next)) return
        }
    }

    /**
 * TEST-ONLY. **ZERO PRODUCTION CALLERS, and that is pinned** — a
 * production caller could reset the epoch without an erasure having
 * happened, which is exactly the mis-attribution the epoch exists to
 * prevent.
 */
    fun forgetForTest() { word.set(0L) }
}

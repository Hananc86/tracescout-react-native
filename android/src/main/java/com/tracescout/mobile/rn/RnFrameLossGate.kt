package com.tracescout.mobile.rn

import com.tracescout.mobile.queue.ErasureRequest
import com.tracescout.mobile.queue.QueueDomainRegion
import com.tracescout.mobile.queue.QueueKeyStores
import com.tracescout.mobile.queue.QueueRegion
import com.tracescout.rn.Exclusion
import com.tracescout.rn.FrameContentLossLedger
import com.tracescout.rn.FrameContentLossState
import com.tracescout.rn.FrameLossCause
import com.tracescout.rn.FrameLossGateOutcome
import com.tracescout.rn.FrameLossReadState
import com.tracescout.rn.FrameLossSink
import com.tracescout.rn.FrameMutationOp
import com.tracescout.rn.FrameRefusalReason
import com.tracescout.rn.FrameRemoval
import java.io.File
import javax.crypto.SecretKey

/**
 * Everything the gate needs to know about the FRAME store, and nothing more.
 *
 * **THIS SEAM EXISTS BECAUSE A FROZEN PIN CAUGHT A SECOND CONSTRUCTION
 * SITE.** The gate first opened its own `EncryptedEventStore` over the frame
 * file to run the discriminator — correct behaviour, and a genuine defect:
 * `RnFrameStore` already owns the ONE construction of that store, and a second
 * site carrying its own `PRESERVE` argument would silently diverge the day the
 * first one gained an argument. `:sdk`'s `AuthorityDurableWriteTest.F21`
 * (consumer inventory, closed in both directions) made it visible.
 *
 * So the gate constructs NOTHING: it is handed a view. [present] is the
 * EXISTENCE WITNESS — `File.exists` at the supplier, never a read — because a
 * committed removal to empty leaves the file present at length 0 while an
 * absent file reads trustworthy-empty, and those must stay distinguishable.
 *
 * Step 6 supplies this from the frame store's own single home.
 */
data class FramesView(
    val present: Boolean,
    val trustworthy: Boolean,
    val records: List<ByteArray>,
)

/**
 * THE ONE ENFORCEMENT HOME for frame-loss reconciliation, and the `:rn`
 * implementation of rn-core's declared [FrameLossSink]. `:rn`, step 5/7.
 *
 * **STEP 5 BUILDS THIS; STEP 6 WIRES IT.** Nothing calls [ensureReconciled]
 * or hands this sink to `FrameDelivery.drain` yet — `RnFrameStore.enqueue` is
 * untouched beyond the inert `removeFirstTyped` adapter, and the five drain-pass
 * mutation sites still mutate directly. Building the enforcement home first is
 * what lets step 6 be a wiring change rather than a design change.
 *
 * **A PLAIN FIELD, NOT AN `AtomicReference`, AND NOT DOUBLE-CHECKED.**
 * [state] is read and written ONLY inside the `FRAME_BRACKET` exclusion, so the
 * region lock is already held when it is consulted. A lock-free primitive here
 * would add a second concurrency model and reintroduce exactly the
 * check-then-act class the exclusion exists to remove.
 *
 * **PROCESS-LOCAL, STARTING AT `NotRun`** — so process death resets safely
 * and a `Refused` verdict can never outlive the condition that produced it.
 */
internal class RnFrameLossGate(
    private val filesDir: File,
    private val key: SecretKey,
    /**
 * **INJECTED, NOT CONSTRUCTED.** The gate owns no frame-store
 * construction site — see [FramesView]. Step 6 supplies this from
 * `RnFrameStore`'s own single home.
 */
    private val framesView: () -> FramesView,
    private val ledger: RnFrameLossStore = RnFrameLossStore(filesDir, key),
) : FrameLossSink {

    private enum class GateState { NotRun, Reconciled }

    /** Read and written ONLY inside the exclusion. */
    private var state: GateState = GateState.NotRun

    private fun framesFile(): File = File(filesDir, QueueKeyStores.RN_FRAMES)

    private fun domain(): QueueDomainRegion = QueueDomainRegion.forStoreFile(framesFile())

    /**
 * Take the frame-domain exclusion and reconcile, once per process.
 *
 * **THE WHOLE BODY IS INSIDE ONE ACQUISITION**, because [state] and every
 * durable step below must be serialized against every other frame mutation
 * on the same domain.
 */
    fun ensureReconciled(): FrameLossGateOutcome =
        when (val h = domain().withDomain(QueueRegion.FRAME_BRACKET) { reconcileInRegion() }) {
            is QueueDomainRegion.Held.Ran<*> ->
                @Suppress("UNCHECKED_CAST") (h.value as FrameLossGateOutcome)
            is QueueDomainRegion.Held.Refused ->
                // The scope never existed. `withDomain` already traced it; the
                // caller decides what to do, and nothing is memoised.
                FrameLossGateOutcome.Refused("exclusion_refused_${h.wire}")
        }

    /**
 * The five-step order IS the safety argument.
 *
 * ```
 * 1 an outstanding erasure request -> REFUSE. RESPECT, never RESUME.
 * 2 open the ledger; READ and CLASSIFY
 * 3 no PENDING -> Reconciled
 * 4 a PENDING -> existence witness FIRST, then the predicate
 * STILL OWED keep it; Reconciled (the next drain re-attempts)
 * COMMITTED append the RECORD successor; Reconciled
 * INDETERMINATE append a successor disclosing it; PENDING cleared; Reconciled
 * 5 any durable ledger write that does not commit -> REFUSE
 * ```
 *
 * **STEP 1 RESPECTS AN ERASURE REQUEST AND NEVER RESUMES IT**, including
 * when the request is UNREADABLE. Resuming would create a SECOND route to
 * `QueueKeyStores.clearAll`, and that path's entire safety is its single
 * caller; `RnTransport.start` already resumes. An unreadable request is
 * treated as outstanding — fail closed, because "I cannot tell whether an
 * erasure is pending" is not permission to write.
 */
    private fun reconcileInRegion(): FrameLossGateOutcome {
        if (state == GateState.Reconciled) return FrameLossGateOutcome.Reconciled

        // 1 — erasure respect.
        when (val er = ErasureRequest.forFilesDir(filesDir).read()) {
            is ErasureRequest.Read.Ok ->
                if (er.record != null) return FrameLossGateOutcome.Refused("erasure_outstanding")
            is ErasureRequest.Read.Unreadable ->
                return FrameLossGateOutcome.Refused("erasure_outstanding_unreadable")
        }

        // 2 — read and classify.
        val read = ledger.read()
        when (read.outcome) {
            FrameLossReadState.OK, FrameLossReadState.TRUSTWORTHY_EMPTY -> Unit
            FrameLossReadState.VANISHED_AFTER_OBSERVED -> {
                // The history was lost. Disclose it durably and monotonically;
                // never report the ledger as legitimately zero.
                val next = FrameContentLossLedger.setPriorStateLost(FrameContentLossLedger.fresh())
                return commitAndMemoise(next, "prior_state_lost")
            }
            // Fail closed. No write, no trim, no promotion, NO NUMBER CLAIMED.
            FrameLossReadState.UNTRUSTWORTHY_AUTH_FAILURE,
            FrameLossReadState.UNTRUSTWORTHY_FRAMING,
            FrameLossReadState.INVARIANT_EXCEEDED,
            FrameLossReadState.LEDGER_RECORD_UNDECODABLE,
            FrameLossReadState.VERSION_UNSUPPORTED ->
                return FrameLossGateOutcome.Refused("ledger_${read.outcome.name.lowercase()}")
        }

        val cur = read.state
        // 3 — nothing to resolve. G6 NEVER has a PENDING, so a G6-only ledger
        // reaches here, is decoded, and changes NO durable state. That
        // asymmetry is DISCLOSED rather than elided: for G9/G10 the gate's
        // decision changes durable state; for G6 it does not.
        if (cur?.pending == null) {
            state = GateState.Reconciled
            return FrameLossGateOutcome.Reconciled
        }

        // 4 — resolve the PENDING against the live FRAME store.
        val pending = cur.pending!!
        val frames = try { framesView() } catch (t: Throwable) { null }

        val resolution = FrameContentLossLedger.decide(
            pending = pending,
            storePresent = frames?.present == true,
            trustworthyRead = frames?.trustworthy == true,
            size = frames?.records?.size ?: -1,
            wholeStoreFingerprint =
                if (frames != null) ledger.fingerprint(frames.records) else "",
        )

        return when (resolution) {
            // Keep the PENDING. The next drain re-attempts the removal; nothing
            // durable changes, so nothing can be reported either way.
            FrameContentLossLedger.Resolution.REMOVAL_STILL_OWED -> {
                state = GateState.Reconciled
                FrameLossGateOutcome.Reconciled
            }
            FrameContentLossLedger.Resolution.REMOVAL_COMMITTED ->
                applyAndMemoise(cur, FrameRemoval.COMMITTED, "resolve_committed")
            FrameContentLossLedger.Resolution.INDETERMINATE ->
                applyAndMemoise(
                    cur, FrameRemoval.INDETERMINATE_DIRECTORY_UNSYNCED, "resolve_indeterminate",
                )
        }
    }

    private fun applyAndMemoise(
        cur: FrameContentLossState,
        removal: FrameRemoval,
        why: String,
    ): FrameLossGateOutcome =
        when (val u = FrameContentLossLedger.resolve(cur, removal)) {
            is FrameContentLossLedger.Update.Ok -> commitAndMemoise(u.state, why)
            // A refused transition is a bookkeeping refusal (e.g. a counter at
            // its ceiling), never a loss. Nothing durable changes.
            is FrameContentLossLedger.Update.Refused ->
                FrameLossGateOutcome.Refused("ledger_update_${u.reason.name.lowercase()}")
        }

    /**
 * **`Reconciled` IS MEMOISED ONLY AFTER A COMMITTED SUCCESSOR APPEND.**
 * Memoising on a `CommittedNoTrim` or a `NotDurable` would let one process
 * treat an unresolved ledger as resolved for its whole lifetime — the
 * state is process-local precisely so that a failure is retried, not cached.
 */
    private fun commitAndMemoise(next: FrameContentLossState, why: String): FrameLossGateOutcome =
        when (val w = ledger.appendState(next)) {
            is FrameLossWrite.Committed -> {
                state = GateState.Reconciled
                FrameLossGateOutcome.Reconciled
            }
            is FrameLossWrite.CommittedNoTrim ->
                FrameLossGateOutcome.Refused("loss_ledger_not_durable_${w.reason}")
            is FrameLossWrite.NotDurable ->
                FrameLossGateOutcome.Refused("loss_ledger_not_durable_${w.reason}")
        }

    // ── FrameLossSink — rn-core's declared seam, implemented here ────────────

    /**
 * Run [block] inside the ONE frame-domain exclusion, **having FIRST resolved
 * any foreign PENDING**.
 *
 * **THE FOREIGN-PENDING RESOLUTION IS IN THIS ONE HOME, NOT AT THE FIVE
 * CALL SITES.** "Resolve before mutating" has to hold at every mutation; put
 * at the sites it is five things to remember, and the sixth site added later
 * forgets.
 *
 * **NEVER CALLED WITH A NETWORK REQUEST INSIDE [block]** — the frame POST
 * happens before every mutation site and stays outside every exclusion.
 * Holding an OS lock across an HTTP request turns a remote stall into a
 * local one. Step 6 wires the sites; a source pin asserts no block
 * lexically contains `post(`.
 */
    override fun <T> withExclusion(op: FrameMutationOp, block: () -> T): Exclusion<T> =
        // `op` is the DECLARED INTENT of the site and is deliberately not read
        // here: the acquisition is the same for all five, and a site that could
        // choose a different scope by naming a different op would be five
        // scopes, not one. The tests pin that each site names a DISTINCT op.
        inFrameScope(block)

    /**
 * STEP 6/7 — the `enqueue` acquisition.
 *
 * **A SEPARATE ENTRY POINT, NOT A SIXTH [FrameMutationOp].** The enum has
 * exactly five members and each names a drain-pass MUTATION; `enqueue` is a
 * write site, not one of them, and adding a sixth member is a negative
 * control that must FAIL. So the enqueue path gets its own door into the
 * SAME one acquisition home rather than a new vocabulary member.
 *
 * **A REFUSAL HERE IS G6-C AND THE CALLER OWNS THAT**, because G6-C is
 * scoped to the `enqueue` acquisition alone — a refused drain-pass exclusion
 * destroys nothing and must set no disclosure. This method therefore returns
 * the typed refusal and sets no witness of its own.
 */
    fun <T> withEnqueueScope(block: () -> T): Exclusion<T> = inFrameScope(block)

    /**
 * THE ONE ACQUISITION BODY. Reconcile first, then run the caller's block,
 * both inside a single `FRAME_BRACKET` hold.
 *
 * **ONE HOME ON PURPOSE.** Two copies of "take the region, reconcile,
 * then mutate" are free to drift, and a drift here is invisible: both would
 * keep working while one of them stopped closing the window the exclusion
 * exists for.
 */
    private fun <T> inFrameScope(block: () -> T): Exclusion<T> =
        when (val h = domain().withDomain(QueueRegion.FRAME_BRACKET) {
            reconcileInRegion()
            block()
        }) {
            is QueueDomainRegion.Held.Ran<*> ->
                @Suppress("UNCHECKED_CAST") Exclusion.Ran(h.value as T)
            is QueueDomainRegion.Held.Refused ->
                // A refused exclusion at a drain-pass mutation means the mutation
                // did NOT happen. It is NOT a content loss and sets NO
                // disclosure — only a refused `enqueue` acquisition is G6-C.
                Exclusion.Refused(h.wire, h.waitedMs)
        }

    /**
 * Commit a durable PENDING before a removal is attempted.
 *
 * **`false` MUST REFUSE THE REMOVAL.** A removal with no durable PENDING
 * cannot be reconciled after a crash, so the bytes would be destroyed with
 * no way to tell afterwards whether they were.
 */
    override fun prepare(cause: FrameLossCause, sessionId: String?): Boolean {
        val read = ledger.read()
        val base = when (read.outcome) {
            FrameLossReadState.OK -> read.state ?: return false
            FrameLossReadState.TRUSTWORTHY_EMPTY -> FrameContentLossLedger.fresh()
            else -> return false
        }
        val frames = try { framesView() } catch (t: Throwable) { return false }
        if (!frames.present || !frames.trustworthy) return false
        val pre = frames.records
        if (pre.isEmpty()) return false
        val u = FrameContentLossLedger.prepare(
            s = base,
            cause = cause,
            preFingerprint = ledger.fingerprint(pre),
            preCount = pre.size,
            expectedPostFingerprint = ledger.fingerprint(pre.drop(1)),
            sessionId = sessionId,
        )
        val next = (u as? FrameContentLossLedger.Update.Ok)?.state ?: return false
        return ledger.appendState(next) is FrameLossWrite.Committed
    }

    /** Record what the typed removal actually did, in the SAME exclusion as [prepare]. */
    override fun resolve(removal: FrameRemoval) {
        val read = ledger.read()
        val cur = if (read.outcome == FrameLossReadState.OK) read.state else null
        if (cur?.pending == null) return
        val u = FrameContentLossLedger.resolve(cur, removal)
        if (u is FrameContentLossLedger.Update.Ok) ledger.appendState(u.state)
    }

    /**
 * Record a G6 enqueue refusal.
 *
 * **THE RETURN VALUE IS THE CLASSIFICATION, AND IT IS THE OBSERVED
 * LEDGER-WRITE OUTCOME — never the refusal reason.** `true` is G6-A, exactly
 * counted. `false` is **G6-B: a disclosure, never a count** — the caller
 * sets the owed witness and `enqueue` still returns false, so the Boolean
 * contract the host app sees is unchanged.
 */
    override fun recordRefusal(reason: FrameRefusalReason, sessionId: String?): Boolean {
        val read = ledger.read()
        val base = when (read.outcome) {
            FrameLossReadState.OK -> read.state ?: return false
            FrameLossReadState.TRUSTWORTHY_EMPTY -> FrameContentLossLedger.fresh()
            else -> return false
        }
        val u = FrameContentLossLedger.recordRefusal(base, reason, sessionId)
        val next = (u as? FrameContentLossLedger.Update.Ok)?.state ?: return false
        return ledger.appendState(next) is FrameLossWrite.Committed
    }

    /** TEST-ONLY: forget the memoised verdict so a case can drive the gate again. */
    fun forgetForTest() { state = GateState.NotRun }
}

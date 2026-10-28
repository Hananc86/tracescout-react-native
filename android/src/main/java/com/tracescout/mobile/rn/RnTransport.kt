package com.tracescout.mobile.rn

import com.tracescout.rn.FlushSchedule

import android.content.Context
import androidx.annotation.RestrictTo
import com.tracescout.mobile.internal.queue.DurableUploadPipeline
import com.tracescout.mobile.internal.queue.ParkStatus
import com.tracescout.mobile.internal.queue.ParkStore
import com.tracescout.mobile.internal.queue.RecordStore
import com.tracescout.mobile.queue.EncryptedParkStore
import com.tracescout.mobile.queue.EncryptedEventStore
import com.tracescout.mobile.queue.RecoveryCoordinator
import com.tracescout.mobile.queue.RecoveryTrace
import com.tracescout.mobile.queue.RecoveryWorker
import com.tracescout.mobile.queue.StoreKeys
import java.io.File
import com.tracescout.mobile.transport.Uploader
import com.tracescout.mobile.transport.backoffMillis
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.tracescout.mobile.internal.loss.LossAuthority

/**
 * Native transport ownership for the React Native producer.
 *
 * **The durable path does not depend on the JS runtime.** JS supplies semantics
 * (a route, a privacy declaration, a navigation); the moment those bytes are
 * accepted into the store the record is NATIVE-owned — its session, sequence,
 * timestamp and digest are fixed, and recovery can drain it with no JS runtime
 * alive. This is the direct lesson of the Android blocker where recovery was
 * anchored in `start` and the only production caller was a button handler:
 * the guard proved the inner link of a chain whose outer link did not exist. On
 * RN the outer link is weaker still — the JS runtime may not exist at all when
 * recovery must run.
 *
 * **It duplicates nothing.** Contiguous slicing, `BatchIdentity`, ack matching,
 * prefix re-check, exact-prefix dequeue, quarantine policy, lease behaviour and
 * WorkManager retry all come from the shared
 * [DurableUploadPipeline] / [RecoveryCoordinator] / [RecoveryWorker]. This class
 * owns only the wiring.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object RnTransport {

    /** Durable unresolved-batch descriptor (see InFlightStore). */
    private const val INFLIGHT_FILE = "tracescout-rn-inflight.json"

    /** Typed states the host may surface. Deliberately no queue contents. */
    enum class State {
        NOT_CONFIGURED,
        READY,
        PRODUCER_UNSUPPORTED,
        AUTH_REJECTED,
        /**
 * The session this batch addresses does not exist YET. The queue is
 * retained byte-for-byte and the constrained worker retries the SAME
 * records under bounded backoff — unlike AUTH_REJECTED and
 * PRODUCER_UNSUPPORTED, this condition can clear by itself.
 */
        SESSION_PRECONDITION,
        PENDING_NETWORK,

        /**
 * F4-P0 Slice D: an eviction bracket a previous process left behind
 * could not be concluded at open (unknowable outcome, incomplete
 * authority scan, or a finalization that is not yet durable). The
 * queue is retained byte-for-byte; every append, bracket and drain
 * refuses typed; owed NO automatic retry — the next open retries.
 */
        STORAGE_RECOVERY_BLOCKED,

        /**
 * F4-P0 Slice D: an erasure of this install is requested and could
 * not be completed at open (the fence is held by another owner, or a
 * step could not be made durable). Nothing was destroyed; normal work
 * refuses until the governed teardown completes at a later open.
 */
        ERASURE_PENDING,
    }

    @Volatile private var store: EncryptedEventStore? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var lastState: State = State.NOT_CONFIGURED
    private val started = AtomicBoolean(false)
    // F2: there is NO per-process sequence counter. Every sequence is claimed
    // durably through LossAuthority (see `enqueue`); the number the record
    // carries is the number the authority owns, across process death.

    /**
 * Records accepted since the last drain request. A COUNT, never a policy:
 * the threshold that reads it lives beside it, in the transport, so the
 * bridge cannot acquire an opinion about when customer data is uploaded.
 */
    private val pendingSinceDrain = AtomicLong(0)

    /** Pending SERIALIZED bytes since the last drain request. */
    private val pendingBytes = AtomicLong(0)

    /**
 * Whether the first-pending deadline is already armed. One drain per
 * empty→non-empty transition; a second arming would be a second timer.
 */
    private val deadlineArmed = AtomicBoolean(false)

    /**
 * True while `drainOnce` holds the lease. A drain REQUEST made while this
 * is set must be CHAINED after the running drain, because `KEEP` on the
 * unique work silently discards it.
 */
    private val drainInFlight = AtomicBoolean(false)

    /**
 * `elapsedRealtime` at which the current/last drain began its FINAL store
 * read (`FlushSchedule.NO_DRAIN_TAIL` when none). See
 * `FlushSchedule.DRAIN_TAIL_GRACE_MS`.
 */
    private val drainTailAt = AtomicLong(FlushSchedule.NO_DRAIN_TAIL)

    private fun msSinceDrainTail(): Long {
        val t = drainTailAt.get()
        return if (t < 0L) FlushSchedule.NO_DRAIN_TAIL else android.os.SystemClock.elapsedRealtime() - t
    }

    /** Records dropped by a permanent RECORD-level rejection. Never a build fact. */
    @JvmField val quarantined = AtomicLong(0)

    /**
 * The session THIS PROCESS was told is over, or null.
 *
 * **A POINTER, NEVER THE FACT.** Terminality itself is durable and
 * lives in the authority; this field only says *which session id the next
 * empty drain should ask about*. The drain still asks
 * `AttributionSink.f7SessionFinalized`, which reads the durable ledger, so
 * a stale or forged value here cannot produce a finality checkpoint for a
 * session whose claim is not on disk. Storing the ANSWER here instead would
 * create a second authority over terminality — in memory, outliving nothing
 * which is precisely the divergence the durable claim exists to prevent.
 *
 * **WHY IT IS NEEDED AT ALL, measured.** `readOwnerOrBlank(records)`
 * derives the owning session from `records[0]`, and an EMPTY queue has no
 * record 0. The native producer answers the same question with its live
 * `identity.sessionId` (`TraceScoutMobile.uploadRaw` passes it as the
 * fallback); this producer holds no session — JS supplies it — so the one
 * that was declared over is the honest equivalent.
 *
 * **SCOPE, STATED RATHER THAN IMPLIED.** Per-process. A host that
 * declares session end and is then killed before a drain emits nothing from
 * this field on the next launch: the claim is still durable (nothing is
 * lost, which is the property that matters) but no later drain in a new
 * process knows which session to ask about. That is the SAME bound the
 * native seam documents, and widening it means scanning the authority root
 * for terminal-but-unemitted sessions — a recovery mechanism, not a seam.
 */
    @Volatile private var finalizedSession: String? = null

    /**
 * Native startup. Safe to call from `Application.onCreate` on the main
 * process, BEFORE any React runtime exists.
 *
 * Idempotent: a second call is a no-op rather than a second drain, so a
 * host that also calls it from an activity cannot start two uploaders.
 */
    fun start(ctx: Context): State {
        val app = ctx.applicationContext
        appContext = app

        // FIRST — before the worker hook, before the queue is opened, before
        // any log call anywhere on this path.
        //
        // `RecoveryTrace.log` returns early when `dir` is null, and `dir` is
        // set ONLY here. This class called `log` in five places and never
        // called `install`, so every RN checkpoint — and every checkpoint the
        // SHARED pipeline writes while serving RN — went nowhere. The result
        // was a producer with no durable diagnostic trail at all, which is
        // indistinguishable from a producer where nothing happened. That is
        // why a queue draining to 0 with zero HTTP requests could not be
        // explained: the explanation was being written to a null.
        //
        // Installed before `started.compareAndSet` deliberately: a second
        // start is a no-op for uploading, but the trace must be live for the
        // process regardless of which call arrived first.
        RecoveryTrace.install(app)
        RecoveryTrace.log("rn_trace_installed")

        if (!started.compareAndSet(false, true)) return lastState

        // F4-P0 Slice D (D6, U-2/U-3): an outstanding erasure is resumed
        // BEFORE the configuration is read and before any queue is opened.
        // Pending is a typed refusal of this start; nothing is destroyed, and
        // the latch is released so a later start can resume once the holder
        // of the fence is gone (X-10 — a platform operation, never a bypass).
        when (val e = com.tracescout.mobile.queue.ErasureFlow.resumeAtOpen(app.filesDir, erasureHomeOf(app))) {
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Pending -> {
                RecoveryTrace.log("rn_start_refused_erasure", "why" to e.reason.take(40))
                lastState = State.ERASURE_PENDING
                started.set(false)
                return lastState
            }
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Incomplete -> {
                RecoveryTrace.log("rn_start_refused_erasure", "why" to ("incomplete:" + e.reason).take(40))
                lastState = State.ERASURE_PENDING
                started.set(false)
                return lastState
            }
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Completed ->
                RecoveryTrace.log("rn_erasure_completed_at_open", "terminal" to e.terminalTeardown)
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.NotRequested -> Unit
        }

        val cfg = RnSecureConfig.load(app)
        if (cfg == null) {
            RecoveryTrace.log("rn_start_no_config")
            lastState = State.NOT_CONFIGURED
            // A start that found NO configuration must not burn the
            // single-flight latch. The legitimate host sequence is: boot
            // unconfigured -> onboarding installs the credential -> start
            // again in the SAME process. Latching here made that second start
            // a silent no-op returning the stale NOT_CONFIGURED, so the SDK
            // could never start without a process restart. Found by the
            // release-safe first-run configuration screen; the debug
            // broadcast flow always configured a FRESH process, which is why
            // it never met this. The latch exists to prevent a SECOND
            // UPLOADER, and an unconfigured pass starts none — releasing it
            // is safe by construction.
            started.set(false)
            return lastState
        }
        store = openStore(app)

        // The SAME hook the Android producer uses — WorkManager drains through
        // this one state machine. Set BEFORE the first drain so a Worker the
        // system instantiates early has something to call.
        RecoveryWorker.drainHook = { c -> drainForWorker(c) }

        RecoveryTrace.log("rn_start_enter")
        // F4-P0 Slice D (D1): OPEN-TIME RECOVERY of a bracket a previous process
        // left unresolved — before the first drain and before any admission. A
        // bracket that cannot be concluded leaves the queue retained and every
        // gate refusing typed; the start reports it rather than draining into
        // a refusal.
        when (val r = recoveryFor(app).recover()) {
            is com.tracescout.mobile.internal.queue.EvictionRecovery.Outcome.Blocked -> {
                RecoveryTrace.log("rn_start_recovery_blocked", "why" to r.reason.take(40))
                lastState = State.STORAGE_RECOVERY_BLOCKED
                return lastState
            }
            is com.tracescout.mobile.internal.queue.EvictionRecovery.Outcome.Resolved ->
                RecoveryTrace.log("rn_start_recovery_resolved", "lost" to r.lost, "didNotHappen" to r.didNotHappen)
            is com.tracescout.mobile.internal.queue.EvictionRecovery.Outcome.NotNeeded -> Unit
        }
        // F4-P2 (F4-16 / F4-17): reconcile a LOSSY queue — after the bracket
        // recovery, before the first admission. Blocked keeps every fence and is
        // reported; the drain below refuses typed on its own.
        when (val q = com.tracescout.mobile.internal.queue.QueueReconciler(homeFor(app), app.filesDir, inflightStoreOf(app)).reconcile()) {
            is com.tracescout.mobile.internal.queue.QueueReconciler.Outcome.Blocked ->
                RecoveryTrace.log("rn_start_reconcile_blocked", "why" to q.reason.take(40), "settled" to q.settled)
            is com.tracescout.mobile.internal.queue.QueueReconciler.Outcome.Resolved ->
                RecoveryTrace.log("rn_start_reconciled", "unreadable" to q.settledUnreadable, "disabled" to q.disabled,
                    "cleared" to q.cleared, "consumed" to q.consumed)
            is com.tracescout.mobile.internal.queue.QueueReconciler.Outcome.NotNeeded -> Unit
        }
        lastState = drainOnce(app)
        // A FOREGROUND STARTUP DRAIN HAS NO WORKER BEHIND IT.
        //
        // The new scheduling rule correctly refuses to arm the flat 2 s
        // first-pending deadline for RETRYABLE_REMAINING, because WorkManager's
        // bounded exponential backoff is supposed to own that retry. But that
        // owner exists only when the drain was entered FROM RecoveryWorker.
        // `start` is not. So a startup drain that ends on a transient session
        // or network refusal would get no deadline AND no worker — the new rule
        // turning a transient refusal into a stranded queue.
        handOffRetryObligation(app, lastState)
        return lastState
    }

    /**
 * Accept an RN record into the durable store.
 *
 * The caller supplies a BUILDER, not bytes: the sequence is assigned here,
 * natively, and stamped into the bytes the builder returns — so the number
 * inside the record and the number the store ordered it by are the SAME
 * number by construction. Taking finished bytes instead would leave the
 * sequence chosen by the caller and merely *counted* here, which is two
 * unreconciled sources for one value; the server refuses a batch whose
 * sequences are not contiguous, so a disagreement is not a cosmetic one.
 *
 * Assignment and append are one critical section, so records reach the
 * store in the order their sequences were issued — a gap the pipeline
 * would have to slice around cannot be created by interleaving.
 *
 * Numbering starts at 0, which is what `mobile_session_start` carries; an
 * `incrementAndGet` first assignment would make sequence 0 unreachable and
 * every session would begin at 1.
 *
 * Returns the assigned sequence, or -1 when the record was refused.
 */
    fun enqueue(ctx: Context, build: (Long) -> ByteArray): Long {
        val app = ctx.applicationContext
        val s = store ?: openStore(app).also { store = it }
        return try {
            var assigned = -1L
            var recordBytesLen = 0L
            var refusal: String? = null
            // F2 — THE SEQUENCE IS CLAIMED DURABLY BEFORE THE RECORD IS BUILT.
            // The builder is probed once with a placeholder to learn WHICH
            // session and event type it will produce (the same fields the
            // pipeline reads from the bytes); the authority for that session
            // then owns the number. A probe is not a record: nothing is stored
            // and no sequence is consumed by it.
            val probe = build(-1L)
            val sid = fieldOf(probe, "sessionId")
            val eventType = fieldOf(probe, "eventType")
            // F4-P0 Slice D (D8 / U-2): a recovery-blocked queue or a requested
            // erasure refuses BEFORE the admission — no number allocated, no
            // authority frame spent on a claim the governed append would refuse.
            com.tracescout.mobile.internal.queue.EvictionRecovery.blocks(homeFor(app))?.let { why ->
                RecoveryTrace.log("rn_enqueue_refused_blocked", "why" to why, "eventType" to eventType)
                return -1L
            }
            // F4-P2 child — GATE A of the reconciliation mutation gate: a durable
            // reconciliation fact binding this queue's image refuses BEFORE the
            // admission (no ticket, no append); Gate B re-asks the same predicate
            // inside the store's append.
            com.tracescout.mobile.internal.queue.QueueReconciler.mutationBlockReason(homeFor(app))?.let { why ->
                RecoveryTrace.log("rn_enqueue_refused_blocked", "why" to why, "eventType" to eventType)
                return -1L
            }
            val h = authorityFor(app, sid, s) ?: run {
                RecoveryTrace.log("rn_authority_refused", "why" to "authority_unavailable", "eventType" to eventType)
                return -1L
            }
            var ticket: LossAuthority.Ticket? = null
            synchronized(this) {
                ticket = when (val a = h.beginAdmission("adm-" + java.util.UUID.randomUUID(), "", eventType)) {
                    is LossAuthority.Admission.Owned -> a.ticket
                    is LossAuthority.Admission.Refused -> {
                        RecoveryTrace.log("rn_authority_refused", "why" to a.reason, "eventType" to eventType); return -1L
                    }
                    is LossAuthority.Admission.NotDurable -> {
                        RecoveryTrace.log("rn_authority_refused", "why" to "not_durable:" + a.reason, "eventType" to eventType); return -1L
                    }
                }
                assigned = ticket!!.sequence
                val bytes = build(assigned)
                recordBytesLen = bytes.size.toLong()
                // THE RESULT WAS DISCARDED ENTIRELY (`s.append(bytes)` as a
                // statement), so a storage refusal was unobservable at EVERY
                // call site: `append` does not throw — it returns
                // `AppendResult(false, "io_error"|"at_capacity"|…)` — and this
                // function returned `assigned` regardless, which is the
                // documented -1 contract being false for the whole class of
                // failures it exists for.
                //
                // F4-P0 Slice C: THE GOVERNED APPEND, NEVER THE BARE STORE.
                // `EncryptedEventStore.append` refuses at capacity and makes no
                // room; the ONE place room is made is the eviction bracket,
                // which both producers reach through this call.
                val r = bracketFor(app).append(bytes)
                if (!r.stored) refusal = r.reason ?: "unknown"
                // F2 settlement, inside the same critical section as the
                // append: ADMITTED on a COMMITTED append; a refusal is ONE loss
                // in the admission's own family; an indeterminate append
                // settles nothing (the next open reconciles it from disk).
                when {
                    r.stored && r.durability == EncryptedEventStore.Durability.COMMITTED -> h.settleAdmitted(ticket!!)
                    !r.stored -> h.settleLost(ticket!!, LossAuthority.Cause.APPEND_REFUSED)
                    else -> RecoveryTrace.log("rn_authority_settle_deferred", "sequence" to assigned, "durability" to r.durability.name)
                }
            }
            if (refusal != null) {
                // AND THE COUNTERS MUST NOT MOVE. `pendingSinceDrain` /
                // `pendingBytes` drive the flush schedule, so incrementing them
                // for a record that was never stored makes the transport plan
                // around telemetry that does not exist.
                //
                // THE SEQUENCE IS NOT REUSED. The authority now holds it as
                // LOST(APPEND_REFUSED) durably, so the gap is ACCOUNTED rather
                // than merely observable — and can never be reallocated, even
                // after a restart.
                RecoveryTrace.log("rn_enqueue_refused",
                    "reason" to refusal!!, "sequence" to assigned, "bytes" to recordBytesLen)
                return -1L
            }
            // All triggers below are OUTSIDE the lock: scheduling is a
            // WorkManager call and must never be held across an append.
            val n = pendingSinceDrain.incrementAndGet()
            val bytes = pendingBytes.addAndGet(recordBytesLen)

            // THE DECISION IS NOT RESTATED HERE. `FlushSchedule` owns when a
            // durable enqueue is guaranteed a future drain, so the enqueue
            // path, the drain-completion path and the guard cannot drift — and
            // so the decision is unit-testable without an Android runtime,
            // which is exactly what let the original gap ship.
            val d = FlushSchedule.onEnqueued(n, bytes, deadlineArmed.get())
            // `compareAndSet` remains the arbiter under concurrency: the pure
            // decision reads a snapshot, the CAS makes "exactly one" true even
            // when two threads see the same snapshot.
            if (d.armDeadline && deadlineArmed.compareAndSet(false, true)) {
                scheduleDeadlineDrain(ctx, afterCurrent = FlushSchedule.onDeadlineArmed(msSinceDrainTail()) ==
                    FlushSchedule.RequestPolicy.AFTER_CURRENT)
            }
            d.drainReason?.let { requestDrain(ctx, it) }
            assigned
        } catch (t: Throwable) {
            RecoveryTrace.log("rn_enqueue_failed", "code" to t.javaClass.simpleName)
            -1L
        }
    }

    /** Safe status. Never queue contents, never the credential. */
    fun state(): State = lastState

    /**
 * **The React Native producer's end-of-session terminal authority seam.**
 * The host application declares *"this session is over"*; the SDK never
 * infers it.
 *
 * This is the RN counterpart of `TraceScoutMobile.endSessionAuthoritatively`
 * and is deliberately the SAME fact written through the SAME durable door
 * (`LossAuthority.Handle.recordTerminal`) with the SAME vocabulary
 * (`TraceScoutMobile.SessionEndOutcome`). It adds no protocol, no record
 * type, no event and no second authority — a product decision asked for a
 * trigger, and the trigger it asked for already exists in the shared
 * pipeline; what was missing on this producer was a writer for the claim
 * that pipeline reads.
 *
 * **IT IS PRODUCT POLICY, NOT LIFECYCLE INFERENCE — IDENTICALLY TO THE
 * NATIVE SEAM.** `lifecycle("background")`, a JS `AppState` change, an
 * `Activity` teardown, a drained queue, `flushHint` and process death are
 * NOT this signal and must never be wired to it: terminality is one-way on
 * the server, so a resumable session reported final cannot be un-reported.
 * `RnBridge.endSessionAuthoritatively` is the only caller, it is reachable
 * only from the host's own `@ReactMethod`, and `F7TerminalSeamTest` pins
 * that no other production file in either producer names this function or
 * `recordTerminal` — so the closure is structural rather than a review
 * convention.
 *
 * **WHY THE SESSION ID IS A PARAMETER HERE AND REFUSED THERE.** The
 * native seam takes none, because `TraceScoutMobile` HOLDS the live
 * identity and a parameter would let a caller terminalize a session it is
 * not running. This producer holds no identity at all — JS owns the
 * session and supplies it on every path — so the equivalent guarantee is
 * enforced one layer up: `RnBridge` passes the identity from the SAME
 * supplier every emit uses, and never one a JS argument named. The
 * narrowing that remains here is `openExisting`, which refuses a session
 * with no authority ON DISK rather than creating one.
 *
 * **DURABLE BEFORE ASSERTED.** The claim is recorded and nothing is
 * emitted. The finality-only checkpoint is produced by the shared
 * metadata-only path on a drain, which is why this — unlike the native
 * seam — *does* ask for one: #2760 says the checkpoint is emitted AT
 * definitive finalization, and on this producer nothing else would ask.
 * It is a HINT through the existing `FlushSchedule` door, never an inline
 * upload, so there is still exactly one drain state machine.
 *
 * Idempotent and one-way: a second call is [ALREADY_RECORDED] and there is
 * no inverse. Never throws into the host app.
 *
 * @return a [TraceScoutMobile.SessionEndOutcome] NAME. A name, not the enum,
 * because `:sdk` is an `implementation` dependency of this module and is
 * therefore invisible to the RN adapter that calls this — the same reason
 * `RnBridge.DurableSink.stateName` crosses that seam as a String.
 */
    fun endSessionAuthoritatively(ctx: Context, sessionId: String): String {
        val app = ctx.applicationContext
        // A blank id can only come from a caller with no live session. Refusing
        // is the honest answer; defaulting to "whatever authority is open"
        // would terminalize a session nobody declared over.
        if (sessionId.isBlank()) {
            RecoveryTrace.log("rn_session_end_no_session", "why" to "blank")
            return com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.NO_SESSION.name
        }
        val h = when (val o = LossAuthority.openExisting(app.filesDir, sessionId)) {
            is LossAuthority.Open.Ok -> o.handle
            is LossAuthority.Open.Unavailable -> {
                // NOT durable-vs-absent folded together: `openExisting` refuses
                // `no_authority` for a session that never had one (nothing to
                // end) and a typed reason for one that cannot be read (a thing
                // that exists and is unreachable). A reader needs them apart.
                RecoveryTrace.log("rn_session_end_no_authority", "why" to o.reason.take(40))
                return if (o.reason == "no_authority")
                    com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.NO_SESSION.name
                else com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.NOT_DURABLE.name
            }
        }
        val w = try {
            // The intent id makes the write idempotent under core's own
            // already-applied rule; the POLICY refusal for a second request
            // arrives as PolicyDeclined, never as a core refusal.
            h.recordTerminal("term-" + java.util.UUID.randomUUID())
        } catch (t: Throwable) {
            RecoveryTrace.log("rn_session_end_threw", "why" to t.javaClass.simpleName)
            return com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.NOT_DURABLE.name
        }
        val outcome = when (w) {
            is LossAuthority.Write.Written ->
                com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.RECORDED
            is LossAuthority.Write.PolicyDeclined ->
                com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.ALREADY_RECORDED
            is LossAuthority.Write.NotDurable ->
                com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.NOT_DURABLE
            is LossAuthority.Write.Refused ->
                com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.REFUSED
        }
        // THE POINTER IS SET FOR *ALREADY_RECORDED* TOO, and that is the
        // retry case working. A first call whose drain never ran, followed by a
        // second after a `flushHint`, must still reach the empty-drain trigger
        // the claim is on disk either way and the gate re-reads it, so the
        // only thing the second call adds is another chance to be asked.
        if (outcome == com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.RECORDED ||
            outcome == com.tracescout.mobile.TraceScoutMobile.SessionEndOutcome.ALREADY_RECORDED
        ) {
            finalizedSession = sessionId
            // Not an upload. The SAME unique, network-constrained worker every
            // other RN drain goes through — see `requestDrain`.
            requestDrain(app, "session_end")
        }
        RecoveryTrace.log("rn_session_end", "outcome" to outcome.name)
        return outcome.name
    }

    /** Phase 2: store an ALREADY-REDACTED, encoded frame for upload. Native only. */
    fun enqueueFrame(ctx: Context, header: com.tracescout.rn.FrameHeader, bytes: ByteArray): Boolean {
        val ok = RnFrameStore.enqueue(ctx, header, bytes)
        RecoveryTrace.log("rn_frame_enqueued", "ok" to ok, "bytes" to bytes.size)
        return ok
    }

    /** Safe frame-store diagnostics (counts/bytes only). Native only. */
    fun framesStatus(ctx: Context): Pair<Int, Long> = RnFrameStore.count(ctx) to RnFrameStore.bytes(ctx)

    /**
 * Ask for a drain. **The caller states a MOMENT; this class decides what
 * to do about it.**
 *
 * Found on the first RN device run: the queue read 4420B before a
 * force-stop and 4420B after — nothing uploaded, because `drainOnce` was
 * reachable only from `start` (once per process) and from
 * `drainForWorker`, and nothing on the RN path ever scheduled that worker.
 * A live RN session therefore accumulated records for its whole lifetime
 * and could only upload on the NEXT cold launch.
 *
 * This deliberately does NOT upload inline. It schedules the SAME unique,
 * network-constrained [RecoveryWorker] recovery uses, so there is still
 * exactly one drain state machine and one set of ack/dequeue rules. Two
 * uploaders racing the same prefix is the defect the lease exists to
 * prevent; adding a second path here would reintroduce it under a
 * friendlier name.
 *
 * Coalescing is WorkManager's: UNIQUE work with KEEP, so N hints in a row
 * are one job. A hint is never a guarantee of immediacy — with no network
 * the constraint holds the job until there is one, which is the whole
 * point of routing through the worker.
 */
    fun requestDrain(ctx: Context, reason: String): Boolean {
        pendingSinceDrain.set(0)
        pendingBytes.set(0)
        deadlineArmed.set(false)
        val policy = FlushSchedule.onDrainRequested(drainInFlight.get(), msSinceDrainTail())
        val scheduled = when (policy) {
            FlushSchedule.RequestPolicy.KEEP -> RecoveryWorker.enqueue(ctx.applicationContext)
            FlushSchedule.RequestPolicy.AFTER_CURRENT ->
                RecoveryWorker.enqueueAfterCurrent(ctx.applicationContext, 0L)
        }
        RecoveryTrace.log(
            "rn_drain_requested", "reason" to reason, "policy" to policy.name,
            "scheduled" to scheduled.toString())
        return scheduled
    }

    /**
 * One drain through the SHARED pipeline, under the SHARED lease.
 *
 * The lease is what stops a foreground drain and the WorkManager drain
 * owning the same prefix at once; it is released in a `finally` because a
 * lease nobody can release silently disables recovery for the whole
 * staleness window — the failure it exists to prevent.
 */
    private fun drainOnce(ctx: Context): State {
        val cfg = RnSecureConfig.load(ctx) ?: return State.NOT_CONFIGURED
        val s = store ?: return State.NOT_CONFIGURED
        if (!RecoveryCoordinator.acquire(ctx)) {
            RecoveryTrace.log("rn_lease_refused")
            return lastState
        }
        drainInFlight.set(true)
        try {
            // F4-P0 Slice D (D4): a bracket still unresolved from an earlier
            // open is RETRIED before this drain — the "retry later" a failed
            // finalization is owed. Still blocked ⇒ typed, zero requests.
            val hm = homeFor(ctx.applicationContext)
            if (com.tracescout.mobile.internal.queue.EvictionRecovery.blocks(hm)?.startsWith("bracket") == true) {
                val r = recoveryFor(ctx.applicationContext).recover()
                if (r is com.tracescout.mobile.internal.queue.EvictionRecovery.Outcome.Blocked) {
                    RecoveryTrace.log("rn_drain_recovery_blocked", "why" to r.reason.take(40))
                    return State.STORAGE_RECOVERY_BLOCKED
                }
            }
            // an earlier change: an EMPTY read is this drain's final look at the store, so
            // the tail opens BEFORE it; a non-empty read is not, so it closes
            // again and settle's re-read reopens it.
            drainTailAt.set(android.os.SystemClock.elapsedRealtime())
            val records = s.readRecordsSafely()
            // **THE EMPTY BRANCH IS NO LONGER A DEAD END WHEN A SESSION HAS
            // BEEN DECLARED OVER.** This `return` is exactly the reason
            // the R-2 chunkless finality checkpoint could not be produced by
            // this producer: `DurableUploadPipeline` owns the trigger, and no
            // RN drain ever reached it with an empty queue. The native producer
            // closed the same gap by routing its three empty drains through the
            // unchanged `uploadRaw`; this is the one RN site of that shape.
            //
            // SCOPED TO THE CASE THAT CAN PRODUCE SOMETHING. An ordinary
            // empty drain — no session declared over — still returns here, with
            // the same trace and the same state, so the overwhelmingly common
            // path is byte-unchanged and a drain never builds a pipeline for a
            // queue that has nothing to say.
            //
            // THE POINTER IS NOT CLEARED, deliberately. The ONCE guarantee
            // lives in the core (a settled terminal boundary refuses a second
            // claim) and the gate re-reads the DURABLE ledger on every pass, so
            // re-asking is safe; clearing on the first attempt would discard
            // the only pointer this process has if that attempt met no network.
            val finalizing = if (records.isEmpty()) finalizedSession else null
            if (records.isEmpty() && finalizing == null) {
                RecoveryTrace.log("rn_queue_empty")
                return State.READY
            }
            // an earlier change: an EMPTY read is this drain's final look at the store, so
            // the tail stays OPEN for it — including the finalizing case, which
            // removes nothing and appends nothing.
            if (records.isNotEmpty()) drainTailAt.set(FlushSchedule.NO_DRAIN_TAIL)
            val sink = object : DurableUploadPipeline.ResultSink {
                override var last: Uploader.Result? = null
            }
            val pipeline = DurableUploadPipeline(
                // The QUEUE HOME, never a bare store (Slice B, B4): the
                // pipeline's five check-then-mutate sequences run as regions on
                // the home's locks. Same home identity as `openStore` resolves.
                home = homeFor(ctx.applicationContext),
                // F2 mutation gate, PER SLICE (liveness B2). It used to ask the
                // authority of whatever session was at the HEAD of the queue when
                // the drain started — a fact about the drain, not about the
                // records being removed — so a head session with pending state
                // froze removals for every later session in the same queue, and
                // a clean head session let a pending later session's records go
                // unchecked. The owning session is now derived from the slice.
                mutationGate = { slice ->
                    LossAuthority.mutationFenceFor(ctx.applicationContext.filesDir, slice)
                },
                // F4-P1: the seam the quarantine bracket settles LOST(QUARANTINED) through.
                evictionSeam = com.tracescout.mobile.internal.queue.EvictionAttribution.forFilesDir(ctx.applicationContext.filesDir),
                poster = { pid, body ->
                    Uploader(cfg.baseUrl, cfg.ingestKey).postChunk(pid, body)
                },
                projectId = cfg.projectId,
                maxRetries = MAX_RETRIES,
                appContext = ctx,
                backoffMillis = { attempt -> backoffMillis(attempt) },
                results = sink,
                quarantine = object : DurableUploadPipeline.QuarantineSink {
                    override fun onQuarantined(count: Int, code: String?) {
                        quarantined.addAndGet(count.toLong())
                    }
                },
                park = openParkStore(ctx.applicationContext),
                // The unresolved-batch descriptor, durable so the invariant
                // survives process death: a batch that has been submitted and
                // not settled is retried with the SAME bytes and identity, and
                // records appended afterwards cannot extend it. See
                // InFlightStore — RN iOS 0.2.0 (7) committed sequences 19-26
                // twice because the next drain re-formed a LONGER prefix.
                inflight = inflightStoreOf(ctx.applicationContext),
                // F2: committed delivery / park / quarantine removals are
                // attributed per owning session through the one facade.
                attribution = com.tracescout.mobile.internal.queue.AttributionSink.forFilesDir(ctx.applicationContext.filesDir),
                // F4-P2: a drain that meets a LOSSY image reconciles it first (F4-16).
                reconciler = com.tracescout.mobile.internal.queue.QueueReconciler(
                    homeFor(ctx.applicationContext), ctx.applicationContext.filesDir, inflightStoreOf(ctx.applicationContext)),
            )
            // THE OWNER OF AN EMPTY DRAIN CANNOT COME FROM THE RECORDS.
            // `readOwnerOrBlank` reads `records[0]`; an empty queue has none,
            // and a blank id is refused by the trigger (`no_session`). The
            // session declared over IS the owner of a chunkless finalization —
            // the same answer the native producer gives by passing its live
            // `identity.sessionId`.
            pipeline.upload(records, fallbackSessionId = finalizing ?: readOwnerOrBlank(records))
            // THE DISPOSITION MUST REACH THE SCHEDULER OR THE FIX IS DEAD
            // CODE. The pipeline can now distinguish "retryable bytes remain"
            // from "permanently undeliverable bytes remain"; if that answer
            // never left the pipeline, `onDrainSettled` would still see only a
            // count and the 2 s loop would survive the whole change — the
            // defect class this repository keeps paying for.
            lastPipelineDisposition = pipeline.disposition
            // THE RETURNED STATE DESCRIBES THE RECORD LANE, so it is read
            // from the record sink BEFORE the frame phase runs. The frame
            // drain below cannot change it, and classifying afterwards would
            // only make that dependency look real.
            val state = classify(sink.last)
            // Phase 2: frame BYTES drain after the metadata records, through
            // the same credential, into the existing frame endpoint. The
            // metadata event can precede its bytes (server marks the frame
            // pending → verified on arrival); bytes never precede a session.
            try {
                val fo = RnFrameStore.drain(ctx, cfg.projectId, Uploader(cfg.baseUrl, cfg.ingestKey))
                if (fo.delivered + fo.refused + fo.retained + fo.unreadable != 0) {
                    RecoveryTrace.log("rn_frames_drained", "delivered" to fo.delivered,
                        "refused" to fo.refused, "retained" to fo.retained, "unreadable" to fo.unreadable)
                }
            } catch (_: Throwable) {}
            // THE DRAIN THAT RAN SETTLES THE PENDING ACCOUNTING — not the
            // hint that asked for one. `requestDrain` was the ONLY resetter, so
            // a drain reached through the deadline or the worker left
            // `pendingSinceDrain` non-zero and `deadlineArmed` true FOREVER:
            // no further deadline was ever armed for the life of the process.
            // Measured on a real active session — a captured network request
            // sat in the durable queue for 3m43s, foregrounded and idle. This
            // is the vc14 stranded-state fix and it must survive the frame
            // phase being inserted above it.
            settlePendingAfterDrain(ctx)
            return state
        } finally {
            drainInFlight.set(false)
            RecoveryCoordinator.release(ctx)
        }
    }

    /**
 * Re-establish the deadline obligation from what the store ACTUALLY still
 * holds, once per completed drain.
 *
 * Reading the store rather than zeroing blindly is what closes the race: a
 * record enqueued WHILE the drain was in flight is not counted by the
 * in-memory counter this resets, but it IS in the store, so the re-arm
 * covers it. Zeroing alone would recreate the same defect in a narrower
 * window — the honest question is "does the queue still hold anything",
 * and only the queue can answer it.
 *
 * This is NOT the deadline sliding forward. The previous deadline was
 * CONSUMED by the drain that just ran; at most one is armed at any moment,
 * and a record arriving mid-window still cannot postpone the one already
 * ticking (that path is `enqueue`, whose `compareAndSet` refuses).
 *
 * An unreadable store is treated as NON-EMPTY: re-arming costs one bounded,
 * coalesced job, while assuming empty would strand records we could not
 * see. Unknown is never evidence of nothing.
 */
    /**
 * The pipeline's reason for ending, carried to the scheduler.
 *
 * A SEPARATE ENUM ON EACH SIDE OF THE BINARY SEAM. `:sdk` owns
 * `DurableUploadPipeline.Disposition` and `rn-core` owns
 * `FlushSchedule.DrainDisposition`; `:sdk` has no rn-core dependency and a
 * composite build is impossible here (AGP 9.3.1 needs Gradle >= 9.5.0 while
 * the RN app is pinned to 8.14.3). This transport is the one place that
 * sees both, so it is the one place the mapping belongs.
 */
    @Volatile
    private var lastPipelineDisposition: DurableUploadPipeline.Disposition =
        DurableUploadPipeline.Disposition.DELIVERABLE_REMAINING

    private fun mapDisposition(d: DurableUploadPipeline.Disposition): FlushSchedule.DrainDisposition =
        when (d) {
            DurableUploadPipeline.Disposition.EMPTY ->
                FlushSchedule.DrainDisposition.EMPTY
            DurableUploadPipeline.Disposition.DELIVERABLE_REMAINING ->
                FlushSchedule.DrainDisposition.DELIVERABLE_REMAINING
            DurableUploadPipeline.Disposition.RETRYABLE_REMAINING ->
                FlushSchedule.DrainDisposition.RETRYABLE_REMAINING
            DurableUploadPipeline.Disposition.TERMINAL_MOVE_PENDING ->
                FlushSchedule.DrainDisposition.TERMINAL_MOVE_PENDING
            DurableUploadPipeline.Disposition.TERMINAL_MOVE_BLOCKED ->
                FlushSchedule.DrainDisposition.TERMINAL_MOVE_BLOCKED
            DurableUploadPipeline.Disposition.TERMINAL_MOVED_AND_MORE_REMAIN ->
                FlushSchedule.DrainDisposition.TERMINAL_MOVED_AND_MORE_REMAIN
            // Slice B. A lock layer refused: another process holds the install,
            // which clears on its own — the bounded retry class.
            DurableUploadPipeline.Disposition.QUEUE_UNAVAILABLE ->
                FlushSchedule.DrainDisposition.RETRYABLE_REMAINING
            // Protection could not be admitted or is unreadable, or the
            // no-progress bound refused before POST: owed NO automatic network
            // retry (a scheduled retry against an unchanged local state is the
            // loop the bound exists to end). The bytes stay; a real local
            // change (an append) requests a drain through the ordinary path
            // and rearms delivery.
            DurableUploadPipeline.Disposition.PROTECTION_UNAVAILABLE,
            DurableUploadPipeline.Disposition.NO_PROGRESS_BOUND,
            // Slice C: an unresolved eviction bracket owns the queue until
            // Slice D's recovery resolves it — the same no-automatic-retry
            // class, for the same reason: a schedule cannot change it.
            DurableUploadPipeline.Disposition.BRACKET_UNRESOLVED ->
                FlushSchedule.DrainDisposition.TERMINAL_MOVE_BLOCKED
            // Slice D (U-2): the install is being erased — no schedule can
            // change that either; the governed teardown clears it.
            DurableUploadPipeline.Disposition.ERASURE_REQUESTED ->
                FlushSchedule.DrainDisposition.TERMINAL_MOVE_BLOCKED
            // F4-P2 C2: a durable reconciliation fact still BINDS this
            // queue's image after the reconciler had its chance, so the ENTIRE
            // ordinary mutation surface is frozen. Same no-automatic-retry
            // class, for the same reason as the three above: a schedule cannot
            // change durable state, and only the governed reconciliation (or,
            // for a retained-evidence fact, an erasure) releases it. An
            // ordinary local change still requests a drain through the usual
            // path, which is how the release is noticed without a timer.
            //
            // THIS ARM IS DELIBERATELY NOT GROUPED WITH THE THREE ABOVE, and
            // the reason is a MEASUREMENT rather than style. Slice D's ACCEPTED
            // pin `SliceDSourceTest SD5` matches the literal
            // `Disposition.ERASURE_REQUESTED -> … TERMINAL_MOVE_BLOCKED`, so
            // folding ERASURE_REQUESTED into a shared arm deletes the arrow it
            // reads and fails an accepted slice's pin while the CONTRACT that
            // pin states is untouched. Re-pointing the pin was the other
            // available answer; giving this value its own arm changes only NEW
            // code and leaves the frozen literal AND its contract intact.
            // Keep each value's arrow its own.
            DurableUploadPipeline.Disposition.RECONCILIATION_BLOCKED ->
                FlushSchedule.DrainDisposition.TERMINAL_MOVE_BLOCKED
        }

    private fun settlePendingAfterDrain(ctx: Context) {
        val s = store ?: return
        // an earlier change: the tail opens BEFORE the final read — a record that misses
        // this read is the one nothing else can cover.
        drainTailAt.set(android.os.SystemClock.elapsedRealtime())
        val remaining = try {
            s.readAll().records.size
        } catch (_: Throwable) {
            RecoveryTrace.log("rn_pending_settle_unreadable")
            -1
        }
        val d = FlushSchedule.onDrainSettled(remaining, mapDisposition(lastPipelineDisposition))
        if (d.resetCounters) {
            pendingSinceDrain.set(0)
            pendingBytes.set(0)
            deadlineArmed.set(false)
        }
        RecoveryTrace.log("rn_pending_settled", "remaining" to remaining.toString())
        if (d.armDeadline && deadlineArmed.compareAndSet(false, true)) {
            scheduleDeadlineDrain(ctx, afterCurrent = true)
        }
        // A terminal head was archived and ordinary work is behind it: continue
        // NOW. Making a healthy later session wait 2 s behind a dead one it has
        // nothing to do with is latency with no purpose — and `requestDrain`
        // preserves the existing pass bounds, so a large parked session cannot
        // monopolise the worker.
        d.drainReason?.let { requestDrain(ctx, it) }
    }

    /**
 * The typed state a producer-capability refusal must produce.
 *
 * `producer_unsupported` and `auth_rejected` are BUILD/CREDENTIAL facts, not
 * record facts: the queue is intact, nothing retries, and a compatible build
 * or a corrected key drains the same records. Returning READY here would
 * make an app that can never upload look healthy.
 */
    private fun classify(r: Uploader.Result?): State = when (r?.code) {
        // Parked and unparkable are the SAME fact for the host: a producer
        // -capability refusal that no retry can clear. They differ only in
        // whether the bytes moved out of the active queue, which is an
        // internal durability detail, not a state the app should branch on.
        "producer_unsupported", "producer_parked" -> State.PRODUCER_UNSUPPORTED
        "auth_rejected" -> State.AUTH_REJECTED
        "session_precondition_missing" -> State.SESSION_PRECONDITION
        // F4-P0 Slice D: the pipeline's two pre-request refusals of its own.
        "bracket_unresolved", "bracket_marker_unreadable" -> State.STORAGE_RECOVERY_BLOCKED
        "erasure_requested" -> State.ERASURE_PENDING
        null -> State.READY
        else -> State.PENDING_NETWORK
    }

    /** The ONE durable in-flight descriptor file — the pipeline's and the reconciler's are the same store. */
    private fun inflightStoreOf(app: Context): com.tracescout.mobile.internal.queue.InFlightStore =
        com.tracescout.mobile.internal.queue.InFlightStore.file(File(app.filesDir, INFLIGHT_FILE))

    /** The recovery driver over the memoized home: the production seam, the production scan. */
    private fun recoveryFor(app: Context): com.tracescout.mobile.internal.queue.EvictionRecovery =
        com.tracescout.mobile.internal.queue.EvictionRecovery(
            homeFor(app), com.tracescout.mobile.internal.queue.EvictionAttribution.forFilesDir(app.filesDir),
            { LossAuthority.scanUnresolvedEvictionsAtOpen(app.filesDir) },
        )

    /**
 * The erasure flow's home factory: the SAME construction `homeFor` uses,
 * for whichever queue name the flow needs (an erasure covers BOTH queues
 * of the install). The queue key is resolved here, in the producer, as
 * the specification pins it; the flow never names it.
 */
    private fun erasureHomeOf(app: Context): (String) -> com.tracescout.mobile.queue.QueueHome = { name ->
        com.tracescout.mobile.queue.QueueHome.of(File(app.filesDir, name)) { queueFile ->
            EncryptedEventStore(queueFile, StoreKeys.queueKey())
        }
    }

    /** A record states its own owner; an unreadable one attributes to blank. */
    /** One string field off the producer's own bytes; blank when unreadable. */
    private fun fieldOf(b: ByteArray, key: String): String = try {
        org.json.JSONObject(String(b, Charsets.UTF_8)).optString(key)
    } catch (_: Throwable) { "" }

    /**
 * The process-wide authority handle for [sid]. The queue snapshot is read
 * ONLY on the first open of a session (adoption of a pre-F2 queue /
 * reconciliation after a restart), never per record.
 */
    private fun authorityFor(app: Context, sid: String, s: EncryptedEventStore): LossAuthority.Handle? {
        if (sid.isEmpty()) return null
        LossAuthority.peek(app.filesDir, sid)?.let { return it }
        val rr = s.readAll()
        return when (val o = LossAuthority.open(app.filesDir, sid, LossAuthority.QueueSnapshot(rr.records, rr.isTrustworthy))) {
            is LossAuthority.Open.Ok -> o.handle
            is LossAuthority.Open.Unavailable -> null
        }
    }

    private fun readOwnerOrBlank(records: List<ByteArray>): String = try {
        org.json.JSONObject(String(records[0], Charsets.UTF_8)).optString("sessionId")
    } catch (_: Throwable) { "" }

    /**
 * A SEPARATE queue file from the Android producer's, under the SAME
 * Keystore-backed key policy. Separate because two producers sharing one
 * file would interleave their sequence counters into a single store, and a
 * whole-store batch could then never be contiguous — the exact defect that
 * refused crash evidence and quarantined it.
 */
    /**
 * UAT-ONLY: read the durable queue through the PRODUCTION decrypt path.
 *
 * IT USES `EncryptedEventStore.readAll`, the same function the uploader
 * uses. A parallel toy decoder would be a second implementation whose
 * behaviour could diverge from the one that actually ships — and a wire
 * proof against a decoder nobody uses proves nothing about the bytes that
 * leave the device.
 *
 * The result carries `discarded`/`truncatedTail` verbatim, so a caller
 * cannot mistake "the store dropped a corrupt frame" for "there were no
 * records". An unreadable store is a distinct answer, not an empty one.
 */
    internal fun uatReadAll(ctx: Context): EncryptedEventStore.ReadResult {
        val s = store ?: openStore(ctx.applicationContext).also { store = it }
        return s.readAll()
    }

    /**
 * ONE QUEUE HOME per process for the RN queue (Slice B, B4). Memoized on
 * the canonical file, so `start`, `enqueue`, the drain and the UAT reader
 * all resolve the same store, the same locks and the same protection state.
 */
    private fun homeFor(app: Context): com.tracescout.mobile.queue.QueueHome =
        com.tracescout.mobile.queue.QueueHome.of(
            // The name comes from the canonical queue-key inventory — see QueueKeyStores.
            File(app.filesDir, com.tracescout.mobile.queue.QueueKeyStores.RN_QUEUE),
        ) { queueFile ->
            EncryptedEventStore(
                queueFile,
                // Throws rather than falling back to an in-process key: something that
                // merely resembles encryption is worse than an honest failure.
                StoreKeys.queueKey(),
            )
        }

    private fun openStore(app: Context) = homeFor(app).store()

    @Volatile private var bracket: com.tracescout.mobile.internal.queue.EvictionBracket? = null

    /**
 * F4-P0 Slice C — the ONE governed append home for this producer, over the
 * same memoized queue home `openStore` resolves, wired to the production
 * eviction-attribution seam for this install's authority root.
 */
    private fun bracketFor(app: Context): com.tracescout.mobile.internal.queue.EvictionBracket =
        bracket ?: com.tracescout.mobile.internal.queue.EvictionBracket(
            homeFor(app),
            com.tracescout.mobile.internal.queue.EvictionAttribution.forFilesDir(app.filesDir),
        ).also { bracket = it }

    /**
 * The PARKED store — records a producer-capability refusal permanently
 * excludes from delivery, preserved losslessly under the SAME
 * Keystore-backed key as the live queue. Separate files from the Android
 * producer's for the same reason the live queues are separate.
 */
    private fun openParkStore(app: Context): ParkStore = try {
        EncryptedParkStore(
            File(app.filesDir, com.tracescout.mobile.queue.QueueKeyStores.RN_PARKED_RECORDS),
            File(app.filesDir, com.tracescout.mobile.queue.QueueKeyStores.RN_PARKED_MANIFEST),
            StoreKeys.queueKey(),
        )
    } catch (t: Throwable) {
        // Fail CLOSED: no parked store means no park, which means the slice is
        // RETAINED in place. Never the reverse.
        RecoveryTrace.log("rn_park_store_unavailable", "code" to t.javaClass.simpleName)
        ParkStore.none()
    }

    /**
 * Safe parked diagnostics — batch/record counts and bytes only.
 *
 * Deliberately NOT a React method. The bridge may state semantics; it may
 * not read, mutate, reactivate or delete parked customer data, and adding
 * a JS accessor is the first step toward a JS mutator. Operators read this
 * natively or from the durable trace, which records the same counts at
 * every park.
 */
    fun parkedStatus(ctx: Context): ParkStatus =
        try { openParkStore(ctx.applicationContext).status() } catch (_: Throwable) {
            ParkStatus(0, 0, 0L)
        }

    private fun EncryptedEventStore.readRecordsSafely(): List<ByteArray> =
        try { readAll().records } catch (_: Throwable) { emptyList() }

    /**
 * The WorkManager entry point. NEVER retries a producer/auth refusal —
 * returning RETRY there is precisely the busy loop the contract forbids,
 * because no number of retries can make an old build emit fields it does
 * not have.
 */
    /**
 * Who owes the retry for a finished drain, expressed ONCE.
 *
 * DERIVED FROM THE HOST STATE, NOT FROM `Disposition.RETRYABLE_REMAINING`.
 * Auth uses that disposition internally — correctly, since a replaced
 * credential delivers the same records — but the HOST contract deliberately
 * treats `AUTH_REJECTED` as non-auto-retrying: the SDK must not spin against
 * a credential only the app can fix. Scheduling from the disposition alone
 * would quietly make auth auto-retry.
 *
 * One helper so `start` and `drainForWorker` cannot drift about which
 * states are the worker's problem.
 */
    private fun retryIsOwedToWorker(state: State): Boolean = when (state) {
        // Transient and self-clearing: an opener may originate the session, a
        // network may return.
        State.SESSION_PRECONDITION, State.PENDING_NETWORK -> true
        // A credential or a build fact. Neither clears by retrying.
        State.AUTH_REJECTED, State.PRODUCER_UNSUPPORTED -> false
        State.READY, State.NOT_CONFIGURED -> false
        // Slice D: owed no automatic retry — the next OPEN retries recovery,
        // and an erasure completes under the fence at the next open.
        State.STORAGE_RECOVERY_BLOCKED, State.ERASURE_PENDING -> false
    }

    private fun handOffRetryObligation(ctx: Context, state: State) {
        if (!retryIsOwedToWorker(state)) return
        val q = RecoveryWorker.enqueue(ctx.applicationContext)
        RecoveryTrace.log(
            if (q) "startup_retry_handed_to_worker" else "startup_retry_handoff_failed",
            "state" to state.name)
    }

    private fun drainForWorker(ctx: Context): RecoveryWorker.Outcome {
        val st = drainOnce(ctx)
        lastState = st
        // THE WORKER'S RETRY DECISION MUST COME FROM THE PIPELINE, NOT FROM
        // THE HTTP RESULT — and this gap could strand data.
        //
        // `classify` reads `sink.last`, an HTTP result. Two terminal
        // LOCAL-archival paths legitimately produce NO http result at all: a
        // restart resuming a persisted terminal descriptor, and a later slice of
        // an already-terminal session. Both are request-free by design — that is
        // the ZERO-HTTP property A promises. But `sink` is fresh per drain, so
        // `sink.last == null` -> classify == READY -> Outcome.DONE, while the
        // corrected FlushSchedule correctly refuses to arm the 2 s network
        // deadline. The combination is:
        //
        // terminal bytes still active
        // + no WorkManager retry
        // + no deadline
        // = STRANDED until some unrelated external trigger
        //
        // So the local-move obligation is read from the disposition directly.
        // AND NOT BY FABRICATING AN HTTP RESULT to steer classify — a fake
        // transport result to obtain a scheduling outcome would put a lie in the
        // one place this file exists to keep honest.
        when (lastPipelineDisposition) {
            DurableUploadPipeline.Disposition.TERMINAL_MOVE_BLOCKED ->
                // NO AUTOMATIC RETRY OF ANY KIND. Preservation capacity is
                // exhausted (or no store is wired), and `ParkStore` has no
                // delete, clear or reactivate path — its capacity never shrinks,
                // so a bounded backoff here is an INFINITE local loop against a
                // condition with no mechanism to change. That would trade an
                // infinite 2 s HTTP loop for an infinite exponential LOCAL one:
                // better load profile, identical wrong contract.
                //
                // PERMANENT stops the worker. Bytes and terminal descriptor are
                // retained, and a later ordinary launch may try once — an app
                // lifecycle event, never a schedule.
                return RecoveryWorker.Outcome.PERMANENT
            DurableUploadPipeline.Disposition.TERMINAL_MOVE_PENDING ->
                // The retry owed is a LOCAL park. WorkManager's own bounded,
                // jittered exponential backoff (30 s base) owns it — an
                // existing, correctly-bounded mechanism, not a new fixed timer.
                // The retried drain resumes the park from the persisted
                // terminal descriptor and performs ZERO network requests.
                return RecoveryWorker.Outcome.RETRY
            DurableUploadPipeline.Disposition.TERMINAL_MOVED_AND_MORE_REMAIN ->
                // The queue advanced and `settlePendingAfterDrain` already
                // requested the immediate continuation; a worker retry of the
                // old head would be pointless.
                return RecoveryWorker.Outcome.DONE
            else -> Unit
        }
        return when (st) {
            State.READY, State.NOT_CONFIGURED -> RecoveryWorker.Outcome.DONE
            State.PRODUCER_UNSUPPORTED, State.AUTH_REJECTED -> RecoveryWorker.Outcome.PERMANENT
            // A missing session CAN clear by itself, so this is the one
            // retain-class that stays eligible for the worker's own bounded,
            // jittered exponential backoff. PERMANENT would strand records
            // that a later originating opener would deliver; an immediate
            // reschedule would be a storm.
            State.SESSION_PRECONDITION -> RecoveryWorker.Outcome.RETRY
            State.PENDING_NETWORK -> RecoveryWorker.Outcome.RETRY
            // Slice D: no schedule can conclude a bracket or complete an
            // erasure; both are retried at the next OPEN, never by the worker.
            State.STORAGE_RECOVERY_BLOCKED, State.ERASURE_PENDING -> RecoveryWorker.Outcome.PERMANENT
        }
    }

    /**
 * The 10-second cap, as ONE delayed job — not a timer and not polling.
 *
 * WorkManager's `setInitialDelay` on the SAME unique work name means this
 * coalesces with every other trigger: whichever fires first runs the drain,
 * and the rest are absorbed by KEEP. There is no periodic tick anywhere in
 * this class, and no second uploader.
 */
    private fun scheduleDeadlineDrain(ctx: Context, afterCurrent: Boolean = false) {
        // `afterCurrent` exists because the settle path runs INSIDE the
        // unique work, where KEEP discards the arm and still reports success.
        val armed = if (afterCurrent) {
            RecoveryWorker.enqueueAfterCurrent(ctx.applicationContext, FIRST_PENDING_DEADLINE_MS)
        } else {
            RecoveryWorker.enqueueDelayed(ctx.applicationContext, FIRST_PENDING_DEADLINE_MS)
        }
        RecoveryTrace.log(
            "rn_deadline_armed", "ms" to FIRST_PENDING_DEADLINE_MS,
            "policy" to (if (afterCurrent) "append" else "keep"),
            "scheduled" to armed.toString())
    }

    private const val MAX_RETRIES = 4

    /**
 * Enqueues accepted before a drain is requested.
 *
 * A bound, not a cadence — the semantic triggers (app backgrounding) do the
 * ordinary work, and this exists so a session that never backgrounds still
 * cannot grow without limit. Low enough to bound the loss window of an
 * un-uploaded queue; high enough that a chatty screen does not schedule on
 * every record (WorkManager coalesces, but a hint per record would still be
 * noise in the trace nobody could read).
 */
    // The thresholds and the deadline live in `FlushSchedule`, with the
    // decision that reads them. A second copy here is a second policy that can
    // drift from the one the tests pin.

    /**
 * The first empty→non-empty transition schedules ONE drain, at most this
 * far out. Subsequent records do NOT reset or postpone it: a deadline that
 * slides is a deadline a busy session never reaches, which is exactly the
 * unbounded accumulation the cap exists to prevent.
 */
    private val FIRST_PENDING_DEADLINE_MS = FlushSchedule.FIRST_PENDING_DEADLINE_MS

    /** Test-only reset. Never called from production code. */
    @JvmStatic
    fun resetForTest() {
        started.set(false); store = null; bracket = null; appContext = null
        lastState = State.NOT_CONFIGURED; quarantined.set(0)
        LossAuthority.closeAll()
        pendingSinceDrain.set(0); pendingBytes.set(0); deadlineArmed.set(false); drainInFlight.set(false)
        drainTailAt.set(FlushSchedule.NO_DRAIN_TAIL)
        // THE F7 POINTER IS PROCESS STATE TOO, and leaving it behind makes a
        // later test's result depend on test ORDER: a test that called
        // `endSessionAuthoritatively` leaves `finalizedSession` set, and the
        // NEXT test's empty `drainOnce` then takes the finalizing branch and
        // builds a pipeline where it should have returned `rn_queue_empty`.
        // Every other piece of drain-relevant state is reset here; this one was
        // added without it (CodeRabbit an earlier change finding [4]).
        finalizedSession = null
        // AND SO IS THE LAST DISPOSITION, which the row written for the line
        // above FOUND: `onDrainSettled` reads it to decide the next schedule,
        // so a previous test's `EMPTY` or `PARKED` steers the next test's
        // scheduling. Same class, same cause — a `@Volatile` field added to the
        // drain and not to the reset. The row asserts the SET, not the name, so
        // the next one cannot be forgotten quietly either.
        lastPipelineDisposition = DurableUploadPipeline.Disposition.DELIVERABLE_REMAINING
        RecoveryWorker.drainHook = null
    }
}

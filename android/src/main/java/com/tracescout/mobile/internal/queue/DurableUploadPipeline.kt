package com.tracescout.mobile.internal.queue

import android.content.Context
import androidx.annotation.RestrictTo
import com.tracescout.mobile.internal.loss.F6CheckpointEmitter
import com.tracescout.mobile.internal.loss.LossAuthority
import com.tracescout.mobile.queue.EncryptedEventStore
import com.tracescout.mobile.queue.F4Constants
import com.tracescout.mobile.queue.QueueHome
import com.tracescout.mobile.queue.QueueRefusal
import com.tracescout.mobile.queue.QueueRegion
import com.tracescout.mobile.queue.RecoveryTrace
import com.tracescout.mobile.queue.RecoveryWorker
import com.tracescout.mobile.transport.ChunkBounds
import com.tracescout.mobile.transport.ResponseAction
import com.tracescout.mobile.transport.TerminalDisposition
import com.tracescout.mobile.transport.Uploader
import com.tracescout.mobile.transport.terminalDisposition

/**
 * The ONE durable upload state machine: contiguous session-owned slicing,
 * deterministic batch identity, `mobile-ack-1` identity matching, prefix
 * re-check, and `removeFirst(N)` — nothing else.
 *
 * **LIBRARY-INTERNAL INFRASTRUCTURE, NOT A CUSTOMER SDK SURFACE.** It is
 * `public` for exactly one reason: a sibling producer module (React Native)
 * must reuse this implementation rather than copy it, and Kotlin `internal` is
 * compilation-module scoped so it cannot cross that boundary. It is not exposed
 * through the Android public API and must never be exposed through the RN
 * public JS API.
 *
 * **Producer-agnostic by construction.** It accepts immutable, already
 * serialised record bytes and knows nothing about who produced them: there is
 * no `sdkFamily`, `sdkVersion`, `platform` or `hostPlatform` here, and no hook
 * through which a caller could alter batch identity or acknowledgement
 * semantics. Producer identity stays compile-time-owned by each emitter (the
 * Android `Envelope` and the RN emitter, separately) — which is what stops a
 * producer misreporting which capture code produced its bytes.
 *
 * This behaviour was extracted verbatim from `TraceScoutMobile.uploadRaw`.
 * Every comment below records a defect that was paid for on a real device; the
 * extraction deliberately changed none of them.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class DurableUploadPipeline(
    /**
 * F4-P0 Slice B — THE QUEUE HOME, NEVER A BARE `RecordStore`. An interface has no lock on it, so a pipeline that owned only
 * `RecordStore` could not open an ordered region around any of its five
 * check-then-mutate sequences. The home owns the canonical store, both
 * lock layers and the durable protection state; every read that decides
 * a mutation here runs inside `home.withQueue(region)` with the mutation
 * it decides, so the premise and the mutation see ONE image.
 */
    private val home: QueueHome,
    /**
 * The F2 mutation gate, PER SLICE (liveness B2): asked about the exact
 * records a removal would take, answers the fenced sequences that forbid
 * it. Defaults to "nothing fenced"; both producers wire the authority's
 * per-slice fence. It is applied by the home's F2-gated adapter INSIDE
 * the region that performs the removal — one acquisition, not three.
 */
    private val mutationGate: (List<ByteArray>) -> List<Long> = { emptyList() },
    /**
 * F4-P1 (F4-15, ruling R4): the eviction attribution seam the quarantine
 * bracket settles through. Defaults to NOT WIRED — a quarantine then
 * refuses `eviction_refused_attribution_unwired` and removes NOTHING
 * (NO ATTRIBUTION ⇒ NO DESTRUCTION); both producers wire
 * `EvictionAttribution.forFilesDir`.
 */
    private val evictionSeam: EvictionAttribution = EvictionAttribution.none(),
    private val poster: ChunkPoster,
    private val projectId: String,
    private val maxRetries: Int,
    private val appContext: Context?,
    private val backoffMillis: (Int) -> Long,
    private val results: ResultSink,
    private val quarantine: QuarantineSink,
    /** Injectable only so tests need not sleep in real time. */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    /**
 * The third durable fate. Defaults to the fail-closed no-op store, which
 * REFUSES to park — a caller that has not wired one keeps the previous
 * retain-in-place behaviour rather than silently losing the slice.
 */
    private val park: ParkStore = ParkStore.none(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /**
 * The unresolved-batch descriptor. See [InFlightStore].
 *
 * REQUIRED — NO DEFAULT, AND NO `memory` DOWNGRADE (Slice B, ruling
 * B6). This used to default to process-lifetime memory "so an unwired
 * caller is never worse off"; a production caller that forgot to wire the
 * file store got a descriptor that could not survive the crash it exists
 * to survive, silently. A caller now has to say which store it means.
 */
    private val inflight: InFlightStore,
    /**
 * The bound the protection admission is checked against. Production
 * passes the derived ceiling; a test injects a small one to prove the
 * refusal. See [F4Constants.protectionAdmissionCeiling].
 */
    private val protectionCapacity: Long = F4Constants.protectionAdmissionCeiling(),
    /**
 * F2 — the attribution authority's narrow seam. Told WHEN a removal
 * committed (delivered / parked / quarantined) and which sequences it
 * covered; it decides what that means. Defaults to the visible UNWIRED
 * sink, never to a silent no-op.
 */
    private val attribution: AttributionSink = AttributionSink.none(),
    /**
 * FAULT SEAM — the crash windows the R1 write-ahead ordering exists to
 * close, made addressable.
 *
 * NOT A TEST HOOK THAT CHANGES BEHAVIOUR. The default does nothing at
 * every point, so production runs the identical path; a test supplies a
 * function that throws [SimulatedProcessDeath] at ONE named point and then
 * constructs a NEW pipeline over the SAME durable stores, which is what a
 * relaunch actually is. Asserting an ordering by reading source text cannot
 * show that the state left on disk is recoverable, and that is the whole
 * property under test.
 */
    private val faults: (String) -> Unit = { },
    /**
 * F4-P2: the queue reconciler, run from the drain when the live read is
 * LOSSY (F4-16: "when a drain meets a lossy image"). Nested inside the
 * SUBMIT region (one acquisition). Null = a caller that does not reconcile
 * from the drain; the open-time run still exists in both producers.
 */
    private val reconciler: QueueReconciler? = null,
) {

    /**
 * Thrown by an injected fault seam to model process death.
 *
 * AN `Error`, AND EVERY `catch (t: Throwable)` IN THIS FILE RETHROWS IT.
 * The pipeline deliberately catches broadly so a storage fault leaves the
 * queue intact — which would also swallow an injected crash and make the
 * crash tests pass by never crashing. `CrashSeamPinsTest` asserts the
 * rethrow at every catch site, so a future catch cannot silently disarm the
 * fault injection.
 */
    class SimulatedProcessDeath(point: String) : Error("simulated_process_death:$point")

    /**
 * Read-modify-write access to the caller's `lastResult`.
 *
 * A sink rather than a return value because the original assigns
 * `lastResult` at several points *during* the drain, including
 * `lastResult = lastResult?.copy(code = …)`, and an observer that reads it
 * mid-drain must see exactly what it saw before.
 */
    interface ResultSink {
        var last: Uploader.Result?
    }

    /**
 * The one transport call the pipeline makes.
 *
 * A narrow seam because `Uploader` is `final`: without it the ack matrix
 * could only be asserted by reading source text, and a source-grep cannot
 * show that `removeFirst` was called with the right COUNT — which is the
 * thing that deletes a customer's replay data when it is wrong.
 */
    fun interface ChunkPoster {
        fun post(projectId: String, body: ByteArray): Uploader.Result
    }

    /** Records dropped by a permanent rejection — never counted as delivered. */
    interface QuarantineSink {
        fun onQuarantined(count: Int, code: String?)
    }

    /**
 * Upload one immutable PREFIX of the durable queue.
 *
 * WHAT THIS REPLACED, AND WHY IT MATTERED. The previous version called
 * `store.clear` on `SUCCESS` — and on `TERMINAL` too. That is wrong three
 * times over: an HTTP 2xx bound to nothing was treated as an
 * acknowledgement; `clear` wipes the WHOLE store rather than the records
 * that were sent, so anything captured while the request was in flight was
 * deleted without ever being uploaded; and one permanent rejection
 * discarded every unrelated record in the queue. A successful HTTP status
 * is not permission to delete a customer's un-uploaded replay data.
 *
 * The rule now: records leave the queue ONLY after an acknowledgement whose
 * identity matches the batch that was sent, and only that prefix.
 */
    /**
 * WHY THE DRAIN REPORTS A DISPOSITION AND NOT ONLY A COUNT.
 *
 * The scheduler that decides whether to arm the 2 s first-pending deadline
 * was given `remainingRecords` alone, and a count cannot tell "retryable
 * bytes remain" from "permanently undeliverable bytes remain" — so a
 * permanently refused head re-armed forever, one real HTTP POST per cycle.
 *
 * A SEPARATE ENUM FROM `FlushSchedule.DrainDisposition` ON PURPOSE. That
 * one lives in `rn-core`, on the far side of the binary seam (`:sdk` has no
 * rn-core dependency and a composite build is impossible here). The
 * transport, which sees both, maps between them; making `:sdk` depend on
 * rn-core to share an enum would couple two modules that cross as published
 * artifacts.
 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    enum class Disposition {
        EMPTY,
        DELIVERABLE_REMAINING,
        RETRYABLE_REMAINING,
        TERMINAL_MOVE_PENDING,

        /**
 * The local archival cannot succeed without an external change —
 * preservation capacity exhausted, or no park store wired.
 *
 * DISTINCT FROM `TERMINAL_MOVE_PENDING` ON PURPOSE. Both retain the
 * bytes and arm no network deadline, but only PENDING is owed a retry.
 * Retrying a BLOCKED move on a bounded backoff replaces an infinite
 * 2 s HTTP loop with an infinite exponential LOCAL loop — better load
 * profile, identical wrong contract, since `ParkStore` has no delete,
 * clear or reactivate path and its capacity never shrinks.
 */
        TERMINAL_MOVE_BLOCKED,

        TERMINAL_MOVED_AND_MORE_REMAIN,

        /**
 * A lock layer refused a region the drain needed — another PROCESS
 * holds the install, or an invariant refusal. Zero mutation, and
 * either zero requests (pre-request) or a committed server answer
 * whose local cleanup is deferred to the next drain. Clears on its
 * own; a plain retry is owed.
 */
        QUEUE_UNAVAILABLE,

        /**
 * The durable protection state could not be read or could not be
 * updated durably, or admitting the batch would exceed the protection
 * ceiling. NO request was made for that batch (ruling B6: never
 * a silent fallback to memory-only protection). Owed no automatic
 * network retry; a later ordinary launch may try once.
 */
        PROTECTION_UNAVAILABLE,

        /**
 * E-5: the previous [F4Constants.MAX_NO_PROGRESS_DRAINS] attempts
 * against this exact queue image POSTED and removed nothing, so this
 * attempt refused BEFORE any request. Only a real local state change
 * (a committed removal, an append that changes the image, a later
 * slice's compaction, a blocker transition) rearms delivery.
 */
        NO_PROGRESS_BOUND,

        /**
 * F4-P0 Slice C (RF4-11): an eviction bracket on this queue is
 * UNRESOLVED — its marker is on disk, or cannot be read — so a drain
 * that removed records would change the image Slice D's recovery must
 * decide on. Refused BEFORE any request, zero mutation. Owed no
 * automatic network retry; Slice D's recovery clears it.
 */
        BRACKET_UNRESOLVED,

        /**
 * F4-P0 Slice D (U-2): an ERASURE of this install is requested and not
 * yet completed, so no drain may start — the system quiesces so the
 * teardown's fence can be taken. Refused BEFORE any request, zero
 * mutation. Owed no automatic retry; the erasure clears it.
 */
        ERASURE_REQUESTED,

        /**
 * F4-P2 C2: a durable reconciliation fact still
 * BINDS this queue's image after the reconciler had its chance —
 * PREPARED / SETTLED, or an unresolved CONSUMED (an unproven consume,
 * retained lossy evidence, a pending owner-bound clear). Refused
 * BEFORE any request: zero POST, zero dequeue, zero PARK, zero
 * quarantine, zero ordinary queue mutation, and the no-progress
 * counter is NOT burned (nothing was posted). Owed no automatic
 * network retry; only the governed reconciliation releases it.
 */
        RECONCILIATION_BLOCKED,
    }

    /** Why the last drain ended. Read by the transport to schedule correctly. */
    @Volatile
    var disposition: Disposition = Disposition.EMPTY
        private set

    /**
 * The last park attempt's typed outcome, used ONLY to choose between
 * `TERMINAL_MOVE_PENDING` and `TERMINAL_MOVE_BLOCKED`.
 *
 * Written immediately before the disposition that reads it, on the same
 * single-threaded drain path, so it cannot describe a different attempt.
 */
    @Volatile
    private var lastParkOutcome: ParkOutcome = ParkOutcome.PARKED

    /**
 * A pending local move is owed a retry ONLY when it can succeed.
 *
 * `BLOCKED` retains the bytes and the terminal descriptor identically, but
 * is owed NO automatic retry: no deadline, no backoff, no polling. A later
 * ordinary launch may try once; a permanently scheduled retry is what this
 * exists to prevent.
 */
    private fun terminalPendingOrBlocked(): Disposition =
        if (lastParkOutcome == ParkOutcome.BLOCKED) Disposition.TERMINAL_MOVE_BLOCKED
        else Disposition.TERMINAL_MOVE_PENDING

    /**
 * The bounds the NEXT batch is built with. They start at the shared
 * defaults and are only ever narrowed, by a 413.
 *
 * PROCESS-LOCAL, AND RESET BY ANY SUCCESSFUL ACKNOWLEDGEMENT. A 413 can
 * come from a proxy with a smaller limit than the API's, or from a
 * misconfiguration that is corrected an hour later; persisting a shrink
 * would let one such refusal permanently halve a healthy client's batching
 * with nothing able to restore it. A successful ack is proof the current
 * bounds work, so it is the honest place to forget the shrink.
 */
    @Volatile
    private var resliceBudgetBytes: Long = ChunkBounds.DEFAULT_BODY_BUDGET_BYTES

    @Volatile
    private var resliceMaxRecords: Int = ChunkBounds.MAX_RECORDS_PER_CHUNK

    /** Never wider than the shared defaults — a shrink only ever narrows. */
    private fun effectiveBudgetBytes(): Long =
        minOf(resliceBudgetBytes, ChunkBounds.DEFAULT_BODY_BUDGET_BYTES)

    private fun effectiveMaxRecords(): Int =
        minOf(resliceMaxRecords, ChunkBounds.MAX_RECORDS_PER_CHUNK)

    // ── Slice B: the home's seams ─────────────────────────────────────────────

    /**
 * The F2-gated adapter over the home's canonical store. Its read →
 * gate → remove is now nested INSIDE the region that decides the removal,
 * so the three acquisitions the adapter used to make are one.
 */
    private val store: RecordStore = home.records(mutationGate)

    /**
 * F4-P1: the quarantine's destructive home. The SAME `EvictionBracket`
 * class that evicts bytes at capacity; its second governed entry
 * `quarantineHead` runs PLAN → MARKER → PREPARE → COMMIT → SETTLE on the
 * server-indicted prefix. Built lazily so a producer that never sees a
 * terminal refusal never instantiates it.
 */
    private val quarantineBracket: EvictionBracket by lazy { EvictionBracket(home, evictionSeam, faults = faults) }

    /** The home's ONE durable protection state (B1) and no-progress counter (B5). */
    private val protection: DeliveryProtection = home.protection()

    /** Requests made by the current drain. Reset per `upload`. */
    private var drainPosted: Int = 0

    /** Whether the current drain committed ANY removal — real local progress. */
    private var drainProgressed: Boolean = false

    /** The logical fingerprint of the image this drain posted against. */
    private var drainEpoch: String = ""

    /** A typed local refusal, expressed in the transport's own result type: no server was asked. */
    private fun localRefusal(code: String): Uploader.Result =
        Uploader.Result(action = ResponseAction.RETRY, status = 0, code = code, ack = null)

    /**
 * Run [block] inside [region] on the home. A layer's typed refusal is
 * traced and answered with [refused] — there is no proceed-unlocked path.
 * A [SimulatedProcessDeath] propagates through the region (its `finally`
 * releases both layers), exactly as a real death would leave them.
 */
    private fun <T> region(region: QueueRegion, refused: (QueueRefusal) -> T, block: () -> T): T =
        when (val o = home.tryWithQueue(region) { block() }) {
            is QueueHome.Outcome.Ran -> o.value
            is QueueHome.Outcome.Refused -> {
                val why = o.refusal.wire
                val waitedMs = o.refusal.waitedMs
                RecoveryTrace.log("pipeline_region_refused", "region" to region.name,
                    "why" to why, "waitedMs" to waitedMs)
                refused(o.refusal)
            }
        }

    /** The refused-layer answer for a drain that has not asked the server anything yet. */
    private fun refuseQueue(r: QueueRefusal) {
        results.last = localRefusal("queue_" + r.wire)
        disposition = Disposition.QUEUE_UNAVAILABLE
    }

    /** A committed removal of records with these [digests]: protection leaves, the no-progress counter resets. */
    private fun committedRemoval(digests: List<String>) {
        drainProgressed = true
        val w = protection.removed(digests)
        if (w !is DeliveryProtection.Write.Committed) {
            // The queue mutation committed; the protection entries over
            // records that no longer exist are orphans, which OVER-protect
            // until the next trustworthy read reconciles them away (W-37).
            // Safe direction; stated, not hidden.
            RecoveryTrace.log("protection_release_deferred", "count" to digests.size)
        }
    }

    /** The pre-request phase's answer. */
    private sealed class Pre {
        data class Ok(val rest: List<ByteArray>, val noProgress: DeliveryProtection.NoProgress) : Pre()
        data class QueueRefused(val refusal: QueueRefusal) : Pre()
        data class NotPrefix(val have: Int, val live: Int) : Pre()
        data class Unreadable(val code: String) : Pre()
        object ProtectionUnreadable : Pre()
        /** Slice C: an unresolved (or unreadable) eviction-bracket marker. */
        data class BracketUnresolved(val code: String) : Pre()
        /** Slice D (U-2): an outstanding (or unreadable) erasure request. */
        object ErasureRequested : Pre()
        /** F4-P2 C2: the durable reconciliation fact still blocks ordinary mutation after the reconciler ran. */
        data class ReconciliationBlocked(val why: String) : Pre()
    }

    /** What a regioned step decided about the drain's control flow. */
    private enum class Step { CONTINUE, STOP }

    /** The freeze-and-admit region's answer for one slice. */
    private sealed class Frozen {
        object Ok : Frozen()
        object DescriptorRefused : Frozen()
        data class ProtectionRefused(val code: String) : Frozen()
        data class QueueRefused(val refusal: QueueRefusal) : Frozen()
        /** Slice D (D7): the slice is no longer the LIVE head — another region moved it since the image was read. */
        object ImageMoved : Frozen()
    }

    /**
 * B2 / NC-F6 — the caller's [records] MUST be a byte-prefix of the LIVE
 * queue. `TraceScoutMobile.flush` used to pass the in-memory `pending`
 * list, a batch sliced from a non-queue list failed the dequeue prefix
 * re-check, nothing was removed, and the next drain rebuilt it: a
 * duplicate re-upload loop invisible in the result and visible only in
 * the traffic. A list that is not the queue's head is refused here,
 * typed, with ZERO requests.
 */
    private fun isBytePrefix(image: List<ByteArray>, live: List<ByteArray>): Boolean {
        if (image.size > live.size) return false
        for (i in image.indices) if (!image[i].contentEquals(live[i])) return false
        return true
    }

    /**
 * Everything that must be decided on ONE queue image before any request:
 * the live read, the image precondition, the protection reconcile, the two
 * reconciles, the stale-descriptor discard. Runs inside the `SUBMIT` region.
 */
    private fun prepare(records: List<ByteArray>): Pre {
        // F4-P0 Slice D (U-2), BEFORE EVEN THE MARKER: no drain while an
        // erasure of this install is requested. The teardown needs the fence
        // this drain would otherwise hold, and a drain that removed records
        // during an erasure would be destroying evidence the governed path has
        // not yet decided about. An unreadable request is PRESENT.
        if (home.erasure().presentOrUnreadable()) {
            RecoveryTrace.log("drain_refused_erasure")
            return Pre.ErasureRequested
        }
        // F4-P0 Slice C, FIRST (RF4-11): no drain while a bracket is
        // unresolved. A removal here would move the fingerprint Slice D's
        // verdict table compares against, turning a decidable bracket into a
        // permanently UNKNOWABLE one. An unreadable marker is PRESENT.
        when (val m = home.bracketMarker().read()) {
            is BracketMarker.Read.Unreadable -> {
                RecoveryTrace.log("drain_refused_bracket", "why" to "marker_unreadable")
                return Pre.BracketUnresolved("bracket_marker_unreadable")
            }
            is BracketMarker.Read.Ok -> if (m.marker != null) {
                RecoveryTrace.log("drain_refused_bracket", "why" to "unresolved", "bracket" to m.marker.bracketId.take(12))
                return Pre.BracketUnresolved("bracket_unresolved")
            }
        }
        val live = try {
            store.readRecords()
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            return Pre.Unreadable(t.javaClass.simpleName)
        }
        if (!isBytePrefix(records, live)) {
            RecoveryTrace.log("queue_image_not_prefix", "have" to records.size, "live" to live.size)
            return Pre.NotPrefix(records.size, live.size)
        }
        var trustworthy = try { store.integrityClass() == RecordStore.INTEGRITY_OK } catch (_: Throwable) { false }
        var live2 = live
        // F4-P2: a drain that meets a LOSSY image hands it to the reconciler FIRST.
        // A Resolved-and-consumed outcome changes the image, so it is re-read and
        // re-checked against the caller's list; Blocked leaves everything as it
        // was and the drain continues exactly as before (the bracket and the
        // quarantine still refuse a lossy image on their own).
        // F4-P2 child: a queue whose DURABLE fact still blocks ordinary mutation (a CONSUMED
        // fact with its clear history unfinished, or a pending fact a crash left behind over a
        // trustworthy image) is handed to the reconciler too — otherwise the block would stand
        // until the next process open while every drain walked past it.
        val factBlocks = QueueReconciler.mutationBlockReason(home) != null
        if ((!trustworthy || factBlocks) && reconciler != null) {
            val o = reconciler.reconcile()
            val resolved = o as? QueueReconciler.Outcome.Resolved
            RecoveryTrace.log("drain_reconcile", "outcome" to o.javaClass.simpleName, "consumed" to (resolved?.consumed == true))
            if (resolved?.consumed == true) {
                live2 = try { store.readRecords() } catch (t: Throwable) {
                    if (t is SimulatedProcessDeath) throw t
                    return Pre.Unreadable(t.javaClass.simpleName)
                }
                if (!isBytePrefix(records, live2)) {
                    RecoveryTrace.log("queue_image_not_prefix", "have" to records.size, "live" to live2.size)
                    return Pre.NotPrefix(records.size, live2.size)
                }
                trustworthy = try { store.integrityClass() == RecordStore.INTEGRITY_OK } catch (_: Throwable) { false }
            }
        }
        // F4-P2 C2 — THE DRAIN'S POST-RECONCILE GATE. Whatever the
        // reconciler answered — Blocked, a partial Resolved, nothing at all (unwired) — the
        // SAME predicate is re-asked against the DURABLE fact, and a queue it still binds
        // is refused HERE, before the protection reconcile, the delivered/parked reconciles,
        // any request, any dequeue, any PARK and any quarantine. The refusal is typed with
        // the predicate's own reason; the no-progress counter is untouched (nothing posts);
        // the queue and the fact are byte-identical. The drain never continues merely
        // because `reconcile` returned.
        // `why` is passed BARE, and the slice it replaces was a privacy-pin failure of
        // mine: `why.take(48)` exceeded `RecoveryTracePrivacyTest`'s 40-character ceiling
        // for a bounded identifier slice. The value is not an identifier at all — it is a
        // TYPED CODE from the predicate's closed vocabulary (longest:
        // `reconciliation_unresolved:evidence_retained`, 43 chars), which that guard
        // sanctions as a bare name, and `RecoveryTrace.render` bounds every value to 64
        // characters regardless. A 40-char slice would have TRUNCATED the longest legal
        // reason — a shorter log line is not a safer one if it corrupts the code it carries.
        // All four gate sites now pass the reason in the same shape: one fact, one shape.
        QueueReconciler.mutationBlockReason(home)?.let { why ->
            RecoveryTrace.log("drain_refused_reconciliation", "why" to why)
            return Pre.ReconciliationBlocked(why)
        }
        val np = protection.noProgress() ?: run {
            RecoveryTrace.log("protection_unreadable")
            return Pre.ProtectionUnreadable
        }
        if (trustworthy) {
            // an open product question second clause / W-37: an entry whose record a TRUSTWORTHY read
            // proves absent leaves. A lossy read authorises nothing.
            val w = protection.reconcileAbsent(live2.map { sha256Hex(it) }.toSet())
            if (w !is DeliveryProtection.Write.Committed) RecoveryTrace.log("protection_reconcile_deferred")
        }
        // The working image is the LIVE queue — the durable truth — never the
        // caller's list. The two reconciles below open their own nested
        // regions, which re-enter this one: one acquisition throughout.
        var rest: List<ByteArray> = reconcileParked(reconcileDelivered(live2))
        // THE STALE-DESCRIPTOR DISCARD MUST RUN EVEN WHEN THE QUEUE IS EMPTY,
        // and it used to sit BELOW the early return. A crash between the R1
        // delivery write and the descriptor release leaves a descriptor naming a
        // batch the next drain then reconciles away — so the queue goes empty,
        // `upload` returned here, and the descriptor stayed on disk naming bytes
        // that no longer exist. My own crash test found it: the cleanup was
        // "unfinished" in exactly the sense the recovery clause covers.
        //
        // an open product question: a stale descriptor disposes of its batch REFERENCE only. The
        // records' protection entries stay until the mutation that removes them
        // commits, or a trustworthy read proves them absent (above).
        inflight.load()?.let { d ->
            if (!resumeStillMatches(d, rest)) {
                RecoveryTrace.log("inflight_stale_discarded",
                    "chunk" to d.chunkId.take(12), "want" to d.recordCount, "have" to rest.size)
                inflight.clear()
                protection.disposeRef(DeliveryProtection.refIdOf(d.chunkId, d.digests))
            }
        }
        return Pre.Ok(rest, np)
    }

    /**
 * E-5 settlement, once per drain, on every exit except a simulated death:
 * an attempt that POSTED and committed no removal is counted against the
 * image it posted; a drain that removed something was reset by the
 * removal itself. A server `duplicate` is an acknowledgement, not local
 * progress — only the removal resets (RF4-49; the NC-R4 control).
 */
    private fun settleNoProgress() {
        if (drainPosted == 0 || drainProgressed) return
        val r = results.last
        val why = (r?.code ?: r?.action?.name ?: "none").take(40)
        val w = protection.recordNoProgress(drainEpoch, why)
        RecoveryTrace.log("no_progress_recorded", "outcome" to w.javaClass.simpleName,
            "epoch" to drainEpoch.take(12), "why" to why)
    }

    fun upload(records: List<ByteArray>, fallbackSessionId: String) {
        drainPosted = 0; drainProgressed = false; drainEpoch = ""
        var died = false
        try {
            uploadInner(records, fallbackSessionId)
        } catch (t: SimulatedProcessDeath) {
            died = true; throw t
        } finally {
            if (!died) settleNoProgress()
        }
    }

    private fun uploadInner(records: List<ByteArray>, fallbackSessionId: String) {
        // An empty queue must not produce a request at all. A round trip that
        // could only ever be answered "nothing happened" is an invitation to
        // read a 2xx as progress.
        if (records.isEmpty()) {
            RecoveryTrace.log("upload_empty_noop"); results.last = null
            disposition = Disposition.EMPTY
            // F7 R-2 — THE ONE PRODUCTION TRIGGER, and it
            // is HERE because this is the one branch that has established, from
            // the drain's own image, that no chunk exists to carry a disclosure.
            // Below this line a chunk is going out and F6 rides it; above it,
            // there is nothing and a finalized session would otherwise never be
            // told. The gate inside decides WHETHER to speak.
            //
            // THE DRAIN'S OWN SESSION IS NOT THE ONLY CANDIDATE, AND AT
            // PROCESS START IT IS THE ONE THAT CANNOT ANSWER YES. `start`
            // mints the new identity BEFORE it calls `recoverInherited`, whose
            // empty-queue path drains with that identity as the fallback owner
            // so this gate used to be asked whether a session born a
            // millisecond ago had been declared over. Only the host's explicit
            // session-end seam writes that claim, so the answer was
            // structurally `false`, and the cross-launch case
            // `endSessionAuthoritatively`'s KDoc promises ("a host that
            // declares session end and is then killed emits the checkpoint on
            // the next launch") could never fire here. The PREVIOUS session is
            // named by the durable record itself, never by a pointer this
            // process happens to hold — a process that died holds nothing.
            for (s in chunklessFinalityCandidates(fallbackSessionId)) {
                finalizeChunklessSession(s)
            }
            return
        }

        // A batch is addressed by the session its RECORDS carry — never by
        // whichever session happens to be running. Recovery uploads a PREVIOUS
        // process's records, and the server refuses a chunk whose events
        // disagree with its address (`chunk_session_mismatch`, permanent), so
        // addressing inherited records with the new session id would refuse —
        // and then quarantine — exactly the records recovery exists to save.
        // A crash between a durable parked write and the active removal leaves
        // the SAME bytes in both stores. Settle that BEFORE any request, so a
        // parked slice is never re-submitted merely because a process died at
        // the wrong instant — "do not automatically retry a parked slice" has
        // to hold across process death, not only within one drain.
        // DELIVERED FIRST, THEN PARKED, AND THE ORDER IS LOAD-BEARING. Both
        // passes match a HEAD prefix, so a leading run of already-delivered
        // records that a crash left behind would hide the parked batch sitting
        // behind it from `reconcileParked` — which matches only the head — and
        // the parked duplicate would be re-submitted instead of reconciled.
        // ONE REGION FOR THE WHOLE PRE-REQUEST PHASE (Slice B, B4): the live
        // read, the image precondition, both reconciles and the stale
        // discard decide on ONE image, and nothing else can mutate the queue
        // between the premise and the mutation. The request itself is never
        // inside a region.
        val pre = region(QueueRegion.SUBMIT, refused = { r -> Pre.QueueRefused(r) }) { prepare(records) }
        var rest: List<ByteArray>
        val np: DeliveryProtection.NoProgress
        when (pre) {
            is Pre.Ok -> { rest = pre.rest; np = pre.noProgress }
            is Pre.QueueRefused -> { refuseQueue(pre.refusal); return }
            is Pre.NotPrefix -> {
                // The caller handed the pipeline something that is not the
                // queue's head. Zero requests; the next drain reads the queue.
                results.last = localRefusal("queue_image_not_prefix")
                disposition = Disposition.RETRYABLE_REMAINING; return
            }
            is Pre.Unreadable -> {
                results.last = localRefusal("queue_unreadable")
                disposition = Disposition.RETRYABLE_REMAINING; return
            }
            is Pre.ProtectionUnreadable -> {
                // B6: the protection state cannot be trusted or updated, so no
                // batch may be POSTed on it. Typed, zero requests, no downgrade.
                results.last = localRefusal("protection_unreadable")
                disposition = Disposition.PROTECTION_UNAVAILABLE; return
            }
            is Pre.BracketUnresolved -> {
                // Slice C: a bracket owns this queue until Slice D resolves it.
                results.last = localRefusal(pre.code)
                disposition = Disposition.BRACKET_UNRESOLVED; return
            }
            is Pre.ErasureRequested -> {
                // Slice D (U-2): the install is being erased; nothing drains.
                results.last = localRefusal("erasure_requested")
                disposition = Disposition.ERASURE_REQUESTED; return
            }
            is Pre.ReconciliationBlocked -> {
                // F4-P2 C2: the fact still binds the image; zero requests, zero mutation.
                results.last = localRefusal(pre.why)
                disposition = Disposition.RECONCILIATION_BLOCKED; return
            }
        }
        if (rest.isEmpty()) {
            RecoveryTrace.log("upload_empty_after_reconcile"); results.last = null
            disposition = Disposition.EMPTY; return
        }
        // E-5 — THE SIXTH SAME-IMAGE ATTEMPT REFUSES BEFORE ANY REQUEST IS
        // BUILT. The epoch is the logical fingerprint of the image this drain
        // would post; the counter is durable and was read inside the region.
        drainEpoch = EncryptedEventStore.logicalFingerprint(rest)
        if (protection.boundReached(np, drainEpoch)) {
            val count = np.count
            RecoveryTrace.log("no_progress_refused", "count" to count, "epoch" to drainEpoch.take(12))
            results.last = localRefusal("no_progress_bound")
            disposition = Disposition.NO_PROGRESS_BOUND; return
        }
        // An UNRESOLVED batch owns the head of the queue until it settles.
        // Re-forming the prefix from the live queue is exactly what produced
        // two committed chunks for sequences 19–26 on a real iPhone: the
        // submission had not settled, three records were appended, and the
        // next drain sent a LONGER batch with a different digest that the
        // server could not recognise as the same work. See [InFlightStore].
        // (A descriptor the prepared image no longer matches was discarded
        // inside the region above; what is loaded here is either absent or
        // still the exact head.)
        var resume: InFlightBatch? = inflight.load()
        if (resume != null && !resumeStillMatches(resume, rest)) {
            RecoveryTrace.log("inflight_stale_discarded",
                "chunk" to resume.chunkId.take(12), "want" to resume.recordCount,
                "have" to rest.size)
            inflight.clear()
            protection.disposeRef(DeliveryProtection.refIdOf(resume.chunkId, resume.digests))
            resume = null
        }

        // THE CROSS-PROCESS CLOSE. A descriptor carrying a terminal reason
        // was classified on an EARLIER pass — possibly in a process that died
        // before the park completed. Resume the local move; do NOT ask the
        // server again. Without this the loop survives restart, which is
        // exactly where it was surviving.
        resume?.let { r ->
            if (r.terminalReason == SessionOriginPolicy.SESSION_ORIGIN_UNRECOVERABLE) {
                val n0 = r.recordCount
                if (rest.size >= n0) {
                    val slice0 = rest.subList(0, n0)
                    val b0 = BatchIdentity.of(r.sessionId, slice0)
                    RecoveryTrace.log(
                        "terminal_resume_no_post",
                        "reason" to r.terminalReason, "chunk" to r.chunkId,
                        "recordCount" to n0)
                    if (parkPrefix(n0, b0, slice0, r.terminalReason)) {
                        inflight.clear()
                        rest = rest.subList(n0, rest.size)
                        results.last = null
                        disposition = if (rest.isEmpty()) Disposition.EMPTY
                                      else Disposition.TERMINAL_MOVED_AND_MORE_REMAIN
                        if (rest.isEmpty()) return
                    } else {
                        // Local archival did not complete. The bytes stay. The
                        // retry owed is the LOCAL move, never a POST.
                        RecoveryTrace.log("terminal_resume_park_failed", "chunk" to r.chunkId.take(12))
                        disposition = terminalPendingOrBlocked()
                        return
                    }
                    resume = null
                }
            }
        }

        // LABELLED. The terminal branches below sit inside the inner
        // RETRY loop, so a bare `continue` there advances the RETRY, not
        // the slice — which sends the SAME batch again and turns "exactly
        // one POST" into two. Measured: A2 saw 2 posts before this label.
        slices@ while (rest.isNotEmpty()) {
            val pending = resume
            val batchSession: String
            val slice: List<ByteArray>
            if (pending != null) {
                // Byte-identical retry: same session, same boundary, same
                // bytes. Newly appended records are NOT admitted.
                batchSession = pending.sessionId
                slice = rest.subList(0, pending.recordCount)
                RecoveryTrace.log("inflight_resumed",
                    "session" to pending.sessionId,
                    "chunk" to pending.chunkId,
                    "firstSequence" to pending.firstSequence,
                    "lastSequence" to pending.lastSequence,
                    "recordCount" to pending.recordCount,
                    "bodySha256" to pending.contentSha256,
                    "prefixFingerprint" to prefixFingerprint(pending.digests),
                    "attempts" to pending.attempts)
            } else {
                val owned = sessionOwnedPrefix(
                    rest, fallbackSessionId,
                    budgetBytes = effectiveBudgetBytes(),
                    maxRecords = effectiveMaxRecords(),
                    // F6 the specification — reserve the checkpoint's bytes for the session the
                    // prefix actually belongs to. Deterministic in (session,
                    // authority state): it renders the real counts against a
                    // worst-case 20-digit boundary, so recomputing it after the
                    // slice yields the same number.
                    reserveFor = { sid -> attribution.f6ReserveBytes(sid) },
                )
                batchSession = owned.first
                slice = owned.second
            }
            val n = slice.size
            val batch = BatchIdentity.of(batchSession, slice)

            // ZERO HTTP FOR A SESSION ALREADY PROVEN TERMINAL. The device
            // learned this fact once, durably; a later slice of the same dead
            // session must not re-ask a server that can only say no again. The
            // `firstSequence > 0` clause still applies inside the policy, so a
            // slice that carries the opener is never archived by this path.
            if (sessionAlreadyTerminal(batchSession)) {
                val v = SessionOriginPolicy.verdict(
                    SessionOriginPolicy.Evidence(
                        sessionPreconditionRefusal = false,
                        firstSequence = batch.firstSequence.toLongOrNull() ?: -1L,
                        openerPresentInActiveStore = false,
                        openerRecoverableElsewhere = false,
                        sessionAlreadyTerminal = true))
                if (v == SessionOriginPolicy.Verdict.TERMINAL_SESSION_ORIGIN) {
                    RecoveryTrace.log(
                        "terminal_known_no_post",
                        "session" to batchSession, "chunk" to batch.chunkId.take(12),
                        "recordCount" to n)
                    if (moveTerminal(n, batch, slice, pending)) {
                        inflight.clear()
                        rest = rest.subList(n, rest.size)
                        results.last = null
                        disposition = if (rest.isEmpty()) Disposition.EMPTY
                                      else Disposition.TERMINAL_MOVED_AND_MORE_REMAIN
                        resume = null
                        continue@slices
                    }
                    disposition = terminalPendingOrBlocked()
                    return
                }
            }

            // A RECORD THAT CANNOT FIT IN ANY LEGAL BODY IS PRESERVED, NEVER
            // DELETED — and it is decided BEFORE a request, because the answer
            // is arithmetic about the record and the server's known body limit,
            // not something a server needs to be asked.
            //
            // Re-slicing cannot help it: the batch is already one record, and
            // there is nothing left to split. Retaining it in place would starve
            // every record behind it for the life of the install — the same
            // strict-FIFO starvation `PARKED_PRODUCER` exists to end — so the
            // bytes MOVE to the parked store through the existing crash-safe
            // operation, with a typed reason, and the queue advances.
            //
            // IT SITS AFTER THE TWO TERMINAL-SESSION BRANCHES ON PURPOSE. A
            // descriptor already classified `session_origin_unrecoverable`, or
            // a session already durably known terminal, is archived under THAT
            // reason — the more specific and already-persisted one — rather
            // than being relabelled by a size check that happens to run first.
            if (n == 1 && !ChunkBounds.headDeliverable(
                    batchSession.toByteArray(Charsets.UTF_8).size, slice[0].size)
            ) {
                RecoveryTrace.log(
                    "record_body_too_large",
                    "session" to batchSession,
                    "chunk" to batch.chunkId.take(12),
                    "recordBytes" to slice[0].size)
                if (parkPrefix(n, batch, slice, ChunkBounds.RECORD_BODY_TOO_LARGE)) {
                    inflight.clear()
                    rest = rest.subList(n, rest.size)
                    results.last = null
                    disposition = if (rest.isEmpty()) Disposition.EMPTY
                                  else Disposition.TERMINAL_MOVED_AND_MORE_REMAIN
                    resume = null
                    continue@slices
                }
                // Local archival did not complete. The bytes stay exactly where
                // they are; the retry owed is the LOCAL move, never a POST of a
                // body we have already computed to be unacceptable.
                disposition = terminalPendingOrBlocked()
                return
            }

            RecoveryTrace.log("upload_raw_enter", "count" to n, "chunk" to batch.chunkId.take(12))
            // ── F6 — THE CHECKPOINT RIDES THIS CHUNK, OR NOTHING DOES ──────────
            //
            // The ONE orchestration point for both Android producers. the specification: EVERY
            // eligible delivery, loss is NOT a precondition — a contiguous
            // interval legitimately emits a complete checkpoint with zero delta,
            // and that is what advances the acknowledged authority.
            //
            // the specification: a refusal NEVER fails, drops or blocks the chunk. `null`
            // means attach nothing and proceed; a PARTIAL checkpoint is
            // forbidden. The reason is traced by the emitter.
            //
            // THE DOCUMENT IS DETERMINISTIC ACROSS RETRIES, which the
            // unresolved-batch invariant requires: a retry of an unsettled batch
            // must rebuild BYTE-IDENTICAL bytes or the pipeline stops. On the
            // first attempt the claim is persisted; a retry finds that durable
            // row and re-derives the SAME five-key document from it by value.
            val f6Checkpoint = attribution.f6CheckpointJson(
                batchSession,
                batch.firstSequence.toLongOrNull() ?: -1L,
                batch.lastSequence.toLongOrNull() ?: -1L,
                attribution.f6ReserveBytes(batchSession),
                resuming = pending != null,
            )
            val body = encodeBody(batchSession, batch.chunkId, slice, f6Checkpoint)

            if (pending != null && (batch.chunkId != pending.chunkId ||
                    sha256Hex(body) != pending.contentSha256)
            ) {
                // The descriptor and the rebuilt bytes disagree. That must be
                // impossible after the digest re-check above; if it ever
                // happens, sending would break the very invariant this exists
                // to hold, so stop instead.
                RecoveryTrace.log("inflight_rebuild_mismatch", "chunk" to pending.chunkId.take(12))
                results.last = results.last?.copy(code = "inflight_rebuild_mismatch")
                return
            }

            // Freeze the identity BEFORE the first network attempt. A
            // descriptor written after the request cannot survive a crash
            // during it — which is the window the defect lives in.
            //
            // AND ADMIT PROTECTION IN THE SAME REGION (Slice B, an open product question). The
            // batch's records become protected — one canonical entry per
            // digest, this batch a REF over them — before any byte goes, and
            // inside a region so a later slice's bracket cannot read the
            // protected set between the freeze and the admission. A reslice
            // adds a ref over digests that are already protected and consumes
            // no capacity; a resumed descriptor maps to the SAME ref.
            val digests = pending?.digests ?: slice.map { sha256Hex(it) }
            val frozen = region(QueueRegion.SUBMIT, refused = { r -> Frozen.QueueRefused(r) }) {
                // F4-P0 Slice D (D7) — THE SLICE MUST STILL BE THE LIVE HEAD,
                // RE-CHECKED INSIDE THIS REGION. `rest` is the image `prepare`
                // read in an EARLIER acquisition, and the slice loop walks it
                // across several slices; a bracket (another region on the same
                // domain) can run between `prepare` and this freeze, or between
                // two slices, and EVICT a record of this slice. Admitting
                // protection for a record that is no longer in the queue, then
                // posting it, then parking it on a terminal answer, produced ONE
                // record with TWO terminal dispositions (parked AND
                // BYTE_EVICTED) — measured by `EvictionRecoveryTest` D19 as a
                // race and D26 deterministically. The check is in the SAME
                // region as the admission, so nothing can move the head between
                // the two; a moved image is a typed refusal and the next drain
                // re-reads.
                if (!isBytePrefix(slice, store.readRecords())) {
                    RecoveryTrace.log("queue_image_moved", "chunk" to batch.chunkId.take(12), "want" to n)
                    return@region Frozen.ImageMoved
                }
                if (pending == null) {
                    val d = InFlightBatch(
                        sessionId = batchSession, chunkId = batch.chunkId,
                        firstSequence = batch.firstSequence, lastSequence = batch.lastSequence,
                        recordCount = n, contentSha256 = sha256Hex(body),
                        digests = digests,
                        firstAttemptAtMs = nowMillis(), attempts = 0,
                    )
                    if (!inflight.save(d)) {
                        // Fail CLOSED. Without a durable descriptor a crash during
                        // the request would let the next drain extend this batch —
                        // the exact defect. Not sending loses nothing: the records
                        // stay queued and the next drain tries again.
                        RecoveryTrace.log("inflight_save_refused", "chunk" to batch.chunkId.take(12))
                        return@region Frozen.DescriptorRefused
                    }
                    // The ONE authoritative record that a batch was frozen, and
                    // frozen BEFORE any network attempt (this line precedes the
                    // first poster.post call). Sanitized: identity, boundary and
                    // digests only — never a record body.
                    RecoveryTrace.log(
                        "inflight_frozen",
                        "session" to batchSession,
                        "chunk" to batch.chunkId,
                        "firstSequence" to batch.firstSequence,
                        "lastSequence" to batch.lastSequence,
                        "recordCount" to n,
                        "bodySha256" to d.contentSha256,
                        "prefixFingerprint" to prefixFingerprint(d.digests),
                    )
                }
                when (val a = protection.admit(batch.chunkId, batchSession, digests, nowMillis(), protectionCapacity)) {
                    is DeliveryProtection.Admit.Ok -> {
                        val fresh = a.newDigests
                        RecoveryTrace.log("protection_admitted", "chunk" to batch.chunkId.take(12),
                            "recordCount" to n, "fresh" to fresh)
                        Frozen.Ok
                    }
                    is DeliveryProtection.Admit.AtCapacity -> Frozen.ProtectionRefused("protection_capacity")
                    is DeliveryProtection.Admit.NotDurable -> Frozen.ProtectionRefused("protection_not_durable")
                    is DeliveryProtection.Admit.Unreadable -> Frozen.ProtectionRefused("protection_unreadable")
                }
            }
            when (frozen) {
                Frozen.Ok -> Unit
                Frozen.ImageMoved -> {
                    // Slice D (D7): zero requests; the next drain reads the live queue.
                    results.last = localRefusal("queue_image_moved")
                    disposition = Disposition.RETRYABLE_REMAINING
                    return
                }
                Frozen.DescriptorRefused -> {
                    results.last = localRefusal("inflight_unavailable")
                    return
                }
                is Frozen.ProtectionRefused -> {
                    // B6: no request for a batch whose protection was not
                    // committed. The descriptor (if written) stays and the
                    // next drain resumes it; nothing is downgraded to memory.
                    val why = frozen.code
                    RecoveryTrace.log("protection_admission_refused", "chunk" to batch.chunkId.take(12),
                        "why" to why)
                    results.last = localRefusal(frozen.code)
                    disposition = Disposition.PROTECTION_UNAVAILABLE
                    return
                }
                is Frozen.QueueRefused -> { refuseQueue(frozen.refusal); return }
            }
            val refId = DeliveryProtection.refIdOf(batch.chunkId, digests)
            resume = null

            var attempt = 1
            while (attempt <= maxRetries) {
                RecoveryTrace.log("transport_enter", "attempt" to attempt)
                RecoveryTrace.log("http_request_started", "attempt" to attempt)
                drainPosted++
                val r = poster.post(projectId, body)
                results.last = r
                RecoveryTrace.log("http_response_received", "action" to r.action.name,
                    "status" to r.status, "code" to (r.code ?: ""),
                    "ackPresent" to (r.ack != null))
                when (r.action) {
                    ResponseAction.SUCCESS -> {
                        val ack = r.ack
                        // A 2xx WITHOUT a matching acknowledgement is not
                        // progress. The queue stays intact, nothing is marked
                        // delivered, and the caller retries on the ordinary
                        // schedule.
                        if (ack == null || !ack.committed || !batch.matches(ack)) {
                            RecoveryTrace.log("ack_invalid",
                                "ackPresent" to (ack != null),
                                "committed" to (ack?.committed == true))
                            results.last = r.copy(code = "response_contract_invalid")
                            return
                        }
                        RecoveryTrace.log("ack_valid", "state" to ack.state)
                        // ONE `DEQUEUE` REGION FROM THE ADOPTION TO THE REMOVAL
                        // (Slice B, B4): the adoption's queue view, the delivery
                        // write, the descriptor release and the prefix re-check +
                        // removal all see ONE image. The authority writes inside
                        // it follow the ruled order (region -> fence -> store
                        // monitor -> authority). The request is already over.
                        val step = region(QueueRegion.DEQUEUE, refused = { q ->
                            // The server committed; only the LOCAL cleanup is
                            // deferred. The next drain reconciles it with no
                            // request, or earns a `duplicate` ack.
                            results.last = r.copy(code = "queue_" + q.wire)
                            disposition = Disposition.QUEUE_UNAVAILABLE
                            Step.STOP
                        }) {
                        // ADOPT BEFORE THE DELIVERY AUTHORITY IS WRITTEN AND
                        // BEFORE ANY BYTE GOES (store review R3). The server's
                        // commit stands either way; what must not happen is the
                        // removal of a pre-F2 session's last records with no
                        // authority left to say they existed.
                        if (!adoptedForRemoval(batchSession, batch.chunkId)) {
                            results.last = r.copy(code = "removal_adoption_not_ready")
                            disposition = Disposition.RETRYABLE_REMAINING
                            return@region Step.STOP
                        }
                        // R1 WRITE-AHEAD — THE DELIVERY AUTHORITY IS WRITTEN
                        // BEFORE ANY LOCAL CLEANUP, AND THE ORDER IS THE WHOLE
                        // POINT.
                        //
                        // This used to be the LAST step: `inflight.clear` ->
                        // `removeFirst(n)` -> `recordDelivered(.)`. That left a
                        // window in which the queue prefix and the in-flight
                        // descriptor were both gone while the durable authority
                        // still said only ADMITTED — so a process dying there
                        // came back with no record of the delivery, no bytes to
                        // re-derive it from, and sequences it could later settle
                        // as LOST for records the server had committed.
                        //
                        // The server's commit is already true when the ack
                        // validates, so writing it first states nothing that is
                        // not yet a fact. Cleanup is then idempotent and
                        // resumable: `reconcileDelivered` completes it on any
                        // later drain, with no request.
                        // THE TRUE COUNT `n` TRAVELS SEPARATELY FROM THE
                        // SEQUENCES (SF-3 / SF-8). `recordSequencesOf` yields only
                        // records that PARSE and belong to THIS batch's session —
                        // an unreadable record, or one whose owner the fallback
                        // merely stood in for, contributes nothing, so the
                        // authority's count guard REFUSES the batch instead of
                        // marking a set F2 could not read as delivered. The
                        // server's commit still stands and the cleanup proceeds.
                        val att = attribution.recordDelivered(
                            batchSession, batch.chunkId, n, sha256Hex(body), recordSequencesOf(batchSession, slice))
                        if (att is AttributionSink.Outcome.NotDurable) {
                            // The authority is FENCED — the DeliveredBatch may or
                            // may not be on disk. Removing the prefix now would
                            // re-open exactly the window this ordering closes, so
                            // nothing is removed and nothing is released: the
                            // retry earns a `duplicate` ack and the reopened
                            // authority reads whatever is genuinely durable.
                            RecoveryTrace.log("delivery_authority_not_durable",
                                "why" to att.reason, "chunk" to batch.chunkId.take(12))
                            results.last = r.copy(code = "delivery_authority_not_durable")
                            disposition = Disposition.RETRYABLE_REMAINING
                            return@region Step.STOP
                        }
                        RecoveryTrace.log("delivery_authority_written",
                            "outcome" to att.javaClass.simpleName, "chunk" to batch.chunkId.take(12),
                            "count" to n)
                        faults("after_delivered_durable")
                        // The records are now COMMITTED_UNDEQUEUED (W-16): the
                        // server holds them, the queue still does. Best-effort
                        // strengthening of a state that already protects — a
                        // failed write here leaves SUBMITTED, which protects too.
                        if (protection.markState(refId, DeliveryProtection.Strength.COMMITTED_UNDEQUEUED)
                            !is DeliveryProtection.Write.Committed
                        ) RecoveryTrace.log("protection_mark_deferred", "chunk" to batch.chunkId.take(12))
                        // F3-P1b — THE LOSS-CHECKPOINT SETTLEMENT CALL SITE, AND
                        // `mobile-ack-1` IS THE GATE, NOT THE AUTHORITY (F3-P1b the specification).
                        // Reached BECAUSE the mobile ack committed and the delivery
                        // authority is durable; it settles ONLY on the strength of a
                        // PARSED `loss-checkpoint-ack-1` block. No block => nothing:
                        // no handle opened, no store read, no trace claiming a
                        // settlement was considered. the capture specification: "mobile-ack-1 alone
                        // must NEVER clear pending loss." Reusing the chunk-ack
                        // handler for the loss path is the conformance tests Part 4d's named
                        // defect, and the block being the ONLY trigger is what keeps
                        // it out. The outcome NEVER changes the chunk disposition,
                        // the reslice budgets, prefix removal or the descriptor
                        // lifecycle — loss settlement depends on the chunk result,
                        // the chunk result never depends on loss settlement.
                        val loss = r.lossAck
                        if (loss != null) settleLossCheckpoint(batchSession, loss)
                        // A successful acknowledgement is proof the CURRENT
                        // bounds work, so any shrink a 413 imposed is forgotten
                        // here. Without this a single refusal — from a proxy, or
                        // from a misconfiguration corrected an hour later —
                        // would permanently halve a healthy client's batching
                        // with nothing able to restore it.
                        resliceBudgetBytes = ChunkBounds.DEFAULT_BODY_BUDGET_BYTES
                        resliceMaxRecords = ChunkBounds.MAX_RECORDS_PER_CHUNK
                        // SETTLED. The descriptor is released here and only
                        // here on the success path, so records appended while
                        // this batch was unresolved form the NEXT batch.
                        RecoveryTrace.log("inflight_settled",
                            "by" to ack.state, "session" to batchSession,
                            "chunk" to batch.chunkId,
                            "firstSequence" to batch.firstSequence,
                            "lastSequence" to batch.lastSequence,
                            "recordCount" to n)
                        inflight.clear()
                        faults("after_inflight_clear")
                        // `duplicate` is not a lesser success: it is the ONLY
                        // thing that lets a client which died between the
                        // server's commit and its own dequeue remove those
                        // records exactly once.
                        dequeueExactly(n, batch)
                        Step.CONTINUE
                        }
                        if (step == Step.STOP) return
                        rest = rest.subList(n, rest.size)
                        break
                    }
                    ResponseAction.TERMINAL -> {
                        // WHO does this refusal indict? The shared response
                        // policy owns that question for every producer —
                        // Android, RN and (later) iOS see identical semantics,
                        // and a per-producer copy would be a second authority
                        // over the thing that deletes customer data.
                        //
                        // Only a rejection that identifies the SUBMITTED BATCH
                        // may quarantine. Everything else retains the queue
                        // byte-for-byte: the credential, the build and the
                        // session address all say nothing about the bytes, and
                        // a later corrected condition delivers the same
                        // records.
                        when (terminalDisposition(r.status, r.code)) {
                            TerminalDisposition.RETAIN_AUTH -> {
                                RecoveryTrace.log("auth_rejected", "status" to r.status)
                                results.last = r.copy(code = "auth_rejected")
                                // A credential says nothing about the bytes: a
                                // replaced key delivers the same records, so
                                // this stays RETRYABLE and is never parked by
                                // the session-origin path.
                                disposition = Disposition.RETRYABLE_REMAINING
                                return
                            }
                            TerminalDisposition.PARK_PRODUCER -> {
                                // No number of retries can make an old build
                                // emit fields it does not have — and the build
                                // is stamped into the captured bytes, so no
                                // LATER build can either. Retaining the slice
                                // in place therefore parks it at the head of a
                                // strict-FIFO queue forever and starves every
                                // record behind it, including records a
                                // compatible build captured afterwards.
                                //
                                // Move it to the third durable state and keep
                                // draining. Nothing is destroyed; the active
                                // queue advances.
                                if (parkPrefix(n, batch, slice, r.code)) {
                                    RecoveryTrace.log("inflight_settled",
                                        "by" to "parked", "chunk" to batch.chunkId,
                                        "recordCount" to n)
                                    inflight.clear()   // settled: parked
                                    results.last = r.copy(code = "producer_parked")
                                    rest = rest.subList(n, rest.size)
                                } else {
                                    // The parked copy is NOT durable, so the
                                    // active bytes stay exactly where they are.
                                    // Stop without rescheduling: an unparkable
                                    // refusal that can never clear must not
                                    // become a retry storm.
                                    RecoveryTrace.log("park_failed", "code" to (r.code ?: ""))
                                    results.last = r.copy(code = "producer_unsupported")
                                    // NEVER `TERMINAL_MOVE_PENDING` HERE. I had set it,
                                    // which turned an UNPARKABLE PRODUCER refusal into a
                                    // WorkManager RETRY where it was previously PERMANENT —
                                    // the precise accident of making unsupported producers
                                    // retry forever through a new result type.
                                    disposition = Disposition.TERMINAL_MOVE_BLOCKED
                                    return
                                }
                            }
                            TerminalDisposition.RETAIN_FEATURE_GATE -> {
                                // A record CLASS is switched off server-side.
                                // That is not a statement about these bytes, and
                                // the prefix is TYPE-BLIND — quarantining it
                                // would delete unrelated replay records that
                                // merely shared a chunk with one gated record.
                                //
                                // The in-flight descriptor is deliberately NOT
                                // cleared: the batch is unresolved, so the next
                                // drain must re-present THESE bytes rather than
                                // re-slice a queue that has grown.
                                RecoveryTrace.log("feature_gate_refused",
                                    "status" to r.status, "code" to (r.code ?: ""))
                                results.last = r.copy(code = "feature_gate_refused")
                                // Retryable, like auth: the SAME records are
                                // valid the moment the gate opens, so the
                                // constrained worker owns the retry under its
                                // own bounded backoff.
                                disposition = Disposition.RETRYABLE_REMAINING
                                return
                            }
                            TerminalDisposition.RETAIN_SESSION_PRECONDITION -> {
                                // THE RESPONSE ALONE IS NOT THE TERMINAL
                                // FACT. `session_not_found` with the opener
                                // still queued is TRANSIENT — the next pass
                                // originates the session and everything
                                // drains. It becomes terminal only when this
                                // producer no longer possesses ANY local
                                // mechanism able to create that session, which
                                // is a fact about the durable store, not about
                                // the reply.
                                val ev = terminalEvidence(
                                    sessionId = batchSession,
                                    firstSequence = batch.firstSequence,
                                    snapshot = rest,
                                    // The unresolved batch IS this slice, so it
                                    // holds an opener only if the slice does —
                                    // and the policy's firstSequence clause
                                    // already covers that case.
                                    inflightHoldsOpener = false,
                                )
                                if (SessionOriginPolicy.verdict(ev) ==
                                    SessionOriginPolicy.Verdict.TERMINAL_SESSION_ORIGIN
                                ) {
                                    RecoveryTrace.log(
                                        "session_origin_unrecoverable",
                                        "session" to batchSession,
                                        "firstSequence" to batch.firstSequence,
                                        "chunk" to batch.chunkId.take(12),
                                        "recordCount" to n)
                                    if (moveTerminal(n, batch, slice, pending)) {
                                        inflight.clear()
                                        rest = rest.subList(n, rest.size)
                                        results.last =
                                            r.copy(code = "session_origin_unrecoverable")
                                        disposition =
                                            if (rest.isEmpty()) Disposition.EMPTY
                                            else Disposition.TERMINAL_MOVED_AND_MORE_REMAIN
                                        resume = null
                                        // A healthy session behind a dead one
                                        // must make progress NOW, not after a
                                        // 2 s sleep it has nothing to do with.
                                        continue@slices
                                    }
                                    // Archival did not complete. Bytes stay.
                                    // The retry owed is the LOCAL move.
                                    results.last =
                                        r.copy(code = "session_origin_park_pending")
                                    disposition = terminalPendingOrBlocked()
                                    return
                                }
                                // The session does not exist YET. Unlike the
                                // two above this CAN clear by itself (an
                                // opener that names the app originates it), so
                                // the drain stops here and the constrained
                                // worker retries the SAME records under its
                                // own bounded exponential backoff — never a
                                // tight loop, never a deletion.
                                RecoveryTrace.log(
                                    "session_precondition_missing",
                                    "status" to r.status, "code" to (r.code ?: ""))
                                results.last = r.copy(code = "session_precondition_missing")
                                disposition = Disposition.RETRYABLE_REMAINING
                                return
                            }
                            TerminalDisposition.RESLICE_BATCH -> {
                                // The BOUNDARY was refused, not the records.
                                val refusedBodyBytes = body.size.toLong()
                                RecoveryTrace.log(
                                    "reslice_batch",
                                    "status" to r.status, "code" to (r.code ?: ""),
                                    "recordCount" to n,
                                    "bodyBytes" to refusedBodyBytes,
                                    "chunk" to batch.chunkId.take(12))
                                // THE DESCRIPTOR IS ABANDONED, and this is the
                                // ONLY disposition that abandons one without the
                                // bytes having moved anywhere. It is sound because
                                // a 413 on this edge is provably write-free — the
                                // parser refuses before the handler is entered and
                                // the typed event-count refusal returns before the
                                // controller's PREFLIGHT/write boundary — so there
                                // is no committed work a later batch could
                                // duplicate. Keeping it would re-present the
                                // identical oversized body forever, which is what
                                // makes a plain RETAIN the wrong answer here.
                                inflight.clear()
                                // THE RECORDS STAY PROTECTED (Slice B, W-22 / an open product question).
                                // The refused batch's REF is kept, marked write-free;
                                // the narrower batch that follows adds a ref over the
                                // same digests and consumes no capacity. The gap this
                                // abandonment left open — records unprotected between
                                // a 413 and the next freeze — is closed by the entries
                                // outliving the descriptor.
                                protection.markState(refId, DeliveryProtection.Strength.REFUSED_WRITE_FREE)
                                results.last = r.copy(code = "chunk_boundary_refused")
                                if (n <= 1) {
                                    // Already ONE record and still refused. Our own
                                    // arithmetic said it would fit; this server says
                                    // otherwise, and its answer is the authority.
                                    // There is nothing left to split, so the record
                                    // is PRESERVED — never deleted, never counted as
                                    // rejected data.
                                    RecoveryTrace.log(
                                        "record_body_too_large",
                                        "session" to batchSession,
                                        "chunk" to batch.chunkId.take(12),
                                        "recordBytes" to slice[0].size)
                                    if (parkPrefix(
                                            n, batch, slice, ChunkBounds.RECORD_BODY_TOO_LARGE)
                                    ) {
                                        rest = rest.subList(n, rest.size)
                                        disposition =
                                            if (rest.isEmpty()) Disposition.EMPTY
                                            else Disposition.TERMINAL_MOVED_AND_MORE_REMAIN
                                        resume = null
                                        continue@slices
                                    }
                                    disposition = terminalPendingOrBlocked()
                                    return
                                }
                                // Halve and rebuild from the SAME durable records.
                                // Each refusal at least halves the admitted count,
                                // so the sequence reaches one record in a bounded
                                // number of steps rather than retrying a size the
                                // server has already refused.
                                val s = ChunkBounds.shrink(n, refusedBodyBytes, resliceBudgetBytes)
                                resliceMaxRecords = s.maxRecords
                                resliceBudgetBytes = s.budgetBytes
                                RecoveryTrace.log(
                                    "reslice_bounds",
                                    "maxRecords" to s.maxRecords,
                                    "budgetBytes" to s.budgetBytes)
                                resume = null
                                continue@slices
                            }
                            TerminalDisposition.QUARANTINE_PREFIX -> {
                                // A permanent rejection OF THESE RECORDS
                                // quarantines THIS slice only.
                                RecoveryTrace.log("inflight_settled",
                                    "by" to "quarantined", "chunk" to batch.chunkId,
                                    "recordCount" to n)
                                inflight.clear()   // settled: quarantined
                                quarantinePrefix(n, batch, r.code)
                                rest = rest.subList(n, rest.size)
                            }
                        }
                        break
                    }
                    ResponseAction.RETRY -> {
                        if (attempt == maxRetries) {
                            // Retries exhausted — usually the device is OFFLINE.
                            appContext?.let { app ->
                                val q = RecoveryWorker.enqueue(app)
                                RecoveryTrace.log(
                                    if (q) "worker_enqueued" else "worker_enqueue_failed")
                            }
                            // Offline / transport failure: the worker's own
                            // bounded backoff owns this retry.
                            disposition = Disposition.RETRYABLE_REMAINING
                            return
                        }
                        try { sleeper(minOf(backoffMillis(attempt), 4_000L)) }
                        catch (_: InterruptedException) {
                            Thread.currentThread().interrupt(); return
                        }
                        attempt++
                    }
                }
            }
        }

        // The loop completed: every slice it could act on was acted on.
        // Whatever remains here is ordinary deliverable work, which is the
        // ONE disposition that may arm the 2 s first-pending deadline.
        disposition = if (rest.isEmpty()) Disposition.EMPTY
                      else Disposition.DELIVERABLE_REMAINING
    }

    /**
 * The ONE body encoder — used for the first attempt and every retry.
 *
 * F6: [lossCheckpoint] is appended as the LAST member, after `events`, so
 * the frozen three-key envelope's own punctuation is unchanged and
 * `ChunkBounds.ENVELOPE_PUNCTUATION_BYTES` (41) still describes exactly what
 * it says it describes. The key is `lossCheckpoint` — the request field the
 * DEPLOYED server reads (`MobileReplayController`), a sibling of `events`
 * and `sessionId`, and the document is already a conforming subset of its
 * `ALLOWED_KEYS`. `null` emits a body byte-identical to today's.
 */
    private fun encodeBody(
        sessionId: String,
        chunkId: String,
        slice: List<ByteArray>,
        lossCheckpoint: String? = null,
    ): ByteArray =
        ("{\"sessionId\":\"" + sessionId + "\",\"chunkId\":\"" + chunkId +
            "\",\"events\":[" + slice.joinToString(",") { String(it, Charsets.UTF_8) } + "]" +
            (if (lossCheckpoint != null) F6CheckpointEmitter.ATTACH_PREFIX + lossCheckpoint else "") +
            "}")
            .toByteArray(Charsets.UTF_8)

    /**
 * A stable fingerprint of the EXACT prefix boundary: sha256 over the joined
 * per-record digests. Safe by construction — a digest of digests carries no
 * record content, no text and no credential, and it is the one value that
 * makes "the same records, in the same order" checkable from a trace line.
 */
    private fun prefixFingerprint(digests: List<String>): String =
        sha256Hex(digests.joinToString("|").toByteArray(Charsets.UTF_8))

    private fun sha256Hex(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    /**
 * Is the live queue still the one the descriptor describes?
 *
 * The store may have GROWN while the batch was unresolved — those records
 * must survive and become the NEXT batch — but the first `recordCount`
 * records must still be, byte for byte, the ones submitted. Anything else
 * means the prefix moved and the descriptor can no longer be honoured.
 */
    private fun resumeStillMatches(d: InFlightBatch, current: List<ByteArray>): Boolean {
        if (current.size < d.recordCount) return false
        for (i in 0 until d.recordCount) if (sha256Hex(current[i]) != d.digests[i]) return false
        return true
    }

    /**
 * Remove exactly the acknowledged prefix — after re-checking that it is
 * still the prefix we sent.
 *
 * Between the snapshot and the acknowledgement the store may have GROWN
 * (that is fine, and those records must survive) but it must not have
 * SHIFTED. If the first n records are no longer the ones submitted, some
 * other path mutated the queue and removing n would delete the wrong
 * records; nothing is removed and the inconsistency is reported.
 */
    private fun dequeueExactly(n: Int, batch: BatchIdentity) {
        try {
            // Nested inside the SUCCESS arm's `DEQUEUE` region — one
            // acquisition; kept as its own region so the sequence is ordered
            // wherever it is reached from.
            region(QueueRegion.DEQUEUE, refused = { q ->
                results.last = results.last?.copy(code = "queue_" + q.wire)
                Unit
            }) {
            val current = store.readRecords()
            if (current.size < n || !batch.prefixMatches(current.subList(0, n))) {
                RecoveryTrace.log("prefix_recheck_failed", "have" to current.size, "want" to n)
                results.last = results.last?.copy(code = "queue_prefix_changed")
                return@region
            }
            // `prefix_removed` IS A CLAIM ABOUT THE DISK, so it may only be
            // written once the removal committed. `removeFirst` returns the
            // number ACTUALLY removed (0 on a failed rewrite, 0 on a refused
            // untrustworthy read) — ignoring it logged `prefix_removed count=n`
            // for a store that still held every one of those records.
            val going = current.subList(0, n).map { sha256Hex(it) }
            val removed = store.removeFirst(n)
            if (removed != n) {
                RecoveryTrace.log("prefix_remove_not_committed", "want" to n, "removed" to removed)
                results.last = results.last?.copy(code = "queue_remove_not_committed")
                return@region
            }
            RecoveryTrace.log("prefix_removed", "count" to n)
            faults("after_queue_remove")
            // an open product question: the removal committed, so the records' protection leaves
            // and the no-progress counter resets — real local progress.
            committedRemoval(going)
            // NO ATTRIBUTION CALL HERE ANY MORE. R1 is written BEFORE this
            // removal (see the SUCCESS branch): the server's commit is a fact
            // the moment the ack validates, and recording it afterwards left a
            // crash window in which the evidence was gone and the authority
            // still said ADMITTED. This function is now cleanup only, and it is
            // idempotent — `reconcileDelivered` finishes it after a crash.
            }
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            // A failed dequeue leaves the records in place. The server
            // deduplicates by chunk identity, so the retry is a `duplicate`
            // acknowledgement rather than a second copy.
            RecoveryTrace.log("dequeue_failed", "code" to t.javaClass.simpleName)
        }
    }

    /**
 * Move the refused prefix to the third durable state, in the ONE order
 * that cannot lose it.
 *
 * park (write + VERIFY by reading back)
 * -> re-check the active prefix is still the one we parked
 * -> removeFirst(n)
 *
 * No step removes active bytes before the parked copy is durable, and the
 * re-check is the same shift guard the acknowledged path uses: the store
 * may have GROWN while the request was in flight (those records must
 * survive) but if the first n are no longer the ones submitted, removing n
 * would delete the wrong records.
 *
 * Every failure path leaves the active queue byte-for-byte intact. A
 * parked copy that exists while the active copy was not removed is the
 * benign case — [reconcileParked] settles it on the next drain, before any
 * request.
 */
    /**
 * Is this session ALREADY durably known terminal?
 *
 * Read from the park store, which is the durable record of that knowledge.
 * This is what lets a LATER slice of the same dead session be archived with
 * ZERO network: the device learned the fact once and must not ask the
 * server again to re-learn it.
 */
    private fun sessionAlreadyTerminal(sessionId: String): Boolean {
      return try {
        val manifest = park.entriesRead()
        if (!manifest.isTrustworthy) {
            // NO is still the right ANSWER — guessing YES would park a
            // session on ignorance, which is the one thing this policy
            // promises it can never do. What changes is that the cost is
            // stated: the device re-asks the server for a fact it had already
            // durably learned, which is precisely the zero-HTTP property this
            // lookup exists to create. A silent `false` made that cost
            // indistinguishable from a session that was simply not terminal.
            RecoveryTrace.log("park_terminal_lookup_untrusted",
                "reason" to manifest.integrityClass)
            return false
        }
        manifest.entries.any {
            it.sessionId == sessionId &&
                it.refusalCode == SessionOriginPolicy.SESSION_ORIGIN_UNRECOVERABLE
        }
      } catch (_: Throwable) {
        RecoveryTrace.log("park_terminal_lookup_untrusted", "reason" to "read_threw")
        false
      }
    }

    /**
 * Gather the terminal evidence from DURABLE state, under one coherent read.
 *
 * THE SNAPSHOT MATTERS, WHICH IS WHY THIS IS NOT AN UNLOCKED SCAN OF A
 * LIST WE HAPPEN TO HOLD. `snapshot` is the record list this drain pass is
 * working from; `store.readRecords` is re-read HERE, at decision time,
 * and the opener must be absent from BOTH for the slice to be terminal. A
 * record appended between the two reads can only be a LATER record of the
 * session (the opener is sequence 0 and is emitted once), so requiring
 * agreement cannot produce a false terminal — and if the live read fails,
 * the catch below keeps the slice transient.
 *
 * AND IT DOES NOT CONSULT AN IN-MEMORY SESSION REGISTRY. A registry is
 * empty on every cold start, which is exactly the case this defect survives
 * it would answer "no opener" for every session after a restart and park
 * sessions whose opener is sitting in the queue. The store is the authority
 * because the store is what survives.
 */
    private fun terminalEvidence(
        sessionId: String,
        firstSequence: String,
        snapshot: List<ByteArray>,
        inflightHoldsOpener: Boolean,
    ): SessionOriginPolicy.Evidence {
        val seq = firstSequence.toLongOrNull() ?: -1L
        val inSnapshot = snapshot.any { SessionOriginPolicy.recordOpensSession(it, sessionId) }
        val live = try {
            store.readRecords()
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("terminal_evidence_unreadable", "code" to t.javaClass.simpleName)
            // Unknown -> claim the opener IS present, i.e. stay transient.
            return SessionOriginPolicy.Evidence(
                sessionPreconditionRefusal = true, firstSequence = seq,
                openerPresentInActiveStore = true, openerRecoverableElsewhere = true,
                sessionAlreadyTerminal = false)
        }
        val inLive = live.any { SessionOriginPolicy.recordOpensSession(it, sessionId) }
        // ABSENCE IS ONLY EVIDENCE WHEN THE READ WAS COMPLETE.
        //
        // `EncryptedEventStore.readAll` recovers what it can and REPORTS what
        // it lost — a frame that failed GCM authentication, a truncated tail, an
        // unreadable file. An opener destroyed by corruption is absent from the
        // list in exactly the same way as an opener that never existed, and only
        // this flag separates them. Treating a lossy read as proof of absence is
        // parking on ignorance, which the policy promises it can never do.
        val trustworthy = try { store.readIsComplete() } catch (_: Throwable) { false }
        if (!trustworthy) {
            RecoveryTrace.log("terminal_evidence_untrustworthy", "session" to sessionId)
        }
        return SessionOriginPolicy.Evidence(
            sessionPreconditionRefusal = true,
            firstSequence = seq,
            openerPresentInActiveStore = inSnapshot || inLive || !trustworthy,
            openerRecoverableElsewhere = inflightHoldsOpener,
            sessionAlreadyTerminal = sessionAlreadyTerminal(sessionId),
        )
    }

    /**
 * Persist the terminal disposition, THEN move the bytes.
 *
 * The order is the whole point of the amendment. If the process dies after
 * step 1 the next launch reads the descriptor, sees the reason, and RESUMES
 * THE PARK — it does not POST again. Persisting after the move would leave
 * a window in which a restart forgets the classification and re-asks a
 * server that can only ever say no.
 *
 * Returns true only when the bytes are durably parked AND the active prefix
 * was removed. Every failure path leaves the active bytes exactly where
 * they are: no delete, no false success, no quarantine classification.
 */
    private fun moveTerminal(
        n: Int,
        batch: BatchIdentity,
        slice: List<ByteArray>,
        pending: InFlightBatch?,
    ): Boolean {
        val reason = SessionOriginPolicy.SESSION_ORIGIN_UNRECOVERABLE
        if (pending == null || pending.terminalReason != reason) {
            val d = (pending ?: InFlightBatch(
                sessionId = batch.sessionId, chunkId = batch.chunkId,
                firstSequence = batch.firstSequence, lastSequence = batch.lastSequence,
                recordCount = n, contentSha256 = "",
                digests = slice.map { sha256Hex(it) },
                firstAttemptAtMs = nowMillis(), attempts = 0,
            )).copy(terminalReason = reason)
            if (!inflight.save(d)) {
                // The classification is NOT durable, so the move must not
                // proceed: a park completed without a persisted reason could be
                // interrupted and forgotten. Retain and report pending.
                RecoveryTrace.log("terminal_reason_not_persisted", "chunk" to batch.chunkId.take(12))
                return false
            }
            RecoveryTrace.log(
                "terminal_reason_persisted",
                "reason" to reason, "session" to batch.sessionId,
                "chunk" to batch.chunkId, "firstSequence" to batch.firstSequence,
                "lastSequence" to batch.lastSequence)
        }
        return parkPrefix(n, batch, slice, reason)
    }

    private fun parkPrefix(
        n: Int,
        batch: BatchIdentity,
        slice: List<ByteArray>,
        code: String?,
    ): Boolean = try {
        // THE WHOLE MOVE IS ONE `PARK` REGION (Slice B, B4): adoption, the
        // park write and its read-back, the loss settlement, the prefix
        // re-check and the removal see ONE image. `EncryptedParkStore.park`
        // takes the same two layers and re-enters them here.
        region(QueueRegion.PARK, refused = { _ -> false }) {
        // F4-P0 Slice D (D7) — THE PREFIX IS RE-CHECKED BEFORE THE PARK WRITE
        // AND BEFORE THE LOSS, not only before the removal. A slice that reaches
        // this region without a freeze (the too-large record, a resumed terminal
        // descriptor) carries no protection, so a bracket may have evicted one of
        // its records since the image was read; writing the parked copy and
        // settling LOST(PARKED) for THAT slice leaves the evicted record parked
        // AND BYTE_EVICTED — two terminal dispositions for one record. Inside this
        // region nothing can move the head, so a slice that IS the head here
        // stays the head through the write, the settlement and the removal.
        // The post-write re-check below stays: it is the guard the A8 rows
        // exercise (the head shifted during the write itself).
        val head = store.readRecords()
        if (head.size < n || !batch.prefixMatches(head.subList(0, n))) {
            RecoveryTrace.log("park_prefix_stale_before_write", "have" to head.size, "want" to n,
                "chunk" to batch.chunkId.take(12))
            false
        } else
        // ADOPT BEFORE THE PARK COMMIT (store review R3). The park settlement
        // is written at the commit and the active bytes go right after; a pre-F2
        // session's last records must be owned by an authority before either.
        if (!adoptedForRemoval(batch.sessionId, batch.chunkId)) {
            false
        } else {
        val now = nowMillis()
        // `firstSeenAt` is inherited from an existing entry so a re-park after
        // a crash records when we FIRST refused, not when we retried.
        val existing = park.entries().firstOrNull { it.chunkId == batch.chunkId }
        val entry = ParkedBatch(
            chunkId = batch.chunkId,
            sessionId = batch.sessionId,
            firstSequence = batch.firstSequence,
            lastSequence = batch.lastSequence,
            recordCount = batch.recordCount,
            producerFamily = producerField(slice, "sdkFamily"),
            producerVersion = producerField(slice, "sdkVersion"),
            refusalCode = (code ?: "").take(64),
            firstSeenAtMs = existing?.firstSeenAtMs ?: now,
            parkedAtMs = now,
        )
        val outcome = park.park(entry, slice)
        lastParkOutcome = outcome
        if (outcome != ParkOutcome.PARKED) {
            RecoveryTrace.log(
                if (outcome == ParkOutcome.BLOCKED) "park_capacity_blocked"
                else "park_retryable_failure",
                "chunk" to batch.chunkId.take(12), "code" to (code ?: ""))
            false
        } else {
            // R2/R3 — THE LOSS IS RECORDED HERE, AFTER THE PARK COMMIT AND
            // BEFORE THE ACTIVE REMOVAL, AND THAT IS A DELIBERATE DEPARTURE FROM
            // THE QUARANTINE ORDER.
            //
            // A parked slice is never automatically retried, so the moment the
            // parked copy is durable these records have left the active timeline
            // permanently — the fact is already true, whether or not this process
            // manages to remove the active bytes. Recording it only after
            // `removeFirst` made the loss contingent on the removal, and the
            // removal is exactly the step a mutation fence can refuse: the slice
            // then stayed at the head, un-settled, and every drain re-parked and
            // re-refused it. Settling first also RESOLVES those sequences, which
            // is what lets the queue advance.
            //
            // Quarantine keeps the opposite order because there the loss IS the
            // destruction this process performs.
            val lost = attribution.recordLost(
                batch.sessionId, recordSequences(slice), LossAuthority.Cause.PARKED)
            if (lost is AttributionSink.Outcome.NotDurable) {
                // A fenced authority cannot state the loss, so the active bytes
                // stay. The parked copy is durable and `reconcileParked` settles
                // it on a later drain, without duplicating it.
                RecoveryTrace.log("park_loss_not_durable", "why" to lost.reason,
                    "chunk" to batch.chunkId.take(12))
                false
            } else {
            RecoveryTrace.log("park_loss_recorded", "outcome" to lost.javaClass.simpleName,
                "chunk" to batch.chunkId.take(12), "count" to n)
            faults("after_park_loss_recorded")
            val current = store.readRecords()
            if (current.size < n || !batch.prefixMatches(current.subList(0, n))) {
                RecoveryTrace.log(
                    "park_prefix_recheck_failed", "have" to current.size, "want" to n)
                false
            } else {
                // THE REMOVAL MUST ACTUALLY HAVE HAPPENED. `removeFirst`
                // now returns 0 when the durable rewrite failed; reporting a
                // park as settled while the active prefix is still on disk is
                // false progress, and it is exactly what the crash/reconcile
                // contract forbids.
                if (store.removeFirst(n) != n) {
                    RecoveryTrace.log("active_remove_failed", "want" to n,
                        "chunk" to batch.chunkId.take(12))
                    false
                } else {
                    // The loss was recorded above, at the park commit. Nothing
                    // is settled here: the removal is cleanup of bytes already
                    // accounted for. The protection over the moved records
                    // leaves with them.
                    committedRemoval(current.subList(0, n).map { sha256Hex(it) })
                    val st = park.status()
                    RecoveryTrace.log(
                        "producer_parked",
                        "count" to n,
                        "chunk" to batch.chunkId.take(12),
                        "code" to (code ?: ""),
                        "parkedBatches" to st.batchCount,
                        "parkedRecords" to st.recordCount)
                    true
                }
            }
            }
        }
        }
        }
    } catch (t: Throwable) {
        if (t is SimulatedProcessDeath) throw t
        RecoveryTrace.log("park_failed", "code" to t.javaClass.simpleName)
        false
    }

    /**
 * R1 CRASH RECOVERY — remove the leading run of records the durable
 * authority ALREADY records as DELIVERED.
 *
 * The delivery authority is now written BEFORE the queue prefix is removed,
 * so a process that dies in that window comes back with records the server
 * has committed still sitting at the head of the active queue. Without this
 * pass they would be re-submitted: wasteful at best, and at worst a second
 * committed chunk for the same sequences, or — if a later refusal classified
 * them terminal — a deletion of records already accounted as delivered.
 *
 * PER RECORD, BY SEQUENCE, FROM THE HEAD ONLY. A sequence is allocated
 * once per session and never reused, so a session id plus a sequence
 * identifies one record; a DELIVERED verdict for it is a statement about
 * that record and no other. Only the leading run is swept, because the queue
 * is FIFO and an unremoved delivered prefix is exactly what the crash leaves.
 *
 * IT STOPS AT THE FIRST THING IT CANNOT READ. A record with no readable
 * session or sequence is never swept — not knowing is not a licence to
 * delete — and an unwired authority sweeps nothing at all.
 *
 * Idempotent: a second pass finds a run of zero.
 *
 * STATED LIMIT: IDENTITY HERE IS `(session, sequence)`, NOT A DIGEST.
 * The in-flight descriptor path verifies per-record digests because it can —
 * it holds them. This pass cannot: after a crash the descriptor may be gone,
 * and the delivered authority stores the sequence SET, not the bytes. So a
 * record carrying a delivered `(session, sequence)` pair is swept on the
 * strength of that pair. That pair identifies one record BY CONSTRUCTION —
 * the authority allocates a sequence once and never reuses it, and the
 * producer stamps it natively at capture — so a second record claiming it
 * would be a producer defect, not something this pass could distinguish. It
 * is recorded here rather than implied to be airtight.
 */
    private fun reconcileDelivered(initial: List<ByteArray>): List<ByteArray> {
        if (!attribution.isWired() || initial.isEmpty()) return initial
        // Group the head run by session so one authority lookup covers a whole
        // batch rather than one record at a time.
        val heads = ArrayList<Pair<String, Long>>()
        for (b in initial) {
            val sid = recordField(b, "sessionId") ?: break
            val seq = recordSequence(b)
            if (sid.isEmpty() || seq < 0) break
            heads.add(sid to seq)
        }
        if (heads.isEmpty()) return initial
        val deliveredBySession = HashMap<String, Set<Long>>()
        var run = 0
        for ((sid, seq) in heads) {
            val known = deliveredBySession.getOrPut(sid) {
                attribution.deliveredSequences(sid, heads.filter { it.first == sid }.map { it.second })
            }
            if (!known.contains(seq)) break
            run++
        }
        if (run == 0) return initial
        return try {
            // ONE `RECONCILE_DELIVERED` REGION for the re-check and the removal
            // (Slice B, B4). A refused layer leaves the image unchanged.
            region(QueueRegion.RECONCILE_DELIVERED, refused = { _ -> initial }) {
            // Re-check against the LIVE store — the same shift guard the
            // acknowledged and parked paths use.
            val live = store.readRecords()
            if (live.size < run) {
                RecoveryTrace.log("delivered_reconcile_prefix_changed", "have" to live.size, "want" to run)
                return@region initial
            }
            for (i in 0 until run) {
                if (recordSequence(live[i]) != heads[i].second || recordField(live[i], "sessionId") != heads[i].first) {
                    RecoveryTrace.log("delivered_reconcile_prefix_changed", "at" to i)
                    return@region initial
                }
            }
            val going = live.subList(0, run).map { sha256Hex(it) }
            val removed = store.removeFirst(run)
            if (removed != run) {
                RecoveryTrace.log("delivered_reconcile_not_committed", "want" to run, "removed" to removed)
                return@region initial
            }
            RecoveryTrace.log("delivered_reconciled", "count" to run)
            committedRemoval(going)
            store.readRecords()
            }
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("delivered_reconcile_failed", "code" to t.javaClass.simpleName)
            initial
        }
    }

    /**
 * Remove any ACTIVE prefix whose bytes are already durably parked.
 *
 * This is the crash guarantee made real: a process that died between the
 * parked write and `removeFirst` left one batch in both stores, and the
 * next drain must resolve that WITHOUT a request and WITHOUT duplicating
 * the parked copy. Identity is recomputed from the live bytes with the
 * same content-addressed function that produced it, so a coincidental
 * match is a byte-for-byte match.
 *
 * Runs before any slicing so a parked slice is never re-submitted.
 */
    private fun reconcileParked(initial: List<ByteArray>): List<ByteArray> {
        var current = initial

        // AN UNREADABLE MANIFEST IS NOT AN EMPTY ONE, AND THIS RETURN USED
        // TO SAY IT WAS — silently, in a `catch` and in an `isEmpty` that
        // could not tell the two apart. The consequence is specific: a crash
        // between "parked copy durable" and "active prefix removed" leaves a
        // DUPLICATE active prefix, and this pass is the only thing that settles
        // it. Reading an unreadable manifest as "nothing is parked" means the
        // duplicate is re-submitted instead of reconciled, for as long as the
        // manifest stays damaged.
        //
        // Returning unchanged is still the correct ACTION — nothing may be
        // removed on the strength of a read that failed — but it is now a
        // stated refusal rather than a silent one, so an operator can tell a
        // quiet store from a broken one.
        val manifest = try {
            park.entriesRead()
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("park_reconcile_refused", "why" to "manifest_read_threw",
                "code" to t.javaClass.simpleName)
            return current
        }
        if (!manifest.isTrustworthy) {
            RecoveryTrace.log("park_reconcile_refused", "why" to "manifest_unreadable",
                "reason" to manifest.integrityClass,
                "readable_entries" to manifest.entries.size)
            return current
        }
        val parked = manifest.entries
        if (parked.isEmpty()) return current
        // SETTLE THE OWED PARK LOSS FROM THE MANIFEST FIRST (liveness B3).
        // The loss becomes true at the park COMMIT, so a process that died
        // between the commit and the settlement owes it — and only the durable
        // manifest can still say which sequences it covered.
        //
        // ONLY A PROVABLY CONTIGUOUS RANGE, and only the exact session the
        // entry names. `firstSequence + recordCount - 1 == lastSequence` is the
        // one arithmetic that makes the range derivable from the manifest alone;
        // anything else would be a guess about which numbers were in the batch,
        // and a guessed LOST settlement is a fabricated loss. Idempotent by the
        // authority's own rules: a sequence already LOST or DELIVERED is skipped,
        // never restated, so repeating this every drain changes nothing.
        for (p in parked) {
            // THE MANIFEST CARRIES SEQUENCES AS DECIMAL STRINGS, deliberately
            // an int64 through a JS Number loses precision, and this is the one
            // place a rounding error becomes a wrong LOST settlement. The rule
            // lives in ONE pure place (`ParkedRangeRule`) so its acceptance
            // predicate is TESTED to be satisfiable by a legitimate parked batch
            // and to refuse every other shape: a value that does not parse,
            // a zero or negative count, an off-by-one range and an arithmetic
            // overflow are each a refusal, never a coercion and never a guess.
            val first: Long
            val last: Long
            when (val v = ParkedRangeRule.contiguous(p.firstSequence, p.lastSequence, p.recordCount)) {
                is ParkedRangeRule.Verdict.Refused -> {
                    RecoveryTrace.log("park_loss_range_refused", "chunk" to p.chunkId.take(12),
                        "why" to v.reason, "count" to p.recordCount)
                    continue
                }
                is ParkedRangeRule.Verdict.Range -> { first = v.first; last = v.last }
            }
            val out = attribution.recordLost(
                p.sessionId, (first..last).toList(), LossAuthority.Cause.PARKED)
            if (out !is AttributionSink.Outcome.NotWired) {
                RecoveryTrace.log("park_loss_reconciled", "chunk" to p.chunkId.take(12),
                    "outcome" to out.javaClass.simpleName, "count" to p.recordCount)
            }
        }
        // ONE `RECONCILE_PARKED` REGION for the whole settle loop (Slice B,
        // B4): every head match, adoption, re-check and removal sees one image.
        return region(QueueRegion.RECONCILE_PARKED, refused = { _ -> current }) {
        var progressed = true
        while (progressed && current.isNotEmpty()) {
            progressed = false
            for (p in parked) {
                if (p.recordCount <= 0 || current.size < p.recordCount) continue
                val head = current.subList(0, p.recordCount)
                if (BatchIdentity.of(p.sessionId, head).chunkId != p.chunkId) continue
                // ADOPT BEFORE THE RECONCILE REMOVAL TOO (store review R3): a
                // parked copy from a pre-F2 process is durable, but the active
                // duplicate is still the only thing that proves the session's
                // allocation to an authority that may not exist yet.
                if (!adoptedForRemoval(p.sessionId, p.chunkId)) return@region current
                current = try {
                    // Re-check against the LIVE store, not the snapshot: the
                    // same shift guard the acknowledged and parked paths use.
                    val live = store.readRecords()
                    if (live.size < p.recordCount ||
                        BatchIdentity.of(p.sessionId, live.subList(0, p.recordCount))
                            .chunkId != p.chunkId
                    ) {
                        RecoveryTrace.log("park_reconcile_prefix_changed", "want" to p.recordCount)
                        return@region current
                    }
                    // `park_reconciled` asserts the duplicate active prefix
                    // is GONE. Ignoring the return value logged it for a store
                    // that still held the records, so the next pass would meet
                    // the same duplicate and report the same reconciliation.
                    val going = live.subList(0, p.recordCount).map { sha256Hex(it) }
                    val removed = store.removeFirst(p.recordCount)
                    if (removed != p.recordCount) {
                        RecoveryTrace.log(
                            "park_reconcile_not_committed",
                            "want" to p.recordCount,
                            "removed" to removed,
                            "chunk" to p.chunkId.take(12))
                        return@region current
                    }
                    RecoveryTrace.log(
                        "park_reconciled",
                        "count" to p.recordCount,
                        "chunk" to p.chunkId.take(12))
                    committedRemoval(going)
                    store.readRecords()
                } catch (t: Throwable) {
                    if (t is SimulatedProcessDeath) throw t
                    RecoveryTrace.log("park_reconcile_failed", "code" to t.javaClass.simpleName)
                    return@region current
                }
                progressed = true
                break
            }
        }
        current
        }
    }

    /**
 * The producer identity the records themselves state. Bounded and never a
 * caller-supplied value — this is provenance for an operator, not a field
 * anything reads back.
 */
    /** The producer's own stamped sequences, in slice order; an unreadable record contributes nothing. */
    private fun recordSequences(slice: List<ByteArray>): List<Long> =
        slice.map { b -> recordSequence(b) }.filter { it >= 0 }

    /**
 * ADOPT BEFORE REMOVE — the ONE gate every removal of [session]'s
 * records passes through (store review R3, binding ruling 2026-09-25).
 *
 * A drain that delivers or parks the LAST records of a pre-F2 session Y and
 * then removes them destroys the only local evidence that Y ever allocated
 * a sequence: the non-creating lookup correctly answered "no authority",
 * the bytes went, and a later `start(sessionId = Y)` found an empty queue,
 * genesis-ed at -1 and re-issued sequence 0 — the reuse class F2 exists to
 * prevent, reached through the read-only door doing its job.
 *
 * So, for the exact slice about to go: derive Y from the slice (the caller
 * already has — `batchSession` / the manifest entry), hand the authority the
 * LIVE queue as the store read it, and let the facade's REMOVAL door do the
 * non-creating lookup first, reconcile an existing authority, or CREATE +
 * ADOPT a pre-F2 session's trustworthy surviving records. Only a DURABLY
 * committed answer lets the removal proceed; anything else keeps the bytes
 * and the ordinary retry earns a `duplicate` ack later.
 *
 * THE VIEW IS THE STORE'S OWN READ, AND ITS OWN INTEGRITY ANSWER. Never a
 * slice, never the drain's snapshot from the top of `upload` (records may
 * have been appended since), never another session, never the running
 * session's state, never a server assumption, never a local counter.
 */
    private fun adoptedForRemoval(session: String, chunkId: String): Boolean {
        val queue: List<ByteArray>
        val trustworthy: Boolean
        try {
            queue = store.readRecords()
            trustworthy = store.integrityClass() == RecordStore.INTEGRITY_OK
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("removal_adoption_unreadable", "code" to t.javaClass.simpleName, "chunk" to chunkId.take(12))
            return false
        }
        val a = attribution.adoptForRemoval(session, queue, trustworthy)
        RecoveryTrace.log("removal_adoption", "outcome" to a.javaClass.simpleName,
            "session" to session.take(24), "chunk" to chunkId.take(12), "count" to queue.size)
        return when (a) {
            is AttributionSink.Adoption.Ready, is AttributionSink.Adoption.NotWired -> { faults("after_removal_adoption"); true }
            is AttributionSink.Adoption.NotDurable -> false
            is AttributionSink.Adoption.Refused -> false
        }
    }

    /**
 * F3-P1b — hand a PARSED `loss-checkpoint-ack-1` to the session's authority through
 * the attribution seam, and record what it answered. ROUTING ONLY: the values go
 * through verbatim, nothing is compared, derived or decided here (P1b-17, OCH-3), and
 * the answer changes NOTHING about the chunk — it is traced and dropped. A settlement
 * problem is a LOSS problem and is reported as one; the chunk was committed and the
 * server said so.
 *
 * Called ONLY when a loss block was parsed — see the call site. The caller-side
 * guard is the one and only trigger.
 */
    private fun settleLossCheckpoint(session: String, loss: Uploader.LossAck) {
        val s: AttributionSink.Settlement = try {
            attribution.settleLossCheckpoint(
                session, loss.protocol, loss.lossAccountingVersion, loss.state, loss.sessionId,
                loss.acceptedThroughSequence, loss.acceptedCounts, loss.terminal,
            )
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("loss_checkpoint_settlement_failed", "code" to t.javaClass.simpleName,
                "session" to session.take(24))
            return
        }
        val reason = when (s) {
            is AttributionSink.Settlement.PolicyDeclined -> s.reason
            is AttributionSink.Settlement.Refused -> s.reason
            is AttributionSink.Settlement.NotDurable -> s.reason
            is AttributionSink.Settlement.Recorded, is AttributionSink.Settlement.NotWired -> ""
        }
        RecoveryTrace.log("loss_checkpoint_settlement", "outcome" to s.javaClass.simpleName,
            "state" to loss.state, "reason" to reason.take(40), "session" to session.take(24))
    }

    // ───────────────────────── F7 SLICE A: the metadata-only emission ──────

    /**
 * The outcome of ONE non-terminal metadata-only emission attempt.
 *
 * EVERY ARM IS A NO-OP FOR THE QUEUE. No record is removed, parked or
 * quarantined on any path — a metadata-only request carries no records, and
 * a checkpoint refusal is a statement about the loss statement.
 */
    internal sealed class MetadataOnly {
        /** The emitter declined (no authority, disabled, nothing to advance, …). */
        internal object NotEmitted : MetadataOnly()
        /** Posted, and the server's `loss-checkpoint-ack-1` was routed for settlement. */
        internal data class Acknowledged(val state: String) : MetadataOnly()
        /** Posted; the response carried NO parseable loss block, so nothing settled. */
        internal data class Unacknowledged(val status: Int, val code: String?) : MetadataOnly()
        /** The request could not be made at all. */
        internal data class NotSent(val code: String) : MetadataOnly()
    }

    /**
 * **F7 SLICE A — emit ONE non-terminal metadata-only loss checkpoint for
 * [sessionId], with `events: []` and NO delivered chunk.**
 *
 * Frozen the capture specification: *"metadata-only carrier: the same
 * `/replay/chunks` route with `events: []`, under the metadata-only gap
 * formula (not the chunk-riding one)"*. The deployed route branches on an
 * empty `events` array and a present `lossCheckpoint` BEFORE any chunk
 * preflight, so this reuses the ONE body encoder with an EMPTY slice and the
 * EXISTING `lossCheckpoint` key — no second encoder, no second wire writer,
 * no new endpoint, no protocol change.
 *
 * **THIS HAS NO PRODUCTION CALLER YET, AND THAT IS DELIBERATE.** The
 * frozen authority names no product TRIGGER for a chunkless disclosure —
 * a conformance test is a CARRIER row and the capture specification describes the carrier — so
 * choosing the moment a producer decides to disclose loss with no chunk is a
 * product policy decision of the same class as an open product question and is reported for a
 * ruling instead of invented. Everything the emission needs is implemented
 * and reachable; a later dispatch adds a call site, and the DEV
 * certification of C16/C17 drives this entry point directly.
 *
 * **IT TOUCHES NO RECORD AND NO DISPOSITION.** It takes no queue region,
 * reads no slice, removes nothing, parks nothing, quarantines nothing, and
 * never changes the drain's disposition, the reslice budgets, the in-flight
 * descriptor or the delivery protection. A refused or unacknowledged
 * emission leaves the durable claim exactly where the emitter persisted it.
 *
 * **SETTLEMENT IS THE EXISTING BINDER/AUTHORITY PATH, UNCHANGED.** A
 * parsed `loss-checkpoint-ack-1` is routed through the SAME
 * [settleLossCheckpoint] the delivered-chunk path uses, which binds in
 * `CheckpointAckBinder` and appends through the facade. No second settlement
 * home, and `LossLedger.acknowledge` is never reached.
 */
    internal fun emitMetadataOnlyCheckpoint(sessionId: String): MetadataOnly {
        val doc = try {
            attribution.f7MetadataOnlyCheckpoint(sessionId)
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("f7_metadata_only_decide_failed", "code" to t.javaClass.simpleName,
                "session" to sessionId.take(24))
            return MetadataOnly.NotSent(t.javaClass.simpleName)
        } ?: return MetadataOnly.NotEmitted
        // The ONE body encoder, with an EMPTY slice: `"events":[]`.
        val body = encodeBody(sessionId, doc.chunkId, emptyList(), doc.json)
        // TWO LITERAL EVENT NAMES, NOT A `retry` VALUE. `RecoveryTracePrivacyTest`
        // allows only a curated set of bounded value shapes, and widening a PRIVACY
        // allowlist for a diagnostic flag is the wrong trade; the event name carries
        // the distinction instead and is not a value expression at all.
        RecoveryTrace.log(
            if (doc.retry) "f7_metadata_only_retry" else "f7_metadata_only_request",
            "session" to sessionId.take(24), "chunk" to doc.chunkId.take(12),
            "bytes" to body.size)
        val r = try {
            poster.post(projectId, body)
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("f7_metadata_only_transport_failed", "code" to t.javaClass.simpleName)
            return MetadataOnly.NotSent(t.javaClass.simpleName)
        }
        // THE PARSED LOSS BLOCK IS THE ONLY TRIGGER, exactly as on the
        // delivered-chunk path. A 2xx with no block settles nothing.
        val loss = r.lossAck
        if (loss == null) {
            RecoveryTrace.log("f7_metadata_only_unacknowledged",
                "status" to r.status, "code" to (r.code ?: ""))
            return MetadataOnly.Unacknowledged(r.status, r.code)
        }
        settleLossCheckpoint(sessionId, loss)
        return MetadataOnly.Acknowledged(loss.state)
    }

    /**
 * **F7 R-2 — THE GOVERNED TRIGGER. The one production caller of the
 * metadata-only emission, and the only shape it may produce.**
 *
 * Board decision #2760 (2026-10-27), verbatim:
 *
 * > *"R-2 zero-chunk metadata-only checkpoint is emitted once at definitive
 * > session finalization when no replay chunk was durably emitted; use
 * > durable idempotency, retry retained record, no synthetic replay
 * > content."*
 *
 * [emitMetadataOnlyCheckpoint] had ZERO production callers because the
 * frozen authority named a carrier and no TRIGGER — *"choosing the moment a
 * producer decides to disclose loss with no chunk is a product policy
 * decision … reported for a ruling instead of invented"*. This is that
 * ruling, written as the smallest predicate that can express it, and
 * nothing below it changed: the emission, the persistence, the settlement
 * and the refusals are the accepted path, reached rather than rewritten.
 *
 * **"no replay chunk durably emitted" IS THE EMPTY BRANCH OF THE DRAIN,
 * AND THAT IS WHY THE ONE CALLER IS [uploadInner]'s.** F6 is the disclosure
 * that RIDES a delivered chunk; while a chunk is going out the disclosure
 * has a carrier, and a second mechanism stating the same fact is how one
 * fact becomes two that can disagree. The branch above refuses to make a
 * request at all because the queue is empty — which is exactly the state in
 * which a finalized session would otherwise never be told — so the
 * condition is established from the drain's own image rather than claimed
 * by a caller. The TERMINALITY half is this function's, below.
 *
 * IT IS REACHED THROUGH THE PRODUCERS' ORDINARY DRAIN, not through a new
 * entry point. Every Android drain site used to RETURN on an empty queue,
 * so the accepted design's *"produced … on the next ordinary drain"* named
 * a path no production drain could take; the sites now hand the empty list
 * to the same `uploadRaw`, whose own body is byte-unchanged.
 *
 * **TERMINALITY IS READ BEFORE A DOCUMENT IS ASKED FOR, NEVER AFTER.**
 * [AttributionSink.f7MetadataOnlyCheckpoint] persists a durable claim
 * before it returns bytes, so filtering by shape afterwards would already
 * have written an `advance` claim — the non-terminal disclosure this ruling
 * does NOT authorise. Asking the cheap question first means the
 * unauthorised shape is never requested.
 *
 * **"ONCE" IS DURABLE, NOT A FLAG HERE.** This object holds no latch and
 * no memo. A second call after the server acknowledged is refused by
 * core — *"terminality already acknowledged at this boundary"* — and a
 * second call while the claim is still pending RE-SENDS the retained
 * record by value rather than minting a new one. So "once" survives process
 * death, which a process-local boolean could not.
 *
 * **A BLANK SESSION IS REFUSED, not defaulted.** A drain whose queue is
 * empty cannot name a session from its records; a caller that cannot say
 * WHICH session finalized has not met the precondition, and guessing the
 * live one would terminalize-disclose a session the caller never meant.
 */
    /**
 * **F7 R-2 CROSS-LAUNCH — every session this chunkless drain must ask the
 * gate about: the one it is running under, then the ones the DURABLE record
 * says a host declared over.**
 *
 * **THE DRAIN'S OWN SESSION STAYS FIRST, AND UNCONDITIONALLY.** An
 * in-session drain after `endSessionAuthoritatively` is the ordinary case
 * and must be byte-unchanged: same single question, same trace, same
 * refusal for a blank id (R2-3). Everything below it is ADDITIONAL, and on
 * the common install there is nothing below it.
 *
 * **THE EXTRA CANDIDATES ARE NAMED BY THE AUTHORITY, NOT BY THIS
 * PROCESS.** The case this serves is a host that declared a session over
 * and was then KILLED: no in-memory pointer survives that, which is why
 * `RnTransport`'s `finalizedSession` field — correct for its own
 * same-process drain — cannot answer it either. `pruneOne` refuses to
 * delete a directory whose claim is `terminal_requested_unacknowledged`, so
 * the claim is still on disk at the next launch; the sink enumerates it.
 *
 * **THE DRAIN'S OWN SESSION IS EXCLUDED EXACTLY ONCE, by the sink.** It
 * is already the head of this list, and asking the gate about it twice in
 * one drain would re-send a pending claim by value inside a single pass —
 * breaking the one-disclosure-per-drain shape R2-1 and R2-4 pin. A second
 * filter here would be a redundant guard that no control can distinguish;
 * one place does it, and `R2-12` is what fails when that place stops.
 *
 * **EVERY CANDIDATE IS OFFERED, and the GATE decides.** This list is
 * "which sessions could possibly owe a finality disclosure", never "which
 * do": `finalizeChunklessSession` reads the durable terminal claim for each
 * and emits for none that has not ended. Filtering by terminality here as
 * well was measured to change no behaviour — the gate re-reads it anyway —
 * so it was removed rather than kept as a second opinion.
 *
 * **COST, MEASURED rather than assumed** (JVM, 2026-10-27): the name
 * list is 17–201 µs; putting each candidate to the gate opens and decrypts
 * that session's authority, ~0.7 ms each — about 6 ms at the eight
 * directories `prune` retains. An install whose only authority is the live
 * session's pays the name list and stops. That is the price of the drain
 * that can actually make this disclosure, on a background path that
 * already reads an encrypted queue.
 */
    private fun chunklessFinalityCandidates(fallbackSessionId: String): List<String> {
        val inherited = try {
            attribution.f7InheritedSessions(fallbackSessionId)
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("f7_inherited_candidates_threw", "why" to t.javaClass.simpleName)
            emptyList()
        }
        if (inherited.isEmpty()) return listOf(fallbackSessionId)
        RecoveryTrace.log("f7_inherited_candidates", "count" to inherited.size)
        return listOf(fallbackSessionId) + inherited
    }

    internal fun finalizeChunklessSession(sessionId: String): MetadataOnly {
        if (sessionId.isBlank()) {
            RecoveryTrace.log("f7_chunkless_skipped", "why" to "no_session")
            return MetadataOnly.NotEmitted
        }
        val finalized = try {
            attribution.f7SessionFinalized(sessionId)
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("f7_chunkless_skipped", "why" to "gate_threw",
                "session" to sessionId.take(24))
            return MetadataOnly.NotEmitted
        }
        if (!finalized) {
            // The ordinary case on every live session, so it is NOT folded into
            // the emitter's own `Omit` vocabulary: "this session has not ended"
            // and "the emitter had nothing to say" are different facts, and a
            // reader of the trace must be able to tell them apart.
            RecoveryTrace.log("f7_chunkless_skipped", "why" to "not_finalized",
                "session" to sessionId.take(24))
            return MetadataOnly.NotEmitted
        }
        RecoveryTrace.log("f7_chunkless_finalization", "session" to sessionId.take(24))
        return emitMetadataOnlyCheckpoint(sessionId)
    }

    /**
 * The stamped sequences of the records in [slice] that PARSE and that
 * [session] OWNS — never a record attributed to [session] only because a
 * fallback stood in for an owner it could not read (SF-8: a permissive
 * upload address is not an authority key).
 */
    private fun recordSequencesOf(session: String, slice: List<ByteArray>): List<Long> =
        slice.mapNotNull { LossAuthority.RecordFields.parse(it) }.filter { it.sessionId == session }.map { it.sequence }

    // ONE READER (SF-7). Every `sequence` / `sessionId` read in this file
    // goes through `LossAuthority.RecordFields.parse` — the same reader the
    // facade reconciles with — so a record cannot be visible to the drain and
    // invisible to recovery, or the other way round.
    /** One record's own stamped sequence, or −1 when it cannot be read. */
    private fun recordSequence(b: ByteArray): Long =
        LossAuthority.RecordFields.parse(b)?.sequence ?: -1L

    /** One record's own string field, or null when it cannot be read. `sessionId` goes through the one reader. */
    private fun recordField(b: ByteArray, key: String): String? =
        if (key == "sessionId") LossAuthority.RecordFields.parse(b)?.sessionId
        else try { org.json.JSONObject(String(b, Charsets.UTF_8)).optString(key) } catch (_: Throwable) { null }

    private fun producerField(slice: List<ByteArray>, key: String): String = try {
        org.json.JSONObject(String(slice[0], Charsets.UTF_8)).optString(key).take(64)
    } catch (_: Throwable) { "" }

    /**
 * A permanent rejection removes the offending prefix and NOTHING else, and
 * never records it as acknowledged.
 *
 * F4-P1 (F4-15, ruling R4): the removal is a GOVERNED BRACKET, not a bare
 * `removeFirst`. Inside the pipeline's QUARANTINE region the indicted
 * prefix is adopted and re-checked, then handed to
 * `EvictionBracket.quarantineHead`, which runs the same PLAN → MARKER →
 * PREPARE → COMMIT → SETTLE as a byte eviction and settles every victim
 * `LOST(QUARANTINED)` only after the commit is PROVEN. The F2 disclosure
 * (`ADMITTED_ABSENT_UNDELIVERED`) that stood in for the terminal loss is
 * gone from this path: a quarantined record is now terminally attributed
 * or it is not removed. The victim counter fires ONLY on `Evicted`.
 */
    private fun quarantinePrefix(n: Int, batch: BatchIdentity, code: String?) {
        try {
            // ONE `QUARANTINE` REGION (Slice B, B4); the bracket nests its own
            // BRACKET region inside it (the region lock is reentrant per
            // thread — one acquisition), so adoption, the prefix re-check and
            // the whole bracket see ONE image.
            region(QueueRegion.QUARANTINE, refused = { q ->
                results.last = results.last?.copy(code = "queue_" + q.wire)
                Unit
            }) {
            // ADOPT BEFORE THE DESTRUCTIVE REMOVAL (store review R3): the
            // bracket's PREPARE needs an authority that OWNS the victims.
            if (!adoptedForRemoval(batch.sessionId, batch.chunkId)) {
                results.last = results.last?.copy(code = "removal_adoption_not_ready")
                return@region
            }
            val current = store.readRecords()
            if (current.size < n || !batch.prefixMatches(current.subList(0, n))) {
                results.last = results.last?.copy(code = "queue_prefix_changed")
                return@region
            }
            val going = current.subList(0, n).map { sha256Hex(it) }
            val r = quarantineBracket.quarantineHead(n, going, mutationGate, code)
            when (val o = r.outcome) {
                // THE ONE THAT MATTERS MOST FOR ACCOUNTING. `onQuarantined`
                // is a VICTIM COUNTER: it states that n records were destroyed
                // AND attributed. It fires only on the proven, settled commit.
                is EvictionBracket.Outcome.Evicted -> {
                    committedRemoval(going)
                    quarantine.onQuarantined(o.victims, code)
                }
                // The bytes are gone (commit proven) but at least one victim's
                // settlement is NOT durable: the marker + intents stay and
                // RECOVERY owns the completion, under the marker's cause. The
                // external callback and the victim count are downstream of the
                // DURABLE LOSS SETTLEMENT, not merely of the queue removal, so
                // both stay silent here; protection is not released either —
                // the next drain's trustworthy read proves the digests absent
                // (Slice B) once recovery has finalized. Typed, so the state
                // is named rather than looking like a quiet success.
                is EvictionBracket.Outcome.SettleIncomplete -> {
                    results.last = results.last?.copy(code = "quarantine_settle_incomplete")
                }
                // Nothing removed (typed refusal) — protection stays, counter silent.
                is EvictionBracket.Outcome.Refused -> {
                    RecoveryTrace.log("quarantine_refused", "code" to o.code.take(40), "detail" to (o.detail ?: ""))
                    results.last = results.last?.copy(code = o.code)
                }
                // The commit's outcome is unknowable: NOTHING is counted,
                // NOTHING is released; the marker blocks every later
                // mutation until recovery decides from durable state.
                is EvictionBracket.Outcome.Unknowable -> {
                    results.last = results.last?.copy(code = r.reason ?: "quarantine_unknowable")
                }
                is EvictionBracket.Outcome.Plain -> {
                    results.last = results.last?.copy(code = "quarantine_unexpected_plain")
                }
            }
            }
        } catch (t: Throwable) {
            if (t is SimulatedProcessDeath) throw t
            RecoveryTrace.log("quarantine_failed", "code" to t.javaClass.simpleName)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {

        // The producer-capability code set USED to live here. It moved to
        // the SHARED response policy (`transport/Transport.kt`
        // `terminalDisposition`) together with the session-precondition set,
        // because the question "which refusals may DELETE records?" must have
        // exactly one answer for every producer. A local copy here would be a
        // second authority over the thing that deletes customer data — the
        // defect class this file's own header warns about.

        /**
 * The longest ADMISSIBLE queue PREFIX owned by a single session, with
 * that session.
 *
 * Records serialise their own session id; a record that cannot state
 * one is attributed to [fallback] (the running session) rather than
 * dropped — an unreadable owner must not stall the queue behind it.
 *
 * THE PREFIX IS NOW BOUNDED, and that is the fix. It used to take
 * the LONGEST contiguous run with no size bound at all, so an offline
 * or outage accumulation produced one request as large as the queue —
 * and the deployed API answers a large enough body with 413, which
 * quarantined (deleted) the very records the accumulation existed to
 * save. The bound is on the ACTUAL encoded body, envelope included, and
 * on the record count; see [ChunkBounds].
 *
 * Ordering and contiguity are UNCHANGED. The slice is still a prefix,
 * still one session, still one ascending sequence run with no gap — the
 * bound only decides where that run is CUT, never which records go in
 * it or in what order. Multiple chunks per session were already
 * supported (the drain loop iterates slices), so a cut costs an extra
 * request and nothing else.
 */
        @JvmStatic
        @JvmOverloads
        fun sessionOwnedPrefix(
            records: List<ByteArray>,
            fallback: String,
            budgetBytes: Long = ChunkBounds.DEFAULT_BODY_BUDGET_BYTES,
            maxRecords: Int = ChunkBounds.MAX_RECORDS_PER_CHUNK,
            /**
 * **F6 — the bytes an F6 checkpoint will occupy in this body,
 * keyed by the OWNER this function derives.**
 *
 * IT IS APPLIED HERE AND NOWHERE ELSE, because this is the one
 * place the owning session is known BEFORE the size bound is
 * computed. Deriving the owner a second time at the call site would
 * give "who owns this slice" two homes that are free to disagree.
 *
 * `ChunkBounds.ENVELOPE_PUNCTUATION_BYTES` (41) IS NOT TOUCHED —
 * it describes the frozen three-key envelope. The reserve is
 * subtracted from the `budgetBytes` PARAMETER the bound function
 * already takes, and the existing `MIN_BODY_BUDGET_BYTES` floor is
 * respected: a reserve may shrink a chunk, never drive its budget to
 * nothing. Default `{ 0 }` keeps every existing caller byte-identical.
 */
            reserveFor: (String) -> Int = { 0 },
        ): Pair<String, List<ByteArray>> {
            // AN UNREADABLE HEAD IS ITS OWN SINGLE-RECORD SLICE (SF-8). The
            // fallback session is an UPLOAD ADDRESS for a record that cannot
            // state its owner — it is never a key the attribution authority
            // resolves a handle with, and a readable record behind it must not
            // be merged into a run under a session that is only a stand-in.
            // The slice is bounded to that one record so the queue still moves.
            val head = LossAuthority.RecordFields.parse(records[0])
                ?: return fallback to records.subList(0, 1)
            fun ownerOf(b: ByteArray): String? = LossAuthority.RecordFields.parse(b)?.sessionId
            fun seqOf(b: ByteArray): Long = LossAuthority.RecordFields.parse(b)?.sequence ?: -1L
            val owner = head.sessionId
            var n = 1
            var prev = head.sequence
            // One session AND one CONTIGUOUS ascending sequence run. The server
            // identifies a batch by its sequence RANGE and permanently refuses
            // a gapped or duplicated one (`batch_sequence_not_contiguous`) —
            // and a session touched by more than one PROCESS has restarted
            // counters, so a whole-store batch can never be contiguous. Found
            // live: the recovery drain of a session with crash-process records
            // was refused and the refusal quarantined the crash evidence it
            // existed to deliver.
            while (n < records.size &&
                ownerOf(records[n]) == owner &&
                seqOf(records[n]) == prev + 1
            ) { prev = seqOf(records[n]); n++ }
            val run = records.subList(0, n)
            // The SIZE bound is applied to the contiguous run, never before it:
            // cutting for size first could make the remaining head start at a
            // sequence the server reads as a gap. Contiguity decides WHAT may
            // be in a batch; the bound decides only HOW MUCH of it fits.
            // F6 the specification: the checkpoint's ACTUAL cost is reserved BEFORE the bound is
            // applied, so a chunk is only ever sized with room for the document
            // it will carry. A reserve that would push the budget below the
            // existing floor is clamped to the floor rather than refused — the
            // emitter's own `fit` check then omits the checkpoint, which is the
            // ruled behaviour.
            val reserve = try { reserveFor(owner).toLong() } catch (_: Throwable) { 0L }
            // THE FLOOR MAY NEVER *RAISE* THE BUDGET, AND THE FIRST RUN
            // CAUGHT IT DOING EXACTLY THAT. `maxOf(MIN_BODY_BUDGET_BYTES, …)`
            // on a budget already BELOW the 64 KiB floor — which is every
            // re-sliced budget and every small test budget — handed the bound
            // function a LARGER budget than it was given, so reserving bytes
            // admitted MORE records (`5 -> 8`). The floor is a limit on how far
            // a reserve may shrink a budget, not a minimum to grow it to.
            // AND `min(budget, MIN)` CANCELLED THE RESERVE ENTIRELY ON EVERY
            // BUDGET BELOW THE FLOOR — the second thing this row caught
            // (`5 -> 5`). `MIN_BODY_BUDGET_BYTES` is *"the floor a repeated 413
            // may shrink the byte budget to"*; it is only meaningful for a
            // budget that STARTED above it. Below it, the only real floor is
            // "enough for the head", and `boundedCount` already guarantees that
            // by ALWAYS admitting the head whatever it costs.
            val budgetAfterReserve = when {
                reserve <= 0L -> budgetBytes
                budgetBytes > ChunkBounds.MIN_BODY_BUDGET_BYTES ->
                    maxOf(ChunkBounds.MIN_BODY_BUDGET_BYTES, budgetBytes - reserve)
                else -> maxOf(1L, budgetBytes - reserve)
            }
            val admitted = ChunkBounds.boundedCount(
                sessionIdUtf8Len = owner.toByteArray(Charsets.UTF_8).size,
                recordSizes = run.map { it.size },
                budgetBytes = budgetAfterReserve,
                maxRecords = maxRecords,
            )
            // THE SAME DEFENSIVE CLAMP THE SWIFT TWIN CARRIES, and the
            // asymmetry was found by a negative control rather than by reading.
            // `boundedCount` returns 0 only for an EMPTY list, which cannot
            // reach here — but with the clamp absent, a `boundedCount` that
            // ever returned 0 would hand the pipeline a ZERO-RECORD batch,
            // while Swift's prefix builder would quietly keep working. Two
            // producers that fail differently under the same fault is worse
            // than either failure.
            return owner to run.subList(0, maxOf(1, admitted).coerceAtMost(run.size))
        }
    }

    /**
 * The deterministic identity of one submitted batch.
 *
 * It must survive a retry, a process death and a network loss, so it is
 * derived from the record BYTES rather than from a counter or a clock —
 * the same batch always produces the same id, and a different batch never
 * does.
 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    class BatchIdentity private constructor(
        val sessionId: String,
        val chunkId: String,
        val firstSequence: String,
        val lastSequence: String,
        val recordCount: Int,
        private val digests: List<String>,
    ) {
        /** Every field the server echoed must equal what we sent. */
        fun matches(a: Uploader.Ack): Boolean =
            a.sessionId == sessionId &&
                a.chunkId == chunkId &&
                a.firstSequence == firstSequence &&
                a.lastSequence == lastSequence &&
                a.recordCount == recordCount

        fun prefixMatches(current: List<ByteArray>): Boolean {
            if (current.size != digests.size) return false
            for (i in digests.indices) if (sha256(current[i]) != digests[i]) return false
            return true
        }

        companion object {
            private fun sha256(b: ByteArray): String =
                java.security.MessageDigest.getInstance("SHA-256").digest(b)
                    .joinToString("") { "%02x".format(it) }

            // The ONE reader (SF-7): the identity's range uses the same numeric
            // rule recovery uses, so the two cannot disagree about a record's
            // number. Owner-independent here: the RANGE of a batch is a fact
            // about its records' numbers, whoever owns them.
            private fun seqOf(b: ByteArray): Long = LossAuthority.RecordFields.sequenceOf(b) ?: -1L

            @JvmStatic
            fun of(sessionId: String, records: List<ByteArray>): BatchIdentity {
                val digests = records.map { sha256(it) }
                val seqs = records.map { seqOf(it) }.filter { it >= 0 }
                // Content-addressed over the batch, so a retry of the same
                // bytes is the same chunk and the server can answer `duplicate`
                // instead of storing a second copy. 32 hex chars satisfies the
                // canonical grammar the API REQUIRES — a malformed id is a
                // permanent refusal rather than a silent server-side
                // substitution.
                val basis = sessionId + "|" + digests.joinToString("|")
                val chunkId = sha256(basis.toByteArray(Charsets.UTF_8)).substring(0, 32)
                return BatchIdentity(
                    sessionId = sessionId,
                    chunkId = chunkId,
                    firstSequence = (seqs.minOrNull() ?: 0L).toString(),
                    lastSequence = (seqs.maxOrNull() ?: 0L).toString(),
                    recordCount = records.size,
                    digests = digests,
                )
            }
        }
    }
}

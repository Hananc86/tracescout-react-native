package com.tracescout.mobile

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import com.tracescout.mobile.capture.CrashHandler
import com.tracescout.mobile.capture.ViewHierarchySnapshotter
import com.tracescout.mobile.core.Clock
import com.tracescout.mobile.core.Ids
import com.tracescout.mobile.core.SystemClock
import com.tracescout.mobile.emit.Envelope
import com.tracescout.mobile.privacy.Posture
import com.tracescout.mobile.privacy.PrivacyLevel
import com.tracescout.mobile.internal.queue.DurableUploadPipeline
import com.tracescout.mobile.queue.EncryptedEventStore
import com.tracescout.mobile.queue.RecoveryCoordinator
import com.tracescout.mobile.queue.RecoveryTrace
import com.tracescout.mobile.queue.RecoveryWorker
import com.tracescout.mobile.queue.StoreTeardown
import com.tracescout.mobile.queue.EvictionResult
import com.tracescout.mobile.queue.QueuedEvent
import com.tracescout.mobile.queue.evictToFit
import com.tracescout.mobile.transport.ResponseAction
import com.tracescout.mobile.transport.Uploader
import com.tracescout.mobile.transport.backoffMillis
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The internal Android capture SDK — the single object a fixture app (and, one
 * day, a customer app) talks to.
 *
 * INTERNAL AND UNPUBLISHED. There is no `maven-publish` in this module's build
 * and no customer Android SDK is released during M0.
 *
 * ARCHITECTURE, AND WHY:
 *
 * - **Capture is synchronous on the caller's thread; everything else is not.**
 * A view hierarchy can only be read on the UI thread, so the walk happens
 * there under a hard millisecond budget. Serialisation, encryption,
 * persistence and upload all move to a single background executor. One
 * executor, not a pool: uploads must stay ordered, and a pool would let a
 * retry of chunk N race ahead of chunk N+1.
 *
 * - **The queue is the source of truth, not the in-memory list.** Every record
 * is persisted (encrypted, Keystore-backed) BEFORE any upload is attempted,
 * so a process death between capture and upload loses nothing. This is what
 * makes crash capture work at all: the crash record is appended by a dying
 * process and uploaded by the next launch.
 *
 * - **Nothing here throws into the host app.** Every public entry point is
 * wrapped. An observability SDK that crashes its host has done more damage
 * than the bug it was installed to find, so a failure inside TraceScout
 * degrades TraceScout and nothing else.
 */
object TraceScoutMobile {

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    data class Config(
        val projectId: String,
        val ingestKey: String,
        val baseUrl: String = "https://api.tracescout.com",
        val environment: String = "production",
        /**
 * The queue's hard ceiling. Eviction is by priority and DISCLOSED —
 * a dropped event increments a counter that rides the next envelope's
 * `dropped` block, because the backend treats an unexplained sequence
 * gap as `incomplete` and refuses to share such a session.
 */
        val maxQueuedEvents: Int = 500,
        val maxRetries: Int = 4,
        /**
 * `semantic` (default) or `semantic_with_frames`. Frames are OPT-IN:
 * the semantic hierarchy is the authoritative replay representation and
 * is sufficient on its own, so rasterizing is an explicit choice with
 * its own cost and its own privacy gate.
 */
        val captureMode: String = "semantic",
    )

    @Volatile private var config: Config? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var identity: Envelope.SessionIdentity? = null
    @Volatile private var store: EncryptedEventStore? = null
    /** The queue home the store belongs to — the identity the pipeline is handed (Slice B). */
    @Volatile private var home: com.tracescout.mobile.queue.QueueHome? = null

    /**
 * F4-P0 Slice C — the ONE governed append home. `EncryptedEventStore.append`
 * refuses at capacity and makes no room; the bracket is where room is made,
 * and both producers reach it. Constructed with the home, over the same
 * memoized queue, wired to the production eviction-attribution seam.
 */
    @Volatile private var bracket: com.tracescout.mobile.internal.queue.EvictionBracket? = null

    /**
 * F4-P0 Slice D — what the last open-time (or pre-drain) eviction recovery
 * concluded, as a bounded typed string (`NotNeeded`, `Resolved:<lost>:<nh>`,
 * `Blocked:<reason>`). Diagnostics only; never a decision input — the
 * decision is the marker on disk, which every gate reads for itself.
 */
    @Volatile
    @JvmStatic
    var lastStorageRecovery: String? = null
        private set

    /** The ONE durable in-flight descriptor file — the pipeline's and the reconciler's are the same store. */
    private fun inflightStoreOf(app: android.content.Context): com.tracescout.mobile.internal.queue.InFlightStore =
        com.tracescout.mobile.internal.queue.InFlightStore.file(java.io.File(app.filesDir, "tracescout-inflight.json"))

    /** The recovery driver over the live home: the production seam, the production scan. */
    private fun recoveryFor(h: com.tracescout.mobile.queue.QueueHome, filesDir: java.io.File) =
        com.tracescout.mobile.internal.queue.EvictionRecovery(
            h, com.tracescout.mobile.internal.queue.EvictionAttribution.forFilesDir(filesDir),
            { com.tracescout.mobile.internal.loss.LossAuthority.scanUnresolvedEvictionsAtOpen(filesDir) },
        )

    /**
 * The erasure flow's home factory — the SAME construction `start` uses
 * (the canonical queue-key store over the canonical file), for whichever
 * queue name the flow needs. The queue key is resolved HERE, in the
 * producer, exactly as the specification pins it; the flow never names it.
 */
    private fun erasureHomeOf(filesDir: java.io.File): (String) -> com.tracescout.mobile.queue.QueueHome = { name ->
        com.tracescout.mobile.queue.QueueHome.of(java.io.File(filesDir, name)) { queueFile ->
            EncryptedEventStore(queueFile, com.tracescout.mobile.queue.StoreKeys.queueKey())
        }
    }

    private fun recordRecovery(o: com.tracescout.mobile.internal.queue.EvictionRecovery.Outcome) {
        lastStorageRecovery = when (o) {
            is com.tracescout.mobile.internal.queue.EvictionRecovery.Outcome.NotNeeded -> "NotNeeded"
            is com.tracescout.mobile.internal.queue.EvictionRecovery.Outcome.Resolved -> "Resolved:${o.lost}:${o.didNotHappen}"
            is com.tracescout.mobile.internal.queue.EvictionRecovery.Outcome.Blocked -> "Blocked:" + o.reason.take(48)
        }
    }
    @Volatile private var uploader: Uploader? = null
    @Volatile private var clock: Clock = SystemClock

    private val started = AtomicBoolean(false)
    /**
 * F2 — THE ONE SEQUENCE AUTHORITY. There is no local counter: every
 * sequence this producer stamps was first claimed durably through
 * [com.tracescout.mobile.internal.loss.LossAuthority] (an `AdmissionIntent`
 * on disk BEFORE the record is built), and every claim is settled by the
 * worker as ADMITTED (the bytes reached the queue) or LOST
 * (`APPEND_REFUSED`). Numbers survive process death, are never reused
 * after a loss, and a corrupted authority disables the SDK for the session
 * rather than falling back to a counter that would silently disagree.
 */
    @Volatile private var authority: com.tracescout.mobile.internal.loss.LossAuthority.Handle? = null
    /** Claims awaiting settlement, keyed by sequence. Bounded by the worker draining them in order. */
    private val tickets = java.util.concurrent.ConcurrentHashMap<Long, com.tracescout.mobile.internal.loss.LossAuthority.Ticket>()
    private val pending = ArrayList<Envelope.Record>()
    /**
 * **`droppedCounts` / `snapshotDropped` MOVED TO F6 — THEY ARE NOT
 * HERE ANY MORE, AND THE MOVE IS DELIBERATE.**
 *
 * a product decision gave F6 *"the checkpoint-specific dropped carrier/block"* and named
 * that pair as its surface, adding that *"widening or repurposing it is
 * F6's to do, not PRE-F6's."* F6 took it — and had to MOVE it rather than
 * use it in place, because `mobile-android/rn/src/main` holds ZERO
 * references to this class, so a carrier private to `TraceScoutMobile`
 * covers the Android Native producer and silently skips RN Android. The one
 * F6 orchestration path required by `an explicit product decision` the specification lives in
 * `internal/loss/F6CheckpointEmitter`, which both producers reach through
 * the `AttributionSink` they already wire, and the carrier lives with it.
 *
 * There is exactly ONE `droppedCounts` in the tree, under the same name, so
 * the `F6_DROPPED_WIRE_WRITER` pin now fires on the real subject instead of
 * being vacuous.
 *
 * [preF6DroppedFamilies] BELOW IS UNCHANGED AND STAYS HERE. It is the
 * ordinary-envelope disclosure PRE-F6 owns, it has a live reader, and a product decision
 * forbids merging the two representations.
 */

    /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5; a product decision as clarified by a product decision) —
 * the SESSION-SCOPED accumulator of canonical loss families from
 * queue-pressure eviction, and the per-record IN-FLIGHT disclosure.
 *
 * DELIBERATELY NOT `droppedCounts`. That field is the F6 checkpoint
 * stage's, its write forms are the `F6_DROPPED_WIRE_WRITER` stage-leak
 * pin, and a PRE-F6 change must leave that pin at ZERO. This is a
 * separate accumulator with a LIVE READER on the ordinary-envelope path —
 * which is the whole point: a correctly-keyed count that nothing reads is
 * the defect a product decision rejected, one layer up.
 *
 * MOVED, NOT COPIED, AT BUILD TIME. [takePreF6Dropped] removes the
 * outstanding counts from [preF6DroppedFamilies] and hands them to the
 * envelope being built, so a second envelope built before the first
 * commits sees nothing and CANNOT carry the same counts. A copying
 * snapshot would disclose the same loss twice.
 *
 * NON-DURABLE ACROSS PROCESS DEATH, BY DECISION. G5 is an in-memory
 * PRE-STORE eviction: no sequence is allocated, so there is no durable
 * victim and no settlement, and the frozen GAP_MATRIX/C5 rows ask
 * for a canonical FAMILY, never for persistence. The disclosure this
 * replaces was already in-memory (`droppedCounts` is a plain field cleared
 * on teardown), so this is the existing architecture of the surface, not a
 * new weakening. Making it durable would mean creating authority state,
 * which a product decision forbids PRE-F6 for G5.
 */
    private val preF6DroppedFamilies = HashMap<String, Int>()
    private val preF6DroppedInFlight = HashMap<String, Map<String, Int>>()
    private val lock = Any()

    /** Single-threaded on purpose — see the class comment on ordering. */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tracescout-mobile").apply {
            isDaemon = true
            // Below default so capture work can never outrank the app's own
            // threads. An SDK is a guest.
            priority = Thread.MIN_PRIORITY + 2
        }
    }

    /** Session posture — the WORST of every contributing segment, never a claim. */
    @Volatile private var nativePosture = Posture(
        level = PrivacyLevel.STRICT, maskText = true, maskInputs = true,
        maskAccessibilityLabels = true, blockImages = true, blockCameraSurfaces = true,
    )
    private val segmentPostures = ArrayList<Posture>()

    // ── THE ROUTE-HOST INTEGRATION ─────────────────────────────────────────
    // NULL UNTIL AN APPLICATION INTEGRATES. An app that never declares a host
    // gets `RouteHost.UNKNOWN` for every attempt and can never emit a
    // capture-unavailable reason — byte-for-byte the behaviour it has today.
    // That is a structural property (there is nothing to ask), not a flag.
    @Volatile private var routeHosts: com.tracescout.mobile.capture.RouteHostRegistry? = null
    @Volatile private var attachDeadline: com.tracescout.mobile.capture.AttachDeadline? = null
    @Volatile private var attachBudgetMs: Long =
        com.tracescout.mobile.capture.RouteHostRegistry.DEFAULT_ATTACH_BUDGET_MS
    private val pendingOutcomes =
        java.util.concurrent.ConcurrentLinkedQueue<Pair<com.tracescout.mobile.capture.RouteHostRegistry.Attempt, String>>()

    /** The bounded wire vocabulary; anything else is not a status we can store. */
    private val BROWSER_PRIVACY_STATUSES = setOf("enabled", "partial", "disabled", "unknown")

    /**
 * The WORST browser-side status seen so far, in wire terms.
 *
 * Worst-wins for the same reason posture is worst-wins: a segment that
 * reported `partial` cannot be made `enabled` by a later frame saying so.
 */
    private var lastBrowserPrivacyStatus: String? = null

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
 * Start a session. Idempotent — a second call is a no-op rather than a
 * second session, because two sessions from one launch would double-count
 * every metric derived from session counts.
 */
    @JvmStatic
    @JvmOverloads
    fun start(
        context: Context,
        cfg: Config,
        initialScreen: String = "unknown",
        sessionId: String? = null,
    ): String? = guard("start") {
        if (!started.compareAndSet(false, true)) return@guard identity?.sessionId

        val app = context.applicationContext
        appContext = app
        val sid = sessionId ?: Ids.newSessionId(clock)
        val pkg = app.packageName
        val pInfo = try {
            app.packageManager.getPackageInfo(pkg, 0)
        } catch (t: Throwable) { null }

        val metrics = app.resources.displayMetrics
        config = cfg
        identity = Envelope.SessionIdentity(
            projectId = cfg.projectId,
            sessionId = sid,
            // The anonymous id is derived per INSTALL and carries no device or
            // advertising identifier. It is a correlation handle across sessions
            // of the same install, nothing more.
            anonymousId = installId(app),
            environment = cfg.environment,
            appVersion = pInfo?.versionName ?: "0.0.0",
            buildNumber = (
                if (android.os.Build.VERSION.SDK_INT >= 28) pInfo?.longVersionCode
                else @Suppress("DEPRECATION") pInfo?.versionCode?.toLong()
                )?.toString() ?: "0",
            appIdentifier = pkg,
            initialScreen = initialScreen,
            captureMode = cfg.captureMode,
            device = Envelope.Device(
                manufacturer = android.os.Build.MANUFACTURER ?: "unknown",
                model = android.os.Build.MODEL ?: "unknown",
                osVersion = android.os.Build.VERSION.RELEASE ?: "unknown",
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                density = metrics.density.toDouble(),
            ),
        )
        RecoveryTrace.install(app)
        RecoveryTrace.log("start_enter")
        // F4-P0 Slice D (D6, U-2/U-3): AN OUTSTANDING ERASURE IS RESUMED
        // BEFORE ANY QUEUE IS OPENED AND BEFORE ANY ADMISSION. A request the
        // previous process left on disk means this install is being erased;
        // normal capture, drain and bracket all refuse until the governed flow
        // completes under the fence. Pending (another owner holds the fence,
        // or the terminal transition could not be made durable) is a TYPED
        // refusal of this start — nothing is destroyed, and the latch is
        // released so a later start may resume once the holder is gone
        // (X-10: that is a platform operation, never a bypass).
        when (val e = com.tracescout.mobile.queue.ErasureFlow.resumeAtOpen(app.filesDir, erasureHomeOf(app.filesDir))) {
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Pending -> {
                RecoveryTrace.log("start_refused_erasure", "why" to e.reason.take(40))
                started.set(false)
                return@guard null
            }
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Incomplete -> {
                RecoveryTrace.log("start_refused_erasure", "why" to ("incomplete:" + e.reason).take(40))
                started.set(false)
                return@guard null
            }
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Completed ->
                RecoveryTrace.log("erasure_completed_at_open", "terminal" to e.terminalTeardown)
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.NotRequested -> Unit
        }
        // ONE QUEUE HOME PER LOGICAL QUEUE (Slice B, B4). The home is
        // memoized on the canonical queue file, owns the store, both lock
        // layers and the durable delivery-protection state, and is what the
        // pipeline is handed — never a bare store. The factory below is called
        // at most once per process for this path.
        val h = com.tracescout.mobile.queue.QueueHome.of(
            // THE NAME COMES FROM THE CANONICAL INVENTORY, NEVER A LITERAL HERE.
            // The teardown clears every queue-key-backed file by that inventory; a
            // literal at the construction site is how a store silently stops being
            // covered by the cleanup and stalls for ever after an erasure.
            java.io.File(app.filesDir, com.tracescout.mobile.queue.QueueKeyStores.SDK_QUEUE),
        ) { queueFile ->
            EncryptedEventStore(
                queueFile,
                // Keystore-backed, non-exportable. If the key cannot be obtained
                // this THROWS and `guard` turns it into a disabled SDK — never a
                // silent fallback to plaintext, which would look like encryption
                // to every reader of this code.
                com.tracescout.mobile.queue.StoreKeys.queueKey(),
            )
        }
        home = h
        store = h.store()
        bracket = com.tracescout.mobile.internal.queue.EvictionBracket(
            h, com.tracescout.mobile.internal.queue.EvictionAttribution.forFilesDir(app.filesDir),
        )
        parkStoreDir = app.filesDir
        // F2: open the durable sequence authority for THIS session before the
        // first allocation. The queue snapshot lets the first open adopt a
        // pre-F2 queue (or reconcile a restarted one). An unavailable
        // authority THROWS — `guard` turns that into a disabled SDK, exactly
        // as an unobtainable Keystore key does; a local counter fallback would
        // be two authorities that disagree once, silently, months later.
        run {
            val rr = store!!.readAll()
            authority = when (val o = com.tracescout.mobile.internal.loss.LossAuthority.open(
                app.filesDir, sid,
                com.tracescout.mobile.internal.loss.LossAuthority.QueueSnapshot(rr.records, rr.isTrustworthy),
            )) {
                is com.tracescout.mobile.internal.loss.LossAuthority.Open.Ok -> o.handle
                is com.tracescout.mobile.internal.loss.LossAuthority.Open.Unavailable ->
                    throw IllegalStateException("loss_authority_unavailable:" + o.reason)
            }
        }
        // F4-P0 Slice D (D1): OPEN-TIME RECOVERY of a bracket a previous
        // process left unresolved — after this session's authority is open and
        // reconciled (so the removal door short-circuits on it), BEFORE the
        // first admission and before the inherited drain. Idempotent: a clean
        // queue reads one absent file; a resolved bracket is removed; a
        // bracket that cannot be concluded stays, and every gate keeps
        // refusing typed until a later open concludes it.
        recordRecovery(recoveryFor(h, app.filesDir).recover())
        // F4-P2 (F4-16 / F4-17): reconcile a LOSSY queue — AFTER the bracket
        // recovery (an absence an intent explains is the bracket's), BEFORE the
        // first admission. A PREPARED-and-absent entry under a lossy image is
        // never settled (R9/Q3). Blocked keeps every fence; the drain refuses typed on its own.
        when (val q = com.tracescout.mobile.internal.queue.QueueReconciler(h, app.filesDir, inflightStoreOf(app)).reconcile()) {
            is com.tracescout.mobile.internal.queue.QueueReconciler.Outcome.Blocked ->
                RecoveryTrace.log("start_reconcile_blocked", "why" to q.reason.take(40), "settled" to q.settled)
            is com.tracescout.mobile.internal.queue.QueueReconciler.Outcome.Resolved ->
                RecoveryTrace.log("start_reconciled", "unreadable" to q.settledUnreadable, "disabled" to q.disabled,
                    "cleared" to q.cleared, "consumed" to q.consumed)
            is com.tracescout.mobile.internal.queue.QueueReconciler.Outcome.NotNeeded -> Unit
        }
        uploader = Uploader(cfg.baseUrl, cfg.ingestKey)
        RecoveryCoordinator.processGeneration(app)
        // The deferred drain runs the SAME state machine — snapshot a prefix,
        // validate the ack, remove only what was acknowledged. There is no
        // second upload path to keep in step.
        RecoveryWorker.drainHook = { ctx ->
            if (!RecoveryCoordinator.acquire(ctx)) RecoveryWorker.Outcome.RETRY
            else try {
                val recs = store?.readAll()?.records ?: emptyList()
                // F7 R-2 (#2760): drain first, then classify — an EMPTY drain is
                // the one moment no chunk can carry a disclosure, and the
                // pipeline owns that decision. (Kept short: the hook is inside a
                // fixed-width slice in RecoveryCoordinatorTest.)
                uploadRaw(recs)
                if (recs.isEmpty()) RecoveryWorker.Outcome.DONE
                else {
                    val left = store?.readAll()?.records?.size ?: 0
                    when {
                        left == 0 -> RecoveryWorker.Outcome.DONE
                        lastResult?.action == com.tracescout.mobile.transport.ResponseAction.TERMINAL ->
                            RecoveryWorker.Outcome.PERMANENT
                        else -> RecoveryWorker.Outcome.RETRY
                    }
                }
            } finally { RecoveryCoordinator.release(ctx) }
        }

        installCrashHandler()
        // AUTOMATIC, SDK-OWNED. Durable recovery is a product responsibility;
        // it must not require a host call. It runs BEFORE the new session's
        // first event so inherited records keep their original position in the
        // queue, and it never rewrites them into the new session — their
        // envelopes already carry their own session id, sequence and time.
        recoverInherited(app)
        RecoveryTrace.log("start_continues")
        enqueueDisclosing { d ->
            Envelope.sessionStart(identity!!, admit("mobile_session_start"), clock.monotonicNanos(),
                posture(), clock = clock, dropped = d)
        }
        sid
    }

    /**
 * Drain anything an EARLIER PROCESS left behind.
 *
 * Called from `start` — never by the host. The previous design made this
 * the host's decision and then nothing ever called it, so records survived
 * process death and were never re-sent: durable storage with no path out.
 * The metered-connection concern that justified deferring to the host is
 * answered by [RecoveryWorker]'s network constraint, not by declining to
 * run.
 *
 * Inherited records are re-uploaded VERBATIM. Re-serialising would mint new
 * event ids and defeat the backend's dedup, turning one crash into two.
 */
    private fun recoverInherited(app: android.content.Context) {
        val s = store ?: return
        // The lease is what stops a foreground start and the WorkManager drain
        // from owning the same prefix at once.
        RecoveryTrace.log("recovery_enter")
        if (!RecoveryCoordinator.acquire(app)) {
            RecoveryTrace.log("lease_refused")
            RecoveryCoordinator.status = RecoveryCoordinator.Status.RECOVERY_IN_PROGRESS
            return
        }
        RecoveryTrace.log("lease_acquired")
        try {
            val res = try { s.readAll() } catch (t: Throwable) {
                RecoveryTrace.log("queue_read_failed", "code" to t.javaClass.simpleName)
                null
            }
            val records = res?.records ?: emptyList()
            RecoveryTrace.log("queue_opened", "count" to records.size)
            if (records.isEmpty()) {
                RecoveryTrace.log("queue_empty")
                RecoveryCoordinator.status = RecoveryCoordinator.Status.RECOVERY_NOT_NEEDED
                // F7 R-2: THE case the session-end seam's own KDoc describes —
                // a host that declared the session over and was then killed
                // emits the checkpoint on the next launch. Asked while this
                // process still holds the recovery lease.
                uploadRaw(records)
                // Never leave a drain scheduled for an empty queue.
                RecoveryWorker.cancel(app)
                // RELEASE ON THIS PATH TOO. The non-empty path hands the lease
                // to the worker task (released in its finally); this early
                // return used to keep it, so a start that found nothing to
                // recover left a FRESH lease behind — and the next process's
                // recovery, the one with records to save, was refused for the
                // full staleness window. A lease held by nobody is exactly the
                // bug the staleness bound exists for; not creating it is
                // better than waiting it out.
                RecoveryCoordinator.release(app)
                return
            }
            RecoveryTrace.log("queue_pending", "count" to records.size)
            RecoveryTrace.log("inherited_records_selected", "count" to records.size)
            RecoveryCoordinator.status = RecoveryCoordinator.Status.RECOVERY_IN_PROGRESS
            worker.execute {
                try {
                    uploadRaw(records)
                    val left = try { s.readAll().records.size } catch (_: Throwable) { -1 }
                    RecoveryCoordinator.status = when {
                        left == 0 -> RecoveryCoordinator.Status.RECOVERY_COMPLETED
                        // Checked BEFORE the general TERMINAL case: an auth
                        // rejection is terminal for the CREDENTIAL, and the
                        // records are still queued rather than quarantined.
                        lastResult?.code == "auth_rejected" ->
                            RecoveryCoordinator.Status.RECOVERY_AUTH_REJECTED
                        lastResult?.action == com.tracescout.mobile.transport.ResponseAction.TERMINAL ->
                            RecoveryCoordinator.Status.RECOVERY_PERMANENT_REJECTION
                        // A transport error is the no-network case in practice;
                        // both defer to the constrained worker rather than
                        // spinning here and draining the battery.
                        lastResult?.code == "transport_error" ->
                            RecoveryCoordinator.Status.RECOVERY_PENDING_NETWORK
                        else -> RecoveryCoordinator.Status.RECOVERY_RETRIABLE_FAILURE
                    }
                    RecoveryTrace.log("recovery_exit", "left" to left,
                        "status" to RecoveryCoordinator.status.name)
                    if (left != 0) {
                        val q = RecoveryWorker.enqueue(app)
                        RecoveryTrace.log(if (q) "worker_enqueued" else "worker_enqueue_failed")
                    } else RecoveryWorker.cancel(app)
                } finally {
                    RecoveryCoordinator.release(app)
                }
            }
        } catch (_: Throwable) {
            RecoveryCoordinator.release(app)
        }
    }

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    /**
 * Capture one semantic keyframe of a view tree.
 *
 * MUST be called on the UI thread — reading a View off the main thread is
 * undefined behaviour on Android, and a snapshot taken mid-layout would be
 * a tree that never existed. The walk is budget-bounded; whatever it could
 * not reach is COUNTED and disclosed, never silently omitted.
 */
    /**
 * ENABLE ROUTE-HOST REPORTING for this application.
 *
 * OPT-IN, AND THE OPT-IN IS THE INTEGRATION. Until an app calls this
 * there is no registry to ask, so no capture-unavailable reason can be
 * produced by any path. Calling it does not by itself change anything
 * either: the app must then DECLARE hosts, and a route with no declared
 * host still yields nothing until its budget expires.
 *
 * @param budgetMs the attach budget. Passed, never guessed — the deadline
 * and the registry must measure the SAME number, or they
 * would disagree about what "the budget" means.
 */
    /**
 * TEST SUPPORT ONLY — NOT A PRODUCT API, AND DELIBERATELY NOT ONE.
 *
 * Route-host reporting is opt-in and, once on, is the app's declared
 * integration; a public "turn it off" would let a route silently stop being
 * ownership-checked, which is the property the whole contract rests on.
 * This exists so instrumented tests can return the process to the
 * unintegrated baseline between cases. `internal` keeps it out of the
 * published surface; androidTest is a friend of main, so tests can reach it
 * and applications cannot.
 */
    @JvmStatic
    internal fun resetRouteHostReportingForTest() {
        attachDeadline?.cancel()
        routeHosts = null
        attachDeadline = null
        pendingOutcomes.clear()
        synchronized(captureRootLock) { captureRoots.clear() }
    }

    @JvmStatic
    @JvmOverloads
    fun enableRouteHostReporting(
        budgetMs: Long = com.tracescout.mobile.capture.RouteHostRegistry.DEFAULT_ATTACH_BUDGET_MS,
    ) {
        // THE PRODUCTION MONOTONIC CLOCK. `elapsedRealtime` counts since
        // boot INCLUDING deep sleep and cannot be moved by an NTP step or by the
        // user changing the date — a budget measured on `currentTimeMillis`
        // could be "expired" by a time change the app had nothing to do with.
        routeHosts = com.tracescout.mobile.capture.RouteHostRegistry(budgetMs) {
            android.os.SystemClock.elapsedRealtime()
        }
        // THE PRODUCTION DEADLINE OWNER — a Handler on the MAIN looper, which
        // is also where capture runs, so the callback cannot race the view
        // hierarchy. This is what makes the wait bounded by the BUDGET rather
        // than by whenever the app happens to capture next.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        attachDeadline = com.tracescout.mobile.capture.AttachDeadline(
            object : com.tracescout.mobile.capture.AttachDeadline.Scheduler {
                override fun schedule(delayMs: Long, task: () -> Unit): Any {
                    val r = Runnable { task() }; h.postDelayed(r, delayMs); return r
                }
                override fun cancel(handle: Any) { h.removeCallbacks(handle as Runnable) }
            }
        )
        attachBudgetMs = budgetMs
    }

    /** A host for [screenId] has attached. No readiness contract is implied. */
    @JvmStatic
    /**
 * @param root the view this host owns — its CAPTURE ROOT.
 *
 * READINESS AND OWNERSHIP ARE SEPARATE DECLARATIONS, AND THIS IS THE
 * ONE THAT AUTHORIZES A CAPTURE. Passing `null` declares a host that
 * participates on the outcome axis and authorizes NO capture — a real and
 * legitimate integration, not a degraded one. Without a root there is
 * nothing to distinguish this host's content from whatever else the window
 * happens to be showing.
 *
 * HELD WEAKLY. A registry that strongly retained an app's view would
 * leak an entire screen for as long as the route stayed registered.
 */
    @JvmOverloads
    fun hostAttached(screenId: String, hostId: String, root: View? = null): Boolean {
        val r = routeHosts ?: return false
        r.hostAttached(screenId, hostId, root?.let { registerCaptureRoot(it) })
        return true
    }

    // ── PLATFORM ROOT RESOLUTION (standalone Android) ───────────────────────
    //
    // THIS IS THE PART SHARED-REGISTRY PARITY DOES NOT PROVE. The registry
    // is byte-identical to the RN twin and a parity test enforces that, but a
    // registry only stores an opaque handle. Turning a handle back into a live
    // view, and deciding whether that view may be captured, is per-platform:
    // RN Android resolves a react tag through the view tree, and this platform
    // resolves a handle the SDK itself minted for a caller-supplied View.
    // Neither is evidence for the other.
    private val captureRootLock = Any()
    private var captureRootSeq = 0
    private val captureRoots = java.util.HashMap<Int, java.lang.ref.WeakReference<View>>()

    private fun registerCaptureRoot(v: View): Int = synchronized(captureRootLock) {
        // Reuse the handle for a view already registered, so re-declaring the
        // same host with the same view is not read as a re-binding.
        for ((h, ref) in captureRoots) if (ref.get() === v) return@synchronized h
        captureRoots.entries.removeAll { it.value.get() == null }   // collected views
        val h = ++captureRootSeq
        captureRoots[h] = java.lang.ref.WeakReference(v)
        h
    }

    /**
 * May this View be captured as `screenId`'s content?
 *
 * IDENTITY, NOT RESEMBLANCE. The supplied view must BE the view the
 * route's host registered — same object — and it must still be attached.
 * Comparing bounds or class would let a different view of the same shape
 * pass, which is precisely the case that produces silently mislabelled
 * content.
 */
    private fun verifyCaptureRoot(screenId: String, root: View): Boolean {
        val r = routeHosts ?: return true          // unintegrated: unchanged
        val bound = r.boundRoot(screenId) ?: run { ownershipRefusals++; return false }
        val v = resolveCaptureRoot(bound.rootHandle) ?: run { ownershipRefusals++; return false }
        if (v !== root) { ownershipRefusals++; return false }
        if (!v.isAttachedToWindow) { ownershipRefusals++; return false }
        return true
    }

    /** Observability only — never a protocol reason and never on the wire. */
    /** Diagnostics for tests: what is actually queued. Never a decision input. */
    @JvmStatic fun pendingOutcomeCount(): Int = pendingOutcomes.size
    /** Observability: attempts dropped without emitting, never silently. */
    @JvmStatic @Volatile var droppedStaleOutcomes: Int = 0
        private set
    /** TEST SUPPORT ONLY: the in-memory record queue's bytes, so an instrumented
 * test can assert what was (or was not) emitted. `internal`; never a decision input. */
    @JvmStatic internal fun pendingRecordBytesForTest(): List<ByteArray> = synchronized(lock) { pending.map { it.bytes } }
    @JvmStatic fun pendingAttemptIds(): List<String> = pendingOutcomes.map { it.first.attemptId }
    @Volatile var ownershipRefusals: Int = 0
        private set

    /** null when the handle no longer names a live view — a host whose view was
 * released or replaced between registration and capture. */
    private fun resolveCaptureRoot(handle: Int): View? = synchronized(captureRootLock) {
        captureRoots[handle]?.get()
    }

    /**
 * The host declares it is PREPARING and WILL report readiness.
 * This promise is what a budget expiry can break into `not_ready`; a host
 * that never makes it can never be timed out.
 */
    @JvmStatic
    fun hostReadinessPending(screenId: String, hostId: String): Boolean {
        val r = routeHosts ?: return false
        r.hostReadinessPending(screenId, hostId); return true
    }

    /** The host declares it became ready. */
    @JvmStatic
    fun hostReady(screenId: String, hostId: String): Boolean {
        val r = routeHosts ?: return false
        r.hostReady(screenId, hostId); return true
    }

    /** The host declares it failed to become ready. */
    @JvmStatic
    fun hostFailed(screenId: String, hostId: String): Boolean {
        val r = routeHosts ?: return false
        r.hostFailed(screenId, hostId); return true
    }

    /** The host went away; its statements stop counting immediately. */
    @JvmStatic
    fun hostDetached(screenId: String, hostId: String): Boolean {
        val r = routeHosts ?: return false
        r.hostDetached(screenId, hostId); return true
    }

    /** A route change or teardown invalidates every in-flight attempt. */
    @JvmStatic
    fun routeChanged(screenId: String?) {
        val r = routeHosts
        r?.routeChanged(screenId)
        // A fired-but-stale callback is made impossible here, not filtered later.
        attachDeadline?.cancel()
        // CANCEL *AND* DRAIN. Cancelling the timer neither retracts a callback
        // already queued nor removes the attempt from the queue; without this the
        // abandoned attempt was retained and re-evaluated forever.
        if (r != null) {
            val keep = ArrayList<Pair<com.tracescout.mobile.capture.RouteHostRegistry.Attempt, String>>()
            while (true) {
                val p = pendingOutcomes.poll() ?: break
                if (!p.first.isSettled() && !r.isStale(p.first)) keep.add(p)
                else { r.cancel(p.first); droppedStaleOutcomes++ }
            }
            pendingOutcomes.addAll(keep)
        }
    }

    /**
 * Re-evaluate waiting attempts and emit at most ONE marker for each.
 * Called by the deadline and by the capture path; safe at any cadence.
 */
    @JvmStatic
    fun resolvePendingCaptureOutcomes(): Int {
        val r = routeHosts ?: return 0
        var emitted = 0
        val keep = ArrayList<Pair<com.tracescout.mobile.capture.RouteHostRegistry.Attempt, String>>()
        while (true) {
            val p = pendingOutcomes.poll() ?: break
            // PRIVACY IS RE-CHECKED AT EXECUTION, NOT ONLY AT QUEUEING. A screen
            // marked never-record AFTER its attempt was queued must not receive a
            // marker at delivery; the attempt is abandoned outright.
            if (com.tracescout.mobile.privacy.ScreenPolicy.isNeverRecord(p.second)) {
                r.cancel(p.first); droppedStaleOutcomes++; continue
            }
            val d = com.tracescout.mobile.capture.CaptureOutcome.decide(
                neverRecord = false, host = r.evaluate(p.first))
            when (d.decision) {
                com.tracescout.mobile.capture.CaptureOutcome.Decision.UNAVAILABLE ->
                    // THE EXACTLY-ONCE GATE IS THE REGISTRY'S, per ATTEMPT —
                    // so a requeue, a racing caller and a retry collapse to one.
                    if (r.claimResolution(p.first)) {
                        val id = identity
                        if (id != null) {
                            enqueueDisclosing { dd ->
                                Envelope.screenCaptureUnavailable(
                                    id, admit("mobile_screen_snapshot"), clock.monotonicNanos(), posture(),
                                    screenId = p.second,
                                    captureUnavailableReason = d.captureUnavailableReason!!,
                                    clock = clock,
                                    dropped = dd,
                                )
                            }
                            emitted++
                        }
                    }
                com.tracescout.mobile.capture.CaptureOutcome.Decision.NO_EMISSION ->
                    // DROPPED IF STALE, NOT RE-QUEUED. `evaluate` refuses a stale
                    // attempt, and "not settled" is true of a stale attempt forever
                    // keeping on `isSettled` alone retained every abandoned
                    // attempt for the life of the process (the defect the RN twins
                    // had fixed; this copy never received it).
                    if (!p.first.isSettled() && !r.isStale(p.first)) keep.add(p)
                    else { r.cancel(p.first); droppedStaleOutcomes++ }
                else -> {
                    // CAPTURE DISCOVERED BY THE DRAIN SETTLES THE ATTEMPT. The host
                    // became ready with no capture in between; nothing is emitted
                    // here, but leaving the attempt OPEN made the next capture
                    // coalesce onto an expired budget and report not_attached at once.
                    r.cancel(p.first)
                }
            }
        }
        pendingOutcomes.addAll(keep)
        if (keep.isEmpty()) attachDeadline?.cancel()
        return emitted
    }

    @JvmStatic
    @JvmOverloads
    fun captureScreen(root: View, screenId: String, budget: ViewHierarchySnapshotter.Budget =
        ViewHierarchySnapshotter.Budget(),
        /**
 * The NATIVE-minted WebView segment this screen belongs to, when the
 * captured tree hosts an embedded browser. Ties the hierarchy to the
 * bridge segment so the renderer can mark the WebView span.
 */
        webViewSegmentId: String? = null,
        /**
 * W3C trace context of the backend work this screen correlates with.
 * The IDs come from the app's own tracing (or the WebView bridge) —
 * the SDK never mints them, because a fabricated trace id would
 * "correlate" with nothing and read as data loss downstream.
 */
        traceId: String? = null,
        spanId: String? = null,
    ): Boolean = guard("capture") {
        val id = identity ?: return@guard false

        // NEVER-RECORD IS DECIDED BEFORE THE WALK, NOT AFTER IT.
        // Snapshotting first and discarding the result would put the screen's
        // content in process memory for no reason, and any later bug in the
        // discard path would emit it. A screen the app declared unrecordable is
        // never traversed: it emits ONLY the typed lifecycle marker below, which
        // carries screen identity, timing and posture and no content at all.
        if (com.tracescout.mobile.privacy.ScreenPolicy.isNeverRecord(screenId)) {
            enqueueDisclosing { d ->
                Envelope.screenNeverRecord(
                    id, admit("mobile_screen_snapshot"), clock.monotonicNanos(), posture(),
                    screenId = screenId, clock = clock, dropped = d,
                )
            }
            return@guard true
        }

        // ── THE CAPTURE-OUTCOME DECISION, FROM OBSERVED SURFACE FACTS ──────
        // Shared rule, fixture-pinned in mobile-native-core the specification
        // (capture-outcome.json) so Android, RN Android and RN iOS cannot
        // diverge on it.
        //
        // THESE ARE THE ONLY TWO CONDITIONS, AND EACH MUST BE OBSERVED.
        // `isAttachedToWindow` is the platform's own attachment fact — not an
        // inference from a null parent or an empty tree. Zero measured bounds on
        // an ATTACHED view is "laid out has not happened yet", which is exactly
        // "the surface existed but had not reached a state from which a capture
        // could be taken".
        //
        // AND NOTHING ELSE MAY PRODUCE A REASON. A budget-exhausted walk, a
        // tree that legitimately contains zero nodes, a missing visual frame or
        // a thrown exception establish NEITHER condition. Those continue to the
        // walk below (and are reported through `degraded`/`dropped` as they
        // always were) or emit nothing at all. Translating an unknown cause into
        // a known reason is the failure this axis exists to prevent.
        // ── THE CAPTURE-OUTCOME FAILURE AXIS ───────────────────────────────
        // THIS PLATFORM CANNOT ESTABLISH EITHER PROTOCOL CONDITION, SO IT
        // EMITS NO REASON — deliberately, and this is the honest answer.
        //
        // The protocol defines `route_surface_not_attached` as "no qualifying
        // active route host for the requested route before the ATTACH BUDGET
        // expired" and `route_surface_not_ready` as "a qualifying active host
        // existed but its DECLARED READINESS never became true, or it reported
        // failure". Standalone Android has no route-host registry, no attach
        // budget and no host readiness declaration: the caller hands us a
        // `View` and a `screenId`, and `root.isAttachedToWindow` read once tells
        // us about THIS VIEW AT THIS INSTANT — not that no host existed for the
        // route across a budget we never waited out.
        //
        // Mapping that instantaneous read onto a reason is exactly the error
        // this axis exists to prevent, so the call site passes UNKNOWN and the
        // shared rule refuses. `CaptureOutcomeCallSiteTest` pins that no reason
        // can be emitted from here; when a route-host registry with a real
        // budget and a real readiness signal exists, THIS is the line that
        // changes, and the fixture already describes what it must supply.
        // Settle any prior attempt before this one begins, on the cadence the
        // app already drives. The deadline below is what makes the wait bounded
        // regardless.
        resolvePendingCaptureOutcomes()

        // ── THE ROUTE-HOST FACTS ────────────────────────────────────────────
        // UNKNOWN UNTIL AN APPLICATION INTEGRATES, and that is the honest
        // answer rather than a gap. `root.isAttachedToWindow` read once is an
        // INSTANT, not "no qualifying host before the attach budget expired",
        // and the caller's NAME for a View is not the platform's statement that
        // this View hosts that route. Only a host's own declaration establishes
        // either protocol condition — see `enableRouteHostReporting`.
        val reg = routeHosts
        val attempt = reg?.beginAttempt(screenId, id.sessionId)
        val outcome = com.tracescout.mobile.capture.CaptureOutcome.decide(
            neverRecord = false,           // already returned above if it were
            host = attempt?.let { reg.evaluate(it) }
                ?: com.tracescout.mobile.capture.CaptureOutcome.RouteHost.UNKNOWN,
        )
        if (outcome.decision == com.tracescout.mobile.capture.CaptureOutcome.Decision.UNAVAILABLE) {
            // Verdict already available at capture time.
            if (attempt == null || reg!!.claimResolution(attempt)) {
                enqueueDisclosing { d ->
                    Envelope.screenCaptureUnavailable(
                        id, admit("mobile_screen_snapshot"), clock.monotonicNanos(), posture(),
                        screenId = screenId,
                        captureUnavailableReason = outcome.captureUnavailableReason!!,
                        clock = clock,
                        dropped = d,
                    )
                }
            }
            return@guard true
        }
        if (attempt != null &&
            outcome.decision == com.tracescout.mobile.capture.CaptureOutcome.Decision.NO_EMISSION) {
            // Still inside the attach budget with nothing decided. ARM A REAL
            // DEADLINE: without it the budget would only be discovered the next
            // time the app happened to capture, i.e. bounded by user behaviour
            // rather than by the budget.
            // A COALESCED ATTEMPT IS ALREADY QUEUED AND ARMED (see the registry).
            // Queueing again would drain it twice; arming again would reset the
            // deadline by the capture cadence — forever under a periodic capture.
            if (pendingOutcomes.none { it.first === attempt }) {
                pendingOutcomes.add(attempt to screenId)
                attachDeadline?.arm(attachBudgetMs) { resolvePendingCaptureOutcomes() }
            }
        } else if (attempt != null) {
            reg!!.cancel(attempt)
        }

        // PRE-EXISTING LIMITATION, DOCUMENTED RATHER THAN CLAIMED SAFE.
        // `captureScreen` does not enforce the UI thread and the walk below has
        // always run on whatever thread the caller used. `View.isAttachedToWindow`,
        // `width`/`height` and the child list are owned by the view hierarchy
        // and may be mutated by it at any time, so an off-thread walk can
        // observe a torn tree. That is unchanged by this tranche and is NOT
        // validated as safe behaviour — it is recorded as a known limitation.
        // Verification of the capture-outcome axis therefore uses a UI-thread
        // call path; nothing here should be read as a guarantee about the
        // off-thread one.
        // OWNERSHIP IS VERIFIED BEFORE THE WALK, AND THERE IS NO FALL-BACK.
        // The caller hands us a View and a screenId; until now NOTHING checked
        // that the two correspond, so an app could capture screen B's tree and
        // have it stored under screen A's label — the same misattribution the
        // RN twin shipped, reached by a different route.
        //
        // THIS ONLY APPLIES TO AN INTEGRATED APP. With no registry the
        // caller's View is the declared root by definition and behaviour is
        // byte-for-byte what it has always been.
        val ownership = verifyCaptureRoot(screenId, root)
        if (!ownership) {
            // THE HIERARCHY IS SUPPRESSED AND NO REASON IS INVENTED.
            // The registry still decides the outcome axis on its own facts. An
            // ownership failure is NOT one of the two protocol conditions, so
            // `Envelope.screenCaptureUnavailable` must NOT be reached from
            // here — that would dress "we could not establish ownership" up as
            // "no qualifying host before the budget expired", which are
            // different statements about the world.
            //
            // AND A GAP, STATED RATHER THAN PAPERED OVER: this platform has
            // no plain metadata-only snapshot emitter (only `screenNeverRecord`
            // and `screenCaptureUnavailable`), so a suppressed capture
            // currently contributes NO snapshot event of its own. The screen
            // still exists in navigation and lifecycle telemetry. On RN the
            // bridge emits a metadata-only snapshot in this case; closing that
            // asymmetry needs a new emitter and is deliberately not invented
            // inside an ownership fix.
            return@guard true
        }
        val snap = ViewHierarchySnapshotter.snapshot(root, budget = budget)
        if (snap.droppedNodes > 0) {
            // `node` IS NOT AN EVENT-LOSS FAMILY AND NEVER WAS. This is
            // hierarchy-node truncation INSIDE one snapshot — the event was
            // captured, delivered and is complete as a document; it simply
            // carries fewer nodes than the tree held. `dropped` is
            // `additionalProperties: false` over the 13 canonical families, so
            // this key is not merely wrong, it is UNREPRESENTABLE. The fact
            // itself already reaches the wire as `degraded`, and it stays a
            // local diagnostic here.
            RecoveryTrace.log("hierarchy_nodes_truncated",
                "count" to snap.droppedNodes, "screenId" to screenId)
        }
        // ONE ancestry-aware transformation. Both the serialised nodes and the
        // allowed-view count below come from THIS result, so the payload and
        // the scalar describing it cannot disagree — which is exactly what the
        // API's mismatch guard caught on device when they could.
        val canonical = com.tracescout.mobile.privacy.CanonicalHierarchy.of(snap.nodes)
        if (canonical.prunedCount > 0) {
            // PRIVACY SUPPRESSION IS NOT LOSS, and reporting it as loss is
            // the worse of the two errors this pair could make. These subtrees
            // were removed because the customer's own privacy rule said so —
            // turning a privacy FEATURE into reported data loss would tell a
            // customer their data was destroyed by a mechanism working exactly
            // as designed. The protocol says so in the schema's own words:
            // "Intentional privacy suppression is NOT loss and must never
            // appear here." Local diagnostic only.
            RecoveryTrace.log("privacy_pruned_subtrees",
                "count" to canonical.prunedCount, "screenId" to screenId)
        }
        enqueueDisclosing { d ->
            Envelope.viewHierarchy(
                id = id,
                sequence = admit("mobile_view_hierarchy"),
                monotonicNanos = clock.monotonicNanos(),
                evidence = com.tracescout.mobile.privacy.CapturePrivacyEvidence.forHierarchy(
                    posture = posture(),
                    canonical = canonical,
                    screenNeverRecord = false,
                ),
                snapshotId = Envelope.newEventId(),
                nodes = canonical.nodes,
                nodeCount = canonical.nodes.size,
                captureDurationMs = snap.captureMillis,
                degraded = snap.truncated,
                screenId = screenId,
                webViewSegmentId = webViewSegmentId,
                // Attached at the ONE place the segment id itself is attached,
                // so the status can never travel without the segment it
                // describes — and can never be forgotten by a caller.
                webViewBrowserPrivacyStatus =
                    if (webViewSegmentId != null) browserPrivacyStatus() else null,
                traceId = traceId,
                spanId = spanId,
                // PRE-F6: the canonical queue-pressure families, moved
                // out of the session accumulator by `enqueueDisclosing`. The
                // zero-writer `droppedCounts` snapshot it replaced here fed this
                // field an ALWAYS-EMPTY map, which is why the block never
                // appeared on the wire.
                dropped = d,
                clock = clock,
            )
        }
        true
    } ?: false

    /**
 * Capture + upload ONE visual frame for [root].
 *
 * Runs the privacy classification FIRST and hands the resulting node list to
 * the capturer, which redacts in the same pass that rasterizes — there is no
 * moment at which an un-redacted bitmap exists as a durable object.
 *
 * The BYTES upload and the METADATA event are separate on purpose: bytes go
 * to the frame endpoint (which content-verifies and promotes them), and the
 * event only ever references the frame by id. A metadata event whose bytes
 * were rejected therefore describes a frame the renderer will show as a
 * typed placeholder — never as a blank screen, and never as content.
 */
    @JvmStatic
    @JvmOverloads
    fun captureVisualFrame(
        root: View,
        screenId: String,
        captureReason: String = "explicit_allow",
    ): String? = guard("frame") {
        val id = identity ?: return@guard null
        val cfg = config ?: return@guard null
        val up = uploader ?: return@guard null
        if (id.captureMode != "semantic_with_frames") return@guard null

        val snap = ViewHierarchySnapshotter.snapshot(root)
        // FLAG_SECURE is a WINDOW attribute. Read it from the window that owns
        // this view rather than inferring it from the node list — a password
        // field inside an ordinary window is redacted, not a reason to refuse
        // the whole screen.
        val windowSecure = try {
            val flags = (root.context as? Activity)?.window?.attributes?.flags ?: 0
            (flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        } catch (t: Throwable) {
            // Unknown window state is treated as SECURE. An unproven window is
            // not a safe one, and refusing a frame costs an enrichment while
            // capturing a secure window costs a disclosure.
            true
        }
        val res = com.tracescout.mobile.capture.VisualFrameCapturer.capture(
            root = root, nodes = snap.nodes, captureMode = id.captureMode,
            captureReason = captureReason, windowSecure = windowSecure,
        )
        val frame = when (res) {
            is com.tracescout.mobile.capture.VisualFrameCapturer.Result.Ok -> res.frame
            is com.tracescout.mobile.capture.VisualFrameCapturer.Result.Refused -> {
                lastFrameRefusal = res.refusal.code
                return@guard null
            }
        }
        val frameId = Envelope.newEventId()
        // RASTERIZE on the caller's thread (it had to be — reading a live View
        // off the main thread is undefined behaviour), then hand the finished
        // BYTES to the worker. The upload must not run here: Android throws
        // NetworkOnMainThreadException for network on the main thread, and this
        // method is by construction called from it. Found live — the first
        // real-device run returned `transport_error` with status 0 on every
        // frame, because the exception was caught by the never-throw guard and
        // correctly classified as a transport failure.
        //
        // The id is returned IMMEDIATELY and the upload proceeds asynchronously,
        // which is also the honest shape: a frame is an optional enrichment, so
        // the caller must never be blocked on it, and a frame whose bytes are
        // rejected renders as a typed placeholder rather than as content.
        val up2 = up
        val cfg2 = cfg
        worker.execute {
            try {
                lastFrameResult = up2.postFrame(
                    projectId = cfg2.projectId, sessionId = id.sessionId,
                    visualFrameId = frameId, bytes = frame.bytes,
                    width = frame.width, height = frame.height,
                    contentSha256 = frame.contentSha256, format = frame.format,
                    privacyState = frame.privacyState,
                    captureReason = frame.captureReason, screenId = screenId,
                )
            } catch (_: Throwable) {}
        }
        frameId
    }

    /** Drain any in-flight frame upload. Fixture/teardown only. */
    @JvmStatic
    fun awaitFrameUpload() {
        try { worker.submit { }.get() } catch (_: Throwable) {}
    }

    /** Last frame upload classification + refusal code — fixture assertions only. */
    @Volatile @JvmStatic var lastFrameResult: Uploader.Result? = null
        private set
    @Volatile @JvmStatic var lastFrameRefusal: String? = null
        private set

    @JvmStatic
    fun recordLifecycle(transition: String, appState: String): Boolean = guard("lifecycle") {
        val id = identity ?: return@guard false
        enqueueDisclosing { d ->
            Envelope.lifecycle(id, admit("mobile_lifecycle"), clock.monotonicNanos(), posture(),
                transition, appState, clock = clock, dropped = d)
        }
        true
    } ?: false

    /**
 * Record a screen navigation. Bounded, wire-safe metadata only: the route
 * ids and transition come from the app's route registry, never from user
 * input; an out-of-enum transition or over-long id is REFUSED (returns
 * false) rather than truncated into a lie. `deepLinkPathTemplate` must be
 * the template form (`/asset/:symbol`) — concrete deep links carry user
 * data and are unrepresentable here.
 */
    @JvmStatic
    @JvmOverloads
    fun recordNavigation(
        toScreenId: String,
        fromScreenId: String? = null,
        transition: String = "push",
        toScreenName: String? = null,
        deepLinkPathTemplate: String? = null,
    ): Boolean = guard("navigation") {
        val id = identity ?: return@guard false
        if (transition !in Envelope.NAV_TRANSITIONS) return@guard false
        if (toScreenId.isBlank() || toScreenId.length > 64) return@guard false
        if (fromScreenId != null && fromScreenId.length > 64) return@guard false
        if (toScreenName != null && toScreenName.length > 200) return@guard false
        if (deepLinkPathTemplate != null && deepLinkPathTemplate.length > 300) return@guard false
        enqueueDisclosing { d ->
            Envelope.navigation(id, admit("mobile_navigation"), clock.monotonicNanos(), posture(),
                transition, toScreenId, fromScreenId, toScreenName, deepLinkPathTemplate,
                clock = clock, dropped = d)
        }
        true
    } ?: false

    @JvmStatic
    fun recordHang(durationMs: Long): Boolean = guard("hang") {
        val id = identity ?: return@guard false
        enqueueDisclosing { d ->
            Envelope.hang(id, admit("mobile_hang"), clock.monotonicNanos(), posture(),
                durationMs, clock = clock, dropped = d)
        }
        true
    } ?: false

    /**
 * Fold a WebView segment's posture into the session.
 *
 * Worst-posture: a relaxed embedded page makes the whole session relaxed.
 * A strict native shell cannot hide a permissive page it is hosting, and
 * pretending otherwise is how a session gets shared that should not be.
 */
    @JvmStatic
    @JvmOverloads
    fun recordWebViewSegment(p: Posture, browserPrivacyStatus: String? = null) =
        guard("webview") {
            synchronized(lock) {
                segmentPostures.add(p)
                // The browser side's OWN coarse status. It is NOT derivable
                // from the posture: the posture says what masking was applied,
                // this says whether the browser recorder considered itself
                // privacy-enabled at all. `WebViewBridge` already parsed,
                // bounded and capped it — and then this function's previous
                // signature took a bare Posture and threw it away, so the
                // column it feeds was empty for every session ever recorded.
                // Bounded to the wire enum here too, so a value that would be
                // refused downstream is dropped at the source instead of
                // travelling as a silent unknown.
                if (browserPrivacyStatus in BROWSER_PRIVACY_STATUSES) {
                    lastBrowserPrivacyStatus = browserPrivacyStatus
                }
            }
            Unit
        }

    // -----------------------------------------------------------------------
    // Delivery
    // -----------------------------------------------------------------------

    /**
 * Persist and upload everything captured so far.
 *
 * `blocking` exists for tests and for a host that must drain before a known
 * teardown. Production callers use the async form so the SDK never occupies
 * a lifecycle callback.
 */
    /**
 * THE UPLOAD SELECTION IS THE DURABLE QUEUE, NEVER THE IN-MEMORY LIST
 * (Slice B, ruling B2; the capture specification). This used to hand `uploadRaw` a copy of
 * `pending` — the in-memory mirror — and the pipeline treats its argument
 * as the queue image: a batch sliced from a non-queue list failed the
 * dequeue prefix re-check, nothing was removed, and the next drain rebuilt
 * it while the server answered `duplicate`. `pending` may REQUEST a flush;
 * it cannot substitute for the queue. The task reads the store on the
 * worker, so a record that was refused by the store is never uploaded
 * from memory, and a record already in the queue from an earlier flush is
 * never left behind an image that does not contain it.
 *
 * THE ORDERING GUARANTEE IS THE SINGLE-THREAD WORKER'S FIFO (RF4-56).
 * `enqueue` submits every durable append to `worker`; this submits the
 * upload task to the SAME `worker`; a single-thread executor runs tasks
 * in submission order, so every append submitted before this flush has
 * COMPLETED before the task reads the queue. That — and only that — is
 * what makes "outstanding appends have completed" true, and a pin holds
 * the flush task to this executor (moving it is a caught control).
 */
    @JvmStatic
    @JvmOverloads
    fun flush(blocking: Boolean = false): Boolean = guard("flush") {
        synchronized(lock) {
            // The mirror has done its job once the flush is requested.
            pending.clear()
        }
        val task = Runnable {
            val s = store ?: return@Runnable
            val recs = s.readAll().records
            // F7 R-2: an explicit flush that finds nothing is chunkless too, so
            // a finalized session is disclosed here rather than waiting for a
            // process restart. Unconditional because the EMPTY case is now a
            // decision the pipeline makes, not an absence of work.
            uploadRaw(recs)
        }
        if (blocking) {
            val f = worker.submit(task)
            f.get()
        } else {
            worker.execute(task)
        }
        true
    } ?: false

    /**
 * Upload pre-serialised envelope bytes, retrying per the shared policy.
 *
 * The records are removed from the durable store ONLY after the backend
 * accepts them. A crash between acceptance and removal re-sends, which the
 * backend deduplicates by event id; the reverse order would lose data
 * outright, and a duplicate is strictly cheaper than a hole.
 */
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
    @Volatile private var parkStoreDir: java.io.File? = null

    /**
 * The PARKED store — records a producer-capability refusal permanently
 * excludes from delivery, preserved losslessly under the SAME
 * Keystore-backed key as the live queue.
 *
 * Fails CLOSED: with no parked store the pipeline cannot park, so the
 * slice is RETAINED in place exactly as before. Never the reverse — a
 * store that pretended to succeed would authorise deleting active bytes
 * with nowhere for them to go.
 */
    private fun openParkStore(): com.tracescout.mobile.internal.queue.ParkStore {
        val dir = parkStoreDir
            ?: return com.tracescout.mobile.internal.queue.ParkStore.none()
        return try {
            com.tracescout.mobile.queue.EncryptedParkStore(
                java.io.File(dir, com.tracescout.mobile.queue.QueueKeyStores.SDK_PARKED_RECORDS),
                java.io.File(dir, com.tracescout.mobile.queue.QueueKeyStores.SDK_PARKED_MANIFEST),
                com.tracescout.mobile.queue.StoreKeys.queueKey(),
            )
        } catch (t: Throwable) {
            RecoveryTrace.log("park_store_unavailable", "code" to t.javaClass.simpleName)
            com.tracescout.mobile.internal.queue.ParkStore.none()
        }
    }

    /** Safe parked diagnostics — counts and bytes only, never content. */
    @JvmStatic
    fun parkedStatus(): com.tracescout.mobile.internal.queue.ParkStatus =
        try { openParkStore().status() }
        catch (_: Throwable) { com.tracescout.mobile.internal.queue.ParkStatus(0, 0, 0L) }

    private fun uploadRaw(records: List<ByteArray>) {
        val cfg = config ?: return
        val id = identity ?: return
        val up = uploader ?: return
        val h = home ?: return
        // NO `appContext` IS A TYPED REFUSAL TO DRAIN, NEVER A SILENT DOWNGRADE
        // (Slice B, ruling B6). This used to fall back to
        // `InFlightStore.memory` when the context was absent — a descriptor
        // that cannot survive the crash it exists to survive, chosen silently.
        val app = appContext ?: run {
            RecoveryTrace.log("drain_refused", "why" to "no_app_context")
            lastResult = Uploader.Result(ResponseAction.RETRY, 0, "inflight_unavailable", null)
            return
        }
        val filesDirForFence = app.filesDir
        // F4-P0 Slice D (D4): a bracket still unresolved from an earlier open is
        // RETRIED before this drain — the "retry later" a failed finalization
        // is owed. Cheap when nothing is blocked (one absent-file read); the
        // pipeline below refuses typed on its own if the marker is still there.
        if (com.tracescout.mobile.internal.queue.EvictionRecovery.blocks(h)?.startsWith("bracket") == true) {
            recordRecovery(recoveryFor(h, filesDirForFence).recover())
        }
        // Thin delegation to the ONE shared state machine. The behaviour lives
        // in com.tracescout.mobile.internal.queue.DurableUploadPipeline so the
        // React Native producer reuses this implementation rather than copying
        // it; the pipeline is producer-agnostic and never sees sdkFamily,
        // sdkVersion, platform or hostPlatform.
        DurableUploadPipeline(
            // The QUEUE HOME, never a bare store (Slice B, B4): the pipeline's
            // check-then-mutate sequences run as regions on the home's locks.
            home = h,
            // The mutation gate, PER SLICE (F2 map the specification, liveness B2). The
            // authority consulted is the one that OWNS the records being
            // removed, derived from their own bytes — not this process's current
            // session, which is what this line used to ask. A drain routinely
            // carries a previous session's records, so asking `authority` (the
            // live session) let one session's unresolved state refuse removals
            // for every other session's acknowledged bytes.
            mutationGate = { slice ->
                com.tracescout.mobile.internal.loss.LossAuthority.mutationFenceFor(filesDirForFence, slice)
            },
            // F4-P1: the seam the quarantine bracket settles LOST(QUARANTINED)
            // through — the SAME per-install seam the eviction bracket uses.
            evictionSeam = com.tracescout.mobile.internal.queue.EvictionAttribution.forFilesDir(filesDirForFence),
            poster = { pid, body -> up.postChunk(pid, body) },
            projectId = cfg.projectId,
            maxRetries = cfg.maxRetries,
            appContext = appContext,
            backoffMillis = { attempt -> backoffMillis(attempt) },
            results = object : DurableUploadPipeline.ResultSink {
                override var last: Uploader.Result?
                    get() = lastResult
                    set(value) { lastResult = value }
            },
            quarantine = object : DurableUploadPipeline.QuarantineSink {
                override fun onQuarantined(count: Int, code: String?) {
                    quarantined.addAndGet(count.toLong())
                    lastQuarantineReason = code
                }
            },
            // The THIRD durable fate. Android reaches it by exactly the same
            // shared policy RN does — a producer-capability refusal is an
            // immutable property of the captured bytes on every platform, so a
            // per-producer answer would be a second authority over the thing
            // that moves customer data.
            park = openParkStore(),
            // Same invariant, same reason: an unresolved batch owns the head of
            // the queue until an identity-bound ack settles it, across process
            // death. See InFlightStore. ALWAYS the durable file store; there is
            // no memory fallback on this path any more.
            inflight = inflightStoreOf(app),
            // F2: delivered / parked / quarantined removals are attributed to
            // the session that owns the records (an inherited slice may belong
            // to a previous session — the sink resolves the handle per session).
            attribution = com.tracescout.mobile.internal.queue.AttributionSink.forFilesDir(app.filesDir),
            // F4-P2: a drain that meets a LOSSY image reconciles it first (F4-16).
            reconciler = com.tracescout.mobile.internal.queue.QueueReconciler(h, app.filesDir, inflightStoreOf(app)),
        ).upload(records, id.sessionId)
    }

    // sessionOwnedPrefix and BatchIdentity moved to
    // com.tracescout.mobile.internal.queue.DurableUploadPipeline. These two
    // delegators exist so the EXISTING Android tests (BatchSessionAddressTest,
    // AckContractTest) exercise the extracted code through their original call
    // sites — which is stronger equivalence evidence than porting the tests.
    internal fun sessionOwnedPrefix(
        records: List<ByteArray>,
        fallback: String,
    ): Pair<String, List<ByteArray>> =
        DurableUploadPipeline.sessionOwnedPrefix(records, fallback)

    internal fun batchIdentityOf(sessionId: String, records: List<ByteArray>) =
        DurableUploadPipeline.BatchIdentity.of(sessionId, records)


    /** Records dropped by a permanent rejection — never counted as delivered. */
    @JvmStatic
    val quarantined = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    @JvmStatic
    var lastQuarantineReason: String? = null
        private set

    /** Last delivery classification — for fixture assertions and diagnostics. */
    @Volatile
    @JvmStatic
    var lastResult: Uploader.Result? = null
        private set

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /**
 * Claim the next sequence for one event, DURABLY, before the record is
 * built. Returns -1 when nothing was claimed (no authority for this
 * session, fenced, unknown event type): no number was allocated, so no
 * loss is recorded — the refusal is traced and `enqueue` drops the
 * unnumbered record. There is deliberately no in-memory fallback.
 */
    private fun admit(eventType: String): Long {
        val h = authority ?: run {
            RecoveryTrace.log("authority_missing", "eventType" to eventType)
            return -1L
        }
        // F4-P0 Slice D (D8 / U-2): while this queue is recovery-blocked or the
        // install is being erased, the append would be refused anyway (RF4-11,
        // U-2) — refusing BEFORE the admission costs no authority frame and
        // allocates no number, so the refusal is not a loss and the WAL does
        // not fill with refused claims while the bracket waits for recovery.
        home?.let { com.tracescout.mobile.internal.queue.EvictionRecovery.blocks(it) }?.let { why ->
            RecoveryTrace.log("authority_admission_refused", "eventType" to eventType, "why" to why)
            return -1L
        }
        // F4-P2 child — GATE A of the reconciliation mutation gate: while a durable
        // reconciliation fact binds this queue's image, no ticket is allocated and
        // nothing is appended (the ONE predicate; Gate B re-asks it at the append).
        home?.let { com.tracescout.mobile.internal.queue.QueueReconciler.mutationBlockReason(it) }?.let { why ->
            RecoveryTrace.log("authority_admission_refused", "eventType" to eventType, "why" to why)
            return -1L
        }
        return when (val a = h.beginAdmission("adm-" + java.util.UUID.randomUUID(), "", eventType)) {
            is com.tracescout.mobile.internal.loss.LossAuthority.Admission.Owned -> {
                tickets[a.ticket.sequence] = a.ticket
                a.ticket.sequence
            }
            is com.tracescout.mobile.internal.loss.LossAuthority.Admission.Refused -> {
                RecoveryTrace.log("authority_refused", "eventType" to eventType, "why" to a.reason); -1L
            }
            is com.tracescout.mobile.internal.loss.LossAuthority.Admission.NotDurable -> {
                RecoveryTrace.log("authority_not_durable", "eventType" to eventType, "why" to a.reason); -1L
            }
        }
    }

    /**
 * What [endSessionAuthoritatively] did. A PRODUCT-level outcome, deliberately
 * NOT `LossAuthority.Write` — that type is `internal` and `:sdk`-owned, and the
 * B10 pin keeps the authority's vocabulary out of every public signature.
 */
    public enum class SessionEndOutcome {
        /**
 * The terminal claim is DURABLE. The finality-only checkpoint is emitted by
 * the ordinary metadata-only path on the next drain — see
 * [endSessionAuthoritatively]'s note on what this call does NOT do.
 */
        RECORDED,

        /**
 * A terminal was ALREADY requested for this session, so nothing was written.
 * Terminality is one-way and recorded ONCE; a second declaration is a no-op
 * rather than an error, which is what makes the call safe to retry.
 */
        ALREADY_RECORDED,

        /** No session is running, so there is nothing to end. Nothing was written. */
        NO_SESSION,

        /**
 * The claim could NOT be persisted, so nothing was asserted to anyone. The
 * handle is fenced and only a reopen decides; the session is unchanged.
 */
        NOT_DURABLE,

        /**
 * The authority refused (a disabled session, an unknown high-water, a fenced
 * instance). Nothing was written and no terminality is claimed.
 */
        REFUSED,
    }

    /**
 * **THE EXPLICIT AUTHORITATIVE SESSION-END SIGNAL — the ONE product act that may
 * terminalize a mobile replay session, and the ONLY production caller of
 * `LossAuthority.Handle.recordTerminal`.**
 *
 * Authority: the lead's option-A ruling
 * `an explicit product decision`, which answers Mission Control an open product question
 * the question the frozen `C18.md` left open — with a fixed policy:
 *
 * ```
 * ONLY an explicit authoritative session-end signal may permanently terminalize.
 * backgrounding MUST NOT terminalize
 * queue drained MUST NOT terminalize
 * app/process shutdown MUST NOT terminalize
 * final flush MUST NOT terminalize
 * terminality is one-way once explicitly asserted.
 * ```
 *
 * **IT IS PRODUCT POLICY, NOT LIFECYCLE INFERENCE, AND THE NAME SAYS SO.**
 * The host application declares *"this session is over"*. The SDK never infers
 * it. `Activity.onStop`, `onDestroy`, `onTrimMemory`, a `ProcessLifecycleOwner`
 * transition, `flush`, a drained queue and process death are NOT this signal
 * and must never be wired to it — a resumable session that was reported final
 * cannot be un-reported, because the server's terminality is one-way.
 *
 * **WHY A GENERIC LIFECYCLE EVENT CANNOT REACH IT BY ACCIDENT, structurally
 * rather than by review.** This function has **ZERO callers in `src/main`** — it
 * exists only to be called from OUTSIDE the SDK — and it is the only production
 * path to `recordTerminal`. So the reachability argument does not depend on
 * auditing what every lifecycle callback happens to call today: there is no
 * internal edge into this function at all, and a future one would have to be
 * added deliberately. `F7TerminalSeamTest` pins both halves — exactly one
 * production caller of `recordTerminal` (this function) and zero production
 * callers of this function — so adding either edge fails the suite.
 *
 * **DURABLE BEFORE ASSERTED.** This records the claim and emits nothing.
 * `LossLedger.freeze`'s own KDoc requires it in that order — *"call
 * `markTerminal`, persist, then emit"* — because a process death between an
 * emission and its persistence leaves a producer whose durable state says
 * non-terminal while the server has been told otherwise, and that divergence is
 * unrecoverable in one direction.
 *
 * **WHAT IT DELIBERATELY DOES NOT DO, and the consequence, stated.** It does
 * not emit, does not flush, does not schedule a drain and does not stop capture.
 * The finality-only checkpoint is produced by the EXISTING metadata-only path
 * (`F7MetadataOnlyEmitter` -> `MetadataOnlyCarrier.finality`) on the next
 * ordinary drain, which includes the next process start's recovery. **So a host
 * that declares session end and is then killed emits the checkpoint on the next
 * launch, not before.** Nothing is lost — the claim is durable, which is the
 * property that matters — but whether this call should also schedule a drain is a
 * PRODUCT decision and is deliberately not taken here.
 *
 * Idempotent and one-way: calling it twice is [SessionEndOutcome.ALREADY_RECORDED],
 * and there is no inverse function, by construction.
 *
 * @return what happened; never throws.
 */
    @JvmStatic
    public fun endSessionAuthoritatively(): SessionEndOutcome {
        // The LIVE session's authority, and nothing else. A session id argument
        // would let a caller terminalize a session it is not running — including an
        // inherited pre-F7 one — and terminality cannot be withdrawn.
        val h = authority ?: run {
            RecoveryTrace.log("f7_session_end_no_authority")
            return SessionEndOutcome.NO_SESSION
        }
        val w = try {
            // The intent id makes the write idempotent under core's own
            // already-applied rule; the POLICY refusal for a second request is
            // core-adjacent and arrives as PolicyDeclined, never as a core refusal.
            h.recordTerminal("term-" + java.util.UUID.randomUUID())
        } catch (t: Throwable) {
            RecoveryTrace.log("f7_session_end_threw", "why" to t.javaClass.simpleName)
            return SessionEndOutcome.NOT_DURABLE
        }
        val outcome = when (w) {
            is com.tracescout.mobile.internal.loss.LossAuthority.Write.Written ->
                SessionEndOutcome.RECORDED
            is com.tracescout.mobile.internal.loss.LossAuthority.Write.PolicyDeclined ->
                SessionEndOutcome.ALREADY_RECORDED
            is com.tracescout.mobile.internal.loss.LossAuthority.Write.NotDurable ->
                SessionEndOutcome.NOT_DURABLE
            is com.tracescout.mobile.internal.loss.LossAuthority.Write.Refused ->
                SessionEndOutcome.REFUSED
        }
        RecoveryTrace.log("f7_session_end", "outcome" to outcome.name)
        return outcome
    }

    private fun browserPrivacyStatus(): String? =
        synchronized(lock) { lastBrowserPrivacyStatus }

    private fun posture(): Posture = synchronized(lock) {
        com.tracescout.mobile.privacy.worstPosture(listOf(nativePosture) + segmentPostures)
    }

    /**
 * **`snapshotDropped` MOVED TO `F6CheckpointEmitter` WITH ITS
 * CARRIER (F6, 2026-10-10).** The pair's own KDoc said *"widening or
 * repurposing it is F6's to do"*, and F6 moved it so the ONE orchestration
 * path reaches it from BOTH Android producers — see the note on the
 * relocated declaration above. Nothing replaces it here: the PRE-F6
 * ordinary-envelope disclosure is [takePreF6Dropped] over
 * [preF6DroppedFamilies] and is unchanged.
 */

    /**
 * PRE-F6: take the outstanding canonical loss disclosure for ONE ordinary
 * envelope. The counts LEAVE the accumulator here — see the field KDoc.
 */
    private fun takePreF6Dropped(): Map<String, Int> = synchronized(lock) {
        if (preF6DroppedFamilies.isEmpty()) emptyMap()
        else HashMap(preF6DroppedFamilies).also { preF6DroppedFamilies.clear() }
    }

    /** PRE-F6: this envelope's disclosure is now bound to [eventId], awaiting its append. */
    private fun bindPreF6Dropped(eventId: String, disclosed: Map<String, Int>) {
        if (disclosed.isEmpty()) return
        synchronized(lock) { preF6DroppedInFlight[eventId] = disclosed }
    }

    /**
 * PRE-F6: the append COMMITTED, so the disclosure has been made exactly
 * once and is dropped. Never re-emitted.
 */
    private fun settlePreF6Dropped(eventId: String) {
        synchronized(lock) { preF6DroppedInFlight.remove(eventId) }
    }

    /**
 * PRE-F6: the append was refused, threw, or is INDETERMINATE — so the
 * bytes carrying this disclosure may never reach the server. The counts go
 * BACK into the accumulator and ride the next ordinary envelope.
 *
 * A count must not be silently erased by a failure the architecture can
 * already tell apart; and a disclosure repeated once is cheaper than a
 * loss nobody is told about.
 */
    private fun returnPreF6Dropped(eventId: String) {
        synchronized(lock) {
            val back = preF6DroppedInFlight.remove(eventId) ?: return
            for ((f, n) in back) preF6DroppedFamilies[f] = (preF6DroppedFamilies[f] ?: 0) + n
        }
    }

    /** PRE-F6: the build itself failed, so the moved counts go straight back. */
    private fun returnPreF6DroppedMap(disclosed: Map<String, Int>) {
        if (disclosed.isEmpty()) return
        synchronized(lock) {
            for ((f, n) in disclosed) preF6DroppedFamilies[f] = (preF6DroppedFamilies[f] ?: 0) + n
        }
    }

    /**
 * Build + enqueue ONE ordinary envelope, carrying any outstanding PRE-F6
 * canonical loss disclosure. The ONE home of the take / bind / restore
 * discipline, so no call site can take the counts and forget to bind them.
 *
 * `crash` is deliberately NOT routed through here: Rule 1 outranks
 * disclosure, and a dying thread must not run state effects.
 *
 * AND THE SENTENCE THAT USED TO FOLLOW WAS FALSE — it claimed the next
 * launch's `session_start` "does carry" the pre-crash outstanding counts.
 * It cannot. The PRE-F6 accumulator is VOLATILE, process-scoped state; a
 * process death DISCARDS whatever was outstanding, and no durable record of
 * it exists anywhere (by decision — F6 owns the durable checkpoint carrier,
 * and this stage adds none).
 *
 * CORRECT SEMANTICS, and they are PERMITTED rather than merely tolerated:
 * - process death DISCARDS outstanding volatile PRE-F6 G5 counts;
 * - that is ALLOWED by the frozen C5 / G5 / Phase-0.8 semantics, which
 * require a LIVE destination for canonical counts, never persistence of
 * the accumulator across process death;
 * - the next launch's `session_start` is disclosure-CAPABLE, but only for
 * counts created AFTER that launch;
 * - the disclosure surface is independent of any SPECIFIC event type — all
 * six ordinary factories carry it — but disclosure is NOT GUARANTEED: if
 * no subsequent ordinary envelope occurs, the counts are never disclosed;
 * - Rule 1 still excludes crash-thread state effects, which is why `crash`
 * is outside this helper at all.
 */
    private inline fun enqueueDisclosing(build: (Map<String, Int>) -> Envelope.Record) {
        val d = takePreF6Dropped()
        val rec = try {
            build(d)
        } catch (t: Throwable) {
            returnPreF6DroppedMap(d)
            throw t
        }
        // The bytes now carry the disclosure; hold the counts in flight until the
        // durable append says COMMITTED. Bound BEFORE `enqueue`, so no path
        // inside it can consume the record without the counts being accounted.
        bindPreF6Dropped(rec.eventId, d)
        enqueue(rec)
    }

    /**
 * Buffer + persist. The bound is enforced with the SHARED eviction
 * function, so the queue's drop policy is identical across bindings and is
 * covered by the golden fixtures rather than reimplemented here.
 */
    private fun enqueue(rec: Envelope.Record) {
        // PRE-F6: the counts were MOVED out of the accumulator to build this
        // envelope and are already bound in flight to `rec.eventId`, so every
        // path that does not reach the durable append must put them back —
        // otherwise a disclosure is erased by a path that lost nothing.
        //
        // AND THE SIGNATURE IS DELIBERATELY UNCHANGED. Three ACCEPTED pins
        // (`AuthorityOwnershipPinsTest` the specification, `SliceBSourceTest` S1,
        // `SliceCSourceTest` SC3) slice this function by the literal
        // `private fun enqueue(rec: Envelope.Record)`. Adding a parameter broke
        // all three anchors while changing none of their contracts — so the
        // disclosure travels through the in-flight field keyed by event id
        // rather than through a new argument. Re-pointing three accepted pins
        // because a new change moved their anchor is the larger intervention.
        val cfg = config ?: run { returnPreF6Dropped(rec.eventId); return }
        if (rec.sequence < 0) {
            returnPreF6Dropped(rec.eventId)
            // Nothing was claimed for this record (see `admit`), so nothing
            // is stored and nothing is lost in the accounting sense — a record
            // stamped with a number nobody owns would be a gap the authority
            // could never explain.
            RecoveryTrace.log("enqueue_unnumbered_dropped", "eventType" to rec.eventType)
            return
        }
        val ticket = tickets[rec.sequence]
        if (ticket != null && ticket.eventType != rec.eventType) {
            // The claim was made for one type and the record built for another:
            // the FAMILY the authority retained would be wrong. Loud, never
            // silent; the bytes are still what they are and still get stored.
            RecoveryTrace.log("authority_type_mismatch", "claimed" to ticket.eventType, "built" to rec.eventType)
        }
        synchronized(lock) {
            pending.add(rec)
            if (pending.size > cfg.maxQueuedEvents) {
                val ev: EvictionResult = evictToFit(
                    pending.map { QueuedEvent(it.eventId, it.eventType, it.sequence) },
                    cfg.maxQueuedEvents,
                )
                // PRE-F6 GAP REMEDIATION (C5 / G5 / E5): A REFUSED EVICTION
                // DESTROYS NOTHING, AND LEAVES THE MIRROR BOUNDED (F-2).
                //
                // The planner refuses rather than guessing a family for an
                // event type the one canonical mapper does not know. The
                // rejected candidate then kept the FULL input, so the retain
                // was a no-op and `pending` grew by one on EVERY unknown-type
                // enqueue, without bound, forever.
                //
                // The record just added is removed from the MIRROR instead.
                // That loses nothing: `pending` has no production reader (only
                // its own size, its own eviction and a test accessor), the
                // upload selection is the DURABLE QUEUE, and the durable append
                // below is UNCONDITIONAL — so this record is still stored and
                // still uploaded. Evicting a known-type victim instead would
                // change priority order, which the frozen C5 row forbids: an
                // unknown type is priority 0 precisely so it can never push out
                // a crash by being unrecognised.
                if (ev.refused != null) {
                    pending.remove(rec)
                    RecoveryTrace.log("queue_pressure_refused",
                        "why" to ev.refused, "reason" to ev.reason, "pending" to pending.size)
                } else {
                    val keep = ev.kept.toHashSet()
                    pending.retainAll { keep.contains(it.eventId) }
                    // PRE-F6: accumulate the CANONICAL families, session-scoped,
                    // so the next ordinary envelope can disclose them.
                    for ((f, n) in ev.droppedByFamily) {
                        preF6DroppedFamilies[f] = (preF6DroppedFamilies[f] ?: 0) + n
                    }
                }
                // THIS FOLDED RAW EVENT-TYPE STRINGS INTO A LOSS-FAMILY MAP.
                // `ev.droppedByEventType` is keyed by `mobile_interaction`,
                // `mobile_visual_frame`, … — real losses, keyed WRONGLY. The
                // wire block admits only the 13 canonical families, so every
                // one of these keys was unrepresentable.
                //
                // THIS BLOCK USED TO DESCRIBE A WORLD THAT NO LONGER EXISTS,
                // AND THE CORRECTION IS RECORDED RATHER THAN QUIETLY APPLIED.
                // It said the canonical map "lives in `rn-core`", that `:sdk`
                // "deliberately cannot reach it", that "the shared pure module
                // does not exist", and that this loss "contributes NOTHING to
                // the wire block". All four were true in Phase 0 and all four
                // are false now.
                //
                // CURRENT TRUTH: the ONE canonical `eventType -> lossFamily`
                // map lives in `mobile-loss-core` — the shared pure module BOTH
                // mobile worlds consume — and `:sdk` DOES reach it, through the
                // approved `LossAuthority` facade, which `LossVocabularyTest`
                // pins as the only importer of the vocabulary in this module
                // (frozen pin B1 forbids `:sdk` naming `rn-core` at all, and
                // that is unchanged). A `when (eventType)` here would still be a
                // second opinion about what was lost, so the family is ASKED
                // FOR and never computed here — see `EventQueue.kt`, which tells
                // the same story about the same mapper.
                //
                // AND THE CANONICAL COUNTS NOW HAVE A DESTINATION:
                // PRE-F6 accumulates them session-scoped and the next ordinary
                // envelope discloses them through the ALREADY-EXISTING
                // `envelopeFields` `dropped` block. What does NOT exist, by
                // decision, is any CHECKPOINT dropped writer or emitter —
                // `droppedCounts` has zero writers and `F6_DROPPED_WIRE_WRITER`
                // / `F6_CHECKPOINT_EMITTER` both stay at ZERO. That split is
                // a product decision: PRE-F6 owns the live ordinary-envelope disclosure, F6
                // owns the checkpoint carrier.
                for ((t, n) in ev.droppedByEventType) {
                    RecoveryTrace.log("queue_pressure_dropped",
                        "eventType" to t, "count" to n, "reason" to ev.reason)
                }
                // PRE-F6 GAP REMEDIATION (C5 / G5 / E5): the SAME losses,
                // now also recorded under the CANONICAL loss family — the only
                // keyspace the envelope's `dropped` block admits. Resolved by
                // the one sanctioned mapper inside the planner, before any
                // record was destroyed.
                //
                // CORRECTED BY a product decision, WHICH SUPERSEDES WHAT THIS COMMENT USED
                // TO SAY. It read "the wire connection is deliberately not made
                // here", and a product decision rejected exactly that: a canonical count whose
                // only consumer is this trace line has NO destination. PRE-F6 owns
                // the connection to the ALREADY-LIVE ordinary-envelope `dropped`
                // disclosure; F6 owns only the CHECKPOINT carrier. The connection
                // is now made, via the session accumulator and `enqueueDisclosing`.
                // This loop stays as a LOCAL diagnostic beside it — it is not the
                // destination, and `F6_DROPPED_WIRE_WRITER` stays at ZERO because
                // nothing here writes `droppedCounts`.
                for ((f, n) in ev.droppedByFamily) {
                    RecoveryTrace.log("queue_pressure_dropped_family",
                        "family" to f, "count" to n, "reason" to ev.reason)
                }
            }
        }
        // Persist off the caller's thread; the durable copy is what survives a
        // process death, so it must exist before the next upload attempt.
        worker.execute {
            // THIS LINE DISCARDED THREE SEPARATE FACTS. It was
            // `try { store?.append(rec.bytes) } catch (_: Throwable) {}`:
            // the AppendResult was discarded, the exception was discarded, and
            // `store?` silently no-opped when the store was null. A record
            // whose sequence had already been burned at the CALL SITE (inside
            // the `Envelope.x(.)` argument list) therefore vanished with no
            // local signal of any kind.
            //
            // Phase 0 makes each of the three observable and keeps them
            // DISTINCT — a null store is a configuration state, a refusal is a
            // storage decision, and a throw is neither.
            val b = bracket
            if (b == null) {
                // PRE-F6 (C1): this path reaches NO durable append, so the
                // counts this envelope took out of the accumulator must go
                // back — otherwise a configuration state (no store) ERASES a
                // disclosure, which is the one thing the in-flight map exists
                // to prevent. The restoration is the SAME one every other
                // non-committing path uses; a second shape here would be a
                // second opinion about what "not stored" means.
                //
                // Whether a null bracket is reachable at this point is not the
                // question: the implementation CLAIMS every non-committing path
                // restores, and an unreachable hole in that claim is still a
                // hole. Logging is unchanged.
                returnPreF6Dropped(rec.eventId)
                RecoveryTrace.log("enqueue_no_store",
                    "eventType" to rec.eventType, "sequence" to rec.sequence)
                return@execute
            }
            try {
                // F4-P0 Slice C: THE GOVERNED APPEND, NEVER THE BARE STORE.
                // The bracket tries the plain append first and runs the
                // PREPARE/COMMIT/SETTLE bracket only on `at_capacity`; its
                // result carries the same three facts `AppendResult` did.
                val r = b.append(rec.bytes)
                if (!r.stored) {
                    RecoveryTrace.log("enqueue_refused",
                        "reason" to (r.reason ?: "unknown"),
                        "eventType" to rec.eventType, "sequence" to rec.sequence)
                }
                // F2 settlement: the claim becomes ADMITTED only on a COMMITTED
                // append; a refusal is ONE loss in the admission's own family.
                // An indeterminate append settles nothing — the next open
                // reconciles it against what is really on disk.
                settleAppend(rec.sequence, r.stored, r.durability)
                // PRE-F6: the disclosure is made exactly once, on a COMMITTED
                // append. Anything else (refused, or an INDETERMINATE directory)
                // returns the counts so they ride the next ordinary envelope —
                // a repeated disclosure is cheaper than a silent erase.
                if (r.stored && r.durability ==
                        com.tracescout.mobile.queue.EncryptedEventStore.Durability.COMMITTED) {
                    settlePreF6Dropped(rec.eventId)
                } else {
                    returnPreF6Dropped(rec.eventId)
                }
            } catch (t: Throwable) {
                RecoveryTrace.log("enqueue_threw",
                    "code" to t.javaClass.simpleName,
                    "eventType" to rec.eventType, "sequence" to rec.sequence)
                settleAppend(rec.sequence, false, com.tracescout.mobile.queue.EncryptedEventStore.Durability.NOT_COMMITTED)
                returnPreF6Dropped(rec.eventId)
            }
        }
    }

    private fun settleAppend(sequence: Long, stored: Boolean, durability: com.tracescout.mobile.queue.EncryptedEventStore.Durability) {
        val h = authority ?: return
        val t = tickets.remove(sequence) ?: return
        when {
            stored && durability == com.tracescout.mobile.queue.EncryptedEventStore.Durability.COMMITTED -> h.settleAdmitted(t)
            !stored -> h.settleLost(t, com.tracescout.mobile.internal.loss.LossAuthority.Cause.APPEND_REFUSED)
            else -> RecoveryTrace.log("authority_settle_deferred", "sequence" to sequence, "durability" to durability.name)
        }
    }

    private fun installCrashHandler() {
        val id = identity ?: return
        val s = store ?: return
        CrashHandler.install(s, afterAppend = { stored, durability ->
            val q = crashTicketSequence
            crashTicketSequence = -1L
            // THE SAME THREE-STATE SETTLEMENT AS EVERY OTHER APPEND (SF-5).
            // A crash record whose append reported `stored` with an
            // INDETERMINATE directory entry may be on disk; settling it LOST
            // here was a durable false loss for a record the next launch
            // would upload. `settleAppend` defers that state, so the reopen
            // decides it from what is genuinely on disk.
            if (q >= 0) settleAppend(q, stored, durability)
        }) { thread, throwable ->
            // Built at crash time from ALREADY-BOUNDED inputs: class name and
            // frame strings. The exception MESSAGE is deliberately excluded —
            // application code interpolates values into messages, and a crash
            // report is not an exemption from the privacy model.
            Envelope.crash(
                id = id,
                // F2: claimed durably even at crash time — the record exists
                // to be delivered on the next launch, so its number must be
                // owned across the death. A refused claim THROWS: CrashHandler
                // catches, chains the host handler, and nothing is appended
                // with a number nobody owns (traced first, so the next launch
                // can read why).
                sequence = admit("mobile_crash").also { q ->
                    if (q < 0) {
                        RecoveryTrace.log("crash_admission_refused")
                        throw IllegalStateException("crash_admission_refused")
                    }
                    crashTicketSequence = q
                },
                monotonicNanos = clock.monotonicNanos(),
                posture = posture(),
                exceptionClass = throwable.javaClass.name,
                frames = throwable.stackTrace.take(50).map {
                    "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                },
                threadName = thread.name,
                clock = clock,
            ).bytes
        }
    }

    @Volatile private var crashTicketSequence: Long = -1L

    /**
 * A per-install identifier stored in the SDK's own private prefs.
 *
 * Deliberately NOT `ANDROID_ID`, not the advertising id, not a hardware
 * identifier: those are cross-app trackers, and the SDK has no need to
 * correlate a user across applications. Uninstall clears it, which is the
 * correct lifetime for a correlation handle.
 */
    private fun installId(app: Context): String {
        val prefs = app.getSharedPreferences("tracescout_mobile", Context.MODE_PRIVATE)
        prefs.getString("aid", null)?.let { return it }
        val v = "and_" + java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("aid", v).apply()
        return v
    }

    /**
 * Every public entry point runs inside this.
 *
 * Returning null on failure rather than rethrowing is the whole contract:
 * the host app must be unable to tell that TraceScout had a bad day.
 */
    private inline fun <T> guard(op: String, body: () -> T): T? = try {
        body()
    } catch (t: Throwable) {
        try { android.util.Log.w("TraceScout", "mobile $op failed: ${t.javaClass.simpleName}") }
        catch (_: Throwable) {}
        null
    }

    /**
 * DESTROY THE SDK'S STORED KEY MATERIAL — the product teardown path
 * (F3-P0 the specification H3).
 *
 * A host app calls this to make everything TraceScout has stored on this
 * device permanently unreadable: the event queue, both park stores, every
 * visual frame, the per-session authority snapshots and WALs, and the
 * durable session floors. It is the SDK's answer to an erasure request, a
 * sign-out that must leave nothing behind, or an opt-out.
 *
 * **IT DESTROYS BOTH KEY DOMAINS, AND THAT IS NOT A CHOICE THIS FUNCTION
 * MAKES.** the specification put the queue and the authority in separate Keystore aliases,
 * so destroying one alone leaves an install whose queue is unreadable while
 * its authority still answers — a state nothing is designed to reopen.
 * `StoreTeardown` is the one home that performs both, and the two
 * per-domain destroys are `internal` to it, so half a teardown is not
 * something any caller can express.
 *
 * **IRREVERSIBLE. WHAT IT DOES TO FILES, EXACTLY.**
 *
 * Destroying the queue key makes every byte in the queue, both park stores
 * and the RN frame store permanently unreadable. `EncryptedEventStore` then
 * REFUSES every destructive rewrite — `removeFirst`, eviction and
 * compaction all stop on `untrustworthy_read`, deliberately, because
 * rewriting from records it failed to read would destroy them — so those
 * stores would accept appends only until they reached their byte cap and
 * then **fail permanently**. A teardown that stopped at the key would be a
 * guaranteed forward-progress loss on any install that already had stored
 * data, and the earlier wording here ("it does not delete files … a later
 * `start` will simply be unable to read anything written before this
 * call") understated that to the point of being wrong.
 *
 * So, **only if the queue key was actually destroyed**, the now-
 * unrecoverable queue-key-backed files are REMOVED, and future capture
 * makes progress under a regenerated key. See
 * [StoreTeardown.Outcome.forwardProgressRestored].
 *
 * **THE AUTHORITY'S OWN DURABLE STATE IS NOT GENERICALLY CLEARED.**
 * `session-floors.bin`, the per-session snapshots and the WALs leave ONLY
 * through the flow's U-7b (`LossAuthority.eraseAuthorityHome`, gated on the
 * destroyed authority key); `LossAuthority.destroyAll` has NO production
 * caller at all since F4-P2 C3 (the public reset below goes through this
 * same flow), because a bare deletion of the authority home destroys the
 * durable sequence floor a reused session id depends on (RULING A1).
 *
 * **IT STILL DOES NOT STOP CAPTURE, END A SESSION OR RESET IN-PROCESS
 * STATE.**
 *
 * PARTIAL FAILURES ARE SURFACED, never folded into one optimistic boolean:
 * the result reports each key domain separately and reports the cleanup as
 * its own typed value. Never throws into the host app.
 */
    @JvmStatic
    fun destroyStoredKeyMaterial(context: Context): StoreTeardown.Outcome =
        destroyStoredKeyMaterial(
            runCatching { context.applicationContext.filesDir }.getOrNull(),
            StoreTeardown.KeyDestroyers.platform,
        )

    /**
 * The same teardown using the files directory remembered at [start].
 *
 * **PREFER THE [Context]-TAKING OVERLOAD.** If the SDK was never started
 * in this process there is no remembered directory, so the keys are still
 * destroyed and the cleanup is reported `NotAttempted("files_dir_unavailable")`
 * an honest partial result rather than a silent one, but a partial result
 * all the same.
 */
    @JvmStatic
    fun destroyStoredKeyMaterial(): StoreTeardown.Outcome =
        destroyStoredKeyMaterial(appContext?.filesDir, StoreTeardown.KeyDestroyers.platform)

    /**
 * The same path with the platform calls injectable, so the specification H3 can be
 * PROVEN rather than asserted. `AndroidKeyStore` does not exist on the JVM,
 * so a test driving the public entry point would exercise the destroys'
 * failure path and could not see whether both were invoked — which is the
 * whole property.
 *
 * **`internal`, AND SO IS [StoreTeardown.KeyDestroyers].** The
 * predecessor exposed both, so any caller could hand in two no-op lambdas
 * and be told `complete = true` about an erasure that deleted nothing. The
 * two public entry points above take no destroyers at all.
 */
    internal fun destroyStoredKeyMaterial(
        filesDir: java.io.File?,
        destroyers: StoreTeardown.KeyDestroyers,
    ): StoreTeardown.Outcome =
        guard("destroyStoredKeyMaterial") {
            if (filesDir == null) {
                // No directory: there is no queue domain to fence and no
                // evidence on disk this process can name — the documented
                // partial result (keys destroyed, cleanup NotAttempted).
                StoreTeardown.destroyAllKeyMaterial(filesDir, destroyers)
            } else {
                // F4-P0 Slice D (D6, RF4-50): THE GOVERNED TEARDOWN, NEVER
                // THE BARE ONE. U-1 writes the durable request; U-3 takes the
                // queue fence; U-4/U-5 conclude (or terminally transition) any
                // unresolved bracket under it; only then do the keys go. A
                // fence another owner holds is a typed `erasure_pending` —
                // nothing destroyed, resumed at the next open.
                erasureOutcome(governedErasure(filesDir, destroyers))
            }
        } ?: StoreTeardown.Outcome(
            queueDestroyed = false, authorityDestroyed = false,
            cleanup = StoreTeardown.Cleanup.NotAttempted("teardown_threw"),
        )

    /**
 * THE ONE GOVERNED ERASURE SITE of this producer (F4-P0 Slice D D6 /
 * RF4-50; F4-P2 C3). U-1 writes the durable request; U-3 takes the queue
 * REGION then the queue FENCE under `TEARDOWN`; U-4 / U-5 conclude — or
 * terminally transition — any unresolved bracket under it; only then do the
 * keys go, then the queue-key files, the markers and the reconciliation
 * facts beside them, then the authority home, then the request. Reached by
 * BOTH destructive entries, [destroyStoredKeyMaterial] and [resetForTest],
 * so there is exactly one destructive lifecycle and never a second one.
 * [faults] is the flow's inert seam ([com.tracescout.mobile.queue.ErasureFlow.NO_FAULTS]
 * in production).
 */
    internal fun governedErasure(
        filesDir: java.io.File,
        destroyers: StoreTeardown.KeyDestroyers,
        faults: (String) -> Unit = com.tracescout.mobile.queue.ErasureFlow.NO_FAULTS,
    ): com.tracescout.mobile.queue.ErasureFlow.Outcome {
        // `internal`, like every other function that takes the proof-seam destroyers: no public overload may
        // let a caller hand in two no-op destroyers and be told the install was erased.
        return com.tracescout.mobile.queue.ErasureFlow.erase(filesDir, erasureHomeOf(filesDir), faults) { StoreTeardown.destroyAllKeyMaterial(filesDir, destroyers) }
    }

    /** The product-facing outcome of a governed erasure: the teardown's own, or a typed pending. */
    private fun erasureOutcome(o: com.tracescout.mobile.queue.ErasureFlow.Outcome): StoreTeardown.Outcome = when (o) {
        is com.tracescout.mobile.queue.ErasureFlow.Outcome.Completed -> o.teardown
        is com.tracescout.mobile.queue.ErasureFlow.Outcome.Incomplete -> o.teardown
        is com.tracescout.mobile.queue.ErasureFlow.Outcome.Pending -> StoreTeardown.Outcome(
            queueDestroyed = false, authorityDestroyed = false,
            cleanup = StoreTeardown.Cleanup.NotAttempted("erasure_pending:" + o.reason),
        )
        is com.tracescout.mobile.queue.ErasureFlow.Outcome.NotRequested -> StoreTeardown.Outcome(
            queueDestroyed = false, authorityDestroyed = false,
            cleanup = StoreTeardown.Cleanup.NotAttempted("erasure_not_requested"),
        )
    }

    /**
 * What a reset did (F4-P2 C3). `internal`: the SDK's own rows read it; a
 * fixture or UAT reader inspects the governed result through the durable
 * `RecoveryTrace` line `reset_erasure` — the public `Unit` signature of
 * [resetForTest] is unchanged.
 */
    internal sealed class ResetOutcome {
        /**
 * No install directory is remembered (the SDK was never started in this
 * process): there is nothing durable this process can name, so only the
 * in-process state is released — exactly what the reset did before C3
 * for a never-started process.
 */
        object NoDomain : ResetOutcome()

        /** The governed erasure ran; [outcome] is the flow's own typed answer. */
        data class Governed(val outcome: com.tracescout.mobile.queue.ErasureFlow.Outcome) : ResetOutcome()

        /** The flow threw. Whatever it wrote durably stays for the next open; nothing in-process is released. */
        data class Threw(val error: String) : ResetOutcome()

        /** True ONLY when the in-process state (home, handles, crash handler, configuration) was released. */
        val released: Boolean
            get() = this is NoDomain ||
                (this is Governed && outcome is com.tracescout.mobile.queue.ErasureFlow.Outcome.Completed)
    }

    @Volatile internal var lastResetOutcome: ResetOutcome? = null

    /**
 * Test/teardown hook — **PUBLIC, AND SHIPPED IN THE RELEASE AAR** (F4-P2
 * C3; the reviewer's visibility check of 2026-10-02): a `@JvmStatic` member
 * of a public object with no `explicitApi`, no `@RestrictTo` and empty
 * consumer rules is production-reachable by any host app, whatever its name
 * or this comment say. It is therefore a WHOLE-INSTALL DESTRUCTIVE path and
 * it runs through the governed TEARDOWN — the same `ErasureFlow` as
 * [destroyStoredKeyMaterial], via the one [governedErasure] site — and
 * through nothing else: no `store.clear`, no `LossAuthority.destroyAll`,
 * no home forgotten before the durable teardown has completed.
 *
 * So a reconciliation fact that binds a queue, the authority evidence the
 * reconciliation needs, and the queue itself leave only under the queue
 * region + fence, after recovery and the terminal transition, together with
 * the keys — or not at all. A fence another owner holds, a key destroy that
 * fails, an incomplete cleanup: each is the flow's typed `Pending` /
 * `Incomplete`, the durable request stays, the next `start` resumes it,
 * and NOTHING in-process is released (the home, the authority handles, the
 * crash handler and the configuration all stay), so no durable state is
 * hidden behind a forgotten home. There is deliberately NO top-level
 * precheck of the reconciliation predicate here: the decision is taken
 * under the same lock order a reconciliation holds while it writes its fact,
 * so a fact that becomes durable after an "open" reading cannot have its
 * queue or its authority destroyed underneath it (no TOCTOU).
 *
 * Once the governed teardown has COMPLETED, the in-process state is released
 * exactly as before. The signature is unchanged on purpose; the governed
 * result is traced (`reset_erasure`) and kept in [lastResetOutcome].
 */
    @JvmStatic
    fun resetForTest() {
        resetGoverned(appContext?.filesDir, StoreTeardown.KeyDestroyers.platform)
    }

    /**
 * The reset with the platform calls injectable — the the specification H3 proof seam
 * [destroyStoredKeyMaterial] already has, for the same reason:
 * `AndroidKeyStore` does not exist on the JVM, so the public entry can only
 * ever reach the `Incomplete` branch there — and with the flow's inert fault
 * seam. `internal`, so no caller outside the module can hand in two no-op
 * destroyers and be told the install was erased.
 */
    internal fun resetGoverned(
        filesDir: java.io.File?,
        destroyers: StoreTeardown.KeyDestroyers,
        faults: (String) -> Unit = com.tracescout.mobile.queue.ErasureFlow.NO_FAULTS,
    ): ResetOutcome {
        // 1. Stop capture in THIS process. In-memory only, no durable effect; nothing
        // further is admitted toward the domain about to be torn down.
        started.set(false)
        synchronized(lock) {
            pending.clear(); segmentPostures.clear()
            preF6DroppedFamilies.clear(); preF6DroppedInFlight.clear()
            lastBrowserPrivacyStatus = null
        }
        // 2. The governed teardown — the ONLY destruction of durable state on this path.
        val outcome: ResetOutcome = if (filesDir == null) ResetOutcome.NoDomain else try {
            ResetOutcome.Governed(governedErasure(filesDir, destroyers, faults))
        } catch (t: Throwable) {
            if (t is com.tracescout.mobile.internal.queue.DurableUploadPipeline.SimulatedProcessDeath) throw t
            ResetOutcome.Threw(t.javaClass.simpleName)
        }
        lastResetOutcome = outcome
        val kind: String = when (outcome) {
            is ResetOutcome.NoDomain -> "no_domain"
            is ResetOutcome.Governed -> outcome.outcome.javaClass.simpleName
            is ResetOutcome.Threw -> "threw"
        }
        val why: String? = when (outcome) {
            is ResetOutcome.Governed -> when (val o = outcome.outcome) {
                is com.tracescout.mobile.queue.ErasureFlow.Outcome.Pending -> o.reason.take(40)
                is com.tracescout.mobile.queue.ErasureFlow.Outcome.Incomplete -> o.reason.take(40)
                else -> null
            }
            is ResetOutcome.Threw -> outcome.error.take(40)
            is ResetOutcome.NoDomain -> null
        }
        com.tracescout.mobile.queue.RecoveryTrace.log("reset_erasure", "outcome" to kind, "why" to why, "released" to outcome.released)
        // 3. The in-process release — ONLY after the governed teardown COMPLETED (or
        // when there is no domain at all). On Pending / Incomplete / Threw the home,
        // the authority handles, the crash handler and the configuration all stay:
        // the durable request is owed a resume at the next open, and a forgotten
        // home would hide exactly the state that resume needs.
        if (!outcome.released) return outcome
        CrashHandler.uninstall()
        // A test that tears keys down and starts again in ONE process must not
        // be handed the previous start's home (and its store over a destroyed
        // key). Homes only — the lock registries are deliberately untouched.
        home = null
        bracket = null
        lastStorageRecovery = null
        com.tracescout.mobile.queue.QueueHome.forgetForTest()
        tickets.clear(); crashTicketSequence = -1L; authority = null
        com.tracescout.mobile.internal.loss.LossAuthority.closeAll()
        identity = null
        lastResult = null
        lastFrameResult = null
        lastFrameRefusal = null
        return outcome
    }
}

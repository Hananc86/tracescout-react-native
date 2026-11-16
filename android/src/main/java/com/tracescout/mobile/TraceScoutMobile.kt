package com.tracescout.mobile

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import com.tracescout.mobile.capture.CrashHandler
import com.tracescout.mobile.capture.ViewHierarchySnapshotter
import com.tracescout.mobile.capture.incident.IncidentPolicy
import com.tracescout.mobile.capture.interaction.HostInteraction
import com.tracescout.mobile.capture.interaction.InteractionProducer
import com.tracescout.mobile.core.Clock
import com.tracescout.mobile.core.Ids
import com.tracescout.mobile.core.SystemClock
import com.tracescout.mobile.emit.Envelope
import com.tracescout.mobile.identity.Identify
import com.tracescout.mobile.identity.IdentityPolicy
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

    /**
 * How a process that was woken ONLY to deliver obtains the endpoint and
 * credential to deliver with.
 *
 * **THE SDK PERSISTS NOTHING FROM THIS, AND THAT IS THE WHOLE POINT.**
 * The alternative — the SDK writing the ingest key into its own store so a
 * woken process can read it back — makes the SDK the owner of a credential's
 * storage, lifetime and rotation on the customer's device. That is a
 * credential-durability policy, and it is the host's to set, not ours. So
 * the descriptor is PULLED at the moment of need and dropped again: there is
 * no new secret store, no raw-token persistence, and nothing on disk to
 * rotate or leak. A host that keeps its key in its own Keystore, derives it
 * from a signed-in session, or declines to make it available outside a
 * foreground app can all express exactly that here.
 *
 * **RETURNING `null` IS A SUPPORTED ANSWER, NOT A FAILURE.** It means
 * "not in this process", and the pending records stay exactly where they
 * are — retained, accounted, and offered to the next attempt. It never
 * quarantines, never deletes and never reports a loss, because nothing was
 * lost: the bytes are intact and undelivered.
 *
 * **CALLED ON A WORKMANAGER BACKGROUND THREAD, WITH NO ACTIVITY AND NO
 * SESSION.** It must not block for long, must not touch the UI, and must not
 * assume any foreground state. It may be called when the app was never
 * opened by the user at all.
 *
 * The returned [Config] is used for DELIVERY ONLY. Nothing in it starts a
 * session, and the capture-shaping fields it also carries
 * (`maxQueuedEvents`, `captureMode`) are irrelevant on this path because the
 * bootstrap captures nothing.
 */
    fun interface PendingUploadResolver {
        /** The descriptor to deliver with, or `null` to refuse this process. */
        fun resolve(context: Context): Config?
    }

    @Volatile private var pendingUploadResolver: PendingUploadResolver? = null

    @Volatile private var config: Config? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var identity: Envelope.SessionIdentity? = null

    /**
 * When the LIVE session began, on the MONOTONIC clock — the anchor
 * `mobile_session_end.durationMs` is measured from.
 *
 * MONOTONIC, NEVER WALL. The wall clock may jump backwards (NTP) and the
 * protocol says so in `envelopeFields`' own note; a wall-derived duration
 * could be negative, and the schema's `durationMs` has `minimum: 0`, so the
 * server would refuse an otherwise good event for a reason that is the
 * device's clock rather than the session.
 *
 * NULL IS A STATE, NOT A ZERO. It means "this process did not start the
 * live session", so the duration is unknown and is OMITTED from the wire
 * rather than written as 0. Session-scoped: seeded in [start] and released
 * with the rest of the per-session state.
 */
    @Volatile private var sessionStartedMono: Long? = null

    /**
 * WHICH call claimed the live session's one `mobile_session_end` — `null`
 * when the claim is free.
 *
 * ONE END PER SESSION, AND THIS IS WHY. A session with two end events
 * contradicts itself on the wire: the renderer has two closing instants and
 * two durations for one session, and the server resolves conflicting
 * envelope facts by HIGHEST SEQUENCE, so the second would simply win —
 * making a repeat call silently REWRITE the end rather than be ignored. A
 * compare-and-set rather than a check-then-set because two host threads
 * declaring the end at once is an ordinary race.
 *
 * **A TOKEN, NOT A BOOLEAN, AND THE DIFFERENCE IS A REAL CROSS-SESSION
 * RACE (found in review).** A boolean is released by VALUE, so a release
 * cannot tell whose claim it is cancelling. Nothing locks
 * [recordSessionEnd] against [start], so this interleaving was reachable:
 *
 * ```
 * 1 A's call claims the latch for session A and reads `identity`
 * 2 start installs session B and clears the latch
 * 3 B's recordSessionEnd claims it and EMITS B's end
 * 4 A's admission is refused and A clears the latch — B's claim
 * 5 B's next recordSessionEnd claims again and emits a SECOND end for B,
 * which by highest-sequence resolution REPLACES B's real end instant
 * ```
 *
 * With a token, step 4 is a `compareAndSet(A's token, null)` that FAILS
 * against B's token, so a refused admission can only ever release its own
 * claim. The token carries the session id so a trace or a debugger says
 * whose end is claimed, and a UUID so two calls in one session cannot share
 * one.
 *
 * Session-scoped: cleared to `null` in [start] for the new session, so one
 * process may end one session and legitimately end the next.
 */
    private val sessionEndClaim = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /**
 * The ONE lock under which [identity] is REPLACED.
 *
 * IT EXISTS BECAUSE G2 MADE THE IDENTITY MUTABLE MID-SESSION. Until
 * `identify` there was exactly one writer — `start`, behind
 * `started.compareAndSet` — so a `@Volatile` reference was sufficient and
 * no lock was needed. `identify` is a read-modify-write (`copy(userId =
 * …)`), and a read-modify-write on a volatile is not atomic: a `start`
 * racing it could have its brand-new session identity overwritten by a copy
 * of the OLD one, which would resurrect a dead session id on every
 * subsequent envelope.
 *
 * THERE ARE THREE WRITERS, NOT TWO, AND THE THIRD IS WHY THIS KDOC WAS
 * WRONG. `start`, `identify` **and `resetGoverned`** all replace
 * [identity], and `resetGoverned`'s `identity = null` was outside the lock
 * while this paragraph claimed "both writers take it". That is not a
 * documentation slip, it is a reachable race with a public entry point:
 * `resetForTest` is shipped `@JvmStatic`, so a host can call it, and the
 * interleaving is
 *
 * 1. `identify` takes the lock and reads a non-null `live`;
 * 2. `resetGoverned` sets `identity = null` OUTSIDE the lock;
 * 3. `identify` writes `live.copy(userId = …)`.
 *
 * The torn-down identity is live again, `hasActiveSession` reports true,
 * and every later envelope carries the DEAD session id — the exact
 * resurrection the lock was introduced to prevent, arriving by the one
 * writer it did not cover. All three now take it.
 *
 * `start` holds it for one assignment (it builds the value first), so the
 * cost is a single uncontended lock once per session, and nothing inside
 * any of the three critical sections can block: each body does nothing but
 * read or replace [identity], which keeps this a LEAF lock and makes a
 * lock-ordering deadlock unrepresentable.
 */
    private val identityWriteLock = Any()
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

    /** Which entry point published the live transport. LIVE outranks BOOTSTRAP. */
    private enum class TransportOwner { LIVE, BOOTSTRAP }

    /**
 * The lifecycle lock for TRANSPORT PUBLICATION ONLY.
 *
 * **NO HOST CALLBACK AND NO I/O IS EVER HELD UNDER IT.** The resolver is
 * another app's code and may block for as long as it likes; the queue open,
 * the erasure resume, the WorkManager enqueue and every request happen
 * outside. All this lock serialises is the bracket/commit bookkeeping
 * below, which is a handful of reference stores.
 */
    private val lifecycleLock = Any()

    /**
 * Who owns the transport right now, or null before either entry point has
 * claimed it. Read and written only under [lifecycleLock].
 */
    private var transportOwner: TransportOwner? = null

    /**
 * **A SEQLOCK OVER THE TRANSPORT QUADRUPLE, AND THE REASON IT IS NOT A
 * MUTEX.** `config`, `uploader`, `home` and `appContext` are four
 * independent `@Volatile` fields that a drain must read as ONE fact, and
 * `start` assigns them ~200 lines apart (`config` early, `uploader`
 * last) with the queue open, the erasure resume and the loss authority in
 * between. Holding a lock across that is holding it across I/O, which is
 * exactly what must not happen here, and deferring all four to one commit
 * would rewrite `start`'s initialization order — the order
 * `SliceDSourceTest.SD6` pins.
 *
 * So writers BRACKET their group with a bump instead, and a reader that
 * sees the counter move across its own four reads knows the quadruple may
 * be torn and REFUSES rather than posting a mixture. Refusing retains:
 * the records stay queued and the next drain reads a settled transport.
 *
 * **ODD MEANS A PUBLICATION IS IN FLIGHT, AND LEAVING THAT OUT WAS A
 * REAL DEFECT.** A first version of this counter did not encode it, on the
 * reasoning that "a reader only ever asks *did this change under me*, never
 * *is a change in flight*". That reasoning is wrong here, and the review
 * said so precisely: those controls *"do not serialize a later start's
 * multi-field publication against drain readers"*. `start`'s window is
 * ~200 lines with the queue open, the erasure resume and the loss authority
 * inside it, so a reader whose four reads land ENTIRELY between the open
 * and the close sees an UNCHANGED counter and happily uses a half-published
 * quadruple — a guard satisfied by the very harm it exists to refuse.
 *
 * So the open makes it ODD and the close makes it EVEN, and a reader
 * refuses an odd value outright as well as any value that moves under it.
 *
 * **AN ABORTED PUBLICATION MUST RESTORE PARITY OR IT STALLS EVERY LATER
 * DRAIN.** An unmatched open does not merely leave readers conservative for
 * one more read: it inverts parity for good, because the NEXT open would
 * take an odd value to even. [abortTransportChange] exists for exactly the
 * paths that leave the window without finishing — the two erasure refusals
 * and, through `guard`'s own catch, every exception path.
 */
    @Volatile private var transportEpoch: Long = 0

    /**
 * Open a transport change. Every writer of the quadruple calls this BEFORE
 * its first assignment and [endTransportChange] after its last. Leaves the
 * counter ODD: a publication is in flight.
 */
    private fun beginTransportChange(owner: TransportOwner) = synchronized(lifecycleLock) {
        transportOwner = owner
        transportPublisher = Thread.currentThread()
        if (transportEpoch % 2L == 0L) transportEpoch++ else transportEpoch += 2L
    }

    /** Close a transport change opened by [beginTransportChange]. Leaves it EVEN. */
    private fun endTransportChange() = synchronized(lifecycleLock) {
        if (transportEpoch % 2L != 0L) transportEpoch++ else transportEpoch += 2L
        transportPublisher = null
    }

    /**
 * The thread currently inside a publication window, so [abortTransportChange]
 * can only ever close a window THIS thread opened.
 *
 * WITHOUT THAT OWNERSHIP TEST THE REPAIR BECOMES THE DEFECT. The abort
 * is reached from `guard`'s catch, which every public entry point shares, so
 * an unowned version would let an unrelated `screen` or `flush` failing
 * on another thread close a LIVE `start`'s window — turning its odd
 * counter even while six fields are still half-published, which is exactly
 * the torn read this whole mechanism exists to refuse. Measured before
 * wiring that catch: `start`'s window contains no nested guard-wrapped
 * call (the four `identify` mentions inside it are prose), so the only
 * same-thread abort reaching it is its own failure.
 */
    private var transportPublisher: Thread? = null

    /**
 * Abandon a publication that will never finish, restoring EVEN parity so
 * later drains are not refused for ever. A no-op unless THIS thread has a
 * window open, so it is safe to call unconditionally; idempotent.
 */
    private fun abortTransportChange() = synchronized(lifecycleLock) {
        if (transportEpoch % 2L != 0L && transportPublisher === Thread.currentThread()) {
            transportEpoch++
            transportPublisher = null
        }
    }

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
        val sid = sessionId ?: Ids.newSessionId(clock)
        val pkg = app.packageName
        val pInfo = try {
            app.packageManager.getPackageInfo(pkg, 0)
        } catch (t: Throwable) { null }

        val metrics = app.resources.displayMetrics
        // S5/an earlier change: a new session id makes every prior WebView binding stale —
        // it names a session that is over. Cleared HERE so correctness never
        // depends on the host remembering `detachWebViewSegment`.
        com.tracescout.mobile.capture.WebViewAttachment.detachAll()
        // BUILT FIRST, PUBLISHED UNDER THE LOCK. `identify` replaces this
        // reference with a copy of itself (G2), and a read-modify-write racing
        // a bare assignment here could overwrite a brand-new session identity
        // with a copy of the OLD one — resurrecting a dead session id on every
        // later envelope. `identityWriteLock` is the one place [identity] is
        // replaced; this holds it for a single assignment.
        //
        // `userId` is DEFAULTED, i.e. a new session starts ANONYMOUS. See
        // `identify` for why the SDK deliberately does not carry an identity
        // across sessions.
        val newIdentity = Envelope.SessionIdentity(
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
                // `DisplayMetrics.density` is px-per-dp — the quantity the
                // protocol calls `pixelRatio`. See Envelope.Device.pixelRatio.
                pixelRatio = metrics.density.toDouble(),
            ),
        )
        synchronized(identityWriteLock) { identity = newIdentity }
        // The session-end anchor and latch are PER SESSION, seeded in the same
        // band as the interaction route below and for the same reason: a value
        // left over from the previous session is worse than no value. The anchor
        // is read before `recoverInherited` runs, so a long recovery cannot be
        // charged to this session's duration.
        //
        // THE LATCH IS CLEARED, NOT ASSUMED CLEAR. `start` is reachable
        // again after `resetForTest`/`resetGoverned` AND after a teardown that
        // did not release (`!outcome.released` returns early), so a process that
        // ended one session must be able to end the next.
        sessionStartedMono = clock.monotonicNanos()
        sessionEndClaim.set(null)
        // an earlier change: the session's CURRENT screen at start is `initialScreen`, so
        // seed the interaction route with it. `InteractionProducer` refuses a
        // gesture with no route (`no_route` — "without a screen there is no
        // replayable subject"), and `interactionScreen` was otherwise written
        // only by routeChanged/captureScreen. MEASURED on a device: every tap
        // and swipe before the host's FIRST navigation was dropped, with the
        // session already naming the screen they happened on. Seeding here
        // invents nothing — it uses the screen the host just named — and a
        // later navigation overwrites it exactly as before.
        interactionScreen = initialScreen.takeIf { it.isNotBlank() && it.length <= 64 }
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
        //
        // EACH TYPED REFUSAL BELOW CLEARS THE IDENTITY AS WELL AS THE
        // LATCH, and releasing the latch alone was a defect review caught
        //. The identity is published ABOVE, before this check, so a
        // refusal that released only `started` left a NON-NULL identity
        // behind — and `identity != null` is what `hasActiveSession` answers
        // and what `identify` gates on, while `started.get` is read
        // NOWHERE. A host whose start was refused was therefore told a session
        // is live, and `identify` answered `Applied` for a session that does
        // not exist.
        //
        // Nothing is destroyed: `config`, `appContext` and `interactionScreen`
        // are deliberately left as they are, because every producer path needs
        // the identity, so clearing the one fact that gates them all is what
        // makes the refusal complete.
        //
        // THE CLEAR IS INLINE IN BOTH BRANCHES RATHER THAN A SHARED HELPER,
        // and that IS measured: `SliceDSourceTest.SD6` — a CERTIFIED F4-P0
        // Slice D guard — pins the LITERAL `started.set(false)` within 240
        // characters of `Outcome.Pending ->`. A `releaseRefusedStart`
        // extraction was written first and SD6 refused it, because the literal
        // was then absent from the branch entirely. Widening an accepted F4
        // pin for a cosmetic refactor is not this change's business.
        //
        // The comment sits HERE rather than inside the branch only because it
        // reads once for both branches. It is NOT required by that window, and
        // two drafts of this note claimed it was — both figures measured
        // wrongly, so here is the measurement instead: SD6 allows 240
        // characters AFTER the `->`, its stripper deletes a comment line's
        // indentation along with its text (≈1 character per line), and as
        // shipped the span is 174 of those 240. A comment of roughly 66 lines
        // inside the branch would break it; 60 provably does not.
        //
        // THIS BUDGET IS ALSO WHY THE TRANSPORT WINDOW OPENS BELOW RATHER
        // THAN ABOVE. A draft put the open after the `started` CAS, which left
        // these two refusals inside it and needed an `abortTransportChange`
        // in each — measured, that spans 213 of the 240 here, and the only
        // other placement (after the latch release) spans 56 of the 40 SD6
        // allows there and FAILS. Both figures are moot now: the window starts
        // after this `when`, so these branches carry no transport statement at
        // all and `IdentifyWiringTest`'s clear-then-latch adjacency is intact.
        //
        // `IdentifyWiringTest` guards the PROPERTY a future third refusal must
        // satisfy — every latch release in this region is immediately preceded
        // by a locked clear — rather than a spelling.
        when (val e = com.tracescout.mobile.queue.ErasureFlow.resumeAtOpen(app.filesDir, erasureHomeOf(app.filesDir))) {
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Pending -> {
                RecoveryTrace.log("start_refused_erasure", "why" to e.reason.take(40))
                synchronized(identityWriteLock) { identity = null }
                started.set(false)
                return@guard null
            }
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Incomplete -> {
                RecoveryTrace.log("start_refused_erasure", "why" to ("incomplete:" + e.reason).take(40))
                synchronized(identityWriteLock) { identity = null }
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
        // THE TRANSPORT GROUP OPENS HERE — AFTER THE ERASURE RESUME, NOT
        // BEFORE IT, AND THAT PLACEMENT IS THE WHOLE REASON A REFUSED START
        // NEEDS NO REPAIR. A first version opened the window immediately after
        // the `started` CAS, which put the two erasure refusals INSIDE it: each
        // then had to abort the publication on its way out, every such exit was
        // one more place a future edit could forget, and the abort landed
        // between the locked identity clear and the latch release — breaking
        // `IdentifyWiringTest`'s invariant that the two are adjacent (measured,
        // not predicted: that row failed with `expected:<0> but was:<2>`).
        //
        // Opening it here is strictly better than any repair: the window now
        // contains ONLY the four assignments and the loss-authority open, it
        // has NO `return@guard` exit at all, and a start refused by the erasure
        // resume leaves the transport EXACTLY as it found it rather than
        // half-overwritten. `appContext` and `config` moved down with it —
        // measured first, nothing between their old sites and here reads any of
        // the six fields, and no function called in that span reads one either
        // (everything uses the `app`/`cfg` locals), so the move is invisible to
        // the code it passed over.
        //
        // Claiming LIVE before the first assignment is what makes a concurrent
        // recovery-only bootstrap refuse instead of overwriting this session's
        // transport; bracketing the group is what stops a drain reading a
        // half-published mixture of the two. Still deliberately AFTER the
        // `started` CAS, so the claim cannot outlive a refused start.
        beginTransportChange(TransportOwner.LIVE)
        appContext = app
        config = cfg
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
        // The transport group is complete — close it BEFORE the recovery
        // generation, the worker drain and the inherited drain below, so every
        // one of them reads a settled quadruple rather than a publication in
        // progress. This sits between the authority and the recovery step and
        // reorders neither, so SD6's erasure < home < authority < recovery <
        // inherited drain < first admission is unchanged.
        //
        // An erasure refusal above returns WITHOUT closing the group, and
        // that is left alone deliberately: it keeps `uploader` null (so a drain
        // refuses on the quadruple anyway) and it resets `started`, which is
        // the signal the bootstrap actually tests. An unmatched open only makes
        // readers more conservative for one more read; it can never make one
        // less so.
        endTransportChange()
        RecoveryCoordinator.processGeneration(app)
        // The deferred drain runs the SAME state machine — snapshot a prefix,
        // validate the ack, remove only what was acknowledged. There is no
        // second upload path to keep in step.
        installWorkerDrain()

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
 * Install the ONE deferred-drain state machine on [RecoveryWorker].
 *
 * **EXTRACTED SO THERE IS EXACTLY ONE OF IT.** Two callers install it
 * now — [start], and the recovery-only bootstrap a woken process takes —
 * and a second copy of this closure is the one thing that must not exist:
 * it decides what `left`, an unreadable queue and a terminal result owe
 * WorkManager, and two versions free to disagree is how a retained
 * obligation becomes a discarded one. The body is unchanged.
 */
    private fun installWorkerDrain() {
        RecoveryWorker.drainHook = { ctx ->
            if (!RecoveryCoordinator.acquire(ctx)) RecoveryWorker.Outcome.RETRY
            else try {
                // UNREADABLE IS NOT EMPTY, AND THIS WAS THE THIRD SITE OF
                // THAT DEFECT IN THIS FILE. `readAll` does NOT throw on a
                // store it cannot open: `EncryptedEventStore` RETURNS a
                // ReadResult carrying `store_unreadable` with an empty record
                // list. So `records.isEmpty` was true for a queue whose bytes
                // are intact and UNREAD, this hook answered `Outcome.DONE`,
                // `doWork` mapped that to `Result.success`, and WorkManager
                // DELETED the unique drain job — records still on disk, nothing
                // scheduled to deliver them, and no loss accounted. Neither
                // delivered nor accounted is the one outcome the loss contract
                // forbids. `-1` ALREADY means "I do not know" on the startup
                // path, so the same value is used here and the two sites agree
                // rather than differing by omission.
                var reachedPipeline = false
                val read = store?.readAll()
                val unreadable = read == null || read.couldNotBeRead
                val recs = read?.records ?: emptyList()
                val left: Int = if (unreadable) {
                    // NO finality checkpoint on this arm. The zero-chunk
                    // checkpoint asserts the session is over and nothing is
                    // pending, which is precisely the claim an unreadable queue
                    // denies us; the startup UNREADABLE arm refuses it for the
                    // same reason. Nothing is deleted, cleared or repaired —
                    // the bytes are ciphertext this process could not read this
                    // time, not proven garbage.
                    RecoveryTrace.log("worker_queue_unreadable",
                        "class" to (read?.integrityClass ?: "read_threw"))
                    -1
                } else {
                    // F7 R-2 (#2760): drain first, then classify — an EMPTY
                    // drain is the one moment no chunk can carry a disclosure,
                    // and the pipeline owns that decision.
                    reachedPipeline = uploadRaw(recs)
                    if (recs.isEmpty()) 0
                    else store?.readAll().let { r ->
                        if (r == null || r.couldNotBeRead) -1 else r.records.size
                    }
                }
                // F-11: THE DEFERRED DRAIN NOW PUBLISHES ITS OWN VERDICT.
                // `RecoveryCoordinator.status` was assigned ONLY inside
                // `recoverInherited`, so it was a START-TIME word: after an
                // offline relaunch it read RECOVERY_PENDING_NETWORK, this
                // worker then delivered every inherited record once
                // connectivity returned, and the word never moved — so every
                // host-facing surface reported a COMPLETED recovery as still
                // pending.
                //
                // Written under the SAME lease as the startup path, which is
                // what serialises the two writers, and through the one mapping
                // both of them use. An ORDINARY (non-inherited) drain must not
                // MANUFACTURE a recovery that never happened: when nothing was
                // outstanding and nothing is left, RECOVERY_NOT_NEEDED already
                // says so and is left exactly as it is.
                if (left != 0 ||
                    RecoveryCoordinator.status != RecoveryCoordinator.Status.RECOVERY_NOT_NEEDED) {
                    RecoveryCoordinator.status =
                        recoveryStatusAfterDrain(left, reachedPipeline = reachedPipeline)
                }
                RecoveryTrace.log("worker_recovery_exit", "left" to left,
                    "status" to RecoveryCoordinator.status.name)
                when {
                    left == 0 -> RecoveryWorker.Outcome.DONE
                    // "I do not know" retains the obligation. It must never be
                    // DONE (that deletes the job) and never PERMANENT (that
                    // quarantines records this process never read).
                    left < 0 -> RecoveryWorker.Outcome.RETRY
                    // GATED ON reachedPipeline FOR THE SAME REASON THE STATUS
                    // IS. `PERMANENT` becomes `Result.failure`, which stops
                    // WorkManager retrying the unique work — the obligation is
                    // ABANDONED, not retained. Reading an ungated `lastResult`
                    // let a STALE terminal result from an earlier drain abandon
                    // records this pass never uploaded, while the status beside
                    // it said RETRIABLE_FAILURE: two contradictory verdicts from
                    // one pass. Only a terminal result produced by THIS drain
                    // may end the obligation.
                    reachedPipeline &&
                        lastResult?.action == com.tracescout.mobile.transport.ResponseAction.TERMINAL ->
                        RecoveryWorker.Outcome.PERMANENT
                    else -> RecoveryWorker.Outcome.RETRY
                }
            } finally { RecoveryCoordinator.release(ctx) }
        }
    }

    /**
 * Tell the SDK how a woken process may obtain a delivery descriptor.
 *
 * **CALL THIS FROM `Application.onCreate`, NOT FROM AN ACTIVITY — THAT
 * IS THE ENTIRE REASON IT EXISTS.** `Application.onCreate` runs on EVERY
 * process start, including the one WorkManager wakes to run the deferred
 * connectivity-constrained drain; an Activity does not exist in that
 * process and never will. A host that only calls [start] from an Activity
 * therefore has, in exactly the process built to deliver, a durable queue
 * and no way to deliver it. Registering here closes that, and
 * costs nothing: no credential is read, no queue is opened, no session is
 * started and no I/O is performed by this call.
 *
 * Idempotent and last-wins. Registration alone delivers nothing — the
 * resolver is consulted only when [RecoveryWorker] runs AND finds records
 * actually pending.
 *
 * **IT DOES NOT REPLACE [start].** Capture still begins when the host
 * calls [start]; this only lets a delivery happen without one.
 */
    @JvmStatic
    fun registerPendingUploadResolver(resolver: PendingUploadResolver?) {
        guard("registerResolver") {
            pendingUploadResolver = resolver
            RecoveryWorker.bootstrapHook =
                if (resolver == null) null else { ctx -> bootstrapForDelivery(ctx) }
        }
    }

    /**
 * The owning session of a slice, read from the HEAD record's own bytes.
 *
 * Blank when it cannot be read — which `sessionOwnedPrefix` then treats as
 * an unreadable head and bounds to its own single-record slice, so the
 * queue still moves without a readable record behind it being merged under
 * a stand-in address.
 */
    private fun ownerOrBlank(records: List<ByteArray>): String = try {
        org.json.JSONObject(String(records[0], Charsets.UTF_8)).optString("sessionId")
    } catch (_: Throwable) { "" }

    /**
 * Why this descriptor may not be used for delivery — `null` when it may.
 *
 * **A BLANK FIELD IS REFUSED, NEVER DEFAULTED, AND an earlier change IS WHY.** Its
 * second defect was a resolver chain that fell through to
 * `https://api.tracescout.com` when no endpoint was configured, and a
 * relaunched process then POSTed real fixture bytes at PRODUCTION twice.
 * An incomplete descriptor is not a descriptor; the only safe answer is to
 * refuse this process and retain.
 *
 * Pure on purpose: the JVM venue has no `Context`, so this is the part of
 * the bootstrap decision that can be DRIVEN in a unit test rather than
 * asserted at the source.
 */
    internal fun deliveryDescriptorRefusal(cfg: Config): String? = when {
        cfg.projectId.isBlank() -> "descriptor_no_project"
        cfg.ingestKey.isBlank() -> "descriptor_no_key"
        cfg.baseUrl.isBlank() -> "descriptor_no_endpoint"
        else -> null
    }

    /**
 * Refuse to deliver one project's records through another project's
 * credential — `null` when the descriptor provably owns the head record,
 * and also when ownership cannot be read at all.
 *
 * **THIS IS A CROSS-TENANT DISCLOSURE GUARD, NOT A TIDINESS CHECK.**
 * The descriptor decides the upload ADDRESS and the ingest KEY, while the
 * pending records carry their own `projectId` in their own envelopes from
 * the process that captured them. Those two can disagree — a host whose
 * resolver reads whichever project is configured *now* can hand this path a
 * different tenant than the one the queued bytes belong to — and delivering
 * them anyway would disclose project A's captured content to project B's
 * endpoint under project B's credential. The server would likely refuse,
 * but "the server catches it" is not the SDK declining to send it.
 *
 * **A RECORD THAT CANNOT STATE A PROJECT IS SKIPPED, DELIBERATELY** —
 * and the decision passes to the next record that CAN. Refusing what cannot
 * be read would strand a corrupt-head queue for ever on a host that only
 * ever bootstraps, and the pipeline already has a typed answer for an
 * unreadable head (it becomes its own single-record slice). Only a
 * CONFIRMED mismatch refuses: a value that was read, and differs.
 *
 * **IT ASKS ABOUT THE BATCH THAT WOULD ACTUALLY BE SENT, NOT ABOUT
 * `records[0]`, AND THE DIFFERENCE WAS A MEASURED CROSS-TENANT HOLE.** This
 * guard keys on `projectId`; the pipeline's [sessionOwnedPrefix] keys on
 * `sessionId`. Where those two fields come apart they stop agreeing: a head
 * that states a SESSION but no PROJECT is readable to the slicer (so the
 * batch extends over the records behind it) and silent to a head-only guard
 * (so the bootstrap admits). Driven on the real functions, a project-silent
 * head followed by two project-A records delivered all three under project
 * B's credential — the exact disclosure this guard exists to refuse. The
 * predecessor's justification for reading only the head ("the pipeline
 * slices by owner from the head; this guard answers the same question about
 * the same record") is false precisely there, because it is NOT the same
 * question: one asks about the project, the other about the session.
 *
 * **SO IT CALLS THE SLICER RATHER THAN REIMPLEMENTING IT.** Deciding
 * "which records could ride out together" a second time here would give
 * that question two homes free to disagree — the defect class
 * [sessionOwnedPrefix]'s own KDoc warns about. And a guard that merely
 * skipped past an unreadable head would be wrong in the OTHER direction:
 * an unparseable head is its own single-record slice (SF-8), so judging it
 * by a record from a LATER batch refuses a queue whose actual batch is
 * clean — measured, and it stranded exactly the corrupt-head queue the
 * admission below exists to keep moving. The `fallback` passed here is
 * unused by this caller: it only names the returned OWNER for an
 * unparseable head, and the owner is discarded.
 *
 * Deciding on the FIRST record of that batch which states a project is
 * SUFFICIENT rather than merely better, and the producer is why:
 * `Envelope.envelopeFields` stamps `projectId` and `sessionId` from the SAME
 * immutable [SessionIdentity] in one expression list, so every record of one
 * session states the same project or none — and a batch is one session. A
 * foreign project can therefore only appear in a DIFFERENT session, which
 * the slicer already excludes. If that stamping ever split into two sources
 * this reasoning dies with it, so `DeliveryOwnershipCompositionTest` pins
 * the composition and the next edit to either mechanism fails rather than
 * silently reopening the hole.
 */
    internal fun crossProjectRefusal(projectId: String, records: List<ByteArray>): String? {
        if (records.isEmpty()) return null
        val (_, batch) = sessionOwnedPrefix(records, "")
        for (record in batch) {
            val owner = try {
                org.json.JSONObject(String(record, Charsets.UTF_8)).optString("projectId")
            } catch (_: Throwable) { "" }
            if (owner.isBlank()) continue
            return if (owner == projectId) null else "descriptor_project_mismatch"
        }
        return null
    }

    /**
 * Bring up the delivery path ONLY — no session, no capture, no admission.
 *
 * **THIS MINTS NO SESSION, AND THE DIFFERENCE IS A PRODUCT FACT, NOT A
 * STYLE CHOICE.** [start] always emits a `mobile_session_start`, so a host
 * that simply called it from `Application.onCreate` would publish a session
 * for every process the system woke to drain — sessions with no user, no
 * screen and no interaction, in the customer's own session counts. The RN
 * and Flutter transports already draw this line: their native `start`
 * installs the drain and emits no `mobile_session_start` (zero
 * `Envelope.sessionStart` call sites in either), and `:sdk` was the one
 * binding with no such entry point. This is that entry point.
 *
 * So it deliberately does NOT: mint an [identity], open the session-scoped
 * [LossAuthority] (nothing is admitted here, and admission is the only
 * thing that needs it), claim [started], or emit any event. [hasActiveSession]
 * stays false, and a later real [start] in the same process is unaffected.
 *
 * What it DOES do is exactly what the deferred drain needs, in the order
 * [start] established: install the trace, honour an outstanding erasure,
 * open the one queue home, build the uploader, install the one drain
 * closure.
 *
 * @return true only when a usable [RecoveryWorker.drainHook] is now
 * installed. Every refusal is false, and false RETAINS: the caller's
 * no-hook branch keeps the obligation exactly as it did before.
 */
    private fun bootstrapForDelivery(ctx: Context): Boolean = guard("bootstrapForDelivery") {
        val app = ctx.applicationContext
        // FIRST, for the reason RnTransport records: `RecoveryTrace.log` returns
        // early while `dir` is null, and `install` is the only thing that sets
        // it. Without this every checkpoint on this path — including the ones
        // explaining a refusal — is written to nowhere, which is
        // indistinguishable from a path where nothing happened.
        RecoveryTrace.install(app)
        RecoveryTrace.log("delivery_bootstrap_enter")

        // A LIVE SESSION OUTRANKS THIS PATH, AND THIS GUARD DID NOT EXIST.
        // The review that asked for it described it as already present and
        // merely insufficient; measured, `started` and `identity` appeared ZERO
        // times anywhere in this function, so there was nothing to strengthen —
        // a woken worker could reach the host's credential and commit a
        // recovery transport while a session was live. It is placed BEFORE the
        // resolver so the common case costs no credential access at all.
        //
        // IT IS NOT THE FIX BY ITSELF, and that is why the commit below
        // re-checks. A `start` that begins one instruction after this line
        // passes it; only the re-check under the lifecycle lock can refuse a
        // resolver that was still thinking when the session came up.
        if (started.get()) {
            RecoveryTrace.log("delivery_bootstrap_refused", "why" to "live_session")
            return@guard false
        }

        val cfg = pendingUploadResolver?.resolve(app) ?: run {
            // A host declining THIS process is an ordinary answer. Nothing is
            // opened, nothing is read, nothing is lost.
            RecoveryTrace.log("delivery_bootstrap_no_descriptor")
            return@guard false
        }
        deliveryDescriptorRefusal(cfg)?.let { why ->
            RecoveryTrace.log("delivery_bootstrap_refused", "why" to why)
            return@guard false
        }

        // An outstanding erasure outranks a delivery: the records it covers are
        // being destroyed on the user's instruction, and draining them first
        // would defeat it. Pending/Incomplete is a typed refusal of this
        // bootstrap, exactly as it is a typed refusal of `start`.
        when (val e = com.tracescout.mobile.queue.ErasureFlow.resumeAtOpen(app.filesDir, erasureHomeOf(app.filesDir))) {
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Pending -> {
                RecoveryTrace.log("delivery_bootstrap_refused_erasure", "why" to e.reason.take(40))
                return@guard false
            }
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Incomplete -> {
                RecoveryTrace.log("delivery_bootstrap_refused_erasure", "why" to ("incomplete:" + e.reason).take(40))
                return@guard false
            }
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.Completed ->
                RecoveryTrace.log("delivery_bootstrap_erasure_completed", "terminal" to e.terminalTeardown)
            is com.tracescout.mobile.queue.ErasureFlow.Outcome.NotRequested -> Unit
        }

        // The SAME memoized home on the SAME canonical queue file `start` opens
        // never a second store over the same bytes.
        val h = com.tracescout.mobile.queue.QueueHome.of(
            java.io.File(app.filesDir, com.tracescout.mobile.queue.QueueKeyStores.SDK_QUEUE),
        ) { queueFile ->
            EncryptedEventStore(queueFile, com.tracescout.mobile.queue.StoreKeys.queueKey())
        }
        val s = h.store()

        // THE READ IS CLASSIFIED, AND THIS SITE IS WHY THE RULE EXISTS.
        // `EncryptedEventStore.readAll` does NOT throw on a store it cannot
        // open — it returns a ReadResult carrying `store_unreadable` with an
        // EMPTY record list. An unclassified read here therefore could not tell
        // "the queue is empty, so no tenant claims these bytes" from "I could
        // not look", and the second reading would let a descriptor this process
        // never attributed install the drain. Caught by `SliceBSourceTest` S3d
        // on the first run of this change, which is the fourth time this file
        // has paid for that exact conflation.
        //
        // Unreadable REFUSES the bootstrap rather than proceeding: ownership
        // cannot be confirmed, so delivering through a credential we cannot
        // attribute is the one thing this guard exists to prevent. It strands
        // nothing permanently — a real `start`, which carries the host's own
        // authoritative configuration, still delivers.
        val read = s.readAll()
        if (read.couldNotBeRead) {
            RecoveryTrace.log(
                "delivery_bootstrap_refused",
                "why" to "queue_unreadable",
                "class" to read.integrityClass,
            )
            return@guard false
        }

        // SCOPE IS CHECKED BEFORE ANY SINGLETON FIELD IS COMMITTED, so a
        // refusal leaves this process exactly as it found it — no config, no
        // uploader, no hook — rather than half-initialized against a tenant it
        // just declined to serve.
        crossProjectRefusal(cfg.projectId, read.records)?.let { why ->
            RecoveryTrace.log("delivery_bootstrap_refused", "why" to why)
            return@guard false
        }

        // Built BEFORE the lock. Nothing another app wrote, and no constructor
        // at all, runs inside the critical section below.
        val up = Uploader(cfg.baseUrl, cfg.ingestKey)

        // THE RE-CHECK AND THE COMMIT ARE ONE ATOMIC STEP, AND THAT IS THE
        // RACE THIS CLOSES. Everything above — the resolver (another app's
        // code, which may block indefinitely), the queue open, the erasure
        // resume, the classified read — happened unlocked, so a `start` can
        // have come up in the meantime. Committing on the strength of the
        // entry check alone would let a resolver that was still thinking
        // overwrite a live session's transport, and because the six fields are
        // independent it could overwrite only SOME of them: the live session's
        // `config` with this descriptor's `uploader`, i.e. one tenant's records
        // posted to another tenant's endpoint under its key.
        //
        // A LIVE session therefore WINS and this path refuses. The refusal is
        // non-sending and loses nothing: no field is touched, the queue is
        // untouched, and the live `start` — which carries the host's own
        // authoritative configuration — delivers these records itself.
        val committed = synchronized(lifecycleLock) {
            if (started.get()) {
                false
            } else {
                // THE SAME ODD/EVEN BRACKET AS `start`, EXPRESSED LOCALLY
                // RATHER THAN ASSUMED. A bare `transportEpoch++` pair is the
                // protocol only while the counter is EVEN on entry, and the
                // reason it always is here — `start` takes its latch before
                // opening its window, so the `started.get` test above
                // refuses while a live publication is in flight — is a
                // coupling two functions apart. Deriving parity from the
                // counter keeps this block correct on its own terms. Both
                // bumps are inside ONE lock hold, so unlike `start`'s long
                // window no reader can observe the odd value for more than the
                // six assignments between them.
                transportOwner = TransportOwner.BOOTSTRAP
                transportPublisher = Thread.currentThread()
                if (transportEpoch % 2L == 0L) transportEpoch++ else transportEpoch += 2L
                appContext = app
                config = cfg
                parkStoreDir = app.filesDir
                home = h
                store = s
                uploader = up
                if (transportEpoch % 2L != 0L) transportEpoch++ else transportEpoch += 2L
                transportPublisher = null
                true
            }
        }
        if (!committed) {
            RecoveryTrace.log("delivery_bootstrap_refused", "why" to "live_session_commit")
            return@guard false
        }
        // Outside the lock on purpose: the recovery generation and the
        // WorkManager enqueue both touch storage, and the drain closure reads
        // the transport when it RUNS rather than when it is installed.
        RecoveryCoordinator.processGeneration(app)
        installWorkerDrain()
        RecoveryTrace.log("delivery_bootstrap_ready")
        true
    } ?: false

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
        // THE READ IS CLASSIFIED FIRST, AND THE CLASSIFICATION IS A TYPE.
        // See [InheritedRead] — an exhaustive `when` over it is what stops a
        // later state from quietly inheriting a decision nobody made for it.
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
            // A `when` USED AS AN EXPRESSION, DELIBERATELY. In statement
            // position a non-exhaustive `when` over an enum is a warning on
            // some Kotlin versions and an error on others; as an initializer
            // it is a compile error on every one of them. A fourth read state
            // added later therefore cannot silently inherit this one's
            // decision — which is exactly how the unreadable case came to be
            // treated as the empty one.
            val proceed: Boolean = when (classifyInheritedRead(res)) {
                InheritedRead.UNREADABLE -> {
                    // NOT `queue_empty`, NOT `RECOVERY_NOT_NEEDED`, and
                    // ABOVE ALL NOT `RecoveryWorker.cancel(app)`. This process
                    // learned NOTHING about what the queue holds, so every one
                    // of those three would be a statement we cannot make — and
                    // the cancel is the harmful one: it deletes the unique
                    // WorkManager job that is the ONLY scheduled path out for
                    // records that are still on disk. Neither delivered nor
                    // accounted is the one combination the loss contract
                    // forbids; `undeliveredRecordBytes` answers `1L` for a
                    // file it cannot measure for the same reason.
                    //
                    // The zero-chunk finality checkpoint is skipped too: it
                    // asserts the session is over and nothing is pending,
                    // which is precisely the claim an unreadable queue denies
                    // us. Emitting it would turn "I could not look" into a
                    // durable completeness claim.
                    //
                    // NOTHING IS DELETED, CLEARED OR REPAIRED. The bytes are
                    // ciphertext this process could not read this time, not
                    // proven garbage; the next open reads them again.
                    RecoveryTrace.log("queue_unreadable",
                        "class" to (res?.integrityClass ?: "read_threw"))
                    RecoveryCoordinator.status = RecoveryCoordinator.Status.RECOVERY_RETRIABLE_FAILURE
                    // AND THE OBLIGATION IS SCHEDULED, NOT MERELY NAMED.
                    // Review caught this: the paragraph above is right that
                    // cancelling would delete "the ONLY scheduled path out" —
                    // and then ASSUMED that path exists. It exists only if some
                    // earlier process enqueued it. A process that appended
                    // records and died before it did leaves none, so an
                    // unreadable queue on the next launch set a retriable
                    // status that nothing would ever act on: the records stay on
                    // disk with no trigger but another start. That is the same
                    // "neither delivered nor accounted" state this arm exists to
                    // prevent, one branch over, in the fix for it.
                    //
                    // The post-upload site routes the identical "I do not know"
                    // value (`left == -1`) to this same call, so the two sites
                    // now agree rather than disagreeing by omission. It is
                    // `enqueueUniqueWork(KEEP)`, so an existing job is kept and
                    // a repeat launch cannot pile up duplicates.
                    val q = RecoveryWorker.enqueue(app)
                    RecoveryTrace.log(if (q) "worker_enqueued" else "worker_enqueue_failed")
                    // The lease is released for the same reason the empty path
                    // releases it: no IN-PROCESS worker task is taking it on
                    // this return. The WorkManager job acquires its own.
                    RecoveryCoordinator.release(app)
                    false
                }
                InheritedRead.EMPTY -> {
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
                    false
                }
                InheritedRead.PENDING -> true
            }
            if (!proceed) return
            RecoveryTrace.log("queue_pending", "count" to records.size)
            RecoveryTrace.log("inherited_records_selected", "count" to records.size)
            RecoveryCoordinator.status = RecoveryCoordinator.Status.RECOVERY_IN_PROGRESS
            worker.execute {
                try {
                    // THE RESULT IS CAPTURED, NEVER ASSUMED. `uploadRaw`
                    // answers false before it reaches the pipeline when
                    // `config`, `identity`, `uploader` or `home` is null, and on
                    // those exits it does not touch `lastResult`. `resetGoverned`
                    // nulls `home` and `identity` on the calling thread and
                    // clears `lastResult` only LATER in the same function, so a
                    // reset racing this queued worker task leaves exactly that
                    // window — and hardcoding `true` here reported a stale
                    // `auth_rejected` as RECOVERY_AUTH_REJECTED.
                    val reached = uploadRaw(records)
                    // THE SAME DEFECT HAS A SECOND SITE, AND IT IS HERE.
                    // Found by reading this function, not by the finding that
                    // named the first one. After the upload this re-reads the
                    // queue to decide what is LEFT, and an unreadable read
                    // returns an ordinary `ReadResult` whose `records` is
                    // empty — so `left` became 0, which claims
                    // `RECOVERY_COMPLETED` and then calls
                    // `RecoveryWorker.cancel(app)`: the identical harm, one
                    // branch further on, reached after a partially successful
                    // drain rather than at open time.
                    //
                    // No new state is needed, because `-1` ALREADY means "I do
                    // not know" on this path (it is what the throw returns) and
                    // already routes to RECOVERY_RETRIABLE_FAILURE plus
                    // `RecoveryWorker.enqueue` — the obligation is retained.
                    // Folding the classified case into it keeps ONE predicate
                    // and one meaning rather than inventing a second.
                    val left = try {
                        s.readAll().let { if (it.couldNotBeRead) -1 else it.records.size }
                    } catch (_: Throwable) { -1 }
                    RecoveryCoordinator.status =
                        recoveryStatusAfterDrain(left, reachedPipeline = reached)
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

    /**
 * The ONE mapping from "what is LEFT after a drain" to the host-facing
 * recovery status.
 *
 * Both drains use it — the foreground one inside [recoverInherited] and the
 * deferred `RecoveryWorker.drainHook` — because the status is ONE fact and
 * two copies of this `when` would drift. That is not hypothetical: the
 * worker had NO copy at all, which is exactly why the word was start-time
 * only (Cordova F-11, measured on an API-35 emulator by an earlier change — the worker
 * delivered the dead session's records within seconds of airplane mode
 * going off and the status still read RECOVERY_PENDING_NETWORK).
 *
 * **THE ARM ORDER IS LOAD-BEARING AND IS PINNED BY A TEST.** An auth
 * rejection is checked BEFORE the general TERMINAL case: it is terminal for
 * the CREDENTIAL, and the records are still queued rather than quarantined,
 * so a host must be told to replace the key rather than that its data was
 * refused. A transport error is the no-network case in practice; both it and
 * a plain retriable failure defer to the connectivity-constrained worker
 * rather than spinning here and draining the battery.
 *
 * **`lastResult` IS ONLY EVIDENCE ABOUT THIS QUEUE IF THIS DRAIN
 * ACTUALLY REACHED THE UPLOAD PIPELINE, WHICH IS WHY [reachedPipeline] IS A
 * PARAMETER AND NOT DERIVED FROM [left].** `lastResult` describes the most
 * recent upload this process made, whenever that was. A STALE
 * `auth_rejected` published as `RECOVERY_AUTH_REJECTED` becomes
 * `CONFIGURATION_REVOKED` in `FixtureStartup.currentState` — it tells the
 * operator to replace a credential, over a queue this drain never read. A
 * stale `TERMINAL` claims the records were refused. Both are false
 * statements about work that did not happen.
 *
 * **AND "REACHED THE PIPELINE" IS NOT THE SAME AS "THE READ WAS
 * READABLE" — a reviewer caught that, and the path is reachable.** The
 * first version passed `uploaded = !unreadable`, but `uploadRaw` has five
 * early exits: a null `config`, `identity`, `uploader` or `home` reaches no
 * pipeline at all, and so does the typed no-`appContext` refusal. Those are
 * not hypothetical — `TraceScoutMobile` never clears its OWN `drainHook`,
 * while `identity` is nulled inside `start`'s governed-erasure refusal
 * and `home`/`identity` by `resetForTest`, so a hook installed by an
 * earlier `start` can run with null dependencies. `uploadRaw` therefore
 * REPORTS whether it got through, and the worker passes that.
 *
 * It is deliberately NOT `if (left > 0)` either. On the foreground path
 * `left` is `-1` when the POST-UPLOAD re-read was unreadable, and when that
 * drain DID reach the pipeline its `lastResult` is current, so a typed
 * cause is the most informative honest answer; keying on `left` would
 * discard a true auth rejection there. That path states its own
 * [reachedPipeline] from `uploadRaw`'s answer rather than assuming it —
 * a reviewer was right that "the upload happened" is not guaranteed
 * merely because `left` is `-1`.
 *
 * With no admissible result, an unknown outcome retains the obligation: it
 * can never be `RECOVERY_COMPLETED`, because the only safe answer to "may I
 * discard the obligation to deliver" is no.
 */
    private fun recoveryStatusAfterDrain(
        left: Int,
        reachedPipeline: Boolean,
    ): RecoveryCoordinator.Status {
        val last = if (reachedPipeline) lastResult else null
        return when {
            left == 0 -> RecoveryCoordinator.Status.RECOVERY_COMPLETED
            last?.code == "auth_rejected" ->
                RecoveryCoordinator.Status.RECOVERY_AUTH_REJECTED
            last?.action == com.tracescout.mobile.transport.ResponseAction.TERMINAL ->
                RecoveryCoordinator.Status.RECOVERY_PERMANENT_REJECTION
            last?.code == "transport_error" ->
                RecoveryCoordinator.Status.RECOVERY_PENDING_NETWORK
            else -> RecoveryCoordinator.Status.RECOVERY_RETRIABLE_FAILURE
        }
    }

    /**
 * What a start-time read of the inherited queue actually established.
 *
 * **THREE STATES, BECAUSE AN EMPTY `records` MEANS TWO DIFFERENT
 * THINGS AND THEY OWE OPPOSITE ACTIONS.** `recoverInherited` used to ask
 * `records.isEmpty`, which cannot separate *the queue is empty* from
 * *I could not read the queue* — so a store this process could not open
 * was logged `queue_empty`, marked `RECOVERY_NOT_NEEDED`, and its
 * scheduled drain was CANCELLED, with the records still on disk. A
 * boolean had to become a type for the two to stop sharing a branch.
 *
 * [UNREADABLE] is reached two ways and both are real:
 * - the read THREW (`res == null`) — narrow, since the store catches its
 * own failures, but reachable (e.g. a `SecurityException` from the
 * `file.exists` probe, which sits outside its inner `try`);
 * - the read RETURNED a classified `unreadable_file` — the dominant
 * route, because `readAllLocked` deliberately catches a wholesale read
 * failure and returns rather than crashing the host app on launch.
 * The verdict already existed; the old code discarded it by reading
 * `.records` and nothing else.
 *
 * **SCOPED TO `unreadable_file`, NOT TO `!isTrustworthy`** — and that
 * is the load-bearing half. A torn tail, an auth-failed frame and an
 * invalid length prefix all leave `isTrustworthy` false, and for all three
 * the survivors ARE deliverable and the damage IS already accounted in
 * `discarded` / `discardReasons`. Refusing on those would wedge recovery
 * for ever on a store with one bad frame — strictly worse than the defect
 * being removed. `unreadable_file` is the one class that says *this
 * process learned nothing*.
 */
    internal enum class InheritedRead { UNREADABLE, EMPTY, PENDING }

    /**
 * Could this read have MISSED records that are still on disk?
 *
 * **ONE HOME, AND DELIBERATELY NOT ON `ReadResult` ITSELF.** The
 * obvious place for this is the type that already classifies the damage —
 * but `EncryptedEventStore.kt` is pinned byte-identical to an
 * F4-accepted base (`AuthorityOwnershipPinsTest.P1a2-14`), so adding a
 * member there is a governed re-pin of a shared file plus the structural
 * twin that pin demands. A recovery fix in `:sdk` is not the change that
 * should carry an F4 store transition, so the predicate lives in the file
 * that consumes it. The cost is stated rather than hidden: a sibling
 * producer cannot reuse this one — each has its own transport, and the
 * RULE's canonical home is `RecoveryWorker.undeliveredRecordBytes`, which
 * answers `1L` for a file it cannot measure for exactly this reason.
 */
    private val EncryptedEventStore.ReadResult.couldNotBeRead: Boolean
        get() = integrityClass == "unreadable_file"

    /**
 * The ONE home of the rule above.
 *
 * Deliberately a pure function of the read outcome: it touches no lease,
 * no WorkManager, no uploader and no Context, so the decision can be
 * driven on the JVM against a REAL [EncryptedEventStore] — which is what
 * makes the unreadable case testable at all without a device.
 */
    internal fun classifyInheritedRead(res: EncryptedEventStore.ReadResult?): InheritedRead =
        when {
            res == null -> InheritedRead.UNREADABLE
            res.couldNotBeRead -> InheritedRead.UNREADABLE
            res.records.isEmpty() -> InheritedRead.EMPTY
            else -> InheritedRead.PENDING
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
        interactionScreen = screenId
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
        interactionScreen = screenId

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
        // Each call reports ITS OWN refusal: a stale code from an earlier
        // capture would be read as this one's reason (CodeRabbit #1, an earlier change).
        lastFrameRefusal = null
        if (id.captureMode != "semantic_with_frames") return@guard null
        // Validated BEFORE anything is rasterized or a sequence is claimed:
        // `admit` runs as an argument of the builder, so a builder `require`
        // failing afterwards would leave a claimed number with no record — an
        // unexplained gap that marks the session incomplete (CodeRabbit #2).
        // An unknown captureReason is already refused by frameCaptureDecision
        // inside the capturer, which also runs before admit.
        if (screenId.isBlank()) {
            lastFrameRefusal = "frame_screen_id_invalid"
            return@guard null
        }
        // A NEVER-RECORD SCREEN IS NEVER RASTERIZED. captureScreen
        // consults ScreenPolicy BEFORE its walk; this path did not, so a
        // frames-opted-in app that called captureVisualFrame on a declared
        // screen would have drawn and uploaded the very screen it declared
        // unrecordable — node classification cannot catch it, because the
        // prohibition is on the SCREEN, not on any node. Refused before a
        // single pixel or node is read, with a typed code.
        if (com.tracescout.mobile.privacy.ScreenPolicy.isNeverRecord(screenId)) {
            lastFrameRefusal = "frame_screen_never_record"
            return@guard null
        }

        // FRAME SPACE = SCREEN SPACE. The
        // protocol's capture mapping is SCALE-ONLY (`compression.downscale`;
        // no origin), and hierarchy/interaction coordinates are SCREEN
        // coordinates. A frame rasterized from a non-window root (the fixture
        // hands its ScrollView) lands in ROOT-relative pixels: on API 26 the
        // root sat 63 px below the status bar, so every overlay the Viewer
        // projects by scale alone was shifted by 63 px (73% mask match at
        // scale-only, 100% only at the root's own offset). So the frame is of
        // the root's WINDOW — and the privacy walk below runs over that SAME
        // view, so every pixel rasterized was also classified.
        val target = root.rootView ?: root
        val snap = ViewHierarchySnapshotter.snapshot(target)
        // A PARTIAL WALK IS NOT A PRIVACY CLASSIFICATION (CodeRabbit #3).
        // The capturer draws the WHOLE view and paints over only the nodes it
        // is handed; a walk that stopped at its node/depth/time budget leaves
        // the un-walked views drawn and unpainted. Refuse — never rasterize
        // content the privacy pass did not see.
        if (snap.truncated || snap.droppedNodes > 0) {
            lastFrameRefusal = "frame_hierarchy_truncated"
            return@guard null
        }
        // FLAG_SECURE is a WINDOW attribute. Read it from the window that owns
        // this view rather than inferring it from the node list — a password
        // field inside an ordinary window is redacted, not a reason to refuse
        // the whole screen.
        //
        // an earlier change, MEASURED ON A DEVICE: this used to read
        // `(root.context as? Activity)?.window` with `?: 0`. A DecorView's
        // context is NOT the Activity, so the cast was null, the flags
        // defaulted to 0, and a FLAG_SECURE window was rasterized and
        // uploaded (fixture "Mark FLAG_SECURE window" → frame 202 0.33 s
        // later). Two corrections: read the window attributes from the view's
        // ROOT layout params — the same source ViewHierarchySnapshotter uses,
        // updated by WindowManager on every setFlags — OR'd with the owning
        // Activity's window when one resolves; and an UNRESOLVABLE window is
        // SECURE, never "flags = 0".
        val windowSecure = try {
            val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
            val rootFlags = (root.rootView?.layoutParams as? android.view.WindowManager.LayoutParams)?.flags
            var c: android.content.Context? = root.context
            while (c is android.content.ContextWrapper && c !is Activity) c = c.baseContext
            val actFlags = (c as? Activity)?.window?.attributes?.flags
            if (rootFlags == null && actFlags == null) true
            else (((rootFlags ?: 0) or (actFlags ?: 0)) and secure) != 0
        } catch (t: Throwable) {
            // Unknown window state is treated as SECURE. An unproven window is
            // not a safe one, and refusing a frame costs an enrichment while
            // capturing a secure window costs a disclosure.
            true
        }
        // The capture instant: read BEFORE rasterizing, so the frame's time is
        // the moment the pixels were drawn from, not the end of the encode.
        val captureMono = clock.monotonicNanos()
        val res = com.tracescout.mobile.capture.VisualFrameCapturer.capture(
            root = target, nodes = snap.nodes, captureMode = id.captureMode,
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
        // THE METADATA EVENT IS THE FRAME'S ONLY CLOCK. The bytes
        // route carries no capture time, so a frame whose metadata never
        // reaches a chunk is stored with a NULL monotonic_nanos and the
        // replay cannot place it. It is enqueued DURABLY, before the async
        // byte upload, with the capture-time monotonic clock — exactly the
        // RN producer's order (RnBridge.emitFrameEvent). A frame whose bytes
        // later fail renders as a typed placeholder at its correct time.
        enqueueDisclosing { d ->
            Envelope.visualFrame(
                id, admit("mobile_visual_frame"), captureMono, posture(),
                screenId = screenId, visualFrameId = frameId,
                contentHash = frame.contentSha256, width = frame.width,
                height = frame.height, byteSize = frame.bytes.size,
                format = frame.format, quality = frame.quality,
                downscale = frame.downscale.toDouble(),
                privacyState = frame.privacyState,
                captureReason = frame.captureReason, clock = clock, dropped = d,
            )
        }
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
                val post = {
                    up2.postFrame(
                        projectId = cfg2.projectId, sessionId = id.sessionId,
                        visualFrameId = frameId, bytes = frame.bytes,
                        width = frame.width, height = frame.height,
                        contentSha256 = frame.contentSha256, format = frame.format,
                        privacyState = frame.privacyState,
                        captureReason = frame.captureReason, screenId = screenId,
                    )
                }
                var r = post()
                // an earlier change, MEASURED ON A DEVICE: the native queue uploads only on
                // an explicit flush, so EVERY frame of a session reached the
                // server before any chunk had created that session and was
                // answered 404 session_not_found — and dropped, because frame
                // bytes have no retry. 11 of 11 frames of a real run. The fix
                // is ordering, not a retry loop: drain the durable queue through
                // flush's own governed path (this IS the worker, so FIFO holds
                // and the metadata event enqueued above is in it), then post the
                // bytes ONCE more. Any other answer is final, exactly as before.
                if (r.status == 404 && r.code == "session_not_found") {
                    drainQueueOnWorker()
                    r = post()
                }
                lastFrameResult = r
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
        // AFTER THE LAST VALIDATION, NOT BEFORE IT. This used to move the
        // interaction route on the first length check, so a navigation REFUSED
        // by any later check still re-pointed it — and every following tap was
        // then attributed to a screen with no `mobile_navigation` event in the
        // session. The route moves only when the navigation is actually going
        // to be recorded.
        interactionScreen = toScreenId
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
 * What [recordSessionEnd] did.
 *
 * FOUR OUTCOMES, NOT A BOOLEAN, FOR THE REASON THE F7 SEAM ALREADY GIVES
 * ([SessionEndOutcome]'s own note): *"a caller cannot know the retry was safe
 * if the two look identical"*. A repeat declaration is a NO-OP rather than an
 * error — that is what makes the call safe to retry from a lifecycle callback
 * that may fire twice — and folding it into `false` beside a genuine refusal
 * would make the two indistinguishable.
 */
    public enum class SessionEndEmission {
        /**
 * The event was admitted by the loss authority and handed to the durable
 * append path. A sequence WAS claimed, so the record is either stored or
 * accounted as a loss against this session — never silently absent.
 */
        EMITTED,

        /**
 * This session ALREADY emitted its end; nothing was built. One end per
 * session — see [sessionEndClaim] for why a second would not be
 * ignored but would REWRITE the first.
 */
        ALREADY_EMITTED,

        /** No session is running, so there is nothing to end. Nothing was built. */
        NO_SESSION,

        /**
 * Either the loss authority REFUSED to admit the event — no session
 * authority, a recovery-blocked queue, an active reconciliation gate, a
 * disabled or fenced session — or the call threw before completing (the
 * [guard] caught it).
 *
 * THE TWO HALVES DIFFER IN WHAT THEY PROMISE, and the difference is
 * the whole reason this value is not `EMITTED`. On a REFUSED ADMISSION
 * nothing was claimed and nothing was stored, so this session's one end
 * is **released** and the call may be retried once the block clears. On
 * a THROW the latch is deliberately **kept**: the record may already
 * have reached the store, and a second end would silently REWRITE the
 * first (the server resolves conflicting envelope facts by highest
 * sequence), which is worse than a missing one. So a throw costs the
 * session its end, and that is the safe direction.
 */
        REFUSED,
    }

    /**
 * **EMIT THE CANONICAL `mobile_session_end` EVENT for the LIVE session.**
 *
 * The host declares *"this session is over"* and the producer records the
 * protocol's own end-of-session event, with the session's length. This is
 * the `mobile_session_start` twin that `Envelope` had never had: the
 * protocol declares the type, `EventQueue.Priority` has always reserved it a
 * dispatch class (2, beside the opener), and no Android producer could build
 * one — measured on a real device by an earlier change, whose Cordova fixture reports
 * the absence in its own prose.
 *
 * **THIS IS NOT [endSessionAuthoritatively], AND THE DIFFERENCE IS THE
 * WHOLE POINT.** Two different acts, deliberately kept apart:
 *
 * | | this call | [endSessionAuthoritatively] |
 * |---|---|---|
 * | what it writes | a replay EVENT in the durable queue | a DURABLE TERMINAL CLAIM in the loss authority |
 * | reversible | it is just an event; the session may keep capturing | NO — terminality is one-way |
 * | server effect | one more event in the session's timeline | the session is accounted FINAL |
 * | if it is never called | the session has no closing event | the session is never final |
 *
 * **Neither implies the other and this call changes nothing about the ack,
 * durability or finality rules.** It does not terminalize, does not touch
 * `LossAuthority.Handle.recordTerminal`, does not flush, does not stop
 * capture and does not schedule a drain. A host that wants BOTH calls both;
 * the recommended order is **terminalize first, then emit** — `LossLedger`'s
 * own rule is *"call `markTerminal`, persist, then emit"*, and in that order
 * the chunk carrying this event also carries F6's delivery checkpoint with
 * `terminal: true`, so finality rides real content. The reverse order is
 * also correct and is NOT refused: the drain may take the event first with
 * `terminal: false`, and the F7 R-2 chunkless carrier then discloses the
 * finality on the next empty drain. Either way finality reaches the server
 * exactly once, through a path that already existed.
 *
 * **WHY IT IS A SEPARATE CALL RATHER THAN AN EMISSION INSIDE THE
 * AUTHORITATIVE SEAM.** `RnBridgeSessionEndTest` E2 pins — with its reasons
 * that the declaration writes NO record, because F7 R-2 covers only the
 * case where nothing was durably emitted and an event appended there would
 * make the chunkless finality carrier unreachable for a session that
 * produced nothing else. That pin stays TRUE: this is an ordinary producer
 * emission on the ordinary path, and the seam beside it is byte-unchanged.
 * A host that wants the zero-chunk finality shape simply does not call this.
 *
 * **NO `reason` PARAMETER, AND THAT IS THE SAFETY PROPERTY.** The only
 * reason a host declaration can honestly carry is `manual`. The protocol's
 * other four need a timer this SDK does not have (`inactivity`,
 * `max_duration`) or describe a session this process did not watch end
 * (`app_terminated`, `process_death_detected`) — an end event manufactured
 * after a death is indistinguishable on the wire from one a host declared,
 * so there must be no argument through which those could arrive rather than
 * merely no caller that passes them today. See
 * [Envelope.SESSION_END_REASONS].
 *
 * **`eventsEmitted` IS DELIBERATELY NOT SENT.** The schema marks it
 * optional and this producer keeps no per-session emitted counter; the
 * authority's ledger is the server-facing accounting and re-deriving a count
 * here would be a second opinion on it. Absence means "not captured", which
 * is the truth, where a number guessed from an admission sequence would
 * silently count admissions rather than deliveries.
 *
 * Idempotent per session: a second call is [SessionEndEmission.ALREADY_EMITTED]
 * and builds nothing.
 *
 * @return what happened; never throws.
 */
    @JvmStatic
    fun recordSessionEnd(): SessionEndEmission = guard("sessionEnd") {
        // THE CLAIM IS JUDGED AGAINST THE LIVE SESSION, NOT AGAINST NULL,
        // and it is taken with a compare-and-set LOOP on the value it observed.
        // Two cross-session races, both found in review, and one same-session
        // one, are closed by that pair together:
        //
        // * `compareAndSet(null, token)` conflates two different facts — "this
        // session already ended" (refuse) and "a session that is OVER left
        // its claim behind" (take it; the live session has not ended). Reading
        // the second as ALREADY_EMITTED lets a dead session's claim stop the
        // live one from ever recording an end.
        // * A bare `get` then `set` would let two threads in the SAME session
        // both pass the check and both emit — which is the very thing this
        // claim exists to prevent. The CAS is from the OBSERVED value, so the
        // loser re-reads, now sees a token naming its own session, and is
        // answered ALREADY_EMITTED.
        //
        // NO LOCK, DELIBERATELY. The identity read is unsynchronized, so a
        // teardown and a new `start` can swap the session under it — but with
        // the session-aware rule the worst that produces is a claim naming a
        // session that is already over, which the next live call OVERWRITES.
        // No end is lost and none is duplicated. Taking `identityWriteLock`
        // here would make the correctness a critical section instead of a
        // property, and it would add a sixth `synchronized(identityWriteLock)`
        // that writes no identity — which `IdentifyWiringTest` pins as a
        // writes-to-locks EQUALITY, deliberately, so that a lock cannot drift
        // away from the writes it is supposed to cover.
        //
        // READ BACK AT THE **LAST** SEPARATOR, AND THAT IS NOT COSMETIC. The
        // token is `<sessionId>#<nonce>`, and `start(…, sessionId = …)` takes the
        // host's string VERBATIM (`val sid = sessionId ?: Ids.newSessionId(clock)`)
        // with no character validation — the `^ts_[0-9]+_[0-9a-f]{12}$` pattern is
        // a WIRE rule the server enforces, not a producer-side guard. So a host
        // may hand us `"abc#1"`, and reading back at the FIRST `#` recovers
        // `"abc"`, which never equals the live session id: the ALREADY_EMITTED
        // check could not fire and a second call would take the claim and emit a
        // SECOND end for one session — the exact duplicate this claim exists to
        // prevent (found in review).
        //
        // The LAST `#` is unconditionally the one we appended, because the nonce
        // is a `java.util.UUID` whose `toString` is `[0-9a-f-]{36}` and cannot
        // contain `#`. So this is correct for EVERY session id, not merely for
        // the canonical ones — and on a canonical id (no `#` at all) it is
        // byte-identical to the first-separator read it replaces.
        val id: Envelope.SessionIdentity
        val claimToken: String
        while (true) {
            val live = identity ?: return@guard SessionEndEmission.NO_SESSION
            val held = sessionEndClaim.get()
            if (held != null && held.substringBeforeLast('#') == live.sessionId) {
                return@guard SessionEndEmission.ALREADY_EMITTED
            }
            val token = live.sessionId + "#" + java.util.UUID.randomUUID()
            if (sessionEndClaim.compareAndSet(held, token)) {
                id = live; claimToken = token; break
            }
        }
        // CLAIMED BEFORE ANYTHING IS BUILT, so two threads cannot both pass.
        // It is released again ONLY where nothing was claimed and nothing was
        // stored — see the `claimed < 0` branch below — and then only by a
        // compare-and-set from THIS call's own token, so a refused admission can
        // never cancel another session's claim (see [sessionEndClaim]). A
        // refused APPEND keeps the claim: the authority allocated a sequence and
        // settles it as LOST(APPEND_REFUSED), so the loss is accounted, and
        // re-emitting would give one session two closing instants.
        // READ ONCE, and omitted when the anchor is unknown. `max(0, …)` is
        // belt-and-braces for a monotonic clock that is specified not to go
        // backwards: the schema's `minimum: 0` would refuse a negative, and a
        // refused event is worse than an unmeasured duration.
        val startedMono = sessionStartedMono
        val nowMono = clock.monotonicNanos()
        val durationMs = startedMono?.let { maxOf(0L, (nowMono - it) / 1_000_000L) }
        // THE ADMISSION'S ANSWER IS READ, NOT DISCARDED — and discarding it
        // was a REAL SILENT LOSS, found in review. `admit` returns -1 when the
        // authority is missing, the queue is recovery-blocked or a reconciliation
        // fact binds the image; `Envelope.sessionEnd` accepts that sequence
        // happily and `enqueue` then drops the record at its own
        // `rec.sequence < 0` guard (`enqueue_unnumbered_dropped`) and returns
        // normally. The first version therefore answered EMITTED with nothing
        // stored AND the latch consumed, so the host could never retry: the
        // session end was lost permanently and the caller was told it succeeded.
        // That is the F-6/F-42 loss class reached through the ADMISSION instead
        // of through a read, and the KDoc's "handed to the durable append path"
        // was true of the call while being false about the outcome.
        //
        // THE RELEASE IS NARROW ON PURPOSE. `enqueue` takes its
        // `rec.sequence < 0` exit BEFORE it touches `pending` or the store, so
        // in exactly this case we KNOW nothing was claimed and nothing was
        // written — a retry is safe and is what the host wants once the block
        // clears. Every other failure keeps the latch, because we cannot prove
        // the record did not reach the store and a duplicate end rewrites the
        // first. See [SessionEndEmission.REFUSED].
        // `admit(.)` STAYS INSIDE THE BUILDER CALL, and `.also` is what keeps
        // it there. `AuthorityOwnershipPinsTest` pairs each `admit("T")` with the
        // `Envelope.<fn>(` that LEXICALLY encloses it, by scanning backwards for
        // the nearest builder call — so hoisting the admission onto its own line
        // made that pin resolve the WRONG builder and fail, on a change that
        // preserved the property it checks. Re-pointing an accepted pin because
        // a new change moved its anchor is the larger intervention (the `enqueue`
        // KDoc makes the same call for the same reason), so the fix keeps the
        // lexical shape and captures the answer beside it.
        // NAMED `claimed`, NOT `sequence`, AND THAT IS NOT COSMETIC. The same
        // pin's last assertion forbids any `sequence = <non-admit>` reaching an
        // Envelope builder — its proxy for "a producer grew a local sequence
        // counter" — and `sequence = it` inside the capture matched it. This
        // value is not a sequence source: it is the ADMISSION'S ANSWER, read
        // AFTER the builder has already been handed `admit(.)` directly, and
        // it reaches no builder at all. `claimed` says that; `sequence` claimed
        // something it is not.
        var claimed = -1L
        enqueueDisclosing { d ->
            Envelope.sessionEnd(id, admit("mobile_session_end").also { claimed = it },
                nowMono, posture(),
                Envelope.SESSION_END_MANUAL, durationMs, clock = clock, dropped = d)
        }
        if (claimed < 0) {
            // COMPARE-AND-SET FROM THIS CALL'S TOKEN, never an unconditional
            // clear: between the claim above and this line a teardown and a new
            // `start` may have installed another session whose end is already
            // claimed, and releasing THAT would let it emit a second end.
            val released = sessionEndClaim.compareAndSet(claimToken, null)
            RecoveryTrace.log("session_end_admission_refused", "released" to released)
            return@guard SessionEndEmission.REFUSED
        }
        SessionEndEmission.EMITTED
    } ?: SessionEndEmission.REFUSED

    // -----------------------------------------------------------------------
    // S4 — native touch + gesture capture. OPT-IN: nothing is observed
    // until the host calls enableInteractionCapture.
    // -----------------------------------------------------------------------

    /** The screen the next interaction belongs to — the last one the host named. */
    @Volatile private var interactionScreen: String? = null
    @Volatile private var interactionCapture: com.tracescout.mobile.capture.interaction.InteractionCapture? = null
    @Volatile private var interactionEnabled = false

    /**
 * Opt in to native touch + gesture capture for every Activity window of
 * [app] (installed on resume). Supported vocabulary: `tap` and `swipe`
 * (begin/move/end/cancel) — the two classes the shared policy admits.
 * Coordinates appear only on Views declared position-safe; text, values,
 * labels and keys are unrepresentable. Pass the current [activity] when
 * calling after it has already resumed.
 */
    @JvmStatic
    @JvmOverloads
    fun enableInteractionCapture(app: android.app.Application, activity: android.app.Activity? = null): Boolean =
        guard("interaction") {
            interactionEnabled = true
            val c = interactionCapture ?: com.tracescout.mobile.capture.interaction.InteractionCapture(
                screenIdProvider = { interactionScreen },
                neverRecordProvider = { s -> s != null && com.tracescout.mobile.privacy.ScreenPolicy.isNeverRecord(s) },
                // An interaction is never a reason to start a session.
                captureEnabled = { interactionEnabled && identity != null },
                sink = { e, carrier -> recordInteraction(e, carrier) },
            ).also { interactionCapture = it; it.installForApplication(app) }
            activity?.let { c.install(it) }
            true
        } ?: false

    /** Stop creating interaction telemetry. The seam stays (dispatch never varies with a setting). */
    @JvmStatic
    fun disableInteractionCapture() { interactionEnabled = false }

    // -----------------------------------------------------------------------
    // S4 — HOST-REPORTED interactions. The public producer surface an
    // EMBEDDER needs: a Cordova/Flutter/native host that observed a gesture on
    // its OWN surface reports it here. OPT-IN, separately from the native
    // window observer above, because the two are different mechanisms.
    //
    // See `capture.interaction.HostInteraction` for the full contract: the row
    // is always the policy's REDACTED field set, a position is not expressible
    // through this path, and the screen / never-record / content-window /
    // target-class facts are the SDK's answers, not the host's.
    // -----------------------------------------------------------------------

    @Volatile private var hostInteractionsEnabled = false

    /**
 * Opt in to recording interactions the HOST observed itself.
 *
 * THIS INSTALLS NOTHING AND OBSERVES NOTHING. It only permits
 * [recordHostInteraction] to create telemetry; with it off that call
 * refuses by name and queues nothing. It is deliberately NOT
 * [enableInteractionCapture]: that one installs a native window callback on
 * every Activity, which an embedder does not want (its own taps are DOM
 * events, so the observer would resolve every one of them to the single
 * host `View`) and must not be made to accept as the price of reporting.
 *
 * An interaction is never a reason to start a session: with no session
 * [recordHostInteraction] refuses `no_session`.
 */
    @JvmStatic
    fun enableHostReportedInteractions(): Boolean = guard("hostInteraction") {
        hostInteractionsEnabled = true
        true
    } ?: false

    /** Stop creating host-reported telemetry. The seam stays; only the permission moves. */
    @JvmStatic
    fun disableHostReportedInteractions() { hostInteractionsEnabled = false }

    /** Whether host-reported interactions are currently permitted. */
    @JvmStatic
    fun hostReportedInteractionsEnabled(): Boolean = hostInteractionsEnabled

    /**
 * Record ONE interaction the host observed on its own surface.
 *
 * @param interaction [HostInteraction.WIRE_TAP] or [HostInteraction.WIRE_SWIPE]
 * the two single-pointer classes the shared policy admits. Any other
 * protocol value is refused `unsupported_gesture`.
 * @param phase `null` for a tap (a tap is ONE row and carries no phase);
 * `begin` / `move` / `end` / `cancel` for a swipe. A mismatch is refused,
 * never defaulted.
 * @param gestureId the host's own grouping token for a multi-row swipe.
 * Bounded to `[A-Za-z0-9_-]{1,64}` because the payload serializer does
 * not escape it; minted when absent.
 * @param textEntryActive the host's own observation that a text field has
 * focus on its surface. `true` suppresses the row (tap TIMING during text
 * entry is a keystroke side channel); `false` is the absence of a claim.
 * @param webView attribute the row to the segment running in this WebView
 * (S5). Attribution happens ONLY if that WebView was already bound by
 * [attachWebViewSegment] with an ADMITTED segment — this argument grants
 * no trust of its own, and an unattached WebView leaves the row
 * unattributed rather than marking it native.
 *
 * @return a typed [HostInteraction.Decision] — `Recorded` with the gestureId
 * HANDED TO THE QUEUE WRITER, or `Refused` with a stable word. Never
 * throws into the host app.
 *
 * `Recorded` IS NOT A DELIVERY RECEIPT, and the earlier wording of this
 * line ("the gestureId that reached the wire") was wrong. MEASURED: `admit`
 * returns `-1` when the authority is missing or the queue is
 * recovery-blocked, and `enqueue` then DROPS the unnumbered record and
 * returns normally (`enqueue_unnumbered_dropped`) — deliberately, because a
 * record stamped with a number nobody owns would be a gap the authority
 * could never explain. The durable append also runs on a worker thread, so a
 * later capacity refusal is not visible here either. So this says the row
 * passed the policy and was handed on; it does not promise storage or
 * upload, and a host must not treat it as proof the interaction was kept.
 */
    @JvmStatic
    @JvmOverloads
    fun recordHostInteraction(
        interaction: String?,
        phase: String? = null,
        gestureId: String? = null,
        textEntryActive: Boolean = false,
        webView: android.webkit.WebView? = null,
    ): HostInteraction.Decision =
        guard("hostInteraction") {
            if (!hostInteractionsEnabled)
                return@guard HostInteraction.Decision.Refused(HostInteraction.REFUSED_NOT_ENABLED)
            // An interaction never starts a session.
            if (identity == null)
                return@guard HostInteraction.Decision.Refused(HostInteraction.REFUSED_NO_SESSION)
            // The route the host already named, and ScreenPolicy's own answer
            // about it — neither is the caller's to assert here.
            val screen = interactionScreen
            val prepared = HostInteraction.prepare(
                interaction = interaction,
                phase = phase,
                gestureId = gestureId,
                screenId = screen,
                screenNeverRecord = screen != null &&
                    com.tracescout.mobile.privacy.ScreenPolicy.isNeverRecord(screen),
                textEntryActive = textEntryActive,
                monotonicNanos = clock.monotonicNanos(),
                wallMillis = clock.wallMillis(),
                newGestureId = { java.util.UUID.randomUUID().toString().replace("-", "").take(32) },
            )
            when (prepared) {
                is HostInteraction.Prepared.Refused ->
                    HostInteraction.Decision.Refused(prepared.reason)
                is HostInteraction.Prepared.Observation ->
                    when (val r = InteractionProducer.produce(prepared.persist)) {
                        // The producer's refusal word is the policy's own
                        // (`no_route`, `never_record`, `text_entry_active`, …);
                        // it is never translated here.
                        is InteractionProducer.Result.Refused ->
                            HostInteraction.Decision.Refused(r.reason)
                        is InteractionProducer.Result.Emit -> {
                            // Attribution is the registry's answer for a WebView
                            // the host already had ADMITTED and attached; null
                            // for anything else, which leaves the row
                            // unattributed rather than claiming "native".
                            val carrier = webView?.let {
                                com.tracescout.mobile.capture.WebViewAttachment.carrierFor(it)
                            }
                            // THE WRITER RETURNS A BARE BOOLEAN, so `false`
                            // means EITHER the session ended between the check
                            // above and this write OR its own guard caught a
                            // fault. The two are separated HERE rather than
                            // documented away, and without touching the shared
                            // writer (the window observer's path, an earlier change): ask
                            // the session question again. Still present ⇒ the
                            // write failed for a reason that is not a session
                            // fact, which is `internal_error`; gone ⇒ an
                            // ordinary end-of-session race, and claiming an SDK
                            // fault for that would send a host hunting a bug
                            // that is not there.
                            if (recordInteraction(r.emission, carrier))
                                HostInteraction.Decision.Recorded(r.emission.gestureId)
                            else if (identity == null)
                                HostInteraction.Decision.Refused(HostInteraction.REFUSED_NO_SESSION)
                            else
                                HostInteraction.Decision.Refused(HostInteraction.REFUSED_INTERNAL_ERROR)
                        }
                    }
            }
            // THE GUARD'S CAUGHT-EXCEPTION VALUE, WHICH IS NOT A SESSION
            // FACT. Reporting it as `no_session` would tell a host to wait for
            // a session it already has, and hide an SDK fault as lifecycle.
        } ?: HostInteraction.Decision.Refused(HostInteraction.REFUSED_INTERNAL_ERROR)

    // -----------------------------------------------------------------------
    // G2 — the identified user. NOT opt-in; see `Identify` for why.
    // -----------------------------------------------------------------------

    /**
 * Name the IDENTIFIED USER for the live session — the producer half of G2.
 *
 * The envelope has declared a root `userId` all along and the deployed
 * server learned to store it (api-web `3f40126f`, migration 181). No
 * producer emitted one, so `sessions.user_id` was NULL for every stored
 * mobile session and the dashboard's free-text user search, `userId`
 * filter and `signedUpStatus` filter were dark for the whole family. This
 * is the call that ends that for native Android.
 *
 * @param userId the opaque key from the host app's OWN auth system. Bounded
 * to [IdentityPolicy.USER_ID_MAX] CODE POINTS and otherwise taken
 * VERBATIM — never trimmed, never normalised, never shortened.
 * @return [Identify.Decision.Applied] with the measured code-point length,
 * or [Identify.Decision.Refused] with a stable word. Never throws into
 * the host app, and never returns the value itself — a host that logs
 * this result must not accidentally log an end user's key.
 *
 * IT EMITS NO EVENT, and that is deliberate. The identity rides the
 * envelope of events the session was going to send anyway, so naming a user
 * costs no queue entry, no chunk and no drain. Minting a record here would
 * also make the identity depend on that one record surviving, where
 * stamping leaves the server free to take the highest sequence it ever sees
 * so a lost chunk cannot strand a session as anonymous.
 *
 * IT TAKES NO SESSION ARGUMENT. The identity applies to the session
 * [identity] reports, so a caller cannot name a user on a session it is not
 * running. It also NEVER STARTS one: a session minted because its own
 * `identify` was the first caller would have no `mobile_session_start`,
 * which the server refuses to originate.
 *
 * A BLANK ARGUMENT IS REFUSED, NOT A SIGN-OUT. Sign-out is not
 * expressible through this field at all — the server's merge is
 * incoming-wins-if-higher-sequence over a non-null identity, so an envelope
 * naming no user does not clear a stored one. Clearing locally would make
 * the device stop stamping while the server kept the old value, and the two
 * would disagree with nothing saying so.
 *
 * THE IDENTITY IS NOT CARRIED INTO THE NEXT SESSION, and that is a
 * choice rather than an omission. A new [start] builds a fresh
 * [Envelope.SessionIdentity] whose `userId` defaults to null, so a new
 * session is ANONYMOUS until the host names a user again. Nothing REMEMBERS
 * the value across sessions, so a sign-out the SDK is never told about
 * cannot be carried forward into a session the user did not consent to.
 * A host whose auth state says "signed in" calls this once after `start`.
 *
 * BUT IT IS NOT TRUE THAT THE KEY NEVER REACHES DISK, and an earlier
 * version of this KDoc said exactly that ("keeps the key in the host's own
 * memory"). Review caught it, and the honest statement matters because
 * integrators quote this text in their own data-retention answers:
 *
 * the identity is stamped into EVERY record's bytes (see
 * `Envelope.envelopeFields`), and `enqueue` appends those bytes to the
 * `EncryptedEventStore` — a FILE, AES-256-GCM per record. So the key
 * does sit on disk, inside the encrypted queue, for as long as a record
 * carrying it is undelivered: across a process restart, and across a
 * host sign-out while records are still queued, because
 * `recoverInherited` re-uploads them verbatim.
 *
 * What IS bounded is the LIFETIME of a QUEUED copy: the queue's, not the
 * installation's. There is no separate identity file and no preference
 * entry.
 *
 * BUT "DELIVERY OR EVICTION REMOVES THE ONLY ONE" WAS ALSO WRONG, and
 * review caught this one too. A SECOND copy exists whenever a slice is
 * PARKED: `DurableUploadPipeline` writes the identity-bearing record bytes
 * into the `EncryptedParkStore` and only then removes them from the active
 * queue (see its `park.park(entry, slice)` call site — "the moment the
 * parked copy is durable these records have left the active timeline
 * permanently"). So delivery or eviction from the ACTIVE queue does not
 * remove the parked copy, and `ParkStore` is deliberately write-and-read
 * only — it has no `delete`.
 *
 * The park files ARE covered by the governed erasure: they are named in
 * `QueueKeyStores.ALL`, so a successful `destroyStoredKeyMaterial` cleanup
 * removes them. That cleanup is CONDITIONAL — `StoreTeardown` attempts it
 * only after the queue key is destroyed and only with a files directory
 * available, and reports `NotAttempted` / `Incomplete` / `erasure_pending`
 * otherwise. An integrator quoting this text in a retention answer needs
 * that qualifier, which is why it is here rather than implied.
 *
 * Idempotent: naming the same user twice applies the same value and answers
 * [Identify.Decision.Applied] both times.
 */
    @JvmStatic
    fun identify(userId: String?): Identify.Decision =
        guard("identify") {
            // An identity never starts a session — see above.
            if (identity == null)
                return@guard Identify.Decision.Refused(Identify.REFUSED_NO_SESSION)
            // ONE validation home, shared with every other mobile producer
            // through the canonical module the twins are pinned to. The
            // decision carries a length and a verdict and deliberately NOT the
            // value on a refusal, so nothing below can leak the key.
            val decision = IdentityPolicy.classifyUserId(userId)
            val accepted = decision.value
            val length = decision.length
            // BOTH NULLS ARE CHECKED IN ONE PLACE, and that is not
            // defensive noise. The policy's contract is that `value` is
            // non-null only for ACCEPTED, where `length` is also non-null — so
            // an `accepted ?: return` followed by `decision.length ?: <recount>`
            // reads fine and makes the recount UNREACHABLE: dead code wearing
            // the shape of a safeguard. Checking the pair here keeps the
            // measured length as the only length, with no `!!` to crash a host
            // app if the contract ever changes.
            if (accepted == null || length == null) {
                return@guard Identify.Decision.Refused(
                    Identify.refusalWord(decision.verdict),
                )
            }
            // RE-READ UNDER THE LOCK. The null check above is not the write:
            // a session can end, or a new `start` can publish a different
            // identity, between the two. Replacing the reference under
            // `identityWriteLock` — the one place it is replaced — means the
            // copy is always of the identity that is live AT THE WRITE, so a
            // dead session id can never be resurrected by this path.
            val applied = synchronized(identityWriteLock) {
                val live = identity
                if (live == null) false
                else {
                    identity = live.copy(userId = accepted)
                    true
                }
            }
            if (applied) Identify.Decision.Applied(length)
            // The session went away between the guard and the write. That is an
            // ordinary end-of-session race, which `no_session` already names —
            // claiming an SDK fault for it would send a host hunting a bug that
            // is not there.
            else Identify.Decision.Refused(Identify.REFUSED_NO_SESSION)
            // THE GUARD'S CAUGHT-EXCEPTION VALUE, WHICH IS NOT A SESSION
            // FACT. Reporting it as `no_session` would tell a host to wait for
            // a session it already has, and hide an SDK fault as lifecycle.
        } ?: Identify.Decision.Refused(Identify.REFUSED_INTERNAL_ERROR)

    // -----------------------------------------------------------------------
    // S6 — native network capture. OPT-IN: nothing is recorded until
    // the host calls enableNetworkCapture, and the switch is read PER REQUEST.
    // -----------------------------------------------------------------------

    @Volatile private var networkEnabled = false

    /**
 * Opt in to recording this app's OWN outbound HTTP requests.
 *
 * THIS INSTALLS NOTHING AND OBSERVES NOTHING BY ITSELF. The SDK does
 * not reach into the host's networking: the host composes
 * `RnNetworkCapture.install(builder, host)` onto the `OkHttpClient.Builder`
 * it already owns, and that adapter asks [networkCaptureEnabled] on every
 * request. Two consequences, both deliberate:
 *
 * - a host that never installs the seam records nothing however this is
 * set, and
 * - turning it off stops the NEXT request being recorded, not the next
 * process — a user who revokes consent expects the former.
 *
 * It is never a reason to start a session: with no session, a completed
 * request is dropped rather than queued, because a record whose session has
 * no opener can never be accepted and would park at the head of the queue.
 */
    @JvmStatic
    fun enableNetworkCapture(): Boolean = guard("network") { networkEnabled = true; true } ?: false

    /** Stop recording network telemetry. The host's seam stays installed. */
    @JvmStatic
    fun disableNetworkCapture() { networkEnabled = false }

    /**
 * The opt-in switch, read by the host's adapter on EVERY request.
 *
 * THE SWITCH ALONE, NOT "and there is a session". The two are separate
 * facts and the capture policy reports a DIFFERENT typed refusal for each
 * (`capture_disabled` vs `no_session`) — collapsing them here would make
 * "the operator turned it off" and "the seam is installed but there is
 * nothing to attach a request to" the same observation, which is the
 * failure this producer has paid for before.
 */
    @JvmStatic
    fun networkCaptureEnabled(): Boolean = networkEnabled

    /**
 * Is there a session a record could be addressed to?
 *
 * The one fact a host's capture adapter needs and cannot otherwise reach.
 * It is NOT a capture switch and starts nothing: a request is never a
 * reason to begin a session.
 */
    @JvmStatic
    fun hasActiveSession(): Boolean = identity != null

    /**
 * Record one COMPLETED native request.
 *
 * Returns false and records nothing when capture is off, when there is no
 * session, or when the facts cannot go on the wire — in which case the
 * typed refusal is traced. A refusal is never a throw: this runs on the
 * caller's networking callback thread, inside their own request, and
 * losing one telemetry row is a different outcome from their request
 * failing because of our observability.
 *
 * @param screenId the route the request STARTED on. A call begun on screen
 * A and completed after navigating to B belongs to A, and the caller
 * froze that at request start — passing the current route here would
 * attribute slow calls to whatever screen the user happened to reach.
 */
    @JvmStatic
    @JvmOverloads
    fun recordNetworkRequest(
        facts: com.tracescout.mobile.capture.network.NetworkRequestFacts,
        screenId: String? = null,
    ): Boolean = guard("network") {
        if (!networkEnabled) return@guard false
        val id = identity ?: return@guard false
        facts.refusalOrNull()?.let { why ->
            RecoveryTrace.log("network_request_refused", "why" to why)
            return@guard false
        }
        enqueueDisclosing { d ->
            Envelope.networkRequest(id, admit("mobile_network_request"), posture(),
                facts, screenId, clock = clock, dropped = d)
        }
        true
    } ?: false

    internal fun recordInteraction(
        e: com.tracescout.mobile.capture.interaction.InteractionProducer.Emission,
        webView: com.tracescout.mobile.capture.WebViewAttachment.Carrier? = null,
    ): Boolean = guard("interaction") {
        val id = identity ?: return@guard false
        enqueueDisclosing { d ->
            Envelope.interaction(id, admit("mobile_interaction"), posture(), e,
                clock = clock, dropped = d, webView = webView)
        }
        true
    } ?: false

    /**
 * Opt in to attributing interactions inside ONE WebView to the segment
 * running in it (S5, an earlier change).
 *
 * The host calls this once a segment has been ADMITTED by
 * [com.tracescout.mobile.capture.WebViewBridge] — the admission, not this
 * call, is what decided the page may participate. Returns the typed
 * decision so a host can log a refusal instead of assuming success; a
 * refusal binds nothing.
 *
 * NOT A CAPTURE SWITCH. It neither starts a session nor enables
 * interaction capture; with capture off this changes nothing at all. And
 * it is not reachable from page content: only the host app holds the
 * `WebView` and the admitted `Segment`.
 */
    @JvmStatic
    @JvmOverloads
    fun attachWebViewSegment(
        webView: android.webkit.WebView,
        segment: com.tracescout.mobile.capture.WebViewBridge.Segment,
        policy: com.tracescout.mobile.capture.WebViewOriginPolicy,
        capabilitySupported: Boolean = true,
    ): com.tracescout.mobile.capture.WebViewAttachment.Decision =
        guard("webview") {
            com.tracescout.mobile.capture.WebViewAttachment.attach(
                webView, segment, policy, identity?.sessionId, capabilitySupported)
        } ?: com.tracescout.mobile.capture.WebViewAttachment.Decision
            .Refuse(com.tracescout.mobile.capture.WebViewAttachment.REFUSED_NO_SESSION)

    /**
 * Forget one WebView's attachment — on navigation, on detach, on destroy.
 *
 * A navigation is a NEW page and therefore a new segment: leaving the old
 * binding in place would attribute the next page's touches to the previous
 * page's segment.
 */
    @JvmStatic
    fun detachWebViewSegment(webView: android.webkit.WebView) =
        com.tracescout.mobile.capture.WebViewAttachment.detach(webView)

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
            // THE READ IS CLASSIFIED BEFORE ITS RECORD COUNT CAN MEAN
            // ANYTHING — see [drainImageOrRefuse]. `null` is a REFUSAL, not an
            // empty queue, so it must not fall through to `uploadRaw`.
            val recs = drainImageOrRefuse(s) ?: return@Runnable
            // F7 R-2: an explicit flush that finds nothing is chunkless too, so
            // a finalized session is disclosed here rather than waiting for a
            // process restart. Unconditional because the EMPTY case is now a
            // decision the pipeline makes, not an absence of work — and it is
            // reached ONLY for a read that established emptiness.
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
 * What [flush]'s task does, for a caller ALREADY running on [worker] (it
 * does not submit; it IS the submitted work — submitting from the worker
 * and waiting would deadlock the single thread). Used only by the frame
 * path, so a frame that must wait for its session drains the queue through
 * the SAME governed upload ([uploadRaw]) — never a dequeue decision of its
 * own. It performs the same two operations as `flush`'s task body — the
 * classified read and the governed upload — and a test asserts that rather
 * than asserting the two bodies are byte-identical, which they are not:
 * each has its own non-local return.
 */
    private fun drainQueueOnWorker() {
        val s = store ?: return
        val recs = drainImageOrRefuse(s) ?: return
        uploadRaw(recs)
    }

    /**
 * The durable queue image for a drain ALREADY running on [worker], or
 * `null` when this process could not read the store.
 *
 * **`null` IS NOT "NOTHING TO SEND", AND HANDING AN EMPTY LIST ON
 * WOULD MANUFACTURE FINALITY.** [uploadRaw]'s empty path is a PRODUCTION
 * TRIGGER, not a no-op: `DurableUploadPipeline.uploadInner` answers an
 * empty image with `Disposition.EMPTY` and then
 * `finalizeChunklessSession(.)` for every candidate — a DURABLE claim
 * that the session is over and produced no chunk. And
 * `EncryptedEventStore.readAll` does NOT throw on a store it cannot
 * open: it RETURNS a `ReadResult` carrying `couldNotBeRead` with an EMPTY
 * record list. So both of these sites used to convert "I could not look"
 * into a completeness claim about bytes that are still on disk — the one
 * combination the loss contract forbids, reached through a read instead of
 * through a lease.
 *
 * This is the SAME harm `InheritedRead.UNREADABLE` refuses one branch
 * over, and it is routed to the SAME contract through the SAME classifier
 * ([classifyInheritedRead]) rather than a second rule that could drift
 * from it: trace the class of damage, keep the obligation, and SCHEDULE
 * the drain that obligation is owed — nothing is deleted, cleared,
 * rewritten or repaired, and no finality checkpoint is emitted on this arm.
 *
 * It deliberately does NOT touch `RecoveryCoordinator.status`, and that
 * asymmetry with the inherited arm is the point: these two sites are
 * ORDINARY flushes holding no recovery lease. Publishing a `RECOVERY_*`
 * verdict from them would assert a recovery that is not happening — the
 * mirror image of the defect being fixed. The honest per-drain signal is
 * the typed `lastResult`, which is exactly how [uploadRaw]'s own
 * `no_app_context` refusal reports a refusal to drain.
 *
 * The `when` is an EXPRESSION, for the reason `recoverInherited` states:
 * in statement position a non-exhaustive `when` over an enum is a warning
 * on some Kotlin versions and an error on others, so a FOURTH read state
 * added later would silently inherit this one's decision — which is how
 * the unreadable case came to be treated as the empty one in the first
 * place.
 */
    private fun drainImageOrRefuse(s: EncryptedEventStore): List<ByteArray>? {
        val res = try { s.readAll() } catch (t: Throwable) {
            RecoveryTrace.log("queue_read_failed", "code" to t.javaClass.simpleName)
            null
        }
        return when (classifyInheritedRead(res)) {
            InheritedRead.UNREADABLE -> {
                RecoveryTrace.log("queue_unreadable",
                    "class" to (res?.integrityClass ?: "read_threw"))
                lastResult = Uploader.Result(ResponseAction.RETRY, 0, "queue_unreadable", null)
                // The obligation is SCHEDULED, not merely named — the lesson
                // the inherited arm's own review paid for. `enqueueUniqueWork`
                // is KEEP, so an existing job is kept and repeated refusals
                // cannot pile up duplicates. Without a Context there is no
                // scheduler to reach, and that is DISCLOSED rather than
                // skipped silently.
                val app = appContext
                if (app == null) {
                    RecoveryTrace.log("worker_enqueue_skipped", "why" to "no_app_context")
                } else {
                    val q = RecoveryWorker.enqueue(app)
                    RecoveryTrace.log(if (q) "worker_enqueued" else "worker_enqueue_failed")
                }
                null
            }
            // An EMPTY read established emptiness, so the pipeline's chunkless
            // decision is one it is entitled to make. PENDING carries records.
            InheritedRead.EMPTY, InheritedRead.PENDING -> res?.records ?: emptyList()
        }
    }

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

    private fun uploadRaw(records: List<ByteArray>): Boolean {
        // OPEN OF THE TRANSPORT SEQLOCK — see [transportEpoch]. The four
        // fields below are independent, so reading them one at a time can
        // compose half of one entry point's transport with half of another's.
        // The close, after the last of them, refuses a torn quadruple instead
        // of posting a mixture of two tenants' endpoint, key and project.
        val epoch0 = transportEpoch
        // AN ODD EPOCH MEANS A PUBLICATION IS IN FLIGHT RIGHT NOW, AND
        // WITHOUT THIS THE CLOSE BELOW CANNOT SEE IT. `start` holds its
        // window open across ~200 lines, the erasure resume and the loss
        // authority, so a drain whose four reads land ENTIRELY inside it
        // observes an UNCHANGED counter and the close passes over a
        // half-published quadruple. Refusing here RETAINS exactly as the close
        // does: nothing removed, parked or quarantined, and the next drain
        // reads a settled transport.
        if (epoch0 % 2L != 0L) {
            RecoveryTrace.log("drain_refused", "why" to "transport_publishing")
            lastResult = Uploader.Result(ResponseAction.RETRY, 0, "transport_publishing", null)
            return false
        }
        val cfg = config ?: return false
        // THE FALLBACK IS AN UPLOAD ADDRESS, AND A PROCESS THAT ONLY
        // BOOTSTRAPPED DELIVERY HAS NO LIVE SESSION TO BE ONE. When this
        // process started a session, that session is the address exactly as
        // before. When it did not — the recovery-only bootstrap, which mints no
        // session deliberately — the address is read from the records' OWN
        // bytes rather than invented, because a minted id here would name a
        // session that never existed. `DurableUploadPipeline.sessionOwnedPrefix`
        // says what this value is for in its own note: "an UPLOAD ADDRESS for a
        // record that cannot state its owner — it is never a key the
        // attribution authority resolves a handle with". Same shape, and the
        // same reason, as `RnTransport`'s session-free drain (`readOwnerOrBlank`).
        //
        // THE SIGNATURE IS DELIBERATELY UNCHANGED. `uploadRaw(records)` is
        // pinned as a LITERAL by four certified source guards — SliceB S3/S5,
        // SliceD SD6 and F7 R2-8, the last of which pins
        // `uploadRaw(recs)\n…if (recs.isEmpty) 0` adjacently — so a parameter
        // added here (as a first draft of this change did) rots all four at
        // once. The real precondition was never the identity anyway: `config`,
        // `uploader`, `home` and `appContext` are what a drain needs, and all
        // four are set by BOTH entry points.
        val fallbackSessionId = identity?.sessionId ?: ownerOrBlank(records)
        val up = uploader ?: return false
        val h = home ?: return false
        // NO `appContext` IS A TYPED REFUSAL TO DRAIN, NEVER A SILENT DOWNGRADE
        // (Slice B, ruling B6). This used to fall back to
        // `InFlightStore.memory` when the context was absent — a descriptor
        // that cannot survive the crash it exists to survive, chosen silently.
        val app = appContext ?: run {
            RecoveryTrace.log("drain_refused", "why" to "no_app_context")
            lastResult = Uploader.Result(ResponseAction.RETRY, 0, "inflight_unavailable", null)
            return false
        }
        // CLOSE OF THE TRANSPORT SEQLOCK. A publication landed across our
        // four reads, so `cfg`, `up`, `h` and `app` may not belong to the same
        // entry point. Refusing RETAINS — nothing is removed, parked or
        // quarantined, and the next drain reads a settled transport — and a
        // retained record is recoverable where a misdirected one is not.
        if (transportEpoch != epoch0) {
            RecoveryTrace.log("drain_refused", "why" to "transport_changed")
            lastResult = Uploader.Result(ResponseAction.RETRY, 0, "transport_changed", null)
            return false
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
        ).upload(records, fallbackSessionId)
        return true
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
        // F-10: THE TRANSPORT IS READ HERE, ON THE CALLER'S THREAD, AND
        // NEVER RE-READ INSIDE THE WORKER.
        //
        // `bracket` and `authority` are mutable: `start` REPLACES both for a
        // new session and the governed teardown clears both. The worker body
        // below is a lambda, and a Kotlin lambda captures the VARIABLE rather
        // than its value — so reading them in there reads them WHENEVER THE
        // TASK RUNS, which can be after a different session was installed. The
        // record would then be appended through the NEW session's bracket and
        // settled against the NEW session's authority; and because `tickets` is
        // keyed by a bare sequence number, that late settlement could remove
        // and settle the LIVE session's claim for the same reused sequence.
        // That is a cross-session settlement: one session's loss accounting
        // resolved by another session's record.
        //
        // `config` was ALREADY captured this way, two statements up. These were
        // the two that were not — the asymmetry was the defect, not the rule.
        //
        // What today's code already does, and the one case it misses: a task
        // that runs after a teardown reads `bracket == null` and refuses
        // (`enqueue_no_store`). It only fails to refuse when the bracket was
        // REPLACED rather than removed. The gate at the worker boundary makes
        // the replacement answer the same way as the removal.
        //
        // `val b = bracket` keeps its exact spelling: `SliceCSourceTest` SC3
        // pins that literal, and the clause now holds for a stronger reason —
        // the read happens before the task is even submitted.
        val b = bracket
        val owner = authority
        val claimedSession = ticket?.sessionId
        // THE RECORD'S OWN SESSION, READ FROM ITS OWN BYTES — and the first
        // version of this repair did not have it, which left two reachable holes
        // (found in review, both real).
        //
        // Everything else available here is MUTABLE or MISSING. `authority` and
        // `identity` name the LIVE session, not the one that built this record.
        // The claim can be ABSENT (the governed teardown clears `tickets`) or
        // FOREIGN (a fresh authority re-allocates sequences from the same floor,
        // so the lookup can return the NEW session's claim for the same number).
        // Both cases arise when the boundary falls between `admit` — which runs
        // inside the `Envelope.x(.)` argument list — and this capture, and in
        // both of them every comparison of two live-ish values agrees, so a gate
        // built only from them passes and S1's record is appended through S2's
        // queue. The bytes are the only IMMUTABLE, SELF-DESCRIBING fact about
        // which session built this record.
        //
        // `LossAuthority.RecordFields.parse` is the ONE existing reader of a
        // record's owner (`sessionOwnedPrefix` and the authority's own recovery
        // both derive the owner through it). A second parser here would be a
        // second opinion about who owns a record.
        //
        // AND IT IS READ IN THE WORKER, NOT HERE — the correction of a cost
        // argument of mine that was WRONG, and review was right to reject it.
        //
        // The three captures above MUST be on this thread: they read MUTABLE
        // state, so reading them later reads a different session. The record's
        // own session is the opposite — `rec.bytes` is IMMUTABLE, so parsing it
        // here or in the worker yields the identical answer. Its first placement
        // here was pattern-matched from its three neighbours rather than
        // reasoned about.
        //
        // That mattered because this is not a cheap read. `captureScreen` takes
        // a `View`, so it runs on the UI thread, and a hierarchy record is
        // bounded by `ViewHierarchySnapshotter.Budget.maxNodes` = 3_000 nodes —
        // a full `JSONObject` parse of that, per capture, on the main thread.
        // The cost note that used to sit here said the parse was "orders of
        // magnitude below the append"; the append runs on the WORKER, so its
        // cost says nothing about this thread's latency. Comparing a
        // caller-thread cost against worker-thread work was the error.
        //
        // See the worker body for the parse and for what an unreadable record
        // does (disclosed, never refused).
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
            // F-10 GATE: the transport this record was ADMITTED through
            // must still be the live transport, or nothing happens here.
            //
            // Four facts, in order:
            //
            // · `bracket !== b` — the queue home was replaced. Appending a
            // finished session's record into the new session's queue is an
            // orphaned write the loss accounting can never explain, and it
            // is the SAME harm the `b == null` arm above already refuses.
            // · `authority !== owner` — the authority was replaced.
            // · the RECORD'S OWN session, from its own bytes, disagreeing with
            // the live authority's. This is the one that survives a cleared
            // or re-allocated `tickets` map, and it is the arm that closes
            // the teardown path.
            // · the CLAIM at this sequence belonging to a different session
            // than the record — the number was reused, and settling that
            // claim would resolve another session's accounting.
            //
            // WHICH ARMS ARE INDEPENDENTLY LOAD-BEARING, MEASURED RATHER
            // THAN ASSERTED — the mutation controls for this change found that
            // disabling any ONE of the first two is caught by NOTHING, and a
            // condition nobody can show failing is a condition nobody is
            // protecting.
            //
            // · `recordSession` and `claimedSession` ARE independently
            // load-bearing, and both cases are production-reachable. `admit`
            // runs inside the `Envelope.x(.)` argument list and this
            // function captures the transport a statement later, so a
            // boundary can fall BETWEEN them. If it does, `b` and `owner`
            // are already the NEW session's, so both identity comparisons
            // are false, and then: the claim is either GONE (the teardown
            // cleared `tickets`) or FOREIGN (the new session reused the
            // sequence and the lookup returns ITS claim, which agrees with
            // the live authority). `F10CrossSessionSettlementTest` isolates
            // each; removing either line reds its own row.
            // · the two IDENTITY comparisons are MUTUALLY REDUNDANT, and that
            // is a property of the writers rather than of this gate:
            // `start` assigns `bracket` (:464) and then `authority` (:476),
            // and the governed teardown clears `bracket` (:3498) and then
            // `authority` (:3501) — so no production path moves one without
            // the other, and neither can be the SOLE cause of a refusal.
            // They are kept as defence in depth, deliberately: the append
            // consumes `b` and the settlement consumes `owner`, they are
            // statements about two DIFFERENT objects, and a future change
            // that separated those two writes would otherwise open the hole
            // silently. No test claims to isolate them, and the control
            // report records them as NOT_CAUGHT with this reason instead of
            // hiding it.
            //
            // A MISSING CLAIM WHOSE RECORD'S SESSION IS STILL LIVE IS NOT
            // REFUSED. Review asked for `ticket == null -> refuse`; that would
            // DISCARD a record belonging to the live session, and the durable
            // append is unconditional by design (F4). `enqueue` has exactly ONE
            // caller, `enqueueDisclosing`, whose `build` stamps the record
            // through `admit`, so a sequence >= 0 always HAD a claim — if it is
            // gone while the record's session is still live, the right answer is
            // to store the bytes and DISCLOSE the anomaly, not to destroy them.
            // Nothing is mis-settled either: `settleAppend(null,.)` returns.
            //
            // A refusal restores the in-flight PRE-F6 counts, exactly as every
            // other non-committing path does: this record reaches no durable
            // append, so a disclosure it took out of the accumulator must go
            // back or a session boundary ERASES it. It settles nothing — a
            // claim belonging to a session that has ended is reconciled from
            // disk at the next open, which is the one place the real state is.
            // The record's OWN session, from its OWN immutable bytes. In the
            // worker because the bytes cannot change and the caller's thread is
            // the UI thread for a hierarchy capture (see `enqueue`'s note).
            // `LossAuthority.RecordFields.parse` is the ONE existing reader of a
            // record's owner — `sessionOwnedPrefix` and the authority's own
            // recovery both derive it there, so this is not a second opinion
            // about who owns a record.
            //
            // A record whose bytes cannot state an owner is NOT refused, it is
            // DISCLOSED: refusing would discard a record for being unreadable to
            // this parser, which is the mistake `sessionOwnedPrefix`'s own
            // comment records (it gives such a head a fallback ADDRESS rather
            // than dropping it). Every arm that can still speak below does.
            val recordSession = com.tracescout.mobile.internal.loss.LossAuthority
                .RecordFields.parse(rec.bytes)?.sessionId
            if (recordSession == null) {
                RecoveryTrace.log("enqueue_record_owner_unreadable",
                    "eventType" to rec.eventType, "sequence" to rec.sequence)
            }
            val liveBracket = bracket
            val liveAuthority = authority
            val stale = when {
                liveBracket !== b -> "bracket_replaced"
                liveAuthority !== owner -> "authority_replaced"
                // THE LOAD-BEARING ARM, and it replaced a WEAKER one. The
                // first version compared the CLAIM's session with the live
                // authority's, which cannot speak for the teardown path at all:
                // `resetGoverned` clears `tickets` BEFORE the next session
                // starts, so there is no pre-boundary claim left to compare, and
                // when the next session has reused the sequence the claim that
                // IS there belongs to the live session and agrees with it. Both
                // of those passed the weaker arm. The record's own session
                // cannot be erased or substituted by either.
                recordSession != null && liveAuthority?.sessionId != recordSession ->
                    "record_session_not_live"
                // The claim found at this sequence was minted for a DIFFERENT
                // session than the one that built the record. Settling it would
                // resolve another session's accounting with this record's
                // outcome, and `settleAppend` refuses it again at the point of
                // settlement — this arm refuses the APPEND as well, because by
                // then the bytes are already in someone else's queue.
                claimedSession != null && recordSession != null &&
                    claimedSession != recordSession -> "claim_foreign"
                else -> null
            }
            if (stale == null && claimedSession == null) {
                // Not a refusal — a DISCLOSURE. See the note above: the record's
                // own session is live, so the bytes belong in this queue; what is
                // missing is only the claim that would settle them.
                RecoveryTrace.log("enqueue_claim_absent",
                    "eventType" to rec.eventType, "sequence" to rec.sequence,
                    "recordSession" to (recordSession ?: "unreadable"))
            }
            if (stale != null) {
                returnPreF6Dropped(rec.eventId)
                RecoveryTrace.log("enqueue_session_replaced",
                    "why" to stale, "eventType" to rec.eventType, "sequence" to rec.sequence,
                    "recordSession" to (recordSession ?: "unreadable"),
                    "claimedSession" to (claimedSession ?: "none"))
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
                settleAppend(ticket, owner, r.stored, r.durability)
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
                settleAppend(ticket, owner, false, com.tracescout.mobile.queue.EncryptedEventStore.Durability.NOT_COMMITTED)
                returnPreF6Dropped(rec.eventId)
            }
        }
    }

    /**
 * The SEQUENCE-ADDRESSED entry, kept for the crash path only — a crash
 * handler runs in the dying process on the session that crashed, holds no
 * captured claim, and has no later session to be confused with. It resolves
 * the claim and the handle and then goes through the ONE bound rule below;
 * it does not decide anything itself.
 */
    private fun settleAppend(sequence: Long, stored: Boolean, durability: com.tracescout.mobile.queue.EncryptedEventStore.Durability) {
        val h = authority ?: return
        val t = tickets[sequence] ?: return
        settleAppend(t, h, stored, durability)
    }

    /**
 * F-10: A SETTLEMENT IS BOUND TO THE CLAIM AND TO THE HANDLE THAT
 * MINTED IT — never to whatever `authority` happens to hold when this runs.
 *
 * The worker-boundary gate proves the transport is unchanged BEFORE the
 * append; it cannot speak for the window DURING one. An append fsyncs, and
 * a teardown plus a `start` inside that window would leave the old
 * three-argument form reading the NEW session's `authority` and removing
 * the NEW session's ticket for a reused sequence — settling one session's
 * record against another's accounting. So the two facts travel in as
 * arguments.
 *
 * The removal is CONDITIONAL (`remove(k, v)` is atomic on the map): a stale
 * task must never destroy a live session's claim. The loser of that race
 * settles nothing, which is the safe direction — a claim that is merely
 * unsettled is reconciled from disk at the next open, while a DESTROYED
 * claim is a gap the authority could never explain.
 *
 * The parameters are deliberately named `t` and `h`: the refused-append
 * line below is pinned byte-for-byte by `SliceP2SourceTest`, and renaming
 * the locals would have re-pointed an accepted pin for no behavioural
 * reason.
 */
    private fun settleAppend(
        t: com.tracescout.mobile.internal.loss.LossAuthority.Ticket?,
        h: com.tracescout.mobile.internal.loss.LossAuthority.Handle?,
        stored: Boolean,
        durability: com.tracescout.mobile.queue.EncryptedEventStore.Durability,
    ) {
        if (t == null || h == null) return
        if (!tickets.remove(t.sequence, t)) {
            // Someone else owns this sequence now. Loud, and no mutation: this
            // is the cross-session case the conditional removal exists for.
            RecoveryTrace.log("authority_settle_not_owned",
                "sequence" to t.sequence, "session" to t.sessionId)
            return
        }
        if (h.sessionId != t.sessionId) {
            // Reachable: `admit` runs inside the `Envelope.x(.)` argument list
            // and `enqueue` captures `authority` a statement later, so a
            // `start` between the two hands this record a handle that did not
            // mint its claim. Settling through it would attribute the record to
            // the wrong session's accounting.
            RecoveryTrace.log("authority_settle_session_mismatch",
                "claimed" to t.sessionId, "owner" to h.sessionId, "sequence" to t.sequence)
            return
        }
        when {
            stored && durability == com.tracescout.mobile.queue.EncryptedEventStore.Durability.COMMITTED -> h.settleAdmitted(t)
            !stored -> h.settleLost(t, com.tracescout.mobile.internal.loss.LossAuthority.Cause.APPEND_REFUSED)
            else -> RecoveryTrace.log("authority_settle_deferred", "sequence" to t.sequence, "durability" to durability.name)
        }
    }

    private fun installCrashHandler() {
        // A PRECONDITION, NOT A CAPTURE — and the difference is a real
        // G2 defect that review found. This line used to be
        // `val id = identity ?: return`, and the crash lambda below passed that
        // CAPTURED value to `Envelope.crash`. `installCrashHandler` runs once
        // inside `start`, which publishes the identity at a point BEFORE this
        // call and long before any `identify` can happen — so the captured
        // value's `userId` was null for the whole life of the session, and an
        // identified user's `mobile_crash` carried no identity.
        //
        // That contradicted three claims in this file at once: `identify`'s
        // own KDoc, `Identify.Decision.Applied`'s, and `envelopeFields`'
        // "EVERY EVENT, NOT THE OPENER". The server merge bounds the damage
        // while some LATER event carries the identity — but a crash with no
        // later event leaves the session anonymous, and a crash is precisely
        // the event an operator most wants attributed.
        //
        // `IdentifiedUserWireTest` could not see it: it builds records
        // directly from a prebuilt `SessionIdentity`, so it never exercises
        // what the handler captured. The guard for it is a WIRING row.
        //
        // So: still refuse to install without a session, but bind NOTHING —
        // the lambda re-reads the volatile field when it runs.
        if (identity == null) return
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
            //
            // READ AT CRASH TIME, NOT AT INSTALL TIME — see the comment on
            // the precondition above. `identity` is `@Volatile`, so this is a
            // plain volatile read: cheap, and safe on the dying thread. A
            // missing session THROWS, which is the convention this very block
            // already relies on for a refused claim: `CrashHandler` wraps
            // `recordBuilder` in its Rule-1 catch and chains the host handler
            // regardless, so an SDK fault can never swallow the host's crash.
            val id = identity ?: throw IllegalStateException("crash_no_session")
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
                // The CLASS of crash, through the twin pinned to the shared
                // incident vectors — never a literal. A React Native host
                // embeds this native SDK too, so the RN-wrapper branch is
                // reachable from here and must not be second-guessed locally.
                crashClass = IncidentPolicy.androidCrashClass(throwable.javaClass.name),
                exceptionClass = throwable.javaClass.name,
                // THE CAP IS THE SHARED ONE AND THE DROP IS DISCLOSED.
                // This used to `take(50)` — an undeclared local bound that
                // silently discarded the rest of a deep stack, which for a
                // crash is the half that names the cause. `framePlan` applies
                // the contract's 128 and reports what it dropped; `frames` has
                // no field for that, so the disclosure goes to the trace, the
                // established local channel for a fact with no wire field.
                frames = throwable.stackTrace.let { raw ->
                    val plan = IncidentPolicy.framePlan(raw.size)
                    if (plan.dropped > 0) {
                        RecoveryTrace.log("crash_frames_truncated",
                            "captured" to raw.size, "emitted" to plan.emitted,
                            "dropped" to plan.dropped)
                    }
                    (0 until plan.emitted).map { i ->
                        val e = raw[i]
                        Envelope.IncidentFrame(
                            index = i,
                            module = IncidentPolicy.boundModule(e.className),
                            symbol = IncidentPolicy.boundSymbol(
                                "${e.className}.${e.methodName}(${e.fileName}:${e.lineNumber})"),
                        )
                    }
                },
                threadName = thread.name,
                // Asked of the LOOPER, not of the thread's NAME. A host app can
                // name any thread "main", and a crash report that decided this
                // from a string would be reporting the app's choice of label as
                // a fact about where the crash happened.
                isMainThread = thread === android.os.Looper.getMainLooper().thread,
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
        // A PUBLICATION THAT DIED HERE MUST STILL BE CLOSED, OR EVERY
        // LATER DRAIN IS REFUSED FOR EVER. `start` throws inside its own
        // transport window on one reachable path — `loss_authority_unavailable`
        // and an unmatched open does not merely leave readers conservative
        // for one more read: it INVERTS parity permanently, because the next
        // open would take an odd counter to even and a reader inside THAT
        // window would see an even, unchanged value. The call is thread-owned
        // (see [transportPublisher]), so it closes only a window this thread
        // opened and is a no-op for every other guarded operation.
        abortTransportChange()
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
            // The segments named a session whose durable state is being torn down.
            com.tracescout.mobile.capture.WebViewAttachment.detachAll()
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
        // UNDER THE LOCK — the third writer. An unlocked teardown here let
        // a concurrent `identify` write back a copy of the identity this line
        // had just cleared; see the [identityWriteLock] KDoc for the exact
        // interleaving. The critical section is this one assignment, so the
        // leaf-lock property that makes a deadlock unrepresentable holds.
        synchronized(identityWriteLock) { identity = null }
        com.tracescout.mobile.capture.WebViewAttachment.detachAll()
        // EVERY opt-in consent switch is released here, and the test below
        // derives that set from THIS block rather than naming one. The network
        // switch was missed when it was added: `interactionEnabled` was reset
        // and `networkEnabled` was not, so after a teardown the host's OkHttp
        // seam — which the SDK never installs and never removes — kept reading
        // `networkCaptureEnabled == true` and recorded the NEXT session's
        // requests with no new opt-in. Two nearly-identical rules is how the
        // next inversion gets in.
        // Per-SESSION state, released with the session it described. Not consent
        // switches — `ConsentSwitchResetTest` derives that set from the
        // `…Enabled = false` shape and these two deliberately do not take it —
        // but leaving them set would charge the NEXT session's duration to a
        // dead anchor and would let a session that never ended look ended.
        sessionStartedMono = null; sessionEndClaim.set(null)
        interactionScreen = null; interactionEnabled = false
        // The SAME rule as the two above, for the same reason: a permission
        // must never outlive the session it was granted for.
        hostInteractionsEnabled = false
        networkEnabled = false
        lastResult = null
        lastFrameResult = null
        lastFrameRefusal = null
        return outcome
    }
}

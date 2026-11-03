package com.tracescout.mobile.rn

import android.content.Context
import android.os.Looper
import com.tracescout.mobile.capture.CrashHandler
import com.tracescout.mobile.capture.HangDetector
import com.tracescout.mobile.internal.loss.LossAuthority
import com.tracescout.mobile.queue.RecoveryTrace
import com.tracescout.rn.IncidentPolicy
import com.tracescout.rn.RnEnvelope
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * RN ANDROID CRASH + HANG CAPTURE — the platform seams, and nothing else.
 *
 * ## The gap this closes, and why the obvious fix could not have worked
 *
 * The Android native crash producer ships inside the React Native package:
 * `:rn` depends on `:sdk`, so `capture/CrashHandler` and `capture/HangDetector`
 * are both on an RN app's classpath. The measured finding was that RN startup
 * never initialises it — and the MECHANISM matters, because it rules out the
 * one-line fix. `CrashHandler` is installed by `TraceScoutMobile.start`, at
 * `installCrashHandler`, and **`TraceScoutMobile.start` is never called in
 * an RN-only app** (`RnBridgeSessionEndTest`'s own words). Calling
 * `installCrashHandler` from RN startup would NPE-or-no-op anyway: it reads
 * `TraceScoutMobile.identity`, `.store` and `.authority`, all of which are null
 * there, and it would build an `Envelope.crash` — the NATIVE producer's
 * envelope, with the native producer's `sdkFamily`.
 *
 * So the record has to be built by the RN producer, through the RN producer's
 * own seams: `RnEnvelope` for the bytes, this module's queue home for the
 * store, the RN session's own `LossAuthority` for the sequence.
 *
 * ## What is REUSED rather than copied
 *
 * `CrashHandler` is already producer-agnostic: it takes a store, an
 * `afterAppend` and a `recordBuilder`, and knows nothing about
 * `TraceScoutMobile`. So this file supplies an RN record builder to the SAME
 * handler, which means there is exactly ONE uncaught-exception handler
 * implementation, ONE chain-to-the-host rule, and ONE crash-region append
 * (`appendFromCrashHandler`, `QueueRegion.CRASH_APPEND`,
 * `Acquisition.BOUNDED`) in the whole SDK. A second crash path would have had
 * to re-derive "chain, never replace", "persist before chaining" and "bounded
 * budget on a dying thread" — three rules whose failure modes are each worse
 * than losing the crash.
 *
 * `HangDetector` is likewise reused verbatim. It had **zero production call
 * sites anywhere in the SDK** before this file: the watchdog was written,
 * unit-tested by constructing it directly, and reached by nothing but the
 * fixture's own `:crash` process. On Android NATIVE the hang path is the public
 * host API `TraceScoutMobile.recordHang(durationMs)` — which is why all 29
 * production hang rows carry round numbers an app passed in. This is the first
 * binding in which the detector actually observes a main thread.
 *
 * ## What this file deliberately does NOT do
 *
 * - **It installs no signal handler.** A `SIGSEGV`/`SIGABRT` handler is a
 * process-global seam the host app (or Play Core, or another vendor's SDK)
 * may already own, and a library that takes it can silently break the app's
 * own reporting. `ndk_signal` therefore stays unreachable from here, and the
 * policy does not claim it.
 * - **It decides no classification and no bound.** Those are
 * `IncidentPolicy`'s, in `rn-core`, pinned to the shared parity vectors, so
 * the Kotlin and Swift producers cannot disagree about what `js_uncaught`
 * means.
 * - **It emits no message.** See `IncidentPolicy`.
 */
object RnIncidentCapture {

    /** One install per process. A second would put the handler in its own chain. */
    private val installed = AtomicBoolean(false)

    @Volatile private var detector: HangDetector? = null

    /**
 * The sequence this crash record claimed, handed from the record builder to
 * `afterAppend` so the ticket can be settled with the SAME three states
 * every other append is settled with.
 *
 * A FIELD AND NOT A LOCAL, because the two halves run in different
 * callbacks of `CrashHandler` and there is no shared frame between them.
 * `-1` means "no claim outstanding", which is what makes a double-settle
 * impossible.
 */
    private val crashTicketSequence = AtomicLong(-1L)

    @Volatile private var crashTicket: LossAuthority.Ticket? = null

    /**
 * What the producer must know to describe an incident, supplied by whoever
 * owns the live session.
 *
 * A PROVIDER AND NOT A SNAPSHOT. The session identity and the current
 * route both change while the app runs, and a crash handler installed once
 * at startup with a copied identity would report the FIRST session for the
 * life of the process. The provider is read at incident time.
 *
 * IT RETURNS NULL WHEN THERE IS NO SESSION, and the caller treats that
 * as "do not emit" rather than inventing one. An incident attributed to a
 * session that does not exist is refused `404 session_not_found` by the
 * server, which this client classifies TERMINAL and QUARANTINES — so an
 * invented identity would destroy the record it was trying to save.
 */
    interface Context2 {
        /** The live RN session, or null if none is running. */
        fun identity(): RnEnvelope.Identity?

        /** The posture to stamp. Never relaxed by this producer. */
        fun posture(): RnEnvelope.Posture

        /** The current route id, or null. */
        fun screenId(): String?
    }

    /**
 * Install both observers for this process.
 *
 * Idempotent: a second call is a no-op, because installing the uncaught
 * handler twice would put it in its own chain and write the crash record
 * twice — and because `HangDetector` starts a watchdog thread.
 *
 * @param hangThresholdMs main-thread block past which a hang is reported.
 * Below `HangDetector.MIN_THRESHOLD_MS` the detector itself refuses rather
 * than reporting ordinary jank as a flood of hangs.
 * @return true if this call installed, false if already installed
 */
    @JvmStatic
    @JvmOverloads
    fun install(
        ctx: Context,
        ctx2: Context2,
        hangThresholdMs: Long = HangDetector.DEFAULT_THRESHOLD_MS,
        mainLooper: Looper = Looper.getMainLooper(),
    ): Boolean {
        if (!installed.compareAndSet(false, true)) return false
        val app = ctx.applicationContext
        installCrash(app, ctx2)
        installHang(app, ctx2, hangThresholdMs, mainLooper)
        return true
    }

    /** Stop the hang watchdog and restore the previous uncaught handler. Teardown and tests. */
    @JvmStatic
    fun uninstall() {
        detector?.stop()
        detector = null
        CrashHandler.uninstall()
        crashTicketSequence.set(-1L)
        crashTicket = null
        installed.set(false)
    }

    // ── crash ───────────────────────────────────────────────────────────────

    private fun installCrash(app: Context, ctx2: Context2) {
        val store = try {
            RnTransport.storeForIncidents(app)
        } catch (t: Throwable) {
            // A queue home that will not open is an honest failure: the crash
            // record would have nowhere to go, and installing a handler that
            // cannot persist is worse than not installing one, because the host
            // app's own handler still runs either way but we would have logged
            // nothing to say why nothing was recorded.
            RecoveryTrace.log("rn_crash_install_failed", "code" to t.javaClass.simpleName)
            return
        }
        val handler = CrashHandler.install(
            store,
            afterAppend = { stored, durability ->
                val seq = crashTicketSequence.getAndSet(-1L)
                val ticket = crashTicket
                crashTicket = null
                if (seq >= 0 && ticket != null) {
                    // THE SAME THREE-STATE SETTLEMENT AS EVERY OTHER APPEND.
                    // Collapsing `stored && COMMITTED` into one boolean turned
                    // `stored = true` + an INDETERMINATE directory — bytes that
                    // may well be on disk — into a durable FALSE LOSS for a
                    // record the next launch would then upload.
                    RnTransport.settleIncidentAppend(app, ticket, stored, durability)
                }
            },
        ) { thread, throwable ->
            buildCrashRecord(app, ctx2, thread, throwable)
        }
        if (handler == null) {
            RecoveryTrace.log("rn_crash_install_refused", "why" to "handler_null")
            return
        }
        // IDEMPOTENCE IS `CrashHandler`'S, AND IT RETURNS THE *EXISTING*
        // HANDLER. In a hybrid app that ALSO ran `TraceScoutMobile.start`,
        // the native handler is already registered and this call returns IT —
        // so the RN record builder is never used and RN crashes are recorded
        // as native ones. That is the correct conflict resolution (one handler,
        // first writer wins, the host's own handler still chains) but it must
        // be VISIBLE rather than look like a successful install.
        if (!handler.javaClass.name.startsWith("com.tracescout.mobile.capture.CrashHandler")) {
            RecoveryTrace.log("rn_crash_install_foreign", "class" to handler.javaClass.name)
        }
        RecoveryTrace.log("rn_crash_installed")
    }

    /**
 * Build the crash record ON THE DYING THREAD.
 *
 * IT REACHES NO AUTHORITY OF ITS OWN. The sequence comes from
 * `RnTransport.beginIncidentAdmission`, which is where the SETTLEMENT also
 * lives — one fact, one home. That seam is the file the `B9` module-boundary
 * guard enumerates, and it uses `peek` rather than `open` precisely because
 * `open` would reconstruct the authority by reading the ENTIRE queue
 * (`store.readAll`) — seconds of I/O on a thread racing the process's
 * death, inside the bounded crash region.
 *
 * A REFUSED CLAIM THROWS. `CrashHandler` catches it, traces, and chains
 * the host handler — so nothing is appended carrying a sequence the
 * authority does not own. Returning empty bytes instead would append a
 * record with sequence `-1`.
 */
    private fun buildCrashRecord(
        app: Context,
        ctx2: Context2,
        thread: Thread,
        throwable: Throwable,
    ): ByteArray {
        val id = ctx2.identity()
            ?: throw IllegalStateException("rn_crash_no_session")
        // THE CLAIM IS THE TRANSPORT'S, AND A MODULE-BOUNDARY GUARD IS WHY.
        // `ModuleBoundaryTest.B9` enumerates every `beginAdmission(` call site;
        // claiming here made this file a third one and the guard caught it. The
        // repair was not to re-point the enumeration but to put the claim where
        // its SETTLEMENT already lived — `RnTransport` — because they are one
        // fact and were split across two files. This producer asks for a ticket
        // and never reaches the authority itself.
        val ticket = RnTransport.beginIncidentAdmission(app, id.sessionId, "mobile_crash")
            ?: throw IllegalStateException("rn_crash_admission_unavailable")
        crashTicket = ticket
        crashTicketSequence.set(ticket.sequence)

        val raw = throwable.stackTrace
        val plan = IncidentPolicy.framePlan(raw.size)
        if (plan.dropped > 0) {
            // The wire has no field for a within-event truncation (see
            // `RnEnvelope.crash`), so the drop is disclosed HERE — the
            // established local channel for a fact with no wire field.
            RecoveryTrace.log(
                "rn_crash_frames_truncated",
                "captured" to raw.size, "emitted" to plan.emitted, "dropped" to plan.dropped,
            )
        }
        val frames = ArrayList<RnEnvelope.IncidentFrame>(plan.emitted)
        for (i in 0 until plan.emitted) {
            val e = raw[i]
            frames.add(
                RnEnvelope.IncidentFrame(
                    index = i,
                    module = IncidentPolicy.boundModule(e.className),
                    symbol = IncidentPolicy.boundSymbol(
                        "${e.className}.${e.methodName}(${e.fileName}:${e.lineNumber})",
                    ),
                ),
            )
        }
        val eventId = newEventId()
        return RnEnvelope.crash(
            id = id,
            eventId = eventId,
            sequence = ticket.sequence,
            timestampMillis = System.currentTimeMillis(),
            monotonicNanos = System.nanoTime(),
            posture = ctx2.posture(),
            // The event's own id IS the incident identity, as on the native
            // producer: one crash, one record, one `UNIQUE (session_row_id,
            // error_id)` row, and a retried upload upserts rather than
            // duplicating. Bounded by construction — see `newEventId`.
            errorId = eventId,
            crashClass = IncidentPolicy.androidCrashClass(throwable.javaClass.name),
            exceptionType = throwable.javaClass.name,
            threadName = thread.name,
            isMainThread = thread === Looper.getMainLooper().thread,
            frames = frames,
            screenId = ctx2.screenId(),
        ).toByteArray(Charsets.UTF_8)
    }

    // ── hang ────────────────────────────────────────────────────────────────

    private fun installHang(
        app: Context,
        ctx2: Context2,
        thresholdMs: Long,
        mainLooper: Looper,
    ) {
        val d = try {
            HangDetector(thresholdMillis = thresholdMs, mainLooper = mainLooper) { blockedMs, stack ->
                onHang(app, ctx2, blockedMs, stack, thresholdMs)
            }
        } catch (t: IllegalArgumentException) {
            // The detector refuses a threshold below its own floor rather than
            // reporting ordinary jank as a flood of hangs. An honest refusal.
            RecoveryTrace.log(
                "rn_hang_install_refused",
                "why" to "threshold_below_floor", "thresholdMs" to thresholdMs,
            )
            return
        }
        detector = d
        d.start()
        RecoveryTrace.log("rn_hang_installed", "thresholdMs" to thresholdMs)
    }

    /**
 * A hang observation. UNLIKE the crash path this runs on the watchdog
 * thread of a LIVE process, so it takes the ORDINARY enqueue — the bounded
 * crash budget exists for a thread racing the process's death and claiming
 * it here would be claiming a budget this path does not need.
 */
    private fun onHang(
        app: Context,
        ctx2: Context2,
        blockedMs: Long,
        stack: Array<StackTraceElement>,
        thresholdMs: Long,
    ) {
        // The policy decides whether this is emittable at all — a non-finite or
        // non-positive duration is refused there, because the server reads
        // `blockedMs` with no range and a hang lane that can show a negative
        // block is worse than one that shows nothing.
        if (!IncidentPolicy.hangIsEmittable(blockedMs, thresholdMs)) {
            RecoveryTrace.log(
                "rn_hang_not_emittable", "blockedMs" to blockedMs, "thresholdMs" to thresholdMs,
            )
            return
        }
        val id = ctx2.identity() ?: return
        val plan = IncidentPolicy.framePlan(stack.size)
        if (plan.dropped > 0) {
            RecoveryTrace.log(
                "rn_hang_frames_truncated",
                "captured" to stack.size, "emitted" to plan.emitted, "dropped" to plan.dropped,
            )
        }
        val frames = ArrayList<RnEnvelope.IncidentFrame>(plan.emitted)
        for (i in 0 until plan.emitted) {
            val e = stack[i]
            frames.add(
                RnEnvelope.IncidentFrame(
                    index = i,
                    module = IncidentPolicy.boundModule(e.className),
                    symbol = IncidentPolicy.boundSymbol(
                        "${e.className}.${e.methodName}(${e.fileName}:${e.lineNumber})",
                    ),
                ),
            )
        }
        val screen = ctx2.screenId()
        val posture = ctx2.posture()
        RnTransport.enqueue(app) { assigned ->
            val eventId = newEventId()
            RnEnvelope.hang(
                id = id,
                eventId = eventId,
                sequence = assigned,
                timestampMillis = System.currentTimeMillis(),
                monotonicNanos = System.nanoTime(),
                posture = posture,
                errorId = eventId,
                hangKind = IncidentPolicy.hangKindFor("android"),
                blockedMs = blockedMs,
                thresholdMs = thresholdMs,
                // OBSERVED, NOT INFERRED. The watchdog is still running, so
                // the block has demonstrably been survived. Whether the process
                // is killed LATER is not knowable here, and a producer that
                // guessed `false` would be reporting a process death that has
                // not happened.
                recovered = true,
                frames = frames,
                screenId = screen,
            ).toByteArray(Charsets.UTF_8)
        }
        // A hang is observed in a live process, so it does not have to wait for
        // the next launch — ask for a drain the way every other producer path
        // does, and let `FlushSchedule` decide what the moment means.
        RnTransport.requestDrain(app, "hang")
    }

    /**
 * `evt_` + 32 hex = 36 chars.
 *
 * WITHIN BOTH BOUNDS BY CONSTRUCTION, which is why nothing truncates it:
 * the envelope's `eventId` pattern admits `[A-Za-z0-9_-]{8,64}` and the
 * server's `errorId` bound is 64. A truncated identity would be a DIFFERENT
 * incident and two crashes could collide under
 * `UNIQUE (session_row_id, error_id)`.
 */
    private fun newEventId(): String = "evt_" + UUID.randomUUID().toString().replace("-", "")
}

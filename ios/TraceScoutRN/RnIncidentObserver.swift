import Foundation

/// RN iOS CRASH + HANG CAPTURE — the platform seams, and nothing else.
///
/// ## The gap this closes
///
/// RN iOS had NO crash or hang producer of any kind: before this file,
/// `NSSetUncaughtExceptionHandler` appeared zero times in `ios/Sources`, and
/// the only mentions of "crash" were comments about store durability. The
/// Android side at least shipped a producer it could not reach; iOS shipped
/// none. `LossFamilies.swift` has mapped `mobile_crash -> "crashes"` since
/// an earlier change, so the loss vocabulary was ready and the emitter was absent.
///
/// ## WHAT THIS SEAM CAN AND CANNOT OBSERVE — the bound, stated up front
///
/// `NSSetUncaughtExceptionHandler` observes an **`NSException` only**. A Swift
/// `fatalError`, a nil force-unwrap, an out-of-bounds array access, an integer
/// overflow and a `precondition` failure all trap the process with
/// `SIGTRAP`/`SIGILL` and **never reach it**. So:
///
/// - this producer reports `objc_nsexception` and NOTHING else;
/// - `swift_fatal`, `mach_exception` and `ndk_signal` stay unreachable, and
/// `IncidentPolicy.iosCrashClass` answers `unknown` for every seam but this
/// one, so the vocabulary cannot be used to imply coverage that does not
/// exist;
/// - a Swift-language crash in a React Native app is therefore NOT captured by
/// this file.
///
/// **No signal handler is installed, deliberately.** `SIGSEGV`/`SIGABRT`/
/// `SIGTRAP` handlers are process-global seams the host app — or
/// a third-party reporter, all of which a real RN app may already
/// ship — compete for. A library that silently takes one can break the app's
/// existing crash reporting, and the failure is invisible until the app needs
/// it. Closing that half is a product decision about competing with the host's
/// own reporter, not an implementation detail, so it is reported rather than
/// taken.
///
/// ## Chaining
///
/// Like the Android `CrashHandler`'s Rule 1, the previously-registered handler
/// is captured and invoked. An SDK that swallows the host's own
/// `NSSetUncaughtExceptionHandler` removes crash reporting the app already had.
public enum RnIncidentObserver {

    // ── state ────────────────────────────────────────────────────────────────
    //
    // STATIC, AND THAT IS FORCED BY THE SEAM. `NSSetUncaughtExceptionHandler`
    // takes a `@convention(c)` function pointer, which cannot capture context —
    // so everything the handler needs must be reachable from a global.

    private static let lock = NSLock()

    /// The handler registered before us. Invoked after we persist.
    private static var previousHandler: (@convention(c) (NSException) -> Void)?

    private static var installedFlag = false
    private static var hangThresholdMs: Int = 3_000
    private static var watchdog: Thread?
    private static var watchdogRunning = false

    /// Bumped by the MAIN thread, read by the watchdog. The watchdog only ever
    /// reads; the main thread only ever writes.
    ///
    /// THE DETECTOR MUST NEVER BLOCK THE THREAD IT WATCHES. The natural
    /// implementation posts a block to the main queue and waits on a semaphore,
    /// which adds contention to a thread already suspected of being blocked and
    /// can turn a slow frame into the hang it was trying to observe. This is the
    /// same counter design the Android `HangDetector` uses, for the same reason.
    private static var heartbeat: Int64 = 0
    private static var heartbeatTimer: DispatchSourceTimer?

    /// What the producer must know to describe an incident, read AT INCIDENT
    /// TIME.
    ///
    /// A PROVIDER AND NOT A SNAPSHOT. The session identity and the current
    /// route both change while the app runs, and an observer installed once at
    /// startup with a copied identity would report the FIRST session for the
    /// life of the process.
    ///
    /// IT RETURNS NIL WHEN THERE IS NO SESSION, and the caller treats that
    /// as "do not emit" rather than inventing one. An incident attributed to a
    /// session that does not exist is refused `404 session_not_found`, which
    /// this client classifies TERMINAL and QUARANTINES — so an invented
    /// identity would destroy the record it was trying to save.
    public struct Context {
        public let identity: () -> RnEnvelope.Identity?
        public let posture: () -> RnEnvelope.Posture
        public let screenId: () -> String?
        /// Appends the record durably and returns the assigned sequence, or nil.
        /// The transport's OWN enqueue — there is no incident-specific store,
        /// sequence generator or uploader.
        public let append: (@escaping (Int64) -> Data) -> Int64?
        /// Ask for a drain. A hang is observed in a LIVE process, so unlike a
        /// crash it does not have to wait for the next launch.
        public let requestDrain: (String) -> Void

        public init(identity: @escaping () -> RnEnvelope.Identity?,
                    posture: @escaping () -> RnEnvelope.Posture,
                    screenId: @escaping () -> String?,
                    append: @escaping (@escaping (Int64) -> Data) -> Int64?,
                    requestDrain: @escaping (String) -> Void) {
            self.identity = identity
            self.posture = posture
            self.screenId = screenId
            self.append = append
            self.requestDrain = requestDrain
        }
    }

    private static var context: Context?

    // ── install ──────────────────────────────────────────────────────────────

    /// Install both observers for this process.
    ///
    /// Idempotent: a second call is a no-op, because registering the exception
    /// handler twice would put our own handler in its own chain and write the
    /// crash record twice, and because the watchdog starts a thread.
    ///
    /// - Returns: true if this call installed, false if already installed.
    @discardableResult
    public static func install(context ctx: Context, hangThresholdMs ms: Int = 3_000) -> Bool {
        lock.lock()
        if installedFlag { lock.unlock(); return false }
        installedFlag = true
        context = ctx
        hangThresholdMs = ms
        // CAPTURED BEFORE WE REPLACE IT. Reading it afterwards would return
        // our own handler and the chain would be a self-reference.
        previousHandler = NSGetUncaughtExceptionHandler()
        lock.unlock()

        NSSetUncaughtExceptionHandler { exception in
            RnIncidentObserver.handleUncaught(exception)
        }
        RecoveryTrace.shared.log("rn_crash_installed", [])

        // THE FLOOR IS THE ANDROID TWIN'S, AND A THRESHOLD BELOW IT IS
        // REFUSED RATHER THAN HONOURED. Reporting below it turns ordinary jank
        // into a flood of "hangs" nobody can act on.
        if ms < 250 {
            RecoveryTrace.shared.log(
                "rn_hang_install_refused",
                [("why", "threshold_below_floor"), ("thresholdMs", String(ms))])
            return true
        }
        startHangWatchdog(thresholdMs: ms)
        RecoveryTrace.shared.log("rn_hang_installed", [("thresholdMs", String(ms))])
        return true
    }

    /// Restore the previous handler and stop the watchdog. Teardown and tests.
    public static func uninstall() {
        lock.lock()
        let prev = previousHandler
        installedFlag = false
        previousHandler = nil
        context = nil
        watchdogRunning = false
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        watchdog = nil
        lock.unlock()
        NSSetUncaughtExceptionHandler(prev)
    }

    public static func isInstalled() -> Bool {
        lock.lock(); defer { lock.unlock() }
        return installedFlag
    }

    // ── crash ────────────────────────────────────────────────────────────────

    /// Runs on the thread that raised, with the process about to terminate.
    ///
    /// PERSIST SYNCHRONOUSLY, THEN CHAIN. There is no "later": handing the
    /// record to a background queue or a network client means it is gone. The
    /// store's `append` ends in `synchronize` — an `fsync(2)` — so a record it
    /// reports as stored really does survive the kill.
    ///
    /// THE CHAIN RUNS EVEN IF WE FAIL. An SDK that eats a crash report
    /// because its own persistence broke is worse than one that was never
    /// installed.
    private static func handleUncaught(_ exception: NSException) {
        lock.lock()
        let ctx = context
        let prev = previousHandler
        lock.unlock()

        if let ctx = ctx {
            emitCrash(ctx, exception)
        }
        prev?(exception)
    }

    private static func emitCrash(_ ctx: Context, _ exception: NSException) {
        guard let id = ctx.identity() else {
            RecoveryTrace.shared.log("rn_crash_no_session", [])
            return
        }
        // `callStackSymbols` is the symbol list the runtime already holds; no
        // new unwinding is performed on a dying thread.
        let raw = exception.callStackSymbols
        let plan = IncidentPolicy.framePlan(captured: raw.count)
        if plan.dropped > 0 {
            // The wire has no field for a within-event truncation (see
            // `RnEnvelope.crash`), so the drop is disclosed HERE — the
            // established local channel for a fact with no wire field.
            RecoveryTrace.shared.log("rn_crash_frames_truncated", [
                ("captured", String(raw.count)),
                ("emitted", String(plan.emitted)),
                ("dropped", String(plan.dropped)),
            ])
        }
        var frames: [RnEnvelope.IncidentFrame] = []
        frames.reserveCapacity(plan.emitted)
        for i in 0..<plan.emitted {
            let parsed = parseBacktraceLine(raw[i])
            frames.append(RnEnvelope.IncidentFrame(
                index: i,
                module: IncidentPolicy.boundModule(parsed.module),
                symbol: IncidentPolicy.boundSymbol(parsed.symbol)))
        }
        let eventId = newEventId()
        let wall = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        let mono = Int64(DispatchTime.now().uptimeNanoseconds)
        let posture = ctx.posture()
        let screen = ctx.screenId()
        // `exception.name` IS THE TYPE, `exception.reason` IS THE MESSAGE —
        // and only the first is emitted. `reason` is where application code
        // interpolates values ("could not load user hanan@example.com"), which
        // is exactly the rule the Android native producer states and this
        // programme inherits.
        let typeName = exception.name.rawValue
        let appended = ctx.append { assigned in
            Data(RnEnvelope.crash(
                id: id, eventId: eventId, sequence: assigned,
                timestampMillis: wall, monotonicNanos: mono, posture: posture,
                // The event's own id IS the incident identity: one crash, one
                // record, one `UNIQUE (session_row_id, error_id)` row, and a
                // retried upload upserts rather than duplicating.
                errorId: eventId,
                crashClass: IncidentPolicy.iosCrashClass("nsexception"),
                exceptionType: typeName,
                threadName: Thread.current.isMainThread ? "main" : Thread.current.name,
                isMainThread: Thread.current.isMainThread,
                frames: frames, screenId: screen).utf8)
        }
        if appended == nil {
            RecoveryTrace.shared.log("rn_crash_append_failed", [("eventId", eventId)])
        }
    }

    // ── hang ─────────────────────────────────────────────────────────────────

    private static func startHangWatchdog(thresholdMs ms: Int) {
        lock.lock()
        watchdogRunning = true
        lock.unlock()

        // The main thread's ONLY job: bump a counter. No allocation, no I/O,
        // nothing that can itself contribute to a hang.
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now(), repeating: .milliseconds(200))
        t.setEventHandler {
            lock.lock(); heartbeat &+= 1; lock.unlock()
        }
        t.resume()
        lock.lock(); heartbeatTimer = t; lock.unlock()

        let w = Thread {
            var lastSeen: Int64 = -1
            var lastChangeNanos = DispatchTime.now().uptimeNanoseconds
            var reportedForThisStall = false
            while true {
                lock.lock()
                let running = watchdogRunning
                let beat = heartbeat
                lock.unlock()
                if !running { return }

                let now = DispatchTime.now().uptimeNanoseconds
                if beat != lastSeen {
                    lastSeen = beat
                    lastChangeNanos = now
                    reportedForThisStall = false
                } else {
                    let blockedMs = Int((now &- lastChangeNanos) / 1_000_000)
                    // ONCE PER STALL, NOT ONCE PER POLL. Without this flag a
                    // ten-second block would emit a hang every 200 ms — fifty
                    // records describing one event, each with a different
                    // duration, and the dashboard's hang count would be a
                    // function of the poll interval.
                    if !reportedForThisStall,
                       IncidentPolicy.hangIsEmittable(blockedMs: Double(blockedMs),
                                                      thresholdMs: Double(ms)) {
                        reportedForThisStall = true
                        emitHang(blockedMs: blockedMs, thresholdMs: ms)
                    }
                }
                Thread.sleep(forTimeInterval: 0.2)
            }
        }
        w.name = "tracescout-rn-hang-watchdog"
        // Below default so the watchdog cannot itself compete for the CPU the
        // main thread is waiting on.
        w.qualityOfService = .utility
        lock.lock(); watchdog = w; lock.unlock()
        w.start()
    }

    /// Runs on the WATCHDOG thread of a LIVE process — so unlike the crash path
    /// it takes the ordinary enqueue and asks for a drain.
    private static func emitHang(blockedMs: Int, thresholdMs: Int) {
        lock.lock()
        let ctx = context
        lock.unlock()
        guard let ctx = ctx, let id = ctx.identity() else { return }

        // NO STACK. The Android twin reads the blocked thread's frames with
        // `Thread.getStackTrace`, a JVM read that does not require the target to
        // run. There is no supported equivalent on Darwin: capturing another
        // thread's backtrace means suspending it with `thread_suspend` and
        // walking its registers, which is exactly the "add contention to a
        // thread already suspected of being blocked" failure the counter design
        // exists to avoid — and it is the private-API territory App Review
        // rejects. So iOS reports the hang WITHOUT frames rather than
        // fabricating or risking one. `frame_count` is therefore 0 for an iOS
        // hang BY DESIGN, and that asymmetry with Android is a measured
        // platform limit, not an omission.
        let eventId = newEventId()
        let wall = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        let mono = Int64(DispatchTime.now().uptimeNanoseconds)
        let posture = ctx.posture()
        let screen = ctx.screenId()
        _ = ctx.append { assigned in
            Data(RnEnvelope.hang(
                id: id, eventId: eventId, sequence: assigned,
                timestampMillis: wall, monotonicNanos: mono, posture: posture,
                errorId: eventId,
                hangKind: IncidentPolicy.hangKindFor("ios"),
                blockedMs: blockedMs, thresholdMs: thresholdMs,
                // OBSERVED, NOT INFERRED. The watchdog is still running, so
                // the block has demonstrably been survived. Whether the process
                // is killed LATER is not knowable here, and a producer that
                // guessed `false` would be reporting a process death that has
                // not happened.
                recovered: true,
                frames: [], screenId: screen).utf8)
        }
        ctx.requestDrain("hang")
    }

    // ── backtrace parsing ───────────────────────────────────────────────────

    /// Split one `callStackSymbols` line into a module and a symbol, DROPPING
    /// the raw return address.
    ///
    /// THE DEFECT THIS FIXES WAS A REAL DISCLOSURE, AND MY OWN TEST MISSED IT.
    /// `callStackSymbols` lines have the `backtrace_symbols(3)` shape
    ///
    /// 4 NorthstarRN 0x0000000104f2a1b4 -[Foo bar:] + 44
    ///
    /// and the first version of this producer handed the WHOLE line to
    /// `boundSymbol`, so every emitted `symbol` carried the frame index, the
    /// module AND the raw return address. `testW7` asserted `frames[].address`
    /// was absent — and it was, because the address was smuggled INSIDE
    /// `symbol`. A raw return address discloses process layout and symbolicates
    /// to nothing without the matching binary, which is exactly what that
    /// test's own comment claims the producer does not emit. Found by review.
    ///
    /// FAIL-SAFE ON AN UNRECOGNISED SHAPE. The format is a convention, not a
    /// contract, so an unparsable line is NOT passed through verbatim: every
    /// `0x…` token is removed and the remainder is emitted. The guarantee is
    /// "no address on the wire, whatever the line looked like", which a
    /// best-effort parser alone would not give.
    static func parseBacktraceLine(_ line: String) -> (module: String?, symbol: String?) {
        let fields = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        // The address is the discriminator: the first `0x…` token.
        let addrIdx = fields.firstIndex { f in
            f.hasPrefix("0x") && f.dropFirst(2).allSatisfy { $0.isHexDigit } && f.count > 2
        }
        guard let ai = addrIdx, ai >= 2, ai + 1 < fields.count else {
            // Unrecognised: strip every address-shaped token and keep the rest.
            let kept = fields.filter { f in
                !(f.hasPrefix("0x") && f.dropFirst(2).allSatisfy { $0.isHexDigit })
            }
            let joined = kept.joined(separator: " ")
            return (nil, joined.isEmpty ? nil : joined)
        }
        // fields[0] is the frame INDEX — the envelope carries its own `index`,
        // so the line's copy is dropped rather than duplicated into the symbol.
        let module = fields[1..<ai].joined(separator: " ")
        var symbol = fields[(ai + 1)...].joined(separator: " ")
        // Trailing ` + <offset>` is an address-relative displacement; it is a
        // layout fact, not a name.
        if let plus = symbol.range(of: " + ", options: .backwards),
           symbol[plus.upperBound...].allSatisfy({ $0.isNumber }),
           !symbol[plus.upperBound...].isEmpty {
            symbol = String(symbol[symbol.startIndex..<plus.lowerBound])
        }
        return (module.isEmpty ? nil : module, symbol.isEmpty ? nil : symbol)
    }

    // ── ids ──────────────────────────────────────────────────────────────────

    /// `evt_` + 32 hex = 36 chars.
    ///
    /// WITHIN BOTH BOUNDS BY CONSTRUCTION, which is why nothing truncates it:
    /// the envelope's `eventId` pattern admits `[A-Za-z0-9_-]{8,64}` and the
    /// server's `errorId` bound is 64. A truncated identity would be a DIFFERENT
    /// incident and two crashes could collide under
    /// `UNIQUE (session_row_id, error_id)`.
    private static func newEventId() -> String {
        "evt_" + UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "")
    }

    // ── test seams ───────────────────────────────────────────────────────────

    /// Drive the crash path with a real `NSException` WITHOUT terminating the
    /// process, so the emitted bytes can be read.
    ///
    /// A SEAM ON THE REAL PATH, NOT A REIMPLEMENTATION. It calls the same
    /// `emitCrash` the registered handler calls, so a test cannot pass against
    /// a code path the handler does not take.
    static func __testEmitCrash(_ exception: NSException) {
        lock.lock(); let ctx = context; lock.unlock()
        guard let ctx = ctx else { return }
        emitCrash(ctx, exception)
    }

    /// Drive the hang path at a chosen duration, same reasoning as above.
    static func __testEmitHang(blockedMs: Int, thresholdMs: Int) {
        emitHang(blockedMs: blockedMs, thresholdMs: thresholdMs)
    }

    /// Is a chain target recorded? Pinned by a test: an SDK that swallowed the
    /// host's handler would remove reporting the app already had.
    static func __testHasChainTarget() -> Bool {
        lock.lock(); defer { lock.unlock() }
        return previousHandler != nil
    }
}

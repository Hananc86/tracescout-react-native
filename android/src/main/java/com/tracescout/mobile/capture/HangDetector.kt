package com.tracescout.mobile.capture

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/**
 * Native-core the specification HangDetector — main-thread hangs / ANRs.
 *
 * **The detector must never block the thread it watches.** That sounds obvious
 * and is the easiest thing to get wrong: the natural implementation posts a
 * task to the main thread and waits on a latch, which adds contention to a
 * thread already suspected of being blocked and can turn a slow frame into the
 * hang it was trying to observe.
 *
 * So instead: a watchdog thread reads a counter that the MAIN thread bumps.
 * The watchdog only ever reads; the main thread only ever writes an atomic. If
 * the counter stops advancing for longer than the threshold, the main thread is
 * blocked — established without asking it to do anything.
 *
 * A stack for the hang is captured from the watchdog via `Thread.getStackTrace`
 * on the main thread — a read that does not require the target to run.
 */
class HangDetector(
    private val thresholdMillis: Long = DEFAULT_THRESHOLD_MS,
    private val pollMillis: Long = 200,
    private val mainLooper: Looper = Looper.getMainLooper(),
    private val onHang: (blockedMs: Long, stack: Array<StackTraceElement>) -> Unit,
) {

    companion object {
        /**
 * Android's own ANR threshold for input dispatch is 5s. Reporting below
 * the floor turns ordinary jank into a flood of "hangs" nobody can act
 * on, so a binding refuses rather than reporting noise.
 */
        const val MIN_THRESHOLD_MS = 250L
        const val DEFAULT_THRESHOLD_MS = 3_000L
    }

    private val ticks = AtomicLong(0)
    private val handler = Handler(mainLooper)
    @Volatile private var running = false
    private var watchdog: Thread? = null
    private var heartbeat: Runnable? = null

    init {
        require(thresholdMillis >= MIN_THRESHOLD_MS) {
            "hang threshold ${thresholdMillis}ms is below the ${MIN_THRESHOLD_MS}ms floor"
        }
    }

    fun start() {
        if (running) return
        running = true

        // The main thread's ONLY job: bump an atomic. No allocation, no I/O,
        // nothing that can itself contribute to a hang.
        val beat = object : Runnable {
            override fun run() {
                ticks.incrementAndGet()
                if (running) handler.postDelayed(this, pollMillis)
            }
        }
        heartbeat = beat
        handler.post(beat)

        watchdog = Thread({
            var lastTick = ticks.get()
            var stalledSince = 0L
            var reportedForThisStall = false

            while (running) {
                try {
                    Thread.sleep(pollMillis)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val now = ticks.get()
                if (now != lastTick) {
                    lastTick = now
                    stalledSince = 0L
                    reportedForThisStall = false
                    continue
                }
                // The counter did not move.
                if (stalledSince == 0L) stalledSince = System.currentTimeMillis()
                val blocked = System.currentTimeMillis() - stalledSince

                // One report per stall — a 30-second hang is ONE hang, not 150.
                if (blocked >= thresholdMillis && !reportedForThisStall) {
                    reportedForThisStall = true
                    val stack = try {
                        mainLooper.thread.stackTrace
                    } catch (t: Throwable) {
                        emptyArray()
                    }
                    try {
                        onHang(blocked, stack)
                    } catch (ignored: Throwable) {
                        // A throwing callback must not kill the watchdog.
                    }
                }
            }
        }, "ts-hang-watchdog").apply {
            isDaemon = true   // never keeps the process alive
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        heartbeat?.let { handler.removeCallbacks(it) }
        heartbeat = null
        watchdog?.interrupt()
        watchdog = null
    }
}

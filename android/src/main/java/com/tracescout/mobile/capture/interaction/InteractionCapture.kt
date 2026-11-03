package com.tracescout.mobile.capture.interaction

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.ViewConfiguration
import java.util.WeakHashMap

/**
 * Native Android touch + gesture capture — the producer, minus the envelope.
 *
 * OPT-IN BY CONSTRUCTION: nothing is observed until the host calls
 * [installForApplication] (or [install]). The host supplies FACTS — the current
 * screen, whether capture is permitted right now — and a [sink] for admitted
 * emissions; this class never creates a session and never writes the queue.
 *
 * ## What it can and cannot see, by construction
 *
 * It observes each ACTIVITY'S OWN WINDOW. The system soft keyboard is another
 * window in another process, so IME keys never arrive (the anti-keylogger
 * guarantee, structural). A `Dialog` or `PopupWindow` has its own window and is
 * NOT captured — a stated coverage limit, and the same mechanism that hides the
 * keyboard. An in-app keyboard DOES live in the content window, which is why
 * `textEntryActive` suppression is mandatory and runs first.
 *
 * ## Install timing
 *
 * `onActivityResumed`, never `onActivityCreated`: a host (AppCompat included)
 * may set its own window callback during `setContentView`, and wrapping before
 * that would be silently replaced.
 */
class InteractionCapture(
    private val screenIdProvider: () -> String?,
    private val neverRecordProvider: (String?) -> Boolean,
    private val captureEnabled: () -> Boolean,
    /**
 * Admitted emissions, each with the WebView carrier the touch landed in
 * (S5, an earlier change) — `null` for an ordinary native touch, which is the
 * overwhelming majority and the default.
 */
    private val sink: (InteractionProducer.Emission, com.tracescout.mobile.capture.WebViewAttachment.Carrier?) -> Unit,
    private val clockNanos: () -> Long = System::nanoTime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {

    /** Counters for a host-visible "why did nothing record". Never logs content. */
    @Volatile var emitted = 0; private set
    @Volatile var refused = 0; private set
    @Volatile var observationFailures = 0; private set

    // THE VALUE IS WEAK TOO: the wrapper's `wrapped` is usually the Activity
    // itself, so a strong value would keep its own weak key alive. The window
    // holds the wrapper strongly for as long as the Activity lives.
    private val installed = WeakHashMap<Activity, java.lang.ref.WeakReference<ObservingWindowCallback>>()
    private val lifecycles = WeakHashMap<Activity, GestureLifecycle>()

    /**
 * The carrier chosen at this gesture's DOWN, held for every phase of it.
 *
 * ONE GESTURE, ONE ANSWER. Re-asking per touch SAMPLE made a swipe that
 * began inside an attached WebView and ended over a native view carry the
 * segment on its `begin` row and not on its `end` row — one `gestureId`,
 * two different attributions, which no consumer can reconcile. It also
 * diverged from the rule this mirrors: RN decides on `down.target`
 * (`RnInteractionCapture`), not on the current sample. Weak keys, so a
 * held carrier never keeps an Activity alive.
 */
    private val gestureCarrier =
        WeakHashMap<Activity, com.tracescout.mobile.capture.WebViewAttachment.Carrier?>()

    @Synchronized
    fun install(activity: Activity) {
        if (installed[activity]?.get() != null) return
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is ObservingWindowCallback) return       // never stack
        // WEAK: the wrapper is the map VALUE, so a strong capture here would
        // keep its own weak key alive and leak every Activity a host installs
        // on without calling uninstall.
        val ref = java.lang.ref.WeakReference(activity)
        val w = ObservingWindowCallback(current,
            onTouch = { ev -> ref.get()?.let { onTouch(it, ev) } },
            onFailure = { observationFailures++; ref.get()?.let { lifecycles[it]?.reset() } })
        window.callback = w
        installed[activity] = java.lang.ref.WeakReference(w)
    }

    /**
 * Restores ONLY if the callback is still ours. If a later library wrapped
 * us, writing ours back would delete its wrapper — so we just forget ours.
 */
    @Synchronized
    fun uninstall(activity: Activity) {
        lifecycles.remove(activity)
        gestureCarrier.remove(activity)
        val ours = installed.remove(activity)?.get() ?: return
        // Observation stops even when a later wrapper keeps us in its chain;
        // delegation to the host stays unconditional.
        ours.active = false
        val window = activity.window ?: return
        if (window.callback === ours) window.callback = ours.wrapped
    }

    @Synchronized
    fun isInstalledOn(activity: Activity): Boolean =
        installed[activity]?.get().let { it != null && activity.window?.callback === it }

    fun installForApplication(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = install(activity)
            override fun onActivityPaused(activity: Activity) {
                // A gesture cannot survive the window losing the foreground.
                lifecycles[activity]?.reset()
            }
            override fun onActivityDestroyed(activity: Activity) = uninstall(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { }
            override fun onActivityStarted(activity: Activity) { }
            override fun onActivityStopped(activity: Activity) { }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { }
        })
    }

    /**
 * an earlier change — the WebView carrier rides a touch ONLY when the touch landed in
 * that WebView. The native twin of the RN rule (`RnInteractionCapture.
 * carrierFor`, an earlier change), and it exists for the reason an earlier change MEASURED there:
 * a segment is live for as long as the page is, so on a WebView-hosting
 * screen EVERY touch would otherwise carry it — including touches on
 * native controls whose rects are disjoint from the WebView. Under the
 * strict posture `mobile_interaction` carries no coordinate, so no
 * consumer could ever undo that; the hit test exists only at capture time.
 *
 * Composed HERE, where the hit-test fact is produced, so neither the
 * producer nor the envelope decides anything. Returns a PROVIDER still read
 * at emit time: for a touch outside web content the registry is never asked
 * and the row stays UNATTRIBUTED — never "native", never a WebView. Nothing
 * here mints, caches or substitutes a carrier.
 */
    internal fun carrierFor(
        insideWebContent: Boolean,
        carrier: () -> com.tracescout.mobile.capture.WebViewAttachment.Carrier?,
    ): () -> com.tracescout.mobile.capture.WebViewAttachment.Carrier? =
        if (insideWebContent) carrier else { { null } }

    private fun lifecycleFor(activity: Activity): GestureLifecycle =
        lifecycles.getOrPut(activity) {
            val dm = activity.resources.displayMetrics
            GestureLifecycle(
                touchSlopPx = ViewConfiguration.get(activity).scaledTouchSlop.toFloat(),
                // The iOS lane's 8-point move threshold, in this display's pixels.
                minMoveDistancePx = 8f * dm.density,
            )
        }

    internal fun onTouch(activity: Activity, ev: MotionEvent) {
        // The permission gates the CREATION of telemetry — never the seam.
        if (!captureEnabled()) { lifecycles[activity]?.reset(); gestureCarrier.remove(activity); return }
        val action = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> GestureLifecycle.Action.DOWN
            MotionEvent.ACTION_MOVE -> GestureLifecycle.Action.MOVE
            MotionEvent.ACTION_UP -> GestureLifecycle.Action.UP
            MotionEvent.ACTION_CANCEL -> GestureLifecycle.Action.CANCEL
            MotionEvent.ACTION_POINTER_DOWN -> GestureLifecycle.Action.EXTRA_POINTER
            else -> return
        }
        // getRawX/Y: pointer 0 in SCREEN pixels — the hierarchy's own basis.
        val sample = GestureLifecycle.TouchSample(action, ev.getPointerId(0), ev.rawX, ev.rawY,
            clockNanos(), wallMillis())
        val root = activity.window?.decorView ?: return
        val screen = screenIdProvider()
        val target = InteractionTargetResolver.resolve(root, sample.x, sample.y)
        val dm: DisplayMetrics = activity.resources.displayMetrics
        val facts = GestureLifecycle.SampleFacts(
            screenId = screen,
            screenNeverRecord = neverRecordProvider(screen) || InteractionTargetResolver.windowSecure(root),
            textEntryActive = InteractionTargetResolver.textEntryActive(root),
            fromContentWindow = true,
            targetClass = target.targetClass,
            positionTreatment = target.positionTreatment,
            viewportWidth = maxOf(dm.widthPixels, root.width),
            viewportHeight = maxOf(dm.heightPixels, root.height),
        )
        // Gated at the hit-test fact of the gesture's DOWN, then HELD. The
        // registry is never asked for a gesture that began outside web content.
        if (action == GestureLifecycle.Action.DOWN) {
            gestureCarrier[activity] = carrierFor(target.webView != null) {
                com.tracescout.mobile.capture.WebViewAttachment.carrierFor(target.webView)
            }()
        }
        val carrier = gestureCarrier[activity]
        val emissions = lifecycleFor(activity).observe(sample, facts)
        for (p in emissions) {
            when (val r = InteractionProducer.produce(p)) {
                is InteractionProducer.Result.Emit -> { sink(r.emission, carrier); emitted++ }
                is InteractionProducer.Result.Refused -> refused++
            }
        }
        // The gesture is over: its carrier must not survive into the next one.
        if (action == GestureLifecycle.Action.UP || action == GestureLifecycle.Action.CANCEL) {
            gestureCarrier.remove(activity)
        }
    }
}

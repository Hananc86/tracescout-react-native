package com.tracescout.mobile.rn

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.Window
import androidx.appcompat.view.WindowCallbackWrapper

/**
 * SEAM A — the audited Android touch observation point.
 *
 * `ANDROID INTERACTION CAPTURE SEAM A: WindowCallbackWrapper — AUDIT GREEN`
 * (an internal record).
 *
 * ## The one property everything else rests on
 *
 * observe -> delegate exactly once -> return the wrapped callback's result
 *
 * TraceScout never consumes, cancels, clones-for-delivery or redispatches an
 * event, never calls the wrapped callback twice, and never replaces a customer
 * or React Native touch listener. If observation throws, the application's
 * dispatch is unaffected — the delegation is deliberately OUTSIDE the try/catch,
 * so there is no path on which our failure becomes the customer's.
 *
 * ## Why `WindowCallbackWrapper` and not a hand-written wrapper
 *
 * `Window.Callback` has ~28 methods across API levels. A hand-written wrapper
 * that forgets `onWindowStartingActionMode` or `onPointerCaptureChanged`
 * silently changes behaviour on a path nobody tested. `androidx.appcompat.view
 *.WindowCallbackWrapper` forwards all of them, with the API-level shims, and
 * appcompat is already a dependency of `ReactActivity`.
 *
 * ## What this seam can and cannot see, by construction
 *
 * It observes the ACTIVITY'S OWN WINDOW and nothing else:
 *
 * - the soft keyboard is a different window owned by the InputMethodService in
 * another process, so IME key touches structurally cannot arrive here. That
 * is the anti-keylogging guarantee, and it holds at the seam before any
 * classifier runs;
 * - a `Dialog` has its own window and its own callback, and an RN `<Modal>` IS
 * a Dialog — so taps inside a Modal are NOT captured. Recorded as
 * `ANDROID INTERACTION v1 COVERAGE LIMITATION: RN MODAL / DIALOG WINDOWS NOT
 * CAPTURED`. This is not a bug to fix opportunistically: the same mechanism
 * is what hides the keyboard.
 *
 * The IME exclusion is a STRUCTURAL property, not a substitute for the
 * focused-editable rule. A custom/in-app keyboard lives inside the application
 * window and its keys DO arrive here, so the policy's `textEntryActive`
 * suppression remains mandatory.
 */
object RnInteractionCapture {

    /** What the wrapper reports upward. Deliberately not a logger. */
    interface Observer {
        /**
 * @return true when the observer wants further events for this gesture.
 * The return value is ADVISORY and never reaches the application: the
 * wrapper's own return is always the wrapped callback's.
 */
        fun onWindowTouch(activity: Activity, event: MotionEvent)

        /** Observation failed. Reported, never thrown onward. */
        fun onObservationFailure(t: Throwable)
    }

    /**
 * The wrapper. `internal` so nothing outside this module can construct one
 * and hand it a callback of its choosing.
 */
    internal class Wrapper(
        private val activity: Activity,
        private val wrapped: Window.Callback,
        private val observer: Observer,
    ) : WindowCallbackWrapper(wrapped) {

        /** The callback we wrapped, for identity-checked uninstall. */
        internal fun wrappedCallback(): Window.Callback = wrapped

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // 1. OBSERVE — bounded. Anything thrown here is contained.
            try {
                observer.onWindowTouch(activity, event)
            } catch (t: Throwable) {
                try { observer.onObservationFailure(t) } catch (_: Throwable) { }
            }
            // 2. DELEGATE — outside the catch, exactly once, the SAME instance.
            // `super` is WindowCallbackWrapper, which forwards to `wrapped`.
            // 3. RETURN the wrapped callback's result, unmodified.
            return super.dispatchTouchEvent(event)
        }
    }

    /**
 * Build the standard observer. A factory, because `RnInteractionObserver`
 * is `internal` — the host app supplies FACTS (which screen, is it
 * never-record, is capture enabled) and never a decision.
 */
    fun observerFor(
        screenIdProvider: () -> String?,
        neverRecordProvider: () -> Boolean,
        captureEnabled: (Activity) -> Boolean,
        /**
 * THE HOST SUPPLIES THE SESSION, exactly as it does for network
 * capture. The SDK must never CREATE one here: an interaction is not a
 * reason to start a session, and a producer that could would attribute
 * a tap to a session the user never began.
 */
        identity: () -> com.tracescout.rn.RnEnvelope.Identity?,
        /**
 * The host's CURRENT WebView carrier, or null — a FACT supplied by the
 * app exactly like [identity], never derived here. Defaulted, so an
 * existing caller keeps emitting unattributed interaction rows.
 */
        webViewRef: () -> com.tracescout.rn.RnEnvelope.WebViewRef? = { null },
    ): Observer = RnInteractionObserver(
        screenIdProvider = screenIdProvider,
        neverRecordProvider = { neverRecordProvider() },
        captureEnabled = captureEnabled,
        emit = { activity, fin, down, kind ->
            RnInteractionEmitter.emit(activity, fin, down, kind, identity,
                carrierFor(down.target.insideWebContent, webViewRef))
        },
    )

    /**
 * an earlier change — the host's carrier rides a tap ONLY when the tap landed on web
 * content.
 *
 * an earlier change measured why: the carrier is SEGMENT-live scoped, so on a
 * WebView-hosting screen every tap carried it — 5 of 8 on the device, 3 of
 * them on native controls whose rects are disjoint from the RNCWebView.
 * `mobile_interaction` has no coordinate under the strict posture, so no
 * consumer can undo that; the hit test exists only at capture time.
 *
 * Composed HERE, where the host's fact is injected, so the emitter still
 * decides nothing. Returns a PROVIDER, still read at emit time: for a tap
 * outside web content the host is never asked and the row stays
 * UNATTRIBUTED — never NATIVE, never WEBVIEW. For a
 * tap inside, the host's answer passes through unchanged; nothing here
 * mints, caches or substitutes a carrier.
 */
    internal fun carrierFor(
        insideWebContent: Boolean,
        webViewRef: () -> com.tracescout.rn.RnEnvelope.WebViewRef?,
    ): () -> com.tracescout.rn.RnEnvelope.WebViewRef? =
        if (insideWebContent) webViewRef else { { null } }

    private val installed = HashMap<Activity, Wrapper>()

    /**
 * Install on one Activity. Idempotent, and a no-op when the window callback
 * is null (a window with no callback is not an application content window).
 */
    @Synchronized
    fun install(activity: Activity, observer: Observer) {
        if (installed.containsKey(activity)) return
        val window = activity.window ?: return
        val current = window.callback ?: return
        // Already ours (e.g. a re-entrant lifecycle callback) — never stack.
        if (current is Wrapper) return
        val w = Wrapper(activity, current, observer)
        window.callback = w
        installed[activity] = w
    }

    /**
 * Uninstall.
 *
 * RESTORES ONLY WHEN THE CALLBACK IS STILL OURS. If another library
 * replaced it after we installed, its wrapper is now the owner and ours is
 * inside its chain; writing our saved callback back would DELETE that
 * library's wrapper. We leave the later owner alone and simply forget our
 * own — no fighting for ownership, and no reinstall loop.
 */
    @Synchronized
    fun uninstall(activity: Activity) {
        val ours = installed.remove(activity) ?: return
        val window = activity.window ?: return
        if (window.callback === ours) {
            window.callback = ours.wrappedCallback()
        }
        // else: a later owner exists. Deliberately untouched.
    }

    @Synchronized
    internal fun isInstalledOn(activity: Activity): Boolean =
        installed[activity].let { it != null && activity.window?.callback === it }

    /**
 * Install for the whole application, with no host-app code change beyond
 * the SDK's existing `Application.onCreate` call.
 *
 * INSTALLED IN `onActivityResumed`, NOT `onActivityCreated`. AppCompat
 * sets its own window callback during `onCreate`/`setContentView`; wrapping
 * before that would wrap the bare PhoneWindow callback and AppCompat would
 * then replace the whole chain — silently uninstalling us.
 *
 * The observation is installed regardless of the capture preference. The
 * preference gates the CREATION of telemetry, not the wrapper: a wrapper
 * that appears and disappears with a runtime setting is a second, untested
 * dispatch configuration, and the one thing this seam must never do is vary
 * how the application receives touches.
 */
    fun installForApplication(app: Application, observer: Observer) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = install(activity, observer)
            override fun onActivityPaused(activity: Activity) { }
            override fun onActivityDestroyed(activity: Activity) = uninstall(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { }
            override fun onActivityStarted(activity: Activity) { }
            override fun onActivityStopped(activity: Activity) { }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { }
        })
    }
}

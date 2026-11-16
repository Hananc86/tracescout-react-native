package com.tracescout.mobile.capture.interaction

import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * The native touch seam: observe -> delegate EXACTLY ONCE -> return the wrapped
 * callback's own result.
 *
 * It never consumes, cancels, copies-for-delivery or redispatches an event, and
 * never replaces a host listener. Observation is inside a catch; delegation is
 * OUTSIDE it, so there is no path on which a capture failure becomes the
 * application's failure.
 *
 * HAND-WRITTEN, AND EVERY `Window.Callback` METHOD IS FORWARDED. The native
 * SDK does not depend on appcompat (the RN seam uses `WindowCallbackWrapper`
 * because `ReactActivity` already pulls it in); adding a UI library to a
 * telemetry AAR for one class is the wrong trade. The cost of hand-writing is
 * that a forgotten method silently falls back to the interface DEFAULT instead
 * of the host's callback — so `ObservingWindowCallbackDelegationTest` reflects
 * over `Window.Callback` and fails if any method of it is not declared here.
 *
 * KEY EVENTS ARE FORWARDED AND NEVER OBSERVED. Only `dispatchTouchEvent`
 * has an observation line. The soft keyboard is another window in another
 * process and cannot reach this callback at all; hardware keys can, and are
 * deliberately not looked at.
 */
internal class ObservingWindowCallback(
    internal val wrapped: Window.Callback,
    private val onTouch: (MotionEvent) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : Window.Callback {

    /** Cleared by uninstall: a retained wrapper then only delegates. */
    @Volatile internal var active = true

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (active) {
            try {
                onTouch(event)
            } catch (t: Throwable) {
                try { onFailure(t) } catch (_: Throwable) { }
            }
        }
        return wrapped.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = wrapped.dispatchKeyEvent(event)
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = wrapped.dispatchKeyShortcutEvent(event)
    override fun dispatchTrackballEvent(event: MotionEvent): Boolean = wrapped.dispatchTrackballEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = wrapped.dispatchGenericMotionEvent(event)
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
        wrapped.dispatchPopulateAccessibilityEvent(event)
    override fun onCreatePanelView(featureId: Int): View? = wrapped.onCreatePanelView(featureId)
    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = wrapped.onCreatePanelMenu(featureId, menu)
    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean =
        wrapped.onPreparePanel(featureId, view, menu)
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = wrapped.onMenuOpened(featureId, menu)
    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = wrapped.onMenuItemSelected(featureId, item)
    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) = wrapped.onWindowAttributesChanged(attrs)
    override fun onContentChanged() = wrapped.onContentChanged()
    override fun onWindowFocusChanged(hasFocus: Boolean) = wrapped.onWindowFocusChanged(hasFocus)
    override fun onAttachedToWindow() = wrapped.onAttachedToWindow()
    override fun onDetachedFromWindow() = wrapped.onDetachedFromWindow()
    override fun onPanelClosed(featureId: Int, menu: Menu) = wrapped.onPanelClosed(featureId, menu)
    override fun onSearchRequested(): Boolean = wrapped.onSearchRequested()
    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = wrapped.onSearchRequested(searchEvent)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? =
        wrapped.onWindowStartingActionMode(callback)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        wrapped.onWindowStartingActionMode(callback, type)
    override fun onActionModeStarted(mode: ActionMode?) = wrapped.onActionModeStarted(mode)
    override fun onActionModeFinished(mode: ActionMode?) = wrapped.onActionModeFinished(mode)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        wrapped.onProvideKeyboardShortcuts(data, menu, deviceId)
    override fun onPointerCaptureChanged(hasCapture: Boolean) = wrapped.onPointerCaptureChanged(hasCapture)
}

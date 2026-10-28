package com.tracescout.rn.react

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * React Native autolinking entry point. The host application does not register
 * the native module by hand; `react-native.config.js` in this package names
 * this class, and the generated package list instantiates it.
 *
 * Both members are deprecated upstream on newer React Native but remain part
 * of the interface across the whole supported range, so both are implemented.
 * The parameter names match the supertype so a caller using named arguments
 * still resolves them.
 */
@Suppress("DEPRECATION")
class TraceScoutRnPackage : ReactPackage {

    @Deprecated("ReactPackage.createNativeModules is deprecated upstream.")
    override fun createNativeModules(
        reactContext: ReactApplicationContext,
    ): List<NativeModule> = listOf(TraceScoutRnModule(reactContext))

    /** The SDK contributes no view managers. */
    @Deprecated("ReactPackage.createViewManagers is deprecated upstream.")
    override fun createViewManagers(
        reactContext: ReactApplicationContext,
    ): List<ViewManager<*, *>> = emptyList()
}

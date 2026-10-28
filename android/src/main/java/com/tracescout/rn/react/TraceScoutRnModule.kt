package com.tracescout.rn.react

import android.content.Context
import android.os.Build
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.tracescout.mobile.rn.RnTransport
import com.tracescout.rn.RnEnvelope
import com.tracescout.rn.bridge.RnBridge
import java.util.UUID

/**
 * The only class in this library that React can call, and it owns no rule.
 *
 * Every method is a 1:1 delegation to [RnBridge], which is where the semantic
 * contract lives and where it is unit-tested. It is deliberately thin: a rule
 * implemented here would be a rule with no test, reachable only from
 * JavaScript, in the one module that cannot be exercised without a device.
 *
 * Note what has no `@ReactMethod`: producer family, SDK version, platform,
 * protocol version, session id, project id, environment, sequence, either
 * clock, the privacy posture, and the ingest credential. JavaScript cannot name
 * them because there is no method that accepts them. The server's
 * producer-capability boundary is only meaningful if a producer cannot
 * misreport which capture code produced its bytes.
 *
 * The credential is installed natively by the host application through
 * `RnSecureConfig.configure` in `Application.onCreate`. Handing the plaintext
 * ingest key to the JavaScript runtime would defeat the Android Keystore that
 * protects it, so there is no JavaScript-facing configure method.
 */
class TraceScoutRnModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = NAME

    /**
 * The durable path, wired to the shared pipeline.
 *
 * The bridge hands over a builder; [RnTransport] assigns the sequence and
 * stamps it into those bytes under one lock. Nothing here chooses a number.
 */
    private val sink = object : RnBridge.DurableSink {
        override fun enqueue(build: (Long) -> ByteArray): Long =
            RnTransport.enqueue(reactContext.applicationContext, build)

        // The bridge reports a moment; the transport decides what it means.
        // Forwarding it verbatim keeps this adapter free of any rule.
        override fun flushHint(reason: String) {
            RnTransport.requestDrain(reactContext.applicationContext, reason)
        }

        override fun stateName(): String = RnTransport.state().name

        // The bridge supplies the session it is running; the transport owns the
        // durable claim, its identity and its idempotence.
        override fun finalizeSession(sessionId: String): String =
            RnTransport.endSessionAuthoritatively(reactContext.applicationContext, sessionId)
    }

    private val pendingFrameLock = Any()
    private var pendingFrame:
        Pair<com.tracescout.rn.HierarchyResult,
             com.tracescout.mobile.rn.RnFrameRasterizer.Frame?>? = null

    private val routeHosts: com.tracescout.rn.RouteHostRegistry by lazy {
        com.tracescout.rn.RouteHostRegistry { android.os.SystemClock.elapsedRealtime() }
    }

    // The deadline owner is a Handler on the main looper, which is also where
    // capture runs, so the callback cannot race the view hierarchy. This is
    // what bounds the wait by the attach budget rather than by whenever the
    // user happens to navigate next.
    private val mainHandler: android.os.Handler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }
    private val attachDeadline: com.tracescout.rn.AttachDeadline by lazy {
        com.tracescout.rn.AttachDeadline(
            object : com.tracescout.rn.AttachDeadline.Scheduler {
                override fun schedule(delayMs: Long, task: () -> Unit): Any {
                    val r = Runnable { task() }
                    mainHandler.postDelayed(r, delayMs)
                    return r
                }
                override fun cancel(handle: Any) {
                    mainHandler.removeCallbacks(handle as Runnable)
                }
            },
        )
    }

    private val bridge: RnBridge by lazy { buildBridge() }

    private fun buildBridge() = RnBridge(
        identity = { session },
        beginSession = { beginIdentity(reactContext.applicationContext) },
        configured = {
            com.tracescout.mobile.rn.RnSecureConfig
                .status(reactContext.applicationContext).configured
        },
        sink = sink,
        routeHosts = routeHosts,
        attachDeadline = attachDeadline,
        // The durable, sanitized trace goes to the SDK's recovery-trace file,
        // the channel that already survives process death. A logcat line is
        // gone the moment the buffer wraps, and a transition that cannot be
        // read afterwards is a transition that was not recorded. The bridge
        // composes only attempt id, route, generation, timings and typed
        // reasons; no credential and no captured content is in scope there.
        trace = { line -> android.util.Log.i("TraceScoutRouteHost", line) },
        clock = object : RnBridge.NativeClock {
            override fun wallMillis() = System.currentTimeMillis()
            override fun monotonicNanos() = System.nanoTime()
        },
        ids = object : RnBridge.EventIdSource {
            override fun newEventId() =
                "evt_" + UUID.randomUUID().toString().replace("-", "")
        },
        // Hierarchy capture is native wiring only: the presence of this closure
        // is the enablement, and no `@ReactMethod` can reach or replace it, so
        // the JavaScript surface stays at exactly eleven methods. The walker
        // refuses in background, FLAG_SECURE and no-activity states, and the
        // bridge then records an honest metadata-only snapshot, carrying a
        // reason only when one of the two protocol conditions was observed.
        hierarchy = { routeId ->
            val app = reactContext.applicationContext
            // Ownership is asked of the registry, not guessed from the window.
            // An integrated app must name a capture root for the route or
            // nothing is walked; an app that names none keeps the window walk.
            val bound = bridge.boundRoot(routeId)
            val binding = when {
                !bridge.isIntegrated() ->
                    com.tracescout.mobile.rn.RnHierarchySnapshotter
                        .CaptureRootBinding.Unintegrated
                bound != null ->
                    com.tracescout.mobile.rn.RnHierarchySnapshotter
                        .CaptureRootBinding.Bound(bound)
                else ->
                    com.tracescout.mobile.rn.RnHierarchySnapshotter
                        .CaptureRootBinding.Unowned
            }
            val outcome = com.tracescout.mobile.rn.RnHierarchySnapshotter.observe(
                reactContext.currentActivity,
                routeId,
                wantFrame = com.tracescout.mobile.rn.RnSecureConfig.framesEnabled(app),
                binding = binding,
                webView = null,
                // React resolves a node handle to its view through the
                // UIManager; a handle for a replaced or unmounted host resolves
                // to null, which is exactly the staleness signal.
                resolver = com.tracescout.mobile.rn.RnHierarchySnapshotter.RootResolver { h ->
                    // `getUIManagerType` is deprecated on newer React Native,
                    // where Fabric is the only renderer. It is kept because it
                    // is the one call that works across the whole supported
                    // range: an application still on the older renderer needs
                    // the lookup, and naming Fabric directly would break it.
                    @Suppress("DEPRECATION")
                    try {
                        com.facebook.react.uimanager.UIManagerHelper
                            .getUIManager(
                                reactContext,
                                com.facebook.react.uimanager.common.ViewUtil.getUIManagerType(h),
                            )
                            ?.resolveView(h)
                    } catch (t: Throwable) { null }
                },
                bindingStillCurrent = {
                    bound != null && bridge.bindingIsCurrent(routeId, bound)
                },
            )
            outcome.capture?.also { cap ->
                synchronized(pendingFrameLock) { pendingFrame = cap.result to cap.frame }
            }
            RnBridge.HierarchyOutcome(
                capture = outcome.capture?.let {
                    RnBridge.HierarchyCapture(it.result, it.durationMs)
                },
                captureUnavailableReason = outcome.captureUnavailableReason,
            )
        },
        // Visual frames are opt-in through the persisted native switch, never
        // from JavaScript. The rasterizer refuses a FLAG_SECURE window and
        // paints the hierarchy's masked and blocked rectangles before encoding;
        // the bytes go to the durable frame store and drain through the
        // transport. A refusal at any step yields null and the snapshot stays
        // wireframe-only.
        frame = { routeId, hierarchy -> run {
            val app = reactContext.applicationContext
            // Hand over only the frame rasterized with this exact result, from
            // the same pass; anything else is null.
            val f = synchronized(pendingFrameLock) {
                val p = pendingFrame
                pendingFrame = null
                if (p != null && p.first === hierarchy) p.second else null
            } ?: return@run null
            val sid = session?.sessionId ?: return@run null
            val vfId = "vf_" + UUID.randomUUID().toString().replace("-", "")
            val header = com.tracescout.rn.FrameHeader(
                vfId, sid, routeId, f.contentSha256,
                f.width, f.height, f.bytes.size, f.format, 0,
            )
            if (!RnTransport.enqueueFrame(app, header, f.bytes)) null
            else RnBridge.FrameCapture(
                vfId, f.contentSha256, f.width, f.height, f.bytes.size,
                f.format, com.tracescout.rn.FrameBudgets.QUALITY, f.downscale,
            )
        } },
    )

    private var session: RnEnvelope.Identity? = null

    /**
 * The one creator of a session on this platform, called exclusively by the
 * bridge's `startSession`.
 *
 * Session identity is native. The project, the environment and the
 * credential come from the host application's native configuration; the
 * application identifier, version and build number come from the running
 * package, because the server originates a native session only from an
 * opener that names the app.
 */
    private fun beginIdentity(ctx: Context): RnEnvelope.Identity? {
        val status = com.tracescout.mobile.rn.RnSecureConfig.status(ctx)
        if (!status.configured) return null
        val hex = UUID.randomUUID().toString().replace("-", "").take(12)
        return RnEnvelope.Identity(
            projectId = status.projectId ?: return null,
            sessionId = "ts_${System.currentTimeMillis()}_$hex",
            anonymousId = anonymousId(ctx),
            environment = status.environment ?: "production",
            appIdentifier = ctx.packageName,
            initialScreen = "home",
            appVersion = packageVersionName(ctx),
            buildNumber = packageVersionCode(ctx),
            device = deviceFacts(ctx),
        ).also { session = it }
    }

    // ---------------------------------------------------------- JS surface
    //
    // Exactly eleven methods, identical to the exported iOS surface. A method
    // that is not here does not exist for JavaScript.

    @ReactMethod fun startSession(initialRouteId: String?, promise: Promise) =
        promise.resolve(bridge.startSession(initialRouteId).outcome.name)

    @ReactMethod fun navigate(
        fromRouteId: String?,
        toRouteId: String,
        toRouteName: String?,
        transition: String,
        promise: Promise,
    ) = promise.resolve(
        bridge.navigate(fromRouteId, toRouteId, toRouteName, transition).outcome.name,
    )

    @ReactMethod fun screenSnapshot(routeId: String, promise: Promise) =
        promise.resolve(bridge.screenSnapshot(routeId).outcome.name)

    @ReactMethod fun lifecycle(transition: String, promise: Promise) =
        promise.resolve(bridge.lifecycle(transition).outcome.name)

    @ReactMethod fun declareNeverRecord(routeId: String, never: Boolean, promise: Promise) =
        promise.resolve(bridge.declareNeverRecord(routeId, never).name)

    @ReactMethod fun hostAttached(
        routeId: String,
        hostId: String,
        rootHandle: Double,
        promise: Promise,
    ) = promise.resolve(
        // The root handle is a React node tag, not a view. A host may attach
        // with 0, participate on the outcome axis, and authorize no capture.
        bridge.hostAttached(
            routeId, hostId, if (rootHandle > 0) rootHandle.toInt() else null,
        ).name,
    )

    @ReactMethod fun hostReadinessPending(routeId: String, hostId: String, promise: Promise) =
        promise.resolve(bridge.hostReadinessPending(routeId, hostId).name)

    @ReactMethod fun hostReady(routeId: String, hostId: String, promise: Promise) =
        promise.resolve(bridge.hostReady(routeId, hostId).name)

    @ReactMethod fun hostFailed(routeId: String, hostId: String, promise: Promise) =
        promise.resolve(bridge.hostFailed(routeId, hostId).name)

    @ReactMethod fun hostDetached(routeId: String, hostId: String, promise: Promise) =
        promise.resolve(bridge.hostDetached(routeId, hostId).name)

    @ReactMethod fun state(promise: Promise) = promise.resolve(bridge.state())

    companion object {
        const val NAME = "TraceScoutRn"

        /**
 * A per-installation identifier, stored beside the SDK's own
 * configuration. It identifies the installation, never the person: it
 * is generated on the device, is not derived from any hardware or
 * advertising identifier, and is discarded when the host application's
 * data is cleared.
 */
        private fun anonymousId(ctx: Context): String {
            val prefs = ctx.applicationContext
                .getSharedPreferences("tracescout_rn_install", Context.MODE_PRIVATE)
            prefs.getString("anonymous_id", null)?.let { return it }
            val id = "rn_" + UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString("anonymous_id", id).apply()
            return id
        }

        private fun packageVersionName(ctx: Context): String =
            try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
            } catch (t: Throwable) { "0" }

        private fun packageVersionCode(ctx: Context): String =
            try {
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION") info.versionCode.toString()
                }
            } catch (t: Throwable) { "0" }

        private fun deviceFacts(ctx: Context): RnEnvelope.DeviceFacts {
            val dm = ctx.resources.displayMetrics
            return RnEnvelope.DeviceFacts(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                osName = "Android",
                osVersion = Build.VERSION.RELEASE,
                screenWidth = dm.widthPixels,
                screenHeight = dm.heightPixels,
                pixelRatio = dm.density.toDouble(),
                densityDpi = dm.densityDpi,
            )
        }
    }
}

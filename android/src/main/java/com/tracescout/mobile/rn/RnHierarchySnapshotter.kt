package com.tracescout.mobile.rn

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.tracescout.rn.BlockedReason
import com.tracescout.rn.CanonicalHierarchy
import com.tracescout.rn.HierarchyResult
import com.tracescout.rn.NodeBounds
import com.tracescout.rn.RawNode
import com.tracescout.rn.RnEnvelope
import com.tracescout.rn.Treatment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Walks the LIVE Android view tree into the RawNode privacy model — the
 * Android twin of the iOS `HierarchySnapshotter`.
 *
 * This file is the only place the RN transport touches views, and it runs ONLY
 * on the main thread and ONLY while an activity window is available — a
 * background walk is refused, not deferred (contract: no traversal or capture
 * in background).
 *
 * What it reads per view: class name, on-screen frame, visibility/alpha, and
 * the PLATFORM privacy facts (password inputs, web content, GPU/secure
 * surfaces). What it NEVER reads: text content, accessibility values, or
 * anything typed into an input. Under the strict default every text-bearing
 * node is MASKED with a length only, and the length comes from a character
 * COUNT — the string is never copied, concatenated, logged or stored.
 */
object RnHierarchySnapshotter {

    /**
 * The complete outcome of ONE capture attempt: the capture if it happened,
 * and the CLOSED capture-unavailable reason ONLY when one of the two
 * protocol conditions was actually OBSERVED.
 *
 * THIS TYPE EXISTS BECAUSE `null` MEANT SEVEN DIFFERENT THINGS.
 * No activity, a finishing activity, a main-thread timeout, no window, a
 * FLAG_SECURE window, a zero-sized root and an invisible root all folded
 * into one `null`, and only two of them are protocol conditions. Reporting
 * a reason for the other five would be inventing a cause; reporting none
 * for all seven loses the two we can actually establish.
 */
    data class Outcome(
        val capture: Capture? = null,
        val captureUnavailableReason: String? = null,
        /**
 * THE REFUSAL BELONGS TO THIS PASS, NOT TO WHOEVER ASKED LAST.
 * `lastOwnershipRefusal` is shared mutable state that the caller reads
 * AFTER the pass returns, so a block still running from a pass that
 * already TIMED OUT can overwrite the answer a newer pass is about to
 * report. Nothing is lost when that happens — a late block emits
 * nothing — but the diagnostic would name another pass's reason, which
 * is the exact defect that made a timeout print `refusal=NONE`.
 * Carried in the outcome, it cannot be overwritten by anyone.
 */
        val ownershipRefusal: OwnershipRefusal = OwnershipRefusal.NONE,
    )

    data class Capture(
        val result: HierarchyResult,
        val durationMs: Long,
        /**
 * Phase 2: the frame rasterized IN THE SAME main-thread pass as the
 * walk, from the SAME result — so the redaction geometry describes
 * exactly the pixels that were drawn. Two separate main-thread hops
 * let the view tree change in between (a list appended rows), and the
 * new rows had no rect: readable text in a frame whose audit passed.
 */
        val frame: RnFrameRasterizer.Frame? = null,
        val frameRefusal: String? = null,
    )

    /**
 * MEASURED, NOT CHOSEN. The walk must run on the main thread, and at 250 ms
 * it did not get it on a cold React Native start: measured 251/253/256 ms
 * against a 250 ms budget on three consecutive cold launches, i.e. the
 * SESSION'S FIRST CAPTURE — the opening screen — failed systematically,
 * every time, and reported it as an empty screen. With the budget lifted
 * purely to measure, the real acquisition cost is 543/549 ms cold and 14 ms
 * warm, and the same capture yields 39 nodes.
 *
 * 1500 ms is ~2.7x the measured cold-start maximum and is deliberately
 * BELOW the route host's own 2000 ms attach budget: a pass that cannot be
 * acquired must still refuse before the deadline that would emit
 * `route_surface_not_attached`, or the walker would be answering a question
 * nobody is waiting for any more.
 */
    private const val MAIN_THREAD_WAIT_MS = 1500L

    /**
 * Capture the activity's decor tree for `routeId`. Returns null when
 * capture is not permitted (no activity, finishing, FLAG_SECURE window,
 * main thread unavailable) — null means "no hierarchy event", never an
 * empty fabricated one.
 */
    fun capture(
        activity: Activity?,
        routeId: String,
        wantFrame: Boolean = false,
        webView: RnEnvelope.WebViewRef? = null,
    ): Capture? = observe(activity, routeId, wantFrame, webView = webView).capture

    /**
 * The same attempt, reporting WHY it failed when — and only when — one of
 * the two protocol conditions was observed.
 */
    /**
 * WHICH SUBTREE IS THIS ROUTE'S CONTENT?
 *
 * THE QUESTION THE WALKER COULD NOT ASK BEFORE, AND THE REASON IT SHIPPED
 * A DEFECT. It was handed a routeId and walked the activity's DECOR VIEW —
 * the whole application window — and `CanonicalHierarchy.build(routeId, raw)`
 * stamped the requested route's label onto whatever that happened to be.
 * Measured on device: a route that was NEVER RENDERED produced 70 nodes of
 * another route's content, emitted under its label.
 *
 * READINESS IS NOT OWNERSHIP. A host declaring itself ready establishes
 * that it is ready. It says nothing about which nodes are its. Only a
 * capture root, verified at capture time, can establish that.
 */
    sealed class CaptureRootBinding {
        /**
 * No route-host registry exists — the app has not integrated.
 * LEGACY BEHAVIOUR, DELIBERATELY UNCHANGED AND DELIBERATELY SEPARATE.
 * The window walk is what unintegrated apps have always got. It carries
 * the same ownership weakness it always did, which is precisely why the
 * integration contract exists; silently changing it would break every
 * app that never opted in.
 */
        object Unintegrated : CaptureRootBinding()

        /** Integrated, and the registry named a capture root for this route. */
        data class Bound(val root: com.tracescout.rn.RouteHostRegistry.BoundRoot) : CaptureRootBinding()

        /**
 * Integrated, and ownership CANNOT be established for this route — no
 * host, no declared capture root, or two hosts claiming one route.
 * Nothing may be walked.
 */
        object Unowned : CaptureRootBinding()
    }

    /** Platform resolution of an opaque root handle. Returns null when the
 * handle no longer names a live view — a replaced or detached host. */
    fun interface RootResolver { fun resolve(handle: Int): View? }

    /** Why a bound capture root was refused at capture time. Observability
 * only — none of these is a protocol reason and none reaches the wire. */
    enum class OwnershipRefusal {
        NONE, UNRESOLVED, DETACHED, FOREIGN_WINDOW, BINDING_CHANGED, AMBIGUOUS_OR_ABSENT,
        /**
 * THE WALK NEVER RAN. The pass must happen on the main thread, and
 * this one did not get it inside the budget — so nothing was observed
 * about the root, the tree, or the route. It is NOT an empty capture,
 * and reporting it as one is what made a systematically-failing first
 * capture look like a screen with no content.
 */
        MAIN_THREAD_UNAVAILABLE,
    }

    @Volatile var lastOwnershipRefusal: OwnershipRefusal = OwnershipRefusal.NONE
        private set

    fun observe(
        activity: Activity?,
        routeId: String,
        wantFrame: Boolean = false,
        binding: CaptureRootBinding = CaptureRootBinding.Unintegrated,
        resolver: RootResolver? = null,
        bindingStillCurrent: () -> Boolean = { true },
        /**
 * The envelope's OWN WebView carrier, or null — threaded straight to
 * [CanonicalHierarchy.build] so the node-level segment link and the
 * envelope-level one come from ONE read of ONE admitted segment.
 *
 * LAST AND DEFAULTED, and there is no production caller yet: the
 * correlation bind still has none on either platform, so
 * every shipping path passes null and emits a tree with no node link —
 * byte-identical to before. Wiring the live correlation in is the next
 * gate, not a silent side effect of this one.
 */
        webView: RnEnvelope.WebViewRef? = null,
    ): Outcome {
        // NO ACTIVITY IS NOT AN OBSERVATION OF A DETACHED SURFACE.
        // `currentActivity` is null while React is between hosts, during
        // teardown, and in a headless task — the route's surface may be
        // perfectly attached to a window we simply cannot reach. We have no
        // fact, so we say nothing. Same for a finishing/destroyed activity:
        // its window is often still attached, so "it is going away" is not
        // "it was not attached".
        // NEVER REPORT A PREVIOUS PASS'S ANSWER. `lastOwnershipRefusal` is
        // shared mutable state read by the caller AFTER this returns, so every
        // path that does not set it used to print whatever the last pass left
        // behind. A timeout printed `refusal=NONE` — a confident, wrong
        // statement about a pass that never looked at anything.
        lastOwnershipRefusal = OwnershipRefusal.NONE
        val act = activity ?: return Outcome()
        if (act.isFinishing || act.isDestroyed) return Outcome()

        if (Looper.myLooper() == Looper.getMainLooper())
            return observeOnMain(act, routeId, wantFrame, binding, resolver, bindingStillCurrent, webView)
        var out: Outcome? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try { out = observeOnMain(act, routeId, wantFrame, binding, resolver, bindingStillCurrent, webView) }
            finally { latch.countDown() }
        }
        // A TIMEOUT IS NOT A REASON, AND THE LEAD'S RULE NAMES IT.
        // A wedged main thread tells us nothing about whether the surface was
        // attached or had dimensions — we never got to look. Bounded wait, then
        // an empty outcome: a refused capture, never a partial one and never an
        // invented cause.
        val t0 = android.os.SystemClock.elapsedRealtime()
        val got = latch.await(MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)
        val waited = android.os.SystemClock.elapsedRealtime() - t0
        // Always recorded, and it carries no content, no identity and no
        // credential: how long the pass waited for the main thread, and whether
        // it ever got it. Without this the budget is a number nobody measured.
        android.util.Log.i("TSRouteHost",
            "rh_acquire route=$routeId waitedMs=$waited budgetMs=$MAIN_THREAD_WAIT_MS acquired=$got")
        if (got) return out ?: Outcome()
        lastOwnershipRefusal = OwnershipRefusal.MAIN_THREAD_UNAVAILABLE
        return Outcome(ownershipRefusal = OwnershipRefusal.MAIN_THREAD_UNAVAILABLE)
    }

    /**
 * OBSERVATION AND TRAVERSAL ARE ONE SYNCHRONOUS MAIN-THREAD PASS, AND
 * THAT IS THE EXACT SCOPE OF THE CLAIM. Everything below runs on the main
 * thread without yielding, so no other main-thread work interleaves between
 * the facts we record and the tree we read. It does NOT mean the surface
 * "cannot change" in general — a background thread can still mutate state
 * the view tree derives from, and the platform may lay out again the moment
 * this block returns. What it does mean is that nothing reorders WITHIN the
 * pass, and that whatever the walk produced IS the capture. No reason is
 * attached after the fact.
 */
    private fun observeOnMain(
        act: Activity,
        routeId: String,
        wantFrame: Boolean = false,
        binding: CaptureRootBinding = CaptureRootBinding.Unintegrated,
        resolver: RootResolver? = null,
        bindingStillCurrent: () -> Boolean = { true },
        webView: RnEnvelope.WebViewRef? = null,
    ): Outcome {
        // NO WINDOW / NO DECOR VIEW IS NOT A ROUTE VERDICT.
        // It establishes that there is no CAPTURE ROOT — which is a fact about
        // this activity, not about the requested route's host. The protocol's
        // `route_surface_not_attached` means "no qualifying active route host
        // for the requested route before the attach budget expired", and a
        // decor view is not authoritatively associated with a route at all: it
        // hosts whatever happens to be current. Unknown ownership, no reason.
        val window = act.window ?: return Outcome()

        // A FLAG_SECURE WINDOW IS A PRIVACY DECLARATION, NOT A FAILURE, and
        // it belongs to a different axis entirely. The app said this surface
        // must not be captured. Refusing the whole walk is correct and
        // unchanged — describing it as an attachment or readiness fact would be
        // false, and inventing a third reason would break the closed vocabulary.
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return Outcome()

        val decor = window.decorView ?: return Outcome()

        // OWNERSHIP IS DECIDED HERE, BEFORE ANYTHING IS READ.
        val root: View = when (binding) {
            is CaptureRootBinding.Unintegrated -> {
                // Legacy. The window IS the root, exactly as before.
                lastOwnershipRefusal = OwnershipRefusal.NONE
                decor
            }
            is CaptureRootBinding.Unowned -> {
                // REFUSE TO EMIT, NOT REFUSE TO REPORT. No hierarchy is
                // produced, so no foreign content can be labelled with this
                // route. The screen still gets its metadata-only record and the
                // registry still decides the outcome axis on its own facts — we
                // do not invent a reason from an ownership failure, because
                // "we could not establish ownership" is not one of the two
                // protocol conditions.
                lastOwnershipRefusal = OwnershipRefusal.AMBIGUOUS_OR_ABSENT
                return Outcome(ownershipRefusal = OwnershipRefusal.AMBIGUOUS_OR_ABSENT)
            }
            is CaptureRootBinding.Bound -> {
                val v = resolver?.resolve(binding.root.rootHandle)
                if (v == null) {
                    // The handle no longer names a live view: the host was
                    // replaced or detached between registration and capture.
                    lastOwnershipRefusal = OwnershipRefusal.UNRESOLVED
                    return Outcome(ownershipRefusal = OwnershipRefusal.UNRESOLVED)
                }
                if (!v.isAttachedToWindow) {
                    lastOwnershipRefusal = OwnershipRefusal.DETACHED
                    return Outcome(ownershipRefusal = OwnershipRefusal.DETACHED)
                }
                // SAME WINDOW, ASKED OF THE VIEWS THEMSELVES. A view from a
                // previous activity can still satisfy isAttachedToWindow while
                // belonging to a window this capture has nothing to do with.
                if (v.windowToken == null || v.windowToken !== decor.windowToken) {
                    lastOwnershipRefusal = OwnershipRefusal.FOREIGN_WINDOW
                    return Outcome(ownershipRefusal = OwnershipRefusal.FOREIGN_WINDOW)
                }
                lastOwnershipRefusal = OwnershipRefusal.NONE
                v
            }
        }

        // THIS PLATFORM CANNOT ESTABLISH EITHER PROTOCOL CONDITION, SO IT
        // EMITS NO REASON — deliberately.
        //
        // React Native has no route-host registry here, no attach budget and no
        // host readiness declaration: the walker is handed a routeId and looks
        // at the activity's decor view. `root.isAttachedToWindow` and
        // `root.width`/`height` describe THAT VIEW AT THIS INSTANT and say
        // nothing about whether a qualifying host for the REQUESTED ROUTE
        // existed across a budget nobody waited out. Asked with UNKNOWN, the
        // shared rule refuses, and refusing to NAME a cause is not refusing to
        // capture — the ordinary walk below is unchanged.
        //
        // When a route-host registry with a real budget and a real readiness
        // signal exists, THIS is the line that changes; the shared fixture
        // already describes exactly what it must supply.
        val decision = com.tracescout.rn.CaptureOutcome.decide(
            neverRecord = false,   // a never-record route never reaches the provider
            host = com.tracescout.rn.CaptureOutcome.RouteHost.UNKNOWN,
        )
        if (decision.decision == com.tracescout.rn.CaptureOutcome.Decision.UNAVAILABLE) {
            return Outcome(captureUnavailableReason = decision.captureUnavailableReason)
        }

        val started = System.nanoTime()
        // AN INVISIBLE ROOT IS NOT ONE OF THE TWO CONDITIONS — it is neither
        // a statement about a route host nor a readiness declaration. No reason.
        val raw = rawNode(root, 0) ?: return Outcome()
        // THE BINDING MUST STILL BE CURRENT AFTER THE WALK, NOT ONLY BEFORE
        // IT. A host can be replaced or detached while the tree is being read;
        // content gathered under a binding that no longer exists is not this
        // route's content, and emitting it would be the same misattribution by
        // a narrower window.
        if (binding is CaptureRootBinding.Bound && !bindingStillCurrent()) {
            lastOwnershipRefusal = OwnershipRefusal.BINDING_CHANGED
            return Outcome(ownershipRefusal = OwnershipRefusal.BINDING_CHANGED)
        }
        val result = CanonicalHierarchy.build(routeId, raw, webView = webView)
        val ms = (System.nanoTime() - started) / 1_000_000
        if (!wantFrame) return Outcome(Capture(result, ms))
        // THE RASTERIZER IS HANDED THE ROOT THE WALK STARTED FROM, AND THE
        // WINDOW IT LIVES IN. It used to re-resolve `act.window.decorView`
        // itself and photograph the whole window, so under a bound capture root
        // the hierarchy was correctly scoped to the host's subtree while the
        // frame was a picture of the entire application window — the same
        // misattribution as before, in pixels instead of nodes, and a picture
        // is the more legible leak of the two. The frame was therefore REFUSED
        // `capture_root_not_rasterizable` until the SOURCE of the pixels could
        // be scoped to a view: that is now the case, so the refusal is gone and
        // `root` — the view OWNERSHIP selected above — is the subject.
        //
        // THE CANVAS IS STILL THE WINDOW (`decor`), AND THAT IS A PROTOCOL
        // FACT, NOT A PREFERENCE. `rawNode` records every node's bounds with
        // `getLocationInWindow`, and the Viewer draws them raw against a surface
        // it sizes from the frame, so a root-SIZED image would move every
        // wireframe rectangle `root.origin` away from the pixels it describes.
        // `FrameGeometry` carries the full argument; only the OWNED ROOT'S
        // PIXELS are ever drawn into that canvas.
        //
        // THE LEGACY PATH IS UNCHANGED, AND NOW GOES THROUGH THE SAME DOOR.
        // Under `Unintegrated` the window IS the declared root, so `root` IS
        // `decor`: the placement is the identity, the unowned region is empty,
        // and the output is the certified whole-window picture — arrived at
        // THROUGH the ownership checks rather than around them.
        //
        // SAME synchronous main-thread pass, SAME result. Within this pass no
        // other main-thread work interleaves, so the tree cannot be re-laid-out
        // between the walk and the draw. That is the ONLY claim being made:
        // it is scoped to this synchronous block, not a general guarantee that
        // the surface cannot change.
        return when (val r = RnFrameRasterizer.capture(act, root, decor, result)) {
            is RnFrameRasterizer.Result.Ok -> {
                traceFrameOutcome(routeId, null, r.frame.width, r.frame.height)
                Outcome(Capture(result, ms, frame = r.frame))
            }
            is RnFrameRasterizer.Result.Refused -> {
                traceFrameOutcome(routeId, r.code, 0, 0)
                Outcome(Capture(result, ms, frameRefusal = r.code))
            }
        }
    }

    /**
 * THE TYPED FRAME OUTCOME, AT THE DECISION BOUNDARY. `frameRefusal` is
 * carried on [Capture], is set at this one site, and was read by NOBODY in
 * production — so a build with frames enabled that produced no frame was
 * indistinguishable, from outside, from a build with frames disabled, and a
 * 19,279-byte device trace contained ZERO occurrences of the substring
 * "frame". The product knew exactly why it had produced nothing and said
 * nothing, which is what made the refusal cost two campaigns to attribute.
 *
 * BOUNDED BY CONSTRUCTION, NOT BY DISCIPLINE. The code is validated
 * against `RnFrameRasterizer.REFUSAL_CODES` and anything else is reported
 * as `other` — so a future refusal string, however it is built, cannot
 * reach a log line as free text. There is no user content here, no URL, no
 * text, no credential and no node identity: a closed code identifier, the
 * route label (already logged by `rh_acquire` and `rh_ownership`), and two
 * integer pixel dimensions.
 *
 * It changes no wire field and no protocol value.
 */
    private fun traceFrameOutcome(routeId: String, refusal: String?, width: Int, height: Int) {
        val code = when {
            refusal == null -> "NONE"
            RnFrameRasterizer.REFUSAL_CODES.contains(refusal) -> refusal
            else -> "other"
        }
        android.util.Log.i(
            "TSRouteHost",
            "rh_frame route=$routeId refusal=$code captured=${if (refusal == null) 1 else 0} " +
                "w=$width h=$height",
        )
    }

    /**
 * One view → one RawNode. Invisible or fully transparent subtrees are
 * omitted entirely — they are not on screen, and serialising them would
 * leak layout the user cannot see.
 */
    internal fun rawNode(view: View, ordinal: Int): RawNode? {
        if (view.visibility != View.VISIBLE || view.alpha < 0.01f) return null

        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val bounds = NodeBounds(loc[0], loc[1], view.width, view.height)
        val type = componentType(view)

        // PLATFORM privacy facts — these can only tighten, never loosen.
        // ONE AUTHORITY. Interaction hit-testing calls the SAME function, so
        // the same view in the same state cannot receive one Treatment when it
        // is drawn and a different one when it is touched. Cloning this `when`
        // into an interaction-only classifier is precisely how the two would
        // drift, and the weaker of two answers is the one an attacker gets.
        val facts = classifyView(view)
        val native: Treatment? = facts.nativeTreatment
        val reason: BlockedReason? = facts.blockedReason

        // Text length comes from a character COUNT only.
        val textLength: Int? = when {
            native == Treatment.BLOCKED -> null
            view is TextView -> view.text?.length
            else -> null
        }

        // Blocked: return the placeholder node WITHOUT visiting children —
        // never descend, never inspect.
        if (native == Treatment.BLOCKED) {
            return RawNode(
                componentType = type, siblingOrdinal = ordinal,
                jsTreatment = Treatment.MASKED, nativeTreatment = Treatment.BLOCKED,
                nativeBlockedReason = reason, bounds = bounds,
                webContent = facts.webContent,
            )
        }

        val kids = ArrayList<RawNode>()
        if (view is ViewGroup) {
            var i = 0
            for (c in 0 until view.childCount) {
                val k = rawNode(view.getChildAt(c), i) ?: continue
                kids.add(k); i++
            }
        }
        // The strict default: everything is MASKED. There is deliberately no
        // allow path in this walker — a Phase-1 tree contains no readable text
        // by construction.
        return RawNode(
            componentType = type, siblingOrdinal = ordinal,
            jsTreatment = Treatment.MASKED, nativeTreatment = native,
            textLength = textLength, bounds = bounds, children = kids,
            webContent = facts.webContent,
        )
    }

    /**
 * The PLATFORM privacy facts for ONE view — the single classification
 * authority shared by hierarchy capture and interaction hit-testing.
 *
 * `editable` and `secure` are carried alongside the Treatment because the
 * interaction policy needs them as SEPARATE suppression signals: a secure
 * target must suppress independently of any focus-state race, and an
 * editable target must suppress even when the hierarchy would merely mask
 * it. They are derived from the same platform inspection, never from a
 * second one.
 */
    internal data class ViewPrivacyFacts(
        val nativeTreatment: Treatment?,
        val blockedReason: BlockedReason?,
        val editable: Boolean,
        val secure: Boolean,
        /**
 * Is this view the WEB CONTENT SURFACE itself? Carried here, out of
 * the SAME `isWebContent` call the treatment is derived from, because
 * two answers to "is this web content" is exactly the drift this
 * classifier's own header warns about. On Android web content is
 * always BLOCKED, so today this is redundant with
 * `blockedReason == THIRD_PARTY_WEBVIEW` — it is carried anyway
 * because the node-level segment link must not be keyed on a
 * treatment that iOS is already allowed to relax.
 */
        val webContent: Boolean = false,
    )

    internal fun classifyView(view: View): ViewPrivacyFacts {
        var native: Treatment? = null
        var webContent = false
        var reason: BlockedReason? = null
        var editable = false
        var secure = false
        when {
            view is EditText -> {
                editable = true
                // Inputs are ALWAYS at least masked; a password field is
                // blocked outright — its subtree must never be inspected.
                if (isSecureInput(view)) {
                    secure = true
                    native = Treatment.BLOCKED; reason = BlockedReason.SECURE_TEXT_ENTRY
                } else native = Treatment.MASKED
            }
            // THE ONE HOME for "is this web content", shared with
            // `RnWebViewAttach.findWebViews`. Two answers to this question
            // would mean a surface this walker blocks and the attach ignores
            // (a blocked node with no segment) or the reverse (a segment on a
            // node nobody blocked). It was an inline `view is WebView` while
            // its neighbours here were already named predicates.
            RnWebViewAttach.isWebContent(view) -> {
                native = Treatment.BLOCKED; reason = BlockedReason.THIRD_PARTY_WEBVIEW
                webContent = true
            }
            isGpuSurface(view) -> {
                secure = true
                native = Treatment.BLOCKED; reason = BlockedReason.NATIVE_SECURE_SURFACE
            }
        }
        return ViewPrivacyFacts(native, reason, editable, secure, webContent)
    }

    /**
 * A bounded, class-derived role. Class names are code identifiers, not
 * user data; RN's host classes (ReactViewGroup, ReactTextView, …) give the
 * renderer enough to draw meaningful wireframes.
 */
    internal fun componentType(view: View): String =
        (view.javaClass.simpleName.ifEmpty { "View" }).take(32)

    /**
 * The password input classes. `inputType` is a bitfield: the VARIATION
 * bits carry the secure classes, so compare the masked variation — a plain
 * `and`-test against one constant misses numberPassword entirely.
 */
    internal fun isSecureInput(view: EditText): Boolean {
        val it = view.inputType
        val cls = it and android.text.InputType.TYPE_MASK_CLASS
        val variation = it and android.text.InputType.TYPE_MASK_VARIATION
        return when (cls) {
            android.text.InputType.TYPE_CLASS_TEXT ->
                variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            android.text.InputType.TYPE_CLASS_NUMBER ->
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
 * GPU-composited surfaces (SurfaceView/TextureView): their content is not
 * in the view tree and is exactly where camera previews, video and maps
 * render. Blocked as a native secure surface — never guessed at.
 */
    private fun isGpuSurface(view: View): Boolean =
        view is android.view.SurfaceView || view is android.view.TextureView
}

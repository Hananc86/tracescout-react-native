package com.tracescout.mobile.rn

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.tracescout.rn.RnWebViewAttachPlan
import com.tracescout.rn.RnWebViewCorrelation
import com.tracescout.rn.RnWebViewInbound
import com.tracescout.rn.RnWebViewSegment
import org.json.JSONObject

/**
 * G2-ATTACH-ANDROID — THE ANDROID ADAPTER THAT TURNS THE DECIDED PLAN INTO A
 * REAL ATTACHMENT, WITHOUT THIS MODULE EVER NAMING `androidx.webkit`.
 *
 * an earlier change measured the gap this closes: `addWebMessageListener`,
 * `addDocumentStartJavaScript` and `androidx.webkit` occur **0 / 0 / 0** times
 * in comment-stripped code across the whole RN path, so the bind an earlier change built
 * and an earlier change wired into the session lifecycle had nothing to attach to — it
 * could mint a segment and never receive a message.
 *
 * ## THE BOUNDARY IS THE WHOLE DESIGN, AND IT IS NOT A PREFERENCE
 *
 * `androidx.webkit` appears in neither `:sdk` nor `:rn`, by a decision an earlier change
 * recorded and `RnWebViewHostTest` enforces: *"an app that embeds no WebView
 * must not be made to carry the WebView support library because the SDK wanted
 * to ask a question about it."* So the two support-library calls belong to the
 * HOST APP, which embeds the WebView and therefore already declares the
 * library. What this module contributes is everything else:
 *
 * ```
 * DISCOVERY which surfaces under this root are web content (framework only)
 * FACTS javaScriptEnabled, read from android.webkit (framework only)
 * PLAN whether to install, with which rules and payload (rn-core, pure)
 * INTAKE parse, cross-check the framework facts, deliver (rn-core, pure)
 * ```
 *
 * The host supplies an [Attacher] — two lines — and gets to make no decision at
 * all. A host that computed its own origin rules or assembled its own identity
 * payload would be a second home for the two facts that define the boundary.
 *
 * ## IT NAMES `android.webkit.WebView`, WHICH IS THE FRAMEWORK, NOT THE
 * ## SUPPORT LIBRARY
 *
 * `RnHierarchySnapshotter` has imported `android.webkit.WebView` since Phase 1
 * to classify web content as BLOCKED. `WebView` ships in `android.jar`; only
 * `WebViewCompat` / `WebViewFeature` come from `androidx.webkit`. The two are
 * easy to conflate and the distinction is what makes this adapter possible at
 * all — so `RnWebViewAttachTest` asserts this module names the framework class
 * and none of the support library's.
 */
object RnWebViewAttach {

    /**
 * Bounds for the discovery walk.
 *
 * A VIEW-TREE WALK MUST BE BOUNDED. An unbounded recursion runs on the
 * main thread against a tree whose shape belongs to the host app's JS, and
 * the failure mode of guessing wrong is a stack overflow inside the
 * customer's app — not a missed WebView. These are generous relative to the
 * hierarchy walker's own budgets (400 nodes / depth 24), because this walk
 * visits every node while the walker's budget bounds what it SERIALISES.
 */
    const val MAX_DEPTH = 64
    const val MAX_VISITED = 4096

    /**
 * IS THIS VIEW WEB CONTENT? — the ONE home for the question.
 *
 * IT IS THE PREDICATE `RnHierarchySnapshotter` CLASSIFIES WITH, extracted
 * from an inline `view is WebView` so the walker and this attach cannot
 * disagree about what web content is. Its neighbours there (`isSecureInput`,
 * `isGpuSurface`) were already named predicates; this one was the odd one
 * out. Two answers to "is this a WebView" would mean a surface the walker
 * blocks and the attach ignores, or the reverse — a blocked node with no
 * segment, or a segment on a node nobody blocked.
 *
 * A TYPE TEST, NOT A CLASS-NAME MATCH. iOS identifies web content by
 * class NAME specifically so linking WebKit is never forced on a host app;
 * here the type is already in the framework and `:rn` already imports it,
 * so a name match would be strictly weaker — it would miss a subclass,
 * which is exactly what `react-native-webview` ships (`RNCWebView` extends
 * `WebView`).
 */
    @JvmStatic
    fun isWebContent(view: View): Boolean = view is WebView

    /**
 * Every web-content surface under [root], in traversal order.
 *
 * IT DOES NOT DESCEND INTO A WEBVIEW. A `WebView`'s children are the
 * engine's own internal views, never host content, and the DOM is not a
 * UIKit/Android view tree at all — the same reason the hierarchy walker
 * returns a placeholder node for web content without visiting its children.
 */
    @JvmStatic
    fun findWebViews(root: View?): List<WebView> {
        val out = ArrayList<WebView>()
        if (root == null) return out
        var visited = 0
        // An explicit stack rather than recursion: the depth bound is then a
        // property of this function and not of the JVM's stack size.
        val stack = ArrayDeque<Pair<View, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (v, depth) = stack.removeLast()
            if (++visited > MAX_VISITED) break
            if (isWebContent(v)) {
                out.add(v as WebView)
                continue // never descend into the engine's own views
            }
            if (depth >= MAX_DEPTH) continue
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { stack.addLast(it to depth + 1) }
                }
            }
        }
        return out
    }

    /**
 * The two `androidx.webkit` calls, as the host app implements them.
 *
 * EVERY PARAMETER IS A FRAMEWORK OR PLATFORM TYPE. No `androidx` type
 * appears in this signature, which is what lets `:rn` declare the seam
 * without declaring the dependency.
 */
    interface Attacher {
        /**
 * Install the message listener and the document-start script.
 *
 * The host must call, in this order and with these exact values:
 * `WebViewCompat.addWebMessageListener(webView, bridgeObject,
 * originRules) { _, message, sourceOrigin, isMainFrame, _ -> onMessage(…) }`
 * then `WebViewCompat.addDocumentStartJavaScript(webView,
 * identityScript, originRules)`.
 *
 * THE LISTENER GOES FIRST, DELIBERATELY. The script is what prompts
 * an admitted frame to echo; installing it before there is anywhere to
 * post would lose the echo of a document that was already loading.
 *
 * @return true when both calls succeeded. A host whose feature check
 * disagreed with reality must return false rather than throw — the
 * refusal is then recorded as an outcome instead of propagating into
 * the capture path.
 */
        fun install(
            webView: WebView,
            bridgeObject: String,
            originRules: Set<String>,
            identityScript: String,
            onMessage: (data: String, sourceOrigin: String?, isMainFrame: Boolean) -> Unit,
        ): Boolean
    }

    /** What happened to one surface. */
    sealed class Outcome {
        data class Installed(val segmentId: String) : Outcome()
        /** Nothing was installed. Carries the typed reason. */
        data class Refused(val why: String) : Outcome()
    }

    /**
 * HOW THE PER-SURFACE PASS REACHES THE UI THREAD.
 *
 * THIS SEAM EXISTS BECAUSE F-23 IS UNTESTABLE WITHOUT IT. `:rn` has no
 * Robolectric and no `returnDefaultValues`, so `android.os.Looper.myLooper`
 * in a unit test throws *"not mocked"* — a hop written directly against the
 * framework could not be driven in ANY venue this module has, and the
 * timeout branch could not be reached at all. The default is the real
 * framework hop, so production behaviour does not depend on the seam; a test
 * supplies an inline runner, or one that refuses, to drive both paths.
 *
 * IT IS ALSO A WAY TO RE-INTRODUCE THE DEFECT, so the production call
 * site must pass NOTHING and take the default. `RnWebViewHostTest` asserts
 * exactly that: an `attach(` call carrying a fourth argument in the fixture
 * or the SDK is a build that has quietly gone back off the UI thread.
 */
    interface MainThreadRunner {
        /**
 * Run [body] on the UI thread and return its result, or `null` if the UI
 * thread could not be obtained within the budget.
 *
 * `null` MEANS "WE NEVER GOT TO LOOK", NOT "NOTHING WAS INSTALLED".
 * The caller turns it into a typed `main_thread_unavailable` refusal
 * rather than inventing a cause — the rule
 * `RnHierarchySnapshotter.observe` states for its own timeout: *"a
 * wedged main thread tells us nothing about whether the surface was
 * attached — we never got to look."*
 */
        fun runOnMain(body: () -> List<Outcome>): List<Outcome>?
    }

    /**
 * The production runner: already on the UI thread → run inline; otherwise
 * post and wait, BOUNDED.
 *
 * A BOUNDED WAIT, mirroring `RnHierarchySnapshotter` (which hops from the
 * same React module queue for the same reason). An unbounded wait on the
 * main thread from the module queue is a deadlock waiting for a slow frame.
 */
    object FrameworkMainThread : MainThreadRunner {
        /**
 * DELIBERATELY ITS OWN CONSTANT, not a reuse of the snapshotter's.
 * That one is `private` to a different subject (a whole hierarchy
 * traversal plus an optional frame); this one covers a settings read and
 * two support-library calls. Sharing the number would tie two unrelated
 * budgets together, and neither owner would know.
 */
        const val MAIN_THREAD_WAIT_MS = 1500L

        override fun runOnMain(body: () -> List<Outcome>): List<Outcome>? {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return body()
            var out: List<Outcome>? = null
            var thrown: Throwable? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { out = body() } catch (t: Throwable) { thrown = t } finally { latch.countDown() }
            }
            val got = latch.await(MAIN_THREAD_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            // A THROW ON THE UI THREAD IS RE-RAISED, NOT CONVERTED TO A
            // TIMEOUT. Reporting it as `main_thread_unavailable` would blame the
            // scheduler for a fault in the body — the swallowing F-23 is about,
            // one layer out.
            thrown?.let { throw it }
            if (!got) return null
            return out ?: emptyList()
        }
    }

    /**
 * Attach every web-content surface under [root] for this session's
 * correlation.
 *
 * A NULL CORRELATION REFUSES, IT DOES NOT ATTACH BLIND. With no
 * correlation there is no minted id to echo and nothing to compare an echo
 * against, so a listener installed here could only ever refuse — silently,
 * in a live page. an earlier change's reasoning, one layer out: *"a bridge that CANNOT
 * ADMIT is not harmless."*
 *
 * ONE OUTCOME PER SURFACE, and a refusal for one surface never stops the
 * others. A host app can embed an admitted help document and an unadmitted
 * third-party widget in the same route, and the second must not silence the
 * first.
 */
    @JvmStatic
    @JvmOverloads
    fun attach(
        root: View?,
        correlation: RnWebViewCorrelation?,
        attacher: Attacher,
        mainThread: MainThreadRunner = FrameworkMainThread,
    ): List<Outcome> {
        // THE EARLY ANSWERS STAY OFF THE UI THREAD, DELIBERATELY. Neither
        // touches a WebView: `findWebViews` is a read-only walk of the view
        // tree (exactly what the predecessor did from this same thread), and a
        // null correlation is decided without asking a surface anything. A hop
        // taken before them would make `attach(root = null, …)` — the one shape
        // the existing JVM suite can drive — reach `android.os.Looper`, which
        // this module's unit venue does not mock, so the row would REFUSE
        // instead of passing. Discovery remaining off-main is a PRE-EXISTING
        // property this change does not alter; moving it is its own decision
        // with its own evidence, and row [9c] pins that it is still computed
        // before the hop so a later reader knows the placement was chosen.
        val surfaces = findWebViews(root)
        if (surfaces.isEmpty()) return emptyList()
        if (correlation == null) {
            return surfaces.map { Outcome.Refused("no_correlation") }
        }
        // EVERYTHING THAT TOUCHES A WebView RUNS ON THE UI THREAD — THIS IS
        // F-23 (board #1506). `WebView.checkThread` THROWS when a WebView
        // method is called off the thread that created it and `targetSdk > 18`,
        // and the only production caller of this function —
        // `TraceScoutRnModule.ensureWebViewAttached` — runs on the React Native
        // MODULE QUEUE. The predecessor read `wv.settings.javaScriptEnabled`
        // inside `runCatching { … }.getOrDefault(false)`, so that throw became
        // the value `false`, and `RnWebViewAttachPlan.decide` then refused
        // `js_disabled`: EVERY attach on Android refused, on every route,
        // whatever the page's scripting setting actually was. Proven on the
        // device because `uat_webview_handshake` (JS ON) and `webview_help`
        // (JS OFF) produced the IDENTICAL refusal.
        //
        // THE SETTINGS READ WAS NOT THE ONLY SWALLOWED THROW. `attacher
        //.install` calls `addWebMessageListener` / `addDocumentStartJavaScript`,
        // which are main-thread-only too, inside its own
        // `runCatching { … }.getOrDefault(false)` — so fixing the read alone
        // would have moved the refusal from `js_disabled` to `install_failed`
        // and left the capability exactly as broken. One hop covers both.
        val out = mainThread.runOnMain { attachOnMain(surfaces, correlation, attacher) }
        // A WEDGED UI THREAD IS NOT A REFUSAL BY ANY SURFACE. We never got to
        // look, so the reason names the thread and nothing else — never
        // `js_disabled`, which would be an invented cause (the exact failure
        // this fix exists to remove) and would read as a page setting.
        return out ?: surfaces.map { Outcome.Refused("main_thread_unavailable") }
    }

    /**
 * The per-surface pass. ALWAYS called on the UI thread.
 *
 * NOTHING HERE SWALLOWS A THROW INTO A VALUE. Each failure gets its OWN
 * typed reason, so the stored outcome says which stage failed:
 *
 * ```
 * js_disabled the framework was ASKED and answered false
 * settings_unreadable the read itself failed (F-23's signature)
 * install_threw a support-library call raised
 * install_failed the attacher returned false
 * ```
 *
 * `js_disabled` NOW MEANS WHAT IT SAYS. Before this it also meant "we
 * could not read the setting", which is why the help-document route and the
 * handshake route were indistinguishable in the evidence. A refusal that
 * cannot be told apart from a different refusal is not a diagnosis.
 */
    private fun attachOnMain(
        surfaces: List<WebView>,
        correlation: RnWebViewCorrelation,
        attacher: Attacher,
    ): List<Outcome> {
        val admitted = correlation.policy
        return surfaces.map { wv ->
            outcomeFor(
                correlation = correlation,
                admitted = admitted,
                // The FRAMEWORK's own answer, read and never set. The RN
                // fixture's help-document route runs with scripting off on
                // purpose; turning it on to make a handshake possible would
                // change the subject being measured.
                readJsEnabled = { wv.settings.javaScriptEnabled },
                install = { plan ->
                    attacher.install(
                        webView = wv,
                        bridgeObject = plan.bridgeObject,
                        originRules = plan.originRules,
                        identityScript = plan.identityScript,
                        onMessage = { data, origin, isMain ->
                            deliver(correlation, data, origin, isMain)
                        },
                    )
                },
            )
        }
    }

    /**
 * ONE surface's outcome, over the two operations that can THROW.
 *
 * IT TAKES CLOSURES BECAUSE `:rn`'s UNIT VENUE CANNOT BUILD A WebView.
 * That module has no Robolectric and no `returnDefaultValues`, so a
 * `WebView` cannot be instantiated and `findWebViews` can never return a
 * non-empty list there — which is why the predecessor's whole per-surface
 * body was covered by SOURCE PINS alone, and why a read that silently
 * became `false` survived every one of them. With the two throwing
 * operations passed in, the mapping from *what the framework did* to *which
 * typed reason is stored* is driven for real, with no mocking framework and
 * no new test dependency.
 *
 * IT IS `internal` WITH EXACTLY ONE CALLER ([attachOnMain]), because a
 * seam that lets a caller supply a non-throwing reader is a way to
 * re-introduce F-23. Row [9f] pins the single call site.
 */
    internal fun outcomeFor(
        correlation: RnWebViewCorrelation,
        admitted: List<String>?,
        readJsEnabled: () -> Boolean,
        install: (RnWebViewAttachPlan.Plan.Install) -> Boolean,
    ): Outcome {
        val jsEnabled = try {
            readJsEnabled()
        } catch (t: Throwable) {
            // Reported, never defaulted. `settings_unreadable` is a DIFFERENT
            // fact from `js_disabled`: the predecessor collapsed them, which is
            // exactly why the device evidence could not tell the handshake
            // route from the help-document route.
            return Outcome.Refused("settings_unreadable")
        }
        val facts = RnWebViewAttachPlan.SurfaceFacts(javaScriptEnabled = jsEnabled)
        return when (
            val plan = RnWebViewAttachPlan.decide(
                capability = correlation.capability,
                admittedOrigins = admitted,
                segmentId = correlation.segmentId,
                surface = facts,
            )
        ) {
            is RnWebViewAttachPlan.Plan.Refuse -> Outcome.Refused(plan.why)
            is RnWebViewAttachPlan.Plan.Install -> {
                val ok = try {
                    install(plan)
                } catch (t: Throwable) {
                    // A support-library call raised — distinct from the
                    // attacher deciding not to install.
                    return Outcome.Refused("install_threw")
                }
                if (ok) Outcome.Installed(correlation.segmentId)
                else Outcome.Refused("install_failed")
            }
        }
    }

    /**
 * ONE inbound message: parse, cross-check the framework's facts, deliver.
 *
 * THE PARSE IS HERE AND NOT IN rn-core, BECAUSE rn-core HAS NO RUNTIME
 * DEPENDENCY. `org.json` ships in `android.jar`, so this module can parse
 * for free while the pure module stays dependency-free by construction —
 * the same split the native Android bridge uses.
 *
 * UNPARSEABLE IS A REFUSAL, NEVER AN EMPTY FIELD MAP. An empty map reaches
 * `RnWebViewSegment.decide` and is refused `frame_role_missing`, which sends
 * a reader after a page that is in fact sending malformed bytes.
 *
 * CORRECTED 2026-10-21: this comment used to say an empty map is
 * refused as *"a message with no segment id"*. It is not, and the
 * difference is the ordering `decide`'s own class docs call the contract:
 * with an empty field map, origin admission passes (the browser supplied the
 * origin and the page declared none, so there is nothing to contradict),
 * then the FRAME ROLE is absent and refuses — before the segment id is read
 * at all. Measured by `RnWebViewHandshakeVectorsTest`, whose first version
 * of that vector carried the same wrong expectation and failed. The point
 * the comment was making is unchanged and if anything stronger: the refusal
 * is even further from naming the real cause.
 */
    @JvmStatic
    fun deliver(
        correlation: RnWebViewCorrelation,
        data: String?,
        sourceOrigin: String?,
        isMainFrame: Boolean,
    ): RnWebViewSegment.Decision? {
        val parsed = parse(data) ?: return null
        val verdict = RnWebViewInbound.decide(
            kind = parsed.first,
            fields = parsed.second,
            sourceOrigin = sourceOrigin,
            isMainFrame = isMainFrame,
        )
        return when (verdict) {
            is RnWebViewInbound.Verdict.Drop -> null
            is RnWebViewInbound.Verdict.Deliver -> correlation.receive(
                kind = verdict.kind,
                fields = verdict.fields,
                authenticatedOrigin = verdict.authenticatedOrigin,
            )
        }
    }

    /**
 * `{"kind":"…","fields":{…}}` → the pair, or null when the bytes are not
 * that.
 *
 * THE FIELDS ARE PASSED THROUGH UNFILTERED. `RnWebViewSegment`'s
 * `ALLOWED_FIELDS` is the closed vocabulary and `decide` is where it is
 * enforced; filtering here would be a second home for it, and the two could
 * then disagree about which key is accepted.
 *
 * BOUNDED. A page controls these bytes, so the payload is capped before
 * it is parsed at all — an unbounded `JSONObject` over a hostile string is
 * memory the page chose.
 */
    @JvmStatic
    fun parse(data: String?): Pair<String?, Map<String, Any?>>? {
        val s = data?.trim() ?: return null
        if (s.isEmpty() || s.length > MAX_MESSAGE_CHARS) return null
        return runCatching {
            val o = JSONObject(s)
            val kind = o.optString("kind").takeIf { it.isNotEmpty() }
            val fieldsObj = o.optJSONObject("fields")
            val map = LinkedHashMap<String, Any?>()
            if (fieldsObj != null) {
                val it = fieldsObj.keys()
                var n = 0
                while (it.hasNext() && n++ < MAX_FIELDS) {
                    val k = it.next()
                    map[k] = when (val v = fieldsObj.get(k)) {
                        JSONObject.NULL -> null
                        is String -> v.take(MAX_FIELD_CHARS)
                        is Boolean, is Int, is Long, is Double -> v
                        // A nested object or array is not in the vocabulary and
                        // is DROPPED rather than stringified: a stringified
                        // object would reach `decide` as a plausible value.
                        else -> null
                    }
                }
            }
            kind to (map as Map<String, Any?>)
        }.getOrNull()
    }

    /** Page-controlled bytes, so every bound is explicit. */
    const val MAX_MESSAGE_CHARS = 8192
    const val MAX_FIELDS = 32
    const val MAX_FIELD_CHARS = 512
}

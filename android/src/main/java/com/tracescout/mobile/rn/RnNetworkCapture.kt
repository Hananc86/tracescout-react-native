package com.tracescout.mobile.rn

import com.tracescout.rn.NetworkBudget
import com.tracescout.rn.NetworkCaptureCore
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * M3.3 — the Android network producer's OKHTTP ADAPTER.
 *
 * THIS FILE OWNS NO POLICY. Every decision — the opt-in, self-exclusion, URL
 * normalization, failure classification, the budget, the event's field set —
 * lives in `NetworkCaptureCore`, which is OkHttp-free and unit-tested on the
 * JVM. This class observes OkHttp and calls in. A rule that lived here could
 * only be tested on a device, which is how untested rules happen.
 *
 * ## The seam
 *
 * `NetworkingModule.setCustomClientBuilder` is applied to `client.newBuilder`
 * on EVERY RN request, and the derived client is the one `newCall` uses
 * (NetworkingModule.kt:366-368 →:619). So composing here reaches the client
 * that actually performs the request — including a client produced by an
 * application's own `OkHttpClientFactory`, which REPLACES construction and would
 * discard anything attached in `createClientBuilder`.
 *
 * ## What it never does
 *
 * It never reads a request or response body, never iterates or reads a header,
 * never stringifies a URL into telemetry, and never touches the application's
 * request or response. `EventListener` is observational by construction: OkHttp
 * hands it byte COUNTS and, on failure, an exception — of which only the CLASS
 * reaches the core, never the message.
 */
object RnNetworkCapture {

    /**
 * The immutable start context, carried as an IN-PROCESS OkHttp tag.
 *
 * A TAG, NEVER A HEADER. A header would put our instrumentation marker on
 * the wire, visible to the customer's server and to anyone on the path, for
 * our own convenience. Nothing here can carry a raw URL, a query, a header,
 * a body, a credential or an exception message — the type has no field for
 * one.
 */
    internal class Ctx(val start: NetworkCaptureCore.StartContext) {
        val requestBytes = AtomicLong(-1)
        val responseBytes = AtomicLong(-1)
        val status = java.util.concurrent.atomic.AtomicInteger(-1)
        /** One terminal outcome per call, whatever OkHttp does. */
        val completed = AtomicBoolean(false)
    }

    /**
 * The marker on TraceScout's OWN transport requests.
 *
 * SELF-EXCLUSION IS BY THIS TAG, NEVER BY HOST. Excluding
 * `api.tracescout.com` by name would blind us to a customer app that
 * legitimately calls us, AND would still miss our own traffic to any other
 * host — a rule that is wrong in both directions.
 */
    class InternalTraceScoutRequest

    /** Everything the adapter needs from the SDK, injected so it stays testable. */
    interface Host {
        fun captureEnabled(): Boolean
        fun hasSession(): Boolean
        fun currentScreenId(): String?
        fun currentGestureId(): String?
        fun newNetworkRequestId(): String
        fun nowWallMillis(): Long
        fun nowMonotonicNanos(): Long
        fun budget(): NetworkBudget
        /** Enqueue the completed event. MUST NOT throw into OkHttp. */
        fun emit(event: NetworkCaptureCore.NetworkEvent)

        /**
 * The core declined to create a start context.
 *
 * THE DISABLED STATE MUST BE DISTINGUISHABLE FROM A BROKEN SEAM.
 * Without this, a host that is installed and refusing every request
 * looks exactly like a host that was never installed — the same
 * "a check that cannot see its own subject" shape this producer has
 * paid for repeatedly.
 *
 * It takes the core's own [NetworkCaptureCore.Refusal], not a string.
 * A String parameter here would be a generic logger wearing a typed
 * name, and the Host is then provably free of ANY method through which
 * free text could arrive.
 */
        fun refused(refusal: NetworkCaptureCore.Refusal)

        /**
 * Instrumentation failed somewhere it must not take the application
 * down with it.
 *
 * Takes a CLOSED enum, not a message. A `diagnostic(String)` would be a
 * generic logger carrying an arbitrary string into a durable file — the
 * one shape through which a URL, a header value or an exception message
 * eventually arrives in telemetry nobody audits.
 */
        fun failed(site: FailureSite)
    }

    /**
 * The only places instrumentation can fail without the application
 * noticing. [INSTALL] belongs here because the host application installs
 * the seam and must be able to report that it could not, using the same
 * closed vocabulary — and because it is the one failure whose symptom
 * (no events at all) is identical to capture simply being off.
 */
    enum class FailureSite(val wire: String) {
        INSTALL("install"),
        CALL_START("call_start"),
        FINISH("finish"),
    }

    // ── composition ──────────────────────────────────────────────────────────

    /**
 * Compose TraceScout instrumentation onto RN's per-request client builder.
 *
 * The application's own builder runs FIRST and its every setting survives —
 * interceptors, timeouts, cache, dispatcher, cookie jar, connection pool. We
 * only add an [EventListener] and one tag-attaching interceptor.
 *
 * THE RN SLOT IS LAST-WRITER-WINS AND HAS NO GETTER
 * (`NetworkingModule.kt:1056`). An application that calls
 * `setCustomClientBuilder` AFTER us replaces the slot and removes our
 * coverage. That is a documented limitation, not something to defeat: we
 * emit a diagnostic if we can detect it and NEVER write ourselves back over
 * the application's later choice. Reflection or a re-install loop would be
 * us fighting the host app for control of its own networking.
 */
    @JvmStatic
    fun install(builder: OkHttpClient.Builder, host: Host, existing: ((OkHttpClient.Builder) -> Unit)? = null) {
        // The app's configuration is applied first and is never inspected or
        // reverted — whatever it produced is what we instrument.
        existing?.invoke(builder)
        builder.eventListener(Listener(host))
    }

    // ── the listener ─────────────────────────────────────────────────────────

    internal class Listener(private val host: Host) : EventListener() {

        private val contexts = java.util.Collections.synchronizedMap(HashMap<Call, Ctx>())

        override fun callStart(call: Call) {
            try {
                val req: Request = call.request()
                // Self-exclusion is read from the IN-PROCESS tag and is decided
                // by the core BEFORE it looks at the URL.
                val internal = req.tag(InternalTraceScoutRequest::class.java) != null
                val r = NetworkCaptureCore.startContext(
                    enabled = host.captureEnabled(),
                    internalRequest = internal,
                    hasSession = host.hasSession(),
                    // The raw URL exists ONLY as this argument. The core
                    // normalizes and discards it; nothing retains it.
                    rawUrl = req.url.toString(),
                    method = req.method,
                    networkRequestId = host.newNetworkRequestId(),
                    startedWallTime = host.nowWallMillis(),
                    startedMonotonicNanos = host.nowMonotonicNanos(),
                    screenId = host.currentScreenId(),
                    gestureId = host.currentGestureId(),
                    traceId = null,
                    spanId = null,
                    budget = host.budget(),
                )
                if (r.captured) contexts[call] = Ctx(r.context!!)
                // A refusal is a FACT about this request, reported once per
                // reason by the host. Dropping it on the floor is what made
                // "capture is off" and "the listener never ran" the same
                // observation.
                else r.refusal?.let { host.refused(it) }
            } catch (t: Throwable) {
                // INSTRUMENTATION MUST NEVER AFFECT THE APPLICATION REQUEST.
                // A throw from a listener callback propagates into OkHttp's call
                // machinery; swallowing here is the difference between "we lost
                // one telemetry event" and "the customer's request failed
                // because of our observability".
                safeDiag(FailureSite.CALL_START)
            }
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            // A COUNT, handed to us by OkHttp. The body is never read.
            contexts[call]?.requestBytes?.set(byteCount)
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            contexts[call]?.responseBytes?.set(byteCount)
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            // ONLY `response.code`. Header VALUES are never read, and the
            // response object is not retained.
            //
            // REDIRECTS: OkHttp follows them inside ONE call, so this fires
            // once per hop and the LAST value wins — which is exactly the v1
            // contract's "one logical request, final status". No intermediate
            // URL, `Location` value or redirect chain is read or stored, and no
            // event is emitted per hop because emission happens only at the
            // single terminal callback.
            contexts[call]?.status?.set(response.code)
        }

        override fun callEnd(call: Call) = finish(call, null, false)

        override fun callFailed(call: Call, ioe: IOException) =
            finish(call, ioe, isCanceled(call))

        override fun canceled(call: Call) {
            // OkHttp may report cancellation without a terminal callback in some
            // paths; the terminal handler is still the only emitter, so this is
            // recorded rather than acted on.
        }

        private fun isCanceled(call: Call): Boolean = try { call.isCanceled() } catch (t: Throwable) { false }

        private fun finish(call: Call, ioe: IOException?, canceled: Boolean) {
            val ctx = contexts.remove(call) ?: return
            // EXACTLY ONE EVENT PER LOGICAL CALL. OkHttp can deliver a
            // terminal callback more than once in retry/cancel races; a
            // compare-and-set makes a duplicate a no-op rather than a second row
            // the server would have to de-duplicate for us.
            if (!ctx.completed.compareAndSet(false, true)) return
            try {
                val end = host.nowMonotonicNanos()
                val reqB = ctx.requestBytes.get().takeIf { it >= 0 }
                val resB = ctx.responseBytes.get().takeIf { it >= 0 }
                val status = ctx.status.get()
                val event = if (ioe == null && status >= 100) {
                    NetworkCaptureCore.completeWithResponse(ctx.start, end, status, reqB, resB)
                } else {
                    NetworkCaptureCore.completeWithFailure(
                        ctx.start, end,
                        // THE CLASS NAME, NEVER `ioe.message`. A provider
                        // message is free text from outside our control and is
                        // exactly where a URL or a credential enters telemetry.
                        exceptionClassName = ioe?.javaClass?.name,
                        canceled = canceled,
                        requestBytes = reqB, responseBytes = resB,
                    )
                }
                if (event != null) host.emit(event)
            } catch (t: Throwable) {
                safeDiag(FailureSite.FINISH)
            }
        }

        private fun safeDiag(site: FailureSite) {
            // A closed enum names the CALLBACK. Never the request, never the
            // URL, never the exception text.
            try { host.failed(site) } catch (_: Throwable) {}
        }
    }
}

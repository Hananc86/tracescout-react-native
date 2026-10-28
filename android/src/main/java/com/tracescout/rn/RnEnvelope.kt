package com.tracescout.rn

/**
 * Envelope assembly for the React Native producer.
 *
 * Deliberately NOT the Android SDK's `emit/Envelope` (see ARCHITECTURE.md D1):
 * that object's identity constants are compile-time on purpose, and a shared
 * runtime seam would let this producer claim `tracescout-android`. The durable
 * queue, transport, ack validation and recovery ARE reused unchanged — the
 * parts where duplication would be dangerous.
 */
object RnEnvelope {

    /** The complete eight-field posture. Order is the documentation. */
    data class Posture(
        val level: String = "strict",
        val maskText: Boolean = true,
        val maskInputs: Boolean = true,
        val maskAccessibilityLabels: Boolean = true,
        val blockImages: Boolean = true,
        val blockCameraSurfaces: Boolean = true,
        val allowedViewCount: Int,
        val screenNeverRecord: Boolean,
    )

    data class Identity(
        val projectId: String,
        val sessionId: String,
        val anonymousId: String,
        val environment: String,
        /**
 * The HOST APPLICATION's package identifier, supplied natively.
 *
 * Without this, the server cannot ORIGINATE the session and every
 * chunk dies `404 session_not_found` — which the shared pipeline
 * classifies TERMINAL and QUARANTINES, destroying the records. Proven
 * on device: `http_response_received action=TERMINAL status=404
 * code=session_not_found` → `quarantinePrefix`.
 *
 * A native session has no browser chunk to originate it, so the API
 * originates one from the `mobile_session_start` opener — but ONLY if
 * that opener "names itself": protocolVersion + platform + sdkFamily +
 * **appIdentifier**. RN emitted the first three and not the fourth, so
 * `namesItself` was false and the chunk was refused rather than
 * originating a session with no identity.
 *
 * It lives on Identity, not on a bridge parameter, because it is a fact
 * about the app the SDK is embedded in — read natively from
 * `Context.packageName`. A JS-settable value would let a producer
 * claim to be a different application.
 */
        val appIdentifier: String,
        /** The route the session opened on. Native, from the first record. */
        val initialScreen: String? = null,
        /**
 * The app build the session was recorded on — `versionName` and
 * `versionCode`, read NATIVELY from the running package like
 * `appIdentifier` above, for the same reason: a JS-settable value would
 * let a producer claim to be a different build.
 *
 * KEPT SEPARATE, as the protocol declares them. Collapsing a
 * marketing version and a build number into one opaque string is how a
 * rebuilt binary silently mis-symbolicates; it is also what makes
 * "which TestFlight build is this session from?" answerable at all.
 *
 * Nullable because `PackageManager` can refuse, and an unknown build is
 * recorded as absent rather than guessed — the server stores NULL and
 * the dashboard's App Version filter correctly matches nothing.
 */
        val appVersion: String? = null,
        val buildNumber: String? = null,
        /**
 * Bounded device facts — the protocol's `device` object. iOS already
 * carries `osName`/`osVersion` here; Android carried nothing, so the
 * server's `os_name` and `device_type` columns stayed NULL for every
 * native session and the dashboard's Operating System and Device
 * filters could not match one.
 */
        val device: DeviceFacts? = null,
    )

    /**
 * The protocol's `device` object, typed NARROWLY and carrying ONLY keys
 * `envelope.schema.json` declares (`additionalProperties: false`).
 *
 * TYPED, NOT A MAP, for the reason `ScreenRef` is: an untyped `Map`
 * falls through `value`'s `else -> str(v.toString)` and ships a quoted
 * Kotlin `toString` as the wire field.
 *
 * DECLARED KEYS ONLY. The Android NATIVE SDK's `Envelope.deviceJson`
 * emits `density`, which the schema does not declare — so a validator that
 * enforced the object's `additionalProperties: false` would refuse it. Do
 * not copy that key here; `pixelRatio` is the declared name for the same
 * fact. (That divergence is a finding against the native producer, not a
 * precedent.)
 *
 * `screenWidth`/`screenHeight` are PHYSICAL PIXELS on Android and
 * `pixelRatio` is the scale that converts them to density-independent
 * points — the server needs BOTH to derive a device type, because iOS
 * reports logical points and the same number means different things on the
 * two platforms. Emitting the pixels without the scale is worse than
 * emitting neither: it reads as a plausible dp value.
 */
    data class DeviceFacts(
        val manufacturer: String? = null,
        val model: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val screenWidth: Int? = null,
        val screenHeight: Int? = null,
        val pixelRatio: Double? = null,
        val densityDpi: Int? = null,
    ) {
        /** True when nothing is known — the envelope omits the object entirely. */
        fun isEmpty(): Boolean =
            manufacturer == null && model == null && osName == null && osVersion == null &&
                screenWidth == null && screenHeight == null && pixelRatio == null &&
                densityDpi == null
    }

    /**
 * Canonical envelope screen identity — the protocol's nested `screen`
 * object (envelope.schema.json: screen:{screenId,name,kind,navigationKey},
 * additionalProperties:false on the object itself). Typed NARROWLY on
 * purpose: an untyped Map would fall through value's
 * `else -> str(v.toString)` and ship a quoted Kotlin toString as the
 * wire field — a second wire-format defect wearing the fix's clothes.
 * RN route identities are kind:"route" per the canonical enum.
 */
    data class ScreenRef(val screenId: String, val kind: String = "route")

    /**
 * MODEL B's **S** on the envelope — the native↔WebView correlation.
 *
 * THE CONSTRUCTOR IS PRIVATE AND [of] IS THE ONLY DOOR. The only way to
 * obtain one is from a segment `RnWebViewSegment.decide` ADMITTED, so a
 * page-chosen segment id, an unadmitted origin, an unsupported WebView
 * provider or an out-of-enum browser status cannot reach the wire by a
 * caller assembling this type by hand. the specification's "nothing may claim them"
 * becomes structural rather than remembered.
 *
 * THE STATUS TRAVELS ONLY ALONGSIDE THE SEGMENT, which is what the
 * envelope schema says of it: *"Only meaningful alongside
 * `webViewSegmentId`"*. Carrying it alone would put a field on the wire
 * with no context to interpret it in, and the server reads it only inside
 * the segment's own branch, so it would be bytes nobody can use.
 */
    class WebViewRef private constructor(
        val segmentId: String,
        val browserPrivacyStatus: String?,
    ) {
        companion object {
            @JvmStatic
            fun of(segment: RnWebViewSegment.Segment): WebViewRef =
                WebViewRef(segment.segmentId, segment.browserPrivacyStatus)
        }
    }

    /**
 * int64 values are emitted as STRINGS. JSON numbers are doubles: above
 * 2^53 a numeric form silently loses the low bits, which reorders events.
 * `sequence` and `monotonicNanos` are the ordering authority, so this is the
 * one place a rounding error becomes lost or misordered evidence.
 */
    fun envelope(
        id: Identity,
        eventId: String,
        eventType: String,
        sequence: Long,
        timestampMillis: Long,
        monotonicNanos: Long,
        posture: Posture,
        extra: Map<String, Any?> = emptyMap(),
        /**
 * MODEL B. Present on EVERY event of a hybrid session, not only the
 * opener — the protocol accepts the envelope-level segment id on any
 * event (`conformance.test.mjs`: *"envelope-level segment id must be
 * accepted on any event"*), and the server derives `sessionKind =
 * hybrid` from ANY event carrying one. It is deliberately NOT under
 * the opener-only rule that `appIdentifier`/`device` follow: a WebView
 * is entered mid-session, and a reload mints a NEW segment, so a value
 * fixed at the opener could not describe either.
 *
 * LAST AND DEFAULTED, like `positionTreatment` on the interaction
 * policy and for the same reason: adding it in the middle renumbers
 * every positional call site, and a caller that has not been taught
 * about WebView correlation must get the pre-existing answer — no
 * segment — never a silently widened one.
 */
        webView: WebViewRef? = null,
    ): String {
        val fields = LinkedHashMap<String, Any?>()
        fields["eventId"] = eventId
        fields["eventType"] = eventType
        fields["protocolVersion"] = Producer.PROTOCOL_VERSION
        fields["platform"] = Producer.PLATFORM
        fields["hostPlatform"] = Producer.HOST_PLATFORM_ANDROID
        fields["sdkFamily"] = Producer.SDK_FAMILY
        fields["sdkVersion"] = Producer.SDK_VERSION
        fields["projectId"] = id.projectId
        fields["sessionId"] = id.sessionId
        fields["anonymousId"] = id.anonymousId
        fields["environment"] = id.environment
        // Only the opener needs to name the app — the server reads identity
        // from the `mobile_session_start` event alone. Emitting it on every
        // event would be redundant bytes on a per-event wire.
        if (eventType == "mobile_session_start") {
            fields["appIdentifier"] = id.appIdentifier
            // Build + device identity ride the SAME opener-only rule, for the
            // same reason: the server reads a native session's identity from
            // `mobile_session_start` alone (immutable, first-non-null-wins), so
            // repeating them on every hierarchy, frame, navigation, lifecycle
            // and network event would pay bytes forever for values that cannot
            // change within the session. A null is OMITTED by `json`, never
            // emitted as null.
            fields["appVersion"] = id.appVersion
            fields["buildNumber"] = id.buildNumber
            // An all-null device object is omitted entirely rather than sent as
            // `{}` — an empty object asserts "we looked and found nothing",
            // which is a different claim from "we did not look".
            fields["device"] = id.device?.takeUnless { it.isEmpty() }
            // Canonical carrier for the opening route is the envelope `screen`
            // object. `initialScreen` was a NON-SCHEMA alias and is gone from
            // the wire (the Identity property name is internal only). The
            // bridge's extra map overwrites this when it knows a fresher route.
            if (id.initialScreen != null) fields["screen"] = ScreenRef(id.initialScreen)
        }
        fields["sequence"] = sequence            // string on the wire
        fields["timestamp"] = timestampMillis    // number: ms fits in 2^53
        fields["monotonicNanos"] = monotonicNanos // string on the wire
        fields["privacyPosture"] = posture
        // THE TYPED PARAMETER IS THE ONLY DOOR, so the two correlation keys
        // are DROPPED from the untyped `extra` map rather than forwarded. The
        // three outcomes of a stray extra key are not equal: dropping it loses
        // a correlation (an UNDER-report, which claims nothing false and is
        // caught by `NO_EXTRA_WEBVIEW_KEYS`'s source pin); forwarding it could
        // put a page-chosen id or an out-of-enum status on the wire, which the
        // API refuses PERMANENTLY and quarantines the whole chunk. Dropping is
        // the only direction that cannot destroy records.
        for ((k, v) in extra) if (v != null && k !in NO_EXTRA_WEBVIEW_KEYS) fields[k] = v
        if (webView != null) {
            fields["webViewSegmentId"] = webView.segmentId
            if (webView.browserPrivacyStatus != null) {
                fields["webViewBrowserPrivacyStatus"] = webView.browserPrivacyStatus
            }
        }
        return json(fields)
    }

    /**
 * The two envelope keys that may travel ONLY through the typed
 * [WebViewRef] door. Named here so the source pin and the filter read one
 * list — a second copy is how the filter and the guard start disagreeing.
 */
    val NO_EXTRA_WEBVIEW_KEYS = setOf("webViewSegmentId", "webViewBrowserPrivacyStatus")

    /**
 * The `mobile_view_hierarchy` event. Its posture carries THIS EVENT's
 * derived allowedViewCount — the planner validates the scalar against the
 * distinct allowed identities in the very nodes attached here, so both
 * come from the same [HierarchyResult] and cannot disagree.
 *
 * Byte-parallel to the Swift `RnEnvelope.viewHierarchy` — same fields, same
 * order, same truncation disclosure.
 */
    fun viewHierarchy(
        id: Identity,
        eventId: String,
        sequence: Long,
        timestampMillis: Long,
        monotonicNanos: Long,
        posture: Posture,
        snapshotId: String,
        screenId: String,
        result: HierarchyResult,
        captureDurationMs: Long,
        /**
 * MODEL B. The hierarchy event of a screen hosting an admitted WebView
 * carries the correlation so the renderer can stitch the browser
 * segment onto the screen it belongs to. The WebView node itself stays
 * BLOCKED with its typed reason — the specification's honest absence, and the shape
 * the already-accepted S5 visual rows certified. LAST and DEFAULTED,
 * so a caller that has not been taught about WebView correlation gets
 * the pre-existing answer.
 */
        webView: WebViewRef? = null,
    ): String {
        val extra = LinkedHashMap<String, Any?>()
        extra["screen"] = ScreenRef(screenId)
        extra["snapshotId"] = snapshotId
        extra["nodeCount"] = result.totalNodes
        extra["captureDurationMs"] = captureDurationMs.toInt()
        extra["degraded"] = false
        extra["encoding"] = "json"
        extra["nodes"] = result.nodes
        if (result.truncated) {
            extra["truncated"] = true
            extra["truncationReason"] = "node_budget"
        }
        return envelope(
            id = id, eventId = eventId, eventType = "mobile_view_hierarchy",
            sequence = sequence, timestampMillis = timestampMillis,
            monotonicNanos = monotonicNanos, posture = posture, extra = extra,
            webView = webView,
        )
    }

    /**
 * The `mobile_visual_frame` METADATA event (schema: metadata only — bytes
 * never travel inline; they are posted to the frame endpoint and the server
 * binds them by contentHash). privacyState has exactly one producible value.
 */
    fun visualFrame(
        id: Identity,
        eventId: String,
        sequence: Long,
        timestampMillis: Long,
        monotonicNanos: Long,
        posture: Posture,
        visualFrameId: String,
        screenId: String,
        contentHash: String,
        width: Int,
        height: Int,
        byteSize: Int,
        format: String,
        quality: Int,
        downscale: Double,
    ): String {
        val extra = LinkedHashMap<String, Any?>()
        extra["screen"] = ScreenRef(screenId)
        extra["visualFrameId"] = visualFrameId
        extra["contentHash"] = contentHash
        extra["captureReason"] = FrameBudgets.CAPTURE_REASON
        extra["scope"] = FrameBudgets.SCOPE
        extra["width"] = width
        extra["height"] = height
        extra["byteSize"] = byteSize
        extra["format"] = format
        extra["compression"] = Compression(quality, downscale)
        extra["privacyState"] = FrameBudgets.PRIVACY_STATE
        return envelope(
            id = id, eventId = eventId, eventType = "mobile_visual_frame",
            sequence = sequence, timestampMillis = timestampMillis,
            monotonicNanos = monotonicNanos, posture = posture, extra = extra,
        )
    }

    /**
 * The `mobile_network_request` event (M3.3).
 *
 * EVERY FIELD COMES FROM [NetworkCaptureCore.NetworkEvent.wire], which is
 * the ONE place the v1 field set is defined. This adds only the envelope's
 * own correlation carriers — it does not restate the network contract,
 * because a second statement of a field set is a second thing to forget to
 * update.
 *
 * The screen is the one FROZEN AT REQUEST START: a request begun on screen
 * A and completed after navigation to B belongs to A.
 */
    fun networkRequest(
        id: Identity,
        eventId: String,
        sequence: Long,
        timestampMillis: Long,
        monotonicNanos: Long,
        posture: Posture,
        event: NetworkCaptureCore.NetworkEvent,
    ): String {
        val extra = LinkedHashMap<String, Any?>()
        for ((k, v) in event.wire()) {
            if (k != "eventType") extra[k] = v
        }
        event.start.screenId?.let { extra["screen"] = ScreenRef(it) }
        event.start.gestureId?.let { extra["gestureId"] = it }
        event.start.traceId?.let { extra["traceId"] = it }
        event.start.spanId?.let { extra["spanId"] = it }
        return envelope(
            id = id, eventId = eventId, eventType = "mobile_network_request",
            sequence = sequence, timestampMillis = timestampMillis,
            monotonicNanos = monotonicNanos, posture = posture, extra = extra,
        )
    }

    data class Compression(val quality: Int, val downscale: Double)
    /**
 * `mobile_interaction` — the ONE v1 interaction wire shape.
 *
 * THE FIELD SET IS THE PRIVACY BOUNDARY, so this builder takes only what
 * may be emitted. There is no parameter for a `nativeID`, a
 * `ts-position-safe` token, a declaration string, target text or value,
 * accessibility content, a `MotionEvent` or a view dump — a caller cannot
 * pass one in, which is a stronger guarantee than remembering not to.
 *
 * COORDINATES ARE NULLABLE AND OMITTED WHEN ABSENT. `json` drops nulls,
 * so a redacted interaction carries no `x`/`y` keys at all rather than
 * carrying them empty — "no position" and "position zero" must never be the
 * same bytes.
 *
 * `onRedactedTarget` describes the POSITION channel, not the text channel:
 * a control whose label stays MASKED in the hierarchy may still report
 * `false` here when its position was explicitly authorised.
 */
    fun interaction(
        id: Identity,
        eventId: String,
        sequence: Long,
        timestampMillis: Long,
        /** From ACTION_DOWN — where the user aimed and when. */
        monotonicNanos: Long,
        posture: Posture,
        screenId: String?,
        gestureId: String,
        onRedactedTarget: Boolean,
        replayX: Float?,
        replayY: Float?,
        pointerCount: Int?,
        /**
 * REQUIRED AND TYPED, NEVER DEFAULTED. This was a bare `"tap"`
 * literal written inside the builder, so the gesture class was decided
 * here — in the one place that "decides nothing" — rather than by the
 * recogniser that observed the touch. A default would reinstate exactly
 * that: a caller that forgot to pass the class would silently emit a
 * tap for a swipe, which is a false statement about what the user did
 * and is indistinguishable from the correct output.
 */
        kind: InteractionKind,
        /**
 * The envelope's OWN WebView carrier, or null.
 *
 * LAST AND DEFAULTED, like [envelope]'s own and like
 * `RnHierarchySnapshotter.observe`'s — so every caller that does not
 * name it emits bytes identical to before, and a NATIVE interaction
 * stays UNATTRIBUTED rather than becoming a WebView one.
 *
 * IT IS THE CARRIER, NOT A SEGMENT ID. `WebViewRef`'s constructor is
 * private and `of` takes an ADMITTED segment, so a bare string can
 * never reach this parameter — the row and the envelope therefore name
 * one segment that was really admitted, which is "N + S, never S
 * alone" at row level.
 *
 * WHETHER a carrier exists is the HOST's decision, injected from the
 * one binding that holds it. This function never looks one up: a
 * producer that could find its own carrier would be a second answer to
 * "which segment is live", and the two would disagree exactly once.
 */
        webView: WebViewRef? = null,
    ): String {
        val extra = LinkedHashMap<String, Any?>()
        extra["interaction"] = kind.wire
        extra["gestureId"] = gestureId
        extra["onRedactedTarget"] = onRedactedTarget
        screenId?.let { extra["screen"] = ScreenRef(it) }
        pointerCount?.let { extra["pointerCount"] = it }
        // BOTH OR NEITHER, AND BOTH FINITE. Half a coordinate is not a
        // position, and the server refuses one (`interaction_coordinate_incomplete`
        // measured) — so the shape can never be half-emitted here.
        //
        // A NON-FINITE COORDINATE EMITS NO POSITION AT ALL (decision
        // RN-T34-NONFINITE-COORDS-20261015). The finiteness test lives HERE,
        // beside the pair invariant, because "is this a position?" is a question
        // about the PAIR and this is the one place that owns it; `value`'s
        // Float arm is the dispatcher-level backstop for every other Float.
        // Omitting is what the schema permits (x/y are `number`, not nullable)
        // and leaves `null` for the keys, which `envelope` drops.
        //
        // IT IS NOT A SERVER REFUSAL, AND THAT IS MEASURED, NOT ASSUMED. The
        // deployed planner skips validation for an absent-or-null coordinate
        // (`if (v === undefined || v === null) continue`) and then sees BOTH as
        // absent, so this event is ACCEPTED and stored with NULL x/y. That is
        // the honest record — an interaction happened, its position could not be
        // computed — and it is strictly better than a stored tap at 0,0. A
        // refusal is only reachable by emitting a deliberately malformed shape
        // (exactly one coordinate), which would destroy the whole chunk and
        // every record batched with it for a condition that is unreachable
        // upstream. Reported rather than engineered around.
        //
        // Unreachable through the real path: `ReplayCoordinates.convert` refuses
        // a non-finite point, so `finalise` yields EMIT_REDACTED and no
        // coordinate is set — W9 asserts that upstream guarantee, and W11a/W11b
        // assert this one, so neither is load-bearing alone.
        if (replayX != null && replayY != null && replayX.isFinite() && replayY.isFinite()) {
            extra["x"] = replayX
            extra["y"] = replayY
        }
        return envelope(
            id = id, eventId = eventId, eventType = "mobile_interaction",
            sequence = sequence, timestampMillis = timestampMillis,
            monotonicNanos = monotonicNanos, posture = posture, extra = extra,
            webView = webView,
        )
    }

    // ------------------------------------------------------------- emission
    private fun json(m: Map<String, Any?>): String = buildString {
        append('{')
        var first = true
        for ((k, v) in m) {
            if (v == null) continue          // omit, never emit null
            if (!first) append(',')
            first = false
            append(str(k)).append(':').append(value(k, v))
        }
        append('}')
    }

    /**
 * ONLY `monotonicNanos`. The two ordering fields are NOT the same shape
 * and the difference is not cosmetic:
 *
 * - `monotonicNanos` is a real int64 (ns since a boot-stable origin, which
 * exceeds 2^53 after ~104 days of uptime), so the protocol types it as a
 * CANONICAL UNSIGNED DECIMAL STRING.
 * - `sequence` is a per-session event counter typed `integer` — a JSON
 * NUMBER. The deployed validator checks `Number.isInteger(e.sequence)`
 * and refuses a string with `sequence_invalid`.
 *
 * Emitting both as strings refused EVERY event at the deployed planner
 * while the unit suite stayed green — found only by feeding the producer's
 * real bytes through the real server.
 */
    private val INT64_AS_STRING = setOf("monotonicNanos")

    private fun value(key: String, v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean -> v.toString()
        is Int -> v.toString()
        is Long -> if (key in INT64_AS_STRING) str(v.toString()) else v.toString()
        is Posture -> json(linkedMapOf(
            "level" to v.level, "maskText" to v.maskText, "maskInputs" to v.maskInputs,
            "maskAccessibilityLabels" to v.maskAccessibilityLabels,
            "blockImages" to v.blockImages, "blockCameraSurfaces" to v.blockCameraSurfaces,
            "allowedViewCount" to v.allowedViewCount, "screenNeverRecord" to v.screenNeverRecord,
        ))
        is ScreenRef -> json(linkedMapOf(
            "screenId" to v.screenId, "kind" to v.kind,
        ))
        // Declared keys only, in schema order. `json` omits every null, so a
        // partially-known device ships exactly the facts that are known.
        is DeviceFacts -> json(linkedMapOf(
            "manufacturer" to v.manufacturer, "model" to v.model,
            "osName" to v.osName, "osVersion" to v.osVersion,
            "screenWidth" to v.screenWidth, "screenHeight" to v.screenHeight,
            "pixelRatio" to v.pixelRatio, "densityDpi" to v.densityDpi,
        ))
        is CanonicalNode -> json(linkedMapOf(
            "id" to v.id, "role" to v.role, "privacy" to v.privacy,
            // Canonical bounds keys (node.schema.json): width/height, not w/h.
            // Kept even for blocked nodes — a rectangle carries no content and
            // the renderer needs it for the labelled placeholder.
            "bounds" to v.bounds,
            // Swift parity: a masked node always carries BOTH fields, with a 0
            // length when none was measured — the pair is one fact.
            "textMasked" to v.textMasked,
            "textLength" to if (v.textMasked == true) (v.textLength ?: 0) else null,
            "blockedReason" to v.blockedReason,
            // MODEL B at node level. `json` omits every null, so a node that
            // is not a correlated web surface ships EXACTLY the bytes it
            // shipped before this field existed — which is what makes the
            // tri-state by PRESENCE the Viewer reads honest: a string means
            // attributed, ABSENT means not attributed, and there is no third
            // spelling that could be mistaken for "the server says native".
            "webViewSegmentId" to v.webViewSegmentId,
            "children" to v.children.ifEmpty { null },
        ))
        is Compression -> json(linkedMapOf(
            "quality" to v.quality, "lossless" to false, "downscale" to v.downscale,
        ))
        // FLOAT AND DOUBLE ARE DIFFERENT RUNTIME TYPES, AND THE FALLBACK
        // BELOW DOES NOT SAY SO. `540.0f is Double` is FALSE — a Kotlin Float
        // boxes to `java.lang.Float` — so before this arm existed every Float
        // fell through to `else -> str(v.toString)` and shipped as
        // `"x":"540.0"`, a QUOTED STRING. The only Floats that reach here are
        // `interaction`'s replayX/replayY, and the deployed planner refuses a
        // non-number coordinate BY DESIGN ("NEVER coerced from a string") —
        // `interaction_coordinate_invalid`, a 4xx, which this client treats as
        // TERMINAL. So the one event class the position channel exists for was
        // the only class that could never be stored, and it took its
        // chunk-mates with it.
        //
        // THAT IS THE SECOND TIME THIS FUNCTION HAS SHIPPED A NUMBER AS A
        // STRING; the INT64_AS_STRING comment above records the first. The
        // lesson is the same and is why these two arms sit together: a numeric
        // type with no arm is not a compile error, it is a silently quoted
        // wire field.
        //
        // A NON-FINITE FLOAT IS `null`, NOT `0` — decision
        // RN-T34-NONFINITE-COORDS-20261015. `0` is a POSITION: the server
        // accepts it, so a NaN
        // transform would be stored as a real tap in the top-left corner —
        // a fabricated fact, which is the one thing `ReplayCoordinates` refuses
        // to do ("it NEVER clamps … clamping would turn a wrong transform into
        // a plausible one"). `null` is what the iOS twin already renders for a
        // non-finite Double (`RnEnvelope.swift`), so there is ONE cross-platform
        // rule for a floating-point value that is not a number.
        //
        // This arm is the dispatcher-level backstop for ANY Float: `envelope`
        // takes an untyped `extra: Map<String, Any?>`, so a future Float field
        // reaches here without passing through `interaction`. The coordinate
        // PAIR is additionally refused inside `interaction` above, where the
        // both-or-neither invariant already lives — so x/y are OMITTED rather
        // than nulled, which is what `mobile_interaction.schema.json` permits
        // (it types them `number`, not `["number","null"]`).
        //
        // THE `Double` ARM IS DELIBERATELY UNCHANGED. The ruling is about
        // COORDINATES, and `replayX`/`replayY` are the only Floats that reach an
        // envelope map. Widening it would change the bytes of every other
        // numeric field (`compression.downscale`, `scale`, `velocity`,
        // `durationMs`) on a path no measurement in this lane covers. The
        // asymmetry is a known, reported divergence from the iOS twin, not an
        // oversight — `InteractionWireTypeTest` W11d pins BOTH arms so it cannot
        // drift silently in either direction.
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Double -> if (v.isFinite()) v.toString() else "0"
        is NodeBounds -> json(linkedMapOf(
            "x" to v.x, "y" to v.y, "width" to v.w, "height" to v.h,
        ))
        is List<*> -> v.joinToString(",", "[", "]") { value(key, it) }
        else -> str(v.toString())
    }

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}

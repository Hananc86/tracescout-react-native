package com.tracescout.mobile.emit

import com.tracescout.mobile.capture.incident.IncidentPolicy
import com.tracescout.mobile.core.Clock
import com.tracescout.mobile.core.SystemClock
import com.tracescout.mobile.core.WireFormat
import com.tracescout.mobile.privacy.CapturePrivacyEvidence
import com.tracescout.mobile.privacy.EmittedNode
import com.tracescout.mobile.privacy.ScreenPolicy
import com.tracescout.mobile.privacy.Posture
import java.security.SecureRandom

/**
 * Builds `mobile-1.0.0` envelopes and serialises them to the exact bytes that
 * go on the wire.
 *
 * JSON IS WRITTEN BY HAND, NOT BY `org.json`, for two reasons that both bit
 * other parts of this platform:
 *
 * 1. `org.json` is an Android framework class. Under a plain JVM unit test it
 * is a stub whose methods throw (or silently return defaults with
 * `returnDefaultValues`), so the serialiser — the one component whose
 * output IS the contract — could only ever be tested on a device. This
 * writer runs anywhere, so the wire format is covered by fast unit tests
 * AND by the instrumented ones.
 * 2. `JSONObject` iterates a HashMap: key order varies. The backend
 * content-addresses the hierarchy payload, so unstable key order would
 * produce a different sha256 for identical content and defeat the
 * idempotent-retry design (a retry would store a second copy instead of
 * being a no-op).
 *
 * Emission order here is fixed and declared, so identical state serialises to
 * identical bytes on every device and every run.
 *
 * `monotonicNanos` is written as a QUOTED DECIMAL STRING. It is an int64 and
 * JSON numbers are doubles: above 2^53 (~104 days of uptime) a numeric form
 * silently loses the low bits, which reorders events. See [WireFormat].
 */
object Envelope {

    const val PROTOCOL_VERSION = "mobile-1.0.0"
    const val PLATFORM = "android"
    const val SDK_FAMILY = "tracescout-android"

    /**
 * This binding's screen abstraction, as `screen.kind` on every envelope
 * that carries a screen identity. It sits beside [PLATFORM] and
 * [SDK_FAMILY] because it is the same KIND of fact: a per-binding
 * constant, not a per-screen measurement.
 *
 * THE GAP IT CLOSES (board finding `mobile-sdk-android-native` F-1,
 * measured by an earlier change). `envelope.schema.json` has always declared
 * `screen.kind` with a 7-value enum, the deployed ingest validates it
 * against that enum and stores it, the read path returns it
 * (`mobileReplayReadService.js` → `screenKind`, carried into the public
 * share view) — and this producer emitted the `screen` member with
 * `screenId` and NOTHING else, so the column was NULL for every native
 * session while all three sibling bindings populated theirs
 * (`RnEnvelope.kt`/`.swift` `route`, `FlutterEnvelope.kt`/`.swift`
 * `widget`, `mobile-ios` `view_controller`). The iOS binding's own
 * `ScreenIdentity` comment names this gap and says there is no reason to
 * inherit it.
 *
 * This is the `device.density` → `pixelRatio` class (see
 * `DeviceWireContractTest`): a DECLARED field that no producer test read
 * and no validator refused, feeding a server column that stayed empty.
 *
 * ## Why `activity`, and why that is not a guess
 *
 * It is what the protocol's own canonical fixtures declare for exactly
 * this producer: all **13** payloads in `mobile-protocol/__fixtures__/`
 * carry `platform: "android"` + `sdkFamily: "tracescout-android"` and
 * every one of them sets `"kind": "activity"`. The enum's other two
 * Android-shaped members are not alternatives this producer could choose
 * between on evidence — every public screen entry point here
 * (`captureScreen(root, screenId)`, `routeChanged(screenId)`,
 * `hostAttached(screenId, …)`) takes a bare caller-supplied STRING, so
 * the SDK never learns whether a screen is a Fragment or a Composable,
 * and both are hosted BY an Activity whose window is what
 * `InteractionCapture` observes. Naming the host entity is therefore the
 * one Android answer that is true in every case the producer can see.
 *
 * `unknown` was the alternative and is worse: it is the enum's "could not
 * determine" member, it would contradict all 13 canonical fixtures, and
 * it would report an absence of knowledge the producer does not actually
 * have.
 *
 * ## Deliberately NOT a parameter
 *
 * The siblings each default this value and let a caller override it. Here
 * there is nothing to override WITH — no public API carries a screen kind
 * so adding a parameter would widen this module's surface without a
 * single call site able to supply a different value, and `envelopeFields`
 * is explicitly the one function whose parameter list must not grow
 * casually (its own comment records why). A host-declared kind is a
 * separate unit with its own public API decision.
 *
 * ## `internal`, deliberately
 *
 * Nothing outside this module reads it, and nothing outside this module
 * should: the value is an implementation detail of what THIS binding puts
 * on the wire, not a contract a host app or a sibling binding may build
 * on. A public constant here would be a surface we would owe compatibility
 * for — an app could branch on it, and the enum member could then never be
 * narrowed. `internal` keeps the `:sdk` test source set's read (the wire
 * contract test asserts the emitted `kind` IS this constant, which is the
 * only thing that must agree with it) and withdraws the accidental
 * promise. The protocol's `screen.kind` enum remains the public contract;
 * this is just which member Android picks.
 */
    internal const val SCREEN_KIND = "activity"

    /**
 * The SDK version is a compile-time constant on purpose: it identifies the
 * capture code that produced the bytes, so it must not be read from a
 * mutable runtime source that could disagree with the binary.
 */
    const val SDK_VERSION = "0.2.0-internal"

    private val secureRandom = SecureRandom()
    private const val ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    /**
 * `eventId` matches the envelope's `[A-Za-z0-9_-]{8,64}` branch. It is the
 * backend's dedup identity, so a retried chunk must carry the SAME ids —
 * ids are minted once at capture time and travel with the queued record,
 * never regenerated at upload time.
 */
    fun newEventId(): String {
        val b = ByteArray(16)
        secureRandom.nextBytes(b)
        val sb = StringBuilder(26)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append(ID_ALPHABET[v % 36])
            sb.append(ID_ALPHABET[(v / 36) % 36])
        }
        return sb.toString()
    }

    /** Identity that is constant for the whole session. */
    data class SessionIdentity(
        val projectId: String,
        val sessionId: String,
        val anonymousId: String,
        val environment: String,
        val appVersion: String,
        val buildNumber: String,
        val appIdentifier: String,
        val initialScreen: String,
        val captureMode: String = "semantic",
        val device: Device? = null,
        val debugId: String? = null,
        /**
 * The IDENTIFIED USER (G2), as the host app named it through
 * `TraceScoutMobile.identify`. Null until it does — and null is
 * ANONYMOUS, which is a fact, not a gap.
 *
 * THE ONE FIELD IN THIS CLASS THAT IS NOT CONSTANT FOR THE WHOLE
 * SESSION, which is why it is the only one `start` does not read
 * natively. `appIdentifier`, `appVersion`, `buildNumber` and [device]
 * are all read from the platform precisely so a caller cannot claim to
 * be a different application, build or device. There is no
 * `Context.getUserId`: only the host app's own auth system knows who
 * is signed in, so this value MUST come from the host, and `identify`
 * is a MID-SESSION call. (The browser SDK treats `userId` as a
 * first-class caller-supplied envelope field for the same reason.)
 *
 * VALIDATED BY `com.tracescout.mobile.identity.IdentityPolicy`,
 * NEVER BY A CALL SITE. It refuses rather than truncating — a shortened
 * opaque key is a DIFFERENT user — and accepts verbatim, because the
 * browser producer writes this same `sessions.user_id` column with no
 * trim and one customer key must not be stored two ways.
 */
        val userId: String? = null,
    )

    data class Device(
        val manufacturer: String,
        val model: String,
        val osVersion: String,
        val screenWidth: Int,
        val screenHeight: Int,
        /**
 * The px→dp scale factor, under the name the PROTOCOL declares.
 *
 * THIS WAS `density` AND THAT NAME IS NOT IN THE CONTRACT.
 * `envelope.schema.json` declares `device` with
 * `additionalProperties: false` and names the scale `pixelRatio`
 * (number, 0.32) / `densityDpi` (integer) — `density` is neither, so
 * every chunk this producer ever sent carried an illegal field. It
 * survived only because the API does not schema-validate mobile events,
 * and the cost was silent: the server's `mobileSessionMeta` reads the
 * declared names to fill `sessions.device_type`, so the Device filter
 * on the Sessions list could never match an Android native session.
 * MEASURED on production: 120 of 206 stored `tracescout-android`
 * sessions carry `density`, ZERO carry either declared name, and
 * `device_type` is NULL on all 206.
 *
 * `DisplayMetrics.density` IS this quantity (px per dp), so only the
 * name changed. One concept, one name, end to end.
 */
        val pixelRatio: Double,
    )

    /**
 * One event, pre-serialised at CAPTURE time.
 *
 * The record is bytes, not an object graph, because the crash handler
 * appends to the queue while the process is dying — there is no budget
 * there to walk a tree and allocate. Everything expensive happens on the
 * capture path where it is already happening.
 */
    data class Record(
        val eventId: String,
        val eventType: String,
        val sequence: Long,
        val monotonicNanos: Long,
        val bytes: ByteArray,
    ) {
        // Kotlin data classes compare ByteArray by reference; two records with
        // the same id ARE the same record for dedup purposes, so identity is
        // pinned to the id rather than left as a reference comparison that
        // silently reports "different" for equal content.
        override fun equals(other: Any?) = other is Record && other.eventId == eventId
        override fun hashCode() = eventId.hashCode()
    }

    // -----------------------------------------------------------------------
    // Serialisation primitives
    // -----------------------------------------------------------------------

    /**
 * Escapes per RFC 8259 and additionally escapes every control character and
 * both line separators (U+2028/U+2029). The latter two are legal in JSON
 * but terminate a statement in some JS parsers — the payload is read by a
 * Node backend and a browser renderer.
 */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' ->
                    sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun str(k: String, v: String?) = if (v == null) null else "\"$k\":\"${esc(v)}\""
    private fun num(k: String, v: Long?) = if (v == null) null else "\"$k\":$v"
    private fun raw(k: String, v: String?) = if (v == null) null else "\"$k\":$v"

    private fun obj(vararg parts: String?): String =
        parts.filterNotNull().joinToString(",", "{", "}")

    /**
 * The WIRE-FACING posture projection — the eight fields
 * envelope.schema.json declares.
 *
 * This used to emit THREE (level, maskText, maskInputs). The other five
 * were absent from Android's emitted contract entirely, which is not
 * cosmetic: `screenNeverRecord` never reached the backend, so a
 * never-record screen could not be distinguished downstream, and
 * `allowedViewCount` never arrived, so "a human explicitly opted views out
 * of masking" was invisible to every read consumer.
 *
 * The internal six-field [Posture] is deliberately unchanged. These two
 * extra values are not properties a segment asserts about ITSELF — they are
 * resolved facts about the capture, so they belong at the emit boundary,
 * not forced into the internal class for test symmetry.
 */
    @JvmStatic
    fun postureProjection(
        p: Posture,
        allowedViewCount: Int,
        screenNeverRecord: Boolean,
    ): String = obj(
        str("level", p.level.wire),
        raw("maskText", p.maskText.toString()),
        raw("maskInputs", p.maskInputs.toString()),
        raw("maskAccessibilityLabels", p.maskAccessibilityLabels.toString()),
        raw("blockImages", p.blockImages.toString()),
        raw("blockCameraSurfaces", p.blockCameraSurfaces.toString()),
        // {type:"integer", minimum:0, maximum:100000}. A value outside the
        // schema contributes 0 rather than being emitted as-is — the SDK must
        // not put on the wire what its own schema would reject.
        num("allowedViewCount",
            (if (allowedViewCount in 0..100_000) allowedViewCount else 0).toLong()),
        raw("screenNeverRecord", screenNeverRecord.toString()),
    )

    /**
 * The ONE production route to a serialised posture. It takes complete
 * evidence, so there is no expression here that can omit a field: the old
 * `postureJson(p) = postureProjection(p)` silently supplied
 * allowedViewCount=0 and screenNeverRecord=false to every event ever sent.
 */
    private fun postureJson(e: CapturePrivacyEvidence): String =
        postureProjection(e.posture, e.allowedViewCount, e.screenNeverRecord)

    private fun deviceJson(d: Device): String = obj(
        str("manufacturer", d.manufacturer),
        str("model", d.model),
        str("osVersion", d.osVersion),
        num("screenWidth", d.screenWidth.toLong()),
        num("screenHeight", d.screenHeight.toLong()),
        raw("pixelRatio", d.pixelRatio.toString()),
    )

    /**
 * A node as it goes on the wire.
 *
 * MASKED NODES CARRY NO `text` KEY AT ALL — not an empty string, not a
 * placeholder. Absence is provable by a byte scan of the stored object;
 * a placeholder is indistinguishable from a real value that happened to
 * look like one, and would make the stored-byte privacy audit vacuous.
 */
    fun nodeJson(n: EmittedNode, kids: Map<Long, List<EmittedNode>>): String {
        val secure = n.privacy == "blocked" || n.privacy == "never_record"
        val masked = n.privacy == "masked"
        val children = if (secure) emptyList() else (kids[n.nodeId] ?: emptyList())
        return obj(
            // CANONICAL NAMES ONLY: `id` and `privacy`, exactly as
            // envelope.schema.json and the golden fixtures declare them.
            //
            // This used to emit `nodeId` plus BOTH `treatment` and `privacy`,
            // to satisfy an API sanitiser that read the non-canonical names.
            // That API has been corrected to consume the canonical ones and now
            // REFUSES a node it cannot identify, so `nodeId` is not merely
            // redundant — a node carrying only it is rejected outright
            // (`hierarchy_node_identity_missing`). Emitting two names for one
            // fact is also how the original mismatch hid for so long: whichever
            // reader you asked, some name agreed with you.
            num("id", n.nodeId),
            str("type", n.type),
            str("privacy", n.privacy),
            n.bounds?.let {
                raw("bounds", "{\"x\":${it.x},\"y\":${it.y},\"w\":${it.w},\"h\":${it.h}}")
            },
            if (masked) raw("textMasked", "true") else null,
            if (masked) num("textLength", (n.textLength ?: 0).toLong()) else null,
            if (secure) str("blockedReason", n.blockedReason ?: "unsupported_surface") else null,
            // `text` is written for an ALLOWED node and nowhere else. There is
            // no other branch in this file that can emit it, so a future edit
            // cannot leak masked content by forgetting a check — the property
            // simply has one writer, guarded here.
            if (n.privacy == "allowed") str("text", n.text) else null,
            if (children.isNotEmpty())
                raw("children", children.joinToString(",", "[", "]") { nodeJson(it, kids) })
            else null,
        )
    }

    /**
 * Rebuild the tree from the flat pre-order list.
 *
 * A node whose parent is missing (its parent was blocked, so the walk never
 * emitted it) is DROPPED rather than re-parented to the root: re-parenting
 * would lift a child out from under the blocked ancestor that was supposed
 * to contain it, which is precisely the disclosure the block prevents.
 */
    fun roots(nodes: List<EmittedNode>): Pair<List<EmittedNode>, Map<Long, List<EmittedNode>>> {
        val known = nodes.mapTo(HashSet()) { it.nodeId }
        val kids = HashMap<Long, MutableList<EmittedNode>>()
        val roots = ArrayList<EmittedNode>()
        for (n in nodes) {
            val p = n.parentId
            if (p == null) roots.add(n)
            else if (known.contains(p)) kids.getOrPut(p) { ArrayList() }.add(n)
            // else: orphan under a pruned ancestor — deliberately dropped.
        }
        return roots to kids
    }

    // -----------------------------------------------------------------------
    // Envelope
    // -----------------------------------------------------------------------

    /**
 * The common envelope prefix — every field the schema marks required, plus
 * the optional identity fields when known.
 *
 * Optional fields are OMITTED when unknown rather than written as null: the
 * schema treats absence as "not captured", and a null would assert we tried
 * and got nothing. Those are different facts and the renderer shows them
 * differently.
 */
    private fun envelopeFields(
        id: SessionIdentity,
        eventId: String,
        eventType: String,
        sequence: Long,
        monotonicNanos: Long,
        // COMPLETE evidence, not a bare Posture. A bare Posture is how the two
        // missing fields defaulted their way onto every wire message.
        evidence: CapturePrivacyEvidence,
        clock: Clock,
        appState: String,
        screenId: String?,
        webViewSegmentId: String?,
        traceId: String?,
        spanId: String?,
        dropped: Map<String, Int>,
        // LAST + defaulted on purpose: every other caller passes this block
        // positionally, and inserting a parameter mid-list silently rebinds
        // arguments at call sites the compiler cannot always object to.
        webViewBrowserPrivacyStatus: String? = null,
        /**
 * The envelope's WALL time, when the caller knows the instant the event
 * describes. Default `null` = read the clock NOW, which is right for
 * every event that is emitted AS it happens.
 *
 * It exists because `mobile_network_request` is emitted on
 * completion while the envelope DESCRIBES the request's start: the
 * monotonic argument was already the start and `timestamp` was still
 * `clock.wallMillis`, so one envelope carried two clocks that
 * disagreed by `durationMs` — seconds on a slow call. The protocol uses
 * the envelope pair for ordering and correlation, so the two halves
 * must describe ONE instant. Caught by review, not on a device.
 *
 * The STORED row is unaffected and always was: the server reads
 * `requestStartedWallTime`/`requestStartedMonotonicNanos` from the event
 * BODY (`mobileChunkIngestion`), and `wallClockAnchor` reads the row —
 * never the envelope.
 */
        wallMillis: Long? = null,
    ): List<String?> = listOf(
        str("eventId", eventId),
        str("eventType", eventType),
        str("protocolVersion", PROTOCOL_VERSION),
        str("platform", PLATFORM),
        str("sdkFamily", SDK_FAMILY),
        str("sdkVersion", SDK_VERSION),
        str("projectId", id.projectId),
        str("sessionId", id.sessionId),
        str("anonymousId", id.anonymousId),
        str("environment", id.environment),
        // EVERY EVENT, NOT THE OPENER — the one identity fact that breaks
        // the "only the opener needs to name the app" rule two fields below,
        // and deliberately.
        //
        // That rule holds for values that cannot change within the session.
        // `userId` is the single identity fact that CAN: `identify` is a
        // mid-session call, so an anonymous session that signs in minutes after
        // its opener must still be attributable. The deployed server says the
        // same thing from its own side — it learns the identity across the
        // WHOLE chunk rather than from its first envelope, because the envelope
        // carrying a `userId` is routinely NOT the one that opened the chunk —
        // and resolves conflicts by HIGHEST SEQUENCE.
        //
        // Stamping every event is therefore also SELF-HEALING: the winner is
        // the highest sequence the server ever sees, so one lost or quarantined
        // chunk cannot strand a session as anonymous for ever. Stamping once
        // would be cheaper and would make the identity depend on a single chunk
        // surviving.
        //
        // An anonymous session pays NO bytes for this: a null is omitted here,
        // never written as `"userId":null`. (The protocol does declare
        // `["string","null"]`, so a null would validate — but absence and a
        // null are different facts everywhere else in this envelope, and this
        // field has no reason to be the exception.)
        id.userId?.let { str("userId", it) },
        str("appVersion", id.appVersion),
        str("buildNumber", id.buildNumber),
        // THE ONE UNDECLARED FIELD THIS PRODUCER STILL EMITS, AND IT IS
        // ISOLATED HERE DELIBERATELY.
        //
        // `envelope.schema.json` declares 37 top-level properties and sets
        // `unevaluatedProperties: false` on every event schema, so an
        // undeclared field fails validation. `appIdentifier` is not among the
        // 37 — AND the DEPLOYED server reads it, from this exact key and from
        // nowhere else:
        //
        // mobileChunkIngestion.js appIdentifier: typeof e.appIdentifier ===
        // 'string' ? e.appIdentifier.slice(0,200): null
        // mobileReplayRepository.js `initial_url` is written as
        // `app://<appIdentifier>/<screen>`
        //
        // So dropping it would NULL `mobile_app_identifier` and collapse the
        // native session's `initial_url` projection — the an earlier change defect in
        // reverse: there, a producer emitted a name the contract did not
        // declare AND the server did not read, and the Device filter could
        // never match; here the server DOES read it, so silence is the
        // regression. The canonical schema and the deployed reader genuinely
        // disagree, and resolving that is a PROTOCOL decision (declare it in
        // `envelope.schema.json`, or give it a declared home) — not a
        // producer one. Widening the schema from here is exactly what an earlier change
        // forbids, so the field stays, alone, and is recorded as the one
        // remaining drift rather than quietly removed or quietly legalised.
        str("appIdentifier", id.appIdentifier),
        // `initialScreen` and the envelope-level `captureMode`/`debugId` used
        // to be stamped here. All three were undeclared, and the deployed
        // server reads NONE of them from this level:
        //
        // * the opener already passes `id.initialScreen` as this envelope's
        // `screenId`, which `envelopeFields` writes as the DECLARED
        // `screen.screenId` — and that is precisely what the ingest reads
        // (`e.screenId` ?? `e.screen.screenId`), so the fact never left
        // the wire, only its illegal duplicate did;
        // * `captureMode` is declared on `mobile_session_start` and
        // `mobile_screen_snapshot` ONLY, and every server read is gated on
        // one of those two event types, so it now rides at the EVENT level
        // where the contract puts it (see `sessionStart` below, and the
        // two snapshot builders which already did this);
        // * `debugId`'s declared home is `buildIdentity.debugId`, which is
        // what the ingest reads. Nothing in this module ever set
        // `SessionIdentity.debugId`, so that stamp was LATENT drift — it
        // could only ever have produced an illegal envelope, never a
        // useful one.
        id.device?.let { raw("device", deviceJson(it)) },
        str("appState", appState),
        // STILL GATED ON `screenId`, AND THAT IS THE POINT: a `screen`
        // object holding only a constant `kind` would name no screen at all,
        // so an event with no screen identity keeps emitting NO `screen`
        // member (pinned by `SessionEndWireTest` and `NetworkRequestEnvelopeTest`).
        // `kind` rides WITH the identity, never instead of it. See [SCREEN_KIND].
        screenId?.let { raw("screen", obj(str("screenId", it), str("kind", SCREEN_KIND))) },
        num("sequence", sequence),
        num("timestamp", wallMillis ?: clock.wallMillis()),
        // int64 as a STRING — see the class comment.
        str("monotonicNanos", WireFormat.monotonicToWire(monotonicNanos)),
        webViewSegmentId?.let { str("webViewSegmentId", it) },
        // Only ever alongside the segment it describes. A status with no
        // segment names nothing, and the server would have no row to put it on.
        webViewSegmentId?.let { _ ->
            webViewBrowserPrivacyStatus?.let { str("webViewBrowserPrivacyStatus", it) }
        },
        traceId?.let { str("traceId", it) },
        spanId?.let { str("spanId", it) },
        raw("privacyPosture", postureJson(evidence)),
        if (dropped.isEmpty()) null
        else raw("dropped", dropped.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { "\"${esc(it.key)}\":${it.value}" }),
    )

    /**
 * A full semantic keyframe.
 *
 * Takes [CapturePrivacyEvidence] rather than a Posture, and has NO default
 * for it: a hierarchy-bearing event cannot be constructed without stating
 * how many views it allows. Build it with
 * `CapturePrivacyEvidence.forHierarchy(posture, canonical, neverRecord)` and
 * pass `canonical.nodes` here, so the count and the payload come from ONE
 * ancestry-aware result and cannot describe different trees.
 */
    fun viewHierarchy(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        evidence: CapturePrivacyEvidence,
        snapshotId: String,
        nodes: List<EmittedNode>,
        nodeCount: Int,
        captureDurationMs: Long,
        degraded: Boolean,
        screenId: String?,
        appState: String = "foreground",
        dropped: Map<String, Int> = emptyMap(),
        webViewSegmentId: String? = null,
        webViewBrowserPrivacyStatus: String? = null,
        traceId: String? = null,
        spanId: String? = null,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
    ): Record {
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_view_hierarchy", sequence, monotonicNanos, evidence,
                clock, appState, screenId, webViewSegmentId, traceId, spanId, dropped,
                webViewBrowserPrivacyStatus,
            ) + listOf(
                str("snapshotId", snapshotId),
                num("rootNodeId", nodes.firstOrNull()?.nodeId ?: 0L),
                num("nodeCount", nodeCount.toLong()),
                raw("captureDurationMs", captureDurationMs.toString()),
                raw("degraded", degraded.toString()),
                str("encoding", "json"),
                raw("nodes", roots(nodes).let { (rs, kids) ->
                    rs.joinToString(",", "[", "]") { nodeJson(it, kids) }
                }),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_view_hierarchy", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    fun sessionStart(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5, a product decision): outstanding canonical
 * loss families, carried on the ALREADY-EXISTING `dropped` disclosure
 * that `envelopeFields` has always owned.
 *
 * LAST + DEFAULTED, for the reason the parameter above it records:
 * every caller passes the envelope block POSITIONALLY, so a parameter
 * inserted mid-list silently rebinds arguments.
 */
        dropped: Map<String, Int> = emptyMap(),
    ): Record {
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_session_start", sequence, monotonicNanos, CapturePrivacyEvidence.nonHierarchy(posture, false),
                clock, "foreground", id.initialScreen, null, null, null, dropped,
            ) + listOf(
                // `"app_launch"` IS NOT A LEGAL VALUE AND NEVER WAS.
                // `mobile_session_start.schema.json` declares `startReason`
                // REQUIRED with a CLOSED five-value enum — cold_start,
                // warm_start, hot_start, manual, resumed_after_process_death —
                // and `app_launch` is in none of them, so every opener this
                // producer has ever emitted failed validation on its own
                // required field.
                //
                // `manual` is the honest replacement, and it is not invented
                // here: it is what all four sibling producers already emit
                // (RN Android/iOS, Flutter Android/iOS), for the reason
                // `FlutterEnvelope.kt` states — "the public semantic operation
                // is an explicit start call and this producer has no
                // trustworthy native signal to claim cold/warm/hot — never
                // fabricate precision the producer does not actually know".
                // Measured here rather than assumed: `Envelope.sessionStart`
                // has exactly ONE call site (`TraceScoutMobile.start`, a host
                // call), and this module holds no process-state signal that
                // could separate a cold from a warm or a hot start. iOS native
                // does carry such a signal and so legitimately says
                // `warm_start` on its inactivity restart; Android does not, and
                // claiming `cold_start` would be a measurement nobody took.
                //
                // The deployed server reads `startReason` NOWHERE (0 of its
                // files), so this correction changes no stored column — it only
                // stops the opener being structurally invalid.
                str("startReason", "manual"),
                // THE SESSION'S DECLARATION, at the level the contract declares
                // it: REQUIRED on this event type, and undeclared at the
                // envelope level it used to ride. The deployed ingest reads it
                // from exactly here (`e.eventType === 'mobile_session_start' &&
                // e.captureMode`), so the stored `mobile_capture_mode` column
                // is fed identically before and after this move.
                str("captureMode", id.captureMode),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_session_start", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    /**
 * The closed `mobile_session_end.reason` enum — mirror of the schema, in the
 * same form `NAV_TRANSITIONS` already takes for `mobile_navigation`.
 *
 * **THE PROTOCOL'S SET IS WIDER THAN WHAT THIS PRODUCER CAN REACH, AND
 * THAT ASYMMETRY IS DELIBERATE.** The mirror is complete because it mirrors
 * a subset here would silently redefine the contract for whoever reads it
 * next. What a producer may REACH is a separate question, answered at the
 * emission site, and today the Android native producer reaches exactly
 * [SESSION_END_MANUAL]:
 *
 * | reason | why this producer does not emit it |
 * |---|---|
 * | `inactivity` | no idle timer exists in `:sdk` (a separate backlog unit) |
 * | `max_duration` | no max-duration timer exists in `:sdk` (same unit) |
 * | `app_terminated` | inferring an end from shutdown is exactly what `an explicit product decision` forbids. The reason is the INFERENCE, not deliverability: a record that reaches the durable queue IS delivered on the next launch by `TraceScoutMobile.recoverInherited`. Shutdown may stop an append from completing; it does not make an APPENDED record undeliverable, and an earlier version of this row claimed it did |
 * | `process_death_detected` | the session it would describe is over and this process never observed it — a claim about a death, manufactured after it |
 *
 * The last two are the ones that matter: an end event invented after the
 * fact is indistinguishable on the wire from one the host declared, so the
 * producer must be unable to write them rather than merely not write them
 * today. `TraceScoutMobile.recordSessionEnd` takes NO reason parameter, so
 * there is no argument through which either could arrive, and
 * `SessionEndWireTest` pins that the only production call site passes the
 * `manual` literal.
 */
    val SESSION_END_REASONS: Set<String> = setOf(
        "inactivity", "max_duration", "manual", "app_terminated", "process_death_detected",
    )

    /** The one reason a HOST declaration can honestly carry. See [SESSION_END_REASONS]. */
    const val SESSION_END_MANUAL: String = "manual"

    /**
 * `mobile_session_start`'s twin: the canonical end-of-session EVENT.
 *
 * **EMITTING THIS IS NOT TERMINALIZING, AND THE TWO MUST NOT BE
 * CONFLATED.** Terminality is a DURABLE LOSS-AUTHORITY CLAIM written only by
 * `TraceScoutMobile.endSessionAuthoritatively`; this is a
 * replay EVENT that says the session ended and carries how long it ran. A
 * host may do either, both, or neither, and this builder changes none of the
 * ack, durability or finality rules — it produces a [Record] exactly like
 * every other builder here and takes the ordinary durable-append path.
 *
 * **THE SESSION-END BODY IS SAFE METADATA ONLY, BY CONSTRUCTION — the
 * same property `navigation` states.** No parameter of THIS builder's own
 * body can carry text, a node, a form value or a screen name: the reason is
 * drawn from a closed enum and the two measurements are integers. That is
 * why it carries the complete posture projection and no content keys.
 *
 * **THE CLAIM IS SCOPED TO THE BODY, DELIBERATELY, AND AN EARLIER VERSION
 * WAS TOO BROAD.** It said no user-entered string could arrive at all —
 * which is not this builder's to promise: `envelopeFields` writes the
 * host-supplied `userId` from [SessionIdentity] onto EVERY event's envelope
 * (G2 `identify`), and a host may legitimately supply an end-user
 * identifier there. So a session end carries no user-entered CONTENT, and
 * the envelope around it may carry an end-user IDENTIFIER like every other
 * event's. Saying otherwise would read as a guarantee the envelope does not
 * give.
 *
 * **`durationMs` AND `eventsEmitted` ARE OMITTED WHEN UNKNOWN, never
 * written as 0.** The schema's optional fields mean "not captured" by
 * absence, and a zero would assert a session that ran no time and emitted
 * nothing — see `envelopeFields`' note on the same rule. The caller derives
 * `durationMs` from a MONOTONIC anchor, never from the wall clock, which may
 * jump backwards.
 *
 * **NO `screen`.** A session end is not about a screen; `lifecycle` makes
 * the same choice for the same reason. Naming whichever screen happened to
 * be current would attribute the end of the session to it.
 */
    fun sessionEnd(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        reason: String,
        /** Monotonic-derived session length. Omitted from the wire when null. */
        durationMs: Long? = null,
        /** Events this session emitted BEFORE this one. Omitted when null. */
        eventsEmitted: Int? = null,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5, a product decision): outstanding canonical
 * loss families, carried on the ALREADY-EXISTING `dropped` disclosure
 * that `envelopeFields` has always owned.
 *
 * LAST + DEFAULTED, for the reason `sessionStart`'s copy records:
 * every caller passes the envelope block POSITIONALLY, so a parameter
 * inserted mid-list silently rebinds arguments.
 */
        dropped: Map<String, Int> = emptyMap(),
    ): Record {
        // CLOSED AT THE BUILDER, the same way `navigation` closes its own enum
        // and this is the second of two independent checks rather than a
        // duplicate of one: the emission site has no reason PARAMETER at all,
        // so the only way an out-of-enum value could reach the wire is a future
        // call site, which is exactly what this refuses. A refusal is louder
        // than a truncation: the server's enum would reject the whole event and
        // the producer would have spent a sequence on bytes nobody can store.
        require(reason in SESSION_END_REASONS) { "unknown session end reason" }
        // The schema's `durationMs` is `minimum: 0`. A negative would be refused
        // upstream for a reason that is the device's clock, not the session.
        require(durationMs == null || durationMs >= 0L) { "negative session durationMs" }
        require(eventsEmitted == null || eventsEmitted >= 0) { "negative eventsEmitted" }
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_session_end", sequence, monotonicNanos, CapturePrivacyEvidence.nonHierarchy(posture, false),
                clock, "foreground", null, null, null, null, dropped,
            ) + listOf(
                str("reason", reason),
                num("durationMs", durationMs),
                num("eventsEmitted", eventsEmitted?.toLong()),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_session_end", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    fun lifecycle(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        transition: String,
        appState: String,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5, a product decision): outstanding canonical
 * loss families, carried on the ALREADY-EXISTING `dropped` disclosure
 * that `envelopeFields` has always owned.
 *
 * LAST + DEFAULTED, for the reason the parameter above it records:
 * every caller passes the envelope block POSITIONALLY, so a parameter
 * inserted mid-list silently rebinds arguments.
 */
        dropped: Map<String, Int> = emptyMap(),
    ): Record {
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_lifecycle", sequence, monotonicNanos, CapturePrivacyEvidence.nonHierarchy(posture, false),
                clock, appState, null, null, null, null, dropped,
            ) + listOf(str("transition", transition))).toTypedArray()
        )
        return Record(eventId, "mobile_lifecycle", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    /** The closed `mobile_navigation.transition` enum — mirror of the schema. */
    val NAV_TRANSITIONS: Set<String> = setOf(
        "push", "pop", "replace", "tab_change",
        "modal_present", "modal_dismiss", "deep_link", "initial",
    )

    /**
 * A navigation event: SAFE METADATA ONLY, by construction. The builder can
 * carry a route id, the previous route id, the transition, an optional
 * screen NAME and a deep-link path TEMPLATE — there is no parameter through
 * which a concrete deep-link value, a form field or any user-entered text
 * could arrive. `deepLinkPathTemplate` is the `/asset/:symbol` form, never
 * the concrete link (the schema documents the same rule).
 */
    fun navigation(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        transition: String,
        toScreenId: String,
        fromScreenId: String?,
        toScreenName: String? = null,
        deepLinkPathTemplate: String? = null,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5, a product decision): outstanding canonical
 * loss families, carried on the ALREADY-EXISTING `dropped` disclosure
 * that `envelopeFields` has always owned.
 *
 * LAST + DEFAULTED, for the reason the parameter above it records:
 * every caller passes the envelope block POSITIONALLY, so a parameter
 * inserted mid-list silently rebinds arguments.
 */
        dropped: Map<String, Int> = emptyMap(),
    ): Record {
        require(transition in NAV_TRANSITIONS) { "unknown navigation transition" }
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_navigation", sequence, monotonicNanos,
                CapturePrivacyEvidence.nonHierarchy(posture, false),
                clock, "foreground", null, null, null, null, dropped,
            ) + listOf(
                str("transition", transition),
                str("fromScreenId", fromScreenId),
                str("toScreenId", toScreenId),
                str("toScreenName", toScreenName),
                str("deepLinkPathTemplate", deepLinkPathTemplate),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_navigation", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    /**
 * A screen the application declared UNRECORDABLE.
 *
 * Deliberately its own event type rather than a lifecycle transition or an
 * empty hierarchy. An empty hierarchy is indistinguishable from a capture
 * that failed, and the renderer would be right to show it as a gap; this
 * says the absence was INTENDED. It carries screen identity, timing and the
 * posture (with screenNeverRecord=true) and no tree, no frame, no text.
 */
    /**
 * A screen the application declared UNRECORDABLE.
 *
 * THIS IS A `mobile_screen_snapshot`, NOT A NEW EVENT TYPE. An earlier
 * version invented `mobile_screen_never_record`; the protocol's eventType
 * enum is CLOSED and the deployed API refuses anything outside it with
 * `event_type_unknown` — every such event from a device would have been a
 * permanent 422, discovered only after building and signing an APK.
 *
 * The protocol already expresses intentional absence, in two parts that
 * together distinguish all four cases:
 *
 * intentionally never recorded: captureMode=metadata_only
 * + privacyPosture.screenNeverRecord=true
 * capture failed: degraded=true / blockedReason=capture_timeout
 * hierarchy unavailable: captureMode=metadata_only, neverRecord=false
 * visual frame unavailable: node imageState=unavailable
 *
 * `metadata_only` alone cannot say WHY the content is absent — the posture
 * flag is what separates "we chose not to" from "we could not". Both now
 * reach the wire, which is exactly what CapturePrivacyEvidence fixed.
 *
 * Carries screen identity, timing and posture. No tree, no frame, no text.
 */
    fun screenNeverRecord(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        screenId: String,
        screenName: String? = null,
        appState: String = "foreground",
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5, a product decision): outstanding canonical
 * loss families, carried on the ALREADY-EXISTING `dropped` disclosure
 * that `envelopeFields` has always owned.
 *
 * LAST + DEFAULTED, for the reason the parameter above it records:
 * every caller passes the envelope block POSITIONALLY, so a parameter
 * inserted mid-list silently rebinds arguments.
 */
        dropped: Map<String, Int> = emptyMap(),
    ): Record {
        // `captureMode` MEANS TWO DIFFERENT THINGS UNDER ONE NAME, and this
        // filter is now DEFENCE IN DEPTH rather than load-bearing.
        //
        // `mobile_session_start.captureMode` is the SESSION's declaration;
        // `mobile_screen_snapshot.captureMode` describes how THIS snapshot was
        // treated. Both are EVENT-level in the contract. `envelopeFields` used
        // to stamp the session's value on every event — undeclared there — so
        // this event emitted a literal duplicate key and org.json rejected the
        // whole document; that is why the filter exists. The envelope no longer
        // emits it, so the filter removes nothing today. It is KEPT because a
        // future re-introduction at envelope level would silently recreate the
        // duplicate, and because it cannot reach the event-level value: it is
        // applied to the ENVELOPE list before the event fields are appended.
        val envelope = envelopeFields(
            id, eventId, "mobile_screen_snapshot", sequence, monotonicNanos,
            // The ONE place screenNeverRecord=true originates in production:
            // the live ScreenPolicy, read at capture time.
            CapturePrivacyEvidence.nonHierarchy(posture, true),
            clock, appState, screenId, null, null, null, dropped,
        ).filterNot { it != null && it.trimStart().startsWith("\"captureMode\"") }
        val json = obj(
            *(envelope + listOf(
                str("snapshotId", eventId),
                str("reason", "screen_entered"),
                // The whole point: metadata, and nothing else.
                str("captureMode", "metadata_only"),
                num("wireframeNodeCount", 0),
                num("blockedNodeCount", 0),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_screen_snapshot", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    /**
 * The screen's content could NOT be captured, and the device observed
 * exactly why.
 *
 * SIBLING OF `screenNeverRecord`, AND DELIBERATELY NOT THE SAME SHAPE.
 * Never-record is an INTENTIONAL EXCLUSION and reports both hierarchy
 * counters as 0 — it walked nothing on purpose. This is a CAPTURE FAILURE
 * and must carry NO counters at all: the API detects them by OWNERSHIP, not
 * truthiness (`capture_unavailable_reason_with_counters`), so emitting them
 * as 0 is emitting them. A marker that claims the content could not be
 * captured while also reporting how many nodes it walked is not
 * interpretable, and the server is right to refuse it.
 *
 * THE TRIGGER REASON IS PRESERVED, NOT OVERWRITTEN. `reason` says why a
 * snapshot was ASKED for; `captureUnavailableReason` says why the content
 * could not be produced. Collapsing them would destroy the trigger fact in
 * order to record the failure fact.
 *
 * `screenNeverRecord` is false here BY CONSTRUCTION. Never-record
 * outranks unavailable and is decided before the surface is ever inspected,
 * so this emitter is unreachable for such a screen — and a document
 * asserting both is refused by the server.
 */
    fun screenCaptureUnavailable(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        screenId: String,
        captureUnavailableReason: String,
        /** The trigger, carried through unchanged. */
        reason: String = "screen_entered",
        appState: String = "foreground",
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5, a product decision): outstanding canonical
 * loss families, carried on the ALREADY-EXISTING `dropped` disclosure
 * that `envelopeFields` has always owned.
 *
 * LAST + DEFAULTED, for the reason the parameter above it records:
 * every caller passes the envelope block POSITIONALLY, so a parameter
 * inserted mid-list silently rebinds arguments.
 */
        dropped: Map<String, Int> = emptyMap(),
    ): Record {
        require(captureUnavailableReason in CAPTURE_UNAVAILABLE_REASONS) {
            "captureUnavailableReason is a CLOSED vocabulary"
        }
        require(screenId.isNotBlank()) { "an unavailable marker needs a real screen identity" }
        // Same envelope/event captureMode collision as screenNeverRecord, and
        // likewise DEFENCE IN DEPTH since an earlier change: the envelope no longer stamps
        // a session captureMode, so this removes nothing today and exists so a
        // re-introduction there cannot silently recreate the duplicate key.
        val envelope = envelopeFields(
            id, eventId, "mobile_screen_snapshot", sequence, monotonicNanos,
            CapturePrivacyEvidence.nonHierarchy(posture, false),
            clock, appState, screenId, null, null, null, dropped,
        ).filterNot { it != null && it.trimStart().startsWith("\"captureMode\"") }
        val json = obj(
            *(envelope + listOf(
                str("snapshotId", eventId),
                str("reason", reason),
                str("captureMode", "metadata_only"),
                str("captureUnavailableReason", captureUnavailableReason),
                // NO wireframeNodeCount. NO blockedNodeCount. See above.
            )).toTypedArray()
        )
        return Record(eventId, "mobile_screen_snapshot", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    /**
 * The `mobile_visual_frame` METADATA event (schema: metadata only — the
 * bytes are posted to the frame endpoint and bound by contentHash).
 *
 * THIS EVENT IS THE FRAME'S ONLY CLOCK. The bytes route carries no
 * capture time, so a frame whose metadata never reaches a chunk is stored
 * with a NULL `monotonic_nanos` and the replay cannot schedule it. The
 * native SDK uploaded bytes without this event until an earlier change; RN always
 * emitted it (RnEnvelope.visualFrame).
 *
 * `downscale` is the EXACT float the capturer drew through
 * (`canvas.scale(scale, scale)`), widened to double — the server publishes
 * it verbatim as the capture→frame mapping, and a rounded value would be a
 * different mapping. `scope` is always `screen` and `nodeId` is omitted
 * (absence = not node-scoped), never written as null.
 */
    fun visualFrame(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        screenId: String,
        visualFrameId: String,
        contentHash: String,
        width: Int,
        height: Int,
        byteSize: Int,
        format: String,
        quality: Int,
        downscale: Double,
        privacyState: String,
        captureReason: String,
        appState: String = "foreground",
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        dropped: Map<String, Int> = emptyMap(),
    ): Record {
        require(screenId.isNotBlank()) { "a visual frame needs a real screen identity" }
        require(privacyState in FRAME_PRIVACY_STATES) { "privacyState is a CLOSED vocabulary" }
        require(captureReason in FRAME_CAPTURE_REASONS) { "captureReason is a CLOSED vocabulary" }
        require(downscale.isFinite() && downscale > 0.0 && downscale <= 1.0) {
            "downscale must be in (0, 1] — the capturer only ever shrinks"
        }
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_visual_frame", sequence, monotonicNanos,
                CapturePrivacyEvidence.nonHierarchy(posture, false),
                clock, appState, screenId, null, null, null, dropped,
            ) + listOf(
                str("visualFrameId", visualFrameId),
                str("contentHash", contentHash),
                str("captureReason", captureReason),
                str("scope", "screen"),
                num("width", width.toLong()),
                num("height", height.toLong()),
                num("byteSize", byteSize.toLong()),
                str("format", format),
                raw("compression", obj(
                    num("quality", quality.toLong()),
                    raw("downscale", downscale.toString()),
                )),
                str("privacyState", privacyState),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_visual_frame", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    /** Closed vocabularies, copied from mobile_visual_frame.schema.json. */
    @JvmField
    val FRAME_PRIVACY_STATES = listOf("masked_before_capture", "blurred", "allowed_explicit")
    /** ONE home: the same list the capture gate (`frameCaptureDecision`) refuses against. */
    @JvmField
    val FRAME_CAPTURE_REASONS: List<String> = com.tracescout.mobile.core.CaptureReasons.ALL

    /** The CLOSED failure vocabulary, copied from the protocol schema. */
    @JvmField
    val CAPTURE_UNAVAILABLE_REASONS = listOf(
        "route_surface_not_attached",
        "route_surface_not_ready",
    )

    /**
 * A crash record.
 *
 * `stackTrace` is a stack, not a message with user content — the exception
 * MESSAGE is deliberately excluded, because application code routinely
 * interpolates values into it ("could not charge card 4111…"). Only the
 * exception CLASS and the frames go on the wire.
 */
    /**
 * One canonical `mobile_crash.frames[]` / `mobile_hang.frames[]` entry.
 *
 * `index` IS REQUIRED BY THE SCHEMA and is the frame's position in the
 * stack, so the order survives a store that does not preserve array order.
 */
    data class IncidentFrame(val index: Int, val module: String?, val symbol: String?)

    private fun framesJson(frames: List<IncidentFrame>): String? {
        if (frames.isEmpty()) return null
        return frames.joinToString(",", "[", "]") { f ->
            obj(num("index", f.index.toLong()), str("module", f.module), str("symbol", f.symbol))
        }
    }

    /**
 * `mobile_crash`, in the names the contract declares.
 *
 * **THIS USED TO EMIT `exceptionType` AND `stackFrames`, AND NEITHER
 * IS A DECLARED FIELD** (Cordova an earlier change finding F-9). `mobile_crash.schema`
 * declares `type` and `frames`, requires `errorId` + `crashClass` +
 * `fatal`, and the deployed planner reads `crashClass` / `frames[]`. So
 * every native crash this SDK ever delivered was ACCEPTED — it carried
 * `errorId`, which is the field the edge hard-rejects on — and then stored
 * UNCLASSIFIED with ZERO frames: the two things a crash report exists to
 * say were the two the server could not read.
 *
 * AND `frames` IS NOT A RENAME. The old `stackFrames` was an array of
 * STRINGS; canonical `frames[]` is an array of OBJECTS with a required
 * `index` plus `module`/`symbol`. A name-only fix would still have stored
 * nothing.
 *
 * THE FIX ALREADY EXISTED ONE FUNCTION BELOW. [hang] emits its
 * canonical `hangKind`/`blockedMs`, and its own comment records why: *"the
 * first draft emitted `durationMs` and would have had every hang-bearing
 * chunk refused at the edge. Caught by the Gate-3 production run, not by
 * any local test."* Crash never got the same correction because it is not
 * refused at the edge — it degrades silently instead, which is why it
 * survived until another producer's lane measured the stored row.
 *
 * [crashClass] is a member of the closed enum and is resolved by the
 * caller through `IncidentPolicy.androidCrashClass`, which is pinned to the
 * shared vectors. It has NO default: a default would let a call site ship
 * `unknown` without deciding, and the breakdown this field exists for would
 * silently become a count of call sites that forgot.
 */
    fun crash(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        crashClass: String,
        exceptionClass: String,
        frames: List<IncidentFrame>,
        threadName: String,
        isMainThread: Boolean,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
    ): Record {
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_crash", sequence, monotonicNanos, CapturePrivacyEvidence.nonHierarchy(posture, false),
                clock, "foreground", null, null, null, null, emptyMap(),
            ) + listOf(
                str("errorId", eventId),
                str("crashClass", crashClass),
                raw("fatal", "true"),
                str("type", IncidentPolicy.boundType(exceptionClass)),
                str("threadName", IncidentPolicy.boundThreadName(threadName)),
                raw("isMainThread", if (isMainThread) "true" else "false"),
                raw("deliveredOnNextLaunch", "true"),
                raw("frames", framesJson(frames)),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_crash", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    fun hang(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        durationMs: Long,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        /**
 * PRE-F6 GAP REMEDIATION (C5 / G5 / E5, a product decision): outstanding canonical
 * loss families, carried on the ALREADY-EXISTING `dropped` disclosure
 * that `envelopeFields` has always owned.
 *
 * LAST + DEFAULTED, for the reason the parameter above it records:
 * every caller passes the envelope block POSITIONALLY, so a parameter
 * inserted mid-list silently rebinds arguments.
 */
        dropped: Map<String, Int> = emptyMap(),
    ): Record {
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_hang", sequence, monotonicNanos, CapturePrivacyEvidence.nonHierarchy(posture, false),
                clock, "foreground", null, null, null, null, dropped,
            ) + listOf(
                // The schema REQUIRES errorId/hangKind/blockedMs, and the
                // ingestion hard-rejects an incident without errorId — the
                // first draft emitted `durationMs` and would have had every
                // hang-bearing chunk refused at the edge. Caught by the Gate-3
                // production run, not by any local test.
                str("errorId", eventId),
                str("hangKind", "anr"),
                num("blockedMs", durationMs),
                raw("recovered", "false"),
                raw("deliveredOnNextLaunch", "true"),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_hang", sequence, monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    /**
 * `mobile_interaction` — one admitted native touch/gesture row (S4, an earlier change).
 *
 * IT DECIDES NOTHING. The shape — positional or redacted, phase or none —
 * was settled by `InteractionProducer` against the shared policy; this only
 * composes the SDK's one envelope around `InteractionProducer.payloadFields`.
 * The `screen` member comes from `envelopeFields(screenId = …)`, the same
 * place every other screen-bound event gets it. Time is the GESTURE's
 * (`emission.monotonicNanos`), the sequence the queue's.
 */
    fun interaction(
        id: SessionIdentity,
        sequence: Long,
        posture: Posture,
        emission: com.tracescout.mobile.capture.interaction.InteractionProducer.Emission,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        dropped: Map<String, Int> = emptyMap(),
        // LAST + defaulted, like `webViewBrowserPrivacyStatus` below and for
        // the same reason: every existing call site passes the block above
        // positionally.
        webView: com.tracescout.mobile.capture.WebViewAttachment.Carrier? = null,
    ): Record {
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_interaction", sequence, emission.monotonicNanos,
                CapturePrivacyEvidence.nonHierarchy(posture, false),
                // THIS POSITION WAS A HARDCODED `null` (S4). The envelope has
                // always been able to carry the segment — every other
                // screen-bound event does — so an interaction landing inside a
                // WebView was unattributable for no reason but this argument.
                // The carrier was decided by `InteractionCapture.carrierFor`
                // against the hit test; this composes, it does not decide.
                clock, "foreground", emission.screenId, webView?.segmentId, null, null, dropped,
                webViewBrowserPrivacyStatus = webView?.browserPrivacyStatus,
            ) + com.tracescout.mobile.capture.interaction.InteractionProducer.payloadFields(emission))
                .toTypedArray()
        )
        return Record(eventId, "mobile_interaction", sequence, emission.monotonicNanos,
            json.toByteArray(Charsets.UTF_8))
    }

    /**
 * `mobile_network_request` — one completed NATIVE request (S6; the S5
 * WebView-network join's native half).
 *
 * IT DECIDES NOTHING. Every value was produced by the OkHttp capture
 * policy and arrives already normalized in
 * [com.tracescout.mobile.capture.network.NetworkRequestFacts], whose field
 * set has nowhere to put a URL, a query, a header, a body or an exception
 * message. This composes the SDK's one envelope around it.
 *
 * BOTH CLOCKS ARE THE POINT. `requestStartedMonotonicNanos` is the
 * authoritative placement clock and `requestStartedWallTime` is the epoch
 * reading taken at the same instant; a consumer that must place a
 * wall-clock-only observation (a browser request recorded inside a
 * WebView) on the native replay clock derives its offset from rows that
 * carry the pair, and measures the clock's drift from their SPREAD. A row
 * carrying only one of them anchors nothing.
 *
 * The ENVELOPE time is the request's START, not the completion: the row is
 * an interval and the lane already treats it as one, so starting it at the
 * end would place every slow call after the thing it caused.
 */
    fun networkRequest(
        id: SessionIdentity,
        sequence: Long,
        posture: Posture,
        facts: com.tracescout.mobile.capture.network.NetworkRequestFacts,
        screenId: String?,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
        dropped: Map<String, Int> = emptyMap(),
    ): Record {
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_network_request", sequence,
                facts.requestStartedMonotonicNanos,
                CapturePrivacyEvidence.nonHierarchy(posture, false),
                // NO WebView CARRIER, DELIBERATELY. A native request is not
                // in-page traffic, and the one consumer that reads the carrier
                // (the browser↔native merger) supersedes a native row ONLY when
                // the row ALSO declares `sourceLayer == "webview"`, which this
                // producer cannot do. Attributing a native call to a segment
                // would therefore be an attribution claim with no reader and no
                // proof — a separate decision, not part of supplying the anchor.
                clock, "foreground", screenId, null, null, null, dropped,
                // The envelope's wall time is the request's START, matching the
                // monotonic value two arguments up. Without this the pair
                // disagreed by durationMs.
                wallMillis = facts.requestStartedWallTime,
            ) + listOf(
                str("networkRequestId", facts.networkRequestId),
                num("requestStartedWallTime", facts.requestStartedWallTime),
                // int64 as a STRING — the same rule `monotonicNanos` follows.
                str("requestStartedMonotonicNanos",
                    WireFormat.monotonicToWire(facts.requestStartedMonotonicNanos)),
                num("durationMs", facts.durationMs.toLong()),
                str("method", facts.method),
                str("scheme", facts.scheme),
                str("host", facts.host),
                str("routeTemplate", facts.routeTemplate),
                facts.status?.let { num("status", it.toLong()) },
                str("outcome", facts.outcome),
                str("errorClass", facts.errorClass),
                num("requestBytes", facts.requestBytes),
                num("responseBytes", facts.responseBytes),
                // REQUIRED, and it was MISSING from the first version of this
                // builder. The schema names `sourceLayer` in its `required` set,
                // so the server would have refused every chunk this producer
                // ever sent — and a permanently refused chunk parks at the head
                // of the queue. Caught by the schema-DERIVED test rather than on
                // a device, which is the whole reason that test reads the
                // contract instead of restating it.
                str("sourceLayer", facts.sourceLayer),
                str("protocolHint", facts.protocolHint),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_network_request", sequence,
            facts.requestStartedMonotonicNanos, json.toByteArray(Charsets.UTF_8))
    }

    /** Wrap records into the chunk body the ingest endpoint accepts. */
    fun chunkBody(sessionId: String, records: List<Record>): ByteArray {
        val events = records.joinToString(",", "[", "]") { String(it.bytes, Charsets.UTF_8) }
        return "{\"sessionId\":\"${esc(sessionId)}\",\"events\":$events}"
            .toByteArray(Charsets.UTF_8)
    }
}

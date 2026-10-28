package com.tracescout.mobile.emit

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
    )

    data class Device(
        val manufacturer: String,
        val model: String,
        val osVersion: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val density: Double,
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
        raw("density", d.density.toString()),
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
        str("appVersion", id.appVersion),
        str("buildNumber", id.buildNumber),
        str("appIdentifier", id.appIdentifier),
        str("initialScreen", id.initialScreen),
        str("captureMode", id.captureMode),
        id.debugId?.let { str("debugId", it) },
        id.device?.let { raw("device", deviceJson(it)) },
        str("appState", appState),
        screenId?.let { raw("screen", obj(str("screenId", it))) },
        num("sequence", sequence),
        num("timestamp", clock.wallMillis()),
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
                str("startReason", "app_launch"),
            )).toTypedArray()
        )
        return Record(eventId, "mobile_session_start", sequence, monotonicNanos,
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
        // `captureMode` EXISTS AT BOTH LEVELS AND THEY COLLIDE BY NAME.
        // envelopeFields emits the SESSION's captureMode (an SDK envelope field
        // the protocol envelope does not declare); mobile_screen_snapshot
        // declares its OWN per-snapshot captureMode. Emitting both produced a
        // literal duplicate key, and org.json rejects the document outright.
        // The EVENT-level value is the one that means anything here, so the
        // envelope's is dropped for this event rather than shadowed.
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
        // Same envelope/event captureMode collision as screenNeverRecord: the
        // EVENT-level value is the one that means anything, so the envelope's
        // is dropped rather than shadowed.
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
    fun crash(
        id: SessionIdentity,
        sequence: Long,
        monotonicNanos: Long,
        posture: Posture,
        exceptionClass: String,
        frames: List<String>,
        threadName: String,
        eventId: String = newEventId(),
        clock: Clock = SystemClock,
    ): Record {
        val json = obj(
            *(envelopeFields(
                id, eventId, "mobile_crash", sequence, monotonicNanos, CapturePrivacyEvidence.nonHierarchy(posture, false),
                clock, "foreground", null, null, null, null, emptyMap(),
            ) + listOf(
                str("errorId", eventId),
                str("exceptionType", exceptionClass),
                str("threadName", threadName),
                raw("fatal", "true"),
                raw("deliveredOnNextLaunch", "true"),
                raw("stackFrames", frames.take(200)
                    .joinToString(",", "[", "]") { "\"${esc(it)}\"" }),
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

    /** Wrap records into the chunk body the ingest endpoint accepts. */
    fun chunkBody(sessionId: String, records: List<Record>): ByteArray {
        val events = records.joinToString(",", "[", "]") { String(it.bytes, Charsets.UTF_8) }
        return "{\"sessionId\":\"${esc(sessionId)}\",\"events\":$events}"
            .toByteArray(Charsets.UTF_8)
    }
}

import Foundation

/// Envelope assembly for the React Native producer on iOS.
///
/// A vendored restatement of the SAME wire contract the certified Android RN
/// producer emits (SDK PR #94) — not a second protocol. Anything that differs
/// here is a defect, and the arbiter is the canonical JSON Schema plus the
/// deployed planner, exercised on REAL emitted bytes by the on-host wire gate.
public enum RnEnvelope {

    /// The complete eight-field posture. Order is the documentation.
    public struct Posture {
        public let level: String
        public let maskText: Bool
        public let maskInputs: Bool
        public let maskAccessibilityLabels: Bool
        public let blockImages: Bool
        public let blockCameraSurfaces: Bool
        public let allowedViewCount: Int
        public let screenNeverRecord: Bool

        public init(level: String = "strict",
                    maskText: Bool = true,
                    maskInputs: Bool = true,
                    maskAccessibilityLabels: Bool = true,
                    blockImages: Bool = true,
                    blockCameraSurfaces: Bool = true,
                    allowedViewCount: Int,
                    screenNeverRecord: Bool) {
            self.level = level
            self.maskText = maskText
            self.maskInputs = maskInputs
            self.maskAccessibilityLabels = maskAccessibilityLabels
            self.blockImages = blockImages
            self.blockCameraSurfaces = blockCameraSurfaces
            self.allowedViewCount = allowedViewCount
            self.screenNeverRecord = screenNeverRecord
        }
    }

    /// Canonical envelope screen identity — the protocol's nested `screen`
    /// object. Typed NARROWLY on purpose: a generic dictionary would let a
    /// caller smuggle undeclared keys past `unevaluatedProperties:false`, and
    /// on Android the equivalent shortcut shipped a quoted `toString`.
    /// RN route identities are `kind:"route"` per the canonical enum.
    public struct ScreenRef {
        public let screenId: String
        public let kind: String
        public init(screenId: String, kind: String = "route") {
            self.screenId = screenId
            self.kind = kind
        }
    }

    public struct Identity {
        public let projectId: String
        public let sessionId: String
        public let anonymousId: String
        public let environment: String
        /// The HOST APPLICATION's bundle identifier, read NATIVELY from
        /// `Bundle.main.bundleIdentifier`. Without it the server cannot
        /// ORIGINATE the session and every chunk dies `404 session_not_found`
        /// (proven on Android). It lives on Identity, never on a bridge
        /// parameter — a JS-settable value would let a producer claim to be a
        /// different application.
        public let appIdentifier: String
        /// The route the session opened on. Native, from the first record.
        public let initialScreen: String?
        /// The IDENTIFIED USER (G2), as the host app named it through
        /// `identify`. Nil until it does — and nil is ANONYMOUS, which is a
        /// fact, not a gap.
        ///
        /// THE ONE IDENTITY FACT THE SDK CANNOT ESTABLISH ITSELF, which is
        /// why it is settable at all. `appIdentifier` is read natively
        /// precisely so a JS value cannot claim to be a different application.
        /// There is no native source for a user id: only the host app's own
        /// auth system knows who is signed in, so this value MUST come from the
        /// host and the native-only rule above does not reach it. (The browser
        /// SDK treats `userId` as a first-class caller-supplied envelope field
        /// for exactly the same reason.)
        ///
        /// VALIDATED BY `IdentityPolicy`, NEVER BY A CALL SITE. It refuses
        /// rather than truncating — a shortened opaque key is a DIFFERENT user
        /// and accepts verbatim, because the browser producer writes this
        /// same column with no trim and one customer key must not be stored
        /// two ways.
        public let userId: String?

        public init(projectId: String, sessionId: String, anonymousId: String,
                    environment: String, appIdentifier: String,
                    initialScreen: String? = nil,
                    userId: String? = nil) {
            self.projectId = projectId
            self.sessionId = sessionId
            self.anonymousId = anonymousId
            self.environment = environment
            self.appIdentifier = appIdentifier
            self.initialScreen = initialScreen
            self.userId = userId
        }
    }

    /// A wire value. Closed by design: every representable case has an exact
    /// canonical serialisation, so there is no `default` branch that could
    /// stringify an unexpected type onto the wire.
    public enum Value {
        case string(String)
        case bool(Bool)
        case int(Int)
        /// int64 emitted as a CANONICAL DECIMAL STRING.
        case int64AsString(Int64)
        case screen(ScreenRef)
        case posture(Posture)
        /// Pre-serialized JSON emitted verbatim. INTERNAL ONLY — the sole
        /// producer is the hierarchy serializer below, whose bytes come from
        /// `CanonicalHierarchy` output, never from a caller-supplied string.
        case rawJson(String)
        /// Frame compression descriptor — the schema's bounded object.
        case compression(quality: Int, downscale: Double)
        /// A JSON number. Used for interaction coordinates, which the schema
        /// declares as `number` in LOGICAL VIEWPORT points — never device
        /// pixels, and never a string.
        case double(Double)
        /// The session's resolved capture-fidelity policy. Four keys, all
        /// derived from ONE `ResolvedFidelityPolicy`; the schema pins
        /// `additionalProperties: false`. Carries no user content.
        case fidelityPolicy(FidelityPolicyEvidence)
        /// A nested JSON object of scalar members. Used for `device`, whose
        /// `osName`/`osVersion` the protocol already declares — the Android
        /// fixtures put the OS version there, so a top-level one would be a
        /// second representation of a fact that already has a home.
        indirect case object([(String, Value)])
        /// A JSON array, rendered through the same `value` as everything
        /// else so a member cannot escape the quoting rules the top level
        /// obeys.
        ///
        /// ADDED FOR `frames[]`, AND TYPED RATHER THAN `rawJson`.
        /// `rawJson` is documented as INTERNAL ONLY with one producer (the
        /// hierarchy serializer, whose bytes come from `CanonicalHierarchy`);
        /// routing a second, caller-shaped structure through it would make that
        /// sentence false and put string assembly back in a caller's hands.
        indirect case array([Value])
    }

    /// MODEL B — the native↔WebView correlation carrier. The SWIFT TWIN of
    /// Kotlin's `RnEnvelope.WebViewRef`.
    ///
    /// A TYPED DOOR WITH ONE CONSTRUCTION SITE, NOT TWO LOOSE STRINGS. The
    /// only way to obtain one is from an ADMITTED `RnWebViewSegment.Segment`,
    /// so a caller cannot put a segment id on the wire for a message the
    /// decision refused — and `browserPrivacyStatus` cannot travel without the
    /// segment it describes. The protocol's conformance suite states the
    /// pairing (*"the browser privacy status is meaningful only alongside a
    /// `webViewSegmentId`"*); carrying it alone would put a field on the wire
    /// describing a correlation that does not exist.
    ///
    /// `init` is private, so `of(_:)` is the only entry. A source pin asserts
    /// there is exactly ONE construction site.
    public struct WebViewRef: Equatable {
        public let segmentId: String
        public let browserPrivacyStatus: String?

        private init(segmentId: String, browserPrivacyStatus: String?) {
            self.segmentId = segmentId
            self.browserPrivacyStatus = browserPrivacyStatus
        }

        public static func of(_ segment: RnWebViewSegment.Segment) -> WebViewRef {
            WebViewRef(segmentId: segment.segmentId,
                       browserPrivacyStatus: segment.browserPrivacyStatus)
        }
    }

    /// The two keys only the `WebViewRef` door may write. Named here so the
    /// source pin and the filter read ONE list.
    static let NO_EXTRA_WEBVIEW_KEYS: Set<String> = [
        "webViewSegmentId", "webViewBrowserPrivacyStatus",
    ]

    /// int64 values are emitted as STRINGS. JSON numbers are doubles: above
    /// 2^53 a numeric form silently loses the low bits, which reorders events.
    /// `sequence` is typed `integer` by the protocol and the deployed validator
    /// checks `Number.isInteger` — emitting BOTH as strings refused every event
    /// on Android while the unit suite stayed green.
    public static func envelope(id: Identity,
                                eventId: String,
                                eventType: String,
                                sequence: Int64,
                                timestampMillis: Int64,
                                monotonicNanos: Int64,
                                posture: Posture,
                                extra: [(String, Value)] = [],
                                /// MODEL B. Present on EVERY event of a hybrid
                                /// session, not only the opener — the protocol
                                /// accepts the envelope-level segment id on any
                                /// event (`conformance.test.mjs`: *"envelope-level
                                /// segment id must be accepted on any event"*),
                                /// and the server derives `sessionKind = hybrid`
                                /// from ANY event carrying one. It is
                                /// deliberately NOT under the opener-only rule
                                /// that `appIdentifier`/`screen` follow: a
                                /// WebView is entered mid-session, and a reload
                                /// mints a NEW segment, so a value fixed at the
                                /// opener could not describe either.
                                ///
                                /// LAST AND DEFAULTED, like the interaction
                                /// policy's `positionTreatment` and for the same
                                /// reason: a caller that has not been taught
                                /// about WebView correlation must get the
                                /// pre-existing answer — no segment — never a
                                /// silently widened one.
                                webView: WebViewRef? = nil) -> String {
        var fields: [(String, Value)] = []
        fields.append(("eventId", .string(eventId)))
        fields.append(("eventType", .string(eventType)))
        fields.append(("protocolVersion", .string(Producer.PROTOCOL_VERSION)))
        fields.append(("platform", .string(Producer.PLATFORM)))
        fields.append(("hostPlatform", .string(Producer.HOST_PLATFORM_IOS)))
        fields.append(("sdkFamily", .string(Producer.SDK_FAMILY)))
        fields.append(("sdkVersion", .string(Producer.SDK_VERSION)))
        fields.append(("projectId", .string(id.projectId)))
        fields.append(("sessionId", .string(id.sessionId)))
        fields.append(("anonymousId", .string(id.anonymousId)))
        fields.append(("environment", .string(id.environment)))
        // EVERY EVENT, NOT THE OPENER — the one identity fact that breaks
        // the opener-only rule below, and deliberately.
        //
        // That rule holds for values that cannot change within the session.
        // `userId` is the single identity fact that CAN: `identify` is a
        // mid-session call, so an anonymous session that signs in minutes after
        // its opener must still be attributable. The deployed server says the
        // same thing from its own side — it learns the identity "across the
        // WHOLE chunk rather than from its first envelope", because "the
        // envelope that carries a userId is routinely NOT the one that opened
        // the chunk" — and resolves conflicts by HIGHEST SEQUENCE.
        //
        // Stamping every event is therefore also SELF-HEALING: the winner is
        // the highest sequence the server ever sees, so one lost or quarantined
        // chunk cannot strand a session as anonymous forever.
        //
        // An anonymous session appends nothing, so it pays no bytes for this.
        if let userId = id.userId {
            fields.append(("userId", .string(userId)))
        }
        if eventType == "mobile_session_start" {
            // Only the opener names the app — the server reads identity from
            // the `mobile_session_start` event alone.
            fields.append(("appIdentifier", .string(id.appIdentifier)))
            // Canonical carrier for the opening route is the envelope `screen`
            // object. A flat top-level `screenId` is a legacy alias no schema
            // declares, and the server's mobile_screens writer never reads it.
            // The bridge's `extra` overrides this when it knows a fresher route.
            if let initial = id.initialScreen {
                fields.append(("screen", .screen(ScreenRef(screenId: initial))))
            }
        }
        fields.append(("sequence", .int(Int(sequence))))       // JSON integer
        fields.append(("timestamp", .int(Int(timestampMillis)))) // ms fits 2^53
        fields.append(("monotonicNanos", .int64AsString(monotonicNanos)))
        fields.append(("privacyPosture", .posture(posture)))
        var seen = Set(fields.map { $0.0 })
        // THE `extra` MAP MAY NOT WRITE A CORRELATION KEY. The typed
        // `WebViewRef` door exists so a segment id on the wire always came from
        // an ADMITTED decision; a caller could otherwise reach the same two
        // keys through `extra` and bypass it entirely. DROPPED, not forwarded:
        // forwarding a page-chosen value could reach a wire the deployed API
        // refuses PERMANENTLY, which quarantines the whole chunk — so dropping
        // is the only direction that cannot destroy records. A source pin reads
        // this filter and `NO_EXTRA_WEBVIEW_KEYS` as one fact.
        for (k, v) in extra where !NO_EXTRA_WEBVIEW_KEYS.contains(k) {
            if let existing = fields.firstIndex(where: { $0.0 == k }) {
                fields[existing] = (k, v)     // override, never duplicate a key
            } else {
                fields.append((k, v))
                seen.insert(k)
            }
        }
        if let wv = webView {
            fields.append(("webViewSegmentId", .string(wv.segmentId)))
            // Absent, never null — `Value` has no null case, so an unreported
            // status can only be an omitted key.
            if let s = wv.browserPrivacyStatus {
                fields.append(("webViewBrowserPrivacyStatus", .string(s)))
            }
        }
        return json(fields)
    }

    /// Serialize one nested canonical node — the wire shape the deployed
    /// sanitiser consumes: `id` + `privacy` (canonical names ONLY; the server
    /// refuses a node it cannot identify), bounds retained even for blocked
    /// nodes (a rectangle carries no content; the renderer needs it for the
    /// labelled placeholder), `text` ABSENT everywhere — this producer has no
    /// allowed-text path at all, so the leak is unrepresentable, not guarded.
    static func nodeJson(_ n: CanonicalNode) -> String {
        var fields: [(String, Value)] = [
            ("id", .string(n.id)),
            ("role", .string(n.role)),
            ("privacy", .string(n.privacy)),
        ]
        if let b = n.bounds {
            // Canonical bounds keys (node.schema.json): width/height, not w/h.
            // The deployed sanitiser accepts both; the two RN producers emit
            // the SAME canonical shape so parity is byte-comparable.
            fields.append(("bounds", .rawJson(
                "{\"x\":\(b.x),\"y\":\(b.y),\"width\":\(b.w),\"height\":\(b.h)}")))
        }
        if n.textMasked == true {
            fields.append(("textMasked", .bool(true)))
            fields.append(("textLength", .int(n.textLength ?? 0)))
        }
        if let r = n.blockedReason { fields.append(("blockedReason", .string(r))) }
        // MODEL B at node level. OMITTED when nil, so a node that is not a
        // correlated web surface ships EXACTLY the bytes it shipped before
        // this field existed — which is what makes the tri-state by PRESENCE
        // the Viewer reads honest: a string means attributed, ABSENT means not
        // attributed, and there is no third spelling.
        //
        // EMITTED BEFORE `children`, matching the Kotlin twin's declared
        // key order. The two producers are compared BYTE-FOR-BYTE by
        // `parity-vectors/hierarchy-vectors.json`, so key order is part of the
        // contract and not a formatting preference.
        if let w = n.webViewSegmentId {
            fields.append(("webViewSegmentId", .string(w)))
        }
        if !n.children.isEmpty {
            fields.append(("children", .rawJson(
                "[" + n.children.map(nodeJson).joined(separator: ",") + "]")))
        }
        return json(fields)
    }

    /// The `mobile_view_hierarchy` event. Its posture carries THIS EVENT\'s
    /// derived allowedViewCount — the planner validates the scalar against the
    /// distinct allowed identities in the very nodes attached here, so both
    /// come from the same `HierarchyResult` and cannot disagree.
    /// The `mobile_network_request` event (M3.3) — the SWIFT TWIN of Kotlin's
    /// `RnEnvelope.networkRequest`, pinned to it byte-for-byte by
    /// `parity-vectors/network-envelope-vectors.json`.
    ///
    /// EVERY EVENT FIELD COMES FROM `NetworkCaptureCore.NetworkEvent.wire`,
    /// which is the ONE place the v1 field set is defined. This adds only the
    /// envelope's own correlation carriers — it does not restate the network
    /// contract, because a second statement of a field set is a second thing to
    /// forget to update. The parity vectors and the JSON Schema are the two
    /// authorities; this function is deliberately almost empty.
    ///
    /// AN ABSENT OPTIONAL IS OMITTED, NEVER `null`. That is not a convention
    /// here, it is structural: `Value` has no null case, so `wire` can only
    /// append keys it actually has, and `json` serialises what it is given.
    ///
    /// The screen is the one FROZEN AT REQUEST START: a request begun on screen
    /// A and completed after navigating to B belongs to A.
    ///
    /// THIS COMMENT USED TO SAY "iOS v1 installs no start hook, so
    /// `screenId` is nil and the key is correctly ABSENT". That stopped being
    /// true one line below it: `NetworkScreenTimeline` (fed from
    /// `TraceScoutRnModule.setNavRoute` → `RnTransport.noteScreen`, read back
    /// through `screenAtMonotonic` at the request's PROJECTED start) now
    /// resolves the screen, so a request that began while a screen was current
    /// DOES carry it. A nil is still an OMITTED key and still never a
    /// fabricated value — the timeline fails closed — but it now means "the
    /// timeline could not answer for that instant", not "iOS cannot ask".
    /// Driven end to end over a real `URLSession` request by
    /// `NetworkScreenAttributionEndToEndTests`.
    public static func networkRequest(id: Identity,
                                      eventId: String,
                                      sequence: Int64,
                                      timestampMillis: Int64,
                                      monotonicNanos: Int64,
                                      posture: Posture,
                                      event: NetworkCaptureCore.NetworkEvent) -> String {
        var extra: [(String, Value)] = []
        for (k, v) in event.wire() where k != "eventType" { extra.append((k, v)) }
        if let s = event.start.screenId { extra.append(("screen", .screen(ScreenRef(screenId: s)))) }
        return envelope(id: id, eventId: eventId, eventType: "mobile_network_request",
                        sequence: sequence, timestampMillis: timestampMillis,
                        monotonicNanos: monotonicNanos, posture: posture, extra: extra)
    }

    public static func viewHierarchy(id: Identity,
                                     eventId: String,
                                     sequence: Int64,
                                     timestampMillis: Int64,
                                     monotonicNanos: Int64,
                                     posture: Posture,
                                     snapshotId: String,
                                     screenId: String,
                                     result: HierarchyResult,
                                     captureDurationMs: Int64,
                                     /// MODEL B. Last and defaulted; see
                                     /// `envelope(…)`. The hierarchy event is
                                     /// where the honest-absence node for the
                                     /// WebView container lives, so it is the
                                     /// one event where the segment and the
                                     /// blocked node travel together.
                                     webView: WebViewRef? = nil) -> String {
        let nodesJson = "[" + result.nodes.map(nodeJson).joined(separator: ",") + "]"
        var extra: [(String, Value)] = [
            ("screen", .screen(ScreenRef(screenId: screenId))),
            ("snapshotId", .string(snapshotId)),
            ("nodeCount", .int(result.totalNodes)),
            ("captureDurationMs", .int(Int(captureDurationMs))),
            ("degraded", .bool(false)),
            ("encoding", .string("json")),
            ("nodes", .rawJson(nodesJson)),
        ]
        if result.truncated {
            extra.append(("truncated", .bool(true)))
            extra.append(("truncationReason", .string("node_budget")))
        }
        return envelope(id: id, eventId: eventId, eventType: "mobile_view_hierarchy",
                        sequence: sequence, timestampMillis: timestampMillis,
                        monotonicNanos: monotonicNanos, posture: posture, extra: extra,
                        webView: webView)
    }

    /// The `mobile_visual_frame` METADATA event (schema: metadata only — bytes
    /// never travel inline; they are posted to the frame endpoint and the
    /// server binds them by contentHash). privacyState has exactly one
    /// producible value. Byte-parallel to the Kotlin builder.
    /// The `mobile_interaction` event (interaction-v1).
    ///
    /// THE EXISTING WIRE, NOT A NEW ONE. This mirrors the Kotlin
    /// `RnEnvelope.interaction` field-for-field; there is no `mobile_interaction_v2`,
    /// no `ios_interaction` and no `native_touch_event`. Every field here is
    /// declared in `mobile_interaction.schema.json`.
    ///
    /// THE CALLER MAY ONLY PASS WHAT THE POLICY ALLOWED. `x`/`y` are
    /// non-nil only for `emit_positional`; this builder does not re-decide, but
    /// it does enforce the shape the server requires.
    ///
    /// ONE DIFFERENCE FROM THE KOTLIN TWIN, DELIBERATE AND DISCLOSED: the
    /// shared policy lists `phase` in BOTH its positional and redacted field
    /// sets, and the schema declares it (`begin|move|end|cancel|instant`), but
    /// the Kotlin builder does not currently emit it. iOS emits it because the
    /// contract says the outcome carries it. That is an Android gap to close,
    /// not an iOS-only semantic — the POLICY is unchanged either way.
    public static func interaction(id: Identity,
                                   eventId: String,
                                   sequence: Int64,
                                   timestampMillis: Int64,
                                   /// The SAME monotonic helper visual frames
                                   /// use — one boot-relative domain, so a tap
                                   /// and the frame that shows its result are
                                   /// directly comparable.
                                   monotonicNanos: Int64,
                                   posture: Posture,
                                   screenId: String?,
                                   interaction: String,
                                   phase: String,
                                   gestureId: String,
                                   onRedactedTarget: Bool,
                                   replayX: Double?,
                                   replayY: Double?,
                                   pointerCount: Int?) -> String {
        envelope(id: id, eventId: eventId, eventType: "mobile_interaction",
                 sequence: sequence, timestampMillis: timestampMillis,
                 monotonicNanos: monotonicNanos, posture: posture,
                 extra: interactionFields(screenId: screenId, interaction: interaction,
                                          phase: phase, gestureId: gestureId,
                                          onRedactedTarget: onRedactedTarget,
                                          replayX: replayX, replayY: replayY,
                                          pointerCount: pointerCount))
    }

    /// ONE definition of the interaction body. The bridge's durable path and
    /// this whole-string builder both go through it, because a second copy is
    /// how the both-or-neither coordinate rule below would end up enforced on
    /// one path and quietly absent from the other -- and the path that lost it
    /// would still look correct in every test written against the other.
    public static func interactionFields(screenId: String?,
                                         interaction: String,
                                         phase: String,
                                         gestureId: String,
                                         onRedactedTarget: Bool,
                                         replayX: Double?,
                                         replayY: Double?,
                                         pointerCount: Int?) -> [(String, Value)] {
        var extra: [(String, Value)] = [
            ("interaction", .string(interaction)),
            ("phase", .string(phase)),
            ("gestureId", .string(gestureId)),
            ("onRedactedTarget", .bool(onRedactedTarget)),
        ]
        if let s = screenId { extra.append(("screen", .screen(ScreenRef(screenId: s)))) }
        if let p = pointerCount { extra.append(("pointerCount", .int(p))) }
        // BOTH OR NEITHER. Half a coordinate is not a position, the server
        // refuses one, and emitting a lone axis would leak a partial location
        // for a target the policy may have redacted. The shape cannot be
        // half-emitted from here.
        if let x = replayX, let y = replayY, x.isFinite, y.isFinite {
            extra.append(("x", .double(x)))
            extra.append(("y", .double(y)))
        }
        return extra
    }

    public static func visualFrame(id: Identity, eventId: String, sequence: Int64,
                                   timestampMillis: Int64, monotonicNanos: Int64,
                                   posture: Posture, visualFrameId: String,
                                   screenId: String, contentHash: String,
                                   width: Int, height: Int, byteSize: Int,
                                   format: String, quality: Int, downscale: Double) -> String {
        let extra: [(String, Value)] = [
            ("screen", .screen(ScreenRef(screenId: screenId))),
            ("visualFrameId", .string(visualFrameId)),
            ("contentHash", .string(contentHash)),
            ("captureReason", .string(FrameBudgets.captureReason)),
            ("scope", .string(FrameBudgets.scope)),
            ("width", .int(width)),
            ("height", .int(height)),
            ("byteSize", .int(byteSize)),
            ("format", .string(format)),
            ("compression", .compression(quality: quality, downscale: downscale)),
            ("privacyState", .string(FrameBudgets.privacyState)),
        ]
        return envelope(id: id, eventId: eventId, eventType: "mobile_visual_frame",
                        sequence: sequence, timestampMillis: timestampMillis,
                        monotonicNanos: monotonicNanos, posture: posture, extra: extra)
    }

    // ------------------------------------------------------------- emission
    /// One `frames[]` entry of a `mobile_crash` / `mobile_hang` event.
    ///
    /// NO `address`, AND NO `message` ANYWHERE ON THE EVENT. The schema
    /// declares both `frames[].address` and a top-level `message`; this
    /// producer emits neither. A raw address is only useful with the exact
    /// binary it came from and is a pointer into the process the privacy model
    /// says nothing about; the message exclusion is inherited from the Android
    /// native producer's own stated rule — "application code interpolates
    /// values into messages, and a crash report is not an exemption from the
    /// privacy model". `symbol` is the developer's own identifier, which is why
    /// it is the one string the contract bounds rather than forbids.
    public struct IncidentFrame {
        public let index: Int
        public let module: String?
        public let symbol: String?
        /// The symbolication artifact this stack resolves against, on frame 0
        /// ONLY — the DEPLOYED planner reads `frames[0].artifactKind` and
        /// `frames[0].artifactIdentifier` and nothing else.
        ///
        /// A VALUE OUTSIDE THE SERVER'S VOCABULARY IS A PERMANENT 422 ON THE
        /// WHOLE CHUNK (`incident_artifact_kind_invalid`), taking whatever was
        /// batched with it. Absence is legal — symbolication is optional.
        public let artifactKind: String?
        public let artifactIdentifier: String?

        public init(index: Int, module: String? = nil, symbol: String? = nil,
                    artifactKind: String? = nil, artifactIdentifier: String? = nil) {
            self.index = index
            self.module = module
            self.symbol = symbol
            self.artifactKind = artifactKind
            self.artifactIdentifier = artifactIdentifier
        }

        /// Declared keys only, in schema order.
        ///
        /// AN ABSENT KEY IS OMITTED, NEVER EMITTED AS NULL — and on this
        /// platform that has to be done HERE. The Swift `json` renders every
        /// pair it is given (the Kotlin twin's `json` skips nulls for the
        /// whole map), so a Swift builder that appended an optional
        /// unconditionally would ship `"module":null` where Kotlin ships
        /// nothing — a byte divergence between twins on the same contract.
        var fields: [(String, Value)] {
            var f: [(String, Value)] = [("index", .int(index))]
            if let m = module { f.append(("module", .string(m))) }
            if let s = symbol { f.append(("symbol", .string(s))) }
            if let k = artifactKind { f.append(("artifactKind", .string(k))) }
            if let i = artifactIdentifier { f.append(("artifactIdentifier", .string(i))) }
            return f
        }
    }

    /// The `mobile_crash` event. Byte-parallel to the Kotlin `RnEnvelope.crash`.
    ///
    /// THE FIELD NAMES ARE THE CONTRACT'S, NOT THE ANDROID NATIVE PRODUCER'S,
    /// AND THE DIFFERENCE IS MEASURED. Native emits `exceptionType` and
    /// `stackFrames` and no `crashClass`, while the deployed planner reads
    /// `crashClass` and `frames` — so all 13 production crash rows carry
    /// `incident_class = NULL` and `frame_count = 0`, every crash marker in the
    /// Viewer renders "Crash: unknown class", and `frames[0].artifactKind` can
    /// never be read so the stack has no artifact to symbolicate against.
    ///
    /// `deliveredOnNextLaunch` IS ALWAYS TRUE FOR A FATAL CRASH — a statement
    /// about this record's ONLY possible delivery path, not a guess. The
    /// protocol declares the field precisely so a reader does not mistake that
    /// gap for clock skew.
    public static func crash(id: Identity,
                             eventId: String,
                             sequence: Int64,
                             timestampMillis: Int64,
                             monotonicNanos: Int64,
                             posture: Posture,
                             /// REQUIRED and bounded. The DEPLOYED planner
                             /// refuses absent or over-long with
                             /// `incident_identity_missing` / `_invalid`, a
                             /// PERMANENT 422 on the whole chunk.
                             errorId: String,
                             /// A member of `IncidentPolicy.crashClasses` —
                             /// classified by the policy, never here.
                             crashClass: String,
                             exceptionType: String?,
                             threadName: String?,
                             isMainThread: Bool?,
                             frames: [IncidentFrame],
                             screenId: String?,
                             fatal: Bool = true) -> String {
        var extra: [(String, Value)] = [
            ("errorId", .string(errorId)),
            ("crashClass", .string(crashClass)),
            ("fatal", .bool(fatal)),
        ]
        // The schema's name for the exception class is `type`, bounded to the
        // length the schema declares.
        if let t = IncidentPolicy.boundType(exceptionType) { extra.append(("type", .string(t))) }
        if let n = IncidentPolicy.boundThreadName(threadName) { extra.append(("threadName", .string(n))) }
        if let m = isMainThread { extra.append(("isMainThread", .bool(m))) }
        extra.append(("deliveredOnNextLaunch", .bool(true)))
        if !frames.isEmpty {
            extra.append(("frames", .array(frames.map { .object($0.fields) })))
        }
        if let s = IncidentPolicy.boundScreenId(screenId) { extra.append(("screen", .screen(ScreenRef(screenId: s)))) }
        // THE FRAME CAP IS NOT DISCLOSED ON THE WIRE, AND THAT IS THE
        // CONTRACT'S DOING. The envelope's `dropped` map is a PER-FAMILY LOSS
        // counter whose own description says "intentional privacy suppression is
        // NOT loss and must never appear here"; a within-event frame truncation
        // is neither, and there is no declared field for it. Inventing one is
        // exactly what native did with `exceptionType` / `stackFrames`. The drop
        // is disclosed LOCALLY through `RecoveryTrace`.
        return envelope(id: id, eventId: eventId, eventType: "mobile_crash",
                        sequence: sequence, timestampMillis: timestampMillis,
                        monotonicNanos: monotonicNanos, posture: posture, extra: extra)
    }

    /// The `mobile_hang` event. Byte-parallel to the Kotlin `RnEnvelope.hang`.
    ///
    /// `blockedMs` IS AN `Int`, NOT A `Double`, AND THAT IS DELIBERATE. The
    /// `.double` case renders a non-finite value as `null`, and the server reads
    /// `blockedMs` with no range — so a NaN computed across a clock step would
    /// reach a `double precision` column as a null nobody chose. An `Int` cannot
    /// be non-finite, and `IncidentPolicy.hangIsEmittable` refuses the value
    /// upstream anyway, so the two guards are independent rather than the same
    /// guard twice.
    public static func hang(id: Identity,
                            eventId: String,
                            sequence: Int64,
                            timestampMillis: Int64,
                            monotonicNanos: Int64,
                            posture: Posture,
                            errorId: String,
                            /// A member of `IncidentPolicy.hangKinds`.
                            hangKind: String,
                            blockedMs: Int,
                            thresholdMs: Int?,
                            recovered: Bool,
                            frames: [IncidentFrame],
                            screenId: String?,
                            deliveredOnNextLaunch: Bool = false) -> String {
        var extra: [(String, Value)] = [
            ("errorId", .string(errorId)),
            ("hangKind", .string(hangKind)),
            ("blockedMs", .int(blockedMs)),
        ]
        if let t = thresholdMs { extra.append(("threshold", .int(t))) }
        extra.append(("recovered", .bool(recovered)))
        extra.append(("deliveredOnNextLaunch", .bool(deliveredOnNextLaunch)))
        if !frames.isEmpty {
            extra.append(("frames", .array(frames.map { .object($0.fields) })))
        }
        // `mobile_hang` declares its OWN `screenId` string (not a screen
        // object), and the deployed planner reads `e.screenId` BEFORE
        // `e.screen.screenId` — so the declared shape is what goes on the wire.
        if let s = IncidentPolicy.boundScreenId(screenId) { extra.append(("screenId", .string(s))) }
        return envelope(id: id, eventId: eventId, eventType: "mobile_hang",
                        sequence: sequence, timestampMillis: timestampMillis,
                        monotonicNanos: monotonicNanos, posture: posture, extra: extra)
    }

    static func json(_ pairs: [(String, Value)]) -> String {
        var out = "{"
        var first = true
        for (k, v) in pairs {
            if !first { out += "," }
            first = false
            out += str(k) + ":" + value(k, v)
        }
        out += "}"
        return out
    }

    private static func value(_ key: String, _ v: Value) -> String {
        switch v {
        case .string(let s): return str(s)
        case .bool(let b): return b ? "true" : "false"
        case .int(let i): return String(i)
        case .int64AsString(let n): return str(String(n))
        case .screen(let s):
            return json([("screenId", .string(s.screenId)), ("kind", .string(s.kind))])
        case .rawJson(let j): return j
        case .double(let d):
            // FINITE ONLY. NaN/inf render as `nan`/`inf`, which is not JSON
            // and would make the whole chunk unparseable server-side; a
            // coordinate we cannot express must not be emitted at all.
            return d.isFinite ? String(d) : "null"
        case .compression(let q, let d):
            return "{\"quality\":\(q),\"lossless\":false,\"downscale\":\(d)}"
        case .fidelityPolicy(let e):
            // booleans unquoted, the two enums as strings — the shape the
            // declared schema requires. `effectivePolicy` is DERIVED inside the
            // evidence type, so an inconsistent pair cannot reach here.
            return "{" + e.wireFields.map { k, v in
                (v == "true" || v == "false")
                    ? "\(str(k)):\(v)" : "\(str(k)):\(str(v))"
            }.joined(separator: ",") + "}"
        case .object(let members):
            // Scalars only, rendered through the same `value` used everywhere
            // else, so a nested member cannot escape the quoting rules the top
            // level obeys.
            return "{" + members.map { k, v in str(k) + ":" + value(k, v) }.joined(separator: ",") + "}"
        case .array(let items):
            return "[" + items.map { value(key, $0) }.joined(separator: ",") + "]"
        case .posture(let p):
            return json([
                ("level", .string(p.level)),
                ("maskText", .bool(p.maskText)),
                ("maskInputs", .bool(p.maskInputs)),
                ("maskAccessibilityLabels", .bool(p.maskAccessibilityLabels)),
                ("blockImages", .bool(p.blockImages)),
                ("blockCameraSurfaces", .bool(p.blockCameraSurfaces)),
                ("allowedViewCount", .int(p.allowedViewCount)),
                ("screenNeverRecord", .bool(p.screenNeverRecord)),
            ])
        }
    }

    /// Minimal, complete JSON string escaping. Control characters are escaped
    /// as \u sequences — an unescaped 0x1F in a route name would produce
    /// invalid JSON the server refuses for the whole batch.
    static func str(_ s: String) -> String {
        var out = "\""
        for ch in s.unicodeScalars {
            switch ch {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if ch.value < 0x20 {
                    out += String(format: "\\u%04x", ch.value)
                } else {
                    out.unicodeScalars.append(ch)
                }
            }
        }
        return out + "\""
    }
}

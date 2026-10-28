import Foundation

/// MODEL B — the React Native native↔WebView CORRELATION decision, SWIFT TWIN.
///
/// This is the Swift side of the contract whose Kotlin side is
/// `android/rn-core/src/main/kotlin/com/tracescout/rn/RnWebViewSegment.kt`, and
/// whose JS reference is `src/webViewBridge.js`. All three are pinned to
/// `parity-vectors/webview-segment-vectors.json`, so a platform disagreement
/// fails on BYTES rather than on three people reading one paragraph — which is
/// precisely the defect the hand-written Swift hierarchy twin produced before
/// anyone compared it (`HierarchyParityTests` shipped UNRUN for weeks).
///
/// The charter's the specification is frozen and this is its executable form:
///
/// - Model B: native session **N**, WebView segment **S**, browser content
/// **B**; the correlation claim is **N + S**, never S alone.
/// - A WebView present but whose content is not captured must appear as an
/// **honest absence**: a BLOCKED node with a TYPED reason, never a
/// structurally-identical copy of the previous screen and never nothing.
/// - Arbitrary third-party WebView capture **must remain blocked**. Embedded
/// capture depends on the runtime WebView capability (a preflight), not on
/// the OS version; an unsupported capability yields **no segment row at
/// all**.
///
/// ## THE ORDER OF THE RULES IS THE CONTRACT
///
/// Capability → message kind → native session → origin admission → segment
/// identity → field vocabulary → admit. SIX vectors exist ONLY to pin that
/// order, because every reordering produces a refusal that misdescribes its own
/// cause:
///
/// - Deciding origin admission AFTER the segment id turns an unadmitted
/// third-party frame away for a `segment_id_mismatch`, which reads like a
/// bug in our own page rather than a policy decision.
/// - Deciding capability LAST reports a FEATURE refusal for an engine that is
/// simply absent, which describes the symptom and sends the reader after a
/// feature flag instead of the engine.
///
/// ## THIS IS A CORRELATION CHANNEL, NOT A JS EXECUTION CHANNEL
///
/// It carries a segment identifier and a privacy posture and NOTHING else. The
/// accepted message vocabulary is a CLOSED set of two shapes, every field is
/// validated, and anything unrecognised is refused with a typed reason rather
/// than forwarded. The page can never choose its own segment id: the native
/// side holds `expectedSegmentId` and a message naming anything else is
/// refused, so a frame cannot attach itself to — or overwrite — another
/// segment's posture.
///
/// ## ONE DELIBERATE DIVERGENCE IN SHAPE, NONE IN SEMANTICS
///
/// Kotlin takes `Map<String, Any?>` and reads it with `as? String` / `== true`.
/// Swift takes a CLOSED `Field` scalar instead, and that is a CORRECTNESS
/// requirement rather than a style choice — see `Field`.
public enum RnWebViewSegment {

    /// Only these two message kinds exist. Anything else is refused.
    public static let KIND_HELLO = "ts_webview_hello"
    public static let KIND_POSTURE = "ts_webview_posture"

    /// The TYPED node reasons, taken from the protocol's CLOSED `blockedReason`
    /// enum (`mobile-protocol/src/node.schema.json`).
    ///
    /// THE ENUM IS CLOSED AND THE DEPLOYED SERVER REFUSES ANYTHING OUTSIDE
    /// IT. Inventing a value like `captured_by_browser_segment` is the
    /// `mobile_screen_never_record` mistake — an event type the protocol never
    /// declared, refused permanently by the API and quarantining the records
    /// that carried it. So an ADMITTED segment's node keeps
    /// `BLOCKED_THIRD_PARTY`: the native walker still does not traverse web
    /// content, and what says content exists ELSEWHERE is the presence of
    /// `webViewSegmentId` on the node, never a different reason.
    public static let BLOCKED_THIRD_PARTY = "third_party_webview"

    /// An unsupported capability is a different fact and gets its own reason.
    public static let BLOCKED_UNSUPPORTED = "unsupported_surface"

    /// The closed set of frame roles a participating document may claim.
    public static let FRAME_ROLES: Set<String> = [
        "parent", "same_origin_child", "cross_origin_child", "nested_outer", "nested_inner",
    ]

    /// The accepted field vocabulary. A bridge that passes through unknown keys
    /// is a bridge whose surface is defined by whoever is on the other end.
    public static let ALLOWED_FIELDS: Set<String> = [
        "segmentId", "level", "maskText", "maskInputs", "browserPrivacyStatus",
        "frameRole", "frameOrigin",
    ]

    /// The browser side's own coarse status — bounded to the WIRE ENUM
    /// (`envelope.schema.json: webViewBrowserPrivacyStatus`).
    ///
    /// AN UNRECOGNISED VALUE IS **ABSENT**, NEVER COERCED TO `unknown`. The
    /// deployed API refuses a present-but-invalid value with a PERMANENT
    /// (non-retriable) `webview_browser_privacy_status_invalid`, which
    /// quarantines the whole chunk — so a value that would be refused
    /// downstream is dropped at the source. It is not coerced to `unknown`
    /// either, because `unknown` is a CLAIM about the browser recorder's state
    /// and we do not have one; absent means "not reported".
    public static let BROWSER_PRIVACY_STATUSES: Set<String> = [
        "enabled", "partial", "disabled", "unknown",
    ]

    /// Weakest → strongest, the SAME ordering as
    /// `mobile-protocol/src/posture.cjs PRIVACY_LEVELS`. Restating the ordering
    /// is the defect that file exists to prevent, so this list is pinned
    /// against it by a parity assertion in the test suite rather than trusted.
    public static let PRIVACY_LEVELS: [String] = ["unknown", "relaxed", "strict"]

    /// Whether embedded-WebView capture can run on THIS device right now.
    ///
    /// THE WIRE STRINGS ARE THE CONTRACT and are shared with the Kotlin twin
    /// and the vectors. They say `provider` because that is what the Android
    /// preflight measures (an updateable system component); on iOS the web
    /// engine ships with the OS and the preflight measures availability and the
    /// runtime features the bridge actually calls. Using one vocabulary for one
    /// decision is deliberate: a second set of codes would make an iOS refusal
    /// and an Android refusal incomparable in a report that must cover both.
    public enum Capability: String, CaseIterable {
        case supported = "webview_capture_supported"
        case providerUnsupported = "webview_provider_unsupported"
        case featureUnsupported = "webview_feature_unsupported"
        case providerUnavailable = "webview_provider_unavailable"

        /// ANYTHING UNRECOGNISED IS `providerUnavailable`, never
        /// `supported`. An unreadable verdict is not evidence of a good one.
        public static func from(_ s: String?) -> Capability {
            guard let s = s, let c = Capability(rawValue: s) else { return .providerUnavailable }
            return c
        }
    }

    /// How the frame's origin was learned.
    ///
    /// An AUTHENTICATED origin came from the browser and is unforgeable; a
    /// DECLARED one came from the page and a hostile frame can claim any
    /// admitted value. Recorded so a report can never present the weaker
    /// evidence as the stronger one.
    public enum OriginTrust: String {
        case authenticated
        case declared
    }

    public enum Outcome: String {
        /// N + S established. The envelope may carry the segment.
        case admit
        /// A message we received and rejected. A segment may still exist.
        case refuse
        /// the specification: no segment row may exist at all on this device.
        case absent
    }

    /// ONE inbound field value — a CLOSED JSON scalar.
    ///
    /// THIS IS A CORRECTNESS REQUIREMENT, NOT A STYLE CHOICE, AND IT IS THE
    /// ONE PLACE THE SWIFT TWIN'S SHAPE DIFFERS FROM KOTLIN'S. Kotlin reads an
    /// `Any?` map with `as? String` and `== true`, which is exact on the JVM.
    /// On Darwin it is NOT: a JSON `1` arriving through `JSONSerialization` is
    /// an `NSNumber`, and `(NSNumber(value: 1) as Any) as? Bool` succeeds with
    /// `true`. An `[String: Any]` field map would therefore answer TRUE for
    /// `maskInputs: 1` and report a protection that the page never claimed —
    /// exactly the truthy coercion the contract's own vector forbids ("a
    /// non-boolean protection flag is the UNPROTECTED answer"). With a closed
    /// scalar the coercion is unrepresentable rather than guarded.
    public enum Field: Equatable {
        case string(String)
        case bool(Bool)
        case number(Double)

        /// The string, or nil when this is not a string. The Swift spelling of
        /// Kotlin's `as? String`.
        public var asString: String? {
            if case .string(let s) = self { return s }
            return nil
        }

        /// True ONLY for a real boolean true. The Swift spelling of Kotlin's
        /// `== true` on an `Any?`.
        public var isTrue: Bool { self == .bool(true) }
    }

    /// What the envelope carries. Nothing here is page-chosen except the
    /// posture claim.
    public struct Segment: Equatable {
        public let segmentId: String
        public let nativeSessionId: String
        public let level: String
        public let maskText: Bool
        public let maskInputs: Bool
        /// Nil when the page reported nothing recognisable.
        public let browserPrivacyStatus: String?
        public let frameRole: String?
        public let origin: String?
        public let originTrust: OriginTrust?
    }

    public struct Decision: Equatable {
        public let outcome: Outcome
        /// A typed code. Never free text, never built from a page value.
        public let code: String
        /// The honest-absence reason for the NATIVE node, present in every
        /// outcome — including `admit`, because the native walker never
        /// captures web content whatever the segment says.
        public let nodeBlockedReason: String
        public let segment: Segment?
    }

    // ── validators ──────────────────────────────────────────────────────────
    //
    // A FULL-RANGE MATCH, NEVER `firstMatch != nil`. Kotlin's
    // `Pattern.matches` requires the whole region to match; ICU's `$` can
    // also match BEFORE a trailing newline, so an anchored pattern alone would
    // admit `"seg0123456789abcdef\n"` on Swift and refuse it on the JVM. The
    // range comparison is what makes the two agree.

    private static let SEGMENT_ID = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{8,64}$")
    private static let SESSION_ID = try! NSRegularExpression(pattern: "^ts_[0-9]+_[0-9a-f]{12}$")
    /// scheme://host[:port] — nothing else.
    private static let ORIGIN = try! NSRegularExpression(pattern: "^https?://[a-z0-9.-]+(:[0-9]{1,5})?$")
    private static let LOCAL = try! NSRegularExpression(
        pattern: "^https?://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?$")

    /// Is this a well-formed native session id?
    ///
    /// EXPOSED SO THERE IS ONE HOME FOR THE QUESTION, NOT TO WIDEN THE API.
    /// `SESSION_ID` is file-private, so the bind site (`RnWebViewBinding`)
    /// would otherwise have had to carry a SECOND copy of the pattern — and a
    /// duplicated identity grammar is the one-fact-two-homes defect this
    /// programme keeps paying for, with the copy that drifts always being the
    /// one nobody is looking at. `decide` still asks this question itself; this
    /// is the same question, not a second answer.
    public static func isValidSessionId(_ s: String) -> Bool {
        fullMatch(SESSION_ID, s)
    }

    static func fullMatch(_ re: NSRegularExpression, _ s: String) -> Bool {
        let whole = NSRange(location: 0, length: (s as NSString).length)
        guard let m = re.firstMatch(in: s, options: [], range: whole) else { return false }
        return m.range == whole
    }

    /// Normalize one origin, or nil when it is unusable. Fails CLOSED on every
    /// ambiguity, because every guess here is a WIDENING.
    ///
    /// `*`, `null` and `about:blank` are the opaque origins: admitting one
    /// admits ALL of them at once, since they are indistinguishable from each
    /// other. `http://` is acceptable only for local development.
    public static func normalizeOrigin(_ raw: String?) -> String? {
        guard let raw = raw else { return nil }
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        // A DOUBLED GUARD, AND NEITHER HALF IS LOAD-BEARING ON ITS OWN FOR
        // THESE THREE VALUES. Measured both directions on the JS twin, not
        // reasoned:
        //
        // this line removed -> `*` / `null` / `about:blank` still refused
        // (ORIGIN rejects them: none is scheme://host)
        // ORIGIN removed -> those three are still refused by this line,
        // while a PATH-bearing origin
        // (`https://host/docs`) stops being refused
        //
        // So removing EITHER half shows no failure for the opaque triple,
        // which is exactly what makes a redundant pair dangerous to tidy up.
        // Keep both: a future widening of ORIGIN (another scheme, a looser host
        // set) would otherwise silently admit an opaque origin, and admitting
        // ONE admits ALL of them — a sandboxed, `data:` or `about:` document
        // reports `null` and they are indistinguishable from each other. Do not
        // delete this on the strength of a control passing.
        let lower = s.lowercased()
        if s == "*" || lower == "null" || lower == "about:blank" { return nil }
        if !fullMatch(ORIGIN, lower) { return nil }
        if lower.hasPrefix("http://") && !fullMatch(LOCAL, lower) { return nil }
        return lower
    }

    /// Normalize a declared allowlist, or nil when it is unusable.
    ///
    /// A SINGLE UNUSABLE ENTRY DISABLES THE WHOLE POLICY rather than
    /// admitting the entries that happened to parse — a typo must not silently
    /// shrink the intended set into something the operator never reviewed. An
    /// EMPTY declared list is a policy that admits nothing, which is different
    /// from no policy at all and is reported differently.
    public static func normalizePolicy(_ origins: [String]?) -> [String]? {
        guard let origins = origins else { return nil }
        var out: [String] = []
        for o in origins {
            guard let n = normalizeOrigin(o) else { return nil }
            if !out.contains(n) { out.append(n) }
        }
        return out
    }

    private static func refuse(_ code: String) -> Decision {
        Decision(outcome: .refuse, code: code, nodeBlockedReason: BLOCKED_THIRD_PARTY, segment: nil)
    }

    /// Decide ONE inbound bridge message.
    ///
    /// `expectedSegmentId` and `nativeSessionId` are held by the NATIVE side.
    /// `authenticatedOrigin` is the origin the BROWSER attributed to the calling
    /// frame, available only on a channel that can supply one; nil means the
    /// page's own claim is all there is, and the decision records that
    /// difference in `Segment.originTrust` rather than hiding it.
    public static func decide(capability: Capability,
                              /// The host app's declared allowlist. Nil = none declared.
                              policy: [String]?,
                              kind: String?,
                              fields: [String: Field],
                              expectedSegmentId: String,
                              nativeSessionId: String,
                              authenticatedOrigin: String? = nil) -> Decision {
        // (1) CAPABILITY FIRST. An unsupported capability yields no segment row
        // at all, so nothing below this line can produce one — and the reason
        // names the ENGINE rather than whatever the page happened to send.
        if capability != .supported {
            return Decision(outcome: .absent, code: capability.rawValue,
                            nodeBlockedReason: BLOCKED_UNSUPPORTED, segment: nil)
        }

        // (2) The closed message vocabulary.
        if kind != KIND_HELLO && kind != KIND_POSTURE { return refuse("unknown_message_kind") }

        // (3) Our own session identity must be well-formed before we attach
        // anything to it.
        if !isValidSessionId(nativeSessionId) { return refuse("native_session_invalid") }

        // (4) ORIGIN ADMISSION, before anything the frame claims about the
        // session. See the ordering note in the type docs.
        guard let admitted = normalizePolicy(policy) else { return refuse("origin_policy_absent") }
        let declared = fields["frameOrigin"]?.asString
        let origin: String
        let trust: OriginTrust
        if let authRaw = authenticatedOrigin {
            guard let auth = normalizeOrigin(authRaw) else { return refuse("origin_malformed") }
            // A frame that names an origin OTHER than the one the browser
            // attributed to it is lying. Only this channel can catch that.
            if let d = declared, normalizeOrigin(d) != auth { return refuse("origin_spoofed") }
            origin = auth
            trust = .authenticated
        } else {
            guard let d = declared else { return refuse("origin_missing") }
            guard let n = normalizeOrigin(d) else { return refuse("origin_malformed") }
            origin = n
            trust = .declared
        }
        if !admitted.contains(origin) { return refuse("origin_not_admitted") }

        guard let role = fields["frameRole"]?.asString else { return refuse("frame_role_missing") }
        if !FRAME_ROLES.contains(role) { return refuse("frame_role_invalid") }

        // (5) SEGMENT IDENTITY. The page may only ECHO the id the native side
        // minted; it can never choose one.
        guard let claimed = fields["segmentId"]?.asString else { return refuse("segment_id_missing") }
        if !fullMatch(SEGMENT_ID, claimed) { return refuse("segment_id_invalid") }
        if claimed != expectedSegmentId { return refuse("segment_id_mismatch") }

        // (6) FIELD VOCABULARY. Refused, not filtered — a dropped unknown key
        // is a surface we did not notice growing.
        //
        // SORTED so the code is deterministic. ASCII keys only in the
        // contract today; the twin's `sorted` and the JVM's natural ordering
        // agree over ASCII, and a non-ASCII key would be an unknown field the
        // decision refuses anyway.
        let unknown = Set(fields.keys).subtracting(ALLOWED_FIELDS)
        if !unknown.isEmpty {
            return refuse("unknown_fields:" + unknown.sorted().joined(separator: ","))
        }

        // (7) ADMIT. An absent or unrecognised level resolves to `unknown` —
        // the weakest, which `isShareable` refuses — never to a convenient
        // default of strict. A protection flag is true only when the page says
        // exactly true; anything else is the unprotected answer, which the
        // session-wide worst-posture resolution then has to account for.
        let lvl = fields["level"]?.asString
        let level = (lvl != nil && PRIVACY_LEVELS.contains(lvl!)) ? lvl! : "unknown"
        let status = fields["browserPrivacyStatus"]?.asString
            .flatMap { BROWSER_PRIVACY_STATUSES.contains($0) ? $0 : nil }
        return Decision(
            outcome: .admit,
            code: "admitted",
            nodeBlockedReason: BLOCKED_THIRD_PARTY,
            segment: Segment(
                segmentId: claimed,
                nativeSessionId: nativeSessionId,
                level: level,
                maskText: fields["maskText"]?.isTrue ?? false,
                maskInputs: fields["maskInputs"]?.isTrue ?? false,
                browserPrivacyStatus: status,
                frameRole: role,
                origin: origin,
                originTrust: trust))
    }
}

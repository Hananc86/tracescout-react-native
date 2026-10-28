import Foundation

/// M3.2 — the PURE core of the **iOS** network producer, and the Swift twin of
/// `NetworkCaptureCore.kt`.
///
/// THE CANONICAL SCHEMA IS THE AUTHORITY, NOT THE S4 PROTOTYPE. The S4-A
/// parity harness carried a deliberately REDUCED projection (`method`, `host`,
/// `routeTemplate`, `status`, `durationMs`, `errorClass`, `protocolHint`,
/// `screenId`) because its only job was to prove the privacy asymmetry. Reading
/// it as the wire contract would have shipped an iOS producer that:
///
/// * omits `networkRequestId`, `requestStartedWallTime`,
/// `requestStartedMonotonicNanos`, `outcome` and `sourceLayer` — **all five
/// REQUIRED** by `mobile_network_request.schema.json`;
/// * emits `errorClass` values `network` / `unknown`, which are **not in the
/// schema's enum at all** (`dns|tls|timeout|connection|cancelled|other`).
///
/// Both would have been refused by the deployed planner, i.e. a PERMANENT 422,
/// which this pipeline quarantines. The field set below is copied from the
/// schema and from the Kotlin core, field by field.
///
/// NO IOS-SPECIFIC SHAPE. Every rule here mirrors Android's: the start
/// context is frozen at request START and never recomputed, failures are
/// classified from a TYPE never a message, `status 0` is unrepresentable, a
/// redirect chain is ONE logical row, and the budget refuses at start.
public enum NetworkCaptureCore {

    /// The schema's closed vocabulary — `dns|tls|timeout|connection|cancelled|other`.
    /// NOT the S4 prototype's `network`/`unknown`, which the schema refuses.
    public enum ErrorClass: String {
        case dns, tls, timeout, connection, cancelled, other
    }

    public enum Outcome: String {
        case success, failure, cancelled, unknown
    }

    /// Frozen at logical request START and never recomputed.
    ///
    /// A request begun on screen A and completed after the user navigated to
    /// screen B belongs to A. On iOS the metrics callback arrives at COMPLETION,
    /// so `screenId` must never be a late read of "the current screen" — that
    /// would be systematically wrong for exactly the slow requests an engineer
    /// opens a replay to investigate. It is resolved instead by looking the
    /// request's START instant up in `NetworkScreenTimeline`, and is nil when
    /// that history cannot cover the instant.
    public struct StartContext: Equatable {
        public let networkRequestId: String
        public let startedWallTime: Int64
        public let startedMonotonicNanos: Int64
        public let method: String
        public let scheme: String?
        public let host: String
        public let routeTemplate: String?
        /// The screen that was current when the request STARTED, or nil when it
        /// cannot be established. Android resolves it at the OkHttp seam, which
        /// runs at start; iOS resolves it by looking `startedMonotonicNanos` up
        /// in `NetworkScreenTimeline`, because its only seam runs at completion.
        public let screenId: String?
        public var sourceLayer: String { "urlsession" }

        public init(networkRequestId: String, startedWallTime: Int64,
                    startedMonotonicNanos: Int64, method: String, scheme: String?,
                    host: String, routeTemplate: String?, screenId: String? = nil) {
            self.networkRequestId = networkRequestId
            self.startedWallTime = startedWallTime
            self.startedMonotonicNanos = startedMonotonicNanos
            self.method = method
            self.scheme = scheme
            self.host = host
            self.routeTemplate = routeTemplate
            self.screenId = screenId
        }
    }

    /// Why no event was produced. Each is a REFUSAL, never a silent drop.
    public enum Refusal: String {
        case captureDisabled = "capture_disabled"
        case internalRequest = "internal_request"
        case urlNotRepresentable = "url_not_representable"
        case budgetExhausted = "budget_exhausted"
        case noSession = "no_session"
        /// IOS-ONLY, AND IT IS A REAL PRODUCT DECISION.
        /// `requestStartedMonotonicNanos` is REQUIRED and non-nullable in the
        /// schema, so when the wall↔monotonic conversion cannot be trusted there
        /// is no truthful event to emit — the whole event is refused rather than
        /// emitted with a fabricated or omitted timestamp. A missing row is
        /// preferable to a row on the wrong part of the replay clock.
        case clockDivergence = "clock_divergence"
        /// The request STARTED before the current capture/session authority
        /// began. Attributing it to the current session would be cross-session
        /// or pre-consent attribution; a missing row is preferable.
        case outsideCaptureEpoch = "outside_capture_epoch"
        /// The epoch turned over while the event was being built.
        case captureEpochChanged = "capture_epoch_changed"
    }

    public struct StartResult {
        public let context: StartContext?
        public let refusal: Refusal?
        public var captured: Bool { context != nil }
    }

    /// CLOSED enum. An unlisted method yields NO event rather than a free-form
    /// string on the busiest event type a session produces.
    static let methods: Set<String> = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]

    /// - Parameter internalRequest: TraceScout's own transport. On iOS this is
    /// STRUCTURAL — the uploader posts through `URLSession.shared`, which has
    /// no delegate at all, so RN's `RCTHTTPRequestHandler` can never be
    /// consulted for its tasks. The parameter stays as defence in depth for
    /// any future TraceScout code that might run on RN's own session.
    public static func startContext(
        enabled: Bool,
        internalRequest: Bool,
        hasSession: Bool,
        rawUrl: String?,
        method: String?,
        networkRequestId: String,
        startedWallTime: Int64,
        startedMonotonicNanos: Int64,
        screenId: String? = nil,
        budget: NetworkBudget
    ) -> StartResult {
        if !enabled { return StartResult(context: nil, refusal: .captureDisabled) }
        // Self-exclusion FIRST, before anything reads the URL, so our own
        // transport never even reaches the normalizer.
        if internalRequest { return StartResult(context: nil, refusal: .internalRequest) }
        if !hasSession { return StartResult(context: nil, refusal: .noSession) }

        let m = (method ?? "").uppercased()
        if !methods.contains(m) { return StartResult(context: nil, refusal: .urlNotRepresentable) }

        // THE ONE normalizer, shared with Kotlin and the JS reference and pinned
        // to the same vectors. The raw URL exists only as this argument and is
        // never retained.
        let n = UrlNormalizer.normalize(rawUrl)
        guard n.representable, let host = n.host else {
            return StartResult(context: nil, refusal: .urlNotRepresentable)
        }
        if !budget.admit() { return StartResult(context: nil, refusal: .budgetExhausted) }

        return StartResult(
            context: StartContext(
                networkRequestId: networkRequestId,
                startedWallTime: startedWallTime,
                startedMonotonicNanos: startedMonotonicNanos,
                method: m,
                scheme: n.scheme,
                host: host,
                routeTemplate: n.routeTemplate,
                screenId: screenId),
            refusal: nil)
    }

    /// Classify a transport failure from the NSError DOMAIN + CODE.
    ///
    /// NEVER FROM `localizedDescription`. A Foundation error string is free
    /// text outside our control and is exactly where a URL, a host or a
    /// credential leaks into telemetry — *"A server with the specified hostname
    /// could not be found."* is harmless, *"Could not connect to the server
    /// api.customer.com:8443"* is not. This function is handed two integers and
    /// a domain string; a message cannot reach it even by accident.
    public static func classifyFailure(domain: String?, code: Int) -> ErrorClass {
        guard domain == NSURLErrorDomain else { return .other }
        switch code {
        // TIMEOUT IS CHECKED BEFORE CANCELLATION, the same ordering the real
        // OkHttp client forced on Android — there a `callTimeout` is implemented
        // BY CANCELLING, so a flag-first classifier reported every timeout as a
        // user cancellation. Foundation gives distinct codes, but keeping the
        // same precedence keeps the two platforms one rule rather than two.
        case NSURLErrorTimedOut:
            return .timeout
        case NSURLErrorCancelled, NSURLErrorUserCancelledAuthentication:
            return .cancelled
        case NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed:
            return .dns
        case NSURLErrorSecureConnectionFailed,
             NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateUntrusted,
             NSURLErrorServerCertificateHasUnknownRoot,
             NSURLErrorServerCertificateNotYetValid,
             NSURLErrorClientCertificateRejected,
             NSURLErrorClientCertificateRequired,
             NSURLErrorAppTransportSecurityRequiresSecureConnection:
            return .tls
        case NSURLErrorCannotConnectToHost,
             NSURLErrorNetworkConnectionLost,
             NSURLErrorNotConnectedToInternet,
             NSURLErrorInternationalRoamingOff,
             NSURLErrorCallIsActive,
             NSURLErrorDataNotAllowed,
             NSURLErrorResourceUnavailable:
            return .connection
        default:
            return .other
        }
    }

    /// `URLSessionTaskTransactionMetrics.networkProtocolName` → the bounded enum.
    ///
    /// THE RAW NAME NEVER CROSSES THE EVENT BOUNDARY. The deployed column is
    /// CHECK-pinned to `(h1|h2|h3|unknown)` (migration 163), so an unmapped
    /// value becomes `unknown` — never a passthrough, and never a rejection of
    /// the whole event for a field that is optional.
    public static func protocolHint(_ raw: String?) -> String {
        guard let raw = raw?.lowercased(), !raw.isEmpty else { return "unknown" }
        switch raw {
        case "http/1.0", "http/1.1": return "h1"
        case "h2", "h2c": return "h2"
        default:
            return raw.hasPrefix("h3") ? "h3" : "unknown"
        }
    }

    /// ONE completed event per logical call.
    public struct NetworkEvent {
        public let start: StartContext
        public let durationMs: Int64
        public let status: Int?
        public let outcome: Outcome
        public let errorClass: ErrorClass?
        public let requestBytes: Int64?
        public let responseBytes: Int64?
        public let protocolHint: String?

        /// The wire pairs, in the canonical order. **The field set IS the
        /// privacy boundary** — there is no key here capable of carrying a body,
        /// a header, an Authorization or Cookie value, an API key, a raw URL, a
        /// query string, a fragment, userinfo, a redirect chain or an NSError
        /// string.
        ///
        /// `redirectCount` is deliberately NOT emitted. `URLSessionTaskMetrics`
        /// exposes it and the approved schema has no field for it; adding one
        /// because an API happens to offer it would make a protocol decision by
        /// accident.
        public func wire() -> [(String, RnEnvelope.Value)] {
            var pairs: [(String, RnEnvelope.Value)] = [
                ("networkRequestId", .string(start.networkRequestId)),
                ("requestStartedWallTime", .int(Int(start.startedWallTime))),
                ("requestStartedMonotonicNanos", .int64AsString(start.startedMonotonicNanos)),
                ("durationMs", .int(Int(durationMs))),
                ("method", .string(start.method)),
            ]
            // AN ABSENT OPTIONAL IS OMITTED, NEVER EMITTED AS `null`, and the
            // envelope serializer is why: `RnEnvelope.Value` has no null case at
            // all, and the Kotlin twin's `json` drops nulls explicitly
            // ("omit, never emit null"). Every one of these keys is nullable in
            // the schema AND absent from `required`, so omission is the
            // canonical on-wire form — and writing `.null` here would not have
            // compiled, which is the cheap version of this lesson.
            if let s = start.scheme { pairs.append(("scheme", .string(s))) }
            pairs.append(("host", .string(start.host)))
            if let rt = start.routeTemplate { pairs.append(("routeTemplate", .string(rt))) }
            if let st = status { pairs.append(("status", .int(st))) }
            pairs.append(("outcome", .string(outcome.rawValue)))
            if let ec = errorClass { pairs.append(("errorClass", .string(ec.rawValue))) }
            if let rb = requestBytes { pairs.append(("requestBytes", .int(Int(rb)))) }
            if let sb = responseBytes { pairs.append(("responseBytes", .int(Int(sb)))) }
            pairs.append(("sourceLayer", .string(start.sourceLayer)))
            if let p = protocolHint { pairs.append(("protocolHint", .string(p))) }
            return pairs
        }
    }

    /// A response arrived.
    ///
    /// `status` outside 100.599 produces NO EVENT. The classic "0 means the
    /// network failed" encoding conflates two different facts, and the schema,
    /// the planner and the SQL CHECK all refuse it — so the producer must not
    /// manufacture one either.
    public static func completeWithResponse(
        start: StartContext, endMonotonicNanos: Int64, status: Int,
        requestBytes: Int64?, responseBytes: Int64?, protocolHint: String?
    ) -> NetworkEvent? {
        guard status >= 100 && status <= 599 else { return nil }
        return NetworkEvent(
            start: start,
            durationMs: duration(start, endMonotonicNanos),
            status: status,
            // A 500 IS A SUCCESSFUL TRANSPORT. `outcome` describes whether the
            // request completed, not whether the server liked it; the Viewer
            // paints the HTTP status. Reporting `failure` here would make every
            // server error look like a network error.
            outcome: .success,
            errorClass: nil,
            requestBytes: requestBytes,
            responseBytes: responseBytes,
            protocolHint: protocolHint)
    }

    /// No response.
    public static func completeWithFailure(
        start: StartContext, endMonotonicNanos: Int64,
        domain: String?, code: Int,
        requestBytes: Int64?, responseBytes: Int64?, protocolHint: String?
    ) -> NetworkEvent {
        let ec = classifyFailure(domain: domain, code: code)
        return NetworkEvent(
            start: start,
            durationMs: duration(start, endMonotonicNanos),
            status: nil,
            outcome: ec == .cancelled ? .cancelled : .failure,
            errorClass: ec,
            requestBytes: requestBytes,
            responseBytes: responseBytes,
            protocolHint: protocolHint)
    }

    private static func duration(_ start: StartContext, _ endMonotonicNanos: Int64) -> Int64 {
        let d = (endMonotonicNanos - start.startedMonotonicNanos) / 1_000_000
        // A monotonic clock cannot go backwards, but the schema refuses a
        // negative duration outright and a defensive clamp costs nothing.
        if d < 0 { return 0 }
        return d > 3_600_000 ? 3_600_000 : d
    }
}

/// The network-telemetry budget — the SAME numbers as Android, deliberately.
///
/// EXHAUSTION DROPS TELEMETRY ONLY. It never blocks, delays or alters the
/// application's request, and it never touches hierarchy or frame capture.
public final class NetworkBudget {
    public let maxEventsPerSession: Int
    public let maxSerializedBytes: Int
    private var admitted = 0
    private var dropped = 0
    private let lock = NSLock()

    public init(maxEventsPerSession: Int, maxSerializedBytes: Int) {
        self.maxEventsPerSession = maxEventsPerSession
        self.maxSerializedBytes = maxSerializedBytes
    }

    /// Frozen v1 limits, measured on the Android fixture and NOT re-derived for
    /// iOS — a platform-specific budget would be a number nobody measured.
    public static func frozenV1() -> NetworkBudget {
        NetworkBudget(maxEventsPerSession: 500, maxSerializedBytes: 4096)
    }

    public func admit() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if admitted >= maxEventsPerSession { dropped += 1; return false }
        admitted += 1
        return true
    }

    /// An oversized serialized event is dropped rather than truncated:
    /// truncating a structured event produces a record that is syntactically
    /// valid and semantically a lie. The slot the start context reserved is
    /// RETURNED, so one oversized event does not consume the session's budget.
    public func admitSerialized(_ byteCount: Int) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if byteCount > maxSerializedBytes {
            dropped += 1
            if admitted > 0 { admitted -= 1 }
            return false
        }
        return true
    }

    /// Returns the reserved slot without counting a drop — for a refusal that is
    /// not the event's fault, such as a clock divergence.
    public func refund() {
        lock.lock(); defer { lock.unlock() }
        if admitted > 0 { admitted -= 1 }
    }

    public func droppedCount() -> Int { lock.lock(); defer { lock.unlock() }; return dropped }
    public func admittedCount() -> Int { lock.lock(); defer { lock.unlock() }; return admitted }
    public func reset() { lock.lock(); defer { lock.unlock() }; admitted = 0; dropped = 0 }
}

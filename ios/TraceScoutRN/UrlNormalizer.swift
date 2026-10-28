import Foundation

/// THE CANONICAL ROUTE-TEMPLATE NORMALIZER — Swift twin.
///
/// ONE RULE OVERRIDES EVERY OTHER: **a raw path is never an output.** When the
/// classifier is not confident the answer degrades — `:segment`, then host-only,
/// then no event at all. It never falls back to the value it could not classify,
/// because "I could not tell what this was" is exactly when the value is most
/// likely to be an identifier, a token or an address.
///
/// The raw URL exists only as the caller's transient input. Query, fragment and
/// userinfo are discarded BEFORE templating, so they are gone before the output
/// is computed and no later code path can reintroduce them.
///
/// Pinned to `parity-vectors/url-normalization-vectors.json` together with the
/// Kotlin twin and the JS reference. Deliberately HAND-PARSED rather than
/// delegating to `URLComponents`: Foundation, `java.net.URI` and WHATWG `URL`
/// disagree on userinfo, empty hosts and percent-encoding, and a parity contract
/// must not rest on three libraries agreeing by luck.
public enum UrlNormalizer {

    public static let maxSegments = 10
    public static let maxSegmentLen = 48

    public struct Normalized: Equatable {
        public let host: String?
        public let routeTemplate: String?
        public let scheme: String?
        /// A URL we cannot represent yields NO event at all.
        public var representable: Bool { host != nil }
        public static let none = Normalized(host: nil, routeTemplate: nil, scheme: nil)
    }

    private static func isHexString(_ s: String, min: Int) -> Bool {
        guard s.count >= min else { return false }
        return s.allSatisfy { $0.isHexDigit }
    }
    private static func isAllDigits(_ s: String) -> Bool {
        !s.isEmpty && s.allSatisfy { $0.isNumber && $0.isASCII }
    }
    private static func isUuid(_ s: String) -> Bool {
        let parts = s.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 5 else { return false }
        let lens = [8, 4, 4, 4, 12]
        for (i, p) in parts.enumerated() where p.count != lens[i] || !p.allSatisfy({ $0.isHexDigit }) {
            return false
        }
        return true
    }
    /// A route NAME: starts with a letter, then letters/digits/. _ -
    private static func isName(_ s: String) -> Bool {
        guard let f = s.first, f.isLetter, f.isASCII else { return false }
        return s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-") }
    }

    static func classify(_ seg: String) -> String? {
        if seg.isEmpty { return nil }
        // CONFIDENT IDENTIFIERS BEFORE THE LENGTH DEGRADE. A 64-hex content
        // digest is longer than maxSegmentLen, so checking length first reports
        // `:segment` for the one shape we can name with certainty.
        if isUuid(seg) || isAllDigits(seg) || isHexString(seg, min: 24) { return ":id" }
        if seg.count > maxSegmentLen { return ":segment" }
        if seg.contains(where: { "@%=+;:".contains($0) }) { return ":segment" }
        if !isName(seg) { return ":segment" }
        // THE FOUR-CHARACTER FLOOR IS LOAD-BEARING. Without it `v2` is
        // digit-dominated (one digit of two characters) and every versioned API
        // collapses to `/:segment/.` — useless for the routes customers have.
        let alnum = seg.filter { $0.isLetter || $0.isNumber }
        let digits = seg.filter { $0.isNumber }.count
        if alnum.count >= 4 && digits > 0 && digits * 2 >= alnum.count { return ":segment" }
        let dots = seg.split(separator: ".", omittingEmptySubsequences: false)
        if dots.count >= 3 && dots.allSatisfy({ $0.count >= 4 }) { return ":segment" }
        return seg
    }

    /// - Parameter raw: the application's URL. NEVER stored, NEVER logged.
    public static func normalize(_ raw: String?) -> Normalized {
        guard let raw = raw, !raw.isEmpty else { return .none }
        guard let schemeRange = raw.range(of: "://"), schemeRange.lowerBound != raw.startIndex else { return .none }
        let scheme = String(raw[raw.startIndex..<schemeRange.lowerBound]).lowercased()
        guard scheme == "http" || scheme == "https" else { return .none }

        var rest = String(raw[schemeRange.upperBound...])
        // Fragment and query die FIRST, before anything reads a path.
        if let h = rest.firstIndex(of: "#") { rest = String(rest[rest.startIndex..<h]) }
        if let q = rest.firstIndex(of: "?") { rest = String(rest[rest.startIndex..<q]) }

        var authority = rest
        var path = "/"
        if let s = rest.firstIndex(of: "/") {
            authority = String(rest[rest.startIndex..<s])
            path = String(rest[s...])
        }
        // userinfo is dropped by taking what follows the LAST '@'.
        if let at = authority.lastIndex(of: "@") {
            authority = String(authority[authority.index(after: at)...])
        }
        let host = authority.lowercased()
        guard !host.isEmpty,
              host.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == ":" || $0 == "-") })
        else { return .none }

        let segs = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        if segs.isEmpty { return Normalized(host: host, routeTemplate: "/", scheme: scheme) }
        if segs.count > maxSegments { return Normalized(host: host, routeTemplate: nil, scheme: scheme) }
        var out: [String] = []
        for s in segs {
            guard let c = classify(s) else { return Normalized(host: host, routeTemplate: nil, scheme: scheme) }
            out.append(c)
        }
        return Normalized(host: host, routeTemplate: "/" + out.joined(separator: "/"), scheme: scheme)
    }
}

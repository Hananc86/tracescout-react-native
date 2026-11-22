import Foundation

/// MOBILE IDENTITY v1 (G2) — what `identify` may put on the wire, in ONE place.
///
/// The Swift twin of `mobile-protocol/src/identity-policy.cjs`, pinned to
/// `parity-vectors/identity-policy-vectors.json`. A platform disagreement fails
/// on BYTES, not on three people reading one paragraph.
///
/// IT REFUSES; IT DOES NOT BOUND. Every other string in this protocol is
/// truncated to its schema length by `IncidentPolicy.boundString` — a symbol
/// cut at 400 characters is still the same symbol. **An identity is not.**
/// `userId` is an opaque key from the customer's own auth system, so a
/// truncated one is a DIFFERENT user: it would attribute a real session to the
/// wrong person or to nobody, and a cross-reference against the customer's user
/// table would miss silently. There is deliberately no `boundUserId` here.
///
/// ACCEPTED VERBATIM — NOT TRIMMED. Blankness is judged over the whole
/// string; the accepted VALUE is never altered. The browser producer writes
/// this same column verbatim, so trimming here would store one customer key as
/// two different values in one column depending on which SDK sent it. And a
/// 255-`u` run followed by a space is 256 code points — inside the bound —
/// while trimming first would store a different, 255-character id.
///
/// CODE POINTS — `unicodeScalars`, NOT `count`. `String.count` counts
/// extended GRAPHEME CLUSTERS, and one cluster can hold many code points, so a
/// family emoji would measure 1 against a `maxLength` the server counts as 7.
/// This is the same trap `IncidentPolicy.boundString` records paying for once:
/// "`String.prefix(n)` counts extended GRAPHEME CLUSTERS". The shared vectors
/// are ASCII and cannot see the difference, so `IdentityPolicyParityTests` pins
/// a non-ASCII case in Swift.
///
/// THERE IS NO SIGN-OUT. The server's merge is incoming-wins-if-higher-
/// sequence over a NON-NULL identity; an envelope naming no user does not clear
/// a stored one, so a producer cannot express "this user signed out" through
/// this field at all. A blank argument is therefore `.blank` — refused — rather
/// than a clear: clearing locally would make the device stop stamping while the
/// server kept the old value, with nothing saying so.
public enum IdentityPolicy {

    /// `userId` max length, in the unit the contract uses. Mirrors the deployed
    /// server's `USER_ID_MAX`; the column is 1024 bytes wide (256 x 4, UTF-8
    /// worst case) precisely so every value accepted here is storable.
    public static let userIdMax = 256

    /// The verdicts, as a closed set. A caller branches on these rather than on
    /// a boolean, because "refused because it is too long" and "refused because
    /// it names nobody" are different facts and an operator needs to tell them
    /// apart. The raw value is the name the shared vectors use.
    public enum Verdict: String {
        case accepted
        case blank
        case tooLong = "too_long"
        case notAString = "not_a_string"
    }

    /// One classification.
    ///
    /// `value` IS NIL FOR EVERY REFUSAL. A refused identity is still an end
    /// user's key, so a caller that logs this result cannot accidentally log
    /// the identity: `length` and `userIdMax` are disclosable, the value is not.
    ///
    /// `length` is nil only when the candidate was absent — an unmeasurable
    /// value has no length.
    public struct Decision: Equatable {
        public let verdict: Verdict
        public let value: String?
        public let length: Int?

        public var acceptable: Bool { verdict == .accepted }

        public init(verdict: Verdict, value: String?, length: Int?) {
            self.verdict = verdict
            self.value = value
            self.length = length
        }
    }

    /// BLANKNESS IS ENUMERATED, NOT DELEGATED — the sharpest edge here.
    ///
    /// The obvious implementation is `trimmingCharacters(in:.whitespacesAndNewlines)`.
    /// That is a DIFFERENT PREDICATE from the server's JS `trim`, measured
    /// rather than assumed:
    ///
    /// U+0085 NEL JS trim KEEPS it -> not blank | `.whitespacesAndNewlines`: blank
    /// U+FEFF BOM JS trim REMOVES it -> blank | `.whitespacesAndNewlines`: not blank
    ///
    /// The server is JS and is the only implementation that decides what gets
    /// stored, and the disagreement is SILENT in the dangerous direction: a
    /// producer accepting `"\u{FEFF}"` would report success to the host app
    /// while the server stored no identity and nothing said why.
    ///
    /// So the set is written down — exactly ECMAScript `WhiteSpace` ∪
    /// `LineTerminator`, proven equal to JS `trim` over every Unicode scalar.
    static let blankScalars: Set<UInt32> = [
        // ECMAScript WhiteSpace, the non-Zs members
        0x0009, 0x000B, 0x000C,
        0xFEFF,                   // BOM — whitespace to JS, NOT to Foundation
        // ECMAScript LineTerminator
        0x000A, 0x000D, 0x2028, 0x2029,
        // Unicode Space_Separator (Zs)
        0x0020, 0x00A0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x202F, 0x205F, 0x3000,
        // U+0085 (NEL) and U+200B (ZWSP) are DELIBERATELY ABSENT — JS `trim`
        // keeps both, so an identity made only of them is a real identity.
    ]

    /// Is this candidate blank — i.e. does it name nobody?
    ///
    /// True for the empty string and for any string whose every scalar is in
    /// `blankScalars`. Equal to the server's `raw.trim.length === 0` by
    /// construction rather than by coincidence.
    public static func isBlankIdentity(_ raw: String) -> Bool {
        for scalar in raw.unicodeScalars where !blankScalars.contains(scalar.value) {
            return false
        }
        return true
    }

    /// The identity's length IN CODE POINTS — `unicodeScalars`, so one emoji is
    /// 1 and a family emoji is its true scalar count rather than 1 cluster.
    public static func userIdLength(_ raw: String) -> Int {
        raw.unicodeScalars.count
    }

    /// Classify one candidate identity. `nil` is `.notAString` — the state a JS
    /// caller reaches by handing the bridge anything that is not a string.
    public static func classifyUserId(_ raw: String?) -> Decision {
        guard let raw else { return Decision(verdict: .notAString, value: nil, length: nil) }
        let length = userIdLength(raw)
        // BLANK BEFORE BOUND, matching the canonical order: a 300-space
        // string is `.blank`, not `.tooLong`. Reversing these two would report
        // a length problem for a value that names nobody.
        if isBlankIdentity(raw) { return Decision(verdict: .blank, value: nil, length: length) }
        if length > userIdMax { return Decision(verdict: .tooLong, value: nil, length: length) }
        return Decision(verdict: .accepted, value: raw, length: length)
    }

    /// Would this candidate reach the wire? Sugar over `classifyUserId`.
    public static func userIdIsAcceptable(_ raw: String?) -> Bool {
        classifyUserId(raw).acceptable
    }
}

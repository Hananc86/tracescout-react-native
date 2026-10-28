import Foundation

// THE HOST-DECLARED WEBVIEW ORIGIN ALLOWLIST, AND THE LIFETIME OF THE TRUST IT
// GRANTS — the S5 contract decision of 2026-10-21,
// implemented as two pure decisions so both run in any venue with no WebKit.
//
// ## WHY THIS IS STRICTER THAN `RnWebViewSegment.normalizeOrigin`, AND WHY
// ## THAT FUNCTION WAS NOT NARROWED INSTEAD
//
// The contract says the host must declare "an allowlist of exact HTTPS
// origins". The shared transport-level normalizer deliberately admits
// `http://localhost` — and that is not an oversight to correct here: it is a
// FROZEN CROSS-PLATFORM CONTRACT. `parity-vectors/webview-segment-vectors.json`
// carries a vector named, verbatim, "http://localhost is admitted for local
// development", and that file is consumed by BOTH this Swift suite and the
// Kotlin twin's. Narrowing `normalizeOrigin` would therefore change an accepted
// shared decision and the other platform's certified behaviour, to express a
// rule that belongs one layer up.
//
// So the two layers answer two different questions, and the relationship
// between them is enforced rather than assumed:
//
// normalizeOrigin "could this string ever be an origin we transact with?"
// admit (here) "may a HOST DECLARE this in its allowlist?"
//
// `admit` requires the shared normalizer to accept the entry FIRST and then
// adds its own conditions. It is a strict subset by construction, so this layer
// can never admit something the shared decision would refuse — and a future
// widening of the shared grammar cannot silently widen a host policy.
public enum RnWebViewOriginPolicy {

    /// The one scheme a host may declare. Exact, lowercase, with the separator,
    /// so no caller can assemble a near-miss out of parts.
    public static let REQUIRED_SCHEME = "https://"

    /// The admissible port range. The shared grammar's `[0-9]{1,5}` admits
    /// `:0` and `:99999`, neither of which is a port a browser can present.
    /// Admitting an unusable port into an allowlist means the operator reviewed
    /// a line that can never match, so it is refused rather than carried.
    public static let MIN_PORT = 1
    public static let MAX_PORT = 65535

    /// What the host declared, as this layer reads it.
    ///
    /// THREE OUTCOMES, AND `absent` IS NOT `declared([])`. "No policy at
    /// all" and "a policy that admits nothing" are different facts about the
    /// host's intent, and collapsing them would report an unconfigured app and
    /// a deliberately-empty allowlist with one word.
    public enum Declared: Equatable {
        /// A reviewed, usable allowlist. May be empty — see `admit`.
        case declared([String])
        /// No allowlist was supplied at all.
        case absent
        /// An allowlist was supplied and is unusable. Carries the reason.
        case refused(String)
    }

    /// Read a host-declared allowlist.
    ///
    /// ONE UNUSABLE ENTRY REFUSES THE WHOLE LIST. This is the
    /// `normalizePolicy` precedent and the reasoning is the same: silently
    /// keeping the entries that happened to parse would shrink the intended set
    /// into something the operator never reviewed, and a shrunk allowlist looks
    /// exactly like a working one until the day the dropped origin is the one
    /// that matters.
    ///
    /// NOTHING IS INFERRED. A bare host (`example.com`), an `http://` entry,
    /// a wildcard, a path, a trailing slash and a userinfo are each refused by
    /// NAME rather than repaired — "no wildcard, inferred, JS-supplied, or
    /// implicit allow-all policy" is the contract, and every repair is a guess
    /// about what the operator meant.
    public static func admit(_ raw: [String]?) -> Declared {
        guard let raw = raw else { return .absent }
        var out: [String] = []
        for entry in raw {
            switch admitOne(entry) {
            case .bad(let why): return .refused(why)
            case .ok(let origin):
                if !out.contains(origin) { out.append(origin) }
            }
        }
        return .declared(out)
    }

    /// The verdict on ONE entry.
    ///
    /// A NAMED TWO-CASE ENUM RATHER THAN AN OPTIONAL, because the refusal
    /// must carry its reason: a nil would make every rejection
    /// indistinguishable, and the operator's next question is always "which
    /// line, and why". (Not `Result` — its failure type must conform to
    /// `Error`, and inventing an error type to carry one string would add a
    /// vocabulary nobody else uses.)
    enum EntryVerdict: Equatable {
        case ok(String)
        case bad(String)
    }

    static func admitOne(_ raw: String) -> EntryVerdict {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return .bad("origin_entry_empty") }
        let lower = s.lowercased()

        // THE WILDCARD CHECK IS FIRST AND IT IS NOT REDUNDANT WITH THE
        // GRAMMAR. `*` cannot pass the shared host charset, so the grammar
        // already refuses it — but a wildcard is the one entry whose INTENT is
        // unmistakable, and naming it specifically is what turns a confusing
        // `origin_entry_malformed` into a reason the operator can act on. It is
        // checked on the raw entry, so `https://*.example.com` is named too.
        if lower.contains("*") { return .bad("origin_entry_wildcard") }

        // SCHEME BEFORE GRAMMAR, DELIBERATELY. The shared grammar accepts
        // `http://` too, so asking it first would report a declared `http://`
        // origin as well-formed and then refuse it for a reason that does not
        // name the scheme. A host declaring `http://` has made a scheme
        // mistake, and that is what it should be told.
        guard lower.hasPrefix(REQUIRED_SCHEME) else {
            return .bad("origin_entry_not_https")
        }

        // The shared transport grammar is the FLOOR: this layer may only ever
        // be stricter. Asking it here is what makes "a strict subset" a
        // property of the code rather than a claim in a comment.
        guard let normalized = RnWebViewSegment.normalizeOrigin(lower) else {
            return .bad("origin_entry_malformed")
        }
        // The shared normalizer lowercases but may otherwise reshape; an
        // entry that is not already in its normal form is ambiguous about what
        // the operator declared, so it is refused rather than rewritten.
        guard normalized == lower else { return .bad("origin_entry_not_canonical") }

        if let port = declaredPort(lower) {
            guard port >= MIN_PORT && port <= MAX_PORT else {
                return .bad("origin_entry_port_out_of_range")
            }
        }
        return .ok(normalized)
    }

    /// The explicit port, or nil when none was written. Returns nil for an
    /// unparseable port too — the grammar has already established it is digits,
    /// so the only way here is an overflow, which `MAX_PORT` would refuse
    /// anyway; reporting nil would call it "no port" and admit it.
    static func declaredPort(_ normalized: String) -> Int? {
        let afterScheme = normalized.dropFirst(REQUIRED_SCHEME.count)
        guard let colon = afterScheme.lastIndex(of: ":") else { return nil }
        let digits = afterScheme[afterScheme.index(after: colon)...]
        if digits.isEmpty { return nil }
        // An out-of-range value must not read as "no port declared".
        return Int(digits) ?? (MAX_PORT + 1)
    }
}

/// WHETHER A CORRELATION ALREADY ADMITTED IS STILL ENTITLED TO ITS TRUST.
///
/// The contract's last clause is "revocation and navigation must not retain
/// stale trust". Admission is per message and already correct; what had no
/// answer is the LIFETIME of an admission after the moment it was granted. An
/// admitted segment is carried on every event of the session, so a segment
/// admitted for `https://a.example.com` and still carried after the web view
/// navigated to `https://b.example.com` is a false datum in exactly the row
/// this lane exists to produce.
///
/// PURE, AND THAT IS WHAT MAKES IT PROVABLE. It reads no web view and
/// imports no WebKit; the caller supplies the current origin and the live
/// policy, so every branch runs in a venue with no WebKit at all.
public enum RnWebViewTrustLifetime {

    public enum Verdict: Equatable {
        /// The admission is still current.
        case retain
        /// The admission is stale and must be dropped. Carries the reason.
        case drop(String)
        /// Nothing was admitted, so there is no trust to revalidate.
        case noSegment
    }

    /// THE ORDER IS THE ARGUMENT, AND EVERY BRANCH FAILS CLOSED.
    ///
    /// The host's standing decision is asked BEFORE the page is read, because
    /// it is authoritative and needs no observation that can fail: a revoked
    /// origin must be reported as revoked even when the current origin cannot
    /// be read at all. Reporting such a case as "unreadable" would describe our
    /// own instrument where the operator's own withdrawal is the fact.
    ///
    /// AN UNREADABLE CURRENT ORIGIN DROPS. It is tempting to retain on a
    /// failed read, because dropping costs a correlation that may well still be
    /// valid — but "I cannot establish that this is still current" IS stale
    /// trust, and the cost of retaining is a segment row asserting a
    /// correlation nobody can prove. An absent segment row is the specification's third
    /// outcome and is honest; a wrong one is not.
    public static func decide(admittedOrigin: String?,
                              currentOrigin: String?,
                              livePolicy: [String]?) -> Verdict {
        guard let admitted = admittedOrigin else { return .noSegment }

        // (1) The host's standing decision.
        guard let live = RnWebViewSegment.normalizePolicy(livePolicy) else {
            return .drop("origin_policy_absent")
        }
        if !live.contains(admitted) { return .drop("origin_revoked") }

        // (2) The page, as it is NOW.
        guard let currentRaw = currentOrigin else { return .drop("origin_unreadable") }
        guard let current = RnWebViewSegment.normalizeOrigin(currentRaw) else {
            return .drop("origin_malformed")
        }
        if current != admitted { return .drop("origin_navigated") }

        return .retain
    }
}

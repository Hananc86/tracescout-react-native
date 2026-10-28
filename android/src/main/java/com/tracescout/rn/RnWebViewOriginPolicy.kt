package com.tracescout.rn

/**
 * THE HOST-DECLARED WEBVIEW ORIGIN ALLOWLIST, AND THE LIFETIME OF THE TRUST IT
 * GRANTS — the S5 contract decision of 2026-10-21,
 * as the KOTLIN twin of `ios/Sources/TraceScoutRN/RnWebViewOriginPolicy.swift`.
 *
 * Both are pure decisions with no platform dependency, so both run in any venue
 * here, a plain JVM unit test with no Android and no emulator.
 *
 * ## WHY THIS IS STRICTER THAN `RnWebViewSegment.normalizeOrigin`, AND WHY
 * ## THAT FUNCTION WAS NOT NARROWED INSTEAD
 *
 * The contract says the host must declare "an allowlist of exact HTTPS
 * origins". The shared transport-level normalizer deliberately admits
 * `http://localhost` — and that is not an oversight to correct here: it is a
 * FROZEN CROSS-PLATFORM CONTRACT. `parity-vectors/webview-segment-vectors.json`
 * carries a vector named, verbatim, "http://localhost is admitted for local
 * development", and that file is consumed by BOTH this Kotlin suite and the
 * Swift twin's. Narrowing [RnWebViewSegment.normalizeOrigin] would therefore
 * change an accepted shared decision and the OTHER platform's certified
 * behaviour, to express a rule that belongs one layer up.
 *
 * So the two layers answer two different questions, and the relationship
 * between them is enforced rather than assumed:
 *
 * ```
 * normalizeOrigin "could this string ever be an origin we transact with?"
 * admit (here) "may a HOST DECLARE this in its allowlist?"
 * ```
 *
 * [admit] requires the shared normalizer to accept the entry FIRST and then
 * adds its own conditions. It is a strict subset by construction, so this layer
 * can never admit something the shared decision would refuse — and a future
 * widening of the shared grammar cannot silently widen a host policy.
 *
 * ## KOTLIN CASE NAMES DIFFER FROM SWIFT'S; THE WIRE REASONS DO NOT
 *
 * Swift's `Declared.declared([String])` cannot be spelled that way here — a
 * nested class may not share its sealed parent's name — so the Kotlin case is
 * [Declared.Allowlist]. That is a LANGUAGE difference and nothing else: the
 * reason codes, the constants and the order of the checks are the contract, and
 * `RnWebViewOriginPolicyParityTest` reads them out of the Swift source rather
 * than trusting this paragraph.
 */
object RnWebViewOriginPolicy {

    /**
 * The one scheme a host may declare. Exact, lowercase, with the separator,
 * so no caller can assemble a near-miss out of parts.
 */
    const val REQUIRED_SCHEME = "https://"

    /**
 * The admissible port range. The shared grammar's `[0-9]{1,5}` admits
 * `:0` and `:99999`, neither of which is a port a browser can present.
 * Admitting an unusable port into an allowlist means the operator reviewed
 * a line that can never match, so it is refused rather than carried.
 */
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    /**
 * What the host declared, as this layer reads it.
 *
 * THREE OUTCOMES, AND [Absent] IS NOT `Allowlist(emptyList)`. "No
 * policy at all" and "a policy that admits nothing" are different facts
 * about the host's intent, and collapsing them would report an
 * unconfigured app and a deliberately-empty allowlist with one word.
 */
    sealed class Declared {
        /** A reviewed, usable allowlist. May be empty — see [admit]. */
        data class Allowlist(val origins: List<String>) : Declared()

        /** No allowlist was supplied at all. */
        data object Absent : Declared()

        /** An allowlist was supplied and is unusable. Carries the reason. */
        data class Refused(val why: String) : Declared()
    }

    /**
 * Read a host-declared allowlist.
 *
 * ONE UNUSABLE ENTRY REFUSES THE WHOLE LIST. This is the
 * [RnWebViewSegment.normalizePolicy] precedent and the reasoning is the
 * same: silently keeping the entries that happened to parse would shrink
 * the intended set into something the operator never reviewed, and a
 * shrunk allowlist looks exactly like a working one until the day the
 * dropped origin is the one that matters.
 *
 * NOTHING IS INFERRED. A bare host (`example.com`), an `http://` entry,
 * a wildcard, a path, a trailing slash and a userinfo are each refused by
 * NAME rather than repaired — "no wildcard, inferred, JS-supplied, or
 * implicit allow-all policy" is the contract, and every repair is a guess
 * about what the operator meant.
 */
    @JvmStatic
    fun admit(raw: List<String>?): Declared {
        if (raw == null) return Declared.Absent
        val out = ArrayList<String>()
        for (entry in raw) {
            when (val v = admitOne(entry)) {
                is EntryVerdict.Bad -> return Declared.Refused(v.why)
                is EntryVerdict.Ok -> if (!out.contains(v.origin)) out.add(v.origin)
            }
        }
        return Declared.Allowlist(out)
    }

    /**
 * The verdict on ONE entry.
 *
 * A NAMED TWO-CASE TYPE RATHER THAN A NULLABLE STRING, because the
 * refusal must carry its reason: a null would make every rejection
 * indistinguishable, and the operator's next question is always "which
 * line, and why".
 */
    sealed class EntryVerdict {
        data class Ok(val origin: String) : EntryVerdict()
        data class Bad(val why: String) : EntryVerdict()
    }

    @JvmStatic
    fun admitOne(raw: String): EntryVerdict {
        val s = raw.trim()
        if (s.isEmpty()) return EntryVerdict.Bad("origin_entry_empty")
        val lower = s.lowercase()

        // THE WILDCARD CHECK IS FIRST AND IT IS NOT REDUNDANT WITH THE
        // GRAMMAR. `*` cannot pass the shared host charset, so the grammar
        // already refuses it — but a wildcard is the one entry whose INTENT is
        // unmistakable, and naming it specifically is what turns a confusing
        // `origin_entry_malformed` into a reason the operator can act on. It is
        // checked before the grammar, so `https://*.example.com` is named too.
        if (lower.contains("*")) return EntryVerdict.Bad("origin_entry_wildcard")

        // SCHEME BEFORE GRAMMAR, DELIBERATELY. The shared grammar accepts
        // `http://` too, so asking it first would report a declared `http://`
        // origin as well-formed and then refuse it for a reason that does not
        // name the scheme. A host declaring `http://` has made a scheme
        // mistake, and that is what it should be told.
        if (!lower.startsWith(REQUIRED_SCHEME)) {
            return EntryVerdict.Bad("origin_entry_not_https")
        }

        // The shared transport grammar is the FLOOR: this layer may only ever
        // be stricter. Asking it here is what makes "a strict subset" a
        // property of the code rather than a claim in a comment.
        val normalized = RnWebViewSegment.normalizeOrigin(lower)
            ?: return EntryVerdict.Bad("origin_entry_malformed")

        // The shared normalizer lowercases but may otherwise reshape; an
        // entry that is not already in its normal form is ambiguous about what
        // the operator declared, so it is refused rather than rewritten.
        if (normalized != lower) return EntryVerdict.Bad("origin_entry_not_canonical")

        val port = declaredPort(lower)
        if (port != null && (port < MIN_PORT || port > MAX_PORT)) {
            return EntryVerdict.Bad("origin_entry_port_out_of_range")
        }
        return EntryVerdict.Ok(normalized)
    }

    /**
 * The explicit port, or null when none was written.
 *
 * AN UNPARSEABLE PORT READS AS OUT-OF-RANGE, NEVER AS "NO PORT". The
 * grammar has already established the characters are digits, so the only
 * way to fail parsing here is an overflow — and returning null would call
 * that "no port declared" and admit it.
 *
 * Its contract is a [REQUIRED_SCHEME]-prefixed origin; [admitOne] has
 * already established that before calling.
 */
    @JvmStatic
    fun declaredPort(normalized: String): Int? {
        val afterScheme = normalized.removePrefix(REQUIRED_SCHEME)
        val colon = afterScheme.lastIndexOf(':')
        if (colon < 0) return null
        val digits = afterScheme.substring(colon + 1)
        if (digits.isEmpty()) return null
        // An out-of-range value must not read as "no port declared".
        return digits.toIntOrNull() ?: (MAX_PORT + 1)
    }
}

/**
 * WHETHER A CORRELATION ALREADY ADMITTED IS STILL ENTITLED TO ITS TRUST — the
 * Kotlin twin of Swift's `RnWebViewTrustLifetime`.
 *
 * The contract's last clause is "revocation and navigation must not retain
 * stale trust". Admission is per message and already correct; what had no
 * answer is the LIFETIME of an admission after the moment it was granted. An
 * admitted segment is carried on every event of the session, so a segment
 * admitted for `https://a.example.com` and still carried after the web view
 * navigated to `https://b.example.com` is a false datum in exactly the row
 * this lane exists to produce.
 *
 * PURE, AND THAT IS WHAT MAKES IT PROVABLE. It reads no web view and imports
 * nothing from Android; the caller supplies the current origin and the live
 * policy, so every branch runs in a plain JVM test.
 */
object RnWebViewTrustLifetime {

    sealed class Verdict {
        /** The admission is still current. */
        data object Retain : Verdict()

        /** The admission is stale and must be dropped. Carries the reason. */
        data class Drop(val why: String) : Verdict()

        /** Nothing was admitted, so there is no trust to revalidate. */
        data object NoSegment : Verdict()
    }

    /**
 * THE ORDER IS THE ARGUMENT, AND EVERY BRANCH FAILS CLOSED.
 *
 * The host's standing decision is asked BEFORE the page is read, because
 * it is authoritative and needs no observation that can fail: a revoked
 * origin must be reported as revoked even when the current origin cannot be
 * read at all. Reporting such a case as "unreadable" would describe our own
 * instrument where the operator's own withdrawal is the fact.
 *
 * AN UNREADABLE CURRENT ORIGIN DROPS. It is tempting to retain on a
 * failed read, because dropping costs a correlation that may well still be
 * valid — but "I cannot establish that this is still current" IS stale
 * trust, and the cost of retaining is a segment row asserting a
 * correlation nobody can prove. An absent segment row is the specification's third
 * outcome and is honest; a wrong one is not.
 *
 * [livePolicy] IS CONSULTED ONLY TO RETIRE. There is no path in this
 * function that admits anything, which is what makes a mid-session WIDENING
 * unreachable from here while a REVOCATION lands at once.
 */
    @JvmStatic
    fun decide(
        admittedOrigin: String?,
        currentOrigin: String?,
        livePolicy: List<String>?,
    ): Verdict {
        val admitted = admittedOrigin ?: return Verdict.NoSegment

        // (1) The host's standing decision.
        val live = RnWebViewSegment.normalizePolicy(livePolicy)
            ?: return Verdict.Drop("origin_policy_absent")
        if (admitted !in live) return Verdict.Drop("origin_revoked")

        // (2) The page, as it is NOW.
        val currentRaw = currentOrigin ?: return Verdict.Drop("origin_unreadable")
        val current = RnWebViewSegment.normalizeOrigin(currentRaw)
            ?: return Verdict.Drop("origin_malformed")
        if (current != admitted) return Verdict.Drop("origin_navigated")

        return Verdict.Retain
    }
}

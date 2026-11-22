package com.tracescout.rn

/**
 * MOBILE IDENTITY v1 (G2) — what `identify` may put on the wire, in ONE place.
 *
 * The Kotlin twin of `mobile-protocol/src/identity-policy.cjs`, pinned to
 * `parity-vectors/identity-policy-vectors.json`. A platform disagreement fails
 * on BYTES, not on three people reading one paragraph.
 *
 * IT REFUSES; IT DOES NOT BOUND. Every other string in this protocol is
 * truncated to its schema length by [IncidentPolicy]'s `bound` — a symbol cut
 * at 400 characters is still the same symbol. **An identity is not.** `userId`
 * is an opaque key from the customer's own auth system, so a truncated one is a
 * DIFFERENT user: it would attribute a real session to the wrong person or to
 * nobody, and a cross-reference against the customer's user table would miss
 * silently. There is deliberately no `boundUserId` here.
 *
 * ACCEPTED VERBATIM — NOT TRIMMED. Blankness is judged after a trim; the
 * accepted VALUE never is. The browser producer writes this same column
 * verbatim, so trimming here would store one customer key as two different
 * values in one column depending on which SDK sent it. And a 255-`u` run
 * followed by a space is 256 code points — inside the bound — while trimming
 * first would store a different, 255-character id.
 *
 * CODE POINTS, because that is what JSON Schema `maxLength` counts.
 * `String.length` counts UTF-16 code UNITS, so one emoji counts as two and a
 * contract-valid 129-emoji identity would be refused as "258 characters". The
 * shared vectors are ASCII and cannot see the difference, so
 * `IdentityPolicyParityTest` pins a non-ASCII case in Kotlin.
 *
 * THERE IS NO SIGN-OUT. The server's merge is incoming-wins-if-higher-
 * sequence over a NON-NULL identity; an envelope naming no user does not clear
 * a stored one, so a producer cannot express "this user signed out" through
 * this field at all. A blank argument is therefore [Verdict.BLANK] — refused —
 * rather than a clear: clearing locally would make the device stop stamping
 * while the server kept the old value, with nothing saying so.
 */
object IdentityPolicy {

    /**
 * `userId` max length, in the unit the contract uses. Mirrors the deployed
 * server's `USER_ID_MAX`; the column is 1024 bytes wide (256 x 4, UTF-8
 * worst case) precisely so every value accepted here is storable.
 */
    const val USER_ID_MAX: Int = 256

    /**
 * The verdicts, as a closed set. A caller branches on these rather than on
 * a boolean, because "refused because it is too long" and "refused because
 * it names nobody" are different facts and an operator needs to tell them
 * apart. `wire` is the name the shared vectors use.
 */
    enum class Verdict(val wire: String) {
        ACCEPTED("accepted"),
        BLANK("blank"),
        TOO_LONG("too_long"),
        NOT_A_STRING("not_a_string"),
    }

    /**
 * One classification.
 *
 * [value] IS NULL FOR EVERY REFUSAL. A refused identity is still an end
 * user's key, so a caller that logs this result cannot accidentally log the
 * identity: [length] and [USER_ID_MAX] are disclosable, the value is not.
 *
 * [length] is null only when the candidate was not a string at all — an
 * unmeasurable value has no length.
 */
    data class Decision(
        val verdict: Verdict,
        val value: String?,
        val length: Int?,
    ) {
        val acceptable: Boolean get() = verdict == Verdict.ACCEPTED
    }

    /**
 * BLANKNESS IS ENUMERATED, NOT DELEGATED — the sharpest edge here.
 *
 * The obvious implementation is Kotlin's own `String.isBlank`. That is a
 * DIFFERENT PREDICATE from the server's JS `trim`, measured rather than
 * assumed:
 *
 * U+FEFF BOM JS trim REMOVES it -> blank | `isBlank`: NOT blank
 * U+0085 NEL JS trim KEEPS it -> not blank | `isBlank`: not blank
 *
 * Only the first row divides KOTLIN from the server -- `isBlank` agrees
 * on NEL, because `Character.isWhitespace` excludes it (category Cc). That
 * was measured after an earlier version of this comment claimed otherwise
 * and `IdentityPolicyParityTest` failed on its own sanity assertion.
 *
 * Enumerating is still the only safe answer, because Swift's
 * `.whitespacesAndNewlines` differs from the server on BOTH rows: one twin
 * happening to agree on one code point is not a shared rule.
 *
 * The server is JS and is the only implementation that decides what gets
 * stored, and the disagreement is SILENT in the dangerous direction: a
 * producer accepting `"﻿"` would report success to the host app while
 * `deriveUserId` returned null, the session kept no identity, and nothing
 * anywhere said why.
 *
 * So the set is written down — exactly ECMAScript `WhiteSpace` ∪
 * `LineTerminator`, proven equal to JS `trim` over every Unicode scalar.
 */
    val BLANK_CODE_POINTS: Set<Int> = setOf(
        // ECMAScript WhiteSpace, the non-Zs members
        0x0009, 0x000B, 0x000C,
        0xFEFF, // BOM — whitespace to JS, NOT to Kotlin's isBlank
        // ECMAScript LineTerminator
        0x000A, 0x000D, 0x2028, 0x2029,
        // Unicode Space_Separator (Zs)
        0x0020, 0x00A0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x202F, 0x205F, 0x3000,
        // U+0085 (NEL) and U+200B (ZWSP) are DELIBERATELY ABSENT — JS `trim`
        // keeps both, so an identity made only of them is a real identity.
    )

    /**
 * Is this candidate blank — i.e. does it name nobody?
 *
 * True for the empty string and for any string whose every code point is in
 * [BLANK_CODE_POINTS]. Equal to the server's `raw.trim.length === 0` by
 * construction rather than by coincidence.
 *
 * ITERATES CODE POINTS, not `Char`s. A `forEach` over chars would split
 * a surrogate pair into two values that are in no set, which happens to
 * give the right answer here (a pair is never blank) and would be the wrong
 * mechanism to copy anywhere else in this file.
 */
    fun isBlankIdentity(raw: String): Boolean {
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (cp !in BLANK_CODE_POINTS) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /**
 * The identity's length IN CODE POINTS — `codePointCount` collapses
 * surrogate pairs, so one emoji is 1 and not 2.
 */
    fun userIdLength(raw: String): Int = raw.codePointCount(0, raw.length)

    /**
 * Classify one candidate identity. `null` is [Verdict.NOT_A_STRING] — the
 * state a JS caller reaches by handing the bridge anything that is not a
 * string, which Kotlin's own type system cannot otherwise express.
 *
 * ONE ENTRY POINT, DELIBERATELY. An overloaded `String` / `String?`
 * pair reads tidier and is a trap: the non-null branch would dispatch by
 * smart cast, and a future edit that defeated the smart cast turns the
 * delegation into unbounded recursion inside a host app's call.
 */
    fun classifyUserId(raw: String?): Decision {
        if (raw == null) return Decision(Verdict.NOT_A_STRING, null, null)
        val length = userIdLength(raw)
        // BLANK BEFORE BOUND, matching the canonical order: a 300-space
        // string is BLANK, not TOO_LONG. Reversing these two lines would
        // report "too long" for a value that names nobody.
        if (isBlankIdentity(raw)) return Decision(Verdict.BLANK, null, length)
        if (length > USER_ID_MAX) return Decision(Verdict.TOO_LONG, null, length)
        return Decision(Verdict.ACCEPTED, raw, length)
    }

    /** Would this candidate reach the wire? Sugar over [classifyUserId]. */
    fun userIdIsAcceptable(raw: String?): Boolean = classifyUserId(raw).acceptable
}

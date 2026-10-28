package com.tracescout.mobile.internal.queue

import com.tracescout.mobile.internal.loss.LossAuthority

/**
 * F4-P2 — **THE ONE NAMED EXACT-CLOSURE PREDICATE** (a product decision as restated
 * 2026-10-01, R9 conditions 5–8; reviewer checklist the specification "U2" and X2).
 *
 * A session CLOSES EXACTLY when the persisted index plus the durable authority
 * state account for EVERY one of its sequences EXACTLY ONCE, with NO unknown or
 * incomplete state. Everything the reconciler decides — the C12 exact-closure
 * arm, the CLOSED decision, the post-consume re-scan, and the R9 clear — asks
 * THIS function; there is no second home for the question.
 *
 * [evaluate] returns the ORDERED list of disqualifiers that are true (empty =
 * closes exactly). The order is the order of authority: a gate-level fact
 * (marker / erasure / in-flight), then the scan, then a pending reconciliation,
 * then the session's own durable state, then its arithmetic. Each disqualifier
 * is one of the ruled set and nothing is folded:
 *
 * | reason | what it names |
 * |---|---|
 * | `gated:<why>` | an unresolved bracket marker, `ERASURE_REQUESTED`, a frozen in-flight descriptor naming an absent record |
 * | `scan_incomplete:<why>` | `Inspect.Failed`, interior corruption, an unlistable root / directory, a key that cannot be obtained — the index could not be read COMPLETELY |
 * | `pending_reconciliation` | a durable reconciliation fact that has not reached CONSUMED |
 * | `no_authority` | the session has NO persisted index at all (a survivor nothing owns) |
 * | `undecodable_family` | an owned entry whose persisted family is outside the canonical vocabulary |
 * | `unresolved_intent` | an eviction / quarantine intent the bracket's recovery still owns |
 * | `prepared_entry` | a PREPARED admission — unresolved under ANY read (never settled from a lossy absence, R9/Q3) |
 * | `duplicate_attribution` | one sequence decoded twice in the image, or decoded AND already settled LOST |
 * | `unknown_sequence:<n>` | a decoded survivor the index does not own — missing durable authority state |
 * | `high_water_unknown:<reason>` | the session is disabled under a reason whose durable `HighWaterConfidence` is UNKNOWN — EXCEPT the exact owner-bound `UNATTRIBUTABLE_GAP` disable THIS reconciliation chain proves it owns (the one the R9 clear removes; see below) |
 * | `missing:<n>` | ADMITTED, undelivered sequences with no decoded survivor |
 *
 * THE ONE READING STATED FOR THE REVIEWER: `UNATTRIBUTABLE_GAP` classifies to
 * `HighWaterConfidence.UNKNOWN` like every reason (RULING A2), so read literally
 * the predicate could never close a session P2 itself disabled, and the R9 clear
 * which REQUIRES exact closure — could never fire: a successfully reconciled
 * session would stay disabled forever, the exact outcome R9 forbids. The
 * disqualifier therefore exempts EXACTLY the disable whose durable owner the
 * authority carries AND the durable fact chain names ([Input.p2Owner]); any other
 * disable — a legacy one, another reason, another owner, an owner the fact
 * cannot vouch for — is `high_water_unknown` and the session never closes.
 */
internal object ExactClosure {

    internal data class Input(
        /** A gate-level refusal (marker / erasure / in-flight ambiguity), or null. */
        val gate: String?,
        /** The index scan's incompleteness, or null when COMPLETE. */
        val scanIncomplete: String?,
        /** A durable reconciliation fact not yet CONSUMED. */
        val pendingReconciliation: Boolean,
        /** The session's persisted index, or null when none exists. */
        val index: LossAuthority.SessionIndex?,
        /** The sequences DECODED from the image for this session, duplicates included. */
        val survivors: List<Long>,
        /** The owner id of the `UNATTRIBUTABLE_GAP` disable the durable fact chain proves THIS reconciliation owns, or null. */
        val p2Owner: String?,
    )

    internal const val UNATTRIBUTABLE_GAP: String = "UNATTRIBUTABLE_GAP"

    /** The disqualifiers that hold, in order of authority; empty means the session closes exactly. */
    internal fun evaluate(i: Input): List<String> {
        val out = ArrayList<String>()
        i.gate?.let { out.add("gated:$it") }
        i.scanIncomplete?.let { out.add("scan_incomplete:$it") }
        if (i.pendingReconciliation) out.add("pending_reconciliation")
        val ix = i.index
        if (ix == null) { out.add("no_authority"); return out }
        if (!ix.familiesCanonical) out.add("undecodable_family")
        if (ix.unresolvedIntents > 0) out.add("unresolved_intent")
        if (ix.prepared.isNotEmpty()) out.add("prepared_entry")
        val seen = HashSet<Long>()
        for (q in i.survivors) {
            if (!seen.add(q)) { out.add("duplicate_attribution"); break }
        }
        for (q in seen) {
            if (q !in ix.families) { out.add("unknown_sequence:$q"); break }
        }
        if (seen.any { it in ix.settledLost }) out.add("duplicate_attribution:lost_and_present")
        if (ix.disabled != null) {
            // The DURABLE confidence (RULING A2) is what is consumed — never the reason name.
            val owned = ix.disabled == UNATTRIBUTABLE_GAP && ix.disabledOwner != null && ix.disabledOwner == i.p2Owner
            if (!owned) out.add(if (ix.highWaterConfidence == "KNOWN") "disabled:${ix.disabled}" else "high_water_unknown:${ix.disabled}")
        }
        val missing = ix.admittedUndelivered.count { it !in seen }
        if (missing > 0) out.add("missing:$missing")
        return out
    }

    /** The predicate: no disqualifier holds. */
    internal fun closesExactly(i: Input): Boolean = evaluate(i).isEmpty()
}

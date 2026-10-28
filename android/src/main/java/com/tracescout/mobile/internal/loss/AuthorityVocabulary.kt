package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. `public` only because Kotlin `internal` is
 * compilation-module scoped; B10 pins that no type
 * here appears in a public API signature of `:sdk` / `:rn` / rn-core.
 */

/**
 * WHAT BECAME of a sequence that left the active replay timeline.
 *
 * CLOSED, AND DELIBERATELY NOT A PROTOCOL VOCABULARY. Nothing here reaches
 * the wire (the wire carries only the 13 canonical FAMILIES); this is the
 * retained authority remembering, per sequence, the fact a conformance test row 2
 * requires it to remember — "N existed and what became of it".
 *
 * WORDING DISCIPLINE: [PARKED] is loss FROM THE ACTIVE TIMELINE
 * with the bytes preserved losslessly at rest and must never be rendered as
 * data rejection; [QUARANTINED] and [BYTE_EVICTED] are destruction. Diagnostics
 * that collapse the two are lying about what happened to a customer's data.
 *
 * F2 WRITERS: `APPEND_REFUSED` (a post-allocation store refusal),
 * `NOT_STORED_AT_RECOVERY` (a PREPARED admission with no survivor after a
 * trustworthy read), `PARKED`, `QUARANTINED`. `BYTE_EVICTED` and `UNREADABLE`
 * are named now so the vocabulary is closed once; their production writers are
 * F4's eviction bracket and reconciler (pinned absent in F2 by a source scan).
 */
public enum class LossCause {
    APPEND_REFUSED,
    NOT_STORED_AT_RECOVERY,
    BYTE_EVICTED,
    PARKED,
    QUARANTINED,
    UNREADABLE,
}

/**
 * WHY a session's loss authority became `checkpointDisabled`.
 *
 * Closed enum, never free text — a persisted string reason would eventually
 * carry a provider message, and a provider message is user-adjacent content.
 *
 * F2 WRITERS: `AUTHORITY_INTERIOR_CORRUPTION` (the authority's own WAL lost a
 * frame that was not its tail), `QUEUE_AHEAD_OF_AUTHORITY` (a surviving queue
 * record carries a sequence above the durable high-water — the authority is
 * behind the truth and may not adopt the queue as its count),
 * `ADOPTION_LOSSY_READ` (a pre-F2 queue could not be read trustworthily at
 * one-time adoption), `ADOPTION_UNKNOWN_EVENT_TYPE`, `RECOVERY_WRITE_REFUSED`
 * (a recovery decision the store refused to record). `UNATTRIBUTABLE_GAP` is
 * F4's reconciler.
 *
 * THE RECORD THAT CARRIES A REASON IS `LossRecord.CheckpointDisabled`, and
 * the name is the salvaged F1 name for a DURABLE AUTHORITY FACT: "this
 * session's loss authority can make no claim". It is not an emitter, not a
 * wire field and not a checkpoint; F2 writes the fact and nothing in F2 reads
 * it for emission — the stage-leak scan pins that no emitter exists.
 */
public enum class DisableReason {
    AUTHORITY_INTERIOR_CORRUPTION,
    QUEUE_AHEAD_OF_AUTHORITY,
    ADOPTION_LOSSY_READ,
    UNATTRIBUTABLE_GAP,
    /**
 * One-time adoption met a survivor whose event type maps to no canonical
 * family (guards review, 2026-09-25). The queue was written by a producer
 * whose vocabulary this authority does not know, so the authority cannot
 * vouch for that history: the sequence is reserved, never owned, and the
 * session is DISABLED durably — a later admission is refused rather than
 * numbered above a queue the authority could not fully classify.
 */
    ADOPTION_UNKNOWN_EVENT_TYPE,
    /**
 * Recovery (reconcile or one-time adoption) DECIDED a write and the durable
 * store REFUSED it (blocker 2 of the ccdbec35 reviews, 2026-09-25). The
 * first F2 draft discarded every append result on those two paths and
 * returned the plan as though performed — so a refused `AdmissionIntent`
 * left a survivor UNOWNED below the eventual high-water with no Pending and
 * no Disable: a silently unaccounted sequence. An authority whose own
 * recovery decisions could not all be made durable cannot vouch for its
 * history, so it is DISABLED durably (UNKNOWN high-water, admission
 * refused); when even this record cannot land, the instance is FENCED.
 */
    RECOVERY_WRITE_REFUSED,

    // ───────────────── authority container v2 (F3-P0 the specification) — four new members ─
    //
    // EACH DECLARES `HighWaterConfidence.UNKNOWN`, exactly like all six
    // existing reasons, so RULING A2 refuses admission on every one of them
    //. There is NO `AllocationSafety`, no `AccountingConfidence`
    // and no A2′: a product decision DELETES block reservation and amortised witnessing from the
    // design rather than deferring them, so the old fail-closed posture is
    // RESTORED VERBATIM rather than superseded.
    //
    // The longest member name becomes 30 characters
    // (`AUTHORITY_TAIL_DIGEST_MISMATCH`, up from today's 29), which raises
    // `CheckpointDisabled`'s grammar maximum by 1 to 169 B and does NOT move
    // RUNG1.

    /**
 * the specification **R18** — the WAL holds FEWER frames than the authenticated witness
 * committed (`Wpresent < C`). Whole-frame tail truncation: a valid, correctly
 * ordered, correctly bound PREFIX, which is exactly why an AAD ordinal cannot
 * see it and why a durable statement of the EXPECTED EXTENT is what detects
 * it. P2 measured the harm — not "a shorter history" but **sequence REUSE**,
 * the one thing RULING A1 forbids: the store's own reader reported
 * `records=3 discarded=0 torn=false integrityClass=ok trustworthy=true`,
 * reopen reported `hw=2`, and the next `admit` returned `Owned(sequence=3)`
 * for a number already issued.
 */
    AUTHORITY_TAIL_ROLLBACK,

    /**
 * the specification **R22** — equal frame counts (`Wpresent == C`), no interior
 * authentication failure (`Wauth == Wpresent`), and the recomputed tail chain
 * `D` differs.
 *
 * **PURE INVARIANT / DEFENCE IN DEPTH — NOT REACHABLE BY ANY EXTERNAL
 * FAULT**, and the specification proves it rather than asserting it: the conjunction
 * requires two DIFFERENT sealed frames to exist for one `(container, ordinal)`
 * pair, and every keyless fault fails to produce that (reorder, duplicate at
 * another ordinal, interior delete and foreign-frame substitution all fail
 * AEAD ⇒ R17; truncation ⇒ R18 or R23; a duplicate at the SAME ordinal is the
 * identical byte sequence and the specification rules it not an anomaly). The two things
 * that CAN reach it are a KEY-AUTHORIZED re-seal at the same ordinal — which
 * the the specification protocol never performs, since A3 seals at `ordinal = Wpresent` and
 * `Wpresent` only increases — and an INTERNAL BUG in the chain computation.
 *
 * **(b) is the honest reason the row exists, and it is a reason about US,
 * not about an attacker.** A digest chain is exactly the kind of incremental
 * computation a refactor breaks silently: every other row keeps passing,
 * because every other row is about counts and AEAD. This is the only row that
 * notices. Its acceptance row is therefore a CONSTRUCTED-TUPLE test against
 * the pure state machine and **no filesystem fault injection is
 * offered, because none exists** — a fabricated fault path produces a green
 * row from a mutation that never reached the subject, which is worse than a
 * declared invariant. Same treatment as `HighWaterConfidence.KNOWN`.
 */
    AUTHORITY_TAIL_DIGEST_MISMATCH,

    /**
 * the specification **R10** — no witness exists, but a NON-GENESIS snapshot does. The
 * witness was repaired from that snapshot and the observed WAL, and the
 * session is disabled: **a deleted witness is indistinguishable from a lost
 * one, and that is exactly the state a rollback manufactures.**
 *
 * Repairing HERE writes over NOTHING (the witness is absent), which is the
 * whole a product decision asymmetry: **absent may be repaired, UNREADABLE may not.** An
 * unreadable witness is R5 — preserved byte-identically, never repaired over,
 * never treated as absent, and it writes no `DisableReason` at all.
 */
    AUTHORITY_WITNESS_ABSENT,

    /**
 * the specification **R20 / R21** — the WAL holds MORE frames than the witness committed,
 * and the state is not the exact one-step interrupted-commit shape R19
 * repairs.
 *
 * R19's five conditions are what make that repair deterministic rather
 * than a plausible guess, and the third is the load-bearing one: the chain
 * over the FIRST `C` frames must reproduce `witness.walTailDigest`, so the
 * committed history is proven byte-identical and the extra frame is genuinely
 * ONE STEP past a known-good state — not a coincidental count match over a
 * different history. This reason exists so that FAILING those conditions is a
 * named refusal instead of falling into R21's arithmetic.
 *
 * `Wpresent > C + 1` (R21) is OUTSIDE the fault model: under STRICT only
 * one append can be in flight per process and the root lock serialises
 * processes, so it cannot be produced by a correct sequence. It shares this
 * reason because the remedy is identical — refuse allocation — and inventing a
 * second reason for an unreachable arithmetic difference would imply a
 * distinction nothing can act on.
 */
    AUTHORITY_WITNESS_BEHIND_WAL,

    // ───────────────────────── F6 slice 1 ──

    /**
 * **F6 — `checkpointForDelivery` refused this interval as `INTERVAL_MISMATCH`.**
 *
 * The checkpoint arithmetic found the delivery's interval inconsistent with the
 * acknowledged authority, which is an INVARIANT VIOLATION in F6's own
 * bookkeeping: an interval the emitter computed does not agree with the durable
 * account it computed it from. the capture specification says a `checkpointDisabled` session emits
 * NOTHING, and the F6 ruling the specification requires the disable to be DURABLE so a restart
 * cannot resume emitting against the same inconsistency.
 *
 * **THE FIRST REASON CLASSIFIED `KNOWN`, AND THAT IS THE WHOLE POINT OF THE
 * CLASSIFICATION EXISTING.** [highWaterConfidence]'s own doc anticipates it: *"a
 * future reason for which the high-water IS provably known can be admitted
 * DELIBERATELY, with its own argument, rather than by loosening a condition."*
 * Here is that argument. Every other reason describes a history the authority
 * cannot BOUND — a corrupt interior, a rolled-back tail, a lossy adoption read,
 * an unaccountable gap. This one describes nothing of the kind: the durable
 * record is intact, every allocation is accounted, the high-water is exactly
 * what the authority says it is. What is broken is F6's CHECKPOINT CLAIM, which
 * is a statement about loss accounting and not about numbering. So admission
 * must keep working: refusing it would stop a healthy session capturing anything
 * at all because a checkpoint could not be computed, which trades a reporting
 * defect for data loss.
 *
 * MONOTONIC. [ownerClearable] is `false`: there is no F6 path that can later
 * prove the interval consistent, so there is nothing for a clear to mean. It is
 * written as the existing [LossRecord.CheckpointDisabled] — no new record kind,
 * no second durable home, and deliberately NOT the owner-bound
 * [LossRecord.OwnedDisable], whose whole purpose is a fact a later proof may
 * retract.
 *
 * IT DOES NOT REUSE `UNATTRIBUTABLE_GAP`. That is F4-P2's owner-bound
 * reconciliation fact with one writer and one clearing door, and reusing it was
 * MEASURED to disable an unrelated F2/F4 session from F6's arithmetic
 * (`InheritedSessionAdoptionTest#G`) while adding a second writer to a pinned
 * single-writer list (`SliceP2SourceTest#P2-2`). One durable reason, one cause.
 *
 * THE NAME IS 20 CHARACTERS, so it does NOT move `CheckpointDisabled`'s
 * grammar maximum (set by the 30-character `AUTHORITY_TAIL_DIGEST_MISMATCH`) and
 * cannot move a padding rung or `AuthorityCodec.FORMAT_VERSION`.
 */
    F6_INTERVAL_MISMATCH,
}

/**
 * Whether a disabled authority still positively KNOWS its own high-water.
 *
 * Audit addendum RULING A2. A disabled session may not know how far it has
 * allocated, and allocating from a number that may already be in use is the
 * same sequence REUSE that RULING A1 forbids, reached through the other door.
 */
public enum class HighWaterConfidence {
    /** The durable high-water is positively known; allocation above it is safe. */
    KNOWN,
    /** The high-water may be lower than reality — admission is PROHIBITED. */
    UNKNOWN,
}

/**
 * The explicit, EXHAUSTIVE classification RULING A2 requires. Adding a
 * `DisableReason` stops compiling until it is classified here, so a new reason
 * can never inherit "safe to keep allocating" by default.
 *
 * EVERY reason today is UNKNOWN, and each for its own measured cause — this
 * is not a blanket `disabled != null` check wearing a classification's clothes.
 * The enum exists so that a future reason for which the high-water IS provably
 * known can be admitted DELIBERATELY, with its own argument, rather than by
 * loosening a condition.
 */
public fun DisableReason.highWaterConfidence(): HighWaterConfidence = when (this) {
    // Interior WAL corruption means records were lost from the middle of the
    // history. The surviving maximum is a LOWER BOUND on what was allocated.
    DisableReason.AUTHORITY_INTERIOR_CORRUPTION -> HighWaterConfidence.UNKNOWN
    // Recovery raised the high-water to the queue's max and then disabled. The
    // queue is not the allocation record: anything already delivered or evicted
    // above that max left no trace in it.
    DisableReason.QUEUE_AHEAD_OF_AUTHORITY -> HighWaterConfidence.UNKNOWN
    // Named explicitly by the ruling: a lossy adoption read cannot establish the
    // pre-F2 maximum at all.
    DisableReason.ADOPTION_LOSSY_READ -> HighWaterConfidence.UNKNOWN
    // A gap nobody can account for is, by definition, a history this authority
    // cannot bound.
    DisableReason.UNATTRIBUTABLE_GAP -> HighWaterConfidence.UNKNOWN
    // The survivors' NUMBERS were read cleanly, but the queue holds a record
    // this build cannot classify — it was written by a producer with a
    // vocabulary the authority does not know. What such a producer allocated
    // beyond what survived is not something this authority can bound, so the
    // adopted maximum is a LOWER BOUND like every other disabled reason, and
    // admission above it is refused (guards review, 2026-09-25).
    DisableReason.ADOPTION_UNKNOWN_EVENT_TYPE -> HighWaterConfidence.UNKNOWN
    // A recovery decision the store refused to record: the durable account is
    // KNOWN to be incomplete, so whatever it says about the maximum is a lower
    // bound at best.
    DisableReason.RECOVERY_WRITE_REFUSED -> HighWaterConfidence.UNKNOWN
    // ─────────────────────────── authority container v2 (F3-P0 the specification, the specification) ───
    // The WAL is SHORTER than the witness committed, so frames that WERE appended
    // and witnessed are gone. The surviving maximum is a LOWER BOUND, and P2
    // measured what allocating from it does: `Owned(sequence=3)` for a number
    // already issued.
    DisableReason.AUTHORITY_TAIL_ROLLBACK -> HighWaterConfidence.UNKNOWN
    // The chain says at least one committed frame is not the frame the witness
    // chained at that ordinal. We cannot say WHICH, so we cannot say what it
    // recorded — the maximum it claims is unverified.
    DisableReason.AUTHORITY_TAIL_DIGEST_MISMATCH -> HighWaterConfidence.UNKNOWN
    // The witness is the anti-rollback COMPLETENESS authority. With it absent,
    // the repaired numbers come from artifacts that a rollback is free to have
    // rolled back together — the honest boundary the specification states.
    DisableReason.AUTHORITY_WITNESS_ABSENT -> HighWaterConfidence.UNKNOWN
    // Frames exist that no witness ever committed, and R19's prefix proof did not
    // hold — so the relationship between the log and the last known-good state is
    // exactly what is in doubt.
    DisableReason.AUTHORITY_WITNESS_BEHIND_WAL -> HighWaterConfidence.UNKNOWN
    // ──────────────────────── F6 slice 1 ───
    // THE ONLY `KNOWN` IN THIS FUNCTION, AND IT IS DELIBERATE. The durable
    // record is INTACT here: nothing was lost from the WAL, no tail rolled back,
    // no adoption read was lossy, no gap is unaccounted. Every allocation this
    // authority made is still accounted for, so the high-water IS positively
    // known and allocation above it is safe. What `INTERVAL_MISMATCH` breaks is
    // F6's CHECKPOINT CLAIM — a statement about loss accounting, not about
    // numbering. Refusing admission for it would stop a healthy session
    // capturing anything because a checkpoint could not be computed: a
    // reporting defect traded for real data loss. The the specification observation proves
    // this on both API levels — numbering does not stop solely for this reason.
    DisableReason.F6_INTERVAL_MISMATCH -> HighWaterConfidence.KNOWN
}

/**
 * F4-P2 — WHICH disable reason may be
 * installed OWNER-BOUND and later CLEARED by the exact owner that installed it.
 *
 * EXHAUSTIVE, LIKE [highWaterConfidence]: a new `DisableReason` stops compiling
 * until it is classified here, so no reason can become clearable by default.
 * EXACTLY ONE reason is owner-clearable today — `UNATTRIBUTABLE_GAP`, the fact the
 * P2 queue reconciler installs for a gap whose cardinality it could not establish
 * and may later prove exactly. Every other reason stays what frozen D2-2 says it
 * is: monotonic, never cleared. This is a classification, not a new reason (R3,
 * R11: no new `DisableReason`), and a LEGACY `CheckpointDisabled(UNATTRIBUTABLE_GAP)`
 * one written without an owner — is NOT clearable either: only the owner-bound
 * record kind `LossRecord.OwnedDisable` carries the identity the clear must name.
 */
public fun DisableReason.ownerClearable(): Boolean = when (this) {
    DisableReason.UNATTRIBUTABLE_GAP -> true
    DisableReason.AUTHORITY_INTERIOR_CORRUPTION,
    DisableReason.QUEUE_AHEAD_OF_AUTHORITY,
    DisableReason.ADOPTION_LOSSY_READ,
    DisableReason.ADOPTION_UNKNOWN_EVENT_TYPE,
    DisableReason.RECOVERY_WRITE_REFUSED,
    DisableReason.AUTHORITY_TAIL_ROLLBACK,
    DisableReason.AUTHORITY_TAIL_DIGEST_MISMATCH,
    DisableReason.AUTHORITY_WITNESS_ABSENT,
    DisableReason.AUTHORITY_WITNESS_BEHIND_WAL,
    // F6 slice 1 — MONOTONIC. No F6 path can ever prove the interval
    // consistent after the fact, so a clear would have nothing to mean. Being
    // `false` is also what keeps it out of the owner-bound record kind: the
    // codec REFUSES `snapshot_owner_on_monotonic_reason`, and
    // `SequenceAuthority` refuses `disable_reason_not_owner_bindable` for an
    // `OwnedDisable` carrying it — two independent refusals, neither relying
    // on the other.
    DisableReason.F6_INTERVAL_MISMATCH -> false
}

/**
 * WHY recovery could not decide a sequence — a CLOSED vocabulary, replacing the
 * free-text `why` the first F2 draft carried.
 *
 * A STRING COULD NOT BE CLASSIFIED, AND THAT IS THE DEFECT THIS TYPE CLOSES.
 * The liveness review found one `reconciliationPending` set serving three
 * unrelated jobs: a Phase-0 destructive-mutation FENCE (the read was lossy, so
 * the evidence a reconciler needs may still be on disk), a DISCLOSURE that F5/F7
 * still owe a settlement for a sequence nobody can currently place, and — one
 * layer up — the park manifest's own owed reconciliation. Overloading one list
 * made the strictest of the three govern all of them, which is how a clean
 * `UNRESOLVED_ABSENT` on one session froze removals for every other session in
 * the process.
 */
public enum class PendingReason {
    /**
 * The active queue could not be read trustworthily (a discarded frame or a
 * torn tail). What is missing is UNKNOWABLE, so a destructive mutator could
 * destroy the only remaining evidence.
 */
    QUEUE_READ_UNTRUSTWORTHY,

    /**
 * A trustworthy read found an ADMITTED sequence with no survivor and no
 * delivery. The read itself is sound — there is simply a settlement F4/F5
 * owes later. Nothing about it makes removing OTHER records unsafe.
 */
    ADMITTED_ABSENT_UNDELIVERED,

    /**
 * One-time adoption met a survivor whose event type maps to no canonical
 * family. The NUMBER is reserved (the high-water advances past it) and the
 * sequence is deliberately not owned under a guess; the read was clean.
 *
 * DISCLOSURE ONLY — the DURABLE consequence is separate. The same
 * adoption also plans `Disable(ADOPTION_UNKNOWN_EVENT_TYPE)`, which is what
 * refuses every later admission for the session; this entry states WHICH
 * sequence could not be owned, it does not fence a removal (the read was
 * sound, the evidence is not in danger).
 */
    ADOPTION_UNKNOWN_EVENT_TYPE,
}

/**
 * The two jobs a pending sequence can have. Only one of them may stop a
 * removal.
 */
public enum class PendingClass {
    /**
 * Phase-0 safety: destructive mutation of the SESSION THIS PENDING SET
 * BELONGS TO must refuse while it holds.
 *
 * Per session, never process-wide. A fence is a statement about one
 * authority's own unreadable history; applying it to a different session's
 * records fences bytes the fence knows nothing about.
 */
    MUTATION_FENCED,

    /**
 * An obligation somebody owes LATER. Disclosed, traced, carried into the
 * snapshot — and explicitly NOT a reason to refuse a removal, because the
 * read that produced it was trustworthy and the evidence is not in danger.
 */
    DISCLOSED_OWED,
}

/**
 * The explicit, EXHAUSTIVE classification the liveness review requires. Adding
 * a [PendingReason] stops compiling until it is classified, so a new reason can
 * never inherit "freeze every removal in the process" by default — which is the
 * direction the previous single set failed in.
 */
public fun PendingReason.pendingClass(): PendingClass = when (this) {
    // The read is the thing in doubt, so the disk is the thing to protect.
    PendingReason.QUEUE_READ_UNTRUSTWORTHY -> PendingClass.MUTATION_FENCED
    // The read was sound; a settlement is owed, not endangered. Freezing
    // removals here starves the whole queue waiting for a phase F2 does not
    // implement — the measured liveness failure.
    PendingReason.ADMITTED_ABSENT_UNDELIVERED -> PendingClass.DISCLOSED_OWED
    // Also a sound read. The number is already reserved by the advanced
    // high-water, so nothing can reuse it; what is missing is a family name.
    PendingReason.ADOPTION_UNKNOWN_EVENT_TYPE -> PendingClass.DISCLOSED_OWED
}

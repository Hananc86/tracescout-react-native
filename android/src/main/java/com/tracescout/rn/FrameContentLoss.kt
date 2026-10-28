package com.tracescout.rn

/**
 * FRAME CONTENT LOSS (FCL) — the PURE carrier vocabulary. rn-core, step 4/7.
 *
 * **CONSTRUCTION CHECKPOINT ONLY.** Nothing here is accounted, nothing is
 * durable, and no reader exists yet: the durable store is `:rn` step 5 and the
 * destructive-site routing is `:rn` step 6. What this file establishes is the
 * one vocabulary both of those must speak.
 *
 * WHY IT LIVES IN rn-core AND IS DECLARED HERE RATHER THAN IN `:rn`
 *: the seam is **declared** in rn-core and
 * **implemented** in `:rn`, never in `:sdk`. rn-core is a pure Kotlin module —
 * no Android, no file I/O, no clock — which is exactly why the frame state
 * machine can be driven under a JVM test the way it runs on a device. A type
 * declared here can be exercised without an emulator; the same type declared in
 * `:rn` could not.
 *
 * **STRUCTURAL ISOLATION IS A REQUIREMENT, NOT A STYLE CHOICE** (clause (g)).
 * This carrier is deliberately **family-free**: it is not keyed by a family
 * string, it is **not assignable to `LossCounts`**, it cannot be fed to any
 * `EventQueue` dropped map, and it cannot reach checkpoint counts, sequences or
 * sequence-gap arithmetic. The frozen `visualFrames` binding is applied ONCE,
 * declaratively, at the reporting boundary **outside** this module. So this
 * file imports nothing — not even `mobile-loss-core`, which rn-core depends on
 * as `implementation` precisely so a core type cannot surface in rn-core's
 * public API (frozen pin B10).
 *
 * **A COUNT HERE IS ALWAYS "EXACTLY COUNTED", NEVER "ALL OCCURRENCES".**
 * The three disclosure booleans on [FrameContentLossState] exist to say so.
 * Classification is by the **OBSERVED LEDGER-WRITE OUTCOME**, never by a
 * refusal reason, and no path may fabricate a count.
 */

/**
 * The three content-loss causes. CLOSED, 3 values.
 *
 * `ENQUEUE_REFUSED` is G6, `UNREADABLE_RECORD` is G9 and `RETRY_EXHAUSTED` is
 * G10. They are kept distinct because their knowabilities differ: G6 never has
 * a PENDING at all, G9 is wholly unattributed, and G10 is always attributed by
 * a validated session.
 */
enum class FrameLossCause { ENQUEUE_REFUSED, UNREADABLE_RECORD, RETRY_EXHAUSTED }

/**
 * The counted G6 refusal reasons. CLOSED, 13 values, **POSITIONAL on the wire —
 * the NAMES ARE NEVER PERSISTED**, so renaming a member cannot change a byte of
 * an existing ledger and a reader never parses a free-form string.
 *
 * **THE FIVE OUTER LOCK REASONS ARE DELIBERATELY ABSENT.** An outer
 * acquisition refusal happens *before the exclusion scope exists*, so it is
 * structurally unrecordable — it is G6-C, disclosed by
 * [FrameContentLossState.exclusionRefusalIncomplete], and it is **never a
 * counted slot**. Giving it a slot here would imply it could be counted.
 *
 * **`STORE_UNAVAILABLE` IS SPLIT INTO THREE, AND THE SPLIT IS A CORRECTNESS
 * REQUIREMENT, NOT GRANULARITY.** The three subcauses of the bare
 * `catch (_: Throwable) { false }` have *different disclosure behaviour*: a
 * keystore failure means the ledger write is almost certainly dead too (G6-B),
 * while a failed store read does not. Flattening them would report one as the
 * other. The three are [KEYSTORE_UNAVAILABLE], [STORE_READ_FAILED] and
 * [HEADER_TOO_LARGE].
 *
 * [OTHER] absorbs any unrecognised `:sdk` refusal token — and **the token
 * itself is NOT persisted**; it is traced. An unrecognised token is our own
 * defect, so it is counted exactly as G6-A rather than silently dropped.
 */
enum class FrameRefusalReason {
    SOFT_CAP,
    EMPTY_RECORD,
    RECORD_TOO_LARGE,
    RECORD_EXCEEDS_CAPACITY,
    AT_CAPACITY,
    IO_ERROR,
    RECONCILIATION_PENDING,
    RECONCILIATION_UNRESOLVED,
    RECONCILIATION_FACT_UNREADABLE,
    KEYSTORE_UNAVAILABLE,
    STORE_READ_FAILED,
    HEADER_TOO_LARGE,
    OTHER,
}

/**
 * What a typed head removal actually did. CLOSED, 3 values.
 *
 * **THIS IS rn-core's OWN MIRROR OF THE STORE'S `Durability`, AND IT HAS TO
 * BE.** rn-core cannot name a `:sdk` type — the module does not depend on
 * `:sdk` and must not — so the three states are declared here and the `:rn`
 * adapter maps `RemoveResult.durability` onto them at step 6. The values are
 * identical by name on purpose: a reader comparing the two sides should see one
 * vocabulary, not two that happen to line up.
 *
 * `NOT_COMMITTED` and [INDETERMINATE_DIRECTORY_UNSYNCED] are **not**
 * interchangeable. Both mean "do not treat the removal as done", but only the
 * second means "and we cannot tell whether it will become done". Collapsing
 * them is what makes an INDETERMINATE state get promoted into a total.
 */
enum class FrameRemoval { COMMITTED, NOT_COMMITTED, INDETERMINATE_DIRECTORY_UNSYNCED }

/**
 * The drain-pass mutation classes. CLOSED, exactly 5 members, one per measured
 * frame-store mutation site.
 *
 * **THE PARAMETER IS AN ENUM BECAUSE A `String` COULD CARRY USER DATA.** An
 * earlier shape took `op: String`; it is WITHDRAWN. The trace label is
 * [wire], derived from a closed constant, so no customer value can reach a
 * trace by type. A sixth mutation class cannot be added without adding a member
 * here, which fails the exactly-five pin.
 *
 * The five sites are the two G9/G10 discard sites plus the three pass-level
 * mutations; each site passes a **distinct** member, so two sites cannot share
 * one label. The sites themselves are wired at **step 6**, not here.
 */
enum class FrameMutationOp(val wire: String) {
    /** The undecodable-record discard — G9. */
    UNREADABLE_DISCARD("frame_g9"),

    /** The attempts-exhausted discard — G10. */
    RETRY_EXHAUSTED_DISCARD("frame_g10"),

    /** Head removal after a valid acknowledgement. Destroys bytes; not a loss. */
    ACK_SETTLEMENT("frame_ack"),

    /** Head removal after a permanent record-level refusal. */
    PERMANENT_REFUSAL_SETTLEMENT("frame_refused"),

    /** Append-then-verify-then-remove rotation of a retried frame. */
    ROTATION("frame_rotate"),
}

/**
 * The result of asking for an exclusion scope.
 *
 * **A REFUSAL IS A VALUE, NEVER A THROW.** A thrown lock refusal reaching a
 * capture-path API would surface in the host app; worse, it would arrive at a
 * broad `catch` as an anonymous throwable and collapse into an unnamed refusal.
 * Returning it typed is what keeps the layer and the wait time legible.
 */
sealed class Exclusion<out T> {
    /** The block ran inside the scope; [value] is its answer. */
    data class Ran<T>(val value: T) : Exclusion<T>()

    /**
 * The scope was never entered, so **the block did not run and nothing was
 * mutated**. [wire] is the layer-prefixed refusal token and [waitedMs] is
 * how long was spent waiting.
 *
 * A refused exclusion at a drain-pass mutation is **NOT a content loss
 * and must set no disclosure** — the frame is still there. Only a refused
 * `enqueue` acquisition is G6-C.
 */
    data class Refused(val wire: String, val waitedMs: Long) : Exclusion<Nothing>()
}

/**
 * The durable-accounting seam, declared pure here and implemented in `:rn`.
 *
 * **THE FOREIGN-PENDING RESOLUTION LIVES INSIDE [withExclusion], NOT AT THE
 * CALL SITES.** "Resolve any foreign PENDING before mutating" has to hold at
 * five different sites; putting it in the one home makes it structural instead
 * of five things to remember.
 *
 * **[withExclusion] IS NEVER CALLED WITH A NETWORK REQUEST INSIDE THE
 * BLOCK.** The frame POST happens before every mutation site and stays outside
 * every exclusion; holding an OS lock across an HTTP request would make a
 * remote stall into a local one.
 *
 * **THE BLOCK API IS RETAINED DELIBERATELY.** Five named sink methods would
 * either take the block anyway, or force the guard-read-and-mutation logic out
 * of this pure state machine and into the Android module — which would destroy
 * the JVM testability that is this module's entire reason for existing.
 */
interface FrameLossSink {
    /**
 * Run [block] inside the ONE frame-domain exclusion scope, having FIRST
 * resolved any foreign PENDING.
 */
    fun <T> withExclusion(op: FrameMutationOp, block: () -> T): Exclusion<T>

    /**
 * Commit a durable PENDING for a removal that is about to be attempted.
 * Returns false when the PENDING could not be made durable — in which case
 * **the removal must be REFUSED**, because a removal with no PENDING cannot
 * be reconciled after a crash. Called inside an exclusion.
 */
    fun prepare(cause: FrameLossCause, sessionId: String?): Boolean

    /** Record what the typed removal actually did. Called inside the SAME exclusion as [prepare]. */
    fun resolve(removal: FrameRemoval)

    /**
 * Record a G6 enqueue refusal. Returns whether the ledger write COMMITTED —
 * **false is G6-B, which is a disclosure and never a count**.
 */
    fun recordRefusal(reason: FrameRefusalReason, sessionId: String?): Boolean
}

/**
 * One attributed row: a (cause, validated session) pair and its exact count.
 *
 * Only G6 and G10 ever have rows. G9 is wholly unattributed and lives in
 * [FrameContentLossState.g9Occurrences] alone.
 */
data class FrameLossRow(val cause: FrameLossCause, val sessionId: String, val occurrences: Long)

/**
 * The in-flight removal record. **G9/G10 ONLY — G6 never has one**, because a
 * refused enqueue destroys nothing and so has nothing to reconcile.
 *
 * The two fingerprints are whole-store folds taken inside the exclusion: one of
 * the store as it is, one of the store as it will be if the head leaves. After
 * a crash, comparing the live store against them is what distinguishes
 * *removal still owed* from *removal committed* without a per-record digest
 * existing anywhere.
 */
data class FrameLossPending(
    val cause: FrameLossCause,
    /** Whole-store fold at PREPARE. */
    val preFingerprint: String,
    /** Record count at PREPARE. */
    val preCount: Int,
    /** Whole-store fold the store would have with the head gone. */
    val expectedPostFingerprint: String,
    /** Validated session for `RETRY_EXHAUSTED`; **null for `UNREADABLE_RECORD`**, which carries no id. */
    val sessionId: String?,
)

/**
 * The whole durable FCL state. One record holds exactly one of these.
 *
 * **NO FIELD HAS A DEFAULT, AND THE BREAK IS THE POINT.** This is the same
 * discipline the store's `RemoveResult.durability` carries: a defaulted field
 * lets a future field be added and silently take a value nobody stated, in a
 * carrier whose entire job is to be honest about what is and is not known. Use
 * [FrameContentLossLedger.fresh] for a first-install state.
 *
 * **THREE DISCLOSURES, THREE FIELDS, NEVER FOLDED, NEVER COUNTS.**
 * [priorStateLost], [refusalRecordingIncomplete] (G6-B) and
 * [exclusionRefusalIncomplete] (G6-C) are monotonic booleans. A *count* of
 * events that could not be recorded would itself be a fabricated number in the
 * one place honesty matters most; a monotonic boolean is the most that is
 * knowable. They are never cleared in ordinary operation — only a governed
 * erasure clears them, by dropping the whole ledger.
 */
data class FrameContentLossState(
    /** Wire schema version; always [FrameContentLossCodec.VERSION]. */
    val version: Int,
    /** The ONLY home for G9's total — G9 has no rows. */
    val g9Occurrences: Long,
    /** Positional over [FrameRefusalReason], size 13. G6-A only. */
    val refusalCounts: List<Long>,
    /** G6 and G10 only; size <= [FrameContentLossCodec.ROWS_MAX]. */
    val rows: List<FrameLossRow>,
    val g6Collapsed: Long,
    val g10Collapsed: Long,
    /** FOLD EVENTS, explicitly an UPPER BOUND — never an exact distinct count. */
    val g6SessionsCollapsedUpperBound: Long,
    /** FOLD EVENTS, explicitly an UPPER BOUND — never an exact distinct count. */
    val g10SessionsCollapsedUpperBound: Long,
    /** INDETERMINATE removals disclosed, never promoted and never folded into a total. */
    val g9IndeterminateDisclosed: Long,
    /** INDETERMINATE removals disclosed, never promoted and never folded into a total. */
    val g10IndeterminateDisclosed: Long,
    /** History was lost. Monotonic. */
    val priorStateLost: Boolean,
    /** G6-B: an occurrence happened in scope and its ledger write was not durable. Monotonic. */
    val refusalRecordingIncomplete: Boolean,
    /** G6-C: refused before the scope existed, so it was structurally unrecordable. Monotonic. */
    val exclusionRefusalIncomplete: Boolean,
    /** 0 or 1, **by type** — the schema cannot express two in-flight removals. */
    val pending: FrameLossPending?,
)

/**
 * How a ledger read turned out. **The classification, not the content.**
 *
 * **RELOCATED HERE FROM `:rn` IN STEP 6/7 UNDER F8-B, BEHAVIOUR-FREE.** The
 * accepted contract's the specification lists this type under this file. Step 4 did not create
 * it, because no sealed authority declared its membership — only outcome NAMES
 * in a table — and the membership is forced by the classification that PRODUCES
 * it, which lives in `:rn`'s durable store. Step 5 therefore declared it beside
 * its producer and DISCLOSED the deviation, because step 5's own diff contract
 * required ZERO rn-core production change: the correct placement was
 * structurally unavailable to it. The step-5 review classified that as **F8-B —
 * non-blocking location debt** and required the move before the final seam
 * candidate.
 *
 * **THE MOVE IS PACKAGE ONLY.** Every member, every name, every ordinal and
 * every doc sentence is carried verbatim; nothing in rn-core consumes the type,
 * so no call site, signature or behaviour changes. The reason to move it is
 * FUTURE DIVERGENCE: eight durable read states are a cross-platform model
 * classification, and one living in `:rn` is one iOS would have to re-declare —
 * which is how two spellings of one model begin.
 *
 * **EVERY UNTRUSTWORTHY STATE IS FAIL-CLOSED: no write, no trim, no
 * promotion, and NO NUMBER CLAIMED.** "I could not read the ledger" must never
 * be reported as "the ledger says zero".
 */
enum class FrameLossReadState {
    /** Readable. The last record is the state; any prefix is history. */
    OK,

    /**
 * Absent, nothing discarded, not torn, and this process has not previously
 * read a non-empty ledger. Legitimately zero.
 *
 * After a COMPLETED erasure this is also the required end state, because
 * zero residue is mandatory — so across a process boundary *erased* and
 * *never existed* are indistinguishable, **and must be**.
 */
    TRUSTWORTHY_EMPTY,

    /**
 * This process already observed a non-empty ledger and it is now absent.
 * The successor carries `priorStateLost`, which is never cleared.
 * **Never reported as zero.** Detectable only within a process lifetime.
 */
    VANISHED_AFTER_OBSERVED,

    /** Records discarded with an authentication failure (e.g. after key rotation). */
    UNTRUSTWORTHY_AUTH_FAILURE,

    /** A torn tail or an invalid length prefix. */
    UNTRUSTWORTHY_FRAMING,

    /** More records than the crash-tolerance bound. Structurally unreachable; fail closed anyway. */
    INVARIANT_EXCEEDED,

    /**
 * The last record authenticated but the codec refused it.
 * **NEVER falls back to an older record** — that would silently
 * under-report, which is worse than refusing.
 */
    LEDGER_RECORD_UNDECODABLE,

    /** `version != 1`. Refuse; never read anyway, never migrated, **never deleted**. */
    VERSION_UNSUPPORTED,
}

/**
 * The verdict of asking the enforcement gate to reconcile.
 *
 * **RELOCATED HERE FROM `:rn` IN STEP 6/7 UNDER F8-B, BEHAVIOUR-FREE**, for
 * the same reason as [FrameLossReadState] and under the same classification:
 * the contract's the specification lists it in this file, step 5 could not put it here without
 * breaking its own diff contract, and it disclosed that rather than being found
 * out. [Refused.reason] is a typed token, never a message built from a
 * throwable.
 */
sealed class FrameLossGateOutcome {
    object Reconciled : FrameLossGateOutcome()
    data class Refused(val reason: String) : FrameLossGateOutcome()
}

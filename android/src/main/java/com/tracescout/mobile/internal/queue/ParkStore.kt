package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo

/**
 * The THIRD durable fate of a record.
 *
 * A record in this store is `PARKED_PRODUCER`: preserved losslessly, not
 * eligible for ordinary drain, and — crucially — **no longer at the head of the
 * active FIFO**, so it cannot block anything behind it.
 *
 * WHY A SEPARATE STORE RATHER THAN A MARKER. `removeFirst(N)` can only advance
 * the active prefix, so a "permanently refused" flag left on the head slice
 * would still starve every record behind it: the drain would reach that slice
 * first, decline to send it, and stop. A marker describes the problem; moving
 * the bytes out of the active queue is the only thing that fixes it. That
 * distinction is the whole design.
 *
 * **PARKED IS NOT QUARANTINE.** Quarantine means the SERVER rejected THESE
 * RECORDS and they are gone. Parked means the server rejected the PRODUCER that
 * made them — an immutable property of the captured bytes (`sdkFamily` /
 * `sdkVersion` are stamped natively at capture time, which is precisely what
 * stops a producer misreporting its own identity), so a corrected build cannot
 * make these particular records acceptable. The records are kept, in full, at
 * rest, under the same encryption as the live queue. Nothing here may ever be
 * reported as data rejection.
 *
 * **The interface is deliberately WRITE-AND-READ ONLY.** There is no `delete`,
 * no `clear`, no `reactivate`. Automatic reactivation is not merely undone, it
 * is unrepresentable — if server capability ever changes, an explicit,
 * controlled reactivation path is a decision to be taken then, not a hook left
 * lying around now. The same reasoning gave [RecordStore] no `clear`.
 */
/**
 * WHY A TYPED OUTCOME AND NOT A BOOLEAN.
 *
 * `#146` made the storage layer distinguish `at_capacity` from `io_error`,
 * precisely because capacity cannot clear by itself. `park` then collapsed
 * both back into `true`/`false`, so the caller could not tell them apart — and
 * the pipeline mapped every failed park to a bounded WorkManager retry.
 *
 * THAT WOULD HAVE REPLACED AN INFINITE 2 s HTTP LOOP WITH AN INFINITE
 * EXPONENTIAL LOCAL PARK LOOP. Better load profile, identical wrong contract:
 * `ParkStore` has no delete, clear or reactivate path, so its logical capacity
 * never shrinks and the retry can never succeed. A distinction the storage
 * layer worked to expose must not be discarded one layer up.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
enum class ParkOutcome {
    /** Durably preserved and verified. The active prefix may be removed. */
    PARKED,

    /**
 * A transient write or verification failure. Active bytes retained; a
 * BOUNDED local retry is legitimately owed, because this can clear.
 */
    RETRYABLE_FAILURE,

    /**
 * Cannot succeed without an external change — preservation capacity is
 * exhausted, or no park store is wired. Active bytes retained, and **no
 * automatic retry of any kind**: no network deadline, no WorkManager
 * backoff, no polling. A later ordinary launch may try once; a permanently
 * scheduled retry is exactly what this value exists to prevent.
 */
    BLOCKED,
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
interface ParkStore {

    /**
 * Persist [records] and [batch] durably, then VERIFY the write.
 *
 * Returns `true` only when the parked copy is readable back, because the
 * caller removes the active bytes on the strength of this answer. An
 * optimistic `true` here is the one way this design could lose data.
 *
 * Idempotent by [ParkedBatch.chunkId]: a batch already parked is not
 * written twice, so a crash between the parked write and the active
 * removal cannot duplicate the parked copy on the next run.
 */
    fun park(batch: ParkedBatch, records: List<ByteArray>): ParkOutcome

    /**
 * Read the manifest, saying whether the read could be trusted.
 *
 * **A REQUIREMENT WITH NO DEFAULT — `entries` ALONE COULD NOT TELL
 * "NOTHING IS PARKED" FROM "I COULD NOT READ".** Both answered with an
 * empty list, and three callers acted on that emptiness:
 *
 * - `park`'s idempotency check read empty and appended a SECOND copy of
 * every record of a batch that was already preserved;
 * - `reconcileParked` read empty and returned as though nothing was
 * parked, so the duplicate active prefix a crash had left behind was
 * never settled;
 * - `sessionAlreadyTerminal` read empty and asked the server again for a
 * fact the device had already durably learned.
 *
 * An unreadable manifest is now its own answer, and every one of those
 * three refuses rather than proceeding on ignorance.
 */
    fun entriesRead(): ParkManifestRead

    /**
 * Every complete parked batch, oldest first. Metadata only.
 *
 * DERIVED from [entriesRead] — it cannot disagree with it, and a caller
 * that only needs the list and has already established trust is welcome to
 * it. A caller that is about to WRITE or REMOVE must use [entriesRead].
 */
    fun entries(): List<ParkedBatch> = entriesRead().entries

    /** Safe diagnostics — counts and bytes, never content. */
    fun status(): ParkStatus

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /**
 * The no-op store, for callers that have not wired a parked store yet.
 *
 * It refuses to park, which makes the pipeline RETAIN the slice
 * exactly as before. Failing closed matters here: a park store that
 * silently claimed success would authorise removing the active bytes
 * with nowhere for them to go.
 */
        @JvmStatic
        fun none(): ParkStore = object : ParkStore {
            // BLOCKED, NOT RETRYABLE. An unwired store never becomes wired
            // at runtime, so its refusal can no more clear by retrying than a
            // full one can. Reporting it retryable would schedule a bounded
            // backoff against a condition with no mechanism to change.
            override fun park(batch: ParkedBatch, records: List<ByteArray>) = ParkOutcome.BLOCKED

            // `ok`, NOT `unreadable`. There is genuinely nothing parked and
            // that is genuinely knowable: this store has no container to fail
            // to read. Reporting it untrustworthy would wedge the reconcile
            // path for every caller that has not wired a park store — a
            // fail-closed answer to a question that has a certain one.
            override fun entriesRead() = ParkManifestRead(emptyList(), RecordStore.INTEGRITY_OK)
            override fun status() = ParkStatus(0, 0, 0L)
        }
    }
}

/**
 * The immutable identity and provenance of one parked batch.
 *
 * SAFE METADATA ONLY. Identity, ordering, size, producer, refusal code and
 * timestamps — nothing that could carry replay content, a URL, a credential or
 * a digest preimage. A diagnostic that can carry replay content is an
 * exfiltration path wearing a diagnostic's clothes.
 *
 * [chunkId] is content-addressed over the batch's own record digests, so it
 * doubles as the crash-recovery matcher without the manifest ever storing the
 * digests themselves.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class ParkedBatch(
    val chunkId: String,
    val sessionId: String,
    val firstSequence: String,
    val lastSequence: String,
    val recordCount: Int,
    val producerFamily: String,
    val producerVersion: String,
    val refusalCode: String,
    val firstSeenAtMs: Long,
    val parkedAtMs: Long,
)

/**
 * A manifest read that can say it FAILED, rather than only that it found
 * nothing.
 *
 * [integrityClass] uses the SAME five-word vocabulary as
 * `EncryptedEventStore.ReadResult.integrityClass` and the iOS `StoreIntegrity`
 * one fact, one name, across three containers — plus one value only a
 * dedicated manifest can produce:
 *
 * **`entry_undecodable`** — a frame that DECRYPTED correctly and then did
 * not parse as a manifest entry. On Android the manifest is its own file
 * carrying nothing but entries, so a frame that is not an entry is knowledge we
 * have lost, not a record that happens to live alongside. It is deliberately
 * NOT a value the iOS twin can produce: there the parked records and their
 * markers share ONE file by design, so a frame that is not JSON is the ordinary
 * case and would make every healthy read look corrupt. The asymmetry is real
 * and is stated rather than smoothed into a shared lie.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class ParkManifestRead(
    val entries: List<ParkedBatch>,
    val integrityClass: String,
) {
    val isTrustworthy: Boolean get() = integrityClass == RecordStore.INTEGRITY_OK

    companion object {
        const val ENTRY_UNDECODABLE = "entry_undecodable"
    }
}

/** Counts and bytes. Deliberately nothing that identifies a session or a user. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class ParkStatus(
    val batchCount: Int,
    val recordCount: Int,
    val bytes: Long,
    /**
 * The manifest's own trust class. A diagnostic that reports `batchCount: 0`
 * for an unreadable manifest is the defect this whole change exists for,
 * repeated on the surface an operator actually looks at.
 */
    val integrityClass: String = RecordStore.INTEGRITY_OK,
)

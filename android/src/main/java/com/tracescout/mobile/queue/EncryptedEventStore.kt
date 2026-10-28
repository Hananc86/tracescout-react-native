package com.tracescout.mobile.queue

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * Native-core the specification PersistentStore — the durable half of the offline queue.
 *
 * FRAME FORMAT (append-only):
 *
 * [4-byte big-endian length][12-byte IV][AES-256-GCM ciphertext + 16-byte tag]
 *
 * WHY PER-RECORD ENCRYPTION RATHER THAN ONE ENCRYPTED FILE. A single stream
 * cipher over the whole file means one torn tail — the normal outcome of
 * process death mid-write — destroys EVERY record behind it. Per-record frames
 * with their own IV and auth tag mean a torn or corrupted frame costs exactly
 * that frame; everything already durable is still readable. Since the reason
 * this store exists is surviving process death, resilience to a partial final
 * write is the whole requirement, not a nicety.
 *
 * THE AUTH TAG IS THE INTEGRITY CHECK. A truncated or bit-flipped frame fails
 * GCM authentication and is DISCARDED, never partially parsed — so a torn
 * record can never be handed upward as a half-valid event.
 *
 * WHAT IS STORED IS ALREADY MASKED. Masking happens at capture, far upstream;
 * this class encrypts what it is given. Encryption is defence in depth against
 * a device compromise, NOT a substitute for masking — an instrumented test
 * greps the raw file bytes for sentinels to keep both honest.
 */
class EncryptedEventStore(
    private val file: File,
    private val key: SecretKey,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    /**
 * What KIND of store this is. Since F4-P0 Slice C it decides NOTHING in
 * this file.
 *
 * `append` READS THIS IN ZERO BRANCHES, AND THAT IS PINNED. Before
 * Slice C the ACTIVE_QUEUE policy made `append` evict oldest-first under
 * pressure (`evictOldest`, deleted): a second destructive writer that
 * destroyed records with no durable intent, no attribution and no
 * settlement — the loss surfaced only at the next reconcile, as
 * `ADMITTED_ABSENT_UNDELIVERED`. Byte eviction now has exactly ONE home,
 * the governed bracket (`internal/queue/EvictionBracket`), which PREPARES a
 * durable intent per victim before the one `compactAndAppend` that removes
 * them. At capacity this store REFUSES, whatever the policy says.
 *
 * The parameter and the enum are kept so the three PRESERVE construction
 * sites and their pins stay untouched, and so the constructor still says
 * out loud which stores must NEVER be made room in — see the enum.
 */
    private val evictionPolicy: EvictionPolicy = EvictionPolicy.ACTIVE_QUEUE,
    /**
 * The parent-directory fsync seam.
 *
 * INJECTABLE SO THE GUARANTEE IS OBSERVABLE. The production
 * implementation needs `android.system.Os`, which does not exist in a JVM
 * unit test — so without this parameter P0-14 ("parent-directory fsync
 * happens", negative control "drop the directory fsync") could only be
 * asserted by reading source text, and a source-grep cannot show that the
 * sync was called on the RIGHT directory at the RIGHT moment.
 */
    private val dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
) {

    // ══════════════════════════════════════════════════════════════════════
    // THE ONE LOCK ORDER
    //
    // QUEUE REGION LOCK -> QUEUE FILE FENCE -> THIS STORE'S MONITOR
    // -> AUTHORITY MONITOR -> AUTHORITY ROOT FILE LOCK
    //
    // TWO LAYERS, NOT ONE, AND THEY ANSWER CONTENTION DIFFERENTLY. The
    // region lock serialises THIS process's threads and WAITS — the behaviour
    // class the `@Synchronized` model had, which must survive the move, because
    // a second thread appending is not a reason to drop a record. The file
    // fence excludes other PROCESSES and is bounded, because there is no thread
    // to wait on. A single class holding both semantics is what turned ordinary
    // intra-process contention into dropped records.
    //
    // BOTH ARE DERIVED HERE, AND THERE IS DELIBERATELY NO CONSTRUCTOR
    // PARAMETER FOR EITHER. Three call sites construct this class directly in
    // other processes or other modules — the fixture's `:writer` and `:crash`
    // services and the UAT diagnostics module — and they are not going to
    // cooperate. Deriving from the store's own canonical path is what makes
    // them honour the order structurally rather than by agreement.
    //
    // AND BOTH MUST BE ACQUIRED *OUTSIDE* THE MONITOR. An earlier design
    // took the lock inside these methods while they were `@Synchronized`, which
    // locks `this` — so the monitor was taken FIRST, the exact inversion of the
    // order above. Every public mutator below is now an UNSYNCHRONIZED wrapper
    // whose body IS the ordered acquisition, calling a PRIVATE `@Synchronized`
    // twin. The twins are private, so no caller anywhere can enter the monitor
    // without having passed both layers.
    // ══════════════════════════════════════════════════════════════════════
    private val regionLock: QueueRegionLock = QueueRegionLock.forStoreFile(file)
    private val fileFence: QueueFileFence = QueueFileFence.forStoreFile(file)

    /** The region lock governing this store. Same object per queue domain. */
    internal fun regionLock(): QueueRegionLock = regionLock

    /** The file fence governing this store. Same object per queue domain. */
    internal fun fileFence(): QueueFileFence = fileFence

    /**
 * The file these bytes live in.
 *
 * EXISTS SO `QueueHome` CAN VERIFY THE STORE ITS FACTORY BUILT IS THE
 * ONE IT IS KEYED ON. A home whose identity disagrees with the store it
 * wraps is the identity hole the deleted `ofStore` overload had; the check
 * needs the store to be able to say which file it is over.
 */
    internal fun backingFile(): File = file

    /** What kind of store this is. Neither value makes `append` destroy anything. */
    enum class EvictionPolicy {
        /**
 * The active replay queue. Room is made ONLY by the governed eviction
 * bracket, outside this file, through `compactAndAppend` — never by
 * `append`, which refuses `at_capacity` exactly as PRESERVE does.
 */
        ACTIVE_QUEUE,

        /**
 * NEVER evicts, and is never a bracket's subject either. At capacity
 * the append is REFUSED and every existing record stays exactly where
 * it is. The park stores and the authority's own files.
 */
        PRESERVE,
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 8L * 1024 * 1024
        private const val IV_BYTES = GcmFrame.IV_BYTES

        /** A frame larger than this is treated as corruption, not a huge record. */
        private const val MAX_FRAME_BYTES = GcmFrame.MAX_FRAME_BYTES

        /**
 * OVER THE LOGICAL ORDERED PLAINTEXT, NEVER THE FILE BYTES. Every
 * rewrite generates fresh IVs, so two files holding the SAME records
 * legitimately differ byte-for-byte. A ciphertext digest would report a
 * mismatch for every correct rewrite and would make the whole
 * transaction unusable.
 *
 * AND IT IS TRANSACTIONAL STATE, NOT DIAGNOSTICS. It is a stable
 * identifier derived from customer telemetry; it must live only inside
 * the encrypted intent store and must never be emitted in a trace, a
 * log or an export. Nothing in this file logs it.
 */
        const val FINGERPRINT_VERSION = "tsfp1"

        /** `4 + IV + plaintext + GCM tag` per frame — see [encodedSize]. */
        @JvmStatic
        fun frameBytes(plaintextLength: Int): Long = (4 + IV_BYTES + plaintextLength + 16).toLong()

        /** The on-disk size of [records] as this store frames them. One formula, shared with the planner. */
        @JvmStatic
        fun encodedSize(records: List<ByteArray>): Long = records.sumOf { frameBytes(it.size) }

        @JvmStatic
        fun logicalFingerprint(records: List<ByteArray>): String {
            val outer = MessageDigest.getInstance("SHA-256")
            outer.update(FINGERPRINT_VERSION.toByteArray(Charsets.UTF_8))
            outer.update(records.size.toString().toByteArray(Charsets.UTF_8))
            for (r in records) {
                // Per-record digest, so record boundaries cannot be forged by
                // concatenation: [a][bc] and [ab][c] must not collide.
                outer.update(MessageDigest.getInstance("SHA-256").digest(r))
            }
            return outer.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val random = SecureRandom()

    /**
 * F4-P2 child — the durable reconciliation fact beside THIS file
 * (`<file>.reconcile.json`, the ONE suffix `QueueHome` owns), read by Gate B
 * of the reconciliation mutation gate inside [appendLocked]. A store that is
 * not a queue (park, authority WAL, frames) never has one, so the read is a
 * single absent-file stat. Never written here: the reconciler is the ONE
 * writer, inside the `RECONCILE` region on this store's own lock pair.
 */
    private val reconciliationFact: com.tracescout.mobile.internal.queue.ReconciliationFact by lazy {
        com.tracescout.mobile.internal.queue.ReconciliationFact(
            File(file.absoluteFile.parentFile, file.name + QueueHome.RECONCILE_SUFFIX),
        )
    }

    data class ReadResult(
        val records: List<ByteArray>,
        /** Frames dropped because they failed integrity — counted, never silent. */
        val discarded: Int,
        val discardReasons: List<String>,
        val truncatedTail: Boolean,
    ) {
        /**
 * The CLASS of damage, as one word — cross-platform parity with the
 * Swift `StoreIntegrity`.
 *
 * A COUNT AND A BOOLEAN DO NOT NAME A DEFECT. The refusal traces
 * carried `discarded=1 torn=false`, which tells an operator that
 * something was dropped and not WHAT: an auth failure sends them
 * looking at the key, an invalid length at the writer, an unreadable
 * file at the filesystem. The vocabulary already existed in
 * `discardReasons`; it simply never reached the line anybody reads.
 *
 * A PURE TORN TAIL ADDS NO REASON STRING (the framing loop just
 * breaks), so deriving this from `discardReasons` alone would report
 * `ok` for a genuinely torn store. The tail is checked explicitly.
 *
 * First-observed wins, matching Swift: a file can be damaged several
 * ways and a stable single answer is what makes two reads comparable.
 */
        val integrityClass: String
            get() = when {
                discardReasons.contains("store_unreadable") -> "unreadable_file"
                discardReasons.contains("frame_auth_failed") -> "auth_failure"
                discardReasons.contains("frame_length_invalid") -> "invalid_length_prefix"
                truncatedTail -> "torn_tail"
                discarded != 0 -> "discarded_unclassified"
                else -> "ok"
            }

        /** The ONE question every destructive operation asks. */
        val isTrustworthy: Boolean get() = discarded == 0 && !truncatedTail
    }

    /**
 * How durable is a write that has returned?
 *
 * THREE VALUES, BECAUSE THERE ARE THREE STATES. The post-move /
 * post-append state where the CONTENTS are committed and the DIRECTORY
 * ENTRY is not confirmed is neither "it happened" nor "it did not", and
 * collapsing it into either is a lie in the one direction that matters:
 * committed authorises finalising a loss, old-retained authorises redoing
 * an operation that may already have taken effect.
 */
    enum class Durability {
        /** Contents AND the directory entry are confirmed durable. */
        COMMITTED,

        /**
 * The write took effect and the parent directory could not be synced.
 * After a crash the result may or may not be present. May NEVER be
 * reported as either neighbour.
 */
        INDETERMINATE_DIRECTORY_UNSYNCED,

        /** Nothing took effect. The previous state is authoritative. */
        NOT_COMMITTED,
    }

    /**
 * THERE IS NO `evicted` FIELD ANY MORE (F4-P0 Slice C). An append never
 * destroys a record, so a count of what it destroyed would be a field that
 * is always zero — a fact nobody can act on, and the one the park store
 * used to have to assert was zero.
 */
    data class AppendResult(
        val stored: Boolean,
        val reason: String?,
        /**
 * NO DEFAULT, FOR THE SAME REASON `RecordStore.integrityClass` HAS
 * NONE. A default of COMMITTED would claim durability on behalf of a
 * construction site that never said so — the fail-open contract this
 * phase exists to remove, re-created one type over. Every construction
 * site is in this file, so requiring it costs nothing and buys a
 * compiler check.
 */
        val durability: Durability,
    )

    /**
 * Append one record, durably. NEVER makes room: at capacity it REFUSES.
 *
 * The write is followed by an fsync BEFORE returning, so a caller that has
 * been told `stored: true` really can survive an immediate process kill.
 * Returning before the sync would make the durability claim a lie in
 * exactly the scenario the store exists for.
 */
    fun append(plaintext: ByteArray): AppendResult =
        ordered(QueueRegion.APPEND, "append", ::appendRefused) { appendLocked(plaintext) }

    /**
 * The CRASH-PATH append: same monitor twin, TIGHTER fence budget.
 *
 * THIS EXISTS BECAUSE AN INDEPENDENT REVIEW FOUND `Region.CRASH_APPEND`
 * HAD ZERO PRODUCTION CALLERS. The tight budget was defined, tested by
 * calling it directly, and reached by nothing — so the only real crash
 * append went through the ordinary region's budget and could sleep for
 * seconds on the dying thread of an uncaught exception. That is strictly
 * WORSE than before this slice, where `append` only took the monitor and
 * could not be refused by contention at all. A mechanism proven by calling
 * it directly while no product path reaches it is the defect class this
 * programme keeps paying for.
 *
 * `internal` because the ONE caller is `capture/CrashHandler`, in this
 * module. It is deliberately not a parameter on `append`: a region argument
 * would let any caller claim the crash budget.
 *
 * **AN INTENTIONAL BEHAVIOUR CHANGE, DISCLOSED.** Before F4 the crash
 * append took only the store MONITOR: it waited for whatever thread held
 * it, for as long as that took, and contention could never refuse it. It
 * now makes ONE short, bounded attempt at each layer
 * ([QueueRegionLock.BOUNDED_BUDGET_MS] against this process's own threads,
 * a few tens of milliseconds against another process) and then answers a
 * TYPED refusal, `crash_append_refused_<layer>_<reason>`. So a same-process
 * hold that OUTLASTS the crash budget now produces an accounted crash-record
 * loss where the old path would have waited and stored. That is the ruled
 * policy, not a regression to fix: the crash thread is racing the process's
 * own death and must not queue behind ordinary work. `CrashHandler` reads
 * the result, traces the refusal once and settles the admission once.
 */
    internal fun appendFromCrashHandler(plaintext: ByteArray): AppendResult =
        ordered(QueueRegion.CRASH_APPEND, "append", ::crashAppendRefused) { appendLocked(plaintext) }

    // ══════════════════════════════════════════════════════════════════════
    // THE ORDERED ACQUISITION, AND THE TYPED REFUSALS
    //
    // A THROW WAS THE WRONG SHAPE, AND THE CRASH PATH PROVED IT. `append`
    // already answers every other failure with a typed `AppendResult` —
    // `at_capacity`, `record_too_large`, `eviction_not_committed` — so a caller
    // that reads the result handles a refusal correctly and a caller that
    // catches broadly loses it. `CrashHandler` catches `Throwable` and ignores
    // it by design (Rule 1: never stop the host's handler), so a thrown
    // refusal there meant no trace, no `afterAppend`, and therefore no F2
    // settlement of the admission — the crash record lost silently. The same
    // catch shape leaves RN's `enqueue` returning -1 with a PREPARED admission
    // nobody resolves.
    //
    // AND THE REASONS ARE DISTINCT. `OverlappingFileLockException`, a
    // lock-invariant violation and ordinary cross-process contention must never
    // share a reason: the first two are defects in our own code and the third
    // is a legitimate second owner that clears on its own. A caller that
    // retried an invariant violation on a contention backoff would spin against
    // a condition that can never change.
    // ══════════════════════════════════════════════════════════════════════

    /**
 * THE ONE ORDERED ACQUISITION: region lock, then file fence, then [body]
 * (which enters the monitor twin) — and a lock refusal translated into the
 * operation's own typed result by [refused].
 *
 * EVERY PUBLIC MUTATOR'S BODY IS EXACTLY ONE CALL TO THIS, AND NOTHING
 * ELSE. There is deliberately no `try` at any public call site: the
 * translation lives here, once, so a public body cannot acquire-then-do or
 * do-then-acquire — it can only delegate. `QueueOrderTest` A2 requires the
 * head of each public body to be this call for the right region, with no
 * `try {` allowance.
 */
    private fun <T> ordered(
        region: QueueRegion,
        op: String,
        refused: (wire: String, cause: Throwable) -> T,
        body: () -> T,
    ): T = try {
        regionLock.withRegion(region) { fileFence.withFence(region, body) }
    } catch (t: Throwable) {
        val wire = refusalOf(t) ?: throw t
        traceRefusal(op, region, wire, waitedMsOf(t))
        refused(wire, t)
    }

    /**
 * The refusal vocabulary, as a reason suffix — layer-prefixed, ONE
 * derivation, and that derivation is NOT here.
 *
 * **[QueueLockRefusal] IS THE ONE HOME AND THIS DELEGATES TO IT.** The
 * two `when`s that used to live here were the second and third spellings
 * of a mapping `QueueRefusal` already had, and a second composition site
 * ([QueueDomainRegion]) would have needed a fourth. A layer added later
 * must gain its prefix in exactly one place.
 */
    private fun refusalOf(t: Throwable): String? = QueueLockRefusal.wireOf(t)

    private fun waitedMsOf(t: Throwable): Long = QueueLockRefusal.waitedMsOf(t)

    private fun traceRefusal(op: String, region: QueueRegion, wire: String, waitedMs: Long) {
        RecoveryTrace.log(
            "${op}_refused", "region" to region.name, "why" to wire,
            "waitedMs" to waitedMs, "store" to file.name,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun appendRefused(wire: String, cause: Throwable): AppendResult =
        AppendResult(false, "append_refused_$wire", Durability.NOT_COMMITTED)

    /**
 * The crash path's OWN refusal name. A crash record lost to a lock refusal
 * is a different fact from an ordinary record refused by one, and the
 * reader of `crash_append_refused_*` must not have to infer which path
 * produced it from the region buried in a trace line.
 */
    @Suppress("UNUSED_PARAMETER")
    private fun crashAppendRefused(wire: String, cause: Throwable): AppendResult =
        AppendResult(false, "crash_append_refused_$wire", Durability.NOT_COMMITTED)

    @Suppress("UNUSED_PARAMETER")
    private fun removeRefused(wire: String, cause: Throwable): RemoveResult =
        RemoveResult(0, "remove_refused_$wire", Durability.NOT_COMMITTED)

    @Suppress("UNUSED_PARAMETER")
    private fun clearRefused(wire: String, cause: Throwable): ClearResult =
        ClearResult(false, "clear_refused_$wire")

    @Suppress("UNUSED_PARAMETER")
    private fun compactRefused(wire: String, cause: Throwable): CompactResult = CompactResult(
        when (cause) {
            is QueueRegionLock.Unavailable -> CompactOutcome.REFUSED_REGION_UNAVAILABLE
            // EXHAUSTIVE, WITH NO `else`. A new fence reason must be placed
            // here deliberately; a fall-through would report our own defect —
            // or an interrupted wait — as "another process has the queue".
            is QueueFileFence.Unavailable -> when (cause.reason) {
                QueueFileFence.Reason.CROSS_PROCESS_BUSY ->
                    CompactOutcome.REFUSED_QUEUE_BUSY_CROSS_PROCESS
                QueueFileFence.Reason.LOCK_INVARIANT,
                QueueFileFence.Reason.REGION_NOT_HELD,
                -> CompactOutcome.REFUSED_LOCK_INVARIANT
                QueueFileFence.Reason.INTERRUPTED ->
                    CompactOutcome.REFUSED_REGION_UNAVAILABLE
            }
            else -> throw cause
        },
    )

    @Synchronized
    private fun appendLocked(plaintext: ByteArray): AppendResult {
        // F4-P2 child — GATE B OF THE RECONCILIATION MUTATION GATE, FIRST, UNDER THE
        // LOCK PAIR. Both production append entries (`append`, `appendFromCrashHandler`)
        // reach this twin and nothing else does, so every production append re-asks the
        // ONE predicate (`QueueReconciler.mutationBlockReason`) here — inside the region
        // and fence the reconciler also holds while it writes the fact — and refuses
        // TYPED, bytes untouched, when a durable fact has bound this image. The producer's
        // existing refusal settlement (`LOST(APPEND_REFUSED)`) finalizes a ticket the
        // fact raced; no new loss cause exists for it.
        com.tracescout.mobile.internal.queue.QueueReconciler.mutationBlockReason(reconciliationFact)?.let { why ->
            RecoveryTrace.log("append_refused_reconciliation", "why" to why, "store" to file.name)
            return AppendResult(false, why, Durability.NOT_COMMITTED)
        }
        if (plaintext.isEmpty()) return AppendResult(false, "empty_record", Durability.NOT_COMMITTED)
        if (plaintext.size > MAX_FRAME_BYTES) return AppendResult(false, "record_too_large", Durability.NOT_COMMITTED)

        // THE ONE FRAMING (F2): sealed by GcmFrame, byte-identical to the inline
        // seal this replaced — pinned against a pre-extraction fixture file.
        val frame = GcmFrame.seal(key, plaintext, GcmFrame.NO_AAD, random)

        if (file.exists() && file.length() + frame.size > maxBytes) {
            // REFUSE, NEVER EVICT — FOR EVERY POLICY (F4-P0 Slice C). The
            // implicit `evictOldest` that used to run here was a second
            // destructive writer with no durable intent and no attribution;
            // it is deleted, and room is made only by the governed bracket
            // through `compactAndAppend`. Two DISTINCT reasons survive, because
            // the bracket can act on one and not the other: `at_capacity` says
            // a victim set might make room; `record_exceeds_capacity` says
            // even an emptied store could not hold this record, so no victim
            // set can help and dropping everything to try would be worse than
            // refusing. Neither is `io_error`: capacity cannot clear by itself,
            // and a caller that retried it on the transient backoff would spin
            // against a condition that can never change.
            return if (frame.size.toLong() > maxBytes)
                AppendResult(false, "record_exceeds_capacity", Durability.NOT_COMMITTED)
            else
                AppendResult(false, "at_capacity", Durability.NOT_COMMITTED)
        }

        // THE DURABILITY FLOOR IS THE PRIMITIVE'S, NOT AN INLINE COPY OF IT.
        // This used to `mkdirs` + write + `fd.sync` and return `true` — which
        // commits the BYTES and, on the very first append, leaves the DIRECTORY
        // ENTRY that makes them reachable unsynced. After a power loss the file
        // can be absent entirely while the caller was told the record was
        // stored. The partial-write rollback also lives in the primitive now,
        // so the two write paths cannot drift apart on it.
        return when (DurableReplace.appendDurably(file, frame, dirSync)) {
            DurableReplace.Outcome.COMMITTED ->
                AppendResult(true, null, Durability.COMMITTED)

            // STORED, AND SAYING SO WOULD BE HALF THE TRUTH. The bytes are
            // fsynced and the file is readable now; what is unconfirmed is
            // whether a crash would still find it under this name. `stored` is
            // true because the record IS in the store and re-appending it would
            // duplicate it; `durability` is what stops a caller treating that
            // as a survival guarantee.
            DurableReplace.Outcome.INDETERMINATE_DIRECTORY_UNSYNCED -> {
                RecoveryTrace.log("append_durability_indeterminate",
                    "why" to "directory_unsynced", "store" to file.name)
                AppendResult(true, null, Durability.INDETERMINATE_DIRECTORY_UNSYNCED)
            }

            DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED ->
                AppendResult(false, "io_error", Durability.NOT_COMMITTED)
        }
    }

    /**
 * Read every intact record.
 *
 * A frame that fails length sanity or GCM authentication is skipped and
 * COUNTED. A truncated tail — the signature of process death mid-append —
 * ends the read cleanly and is reported, because everything before it is
 * still perfectly good data.
 */
    fun readAll(): ReadResult = readAllLocked()

    /**
 * DELIBERATELY NOT ORDERED, and this is a decision rather than an
 * omission — but NOT for the reason an earlier version of this comment
 * gave. It claimed "every mutation is a whole-file atomic rename, so a
 * reader sees one complete image or the other". **That is false for the
 * append path.** `removeFirst` and `compactAndAppend` do rewrite through
 * `DurableReplace.replace` (temp file, fsync, atomic rename), but `append`
 * writes IN PLACE through `DurableReplace.appendDurably`. A standalone read
 * concurrent with an append can therefore observe a partially written trail
 * frame, which is exactly the torn tail the reader already handles: the
 * scan stops at it, reports `truncatedTail`, and returns every intact
 * record before it.
 *
 * So the honest statement is:
 *
 * * a standalone observational read may see a truncated or torn tail, and
 * may report itself untrustworthy (`discarded > 0`, `truncatedTail`);
 * * such a read is fine for observation and **MUST NOT authorise a
 * destructive mutation or a recovery verdict** — `removeFirstLocked`
 * refuses on exactly that condition, and `RecordStore` carries the
 * integrity class for the same reason;
 * * every read that IS a mutation premise runs under the region lock and
 * the file fence, through [QueueHome.withQueue] or inside an ordered
 * wrapper's own monitor twin, where the read and the mutation share one
 * acquisition and one image.
 *
 * Ordering standalone reads would put both lock layers on the hot capture
 * path to buy a property the mutation-premise rule already gives.
 */
    @Synchronized
    private fun readAllLocked(): ReadResult {
        if (!file.exists()) return ReadResult(emptyList(), 0, emptyList(), false)

        val out = ArrayList<ByteArray>()
        val reasons = ArrayList<String>()
        var discarded = 0
        var truncatedTail = false

        try {
            val bytes = file.readBytes()
            var off = 0
            while (off + 4 <= bytes.size) {
                val len = readInt(bytes, off)
                // A nonsensical length means the file is corrupt from here on;
                // stop rather than seek to an arbitrary offset.
                if (len <= IV_BYTES || len > MAX_FRAME_BYTES) {
                    discarded++
                    reasons.add("frame_length_invalid")
                    truncatedTail = true
                    break
                }
                if (off + 4 + len > bytes.size) {
                    // The classic torn write. Everything before it is intact.
                    truncatedTail = true
                    break
                }
                try {
                    out.add(GcmFrame.open(key, bytes, off + 4, len, GcmFrame.NO_AAD))
                } catch (t: Throwable) {
                    // Auth failure: tampered or corrupted. Never partially parsed.
                    discarded++
                    reasons.add("frame_auth_failed")
                }
                off += 4 + len
            }
        } catch (t: Throwable) {
            // A store we cannot read at all is discarded wholesale rather than
            // crashing the host app on launch.
            return ReadResult(out, discarded + 1, reasons + "store_unreadable", truncatedTail)
        }

        return ReadResult(out, discarded, reasons, truncatedTail)
    }

    /** Drop the first [count] intact records and rewrite. Used after a successful upload. */
    /**
 * Remove the first [count] records, returning how many were ACTUALLY
 * removed.
 *
 * THIS USED TO LIE. It called `rewrite(keep)` — which swallowed every
 * error and returned nothing — and then reported `minOf(count, all.size)`
 * regardless. A failed durable rewrite therefore reported success, so the
 * upload pipeline could settle its in-flight descriptor and report
 * `terminalMoved` while the active prefix was still on disk. That is false
 * progress: usually recoverable because the parked copy exists, but it
 * breaks the crash/reconciliation contract and creates repeated local
 * churn.
 *
 * Now: `count` ONLY if the rewrite committed, otherwise `0` with the
 * original file left authoritative.
 */
    fun removeFirst(count: Int): Int = removeFirstDetailed(count).removed

    /**
 * The typed removal. **The acquisition lives here and only here**;
 * [removeFirst] is a whole-body delegation to it.
 *
 * WHY A SECOND SURFACE EXISTS. An `Int` cannot carry WHY nothing moved,
 * and the ruling that produced the two-layer order forbids folding a
 * cross-process holder, an internal lock-invariant violation and a crash
 * budget expiry into one reason. `0` remains the fail-closed number every
 * existing caller already handles — it is what an untrustworthy read and a
 * failed rewrite both return — so nothing downstream has to change to stay
 * correct; a caller that wants the reason asks for it.
 */
    fun removeFirstDetailed(count: Int): RemoveResult =
        ordered(QueueRegion.REMOVE_FIRST, "remove", ::removeRefused) {
            removeFirstLocked(count)
        }

    /**
 * [removed] is authoritative; [refusal] is non-null ONLY when a lock layer
 * refused, and names which layer and why.
 *
 * **[durability] HAS NO DEFAULT, AND THE BREAKAGE IS THE POINT.** The
 * same reason `AppendResult.durability` has none: a default of
 * `COMMITTED` would claim durability on behalf of a caller that never
 * stated it, and a default of `NOT_COMMITTED` would silently report every
 * successful removal as undurable. Adding the field with no default breaks
 * at every construction site, which is what forces each one to say which
 * of the three states it actually observed.
 *
 * **IT DOES NOT CHANGE WHAT [removed] MEANS.** `removeFirst(count): Int`
 * is byte-identical in behaviour and `refusal`'s meaning is unchanged. An
 * INDETERMINATE rewrite still reports `removed = 0`, because `0` is the
 * safe ACTION — the caller keeps retaining, and a repeat removal of an
 * already-removed prefix is caught by the shift guard. What is new is that
 * the third state is now VISIBLE to a caller that needs it, instead of
 * being collapsed into the same `0` a failed rewrite produces. Collapsing
 * those two is exactly the state this phase forbids becoming invisible.
 */
    data class RemoveResult(
        val removed: Int,
        val refusal: String?,
        val durability: Durability,
    )

    @Synchronized
    private fun removeFirstLocked(count: Int): RemoveResult {
        // F4-P2 C2 — THE RECONCILIATION MUTATION GATE, FIRST, UNDER THE LOCK PAIR
        // (a product decision: `mutationBlockReason != null` freezes the ENTIRE ordinary
        // mutation surface). Every production removal — the delivered dequeue, the PARK
        // removal, `reconcileDelivered`, `reconcileParked` — reaches this twin through
        // `removeFirstDetailed`, so the SAME predicate Gate B asks is asked here, inside
        // the REMOVE_FIRST region and fence the reconciler also holds in RECONCILE while
        // it writes the fact. A blocked queue removes NOTHING: `0`, the fail-closed answer
        // every caller already handles, with the typed reason in the trace. The ONLY
        // rewrites a bound image may receive are the governed `consumeReconciled` (RECONCILE)
        // and the erasure teardown (TEARDOWN).
        com.tracescout.mobile.internal.queue.QueueReconciler.mutationBlockReason(reconciliationFact)?.let { why ->
            RecoveryTrace.log("store_remove_refused", "op" to "removeFirst", "why" to why, "want" to count)
            return RemoveResult(0, null, Durability.NOT_COMMITTED)
        }
        if (count <= 0) return RemoveResult(0, null, Durability.NOT_COMMITTED)
        val read = readAllLocked()

        // A LOSSY READ CANNOT AUTHORISE A REWRITE — the rule
        // `compactAndAppend` already states, now enforced here too.
        // `rewrite(keep)` writes back ONLY the survivors, so a successful
        // removeFirst over a store with a corrupt or torn frame permanently
        // DESTROYS that frame: the operation that makes room erases the
        // evidence the accounting needs, turning a recoverable corrupt tail
        // into unattributable loss.
        //
        // KNOWN PHASE-0 CONSEQUENCE, STATED RATHER THAN HIDDEN: with no
        // reconciler yet, a store holding a corrupt frame cannot dequeue, so
        // an acknowledged prefix is re-submitted (the server de-duplicates it)
        // until reconciliation lands. Refusing is the lesser harm — a stall is
        // visible and recoverable; a destroyed frame is neither.
        if (read.discarded != 0 || read.truncatedTail) {
            RecoveryTrace.log("store_remove_refused", "op" to "removeFirst",
                "why" to "untrustworthy_read", "reason" to read.integrityClass,
                "want" to count,
                "discarded" to read.discarded, "torn" to read.truncatedTail)
            return RemoveResult(0, null, Durability.NOT_COMMITTED)
        }

        val all = read.records
        val keep = if (count >= all.size) emptyList() else all.subList(count, all.size)
        return when (rewrite(keep)) {
            DurableReplace.Outcome.COMMITTED ->
                RemoveResult(minOf(count, all.size), null, Durability.COMMITTED)

            DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED -> {
                RecoveryTrace.log("store_rewrite_failed", "op" to "removeFirst", "want" to count)
                RemoveResult(0, null, Durability.NOT_COMMITTED)
            }

            // THE THIRD STATE, AND `0` IS THE SAFE **ACTION**, NOT A CLAIM
            // THAT NOTHING HAPPENED. The atomic move succeeded, so the records
            // may already be gone; the directory entry is unconfirmed, so after
            // a crash they may be back. Reporting `count` would authorise
            // finalising a removal that may un-happen; reporting `0` keeps the
            // caller retaining, and a repeat removal of an already-removed
            // prefix is caught by the shift guard rather than destroying
            // anything. The FACT is recorded distinctly so an operator can see
            // an indeterminate durable write, which is the collapse this phase
            // forbids: what must never happen is the state becoming invisible.
            DurableReplace.Outcome.INDETERMINATE_DIRECTORY_UNSYNCED -> {
                RecoveryTrace.log("store_rewrite_indeterminate", "op" to "removeFirst",
                    "why" to "directory_unsynced", "want" to count)
                RemoveResult(0, null, Durability.INDETERMINATE_DIRECTORY_UNSYNCED)
            }
        }
    }

    /**
 * Destroy every record.
 *
 * IT ANSWERS A TYPED RESULT RATHER THAN `Unit`, BECAUSE A `Unit` RETURN
 * CANNOT REPORT A REFUSAL AT ALL. Under the previous shape a lock refusal
 * would either have thrown into a caller that had no branch for it, or been
 * swallowed — and a `clear` that quietly did nothing is the most
 * dangerous of the four: the caller believes the queue is empty. Statement
 * use still compiles, so no existing call site changes.
 */
    fun clear(): ClearResult =
        ordered(QueueRegion.CLEAR, "clear", ::clearRefused) { clearLocked()?.let { ClearResult(false, it) } ?: ClearResult(true, null) }

    /**
 * [cleared] is false when a lock layer refused OR when the reconciliation
 * mutation gate refused (F4-P2 C3); [refusal] says which, in the gate's own
 * reason vocabulary.
 */
    data class ClearResult(val cleared: Boolean, val refusal: String?)

    /**
 * Returns `null` when the file was deleted, else the typed refusal.
 *
 * F4-P2 C3 — THE RECONCILIATION MUTATION GATE, FIRST, UNDER THE LOCK
 * PAIR, ON THE FOURTH ORDINARY MUTATOR. `clear` was left ungated in C2 as
 * "teardown-shaped with no ordinary production caller" — and the reviewer's
 * visibility check settled it the other way: `TraceScoutMobile.resetForTest`
 * is a PUBLIC `@JvmStatic` member of a public object, shipped in the release
 * AAR with no `explicitApi`, no `@RestrictTo` and an empty consumer-rules
 * file, and it reaches `store?.clear`. A doc comment is not an enforcement
 * boundary. So the SAME predicate the other three twins ask is asked here,
 * inside the CLEAR region and fence: a bound image is never deleted by an
 * ordinary clear — typed, zero mutation. The ONLY destructive paths that may
 * touch a bound image remain the governed `consumeReconciled` (RECONCILE)
 * and the erasure teardown (TEARDOWN), which destroys files directly and
 * never through this method. A fully resolved fact, or no fact, clears
 * exactly as before.
 */
    @Synchronized
    private fun clearLocked(): String? {
        com.tracescout.mobile.internal.queue.QueueReconciler.mutationBlockReason(reconciliationFact)?.let { why ->
            RecoveryTrace.log("store_clear_refused", "why" to why, "store" to file.name)
            return why
        }
        file.delete()
        return null
    }

    /**
 * NEITHER ORDERED NOR SYNCHRONIZED. It reads one `long` from the
 * filesystem and is never a premise for a mutation; holding the monitor for
 * it would be a monitor acquisition with no lock layer above it, which is
 * the exact shape the one-order rule removes.
 */
    fun sizeBytes(): Long = if (file.exists()) file.length() else 0

    /**
 * The byte cap this store refuses at. Read by the eviction bracket's
 * planner, which must plan against the SAME number `compactAndAppend`
 * enforces — a planner carrying its own copy of the cap is how a plan
 * that "fits" gets refused `REFUSED_OVER_CAPACITY`, or worse, how a cap
 * stops being a cap.
 */
    fun capacityBytes(): Long = maxBytes

    // The on-disk size arithmetic lives ONCE, in the companion (`encodedSize`):
    // the one formula `compactAndAppend`'s capacity check and the bracket's
    // planner share. A planner with its own formula is a second opinion about
    // what fits.

    /**
 * Rewrite via a temp file + atomic rename, so a crash during compaction
 * leaves either the old store or the new one — never a half-written store.
 */
    /**
 * Replace the file's whole contents, atomically.
 *
 * FAIL-CLOSED, AND THE `copyTo` FALLBACK IS GONE. A failed `renameTo`
 * used to fall back to `copyTo(overwrite = true)`, which is NOT an atomic
 * replacement: a process death partway through leaves a half-copied
 * destination, i.e. the store's trailing healthy records destroyed by the
 * operation that was meant to preserve them. Returning false and leaving
 * the OLD file authoritative is always better than a half-written one.
 */
    // ══════════════════════════════════════════════════════════════════════
    // compactAndAppend — the ONE atomic "remove selected records AND add this
    // one" operation. Exactly ONE production caller, by ruling: the eviction
    // bracket's commit (`internal/queue/EvictionBracket`), reached only inside
    // a BRACKET region with every victim's durable intent already written.
    // ══════════════════════════════════════════════════════════════════════

    /**
 * The outcome of a [compactAndAppend]. A boolean cannot carry these apart,
 * and the difference between "nothing happened" and "we cannot tell what
 * happened" is the whole point of a transaction.
 */
    enum class CompactOutcome {
        /** The new logical state is durable and was VERIFIED by read-back. */
        COMMITTED,

        /** The store is not in the state the caller planned against. Zero mutation. */
        REFUSED_OLD_STATE_MISMATCH,

        /** The store could not be read completely/trustworthily. Zero mutation. */
        REFUSED_UNTRUSTWORTHY_READ,

        /** The planned result does not fit. Zero mutation, and NO extra eviction. */
        REFUSED_OVER_CAPACITY,

        /** The keep plan is not a valid subsequence of the current state. Zero mutation. */
        REFUSED_INVALID_PLAN,

        /**
 * F4-P2 C2: a durable reconciliation fact still BINDS this image
 * (`QueueReconciler.mutationBlockReason` non-null — PREPARED / SETTLED /
 * an unresolved CONSUMED, including a pending owner-bound clear). Zero
 * mutation; the governed reconciliation alone may release it.
 */
        REFUSED_RECONCILIATION_BLOCKED,

        // THREE LOCK REFUSALS, NEVER ONE. Zero mutation in all three, and a
        // caller that folded them would retry an invariant violation on a
        // contention backoff, against a condition that cannot clear.

        /**
 * Another PROCESS holds the install. Ordinary, expected, and clears on
 * its own — the only one of the three that is worth a plain retry.
 */
        REFUSED_QUEUE_BUSY_CROSS_PROCESS,

        /**
 * This process holds an OS lock on the domain outside the fence, or
 * reached the fence without the region lock. A
 * defect in our own code, not a condition that clears. Typed here
 * rather than thrown so a legitimate second owner cannot crash a
 * shipped app; `QueueFileFence.strictInvariants` makes it fatal where
 * fatal is safe.
 */
        REFUSED_LOCK_INVARIANT,

        /**
 * The SAME-PROCESS region lock refused — only reachable for a BOUNDED
 * region's expired budget or an interrupted wait (at either layer),
 * because an ordinary region waits for the owning thread rather than
 * refusing.
 */
        REFUSED_REGION_UNAVAILABLE,

        /** The rewrite did not commit. The OLD file remains authoritative. */
        FAILED_OLD_RETAINED,

        /**
 * The rename SUCCEEDED and the read-back does NOT match the expected
 * new fingerprint. The store is neither provably old nor provably the
 * planned new state. The caller MUST fail closed: no loss may be
 * finalized and no further eviction may be attempted.
 */
        AMBIGUOUS_POST_COMMIT_MISMATCH,

        /**
 * The atomic move SUCCEEDED and the parent-directory fsync did
 * not, so the new state may not survive a crash. Distinct from
 * [AMBIGUOUS_POST_COMMIT_MISMATCH]: there the bytes read back WRONG,
 * here they read back right and may not persist. Two different facts
 * with two different remedies; one value for both would make the
 * diagnosis impossible. The caller must fence — no loss may be
 * finalized, no further eviction attempted.
 */
        INDETERMINATE_DIRECTORY_UNSYNCED,
    }

    data class CompactResult(
        val outcome: CompactOutcome,
        /** Records removed. Meaningful only on COMMITTED. */
        val removed: Int = 0,
        /** Records kept. Meaningful only on COMMITTED. */
        val kept: Int = 0,
    )

    /**
 * ATOMICALLY replace the active queue with `keepIndices` (in their existing
 * order) plus `incomingRecord` appended last.
 *
 * ## Why this primitive has to exist
 *
 * `append` is a plain append+fsync and `rewrite` is a whole-file atomic
 * replacement, and they are SEPARATE operations. So today there is no way
 * to drop records and add a record in one commit — and either ordering has
 * a crash window that is not acceptable for planned loss:
 *
 * ledger first, crash before eviction -> a declared loss that never happened
 * queue first, crash before ledger -> a real loss that is undisclosed
 *
 * The caller's PREPARED intent is the write-ahead log; this is the single
 * atomic step it brackets. There is deliberately no second transaction log
 * in here.
 *
 * ## What this knows, and what it must never know
 *
 * It knows: expected old logical state, the exact keep set, the exact
 * incoming bytes, expected new logical state. It knows NOTHING about
 * sessions, loss families, current-vs-historical, pressure policy or
 * checkpoints — those are the planner's meaning, not the store's.
 *
 * ## Guarantees
 *
 * - the read must be COMPLETE and trustworthy, or nothing happens;
 * - the old logical fingerprint must match EXACTLY, or nothing happens;
 * - the result is exactly `kept + incoming`, in that order;
 * - it must fit `maxBytes`, or nothing happens — **no eviction happens
 * inside this primitive**, ever. If the plan does not free enough space
 * the answer is REFUSED, not "free some more";
 * - one temp file, fsync'd, atomically renamed — no `append` call, no
 * best-effort fallback;
 * - COMMITTED is returned ONLY after the new state is re-read and its
 * fingerprint matches what the caller predicted.
 *
 * @param expectedOldLogicalFingerprint [logicalFingerprint] of the state the
 * caller planned against.
 * @param keepIndices indices into the CURRENT logical order to retain, in
 * ascending order. Indices are exact because the old fingerprint pins the
 * exact old state; a mistaken plan is caught anyway by the new-fingerprint
 * verification, which is the real safety net.
 * @param incomingRecord the already-masked plaintext envelope to append —
 * or **`null` for a RETAIN-ONLY compaction** (F4-P1): the result is
 * exactly `kept`, nothing is appended. The quarantine bracket's commit
 * removes an indicted prefix and adds no record; every other guarantee
 * (complete read, exact old state, exact plan, cap, one atomic rename,
 * read-back verification) is identical, so the prefix removal commits
 * under the same fingerprint pin and the same post-commit verdict as a
 * byte eviction. An EMPTY record is still a record and is still appended;
 * only `null` means "none".
 * @param expectedNewLogicalFingerprint what the caller predicts the result
 * to be. A mismatch is never silently accepted.
 */
    fun compactAndAppend(
        expectedOldLogicalFingerprint: String,
        keepIndices: List<Int>,
        incomingRecord: ByteArray?,
        expectedNewLogicalFingerprint: String,
    ): CompactResult = ordered(QueueRegion.COMPACT, "compact", ::compactRefused) {
        compactAndAppendLocked(
            expectedOldLogicalFingerprint, keepIndices, incomingRecord,
            expectedNewLogicalFingerprint,
        )
    }

    @Synchronized
    private fun compactAndAppendLocked(
        expectedOldLogicalFingerprint: String,
        keepIndices: List<Int>,
        incomingRecord: ByteArray?,
        expectedNewLogicalFingerprint: String,
    ): CompactResult {
        // F4-P2 C2 — THE RECONCILIATION MUTATION GATE, FIRST, UNDER THE LOCK PAIR.
        // Both bracket commits (the byte eviction and the quarantine's retain-only
        // rewrite) reach this twin and nothing else does; the SAME predicate Gate B
        // asks is asked here, inside the COMPACT region and fence. A bound image is
        // never rewritten by an ordinary compaction — typed, zero mutation.
        com.tracescout.mobile.internal.queue.QueueReconciler.mutationBlockReason(reconciliationFact)?.let { why ->
            RecoveryTrace.log("compact_refused", "why" to "reconciliation_blocked", "reason" to why)
            return CompactResult(CompactOutcome.REFUSED_RECONCILIATION_BLOCKED)
        }
        val read = readAllLocked()

        // A LOSSY READ CANNOT AUTHORISE A REWRITE. Rewriting from records we
        // know we failed to read would DESTROY the unread ones — turning a
        // recoverable corrupt tail into permanent loss, by the operation whose
        // entire purpose is to account for loss honestly.
        if (read.discarded != 0 || read.truncatedTail) {
            RecoveryTrace.log("compact_refused", "why" to "untrustworthy_read",
                "reason" to read.integrityClass,
                "discarded" to read.discarded, "torn" to read.truncatedTail)
            return CompactResult(CompactOutcome.REFUSED_UNTRUSTWORTHY_READ)
        }

        val current = read.records
        if (logicalFingerprint(current) != expectedOldLogicalFingerprint) {
            // Someone appended, drained or parked since the plan was made.
            RecoveryTrace.log("compact_refused", "why" to "old_state_mismatch")
            return CompactResult(CompactOutcome.REFUSED_OLD_STATE_MISMATCH)
        }

        // A plan must be a genuine ascending subsequence of the current state.
        var prev = -1
        for (i in keepIndices) {
            if (i <= prev || i < 0 || i >= current.size) {
                RecoveryTrace.log("compact_refused", "why" to "invalid_plan")
                return CompactResult(CompactOutcome.REFUSED_INVALID_PLAN)
            }
            prev = i
        }

        val kept = keepIndices.map { current[it] }
        // F4-P1: `null` = retain-only — the result is exactly `kept`.
        val next = if (incomingRecord == null) kept else kept + listOf(incomingRecord)

        // REFUSE, DO NOT MAKE ROOM. Evicting here would destroy records the
        // planner never selected and never accounted for — silent loss inside
        // the loss-accounting mechanism.
        if (encodedSize(next) > maxBytes) {
            RecoveryTrace.log("compact_refused", "why" to "over_capacity")
            return CompactResult(CompactOutcome.REFUSED_OVER_CAPACITY)
        }

        when (rewrite(next)) {
            DurableReplace.Outcome.COMMITTED -> Unit
            DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED -> {
                RecoveryTrace.log("compact_failed", "why" to "rewrite_not_committed")
                return CompactResult(CompactOutcome.FAILED_OLD_RETAINED)
            }
            // THE ONE PLACE THIS MATTERS MOST. This primitive exists so a
            // planned loss and the queue mutation that enacts it commit
            // together; an unconfirmed directory entry means the caller cannot
            // know which side of that boundary it is on, and must fence rather
            // than finalise. Deliberately NOT folded into
            // AMBIGUOUS_POST_COMMIT_MISMATCH: that value means the bytes read
            // back wrong, this one means the write may not survive a crash.
            // Two different remedies, two different values.
            DurableReplace.Outcome.INDETERMINATE_DIRECTORY_UNSYNCED -> {
                RecoveryTrace.log("compact_indeterminate", "why" to "directory_unsynced")
                return CompactResult(CompactOutcome.INDETERMINATE_DIRECTORY_UNSYNCED)
            }
        }

        // PROVE IT. A successful rename is not proof the bytes decrypt back
        // to the planned records — and this is the one moment where a wrong
        // answer authorises finalising a loss that may not match what happened.
        val after = readAllLocked()
        val actual = logicalFingerprint(after.records)
        if (after.discarded != 0 || after.truncatedTail || actual != expectedNewLogicalFingerprint) {
            RecoveryTrace.log("compact_ambiguous", "discarded" to after.discarded,
                "torn" to after.truncatedTail, "count" to after.records.size)
            return CompactResult(CompactOutcome.AMBIGUOUS_POST_COMMIT_MISMATCH)
        }

        RecoveryTrace.log("compact_committed",
            "kept" to kept.size, "removed" to (current.size - kept.size))
        return CompactResult(CompactOutcome.COMMITTED,
            removed = current.size - kept.size, kept = kept.size)
    }

    /**
 * The fingerprint of the CURRENT logical state, or null when the store
 * cannot be read completely — a caller must not plan against a state it
 * could not fully observe.
 */
    fun currentLogicalFingerprint(): String? {
        val r = readAll()
        if (r.discarded != 0 || r.truncatedTail) return null
        return logicalFingerprint(r.records)
    }

    // ───────────────────────────── F4-P2: the exact image identity + governed CONSUMPTION

    /**
 * F4-P2 — the EXACT identity of the live image, read under the monitor: the
 * raw file bytes' sha256 and length (a byte of damage anywhere is a
 * different image), the integrity classification, the decoded survivors'
 * logical fingerprint and count. The reconciliation fact binds this, and
 * [consumeReconciled] refuses any image that is not byte-for-byte the one
 * the fact was written against. Read-only.
 */
    data class ImageIdentity(
        val imageSha256: String,
        val imageLength: Long,
        val integrityClass: String,
        val discarded: Int,
        val torn: Boolean,
        val discardReasons: List<String>,
        val survivorFingerprint: String,
        val survivorCount: Int,
    )

    data class ImageReading(val read: ReadResult, val identity: ImageIdentity)

    fun readImage(): ImageReading = readImageLocked()

    @Synchronized
    private fun readImageLocked(): ImageReading {
        // An unreadable file (U5) has no meaningful byte identity; it is never consumed, so the empty digest is honest here.
        val raw = try { if (file.exists()) file.readBytes() else ByteArray(0) } catch (_: Throwable) { ByteArray(0) }
        val read = readAllLocked()
        return ImageReading(read, ImageIdentity(
            com.tracescout.mobile.internal.queue.DeliveryProtection.sha256Hex(raw), raw.size.toLong(),
            read.integrityClass, read.discarded, read.truncatedTail, read.discardReasons,
            logicalFingerprint(read.records), read.records.size,
        ))
    }

    /** Why a reconciled consumption did or did not happen. The one success value is VERIFIED by read-back; every refusal is zero mutation. */
    enum class ConsumeOutcome {
        COMMITTED,
        /** The live image is TRUSTWORTHY — there is no corruption evidence to consume. */
        REFUSED_NOTHING_TO_CONSUME,
        /** The live image is not byte-for-byte the one the fact binds (digest, length, class, count or tail differ). */
        REFUSED_IMAGE_MOVED,
        /** The decoded survivors do not hash to the bound fingerprint. */
        REFUSED_SURVIVORS_MISMATCH,
        /** An UNKNOWN-cardinality class — a rewrite could destroy unreachable frames. */
        REFUSED_UNKNOWN_CARDINALITY,
        /** The queue region or file fence was unavailable. */
        REFUSED_QUEUE_UNAVAILABLE,
        /** The durable replace did not commit; the old file is intact. */
        FAILED_OLD_RETAINED,
        /** The rename succeeded but the directory entry is unconfirmed. */
        INDETERMINATE_DIRECTORY_UNSYNCED,
        /** The bytes read back wrong after the rename. */
        AMBIGUOUS_POST_COMMIT_MISMATCH,
    }

    data class ConsumeResult(val outcome: ConsumeOutcome, val detail: String? = null, val dropped: Int = 0, val kept: Int = 0)

    private fun consumeRefused(wire: String, cause: Throwable): ConsumeResult =
        ConsumeResult(ConsumeOutcome.REFUSED_QUEUE_UNAVAILABLE, wire)

    /**
 * F4-P2 (C14 reconciliation half; GAP_MATRIX F.3; a product decision) — rewrite
 * EXACTLY the decoded survivors of a LOSSY image whose loss has been durably
 * settled, dropping the frames that could not be decoded and nothing else.
 *
 * NOT `compactAndAppend`, deliberately: that is the eviction bracket's ONE
 * receiver and refuses a lossy image by contract. This primitive is legal ONLY
 * on a lossy image, only for the KNOWN-cardinality classes (`auth_failure`,
 * `torn_tail` — an `invalid_length_prefix` or unreadable image hides frames
 * that may still be intact behind the break), and only against the EXACT
 * image the reconciliation fact binds: raw digest, length, class, counts and
 * survivor fingerprint must all match or nothing is written. The keep set IS
 * every decoded record, by construction, so a decodable record can never be
 * dropped. Ordered on the ONE reconciliation region; one production caller.
 */
    fun consumeReconciled(expected: ImageIdentity): ConsumeResult =
        ordered(QueueRegion.RECONCILE, "consume", ::consumeRefused) { consumeReconciledLocked(expected) }

    @Synchronized
    private fun consumeReconciledLocked(expected: ImageIdentity): ConsumeResult {
        val img = readImageLocked()
        val read = img.read
        if (read.isTrustworthy) {
            RecoveryTrace.log("consume_refused", "why" to "nothing_to_consume")
            return ConsumeResult(ConsumeOutcome.REFUSED_NOTHING_TO_CONSUME)
        }
        val cls = read.integrityClass
        if (cls != "auth_failure" && cls != "torn_tail") {
            RecoveryTrace.log("consume_refused", "why" to "unknown_cardinality", "reason" to cls)
            return ConsumeResult(ConsumeOutcome.REFUSED_UNKNOWN_CARDINALITY, cls)
        }
        val id = img.identity
        if (id.imageSha256 != expected.imageSha256 || id.imageLength != expected.imageLength ||
            cls != expected.integrityClass || id.discarded != expected.discarded || id.torn != expected.torn) {
            RecoveryTrace.log("consume_refused", "why" to "image_moved", "reason" to cls,
                "discarded" to read.discarded, "torn" to read.truncatedTail)
            return ConsumeResult(ConsumeOutcome.REFUSED_IMAGE_MOVED, cls)
        }
        val survivors = read.records
        if (id.survivorFingerprint != expected.survivorFingerprint || id.survivorCount != expected.survivorCount) {
            RecoveryTrace.log("consume_refused", "why" to "survivors_mismatch", "count" to survivors.size)
            return ConsumeResult(ConsumeOutcome.REFUSED_SURVIVORS_MISMATCH)
        }
        when (rewrite(survivors)) {
            DurableReplace.Outcome.COMMITTED -> Unit
            DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED -> {
                RecoveryTrace.log("consume_failed", "why" to "rewrite_not_committed")
                return ConsumeResult(ConsumeOutcome.FAILED_OLD_RETAINED)
            }
            DurableReplace.Outcome.INDETERMINATE_DIRECTORY_UNSYNCED -> {
                RecoveryTrace.log("consume_indeterminate", "why" to "directory_unsynced")
                return ConsumeResult(ConsumeOutcome.INDETERMINATE_DIRECTORY_UNSYNCED)
            }
        }
        // PROVE IT — the consumed image must read back TRUSTWORTHY and be exactly the survivors.
        val after = readAllLocked()
        if (!after.isTrustworthy || logicalFingerprint(after.records) != expected.survivorFingerprint) {
            RecoveryTrace.log("consume_ambiguous", "discarded" to after.discarded, "torn" to after.truncatedTail, "count" to after.records.size)
            return ConsumeResult(ConsumeOutcome.AMBIGUOUS_POST_COMMIT_MISMATCH)
        }
        RecoveryTrace.log("consume_committed", "reason" to cls, "dropped" to read.discarded, "kept" to survivors.size, "torn" to read.truncatedTail)
        return ConsumeResult(ConsumeOutcome.COMMITTED, cls, dropped = read.discarded, kept = survivors.size)
    }

    private fun rewrite(records: List<ByteArray>): DurableReplace.Outcome =
        DurableReplace.replace(file, dirSync) { raf ->
            for (r in records) raf.write(GcmFrame.seal(key, r, GcmFrame.NO_AAD, random))
        }

    private fun readInt(buf: ByteArray, off: Int): Int = GcmFrame.readInt(buf, off)
}

package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.internal.queue.DurableUploadPipeline
import com.tracescout.mobile.internal.queue.ParkManifestRead
import com.tracescout.mobile.internal.queue.ParkStatus
import com.tracescout.mobile.internal.queue.ParkOutcome
import com.tracescout.mobile.internal.queue.ParkStore
import com.tracescout.mobile.internal.queue.ParkedBatch
import com.tracescout.mobile.internal.queue.RecordStore
import java.io.File
import javax.crypto.SecretKey

/**
 * The production [ParkStore] — the same encrypted-at-rest posture as the live
 * queue, because parked records are the SAME customer replay data that merely
 * cannot be delivered.
 *
 * Two [EncryptedEventStore]s under the SAME Keystore-backed key:
 *
 * - **records** — the exact record bytes, appended in order.
 * - **manifest** — one frame per complete parked batch, holding [ParkedBatch].
 *
 * Reusing the event store rather than inventing a second file format is
 * deliberate: it inherits the per-frame AES-256-GCM authentication, the
 * fsync-before-return durability, the torn-tail tolerance and the atomic
 * compaction that were paid for on real devices. A second, younger crypto
 * container beside a proven one is a liability, not a feature.
 *
 * **A crash mid-append can leave ORPHAN frames in the records store** — a
 * partial batch whose manifest entry was never written. They are preserved
 * forever and never counted as a batch: [park] verifies by re-reading the TAIL,
 * so a partial predecessor simply fails the match and the full batch is
 * appended after it. Keeping a few unreferenced encrypted frames is the correct
 * trade against the alternative, which is deleting bytes to tidy up.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class EncryptedParkStore(
    recordsFile: File,
    manifestFile: File,
    key: SecretKey,
    maxBytes: Long = EncryptedEventStore.DEFAULT_MAX_BYTES,
) : ParkStore {

    // PRESERVE, NOT THE ACTIVE QUEUE'S POLICY. This store's entire contract
    // is "write and read only — no delete, preserved losslessly", and it is
    // built on the same class as the active replay queue. With the default
    // oldest-first pressure policy it silently EVICTED already-parked customer
    // bytes the moment it filled: `append` reports `stored = true` AFTER an
    // eviction, and this class only ever looked at `.stored`.
    //
    // The manifest is the worse half. Evicting an old entry destroys the
    // durable knowledge that a session is already terminal — so later slices of
    // that dead session go back to the server for a fact the device had already
    // learned, which is exactly the zero-HTTP property A exists to create.
    private val records = EncryptedEventStore(
        recordsFile, key, maxBytes, EncryptedEventStore.EvictionPolicy.PRESERVE)
    private val manifest = EncryptedEventStore(
        manifestFile, key, MANIFEST_MAX_BYTES, EncryptedEventStore.EvictionPolicy.PRESERVE)
    private val recordsPath = recordsFile
    private val manifestPath = manifestFile

    /**
 * AN ORDERED WRAPPER, LIKE THE STORE'S OWN MUTATORS (Slice B). The park
 * write used to take THIS monitor first and reach the queue domain's
 * region lock only inside `records.append` — a `park monitor -> region`
 * path against the pipeline's `region -> park monitor` path, i.e. a
 * deadlock the moment the pipeline's park sequence became a region
 * (Slice A disclosure 1). The order is now `QUEUE REGION LOCK -> QUEUE
 * FILE FENCE -> park monitor -> store monitor` on every entry: the
 * pipeline calls this from inside its `PARK` region and re-enters both
 * layers; an un-regioned caller takes them here. A refused layer is a
 * typed [ParkOutcome.RETRYABLE_FAILURE] (the active bytes stay), never a
 * proceed-unlocked branch. Reads keep the monitor-only shape the store's
 * own reads have.
 */
    override fun park(batch: ParkedBatch, batchRecords: List<ByteArray>): ParkOutcome = try {
        records.regionLock().withRegion(QueueRegion.PARK) {
            records.fileFence().withFence(QueueRegion.PARK) { parkLocked(batch, batchRecords) }
        }
    } catch (u: QueueRegionLock.Unavailable) {
        RecoveryTrace.log("park_refused", "why" to "region_${u.reason.wire}")
        ParkOutcome.RETRYABLE_FAILURE
    } catch (u: QueueFileFence.Unavailable) {
        RecoveryTrace.log("park_refused", "why" to "fence_${u.reason.wire}")
        ParkOutcome.RETRYABLE_FAILURE
    }

    @Synchronized
    private fun parkLocked(batch: ParkedBatch, batchRecords: List<ByteArray>): ParkOutcome {
        if (batchRecords.isEmpty()) return ParkOutcome.RETRYABLE_FAILURE

        // AN UNREADABLE MANIFEST MAY NOT AUTHORISE A WRITE.
        //
        // Every decision below rests on "is this batch already parked?", and
        // that question is answered from the manifest. When the manifest could
        // not be read the honest answer is UNKNOWN — and the old code turned
        // unknown into NO, then appended a duplicate copy of every record of a
        // batch that may already be preserved in full. The parked store has no
        // delete path, so that duplication is permanent.
        //
        // RETRYABLE, not BLOCKED: a torn tail is a condition a later write or
        // a later read can resolve, unlike exhausted capacity. The active bytes
        // are retained either way, which is the safe direction.
        val manifest0 = entriesRead()
        if (!manifest0.isTrustworthy) {
            RecoveryTrace.log("park_refused", "why" to "manifest_unreadable",
                "reason" to manifest0.integrityClass)
            return ParkOutcome.RETRYABLE_FAILURE
        }

        // Already durable? Then this is a re-park after a crash between the
        // parked write and the active removal. Writing again would duplicate
        // the parked copy — the one outcome the crash guarantee forbids.
        if (tailMatches(batch, batchRecords.size)) {
            ensureManifest(batch)
            return if (tailMatches(batch, batchRecords.size) && hasEntry(batch.chunkId))
                ParkOutcome.PARKED else ParkOutcome.RETRYABLE_FAILURE
        }

        for (r in batchRecords) {
            // A REFUSAL HERE LEAVES EVERY EXISTING PARKED RECORD UNTOUCHED
            // and the caller retains the active bytes. `at_capacity` is
            // reported distinctly so a caller cannot put a condition that
            // cannot self-clear on the same retry path as a transient I/O
            // failure.
            val ar = records.append(r)
            if (!ar.stored) {
                RecoveryTrace.log("park_append_refused", "reason" to (ar.reason ?: ""))
                // CARRY THE DISTINCTION UPWARD. #146 made the store report
                // `at_capacity` separately from `io_error` because capacity
                // cannot clear by itself; collapsing them here would put a
                // permanently blocked condition on a bounded retry loop.
                return if (ar.reason == "at_capacity" || ar.reason == "record_exceeds_capacity")
                    ParkOutcome.BLOCKED else ParkOutcome.RETRYABLE_FAILURE
            }
            // THERE USED TO BE AN `ar.evicted != 0` CHECK HERE, traced as
            // `park_evicted_unexpectedly`. Since F4-P0 Slice C `append` has no
            // eviction path at all and no `evicted` field: the preservation claim
            // no longer rests on a runtime check of a count, it rests on the
            // STRUCTURE of the store (`SliceCSourceTest` pins that `append`'s body
            // carries no destructive branch). A check of a fact that cannot be
            // false is a check that cannot fail, and it is removed rather than
            // kept as decoration.
        }

        // VERIFY by reading back, not by trusting the append's return value.
        // The caller deletes the active bytes on the strength of this answer.
        if (!tailMatches(batch, batchRecords.size)) return ParkOutcome.RETRYABLE_FAILURE

        ensureManifest(batch)
        return if (hasEntry(batch.chunkId)) ParkOutcome.PARKED
               else ParkOutcome.RETRYABLE_FAILURE
    }

    @Synchronized
    override fun entriesRead(): ParkManifestRead {
        val read = try {
            manifest.readAll()
        } catch (_: Throwable) {
            // Not "no entries" — no ANSWER. These are different facts and the
            // whole point of this type is that they stop sharing a value.
            return ParkManifestRead(emptyList(), RecordStore.INTEGRITY_UNREADABLE)
        }

        val seen = HashSet<String>()
        val out = ArrayList<ParkedBatch>()
        var undecodable = 0
        for (frame in read.records) {
            val decoded = decode(String(frame, Charsets.UTF_8))
            if (decoded == null) {
                // It decrypted and then did not parse. On THIS container —
                // a dedicated manifest holding nothing but entries — that is a
                // lost entry, not an unrelated frame. See ParkManifestRead for
                // why the iOS twin cannot and must not report this class.
                undecodable++
                continue
            }
            if (seen.add(decoded.chunkId)) out.add(decoded)
        }

        // First-observed damage wins, and a container-level failure outranks a
        // per-entry one: if frames were dropped at the framing layer we cannot
        // know what the undecodable count would even have been.
        val cls = when {
            !read.isTrustworthy -> read.integrityClass
            undecodable != 0 -> ParkManifestRead.ENTRY_UNDECODABLE
            else -> RecordStore.INTEGRITY_OK
        }
        if (cls != RecordStore.INTEGRITY_OK) {
            RecoveryTrace.log("park_manifest_untrustworthy",
                "reason" to cls, "readable_entries" to out.size, "undecodable" to undecodable)
        }
        return ParkManifestRead(out, cls)
    }

    @Synchronized
    override fun status(): ParkStatus {
        val read = entriesRead()
        return ParkStatus(
            batchCount = read.entries.size,
            recordCount = read.entries.sumOf { it.recordCount },
            bytes = safeLength(recordsPath) + safeLength(manifestPath),
            // SURFACED, NOT SWALLOWED. `batchCount: 0` on an unreadable
            // manifest is exactly the conflation this change removes; a
            // diagnostic that repeats it is worse than one that says nothing,
            // because an operator believes it.
            integrityClass = read.integrityClass,
        )
    }

    // -----------------------------------------------------------------------

    private fun safeLength(f: File): Long = try { if (f.exists()) f.length() else 0L } catch (_: Throwable) { 0L }

    /**
 * Is the parked store's TAIL exactly this batch?
 *
 * Identity is recomputed from the stored bytes with the SAME
 * content-addressed function the upload path uses, so "it is there" is
 * proven rather than asserted. Comparing chunkIds is equivalent to
 * comparing every record digest, which is why the manifest never has to
 * store digests at all.
 */
    private fun tailMatches(batch: ParkedBatch, n: Int): Boolean = try {
        val all = records.readAll().records
        all.size >= n &&
            DurableUploadPipeline.BatchIdentity
                .of(batch.sessionId, all.subList(all.size - n, all.size))
                .chunkId == batch.chunkId
    } catch (_: Throwable) { false }

    /**
 * FALSE ON AN UNREADABLE MANIFEST IS ONLY SAFE BECAUSE [park] HAS
 * ALREADY REFUSED. Every caller of this reaches it after that gate, so it
 * is never asked to turn an unknown into a decision. It is scoped private
 * for exactly that reason.
 */
    private fun hasEntry(chunkId: String): Boolean =
        entriesRead().entries.any { it.chunkId == chunkId }

    private fun ensureManifest(batch: ParkedBatch) {
        if (hasEntry(batch.chunkId)) return
        try { manifest.append(encode(batch).toByteArray(Charsets.UTF_8)) } catch (_: Throwable) { }
    }

    private fun encode(b: ParkedBatch): String = org.json.JSONObject()
        .put("chunkId", b.chunkId)
        .put("sessionId", b.sessionId)
        .put("firstSequence", b.firstSequence)
        .put("lastSequence", b.lastSequence)
        .put("recordCount", b.recordCount)
        .put("producerFamily", b.producerFamily)
        .put("producerVersion", b.producerVersion)
        .put("refusalCode", b.refusalCode)
        .put("firstSeenAtMs", b.firstSeenAtMs)
        .put("parkedAtMs", b.parkedAtMs)
        .toString()

    private fun decode(s: String): ParkedBatch? = try {
        val o = org.json.JSONObject(s)
        val chunkId = o.optString("chunkId")
        if (chunkId.isEmpty()) null else ParkedBatch(
            chunkId = chunkId,
            sessionId = o.optString("sessionId"),
            firstSequence = o.optString("firstSequence"),
            lastSequence = o.optString("lastSequence"),
            recordCount = o.optInt("recordCount"),
            producerFamily = o.optString("producerFamily"),
            producerVersion = o.optString("producerVersion"),
            refusalCode = o.optString("refusalCode"),
            firstSeenAtMs = o.optLong("firstSeenAtMs"),
            parkedAtMs = o.optLong("parkedAtMs"),
        )
    } catch (_: Throwable) { null }

    companion object {
        /** Metadata only; a few hundred bytes per batch. */
        private const val MANIFEST_MAX_BYTES: Long = 512L * 1024L
    }
}

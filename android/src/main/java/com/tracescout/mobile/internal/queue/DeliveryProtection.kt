package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.queue.DurableReplace
import com.tracescout.mobile.queue.F4Constants
import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File

/**
 * F4-P0 Slice B — the DURABLE, DIGEST-KEYED delivery-protection state of one
 * queue home, plus the durable no-progress
 * counter (RF4-49, E-5; the specification) that must survive a restart beside it.
 *
 * ## What it protects, and from what
 *
 * A record that has been SUBMITTED to the server, or whose delivery the
 * server has COMMITTED but whose bytes have not yet left the queue, must not
 * be chosen as an eviction victim by the bracket a later slice installs, and
 * must not be forgotten by a crash between any two steps of delivery. The
 * unit of that protection is the RECORD, identified by the sha256 of its own
 * bytes — never a batch. A batch is a *reference* to records: the 413 reslice
 * chain produces several batches over the same records, and REV3's
 * batch-entry ceiling turned that legitimate chain into a progress deadlock
 *. Here a reslice adds a REF over digests that are already protected
 * and consumes no capacity.
 *
 * ## The rules (each is a test in `DeliveryProtectionTest`)
 *
 * * **an open product question** one canonical [ProtectedRecord] per digest, however many batches
 * reference it;
 * * **an open product question** a reslice adds a ref, never a copy;
 * * **an open product question / an open product question** disposing a stale batch removes THAT ref only — the digest
 * entry stays until the queue mutation that removes the record COMMITS, or
 * the record is proven absent from a TRUSTWORTHY live read;
 * * **an open product question** a record's `strongest` state is a maximum — a later, weaker ref
 * never downgrades a committed one;
 * * **an open product question** an entry leaves only after the removal it protects committed
 * ([removed]) or on proof of absence ([reconcileAbsent]);
 * * **an open product question** at capacity, admitting a NEW batch that needs NEW digests is
 * refused; nothing already protected is ever deleted by a refusal.
 *
 * ## Capacity — derived bound, UNSET operational ceiling
 *
 * The number a caller passes to [admit] is the pipeline's; production passes
 * [F4Constants.protectionAdmissionCeiling], which is the DERIVED hard bound
 * ([F4Constants.PROTECTED_DIGESTS_REPRESENTABLE_MAX]) while the operational
 * [F4Constants.PROTECTION_CAPACITY_MAX] is UNSET — a digest can only be
 * protected while its record is in the queue, and the queue cannot hold more
 * than that. The operational ceiling stays UNSET and blocks F4 stage
 * acceptance (`F4StageAcceptanceGate`); nothing here narrows it.
 *
 * ## Durability
 *
 * One JSON document; every write is temp file → fsync → atomic rename →
 * parent-directory fsync ([DurableReplace.replace]) → read back and compare.
 * A write that cannot be verified is [Write.NotDurable] and the caller refuses
 * to POST (ruling B6: no silent fallback to memory-only protection). Not
 * encrypted: digests, ids, sequence bounds and counts only — no record
 * content, no text, no credential (the same argument as `InFlightStore`).
 *
 * ## State is always read from the file
 *
 * Every operation re-reads the file under this object's monitor and writes
 * the whole state back, so two objects over one file cannot disagree and a
 * process that died mid-drain leaves exactly the old set or the new set.
 *
 * Lock order: a caller holds the queue region (and fence) BEFORE entering
 * this monitor; this class never calls into the store, the region lock or
 * the fence, so no inversion is reachable from here.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class DeliveryProtection internal constructor(
    private val file: File,
    private val dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
) {

    /**
 * How strongly a record is protected. Declared WEAKEST FIRST so that an open product question's
 * maximum is the enum's natural order.
 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    enum class Strength {
        /** Frozen and submitted; no settled acknowledgement yet. */
        SUBMITTED,

        /** The batch boundary was refused (413) — write-free; a narrower batch will follow. */
        REFUSED_WRITE_FREE,

        /** Classified for a local terminal move (park); the move has not committed. */
        TERMINAL_LOCAL_MOVE,

        /** The server committed the delivery; the bytes are still in the queue. */
        COMMITTED_UNDEQUEUED,
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class ProtectedRecord(
        val digest: String,
        val refs: Set<String>,
        val firstAtMs: Long,
        val strongest: Strength,
    )

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class BatchRef(
        val refId: String,
        val chunkId: String,
        val sessionId: String,
        /** In batch order — lifecycle metadata; the byte-identical retry is `InFlightStore`'s. */
        val digests: List<String>,
        val state: Strength,
        val attempts: Int,
        val terminalReason: String?,
    )

    /**
 * The durable no-progress counter. `epoch` is the logical
 * fingerprint of the queue image the counted attempts were made against;
 * `blocker` is the typed reason the last attempt made no local progress.
 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class NoProgress(val count: Int, val epoch: String, val blocker: String) {
        companion object { val NONE = NoProgress(0, "", "") }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class State(
        val digests: Map<String, ProtectedRecord>,
        val batches: Map<String, BatchRef>,
        val noProgress: NoProgress,
    ) {
        fun protectedDigests(): Set<String> = digests.keys

        companion object { val EMPTY = State(emptyMap(), emptyMap(), NoProgress.NONE) }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Read {
        data class Ok(val state: State) : Read()

        /** The file exists and could not be read or parsed. NOT "nothing is protected". */
        data class Unreadable(val reason: String) : Read()
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Write {
        object Committed : Write()

        /** The state was not changed — a typed reason, no mutation. */
        data class Refused(val reason: String) : Write()

        /** The write could not be verified durable. The caller must not POST on it. */
        data class NotDurable(val reason: String) : Write()
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Admit {
        data class Ok(val refId: String, val newDigests: Int) : Admit()

        /** an open product question: the NEW digests would exceed [capacity]. Nothing was changed. */
        data class AtCapacity(val protectedNow: Int, val needed: Int, val capacity: Long) : Admit()

        data class NotDurable(val reason: String) : Admit()
        data class Unreadable(val reason: String) : Admit()
    }

    /** Where this state lives. Diagnostics only. */
    fun path(): String = file.path

    @Synchronized
    fun read(): Read = readFile()

    /** The protected digest set, or null when the state is unreadable (fail closed: the caller decides). */
    @Synchronized
    fun protectedDigests(): Set<String>? = when (val r = readFile()) {
        is Read.Ok -> r.state.protectedDigests()
        is Read.Unreadable -> null
    }

    @Synchronized
    fun noProgress(): NoProgress? = when (val r = readFile()) {
        is Read.Ok -> r.state.noProgress
        is Read.Unreadable -> null
    }

    /**
 * Admit a batch over [digests] BEFORE its first network attempt.
 *
 * Idempotent by [refId]: re-admitting a batch that is already referenced
 * (a resumed descriptor) changes nothing and answers Ok. Digests already
 * protected gain a ref and consume no capacity; only NEW digests are
 * counted against [capacity], and a refusal changes nothing.
 */
    @Synchronized
    fun admit(chunkId: String, sessionId: String, digests: List<String>, nowMs: Long, capacity: Long): Admit {
        val state = when (val r = readFile()) {
            is Read.Ok -> r.state
            is Read.Unreadable -> return Admit.Unreadable(r.reason)
        }
        val refId = refIdOf(chunkId, digests)
        if (state.batches.containsKey(refId)) return Admit.Ok(refId, 0)
        val fresh = digests.toSet().filterNot { state.digests.containsKey(it) }
        val after = state.digests.size + fresh.size
        if (after > capacity) {
            RecoveryTrace.log("protection_capacity_refused", "have" to state.digests.size,
                "need" to fresh.size, "chunk" to chunkId.take(12))
            return Admit.AtCapacity(state.digests.size, fresh.size, capacity)
        }
        val newDigests = HashMap(state.digests)
        for (d in digests) {
            val existing = newDigests[d]
            newDigests[d] = if (existing == null) {
                ProtectedRecord(d, setOf(refId), nowMs, Strength.SUBMITTED)
            } else {
                existing.copy(refs = existing.refs + refId)   // an open product question: strongest is untouched by a weaker ref
            }
        }
        val newBatches = HashMap(state.batches)
        newBatches[refId] = BatchRef(refId, chunkId, sessionId, digests, Strength.SUBMITTED, 0, null)
        return when (val w = writeFile(State(newDigests, newBatches, state.noProgress))) {
            is Write.Committed -> Admit.Ok(refId, fresh.size)
            is Write.NotDurable -> Admit.NotDurable(w.reason)
            is Write.Refused -> Admit.NotDurable(w.reason)
        }
    }

    /** Move a batch ref to [state]; each of its records takes the MAXIMUM of its current and [state]. */
    @Synchronized
    fun markState(refId: String, state: Strength, terminalReason: String? = null): Write =
        mutate("mark") { s ->
            val b = s.batches[refId] ?: return@mutate null
            val batches = HashMap(s.batches)
            batches[refId] = b.copy(state = state, terminalReason = terminalReason ?: b.terminalReason)
            val digests = HashMap(s.digests)
            for (d in b.digests) {
                val e = digests[d] ?: continue
                if (state.ordinal > e.strongest.ordinal) digests[d] = e.copy(strongest = state)
            }
            State(digests, batches, s.noProgress)
        }

    /**
 * an open product question / an open product question — dispose of ONE batch reference (a stale descriptor, a
 * superseded boundary). The digest entries stay: a record remains
 * protected until the mutation that removes it commits or a trustworthy
 * read proves it absent. Over-protection is the safe direction.
 */
    @Synchronized
    fun disposeRef(refId: String): Write = mutate("dispose") { s ->
        if (!s.batches.containsKey(refId)) return@mutate null
        val batches = HashMap(s.batches); batches.remove(refId)
        val digests = HashMap(s.digests)
        for ((d, e) in s.digests) if (refId in e.refs) digests[d] = e.copy(refs = e.refs - refId)
        State(digests, batches, s.noProgress)
    }

    /**
 * an open product question — a queue removal COMMITTED for records with these [digests]: their
 * entries leave, every ref drops them, and a ref with no protected record
 * left is disposed. This is REAL LOCAL PROGRESS, so the no-progress
 * counter is reset here.
 */
    @Synchronized
    fun removed(digests: Collection<String>): Write = mutate("removed") { s ->
        val gone = digests.toSet()
        if (gone.isEmpty()) return@mutate State(s.digests, s.batches, NoProgress.NONE)
        val entries = HashMap(s.digests); for (d in gone) entries.remove(d)
        val batches = HashMap<String, BatchRef>()
        for ((id, b) in s.batches) if (b.digests.any { entries.containsKey(it) }) batches[id] = b
        State(entries, batches, NoProgress.NONE)
    }

    /**
 * an open product question second clause / W-37 — a TRUSTWORTHY live read says the queue holds
 * exactly [liveDigests]: an entry whose record is absent is proven
 * removed by some committed mutation and leaves; a ref with no protected
 * record left is disposed. Never call this from a lossy read.
 */
    @Synchronized
    fun reconcileAbsent(liveDigests: Set<String>): Write = mutate("reconcile") { s ->
        val entries = s.digests.filterKeys { it in liveDigests }
        if (entries.size == s.digests.size) return@mutate s   // nothing absent: no write, Committed
        val batches = s.batches.filterValues { b -> b.digests.any { entries.containsKey(it) } }
        State(entries, batches, s.noProgress)
    }

    /**
 * E-5 — an attempt that POSTED and removed nothing, against the queue
 * image [epoch], for [blocker]. The same epoch AND the same blocker counts
 * up; either changing restarts the count at ONE (a real local state
 * change or a blocker transition rearms; RF4-49). Never reset by a server
 * `duplicate`, never by a restart — the caller resets only through
 * [removed] / [localProgress].
 */
    @Synchronized
    fun recordNoProgress(epoch: String, blocker: String): Write = mutate("no_progress") { s ->
        val np = s.noProgress
        val next = if (np.epoch == epoch && np.blocker == blocker) np.count + 1 else 1
        State(s.digests, s.batches, NoProgress(next, epoch, blocker))
    }

    /**
 * The REARM SEAM for progress that is not a removal — a later slice's
 * compaction commit, a recovery transition that changes the blocker
 * state. Resets the counter; touches no protection.
 */
    @Synchronized
    fun localProgress(reason: String): Write = mutate("local_progress") { s ->
        RecoveryTrace.log("no_progress_rearmed", "why" to reason.take(40))
        State(s.digests, s.batches, NoProgress.NONE)
    }

    /**
 * Is the [MAX_NO_PROGRESS_DRAINS] bound reached for [epoch]? True means
 * the NEXT attempt must refuse BEFORE any request is built.
 */
    fun boundReached(np: NoProgress, epoch: String): Boolean =
        np.count >= F4Constants.MAX_NO_PROGRESS_DRAINS && np.epoch == epoch

    // ── the one read-modify-write ────────────────────────────────────────────

    private inline fun mutate(why: String, f: (State) -> State?): Write {
        val s = when (val r = readFile()) {
            is Read.Ok -> r.state
            is Read.Unreadable -> return Write.NotDurable("unreadable:" + r.reason)
        }
        val next = f(s) ?: return Write.Refused("no_such_ref")
        if (next == s) return Write.Committed
        val w = writeFile(next)
        if (w !is Write.Committed) RecoveryTrace.log("protection_write_not_durable", "why" to why)
        return w
    }

    private fun readFile(): Read {
        if (!file.exists()) return Read.Ok(State.EMPTY)
        return try {
            val parsed = decode(file.readText(Charsets.UTF_8)) ?: return Read.Unreadable("undecodable")
            Read.Ok(parsed)
        } catch (t: Throwable) {
            Read.Unreadable(t.javaClass.simpleName)
        }
    }

    private fun writeFile(state: State): Write {
        val bytes = encode(state).toByteArray(Charsets.UTF_8)
        val outcome = try {
            DurableReplace.replace(file, dirSync) { raf -> raf.write(bytes) }
        } catch (t: Throwable) {
            return Write.NotDurable(t.javaClass.simpleName)
        }
        return when (outcome) {
            DurableReplace.Outcome.COMMITTED -> {
                val back = readFile()
                if (back is Read.Ok && back.state == state) Write.Committed
                else Write.NotDurable("readback_mismatch")
            }
            DurableReplace.Outcome.INDETERMINATE_DIRECTORY_UNSYNCED -> Write.NotDurable("directory_unsynced")
            DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED -> Write.NotDurable("not_committed")
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        private const val VERSION = 1

        /** A batch reference id: derived from the batch's own identity, so a resumed descriptor maps to the same ref. */
        @JvmStatic
        fun refIdOf(chunkId: String, digests: List<String>): String =
            sha256Hex((chunkId + "|" + digests.joinToString("|")).toByteArray(Charsets.UTF_8)).substring(0, 16)

        @JvmStatic
        fun sha256Hex(b: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        @JvmStatic
        fun encode(s: State): String {
            val o = org.json.JSONObject()
            o.put("v", VERSION)
            val ds = org.json.JSONObject()
            for ((d, e) in s.digests.toSortedMap()) {
                ds.put(d, org.json.JSONObject()
                    .put("refs", org.json.JSONArray(e.refs.sorted()))
                    .put("firstAtMs", e.firstAtMs)
                    .put("strongest", e.strongest.name))
            }
            o.put("digests", ds)
            val bs = org.json.JSONObject()
            for ((id, b) in s.batches.toSortedMap()) {
                bs.put(id, org.json.JSONObject()
                    .put("chunkId", b.chunkId)
                    .put("sessionId", b.sessionId)
                    .put("digests", org.json.JSONArray(b.digests))
                    .put("state", b.state.name)
                    .put("attempts", b.attempts)
                    .also { if (b.terminalReason != null) it.put("terminalReason", b.terminalReason) })
            }
            o.put("batches", bs)
            o.put("noProgress", org.json.JSONObject()
                .put("count", s.noProgress.count)
                .put("epoch", s.noProgress.epoch)
                .put("blocker", s.noProgress.blocker))
            return o.toString()
        }

        @JvmStatic
        fun decode(text: String): State? = try {
            val o = org.json.JSONObject(text)
            if (o.optInt("v", -1) != VERSION) null else {
                val ds = o.getJSONObject("digests")
                val digests = LinkedHashMap<String, ProtectedRecord>()
                for (k in ds.keys()) {
                    val e = ds.getJSONObject(k)
                    val refs = e.getJSONArray("refs").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    digests[k] = ProtectedRecord(k, refs, e.getLong("firstAtMs"), Strength.valueOf(e.getString("strongest")))
                }
                val bs = o.getJSONObject("batches")
                val batches = LinkedHashMap<String, BatchRef>()
                for (k in bs.keys()) {
                    val b = bs.getJSONObject(k)
                    val dl = b.getJSONArray("digests").let { a -> (0 until a.length()).map { a.getString(it) } }
                    batches[k] = BatchRef(k, b.getString("chunkId"), b.getString("sessionId"), dl,
                        Strength.valueOf(b.getString("state")), b.getInt("attempts"),
                        b.optString("terminalReason").ifEmpty { null })
                }
                val np = o.getJSONObject("noProgress")
                State(digests, batches, NoProgress(np.getInt("count"), np.getString("epoch"), np.getString("blocker")))
            }
        } catch (_: Throwable) { null }
    }
}

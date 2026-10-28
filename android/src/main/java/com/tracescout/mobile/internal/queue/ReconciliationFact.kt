package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.queue.DurableReplace
import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File

/**
 * F4-P2 (C14 reconciliation half; F4-16; a product decision, R8, R9, R11) — the ONE
 * durable reconciliation fact of a queue home: `<queue file>.reconcile.json`,
 * beside the queue, owned by `QueueHome`, read and written only inside the
 * `RECONCILE` region. One fact per EXACT queue image; the per-session plans live
 * inside the same image-bound artifact.
 *
 * It binds: queue identity · the exact source image (raw sha256 + length) · its
 * integrity classification + reasons · the decoded survivors (logical fingerprint
 * + count, and the count that could not be parsed) · per session: the exact
 * missing ADMITTED / PREPARED sets, the PERSISTED family of every missing
 * sequence, the decision, the PRIOR disable state (reason + owner) observed
 * BEFORE anything was written, the owner this chain proves for an
 * `UNATTRIBUTABLE_GAP` disable, whether the R9 clear is planned, and the durable
 * high-water + owned count the clear must find unchanged · the ownership map
 * CARRIED from the superseded fact · which sessions THIS fact installed a disable
 * for (recorded at SETTLED) · which it CLEARED (recorded after CONSUMED) · a
 * reconciliation id/nonce · durable state PREPARED → SETTLED → CONSUMED.
 *
 * Durability: `DurableReplace.replace` (temp file → fsync → atomic rename →
 * parent-directory fsync) + read-back; an unreadable file is PRESENT, never
 * absent; a pending fact (PREPARED / SETTLED) is never replaced — a different
 * image BLOCKS, the reconciler never adapts a fact to a new image; a CONSUMED
 * fact is the retained tombstone and the ownership carrier, superseded only by a
 * fact that names it, never deleted by the reconciler. Not the bracket marker.
 * Not key-backed: digests, counts, names, ids and sequence numbers only.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class ReconciliationFact internal constructor(
    private val file: File,
    private val dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
) {

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class SessionPlan(
        val sessionId: String,
        /** [DECISION_SETTLE], [DECISION_DISABLE] or [DECISION_CLOSED]. */
        val decision: String,
        val missingAdmitted: List<Long>,
        /** PREPARED entries with no decoded survivor — recorded, NEVER settled from a lossy absence (R9/Q3). */
        val missingPrepared: List<Long>,
        /** The PERSISTED family of every missing sequence (D1-6) — the only family the settlement may name. */
        val families: Map<Long, String>,
        /** The disable reason observed BEFORE this reconciliation wrote anything, or null. */
        val priorDisabled: String?,
        /** The durable owner observed with it, or null (none / legacy). */
        val priorOwner: String?,
        /** The owner id this chain PROVES for the session's `UNATTRIBUTABLE_GAP` disable (authority owner == carried fact owner), or null. */
        val ownedBy: String?,
        /** The R9 clear is planned for this session (the fresh re-scan still decides). */
        val clear: Boolean,
        /** The durable high-water at plan time — condition 9: unchanged at the clear. */
        val highWater: Long,
        /** The number of owned sequences at plan time — condition 10: no sequence re-issued at the clear. */
        val ownedCount: Int,
    )

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    data class Fact(
        val factId: String,
        val queueFile: String,
        val nonce: String,
        val imageSha256: String,
        val imageLength: Long,
        val integrityClass: String,
        val discarded: Int,
        val torn: Boolean,
        val discardReasons: List<String>,
        val survivorFingerprint: String,
        val survivorCount: Int,
        /** Decoded records whose own fields could not be read: with any, the cardinality is UNKNOWN. */
        val unparseableSurvivors: Int,
        val sessions: List<SessionPlan>,
        val state: String,
        val createdAtMs: Long,
        val consumedFingerprint: String? = null,
        val supersedes: String? = null,
        /** sessionId -> owner fact id, CARRIED from the superseded fact: ownership survives supersession. */
        val ownedDisables: Map<String, String> = emptyMap(),
        /** Sessions whose `UNATTRIBUTABLE_GAP` disable THIS fact installed (owner = this factId). Recorded at SETTLED. */
        val installedDisables: List<String> = emptyList(),
        /** Sessions whose owned disable THIS fact cleared. Recorded after CONSUMED. */
        val clearedDisables: List<String> = emptyList(),
    ) {
        /** Only the known-cardinality classes, with every survivor readable, may ever be consumed. */
        val cardinalityKnown: Boolean get() = (integrityClass == "auth_failure" || integrityClass == "torn_tail") && unparseableSurvivors == 0
        /** A consume is owed: a known-cardinality lossy image with no session left disabled. */
        val consumes: Boolean get() = cardinalityKnown && sessions.none { it.decision == DECISION_DISABLE }
        /** The ownership this fact passes to its successor: carried + installed − cleared. */
        fun ownershipAfter(): Map<String, String> {
            val m = LinkedHashMap(ownedDisables)
            for (s in installedDisables) m[s] = factId
            for (s in clearedDisables) m.remove(s)
            return m
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Read {
        data class Ok(val fact: Fact?) : Read()
        data class Unreadable(val reason: String) : Read()
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Write {
        object Committed : Write()
        data class Refused(val reason: String) : Write()
        data class NotDurable(val reason: String) : Write()
    }

    fun path(): String = file.path

    @Synchronized
    fun read(): Read = readFile()

    /**
 * Install a PREPARED fact. Idempotent for the same id. A PENDING fact
 * (PREPARED or SETTLED) is NEVER replaced — the reconciler resumes it or
 * blocks; a CONSUMED fact may be superseded only by a fact that names it.
 */
    @Synchronized
    fun install(f: Fact): Write {
        if (f.state != STATE_PREPARED) return Write.Refused("install_requires_prepared")
        if (f.installedDisables.isNotEmpty() || f.clearedDisables.isNotEmpty()) return Write.Refused("install_carries_post_write_state")
        if (f.factId != factIdOf(f)) return Write.Refused("fact_id_mismatch")
        when (val r = readFile()) {
            is Read.Unreadable -> return Write.Refused("fact_unreadable:" + r.reason)
            is Read.Ok -> {
                val cur = r.fact
                if (cur != null) {
                    if (cur.factId == f.factId) return Write.Committed
                    when (cur.state) {
                        STATE_PREPARED, STATE_SETTLED -> return Write.Refused("fact_pending_present:" + cur.state)
                        STATE_CONSUMED -> if (f.supersedes != cur.factId) return Write.Refused("fact_consumed_not_named")
                        else -> Unit
                    }
                }
            }
        }
        return writeFile(f)
    }

    /**
 * Advance [factId] one step forward: PREPARED -> SETTLED (recording which
 * sessions this fact installed a disable for), SETTLED -> CONSUMED (recording
 * the survivor fingerprint the resulting image was proven to carry).
 */
    @Synchronized
    fun advance(factId: String, state: String, consumedFingerprint: String? = null, installedDisables: List<String> = emptyList()): Write {
        val cur = when (val r = readFile()) {
            is Read.Unreadable -> return Write.Refused("fact_unreadable:" + r.reason)
            is Read.Ok -> r.fact ?: return Write.Refused("fact_absent")
        }
        if (cur.factId != factId) return Write.Refused("fact_of_other_reconciliation")
        if (cur.state == state) return Write.Committed
        val legal = (cur.state == STATE_PREPARED && state == STATE_SETTLED) ||
            (cur.state == STATE_SETTLED && state == STATE_CONSUMED)
        if (!legal) return Write.Refused("fact_state_not_monotonic:${cur.state}->$state")
        if (state == STATE_CONSUMED && consumedFingerprint != cur.survivorFingerprint) return Write.Refused("consumed_fingerprint_mismatch")
        if (state == STATE_SETTLED && installedDisables.any { s -> cur.sessions.none { it.sessionId == s } }) return Write.Refused("installed_session_not_in_fact")
        return writeFile(cur.copy(
            state = state,
            consumedFingerprint = if (state == STATE_CONSUMED) consumedFingerprint else cur.consumedFingerprint,
            installedDisables = if (state == STATE_SETTLED) installedDisables.distinct().sorted() else cur.installedDisables,
        ))
    }

    /** Record the sessions whose owned disable was CLEARED — only on a CONSUMED fact, only for a planned clear. */
    @Synchronized
    fun recordCleared(factId: String, sessions: List<String>): Write {
        val cur = when (val r = readFile()) {
            is Read.Unreadable -> return Write.Refused("fact_unreadable:" + r.reason)
            is Read.Ok -> r.fact ?: return Write.Refused("fact_absent")
        }
        if (cur.factId != factId) return Write.Refused("fact_of_other_reconciliation")
        if (cur.state != STATE_CONSUMED) return Write.Refused("clear_requires_consumed:" + cur.state)
        if (sessions.any { s -> cur.sessions.none { it.sessionId == s && it.clear } }) return Write.Refused("cleared_session_not_planned")
        val merged = (cur.clearedDisables + sessions).distinct().sorted()
        if (merged == cur.clearedDisables) return Write.Committed
        return writeFile(cur.copy(clearedDisables = merged))
    }

    private fun readFile(): Read {
        if (!file.exists()) return Read.Ok(null)
        return try {
            val f = decode(file.readText(Charsets.UTF_8)) ?: return Read.Unreadable("undecodable")
            if (f.factId != factIdOf(f)) return Read.Unreadable("fact_id_mismatch")
            Read.Ok(f)
        } catch (t: Throwable) {
            Read.Unreadable(t.javaClass.simpleName)
        }
    }

    private fun writeFile(f: Fact): Write {
        val bytes = encode(f).toByteArray(Charsets.UTF_8)
        val outcome = try {
            DurableReplace.replace(file, dirSync) { raf -> raf.write(bytes) }
        } catch (t: Throwable) {
            return Write.NotDurable(t.javaClass.simpleName)
        }
        return when (outcome) {
            DurableReplace.Outcome.COMMITTED -> {
                val back = readFile()
                if (back is Read.Ok && back.fact == f) Write.Committed else Write.NotDurable("readback_mismatch")
            }
            DurableReplace.Outcome.INDETERMINATE_DIRECTORY_UNSYNCED -> {
                RecoveryTrace.log("reconcile_fact_unsynced"); Write.NotDurable("directory_unsynced")
            }
            DurableReplace.Outcome.NOT_COMMITTED_OLD_RETAINED -> Write.NotDurable("not_committed")
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        private const val VERSION = 2
        const val STATE_PREPARED: String = "PREPARED"
        const val STATE_SETTLED: String = "SETTLED"
        const val STATE_CONSUMED: String = "CONSUMED"
        const val DECISION_SETTLE: String = "SETTLE"
        const val DECISION_DISABLE: String = "DISABLE"
        const val DECISION_CLOSED: String = "CLOSED"

        /** The identity: every BOUND field (never the post-write installed/cleared lists, which are recorded against it). */
        @JvmStatic
        fun factIdOf(f: Fact): String {
            val sb = StringBuilder("rcf2|").append(f.queueFile).append('|').append(f.nonce).append('|')
                .append(f.imageSha256).append('|').append(f.imageLength).append('|').append(f.integrityClass).append('|')
                .append(f.discarded).append('|').append(f.torn).append('|').append(f.discardReasons.joinToString(","))
                .append('|').append(f.survivorFingerprint).append('|').append(f.survivorCount).append('|').append(f.unparseableSurvivors)
                .append('|').append(f.supersedes ?: "")
            for ((s, o) in f.ownedDisables.toSortedMap()) sb.append('|').append("own:").append(s).append('=').append(o)
            for (s in f.sessions) sb.append('|').append(s.sessionId).append(':').append(s.decision).append(':')
                .append(s.missingAdmitted.joinToString(",")).append(':').append(s.missingPrepared.joinToString(","))
                .append(':').append(s.families.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" })
                .append(':').append(s.priorDisabled ?: "").append(':').append(s.priorOwner ?: "").append(':').append(s.ownedBy ?: "")
                .append(':').append(s.clear).append(':').append(s.highWater).append(':').append(s.ownedCount)
            return DeliveryProtection.sha256Hex(sb.toString().toByteArray(Charsets.UTF_8))
        }

        private fun strOrNull(o: org.json.JSONObject, k: String): String? = if (o.isNull(k)) null else o.getString(k)

        @JvmStatic
        fun encode(f: Fact): String {
            val sessions = org.json.JSONArray()
            for (s in f.sessions) {
                val fam = org.json.JSONObject()
                for ((k, v) in s.families.toSortedMap()) fam.put(k.toString(), v)
                sessions.put(org.json.JSONObject()
                    .put("sessionId", s.sessionId).put("decision", s.decision)
                    .put("missingAdmitted", org.json.JSONArray(s.missingAdmitted.map { it.toString() }))
                    .put("missingPrepared", org.json.JSONArray(s.missingPrepared.map { it.toString() }))
                    .put("families", fam)
                    .put("priorDisabled", s.priorDisabled ?: org.json.JSONObject.NULL)
                    .put("priorOwner", s.priorOwner ?: org.json.JSONObject.NULL)
                    .put("ownedBy", s.ownedBy ?: org.json.JSONObject.NULL)
                    .put("clear", s.clear).put("highWater", s.highWater).put("ownedCount", s.ownedCount))
            }
            val owned = org.json.JSONObject()
            for ((k, v) in f.ownedDisables.toSortedMap()) owned.put(k, v)
            return org.json.JSONObject()
                .put("v", VERSION).put("factId", f.factId).put("queueFile", f.queueFile).put("nonce", f.nonce)
                .put("imageSha256", f.imageSha256).put("imageLength", f.imageLength)
                .put("integrityClass", f.integrityClass).put("discarded", f.discarded).put("torn", f.torn)
                .put("discardReasons", org.json.JSONArray(f.discardReasons))
                .put("survivorFingerprint", f.survivorFingerprint).put("survivorCount", f.survivorCount)
                .put("unparseableSurvivors", f.unparseableSurvivors)
                .put("sessions", sessions).put("state", f.state).put("createdAtMs", f.createdAtMs)
                .put("consumedFingerprint", f.consumedFingerprint ?: org.json.JSONObject.NULL)
                .put("supersedes", f.supersedes ?: org.json.JSONObject.NULL)
                .put("ownedDisables", owned)
                .put("installedDisables", org.json.JSONArray(f.installedDisables))
                .put("clearedDisables", org.json.JSONArray(f.clearedDisables))
                .toString()
        }

        @JvmStatic
        fun decode(text: String): Fact? = try {
            val o = org.json.JSONObject(text)
            if (o.optInt("v", -1) != VERSION) null else {
                fun strings(a: org.json.JSONArray) = (0 until a.length()).map { a.getString(it) }
                val sessions = o.getJSONArray("sessions").let { a ->
                    (0 until a.length()).map { i ->
                        val s = a.getJSONObject(i)
                        val famO = s.getJSONObject("families")
                        val fam = LinkedHashMap<Long, String>()
                        for (k in famO.keys()) fam[k.toLong()] = famO.getString(k)
                        SessionPlan(
                            s.getString("sessionId"), s.getString("decision"),
                            strings(s.getJSONArray("missingAdmitted")).map { it.toLong() },
                            strings(s.getJSONArray("missingPrepared")).map { it.toLong() },
                            fam, strOrNull(s, "priorDisabled"), strOrNull(s, "priorOwner"), strOrNull(s, "ownedBy"),
                            s.getBoolean("clear"), s.getLong("highWater"), s.getInt("ownedCount"),
                        )
                    }
                }
                val ownedO = o.getJSONObject("ownedDisables")
                val owned = LinkedHashMap<String, String>()
                for (k in ownedO.keys()) owned[k] = ownedO.getString(k)
                val state = o.getString("state")
                if (state != STATE_PREPARED && state != STATE_SETTLED && state != STATE_CONSUMED) null else Fact(
                    o.getString("factId"), o.getString("queueFile"), o.getString("nonce"),
                    o.getString("imageSha256"), o.getLong("imageLength"), o.getString("integrityClass"),
                    o.getInt("discarded"), o.getBoolean("torn"), strings(o.getJSONArray("discardReasons")),
                    o.getString("survivorFingerprint"), o.getInt("survivorCount"), o.getInt("unparseableSurvivors"), sessions, state,
                    o.getLong("createdAtMs"), strOrNull(o, "consumedFingerprint"), strOrNull(o, "supersedes"),
                    owned, strings(o.getJSONArray("installedDisables")), strings(o.getJSONArray("clearedDisables")),
                )
            }
        } catch (_: Throwable) { null }
    }
}

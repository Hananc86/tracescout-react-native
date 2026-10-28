package com.tracescout.mobile.internal.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.internal.loss.LossAuthority
import com.tracescout.mobile.queue.EncryptedEventStore
import com.tracescout.mobile.queue.QueueHome
import com.tracescout.mobile.queue.QueueRegion
import com.tracescout.mobile.queue.RecoveryTrace
import java.io.File

/**
 * F4-P2 — THE QUEUE RECONCILER (F4-16 / F4-17; GAP_MATRIX F.1–F.4; the capture specification, the specification;
 * frozen rows C11, C12, C13 and the reconciliation half of C14; a product decision
 * R1–R11 of 2026-10-01).
 *
 * The ONE home that resolves what a LOSSY active-queue read leaves behind, the
 * ONE caller of the facade's `UNREADABLE` writer, the ONE caller of the facade's
 * owner-bound `UNATTRIBUTABLE_GAP` writer and of its ONE clear, and the ONE
 * caller of the store's governed `consumeReconciled`. It runs in the ONE
 * enumerated `RECONCILE` region and only when no eviction bracket is unresolved,
 * no erasure is requested and no in-flight batch is ambiguous.
 *
 * ## The decision, PER SESSION, from the persisted index only (R1, R2) — through
 * ## [ExactClosure], the one named predicate
 *
 * missing(session) = ADMITTED-undelivered(session) − decodedSurvivors(session)
 * family(s) = index[s] — persisted at admission, never inferred
 *
 * | closure verdict | image | decision |
 * |---|---|---|
 * | closes exactly | any | CLOSED — and if the session carries the owner-bound `UNATTRIBUTABLE_GAP` disable this fact chain PROVES it owns, the R9 CLEAR is planned |
 * | only `prepared_entry` open | any | CLOSED, nothing written: a PREPARED entry is unresolved under ANY read and is NEVER settled from a lossy absence (R9/Q3 three-way state (b)) |
 * | `missing` (+ at most `prepared_entry`) | trustworthy | DISABLE — U4: the evidence is gone; NO cause (three-way state (c)) |
 * | `missing` (+ at most `prepared_entry`) | `auth_failure` / `torn_tail`, every survivor readable | SETTLE: each missing ADMITTED ⇒ `LOST(UNREADABLE)` under its persisted family (three-way state (a)); PREPARED stays PREPARED; then the corrupt frames are CONSUMED |
 * | `missing` with any other disqualifier, or an unknown-cardinality class | any | DISABLE `UNATTRIBUTABLE_GAP` (owner-bound, R11); nothing settled, nothing consumed |
 * | a FOREIGN disable / UNKNOWN confidence / any other disqualifier, with ZERO known-missing | lossy | DISABLE recorded (the foreign disable RETAINED, installed=false) — F1: it BLOCKS the shared consume, and every SETTLE on that image becomes DISABLE: nothing settled, nothing consumed |
 *
 * ## The R4 order, enforced here and pinned (`SliceP2SourceTest`)
 *
 * 1. gates: marker / erasure / in-flight ambiguity — any ⇒ Blocked, nothing written
 * 2. the EXACT image (raw digest + length + class + survivors)
 * 3. the durable FACT — written and READ BACK before any authority write; it
 * records the prior disable state of every session and CARRIES the ownership
 * map of the superseded fact; a relaunch resumes the fact it finds for the
 * SAME image, and BLOCKS typed on any other image (never adapts, never rewrites)
 * 4. settle exactly the bound set (idempotent); install the owner-bound disable
 * where decided, owner = this fact's id
 * 5. SETTLED (durable), recording which sessions THIS fact installed
 * 6. re-verify every gate
 * 7. the governed consume against the EXACT bound image; fsync + read-back
 * 8. a FRESH re-scan (the predicate again, per session) must close exactly
 * 9. CONSUMED (durable) — only now is the refusal released
 * 10. the R9 CLEAR, per planned session, only if ALL TEN hold on the FRESH scan:
 * durable ownership (authority owner == the owner this chain names) · every
 * settlement durable · the consume durable + read back · the fact CONSUMED ·
 * the fresh re-scan · exact closure · zero marker / intent / erasure /
 * in-flight / PREPARED / pending-reconciliation ambiguity · no other
 * owner/reason · high-water unchanged · no sequence re-issued — then the
 * facade's ONE clear, then the fact records it. Any one false ⇒ preserved.
 *
 * A death after 3 ⇒ 4 is retried; after 5 ⇒ 7 is retried against the EXACT
 * bound image; after 7 ⇒ the exact resulting image is proven and the fact
 * finished idempotently; after 9 ⇒ the clear is re-planned by the NEXT fact,
 * which inherits the ownership (idempotent: an already-clear session is CLOSED).
 *
 * ## What it never does
 *
 * A fallback family · a victim for an unknown-cardinality class · a sequence
 * removed from arithmetic · a decodable record dropped · a `NOT_STORED_AT_RECOVERY`
 * or an `UNREADABLE` for a PREPARED entry · a legacy or foreign disable cleared ·
 * a clear with ownership guessed from the reason name · a settlement from an
 * authority that could not be read (O-1) · a consume under a disabled session.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class QueueReconciler(
    private val home: QueueHome,
    private val filesDir: File,
    /** The pipeline's durable in-flight descriptor store — a frozen batch is in-flight ambiguity. */
    private val inflight: InFlightStore? = null,
    /**
 * The PERSISTED INDEX of every session, read-only and complete-or-not-at-all.
 * The facade owns the authority store and this enumeration (B9); the
 * reconciler consumes facade types only (B10). Production: the authority-v2
 * key through `scanSessionIndexesAtOpen`.
 */
    private val scan: () -> LossAuthority.IndexScan = { LossAuthority.scanSessionIndexesAtOpen(filesDir) },
    /** The NON-CREATING door. A session with no authority on disk cannot own a sequence. */
    private val open: (String) -> LossAuthority.Open = { sid -> LossAuthority.openExisting(filesDir, sid) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val nonce: () -> String = { java.util.UUID.randomUUID().toString() },
    /** FAULT SEAM — inert by default; a test throws [DurableUploadPipeline.SimulatedProcessDeath] at ONE named point. */
    private val faults: (String) -> Unit = { },
) {

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Outcome {
        /** The read was trustworthy and nothing was owed: nothing written. */
        object NotNeeded : Outcome()

        /** Every decision durable. [consumed] = the corrupt frames were dropped (known cardinality only). [cleared] = R9 clears that landed. */
        data class Resolved(val settledUnreadable: Int, val disabled: Int, val cleared: Int, val consumed: Boolean) : Outcome()

        /** Nothing best-effort: every fence stays, the image is untouched. [settled] counts writes that ARE durable this run. */
        data class Blocked(val reason: String, val settled: Int) : Outcome()
    }

    private val store: EncryptedEventStore = home.store()
    private val fact = home.reconciliationFact()

    /** The driver. One region; a lock refusal is a typed Blocked, never a proceed-unlocked path. */
    fun reconcile(): Outcome = when (val o = home.tryWithQueue(QueueRegion.RECONCILE) { reconcileInRegion() }) {
        is QueueHome.Outcome.Ran -> o.value
        is QueueHome.Outcome.Refused -> {
            RecoveryTrace.log("queue_reconcile_region_refused", "why" to o.refusal.wire, "waitedMs" to o.refusal.waitedMs)
            Outcome.Blocked("reconcile_queue_" + o.refusal.wire, 0)
        }
    }

    private fun gate(): String? {
        EvictionRecovery.blocks(home)?.let { return it }
        val d = inflight?.load() ?: return null
        // A frozen batch whose bytes are all still decodable is intact; one naming a
        // record the image no longer yields is a request whose outcome is unknown.
        val present = store.readAll().records.map { DeliveryProtection.sha256Hex(it) }.toSet()
        return if (d.digests.all { it in present }) null else "inflight_ambiguous"
    }

    private fun reconcileInRegion(): Outcome {
        gate()?.let { return blocked("gated:$it", 0) }

        // ── 2. the EXACT image ──
        val img = store.readImage()
        faults("after_read")

        // ── a fact already on disk decides first (idempotent resume; a pending one is never replaced) ──
        val existing = when (val r = fact.read()) {
            is ReconciliationFact.Read.Unreadable -> return blocked("fact_unreadable:" + r.reason.take(32), 0)
            is ReconciliationFact.Read.Ok -> r.fact
        }
        if (existing != null && existing.state != ReconciliationFact.STATE_CONSUMED) return resume(existing, img)
        // ── a CONSUMED fact whose planned clear LANDED in the authority before the process died, but whose
        // clear history was never written: finish the fact idempotently from what is durably observable —
        // the fact names the owner, the authority is now ENABLED, and this door is the ONLY writer of an
        // owner-bound clear. Nothing is re-enabled, nothing re-planned; a session still disabled falls
        // through to the ordinary plan, which re-plans its clear under a NEW fact.
        if (existing != null) finishRecordedClears(existing)

        // ── the per-session arithmetic through the ONE predicate ──
        val planned = plan(img, existing) ?: return blocked(lastBlock, 0)
        val id = img.identity
        val consumes = planned.cardinalityKnown && planned.sessions.none { it.decision == ReconciliationFact.DECISION_DISABLE }
        val needsWrite = planned.sessions.any { it.decision != ReconciliationFact.DECISION_CLOSED || it.clear }
        if (!needsWrite && !consumes) {
            if (!img.read.isTrustworthy) RecoveryTrace.log("queue_reconciled", "class" to id.integrityClass, "disabled" to 0, "sessions" to 0, "consumed" to false)
            return if (img.read.isTrustworthy) Outcome.NotNeeded else Outcome.Resolved(0, 0, 0, consumed = false)
        }
        // ── 3. the durable FACT, read back before any authority write ──
        val f = ReconciliationFact.Fact(
            factId = "", queueFile = home.queueFile.name, nonce = nonce(),
            imageSha256 = id.imageSha256, imageLength = id.imageLength, integrityClass = id.integrityClass,
            discarded = id.discarded, torn = id.torn, discardReasons = id.discardReasons,
            survivorFingerprint = id.survivorFingerprint, survivorCount = id.survivorCount,
            unparseableSurvivors = planned.unparseable,
            sessions = planned.sessions, state = ReconciliationFact.STATE_PREPARED, createdAtMs = nowMs(),
            supersedes = existing?.factId, ownedDisables = planned.carriedOwnership,
        ).let { it.copy(factId = ReconciliationFact.factIdOf(it)) }
        when (val w = fact.install(f)) {
            is ReconciliationFact.Write.Committed -> Unit
            is ReconciliationFact.Write.Refused -> return blocked("fact_refused:" + w.reason.take(32), 0)
            is ReconciliationFact.Write.NotDurable -> return blocked("fact_not_durable:" + w.reason.take(32), 0)
        }
        faults("after_fact_prepared")
        return execute(f, img)
    }

    /** A fact is on disk and not finished: resume it against the live image, or refuse typed. NEVER adapted to another image. */
    private fun resume(f: ReconciliationFact.Fact, img: EncryptedEventStore.ImageReading): Outcome {
        val id = img.identity
        val sameImage = id.imageSha256 == f.imageSha256 && id.imageLength == f.imageLength
        if (sameImage) return execute(f, img)
        // SETTLED with a consume owed, and the live image is EXACTLY the bound survivors: the
        // consume landed and the process died before the fact recorded it — finish, consume nothing.
        if (f.state == ReconciliationFact.STATE_SETTLED && f.consumes && img.read.isTrustworthy &&
            id.survivorFingerprint == f.survivorFingerprint && id.survivorCount == f.survivorCount)
            return finish(f, img, consumedNow = false)
        RecoveryTrace.log("queue_reconcile_fact_stale", "state" to f.state)
        return blocked("fact_image_mismatch", 0)
    }

    /**
 * R9 crash window "after the clear append, before the fact update": the clear is durable in the authority
 * (the ONE owner-bound writer, reached only from [clearStep]) and the CONSUMED fact still plans it without
 * recording it. Finish the history from durable facts only: planned clear + named owner + the authority's
 * DURABLE state re-read ENABLED with no owner, at the planned high-water and owned count (B1/B3: the same
 * [clearObservedDurably] the clear itself must pass) ⇒ the exact clear already applied. Idempotent
 * ([ReconciliationFact.recordCleared] merges and dedupes); writes nothing into the authority; never
 * touches a session that is still disabled, and appends no second clear.
 */
    private fun finishRecordedClears(f: ReconciliationFact.Fact) {
        val owed = f.sessions.filter { it.clear && it.ownedBy != null && it.sessionId !in f.clearedDisables }
        if (owed.isEmpty()) return
        val done = ArrayList<String>()
        for (p in owed) if (clearObservedDurably(p)) done.add(p.sessionId)
        if (done.isEmpty()) return
        when (fact.recordCleared(f.factId, done)) {
            is ReconciliationFact.Write.Committed -> RecoveryTrace.log("queue_reconcile_clear_finished", "sessions" to done.size)
            else -> RecoveryTrace.log("queue_reconcile_clear_unrecorded", "sessions" to done.size)
        }
    }

    private var lastBlock: String = ""

    private class Plan(val sessions: List<ReconciliationFact.SessionPlan>, val cardinalityKnown: Boolean, val unparseable: Int,
                       val carriedOwnership: Map<String, String>)

    /** One session's fresh evaluation: its index, its decoded survivors, the predicate's verdict. */
    private class Fresh(val index: LossAuthority.SessionIndex?, val survivors: List<Long>, val reasons: List<String>)

    /**
 * The per-session plan over [img] through [ExactClosure], or null (with
 * [lastBlock] set) when the scan is INCOMPLETE — O-1: a session whose index
 * cannot be read may own a missing sequence, so nothing is decided for ANY
 * session and nothing is consumed — or a survivor's session has no authority.
 */
    private fun plan(img: EncryptedEventStore.ImageReading, prior: ReconciliationFact.Fact?): Plan? {
        val read = img.read
        val cls = read.integrityClass
        val fresh = scanFresh(img, prior?.ownershipAfter() ?: emptyMap()) ?: return null
        val cardinalityKnown = (cls == "auth_failure" || cls == "torn_tail") && fresh.unparseable == 0
        faults("after_inspect")
        val out = ArrayList<ReconciliationFact.SessionPlan>()
        for ((sid, fr) in fresh.bySession) {
            val ix = fr.index ?: continue
            val mine = fr.survivors.toSet()
            val missingAdmitted = ix.admittedUndelivered.filter { it !in mine }
            val missingPrepared = ix.prepared.filter { it !in mine }
            val core = fr.reasons.filter { !it.startsWith("missing:") && it != "prepared_entry" }
            val owner = fresh.ownership[sid]?.takeIf { ix.disabled == ExactClosure.UNATTRIBUTABLE_GAP && it == ix.disabledOwner }
            val decision = when {
                fr.reasons.isEmpty() -> ReconciliationFact.DECISION_CLOSED
                // a PREPARED entry alone: unresolved under ANY read, NEVER settled here — nothing to write
                core.isEmpty() && missingAdmitted.isEmpty() -> ReconciliationFact.DECISION_CLOSED
                core.isNotEmpty() -> ReconciliationFact.DECISION_DISABLE   // unresolved intent / unknown sequence / undecodable family / foreign disable: no exact arithmetic
                read.isTrustworthy -> ReconciliationFact.DECISION_DISABLE    // U4: the evidence is gone, no cause (three-way (c))
                cardinalityKnown -> ReconciliationFact.DECISION_SETTLE
                else -> ReconciliationFact.DECISION_DISABLE
            }
            // The R9 clear is PLANNED for a proven-owned session that closes now (CLOSED, no disqualifier) or will
            // close once its exact missing set is settled and consumed (SETTLE with ONLY `missing` open); the fresh
            // post-consume re-scan is what actually decides, in clearStep.
            val clear = owner != null && ((decision == ReconciliationFact.DECISION_CLOSED && fr.reasons.isEmpty()) ||
                (decision == ReconciliationFact.DECISION_SETTLE && core.isEmpty() && missingPrepared.isEmpty()))
            // ── F1: a session the predicate cannot close BLOCKS the shared consume ──
            // A foreign (legacy) disable, or an UNKNOWN high-water confidence, means the session may own
            // sequences BEYOND its index — so a corrupt frame in the SHARED lossy image may be ITS evidence
            // even when its currently-known missing ADMITTED set is EMPTY: zero known-missing does not prove
            // the lost frame belongs to another session. Such a session is recorded as DISABLE — its prior,
            // foreign disable is RETAINED, never overwritten (the execute arm skips the write; the fact records
            // the prior state and installed=false) — and that DISABLE blocks the consume for the WHOLE image.
            // On a trustworthy image there is nothing to consume and nothing to write.
            if (decision == ReconciliationFact.DECISION_DISABLE && missingAdmitted.isEmpty() && ix.disabled != null) {
                if (read.isTrustworthy) continue
                out.add(ReconciliationFact.SessionPlan(sid, ReconciliationFact.DECISION_DISABLE, missingAdmitted, missingPrepared, emptyMap(),
                    ix.disabled, ix.disabledOwner, owner, false, ix.highWater, ix.families.size)); continue
            }
            if (decision == ReconciliationFact.DECISION_CLOSED && !clear && read.isTrustworthy) continue
            val families = LinkedHashMap<Long, String>()
            for (q in missingAdmitted + missingPrepared) ix.families[q]?.let { families[q] = it }
            out.add(ReconciliationFact.SessionPlan(sid, decision, missingAdmitted, missingPrepared, families,
                ix.disabled, ix.disabledOwner, owner, clear, ix.highWater, ix.families.size))
        }
        // ── THE ARITHMETIC MUST CLOSE EXACTLY ACROSS THE IMAGE, NOT ONLY PER SESSION ──
        // A known-cardinality image lost exactly `discarded` frames (+ one torn frame). If the
        // sessions planned for settlement are missing MORE ADMITTED records than that, at least
        // one absence is NOT a corrupt frame — it is a record that never reached the queue —
        // and nothing can say which. Then no session is settled (a guessed `UNREADABLE` is a
        // fiction, R2); every such session is DISABLED instead, and nothing is consumed.
        val lostFrames = img.identity.discarded + (if (img.identity.torn) 1 else 0)
        val planned = out.filter { it.decision == ReconciliationFact.DECISION_SETTLE }.sumOf { it.missingAdmitted.size }
        // F1: the image-wide inequality is NEVER sufficient on its own — a consume is authorized only when
        // EVERY session that could intersect the image closes exactly. One session the predicate cannot
        // close (a foreign disable, UNKNOWN confidence, an unresolved intent, an undecodable family, …)
        // blocks the consume, and a settlement whose frames cannot be consumed is a settlement of evidence
        // that stays in the image — so with the consume blocked, nothing is settled either.
        val blocked = out.count { it.decision == ReconciliationFact.DECISION_DISABLE }
        if (cardinalityKnown && (planned > lostFrames || blocked > 0)) {
            if (planned > lostFrames) RecoveryTrace.log("queue_reconcile_arithmetic_open", "missing" to planned, "frames" to lostFrames)
            else if (planned > 0) RecoveryTrace.log("queue_reconcile_consume_blocked", "missing" to planned, "blocking" to blocked)
            for (i in out.indices) if (out[i].decision == ReconciliationFact.DECISION_SETTLE)
                out[i] = out[i].copy(decision = ReconciliationFact.DECISION_DISABLE, clear = false)
        }
        return Plan(out, cardinalityKnown, fresh.unparseable, fresh.ownership)
    }

    private class FreshScan(val bySession: Map<String, Fresh>, val unparseable: Int, val ownership: Map<String, String>)

    /** The FRESH per-session evaluation: a complete scan, the decoded survivors, the predicate — or null (Blocked) when the scan is incomplete. */
    private fun scanFresh(img: EncryptedEventStore.ImageReading, ownership: Map<String, String>): FreshScan? {
        val survivors = HashMap<String, ArrayList<Long>>()
        var unparseable = 0
        for (r in img.read.records) {
            val row = LossAuthority.RecordFields.parse(r)
            if (row == null) { unparseable++; continue }
            survivors.getOrPut(row.sessionId) { ArrayList() }.add(row.sequence)
        }
        val indexes = when (val sc = scan()) {
            is LossAuthority.IndexScan.Incomplete -> { lastBlock = "scan_incomplete:" + sc.reason.take(40); return null }
            is LossAuthority.IndexScan.Complete -> sc.sessions
        }
        // A survivor whose session has no index cannot be reconciled: nothing owns its
        // sequences, so nothing can state what else is missing.
        for (sid in survivors.keys) if (indexes.none { it.sessionId == sid }) { lastBlock = "survivor_without_authority:" + sid.take(16); return null }
        val out = LinkedHashMap<String, Fresh>()
        for (ix in indexes) {
            val mine = survivors[ix.sessionId] ?: emptyList<Long>()
            val owner = ownership[ix.sessionId]
            val reasons = ExactClosure.evaluate(ExactClosure.Input(null, null, false, ix, mine, owner))
            out[ix.sessionId] = Fresh(ix, mine, reasons)
        }
        return FreshScan(out, unparseable, ownership)
    }

    private fun handle(sid: String): LossAuthority.Handle? = when (val o = open(sid)) {
        is LossAuthority.Open.Ok -> if (o.handle.isFenced()) { lastBlock = "authority_fenced:" + sid.take(16); null } else o.handle
        is LossAuthority.Open.Unavailable -> { lastBlock = "authority_unavailable:" + o.reason.take(32); null }
    }

    /** Steps 4–10 over a durable fact whose image is the live one. */
    private fun execute(f: ReconciliationFact.Fact, img: EncryptedEventStore.ImageReading): Outcome {
        var settledUnreadable = 0; var disabled = 0
        val installed = ArrayList<String>()
        fun settled(): Int = settledUnreadable + disabled
        if (f.state == ReconciliationFact.STATE_PREPARED) {
            // ── 4. settle exactly what the fact binds; install the owner-bound disable where decided ──
            for (p in f.sessions) {
                when (p.decision) {
                    ReconciliationFact.DECISION_CLOSED -> continue
                    ReconciliationFact.DECISION_DISABLE -> {
                        if (p.priorDisabled != null) continue   // retained, never overwritten (R3); not ours
                        val h = handle(p.sessionId) ?: return blocked(lastBlock, settled())
                        when (val w = h.disableUnattributableGap(f.factId)) {
                            is LossAuthority.Write.Written -> { disabled++; installed.add(p.sessionId) }
                            is LossAuthority.Write.Refused ->
                                if (w.reason.startsWith("already_disabled:")) RecoveryTrace.log("queue_reconcile_disable_present", "why" to w.reason.take(40))
                                else return blocked("disable_refused:" + w.reason.take(32), settled())
                            is LossAuthority.Write.NotDurable -> return blocked("disable_not_durable:" + w.reason.take(32), settled())
                            is LossAuthority.Write.PolicyDeclined -> return blocked("disable_declined:" + w.reason.take(32), settled())
                        }
                        faults("after_disable:${p.sessionId.take(8)}")
                    }
                    ReconciliationFact.DECISION_SETTLE -> {
                        val h = handle(p.sessionId) ?: return blocked(lastBlock, settled())
                        // ONLY the missing ADMITTED set — a PREPARED entry stays PREPARED (R9/Q3).
                        for (q in p.missingAdmitted) {
                            if (p.families[q] == null) return blocked("family_not_indexed", settled())
                            when (val w = h.unreadableLost(q)) {
                                is LossAuthority.Write.Written -> settledUnreadable++
                                is LossAuthority.Write.Refused -> return blocked("unreadable_refused:" + w.reason.take(32), settled())
                                is LossAuthority.Write.NotDurable -> return blocked("unreadable_not_durable:" + w.reason.take(32), settled())
                                is LossAuthority.Write.PolicyDeclined -> return blocked("unreadable_declined:" + w.reason.take(32), settled())
                            }
                            faults("after_settle:${p.sessionId.take(8)}:$q")
                        }
                    }
                    else -> return blocked("fact_decision_unknown", settled())
                }
            }
            // ── 5. SETTLED, durable, recording which sessions THIS fact installed ──
            when (val w = fact.advance(f.factId, ReconciliationFact.STATE_SETTLED, installedDisables = installed)) {
                is ReconciliationFact.Write.Committed -> Unit
                is ReconciliationFact.Write.Refused -> return blocked("fact_refused:" + w.reason.take(32), settled())
                is ReconciliationFact.Write.NotDurable -> return blocked("fact_not_durable:" + w.reason.take(32), settled())
            }
            faults("after_fact_settled")
        }
        if (!f.consumes) {
            // Nothing to consume (a trustworthy image, or an unknown-cardinality class whose
            // sessions were disabled): the resulting image IS the bound image. Prove it and finish.
            return finish(f, store.readImage(), consumedNow = false, counts = settledUnreadable to disabled)
        }
        // ── 6. re-verify every gate before the destructive step ──
        gate()?.let { return blocked("gated_before_consume:$it", settled()) }
        faults("before_consume")
        // ── 7. the governed consume against the EXACT bound image ──
        val expected = EncryptedEventStore.ImageIdentity(f.imageSha256, f.imageLength, f.integrityClass, f.discarded, f.torn,
            f.discardReasons, f.survivorFingerprint, f.survivorCount)
        val c = store.consumeReconciled(expected)
        if (c.outcome != EncryptedEventStore.ConsumeOutcome.COMMITTED) return blocked("consume_" + c.outcome.name.lowercase(), settled())
        faults("after_consume")
        return finish(f, store.readImage(), consumedNow = true, counts = settledUnreadable to disabled)
    }

    /** Steps 8–10: the fresh re-scan must close exactly; CONSUMED; then the R9 clear per planned session. */
    private fun finish(f: ReconciliationFact.Fact, after: EncryptedEventStore.ImageReading, consumedNow: Boolean,
                       counts: Pair<Int, Int> = 0 to 0): Outcome {
        val settled = counts.first + counts.second
        val id = after.identity
        if (f.consumes) {
            if (!after.read.isTrustworthy || id.survivorFingerprint != f.survivorFingerprint || id.survivorCount != f.survivorCount)
                return blocked("rescan_image_not_survivors", settled)
        } else if (id.imageSha256 != f.imageSha256 || id.imageLength != f.imageLength) {
            return blocked("fact_image_mismatch", settled)
        }
        // ── 8. the FRESH re-scan, through the ONE predicate ──
        val fresh = scanFresh(after, f.ownershipAfterInstall()) ?: return blocked(lastBlock, settled)
        for (p in f.sessions) {
            if (p.decision != ReconciliationFact.DECISION_SETTLE) continue
            val fr = fresh.bySession[p.sessionId] ?: return blocked("rescan_session_gone:" + p.sessionId.take(16), settled)
            if (fr.reasons.any { it.startsWith("missing:") }) return blocked("rescan_not_closed:" + p.sessionId.take(16), settled)
        }
        // ── 9. CONSUMED (durable) ──
        when (val w = fact.advance(f.factId, ReconciliationFact.STATE_CONSUMED, f.survivorFingerprint)) {
            is ReconciliationFact.Write.Committed -> Unit
            is ReconciliationFact.Write.Refused -> return blocked("fact_refused:" + w.reason.take(32), settled)
            is ReconciliationFact.Write.NotDurable -> return blocked("fact_not_durable:" + w.reason.take(32), settled)
        }
        faults("after_fact_consumed")
        // ── 10. the R9 clear — only now, only per planned session, only if ALL TEN hold on the FRESH scan ──
        val cleared = clearStep(f, fresh)
        RecoveryTrace.log("queue_reconciled", "class" to f.integrityClass, "unreadable" to counts.first, "disabled" to counts.second,
            "cleared" to cleared, "sessions" to f.sessions.size, "consumed" to f.consumes, "dropped" to f.discarded, "resumed" to (f.consumes && !consumedNow))
        return Outcome.Resolved(counts.first, counts.second, cleared, consumed = f.consumes)
    }

    /** The ownership a fact vouches for AFTER its own installs: carried + installed by this fact. */
    private fun ReconciliationFact.Fact.ownershipAfterInstall(): Map<String, String> {
        val m = LinkedHashMap(ownedDisables)
        for (s in installedDisables) m[s] = factId
        return m
    }

    /**
 * R9 — the bounded clear. Reached ONLY after the fact is CONSUMED (step 9),
 * which itself follows durable settlements (4/5) and the durable, read-back
 * consume (7) — conditions 2, 3, 4. Here, on the FRESH scan (5): the
 * predicate must hold with ZERO disqualifier (6, 7 — closure, marker, intent,
 * erasure, in-flight, PREPARED, pending reconciliation are all its inputs or
 * gates), the authority's durable owner must be the owner this fact chain
 * names (1 — ownership is read from TWO durable sources, never memory, never
 * the reason name), the disable must still be `UNATTRIBUTABLE_GAP` under that
 * owner (8 — a legacy or foreign reason replaced it, or another owner holds it),
 * the high-water must be exactly the planned one (9) and the owned count the
 * planned one (10). Then the facade's ONE clear; the fact records what landed.
 * Any one false ⇒ the disable is PRESERVED and the reason traced.
 */
    private fun clearStep(f: ReconciliationFact.Fact, fresh: FreshScan): Int {
        val done = ArrayList<String>()
        for (p in f.sessions) {
            if (!p.clear || p.ownedBy == null) continue
            val fr = fresh.bySession[p.sessionId]
            val ix = fr?.index
            val why = when {
                fr == null || ix == null -> "no_authority"
                gate() != null -> "gated"
                fr.reasons.isNotEmpty() -> fr.reasons.first()
                ix.disabled != ExactClosure.UNATTRIBUTABLE_GAP -> "disable_not_p2"
                ix.disabledOwner != p.ownedBy -> "owner_mismatch"
                ix.highWater != p.highWater -> "high_water_changed"
                ix.families.size != p.ownedCount -> "sequence_reissued"
                else -> null
            }
            if (why != null) { RecoveryTrace.log("queue_reconcile_clear_withheld", "why" to why.take(40)); continue }
            val h = handle(p.sessionId) ?: run { RecoveryTrace.log("queue_reconcile_clear_withheld", "why" to lastBlock.take(40)); null } ?: continue
            when (val w = h.clearUnattributableGap(p.ownedBy)) {
                // the durable append landed; re-read the authority's CURRENT state and prove it is enabled
                // before the fact records a clear — a clear the authority does not show is not a clear
                is LossAuthority.Write.Written -> if (clearObservedDurably(p)) done.add(p.sessionId)
                    else RecoveryTrace.log("queue_reconcile_clear_withheld", "why" to "clear_not_observed")
                is LossAuthority.Write.Refused -> RecoveryTrace.log("queue_reconcile_clear_withheld", "why" to w.reason.take(40))
                is LossAuthority.Write.NotDurable -> RecoveryTrace.log("queue_reconcile_clear_withheld", "why" to w.reason.take(40))
                is LossAuthority.Write.PolicyDeclined -> RecoveryTrace.log("queue_reconcile_clear_withheld", "why" to w.reason.take(40))
            }
            faults("after_clear:${p.sessionId.take(8)}")
        }
        if (done.isNotEmpty()) {
            when (val w = fact.recordCleared(f.factId, done)) {
                is ReconciliationFact.Write.Committed -> Unit
                else -> RecoveryTrace.log("queue_reconcile_clear_unrecorded", "sessions" to done.size)
            }
        }
        return done.size
    }

    /**
 * B3 — the writer's return value is NOT the proof. After the owned clear's append returns, the
 * authority's CURRENT state is re-read from its DURABLE files — the SAME scan the plan and the fresh
 * re-scan use, never the handle's memory — and the clear is recorded only if that read shows the
 * session ENABLED, with NO owner remaining, at the planned high-water and the planned owned count.
 * An Incomplete scan proves nothing and withholds the record (the relaunch finishes it from the
 * same durable facts through [finishRecordedClears]).
 */
    private fun clearObservedDurably(p: ReconciliationFact.SessionPlan): Boolean {
        val ix = when (val sc = scan()) {
            is LossAuthority.IndexScan.Incomplete -> return false
            is LossAuthority.IndexScan.Complete -> sc.sessions.firstOrNull { it.sessionId == p.sessionId }
        } ?: return false
        return ix.disabled == null && ix.disabledOwner == null && ix.highWater == p.highWater && ix.families.size == p.ownedCount
    }

    private fun blocked(reason: String, settled: Int): Outcome.Blocked {
        RecoveryTrace.log("queue_reconcile_blocked", "why" to reason.take(48), "settled" to settled)
        return Outcome.Blocked(reason, settled)
    }

    /**
 * ## THE RECONCILIATION MUTATION GATE (F4-P2 production child, a product decision)
 *
 * Once a durable reconciliation fact has BOUND a lossy source image, ordinary admission and
 * append must not be able to mutate that image until the governed reconciliation has released
 * the block — otherwise a record appended between the fact and the consume moves the bound
 * image, and the consume (correctly) refuses `fact_image_mismatch` forever. This companion is
 * the ONE predicate both gates ask; there is deliberately no second, producer-local reading of
 * the fact:
 *
 * * **Gate A** — both producers consult [mutationBlockReason] BEFORE `beginAdmission`: a
 * blocked queue refuses typed, allocates no ticket, appends nothing.
 * * **Gate B** — `EncryptedEventStore.appendLocked` (the ONE monitor twin every production
 * append reaches: the ordinary and the crash entry) re-asks it INSIDE the existing
 * `APPEND`/`CRASH_APPEND` region + fence + monitor, so a fact that became durable after the
 * admission and before the append is seen: the append refuses typed with the queue bytes
 * unchanged, and the producers finalize the already-granted ticket through the EXISTING
 * append-refusal settlement (`LOST(APPEND_REFUSED)`) — no new loss cause, no stranded
 * PREPARED entry. The fact is written inside the `RECONCILE` region on the SAME lock pair,
 * so the two cannot interleave: no new lock order.
 *
 * The block is a property of the QUEUE, never of a session: a pending fact blocks every
 * session sharing the queue ("my own arithmetic is clean" is not a reason to move the image).
 *
 * | fact | verdict |
 * |---|---|
 * | none | open — pre-detection appends keep their existing behaviour (no scan is added) |
 * | unreadable | blocked (`reconciliation_fact_unreadable`) — unreadable is PRESENT |
 * | PREPARED / SETTLED | blocked (`reconciliation_pending:<state>`), always |
 * | CONSUMED, a consume owed whose read-back fingerprint is not the bound survivors | blocked (`consume_unproven`) |
 * | CONSUMED, `consumes == false` over a LOSSY image | blocked (`evidence_retained`): the bound lossy evidence is live and nothing consumes it under a disabled session — the queue is released only by erasure |
 * | CONSUMED, a planned owner-bound clear not yet recorded in the fact's clear history | blocked (`clear_pending`) |
 * | CONSUMED, consume durable + read back (or nothing to consume), every planned clear recorded | open |
 *
 * The enum value CONSUMED alone is never "safe to append".
 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /** Prefix of the typed refusal while the fact is PREPARED or SETTLED. */
        const val BLOCK_PENDING: String = "reconciliation_pending"
        /** Prefix of the typed refusal while a CONSUMED fact is still unresolved for mutation purposes. */
        const val BLOCK_UNRESOLVED: String = "reconciliation_unresolved"
        /** The typed refusal for an unreadable fact. */
        const val BLOCK_UNREADABLE: String = "reconciliation_fact_unreadable"

        /** The ONE mutation-block predicate, over the home's durable fact. `null` = ordinary mutation may proceed. */
        @JvmStatic
        fun mutationBlockReason(home: QueueHome): String? = mutationBlockReason(home.reconciliationFact())

        /** The same predicate over a fact reader (the store's, over `<queue>.reconcile.json`). */
        @JvmStatic
        fun mutationBlockReason(fact: ReconciliationFact): String? = when (val r = fact.read()) {
            is ReconciliationFact.Read.Unreadable -> BLOCK_UNREADABLE
            is ReconciliationFact.Read.Ok -> blockReasonOf(r.fact)
        }

        /** The pure table above, over a decoded fact. */
        @JvmStatic
        fun blockReasonOf(f: ReconciliationFact.Fact?): String? {
            if (f == null) return null
            return when (f.state) {
                ReconciliationFact.STATE_PREPARED, ReconciliationFact.STATE_SETTLED -> "$BLOCK_PENDING:${f.state}"
                ReconciliationFact.STATE_CONSUMED -> when {
                    f.consumes && f.consumedFingerprint != f.survivorFingerprint -> "$BLOCK_UNRESOLVED:consume_unproven"
                    !f.consumes && f.integrityClass != RecordStore.INTEGRITY_OK -> "$BLOCK_UNRESOLVED:evidence_retained"
                    f.sessions.any { it.clear && it.ownedBy != null && it.sessionId !in f.clearedDisables } -> "$BLOCK_UNRESOLVED:clear_pending"
                    else -> null   // resolved for mutation purposes
                }
                else -> "$BLOCK_UNRESOLVED:state_unknown"
            }
        }
    }
}

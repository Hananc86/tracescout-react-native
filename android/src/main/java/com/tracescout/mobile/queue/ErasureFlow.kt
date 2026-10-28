package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import com.tracescout.mobile.internal.loss.LossAuthority
import com.tracescout.mobile.internal.queue.EvictionAttribution
import com.tracescout.mobile.internal.queue.EvictionRecovery
import com.tracescout.mobile.internal.queue.QueueReconciler
import com.tracescout.mobile.internal.queue.ReconciliationFact
import java.io.File

/**
 * F4-P0 Slice D (D6, the capture specification / RF4-50) — THE GOVERNED TEARDOWN. The forced
 * path ("if the fence is still unavailable … proceed") does not exist here:
 * not bounded, not flag-guarded, not retained for emergencies. No queue or
 * authority evidence is destroyed without the canonical queue fence held.
 *
 * ```
 * U-1 request(filesDir) write/refresh the durable, NON-KEY-BACKED ERASURE_REQUESTED record
 * U-2 (everywhere else) every new append, bracket and drain refuses `erasure_requested` while it exists
 * U-3 resume(filesDir, …) QUEUE REGION LOCK -> QUEUE FILE FENCE, Region.TEARDOWN, on the install's domain
 * unavailable -> Pending("queue_fence_<reason>"); NOTHING destroyed; retried at the next open
 * U-4 under the fence EvictionRecovery.recover for every queue that carries a marker
 * U-4b (F4-P2 C3) an UNRESOLVED reconciliation fact beside a queue is accounted — asked through the
 * ONE mutation-gate predicate — as a claim this erasure DISCARDS, never reconciles
 * U-5 not completely resolved (a blocked bracket, OR a discarded fact) -> the EXPLICIT
 * durable `terminal_teardown` transition, recorded under this fence, BEFORE any key
 * goes, naming what it supersedes; not durable -> Pending
 * U-6 teardown destroy the queue key, then the authority key (StoreTeardown's two independent attempts)
 * U-7 clear the queue-key-backed files only if the queue key went (StoreTeardown's own gate),
 * plus the marker files; erase the authority home only if the authority key went
 * U-8 clear ERASURE_REQUESTED — only when everything above is DONE; release the fence
 * ```
 *
 * ## The wedged-process case (X-10)
 *
 * A process that holds the fence forever delays erasure forever. That is the
 * correct behaviour for storage code: making erasure eventually happen is a
 * PLATFORM operation (kill or restart the holder; the OS releases the lock),
 * and this layer neither performs it nor simulates it by proceeding anyway.
 * Every refusal is typed and the request survives for the next open.
 *
 * ## Why the marker is read by FILE NAME before a home is built
 *
 * A home needs the queue key; an erasure that is being RESUMED after U-6
 * (W-26) has no usable key and a queue nobody can read. Only a queue whose
 * marker file exists needs recovery at all, so homes are built lazily and a
 * home that cannot be built is an unresolved bracket — which U-5 answers with
 * the terminal transition rather than with a guess. A request already in the
 * `terminal_teardown` phase skips U-4/U-5 (W-36): the decision was made,
 * durably, under this fence, and is not re-litigated by a reopen.
 *
 * ## The lock is the home's lock
 *
 * `QueueRegionLock.forStoreFile` and `QueueFileFence.forStoreFile` resolve
 * the SAME registry objects every `QueueHome.withQueue` on this install uses
 * (one domain per files directory — `QueueDomain`), taken in the one order,
 * so a bracket, a drain or a recovery in any process is excluded exactly as
 * it would be by a home. The recovery driver's own `RECOVERY` region nests
 * reentrantly inside `TEARDOWN`.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object ErasureFlow {

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Outcome {
        /** No request on disk. Nothing happened. */
        object NotRequested : Outcome()

        /** The request stays; NOTHING was destroyed. Typed, and owed a retry at the next open. */
        data class Pending(val reason: String) : Outcome()

        /** Keys destroyed, files cleared, authority home erased, request cleared. [terminalTeardown] says U-5 ran. */
        data class Completed(val teardown: StoreTeardown.Outcome, val terminalTeardown: Boolean) : Outcome()

        /** U-6 ran but U-7/U-8 did not finish; the request STAYS so the next open resumes. */
        data class Incomplete(val teardown: StoreTeardown.Outcome, val reason: String) : Outcome()
    }

    /** The two production queue names whose markers an erasure must resolve. */
    @JvmStatic
    val QUEUE_NAMES: List<String> = listOf(QueueKeyStores.SDK_QUEUE, QueueKeyStores.RN_QUEUE)

    /** U-1. Idempotent; never downgrades a `terminal_teardown` phase back to `requested`. */
    @JvmStatic
    @JvmOverloads
    fun request(
        filesDir: File,
        nowMs: Long = System.currentTimeMillis(),
        dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
    ): ErasureRequest.Write {
        val req = ErasureRequest.forFilesDir(filesDir, dirSync)
        val existing = (req.read() as? ErasureRequest.Read.Ok)?.record
        if (existing?.phase == ErasureRequest.PHASE_TERMINAL_TEARDOWN) return ErasureRequest.Write.Committed
        val w = req.install(ErasureRequest.Record(nowMs, ErasureRequest.PHASE_REQUESTED, null))
        RecoveryTrace.log("erasure_requested", "outcome" to w.javaClass.simpleName)
        return w
    }

    /**
 * FAULT SEAM (F4-P2 C3) — inert by default. A test throws
 * [com.tracescout.mobile.internal.queue.DurableUploadPipeline.SimulatedProcessDeath]
 * at ONE named point so a process death at a RULED teardown boundary can be
 * driven and the next open's resume proven idempotent. The points are the
 * boundaries the frozen W-27 / W-36 rows name, plus the one between the keys
 * going and the markers leaving:
 *
 * * `after_request` — U-1 is durable, the fence is not yet taken (W-27)
 * * `after_terminal` — the U-5 `terminal_teardown` transition is durable, no key has gone (W-36)
 * * `after_teardown` — U-6 ran (keys destroyed, queue-key files cleared); U-7's marker /
 * fact removal, U-7b and U-8 have NOT run
 *
 * Production passes nothing and the flow is byte-for-byte the accepted one.
 * The seam is on the flow and NOT on the destroyers: `StoreTeardown` wraps
 * each destroyer in `runCatching`, so a death thrown there would be read as
 * a failed key destroy — a different fact.
 */
    @JvmStatic
    val NO_FAULTS: (String) -> Unit = { }

    /**
 * The PRODUCT flow: U-1 then U-2.U-8. [homeOf] is the PRODUCER's own
 * home factory (the queue key is resolved where the producers resolve it,
 * never here — the specification pins the queue key's consumers); [teardown] is the
 * producer's `StoreTeardown` call. [faults] sits BEFORE [teardown] so the
 * trailing-lambda call shape `erase(dir, homeOf) { teardown }` keeps
 * binding the teardown, exactly as it did before the seam existed.
 */
    @JvmStatic
    fun erase(
        filesDir: File,
        homeOf: (String) -> QueueHome,
        faults: (String) -> Unit = NO_FAULTS,
        teardown: () -> StoreTeardown.Outcome,
    ): Outcome {
        when (val w = request(filesDir)) {
            is ErasureRequest.Write.Committed -> Unit
            is ErasureRequest.Write.NotDurable -> return Outcome.Pending("request_not_durable:" + w.reason)
        }
        faults("after_request")
        return resume(filesDir, homeOf = homeOf, teardown = teardown, faults = faults)
    }

    /**
 * Resume an OUTSTANDING request at open (both producers call this before
 * any queue is opened): U-2.U-8 with the platform teardown. Absent ⇒
 * [Outcome.NotRequested] with nothing read beyond the request file.
 */
    @JvmStatic
    fun resumeAtOpen(filesDir: File, homeOf: (String) -> QueueHome): Outcome =
        resume(filesDir, homeOf = homeOf, teardown = { StoreTeardown.destroyAllKeyMaterial(filesDir) })

    /**
 * U-2.U-8 with every factory injectable, so the ordering can be PROVEN on
 * the JVM (there is no `AndroidKeyStore` here). Production supplies its
 * home factory and the platform teardown; the recovery driver defaults to
 * the production seam and the production scan; a test supplies its own
 * key, its own homes and its own destroyers.
 */
    @JvmStatic
    @JvmOverloads
    fun resume(
        filesDir: File,
        queueNames: List<String> = QUEUE_NAMES,
        homeOf: (String) -> QueueHome,
        recoveryOf: (QueueHome) -> EvictionRecovery = { h ->
            EvictionRecovery(h, EvictionAttribution.forFilesDir(filesDir), { LossAuthority.scanUnresolvedEvictionsAtOpen(filesDir) })
        },
        teardown: () -> StoreTeardown.Outcome,
        dirSync: DurableReplace.DirSync = DurableReplace.platformDirSync,
        nowMs: Long = System.currentTimeMillis(),
        faults: (String) -> Unit = NO_FAULTS,
    ): Outcome {
        val req = ErasureRequest.forFilesDir(filesDir, dirSync)
        val record = when (val r = req.read()) {
            is ErasureRequest.Read.Ok -> r.record ?: return Outcome.NotRequested
            // PRESENT, never absent: an unreadable request is resumed as `requested`.
            is ErasureRequest.Read.Unreadable -> ErasureRequest.Record(nowMs, ErasureRequest.PHASE_REQUESTED, "unreadable:" + r.reason)
        }
        // U-3: region, THEN fence — the one order, on the install's one domain.
        val domainFile = File(filesDir, queueNames.first())
        val regionLock = QueueRegionLock.forStoreFile(domainFile)
        val fence = QueueFileFence.forStoreFile(domainFile)
        return try {
            regionLock.withRegion(QueueRegion.TEARDOWN) {
                fence.withFence(QueueRegion.TEARDOWN) {
                    underFence(filesDir, queueNames, req, record, homeOf, recoveryOf, teardown, faults)
                }
            }
        } catch (u: QueueRegionLock.Unavailable) {
            RecoveryTrace.log("erasure_pending", "why" to "queue_region_" + u.reason.wire)
            Outcome.Pending("queue_region_" + u.reason.wire)
        } catch (u: QueueFileFence.Unavailable) {
            RecoveryTrace.log("erasure_pending", "why" to "queue_fence_" + u.reason.wire)
            Outcome.Pending("queue_fence_" + u.reason.wire)
        }
    }

    /** U-4.U-8. THE FENCE IS HELD. */
    private fun underFence(
        filesDir: File,
        queueNames: List<String>,
        req: ErasureRequest,
        record: ErasureRequest.Record,
        homeOf: (String) -> QueueHome,
        recoveryOf: (QueueHome) -> EvictionRecovery,
        teardown: () -> StoreTeardown.Outcome,
        faults: (String) -> Unit,
    ): Outcome {
        var terminal = record.phase == ErasureRequest.PHASE_TERMINAL_TEARDOWN
        if (!terminal) {
            // ── U-4: recovery, for every queue that carries a marker ──
            val unresolved = ArrayList<String>()
            for (name in queueNames) {
                if (!File(filesDir, name + QueueHome.BRACKET_SUFFIX).exists()) continue
                val home = try { homeOf(name) } catch (t: Throwable) {
                    unresolved.add("$name:home_unavailable:${t.javaClass.simpleName}"); continue
                }
                when (val o = recoveryOf(home).recover()) {
                    is EvictionRecovery.Outcome.NotNeeded, is EvictionRecovery.Outcome.Resolved -> Unit
                    is EvictionRecovery.Outcome.Blocked -> unresolved.add("$name:${o.reason}")
                }
            }
            // ── U-4b (F4-P2 C3, a product decision): account for an UNRESOLVED reconciliation fact ──
            // A whole-install erasure is a DISTINCT terminal lifecycle outcome: it does NOT finish the
            // ordinary P2 reconciliation a fact binds, and it does NOT report that fact as reconciled.
            // The fact is DISCARDED, with the queue it binds, under the durable terminal-erasure
            // transition — so an unresolved fact (asked through the ONE predicate the mutation gate
            // uses) enters U-5 exactly as an unresolvable bracket does, and the transition NAMES it.
            // Nothing here settles, advances, consumes or clears the fact; a resolved tombstone is
            // not an unresolved claim and enters no transition.
            for (name in queueNames) {
                val ff = File(filesDir, name + QueueHome.RECONCILE_SUFFIX)
                if (!ff.exists()) continue
                val block = QueueReconciler.mutationBlockReason(ReconciliationFact(ff)) ?: continue
                unresolved.add("$name:reconciliation_fact_discarded:$block")
            }
            if (unresolved.isNotEmpty()) {
                // ── U-5: the explicit durable transition, under THIS fence, before any key goes ──
                val note = unresolved.joinToString(";").take(120)
                when (val w = req.install(ErasureRequest.Record(record.requestedAtMs, ErasureRequest.PHASE_TERMINAL_TEARDOWN, note))) {
                    is ErasureRequest.Write.Committed -> Unit
                    is ErasureRequest.Write.NotDurable -> {
                        RecoveryTrace.log("erasure_pending", "why" to "terminal_transition_not_durable:" + w.reason.take(32))
                        return Outcome.Pending("terminal_transition_not_durable:" + w.reason)
                    }
                }
                RecoveryTrace.log("erasure_terminal_teardown", "unresolved" to unresolved.size)
                faults("after_terminal")
                terminal = true
            } else if (record.note?.startsWith("unreadable:") == true) {
                // The resumed request was unreadable: make the phase durable before destruction,
                // so the next process reads a fact rather than resuming a guess.
                req.install(ErasureRequest.Record(record.requestedAtMs, ErasureRequest.PHASE_REQUESTED, null))
            }
        }

        // ── U-6 + U-7: keys, then the queue-key-backed files (gated on the queue key, inside the teardown) ──
        val t = teardown()
        faults("after_teardown")
        // U-7, continued: the marker files leave only once the queue key is gone — like the queue itself.
        // F4-P2: the reconciliation fact beside each queue leaves with the marker — its
        // proof is about a queue that no longer exists once the queue key is gone.
        if (t.queueDestroyed) for (name in queueNames) {
            for (suffix in listOf(QueueHome.BRACKET_SUFFIX, QueueHome.RECONCILE_SUFFIX)) {
                val mf = File(filesDir, name + suffix)
                try { if (mf.exists()) mf.delete() } catch (_: Throwable) { /* verified below */ }
            }
        }
        val markersGone = queueNames.none {
            File(filesDir, it + QueueHome.BRACKET_SUFFIX).exists() || File(filesDir, it + QueueHome.RECONCILE_SUFFIX).exists()
        }
        // U-7b: the authority home, only once ITS key is gone (ciphertext nothing can open again).
        val authorityErased = LossAuthority.eraseAuthorityHome(filesDir, t.authorityDestroyed)
        // ── U-7c: process-local state, under BOTH locks, before the `why` gate ──
        // ONE line, and it adds no `why` arm. The generic seam's reset is provably
        // infallible (B2 option 1: no `runCatching`, no-throw pinned on the
        // contract), so there is nothing to veto and the gate below stays at FIVE
        // arms. Gated on `forwardProgressRestored` ALONE — the teardown's own
        // result for "queue key destroyed AND cleanup Done" — because a reset
        // after a teardown that did NOT restore forward progress would drop state
        // describing durable bytes that are still there.
        val processStateApplied = QueueProcessStateReset.dropIfErased(t.forwardProgressRestored)
        RecoveryTrace.log("erasure_teardown", "queue" to t.queueDestroyed, "authority" to t.authorityDestroyed,
            "cleanup" to t.cleanup.javaClass.simpleName, "authorityErased" to authorityErased, "terminal" to terminal,
            "processStateApplied" to processStateApplied)

        // ── U-8: clear the request ONLY when everything is done ──
        val why = when {
            t.cleanup !is StoreTeardown.Cleanup.Done -> "cleanup_" + t.cleanup.javaClass.simpleName.lowercase()
            !t.queueDestroyed -> "queue_key_not_destroyed"
            !markersGone -> "marker_files_remain"
            t.authorityDestroyed && !authorityErased -> "authority_home_not_erased"
            !t.authorityDestroyed -> "authority_key_not_destroyed"
            else -> null
        }
        if (why != null) return Outcome.Incomplete(t, why)
        return when (val c = req.clear()) {
            is ErasureRequest.Write.Committed -> Outcome.Completed(t, terminal)
            is ErasureRequest.Write.NotDurable -> Outcome.Incomplete(t, "request_clear_" + c.reason)
        }
    }
}

package com.tracescout.mobile.rn

import android.content.Context
import com.tracescout.mobile.internal.queue.QueueReconciler
import com.tracescout.mobile.queue.EncryptedEventStore
import com.tracescout.mobile.queue.RecoveryTrace
import com.tracescout.mobile.queue.StoreKeys
import com.tracescout.mobile.transport.Uploader
import com.tracescout.rn.Exclusion
import com.tracescout.rn.FrameBudgets
import com.tracescout.rn.FrameDelivery
import com.tracescout.rn.FrameHeader
import com.tracescout.rn.FrameInFlightStore
import com.tracescout.rn.FrameLossGateOutcome
import com.tracescout.rn.FrameRefusalReason
import com.tracescout.rn.FrameRemoval
import java.io.File

/**
 * Durable, encrypted store for ALREADY-REDACTED frame bytes awaiting upload.
 *
 * The same proven container as the event queue (`EncryptedEventStore`, same
 * Keystore-backed key discipline), a separate file. A frame's METADATA event
 * rides the ordinary record queue; the BYTES ride here and are posted to the
 * existing `/replay/frames` endpoint, which binds them by contentHash and
 * de-duplicates identical bytes.
 *
 * **This class is now only the ADAPTER.** The delivery rules live in the pure
 * `FrameDelivery` state machine in rn-core, so process death, a missing
 * acknowledgement and the retry path are driven under JVM tests exactly as they
 * run on a device. It used to hold its own drain, and that drain removed the
 * head record and only then re-appended a copy with an incremented attempt
 * count — a window in which a crash lost the frame permanently, on an endpoint
 * where a duplicate costs nothing.
 *
 * The store is capped at [FrameBudgets.STORE_CAP_BYTES]; a frame that would
 * overflow it is refused at enqueue, never silently dropped later.
 *
 * AND THAT CLAIM IS NOW CARRIED BY THE TYPE. It used to rest on ARITHMETIC
 * BETWEEN TWO CONSTANTS IN TWO MODULES: the store was opened with two
 * arguments, so `maxBytes` defaulted to 8 MiB over ENCODED sizes and the policy
 * defaulted to `ACTIVE_QUEUE`, while the soft cap above is 4 MiB over PLAINTEXT
 * sizes. The soft cap bound first by a wide margin, so eviction inside the frame
 * store was unreachable — by accident, not by construction. Either constant
 * moving re-opened it, and the sentence above would still have read as a
 * guarantee. `PRESERVE` makes "never silently dropped later" a property of the
 * store rather than of a coincidence, which is the lesson
 * `EncryptedEventStore`'s own constructor already records about `ParkStore`.
 */
object RnFrameStore {
    /**
 * THE CANONICAL QUEUE-KEY INVENTORY IS THE OWNER OF THIS NAME. The teardown
 * clears every queue-key-backed file by that inventory; a literal here is how a
 * store silently stops being covered and stalls for ever after an erasure.
 */
    const val FILE = com.tracescout.mobile.queue.QueueKeyStores.RN_FRAMES
    const val INFLIGHT_FILE = "tracescout-rn-frame-inflight.json"

    private fun open(ctx: Context): EncryptedEventStore =
        EncryptedEventStore(
            File(ctx.applicationContext.filesDir, FILE),
            StoreKeys.queueKey(),
            evictionPolicy = EncryptedEventStore.EvictionPolicy.PRESERVE,
        )

    /**
 * **ONE GATE PER PROCESS PER FILES DIR.** The gate memoises its
 * reconciliation verdict for the process, so constructing a fresh one at
 * every call site would re-run the whole five-step reconciliation on every
 * enqueue and every mutation — and, worse, would make "once per process" a
 * sentence rather than a property.
 */
    @Volatile private var cachedGate: Pair<String, RnFrameLossGate>? = null

    /**
 * The accounting gate for this app's frame domain.
 *
 * **THROWS WHEN THE KEYSTORE IS DEAD, DELIBERATELY.** Resolving the key
 * here is what lets `enqueue` classify that as an IN-SCOPE class (G6-B) and
 * not as a refused exclusion (G6-C) — see the call site.
 */
    private fun gate(ctx: Context): RnFrameLossGate {
        val dir = ctx.applicationContext.filesDir
        cachedGate?.let { if (it.first == dir.path) return it.second }
        return synchronized(this) {
            cachedGate?.let { if (it.first == dir.path) return@synchronized it.second }
            val g = RnFrameLossGate(
                filesDir = dir,
                key = StoreKeys.queueKey(),
                // INJECTED FROM THIS ONE HOME. The gate constructs no
                // `EncryptedEventStore` of its own — a second construction site
                // carrying its own `PRESERVE` argument would silently diverge
                // from `open` the day either gained an argument, which is the
                // defect `:sdk`'s F21 consumer inventory caught in step 5.
                framesView = { framesView(ctx) },
            )
            cachedGate = dir.path to g
            g
        }
    }

    /**
 * Everything the gate may know about the frame store, read through the ONE
 * `open` home.
 *
 * **[FramesView.present] IS `File.exists`, NEVER A READ.** A committed
 * removal down to empty leaves the file PRESENT at length 0, while an absent
 * file reads trustworthy-empty — and the discriminator must keep those two
 * apart, because one is "a removal committed" and the other is "there was
 * never anything here".
 */
    private fun framesView(ctx: Context): FramesView {
        val present = File(ctx.applicationContext.filesDir, FILE).exists()
        val read = try { open(ctx).readAll() } catch (_: Throwable) { null }
        return FramesView(
            present = present,
            // An unreadable store is NOT trustworthy — fail closed.
            trustworthy = read?.isTrustworthy == true,
            records = read?.records ?: emptyList(),
        )
    }

    /** TEST-ONLY: drop the memoised gate so a case can drive a fresh process state. */
    internal fun forgetGateForTest() { cachedGate = null }

    /** Bytes currently held. -1 = unreadable. */
    fun bytes(ctx: Context): Long = try { open(ctx).readAll().records.sumOf { it.size.toLong() } } catch (_: Throwable) { -1 }
    fun count(ctx: Context): Int = try { open(ctx).readAll().records.size } catch (_: Throwable) { -1 }

    /**
 * Returns false when the cap would be exceeded or the write failed.
 *
 * `.stored` WAS THE ONLY THING READ; `reason` and `evicted` were
 * discarded, so every distinct refusal — soft cap, store cap, oversized
 * record, IO failure — reached the caller as one indistinguishable `false`.
 * A frame carries no sequence of its own, so a refusal here is not a
 * sequence gap; it is a `visualFrames` victim attributable through its
 * metadata event. Phase 0 makes the reason OBSERVABLE (the accounting that
 * consumes it is a later gate) and keeps the Boolean contract unchanged.
 */
    fun enqueue(ctx: Context, header: FrameHeader, bytes: ByteArray): Boolean {
        // **THE EPOCH IS READ BEFORE THE ACQUISITION.** A witness setter is
        // epoch-guarded, and the generation it must be judged against is the one
        // that was live when this refusal BEGAN. Reading it after a wait would
        // let a refusal from before an erasure be flushed into the fresh ledger
        // the exact N5 defect the epoch exists to close.
        val at = RnFrameLossProcessState.epoch()

        // **A DEAD KEYSTORE IS AN IN-SCOPE CLASS (G6-A/B), NEVER G6-C.** The
        // domain is keyed on the store's PATH alone, so the scope would be
        // acquirable — but with no key there is no ledger to write to either, so
        // no durable write can commit and the observed-outcome rule makes this
        // G6-B: a monotonic DISCLOSURE, never a count. Reporting it as G6-C
        // would claim the exclusion was refused, which is false.
        val sink = try { gate(ctx) } catch (t: Throwable) {
            RnFrameLossProcessState.setRefusalRecordingOwed(at)
            RecoveryTrace.log("rn_frame_enqueue_refused",
                "reason" to FrameRefusalReason.KEYSTORE_UNAVAILABLE.name,
                "at" to "key", "class" to (t::class.java.simpleName), "durability" to "g6_b")
            return false
        }

        // **THE WHOLE BODY IS INSIDE ONE ACQUISITION, AND THE SOFT-CAP READ IS
        // INSIDE IT.** That is the `[0b]` fix: the read and the append used to be
        // two unsynchronised store calls, so two concurrent enqueues could both
        // observe the same `held` and both append past the cap.
        return when (val ex = sink.withEnqueueScope { enqueueInRegion(ctx, header, bytes, sink, at) }) {
            is Exclusion.Ran -> ex.value
            is Exclusion.Refused -> {
                // **G6-C — structurally unrecordable.** The scope never existed,
                // so there was nowhere to record the refusal. The witness is
                // set with NO lock held, which is safe because the word is a
                // single CAS, and it is epoch-guarded so a refusal that began
                // before an erasure cannot be disclosed after it.
                RnFrameLossProcessState.setExclusionRefusalOwed(at)
                RecoveryTrace.log("rn_frame_enqueue_refused",
                    "reason" to "exclusion_refused", "wire" to ex.wire,
                    "waitedMs" to ex.waitedMs, "durability" to "g6_c")
                false
            }
        }
    }

    /**
 * The former body, with the whole-file anonymous catch REPLACED by per-step
 * typed containment that NAMES THE SOURCE.
 *
 * **THE RETIRED ARM WAS `} catch (_: Throwable) { false }` AROUND THE
 * ENTIRE BODY** — `open`, `readAll`, the soft-cap branch, `encode`, `append`
 * and both traces. Every distinct cause reached the caller as one
 * indistinguishable `false` and left no trace naming it, which is the `[0c]`
 * class: three different store-unavailable causes collapsed into one opaque
 * reason. They are now three separate slots, and restoring the single arm is
 * a negative control that must FAIL.
 *
 * **THERE IS NO TRAILING ANONYMOUS ARM.** The residual is RULED, not
 * silent: an unexpected throw maps to [FrameRefusalReason.OTHER] and is
 * traced WITH THE THROWABLE'S CLASS NAME. Letting it propagate is not an
 * option — `RnTransport.enqueueFrame` does not catch, so it would reach the
 * host app from a capture-path API.
 */
    private fun enqueueInRegion(
        ctx: Context,
        header: FrameHeader,
        bytes: ByteArray,
        loss: RnFrameLossGate,
        at: Long,
    ): Boolean {
        val s = try { open(ctx) } catch (t: Throwable) {
            return refuse(FrameRefusalReason.KEYSTORE_UNAVAILABLE, header, loss, at, "open", t)
        }
        val held = try { s.readAll().records.sumOf { it.size.toLong() } } catch (t: Throwable) {
            return refuse(FrameRefusalReason.STORE_READ_FAILED, header, loss, at, "read_all", t)
        }
        if (held + bytes.size + 512 > FrameBudgets.STORE_CAP_BYTES) {
            return refuse(FrameRefusalReason.SOFT_CAP, header, loss, at, "soft_cap", null, held, bytes.size)
        }
        val record = try { com.tracescout.rn.FrameRecord.encode(header, bytes) } catch (t: Throwable) {
            // `FrameRecord.encode`'s `require(h.size <= MAX_HEADER)` — a producer
            // defect, and its own named slot rather than a generic store fault.
            return refuse(FrameRefusalReason.HEADER_TOO_LARGE, header, loss, at, "encode", t)
        }
        val r = try { s.append(record) } catch (t: Throwable) {
            return refuse(FrameRefusalReason.OTHER, header, loss, at, "append_threw", t)
        }
        // THERE USED TO BE AN `r.evicted != 0` ARM HERE. Since F4-P0
        // Slice C `append` destroys nothing under any policy and carries
        // no `evicted` field, so "frames destroyed to make room for frames"
        // is unrepresentable rather than merely unreachable.
        if (r.stored) return true
        return refuse(appendReason(r.reason), header, loss, at, r.reason ?: "unknown", null, held, bytes.size)
    }

    /**
 * ONE home for "a G6 refusal was observed inside the scope".
 *
 * **THE CLASS IS THE OBSERVED LEDGER-WRITE OUTCOME, NEVER THE REFUSAL
 * REASON.** The same [FrameRefusalReason] is **G6-A** when the ledger append
 * COMMITS and **G6-B** when it does not. No reason-to-durability mapping
 * exists, may be inferred from any table, or may be relied on by any caller
 * which is why this function takes the reason and then ASKS, instead of
 * deciding from it.
 */
    private fun refuse(
        reason: FrameRefusalReason,
        header: FrameHeader,
        loss: RnFrameLossGate,
        at: Long,
        where: String,
        cause: Throwable?,
        held: Long = -1,
        size: Int = -1,
    ): Boolean {
        val committed = loss.recordRefusal(reason, header.sessionId)
        if (!committed) {
            // **G6-B — a DISCLOSURE, never a count.** The next committed write
            // will carry `refusalRecordingIncomplete`.
            RnFrameLossProcessState.setRefusalRecordingOwed(at)
        }
        RecoveryTrace.log("rn_frame_enqueue_refused",
            "reason" to reason.name, "at" to where,
            // The throwable's CLASS NAME only — the opposite of the retired
            // anonymous arm, which named nothing at all.
            "class" to (cause?.let { it::class.java.simpleName } ?: ""),
            "held" to held, "bytes" to size,
            "durability" to if (committed) "g6_a" else "g6_b")
        return false
    }

    /**
 * The ONE `when` over the store's EXISTING typed append tokens.
 *
 * **THE RECONCILIATION PREFIXES COME FROM `:sdk`'s OWN CONSTANTS**, not
 * from literals here — the tokens carry a `:<state>` suffix, so a literal
 * would be a second spelling of one fact and would drift the day a state is
 * added.
 *
 * **AN UNRECOGNISED TOKEN IS [FrameRefusalReason.OTHER], AND THE TOKEN IS
 * TRACED.** The authority's table does not enumerate the store's lock-layer
 * tokens (`append_refused_<wire>`), and the counted set is CLOSED at 13, so
 * `OTHER` is the only honest member: no taxonomy is invented and the exact
 * token stays observable. **DISCLOSED as a reading** — the alternative would
 * be a 14th member, which the frozen enum forbids.
 *
 * **`internal` SO THE MAPPING IS DRIVEN, NOT SCANNED.** `:rn` carries no
 * Robolectric, so `enqueue` itself is not JVM-drivable — and a source scan
 * is the weakest possible proof of a 13-way table. This is the one piece of
 * the write site that is pure, so it is exposed to the module's own tests
 * and asserted against `:sdk`'s REAL token constants.
 */
    internal fun appendReason(token: String?): FrameRefusalReason = when {
        token == null -> FrameRefusalReason.OTHER
        token == "empty_record" -> FrameRefusalReason.EMPTY_RECORD
        token == "record_too_large" -> FrameRefusalReason.RECORD_TOO_LARGE
        token == "record_exceeds_capacity" -> FrameRefusalReason.RECORD_EXCEEDS_CAPACITY
        token == "at_capacity" -> FrameRefusalReason.AT_CAPACITY
        token == "io_error" -> FrameRefusalReason.IO_ERROR
        token == QueueReconciler.BLOCK_UNREADABLE -> FrameRefusalReason.RECONCILIATION_FACT_UNREADABLE
        token.startsWith(QueueReconciler.BLOCK_PENDING + ":") -> FrameRefusalReason.RECONCILIATION_PENDING
        token.startsWith(QueueReconciler.BLOCK_UNRESOLVED + ":") -> FrameRefusalReason.RECONCILIATION_UNRESOLVED
        else -> FrameRefusalReason.OTHER
    }

    data class DrainOutcome(val delivered: Int, val refused: Int, val retained: Int, val unreadable: Int)

    /** The durable descriptor of the single unresolved frame, beside the queue. */
    private fun inflight(ctx: Context): FrameInFlightStore =
        FrameInFlightStore.file(File(ctx.applicationContext.filesDir, INFLIGHT_FILE))

    /**
 * Post every stored frame through the shared state machine.
 *
 * A frame is removed ONLY on a valid `accepted`/`duplicate` acknowledgement,
 * a permanent record-level refusal, or exhaustion. A 2xx with no parseable
 * acknowledgement retains the bytes.
 */
    fun drain(ctx: Context, projectId: String, uploader: Uploader): DrainOutcome {
        val s = try { open(ctx) } catch (_: Throwable) { return DrainOutcome(0, 0, 0, -1) }
        val sink = try { gate(ctx) } catch (_: Throwable) { return DrainOutcome(0, 0, 0, -1) }
        // **THE SCOPE IS TAKEN AROUND THE GATE ONLY, THEN RELEASED.** The
        // drain performs a network request per frame, and holding an OS lock
        // across an HTTP request turns a remote stall into a local one. Each
        // mutation site re-acquires for exactly as long as its own guard read
        // and mutation take.
        //
        // **A REFUSED GATE STOPS THE PASS — FAIL CLOSED.** "I could not
        // reconcile" is not permission to destroy frames; every site would
        // refuse anyway, and stopping here says so once instead of five times.
        if (sink.ensureReconciled() is FrameLossGateOutcome.Refused) {
            RecoveryTrace.log("rn_frame_drain_gate_refused", "store" to FILE)
            return DrainOutcome(0, 0, 0, 0)
        }
        val queue = object : FrameDelivery.Queue {
            override fun readAll(): List<ByteArray> = s.readAll().records
            override fun append(record: ByteArray): Boolean = s.append(record).stored
            override fun removeFirst(count: Int): Int = s.removeFirst(count)

            /**
 * STEP 5/7 FIRST ACT (F7-A) — the MAPPING, and nothing else.
 *
 * **PROVABLY INERT TODAY.** `removeFirstTyped` has ZERO
 * production callers: all five `FrameDelivery` discard sites still
 * go through `removedOne` -> [removeFirst]. This override exists so
 * `:rn` compiles against step 4's abstract declaration; it changes
 * no runtime path until step 6 wires the callers.
 *
 * **`durability` ALONE IS SUFFICIENT, AND THAT WAS MEASURED.**
 * Every `RemoveResult` construction in the store was enumerated: the
 * lock-refusal path, the four early returns and the unsynced path all
 * carry `removed = 0`, and only the committed path carries
 * `COMMITTED`. So the adapter need not read `removed` or `refusal`,
 * and reading them would invent a second opinion about one fact.
 *
 * **THE `when` IS EXHAUSTIVE WITH NO `else` ON PURPOSE.** A fourth
 * `Durability` value must become a COMPILE ERROR here rather than a
 * silent mis-report through a catch-all arm.
 *
 * **[removeFirst] AND THIS ARE INDEPENDENT ENTRY POINTS AND EACH
 * PERFORMS ONE REMOVAL.** `removeFirst(count)` is
 * `removeFirstDetailed(count).removed` — the same destructive call.
 * Implementing either by delegating to the other, or calling BOTH at
 * one site, removes TWICE. Unreachable today (zero callers); step 6
 * must route each G9/G10 discard through EXACTLY ONE of them, and
 * `RnFrameStoreAdapterTest` pins that structurally now so the trap
 * cannot be walked into later.
 *
 * No durable-store logic, no `FrameLossSink`, no
 * `prepare`/`resolve`/`recordRefusal`, no sequence effect, no
 * `try`/`catch` (a catch here would turn a store fault into a
 * reported removal state; `FrameDelivery.removedOne` already catches
 * at the call site).
 */
            override fun removeFirstTyped(count: Int): FrameRemoval =
                when (s.removeFirstDetailed(count).durability) {
                    EncryptedEventStore.Durability.COMMITTED ->
                        FrameRemoval.COMMITTED
                    EncryptedEventStore.Durability.NOT_COMMITTED ->
                        FrameRemoval.NOT_COMMITTED
                    EncryptedEventStore.Durability.INDETERMINATE_DIRECTORY_UNSYNCED ->
                        FrameRemoval.INDETERMINATE_DIRECTORY_UNSYNCED
                }
        }
        val out = FrameDelivery.drain(
            queue = queue,
            inflight = inflight(ctx),
            post = { h, bytes ->
                val r = uploader.postFrame(
                    projectId = projectId, sessionId = h.sessionId, visualFrameId = h.visualFrameId,
                    bytes = bytes, width = h.width, height = h.height, contentSha256 = h.contentHash,
                    format = h.format, privacyState = FrameBudgets.PRIVACY_STATE,
                    captureReason = FrameBudgets.CAPTURE_REASON, screenId = h.screenId,
                    scope = FrameBudgets.SCOPE,
                )
                FrameDelivery.PostResult(
                    status = r.status,
                    // A bare 2xx is not an acknowledgement. The delivery state
                    // machine additionally checks this identity against the
                    // descriptor it froze before the request.
                    ack = r.frameAck?.let {
                        FrameDelivery.FrameAck(
                            state = it.state, sessionId = it.sessionId,
                            visualFrameId = it.visualFrameId,
                            contentHash = it.contentHash, byteSize = it.byteSize,
                        )
                    },
                    code = r.code,
                )
            },
            // NO DEFAULT EXISTS FOR THIS PARAMETER, so this call site is the
            // compiler's proof that the one production drain accounts through
            // the one gate.
            loss = sink,
            trace = { event, fields -> RecoveryTrace.log(event, *fields.toTypedArray()) },
            nowMs = { System.currentTimeMillis() },
        )
        // `refused` reports record-level rejections AND exhaustion; both mean the
        // frame will render as a typed placeholder rather than a wrong image.
        return DrainOutcome(
            delivered = out.delivered + out.duplicate,
            refused = out.refused + out.exhausted,
            retained = out.retained,
            unreadable = out.unreadable,
        )
    }
}

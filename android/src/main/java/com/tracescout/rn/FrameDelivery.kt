package com.tracescout.rn

/**
 * The frame delivery state machine — pure, so it can be driven under a JVM test
 * exactly as it runs on a device.
 *
 * It replaces a drain that removed the head record and only then re-appended a
 * copy with an incremented attempt count. That window lost the frame on a crash,
 * and it removed the bytes on a bare 2xx that carried no acknowledgement at all.
 * Both are the same mistake the chunk pipeline already paid for.
 *
 * THE TWO RULES, and why they point in opposite directions from the chunk case:
 *
 * 1. **Never remove before durable settlement.** A frame is removed only after
 * a valid `accepted`/`duplicate` acknowledgement, a permanent record-level
 * refusal, or exhaustion. A 2xx whose body does not actually say `accepted`
 * is NOT an acknowledgement (proxies, truncation, an error page with a 200):
 * the frame is retained.
 * 2. **When a window is unavoidable, prefer DUPLICATION to loss.** Rotating a
 * retried frame to the tail is what keeps one dead upload from blocking
 * every later frame. It is done append-first, verified, then remove — so a
 * crash inside the window leaves a second copy of the same bytes. The
 * endpoint binds a frame by `contentHash` and de-duplicates, so a duplicate
 * costs nothing; a keyframe nothing can recreate costs everything.
 *
 * The chunk pipeline reaches the opposite conclusion (freeze the exact prefix,
 * never re-form it) because a BATCH's identity is its composition and a second
 * copy is a genuinely different, uncollapsible piece of work. A frame is one
 * content-addressed unit, so the same principle produces the other answer.
 */
object FrameDelivery {

    /** The server's `frame-ack-1`, as parsed by the transport. */
    data class FrameAck(
        val state: String,
        val sessionId: String,
        val visualFrameId: String,
        val contentHash: String,
        val byteSize: Long,
    )

    /** What the transport made of one frame POST. */
    data class PostResult(
        val status: Int,
        /**
 * The identity-bound acknowledgement, or null. A 2xx is NOT this: a
 * proxy page, a truncated body and an error page served with a 200 are
 * all 2xx, and none of them is permission to delete durable bytes.
 */
        val ack: FrameAck? = null,
        val code: String? = null,
    )

    /** Settlement states. `duplicate` is a settlement, not a failure. */
    private val ACK_STATES = setOf("accepted", "duplicate")

    /**
 * The ONE settlement decision, matching the shared protocol rule
 * (`mobile-protocol` `evaluateFrameAck`): the acknowledgement must prove the
 * committed frame belongs to the SAME session and carries the EXACT content
 * identity and size of the unresolved in-flight frame.
 *
 * Returns null when it settles; otherwise a typed refusal code — never a
 * message built from the response, so it can be traced without leaking a
 * body.
 */
    internal fun ackRefusal(ack: FrameAck?, d: FrameInFlight): String? = when {
        ack == null -> "ack_absent"
        ack.state !in ACK_STATES -> "ack_state_unknown"
        ack.sessionId != d.sessionId -> "ack_session_mismatch"
        ack.visualFrameId != d.visualFrameId -> "ack_frame_mismatch"
        ack.contentHash != d.contentHash -> "ack_content_mismatch"
        ack.byteSize != d.byteSize.toLong() -> "ack_byte_size_mismatch"
        else -> null
    }

    data class Outcome(
        val delivered: Int = 0,
        val duplicate: Int = 0,
        val refused: Int = 0,
        val retained: Int = 0,
        val unreadable: Int = 0,
        val exhausted: Int = 0,
        /** Set when the descriptor could not be persisted: nothing was sent. */
        val failedClosed: Boolean = false,
    )

    /** The durable queue seam — the production implementation is the encrypted
 * store; a test supplies a list. */
    interface Queue {
        fun readAll(): List<ByteArray>
        fun append(record: ByteArray): Boolean
        fun removeFirst(count: Int): Int

        /**
 * The DURABILITY of a head removal, not just how many records left.
 *
 * **ABSTRACT, WITH NO DEFAULT, DELIBERATELY.** A default would let
 * every existing implementer keep answering the question it was never
 * asked — and the only honest default is a lie in one direction or the
 * other: `COMMITTED` claims durability nobody established, while
 * `NOT_COMMITTED` reports every successful removal as undurable. No
 * default breaks each implementer at compile time and makes it state
 * which of the three it actually observed. This is the same discipline
 * the store's own `RemoveResult.durability` carries, for the same
 * reason.
 *
 * **STEP 4/7 DECLARED THIS; STEP 6/7 CALLS IT.** The two G9/G10
 * discard sites route PREPARE -> typed removal -> RESOLVE through
 * `discardHead`; the three pass-level sites keep [removeFirst] via
 * `removedOne`, which is what the contract assigns them. Declaring the
 * vocabulary first is what let step 5's durable store and step 6's wiring
 * be written against ONE agreed contract instead of two.
 *
 * **THIS AND [removeFirst] ARE INDEPENDENT ENTRY POINTS AND EACH
 * PERFORMS ONE REMOVAL.** Implementing either by delegating to the other,
 * or calling BOTH at one site, removes TWICE. Every site is graded
 * SEPARATELY — a scan proving that no FUNCTION names both is necessary
 * and NOT sufficient, because one function could call one entry point
 * twice.
 */
        fun removeFirstTyped(count: Int): FrameRemoval
    }

    /** Sanitized checkpoints. Ids, a digest, a length and a counter only. */
    fun interface Trace { fun log(event: String, fields: List<Pair<String, Any?>>) }

    const val MAX_ATTEMPTS = 8

    /**
 * @param post called at most once per frame per pass.
 * @param loss the durable accounting seam. **NO DEFAULT, DELIBERATELY** —
 * see below.
 * @param nowMs injected so a test does not depend on the wall clock.
 *
 * **[loss] HAS NO DEFAULT, AND THAT IS THE POINT.** Every default that
 * could be written here is a lie in one direction: a no-op sink would let
 * every existing caller keep destroying frames with no durable accounting
 * while the signature claimed otherwise, and a throwing one would turn a
 * missing wiring into a crash on the capture path. No default breaks each
 * caller at COMPILE TIME and makes it state which sink it accounts through —
 * the same discipline `Queue.removeFirstTyped` and the store's
 * `RemoveResult.durability` already carry, for the same reason.
 *
 * **STEP 6/7 WIRES THE FIVE MUTATION SITES.** Each destructive mutation
 * and every guard read used to DECIDE it now run inside ONE
 * `FRAME_BRACKET` exclusion, so a concurrent remover cannot land between the
 * read and the mutation. The two DISCARD sites additionally route
 * PREPARE -> TYPED REMOVAL -> RESOLVE, so a durable PENDING exists BEFORE the
 * physical loss and a crash in the window is reconcilable. [post] stays
 * OUTSIDE every exclusion: holding an OS lock across an HTTP request turns a
 * remote stall into a local one.
 */
    fun drain(
        queue: Queue,
        inflight: FrameInFlightStore,
        post: (FrameHeader, ByteArray) -> PostResult,
        loss: FrameLossSink,
        trace: Trace = Trace { _, _ -> },
        nowMs: () -> Long = { 0L },
    ): Outcome {
        var delivered = 0; var duplicate = 0; var refused = 0
        var retained = 0; var unreadable = 0; var exhausted = 0

        val snapshot = try { queue.readAll() } catch (_: Throwable) { return Outcome(unreadable = -1) }
        // A rotated frame goes to the TAIL and therefore back into this walk.
        // Without this the pass comes around the ring and posts the same frame
        // again — a retry storm inside one drain, and the attempt counter would
        // burn through MAX_ATTEMPTS in a single pass. One frame, one attempt,
        // per pass.
        val seen = HashSet<String>()
        // Walk a snapshot but always operate on the LIVE head: the container is
        // FIFO and every disposition either removes the head or rotates it.
        for (i in snapshot.indices) {
            val head = try { queue.readAll().firstOrNull() } catch (_: Throwable) { null } ?: break
            val decoded = FrameRecord.decode(head)
            if (decoded == null) {
                // Nothing to preserve: an undecodable record cannot be posted
                // and cannot be repaired. Removing it is not a loss.
                //
                // But the COUNT is a claim about the store. `removeFirst`
                // returns the number actually removed; ignoring it counted an
                // `unreadable` for a record still at the head — and since this
                // branch `continue`s, the next iteration met the same head and
                // counted it again, reporting one stuck record as N while every
                // frame behind it starved.
                //
                // **STEP 6 — G9.** The decision input (the head, and the fact
                // that it does not decode) is re-read INSIDE the exclusion that
                // performs the removal, so the window between "I read an
                // undecodable head" and "I removed the head" is closed against a
                // concurrent remover. The store's own per-call region lock cannot
                // close it: two calls, two acquisitions.
                val why = discardHead(
                    queue = queue, loss = loss,
                    op = FrameMutationOp.UNREADABLE_DISCARD,
                    cause = FrameLossCause.UNREADABLE_RECORD,
                    // G9 IS WHOLLY UNATTRIBUTED. An undecodable record cannot
                    // be read, so there is no session to attribute it to — and
                    // taking one from the in-flight descriptor would attribute a
                    // loss to a session on the strength of a DIFFERENT record.
                    sessionId = null,
                    expected = head,
                    stillEligible = { live -> FrameRecord.decode(live) == null },
                )
                if (why != null) {
                    trace.log("frame_remove_not_committed", listOf(
                        "by" to "unreadable", "index" to i, "why" to why))
                    return Outcome(delivered, duplicate, refused, retained, unreadable, exhausted)
                }
                unreadable++
                inflight.load()?.let { inflight.clear() }
                continue
            }
            val (h, bytes) = decoded
            val d0 = inflight.load()
            val identity = "${h.sessionId}|${h.visualFrameId}|${h.contentHash}|${bytes.size}"

            // A descriptor naming something other than the head describes a
            // frame that already settled or rotated: reconcile with NO request.
            if (d0 != null && d0.identity() != identity) {
                trace.log("frame_inflight_reconciled", listOf("visualFrameId" to d0.visualFrameId))
                inflight.clear()
            }
            if (!seen.add(identity)) break   // came around the ring; done for this pass
            val resumed = if (d0 != null && d0.identity() == identity) d0 else null
            if (resumed != null) {
                trace.log("frame_inflight_resumed", listOf(
                    "sessionId" to h.sessionId, "visualFrameId" to h.visualFrameId,
                    "contentHash" to h.contentHash, "byteSize" to bytes.size,
                    "attempts" to resumed.attempts))
            }
            // The header only advances on a COMPLETED rotation; the descriptor
            // advances before the request. A process dying inside every attempt
            // would otherwise leave the count at zero forever.
            val prior = maxOf(h.attempts, resumed?.attempts ?: 0)
            if (prior >= MAX_ATTEMPTS) {
                // `exhausted` IS A LOSS COUNT — it says these bytes were
                // given up on and removed. It may not be taken on an
                // uncommitted removal.
                //
                // **STEP 6 — G10.** Same shape as G9: the head and the
                // attempts predicate are re-derived from the LIVE bytes inside
                // the exclusion that removes them, and a durable PENDING exists
                // before the physical loss.
                val why = discardHead(
                    queue = queue, loss = loss,
                    op = FrameMutationOp.RETRY_EXHAUSTED_DISCARD,
                    cause = FrameLossCause.RETRY_EXHAUSTED,
                    // G10 SUPPLIES THE RECORD'S OWN SESSION AND NOTHING ELSE.
                    // The ledger VALIDATES it and attributes only a validated
                    // one; this site states a fact and invents no attribution.
                    sessionId = h.sessionId,
                    expected = head,
                    stillEligible = { live ->
                        val d = FrameRecord.decode(live)
                        d != null && maxOf(d.first.attempts, resumed?.attempts ?: 0) >= MAX_ATTEMPTS
                    },
                )
                if (why != null) {
                    trace.log("frame_remove_not_committed", listOf(
                        "by" to "exhausted", "visualFrameId" to h.visualFrameId, "why" to why))
                    return Outcome(delivered, duplicate, refused, retained, unreadable, exhausted)
                }
                exhausted++; inflight.clear()
                trace.log("frame_inflight_settled", listOf(
                    "by" to "exhausted", "visualFrameId" to h.visualFrameId, "attempts" to prior))
                continue
            }

            val d = FrameInFlight(
                sessionId = h.sessionId, visualFrameId = h.visualFrameId,
                contentHash = h.contentHash, byteSize = bytes.size,
                attempts = prior + 1,
                firstAttemptAtMs = resumed?.firstAttemptAtMs ?: nowMs(),
            )
            // FAIL CLOSED. Sending on the strength of a descriptor we could not
            // persist is exactly the unresolved state we cannot recover from.
            if (!inflight.save(d)) {
                trace.log("frame_inflight_freeze_failed", listOf("visualFrameId" to h.visualFrameId))
                return Outcome(delivered, duplicate, refused, retained, unreadable, exhausted,
                               failedClosed = true)
            }
            trace.log("frame_inflight_frozen", listOf(
                "sessionId" to h.sessionId, "visualFrameId" to h.visualFrameId,
                "contentHash" to h.contentHash, "byteSize" to bytes.size,
                "attempts" to d.attempts))

            val r = try { post(h, bytes) } catch (_: Throwable) { PostResult(0, null, code = "transport_error") }
            trace.log("rn_frame_post", listOf(
                "status" to r.status, "code" to (r.code ?: ""), "attempt" to d.attempts))

            // A frame settles ONLY on a frame-ack-1 whose identity matches the
            // descriptor we froze before the request. Everything else — a bare
            // 2xx, a mismatched identity, an unknown state — RETAINS the bytes.
            val ackFail = if (r.status in 200..299) ackRefusal(r.ack, d) else "ack_absent"
            val settled = r.status in 200..299 && ackFail == null
            val permanent = r.status in 400..499 && r.status != 404 && r.status != 408 && r.status != 429

            when {
                settled -> {
                    // Re-check the head is still the frame we sent before
                    // removing anything — the same guard the acknowledged chunk
                    // path uses. **STEP 6: THAT GUARD READ AND THE REMOVAL ARE
                    // NOW INSIDE ONE EXCLUSION**, which is the whole point —
                    // `headIdentity` and `removeFirst` each take the store's own
                    // per-call region lock, so without an outer hold a concurrent
                    // remover can land between them and this site would remove a
                    // frame it never acknowledged.
                    val site = loss.withExclusion(FrameMutationOp.ACK_SETTLEMENT) {
                        if (headIdentity(queue) == identity) Settle.Removed(removedOne(queue))
                        else Settle.HeadMoved
                    }
                    when (site) {
                        is Exclusion.Ran -> when (val v = site.value) {
                            is Settle.Removed -> {
                                // The ack is authoritative about the SERVER, so the
                                // delivered/duplicate count stands either way — but a
                                // failed local removal leaves a stuck head that every
                                // later iteration would re-read, so the pass ends and
                                // says so rather than spinning.
                                if (r.ack!!.state == "duplicate") duplicate++ else delivered++
                                trace.log("frame_inflight_settled", listOf(
                                    "by" to r.ack.state,
                                    "visualFrameId" to h.visualFrameId, "attempts" to d.attempts))
                                if (!v.committed) {
                                    trace.log("frame_remove_not_committed", listOf(
                                        "by" to r.ack.state, "visualFrameId" to h.visualFrameId))
                                    inflight.clear()
                                    return Outcome(delivered, duplicate, refused, retained,
                                                   unreadable, exhausted)
                                }
                            }
                            Settle.HeadMoved ->
                                trace.log("frame_head_moved", listOf("visualFrameId" to h.visualFrameId))
                        }
                        is Exclusion.Refused -> {
                            // THE BLOCK NEVER RAN, SO NOTHING WAS READ AND
                            // NOTHING WAS MUTATED — no count is taken and **NO
                            // DISCLOSURE IS SET**: the frame is still there, and
                            // only a refused `enqueue` acquisition is G6-C. The
                            // pass ends rather than re-posting an acknowledged
                            // head; the server collapses the duplicate by
                            // contentHash when the next pass delivers it.
                            trace.log("frame_exclusion_refused", listOf(
                                "op" to FrameMutationOp.ACK_SETTLEMENT.wire,
                                "wire" to site.wire, "waitedMs" to site.waitedMs,
                                "visualFrameId" to h.visualFrameId))
                            inflight.clear()
                            return Outcome(delivered, duplicate, refused, retained,
                                           unreadable, exhausted)
                        }
                    }
                    inflight.clear()
                }
                permanent -> {
                    // A record-level refusal: these bytes can never succeed, and
                    // the metadata event's frame renders as a typed placeholder.
                    // `refused` IS A LOSS COUNT (these bytes are destroyed
                    // and the frame renders as a typed placeholder), so it may
                    // only be taken after the removal commits.
                    // STEP 6: guard read AND removal inside ONE exclusion.
                    // A MOVED HEAD STILL COUNTS `refused`, exactly as before:
                    // the short-circuit `&&` meant `removedOne` was never called
                    // and the old `if` was false, so the pass fell through to the
                    // count. That is preserved deliberately — the SERVER refused
                    // these bytes permanently, which is true whatever the local
                    // head is now.
                    val site = loss.withExclusion(FrameMutationOp.PERMANENT_REFUSAL_SETTLEMENT) {
                        if (headIdentity(queue) == identity) Settle.Removed(removedOne(queue))
                        else Settle.HeadMoved
                    }
                    if (site is Exclusion.Ran && site.value.let { it is Settle.Removed && !it.committed }) {
                        trace.log("frame_remove_not_committed", listOf(
                            "by" to "refused", "visualFrameId" to h.visualFrameId))
                        inflight.clear()
                        return Outcome(delivered, duplicate, refused, retained,
                                       unreadable, exhausted)
                    }
                    if (site is Exclusion.Refused) {
                        // Nothing ran, nothing mutated, no disclosure. No count
                        // either: the pass ends and the next one re-asks the
                        // server, which refuses the same bytes again.
                        trace.log("frame_exclusion_refused", listOf(
                            "op" to FrameMutationOp.PERMANENT_REFUSAL_SETTLEMENT.wire,
                            "wire" to site.wire, "waitedMs" to site.waitedMs,
                            "visualFrameId" to h.visualFrameId))
                        inflight.clear()
                        return Outcome(delivered, duplicate, refused, retained,
                                       unreadable, exhausted)
                    }
                    refused++; inflight.clear()
                    trace.log("frame_inflight_settled", listOf(
                        "by" to "refused", "visualFrameId" to h.visualFrameId,
                        "code" to (r.code ?: "")))
                }
                r.status in 200..299 -> {
                    // 2xx with NO valid identity-bound acknowledgement. NOT a
                    // settlement: retain the bytes exactly where they are and
                    // stop the pass, or the loop would re-post the same unchanged
                    // head immediately. The typed reason says WHICH check failed
                    // an absent ack and a mismatched one are different facts.
                    retained++
                    trace.log("frame_ack_invalid", listOf(
                        "status" to r.status, "reason" to (ackFail ?: "ack_absent"),
                        "visualFrameId" to h.visualFrameId))
                    return Outcome(delivered, duplicate, refused, retained, unreadable, exhausted)
                }
                else -> {
                    // Precondition or transport. Rotate APPEND-FIRST so the
                    // bytes are durably present twice before they are present
                    // once — a crash here duplicates, and the endpoint collapses
                    // a duplicate by contentHash.
                    // ENCODING STAYS OUTSIDE THE EXCLUSION. It is neither a
                    // guard read nor a mutation, and the lock is not held for
                    // work that does not need it.
                    val rotated = FrameRecord.encode(h.copy(attempts = d.attempts), bytes)
                    // STEP 6: the APPEND, the head guard read and the REMOVAL
                    // are one exclusion — three store calls, three per-call
                    // region locks, and without an outer hold a concurrent
                    // remover between the append and the removal would make this
                    // site delete a frame it did not rotate.
                    val site = loss.withExclusion(FrameMutationOp.ROTATION) {
                        val appended = try { queue.append(rotated) } catch (_: Throwable) { false }
                        when {
                            !appended -> Rotate.AppendFailed
                            headIdentity(queue) == identity -> Rotate.Rotated(removedOne(queue))
                            else -> Rotate.HeadMoved
                        }
                    }
                    when (site) {
                        is Exclusion.Ran -> when (val v = site.value) {
                            is Rotate.Rotated -> {
                                // `retained` is true either way — the frame IS retained.
                                // A failed removal here leaves the deliberate duplicate
                                // the append-first order exists to prefer, which the
                                // endpoint collapses by contentHash; the `seen` set ends
                                // the pass on the next encounter, so it cannot spin.
                                if (!v.committed) {
                                    trace.log("frame_remove_not_committed", listOf(
                                        "by" to "rotate", "visualFrameId" to h.visualFrameId))
                                }
                                retained++
                            }
                            Rotate.AppendFailed -> {
                                // Could not rotate: leave the frame at the head
                                // untouched. Retrying a head is bounded by attempts.
                                retained++
                                trace.log("frame_rotate_failed", listOf("visualFrameId" to h.visualFrameId))
                            }
                            // APPENDED BUT THE HEAD MOVED TAKES NO COUNT, exactly
                            // as before: neither old branch fired, because the
                            // frame's disposition is genuinely unknown from here.
                            Rotate.HeadMoved ->
                                trace.log("frame_head_moved", listOf("visualFrameId" to h.visualFrameId))
                        }
                        is Exclusion.Refused -> {
                            // Nothing ran: no append, no removal, no disclosure.
                            // The frame is exactly where it was, so it IS retained.
                            retained++
                            trace.log("frame_exclusion_refused", listOf(
                                "op" to FrameMutationOp.ROTATION.wire,
                                "wire" to site.wire, "waitedMs" to site.waitedMs,
                                "visualFrameId" to h.visualFrameId))
                            inflight.clear()
                            return Outcome(delivered, duplicate, refused, retained,
                                           unreadable, exhausted)
                        }
                    }
                    inflight.clear()
                    // A transport failure fails every following frame the same
                    // way; stop rather than spin the store.
                    if (r.status == 0) return Outcome(delivered, duplicate, refused, retained,
                                                      unreadable, exhausted)
                }
            }
        }
        return Outcome(delivered, duplicate, refused, retained, unreadable, exhausted)
    }

    /**
 * True ONLY when the head was actually removed.
 *
 * `Queue.removeFirst` has returned the number ACTUALLY removed since the
 * primitive was fixed — 0 on a failed durable rewrite, and (since Phase 0)
 * 0 on a refused untrustworthy read. Every one of this file's five removal
 * sites discarded it, so each inherited the lie the primitive no longer
 * tells. A throw is a non-removal too, not a crash to propagate out of a
 * drain.
 */
    private fun removedOne(queue: Queue): Boolean =
        try { queue.removeFirst(1) == 1 } catch (_: Throwable) { false }

    /** What a pass-level settlement site did. */
    private sealed class Settle {
        data class Removed(val committed: Boolean) : Settle()
        object HeadMoved : Settle()
    }

    /** What the rotation site did. */
    private sealed class Rotate {
        data class Rotated(val committed: Boolean) : Rotate()
        object AppendFailed : Rotate()
        object HeadMoved : Rotate()
    }

    /**
 * A DISCARD site: **PREPARE -> TYPED REMOVAL -> RESOLVE, inside ONE
 * exclusion, with the guard read inside that same exclusion.**
 *
 * Returns `null` when the head was removed AND the removal COMMITTED;
 * otherwise a typed token saying why nothing may be counted. The caller must
 * take its loss count ONLY on `null`.
 *
 * **ORDER IS THE SAFETY ARGUMENT, AND IT IS NOT NEGOTIABLE.** The durable
 * PENDING is committed BEFORE the physical removal, so a process dying in
 * the window leaves a record saying "a removal was owed for this cause" and
 * the next open reconciles it from the store's OWN witness. Removing first
 * and recording after is the window that loses a frame with nothing left on
 * disk to say it existed.
 *
 * **A REFUSED PREPARE REFUSES THE REMOVAL.** `prepare == false` means the
 * PENDING is not durable, so a removal could not be reconciled after a
 * crash — the physical record REMAINS. That is the ruled behaviour and it is
 * why the `when` reaches `removeFirstTyped` only on the `else` arm.
 *
 * **EXACTLY ONE REMOVAL ENTRY POINT, EXACTLY ONCE.** This body calls
 * [Queue.removeFirstTyped] and never [Queue.removeFirst]; the three
 * pass-level sites call [removedOne] (hence `removeFirst`) and never the
 * typed one. The two are INDEPENDENT destructive calls — `removeFirst(count)`
 * is `removeFirstDetailed(count).removed` — so calling both at one site, or
 * either twice, removes TWICE.
 *
 * **A THROW FROM THE REMOVAL DOES NOT RESOLVE.** If `removeFirstTyped`
 * throws we do not know whether the record is gone, and `resolve` would
 * record a removal outcome nobody observed. The PENDING is left exactly as
 * the crash path leaves it, for the reconciler to classify from the store.
 */
    private fun discardHead(
        queue: Queue,
        loss: FrameLossSink,
        op: FrameMutationOp,
        cause: FrameLossCause,
        sessionId: String?,
        expected: ByteArray,
        stillEligible: (ByteArray) -> Boolean,
    ): String? {
        val ex = loss.withExclusion(op) {
            // THE GUARD READ, INSIDE THE EXCLUSION THAT WILL MUTATE.
            val live = try { queue.readAll().firstOrNull() } catch (_: Throwable) { null }
            when {
                live == null -> "head_absent"
                !live.contentEquals(expected) -> "head_moved"
                !stillEligible(live) -> "head_not_eligible"
                !loss.prepare(cause, sessionId) -> "prepare_refused"
                else -> {
                    val removal =
                        try { queue.removeFirstTyped(1) } catch (_: Throwable) { null }
                    when (removal) {
                        null -> "removal_threw"
                        FrameRemoval.COMMITTED -> { loss.resolve(removal); null }
                        FrameRemoval.NOT_COMMITTED -> { loss.resolve(removal); "not_committed" }
                        FrameRemoval.INDETERMINATE_DIRECTORY_UNSYNCED -> {
                            // INDETERMINATE IS NOT A COUNT. The removal may or
                            // may not survive a crash, so the ledger records the
                            // DISCLOSURE and this site takes no loss count — the
                            // exact-count / durable-incomplete distinction, kept.
                            loss.resolve(removal); "indeterminate"
                        }
                    }
                }
            }
        }
        return when (ex) {
            is Exclusion.Ran -> ex.value
            // The block never ran: nothing was read, nothing prepared, nothing
            // removed, and NO disclosure is set. Only a refused `enqueue`
            // acquisition is G6-C.
            is Exclusion.Refused -> "exclusion_refused_${ex.wire}"
        }
    }

    private fun headIdentity(queue: Queue): String? {
        val head = try { queue.readAll().firstOrNull() } catch (_: Throwable) { null } ?: return null
        val (h, b) = FrameRecord.decode(head) ?: return null
        return "${h.sessionId}|${h.visualFrameId}|${h.contentHash}|${b.size}"
    }
}

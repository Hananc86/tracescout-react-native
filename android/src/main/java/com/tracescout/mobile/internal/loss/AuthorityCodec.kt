package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. `public` only because Kotlin `internal` is
 * compilation-module scoped.
 */

/**
 * The PLAINTEXT grammar of the retained attribution authority — format **1**
 * of a NEW format.
 *
 * THIS CODEC KNOWS NOTHING ABOUT ENCRYPTION, FILES OR FRAMING. It turns a
 * [LossRecord] or an [AuthoritySnapshot] into deterministic bytes and back. The
 * `:sdk` store seals every one of these byte strings in its own AES-GCM frame
 * under the Keystore boundary (D1); the pure module never sees a key. That
 * split is what lets every grammar rule be tested on the JVM with no crypto.
 *
 * FIXED FIELD ORDER, CLOSED KIND SET, VERSION FIRST. Every encoding begins
 * with the format version byte, so a reader handed bytes of another format
 * refuses BY VERSION with the version it found ("format_version_unsupported:3")
 * never by misparsing them, never by treating them as empty. An unknown kind byte is a typed decode FAILURE (F-3): a codec
 * that skips what it does not understand cannot tell a forward-compatible
 * field from a corrupted frame, and "skip and continue" over authoritative
 * loss state silently erases a transition.
 *
 * NO FIELD CAN CARRY CONTENT (F-7). Every field is an identifier, a
 * canonical vocabulary word, a number, a boolean or a digest hex. There is no
 * free-text field — not "sanitised", ABSENT — and no per-record digest of a
 * captured event (F-6, the capture specification).
 *
 * Layout (all integers big-endian):
 * record [ver:1][kind:1][fieldCount:1]( [len:4][utf8 bytes] )*
 * snapshot [ver:1][0x53 'S'][fieldCount:1]( [len:4][utf8 bytes] )*
 *
 * The old branch's TSV + CRC32 framing is DISCARDED (plaintext on disk exposed
 * family and order to a file-system reader); its grammar CONCEPTS — closed
 * kinds, fixed order, typed refusal, version refusal — are what survive.
 */
/**
 * THE SHAPE OF EVERY STRING FIELD THE AUTHORITY PERSISTS — validated at ENCODE
 * and again at DECODE (guards review, 2026-09-25).
 *
 * WHY AT ENCODE. The snapshot's entry list is `seq:intentId:family:phase:cause`
 * joined by `,` — a DELIMITED grammar. An intent id carrying `:` or `,` would
 * encode without complaint and then fail to DECODE on the next open as
 * `snapshot_entry_malformed`, i.e. a value accepted by the writer would make the
 * authority unreadable for the reader: a refusal the writer could have made
 * cheaply, made expensive and permanent instead. The codec's own doc says every
 * field is "an identifier, a canonical vocabulary word, a number, a boolean or
 * a digest hex"; this is that sentence as code, applied before any byte is
 * framed. Everything that reaches `frame` has passed it, so the delimited
 * grammar is unambiguous by construction rather than by the callers' habits.
 *
 * THE SHAPES ARE THE ONES THE PRODUCERS ALREADY EMIT, NOT TIGHTER: the
 * envelope session id (`ts_<ms>_<12 hex>`), the chunk id grammar the API
 * honours (`[A-Za-z0-9_-]{8,64}`), a lowercase-or-uppercase sha256, and the
 * facade's deterministic intent ids (`<eventId>-lost-<CAUSE>`,
 * `rec-<seq>-admitted`, `dis-<REASON>`). No shape admits whitespace, a quote,
 * a delimiter or a control character, so no field can carry content.
 */
public object AuthorityFieldShape {
    public const val MAX_IDENTIFIER: Int = 128
    private val IDENTIFIER: Regex = Regex("^[A-Za-z0-9_.\\-]{1,128}$")
    private val CHUNK_ID: Regex = Regex("^[A-Za-z0-9_\\-]{8,64}$")
    private val SHA256: Regex = Regex("^[0-9a-fA-F]{64}$")

    /** intent ids, session ids, marker ids, phase names: one token, no delimiter. */
    public fun identifier(field: String, value: String): String {
        if (!IDENTIFIER.matches(value)) throw LossFormatException("field_shape:$field")
        return value
    }

    /** The chunk id grammar the server honours; anything else is a derived hash there, never an identity. */
    public fun chunkId(field: String, value: String): String {
        if (!CHUNK_ID.matches(value)) throw LossFormatException("field_shape:$field")
        return value
    }

    public fun sha256(field: String, value: String): String {
        if (!SHA256.matches(value)) throw LossFormatException("field_shape:$field")
        return value
    }

    /** A canonical loss family — the vocabulary IS the shape. */
    public fun family(value: String): String {
        LossCounts.requireCanonical(value)
        return value
    }

    /** A [SequenceAuthority.Phase] name, as persisted in a snapshot entry. */
    public fun phase(value: String): String {
        if (SequenceAuthority.Phase.values().none { it.name == value }) throw LossFormatException("field_shape:phase")
        return value
    }
}

public object AuthorityCodec {

    /**
 * Format version. `3` was the old branch's plaintext TSV; `1` was F2's.
 *
 * **1 → 2 IS THE AUTHORITY-CONTAINER-V2 CLEAN BREAK.** The
 * plaintext grammar changes in the same transition as the container, the key
 * alias and the root directory, because shipping them as two successive
 * incompatible formats would mean two clean breaks, two refusal policies and
 * two migration arguments. **No fallback path exists**: a reader that fails
 * any step of the specification refuses; it never retries with different AAD, a different
 * key or a different version.
 */
    public const val FORMAT_VERSION: Int = 2

    private const val K_LOSS: Int = 1
    private const val K_HW: Int = 2
    private const val K_ADMIT: Int = 3
    private const val K_EVICT: Int = 4
    private const val K_FINAL: Int = 5
    private const val K_DELIVERED: Int = 6
    private const val K_DBATCH: Int = 7
    private const val K_ACK: Int = 8
    private const val K_TERMINAL: Int = 9
    private const val K_DISABLED: Int = 10

    /**
 * NAMED FOR THE FACT IT RECORDS. v3 of the design called
 * this `K_PENDING` with the codec name `"pending"`; the constant and the
 * persisted name now both say what the record IS. Because the kind is new in
 * this same v2 transition there is no compatibility cost — no reader has ever
 * seen either spelling.
 */
    private const val K_CHECKPOINT_REQUESTED: Int = 11

    /**
 * F4-P2: the owner-bound disable and the ONE
 * clear record. Two NEW kinds in the SAME v2 grammar — a reader that
 * predates them refuses `unknown_record_kind` (typed, F-3) rather than
 * skipping a transition it cannot read; a pre-P2 WAL carries neither and
 * replays unchanged.
 */
    private const val K_OWNED_DISABLE: Int = 12
    private const val K_OWNED_DISABLE_CLEARED: Int = 13

    private const val K_SNAPSHOT: Int = 0x53

    /** The v2 container's authenticated header frame at ordinal 0. ASCII 'H'. */
    private const val K_HEADER: Int = 0x48

    /** The v2 head-witness body frame. ASCII 'W'. */
    private const val K_WITNESS: Int = 0x57

    /**
 * The closed set, as a fact the tests can count instead of a literal they
 * transcribe. **11 entries in v2** (10 in v1), so the test that counts the
 * closed set rather than transcribing it keeps working.
 */
    public val RECORD_KINDS: List<String> = listOf(
        "loss", "hw", "admit", "evict", "final", "delivered", "dbatch", "ack", "terminal", "disabled",
        "checkpointRequested",
        // F4-P2 R11: 13 entries — the owner-bound disable and its clear.
        "ownedDisable", "ownedDisableCleared",
    )

    /** Bounded: a field longer than this is corruption, never a huge value. */
    public const val MAX_FIELD_BYTES: Int = 256 * 1024

    // ─────────────────────────────────────────────────────────────── encode

    // EVERY STRING FIELD PASSES `AuthorityFieldShape` BEFORE IT IS FRAMED —
    // and again after it is parsed. `id`/`fam`/`sid`/`chunk`/`sha` are the one
    // place a field's shape is stated, so encode and decode cannot drift apart.
    private fun id(field: String, v: String) = AuthorityFieldShape.identifier(field, v)
    private fun fam(v: String) = AuthorityFieldShape.family(v)
    private fun sid(v: String) = AuthorityFieldShape.identifier("sessionId", v)
    private fun chunk(v: String) = AuthorityFieldShape.chunkId("chunkId", v)
    private fun sha(v: String) = AuthorityFieldShape.sha256("contentSha256", v)

    public fun encodeRecord(r: LossRecord): ByteArray = when (r) {
        is LossRecord.LossRecorded ->
            frame(K_LOSS, id("intentId", r.intentId), r.logicalSequence.toString(), fam(r.family), r.count.toString())
        is LossRecord.HighWaterAdvanced ->
            frame(K_HW, id("intentId", r.intentId), r.highWater.toString())
        is LossRecord.AdmissionIntent ->
            frame(K_ADMIT, id("intentId", r.intentId), r.logicalSequence.toString(), fam(r.family))
        is LossRecord.EvictionIntent ->
            frame(K_EVICT, id("intentId", r.intentId), r.logicalSequence.toString(), fam(r.family), r.count.toString())
        is LossRecord.IntentFinalized -> {
            if ((r.outcome == LossRecord.IntentFinalized.Outcome.LOST) != (r.cause != null))
                throw LossFormatException("final_cause_mismatch")
            frame(K_FINAL, id("intentId", r.intentId), id("resolvesIntentId", r.resolvesIntentId), r.outcome.name, r.cause?.name ?: "")
        }
        is LossRecord.DeliveredMarker ->
            frame(K_DELIVERED, id("intentId", r.intentId), r.throughSequence.toString())
        is LossRecord.DeliveredBatch ->
            frame(K_DBATCH, id("intentId", r.intentId), sid(r.sessionId), chunk(r.chunkId), r.recordCount.toString(),
                sha(r.contentSha256), hex(r.sequenceEncoding))
        is LossRecord.AckApplied ->
            frame(K_ACK, id("intentId", r.intentId), r.throughSequence.toString(),
                LossCounts.encode(r.counts), r.acknowledgedTerminal.toString(),
                id("settlesCheckpointId", r.settlesCheckpointId))
        is LossRecord.TerminalRequested ->
            frame(K_TERMINAL, id("intentId", r.intentId))
        is LossRecord.CheckpointDisabled ->
            frame(K_DISABLED, id("intentId", r.intentId), r.reason.name)
        is LossRecord.OwnedDisable ->
            frame(K_OWNED_DISABLE, id("intentId", r.intentId), r.reason.name, id("ownerId", r.ownerId))
        is LossRecord.OwnedDisableCleared ->
            frame(K_OWNED_DISABLE_CLEARED, id("intentId", r.intentId), id("ownerId", r.ownerId))
        is LossRecord.CheckpointRequested ->
            frame(K_CHECKPOINT_REQUESTED, id("intentId", r.intentId), id("checkpointId", r.checkpointId),
                r.throughSequence.toString(), LossCounts.encode(r.frozenCounts), r.claimsTerminal.toString())
    }

    public fun decodeRecord(b: ByteArray): LossRecord {
        val (kind, f) = parse(b)
        fun s(i: Int): String = f.getOrNull(i) ?: throw LossFormatException("field_missing")
        fun n(i: Int): Long = s(i).toLongOrNull() ?: throw LossFormatException("field_not_numeric")
        return when (kind) {
            K_LOSS -> {
                require(f, 4); LossRecord.LossRecorded(id("intentId", s(0)), n(1), fam(s(2)), n(3))
            }
            K_HW -> { require(f, 2); LossRecord.HighWaterAdvanced(id("intentId", s(0)), n(1)) }
            K_ADMIT -> {
                require(f, 3); LossRecord.AdmissionIntent(id("intentId", s(0)), n(1), fam(s(2)))
            }
            K_EVICT -> {
                require(f, 4); LossRecord.EvictionIntent(id("intentId", s(0)), n(1), fam(s(2)), n(3))
            }
            K_FINAL -> {
                require(f, 4)
                val outcome = runCatching { LossRecord.IntentFinalized.Outcome.valueOf(s(2)) }
                    .getOrElse { throw LossFormatException("unknown_outcome") }
                val cause = if (s(3).isEmpty()) null else runCatching { LossCause.valueOf(s(3)) }
                    .getOrElse { throw LossFormatException("unknown_loss_cause") }
                if ((outcome == LossRecord.IntentFinalized.Outcome.LOST) != (cause != null))
                    throw LossFormatException("final_cause_mismatch")
                LossRecord.IntentFinalized(id("intentId", s(0)), id("resolvesIntentId", s(1)), outcome, cause)
            }
            K_DELIVERED -> { require(f, 2); LossRecord.DeliveredMarker(id("intentId", s(0)), n(1)) }
            K_DBATCH -> {
                require(f, 6)
                val count = s(3).toIntOrNull() ?: throw LossFormatException("dbatch_count")
                LossRecord.DeliveredBatch(id("intentId", s(0)), sid(s(1)), chunk(s(2)), count, sha(s(4)), unhex(s(5)))
            }
            K_ACK -> {
                // 5, NOT 4 — the checkpoint identity is REQUIRED.
                require(f, 5)
                LossRecord.AckApplied(id("intentId", s(0)), n(1), LossCounts.decode(s(2)),
                    s(3).toBooleanStrictOrNull() ?: throw LossFormatException("bad_bool"),
                    id("settlesCheckpointId", s(4)))
            }
            K_TERMINAL -> { require(f, 1); LossRecord.TerminalRequested(id("intentId", s(0))) }
            K_DISABLED -> {
                require(f, 2)
                LossRecord.CheckpointDisabled(id("intentId", s(0)), runCatching { DisableReason.valueOf(s(1)) }
                    .getOrElse { throw LossFormatException("unknown_disable_reason") })
            }
            K_OWNED_DISABLE -> {
                require(f, 3)
                LossRecord.OwnedDisable(id("intentId", s(0)), runCatching { DisableReason.valueOf(s(1)) }
                    .getOrElse { throw LossFormatException("unknown_disable_reason") }, id("ownerId", s(2)))
            }
            K_OWNED_DISABLE_CLEARED -> { require(f, 2); LossRecord.OwnedDisableCleared(id("intentId", s(0)), id("ownerId", s(1))) }
            K_CHECKPOINT_REQUESTED -> {
                require(f, 5)
                LossRecord.CheckpointRequested(id("intentId", s(0)), id("checkpointId", s(1)), n(2),
                    LossCounts.decode(s(3)),
                    s(4).toBooleanStrictOrNull() ?: throw LossFormatException("bad_bool"))
            }
            K_SNAPSHOT -> throw LossFormatException("snapshot_where_record_expected")
            K_HEADER -> throw LossFormatException("header_where_record_expected")
            K_WITNESS -> throw LossFormatException("witness_where_record_expected")
            // NOT a skip. An unknown kind in authoritative state is a failure,
            // because the alternative is erasing a transition we cannot read.
            else -> throw LossFormatException("unknown_record_kind:$kind")
        }
    }

    // ───────────────────────────────────────────────────────────── snapshot

    /**
 * The compacted baseline of one session's authority — everything a reopen
 * needs before replaying the WAL generation it names.
 *
 * `entries` carries the RETAINED owned sequences (`seq → intentId, family,
 * phase, cause`) — PREPARED, ADMITTED and LOST. DELIVERED sequences are
 * folded into `deliveredBatches` (the derived index is rebuilt on load,
 * never persisted beside it) and are not duplicated here.
 */
    public data class AuthoritySnapshot(
        val sessionId: String,
        val highWater: Long,
        val walGeneration: Long,
        val disabled: DisableReason?,
        /** seq -> (intentId, family, phase, cause) */
        val entries: List<EntryRow>,
        val deliveredBatches: List<DeliveredBatchSnapshot>,
        val ackThrough: Long,
        val ackCounts: Map<String, Long>,
        val terminalRequested: Boolean,
        val acknowledgedTerminal: Boolean,
        /**
 * THE DERIVED PENDING CHECKPOINT, projected so it survives compaction and
 * restart. Fields **11–14**; the snapshot is **exactly 14** fields
 * in v2 (10 in v1).
 *
 * THESE FOUR KEEP THEIR `pending*` NAMES DELIBERATELY. They are NOT
 * the record kind — they are the snapshot's projection of the DERIVED
 * pending state (a requested checkpoint with no settling ack), which is
 * exactly what "pending" correctly describes. Renaming them to
 * `checkpointRequested*` would make the snapshot claim to carry a RECORD
 * rather than a STATE, and the specification I10's second control is precisely that
 * mistake.
 *
 * v2 of the design said 15 fields; field 11 was `reservedThrough` and
 * it is GONE with the reservation.
 */
        val pending: PendingCheckpointRow? = null,
        /**
 * F4-P2 (R11): the OWNER of an owner-bound disable, or null for no disable /
 * a legacy monotonic one. NOT A FIFTEENTH WIRE FIELD — the snapshot stays
 * EXACTLY 14 fields. The owner is encoded INSIDE field 3 as
 * `REASON|owner` (`|` cannot occur in a reason name or an identifier-shaped
 * owner, so the split is unambiguous); a snapshot written before P2 carries a
 * bare reason and decodes to `disabledOwner = null` unchanged, and a reader
 * that predates P2 refuses an owned field typed (`unknown_disable_reason`)
 * rather than reading an owned disable as a legacy one.
 */
        val disabledOwner: String? = null,
    )

    /**
 * The four pending fields as ONE nullable row — so "all four or none of the
 * four" is a TYPE fact in memory and a CHECKED cross-field rule on the wire.
 *
 * `""` for `pendingCheckpointId` means absent — the convention `disabled`
 * already uses. A **cross-field all-or-none rule** is enforced on decode
 * (`snapshot_pending_checkpoint_partial`): a half-present pending checkpoint
 * is a shape no later check could distinguish from a real one, which is the
 * same argument `SequenceAuthority.fromSnapshot` already makes for
 * all-or-nothing loading.
 */
    public data class PendingCheckpointRow(
        val checkpointId: String,
        val throughSequence: Long,
        val frozenCounts: Map<String, Long>,
        val claimsTerminal: Boolean,
    )

    /** The absent sentinel for `pendingThroughSequence`, matching the genesis convention. */
    public const val PENDING_ABSENT_SEQUENCE: Long = -1L

    /** One retained owned sequence, as persisted. `phase` is the [SequenceAuthority.Phase] name. */
    public data class EntryRow(
        val sequence: Long,
        val intentId: String,
        val family: String,
        val phase: String,
        val cause: LossCause?,
    )

    public fun encodeSnapshot(s: AuthoritySnapshot): ByteArray {
        val entries = StringBuilder()
        for (e in s.entries.sortedBy { it.sequence }) {
            if (entries.isNotEmpty()) entries.append(',')
            // THE ONE DELIMITED GRAMMAR IN THE FORMAT. Each token is shape-checked
            // here, so `:` and `,` cannot occur inside one and the split on decode
            // is unambiguous — see AuthorityFieldShape.
            entries.append(e.sequence).append(':').append(id("entry.intentId", e.intentId)).append(':')
                .append(fam(e.family)).append(':').append(AuthorityFieldShape.phase(e.phase)).append(':').append(e.cause?.name ?: "")
        }
        val p = s.pending
        return frame(K_SNAPSHOT,
            sid(s.sessionId),
            s.highWater.toString(),
            s.walGeneration.toString(),
            encodeDisabled(s.disabled, s.disabledOwner),
            entries.toString(),
            encodeBatches(s.deliveredBatches),
            s.ackThrough.toString(),
            LossCounts.encode(s.ackCounts),
            s.terminalRequested.toString(),
            s.acknowledgedTerminal.toString(),
            // 11.14 — the derived pending checkpoint. ABSENT is the
            // neutral quadruple, never a partially-filled one.
            if (p == null) "" else id("pendingCheckpointId", p.checkpointId),
            (p?.throughSequence ?: PENDING_ABSENT_SEQUENCE).toString(),
            LossCounts.encode(p?.frozenCounts ?: LossCounts.zero()),
            (p?.claimsTerminal ?: false).toString(),
        )
    }

    public fun decodeSnapshot(b: ByteArray): AuthoritySnapshot {
        val (kind, f) = parse(b)
        if (kind != K_SNAPSHOT) throw LossFormatException("record_where_snapshot_expected")
        // EXACTLY 14. No optional tail, no "extra fields
        // ignored" — relaxing one `require` to `>=` is I6's negative control.
        require(f, 14)
        fun n(i: Int): Long = f[i].toLongOrNull() ?: throw LossFormatException("snapshot_field_not_numeric")
        val entries = ArrayList<EntryRow>()
        if (f[4].isNotEmpty()) for (part in f[4].split(',')) {
            val c = part.split(':')
            if (c.size != 5) throw LossFormatException("snapshot_entry_malformed")
            val seq = c[0].toLongOrNull() ?: throw LossFormatException("snapshot_entry_malformed")
            val cause = if (c[4].isEmpty()) null else runCatching { LossCause.valueOf(c[4]) }
                .getOrElse { throw LossFormatException("unknown_loss_cause") }
            entries.add(EntryRow(seq, id("entry.intentId", c[1]), fam(c[2]), AuthorityFieldShape.phase(c[3]), cause))
        }
        return AuthoritySnapshot(
            sessionId = sid(f[0]),
            highWater = n(1),
            walGeneration = n(2),
            disabled = decodeDisabled(f[3]).first,
            disabledOwner = decodeDisabled(f[3]).second,
            entries = entries,
            deliveredBatches = decodeBatches(f[5]),
            ackThrough = n(6),
            ackCounts = LossCounts.decode(f[7]),
            terminalRequested = f[8].toBooleanStrictOrNull() ?: throw LossFormatException("bad_bool"),
            acknowledgedTerminal = f[9].toBooleanStrictOrNull() ?: throw LossFormatException("bad_bool"),
            pending = decodePending(f[10], n(11), f[12], f[13]),
        )
    }

    /** Field 3: `""` / `REASON` (legacy, monotonic) / `REASON|owner` (F4-P2 owner-bound). */
    private fun encodeDisabled(reason: DisableReason?, owner: String?): String = when {
        reason == null -> { if (owner != null) throw LossFormatException("snapshot_owner_without_disable"); "" }
        owner == null -> reason.name
        else -> { if (!reason.ownerClearable()) throw LossFormatException("snapshot_owner_on_monotonic_reason"); reason.name + "|" + id("disabledOwner", owner) }
    }

    private fun decodeDisabled(field: String): Pair<DisableReason?, String?> {
        if (field.isEmpty()) return null to null
        val bar = field.indexOf('|')
        val name = if (bar < 0) field else field.substring(0, bar)
        val reason = runCatching { DisableReason.valueOf(name) }.getOrElse { throw LossFormatException("unknown_disable_reason") }
        if (bar < 0) return reason to null
        val owner = id("disabledOwner", field.substring(bar + 1))
        if (!reason.ownerClearable()) throw LossFormatException("snapshot_owner_on_monotonic_reason")
        return reason to owner
    }

    /**
 * THE ALL-OR-NONE CROSS-FIELD RULE for the four pending fields.
 *
 * A 14-field frame makes every field PHYSICALLY present, so "present"
 * has to mean MEANINGFUL. Absent is the exact neutral quadruple; present
 * requires an identity AND a sequence that exists. Anything between the two
 * is `snapshot_pending_checkpoint_partial` — because a half-present pending
 * checkpoint is a shape no later check could distinguish from a real one, and
 * accepting it would let a snapshot claim a checkpoint nobody requested or
 * silently drop one that was.
 */
    private fun decodePending(
        idField: String,
        through: Long,
        countsField: String,
        terminalField: String,
    ): PendingCheckpointRow? {
        val claims = terminalField.toBooleanStrictOrNull() ?: throw LossFormatException("bad_bool")
        val counts = LossCounts.decode(countsField)
        val countsAllZero = counts.values.all { it == 0L }
        if (idField.isEmpty()) {
            // ABSENT — every other pending field must be neutral.
            if (through != PENDING_ABSENT_SEQUENCE || !countsAllZero || claims)
                throw LossFormatException("snapshot_pending_checkpoint_partial")
            return null
        }
        if (through < 0L) throw LossFormatException("snapshot_pending_checkpoint_partial")
        return PendingCheckpointRow(id("pendingCheckpointId", idField), through, counts, claims)
    }

    // ──────────────────────────────────────────── v2 container header frame

    /**
 * THE AUTHENTICATED HEADER at ordinal 0 — **exactly 4 fields**.
 *
 * ```
 * fileRole name | sessionId (empty for role=floors) | generation | fileInstanceId hex
 * ```
 *
 * Grammar maximum: `3 + (4+8) + (4+128) + (4+19) + (4+32)` = **206 B**, so it
 * fits RUNG1 = 1024 with 814 B of margin.
 *
 * 4, not 5 — v2 of the design bound a `familySetDigest` here as well as in
 * AAD_B; the specification removes it, on an independent examination of the accepted
 * decoder rather than merely because a product decision was rejected.
 */
    public fun encodeHeader(h: AuthorityContainer.Header): ByteArray = frame(
        K_HEADER,
        h.role.label,
        // EMPTY IS LEGAL HERE, AND ONLY HERE, AND ONLY FOR `floors`.
        // `AuthorityContainer.checkHeader` is what turns that into a REQUIREMENT
        // per role (`floors_header_session_present` / `session_mismatch`); the
        // codec's job is the grammar, not the role policy.
        if (h.sessionId.isEmpty()) "" else sid(h.sessionId),
        h.generation.toString(),
        AuthorityFieldShape.identifier("fileInstanceIdHex", h.fileInstanceIdHex),
    )

    public fun decodeHeader(b: ByteArray): AuthorityContainer.Header {
        val (kind, f) = parse(b)
        if (kind != K_HEADER) throw LossFormatException("header_malformed:kind:$kind")
        if (f.size != 4) throw LossFormatException("header_malformed:field_count:${f.size}")
        val role = AuthorityContainer.Role.ofLabel(f[0])
            ?: throw LossFormatException("header_malformed:role:${f[0].take(16)}")
        val gen = f[2].toLongOrNull() ?: throw LossFormatException("header_malformed:generation")
        return AuthorityContainer.Header(
            role = role,
            sessionId = if (f[1].isEmpty()) "" else sid(f[1]),
            generation = gen,
            fileInstanceIdHex = AuthorityFieldShape.identifier("fileInstanceIdHex", f[3]),
        )
    }

    // ──────────────────────────────────────────────── v2 head witness frame

    /**
 * THE HEAD WITNESS body frame — **exactly 7 fields**.
 *
 * ```
 * sessionId | generation | walFrameCount | walTailDigest (sha256 hex)
 * | highWaterClaim | walFileInstanceId hex | snapshotFileInstanceId hex
 * ```
 *
 * Grammar maximum: `3 + (4+128) + (4+19) + (4+19) + (4+64) + (4+19) + (4+32)
 * + (4+32)` = **344 B**, so it fits RUNG1 = 1024 with 676 B of margin, and
 * the padded witness file is `32 + 1056 + 1056 = 2144 B` — the exact
 * `witnessBytes` configuration M2 measured.
 *
 * 7, not 6: v2 of the design carried `reservedThrough` (gone with the
 * reservation, a product decision) and did NOT carry the two file-instance bindings, which
 * are what name a same-generation SUBSTITUTION the specification cannot see (R15/R16).
 */
    public data class Witness(
        val sessionId: String,
        /** THE COMMIT POINT OF COMPACTION. The witness, not the filesystem, decides which generation is current. */
        val generation: Long,
        /**
 * `C` — LOG EXTENT AND NOTHING ELSE. Never read as a high-water, never as
 * a sequence, never as a reservation.
 */
        val walFrameCount: Long,
        /** `D` — the incremental chain over the sealed bytes as they lie on disk. */
        val walTailDigest: String,
        /**
 * The **FACTUAL** high-water as of that extent: what was issued and
 * admitted. NEVER derived from a file's extent or size.
 */
        val highWaterClaim: Long,
        val walFileInstanceIdHex: String,
        val snapshotFileInstanceIdHex: String,
    )

    public fun encodeWitness(w: Witness): ByteArray = frame(
        K_WITNESS,
        sid(w.sessionId),
        w.generation.toString(),
        w.walFrameCount.toString(),
        sha(w.walTailDigest),
        w.highWaterClaim.toString(),
        AuthorityFieldShape.identifier("walFileInstanceIdHex", w.walFileInstanceIdHex),
        AuthorityFieldShape.identifier("snapshotFileInstanceIdHex", w.snapshotFileInstanceIdHex),
    )

    public fun decodeWitness(b: ByteArray): Witness {
        val (kind, f) = parse(b)
        if (kind != K_WITNESS) throw LossFormatException("witness_malformed:kind:$kind")
        if (f.size != 7) throw LossFormatException("witness_malformed:field_count:${f.size}")
        fun n(i: Int): Long = f[i].toLongOrNull() ?: throw LossFormatException("witness_malformed:not_numeric:$i")
        return Witness(
            sessionId = sid(f[0]),
            generation = n(1),
            walFrameCount = n(2),
            walTailDigest = sha(f[3]),
            highWaterClaim = n(4),
            walFileInstanceIdHex = AuthorityFieldShape.identifier("walFileInstanceIdHex", f[5]),
            snapshotFileInstanceIdHex = AuthorityFieldShape.identifier("snapshotFileInstanceIdHex", f[6]),
        )
    }

    // ───────────────────────────────────────────────────── delivered batches
    //
    // LENGTH-PREFIXED SEGMENTS, NOT DELIMITED FIELDS (salvaged rule). A
    // separator scheme makes ("ab","c") and ("a","bc") indistinguishable, which
    // for a delivery authority means two batches collapsing into one.

    private fun seg(sb: StringBuilder, v: String) { sb.append(v.length).append(':').append(v) }

    public fun encodeBatches(batches: Collection<DeliveredBatchSnapshot>): String {
        val sb = StringBuilder()
        for (b in batches.sortedBy { it.markerId }) {
            if (sb.isNotEmpty()) sb.append(';')
            seg(sb, id("markerId", b.markerId)); seg(sb, sid(b.sessionId)); seg(sb, chunk(b.chunkId))
            seg(sb, b.recordCount.toString()); seg(sb, sha(b.contentSha256)); seg(sb, hex(b.sequenceEncoding))
        }
        return sb.toString()
    }

    public fun decodeBatches(raw: String): List<DeliveredBatchSnapshot> {
        val out = ArrayList<DeliveredBatchSnapshot>()
        if (raw.isEmpty()) return out
        val seen = HashSet<String>()
        for (rec in raw.split(';')) {
            var p = 0
            fun next(): String {
                val c = rec.indexOf(':', p)
                if (c < 0) throw LossFormatException("delivered_batch_segment_malformed")
                val n = rec.substring(p, c).toIntOrNull() ?: throw LossFormatException("delivered_batch_segment_length")
                if (n < 0 || c + 1 + n > rec.length) throw LossFormatException("delivered_batch_segment_truncated")
                p = c + 1 + n
                return rec.substring(c + 1, c + 1 + n)
            }
            val marker = next(); val session = next(); val chunk = next()
            val count = next().toIntOrNull() ?: throw LossFormatException("delivered_batch_count_malformed")
            val sha = next(); val encHex = next()
            if (p != rec.length) throw LossFormatException("delivered_batch_trailing")
            if (!seen.add(marker)) throw LossFormatException("delivered_batch_duplicate_marker")
            out.add(DeliveredBatchSnapshot(id("markerId", marker), sid(session), chunk(chunk), count, sha(sha), unhex(encHex)))
        }
        return out
    }

    // ───────────────────────────────────────────────────────────── framing

    private fun frame(kind: Int, vararg fields: String): ByteArray {
        if (fields.size > 255) throw LossFormatException("too_many_fields")
        val encoded = fields.map { it.toByteArray(Charsets.UTF_8) }
        for (e in encoded) if (e.size > MAX_FIELD_BYTES) throw LossFormatException("field_too_large")
        val total = 3 + encoded.sumOf { 4 + it.size }
        val out = ByteArray(total)
        out[0] = FORMAT_VERSION.toByte(); out[1] = kind.toByte(); out[2] = fields.size.toByte()
        var p = 3
        for (e in encoded) {
            out[p] = (e.size ushr 24).toByte(); out[p + 1] = (e.size ushr 16).toByte()
            out[p + 2] = (e.size ushr 8).toByte(); out[p + 3] = e.size.toByte()
            System.arraycopy(e, 0, out, p + 4, e.size); p += 4 + e.size
        }
        return out
    }

    private fun parse(b: ByteArray): Pair<Int, List<String>> {
        if (b.size < 3) throw LossFormatException("frame_truncated")
        val ver = b[0].toInt() and 0xff
        // A VERSION MISMATCH IS NOT CORRUPTION, and naming the version found
        // is what lets an operator tell an old format from a future one.
        if (ver != FORMAT_VERSION) throw LossFormatException("format_version_unsupported:$ver")
        val kind = b[1].toInt() and 0xff
        val count = b[2].toInt() and 0xff
        val fields = ArrayList<String>(count)
        var p = 3
        repeat(count) {
            if (p + 4 > b.size) throw LossFormatException("frame_truncated")
            val len = ((b[p].toInt() and 0xff) shl 24) or ((b[p + 1].toInt() and 0xff) shl 16) or
                ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)
            if (len < 0 || len > MAX_FIELD_BYTES || p + 4 + len > b.size) throw LossFormatException("frame_truncated")
            fields.add(String(b, p + 4, len, Charsets.UTF_8)); p += 4 + len
        }
        if (p != b.size) throw LossFormatException("frame_trailing_bytes")
        return kind to fields
    }

    private fun require(f: List<String>, n: Int) {
        if (f.size != n) throw LossFormatException("field_count_mismatch:${f.size}!=$n")
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun unhex(h: String): ByteArray {
        if (h.length % 2 != 0) throw LossFormatException("hex_malformed")
        return DeliveredBatchFields.hexToBytes(h)
    }
}

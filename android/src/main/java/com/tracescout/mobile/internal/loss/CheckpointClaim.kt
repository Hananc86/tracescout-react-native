package com.tracescout.mobile.internal.loss

import com.tracescout.rn.LossFamilies

/**
 * **THE ONE HOME OF A LOSS-CHECKPOINT CLAIM'S IDENTITY AND ITS RENDERED BYTES.**
 *
 * **WHY IT EXISTS, AND WHY IT IS IN CORE.** F6 (the delivery emitter) and
 * F7 Slice A (the metadata-only carrier's orchestrator) both need (a) a
 * content-addressed identity for the claim they are about to persist and (b) the
 * UTF-8 bytes of the document `PendingCheckpoint.wire` produced. Both were
 * PRIVATE to `F6CheckpointEmitter`. A second copy in the F7 orchestrator would
 * be two derivations of one fact — exactly the `treatment` / `privacy` shape
 * this programme has already paid for twice — so they are lifted here and F6
 * delegates to them.
 *
 * **CORE, NOT `:sdk`, AND THAT IS NOT A STYLE CHOICE.** [identity] is derived
 * over the CANONICAL family set in canonical order, so its correctness is a
 * property of `LossFamilies` — core's own vocabulary. `:sdk` additionally pins
 * that exactly TWO of its production files may name `com.tracescout.rn.
 * LossFamilies` (the facade and the F6 emitter), and *"a THIRD consumer is still
 * a finding"*; putting this in `:sdk` would have made the F7 orchestrator that
 * third consumer or forced the pin open. Here it consumes core's vocabulary
 * inside core, and `:sdk`'s consumer count is unchanged.
 *
 * **NEITHER FUNCTION DECIDES ANYTHING.** No arithmetic, no interval check, no
 * counts comparison, no terminal decision. [render] adds, removes and renames
 * nothing — the map's keys, order and value types are
 * `PendingCheckpoint.wire`'s, which is the ONE wire writer.
 */
public object CheckpointClaim {

    /**
 * The durable/local checkpoint identity (an explicit product decision the specification).
 *
 * **IT NEVER GOES ON THE WIRE.** `PendingCheckpoint.wire` emits
 * exactly five keys and `checkpointId` is not one of them; settlement binds
 * locally — `CheckpointAckBinder` takes `settlesCheckpointId` from the
 * DURABLE row. So the identity exists to bind an ack to the claim it
 * answers, and adding it to the document would be a wire-format change for
 * nothing.
 *
 * Content-addressed over the claim, so an identical re-request under an
 * identical claim is `SequenceAuthority`'s `AlreadyApplied` — one durable
 * record, no second WAL frame — rather than a second outstanding claim. Hex,
 * 32 chars, which satisfies `AuthorityFieldShape`'s chunk-id shape.
 *
 * LENGTH-PREFIXED PARTS. Concatenating the fields raw would let two
 * different claims hash the same way (`"a" + "bc"` vs `"ab" + "c"`); the
 * two-byte length prefix makes that unrepresentable.
 */
    @JvmStatic
    public fun identity(
        sessionId: String,
        through: Long,
        counts: Map<String, Long>,
        terminal: Boolean,
    ): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        fun part(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            md.update(byteArrayOf((b.size shr 8).toByte(), b.size.toByte()))
            md.update(b)
        }
        part(sessionId)
        part(through.toString())
        for (f in LossFamilies.ALL) part(f + "=" + (counts[f] ?: 0L))
        part(if (terminal) "1" else "0")
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    /**
 * Serialise the wire map the ONE wire writer produced.
 *
 * IT IS A RENDERER, NOT A SECOND WIRE WRITER: the map's keys, order and
 * value types are `PendingCheckpoint.wire`'s, and this adds, removes and
 * renames nothing. `counts` is rendered in the map's own iteration order
 * (`wire` hands back a `LinkedHashMap`), so the bytes are deterministic
 * and a retry is byte-identical to the first emission.
 */
    @JvmStatic
    public fun render(doc: Map<String, Any>): String {
        val sb = StringBuilder(256)
        sb.append('{')
        var first = true
        for ((k, v) in doc) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(k).append("\":")
            when (v) {
                is String -> sb.append('"').append(v).append('"')
                is Boolean -> sb.append(if (v) "true" else "false")
                is Int, is Long -> sb.append(v.toString())
                is Map<*, *> -> {
                    sb.append('{')
                    var f2 = true
                    for ((ck, cv) in v) {
                        if (!f2) sb.append(',')
                        f2 = false
                        sb.append('"').append(ck).append("\":").append(cv.toString())
                    }
                    sb.append('}')
                }
                else -> sb.append('"').append(v.toString()).append('"')
            }
        }
        sb.append('}')
        return sb.toString()
    }
}

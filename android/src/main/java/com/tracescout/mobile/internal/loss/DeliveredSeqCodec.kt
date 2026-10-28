package com.tracescout.mobile.internal.loss

/*
 * INTERNAL IMPLEMENTATION CONTRACT of the TraceScout mobile SDK — owned by
 * `mobile-loss-core`. NOT a supported public surface; NO compatibility
 * guarantee across versions. It is `public` only because Kotlin `internal` is
 * compilation-module scoped and every consumer (`:sdk`) lives across the
 * module boundary. a conformance test pins that no type
 * here ever appears in a public API signature of `:sdk` / `:rn` / rn-core.
 */
/**
 * The canonical encoding of an EXACT ORDERED SEQUENCE SET.
 *
 * First value absolute, then strictly positive deltas, each an unsigned LEB128
 * varint.
 *
 * THIS IS A SET, NOT A RANGE, AND THE DIFFERENCE IS THE WHOLE POINT. The
 * global sequence space is shared: a drop ticket and an evicted record each own
 * a sequence and have NO EventStore frame. Storing `first.last` and expanding
 * it on read would convert such a sequence from "lost" to "delivered" purely
 * because it lies numerically inside a range. Here a gap is REPRESENTED — it
 * appears as a delta greater than 1 — so nothing can be swallowed by
 * arithmetic.
 *
 * WHY DELTAS AT ALL, GIVEN THAT. Not to imply contiguity — to bound the
 * SIZE. One atomic `DeliveredBatch` must fit a single WAL frame
 * (`MAX_RECORD_BYTES`), and the maximum reachable batch is thousands of
 * records. A decimal list of absolute values would not fit; deltas make the
 * cost a function of the record COUNT rather than of the sequence magnitudes,
 * while every value is still stored rather than inferred.
 *
 * Every malformed input is a refusal, never a best guess: an encoder that
 * repairs its input silently is how a delivery record comes to describe a set
 * nobody submitted.
 */
public object DeliveredSeqCodec {

    /** Encode a strictly increasing, non-negative sequence list. */
    public fun encode(sequences: List<Long>): ByteArray {
        if (sequences.isEmpty()) throw LossFormatException("delivered_seq_empty")
        if (sequences[0] < 0) throw LossFormatException("delivered_seq_negative")
        val out = java.io.ByteArrayOutputStream()
        // count first, so a decoder can refuse a truncated body rather than
        // returning the prefix it happened to parse.
        writeVarint(out, sequences.size.toLong())
        writeVarint(out, sequences[0])
        for (i in 1 until sequences.size) {
            val d = sequences[i] - sequences[i - 1]
            // delta 0 is a DUPLICATE and a negative delta is disorder. Both
            // are refused rather than normalised: a set that silently loses a
            // duplicate would report a smaller batch than the one submitted.
            if (d <= 0) throw LossFormatException("delivered_seq_not_increasing")
            writeVarint(out, d)
        }
        return out.toByteArray()
    }

    /**
 * Decode, and refuse anything that is not exactly what [encode] produces.
 *
 * @param expectedCount the record count carried independently by the
 * DeliveredBatch. Checking the decoded count against it is what makes a
 * truncated or padded body detectable at all.
 */
    public fun decode(bytes: ByteArray, expectedCount: Int): List<Long> {
        val r = Reader(bytes)
        val n = r.varint("count")
        if (n <= 0) throw LossFormatException("delivered_seq_count_invalid")
        if (n > Int.MAX_VALUE.toLong()) throw LossFormatException("delivered_seq_count_overflow")
        if (n.toInt() != expectedCount)
            throw LossFormatException("delivered_seq_count_mismatch:${n.toInt()}!=$expectedCount")
        val out = ArrayList<Long>(n.toInt())
        var cur = r.varint("first")
        if (cur < 0) throw LossFormatException("delivered_seq_negative")
        out.add(cur)
        for (i in 1 until n.toInt()) {
            val d = r.varint("delta")
            if (d <= 0) throw LossFormatException("delivered_seq_not_increasing")
            // Overflow is a refusal, not a wrap. A wrapped value would be a
            // perfectly well-formed number describing a sequence nobody owns.
            val next = cur + d
            if (next < cur) throw LossFormatException("delivered_seq_overflow")
            cur = next
            out.add(cur)
        }
        // TRAILING BYTES ARE A REFUSAL. A body that decodes cleanly and then
        // continues is not the body we wrote, and accepting its prefix would
        // mean accepting an unknown writer's framing.
        if (!r.atEnd()) throw LossFormatException("delivered_seq_trailing_bytes")
        return out
    }

    /** The exact bytes [encode] would produce — for size gates, without a copy. */
    public fun encodedSize(sequences: List<Long>): Int = encode(sequences).size

    private fun writeVarint(out: java.io.ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x == 0L) { out.write(b); return }
            out.write(b or 0x80)
        }
    }

    private class Reader(private val b: ByteArray) {
        private var p = 0
        fun atEnd() = p >= b.size
        fun varint(what: String): Long {
            var shift = 0
            var acc = 0L
            var read = 0
            while (true) {
                if (p >= b.size) throw LossFormatException("delivered_seq_truncated:$what")
                val c = b[p++].toInt() and 0xFF
                read++
                // 64 bits / 7 bits per byte = 10 bytes maximum.
                if (shift > 63) throw LossFormatException("delivered_seq_varint_overflow:$what")
                acc = acc or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) {
                    // NON-CANONICAL FORMS ARE REFUSED. `0x80 0x00` decodes to
                    // 0 just as `0x00` does, so two different byte strings would
                    // hash to two marker ids for one logical set. A codec that
                    // accepts both cannot be used for identity.
                    if (read > 1 && c == 0) throw LossFormatException("delivered_seq_varint_overlong:$what")
                    return acc
                }
                shift += 7
            }
        }
    }
}

package com.tracescout.mobile.queue

import android.content.Context
import java.io.File

/**
 * A DURABLE, sanitized trace of the recovery/upload decision path.
 *
 * WHY A FILE. "No HTTP request reached the API" is compatible with a dozen
 * different early returns, and every in-memory way of telling them apart has
 * already failed here: `am instrument` runs in a fresh process, so process
 * statics describe the instrumentation run rather than the process that did
 * the work. Only file-backed state crosses the process boundary — the queue
 * proved that, and this gives the DECISION PATH the same property.
 *
 * WHAT IT MAY RECORD. Checkpoint names and bounded metadata: counts,
 * sequences, typed codes, generation ids, a batch identity HASH. Never a
 * replay body, node, text, sentinel, key, header or full URL — the writer
 * takes only ints, booleans and short enum-like strings, so there is no
 * parameter through which content could arrive.
 *
 * BOUNDED. The file is a simple append log truncated at [MAX_BYTES] by
 * dropping the OLDEST half; a diagnostic that can grow without bound in a
 * customer's app is a defect of its own.
 */
object RecoveryTrace {

    private const val FILE = "tracescout-recovery-trace.log"
    private const val MAX_BYTES = 64 * 1024L

    @Volatile
    private var dir: File? = null

    /** Wired once at start; a trace before start has nowhere to live. */
    @JvmStatic
    fun install(ctx: Context) { dir = ctx.filesDir }

    /**
 * One checkpoint. Values are FILTERED, not trusted: anything longer than
 * 64 chars or containing a quote is dropped, so a caller cannot smuggle
 * content through a "code" even by accident.
 */
    @JvmStatic
    fun log(event: String, vararg fields: Pair<String, Any?>) {
        val d = dir ?: return
        try {
            val sb = StringBuilder()
            sb.append("{\"ts\":").append(System.currentTimeMillis())
            // A pid read that throws (no Android runtime, as on the JVM) must not
            // silently drop the LINE: the pid is context, the event is the fact.
            sb.append(",\"pid\":").append(try { android.os.Process.myPid() } catch (_: Throwable) { -1 })
            sb.append(render(event, fields))
            sb.append("}\n")
            val f = File(d, FILE)
            if (f.length() > MAX_BYTES) {
                // Keep the NEWEST half. The interesting checkpoint is almost
                // always the most recent startup, and a rotate that kept the
                // oldest half would preserve exactly the runs nobody is
                // debugging any more.
                val tail = f.readBytes().let { it.copyOfRange(it.size / 2, it.size) }
                f.writeBytes(tail)
            }
            f.appendBytes(sb.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Throwable) {
            // A diagnostic must never take the SDK down.
        }
    }

    /**
 * THE PRIVACY BOUNDARY OF ONE CHECKPOINT, AS A PURE FUNCTION — the part of a
 * trace line between the process stamp and the closing brace (guards review,
 * 2026-09-25: "RecoveryTrace privacy becomes tested").
 *
 * Everything the header above promises is decided HERE and nowhere else:
 * only `Int`/`Long`/`Boolean`/`String` values are rendered (any other type is
 * DROPPED, never `toString`ed — a `ByteArray`, a record, a map or an
 * exception cannot reach the file through this parameter); every key and
 * every string value passes [clean], so no value can close the JSON string,
 * inject a key, carry a newline, a quote, a backslash, a control character
 * or a space, or exceed 64 characters. `log` needs a Context for the file;
 * this needs nothing, which is what lets `RecoveryTracePrivacyTest` drive the
 * real boundary on the JVM instead of asserting it from a comment.
 */
    @JvmStatic
    internal fun render(event: String, fields: Array<out Pair<String, Any?>>): String {
        val sb = StringBuilder()
        sb.append(",\"event\":\"").append(clean(event)).append('"')
        for ((k, v) in fields) {
            val key = clean(k)
            if (key.isEmpty()) continue
            when (v) {
                null -> {}
                is Int, is Long, is Boolean -> sb.append(",\"").append(key).append("\":").append(v)
                is String -> sb.append(",\"").append(key).append("\":\"").append(clean(v)).append('"')
                else -> {}
            }
        }
        return sb.toString()
    }

    /**
 * `/` IS PERMITTED, AND THAT IS A DELIBERATE CORRECTION.
 *
 * The Phase-3 network checkpoint records a NORMALIZED route template
 * (`/accounts/:id`). Stripping its separators produced `accounts:id` — a
 * value that is not what was recorded and not what a reader would
 * reconstruct, i.e. a diagnostic that silently mangles the fact it claims
 * to hold. Either record it faithfully or do not record it.
 *
 * This does not weaken the guarantee, because the guarantee was never in
 * this filter. A raw path passed here would ALREADY survive it in part
 * (letters, digits, dots and dashes all pass today), so the real protection
 * is that every call site passes an already-normalized or already-typed
 * value — which is why the checkpoint's field list is documented at the
 * call site rather than trusted here. What this filter owns is that no
 * value can terminate the JSON string, inject a key, or exceed the bound:
 * quotes, backslashes, control characters and newlines remain excluded,
 * and `/` can do none of those things.
 */
    /**
 * ASCII ONLY (guards review, 2026-09-25). The previous filter used
 * `isLetterOrDigit`, which is Unicode-aware — so a sentence in a script
 * that does not separate words with spaces (CJK, Thai) passed through WHOLE
 * up to the bound, and Latin text lost only its spaces. A code is ASCII by
 * construction; a value that needs any other character is not a code and
 * has no business here.
 */
    private fun clean(s: String): String =
        s.take(64).filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_-.:/" }
}

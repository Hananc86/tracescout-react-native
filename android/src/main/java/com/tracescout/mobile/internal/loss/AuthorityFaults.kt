package com.tracescout.mobile.internal.loss

/**
 * THE FAULT SEAM the acceptance matrix's **F** column needs — INERT BY DEFAULT.
 *
 * **SEVEN V6 ROWS ARE UNREACHABLE WITHOUT ONE, AND THAT IS WHY THIS EXISTS.** the specification
 * E1/E2/E4/E5 need a fault between the snapshot install and the read-back, or between the
 * witness advance and the retirement; S14 needs the bootstrap interrupted at each of
 * G1…G5; F12 needs the three IO failures `NOT_COMMITTED_PROVEN_UNCHANGED` is defined
 * against. None of them is producible from outside the process — an external `kill` cannot
 * be aimed between two adjacent statements — so without a seam those rows would be
 * asserted by reading the code, which is exactly what the F column exists to improve on.
 *
 * ## The safety rules, each asserted by `AuthorityFaultSeamTest`
 *
 * 1. **[hook] defaults to `null`, and a null hook costs one volatile read.** The
 * production path is byte-identical with the seam unset — there is no flag to
 * misconfigure and no behaviour to enable.
 * 2. **Nothing in production sets it.** A source scan pins the only assignments to test
 * sources, so a shipped build cannot arm it.
 * 3. **The hook decides what a fault IS, not this object.** A hook may throw
 * [SimulatedFault] (an abort, for the rows that need one), throw an `IOException` (for
 * F12's three IO failures, which the primitive must then handle as it would a real
 * one), or perform a side effect such as corrupting the file just written (for E1/E2,
 * where nothing needs to throw at all). Keeping the decision in the hook is what stops
 * this file accumulating fault-specific behaviour.
 * 4. **[SimulatedFault] is an `Error`, and every broad `catch (t: Throwable)` on an
 * authority mutation path RETHROWS it.** A catch that swallowed it would make every
 * crash test pass by never crashing — the exact trap the F2 crash-seam work recorded.
 */
internal object AuthorityFaults {

    /**
 * Where a fault may be injected. Each name is the the specification row that needs it, so a point
 * with no row is a point nobody asked for.
 */
    enum class Point {
        /** the specification S14 — the specification G2: the genesis snapshot is installed. */
        GENESIS_AFTER_SNAPSHOT,
        /** the specification S14 — the specification G3: the genesis snapshot has been read back and verified. */
        GENESIS_AFTER_VERIFY,
        /** the specification S14 — the specification G4: the empty genesis WAL exists. */
        GENESIS_AFTER_WAL,
        /** the specification S14 — the specification G5: the bootstrap witness has committed. */
        GENESIS_AFTER_WITNESS,
        /** the specification E1/E2/E5 — the specification step 2: `snapshot-<g+1>` is on disk, not yet read back. */
        COMPACT_AFTER_SNAPSHOT_INSTALL,
        /** the specification E4 — the specification step 5: the witness names `g+1`; `g` is not yet retired. */
        COMPACT_AFTER_WITNESS_ADVANCE,
        /**
 * The ENTRY to the restoration path — a failing `write`.
 *
 * NOT one of F12's three rows. It exists because `restoreDurably` is only
 * reachable when the append itself fails, so without it the three rows below
 * have no way in; a test that could not reach them would report them green.
 */
        WRITE_AT_APPEND_WRITE,
        /** the specification F12 — the data `setLength` that shortens a partial append. */
        WRITE_AT_SETLENGTH,
        /** the specification F12 — the `fd.sync` that makes the RESTORATION durable. */
        WRITE_AT_RESTORE_SYNC,
        /** the specification F12 — the `fd.sync` that makes the DATA durable. */
        WRITE_AT_DATA_SYNC,
    }

    /** Thrown by a hook that models a process death. An `Error`, and never swallowed. */
    class SimulatedFault(val point: Point) : Error("simulated fault at $point")

    /** `null` = inert. Set ONLY from tests (pinned). */
    @Volatile
    @JvmField
    var hook: ((Point) -> Unit)? = null

    /** One volatile read on the production path, and nothing else. */
    fun at(point: Point) {
        hook?.invoke(point)
    }

    /** Restore the inert default. Tests call this in a `finally`. */
    fun clear() {
        hook = null
    }
}

package com.tracescout.mobile.queue

/**
 * THE ONE HOME OF THE LOCK-REFUSAL VOCABULARY.
 *
 * Two layers can refuse an acquisition — [QueueRegionLock] (this process's
 * threads) and [QueueFileFence] (other processes) — and the ruling that
 * produced the two-layer order forbids folding them into one reason. So every
 * reader needs the same two answers, and the layer prefix must be spelled in
 * exactly one place:
 *
 * * **which layer refused, and why** — the layer-prefixed wire token;
 * * **how long the caller waited** before the refusal.
 *
 * ## THE DEFECT THIS EXISTS FOR, AND WHY IT IS NOT A NEW HOME BESIDE AN OLD ONE
 *
 * Before this file the derivation was written THREE times:
 *
 * ```
 * QueueRefusal.of(QueueRegionLock.Unavailable) "region_${u.reason.wire}" (QueueHome.kt)
 * QueueRefusal.of(QueueFileFence.Unavailable) "fence_${u.reason.wire}" (QueueHome.kt)
 * EncryptedEventStore.refusalOf(t) a private `when`, both arms
 * EncryptedEventStore.waitedMsOf(t) a second private `when`
 * ```
 *
 * [QueueRefusal] is a real, already-shipped home for the *typed* question — it
 * names the layer and the region and cannot fail, because overload dispatch
 * makes it exhaustive. Adding a fourth spelling here and calling it "the one
 * home" would have been the very duplication the control forbids. So this
 * object owns the **token derivation**, and [QueueRefusal] now asks it:
 *
 * * [regionWire] / [fenceWire] are the ONLY places the `region_` / `fence_`
 * prefixes are written;
 * * [wireOf] / [waitedMsOf] answer the *untyped* question a caught `Throwable`
 * poses, and are the ONLY `when` over the two `Unavailable` types.
 *
 * A second `when` over those types, anywhere in the shipped sources, is the
 * defect — not a convenience. `QueueLockRefusalTest` asserts this file is the
 * only place that branches on both, and the dedicated control duplicates it to
 * prove the pin fires.
 *
 * **`internal`.** It is vocabulary for the queue package's own composition
 * sites, not API. A caller outside this module asks [QueueDomainRegion], which
 * answers with a typed refusal that already carries the token.
 *
 * **IT NEVER THROWS AND NEVER SWALLOWS.** [wireOf] answers `null` for a
 * throwable that is not a lock refusal, and `null` means **RETHROW** — a
 * vocabulary that mapped an unrelated `Throwable` to a token would convert a
 * real fault into an accounted refusal.
 */
internal object QueueLockRefusal {

    /** The ONE place the region layer's prefix is written. */
    fun regionWire(reason: QueueRegionLock.Reason): String = "region_${reason.wire}"

    /** The ONE place the fence layer's prefix is written. */
    fun fenceWire(reason: QueueFileFence.Reason): String = "fence_${reason.wire}"

    /**
 * The layer-prefixed refusal token, or `null` when [t] is not a lock
 * refusal at all.
 *
 * `null` means **rethrow**. It does not mean "no reason".
 */
    fun wireOf(t: Throwable): String? = when (t) {
        is QueueRegionLock.Unavailable -> regionWire(t.reason)
        is QueueFileFence.Unavailable -> fenceWire(t.reason)
        else -> null
    }

    /**
 * How long the refused caller waited, in milliseconds.
 *
 * `0L` for a throwable that is not a lock refusal — a value no caller may
 * read without first establishing [wireOf] is non-null.
 */
    fun waitedMsOf(t: Throwable): Long = when (t) {
        is QueueRegionLock.Unavailable -> t.waitedMs
        is QueueFileFence.Unavailable -> t.waitedMs
        else -> 0L
    }
}

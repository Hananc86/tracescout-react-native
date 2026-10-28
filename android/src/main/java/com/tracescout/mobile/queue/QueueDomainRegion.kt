package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import java.io.File

/**
 * THE GENERIC TWO-LAYER COMPOSITION, ANSWERING A TYPED REFUSAL.
 *
 * `QueueRegionLock` then, INSIDE it, `QueueFileFence` — the one order
 * [QueueRegion] derives — over the one domain [QueueDomain.pathFor] derives,
 * for a caller that is NOT an [EncryptedEventStore].
 *
 * ## Why a second composition site exists, deliberately
 *
 * `EncryptedEventStore.ordered` composes the same two layers for the store's
 * own mutators, and `QueueOrderTest.A2` pins it as the ONE acquisition site
 * **in that file** — a pin this class must not disturb and does not: A2's
 * `withRegion`/`withFence` counts are scoped to the store's own source.
 *
 * **AND THE STORE MUST NOT BE MADE TO DELEGATE HERE.** Folding the two into
 * one home looks like tidying and is forbidden: `A2` requires each destructive
 * public mutator's whole body to BE the store's own ordered acquisition, so a
 * store that delegated outward would satisfy neither the pin nor the reason for
 * it. The dedicated control performs exactly that delegation and requires
 * `QueueOrderTest.A2` to FAIL.
 *
 * ## What this composition is for
 *
 * A caller that must **account** a refusal rather than propagate it, over a
 * domain it does not own a store for. It answers [Held] — never a throw — so a
 * refusal is a value the caller has to handle, and every refusal is TRACED, so
 * an operator can see which layer refused and after how long.
 *
 * **A NON-LOCK THROWABLE PROPAGATES UNCHANGED.** Only the two `Unavailable`
 * types are refusals; anything else is a real fault and converting it into a
 * typed refusal would hide it. [QueueLockRefusal.wireOf] answering `null` is
 * what makes that distinction, and it is the ONE place it is made.
 *
 * ## Exclusion is a property of the DOMAIN, not of the file
 *
 * Both layers key on [QueueDomain.pathFor], which is the install directory's
 * lock file. So `TEARDOWN` taken for the SDK queue and `FRAME_BRACKET` taken
 * for the RN frame store resolve to the SAME domain and genuinely exclude each
 * other — a teardown cannot run while a frame bracket holds the install, and
 * neither can observe the other half-done. `QueueDomainRegionTest` asserts
 * that both by `pathFor` equality and behaviourally, because equality alone
 * would still pass if the composition keyed on something else.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class QueueDomainRegion private constructor(
    private val domainPath: String,
    private val regionLock: QueueRegionLock,
    private val fileFence: QueueFileFence,
) {

    /**
 * Ran, or refused by a named layer.
 *
 * [Refused.cause] is RETAINED on purpose. A reason token says which
 * layer and why; it cannot carry the stack that explains how the refusal
 * arrived, and a caller that wants to trace one has nowhere else to get it.
 * The dedicated control drops it and requires the pin to fire.
 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Held<out T> {
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        data class Ran<out T>(val value: T) : Held<T>()

        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        data class Refused(
            val wire: String,
            val waitedMs: Long,
            val cause: Throwable,
        ) : Held<Nothing>()
    }

    /** The domain both layers key on — the install directory's lock file. */
    fun domainPath(): String = domainPath

    /**
 * Take [region] on this domain and run [block] inside BOTH layers.
 *
 * Region first, fence inside it, exactly as [QueueRegion] documents: a
 * reversed order would create a lock-order question this design does not
 * have.
 */
    fun <T> withDomain(region: QueueRegion, block: () -> T): Held<T> = try {
        Held.Ran(regionLock.withRegion(region) { fileFence.withFence(region, block) })
    } catch (t: Throwable) {
        // `null` is NOT a refusal — it is a real fault, and it must leave by
        // the same door it arrived through.
        val wire = QueueLockRefusal.wireOf(t) ?: throw t
        val waited = QueueLockRefusal.waitedMsOf(t)
        RecoveryTrace.log(
            "queue_domain_refused",
            "region" to region.name, "why" to wire, "waitedMs" to waited,
        )
        Held.Refused(wire, waited, t)
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /**
 * The composition for the domain containing [storeFile].
 *
 * Both layers are built from the SAME canonical store file, so the
 * domain cannot differ between them. Keying the region lock per file
 * while the fence keys per directory is the defect [QueueDomain]
 * documents, and taking one path here is what makes it unrepresentable.
 */
        @JvmStatic
        fun forStoreFile(storeFile: File): QueueDomainRegion = QueueDomainRegion(
            QueueDomain.pathFor(storeFile),
            QueueRegionLock.forStoreFile(storeFile),
            QueueFileFence.forStoreFile(storeFile),
        )
    }
}

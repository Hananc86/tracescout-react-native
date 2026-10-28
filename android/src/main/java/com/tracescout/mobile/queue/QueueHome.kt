package com.tracescout.mobile.queue

import androidx.annotation.RestrictTo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The one home of a logical queue: its canonical store, and the two lock layers
 * that govern every destructive operation on it.
 *
 * ## The hole this closes
 *
 * `DurableUploadPipeline` held only a `RecordStore` — an interface with no lock
 * on it — so it could not open an ordered region around any of its five
 * check-then-mutate sequences (read the head, then conditionally remove it).
 * Each of those is a park move, a quarantine, a dequeue or a reconcile, i.e.
 * exactly the operations that must not interleave with an eviction bracket.
 * Handing the pipeline a `QueueHome` instead makes the region reachable, and
 * makes it reachable *only* through [withQueue].
 *
 * ## One identity, production and JVM venue
 *
 * [of] is memoized on the **canonical queue file path**, so two constructions
 * over one logical queue return the SAME home — the same locks, and later the
 * same protection state. A test that wants isolation uses a genuinely different
 * file, which is a different queue and should be.
 *
 * **THE HOME REGISTRY IS NOT THE LOCK REGISTRY.** [forgetForTest] clears
 * homes; it deliberately cannot clear a [QueueRegionLock] or a
 * [QueueFileFence]. Handing out a second lock object for a domain some live
 * store is still serialising on would serialise nothing — and the failure would
 * appear only under concurrency, in a venue that had "reset cleanly". The three
 * registries are separate so that is unreachable rather than merely unintended.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class QueueHome private constructor(
    /** The canonical queue file. The home's identity. */
    val queueFile: File,
    private val store: EncryptedEventStore,
    private val regionLock: QueueRegionLock,
    private val fileFence: QueueFileFence,
) {

    /**
 * A view of the queue that only exists INSIDE a region.
 *
 * `internal` IS KOTLIN MODULE SCOPE, NOT "PRIVATE TO [QueueHome]" — an
 * earlier version of this comment said the latter and a review corrected
 * it. The whole of `:sdk`, INCLUDING its test source set, can construct
 * one. What that costs is bounded and worth stating: every member here
 * delegates to the public ordered wrappers, so a smuggled instance still
 * takes both layers for each individual operation; what is lost is
 * multi-step REGION atomicity, which is the reason to obtain one from
 * [withQueue] rather than by hand. `QueueOrderTest` pins the single
 * construction site instead of relying on the visibility.
 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    class FencedQueue internal constructor(private val store: EncryptedEventStore) {
        /**
 * The queue image, read INSIDE the region — so a decision taken on it
 * and the mutation that follows see the same image.
 */
        fun read(): EncryptedEventStore.ReadResult = store.readAll()

        fun records(): List<ByteArray> = store.readAll().records

        fun append(plaintext: ByteArray): EncryptedEventStore.AppendResult =
            store.append(plaintext)

        /** The typed removal, so a refusal's reason survives the call. */
        fun remove(count: Int): EncryptedEventStore.RemoveResult =
            store.removeFirstDetailed(count)

        fun removeFirst(count: Int): Int = store.removeFirst(count)

        fun clear(): EncryptedEventStore.ClearResult = store.clear()

        // THERE IS DELIBERATELY NO `compactAndAppend` HERE, AND THE STAGE-LEAK
        // SCANNER IS WHY THE FIRST VERSION OF THIS FILE HAD ONE.
        //
        // `compactAndAppend` gets exactly ONE production home, and that home is
        // the eviction bracket in a later slice — not a general-purpose method on
        // a facade every caller can reach. Adding a delegate here created a
        // `F4_COMPACTION_BRACKET` site inside the shared queue package, which is
        // the `P1a2-13` forbidden location, and it broke five pins at once plus
        // `compactAndAppend has NO production caller`. The scanner was right: a
        // convenience delegate IS a second destructive path, whatever the
        // intention behind it. A later slice reaches the operation through
        // `QueueHome.store` inside a BRACKET region, in its own ownership home.

        fun logicalFingerprint(): String =
            EncryptedEventStore.logicalFingerprint(store.readAll().records)
    }

    /**
 * Run [block] with BOTH layers held for [region], in the one order — the
 * ONE way to open an ordered region.
 *
 * Reentrant at both layers, so a sequence that nests (a park write inside a
 * park move, a store mutation inside a bracket) is a single acquisition
 * held throughout rather than two locks in an order.
 *
 * @throws QueueRegionLock.Unavailable only for a BOUNDED region's expired
 * budget or an interrupted wait — an ordinary region WAITS for the owning
 * thread rather than refusing.
 * @throws QueueFileFence.Unavailable when another PROCESS holds the
 * install. There is no proceed-unlocked path at either layer.
 */
    fun <T> withQueue(region: QueueRegion, block: (FencedQueue) -> T): T =
        regionLock.withRegion(region) {
            fileFence.withFence(region) { block(FencedQueue(store)) }
        }

    /**
 * The same acquisition, answering a typed [Outcome] instead of throwing —
 * for a caller that must account a refusal rather than propagate it.
 */
    fun <T> tryWithQueue(region: QueueRegion, block: (FencedQueue) -> T): Outcome<T> = try {
        Outcome.Ran(withQueue(region, block))
    } catch (u: QueueRegionLock.Unavailable) {
        Outcome.Refused(QueueRefusal.of(u))
    } catch (u: QueueFileFence.Unavailable) {
        Outcome.Refused(QueueRefusal.of(u))
    }

    /** Ran, or refused with a reason that names WHICH layer refused and why. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    sealed class Outcome<out T> {
        data class Ran<T>(val value: T) : Outcome<T>()
        data class Refused(val refusal: QueueRefusal) : Outcome<Nothing>()
    }

    /** The raw store, for the single-step public API that orders itself. */
    fun store(): EncryptedEventStore = store

    /**
 * The ONE durable delivery-protection state of this queue (Slice B, B1).
 *
 * Owned by the home so that every pipeline constructed over this queue —
 * the native producer's per-drain construction, the RN transport's, a
 * later bracket's planner — reads and writes the SAME file beside the
 * queue: `<queue file name>.protection.json`, in the queue's directory,
 * under the same domain lock. It is not key-backed (digests and ids only)
 * and is deliberately absent from `QueueKeyStores.ALL`: a teardown that
 * deletes the queue leaves it, and the next trustworthy read reconciles
 * every entry away as proven absent.
 */
    fun protection(): com.tracescout.mobile.internal.queue.DeliveryProtection = protection

    private val protection = com.tracescout.mobile.internal.queue.DeliveryProtection(
        File(queueFile.parentFile, queueFile.name + PROTECTION_SUFFIX))

    /**
 * The ONE durable eviction-bracket marker of this queue (Slice C, C2):
 * `<queue file name>.bracket.json`, beside the queue, under the same
 * domain lock, owned by the home so the bracket, the pipeline's drain
 * gate and both producers read the SAME file. Not key-backed (digests and
 * fingerprints only) and deliberately absent from `QueueKeyStores.ALL`,
 * for the same reason as the protection file.
 */
    fun bracketMarker(): com.tracescout.mobile.internal.queue.BracketMarker = bracketMarker

    private val bracketMarker = com.tracescout.mobile.internal.queue.BracketMarker(
        File(queueFile.parentFile, queueFile.name + BRACKET_SUFFIX))

    /**
 * The ONE durable erasure request of this INSTALL (Slice D, D6 / U-1):
 * `<queue directory>/tracescout-erasure.requested`. Per install, not per
 * queue — both queue homes of one `filesDir` resolve the same file, which
 * is what makes U-2 ("no new queue work while an erasure is requested")
 * one fact for the native and the RN producer. Not key-backed (it must
 * survive the destruction of both key domains), absent from
 * `QueueKeyStores.ALL`, and cleared explicitly at U-8.
 */
    fun erasure(): ErasureRequest = erasure

    /**
 * F4-P2: the ONE durable reconciliation fact of this queue:
 * `<queue file name>.reconcile.json`, beside the queue, owned by the home so
 * the reconciler, the erasure flow and a test read the SAME file. Not
 * key-backed, for the same reason as the marker beside it.
 */
    fun reconciliationFact(): com.tracescout.mobile.internal.queue.ReconciliationFact = reconciliationFact

    private val reconciliationFact = com.tracescout.mobile.internal.queue.ReconciliationFact(
        File(queueFile.parentFile, queueFile.name + RECONCILE_SUFFIX),
    )

    private val erasure = ErasureRequest(File(queueFile.parentFile, ErasureRequest.FILE_NAME))

    /** The F2-gated adapter, for callers that genuinely need one step. */
    fun records(mutationGate: (List<ByteArray>) -> List<Long> = { emptyList() }) =
        com.tracescout.mobile.internal.queue.RecordStore.of(store, mutationGate)

    fun regionLock(): QueueRegionLock = regionLock

    fun fileFence(): QueueFileFence = fileFence

    /** True when the calling thread is inside a region on THIS home. */
    fun isRegionHeldByCurrentThread(): Boolean =
        regionLock.isHeldByCurrentThread() && fileFence.isHeldByCurrentThread()

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    companion object {
        /** The protection file lives beside its queue: `<queue>.protection.json`. */
        const val PROTECTION_SUFFIX: String = ".protection.json"

        /** The eviction-bracket marker lives beside its queue: `<queue>.bracket.json`. */
        const val BRACKET_SUFFIX: String = ".bracket.json"

        /** F4-P2: the reconciliation fact lives beside its queue: `<queue>.reconcile.json`. */
        const val RECONCILE_SUFFIX: String = ".reconcile.json"

        /**
 * HOMES ONLY. The locks live in their own registries, on purpose —
 * see the class note. Adding a lock to this map would make
 * [forgetForTest] able to replace a live lock.
 */
        private val instances = ConcurrentHashMap<String, QueueHome>()

        /**
 * The home for [queueFile], creating the store with [storeFactory] the
 * first time and returning the SAME home thereafter.
 *
 * The factory exists because the store needs a key and a policy that
 * this class has no business knowing; it is called at most once per
 * canonical path.
 *
 * THERE IS DELIBERATELY NO `ofStore(queueFile, store)` OVERLOAD. It
 * existed, it had zero callers in production and in tests, and it was a
 * standing identity hole: `computeIfAbsent` DISCARDS the argument when
 * a home already exists, so a caller could be handed a home wrapping a
 * DIFFERENT store — one whose key a teardown had destroyed, for
 * instance. A `check` would have made the mismatch loud; deleting the
 * entry point makes a caller-supplied store that disagrees with the
 * home's identity unrepresentable, which is better. The factory form
 * below cannot disagree, because the home builds the store itself from
 * the canonical file it is keyed on.
 */
        @JvmStatic
        fun of(queueFile: File, storeFactory: (File) -> EncryptedEventStore): QueueHome {
            val canonical = canonicalOf(queueFile)
            return instances.computeIfAbsent(canonical.path) { _ ->
                val built = storeFactory(canonical)
                // THE FACTORY IS NOT TRUSTED TO HONOUR ITS ARGUMENT. It is a
                // lambda a caller wrote; nothing stops it returning a store over
                // an unrelated file, and a home whose identity disagrees with the
                // store it wraps is the exact hole `ofStore` was deleted for. The
                // comparison is on the CANONICAL backing file, because that is
                // what both lock layers key on.
                check(canonicalOf(built.backingFile()).path == canonical.path) {
                    "the store factory returned a store over ${built.backingFile().path}, " +
                        "which is not the home's canonical queue file ${canonical.path}"
                }
                QueueHome(
                    canonical,
                    built,
                    QueueRegionLock.forStoreFile(canonical),
                    QueueFileFence.forStoreFile(canonical),
                )
            }
        }

        /**
 * TESTS ONLY, and it forgets HOMES and nothing else. It does not
 * delete a lock file and it cannot replace a live lock object: the lock
 * registries are separate, so a home rebuilt for the same path gets the
 * SAME [QueueRegionLock] and the SAME [QueueFileFence]. Forgetting a
 * home is safe; replacing a lock under a running store would silently
 * serialise nothing, and unlinking a lock file would break the
 * cross-process guarantee.
 */
        @JvmStatic
        internal fun forgetForTest() {
            instances.clear()
        }

        private fun canonicalOf(f: File): File =
            try { f.canonicalFile } catch (_: Throwable) { f.absoluteFile }
    }
}

/**
 * Which layer refused, and why — one vocabulary, so a caller never has to
 * flatten two different facts into one reason string.
 *
 * THE `wire` VALUES ARE DISTINCT BY CONSTRUCTION. A same-process crash
 * budget expiry, an interrupted wait, a cross-process holder and an internal
 * lock-invariant violation are four different conditions with four different
 * correct responses, and the ruling that produced this type forbids mapping
 * them to one reason.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class QueueRefusal(
    val region: QueueRegion,
    val layer: Layer,
    val wire: String,
    val waitedMs: Long,
) {
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    enum class Layer { REGION, FENCE }

    companion object {
        // THE PREFIX IS NOT WRITTEN HERE. `QueueLockRefusal` is the one home
        // of the layer-prefixed token; these two factories stay because overload
        // dispatch makes the TYPED question exhaustive (they cannot fail, where
        // an untyped `Throwable` must be allowed to), but the derivation they
        // return is the shared one. Re-inlining `"region_"` / `"fence_"` here is
        // the duplication the dedicated control exists to catch.
        fun of(u: QueueRegionLock.Unavailable): QueueRefusal =
            QueueRefusal(u.region, Layer.REGION, QueueLockRefusal.regionWire(u.reason), u.waitedMs)

        fun of(u: QueueFileFence.Unavailable): QueueRefusal =
            QueueRefusal(u.region, Layer.FENCE, QueueLockRefusal.fenceWire(u.reason), u.waitedMs)
    }
}

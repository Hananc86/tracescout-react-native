package com.tracescout.mobile.internal.loss

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * THE DEDICATED AUTHORITY-V2 KEY DOMAIN (F3-P0 the specification).
 *
 * ```
 * tracescout_authority_v2 AES-256-GCM, AndroidKeyStore,
 * PURPOSE_ENCRYPT|DECRYPT, BLOCK_MODE_GCM,
 * ENCRYPTION_PADDING_NONE,
 * setRandomizedEncryptionRequired(false)
 * NOT user-authentication-bound,
 * NOT setUnlockedDeviceRequired
 * ```
 *
 * the same properties, and the same reasons, as `StoreKeys.queueKey`.
 *
 * ## Why DEDICATED rather than "the queue key plus strong AAD"
 *
 * * it makes a **queue → authority ciphertext transplant STRUCTURALLY
 * IMPOSSIBLE**, in addition to being AAD-refused. A property that holds because
 * the ciphertext cannot decrypt at all is stronger than one that holds because
 * a check fired;
 * * it **bounds the GCM invocation domain**. With random 96-bit IVs the safe
 * invocation budget for one key is finite; the queue does orders of magnitude
 * more work than the authority, and separating them keeps the authority's count
 * small and countable;
 * * it isolates queue / park / authority domains, so a future key rotation or
 * destruction in one domain does not silently blind another;
 * * the migration cost is already being paid (new root dir, new format, and the
 * authority's departure from `EncryptedEventStore`), so the incremental cost is
 * one `KeyGenParameterSpec`.
 *
 * **ALSO LOAD-BEARING FOR the specification** `EncryptedAuthorityStore` used to take the
 * QUEUE's `SecretKey`. A dedicated alias means the authority's files cannot be an
 * `EncryptedEventStore` built over the queue key even if nothing else changed.
 *
 * ## ALL FOUR ROLES MOVE TOGETHER, OR THE ISOLATION CLAIM IS NOT THE CLAIM IT
 * READS AS
 *
 * A design that moves `snapshot` and `wal` to this alias and leaves the **FLOORS**
 * on a queue-key default **FAILS X1-B**, for a reason invisible to any
 * snapshot/WAL-only assertion: the floors file would keep opening, keep
 * authenticating and keep passing every test that asks *"does the authority
 * work?"* — while `session-floors.bin`, the one artifact whose whole purpose is to
 * stop a reused session id from allocating from zero after a prune, sat in the
 * QUEUE's key domain. the specification's first bullet would then be true of the snapshot and the
 * WAL and false of the floors, and the specification H2's transplant property would hold for
 * the roles nobody attacks.
 *
 * ## `StoreKeys.queueKey` IS NOT MIGRATED, RENAMED OR RE-POINTED
 *
 * The migration is **THREE DEFAULT EXPRESSIONS** in `LossAuthority` (`open`,
 * `openExisting`, `openForRemoval`), **not a symbol**. Eight live call sites
 * depend on `queueKey` resolving the QUEUE key — the Android queue, the Android
 * park, the RN queue, the RN park, RN frames, and three harness readers — and two
 * of them are the **park** stores, whose bytes are preserved losslessly and never
 * deleted. A key-domain change there makes already-parked customer data
 * permanently unreadable **with no delete path to recover from**. the specification H6's third
 * control is exactly that mutation: re-pointing the symbol satisfies every
 * authority assertion in the row and silently moves five shipped consumers into
 * this domain.
 *
 * ## Two keys are two ways to lose data — stated, and handled
 *
 * Destroying the queue key no longer makes the authority unreadable and vice
 * versa, so a teardown path must destroy **BOTH** — which is now a property of
 * construction rather than of discipline: `StoreTeardown.destroyAllKeyMaterial`
 * is the one home, both per-domain destroys are `internal` to it, and
 * `TraceScoutMobile.destroyStoredKeyMaterial` is the product path that calls
 * it. Destroying one alone is not something a caller can express. And a
 * device can legitimately have the queue key and **not** this one (the first run
 * of a v2 build) — that is GENESIS, not an error.
 */
internal object AuthorityKeys {

    private const val KEYSTORE = "AndroidKeyStore"

    /** The authority-v2 domain. `keyDomain = 1` in every v2 preamble names THIS. */
    const val ALIAS: String = "tracescout_authority_v2"

    /**
 * The domain NAME this alias is, for typed per-domain refusals.
 *
 * NOT the alias, and not the preamble's numeric `keyDomain` either. A
 * refusal a human reads must name the domain in words; the alias is a
 * Keystore implementation detail and the byte is a wire detail.
 */
    const val DOMAIN: String = "authority_v2"

    /**
 * Fetch or create the authority-v2 key.
 *
 * Throws rather than falling back to anything: a caller that cannot get a
 * real key must decide whether to run without an authority, not be silently
 * handed something that merely resembles encryption. The throw becomes the
 * typed `key_unavailable:<domain>:<Exception>` refusal — the DOMAIN is a
 * field of the reason, not an implied context, so a reader learns WHICH key
 * was unavailable. Evaluated at the specification **step 3 —
 * BEFORE any AEAD attempt** — because a missing key is not corruption and
 * must never be reported as one.
 *
 * **NO PER-RECORD OR EXPORTABLE KEY MATERIAL EXISTS ANYWHERE.** One
 * Keystore-resident, non-exportable alias per domain; no derived keys in app
 * memory, and no key id in the file.
 */
    fun authorityKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // We supply our own per-frame IV, so a frame carries everything
                // needed to decrypt itself independently of its neighbours.
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    /**
 * Deleting this key makes every authority-v2 file permanently unreadable.
 *
 * **`internal`, AND `StoreTeardown` IS ITS ONLY CALLER**. The
 * earlier wording here — *"a teardown MUST call both this and
 * `StoreKeys.destroyQueueKey`"* — was an instruction to a reader, and
 * NEITHER function had a caller anywhere in the tree, so the instruction
 * was addressed to nobody. The requirement is now discharged by
 * construction: both per-domain destroys are unreachable except through the
 * one teardown that performs both.
 *
 * **IT THROWS ON FAILURE.** This used to swallow every `Throwable` as
 * "best effort", which made the teardown's `authorityDestroyed` an
 * unconditional `true` — an unfalsifiable claim about an irreversible
 * operation. Containment belongs at the teardown, which attempts both
 * domains independently and reports each; the leaf's job is to tell the
 * truth about its own domain. See `StoreKeys.destroyQueueKey`.
 */
    internal fun destroyAuthorityKey() {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
    }
}

package com.tracescout.mobile.queue

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The encryption key for [EncryptedEventStore].
 *
 * The key lives in the ANDROID KEYSTORE and is never exported. That is the
 * point: the SDK holds a handle, not key material, so a stolen app-data
 * directory yields ciphertext with no key beside it. An in-process random key
 * would be strictly worse than useless — it would look like encryption while
 * living in the same memory an attacker with the data already reached.
 *
 * The key is NOT user-authentication-bound and NOT `setUnlockedDeviceRequired`:
 * the queue must be writable while the device is locked and the app is in the
 * background, which is precisely when a crash-time or backgrounded flush
 * happens. Requiring an unlocked device would silently drop exactly the events
 * that matter most.
 */
object StoreKeys {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "tracescout_mobile_queue_v1"

    /**
 * Fetch or create the queue key.
 *
 * Throws rather than falling back to an in-process key: a caller that
 * cannot get a real key must decide whether to run without persistence, not
 * be silently handed something that merely resembles encryption.
 */
    fun queueKey(): SecretKey {
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
                // We supply our own per-record IV so a frame carries everything
                // needed to decrypt itself independently.
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    /** The domain name this alias IS, for typed per-domain refusals. */
    const val DOMAIN: String = "queue_v1"

    /**
 * Deleting the key makes every stored frame permanently unreadable.
 *
 * **`internal`, AND [StoreTeardown] IS ITS ONLY CALLER**. The
 * two key domains must be destroyed TOGETHER or a "reset" leaves an install
 * whose queue is unreadable while its authority still answers. Making the
 * per-domain destroy unreachable from outside the one teardown home turns
 * "do not destroy only the queue key" from a convention into something a
 * caller cannot express.
 *
 * **IT THROWS ON FAILURE, AND THAT IS A CORRECTION.** This used to
 * swallow every `Throwable` as "best effort — the caller is already tearing
 * down", which made `runCatching { destroyQueueKey }.isSuccess`
 * **UNCONDITIONALLY TRUE**: the teardown reported `queueDestroyed = true`
 * whatever the Keystore did. That is not a best effort, it is an unfalsifiable
 * claim about an irreversible operation — and it makes the one branch that
 * must exist unreachable, because [StoreTeardown] clears the queue-key stores
 * ONLY when this succeeded. Containment belongs at the teardown, which
 * attempts both domains independently and reports each; the leaf's job is to
 * tell the truth about its own domain.
 */
    internal fun destroyQueueKey() {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
    }
}

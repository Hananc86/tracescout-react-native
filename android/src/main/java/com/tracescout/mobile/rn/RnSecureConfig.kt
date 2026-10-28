package com.tracescout.mobile.rn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed configuration for the React Native transport.
 *
 * **The credential is WRITE-ONLY from JavaScript.** JS may submit or replace a
 * key and may ask for a status; there is deliberately **no method anywhere on
 * this object that returns the key to a caller outside the native module**, and
 * `RnSecureConfigTest` asserts that by reflection rather than by review.
 *
 * The key is encrypted with an AES-256/GCM key held in the **AndroidKeyStore**
 * and never extractable from it, so the ciphertext in SharedPreferences is
 * useless on another device or to anything that copies the prefs file out. The
 * plaintext key is never written to AsyncStorage, MMKV, SharedPreferences, the
 * JS bundle, a JS log, a bridge response, an Intent extra, a URL or crash
 * metadata.
 *
 * Mirrors the fixture's proven `SecureConfigStore` rather than inventing a
 * second scheme — same cipher, same non-extractable handle, same
 * fingerprint-only disclosure. It is a separate object only because the fixture
 * one lives in the fixture app and this must ship inside the library.
 */
object RnSecureConfig {

    private const val PREFS = "tracescout_rn_config"
    private const val KEY_ALIAS = "tracescout_rn_ingest_key_v1"
    private const val P_CIPHERTEXT = "ingest_key_ct"
    private const val P_IV = "ingest_key_iv"
    private const val P_PROJECT = "project_id"
    private const val P_ENVIRONMENT = "environment"
    private const val P_BASE_URL = "base_url"
    /** Phase 2 visual frames: OPT-IN, native, default false. Never a JS parameter. */
    private const val P_FRAMES = "frames_enabled"
    // A DEDICATED KEY, DELIBERATELY UNRELATED TO ANY VISUAL FLAG. Network
    // capture is a separate consent from visual replay, so it gets a separate
    // stored fact — nothing derives it, and no migration can copy another
    // preference into it.
    private const val P_NETWORK_CAPTURE = "network_capture_enabled_v1"

    /**
 * ITS OWN KEY, ITS OWN DEFAULT, DERIVED FROM NOTHING. Interaction capture
 * is independent of replay, hierarchy, frames, network, the renderer and the
 * SERVER's interaction gate. An SDK upgrade alone must capture zero taps, so
 * the default is false and there is no path by which another consent can
 * turn it on.
 */
    private const val P_INTERACTION_CAPTURE = "interaction_capture_enabled_v1"

    /**
 * The host-declared WebView origin allowlist.
 *
 * ITS OWN KEY, AND NATIVE-OWNED. The contract is "native-owned SDK
 * configuration at initialization": a policy a page's own runtime could
 * supply is a policy the page could widen, so this is persisted beside the
 * other native switches and has no bridge method and no `@ReactMethod`.
 * This module carries no `com.facebook.react` dependency at all, so a
 * JS-callable door is unrepresentable rather than merely absent.
 *
 * STORED VERBATIM, VALIDATED AT THE BIND. These accessors do not admit,
 * repair or filter — [com.tracescout.rn.RnWebViewOriginPolicy.admit] is the
 * one admission rule, and validating in two places is how the two answers
 * drift. A malformed entry therefore persists and is refused BY NAME every
 * time it is read, which is what lets an operator see the entry they typed.
 */
    private const val P_WEBVIEW_ORIGINS = "webview_origin_allowlist_v1"
    private const val GCM_TAG_BITS = 128

    /**
 * What JS is allowed to know. Note what is ABSENT: the key.
 *
 * `keyFingerprint` is a truncated SHA-256 of the key, so an operator can
 * confirm WHICH credential is installed without the value being recoverable
 * from what they were shown.
 */
    data class Status(
        val configured: Boolean,
        val projectId: String?,
        val environment: String?,
        val keyFingerprint: String?,
    )

    /** Native-only. Never returned across the bridge. */
    internal data class Config(
        val projectId: String,
        val ingestKey: String,
        val environment: String,
        val baseUrl: String,
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
 * `setUserAuthenticationRequired` is deliberately NOT set: requiring a
 * device unlock to upload telemetry would make background drain fail in
 * ways indistinguishable from a bug, which is the wrong trade for a
 * write-only client credential.
 */
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /**
 * **NOT JS-callable — this takes the plaintext credential.**
 *
 * This is the HOST APP's native configuration entry point, called from
 * `Application.onCreate` with a key the app sources itself. Exposing it as
 * a `@ReactMethod` would hand the plaintext ingest key to the JS runtime,
 * defeating the Keystore entirely: the encryption would protect a value
 * that had already been handed out in the clear.
 *
 * The previous doc comment on this function said "JS-callable", which is
 * the kind of note that becomes an instruction to the next reader. There is
 * no JS-facing configure, and the bridge's structural tests assert that no
 * public method there accepts a credential.
 *
 * Replaces any previous credential atomically.
 */
    fun configure(
        ctx: Context,
        projectId: String,
        ingestKey: String,
        environment: String,
        baseUrl: String,
    ): Status {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(ingestKey.toByteArray(Charsets.UTF_8))
        prefs(ctx).edit()
            .putString(P_CIPHERTEXT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(P_IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString(P_PROJECT, projectId)
            .putString(P_ENVIRONMENT, environment)
            .putString(P_BASE_URL, baseUrl)
            .apply()
        return status(ctx)
    }

    /**
 * The ONLY thing JS may read back.
 *
 * The fingerprint is computed from the DECRYPTED key so it identifies the
 * credential rather than the ciphertext — two encryptions of the same key
 * differ (fresh IV), and a fingerprint that changed on every write would
 * tell an operator nothing.
 */
    fun status(ctx: Context): Status {
        val p = prefs(ctx)
        val project = p.getString(P_PROJECT, null)
        val key = readKeyInternal(ctx)
        return Status(
            configured = project != null && key != null,
            projectId = project,
            environment = p.getString(P_ENVIRONMENT, null),
            keyFingerprint = key?.let { fingerprint(it) },
        )
    }

    /**
 * Phase 2 visual frames — OPT-IN. A native, persisted, default-false switch
 * the host app flips from its own settings surface. It has no bridge
 * method: the JS surface stays six semantic methods, and a JS-settable
 * capture switch would make "opt-in" a claim rather than a property.
 */
    fun setFramesEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(P_FRAMES, enabled).apply()
    }
    fun framesEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(P_FRAMES, false)

    /** Documented reset flow: the credential AND the configuration both go. */
    /**
 * M3.3 — the CLIENT network-capture opt-in. **Default OFF.**
 *
 * INDEPENDENT BY CONSTRUCTION. It is not derived from replay ingest,
 * hierarchy capture, visual frames, the renderer, or the SERVER network
 * flag — reading any of those here would make one consent imply another.
 * The standing Lab runs visual capture permanently; that must never begin
 * producing network telemetry, and an app UPGRADE must never begin
 * producing it either: a customer who installs a new SDK version has
 * consented to nothing new.
 *
 * Turning it OFF stops the CREATION of new network events. It never gates
 * the application's own HTTP request, and it does not discard events
 * already durably queued — those are records we already took, and deleting
 * them would be losing data to express a preference about future data.
 */
    fun setNetworkCaptureEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(P_NETWORK_CAPTURE, enabled).apply()
    }

    fun networkCaptureEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(P_NETWORK_CAPTURE, false)

    /**
 * Turning interaction capture OFF stops the CREATION of new interaction
 * telemetry. It never gates the application's touch delivery — the seam
 * observes and delegates whatever this returns — and it does not discard
 * events already durably queued: those are records we already took, and
 * deleting them would be losing data to express a preference about future
 * data.
 */
    fun setInteractionCaptureEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(P_INTERACTION_CAPTURE, enabled).apply()
    }

    fun interactionCaptureEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(P_INTERACTION_CAPTURE, false)

    /**
 * Declare the WebView origin allowlist, or remove it entirely.
 *
 * NULL REMOVES, AND AN EMPTY LIST IS NOT THE SAME THING. "No allowlist
 * at all" and "a reviewed allowlist that admits nothing" are different
 * facts about the host's intent, and the bind reports them with different
 * reasons (`origin_policy_absent` vs `origin_policy_empty`).
 *
 * A JSON ARRAY, NOT A `StringSet`. `putStringSet` is unordered, and the
 * operator's declaration order is what [RnWebViewOriginPolicy.admit]
 * preserves and de-duplicates in — so a Set would silently reshape the
 * reviewed list. `org.json` is the established ordered-list encoding in
 * this SDK's own persisted state (`InFlightStore`, `DeliveryProtection`)
 * and ships with the platform, so this adds no dependency.
 */
    fun setWebViewOriginAllowlist(ctx: Context, origins: List<String>?) {
        val e = prefs(ctx).edit()
        if (origins == null) {
            e.remove(P_WEBVIEW_ORIGINS)
        } else {
            e.putString(P_WEBVIEW_ORIGINS, org.json.JSONArray(origins).toString())
        }
        e.apply()
    }

    /**
 * The declared allowlist, or null when the host declared none.
 *
 * A PRESENT-BUT-UNREADABLE VALUE ALSO READS AS NULL, AND THAT IS A
 * DELIBERATE FAIL-CLOSED CONFLATION RATHER THAN AN OVERSIGHT. Both answers
 * lead the bind to the same refusal and admit no origin, so the safety
 * direction is identical; what is lost is only the operator's diagnosis,
 * and the alternative — returning the raw blob as though it were an entry —
 * would invent an origin declaration nobody made. The Swift twin's
 * `UserDefaults.stringArray` behaves the same way for the same reason.
 */
    fun webViewOriginAllowlist(ctx: Context): List<String>? {
        val raw = prefs(ctx).getString(P_WEBVIEW_ORIGINS, null) ?: return null
        return runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        }.getOrNull()
    }

    fun disconnect(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(KEY_ALIAS)
        }
    }

    /** 12 hex chars of SHA-256 — identifies, never reconstructs. */
    private fun fingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 12)

    /**
 * INTERNAL. Visible to the native transport only.
 *
 * `internal` alone is NOT a JVM boundary: Kotlin compiles it to a PUBLIC
 * method with a mangled name (`readKeyInternal$rn`), which Java code or
 * reflection in another module can still call — my own test caught exactly
 * that. `@JvmSynthetic` removes it from the Java-visible surface, so the
 * write-only contract is enforced by the bytecode rather than by Kotlin
 * visibility that only Kotlin callers respect.
 */
    @JvmSynthetic
    internal fun readKeyInternal(ctx: Context): String? {
        val p = prefs(ctx)
        val ct = p.getString(P_CIPHERTEXT, null) ?: return null
        val iv = p.getString(P_IV, null) ?: return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    @JvmSynthetic
    internal fun load(ctx: Context): Config? {
        val p = prefs(ctx)
        val project = p.getString(P_PROJECT, null) ?: return null
        val key = readKeyInternal(ctx) ?: return null
        return Config(
            projectId = project,
            ingestKey = key,
            environment = p.getString(P_ENVIRONMENT, null) ?: "production",
            baseUrl = p.getString(P_BASE_URL, null) ?: "https://api.tracescout.com",
        )
    }
}

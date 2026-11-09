package app.kotoba.core.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores API keys encrypted with an AES-256-GCM key held in the Android Keystore.
 * Ciphertext lives in a private SharedPreferences file that is excluded from backups
 * (see `data_extraction_rules.xml`), so keys never leave the device or appear in logs.
 */
@Singleton
class SecureKeyStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasKey(providerId: String): Boolean = prefs.contains(providerId)

    fun get(providerId: String): String? {
        val stored = prefs.getString(providerId, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull()
    }

    fun put(providerId: String, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(providerId, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun remove(providerId: String) = prefs.edit().remove(providerId).apply()

    /** Masked representation for UI, e.g. `sk-…9f3a`. */
    fun masked(providerId: String): String? = get(providerId)?.let { k ->
        if (k.length <= 8) "••••" else k.take(3) + "…" + k.takeLast(4)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val PREFS = "secure_api_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kotoba_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}

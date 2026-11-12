package com.nyro.companion.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Azure API key encrypted with an AES-256-GCM key that lives in the
 * Android Keystore (hardware-backed where the device supports it). Only the
 * ciphertext is written to app-private storage; the plaintext key is never logged.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("nyro_secure", Context.MODE_PRIVATE)

    @Volatile private var cached: String? = null

    fun hasApiKey(): Boolean = prefs.contains(PREF_KEY)

    fun getApiKey(): String? {
        cached?.let { return it }
        val stored = prefs.getString(PREF_KEY, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LEN)
            val ct = bytes.copyOfRange(IV_LEN, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8).also { cached = it }
        } catch (e: Exception) {
            // Key invalidated (e.g. lock-screen reset) or data corrupt: drop it so the user re-enters it.
            clear()
            null
        }
    }

    fun setApiKey(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = cipher.iv + ct
        prefs.edit().putString(PREF_KEY, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        cached = value
    }

    fun clear() {
        prefs.edit().remove(PREF_KEY).apply()
        cached = null
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nyro_azure_api_key"
        const val PREF_KEY = "azure_key_ct"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}

package com.sari.subtitle.v2

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** يخزن مفتاح Groq مشفراً باستخدام AES-GCM ومفتاح محفوظ في Android Keystore. */
class GroqApiKeyStore(context: Context) {
    companion object {
        private const val PREFS = "sari_groq_secret"
        private const val PREF_CIPHERTEXT = "groq_api_key_ciphertext"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "sari_groq_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun save(apiKey: String) {
        val value = apiKey.trim()
        require(value.isNotEmpty()) { "مفتاح Groq فارغ." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(PREF_CIPHERTEXT, encoded).commit()) {
            "تعذر حفظ مفتاح Groq المشفر."
        }
    }

    /** يعيد سلسلة فارغة بدلاً من إسقاط التطبيق إذا تعذر فك التشفير. */
    @Synchronized
    fun read(): String {
        val encoded = prefs.getString(PREF_CIPHERTEXT, null) ?: return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) throw IllegalStateException("بيانات المفتاح المشفر غير صالحة.")
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingSecretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            // قد يحدث بعد استعادة بيانات التطبيق دون مفتاح Keystore أو تلف البيانات.
            runCatching { clear() }
            ""
        }
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().remove(PREF_CIPHERTEXT).commit()) { "تعذر حذف مفتاح Groq المشفر." }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val existing = loadKeyStore().getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingSecretKey(): SecretKey =
        loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("مفتاح التشفير غير موجود في Android Keystore.")

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}

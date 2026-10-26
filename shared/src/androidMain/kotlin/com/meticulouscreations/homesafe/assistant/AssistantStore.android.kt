package com.meticulouscreations.homesafe.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.meticulouscreations.homesafe.PlatformContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The preferences in a small file of their own, and the API key in it only as ciphertext: sealed
 * with an AES key that lives in the Android Keystore and never leaves it, so the file alone
 * (a backup, say) doesn't give the key away. Unlike the saved sign-in, using it asks for no
 * fingerprint: the assistant is opened far too often for that.
 */
private class KeystoreAssistantStore(context: Context) : AssistantStore {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun apiKey(): String? = runCatching {
        val sealed = preferences.getString(PREF_KEY, null) ?: return null
        val iv = Base64.decode(preferences.getString(PREF_IV, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
        String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    override fun saveApiKey(key: String?) {
        if (key == null) {
            preferences.edit().remove(PREF_KEY).remove(PREF_IV).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(PREF_KEY, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override fun string(name: String): String? = preferences.getString(PREF_PREFIX + name, null)

    override fun saveString(name: String, value: String?) {
        preferences.edit().apply { if (value == null) remove(PREF_PREFIX + name) else putString(PREF_PREFIX + name, value) }.apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}

private const val PREFS_NAME = "assistant"
private const val PREF_KEY = "api_key"
private const val PREF_IV = "api_key_iv"
private const val PREF_PREFIX = "pref_"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "homesafe_assistant_key"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128

actual fun createAssistantStore(platformContext: PlatformContext): AssistantStore =
    KeystoreAssistantStore(platformContext.context.applicationContext)

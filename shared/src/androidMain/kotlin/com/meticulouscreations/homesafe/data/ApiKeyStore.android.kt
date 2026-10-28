package com.meticulouscreations.homesafe.data

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
 * fingerprint: a key is used far too often for that (every message, every map tile).
 */
private class KeystoreApiKeyStore(context: Context, name: ApiKeyStoreName) : ApiKeyStore {
    private val preferences = context.getSharedPreferences(name.name, Context.MODE_PRIVATE)
    private val keyAlias = "homesafe_${name.name}_key"

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
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}

private const val PREF_KEY = "api_key"
private const val PREF_IV = "api_key_iv"
private const val PREF_PREFIX = "pref_"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128

actual fun createApiKeyStore(platformContext: PlatformContext, name: ApiKeyStoreName): ApiKeyStore =
    KeystoreApiKeyStore(platformContext.context.applicationContext, name)

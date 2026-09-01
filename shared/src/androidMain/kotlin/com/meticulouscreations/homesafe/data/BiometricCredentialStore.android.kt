package com.meticulouscreations.homesafe.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "homesafe_biometric_credentials_key"
private const val PREFS_NAME = "homesafe_biometric_credentials"
private const val PREF_CIPHERTEXT = "ciphertext"
private const val PREF_IV = "iv"
private const val GCM_TAG_LENGTH_BITS = 128

/** Thrown when a `BiometricPrompt` flow fails or is cancelled by the user. */
class BiometricAuthException(message: String) : Exception(message)

/**
 * Android implementation of [BiometricCredentialStore].
 *
 * Security model: the AES-GCM key backing [save]/[authenticateAndRetrieve] lives in the
 * Android Keystore ([getOrCreateSecretKey]) and is created with
 * [KeyGenParameterSpec.Builder.setUserAuthenticationRequired] set to `true`. That means the
 * Keystore itself refuses to hand out a usable [Cipher] for this key until the caller has
 * completed a fresh biometric check — a [Cipher.init] call for this key throws
 * `UserNotAuthenticatedException` unless it is unlocked via a successful
 * [BiometricPrompt.CryptoObject] flow. In other words, biometric auth is a prerequisite the
 * *crypto operation itself* enforces, not a boolean flag application code checks before
 * separately reading a plaintext-accessible credential. [setInvalidatedByBiometricEnrollment]
 * additionally invalidates the key (and therefore any previously saved credentials) if the
 * user's enrolled biometrics change, so a newly-enrolled fingerprint/face can never unlock
 * credentials saved under a different one.
 */
private class AndroidBiometricCredentialStore(private val activity: FragmentActivity) : BiometricCredentialStore {

    private val prefs = activity.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean =
        BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override fun displayName(): String = "fingerprint"

    override fun hasSavedCredentials(): Boolean =
        prefs.contains(PREF_CIPHERTEXT) && prefs.contains(PREF_IV)

    override suspend fun save(credentials: SavedCredentials): Result<Unit> = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        }
        val authenticatedCipher = authenticate(
            cipher = cipher,
            title = "Enable biometric sign-in",
            subtitle = "Confirm your fingerprint or face to save this login",
        )
        val plaintext = json.encodeToString(SavedCredentials.serializer(), credentials).encodeToByteArray()
        val ciphertext = authenticatedCipher.doFinal(plaintext)
        prefs.edit()
            .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(authenticatedCipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override suspend fun authenticateAndRetrieve(): Result<SavedCredentials> = runCatching {
        val ciphertext = prefs.getString(PREF_CIPHERTEXT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: error("No saved biometric credentials")
        val iv = prefs.getString(PREF_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: error("No saved biometric credentials")

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        val authenticatedCipher = authenticate(
            cipher = cipher,
            title = "Sign in with biometrics",
            subtitle = "Use your fingerprint or face to sign in to HomeSafe",
        )
        val plaintext = authenticatedCipher.doFinal(ciphertext)
        json.decodeFromString(SavedCredentials.serializer(), plaintext.decodeToString())
    }

    override fun clear() {
        prefs.edit().remove(PREF_CIPHERTEXT).remove(PREF_IV).apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** Runs a `BiometricPrompt` bound to [cipher] and resumes with the now-unlocked cipher on success. */
    private suspend fun authenticate(cipher: Cipher, title: String, subtitle: String): Cipher =
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlockedCipher = result.cryptoObject?.cipher
                    if (unlockedCipher != null) {
                        continuation.resume(unlockedCipher)
                    } else {
                        continuation.resumeWithException(
                            BiometricAuthException("Biometric auth succeeded without a crypto object"),
                        )
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(BiometricAuthException(errString.toString()))
                    }
                }

                override fun onAuthenticationFailed() {
                    // One failed attempt (e.g. an unrecognized fingerprint); the prompt stays open
                    // for the user to retry. Only onAuthenticationError ends the flow.
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

actual fun createBiometricCredentialStore(context: PlatformContext): BiometricCredentialStore =
    AndroidBiometricCredentialStore(context.context as FragmentActivity)

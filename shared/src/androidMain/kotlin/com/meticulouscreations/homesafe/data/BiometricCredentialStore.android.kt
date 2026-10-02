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
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.shared.R
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_fingerprint
import homesafe.shared.generated.resources.biometric_needs_app_open
import homesafe.shared.generated.resources.biometric_no_saved_login
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource
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

/** Nothing was saved for biometric sign-in, or what was is gone. */
private fun noSavedLogin() = LocalizedException(UiText.of(Res.string.biometric_no_saved_login), technical = "No saved biometric credentials")

/** Thrown when a `BiometricPrompt` flow fails or is cancelled by the user; the message is the platform's own, already in the reader's language. */
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

    override fun displayName(): StringResource = Res.string.biometric_name_fingerprint

    override fun hasSavedCredentials(): Boolean =
        prefs.contains(PREF_CIPHERTEXT) && prefs.contains(PREF_IV)

    override suspend fun save(credentials: SavedCredentials): Result<Unit> = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        }
        val authenticatedCipher = authenticate(
            cipher = cipher,
            title = activity.getString(R.string.biometric_enable_title),
            subtitle = activity.getString(R.string.biometric_enable_subtitle),
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
            ?: throw noSavedLogin()
        val iv = prefs.getString(PREF_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: throw noSavedLogin()

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        val authenticatedCipher = authenticate(
            cipher = cipher,
            title = activity.getString(R.string.biometric_sign_in_title),
            subtitle = activity.getString(R.string.biometric_sign_in_subtitle),
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
                .setNegativeButtonText(activity.getString(R.string.biometric_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()

            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** For a graph built with no Activity (a background receiver): there is nobody to show a prompt to. */
private class HeadlessBiometricCredentialStore : BiometricCredentialStore {
    override fun isAvailable(): Boolean = false
    override fun displayName(): StringResource = Res.string.biometric_name_fingerprint
    override fun hasSavedCredentials(): Boolean = false
    override suspend fun save(credentials: SavedCredentials): Result<Unit> =
        Result.failure(needsTheAppOpen())
    override suspend fun authenticateAndRetrieve(): Result<SavedCredentials> =
        Result.failure(needsTheAppOpen())

    private fun needsTheAppOpen() =
        LocalizedException(UiText.of(Res.string.biometric_needs_app_open), technical = "Biometric login needs the app open")
    override fun clear() = Unit
}

actual fun createBiometricCredentialStore(context: PlatformContext): BiometricCredentialStore =
    (context.context as? FragmentActivity)?.let(::AndroidBiometricCredentialStore) ?: HeadlessBiometricCredentialStore()

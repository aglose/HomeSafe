package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.IdentityCheck
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_confirmation_cancelled
import homesafe.shared.generated.resources.biometric_finance_reason
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import platform.LocalAuthentication.LAContext
import kotlin.coroutines.resume

/**
 * A plain Face ID / Touch ID prompt, with no Keychain item behind it: the answer is all that is
 * wanted. The phone's passcode is offered when the face or finger isn't recognised.
 */
private class IosIdentityCheck : IdentityCheck {

    override val isAvailable: Boolean get() = canAuthenticateDeviceOwner()

    override val methodName: StringResource get() = deviceOwnerMethodName()

    override suspend fun confirm(): Result<Unit> {
        val reason = getString(Res.string.biometric_finance_reason)
        return suspendCancellableCoroutine { continuation ->
            LAContext().evaluatePolicy(
                policy = DeviceOwnerPolicy,
                localizedReason = reason,
            ) { success, error ->
                if (!continuation.isActive) return@evaluatePolicy
                // The system's own words when it gave any (locked out after too many tries, Face ID
                // switched off for the app), already in the reader's language; the app's otherwise.
                val why = error?.localizedDescription?.takeIf { it.isNotBlank() }
                continuation.resume(
                    when {
                        success -> Result.success(Unit)
                        why != null -> Result.failure(IdentityCheckException(why))
                        else -> Result.failure(LocalizedException(UiText.of(Res.string.biometric_confirmation_cancelled), technical = "Biometric confirmation was cancelled"))
                    },
                )
            }
        }
    }
}

/** The system refused or gave up; the message is its own, already in the reader's language. */
private class IdentityCheckException(message: String) : Exception(message)

actual fun createIdentityCheck(context: PlatformContext): IdentityCheck = IosIdentityCheck()

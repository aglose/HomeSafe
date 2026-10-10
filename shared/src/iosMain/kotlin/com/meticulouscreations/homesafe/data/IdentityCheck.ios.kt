package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.IdentityCheck
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_confirmation_cancelled
import homesafe.shared.generated.resources.biometric_finance_reason
import homesafe.shared.generated.resources.biometric_name_face_id
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.biometric_name_touch_id
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSError
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

/** A plain Face ID / Touch ID prompt, with no Keychain item behind it: the answer is all that is wanted. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosIdentityCheck : IdentityCheck {

    override val isAvailable: Boolean
        get() = memScoped {
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, errorVar.ptr)
        }

    // biometryType is only populated once canEvaluatePolicy has run on this context.
    override val methodName: StringResource
        get() = memScoped {
            val context = LAContext()
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, errorVar.ptr)
            when (context.biometryType) {
                LABiometryTypeFaceID -> Res.string.biometric_name_face_id
                LABiometryTypeTouchID -> Res.string.biometric_name_touch_id
                else -> Res.string.biometric_name_generic
            }
        }

    override suspend fun confirm(): Result<Unit> {
        val reason = getString(Res.string.biometric_finance_reason)
        val recognised = suspendCancellableCoroutine { continuation ->
            LAContext().evaluatePolicy(
                policy = LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                localizedReason = reason,
            ) { success, _ ->
                if (continuation.isActive) continuation.resume(success)
            }
        }
        return if (recognised) {
            Result.success(Unit)
        } else {
            Result.failure(LocalizedException(UiText.of(Res.string.biometric_confirmation_cancelled), technical = "Biometric confirmation was cancelled"))
        }
    }
}

actual fun createIdentityCheck(context: PlatformContext): IdentityCheck = IosIdentityCheck()

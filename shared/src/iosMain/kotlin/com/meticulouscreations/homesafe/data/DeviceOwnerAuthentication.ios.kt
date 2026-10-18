package com.meticulouscreations.homesafe.data

import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_face_id
import homesafe.shared.generated.resources.biometric_name_passcode
import homesafe.shared.generated.resources.biometric_name_touch_id
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import org.jetbrains.compose.resources.StringResource
import platform.Foundation.NSError
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicy
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

/**
 * The policy every iOS check uses: Face ID or Touch ID, with the phone's passcode offered when it
 * fails or isn't set up. A phone with only a passcode passes it too.
 */
internal val DeviceOwnerPolicy: LAPolicy = LAPolicyDeviceOwnerAuthentication

@OptIn(ExperimentalForeignApi::class)
internal fun canEvaluate(policy: LAPolicy, context: LAContext = LAContext()): Boolean = memScoped {
    val errorVar = alloc<ObjCObjectVar<NSError?>>()
    context.canEvaluatePolicy(policy, errorVar.ptr)
}

/** True when the phone can check who is holding it: biometrics, its passcode, or both. */
internal fun canAuthenticateDeviceOwner(): Boolean = canEvaluate(DeviceOwnerPolicy)

/**
 * What the check will mostly ask for: Face ID or Touch ID when one is set up, the passcode when
 * that is all there is. (biometryType is only filled in once canEvaluatePolicy has run on the context.)
 */
internal fun deviceOwnerMethodName(): StringResource {
    val context = LAContext()
    if (!canEvaluate(LAPolicyDeviceOwnerAuthenticationWithBiometrics, context)) return Res.string.biometric_name_passcode
    return when (context.biometryType) {
        LABiometryTypeFaceID -> Res.string.biometric_name_face_id
        LABiometryTypeTouchID -> Res.string.biometric_name_touch_id
        else -> Res.string.biometric_name_passcode
    }
}

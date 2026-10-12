package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.IdentityCheck
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.identity_check_unavailable
import org.jetbrains.compose.resources.StringResource

/**
 * Builds the platform's [IdentityCheck]: BiometricPrompt on Android, LocalAuthentication on iOS.
 * Desktop and the browser have nothing to check against, so there it is [NoIdentityCheck].
 */
expect fun createIdentityCheck(context: PlatformContext): IdentityCheck

/** For a platform, or a moment (no screen to show a prompt on), with no way to check who is there. */
internal object NoIdentityCheck : IdentityCheck {
    override val isAvailable: Boolean = false
    override val methodName: StringResource = Res.string.biometric_name_generic
    override suspend fun confirm(): Result<Unit> =
        Result.failure(LocalizedException(UiText.of(Res.string.identity_check_unavailable), technical = "No identity check on this device"))
}

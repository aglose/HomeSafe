package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.IdentityCheck
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_finance_reason
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** Touch ID, or the Mac's password, with nothing behind it: the answer is all that is wanted. */
private object MacIdentityCheck : IdentityCheck {
    override val isAvailable: Boolean get() = MacOwnerCheck.isAvailable

    override val methodName: StringResource get() = MacOwnerCheck.methodName

    override suspend fun confirm(): Result<Unit> = MacOwnerCheck.ask("confirm", getString(Res.string.biometric_finance_reason)).map { }
}

/** The Mac's Touch ID where the app carries its helper; elsewhere nothing to check with ([NoIdentityCheck]). */
actual fun createIdentityCheck(context: PlatformContext): IdentityCheck = if (MacOwnerCheck.isAvailable) MacIdentityCheck else NoIdentityCheck

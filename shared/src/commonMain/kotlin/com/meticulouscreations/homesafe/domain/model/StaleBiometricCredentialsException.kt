package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.connection_error_stale_biometric_login

/**
 * The credentials saved for biometric sign-in no longer match the server — the password was
 * changed there, or the account was recreated — so they have been forgotten. The text reads
 * as the user should see it: the next step is a password sign-in, after which the app offers to
 * save the working credentials again.
 */
class StaleBiometricCredentialsException : LocalizedException(UiText.of(Res.string.connection_error_stale_biometric_login), technical = TECHNICAL) {
    private companion object {
        const val TECHNICAL = "Saved biometric credentials were refused by the server"
    }
}

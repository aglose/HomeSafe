package com.meticulouscreations.homesafe.domain.model

/**
 * The credentials saved for biometric sign-in no longer match the server — the password was
 * changed there, or the account was recreated — so they have been forgotten. The message reads
 * as the user should see it: the next step is a password sign-in, after which the app offers to
 * save the working credentials again.
 */
class StaleBiometricCredentialsException : Exception(MESSAGE) {
    private companion object {
        const val MESSAGE = "Your saved login no longer matches the server. Sign in with your password to update it."
    }
}

package com.meticulouscreations.homesafe.domain.model

/** What biometric sign-in can offer on this device right now. */
data class BiometricLoginStatus(
    /** True if biometric hardware is present, enrolled, and usable on this platform/device. */
    val isAvailable: Boolean,
    /** Short user-facing name for the biometric method, e.g. "Face ID" or "fingerprint". */
    val displayName: String,
    /** True if credentials were previously saved for biometric login and are (still) present. */
    val hasSavedCredentials: Boolean,
)

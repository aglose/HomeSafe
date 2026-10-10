package com.meticulouscreations.homesafe.domain.platform

import org.jetbrains.compose.resources.StringResource

/**
 * Asks whoever is holding the device to prove they are its owner, with a fingerprint or a face,
 * and reports only whether they did. Nothing is stored or unlocked by it (the saved login has its
 * own store, which ties the read itself to the check); this is for a screen that must not open
 * for someone who merely picked the phone up, when there is no server to ask who they are.
 */
interface IdentityCheck {
    /** True if the device has a fingerprint or face enrolled that it can check against. */
    val isAvailable: Boolean

    /** A short, user-facing name for how the device checks, e.g. "Face ID" or "fingerprint". */
    val methodName: StringResource

    /** Shows the system's prompt. Succeeds once the owner is recognised; fails if they aren't, or put it away. */
    suspend fun confirm(): Result<Unit>
}

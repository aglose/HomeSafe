package com.meticulouscreations.homesafe.finance.domain

import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

/**
 * Whether the household's money may be shown on this device right now.
 *
 * With the server answering, the server decides: every read of the sheet, the budget and the banks
 * rides on the session it issued, and it refuses whoever it doesn't know. Without it (not signed in
 * yet, Tailscale down, the box off) the app opens on what the device kept, and there is nobody to
 * ask. So the finance app is [locked] then, until whoever is holding the phone proves they own it
 * ([unlock]: a fingerprint or a face), and it locks again when the app is closed or leaves the
 * screen. The markets, which are public, are never held back by this; the pages they share with
 * the household's own figures are.
 */
interface FinanceAccess {
    /** True while the server is signed in to and answering, so what the finance app shows is what it just said. */
    val online: StateFlow<Boolean>

    /** True while there is no server to ask and nobody has proved the phone is theirs: nothing kept on the device is shown. */
    val locked: StateFlow<Boolean>

    /** Whether this device can check who is holding it at all. Where it can't, the finance app stays locked until the server answers. */
    val canUnlock: Boolean

    /** A short, user-facing name for how it checks, e.g. "Face ID" or "fingerprint". */
    val unlockMethod: StringResource

    /** Asks for the fingerprint or face. Succeeding unlocks until [lock], the app leaving the screen, or the server coming back. */
    suspend fun unlock(): Result<Unit>

    /** The finance app was closed: the next opening without the server asks again. */
    fun lock()
}

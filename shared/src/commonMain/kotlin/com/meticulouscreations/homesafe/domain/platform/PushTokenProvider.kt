package com.meticulouscreations.homesafe.domain.platform

/**
 * This device's push token — the identity the relay knows it by, so presence changes land on
 * the right row. A domain-level port: Android answers with Firebase, platforms without push
 * (desktop, a browser tab, and iOS until APNs registration lands) say [isSupported] is false.
 */
interface PushTokenProvider {
    /** False where this app can't receive push, so presence can't be attributed to the device either. */
    val isSupported: Boolean

    /** The current token, or null if push isn't set up or the token isn't available yet. */
    suspend fun token(): String?
}

package com.meticulouscreations.homesafe.data

import com.google.firebase.messaging.FirebaseMessaging
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Firebase's registration token — the same one `PushRegistrar` in the Android app hands the
 * relay, so the relay's device row and this device's presence line up.
 */
private class FirebasePushTokenProvider : PushTokenProvider {
    override val isSupported = true

    override suspend fun token(): String? = runCatching {
        suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
                .addOnCanceledListener { continuation.resume(null) }
        }
    }.getOrNull()
}

actual fun createPushTokenProvider(platformContext: PlatformContext): PushTokenProvider = FirebasePushTokenProvider()

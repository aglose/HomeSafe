package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.NotificationMute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/** The switch in the standard user defaults. One per process, so every reader sees every change. */
private object UserDefaultsNotificationMute : NotificationMute {
    private const val KEY = "notifications_muted"

    private val state = MutableStateFlow(NSUserDefaults.standardUserDefaults.boolForKey(KEY))

    override val muted: StateFlow<Boolean> = state.asStateFlow()

    override fun setMuted(muted: Boolean) {
        state.value = muted
        NSUserDefaults.standardUserDefaults.setBool(muted, KEY)
    }
}

actual fun createNotificationMute(platformContext: PlatformContext): NotificationMute = UserDefaultsNotificationMute

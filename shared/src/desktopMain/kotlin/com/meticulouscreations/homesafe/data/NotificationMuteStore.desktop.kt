package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.DesktopStorage
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.NotificationMute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The switch in the app's preferences. One per process, so every reader sees every change. */
private object PreferencesNotificationMute : NotificationMute {
    private const val KEY = "notifications_muted"
    private val preferences = DesktopStorage.preferences("notifications")

    private val state = MutableStateFlow(preferences.getBoolean(KEY, false))

    override val muted: StateFlow<Boolean> = state.asStateFlow()

    override fun setMuted(muted: Boolean) {
        state.value = muted
        preferences.putBoolean(KEY, muted)
        preferences.flush()
    }
}

actual fun createNotificationMute(platformContext: PlatformContext): NotificationMute = PreferencesNotificationMute

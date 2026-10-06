package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.alerts_title
import homesafe.shared.generated.resources.settings_alerts_subtitle
import org.jetbrains.compose.resources.stringResource

/**
 * This device's alert preferences, one tap below Settings: the notifications switch and, once
 * it's on, what to hear about and when ([AlertsSection]). With every zone's rules showing it runs
 * to a couple of screens, which is why the main page carries only the one-line [alertsSummary].
 */
@Composable
fun AlertsSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // "Open notification settings" leaves the app from this page, and comes back to it.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationPermission()
        onPauseOrDispose { }
    }

    SettingsPage(
        title = stringResource(Res.string.alerts_title),
        subtitle = stringResource(Res.string.settings_alerts_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        AlertsSection(
            state = state,
            onPushNotifications = viewModel::setPushNotifications,
            onZoneCategory = viewModel::setZoneCategory,
            onPreset = viewModel::applyAlertPreset,
            onQuietHours = viewModel::setQuietHours,
            onOnlyWhenAway = viewModel::setOnlyWhenAway,
            onQuietFamiliar = viewModel::setQuietFamiliarPeople,
            onLoadVolume = viewModel::loadAlertVolume,
            onOpenSettings = viewModel::openNotificationSettings,
            onSendTest = viewModel::sendTestNotification,
        )
    }
}

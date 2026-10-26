package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.finance.domain.BudgetAlertSwitches
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.viewmodel.BudgetAlertsState
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.alerts_finance_loading
import homesafe.shared.generated.resources.alerts_mute_all
import homesafe.shared.generated.resources.alerts_mute_all_off
import homesafe.shared.generated.resources.alerts_mute_all_on
import homesafe.shared.generated.resources.alerts_section_finance
import homesafe.shared.generated.resources.alerts_section_weather
import homesafe.shared.generated.resources.alerts_title
import homesafe.shared.generated.resources.common_retry
import homesafe.shared.generated.resources.doors_settings_summary
import homesafe.shared.generated.resources.doors_title
import homesafe.shared.generated.resources.fin_budget_settings_alert_buckets
import homesafe.shared.generated.resources.fin_budget_settings_alert_buckets_note
import homesafe.shared.generated.resources.fin_budget_settings_alert_savings
import homesafe.shared.generated.resources.fin_budget_settings_alert_savings_note
import homesafe.shared.generated.resources.fin_budget_settings_alert_total
import homesafe.shared.generated.resources.fin_budget_settings_alert_total_note
import homesafe.shared.generated.resources.fin_budget_settings_alerts_note
import homesafe.shared.generated.resources.fin_budget_settings_alerts_subtitle
import homesafe.shared.generated.resources.settings_alerts_subtitle
import homesafe.shared.generated.resources.weather_settings_notices
import homesafe.shared.generated.resources.weather_settings_notices_blocked
import homesafe.shared.generated.resources.weather_settings_notices_body
import homesafe.shared.generated.resources.weather_settings_notices_extremes
import homesafe.shared.generated.resources.weather_settings_notices_extremes_body
import homesafe.shared.generated.resources.weather_settings_notices_outlook
import homesafe.shared.generated.resources.weather_settings_notices_outlook_body
import homesafe.shared.generated.resources.weather_settings_notices_place
import homesafe.shared.generated.resources.weather_settings_notices_severe
import homesafe.shared.generated.resources.weather_settings_notices_severe_body
import homesafe.shared.generated.resources.weather_settings_notices_soon
import homesafe.shared.generated.resources.weather_settings_notices_soon_body
import homesafe.shared.generated.resources.weather_settings_notices_turn_on
import homesafe.shared.generated.resources.weather_settings_notices_unsupported
import org.jetbrains.compose.resources.stringResource

/**
 * What this phone is told about, by every app, one tap below Settings: one switch that mutes the
 * lot ([MuteAllCard]), then a card for each app that sends notifications. The cameras' comes first ([AlertsSection]); with every zone's rules showing
 * it runs to a couple of screens, which is why the main page carries only the one-line
 * [alertsSummary]. Then the doors, whose switches are per door and so stay on their own page; the
 * weather app's switches; and the budget's alerts, which are the household's and are read from
 * the relay each time the page is opened. The weather and budget switches are the same ones those
 * apps' own settings show.
 */
@Composable
fun NotificationsSettingsScreen(onBack: () -> Unit, onOpenDoors: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // "Open notification settings" leaves the app from this page, and comes back to it; the
    // budget's switches may have been changed by the other phone, or in Finance, meanwhile.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationPermission()
        viewModel.refreshBudgetAlerts()
        onPauseOrDispose { }
    }

    SettingsPage(
        title = stringResource(Res.string.alerts_title),
        subtitle = stringResource(Res.string.settings_alerts_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (state.notificationsSupported) MuteAllCard(muted = state.notificationsMuted, onMute = viewModel::setNotificationsMuted)
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
        OtherAppsNotifications(
            state = state,
            onOpenDoors = onOpenDoors,
            onWeatherNotices = viewModel::setWeatherNotices,
            onAllowNotifications = viewModel::allowNotifications,
            onBudgetAlerts = viewModel::setBudgetAlerts,
            onRetryBudget = viewModel::refreshBudgetAlerts,
        )
    }
}

/**
 * "Mute all notifications", over every app's card. Muted, the relay pushes this phone nothing and
 * the app posts nothing of its own (see `NotificationMute`); the cards below keep their choices,
 * so turning it off puts things back as they were.
 */
@Composable
internal fun MuteAllCard(muted: Boolean, onMute: (Boolean) -> Unit) {
    SettingsCard {
        SettingsToggleRow(
            title = stringResource(Res.string.alerts_mute_all),
            description = stringResource(if (muted) Res.string.alerts_mute_all_on else Res.string.alerts_mute_all_off),
            checked = muted,
            onCheckedChange = onMute,
        )
    }
}

/** Every card on the Notifications page after the cameras': the doors, the weather app and the budget. */
@Composable
internal fun OtherAppsNotifications(
    state: SettingsUiState,
    onOpenDoors: () -> Unit,
    onWeatherNotices: (WeatherNoticeSettings) -> Unit,
    onAllowNotifications: () -> Unit,
    onBudgetAlerts: (BudgetAlertSwitches) -> Unit,
    onRetryBudget: () -> Unit,
) {
    // One emitter at the top level; the spacing matches the page's between its cards.
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsGroup {
            SettingsNavRow(
                icon = Icons.Filled.DoorFront,
                title = stringResource(Res.string.doors_title),
                summary = stringResource(Res.string.doors_settings_summary),
                onClick = onOpenDoors,
            )
        }
        WeatherNotificationsSection(state, onWeatherNotices, onAllowNotifications)
        BudgetAlertsSection(state.budgetAlerts, onBudgetAlerts, onRetryBudget)
    }
}

/**
 * The weather app's switches, as its own settings have them: the whole thing, then each kind,
 * which are only choices while the whole thing is on. The permission is the phone's one, shared
 * with the cameras': while it's wanted here but not given (never asked, or refused) the card says
 * so, with a button to let them through, as the weather app's own settings do.
 */
@Composable
private fun WeatherNotificationsSection(state: SettingsUiState, onChange: (WeatherNoticeSettings) -> Unit, onAllow: () -> Unit) {
    SettingsSection(title = stringResource(Res.string.alerts_section_weather), icon = Icons.Filled.WbSunny) {
        if (!state.notificationsSupported) {
            SettingsCaption(stringResource(Res.string.weather_settings_notices_unsupported))
            return@SettingsSection
        }
        val notices = state.weatherNotices
        SettingsToggleRow(
            title = stringResource(Res.string.weather_settings_notices),
            description = stringResource(Res.string.weather_settings_notices_body),
            checked = notices.enabled,
            onCheckedChange = { onChange(notices.copy(enabled = it)) },
        )
        if (notices.enabled && state.notificationPermission != NotificationPermission.GRANTED) {
            SettingsCaption(stringResource(Res.string.weather_settings_notices_blocked), error = true)
            OutlinedButton(onClick = onAllow) { Text(stringResource(Res.string.weather_settings_notices_turn_on)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        SettingsToggleRow(
            title = stringResource(Res.string.weather_settings_notices_soon),
            description = stringResource(Res.string.weather_settings_notices_soon_body),
            checked = notices.precipitationSoon,
            enabled = notices.enabled,
            onCheckedChange = { onChange(notices.copy(precipitationSoon = it)) },
        )
        SettingsToggleRow(
            title = stringResource(Res.string.weather_settings_notices_outlook),
            description = stringResource(Res.string.weather_settings_notices_outlook_body),
            checked = notices.dailyOutlook,
            enabled = notices.enabled,
            onCheckedChange = { onChange(notices.copy(dailyOutlook = it)) },
        )
        SettingsToggleRow(
            title = stringResource(Res.string.weather_settings_notices_severe),
            description = stringResource(Res.string.weather_settings_notices_severe_body),
            checked = notices.severeAlerts,
            enabled = notices.enabled,
            onCheckedChange = { onChange(notices.copy(severeAlerts = it)) },
        )
        // The extremes ride along with the look ahead, so they go quiet with it.
        SettingsToggleRow(
            title = stringResource(Res.string.weather_settings_notices_extremes),
            description = stringResource(Res.string.weather_settings_notices_extremes_body),
            checked = notices.extremes,
            enabled = notices.enabled && notices.dailyOutlook,
            onCheckedChange = { onChange(notices.copy(extremes = it)) },
        )
        SettingsCaption(stringResource(Res.string.weather_settings_notices_place))
    }
}

/**
 * The budget's three alerts, as Finance's budget settings have them. They are the household's,
 * so the card says what that means for this phone; with no budget on the relay, or none this
 * account may see, there is no card.
 */
@Composable
private fun BudgetAlertsSection(budget: BudgetAlertsState, onChange: (BudgetAlertSwitches) -> Unit, onRetry: () -> Unit) {
    if (budget == BudgetAlertsState.None) return
    SettingsSection(title = stringResource(Res.string.alerts_section_finance), icon = Icons.Filled.AccountBalanceWallet) {
        SettingsCaption(stringResource(Res.string.fin_budget_settings_alerts_subtitle))
        when (budget) {
            BudgetAlertsState.None -> Unit

            BudgetAlertsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                SettingsCaption(stringResource(Res.string.alerts_finance_loading))
            }

            is BudgetAlertsState.Failed -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingsCaption(budget.error.resolve(), error = true, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
            }

            is BudgetAlertsState.Ready -> {
                val alerts = budget.alerts
                SettingsToggleRow(
                    title = stringResource(Res.string.fin_budget_settings_alert_total),
                    description = stringResource(Res.string.fin_budget_settings_alert_total_note),
                    checked = alerts.total,
                    enabled = !budget.saving,
                    onCheckedChange = { onChange(alerts.copy(total = it)) },
                )
                SettingsToggleRow(
                    title = stringResource(Res.string.fin_budget_settings_alert_savings),
                    description = stringResource(Res.string.fin_budget_settings_alert_savings_note),
                    checked = alerts.savings,
                    enabled = !budget.saving,
                    onCheckedChange = { onChange(alerts.copy(savings = it)) },
                )
                SettingsToggleRow(
                    title = stringResource(Res.string.fin_budget_settings_alert_buckets),
                    description = stringResource(Res.string.fin_budget_settings_alert_buckets_note),
                    checked = alerts.buckets,
                    enabled = !budget.saving,
                    onCheckedChange = { onChange(alerts.copy(buckets = it)) },
                )
                budget.error?.let { SettingsCaption(it.resolve(), error = true) }
                SettingsCaption(stringResource(Res.string.fin_budget_settings_alerts_note))
            }
        }
    }
}

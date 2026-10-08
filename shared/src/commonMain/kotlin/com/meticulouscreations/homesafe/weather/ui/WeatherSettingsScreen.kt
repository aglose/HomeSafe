package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.WeatherUiState
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.TemperatureUnit
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_alert_from
import homesafe.shared.generated.resources.weather_alert_issued_by
import homesafe.shared.generated.resources.weather_alert_until
import homesafe.shared.generated.resources.weather_alert_what_to_do
import homesafe.shared.generated.resources.weather_settings_about
import homesafe.shared.generated.resources.weather_settings_about_body
import homesafe.shared.generated.resources.weather_settings_celsius
import homesafe.shared.generated.resources.weather_settings_fahrenheit
import homesafe.shared.generated.resources.weather_settings_imperial
import homesafe.shared.generated.resources.weather_settings_measures
import homesafe.shared.generated.resources.weather_settings_metric
import homesafe.shared.generated.resources.weather_settings_notices
import homesafe.shared.generated.resources.weather_settings_notices_blocked
import homesafe.shared.generated.resources.weather_settings_notices_body
import homesafe.shared.generated.resources.weather_settings_notices_extremes
import homesafe.shared.generated.resources.weather_settings_notices_extremes_body
import homesafe.shared.generated.resources.weather_settings_notices_group
import homesafe.shared.generated.resources.weather_settings_notices_outlook
import homesafe.shared.generated.resources.weather_settings_notices_outlook_body
import homesafe.shared.generated.resources.weather_settings_notices_place
import homesafe.shared.generated.resources.weather_settings_notices_severe
import homesafe.shared.generated.resources.weather_settings_notices_severe_body
import homesafe.shared.generated.resources.weather_settings_notices_soon
import homesafe.shared.generated.resources.weather_settings_notices_soon_body
import homesafe.shared.generated.resources.weather_settings_notices_turn_on
import homesafe.shared.generated.resources.weather_settings_notices_unsupported
import homesafe.shared.generated.resources.weather_settings_sky
import homesafe.shared.generated.resources.weather_settings_still_sky
import homesafe.shared.generated.resources.weather_settings_still_sky_body
import homesafe.shared.generated.resources.weather_settings_temperature
import homesafe.shared.generated.resources.weather_settings_units
import org.jetbrains.compose.resources.stringResource

/**
 * The weather app's settings: the units its numbers are written in, which notifications it
 * sends (and, when the OS is in the way of them, how to let them through), whether the sky
 * moves, and where the data comes from.
 */
@Composable
internal fun WeatherSettingsScreen(
    state: WeatherUiState,
    padding: PaddingValues,
    onUnitsChange: (WeatherUnits) -> Unit,
    onNoticesChange: (WeatherNoticeSettings) -> Unit,
    onStillSkyChange: (Boolean) -> Unit,
    onAllowNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val units = state.preferences.units
    val notices = state.preferences.notices
    Column(
        modifier.fillMaxSize().background(colors.surface).verticalScroll(rememberScrollState()).padding(padding).testTag("weather_settings"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsGroup(stringResource(Res.string.weather_settings_units)) {
            ChoiceRow(
                stringResource(Res.string.weather_settings_temperature),
                listOf(stringResource(Res.string.weather_settings_fahrenheit), stringResource(Res.string.weather_settings_celsius)),
                units.temperature.ordinal,
                "weather_units_temperature",
            ) { onUnitsChange(units.copy(temperature = TemperatureUnit.entries[it])) }
            Spacer(Modifier.height(12.dp))
            ChoiceRow(
                stringResource(Res.string.weather_settings_measures),
                listOf(stringResource(Res.string.weather_settings_imperial), stringResource(Res.string.weather_settings_metric)),
                units.measures.ordinal,
                "weather_units_measures",
            ) { onUnitsChange(units.copy(measures = MeasureSystem.entries[it])) }
        }

        SettingsGroup(stringResource(Res.string.weather_settings_notices_group)) {
            when (state.notificationPermission) {
                null -> Text(stringResource(Res.string.weather_settings_notices_unsupported), style = type.body, color = colors.onSkyMuted)

                else -> {
                    SwitchRow(stringResource(Res.string.weather_settings_notices), stringResource(Res.string.weather_settings_notices_body), notices.enabled, "weather_notices_enabled") {
                        onNoticesChange(notices.copy(enabled = it))
                    }
                    if (notices.enabled && state.notificationPermission != NotificationPermission.GRANTED) {
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.watch.copy(alpha = 0.14f)).border(1.dp, colors.watch.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp)) {
                            Text(stringResource(Res.string.weather_settings_notices_blocked), style = type.body, color = colors.onSky)
                            Spacer(Modifier.height(10.dp))
                            PillButton(stringResource(Res.string.weather_settings_notices_turn_on), onAllowNotifications, Modifier.testTag("weather_notices_allow"))
                        }
                    }
                    // The kinds are only choices while the whole thing is on.
                    Column(Modifier.graphicsLayer { alpha = if (notices.enabled) 1f else 0.45f }) {
                        Spacer(Modifier.height(6.dp))
                        SwitchRow(stringResource(Res.string.weather_settings_notices_soon), stringResource(Res.string.weather_settings_notices_soon_body), notices.precipitationSoon, "weather_notices_soon", notices.enabled) {
                            onNoticesChange(notices.copy(precipitationSoon = it))
                        }
                        SwitchRow(stringResource(Res.string.weather_settings_notices_outlook), stringResource(Res.string.weather_settings_notices_outlook_body), notices.dailyOutlook, "weather_notices_outlook", notices.enabled) {
                            onNoticesChange(notices.copy(dailyOutlook = it))
                        }
                        SwitchRow(stringResource(Res.string.weather_settings_notices_severe), stringResource(Res.string.weather_settings_notices_severe_body), notices.severeAlerts, "weather_notices_severe", notices.enabled) {
                            onNoticesChange(notices.copy(severeAlerts = it))
                        }
                        SwitchRow(stringResource(Res.string.weather_settings_notices_extremes), stringResource(Res.string.weather_settings_notices_extremes_body), notices.extremes, "weather_notices_extremes", notices.enabled && notices.dailyOutlook) {
                            onNoticesChange(notices.copy(extremes = it))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(Res.string.weather_settings_notices_place), style = type.label, color = colors.onSkyFaint)
                    }
                }
            }
        }

        SettingsGroup(stringResource(Res.string.weather_settings_sky)) {
            SwitchRow(stringResource(Res.string.weather_settings_still_sky), stringResource(Res.string.weather_settings_still_sky_body), state.preferences.stillSky, "weather_still_sky", onChange = onStillSkyChange)
        }

        SettingsGroup(stringResource(Res.string.weather_settings_about)) {
            Text(stringResource(Res.string.weather_settings_about_body), style = type.body, color = colors.onSkyMuted)
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val colors = WeatherTheme.colors
    Column(Modifier.cardWidth().clip(WeatherCardShape).background(colors.surfaceRaised).border(1.dp, colors.hairline, WeatherCardShape).padding(16.dp)) {
        CardLabel(title)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** A label and a two-way choice beside it, as one control. */
@Composable
private fun ChoiceRow(label: String, options: List<String>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    val colors = WeatherTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = WeatherTheme.type.body, color = colors.onSky, modifier = Modifier.weight(1f))
        Row(Modifier.clip(CircleShape).background(colors.surface).border(1.dp, colors.hairline, CircleShape).padding(3.dp).selectableGroup()) {
            options.forEachIndexed { i, option ->
                val chosen = i == selected
                Text(
                    option,
                    style = WeatherTheme.type.bodyStrong,
                    color = if (chosen) Color(0xFF06121F) else colors.onSkyMuted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (chosen) colors.accent else Color.Transparent)
                        .selectable(selected = chosen, role = Role.RadioButton) { onSelect(i) }
                        .testTag("${tag}_$i")
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = WeatherTheme.colors
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).testTag(tag).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = WeatherTheme.type.bodyStrong, color = colors.onSky)
            Text(body, style = WeatherTheme.type.label, color = colors.onSkyMuted)
        }
        Spacer(Modifier.width(12.dp))
        // The row is the control; the switch only shows its state.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF06121F),
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.onSkyMuted,
                uncheckedTrackColor = colors.surface,
                uncheckedBorderColor = colors.hairline,
            ),
        )
    }
}

/**
 * A government warning in full, in the agency's own words: what, until when, and what to do.
 * [offsetAt] reads the place's clock at a moment, which for a warning running past a change of
 * the clocks is not the same from its start to its end.
 */
@Composable
internal fun AlertScreen(alert: WeatherAlert, offsetAt: (Long) -> Int, padding: PaddingValues, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    Column(
        modifier.fillMaxSize().background(colors.surface).verticalScroll(rememberScrollState()).padding(padding).testTag("weather_alert_detail"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.cardWidth().padding(horizontal = 6.dp)) {
            Text(alert.event, style = type.title, color = if (alert.isUrgent) colors.danger else colors.watch)
            Spacer(Modifier.height(6.dp))
            alert.onsetEpochSeconds?.let {
                Text(stringResource(Res.string.weather_alert_from, stringResource(WeatherFormat.weekday(it, offsetAt(it))), WeatherFormat.clock(it, offsetAt(it)).resolve()), style = type.label, color = colors.onSkyMuted)
            }
            alert.endsEpochSeconds?.let {
                Text(stringResource(Res.string.weather_alert_until, stringResource(WeatherFormat.weekday(it, offsetAt(it))), WeatherFormat.clock(it, offsetAt(it)).resolve()), style = type.label, color = colors.onSkyMuted)
            }
            if (alert.sender.isNotBlank()) {
                Text(stringResource(Res.string.weather_alert_issued_by, alert.sender), style = type.label, color = colors.onSkyFaint)
            }
            if (alert.headline.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(alert.headline, style = type.bodyStrong, color = colors.onSky)
            }
            if (alert.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(alert.description, style = type.body, color = colors.onSkyMuted)
            }
            if (alert.instruction.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                CardLabel(stringResource(Res.string.weather_alert_what_to_do))
                Spacer(Modifier.height(6.dp))
                Text(alert.instruction, style = type.body, color = colors.onSky)
            }
        }
    }
}

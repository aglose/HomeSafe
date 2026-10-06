package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.matchingPreset
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.settings_alerts_summary_blocked
import homesafe.shared.generated.resources.settings_alerts_summary_custom
import homesafe.shared.generated.resources.settings_alerts_summary_off
import homesafe.shared.generated.resources.settings_alerts_summary_on
import homesafe.shared.generated.resources.settings_alerts_summary_only_away
import homesafe.shared.generated.resources.settings_alerts_summary_quiet
import homesafe.shared.generated.resources.settings_alerts_summary_relay
import homesafe.shared.generated.resources.settings_alerts_summary_strangers
import homesafe.shared.generated.resources.settings_alerts_summary_unsupported
import homesafe.shared.generated.resources.settings_away_summary_automatic
import homesafe.shared.generated.resources.settings_away_summary_away
import homesafe.shared.generated.resources.settings_away_summary_checking
import homesafe.shared.generated.resources.settings_away_summary_home
import homesafe.shared.generated.resources.settings_away_summary_leaving
import homesafe.shared.generated.resources.settings_away_summary_nobody_home
import homesafe.shared.generated.resources.settings_away_summary_unreachable
import homesafe.shared.generated.resources.settings_cameras_loading
import homesafe.shared.generated.resources.settings_cameras_summary_all
import homesafe.shared.generated.resources.settings_cameras_summary_none
import homesafe.shared.generated.resources.settings_cameras_summary_off
import homesafe.shared.generated.resources.settings_cameras_summary_some

/*
 * The second line of each row on the main Settings page: how that section stands, so the page
 * answers "is everything as I left it?" without opening anything. The Server row's line is
 * serverSummary, beside its page.
 */

/**
 * The Alerts row: why nothing can arrive ("Blocked in system settings"), or "Off", or while
 * notifications are on what they amount to — "On · People only · quiet 10:00 PM – 7:00 AM". The
 * parts follow what the Alerts page shows: with only Away alerts left the rules decide nothing, so
 * that is all the line says; where the relay pushes, the relay stands in for the preset; and the
 * preset is left out until the cameras it is matched against have been read.
 */
internal fun alertsSummary(state: SettingsUiState): UiText {
    val alerts = state.alerts
    return when {
        !state.notificationsSupported -> UiText.of(Res.string.settings_alerts_summary_unsupported)

        state.notificationPermission == NotificationPermission.DENIED -> UiText.of(Res.string.settings_alerts_summary_blocked)

        !state.pushNotificationsActive -> UiText.of(Res.string.settings_alerts_summary_off)

        alerts.onlyWhenAway -> UiText.of(Res.string.settings_alerts_summary_only_away)

        else -> {
            val places = state.alertPlaces
            val rules = when {
                state.relayPushes -> UiText.of(Res.string.settings_alerts_summary_relay)
                places.isEmpty() -> null
                else -> alerts.matchingPreset(places)?.let { UiText.of(presetLabel(it)) } ?: UiText.of(Res.string.settings_alerts_summary_custom)
            }
            val quiet = alerts.quietHours.takeIf { it.enabled }?.let {
                UiText.of(Res.string.settings_alerts_summary_quiet, minuteOfDayText(it.startMinute), minuteOfDayText(it.endMinute))
            }
            // The stranger rule only does anything with face recognition on, which is also when its switch can be.
            val strangers = UiText.of(Res.string.settings_alerts_summary_strangers)
                .takeIf { alerts.quietFamiliarPeople && state.overview?.faceRecognitionEnabled == true }
            summaryOf(UiText.of(Res.string.settings_alerts_summary_on), rules, quiet, strangers)
        }
    }
}

/**
 * The Away mode row: the household first — "Nobody home" outranks anything about one phone —
 * then this phone as the relay has it, and "automatic" once the geofence is doing the flipping.
 */
internal fun awaySummary(state: SettingsUiState): UiText {
    val presence = state.presence
    val me = presence.thisDevice
    val status = when {
        presence == HouseholdPresence.EMPTY && state.awayError != null -> Res.string.settings_away_summary_unreachable
        presence == HouseholdPresence.EMPTY -> Res.string.settings_away_summary_checking
        presence.everyoneAway -> Res.string.settings_away_summary_nobody_home
        me?.pendingAway == true -> Res.string.settings_away_summary_leaving
        me?.away == true -> Res.string.settings_away_summary_away
        else -> Res.string.settings_away_summary_home
    }
    return summaryOf(UiText.of(status), UiText.of(Res.string.settings_away_summary_automatic).takeIf { state.automaticPresenceActive })
}

/**
 * The Cameras row: how many of the cameras the server runs have object detection on. A camera
 * switched off in Frigate's config isn't one it runs, so it is neither counted nor counted against.
 */
internal fun detectionSummary(overview: ServerOverview?): UiText {
    overview ?: return UiText.of(Res.string.settings_cameras_loading)
    val running = overview.cameras.filter { it.enabled }
    val detecting = running.count { it.detectionEnabled }
    return when {
        overview.cameras.isEmpty() -> UiText.of(Res.string.settings_cameras_summary_none)
        detecting == 0 -> UiText.of(Res.string.settings_cameras_summary_off)
        detecting == running.size -> UiText.plural(Res.plurals.settings_cameras_summary_all, running.size)
        else -> UiText.plural(Res.plurals.settings_cameras_summary_some, running.size, detecting, running.size)
    }
}

private fun summaryOf(vararg parts: UiText?): UiText = UiText.Joined(parts.filterNotNull(), separator = UiText.of(Res.string.common_dot_separator))

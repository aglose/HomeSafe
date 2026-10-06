package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.AlertZone
import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.QuietHours
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.alerts_preset_people_vehicles
import homesafe.shared.generated.resources.alerts_time_am
import homesafe.shared.generated.resources.alerts_time_pm
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
import homesafe.shared.generated.resources.settings_away_summary_not_registered
import homesafe.shared.generated.resources.settings_away_summary_unreachable
import homesafe.shared.generated.resources.settings_cameras_loading
import homesafe.shared.generated.resources.settings_cameras_summary_all
import homesafe.shared.generated.resources.settings_cameras_summary_none
import homesafe.shared.generated.resources.settings_cameras_summary_off
import homesafe.shared.generated.resources.settings_cameras_summary_some
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one line each row of the main Settings page says about its section: Alerts, Away mode and Cameras. */
class SettingsSummariesTest {

    private fun camera(name: String, enabled: Boolean = true, detecting: Boolean = true) = CameraPipeline(
        name = name,
        enabled = enabled,
        detectionEnabled = detecting,
        motionEnabled = true,
        cameraFps = 5.0,
        detectionFps = 1.0,
        skippedFps = 0.0,
    )

    private fun overview(cameras: List<CameraPipeline> = listOf(camera("front_door"), camera("driveway")), faceRecognition: Boolean = true) = ServerOverview(
        version = "0.17.2",
        latestVersion = "0.17.2",
        uptimeSeconds = 86_400,
        cpuPercent = 9.0,
        memoryPercent = 40.0,
        recordingsStorage = null,
        detector = null,
        gpus = emptyList(),
        retention = RetentionPolicy(7.0, 7.0, null, null),
        faceRecognitionEnabled = faceRecognition,
        licensePlateRecognitionEnabled = false,
        semanticSearchEnabled = false,
        cameras = cameras,
        canEditConfig = true,
    )

    private fun summary(vararg parts: UiText) = UiText.Joined(parts.toList(), UiText.of(Res.string.common_dot_separator))

    private fun text(res: StringResource) = UiText.of(res)

    // ---- Alerts ---------------------------------------------------------------------------------

    private val on = text(Res.string.settings_alerts_summary_on)

    /** Notifications allowed by the OS and switched on in the app. */
    private fun alertsOn(alerts: AlertSettings = AlertSettings(pushNotificationsEnabled = true), overview: ServerOverview? = overview(), relayPushes: Boolean = false) =
        SettingsUiState(
            overview = overview,
            alerts = alerts,
            notificationsSupported = true,
            notificationPermission = NotificationPermission.GRANTED,
            relayPushes = relayPushes,
        )

    @Test
    fun whenNothingCanArriveTheAlertsRowSaysWhy() {
        assertEquals(text(Res.string.settings_alerts_summary_unsupported), alertsSummary(SettingsUiState(notificationsSupported = false)))
        // Switched on in the app, but the OS says no: the OS wins.
        assertEquals(text(Res.string.settings_alerts_summary_blocked), alertsSummary(alertsOn().copy(notificationPermission = NotificationPermission.DENIED)))
        assertEquals(text(Res.string.settings_alerts_summary_off), alertsSummary(alertsOn(alerts = AlertSettings.DEFAULT)))
        // Wanted, but the OS hasn't been asked yet, so nothing arrives.
        assertEquals(text(Res.string.settings_alerts_summary_off), alertsSummary(alertsOn().copy(notificationPermission = NotificationPermission.NOT_DETERMINED)))
    }

    @Test
    fun withNotificationsOnTheAlertsRowNamesThePresetTheRulesAmountTo() {
        assertEquals(summary(on, text(Res.string.alerts_preset_people_vehicles)), alertsSummary(alertsOn()))

        val tuned = AlertSettings(pushNotificationsEnabled = true, zoneRules = mapOf(AlertZone("front_door", null) to setOf(MomentCategory.ANIMALS)))
        assertEquals(summary(on, text(Res.string.settings_alerts_summary_custom)), alertsSummary(alertsOn(alerts = tuned)))
    }

    @Test
    fun theRulesAreLeftOutOfTheAlertsRowWhereTheyDecideNothing() {
        // Until the cameras are read there is nothing to match a preset against.
        assertEquals(summary(on), alertsSummary(alertsOn(overview = null)))
        // A phone the relay pushes to hears what the relay decides.
        assertEquals(summary(on, text(Res.string.settings_alerts_summary_relay)), alertsSummary(alertsOn(relayPushes = true)))
        // With only Away alerts left, neither the rules nor quiet hours apply.
        val onlyAway = AlertSettings(pushNotificationsEnabled = true, onlyWhenAway = true, quietHours = QuietHours(enabled = true))
        assertEquals(text(Res.string.settings_alerts_summary_only_away), alertsSummary(alertsOn(alerts = onlyAway)))
    }

    @Test
    fun quietHoursAndTheStrangerRuleFollowThePresetOnTheAlertsRow() {
        val alerts = AlertSettings(
            pushNotificationsEnabled = true,
            quietFamiliarPeople = true,
            quietHours = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60 + 5),
        )
        val quiet = UiText.of(Res.string.settings_alerts_summary_quiet, UiText.of(Res.string.alerts_time_pm, 10, "00"), UiText.of(Res.string.alerts_time_am, 7, "05"))
        assertEquals(
            summary(on, text(Res.string.alerts_preset_people_vehicles), quiet, text(Res.string.settings_alerts_summary_strangers)),
            alertsSummary(alertsOn(alerts = alerts)),
        )
        // The stranger rule does nothing without face recognition, so the row doesn't claim it.
        assertEquals(
            summary(on, text(Res.string.alerts_preset_people_vehicles), quiet),
            alertsSummary(alertsOn(alerts = alerts, overview = overview(faceRecognition = false))),
        )
    }

    @Test
    fun aQuietWindowThatStartsWhereItEndsIsEmptySoTheAlertsRowLeavesItOut() {
        val alerts = AlertSettings(pushNotificationsEnabled = true, quietHours = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 22 * 60))
        assertEquals(summary(on, text(Res.string.alerts_preset_people_vehicles)), alertsSummary(alertsOn(alerts = alerts)))
    }

    // ---- Away mode ------------------------------------------------------------------------------

    private fun thisPhone(away: Boolean = false, pendingAway: Boolean = false) =
        PresenceDevice(name = "Google Pixel 10 Pro XL", platform = "android", away = away, isThisDevice = true, pendingAway = pendingAway, id = "pixel", build = "release")

    private val otherPhone = PresenceDevice(name = "Apple iPhone", platform = "ios", away = true, id = "iphone", build = "release")

    private fun household(me: PresenceDevice = thisPhone(), everyoneAway: Boolean = false, home: HomeLocation? = null) =
        HouseholdPresence(devices = listOf(me, otherPhone), everyoneAway = everyoneAway, home = home)

    @Test
    fun theAwayRowSaysWhereThisPhoneStands() {
        assertEquals(summary(text(Res.string.settings_away_summary_home)), awaySummary(SettingsUiState(presence = household())))
        assertEquals(summary(text(Res.string.settings_away_summary_leaving)), awaySummary(SettingsUiState(presence = household(thisPhone(pendingAway = true)))))
        assertEquals(summary(text(Res.string.settings_away_summary_away)), awaySummary(SettingsUiState(presence = household(thisPhone(away = true)))))
    }

    @Test
    fun anEmptyHouseOutranksThisPhoneOnTheAwayRow() {
        assertEquals(
            summary(text(Res.string.settings_away_summary_nobody_home)),
            awaySummary(SettingsUiState(presence = household(thisPhone(away = true), everyoneAway = true))),
        )
    }

    @Test
    fun untilTheRelayAnswersTheAwayRowSaysItIsChecking() {
        assertEquals(summary(text(Res.string.settings_away_summary_checking)), awaySummary(SettingsUiState()))
        assertEquals(summary(text(Res.string.settings_away_summary_unreachable)), awaySummary(SettingsUiState(awayError = "timeout".asUiText())))
        // A failed change with the household still listed is the page's to explain; the row keeps saying who's home.
        assertEquals(summary(text(Res.string.settings_away_summary_home)), awaySummary(SettingsUiState(presence = household(), awayError = "timeout".asUiText())))
    }

    @Test
    fun aHouseholdListedWithoutThisPhoneIsNotTakenForThisPhoneBeingHome() {
        val others = HouseholdPresence(devices = listOf(otherPhone.copy(away = false)), everyoneAway = false, home = HomeLocation(40.0, -75.0, 150.0))
        assertEquals(summary(text(Res.string.settings_away_summary_not_registered)), awaySummary(SettingsUiState(presence = others)))
        // Nor is anything "automatic" for a phone the relay has no switch for.
        val wanted = AlertSettings(pushNotificationsEnabled = false, automaticPresence = true)
        assertEquals(
            summary(text(Res.string.settings_away_summary_not_registered)),
            awaySummary(SettingsUiState(alerts = wanted, presence = others, locationAccess = LocationAccess.ALWAYS)),
        )
        // The household's answer still comes first.
        assertEquals(
            summary(text(Res.string.settings_away_summary_nobody_home)),
            awaySummary(SettingsUiState(presence = others.copy(devices = listOf(otherPhone), everyoneAway = true))),
        )
    }

    @Test
    fun theAwayRowSaysAutomaticOnlyOnceTheFenceCanWork() {
        val wanted = AlertSettings(pushNotificationsEnabled = false, automaticPresence = true)
        val home = HomeLocation(latitude = 40.0, longitude = -75.0, radiusMeters = 150.0)
        assertEquals(
            summary(text(Res.string.settings_away_summary_home), text(Res.string.settings_away_summary_automatic)),
            awaySummary(SettingsUiState(alerts = wanted, presence = household(home = home), locationAccess = LocationAccess.ALWAYS)),
        )
        // Switched on, but no home to draw the fence around, or no leave to watch it with the app closed.
        assertEquals(
            summary(text(Res.string.settings_away_summary_home)),
            awaySummary(SettingsUiState(alerts = wanted, presence = household(), locationAccess = LocationAccess.ALWAYS)),
        )
        assertEquals(
            summary(text(Res.string.settings_away_summary_home)),
            awaySummary(SettingsUiState(alerts = wanted, presence = household(home = home), locationAccess = LocationAccess.WHILE_IN_USE)),
        )
    }

    // ---- Cameras --------------------------------------------------------------------------------

    @Test
    fun theCamerasRowCountsTheCamerasThatAreDetecting() {
        assertEquals(UiText.plural(Res.plurals.settings_cameras_summary_all, 2), detectionSummary(overview()))
        assertEquals(
            UiText.plural(Res.plurals.settings_cameras_summary_some, 3, 2, 3),
            detectionSummary(overview(listOf(camera("front_door"), camera("driveway"), camera("back_yard", detecting = false)))),
        )
        assertEquals(text(Res.string.settings_cameras_summary_off), detectionSummary(overview(listOf(camera("front_door", detecting = false)))))
    }

    @Test
    fun aCameraSwitchedOffInConfigIsNotCountedAgainstTheRest() {
        val cameras = listOf(camera("front_door"), camera("driveway"), camera("old_garage", enabled = false, detecting = false))
        assertEquals(UiText.plural(Res.plurals.settings_cameras_summary_all, 2), detectionSummary(overview(cameras)))
    }

    @Test
    fun theCamerasRowSaysWhenThereIsNothingToCountYet() {
        assertEquals(text(Res.string.settings_cameras_loading), detectionSummary(null))
        assertEquals(text(Res.string.settings_cameras_summary_none), detectionSummary(overview(emptyList())))
    }
}

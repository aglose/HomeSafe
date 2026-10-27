package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.appicon.AppIcon
import com.meticulouscreations.homesafe.appicon.AppIconSwitcher
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertPreset
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.AlertVolume
import com.meticulouscreations.homesafe.domain.model.AlertZone
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.QuietHours
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.withPreset
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.platform.NotificationMute
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.domain.usecase.ClearHomeUseCase
import com.meticulouscreations.homesafe.domain.usecase.EstimateAlertVolumeUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierModelsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetNotificationPermissionUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveActiveConnectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveLocationAccessUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveRelayPushUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveServerOverviewErrorUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveServerOverviewUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.OpenNotificationSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshServerOverviewUseCase
import com.meticulouscreations.homesafe.domain.usecase.RemoveHouseholdDeviceUseCase
import com.meticulouscreations.homesafe.domain.usecase.RequestLocationAccessUseCase
import com.meticulouscreations.homesafe.domain.usecase.RequestNotificationPermissionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SendTestNotificationUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetAwayUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraDetectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraMotionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetHomeHereUseCase
import com.meticulouscreations.homesafe.domain.usecase.UpdateSettingsUseCase
import com.meticulouscreations.homesafe.finance.domain.BudgetAlertPreferences
import com.meticulouscreations.homesafe.finance.domain.BudgetAlertSwitches
import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
import com.meticulouscreations.homesafe.finance.domain.FinancePreferencesRepository
import com.meticulouscreations.homesafe.fitness.domain.FigureStyle
import com.meticulouscreations.homesafe.fitness.domain.FitnessPreferencesRepository
import com.meticulouscreations.homesafe.maps.BaseMaps
import com.meticulouscreations.homesafe.maps.MapProvider
import com.meticulouscreations.homesafe.maps.MapSettings
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import com.meticulouscreations.homesafe.vault.HouseholdKeys
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticePreferences
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.alerts_finance_error
import homesafe.shared.generated.resources.alerts_finance_save_error
import homesafe.shared.generated.resources.presence_error_location
import homesafe.shared.generated.resources.presence_error_not_connected
import homesafe.shared.generated.resources.presence_error_relay_refused
import homesafe.shared.generated.resources.presence_error_relay_unreachable
import homesafe.shared.generated.resources.presence_error_relay_unreachable_detail
import homesafe.shared.generated.resources.settings_camera_error_generic
import homesafe.shared.generated.resources.settings_camera_error_refused
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Settings tab shows, in one immutable snapshot. */
@Immutable
data class SettingsUiState(
    val connection: ActiveConnection? = null,
    /** Null until the first successful read of the server's stats and config. */
    val overview: ServerOverview? = null,
    /** The last poll's failure, if any — shown alongside whatever was loaded before it. */
    val overviewError: UiText? = null,
    val alerts: AlertSettings = AlertSettings.DEFAULT,
    val notificationsSupported: Boolean = false,
    val notificationPermission: NotificationPermission = NotificationPermission.NOT_DETERMINED,
    /** Cameras whose detection/motion switch is mid-flight; their switches lock until the server answers. */
    val busyCameras: Set<String> = emptySet(),
    /** Why the last switch flip failed, e.g. a viewer account hitting an admin-only call. */
    val cameraError: UiText? = null,
    /** True briefly after the test button, for an inline "Sent" confirmation. */
    val testNotificationSent: Boolean = false,
    /** The server's custom classifiers that can be taught in-app; empty while loading or when there are none. */
    val classifiers: List<ClassifierModel> = emptyList(),
    /** Away mode: who's home, per the relay. [HouseholdPresence.EMPTY] until it has answered. */
    val presence: HouseholdPresence = HouseholdPresence.EMPTY,
    /** True while the "I'm away" switch is mid-flight. */
    val awayBusy: Boolean = false,
    /** Why the relay couldn't be read or told, e.g. it's down. */
    val awayError: UiText? = null,
    /** The device being removed from the relay, by id; its row's remove button waits for the answer. */
    val removingDevice: String? = null,
    /** Automatic presence, always on for a tracked phone: whether a geofence is possible on this platform at all... */
    val geofenceSupported: Boolean = false,
    /** ...and how much location the phone lets us see. Only [LocationAccess.ALWAYS] makes the fence useful. */
    val locationAccess: LocationAccess = LocationAccess.UNAVAILABLE,
    /** True while "Set home here" is getting a fix and telling the relay. */
    val homeBusy: Boolean = false,
    val homeError: UiText? = null,
    /** How often each alert rule would have fired last week; null until read, and left null if the read fails. */
    val alertVolume: AlertVolume? = null,
    /**
     * The relay pushes this phone its alerts, so what it hears follows the relay's policy and the
     * per-zone rules (which only drive the in-app poller) decide nothing here.
     */
    val relayPushes: Boolean = false,
    /** How the finance app's Economy and Risk tabs talk about the economy. */
    val economyTone: EconomyTone = EconomyTone.DEFAULT,
    /** What the drawer's Finance card shows. */
    val financeDrawerItems: List<FinanceDrawerItem> = FinanceDrawerItem.DEFAULT,
    /** How the fitness app draws the figure that performs each exercise. */
    val figureStyle: FigureStyle = FigureStyle.DEFAULT,
    /** Whether this device lets the app change its home-screen icon; the row isn't shown when not. */
    val appIconsSupported: Boolean = false,
    /** The home-screen icon in use. */
    val appIcon: AppIcon = AppIcon.DEFAULT,
    /** Whose map the app's maps are drawn on, and how Google's is getting on when it's picked. */
    val maps: MapSettings = MapSettings(),
    /** Which of the weather app's notifications are wanted. They share this phone's one notification permission with the cameras'. */
    val weatherNotices: WeatherNoticeSettings = WeatherNoticeSettings(),
    /** The budget's alert switches, read from the relay each time the Notifications page is opened. */
    val budgetAlerts: BudgetAlertsState = BudgetAlertsState.Loading,
    /** "Mute all notifications": nothing from any app reaches this phone while it's on, whatever each app's switches say. */
    val notificationsMuted: Boolean = false,
) {
    /**
     * Every place the alert rules cover, in the order the Settings tab lists them: each enabled
     * camera's zones, then its "anywhere else". What a preset is applied to and matched against.
     */
    val alertPlaces: List<AlertZone>
        get() = overview?.cameras.orEmpty().filter { it.enabled }.flatMap { camera ->
            camera.zones.map { AlertZone(camera.name, it.name) } + AlertZone(camera.name, null)
        }

    /**
     * Everything the fence needs is in place: this is one of the tracked phones, location
     * "always", and a home to draw it around. There is no switch; this is only what's missing.
     */
    val automaticPresenceActive: Boolean
        get() = presence.thisDevice != null && geofenceSupported && locationAccess == LocationAccess.ALWAYS && presence.home != null

    /** This phone's switch. Optimistically nothing until the relay has listed this device. */
    val thisDeviceAway: Boolean get() = presence.thisDevice?.away == true

    /** The push switch is effectively on only when the OS also allows it. */
    val pushNotificationsActive: Boolean
        get() = alerts.pushNotificationsEnabled && notificationPermission == NotificationPermission.GRANTED

    /** The weather app's switch, likewise: on, and let through by the OS. */
    val weatherNoticesActive: Boolean
        get() = notificationsSupported && weatherNotices.enabled && notificationPermission == NotificationPermission.GRANTED
}

/** The budget's alert switches as the Notifications page has them from the relay. */
@Immutable
sealed interface BudgetAlertsState {
    /** Not read yet. */
    data object Loading : BudgetAlertsState

    /** No budget on the relay, or not this account's to see: the page leaves Finance out. */
    data object None : BudgetAlertsState

    /** The relay couldn't be asked, and nothing was read before. */
    data class Failed(val error: UiText) : BudgetAlertsState

    /** The switches; while [saving] they show what was asked for, and [error] says why the last save didn't take. */
    data class Ready(val alerts: BudgetAlertSwitches, val saving: Boolean = false, val error: UiText? = null) : BudgetAlertsState
}

/** Transient, screen-local state the flows above don't own. */
private data class LocalState(
    val permission: NotificationPermission = NotificationPermission.NOT_DETERMINED,
    val busyCameras: Set<String> = emptySet(),
    val cameraError: UiText? = null,
    val testNotificationSent: Boolean = false,
    val classifiers: List<ClassifierModel> = emptyList(),
    val awayBusy: Boolean = false,
    val awayError: UiText? = null,
    val removingDevice: String? = null,
    val homeBusy: Boolean = false,
    val homeError: UiText? = null,
    val alertVolume: AlertVolume? = null,
    val alertVolumeRequested: Boolean = false,
    val appIcon: AppIcon = AppIcon.DEFAULT,
    val budgetAlerts: BudgetAlertsState = BudgetAlertsState.Loading,
)

/** The drawer apps' preferences that Settings shows, as one value for [SettingsViewModel.uiState]'s combine. */
private data class AppPreferences(
    val tone: EconomyTone,
    val drawer: List<FinanceDrawerItem>,
    val figures: FigureStyle,
    val weatherNotices: WeatherNoticeSettings,
    val muted: Boolean,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class SettingsViewModel(
    observeSettingsUseCase: ObserveSettingsUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase,
    observeServerOverviewUseCase: ObserveServerOverviewUseCase,
    observeServerOverviewErrorUseCase: ObserveServerOverviewErrorUseCase,
    private val refreshServerOverviewUseCase: RefreshServerOverviewUseCase,
    private val setCameraDetectionUseCase: SetCameraDetectionUseCase,
    private val setCameraMotionUseCase: SetCameraMotionUseCase,
    observeActiveConnectionUseCase: ObserveActiveConnectionUseCase,
    private val getNotificationPermissionUseCase: GetNotificationPermissionUseCase,
    private val requestNotificationPermissionUseCase: RequestNotificationPermissionUseCase,
    private val openNotificationSettingsUseCase: OpenNotificationSettingsUseCase,
    private val sendTestNotificationUseCase: SendTestNotificationUseCase,
    private val getClassifierModelsUseCase: GetClassifierModelsUseCase,
    observeHouseholdPresenceUseCase: ObserveHouseholdPresenceUseCase,
    private val refreshHouseholdPresenceUseCase: RefreshHouseholdPresenceUseCase,
    private val setAwayUseCase: SetAwayUseCase,
    private val removeHouseholdDeviceUseCase: RemoveHouseholdDeviceUseCase,
    observeLocationAccessUseCase: ObserveLocationAccessUseCase,
    private val requestLocationAccessUseCase: RequestLocationAccessUseCase,
    private val setHomeHereUseCase: SetHomeHereUseCase,
    private val clearHomeUseCase: ClearHomeUseCase,
    private val estimateAlertVolumeUseCase: EstimateAlertVolumeUseCase,
    observeRelayPushUseCase: ObserveRelayPushUseCase,
    private val financePreferences: FinancePreferencesRepository,
    private val fitnessPreferences: FitnessPreferencesRepository,
    private val weatherNoticePreferences: WeatherNoticePreferences,
    private val budgetAlertPreferences: BudgetAlertPreferences,
    private val notificationMute: NotificationMute,
    private val appIcons: AppIconSwitcher,
    private val baseMaps: BaseMaps,
    private val householdKeys: HouseholdKeys,
) : ViewModel() {

    private val notificationsSupported: Boolean = getNotificationPermissionUseCase.isSupported
    private val geofenceSupported: Boolean = observeLocationAccessUseCase.geofenceSupported
    private val presence: StateFlow<HouseholdPresence> = observeHouseholdPresenceUseCase()
    private val locationAccess: StateFlow<LocationAccess> = observeLocationAccessUseCase()
    private val relayPushes: StateFlow<Boolean> = observeRelayPushUseCase()

    private val settings: StateFlow<AlertSettings> =
        observeSettingsUseCase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertSettings.DEFAULT)

    private val local = MutableStateFlow(LocalState(appIcon = appIcons.current()))

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            observeActiveConnectionUseCase(),
            observeServerOverviewUseCase(),
            observeServerOverviewErrorUseCase(),
            settings,
            local,
        ) { connection, overview, overviewError, alerts, local ->
            SettingsUiState(
                connection = connection,
                overview = overview,
                overviewError = overviewError,
                alerts = alerts,
                notificationsSupported = notificationsSupported,
                notificationPermission = local.permission,
                busyCameras = local.busyCameras,
                cameraError = local.cameraError,
                testNotificationSent = local.testNotificationSent,
                classifiers = local.classifiers,
                awayBusy = local.awayBusy,
                awayError = local.awayError,
                removingDevice = local.removingDevice,
                geofenceSupported = geofenceSupported,
                homeBusy = local.homeBusy,
                homeError = local.homeError,
                alertVolume = local.alertVolume,
                appIconsSupported = appIcons.supported,
                appIcon = local.appIcon,
                budgetAlerts = local.budgetAlerts,
            )
        },
        presence,
        locationAccess,
        relayPushes,
        // The drawer apps' own preferences, as one: there is room for five flows here.
        combine(
            financePreferences.observeEconomyTone(),
            financePreferences.observeDrawerItems(),
            fitnessPreferences.observeFigureStyle(),
            weatherNoticePreferences.observe(),
            notificationMute.muted,
            ::AppPreferences,
        ),
    ) { state, presence, access, pushed, apps ->
        state.copy(
            presence = presence,
            locationAccess = access,
            relayPushes = pushed,
            economyTone = apps.tone,
            financeDrawerItems = apps.drawer,
            figureStyle = apps.figures,
            weatherNotices = apps.weatherNotices,
            notificationsMuted = apps.muted,
        )
    }
        .combine(baseMaps.settings) { state, maps -> state.copy(maps = maps) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUiState(
                notificationsSupported = notificationsSupported,
                geofenceSupported = geofenceSupported,
                appIconsSupported = appIcons.supported,
                appIcon = local.value.appIcon,
            ),
        )

    init {
        refreshNotificationPermission()
        refreshClassifiers()
        // Someone may have saved a key, or let this login in, since the vault last looked.
        householdKeys.refresh()
        // So the Maps row can say whether Google's key works without a map being opened first.
        viewModelScope.launch { baseMaps.check() }
    }

    /** Re-reads the OS permission — called on every resume, since the user may have changed it in system settings. */
    fun refreshNotificationPermission() {
        if (!notificationsSupported) return
        viewModelScope.launch {
            val permission = getNotificationPermissionUseCase()
            local.update { it.copy(permission = permission) }
        }
    }

    fun retryOverview() {
        viewModelScope.launch { refreshServerOverviewUseCase() }
    }

    /**
     * Turning notifications on asks the OS first, and only records "on" if the OS agreed — a
     * switch that says on while nothing can ever arrive would be a lie. Turning off never asks.
     */
    fun setPushNotifications(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                updateSettingsUseCase(settings.value.copy(pushNotificationsEnabled = false))
                return@launch
            }
            val granted = getNotificationPermissionUseCase() == NotificationPermission.GRANTED || requestNotificationPermissionUseCase()
            local.update { it.copy(permission = getNotificationPermissionUseCase()) }
            updateSettingsUseCase(settings.value.copy(pushNotificationsEnabled = granted))
        }
    }

    fun setZoneCategory(place: AlertZone, category: MomentCategory, enabled: Boolean) {
        if (category == MomentCategory.ALL) return
        val updated = settings.value.withCategory(place, category, enabled)
        viewModelScope.launch { updateSettingsUseCase(updated) }
    }

    /** Rewrites every place's rules to [preset]'s; the grid below stays free to tune from there. */
    fun applyAlertPreset(preset: AlertPreset) {
        val places = uiState.value.alertPlaces
        if (places.isEmpty()) return
        val updated = settings.value.withPreset(preset, places)
        viewModelScope.launch { updateSettingsUseCase(updated) }
    }

    fun setQuietHours(quietHours: QuietHours) {
        val updated = settings.value.copy(quietHours = quietHours)
        viewModelScope.launch { updateSettingsUseCase(updated) }
    }

    fun setOnlyWhenAway(enabled: Boolean) {
        val updated = settings.value.copy(onlyWhenAway = enabled)
        viewModelScope.launch { updateSettingsUseCase(updated) }
    }

    /**
     * Reads last week's detections once per visit to the rules, for the "~40/day" warnings. Once
     * per view model: the numbers are a week's average, so a fresher read wouldn't say anything
     * new, and a failure just leaves the warnings out.
     */
    fun loadAlertVolume() {
        if (local.value.alertVolumeRequested) return
        local.update { it.copy(alertVolumeRequested = true) }
        viewModelScope.launch {
            val volume = estimateAlertVolumeUseCase().getOrNull()
            local.update { it.copy(alertVolume = volume) }
        }
    }

    fun setEconomyTone(tone: EconomyTone) {
        viewModelScope.launch { financePreferences.setEconomyTone(tone) }
    }

    fun setFinanceDrawerItems(items: List<FinanceDrawerItem>) {
        viewModelScope.launch { financePreferences.setDrawerItems(items) }
    }

    fun setFigureStyle(style: FigureStyle) {
        viewModelScope.launch { fitnessPreferences.setFigureStyle(style) }
    }

    fun setMapProvider(provider: MapProvider) {
        baseMaps.setProvider(provider)
        viewModelScope.launch { baseMaps.check() }
    }

    /** Seals the Google Maps key into the household's vault ([key] blank or null forgets it) and asks Google about it at once. */
    fun setGoogleMapsKey(key: String?) {
        viewModelScope.launch { if (baseMaps.setGoogleKey(key).isSuccess) baseMaps.check() }
    }

    /**
     * Changes which weather notifications are wanted. Turning them on asks the OS for permission
     * if it hasn't been given, as the cameras' switch does; the weather app's switch stays as set
     * either way, as it does in the weather app's own settings. The OS is asked first: switched
     * on, the weather app looks at the forecast at once, and that look has to find the answer.
     */
    fun setWeatherNotices(settings: WeatherNoticeSettings) {
        viewModelScope.launch {
            if (settings.enabled && notificationsSupported && getNotificationPermissionUseCase() != NotificationPermission.GRANTED) {
                requestNotificationPermissionUseCase()
                local.update { it.copy(permission = getNotificationPermissionUseCase()) }
            }
            weatherNoticePreferences.update { settings }
        }
    }

    /** Mutes, or unmutes, every notification on this phone; each app's own choices are left as they are. */
    fun setNotificationsMuted(muted: Boolean) = notificationMute.setMuted(muted)

    /** Lets notifications through: the OS's prompt if it can still be asked, its settings once it has said no. */
    fun allowNotifications() {
        if (!notificationsSupported) return
        viewModelScope.launch {
            if (getNotificationPermissionUseCase() == NotificationPermission.DENIED) openNotificationSettingsUseCase() else requestNotificationPermissionUseCase()
            local.update { it.copy(permission = getNotificationPermissionUseCase()) }
        }
    }

    /**
     * Re-reads the budget's alert switches — on every visit to the Notifications page, since the
     * other phone or the finance app may have changed them. Switches already read stay up while
     * the relay is asked again, and stay if it can't be reached.
     */
    fun refreshBudgetAlerts() {
        val askedOf = local.value.budgetAlerts
        viewModelScope.launch {
            val result = budgetAlertPreferences.read()
            local.update { current ->
                val answer: BudgetAlertsState = result.fold(
                    onSuccess = { alerts -> alerts?.let { BudgetAlertsState.Ready(it) } ?: BudgetAlertsState.None },
                    onFailure = { error ->
                        current.budgetAlerts.takeIf { it is BudgetAlertsState.Ready }
                            ?: BudgetAlertsState.Failed(error.userMessage(Res.string.alerts_finance_error))
                    },
                )
                // A switch flipped while the relay was being read, saved or still saving, has the newer word.
                if (current.budgetAlerts !== askedOf) current else current.copy(budgetAlerts = answer)
            }
        }
    }

    /** Saves the budget's alert switches on the relay; they show what was asked at once, and go back if it can't be saved. */
    fun setBudgetAlerts(alerts: BudgetAlertSwitches) {
        val before = local.value.budgetAlerts as? BudgetAlertsState.Ready ?: return
        if (before.saving) return
        local.update { it.copy(budgetAlerts = BudgetAlertsState.Ready(alerts, saving = true)) }
        viewModelScope.launch {
            val result = budgetAlertPreferences.save(alerts)
            val after = result.fold(
                onSuccess = { saved -> BudgetAlertsState.Ready(saved) },
                onFailure = { error -> BudgetAlertsState.Ready(before.alerts, error = error.userMessage(Res.string.alerts_finance_save_error)) },
            )
            local.update { it.copy(budgetAlerts = after) }
        }
    }

    /** Puts [icon] on the home screen; the choice shown is read back from the platform, so a refusal leaves the old one ticked. */
    fun setAppIcon(icon: AppIcon) {
        viewModelScope.launch {
            appIcons.set(icon)
            local.update { it.copy(appIcon = appIcons.current()) }
        }
    }

    /** Familiar vs. stranger: skip notifications for people Frigate has put a name to. */
    fun setQuietFamiliarPeople(enabled: Boolean) {
        val updated = settings.value.copy(quietFamiliarPeople = enabled)
        viewModelScope.launch { updateSettingsUseCase(updated) }
    }

    fun openNotificationSettings() = openNotificationSettingsUseCase()

    fun sendTestNotification() {
        viewModelScope.launch { sendTestNotificationUseCase() }
        local.update { it.copy(testNotificationSent = true) }
    }

    fun setCameraDetection(cameraName: String, enabled: Boolean) =
        flipCameraSwitch(cameraName) { setCameraDetectionUseCase(cameraName, enabled) }

    fun setCameraMotion(cameraName: String, enabled: Boolean) =
        flipCameraSwitch(cameraName) { setCameraMotionUseCase(cameraName, enabled) }

    fun dismissCameraError() = local.update { it.copy(cameraError = null) }

    /**
     * Re-reads the server's classifiers — on every visit to the tab, since the view model outlives
     * it and a classifier added in Frigate's own UI should show up without a relaunch. Only
     * classifiers that actually classify something get a row; a model with no objects has nothing to label.
     */
    fun refreshClassifiers() {
        viewModelScope.launch {
            val models = getClassifierModelsUseCase().getOrDefault(emptyList()).filter { it.objects.isNotEmpty() }
            local.update { it.copy(classifiers = models) }
        }
    }

    /**
     * Re-reads who's home — on every visit, since the other phone may have flipped its switch. A
     * failure here is what disables the away switch: a presence change the relay can't record
     * would mislead both phones.
     */
    fun refreshPresence() {
        viewModelScope.launch {
            val result = refreshHouseholdPresenceUseCase()
            local.update { it.copy(awayError = result.exceptionOrNull()?.let(::friendlyAwayError)) }
        }
    }

    /** Flips this phone's "I'm away" switch on the relay; the switch shows what the relay answered, not what was asked. */
    fun setAway(away: Boolean) {
        if (local.value.awayBusy) return
        local.update { it.copy(awayBusy = true, awayError = null) }
        viewModelScope.launch {
            val result = setAwayUseCase(away)
            local.update { it.copy(awayBusy = false, awayError = result.exceptionOrNull()?.let(::friendlyAwayError)) }
        }
    }

    /**
     * Forgets a tracked phone ([deviceId]) on the relay — one that was replaced — so the next
     * release phone of its platform takes its place. The Settings row asks first; this just does it.
     */
    fun removeDevice(deviceId: String) {
        if (local.value.removingDevice != null) return
        local.update { it.copy(removingDevice = deviceId, awayError = null) }
        viewModelScope.launch {
            val result = removeHouseholdDeviceUseCase(deviceId)
            local.update { it.copy(removingDevice = null, awayError = result.exceptionOrNull()?.let(::friendlyAwayError)) }
        }
    }

    /** The next step towards "Always" — the OS decides which prompt (or settings screen) that is. */
    fun requestLocationAccess() {
        viewModelScope.launch { requestLocationAccessUseCase() }
    }

    /** Makes where this phone is standing the household's home. */
    fun setHomeHere() {
        if (local.value.homeBusy) return
        local.update { it.copy(homeBusy = true, homeError = null) }
        viewModelScope.launch {
            val result = setHomeHereUseCase()
            local.update { it.copy(homeBusy = false, homeError = result.exceptionOrNull()?.let(::friendlyHomeError)) }
        }
    }

    fun clearHome() {
        if (local.value.homeBusy) return
        local.update { it.copy(homeBusy = true, homeError = null) }
        viewModelScope.launch {
            val result = clearHomeUseCase()
            local.update { it.copy(homeBusy = false, homeError = result.exceptionOrNull()?.let(::friendlyHomeError)) }
        }
    }

    /**
     * What to say about a failed relay call. Status codes and "Not connected" are matched in the
     * failure's technical message, which a [com.meticulouscreations.homesafe.text.LocalizedException]
     * keeps for exactly this; anything else shows as the failure explains itself.
     */
    private fun friendlyAwayError(error: Throwable): UiText {
        val message = error.message ?: return UiText.of(Res.string.presence_error_relay_unreachable)
        return when {
            "401" in message || "403" in message -> UiText.of(Res.string.presence_error_relay_refused)
            "Not connected" in message -> UiText.of(Res.string.presence_error_not_connected)
            else -> error.userMessage(Res.string.presence_error_relay_unreachable_detail)
        }
    }

    private fun friendlyHomeError(error: Throwable): UiText = when {
        error.message?.contains("location") == true -> UiText.of(Res.string.presence_error_location)
        else -> friendlyAwayError(error)
    }

    private fun flipCameraSwitch(cameraName: String, action: suspend () -> Result<Unit>) {
        if (cameraName in local.value.busyCameras) return
        local.update { it.copy(busyCameras = it.busyCameras + cameraName, cameraError = null) }
        viewModelScope.launch {
            val result = action()
            local.update {
                it.copy(
                    busyCameras = it.busyCameras - cameraName,
                    cameraError = result.exceptionOrNull()?.let(::friendlyCameraError),
                )
            }
        }
    }

    private fun friendlyCameraError(error: Throwable): UiText {
        val message = error.message ?: return UiText.of(Res.string.settings_camera_error_generic)
        return when {
            "401" in message || "403" in message -> UiText.of(Res.string.settings_camera_error_refused)
            else -> error.userMessage()
        }
    }
}

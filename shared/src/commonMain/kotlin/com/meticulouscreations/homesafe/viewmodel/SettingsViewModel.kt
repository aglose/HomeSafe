package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.AlertZone
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.domain.usecase.ClearHomeUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierModelsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetNotificationPermissionUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveActiveConnectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveLocationAccessUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveServerOverviewErrorUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveServerOverviewUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.OpenNotificationSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshServerOverviewUseCase
import com.meticulouscreations.homesafe.domain.usecase.RequestLocationAccessUseCase
import com.meticulouscreations.homesafe.domain.usecase.RequestNotificationPermissionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SendTestNotificationUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetAwayUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraDetectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraMotionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetHomeHereUseCase
import com.meticulouscreations.homesafe.domain.usecase.UpdateSettingsUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
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
    val overviewError: String? = null,
    val alerts: AlertSettings = AlertSettings.DEFAULT,
    val notificationsSupported: Boolean = false,
    val notificationPermission: NotificationPermission = NotificationPermission.NOT_DETERMINED,
    /** Cameras whose detection/motion switch is mid-flight; their switches lock until the server answers. */
    val busyCameras: Set<String> = emptySet(),
    /** Why the last switch flip failed, e.g. a viewer account hitting an admin-only call. */
    val cameraError: String? = null,
    /** True briefly after the test button, for an inline "Sent" confirmation. */
    val testNotificationSent: Boolean = false,
    /** The server's custom classifiers that can be taught in-app; empty while loading or when there are none. */
    val classifiers: List<ClassifierModel> = emptyList(),
    /** Away mode: who's home, per the relay. [HouseholdPresence.EMPTY] until it has answered. */
    val presence: HouseholdPresence = HouseholdPresence.EMPTY,
    /** True while the "I'm away" switch is mid-flight. */
    val awayBusy: Boolean = false,
    /** Why the relay couldn't be read or told, e.g. it's down. */
    val awayError: String? = null,
    /** Automatic presence: whether a geofence is possible on this platform at all... */
    val geofenceSupported: Boolean = false,
    /** ...and how much location the phone lets us see. Only [LocationAccess.ALWAYS] makes the fence useful. */
    val locationAccess: LocationAccess = LocationAccess.UNAVAILABLE,
    /** True while "Set home here" is getting a fix and telling the relay. */
    val homeBusy: Boolean = false,
    val homeError: String? = null,
) {
    /** The user's wish; see [automaticPresenceActive] for whether it can actually do anything. */
    val automaticPresence: Boolean get() = alerts.automaticPresence

    /** Everything the fence needs is in place: switched on, location "always", and a home to draw it around. */
    val automaticPresenceActive: Boolean
        get() = automaticPresence && locationAccess == LocationAccess.ALWAYS && presence.home != null

    /** This phone's switch. Optimistically nothing until the relay has listed this device. */
    val thisDeviceAway: Boolean get() = presence.thisDevice?.away == true

    /** The push switch is effectively on only when the OS also allows it. */
    val pushNotificationsActive: Boolean
        get() = alerts.pushNotificationsEnabled && notificationPermission == NotificationPermission.GRANTED
}

/** Transient, screen-local state the flows above don't own. */
private data class LocalState(
    val permission: NotificationPermission = NotificationPermission.NOT_DETERMINED,
    val busyCameras: Set<String> = emptySet(),
    val cameraError: String? = null,
    val testNotificationSent: Boolean = false,
    val classifiers: List<ClassifierModel> = emptyList(),
    val awayBusy: Boolean = false,
    val awayError: String? = null,
    val homeBusy: Boolean = false,
    val homeError: String? = null,
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
    observeLocationAccessUseCase: ObserveLocationAccessUseCase,
    private val requestLocationAccessUseCase: RequestLocationAccessUseCase,
    private val setHomeHereUseCase: SetHomeHereUseCase,
    private val clearHomeUseCase: ClearHomeUseCase,
) : ViewModel() {

    private val notificationsSupported: Boolean = getNotificationPermissionUseCase.isSupported
    private val geofenceSupported: Boolean = observeLocationAccessUseCase.geofenceSupported
    private val presence: StateFlow<HouseholdPresence> = observeHouseholdPresenceUseCase()
    private val locationAccess: StateFlow<LocationAccess> = observeLocationAccessUseCase()

    private val settings: StateFlow<AlertSettings> =
        observeSettingsUseCase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertSettings.DEFAULT)

    private val local = MutableStateFlow(LocalState())

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
                geofenceSupported = geofenceSupported,
                homeBusy = local.homeBusy,
                homeError = local.homeError,
            )
        },
        presence,
        locationAccess,
    ) { state, presence, access -> state.copy(presence = presence, locationAccess = access) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUiState(notificationsSupported = notificationsSupported, geofenceSupported = geofenceSupported),
        )

    init {
        refreshNotificationPermission()
        refreshClassifiers()
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

    /** Familiar vs. stranger: skip notifications for people Frigate has put a name to. */
    fun setQuietFamiliarPeople(enabled: Boolean) {
        val updated = settings.value.copy(quietFamiliarPeople = enabled)
        viewModelScope.launch { updateSettingsUseCase(updated) }
    }

    fun openNotificationSettings() = openNotificationSettingsUseCase()

    fun sendTestNotification() {
        sendTestNotificationUseCase()
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
     * Automatic presence. Turning it on also asks for the location access it needs, since a
     * switch that's on but can't see the phone leave would do nothing quietly; the section's
     * captions say what's still missing after the prompt.
     */
    fun setAutomaticPresence(enabled: Boolean) {
        viewModelScope.launch {
            updateSettingsUseCase(settings.value.copy(automaticPresence = enabled))
            if (enabled && locationAccess.value != LocationAccess.ALWAYS) requestLocationAccessUseCase()
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

    private fun friendlyAwayError(error: Throwable): String {
        val message = error.message ?: return "Couldn't reach the push relay."
        return when {
            "401" in message || "403" in message -> "The relay refused this session. Sign in again."
            "Not connected" in message -> "Not connected to a server."
            else -> "Couldn't reach the push relay: $message"
        }
    }

    private fun friendlyHomeError(error: Throwable): String = when {
        error.message?.contains("location") == true -> "Couldn't get this phone's location. Is location on?"
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
                    cameraError = result.exceptionOrNull()?.let { error -> friendlyCameraError(error.message) },
                )
            }
        }
    }

    private fun friendlyCameraError(message: String?): String = when {
        message == null -> "Couldn't change that setting."
        "401" in message || "403" in message -> "Frigate refused: this account can't change config. Sign in with an admin account."
        else -> message
    }
}

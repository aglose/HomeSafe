package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.AlertZone
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierModelsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetNotificationPermissionUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveActiveConnectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveServerOverviewErrorUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveServerOverviewUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.OpenNotificationSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshServerOverviewUseCase
import com.meticulouscreations.homesafe.domain.usecase.RequestNotificationPermissionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SendTestNotificationUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraDetectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetCameraMotionUseCase
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
) {
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
) : ViewModel() {

    private val notificationsSupported: Boolean = getNotificationPermissionUseCase.isSupported

    private val settings: StateFlow<AlertSettings> =
        observeSettingsUseCase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertSettings.DEFAULT)

    private val local = MutableStateFlow(LocalState())

    val uiState: StateFlow<SettingsUiState> = combine(
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(notificationsSupported = notificationsSupported))

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

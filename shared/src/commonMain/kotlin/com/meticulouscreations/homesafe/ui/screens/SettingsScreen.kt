package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.appicon.AppIcon
import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.NotTrackedReason
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.formatLastSeen
import com.meticulouscreations.homesafe.domain.model.formatMegabytes
import com.meticulouscreations.homesafe.domain.model.formatPercent
import com.meticulouscreations.homesafe.domain.model.formatRetentionDays
import com.meticulouscreations.homesafe.domain.model.formatUptime
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
import com.meticulouscreations.homesafe.fitness.domain.FigureStyle
import com.meticulouscreations.homesafe.maps.MapProvider
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.alerts_title
import homesafe.shared.generated.resources.common_cancel
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_retry
import homesafe.shared.generated.resources.doors_settings_summary
import homesafe.shared.generated.resources.doors_title
import homesafe.shared.generated.resources.finance_drawer_settings_title
import homesafe.shared.generated.resources.fitness_figure_settings_title
import homesafe.shared.generated.resources.maps_settings_title
import homesafe.shared.generated.resources.presence_allow_location
import homesafe.shared.generated.resources.presence_allow_location_always
import homesafe.shared.generated.resources.presence_allow_location_settings
import homesafe.shared.generated.resources.presence_automatic
import homesafe.shared.generated.resources.presence_automatic_needs_always
import homesafe.shared.generated.resources.presence_automatic_needs_home
import homesafe.shared.generated.resources.presence_automatic_not_tracked
import homesafe.shared.generated.resources.presence_automatic_on
import homesafe.shared.generated.resources.presence_automatic_unsupported
import homesafe.shared.generated.resources.presence_caption
import homesafe.shared.generated.resources.presence_clear_home
import homesafe.shared.generated.resources.presence_detail_this_phone
import homesafe.shared.generated.resources.presence_home_not_set
import homesafe.shared.generated.resources.presence_home_set
import homesafe.shared.generated.resources.presence_im_away
import homesafe.shared.generated.resources.presence_im_away_away
import homesafe.shared.generated.resources.presence_im_away_checking
import homesafe.shared.generated.resources.presence_im_away_home
import homesafe.shared.generated.resources.presence_im_away_leaving
import homesafe.shared.generated.resources.presence_im_away_relay_unreachable
import homesafe.shared.generated.resources.presence_move_home_here
import homesafe.shared.generated.resources.presence_no_android_phone
import homesafe.shared.generated.resources.presence_no_iphone
import homesafe.shared.generated.resources.presence_nobody_home
import homesafe.shared.generated.resources.presence_not_tracked_debug
import homesafe.shared.generated.resources.presence_not_tracked_emulator
import homesafe.shared.generated.resources.presence_not_tracked_other_phone
import homesafe.shared.generated.resources.presence_not_tracked_unknown
import homesafe.shared.generated.resources.presence_remove
import homesafe.shared.generated.resources.presence_remove_body
import homesafe.shared.generated.resources.presence_remove_device
import homesafe.shared.generated.resources.presence_remove_title
import homesafe.shared.generated.resources.presence_set_home_here
import homesafe.shared.generated.resources.presence_status_away
import homesafe.shared.generated.resources.presence_status_away_since
import homesafe.shared.generated.resources.presence_status_home
import homesafe.shared.generated.resources.presence_status_leaving
import homesafe.shared.generated.resources.presence_title
import homesafe.shared.generated.resources.presence_unnamed_device
import homesafe.shared.generated.resources.settings_ai_caption
import homesafe.shared.generated.resources.settings_ai_face_recognition
import homesafe.shared.generated.resources.settings_ai_license_plates
import homesafe.shared.generated.resources.settings_ai_loading
import homesafe.shared.generated.resources.settings_ai_semantic_search
import homesafe.shared.generated.resources.settings_ai_title
import homesafe.shared.generated.resources.settings_app_icon_title
import homesafe.shared.generated.resources.settings_camera_detections_per_second
import homesafe.shared.generated.resources.settings_camera_disabled
import homesafe.shared.generated.resources.settings_camera_fps
import homesafe.shared.generated.resources.settings_camera_no_stats
import homesafe.shared.generated.resources.settings_camera_skipped
import homesafe.shared.generated.resources.settings_cameras_loading
import homesafe.shared.generated.resources.settings_cameras_title
import homesafe.shared.generated.resources.settings_cameras_viewer_read_only
import homesafe.shared.generated.resources.settings_dismiss
import homesafe.shared.generated.resources.settings_millis
import homesafe.shared.generated.resources.settings_motion_detection
import homesafe.shared.generated.resources.settings_motion_needed
import homesafe.shared.generated.resources.settings_motion_off
import homesafe.shared.generated.resources.settings_motion_on
import homesafe.shared.generated.resources.settings_object_detection
import homesafe.shared.generated.resources.settings_object_detection_off
import homesafe.shared.generated.resources.settings_object_detection_on
import homesafe.shared.generated.resources.settings_off
import homesafe.shared.generated.resources.settings_on
import homesafe.shared.generated.resources.settings_retention_alerts
import homesafe.shared.generated.resources.settings_retention_caption
import homesafe.shared.generated.resources.settings_retention_continuous
import homesafe.shared.generated.resources.settings_retention_default
import homesafe.shared.generated.resources.settings_retention_detections
import homesafe.shared.generated.resources.settings_retention_motion
import homesafe.shared.generated.resources.settings_server_connected_to
import homesafe.shared.generated.resources.settings_server_cpu
import homesafe.shared.generated.resources.settings_server_detector
import homesafe.shared.generated.resources.settings_server_detector_input_size
import homesafe.shared.generated.resources.settings_server_gpu
import homesafe.shared.generated.resources.settings_server_gpu_busy
import homesafe.shared.generated.resources.settings_server_gpu_decoder
import homesafe.shared.generated.resources.settings_server_gpu_memory
import homesafe.shared.generated.resources.settings_server_inference
import homesafe.shared.generated.resources.settings_server_last_refresh_failed
import homesafe.shared.generated.resources.settings_server_loading
import homesafe.shared.generated.resources.settings_server_memory
import homesafe.shared.generated.resources.settings_server_no_inference
import homesafe.shared.generated.resources.settings_server_route_local
import homesafe.shared.generated.resources.settings_server_route_tailscale_local_unreachable
import homesafe.shared.generated.resources.settings_server_title
import homesafe.shared.generated.resources.settings_server_unreachable
import homesafe.shared.generated.resources.settings_server_update_available
import homesafe.shared.generated.resources.settings_server_uptime
import homesafe.shared.generated.resources.settings_server_version
import homesafe.shared.generated.resources.settings_storage_loading
import homesafe.shared.generated.resources.settings_storage_percent_used
import homesafe.shared.generated.resources.settings_storage_recordings
import homesafe.shared.generated.resources.settings_storage_title
import homesafe.shared.generated.resources.settings_storage_total
import homesafe.shared.generated.resources.settings_storage_unreported
import homesafe.shared.generated.resources.settings_storage_used
import homesafe.shared.generated.resources.settings_value_missing
import homesafe.shared.generated.resources.tone_settings_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The "Settings" tab: a short list of rows, everyday things first, each saying in a line how its
 * section stands. See [SettingsHome] for what's on it. [onOpen] is handed the route of the page a
 * row opens ([NotificationsRoute], [ClassifierRoute], …).
 */
@Composable
fun SettingsTabContent(
    onOpen: (Any) -> Unit = {},
    scrollToTopRequests: ScrollToTopRequests = ScrollToTopRequests.NONE,
) {
    val viewModel: SettingsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The rows' summaries are read from the same things their pages are: coming back from the OS
    // notification settings must show without a relaunch, the other phone may have flipped its
    // switch, and a classifier added on the server since the last visit should appear too.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationPermission()
        viewModel.refreshClassifiers()
        viewModel.refreshPresence()
        onPauseOrDispose { }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState, scrollToTopRequests) { scrollToTopRequests.collect { scrollState.animateScrollTo(0) } }
    SettingsHome(
        state = state,
        onOpenNotifications = { onOpen(NotificationsRoute) },
        onOpenAway = { onOpen(AwayRoute) },
        onOpenDoors = { onOpen(DoorsRoute) },
        onOpenClassifier = { onOpen(ClassifierRoute(it)) },
        onOpenFaces = { onOpen(FacesRoute) },
        onOpenServer = { onOpen(ServerRoute) },
        onDetection = viewModel::setCameraDetection,
        onMotion = viewModel::setCameraMotion,
        onDismissCameraError = viewModel::dismissCameraError,
        onTone = viewModel::setEconomyTone,
        onFinanceDrawerItems = viewModel::setFinanceDrawerItems,
        onFigureStyle = viewModel::setFigureStyle,
        onAppIcon = viewModel::setAppIcon,
        onMapProvider = viewModel::setMapProvider,
        onGoogleMapsKey = viewModel::setGoogleMapsKey,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = contentGutter())
            // Applied after verticalScroll, so this is content padding: the page scrolls under
            // the shell's floating top bar and the bottom nav rather than stopping short of them.
            .padding(top = shellTopBarClearance() + 8.dp, bottom = bottomNavClearance()),
    )
}

/** The rows of the main Settings page that open out in place rather than onto a page of their own. */
internal enum class SettingsFold { CAMERAS, ECONOMY, FINANCE_DRAWER, FIGURES, MAPS, APP_ICON }

/**
 * The Settings tab's page, from a [SettingsUiState]. Three groups of rows: every app's
 * notifications and away mode, each a page of its own; teaching the server faces and cars; then the cameras'
 * detection switches, how Finance talks about the economy, what its card in the drawer shows, how
 * Fitness draws its exercises, whose map the maps are drawn on and the app's home-screen icon — a few controls each, so they open out in place — and last the way into what the server is and is doing. Every row's second line
 * is that section in brief (see SettingsSummaries.kt), so most visits end on this page.
 *
 * One fold is open at a time, so the page never grows back into the long list it replaced;
 * [initialFold] is the one open to begin with, for previews and tests.
 */
@Composable
internal fun SettingsHome(
    state: SettingsUiState,
    onOpenNotifications: () -> Unit,
    onOpenAway: () -> Unit,
    onOpenClassifier: (String) -> Unit,
    onOpenFaces: () -> Unit,
    onOpenServer: () -> Unit,
    onDetection: (String, Boolean) -> Unit,
    onMotion: (String, Boolean) -> Unit,
    onDismissCameraError: () -> Unit,
    onTone: (EconomyTone) -> Unit,
    onFinanceDrawerItems: (List<FinanceDrawerItem>) -> Unit,
    onFigureStyle: (FigureStyle) -> Unit,
    onAppIcon: (AppIcon) -> Unit,
    modifier: Modifier = Modifier,
    onMapProvider: (MapProvider) -> Unit = {},
    onGoogleMapsKey: (String?) -> Unit = {},
    onOpenDoors: (() -> Unit)? = null,
    initialFold: SettingsFold? = null,
) {
    var openFold by rememberSaveable { mutableStateOf(initialFold) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsGroup {
            SettingsNavRow(
                icon = Icons.Filled.Notifications,
                title = stringResource(Res.string.alerts_title),
                summary = alertsSummary(state).resolve(),
                onClick = onOpenNotifications,
                opens = NotificationsRoute,
            )
            SettingsRowDivider()
            SettingsNavRow(
                icon = Icons.Filled.Home,
                title = stringResource(Res.string.presence_title),
                summary = awaySummary(state).resolve(),
                onClick = onOpenAway,
                opens = AwayRoute,
            )
            if (onOpenDoors != null) {
                SettingsRowDivider()
                SettingsNavRow(
                    icon = Icons.Filled.DoorFront,
                    title = stringResource(Res.string.doors_title),
                    summary = stringResource(Res.string.doors_settings_summary),
                    onClick = onOpenDoors,
                    opens = DoorsRoute,
                )
            }
        }
        RecognitionSection(
            models = state.classifiers,
            faceRecognitionEnabled = state.overview?.faceRecognitionEnabled,
            onOpen = onOpenClassifier,
            onOpenFaces = onOpenFaces,
        )
        SettingsGroup {
            SettingsExpandableRow(
                icon = Icons.Filled.Videocam,
                title = stringResource(Res.string.settings_cameras_title),
                summary = detectionSummary(state.overview).resolve(),
                expanded = openFold == SettingsFold.CAMERAS,
                onToggle = { openFold = SettingsFold.CAMERAS.takeIf { it != openFold } },
            ) {
                CameraDetectionRows(state = state, onDetection = onDetection, onMotion = onMotion, onDismissError = onDismissCameraError)
            }
            SettingsRowDivider()
            SettingsExpandableRow(
                icon = Icons.Filled.Public,
                title = stringResource(Res.string.tone_settings_title),
                summary = stringResource(state.economyTone.label),
                expanded = openFold == SettingsFold.ECONOMY,
                onToggle = { openFold = SettingsFold.ECONOMY.takeIf { it != openFold } },
            ) {
                EconomyToneOptions(tone = state.economyTone, onTone = onTone)
            }
            SettingsRowDivider()
            SettingsExpandableRow(
                icon = Icons.AutoMirrored.Filled.ShowChart,
                title = stringResource(Res.string.finance_drawer_settings_title),
                summary = financeDrawerSummary(state.financeDrawerItems),
                expanded = openFold == SettingsFold.FINANCE_DRAWER,
                onToggle = { openFold = SettingsFold.FINANCE_DRAWER.takeIf { it != openFold } },
                modifier = Modifier.testTag("settings_finance_drawer"),
            ) {
                FinanceDrawerOptions(items = state.financeDrawerItems, onItems = onFinanceDrawerItems)
            }
            SettingsRowDivider()
            SettingsExpandableRow(
                icon = Icons.Filled.FitnessCenter,
                title = stringResource(Res.string.fitness_figure_settings_title),
                summary = stringResource(state.figureStyle.label),
                expanded = openFold == SettingsFold.FIGURES,
                onToggle = { openFold = SettingsFold.FIGURES.takeIf { it != openFold } },
            ) {
                FigureStyleOptions(style = state.figureStyle, onStyle = onFigureStyle)
            }
            SettingsRowDivider()
            SettingsExpandableRow(
                icon = Icons.Filled.Map,
                title = stringResource(Res.string.maps_settings_title),
                summary = stringResource(state.maps.provider.label),
                expanded = openFold == SettingsFold.MAPS,
                onToggle = { openFold = SettingsFold.MAPS.takeIf { it != openFold } },
                modifier = Modifier.testTag("settings_maps"),
            ) {
                MapProviderOptions(settings = state.maps, onProvider = onMapProvider, onGoogleKey = onGoogleMapsKey)
            }
            SettingsRowDivider()
            if (state.appIconsSupported) {
                SettingsExpandableRow(
                    icon = Icons.Filled.Palette,
                    title = stringResource(Res.string.settings_app_icon_title),
                    summary = stringResource(state.appIcon.label),
                    expanded = openFold == SettingsFold.APP_ICON,
                    onToggle = { openFold = SettingsFold.APP_ICON.takeIf { it != openFold } },
                ) {
                    AppIconOptions(icon = state.appIcon, onIcon = onAppIcon)
                }
                SettingsRowDivider()
            }
            ServerSummaryRow(summary = serverSummary(state.connection?.route, state.overview, state.overviewError).resolve(), onOpen = onOpenServer)
        }
    }
}

@Composable
internal fun ServerSection(state: SettingsUiState, onRetry: () -> Unit) {
    SettingsSection(title = stringResource(Res.string.settings_server_title), icon = Icons.Filled.Dns) {
        state.connection?.let { connection ->
            SettingsCaption(stringResource(Res.string.settings_server_connected_to, connection.activeUrl))
            SettingsCaption(
                stringResource(
                    when (connection.route) {
                        ConnectionRoute.LOCAL_NETWORK -> Res.string.settings_server_route_local

                        ConnectionRoute.TAILSCALE ->
                            if (connection.localUrl == null) ConnectionRoute.TAILSCALE.label else Res.string.settings_server_route_tailscale_local_unreachable
                    },
                ),
            )
        }
        val overview = state.overview
        when {
            overview == null && state.overviewError != null -> LoadFailedRow(state.overviewError, onRetry)

            overview == null -> LoadingRow(stringResource(Res.string.settings_server_loading))

            else -> {
                SettingsInfoGrid(
                    listOf(
                        InfoItem(
                            stringResource(Res.string.settings_server_version),
                            overview.version.ifBlank { stringResource(Res.string.settings_value_missing) },
                            note = overview.latestVersion?.takeIf { overview.updateAvailable }?.let { stringResource(Res.string.settings_server_update_available, it) },
                        ),
                        InfoItem(stringResource(Res.string.settings_server_uptime), formatUptime(overview.uptimeSeconds).resolve()),
                        InfoItem(stringResource(Res.string.settings_server_cpu), formatPercent(overview.cpuPercent).resolve()),
                        InfoItem(stringResource(Res.string.settings_server_memory), formatPercent(overview.memoryPercent).resolve()),
                    ),
                )
                overview.detector?.let { detector ->
                    val inputSize = detector.inputWidth?.let { w -> detector.inputHeight?.let { h -> stringResource(Res.string.settings_server_detector_input_size, w, h) } }
                    // The model's name and its input size are both data; a space is all that sits between them.
                    val model = listOfNotNull(detector.modelType, inputSize).joinToString(" ")
                    InfoRow(
                        label = stringResource(Res.string.settings_server_detector),
                        value = listOfNotNull(detector.type, model.takeIf { it.isNotBlank() }).joinToString(stringResource(Res.string.common_dot_separator)),
                        note = detector.inferenceMs?.let { stringResource(Res.string.settings_server_inference, formatMillis(it)) }
                            ?: stringResource(Res.string.settings_server_no_inference),
                    )
                }
                overview.gpus.forEach { gpu ->
                    InfoRow(
                        label = stringResource(Res.string.settings_server_gpu),
                        value = gpu.name,
                        note = listOfNotNull(
                            gpu.gpuPercent?.let { UiText.of(Res.string.settings_server_gpu_busy, formatPercent(it)) },
                            gpu.memoryPercent?.let { UiText.of(Res.string.settings_server_gpu_memory, formatPercent(it)) },
                            gpu.decoderPercent?.let { UiText.of(Res.string.settings_server_gpu_decoder, formatPercent(it)) },
                        ).takeIf { it.isNotEmpty() }?.let { UiText.Joined(it, UiText.of(Res.string.common_dot_separator)).resolve() },
                    )
                }
                if (state.overviewError != null) {
                    SettingsCaption(stringResource(Res.string.settings_server_last_refresh_failed, state.overviewError.resolve()), error = true)
                }
            }
        }
    }
}

@Composable
internal fun StorageSection(overview: ServerOverview?) {
    SettingsSection(title = stringResource(Res.string.settings_storage_title), icon = Icons.Filled.Storage) {
        if (overview == null) {
            LoadingRow(stringResource(Res.string.settings_storage_loading))
            return@SettingsSection
        }
        val storage = overview.recordingsStorage
        if (storage == null) {
            SettingsCaption(stringResource(Res.string.settings_storage_unreported))
        } else {
            StorageUsageBar(
                label = stringResource(Res.string.settings_storage_recordings, storage.path),
                usedFraction = storage.usedFraction,
                usedText = stringResource(Res.string.settings_storage_used, formatMegabytes(storage.usedMb).resolve()),
                totalText = stringResource(Res.string.settings_storage_total, formatMegabytes(storage.totalMb).resolve()),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        val retention = overview.retention
        SettingsInfoGrid(
            listOf(
                InfoItem(stringResource(Res.string.settings_retention_continuous), formatRetentionDays(retention.continuousDays).resolve()),
                InfoItem(stringResource(Res.string.settings_retention_motion), formatRetentionDays(retention.motionDays).resolve()),
                InfoItem(stringResource(Res.string.settings_retention_alerts), retentionOrDefault(retention.alertDays)),
                InfoItem(stringResource(Res.string.settings_retention_detections), retentionOrDefault(retention.detectionDays)),
            ),
        )
        SettingsCaption(stringResource(Res.string.settings_retention_caption))
    }
}

/**
 * What the Cameras row opens out to: every camera's detection switches. Rarely touched, but they
 * decide whether a camera alerts at all, so they stay on the main page rather than a tap further in.
 */
@Composable
private fun CameraDetectionRows(
    state: SettingsUiState,
    onDetection: (String, Boolean) -> Unit,
    onMotion: (String, Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    val overview = state.overview
    if (overview == null) {
        LoadingRow(stringResource(Res.string.settings_cameras_loading))
        return
    }
    // One emitter at the top level; the spacing matches the card sections elsewhere.
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!overview.canEditConfig) {
            SettingsCaption(stringResource(Res.string.settings_cameras_viewer_read_only))
        }
        overview.cameras.forEachIndexed { index, camera ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            CameraPipelineRows(
                camera = camera,
                editable = overview.canEditConfig,
                busy = camera.name in state.busyCameras,
                onDetection = { onDetection(camera.name, it) },
                onMotion = { onMotion(camera.name, it) },
            )
        }
        state.cameraError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsCaption(error.resolve(), error = true, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissError) { Text(stringResource(Res.string.settings_dismiss)) }
            }
        }
    }
}

/** What Frigate's config switches on beyond detection. Read-only: each needs a config.yml edit and a restart. */
@Composable
internal fun AiFeaturesSection(overview: ServerOverview?) {
    SettingsSection(title = stringResource(Res.string.settings_ai_title), icon = Icons.Filled.AutoAwesome) {
        if (overview == null) {
            LoadingRow(stringResource(Res.string.settings_ai_loading))
            return@SettingsSection
        }
        InfoRow(label = stringResource(Res.string.settings_ai_face_recognition), value = onOff(overview.faceRecognitionEnabled))
        InfoRow(label = stringResource(Res.string.settings_ai_license_plates), value = onOff(overview.licensePlateRecognitionEnabled))
        InfoRow(label = stringResource(Res.string.settings_ai_semantic_search), value = onOff(overview.semanticSearchEnabled))
        SettingsCaption(stringResource(Res.string.settings_ai_caption))
    }
}

/** A camera's name and live throughput, then its two switches. Motion can't go off while detection needs it — Frigate's own rule. */
@Composable
private fun CameraPipelineRows(
    camera: CameraPipeline,
    editable: Boolean,
    busy: Boolean,
    onDetection: (Boolean) -> Unit,
    onMotion: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = camera.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = cameraThroughput(camera).resolve(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsToggleRow(
            title = stringResource(Res.string.settings_object_detection),
            description = stringResource(if (camera.detectionEnabled) Res.string.settings_object_detection_on else Res.string.settings_object_detection_off),
            checked = camera.detectionEnabled,
            enabled = editable && !busy && camera.enabled,
            onCheckedChange = onDetection,
        )
        SettingsToggleRow(
            title = stringResource(Res.string.settings_motion_detection),
            description = stringResource(
                when {
                    camera.detectionEnabled -> Res.string.settings_motion_needed
                    camera.motionEnabled -> Res.string.settings_motion_on
                    else -> Res.string.settings_motion_off
                },
            ),
            checked = camera.motionEnabled,
            enabled = editable && !busy && camera.enabled && !camera.detectionEnabled,
            onCheckedChange = onMotion,
        )
    }
}

/**
 * The Away mode page's one card ([AwaySettingsScreen]): this phone's "I'm away" switch, the
 * household's two tracked phones, what keeps them up to date on their own, and what happens once
 * both have left. The relay on the Frigate box keeps the answer, so both phones see the same thing.
 */
@Composable
internal fun AwaySection(
    state: SettingsUiState,
    onAway: (Boolean) -> Unit,
    onRequestLocation: () -> Unit,
    onSetHomeHere: () -> Unit,
    onClearHome: () -> Unit,
    onRemoveDevice: (String) -> Unit,
) {
    SettingsCard {
        val relayUnreachable = state.presence == HouseholdPresence.EMPTY && state.awayError != null
        val me = state.presence.thisDevice
        // Without this phone's entry there is nothing to say where it counts as being: the relay
        // either hasn't answered yet, or doesn't track this install, and says why.
        SettingsToggleRow(
            title = stringResource(Res.string.presence_im_away),
            description = when {
                relayUnreachable -> stringResource(Res.string.presence_im_away_relay_unreachable)
                me == null && state.presence == HouseholdPresence.EMPTY -> stringResource(Res.string.presence_im_away_checking)
                me == null -> notTrackedText(state.presence)
                me.pendingAway -> stringResource(Res.string.presence_im_away_leaving)
                state.thisDeviceAway -> stringResource(Res.string.presence_im_away_away)
                else -> stringResource(Res.string.presence_im_away_home)
            },
            checked = state.thisDeviceAway,
            enabled = !state.awayBusy && !relayUnreachable && me != null,
            onCheckedChange = onAway,
        )
        if (state.presence != HouseholdPresence.EMPTY) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            PresenceDeviceList(state, onRemoveDevice)
            if (state.presence.everyoneAway) {
                SettingsCaption(stringResource(Res.string.presence_nobody_home), error = true)
            }
        }
        state.awayError?.let { SettingsCaption(it.resolve(), error = true) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        AutomaticPresenceRows(state, onRequestLocation, onSetHomeHere, onClearHome)

        SettingsCaption(stringResource(Res.string.presence_caption))
    }
}

/** Why the relay doesn't track this install, in a sentence. */
@Composable
private fun notTrackedText(presence: HouseholdPresence): String = when (presence.thisDeviceNotTracked) {
    NotTrackedReason.DEBUG_BUILD -> stringResource(Res.string.presence_not_tracked_debug)
    NotTrackedReason.EMULATOR -> stringResource(Res.string.presence_not_tracked_emulator)
    NotTrackedReason.ANOTHER_PHONE -> stringResource(Res.string.presence_not_tracked_other_phone)
    NotTrackedReason.UNKNOWN, null -> stringResource(Res.string.presence_not_tracked_unknown)
}

/**
 * What keeps this phone's presence up to date without the switch — always on for a tracked
 * phone, so there is no switch for it — then whatever it still needs, in the order the user has
 * to supply it: location access, then a home to draw the fence around. Each missing piece gets
 * one button.
 */
@Composable
private fun AutomaticPresenceRows(
    state: SettingsUiState,
    onRequestLocation: () -> Unit,
    onSetHomeHere: () -> Unit,
    onClearHome: () -> Unit,
) {
    // One emitter at the top level; the spacing matches the section it sits in.
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val home = state.presence.home
        val access = state.locationAccess
        val tracked = state.presence.thisDevice != null
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(Res.string.presence_automatic),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SettingsCaption(
                stringResource(
                    when {
                        !state.geofenceSupported -> Res.string.presence_automatic_unsupported
                        !tracked -> Res.string.presence_automatic_not_tracked
                        access != LocationAccess.ALWAYS -> Res.string.presence_automatic_needs_always
                        home == null -> Res.string.presence_automatic_needs_home
                        else -> Res.string.presence_automatic_on
                    },
                ),
            )
        }
        if (state.geofenceSupported && tracked && access != LocationAccess.ALWAYS) {
            OutlinedButton(onClick = onRequestLocation) {
                Text(
                    stringResource(
                        when (access) {
                            LocationAccess.WHILE_IN_USE -> Res.string.presence_allow_location_always
                            LocationAccess.DENIED -> Res.string.presence_allow_location_settings
                            else -> Res.string.presence_allow_location
                        },
                    ),
                )
            }
        }
        if (state.geofenceSupported) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSetHomeHere, enabled = !state.homeBusy && access != LocationAccess.NOT_ASKED && access != LocationAccess.DENIED) {
                    Text(stringResource(if (home == null) Res.string.presence_set_home_here else Res.string.presence_move_home_here))
                }
                if (home != null) {
                    TextButton(onClick = onClearHome, enabled = !state.homeBusy) { Text(stringResource(Res.string.presence_clear_home)) }
                }
            }
        }
        SettingsCaption(
            if (home == null) stringResource(Res.string.presence_home_not_set) else stringResource(Res.string.presence_home_set, home.radiusMeters.toInt()),
        )
        state.homeError?.let { SettingsCaption(it.resolve(), error = true) }
    }
}

/**
 * The household's two tracked phones, Android first, and a line for whichever isn't tracked yet.
 * Either can be removed, after a confirmation — how a replaced phone hands its place to the new one.
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun PresenceDeviceList(state: SettingsUiState, onRemove: (String) -> Unit) {
    val devices = state.presence.devices
    // Read once per snapshot: "seen 5 days ago" doesn't need to tick while the page is open.
    val now = remember(devices) { Clock.System.now().epochSeconds.toDouble() }
    var confirming by remember { mutableStateOf<PresenceDevice?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        devices.forEach { device ->
            PresenceDeviceRow(device, now, removing = device.id == state.removingDevice, onRemove = { confirming = device })
        }
        if (devices.none { it.platform.equals("android", ignoreCase = true) }) {
            SettingsCaption(stringResource(Res.string.presence_no_android_phone))
        }
        if (devices.none { it.platform.equals("ios", ignoreCase = true) }) {
            SettingsCaption(stringResource(Res.string.presence_no_iphone))
        }
    }

    confirming?.let { device ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(Res.string.presence_remove_title, presenceDeviceName(device))) },
            text = { Text(stringResource(Res.string.presence_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    device.id?.let(onRemove)
                }) { Text(stringResource(Res.string.presence_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}

/**
 * "Google Pixel 10 Pro XL", then "away since 4:12 PM" / "home" / "leaving…" and when the relay
 * last heard from it. Every row but this phone's has a remove button.
 */
@Composable
private fun PresenceDeviceRow(device: PresenceDevice, nowEpochSeconds: Double, removing: Boolean, onRemove: () -> Unit) {
    val status = when {
        device.away && device.updatedEpochSeconds != null -> UiText.of(Res.string.presence_status_away_since, formatClockTime(device.updatedEpochSeconds))
        device.away -> UiText.of(Res.string.presence_status_away)
        device.pendingAway -> UiText.of(Res.string.presence_status_leaving)
        else -> UiText.of(Res.string.presence_status_home)
    }
    val details = UiText.Joined(
        listOfNotNull(
            UiText.of(Res.string.presence_detail_this_phone).takeIf { device.isThisDevice },
            status,
            device.lastSeenEpochSeconds?.takeIf { !device.isThisDevice }?.let { formatLastSeen(it, nowEpochSeconds) },
        ),
        separator = UiText.of(Res.string.common_dot_separator),
    ).resolve()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PulsingDot(
            color = when {
                device.away -> MaterialTheme.colorScheme.primary
                device.pendingAway -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.secondary
            },
            size = 6.dp,
            pulsing = device.away || device.pendingAway,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = presenceDeviceName(device), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            SettingsCaption(details)
        }
        when {
            removing -> CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(16.dp), strokeWidth = 2.dp)

            // Removing this phone would only last until its next connect; an older relay sends no id to remove by.
            !device.isThisDevice && device.id != null -> IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.presence_remove_device, presenceDeviceName(device)), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The name the phone registered, else its platform ("Android"), else a placeholder. Both are data from the relay. */
@Composable
private fun presenceDeviceName(device: PresenceDevice): String =
    device.name.ifBlank { device.platform.replaceFirstChar { it.uppercase() }.ifBlank { stringResource(Res.string.presence_unnamed_device) } }

// ---- Building blocks ---------------------------------------------------------------------------

private data class InfoItem(val label: String, val value: String, val note: String? = null)

/** A card of settings under a heading, for a page made of several. */
@Composable
internal fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    SettingsCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        content()
    }
}

/** The card alone, for a page that is one section: its header already says which. */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
internal fun SettingsCaption(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        SettingsCaption(text)
    }
}

@Composable
private fun LoadFailedRow(message: UiText, onRetry: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SettingsCaption(stringResource(Res.string.settings_server_unreachable, message.resolve()), error = true, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
    }
}

@Composable
private fun SettingsInfoGrid(items: List<InfoItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rowItems.forEach { item ->
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = item.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = item.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        item.note?.let { Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A full-width label/value pair, for values too long for the two-column grid. */
@Composable
private fun InfoRow(label: String, value: String, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        note?.let { Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun StorageUsageBar(label: String, usedFraction: Float, usedText: String, totalText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                text = stringResource(Res.string.settings_storage_percent_used, (usedFraction * 100).roundToInt()),
                style = MaterialTheme.typography.labelMedium,
                color = if (usedFraction >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(usedFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(if (usedFraction >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SettingsCaption(usedText)
            SettingsCaption(totalText)
        }
    }
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val textAlpha = if (enabled) 1f else 0.6f
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha))
            Text(text = description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha))
        }
        // Material's disabled-checked thumb is the surface colour, which on this dark theme makes a
        // locked "on" switch read as "off". A locked switch still has to show its state, so the
        // disabled colours are the enabled ones, dimmed.
        val colors = MaterialTheme.colorScheme
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimaryContainer,
                checkedTrackColor = colors.primaryContainer,
                disabledCheckedThumbColor = colors.onPrimaryContainer.copy(alpha = 0.5f),
                disabledCheckedTrackColor = colors.primaryContainer.copy(alpha = 0.4f),
                disabledUncheckedThumbColor = colors.onSurfaceVariant.copy(alpha = 0.4f),
                disabledUncheckedTrackColor = colors.surfaceContainerHighest.copy(alpha = 0.4f),
                disabledUncheckedBorderColor = colors.outline.copy(alpha = 0.2f),
            ),
        )
    }
}

@Composable
private fun onOff(enabled: Boolean): String = stringResource(if (enabled) Res.string.settings_on else Res.string.settings_off)

/** A retention period, or "Default" when Frigate's own default applies. */
@Composable
private fun retentionOrDefault(days: Double?): String =
    days?.let { formatRetentionDays(it).resolve() } ?: stringResource(Res.string.settings_retention_default)

/** "5.7 detections/s · 5 fps" — what the pipeline is doing right now; "Disabled" for a camera turned off in config. */
private fun cameraThroughput(camera: CameraPipeline): UiText {
    if (!camera.enabled) return UiText.of(Res.string.settings_camera_disabled)
    val parts = listOfNotNull(
        camera.detectionFps?.takeIf { camera.detectionEnabled }?.let { UiText.of(Res.string.settings_camera_detections_per_second, it.format1()) },
        camera.cameraFps?.let { UiText.of(Res.string.settings_camera_fps, it.roundToInt()) },
        camera.skippedFps?.takeIf { it > 0 }?.let { UiText.of(Res.string.settings_camera_skipped, it.format1()) },
    )
    return if (parts.isEmpty()) UiText.of(Res.string.settings_camera_no_stats) else UiText.Joined(parts, UiText.of(Res.string.common_dot_separator))
}

/** "6.8 ms" */
@Composable
private fun formatMillis(ms: Double): String = stringResource(Res.string.settings_millis, ms.format1())

private fun Double.format1(): String {
    val scaled = (this * 10).roundToInt()
    return if (scaled % 10 == 0) "${scaled / 10}" else "${scaled / 10}.${scaled % 10}"
}

package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HouseholdDeviceList
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.formatLastSeen
import com.meticulouscreations.homesafe.domain.model.formatMegabytes
import com.meticulouscreations.homesafe.domain.model.formatPercent
import com.meticulouscreations.homesafe.domain.model.formatRetentionDays
import com.meticulouscreations.homesafe.domain.model.formatUptime
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_cancel
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_retry
import homesafe.shared.generated.resources.presence_allow_location
import homesafe.shared.generated.resources.presence_allow_location_always
import homesafe.shared.generated.resources.presence_allow_location_settings
import homesafe.shared.generated.resources.presence_automatic
import homesafe.shared.generated.resources.presence_automatic_needs_always
import homesafe.shared.generated.resources.presence_automatic_needs_home
import homesafe.shared.generated.resources.presence_automatic_off
import homesafe.shared.generated.resources.presence_automatic_on
import homesafe.shared.generated.resources.presence_automatic_unsupported
import homesafe.shared.generated.resources.presence_caption
import homesafe.shared.generated.resources.presence_clear_home
import homesafe.shared.generated.resources.presence_decides
import homesafe.shared.generated.resources.presence_decides_off
import homesafe.shared.generated.resources.presence_decides_on
import homesafe.shared.generated.resources.presence_decides_other
import homesafe.shared.generated.resources.presence_detail_debug
import homesafe.shared.generated.resources.presence_detail_decides
import homesafe.shared.generated.resources.presence_detail_this_phone
import homesafe.shared.generated.resources.presence_hide_other_devices
import homesafe.shared.generated.resources.presence_home_not_set
import homesafe.shared.generated.resources.presence_home_set
import homesafe.shared.generated.resources.presence_im_away
import homesafe.shared.generated.resources.presence_im_away_away
import homesafe.shared.generated.resources.presence_im_away_debug
import homesafe.shared.generated.resources.presence_im_away_home
import homesafe.shared.generated.resources.presence_im_away_leaving
import homesafe.shared.generated.resources.presence_im_away_other_decides
import homesafe.shared.generated.resources.presence_im_away_relay_unreachable
import homesafe.shared.generated.resources.presence_move_home_here
import homesafe.shared.generated.resources.presence_nobody_home
import homesafe.shared.generated.resources.presence_other_devices
import homesafe.shared.generated.resources.presence_other_devices_counted
import homesafe.shared.generated.resources.presence_other_devices_none_counted
import homesafe.shared.generated.resources.presence_remove
import homesafe.shared.generated.resources.presence_remove_body
import homesafe.shared.generated.resources.presence_remove_device
import homesafe.shared.generated.resources.presence_remove_title
import homesafe.shared.generated.resources.presence_set_home_here
import homesafe.shared.generated.resources.presence_show_other_devices
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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The "Settings" tab, everyday things first: this device's alert preferences, away mode, and
 * teaching the server faces and cars. Then the per-camera detection switches — rarely touched,
 * but a control rather than a readout, and one that decides whether a camera alerts at all, so
 * they stay on this page. Then how the finance app talks about the economy. Last, one row into
 * what the server is and is doing (live, from its stats and config), summarised in a line.
 */
@Composable
fun SettingsTabContent(
    onOpenClassifier: (String) -> Unit = {},
    onOpenFaces: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    scrollToTopRequests: ScrollToTopRequests = ScrollToTopRequests.NONE,
) {
    val viewModel: SettingsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Coming back from the OS notification settings screen must be reflected without a relaunch,
    // and a classifier added on the server since the last visit should appear too.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationPermission()
        viewModel.refreshClassifiers()
        viewModel.refreshPresence()
        onPauseOrDispose { }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState, scrollToTopRequests) { scrollToTopRequests.collect { scrollState.animateScrollTo(0) } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = contentGutter())
            // Applied after verticalScroll, so this is content padding: the page scrolls under
            // the shell's floating top bar and the bottom nav rather than stopping short of them.
            .padding(top = shellTopBarClearance() + 8.dp, bottom = bottomNavClearance()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
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
        AwaySection(
            state = state,
            onAway = viewModel::setAway,
            onDecides = viewModel::setDecidesPresence,
            onAutomatic = viewModel::setAutomaticPresence,
            onRequestLocation = viewModel::requestLocationAccess,
            onSetHomeHere = viewModel::setHomeHere,
            onClearHome = viewModel::clearHome,
            onRemoveDevice = viewModel::removeDevice,
        )
        RecognitionSection(
            models = state.classifiers,
            faceRecognitionEnabled = state.overview?.faceRecognitionEnabled,
            onOpen = onOpenClassifier,
            onOpenFaces = onOpenFaces,
        )
        DetectionSection(
            state = state,
            onDetection = viewModel::setCameraDetection,
            onMotion = viewModel::setCameraMotion,
            onDismissError = viewModel::dismissCameraError,
        )
        EconomyToneSection(tone = state.economyTone, onTone = viewModel::setEconomyTone)
        ServerSummaryRow(summary = serverSummary(state.connection?.route, state.overview, state.overviewError).resolve(), onOpen = onOpenServer)
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

@Composable
private fun DetectionSection(
    state: SettingsUiState,
    onDetection: (String, Boolean) -> Unit,
    onMotion: (String, Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    SettingsSection(title = stringResource(Res.string.settings_cameras_title), icon = Icons.Filled.PersonSearch) {
        val overview = state.overview
        if (overview == null) {
            LoadingRow(stringResource(Res.string.settings_cameras_loading))
            return@SettingsSection
        }
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
 * Away mode: this phone's "I'm away" switch, whether this phone alone decides (the relay's
 * presence authority), the household's phones, automatic presence, and what happens once the
 * last one leaves. The relay on the Frigate box keeps the answer, so both phones see the same thing.
 */
@Composable
internal fun AwaySection(
    state: SettingsUiState,
    onAway: (Boolean) -> Unit,
    onDecides: (Boolean) -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onRequestLocation: () -> Unit,
    onSetHomeHere: () -> Unit,
    onClearHome: () -> Unit,
    onRemoveDevice: (String) -> Unit,
) {
    SettingsSection(title = stringResource(Res.string.presence_title), icon = Icons.Filled.Home) {
        val relayUnreachable = state.presence == HouseholdPresence.EMPTY && state.awayError != null
        val switchEnabled = !state.awayBusy && !relayUnreachable
        val me = state.presence.thisDevice
        // The name of another phone deciding alone; null when it is this one, or when every counting phone votes.
        val otherDecider = state.presence.decidingDevice?.takeIf { !it.isThisDevice }?.let { presenceDeviceName(it) }
        // A debug install may still flip its own switch — the relay simply doesn't count it.
        val thisDeviceCounts = me?.countsForAway != false
        SettingsToggleRow(
            title = stringResource(Res.string.presence_im_away),
            description = when {
                relayUnreachable -> stringResource(Res.string.presence_im_away_relay_unreachable)
                !thisDeviceCounts && otherDecider != null -> stringResource(Res.string.presence_im_away_other_decides, otherDecider)
                !thisDeviceCounts -> stringResource(Res.string.presence_im_away_debug)
                me?.pendingAway == true -> stringResource(Res.string.presence_im_away_leaving)
                state.thisDeviceAway -> stringResource(Res.string.presence_im_away_away)
                else -> stringResource(Res.string.presence_im_away_home)
            },
            checked = state.thisDeviceAway,
            enabled = switchEnabled,
            onCheckedChange = onAway,
        )
        SettingsToggleRow(
            title = stringResource(Res.string.presence_decides),
            description = when {
                state.thisDeviceDecides -> stringResource(Res.string.presence_decides_on)
                otherDecider != null -> stringResource(Res.string.presence_decides_other, otherDecider)
                else -> stringResource(Res.string.presence_decides_off)
            },
            checked = state.thisDeviceDecides,
            // An older relay lists no ids, and has no presence authority to set either.
            enabled = !state.decidesBusy && !relayUnreachable && me?.id != null,
            onCheckedChange = onDecides,
        )
        if (state.presence.devices.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            PresenceDeviceList(state, onRemoveDevice)
            if (state.presence.everyoneAway) {
                SettingsCaption(stringResource(Res.string.presence_nobody_home), error = true)
            }
        }
        state.awayError?.let { SettingsCaption(it.resolve(), error = true) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        AutomaticPresenceRows(state, onAutomatic, onRequestLocation, onSetHomeHere, onClearHome)

        SettingsCaption(stringResource(Res.string.presence_caption))
    }
}

/**
 * The switch, then whatever it still needs, in the order the user has to supply it: location
 * access, then a home to draw the fence around. Each missing piece gets one button. Once all
 * three are in place the caption says what's watching.
 */
@Composable
private fun AutomaticPresenceRows(
    state: SettingsUiState,
    onAutomatic: (Boolean) -> Unit,
    onRequestLocation: () -> Unit,
    onSetHomeHere: () -> Unit,
    onClearHome: () -> Unit,
) {
    // One emitter at the top level; the spacing matches the section it sits in.
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val home = state.presence.home
        val access = state.locationAccess
        SettingsToggleRow(
            title = stringResource(Res.string.presence_automatic),
            description = stringResource(
                when {
                    !state.geofenceSupported -> Res.string.presence_automatic_unsupported
                    !state.automaticPresence -> Res.string.presence_automatic_off
                    access != LocationAccess.ALWAYS -> Res.string.presence_automatic_needs_always
                    home == null -> Res.string.presence_automatic_needs_home
                    else -> Res.string.presence_automatic_on
                },
            ),
            checked = state.automaticPresence && state.geofenceSupported,
            enabled = state.geofenceSupported,
            onCheckedChange = onAutomatic,
        )
        if (state.automaticPresence && state.geofenceSupported) {
            if (access != LocationAccess.ALWAYS) {
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSetHomeHere, enabled = !state.homeBusy && access != LocationAccess.NOT_ASKED && access != LocationAccess.DENIED) {
                    Text(stringResource(if (home == null) Res.string.presence_set_home_here else Res.string.presence_move_home_here))
                }
                if (home != null) {
                    TextButton(onClick = onClearHome, enabled = !state.homeBusy) { Text(stringResource(Res.string.presence_clear_home)) }
                }
            }
            SettingsCaption(
                if (home == null) stringResource(Res.string.presence_home_not_set) else stringResource(Res.string.presence_home_set, home.radiusMeters.toInt()),
            )
            state.homeError?.let { SettingsCaption(it.resolve(), error = true) }
        }
    }
}

/**
 * The phones the relay knows, as [HouseholdDeviceList] splits them: the ones that decide away
 * mode up front, and old installs and debug builds folded under "N other devices". Any device
 * but this one can be removed, after a confirmation — the usual cleanup after a reinstall.
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun PresenceDeviceList(state: SettingsUiState, onRemove: (String) -> Unit) {
    val devices = state.presence.devices
    // Read once per snapshot: "seen 5 days ago" doesn't need to tick while the page is open.
    val now = remember(devices) { Clock.System.now().epochSeconds.toDouble() }
    val list = remember(devices, now) { HouseholdDeviceList.of(devices, now) }
    var showOthers by rememberSaveable { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<PresenceDevice?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.primary.forEach { device ->
            PresenceDeviceRow(device, now, removing = device.id == state.removingDevice, onRemove = { confirming = device })
        }
        if (list.others.isNotEmpty()) {
            OtherDevicesToggle(count = list.others.size, counted = list.countedOthers, expanded = showOthers, onToggle = { showOthers = !showOthers })
            if (showOthers) {
                list.others.forEach { device ->
                    PresenceDeviceRow(device, now, removing = device.id == state.removingDevice, onRemove = { confirming = device })
                }
            }
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

/** "4 other devices", and what they are; tapping shows or hides them. */
@Composable
private fun OtherDevicesToggle(count: Int, counted: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = pluralStringResource(Res.plurals.presence_other_devices, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SettingsCaption(
                if (counted == 0) {
                    stringResource(Res.string.presence_other_devices_none_counted)
                } else {
                    pluralStringResource(Res.plurals.presence_other_devices_counted, counted, counted)
                },
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(if (expanded) Res.string.presence_hide_other_devices else Res.string.presence_show_other_devices),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Google Pixel 10 Pro XL", then "away since 4:12 PM" / "home" / "leaving…", when the relay last
 * heard from it, and whether it counts. The phone that decides home/away alone says so. A debug
 * install is greyed out and says so: it hears the alerts but doesn't get a vote. Under a deciding
 * phone the others' dots are greyed too — their switches don't count — but they are still the
 * household's phones, named as such. Every row but this phone's has a remove button.
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
            UiText.of(Res.string.presence_detail_decides).takeIf { device.decides },
            UiText.of(Res.string.presence_detail_debug).takeIf { !device.countsForAway && device.isTestInstall },
        ),
        separator = UiText.of(Res.string.common_dot_separator),
    ).resolve()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PulsingDot(
            color = when {
                !device.countsForAway -> MaterialTheme.colorScheme.outline
                device.away -> MaterialTheme.colorScheme.primary
                device.pendingAway -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.secondary
            },
            size = 6.dp,
            pulsing = (device.away || device.pendingAway) && device.countsForAway,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = presenceDeviceName(device),
                style = MaterialTheme.typography.bodyMedium,
                color = if (device.isTestInstall) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
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

@Composable
internal fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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

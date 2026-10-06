package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.settings_server_subtitle
import homesafe.shared.generated.resources.settings_server_summary_checking
import homesafe.shared.generated.resources.settings_server_summary_disk_full
import homesafe.shared.generated.resources.settings_server_summary_healthy
import homesafe.shared.generated.resources.settings_server_summary_refresh_failed
import homesafe.shared.generated.resources.settings_server_summary_storage_used
import homesafe.shared.generated.resources.settings_server_summary_unreachable
import homesafe.shared.generated.resources.settings_server_summary_update_available
import homesafe.shared.generated.resources.settings_server_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The server's diagnostics, one tap below Settings: how the app reaches it, its version, load and
 * detector, its uptime record, the recordings disk and retention, and the AI features its config switches on. Worth
 * a look when something seems off, not on every visit — which is why the main page carries only
 * the one-line [serverSummary] and a way in.
 */
@Composable
fun ServerSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, onOpenUptime: () -> Unit = {}) {
    val viewModel: SettingsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsPage(
        title = stringResource(Res.string.settings_server_title),
        subtitle = stringResource(Res.string.settings_server_subtitle),
        onBack = onBack,
        modifier = modifier,
        onRefresh = viewModel::retryOverview,
    ) {
        ServerSection(state, onRetry = viewModel::retryOverview)
        UptimeLinkRow(onOpen = onOpenUptime)
        StorageSection(state.overview)
        AiFeaturesSection(state.overview)
    }
}

/**
 * The Server row's one line on the main Settings page: "Tailscale · 55% storage used · healthy".
 * The route, then the disk (the one number that creeps up unattended), then the single most
 * pressing thing wrong — or "healthy" when nothing is. Parts the server hasn't reported are left out.
 */
internal fun serverSummary(route: ConnectionRoute?, overview: ServerOverview?, overviewError: UiText?): UiText {
    val storage = overview?.recordingsStorage
    val status = when {
        overview == null && overviewError != null -> Res.string.settings_server_summary_unreachable
        overview == null -> Res.string.settings_server_summary_checking
        overviewError != null -> Res.string.settings_server_summary_refresh_failed
        storage != null && storage.usedFraction >= 0.9f -> Res.string.settings_server_summary_disk_full
        overview.updateAvailable -> Res.string.settings_server_summary_update_available
        else -> Res.string.settings_server_summary_healthy
    }
    return UiText.Joined(
        listOfNotNull(
            route?.let { UiText.of(it.label) },
            storage?.let { UiText.of(Res.string.settings_server_summary_storage_used, (it.usedFraction * 100).roundToInt()) },
            UiText.of(status),
        ),
        separator = UiText.of(Res.string.common_dot_separator),
    )
}

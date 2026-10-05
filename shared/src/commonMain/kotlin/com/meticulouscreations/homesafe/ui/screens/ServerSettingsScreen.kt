package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.settings_refresh
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

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(onBack = onBack, onRefresh = viewModel::retryOverview)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = contentGutter())
                // Content padding, as on the main page: the last card scrolls clear of the floating nav.
                .padding(top = 8.dp, bottom = bottomNavClearance()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ServerSection(state, onRetry = viewModel::retryOverview)
            UptimeLinkRow(onOpen = onOpenUptime)
            StorageSection(state.overview)
            AiFeaturesSection(state.overview)
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = nestedHeaderVerticalPadding()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back), tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(Res.string.settings_server_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, textAlign = TextAlign.Center)
            Text(text = stringResource(Res.string.settings_server_subtitle), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.settings_refresh), tint = MaterialTheme.colorScheme.primary)
        }
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

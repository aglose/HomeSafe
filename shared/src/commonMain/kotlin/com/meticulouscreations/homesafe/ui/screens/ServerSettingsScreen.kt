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
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlin.math.roundToInt

/**
 * The server's diagnostics, one tap below Settings: how the app reaches it, its version, load and
 * detector, the recordings disk and retention, and the AI features its config switches on. Worth
 * a look when something seems off, not on every visit — which is why the main page carries only
 * the one-line [serverSummary] and a way in.
 */
@Composable
fun ServerSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(onBack = onBack, onRefresh = viewModel::retryOverview)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TAB_CONTENT_HORIZONTAL_PADDING)
                // Content padding, as on the main page: the last card scrolls clear of the floating nav.
                .padding(top = 8.dp, bottom = bottomNavClearance()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ServerSection(state, onRetry = viewModel::retryOverview)
            StorageSection(state.overview)
            AiFeaturesSection(state.overview)
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Server", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, textAlign = TextAlign.Center)
            Text(text = "What Frigate is and is doing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The Server row's one line on the main Settings page: "Tailscale · 55% storage used · healthy".
 * The route, then the disk (the one number that creeps up unattended), then the single most
 * pressing thing wrong — or "healthy" when nothing is. Parts the server hasn't reported are left out.
 */
internal fun serverSummary(route: ConnectionRoute?, overview: ServerOverview?, overviewError: String?): String {
    val storage = overview?.recordingsStorage
    val status = when {
        overview == null && overviewError != null -> "can't reach the server"
        overview == null -> "checking…"
        overviewError != null -> "last refresh failed"
        storage != null && storage.usedFraction >= 0.9f -> "disk nearly full"
        overview.updateAvailable -> "update available"
        else -> "healthy"
    }
    return listOfNotNull(
        when (route) {
            ConnectionRoute.LOCAL_NETWORK -> "Local network"
            ConnectionRoute.TAILSCALE -> "Tailscale"
            null -> null
        },
        storage?.let { "${(it.usedFraction * 100).roundToInt()}% storage used" },
        status,
    ).joinToString(" · ")
}

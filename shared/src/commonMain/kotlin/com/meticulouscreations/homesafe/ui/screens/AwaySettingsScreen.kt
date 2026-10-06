package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.presence_title
import homesafe.shared.generated.resources.settings_away_subtitle
import org.jetbrains.compose.resources.stringResource

/**
 * Away mode, one tap below Settings: this phone's "I'm away" switch, who decides, the household's
 * phones, and automatic presence ([AwaySection]). The main page carries the one-line [awaySummary].
 */
@Composable
fun AwaySettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The other phone may have flipped its switch, and a trip to the OS location settings ends here.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPresence()
        onPauseOrDispose { }
    }

    SettingsPage(
        title = stringResource(Res.string.presence_title),
        subtitle = stringResource(Res.string.settings_away_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
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
    }
}

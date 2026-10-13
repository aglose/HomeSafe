package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.AtlasProblem
import com.meticulouscreations.homesafe.atlas.domain.AtlasSetupProblem
import com.meticulouscreations.homesafe.atlas.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.finance.FinanceLockUiState
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_empty_action
import homesafe.shared.generated.resources.atlas_empty_body
import homesafe.shared.generated.resources.atlas_empty_title
import homesafe.shared.generated.resources.atlas_lock_body
import homesafe.shared.generated.resources.atlas_lock_no_check
import homesafe.shared.generated.resources.atlas_lock_title
import homesafe.shared.generated.resources.atlas_lock_unlock
import homesafe.shared.generated.resources.atlas_sync_check_now
import homesafe.shared.generated.resources.atlas_sync_checked
import homesafe.shared.generated.resources.atlas_sync_device_summary
import homesafe.shared.generated.resources.atlas_sync_device_title
import homesafe.shared.generated.resources.atlas_sync_drive_api_disabled
import homesafe.shared.generated.resources.atlas_sync_drive_empty
import homesafe.shared.generated.resources.atlas_sync_drive_failed
import homesafe.shared.generated.resources.atlas_sync_drive_no_key
import homesafe.shared.generated.resources.atlas_sync_drive_not_configured
import homesafe.shared.generated.resources.atlas_sync_drive_not_shared
import homesafe.shared.generated.resources.atlas_sync_drive_ok
import homesafe.shared.generated.resources.atlas_sync_drive_reading
import homesafe.shared.generated.resources.atlas_sync_drive_refused
import homesafe.shared.generated.resources.atlas_sync_drive_title
import homesafe.shared.generated.resources.atlas_sync_fetching
import homesafe.shared.generated.resources.atlas_sync_fetching_years
import homesafe.shared.generated.resources.atlas_sync_file
import homesafe.shared.generated.resources.atlas_sync_howto_body
import homesafe.shared.generated.resources.atlas_sync_howto_title
import homesafe.shared.generated.resources.atlas_sync_names_api_disabled
import homesafe.shared.generated.resources.atlas_sync_names_month
import homesafe.shared.generated.resources.atlas_sync_names_not_configured
import homesafe.shared.generated.resources.atlas_sync_names_progress
import homesafe.shared.generated.resources.atlas_sync_names_refused
import homesafe.shared.generated.resources.atlas_sync_names_title
import homesafe.shared.generated.resources.atlas_sync_never
import homesafe.shared.generated.resources.atlas_sync_share_with
import org.jetbrains.compose.resources.stringResource

/** A pill of a button in the trail's amber: the one thing a page most wants pressed. */
@Composable
internal fun AtlasButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = AtlasTheme.colors
    Text(
        label,
        style = AtlasTheme.type.bodyStrong,
        color = if (enabled) colors.background else colors.textFaint,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(if (enabled) colors.trail else colors.surfaceRaised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
    )
}

/** How many whole days before [now] a moment was, for "checked 3 days ago". */
private fun daysBefore(now: Long, epochSeconds: Long): Int = ((now - epochSeconds) / SECONDS_PER_DAY).toInt().coerceAtLeast(0)

/**
 * Where the history comes from and how that is going: what this device holds, the Drive folder
 * the relay looks in (and which step is missing, when one is), how far the place names have got,
 * and how to put a newer export where the relay will find it.
 */
@Composable
internal fun SyncScreen(state: AtlasUiState, padding: PaddingValues, onCheckNow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    val sync = state.sync
    val server = sync.server
    val history = state.history
    val now = state.nowEpochSeconds
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AtlasGutter).testTag("atlas_sync"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AtlasCard(title = stringResource(Res.string.atlas_sync_device_title)) {
            if (history.isEmpty) {
                Text(stringResource(Res.string.atlas_empty_title), style = AtlasTheme.type.headline, color = colors.text)
            } else {
                Text(
                    stringResource(
                        Res.string.atlas_sync_device_summary,
                        AtlasFormat.grouped(history.visits.size.toLong()),
                        AtlasFormat.grouped(history.moves.size.toLong()),
                        dateText(history.firstDay),
                        dateText(history.lastDay),
                    ),
                    style = AtlasTheme.type.body,
                    color = colors.text,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    sync.syncing && sync.yearsTotal > 0 -> stringResource(Res.string.atlas_sync_fetching_years, sync.yearsDone, sync.yearsTotal)
                    sync.syncing -> stringResource(Res.string.atlas_sync_fetching)
                    sync.checkedAtEpochSeconds != null -> stringResource(Res.string.atlas_sync_checked, agoText(daysBefore(now, sync.checkedAtEpochSeconds)))
                    else -> stringResource(Res.string.atlas_sync_never)
                },
                style = AtlasTheme.type.label,
                color = colors.textFaint,
            )
            sync.message?.let {
                Spacer(Modifier.height(10.dp))
                StatusLine(ok = false, text = it.resolve())
            }
            Spacer(Modifier.height(14.dp))
            AtlasButton(stringResource(Res.string.atlas_sync_check_now), onCheckNow, Modifier.testTag("atlas_sync_now"), enabled = !sync.syncing && sync.problem != AtlasProblem.SIGNED_OUT)
        }

        if (server != null) {
            AtlasCard(title = stringResource(Res.string.atlas_sync_drive_title)) {
                val problem = server.driveProblem
                when {
                    !server.driveConfigured -> StatusLine(false, stringResource(Res.string.atlas_sync_drive_not_configured))
                    server.reading -> StatusLine(true, stringResource(Res.string.atlas_sync_drive_reading))
                    problem == null -> StatusLine(true, stringResource(Res.string.atlas_sync_drive_ok))
                    problem == AtlasSetupProblem.NOT_SHARED -> StatusLine(false, stringResource(Res.string.atlas_sync_drive_not_shared))
                    problem == AtlasSetupProblem.API_DISABLED -> StatusLine(false, stringResource(Res.string.atlas_sync_drive_api_disabled))
                    problem == AtlasSetupProblem.EMPTY -> StatusLine(false, stringResource(Res.string.atlas_sync_drive_empty))
                    problem == AtlasSetupProblem.NO_KEY -> StatusLine(false, stringResource(Res.string.atlas_sync_drive_no_key))
                    problem == AtlasSetupProblem.FAILED -> StatusLine(false, stringResource(Res.string.atlas_sync_drive_failed))
                    else -> StatusLine(false, stringResource(Res.string.atlas_sync_drive_refused))
                }
                // What the server itself said, as it said it: for whoever looks after the box.
                if (server.driveConfigured && (problem == AtlasSetupProblem.FAILED || problem == AtlasSetupProblem.OTHER)) Technical(server.driveMessage)
                if (server.serviceAccount != null && (problem == AtlasSetupProblem.NOT_SHARED || problem == AtlasSetupProblem.EMPTY || !server.driveConfigured)) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(Res.string.atlas_sync_share_with), style = AtlasTheme.type.label, color = colors.textFaint)
                    SelectionContainer { Text(server.serviceAccount, style = AtlasTheme.type.bodyStrong, color = colors.trail) }
                }
                if (server.fileName != null && server.fileReadAtEpochSeconds != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(Res.string.atlas_sync_file, server.fileName, agoText(daysBefore(now, server.fileReadAtEpochSeconds))),
                        style = AtlasTheme.type.label,
                        color = colors.textMuted,
                    )
                }
            }
            AtlasCard(title = stringResource(Res.string.atlas_sync_names_title)) {
                when {
                    !server.namingConfigured -> StatusLine(false, stringResource(Res.string.atlas_sync_names_not_configured))
                    server.namingProblem == AtlasSetupProblem.API_DISABLED -> StatusLine(false, stringResource(Res.string.atlas_sync_names_api_disabled))
                    server.namingProblem != null -> StatusLine(false, stringResource(Res.string.atlas_sync_names_refused))
                    else -> StatusLine(true, stringResource(Res.string.atlas_sync_names_progress, AtlasFormat.grouped(server.placesNamed.toLong()), AtlasFormat.grouped(server.placesTotal.toLong())))
                }
                if (server.namingConfigured) {
                    if (server.namingProblem != null) {
                        if (server.namingProblem != AtlasSetupProblem.API_DISABLED) Technical(server.namingMessage)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(Res.string.atlas_sync_names_progress, AtlasFormat.grouped(server.placesNamed.toLong()), AtlasFormat.grouped(server.placesTotal.toLong())), style = AtlasTheme.type.label, color = colors.textMuted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(Res.string.atlas_sync_names_month, AtlasFormat.grouped(server.askedThisMonth.toLong()), AtlasFormat.grouped(server.monthlyCap.toLong())), style = AtlasTheme.type.label, color = colors.textFaint)
                }
            }
        }

        AtlasCard(title = stringResource(Res.string.atlas_sync_howto_title)) {
            Text(stringResource(Res.string.atlas_sync_howto_body), style = AtlasTheme.type.body, color = colors.textMuted)
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    val colors = AtlasTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, contentDescription = null, tint = if (ok) colors.good else colors.trail, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = AtlasTheme.type.body, color = colors.text)
    }
}

/** A line of the server's own words under a message: small, faint, and there to be copied, not read as the message. */
@Composable
private fun Technical(text: String?) {
    if (text.isNullOrBlank()) return
    Spacer(Modifier.height(6.dp))
    SelectionContainer { Text(text, style = AtlasTheme.type.label, color = AtlasTheme.colors.textFaint, modifier = Modifier.padding(start = 28.dp)) }
}

/** Nothing on this device yet: what the app is for, and the way to the page that says how to fill it. */
@Composable
internal fun AtlasEmpty(state: AtlasUiState, padding: PaddingValues, onSetUp: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    Column(
        modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp).testTag("atlas_empty"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(colors.trail.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Public, contentDescription = null, tint = colors.trail, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(Res.string.atlas_empty_title), style = AtlasTheme.type.title, color = colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(state.sync.message?.resolve() ?: stringResource(Res.string.atlas_empty_body), style = AtlasTheme.type.body, color = colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        Spacer(Modifier.height(22.dp))
        AtlasButton(stringResource(Res.string.atlas_empty_action), onSetUp, Modifier.testTag("atlas_empty_action"))
    }
}

/**
 * The lock: with no server to ask who this is, the history the device kept stays shut until
 * whoever holds the phone shows it is theirs. The same lock, and the same rules, as the finance
 * app's (`FinanceAccess`).
 */
@Composable
internal fun AtlasLockScreen(lock: FinanceLockUiState, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    Box(modifier.fillMaxSize().background(colors.background).testTag("atlas_lock")) {
        AtlasBackdrop(colors.trail, colors.air, Modifier.fillMaxSize(), seed = 0.7f, energy = 0.35f)
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0x33000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = colors.trail, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(Res.string.atlas_lock_title), style = AtlasTheme.type.title, color = colors.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (lock.canUnlock) Res.string.atlas_lock_body else Res.string.atlas_lock_no_check),
                style = AtlasTheme.type.body,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            lock.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it.resolve(), style = AtlasTheme.type.label, color = colors.danger, textAlign = TextAlign.Center)
            }
            if (lock.canUnlock) {
                Spacer(Modifier.height(22.dp))
                AtlasButton(stringResource(Res.string.atlas_lock_unlock, stringResource(lock.method)), onUnlock, Modifier.testTag("atlas_unlock"), enabled = !lock.checking)
            }
        }
    }
}

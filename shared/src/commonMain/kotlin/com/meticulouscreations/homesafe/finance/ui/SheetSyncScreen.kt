package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.SectionHealth
import com.meticulouscreations.homesafe.finance.domain.SectionStatus
import kotlin.time.Clock

/** How the last sync of the budget sheet went, as one of three lights. */
private enum class SyncLight { OK, LOOK, FAILED }

private fun lightOf(state: FinanceUiState): SyncLight = when {
    state.sheetIssue != null -> SyncLight.FAILED
    (state.finance?.health?.problemCount ?: 0) > 0 -> SyncLight.LOOK
    else -> SyncLight.OK
}

@Composable
private fun SyncLight.color(): Color = when (this) {
    SyncLight.OK -> FinanceTheme.colors.gain
    SyncLight.LOOK -> FinanceTheme.colors.watch
    SyncLight.FAILED -> FinanceTheme.colors.loss
}

/**
 * The Wallet's first line: when the sheet was last read, and whether all of it was. Amber when
 * a part of the sheet or a chart couldn't be read (say a title was renamed), red when the last
 * sync failed and the page is showing an older read. Opens the sync page.
 */
@Composable
internal fun SheetSyncLine(state: FinanceUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val finance = state.finance ?: return
    val light = lightOf(state)
    val now = Clock.System.now().epochSeconds
    val ago = FinanceFormat.ago(now, finance.fetchedAtEpochSeconds)
    val problems = finance.health.problemCount
    val text = when (light) {
        SyncLight.FAILED -> "Couldn't sync · showing the sheet from $ago"
        SyncLight.LOOK -> "Synced $ago · ${if (problems == 1) "1 thing needs" else "$problems things need"} a look"
        SyncLight.OK -> "Synced $ago · everything read"
    }
    Row(
        modifier
            .padding(horizontal = PageGutter)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onOpen)
            .background(if (light == SyncLight.OK) Color.Transparent else light.color().copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(light.color()))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = FinanceTheme.type.label,
            color = if (light == SyncLight.OK) FinanceTheme.colors.textSecondary else light.color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("  ›", style = FinanceTheme.type.label, color = FinanceTheme.colors.textTertiary)
    }
}

/**
 * The sheet's sync, in full: when it was last read and whether that worked, then every part of
 * the sheet the app reads (and what title it looks for), every chart, and anything it had to
 * leave out. The page to open after reorganising the sheet.
 */
@Composable
internal fun SheetSyncScreen(state: FinanceUiState, contentPadding: PaddingValues, onSyncNow: () -> Unit) {
    val colors = FinanceTheme.colors
    val uriHandler = LocalUriHandler.current
    val finance = state.finance
    val health = finance?.health
    val light = lightOf(state)
    val now = Clock.System.now().epochSeconds
    LazyColumn(contentPadding = contentPadding) {
        item(key = "status") {
            FinanceCard(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(light.color()))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when (light) {
                            SyncLight.FAILED -> "The last sync didn't work"
                            SyncLight.LOOK -> "Synced, but not everything was read"
                            SyncLight.OK -> "Everything in the sheet was read"
                        },
                        style = FinanceTheme.type.bodyStrong,
                        color = colors.textPrimary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                finance?.let { f ->
                    val t = f.fetchedAtEpochSeconds
                    Text(
                        "Last read from Google ${FinanceFormat.ago(now, t)} (${FinanceFormat.dateTime(t, FinanceFormat.localOffsetSeconds(t))})",
                        style = FinanceTheme.type.label,
                        color = colors.textSecondary,
                    )
                }
                state.sheetIssue?.let { issue ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        issue.message.ifBlank { "The relay couldn't read the sheet." } + if (finance != null) " The Wallet is showing the last read that worked." else "",
                        style = FinanceTheme.type.label,
                        color = colors.loss,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(if (state.refreshing) "Syncing…" else "Sync now", colors.accent, onClick = onSyncNow)
                    finance?.sourceUrl?.let { url -> PillButton("Open the sheet", colors.textSecondary, onClick = { uriHandler.openUri(url) }) }
                }
            }
        }
        if (health != null && health.sections.isNotEmpty()) {
            item(key = "sections-h") { SectionHeader("What the app reads", subtitle = "Each part is found by its title, wherever it sits in the sheet") }
            // What needs a look first, then the rest in the sheet's order.
            items(health.sections.sortedBy { it.status == SectionStatus.OK }, key = { "s-${it.section.name}" }) { SectionRow(it) }
        }
        if (health != null && health.notes.isNotEmpty()) {
            item(key = "notes-h") { SectionHeader("Left out") }
            items(health.notes, key = { "n-${it.message}" }) { note ->
                StatusRow(
                    color = colors.watch,
                    title = note.section?.label ?: "Sheet",
                    trailing = null,
                    detail = note.message,
                )
            }
        }
        if (health != null && health.charts.isNotEmpty()) {
            item(key = "charts-h") { SectionHeader("Charts", subtitle = "Every chart in the sheet; adding, changing or removing one shows on the next sync") }
            val charts = health.charts.sortedBy { it.problem == null }
            items(charts.size, key = { "c-$it" }) { i ->
                val c = charts[i]
                StatusRow(
                    color = if (c.problem == null) colors.gain else colors.watch,
                    title = c.title,
                    trailing = if (c.problem == null) "Drawn" else "Not drawn",
                    detail = listOfNotNull(c.tab.takeIf { it.isNotBlank() }, c.problem).joinToString(" · "),
                )
            }
        }
        item(key = "how") {
            FinePrint(
                "The app reads the sheet whenever Finance opens, every couple of minutes while it's open, and when you pull down. " +
                    "Moving a part, or adding and removing rows and columns in it, is fine. Renaming a part's title is what loses it: " +
                    "rename it back to the title this page shows, or have the app changed to look for the new one.",
                Modifier.padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun SectionRow(health: SectionHealth) {
    val colors = FinanceTheme.colors
    StatusRow(
        color = when (health.status) {
            SectionStatus.OK -> colors.gain
            SectionStatus.EMPTY -> colors.watch
            SectionStatus.MISSING -> colors.loss
        },
        title = health.section.label,
        trailing = when (health.status) {
            SectionStatus.OK -> health.found
            SectionStatus.EMPTY -> "Nothing read"
            SectionStatus.MISSING -> "Not found"
        },
        detail = when (health.status) {
            SectionStatus.OK -> null
            SectionStatus.EMPTY -> "Its title is there, but nothing under it could be read. Looks for ${health.section.lookedFor}."
            SectionStatus.MISSING -> "Looks for ${health.section.lookedFor}."
        },
    )
}

@Composable
private fun StatusRow(color: Color, title: String, trailing: String?, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(title, style = FinanceTheme.type.body, color = FinanceTheme.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.let { Text(it, style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary) }
        }
        detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = FinanceTheme.type.label, color = FinanceTheme.colors.textTertiary, modifier = Modifier.padding(start = 18.dp, top = 2.dp))
        }
    }
}

@Composable
private fun PillButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = FinanceTheme.type.bodyStrong,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

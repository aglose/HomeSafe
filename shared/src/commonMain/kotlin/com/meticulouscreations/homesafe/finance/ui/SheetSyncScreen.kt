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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.SectionHealth
import com.meticulouscreations.homesafe.finance.domain.SectionStatus
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.fin_sheet_sync_chart_drawn
import homesafe.shared.generated.resources.fin_sheet_sync_chart_not_drawn
import homesafe.shared.generated.resources.fin_sheet_sync_charts_subtitle
import homesafe.shared.generated.resources.fin_sheet_sync_charts_title
import homesafe.shared.generated.resources.fin_sheet_sync_how
import homesafe.shared.generated.resources.fin_sheet_sync_issue_fallback
import homesafe.shared.generated.resources.fin_sheet_sync_issue_showing_last
import homesafe.shared.generated.resources.fin_sheet_sync_last_read
import homesafe.shared.generated.resources.fin_sheet_sync_line_failed
import homesafe.shared.generated.resources.fin_sheet_sync_line_look
import homesafe.shared.generated.resources.fin_sheet_sync_line_ok
import homesafe.shared.generated.resources.fin_sheet_sync_note_whole_sheet
import homesafe.shared.generated.resources.fin_sheet_sync_notes_title
import homesafe.shared.generated.resources.fin_sheet_sync_now
import homesafe.shared.generated.resources.fin_sheet_sync_open_action
import homesafe.shared.generated.resources.fin_sheet_sync_open_sheet
import homesafe.shared.generated.resources.fin_sheet_sync_section_empty_detail
import homesafe.shared.generated.resources.fin_sheet_sync_section_missing_detail
import homesafe.shared.generated.resources.fin_sheet_sync_section_not_found
import homesafe.shared.generated.resources.fin_sheet_sync_section_nothing_read
import homesafe.shared.generated.resources.fin_sheet_sync_sections_subtitle
import homesafe.shared.generated.resources.fin_sheet_sync_sections_title
import homesafe.shared.generated.resources.fin_sheet_sync_status_failed
import homesafe.shared.generated.resources.fin_sheet_sync_status_look
import homesafe.shared.generated.resources.fin_sheet_sync_status_ok
import homesafe.shared.generated.resources.fin_sheet_sync_syncing
import org.jetbrains.compose.resources.stringResource
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
        SyncLight.FAILED -> UiText.of(Res.string.fin_sheet_sync_line_failed, ago)
        SyncLight.LOOK -> UiText.plural(Res.plurals.fin_sheet_sync_line_look, problems, problems, ago)
        SyncLight.OK -> UiText.of(Res.string.fin_sheet_sync_line_ok, ago)
    }.resolve()
    Row(
        modifier
            .padding(horizontal = PageGutter)
            // A 48 dp touch target around the slim pill, announced as a button.
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fin_sheet_sync_open_action), onClick = onOpen)
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
                            SyncLight.FAILED -> stringResource(Res.string.fin_sheet_sync_status_failed)
                            SyncLight.LOOK -> stringResource(Res.string.fin_sheet_sync_status_look)
                            SyncLight.OK -> stringResource(Res.string.fin_sheet_sync_status_ok)
                        },
                        style = FinanceTheme.type.bodyStrong,
                        color = colors.textPrimary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                finance?.let { f ->
                    val t = f.fetchedAtEpochSeconds
                    Text(
                        UiText.of(Res.string.fin_sheet_sync_last_read, FinanceFormat.ago(now, t), FinanceFormat.dateTime(t, FinanceFormat.localOffsetSeconds(t))).resolve(),
                        style = FinanceTheme.type.label,
                        color = colors.textSecondary,
                    )
                }
                state.sheetIssue?.let { issue ->
                    Spacer(Modifier.height(6.dp))
                    val message = issue.message.takeUnless { it == UiText.Empty } ?: UiText.of(Res.string.fin_sheet_sync_issue_fallback)
                    Text(
                        (if (finance != null) UiText.of(Res.string.fin_sheet_sync_issue_showing_last, message) else message).resolve(),
                        style = FinanceTheme.type.label,
                        color = colors.loss,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(if (state.refreshing) Res.string.fin_sheet_sync_syncing else Res.string.fin_sheet_sync_now), colors.accent, onClick = onSyncNow)
                    finance?.sourceUrl?.let { url -> PillButton(stringResource(Res.string.fin_sheet_sync_open_sheet), colors.textSecondary, onClick = { uriHandler.openUri(url) }) }
                }
            }
        }
        if (health != null && health.sections.isNotEmpty()) {
            item(key = "sections-h") {
                SectionHeader(stringResource(Res.string.fin_sheet_sync_sections_title), subtitle = stringResource(Res.string.fin_sheet_sync_sections_subtitle))
            }
            // What needs a look first, then the rest in the sheet's order.
            items(health.sections.sortedBy { it.status == SectionStatus.OK }, key = { "s-${it.section.name}" }) { SectionRow(it) }
        }
        if (health != null && health.notes.isNotEmpty()) {
            item(key = "notes-h") { SectionHeader(stringResource(Res.string.fin_sheet_sync_notes_title)) }
            items(health.notes.size, key = { "n-$it" }) { i ->
                val note = health.notes[i]
                StatusRow(
                    color = colors.watch,
                    title = stringResource(note.section?.label ?: Res.string.fin_sheet_sync_note_whole_sheet),
                    trailing = null,
                    detail = note.message.resolve(),
                )
            }
        }
        if (health != null && health.charts.isNotEmpty()) {
            item(key = "charts-h") {
                SectionHeader(stringResource(Res.string.fin_sheet_sync_charts_title), subtitle = stringResource(Res.string.fin_sheet_sync_charts_subtitle))
            }
            val charts = health.charts.sortedBy { it.problem == null }
            items(charts.size, key = { "c-$it" }) { i ->
                val c = charts[i]
                StatusRow(
                    color = if (c.problem == null) colors.gain else colors.watch,
                    title = c.title.resolve(),
                    trailing = stringResource(if (c.problem == null) Res.string.fin_sheet_sync_chart_drawn else Res.string.fin_sheet_sync_chart_not_drawn),
                    detail = UiText.Joined(listOfNotNull(c.tab.takeIf { it.isNotBlank() }?.asUiText(), c.problem), UiText.of(Res.string.common_dot_separator)).resolve(),
                )
            }
        }
        item(key = "how") {
            FinePrint(stringResource(Res.string.fin_sheet_sync_how), Modifier.padding(top = 20.dp))
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
        title = stringResource(health.section.label),
        trailing = when (health.status) {
            SectionStatus.OK -> health.found?.resolve()
            SectionStatus.EMPTY -> stringResource(Res.string.fin_sheet_sync_section_nothing_read)
            SectionStatus.MISSING -> stringResource(Res.string.fin_sheet_sync_section_not_found)
        },
        detail = when (health.status) {
            SectionStatus.OK -> null
            SectionStatus.EMPTY -> stringResource(Res.string.fin_sheet_sync_section_empty_detail, stringResource(health.section.lookedFor))
            SectionStatus.MISSING -> stringResource(Res.string.fin_sheet_sync_section_missing_detail, stringResource(health.section.lookedFor))
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
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

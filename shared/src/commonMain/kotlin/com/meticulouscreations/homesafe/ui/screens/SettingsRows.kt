package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.settings_refresh
import org.jetbrains.compose.resources.stringResource

/*
 * What the Settings pages are laid out with. The main page is a few SettingsGroups of one-line
 * rows, each saying in its second line how things stand: a SettingsNavRow opens a page of its
 * own, for a section with lists and dialogs to it, and a SettingsExpandableRow opens out where
 * it is, for one that is only a few switches. The pages a row opens are SettingsPages.
 */

private val GROUP_SHAPE = RoundedCornerShape(16.dp)

private val ROW_ICON_SIZE = 24.dp

private val ROW_ICON_GAP = 12.dp

private val ROW_HORIZONTAL_PADDING = 16.dp

/** One card around a run of rows; put a [SettingsRowDivider] between each pair. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(GROUP_SHAPE)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), GROUP_SHAPE),
        content = content,
    )
}

/** The hairline between two rows of a [SettingsGroup], starting where their titles do. */
@Composable
internal fun SettingsRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = ROW_HORIZONTAL_PADDING + ROW_ICON_SIZE + ROW_ICON_GAP),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
    )
}

/** A row that opens a page of its own: an icon, what it is, how it stands ([summary]), and a chevron. */
@Composable
internal fun SettingsNavRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsRowLayout(
        icon = icon,
        title = title,
        summary = summary,
        trailing = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/**
 * A row that opens out in place: tapping it shows or hides [content] beneath it, inside the same
 * card. The [summary] stays while it's open, so the row reads the same either way. Whether it is
 * open is the caller's to keep. Screen readers hear it as something to expand or collapse.
 */
@Composable
internal fun SettingsExpandableRow(
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsRowLayout(
            icon = icon,
            title = title,
            summary = summary,
            trailing = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            modifier = Modifier
                .clickable(onClick = onToggle)
                .semantics {
                    if (expanded) {
                        collapse {
                            onToggle()
                            true
                        }
                    } else {
                        expand {
                            onToggle()
                            true
                        }
                    }
                },
        )
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = ROW_HORIZONTAL_PADDING, end = ROW_HORIZONTAL_PADDING, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsRowLayout(icon: ImageVector, title: String, summary: String, trailing: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(ROW_ICON_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(ROW_ICON_SIZE))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A page one tap below the Settings tab: a header — back, the page's name and a line about it,
 * and a refresh button where there is something to re-read ([onRefresh]) — over [content] in a
 * scrolling column. The shell hides its own top bar while one is up; the bottom nav stays.
 */
@Composable
internal fun SettingsPage(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = nestedHeaderVerticalPadding()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back), tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, textAlign = TextAlign.Center)
                Text(text = subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (onRefresh != null) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.settings_refresh), tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                // As much room as the back button takes, so the title stays centred.
                Spacer(Modifier.minimumInteractiveComponentSize().size(40.dp))
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = contentGutter())
                // Content padding, as on the main page: the last card scrolls clear of the floating nav.
                .padding(top = 8.dp, bottom = bottomNavClearance()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

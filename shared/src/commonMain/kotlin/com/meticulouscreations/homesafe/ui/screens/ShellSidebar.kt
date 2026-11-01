package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.meticulouscreations.homesafe.navigation.TOP_LEVEL_ROUTES
import com.meticulouscreations.homesafe.ui.AppSectionsHost
import com.meticulouscreations.homesafe.ui.WithTooltip
import com.meticulouscreations.homesafe.ui.assistant.LocalAssistant
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.ui.shortcutLabel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_drawer_title
import homesafe.shared.generated.resources.charging_drawer_title
import homesafe.shared.generated.resources.fitness_drawer_title
import homesafe.shared.generated.resources.packages_drawer_title
import homesafe.shared.generated.resources.shell_app_title
import homesafe.shared.generated.resources.shell_drawer_finance
import homesafe.shared.generated.resources.shell_drawer_section_apps
import homesafe.shared.generated.resources.shell_sidebar_assistant
import homesafe.shared.generated.resources.shell_sidebar_hide
import homesafe.shared.generated.resources.weather_drawer_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How wide the sidebar stands beside the content ([showsSidebar][com.meticulouscreations.homesafe.ui.showsSidebar]). */
internal val SIDEBAR_WIDTH = 248.dp

/** The apps in the sidebar, in the order the iPad's lists them and ⌘4 to ⌘9 reach them. */
internal val SIDEBAR_APPS = listOf(InnerApp.WEATHER, InnerApp.ATLAS, InnerApp.FITNESS, InnerApp.FINANCE, InnerApp.PACKAGES, InnerApp.CHARGING)

internal val InnerApp.title: StringResource
    get() = when (this) {
        InnerApp.WEATHER -> Res.string.weather_drawer_title
        InnerApp.ATLAS -> Res.string.atlas_drawer_title
        InnerApp.PACKAGES -> Res.string.packages_drawer_title
        InnerApp.FITNESS -> Res.string.fitness_drawer_title
        InnerApp.FINANCE -> Res.string.shell_drawer_finance
        InnerApp.CHARGING -> Res.string.charging_drawer_title
    }

/** The same pictures as the iPad's sidebar, near enough: a cloud, a map, a weight, a rising line, a bolt. */
internal val InnerApp.icon: ImageVector
    get() = when (this) {
        InnerApp.WEATHER -> Icons.Filled.WbCloudy
        InnerApp.ATLAS -> Icons.Filled.Map
        InnerApp.PACKAGES -> Icons.Filled.Inventory2
        InnerApp.FITNESS -> Icons.Filled.FitnessCenter
        InnerApp.FINANCE -> Icons.AutoMirrored.Filled.TrendingUp
        InnerApp.CHARGING -> Icons.Filled.Bolt
    }

/** The sidebar, for tests: there when the shell is laid out for a wide window. */
const val SIDEBAR_TEST_TAG = "shell_sidebar"

/** An app's row in the sidebar, for tests. */
fun sidebarAppTestTag(app: InnerApp): String = "sidebar_app_" + app.name.lowercase()

/** The row for [app]'s section [index] under its own, while it is open, for tests. */
fun sidebarSectionTestTag(app: InnerApp, index: Int): String = sidebarAppTestTag(app) + "_section_" + index

/**
 * Every destination down the start edge of a wide window: the shell's three tabs, then the apps
 * the drawer holds on a phone, each a destination of its own shown in the content area beside
 * ([ShellNavigation.sidebarApp]), as the iPad's sidebar has them. A row is selected while what it
 * opens is up; with a keyboard each says its shortcut (⌘1 to ⌘9, as the menu bar and the iPad's Go
 * menu have them). The assistant, which a hold on the floating nav opens elsewhere, has a row of
 * its own at the foot, there being no nav to hold.
 *
 * The tab rows carry the bottom nav's test tags, so a test (or the Android CLI) finds a tab the
 * same way whichever nav the window has.
 */
@Composable
internal fun ShellSidebar(nav: ShellNavigation, sections: AppSectionsHost, onHide: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .testTag(SIDEBAR_TEST_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.shell_app_title),
                style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.03.em),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            val hide = stringResource(Res.string.shell_sidebar_hide)
            WithTooltip(hide) {
                IconButton(onClick = onHide, modifier = Modifier.handCursor()) {
                    Icon(Icons.Filled.ViewSidebar, contentDescription = hide, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TOP_LEVEL_ROUTES.forEachIndexed { index, route ->
                SidebarItem(
                    icon = route.icon,
                    label = stringResource(route.label),
                    selected = nav.sidebarApp == null && nav.selectedTab == route,
                    shortcut = (index + 1).toString(),
                    onClick = { nav.selectTab(route) },
                    modifier = Modifier.testTag(bottomNavTestTag(route)),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(Res.string.shell_drawer_section_apps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
            )
            SIDEBAR_APPS.forEachIndexed { index, app ->
                val open = nav.sidebarApp == app
                SidebarItem(
                    icon = app.icon,
                    label = stringResource(app.title),
                    // While its sections are listed under it, the app's row steps back for the one that is up.
                    selected = open && sections.sections.isEmpty(),
                    shortcut = (TOP_LEVEL_ROUTES.size + index + 1).toString(),
                    onClick = { nav.openApp(app, Offset.Zero, nav.selectedTab) },
                    modifier = Modifier.testTag(sidebarAppTestTag(app)),
                    emphasised = open,
                )
                if (open) {
                    sections.sections.forEachIndexed { sectionIndex, section ->
                        SidebarItem(
                            icon = section.icon,
                            label = stringResource(section.label),
                            selected = sections.selected == sectionIndex,
                            shortcut = null,
                            onClick = { sections.select(sectionIndex) },
                            modifier = Modifier.padding(start = 20.dp).testTag(sidebarSectionTestTag(app, sectionIndex)),
                            compact = true,
                        )
                    }
                }
            }
        }
        val assistant = LocalAssistant.current
        if (assistant != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            val bounds = remember { arrayOf(Rect.Zero) }
            SidebarItem(
                icon = Icons.Filled.AutoAwesome,
                label = stringResource(Res.string.shell_sidebar_assistant),
                selected = assistant.isOpen,
                shortcut = "K",
                onClick = { assistant.toggle(bounds[0]) },
                modifier = Modifier.padding(vertical = 8.dp).onGloballyPositioned { bounds[0] = it.boundsInWindow() },
            )
        }
    }
}

/**
 * One row of the sidebar: an icon and a label, tinted and filled while [selected], lit under the
 * pointer, with its keyboard shortcut at the end where shortcuts are shown ([shortcutLabel]).
 */
@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    shortcut: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.onPrimaryContainer else colors.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 34.dp else 40.dp)
            .clip(shape)
            .background(
                when {
                    selected -> colors.primaryContainer
                    hovered -> colors.onSurface.copy(alpha = 0.06f)
                    else -> colors.surfaceContainerLowest
                },
            )
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Tab, onClick = onClick)
            .handCursor()
            // An app whose sections are listed under it is still the one that is up.
            .semantics { this.selected = selected || emphasised }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) content else colors.primary, modifier = Modifier.size(if (compact) 18.dp else 20.dp))
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (emphasised && !selected) Box(Modifier.size(6.dp).clip(CircleShape).background(colors.primary))
        shortcut?.let { shortcutLabel(it) }?.let { keys ->
            Box {
                Text(
                    text = keys,
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = if (selected) 0.7f else 0.45f),
                )
            }
        }
    }
}

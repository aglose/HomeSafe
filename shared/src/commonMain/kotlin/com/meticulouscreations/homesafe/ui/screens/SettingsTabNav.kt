package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.navigation.BackStackViewModelStores
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.ui.showsTwoPanes
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.settings_pane_empty
import homesafe.shared.generated.resources.settings_recognition_classifier_description
import homesafe.shared.generated.resources.settings_recognition_faces
import homesafe.shared.generated.resources.settings_recognition_faces_description
import homesafe.shared.generated.resources.settings_recognition_objects_separator
import homesafe.shared.generated.resources.settings_recognition_title
import homesafe.shared.generated.resources.settings_server_title
import homesafe.shared.generated.resources.uptime_link_description
import homesafe.shared.generated.resources.uptime_title
import org.jetbrains.compose.resources.stringResource

/** The Settings tab's root; the shell watches the back stack's depth to hide its own bar on nested screens. */
data object SettingsHomeRoute

/** This device's alert preferences: the notifications switch, what to hear about, and when. */
data object AlertsRoute

/** Away mode: who's home, the household's phones, and automatic presence. */
data object AwayRoute

/** The labelling screen for one of Frigate's custom classifiers. */
data class ClassifierRoute(val modelName: String)

/** Frigate's face library: who it recognises and the faces waiting for a name. */
data object FacesRoute

/** The server's diagnostics — address, load, detector, disk, retention, AI features — off the main page. */
data object ServerRoute

/** The server's uptime record, one tap below its diagnostics. */
data object UptimeRoute

/** The door sensors and when each one's openings notify (also opened from Home). */
data object DoorsRoute

/**
 * The Settings tab's own nested navigation: the settings page, and the pages its rows open —
 * alerts, away mode, a classifier's labelling screen, the face library, and the server's
 * diagnostics with its uptime record below them. [content] renders the settings page and
 * receives `open`, which pushes the route it is given.
 *
 * The pages with view models of their own get their own stores from [stores], cleared once they
 * are popped. Alerts, away mode and the server's page share the settings page's view model, so
 * they keep the surrounding store with it.
 *
 * In a window with room for two panes ([showsTwoPanes]: a tablet, a desktop) the page a row
 * opens stands beside the settings page rather than over it, as a tablet's own Settings does;
 * see [SettingsTwoPanes]. The back stack is the same one either way, so turning a tablet from
 * one layout to the other keeps whatever page was open.
 */
@Composable
fun SettingsTabNav(
    backStack: SnapshotStateList<Any>,
    stores: BackStackViewModelStores,
    content: @Composable (open: (Any) -> Unit) -> Unit,
) {
    val pop: () -> Unit = { backStack.removeLastOrNull() }
    val pages = entryProvider {
        entry<SettingsHomeRoute> { content { route -> backStack.add(route) } }
        entry<AlertsRoute> { AlertsSettingsScreen(onBack = pop) }
        entry<AwayRoute> { AwaySettingsScreen(onBack = pop) }
        entry<ClassifierRoute> { route -> stores.Scoped(route) { ClassifierLabelingScreen(modelName = route.modelName, onBack = pop) } }
        entry<FacesRoute> { route -> stores.Scoped(route) { FaceLibraryScreen(onBack = pop) } }
        entry<ServerRoute> { ServerSettingsScreen(onBack = pop, onOpenUptime = { backStack.add(UptimeRoute) }) }
        entry<UptimeRoute> { route -> stores.Scoped(route) { ServerUptimeScreen(onBack = pop) } }
        entry<DoorsRoute> { route -> stores.Scoped(route) { DoorsScreen(onBack = pop) } }
    }
    if (showsTwoPanes()) {
        SettingsTwoPanes(backStack, pages, content)
    } else {
        NavDisplay(
            backStack = backStack,
            onBack = pop,
            transitionSpec = { sharedAxis(forward = true) },
            popTransitionSpec = { sharedAxis(forward = false) },
            predictivePopTransitionSpec = { predictiveSharedAxis(it) },
            entryProvider = pages,
        )
    }
}

/**
 * Settings laid out for a wide window: the settings page down the start side at
 * [SETTINGS_LIST_PANE_WIDTH], and beside it, under the shell's top bar (which stays up — the
 * list it belongs to is still on screen), the page the last row opened, or a word on what goes
 * there before one has been.
 *
 * A row opens its page in place of the one beside it, rather than on top of it: tapping down the
 * list leafs through the pages, as on a tablet's own Settings, and Back from any of them closes
 * that side rather than walking back through every page looked at. A page's own way deeper (the
 * server's uptime) still stacks within that side.
 */
@Composable
internal fun SettingsTwoPanes(
    backStack: SnapshotStateList<Any>,
    pages: (Any) -> NavEntry<Any>,
    content: @Composable (open: (Any) -> Unit) -> Unit,
) {
    val openBeside: (Any) -> Unit = { route ->
        if (backStack.getOrNull(1) != route || backStack.size > 2) {
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            backStack.add(route)
        }
    }
    val detail = backStack.drop(1)
    val open = detail.firstOrNull()
    // One page beside the list is the last thing Back closes there; NavDisplay only pops what
    // has something under it, so that one is this handler's. Nothing beside the list, and Back
    // falls through to the shell (to Home).
    rememberPredictiveBack(enabled = detail.size == 1) { backStack.removeLastOrNull() }
    val topClearance = shellTopBarClearance()
    val detailWidth = shellContentWidth() - SETTINGS_LIST_PANE_WIDTH - 1.dp
    Row(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPaneWidth provides SETTINGS_LIST_PANE_WIDTH, LocalOpenSettingsPage provides open) {
            Box(Modifier.width(SETTINGS_LIST_PANE_WIDTH).fillMaxHeight()) { content(openBeside) }
        }
        VerticalDivider(
            modifier = Modifier.padding(top = topClearance, bottom = 24.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )
        CompositionLocalProvider(LocalPaneWidth provides detailWidth) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The page's own header steps in from the status bar; here the shell's bar is
                    // above it as well, so it starts under that instead.
                    .padding(top = topClearance)
                    .consumeWindowInsets(PaddingValues(top = topClearance))
                    .clipToBounds(),
            ) {
                if (detail.isEmpty()) {
                    SettingsPaneEmpty()
                } else {
                    NavDisplay(
                        backStack = detail,
                        onBack = { backStack.removeLastOrNull() },
                        // A row's page takes the last one's place in a fade-through, as nothing
                        // was drilled into; a page within it (uptime) arrives with the usual slide.
                        transitionSpec = {
                            if (targetState.previousEntries.size == initialState.previousEntries.size) SharedElementPush else sharedAxis(forward = true)
                        },
                        popTransitionSpec = { sharedAxis(forward = false) },
                        predictivePopTransitionSpec = { predictiveSharedAxis(it) },
                        entryProvider = pages,
                    )
                }
            }
        }
    }
}

/** What the page side shows before a row has opened one. */
@Composable
private fun SettingsPaneEmpty() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = contentGutter(), vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(Res.string.settings_pane_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** How wide the settings page is beside the page it opens: wide enough for a row's summary to read on one or two lines. */
internal val SETTINGS_LIST_PANE_WIDTH = 380.dp

/**
 * The Settings rows that teach the server who and what it's looking at: the face library when
 * Frigate's face recognition is on ([faceRecognitionEnabled] is null until the config has been
 * read), and one row per custom classifier ("Known Cars"). Renders nothing when there's neither.
 */
@Composable
fun RecognitionSection(
    models: List<ClassifierModel>,
    faceRecognitionEnabled: Boolean?,
    onOpen: (String) -> Unit,
    onOpenFaces: () -> Unit,
) {
    val faces = faceRecognitionEnabled == true
    if (models.isEmpty() && !faces) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.settings_recognition_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp),
        )
        SettingsGroup {
            if (faces) {
                SettingsNavRow(
                    icon = Icons.Filled.Face,
                    title = stringResource(Res.string.settings_recognition_faces),
                    summary = stringResource(Res.string.settings_recognition_faces_description),
                    onClick = onOpenFaces,
                    opens = FacesRoute,
                )
            }
            models.forEachIndexed { index, model ->
                if (faces || index > 0) SettingsRowDivider()
                SettingsNavRow(
                    icon = Icons.Filled.DirectionsCar,
                    title = model.displayName,
                    summary = stringResource(Res.string.settings_recognition_classifier_description, objectList(model.objects)),
                    onClick = { onOpen(model.name) },
                    opens = ClassifierRoute(model.name),
                )
            }
        }
    }
}

/**
 * The way into the server's diagnostics ([ServerSettingsScreen]), last on the page, with a
 * [summary] line — route, disk, health — so the page still says at a glance whether all is well.
 */
@Composable
internal fun ServerSummaryRow(summary: String, onOpen: () -> Unit) {
    SettingsNavRow(icon = Icons.Filled.Dns, title = stringResource(Res.string.settings_server_title), summary = summary, onClick = onOpen, opens = ServerRoute)
}

/** The way from the server's diagnostics into its uptime record ([ServerUptimeScreen]), a card of its own between the others. */
@Composable
internal fun UptimeLinkRow(onOpen: () -> Unit) {
    SettingsGroup {
        SettingsNavRow(
            icon = Icons.Filled.MonitorHeart,
            title = stringResource(Res.string.uptime_title),
            summary = stringResource(Res.string.uptime_link_description),
            onClick = onOpen,
        )
    }
}

/** A classifier's Frigate labels, which are data, as one phrase: "car", "car and truck". */
@Composable
private fun objectList(objects: List<String>): String =
    UiText.Joined(objects.map { it.asUiText() }, UiText.of(Res.string.settings_recognition_objects_separator)).resolve()

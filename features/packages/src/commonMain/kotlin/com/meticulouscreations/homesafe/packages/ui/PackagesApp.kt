package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.AssignmentReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.packages.PackagesUiState
import com.meticulouscreations.homesafe.packages.PackagesViewModel
import com.meticulouscreations.homesafe.packages.domain.PackagesProblem
import com.meticulouscreations.homesafe.packages.domain.ParcelChange
import com.meticulouscreations.homesafe.text.load
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.AppSection
import com.meticulouscreations.homesafe.ui.LocalAppSections
import com.meticulouscreations.homesafe.ui.appPageGutter
import com.meticulouscreations.homesafe.ui.assistant.AssistantScreenContext
import com.meticulouscreations.homesafe.ui.assistant.LocalAssistant
import com.meticulouscreations.homesafe.ui.assistant.assistantTrigger
import com.meticulouscreations.homesafe.ui.publishAppSections
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.ui.rememberPredictiveBackTransition
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.common_retry
import homesafe.shared.generated.resources.packages_close
import homesafe.shared.generated.resources.packages_loading
import homesafe.shared.generated.resources.packages_tab_all
import homesafe.shared.generated.resources.packages_tab_insights
import homesafe.shared.generated.resources.packages_tab_returns
import homesafe.shared.generated.resources.packages_tab_today
import homesafe.shared.generated.resources.packages_title
import homesafe.shared.generated.resources.packages_title_add
import homesafe.shared.generated.resources.packages_title_claim
import homesafe.shared.generated.resources.packages_title_parcel
import homesafe.shared.generated.resources.packages_title_setup
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The packages app's tabs, in nav order. */
enum class PackagesTab(val label: StringResource, val icon: ImageVector) {
    TODAY(Res.string.packages_tab_today, Icons.Filled.Inventory2),
    ALL(Res.string.packages_tab_all, Icons.Filled.Search),
    RETURNS(Res.string.packages_tab_returns, Icons.AutoMirrored.Filled.AssignmentReturn),
    INSIGHTS(Res.string.packages_tab_insights, Icons.Filled.Insights),
}

/** A page pushed over the tabs. */
@Immutable
sealed interface PackagesPage {
    data class Detail(val id: String) : PackagesPage

    data class Claim(val id: String) : PackagesPage

    data object Add : PackagesPage

    data object Setup : PackagesPage
}

/** Everything the screens can ask for, as one thing to hand down: the view model's side of the app. */
@Stable
class PackagesActions(
    val onClose: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onChange: (String, ParcelChange) -> Unit = { _, _ -> },
    val onForget: (String) -> Unit = {},
    val onAdd: (String, String?, String?, Boolean) -> Unit = { _, _, _, _ -> },
    val onSetPhoneOwner: (String?) -> Unit = {},
    val onSetNotify: (String, Boolean) -> Unit = { _, _ -> },
    val onSetHideNew: (String, Boolean) -> Unit = { _, _ -> },
    val onNoticeSeen: () -> Unit = {},
    val onAddSeen: () -> Unit = {},
    /** The address of a kept picture, or (a null id) the landing right now. */
    val pictureUrl: suspend (String?, String) -> String? = { _, _ -> null },
)

/**
 * The packages: an app of its own inside PercySafe, opened from the drawer. Everything on its way
 * to the house for everyone in it, read from their emails, and what the door camera saw of it when
 * it came. Back pops a page, then leaves the app ([onClose]). A notification's [link] opens on the
 * parcel it was about.
 */
@Composable
fun PackagesApp(onClose: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true, link: String? = null, onShowLink: () -> Unit = {}) {
    // The activity's instance, the one the drawer's card shares.
    val viewModel: PackagesViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val actions = remember(viewModel) {
        PackagesActions(
            onClose = { close() },
            onRefresh = { viewModel.refresh(readMail = true) },
            onChange = viewModel::change,
            onForget = viewModel::forget,
            onAdd = viewModel::add,
            onSetPhoneOwner = viewModel::setPhoneOwner,
            onSetNotify = viewModel::setNotify,
            onSetHideNew = viewModel::setHideNew,
            onNoticeSeen = viewModel::noticeSeen,
            onAddSeen = viewModel::addSeen,
            pictureUrl = viewModel::pictureUrl,
        )
    }
    PackagesAppContent(state, actions, modifier, active, link = link, onShowLink = onShowLink)
}

/** [PackagesApp] without its view model: everything on screen from one [state], for previews and tests as much as for the app. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PackagesAppContent(
    state: PackagesUiState,
    actions: PackagesActions,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    initialTab: PackagesTab = PackagesTab.TODAY,
    initialPage: PackagesPage? = null,
    link: String? = null,
    onShowLink: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val pages = remember { mutableStateListOf<PackagesPage>().apply { initialPage?.let(::add) } }
    AssistantScreenContext { "Packages app, ${tab.name.lowercase()} tab" + (pages.lastOrNull()?.let { ", $it page open" } ?: "") }
    val haptics = LocalHapticFeedback.current

    fun pop() {
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else actions.onClose()
    }

    val back = rememberPredictiveBack(enabled = active && pages.isNotEmpty()) { pop() }

    val showLink by rememberUpdatedState(onShowLink)
    LaunchedEffect(link) {
        if (link != null) {
            pages.clear()
            pages += PackagesPage.Detail(link)
            showLink()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it.load())
            actions.onNoticeSeen()
        }
    }
    LaunchedEffect(state.added) {
        if (state.added) {
            if (pages.lastOrNull() == PackagesPage.Add) pop()
            actions.onAddSeen()
        }
    }

    val fontFamily = albertSansFontFamily()
    val palette = remember { PackagesPalette() }
    val stamps = remember { StampsSeen() }
    // Remembered: a new instance through the static local would recompose the whole tree on every state change.
    val type = remember(fontFamily) { PackagesTypography(fontFamily) }
    CompositionLocalProvider(LocalPackagesPalette provides palette, LocalPackagesTypography provides type, LocalStampsSeen provides stamps) {
        val status = WindowInsets.statusBars.asPaddingValues()
        val nav = WindowInsets.navigationBars.asPaddingValues()
        val top = status.calculateTopPadding() + 62.dp
        BoxWithConstraints(modifier.fillMaxSize().background(palette.background).testTag("packages_app")) {
            // Centred at a readable width in a wide window (a desktop's, a tablet's): see appPageGutter.
            val gutter = appPageGutter(maxWidth)
            val tabPadding = PaddingValues(start = gutter, end = gutter, top = top, bottom = nav.calculateBottomPadding() + if (LocalAppSections.current != null) 28.dp else 108.dp)
            val pagePadding = PaddingValues(start = gutter, end = gutter, top = top, bottom = nav.calculateBottomPadding() + 28.dp)
            val page = pages.lastOrNull()
            val showTabs = pages.isEmpty() && state.loaded
            val inSidebar = publishAppSections(
                sections = if (state.loaded) PACKAGES_SECTIONS else emptyList(),
                selected = if (pages.isEmpty()) tab.ordinal else -1,
            ) { index ->
                pages.clear()
                tab = PackagesTab.entries[index]
            }

            val warmth = packageWarmth(state)
            PackagesBackdrop(Modifier.fillMaxWidth().height(420.dp), seed = 0.2f, warm = warmth, running = active)
            Box(Modifier.fillMaxWidth().height(420.dp).background(Brush.verticalGradient(0f to Color.Transparent, 1f to palette.background)))

            when {
                !state.loaded -> PackagesWaiting(state, pagePadding, active, onRetry = actions.onRefresh)

                else -> {
                    val target: Any = page ?: tab
                    // Which way the stack last moved, for the slide's direction; outside the snapshot system since it only steers an animation.
                    val depth = remember { intArrayOf(pages.size, 1) }
                    if (pages.size != depth[0]) {
                        depth[1] = if (pages.size > depth[0]) 1 else -1
                        depth[0] = pages.size
                    }
                    val previous: Any? = if (pages.isEmpty()) null else pages.getOrNull(pages.lastIndex - 1) ?: tab
                    rememberPredictiveBackTransition(target, previous, back, label = "packagesPage").AnimatedContent(
                        modifier = Modifier.imePadding(),
                        transitionSpec = { packagesTransition(initialState, targetState, forward = depth[1] > 0 && !back.inProgress) },
                    ) { shown ->
                        val open: (String) -> Unit = { pages += PackagesPage.Detail(it) }
                        when (shown) {
                            PackagesTab.TODAY -> TodayScreen(state, tabPadding, active, actions, onOpen = open, onAdd = { pages += PackagesPage.Add })
                            PackagesTab.ALL -> AllScreen(state, tabPadding, active, onOpen = open)
                            PackagesTab.RETURNS -> ReturnsScreen(state, tabPadding, actions, onOpen = open)
                            PackagesTab.INSIGHTS -> InsightsScreen(state, tabPadding)
                            is PackagesPage.Detail -> ParcelScreen(state, shown.id, pagePadding, active, actions, onClaim = { pages += PackagesPage.Claim(shown.id) }, onLeave = { if (pages.lastOrNull() == shown) pop() })
                            is PackagesPage.Claim -> ClaimScreen(state, shown.id, pagePadding)
                            PackagesPage.Add -> AddScreen(state, pagePadding, actions)
                            PackagesPage.Setup -> SetupScreen(state, pagePadding, actions)
                            else -> Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }

            PackagesTopBar(
                title = when (page) {
                    null -> stringResource(Res.string.packages_title)
                    is PackagesPage.Detail -> stringResource(Res.string.packages_title_parcel)
                    is PackagesPage.Claim -> stringResource(Res.string.packages_title_claim)
                    PackagesPage.Add -> stringResource(Res.string.packages_title_add)
                    PackagesPage.Setup -> stringResource(Res.string.packages_title_setup)
                },
                paged = page != null,
                onBack = ::pop,
                onAdd = if (page == null && state.loaded) ({ pages += PackagesPage.Add }) else null,
                onSetup = if (page == null && state.loaded) ({ pages += PackagesPage.Setup }) else null,
                setupNeedsAttention = state.loaded && (state.snapshot.viewer == null || !state.snapshot.mailOn),
            )

            AnimatedVisibility(
                visible = showTabs && !inSidebar,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            ) {
                PackagesNav(tab) { picked ->
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    tab = picked
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
        }
    }
}

/** Pages pushed over the tabs slide in from the right and back out the way they came; the tabs fade through one another, with a small lift. */
private fun packagesTransition(from: Any, to: Any, forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
    return when {
        from !is PackagesPage && to !is PackagesPage -> (fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.985f)) togetherWith fadeOut(tween(160))
        forward -> (slideInHorizontally(spec) { it } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(200)))
        else -> (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(tween(200)))
    }
}

/** Nothing to show yet: on its way, or why it isn't. */
@Composable
internal fun PackagesWaiting(state: PackagesUiState, padding: PaddingValues, active: Boolean, onRetry: () -> Unit) {
    val colors = PackagesTheme.colors
    val problem = state.sync.problem
    Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ParcelBox(Modifier.size(180.dp), warm = 0.3f, running = active)
        Text(
            state.sync.message?.resolve() ?: stringResource(Res.string.packages_loading),
            style = PackagesTheme.type.body,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp),
        )
        if (problem != null && problem != PackagesProblem.SIGNED_OUT) {
            PackagesButton(stringResource(Res.string.common_retry), onRetry, Modifier.padding(top = 16.dp), primary = false)
        }
    }
}

@Composable
private fun PackagesTopBar(title: String, paged: Boolean, onBack: () -> Unit, onAdd: (() -> Unit)?, onSetup: (() -> Unit)?, setupNeedsAttention: Boolean, closable: Boolean = LocalAppSections.current == null) {
    val colors = PackagesTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            // All but opaque behind the title: a list scrolling under it mustn't read through it.
            .background(Brush.verticalGradient(0f to colors.background.copy(alpha = 0.98f), 0.82f to colors.background.copy(alpha = 0.94f), 1f to Color.Transparent))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paged || closable) {
            PackagesRoundButton(
                if (paged) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                stringResource(if (paged) Res.string.common_back else Res.string.packages_close),
                onBack,
                Modifier.testTag("packages_back"),
            )
        }
        Text(title, style = PackagesTheme.type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (onAdd != null) PackagesRoundButton(Icons.Filled.Add, stringResource(Res.string.packages_title_add), onAdd, Modifier.testTag("packages_add"))
        if (onSetup != null) {
            Box {
                PackagesRoundButton(Icons.Filled.Tune, stringResource(Res.string.packages_title_setup), onSetup, Modifier.testTag("packages_setup"), tint = colors.textMuted)
                if (setupNeedsAttention) Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(8.dp).clip(CircleShape).background(colors.tape))
            }
        }
    }
}

/** The tabs, as a pill along the foot. */
@Composable
private fun PackagesNav(selected: PackagesTab, onSelect: (PackagesTab) -> Unit) {
    val colors = PackagesTheme.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth(0.94f)
            // Held, as the shell's own bar is, it opens the assistant.
            .assistantTrigger(LocalAssistant.current)
            .clip(CircleShape)
            .background(colors.surfaceRaised.copy(alpha = 0.96f))
            .border(1.dp, colors.hairline, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PackagesTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.tape.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
                    .semantics { this.selected = isSelected }
                    .testTag("packages_tab_${tab.name.lowercase()}")
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, contentDescription = null, tint = if (isSelected) colors.tape else colors.textFaint, modifier = Modifier.size(22.dp))
                Text(stringResource(tab.label), style = PackagesTheme.type.micro, color = if (isSelected) colors.text else colors.textFaint, maxLines = 1)
            }
        }
    }
}

/** The tabs as the shell's sidebar lists them, under its row (see publishAppSections). */
private val PACKAGES_SECTIONS = PackagesTab.entries.map { AppSection(it.label, it.icon) }

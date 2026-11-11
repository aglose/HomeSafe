package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.shell_return_to_app
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.minutes

/** How long the way back to an app stays on offer over the cameras before it is forgotten. */
private val RETURN_OFFER = 10.minutes

/**
 * The other half of an app's camera button ([ShellNavigation.hopToCameras]): once it has brought
 * the cameras up, this floats just above the tabs, under the same thumb, and goes back to the app
 * on the page it was left on. Between them the two make a there-and-back of one tap each way.
 *
 * It goes wherever the tabs' nav goes (under the drawer, a full-screen camera), and for good once
 * it is used, another app is opened, or it has waited [RETURN_OFFER].
 */
// An overlay, not a laid-out element: it fills the scaffold's layer itself.
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
internal fun ReturnToAppChip(nav: ShellNavigation, tab: TopLevelRoute) {
    val app = nav.returnApp
    LaunchedEffect(app) {
        if (app != null) {
            delay(RETURN_OFFER)
            nav.forgetReturn()
        }
    }
    // The app it last named, so it still reads as it leaves.
    var shown by remember { mutableStateOf(app ?: InnerApp.FINANCE) }
    if (app != null) shown = app
    // Just above the floating tabs; under a native bar or beside the rail, clear of the window's foot.
    val lift = if (LocalNativeTabBar.current || isCompactLandscape()) bottomNavClearance() else bottomNavClearance() - 16.dp
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            // Not beside the sidebar: there the apps are destinations of their own, a click away.
            visible = app != null && !nav.appsInSidebar && nav.showsBottomNav(tab),
            modifier = Modifier.padding(bottom = lift),
            enter = fadeIn(tween(NAV_TRANSITION_MS, easing = LinearEasing)) + slideInVertically(tween(NAV_TRANSITION_MS, easing = NavEnterEasing)) { it / 2 },
            exit = fadeOut(tween(NAV_TRANSITION_MS / 2, easing = LinearEasing)) + slideOutVertically(tween(NAV_TRANSITION_MS / 2)) { it / 2 },
        ) {
            ReturnChip(shown, onClick = { origin -> nav.returnToApp(origin, tab) })
        }
    }
}

/** The chip itself; [onClick] is told where its middle is, for the app to grow from. */
@Composable
internal fun ReturnChip(app: InnerApp, onClick: (origin: Offset) -> Unit, modifier: Modifier = Modifier) {
    var center by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier
            .onGloballyPositioned { center = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(LocalFrigateExtraColors.current.glassFill, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
            .clickable(role = Role.Button) { onClick(center) }
            .handCursor()
            .testTag("shell_return_to_app")
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Text(stringResource(Res.string.shell_return_to_app, stringResource(app.title)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Icon(app.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

@Preview(name = "Return chip · back to Finance", widthDp = 390, heightDp = 96)
@Composable
private fun ReturnChipPreview() {
    FrigateTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            ReturnChip(InnerApp.FINANCE, onClick = {})
        }
    }
}

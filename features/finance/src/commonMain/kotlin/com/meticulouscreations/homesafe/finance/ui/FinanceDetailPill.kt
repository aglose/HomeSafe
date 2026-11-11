package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.handCursor
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_bank_title
import homesafe.shared.generated.resources.finance_pill_back_to
import homesafe.shared.generated.resources.finance_pill_cameras
import homesafe.shared.generated.resources.finance_title_sheet_sync
import org.jetbrains.compose.resources.stringResource

/** The height of one level in the trail that fans out above the pill, and of the pill's own buttons. */
private val TRAIL_ROW = 48.dp

/** From the top of the pill's Back button up to the foot of the trail's first row: the pill's padding, the gap, the trail's padding. */
private val TRAIL_BASE = 6.dp + 8.dp + 6.dp

/** How far to the side of the trail the thumb may stray before letting go does nothing. */
private val TRAIL_STRAY = 56.dp

private val TRAIL_WIDTH = 248.dp

/** The thumb has left the trail: letting go there goes nowhere. */
private const val STRAYED = -1

/**
 * What stands where the tabs' nav was while a page is open over them, so the way back is under
 * the thumb rather than at the top of the screen: a pill whose wide half goes back (and says
 * where to), and whose other half goes straight to the cameras ([onCameras]; left out where there
 * are none to go to).
 *
 * [trail] is every level beneath the page that is up, nearest first, ending with the tab. A tap
 * on the back half goes up one. Held, the trail fans out above the thumb, which slides up it to
 * pick a level and lets go to land there: one gesture, however deep the page is. Sliding off to
 * the side and letting go stays put. [onPop] is told how many levels to go up.
 *
 * [scrubbing] draws the trail fanned out with that level picked, for a preview.
 */
// Public for the UI tests, which live in :app.
@Composable
fun FinanceDetailPill(
    trail: List<String>,
    onPop: (levels: Int) -> Unit,
    onCameras: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scrubbing: Int? = null,
) {
    if (trail.isEmpty()) return
    val colors = FinanceTheme.colors
    val haptics = LocalHapticFeedback.current
    val pop by rememberUpdatedState(onPop)
    // The level under the thumb while the trail is out; null while it is put away.
    var held by remember { mutableStateOf<Int?>(null) }
    val picked = held ?: scrubbing
    val backTo = stringResource(Res.string.finance_pill_back_to, trail.first())
    val further = trail.drop(1).mapIndexed { index, title -> stringResource(Res.string.finance_pill_back_to, title) to index + 2 }
    Column(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth(0.92f),
    ) {
        AnimatedVisibility(
            visible = picked != null,
            enter = fadeIn(tween(120)) + expandVertically(tween(180), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160), shrinkTowards = Alignment.Bottom),
        ) {
            PillTrail(trail, picked, Modifier.padding(bottom = 8.dp).testTag("finance_pill_trail"))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(colors.surfaceRaised.copy(alpha = 0.94f))
                .border(1.dp, colors.hairline, CircleShape)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(TRAIL_ROW)
                    .clip(CircleShape)
                    .background(if (picked != null) colors.gain.copy(alpha = 0.16f) else Color.Transparent)
                    .handCursor()
                    .testTag("finance_pill_back")
                    // A keyboard gets the tap: Tab reaches it, and Enter or Space goes up one level. Ahead of the
                    // semantics below, which would clear the focus it offers.
                    .onKeyEvent { event ->
                        val activates = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar
                        if (activates && event.type == KeyEventType.KeyUp) pop(1)
                        activates
                    }
                    .focusable()
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = backTo
                        onClick {
                            pop(1)
                            true
                        }
                        customActions = further.map { (label, levels) ->
                            CustomAccessibilityAction(label) {
                                pop(levels)
                                true
                            }
                        }
                    }
                    .pointerInput(trail.size) {
                        val row = TRAIL_ROW.toPx()
                        val base = TRAIL_BASE.toPx()
                        val stray = TRAIL_STRAY.toPx()
                        val width = TRAIL_WIDTH.toPx()

                        fun levelAt(position: Offset): Int = when {
                            position.x < -stray || position.x > width + stray || position.y > size.height + stray -> STRAYED
                            position.y > -base -> 0
                            else -> ((-position.y - base) / row).toInt().coerceAtMost(trail.lastIndex)
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var longPress = false
                            val up = try {
                                withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                            } catch (_: PointerEventTimeoutCancellationException) {
                                longPress = true
                                null
                            }
                            if (!longPress) {
                                if (up != null) {
                                    up.consume()
                                    pop(1)
                                }
                                return@awaitEachGesture
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            held = 0
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        // Taken away rather than let go (Android sends that as an up
                                        // already consumed): the trail is put away and nothing is picked.
                                        if (change.isConsumed) break
                                        change.consume()
                                        val level = held
                                        if (level != null && level != STRAYED) {
                                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                            pop(level + 1)
                                        }
                                        break
                                    }
                                    val level = levelAt(change.position)
                                    if (level != held) {
                                        held = level
                                        if (level != STRAYED) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                    change.consume()
                                }
                            } finally {
                                // Also when the gesture is taken away: the trail is never left out.
                                held = null
                            }
                        }
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(22.dp))
                Text(trail.first(), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // More than one level down: there is a trail worth holding for.
                if (trail.size > 1) Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            }
            if (onCameras != null) {
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(colors.hairline))
                val cameras = stringResource(Res.string.finance_pill_cameras)
                Column(
                    Modifier
                        .height(TRAIL_ROW)
                        .clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onCameras)
                        .handCursor()
                        .testTag("finance_pill_cameras")
                        .padding(horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
                    Text(cameras, style = FinanceTheme.type.micro, color = colors.textSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** The levels to go back to, the nearest at the foot (closest to the thumb) and the tab at the top. */
@Composable
private fun PillTrail(trail: List<String>, picked: Int?, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .width(TRAIL_WIDTH)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.hairline, shape)
            .padding(6.dp),
    ) {
        trail.indices.reversed().forEach { level ->
            val on = level == picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(TRAIL_ROW)
                    .clip(CircleShape)
                    .background(if (on) colors.gain.copy(alpha = 0.16f) else Color.Transparent)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Stepped in by depth, so the trail reads as the path it is.
                Spacer(Modifier.width(10.dp * (trail.lastIndex - level)))
                Text(trail[level], style = FinanceTheme.type.bodyStrong, color = if (on) colors.gain else colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Preview(name = "Detail pill · back and the cameras", widthDp = 390, heightDp = 200)
@Composable
private fun FinanceDetailPillPreview() {
    FinanceStage {
        Box(Modifier.fillMaxSize()) {
            FinanceDetailPill(
                trail = listOf(stringResource(Res.string.finance_title_sheet_sync), stringResource(FinanceTab.WALLET.label)),
                onPop = {},
                onCameras = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Preview(name = "Detail pill · held, the trail out", widthDp = 390, heightDp = 320)
@Composable
private fun FinanceDetailPillScrubPreview() {
    FinanceStage {
        Box(Modifier.fillMaxSize()) {
            FinanceDetailPill(
                trail = listOf(stringResource(Res.string.fin_bank_title), stringResource(Res.string.finance_title_sheet_sync), stringResource(FinanceTab.WALLET.label)),
                onPop = {},
                onCameras = {},
                modifier = Modifier.align(Alignment.BottomCenter),
                scrubbing = 1,
            )
        }
    }
}

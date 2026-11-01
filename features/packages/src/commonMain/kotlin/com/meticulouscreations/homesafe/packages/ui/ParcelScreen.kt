@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.packages.PackagesUiState
import com.meticulouscreations.homesafe.packages.domain.CameraVerdict
import com.meticulouscreations.homesafe.packages.domain.PackagesAnalysis
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ParcelChange
import com.meticulouscreations.homesafe.packages.domain.ParcelDays
import com.meticulouscreations.homesafe.packages.domain.ParcelEvent
import com.meticulouscreations.homesafe.packages.domain.ParcelProblem
import com.meticulouscreations.homesafe.packages.domain.ParcelStage
import com.meticulouscreations.homesafe.packages.domain.ParcelZone
import com.meticulouscreations.homesafe.packages.domain.PorchState
import com.meticulouscreations.homesafe.packages.domain.ReturnState
import com.meticulouscreations.homesafe.packages.ui.shader.PACKAGES_SCAN_SHADER
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.components.rememberShareText
import com.meticulouscreations.homesafe.weather.ui.shader.weatherShaderOrNull
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_save
import homesafe.shared.generated.resources.packages_action_archive
import homesafe.shared.generated.resources.packages_action_claim
import homesafe.shared.generated.resources.packages_action_forget
import homesafe.shared.generated.resources.packages_action_mark_delivered
import homesafe.shared.generated.resources.packages_action_owner
import homesafe.shared.generated.resources.packages_action_pin
import homesafe.shared.generated.resources.packages_action_surprise
import homesafe.shared.generated.resources.packages_action_surprise_body
import homesafe.shared.generated.resources.packages_action_track
import homesafe.shared.generated.resources.packages_action_unarchive
import homesafe.shared.generated.resources.packages_actions
import homesafe.shared.generated.resources.packages_claim_evidence
import homesafe.shared.generated.resources.packages_claim_head
import homesafe.shared.generated.resources.packages_claim_pictures
import homesafe.shared.generated.resources.packages_claim_share
import homesafe.shared.generated.resources.packages_claim_share_title
import homesafe.shared.generated.resources.packages_copy
import homesafe.shared.generated.resources.packages_detail_carrier
import homesafe.shared.generated.resources.packages_detail_left
import homesafe.shared.generated.resources.packages_detail_order
import homesafe.shared.generated.resources.packages_detail_owner
import homesafe.shared.generated.resources.packages_detail_shop
import homesafe.shared.generated.resources.packages_detail_total
import homesafe.shared.generated.resources.packages_detail_tracking
import homesafe.shared.generated.resources.packages_details
import homesafe.shared.generated.resources.packages_guess
import homesafe.shared.generated.resources.packages_masked_body
import homesafe.shared.generated.resources.packages_masked_owner
import homesafe.shared.generated.resources.packages_masked_title
import homesafe.shared.generated.resources.packages_notes_nickname
import homesafe.shared.generated.resources.packages_notes_notes
import homesafe.shared.generated.resources.packages_notes_title
import homesafe.shared.generated.resources.packages_porch_gone
import homesafe.shared.generated.resources.packages_porch_got_it
import homesafe.shared.generated.resources.packages_porch_inside_by
import homesafe.shared.generated.resources.packages_porch_inside_door
import homesafe.shared.generated.resources.packages_porch_out
import homesafe.shared.generated.resources.packages_porch_sensor
import homesafe.shared.generated.resources.packages_porch_still_out
import homesafe.shared.generated.resources.packages_porch_title
import homesafe.shared.generated.resources.packages_problem_attempted_body
import homesafe.shared.generated.resources.packages_problem_attempted_title
import homesafe.shared.generated.resources.packages_problem_body
import homesafe.shared.generated.resources.packages_problem_title
import homesafe.shared.generated.resources.packages_promised
import homesafe.shared.generated.resources.packages_proof_after
import homesafe.shared.generated.resources.packages_proof_ask
import homesafe.shared.generated.resources.packages_proof_before
import homesafe.shared.generated.resources.packages_proof_elsewhere
import homesafe.shared.generated.resources.packages_proof_gone_pictures
import homesafe.shared.generated.resources.packages_proof_hint
import homesafe.shared.generated.resources.packages_proof_no
import homesafe.shared.generated.resources.packages_proof_none
import homesafe.shared.generated.resources.packages_proof_said_by
import homesafe.shared.generated.resources.packages_proof_title
import homesafe.shared.generated.resources.packages_proof_visit_at
import homesafe.shared.generated.resources.packages_proof_yes
import homesafe.shared.generated.resources.packages_return_30_days
import homesafe.shared.generated.resources.packages_return_clear
import homesafe.shared.generated.resources.packages_return_left
import homesafe.shared.generated.resources.packages_return_mark_refunded
import homesafe.shared.generated.resources.packages_return_mark_sent
import homesafe.shared.generated.resources.packages_return_over
import homesafe.shared.generated.resources.packages_return_refunded
import homesafe.shared.generated.resources.packages_return_sent
import homesafe.shared.generated.resources.packages_return_start
import homesafe.shared.generated.resources.packages_return_started
import homesafe.shared.generated.resources.packages_return_title
import homesafe.shared.generated.resources.packages_return_unknown
import homesafe.shared.generated.resources.packages_signature_body
import homesafe.shared.generated.resources.packages_signature_title
import homesafe.shared.generated.resources.packages_stage_delivered
import homesafe.shared.generated.resources.packages_this_carrier
import homesafe.shared.generated.resources.packages_timeline
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** How long a link to a parcel not on the list waits for the list to catch up. */
private const val PARCEL_LINK_PATIENCE_MILLIS = 8_000L

/** All there is to know of one parcel: where it has got to, what the camera saw, what can be done about it, and what has happened so far. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ParcelScreen(state: PackagesUiState, id: String, padding: PaddingValues, active: Boolean, actions: PackagesActions, onClaim: () -> Unit, onLeave: () -> Unit) {
    val leave by rememberUpdatedState(onLeave)
    val snapshot = state.snapshot
    val parcel = snapshot.parcels.firstOrNull { it.id == id }
    var held by remember(id) { mutableStateOf(false) }
    if (parcel == null) {
        if (held) {
            // Forgotten a moment ago.
            LaunchedEffect(Unit) { leave() }
        } else {
            // A push can be newer than the list: ask, and only then is it a link to something no longer kept.
            LaunchedEffect(id) {
                actions.onRefresh()
                delay(PARCEL_LINK_PATIENCE_MILLIS)
                leave()
            }
            PackagesWaiting(state, padding, active, onRetry = actions.onRefresh)
        }
        return
    }
    SideEffect { held = true }
    val colors = PackagesTheme.colors
    val tint = parcel.tint()
    val today = snapshot.today
    val zone = state.zone
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp).testTag("packages_parcel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The head: a box of its own when there's something coming, the wrapping when it's a surprise.
        ParcelHead(parcel, today, zone, active)
        if (parcel.masked) {
            PackagesCard {
                Text(stringResource(Res.string.packages_masked_title), style = PackagesTheme.type.headline, color = colors.text)
                Text(stringResource(Res.string.packages_masked_body), style = PackagesTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        JourneyCard(parcel, today, active, tint)
        if (!parcel.delivered && !parcel.masked) {
            PackagesAnalysis.arrivalGuess(parcel, snapshot.parcels, zone)?.let { guess ->
                PackagesCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = colors.tape, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            pluralStringResource(Res.plurals.packages_guess, guess.basedOn, carrierName(parcel) ?: stringResource(Res.string.packages_this_carrier), hourText(guess.fromHour), hourText(guess.toHour), guess.basedOn),
                            style = PackagesTheme.type.body,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
        if (parcel.signature && !parcel.delivered) NoticeCard(Icons.Filled.ReportProblem, colors.warn, stringResource(Res.string.packages_signature_title), stringResource(Res.string.packages_signature_body))
        parcel.problem?.let { problem ->
            NoticeCard(
                Icons.Filled.ReportProblem,
                colors.warn,
                stringResource(if (problem == ParcelProblem.ATTEMPTED) Res.string.packages_problem_attempted_title else Res.string.packages_problem_title),
                stringResource(if (problem == ParcelProblem.ATTEMPTED) Res.string.packages_problem_attempted_body else Res.string.packages_problem_body),
            )
        }
        if (parcel.delivered && !parcel.masked) CameraProofCard(parcel, snapshot.cameraName, today, zone, actions)
        if (parcel.delivered && !parcel.masked) PorchCard(parcel, today, zone, snapshot.doorSensor, actions)
        if (!parcel.masked) {
            DetailsCard(parcel)
            ActionsCard(parcel, today, snapshot.people, snapshot.viewer, actions, onClaim)
            if (parcel.delivered) ReturnCard(parcel, today, actions)
            NotesCard(parcel, actions)
            TimelineCard(parcel.timeline, today, zone)
        } else if (parcel.owner.isNotBlank()) {
            Text(stringResource(Res.string.packages_masked_owner, PackagesFormat.person(parcel.owner)), style = PackagesTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun ParcelHead(parcel: Parcel, today: Int, zone: ParcelZone, active: Boolean) {
    val colors = PackagesTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().height(190.dp).clip(shape).border(1.dp, colors.hairline, shape)) {
        if (parcel.masked) {
            GiftWrap(Modifier.matchParentSize(), seed = (parcel.id.hashCode() and 255) / 255f, running = active)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color(0xB3120820))))
        } else {
            PackagesBackdrop(Modifier.matchParentSize(), seed = (parcel.id.hashCode() and 255) / 255f, warm = if (parcel.delivered || parcel.stage == ParcelStage.OUT_FOR_DELIVERY) 1f else 0.42f, running = active)
            ParcelBox(Modifier.align(Alignment.TopEnd).size(170.dp), warm = if (parcel.delivered || parcel.stage == ParcelStage.OUT_FOR_DELIVERY) 1f else 0.42f, running = active)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = if (parcel.masked) 18.dp else 128.dp, bottom = 16.dp)) {
            Text(parcelName(parcel), style = PackagesTheme.type.title, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("parcel_name"))
            Text(parcelStatus(parcel, today, zone), style = PackagesTheme.type.body, color = if (parcel.needsAttention) colors.warn else colors.textMuted, modifier = Modifier.padding(top = 3.dp))
        }
        if (parcel.delivered && !parcel.masked) DeliveredStamp(parcel.id, Modifier.align(Alignment.TopStart).padding(14.dp), good = parcel.porch != PorchState.MAY_BE_GONE)
    }
}

/** The parcels whose stamp has come down since the app opened: it thumps the once, and is just there after that. */
@Stable
internal class StampsSeen {
    private val seen = HashSet<String>()

    fun firstTime(id: String): Boolean = seen.add(id)

    fun wasSeen(id: String): Boolean = id in seen
}

internal val LocalStampsSeen = staticCompositionLocalOf { StampsSeen() }

/** "DELIVERED", stamped on with a thump the first time the page comes up. */
@Composable
private fun DeliveredStamp(id: String, modifier: Modifier = Modifier, good: Boolean = true) {
    val colors = PackagesTheme.colors
    val tint = if (good) colors.good else colors.warn
    val stamps = LocalStampsSeen.current
    val still = LocalInspectionMode.current
    val settled = remember(id) { still || stamps.wasSeen(id) }
    val hit = remember(id) { Animatable(if (settled) 1f else 2.4f) }
    val alpha = remember(id) { Animatable(if (settled) 1f else 0f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(id) {
        if (settled || !stamps.firstTime(id)) return@LaunchedEffect
        delay(260)
        alpha.animateTo(1f, tween(90))
        hit.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 1100f))
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val shape = RoundedCornerShape(7.dp)
    Text(
        stringResource(Res.string.packages_stage_delivered).uppercase(),
        style = PackagesTheme.type.micro,
        color = tint,
        modifier = modifier
            .graphicsLayer {
                scaleX = hit.value
                scaleY = hit.value
                rotationZ = -4f
                this.alpha = alpha.value
            }
            .clip(shape)
            .background(colors.background.copy(alpha = 0.55f))
            .border(1.5.dp, tint, shape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** Its four steps along the road, with the parcel of light at the one it has got to. */
@Composable
private fun JourneyCard(parcel: Parcel, today: Int, active: Boolean, tint: Color) {
    val colors = PackagesTheme.colors
    val stages = ParcelStage.entries
    PackagesCard {
        RouteStrip(parcel.progress(today), tint, running = active, margin = 8.dp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            stages.forEach { stage ->
                val reached = stage.ordinal <= parcel.stage.ordinal
                val dot by animateFloatAsState(if (stage == parcel.stage) 1f else 0f, tween(400), label = "stageDot")
                Text(
                    stringResource(stageName(stage)),
                    style = PackagesTheme.type.micro,
                    color = if (dot > 0.5f) {
                        tint
                    } else if (reached) {
                        colors.textMuted
                    } else {
                        colors.textFaint
                    },
                    maxLines = 2,
                )
            }
        }
        promiseText(parcel, today)?.takeIf { !parcel.delivered }?.let { promise ->
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.packages_promised), style = PackagesTheme.type.micro, color = colors.textFaint)
            Text(promise, style = PackagesTheme.type.headline, color = colors.text, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun NoticeCard(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, body: String) {
    val colors = PackagesTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(tint.copy(alpha = 0.10f)).border(1.dp, tint.copy(alpha = 0.34f), shape).padding(15.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(11.dp))
        Column {
            Text(title, style = PackagesTheme.type.bodyStrong, color = colors.text)
            Text(body, style = PackagesTheme.type.label, color = colors.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * What the door camera saw: its word for it and how sure that is, the two pictures of the landing
 * to wipe between, and a way to say it was right or wrong. The model is often unsure, and a person
 * looking at the pictures isn't.
 */
@Composable
private fun CameraProofCard(parcel: Parcel, cameraName: String, today: Int, zone: ParcelZone, actions: PackagesActions) {
    val colors = PackagesTheme.colors
    val verdict = parcel.verdict
    PackagesCard(title = stringResource(Res.string.packages_proof_title, cameraName)) {
        if (verdict == null) {
            Text(stringResource(if (parcel.leftNote != null) Res.string.packages_proof_elsewhere else Res.string.packages_proof_none), style = PackagesTheme.type.body, color = colors.textMuted)
            return@PackagesCard
        }
        val tint = colors.verdict(verdict)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(if (verdict == CameraVerdict.NOT_SEEN) Icons.Filled.VideocamOff else Icons.Filled.Videocam, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(verdictHeadline(verdict)), style = PackagesTheme.type.bodyStrong, color = colors.text, modifier = Modifier.testTag("parcel_verdict"))
                parcel.visitAt?.let { Text(stringResource(Res.string.packages_proof_visit_at, momentText(it, today, zone)), style = PackagesTheme.type.label, color = colors.textMuted) }
                if (parcel.verdictBy != null) Text(stringResource(Res.string.packages_proof_said_by, PackagesFormat.person(parcel.verdictBy)), style = PackagesTheme.type.label, color = colors.textFaint)
            }
        }
        if (parcel.hasFrames(after = false) && parcel.hasFrames(after = true)) {
            Spacer(Modifier.height(13.dp))
            BeforeAfter(parcel.id, "before", "after", actions)
            Spacer(Modifier.height(5.dp))
            Text(stringResource(Res.string.packages_proof_hint), style = PackagesTheme.type.label, color = colors.textFaint)
        }
        if (parcel.porch == PorchState.MAY_BE_GONE && "missing_before" in parcel.frames && "missing_after" in parcel.frames) {
            Spacer(Modifier.height(13.dp))
            Text(stringResource(Res.string.packages_proof_gone_pictures), style = PackagesTheme.type.bodyStrong, color = colors.warn)
            Spacer(Modifier.height(7.dp))
            BeforeAfter(parcel.id, "missing_before", "missing_after", actions)
        }
        // Only worth asking while nobody has said, and there are pictures to go on, or there were none.
        if (parcel.verdictBy == null) {
            Spacer(Modifier.height(13.dp))
            Text(stringResource(Res.string.packages_proof_ask), style = PackagesTheme.type.label, color = colors.textMuted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PackagesButton(stringResource(Res.string.packages_proof_yes), { actions.onChange(parcel.id, ParcelChange(cameraRight = true)) }, Modifier.weight(1f), icon = Icons.Filled.Check, tint = colors.good, primary = false)
                PackagesButton(stringResource(Res.string.packages_proof_no), { actions.onChange(parcel.id, ParcelChange(cameraRight = false)) }, Modifier.weight(1f), tint = colors.warn, primary = false)
            }
        }
    }
}

/**
 * The landing before and after, one over the other, with a handle to drag between them; it runs
 * across once by itself as it comes up, and a scan line sweeps down as though it were being looked
 * through, so the difference is seen before anyone touches it.
 */
@Composable
private fun BeforeAfter(parcelId: String, beforeName: String, afterName: String, actions: PackagesActions) {
    val colors = PackagesTheme.colors
    val before by produceState<String?>(null, parcelId, beforeName) { value = actions.pictureUrl(parcelId, beforeName) }
    val after by produceState<String?>(null, parcelId, afterName) { value = actions.pictureUrl(parcelId, afterName) }
    var dragged by remember { mutableStateOf(false) }
    val wipe = remember { Animatable(0.82f) }
    val sweep = remember { Animatable(-0.1f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        sweep.animateTo(1.1f, tween(1100, easing = LinearEasing))
    }
    LaunchedEffect(dragged) {
        if (dragged) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        wipe.animateTo(0.18f, tween(1000, easing = FastOutSlowInEasing))
        wipe.animateTo(0.5f, tween(600, easing = FastOutSlowInEasing))
    }
    val scanTint = colors.camera
    val shaded = LocalPackagesShaders.current
    val scan = remember(shaded) { if (shaded) weatherShaderOrNull(PACKAGES_SCAN_SHADER) else null }
    val shape = RoundedCornerShape(16.dp)
    val line = colors.tape
    var at by remember { mutableFloatStateOf(0.5f) }
    if (!dragged) at = wipe.value
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.75f)
            .clip(shape)
            .background(colors.surfaceRaised)
            .graphicsLayer {
                if (scan != null && sweep.value < 1.08f) {
                    scan.setUniform("size", size.width, size.height)
                    scan.setUniform("sweep", sweep.value)
                    scan.setUniform("tint", scanTint)
                    renderEffect = scan.renderEffect()
                } else {
                    renderEffect = null
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    dragged = true
                    at = (offset.x / size.width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = {
                    dragged = true
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }) { change, _ ->
                    change.consume()
                    at = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
            .testTag("before_after"),
    ) {
        val width = maxWidth
        if (LocalInspectionMode.current) LandingSketch(parcel = false, Modifier.fillMaxSize())
        before?.let { AsyncImage(model = it, contentDescription = stringResource(Res.string.packages_proof_before), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val x = size.width * at
                drawContext.canvas.save()
                drawContext.canvas.clipRect(x, 0f, size.width, size.height)
                drawContent()
                drawContext.canvas.restore()
            },
        ) {
            if (LocalInspectionMode.current) LandingSketch(parcel = true, Modifier.fillMaxSize())
            after?.let { AsyncImage(model = it, contentDescription = stringResource(Res.string.packages_proof_after), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        // The handle.
        Box(Modifier.fillMaxHeight().width(2.dp).offset { IntOffset((width.toPx() * at).roundToInt() - 1, 0) }.background(line))
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((width.toPx() * at).roundToInt() - 16.dp.roundToPx(), 0) }
                .size(32.dp)
                .clip(CircleShape)
                .background(line),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = colors.background, modifier = Modifier.size(20.dp))
        }
        WipeLabel(stringResource(Res.string.packages_proof_before), Modifier.align(Alignment.TopStart))
        WipeLabel(stringResource(Res.string.packages_proof_after), Modifier.align(Alignment.TopEnd))
    }
}

/** What a preview shows in place of a picture from the camera, which it has no network to fetch: a mat, and on it for the after picture, a box. */
@Composable
private fun LandingSketch(parcel: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color(0xFF3A4A44))
        drawRoundRect(Color(0xFF8A6A42), Offset(size.width * 0.18f, size.height * 0.24f), Size(size.width * 0.64f, size.height * 0.46f), CornerRadius(6f))
        if (parcel) {
            drawRoundRect(Color(0xFFC89A5E), Offset(size.width * 0.38f, size.height * 0.2f), Size(size.width * 0.3f, size.height * 0.42f), CornerRadius(4f))
            drawRect(Color(0xFFF0C678), Offset(size.width * 0.5f, size.height * 0.2f), Size(size.width * 0.05f, size.height * 0.42f))
        }
    }
}

@Composable
private fun WipeLabel(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Text(text.uppercase(), style = PackagesTheme.type.micro, color = Color.White, modifier = modifier.padding(8.dp).clip(shape).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 3.dp))
}

/** Where it is now: what the door sensor and the household have said, and the buttons for saying more. */
@Composable
private fun PorchCard(parcel: Parcel, today: Int, zone: ParcelZone, doorSensor: Boolean, actions: PackagesActions) {
    val colors = PackagesTheme.colors
    val porch = parcel.porch ?: return
    PackagesCard(title = stringResource(Res.string.packages_porch_title)) {
        val (headline, tint) = when (porch) {
            PorchState.ON_PORCH -> stringResource(Res.string.packages_porch_out) to colors.tape

            PorchState.INSIDE -> (
                if (parcel.porchBy == "door") stringResource(Res.string.packages_porch_inside_door) else stringResource(Res.string.packages_porch_inside_by, PackagesFormat.person(parcel.porchBy ?: ""))
                ) to colors.good

            PorchState.MAY_BE_GONE -> stringResource(Res.string.packages_porch_gone) to colors.warn
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (porch == PorchState.INSIDE) Icons.Filled.CheckCircle else Icons.Filled.Inventory2, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(headline, style = PackagesTheme.type.bodyStrong, color = colors.text, modifier = Modifier.weight(1f))
        }
        parcel.porchAt?.let { Text(momentText(it, today, zone), style = PackagesTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(top = 3.dp, start = 30.dp)) }
        if (porch == PorchState.ON_PORCH && doorSensor) {
            Text(stringResource(Res.string.packages_porch_sensor), style = PackagesTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(top = 7.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (porch == PorchState.INSIDE) {
            PackagesButton(stringResource(Res.string.packages_porch_still_out), { actions.onChange(parcel.id, ParcelChange(porch = PorchState.ON_PORCH)) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.Undo, primary = false)
        } else {
            PackagesButton(stringResource(Res.string.packages_porch_got_it), { actions.onChange(parcel.id, ParcelChange(porch = PorchState.INSIDE)) }, Modifier.fillMaxWidth().testTag("parcel_got_it"), icon = Icons.Filled.CheckCircle, tint = colors.good)
        }
    }
}

/** The numbers someone calling a carrier wants, each one a tap away from being copied. */
@Composable
private fun DetailsCard(parcel: Parcel) {
    val colors = PackagesTheme.colors
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val copyLabel = stringResource(Res.string.packages_copy)
    fun copy(value: String) {
        clipboard.setText(AnnotatedString(value))
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    @Composable
    fun DetailRow(label: String, value: String, copyable: Boolean = false) {
        Row(Modifier.fillMaxWidth().then(if (copyable) Modifier.pressable({ copy(value) }, copyLabel) else Modifier).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PackagesTheme.type.label, color = colors.textFaint, modifier = Modifier.width(92.dp))
            Text(value, style = PackagesTheme.type.bodyStrong, color = colors.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (copyable) Icon(Icons.Filled.ContentCopy, contentDescription = copyLabel, tint = colors.textFaint, modifier = Modifier.size(14.dp))
        }
    }
    PackagesCard(title = stringResource(Res.string.packages_details)) {
        parcel.shop?.let { DetailRow(stringResource(Res.string.packages_detail_shop), it) }
        carrierName(parcel)?.let { DetailRow(stringResource(Res.string.packages_detail_carrier), it) }
        parcel.tracking?.let { DetailRow(stringResource(Res.string.packages_detail_tracking), it, copyable = true) }
        parcel.orderId?.let { DetailRow(stringResource(Res.string.packages_detail_order), it, copyable = true) }
        parcel.total?.let { DetailRow(stringResource(Res.string.packages_detail_total), PackagesFormat.money(it)) }
        if (parcel.owner.isNotBlank()) DetailRow(stringResource(Res.string.packages_detail_owner), PackagesFormat.person(parcel.owner))
        parcel.leftNote?.let { DetailRow(stringResource(Res.string.packages_detail_left), it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsCard(parcel: Parcel, today: Int, people: List<String>, viewer: String?, actions: PackagesActions, onClaim: () -> Unit) {
    val colors = PackagesTheme.colors
    val uri = LocalUriHandler.current
    PackagesCard(title = stringResource(Res.string.packages_actions)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            parcel.url?.let { url ->
                PackagesButton(stringResource(Res.string.packages_action_track), { uri.openUri(url) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.OpenInNew, primary = false)
            }
            if (parcel.late || parcel.problem != null || parcel.delivered) {
                PackagesButton(stringResource(Res.string.packages_action_claim), onClaim, Modifier.fillMaxWidth().testTag("parcel_claim"), icon = Icons.Filled.ReportProblem, tint = colors.warn, primary = false)
            }
            if (!parcel.delivered) {
                PackagesButton(stringResource(Res.string.packages_action_mark_delivered), { actions.onChange(parcel.id, ParcelChange(markDelivered = true)) }, Modifier.fillMaxWidth(), icon = Icons.Filled.Check, tint = colors.good, primary = false)
            }
            // Only its owner can hide a thing: anyone else would be hiding it from themselves, with no way back.
            if (viewer != null && (parcel.owner == viewer || parcel.owner.isBlank())) {
                ToggleRow(Icons.Filled.CardGiftcard, stringResource(Res.string.packages_action_surprise), stringResource(Res.string.packages_action_surprise_body), parcel.surprise, colors.gift, onToggle = {
                    actions.onChange(parcel.id, ParcelChange(surprise = !parcel.surprise))
                }, Modifier.testTag("parcel_surprise"))
            }
            ToggleRow(Icons.Filled.PushPin, stringResource(Res.string.packages_action_pin), null, parcel.pinned, colors.tape, onToggle = {
                actions.onChange(parcel.id, ParcelChange(pinned = !parcel.pinned))
            })
            if (people.size > 1) {
                Text(stringResource(Res.string.packages_action_owner), style = PackagesTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.forEach { person ->
                        PackagesChoice(PackagesFormat.person(person), parcel.owner == person, { actions.onChange(parcel.id, ParcelChange(owner = person)) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                PackagesButton(
                    stringResource(if (parcel.archived) Res.string.packages_action_unarchive else Res.string.packages_action_archive),
                    { actions.onChange(parcel.id, ParcelChange(archived = !parcel.archived)) },
                    Modifier.weight(1f),
                    icon = Icons.Filled.Archive,
                    tint = colors.textMuted,
                    primary = false,
                )
                PackagesButton(stringResource(Res.string.packages_action_forget), { actions.onForget(parcel.id) }, Modifier.weight(1f), icon = Icons.Filled.DeleteOutline, tint = colors.danger, primary = false)
            }
        }
    }
}

@Composable
private fun ToggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String?, on: Boolean, tint: Color, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PackagesTheme.colors
    val knob by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 500f), label = "toggle")
    Row(modifier.fillMaxWidth().pressable(onToggle, title, Role.Switch).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (on) tint else colors.textFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = PackagesTheme.type.bodyStrong, color = colors.text)
            if (body != null) Text(body, style = PackagesTheme.type.label, color = colors.textFaint)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(42.dp).height(24.dp).clip(CircleShape).background(androidx.compose.ui.graphics.lerp(colors.surfaceRaised, tint, knob))) {
            Box(Modifier.offset { IntOffset((knob * 18.dp.toPx()).roundToInt() + 3.dp.roundToPx(), 3.dp.roundToPx()) }.size(18.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/** How long it can still go back, and how a return is going. */
@Composable
internal fun ReturnCard(parcel: Parcel, today: Int, actions: PackagesActions) {
    val colors = PackagesTheme.colors
    PackagesCard(title = stringResource(Res.string.packages_return_title)) {
        when (parcel.returnState) {
            ReturnState.NONE -> {
                val by = parcel.returnByDay
                if (by != null) {
                    val left = daysLeft(by, today)
                    val tint = if (left <= 3) colors.warn else colors.textMuted
                    Text(
                        if (left >= 0) pluralStringResource(Res.plurals.packages_return_left, left, left, dateText(by)) else stringResource(Res.string.packages_return_over, dateText(by)),
                        style = PackagesTheme.type.bodyStrong,
                        color = tint,
                    )
                    if (left >= 0) {
                        val whole = (parcel.deliveredAt?.let { by - (it / 86_400L).toInt() } ?: 30).coerceAtLeast(1)
                        val fraction = (left.toFloat() / whole).coerceIn(0f, 1f)
                        Box(Modifier.padding(top = 9.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(colors.surfaceRaised)) {
                            Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(CircleShape).background(tint))
                        }
                    }
                } else {
                    Text(stringResource(Res.string.packages_return_unknown), style = PackagesTheme.type.body, color = colors.textMuted)
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PackagesButton(stringResource(Res.string.packages_return_start), { actions.onChange(parcel.id, ParcelChange(returnState = ReturnState.STARTED)) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.Undo, primary = false)
                    if (by == null) {
                        PackagesButton(
                            stringResource(Res.string.packages_return_30_days),
                            { actions.onChange(parcel.id, ParcelChange(returnBy = ParcelDays.format(today + 30))) },
                            Modifier.weight(1f),
                            icon = Icons.Filled.Schedule,
                            primary = false,
                        )
                    } else {
                        PackagesButton(stringResource(Res.string.packages_return_clear), { actions.onChange(parcel.id, ParcelChange(returnBy = "")) }, Modifier.weight(1f), primary = false, tint = colors.textMuted)
                    }
                }
            }

            ReturnState.STARTED -> {
                Text(stringResource(Res.string.packages_return_started), style = PackagesTheme.type.bodyStrong, color = colors.tape)
                PackagesButton(stringResource(Res.string.packages_return_mark_sent), { actions.onChange(parcel.id, ParcelChange(returnState = ReturnState.SENT)) }, Modifier.fillMaxWidth().padding(top = 11.dp), primary = false)
            }

            ReturnState.SENT -> {
                Text(stringResource(Res.string.packages_return_sent), style = PackagesTheme.type.bodyStrong, color = colors.onTheWay)
                PackagesButton(stringResource(Res.string.packages_return_mark_refunded), { actions.onChange(parcel.id, ParcelChange(returnState = ReturnState.REFUNDED)) }, Modifier.fillMaxWidth().padding(top = 11.dp), primary = false, tint = colors.good)
            }

            ReturnState.REFUNDED -> Text(stringResource(Res.string.packages_return_refunded), style = PackagesTheme.type.bodyStrong, color = colors.good)
        }
    }
}

/** A name of its own and a few words, kept with it for everyone in the house. */
@Composable
private fun NotesCard(parcel: Parcel, actions: PackagesActions) {
    val colors = PackagesTheme.colors
    var nickname by remember(parcel.id) { mutableStateOf(parcel.nickname.orEmpty()) }
    var notes by remember(parcel.id) { mutableStateOf(parcel.notes.orEmpty()) }
    // What the relay holds comes through to a field nobody is part way through changing; what is being typed stays.
    var heldNickname by remember(parcel.id) { mutableStateOf(parcel.nickname.orEmpty()) }
    var heldNotes by remember(parcel.id) { mutableStateOf(parcel.notes.orEmpty()) }
    LaunchedEffect(parcel.nickname, parcel.notes) {
        if (nickname == heldNickname) nickname = parcel.nickname.orEmpty()
        if (notes == heldNotes) notes = parcel.notes.orEmpty()
        heldNickname = parcel.nickname.orEmpty()
        heldNotes = parcel.notes.orEmpty()
    }
    val changed = nickname != parcel.nickname.orEmpty() || notes != parcel.notes.orEmpty()
    PackagesCard(title = stringResource(Res.string.packages_notes_title)) {
        PackagesField(nickname, { nickname = it }, stringResource(Res.string.packages_notes_nickname), Modifier.testTag("parcel_nickname"))
        Spacer(Modifier.height(8.dp))
        PackagesField(notes, { notes = it }, stringResource(Res.string.packages_notes_notes), singleLine = false)
        if (changed) {
            PackagesButton(stringResource(Res.string.common_save), { actions.onChange(parcel.id, ParcelChange(nickname = nickname, notes = notes)) }, Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

@Composable
internal fun PackagesField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    val colors = PackagesTheme.colors
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = PackagesTheme.type.body.copy(color = colors.text),
        cursorBrush = SolidColor(colors.tape),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(shape).background(colors.surfaceRaised).border(1.dp, colors.hairline, shape).padding(horizontal = 13.dp, vertical = 11.dp)) {
                if (value.isEmpty()) Text(hint, style = PackagesTheme.type.body, color = colors.textFaint)
                inner()
            }
        },
    )
}

/** What has happened, newest first, each with a mark on a line. */
@Composable
private fun TimelineCard(timeline: List<ParcelEvent>, today: Int, zone: ParcelZone) {
    val colors = PackagesTheme.colors
    val events = remember(timeline) { timeline.filter { eventText(it) != null }.distinctBy { it.kind + it.at / 60 + (it.detail ?: "") }.asReversed() }
    if (events.isEmpty()) return
    PackagesCard(title = stringResource(Res.string.packages_timeline)) {
        events.forEachIndexed { index, event ->
            Row(Modifier.fillMaxWidth().riseIn(index)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(16.dp)) {
                    Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(if (index == 0) colors.tape else colors.textFaint))
                    if (index != events.lastIndex) Box(Modifier.width(1.dp).height(40.dp).background(colors.hairline))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.padding(bottom = 10.dp)) {
                    Text(stringResource(eventText(event)!!), style = PackagesTheme.type.bodyStrong, color = colors.text)
                    if (event.detail != null && event.kind != "camera_seen" && event.kind != "camera_confirmed" && event.detail != "frames") {
                        Text(event.detail, style = PackagesTheme.type.label, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(momentText(event.at, today, zone), style = PackagesTheme.type.label, color = colors.textFaint)
                }
            }
        }
    }
}

/** For asking what's the matter: what a shop or carrier will want to hear, ready to send. */
@Composable
internal fun ClaimScreen(state: PackagesUiState, id: String, padding: PaddingValues) {
    val colors = PackagesTheme.colors
    val parcel = state.snapshot.parcels.firstOrNull { it.id == id } ?: return
    val resolved = claimText(parcel, state.snapshot.cameraName, state.zone).resolve()
    val share = rememberShareText()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val shareTitle = stringResource(Res.string.packages_claim_share_title)
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.packages_claim_head), style = PackagesTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
        PackagesCard(title = stringResource(Res.string.packages_claim_evidence)) {
            Text(resolved, style = PackagesTheme.type.body, color = colors.text, modifier = Modifier.testTag("claim_text"))
        }
        if (share != null) {
            PackagesButton(stringResource(Res.string.packages_claim_share), { share(resolved, shareTitle) }, Modifier.fillMaxWidth(), icon = Icons.Filled.Share)
        }
        PackagesButton(
            stringResource(Res.string.packages_copy),
            {
                clipboard.setText(AnnotatedString(resolved))
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            Modifier.fillMaxWidth(),
            icon = Icons.Filled.ContentCopy,
            primary = share == null,
        )
        parcel.url?.let { url -> PackagesButton(stringResource(Res.string.packages_action_track), { uri.openUri(url) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.OpenInNew, primary = false) }
        if (parcel.frames.isNotEmpty()) {
            Text(stringResource(Res.string.packages_claim_pictures), style = PackagesTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.packages.ui.shader.PACKAGES_BACKDROP_SHADER
import com.meticulouscreations.homesafe.packages.ui.shader.PACKAGES_GIFT_SHADER
import com.meticulouscreations.homesafe.packages.ui.shader.PACKAGES_ROUTE_SHADER
import com.meticulouscreations.homesafe.packages.ui.shader.PARCEL_BOX_SHADER
import com.meticulouscreations.homesafe.ui.WithTooltip
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.weather.ui.shader.layersKeepTheirPixels
import com.meticulouscreations.homesafe.weather.ui.shader.rememberWeatherShaderClock
import com.meticulouscreations.homesafe.weather.ui.shader.weatherShaderOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val PACKAGES_FRAME_SECONDS = 1f / 30f

/**
 * The dusk the screens lie on: a lamp's cone, dust in it and tape sliding past. [warm] (0-1) is
 * how near things are: cold and blue with nothing coming, the porch light with something at the door.
 */
@Composable
internal fun PackagesBackdrop(modifier: Modifier = Modifier, seed: Float = 0f, warm: Float = 0.5f, running: Boolean = true) {
    val shaded = LocalPackagesShaders.current
    val shader = remember(shaded) { if (shaded) weatherShaderOrNull(PACKAGES_BACKDROP_SHADER) else null }
    val clock = rememberWeatherShaderClock(running, frameSeconds = PACKAGES_FRAME_SECONDS)
    val warmth by animateFloatAsState(warm, tween(1200, easing = FastOutSlowInEasing), label = "packagesWarm")
    Box(
        modifier
            .then(if (shader != null && layersKeepTheirPixels) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier)
            .drawBehind {
                if (shader != null) {
                    shader.setUniform("size", size.width, size.height)
                    shader.setUniform("time", clock.floatValue)
                    shader.setUniform("seed", seed)
                    shader.setUniform("warm", warmth)
                    drawRect(shader.brush())
                } else {
                    drawRect(Brush.linearGradient(listOf(Color(0xFF3A2A1C), Color(0xFF0D0A08))))
                }
            },
    )
}

/**
 * A parcel you could pick up: it turns slowly while something is on its way, and drops onto the
 * porch with a thump when [landed] first comes true. The box itself is the shader's; a plain
 * kraft square stands in where there are no shaders, so a page never has a hole in it.
 */
@Composable
internal fun ParcelBox(modifier: Modifier = Modifier, warm: Float = 0.5f, running: Boolean = true, landed: Boolean = false, spinSpeed: Float = 0.26f) {
    val shaded = LocalPackagesShaders.current
    val shader = remember(shaded) { if (shaded) weatherShaderOrNull(PARCEL_BOX_SHADER) else null }
    val clock = rememberWeatherShaderClock(running, frameSeconds = PACKAGES_FRAME_SECONDS)
    val warmth by animateFloatAsState(warm, tween(900), label = "boxWarm")
    val drop = remember { Animatable(0f) }
    val land = remember { Animatable(0f) }
    var thumped by remember { mutableStateOf(landed) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(landed) {
        if (landed && !thumped) {
            thumped = true
            drop.snapTo(1f)
            drop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 220f))
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            land.snapTo(1f)
            land.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        } else if (!landed) {
            thumped = false
        }
    }
    Box(
        modifier.drawBehind {
            if (shader != null) {
                shader.setUniform("size", size.width, size.height)
                shader.setUniform("time", clock.floatValue)
                shader.setUniform("spin", 0.62f + sin(clock.floatValue * spinSpeed) * 0.34f)
                shader.setUniform("tilt", 0f)
                shader.setUniform("drop", drop.value)
                shader.setUniform("land", land.value)
                shader.setUniform("warm", warmth)
                drawRect(shader.brush())
            } else {
                val edge = size.minDimension * 0.5f
                drawRoundRect(Color(0xFFC89A5E), Offset((size.width - edge) / 2, (size.height - edge) / 2), Size(edge, edge), CornerRadius(edge * 0.06f))
                drawRect(Color(0xFFF0C678), Offset(size.width / 2 - edge * 0.07f, (size.height - edge) / 2), Size(edge * 0.14f, edge))
            }
        },
    )
}

/**
 * The road a parcel is on, as a strip: [progress] (0-1) along it, the part behind it lit. Thin
 * enough to run in a list, and stopped when it isn't [running] or there is nothing moving.
 */
@Composable
internal fun RouteStrip(progress: Float, tint: Color, modifier: Modifier = Modifier, running: Boolean = true, margin: Dp = 8.dp) {
    val shaded = LocalPackagesShaders.current
    val shader = remember(shaded) { if (shaded) weatherShaderOrNull(PACKAGES_ROUTE_SHADER) else null }
    val moving = running && progress < 0.999f
    val clock = rememberWeatherShaderClock(moving, frameSeconds = PACKAGES_FRAME_SECONDS)
    val shown by animateFloatAsState(progress, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 60f), label = "routeProgress")
    val faint = PackagesTheme.colors.hairline
    Box(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .drawBehind {
                val inset = margin.toPx()
                if (shader != null) {
                    shader.setUniform("size", size.width, size.height)
                    shader.setUniform("time", clock.floatValue)
                    shader.setUniform("progress", shown)
                    shader.setUniform("margin", inset)
                    shader.setUniform("tint", tint)
                    drawRect(shader.brush())
                } else {
                    val y = size.height / 2
                    val span = size.width - inset * 2
                    drawLine(faint, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 2.dp.toPx())
                    drawLine(tint, Offset(inset, y), Offset(inset + span * shown, y), strokeWidth = 2.dp.toPx())
                    drawCircle(tint, 4.dp.toPx(), Offset(inset + span * shown, y))
                }
            },
    )
}

/** A surprise's foil wrapping, ribbon and all, behind what is laid over it. */
@Composable
internal fun GiftWrap(modifier: Modifier = Modifier, seed: Float = 0.3f, running: Boolean = true, content: @Composable BoxScope.() -> Unit = {}) {
    val shaded = LocalPackagesShaders.current
    val shader = remember(shaded) { if (shaded) weatherShaderOrNull(PACKAGES_GIFT_SHADER) else null }
    val clock = rememberWeatherShaderClock(running, frameSeconds = PACKAGES_FRAME_SECONDS)
    val colors = PackagesTheme.colors
    Box(
        modifier.drawBehind {
            if (shader != null) {
                shader.setUniform("size", size.width, size.height)
                shader.setUniform("time", clock.floatValue)
                shader.setUniform("seed", seed)
                shader.setUniform("tint", colors.gift)
                shader.setUniform("tint2", colors.giftDeep)
                drawRect(shader.brush())
            } else {
                drawRect(Brush.linearGradient(listOf(colors.gift, colors.giftDeep)))
            }
        },
        content = content,
    )
}

/** Scraps of cardboard and tape flung up and falling, once, each time [trigger] changes to something new. */
@Composable
internal fun CelebrationBurst(trigger: Any?, modifier: Modifier = Modifier) {
    val colors = PackagesTheme.colors
    val progress = remember { Animatable(1f) }
    val seed = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        seed.floatValue = Random.nextFloat() * 1000f
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1500, easing = LinearEasing))
    }
    val palette = remember(colors) { listOf(colors.kraft, colors.tape, colors.label, colors.good, colors.camera) }
    Canvas(modifier) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val random = Random(seed.floatValue.roundToInt())
        val origin = Offset(size.width / 2, size.height * 0.46f)
        repeat(46) {
            val angle = -PI.toFloat() * (0.08f + random.nextFloat() * 0.84f)
            val speed = size.minDimension * (0.45f + random.nextFloat() * 0.85f)
            val spin = (random.nextFloat() - 0.5f) * 13f
            val w = (5f + random.nextFloat() * 9f) * density
            val h = (3f + random.nextFloat() * 5f) * density
            val colour = palette[random.nextInt(palette.size)]
            val x = origin.x + cos(angle) * speed * t
            val y = origin.y + sin(angle) * speed * t + size.height * 1.05f * t * t
            val alpha = (1f - (t - 0.62f).coerceAtLeast(0f) / 0.38f).coerceIn(0f, 1f)
            rotate(spin * t * 57.3f, Offset(x, y)) {
                drawRect(colour.copy(alpha = alpha), Offset(x - w / 2, y - h / 2), Size(w, h))
            }
        }
    }
}

/** Something that gives under a finger and springs back. */
@Composable
internal fun Modifier.pressable(onClick: () -> Unit, label: String? = null, role: Role = Role.Button): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, spring(dampingRatio = 0.55f, stiffness = 900f), label = "press")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .handCursor()
        .clickable(interactionSource = source, indication = null, onClickLabel = label, role = role, onClick = onClick)
}

/** A round icon button, named for a screen reader by [label]. */
@Composable
internal fun PackagesRoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = PackagesTheme.colors.text, fill: Color = Color.Transparent) {
    WithTooltip(label) {
        Box(
            modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(fill)
                .handCursor()
                .clickable(onClick = onClick, role = Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

/** A small pill of colour and words: a carrier, an owner, a state. */
@Composable
internal fun PackagesPill(text: String, tint: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, filled: Boolean = false) {
    val shape = CircleShape
    Row(
        modifier
            .clip(shape)
            .background(tint.copy(alpha = if (filled) 0.22f else 0.11f))
            .border(1.dp, tint.copy(alpha = 0.26f), shape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Text(text, style = PackagesTheme.type.label, color = tint, maxLines = 1)
    }
}

/** A big, clear action. */
@Composable
internal fun PackagesButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = PackagesTheme.colors.tape, primary: Boolean = true, enabled: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    val colors = PackagesTheme.colors
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .background(if (primary) tint else tint.copy(alpha = 0.12f))
            .border(1.dp, if (primary) Color.Transparent else tint.copy(alpha = 0.3f), shape)
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (primary) colors.background else tint
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(text, style = PackagesTheme.type.bodyStrong, color = ink, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** A choice that is on or off, in a row of them. */
@Composable
internal fun PackagesChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = PackagesTheme.colors.tape) {
    val colors = PackagesTheme.colors
    val shape = CircleShape
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "choice")
    Text(
        text,
        style = PackagesTheme.type.label,
        color = androidx.compose.ui.graphics.lerp(colors.textMuted, colors.background, glow),
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(androidx.compose.ui.graphics.lerp(colors.surfaceRaised, tint, glow))
            .border(1.dp, colors.hairline, shape)
            .pressable(onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    )
}

/** A number that counts its way to a new value rather than jumping. */
@Composable
internal fun rememberCounted(value: Int): Int {
    val shown = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value) { shown.animateTo(value.toFloat(), tween(650, easing = FastOutSlowInEasing)) }
    return shown.value.roundToInt()
}

/** Comes up from a little below as it first appears, later the further down a list it is. */
@Composable
internal fun Modifier.riseIn(index: Int): Modifier {
    // Once: a list scrolled back to doesn't make everything rise again. A picture of the screen (a preview) has no time to rise in.
    val still = LocalInspectionMode.current
    var risen by rememberSaveable { mutableStateOf(still) }
    val rise = remember { Animatable(if (risen) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!risen) {
            rise.animateTo(1f, tween(420, delayMillis = (index.coerceAtMost(8)) * 45, easing = FastOutSlowInEasing))
            risen = true
        }
    }
    return graphicsLayer {
        alpha = rise.value
        translationY = (1f - rise.value) * 18.dp.toPx()
    }
}

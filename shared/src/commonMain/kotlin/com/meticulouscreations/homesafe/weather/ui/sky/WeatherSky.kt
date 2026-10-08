package com.meticulouscreations.homesafe.weather.ui.sky

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.meticulouscreations.homesafe.weather.ui.LocalWeatherShaders
import com.meticulouscreations.homesafe.weather.ui.shader.CELESTIAL_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.CLOUD_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.GLASS_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.PRECIP_SHADER
import com.meticulouscreations.homesafe.weather.ui.shader.weatherShaderOrNull
import kotlin.math.exp
import kotlin.random.Random

/**
 * A moment of a sky held still, for a preview, a test, or a reader who has asked for no motion:
 * the shaders' clock at [time] seconds, and, when [boltAge] isn't negative, a lightning strike
 * that many seconds old.
 */
data class SkyFreeze(val time: Float = 14f, val boltAge: Float = -1f)

/**
 * The sky for [scene], drawn by the four shaders in SkyShaders.kt and moving: cloud drifts and
 * billows on the wind, rain and snow fall, lightning strikes when there's thunder about. A
 * change of [scene] isn't a cut: every number the shaders run on eases to its new value, so
 * dusk comes on and a shower blows in.
 *
 * With [glass] the heavier rain also beads and runs on the glass, bending the sky behind it.
 * [freeze] holds it on one frame. The clock stops while the app is in the background, and while
 * [running] is false.
 *
 * Where the shaders can't be compiled (Android Studio's preview renderer), or are switched off
 * ([LocalWeatherShaders]), it is the scene's gradient alone.
 */
@Composable
fun WeatherSky(
    scene: SkyScene,
    modifier: Modifier = Modifier,
    glass: Boolean = true,
    running: Boolean = true,
    freeze: SkyFreeze? = null,
) {
    val shaded = LocalWeatherShaders.current
    val celestial = remember(shaded) { if (shaded) weatherShaderOrNull(CELESTIAL_SHADER) else null }
    val clouds = remember(shaded) { if (shaded) weatherShaderOrNull(CLOUD_SHADER) else null }
    val precip = remember(shaded) { if (shaded) weatherShaderOrNull(PRECIP_SHADER) else null }
    val drops = remember(shaded, glass) { if (shaded && glass) weatherShaderOrNull(GLASS_SHADER) else null }
    val animator = remember { SkyAnimator() }
    val held = freeze ?: if (LocalInspectionMode.current) SkyFreeze() else null
    val palette = remember(scene) { SkyPalette.of(scene) }
    // Set as part of composition, so the very first frame is already this scene; the redraw is
    // asked for once composition is done, since asking is a write to state.
    remember(scene, held) { animator.aim(scene, palette, held) }
    SideEffect { animator.redrawIfAimed() }

    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val ticking = held == null && running && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(animator, ticking) {
        if (!ticking) return@LaunchedEffect
        // An infinite animation's frames: anything that waits for animations to settle (a UI
        // test's idling) knows this one never will.
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                animator.step(((now - last) / 1e9f).coerceIn(0f, 0.1f))
                last = now
            }
        }
    }
    val unit = LocalDensity.current.density

    Box(
        modifier
            .clipToBounds()
            .graphicsLayer {
                // Read first, whatever follows: it is the only thing here that says "look again",
                // and the glass has to notice the rain starting as well as the rain going on.
                animator.frame.intValue
                val wet = animator.value(GLASS)
                if (drops != null && wet > 0.01f && size.minDimension > 0f) {
                    drops.setUniform("size", size.width, size.height)
                    drops.setUniform("time", animator.time)
                    drops.setUniform("unit", unit)
                    drops.setUniform("amount", wet)
                    renderEffect = drops.renderEffect()
                } else {
                    renderEffect = null
                }
            }
            .drawBehind {
                animator.frame.intValue
                if (celestial == null) {
                    drawRect(Brush.verticalGradient(listOf(animator.color(ZENITH), animator.color(HORIZON))))
                    return@drawBehind
                }
                celestial.setUniform("size", size.width, size.height)
                celestial.setUniform("time", animator.time)
                celestial.setUniform("unit", unit)
                celestial.setUniform("zenith", animator.color(ZENITH))
                celestial.setUniform("horizon", animator.color(HORIZON))
                celestial.setUniform("glow", animator.color(GLOW))
                celestial.setUniform("sunColor", animator.color(SUN_COLOR))
                celestial.setUniform("sun", animator.value(SUN_X), animator.value(SUN_Y), animator.value(SUN_DISC))
                celestial.setUniform("moon", animator.value(MOON_X), animator.value(MOON_Y), animator.value(MOON))
                celestial.setUniform("moonCycle", animator.value(MOON_CYCLE))
                celestial.setUniform("stars", animator.value(STARS))
                drawRect(celestial.brush())
            },
    ) {
        if (clouds != null) {
            // Half the pixels each way: cloud and fog have no edges to lose, and they are most of the work.
            Canvas(Modifier.matchParentSize().downscaled(CLOUD_DOWNSCALE)) {
                animator.frame.intValue
                if (animator.value(COVER) < 0.01f && animator.value(FOG) < 0.01f && animator.boltPower <= 0f) return@Canvas
                clouds.setUniform("size", size.width, size.height)
                clouds.setUniform("time", animator.time)
                clouds.setUniform("unit", unit / CLOUD_DOWNSCALE)
                clouds.setUniform("horizon", animator.color(HORIZON))
                clouds.setUniform("sunColor", animator.color(SUN_COLOR))
                clouds.setUniform("sun", animator.value(SUN_X), animator.value(SUN_Y), animator.value(SUN_DISC))
                clouds.setUniform("cloudLit", animator.color(CLOUD_LIT))
                clouds.setUniform("cloudShade", animator.color(CLOUD_SHADE))
                clouds.setUniform("cover", animator.value(COVER))
                clouds.setUniform("storm", animator.value(STORM))
                clouds.setUniform("drift", animator.driftX, animator.driftY)
                clouds.setUniform("fog", animator.value(FOG))
                clouds.setUniform("bolt", animator.boltX, animator.boltSeed, animator.boltAge, animator.boltPower)
                drawRect(clouds.brush())
            }
        }
        if (precip != null) {
            Canvas(Modifier.matchParentSize()) {
                animator.frame.intValue
                val bolt = animator.boltPower > 0f && animator.boltAge in 0f..0.45f
                if (animator.value(RAIN) < 0.01f && animator.value(SNOW) < 0.01f && !bolt) return@Canvas
                precip.setUniform("size", size.width, size.height)
                precip.setUniform("time", animator.time)
                precip.setUniform("unit", unit)
                precip.setUniform("rain", animator.value(RAIN))
                precip.setUniform("snow", animator.value(SNOW))
                precip.setUniform("wind", animator.value(WIND))
                precip.setUniform("light", animator.color(PRECIP_LIGHT))
                precip.setUniform("bolt", animator.boltX, animator.boltSeed, animator.boltAge, animator.boltPower)
                drawRect(precip.brush())
            }
        }
    }
}

private const val CLOUD_DOWNSCALE = 2

/** Ten minutes: the shaders' clock starts over, which shows as nothing more than the rain re-dealing itself. */
private const val CLOCK_WRAP_SECONDS = 600f

/**
 * Lays its content out [factor] times smaller each way and draws it scaled back up through an
 * offscreen layer. Where the platform keeps a layer's pixels at the layer's own size (Android),
 * a shader in it runs on a fraction of the screen's pixels and is stretched over the rest.
 */
private fun Modifier.downscaled(factor: Int): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed((width + factor - 1) / factor, (height + factor - 1) / factor))
    layout(width, height) {
        placeable.placeWithLayer(0, 0) {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = if (placeable.width > 0) width.toFloat() / placeable.width else 1f
            scaleY = if (placeable.height > 0) height.toFloat() / placeable.height else 1f
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }
}

// Where each of the sky's numbers lives in the animator: colours take three slots.
private const val ZENITH = 0
private const val HORIZON = 3
private const val GLOW = 6
private const val SUN_COLOR = 9
private const val CLOUD_LIT = 12
private const val CLOUD_SHADE = 15
private const val PRECIP_LIGHT = 18
private const val SUN_X = 21
private const val SUN_Y = 22
private const val SUN_DISC = 23
private const val MOON_X = 24
private const val MOON_Y = 25
private const val MOON = 26
private const val MOON_CYCLE = 27
private const val STARS = 28
private const val COVER = 29
private const val STORM = 30
private const val RAIN = 31
private const val SNOW = 32
private const val FOG = 33
private const val WIND = 34
private const val GLASS = 35
private const val THUNDER = 36
private const val SLOTS = 37

/**
 * The sky's state between frames: every uniform as it is now and as it is heading, the
 * shaders' clock, how far the wind has carried the cloud, and the lightning. Plain fields, not
 * snapshot state: only [frame] is observed, by the draw, so a frame costs a redraw and never a
 * recomposition.
 */
@Stable
internal class SkyAnimator(private val random: Random = Random.Default) {
    private val current = FloatArray(SLOTS)
    private val target = FloatArray(SLOTS)
    private var primed = false
    private var aimed = false

    /** Bumped once a frame; reading it in a draw is what makes that draw run again. */
    val frame = mutableIntStateOf(0)

    var time = 0f
        private set
    var driftX = 0f
        private set
    var driftY = 0f
        private set

    var boltX = 0.5f
        private set
    var boltSeed = 1f
        private set
    var boltPower = 0f
        private set
    private var boltAt = -100f
    private var nextBoltAt = 0f
    private var heldBoltAge: Float? = null

    val boltAge: Float get() = heldBoltAge ?: (time - boltAt)

    fun value(slot: Int): Float = current[slot]

    fun color(slot: Int): Color = Color(current[slot].coerceIn(0f, 1f), current[slot + 1].coerceIn(0f, 1f), current[slot + 2].coerceIn(0f, 1f))

    /** Heads for [scene]. The first call, and any with [freeze], arrives at once. */
    fun aim(scene: SkyScene, palette: SkyPalette, freeze: SkyFreeze?) {
        put(ZENITH, palette.zenith)
        put(HORIZON, palette.horizon)
        put(GLOW, palette.glow)
        put(SUN_COLOR, palette.sunColor)
        put(CLOUD_LIT, palette.cloudLit)
        put(CLOUD_SHADE, palette.cloudShade)
        put(PRECIP_LIGHT, palette.precipLight)
        target[SUN_X] = scene.sunX
        target[SUN_Y] = scene.sunY
        target[SUN_DISC] = palette.sunDisc
        target[MOON_X] = scene.moonX
        target[MOON_Y] = scene.moonY
        target[MOON] = palette.moon
        target[MOON_CYCLE] = scene.moonCycle
        target[STARS] = palette.stars
        target[COVER] = scene.cloudCover
        target[STORM] = scene.storm
        target[RAIN] = scene.rain
        target[SNOW] = scene.snow
        target[FOG] = scene.fog
        target[WIND] = scene.wind
        // Drops gather on the glass once it's properly raining, not in a drizzle.
        target[GLASS] = ((scene.rain - 0.3f) * 1.5f).coerceIn(0f, 1f)
        target[THUNDER] = scene.thunder
        if (!primed || freeze != null) {
            target.copyInto(current)
            primed = true
        }
        if (freeze != null) {
            time = freeze.time
            driftX = freeze.time * 0.02f * scene.wind
            driftY = freeze.time * 0.012f
            heldBoltAge = freeze.boltAge.takeIf { it >= 0f }
            boltPower = if (heldBoltAge != null) 1f else 0f
            boltX = 0.42f
            boltSeed = 3f
        } else {
            heldBoltAge = null
        }
        aimed = true
    }

    /** Has the draws run again if [aim] changed anything since they last did. */
    fun redrawIfAimed() {
        if (aimed) {
            aimed = false
            frame.intValue++
        }
    }

    /** One frame on, [dt] seconds later. */
    fun step(dt: Float) {
        // Wrapped well short of where a float runs out of room for the rain's fast clock (everything
        // timed by it re-deals itself at the wrap, once in ten minutes); the strike is carried over
        // so one in progress isn't cut off.
        time += dt
        if (time > CLOCK_WRAP_SECONDS) {
            time -= CLOCK_WRAP_SECONDS
            boltAt -= CLOCK_WRAP_SECONDS
            if (nextBoltAt > 0f) nextBoltAt -= CLOCK_WRAP_SECONDS
        }
        // Most things ease in a second or two; the sun and moon glide, since a scrub through the
        // day moves them a long way.
        val ease = 1f - exp(-dt * 1.6f)
        val glide = 1f - exp(-dt * 4.5f)
        for (i in 0 until SLOTS) {
            val k = if (i in SUN_X..MOON_Y && i != SUN_DISC) glide else ease
            current[i] += (target[i] - current[i]) * k
        }
        // The moon's phase isn't a thing to animate between places: it would spin.
        current[MOON_CYCLE] = target[MOON_CYCLE]
        val wind = current[WIND]
        driftX += dt * wind * 0.035f
        driftY += dt * (0.012f + 0.02f * kotlin.math.abs(wind))

        if (current[THUNDER] > 0.5f) {
            if (nextBoltAt <= 0f) nextBoltAt = time + 1.5f + random.nextFloat() * 3f
            if (time >= nextBoltAt) {
                boltAt = time
                boltX = 0.15f + random.nextFloat() * 0.7f
                boltSeed = 1f + random.nextFloat() * 50f
                boltPower = 0.65f + random.nextFloat() * 0.35f
                nextBoltAt = time + 3f + random.nextFloat() * 8f
            }
        } else {
            nextBoltAt = 0f
        }
        if (time - boltAt > 1.5f) boltPower = 0f
        frame.intValue++
    }

    private fun put(slot: Int, color: Color) {
        target[slot] = color.red
        target[slot + 1] = color.green
        target[slot + 2] = color.blue
    }
}

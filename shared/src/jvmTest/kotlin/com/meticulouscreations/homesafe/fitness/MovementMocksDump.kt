package com.meticulouscreations.homesafe.fitness

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.ui.FitnessCardShape
import com.meticulouscreations.homesafe.fitness.ui.FitnessPalette
import com.meticulouscreations.homesafe.fitness.ui.FitnessTheme
import com.meticulouscreations.homesafe.fitness.ui.FitnessTypography
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessPalette
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessTypography
import com.meticulouscreations.homesafe.fitness.ui.movement.AnatomyPlate
import com.meticulouscreations.homesafe.fitness.ui.movement.HeadId
import com.meticulouscreations.homesafe.fitness.ui.movement.HeadNames
import com.meticulouscreations.homesafe.fitness.ui.movement.MOVEMENT_SHADERS
import com.meticulouscreations.homesafe.fitness.ui.movement.Movement
import com.meticulouscreations.homesafe.fitness.ui.movement.MovementFigure
import com.meticulouscreations.homesafe.fitness.ui.movement.MovementStyle
import com.meticulouscreations.homesafe.fitness.ui.movement.REP_SECONDS
import com.meticulouscreations.homesafe.fitness.ui.movement.View
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.fail

/**
 * Draws the movement mocks (MovementStyles.kt) to pictures to choose between: does nothing
 * unless MOVEMENT_MOCKS_OUT names a directory. For each style, a sheet of every movement at the
 * start, middle and end of a rep; the workout list as it would look with the figures in it; and,
 * with MOVEMENT_MOCKS_SPRITES set, each movement's whole rep as a grid of frames to play back.
 */
class MovementMocksDump {

    @Test
    fun dump() {
        val out = System.getenv("MOVEMENT_MOCKS_OUT")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        out.mkdirs()
        // A shader that doesn't compile falls back to a plain drawing without a word: say so here instead.
        for ((name, source) in MOVEMENT_SHADERS) {
            try {
                RuntimeEffect.makeForShader(source)
            } catch (e: Throwable) {
                fail("The $name shader does not compile: ${e.message}")
            }
        }
        val only = System.getenv("MOVEMENT_MOCKS_STYLE")?.takeIf { it.isNotBlank() }
        val what = System.getenv("MOVEMENT_MOCKS_WHAT")?.takeIf { it.isNotBlank() }?.split(',') ?: listOf("atlas", "sheet", "list")
        val styles = MovementStyle.entries.filter { only == null || it.name.equals(only, ignoreCase = true) }
        for (style in styles) {
            val name = style.name.lowercase()
            if ("atlas" in what) {
                for (view in View.entries) {
                    still(ATLAS_W, ATLAS_H, 2f) { Atlas(view, style) }.save(File(out, "atlas-$name-${view.name.lowercase()}.png"))
                    if (System.getenv("MOVEMENT_MOCKS_BARE") != null) {
                        // The shoulder and the hip, close to: what a shape is judged by.
                        val whole = still(300, 660, 5f) { Atlas(view, style, labelled = false) }
                        whole.crop(0.1f, 0.14f, 0.9f, 0.42f).save(File(out, "zoom-$name-${view.name.lowercase()}-shoulder.png"))
                        whole.crop(0.1f, 0.38f, 0.9f, 0.66f).save(File(out, "zoom-$name-${view.name.lowercase()}-hip.png"))
                    }
                }
            }
            if ("sheet" in what) still(SPRITE_W * MOMENTS.size, SPRITE_H * Movement.entries.size, 1.5f) { Sheet(style) }.save(File(out, "sheet-$name.png"))
            if ("list" in what) still(412, 740, 2f) { WorkoutListMock(style) }.save(File(out, "list-$name.png"))
            System.getenv("MOVEMENT_MOCKS_CLOSE")?.takeIf { it.isNotBlank() }?.split(',')?.forEach { wanted ->
                val movement = Movement.valueOf(wanted.uppercase())
                still(SPRITE_W * MOMENTS.size, SPRITE_H, 3f) { Row { for (moment in MOMENTS) Tile(movement, style, { moment }, Modifier.size(SPRITE_W.dp, SPRITE_H.dp), labelled = false) } }
                    .save(File(out, "close-$name-${wanted.lowercase()}.png"))
            }
        }
        if ("rep" !in what) return
        // MOVEMENT_MOCKS_REPS names the movements to animate (the stone figure's shader is slow off a GPU); all of them otherwise.
        val reps = System.getenv("MOVEMENT_MOCKS_REPS")?.takeIf { it.isNotBlank() }?.split(',')?.map { it.uppercase() }
        for (style in styles) {
            for (movement in Movement.entries.filter { reps == null || it.name in reps }) {
                sprite(style, movement, File(out, "rep-${style.name.lowercase()}-${movement.name.lowercase()}.webp"))
            }
        }
    }

    private fun Image.save(file: File) = file.writeBytes(encodeToData(EncodedImageFormat.PNG)!!.bytes)

    /** The part of this picture between the given fractions of its width and height. */
    private fun Image.crop(left: Float, top: Float, right: Float, bottom: Float): Image {
        val surface = Surface.makeRasterN32Premul(((right - left) * width).roundToInt(), ((bottom - top) * height).roundToInt())
        surface.canvas.drawImage(this, -left * width, -top * height)
        return surface.makeImageSnapshot()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun scene(widthDp: Int, heightDp: Int, density: Float, content: @Composable () -> Unit) = ImageComposeScene(
        width = (widthDp * density).roundToInt(),
        height = (heightDp * density).roundToInt(),
        density = Density(density),
        layoutDirection = LayoutDirection.Ltr,
        coroutineContext = Dispatchers.Unconfined,
    ) {
        FrigateTheme {
            val family = albertSansFontFamily()
            val type = remember(family) { FitnessTypography(family) }
            CompositionLocalProvider(LocalFitnessPalette provides FitnessPalette(), LocalFitnessTypography provides type) { content() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun still(widthDp: Int, heightDp: Int, density: Float, content: @Composable () -> Unit): Image {
        val scene = scene(widthDp, heightDp, density, content)
        try {
            // A few frames, so the fonts have loaded.
            var image = scene.render(0)
            for (frame in 1..6) image = scene.render(frame * 16_000_000L)
            return image
        } finally {
            scene.close()
        }
    }

    /** A whole rep of [movement] as [SPRITE_COLUMNS] × [SPRITE_ROWS] frames in one picture. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun sprite(style: MovementStyle, movement: Movement, file: File) {
        val clock = mutableFloatStateOf(0f)
        // The stone figure's shader is slow off a GPU: fewer and smaller frames for it.
        val heavy = style == MovementStyle.MARBLE
        val density = if (heavy) 1.25f else 1.75f
        val rows = if (heavy) 3 else SPRITE_ROWS
        val scene = scene(SPRITE_W, SPRITE_H, density) { Tile(movement, style, { clock.floatValue }, Modifier.fillMaxSize(), framed = false) }
        repeat(4) { scene.render(it * 16_000_000L) }
        val width = (SPRITE_W * density).roundToInt()
        val height = (SPRITE_H * density).roundToInt()
        val sheet = Surface.makeRasterN32Premul(width * SPRITE_COLUMNS, height * rows)
        try {
            val frames = SPRITE_COLUMNS * rows
            for (frame in 0 until frames) {
                clock.floatValue = frame * REP_SECONDS / frames
                Snapshot.sendApplyNotifications()
                val image = scene.render(frame * 16_000_000L)
                sheet.canvas.drawImage(image, (frame % SPRITE_COLUMNS * width).toFloat(), (frame / SPRITE_COLUMNS * height).toFloat())
            }
            file.writeBytes(sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.WEBP, 86)!!.bytes)
        } finally {
            scene.close()
            sheet.close()
        }
    }

    private companion object {
        const val ATLAS_W = 470
        const val ATLAS_H = 660
        const val SPRITE_COLUMNS = 8
        const val SPRITE_ROWS = 4
    }
}

/** The size the full figure is drawn at: about what an open exercise card has for it. */
private const val SPRITE_W = 360
private const val SPRITE_H = 330

/** The start of a rep, half way up, and the top of it. */
private val MOMENTS = listOf(0f, 0.8f, 1.45f)

// The heads' names, as an anatomy text gives them. Words, so they live with whoever draws the figure: here, the mock.
private val NAMES = mapOf(
    HeadId.STERNOCLEIDOMASTOID to "Sternocleidomastoid",
    HeadId.TRAPEZIUS_UPPER to "Trapezius\nupper fibres",
    HeadId.TRAPEZIUS_MIDDLE to "Trapezius\nmiddle fibres",
    HeadId.TRAPEZIUS_LOWER to "Trapezius\nlower fibres",
    HeadId.DELTOID_ANTERIOR to "Deltoid\nanterior head",
    HeadId.DELTOID_LATERAL to "Deltoid\nlateral head",
    HeadId.DELTOID_POSTERIOR to "Deltoid\nposterior head",
    HeadId.PECTORALIS_CLAVICULAR to "Pectoralis major\nclavicular head",
    HeadId.PECTORALIS_STERNAL to "Pectoralis major\nsternocostal head",
    HeadId.SERRATUS_ANTERIOR to "Serratus anterior",
    HeadId.RECTUS_ABDOMINIS to "Rectus abdominis",
    HeadId.EXTERNAL_OBLIQUE to "External oblique",
    HeadId.LATISSIMUS_DORSI to "Latissimus dorsi",
    HeadId.TERES_MAJOR to "Teres major",
    HeadId.TERES_MINOR to "Teres minor",
    HeadId.INFRASPINATUS to "Infraspinatus",
    HeadId.ERECTOR_SPINAE to "Erector spinae",
    HeadId.BICEPS_LONG to "Biceps brachii\nlong head",
    HeadId.BICEPS_SHORT to "Biceps brachii\nshort head",
    HeadId.BRACHIALIS to "Brachialis",
    HeadId.TRICEPS_LONG to "Triceps brachii\nlong head",
    HeadId.TRICEPS_LATERAL to "Triceps brachii\nlateral head",
    HeadId.TRICEPS_MEDIAL to "Triceps brachii\nmedial head",
    HeadId.BRACHIORADIALIS to "Brachioradialis",
    HeadId.WRIST_FLEXORS to "Flexor carpi radialis",
    HeadId.FLEXOR_CARPI_ULNARIS to "Flexor carpi ulnaris",
    HeadId.EXTENSOR_CARPI_RADIALIS to "Extensor carpi radialis",
    HeadId.EXTENSOR_DIGITORUM to "Extensor digitorum",
    HeadId.EXTENSOR_CARPI_ULNARIS to "Extensor carpi ulnaris",
    HeadId.GLUTEUS_MEDIUS to "Gluteus medius",
    HeadId.GLUTEUS_MAXIMUS to "Gluteus maximus",
    HeadId.TENSOR_FASCIAE_LATAE to "Tensor fasciae latae",
    HeadId.SARTORIUS to "Sartorius",
    HeadId.RECTUS_FEMORIS to "Quadriceps\nrectus femoris",
    HeadId.VASTUS_LATERALIS to "Quadriceps\nvastus lateralis",
    HeadId.VASTUS_MEDIALIS to "Quadriceps\nvastus medialis",
    HeadId.ADDUCTOR_LONGUS to "Adductor longus",
    HeadId.ADDUCTOR_MAGNUS to "Adductor magnus",
    HeadId.GRACILIS to "Gracilis",
    HeadId.BICEPS_FEMORIS_LONG to "Biceps femoris\nlong head",
    HeadId.BICEPS_FEMORIS_SHORT to "Biceps femoris\nshort head",
    HeadId.SEMITENDINOSUS to "Semitendinosus",
    HeadId.SEMIMEMBRANOSUS to "Semimembranosus",
    HeadId.TIBIALIS_ANTERIOR to "Tibialis anterior",
    HeadId.EXTENSOR_DIGITORUM_LONGUS to "Extensor digitorum longus",
    HeadId.PERONEUS_LONGUS to "Fibularis longus",
    HeadId.GASTROCNEMIUS_LATERAL to "Gastrocnemius\nlateral head",
    HeadId.GASTROCNEMIUS_MEDIAL to "Gastrocnemius\nmedial head",
    HeadId.SOLEUS to "Soleus",
)

@Composable
private fun headNames(): HeadNames {
    val style = FitnessTheme.type.label.copy(fontSize = 9.5.sp, lineHeight = 11.sp)
    return remember(style) { HeadNames(style) { NAMES.getValue(it) } }
}

@Composable
private fun Atlas(view: View, style: MovementStyle, labelled: Boolean = true) {
    Box(Modifier.fillMaxSize().background(FitnessTheme.colors.surface).padding(8.dp)) {
        AnatomyPlate(view, style, FitnessTheme.colors.focus(WorkoutFocus.CHEST), Modifier.fillMaxSize(), if (labelled) headNames() else null)
    }
}

// By name, so that a movement added to the rig draws here without this file knowing of it.
private fun focusOf(movement: Movement): WorkoutFocus = when (movement.name) {
    "INCLINE_PRESS", "BENCH_PRESS", "CHEST_FLY", "DIP" -> WorkoutFocus.CHEST
    "LAT_PULLDOWN", "PULL_UP", "SEATED_ROW", "SHRUG", "REAR_DELT_FLY" -> WorkoutFocus.BACK
    "LATERAL_RAISE", "FRONT_RAISE", "OVERHEAD_PRESS" -> WorkoutFocus.SHOULDERS
    "BICEPS_CURL", "TRICEPS_PUSHDOWN" -> WorkoutFocus.ARMS
    else -> WorkoutFocus.LEGS
}

@Composable
private fun Tile(movement: Movement, style: MovementStyle, seconds: () -> Float, modifier: Modifier = Modifier, framed: Boolean = true, labelled: Boolean = true) {
    val colors = FitnessTheme.colors
    Box(
        modifier
            .then(if (framed) Modifier.padding(4.dp).clip(FitnessCardShape).background(colors.surface).border(1.dp, colors.hairline, FitnessCardShape) else Modifier.background(colors.surface))
            .padding(10.dp),
    ) {
        MovementFigure(movement, style, colors.focus(focusOf(movement)), seconds, Modifier.fillMaxSize(), if (labelled) headNames() else null)
    }
}

@Composable
private fun Sheet(style: MovementStyle) {
    Column(Modifier.background(FitnessTheme.colors.background)) {
        for (movement in Movement.entries) {
            Row {
                for (moment in MOMENTS) Tile(movement, style, { moment }, Modifier.size(SPRITE_W.dp, SPRITE_H.dp))
            }
        }
    }
}

private class Row(val movement: Movement, val name: String, val last: String, val target: String)

// Sample data, as a workout's list would have it.
private val ROWS = listOf(
    Row(Movement.INCLINE_PRESS, "Incline bench press", "Last 160 lb × 12 · 4 days ago", "170 × 9"),
    Row(Movement.LAT_PULLDOWN, "Lat pulldown (strict)", "Last 187 lb × 12 · 2 days ago", "187 × 13"),
    Row(Movement.SQUAT, "Barbell squat", "Last 290 lb × 11 · 6 days ago", "300 × 10"),
    Row(Movement.LATERAL_RAISE, "Dumbbell lateral raise", "Last 15 lb × 21 · 4 days ago", "17.5 × 18"),
)

/** The workout's list of exercises with a figure in each row, the first one open with the movement large. */
@Composable
private fun WorkoutListMock(style: MovementStyle) {
    val colors = FitnessTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ROWS.forEachIndexed { index, row -> ExerciseCardMock(row, style, expanded = index == 0) }
    }
}

@Composable
private fun ExerciseCardMock(row: Row, style: MovementStyle, expanded: Boolean) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val tints = colors.focus(focusOf(row.movement))
    Column(
        Modifier.fillMaxWidth().clip(FitnessCardShape).background(colors.surface).border(1.dp, if (expanded) tints.first else colors.hairline, FitnessCardShape),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(colors.background).border(1.dp, colors.hairline, RoundedCornerShape(14.dp)).padding(3.dp)) {
                MovementFigure(row.movement, style, tints, { 1.45f }, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, style = type.headline, color = colors.text, maxLines = 1)
                Text(row.last, style = type.label, color = colors.textFaint, maxLines = 1)
            }
            if (!expanded) {
                Row(Modifier.clip(CircleShape).border(1.dp, colors.gold.copy(alpha = 0.55f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Flag, contentDescription = null, tint = colors.gold, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(row.target, style = type.label, color = colors.gold, maxLines = 1)
                }
            }
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = colors.textFaint, modifier = Modifier.padding(start = 6.dp).size(22.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f })
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).height(320.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B0C10))) {
                MovementFigure(row.movement, style, tints, { 0.95f }, Modifier.fillMaxSize().padding(6.dp), headNames())
            }
        }
    }
}

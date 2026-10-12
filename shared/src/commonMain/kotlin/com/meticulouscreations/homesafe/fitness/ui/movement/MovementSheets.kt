package com.meticulouscreations.homesafe.fitness.ui.movement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import com.meticulouscreations.homesafe.fitness.domain.MovementKind
import homesafe.shared.generated.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.concurrent.Volatile

// The true-3D figure's pictures: a rep of each movement rendered ahead of time from a modelled
// lifter (tools/movement-render) and packed under composeResources/files/movement/<kind>/, read
// here and played back in ExerciseFigure.kt.

/** How many frames a rendered rep has, from its easy end (the first) to its hard end (the last), and how many of them a sheet has to a row. */
internal const val SHEET_FRAMES = 25
internal const val SHEET_COLUMNS = 5

/** Where the middle of a working head is in one frame: [x] and [y] as shares of the frame from its top left. [role] is as [Movement.work] gives it. */
internal class FrameAnchor(val head: HeadId, val right: Boolean, val role: Float, val x: Float, val y: Float)

/**
 * One movement's rep as it was rendered. [body] is the lit lifter and their equipment on a clear
 * ground, [SHEET_FRAMES] frames in a grid; [muscle] the same frames smaller, black but for the
 * working muscles: red the prime movers, green the synergists, blue the stabilizers, each a
 * light's strength. [anchors] says, frame by frame, where each of those muscles is.
 */
@Stable
internal class MovementSheets(val body: ImageBitmap, val muscle: ImageBitmap, val anchors: List<List<FrameAnchor>>) {
    /** A frame's side in each sheet, in its pixels. */
    val bodyFrame: Int = body.width / SHEET_COLUMNS
    val muscleFrame: Int = muscle.width / SHEET_COLUMNS

    /** The heads to call out by name: the ones that do the lift and the ones that help it, head to toe as the body lists them. */
    val named: Set<HeadId> = anchors.flatten().filter { it.role >= SYNERGIST }.map { it.head }.sortedBy { it.ordinal }.toSet()
}

/** Reads a movement's `anchors.json`. */
internal object MovementAnchors {
    @Serializable
    private class Entry(val head: String = "", val side: String = "", val role: String = "", val x: Float = 0f, val y: Float = 0f)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The anchors of every frame. A head or a role this build doesn't know (the pictures are
     * made apart from the app, and may run ahead of it) is left out, not failed on.
     */
    fun parse(text: String): List<List<FrameAnchor>> = json.decodeFromString<List<List<Entry>>>(text).map { frame ->
        frame.mapNotNull { entry ->
            val head = HeadId.entries.firstOrNull { it.name == entry.head } ?: return@mapNotNull null
            val role = when (entry.role) {
                "prime" -> PRIME_MOVER
                "synergist" -> SYNERGIST
                "stabilizer" -> STABILIZER
                else -> return@mapNotNull null
            }
            FrameAnchor(head, right = entry.side == "r", role, entry.x, entry.y)
        }
    }
}

/** Something read from the app's resources, which takes a moment and may not be there. */
internal sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>

    /** Not packed with this build, or unreadable: something else has to be shown. */
    data object Missing : Loaded<Nothing>

    class Ready<T>(val value: T) : Loaded<T>
}

/**
 * The rendered pictures, read once and kept: every movement's badge (a list shows a dozen at
 * once, and each is small), and the sheets of one movement only, the last one asked for. A
 * decoded body sheet is some sixteen megabytes, so only a figure drawn large asks for them,
 * asking for another movement's lets the last go, and so does the last such figure leaving the
 * screen ([hold], [letGo]).
 */
internal object MovementPictures {
    private val badgeLock = Mutex()
    private val sheetLock = Mutex()

    // Replaced whole under their locks, so that they can be read without one.
    @Volatile
    private var badges: Map<MovementKind, Loaded<ImageBitmap>> = emptyMap()

    @Volatile
    private var sheets: Pair<MovementKind, MovementSheets>? = null

    @Volatile
    private var noSheets: Set<MovementKind> = emptySet()

    /** How many figures on screen are drawn from the sheets. Counted on the main thread, by [hold] and [letGo]. */
    private var holders = 0

    /** A figure that draws from the sheets has come on screen. */
    fun hold() {
        holders++
    }

    /** One has left: with none on screen, the sheets are let go of (one still being read is kept until the next figure has come and gone). */
    fun letGo() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) sheets = null
    }

    /** [kind]'s badge if it has been read already, so a row scrolled back to has it at once. */
    fun heldBadge(kind: MovementKind): Loaded<ImageBitmap>? = badges[kind]

    fun heldSheets(kind: MovementKind): Loaded<MovementSheets>? = when {
        sheets?.first == kind -> sheets?.second?.let { Loaded.Ready(it) }
        kind in noSheets -> Loaded.Missing
        else -> null
    }

    suspend fun badge(kind: MovementKind): Loaded<ImageBitmap> = heldBadge(kind) ?: badgeLock.withLock {
        heldBadge(kind) ?: read { Res.readBytes("${MovementCatalog.folder(kind)}/badge.webp").decodeToImageBitmap() }.also { badges = badges + (kind to it) }
    }

    suspend fun sheets(kind: MovementKind): Loaded<MovementSheets> = heldSheets(kind) ?: sheetLock.withLock {
        heldSheets(kind) ?: read {
            val folder = MovementCatalog.folder(kind)
            val anchors = MovementAnchors.parse(Res.readBytes("$folder/anchors.json").decodeToString())
            val body = Res.readBytes("$folder/body.webp").decodeToImageBitmap()
            val muscle = Res.readBytes("$folder/muscle.webp").decodeToImageBitmap()
            // A sheet that isn't the grid it is read as would be drawn as rubbish: better the stand-in.
            check(anchors.size == SHEET_FRAMES && body.width == body.height && muscle.width == muscle.height && body.width >= SHEET_COLUMNS && muscle.width >= SHEET_COLUMNS)
            MovementSheets(body, muscle, anchors)
        }.also { read ->
            when (read) {
                is Loaded.Ready -> sheets = kind to read.value
                else -> noSheets = noSheets + kind
            }
        }
    }

    private inline fun <T> read(block: () -> T): Loaded<T> = try {
        Loaded.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // No such file (this movement's frames aren't packed yet), or one that can't be decoded. An Error (out of
        // memory decoding a sheet, say) is neither, and is left to be thrown: it says nothing about the file.
        Loaded.Missing
    }
}

/** [kind]'s badge, read off the main thread the first time it is asked for. */
@Composable
internal fun rememberMovementBadge(kind: MovementKind): Loaded<ImageBitmap> = rememberLoaded(kind, Badges)

/** [kind]'s sheets, kept in memory for as long as this is on screen. */
@Composable
internal fun rememberMovementSheets(kind: MovementKind): Loaded<MovementSheets> {
    DisposableEffect(Unit) {
        MovementPictures.hold()
        onDispose { MovementPictures.letGo() }
    }
    return rememberLoaded(kind, Sheets)
}

/** One of the two things [MovementPictures] keeps: what it has already, and how to read it. */
private interface Kept<T> {
    fun held(kind: MovementKind): Loaded<T>?

    suspend fun read(kind: MovementKind): Loaded<T>
}

private object Badges : Kept<ImageBitmap> {
    override fun held(kind: MovementKind) = MovementPictures.heldBadge(kind)

    override suspend fun read(kind: MovementKind) = MovementPictures.badge(kind)
}

private object Sheets : Kept<MovementSheets> {
    override fun held(kind: MovementKind) = MovementPictures.heldSheets(kind)

    override suspend fun read(kind: MovementKind) = MovementPictures.sheets(kind)
}

@Composable
private fun <T> rememberLoaded(kind: MovementKind, kept: Kept<T>): Loaded<T> {
    // A preview has one thread and a handful of frames: there, the pictures are read where they are asked for.
    val still = LocalInspectionMode.current
    val state = remember(kind, kept) { mutableStateOf(kept.held(kind) ?: Loaded.Loading) }
    LaunchedEffect(kind, kept, still) {
        if (state.value != Loaded.Loading) return@LaunchedEffect
        state.value = if (still) kept.read(kind) else withContext(Dispatchers.Default) { kept.read(kind) }
    }
    return state.value
}

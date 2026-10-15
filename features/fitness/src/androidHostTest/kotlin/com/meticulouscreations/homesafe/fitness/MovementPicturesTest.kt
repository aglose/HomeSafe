package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.fitness.domain.MovementKind
import com.meticulouscreations.homesafe.fitness.ui.movement.MovementAnchors
import com.meticulouscreations.homesafe.fitness.ui.movement.MovementCatalog
import com.meticulouscreations.homesafe.fitness.ui.movement.SHEET_COLUMNS
import com.meticulouscreations.homesafe.fitness.ui.movement.SHEET_FRAMES
import com.meticulouscreations.homesafe.fitness.ui.movement.SYNERGIST
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The true-3D figure's pictures as they are packed with the app (tools/movement-render), read
 * the way the app reads them. They are made apart from the app, so this is where the two are
 * held to the same shape: a folder that isn't a movement the app has, a sheet that isn't the
 * grid of frames it is played back as, or anchors that name no muscle would each show up on a
 * phone as a figure that isn't there or is drawn wrong.
 *
 * Resources can't be loaded as the app loads them from every kind of test here, so the files are
 * read from the source tree, where they are packed from.
 */
class MovementPicturesTest {

    // Gradle runs the host tests from this module's directory; the pictures are packed with :shared's resources.
    private val root = File("../../shared/src/commonMain/composeResources/files/movement")
    private val folders = root.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }

    @Test
    fun everyFolderIsAMovementTheAppHasUnderTheNameItLooksFor() {
        val expected = MovementKind.entries.associateBy { MovementCatalog.folder(it).substringAfterLast('/') }
        for (folder in folders) assertTrue(folder.name in expected, "${folder.name} is no MovementKind: nothing would ever show it")
    }

    @Test
    fun everyMovementHasItsFourFilesPacked() {
        for (kind in MovementKind.entries) {
            val folder = File(root, MovementCatalog.folder(kind).substringAfterLast('/'))
            for (name in listOf("body.webp", "muscle.webp", "badge.webp", "anchors.json")) {
                val file = File(folder, name)
                assertTrue(file.isFile && file.length() > 0, "$kind has no ${folder.name}/$name: it would be drawn as the forged study where the rendered lifter was chosen")
            }
        }
    }

    @Test
    fun everyPackedMovementHasItsFourFilesInTheShapeTheyArePlayedBackIn() {
        for (folder in folders) {
            val body = File(folder, "body.webp").readBytes().webpSize()
            val muscle = File(folder, "muscle.webp").readBytes().webpSize()
            val badge = File(folder, "badge.webp").readBytes().webpSize()
            assertEquals(body.width, body.height, "${folder.name}: the body sheet is a square grid")
            assertEquals(0, body.width % SHEET_COLUMNS, "${folder.name}: the body sheet divides into $SHEET_COLUMNS columns")
            assertEquals(muscle.width, muscle.height, "${folder.name}: the muscle sheet is a square grid")
            assertEquals(0, muscle.width % SHEET_COLUMNS, "${folder.name}: the muscle sheet divides into $SHEET_COLUMNS columns")
            assertEquals(badge.width, badge.height, "${folder.name}: the badge is square")

            val frames = MovementAnchors.parse(File(folder, "anchors.json").readText())
            assertEquals(SHEET_FRAMES, frames.size, "${folder.name}: a frame of anchors for each frame of the rep")
            for ((index, frame) in frames.withIndex()) {
                assertTrue(frame.any { it.role >= SYNERGIST }, "${folder.name}: frame $index has no working head to name")
                assertTrue(frame.all { it.x in 0f..1f && it.y in 0f..1f }, "${folder.name}: frame $index has an anchor outside the frame")
            }
        }
    }
}

/** A size read off an image's header. */
private data class Size(val width: Int, val height: Int)

/**
 * A WebP's width and height, read from its header: the Android host tests have no image decoder
 * to ask. Covers the three kinds of WebP: lossy ("VP8 "), lossless ("VP8L") and extended ("VP8X").
 */
private fun ByteArray.webpSize(): Size {
    fun u8(at: Int) = this[at].toInt() and 0xFF
    fun le24(at: Int) = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)
    check(String(this, 0, 4, Charsets.US_ASCII) == "RIFF" && String(this, 8, 4, Charsets.US_ASCII) == "WEBP") { "not a WebP" }
    return when (val chunk = String(this, 12, 4, Charsets.US_ASCII)) {
        "VP8X" -> Size(le24(24) + 1, le24(27) + 1)

        "VP8 " -> Size((u8(26) or (u8(27) shl 8)) and 0x3FFF, (u8(28) or (u8(29) shl 8)) and 0x3FFF)

        "VP8L" -> {
            val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
            Size((bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
        }

        else -> error("unknown WebP chunk $chunk")
    }
}

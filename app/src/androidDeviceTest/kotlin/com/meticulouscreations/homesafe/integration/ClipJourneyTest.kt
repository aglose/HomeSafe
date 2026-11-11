package com.meticulouscreations.homesafe.integration

import android.content.ContentUris
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import com.meticulouscreations.homesafe.fakefrigate.FakeEvent
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.fakefrigate.RecordedRequest
import com.meticulouscreations.homesafe.ui.screens.CLIP_PLAYHEAD_TEST_TAG
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Clips, all the way through with real video: cut one in the editor, save it, find it in the
 * phone's Downloads and play it; and play a detection's clip in Moments and download that. The
 * fake Frigate serves real H.264 for this (`FakeVideo`), so the app's own player decodes the HLS
 * playlists, Android's DownloadManager fetches the MP4, and the rewrite into Downloads
 * (`saveClipToDownloads`) runs on a fragmented file, as Frigate's are.
 *
 * Android only, unlike the journeys in `src/integrationTest`: the file is looked for where
 * Android puts it, and the desktop's downloader would save into the Downloads folder of whoever
 * runs the tests and open a Finder window on it.
 *
 * Playing and saving are journeys of their own because apksim can't yet do all of it, and a test
 * that can't run there says so and is skipped rather than failing (see [assumeVideoDecodes] and
 * [assumeDownloadsAreFetched]). An emulator or a phone runs every one.
 */
@OptIn(ExperimentalTestApi::class)
class ClipJourneyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    @Test
    fun theClipEditorPlaysTheRecordingAndThenTheLengthPickedInIt() {
        assumeVideoDecodes()
        runAppJourney {
            openEditor()

            // Its preview is the recording itself: the player asks the server for the playlist,
            // then for the video in it, and the clock over the picture follows the frames.
            awaitRequest("the editor's playlist of the back yard's recording") { it.isVod("back_yard", "index.m3u8") }
            awaitRequest("a segment of that playlist") { it.isVod("back_yard") && it.path.endsWith(".m4s") }
            awaitPlayheadMoving()

            // Ten seconds, from one of the length chips: the new selection plays from its start,
            // through those ten seconds and no further, and round again.
            press(hasContentDescription(TEN_SECONDS), "the 10s length chip")
            awaitPlayheadLooping(seconds = 10)
            assertFalse(exists(hasText("Couldn't play this recording")), "the recording plays")
        }
    }

    @Test
    fun aClipCutInTheEditorIsSavedToDownloadsAsAVideoThatPlays() {
        assumeDownloadsAreFetched()
        runAppJourney {
            openEditor()
            // Ten seconds, from one of the length chips: a different clip from the one the editor proposed.
            press(hasContentDescription(TEN_SECONDS), "the 10s length chip")
            press(SAVE and hasClickAction(), "the Save button")

            awaitNode(hasContentDescription("Clip saved"), "the Save button, saying the clip is saved", SAVE_WAIT)
            val request = server.requests.first { it.path.startsWith("/api/back_yard/start/") && it.path.endsWith("/clip.mp4") }
            val parts = request.path.split('/')
            val start = parts[4].toDouble()
            assertEquals(10.0, parts[6].toDouble() - start, 0.01, "the server was asked for the ten seconds selected: $request")

            val saved = awaitDownload("homesafe_back_yard_${start.toLong()}_10s.mp4")
            try {
                assertPlays(saved, seconds = 10)
            } finally {
                resolver.delete(saved, null, null)
            }
        }
    }

    @Test
    fun aDetectionsClipPlaysToItsEndInMoments() {
        assumeVideoDecodes()
        val visitor = shortVisit()
        runAppJourney(FakeFrigateState.household().apply { events += visitor }) {
            val moments = MomentsRobot(this)
            signIn.signInAs()
            moments.open()
            moments.awaitMoment(VISITOR)
            moments.openClip(VISITOR)

            // Playing: the picture offers to pause. Then every segment is fetched and the clip
            // comes to rest on its last frame, where the picture offers to play it again.
            awaitNode(hasClickLabel("Pause clip"), "the open clip, playing")
            awaitRequest("the clip's playlist") { it.path == "/vod/event/${visitor.id}/index.m3u8" }
            awaitRequest("the clip's last segment") { it.path == "/vod/event/${visitor.id}/seg-2.m4s" }
            // The picture stops offering to pause. (Every unopened card offers "Play clip" too,
            // so that label showing says nothing about this one.)
            awaitGone(hasClickLabel("Pause clip"), "the open clip, still playing", PLAY_WAIT)
            assertFalse(exists(hasText("Couldn't play this clip")), "the clip plays")
            assertTrue(exists(hasContentDescription("Play full screen")), "the clip is still open, at rest on its last frame")
        }
    }

    @Test
    fun aDetectionsClipDownloadsAsAVideoThatPlays() {
        assumeDownloadsAreFetched()
        val visitor = shortVisit()
        runAppJourney(FakeFrigateState.household().apply { events += visitor }) {
            val moments = MomentsRobot(this)
            signIn.signInAs()
            moments.open()
            moments.awaitMoment(VISITOR)

            // The newest detection's card is the first, and so is its download button.
            awaitNode(hasContentDescription("Download clip"), "the card's download button").performSemanticsAction(SemanticsActions.OnClick)
            settle()

            awaitNode(hasContentDescription("Downloaded"), "the download button's tick", SAVE_WAIT)
            assertTrue(server.received { it.path == "/api/events/${visitor.id}/clip.mp4" }, "the clip was asked of the server as one file")
            val saved = awaitDownload("homesafe_back_yard_${visitor.startTime.toLong()}.mp4")
            try {
                assertPlays(saved, seconds = 6)
            } finally {
                resolver.delete(saved, null, null)
            }
        }
    }

    /** A cat in the back yard for six seconds, a minute ago: a clip short enough to watch all of. */
    private fun shortVisit(): FakeEvent {
        val start = System.currentTimeMillis() / 1000 - 60.0
        return FakeEvent("${start.toLong()}.000000-visit1", "back_yard", "cat", startTime = start, endTime = start + 6)
    }

    /** Signs in and opens the clip editor from the back yard's scissors, waiting until it has a selection to save. */
    private fun AppJourney.openEditor() {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        signIn.signInAs()
        home.openCamera("back_yard", "Back Yard")
        camera.press(hasContentDescription("Clip a video from Back Yard"), "the scissors")
        awaitNode(SAVE and hasClickAction(), "the Save button, ready")
    }

    /**
     * Skips the test where no H.264 decoder is listed and the runtime is apksim: its Linux image
     * has none yet (on a Mac it decodes with VideoToolbox), and a player with nothing to decode
     * the picture with can't play a clip. A phone or an emulator without one fails instead.
     */
    private fun assumeVideoDecodes() {
        val decodes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            !codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }
        assumeTrue("No H.264 decoder on this apksim host, so clips can't play here", decodes || !ON_APKSIM)
    }

    /**
     * Skips the test on apksim: its DownloadManager is Robolectric's, which notes a download and
     * never fetches it, so a save there stays "Saving" for good. A phone or an emulator runs it.
     */
    private fun assumeDownloadsAreFetched() {
        assumeTrue("apksim's DownloadManager doesn't fetch, so a clip can't be saved here", !ON_APKSIM)
    }

    /**
     * Waits for the server to have been asked for something, driving the app's frames meanwhile
     * (unlike `server.awaitRequest`, which holds the main thread: on apksim the test runs on it,
     * and a player that can't get to it stops loading).
     */
    private fun AppJourney.awaitRequest(description: String, predicate: (RecordedRequest) -> Boolean) {
        awaitUntil("the server to be asked for $description", PLAY_WAIT) { server.received(predicate) }
    }

    /**
     * Waits for the clock time over the editor's preview to move on twice. It follows the frame
     * the player is showing (and holds still while a seek is still on its way), so it only moves
     * while video is decoding.
     */
    private fun AppJourney.awaitPlayheadMoving() {
        val seen = mutableSetOf<String>()
        awaitUntil("the editor's playhead to move on as the clip plays (saw $seen)", PLAY_WAIT) {
            playheadLabel()?.let { seen += it }
            seen.size >= 3
        }
    }

    /**
     * Follows the clock over the preview, second by second, until it goes back to a time it has
     * already shown after most of a [seconds]-long run: the selection played through and came
     * round to its start. Then the run must not have been longer than the selection, which it
     * would be if the preview were still playing the clip the editor first proposed. (A step
     * back after only a second or two is a seek settling, and is waited past.)
     */
    private fun AppJourney.awaitPlayheadLooping(seconds: Int) {
        val shown = mutableListOf<String>()
        var looped = false
        awaitUntil("the editor's playhead to play $seconds seconds and come round to the start (showed $shown)", LOOP_WAIT) {
            val label = playheadLabel()
            if (label != null && label != shown.lastOrNull()) {
                if (label in shown && shown.toSet().size >= seconds - LOOP_SLACK_SECONDS) looped = true
                shown += label
            }
            looped
        }
        val run = shown.toSet().size
        assertTrue(run <= seconds + 2, "the preview played $run different seconds before coming round, of a $seconds-second selection: $shown")
    }

    /** The clock time over the editor's preview, or null while it isn't up. */
    private fun AppJourney.playheadLabel(): String? =
        ui.onAllNodes(hasTestTag(CLIP_PLAYHEAD_TEST_TAG)).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text

    /** Through the control's own click action: the editor's chips scroll sideways, and a card's button may sit under the floating nav. */
    private fun AppJourney.press(matcher: SemanticsMatcher, description: String) {
        awaitSingle(matcher, description).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        snapshot("after press on $description")
    }

    /** Waits for [fileName] to be in the phone's Downloads, finished (no longer pending), and returns it. */
    private fun AppJourney.awaitDownload(fileName: String): Uri {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var found: Uri? = null
        awaitUntil("$fileName in Downloads", SAVE_WAIT) {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(1) == 0) found = ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
            found != null
        }
        return checkNotNull(found)
    }

    /**
     * Plays the saved file from its first frame to its last, as a gallery would: it must be an
     * ordinary MP4 that says how long it is (Frigate's fragmented one doesn't, which is what the
     * rewrite on the way into Downloads is for), [seconds] long, and every frame must decode.
     */
    private fun assertPlays(video: Uri, seconds: Int) {
        val extractor = MediaExtractor()
        try {
            resolver.openFileDescriptor(video, "r").use { extractor.setDataSource(checkNotNull(it) { "Couldn't open $video" }.fileDescriptor) }
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            assertTrue(track != null, "the saved file has a video track")
            val format = extractor.getTrackFormat(track!!)
            assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
            val duration = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            assertTrue(abs(duration - seconds) < 0.5, "the saved file says it is $duration s long, and $seconds s were asked for")
            extractor.selectTrack(track)

            val frames = decodeEveryFrame(extractor, format)
            assertEquals(seconds * FRAMES_PER_SECOND, frames, "every frame of the saved file decodes")
        } finally {
            extractor.release()
        }
    }

    /** Runs the [extractor]'s selected track through the device's decoder and returns how many pictures came out. */
    private fun decodeEveryFrame(extractor: MediaExtractor, format: MediaFormat): Int {
        val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var frames = 0
            var fed = false
            val deadline = System.nanoTime() + DECODE_WAIT.inWholeNanoseconds
            while (true) {
                check(System.nanoTime() < deadline) { "The decoder gave $frames frames and then nothing more within $DECODE_WAIT" }
                if (!fed) {
                    val input = decoder.dequeueInputBuffer(CODEC_POLL_MICROS)
                    if (input >= 0) {
                        val size = extractor.readSampleData(checkNotNull(decoder.getInputBuffer(input)), 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fed = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, CODEC_POLL_MICROS)
                if (output >= 0) {
                    if (info.size > 0) frames++
                    decoder.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return frames
                }
            }
        } finally {
            decoder.release()
        }
    }

    private fun RecordedRequest.isVod(camera: String, file: String? = null): Boolean =
        method == "GET" && path.startsWith("/vod/$camera/start/") && (file == null || path.endsWith("/$file"))

    private fun hasClickLabel(label: String): SemanticsMatcher =
        SemanticsMatcher("has click label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    private companion object {
        val SAVE = hasContentDescription("Save clip")
        const val TEN_SECONDS = "Make the clip 10s long"
        const val VISITOR = "Cat detected"

        /** Whether this is apksim, Android's framework on the JVM, and not a phone or an emulator: Robolectric is only there. */
        val ON_APKSIM: Boolean = runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess || Build.FINGERPRINT == "robolectric"

        /** The fake's video: five frames a second (see FakeVideo). */
        const val FRAMES_PER_SECOND = 5

        /** A player's first frames, or a short clip played through, on a slow emulator. */
        val PLAY_WAIT = 40.seconds

        /** A ten-second selection played through, with time for a slow emulator to start it. */
        val LOOP_WAIT = 60.seconds

        /** How many of a selection's seconds a slow emulator may skip over without the run counting as too short to be one. */
        const val LOOP_SLACK_SECONDS = 4

        /** DownloadManager picking the download up, fetching it, and the rewrite into Downloads. */
        val SAVE_WAIT = 60.seconds
        val DECODE_WAIT = 30.seconds
        const val CODEC_POLL_MICROS = 10_000L
    }
}

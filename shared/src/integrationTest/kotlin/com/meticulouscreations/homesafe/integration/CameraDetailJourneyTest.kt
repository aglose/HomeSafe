package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.fakefrigate.RecordedRequest
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A camera's own screen, reached by tapping its card: its name in the header, the timeline drawn
 * from the server's recordings, the recent detections asked of the server for that camera alone,
 * the quick actions under the player, and the way back to the list. Live video itself isn't
 * served by the fake (there is no go2rtc), so these follow everything around the player rather
 * than the picture in it.
 */
class CameraDetailJourneyTest {

    @Test
    fun tappingACardOpensThatCameraAndBackReturnsToEveryCard() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        signIn.signInAs()

        home.openCamera("driveway", "Driveway")
        camera.back()

        home.awaitList()
        shell.awaitSelected(TopLevelRoute.Home)
        home.awaitCard("front_door", "Front Door")
        home.awaitCard("driveway", "Driveway")
        home.awaitCard("back_yard", "Back Yard")
    }

    @Test
    fun theCameraScreenListsThatCamerasOwnRecentDetections() = runAppJourney {
        val home = HomeRobot(this)
        signIn.signInAs()

        home.openCamera("front_door", "Front Door")

        // Asked of the server for this camera alone, not sliced out of the whole feed.
        server.awaitRequest(description = "the front door's own detections") {
            it.path == "/api/events" && it.query["cameras"] == listOf("front_door")
        }
        awaitText("Person in the porch")
        awaitText("Alice detected")
        assertFalse(exists(hasText("Dog detected")), "the back yard's dog is not the front door's")
    }

    @Test
    fun theTimelineIsDrawnFromTheServersRecordingsAndASpanChipAsksForThatMuchHistory() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        signIn.signInAs()

        home.openCamera("front_door", "Front Door")

        val threeHours = server.awaitRequest(description = "the front door's recordings") { it.isRecordingsOf("front_door") }
        assertTrue(threeHours.window() in THREE_HOURS_WINDOW, "the timeline opens on three hours: $threeHours")
        // Six hours of recordings on the server, so the "nothing recorded" line goes once they land.
        awaitText("Timeline")
        awaitGone(hasText(NO_RECORDINGS), "the \"nothing recorded\" line")

        camera.press(hasText("24h"), "the 24h span chip")

        val day = server.awaitRequest(description = "a day of the front door's recordings") {
            it.isRecordingsOf("front_door") && it.window() > DAY_SECONDS
        }
        assertTrue(day.window() < DAY_SECONDS + WINDOW_SLACK_SECONDS, "a day, and not more: $day")
    }

    @Test
    fun aCameraWithNoHistorySaysSoUnderTheTimelineAndInRecentActivity() = runAppJourney(FakeFrigateState.quiet()) {
        val home = HomeRobot(this)
        signIn.signInAs()

        home.openCamera("front_door", "Front Door")

        awaitText("No detections on this camera yet.")
        awaitText(NO_RECORDINGS)
    }

    @Test
    fun recordingsTheServerCannotServeAreReportedUnderTheTimeline() = runAppJourney {
        state.edit { failures["/api/front_door/recordings"] = 500 }
        val home = HomeRobot(this)
        signIn.signInAs()

        home.openCamera("front_door", "Front Door")

        awaitText("Couldn't load recordings", substring = true)
        // Only the recordings are broken: the recent detections still come through.
        awaitText("Person in the porch")
    }

    @Test
    fun aCameraTheServerHasDisabledOpensWithoutALivePill() = runAppJourney {
        state.edit {
            val index = cameras.indexOfFirst { it.name == "back_yard" }
            cameras[index] = cameras[index].copy(enabled = false)
        }
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        signIn.signInAs()

        home.openCamera("back_yard", "Back Yard")
        // Its history is still there to look through.
        awaitText("Dog detected")
        assertFalse(exists(hasText(LIVE)), "a disabled camera has no live stream to offer")
        camera.back()

        home.openCamera("front_door", "Front Door")
        awaitText(LIVE)
    }

    @Test
    fun theScissorsOpenTheClipEditorAndCloseBackToTheCamera() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        signIn.signInAs()
        home.openCamera("back_yard", "Back Yard")

        camera.press(hasContentDescription("Clip a video from Back Yard"), "the scissors")

        // The editor takes the whole screen: the camera page's own sections are gone.
        awaitNode(hasContentDescription(CLOSE_EDITOR), "the clip editor's close button")
        awaitGone(hasText(RECENT_ACTIVITY), "the camera page, under the editor")
        // It asks the server for the quarter hour behind live — the camera page itself only
        // ever asks for an hour or more, so a window this narrow is the editor's.
        server.awaitRequest(description = "the clip editor's recordings read") {
            it.isRecordingsOf("back_yard") && it.window() < CLIP_REACH_UPPER_BOUND
        }

        tap(hasContentDescription(CLOSE_EDITOR), "the clip editor's close button")

        camera.awaitOpen("Back Yard")
    }

    private fun RecordedRequest.isRecordingsOf(camera: String): Boolean = method == "GET" && path == "/api/$camera/recordings"

    /** How many seconds of history a recordings request asks for. */
    private fun RecordedRequest.window(): Double {
        val after = query["after"]?.firstOrNull()?.toDoubleOrNull() ?: return 0.0
        val before = query["before"]?.firstOrNull()?.toDoubleOrNull() ?: return 0.0
        return before - after
    }

    private companion object {
        const val LIVE = "LIVE"
        const val CLOSE_EDITOR = "Close clip editor"

        /** The editor opened at live reads ~16 minutes back (its 15-minute reach plus a minute's padding). */
        const val CLIP_REACH_UPPER_BOUND = 3_000.0
        const val RECENT_ACTIVITY = "Recent Activity"
        const val NO_RECORDINGS = "No recordings in this window yet"
        const val DAY_SECONDS = 86_400.0

        /** The app pads each window by a minute so a segment straddling its left edge still draws. */
        const val WINDOW_SLACK_SECONDS = 300.0
        val THREE_HOURS_WINDOW = 10_800.0..(10_800.0 + WINDOW_SLACK_SECONDS)
    }
}

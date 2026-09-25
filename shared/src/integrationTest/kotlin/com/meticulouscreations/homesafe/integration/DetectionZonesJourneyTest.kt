package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.fakefrigate.RecordedRequest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The detection-zones editor behind a camera screen's overflow menu: it opens on what the server's
 * config holds for that camera, draws and names shapes locally, and pushes a layer back with
 * `PUT /api/config/set` only on Save — which the fake applies to its own config, so the next read
 * (the editor's own reload) shows what was saved. Corners are placed by tapping the frame at
 * fractions of its size, which is how they reach the server too.
 */
class DetectionZonesJourneyTest {

    @Test
    fun theOverflowMenuOpensTheEditorOnTheZonesAndMasksTheServerHas() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        val zones = ZonesRobot(this)
        signIn.signInAs()
        home.openCamera("front_door", "Front Door")

        camera.openDetectionZones()

        zones.awaitLoaded("Front Door")
        zones.awaitLayer("Zones · 1")
        zones.awaitLayer("Ignore objects")
        zones.awaitLayer("Ignore motion")
        // The front door's one zone, as its row reads: its friendly name and what it counts.
        awaitNode(hasText("Porch") and hasText("person"), "the Porch zone's row")
    }

    @Test
    fun aZoneDrawnAndNamedIsSavedToFrigateAndBackLeadsToTheCamera() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        val zones = ZonesRobot(this)
        signIn.signInAs()
        home.openCamera("front_door", "Front Door")
        camera.openDetectionZones()
        zones.awaitLoaded("Front Door")

        // Clear of the porch (the lower left) and of the refresh button (the top right corner).
        zones.draw("zone", 0.55f to 0.35f, 0.8f to 0.35f, 0.8f to 0.55f)
        awaitText("Zone 2")
        zones.nameSelectedZone("Mailbox")
        zones.save()

        val saved = server.awaitRequest(description = "the zone saved with config/set") { it.isConfigSet() }
        assertEquals(listOf("Mailbox"), saved.query["cameras.front_door.zones.mailbox.friendly_name"], "the name typed: $saved")
        val coordinates = saved.query["cameras.front_door.zones.mailbox.coordinates"]?.single()
        assertCorners(listOf(0.55 to 0.35, 0.8 to 0.35, 0.8 to 0.55), coordinates, "$saved")
        assertTrue(saved.query.keys.none { it.contains(".zones.porch") }, "the untouched porch zone isn't re-sent: $saved")

        awaitText("Saved. Frigate is using the new zones now.")
        zones.awaitLayer("Zones · 2")
        val mailbox = state.camera("front_door")!!.zones.single { it.name == "mailbox" }
        assertEquals("Mailbox", mailbox.friendlyName, "the server's config now has it: $mailbox")

        zones.back()
        camera.awaitOpen("Front Door")
    }

    @Test
    fun anIgnoreAreaDrawnOnTheMotionLayerJoinsTheCamerasMotionMasks() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        val zones = ZonesRobot(this)
        signIn.signInAs()
        home.openCamera("back_yard", "Back Yard")
        camera.openDetectionZones()
        zones.awaitLoaded("Back Yard")

        // The back yard already ignores motion along the top left (the neighbour's tree).
        zones.openLayer("Ignore motion · 1")
        zones.draw("ignore area", 0.5f to 0.4f, 0.75f to 0.4f, 0.75f to 0.7f)
        zones.save()

        val saved = server.awaitRequest(description = "the motion masks saved with config/set") { it.isConfigSet() }
        val masks = saved.query["cameras.back_yard.motion.mask"].orEmpty()
        assertEquals(2, masks.size, "the old mask and the new one, together: $saved")
        assertTrue(ORIGINAL_BACK_YARD_MASK in masks, "the existing mask is kept as it was: $saved")
        assertCorners(listOf(0.5 to 0.4, 0.75 to 0.4, 0.75 to 0.7), masks.single { it != ORIGINAL_BACK_YARD_MASK }, "$saved")

        awaitText("Saved. Frigate is using the new ignore areas now.")
        zones.awaitLayer("Ignore motion · 2")
        assertEquals(2, state.camera("back_yard")!!.motionMasks.size, "the server's config has both")
    }

    @Test
    fun leavingWithUnsavedWorkOffersToSaveItAndSavingLeavesForTheCamera() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        val zones = ZonesRobot(this)
        signIn.signInAs()
        home.openCamera("front_door", "Front Door")
        camera.openDetectionZones()
        zones.awaitLoaded("Front Door")
        zones.openLayer("Ignore objects")
        zones.draw("ignore area", 0.55f to 0.35f, 0.8f to 0.35f, 0.8f to 0.55f)

        zones.back()
        awaitText("Save your changes?")
        assertTrue(server.requests.none { it.isConfigSet() }, "nothing is sent before the choice is made")
        tapText("Save and leave")

        val saved = server.awaitRequest(description = "the object mask saved with config/set") { it.isConfigSet() }
        assertEquals(1, saved.query["cameras.front_door.objects.mask"]?.size, "the one new ignore area: $saved")
        camera.awaitOpen("Front Door")
        assertEquals(1, state.camera("front_door")!!.objectMasks.size, "the server's config has it")
    }

    @Test
    fun aViewerAccountIsToldItCannotSaveAndTheServerKeepsItsConfig() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        val zones = ZonesRobot(this)
        signIn.signInAs(FakeFrigateState.VIEWER)
        home.openCamera("front_door", "Front Door")
        camera.openDetectionZones()
        zones.awaitLoaded("Front Door")
        zones.openLayer("Ignore objects")
        zones.draw("ignore area", 0.55f to 0.35f, 0.8f to 0.35f, 0.8f to 0.55f)

        zones.save()

        server.awaitRequest(description = "the attempt to save") { it.isConfigSet() }
        awaitText("Couldn't save", substring = true)
        assertTrue(state.camera("front_door")!!.objectMasks.isEmpty(), "Frigate refused a viewer's write")
        // The drawing is kept, so an admin's session could still save it.
        zones.awaitLayer("Ignore objects · 1")
    }

    private fun RecordedRequest.isConfigSet(): Boolean = method == "PUT" && path == "/api/config/set"

    /** [coordinates] is Frigate's "x1,y1,x2,y2,…" and lands on [expected], within a tap's rounding. */
    private fun assertCorners(expected: List<Pair<Double, Double>>, coordinates: String?, context: String) {
        val values = coordinates?.split(',')?.map { it.trim().toDouble() }
        assertEquals(expected.size * 2, values?.size, "one x,y pair per corner tapped: $coordinates ($context)")
        val corners = values.orEmpty().chunked(2) { (x, y) -> x to y }
        expected.zip(corners).forEach { (want, got) ->
            assertTrue(
                abs(want.first - got.first) <= CORNER_TOLERANCE && abs(want.second - got.second) <= CORNER_TOLERANCE,
                "corner $got is where $want was tapped: $coordinates ($context)",
            )
        }
    }

    private companion object {
        /** The fake household's back-yard motion mask, as the app writes it back unchanged. */
        const val ORIGINAL_BACK_YARD_MASK = "0.0,0.0,0.3,0.0,0.3,0.2,0.0,0.2"

        /** A tap lands on a whole pixel and the app keeps thousandths: a couple of hundredths is plenty. */
        const val CORNER_TOLERANCE = 0.02
    }
}

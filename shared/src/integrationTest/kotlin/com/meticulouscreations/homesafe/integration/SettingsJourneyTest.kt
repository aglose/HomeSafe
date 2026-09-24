package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Settings tab against a real server: the Server row's summary and the Server page behind it
 * (read from `/api/stats` and `/api/config` by the real status repository), the per-camera
 * detection switches writing through `PUT /api/config/set` and following what the server then
 * says, and what the page tells a viewer account or a device that can't post notifications.
 *
 * Both journey hosts — the desktop JVM and an Android test context with no activity — have no
 * notification surface, so the alert rules (presets, zones, quiet hours) never appear here; the
 * screen-level `AlertRulesUiTest` covers those.
 */
class SettingsJourneyTest {

    @Test
    fun theServerRowSummarisesTheServerAndOpensOntoItsDetails() = runAppJourney {
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()

        settings.awaitSummary("Tailscale · 42% storage used · healthy")
        settings.openServer()

        awaitNode(hasText("Connected to", substring = true) and hasText(":${server.port}", substring = true), "the address the app is connected to")
        settings.awaitValueUnder("VERSION", "0.17.2-test")
        settings.awaitValueUnder("UPTIME", "3 days, 4 hours")
        settings.awaitValueUnder("CPU", "9%")
        settings.awaitValueUnder("MEMORY", "38%")
        awaitUntil("the detector to be the edgetpu") { settings.valueUnder("DETECTOR")?.startsWith("edgetpu") == true }
        // The inference time is only found by the detector's config key ("coral") in the stats.
        awaitText("Inference 8.5 ms per frame")
        awaitText("Recordings (/media/frigate/recordings)")
        awaitText("42% used")
        awaitText("420 GB used")
        awaitText("1 TB total")
        settings.awaitValueUnder("CONTINUOUS", "7 days")
        settings.awaitValueUnder("MOTION", "14 days")
        settings.awaitValueUnder("ALERTS", "30 days")
        settings.awaitValueUnder("DETECTIONS", "30 days")
        assertTrue(server.received { it.method == "GET" && it.path == "/api/stats" }, "stats were read: ${server.requests}")
        assertTrue(server.received { it.method == "GET" && it.path == "/api/config" }, "config was read: ${server.requests}")

        settings.backFromServer()
        settings.awaitSummary("Tailscale · 42% storage used · healthy")
    }

    @Test
    fun aNearlyFullDiskLeadsTheSummaryAndTheServerPageStillOffersTheUpdate() = runAppJourney {
        state.edit { this.server = this.server.copy(latestVersion = "0.18.0", recordingsUsedMb = 950_000.0) }
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()

        // A full disk outranks an available update: it's the one that loses footage.
        settings.awaitSummary("Tailscale · 95% storage used · disk nearly full")
        settings.openServer()
        settings.awaitValueUnder("VERSION", "0.17.2-test")
        awaitText("Update available: 0.18.0")
        awaitText("95% used")
        awaitText("950 GB used")
    }

    @Test
    fun aFailingStatsEndpointSaysSoAndRetryRecoversOnceTheServerDoes() = runAppJourney {
        state.edit { failures["/api/stats"] = 500 }
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()

        settings.awaitSummary("Tailscale · can't reach the server")
        awaitText("Reading camera pipelines…")
        settings.openServer()
        awaitText("Couldn't reach the server: Couldn't load server stats: 500", substring = true)
        assertFalse(exists(hasText("0.17.2-test")), "no numbers without stats")

        state.edit { failures.remove("/api/stats") }
        settings.press(hasText("Retry") and hasClickAction(), "the Retry button")

        settings.awaitValueUnder("VERSION", "0.17.2-test")
        awaitGone(hasText("Couldn't reach the server", substring = true), "the failure")
        settings.backFromServer()
        settings.awaitSummary("Tailscale · 42% storage used · healthy")
        settings.awaitDetection("Front Door", on = true, enabled = true)
    }

    @Test
    fun aRefreshThatFailsKeepsTheLastNumbersAndSaysTheRefreshFailed() = runAppJourney {
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()
        settings.openServer()
        settings.awaitValueUnder("VERSION", "0.17.2-test")

        state.edit { failures["/api/stats"] = 500 }
        settings.refresh()

        awaitText("Last refresh failed: Couldn't load server stats: 500", substring = true)
        settings.awaitValueUnder("VERSION", "0.17.2-test")
        settings.backFromServer()
        settings.awaitSummary("Tailscale · 42% storage used · last refresh failed")

        state.edit { failures.remove("/api/stats") }
        settings.openServer()
        settings.refresh()
        awaitGone(hasText("Last refresh failed", substring = true), "the refresh failure")
        settings.backFromServer()
        settings.awaitSummary("Tailscale · 42% storage used · healthy")
    }

    @Test
    fun theAiFeaturesReadOffTheServersConfigAndFaceRecognitionBringsTheFacesRow() = runAppJourney {
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()

        awaitText("Recognition")
        awaitNode(settings.facesRow(), "the Faces row")
        awaitNode(settings.classifierRow(ClassifierRobot.HOUSEHOLD_CARS), "the Household Cars row")
        awaitText("Label what the car classifier saw, and retrain it")

        settings.openServer()
        settings.awaitValueUnder("FACE RECOGNITION", "On")
        settings.awaitValueUnder("LICENSE PLATE RECOGNITION", "Off")
        settings.awaitValueUnder("SEMANTIC SEARCH", "On")
    }

    @Test
    fun withFaceRecognitionOffInConfigTheFacesRowIsLeftOut() = runAppJourney {
        state.edit {
            this.server = this.server.copy(faceRecognitionEnabled = false, licensePlateRecognitionEnabled = true, semanticSearchEnabled = false)
        }
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()

        // The summary is drawn from the same read that decides the Faces row, so once it's there the row has been decided.
        settings.awaitSummary("Tailscale · 42% storage used · healthy")
        awaitNode(settings.classifierRow(ClassifierRobot.HOUSEHOLD_CARS), "the Household Cars row")
        assertFalse(exists(settings.facesRow()), "no Faces row while face recognition is off")

        settings.openServer()
        settings.awaitValueUnder("FACE RECOGNITION", "Off")
        settings.awaitValueUnder("LICENSE PLATE RECOGNITION", "On")
        settings.awaitValueUnder("SEMANTIC SEARCH", "Off")
    }

    @Test
    fun turningACamerasDetectionOffWritesItToTheServerAndTheSwitchFollowsTheServer() = runAppJourney {
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()
        settings.awaitDetection("Back Yard", on = true, enabled = true)
        // Motion is locked on while detection needs it — Frigate's own rule.
        settings.awaitMotion("Back Yard", on = true, enabled = false)

        settings.flipDetection("Back Yard")

        val write = settings.awaitSwitchWrite("back_yard", "detect", enabled = false)
        settings.awaitDetection("Back Yard", on = false, enabled = true)
        awaitText("Off — no detections or alerts from this camera.")
        settings.awaitMotion("Back Yard", on = true, enabled = true)
        assertFalse(state.camera("back_yard")!!.detectEnabled, "the server applied the write")
        assertTrue(state.camera("back_yard")!!.motionEnabled, "motion was left alone")
        assertTrue(state.camera("front_door")!!.detectEnabled, "no other camera was touched")
        assertEquals(listOf(SettingsRobot.SwitchWrite("back_yard", "detect", false)), settings.switchWrites(), "one write: ${server.requests}")
        val afterWrite = server.requests.dropWhile { it != write }.drop(1)
        assertTrue(afterWrite.any { it.method == "GET" && it.path == "/api/config" }, "the config was read back after the write: ${server.requests}")
        settings.awaitDetection("Front Door", on = true, enabled = true)
    }

    @Test
    fun withDetectionOffMotionCanGoOffTooAndTurningDetectionBackOnBringsMotionWithIt() = runAppJourney {
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()
        settings.flipDetection("Back Yard")
        settings.awaitSwitchWrite("back_yard", "detect", enabled = false)
        settings.awaitMotion("Back Yard", on = true, enabled = true)

        settings.flipMotion("Back Yard")
        settings.awaitSwitchWrite("back_yard", "motion", enabled = false)
        settings.awaitMotion("Back Yard", on = false, enabled = true)
        awaitText("Off — only continuous recording.")
        assertFalse(state.camera("back_yard")!!.motionEnabled, "the server applied the motion write")

        server.clearRequests()
        settings.flipDetection("Back Yard")

        awaitUntil("both writes of turning detection back on") { settings.switchWrites().size >= 2 }
        assertEquals(
            listOf(SettingsRobot.SwitchWrite("back_yard", "motion", true), SettingsRobot.SwitchWrite("back_yard", "detect", true)),
            settings.switchWrites(),
            "motion first, since detection can't run without it",
        )
        settings.awaitDetection("Back Yard", on = true, enabled = true)
        settings.awaitMotion("Back Yard", on = true, enabled = false)
        assertTrue(state.camera("back_yard")!!.detectEnabled && state.camera("back_yard")!!.motionEnabled, "both back on at the server")
    }

    @Test
    fun aViewerSeesTheSwitchesLockedAndIsToldWhy() = runAppJourney {
        val settings = SettingsRobot(this)
        signIn.signInAs(FakeFrigateState.VIEWER)
        settings.open()

        awaitText(SettingsRobot.VIEWER_CAPTION)
        for (camera in listOf("Front Door", "Driveway", "Back Yard")) {
            // Locked, but still showing what the server has.
            settings.awaitDetection(camera, on = true, enabled = false)
            settings.awaitMotion(camera, on = true, enabled = false)
        }
        assertTrue(server.received { it.path == "/api/profile" }, "the role was asked for: ${server.requests}")
        assertFalse(server.received { it.method == "PUT" }, "a viewer never sends a config write: ${server.requests}")
    }

    @Test
    fun aSwitchFlipTheServerRefusesSaysWhyAndLeavesTheSwitchAsTheServerHasIt() = runAppJourney {
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()
        settings.awaitDetection("Front Door", on = true, enabled = true)

        state.edit { failures["/api/config/set"] = 401 }
        settings.flipDetection("Front Door")

        settings.awaitSwitchWrite("front_door", "detect", enabled = false)
        awaitText("Injected failure")
        settings.awaitDetection("Front Door", on = true, enabled = true)
        assertTrue(state.camera("front_door")!!.detectEnabled, "nothing changed on the server")

        settings.press(hasText("Dismiss") and hasClickAction(), "the Dismiss button")
        awaitGone(hasText("Injected failure"), "the error")
    }

    @Test
    fun notificationsSayTheyAreUnavailableWhereThereIsNothingToPostThem() = runAppJourney {
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()

        awaitText(SettingsRobot.NOTIFICATIONS_UNAVAILABLE)
        settings.awaitSwitchInRow("Notifications", on = false, enabled = false)
        // The rules only open up under a working notifications switch.
        assertFalse(exists(hasText("What to hear about")), "no alert rules without notifications")
        assertFalse(exists(hasText("Quiet hours")), "no quiet hours without notifications")
        assertFalse(exists(hasText("Send test notification")), "no test button without notifications")
    }

    @Test
    fun leavingTheTabAndComingBackKeepsTheServerPageOpenAndTheSwitchAsTheServerHasIt() = runAppJourney {
        val settings = SettingsRobot(this)
        signIn.signInAs()
        settings.open()
        settings.flipDetection("Back Yard")
        settings.awaitSwitchWrite("back_yard", "detect", enabled = false)
        settings.awaitDetection("Back Yard", on = false, enabled = true)
        settings.openServer()

        shell.openTab(TopLevelRoute.Home)
        awaitGone(hasText(SettingsRobot.SERVER_PAGE_SUBTITLE), "the Server page")
        shell.openTab(TopLevelRoute.Settings)

        awaitText(SettingsRobot.SERVER_PAGE_SUBTITLE)
        settings.awaitValueUnder("VERSION", "0.17.2-test")
        settings.backFromServer()
        settings.awaitDetection("Back Yard", on = false, enabled = true)
        settings.awaitDetection("Front Door", on = true, enabled = true)
        assertFalse(state.camera("back_yard")!!.detectEnabled, "still off at the server")
    }
}

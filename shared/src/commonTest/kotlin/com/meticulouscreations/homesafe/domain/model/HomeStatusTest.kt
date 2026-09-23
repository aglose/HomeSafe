package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two lines at the top of the home page. Everything is pinned to UTC on one September evening,
 * so the clock labels read the same wherever the test runs; the house has four cameras, all on,
 * and two phones, both home, unless a test says otherwise.
 */
class HomeStatusTest {

    private val day = LocalDate(2026, 9, 22)
    private val utc = TimeZone.UTC

    private fun at(hour: Int, minute: Int, dayOffset: Int = 0): Double =
        (day.toEpochDays() + dayOffset) * 86_400.0 + hour * 3_600 + minute * 60

    private val now = at(19, 30)

    private val fourCameras = listOf("front_door", "back_yard", "driveway", "garage").map { Camera(name = it, enabled = true) }

    private fun phone(name: String, away: Boolean = false, isThisDevice: Boolean = false, counts: Boolean = true) =
        PresenceDevice(name = name, platform = "android", away = away, isThisDevice = isThisDevice, countsForAway = counts)

    private val bothHome = HouseholdPresence(
        devices = listOf(phone("Pixel", isThisDevice = true), phone("iPhone")),
        everyoneAway = false,
    )

    private fun detection(
        start: Double,
        end: Double? = start + 45,
        label: String = "person",
        subLabel: String? = null,
        camera: String = "hikvision_2",
        zones: List<String> = emptyList(),
    ) = MomentEvent(
        id = "e-$start", cameraName = camera, label = label, subLabel = subLabel,
        startEpochSeconds = start, endEpochSeconds = end, topScore = 0.8, hasClip = true, hasSnapshot = true,
        zones = zones,
    )

    private fun status(
        latest: MomentEvent?,
        momentsLoaded: Boolean = true,
        cameras: List<Camera>? = fourCameras,
        presence: HouseholdPresence = bothHome,
    ) = homeStatus(latest, momentsLoaded, cameras, presence, nowEpochSeconds = now, today = day, timeZone = utc)

    @Test
    fun aRecentDetectionIsTheHeadlineWithHowLongAgo() {
        val status = status(detection(start = at(19, 26), end = at(19, 27)))

        assertEquals("Person at Backyard", status.headline)
        assertEquals("3 min ago · 4 cameras on · Everyone home", status.details)
    }

    @Test
    fun theHeadlineSaysWhereWhenAZoneKnows() {
        val status = status(detection(start = at(19, 26), label = "car", subLabel = "sarahs_tesla", zones = listOf("street", "driveway")))

        assertEquals("Sarah's Tesla in the driveway", status.headline, "the classifier's name and the last zone it reached")
    }

    @Test
    fun aDetectionStillInProgressIsHappeningNow() {
        val status = status(detection(start = at(19, 28), end = null))

        assertEquals("Now · 4 cameras on · Everyone home", status.details)
    }

    @Test
    fun anOldDetectionSettlesIntoAllQuietSinceItEnded() {
        val status = status(detection(start = at(18, 55), end = at(18, 56)))

        assertEquals("All quiet since 6:56 PM", status.headline)
        assertEquals("4 cameras on · Everyone home", status.details, "no age once it isn't news")
    }

    @Test
    fun quietSinceYesterdaySaysSo() {
        val status = status(detection(start = at(22, 10, dayOffset = -1), end = at(22, 12, dayOffset = -1)))

        assertEquals("All quiet since 10:12 PM yesterday", status.headline)
    }

    @Test
    fun aCarStillTrackedSinceHoursAgoIsNotNews() {
        // Frigate keeps a parked car's detection open for as long as it sits there.
        val status = status(detection(start = at(16, 5), end = null, label = "car"))

        assertEquals("All quiet since 4:05 PM", status.headline)
    }

    @Test
    fun nothingEverDetectedIsSimplyQuiet() {
        assertEquals("All quiet", status(latest = null).headline)
    }

    @Test
    fun nothingIsSaidOfDetectionsUntilTheyHaveLoaded() {
        val status = status(latest = null, momentsLoaded = false)

        assertNull(status.headline, "not loaded is not the same as quiet")
        assertEquals("4 cameras on · Everyone home", status.details, "the rest of the picture needn't wait for it")
    }

    @Test
    fun detailsWaitForWhatTheyAreBuiltFrom() {
        val status = status(latest = null, momentsLoaded = false, cameras = null, presence = HouseholdPresence.EMPTY)

        assertNull(status.headline)
        assertNull(status.details, "blank, holding its place, rather than a placeholder that gets swapped out")
    }

    @Test
    fun camerasSwitchedOffAreCounted() {
        assertEquals("3 of 4 cameras on", camerasOnLabel(fourCameras.mapIndexed { i, camera -> camera.copy(enabled = i != 2) }))
        assertEquals("1 camera on", camerasOnLabel(listOf(Camera(name = "front_door", enabled = true))))
        assertNull(camerasOnLabel(emptyList()), "the grid already says there are no cameras")
    }

    @Test
    fun presenceReadsFromTheRelaysSnapshot() {
        assertEquals("Everyone home", presenceLabel(bothHome))
        assertEquals(
            "1 of 2 home",
            presenceLabel(bothHome.copy(devices = listOf(phone("Pixel", isThisDevice = true), phone("iPhone", away = true)))),
        )
        assertEquals(
            "You're away",
            presenceLabel(bothHome.copy(devices = listOf(phone("Pixel", away = true, isThisDevice = true), phone("iPhone")))),
            "this phone's own state first: it is what its owner is checking",
        )
        assertEquals(
            "House empty",
            presenceLabel(HouseholdPresence(listOf(phone("Pixel", away = true, isThisDevice = true), phone("iPhone", away = true)), everyoneAway = true)),
        )
        assertNull(presenceLabel(HouseholdPresence.EMPTY), "the relay hasn't answered")
    }

    @Test
    fun onlyCountingPhonesDecideWhoIsHome() {
        // A debug install on an emulator gets pushes but no vote.
        val presence = HouseholdPresence(
            devices = listOf(phone("Pixel"), phone("Emulator", away = true, isThisDevice = false, counts = false)),
            everyoneAway = false,
        )

        assertEquals("Everyone home", presenceLabel(presence))
    }
}

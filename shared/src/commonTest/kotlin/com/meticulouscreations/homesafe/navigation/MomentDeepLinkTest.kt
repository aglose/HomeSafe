package com.meticulouscreations.homesafe.navigation

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MomentDeepLinkTest {

    private val link = MomentDeepLink(eventId = "1790131143.212347-qq7fe8", cameraName = "amcrest_1", startEpochSeconds = 1790131143.212347)

    @AfterTest
    fun clearBus() {
        MomentDeepLinks.pending.value?.let(MomentDeepLinks::consume)
    }

    @Test
    fun survivesTheUriRoundTrip() {
        assertEquals(link, MomentDeepLink.fromUri(link.toUri()))
    }

    @Test
    fun survivesTheStringMapRoundTrip() {
        val map = link.toMap()
        assertEquals(link, MomentDeepLink.from { map[it] })
    }

    @Test
    fun readsTheRelaysPushData() {
        // Exactly what relay.py puts in a push's data, extra keys and all.
        val data = mapOf(
            "review_id" to "1790131140.1-abc",
            "camera" to "hikvision_1",
            "event_id" to "1790131143.212347-qq7fe8",
            "zones" to "driveway",
            "start_time" to "1790131143.212347",
        )
        assertEquals(MomentDeepLink("1790131143.212347-qq7fe8", "hikvision_1", 1790131143.212347), MomentDeepLink.from { data[it] })
    }

    @Test
    fun theTagCarButtonsLinkSurvivesTheRoundTripAndOnlyItOpensThePicker() {
        val tagging = link.copy(tagCar = true)
        assertEquals(tagging, MomentDeepLink.fromUri(tagging.toUri()))
        assertEquals("1", tagging.toMap()["tag_car"])
        assertFalse("tag_car" in link.toMap(), "a plain tap's link reads as it always has")
        // The relay's own flag for an unnamed car is a different key: it marks the push, and must not open the picker on a tap.
        val data = mapOf("camera" to "hikvision_1", "event_id" to "e", "start_time" to "1790131143.5", "car_unnamed" to "1")
        assertFalse(MomentDeepLink.from { data[it] }!!.tagCar)
    }

    @Test
    fun anOlderPushWithNoEventIdStillOpensTheMoment() {
        val data = mapOf("camera" to "hikvision_1", "event_id" to "", "start_time" to "1790131143.5")
        assertEquals(MomentDeepLink("", "hikvision_1", 1790131143.5), MomentDeepLink.from { data[it] })
    }

    @Test
    fun rejectsWhatCannotOpenAMoment() {
        assertNull(MomentDeepLink.from { mapOf("start_time" to "1790131143.5")[it] }, "no camera")
        assertNull(MomentDeepLink.from { mapOf("camera" to "amcrest_1", "start_time" to "")[it] }, "no time (the test push's)")
        assertNull(MomentDeepLink.fromUri("https://moment?camera=a&start_time=1"), "someone else's scheme")
        assertNull(MomentDeepLink.fromUri("homesafe://camera?camera=a&start_time=1"), "another host")
        assertNull(MomentDeepLink.fromUri("not a uri at all"))
    }

    @Test
    fun theBusHoldsALinkUntilItIsConsumedAndKeepsANewerOne() {
        val newer = link.copy(eventId = "newer")
        MomentDeepLinks.open(link)
        MomentDeepLinks.open(newer)
        MomentDeepLinks.consume(link)
        assertEquals(newer, MomentDeepLinks.pending.value, "consuming a stale link leaves the newer tap")
        MomentDeepLinks.consume(newer)
        assertNull(MomentDeepLinks.pending.value)
    }
}

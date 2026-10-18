package com.meticulouscreations.homesafe.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [DoorDeepLink]: the URI behind a door notification's tap, and the hand-off that holds it until the shell exists. */
class DoorDeepLinkTest {

    @Test
    fun aLinkReadsBackFromItsOwnUri() {
        assertEquals("homesafe://doors?door=back_door", DoorDeepLink("back_door").toUri())
        assertEquals(DoorDeepLink("back_door"), DoorDeepLink.fromUri(DoorDeepLink("back_door").toUri()))
        // An id with characters a URI keeps for itself comes back as it went.
        assertEquals(DoorDeepLink("garage & side"), DoorDeepLink.fromUri(DoorDeepLink("garage & side").toUri()))
    }

    @Test
    fun withNoDoorItIsStillTheDoorsPage() {
        assertEquals("homesafe://doors", DoorDeepLink().toUri())
        assertEquals(DoorDeepLink(), DoorDeepLink.fromUri("homesafe://doors"))
        assertEquals(DoorDeepLink(), DoorDeepLink.fromUri("homesafe://doors?door="))
    }

    @Test
    fun anythingElseIsNotADoorLink() {
        assertNull(DoorDeepLink.fromUri("homesafe://moment?camera=front_door&start_time=1791374400"))
        assertNull(DoorDeepLink.fromUri("homesafe://finance?tab=budget"))
        assertNull(DoorDeepLink.fromUri("https://doors?door=back_door"))
        assertNull(DoorDeepLink.fromUri("not a uri"))
        assertNull(DoorDeepLink.fromUri(null))
        // And a door link is none of the others.
        assertNull(MomentDeepLink.fromUri(DoorDeepLink("back_door").toUri()))
        assertNull(WeatherDeepLink.fromUri(DoorDeepLink("back_door").toUri()))
        assertNull(FinanceDeepLink.fromUri(DoorDeepLink("back_door").toUri()))
    }

    @Test
    fun aPushsDataIsADoorLinkOnlyWhenItNamesADoor() {
        // The relay's data for a door opening (`door_announce`), as iOS hands it over in `userInfo`.
        val door = mapOf("door" to "back_door", "door_name" to "Back Door", "door_event" to "opened", "notif_id" to "door-back_door", "away" to "1")
        assertEquals(DoorDeepLink("back_door"), DoorDeepLink.from(door::get))
        assertNull(DoorDeepLink.from(mapOf("door" to "")::get))
        // A detection's push names a camera, not a door.
        assertNull(DoorDeepLink.from(MomentDeepLink("1791374400.1-abc", "front_door", 1791374400.0).toMap()::get))
        assertNull(DoorDeepLink.from(WeatherDeepLink().toMap()::get))
    }

    @Test
    fun aTapWaitsToBeActedOnAndANewerOneReplacesIt() {
        val back = DoorDeepLink("back_door")
        val garage = DoorDeepLink("garage")
        DoorDeepLinks.open(back)
        DoorDeepLinks.open(garage)
        // The first was replaced before anyone looked: clearing it leaves the newer one waiting.
        DoorDeepLinks.consume(back)
        assertEquals(garage, DoorDeepLinks.pending.value)
        DoorDeepLinks.consume(garage)
        assertNull(DoorDeepLinks.pending.value)
    }
}

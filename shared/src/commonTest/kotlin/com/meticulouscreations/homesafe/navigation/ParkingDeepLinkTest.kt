package com.meticulouscreations.homesafe.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [ParkingDeepLink]: the push data behind a parking notification's tap, through a URI and back. */
class ParkingDeepLinkTest {

    private val push = mapOf(
        "parking" to "1",
        "notif_id" to "parking-andrews_tesla",
        "parking_event" to "due",
        "parking_car" to "andrews_tesla",
        "parking_car_name" to "Andrew's Tesla",
        "parking_by" to "1793894400",
        "parking_street" to "Elm St & 3rd",
        "parking_side" to "north",
        "parking_note" to "Street sweeping",
        "parking_from" to "1793894400",
        "parking_until" to "1793905200",
        "parking_lat" to "40.12345",
        "parking_lng" to "-89.54321",
        "parking_heading" to "270",
        "title" to "Move Andrew's Tesla by Thu 8:00 AM",
    )

    @Test
    fun aPushWithASpotSurvivesTheUri() {
        val link = ParkingDeepLink.from(push::get)!!
        assertEquals(link, ParkingDeepLink.fromUri(link.toUri()))
        assertEquals("Elm St & 3rd", link.alert?.street)
        assertEquals(40.12345, link.alert?.latitude)
        assertNull(link.values["title"], "the relay's English stays behind")
    }

    @Test
    fun noSpotOrNotParkingIsNoLink() {
        assertNull(ParkingDeepLink.from((push - "parking_lat")::get))
        assertNull(ParkingDeepLink.from((push - "parking")::get))
        assertNull(ParkingDeepLink.fromUri(DoorDeepLink("back_door").toUri()))
        assertNull(ParkingDeepLink.fromUri("not a uri"))
        assertNull(DoorDeepLink.fromUri(ParkingDeepLink.from(push::get)!!.toUri()))
    }
}

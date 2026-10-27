package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParkingAlertTest {

    // What `parking_text` in relay.py sends for a sweeping the evening before.
    private val sweeping = mapOf(
        "parking" to "1",
        "notif_id" to "parking-andrews_tesla",
        "parking_event" to "due",
        "parking_car" to "andrews_tesla",
        "parking_car_name" to "Andrew's Tesla",
        "parking_by" to "1793894400",
        "parking_street" to "Elm St",
        "parking_side" to "north",
        "parking_rule" to "no_parking",
        "parking_minutes" to "",
        "parking_from" to "1793894400",
        "parking_until" to "1793905200",
        "parking_note" to "Street sweeping",
    )

    @Test
    fun aSweepingIsReadFromThePushsData() {
        assertTrue(ParkingAlert.isParking { sweeping[it] })
        assertEquals(
            ParkingAlert(
                id = "parking-andrews_tesla",
                carName = "Andrew's Tesla",
                event = ParkingAlert.Event.DUE,
                street = "Elm St",
                side = ParkingAlert.Side.NORTH,
                byEpochSeconds = 1_793_894_400L,
                note = "Street sweeping",
                limitMinutes = null,
                fromEpochSeconds = 1_793_894_400L,
                untilEpochSeconds = 1_793_905_200L,
                silent = false,
            ),
            ParkingAlert.from { sweeping[it] },
        )
    }

    @Test
    fun aTimeLimitHasItsMinutesAndNoWindow() {
        val limit = sweeping + mapOf("parking_event" to "now", "parking_rule" to "limit", "parking_minutes" to "120", "parking_note" to "")
        val alert = ParkingAlert.from { limit[it] }
        assertEquals(ParkingAlert.Event.NOW, alert?.event)
        assertEquals(120, alert?.limitMinutes)
        assertNull(alert?.fromEpochSeconds)
        assertNull(alert?.note, "no words of the household's own")
    }

    @Test
    fun aCarThatMovedIsQuietAndHasNoDeadline() {
        val moved = mapOf(
            "parking" to "1",
            "notif_id" to "parking-andrews_tesla",
            "parking_event" to "moved",
            "parking_car" to "andrews_tesla",
            "parking_car_name" to "Andrew's Tesla",
            "parking_street" to "Elm St",
            "silent" to "1",
        )
        val alert = ParkingAlert.from { moved[it] }
        assertEquals(ParkingAlert.Event.MOVED, alert?.event)
        assertTrue(alert?.silent == true)
        assertNull(alert?.byEpochSeconds)
        assertNull(alert?.side)
    }

    @Test
    fun anUnsureSideIsNoSide() {
        assertNull(ParkingAlert.from { (sweeping + ("parking_side" to ""))[it] }?.side)
    }

    @Test
    fun whatThisBuildCantWordIsLeftToTheRelaysEnglish() {
        assertNull(ParkingAlert.from { (sweeping + ("parking_event" to "towed"))[it] }, "an event a newer relay knows about")
        assertNull(ParkingAlert.from { (sweeping - "parking_by")[it] }, "due, but by when?")
        assertFalse(ParkingAlert.isParking { (sweeping - "parking")[it] })
    }

    @Test
    fun theSpotIsReadForTheMapAndDroppedOnceTheCarHasMoved() {
        val parked = ParkingAlert.from((sweeping + mapOf("parking_lat" to "40.12345", "parking_lng" to "-89.54321", "parking_heading" to "270"))::get)
        assertEquals(40.12345, parked?.latitude)
        assertEquals(-89.54321, parked?.longitude)
        assertEquals(270f, parked?.headingDegrees)
        assertNull(ParkingAlert.from(sweeping::get)?.latitude, "an older relay sends no spot")
        assertNull(ParkingAlert.from((sweeping + mapOf("parking_lat" to "40.1", "parking_lng" to ""))::get)?.latitude, "half a spot is none")
        val moved = sweeping + mapOf("parking_event" to "moved", "parking_lat" to "40.1", "parking_lng" to "-89.5")
        assertNull(ParkingAlert.from(moved::get)?.latitude)
    }
}

package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.doors_day_ago
import homesafe.shared.generated.resources.doors_day_today
import homesafe.shared.generated.resources.doors_day_yesterday
import homesafe.shared.generated.resources.doors_detail_battery
import homesafe.shared.generated.resources.doors_detail_battery_low
import homesafe.shared.generated.resources.doors_detail_offline
import homesafe.shared.generated.resources.doors_detail_weak_signal
import homesafe.shared.generated.resources.doors_state_closed
import homesafe.shared.generated.resources.doors_state_closed_days
import homesafe.shared.generated.resources.doors_state_closed_just_now
import homesafe.shared.generated.resources.doors_state_open_hours
import homesafe.shared.generated.resources.doors_state_open_minutes
import homesafe.shared.generated.resources.doors_state_unknown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DoorTextTest {

    private val now = 1_792_300_000_000L

    private fun door(open: Boolean?, agoSeconds: Long?) = Door("0x01", "Front door", open = open, changedAtMillis = agoSeconds?.let { now - it * 1000 })

    @Test
    fun howADoorStandsIsCountedFromItsLastChange() {
        assertEquals(UiText.of(Res.string.doors_state_unknown), doorStateText(door(null, null), now))
        assertEquals(UiText.of(Res.string.doors_state_closed), doorStateText(door(false, null), now))
        assertEquals(UiText.of(Res.string.doors_state_closed_just_now), doorStateText(door(false, 30), now))
        assertEquals(UiText.plural(Res.plurals.doors_state_open_minutes, 5), doorStateText(door(true, 5 * 60 + 20), now))
        assertEquals(UiText.plural(Res.plurals.doors_state_open_hours, 2), doorStateText(door(true, 2 * 3_600 + 59 * 60), now))
        assertEquals(UiText.plural(Res.plurals.doors_state_closed_days, 3), doorStateText(door(false, 3 * 86_400 + 10), now))
    }

    @Test
    fun theSensorsHealthSaysOnlyWhatsWorthSaying() {
        val dot = UiText.of(Res.string.common_dot_separator)
        assertNull(doorHealthText(Door("0x01", "Front door", open = false)))
        assertEquals(UiText.Joined(listOf(UiText.of(Res.string.doors_detail_battery, 87)), dot), doorHealthText(Door("0x01", "Front door", open = false, battery = 87, linkQuality = 120)))
        assertEquals(
            UiText.Joined(listOf(UiText.of(Res.string.doors_detail_offline), UiText.of(Res.string.doors_detail_battery_low), UiText.of(Res.string.doors_detail_weak_signal)), dot),
            doorHealthText(Door("0x01", "Front door", open = false, battery = 15, linkQuality = 12, online = false)),
        )
    }

    @Test
    fun historyDays() {
        assertEquals(UiText.of(Res.string.doors_day_today), doorDayText(0))
        assertEquals(UiText.of(Res.string.doors_day_yesterday), doorDayText(1))
        assertEquals(UiText.plural(Res.plurals.doors_day_ago, 4), doorDayText(4))
    }

    @Test
    fun aDoorAlertIsReadFromThePushsData() {
        val data = mapOf("door" to "0x01", "door_name" to "Front door", "door_event" to "opened", "notif_id" to "door-0x01", "away" to "1")
        assertEquals(DoorAlert("door-0x01", "0x01", "Front door", away = true, silent = false), DoorAlert.from { data[it] })
        assertEquals(true, DoorAlert.isDoor { data[it] })
        assertNull(DoorAlert.from { (data + ("door_event" to "left_open"))[it] }, "an event this build can't word")
        assertEquals(false, DoorAlert.isDoor { mapOf("review_id" to "r1")[it] })
    }

    @Test
    fun homesGlanceShowsTheDoorsThatNeedALookAndCountsTheRest() {
        val back = Door("0x01", "Back door", open = true)
        val front = Door("0x02", "Front door", open = false)
        val garage = Door("0x03", "Garage door", open = false, online = false)
        val patio = Door("0x04", "Patio door", open = null)
        val shed = Door("0x05", "Shed door", open = true, online = false)
        val porch = Door("0x07", "Porch door", open = false, battery = 12)
        val glance = doorGlance(listOf(front, garage, back, patio, shed, porch))
        assertEquals(listOf(back, garage, patio, shed, porch), glance.shown, "open (and heard from) first, then the rest in order — a low battery needs a look too")
        assertEquals(1, glance.closedCount)
        assertEquals(false, glance.allClosed)
        assertEquals(DoorGlance(emptyList(), closedCount = 2, allClosed = true), doorGlance(listOf(front, front.copy(id = "0x06"))))
        assertEquals(DoorGlance(emptyList(), closedCount = 0, allClosed = false), doorGlance(emptyList()))
    }
}

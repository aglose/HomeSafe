package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MomentEventTest {

    private val utc = TimeZone.UTC
    private fun event(label: String = "person", start: Double, end: Double? = start + 15, sub: String? = null) = MomentEvent(
        id = "e", cameraName = "hikvision_1", label = label, subLabel = sub,
        startEpochSeconds = start, endEpochSeconds = end, topScore = 0.9, hasClip = true, hasSnapshot = true,
    )

    @Test
    fun labelsMapOntoTheThreeChips() {
        assertEquals(MomentCategory.PEOPLE, categoryForLabel("person"))
        assertEquals(MomentCategory.VEHICLES, categoryForLabel("car"))
        assertEquals(MomentCategory.VEHICLES, categoryForLabel("Truck"))
        assertEquals(MomentCategory.ANIMALS, categoryForLabel("dog"))
        // Unknown labels are still shown under All Events, just not under any specific chip.
        assertEquals(MomentCategory.ALL, categoryForLabel("package"))
    }

    @Test
    fun groupsAsTodayYesterdayWeekdayThenDate() {
        val today = LocalDate(2026, 9, 2)                // a Wednesday
        val noon = { d: LocalDate -> d.toEpochDays() * 86_400.0 + 12 * 3_600 }
        assertEquals("Today", event(start = noon(LocalDate(2026, 9, 2))).present(today, utc).dateGroup)
        assertEquals("Yesterday", event(start = noon(LocalDate(2026, 9, 1))).present(today, utc).dateGroup)
        assertEquals("Sunday", event(start = noon(LocalDate(2026, 8, 30))).present(today, utc).dateGroup)
        assertEquals("Aug 20", event(start = noon(LocalDate(2026, 8, 20))).present(today, utc).dateGroup)
        assertEquals("Sep 2", event(start = noon(LocalDate(2026, 9, 2))).present(today, utc).dateSubLabel)
    }

    @Test
    fun formatsTimeDurationTitleAndBadge() {
        val today = LocalDate(2026, 9, 2)
        val start = LocalDate(2026, 9, 2).toEpochDays() * 86_400.0 + 8 * 3_600 + 42 * 60   // 08:42 UTC
        val p = event(start = start, end = start + 15, sub = "andrew").present(today, utc)
        assertEquals("8:42 AM", p.timeLabel)
        assertEquals("0:15", p.durationLabel)
        assertEquals("Person detected", p.title)
        assertEquals("person · andrew", p.badgeLabel)
        assertEquals("Car detected", event(label = "car", start = start).present(today, utc).title)
    }

    @Test
    fun inProgressEventHasNoDuration() {
        val today = LocalDate(2026, 9, 2)
        val e = event(start = today.toEpochDays() * 86_400.0, end = null)
        assertEquals(true, e.isInProgress)
        assertNull(e.present(today, utc).durationLabel)
    }
}

package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shapes taken from the Moments tab on 2026-09-22: five clips of one person in the backyard
 * between 6:55 and 6:56 PM, and Andrew's Tesla filed eight times between 5:51 and 6:30 PM.
 */
class MomentVisitsTest {

    private val day = LocalDate(2026, 9, 22)
    private val utc = TimeZone.UTC

    private fun at(hour: Int, minute: Int, second: Int = 0): Double = day.toEpochDays() * 86_400.0 + hour * 3_600 + minute * 60 + second

    private fun event(
        id: String,
        start: Double,
        end: Double? = start + 12,
        label: String = "person",
        camera: String = "backyard",
        subLabel: String? = null,
        subLabelScore: Double? = null,
        zones: List<String> = emptyList(),
        hasClip: Boolean = true,
    ) = MomentEvent(
        id = id, cameraName = camera, label = label, subLabel = subLabel,
        startEpochSeconds = start, endEpochSeconds = end, topScore = 0.8, hasClip = hasClip, hasSnapshot = false,
        zones = zones, subLabelScore = subLabelScore,
    )

    /** The backyard at dusk: five person clips seconds apart, as Frigate lost and re-found them. */
    private val backyard = listOf(
        event("p1", at(18, 55, 2)),
        event("p2", at(18, 55, 20)),
        event("p3", at(18, 55, 41)),
        event("p4", at(18, 56, 0)),
        event("p5", at(18, 56, 25)),
    )

    @Test
    fun clipsOfOnePersonOnOneCameraSecondsApartAreOneVisit() {
        val visits = backyard.shuffled().groupIntoVisits()
        assertEquals(1, visits.size)
        val visit = visits.single()
        assertEquals(VisitKind.VISIT, visit.kind)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), visit.events.map { it.id }, "oldest first")
        assertEquals("p1", visit.key)
        assertEquals("p1", visit.lead.id, "a tap plays how the visit started")
    }

    @Test
    fun aVisitReadsAsOneCardWithItsSpanAndClipCount() {
        val p = backyard.groupIntoVisits().single().present(day, utc)
        assertEquals("Person detected", p.title)
        assertEquals("6:55–6:56 PM", p.timeLabel)
        assertEquals("Backyard", p.locationLabel)
        assertEquals("5 clips", p.clipCountLabel)
        assertEquals("person", p.badgeLabel)
    }

    @Test
    fun aGapLongerThanTheVisitGapStartsANewVisit() {
        val later = event("p6", at(18, 56, 37) + MomentVisits.VISIT_GAP_SECONDS + 1)
        val visits = (backyard + later).groupIntoVisits()
        assertEquals(listOf(listOf("p6"), listOf("p1", "p2", "p3", "p4", "p5")), visits.map { v -> v.events.map { it.id } }, "newest first")
        assertEquals(VisitKind.SINGLE, visits.first().kind)
    }

    @Test
    fun theGapIsMeasuredFromTheEndOfTheLastClip() {
        // A long clip followed by one starting 2 min after it ended but 10 min after it started.
        val long = event("long", at(10, 0), end = at(10, 8))
        val next = event("next", at(10, 10))
        assertEquals(1, listOf(long, next).groupIntoVisits().size)
    }

    @Test
    fun aClipStillInProgressKeepsItsVisitOpen() {
        val live = event("live", at(10, 0), end = null)
        val next = event("next", at(10, 30))
        val visit = listOf(live, next).groupIntoVisits().single()
        assertNull(visit.endEpochSeconds)
        assertEquals("Since 10:00 AM", visit.present(day, utc).timeLabel)
    }

    @Test
    fun otherCamerasAndOtherLabelsAreOtherVisits() {
        val visits = listOf(
            event("a", at(9, 0)),
            event("b", at(9, 0, 30), camera = "front_door"),
            event("c", at(9, 1), label = "dog"),
            event("d", at(9, 1, 30)),
        ).groupIntoVisits()
        val byKey = visits.associate { it.key to it.events.map { e -> e.id } }
        assertEquals(mapOf("a" to listOf("a", "d"), "b" to listOf("b"), "c" to listOf("c")), byKey, "a dog between two person clips doesn't split the person's visit")
    }

    @Test
    fun twoDifferentPeopleAreNotOneVisitButANameJoinsAnAnonymousOne() {
        val visits = listOf(
            event("anon", at(9, 0)),
            event("andrew", at(9, 0, 30), subLabel = "andrew"),
            event("sarah", at(9, 1), subLabel = "sarah"),
        ).groupIntoVisits()
        assertEquals(setOf(listOf("anon", "andrew"), listOf("sarah")), visits.map { v -> v.events.map { it.id } }.toSet())
        val andrews = visits.first { it.key == "anon" }
        assertEquals("andrew", andrews.subLabel)
        assertEquals("Andrew detected", andrews.present(day, utc).title, "the visit is titled by whoever was recognised in it")
    }

    @Test
    fun aVisitIsPlacedWhereItEndedUp() {
        val visit = listOf(
            event("a", at(9, 0), zones = listOf("lawn")),
            event("b", at(9, 1), zones = listOf("sidewalk")),
        ).groupIntoVisits().single()
        val p = visit.present(day, utc)
        assertEquals("Person on the sidewalk", p.title)
        assertEquals("Backyard · Lawn, Sidewalk", p.locationLabel)
    }

    @Test
    fun theLeadIsTheFirstClipThatCanPlay() {
        val visit = listOf(event("noclip", at(9, 0), hasClip = false), event("clip", at(9, 1))).groupIntoVisits().single()
        assertEquals("noclip", visit.key)
        assertEquals("clip", visit.lead.id)
    }

    /** Andrew's Tesla between 5:51 and 6:30 PM, on two cameras, as the classifier named it. */
    private val tesla = listOf(
        at(17, 51),
        at(17, 58),
        at(18, 4),
        at(18, 11),
        at(18, 17),
        at(18, 22),
        at(18, 27),
        at(18, 30),
    ).mapIndexed { i, start ->
        event("t$i", start, label = "car", camera = if (i % 2 == 0) "front_yard" else "hikvision_2", subLabel = "andrews_tesla", subLabelScore = 0.9)
    }

    @Test
    fun aHouseholdCarsComingsAndGoingsFoldIntoOneRoutine() {
        val visits = tesla.groupIntoVisits()
        val routine = visits.single()
        assertEquals(VisitKind.ROUTINE, routine.kind)
        assertEquals(8, routine.events.size, "across cameras")
        val p = routine.present(day, utc)
        assertEquals("Andrew's Tesla came and went 8×", p.title)
        assertEquals("5:51–6:30 PM", p.timeLabel)
        assertEquals("Front Yard, Backyard", p.locationLabel)
        assertEquals("8 sightings", p.clipCountLabel)
    }

    @Test
    fun theRoutineGapSplitsAHouseholdCarsDay() {
        val tonight = event("tonight", at(18, 30) + MomentVisits.ROUTINE_GAP_SECONDS + 60, label = "car", subLabel = "andrews_tesla")
        val visits = (tesla + tonight).groupIntoVisits()
        assertEquals(listOf(VisitKind.SINGLE, VisitKind.ROUTINE), visits.map { it.kind })
    }

    @Test
    fun twoHouseholdCarsAreTwoRoutinesAndAStrangersCarIsNotARoutine() {
        val yaya = listOf(event("y1", at(18, 0), label = "car", subLabel = "yayas_car"), event("y2", at(18, 10), label = "car", subLabel = "yayas_car"))
        val stranger = event("s", at(18, 5), label = "car")
        val visits = (tesla + yaya + stranger).groupIntoVisits()
        assertEquals(3, visits.size)
        assertEquals(listOf(VisitKind.SINGLE, VisitKind.ROUTINE, VisitKind.ROUTINE), visits.map { it.kind }.sorted())
        assertFalse(visits.single { it.key == "s" }.isFamiliar)
    }

    @Test
    fun theClassifiersRejectCategoryIsNeverAName() {
        val notOurs = event("n", at(18, 0), label = "car", subLabel = "none")
        val slugged = event("n2", at(18, 1), label = "car", subLabel = "not_ours")
        val unknownFace = event("u", at(18, 2), subLabel = "unknown")
        listOf(notOurs, slugged, unknownFace).forEach {
            assertFalse(it.isFamiliar, "${it.subLabel} is not a name")
            assertFalse(it.isHouseholdCar)
        }
        assertEquals("Car detected", notOurs.present(day, utc).title)
        assertEquals("Person detected", unknownFace.present(day, utc).title)
    }

    @Test
    fun aVisitIsFamiliarWhenAnyOfItsClipsWasRecognised() {
        val visit = listOf(event("a", at(9, 0)), event("b", at(9, 0, 30), subLabel = "andrew")).groupIntoVisits().single()
        assertTrue(visit.isFamiliar)
        assertFalse(backyard.groupIntoVisits().single().isFamiliar)
    }

    @Test
    fun aSingleDetectionReadsExactlyAsItDidBefore() {
        val e = event("solo", at(8, 42), zones = listOf("patio"))
        assertEquals(e.present(day, utc), listOf(e).groupIntoVisits().single().present(day, utc))
    }

    @Test
    fun rangesShareTheirMeridiemAndCollapseWithinAMinute() {
        assertEquals("6:55–6:56 PM", clockRangeLabel(at(18, 55), at(18, 56), utc))
        assertEquals("11:58 AM–12:04 PM", clockRangeLabel(at(11, 58), at(12, 4), utc))
        assertEquals("6:55 PM", clockRangeLabel(at(18, 55, 1), at(18, 55, 40), utc))
        assertEquals("Since 6:55 PM", clockRangeLabel(at(18, 55), null, utc))
    }
}

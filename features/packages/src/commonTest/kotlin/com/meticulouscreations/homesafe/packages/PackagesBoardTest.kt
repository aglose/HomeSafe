package com.meticulouscreations.homesafe.packages

import com.meticulouscreations.homesafe.packages.domain.CameraVerdict
import com.meticulouscreations.homesafe.packages.domain.Carrier
import com.meticulouscreations.homesafe.packages.domain.PackagesAnalysis
import com.meticulouscreations.homesafe.packages.domain.PackagesBoard
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ParcelDays
import com.meticulouscreations.homesafe.packages.domain.ParcelFilter
import com.meticulouscreations.homesafe.packages.domain.ParcelProblem
import com.meticulouscreations.homesafe.packages.domain.ParcelStage
import com.meticulouscreations.homesafe.packages.domain.ParcelZone
import com.meticulouscreations.homesafe.packages.domain.PorchState
import com.meticulouscreations.homesafe.packages.domain.ReturnState
import com.meticulouscreations.homesafe.packages.ui.PackagesFormat
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackagesBoardTest {
    private val today = ParcelDays.of(2026, 10, 30)
    private val now = today * 86_400L + 15 * 3600

    private fun board(vararg parcels: Parcel) = PackagesBoard.of(parcels.toList(), today, now)

    private fun on(id: String, stage: ParcelStage = ParcelStage.SHIPPED, from: Int? = null, to: Int? = from) = Parcel(id = id, stage = stage, expectedFromDay = from, expectedToDay = to)

    @Test
    fun dayNumbersRoundTrip() {
        listOf(Triple(1970, 1, 1), Triple(2024, 2, 29), Triple(2026, 12, 31), Triple(2027, 1, 1)).forEach { (y, m, d) ->
            assertEquals(Triple(y, m, d), ParcelDays.date(ParcelDays.of(y, m, d)))
        }
        assertEquals(0, ParcelDays.of(1970, 1, 1))
        assertEquals(today, ParcelDays.parse("2026-10-30"))
        assertEquals("2026-10-30", ParcelDays.format(today))
        assertNull(ParcelDays.parse("soon"))
    }

    @Test
    fun aWeekdayStartsOnMonday() {
        // 2026-10-30 is a Friday.
        assertEquals(4, ParcelDays.weekday(today))
    }

    @Test
    fun eachParcelGoesWhereItWillBeLookedFor() {
        val outForDelivery = on("out", ParcelStage.OUT_FOR_DELIVERY)
        val due = on("due", from = today - 1, to = today + 1)
        val tomorrow = on("tomorrow", from = today + 1)
        val later = on("later", from = today + 4)
        val unknown = on("unknown")
        val board = board(outForDelivery, due, tomorrow, later, unknown)
        assertEquals(setOf("out", "due"), board.today.map { it.id }.toSet())
        assertEquals(listOf("tomorrow"), board.tomorrow.map { it.id })
        assertEquals(listOf("later", "unknown"), board.later.map { it.id })
        assertEquals(4 + 1, board.onTheWay)
    }

    @Test
    fun whatNeedsSomeoneComesOutOfTheOtherLists() {
        val attempted = on("attempted", ParcelStage.OUT_FOR_DELIVERY).copy(problem = ParcelProblem.ATTEMPTED)
        val late = on("late", from = today - 2).copy(late = true)
        val gone = Parcel(id = "gone", stage = ParcelStage.DELIVERED, porch = PorchState.MAY_BE_GONE, deliveredAt = now)
        val signature = on("sign", from = today + 1).copy(signature = true)
        val board = board(attempted, late, gone, signature)
        assertEquals(setOf("attempted", "late", "gone", "sign"), board.attention.map { it.id }.toSet())
        assertTrue(board.today.isEmpty() && board.tomorrow.isEmpty() && board.onPorch.isEmpty())
    }

    @Test
    fun aSignatureStopsMatteringOnceItHasCome() {
        val signed = Parcel(id = "signed", stage = ParcelStage.DELIVERED, signature = true, deliveredAt = now)
        assertFalse(signed.needsAttention)
        assertEquals(listOf("signed"), board(signed).arrived.map { it.id })
    }

    @Test
    fun outThereIsApartFromTakenInAndOldNewsDropsOff() {
        val out = Parcel(id = "out", stage = ParcelStage.DELIVERED, porch = PorchState.ON_PORCH, deliveredAt = now - 60)
        val inside = Parcel(id = "in", stage = ParcelStage.DELIVERED, porch = PorchState.INSIDE, deliveredAt = now - 3600)
        val old = Parcel(id = "old", stage = ParcelStage.DELIVERED, porch = PorchState.INSIDE, deliveredAt = now - 30 * 86_400L)
        val board = board(out, inside, old)
        assertEquals(listOf("out"), board.onPorch.map { it.id })
        assertEquals(listOf("in"), board.arrived.map { it.id })
    }

    @Test
    fun archivedIsOffTheBoardAndPinnedComesFirst() {
        val board = board(on("hidden", ParcelStage.OUT_FOR_DELIVERY).copy(archived = true), on("a", from = today + 3), on("pinned", from = today + 9).copy(pinned = true))
        assertEquals(listOf("pinned", "a"), board.later.map { it.id })
    }

    @Test
    fun anEmptyBoardKnowsItIs() {
        assertTrue(board().isEmpty)
        assertFalse(board(on("a")).isEmpty)
    }

    @Test
    fun aFilterNarrowsByWordsPersonAndCarrier() {
        val parcel = Parcel(id = "a", owner = "sam", carrier = Carrier.UPS, title = "Hand-thrown mugs", shop = "Etsy", tracking = "1Z999")
        assertTrue(ParcelFilter(query = "mugs etsy").matches(parcel))
        assertTrue(ParcelFilter(query = "1z9").matches(parcel))
        assertTrue(ParcelFilter(query = "ups").matches(parcel))
        assertFalse(ParcelFilter(query = "towels").matches(parcel))
        assertFalse(ParcelFilter(owner = "alex").matches(parcel))
        assertFalse(ParcelFilter(carrier = Carrier.USPS).matches(parcel))
        assertFalse(ParcelFilter(onlyActive = true).matches(parcel.copy(stage = ParcelStage.DELIVERED)))
    }

    @Test
    fun aSurpriseKeptFromThisPhoneCantBeFoundByWhatItIs() {
        val kept = Parcel(id = "kept", masked = true, owner = "alex", carrier = Carrier.UPS)
        assertFalse(ParcelFilter(query = "ring").matches(kept))
    }

    // Patterns

    private fun came(hour: Int, carrier: Carrier = Carrier.AMAZON, daysAgo: Int = 1, owner: String = "sam", shop: String? = null, total: Double? = null, promised: Int? = null) = Parcel(
        id = "$carrier$hour$daysAgo", owner = owner, stage = ParcelStage.DELIVERED, carrier = carrier, shop = shop, total = total, createdAt = (today - daysAgo - 2) * 86_400L,
        deliveredAt = (today - daysAgo) * 86_400L + hour * 3600, expectedToDay = promised,
    )

    @Test
    fun aCarrierWithEnoughHistoryGetsAGuessAtTheHour() {
        val past = listOf(13, 14, 14, 15, 14).mapIndexed { i, hour -> came(hour, daysAgo = i + 1) }
        val guess = assertNotNull(PackagesAnalysis.arrivalGuess(on("coming").copy(carrier = Carrier.AMAZON), past, ParcelZone.fixed(0)))
        assertEquals(5, guess.basedOn)
        assertTrue(guess.fromHour in 12..14 && guess.toHour in 14..16, "$guess")
    }

    @Test
    fun aGuessFromAlmostNothingIsntOne() {
        assertNull(PackagesAnalysis.arrivalGuess(on("coming").copy(carrier = Carrier.AMAZON), listOf(came(13), came(14, daysAgo = 2)), ParcelZone.fixed(0)))
        assertNull(PackagesAnalysis.arrivalGuess(on("coming").copy(carrier = Carrier.UPS), List(5) { came(13, daysAgo = it + 1) }, ParcelZone.fixed(0)))
    }

    @Test
    fun aDeliveredParcelNeedsNoGuess() {
        assertNull(PackagesAnalysis.arrivalGuess(came(13), List(5) { came(13, daysAgo = it + 1) }, ParcelZone.fixed(0)))
    }

    @Test
    fun theDoorCameraMomentBeatsTheEmailsForWhenItCame() {
        // The email can come hours after the parcel did; the visit is when it came.
        val past = List(4) { came(20, daysAgo = it + 1).copy(visitAt = (today - it - 1) * 86_400L + 11 * 3600) }
        val guess = assertNotNull(PackagesAnalysis.arrivalGuess(on("coming").copy(carrier = Carrier.AMAZON), past, ParcelZone.fixed(0)))
        assertTrue(guess.toHour <= 12, "$guess")
    }

    @Test
    fun theYearsNumbersAddUp() {
        val parcels = listOf(
            came(13, total = 10.0),
            came(13, Carrier.UPS, daysAgo = 2, owner = "alex", shop = "Target", total = 5.0),
            came(9, Carrier.UPS, daysAgo = 40, owner = "alex", shop = "Target", total = 20.0, promised = today - 41),
            came(9, Carrier.UPS, daysAgo = 400),
        )
        val insights = PackagesAnalysis.insights(parcels, today, ParcelZone.fixed(0))
        assertEquals(4, insights.deliveredAll)
        assertEquals(3, insights.deliveredThisYear)
        assertEquals(2, insights.deliveredThisMonth)
        assertEquals(1, insights.deliveredLastMonth)
        assertEquals(15.0, insights.spentThisMonth)
        assertEquals(35.0, insights.spentThisYear)
        assertEquals(listOf("sam" to 2, "alex" to 2).toSet(), insights.perPerson.toSet())
        assertEquals(listOf("Target" to 2), insights.topShops)
        assertEquals(12, insights.months.size)
        assertEquals(2, insights.months.last().count)
        val ups = insights.carriers.first { it.carrier == Carrier.UPS }
        assertEquals(0, ups.onTimePercent)
        assertNull(insights.carriers.first { it.carrier == Carrier.AMAZON }.onTimePercent)
    }

    @Test
    fun thePeakIsTheBusiestHourOfTheBusiestDay() {
        val parcels = listOf(came(13), came(13, daysAgo = 8), came(9, daysAgo = 2))
        val insights = PackagesAnalysis.insights(parcels, today, ParcelZone.fixed(0))
        assertEquals(13, insights.busiestHour)
        assertEquals(ParcelDays.weekday(today - 1), insights.busiestWeekday)
    }

    @Test
    fun hoursAreOnTheHouseholdsClock() {
        val insights = PackagesAnalysis.insights(listOf(came(20)), today, ParcelZone.fixed(-7 * 3600))
        assertEquals(13, insights.busiestHour)
    }

    @Test
    fun nothingDeliveredHasNoPeak() {
        val insights = PackagesAnalysis.insights(listOf(on("coming")), today, ParcelZone.fixed(0))
        assertNull(insights.busiestHour)
        assertEquals(0, insights.heatMax)
    }

    @Test
    fun aParcelTheCameraMadeUpHasNoCarrierToRate() {
        val made = came(13).copy(carrier = Carrier.OTHER, fromCamera = true, verdict = CameraVerdict.CAMERA_ONLY)
        assertTrue(PackagesAnalysis.insights(listOf(made), today, ParcelZone.fixed(0)).carriers.isEmpty())
    }

    @Test
    fun returnsAreSortedByWhatsClosingFirst() {
        val soon = came(9).copy(id = "soon", returnByDay = today + 2)
        val later = came(9).copy(id = "later", returnByDay = today + 20)
        val over = came(9).copy(id = "over", returnByDay = today - 1)
        val sent = came(9).copy(id = "sent", returnByDay = today + 5, returnState = ReturnState.SENT)
        val kept = came(9).copy(id = "kept", masked = true, returnByDay = today + 1)
        val (open, moving) = PackagesAnalysis.returnsOf(listOf(later, over, soon, sent, kept), today)
        assertEquals(listOf("soon", "later"), open.map { it.id })
        assertEquals(listOf("sent"), moving.map { it.id })
    }

    @Test
    fun aMomentReadsByTheClocksOfItsOwnDay() {
        val zone = ParcelZone.of(TimeZone.of("America/Los_Angeles"))
        // Noon on either side of the clocks changing on 2026-11-01: UTC-7, then UTC-8.
        assertEquals(720, PackagesFormat.minuteOfDay(ParcelDays.of(2026, 10, 31) * 86_400L + 19 * 3600, zone))
        assertEquals(720, PackagesFormat.minuteOfDay(ParcelDays.of(2026, 11, 2) * 86_400L + 20 * 3600, zone))
    }

    @Test
    fun aPromisedDayGoneByIsntTodayOrNext() {
        val board = board(on("past", from = today - 3, to = today - 2), on("no_end", from = today - 3, to = null), on("coming", from = today + 4, to = today + 5))
        assertTrue(board.today.isEmpty())
        assertEquals("coming", board.later.first().id)
        assertEquals(3, board.later.size)
    }
}

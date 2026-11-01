package com.meticulouscreations.homesafe.charging

import com.meticulouscreations.homesafe.charging.domain.CarMonth
import com.meticulouscreations.homesafe.charging.domain.ChargeCostSource
import com.meticulouscreations.homesafe.charging.domain.ChargeMeasure
import com.meticulouscreations.homesafe.charging.domain.ChargePlace
import com.meticulouscreations.homesafe.charging.domain.ChargeSession
import com.meticulouscreations.homesafe.charging.domain.ChargingMonth
import com.meticulouscreations.homesafe.charging.domain.ChargingSummary
import com.meticulouscreations.homesafe.charging.domain.GasComparison
import com.meticulouscreations.homesafe.charging.ui.ChargingFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargingModelsTest {
    private val alex = CarMonth("a", "Alex", homeKwh = 100.0, homeCost = 28.0, fastKwh = 40.0, fastCost = 16.0, sessions = 5, miles = 600.0, usedKwh = 150.0)
    private val sam = CarMonth("b", "Sam", homeKwh = 50.0, homeCost = 14.0, otherKwh = 10.0, otherCost = 3.0, sessions = 3)

    @Test
    fun aCarsMonthAddsUpAndSaysWhatAMileCost() {
        assertEquals(44.0, alex.cost)
        assertEquals(140.0, alex.kwh)
        assertEquals(44.0 / 600.0, alex.costPerMile)
        assertEquals(4.0, alex.milesPerKwh)
    }

    @Test
    fun aCarThatHasntSaidItsMilesHasNoPerMile() {
        assertNull(sam.costPerMile)
        assertNull(sam.milesPerKwh)
    }

    @Test
    fun aMonthCountsWhatTheCardsPaidBeyondTheReceipts() {
        val month = ChargingMonth("2026-10", listOf(alex, sam), cardFast = 21.0, unmatched = 5.0, peakKwh = 15.0)
        assertEquals(2026, month.year)
        assertEquals(10, month.month)
        assertEquals(21.0, month.fastCost)
        assertEquals(66.0, month.cost)
        assertEquals(8, month.sessions)
        assertEquals(600.0, month.miles)
        assertEquals(0.1, month.peakShare)
        assertEquals(42.0 / 150.0, month.homePerKwh)
    }

    @Test
    fun aMonthsMilesOnlyMatchItsCostOnceEveryCarHasSaid() {
        val known = CarMonth("a", "Alex", homeCost = 10.0, miles = 100.0)
        val unknown = CarMonth("b", "Sam", homeCost = 10.0)
        val partly = ChargingMonth("2026-10", cars = listOf(known, unknown))
        assertEquals(100.0, partly.miles)
        assertNull(partly.milesOfEveryCar)
        assertNull(partly.costPerMile)
        val whole = ChargingMonth("2026-10", cars = listOf(known, unknown.copy(miles = 100.0)))
        assertEquals(200.0, whole.milesOfEveryCar)
        assertEquals(0.1, whole.costPerMile)
    }

    @Test
    fun milesOnlyGetACostOnceEveryChargeHasAPrice() {
        val car = CarMonth("a", "Alex", homeCost = 10.0, miles = 100.0, unpriced = 1)
        assertNull(car.costPerMile)
        assertNull(ChargingMonth("2026-10", cars = listOf(car), unpriced = 1).costPerMile)
        assertEquals(0.1, ChargingMonth("2026-10", cars = listOf(car.copy(unpriced = 0))).costPerMile)
    }

    @Test
    fun aPriceCanBeTypedEitherWayItsDecimalsAreWritten() {
        for ((text, price) in listOf(
            "3.50" to 3.5, "3,50" to 3.5, "$12" to 12.0, " 7 " to 7.0, ".5" to 0.5, "3." to 3.0, "1,234.50" to 1234.5, "1.234,50" to 1234.5, "1,234" to 1234.0, "1.234" to 1234.0, "1,234,567" to 1234567.0,
        )) {
            assertEquals(price, ChargingFormat.price(text), text)
        }
        for (text in listOf("", ".", ",", "abc", "1,2,3", "-5", "1e5", "NaN", "1.2.3", "12,34.5")) {
            assertNull(ChargingFormat.price(text), text)
        }
    }

    @Test
    fun aMonthKnownOnlyFromTheCardsCostsWhatTheyPaid() {
        val month = ChargingMonth("2026-08", cardFast = 22.0, unmatched = 22.0)
        assertEquals(22.0, month.cost)
        assertEquals(0.0, month.kwh)
        assertNull(month.miles)
        assertNull(month.peakShare)
    }

    private fun session(ended: Long? = 200, missed: Boolean = false, measure: ChargeMeasure = ChargeMeasure.POWER, cost: Double? = 5.0, kwh: Double = 20.0) = ChargeSession(
        id = 1, car = "a", carName = "Alex", place = ChargePlace.HOME, missed = missed, startedEpochSeconds = 100, endedEpochSeconds = ended, socStart = 40, socEnd = 60,
        kwh = kwh, peakKw = 7.7, measure = measure, site = null, cost = cost, costSource = ChargeCostSource.PLAN, note = null,
    )

    @Test
    fun aChargeIsAnEstimateWhenOnlyTheBatterySaidHowMuch() {
        assertFalse(session().isEstimate)
        assertTrue(session(measure = ChargeMeasure.BATTERY).isEstimate)
        assertTrue(session(missed = true).isEstimate)
        assertFalse(session(ended = null, measure = ChargeMeasure.BATTERY).isEstimate)
    }

    @Test
    fun aChargesPriceAndLength() {
        assertEquals(0.25, session().pricePerKwh)
        assertNull(session(cost = null).pricePerKwh)
        assertNull(session(kwh = 0.0).pricePerKwh)
        assertEquals(100, session().durationSeconds(1_000))
        assertEquals(900, session(ended = null).durationSeconds(1_000))
    }

    @Test
    fun gasCostsItsPriceOverItsMileage() {
        assertEquals(48.0, GasComparison(4.8, 30.0).costOf(300.0))
    }

    @Test
    fun numbersAreGroupedAndRounded() {
        assertEquals("1,234.50", ChargingFormat.fixed(1234.5))
        assertEquals("0.29", ChargingFormat.fixed(0.2876))
        assertEquals("-3.10", ChargingFormat.fixed(-3.1))
        assertEquals("0.00", ChargingFormat.fixed(-0.001))
        assertEquals("1,000,000", ChargingFormat.fixed(999_999.6, 0))
        assertEquals("12.3", ChargingFormat.energy(12.34))
        assertEquals("123", ChargingFormat.energy(123.4))
    }
}

package com.meticulouscreations.homesafe.charging.ui

import com.meticulouscreations.homesafe.charging.ChargingUiState
import com.meticulouscreations.homesafe.charging.domain.CarMonth
import com.meticulouscreations.homesafe.charging.domain.CarNow
import com.meticulouscreations.homesafe.charging.domain.ChargeCostSource
import com.meticulouscreations.homesafe.charging.domain.ChargeHour
import com.meticulouscreations.homesafe.charging.domain.ChargeMeasure
import com.meticulouscreations.homesafe.charging.domain.ChargePlace
import com.meticulouscreations.homesafe.charging.domain.ChargeSession
import com.meticulouscreations.homesafe.charging.domain.ChargeSessionDetail
import com.meticulouscreations.homesafe.charging.domain.ChargingMonth
import com.meticulouscreations.homesafe.charging.domain.ChargingSetup
import com.meticulouscreations.homesafe.charging.domain.ChargingSummary
import com.meticulouscreations.homesafe.charging.domain.GasComparison
import com.meticulouscreations.homesafe.charging.domain.RatePeriod
import com.meticulouscreations.homesafe.charging.domain.RatePlan
import com.meticulouscreations.homesafe.charging.domain.RatePrice

/** A made-up household's charging, for previews and tests. No real numbers: the repository is public. */
object ChargingFixtures {
    const val NOW = 1_793_001_600L
    private const val HOUR = 3600L
    private const val DAY = 86_400L

    private fun charge(id: Long, car: String, name: String, place: ChargePlace, agoDays: Long, hours: Double, kwh: Double, cost: Double?, source: ChargeCostSource?, site: String? = null, missed: Boolean = false, measure: ChargeMeasure = ChargeMeasure.POWER, soc: Pair<Int, Int> = 42 to 80) = ChargeSession(
        id = id, car = car, carName = name, place = place, missed = missed,
        startedEpochSeconds = NOW - (agoDays + 1) * DAY - 2 * HOUR, endedEpochSeconds = NOW - (agoDays + 1) * DAY - 2 * HOUR + (hours * HOUR).toLong(),
        socStart = soc.first, socEnd = soc.second, kwh = kwh, peakKw = if (place.isFast) 186.0 else 7.7, measure = measure, site = site, cost = cost, costSource = source, note = null,
    )

    val sessions = listOf(
        charge(9, "car_a", "Alex", ChargePlace.HOME, 0, 3.5, 26.9, 7.07, ChargeCostSource.PLAN),
        charge(8, "car_b", "Sam", ChargePlace.HOME, 1, 4.2, 32.3, 8.49, ChargeCostSource.PLAN),
        charge(7, "car_a", "Alex", ChargePlace.SUPERCHARGER, 3, 0.45, 41.0, 16.40, ChargeCostSource.RECEIPT, site = "Example Plaza", soc = 18 to 72).copy(idleFee = 1.50),
        charge(6, "car_b", "Sam", ChargePlace.OTHER, 5, 2.0, 11.2, null, null, soc = 50 to 63),
        charge(5, "car_a", "Alex", ChargePlace.HOME, 6, 5.0, 30.1, 7.91, ChargeCostSource.PLAN, missed = true, measure = ChargeMeasure.BATTERY),
        charge(4, "car_b", "Sam", ChargePlace.OTHER, 8, 3.0, 18.0, 6.50, ChargeCostSource.TYPED, soc = 30 to 54),
    )

    private val tiered = RatePlan(
        id = "tiered", name = "Standard residential", isTimeOfUse = false, url = "https://example.com/standard", current = true, effective = "2026-07-01",
        prices = listOf(RatePrice(RatePeriod.TIER1, 0.12, 0.1263), RatePrice(RatePeriod.TIER2, 0.25, 0.2628)), baselineSummer = 200, baselineWinter = 250,
        weekday = List(24) { 0.2628 }, weekend = List(24) { 0.2628 },
    )
    private val tou = RatePlan(
        id = "ev_tou", name = "EV time of use", isTimeOfUse = true, url = "https://example.com/ev", current = false, effective = "2026-07-01",
        prices = listOf(RatePrice(RatePeriod.OFF_PEAK, 0.15, 0.1578), RatePrice(RatePeriod.PEAK, 0.45, 0.4728)), peakStart = 17, peakEnd = 21,
        weekday = List(24) { if (it in 17 until 21) 0.4728 else 0.1578 }, weekend = List(24) { 0.1578 },
    )

    private val months = listOf(
        ChargingMonth(
            key = "2026-10",
            cars = listOf(
                CarMonth("car_a", "Alex", 196.0, 54.8, 41.0, 16.4, 0.0, 0.0, 9, 1012.0, 268.0, homePricedKwh = 196.0, fastPricedKwh = 41.0, homeBatteryKwh = 176.4, fastBatteryKwh = 41.0, fastIdle = 1.5, homePricedCost = 54.8, fastPricedCost = 14.9),
                CarMonth("car_b", "Sam", 231.0, 64.6, 0.0, 0.0, 29.2, 6.5, 11, 1185.0, 301.0, homePricedKwh = 231.0, otherPricedKwh = 18.0, homeBatteryKwh = 207.9, otherBatteryKwh = 16.2, homePricedCost = 64.6, otherPricedCost = 6.5),
            ),
            cardFast = 16.4,
            unmatched = 0.0,
            electricPaid = 240.0,
            peakKwh = 38.0,
            planCosts = mapOf("tiered" to 119.4, "ev_tou" to 87.1),
        ),
        ChargingMonth(
            key = "2026-09",
            cars = listOf(
                CarMonth("car_a", "Alex", 172.0, 48.1, 62.0, 27.5, 0.0, 0.0, 8, 1120.0, 301.0, homePricedKwh = 172.0, fastPricedKwh = 62.0, homeBatteryKwh = 154.8, fastBatteryKwh = 62.0, homePricedCost = 48.1, fastPricedCost = 27.5),
                CarMonth("car_b", "Sam", 204.0, 57.1, 18.0, 8.1, 0.0, 0.0, 9, 980.0, 266.0, homePricedKwh = 204.0, fastPricedKwh = 18.0, homeBatteryKwh = 183.6, fastBatteryKwh = 18.0, homePricedCost = 57.1, fastPricedCost = 8.1),
            ),
            cardFast = 50.0,
            unmatched = 14.4,
            electricPaid = 275.0,
            peakKwh = 20.0,
            planCosts = mapOf("tiered" to 105.2, "ev_tou" to 71.0),
        ),
        ChargingMonth(key = "2026-08", cars = listOf(CarMonth("car_a", "Alex", fastKwh = 30.0, fastCost = 12.6, sessions = 1, fastPricedKwh = 30.0, fastBatteryKwh = 30.0, fastPricedCost = 12.6)), cardFast = 22.0, unmatched = 9.4, electricPaid = 210.0, homeRecorded = false),
        ChargingMonth(key = "2026-07", cardFast = 9.0, unmatched = 9.0, electricPaid = 195.0, homeRecorded = false),
    )

    val summary = ChargingSummary(
        providerName = "Example Power",
        providerUrl = "https://example.com",
        planId = "tiered",
        plans = listOf(tiered, tou),
        recentPeakShare = 0.06,
        taxPercent = 5.0,
        surchargePerKwh = 0.0003,
        customerCharge = 20.0,
        gas = GasComparison(4.8, 30.0),
        now = listOf(
            CarNow("car_a", "Alex", 57, 80, 182.0, 96, 7.7, charge(10, "car_a", "Alex", ChargePlace.HOME, 0, 1.0, 11.9, 3.13, ChargeCostSource.PLAN, soc = 42 to 57).copy(endedEpochSeconds = null)),
            CarNow("car_b", "Sam", 80, 80, 248.0, null, null, null),
        ),
        months = months,
        byHour = List(24) { hour ->
            if (hour >= 21 || hour < 3) {
                42.0
            } else if (hour in 17 until 21) {
                9.5
            } else {
                1.0
            }
        },
        sessions = sessions,
        moreSessions = true,
        firstSessionEpochSeconds = NOW - 60 * DAY,
    )

    val detail = ChargeSessionDetail(
        session = sessions.first(),
        hours = listOf(
            ChargeHour(NOW - DAY - 2 * HOUR, 3.1, 0.2628, RatePeriod.TIER2),
            ChargeHour(NOW - DAY - HOUR, 7.68, 0.2628, RatePeriod.TIER2),
            ChargeHour(NOW - DAY, 7.68, 0.2628, RatePeriod.TIER2),
            ChargeHour(NOW - DAY + HOUR, 7.68, 0.2628, RatePeriod.TIER2),
            ChargeHour(NOW - DAY + 2 * HOUR, 0.76, 0.2628, RatePeriod.TIER2),
        ),
        plans = mapOf("tiered" to 7.07, "ev_tou" to 4.25),
    )

    fun state(summary: ChargingSummary = this.summary, detail: ChargeSessionDetail? = null) = ChargingUiState(
        loaded = true,
        summary = summary,
        moreSessions = summary.moreSessions,
        nowEpochSeconds = NOW,
        detail = detail,
    )

    /** Before the first charge, with the box not set up yet. */
    val emptyState = state(ChargingSummary(setup = ChargingSetup(rates = false, powerStream = false, receipts = false)))
}

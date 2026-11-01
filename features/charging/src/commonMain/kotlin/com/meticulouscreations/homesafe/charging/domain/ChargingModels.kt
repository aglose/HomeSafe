package com.meticulouscreations.homesafe.charging.domain

import androidx.compose.runtime.Immutable

/** Where a charge happened. */
enum class ChargePlace {
    HOME,
    SUPERCHARGER,

    /** Another company's fast charger. */
    FAST,
    OTHER,
    ;

    /** Paid for at the charger, rather than on the electricity bill. */
    val isFast: Boolean get() = this == SUPERCHARGER || this == FAST

    companion object {
        fun of(code: String?): ChargePlace = when (code) {
            "home" -> HOME
            "supercharger" -> SUPERCHARGER
            "fast" -> FAST
            else -> OTHER
        }
    }
}

/** Where a charge's price came from. */
enum class ChargeCostSource {
    /** The electricity plan's rate for each of its hours. */
    PLAN,

    /** Tesla's receipt. */
    RECEIPT,

    /** Someone typed it in. */
    TYPED,
    ;

    companion object {
        fun of(code: String?): ChargeCostSource? = when (code) {
            "plan" -> PLAN
            "receipt" -> RECEIPT
            "typed" -> TYPED
            else -> null
        }
    }
}

/** How a charge's energy is known. */
enum class ChargeMeasure {
    /** The charger's volts and amps as it ran. */
    POWER,

    /** Tesla's receipt. */
    RECEIPT,

    /** What the battery gained, plus what charging loses: an estimate. */
    BATTERY,
    ;

    companion object {
        fun of(code: String?): ChargeMeasure = when (code) {
            "power" -> POWER
            "receipt" -> RECEIPT
            else -> BATTERY
        }
    }
}

/** One of a plan's rates. Each hour of a charge at home is billed at one. */
enum class RatePeriod {
    PEAK,
    OFF_PEAK,
    TIER1,
    TIER2,
    ;

    companion object {
        fun of(code: String?): RatePeriod? = when (code) {
            "peak" -> PEAK
            "off_peak" -> OFF_PEAK
            "tier1" -> TIER1
            "tier2" -> TIER2
            else -> null
        }
    }
}

/** One charge of one car. */
@Immutable
data class ChargeSession(
    val id: Long,
    val car: String,
    val carName: String,
    val place: ChargePlace,
    /** Only the battery's rise was seen: charged while the relay wasn't listening. */
    val missed: Boolean,
    val startedEpochSeconds: Long,
    /** Null while it is still charging. */
    val endedEpochSeconds: Long?,
    val socStart: Int?,
    val socEnd: Int?,
    /** What it took from the charger. */
    val kwh: Double,
    val peakKw: Double?,
    val measure: ChargeMeasure,
    /** The Supercharger's name, from Tesla's receipt. Data, not copy. */
    val site: String?,
    /** Null when no one knows what a charge away from home cost. */
    val cost: Double?,
    val costSource: ChargeCostSource?,
    val note: String?,
    /** Its start to the fraction, as the relay holds it: what asks for the charges before this one. */
    val pageKey: Double = startedEpochSeconds.toDouble(),
    /** What of [cost] was for staying plugged in after it finished, by Tesla's receipt. */
    val idleFee: Double = 0.0,
) {
    val isOpen: Boolean get() = endedEpochSeconds == null

    /** Its energy was worked out from the battery rather than measured. */
    val isEstimate: Boolean get() = !isOpen && (missed || measure == ChargeMeasure.BATTERY)

    /** What each kWh came to, idle fees aside; null without a price or any energy. */
    val pricePerKwh: Double? get() = cost?.takeIf { kwh > 0 }?.let { (it - idleFee) / kwh }

    fun durationSeconds(nowEpochSeconds: Long): Long = (endedEpochSeconds ?: nowEpochSeconds) - startedEpochSeconds
}

/** The energy of one hour of a charge, priced on the household's plan. */
@Immutable
data class ChargeHour(val epochSeconds: Long, val kwh: Double, val price: Double?, val period: RatePeriod?)

/** A charge hour by hour. [plans] is what it would have cost on each plan, by plan id: charges at home only. */
@Immutable
data class ChargeSessionDetail(val session: ChargeSession, val hours: List<ChargeHour>, val plans: Map<String, Double>)

/** One car's month. */
@Immutable
data class CarMonth(
    val car: String,
    val name: String,
    val homeKwh: Double = 0.0,
    val homeCost: Double = 0.0,
    val fastKwh: Double = 0.0,
    val fastCost: Double = 0.0,
    val otherKwh: Double = 0.0,
    val otherCost: Double = 0.0,
    val sessions: Int = 0,
    /** Driven this month, by the odometer; null until the car has said it twice. */
    val miles: Double? = null,
    /** What it used, by the car's lifetime counter. */
    val usedKwh: Double? = null,
    /** Charges with energy and no known price, which [cost] leaves out. */
    val unpriced: Int = 0,
    /** The energy of the charges that have a price, at each place: what a price per kWh divides by. */
    val homePricedKwh: Double = 0.0,
    val fastPricedKwh: Double = 0.0,
    val otherPricedKwh: Double = 0.0,
    /** What of that energy reached the battery, past what charging loses: what a price per mile goes by. */
    val homeBatteryKwh: Double = 0.0,
    val fastBatteryKwh: Double = 0.0,
    val otherBatteryKwh: Double = 0.0,
    /** What of [fastCost] was for staying plugged in. */
    val fastIdle: Double = 0.0,
    /** What those priced charges cost, idle fees aside: a receipt with a price and no energy is in [cost] but not here. */
    val homePricedCost: Double = 0.0,
    val fastPricedCost: Double = 0.0,
    val otherPricedCost: Double = 0.0,
) {
    val cost: Double get() = homeCost + fastCost + otherCost
    val kwh: Double get() = homeKwh + fastKwh + otherKwh

    val homePerKwh: Double? get() = priceOf(homePricedCost, homePricedKwh)
    val fastPerKwh: Double? get() = priceOf(fastPricedCost, fastPricedKwh)
    val otherPerKwh: Double? get() = priceOf(otherPricedCost, otherPricedKwh)

    /** All its charging together, idle fees aside. */
    val perKwh: Double? get() = priceOf(homePricedCost + fastPricedCost + otherPricedCost, homePricedKwh + fastPricedKwh + otherPricedKwh)

    /** The battery's kWh a mile took; null until both are known. */
    val kwhPerMile: Double? get() = miles?.takeIf { it > 0 }?.let { driven -> usedKwh?.takeIf { it > 0 }?.let { it / driven } }

    val homePerMile: Double? get() = listOf(this).homePerMile()
    val fastPerMile: Double? get() = listOf(this).fastPerMile()
    val otherPerMile: Double? get() = listOf(this).otherPerMile()

    operator fun plus(other: CarMonth) = copy(
        homeKwh = homeKwh + other.homeKwh,
        homeCost = homeCost + other.homeCost,
        fastKwh = fastKwh + other.fastKwh,
        fastCost = fastCost + other.fastCost,
        otherKwh = otherKwh + other.otherKwh,
        otherCost = otherCost + other.otherCost,
        sessions = sessions + other.sessions,
        miles = sumOfKnown(miles, other.miles),
        usedKwh = sumOfKnown(usedKwh, other.usedKwh),
        unpriced = unpriced + other.unpriced,
        homePricedKwh = homePricedKwh + other.homePricedKwh,
        fastPricedKwh = fastPricedKwh + other.fastPricedKwh,
        otherPricedKwh = otherPricedKwh + other.otherPricedKwh,
        homeBatteryKwh = homeBatteryKwh + other.homeBatteryKwh,
        fastBatteryKwh = fastBatteryKwh + other.fastBatteryKwh,
        otherBatteryKwh = otherBatteryKwh + other.otherBatteryKwh,
        fastIdle = fastIdle + other.fastIdle,
        homePricedCost = homePricedCost + other.homePricedCost,
        fastPricedCost = fastPricedCost + other.fastPricedCost,
        otherPricedCost = otherPricedCost + other.otherPricedCost,
    )

    /** Dollars to go a mile; null without miles, or while part of what they cost is unknown. */
    val costPerMile: Double? get() = miles?.takeIf { it > 0 && unpriced == 0 }?.let { cost / it }

    /** Miles out of each kWh it used; null until both are known. */
    val milesPerKwh: Double? get() = usedKwh?.takeIf { it > 0 }?.let { used -> miles?.let { it / used } }
}

/** Priced energy is what makes a price known, so energy that was free has a rate of zero; null without any. */
private fun priceOf(cost: Double, kwh: Double): Double? = kwh.takeIf { it > 0 }?.let { cost.coerceAtLeast(0.0) / it }

private fun sumOfKnown(a: Double?, b: Double?): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)

/**
 * What a mile cost on energy from one place, for these cars together: its price over the miles its
 * battery kWh went, each car by its own mileage. Counting from the battery takes in what charging
 * loses, which is what makes a kWh from the wall comparable with a fast charger's. Null without any
 * car whose mileage and energy from there are both known.
 */
private fun List<CarMonth>.perMile(cost: (CarMonth) -> Double, battery: (CarMonth) -> Double): Double? {
    val known = filter { it.kwhPerMile != null && battery(it) > 0 }
    val miles = known.sumOf { car -> car.kwhPerMile?.let { battery(car) / it } ?: 0.0 }
    return miles.takeIf { it > 0 }?.let { known.sumOf(cost) / it }
}

internal fun List<CarMonth>.homePerMile(): Double? = perMile({ it.homePricedCost }, { it.homeBatteryKwh })

internal fun List<CarMonth>.fastPerMile(): Double? = perMile({ it.fastPricedCost }, { it.fastBatteryKwh })

internal fun List<CarMonth>.otherPerMile(): Double? = perMile({ it.otherPricedCost }, { it.otherBatteryKwh })

/** One month of the household's charging. */
@Immutable
data class ChargingMonth(
    /** "2026-10". */
    val key: String,
    val cars: List<CarMonth> = emptyList(),
    /** What the cards paid for Superchargers; null for someone not allowed the finances. */
    val cardFast: Double? = null,
    /** What the cards paid for Superchargers beyond Tesla's receipts. No one car's. */
    val unmatched: Double = 0.0,
    /** What was paid to the electricity provider; null for someone not allowed the finances. */
    val electricPaid: Double? = null,
    /** kWh charged at home during a time-of-use plan's peak hours. */
    val peakKwh: Double = 0.0,
    /** What the month's charging at home comes to on each plan, by plan id. */
    val planCosts: Map<String, Double> = emptyMap(),
    /** Charges whose price [cost] doesn't hold, by any receipt, card or plan. */
    val unpriced: Int = 0,
    /** The relay was listening to the cars by then. Before it, the month is only what Tesla's receipts and the cards say of Superchargers. */
    val homeRecorded: Boolean = true,
) {
    val year: Int get() = key.substringBefore('-').toIntOrNull() ?: 0
    val month: Int get() = key.substringAfter('-').toIntOrNull() ?: 1

    val homeCost: Double get() = cars.sumOf { it.homeCost }
    val homeKwh: Double get() = cars.sumOf { it.homeKwh }

    /** Fast charging, what's on the cards beyond the receipts included. */
    val fastCost: Double get() = cars.sumOf { it.fastCost } + unmatched
    val fastKwh: Double get() = cars.sumOf { it.fastKwh }
    val otherCost: Double get() = cars.sumOf { it.otherCost }
    val otherKwh: Double get() = cars.sumOf { it.otherKwh }
    val cost: Double get() = homeCost + fastCost + otherCost
    val kwh: Double get() = homeKwh + fastKwh + otherKwh
    val sessions: Int get() = cars.sumOf { it.sessions }
    val miles: Double? get() = cars.mapNotNull { it.miles }.takeIf { it.isNotEmpty() }?.sum()

    /** The miles the whole month's [cost] went on: null until every car has said its own, or a total would set one car's miles against both cars' charging. */
    val milesOfEveryCar: Double? get() = if (cars.isNotEmpty() && cars.all { it.miles != null }) miles else null

    /** Dollars to go a mile; null without every car's miles, or while part of what they cost is unknown. */
    val costPerMile: Double? get() = milesOfEveryCar?.takeIf { it > 0 && unpriced == 0 }?.let { cost / it }

    /** What a kWh came to at home; null with no price for it yet, which isn't free. */
    val homePerKwh: Double? get() = priceOf(cars.sumOf { it.homePricedCost }, cars.sumOf { it.homePricedKwh })

    /** What a kWh came to at fast chargers and elsewhere, idle fees aside. What the cards paid beyond the receipts has no kWh, so it doesn't come into it. */
    val fastPerKwh: Double? get() = priceOf(cars.sumOf { it.fastPricedCost }, cars.sumOf { it.fastPricedKwh })
    val otherPerKwh: Double? get() = priceOf(cars.sumOf { it.otherPricedCost }, cars.sumOf { it.otherPricedKwh })

    val homePerMile: Double? get() = cars.homePerMile()
    val fastPerMile: Double? get() = cars.fastPerMile()
    val otherPerMile: Double? get() = cars.otherPerMile()

    /** Two months that can fairly be set side by side: a month without home charging says nothing against one with it. */
    fun comparesWith(other: ChargingMonth): Boolean = homeRecorded == other.homeRecorded

    /** The share of home charging in peak hours, 0 to 1. */
    val peakShare: Double? get() = homeKwh.takeIf { it > 0 }?.let { (peakKwh / it).coerceIn(0.0, 1.0) }
}

/** One of a plan's rates, as the utility states it and as the bill charges it. */
@Immutable
data class RatePrice(val period: RatePeriod, val rate: Double, val billed: Double)

/** An electricity plan as it stands today. */
@Immutable
data class RatePlan(
    val id: String,
    val name: String,
    val isTimeOfUse: Boolean,
    val url: String?,
    val current: Boolean,
    /** When these rates took effect, "2026-07-01". */
    val effective: String?,
    val prices: List<RatePrice>,
    val peakStart: Int? = null,
    val peakEnd: Int? = null,
    val baselineSummer: Int? = null,
    val baselineWinter: Int? = null,
    /** What a kWh costs as billed in each of a day's 24 hours. */
    val weekday: List<Double> = emptyList(),
    val weekend: List<Double> = emptyList(),
)

/** The price of gas and the mileage the charging is set against. */
@Immutable
data class GasComparison(val pricePerGallon: Double, val milesPerGallon: Double) {
    fun costOf(miles: Double): Double = miles / milesPerGallon * pricePerGallon
}

/** A car's charging at this moment. */
@Immutable
data class CarNow(
    val car: String,
    val name: String,
    val battery: Int?,
    val limit: Int?,
    val rangeMiles: Double?,
    val minutesToFull: Int?,
    val kw: Double?,
    val session: ChargeSession?,
)

/** What's still owed on the box for the page to say all it can. */
@Immutable
data class ChargingSetup(
    /** The electricity plans' rates are there, so home charging has a price. */
    val rates: Boolean = true,
    /** The cars have been told to stream volts and amps, so a charge is measured at the wall. */
    val powerStream: Boolean = true,
    /** Tesla answers for its receipts, so a Supercharger's price and name are known. */
    val receipts: Boolean = true,
) {
    val complete: Boolean get() = rates && powerStream && receipts
}

/** Everything the charging app opens on. */
@Immutable
data class ChargingSummary(
    val providerName: String? = null,
    val providerUrl: String? = null,
    val planId: String? = null,
    val plans: List<RatePlan> = emptyList(),
    val taxPercent: Double? = null,
    val surchargePerKwh: Double? = null,
    val customerCharge: Double? = null,
    val gas: GasComparison? = null,
    val now: List<CarNow> = emptyList(),
    /** Newest first. */
    val months: List<ChargingMonth> = emptyList(),
    /** kWh charged at home in each hour of the day, lately. */
    val byHour: List<Double> = emptyList(),
    /** How much of that, 0 to 1, a time-of-use plan would price at its peak; null without such a plan or any charging. */
    val recentPeakShare: Double? = null,
    /** The newest charges. */
    val sessions: List<ChargeSession> = emptyList(),
    val moreSessions: Boolean = false,
    val firstSessionEpochSeconds: Long? = null,
    val setup: ChargingSetup = ChargingSetup(),
) {
    val plan: RatePlan? get() = plans.firstOrNull { it.id == planId }

    /** One car's months, newest first, each as a month holding only that car. */
    fun monthsOf(car: String): List<ChargingMonth> = months.mapNotNull { month ->
        month.cars.firstOrNull { it.car == car }?.let { month.copy(cars = listOf(it), cardFast = null, unmatched = 0.0, electricPaid = null, unpriced = it.unpriced) }
    }

    /** The same without what the cards and banks said. A Supercharger known only from the cards then has no price. */
    fun withoutCards(): ChargingSummary = copy(months = months.map { it.copy(cardFast = null, unmatched = 0.0, electricPaid = null, unpriced = it.cars.sumOf { car -> car.unpriced }) })

    /** A car charging now. */
    val charging: List<CarNow> get() = now.filter { it.session != null }

    val isEmpty: Boolean get() = months.isEmpty() && sessions.isEmpty()
}

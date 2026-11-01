package com.meticulouscreations.homesafe.charging.data

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
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_error_no_such_charge
import homesafe.shared.generated.resources.charging_error_outdated
import homesafe.shared.generated.resources.charging_error_relay_answered
import homesafe.shared.generated.resources.charging_error_signed_out
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What the Teslas cost to charge, through the relay (`/charging` in `relay/relay.py`), which keeps
 * every charge and works the prices out when asked. Rides on the Frigate session cookie.
 */
@Inject
class ChargingRelayApi(private val httpClient: HttpClient) {

    suspend fun summary(serverUrl: String): Result<ChargingSummary> = call {
        httpClient.get(PushRelayApi.relayUrl(serverUrl, "/charging")).answer<RelayCharging>().toDomain()
    }

    /** Charges older than [before] (a charge's [pageKey][ChargeSession.pageKey]), and whether there are older ones still. */
    suspend fun olderSessions(serverUrl: String, before: Double): Result<Pair<List<ChargeSession>, Boolean>> = call {
        val page = httpClient.get(PushRelayApi.relayUrl(serverUrl, "/charging/sessions")) { parameter("before", before) }.answer<RelayChargePage>()
        page.sessions.map { it.toDomain() } to page.moreSessions
    }

    suspend fun session(serverUrl: String, id: Long): Result<ChargeSessionDetail> = call {
        httpClient.get(PushRelayApi.relayUrl(serverUrl, "/charging/sessions/$id")).answer<RelayChargeDetail>().toDomain()
    }

    /** A price typed in for a charge, and a note on it; null takes either away. */
    suspend fun saveSession(serverUrl: String, id: Long, cost: Double?, note: String?): Result<ChargeSessionDetail> = call {
        httpClient.post(PushRelayApi.relayUrl(serverUrl, "/charging/sessions/$id")) {
            contentType(ContentType.Application.Json)
            setBody(RelayChargePatch(cost, note))
        }.answer<RelayChargeDetail>().toDomain()
    }

    suspend fun choosePlan(serverUrl: String, plan: String): Result<ChargingSummary> = call {
        httpClient.post(PushRelayApi.relayUrl(serverUrl, "/charging/plan")) {
            contentType(ContentType.Application.Json)
            setBody(RelayPlanChoice(plan))
        }.answer<RelayCharging>().toDomain()
    }

    private suspend inline fun <T> call(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend inline fun <reified T> HttpResponse.answer(): T = when {
        status.isSuccess() -> body<T>()

        // The route's own 404 for a charge gone from the ledger; or FastAPI's for a route it doesn't have, a relay from before this app.
        status == HttpStatusCode.NotFound -> throw LocalizedException(
            UiText.of(if ("no such charge" in bodyAsText()) Res.string.charging_error_no_such_charge else Res.string.charging_error_outdated),
            technical = "HTTP 404",
        )

        status == HttpStatusCode.Unauthorized -> throw LocalizedException(UiText.of(Res.string.charging_error_signed_out), technical = "HTTP 401")

        else -> throw LocalizedException(UiText.of(Res.string.charging_error_relay_answered, status.value.toString()), technical = "The relay answered $status")
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class RelayChargePatch(@EncodeDefault val cost: Double?, @EncodeDefault val note: String?)

@Serializable
internal data class RelayPlanChoice(val plan: String)

@Serializable
internal data class RelayCharge(
    val id: Long,
    val car: String,
    val name: String = "",
    val place: String? = null,
    val source: String? = null,
    val started: Double,
    val ended: Double? = null,
    @SerialName("soc_start") val socStart: Double? = null,
    @SerialName("soc_end") val socEnd: Double? = null,
    val kwh: Double = 0.0,
    @SerialName("peak_kw") val peakKw: Double? = null,
    val how: String? = null,
    val site: String? = null,
    val cost: Double? = null,
    @SerialName("cost_is") val costIs: String? = null,
    val note: String? = null,
    @SerialName("idle_fee") val idleFee: Double = 0.0,
) {
    fun toDomain() = ChargeSession(
        id = id, car = car, carName = name.ifBlank { car }, place = ChargePlace.of(place), missed = source == "soc",
        startedEpochSeconds = started.roundToLong(), endedEpochSeconds = ended?.roundToLong(),
        socStart = socStart?.roundToInt(), socEnd = socEnd?.roundToInt(), kwh = kwh, peakKw = peakKw?.takeIf { it > 0 }, measure = ChargeMeasure.of(how),
        site = site, cost = cost, costSource = ChargeCostSource.of(costIs), note = note, pageKey = started, idleFee = idleFee,
    )
}

@Serializable
internal data class RelayChargePage(val sessions: List<RelayCharge> = emptyList(), @SerialName("more_sessions") val moreSessions: Boolean = false)

@Serializable
internal data class RelayChargeHour(val hour: Double, val kwh: Double = 0.0, val price: Double? = null, val period: String? = null)

@Serializable
internal data class RelayChargeDetail(
    val id: Long,
    val car: String,
    val name: String = "",
    val place: String? = null,
    val source: String? = null,
    val started: Double,
    val ended: Double? = null,
    @SerialName("soc_start") val socStart: Double? = null,
    @SerialName("soc_end") val socEnd: Double? = null,
    val kwh: Double = 0.0,
    @SerialName("peak_kw") val peakKw: Double? = null,
    val how: String? = null,
    val site: String? = null,
    val cost: Double? = null,
    @SerialName("cost_is") val costIs: String? = null,
    val note: String? = null,
    val hours: List<RelayChargeHour> = emptyList(),
    val plans: Map<String, Double> = emptyMap(),
    @SerialName("idle_fee") val idleFee: Double = 0.0,
) {
    fun toDomain() = ChargeSessionDetail(
        session = RelayCharge(id, car, name, place, source, started, ended, socStart, socEnd, kwh, peakKw, how, site, cost, costIs, note, idleFee).toDomain(),
        hours = hours.map { ChargeHour(it.hour.roundToLong(), it.kwh, it.price, RatePeriod.of(it.period)) },
        plans = plans,
    )
}

@Serializable
internal data class RelayCarMonth(
    val car: String,
    val name: String = "",
    @SerialName("home_kwh") val homeKwh: Double = 0.0,
    @SerialName("home_cost") val homeCost: Double = 0.0,
    @SerialName("fast_kwh") val fastKwh: Double = 0.0,
    @SerialName("fast_cost") val fastCost: Double = 0.0,
    @SerialName("other_kwh") val otherKwh: Double = 0.0,
    @SerialName("other_cost") val otherCost: Double = 0.0,
    val sessions: Int = 0,
    val miles: Double? = null,
    @SerialName("used_kwh") val usedKwh: Double? = null,
    val unpriced: Int = 0,
    @SerialName("home_priced_kwh") val homePricedKwh: Double = 0.0,
    @SerialName("fast_priced_kwh") val fastPricedKwh: Double = 0.0,
    @SerialName("other_priced_kwh") val otherPricedKwh: Double = 0.0,
    @SerialName("home_battery_kwh") val homeBatteryKwh: Double = 0.0,
    @SerialName("fast_battery_kwh") val fastBatteryKwh: Double = 0.0,
    @SerialName("other_battery_kwh") val otherBatteryKwh: Double = 0.0,
    @SerialName("fast_idle") val fastIdle: Double = 0.0,
)

@Serializable
internal data class RelayChargingMonth(
    val month: String,
    val cars: List<RelayCarMonth> = emptyList(),
    @SerialName("card_fast") val cardFast: Double? = null,
    val unmatched: Double = 0.0,
    @SerialName("electric_paid") val electricPaid: Double? = null,
    @SerialName("peak_kwh") val peakKwh: Double = 0.0,
    val plans: Map<String, Double> = emptyMap(),
    val unpriced: Int = 0,
)

@Serializable
internal data class RelayRatePrice(val period: String, val rate: Double, val billed: Double)

@Serializable
internal data class RelayRatePlan(
    val id: String,
    val name: String = "",
    val kind: String? = null,
    val url: String? = null,
    val current: Boolean = false,
    val effective: String? = null,
    val periods: List<RelayRatePrice> = emptyList(),
    @SerialName("peak_start") val peakStart: Int? = null,
    @SerialName("peak_end") val peakEnd: Int? = null,
    @SerialName("baseline_summer") val baselineSummer: Double? = null,
    @SerialName("baseline_winter") val baselineWinter: Double? = null,
    val weekday: List<Double> = emptyList(),
    val weekend: List<Double> = emptyList(),
)

@Serializable
internal data class RelayProvider(val name: String? = null, val url: String? = null)

@Serializable
internal data class RelayGas(val price: Double? = null, val mpg: Double? = null)

@Serializable
internal data class RelayCarNow(
    val car: String,
    val name: String = "",
    val battery: Double? = null,
    val limit: Double? = null,
    @SerialName("range_mi") val rangeMiles: Double? = null,
    @SerialName("minutes_to_full") val minutesToFull: Int? = null,
    val kw: Double? = null,
    val session: RelayCharge? = null,
)

@Serializable
internal data class RelayChargingSetup(val rates: Boolean = false, @SerialName("power_stream") val powerStream: Boolean = false, val receipts: Boolean = false)

@Serializable
internal data class RelayCharging(
    val provider: RelayProvider? = null,
    val plan: String? = null,
    val plans: List<RelayRatePlan> = emptyList(),
    @SerialName("tax_percent") val taxPercent: Double? = null,
    @SerialName("surcharge_per_kwh") val surchargePerKwh: Double? = null,
    @SerialName("customer_charge") val customerCharge: Double? = null,
    val gas: RelayGas? = null,
    val now: List<RelayCarNow> = emptyList(),
    val months: List<RelayChargingMonth> = emptyList(),
    @SerialName("by_hour") val byHour: List<Double> = emptyList(),
    @SerialName("recent_peak_share") val recentPeakShare: Double? = null,
    val sessions: List<RelayCharge> = emptyList(),
    @SerialName("more_sessions") val moreSessions: Boolean = false,
    @SerialName("first_session") val firstSession: Double? = null,
    @SerialName("recording_from") val recordingFrom: String? = null,
    val setup: RelayChargingSetup = RelayChargingSetup(),
) {
    fun toDomain() = ChargingSummary(
        providerName = provider?.name,
        providerUrl = provider?.url,
        planId = plan,
        plans = plans.map { p ->
            RatePlan(
                id = p.id, name = p.name.ifBlank { p.id }, isTimeOfUse = p.kind == "tou", url = p.url, current = p.current, effective = p.effective,
                prices = p.periods.mapNotNull { price -> RatePeriod.of(price.period)?.let { RatePrice(it, price.rate, price.billed) } },
                peakStart = p.peakStart, peakEnd = p.peakEnd, baselineSummer = p.baselineSummer?.roundToInt(), baselineWinter = p.baselineWinter?.roundToInt(), weekday = p.weekday, weekend = p.weekend,
            )
        },
        taxPercent = taxPercent,
        surchargePerKwh = surchargePerKwh,
        customerCharge = customerCharge,
        gas = gas?.let { g ->
            val price = g.price
            val mpg = g.mpg
            if (price != null && mpg != null && mpg > 0) GasComparison(price, mpg) else null
        },
        now = now.map { CarNow(it.car, it.name.ifBlank { it.car }, it.battery?.roundToInt(), it.limit?.roundToInt(), it.rangeMiles, it.minutesToFull, it.kw, it.session?.toDomain()) },
        months = months.map { m ->
            ChargingMonth(
                key = m.month,
                cars = m.cars.map {
                    CarMonth(
                        it.car, it.name.ifBlank { it.car }, it.homeKwh, it.homeCost, it.fastKwh, it.fastCost, it.otherKwh, it.otherCost, it.sessions, it.miles, it.usedKwh, it.unpriced,
                        it.homePricedKwh, it.fastPricedKwh, it.otherPricedKwh, it.homeBatteryKwh, it.fastBatteryKwh, it.otherBatteryKwh, it.fastIdle,
                    )
                },
                cardFast = m.cardFast,
                unmatched = m.unmatched,
                electricPaid = m.electricPaid,
                peakKwh = m.peakKwh,
                planCosts = m.plans,
                unpriced = m.unpriced,
                // Keys sort as the months do. Home charging heard says so by itself, whatever else was or wasn't said.
                homeRecorded = recordingFrom?.let { m.month >= it } == true || m.cars.any { it.homeKwh > 0 },
            )
        },
        byHour = byHour,
        recentPeakShare = recentPeakShare,
        sessions = sessions.map { it.toDomain() },
        moreSessions = moreSessions,
        firstSessionEpochSeconds = firstSession?.roundToLong(),
        setup = ChargingSetup(setup.rates, setup.powerStream, setup.receipts),
    )
}

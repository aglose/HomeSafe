package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.Signal
import com.meticulouscreations.homesafe.finance.domain.StressScore
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.narrator_briefing_borrowing_cheap
import homesafe.shared.generated.resources.narrator_briefing_borrowing_cheap_fed
import homesafe.shared.generated.resources.narrator_briefing_borrowing_expensive
import homesafe.shared.generated.resources.narrator_briefing_borrowing_expensive_fed
import homesafe.shared.generated.resources.narrator_briefing_borrowing_moderate
import homesafe.shared.generated.resources.narrator_briefing_borrowing_moderate_fed
import homesafe.shared.generated.resources.narrator_briefing_debt
import homesafe.shared.generated.resources.narrator_briefing_debt_strain
import homesafe.shared.generated.resources.narrator_briefing_jobs_cooling
import homesafe.shared.generated.resources.narrator_briefing_jobs_plentiful
import homesafe.shared.generated.resources.narrator_briefing_jobs_plentiful_rate
import homesafe.shared.generated.resources.narrator_briefing_jobs_weakening
import homesafe.shared.generated.resources.narrator_briefing_markets_calm
import homesafe.shared.generated.resources.narrator_briefing_markets_calm_down
import homesafe.shared.generated.resources.narrator_briefing_markets_calm_up
import homesafe.shared.generated.resources.narrator_briefing_markets_jittery
import homesafe.shared.generated.resources.narrator_briefing_markets_jittery_down
import homesafe.shared.generated.resources.narrator_briefing_markets_jittery_up
import homesafe.shared.generated.resources.narrator_briefing_prices_bit_fast
import homesafe.shared.generated.resources.narrator_briefing_prices_falling
import homesafe.shared.generated.resources.narrator_briefing_prices_fast
import homesafe.shared.generated.resources.narrator_briefing_prices_normal
import homesafe.shared.generated.resources.narrator_briefing_recession_both
import homesafe.shared.generated.resources.narrator_briefing_recession_curve
import homesafe.shared.generated.resources.narrator_briefing_recession_jobs
import homesafe.shared.generated.resources.narrator_briefing_recession_quiet
import homesafe.shared.generated.resources.narrator_count_warning_signs
import homesafe.shared.generated.resources.narrator_for_you_home_less
import homesafe.shared.generated.resources.narrator_for_you_home_more
import homesafe.shared.generated.resources.narrator_for_you_mortgage_general
import homesafe.shared.generated.resources.narrator_for_you_mortgage_less
import homesafe.shared.generated.resources.narrator_for_you_mortgage_more
import homesafe.shared.generated.resources.narrator_for_you_mortgage_same
import homesafe.shared.generated.resources.narrator_for_you_prices_falling
import homesafe.shared.generated.resources.narrator_for_you_prices_falling_spend
import homesafe.shared.generated.resources.narrator_for_you_prices_rising
import homesafe.shared.generated.resources.narrator_for_you_prices_rising_spend
import homesafe.shared.generated.resources.narrator_for_you_runway_ok
import homesafe.shared.generated.resources.narrator_for_you_runway_short
import homesafe.shared.generated.resources.narrator_for_you_runway_solid
import homesafe.shared.generated.resources.narrator_for_you_savings_beats_inflation
import homesafe.shared.generated.resources.narrator_for_you_savings_general
import homesafe.shared.generated.resources.narrator_for_you_savings_trails_inflation
import homesafe.shared.generated.resources.narrator_for_you_stocks_general
import homesafe.shared.generated.resources.narrator_for_you_stocks_mine
import homesafe.shared.generated.resources.narrator_headline_gathering
import homesafe.shared.generated.resources.narrator_headline_mixed
import homesafe.shared.generated.resources.narrator_headline_mostly_cloudy
import homesafe.shared.generated.resources.narrator_headline_mostly_sunny
import homesafe.shared.generated.resources.narrator_headline_partly_cloudy
import homesafe.shared.generated.resources.narrator_headline_stormy
import homesafe.shared.generated.resources.narrator_quote_down_big
import homesafe.shared.generated.resources.narrator_quote_down_ordinary
import homesafe.shared.generated.resources.narrator_quote_down_quiet
import homesafe.shared.generated.resources.narrator_quote_down_very_big
import homesafe.shared.generated.resources.narrator_quote_up_big
import homesafe.shared.generated.resources.narrator_quote_up_ordinary
import homesafe.shared.generated.resources.narrator_quote_up_quiet
import homesafe.shared.generated.resources.narrator_quote_up_very_big
import homesafe.shared.generated.resources.narrator_summary_red
import homesafe.shared.generated.resources.narrator_summary_stress_calm
import homesafe.shared.generated.resources.narrator_summary_stress_elevated
import homesafe.shared.generated.resources.narrator_summary_stress_high
import homesafe.shared.generated.resources.narrator_summary_stress_severe
import homesafe.shared.generated.resources.narrator_topic_borrowing
import homesafe.shared.generated.resources.narrator_topic_debt
import homesafe.shared.generated.resources.narrator_topic_jobs
import homesafe.shared.generated.resources.narrator_topic_markets
import homesafe.shared.generated.resources.narrator_topic_prices
import homesafe.shared.generated.resources.narrator_topic_recession
import homesafe.shared.generated.resources.narrator_trend_climbing_worse
import homesafe.shared.generated.resources.narrator_trend_easing_better
import homesafe.shared.generated.resources.narrator_trend_falling
import homesafe.shared.generated.resources.narrator_trend_improving
import homesafe.shared.generated.resources.narrator_trend_rising
import homesafe.shared.generated.resources.narrator_trend_sliding_worse
import homesafe.shared.generated.resources.narrator_trend_steady
import homesafe.shared.generated.resources.narrator_two_sentences
import homesafe.shared.generated.resources.narrator_verdict_ccdelinq_falling_behind
import homesafe.shared.generated.resources.narrator_verdict_ccdelinq_keeping_up
import homesafe.shared.generated.resources.narrator_verdict_corepce_falling
import homesafe.shared.generated.resources.narrator_verdict_corepce_rising
import homesafe.shared.generated.resources.narrator_verdict_cpi_above_goal
import homesafe.shared.generated.resources.narrator_verdict_cpi_falling
import homesafe.shared.generated.resources.narrator_verdict_cpi_far_above_goal
import homesafe.shared.generated.resources.narrator_verdict_cpi_on_goal
import homesafe.shared.generated.resources.narrator_verdict_cpi_well_above_goal
import homesafe.shared.generated.resources.narrator_verdict_curve_inverted
import homesafe.shared.generated.resources.narrator_verdict_curve_normal
import homesafe.shared.generated.resources.narrator_verdict_debtgdp
import homesafe.shared.generated.resources.narrator_verdict_dff
import homesafe.shared.generated.resources.narrator_verdict_dgs10
import homesafe.shared.generated.resources.narrator_verdict_dgs2
import homesafe.shared.generated.resources.narrator_verdict_dgs30
import homesafe.shared.generated.resources.narrator_verdict_gdp_healthy
import homesafe.shared.generated.resources.narrator_verdict_gdp_shrank
import homesafe.shared.generated.resources.narrator_verdict_gdp_sluggish
import homesafe.shared.generated.resources.narrator_verdict_homeprices_down
import homesafe.shared.generated.resources.narrator_verdict_homeprices_up
import homesafe.shared.generated.resources.narrator_verdict_hy_nervous
import homesafe.shared.generated.resources.narrator_verdict_hy_relaxed
import homesafe.shared.generated.resources.narrator_verdict_hy_scared
import homesafe.shared.generated.resources.narrator_verdict_icsa_high
import homesafe.shared.generated.resources.narrator_verdict_icsa_low
import homesafe.shared.generated.resources.narrator_verdict_icsa_rising
import homesafe.shared.generated.resources.narrator_verdict_interest
import homesafe.shared.generated.resources.narrator_verdict_m2_growing
import homesafe.shared.generated.resources.narrator_verdict_m2_shrinking
import homesafe.shared.generated.resources.narrator_verdict_mortgage
import homesafe.shared.generated.resources.narrator_verdict_other
import homesafe.shared.generated.resources.narrator_verdict_sahm_quiet
import homesafe.shared.generated.resources.narrator_verdict_sahm_ringing
import homesafe.shared.generated.resources.narrator_verdict_stlfsi_calm
import homesafe.shared.generated.resources.narrator_verdict_stlfsi_strained
import homesafe.shared.generated.resources.narrator_verdict_umcsent_below
import homesafe.shared.generated.resources.narrator_verdict_umcsent_gloomy
import homesafe.shared.generated.resources.narrator_verdict_umcsent_upbeat
import homesafe.shared.generated.resources.narrator_verdict_unrate_softening
import homesafe.shared.generated.resources.narrator_verdict_unrate_solid
import homesafe.shared.generated.resources.narrator_verdict_unrate_weak
import homesafe.shared.generated.resources.narrator_verdict_vix_calm
import homesafe.shared.generated.resources.narrator_verdict_vix_choppy
import homesafe.shared.generated.resources.narrator_verdict_vix_wild
import homesafe.shared.generated.resources.narrator_verdict_waiting
import homesafe.shared.generated.resources.narrator_weather_cloudy
import homesafe.shared.generated.resources.narrator_weather_partly_cloudy
import homesafe.shared.generated.resources.narrator_weather_stormy
import homesafe.shared.generated.resources.narrator_weather_sunny
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.math.roundToLong

/** Which way a reading has been heading. */
enum class Trend { RISING, FALLING, STEADY }

/** The briefing's weather: how a part of the economy feels right now, at a glance. */
enum class Weather(val label: StringResource) {
    SUNNY(Res.string.narrator_weather_sunny),
    PARTLY_CLOUDY(Res.string.narrator_weather_partly_cloudy),
    CLOUDY(Res.string.narrator_weather_cloudy),
    STORMY(Res.string.narrator_weather_stormy),
    ;

    companion object {
        fun of(signal: Signal?): Weather = when (signal) {
            Signal.CALM -> SUNNY
            Signal.WATCH -> CLOUDY
            Signal.DANGER -> STORMY
            null -> PARTLY_CLOUDY
        }
    }
}

/** One line of the economic weather report. [explainerId] is what its ⓘ opens. */
@Immutable
data class BriefingItem(val topic: StringResource, val weather: Weather, val sentence: UiText, val explainerId: String)

/** The report: a [headline] sky, a [summary] (null while there's nothing to sum up) and its lines. */
@Immutable
data class Briefing(val headline: StringResource, val summary: UiText?, val items: List<BriefingItem>)

/**
 * Picks the one of four that matches the band [score] falls in on the stress gauge, the same
 * bands [StressScore.label] names, for sentences that say the band in the middle.
 */
internal fun <T> byStressBand(score: Double, calm: T, elevated: T, high: T, severe: T): T = when {
    score >= 70 -> severe
    score >= 50 -> high
    score >= 30 -> elevated
    else -> calm
}

/**
 * Turns readings into sentences a person who never reads the business pages can follow: a
 * one-line verdict for each indicator, which way it's heading, what it means for this household's
 * own money (when the budget sheet is in), and a short weather report on the whole economy.
 *
 * Every sentence is built from the live numbers; nothing here predicts anything. Each is a whole
 * sentence from the strings file with the numbers, already formatted, as its arguments, so a
 * translation can put them where its own word order wants them.
 */
object Narrator {

    /** Which way [series] has moved over roughly [months], ignoring wiggles smaller than [noise]. */
    fun trend(series: Series, months: Int = 6, noise: Double): Trend {
        val t = series.lastTime ?: return Trend.STEADY
        val now = series.lastValue ?: return Trend.STEADY
        val then = series.valueAtOrBefore(t - months * 30L * Series.DAY_SECONDS) ?: return Trend.STEADY
        return when {
            now - then > noise -> Trend.RISING
            then - now > noise -> Trend.FALLING
            else -> Trend.STEADY
        }
    }

    private fun trendOf(r: IndicatorReading): Trend {
        val th = r.indicator.thresholds
        val noise = if (th != null) abs(th.danger - th.watch) * 0.2 else (abs(r.latest ?: 1.0) * 0.05).coerceAtLeast(0.05)
        return trend(r.history, noise = noise)
    }

    private fun pct(v: Double, decimals: Int = 1) = FinanceFormat.percent(v, decimals)

    /** "It's been climbing" / "It's been easing" — said in terms of whether that's good. */
    private fun heading(r: IndicatorReading): StringResource {
        val worseUp = r.indicator.thresholds?.higherIsWorse
        return when (trendOf(r)) {
            Trend.STEADY -> Res.string.narrator_trend_steady

            Trend.RISING -> when (worseUp) {
                false -> Res.string.narrator_trend_improving
                true -> Res.string.narrator_trend_climbing_worse
                null -> Res.string.narrator_trend_rising
            }

            Trend.FALLING -> when (worseUp) {
                true -> Res.string.narrator_trend_easing_better
                false -> Res.string.narrator_trend_sliding_worse
                null -> Res.string.narrator_trend_falling
            }
        }
    }

    /** One plain sentence on what [r] says right now. */
    fun verdict(r: IndicatorReading): UiText {
        val v = r.latest ?: return UiText.of(Res.string.narrator_verdict_waiting)
        return when (r.indicator.id) {
            "cpi", "corecpi" -> when {
                // Deflation is said as prices lower, never "−0.5% higher".
                v < 0 -> UiText.of(Res.string.narrator_verdict_cpi_falling, pct(abs(v)))

                v <= 2.2 -> UiText.of(Res.string.narrator_verdict_cpi_on_goal, pct(v))

                v <= 3.5 -> UiText.of(Res.string.narrator_verdict_cpi_above_goal, pct(v))

                v <= 5 -> UiText.of(Res.string.narrator_verdict_cpi_well_above_goal, pct(v))

                else -> UiText.of(Res.string.narrator_verdict_cpi_far_above_goal, pct(v))
            }

            "corepce" -> if (v >= 0) {
                UiText.of(Res.string.narrator_verdict_corepce_rising, pct(v))
            } else {
                UiText.of(Res.string.narrator_verdict_corepce_falling, pct(abs(v)))
            }

            "unrate" -> UiText.of(
                when {
                    v < 4.5 -> Res.string.narrator_verdict_unrate_solid
                    v < 5.5 -> Res.string.narrator_verdict_unrate_softening
                    else -> Res.string.narrator_verdict_unrate_weak
                },
                FinanceFormat.grouped(v, 1),
            )

            "sahm" -> if (v >= 0.5) {
                UiText.of(Res.string.narrator_verdict_sahm_ringing, FinanceFormat.grouped(v, 2))
            } else {
                UiText.of(Res.string.narrator_verdict_sahm_quiet, FinanceFormat.grouped(v.coerceAtLeast(0.0), 2))
            }

            // Claims arrive in thousands; said as a whole number of people, rounded to the thousand.
            "icsa" -> UiText.of(
                when {
                    v < 250 -> Res.string.narrator_verdict_icsa_low
                    v < 300 -> Res.string.narrator_verdict_icsa_rising
                    else -> Res.string.narrator_verdict_icsa_high
                },
                FinanceFormat.grouped(v.roundToLong() * 1000.0, 0),
            )

            "gdp" -> when {
                v < 0 -> UiText.of(Res.string.narrator_verdict_gdp_shrank, pct(abs(v)))
                v >= 2 -> UiText.of(Res.string.narrator_verdict_gdp_healthy, pct(v))
                else -> UiText.of(Res.string.narrator_verdict_gdp_sluggish, pct(v))
            }

            "umcsent" -> UiText.of(
                when {
                    v < 60 -> Res.string.narrator_verdict_umcsent_gloomy
                    v < 80 -> Res.string.narrator_verdict_umcsent_below
                    else -> Res.string.narrator_verdict_umcsent_upbeat
                },
                FinanceFormat.grouped(v, 0),
            )

            "t10y2y", "t10y3m" -> if (v < 0) {
                UiText.of(Res.string.narrator_verdict_curve_inverted, FinanceFormat.grouped(abs(v), 2))
            } else {
                UiText.of(Res.string.narrator_verdict_curve_normal, FinanceFormat.grouped(v, 2))
            }

            "hy" -> UiText.of(
                when {
                    v < 4.5 -> Res.string.narrator_verdict_hy_relaxed
                    v < 6 -> Res.string.narrator_verdict_hy_nervous
                    else -> Res.string.narrator_verdict_hy_scared
                },
                FinanceFormat.grouped(v, 1),
            )

            "stlfsi" -> UiText.of(
                if (v <= 0) Res.string.narrator_verdict_stlfsi_calm else Res.string.narrator_verdict_stlfsi_strained,
                FinanceFormat.grouped(v, 2),
            )

            "vix" -> UiText.of(
                when {
                    v < 20 -> Res.string.narrator_verdict_vix_calm
                    v < 30 -> Res.string.narrator_verdict_vix_choppy
                    else -> Res.string.narrator_verdict_vix_wild
                },
                FinanceFormat.grouped(v, 0),
            )

            "ccdelinq" -> UiText.of(
                if (v < 3.5) Res.string.narrator_verdict_ccdelinq_keeping_up else Res.string.narrator_verdict_ccdelinq_falling_behind,
                pct(v),
            )

            "debtgdp" -> UiText.of(Res.string.narrator_verdict_debtgdp, FinanceFormat.grouped(v, 0))

            "interest" -> UiText.of(Res.string.narrator_verdict_interest, FinanceFormat.grouped(v, 0))

            "m2" -> if (v >= 0) {
                UiText.of(Res.string.narrator_verdict_m2_growing, pct(v))
            } else {
                UiText.of(Res.string.narrator_verdict_m2_shrinking, pct(abs(v)))
            }

            "dff" -> UiText.of(Res.string.narrator_verdict_dff, pct(v, 2))

            "mortgage" -> UiText.of(Res.string.narrator_verdict_mortgage, pct(v, 2), FinanceFormat.money(monthlyPayment(500_000.0, v / 100, 30), 0))

            "homeprices" -> if (v >= 0) {
                UiText.of(Res.string.narrator_verdict_homeprices_up, pct(v))
            } else {
                UiText.of(Res.string.narrator_verdict_homeprices_down, pct(abs(v)))
            }

            "dgs10" -> UiText.of(Res.string.narrator_verdict_dgs10, pct(v, 2))

            "dgs2" -> UiText.of(Res.string.narrator_verdict_dgs2, pct(v, 2))

            "dgs30" -> UiText.of(Res.string.narrator_verdict_dgs30, pct(v, 2))

            else -> UiText.of(Res.string.narrator_verdict_other, FinanceFormat.indicator(v, r.indicator.unit))
        }
    }

    /** The verdict with which way it's heading, for an explainer's "Right now". */
    fun rightNow(r: IndicatorReading): UiText = UiText.of(Res.string.narrator_two_sentences, verdict(r), UiText.of(heading(r)))

    /** What [id]'s reading means for this household, in dollars where the sheet allows; null when there's nothing useful to say. */
    fun forYou(id: String, readings: Map<String, IndicatorReading>, quotes: Map<String, Quote>, finance: PersonalFinance?): UiText? {
        val r = readings[id]?.latest
        return when (id) {
            "cpi", "corecpi", "corepce" -> r?.let { inflation ->
                val spend = finance?.monthlyExpenses
                when {
                    inflation < 0 && spend != null -> {
                        val less = spend * abs(inflation) / (100 + inflation)
                        UiText.of(Res.string.narrator_for_you_prices_falling_spend, FinanceFormat.money(spend, 0), FinanceFormat.money(less, 0))
                    }

                    inflation < 0 -> UiText.of(Res.string.narrator_for_you_prices_falling, FinanceFormat.money(100 + inflation, 2))

                    spend != null -> {
                        val more = spend * inflation / (100 + inflation)
                        UiText.of(Res.string.narrator_for_you_prices_rising_spend, FinanceFormat.money(spend, 0), FinanceFormat.money(more, 0), pct(inflation))
                    }

                    else -> UiText.of(Res.string.narrator_for_you_prices_rising, FinanceFormat.money(100 + inflation, 2), pct(inflation))
                }
            }

            "dff", "realrate" -> {
                val ff = readings["dff"]?.latest ?: return null
                val cpi = readings["cpi"]?.latest
                val cash = finance?.liquidCash?.takeIf { it > 0 }
                val real = cpi?.let { ff - it }
                when {
                    cash != null && real != null -> UiText.of(
                        if (real >= 0) Res.string.narrator_for_you_savings_beats_inflation else Res.string.narrator_for_you_savings_trails_inflation,
                        pct(ff, 2),
                        FinanceFormat.compactMoney(cash),
                        FinanceFormat.money(cash * ff / 100, 0),
                    )

                    else -> UiText.of(Res.string.narrator_for_you_savings_general)
                }
            }

            "mortgage", "dgs10" -> {
                val rate = readings["mortgage"]?.latest ?: return null
                val plan = finance?.mortgagePlan
                if (plan != null) {
                    val loan = plan.homePrice * (1 - plan.downPaymentFraction)
                    val atMarket = monthlyPayment(loan, rate / 100, plan.termYears)
                    val atPlan = monthlyPayment(loan, plan.rate, plan.termYears)
                    val diff = atMarket - atPlan
                    val house = FinanceFormat.compactMoney(plan.homePrice)
                    val payment = FinanceFormat.money(atMarket, 0)
                    val planRate = FinanceFormat.fractionPercent(plan.rate, 2)
                    when {
                        abs(diff) < 25 -> UiText.of(Res.string.narrator_for_you_mortgage_same, house, payment, planRate)
                        diff > 0 -> UiText.of(Res.string.narrator_for_you_mortgage_more, house, payment, FinanceFormat.money(diff, 0), planRate)
                        else -> UiText.of(Res.string.narrator_for_you_mortgage_less, house, payment, FinanceFormat.money(abs(diff), 0), planRate)
                    }
                } else {
                    UiText.of(Res.string.narrator_for_you_mortgage_general)
                }
            }

            "homeprices" -> {
                val change = r ?: return null
                val value = finance?.home?.value ?: return null
                val delta = value * change / (100 + change)
                UiText.of(
                    if (delta >= 0) Res.string.narrator_for_you_home_more else Res.string.narrator_for_you_home_less,
                    FinanceFormat.money(abs(delta), 0),
                )
            }

            "unrate", "sahm", "icsa" -> finance?.runwayMonths?.let { months ->
                UiText.of(
                    when {
                        months >= 6 -> Res.string.narrator_for_you_runway_solid
                        months >= 3 -> Res.string.narrator_for_you_runway_ok
                        else -> Res.string.narrator_for_you_runway_short
                    },
                    FinanceFormat.grouped(months, 1),
                )
            }

            "sp500", "vix" -> {
                val sp = quotes[MarketCatalog.SP500.symbol] ?: return null
                val invested = finance?.accounts?.filter { it.category == AccountCategory.RETIREMENT || it.category == AccountCategory.INVESTING }?.sumOf { it.balance }?.takeIf { it > 0 }
                if (invested != null) {
                    UiText.of(Res.string.narrator_for_you_stocks_mine, FinanceFormat.compactMoney(invested), FinanceFormat.signedMoney(invested * sp.changePercent / 100, 0))
                } else {
                    UiText.of(Res.string.narrator_for_you_stocks_general, FinanceFormat.signedPercent(sp.changePercent), FinanceFormat.signedMoney(1000 * sp.changePercent, 0))
                }
            }

            else -> null
        }
    }

    /** One sentence on today's move for [symbol], with a sense of whether it's a big day. */
    fun quoteVerdict(symbol: String, quote: Quote): UiText {
        val meta = MarketCatalog.lookup(symbol)
        val p = quote.changePercent
        val up = p >= 0
        val sentence = when {
            abs(p) < 0.3 -> if (up) Res.string.narrator_quote_up_quiet else Res.string.narrator_quote_down_quiet
            abs(p) < 1 -> if (up) Res.string.narrator_quote_up_ordinary else Res.string.narrator_quote_down_ordinary
            abs(p) < 2 -> if (up) Res.string.narrator_quote_up_big else Res.string.narrator_quote_down_big
            else -> if (up) Res.string.narrator_quote_up_very_big else Res.string.narrator_quote_down_very_big
        }
        return UiText.of(sentence, meta.shortName, FinanceFormat.percent(abs(p), 2))
    }

    /**
     * The economy in a handful of lines, each a part of it with its weather: prices, jobs,
     * borrowing, markets, recession warnings and government debt. Missing readings are left out.
     */
    fun briefing(readings: Map<String, IndicatorReading>, stress: StressScore?, quotes: Map<String, Quote>): Briefing {
        val items = mutableListOf<BriefingItem>()
        readings["cpi"]?.let { r ->
            val v = r.latest ?: return@let
            items += BriefingItem(
                Res.string.narrator_topic_prices,
                Weather.of(r.signal),
                when {
                    v < 0 -> UiText.of(Res.string.narrator_briefing_prices_falling, pct(abs(v)))
                    v <= 2.5 -> UiText.of(Res.string.narrator_briefing_prices_normal, pct(v))
                    v <= 4 -> UiText.of(Res.string.narrator_briefing_prices_bit_fast, pct(v))
                    else -> UiText.of(Res.string.narrator_briefing_prices_fast, pct(v))
                },
                "cpi",
            )
        }
        val jobs = listOfNotNull(readings["unrate"], readings["sahm"], readings["icsa"])
        if (jobs.isNotEmpty()) {
            val worst = jobs.maxByOrNull { it.stress ?: 0.0 }
            val un = readings["unrate"]?.latest
            items += BriefingItem(
                Res.string.narrator_topic_jobs,
                Weather.of(worst?.signal),
                when (worst?.signal) {
                    Signal.DANGER -> UiText.of(Res.string.narrator_briefing_jobs_weakening)
                    Signal.WATCH -> UiText.of(Res.string.narrator_briefing_jobs_cooling)
                    else -> un?.let { UiText.of(Res.string.narrator_briefing_jobs_plentiful_rate, pct(it)) } ?: UiText.of(Res.string.narrator_briefing_jobs_plentiful)
                },
                "unrate",
            )
        }
        readings["mortgage"]?.let { r ->
            val v = r.latest ?: return@let
            val ff = readings["dff"]?.latest
            items += BriefingItem(
                Res.string.narrator_topic_borrowing,
                Weather.of(r.signal),
                if (ff != null) {
                    UiText.of(
                        when {
                            v >= 6.5 -> Res.string.narrator_briefing_borrowing_expensive_fed
                            v >= 5 -> Res.string.narrator_briefing_borrowing_moderate_fed
                            else -> Res.string.narrator_briefing_borrowing_cheap_fed
                        },
                        pct(v, 1),
                        pct(ff, 2),
                    )
                } else {
                    UiText.of(
                        when {
                            v >= 6.5 -> Res.string.narrator_briefing_borrowing_expensive
                            v >= 5 -> Res.string.narrator_briefing_borrowing_moderate
                            else -> Res.string.narrator_briefing_borrowing_cheap
                        },
                        pct(v, 1),
                    )
                },
                "mortgage",
            )
        }
        val vix = readings["vix"]
        val sp = quotes[MarketCatalog.SP500.symbol]
        if (vix != null || sp != null) {
            val calm = (vix?.latest ?: 15.0) < 20
            items += BriefingItem(
                Res.string.narrator_topic_markets,
                Weather.of(vix?.signal),
                if (sp == null) {
                    UiText.of(if (calm) Res.string.narrator_briefing_markets_calm else Res.string.narrator_briefing_markets_jittery)
                } else {
                    val up = sp.change >= 0
                    UiText.of(
                        when {
                            calm && up -> Res.string.narrator_briefing_markets_calm_up
                            calm -> Res.string.narrator_briefing_markets_calm_down
                            up -> Res.string.narrator_briefing_markets_jittery_up
                            else -> Res.string.narrator_briefing_markets_jittery_down
                        },
                        FinanceFormat.percent(abs(sp.changePercent), 2),
                    )
                },
                "sp500",
            )
        }
        val curve = readings["t10y2y"]
        val sahm = readings["sahm"]
        if (curve != null || sahm != null) {
            val inverted = (curve?.latest ?: 1.0) < 0
            val alarm = (sahm?.latest ?: 0.0) >= 0.5
            val signal = when {
                inverted && alarm -> Signal.DANGER
                inverted || alarm -> Signal.WATCH
                else -> Signal.CALM
            }
            items += BriefingItem(
                Res.string.narrator_topic_recession,
                Weather.of(signal),
                UiText.of(
                    when {
                        inverted && alarm -> Res.string.narrator_briefing_recession_both
                        alarm -> Res.string.narrator_briefing_recession_jobs
                        inverted -> Res.string.narrator_briefing_recession_curve
                        else -> Res.string.narrator_briefing_recession_quiet
                    },
                ),
                "recession",
            )
        }
        readings["interest"]?.let { r ->
            val v = r.latest ?: return@let
            items += BriefingItem(
                Res.string.narrator_topic_debt,
                Weather.of(r.signal),
                UiText.of(
                    if (v >= 20) Res.string.narrator_briefing_debt_strain else Res.string.narrator_briefing_debt,
                    FinanceFormat.grouped(v, 0),
                ),
                "interest",
            )
        }
        val dangers = items.count { it.weather == Weather.STORMY }
        val clouds = items.count { it.weather == Weather.CLOUDY }
        val headline = when {
            items.isEmpty() -> Res.string.narrator_headline_gathering
            dangers >= 3 -> Res.string.narrator_headline_stormy
            dangers >= 1 -> Res.string.narrator_headline_mixed
            clouds >= 3 -> Res.string.narrator_headline_mostly_cloudy
            clouds >= 1 -> Res.string.narrator_headline_partly_cloudy
            else -> Res.string.narrator_headline_mostly_sunny
        }
        val summary = when {
            items.isEmpty() -> null

            stress == null -> UiText.plural(Res.plurals.narrator_summary_red, items.size, dangers, items.size)

            else -> UiText.of(
                byStressBand(
                    stress.score,
                    calm = Res.string.narrator_summary_stress_calm,
                    elevated = Res.string.narrator_summary_stress_elevated,
                    high = Res.string.narrator_summary_stress_high,
                    severe = Res.string.narrator_summary_stress_severe,
                ),
                FinanceFormat.grouped(stress.score, 0),
                UiText.plural(Res.plurals.narrator_count_warning_signs, stress.dangers),
                stress.watches,
            )
        }
        return Briefing(headline, summary, items)
    }

    /** The plain name for an indicator, falling back to its own short title, then its id. */
    fun plainTitle(id: String): UiText =
        (Explainers.byId(id)?.title ?: IndicatorCatalog.byId(id)?.shortTitle)?.let { UiText.of(it) } ?: id.asUiText()
}

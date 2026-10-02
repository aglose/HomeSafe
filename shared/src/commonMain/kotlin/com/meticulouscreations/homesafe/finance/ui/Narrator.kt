package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.EconomyTone
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
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.narrator_briefing_borrowing_cheap
import homesafe.shared.generated.resources.narrator_briefing_borrowing_expensive
import homesafe.shared.generated.resources.narrator_briefing_borrowing_expensive_fed
import homesafe.shared.generated.resources.narrator_briefing_borrowing_moderate
import homesafe.shared.generated.resources.narrator_briefing_borrowing_straight
import homesafe.shared.generated.resources.narrator_briefing_borrowing_straight_fed
import homesafe.shared.generated.resources.narrator_briefing_debt
import homesafe.shared.generated.resources.narrator_briefing_debt_straight
import homesafe.shared.generated.resources.narrator_briefing_debt_straight_ratio
import homesafe.shared.generated.resources.narrator_briefing_debt_strain
import homesafe.shared.generated.resources.narrator_briefing_jobs_claims_straight
import homesafe.shared.generated.resources.narrator_briefing_jobs_cooling
import homesafe.shared.generated.resources.narrator_briefing_jobs_cooling_rate
import homesafe.shared.generated.resources.narrator_briefing_jobs_plentiful
import homesafe.shared.generated.resources.narrator_briefing_jobs_plentiful_rate
import homesafe.shared.generated.resources.narrator_briefing_jobs_u6_straight
import homesafe.shared.generated.resources.narrator_briefing_jobs_unrate_straight
import homesafe.shared.generated.resources.narrator_briefing_jobs_weakening
import homesafe.shared.generated.resources.narrator_briefing_markets_calm
import homesafe.shared.generated.resources.narrator_briefing_markets_calm_down
import homesafe.shared.generated.resources.narrator_briefing_markets_calm_up
import homesafe.shared.generated.resources.narrator_briefing_markets_down_straight
import homesafe.shared.generated.resources.narrator_briefing_markets_fear_straight
import homesafe.shared.generated.resources.narrator_briefing_markets_jittery
import homesafe.shared.generated.resources.narrator_briefing_markets_jittery_down
import homesafe.shared.generated.resources.narrator_briefing_markets_jittery_up
import homesafe.shared.generated.resources.narrator_briefing_markets_up_straight
import homesafe.shared.generated.resources.narrator_briefing_prices_bit_fast
import homesafe.shared.generated.resources.narrator_briefing_prices_falling
import homesafe.shared.generated.resources.narrator_briefing_prices_fast
import homesafe.shared.generated.resources.narrator_briefing_prices_higher_straight
import homesafe.shared.generated.resources.narrator_briefing_prices_lower_straight
import homesafe.shared.generated.resources.narrator_briefing_prices_normal
import homesafe.shared.generated.resources.narrator_briefing_recession_alarm_straight
import homesafe.shared.generated.resources.narrator_briefing_recession_both
import homesafe.shared.generated.resources.narrator_briefing_recession_curve
import homesafe.shared.generated.resources.narrator_briefing_recession_curve_inverted_straight
import homesafe.shared.generated.resources.narrator_briefing_recession_curve_normal_straight
import homesafe.shared.generated.resources.narrator_briefing_recession_jobs
import homesafe.shared.generated.resources.narrator_briefing_recession_quiet
import homesafe.shared.generated.resources.narrator_briefing_slack_calm_straight
import homesafe.shared.generated.resources.narrator_briefing_slack_item
import homesafe.shared.generated.resources.narrator_briefing_slack_past_straight
import homesafe.shared.generated.resources.narrator_briefing_slack_soft_spot
import homesafe.shared.generated.resources.narrator_briefing_slack_soft_spots
import homesafe.shared.generated.resources.narrator_briefing_slack_sound
import homesafe.shared.generated.resources.narrator_bright_ccdelinq
import homesafe.shared.generated.resources.narrator_bright_civpart
import homesafe.shared.generated.resources.narrator_bright_curve_inverted
import homesafe.shared.generated.resources.narrator_bright_curve_normal
import homesafe.shared.generated.resources.narrator_bright_debt
import homesafe.shared.generated.resources.narrator_bright_fed_rate
import homesafe.shared.generated.resources.narrator_bright_gdp_growing
import homesafe.shared.generated.resources.narrator_bright_gdp_recession
import homesafe.shared.generated.resources.narrator_bright_hiring_options
import homesafe.shared.generated.resources.narrator_bright_hiring_slower
import homesafe.shared.generated.resources.narrator_bright_homeprices_down
import homesafe.shared.generated.resources.narrator_bright_homeprices_up
import homesafe.shared.generated.resources.narrator_bright_jobs_cooling
import homesafe.shared.generated.resources.narrator_bright_jobs_tight
import homesafe.shared.generated.resources.narrator_bright_m2_growing
import homesafe.shared.generated.resources.narrator_bright_m2_shrinking
import homesafe.shared.generated.resources.narrator_bright_markets_calm
import homesafe.shared.generated.resources.narrator_bright_markets_panic
import homesafe.shared.generated.resources.narrator_bright_prices_falling
import homesafe.shared.generated.resources.narrator_bright_prices_hot
import homesafe.shared.generated.resources.narrator_bright_prices_near_goal
import homesafe.shared.generated.resources.narrator_bright_rates_high
import homesafe.shared.generated.resources.narrator_bright_rates_reasonable
import homesafe.shared.generated.resources.narrator_bright_realwages_down
import homesafe.shared.generated.resources.narrator_bright_realwages_up
import homesafe.shared.generated.resources.narrator_bright_temphelp_down
import homesafe.shared.generated.resources.narrator_bright_temphelp_up
import homesafe.shared.generated.resources.narrator_bright_umcsent
import homesafe.shared.generated.resources.narrator_change_points
import homesafe.shared.generated.resources.narrator_fact_ccdelinq
import homesafe.shared.generated.resources.narrator_fact_civpart
import homesafe.shared.generated.resources.narrator_fact_corecpi_higher
import homesafe.shared.generated.resources.narrator_fact_corecpi_lower
import homesafe.shared.generated.resources.narrator_fact_cpi_higher
import homesafe.shared.generated.resources.narrator_fact_cpi_lower
import homesafe.shared.generated.resources.narrator_fact_curve_inverted
import homesafe.shared.generated.resources.narrator_fact_curve_normal
import homesafe.shared.generated.resources.narrator_fact_gdp_grew
import homesafe.shared.generated.resources.narrator_fact_hy
import homesafe.shared.generated.resources.narrator_fact_icsa
import homesafe.shared.generated.resources.narrator_fact_insured
import homesafe.shared.generated.resources.narrator_fact_longterm
import homesafe.shared.generated.resources.narrator_fact_openings
import homesafe.shared.generated.resources.narrator_fact_primeepop
import homesafe.shared.generated.resources.narrator_fact_quits
import homesafe.shared.generated.resources.narrator_fact_realwages_less
import homesafe.shared.generated.resources.narrator_fact_realwages_more
import homesafe.shared.generated.resources.narrator_fact_sahm
import homesafe.shared.generated.resources.narrator_fact_slackgap
import homesafe.shared.generated.resources.narrator_fact_stlfsi
import homesafe.shared.generated.resources.narrator_fact_temphelp_fewer
import homesafe.shared.generated.resources.narrator_fact_temphelp_more
import homesafe.shared.generated.resources.narrator_fact_u6
import homesafe.shared.generated.resources.narrator_fact_umcsent
import homesafe.shared.generated.resources.narrator_fact_unrate
import homesafe.shared.generated.resources.narrator_fact_vix
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
import homesafe.shared.generated.resources.narrator_format_points_change
import homesafe.shared.generated.resources.narrator_format_unchanged
import homesafe.shared.generated.resources.narrator_headline_gathering
import homesafe.shared.generated.resources.narrator_headline_mixed
import homesafe.shared.generated.resources.narrator_headline_mostly_cloudy
import homesafe.shared.generated.resources.narrator_headline_mostly_sunny
import homesafe.shared.generated.resources.narrator_headline_partly_cloudy
import homesafe.shared.generated.resources.narrator_headline_stormy
import homesafe.shared.generated.resources.narrator_headline_straight
import homesafe.shared.generated.resources.narrator_judgement_calm
import homesafe.shared.generated.resources.narrator_judgement_ccdelinq
import homesafe.shared.generated.resources.narrator_judgement_cpi_above_goal
import homesafe.shared.generated.resources.narrator_judgement_cpi_falling
import homesafe.shared.generated.resources.narrator_judgement_cpi_far_above_goal
import homesafe.shared.generated.resources.narrator_judgement_cpi_on_goal
import homesafe.shared.generated.resources.narrator_judgement_cpi_well_above_goal
import homesafe.shared.generated.resources.narrator_judgement_curve_inverted
import homesafe.shared.generated.resources.narrator_judgement_curve_normal
import homesafe.shared.generated.resources.narrator_judgement_danger
import homesafe.shared.generated.resources.narrator_judgement_gdp_healthy
import homesafe.shared.generated.resources.narrator_judgement_gdp_shrinking
import homesafe.shared.generated.resources.narrator_judgement_gdp_slow
import homesafe.shared.generated.resources.narrator_judgement_icsa_high
import homesafe.shared.generated.resources.narrator_judgement_icsa_low
import homesafe.shared.generated.resources.narrator_judgement_icsa_rising
import homesafe.shared.generated.resources.narrator_judgement_realwages_raise
import homesafe.shared.generated.resources.narrator_judgement_realwages_trailing
import homesafe.shared.generated.resources.narrator_judgement_sahm_quiet
import homesafe.shared.generated.resources.narrator_judgement_sahm_ringing
import homesafe.shared.generated.resources.narrator_judgement_umcsent_below
import homesafe.shared.generated.resources.narrator_judgement_umcsent_gloomy
import homesafe.shared.generated.resources.narrator_judgement_umcsent_upbeat
import homesafe.shared.generated.resources.narrator_judgement_unrate_cooling
import homesafe.shared.generated.resources.narrator_judgement_unrate_harder
import homesafe.shared.generated.resources.narrator_judgement_unrate_strong
import homesafe.shared.generated.resources.narrator_judgement_watch
import homesafe.shared.generated.resources.narrator_perspective_bright_side
import homesafe.shared.generated.resources.narrator_perspective_record
import homesafe.shared.generated.resources.narrator_quote_down_big
import homesafe.shared.generated.resources.narrator_quote_down_ordinary
import homesafe.shared.generated.resources.narrator_quote_down_quiet
import homesafe.shared.generated.resources.narrator_quote_down_very_big
import homesafe.shared.generated.resources.narrator_quote_up_big
import homesafe.shared.generated.resources.narrator_quote_up_ordinary
import homesafe.shared.generated.resources.narrator_quote_up_quiet
import homesafe.shared.generated.resources.narrator_quote_up_very_big
import homesafe.shared.generated.resources.narrator_record_better_than
import homesafe.shared.generated.resources.narrator_record_higher_than
import homesafe.shared.generated.resources.narrator_record_highest_ever
import homesafe.shared.generated.resources.narrator_record_highest_since
import homesafe.shared.generated.resources.narrator_record_lower_than
import homesafe.shared.generated.resources.narrator_record_lowest_ever
import homesafe.shared.generated.resources.narrator_record_lowest_since
import homesafe.shared.generated.resources.narrator_record_worse_than
import homesafe.shared.generated.resources.narrator_record_year_ago
import homesafe.shared.generated.resources.narrator_standing_calm
import homesafe.shared.generated.resources.narrator_standing_danger
import homesafe.shared.generated.resources.narrator_standing_watch
import homesafe.shared.generated.resources.narrator_summary_bright_calm
import homesafe.shared.generated.resources.narrator_summary_bright_elevated
import homesafe.shared.generated.resources.narrator_summary_bright_high
import homesafe.shared.generated.resources.narrator_summary_bright_severe
import homesafe.shared.generated.resources.narrator_summary_calm_areas
import homesafe.shared.generated.resources.narrator_summary_straight_calm
import homesafe.shared.generated.resources.narrator_summary_straight_dangers
import homesafe.shared.generated.resources.narrator_summary_straight_elevated
import homesafe.shared.generated.resources.narrator_summary_straight_high
import homesafe.shared.generated.resources.narrator_summary_straight_severe
import homesafe.shared.generated.resources.narrator_topic_borrowing
import homesafe.shared.generated.resources.narrator_topic_debt
import homesafe.shared.generated.resources.narrator_topic_jobs
import homesafe.shared.generated.resources.narrator_topic_markets
import homesafe.shared.generated.resources.narrator_topic_prices
import homesafe.shared.generated.resources.narrator_topic_recession
import homesafe.shared.generated.resources.narrator_topic_slack
import homesafe.shared.generated.resources.narrator_trend_climbing_worse
import homesafe.shared.generated.resources.narrator_trend_easing_better
import homesafe.shared.generated.resources.narrator_trend_falling
import homesafe.shared.generated.resources.narrator_trend_improving
import homesafe.shared.generated.resources.narrator_trend_rising
import homesafe.shared.generated.resources.narrator_trend_sliding_worse
import homesafe.shared.generated.resources.narrator_trend_steady
import homesafe.shared.generated.resources.narrator_trend_straight_away
import homesafe.shared.generated.resources.narrator_trend_straight_back_toward
import homesafe.shared.generated.resources.narrator_trend_straight_further_in
import homesafe.shared.generated.resources.narrator_trend_straight_little_changed
import homesafe.shared.generated.resources.narrator_trend_straight_moved
import homesafe.shared.generated.resources.narrator_trend_straight_toward
import homesafe.shared.generated.resources.narrator_trend_straight_unchanged
import homesafe.shared.generated.resources.narrator_two_sentences
import homesafe.shared.generated.resources.narrator_verdict_corepce_falling
import homesafe.shared.generated.resources.narrator_verdict_corepce_rising
import homesafe.shared.generated.resources.narrator_verdict_debtgdp
import homesafe.shared.generated.resources.narrator_verdict_dff
import homesafe.shared.generated.resources.narrator_verdict_dgs10
import homesafe.shared.generated.resources.narrator_verdict_dgs2
import homesafe.shared.generated.resources.narrator_verdict_dgs30
import homesafe.shared.generated.resources.narrator_verdict_gdp_shrank
import homesafe.shared.generated.resources.narrator_verdict_homeprices_down
import homesafe.shared.generated.resources.narrator_verdict_homeprices_up
import homesafe.shared.generated.resources.narrator_verdict_interest
import homesafe.shared.generated.resources.narrator_verdict_m2_growing
import homesafe.shared.generated.resources.narrator_verdict_m2_shrinking
import homesafe.shared.generated.resources.narrator_verdict_mortgage
import homesafe.shared.generated.resources.narrator_verdict_other
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

/**
 * One line of the economic report: a part of the economy, the worst [signal] among the readings it
 * sums up, and a sentence. [explainerId] is what its ⓘ opens.
 */
@Immutable
data class BriefingItem(val topic: StringResource, val signal: Signal?, val sentence: UiText, val explainerId: String) {
    val weather: Weather get() = Weather.of(signal)
}

/** The report in [tone]: a [headline], a [summary] (null while there's nothing to sum up) and its lines. */
@Immutable
data class Briefing(val tone: EconomyTone, val headline: UiText, val summary: UiText?, val items: List<BriefingItem>)

/** A heading and a paragraph that put a reading in context, in the chosen tone. */
@Immutable
data class Perspective(val heading: StringResource, val body: UiText)

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
 * own money (when the budget sheet is in), and a short report on the whole economy.
 *
 * Every sentence is built from the live numbers; nothing here predicts anything. What's said
 * about them follows the [EconomyTone]: [EconomyTone.STRAIGHT] states each reading, where it sits
 * against its watch and danger lines and its own history, and nothing it can't back with a
 * number; [EconomyTone.BRIGHT_SIDE] says the same reading kindly and adds the upsides it brings.
 *
 * Each sentence is a whole sentence from the strings file with the numbers, already formatted, as
 * its arguments, so a translation can put them where its own word order wants them; where several
 * are said together they are whole sentences one after another, never clauses glued together.
 */
object Narrator {

    private val UNCHANGED: UiText = UiText.of(Res.string.narrator_format_unchanged)

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

    private fun noiseOf(r: IndicatorReading): Double {
        val th = r.indicator.thresholds
        return if (th != null) abs(th.danger - th.watch) * 0.2 else (abs(r.latest ?: 1.0) * 0.05).coerceAtLeast(0.05)
    }

    private fun trendOf(r: IndicatorReading): Trend = trend(r.history, noise = noiseOf(r))

    private fun pct(v: Double, decimals: Int = 1) = FinanceFormat.percent(v, decimals)

    private fun value(r: IndicatorReading, v: Double) = FinanceFormat.indicator(v, r.indicator.unit)

    /** Whole sentences, one after another. */
    private fun sentences(parts: List<UiText>): UiText = parts.reduce { a, b -> UiText.of(Res.string.narrator_two_sentences, a, b) }

    /** A change in the reading's own units, written out for a sentence: "+0.40 points" for a rate, "+40K" for a count. */
    private fun change(r: IndicatorReading, delta: Double): UiText {
        val short = FinanceFormat.indicatorChange(delta, r.indicator.unit)
        return if (short is UiText.Resource && short.res == Res.string.narrator_format_points_change) UiText.Resource(Res.string.narrator_change_points, short.args) else short
    }

    /**
     * Which way it's been heading; null when there's no reading from six months back to compare.
     * Straight talk gives the six-month change and whether it's toward or away from the danger
     * line; the bright side says it in words, kindly.
     */
    private fun heading(r: IndicatorReading, tone: EconomyTone): UiText? {
        val t = trendOf(r)
        val worseUp = r.indicator.thresholds?.higherIsWorse
        if (tone == EconomyTone.STRAIGHT) {
            val time = r.history.lastTime ?: return null
            val now = r.latest ?: return null
            val then = r.history.valueAtOrBefore(time - 6 * 30L * Series.DAY_SECONDS) ?: return null
            val delta = now - then
            val worsening = worseUp != null && (delta > 0) == worseUp
            val moved = change(r, delta)
            return when {
                moved == UNCHANGED -> UiText.of(Res.string.narrator_trend_straight_unchanged)

                t == Trend.STEADY -> UiText.of(Res.string.narrator_trend_straight_little_changed, moved)

                worseUp == null -> UiText.of(Res.string.narrator_trend_straight_moved, moved)

                // Already past the line, a worsening move goes further from it, not toward it.
                r.signal == Signal.DANGER -> UiText.of(
                    if (worsening) Res.string.narrator_trend_straight_further_in else Res.string.narrator_trend_straight_back_toward,
                    moved,
                )

                worsening -> UiText.of(Res.string.narrator_trend_straight_toward, moved)

                else -> UiText.of(Res.string.narrator_trend_straight_away, moved)
            }
        }
        return UiText.of(
            when (t) {
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
            },
        )
    }

    /** What the reading is, in plain words and with no judgement on it. */
    fun fact(r: IndicatorReading): UiText {
        val v = r.latest ?: return UiText.of(Res.string.narrator_verdict_waiting)
        return when (r.indicator.id) {
            // Deflation is said as prices lower, never "−0.5% higher".
            "cpi" -> if (v >= 0) UiText.of(Res.string.narrator_fact_cpi_higher, pct(v)) else UiText.of(Res.string.narrator_fact_cpi_lower, pct(abs(v)))

            "corecpi" -> if (v >= 0) UiText.of(Res.string.narrator_fact_corecpi_higher, pct(v)) else UiText.of(Res.string.narrator_fact_corecpi_lower, pct(abs(v)))

            "corepce" -> if (v >= 0) {
                UiText.of(Res.string.narrator_verdict_corepce_rising, pct(v))
            } else {
                UiText.of(Res.string.narrator_verdict_corepce_falling, pct(abs(v)))
            }

            "unrate" -> UiText.of(Res.string.narrator_fact_unrate, FinanceFormat.grouped(v, 1))

            "sahm" -> UiText.of(Res.string.narrator_fact_sahm, FinanceFormat.grouped(v.coerceAtLeast(0.0), 2))

            // Claims arrive in thousands; said as a whole number of people, rounded to the thousand.
            "icsa" -> UiText.of(Res.string.narrator_fact_icsa, FinanceFormat.grouped(v.roundToLong() * 1000.0, 0))

            "u6" -> UiText.of(Res.string.narrator_fact_u6, pct(v))

            "slackgap" -> UiText.of(Res.string.narrator_fact_slackgap, FinanceFormat.grouped(v, 1))

            "primeepop" -> UiText.of(Res.string.narrator_fact_primeepop, FinanceFormat.grouped(v, 1))

            "longterm" -> UiText.of(Res.string.narrator_fact_longterm, pct(v))

            "insured" -> UiText.of(Res.string.narrator_fact_insured, pct(v))

            "quits" -> UiText.of(Res.string.narrator_fact_quits, pct(v))

            "openings" -> UiText.of(Res.string.narrator_fact_openings, FinanceFormat.grouped(v, 2))

            "realwages" -> if (v >= 0) UiText.of(Res.string.narrator_fact_realwages_more, pct(v)) else UiText.of(Res.string.narrator_fact_realwages_less, pct(abs(v)))

            "temphelp" -> if (v >= 0) UiText.of(Res.string.narrator_fact_temphelp_more, pct(v)) else UiText.of(Res.string.narrator_fact_temphelp_fewer, pct(abs(v)))

            "civpart" -> UiText.of(Res.string.narrator_fact_civpart, pct(v))

            "gdp" -> if (v >= 0) UiText.of(Res.string.narrator_fact_gdp_grew, pct(v)) else UiText.of(Res.string.narrator_verdict_gdp_shrank, pct(abs(v)))

            "umcsent" -> UiText.of(Res.string.narrator_fact_umcsent, FinanceFormat.grouped(v, 0))

            "t10y2y", "t10y3m" -> if (v < 0) {
                UiText.of(Res.string.narrator_fact_curve_inverted, FinanceFormat.grouped(abs(v), 2))
            } else {
                UiText.of(Res.string.narrator_fact_curve_normal, FinanceFormat.grouped(v, 2))
            }

            "hy" -> UiText.of(Res.string.narrator_fact_hy, FinanceFormat.grouped(v, 1))

            "stlfsi" -> UiText.of(Res.string.narrator_fact_stlfsi, FinanceFormat.grouped(v, 2))

            "vix" -> UiText.of(Res.string.narrator_fact_vix, FinanceFormat.grouped(v, 0))

            "ccdelinq" -> UiText.of(Res.string.narrator_fact_ccdelinq, pct(v))

            "debtgdp" -> UiText.of(Res.string.narrator_verdict_debtgdp, FinanceFormat.grouped(v, 0))

            "interest" -> UiText.of(Res.string.narrator_verdict_interest, FinanceFormat.grouped(v, 0))

            "m2" -> if (v >= 0) UiText.of(Res.string.narrator_verdict_m2_growing, pct(v)) else UiText.of(Res.string.narrator_verdict_m2_shrinking, pct(abs(v)))

            "dff" -> UiText.of(Res.string.narrator_verdict_dff, pct(v, 2))

            "mortgage" -> UiText.of(Res.string.narrator_verdict_mortgage, pct(v, 2), FinanceFormat.money(monthlyPayment(500_000.0, v / 100, 30), 0))

            "homeprices" -> if (v >= 0) UiText.of(Res.string.narrator_verdict_homeprices_up, pct(v)) else UiText.of(Res.string.narrator_verdict_homeprices_down, pct(abs(v)))

            "dgs10" -> UiText.of(Res.string.narrator_verdict_dgs10, pct(v, 2))

            "dgs2" -> UiText.of(Res.string.narrator_verdict_dgs2, pct(v, 2))

            "dgs30" -> UiText.of(Res.string.narrator_verdict_dgs30, pct(v, 2))

            else -> UiText.of(Res.string.narrator_verdict_other, value(r, v))
        }
    }

    /** Where the reading sits against its watch and danger lines, in numbers; null when it has none. */
    fun standing(r: IndicatorReading): UiText? {
        val v = r.latest ?: return null
        val th = r.indicator.thresholds ?: return null
        return when (th.signal(v)) {
            Signal.DANGER -> UiText.of(Res.string.narrator_standing_danger, value(r, th.danger))
            Signal.WATCH -> UiText.of(Res.string.narrator_standing_watch, value(r, th.watch), value(r, th.danger))
            Signal.CALM -> UiText.of(Res.string.narrator_standing_calm, value(r, th.watch))
        }
    }

    /** A kind word on the reading, for the bright side: how it compares, with the good said first. */
    private fun judgement(r: IndicatorReading): UiText? {
        val v = r.latest ?: return null
        val signal = r.signal
        return when (r.indicator.id) {
            "cpi", "corecpi" -> UiText.of(
                when {
                    v < 0 -> Res.string.narrator_judgement_cpi_falling
                    v <= 2.2 -> Res.string.narrator_judgement_cpi_on_goal
                    v <= 3.5 -> Res.string.narrator_judgement_cpi_above_goal
                    v <= 5 -> Res.string.narrator_judgement_cpi_well_above_goal
                    else -> Res.string.narrator_judgement_cpi_far_above_goal
                },
            )

            "unrate" -> when {
                v < 4.5 -> UiText.of(Res.string.narrator_judgement_unrate_strong)
                v < 5.5 -> UiText.of(Res.string.narrator_judgement_unrate_cooling, FinanceFormat.grouped(100 - v, 1))
                else -> UiText.of(Res.string.narrator_judgement_unrate_harder)
            }

            "sahm" -> UiText.of(if (v >= 0.5) Res.string.narrator_judgement_sahm_ringing else Res.string.narrator_judgement_sahm_quiet)

            "icsa" -> UiText.of(
                when {
                    v < 250 -> Res.string.narrator_judgement_icsa_low
                    v < 300 -> Res.string.narrator_judgement_icsa_rising
                    else -> Res.string.narrator_judgement_icsa_high
                },
            )

            "t10y2y", "t10y3m" -> UiText.of(if (v < 0) Res.string.narrator_judgement_curve_inverted else Res.string.narrator_judgement_curve_normal)

            "umcsent" -> UiText.of(
                when {
                    v < 60 -> Res.string.narrator_judgement_umcsent_gloomy
                    v < 80 -> Res.string.narrator_judgement_umcsent_below
                    else -> Res.string.narrator_judgement_umcsent_upbeat
                },
            )

            "gdp" -> UiText.of(
                when {
                    v >= 2 -> Res.string.narrator_judgement_gdp_healthy
                    v >= 0 -> Res.string.narrator_judgement_gdp_slow
                    else -> Res.string.narrator_judgement_gdp_shrinking
                },
            )

            "realwages" -> UiText.of(if (v >= 0) Res.string.narrator_judgement_realwages_raise else Res.string.narrator_judgement_realwages_trailing)

            "ccdelinq" -> UiText.of(Res.string.narrator_judgement_ccdelinq, pct(100 - v))

            else -> when (signal) {
                Signal.CALM -> UiText.of(Res.string.narrator_judgement_calm)
                Signal.WATCH -> UiText.of(Res.string.narrator_judgement_watch)
                Signal.DANGER -> UiText.of(Res.string.narrator_judgement_danger)
                null -> null
            }
        }
    }

    /**
     * One plain sentence on what [r] says right now. Straight talk adds where it stands against its
     * lines; the bright side adds a kind word on it.
     */
    fun verdict(r: IndicatorReading, tone: EconomyTone): UiText {
        if (r.latest == null) return UiText.of(Res.string.narrator_verdict_waiting)
        val second = when (tone) {
            EconomyTone.STRAIGHT -> standing(r)
            EconomyTone.BRIGHT_SIDE -> judgement(r)
        }
        return sentences(listOfNotNull(fact(r), second))
    }

    /** The verdict with which way it's heading, for an explainer's "Right now". */
    fun rightNow(r: IndicatorReading, tone: EconomyTone): UiText = sentences(listOfNotNull(verdict(r, tone), heading(r, tone)))

    /**
     * Today's reading against its own history: the share of past readings it's better or worse
     * than, how long since it was last this high or low, and what it was a year ago. Only numbers.
     */
    fun record(r: IndicatorReading): UiText? {
        val v = r.latest ?: return null
        val h = r.history
        val t = h.lastTime ?: return null
        if (h.size < 8) return null
        val since = FinanceFormat.monthYear(h.times.first())
        val th = r.indicator.thresholds
        val parts = mutableListOf<UiText>()
        val others = h.size - 1
        if (th != null) {
            val better = (0 until others).count { i -> if (th.higherIsWorse) h.values[i] < v else h.values[i] > v }
            val worse = (0 until others).count { i -> if (th.higherIsWorse) h.values[i] > v else h.values[i] < v }
            parts += if (better >= worse) {
                UiText.of(Res.string.narrator_record_worse_than, better * 100 / others, since)
            } else {
                UiText.of(Res.string.narrator_record_better_than, worse * 100 / others, since)
            }
        } else {
            val lower = (0 until others).count { h.values[it] < v }
            val higher = (0 until others).count { h.values[it] > v }
            parts += if (lower >= higher) {
                UiText.of(Res.string.narrator_record_higher_than, lower * 100 / others, since)
            } else {
                UiText.of(Res.string.narrator_record_lower_than, higher * 100 / others, since)
            }
        }
        extreme(h, v, t)?.let { parts += it }
        yearAgo(h, t)?.let { parts += UiText.of(Res.string.narrator_record_year_ago, value(r, it)) }
        return sentences(parts)
    }

    /**
     * The reading a year before [t], with the same few days' grace as [Series.yearOverYearPercent]
     * (which also covers a weekly series' 52 weeks); null when that reading is missing, so a
     * 13-month-old one is never called a year's.
     */
    private fun yearAgo(h: Series, t: Long): Double? {
        val target = t - Series.YEAR_SECONDS
        val k = h.indexAtOrBefore(target + 3 * Series.DAY_SECONDS)
        if (k < 0 || h.times[k] < target - 3 * Series.DAY_SECONDS) return null
        return h.values[k]
    }

    /** "The highest since Oct 2021." when today's reading hasn't been matched for over a year; null otherwise. */
    private fun extreme(h: Series, v: Double, t: Long): UiText? {
        fun lastAtLeastAs(higher: Boolean): Long? {
            for (i in h.size - 2 downTo 0) if (if (higher) h.values[i] >= v else h.values[i] <= v) return h.times[i]
            return null
        }
        val yearAgo = t - Series.YEAR_SECONDS
        for (higher in listOf(true, false)) {
            val at = lastAtLeastAs(higher)
            if (at == null) return UiText.of(if (higher) Res.string.narrator_record_highest_ever else Res.string.narrator_record_lowest_ever)
            if (at < yearAgo) return UiText.of(if (higher) Res.string.narrator_record_highest_since else Res.string.narrator_record_lowest_since, FinanceFormat.monthYear(at))
        }
        return null
    }

    /** The upsides a reading brings, or what tends to go right from here. Pragmatic, never advice. */
    fun brightSide(r: IndicatorReading): UiText? {
        val v = r.latest ?: return null
        val worse = r.signal == Signal.WATCH || r.signal == Signal.DANGER
        val sentence = when (r.indicator.id) {
            "cpi", "corecpi", "corepce" -> when {
                v < 0 -> Res.string.narrator_bright_prices_falling
                !worse -> Res.string.narrator_bright_prices_near_goal
                else -> Res.string.narrator_bright_prices_hot
            }

            "unrate", "sahm", "icsa", "u6", "slackgap", "longterm", "insured", "primeepop" ->
                if (!worse) Res.string.narrator_bright_jobs_tight else Res.string.narrator_bright_jobs_cooling

            "quits", "openings" -> if (!worse) Res.string.narrator_bright_hiring_options else Res.string.narrator_bright_hiring_slower

            "realwages" -> if (v >= 0) Res.string.narrator_bright_realwages_up else Res.string.narrator_bright_realwages_down

            "temphelp" -> if (v >= 0) Res.string.narrator_bright_temphelp_up else Res.string.narrator_bright_temphelp_down

            "civpart" -> Res.string.narrator_bright_civpart

            "gdp" -> if (!worse) Res.string.narrator_bright_gdp_growing else Res.string.narrator_bright_gdp_recession

            "umcsent" -> Res.string.narrator_bright_umcsent

            "t10y2y", "t10y3m" -> if (v < 0) Res.string.narrator_bright_curve_inverted else Res.string.narrator_bright_curve_normal

            "hy", "stlfsi", "vix" -> if (!worse) Res.string.narrator_bright_markets_calm else Res.string.narrator_bright_markets_panic

            "ccdelinq" -> return UiText.of(Res.string.narrator_bright_ccdelinq, pct(100 - v))

            "debtgdp", "interest" -> Res.string.narrator_bright_debt

            "m2" -> if (v >= 0) Res.string.narrator_bright_m2_growing else Res.string.narrator_bright_m2_shrinking

            "dff", "dgs2" -> Res.string.narrator_bright_fed_rate

            "mortgage", "dgs10", "dgs30" -> if (!worse) Res.string.narrator_bright_rates_reasonable else Res.string.narrator_bright_rates_high

            "homeprices" -> if (v >= 0) Res.string.narrator_bright_homeprices_up else Res.string.narrator_bright_homeprices_down

            else -> return null
        }
        return UiText.of(sentence)
    }

    /** What the detail page and the explainer add under "Right now": the record (straight talk) or the bright side. */
    fun perspective(r: IndicatorReading, tone: EconomyTone): Perspective? = when (tone) {
        EconomyTone.STRAIGHT -> record(r)?.let { Perspective(Res.string.narrator_perspective_record, it) }
        EconomyTone.BRIGHT_SIDE -> brightSide(r)?.let { Perspective(Res.string.narrator_perspective_bright_side, it) }
    }

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

            "unrate", "sahm", "icsa", "u6", "slackgap", "longterm", "insured", "openings" -> finance?.runwayMonths?.let { months ->
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
     * The economy in a handful of lines, each a part of it with its signal: prices, jobs, the
     * slack beneath the headline jobs number, borrowing, markets, recession warnings and
     * government debt. Missing readings are left out. Straight talk leads with the numbers and
     * the lines they're measured against; the bright side reads them as a weather report.
     */
    fun briefing(readings: Map<String, IndicatorReading>, stress: StressScore?, quotes: Map<String, Quote>, tone: EconomyTone): Briefing {
        val straight = tone == EconomyTone.STRAIGHT
        val items = mutableListOf<BriefingItem>()
        readings["cpi"]?.let { r ->
            val v = r.latest ?: return@let
            items += BriefingItem(
                Res.string.narrator_topic_prices,
                r.signal,
                when {
                    straight && v >= 0 -> UiText.of(Res.string.narrator_briefing_prices_higher_straight, pct(v))
                    straight -> UiText.of(Res.string.narrator_briefing_prices_lower_straight, pct(abs(v)))
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
            val u6 = readings["u6"]?.latest
            val claims = readings["icsa"]?.latest
            items += BriefingItem(
                Res.string.narrator_topic_jobs,
                worst?.signal,
                if (straight) {
                    val parts = listOfNotNull(
                        un?.let { UiText.of(Res.string.narrator_briefing_jobs_unrate_straight, pct(it)) },
                        u6?.let { UiText.of(Res.string.narrator_briefing_jobs_u6_straight, pct(it)) },
                        claims?.let { UiText.of(Res.string.narrator_briefing_jobs_claims_straight, FinanceFormat.grouped(it, 0)) },
                    )
                    // Only the jobs alarm is in: say what it reads.
                    if (parts.isEmpty()) fact(jobs.first()) else sentences(parts)
                } else {
                    when (worst?.signal) {
                        Signal.DANGER -> UiText.of(Res.string.narrator_briefing_jobs_weakening)
                        Signal.WATCH -> un?.let { UiText.of(Res.string.narrator_briefing_jobs_cooling_rate, pct(100 - it)) } ?: UiText.of(Res.string.narrator_briefing_jobs_cooling)
                        else -> un?.let { UiText.of(Res.string.narrator_briefing_jobs_plentiful_rate, pct(it)) } ?: UiText.of(Res.string.narrator_briefing_jobs_plentiful)
                    }
                },
                "unrate",
            )
        }
        val slack = IndicatorCatalog.labor.mapNotNull { readings[it.id] }.filter { it.indicator.thresholds != null && it.latest != null }
        if (slack.isNotEmpty()) {
            val flashing = slack.filter { it.signal == Signal.WATCH || it.signal == Signal.DANGER }.sortedByDescending { it.stress ?: 0.0 }
            val worst = flashing.firstOrNull()?.signal ?: Signal.CALM
            val calm = slack.size - flashing.size
            items += BriefingItem(
                Res.string.narrator_topic_slack,
                worst,
                when {
                    straight && flashing.isEmpty() -> UiText.plural(Res.plurals.narrator_briefing_slack_calm_straight, slack.size)

                    straight -> UiText.plural(
                        Res.plurals.narrator_briefing_slack_past_straight,
                        slack.size,
                        flashing.size,
                        slack.size,
                        UiText.Joined(
                            flashing.take(3).map { UiText.of(Res.string.narrator_briefing_slack_item, plainTitle(it.indicator.id), value(it, it.latest!!)) },
                            UiText.of(Res.string.common_list_separator),
                        ),
                    )

                    flashing.isEmpty() -> UiText.of(Res.string.narrator_briefing_slack_sound)

                    flashing.size == 1 -> UiText.plural(Res.plurals.narrator_briefing_slack_soft_spot, slack.size, calm, slack.size, plainTitle(flashing[0].indicator.id))

                    else -> UiText.plural(
                        Res.plurals.narrator_briefing_slack_soft_spots,
                        slack.size,
                        calm,
                        slack.size,
                        plainTitle(flashing[0].indicator.id),
                        plainTitle(flashing[1].indicator.id),
                    )
                },
                "u6",
            )
        }
        readings["mortgage"]?.let { r ->
            val v = r.latest ?: return@let
            val ff = readings["dff"]?.latest
            items += BriefingItem(
                Res.string.narrator_topic_borrowing,
                r.signal,
                when {
                    straight && ff != null -> UiText.of(Res.string.narrator_briefing_borrowing_straight_fed, pct(v, 2), pct(ff, 2))
                    straight -> UiText.of(Res.string.narrator_briefing_borrowing_straight, pct(v, 2))
                    v >= 6.5 && ff != null -> UiText.of(Res.string.narrator_briefing_borrowing_expensive_fed, pct(v, 1), pct(ff, 2))
                    v >= 6.5 -> UiText.of(Res.string.narrator_briefing_borrowing_expensive, pct(v, 1))
                    v >= 5 -> UiText.of(Res.string.narrator_briefing_borrowing_moderate, pct(v, 1))
                    else -> UiText.of(Res.string.narrator_briefing_borrowing_cheap, pct(v, 1))
                },
                "mortgage",
            )
        }
        val vix = readings["vix"]
        val sp = quotes[MarketCatalog.SP500.symbol]
        if (vix != null || sp != null) {
            val calm = (vix?.latest ?: 15.0) < 20
            val up = sp != null && sp.change >= 0
            val move = sp?.let { FinanceFormat.percent(abs(it.changePercent), 2) }
            items += BriefingItem(
                Res.string.narrator_topic_markets,
                vix?.signal,
                if (straight) {
                    val parts = listOfNotNull(
                        vix?.latest?.let { UiText.of(Res.string.narrator_briefing_markets_fear_straight, FinanceFormat.grouped(it, 0)) },
                        move?.let { UiText.of(if (up) Res.string.narrator_briefing_markets_up_straight else Res.string.narrator_briefing_markets_down_straight, it) },
                    )
                    if (parts.isEmpty()) UiText.of(Res.string.narrator_verdict_waiting) else sentences(parts)
                } else if (move == null) {
                    UiText.of(if (calm) Res.string.narrator_briefing_markets_calm else Res.string.narrator_briefing_markets_jittery)
                } else {
                    UiText.of(
                        when {
                            calm && up -> Res.string.narrator_briefing_markets_calm_up
                            calm -> Res.string.narrator_briefing_markets_calm_down
                            up -> Res.string.narrator_briefing_markets_jittery_up
                            else -> Res.string.narrator_briefing_markets_jittery_down
                        },
                        move,
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
                signal,
                if (straight) {
                    val parts = listOfNotNull(
                        curve?.latest?.let {
                            UiText.of(
                                if (it < 0) Res.string.narrator_briefing_recession_curve_inverted_straight else Res.string.narrator_briefing_recession_curve_normal_straight,
                                FinanceFormat.signedPercent(it),
                            )
                        },
                        sahm?.latest?.let { UiText.of(Res.string.narrator_briefing_recession_alarm_straight, FinanceFormat.grouped(it.coerceAtLeast(0.0), 2)) },
                    )
                    if (parts.isEmpty()) UiText.of(Res.string.narrator_verdict_waiting) else sentences(parts)
                } else {
                    UiText.of(
                        when {
                            inverted && alarm -> Res.string.narrator_briefing_recession_both
                            alarm -> Res.string.narrator_briefing_recession_jobs
                            inverted -> Res.string.narrator_briefing_recession_curve
                            else -> Res.string.narrator_briefing_recession_quiet
                        },
                    )
                },
                "recession",
            )
        }
        readings["interest"]?.let { r ->
            val v = r.latest ?: return@let
            val debt = readings["debtgdp"]?.latest
            val cents = FinanceFormat.grouped(v, 0)
            items += BriefingItem(
                Res.string.narrator_topic_debt,
                r.signal,
                when {
                    straight && debt != null -> UiText.of(Res.string.narrator_briefing_debt_straight_ratio, cents, pct(debt, 0))
                    straight -> UiText.of(Res.string.narrator_briefing_debt_straight, cents)
                    v >= 20 -> UiText.of(Res.string.narrator_briefing_debt_strain, cents)
                    else -> UiText.of(Res.string.narrator_briefing_debt, cents)
                },
                "interest",
            )
        }
        val dangers = items.count { it.signal == Signal.DANGER }
        val watches = items.count { it.signal == Signal.WATCH }
        // Only lines that were actually judged calm: a part still waiting on its reading (markets
        // with a quote but no fear gauge yet) is neither reassuring nor alarming.
        val calms = items.count { it.signal == Signal.CALM }
        val headline = when {
            items.isEmpty() -> UiText.of(Res.string.narrator_headline_gathering)
            straight -> UiText.of(Res.string.narrator_headline_straight, dangers, watches, calms)
            dangers >= 3 -> UiText.of(Res.string.narrator_headline_stormy)
            dangers >= 1 -> UiText.of(Res.string.narrator_headline_mixed)
            watches >= 3 -> UiText.of(Res.string.narrator_headline_mostly_cloudy)
            watches >= 1 -> UiText.of(Res.string.narrator_headline_partly_cloudy)
            else -> UiText.of(Res.string.narrator_headline_mostly_sunny)
        }
        val summary = when {
            items.isEmpty() -> null

            straight && stress != null -> UiText.plural(
                byStressBand(
                    stress.score,
                    calm = Res.plurals.narrator_summary_straight_calm,
                    elevated = Res.plurals.narrator_summary_straight_elevated,
                    high = Res.plurals.narrator_summary_straight_high,
                    severe = Res.plurals.narrator_summary_straight_severe,
                ),
                stress.counted,
                FinanceFormat.grouped(stress.score, 0),
                stress.counted,
                stress.dangers,
                stress.watches,
            )

            straight -> UiText.plural(Res.plurals.narrator_summary_straight_dangers, items.size, dangers, items.size)

            stress == null -> UiText.plural(Res.plurals.narrator_summary_calm_areas, items.size, calms, items.size)

            else -> UiText.plural(
                byStressBand(
                    stress.score,
                    calm = Res.plurals.narrator_summary_bright_calm,
                    elevated = Res.plurals.narrator_summary_bright_elevated,
                    high = Res.plurals.narrator_summary_bright_high,
                    severe = Res.plurals.narrator_summary_bright_severe,
                ),
                stress.counted,
                FinanceFormat.grouped(stress.score, 0),
                stress.counted - stress.dangers - stress.watches,
                stress.counted,
            )
        }
        return Briefing(tone, headline, summary, items)
    }

    /** The plain name for an indicator, falling back to its own short title, then its id. */
    fun plainTitle(id: String): UiText =
        (Explainers.byId(id)?.title ?: IndicatorCatalog.byId(id)?.shortTitle)?.let { UiText.of(it) } ?: id.asUiText()
}

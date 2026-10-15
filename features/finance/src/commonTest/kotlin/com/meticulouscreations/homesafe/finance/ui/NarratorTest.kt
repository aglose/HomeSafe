package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.Signal
import com.meticulouscreations.homesafe.finance.domain.StressScore
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.narrator_briefing_jobs_u6_straight
import homesafe.shared.generated.resources.narrator_briefing_jobs_unrate_straight
import homesafe.shared.generated.resources.narrator_briefing_prices_falling
import homesafe.shared.generated.resources.narrator_briefing_prices_lower_straight
import homesafe.shared.generated.resources.narrator_briefing_slack_item
import homesafe.shared.generated.resources.narrator_briefing_slack_past_straight
import homesafe.shared.generated.resources.narrator_change_points
import homesafe.shared.generated.resources.narrator_fact_cpi_higher
import homesafe.shared.generated.resources.narrator_fact_cpi_lower
import homesafe.shared.generated.resources.narrator_fact_curve_inverted
import homesafe.shared.generated.resources.narrator_fact_curve_normal
import homesafe.shared.generated.resources.narrator_fact_unrate
import homesafe.shared.generated.resources.narrator_for_you_mortgage_more
import homesafe.shared.generated.resources.narrator_for_you_prices_falling_spend
import homesafe.shared.generated.resources.narrator_for_you_prices_rising_spend
import homesafe.shared.generated.resources.narrator_headline_gathering
import homesafe.shared.generated.resources.narrator_headline_mostly_sunny
import homesafe.shared.generated.resources.narrator_headline_stormy
import homesafe.shared.generated.resources.narrator_headline_straight
import homesafe.shared.generated.resources.narrator_judgement_cpi_above_goal
import homesafe.shared.generated.resources.narrator_judgement_cpi_falling
import homesafe.shared.generated.resources.narrator_judgement_cpi_on_goal
import homesafe.shared.generated.resources.narrator_judgement_cpi_well_above_goal
import homesafe.shared.generated.resources.narrator_judgement_curve_inverted
import homesafe.shared.generated.resources.narrator_judgement_curve_normal
import homesafe.shared.generated.resources.narrator_perspective_bright_side
import homesafe.shared.generated.resources.narrator_perspective_record
import homesafe.shared.generated.resources.narrator_quote_up_ordinary
import homesafe.shared.generated.resources.narrator_record_highest_since
import homesafe.shared.generated.resources.narrator_record_worse_than
import homesafe.shared.generated.resources.narrator_record_year_ago
import homesafe.shared.generated.resources.narrator_standing_calm
import homesafe.shared.generated.resources.narrator_standing_danger
import homesafe.shared.generated.resources.narrator_standing_watch
import homesafe.shared.generated.resources.narrator_summary_bright_severe
import homesafe.shared.generated.resources.narrator_summary_calm_areas
import homesafe.shared.generated.resources.narrator_topic_jobs
import homesafe.shared.generated.resources.narrator_topic_markets
import homesafe.shared.generated.resources.narrator_topic_prices
import homesafe.shared.generated.resources.narrator_topic_recession
import homesafe.shared.generated.resources.narrator_topic_slack
import homesafe.shared.generated.resources.narrator_trend_climbing_worse
import homesafe.shared.generated.resources.narrator_trend_straight_back_toward
import homesafe.shared.generated.resources.narrator_trend_straight_further_in
import homesafe.shared.generated.resources.narrator_trend_straight_toward
import homesafe.shared.generated.resources.narrator_two_sentences
import homesafe.shared.generated.resources.narrator_verdict_corepce_falling
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val STRAIGHT = EconomyTone.STRAIGHT
private val BRIGHT = EconomyTone.BRIGHT_SIDE

class NarratorTest {

    // A twelfth of a year, so twelve readings back is exactly a year back, as FRED's month starts are.
    private val month = Series.YEAR_SECONDS / 12

    /** A monthly series of [values], oldest first, ending now. */
    private fun monthly(vararg values: Double) = Series(LongArray(values.size) { 1_790_000_000L - (values.size - 1 - it) * month }, values)

    private fun reading(id: String, vararg values: Double) = IndicatorReading(IndicatorCatalog.byId(id)!!, monthly(*values))

    /** [first] then [second], as the narrator puts two whole sentences side by side. */
    private fun two(first: UiText, second: UiText) = UiText.of(Res.string.narrator_two_sentences, first, second)

    /** The whole sentences [text] is made of, in order. */
    private fun sentencesOf(text: UiText): List<UiText> =
        if (text is UiText.Resource && text.res == Res.string.narrator_two_sentences) text.args.flatMap { sentencesOf(it as UiText) } else listOf(text)

    @Test
    fun everyExplainerLinkLeadsSomewhere() {
        Explainers.glossary.forEach { e ->
            e.related.forEach { r -> assertNotNull(Explainers.byId(r), "${e.id} links to missing $r") }
        }
    }

    @Test
    fun everyIndicatorAndMarketSymbolHasAnExplainer() {
        IndicatorCatalog.all.forEach { assertNotNull(Explainers.byId(it.id), "no explainer for ${it.id}") }
        (MarketCatalog.indices + MarketCatalog.macro).forEach { m ->
            val id = assertNotNull(Explainers.forSymbol(m.symbol), "no explainer for ${m.symbol}")
            assertNotNull(Explainers.byId(id))
        }
    }

    @Test
    fun theBrightSideMeasuresInflationAgainstTheFedsGoal() {
        assertEquals(
            two(UiText.of(Res.string.narrator_fact_cpi_higher, "2.0%"), UiText.of(Res.string.narrator_judgement_cpi_on_goal)),
            Narrator.verdict(reading("cpi", 2.0), BRIGHT),
        )
        assertEquals(
            two(UiText.of(Res.string.narrator_fact_cpi_higher, "3.0%"), UiText.of(Res.string.narrator_judgement_cpi_above_goal)),
            Narrator.verdict(reading("cpi", 3.0), BRIGHT),
        )
        assertEquals(
            two(UiText.of(Res.string.narrator_fact_cpi_higher, "4.0%"), UiText.of(Res.string.narrator_judgement_cpi_well_above_goal)),
            Narrator.verdict(reading("cpi", 4.0), BRIGHT),
        )
        // Deflation is its own sentence, "lower", with the size of the fall unsigned.
        assertEquals(
            two(UiText.of(Res.string.narrator_fact_cpi_lower, "0.5%"), UiText.of(Res.string.narrator_judgement_cpi_falling)),
            Narrator.verdict(reading("cpi", -0.5), BRIGHT),
        )
    }

    @Test
    fun straightTalkStatesTheLinesInsteadOfAdjectives() {
        // Unemployment 5.0%: past the 4.5% watch line, short of the 5.5% danger line.
        assertEquals(
            two(UiText.of(Res.string.narrator_fact_unrate, "5.0"), UiText.of(Res.string.narrator_standing_watch, "4.50%", "5.50%")),
            Narrator.verdict(reading("unrate", 5.0), STRAIGHT),
        )
        assertEquals(UiText.of(Res.string.narrator_standing_danger, "5.50%"), sentencesOf(Narrator.verdict(reading("unrate", 6.0), STRAIGHT)).last())
        assertEquals(UiText.of(Res.string.narrator_standing_calm, "4.50%"), sentencesOf(Narrator.verdict(reading("unrate", 3.8), STRAIGHT)).last())
    }

    @Test
    fun fallingPricesAreNeverCalledNegativeRises() {
        val r = mapOf("cpi" to reading("cpi", -1.0))
        val mine = assertIs<UiText.Resource>(Narrator.forYou("cpi", r, emptyMap(), FinanceFixtures.finance))
        assertEquals(Res.string.narrator_for_you_prices_falling_spend, mine.res)
        mine.args.forEach { assertFalse(it.toString().contains("-"), "$it") }
        val expected = mapOf(
            STRAIGHT to UiText.of(Res.string.narrator_briefing_prices_lower_straight, "1.0%"),
            BRIGHT to UiText.of(Res.string.narrator_briefing_prices_falling, "1.0%"),
        )
        EconomyTone.entries.forEach { tone ->
            val briefing = Narrator.briefing(r, null, emptyMap(), tone)
            assertEquals(expected.getValue(tone), briefing.items.first { it.topic == Res.string.narrator_topic_prices }.sentence, tone.name)
        }
        assertEquals(UiText.of(Res.string.narrator_verdict_corepce_falling, "0.4%"), Narrator.fact(reading("corepce", -0.4)))
    }

    @Test
    fun theYieldCurveVerdictSaysWhenItsInverted() {
        assertEquals(
            two(UiText.of(Res.string.narrator_fact_curve_inverted, "0.30"), UiText.of(Res.string.narrator_judgement_curve_inverted)),
            Narrator.verdict(reading("t10y2y", -0.3), BRIGHT),
        )
        assertEquals(
            two(UiText.of(Res.string.narrator_fact_curve_normal, "0.60"), UiText.of(Res.string.narrator_judgement_curve_normal)),
            Narrator.verdict(reading("t10y2y", 0.6), BRIGHT),
        )
        assertEquals(UiText.of(Res.string.narrator_fact_curve_inverted, "0.30"), sentencesOf(Narrator.verdict(reading("t10y2y", -0.3), STRAIGHT)).first())
        assertEquals(UiText.of(Res.string.narrator_fact_curve_normal, "0.60"), sentencesOf(Narrator.verdict(reading("t10y2y", 0.6), STRAIGHT)).first())
    }

    @Test
    fun theTrendIgnoresWigglesAndSaysWhichWayItsHeading() {
        // Unemployment (higher is worse) climbing a full point over six months.
        val rising = reading("unrate", 3.8, 3.9, 4.0, 4.2, 4.4, 4.6, 4.8)
        assertEquals(
            two(Narrator.verdict(rising, BRIGHT), UiText.of(Res.string.narrator_trend_climbing_worse)),
            Narrator.rightNow(rising, BRIGHT),
        )
        assertEquals(
            two(Narrator.verdict(rising, STRAIGHT), UiText.of(Res.string.narrator_trend_straight_toward, UiText.of(Res.string.narrator_change_points, "+1.00"))),
            Narrator.rightNow(rising, STRAIGHT),
        )
        assertEquals(Trend.STEADY, Narrator.trend(monthly(4.0, 4.01, 3.99, 4.0, 4.02, 4.0, 4.01), noise = 0.1))
        assertEquals(Trend.FALLING, Narrator.trend(monthly(5.0, 4.8, 4.6, 4.4, 4.2, 4.0, 3.8), noise = 0.1))
    }

    @Test
    fun pastTheDangerLineAWorseningMoveGoesFurtherIn() {
        // Unemployment 6% → 7%, already past the 5.5% danger line.
        val worse = Narrator.rightNow(reading("unrate", 6.0, 6.2, 6.4, 6.6, 6.8, 6.9, 7.0), STRAIGHT)
        assertEquals(UiText.of(Res.string.narrator_trend_straight_further_in, UiText.of(Res.string.narrator_change_points, "+1.00")), sentencesOf(worse).last())
        val better = Narrator.rightNow(reading("unrate", 7.0, 6.9, 6.8, 6.6, 6.4, 6.2, 6.0), STRAIGHT)
        assertEquals(UiText.of(Res.string.narrator_trend_straight_back_toward, UiText.of(Res.string.narrator_change_points, "−1.00")), sentencesOf(better).last())
    }

    @Test
    fun aMissingMonthIsNeverQuotedAsAYearAgo() {
        // Fourteen monthly readings 30 days apart, with the one closest to a year back removed.
        val full = monthly(4.0, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 5.0, 5.1, 5.2, 5.3)
        val yearBack = full.lastTime!! - Series.YEAR_SECONDS
        val gap = full.times.indices.minByOrNull { abs(full.times[it] - yearBack) }!!
        val holed = Series(full.times.filterIndexed { i, _ -> i != gap }.toLongArray(), full.values.filterIndexed { i, _ -> i != gap }.toDoubleArray())
        val record = assertNotNull(Narrator.record(IndicatorReading(IndicatorCatalog.unemployment, holed)))
        assertTrue(sentencesOf(record).none { it is UiText.Resource && it.res == Res.string.narrator_record_year_ago }, "$record")
    }

    @Test
    fun theRecordPlacesTodayInItsOwnHistory() {
        // Fourteen months; today's 4.6% is the highest since the first month's 5.0%.
        val r = reading("unrate", 5.0, 4.0, 3.9, 3.8, 3.7, 3.6, 3.6, 3.7, 3.8, 3.9, 4.0, 4.1, 4.3, 4.6)
        val record = sentencesOf(assertNotNull(Narrator.record(r)))
        assertEquals(UiText.of(Res.string.narrator_record_worse_than, 92, FinanceFormat.monthYear(r.history.times.first())), record.first())
        assertTrue(record.any { it is UiText.Resource && it.res == Res.string.narrator_record_highest_since }, "$record")
        assertTrue(record.any { it is UiText.Resource && it.res == Res.string.narrator_record_year_ago }, "$record")
        assertEquals(Res.string.narrator_perspective_record, Narrator.perspective(r, STRAIGHT)?.heading)
        assertEquals(Res.string.narrator_perspective_bright_side, Narrator.perspective(r, BRIGHT)?.heading)
    }

    @Test
    fun everyRadarReadingHasABrightSide() {
        IndicatorCatalog.all.forEach { ind -> assertNotNull(Narrator.brightSide(IndicatorReading(ind, monthly(1.0, 1.0))), "no bright side for ${ind.id}") }
    }

    @Test
    fun forYouPutsInflationInTheHouseholdsDollars() {
        val finance = FinanceFixtures.finance
        val text = Narrator.forYou("cpi", mapOf("cpi" to reading("cpi", 4.0)), emptyMap(), finance)
        // $11,385 a month at 4% inflation: 11,385 × 4 / 104 ≈ $438.
        assertEquals(
            UiText.of(Res.string.narrator_for_you_prices_rising_spend, FinanceFormat.money(finance.monthlyExpenses!!, 0), "$438", "4.0%"),
            text,
        )
    }

    @Test
    fun forYouComparesTodaysMortgageRateWithThePlan() {
        val finance = FinanceFixtures.finance
        val plan = finance.mortgagePlan!!
        val text = Narrator.forYou("mortgage", mapOf("mortgage" to reading("mortgage", 7.4)), emptyMap(), finance)
        val loan = plan.homePrice * (1 - plan.downPaymentFraction)
        val atMarket = monthlyPayment(loan, 0.074, plan.termYears)
        val diff = atMarket - monthlyPayment(loan, plan.rate, plan.termYears)
        assertEquals(
            UiText.of(
                Res.string.narrator_for_you_mortgage_more,
                FinanceFormat.compactMoney(plan.homePrice),
                FinanceFormat.money(atMarket, 0),
                FinanceFormat.money(diff, 0),
                FinanceFormat.fractionPercent(plan.rate, 2),
            ),
            text,
        )
    }

    @Test
    fun forYouSaysNothingWithoutTheNumbersItNeeds() {
        assertNull(Narrator.forYou("homeprices", mapOf("homeprices" to reading("homeprices", 3.0)), emptyMap(), null))
        assertNull(Narrator.forYou("unrate", emptyMap(), emptyMap(), null))
        assertNull(Narrator.forYou("gdp", emptyMap(), emptyMap(), FinanceFixtures.finance))
    }

    @Test
    fun theBriefingTurnsSignalsIntoWeather() {
        val calm = Narrator.briefing(
            mapOf("cpi" to reading("cpi", 2.1), "unrate" to reading("unrate", 3.9), "t10y2y" to reading("t10y2y", 0.8), "sahm" to reading("sahm", 0.1)),
            stress = null,
            quotes = emptyMap(),
            tone = BRIGHT,
        )
        assertEquals(UiText.of(Res.string.narrator_headline_mostly_sunny), calm.headline)
        assertTrue(calm.items.all { it.weather == Weather.SUNNY })
        val n = calm.items.size
        assertEquals(UiText.plural(Res.plurals.narrator_summary_calm_areas, n, n, n), calm.summary)

        val stormy = Narrator.briefing(
            mapOf("cpi" to reading("cpi", 6.0), "unrate" to reading("unrate", 6.5), "t10y2y" to reading("t10y2y", -0.5), "sahm" to reading("sahm", 0.7)),
            stress = StressScore(75.0, 4, 3, 1),
            quotes = emptyMap(),
            tone = BRIGHT,
        )
        assertEquals(UiText.of(Res.string.narrator_headline_stormy), stormy.headline)
        assertEquals(Weather.STORMY, stormy.items.first { it.topic == Res.string.narrator_topic_recession }.weather)
        // Severe, and none of the four readings calm.
        assertEquals(UiText.plural(Res.plurals.narrator_summary_bright_severe, 4, "75", 0, 4), stormy.summary)
    }

    @Test
    fun theStraightBriefingCountsSignalsAndNamesTheHiddenSlack() {
        val briefing = Narrator.briefing(
            mapOf(
                "cpi" to reading("cpi", 2.1),
                "unrate" to reading("unrate", 4.1),
                "u6" to reading("u6", 7.7),
                "longterm" to reading("longterm", 27.0),
                "quits" to reading("quits", 2.4),
            ),
            stress = null,
            quotes = emptyMap(),
            tone = STRAIGHT,
        )
        assertEquals(UiText.of(Res.string.narrator_headline_straight, 0, 1, 2), briefing.headline)
        assertEquals(
            two(UiText.of(Res.string.narrator_briefing_jobs_unrate_straight, "4.1%"), UiText.of(Res.string.narrator_briefing_jobs_u6_straight, "7.7%")),
            briefing.items.first { it.topic == Res.string.narrator_topic_jobs }.sentence,
        )
        val slack = briefing.items.first { it.topic == Res.string.narrator_topic_slack }
        assertEquals(Signal.WATCH, slack.signal)
        assertEquals(
            UiText.plural(
                Res.plurals.narrator_briefing_slack_past_straight,
                3,
                1,
                3,
                UiText.Joined(
                    listOf(UiText.of(Res.string.narrator_briefing_slack_item, Narrator.plainTitle("longterm"), "27.00%")),
                    UiText.of(Res.string.common_list_separator),
                ),
            ),
            slack.sentence,
        )
    }

    @Test
    fun aPartWithoutASignalIsNotCountedAsCalm() {
        // A quote but no fear gauge yet: the markets line has no signal of its own.
        val briefing = Narrator.briefing(
            mapOf("cpi" to reading("cpi", 2.1)),
            stress = null,
            quotes = mapOf(MarketCatalog.SP500.symbol to FinanceFixtures.quotes.getValue(MarketCatalog.SP500.symbol)),
            tone = STRAIGHT,
        )
        assertNull(briefing.items.first { it.topic == Res.string.narrator_topic_markets }.signal)
        assertEquals(UiText.of(Res.string.narrator_headline_straight, 0, 0, 1), briefing.headline)
    }

    @Test
    fun anEmptyBriefingSaysItsGatheringAndHasNoSummary() {
        EconomyTone.entries.forEach { tone ->
            val empty = Narrator.briefing(emptyMap(), stress = null, quotes = emptyMap(), tone = tone)
            assertEquals(UiText.of(Res.string.narrator_headline_gathering), empty.headline, tone.name)
            assertNull(empty.summary, tone.name)
        }
    }

    @Test
    fun aQuotesMoveIsSizedForAPerson() {
        val q = FinanceFixtures.quotes.getValue(MarketCatalog.SP500.symbol)
        assertEquals(
            UiText.of(Res.string.narrator_quote_up_ordinary, MarketCatalog.SP500.shortName, FinanceFormat.percent(abs(q.changePercent), 2)),
            Narrator.quoteVerdict(MarketCatalog.SP500.symbol, q),
        )
    }
}

package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.StressScore
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.narrator_briefing_prices_falling
import homesafe.shared.generated.resources.narrator_count_warning_signs
import homesafe.shared.generated.resources.narrator_for_you_mortgage_more
import homesafe.shared.generated.resources.narrator_for_you_prices_falling_spend
import homesafe.shared.generated.resources.narrator_for_you_prices_rising_spend
import homesafe.shared.generated.resources.narrator_headline_gathering
import homesafe.shared.generated.resources.narrator_headline_mostly_sunny
import homesafe.shared.generated.resources.narrator_headline_stormy
import homesafe.shared.generated.resources.narrator_quote_up_ordinary
import homesafe.shared.generated.resources.narrator_summary_red
import homesafe.shared.generated.resources.narrator_summary_stress_severe
import homesafe.shared.generated.resources.narrator_topic_prices
import homesafe.shared.generated.resources.narrator_topic_recession
import homesafe.shared.generated.resources.narrator_trend_climbing_worse
import homesafe.shared.generated.resources.narrator_two_sentences
import homesafe.shared.generated.resources.narrator_verdict_corepce_falling
import homesafe.shared.generated.resources.narrator_verdict_cpi_above_goal
import homesafe.shared.generated.resources.narrator_verdict_cpi_falling
import homesafe.shared.generated.resources.narrator_verdict_cpi_on_goal
import homesafe.shared.generated.resources.narrator_verdict_cpi_well_above_goal
import homesafe.shared.generated.resources.narrator_verdict_curve_inverted
import homesafe.shared.generated.resources.narrator_verdict_curve_normal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NarratorTest {

    private val month = 30L * Series.DAY_SECONDS

    /** A monthly series of [values], oldest first, ending now. */
    private fun monthly(vararg values: Double) = Series(LongArray(values.size) { 1_790_000_000L - (values.size - 1 - it) * month }, values)

    private fun reading(id: String, vararg values: Double) = IndicatorReading(IndicatorCatalog.byId(id)!!, monthly(*values))

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
    fun inflationVerdictsMeasureAgainstTheFedsGoal() {
        assertEquals(UiText.of(Res.string.narrator_verdict_cpi_on_goal, "2.0%"), Narrator.verdict(reading("cpi", 2.0)))
        assertEquals(UiText.of(Res.string.narrator_verdict_cpi_above_goal, "3.0%"), Narrator.verdict(reading("cpi", 3.0)))
        assertEquals(UiText.of(Res.string.narrator_verdict_cpi_well_above_goal, "4.0%"), Narrator.verdict(reading("cpi", 4.0)))
        // Deflation is its own sentence, "lower", with the size of the fall unsigned.
        assertEquals(UiText.of(Res.string.narrator_verdict_cpi_falling, "0.5%"), Narrator.verdict(reading("cpi", -0.5)))
    }

    @Test
    fun fallingPricesAreNeverCalledNegativeRises() {
        val r = mapOf("cpi" to reading("cpi", -1.0))
        val mine = assertIs<UiText.Resource>(Narrator.forYou("cpi", r, emptyMap(), FinanceFixtures.finance))
        assertEquals(Res.string.narrator_for_you_prices_falling_spend, mine.res)
        mine.args.forEach { assertFalse(it.toString().contains("-"), "$it") }
        val briefing = Narrator.briefing(r, null, emptyMap())
        assertEquals(
            UiText.of(Res.string.narrator_briefing_prices_falling, "1.0%"),
            briefing.items.first { it.topic == Res.string.narrator_topic_prices }.sentence,
        )
        assertEquals(UiText.of(Res.string.narrator_verdict_corepce_falling, "0.4%"), Narrator.verdict(reading("corepce", -0.4)))
    }

    @Test
    fun theYieldCurveVerdictSaysWhenItsUpsideDown() {
        assertEquals(UiText.of(Res.string.narrator_verdict_curve_inverted, "0.30"), Narrator.verdict(reading("t10y2y", -0.3)))
        assertEquals(UiText.of(Res.string.narrator_verdict_curve_normal, "0.60"), Narrator.verdict(reading("t10y2y", 0.6)))
    }

    @Test
    fun theTrendIgnoresWigglesAndSaysWhetherItsGood() {
        // Unemployment (higher is worse) climbing a full point over six months: the wrong direction.
        val rising = reading("unrate", 3.8, 3.9, 4.0, 4.2, 4.4, 4.6, 4.8)
        assertEquals(
            UiText.of(Res.string.narrator_two_sentences, Narrator.verdict(rising), UiText.of(Res.string.narrator_trend_climbing_worse)),
            Narrator.rightNow(rising),
        )
        assertEquals(Trend.STEADY, Narrator.trend(monthly(4.0, 4.01, 3.99, 4.0, 4.02, 4.0, 4.01), noise = 0.1))
        assertEquals(Trend.FALLING, Narrator.trend(monthly(5.0, 4.8, 4.6, 4.4, 4.2, 4.0, 3.8), noise = 0.1))
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
        )
        assertEquals(Res.string.narrator_headline_mostly_sunny, calm.headline)
        assertTrue(calm.items.all { it.weather == Weather.SUNNY })
        assertEquals(UiText.plural(Res.plurals.narrator_summary_red, calm.items.size, 0, calm.items.size), calm.summary)

        val stormy = Narrator.briefing(
            mapOf("cpi" to reading("cpi", 6.0), "unrate" to reading("unrate", 6.5), "t10y2y" to reading("t10y2y", -0.5), "sahm" to reading("sahm", 0.7)),
            stress = StressScore(75.0, 4, 3, 1),
            quotes = emptyMap(),
        )
        assertEquals(Res.string.narrator_headline_stormy, stormy.headline)
        assertEquals(Weather.STORMY, stormy.items.first { it.topic == Res.string.narrator_topic_recession }.weather)
        assertEquals(
            UiText.of(Res.string.narrator_summary_stress_severe, "75", UiText.plural(Res.plurals.narrator_count_warning_signs, 3), 1),
            stormy.summary,
        )
    }

    @Test
    fun anEmptyBriefingSaysItsGatheringAndHasNoSummary() {
        val empty = Narrator.briefing(emptyMap(), stress = null, quotes = emptyMap())
        assertEquals(Res.string.narrator_headline_gathering, empty.headline)
        assertNull(empty.summary)
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

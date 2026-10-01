package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.StressScore
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(Narrator.verdict(reading("cpi", 2.0)).contains("right around the Fed's 2% goal"))
        assertTrue(Narrator.verdict(reading("cpi", 3.0)).contains("a bit faster"))
        assertTrue(Narrator.verdict(reading("cpi", 4.0)).contains("well above"))
        val deflation = Narrator.verdict(reading("cpi", -0.5))
        assertTrue(deflation.startsWith("Prices are 0.5% lower than a year ago."), deflation)
        assertTrue(deflation.contains("falling"))
    }

    @Test
    fun fallingPricesAreNeverCalledNegativeRises() {
        val r = mapOf("cpi" to reading("cpi", -1.0))
        val mine = assertNotNull(Narrator.forYou("cpi", r, emptyMap(), FinanceFixtures.finance))
        assertTrue(mine.startsWith("Prices are falling"), mine)
        assertTrue(!mine.contains("-"), mine)
        val briefing = Narrator.briefing(r, null, emptyMap())
        assertTrue(briefing.items.first { it.topic == "Prices" }.sentence.contains("1.0% lower"))
        assertTrue(Narrator.verdict(reading("corepce", -0.4)).contains("falling 0.4%"))
    }

    @Test
    fun theYieldCurveVerdictSaysWhenItsUpsideDown() {
        assertTrue(Narrator.verdict(reading("t10y2y", -0.3)).contains("upside-down"))
        assertTrue(Narrator.verdict(reading("t10y2y", 0.6)).contains("normal, healthy"))
    }

    @Test
    fun theTrendIgnoresWigglesAndSaysWhetherItsGood() {
        // Unemployment (higher is worse) climbing a full point over six months: the wrong direction.
        val rising = reading("unrate", 3.8, 3.9, 4.0, 4.2, 4.4, 4.6, 4.8)
        assertTrue(Narrator.rightNow(rising).contains("wrong direction"))
        assertEquals(Trend.STEADY, Narrator.trend(monthly(4.0, 4.01, 3.99, 4.0, 4.02, 4.0, 4.01), noise = 0.1))
        assertEquals(Trend.FALLING, Narrator.trend(monthly(5.0, 4.8, 4.6, 4.4, 4.2, 4.0, 3.8), noise = 0.1))
    }

    @Test
    fun forYouPutsInflationInTheHouseholdsDollars() {
        val finance = FinanceFixtures.finance
        val text = assertNotNull(Narrator.forYou("cpi", mapOf("cpi" to reading("cpi", 4.0)), emptyMap(), finance))
        // $11,385 a month at 4% inflation: 11,385 × 4 / 104 ≈ $438.
        assertTrue(text.contains("$438"), text)
        assertTrue(text.contains(FinanceFormat.money(finance.monthlyExpenses!!, 0)))
    }

    @Test
    fun forYouComparesTodaysMortgageRateWithThePlan() {
        val finance = FinanceFixtures.finance
        val plan = finance.mortgagePlan!!
        val text = assertNotNull(Narrator.forYou("mortgage", mapOf("mortgage" to reading("mortgage", 7.4)), emptyMap(), finance))
        val loan = plan.homePrice * (1 - plan.downPaymentFraction)
        val diff = monthlyPayment(loan, 0.074, plan.termYears) - monthlyPayment(loan, plan.rate, plan.termYears)
        assertTrue(text.contains(FinanceFormat.money(diff, 0) + " more"), text)
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
        assertEquals("Mostly sunny", calm.headline)
        assertTrue(calm.items.all { it.weather == Weather.SUNNY })

        val stormy = Narrator.briefing(
            mapOf("cpi" to reading("cpi", 6.0), "unrate" to reading("unrate", 6.5), "t10y2y" to reading("t10y2y", -0.5), "sahm" to reading("sahm", 0.7)),
            stress = StressScore(75.0, 4, 3, 1),
            quotes = emptyMap(),
        )
        assertEquals("Stormy", stormy.headline)
        assertEquals(Weather.STORMY, stormy.items.first { it.topic == "Recession signs" }.weather)
        assertTrue(stormy.summary.startsWith("Overall stress is severe"))
    }

    @Test
    fun aQuotesMoveIsSizedForAPerson() {
        val q = FinanceFixtures.quotes.getValue(MarketCatalog.SP500.symbol)
        assertTrue(Narrator.quoteVerdict(MarketCatalog.SP500.symbol, q).contains("an ordinary day"))
    }
}

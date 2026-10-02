package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.Signal
import com.meticulouscreations.homesafe.finance.domain.StressScore
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(Narrator.verdict(reading("cpi", 2.0), BRIGHT).contains("right around the Fed's 2% goal"))
        assertTrue(Narrator.verdict(reading("cpi", 3.0), BRIGHT).contains("a bit faster"))
        assertTrue(Narrator.verdict(reading("cpi", 4.0), BRIGHT).contains("well above"))
        val deflation = Narrator.verdict(reading("cpi", -0.5), BRIGHT)
        assertTrue(deflation.startsWith("Prices are 0.5% lower than a year ago."), deflation)
        assertTrue(deflation.contains("falling"))
    }

    @Test
    fun straightTalkStatesTheLinesInsteadOfAdjectives() {
        // Unemployment 5.0%: past the 4.5% watch line, short of the 5.5% danger line.
        val text = Narrator.verdict(reading("unrate", 5.0), STRAIGHT)
        assertTrue(text.contains("past the watch line of 4.50%"), text)
        assertTrue(text.contains("danger line is 5.50%"), text)
        listOf("solid", "softening", "weak", "healthy", "gloomy").forEach { assertTrue(!text.contains(it), "\"$it\" in: $text") }
        assertTrue(Narrator.verdict(reading("unrate", 6.0), STRAIGHT).contains("past the danger line of 5.50%"))
        assertTrue(Narrator.verdict(reading("unrate", 3.8), STRAIGHT).contains("inside the calm range"))
    }

    @Test
    fun fallingPricesAreNeverCalledNegativeRises() {
        val r = mapOf("cpi" to reading("cpi", -1.0))
        val mine = assertNotNull(Narrator.forYou("cpi", r, emptyMap(), FinanceFixtures.finance))
        assertTrue(mine.startsWith("Prices are falling"), mine)
        assertTrue(!mine.contains("-"), mine)
        EconomyTone.entries.forEach { tone ->
            val briefing = Narrator.briefing(r, null, emptyMap(), tone)
            assertTrue(briefing.items.first { it.topic == "Prices" }.sentence.contains("1.0% lower"), tone.name)
        }
        assertTrue(Narrator.verdict(reading("corepce", -0.4), STRAIGHT).contains("falling 0.4%"))
    }

    @Test
    fun theYieldCurveVerdictSaysWhenItsInverted() {
        assertTrue(Narrator.verdict(reading("t10y2y", -0.3), BRIGHT).contains("upside-down"))
        assertTrue(Narrator.verdict(reading("t10y2y", 0.6), BRIGHT).contains("normal, healthy"))
        assertTrue(Narrator.verdict(reading("t10y2y", -0.3), STRAIGHT).contains("the curve is inverted"))
        assertTrue(Narrator.verdict(reading("t10y2y", 0.6), STRAIGHT).contains("not inverted"))
    }

    @Test
    fun theTrendIgnoresWigglesAndSaysWhichWayItsHeading() {
        // Unemployment (higher is worse) climbing a full point over six months.
        val rising = reading("unrate", 3.8, 3.9, 4.0, 4.2, 4.4, 4.6, 4.8)
        assertTrue(Narrator.rightNow(rising, BRIGHT).contains("keep an eye on"))
        val straight = Narrator.rightNow(rising, STRAIGHT)
        assertTrue(straight.contains("+1.00 points over six months, toward the danger line"), straight)
        assertEquals(Trend.STEADY, Narrator.trend(monthly(4.0, 4.01, 3.99, 4.0, 4.02, 4.0, 4.01), noise = 0.1))
        assertEquals(Trend.FALLING, Narrator.trend(monthly(5.0, 4.8, 4.6, 4.4, 4.2, 4.0, 3.8), noise = 0.1))
    }

    @Test
    fun pastTheDangerLineAWorseningMoveGoesFurtherIn() {
        // Unemployment 6% → 7%, already past the 5.5% danger line.
        val worse = Narrator.rightNow(reading("unrate", 6.0, 6.2, 6.4, 6.6, 6.8, 6.9, 7.0), STRAIGHT)
        assertTrue(worse.contains("further into the danger zone"), worse)
        val better = Narrator.rightNow(reading("unrate", 7.0, 6.9, 6.8, 6.6, 6.4, 6.2, 6.0), STRAIGHT)
        assertTrue(better.contains("back toward the danger line"), better)
    }

    @Test
    fun aMissingMonthIsNeverQuotedAsAYearAgo() {
        // Fourteen monthly readings 30 days apart, with the one closest to a year back removed.
        val full = monthly(4.0, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 5.0, 5.1, 5.2, 5.3)
        val yearBack = full.lastTime!! - Series.YEAR_SECONDS
        val gap = full.times.indices.minByOrNull { kotlin.math.abs(full.times[it] - yearBack) }!!
        val holed = Series(full.times.filterIndexed { i, _ -> i != gap }.toLongArray(), full.values.filterIndexed { i, _ -> i != gap }.toDoubleArray())
        val record = assertNotNull(Narrator.record(IndicatorReading(IndicatorCatalog.unemployment, holed)))
        assertTrue(!record.contains("A year ago"), record)
    }

    @Test
    fun theRecordPlacesTodayInItsOwnHistory() {
        // Fourteen months; today's 4.6% is the highest since the first month's 5.0%.
        val r = reading("unrate", 5.0, 4.0, 3.9, 3.8, 3.7, 3.6, 3.6, 3.7, 3.8, 3.9, 4.0, 4.1, 4.3, 4.6)
        val record = assertNotNull(Narrator.record(r))
        assertTrue(record.startsWith("Worse than 92% of readings since"), record)
        assertTrue(record.contains("The highest since"), record)
        assertTrue(record.contains("A year ago it was"), record)
        assertEquals("On the record", Narrator.perspective(r, STRAIGHT)?.heading)
        assertEquals("The bright side", Narrator.perspective(r, BRIGHT)?.heading)
    }

    @Test
    fun everyRadarReadingHasABrightSide() {
        IndicatorCatalog.all.forEach { ind -> assertNotNull(Narrator.brightSide(IndicatorReading(ind, monthly(1.0, 1.0))), "no bright side for ${ind.id}") }
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
            tone = BRIGHT,
        )
        assertEquals("Mostly sunny", calm.headline)
        assertTrue(calm.items.all { it.weather == Weather.SUNNY })

        val stormy = Narrator.briefing(
            mapOf("cpi" to reading("cpi", 6.0), "unrate" to reading("unrate", 6.5), "t10y2y" to reading("t10y2y", -0.5), "sahm" to reading("sahm", 0.7)),
            stress = StressScore(75.0, 4, 3, 1),
            quotes = emptyMap(),
            tone = BRIGHT,
        )
        assertEquals("Stormy", stormy.headline)
        assertEquals(Weather.STORMY, stormy.items.first { it.topic == "Recession signs" }.weather)
        assertTrue(stormy.summary.startsWith("Overall stress is severe"))
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
        assertEquals("0 in danger · 1 to watch · 2 calm", briefing.headline)
        val jobs = briefing.items.first { it.topic == "Jobs" }.sentence
        assertTrue(jobs.contains("Unemployment 4.1%") && jobs.contains("7.7% counting the underemployed"), jobs)
        val slack = briefing.items.first { it.topic == "Beneath the headline" }
        assertEquals(Signal.WATCH, slack.signal)
        assertTrue(slack.sentence.startsWith("1 of 3 measures past a line: long job searches 27.00%"), slack.sentence)
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
        assertNull(briefing.items.first { it.topic == "Markets" }.signal)
        assertEquals("0 in danger · 0 to watch · 1 calm", briefing.headline)
    }

    @Test
    fun aQuotesMoveIsSizedForAPerson() {
        val q = FinanceFixtures.quotes.getValue(MarketCatalog.SP500.symbol)
        assertTrue(Narrator.quoteVerdict(MarketCatalog.SP500.symbol, q).contains("an ordinary day"))
    }
}

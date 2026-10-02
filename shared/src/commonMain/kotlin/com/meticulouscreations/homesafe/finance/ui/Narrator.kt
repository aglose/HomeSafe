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
import kotlin.math.abs

/** Which way a reading has been heading. */
enum class Trend { RISING, FALLING, STEADY }

/** The briefing's weather: how a part of the economy feels right now, at a glance. */
enum class Weather(val label: String) {
    SUNNY("Sunny"),
    PARTLY_CLOUDY("Partly cloudy"),
    CLOUDY("Cloudy"),
    STORMY("Stormy"),
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
data class BriefingItem(val topic: String, val signal: Signal?, val sentence: String, val explainerId: String) {
    val weather: Weather get() = Weather.of(signal)
}

@Immutable
data class Briefing(val tone: EconomyTone, val headline: String, val summary: String, val items: List<BriefingItem>)

/** A heading and a paragraph that put a reading in context, in the chosen tone. */
@Immutable
data class Perspective(val heading: String, val body: String)

/**
 * Turns readings into sentences a person who never reads the business pages can follow: a
 * one-line verdict for each indicator, which way it's heading, what it means for this household's
 * own money (when the budget sheet is in), and a short report on the whole economy.
 *
 * Every sentence is built from the live numbers; nothing here predicts anything. What's said
 * about them follows the [EconomyTone]: [EconomyTone.STRAIGHT] states each reading, where it sits
 * against its watch and danger lines and its own history, and nothing it can't back with a
 * number; [EconomyTone.BRIGHT_SIDE] says the same reading kindly and adds the upsides it brings.
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

    private fun noiseOf(r: IndicatorReading): Double {
        val th = r.indicator.thresholds
        return if (th != null) abs(th.danger - th.watch) * 0.2 else (abs(r.latest ?: 1.0) * 0.05).coerceAtLeast(0.05)
    }

    private fun trendOf(r: IndicatorReading): Trend = trend(r.history, noise = noiseOf(r))

    private fun pct(v: Double, decimals: Int = 1) = FinanceFormat.grouped(v, decimals) + "%"

    private fun value(r: IndicatorReading, v: Double) = FinanceFormat.indicator(v, r.indicator.unit)

    /** A change in the reading's own units, written out for a sentence: "+0.40 points" for a rate, "+40K" for a count. */
    private fun change(r: IndicatorReading, delta: Double): String =
        FinanceFormat.indicatorChange(delta, r.indicator.unit).replace(" pts", " points")

    /**
     * Which way it's been heading. Straight talk gives the six-month change and whether it's toward
     * or away from the danger line; the bright side says it in words, kindly.
     */
    private fun heading(r: IndicatorReading, tone: EconomyTone): String {
        val t = trendOf(r)
        val worseUp = r.indicator.thresholds?.higherIsWorse
        if (tone == EconomyTone.STRAIGHT) {
            val time = r.history.lastTime ?: return ""
            val now = r.latest ?: return ""
            val then = r.history.valueAtOrBefore(time - 6 * 30L * Series.DAY_SECONDS) ?: return ""
            val delta = now - then
            val worsening = worseUp != null && (delta > 0) == worseUp
            val toward = when {
                t == Trend.STEADY || worseUp == null -> ""

                // Already past the line, a worsening move goes further from it, not toward it.
                r.signal == Signal.DANGER -> if (worsening) ", further into the danger zone" else ", back toward the danger line"

                worsening -> ", toward the danger line"

                else -> ", away from the danger line"
            }
            val moved = change(r, delta)
            return when {
                moved == "unchanged" -> "Unchanged over six months."
                t == Trend.STEADY -> "Little changed over six months ($moved)."
                else -> "$moved over six months$toward."
            }
        }
        return when (t) {
            Trend.STEADY -> "It's held about steady for six months."

            Trend.RISING -> when (worseUp) {
                false -> "It's been improving over the past six months — a good sign."
                true -> "It's been climbing over the past six months, so it's one to keep an eye on."
                null -> "It's been rising over the past six months."
            }

            Trend.FALLING -> when (worseUp) {
                true -> "It's been easing over the past six months — a good sign."
                false -> "It's been slipping over the past six months, so it's one to keep an eye on."
                null -> "It's been falling over the past six months."
            }
        }
    }

    /** What the reading is, in plain words and with no judgement on it. */
    fun fact(r: IndicatorReading): String {
        val v = r.latest ?: return "Waiting for the latest reading."
        return when (r.indicator.id) {
            // Deflation is said as prices lower, never "−0.5% higher".
            "cpi" -> if (v >= 0) "Prices are ${pct(v)} higher than a year ago." else "Prices are ${pct(abs(v))} lower than a year ago."

            "corecpi" -> if (v >= 0) "Leaving out food and gas, prices are ${pct(v)} higher than a year ago." else "Leaving out food and gas, prices are ${pct(abs(v))} lower than a year ago."

            "corepce" -> if (v >= 0) {
                "The Fed's preferred measure says prices are rising ${pct(v)} a year, against its 2% target."
            } else {
                "The Fed's preferred measure says prices are falling ${pct(abs(v))} a year, below its 2% target."
            }

            "unrate" -> "About ${FinanceFormat.grouped(v, 1)} out of every 100 people who want a job can't find one."

            "sahm" -> "Unemployment's three-month average is ${FinanceFormat.grouped(v.coerceAtLeast(0.0), 2)} points above its low of the past year."

            "icsa" -> "About ${FinanceFormat.grouped(v, 0)},000 people filed for unemployment for the first time last week."

            "u6" -> "Counting part-timers who want full-time work and people who've stopped looking, ${pct(v)} of the workforce is underemployed."

            "slackgap" -> "Underemployment runs ${FinanceFormat.grouped(v, 1)} points above the headline unemployment rate."

            "primeepop" -> "${FinanceFormat.grouped(v, 1)} out of every 100 people aged 25 to 54 have a job."

            "longterm" -> "${pct(v)} of unemployed people have been looking for more than six months."

            "insured" -> "${pct(v)} of workers covered by unemployment insurance are still collecting benefits."

            "quits" -> "${pct(v)} of workers quit their job last month."

            "openings" -> "There are ${FinanceFormat.grouped(v, 2)} open jobs for every unemployed person."

            "realwages" -> if (v >= 0) {
                "The typical full-time paycheck buys ${pct(v)} more than it did a year ago."
            } else {
                "The typical full-time paycheck buys ${pct(abs(v))} less than it did a year ago."
            }

            "temphelp" -> if (v >= 0) "Temp agencies employ ${pct(v)} more people than a year ago." else "Temp agencies employ ${pct(abs(v))} fewer people than a year ago."

            "civpart" -> "${pct(v)} of adults are working or looking for work."

            "gdp" -> if (v >= 0) "The economy grew at a ${pct(v)} yearly pace last quarter." else "The economy shrank at a ${pct(abs(v))} yearly pace last quarter."

            "umcsent" -> "People's mood about money scores ${FinanceFormat.grouped(v, 0)}, against a long-run average of about 85."

            "t10y2y", "t10y3m" -> if (v < 0) {
                "Short-term borrowing costs ${FinanceFormat.grouped(abs(v), 2)} points more than long-term: the curve is inverted."
            } else {
                "Long-term borrowing costs ${FinanceFormat.grouped(v, 2)} points more than short-term: the curve is not inverted."
            }

            "hy" -> "Risky companies pay ${FinanceFormat.grouped(v, 1)} points more than the government to borrow."

            "stlfsi" -> "The financial stress index reads ${FinanceFormat.grouped(v, 2)}, where 0 is normal."

            "vix" -> "The fear gauge is at ${FinanceFormat.grouped(v, 0)}; under 20 counts as calm."

            "ccdelinq" -> "${pct(v)} of credit card balances are a month or more overdue."

            "debtgdp" -> "The government owes about \$${FinanceFormat.grouped(v, 0)} for every \$100 the country produces in a year."

            "interest" -> "About ${FinanceFormat.grouped(v, 0)} cents of every federal tax dollar now go to interest on the debt."

            "m2" -> if (v >= 0) "The amount of money in the economy is growing ${pct(v)} a year." else "The amount of money in the economy is shrinking ${pct(abs(v))} a year."

            "dff" -> "The Fed's interest rate is ${pct(v, 2)} — the starting point for credit cards, car loans and savings accounts."

            "mortgage" -> "A typical 30-year mortgage costs ${pct(v, 2)}. On a \$500,000 loan that's about ${FinanceFormat.money(monthlyPayment(500_000.0, v / 100, 30), 0)} a month before taxes and insurance."

            "homeprices" -> if (v >= 0) "Home prices are up ${pct(v)} from a year ago." else "Home prices are down ${pct(abs(v))} from a year ago."

            "dgs10" -> "The government pays ${pct(v, 2)} to borrow for 10 years. Mortgage rates usually run 1.5–2 points above this."

            "dgs2" -> "The government pays ${pct(v, 2)} to borrow for 2 years — roughly where investors expect the Fed's rate to be."

            "dgs30" -> "The government pays ${pct(v, 2)} to borrow for 30 years."

            else -> "The latest reading is ${value(r, v)}."
        }
    }

    /** Where the reading sits against its watch and danger lines, in numbers; null when it has none. */
    fun standing(r: IndicatorReading): String? {
        val v = r.latest ?: return null
        val th = r.indicator.thresholds ?: return null
        return when (th.signal(v)) {
            Signal.DANGER -> "That's past the danger line of ${value(r, th.danger)}."
            Signal.WATCH -> "That's past the watch line of ${value(r, th.watch)}; the danger line is ${value(r, th.danger)}."
            Signal.CALM -> "That's inside the calm range; the watch line is ${value(r, th.watch)}."
        }
    }

    /** A kind word on the reading, for the bright side: how it compares, with the good said first. */
    private fun judgement(r: IndicatorReading): String? {
        val v = r.latest ?: return null
        val signal = r.signal
        return when (r.indicator.id) {
            "cpi", "corecpi" -> when {
                v < 0 -> "Prices are actually falling, which stretches every dollar."
                v <= 2.2 -> "That's right around the Fed's 2% goal."
                v <= 3.5 -> "That's a bit faster than the Fed's 2% goal, and a long way below 2022's 9%."
                v <= 5 -> "That's well above the Fed's 2% goal, though inflation has come down from higher before."
                else -> "That's far above the Fed's 2% goal — the kind that squeezes budgets, and the kind the Fed acts hardest against."
            }

            "unrate" -> when {
                v < 4.5 -> "That's a strong job market."
                v < 5.5 -> "The job market is cooling, but ${FinanceFormat.grouped(100 - v, 1)} in 100 people who want work have it."
                else -> "Jobs are harder to find right now; this is when a cash cushion earns its keep."
            }

            "sahm" -> if (v >= 0.5) "The jobs alarm has rung, which has marked the start of recessions — and every one since 1990 has ended within 18 months." else "The jobs alarm, which rings at 0.5, is quiet."

            "icsa" -> when {
                v < 250 -> "That's a low, healthy level of layoffs."
                v < 300 -> "Layoffs have picked up a little from very low levels."
                else -> "That's a high level of layoffs."
            }

            "t10y2y", "t10y3m" -> if (v < 0) "This upside-down pattern has come before recessions, but the lead can run to two years, and savers earn more on short-term deposits meanwhile." else "That's the normal, healthy shape."

            "umcsent" -> when {
                v < 60 -> "That's gloomy, but mood isn't money: sentiment was near 50 in June 2022 with unemployment at 3.6%."
                v < 80 -> "That's below average — feelings often lag the numbers."
                else -> "That's upbeat."
            }

            "gdp" -> when {
                v >= 2 -> "That's healthy growth."
                v >= 0 -> "That's slow, but still growth."
                else -> "Every US recession since 1990 has ended within 18 months, and output went on to new highs after each."
            }

            "realwages" -> if (v >= 0) "That's a real raise, if the typical worker got one." else "Paychecks are trailing prices for now; after 2021–22's dip, real pay was growing again within 18 months."

            "ccdelinq" -> "Put the other way, ${pct(100 - v)} of card balances are being paid on time."

            else -> when (signal) {
                Signal.CALM -> "That's comfortably on the calm side."
                Signal.WATCH -> "That's worth watching, though it's short of the danger line."
                Signal.DANGER -> "That's in the danger zone, which has been where things turn around before."
                null -> null
            }
        }
    }

    /**
     * One plain sentence on what [r] says right now. Straight talk adds where it stands against its
     * lines; the bright side adds a kind word on it.
     */
    fun verdict(r: IndicatorReading, tone: EconomyTone): String {
        if (r.latest == null) return "Waiting for the latest reading."
        val second = when (tone) {
            EconomyTone.STRAIGHT -> standing(r)
            EconomyTone.BRIGHT_SIDE -> judgement(r)
        }
        return listOfNotNull(fact(r), second).joinToString(" ")
    }

    /** The verdict with which way it's heading, for an explainer's "Right now". */
    fun rightNow(r: IndicatorReading, tone: EconomyTone): String = listOf(verdict(r, tone), heading(r, tone)).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Today's reading against its own history: the share of past readings it's better or worse
     * than, how long since it was last this high or low, and what it was a year ago. Only numbers.
     */
    fun record(r: IndicatorReading): String? {
        val v = r.latest ?: return null
        val h = r.history
        val t = h.lastTime ?: return null
        if (h.size < 8) return null
        val since = FinanceFormat.monthYear(h.times.first())
        val th = r.indicator.thresholds
        val parts = mutableListOf<String>()
        val others = h.size - 1
        if (th != null) {
            val better = (0 until others).count { i -> if (th.higherIsWorse) h.values[i] < v else h.values[i] > v }
            val worse = (0 until others).count { i -> if (th.higherIsWorse) h.values[i] > v else h.values[i] < v }
            parts += if (better >= worse) {
                "Worse than ${better * 100 / others}% of readings since $since."
            } else {
                "Better than ${worse * 100 / others}% of readings since $since."
            }
        } else {
            val lower = (0 until others).count { h.values[it] < v }
            val higher = (0 until others).count { h.values[it] > v }
            parts += if (lower >= higher) "Higher than ${lower * 100 / others}% of readings since $since." else "Lower than ${higher * 100 / others}% of readings since $since."
        }
        extreme(h, v, t)?.let { parts += it }
        yearAgo(h, t)?.let { parts += "A year ago it was ${value(r, it)}." }
        return parts.joinToString(" ")
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
    private fun extreme(h: Series, v: Double, t: Long): String? {
        fun lastAtLeastAs(higher: Boolean): Long? {
            for (i in h.size - 2 downTo 0) if (if (higher) h.values[i] >= v else h.values[i] <= v) return h.times[i]
            return null
        }
        val yearAgo = t - Series.YEAR_SECONDS
        for (higher in listOf(true, false)) {
            val word = if (higher) "highest" else "lowest"
            val at = lastAtLeastAs(higher)
            if (at == null) return "The $word in the record."
            if (at < yearAgo) return "The $word since ${FinanceFormat.monthYear(at)}."
        }
        return null
    }

    /** The upsides a reading brings, or what tends to go right from here. Pragmatic, never advice. */
    fun brightSide(r: IndicatorReading): String? {
        val v = r.latest ?: return null
        val worse = r.signal == Signal.WATCH || r.signal == Signal.DANGER
        return when (r.indicator.id) {
            "cpi", "corecpi", "corepce" -> when {
                v < 0 -> "Falling prices stretch every dollar you've saved."
                !worse -> "Inflation near the Fed's goal leaves it room to cut rates, which makes borrowing cheaper."
                else -> "Inflation has come down fast before: from about 9% in mid-2022 to about 3% a year later. Meanwhile savings accounts and Treasury bills tend to pay more while it runs hot."
            }

            "unrate", "sahm", "icsa", "u6", "slackgap", "longterm", "insured", "primeepop" -> if (!worse) {
                "Most people who want work have it, and a tight job market is when raises and job switches come easiest."
            } else {
                "A cooling job market tends to bring the Fed to cut rates, which eases loan and card costs. Jobs have come back after every downturn: unemployment went from 14.8% in April 2020 to 3.6% by May 2022."
            }

            "quits", "openings" -> if (!worse) {
                "Workers still have options: when people feel free to quit, pay tends to keep rising."
            } else {
                "Slower hiring takes pressure off prices, and it usually comes before rate cuts that help borrowers. Staying put also builds seniority while the market is choosier."
            }

            "realwages" -> if (v >= 0) "Paychecks are outrunning prices, so the typical worker can afford a little more than a year ago." else "The last time paychecks trailed prices, in 2021–22, real pay was growing again within 18 months."

            "temphelp" -> if (v >= 0) "Businesses are adding temp staff, often the first step before permanent hiring." else "Temp jobs fell through 2023–24 with no recession following. On its own it's an early hint, not a verdict."

            "civpart" -> "Much of the long slide since 2000 is baby boomers retiring rather than jobs disappearing, which is why the share of 25–54s with a job is the better gauge."

            "gdp" -> if (!worse) "Growth means businesses are selling more, which is what pays for hiring and raises." else "Every US recession since 1990 lasted between 2 and 18 months, and the economy grew past its old peak after each."

            "umcsent" -> "Mood isn't money: sentiment was near 50 in June 2022 while unemployment sat at 3.6%. Gloom tends to track prices more than jobs."

            "t10y2y", "t10y3m" -> if (v < 0) "An inverted curve means short-term savings — high-yield accounts, CDs, Treasury bills — pay more than locking money away for years." else "A normal curve is what markets show when they expect steady growth."

            "hy", "stlfsi", "vix" -> if (!worse) "Calm markets mean businesses can borrow to expand and hire." else "Market panics have tended to be short: 2020's stress faded within months once the Fed stepped in."

            "ccdelinq" -> "${pct(100 - v)} of card balances are being paid on time."

            "debtgdp", "interest" -> "Debt is easier to carry while the economy grows faster than its interest bill, and the US borrows in a currency it issues."

            "m2" -> if (v >= 0) "Money growing at a steady pace keeps lending and spending going." else "The 2023 shrink followed record growth in 2020–21; the money supply stayed far above its pre-pandemic level."

            "dff", "dgs2" -> "When the Fed's rate is high, savings accounts and CDs pay more; when it's cut, borrowing gets cheaper. Either way someone in the household gains."

            "mortgage", "dgs10", "dgs30" -> if (!worse) "Financing is reasonable, which helps buyers and anyone refinancing." else "Higher rates have cooled bidding wars, giving buyers more room to negotiate, and today's savers and new bond buyers earn more."

            "homeprices" -> if (v >= 0) "Rising prices build equity for homeowners." else "Falling prices bring homes within reach of more buyers."

            else -> null
        }
    }

    /** What the detail page and the explainer add under "Right now": the record (straight talk) or the bright side. */
    fun perspective(r: IndicatorReading, tone: EconomyTone): Perspective? = when (tone) {
        EconomyTone.STRAIGHT -> record(r)?.let { Perspective("On the record", it) }
        EconomyTone.BRIGHT_SIDE -> brightSide(r)?.let { Perspective("The bright side", it) }
    }

    /** What [id]'s reading means for this household, in dollars where the sheet allows; null when there's nothing useful to say. */
    fun forYou(id: String, readings: Map<String, IndicatorReading>, quotes: Map<String, Quote>, finance: PersonalFinance?): String? {
        val r = readings[id]?.latest
        return when (id) {
            "cpi", "corecpi", "corepce" -> r?.let { inflation ->
                val spend = finance?.monthlyExpenses
                when {
                    inflation < 0 && spend != null -> {
                        val less = spend * abs(inflation) / (100 + inflation)
                        "Prices are falling: your ${FinanceFormat.money(spend, 0)} of monthly spending buys what would have cost about ${FinanceFormat.money(less, 0)} more a year ago."
                    }

                    inflation < 0 -> "Prices are falling: something that cost \$100 a year ago costs about ${FinanceFormat.money(100 + inflation, 2)} now."

                    spend != null -> {
                        val more = spend * inflation / (100 + inflation)
                        "If your ${FinanceFormat.money(spend, 0)} of monthly spending rose with prices, the same things would have cost about ${FinanceFormat.money(more, 0)} less a year ago. Raises below ${pct(inflation)} mean your pay buys less than it did."
                    }

                    else -> "Something that cost \$100 a year ago costs about ${FinanceFormat.money(100 + inflation, 2)} now. A raise smaller than ${pct(inflation)} means your pay buys less than it did."
                }
            }

            "dff", "realrate" -> {
                val ff = readings["dff"]?.latest ?: return null
                val cpi = readings["cpi"]?.latest
                val cash = finance?.liquidCash?.takeIf { it > 0 }
                val real = cpi?.let { ff - it }
                when {
                    cash != null && real != null ->
                        "High-yield savings accounts pay roughly the Fed's ${pct(ff, 2)}. On your ${FinanceFormat.compactMoney(cash)} in cash that's about ${FinanceFormat.money(cash * ff / 100, 0)} a year — " +
                            if (real >= 0) "a bit more than inflation takes away." else "less than inflation takes away."

                    else -> "Savings accounts tend to pay close to the Fed's rate, and credit cards charge it plus 15–20 points."
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
                    "For the ${FinanceFormat.compactMoney(plan.homePrice)} house in your planner, today's average rate means about ${FinanceFormat.money(atMarket, 0)} a month for the loan — " +
                        if (abs(diff) < 25) "about the same as the ${FinanceFormat.fractionPercent(plan.rate, 2)} in your plan." else "${FinanceFormat.money(abs(diff), 0)} ${if (diff > 0) "more" else "less"} than at the ${FinanceFormat.fractionPercent(plan.rate, 2)} in your plan."
                } else {
                    "Each 1-point change in mortgage rates moves the payment on a \$500,000 loan by about \$330 a month."
                }
            }

            "homeprices" -> {
                val change = r ?: return null
                val value = finance?.home?.value ?: return null
                val delta = value * change / (100 + change)
                "If your home tracked the national average, it's worth about ${FinanceFormat.money(abs(delta), 0)} ${if (delta >= 0) "more" else "less"} than a year ago."
            }

            "unrate", "sahm", "icsa", "u6", "slackgap", "longterm", "insured", "openings" -> finance?.runwayMonths?.let { months ->
                "Your cash would cover about ${FinanceFormat.grouped(months, 1)} months of expenses if a paycheck stopped" +
                    when {
                        months >= 6 -> " — a solid cushion."
                        months >= 3 -> " — within the 3–6 months planners suggest."
                        else -> " — planners suggest at least 3–6 months."
                    }
            }

            "sp500", "vix" -> {
                val sp = quotes[MarketCatalog.SP500.symbol] ?: return null
                val invested = finance?.accounts?.filter { it.category == AccountCategory.RETIREMENT || it.category == AccountCategory.INVESTING }?.sumOf { it.balance }?.takeIf { it > 0 }
                if (invested != null) {
                    "If your ${FinanceFormat.compactMoney(invested)} in investments moved with the S&P 500 today, that's roughly ${FinanceFormat.signedMoney(invested * sp.changePercent / 100, 0)}. Day-to-day moves like this are normal; what matters is the long run."
                } else {
                    "A typical 401(k) moves with this. Today's ${FinanceFormat.signedPercent(sp.changePercent)} on \$100,000 would be about ${FinanceFormat.signedMoney(1000 * sp.changePercent, 0)}."
                }
            }

            else -> null
        }
    }

    /** One sentence on today's move for [symbol], with a sense of whether it's a big day. */
    fun quoteVerdict(symbol: String, quote: Quote): String {
        val meta = MarketCatalog.lookup(symbol)
        val p = quote.changePercent
        val size = when {
            abs(p) < 0.3 -> "a quiet day"
            abs(p) < 1 -> "an ordinary day"
            abs(p) < 2 -> "a big day"
            else -> "a very big day"
        }
        val dir = if (p >= 0) "up" else "down"
        return "${meta.shortName} is $dir ${FinanceFormat.grouped(abs(p), 2)}% today — $size. Most days move less than 1%."
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
                "Prices",
                r.signal,
                when {
                    straight && v >= 0 -> "${pct(v)} higher than a year ago, against the Fed's 2% target."
                    straight -> "${pct(abs(v))} lower than a year ago, against the Fed's 2% target."
                    v < 0 -> "Prices are falling (${pct(abs(v))} lower than a year ago), so every dollar stretches further."
                    v <= 2.5 -> "Prices are rising at a normal pace (${pct(v)} a year)."
                    v <= 4 -> "Prices are rising a little faster than the 2% goal (${pct(v)} a year), well down from 2022."
                    else -> "Prices are rising fast (${pct(v)} a year); savings rates tend to rise with them."
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
                "Jobs",
                worst?.signal,
                if (straight) {
                    listOfNotNull(
                        un?.let { "Unemployment ${pct(it)}" },
                        u6?.let { "${pct(it)} counting the underemployed (U-6)" },
                        claims?.let { "${FinanceFormat.grouped(it, 0)}K new claims a week" },
                    ).joinToString("; ") + "."
                } else {
                    when (worst?.signal) {
                        Signal.DANGER -> "Layoffs are rising — the time an emergency fund earns its keep."
                        Signal.WATCH -> "The job market is cooling off a little" + (un?.let { ", though ${FinanceFormat.grouped(100 - it, 1)}% of the workforce is employed." } ?: ".")
                        else -> "Jobs are plentiful" + (un?.let { " — unemployment is ${pct(it)}." } ?: ".")
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
                "Beneath the headline",
                worst,
                when {
                    straight && flashing.isEmpty() -> "All ${slack.size} measures of hidden slack are inside their calm ranges."

                    straight ->
                        "${flashing.size} of ${slack.size} measures past a line: " +
                            flashing.take(3).joinToString(", ") { "${plainTitle(it.indicator.id).lowercase()} ${value(it, it.latest!!)}" } + "."

                    flashing.isEmpty() -> "Underemployment, long searches and hiring all look sound, so the headline isn't hiding weakness."

                    else ->
                        "$calm of ${slack.size} hidden-slack measures are calm; " +
                            flashing.take(2).joinToString(" and ") { plainTitle(it.indicator.id).lowercase() } + " are the soft spots."
                },
                "u6",
            )
        }
        readings["mortgage"]?.let { r ->
            val v = r.latest ?: return@let
            val ff = readings["dff"]?.latest
            items += BriefingItem(
                "Borrowing",
                r.signal,
                if (straight) {
                    "30-year mortgages ${pct(v, 2)}" + (ff?.let { "; the Fed's rate ${pct(it, 2)}." } ?: ".")
                } else {
                    when {
                        v >= 6.5 -> "Borrowing is pricey (mortgages around ${pct(v, 1)})" + (ff?.let { ", but savings pay close to the Fed's ${pct(it, 2)}." } ?: ".")
                        v >= 5 -> "Borrowing costs are moderate — mortgages around ${pct(v, 1)}."
                        else -> "Borrowing is cheap — mortgages around ${pct(v, 1)}."
                    }
                },
                "mortgage",
            )
        }
        val vix = readings["vix"]
        val sp = quotes[MarketCatalog.SP500.symbol]
        if (vix != null || sp != null) {
            val calm = (vix?.latest ?: 15.0) < 20
            val today = sp?.let { "the S&P 500 is ${if (it.change >= 0) "up" else "down"} ${FinanceFormat.grouped(abs(it.changePercent), 2)}% today" }
            items += BriefingItem(
                "Markets",
                vix?.signal,
                if (straight) {
                    listOfNotNull(vix?.latest?.let { "fear gauge ${FinanceFormat.grouped(it, 0)} (under 20 is calm)" }, today).joinToString("; ").replaceFirstChar { it.uppercase() } + "."
                } else {
                    (if (calm) "Stock markets are calm" else "Stock markets are jittery, which passes") + (today?.let { " — $it." } ?: ".")
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
                "Recession signs",
                signal,
                if (straight) {
                    listOfNotNull(
                        curve?.latest?.let { "Yield curve ${FinanceFormat.signedPercent(it)}" + if (it < 0) " (inverted)" else " (not inverted)" },
                        sahm?.latest?.let { "jobs alarm ${FinanceFormat.grouped(it.coerceAtLeast(0.0), 2)} (rings at 0.50)" },
                    ).joinToString("; ").replaceFirstChar { it.uppercase() } + "."
                } else {
                    when {
                        inverted && alarm -> "Both of the most reliable recession alarms are ringing; every recession since 1990 has ended within 18 months."
                        alarm -> "The jobs-based recession alarm is ringing, though it has rung early before."
                        inverted -> "The yield curve is upside down, an early warning with a long and uneven lead."
                        else -> "The two most reliable recession alarms are quiet."
                    }
                },
                "recession",
            )
        }
        readings["interest"]?.let { r ->
            val v = r.latest ?: return@let
            val debt = readings["debtgdp"]?.latest
            items += BriefingItem(
                "Government debt",
                r.signal,
                if (straight) {
                    "${FinanceFormat.grouped(v, 0)}¢ of every tax dollar goes to interest" + (debt?.let { "; debt is ${pct(it, 0)} of a year's output." } ?: ".")
                } else {
                    "About ${FinanceFormat.grouped(v, 0)}¢ of every tax dollar goes to interest" + if (v >= 20) " — a slow-moving strain rather than a sudden one." else "."
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
            items.isEmpty() -> "Gathering the latest readings…"
            straight -> "$dangers in danger · $watches to watch · $calms calm"
            dangers >= 3 -> "Stormy"
            dangers >= 1 -> "Mixed, with a storm or two"
            watches >= 3 -> "Mostly cloudy"
            watches >= 1 -> "Partly cloudy"
            else -> "Mostly sunny"
        }
        val summary = when {
            items.isEmpty() -> ""

            straight && stress != null ->
                "Composite stress ${FinanceFormat.grouped(stress.score, 0)} of 100 (${stress.label.lowercase()}) across ${stress.counted} readings: " +
                    "${stress.dangers} past a danger line, ${stress.watches} past a watch line."

            straight -> "$dangers of ${items.size} areas are past a danger line."

            stress == null -> "$calms of ${items.size} areas are calm."

            else -> "Overall stress is ${stress.label.lowercase()} (${FinanceFormat.grouped(stress.score, 0)} of 100), and ${stress.counted - stress.dangers - stress.watches} of ${stress.counted} readings are calm."
        }
        return Briefing(tone, headline, summary, items)
    }

    /** The plain name for an indicator, falling back to its own title. */
    fun plainTitle(id: String): String = Explainers.byId(id)?.title ?: IndicatorCatalog.byId(id)?.shortTitle ?: id
}

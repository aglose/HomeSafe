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

/** One line of the economic weather report. [explainerId] is what its ⓘ opens. */
@Immutable
data class BriefingItem(val topic: String, val weather: Weather, val sentence: String, val explainerId: String)

@Immutable
data class Briefing(val headline: String, val summary: String, val items: List<BriefingItem>)

/**
 * Turns readings into sentences a person who never reads the business pages can follow: a
 * one-line verdict for each indicator, which way it's heading, what it means for this household's
 * own money (when the budget sheet is in), and a short weather report on the whole economy.
 *
 * Every sentence is built from the live numbers; nothing here predicts anything.
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

    private fun pct(v: Double, decimals: Int = 1) = FinanceFormat.grouped(v, decimals) + "%"

    /** "and it's been climbing" / "and it's easing" — said in terms of whether that's good. */
    private fun heading(r: IndicatorReading): String {
        val t = trendOf(r)
        val worseUp = r.indicator.thresholds?.higherIsWorse
        return when (t) {
            Trend.STEADY -> "It's been about steady for six months."

            Trend.RISING -> if (worseUp == false) {
                "It's been improving over the past six months."
            } else if (worseUp == true) {
                "It's been climbing over the past six months — the wrong direction."
            } else {
                "It's been rising over the past six months."
            }

            Trend.FALLING -> if (worseUp == true) {
                "It's been easing over the past six months — a good sign."
            } else if (worseUp == false) {
                "It's been sliding over the past six months — the wrong direction."
            } else {
                "It's been falling over the past six months."
            }
        }
    }

    /** One plain sentence on what [r] says right now. */
    fun verdict(r: IndicatorReading): String {
        val v = r.latest ?: return "Waiting for the latest reading."
        return when (r.indicator.id) {
            "cpi", "corecpi" -> {
                val vs = when {
                    v < 0 -> "Prices are actually falling."
                    v <= 2.2 -> "That's right around the Fed's 2% goal."
                    v <= 3.5 -> "That's a bit faster than the Fed's 2% goal."
                    v <= 5 -> "That's well above the Fed's 2% goal."
                    else -> "That's far above the Fed's 2% goal — the kind that squeezes budgets."
                }
                // Deflation is said as prices lower, never "−0.5% higher".
                (if (v >= 0) "Prices are ${pct(v)} higher than a year ago. " else "Prices are ${pct(abs(v))} lower than a year ago. ") + vs
            }

            "corepce" -> if (v >= 0) {
                "The Fed's preferred measure says prices are rising ${pct(v)} a year, against its 2% target."
            } else {
                "The Fed's preferred measure says prices are falling ${pct(abs(v))} a year, below its 2% target."
            }

            "unrate" -> "About ${FinanceFormat.grouped(v, 1)} out of every 100 people who want a job can't find one." +
                if (v < 4.5) {
                    " That's a solid job market."
                } else if (v < 5.5) {
                    " That's softening."
                } else {
                    " That's a weak job market."
                }

            "sahm" -> if (v >= 0.5) {
                "The jobs alarm is ringing: unemployment is ${FinanceFormat.grouped(v, 2)} points above its low of the past year, past the 0.5 line that has marked every recession since 1970."
            } else {
                "Unemployment is ${FinanceFormat.grouped(v.coerceAtLeast(0.0), 2)} points above its low of the past year — the alarm rings at 0.5."
            }

            "icsa" -> "About ${FinanceFormat.grouped(v, 0)},000 people filed for unemployment for the first time last week." +
                if (v < 250) {
                    " That's a low, healthy level of layoffs."
                } else if (v < 300) {
                    " Layoffs are picking up."
                } else {
                    " That's a high level of layoffs."
                }

            "gdp" -> if (v >= 0) {
                "The economy grew at a ${pct(v)} yearly pace last quarter." + if (v >= 2) " That's healthy." else " That's sluggish."
            } else {
                "The economy shrank at a ${pct(abs(v))} yearly pace last quarter."
            }

            "umcsent" -> "People's mood about money scores ${FinanceFormat.grouped(v, 0)}, against a long-run average of about 85." +
                if (v < 60) {
                    " That's very gloomy."
                } else if (v < 80) {
                    " That's below average."
                } else {
                    " That's upbeat."
                }

            "t10y2y", "t10y3m" -> if (v < 0) {
                "Short-term borrowing costs more than long-term (by ${FinanceFormat.grouped(abs(v), 2)} points) — the upside-down pattern that has come before every recession since 1970."
            } else {
                "Long-term borrowing costs ${FinanceFormat.grouped(v, 2)} points more than short-term — the normal, healthy shape."
            }

            "hy" -> "Risky companies pay ${FinanceFormat.grouped(v, 1)} points more than the government to borrow." +
                if (v < 4.5) {
                    " Lenders are relaxed."
                } else if (v < 6) {
                    " Lenders are getting nervous."
                } else {
                    " Lenders are scared of defaults."
                }

            "stlfsi" -> if (v <= 0) {
                "The financial system is calmer than usual (${FinanceFormat.grouped(v, 2)}, where 0 is normal)."
            } else {
                "The financial system is under more strain than usual (${FinanceFormat.grouped(v, 2)}, where 0 is normal)."
            }

            "vix" -> "Investors expect " + (
                if (v < 20) {
                    "calm"
                } else if (v < 30) {
                    "choppy"
                } else {
                    "wild"
                }
                ) + " markets over the next month (fear gauge at ${FinanceFormat.grouped(v, 0)}; under 20 is calm)."

            "ccdelinq" -> "${pct(v)} of credit card balances are a month or more overdue." + if (v < 3.5) " Households are mostly keeping up." else " Households are falling behind."

            "debtgdp" -> "The government owes about \$${FinanceFormat.grouped(v, 0)} for every \$100 the country produces in a year."

            "interest" -> "About ${FinanceFormat.grouped(v, 0)} cents of every federal tax dollar now go to interest on the debt."

            "m2" -> if (v >= 0) "The amount of money in the economy is growing ${pct(v)} a year." else "The amount of money in the economy is shrinking ${pct(abs(v))} a year — rare, and hard on borrowers."

            "dff" -> "The Fed's interest rate is ${pct(v, 2)} — the starting point for credit cards, car loans and savings accounts."

            "mortgage" -> "A typical 30-year mortgage costs ${pct(v, 2)}. On a \$500,000 loan that's about ${FinanceFormat.money(monthlyPayment(500_000.0, v / 100, 30), 0)} a month before taxes and insurance."

            "homeprices" -> if (v >= 0) "Home prices are up ${pct(v)} from a year ago." else "Home prices are down ${pct(abs(v))} from a year ago."

            "dgs10" -> "The government pays ${pct(v, 2)} to borrow for 10 years. Mortgage rates usually run 1.5–2 points above this."

            "dgs2" -> "The government pays ${pct(v, 2)} to borrow for 2 years — roughly where investors expect the Fed's rate to be."

            "dgs30" -> "The government pays ${pct(v, 2)} to borrow for 30 years."

            else -> "The latest reading is ${FinanceFormat.indicator(v, r.indicator.unit)}."
        }
    }

    /** The verdict with which way it's heading, for an explainer's "Right now". */
    fun rightNow(r: IndicatorReading): String = verdict(r) + " " + heading(r)

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

            "unrate", "sahm", "icsa" -> finance?.runwayMonths?.let { months ->
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
     * The economy in a handful of lines, each a part of it with its weather: prices, jobs,
     * borrowing, markets, recession warnings and government debt. Missing readings are left out.
     */
    fun briefing(readings: Map<String, IndicatorReading>, stress: StressScore?, quotes: Map<String, Quote>): Briefing {
        val items = mutableListOf<BriefingItem>()
        readings["cpi"]?.let { r ->
            val v = r.latest ?: return@let
            items += BriefingItem(
                "Prices",
                Weather.of(r.signal),
                when {
                    v < 0 -> "Prices are falling (${pct(abs(v))} lower than a year ago) — rare, and usually a sign of a weak economy."
                    v <= 2.5 -> "Prices are rising at a normal pace (${pct(v)} a year)."
                    v <= 4 -> "Prices are still rising a little too fast (${pct(v)} a year vs the 2% goal)."
                    else -> "Prices are rising fast (${pct(v)} a year) — budgets are being squeezed."
                },
                "cpi",
            )
        }
        val jobs = listOfNotNull(readings["unrate"], readings["sahm"], readings["icsa"])
        if (jobs.isNotEmpty()) {
            val worst = jobs.maxByOrNull { it.stress ?: 0.0 }
            val un = readings["unrate"]?.latest
            items += BriefingItem(
                "Jobs",
                Weather.of(worst?.signal),
                when (worst?.signal) {
                    Signal.DANGER -> "The job market is weakening: layoffs are rising."
                    Signal.WATCH -> "The job market is cooling off a little."
                    else -> "Jobs are plentiful" + (un?.let { " — unemployment is ${pct(it)}." } ?: ".")
                },
                "unrate",
            )
        }
        readings["mortgage"]?.let { r ->
            val v = r.latest ?: return@let
            val ff = readings["dff"]?.latest
            items += BriefingItem(
                "Borrowing",
                Weather.of(r.signal),
                (
                    if (v >= 6.5) {
                        "Borrowing is expensive"
                    } else if (v >= 5) {
                        "Borrowing costs are moderate"
                    } else {
                        "Borrowing is cheap"
                    }
                    ) +
                    " — mortgages around ${pct(v, 1)}" + (ff?.let { ", the Fed's rate ${pct(it, 2)}." } ?: "."),
                "mortgage",
            )
        }
        val vix = readings["vix"]
        val sp = quotes[MarketCatalog.SP500.symbol]
        if (vix != null || sp != null) {
            val calm = (vix?.latest ?: 15.0) < 20
            items += BriefingItem(
                "Markets",
                Weather.of(vix?.signal),
                (if (calm) "Stock markets are calm" else "Stock markets are jittery") +
                    (sp?.let { " — the S&P 500 is ${if (it.change >= 0) "up" else "down"} ${FinanceFormat.grouped(abs(it.changePercent), 2)}% today." } ?: "."),
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
                Weather.of(signal),
                when {
                    inverted && alarm -> "Both of the most reliable recession alarms are ringing."
                    alarm -> "The jobs-based recession alarm is ringing."
                    inverted -> "The yield curve is upside down, an early recession warning."
                    else -> "The two most reliable recession alarms are quiet."
                },
                "recession",
            )
        }
        readings["interest"]?.let { r ->
            val v = r.latest ?: return@let
            items += BriefingItem(
                "Government debt",
                Weather.of(r.signal),
                "About ${FinanceFormat.grouped(v, 0)}¢ of every tax dollar goes to interest on the national debt" +
                    if (v >= 20) " — a growing strain." else ".",
                "interest",
            )
        }
        val dangers = items.count { it.weather == Weather.STORMY }
        val clouds = items.count { it.weather == Weather.CLOUDY }
        val headline = when {
            items.isEmpty() -> "Gathering the latest readings…"
            dangers >= 3 -> "Stormy"
            dangers >= 1 -> "Mixed, with a storm or two"
            clouds >= 3 -> "Mostly cloudy"
            clouds >= 1 -> "Partly cloudy"
            else -> "Mostly sunny"
        }
        val summary = when {
            items.isEmpty() -> ""
            stress == null -> "$dangers of ${items.size} areas are flashing red."
            else -> "Overall stress is ${stress.label.lowercase()} (${FinanceFormat.grouped(stress.score, 0)} of 100): ${stress.dangers} warning signs in the danger zone and ${stress.watches} worth watching."
        }
        return Briefing(headline, summary, items)
    }

    /** The plain name for an indicator, falling back to its own title. */
    fun plainTitle(id: String): String = Explainers.byId(id)?.title ?: IndicatorCatalog.byId(id)?.shortTitle ?: id
}

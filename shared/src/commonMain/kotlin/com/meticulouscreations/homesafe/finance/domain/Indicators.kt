package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable

/** How an indicator is shown: a raw level, or its change from a year before (a price index → inflation). */
enum class Transform { LEVEL, YEAR_OVER_YEAR }

/** How a reading is written. */
enum class IndicatorUnit { PERCENT, INDEX, THOUSANDS, RATIO }

/** Calm, worth watching, or flashing red. */
enum class Signal(val label: String) {
    CALM("Calm"),
    WATCH("Watch"),
    DANGER("Danger"),
}

/** The group an indicator sits in on the Risk tab. */
enum class IndicatorGroup(val title: String, val blurb: String) {
    RECESSION("Recession signals", "The readings that have turned before past downturns."),
    LABOR("Jobs beneath the headline", "Underemployment, long searches and hiring: the slack the unemployment rate leaves out."),
    CREDIT("Credit & market stress", "Where cracks in the financial system show first."),
    DEBT("Debt & money", "Whether the country's borrowing is getting away from it."),
    INFLATION("Inflation & rates", "What money costs, and what it's worth."),
}

/**
 * Where an indicator turns from calm to watch to danger. With [higherIsWorse] a reading at or
 * above [danger] is danger and at or above [watch] is watch; otherwise the same, downwards.
 */
@Immutable
data class Thresholds(val watch: Double, val danger: Double, val higherIsWorse: Boolean) {
    fun signal(value: Double): Signal = if (higherIsWorse) {
        when {
            value >= danger -> Signal.DANGER
            value >= watch -> Signal.WATCH
            else -> Signal.CALM
        }
    } else {
        when {
            value <= danger -> Signal.DANGER
            value <= watch -> Signal.WATCH
            else -> Signal.CALM
        }
    }

    /**
     * 0 (calm) to 1 (at or past danger): 0.5 at the watch line, and linear either side of it,
     * with the calm half as wide as the watch-to-danger gap. What the composite gauge averages.
     */
    fun stress(value: Double): Double {
        // Signed the way things get worse, so in both directions a positive distance past the
        // watch line, in watch-to-danger gaps, is worse.
        val gap = danger - watch
        if (gap == 0.0) {
            return when (signal(value)) {
                Signal.DANGER -> 1.0
                Signal.WATCH -> 0.5
                Signal.CALM -> 0.0
            }
        }
        val beyond = (value - watch) / gap
        return (0.5 + beyond * 0.5).coerceIn(0.0, 1.0)
    }
}

/** How an indicator built from two FRED series puts them together, first with second. */
enum class Combine {
    /** One series, used as it is. */
    NONE,

    /** First ÷ second × 100: interest as a share of tax revenue. */
    RATIO_PERCENT,

    /** First ÷ second: job openings per unemployed person. */
    RATIO,

    /** First − second: U-6 less U-3, the slack the headline rate leaves out. */
    DIFFERENCE,
    ;

    /**
     * [first] and [second] put together at [first]'s times, each against [second]'s reading at or
     * before it; a ratio's point is dropped where [second] is zero. [NONE] is [first] unchanged.
     */
    fun apply(first: Series, second: Series): Series = when (this) {
        NONE -> first
        RATIO_PERCENT -> first.combine(second) { a, b -> if (b == 0.0) null else a / b * 100.0 }
        RATIO -> first.combine(second) { a, b -> if (b == 0.0) null else a / b }
        DIFFERENCE -> first.combine(second) { a, b -> a - b }
    }
}

/**
 * One economic reading from FRED. [fredIds] is usually one series; a combined indicator names two
 * and puts them together as [combine] says. [startDate] bounds how far back it is fetched (a year
 * further for [Transform.YEAR_OVER_YEAR], which needs the year before).
 */
@Immutable
data class Indicator(
    val id: String,
    val title: String,
    val shortTitle: String,
    val fredIds: List<String>,
    val unit: IndicatorUnit,
    val group: IndicatorGroup,
    val transform: Transform = Transform.LEVEL,
    val thresholds: Thresholds? = null,
    /** Why it matters, in plain words. */
    val why: String,
    /** What the danger line means, said once on the detail page. */
    val dangerNote: String = "",
    /** A reference line to draw (the Fed's 2% target, a 0% inversion line). */
    val referenceLine: Double? = null,
    val referenceLabel: String = "",
    val combine: Combine = Combine.NONE,
    val startDate: String = "1990-01-01",
    /** Multiplies every reading, e.g. jobless claims to thousands. */
    val scale: Double = 1.0,
    /** How much the composite stress gauge leans on it. */
    val weight: Double = 1.0,
    /** Readings arrive this often, which is how stale data is judged and how changes are described. */
    val cadence: Cadence = Cadence.DAILY,
)

enum class Cadence(val label: String, val periodLabel: String) {
    DAILY("Daily", "day"),
    WEEKLY("Weekly", "week"),
    MONTHLY("Monthly", "month"),
    QUARTERLY("Quarterly", "quarter"),
}

/** Every FRED-backed reading the app follows. Thresholds are rules of thumb, explained on each page. */
object IndicatorCatalog {
    val yieldCurve10y2y = Indicator(
        id = "t10y2y", title = "Yield curve (10Y − 2Y)", shortTitle = "10Y−2Y",
        fredIds = listOf("T10Y2Y"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 0.25, danger = 0.0, higherIsWorse = false),
        why = "Normally lenders want more to lock money up for ten years than for two. When the 2-year pays more — an inverted curve — markets are betting the Fed will have to cut because a slowdown is coming. Every US recession since 1970 was preceded by one.",
        dangerNote = "Below 0% the curve is inverted. Recessions have tended to arrive 6–24 months after inversion, often just as the curve turns positive again.",
        referenceLine = 0.0, referenceLabel = "Inversion", startDate = "1990-01-01", weight = 1.5,
    )
    val yieldCurve10y3m = Indicator(
        id = "t10y3m", title = "Yield curve (10Y − 3M)", shortTitle = "10Y−3M",
        fredIds = listOf("T10Y3M"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 0.3, danger = 0.0, higherIsWorse = false),
        why = "The New York Fed's preferred recession model uses this spread. It compares the 10-year yield with 3-month bills, which track the Fed's policy rate almost exactly.",
        dangerNote = "Below 0% is inverted, the New York Fed model's strongest recession signal.",
        referenceLine = 0.0, referenceLabel = "Inversion", startDate = "1990-01-01", weight = 1.5,
    )
    val sahm = Indicator(
        id = "sahm", title = "Sahm rule", shortTitle = "Sahm",
        fredIds = listOf("SAHMREALTIME"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 0.3, danger = 0.5, higherIsWorse = true),
        why = "How far the 3-month average unemployment rate has risen above its low of the past year. Unemployment rarely rises a little: once it starts climbing it tends to keep going.",
        dangerNote = "At 0.5 points the rule has called every recession since 1970, usually within months of it starting.",
        referenceLine = 0.5, referenceLabel = "Recession trigger", startDate = "1990-01-01", weight = 1.5, cadence = Cadence.MONTHLY,
    )
    val unemployment = Indicator(
        id = "unrate", title = "Unemployment rate", shortTitle = "Unemployment",
        fredIds = listOf("UNRATE"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 4.5, danger = 5.5, higherIsWorse = true),
        why = "The share of people looking for work who can't find it. Jobs are what keep household spending — two thirds of the economy — going.",
        dangerNote = "Above 5.5% layoffs are usually feeding on themselves.",
        startDate = "1990-01-01", cadence = Cadence.MONTHLY,
    )
    val claims = Indicator(
        id = "icsa", title = "Initial jobless claims", shortTitle = "Jobless claims",
        fredIds = listOf("ICSA"), unit = IndicatorUnit.THOUSANDS, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 250.0, danger = 300.0, higherIsWorse = true),
        why = "New unemployment filings each week: the fastest hard data on layoffs, out every Thursday.",
        dangerNote = "Sustained readings over 300k have marked every recession of the past 50 years.",
        startDate = "2000-01-01", scale = 0.001, cadence = Cadence.WEEKLY,
    )
    val gdp = Indicator(
        id = "gdp", title = "Real GDP growth", shortTitle = "GDP growth",
        fredIds = listOf("A191RL1Q225SBEA"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 1.0, danger = 0.0, higherIsWorse = false),
        why = "How fast the economy grew last quarter after inflation, as an annual rate.",
        dangerNote = "Two quarters in a row below zero is the textbook definition of a recession.",
        referenceLine = 0.0, referenceLabel = "Contraction", startDate = "1990-01-01", cadence = Cadence.QUARTERLY,
    )
    val sentiment = Indicator(
        id = "umcsent", title = "Consumer sentiment", shortTitle = "Sentiment",
        fredIds = listOf("UMCSENT"), unit = IndicatorUnit.INDEX, group = IndicatorGroup.RECESSION,
        thresholds = Thresholds(watch = 70.0, danger = 55.0, higherIsWorse = false),
        why = "The University of Michigan's monthly survey of how people feel about their finances and the economy. Gloomy households stop spending.",
        dangerNote = "Below 55 is the territory of 1980, 2008 and 2022.",
        startDate = "1990-01-01", weight = 0.5, cadence = Cadence.MONTHLY,
    )

    // Jobs beneath the headline. The headline rate counts only people who looked for work in the
    // past four weeks and found none; these catch the part-timers who want more hours, the people
    // who gave up looking, how long searches are taking, and whether employers are still hiring.
    // Each one's lines are set from its own FRED history since 1990, and its danger note says
    // where the line sits against 2007 (the last year before a recession) and 2008–10.
    val u6 = Indicator(
        id = "u6", title = "Underemployment rate (U-6)", shortTitle = "U-6",
        fredIds = listOf("U6RATE"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.LABOR,
        thresholds = Thresholds(watch = 8.0, danger = 9.5, higherIsWorse = true),
        why = "The broadest official jobless rate: the unemployed, plus people working part-time who want full-time work, plus people who want a job but have stopped looking. It runs about twice the headline rate.",
        dangerNote = "U-6 averaged 8.3% in 2007 and passed 9.5% in May 2008, five months into that recession; it peaked at 17.2% in 2010 and 22.9% in April 2020.",
        startDate = "1994-01-01", weight = 1.0, cadence = Cadence.MONTHLY,
    )
    val slackGap = Indicator(
        id = "slackgap", title = "Hidden slack (U-6 minus U-3)", shortTitle = "Hidden slack",
        fredIds = listOf("U6RATE", "UNRATE"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.LABOR,
        combine = Combine.DIFFERENCE,
        thresholds = Thresholds(watch = 4.0, danger = 5.0, higherIsWorse = true),
        why = "The part of underemployment the headline rate doesn't count: involuntary part-timers and people who want work but aren't looking. When it widens, the job market is weaker than the headline says.",
        dangerNote = "The gap averaged 3.7 points in 2007 and passed 5 in October 2008; it peaked at 7.4 in 2011.",
        startDate = "1994-01-01", weight = 0.5, cadence = Cadence.MONTHLY,
    )
    val primeEpop = Indicator(
        id = "primeepop", title = "Prime-age employment rate (25–54)", shortTitle = "Prime-age employed",
        fredIds = listOf("LNS12300060"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.LABOR,
        thresholds = Thresholds(watch = 79.5, danger = 78.5, higherIsWorse = false),
        why = "The share of all 25-to-54-year-olds who have a job. It can't be flattered by people giving up the search, and the age band leaves out retirement and school, so it is the cleanest single read on how many people are working.",
        dangerNote = "It averaged 79.9% in 2007 and fell below 78.5% in October 2008, bottoming at 74.8% at the end of 2009 (69.6% in April 2020).",
        startDate = "1990-01-01", weight = 0.75, cadence = Cadence.MONTHLY,
    )
    val longTerm = Indicator(
        id = "longterm", title = "Long-term unemployed (27+ weeks)", shortTitle = "Long-term jobless",
        fredIds = listOf("LNS13025703"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.LABOR,
        thresholds = Thresholds(watch = 25.0, danger = 30.0, higherIsWorse = true),
        why = "Of the people who are unemployed, the share who have been looking for more than six months. A rising share means jobs are getting harder to find even if layoffs haven't picked up.",
        dangerNote = "It was about 18% in 2007 and passed 30% in 2009 on the way to 45% in 2010. It sat near 21% through 2019.",
        startDate = "1990-01-01", weight = 0.5, cadence = Cadence.MONTHLY,
    )
    val insuredUnemployment = Indicator(
        id = "insured", title = "Insured unemployment rate (continuing claims)", shortTitle = "Insured unemployment",
        fredIds = listOf("IURSA"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.LABOR,
        thresholds = Thresholds(watch = 2.0, danger = 2.5, higherIsWorse = true),
        why = "People still collecting unemployment benefits each week, as a share of the jobs that insurance covers. New claims say how many are being laid off; this says how many are failing to find the next job. As a rate it compares fairly across decades, where the raw count grows with the workforce.",
        dangerNote = "It averaged 1.9% in 2007, crossed 2% in December 2007 as that recession began and 2.5% in August 2008, peaking at 5.0% in mid-2009. It sat near 1.2% through 2019.",
        startDate = "1990-01-01", weight = 0.75, cadence = Cadence.WEEKLY,
    )
    val quits = Indicator(
        id = "quits", title = "Quits rate", shortTitle = "Quits",
        fredIds = listOf("JTSQUR"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.LABOR,
        thresholds = Thresholds(watch = 2.1, danger = 1.8, higherIsWorse = false),
        why = "The share of workers who quit their job each month. People quit when they're confident of finding something better, so it is workers' own vote on the job market.",
        dangerNote = "It was 2.1% in 2007 and fell under 1.8% in late 2008, bottoming at 1.2% in 2009. It peaked at 3.0% in 2021–22.",
        startDate = "2001-01-01", weight = 0.75, cadence = Cadence.MONTHLY,
    )
    val openings = Indicator(
        id = "openings", title = "Job openings per unemployed person", shortTitle = "Openings per seeker",
        fredIds = listOf("JTSJOL", "UNEMPLOY"), unit = IndicatorUnit.RATIO, group = IndicatorGroup.LABOR,
        combine = Combine.RATIO,
        thresholds = Thresholds(watch = 0.8, danger = 0.5, higherIsWorse = false),
        why = "Open jobs divided by people looking for one. Above 1 there are more jobs than job seekers; below 1 there aren't enough to go round.",
        dangerNote = "It averaged 0.66 in 2006–07, fell below 0.5 in May 2008 and bottomed at 0.15 in 2009. It peaked at 2.0 in 2022 and was 1.2 through 2019.",
        startDate = "2001-01-01", weight = 0.75, cadence = Cadence.MONTHLY,
    )
    val realWages = Indicator(
        id = "realwages", title = "Real weekly earnings growth", shortTitle = "Real pay",
        fredIds = listOf("LES1252881600Q"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.LABOR,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 0.5, danger = 0.0, higherIsWorse = false),
        why = "How much more the typical full-time worker's weekly pay buys than a year ago, after inflation. Below zero, paychecks are losing to prices.",
        dangerNote = "Below 0% the median full-time paycheck buys less than a year before, as through 2021–22. In recessions it can rise for a grim reason: lower-paid workers lose their jobs first, which lifts the median of those still working.",
        referenceLine = 0.0, referenceLabel = "Losing to prices", startDate = "1989-01-01", weight = 0.5, cadence = Cadence.QUARTERLY,
    )
    val tempHelp = Indicator(
        id = "temphelp", title = "Temporary-help jobs, year over year", shortTitle = "Temp jobs",
        fredIds = listOf("TEMPHELPS"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.LABOR,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 0.0, danger = -5.0, higherIsWorse = false),
        why = "Temp staff are the easiest workers to add and the first to let go, so temp-agency employment tends to turn before the rest of the job market.",
        dangerNote = "It turned negative in late 2000 and early 2007, months before the 2001 and 2008 recessions. It also fell through 2019 and by about 8% a year in 2023–24 with no recession following, so it is a lead, not a verdict.",
        referenceLine = 0.0, startDate = "1989-01-01", weight = 0.5, cadence = Cadence.MONTHLY,
    )
    val participation = Indicator(
        id = "civpart",
        title = "Labor force participation",
        shortTitle = "Participation",
        fredIds = listOf("CIVPART"),
        unit = IndicatorUnit.PERCENT,
        group = IndicatorGroup.LABOR,
        why = "The share of adults working or looking for work. It has drifted down since 2000 mostly because the population is ageing, so it has no warning lines; the prime-age employment rate is the cleaner read.",
        startDate = "1990-01-01",
        cadence = Cadence.MONTHLY,
    )

    val highYield = Indicator(
        id = "hy", title = "Junk bond spread", shortTitle = "HY spread",
        fredIds = listOf("BAMLH0A0HYM2"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.CREDIT,
        thresholds = Thresholds(watch = 4.5, danger = 6.0, higherIsWorse = true),
        why = "The extra yield the riskiest companies pay over Treasuries. When lenders get scared of defaults this blows out, and companies that need to borrow can't.",
        dangerNote = "Over 6% credit markets are pricing a wave of defaults (2008 peaked near 20%; March 2020 at 11%).",
        startDate = "1997-01-01", weight = 1.5,
    )
    val stress = Indicator(
        id = "stlfsi", title = "Financial stress index", shortTitle = "Stress index",
        fredIds = listOf("STLFSI4"), unit = IndicatorUnit.INDEX, group = IndicatorGroup.CREDIT,
        thresholds = Thresholds(watch = 0.0, danger = 1.0, higherIsWorse = true),
        why = "The St. Louis Fed's weekly blend of 18 market measures — rates, spreads, volatility. Zero is normal.",
        dangerNote = "Above 1 the financial system is under real strain; 2008 hit 5.",
        referenceLine = 0.0, referenceLabel = "Normal", startDate = "1994-01-01", weight = 1.5, cadence = Cadence.WEEKLY,
    )
    val vix = Indicator(
        id = "vix", title = "VIX fear gauge", shortTitle = "VIX",
        fredIds = listOf("VIXCLS"), unit = IndicatorUnit.INDEX, group = IndicatorGroup.CREDIT,
        thresholds = Thresholds(watch = 20.0, danger = 30.0, higherIsWorse = true),
        why = "Expected stock market volatility over the next 30 days, from options prices.",
        dangerNote = "Above 30 the market is afraid; panics push it past 50.",
        startDate = "1990-01-01",
    )
    val delinquency = Indicator(
        id = "ccdelinq", title = "Credit card delinquency", shortTitle = "Card delinquency",
        fredIds = listOf("DRCCLACBS"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.CREDIT,
        thresholds = Thresholds(watch = 3.5, danger = 4.5, higherIsWorse = true),
        why = "The share of credit card balances at least 30 days late at US banks: how stretched households are.",
        dangerNote = "Above 4.5% households are falling behind as they did going into 2008.",
        startDate = "1991-01-01", cadence = Cadence.QUARTERLY,
    )
    val debtToGdp = Indicator(
        id = "debtgdp", title = "Federal debt to GDP", shortTitle = "Debt / GDP",
        fredIds = listOf("GFDEGDQ188S"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.DEBT,
        thresholds = Thresholds(watch = 100.0, danger = 125.0, higherIsWorse = true),
        why = "Everything the federal government owes, against a year of national output.",
        dangerNote = "Over 125% the US is past its World War II peak (119% in 1946), and every rate rise lands on a bigger pile.",
        startDate = "1990-01-01", weight = 0.75, cadence = Cadence.QUARTERLY,
    )
    val interestBurden = Indicator(
        id = "interest", title = "Interest as share of tax revenue", shortTitle = "Interest burden",
        fredIds = listOf("A091RC1Q027SBEA", "W006RC1Q027SBEA"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.DEBT,
        thresholds = Thresholds(watch = 20.0, danger = 30.0, higherIsWorse = true),
        why = "How much of every tax dollar goes to paying interest on the national debt. Money spent on interest can't be spent on anything else, and borrowing to pay it is how debt spirals start.",
        dangerNote = "Above 30% the government spends more on interest than on almost anything else.",
        combine = Combine.RATIO_PERCENT, startDate = "1990-01-01", weight = 1.0, cadence = Cadence.QUARTERLY,
    )
    val m2 = Indicator(
        id = "m2", title = "Money supply growth (M2)", shortTitle = "M2 growth",
        fredIds = listOf("M2SL"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.DEBT,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 2.0, danger = 0.0, higherIsWorse = false),
        why = "How fast the amount of money in the economy is growing. Booms run on it; it shrank for the first time since the 1930s in 2023.",
        dangerNote = "Below 0% money is draining out of the economy — deflationary, and hard on borrowers.",
        referenceLine = 0.0, referenceLabel = "Shrinking", startDate = "1989-01-01", weight = 0.5, cadence = Cadence.MONTHLY,
    )
    val cpi = Indicator(
        id = "cpi", title = "Inflation (CPI)", shortTitle = "CPI",
        fredIds = listOf("CPIAUCSL"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 3.0, danger = 5.0, higherIsWorse = true),
        why = "How much prices rose over the past year, across everything a typical household buys.",
        dangerNote = "Above 5% inflation is eating real incomes and forcing the Fed's hand.",
        referenceLine = 2.0, referenceLabel = "Fed target", startDate = "1989-01-01", cadence = Cadence.MONTHLY,
    )
    val coreCpi = Indicator(
        id = "corecpi", title = "Core inflation (CPI ex food & energy)", shortTitle = "Core CPI",
        fredIds = listOf("CPILFESL"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 3.0, danger = 4.5, higherIsWorse = true),
        why = "Inflation without the swings of food and gas: the part that sticks.",
        referenceLine = 2.0, referenceLabel = "Fed target", startDate = "1989-01-01", weight = 0.75, cadence = Cadence.MONTHLY,
    )
    val corePce = Indicator(
        id = "corepce", title = "Core PCE inflation", shortTitle = "Core PCE",
        fredIds = listOf("PCEPILFE"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 2.75, danger = 4.0, higherIsWorse = true),
        why = "The inflation measure the Federal Reserve actually targets at 2%.",
        referenceLine = 2.0, referenceLabel = "Fed target", startDate = "1989-01-01", weight = 0.75, cadence = Cadence.MONTHLY,
    )
    val fedFunds = Indicator(
        id = "dff",
        title = "Fed funds rate",
        shortTitle = "Fed funds",
        fredIds = listOf("DFF"),
        unit = IndicatorUnit.PERCENT,
        group = IndicatorGroup.INFLATION,
        why = "The overnight rate the Federal Reserve sets. Every other interest rate in the country starts here.",
        startDate = "1990-01-01",
    )
    val mortgage = Indicator(
        id = "mortgage", title = "30-year mortgage rate", shortTitle = "Mortgage rate",
        fredIds = listOf("MORTGAGE30US"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        thresholds = Thresholds(watch = 6.5, danger = 7.5, higherIsWorse = true),
        why = "Freddie Mac's weekly average 30-year fixed rate: what buying a house costs to finance.",
        dangerNote = "Above 7.5% housing affordability is at its worst since the 1980s.",
        startDate = "1990-01-01", weight = 0.5, cadence = Cadence.WEEKLY,
    )
    val homePrices = Indicator(
        id = "homeprices", title = "Home prices (Case-Shiller)", shortTitle = "Home prices",
        fredIds = listOf("CSUSHPINSA"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        transform = Transform.YEAR_OVER_YEAR,
        thresholds = Thresholds(watch = 0.0, danger = -5.0, higherIsWorse = false),
        why = "US home prices over the past year. Homes are most families' biggest asset; falling prices were the fuse in 2008.",
        dangerNote = "Prices down 5% or more on a year ago means falling household wealth and underwater mortgages.",
        referenceLine = 0.0, startDate = "1989-01-01", weight = 0.75, cadence = Cadence.MONTHLY,
    )
    val tenYear = Indicator(
        id = "dgs10", title = "10-year Treasury yield", shortTitle = "10Y yield",
        fredIds = listOf("DGS10"), unit = IndicatorUnit.PERCENT, group = IndicatorGroup.INFLATION,
        thresholds = Thresholds(watch = 4.75, danger = 5.5, higherIsWorse = true),
        why = "The rate the world's safest borrower pays for ten years, and the anchor for mortgages and corporate debt. A disorderly surge means buyers are demanding more to hold US debt.",
        dangerNote = "Above 5.5% borrowing costs are at levels last seen before 2008.",
        startDate = "1990-01-01",
    )
    val twoYear = Indicator(
        id = "dgs2",
        title = "2-year Treasury yield",
        shortTitle = "2Y yield",
        fredIds = listOf("DGS2"),
        unit = IndicatorUnit.PERCENT,
        group = IndicatorGroup.INFLATION,
        why = "Where markets expect the Fed's rate to be over the next two years.",
        startDate = "1990-01-01",
    )
    val thirtyYear = Indicator(
        id = "dgs30",
        title = "30-year Treasury yield",
        shortTitle = "30Y yield",
        fredIds = listOf("DGS30"),
        unit = IndicatorUnit.PERCENT,
        group = IndicatorGroup.INFLATION,
        why = "The long bond: what investors want to lend to Washington for a generation.",
        startDate = "1990-01-01",
    )

    /** The readings that feed the Risk tab's gauge, by group. */
    val radar: List<Indicator> = listOf(
        yieldCurve10y2y, yieldCurve10y3m, sahm, unemployment, claims, gdp, sentiment,
        u6, slackGap, primeEpop, longTerm, insuredUnemployment, quits, openings, realWages, tempHelp,
        highYield, stress, vix, delinquency,
        debtToGdp, interestBurden, m2,
        cpi, coreCpi, corePce, mortgage, homePrices, tenYear,
    )

    val all: List<Indicator> = radar + listOf(fedFunds, twoYear, thirtyYear, participation)

    /** The labor readings the Economy tab lists under the unemployment chart. */
    val labor: List<Indicator> = listOf(u6, slackGap, primeEpop, longTerm, insuredUnemployment, quits, openings, realWages, tempHelp, participation)

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Indicator? = byId[id]
}

/** The Treasury curve's tenors, as FRED's constant-maturity series and how far out each is in years. */
@Immutable
data class Tenor(val label: String, val fredId: String, val years: Double)

object YieldCurve {
    val tenors = listOf(
        Tenor("1M", "DGS1MO", 1.0 / 12),
        Tenor("3M", "DGS3MO", 0.25),
        Tenor("6M", "DGS6MO", 0.5),
        Tenor("1Y", "DGS1", 1.0),
        Tenor("2Y", "DGS2", 2.0),
        Tenor("3Y", "DGS3", 3.0),
        Tenor("5Y", "DGS5", 5.0),
        Tenor("7Y", "DGS7", 7.0),
        Tenor("10Y", "DGS10", 10.0),
        Tenor("20Y", "DGS20", 20.0),
        Tenor("30Y", "DGS30", 30.0),
    )

    /** How far back the curve's tenors are fetched: enough to draw the curve as it stood two years ago. */
    const val START_DATE = "2023-06-01"
}

/** An indicator's latest reading, its history (transformed), and what it says now. */
@Immutable
data class IndicatorReading(
    val indicator: Indicator,
    val history: Series,
) {
    val latest: Double? get() = history.lastValue
    val latestEpochSeconds: Long? get() = history.lastTime
    val signal: Signal? get() = latest?.let { v -> indicator.thresholds?.signal(v) }
    val stress: Double? get() = latest?.let { v -> indicator.thresholds?.stress(v) }

    /** Change from the reading about a year ago, in the indicator's own units. */
    val yearChange: Double? get() {
        val t = latestEpochSeconds ?: return null
        val prior = history.valueAtOrBefore(t - Series.YEAR_SECONDS) ?: return null
        return latest!! - prior
    }

    /** Change from the reading before the latest. */
    val lastChange: Double? get() =
        if (history.size < 2) null else history.values[history.size - 1] - history.values[history.size - 2]
}

/** The composite: weighted mean stress of every reading that has thresholds, 0–100. */
@Immutable
data class StressScore(val score: Double, val counted: Int, val dangers: Int, val watches: Int) {
    val label: String get() = when {
        score >= 70 -> "Severe"
        score >= 50 -> "High"
        score >= 30 -> "Elevated"
        else -> "Calm"
    }

    companion object {
        fun of(readings: Collection<IndicatorReading>): StressScore? {
            var total = 0.0
            var weights = 0.0
            var dangers = 0
            var watches = 0
            var counted = 0
            for (r in readings) {
                val s = r.stress ?: continue
                total += s * r.indicator.weight
                weights += r.indicator.weight
                counted++
                when (r.signal) {
                    Signal.DANGER -> dangers++
                    Signal.WATCH -> watches++
                    else -> Unit
                }
            }
            if (counted == 0) return null
            return StressScore(total / weights * 100.0, counted, dangers, watches)
        }
    }
}

/** A stretch of time to shade on a chart, e.g. a recession. */
@Immutable
data class Period(val startEpochSeconds: Long, val endEpochSeconds: Long, val label: String)

/** The US recessions since 1990, as the National Bureau of Economic Research dates them (peak to trough). */
object Recessions {
    private const val DAY = 86_400L

    // Epoch days of each month's first day, so no date library is needed here.
    val us: List<Period> = listOf(
        Period(7486 * DAY, 7729 * DAY, "1990–91"),
        Period(11382 * DAY, 11627 * DAY, "2001"),
        Period(13848 * DAY, 14396 * DAY, "2008"),
        Period(18293 * DAY, 18353 * DAY, "COVID"),
    )
}

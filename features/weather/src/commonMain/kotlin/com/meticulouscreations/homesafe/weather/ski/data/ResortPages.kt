package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.weather.ski.domain.SkiDayPrice
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPriceTier
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortNews
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Reads what resorts publish on their own sites. Each family of sites has its own shape: Vail's
 * write their numbers into the page as JavaScript, Alterra's come from a feed, others are
 * plain pages. Whatever a page doesn't say is left null, so a resort's silence isn't taken for 0.
 */
internal object ResortPages {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** A page with its markup taken out and its spaces closed up: what a person reads. */
    fun textOf(html: String): String {
        val noScripts = SCRIPTS.replace(html, " ")
        val noTags = TAGS.replace(noScripts, " ")
        return SPACES.replace(entities(noTags), " ").trim()
    }

    private fun entities(text: String): String = NUMERIC_ENTITY.replace(
        text.replace("&quot;", "\"").replace("&#34;", "\"").replace("&apos;", "'").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&deg;", "°").replace("&amp;", "&"),
    ) { match -> match.groupValues[1].toIntOrNull()?.let { code -> if (code in 32..0xFFFF) code.toChar().toString() else " " } ?: " " }

    private val SCRIPTS = Regex("<(script|style|noscript)\\b.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private val NUMERIC_ENTITY = Regex("&#(\\d+);")

    /** The JSON value that follows `name =` in a page's scripts. */
    private fun jsonAfter(html: String, name: String): JsonElement? {
        val at = Regex(Regex.escape(name) + "\\s*=\\s*").find(html) ?: return null
        val start = at.range.last + 1
        val end = endOfJson(html, start) ?: return null
        return runCatching { json.parseToJsonElement(html.substring(start, end)) }.getOrNull()
    }

    /** Where the object or array starting at [start] closes. */
    private fun endOfJson(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> if (--depth == 0) return i + 1
            }
        }
        return null
    }

    // ---- Vail's sites -----------------------------------------------------------------------------

    /**
     * The snow from a Vail resort's "snow and weather report" page, where it's set as
     * `FR.snowReportData`. [terrainHtml] is its "terrain and lift status" page, for what's turning.
     * Its inches are what the resort reports; centimetres are its rounding of them.
     */
    fun vailReport(html: String, terrainHtml: String?, pageUrl: String, zone: TimeZone): SkiResortNews? {
        val snow = jsonAfter(html, "FR.snowReportData") as? JsonObject ?: return null
        val terrain = terrainHtml?.let { jsonAfter(it, "TerrainStatusFeed") } as? JsonObject
        val lifts = (terrain?.get("Lifts") as? JsonArray).orEmpty().map { (it as? JsonObject)?.whole("Status") }
        val trails = (terrain?.get("GroomingAreas") as? JsonArray).orEmpty().flatMap { ((it as? JsonObject)?.get("Trails") as? JsonArray).orEmpty() }
        return SkiResortNews(
            pageUrl = pageUrl,
            last12Cm = snow.vailDepth("OvernightSnowfall"),
            last24Cm = snow.vailDepth("TwentyFourHourSnowfall"),
            last48Cm = snow.vailDepth("FortyEightHourSnowfall"),
            last7DaysCm = snow.vailDepth("SevenDaySnowfall"),
            seasonCm = snow.vailDepth("CurrentSeason"),
            baseDepthCm = snow.vailDepth("BaseDepth"),
            conditions = snow.text("OverallSnowConditions"),
            // Closed 0, Open 1, On-Hold 2, Scheduled 3, Unknown 4, as the page's own filters have them.
            liftsOpen = lifts.count { it == 1 || it == 2 || it == 3 }.takeIf { lifts.isNotEmpty() },
            liftsTotal = lifts.size.takeIf { it > 0 },
            // Out of season the list is down to a summer trail or two, which isn't the mountain: the listing's total is better.
            runsOpen = trails.count { (it as? JsonObject)?.flag("IsOpen") == true }.takeIf { trails.size >= MIN_WINTER_TRAILS },
            runsTotal = trails.size.takeIf { it >= MIN_WINTER_TRAILS },
            updatedAtEpochSeconds = snow.text("LastUpdatedText")?.let { longDate(it, zone) },
        )
    }

    private const val MIN_WINTER_TRAILS = 10

    private fun JsonObject.vailDepth(key: String): Double? = (this[key] as? JsonObject)?.number("Inches")?.times(CM_PER_INCH)

    // ---- Alterra's feed -----------------------------------------------------------------------------

    /**
     * A resort's report from the feed Alterra's sites draw theirs from. The three areas carry
     * their own totals; a resort's headline figures are the most any area reports. Inches are
     * what's reported: where one is `--`, its centimetres are 0 by default, not a measurement.
     */
    fun feedReport(feed: String, pageUrl: String): SkiResortNews? {
        val root = runCatching { json.parseToJsonElement(feed) }.getOrNull() as? JsonObject ?: return null
        val resort = (root["Resorts"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val report = resort["SnowReport"] as? JsonObject ?: return null
        val areas = listOfNotNull(report["BaseArea"], report["MidMountainArea"], report["SummitArea"]).filterIsInstance<JsonObject>()
        fun most(key: String) = areas.mapNotNull { it.reportedInches(key) }.maxOrNull()?.times(CM_PER_INCH)
        return SkiResortNews(
            pageUrl = pageUrl,
            last24Cm = most("Last24HoursIn"),
            last48Cm = most("Last48HoursIn"),
            last72Cm = most("Last72HoursIn"),
            last7DaysCm = most("Last7DaysIn"),
            seasonCm = report.reportedInches("SeasonTotalIn")?.times(CM_PER_INCH),
            baseDepthCm = (report["BaseArea"] as? JsonObject)?.reportedInches("BaseIn")?.times(CM_PER_INCH),
            middleDepthCm = (report["MidMountainArea"] as? JsonObject)?.reportedInches("BaseIn")?.times(CM_PER_INCH),
            summitDepthCm = (report["SummitArea"] as? JsonObject)?.reportedInches("BaseIn")?.times(CM_PER_INCH),
            conditions = report.text("BaseConditions")?.takeUnless { it == NO_VALUE },
            liftsOpen = report.whole("TotalOpenLifts"),
            liftsTotal = report.whole("TotalLifts"),
            runsOpen = report.whole("TotalOpenTrails"),
            runsTotal = report.whole("TotalTrails"),
            running = resort.text("OperatingStatus")?.let { !it.equals("Closed", ignoreCase = true) },
            updatedAtEpochSeconds = report.text("LastUpdate")?.let(::isoInstant),
        )
    }

    private fun JsonObject.reportedInches(key: String): Double? = text(key)?.takeUnless { it == NO_VALUE }?.toDoubleOrNull()

    private const val NO_VALUE = "--"

    /** `2026-10-29T17:47:43-0700`, whose offset has no colon, which is more than [Instant.parse] takes. */
    private fun isoInstant(text: String): Long? {
        val fixed = Regex("([+-]\\d{2})(\\d{2})$").replace(text) { "${it.groupValues[1]}:${it.groupValues[2]}" }
        return runCatching { Instant.parse(fixed).epochSeconds }.getOrNull()
    }

    // ---- Plain pages --------------------------------------------------------------------------------

    /** Sugar Bowl's conditions page: its summit's figures, then its base's, each with its own labels. */
    fun sugarBowlReport(html: String, pageUrl: String, zone: TimeZone): SkiResortNews? {
        val text = textOf(html)
        val from = text.indexOf("Last Updated: ").takeIf { it >= 0 } ?: return null
        val body = text.substring(from)
        val summit = body.substringAfter("Summit ", "").substringBefore(" Base 6,")
        val base = body.substringAfter(" Base 6,", "")
        fun inches(part: String, label: String) = Regex("([\\d.]+) \" $label").find(part)?.groupValues?.get(1)?.toDoubleOrNull()?.times(CM_PER_INCH)
        fun count(label: String) = Regex("(\\d+) /(\\d+) $label").find(body)?.groupValues
        val lifts = count("Lifts Open")
        val runs = count("Trails Open")
        return SkiResortNews(
            pageUrl = pageUrl,
            last12Cm = inches(summit, "Overnight"),
            last24Cm = inches(summit, "24 Hour"),
            last7DaysCm = inches(summit, "7-Day"),
            seasonCm = inches(summit, "Year to Date"),
            baseDepthCm = inches(base, "Base Depth"),
            summitDepthCm = inches(summit, "Base Depth"),
            conditions = Regex("Surface Conditions (.+?) Road Conditions").find(body)?.groupValues?.get(1),
            liftsOpen = lifts?.get(1)?.toIntOrNull(),
            liftsTotal = lifts?.get(2)?.toIntOrNull(),
            runsOpen = runs?.get(1)?.toIntOrNull(),
            runsTotal = runs?.get(2)?.toIntOrNull(),
            running = Regex("Mountain Status (\\w+)").find(body)?.groupValues?.get(1)?.let { !it.equals("Closed", ignoreCase = true) },
            updatedAtEpochSeconds = Regex("Last Updated: (\\d{2}/\\d{2}/\\d{4} \\d{1,2}:\\d{2} [AP]M)").find(body)?.groupValues?.get(1)?.let { slashDate(it, zone) },
        )
    }

    /** Sierra-at-Tahoe's report: a table of summit and base for each length of time, then what's open and when it was written. */
    fun sierraReport(html: String, pageUrl: String, zone: TimeZone): SkiResortNews? {
        val text = textOf(html)
        val from = text.indexOf("Summit Base 12 Hour").takeIf { it >= 0 } ?: return null
        val body = text.substring(from)
        fun pair(label: String) = Regex("$label ([\\d.]+|–)[″\"] ?([\\d.]+|–)?[″\"]?").find(body)?.groupValues
        fun summit(label: String) = pair(label)?.get(1)?.toDoubleOrNull()?.times(CM_PER_INCH)
        fun base(label: String) = pair(label)?.get(2)?.toDoubleOrNull()?.times(CM_PER_INCH)
        fun count(label: String) = Regex("$label (\\d+) /(\\d+)").find(body)?.groupValues
        val lifts = count("Lifts Open")
        val runs = count("Runs Open")
        return SkiResortNews(
            pageUrl = pageUrl,
            last12Cm = summit("12 Hour"),
            last24Cm = summit("24 Hour"),
            last48Cm = summit("48 Hour"),
            last7DaysCm = summit("7-Day"),
            seasonCm = Regex("YTD ([\\d.]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.times(CM_PER_INCH),
            summitDepthCm = summit("Depth"),
            baseDepthCm = base("Depth"),
            liftsOpen = lifts?.get(1)?.toIntOrNull(),
            liftsTotal = lifts?.get(2)?.toIntOrNull(),
            runsOpen = runs?.get(1)?.toIntOrNull(),
            runsTotal = runs?.get(2)?.toIntOrNull(),
            updatedAtEpochSeconds = Regex("[A-Z][a-z]{2} ([A-Z][a-z]{2} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2} [AP]M) Snow Reporter").find(body)?.groupValues?.get(1)?.let { shortMonthDate(it, zone) },
        )
    }

    /**
     * Sugar Bowl's ticket page: one table with Sun-Fri and Sat+Holiday, online-from and window. Its
     * online figures are the lowest they start at, so they're marked that way.
     */
    fun sugarBowlPrices(html: String): List<SkiPriceTier> {
        val text = textOf(html)
        val row = Regex("Sat\\+Holiday Window Rates (.+?) Super Senior").find(text)?.groupValues?.get(1) ?: return emptyList()
        fun prices(label: String) = Regex("$label \\([^)]*\\) \\$(\\d+) \\$(\\d+) \\$(\\d+) \\$(\\d+)").find(row)?.groupValues?.drop(1)?.map { it.toDouble() }
        val adult = prices("Adult") ?: return emptyList()
        val child = prices("Child")
        return listOf(
            SkiPriceTier(SkiPriceTier.Kind.SUNDAY_TO_FRIDAY, SkiDayPrice(adultOnline = adult[0], adultWindow = adult[1], childOnline = child?.get(0), childWindow = child?.get(1)), onlineIsLowest = true),
            SkiPriceTier(SkiPriceTier.Kind.SATURDAY, SkiDayPrice(adultOnline = adult[2], adultWindow = adult[3], childOnline = child?.get(2), childWindow = child?.get(3)), onlineIsLowest = true),
        )
    }

    /** Sierra-at-Tahoe's rate table: a single-day ticket as non-peak and peak. Which days are which it doesn't say here. */
    fun sierraPrices(html: String): List<SkiPriceTier> {
        val text = textOf(html)
        val table = text.substringAfter("Non-Peak/Peak Lift Ticket", "").takeIf { it.isNotEmpty() } ?: return emptyList()
        fun prices(label: String) = Regex("$label \\([^)]*\\) \\$(\\d+)/\\$?(\\d+)").find(table)?.groupValues?.drop(1)?.map { it.toDouble() }
        val adult = prices("Adult") ?: return emptyList()
        val child = prices("Child")
        return listOf(
            SkiPriceTier(SkiPriceTier.Kind.NON_PEAK, SkiDayPrice(adultWindow = adult[0], childWindow = child?.get(0))),
            SkiPriceTier(SkiPriceTier.Kind.PEAK, SkiDayPrice(adultWindow = adult[1], childWindow = child?.get(1))),
        )
    }

    /** A POWDR mountain's latest snow report (its `dor/drupal/snow-reports`) with its lifts and trails (`dor/lift-trail-report`). */
    fun powdrReport(reports: String, liftTrail: String?, pageUrl: String): SkiResortNews? {
        val root = runCatching { json.parseToJsonElement(reports) }.getOrNull() as? JsonArray ?: return null
        val latest = root.firstOrNull() as? JsonObject ?: return null
        val computed = latest["computed"] as? JsonObject
        fun inches(key: String) = computed?.number(key)?.times(CM_PER_INCH)
        val status = liftTrail?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        fun open(key: String) = (status?.get(key) as? JsonArray)?.count { (it as? JsonObject)?.text("status")?.equals("open", ignoreCase = true) == true }
        fun all(key: String) = (status?.get(key) as? JsonArray)?.size?.takeIf { it > 0 }
        return SkiResortNews(
            pageUrl = pageUrl,
            last12Cm = latest.number("overnight_amount")?.times(CM_PER_INCH),
            last24Cm = inches("24_hour"),
            last48Cm = inches("48_hour"),
            last72Cm = inches("72_hour"),
            last7DaysCm = inches("7_day"),
            seasonCm = inches("total"),
            baseDepthCm = latest.number("base_depth")?.times(CM_PER_INCH),
            liftsOpen = open("lift").takeIf { all("lift") != null },
            liftsTotal = all("lift"),
            runsOpen = open("trail").takeIf { all("trail") != null },
            runsTotal = all("trail"),
            updatedAtEpochSeconds = latest.number("updated")?.toLong(),
        )
    }

    /** Mt. Rose's XML feed. Its depths are written as ranges (`30-44"`), the bottom of the mountain to the top. */
    fun roseReport(xml: String, pageUrl: String, zone: TimeZone): SkiResortNews? {
        fun tag(name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
        fun numbers(text: String?) = text?.let { Regex("[\\d.]+").findAll(it).mapNotNull { n -> n.value.toDoubleOrNull() }.toList() }.orEmpty()
        val base = numbers(tag("Base_Depth"))
        tag("Updated_Time") ?: return null
        val lifts = Regex("<\\w+>(\\w+)</\\w+>").findAll(tag("Lift_Status").orEmpty()).map { it.groupValues[1] }.toList()
        return SkiResortNews(
            pageUrl = pageUrl,
            last24Cm = numbers(tag("New_Snow")).maxOrNull()?.times(CM_PER_INCH),
            seasonCm = numbers(tag("Season_Total")).maxOrNull()?.times(CM_PER_INCH),
            baseDepthCm = base.firstOrNull()?.times(CM_PER_INCH),
            summitDepthCm = base.lastOrNull()?.times(CM_PER_INCH),
            liftsOpen = lifts.count { !it.equals("Closed", ignoreCase = true) }.takeIf { lifts.isNotEmpty() },
            liftsTotal = lifts.size.takeIf { it > 0 },
            updatedAtEpochSeconds = tag("Updated_Time")?.let { longDateLowercase(it, zone) },
        )
    }

    /** Homewood's table: Base, then Summit, each with 12 hour, 24 hour, depth and season figures. */
    fun homewoodReport(html: String, pageUrl: String): SkiResortNews? {
        val text = textOf(html)
        val table = text.substringAfter("Season Total Base ", "").takeIf { it.isNotEmpty() } ?: return null
        val row = Regex("([\\d.]+) in")
        val base = row.findAll(table.substringBefore("Summit ")).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        val summit = row.findAll(table.substringAfter("Summit ", "").take(TABLE_ROW_LENGTH)).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        if (base.size < 4) return null
        return SkiResortNews(
            pageUrl = pageUrl,
            last12Cm = summit.getOrNull(0)?.times(CM_PER_INCH),
            last24Cm = summit.getOrNull(1)?.times(CM_PER_INCH),
            summitDepthCm = summit.getOrNull(2)?.times(CM_PER_INCH),
            seasonCm = summit.getOrNull(3)?.times(CM_PER_INCH),
            baseDepthCm = base[2] * CM_PER_INCH,
        )
    }

    /** Donner Ski Ranch's lift status page: two totals, a date, and a line for each chair. */
    fun donnerRanchReport(html: String, pageUrl: String, zone: TimeZone): SkiResortNews? {
        val text = textOf(html)
        val last24 = Regex("24-hour snow total: ([\\d.]+) in").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val chairs = Regex("Chair \\d+: (\\w+)").findAll(text).map { it.groupValues[1] }.toList()
        return SkiResortNews(
            pageUrl = pageUrl,
            last24Cm = last24 * CM_PER_INCH,
            seasonCm = Regex("Season total: ([\\d.]+) in").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.times(CM_PER_INCH),
            liftsOpen = chairs.count { !it.equals("Closed", ignoreCase = true) }.takeIf { chairs.isNotEmpty() },
            liftsTotal = chairs.size.takeIf { it > 0 },
            updatedAtEpochSeconds = Regex("Updated (\\d{2})/(\\d{2})/(\\d{4})").find(text)?.groupValues?.let { instant(it[1].toInt(), it[2], it[3], "12", "00", "P", zone) },
        )
    }

    /** Tahoe Donner's snow and weather page. Its date has no year, so it's taken to be the latest such date there's been. */
    fun tahoeDonnerReport(html: String, pageUrl: String, zone: TimeZone, nowEpochSeconds: Long): SkiResortNews? {
        val text = textOf(html)
        val m = Regex("Snow Report ([\\d.]+) inches 24 HRS ([\\d.]+) inches 48 HRS ([\\d.]+) inches BASE").find(text)?.groupValues ?: return null
        return SkiResortNews(
            pageUrl = pageUrl,
            last24Cm = m[1].toDouble() * CM_PER_INCH,
            last48Cm = m[2].toDouble() * CM_PER_INCH,
            baseDepthCm = m[3].toDouble() * CM_PER_INCH,
            updatedAtEpochSeconds = Regex("Report updated: \\w+ ([A-Z][a-z]{2}) (\\d{1,2}) \\| (\\d{1,2}):(\\d{2}) ([ap])m").find(text)?.groupValues?.let { d ->
                val year = Instant.fromEpochSeconds(nowEpochSeconds).toString().take(4).toInt()
                val month = MONTHS.indexOf(d[1]) + 1
                val thisYear = instant(month, d[2], year.toString(), d[3], d[4], d[5].uppercase(), zone)
                if (thisYear != null && thisYear > nowEpochSeconds + 86_400) instant(month, d[2], (year - 1).toString(), d[3], d[4], d[5].uppercase(), zone) else thisYear
            },
        )
    }

    /** Diamond Peak's mountain report: its labels, each followed by a figure in inches. */
    fun diamondPeakReport(html: String, pageUrl: String): SkiResortNews? {
        val text = textOf(html).substringAfter("Mountain Report New Snow", "").takeIf { it.isNotEmpty() }?.let { "New Snow$it" } ?: return null
        fun inches(label: String) = Regex("$label ([\\d.]+) Inches").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.times(CM_PER_INCH)
        return SkiResortNews(
            pageUrl = pageUrl,
            last12Cm = inches("New Snow Overnight"),
            last24Cm = inches("New Snow 24H Total") ?: return null,
            baseDepthCm = inches("Base Depth"),
            summitDepthCm = inches("Peak Depth"),
            seasonCm = inches("Season Total"),
        )
    }

    /** Dodge Ridge's snow report: three totals and no depth. */
    fun dodgeRidgeReport(html: String, pageUrl: String): SkiResortNews? {
        val text = textOf(html).substringAfter("Snowfall Totals", "").takeIf { it.isNotEmpty() } ?: return null
        fun inches(label: String) = Regex("$label ([\\d.]+)[″\"]").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.times(CM_PER_INCH)
        return SkiResortNews(pageUrl = pageUrl, last24Cm = inches("24 hrs") ?: return null, last72Cm = inches("72 hrs"), seasonCm = inches("Season Total"))
    }

    /** China Peak's conditions: totals under "Snow Totals:", and a status for each chair. */
    fun chinaPeakReport(html: String, pageUrl: String): SkiResortNews? {
        val text = textOf(html)
        fun inches(label: String) = Regex("$label: ([\\d.]+)\"").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.times(CM_PER_INCH)
        val chairs = Regex("Chair \\w+: (\\w+)").findAll(text).map { it.groupValues[1] }.toList()
        return SkiResortNews(
            pageUrl = pageUrl,
            last24Cm = inches("Last 24 Hours") ?: return null,
            last7DaysCm = inches("Last 7 Days"),
            seasonCm = inches("Season Total"),
            liftsOpen = chairs.count { !it.equals("CLOSED", ignoreCase = true) }.takeIf { chairs.isNotEmpty() },
            liftsTotal = chairs.size.takeIf { it > 0 },
        )
    }

    /** Donner Ski Ranch's rate table: all-day tickets, sold only at the window. Its year is printed above it. */
    fun donnerRanchPrices(html: String): List<SkiPriceTier> {
        val text = textOf(html).substringAfter("Lift Tickets All Day", "")
        fun price(label: String) = Regex("$label \\([^)]*\\) \\$(\\d+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val adult = price("Adult") ?: return emptyList()
        return listOf(SkiPriceTier(SkiPriceTier.Kind.ANY_DAY, SkiDayPrice(adultWindow = adult, childWindow = price("Child"))))
    }

    /** China Peak's single-day passes: "as low as" online, "up to" at the resort. */
    fun chinaPeakPrices(html: String): List<SkiPriceTier> {
        val text = textOf(html).substringAfter("SINGLE DAY PASSES", "")
        fun prices(label: String) = Regex("$label AGES [\\d-]+ Pricing as low as \\$(\\d+).*?Up to \\$(\\d+)").find(text)?.groupValues
        val adult = prices("ADULT") ?: return emptyList()
        val child = prices("CHILD")
        return listOf(
            SkiPriceTier(
                SkiPriceTier.Kind.ANY_DAY,
                SkiDayPrice(adultOnline = adult[1].toDouble(), adultWindow = adult[2].toDouble(), childOnline = child?.get(1)?.toDouble(), childWindow = child?.get(2)?.toDouble()),
                onlineIsLowest = true,
            ),
        )
    }

    /** A POWDR mountain's page data lists what it sells with a price; the day ticket's is a "starting at". */
    fun powdrPrices(pageData: String): List<SkiPriceTier> {
        val m = Regex("Lift Ticket[^{}]*?\"price\":\"\\$(\\d+)\",\"priceDescription\":\"\\(starting at\\)\"").find(pageData)?.groupValues ?: return emptyList()
        return listOf(SkiPriceTier(SkiPriceTier.Kind.ANY_DAY, SkiDayPrice(adultOnline = m[1].toDouble()), onlineIsLowest = true))
    }

    // ---- What a shop's browser hands back -----------------------------------------------------------

    /** The dated prices the shop scripts ([ResortScripts]) return: `{"2026-12-26":[adultOnline,adultWindow,childOnline,childWindow]}`. */
    fun datedPrices(answer: String): Map<Int, SkiDayPrice> {
        val root = runCatching { json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return emptyMap()
        return root.entries.mapNotNull { (date, values) ->
            val day = runCatching { LocalDate.parse(date).toEpochDays().toInt() }.getOrNull() ?: return@mapNotNull null
            val list = (values as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { value -> value > 0 } } ?: return@mapNotNull null
            if (list.size < 4 || list.all { it == null }) return@mapNotNull null
            day to SkiDayPrice(list[0], list[1], list[2], list[3])
        }.toMap()
    }

    /** The days a Vail shop's script got an answer for, whatever the answer was. */
    fun checkedDays(answer: String): Set<Int> {
        val root = runCatching { json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return emptySet()
        return (root["checked"] as? JsonArray).orEmpty().mapNotNullTo(HashSet()) { date ->
            (date as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalDate.parse(it).toEpochDays().toInt() }.getOrNull() }
        }
    }

    // ---- Small things ---------------------------------------------------------------------------------

    /** "Updated November 01, 2026 at 10:20 AM PST". The zone named is the resort's, which is the one given. */
    fun longDate(text: String, zone: TimeZone): Long? {
        val m = Regex("([A-Z][a-z]+) (\\d{1,2}), (\\d{4}) at (\\d{1,2}):(\\d{2}) ([AP])M").find(text)?.groupValues ?: return null
        return instant(MONTHS.indexOf(m[1].take(3)) + 1, m[2], m[3], m[4], m[5], m[6], zone)
    }

    /** "October 13, 2026 8:12 am", as Mt. Rose's feed writes it. */
    fun longDateLowercase(text: String, zone: TimeZone): Long? {
        val m = Regex("([A-Z][a-z]+) (\\d{1,2}), (\\d{4}) (\\d{1,2}):(\\d{2}) ([ap])m").find(text)?.groupValues ?: return null
        return instant(MONTHS.indexOf(m[1].take(3)) + 1, m[2], m[3], m[4], m[5], m[6].uppercase(), zone)
    }

    /** "04/05/2026 03:57 PM". */
    fun slashDate(text: String, zone: TimeZone): Long? {
        val m = Regex("(\\d{2})/(\\d{2})/(\\d{4}) (\\d{1,2}):(\\d{2}) ([AP])M").find(text)?.groupValues ?: return null
        return instant(m[1].toInt(), m[2], m[3], m[4], m[5], m[6], zone)
    }

    /** "Oct 16, 2026 7:45 AM". */
    fun shortMonthDate(text: String, zone: TimeZone): Long? {
        val m = Regex("([A-Z][a-z]{2}) (\\d{1,2}), (\\d{4}) (\\d{1,2}):(\\d{2}) ([AP])M").find(text)?.groupValues ?: return null
        return instant(MONTHS.indexOf(m[1]) + 1, m[2], m[3], m[4], m[5], m[6], zone)
    }

    private fun instant(month: Int, day: String, year: String, hour: String, minute: String, halfDay: String, zone: TimeZone): Long? {
        if (month !in 1..12) return null
        val twelve = hour.toIntOrNull()?.rem(12) ?: return null
        val hour24 = if (halfDay == "P") twelve + 12 else twelve
        return runCatching { LocalDateTime(year.toInt(), month, day.toInt(), hour24, minute.toInt()).toInstant(zone).epochSeconds }.getOrNull()
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private const val CM_PER_INCH = 2.54

    /** More than a row of Homewood's table takes up, so what follows it isn't read as part of it. */
    private const val TABLE_ROW_LENGTH = 60

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun JsonObject.whole(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.roundToInt() }

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/**
 * What's run inside a resort's own shop page by the browser. Each says null until the page is ready, then
 * asks for what the page itself would ask for, and leaves the answer for the next time it's run.
 */
internal object ResortScripts {
    /**
     * A Vail shop: a ticket's price for each of [dates] (`yyyy-MM-dd`) and for the first day it sells,
     * through the page's own call, with the token the page itself sends with it.
     */
    fun vail(dates: List<String>): String = """
        (function () {
          if (window.__ski !== undefined) return window.__ski;
          var model = window.FR && FR.liftTicketsViewModel;
          var token = document.querySelector('#csrfToken');
          if (!model || !token || !token.value) return null;
          window.__ski = null;
          var parts = String(model.LiftTicketComponent.DefaultDate || '').split('/');
          var first = parts.length === 3 ? parts[2] + '-' + parts[0] + '-' + parts[1] : null;
          var dates = ${dates.joinToString(",", "[", "]") { "'$it'" }};
          if (first && dates.indexOf(first) < 0) dates.push(first);
          var out = {};
          var checked = [];
          var next = function (i) {
            if (i >= dates.length) {
              out.checked = checked;
              window.__ski = JSON.stringify(out);
              return;
            }
            fetch('/api/LiftAccessApi/GetLiftTickets/' + dates[i] + '/true/All/' + model.LiftTicketComponentId, {
              headers: { __RequestVerificationToken: token.value, Accept: 'application/json' }
            }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
              // A day that answered, priced or not: one that failed gets asked about again.
              if (j && j.LiftTickets) checked.push(dates[i]);
              var one = ((j && j.LiftTickets) || []).filter(function (t) { return t.NumberOfDays === 1 && t.IsProductAvailable; });
              var of = function (age) { return one.filter(function (t) { return t.AgeCategoryDisplay === age; })[0] || {}; };
              var a = of('Adult'), c = of('Child');
              if (a.Price || c.Price) out[dates[i]] = [a.Price || null, a.WindowPrice || null, c.Price || null, c.WindowPrice || null];
            }).catch(function () {}).then(function () { next(i + 1); });
          };
          next(0);
          return null;
        })()
    """.trimIndent()

    /**
     * The shop platform Alterra's and POWDR's mountains share: the whole season's calendar for an
     * adult's and a child's one-day ticket, the same request as choosing them on its calendar.
     * [categorySlug] is the shop's category and [productPattern] picks the ticket in it. The other
     * choices a ticket has (how long, what time) take their first or preselected value.
     * `BasePrice` is its price without the online saving, the nearest it has to a window price.
     */
    fun shop(categorySlug: String, productPattern: String): String = """
        (function () {
          if (window.__ski !== undefined) return window.__ski;
          // A web application firewall may check the browser first, on a page of its own, then load the real one.
          if (document.readyState !== 'complete' || /WAF/.test(document.title)) return null;
          if (window.__skiNotBefore && Date.now() < window.__skiNotBefore) return null;
          window.__ski = null;
          var finish = function (out) { window.__ski = JSON.stringify(out || {}); };
          var json = function (r) { if (!r.ok) throw new Error('refused'); return r.json(); };
          var flat = [];
          var walk = function (list) { (list || []).forEach(function (c) { flat.push(c); walk(c.SubCategories); }); };
          fetch('/api/v1/categories').then(json).then(function (categories) {
            walk(categories);
            var category = flat.filter(function (c) { return c.SeName === '$categorySlug'; })[0];
            if (!category) return finish({});
            return fetch('/api/category/' + category.Id).then(json).then(function (detail) {
            var product = (detail.ProductDetails || []).filter(function (p) { return /$productPattern/i.test(p.Name + ' ' + p.SeName); })[0];
            if (!product) return finish({});
            var id = product.Id;
            return fetch('/api/v1/product-attribute/product/' + id).then(json).then(function (attributes) {
            var lists = attributes.filter(function (a) { return !a.HasAddOn && (a.ProductAttributeValues || []).length; });
            var ageOf = lists.filter(function (a) { return /^ages?$/i.test(a.Name.trim()); })[0];
            if (!ageOf) return finish({});
            var ages = ageOf.ProductAttributeValues;
            var named = function (pattern) { return ages.filter(function (v) { return pattern.test(v.Name.trim()); })[0]; };
            var adult = named(/^(18|19|23) to (64|69|79)$/) || named(/^adult/i) || named(/^1\d and over$/i);
            var child = named(/^\d+ to 12$/) || named(/^child/i);
            if (!adult) return finish({});
            var rest = lists.filter(function (a) { return a !== ageOf; }).map(function (a) {
              var values = a.ProductAttributeValues;
              return (values.filter(function (v) { return v.IsPreselected; })[0] || values[0]).Id;
            });
            var now = new Date();
            var later = new Date(now.getTime() + 240 * 86400000);
            var ask = function (age) {
              return fetch('/api/v1/product-variant', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify({ ProductAttributeValueIds: [age.Id].concat(rest), ProductId: id, StartDate: now.toISOString(), EndDate: later.toISOString(), WithPriceLists: true, WithInventory: true })
              }).then(json).then(function (j) { return (j.Variants && j.Variants[0] && j.Variants[0].DayPriceLists) || []; });
            };
            return Promise.all([ask(adult), child ? ask(child) : Promise.resolve([])]).then(function (both) {
              var out = {};
              both[0].forEach(function (d) { out[d.Date] = [d.Price || null, d.BasePrice || null, null, null]; });
              both[1].forEach(function (d) {
                var row = out[d.Date] || (out[d.Date] = [null, null, null, null]);
                row[2] = d.Price || null;
                row[3] = d.BasePrice || null;
              });
              finish(out);
            });
            });
            });
          }).catch(function () {
            // Refused or unreadable, which is what a check still going on looks like: leave a moment, and ask again.
            window.__skiNotBefore = Date.now() + 3000;
            window.__ski = undefined;
          });
          return null;
        })()
    """.trimIndent()
}

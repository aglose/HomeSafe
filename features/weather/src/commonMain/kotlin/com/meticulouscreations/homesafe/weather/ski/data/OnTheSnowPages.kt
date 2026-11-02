package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.weather.ski.domain.SkiOpenState
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortFacts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortPage
import com.meticulouscreations.homesafe.weather.ski.domain.SkiSnowDay
import com.meticulouscreations.homesafe.weather.ski.domain.SkiTicketPrice
import com.meticulouscreations.homesafe.weather.ski.domain.SkiWebcam
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * The data inside one of OnTheSnow's pages. It has no public API; its pages are built by Next.js,
 * which writes what the page was drawn from into the HTML as a stream of numbered rows
 * (`self.__next_f.push([1,"..."])`), each a line `<hex id>:<JSON>`, where a string `"$2a"` stands
 * for row 2a. This reads the stream back and follows those references.
 */
internal class NextFlightRows private constructor(private val rows: Map<String, String>) {
    private val parsed = HashMap<String, JsonElement?>()

    private fun row(id: String): JsonElement? = parsed.getOrPut(id) {
        rows[id]?.takeIf { it.startsWith("{") || it.startsWith("[") }?.let { runCatching { LENIENT.parseToJsonElement(it) }.getOrNull() }
    }

    /** [element] with its references followed, [depth] levels down. */
    fun resolve(element: JsonElement, depth: Int = MAX_DEPTH): JsonElement = when {
        depth <= 0 -> element

        element is JsonObject -> JsonObject(element.mapValues { resolve(it.value, depth - 1) })

        element is JsonArray -> JsonArray(element.map { resolve(it, depth - 1) })

        element is JsonPrimitive && element.isString && element.content.startsWith("$") -> {
            val target = element.content.drop(1).takeIf { id -> id.isNotEmpty() && id.all { it.isHex() } }?.let(::row)
            if (target != null) resolve(target, depth - 1) else element
        }

        else -> element
    }

    /** Every row that is an object holding all of [keys], its references followed. */
    fun objectsWith(vararg keys: String): List<JsonObject> {
        val needles = keys.map { "\"$it\":" }
        return rows.entries.mapNotNull { (id, text) ->
            if (!text.startsWith("{") || needles.any { it !in text }) return@mapNotNull null
            val found = row(id) as? JsonObject ?: return@mapNotNull null
            if (keys.any { it !in found }) return@mapNotNull null
            resolve(found) as? JsonObject
        }
    }

    companion object {
        private const val MAX_DEPTH = 6
        private const val PUSH = "self.__next_f.push([1,\""
        private const val END = "\"])</script>"
        private val LENIENT = Json { isLenient = true }

        private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f'

        fun parse(html: String): NextFlightRows {
            val stream = StringBuilder()
            var at = html.indexOf(PUSH)
            while (at >= 0) {
                val start = at + PUSH.length
                val end = html.indexOf(END, start)
                if (end < 0) break
                // Each piece is a JavaScript string, and JSON's escapes are the same ones.
                runCatching { stream.append(LENIENT.decodeFromString<String>("\"" + html.substring(start, end) + "\"")) }
                at = html.indexOf(PUSH, end)
            }
            val rows = HashMap<String, String>()
            stream.split('\n').forEach { line ->
                val colon = line.indexOf(':')
                if (colon > 0 && colon <= 6 && (0 until colon).all { line[it].isHex() }) rows[line.substring(0, colon)] = line.substring(colon + 1)
            }
            return NextFlightRows(rows)
        }
    }
}

/** Turns OnTheSnow's pages into the Ski tab's models. A page that has changed shape reads as empty. */
internal object OnTheSnowPages {
    /** A region's or a pass's list of resorts. The same resort can be listed twice, in a banner as well. */
    fun reports(html: String): List<SkiReport> {
        val rows = NextFlightRows.parse(html)
        return rows.objectsWith("slug", "snow", "lifts", "status")
            .mapNotNull { it.toReport() }
            .groupBy { it.slug }
            .map { (_, same) -> same.maxBy { it.outlook.size } }
            .sortedBy { it.name.lowercase() }
    }

    /** Listed prices by resort slug, from a region's lift-ticket page. */
    fun prices(html: String): Map<String, SkiTicketPrice> {
        val rows = NextFlightRows.parse(html)
        return rows.objectsWith("resort", "dailyPassAdult").mapNotNull { row ->
            val slug = (row["resort"] as? JsonObject)?.text("slug") ?: return@mapNotNull null
            slug to SkiTicketPrice(
                adultDay = row.money("dailyPassAdult"),
                childDay = row.money("dailyPassChild"),
                adultSeason = row.money("seasonPassAdult"),
                currency = row.text("currency") ?: "US$",
                purchaseUrl = row.text("purchaseUrl")?.let(::cleanUrl),
            )
        }.toMap()
    }

    /**
     * The page of the resort [slug], which is one of several on it: the page lists resorts nearby
     * in the same shape. Null if it isn't there, or has no coordinates.
     */
    fun resortPage(html: String, slug: String, regionSlug: String, nowEpochSeconds: Long): SkiResortPage? {
        val rows = NextFlightRows.parse(html)
        val main = rows.objectsWith("latitude", "longitude", "snow", "lifts").firstOrNull { it.text("slug") == slug } ?: return null
        val report = main.toReport(regionSlug) ?: return null
        val latitude = main.number("latitude") ?: return null
        val longitude = main.number("longitude") ?: return null
        val elevation = rows.objectsWith("elevation", "terrain").firstNotNullOfOrNull { it["elevation"] as? JsonObject }
        val terrain = main["terrain"] as? JsonObject
        return SkiResortPage(
            facts = SkiResortFacts(
                latitude = latitude,
                longitude = longitude,
                baseElevationM = elevation?.number("base"),
                summitElevationM = elevation?.number("summit"),
                verticalM = elevation?.number("verticalDrop"),
                city = main.text("physicalCity"),
                acres = (terrain?.get("acres") as? JsonObject)?.number("total")?.roundToInt(),
                runsBeginner = (terrain?.get("runsBeginner") as? JsonObject)?.whole("total"),
                runsIntermediate = (terrain?.get("runsIntermediate") as? JsonObject)?.whole("total"),
                runsAdvanced = (terrain?.get("runsAdvanced") as? JsonObject)?.whole("total"),
                runsExpert = (terrain?.get("runsExpert") as? JsonObject)?.whole("total"),
                websiteUrl = main.text("website")?.let(::cleanUrl),
                ticketsUrl = main.text("liftsUrl")?.let(::cleanUrl),
                phone = main.text("phone"),
            ),
            report = report,
            webcams = (main["webcams"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toWebcam() }.take(MAX_WEBCAMS),
            recentSnow = main.snowDays("recent"),
            fetchedAtEpochSeconds = nowEpochSeconds,
        )
    }

    private const val MAX_WEBCAMS = 8

    private fun JsonObject.toReport(fallbackRegion: String? = null): SkiReport? {
        val slug = text("slug") ?: return null
        val name = text("title") ?: return null
        val region = regionOf(this["region"]) ?: fallbackRegion ?: return null
        val snow = this["snow"] as? JsonObject
        val depths = this["depths"] as? JsonObject
        val lifts = this["lifts"] as? JsonObject
        val runs = this["runs"] as? JsonObject
        val status = this["status"] as? JsonObject
        return SkiReport(
            slug = slug,
            regionSlug = region,
            name = name,
            baseDepthCm = (depths ?: snow)?.number("base"),
            middleDepthCm = (depths ?: snow)?.number("middle"),
            summitDepthCm = (depths ?: snow)?.number("summit"),
            last24Cm = snow?.number("last24"),
            last48Cm = snow?.number("last48"),
            last72Cm = snow?.number("last72"),
            liftsOpen = lifts?.whole("open"),
            liftsTotal = lifts?.whole("total"),
            runsOpen = runs?.whole("open"),
            runsTotal = runs?.whole("total"),
            runsOpenPercent = runs?.whole("openPercent"),
            state = stateOf(status?.whole("openFlag")),
            openingEpochDay = status?.text("openingDate")?.let(::epochDay),
            closingEpochDay = status?.text("closingDate")?.let(::epochDay),
            updatedAtEpochSeconds = text("updatedAt")?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() },
            outlook = snowDays("forecast"),
        )
    }

    /** What the site's flag means, going by which resorts carry which: 3 and 5 are open part of the time or part of the mountain. */
    private fun stateOf(flag: Int?): SkiOpenState = when (flag) {
        1 -> SkiOpenState.OPEN
        3, 5 -> SkiOpenState.LIMITED
        6 -> SkiOpenState.NOT_OPERATING
        else -> SkiOpenState.CLOSED
    }

    /** The region is a slug on a list of resorts, and an object on a resort's own page. */
    private fun regionOf(element: JsonElement?): String? = when (element) {
        is JsonObject -> element.text("slug")
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() && !it.startsWith("$") }
        else -> null
    }

    private fun JsonObject.toWebcam(): SkiWebcam? {
        val image = text("image")?.takeIf { it.startsWith("https://") } ?: return null
        return SkiWebcam(
            title = text("title").orEmpty(),
            imageUrl = image,
            thumbnailUrl = text("thumbnail")?.takeIf { it.startsWith("https://") } ?: image,
            epochSeconds = text("date")?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() },
        )
    }

    private fun JsonObject.snowDays(key: String): List<SkiSnowDay> = (this[key] as? JsonArray).orEmpty().mapNotNull { day ->
        val o = day as? JsonObject ?: return@mapNotNull null
        val on = o.text("date")?.let(::epochDay) ?: return@mapNotNull null
        SkiSnowDay(on, o.number("snow") ?: 0.0)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.whole(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull ?: number(key)?.roundToInt()

    /** "1,678.00" to 1678.0. Nothing, or nought, means it wasn't given. */
    private fun JsonObject.money(key: String): Double? = text(key)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 }

    /** The date of "2026-11-25" or "2026-11-25T00:00:00+00:00". */
    private fun epochDay(text: String): Int? = runCatching { LocalDate.parse(text.take(10)).toEpochDays().toInt() }.getOrNull()

    /** Some of these are typed by hand ("http:///www…"), and most carry the site's tracking tag. */
    internal fun cleanUrl(url: String): String? {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore(":", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = trimmed.substringAfter(":").trimStart('/')
        if (rest.isEmpty()) return null
        return "$scheme://$rest".substringBefore("?utm_").substringBefore("&utm_")
    }
}

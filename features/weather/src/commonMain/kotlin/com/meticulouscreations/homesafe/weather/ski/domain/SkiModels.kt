package com.meticulouscreations.homesafe.weather.ski.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

/** How long a report counts as saying how things are. */
private const val REPORT_CURRENT_SECONDS = 7 * 86_400L

/** How long a site has to have been failing before it's worth saying so: a bad day of theirs is no redesign. */
private const val SITE_TROUBLE_SECONDS = 3 * 86_400L

/** Days of the week, counting from Monday. */
private const val SATURDAY = 5
private const val SUNDAY = 6

/** A resort the household can follow: [slug] and [regionSlug] are where its pages are, [name] is what it calls itself. */
@Immutable
@Serializable
data class SkiResortRef(val slug: String, val regionSlug: String, val name: String)

/** Whether a resort is running, as it reports it. */
enum class SkiOpenState {
    OPEN,

    /** Running, but only some days or some of the mountain. */
    LIMITED,

    /** Closed for now: out of season, or between weekends. */
    CLOSED,

    /** Not expected to run at all this season. */
    NOT_OPERATING,
}

/** Snow that fell, or is expected, on one calendar day ([epochDay] counts days from 1970). */
@Immutable
@Serializable
data class SkiSnowDay(val epochDay: Int, val cm: Double)

/**
 * A resort's own report, as OnTheSnow carries it. Everything a resort hasn't said is null: 0 cm is
 * something it reported, and no one has measured what isn't there. [updatedAtEpochSeconds] is when
 * it last said anything, which out of season can be months ago.
 */
@Immutable
@Serializable
data class SkiReport(
    val slug: String,
    val regionSlug: String,
    val name: String,
    val baseDepthCm: Double? = null,
    val middleDepthCm: Double? = null,
    val summitDepthCm: Double? = null,
    val last24Cm: Double? = null,
    val last48Cm: Double? = null,
    val last72Cm: Double? = null,
    val liftsOpen: Int? = null,
    val liftsTotal: Int? = null,
    val runsOpen: Int? = null,
    val runsTotal: Int? = null,
    val runsOpenPercent: Int? = null,
    val state: SkiOpenState = SkiOpenState.CLOSED,
    val openingEpochDay: Int? = null,
    val closingEpochDay: Int? = null,
    val updatedAtEpochSeconds: Long? = null,
    /** The next eight days as OnTheSnow forecasts them: the only forecast for a resort not followed. */
    val outlook: List<SkiSnowDay> = emptyList(),
    val last12Cm: Double? = null,
    val last7DaysCm: Double? = null,
    val seasonCm: Double? = null,
    /** The surface, in the resort's own word for it. */
    val conditions: String? = null,
    /** The resort's own page these numbers came from, or null where they are OnTheSnow's listing. */
    val resortPageUrl: String? = null,
    /** When the resort's own page was read, which is all there is to go by where it puts no date on its report. */
    val fetchedAtEpochSeconds: Long? = null,
) {
    val ref: SkiResortRef get() = SkiResortRef(slug, regionSlug, name)

    /** The deepest of what it reports, wherever on the mountain. */
    val deepestCm: Double? get() = listOfNotNull(baseDepthCm, middleDepthCm, summitDepthCm).maxOrNull()

    /**
     * Said something this week: an old report's snow has melted, and its lifts aren't turning. A
     * resort's own page with no date on it is taken at its word for as long as it was read lately:
     * a site that has stopped answering leaves its last reading behind.
     */
    fun isCurrent(nowEpochSeconds: Long): Boolean = asOfEpochSeconds?.let { nowEpochSeconds - it <= REPORT_CURRENT_SECONDS } ?: false

    /** When this is from: the date it gives, or failing that when it was read. */
    val asOfEpochSeconds: Long? get() = updatedAtEpochSeconds ?: fetchedAtEpochSeconds

    /** Running today, by a report recent enough to say so. */
    fun runningNow(nowEpochSeconds: Long): Boolean = (state == SkiOpenState.OPEN || state == SkiOpenState.LIMITED) && isCurrent(nowEpochSeconds)

    /**
     * This listing with the resort's own figures over it. The dates it opens and closes stay the
     * listing's, which has them for every resort. What the resort leaves out, it leaves out: a
     * listing's stale figure doesn't stand in beside a fresh one from the resort's own page.
     */
    fun merged(news: SkiResortNews): SkiReport = copy(
        baseDepthCm = news.baseDepthCm,
        middleDepthCm = news.middleDepthCm,
        summitDepthCm = news.summitDepthCm,
        last12Cm = news.last12Cm,
        last24Cm = news.last24Cm,
        last48Cm = news.last48Cm,
        last72Cm = news.last72Cm,
        last7DaysCm = news.last7DaysCm,
        seasonCm = news.seasonCm,
        conditions = news.conditions,
        liftsOpen = news.liftsOpen,
        liftsTotal = news.liftsTotal,
        runsOpen = news.runsOpen,
        runsTotal = news.runsTotal,
        runsOpenPercent = null,
        state = when {
            news.running == true || (news.liftsOpen ?: 0) > 0 -> if (state == SkiOpenState.LIMITED) state else SkiOpenState.OPEN
            news.running == false -> SkiOpenState.CLOSED
            else -> state
        },
        updatedAtEpochSeconds = news.updatedAtEpochSeconds,
        resortPageUrl = news.pageUrl,
        fetchedAtEpochSeconds = news.fetchedAtEpochSeconds,
    )

    /** Whether it should be running on [epochDay], going by its state now and the dates it gave. An "open" from an old report says nothing about now. */
    fun runsOn(epochDay: Int, todayEpochDay: Int, nowEpochSeconds: Long): Boolean {
        if (state == SkiOpenState.NOT_OPERATING) return false
        closingEpochDay?.let { closes -> if (epochDay > closes && closes >= todayEpochDay) return false }
        if (runningNow(nowEpochSeconds)) return true
        // Not known to be running today. It runs that day only if it gave an opening date still ahead, and not after it.
        val opens = openingEpochDay ?: return false
        return opens > todayEpochDay && epochDay >= opens
    }
}

/** What a resort's own page says about its snow and lifts. Only what it says: anything else is null. */
@Immutable
@Serializable
data class SkiResortNews(
    val pageUrl: String,
    val last12Cm: Double? = null,
    val last24Cm: Double? = null,
    val last48Cm: Double? = null,
    val last72Cm: Double? = null,
    val last7DaysCm: Double? = null,
    val seasonCm: Double? = null,
    val baseDepthCm: Double? = null,
    val middleDepthCm: Double? = null,
    val summitDepthCm: Double? = null,
    val conditions: String? = null,
    val liftsOpen: Int? = null,
    val liftsTotal: Int? = null,
    val runsOpen: Int? = null,
    val runsTotal: Int? = null,
    /** Where it says outright whether it's running. */
    val running: Boolean? = null,
    val updatedAtEpochSeconds: Long? = null,
    /** When this was read from the site. */
    val fetchedAtEpochSeconds: Long? = null,
)

/** A one-day ticket from a resort's own site. Online is what it sells for ahead; window is at the mountain, or its price before any online saving. */
@Immutable
@Serializable
data class SkiDayPrice(
    val adultOnline: Double? = null,
    val adultWindow: Double? = null,
    val childOnline: Double? = null,
    val childWindow: Double? = null,
) {
    /** What to plan on: online where it's sold online. */
    val adult: Double? get() = adultOnline ?: adultWindow
    val child: Double? get() = childOnline ?: childWindow
}

/** A row of a resort's rate table, for the sites that publish rates by kind of day and not by date. */
@Immutable
@Serializable
data class SkiPriceTier(
    val kind: Kind,
    val price: SkiDayPrice,
    /** Its online figure is the lowest it starts at, not the price on any given day. */
    val onlineIsLowest: Boolean = false,
    /** The dates it's for, where the resort publishes them. */
    val onDays: Set<Int> = emptySet(),
    /** The season the resort says these rates are for, as it writes it. */
    val season: String? = null,
) {
    enum class Kind { ANY_DAY, SUNDAY_TO_FRIDAY, SATURDAY, NON_PEAK, PEAK }
}

/** A resort's ticket prices from its own site: by date where it sells that way, as a rate table where it doesn't. */
@Immutable
@Serializable
data class SkiResortPrices(
    val pageUrl: String,
    val days: Map<Int, SkiDayPrice> = emptyMap(),
    val tiers: List<SkiPriceTier> = emptyList(),
    val fetchedAtEpochSeconds: Long = 0,
    /** Every day its shop has been asked about, sold or not: before a season most aren't, and that's an answer. */
    val daysAsked: Set<Int> = emptySet(),
    /** Tickets for several days, where it sells them: by the day they start, then by how many days. */
    val packs: Map<Int, Map<Int, SkiDayPrice>> = emptyMap(),
    /** The lengths of ticket asked after for each start day, sold or not, where each length is a request of its own. */
    val packsAsked: Map<Int, Set<Int>> = emptyMap(),
) {
    /** The price on [epochDay], or before a season opens the first day's. A day with none inside the calendar has none: another day's isn't its price. */
    fun priceFor(epochDay: Int): Pair<Int, SkiDayPrice>? {
        days[epochDay]?.let { return epochDay to it }
        val first = days.keys.minOrNull()?.takeIf { it > epochDay } ?: return null
        return first to days.getValue(first)
    }

    /**
     * The rate that applies on [epochDay], if the table says. A resort that publishes its peak dates
     * goes by them. Where it doesn't, a weekend, Sunday included, is taken as peak. A table with a
     * Saturday rate gives Sunday as a cheaper day itself. 1 January 1970 was a Thursday, so Monday is 0.
     */
    fun tierOn(epochDay: Int): SkiPriceTier? {
        val peak = tiers.firstOrNull { it.kind == SkiPriceTier.Kind.PEAK && it.onDays.isNotEmpty() }
        if (peak != null) return if (epochDay in peak.onDays) peak else tiers.firstOrNull { it.kind == SkiPriceTier.Kind.NON_PEAK }
        val kinds = when ((epochDay + 3).mod(7)) {
            SATURDAY -> listOf(SkiPriceTier.Kind.SATURDAY, SkiPriceTier.Kind.PEAK)
            SUNDAY -> listOf(SkiPriceTier.Kind.SUNDAY_TO_FRIDAY, SkiPriceTier.Kind.PEAK)
            else -> listOf(SkiPriceTier.Kind.SUNDAY_TO_FRIDAY, SkiPriceTier.Kind.NON_PEAK)
        }
        return tiers.firstOrNull { it.kind in kinds } ?: tiers.firstOrNull { it.kind == SkiPriceTier.Kind.ANY_DAY }
    }
}

/**
 * What a resort's own site says about parking: [dayPrice] is the least a car pays on an ordinary
 * day, and [note] says what's behind that, as lots and rules are too various to reckon.
 */
@Immutable
data class SkiParking(val pageUrl: String, val note: StringResource, val reservationNeeded: Boolean = false, val dayPrice: Int? = null)

/**
 * Which resorts' own sites have stopped answering usefully. A site that fails while others are being
 * read has probably changed; if nothing's being read, it's the connection.
 */
@Immutable
@Serializable
data class SkiSiteHealth(val lastReadEpochSeconds: Long = 0, val failingSince: Map<String, Long> = emptyMap()) {
    /** The sites worth telling anyone about, and since when. */
    fun trouble(nowEpochSeconds: Long): Map<String, Long> =
        failingSince.filterValues { since -> nowEpochSeconds - since >= SITE_TROUBLE_SECONDS && lastReadEpochSeconds > since }
}

/** A day ticket's listed price, from OnTheSnow. A figure the resort hasn't given is null (a listed 0 means it gave none). */
@Immutable
@Serializable
data class SkiTicketPrice(
    val adultDay: Double? = null,
    val childDay: Double? = null,
    val adultSeason: Double? = null,
    /** "US$", as the listing writes it. */
    val currency: String = "US$",
    val purchaseUrl: String? = null,
)

/** A camera on the mountain: its picture at [epochSeconds]. */
@Immutable
@Serializable
data class SkiWebcam(val title: String, val imageUrl: String, val thumbnailUrl: String, val epochSeconds: Long? = null)

/** What a resort is, as opposed to how it's doing: where it is, how high, how big, and where to buy a ticket. */
@Immutable
@Serializable
data class SkiResortFacts(
    val latitude: Double,
    val longitude: Double,
    val baseElevationM: Double? = null,
    val summitElevationM: Double? = null,
    val verticalM: Double? = null,
    val city: String? = null,
    val acres: Int? = null,
    val runsBeginner: Int? = null,
    val runsIntermediate: Int? = null,
    val runsAdvanced: Int? = null,
    val runsExpert: Int? = null,
    val websiteUrl: String? = null,
    val ticketsUrl: String? = null,
    val phone: String? = null,
)

/** A resort's own page: its [facts], its latest report in more detail, its cameras and the snow it has reported day by day. */
@Immutable
@Serializable
data class SkiResortPage(
    val facts: SkiResortFacts,
    val report: SkiReport,
    val webcams: List<SkiWebcam> = emptyList(),
    val recentSnow: List<SkiSnowDay> = emptyList(),
    val fetchedAtEpochSeconds: Long = 0,
)

/** One day on the mountain: snow by the models' best guess with the spread between them, at the summit; temperatures top and bottom. */
@Immutable
@Serializable
data class SkiForecastDay(
    /** The day's local midnight. */
    val epochSeconds: Long,
    val weatherCode: Int,
    val snowCm: Double,
    /** The least and most of what the models expect. A wide gap is a forecast nobody should plan around. */
    val snowLowCm: Double = snowCm,
    val snowHighCm: Double = snowCm,
    val summitHighC: Double,
    val summitLowC: Double,
    val baseHighC: Double? = null,
    val baseLowC: Double? = null,
    /** Rain at the base: the difference between a powder day and a wet one. */
    val baseRainMm: Double? = null,
    val baseSnowCm: Double? = null,
    val precipitationProbability: Int? = null,
    val windMaxKmh: Double = 0.0,
    val gustMaxKmh: Double = 0.0,
    val sunriseEpochSeconds: Long? = null,
    val sunsetEpochSeconds: Long? = null,
) {
    val kind: WeatherKind get() = WeatherKind.fromCode(weatherCode)
}

/** One hour at the summit, starting at [epochSeconds]. */
@Immutable
@Serializable
data class SkiForecastHour(
    val epochSeconds: Long,
    val temperatureC: Double,
    val snowCm: Double = 0.0,
    val weatherCode: Int = 3,
    val windKmh: Double = 0.0,
    val gustKmh: Double = 0.0,
    val visibilityM: Double? = null,
    /** Where the air reaches freezing, above sea level: below this it snows, above it rains. */
    val freezingLevelM: Double? = null,
    val isDay: Boolean = true,
) {
    val kind: WeatherKind get() = WeatherKind.fromCode(weatherCode)
}

/** The forecast for a mountain, read at its own heights rather than at the valley town the grid would otherwise give. */
@Immutable
@Serializable
data class SkiForecast(
    val fetchedAtEpochSeconds: Long,
    val utcOffsetSeconds: Int,
    val timeZoneId: String = "",
    /** From a few days back, so the snow that has already fallen shows too, up to as far ahead as the models reach. */
    val days: List<SkiForecastDay> = emptyList(),
    val hours: List<SkiForecastHour> = emptyList(),
) {
    /** The calendar day [epochSeconds] falls in at the mountain. */
    fun epochDayOf(epochSeconds: Long): Int = (epochSeconds + utcOffsetSeconds).floorDiv(DAY_SECONDS).toInt()

    fun day(epochDay: Int): SkiForecastDay? = days.firstOrNull { epochDayOf(it.epochSeconds) == epochDay }

    /** The hours of [epochDay] that a skier is out in. */
    fun skiingHours(epochDay: Int): List<SkiForecastHour> = hours.filter { hour ->
        epochDayOf(hour.epochSeconds) == epochDay && (hour.epochSeconds + utcOffsetSeconds).mod(DAY_SECONDS) / 3600 in 8L..16L
    }

    /** When [hour] o'clock comes on [epochDay] at the mountain: by its own zone, as the clocks may change before then, and by today's offset where the zone isn't known. */
    fun epochSecondsAt(epochDay: Int, hour: Int): Long {
        val zone = timeZoneId.takeIf { it.isNotBlank() }?.let { id -> runCatching { TimeZone.of(id) }.getOrNull() }
            ?: return epochDay * DAY_SECONDS + hour * 3_600L - utcOffsetSeconds
        return LocalDate.fromEpochDays(epochDay).atTime(hour, 0).toInstant(zone).epochSeconds
    }

    companion object {
        const val DAY_SECONDS = 86_400L
    }
}

/** How a drive's time was worked out, which is how far to trust it. */
enum class SkiDriveSource {
    /** Google's routes, knowing the traffic now or usual at the hour asked about. */
    TRAFFIC,

    /** An open road map with no traffic on it: the time on an empty road. */
    FREE_FLOW,
}

/** A drive there or back. [path] is the road as an encoded polyline, which chain controls are matched against. */
@Immutable
@Serializable
data class SkiDrive(
    val seconds: Long,
    val meters: Double,
    val source: SkiDriveSource,
    val path: String = "",
    /** Which departure it's for; null for leaving now. */
    val departEpochSeconds: Long? = null,
)

/** What a road's state asks of a car, the least first: mostly Caltrans's own requirement levels. */
enum class ChainLevel {
    /** Worth knowing, in the road service's own words: its [ChainControl.description]. */
    NOTICE,

    /** R-1: chains or snow tires on the drive wheels. */
    R1,

    /** R-2: chains required, except four-wheel drive with snow tires. */
    R2,

    /** R-3: chains on everything, usually just before the road closes. */
    R3,

    /** The road is shut, such as a pass closed for the winter. */
    CLOSED,
}

/** What's in force at a checkpoint or along a stretch of highway. */
@Immutable
@Serializable
data class ChainControl(
    val level: ChainLevel,
    /** "I-80". */
    val route: String,
    /** "Donner Summit", as Caltrans calls it. */
    val place: String,
    val direction: String = "",
    val latitude: Double,
    val longitude: Double,
    /** Caltrans's own sentence about it. */
    val description: String = "",
)

/** What's in force along a road, and whether that's all known: nothing found is no all-clear when [complete] is false, as a file couldn't be read. */
@Immutable
data class SkiChainReport(val controls: List<ChainControl> = emptyList(), val complete: Boolean = true)

/** The season passes the app knows the resorts of. [slug] is where the pass's list of resorts is. */
enum class SkiPass(val slug: String) {
    EPIC("epic-pass"),
    IKON("ikon-pass"),
    INDY("indy-pass"),
    MOUNTAIN_COLLECTIVE("mountain-collective"),
    POWDER_ALLIANCE("powder-alliance"),
}

/** Where a drive starts. */
enum class SkiOrigin {
    /** The household's home, or the phone's last position when no home is set. */
    HOME,

    /** Wherever the phone is now: for planning from a cabin. */
    HERE,
}

/** Which of the Ski tab's notifications are wanted. All on to begin with, as the weather's others are. */
@Immutable
@Serializable
data class SkiAlerts(
    /** A big snowfall forecast for the coming weekend, or the day picked. */
    val bigSnow: Boolean = true,
    /** A resort's own site reporting fresh snow. */
    val freshSnow: Boolean = true,
    /** Chain controls on the road to a resort. */
    val chains: Boolean = true,
) {
    val any: Boolean get() = bigSnow || freshSnow || chains
}

/** Who's going and from where: what the household has set up on the Ski tab, kept between runs. */
@Immutable
@Serializable
data class SkiPlan(
    val resorts: List<SkiResortRef> = DEFAULT_RESORTS,
    val regions: List<String> = DEFAULT_REGIONS,
    val adults: Int = 2,
    val children: Int = 0,
    val passes: Set<SkiPass> = emptySet(),
    val origin: SkiOrigin = SkiOrigin.HOME,
    /** What the household actually pays a day at a resort, by its slug, where that isn't the listed price (or none is listed). */
    val ownPrices: Map<String, Int> = emptyMap(),
    /** How many days on the mountain, from the day chosen. */
    val tripDays: Int = 1,
    /** What a night's lodging comes to, in whole dollars. Nothing set isn't free: it's left out and said to be. */
    val lodgingPerNight: Int = 0,
    /** Gas or charging, in cents a mile. The default is a rough figure for an ordinary car. */
    val drivingCentsPerMile: Int = DEFAULT_DRIVING_CENTS_PER_MILE,
    /** What parking costs a day at a resort, by its slug, as the household finds it. */
    val ownParking: Map<String, Int> = emptyMap(),
    /** The day last picked on the strip. Once it's past, it means nothing and the coming Saturday stands in. */
    val tripEpochDay: Int? = null,
    /** The tab has been opened on this device: a resort it's only following by default says nothing until then. */
    val tabOpened: Boolean = false,
    val alerts: SkiAlerts = SkiAlerts(),
) {
    fun follows(slug: String): Boolean = resorts.any { it.slug == slug }

    companion object {
        const val DEFAULT_DRIVING_CENTS_PER_MILE = 15
        const val MAX_TRIP_DAYS = 7

        /** Northern California's big mountains, where the household goes to start with. */
        val DEFAULT_RESORTS = listOf(
            SkiResortRef("palisades-tahoe", "california", "Palisades Tahoe"),
            SkiResortRef("northstar-california", "california", "Northstar California"),
            SkiResortRef("sugar-bowl-resort", "california", "Sugar Bowl Resort"),
            SkiResortRef("heavenly-mountain-resort", "california", "Heavenly"),
            SkiResortRef("kirkwood", "california", "Kirkwood"),
            SkiResortRef("sierra-at-tahoe", "california", "Sierra-at-Tahoe"),
        )
        val DEFAULT_REGIONS = listOf("northern-california")
    }
}

/** A part of the world resorts are grouped by. [slug] is where its list is; [name] is copy, since there is no list of them to read it from. */
@Immutable
data class SkiRegion(val slug: String, val name: StringResource)

package com.meticulouscreations.homesafe.weather.ski.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.ski_chain_r1
import homesafe.shared.generated.resources.ski_chain_r2
import homesafe.shared.generated.resources.ski_chain_r3
import homesafe.shared.generated.resources.ski_notice_and_more
import homesafe.shared.generated.resources.ski_notice_chains_road
import homesafe.shared.generated.resources.ski_notice_chains_title_r1
import homesafe.shared.generated.resources.ski_notice_chains_title_r2
import homesafe.shared.generated.resources.ski_notice_chains_title_r3
import homesafe.shared.generated.resources.ski_notice_fresh_body_many
import homesafe.shared.generated.resources.ski_notice_fresh_body_one
import homesafe.shared.generated.resources.ski_notice_fresh_title_many
import homesafe.shared.generated.resources.ski_notice_fresh_title_one
import homesafe.shared.generated.resources.ski_notice_list_separator
import homesafe.shared.generated.resources.ski_notice_resort_amount
import homesafe.shared.generated.resources.ski_notice_snow_range
import homesafe.shared.generated.resources.ski_notice_snow_title
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the relay decided a phone should hear (`ski_data` in relay/relay.py), for it to say in the
 * reader's own language and units: only what the words need, in as little room as will do, since
 * a push has 4 KB. [k] says which of the three: [SNOW], [FRESH] or [CHAINS]. [d] is the day it's
 * about, [x] when it stops being news, [r] the resorts, the most first ([m] more besides), and
 * [c] the roads, the worst first.
 */
@Immutable
@Serializable
class SkiNoticePush(
    val k: String = "",
    val d: Int? = null,
    val x: Long? = null,
    val r: List<Resort> = emptyList(),
    val m: Int = 0,
    val c: List<Road> = emptyList(),
) {
    @Immutable
    @Serializable
    class Resort(val n: String = "", val s: String = "", val cm: Double = 0.0, val lo: Double? = null, val hi: Double? = null)

    /** A highway: its name, the checkpoint, the level asked for (1 to 3) and the resorts it's on the way to. */
    @Immutable
    @Serializable
    class Road(val n: String = "", val p: String = "", val l: Int = 1, val t: List<String> = emptyList())

    companion object {
        /** The push's data key that holds this, which is also what marks it as one of these. */
        const val KEY = "ski_notice"
        const val SNOW = "snow"
        const val FRESH = "fresh"
        const val CHAINS = "chains"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** Null for what this build can't read: a newer relay's. */
        fun read(payload: String): SkiNoticePush? = runCatching { JSON.decodeFromString<SkiNoticePush>(payload) }.getOrNull()?.takeIf { it.k in setOf(SNOW, FRESH, CHAINS) }
    }
}

/** A skiing notification to post. [day] and [resort] are where its tap lands on the Ski tab. */
@Immutable
data class SkiNotice(
    val key: String,
    val title: UiText,
    val body: UiText,
    val icon: WeatherNoticeIcon,
    val detail: UiText? = null,
    /** Beside its picture, which has no chart and so no room for more: a small line, and the one thing to read. */
    val caption: UiText? = null,
    val headline: UiText? = null,
    val expiresEpochSeconds: Long? = null,
    val day: Int? = null,
    val resort: String? = null,
)

/**
 * Turns what the relay decided into what is posted, or into nothing: the switches, and what this
 * phone has already said, still have their say. What's worth saying is the relay's to decide, and
 * lives there with its tests (`ski_big_snow`, `ski_fresh_snow`, `ski_chains_news`): none of it
 * can wait for a phone to wake.
 */
object SkiNoticePlanner {
    fun notice(
        push: SkiNoticePush,
        key: String,
        weather: WeatherNoticeSettings,
        alerts: SkiAlerts,
        units: WeatherUnits,
        sent: Map<String, Long> = emptyMap(),
    ): SkiNotice? {
        if (!weather.enabled || key in sent) return null
        return when (push.k) {
            SkiNoticePush.SNOW -> if (alerts.bigSnow) snow(push, key, units) else null
            SkiNoticePush.FRESH -> if (alerts.freshSnow) fresh(push, key, units) else null
            SkiNoticePush.CHAINS -> if (alerts.chains) chains(push, key) else null
            else -> null
        }
    }

    private fun snow(push: SkiNoticePush, key: String, units: WeatherUnits): SkiNotice? {
        val day = push.d ?: return null
        if (push.r.isEmpty()) return null
        val weekday = UiText.of(WeatherFormat.weekdayLong(day * SkiForecast.DAY_SECONDS, 0))
        val ranges = push.r.filter { it.lo != null && it.hi != null && it.lo != it.hi }
        return SkiNotice(
            key = key,
            title = UiText.of(Res.string.ski_notice_snow_title, weekday),
            body = listed(push, units),
            icon = WeatherNoticeIcon.SNOW,
            detail = ranges.takeIf { it.isNotEmpty() }?.let { shown ->
                UiText.Joined(shown.map { UiText.of(Res.string.ski_notice_snow_range, it.n, WeatherFormat.snow(it.cm, units), WeatherFormat.snow(it.lo ?: it.cm, units), WeatherFormat.snow(it.hi ?: it.cm, units)) }, LINE)
            },
            caption = amount(push.r.first(), units),
            headline = weekday,
            expiresEpochSeconds = push.x,
            day = day,
            resort = push.r.singleOrNull()?.takeIf { push.m == 0 }?.s,
        )
    }

    private fun fresh(push: SkiNoticePush, key: String, units: WeatherUnits): SkiNotice? {
        val first = push.r.firstOrNull() ?: return null
        val one = push.r.size == 1 && push.m == 0
        val count = push.r.size + push.m
        val title = if (one) UiText.of(Res.string.ski_notice_fresh_title_one, first.n) else UiText.plural(Res.plurals.ski_notice_fresh_title_many, count, count)
        return SkiNotice(
            key = key,
            title = title,
            body = if (one) UiText.of(Res.string.ski_notice_fresh_body_one, WeatherFormat.snow(first.cm, units)) else UiText.of(Res.string.ski_notice_fresh_body_many, listed(push, units)),
            icon = WeatherNoticeIcon.SNOW,
            caption = first.n.asUiText(),
            headline = WeatherFormat.snow(first.cm, units),
            expiresEpochSeconds = push.x,
            day = push.d,
            resort = first.s.takeIf { one },
        )
    }

    private fun chains(push: SkiNoticePush, key: String): SkiNotice? {
        val worst = push.c.firstOrNull() ?: return null
        val roads = push.c.map { road ->
            UiText.of(Res.string.ski_notice_chains_road, UiText.of(asked(road.l)), road.n, road.p, UiText.Joined(road.t.map { it.asUiText() }, UiText.of(Res.string.ski_notice_list_separator)))
        }
        return SkiNotice(
            key = key,
            title = UiText.of(titled(worst.l), worst.n),
            body = UiText.Joined(roads, UiText.of(Res.string.common_dot_separator)),
            icon = WeatherNoticeIcon.CHAINS,
            caption = worst.p.asUiText(),
            headline = worst.n.asUiText(),
            expiresEpochSeconds = push.x,
            day = push.d,
        )
    }

    /** "Palisades Tahoe 18 in · Sugar Bowl 16 in · and 2 more": a name and an amount are data, and a list has no sentence to be whole in. */
    private fun listed(push: SkiNoticePush, units: WeatherUnits): UiText {
        val parts = push.r.map { amount(it, units) } + if (push.m > 0) listOf(UiText.plural(Res.plurals.ski_notice_and_more, push.m, push.m)) else emptyList()
        return UiText.Joined(parts, UiText.of(Res.string.common_dot_separator))
    }

    private fun amount(resort: SkiNoticePush.Resort, units: WeatherUnits) = UiText.of(Res.string.ski_notice_resort_amount, resort.n, WeatherFormat.snow(resort.cm, units))

    private fun asked(level: Int) = when (level) {
        3 -> Res.string.ski_chain_r3
        2 -> Res.string.ski_chain_r2
        else -> Res.string.ski_chain_r1
    }

    private fun titled(level: Int) = when (level) {
        3 -> Res.string.ski_notice_chains_title_r3
        2 -> Res.string.ski_notice_chains_title_r2
        else -> Res.string.ski_notice_chains_title_r1
    }

    private val LINE = UiText.Verbatim("\n")
}

/**
 * Keeps the relay told what the Ski tab wants to hear, since it's the relay that looks: what's
 * followed, the day picked, the switches. Nothing about where the device is.
 */
interface SkiNoticeSync {
    /** Called once at launch: tells the relay when the plan or the switches change. */
    fun watch()

    /** Tells the relay now if it's been told something else, or not for a while. Throws nothing the caller must handle: the next one tries again. */
    suspend fun sync()

    /** A build with no relay to tell. */
    object Never : SkiNoticeSync {
        override fun watch() = Unit

        override suspend fun sync() = Unit
    }
}

package com.meticulouscreations.homesafe.domain.model

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.uptime_check_cameras
import homesafe.shared.generated.resources.uptime_check_cameras_about
import homesafe.shared.generated.resources.uptime_check_dns
import homesafe.shared.generated.resources.uptime_check_dns_about
import homesafe.shared.generated.resources.uptime_check_frigate
import homesafe.shared.generated.resources.uptime_check_frigate_about
import homesafe.shared.generated.resources.uptime_check_internet
import homesafe.shared.generated.resources.uptime_check_internet_about
import homesafe.shared.generated.resources.uptime_check_live
import homesafe.shared.generated.resources.uptime_check_live_about
import homesafe.shared.generated.resources.uptime_check_router
import homesafe.shared.generated.resources.uptime_check_router_about
import homesafe.shared.generated.resources.uptime_check_server
import homesafe.shared.generated.resources.uptime_check_server_about
import homesafe.shared.generated.resources.uptime_check_tailscale
import homesafe.shared.generated.resources.uptime_check_tailscale_about
import homesafe.shared.generated.resources.uptime_moment
import homesafe.shared.generated.resources.uptime_percent_one_decimal
import homesafe.shared.generated.resources.uptime_percent_two_decimals
import homesafe.shared.generated.resources.uptime_percent_whole
import homesafe.shared.generated.resources.uptime_range_day
import homesafe.shared.generated.resources.uptime_range_month
import homesafe.shared.generated.resources.uptime_range_week
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToLong
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What one span of the uptime record says of a check, or of a device on the tailnet. */
enum class UptimeState {
    Up,

    /** Up for part of the span and down for the rest. */
    Partial,
    Down,

    /** Nothing was sampled: the relay wasn't recording yet, or this check couldn't be made. */
    NotMeasured,
    ;

    companion object {
        /** The relay's letter for a span (its `bucket_state`); anything it adds later reads as not measured. */
        fun fromLetter(letter: Char): UptimeState = when (letter) {
            'u' -> Up
            'd' -> Partial
            'x' -> Down
            else -> NotMeasured
        }
    }
}

/**
 * The links the relay tries once a minute, from the box outwards and then what it serves (the
 * relay's `UPTIME_CHECKS`). [title] names it and [about] says what it being down means for
 * someone holding the app.
 */
enum class UptimeCheckKind(val key: String, val title: StringResource, val about: StringResource) {
    Server("server", Res.string.uptime_check_server, Res.string.uptime_check_server_about),
    Router("router", Res.string.uptime_check_router, Res.string.uptime_check_router_about),
    Internet("internet", Res.string.uptime_check_internet, Res.string.uptime_check_internet_about),
    Dns("dns", Res.string.uptime_check_dns, Res.string.uptime_check_dns_about),
    Tailscale("tailscale", Res.string.uptime_check_tailscale, Res.string.uptime_check_tailscale_about),
    Frigate("frigate", Res.string.uptime_check_frigate, Res.string.uptime_check_frigate_about),
    Cameras("cameras", Res.string.uptime_check_cameras, Res.string.uptime_check_cameras_about),
    Live("live", Res.string.uptime_check_live, Res.string.uptime_check_live_about),
    ;

    companion object {
        fun of(key: String): UptimeCheckKind? = entries.firstOrNull { it.key == key }
    }
}

/** One check over the range: a state per span, how much of the measured time it was up, and whether it is up now. */
@Immutable
data class UptimeCheck(
    val key: String,
    /** The relay's own name for it, shown for a check this build has no words for. */
    val serverName: String,
    val states: List<UptimeState>,
    /** 0–1 of the samples taken; null when none were. */
    val upFraction: Double?,
    val downSeconds: Long,
    /** Null when the newest sample is too old to speak for now. */
    val isUpNow: Boolean?,
) {
    val kind: UptimeCheckKind? get() = UptimeCheckKind.of(key)

    val title: UiText get() = kind?.let { UiText.of(it.title) } ?: serverName.asUiText()
}

/** One of the household's devices as Tailscale on the server sees it: on the tailnet or not, span by span. */
@Immutable
data class UptimeDevice(
    /** Its MagicDNS name ("pixel-10-pro-xl"), which is data. */
    val name: String,
    val os: String,
    val isOnline: Boolean?,
    val lastSeenEpochSeconds: Long?,
    val states: List<UptimeState>,
)

/** A stretch something was down. [endEpochSeconds] is null while it still is. */
@Immutable
data class UptimeOutage(
    val checkKey: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long?,
    val seconds: Long,
)

/**
 * The server's uptime record over one range, as the push relay keeps it: it samples each check
 * once a minute from the box's side, so a span with no samples is one the relay itself wasn't
 * running for (the [UptimeCheckKind.Server] row).
 */
@Immutable
data class ServerUptime(
    val sinceEpochSeconds: Long,
    val untilEpochSeconds: Long,
    val bucketSeconds: Double,
    /** When the relay kept its first sample; nothing before it counts as down. */
    val recordingSinceEpochSeconds: Long?,
    val checks: List<UptimeCheck>,
    val devices: List<UptimeDevice>,
    val outages: List<UptimeOutage>,
) {
    /** The checks that are down as of the newest sample. */
    val downNow: List<UptimeCheck> get() = checks.filter { it.isUpNow == false }

    /** When span [index] of a row starts. */
    fun bucketStart(index: Int): Long = sinceEpochSeconds + (index * bucketSeconds).roundToLong()
}

/** How far back the status screen looks. */
enum class UptimeRange(val hours: Int, val label: StringResource) {
    Day(24, Res.string.uptime_range_day),
    Week(24 * 7, Res.string.uptime_range_week),
    Month(24 * 30, Res.string.uptime_range_month),
}

/**
 * "100%", "99.9%", "99.95%": as many decimals as it takes to tell it from perfect, since a
 * rounded "100%" over a day with an outage in it would be a lie. Never rounds up to 100.
 */
fun formatUpFraction(fraction: Double): UiText {
    val percent = (fraction.coerceIn(0.0, 1.0) * 100)
    if (percent >= 100.0) return UiText.of(Res.string.uptime_percent_whole, 100)
    val hundredths = kotlin.math.floor(percent * 100).toLong()
    val whole = (hundredths / 100).toInt()
    val fractionPart = (hundredths % 100).toInt()
    return when {
        percent < 99.0 -> UiText.of(Res.string.uptime_percent_whole, whole)
        percent < 99.9 || fractionPart % 10 == 0 -> UiText.of(Res.string.uptime_percent_one_decimal, whole, fractionPart / 10)
        else -> UiText.of(Res.string.uptime_percent_two_decimals, whole, fractionPart.toString().padStart(2, '0'))
    }
}

/** "Oct 4, 2:06 PM" in [timeZone]: a day and a time, for when a span or an outage began. */
@OptIn(ExperimentalTime::class)
fun uptimeMoment(epochSeconds: Long, timeZone: TimeZone): UiText {
    val date = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(timeZone).date
    return UiText.of(Res.string.uptime_moment, date.shortLabel(), clockLabel(epochSeconds.toDouble(), timeZone).asUiText())
}

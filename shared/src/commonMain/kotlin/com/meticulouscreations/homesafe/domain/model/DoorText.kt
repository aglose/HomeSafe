package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.doors_day_ago
import homesafe.shared.generated.resources.doors_day_today
import homesafe.shared.generated.resources.doors_day_yesterday
import homesafe.shared.generated.resources.doors_detail_battery
import homesafe.shared.generated.resources.doors_detail_battery_low
import homesafe.shared.generated.resources.doors_detail_offline
import homesafe.shared.generated.resources.doors_detail_weak_signal
import homesafe.shared.generated.resources.doors_notify_always
import homesafe.shared.generated.resources.doors_notify_away
import homesafe.shared.generated.resources.doors_notify_never
import homesafe.shared.generated.resources.doors_state_closed
import homesafe.shared.generated.resources.doors_state_closed_days
import homesafe.shared.generated.resources.doors_state_closed_hours
import homesafe.shared.generated.resources.doors_state_closed_just_now
import homesafe.shared.generated.resources.doors_state_closed_minutes
import homesafe.shared.generated.resources.doors_state_open
import homesafe.shared.generated.resources.doors_state_open_days
import homesafe.shared.generated.resources.doors_state_open_hours
import homesafe.shared.generated.resources.doors_state_open_minutes
import homesafe.shared.generated.resources.doors_state_opened_just_now
import homesafe.shared.generated.resources.doors_state_unknown
import org.jetbrains.compose.resources.StringResource

/** What each [DoorNotify] is called on its chip. */
val DoorNotify.label: StringResource
    get() = when (this) {
        DoorNotify.AWAY -> Res.string.doors_notify_away
        DoorNotify.ALWAYS -> Res.string.doors_notify_always
        DoorNotify.NEVER -> Res.string.doors_notify_never
    }

/** How [door] stands at [nowMillis]: "Open for 5 min", "Closed for 2 hours", "Waiting for its first reading". */
fun doorStateText(door: Door, nowMillis: Long): UiText {
    val open = door.open ?: return UiText.of(Res.string.doors_state_unknown)
    val changed = door.changedAtMillis ?: return UiText.of(if (open) Res.string.doors_state_open else Res.string.doors_state_closed)
    val seconds = ((nowMillis - changed) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> UiText.of(if (open) Res.string.doors_state_opened_just_now else Res.string.doors_state_closed_just_now)
        seconds < 3_600 -> UiText.plural(if (open) Res.plurals.doors_state_open_minutes else Res.plurals.doors_state_closed_minutes, (seconds / 60).toInt())
        seconds < 86_400 -> UiText.plural(if (open) Res.plurals.doors_state_open_hours else Res.plurals.doors_state_closed_hours, (seconds / 3_600).toInt())
        else -> UiText.plural(if (open) Res.plurals.doors_state_open_days else Res.plurals.doors_state_closed_days, (seconds / 86_400).toInt())
    }
}

/** The sensor's own health, only what's worth saying: offline, battery, a weak signal. Null when there's nothing. */
fun doorHealthText(door: Door): UiText? {
    val parts = buildList {
        if (door.online == false) add(UiText.of(Res.string.doors_detail_offline))
        when {
            door.needsBattery -> add(UiText.of(Res.string.doors_detail_battery_low))
            door.battery != null -> add(UiText.of(Res.string.doors_detail_battery, door.battery))
        }
        if (door.weakSignal) add(UiText.of(Res.string.doors_detail_weak_signal))
    }
    return parts.takeIf { it.isNotEmpty() }?.let { UiText.Joined(it, UiText.of(Res.string.common_dot_separator)) }
}

/** A history day's heading: "Today", "Yesterday", "3 days ago". [daysAgo] counts calendar days. */
fun doorDayText(daysAgo: Int): UiText = when {
    daysAgo <= 0 -> UiText.of(Res.string.doors_day_today)
    daysAgo == 1 -> UiText.of(Res.string.doors_day_yesterday)
    else -> UiText.plural(Res.plurals.doors_day_ago, daysAgo)
}

/** How Home's glance row shows the doors: each one that needs a look on its own, the rest as a count. */
data class DoorGlance(
    /** Open, offline, or never heard from: open ones first, in the relay's order. */
    val shown: List<Door>,
    /** The doors that are closed and online, told as one number. */
    val closedCount: Int,
    /** Every door is closed (and there is at least one): "All doors closed". */
    val allClosed: Boolean,
)

fun doorGlance(doors: List<Door>): DoorGlance {
    val needsALook = doors.filter { it.open != false || it.online == false }
    val shown = needsALook.sortedByDescending { it.open == true && it.online != false }
    val closedCount = doors.size - needsALook.size
    return DoorGlance(shown = shown, closedCount = closedCount, allClosed = needsALook.isEmpty() && closedCount > 0)
}

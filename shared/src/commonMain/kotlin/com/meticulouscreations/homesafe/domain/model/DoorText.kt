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
import homesafe.shared.generated.resources.doors_detail_signal_fair
import homesafe.shared.generated.resources.doors_detail_signal_good
import homesafe.shared.generated.resources.doors_detail_signal_strong
import homesafe.shared.generated.resources.doors_detail_signal_weak
import homesafe.shared.generated.resources.doors_notify_always
import homesafe.shared.generated.resources.doors_notify_away
import homesafe.shared.generated.resources.doors_notify_never
import homesafe.shared.generated.resources.doors_pairing_interviewing
import homesafe.shared.generated.resources.doors_pairing_join_failed
import homesafe.shared.generated.resources.doors_pairing_joined
import homesafe.shared.generated.resources.doors_pairing_paired
import homesafe.shared.generated.resources.doors_pairing_paired_other
import homesafe.shared.generated.resources.doors_pairing_paired_unnamed
import homesafe.shared.generated.resources.doors_pairing_rejoined
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

/** What each [DoorSignal] is called, with the link quality it came from as its argument. */
val DoorSignal.label: StringResource
    get() = when (this) {
        DoorSignal.WEAK -> Res.string.doors_detail_signal_weak
        DoorSignal.FAIR -> Res.string.doors_detail_signal_fair
        DoorSignal.GOOD -> Res.string.doors_detail_signal_good
        DoorSignal.STRONG -> Res.string.doors_detail_signal_strong
    }

/**
 * The sensor's own health: offline, battery, signal. Null when there's nothing. An offline
 * sensor's signal is from before it went quiet, so only a weak one is said, as the likely why.
 */
fun doorHealthText(door: Door): UiText? {
    val parts = buildList {
        if (door.online == false) add(UiText.of(Res.string.doors_detail_offline))
        when {
            door.needsBattery -> add(UiText.of(Res.string.doors_detail_battery_low))
            door.battery != null -> add(UiText.of(Res.string.doors_detail_battery, door.battery))
        }
        val signal = door.signal
        if (signal != null && door.linkQuality != null && (door.online != false || signal == DoorSignal.WEAK)) {
            add(UiText.of(signal.label, door.linkQuality))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.let { UiText.Joined(it, UiText.of(Res.string.common_dot_separator)) }
}

/**
 * What pairing says of a device that joined: [door] is the door it is, once the relay has it as
 * one. A known door that joined is one that dropped off and is back; a new one is set up, then
 * wants a name.
 */
fun doorJoinText(join: DoorJoin, door: Door?): UiText = when (join.status) {
    DoorJoinStatus.JOINED -> if (door != null && !door.unnamed) UiText.of(Res.string.doors_pairing_rejoined, door.name) else UiText.of(Res.string.doors_pairing_joined)

    DoorJoinStatus.INTERVIEWING -> UiText.of(Res.string.doors_pairing_interviewing)

    DoorJoinStatus.PAIRED -> when {
        door == null -> UiText.of(Res.string.doors_pairing_paired_other)
        door.unnamed -> UiText.of(Res.string.doors_pairing_paired_unnamed)
        else -> UiText.of(Res.string.doors_pairing_paired, door.name)
    }

    DoorJoinStatus.FAILED -> UiText.of(Res.string.doors_pairing_join_failed)
}

/** A history day's heading: "Today", "Yesterday", "3 days ago". [daysAgo] counts calendar days. */
fun doorDayText(daysAgo: Int): UiText = when {
    daysAgo <= 0 -> UiText.of(Res.string.doors_day_today)
    daysAgo == 1 -> UiText.of(Res.string.doors_day_yesterday)
    else -> UiText.plural(Res.plurals.doors_day_ago, daysAgo)
}

/** How Home's glance row shows the doors: each one that needs a look on its own, the rest as a count. */
data class DoorGlance(
    /** Open, offline, never heard from, or wanting a battery: open ones first, in the relay's order. */
    val shown: List<Door>,
    /** The doors that are closed, online and on a good battery, told as one number. */
    val closedCount: Int,
    /** Every door is closed (and there is at least one): "All doors closed". */
    val allClosed: Boolean,
)

fun doorGlance(doors: List<Door>): DoorGlance {
    val needsALook = doors.filter { it.open != false || it.online == false || it.needsBattery }
    val shown = needsALook.sortedByDescending { it.open == true && it.online != false }
    val closedCount = doors.size - needsALook.size
    return DoorGlance(shown = shown, closedCount = closedCount, allClosed = needsALook.isEmpty() && closedCount > 0)
}

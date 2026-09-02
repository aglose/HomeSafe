package com.meticulouscreations.homesafe.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** "3:42 PM", or "3:42:07 PM" with [withSeconds], in the device's time zone. */
@OptIn(ExperimentalTime::class)
fun formatClockTime(epochSeconds: Double, withSeconds: Boolean = false): String {
    val local = Instant.fromEpochSeconds(epochSeconds.toLong()).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour12 = (local.hour + 11) % 12 + 1
    val suffix = if (local.hour < 12) "AM" else "PM"
    val minute = local.minute.toString().padStart(2, '0')
    return if (withSeconds) {
        "$hour12:$minute:${local.second.toString().padStart(2, '0')} $suffix"
    } else {
        "$hour12:$minute $suffix"
    }
}

/** "12:34" or "1:02:34" — how far behind live a playhead is. */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = secs.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$minutes:$ss"
}

/** The device time zone's UTC offset at [epochSeconds], for aligning timeline ticks to local hours. */
@OptIn(ExperimentalTime::class)
fun localUtcOffsetSeconds(epochSeconds: Double): Int =
    TimeZone.currentSystemDefault().offsetAt(Instant.fromEpochSeconds(epochSeconds.toLong())).totalSeconds

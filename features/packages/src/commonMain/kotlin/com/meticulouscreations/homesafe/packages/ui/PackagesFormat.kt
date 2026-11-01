package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.runtime.Composable
import com.meticulouscreations.homesafe.packages.domain.CameraVerdict
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ParcelDays
import com.meticulouscreations.homesafe.packages.domain.ParcelEvent
import com.meticulouscreations.homesafe.packages.domain.ParcelProblem
import com.meticulouscreations.homesafe.packages.domain.ParcelStage
import com.meticulouscreations.homesafe.packages.domain.ParcelZone
import com.meticulouscreations.homesafe.packages.domain.PorchState
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_date_month_day
import homesafe.shared.generated.resources.finance_date_month_day_year
import homesafe.shared.generated.resources.finance_month_apr
import homesafe.shared.generated.resources.finance_month_aug
import homesafe.shared.generated.resources.finance_month_dec
import homesafe.shared.generated.resources.finance_month_feb
import homesafe.shared.generated.resources.finance_month_jan
import homesafe.shared.generated.resources.finance_month_jul
import homesafe.shared.generated.resources.finance_month_jun
import homesafe.shared.generated.resources.finance_month_mar
import homesafe.shared.generated.resources.finance_month_may
import homesafe.shared.generated.resources.finance_month_nov
import homesafe.shared.generated.resources.finance_month_oct
import homesafe.shared.generated.resources.finance_month_sep
import homesafe.shared.generated.resources.packages_ago_days
import homesafe.shared.generated.resources.packages_ago_hours
import homesafe.shared.generated.resources.packages_ago_minutes
import homesafe.shared.generated.resources.packages_ago_now
import homesafe.shared.generated.resources.packages_claim_ask
import homesafe.shared.generated.resources.packages_claim_camera_nobody
import homesafe.shared.generated.resources.packages_claim_camera_visit
import homesafe.shared.generated.resources.packages_claim_carrier
import homesafe.shared.generated.resources.packages_claim_gone
import homesafe.shared.generated.resources.packages_claim_intro
import homesafe.shared.generated.resources.packages_claim_item
import homesafe.shared.generated.resources.packages_claim_left
import homesafe.shared.generated.resources.packages_claim_marked
import homesafe.shared.generated.resources.packages_claim_order
import homesafe.shared.generated.resources.packages_claim_promised
import homesafe.shared.generated.resources.packages_claim_shop
import homesafe.shared.generated.resources.packages_claim_total
import homesafe.shared.generated.resources.packages_claim_tracking
import homesafe.shared.generated.resources.packages_date_weekday
import homesafe.shared.generated.resources.packages_event_added
import homesafe.shared.generated.resources.packages_event_attempted
import homesafe.shared.generated.resources.packages_event_camera_confirmed
import homesafe.shared.generated.resources.packages_event_camera_not_seen
import homesafe.shared.generated.resources.packages_event_camera_only
import homesafe.shared.generated.resources.packages_event_camera_seen
import homesafe.shared.generated.resources.packages_event_delivered
import homesafe.shared.generated.resources.packages_event_exception
import homesafe.shared.generated.resources.packages_event_expected
import homesafe.shared.generated.resources.packages_event_marked
import homesafe.shared.generated.resources.packages_event_may_be_gone
import homesafe.shared.generated.resources.packages_event_ordered
import homesafe.shared.generated.resources.packages_event_out
import homesafe.shared.generated.resources.packages_event_quiet
import homesafe.shared.generated.resources.packages_event_refund
import homesafe.shared.generated.resources.packages_event_return
import homesafe.shared.generated.resources.packages_event_return_refunded
import homesafe.shared.generated.resources.packages_event_return_sent
import homesafe.shared.generated.resources.packages_event_return_started
import homesafe.shared.generated.resources.packages_event_scan
import homesafe.shared.generated.resources.packages_event_shipped
import homesafe.shared.generated.resources.packages_event_still_out
import homesafe.shared.generated.resources.packages_event_taken_in
import homesafe.shared.generated.resources.packages_from_carrier
import homesafe.shared.generated.resources.packages_from_shop
import homesafe.shared.generated.resources.packages_promise_between
import homesafe.shared.generated.resources.packages_promise_by
import homesafe.shared.generated.resources.packages_range
import homesafe.shared.generated.resources.packages_stage_delivered
import homesafe.shared.generated.resources.packages_stage_ordered
import homesafe.shared.generated.resources.packages_stage_out
import homesafe.shared.generated.resources.packages_stage_shipped
import homesafe.shared.generated.resources.packages_status_attempted
import homesafe.shared.generated.resources.packages_status_delivered_at
import homesafe.shared.generated.resources.packages_status_late
import homesafe.shared.generated.resources.packages_status_late_plain
import homesafe.shared.generated.resources.packages_status_may_be_gone
import homesafe.shared.generated.resources.packages_status_out
import homesafe.shared.generated.resources.packages_status_problem
import homesafe.shared.generated.resources.packages_surprise
import homesafe.shared.generated.resources.packages_today
import homesafe.shared.generated.resources.packages_tomorrow
import homesafe.shared.generated.resources.packages_unexpected
import homesafe.shared.generated.resources.packages_unnamed
import homesafe.shared.generated.resources.packages_verdict_camera_only
import homesafe.shared.generated.resources.packages_verdict_confirmed
import homesafe.shared.generated.resources.packages_verdict_not_seen
import homesafe.shared.generated.resources.packages_verdict_seen
import homesafe.shared.generated.resources.packages_verdict_short_camera_only
import homesafe.shared.generated.resources.packages_verdict_short_confirmed
import homesafe.shared.generated.resources.packages_verdict_short_not_seen
import homesafe.shared.generated.resources.packages_verdict_short_seen
import homesafe.shared.generated.resources.packages_when_day_at
import homesafe.shared.generated.resources.packages_when_today_at
import homesafe.shared.generated.resources.packages_when_yesterday_at
import homesafe.shared.generated.resources.packages_yesterday
import homesafe.shared.generated.resources.weather_time_am
import homesafe.shared.generated.resources.weather_time_pm
import homesafe.shared.generated.resources.weather_weekday_fri
import homesafe.shared.generated.resources.weather_weekday_long_fri
import homesafe.shared.generated.resources.weather_weekday_long_mon
import homesafe.shared.generated.resources.weather_weekday_long_sat
import homesafe.shared.generated.resources.weather_weekday_long_sun
import homesafe.shared.generated.resources.weather_weekday_long_thu
import homesafe.shared.generated.resources.weather_weekday_long_tue
import homesafe.shared.generated.resources.weather_weekday_long_wed
import homesafe.shared.generated.resources.weather_weekday_mon
import homesafe.shared.generated.resources.weather_weekday_sat
import homesafe.shared.generated.resources.weather_weekday_sun
import homesafe.shared.generated.resources.weather_weekday_thu
import homesafe.shared.generated.resources.weather_weekday_tue
import homesafe.shared.generated.resources.weather_weekday_wed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** How the packages screens write dates, times and what is known of a parcel. */
internal object PackagesFormat {
    val MONTHS = listOf(
        Res.string.finance_month_jan, Res.string.finance_month_feb, Res.string.finance_month_mar, Res.string.finance_month_apr,
        Res.string.finance_month_may, Res.string.finance_month_jun, Res.string.finance_month_jul, Res.string.finance_month_aug,
        Res.string.finance_month_sep, Res.string.finance_month_oct, Res.string.finance_month_nov, Res.string.finance_month_dec,
    )
    val WEEKDAYS = listOf(
        Res.string.weather_weekday_mon,
        Res.string.weather_weekday_tue,
        Res.string.weather_weekday_wed,
        Res.string.weather_weekday_thu,
        Res.string.weather_weekday_fri,
        Res.string.weather_weekday_sat,
        Res.string.weather_weekday_sun,
    )
    val WEEKDAYS_LONG = listOf(
        Res.string.weather_weekday_long_mon,
        Res.string.weather_weekday_long_tue,
        Res.string.weather_weekday_long_wed,
        Res.string.weather_weekday_long_thu,
        Res.string.weather_weekday_long_fri,
        Res.string.weather_weekday_long_sat,
        Res.string.weather_weekday_long_sun,
    )

    fun money(amount: Double): String {
        val cents = kotlin.math.round(amount * 100).toLong()
        val whole = (cents / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        return "$$whole.${(cents % 100).toString().padStart(2, '0')}"
    }

    /** A person's mailbox name as the household says it: "sarah" is Sarah. */
    fun person(name: String): String = name.replaceFirstChar { it.uppercase() }

    fun localDay(epochSeconds: Long, zone: ParcelZone): Int = (epochSeconds + zone.offsetAt(epochSeconds)).floorDiv(86_400L).toInt()

    fun minuteOfDay(epochSeconds: Long, zone: ParcelZone): Int = ((epochSeconds + zone.offsetAt(epochSeconds)).mod(86_400L) / 60).toInt()
}

/** "Oct 31", with "Fri, " before it for [weekday]. */
@Composable
internal fun dateText(day: Int, weekday: Boolean = false, year: Boolean = false): String {
    val (y, m, d) = ParcelDays.date(day)
    val date = if (year) {
        stringResource(Res.string.finance_date_month_day_year, stringResource(PackagesFormat.MONTHS[m - 1]), d, y)
    } else {
        stringResource(Res.string.finance_date_month_day, stringResource(PackagesFormat.MONTHS[m - 1]), d)
    }
    return if (weekday) stringResource(Res.string.packages_date_weekday, stringResource(PackagesFormat.WEEKDAYS[ParcelDays.weekday(day)]), date) else date
}

/** "4:05 PM". */
@Composable
internal fun clockText(minuteOfDay: Int): String {
    val hour = minuteOfDay / 60 % 24
    val minute = (minuteOfDay % 60).toString().padStart(2, '0')
    return stringResource(if (hour < 12) Res.string.weather_time_am else Res.string.weather_time_pm, "${(hour + 11) % 12 + 1}:$minute")
}

/** A clock time the relay gave as "14:30". */
@Composable
internal fun clockText(hhmm: String): String {
    val parts = hhmm.split(':').mapNotNull { it.toIntOrNull() }
    return clockText(parts.getOrElse(0) { 0 } * 60 + parts.getOrElse(1) { 0 })
}

/** "2 PM". */
@Composable
internal fun hourText(hour: Int): String = stringResource(if (hour.mod(24) < 12) Res.string.weather_time_am else Res.string.weather_time_pm, ((hour.mod(24) + 11) % 12 + 1).toString())

/** "Today, 4:05 PM", "Yesterday, 9:12 AM", or "Fri, Oct 24, 4:05 PM". */
@Composable
internal fun momentText(epochSeconds: Long, today: Int, zone: ParcelZone): String {
    val day = PackagesFormat.localDay(epochSeconds, zone)
    val clock = clockText(PackagesFormat.minuteOfDay(epochSeconds, zone))
    return when (day) {
        today -> stringResource(Res.string.packages_when_today_at, clock)
        today - 1 -> stringResource(Res.string.packages_when_yesterday_at, clock)
        else -> stringResource(Res.string.packages_when_day_at, dateText(day, weekday = true), clock)
    }
}

/** "12 min ago", "3 hours ago", "2 days ago". */
@Composable
internal fun agoText(epochSeconds: Long, now: Long): String {
    val seconds = (now - epochSeconds).coerceAtLeast(0)
    return when {
        seconds < 90 -> stringResource(Res.string.packages_ago_now)
        seconds < 3600 -> pluralStringResource(Res.plurals.packages_ago_minutes, (seconds / 60).toInt(), (seconds / 60).toInt())
        seconds < 86_400 -> pluralStringResource(Res.plurals.packages_ago_hours, (seconds / 3600).toInt(), (seconds / 3600).toInt())
        else -> pluralStringResource(Res.plurals.packages_ago_days, (seconds / 86_400).toInt(), (seconds / 86_400).toInt())
    }
}

/** A day relative to today: "Today", "Tomorrow", "Tuesday", or a date further off. */
@Composable
internal fun dayWord(day: Int, today: Int): String = when {
    day == today -> stringResource(Res.string.packages_today)
    day == today + 1 -> stringResource(Res.string.packages_tomorrow)
    day == today - 1 -> stringResource(Res.string.packages_yesterday)
    day in (today + 2)..(today + 6) -> stringResource(PackagesFormat.WEEKDAYS_LONG[ParcelDays.weekday(day)])
    else -> dateText(day)
}

/** What it is called: its nickname, or what its email said, or whose it is from; a surprise is only that. */
@Composable
internal fun parcelName(parcel: Parcel): String = when {
    parcel.masked -> stringResource(Res.string.packages_surprise)
    parcel.nickname != null -> parcel.nickname
    parcel.title != null -> parcel.title
    parcel.fromCamera -> stringResource(Res.string.packages_unexpected)
    parcel.shop != null -> stringResource(Res.string.packages_from_shop, parcel.shop)
    parcel.carrier.label.isNotEmpty() -> stringResource(Res.string.packages_from_carrier, parcel.carrier.label)
    else -> stringResource(Res.string.packages_unnamed)
}

/** Where it stands, in a few words. */
@Composable
internal fun parcelStatus(parcel: Parcel, today: Int, zone: ParcelZone): String = when {
    parcel.problem == ParcelProblem.ATTEMPTED -> stringResource(Res.string.packages_status_attempted)
    parcel.problem == ParcelProblem.EXCEPTION -> stringResource(Res.string.packages_status_problem)
    parcel.porch == PorchState.MAY_BE_GONE -> stringResource(Res.string.packages_status_may_be_gone)
    parcel.delivered -> parcel.deliveredAt?.let { stringResource(Res.string.packages_status_delivered_at, momentText(it, today, zone)) } ?: stringResource(Res.string.packages_stage_delivered)
    parcel.late -> parcel.expectedToDay?.let { stringResource(Res.string.packages_status_late, dateText(it)) } ?: stringResource(Res.string.packages_status_late_plain)
    else -> promiseText(parcel, today) ?: stringResource(stageName(parcel.stage))
}

/** When it is promised: "Today by 9 PM", "Today, 2:30 PM - 4:30 PM", "Tuesday", "Nov 3 - Nov 8". Null when nobody has said. */
@Composable
internal fun promiseText(parcel: Parcel, today: Int): String? {
    val due = parcel.expectedToDay ?: parcel.expectedFromDay ?: today
    // Out on the van says more than a day already here or gone by, unless there's an hour to give.
    if (parcel.stage == ParcelStage.OUT_FOR_DELIVERY && (due < today || (due == today && parcel.windowEnd == null))) return stringResource(Res.string.packages_status_out)
    val from = parcel.expectedFromDay ?: return null
    val to = parcel.expectedToDay ?: from
    if (to < today) return dateText(to)
    val first = maxOf(from, today)
    if (first != to) return stringResource(Res.string.packages_range, dayWord(first, today), dayWord(to, today))
    val day = dayWord(to, today)
    return when {
        parcel.windowStart != null && parcel.windowEnd != null -> stringResource(Res.string.packages_promise_between, day, clockText(parcel.windowStart), clockText(parcel.windowEnd))
        parcel.windowEnd != null -> stringResource(Res.string.packages_promise_by, day, clockText(parcel.windowEnd))
        else -> day
    }
}

internal fun stageName(stage: ParcelStage): StringResource = when (stage) {
    ParcelStage.ORDERED -> Res.string.packages_stage_ordered
    ParcelStage.SHIPPED -> Res.string.packages_stage_shipped
    ParcelStage.OUT_FOR_DELIVERY -> Res.string.packages_stage_out
    ParcelStage.DELIVERED -> Res.string.packages_stage_delivered
}

/** How far along its road a parcel has got, for the route strip. */
internal fun Parcel.progress(today: Int): Float = when (stage) {
    ParcelStage.ORDERED -> 0.08f

    ParcelStage.SHIPPED -> {
        // Along the shipped stretch by how near its promised day is.
        val to = expectedToDay
        val left = if (to == null) 3 else (to - today).coerceIn(0, 5)
        0.28f + (5 - left) * 0.07f
    }

    ParcelStage.OUT_FOR_DELIVERY -> 0.86f

    ParcelStage.DELIVERED -> 1f
}

/** What the camera's verdict amounts to, in one line. */
internal fun verdictHeadline(verdict: CameraVerdict): StringResource = when (verdict) {
    CameraVerdict.CONFIRMED -> Res.string.packages_verdict_confirmed
    CameraVerdict.SEEN -> Res.string.packages_verdict_seen
    CameraVerdict.NOT_SEEN -> Res.string.packages_verdict_not_seen
    CameraVerdict.CAMERA_ONLY -> Res.string.packages_verdict_camera_only
}

internal fun verdictShort(verdict: CameraVerdict): StringResource = when (verdict) {
    CameraVerdict.CONFIRMED -> Res.string.packages_verdict_short_confirmed
    CameraVerdict.SEEN -> Res.string.packages_verdict_short_seen
    CameraVerdict.NOT_SEEN -> Res.string.packages_verdict_short_not_seen
    CameraVerdict.CAMERA_ONLY -> Res.string.packages_verdict_short_camera_only
}

/** A timeline line's words, by the relay's name for what happened. A kind this build doesn't know isn't shown. */
internal fun eventText(event: ParcelEvent): StringResource? = when (event.kind) {
    "ordered" -> Res.string.packages_event_ordered
    "shipped" -> Res.string.packages_event_shipped
    "out_for_delivery" -> Res.string.packages_event_out
    "delivered" -> Res.string.packages_event_delivered
    "expected" -> Res.string.packages_event_expected
    "attempted" -> Res.string.packages_event_attempted
    "exception" -> Res.string.packages_event_exception
    "refund" -> Res.string.packages_event_refund
    "return" -> Res.string.packages_event_return
    "added" -> Res.string.packages_event_added
    "scan" -> Res.string.packages_event_scan
    "camera_confirmed" -> Res.string.packages_event_camera_confirmed
    "camera_seen" -> Res.string.packages_event_camera_seen
    "camera_not_seen" -> Res.string.packages_event_camera_not_seen
    "camera_only" -> Res.string.packages_event_camera_only
    "taken_in" -> Res.string.packages_event_taken_in
    "still_out" -> Res.string.packages_event_still_out
    "may_be_gone" -> Res.string.packages_event_may_be_gone
    "marked_delivered" -> Res.string.packages_event_marked
    "return_started" -> Res.string.packages_event_return_started
    "return_sent" -> Res.string.packages_event_return_sent
    "return_refunded" -> Res.string.packages_event_return_refunded
    "gone_quiet" -> Res.string.packages_event_quiet
    else -> null
}

/** The claim kit: everything someone needs to tell a shop or a carrier that a parcel didn't come, as one piece of text. */
internal fun claimText(parcel: Parcel, cameraName: String, zone: ParcelZone): UiText {
    val lines = buildList<UiText> {
        add(UiText.of(Res.string.packages_claim_intro))
        parcel.shop?.let { add(UiText.of(Res.string.packages_claim_shop, it)) }
        parcel.title?.let { add(UiText.of(Res.string.packages_claim_item, it)) }
        parcel.orderId?.let { add(UiText.of(Res.string.packages_claim_order, it)) }
        parcel.tracking?.let { add(UiText.of(Res.string.packages_claim_tracking, it)) }
        (parcel.carrier.label.ifEmpty { parcel.carrierName })?.takeIf { it.isNotEmpty() }?.let { add(UiText.of(Res.string.packages_claim_carrier, it)) }
        parcel.total?.let { add(UiText.of(Res.string.packages_claim_total, PackagesFormat.money(it))) }
        parcel.expectedToDay?.let { add(UiText.of(Res.string.packages_claim_promised, ParcelDays.format(it))) }
        parcel.deliveredAt?.let { add(UiText.of(Res.string.packages_claim_marked, timestamp(it, zone))) }
        when (parcel.verdict) {
            CameraVerdict.NOT_SEEN -> add(UiText.of(Res.string.packages_claim_camera_nobody, cameraName))
            CameraVerdict.SEEN, CameraVerdict.CONFIRMED -> parcel.visitAt?.let { add(UiText.of(Res.string.packages_claim_camera_visit, cameraName, timestamp(it, zone))) }
            else -> Unit
        }
        if (parcel.porch == PorchState.MAY_BE_GONE) add(UiText.of(Res.string.packages_claim_gone))
        parcel.leftNote?.let { add(UiText.of(Res.string.packages_claim_left, it)) }
        add(UiText.of(Res.string.packages_claim_ask))
    }
    return UiText.Joined(lines, UiText.Verbatim("\n"))
}

/** A moment as 2026-10-31 16:05, on the household's clock: for a claim, where a day's name in the app's language is no use to anyone. */
internal fun timestamp(epochSeconds: Long, zone: ParcelZone): String {
    val minute = PackagesFormat.minuteOfDay(epochSeconds, zone)
    return "${ParcelDays.format(PackagesFormat.localDay(epochSeconds, zone))} ${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
}

/** Whole days between now and a day: how long a return has left. */
internal fun daysLeft(day: Int, today: Int): Int = abs(day - today).let { if (day < today) -it else it }

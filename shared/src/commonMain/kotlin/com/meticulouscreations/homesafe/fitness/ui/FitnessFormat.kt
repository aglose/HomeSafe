package com.meticulouscreations.homesafe.fitness.ui

import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.PaceVerdict
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.Record
import com.meticulouscreations.homesafe.fitness.domain.RecordKind
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.Target
import com.meticulouscreations.homesafe.fitness.domain.TargetReason
import com.meticulouscreations.homesafe.fitness.domain.VolumeStatus
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_date_month_day
import homesafe.shared.generated.resources.fitness_date_month_day_year
import homesafe.shared.generated.resources.fitness_pace_bulk_fast
import homesafe.shared.generated.resources.fitness_pace_bulk_on
import homesafe.shared.generated.resources.fitness_pace_bulk_slow
import homesafe.shared.generated.resources.fitness_pace_cut_fast
import homesafe.shared.generated.resources.fitness_pace_cut_on
import homesafe.shared.generated.resources.fitness_pace_cut_slow
import homesafe.shared.generated.resources.fitness_pace_steady
import homesafe.shared.generated.resources.fitness_reason_add_rep
import homesafe.shared.generated.resources.fitness_reason_add_weight
import homesafe.shared.generated.resources.fitness_reason_back_off
import homesafe.shared.generated.resources.fitness_reason_benchmark
import homesafe.shared.generated.resources.fitness_reason_ease_in
import homesafe.shared.generated.resources.fitness_reason_hold
import homesafe.shared.generated.resources.fitness_reason_rebuild
import homesafe.shared.generated.resources.fitness_record_all_time_reps
import homesafe.shared.generated.resources.fitness_record_all_time_weight
import homesafe.shared.generated.resources.fitness_record_phase_bulk
import homesafe.shared.generated.resources.fitness_record_phase_cut
import homesafe.shared.generated.resources.fitness_record_phase_maintain
import homesafe.shared.generated.resources.fitness_set_line
import homesafe.shared.generated.resources.fitness_volume_building
import homesafe.shared.generated.resources.fitness_volume_high
import homesafe.shared.generated.resources.fitness_volume_low
import homesafe.shared.generated.resources.fitness_volume_none
import homesafe.shared.generated.resources.fitness_volume_on_target
import homesafe.shared.generated.resources.fitness_weight_bodyweight
import homesafe.shared.generated.resources.fitness_weight_bodyweight_plus
import homesafe.shared.generated.resources.fitness_weight_level
import homesafe.shared.generated.resources.fitness_weight_per_hand
import homesafe.shared.generated.resources.fitness_weight_plates
import homesafe.shared.generated.resources.fitness_weight_plates_plus
import homesafe.shared.generated.resources.fitness_weight_pounds
import homesafe.shared.generated.resources.fitness_when_days_ago
import homesafe.shared.generated.resources.fitness_when_today
import homesafe.shared.generated.resources.fitness_when_yesterday
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How the fitness screens write a weight, a set, a day and a line of coaching. */
internal object FitnessFormat {
    private const val PLATE_POUNDS = 45.0

    /** A number without a tail it doesn't need: 185, 17.5, 71.5. */
    fun number(value: Double): String {
        val tenths = (value * 10).roundToLong()
        return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${abs(tenths % 10)}"
    }

    /** [weight] as its exercise counts it: "185 lb", "44 lb × 2", "4 plates + 25", "Pin 14", "Bodyweight + 30 lb". */
    fun weight(kind: LoadKind, weight: Double): UiText = when (kind) {
        LoadKind.WEIGHT -> UiText.of(Res.string.fitness_weight_pounds, number(weight))

        LoadKind.PER_HAND -> UiText.of(Res.string.fitness_weight_per_hand, number(weight))

        LoadKind.LEVEL -> UiText.of(Res.string.fitness_weight_level, number(weight))

        LoadKind.BODYWEIGHT -> if (weight <= 0.0) UiText.of(Res.string.fitness_weight_bodyweight) else UiText.of(Res.string.fitness_weight_bodyweight_plus, number(weight))

        LoadKind.PLATES -> {
            val side = weight / 2
            val plates = (side / PLATE_POUNDS + 1e-6).toInt()
            val extra = side - plates * PLATE_POUNDS
            when {
                plates == 0 -> UiText.of(Res.string.fitness_weight_pounds, number(weight))
                extra < 0.05 -> UiText.plural(Res.plurals.fitness_weight_plates, plates)
                else -> UiText.plural(Res.plurals.fitness_weight_plates_plus, plates, plates, number(extra))
            }
        }
    }

    /** A set in one breath: "320 lb × 10". */
    fun set(kind: LoadKind, weight: Double, reps: Int): UiText = UiText.of(Res.string.fitness_set_line, weight(kind, weight), reps)

    /** The weight alone, as short as it goes, for a ladder's foot or a chip: the number, since the unit is the exercise's. */
    fun short(weight: Double): String = number(weight)

    /** The step the big buttons move a weight by, written on them. */
    fun step(value: Double): String = number(value)

    fun reason(target: Target): UiText = when (target.reason) {
        TargetReason.BENCHMARK -> UiText.of(Res.string.fitness_reason_benchmark)
        TargetReason.ADD_REP -> UiText.of(Res.string.fitness_reason_add_rep)
        TargetReason.ADD_WEIGHT -> UiText.of(Res.string.fitness_reason_add_weight)
        TargetReason.HOLD -> UiText.of(Res.string.fitness_reason_hold)
        TargetReason.BACK_OFF -> UiText.of(Res.string.fitness_reason_back_off)
        TargetReason.REBUILD -> UiText.of(Res.string.fitness_reason_rebuild, (target.ofBest * 100).roundToInt())
        TargetReason.EASE_IN -> UiText.of(Res.string.fitness_reason_ease_in)
    }

    fun record(record: Record, phase: PhaseKind): StringResource = when (record.scope) {
        RecordScope.ALL_TIME -> if (record.kind == RecordKind.WEIGHT) Res.string.fitness_record_all_time_weight else Res.string.fitness_record_all_time_reps

        RecordScope.PHASE -> when (phase) {
            PhaseKind.CUT -> Res.string.fitness_record_phase_cut
            PhaseKind.BULK -> Res.string.fitness_record_phase_bulk
            PhaseKind.MAINTAIN -> Res.string.fitness_record_phase_maintain
        }
    }

    fun volume(status: VolumeStatus): StringResource = when (status) {
        VolumeStatus.NONE -> Res.string.fitness_volume_none
        VolumeStatus.LOW -> Res.string.fitness_volume_low
        VolumeStatus.BUILDING -> Res.string.fitness_volume_building
        VolumeStatus.ON_TARGET -> Res.string.fitness_volume_on_target
        VolumeStatus.HIGH -> Res.string.fitness_volume_high
    }

    /** A weekly change of bodyweight as a share of it: 0.007 is "0.7%". */
    fun percent(fraction: Double): String = number(abs(fraction) * 100) + "%"

    fun pace(phase: PhaseKind, verdict: PaceVerdict, weeklyChange: Double): UiText {
        val rate = (
            if (weeklyChange > 0) {
                "+"
            } else if (weeklyChange < 0) {
                "−"
            } else {
                ""
            }
            ) + percent(weeklyChange)
        return when (phase) {
            PhaseKind.CUT -> when (verdict) {
                PaceVerdict.TOO_FAST -> UiText.of(Res.string.fitness_pace_cut_fast, percent(weeklyChange))
                PaceVerdict.ON_PACE -> UiText.of(Res.string.fitness_pace_cut_on, percent(weeklyChange))
                else -> UiText.of(Res.string.fitness_pace_cut_slow, rate)
            }

            PhaseKind.BULK -> when (verdict) {
                PaceVerdict.TOO_FAST -> UiText.of(Res.string.fitness_pace_bulk_fast, percent(weeklyChange))
                PaceVerdict.ON_PACE -> UiText.of(Res.string.fitness_pace_bulk_on, percent(weeklyChange))
                else -> UiText.of(Res.string.fitness_pace_bulk_slow, rate)
            }

            PhaseKind.MAINTAIN -> UiText.of(Res.string.fitness_pace_steady, rate)
        }
    }

    /** How long ago [epochSeconds] was, by the calendar: "Today", "Yesterday", "6 days ago", and past a month the date. */
    fun whenText(epochSeconds: Long, nowEpochSeconds: Long, utcOffsetSeconds: Int): UiText {
        val days = (nowEpochSeconds + utcOffsetSeconds).floorDiv(SECONDS_PER_DAY) - (epochSeconds + utcOffsetSeconds).floorDiv(SECONDS_PER_DAY)
        return when {
            days <= 0L -> UiText.of(Res.string.fitness_when_today)
            days == 1L -> UiText.of(Res.string.fitness_when_yesterday)
            days <= 45L -> UiText.plural(Res.plurals.fitness_when_days_ago, days.toInt())
            else -> date(epochSeconds, utcOffsetSeconds, withYear = days > 300)
        }
    }

    /** A day as month and day in figures ("10/8"), with the year when it isn't this one's business to guess. */
    fun date(epochSeconds: Long, utcOffsetSeconds: Int, withYear: Boolean = false): UiText {
        val (year, month, day) = civil((epochSeconds + utcOffsetSeconds).floorDiv(SECONDS_PER_DAY))
        return if (withYear) {
            UiText.of(Res.string.fitness_date_month_day_year, month, day, (year % 100).toString().padStart(2, '0'))
        } else {
            UiText.of(Res.string.fitness_date_month_day, month, day)
        }
    }

    /** The civil date of a day counted from 1970 (Howard Hinnant's `civil_from_days`). */
    fun civil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }

    /** Minutes and seconds of a rest: "1:30". */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Data that is already words (a name, a note). */
    fun verbatim(text: String): UiText = text.asUiText()
}

package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Composable
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.household_cars_seen_days
import homesafe.shared.generated.resources.household_cars_seen_hours
import homesafe.shared.generated.resources.household_cars_seen_just_now
import homesafe.shared.generated.resources.household_cars_seen_minutes
import homesafe.shared.generated.resources.household_cars_where_away
import homesafe.shared.generated.resources.household_cars_where_driving
import homesafe.shared.generated.resources.household_cars_where_home
import homesafe.shared.generated.resources.household_cars_where_unknown
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Where [car] is, in a word or two: the street it is parked on, else home, driving or away (by
 * where Tesla last saw it), or not seen yet. Home's car pill and the cars' map both say this.
 */
@Composable
fun carWhereabouts(car: HouseholdCar): String {
    val seen = car.lastSeen
    return when {
        car.parked != null -> car.parked.street
        seen == null -> stringResource(Res.string.household_cars_where_unknown)
        seen.moving && !car.asleep -> stringResource(Res.string.household_cars_where_driving)
        seen.home == true -> stringResource(Res.string.household_cars_where_home)
        else -> stringResource(Res.string.household_cars_where_away)
    }
}

/** How long ago Tesla last saw [car], counted from [nowEpochSeconds]: "Seen 12 min ago"; null when it never has. */
@Composable
fun carSeen(car: HouseholdCar, nowEpochSeconds: Long): String? {
    val at = car.lastSeen?.atEpochSeconds?.takeIf { it > 0 } ?: return null
    val minutes = ((nowEpochSeconds - at) / 60).coerceAtLeast(0).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.household_cars_seen_just_now)
        minutes < 60 -> pluralStringResource(Res.plurals.household_cars_seen_minutes, minutes, minutes)
        minutes < 48 * 60 -> pluralStringResource(Res.plurals.household_cars_seen_hours, minutes / 60, minutes / 60)
        else -> pluralStringResource(Res.plurals.household_cars_seen_days, minutes / (24 * 60), minutes / (24 * 60))
    }
}

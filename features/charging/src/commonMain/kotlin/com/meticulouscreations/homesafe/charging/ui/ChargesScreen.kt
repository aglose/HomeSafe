package com.meticulouscreations.homesafe.charging.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricCar
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.charging.ChargingUiState
import com.meticulouscreations.homesafe.charging.domain.ChargePlace
import com.meticulouscreations.homesafe.charging.domain.ChargeSession
import com.meticulouscreations.homesafe.ui.handCursor
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_battery_range
import homesafe.shared.generated.resources.charging_charge_estimated
import homesafe.shared.generated.resources.charging_charge_no_price
import homesafe.shared.generated.resources.charging_charge_now
import homesafe.shared.generated.resources.charging_charge_when
import homesafe.shared.generated.resources.charging_charges_all
import homesafe.shared.generated.resources.charging_charges_all_cars
import homesafe.shared.generated.resources.charging_charges_empty
import homesafe.shared.generated.resources.charging_charges_load_more
import homesafe.shared.generated.resources.charging_place_fast_short
import homesafe.shared.generated.resources.charging_place_home
import homesafe.shared.generated.resources.charging_place_other
import org.jetbrains.compose.resources.stringResource

/** The ways the list of charges can be narrowed by place. */
private enum class PlaceFilter { ALL, HOME, FAST, OTHER }

/** Every charge, newest first, narrowed to a car and a kind of place. */
@Composable
internal fun ChargesScreen(
    state: ChargingUiState,
    car: String?,
    padding: PaddingValues,
    onChooseCar: (String?) -> Unit,
    onOpenCharge: (Long) -> Unit,
    onLoadMore: () -> Unit,
) {
    val colors = ChargingTheme.colors
    var placeName by rememberSaveable { mutableStateOf(PlaceFilter.ALL.name) }
    val place = PlaceFilter.valueOf(placeName)
    val sessions = state.sessions
    val cars = sessions.map { it.car to it.carName }.distinct().sortedBy { it.second }
    val shown = sessions.filter { session ->
        (car == null || session.car == car) && when (place) {
            PlaceFilter.ALL -> true
            PlaceFilter.HOME -> session.place == ChargePlace.HOME
            PlaceFilter.FAST -> session.place.isFast
            PlaceFilter.OTHER -> session.place == ChargePlace.OTHER
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("charging_charges"), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "filters", contentType = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterPill(stringResource(Res.string.charging_charges_all), place == PlaceFilter.ALL, "charging_filter_all") { placeName = PlaceFilter.ALL.name }
                    FilterPill(stringResource(Res.string.charging_place_home), place == PlaceFilter.HOME, "charging_filter_home") { placeName = PlaceFilter.HOME.name }
                    FilterPill(stringResource(Res.string.charging_place_fast_short), place == PlaceFilter.FAST, "charging_filter_fast") { placeName = PlaceFilter.FAST.name }
                    FilterPill(stringResource(Res.string.charging_place_other), place == PlaceFilter.OTHER, "charging_filter_other") { placeName = PlaceFilter.OTHER.name }
                }
                // A car asked for from the Cars page may have no charges yet, so the way back is always there.
                if (cars.size > 1 || car != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (car != null) FilterPill(stringResource(Res.string.charging_charges_all_cars), false, "charging_filter_all_cars") { onChooseCar(null) }
                        cars.forEach { (id, name) ->
                            FilterPill(name, car == id, "charging_filter_car_$id") { onChooseCar(if (car == id) null else id) }
                        }
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(stringResource(Res.string.charging_charges_empty), style = ChargingTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        items(shown, key = { it.id }, contentType = { "charge" }) { session ->
            ChargeRow(session, state.nowEpochSeconds, onOpenCharge)
        }
        if (state.moreSessions) {
            item(key = "more", contentType = "more") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.handCursor().testTag("charging_load_more")) {
                        Text(stringResource(Res.string.charging_charges_load_more), color = colors.home)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = ChargingTheme.colors
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) colors.home.copy(alpha = 0.2f) else colors.surface)
            .border(1.dp, if (selected) colors.home.copy(alpha = 0.6f) else colors.hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .handCursor()
            .semantics { this.selected = selected }
            .testTag(tag)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = ChargingTheme.type.label, color = if (selected) colors.text else colors.textMuted, maxLines = 1)
    }
}

internal val ChargePlace.icon: ImageVector
    get() = when (this) {
        ChargePlace.HOME -> Icons.Filled.Home
        ChargePlace.SUPERCHARGER, ChargePlace.FAST -> Icons.Filled.Bolt
        ChargePlace.OTHER -> Icons.Filled.EvStation
    }

/** A charge in the list: whose, where, when, how much, and what it cost. */
@Composable
private fun ChargeRow(session: ChargeSession, nowEpochSeconds: Long, onOpen: (Long) -> Unit) {
    val colors = ChargingTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val color = colors.place(session.place)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape)
            .clickable(role = Role.Button) { onOpen(session.id) }
            .handCursor()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("charging_charge_${session.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(if (session.isOpen) Icons.Filled.ElectricCar else session.place.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(session.site ?: stringResource(session.place.label), style = ChargingTheme.type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.charging_charge_when, session.carName, dayText(session.startedEpochSeconds), timeText(session.startedEpochSeconds)),
                style = ChargingTheme.type.label,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(durationText(session.durationSeconds(nowEpochSeconds)), style = ChargingTheme.type.label, color = colors.textFaint)
                val from = session.socStart
                val to = session.socEnd
                if (from != null && to != null && to > from) Text(stringResource(Res.string.charging_battery_range, from.toString(), to.toString()), style = ChargingTheme.type.label, color = colors.textFaint)
                if (session.isEstimate) Text(stringResource(Res.string.charging_charge_estimated), style = ChargingTheme.type.label, color = colors.peak)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            val cost = session.cost
            Text(if (cost != null) moneyText(cost) else stringResource(Res.string.charging_charge_no_price), style = ChargingTheme.type.bodyStrong, color = if (cost != null) colors.text else colors.textFaint)
            Text(kwhText(session.kwh), style = ChargingTheme.type.label, color = colors.textMuted)
            session.pricePerKwh?.takeIf { !session.isOpen }?.let { Text(rateText(it), style = ChargingTheme.type.label, color = colors.textFaint) }
            if (session.isOpen) Text(stringResource(Res.string.charging_charge_now), style = ChargingTheme.type.label, color = colors.home)
        }
    }
}

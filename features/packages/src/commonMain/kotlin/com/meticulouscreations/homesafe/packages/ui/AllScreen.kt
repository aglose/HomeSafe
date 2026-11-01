package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.packages.PackagesUiState
import com.meticulouscreations.homesafe.packages.domain.Carrier
import com.meticulouscreations.homesafe.packages.domain.ParcelDays
import com.meticulouscreations.homesafe.packages.domain.ParcelFilter
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_date_month_year
import homesafe.shared.generated.resources.packages_filter_active
import homesafe.shared.generated.resources.packages_search_hint
import homesafe.shared.generated.resources.packages_search_none
import org.jetbrains.compose.resources.stringResource

/** Everything that has ever come or is coming, to search through and narrow down, a month at a time. */
@Composable
internal fun AllScreen(state: PackagesUiState, padding: PaddingValues, active: Boolean, onOpen: (String) -> Unit) {
    val colors = PackagesTheme.colors
    val snapshot = state.snapshot
    var query by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable { mutableStateOf<String?>(null) }
    var carrier by rememberSaveable { mutableStateOf<Carrier?>(null) }
    var onlyActive by rememberSaveable { mutableStateOf(false) }
    val filter = ParcelFilter(query, owner, carrier, onlyActive)
    val shown by remember(snapshot.parcels, query, owner, carrier, onlyActive) {
        derivedStateOf { snapshot.parcels.filter(filter::matches).sortedByDescending { it.deliveredAt ?: it.updatedAt } }
    }
    val carriers = remember(snapshot.parcels) { snapshot.parcels.map { it.carrier }.filter { it != Carrier.OTHER }.distinct() }
    val byMonth = remember(shown, state.zone) {
        shown.groupBy { parcel ->
            val (y, m, _) = ParcelDays.date(PackagesFormat.localDay(parcel.deliveredAt ?: parcel.updatedAt, state.zone))
            y * 12 + m - 1
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("packages_all"), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "filters") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                PackagesField(query, { query = it }, stringResource(Res.string.packages_search_hint), Modifier.testTag("packages_search"))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    PackagesChoice(stringResource(Res.string.packages_filter_active), onlyActive, { onlyActive = !onlyActive })
                    snapshot.people.forEach { person -> PackagesChoice(PackagesFormat.person(person), owner == person, { owner = person.takeIf { owner != person } }) }
                    carriers.forEach { c -> PackagesChoice(c.label, carrier == c, { carrier = c.takeIf { carrier != c } }, tint = colors.carrier(c)) }
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "none") { Text(stringResource(Res.string.packages_search_none), style = PackagesTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) }
        }
        byMonth.forEach { (month, parcels) ->
            item(key = "m$month") {
                SectionHead(stringResource(Res.string.finance_date_month_year, stringResource(PackagesFormat.MONTHS[month % 12]), month / 12), parcels.size, colors.kraft, Modifier.padding(horizontal = 16.dp))
            }
            items(parcels, key = { it.id }) { parcel ->
                ParcelCard(parcel, snapshot.today, state.zone, active && !parcel.delivered, onOpen, Modifier.padding(horizontal = 16.dp), showRoute = false)
            }
        }
    }
}

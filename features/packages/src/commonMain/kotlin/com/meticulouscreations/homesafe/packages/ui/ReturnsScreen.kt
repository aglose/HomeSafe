package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.packages.PackagesUiState
import com.meticulouscreations.homesafe.packages.domain.PackagesAnalysis
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ReturnState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_return_refunded
import homesafe.shared.generated.resources.packages_return_sent
import homesafe.shared.generated.resources.packages_return_started
import homesafe.shared.generated.resources.packages_returns_by
import homesafe.shared.generated.resources.packages_returns_days_left
import homesafe.shared.generated.resources.packages_returns_moving
import homesafe.shared.generated.resources.packages_returns_none
import homesafe.shared.generated.resources.packages_returns_none_body
import homesafe.shared.generated.resources.packages_returns_open
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What can still go back and how long it has, and the returns already under way. */
@Composable
internal fun ReturnsScreen(state: PackagesUiState, padding: PaddingValues, actions: PackagesActions, onOpen: (String) -> Unit) {
    val colors = PackagesTheme.colors
    val today = state.snapshot.today
    val (open, moving) = remember(state.snapshot.parcels, today) { PackagesAnalysis.returnsOf(state.snapshot.parcels, today) }
    LazyColumn(Modifier.fillMaxSize().testTag("packages_returns"), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (moving.isNotEmpty()) {
            item(key = "head_moving") { SectionHead(stringResource(Res.string.packages_returns_moving), moving.size, colors.onTheWay, Modifier.padding(horizontal = 16.dp)) }
            items(moving, key = { "m${it.id}" }) { parcel -> ReturnRow(parcel, today, onOpen, Modifier.padding(horizontal = 16.dp)) }
        }
        if (open.isNotEmpty()) {
            item(key = "head_open") { SectionHead(stringResource(Res.string.packages_returns_open), open.size, colors.tape, Modifier.padding(horizontal = 16.dp)) }
            items(open, key = { "o${it.id}" }) { parcel -> ReturnRow(parcel, today, onOpen, Modifier.padding(horizontal = 16.dp)) }
        }
        if (open.isEmpty() && moving.isEmpty()) {
            item(key = "none") {
                Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(Res.string.packages_returns_none), style = PackagesTheme.type.headline, color = colors.text)
                    Text(stringResource(Res.string.packages_returns_none_body), style = PackagesTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun ReturnRow(parcel: Parcel, today: Int, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = PackagesTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val by = parcel.returnByDay
    val left = by?.let { daysLeft(it, today) }
    val urgent = left != null && left <= 3 && parcel.returnState == ReturnState.NONE
    Row(modifier.fillMaxWidth().pressable({ onOpen(parcel.id) }).clip(shape).background(colors.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(parcelName(parcel), style = PackagesTheme.type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (parcel.returnState) {
                    ReturnState.STARTED -> stringResource(Res.string.packages_return_started)
                    ReturnState.SENT -> stringResource(Res.string.packages_return_sent)
                    ReturnState.REFUNDED -> stringResource(Res.string.packages_return_refunded)
                    ReturnState.NONE -> by?.let { stringResource(Res.string.packages_returns_by, dateText(it)) }.orEmpty()
                },
                style = PackagesTheme.type.label,
                color = colors.textMuted,
            )
            parcel.shop?.let { Text(it, style = PackagesTheme.type.label, color = colors.textFaint) }
        }
        if (left != null && parcel.returnState == ReturnState.NONE) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(left.toString(), style = PackagesTheme.type.title, color = if (urgent) colors.warn else colors.text)
                Text(pluralStringResource(Res.plurals.packages_returns_days_left, left), style = PackagesTheme.type.micro, color = colors.textFaint)
            }
        }
    }
}

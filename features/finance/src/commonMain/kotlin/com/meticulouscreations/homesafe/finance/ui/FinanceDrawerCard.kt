package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.ui.FinanceFormat
import com.meticulouscreations.homesafe.finance.ui.FinancePalette
import com.meticulouscreations.homesafe.finance.ui.components.Sparkline
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.shell_drawer_change_today
import homesafe.shared.generated.resources.shell_drawer_finance
import homesafe.shared.generated.resources.shell_drawer_finance_locked
import homesafe.shared.generated.resources.shell_drawer_finance_subtitle
import homesafe.shared.generated.resources.shell_drawer_net_worth
import homesafe.shared.generated.resources.shell_drawer_open_finance
import org.jetbrains.compose.resources.stringResource

/**
 * The Finance app's door: its name, today's S&P 500 with its sparkline, and net worth once the
 * sheet is in. While the app is [locked] the net worth gives way to a line saying so: the index is
 * anyone's to see, the household's money isn't.
 */
@Composable
fun FinanceDrawerCard(finance: FinanceUiState, locked: Boolean, onOpen: (Offset) -> Unit, modifier: Modifier = Modifier) {
    val palette = remember { FinancePalette() }
    var center by remember { mutableStateOf(Offset.Zero) }
    val sp = finance.quotes[MarketCatalog.SP500.symbol]
    val dir = palette.direction(sp?.change ?: 0.0)
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF07140A), Color(0xFF000000))))
            .border(1.dp, Brush.linearGradient(listOf(palette.gain.copy(alpha = 0.7f), palette.accent.copy(alpha = 0.2f))), shape)
            .clickable(onClickLabel = stringResource(Res.string.shell_drawer_open_finance)) { onOpen(center) }
            .testTag("drawer_finance")
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(palette.gain), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.shell_drawer_finance), style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(stringResource(Res.string.shell_drawer_finance_subtitle), maxLines = 1, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = palette.textSecondary)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(MarketCatalog.SP500.shortName.resolve(), style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                Text(sp?.let { FinanceFormat.grouped(it.price) } ?: "—", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(sp?.let { stringResource(Res.string.shell_drawer_change_today, FinanceFormat.signedPercent(it.changePercent)) } ?: " ", style = MaterialTheme.typography.labelSmall, color = dir)
            }
            if (sp != null && sp.intraday.size > 1) {
                Sparkline(sp.intraday, dir, Modifier.width(96.dp).height(40.dp), baseline = sp.previousClose)
            }
        }
        if (locked) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("drawer_finance_locked")) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(14.dp))
                Text(stringResource(Res.string.shell_drawer_finance_locked), style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
            }
        } else {
            finance.finance?.netWorth?.let { nw ->
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.shell_drawer_net_worth, FinanceFormat.compactMoney(nw)), style = MaterialTheme.typography.labelMedium, color = palette.accent)
            }
        }
    }
}

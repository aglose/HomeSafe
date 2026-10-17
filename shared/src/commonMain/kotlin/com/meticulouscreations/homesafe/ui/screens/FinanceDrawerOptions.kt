package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.finance_drawer_settings_caption
import homesafe.shared.generated.resources.finance_drawer_settings_full
import homesafe.shared.generated.resources.finance_drawer_settings_markets
import homesafe.shared.generated.resources.finance_drawer_settings_money
import homesafe.shared.generated.resources.finance_drawer_settings_none
import org.jetbrains.compose.resources.stringResource

/**
 * What the Finance card in the drawer shows: the markets and the household's figures, each
 * ticked on or off, up to [FinanceDrawerItem.MAX]. Once that many are ticked the rest wait until
 * one comes off. On the Settings page these are what the "Finance in the menu" row opens out to,
 * the row itself naming what is chosen.
 */
@Composable
internal fun FinanceDrawerOptions(items: List<FinanceDrawerItem>, onItems: (List<FinanceDrawerItem>) -> Unit, modifier: Modifier = Modifier) {
    val full = items.size >= FinanceDrawerItem.MAX
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsCaption(stringResource(Res.string.finance_drawer_settings_caption))
        val (markets, money) = FinanceDrawerItem.entries.partition { it.isMarket }
        listOf(Res.string.finance_drawer_settings_markets to markets, Res.string.finance_drawer_settings_money to money).forEach { (heading, group) ->
            Text(
                stringResource(heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Two to a row: the names are short, and fifteen rows would be a page of their own.
            group.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { option -> DrawerItemOption(option, on = option in items, enabled = option in items || !full, onToggle = { onItems(FinanceDrawerItem.toggled(items, option)) }, modifier = Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (full) SettingsCaption(stringResource(Res.string.finance_drawer_settings_full), modifier = Modifier.padding(top = 4.dp))
    }
}

/** One thing the card can show, ticked on or off; the whole row takes the tap, and the box only shows the choice. */
@Composable
private fun DrawerItemOption(option: FinanceDrawerItem, on: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .toggleable(value = on, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag("settings_finance_drawer_${option.name.lowercase()}")
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = on, onCheckedChange = null, enabled = enabled)
        Text(
            stringResource(option.label),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The "Finance in the menu" row's second line: what the card shows, by short name, or that it shows only its name. */
@Composable
internal fun financeDrawerSummary(items: List<FinanceDrawerItem>): String {
    if (items.isEmpty()) return stringResource(Res.string.finance_drawer_settings_none)
    val separator = stringResource(Res.string.common_list_separator)
    return items.map { stringResource(it.label) }.joinToString(separator)
}

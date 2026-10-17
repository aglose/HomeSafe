// Public on purpose: :androidApp's screenshot tests call these from another module (see HomePreviews.kt).
@file:Suppress("ktlint:compose:preview-public-check")

package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
import com.meticulouscreations.homesafe.finance.ui.FinanceDrawerCard
import com.meticulouscreations.homesafe.finance.ui.FinanceFixtures
import com.meticulouscreations.homesafe.fitness.ui.FitnessDrawerCard
import com.meticulouscreations.homesafe.fitness.ui.FitnessFixtures
import com.meticulouscreations.homesafe.fitness.ui.HealthFixtures
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

/*
 * The drawer's Fitness and Finance cards with their data in, as wide as the drawer draws them:
 * Fitness with and without today's readiness, and Finance as it comes, with several markets, with
 * the household's figures, and locked.
 */

@Composable
private fun DrawerColumn(content: @Composable () -> Unit) {
    FrigateTheme {
        Column(Modifier.background(Color(0xFF1B1B1B)).width(328.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Preview(name = "Drawer, Fitness")
@Composable
fun DrawerFitnessCardsPreview() {
    DrawerColumn {
        FitnessDrawerCard(FitnessFixtures.state(), HealthFixtures.connected().body.readiness, animated = false, onOpen = {})
        FitnessDrawerCard(FitnessFixtures.state(), HealthFixtures.runDown().body.readiness, animated = false, onOpen = {})
        FitnessDrawerCard(FitnessFixtures.state(working = true), readiness = null, animated = false, onOpen = {})
    }
}

@Preview(name = "Drawer, Finance")
@Composable
fun DrawerFinanceCardsPreview() {
    val state = FinanceFixtures.state
    DrawerColumn {
        FinanceDrawerCard(state, locked = false, onOpen = {})
        FinanceDrawerCard(state.copy(drawerItems = listOf(FinanceDrawerItem.SP500, FinanceDrawerItem.DOW, FinanceDrawerItem.NASDAQ, FinanceDrawerItem.BITCOIN)), locked = false, onOpen = {})
        FinanceDrawerCard(state.copy(drawerItems = listOf(FinanceDrawerItem.DOW, FinanceDrawerItem.NET_WORTH, FinanceDrawerItem.CASH, FinanceDrawerItem.DEBT)), locked = false, onOpen = {})
        FinanceDrawerCard(state.copy(drawerItems = listOf(FinanceDrawerItem.SP500, FinanceDrawerItem.TEN_YEAR, FinanceDrawerItem.NET_WORTH)), locked = true, onOpen = {})
    }
}

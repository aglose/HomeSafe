package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.ui.graphics.vector.ImageVector
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_tab_budget
import homesafe.shared.generated.resources.finance_tab_economy
import homesafe.shared.generated.resources.finance_tab_markets
import homesafe.shared.generated.resources.finance_tab_risk
import homesafe.shared.generated.resources.finance_tab_wallet
import org.jetbrains.compose.resources.StringResource

/** The finance app's five tabs, in bottom-nav order. */
enum class FinanceTab(val label: StringResource, val icon: ImageVector) {
    WALLET(Res.string.finance_tab_wallet, Icons.Filled.AccountBalanceWallet),
    BUDGET(Res.string.finance_tab_budget, Icons.Filled.CreditCard),
    MARKETS(Res.string.finance_tab_markets, Icons.AutoMirrored.Filled.ShowChart),
    ECONOMY(Res.string.finance_tab_economy, Icons.Filled.Public),
    RISK(Res.string.finance_tab_risk, Icons.Filled.Radar),
}

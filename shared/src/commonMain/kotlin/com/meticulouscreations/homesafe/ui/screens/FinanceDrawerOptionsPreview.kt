// Public on purpose: :androidApp's screenshot tests call these from another module (see HomePreviews.kt).
@file:Suppress("ktlint:compose:preview-public-check")

package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

/** The Settings fold for the drawer's Finance card, with four chosen: the rest wait. */
@Preview(name = "Settings, Finance in the menu")
@Composable
fun FinanceDrawerOptionsPreview() {
    FrigateTheme {
        FinanceDrawerOptions(
            items = listOf(FinanceDrawerItem.SP500, FinanceDrawerItem.DOW, FinanceDrawerItem.NET_WORTH, FinanceDrawerItem.CASH),
            onItems = {},
            modifier = Modifier.background(MaterialTheme.colorScheme.surface).width(380.dp).padding(16.dp),
        )
    }
}

package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.finance.ui.FinanceTab
import com.meticulouscreations.homesafe.ui.AppSection
import com.meticulouscreations.homesafe.ui.AppSectionsHost
import com.meticulouscreations.homesafe.ui.LocalShortcutKey
import com.meticulouscreations.homesafe.ui.ShortcutKey
import com.meticulouscreations.homesafe.ui.assistant.AssistantHost
import com.meticulouscreations.homesafe.ui.assistant.LocalAssistant
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview

/** The sidebar of a wide window with Home up, as the desktop app opens; with a keyboard, so each row says its shortcut. */
@Preview(name = "Sidebar, Home", widthDp = 248, heightDp = 800)
@Composable
private fun ShellSidebarHomePreview() {
    FrigatePreview {
        CompositionLocalProvider(LocalShortcutKey provides ShortcutKey.Command, LocalAssistant provides remember { AssistantHost() }) {
            ShellSidebar(nav = remember { ShellNavigation() }, sections = remember { AppSectionsHost() }, onHide = {})
        }
    }
}

/** Finance open beside it, its tabs listed under its row with Markets up; on Android's keyboard, so Ctrl. */
@Preview(name = "Sidebar, Finance open", widthDp = 248, heightDp = 800)
@Composable
private fun ShellSidebarAppPreview() {
    val nav = remember {
        ShellNavigation().apply {
            useSidebar(true)
            openApp(InnerApp.FINANCE, Offset.Zero, selectedTab)
        }
    }
    val sections = remember {
        AppSectionsHost().apply { publish(FinanceTab.entries.map { AppSection(it.label, it.icon) }, selected = FinanceTab.MARKETS.ordinal) {} }
    }
    FrigatePreview {
        CompositionLocalProvider(LocalShortcutKey provides ShortcutKey.Control, LocalAssistant provides remember { AssistantHost() }) {
            ShellSidebar(nav = nav, sections = sections, onHide = {})
        }
    }
}

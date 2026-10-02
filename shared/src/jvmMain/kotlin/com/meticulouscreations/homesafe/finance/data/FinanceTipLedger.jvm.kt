package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.PlatformContext
import java.util.prefs.Preferences

/** The counts in the desktop user's Java preferences, under the app's own node. */
private class PreferencesTipLedger : FinanceTipLedger {
    private val node = Preferences.userRoot().node("com/meticulouscreations/homesafe/finance_tips")

    override fun timesShown(tip: String): Int = node.getInt(tip, 0)

    override fun recordShown(tip: String) {
        node.putInt(tip, timesShown(tip) + 1)
    }
}

actual fun createFinanceTipLedger(platformContext: PlatformContext): FinanceTipLedger = PreferencesTipLedger()

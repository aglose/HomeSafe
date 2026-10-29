package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.DesktopStorage
import com.meticulouscreations.homesafe.PlatformContext

/** The counts in the app's preferences, one integer per tip. */
private object PreferencesTipLedger : FinanceTipLedger {
    private val preferences = DesktopStorage.preferences("finance_tips")

    override fun timesShown(tip: String): Int = preferences.getInt(tip, 0)

    override fun recordShown(tip: String) {
        preferences.putInt(tip, timesShown(tip) + 1)
        preferences.flush()
    }
}

actual fun createFinanceTipLedger(platformContext: PlatformContext): FinanceTipLedger = PreferencesTipLedger

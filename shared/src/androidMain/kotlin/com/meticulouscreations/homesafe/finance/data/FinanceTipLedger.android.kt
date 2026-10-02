package com.meticulouscreations.homesafe.finance.data

import android.content.Context
import com.meticulouscreations.homesafe.PlatformContext

/** The counts in a small preferences file of their own. */
private class SharedPreferencesTipLedger(context: Context) : FinanceTipLedger {
    private val preferences = context.getSharedPreferences("finance_tips", Context.MODE_PRIVATE)

    override fun timesShown(tip: String): Int = preferences.getInt(tip, 0)

    override fun recordShown(tip: String) {
        preferences.edit().putInt(tip, timesShown(tip) + 1).apply()
    }
}

actual fun createFinanceTipLedger(platformContext: PlatformContext): FinanceTipLedger =
    SharedPreferencesTipLedger(platformContext.context.applicationContext)

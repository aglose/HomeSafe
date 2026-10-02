package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.PlatformContext
import platform.Foundation.NSUserDefaults

/** The counts in the standard user defaults, one integer per tip. */
private object UserDefaultsTipLedger : FinanceTipLedger {
    private fun key(tip: String) = "finance_tip_$tip"

    override fun timesShown(tip: String): Int = NSUserDefaults.standardUserDefaults.integerForKey(key(tip)).toInt()

    override fun recordShown(tip: String) {
        NSUserDefaults.standardUserDefaults.setInteger((timesShown(tip) + 1).toLong(), key(tip))
    }
}

actual fun createFinanceTipLedger(platformContext: PlatformContext): FinanceTipLedger = UserDefaultsTipLedger

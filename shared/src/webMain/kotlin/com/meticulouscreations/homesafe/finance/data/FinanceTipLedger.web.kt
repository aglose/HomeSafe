package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.PlatformContext

/** The browser build keeps no local state between loads (see the in-memory connection history), so neither do the tips. */
actual fun createFinanceTipLedger(platformContext: PlatformContext): FinanceTipLedger = InMemoryFinanceTipLedger()

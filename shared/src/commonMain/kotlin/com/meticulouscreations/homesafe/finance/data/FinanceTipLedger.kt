package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.PlatformContext

/**
 * How many times each of the finance app's nudges has been put on screen on this install, so a
 * tip can come up a set number of times and then stay away. Kept outside the database: it's a
 * couple of counters, and losing them (a cleared app) only means seeing a tip again.
 */
interface FinanceTipLedger {
    fun timesShown(tip: String): Int

    fun recordShown(tip: String)
}

expect fun createFinanceTipLedger(platformContext: PlatformContext): FinanceTipLedger

/** Counts for as long as the process lives: for tests, and platforms with nowhere lasting to keep them. */
class InMemoryFinanceTipLedger : FinanceTipLedger {
    private val counts = HashMap<String, Int>()

    override fun timesShown(tip: String): Int = counts[tip] ?: 0

    override fun recordShown(tip: String) {
        counts[tip] = timesShown(tip) + 1
    }
}

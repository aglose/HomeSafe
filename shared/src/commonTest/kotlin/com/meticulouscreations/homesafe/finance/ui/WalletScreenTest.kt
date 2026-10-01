package com.meticulouscreations.homesafe.finance.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The interactive mortgage planner's amortization formula, isolated from its sliders and Compose. */
class WalletScreenTest {

    @Test
    fun monthlyPaymentMatchesTheStandardAmortizationFormula() {
        // $1,200,000 at 6% for 30 years.
        assertEquals(7194.61, monthlyPayment(1_200_000.0, 0.06, 30), 0.01)
    }

    @Test
    fun monthlyPaymentAtZeroRateIsJustTheLoanSplitEvenly() {
        assertEquals(1000.0, monthlyPayment(120_000.0, 0.0, 10))
    }

    @Test
    fun monthlyPaymentOverZeroYearsIsZero() {
        assertEquals(0.0, monthlyPayment(1_200_000.0, 0.06, 0))
    }
}

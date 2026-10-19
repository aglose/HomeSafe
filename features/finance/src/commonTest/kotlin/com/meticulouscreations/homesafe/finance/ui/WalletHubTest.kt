package com.meticulouscreations.homesafe.finance.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which of the Wallet's "Your money" rows a sheet gets, and where the checkup's links land. */
class WalletHubTest {

    private val full = FinanceFixtures.finance

    @Test
    fun aSheetWithEverythingListsEveryTopicInOrder() {
        assertEquals(WalletTopic.entries, walletTopics(full))
    }

    @Test
    fun anEmptySheetListsNoTopics() {
        val empty = full.copy(
            expenses = emptyList(),
            history = emptyList(),
            accounts = emptyList(),
            debts = emptyList(),
            home = null,
            mortgagePlan = null,
            oldHouse = null,
            vesting = emptyList(),
            taxYears = emptyList(),
            charts = emptyList(),
        )
        assertEquals(emptyList(), walletTopics(empty))
    }

    @Test
    fun theHomePageStaysForThePlannerAloneAndVestingForTaxesAlone() {
        val noHouse = full.copy(home = null, oldHouse = null, vesting = emptyList())
        val topics = walletTopics(noHouse)
        assertEquals(true, WalletTopic.HOME in topics)
        assertEquals(true, WalletTopic.VESTING_TAXES in topics)
        assertEquals(false, WalletTopic.HOME in walletTopics(noHouse.copy(mortgagePlan = null)))
    }

    @Test
    fun cashFlowNeedsExpensesOrMoreThanTwoCheckIns() {
        val history = full.history
        assertEquals(false, WalletTopic.CASH_FLOW in walletTopics(full.copy(expenses = emptyList(), history = history.take(2))))
        assertEquals(true, WalletTopic.CASH_FLOW in walletTopics(full.copy(expenses = emptyList(), history = history.take(3))))
    }

    @Test
    fun everyCheckupSectionOpensThePageThatShowsIt() {
        assertEquals(WalletTopic.ACCOUNTS, WalletSection.ACCOUNTS.topic)
        assertEquals(WalletTopic.ACCOUNTS, WalletSection.ALLOCATION.topic)
        assertEquals(WalletTopic.CASH_FLOW, WalletSection.CASH_FLOW.topic)
        assertEquals(WalletTopic.DEBT, WalletSection.DEBT.topic)
    }

    @Test
    fun theHomeIsOwnedInProportionToWhatIsPaidFor() {
        // $855,000 home with $642,300 still owed.
        assertEquals(0.2488, homeOwnedFraction(full)!!, 0.0001)
        assertNull(homeOwnedFraction(full.copy(home = null)))
    }
}

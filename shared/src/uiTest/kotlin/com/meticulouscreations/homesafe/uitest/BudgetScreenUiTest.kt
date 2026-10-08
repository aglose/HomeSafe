package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.BudgetUiState
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.ExpenseLine
import com.meticulouscreations.homesafe.finance.ui.BudgetFixtures
import com.meticulouscreations.homesafe.finance.ui.BudgetScreen
import com.meticulouscreations.homesafe.finance.ui.BudgetSettingsScreen
import com.meticulouscreations.homesafe.finance.ui.FinanceFixtures
import com.meticulouscreations.homesafe.finance.ui.FinanceStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * The Budget page's taps: a purchase nobody was put to goes to a person in one, the sheet behind
 * a purchase remembers its shop when asked to, a card is told what it is, and a month with no
 * cards sends you to link some.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetScreenUiTest {

    private class Calls {
        val tagged = mutableListOf<Triple<String, BucketId, Boolean>>()
        val roles = mutableListOf<Pair<String, CardRole>>()
        var linkedAccounts = 0
    }

    private fun onBudget(state: BudgetUiState, height: Dp = 1_400.dp, block: ComposeUiTest.(Calls) -> Unit) {
        val calls = Calls()
        // The tank and the meters are shaders drawn in software here: seconds per simulated second.
        runComposeUiTest(testTimeout = 3.minutes) {
            mainClock.autoAdvance = false
            setContent {
                FinanceStage {
                    // A phone's width whatever the test window is, and tall enough that what is tapped is laid out.
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(412.dp, height)) {
                            BudgetScreen(
                                state = state,
                                listState = rememberLazyListState(),
                                contentPadding = PaddingValues(),
                                onTag = { id, bucket, remember -> calls.tagged += Triple(id, bucket, remember) },
                                onSetRole = { card, role -> calls.roles += card to role },
                                onShowMonth = {},
                                onSyncNow = {},
                                onRetry = {},
                                onOpenLinkedAccounts = { calls.linkedAccounts++ },
                                onOpenSettings = {},
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(400)
            block(calls)
        }
    }

    @Test
    fun aMonthIntoTheSavingsSaysSoOverItsNumber() = onBudget(BudgetFixtures.state(BudgetFixtures.dipping)) {
        onNodeWithTag("finance_budget_headline").assertTextEquals("Into savings")
    }

    @Test
    fun aNameUnderAPurchaseNobodyWasPutToTagsItWithoutRememberingTheShop() = onBudget(BudgetFixtures.state(BudgetFixtures.onPace)) { calls ->
        val purchase = BudgetFixtures.onPace.unassigned.first()
        // By its click action, not a touch: the row can sit past the test window's edge.
        onNodeWithTag("finance_budget_sort_${purchase.id}_person:Sam").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(Triple(purchase.id, BucketId.Person("Sam") as BucketId, false)), calls.tagged)
    }

    @Test
    fun thePurchasesSheetRemembersTheShopWhenAskedTo() = onBudget(BudgetFixtures.state(BudgetFixtures.onPace), height = 4_200.dp) { calls ->
        val purchase = BudgetFixtures.onPace.purchases.first()
        onNodeWithTag("finance_budget_purchase_${purchase.id}").performSemanticsAction(SemanticsActions.OnClick)
        // The sheet sliding up.
        mainClock.advanceTimeBy(600)
        onNodeWithTag("finance_budget_tag_remember").performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeByFrame()
        onNodeWithTag("finance_budget_tag_person:Alex").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(Triple(purchase.id, BucketId.Person("Alex") as BucketId, true)), calls.tagged)
    }

    @Test
    fun aCardNobodyHasSaidAnythingAboutIsToldWhatItIs() = onBudget(BudgetFixtures.state(BudgetFixtures.unsorted)) { calls ->
        // One row of choices a card, in the cards' order: the second card is the family's.
        onAllNodesWithText("The family's")[1].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(BudgetFixtures.FAMILY to CardRole.Family as CardRole), calls.roles)
    }

    @Test
    fun theSettingsListASheetWithTwoLinesOfTheSameName() {
        val finance = FinanceFixtures.finance.let { it.copy(expenses = it.expenses + ExpenseLine("Insurance", 120.0)) }
        val saved = mutableListOf<BudgetConfigPatch>()
        runComposeUiTest {
            setContent {
                FinanceStage {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(412.dp, 3_000.dp)) {
                            BudgetSettingsScreen(BudgetFixtures.state(BudgetFixtures.onPace), finance, PaddingValues(), onSave = { saved += it }, onForgetRule = {})
                        }
                    }
                }
            }
            // Both are there, and ticking one says the name is paid by card.
            onAllNodesWithText("Insurance").assertCountEquals(2)
            onAllNodesWithText("Insurance")[1].performSemanticsAction(SemanticsActions.OnClick)
        }
        assertEquals(listOf(BudgetFixtures.onPace.config.cardPaidLines + "Insurance"), saved.map { it.cardPaidLines })
    }

    @Test
    fun withNoCardLinkedThePageSendsYouToLinkOne() = onBudget(BudgetFixtures.state(BudgetFixtures.noCards)) { calls ->
        onNodeWithText("Linked accounts").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, calls.linkedAccounts)
    }
}

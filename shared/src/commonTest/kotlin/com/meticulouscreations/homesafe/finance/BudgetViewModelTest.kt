package com.meticulouscreations.homesafe.finance

import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetLimits
import com.meticulouscreations.homesafe.finance.domain.BudgetRepository
import com.meticulouscreations.homesafe.finance.domain.BudgetSheetFigures
import com.meticulouscreations.homesafe.finance.ui.BudgetFixtures
import com.meticulouscreations.homesafe.finance.ui.FinanceFixtures
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_budget_error_generic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val october = BudgetFixtures.onPace
    private val september = october.copy(month = "2026-09", isCurrentMonth = false, day = 30, daysInMonth = 30)

    /** Answers a month from [months] (the last one repeating), and each change with [changed]; keeps what it was asked. */
    private inner class FakeRepository : BudgetRepository {
        val months = ArrayDeque<Result<Budget>>()
        var changed: Result<Budget> = Result.success(october)
        val asked = mutableListOf<String?>()
        val saved = mutableListOf<BudgetConfigPatch>()
        val tagged = mutableListOf<Triple<String, BucketId, Boolean>>()
        val forgotten = mutableListOf<String>()
        var syncs = 0

        override suspend fun budget(month: String?): Result<Budget> {
            asked += month
            return if (months.size > 1) months.removeFirst() else months.first()
        }

        override suspend fun save(patch: BudgetConfigPatch): Result<Budget> {
            saved += patch
            return changed
        }

        override suspend fun tag(purchaseId: String, bucket: BucketId, remember: Boolean): Result<Budget> {
            tagged += Triple(purchaseId, bucket, remember)
            return changed
        }

        override suspend fun forgetRule(merchantKey: String): Result<Budget> {
            forgotten += merchantKey
            return changed
        }

        override suspend fun syncNow(): Result<Budget> {
            syncs++
            return changed
        }
    }

    private fun TestScope.viewModel(repo: FakeRepository) = BudgetViewModel(
        repo,
        object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(START + testScheduler.currentTime / 1000)
        },
    )

    private fun TestScope.shown(repo: FakeRepository, budget: Budget = october): BudgetViewModel {
        repo.months += Result.success(budget)
        return viewModel(repo).also {
            it.setActive(true)
            runCurrent()
        }
    }

    @Test
    fun whileThePageIsUpTheMonthIsReadEveryMinute() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = shown(repo)
        assertFalse(vm.uiState.value.loading)
        assertEquals("2026-10", vm.uiState.value.budget?.month)
        assertEquals(START, vm.uiState.value.readAtEpochSeconds)
        advanceTimeBy(BudgetViewModel.POLL_MS + 1)
        assertEquals(2, repo.asked.size)
        vm.setActive(false)
        advanceTimeBy(BudgetViewModel.POLL_MS * 3)
        assertEquals(2, repo.asked.size)
    }

    @Test
    fun aRelayThatCannotBeReachedSaysSoAndKeepsTheMonthItHad() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = shown(repo)
        repo.months.clear()
        repo.months += Result.failure(IllegalStateException())
        vm.refresh()
        runCurrent()
        assertEquals(BankProblem.OTHER, vm.uiState.value.problem)
        assertEquals(UiText.of(Res.string.fin_budget_error_generic), vm.uiState.value.problemText)
        assertNotNull(vm.uiState.value.budget)
        vm.setActive(false)
    }

    @Test
    fun someoneTheRelayNoLongerShowsTheMoneyToSeesNoneOfIt() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = shown(repo)
        repo.months.clear()
        repo.months += Result.failure(BankSyncException(BankProblem.NOT_ALLOWED, "admins only".asUiText()))
        vm.refresh()
        runCurrent()
        assertNull(vm.uiState.value.budget)
        assertEquals(BankProblem.NOT_ALLOWED, vm.uiState.value.problem)
        assertEquals("admins only".asUiText(), vm.uiState.value.problemText)
        vm.setActive(false)
    }

    @Test
    fun taggingAPurchaseShowsTheMonthAsItThenStands() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = shown(repo)
        val sorted = october.copy(spent = 1.0)
        repo.changed = Result.success(sorted)
        vm.tag("p-1-0", BucketId.Person("Sam"), remember = true)
        assertEquals(setOf("p-1-0"), vm.uiState.value.tagging)
        runCurrent()
        assertEquals(listOf(Triple("p-1-0", BucketId.Person("Sam") as BucketId, true)), repo.tagged)
        assertEquals(emptySet(), vm.uiState.value.tagging)
        assertEquals(sorted, vm.uiState.value.budget)
        vm.setActive(false)
    }

    @Test
    fun purchasesTaggedOneAfterAnotherAllGoToTheRelayInTheOrderTapped() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = shown(repo)
        vm.tag("p-1-0", BucketId.Person("Sam"), remember = false)
        vm.tag("p-2-0", BucketId.Person("Alex"), remember = false)
        // The same one tapped twice before it is answered is one tag.
        vm.tag("p-1-0", BucketId.Family, remember = false)
        assertEquals(setOf("p-1-0", "p-2-0"), vm.uiState.value.tagging)
        runCurrent()
        assertEquals(listOf("p-1-0" to BucketId.Person("Sam"), "p-2-0" to BucketId.Person("Alex")), repo.tagged.map { it.first to it.second })
        assertEquals(emptySet(), vm.uiState.value.tagging)
        vm.setActive(false)
    }

    @Test
    fun aTagTheRelayRefusesSaysWhyAndChangesNothing() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = shown(repo)
        repo.changed = Result.failure(BankSyncException(BankProblem.OTHER, "That purchase isn't there any more".asUiText()))
        vm.tag("gone", BucketId.Family, remember = false)
        runCurrent()
        assertEquals(emptySet(), vm.uiState.value.tagging)
        assertEquals(BankNotice("That purchase isn't there any more".asUiText(), isError = true), vm.uiState.value.notice)
        assertEquals(october, vm.uiState.value.budget)
        vm.dismissNotice()
        assertNull(vm.uiState.value.notice)
        vm.setActive(false)
    }

    @Test
    fun lookingBackAsksForThatMonthAndAChangeMadeThereKeepsItOnScreen() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = shown(repo)
        repo.months.clear()
        repo.months += Result.success(september)
        vm.showMonth("2026-09")
        assertTrue(vm.uiState.value.loading)
        runCurrent()
        assertEquals(listOf(null, "2026-09"), repo.asked)
        assertEquals("2026-09", vm.uiState.value.budget?.month)

        // The relay answers a saved setting with the month under way, which isn't the one being looked at.
        vm.save(BudgetConfigPatch(limits = BudgetLimits(total = 4_000.0)))
        runCurrent()
        assertEquals("2026-09", vm.uiState.value.budget?.month)
        assertEquals(listOf(null, "2026-09", "2026-09"), repo.asked)
        assertFalse(vm.uiState.value.saving)

        // Asking for the month under way by name is the same as going back to it.
        repo.months.clear()
        repo.months += Result.success(october)
        vm.showMonth("2026-10")
        runCurrent()
        assertNull(vm.uiState.value.month)
        assertEquals("2026-10", vm.uiState.value.budget?.month)
        vm.setActive(false)
    }

    @Test
    fun askingForTheCardsToBeReadFollowsTheReadUntilItIsDone() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = shown(repo)
        repo.changed = Result.success(october.copy(syncing = true))
        repo.months.clear()
        repo.months += Result.success(october.copy(syncing = true))
        repo.months += Result.success(october.copy(syncing = false, spent = 2.0))
        vm.syncNow()
        assertTrue(vm.uiState.value.syncing)
        vm.syncNow()
        runCurrent()
        assertEquals(1, repo.syncs)
        assertTrue(vm.uiState.value.syncing)
        advanceTimeBy(BudgetViewModel.SYNC_POLL_MS * 2 + 1)
        assertFalse(vm.uiState.value.syncing)
        assertEquals(2.0, vm.uiState.value.budget?.spent)
        vm.setActive(false)
    }

    @Test
    fun theSheetsTakeHomeAndBillsAreReportedOnceWhenTheRelaysDiffer() = runTest(dispatcher) {
        val repo = FakeRepository()
        val behind = october.copy(config = october.config.copy(sheet = BudgetSheetFigures(takeHome = 1.0, billsOffCard = 1.0)))
        repo.changed = Result.success(behind)
        val vm = shown(repo, behind)
        val finance = FinanceFixtures.finance
        val figures = BudgetSheetFigures.of(finance, october.config.cardPaidLines)
        vm.onSheet(finance)
        runCurrent()
        assertEquals(listOf(BudgetConfigPatch(sheet = figures)), repo.saved)
        // The relay's answer still has the old figures (it refused, say): it isn't told again and again.
        vm.onSheet(finance)
        runCurrent()
        assertEquals(1, repo.saved.size)
        vm.setActive(false)
    }

    @Test
    fun aReportOfTheSheetThatNeverArrivedIsSentAgain() = runTest(dispatcher) {
        val repo = FakeRepository()
        val behind = october.copy(config = october.config.copy(sheet = BudgetSheetFigures(takeHome = 1.0, billsOffCard = 1.0)))
        val vm = shown(repo, behind)
        repo.changed = Result.failure(IllegalStateException("connection reset"))
        vm.onSheet(FinanceFixtures.finance)
        runCurrent()
        repo.changed = Result.success(behind)
        vm.onSheet(FinanceFixtures.finance)
        runCurrent()
        assertEquals(2, repo.saved.size)
        vm.setActive(false)
    }

    @Test
    fun aRelayThatAlreadyHasTheSheetsFiguresIsToldNothing() = runTest(dispatcher) {
        val repo = FakeRepository()
        val finance = FinanceFixtures.finance
        val figures = BudgetSheetFigures.of(finance, october.config.cardPaidLines)
        val vm = shown(repo, october.copy(config = october.config.copy(sheet = figures)))
        vm.onSheet(finance)
        vm.onSheet(null)
        runCurrent()
        assertEquals(emptyList(), repo.saved)
        vm.setActive(false)
    }

    @Test
    fun aBudgetWithNoPeopleYetIsGivenTheSheets() = runTest(dispatcher) {
        val repo = FakeRepository()
        val finance = FinanceFixtures.finance
        val figures = BudgetSheetFigures.of(finance, emptyList())
        val vm = shown(repo, october.copy(config = october.config.copy(people = emptyList(), cardPaidLines = emptyList(), sheet = figures)))
        vm.onSheet(finance)
        runCurrent()
        assertEquals(listOf(BudgetConfigPatch(people = finance.people)), repo.saved)
        vm.setActive(false)
    }

    @Test
    fun someoneAddedToTheSheetLaterIsToldToTheRelayAndASheetNamingNobodyChangesNothing() = runTest(dispatcher) {
        val repo = FakeRepository()
        val finance = FinanceFixtures.finance
        val figures = BudgetSheetFigures.of(finance, october.config.cardPaidLines)
        val vm = shown(repo, october.copy(config = october.config.copy(sheet = figures)))
        vm.onSheet(finance.copy(people = emptyList()))
        runCurrent()
        assertEquals(emptyList(), repo.saved)
        vm.onSheet(finance.copy(people = finance.people + " Robin "))
        runCurrent()
        assertEquals(listOf(BudgetConfigPatch(people = finance.people + "Robin")), repo.saved)
        vm.setActive(false)
    }

    @Test
    fun forgettingAShopAsksTheRelayTo() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = shown(repo)
        vm.forgetRule("corner coffee")
        runCurrent()
        assertEquals(listOf("corner coffee"), repo.forgotten)
        vm.setActive(false)
    }

    private companion object {
        const val START = 1_791_374_400L
    }
}

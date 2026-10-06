package com.meticulouscreations.homesafe.finance

import com.meticulouscreations.homesafe.finance.domain.BankFeed
import com.meticulouscreations.homesafe.finance.domain.BankInstitution
import com.meticulouscreations.homesafe.finance.domain.BankLinkKind
import com.meticulouscreations.homesafe.finance.domain.BankLinkProgress
import com.meticulouscreations.homesafe.finance.domain.BankLinkStart
import com.meticulouscreations.homesafe.finance.domain.BankLinkStatus
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSync
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BankSyncRepository
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_bank_error_generic
import homesafe.shared.generated.resources.fin_bank_notice_exited
import homesafe.shared.generated.resources.fin_bank_notice_expired
import homesafe.shared.generated.resources.fin_bank_notice_linked_named
import homesafe.shared.generated.resources.fin_bank_notice_unlinked
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
class BankSyncViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun bank(vararg names: String, syncing: Boolean = false) = BankSync(
        configured = true,
        sandbox = false,
        institutions = names.map { BankInstitution(it.lowercase(), it, syncedAtEpochSeconds = null, error = null, errorMessage = null, needsRelink = false, accounts = emptyList()) },
        syncedAtEpochSeconds = null,
        syncing = syncing,
        nextSyncAtEpochSeconds = null,
        feed = BankFeed(configured = false, url = null, writtenAtEpochSeconds = null, error = null, message = null, serviceAccount = null, activationUrl = null),
    )

    /** Answers from queues, in the order asked; the last answer of a queue repeats. */
    private inner class FakeRepository : BankSyncRepository {
        val statuses = ArrayDeque<Result<BankSync>>()
        val progress = ArrayDeque<Result<BankLinkProgress>>()
        var link: Result<BankLinkStart> = Result.success(BankLinkStart("link-1", "https://secure.plaid.com/hl/abc", START + 1_800))
        var sync: Result<BankSync> = Result.success(bank("Chase", syncing = true))
        var unlinked: Result<BankSync> = Result.success(bank())
        var statusCalls = 0
        var progressCalls = 0
        val linked = mutableListOf<BankLinkKind>()
        val relinked = mutableListOf<String>()

        private fun <T> ArrayDeque<T>.next(): T = if (size > 1) removeFirst() else first()

        override suspend fun status(): Result<BankSync> {
            statusCalls++
            return statuses.next()
        }

        override suspend fun startLink(kind: BankLinkKind): Result<BankLinkStart> {
            linked += kind
            return link
        }

        override suspend fun startRelink(institutionId: String): Result<BankLinkStart> {
            relinked += institutionId
            return link
        }

        override suspend fun linkProgress(token: String): Result<BankLinkProgress> {
            progressCalls++
            return progress.next()
        }

        override suspend fun syncNow(): Result<BankSync> = sync

        override suspend fun unlink(institutionId: String): Result<BankSync> = unlinked
    }

    private fun TestScope.ticking() = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(START + testScheduler.currentTime / 1000)
    }

    private fun TestScope.viewModel(repo: FakeRepository) = BankSyncViewModel(repo, ticking())

    @Test
    fun openingThePageReadsTheStatus() = runTest(dispatcher) {
        val repo = FakeRepository().apply { statuses += Result.success(bank("Chase")) }
        val vm = viewModel(repo)
        assertTrue(vm.uiState.value.loading)
        vm.load()
        runCurrent()
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(listOf("Chase"), state.bank?.institutions?.map { it.name })
        assertNull(state.problem)
    }

    @Test
    fun aRelayThatRefusesSaysWhy() = runTest(dispatcher) {
        val repo = FakeRepository().apply { statuses += Result.failure(BankSyncException(BankProblem.NOT_ALLOWED, "admins only".asUiText())) }
        val vm = viewModel(repo)
        vm.load()
        runCurrent()
        assertEquals(BankProblem.NOT_ALLOWED, vm.uiState.value.problem)
        assertEquals("admins only".asUiText(), vm.uiState.value.problemText)
        assertNull(vm.uiState.value.bank)

        // Asked again once it's put right, the page shows.
        repo.statuses.clear()
        repo.statuses += Result.success(bank())
        vm.load()
        runCurrent()
        assertNull(vm.uiState.value.problem)
        assertNotNull(vm.uiState.value.bank)
    }

    @Test
    fun aFailureWithNoWordsOfItsOwnGetsThePagesOwn() = runTest(dispatcher) {
        val repo = FakeRepository().apply { statuses += Result.failure(IllegalStateException()) }
        val vm = viewModel(repo)
        vm.load()
        runCurrent()
        assertEquals(BankProblem.OTHER, vm.uiState.value.problem)
        assertEquals(UiText.of(Res.string.fin_bank_error_generic), vm.uiState.value.problemText)
    }

    @Test
    fun linkingHandsOverPlaidsPageOnceAndWaitsForItToBeFinished() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            statuses += Result.success(bank())
            statuses += Result.success(bank("Chase"))
            progress += Result.success(BankLinkProgress(BankLinkStatus.PENDING))
            progress += Result.success(BankLinkProgress(BankLinkStatus.PENDING))
            progress += Result.success(BankLinkProgress(BankLinkStatus.LINKED, listOf("Chase"), bank("Chase", syncing = true)))
        }
        val vm = viewModel(repo)
        vm.load()
        vm.link(BankLinkKind.INVESTMENTS)
        runCurrent()
        assertEquals(listOf(BankLinkKind.INVESTMENTS), repo.linked)
        val linking = assertNotNull(vm.uiState.value.linking)
        assertEquals("https://secure.plaid.com/hl/abc", linking.url)
        assertFalse(linking.opened)
        vm.onLinkOpened()
        assertTrue(vm.uiState.value.linking!!.opened)

        // Still on Plaid's page: asked every few seconds, nothing changes.
        advanceTimeBy(2 * BankSyncViewModel.LINK_POLL_MS + 1)
        assertEquals(2, repo.progressCalls)
        assertNotNull(vm.uiState.value.linking)

        advanceTimeBy(BankSyncViewModel.LINK_POLL_MS)
        val state = vm.uiState.value
        assertNull(state.linking)
        assertEquals(BankNotice(UiText.of(Res.string.fin_bank_notice_linked_named, "Chase".asUiText())), state.notice)
        assertTrue(state.syncing, "the relay reads the new institution straight away")

        // ...and the page follows that read until it lands.
        advanceTimeBy(BankSyncViewModel.SYNC_POLL_MS + 1)
        assertFalse(vm.uiState.value.syncing)
        assertEquals(3, repo.progressCalls, "a made link isn't asked about again")
    }

    @Test
    fun leavingPlaidsPageUnfinishedLinksNothing() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            statuses += Result.success(bank())
            progress += Result.success(BankLinkProgress(BankLinkStatus.EXITED))
        }
        val vm = viewModel(repo)
        vm.link(BankLinkKind.BANK)
        advanceTimeBy(BankSyncViewModel.LINK_POLL_MS + 1)
        assertNull(vm.uiState.value.linking)
        assertEquals(BankNotice(UiText.of(Res.string.fin_bank_notice_exited)), vm.uiState.value.notice)
    }

    @Test
    fun aLinkNeverFinishedLapsesWhenPlaidsDoes() = runTest(dispatcher) {
        val repo = FakeRepository().apply { progress += Result.success(BankLinkProgress(BankLinkStatus.PENDING)) }
        val vm = viewModel(repo)
        vm.link(BankLinkKind.BANK)
        advanceTimeBy(1_800_000L + BankSyncViewModel.LINK_POLL_MS)
        assertNull(vm.uiState.value.linking)
        assertEquals(BankNotice(UiText.of(Res.string.fin_bank_notice_expired), isError = true), vm.uiState.value.notice)
        val asked = repo.progressCalls
        advanceTimeBy(60_000)
        assertEquals(asked, repo.progressCalls, "and is no longer asked about")
    }

    @Test
    fun aFewDroppedAnswersWhileBehindTheBrowserAreNotTheLinkFailing() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            repeat(BankSyncViewModel.LINK_POLL_FAILURES - 1) { progress += Result.failure(IllegalStateException("timeout")) }
            progress += Result.success(BankLinkProgress(BankLinkStatus.PENDING))
        }
        val vm = viewModel(repo)
        vm.link(BankLinkKind.BANK)
        advanceTimeBy(BankSyncViewModel.LINK_POLL_FAILURES * BankSyncViewModel.LINK_POLL_MS + 1)
        assertNotNull(vm.uiState.value.linking)
        assertNull(vm.uiState.value.notice)
        vm.cancelLink()
    }

    @Test
    fun aRelayThatKeepsFailingEndsTheWait() = runTest(dispatcher) {
        val repo = FakeRepository().apply { progress += Result.failure(BankSyncException(BankProblem.PLAID, "Plaid said no".asUiText())) }
        val vm = viewModel(repo)
        vm.link(BankLinkKind.BANK)
        advanceTimeBy(BankSyncViewModel.LINK_POLL_FAILURES * BankSyncViewModel.LINK_POLL_MS + 1)
        assertNull(vm.uiState.value.linking)
        assertEquals(BankNotice("Plaid said no".asUiText(), isError = true), vm.uiState.value.notice)
    }

    @Test
    fun aLinkPlaidWontMakeIsSaidAndNothingIsOpened() = runTest(dispatcher) {
        val repo = FakeRepository().apply { link = Result.failure(BankSyncException(BankProblem.PLAID, "bad keys".asUiText())) }
        val vm = viewModel(repo)
        vm.link(BankLinkKind.BANK)
        runCurrent()
        assertNull(vm.uiState.value.linking)
        assertFalse(vm.uiState.value.startingLink)
        assertEquals(BankNotice("bad keys".asUiText(), isError = true), vm.uiState.value.notice)
    }

    @Test
    fun cancellingStopsAskingAboutTheLink() = runTest(dispatcher) {
        val repo = FakeRepository().apply { progress += Result.success(BankLinkProgress(BankLinkStatus.PENDING)) }
        val vm = viewModel(repo)
        vm.link(BankLinkKind.BANK)
        advanceTimeBy(BankSyncViewModel.LINK_POLL_MS + 1)
        vm.cancelLink()
        assertNull(vm.uiState.value.linking)
        advanceTimeBy(10 * BankSyncViewModel.LINK_POLL_MS)
        assertEquals(1, repo.progressCalls)
    }

    @Test
    fun signingInAgainNamesTheInstitution() = runTest(dispatcher) {
        val repo = FakeRepository().apply { progress += Result.success(BankLinkProgress(BankLinkStatus.PENDING)) }
        val vm = viewModel(repo)
        vm.relink("chase")
        runCurrent()
        assertEquals(listOf("chase"), repo.relinked)
        assertNotNull(vm.uiState.value.linking)
        vm.cancelLink()
    }

    @Test
    fun syncNowFollowsTheReadUntilItLands() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            statuses += Result.success(bank("Chase", syncing = true))
            statuses += Result.success(bank("Chase", syncing = true))
            statuses += Result.success(bank("Chase"))
        }
        val vm = viewModel(repo)
        vm.syncNow()
        assertTrue(vm.uiState.value.syncing, "from the tap, before the relay has answered")
        runCurrent()
        assertTrue(vm.uiState.value.syncing)
        advanceTimeBy(2 * BankSyncViewModel.SYNC_POLL_MS + 1)
        assertTrue(vm.uiState.value.syncing)
        advanceTimeBy(BankSyncViewModel.SYNC_POLL_MS)
        assertFalse(vm.uiState.value.syncing)
        val asked = repo.statusCalls
        advanceTimeBy(60_000)
        assertEquals(asked, repo.statusCalls, "nothing is polled once the read has landed")
    }

    @Test
    fun aSyncTheRelayDidNotStartIsNotFollowed() = runTest(dispatcher) {
        val repo = FakeRepository().apply { sync = Result.success(bank("Chase")) }
        val vm = viewModel(repo)
        vm.syncNow()
        runCurrent()
        assertFalse(vm.uiState.value.syncing)
        advanceTimeBy(60_000)
        assertEquals(0, repo.statusCalls)
    }

    @Test
    fun unlinkingShowsWhatIsLeftAndSaysWhoWent() = runTest(dispatcher) {
        val repo = FakeRepository().apply { statuses += Result.success(bank("Chase", "Fidelity")) }
        val vm = viewModel(repo)
        vm.load()
        runCurrent()
        repo.unlinked = Result.success(bank("Fidelity"))
        vm.unlink("chase")
        assertEquals("chase", vm.uiState.value.unlinking)
        runCurrent()
        val state = vm.uiState.value
        assertNull(state.unlinking)
        assertEquals(listOf("Fidelity"), state.bank?.institutions?.map { it.name })
        assertEquals(BankNotice(UiText.of(Res.string.fin_bank_notice_unlinked, "Chase".asUiText())), state.notice)
    }

    @Test
    fun anUnlinkThatFailsKeepsTheInstitution() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            statuses += Result.success(bank("Chase"))
            unlinked = Result.failure(BankSyncException(BankProblem.PLAID, "try later".asUiText()))
        }
        val vm = viewModel(repo)
        vm.load()
        runCurrent()
        vm.unlink("chase")
        runCurrent()
        assertEquals(listOf("Chase"), vm.uiState.value.bank?.institutions?.map { it.name })
        assertEquals(BankNotice("try later".asUiText(), isError = true), vm.uiState.value.notice)
    }

    private companion object {
        const val START = 1_790_000_000L
    }
}

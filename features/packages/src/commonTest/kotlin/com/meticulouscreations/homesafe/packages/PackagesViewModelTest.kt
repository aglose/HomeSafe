package com.meticulouscreations.homesafe.packages

import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.packages.domain.PackagesProblem
import com.meticulouscreations.homesafe.packages.domain.PackagesRepository
import com.meticulouscreations.homesafe.packages.domain.PackagesSnapshot
import com.meticulouscreations.homesafe.packages.domain.PackagesSync
import com.meticulouscreations.homesafe.packages.domain.PackagesUnavailableException
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ParcelChange
import com.meticulouscreations.homesafe.packages.domain.ParcelStage
import com.meticulouscreations.homesafe.packages.domain.PorchState
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_error_not_a_number
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
class PackagesViewModelTest {
    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FixedClock(var seconds: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(seconds)
    }

    private class FakeRepository : PackagesRepository {
        override val snapshot = MutableStateFlow<PackagesSnapshot?>(null)
        override val sync = MutableStateFlow(PackagesSync())

        override fun forgetIfSignedOut() = Unit
        val refreshes = ArrayList<Boolean>()
        val changes = ArrayList<Pair<String, ParcelChange>>()
        val forgotten = ArrayList<String>()
        val adds = ArrayList<String>()
        val notifies = ArrayList<Pair<String, Boolean>>()
        val hides = ArrayList<Pair<String, Boolean>>()
        val owners = ArrayList<String?>()
        var failNext: Throwable? = null

        private fun answer(): Result<Unit> = failNext?.let { Result.failure<Unit>(it).also { failNext = null } } ?: Result.success(Unit)

        override suspend fun refresh(readMail: Boolean) {
            refreshes += readMail
        }

        override suspend fun add(tracking: String, nickname: String?, owner: String?, surprise: Boolean): Result<Unit> {
            adds += tracking
            return answer()
        }

        override suspend fun change(parcelId: String, change: ParcelChange): Result<Unit> {
            changes += parcelId to change
            return answer()
        }

        override suspend fun forget(parcelId: String): Result<Unit> {
            forgotten += parcelId
            return answer()
        }

        override suspend fun setPhoneOwner(person: String?): Result<Unit> {
            owners += person
            return answer()
        }

        override suspend fun setNotify(kind: String, on: Boolean): Result<Unit> {
            notifies += kind to on
            return answer()
        }

        override suspend fun setHideNew(person: String, on: Boolean): Result<Unit> {
            hides += person to on
            return answer()
        }

        override suspend fun pictureUrl(parcelId: String?, name: String): String? = "http://box/$parcelId/$name"
    }

    private fun test(block: suspend TestScope.(PackagesViewModel, FakeRepository, FixedClock) -> Unit) = runTest(dispatcher) {
        val repository = FakeRepository()
        val clock = FixedClock(1_793_316_000)
        val viewModel = PackagesViewModel(repository, clock)
        block(viewModel, repository, clock)
        // The polling and the following would otherwise outlive the test.
        viewModel.setActive(false)
        viewModel.viewModelScope.cancel()
    }

    private val snapshot = PackagesSnapshot(parcels = listOf(Parcel(id = "a", stage = ParcelStage.OUT_FOR_DELIVERY)), today = 20_000, nowEpochSeconds = 1_793_316_000, viewer = "sam")

    @Test
    fun nothingIsAskedUntilSomethingIsOnScreen() = test { viewModel, repository, _ ->
        runCurrent()
        assertTrue(repository.refreshes.isEmpty())
        assertFalse(viewModel.uiState.value.loaded)
        viewModel.setActive(false)
        runCurrent()
        assertTrue(repository.refreshes.isEmpty())
    }

    @Test
    fun whatTheRelaySaysBecomesTheBoard() = test { viewModel, repository, _ ->
        viewModel.setActive(true, full = false)
        runCurrent()
        assertEquals(listOf(false), repository.refreshes)
        repository.snapshot.value = snapshot
        runCurrent()
        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertEquals(listOf("a"), state.board.today.map { it.id })
    }

    @Test
    fun signedOutEmptiesWhatWasShown() = test { viewModel, repository, _ ->
        viewModel.setActive(true, full = false)
        repository.snapshot.value = snapshot
        runCurrent()
        repository.snapshot.value = null
        runCurrent()
        assertFalse(viewModel.uiState.value.loaded)
        assertTrue(viewModel.uiState.value.board.isEmpty)
    }

    @Test
    fun theDrawersCardAsksRarelyAndTheAppAsksEveryMinute() = test { viewModel, repository, clock ->
        viewModel.setActive(true, full = false)
        runCurrent()
        assertEquals(1, repository.refreshes.size)
        clock.seconds += 120
        viewModel.setActive(true, full = false)
        runCurrent()
        assertEquals(1, repository.refreshes.size)
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(1, repository.refreshes.size)
        viewModel.setActive(true, full = true)
        runCurrent()
        assertEquals(2, repository.refreshes.size)
        advanceTimeBy(60_001L)
        runCurrent()
        assertEquals(3, repository.refreshes.size)
    }

    @Test
    fun leavingStopsThePolling() = test { viewModel, repository, _ ->
        viewModel.setActive(true, full = true)
        runCurrent()
        val asked = repository.refreshes.size
        viewModel.setActive(false)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(asked, repository.refreshes.size)
    }

    @Test
    fun aPullReadsTheMailToo() = test { viewModel, repository, _ ->
        viewModel.refresh(readMail = true)
        runCurrent()
        assertEquals(listOf(true), repository.refreshes)
    }

    @Test
    fun whatSomeoneSaysGoesToTheRelay() = test { viewModel, repository, _ ->
        viewModel.change("a", ParcelChange(porch = PorchState.INSIDE))
        viewModel.forget("b")
        viewModel.setNotify("porch", false)
        viewModel.setHideNew("sam", true)
        viewModel.setPhoneOwner("sam")
        runCurrent()
        assertEquals(listOf("a" to ParcelChange(porch = PorchState.INSIDE)), repository.changes)
        assertEquals(listOf("b"), repository.forgotten)
        assertEquals(listOf("porch" to false), repository.notifies)
        assertEquals(listOf("sam" to true), repository.hides)
        assertEquals(listOf<String?>("sam"), repository.owners)
    }

    @Test
    fun aRefusedNumberIsSaidAndItsPageStays() = test { viewModel, repository, _ ->
        val refusal = UiText.of(Res.string.packages_error_not_a_number)
        repository.failNext = PackagesUnavailableException(PackagesProblem.OTHER, refusal, technical = "HTTP 400")
        viewModel.add("nope", null, null, false)
        runCurrent()
        assertEquals(refusal, assertNotNull(viewModel.uiState.value.notice))
        assertFalse(viewModel.uiState.value.added)
        viewModel.noticeSeen()
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun anAcceptedNumberClosesItsPage() = test { viewModel, repository, _ ->
        viewModel.add("1Z999AA10123456784", null, "sam", true)
        runCurrent()
        assertEquals(listOf("1Z999AA10123456784"), repository.adds)
        assertTrue(viewModel.uiState.value.added)
        viewModel.addSeen()
        assertFalse(viewModel.uiState.value.added)
    }
}

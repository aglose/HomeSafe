package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.model.ServerUptime
import com.meticulouscreations.homesafe.domain.model.UptimeRange
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.UptimeRepository
import com.meticulouscreations.homesafe.domain.usecase.GetServerUptimeUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveActiveConnectionUseCase
import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.error_relay_answered
import homesafe.shared.generated.resources.uptime_load_failed
import homesafe.shared.generated.resources.uptime_not_kept_yet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The uptime screen's state: what it loads and when, and what a failure says. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerUptimeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val tailscale = ActiveConnection(serverUrl = "http://100.99.163.71:8971", localUrl = "http://192.168.68.65:8971", route = ConnectionRoute.TAILSCALE)
    private val atHome = tailscale.copy(route = ConnectionRoute.LOCAL_NETWORK)

    private fun record(hours: Int) = ServerUptime(
        sinceEpochSeconds = 1_791_147_600L - hours * 3_600L,
        untilEpochSeconds = 1_791_147_600L,
        bucketSeconds = hours * 3_600.0 / 96,
        recordingSinceEpochSeconds = null,
        checks = emptyList(),
        devices = emptyList(),
        outages = emptyList(),
    )

    private class FakeUptime(private val record: (Int) -> ServerUptime) : UptimeRepository {
        val asked = mutableListOf<UptimeRange>()
        var failure: Throwable? = null

        /** When set, the next read waits on it, so a test can look at the state mid-load. */
        var gate: CompletableDeferred<Unit>? = null

        /** Hands a cancelled read back as a failure, as a `runCatching` around the request would. */
        var swallowCancellation = false

        override suspend fun getUptime(range: UptimeRange): Result<ServerUptime> {
            asked += range
            try {
                gate?.await()
            } catch (e: CancellationException) {
                if (!swallowCancellation) throw e
                return Result.failure(e)
            }
            return failure?.let { Result.failure(it) } ?: Result.success(record(range.hours))
        }
    }

    private class FakeConnection(connection: ActiveConnection?) : ConnectionRepository {
        override val activeConnection = MutableStateFlow(connection)
        override val currentServerUrl = MutableStateFlow(connection?.activeUrl)
        override val mostRecentConnection = MutableStateFlow<ConnectionRecord?>(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = Res.string.biometric_name_generic
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    private fun viewModel(uptime: FakeUptime, connection: FakeConnection) =
        ServerUptimeViewModel(GetServerUptimeUseCase(uptime), ObserveActiveConnectionUseCase(connection))

    @Test
    fun itOpensOnTheLastDay() = runTest(dispatcher) {
        val uptime = FakeUptime(::record)
        val vm = viewModel(uptime, FakeConnection(tailscale))
        assertTrue(vm.uiState.value.isLoading)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(listOf(UptimeRange.Day), uptime.asked)
        assertEquals(record(24), state.uptime)
        assertEquals(ConnectionRoute.TAILSCALE, state.route)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun anotherRangeIsReadAndTheOldOneStaysUpMeanwhile() = runTest(dispatcher) {
        val uptime = FakeUptime(::record)
        val vm = viewModel(uptime, FakeConnection(tailscale))
        advanceUntilIdle()
        uptime.gate = CompletableDeferred()
        vm.selectRange(UptimeRange.Week)
        advanceUntilIdle()
        assertEquals(UptimeRange.Week, vm.uiState.value.range)
        assertTrue(vm.uiState.value.isLoading)
        assertEquals(record(24), vm.uiState.value.uptime, "still the day's, until the week's arrives")
        uptime.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(record(168), vm.uiState.value.uptime)
        assertEquals(listOf(UptimeRange.Day, UptimeRange.Week), uptime.asked)
    }

    @Test
    fun choosingTheRangeAlreadyShownReadsNothing() = runTest(dispatcher) {
        val uptime = FakeUptime(::record)
        val vm = viewModel(uptime, FakeConnection(tailscale))
        advanceUntilIdle()
        vm.selectRange(UptimeRange.Day)
        advanceUntilIdle()
        assertEquals(1, uptime.asked.size)
    }

    @Test
    fun aSlowReadForARangeNoLongerChosenIsDropped() = runTest(dispatcher) {
        val uptime = FakeUptime(::record)
        val vm = viewModel(uptime, FakeConnection(tailscale))
        advanceUntilIdle()
        val slow = CompletableDeferred<Unit>()
        uptime.gate = slow
        vm.selectRange(UptimeRange.Month)
        advanceUntilIdle()
        uptime.gate = null
        vm.selectRange(UptimeRange.Week)
        advanceUntilIdle()
        slow.complete(Unit)
        advanceUntilIdle()
        assertEquals(record(168), vm.uiState.value.uptime, "the month's answer came last and is not shown")
    }

    @Test
    fun aSupersededReadThatComesBackAsAFailureIsNotShownAsAnError() = runTest(dispatcher) {
        val uptime = FakeUptime(::record).apply { swallowCancellation = true }
        val vm = viewModel(uptime, FakeConnection(tailscale))
        advanceUntilIdle()
        uptime.gate = CompletableDeferred()
        vm.selectRange(UptimeRange.Month)
        advanceUntilIdle()
        uptime.gate = null
        vm.selectRange(UptimeRange.Week)
        advanceUntilIdle()
        assertEquals(record(168), vm.uiState.value.uptime)
        assertNull(vm.uiState.value.error, "the month's read was cancelled, which is not something to tell anyone")
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun comingHomeReadsItAgainOverTheHomeNetwork() = runTest(dispatcher) {
        val uptime = FakeUptime(::record)
        val connection = FakeConnection(tailscale)
        val vm = viewModel(uptime, connection)
        advanceUntilIdle()
        connection.activeConnection.value = atHome
        advanceUntilIdle()
        assertEquals(2, uptime.asked.size)
        assertEquals(ConnectionRoute.LOCAL_NETWORK, vm.uiState.value.route)
    }

    @Test
    fun refreshReadsTheSameRangeAgain() = runTest(dispatcher) {
        val uptime = FakeUptime(::record)
        val vm = viewModel(uptime, FakeConnection(atHome))
        advanceUntilIdle()
        vm.selectRange(UptimeRange.Week)
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(UptimeRange.Day, UptimeRange.Week, UptimeRange.Week), uptime.asked)
    }

    @Test
    fun aRelayFromBeforeTheRecordSaysSoPlainly() = runTest(dispatcher) {
        val uptime = FakeUptime(::record).apply {
            failure = FrigateResponseException(UiText.of(Res.string.error_relay_answered, "404 Not Found"), technical = "Relay answered 404 Not Found")
        }
        val vm = viewModel(uptime, FakeConnection(tailscale))
        advanceUntilIdle()
        assertEquals(UiText.of(Res.string.uptime_not_kept_yet), vm.uiState.value.error)
        assertNull(vm.uiState.value.uptime)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun anyOtherFailureSaysWhy() = runTest(dispatcher) {
        val refused = UiText.of(Res.string.error_relay_answered, "502 Bad Gateway")
        val uptime = FakeUptime(::record).apply { failure = FrigateResponseException(refused, technical = "Relay answered 502 Bad Gateway") }
        val vm = viewModel(uptime, FakeConnection(tailscale))
        advanceUntilIdle()
        assertEquals(UiText.of(Res.string.uptime_load_failed, refused), vm.uiState.value.error)
    }

    @Test
    fun aFailedRefreshKeepsTheRecordAndASuccessClearsTheError() = runTest(dispatcher) {
        val uptime = FakeUptime(::record)
        val vm = viewModel(uptime, FakeConnection(tailscale))
        advanceUntilIdle()
        uptime.failure = IllegalStateException("Connection refused")
        vm.refresh()
        advanceUntilIdle()
        assertEquals(record(24), vm.uiState.value.uptime)
        assertEquals(UiText.of(Res.string.uptime_load_failed, "Connection refused"), vm.uiState.value.error)
        uptime.failure = null
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
    }
}

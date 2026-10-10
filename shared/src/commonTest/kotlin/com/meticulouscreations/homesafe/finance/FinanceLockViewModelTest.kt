package com.meticulouscreations.homesafe.finance

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_lock_failed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** The lock as the finance app and the drawer's card see it. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceLockViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun onlineThereIsNoLockAndNothingToSayAboutBeingOffline() = runTest {
        val vm = FinanceLockViewModel(FakeFinanceAccess())
        assertFalse(vm.uiState.value.locked)
        assertFalse(vm.uiState.value.offline)
    }

    @Test
    fun offlineItIsLockedUntilTheFingerprintPassesAndStaysMarkedOffline() = runTest {
        val access = FakeFinanceAccess().apply { goOffline() }
        val vm = FinanceLockViewModel(access)
        assertTrue(vm.uiState.value.locked)
        assertTrue(vm.uiState.value.canUnlock)

        vm.unlock()
        assertFalse(vm.uiState.value.locked)
        assertTrue(vm.uiState.value.offline, "what's shown is what the device kept, and the app says so")
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun aRefusedFingerprintShowsWhatTheSystemSaid() = runTest {
        val access = FakeFinanceAccess().apply {
            goOffline()
            check = Result.failure(IllegalStateException("Too many attempts. Try again later."))
        }
        val vm = FinanceLockViewModel(access)
        vm.unlock()
        assertTrue(vm.uiState.value.locked)
        assertFalse(vm.uiState.value.checking)
        assertEquals("Too many attempts. Try again later.".asUiText(), vm.uiState.value.error)
    }

    @Test
    fun aFailureThatSaysNothingGetsTheAppsOwnWords() = runTest {
        val access = FakeFinanceAccess().apply {
            goOffline()
            check = Result.failure(IllegalStateException())
        }
        val vm = FinanceLockViewModel(access)
        vm.unlock()
        assertEquals(UiText.of(Res.string.finance_lock_failed), vm.uiState.value.error)
    }

    @Test
    fun closingTheAppLocksItAndClearsTheLastError() = runTest {
        val access = FakeFinanceAccess().apply { goOffline() }
        val vm = FinanceLockViewModel(access)
        vm.unlock()
        vm.lock()
        assertTrue(vm.uiState.value.locked)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun aDeviceWithNothingToCheckWithIsNeverPrompted() = runTest {
        val access = FakeFinanceAccess(canUnlock = false).apply { goOffline() }
        val vm = FinanceLockViewModel(access)
        vm.unlock()
        assertEquals(0, access.checks)
        assertTrue(vm.uiState.value.locked)
        assertFalse(vm.uiState.value.canUnlock)
    }

    @Test
    fun unlockedOnlineIsNotAskedForAFingerprint() = runTest {
        val access = FakeFinanceAccess()
        FinanceLockViewModel(access).unlock()
        assertEquals(0, access.checks)
    }
}

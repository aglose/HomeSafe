package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.AppVisibility
import com.meticulouscreations.homesafe.finance.FakeIdentityCheck
import com.meticulouscreations.homesafe.finance.FakeServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the finance app is locked: with no server to ask who is holding the phone, until a
 * fingerprint says, and again whenever that stops being fresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceAccessImplTest {

    @AfterTest fun backOnScreen() = AppVisibility.update(true)

    private class Subject(scope: TestScope, url: String?, unreachable: Boolean = false, canCheck: Boolean = true) {
        val connection = FakeServerConnection(url, unreachable)
        val reader = FakeIdentityCheck(canCheck)
        val testScheduler = scope.testScheduler

        // Unconfined: each change has reached `locked` by the time the line after it reads it.
        val appScope = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler))
        val access = FinanceAccessImpl(connection, reader, appScope)
    }

    private fun subject(url: String?, unreachable: Boolean = false, canCheck: Boolean = true, block: suspend Subject.() -> Unit) = runTest {
        val subject = Subject(this, url, unreachable, canCheck)
        try {
            subject.block()
        } finally {
            subject.appScope.cancel()
        }
    }

    @Test
    fun withTheServerAnsweringNothingIsLockedAndNobodyIsAsked() = subject("https://box.ts.net") {
        assertTrue(access.online.value)
        assertFalse(access.locked.value)
        assertTrue(access.unlock().isSuccess)
        assertEquals(0, reader.prompts, "the server's session is the answer; a fingerprint adds nothing")
    }

    @Test
    fun beforeAnySignInItIsLockedUntilTheFingerprintIsRead() = subject(url = null) {
        assertFalse(access.online.value)
        assertTrue(access.locked.value)

        assertTrue(access.unlock().isSuccess)
        assertEquals(1, reader.prompts)
        assertFalse(access.locked.value)
        assertFalse(access.online.value, "unlocked is not online: what shows is still what the device kept")
    }

    @Test
    fun signedInOnWhatTheDeviceKeptItIsLockedToo() = subject("https://box.ts.net", unreachable = true) {
        // The saved login opened the app with a fingerprint; this asks for another.
        assertTrue(access.locked.value)
        access.unlock()
        assertEquals(1, reader.prompts)
        assertFalse(access.locked.value)
    }

    @Test
    fun aFingerprintThatIsNotRecognisedLeavesItLocked() = subject(url = null) {
        reader.answer = Result.failure(IllegalStateException("Fingerprint operation cancelled by user."))
        assertTrue(access.unlock().isFailure)
        assertTrue(access.locked.value)
    }

    @Test
    fun closingTheAppLocksItAgain() = subject(url = null) {
        access.unlock()
        access.lock()
        assertTrue(access.locked.value)
        access.unlock()
        assertEquals(2, reader.prompts, "each opening without the server asks")
    }

    @Test
    fun leavingTheScreenLocksItAgain() = subject(url = null) {
        access.unlock()
        AppVisibility.update(false)
        AppVisibility.update(true)
        assertTrue(access.locked.value, "put down and picked up again, it asks again")
    }

    @Test
    fun theServerGoingAwayLocksItAndComingBackOpensIt() = subject("https://box.ts.net") {
        connection.serverUnreachable.value = true
        assertTrue(access.locked.value)

        connection.serverUnreachable.value = false
        assertFalse(access.locked.value)
        assertEquals(0, reader.prompts)
    }

    @Test
    fun anUnlockDoesNotOutliveTheServersReturn() = subject("https://box.ts.net", unreachable = true) {
        access.unlock()
        connection.serverUnreachable.value = false
        connection.serverUnreachable.value = true
        assertTrue(access.locked.value, "the fingerprint stood in for the server once; the next outage asks again")
    }

    @Test
    fun signingInOpensItWithoutAFingerprint() = subject(url = null) {
        connection.signIn("https://box.ts.net")
        assertTrue(access.online.value)
        assertFalse(access.locked.value)
    }

    @Test
    fun aDeviceThatCannotCheckSaysSo() = subject(url = null, canCheck = false) {
        assertFalse(access.canUnlock)
        assertTrue(access.locked.value)
    }

    @Test
    fun aFingerprintReadAfterTheAppWasClosedUnderThePromptUnlocksNothing() = subject(url = null) {
        reader.whilePrompting = { access.lock() }
        assertTrue(access.unlock().isFailure, "the answer is to a lock that has since been put back on")
        assertTrue(access.locked.value)

        reader.whilePrompting = {}
        assertTrue(access.unlock().isSuccess, "asked again, it unlocks as usual")
        assertFalse(access.locked.value)
    }

    @Test
    fun aFingerprintReadAfterTheAppLeftTheScreenUnlocksNothing() = subject(url = null) {
        reader.whilePrompting = { AppVisibility.update(false) }
        assertTrue(access.unlock().isFailure)
        AppVisibility.update(true)
        assertTrue(access.locked.value, "coming back, it is still locked")
    }

    @Test
    fun theServerComingBackUnderThePromptLeavesNoUnlockBehind() = subject("https://box.ts.net", unreachable = true) {
        reader.whilePrompting = { connection.serverUnreachable.value = false }
        assertTrue(access.unlock().isSuccess, "there is nothing left to unlock")
        assertFalse(access.locked.value)

        connection.serverUnreachable.value = true
        assertTrue(access.locked.value, "and the next outage asks, rather than finding the last answer waiting")
    }

    @Test
    fun aRequestNobodyAnsweredHasTheServerAskedAndLocksIfItIsGone() = subject("https://box.ts.net") {
        connection.answers = false
        access.serverUnanswered()
        assertEquals(1, connection.checks)
        assertFalse(access.online.value)
        assertTrue(access.locked.value, "by the time it returns: nothing kept is shown on the strength of a dead session")
    }

    @Test
    fun aRequestNobodyAnsweredToAServerThatIsThereAfterAllChangesNothing() = subject("https://box.ts.net") {
        access.serverUnanswered()
        assertEquals(1, connection.checks)
        assertTrue(access.online.value)
        assertFalse(access.locked.value)
    }

    @Test
    fun aServerFoundGoneIsAskedAgainUntilItAnswers() = subject("https://box.ts.net") {
        connection.answers = false
        access.serverUnanswered()
        testScheduler.advanceTimeBy(31_000)
        assertTrue(connection.checks >= 3, "nothing else is certain to look again: ${connection.checks}")
        assertTrue(access.locked.value)

        connection.answers = true
        testScheduler.advanceTimeBy(16_000)
        assertTrue(access.online.value)
        assertFalse(access.locked.value)
        val settled = connection.checks
        testScheduler.advanceTimeBy(60_000)
        assertEquals(settled, connection.checks, "and once it answers, it is left alone")
    }

    @Test
    fun withNoSignInThereIsNoServerToAsk() = subject(url = null) {
        access.serverUnanswered()
        assertEquals(0, connection.checks)
    }
}

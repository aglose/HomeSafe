package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionProblem
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.TailnetProbe
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** What the app says when it is signed in and nothing answers: Tailscale off on this device, or the server. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveConnectionProblemUseCaseTest {

    private val overTailscale = ActiveConnection("http://100.99.163.71:8971", "http://192.168.68.65:8971", ConnectionRoute.TAILSCALE)

    private class FakeConnection(connection: ActiveConnection?) : ConnectionRepository {
        override val activeConnection = MutableStateFlow(connection)
        override val serverUnreachable = MutableStateFlow(false)
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

    /** Collects what the use case says, in order, while [block] changes the world. */
    private fun problems(connection: FakeConnection, probe: TailnetProbe, block: suspend kotlinx.coroutines.test.TestScope.() -> Unit): List<ConnectionProblem?> {
        val seen = mutableListOf<ConnectionProblem?>()
        runTest {
            val job = backgroundScope.launch { ObserveConnectionProblemUseCase(connection, probe)().toList(seen) }
            runCurrent()
            block()
            runCurrent()
            job.cancel()
        }
        return seen
    }

    @Test
    fun aServerThatAnswersIsNoProblem() {
        assertEquals(listOf(null), problems(FakeConnection(overTailscale), { false }) {})
    }

    @Test
    fun nothingAnsweringWithNoTailnetAddressIsTailscaleBeingOff() {
        val connection = FakeConnection(overTailscale)
        val seen = problems(connection, { false }) { connection.serverUnreachable.value = true }
        assertEquals(listOf(null, ConnectionProblem.TailscaleOff), seen)
    }

    @Test
    fun nothingAnsweringWhileOnTheTailnetIsTheServer() {
        val connection = FakeConnection(overTailscale)
        val seen = problems(connection, { true }) { connection.serverUnreachable.value = true }
        assertEquals(listOf(null, ConnectionProblem.ServerUnreachable), seen)
    }

    @Test
    fun aDeviceThatCannotSayIsNotBlamed() {
        val connection = FakeConnection(overTailscale)
        val seen = problems(connection, { null }) { connection.serverUnreachable.value = true }
        assertEquals(listOf(null, ConnectionProblem.ServerUnreachable), seen)
    }

    @Test
    fun aServerNotReachedThroughTailscaleIsNeverCalledTailscaleBeingOff() {
        val connection = FakeConnection(overTailscale.copy(serverUrl = "https://frigate.example.com"))
        val seen = problems(connection, { false }) { connection.serverUnreachable.value = true }
        assertEquals(listOf(null, ConnectionProblem.ServerUnreachable), seen)
    }

    @Test
    fun turningTailscaleOnWhileTheServerIsStillAwayChangesWhatIsSaid() {
        val connection = FakeConnection(overTailscale)
        var onTailnet = false
        val seen = problems(connection, { onTailnet }) {
            connection.serverUnreachable.value = true
            runCurrent()
            onTailnet = true
            advanceTimeBy(5_001)
        }
        assertEquals(listOf(null, ConnectionProblem.TailscaleOff, ConnectionProblem.ServerUnreachable), seen)
    }

    @Test
    fun theProblemClearsTheMomentTheServerAnswers() {
        val connection = FakeConnection(overTailscale)
        val seen = problems(connection, { false }) {
            connection.serverUnreachable.value = true
            runCurrent()
            connection.serverUnreachable.value = false
        }
        assertEquals(listOf(null, ConnectionProblem.TailscaleOff, null), seen)
    }

    @Test
    fun signedOutThereIsNothingToSay() {
        val connection = FakeConnection(null)
        val seen = problems(connection, { false }) { connection.serverUnreachable.value = true }
        assertEquals(listOf(null), seen)
    }

    @Test
    fun aSignInThatNothingAnsweredIsExplainedOnlyByTailscaleBeingOff() {
        val off = ObserveConnectionProblemUseCase(FakeConnection(null)) { false }
        assertEquals(ConnectionProblem.TailscaleOff, off.explainUnanswered("http://100.99.163.71:8971"))
        assertNull(off.explainUnanswered("http://192.168.68.65:8971"), "not a tailnet address")
        assertNull(ObserveConnectionProblemUseCase(FakeConnection(null)) { true }.explainUnanswered("http://100.99.163.71:8971"), "on the tailnet: something else is wrong")
        assertNull(ObserveConnectionProblemUseCase(FakeConnection(null)) { null }.explainUnanswered("http://100.99.163.71:8971"), "can't tell")
    }
}

package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.BankLinkKind
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.network.TailnetProbe
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.fin_bank_error_generic
import homesafe.shared.generated.resources.fin_bank_error_tailscale_off
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * What [BankSyncRepositoryImpl] makes of a relay that nothing answers. On 2026-10-08 linking
 * Robinhood showed Android's own "Unable to resolve host …ts.net: No address associated with
 * hostname": another VPN was on, so Tailscale wasn't.
 */
class BankSyncRepositoryImplTest {

    private class FakeConnection(url: String?) : ConnectionRepository {
        override val activeConnection = MutableStateFlow<ActiveConnection?>(null)
        override val serverUnreachable = MutableStateFlow(false)
        override val currentServerUrl = MutableStateFlow(url)
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

    private fun repository(serverUrl: String, onTailnet: Boolean?, engine: MockEngine = MockEngine { throw IOException("Unable to resolve host") }): BankSyncRepositoryImpl {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout)
        }
        return BankSyncRepositoryImpl(BankSyncRelayApi(client), FakeConnection(serverUrl), TailnetProbe { onTailnet })
    }

    private fun Result<*>.problem(): BankSyncException = assertIs<BankSyncException>(exceptionOrNull())

    @Test
    fun aTailnetServerUnansweredWithTailscaleOffSaysSo() = runTest {
        val e = repository("https://box.tail1234.ts.net", onTailnet = false).startLink(BankLinkKind.INVESTMENTS).problem()
        assertEquals(BankProblem.TAILSCALE_OFF, e.problem)
        assertEquals(UiText.of(Res.string.fin_bank_error_tailscale_off), e.text)
        assertEquals("Unable to resolve host", e.message, "the platform's words stay for the logs")
    }

    @Test
    fun anUnansweredServerTailscaleDoesntExplainIsJustUnreachable() = runTest {
        for ((url, onTailnet) in listOf("https://box.tail1234.ts.net" to true, "https://box.tail1234.ts.net" to null, "http://192.168.68.65:8971" to false)) {
            val e = repository(url, onTailnet).status().problem()
            assertEquals(BankProblem.UNREACHABLE, e.problem, "$url, on the tailnet: $onTailnet")
            assertEquals(UiText.of(Res.string.fin_bank_error_generic), e.text)
        }
    }

    @Test
    fun aServerThatAnsweredKeepsItsOwnWords() = runTest {
        val engine = MockEngine { respond("""{"detail":{"error":"not_configured"}}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json")) }
        // Even with the probe wrong about Tailscale: something answered, so it wasn't off.
        assertEquals(BankProblem.NOT_CONFIGURED, repository("https://box.tail1234.ts.net", onTailnet = false, engine).status().problem().problem)
    }
}

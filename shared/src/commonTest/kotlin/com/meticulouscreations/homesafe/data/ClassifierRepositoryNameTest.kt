package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.FrigateClassifierApi
import com.meticulouscreations.homesafe.network.PushRelayApi
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A person naming a car goes through the relay, which keeps the name as a person's: since the
 * retrain of 2026-09-27 the classifier scores some of its own guesses 1.0, the score a tag carries.
 */
class ClassifierRepositoryNameTest {

    private class FakeConnection(url: String?) : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow(url)
        override val activeConnection: StateFlow<ActiveConnection?> = MutableStateFlow(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = Res.string.biometric_name_generic
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    /** Every request as "port path body"; the relay answers [relayStatus], Frigate 200. */
    private fun repository(relayStatus: HttpStatusCode = HttpStatusCode.OK): Pair<ClassifierRepositoryImpl, MutableList<String>> {
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += "${req.url.port} ${req.url.encodedPath} ${(req.body as? TextContent)?.text.orEmpty()}"
            val status = if (req.url.port == PushRelayApi.RELAY_PORT) relayStatus else HttpStatusCode.OK
            respond("""{"success":true,"message":"ok"}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val repository = ClassifierRepositoryImpl(FrigateClassifierApi(client), PushRelayApi(client), FakeConnection("http://frigate:8971"))
        return repository to seen
    }

    @Test
    fun aTagGoesThroughTheRelayAtAPersonsScore() = runTest {
        val (repository, seen) = repository()
        assertTrue(repository.nameTrackedObject("1790526225.375382-4cj3er", "andrews_tesla").isSuccess)
        val request = seen.single()
        assertTrue(request.startsWith("8787 /events/1790526225.375382-4cj3er/sub_label "), request)
        assertTrue(""""subLabel":"andrews_tesla"""" in request && """"subLabelScore":1.0""" in request, request)
    }

    @Test
    fun whenTheRelayDoesntTakeItTheTagGoesStraightToFrigate() = runTest {
        val (repository, seen) = repository(relayStatus = HttpStatusCode.NotFound)
        assertTrue(repository.nameTrackedObject("e1", "andrews_tesla").isSuccess)
        assertEquals(listOf("8787", "8971"), seen.map { it.substringBefore(' ') })
        assertTrue(seen[1].startsWith("8971 /api/events/e1/sub_label "), seen[1])
    }

    @Test
    fun takingTheNameAwaySendsAnEmptyNameAndNoScore() = runTest {
        val (repository, seen) = repository()
        assertTrue(repository.nameTrackedObject("e1", null).isSuccess)
        assertTrue(""""subLabel":""""" in seen.single() && """"subLabelScore":1.0""" !in seen.single(), seen.single())
    }
}

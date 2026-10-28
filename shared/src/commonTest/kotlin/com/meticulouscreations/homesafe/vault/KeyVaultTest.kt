package com.meticulouscreations.homesafe.vault

import com.meticulouscreations.homesafe.data.InMemoryApiKeyStore
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [KeyVault] against a relay that keeps its vault the way relay.py does: two logins sharing the
 * household's keys, a new password, and the assistant's old key moved off the phone. The relay
 * here keeps every blob it is sent, so the test can check it never holds a key in the clear.
 */
class KeyVaultTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relay = FakeVaultRelay()

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun theFirstLoginStartsTheHouseholdAndTheSecondIsLetIn() = runTest {
        val andrew = vault("andrew")
        andrew.signIn("andrew-password")
        andrew.vault.awaitOpen()
        andrew.vault.save(HouseholdKeys.GOOGLE_MAPS, GOOGLE_KEY).getOrThrow()

        val sarah = vault("sarah")
        sarah.signIn("sarah-password")
        assertEquals(VaultState.Waiting, sarah.vault.await { it is VaultState.Waiting }, "nobody has let her in yet")
        assertFalse(relay.blobs().any { GOOGLE_KEY in it }, "the relay never sees a key")

        // Andrew opens the app again: his phone grants her the household key.
        andrew.vault.refresh()
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (!relay.granted("sarah")) delay(20) } }
        sarah.vault.refresh()
        assertEquals(GOOGLE_KEY, sarah.vault.awaitOpen().secrets[HouseholdKeys.GOOGLE_MAPS])
    }

    @Test
    fun aNewPasswordOnTheOnlyLoginStartsAgainWithoutTheOldKeys() = runTest {
        val andrew = vault("andrew")
        andrew.signIn("old")
        andrew.vault.awaitOpen()
        andrew.vault.save(HouseholdKeys.ANTHROPIC, "sk-ant").getOrThrow()

        val again = vault("andrew")
        again.signIn("new")
        assertEquals(emptyMap(), again.vault.awaitOpen().secrets, "sealed under a household key nobody can open any more")
    }

    @Test
    fun theAssistantsOldKeyMovesIntoTheVaultAndOffThePhone() = runTest {
        val phone = InMemoryApiKeyStore(key = "sk-on-the-phone")
        val andrew = vault("andrew", phone)
        andrew.signIn("pw")
        assertEquals("sk-on-the-phone", andrew.vault.awaitOpen().secrets[HouseholdKeys.ANTHROPIC])
        assertNull(phone.apiKey())
    }

    @Test
    fun aSecondPhoneSigningInAtOnceUsesTheFirstsKeyPairInsteadOfReplacingIt() = runTest {
        val first = vault("andrew")
        first.signIn("pw")
        first.vault.awaitOpen()
        first.vault.save(HouseholdKeys.GOOGLE_MAPS, GOOGLE_KEY).getOrThrow()

        // The second phone's first look predates the first phone's key pair.
        relay.staleOnce = "andrew"
        val second = vault("andrew")
        second.signIn("pw")
        assertEquals(GOOGLE_KEY, second.vault.awaitOpen().secrets[HouseholdKeys.GOOGLE_MAPS], "it looked again, and the household's keys survived")
    }

    @Test
    fun aVaultOpenedOnOneServerIsNeverUsedForAnother() = runTest {
        val andrew = vault("andrew")
        andrew.signIn("pw")
        andrew.vault.awaitOpen()

        andrew.connection.currentServerUrl.value = "https://$OLD_RELAY_HOST"
        andrew.vault.await { it is VaultState.Unsupported }
        assertTrue(andrew.vault.save(HouseholdKeys.GOOGLE_MAPS, GOOGLE_KEY).isFailure, "not sent to the household on the other server")
        assertFalse(relay.hasSecret(HouseholdKeys.GOOGLE_MAPS))
    }

    @Test
    fun withNoLoginNothingIsAskedOrSaved() = runTest {
        val andrew = vault("andrew")
        assertIs<VaultState.SignedOut>(andrew.vault.state.value)
        assertTrue(andrew.vault.save(HouseholdKeys.GOOGLE_MAPS, GOOGLE_KEY).isFailure)
        assertEquals(0, relay.calls)
    }

    private class Phone(val vault: KeyVault, private val login: SignedInLogin, private val user: String, val connection: FakeConnection) {
        fun signIn(password: String) = login.signedIn(user, password)
    }

    private fun vault(user: String, assistantStore: InMemoryApiKeyStore = InMemoryApiKeyStore()): Phone {
        val engine = MockEngine { request ->
            val (status, text) = if (request.url.host == OLD_RELAY_HOST) {
                HttpStatusCode.NotFound to ""
            } else {
                relay.answer(user, request.method, request.url.encodedPath, request.body.toByteArray().decodeToString())
            }
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            defaultRequest { header("X-Test-User", user) }
        }
        val login = SignedInLogin()
        val connection = FakeConnection("https://frigate.test")
        val vault = KeyVault(login, connection, PushRelayApi(client), assistantStore, scope).apply { iterations = 1_000 }
        return Phone(vault, login, user, connection)
    }

    private suspend fun KeyVault.await(predicate: (VaultState) -> Boolean): VaultState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { state.first(predicate) } }

    private suspend fun KeyVault.awaitOpen(): VaultState.Open = await { it is VaultState.Open } as VaultState.Open

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

    /** relay.py's vault, in memory: the same rules about who may grant, start a household and write secrets. */
    private class FakeVaultRelay {
        private class Member(val public: String, val private: String, var grant: JsonObject? = null, var grantFor: String? = null, var grantHousehold: String? = null)

        private val members = linkedMapOf<String, Member>()
        private var household: String? = null
        private val secrets = linkedMapOf<String, String>()
        var calls = 0
            private set

        /** The next `GET /vault` for this user answers as if they had no key pair yet: what a second phone saw just before the first set one. */
        var staleOnce: String? = null

        suspend fun granted(user: String): Boolean = lock.withLock { members[user]?.let(::isGranted) == true }

        suspend fun hasSecret(name: String): Boolean = lock.withLock { name in secrets }

        fun blobs(): List<String> = members.values.flatMap { listOfNotNull(it.public, it.private, it.grant?.toString()) } + secrets.values

        private fun isGranted(member: Member) = member.grant != null && member.grantHousehold == household && member.grantFor == member.public

        private val lock = Mutex()

        suspend fun answer(user: String, method: HttpMethod, path: String, body: String): Pair<HttpStatusCode, String> = lock.withLock {
            calls++
            val json = body.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
            fun field(name: String) = json!![name]!!.jsonPrimitive.content
            val refused = when {
                method == HttpMethod.Get && path == "/vault" -> {
                    if (staleOnce == user) {
                        staleOnce = null
                        return@withLock HttpStatusCode.OK to view(user, hideMe = true).toString()
                    }
                    null
                }

                method == HttpMethod.Put && path == "/vault/me" -> {
                    val replaces = json!!["replaces"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                    if (members[user]?.public != replaces) {
                        HttpStatusCode.Conflict
                    } else {
                        members[user] = Member(field("public"), field("private"))
                        null
                    }
                }

                method == HttpMethod.Put && path.startsWith("/vault/grants/") -> {
                    val target = members[path.substringAfterLast('/')]
                    when {
                        field("household") != household || members[user]?.let(::isGranted) != true -> HttpStatusCode.Conflict

                        target == null -> HttpStatusCode.NotFound

                        target.public != field("recipient") -> HttpStatusCode.Conflict

                        else -> {
                            target.grant = buildJsonObject {
                                put("ephemeral", field("ephemeral"))
                                put("box", field("box"))
                            }
                            target.grantFor = target.public
                            target.grantHousehold = household
                            null
                        }
                    }
                }

                method == HttpMethod.Post && path == "/vault/household" -> {
                    val me = members[user]
                    if (me == null || members.values.any(::isGranted) || me.public != field("recipient")) {
                        HttpStatusCode.Conflict
                    } else {
                        household = field("household")
                        secrets.clear()
                        me.grant = buildJsonObject {
                            put("ephemeral", field("ephemeral"))
                            put("box", field("box"))
                        }
                        me.grantFor = me.public
                        me.grantHousehold = household
                        null
                    }
                }

                method == HttpMethod.Put && path == "/vault/secrets" -> {
                    if (field("household") != household || members[user]?.let(::isGranted) != true) {
                        HttpStatusCode.Conflict
                    } else {
                        json!!["secrets"]!!.jsonObject.forEach { (name, box) -> if (box is JsonNull) secrets.remove(name) else secrets[name] = box.jsonPrimitive.content }
                        null
                    }
                }

                else -> HttpStatusCode.NotFound
            }
            if (refused != null) return@withLock refused to ""
            HttpStatusCode.OK to view(user).toString()
        }

        private fun view(user: String, hideMe: Boolean = false): JsonElement = buildJsonObject {
            put("user", user)
            household?.let { put("household", it) }
            members[user]?.takeUnless { hideMe }?.let { me ->
                put(
                    "me",
                    buildJsonObject {
                        put("public", me.public)
                        put("private", me.private)
                        if (isGranted(me)) put("grant", me.grant!!)
                    },
                )
            }
            put(
                "members",
                buildJsonArray {
                    members.filterKeys { !hideMe || it != user }.forEach { (name, member) ->
                        add(
                            buildJsonObject {
                                put("user", name)
                                put("public", member.public)
                                put("granted", isGranted(member))
                            },
                        )
                    }
                },
            )
            put("secrets", buildJsonObject { if (household != null) secrets.forEach { (name, box) -> put(name, box) } })
        }
    }

    private companion object {
        const val GOOGLE_KEY = "AIza-household-key"

        /** A server whose relay predates the vault: it answers 404. */
        const val OLD_RELAY_HOST = "old-relay.test"
    }
}

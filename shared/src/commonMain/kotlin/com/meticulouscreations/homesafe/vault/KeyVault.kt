package com.meticulouscreations.homesafe.vault

import com.meticulouscreations.homesafe.data.ASSISTANT_KEYS
import com.meticulouscreations.homesafe.data.ApiKeyStore
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.network.RelayVault
import com.meticulouscreations.homesafe.network.RelayVaultGrant
import com.meticulouscreations.homesafe.network.RelayVaultGrantBody
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.vault_not_open
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Opens the household's key vault on the server with this session's Frigate login, and keeps the
 * keys in memory while the app runs (docs/key-vault.md; the cryptography is [VaultCrypto]):
 *
 * 1. The login key is worked out from the password once per sign-in.
 * 2. This login's key pair is opened from the server; on a first sign-in, or after a new
 *    password, a new one is made and sent up, sealed.
 * 3. The household key is opened from this login's grant. When nobody holds it at all (the very
 *    first phone, or the only member's password changed), a new one is started.
 * 4. The secrets are opened, and any member still waiting is granted the household key.
 *
 * A key the assistant kept on this phone before the vault is moved into it, and the phone's copy
 * dropped. A failure is tried again every few minutes while the login lasts.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KeyVault(
    private val signedIn: SignedInLogin,
    private val connection: ConnectionRepository,
    private val relay: PushRelayApi,
    @Named(ASSISTANT_KEYS) private val assistantStore: ApiKeyStore,
    appScope: CoroutineScope,
) : HouseholdKeys {
    private class Opened(val serverUrl: String, val household: String, val householdKey: ByteArray)

    private val _state = MutableStateFlow<VaultState>(VaultState.SignedOut)
    override val state: StateFlow<VaultState> = _state.asStateFlow()

    private val lock = Mutex()
    private var opened: Opened? = null
    private var loginKey: Pair<SignedInLogin.Login, ByteArray>? = null
    private val refreshes = MutableStateFlow(0)

    /** PBKDF2's rounds; fewer only in tests. */
    internal var iterations = VaultCrypto.LOGIN_ITERATIONS

    init {
        appScope.launch {
            combine(signedIn.login, connection.currentServerUrl, refreshes) { login, url, _ -> login to url }.collectLatest { (login, url) ->
                if (login == null || url == null) {
                    lock.withLock { opened = null }
                    _state.value = VaultState.SignedOut
                    return@collectLatest
                }
                while (true) {
                    if (_state.value !is VaultState.Open) _state.value = VaultState.Opening
                    val result = try {
                        lock.withLock { open(url, login) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        VaultState.Failed(e.userMessage())
                    }
                    _state.value = result
                    if (result !is VaultState.Failed) break
                    delay(RETRY)
                }
            }
        }
    }

    override fun refresh() {
        refreshes.value++
    }

    override suspend fun save(name: String, value: String?): Result<Unit> = lock.withLock {
        val vault = opened ?: return@withLock Result.failure(LocalizedException(_state.value.note() ?: UiText.of(Res.string.vault_not_open), technical = "Vault not open"))
        runCatching {
            val box = value?.let { VaultCrypto.sealSecret(vault.householdKey, name, it) }
            val answer = relay.putVaultSecrets(vault.serverUrl, vault.household, mapOf(name to box)).getOrThrow()
            _state.value = VaultState.Open(openSecrets(vault, answer))
        }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun open(serverUrl: String, login: SignedInLogin.Login): VaultState {
        var vault = relay.getVault(serverUrl).getOrThrow() ?: return VaultState.Unsupported
        val loginKey = loginKeyFor(login)

        // This login's key pair: opened, or made afresh (a first sign-in, or a new password).
        val me = vault.me
        var member = me?.let { VaultCrypto.openMember(loginKey, it.public, it.private) }
        if (member == null) {
            member = VaultCrypto.newMember(loginKey)
            vault = relay.putVaultMember(serverUrl, member.public, member.sealedPrivate).getOrThrow()
        }

        // The household key: this login's grant, or a new one when nobody holds it.
        val household = vault.household
        var householdKey = vault.me?.grant?.let { VaultCrypto.openGrant(member, it.toCrypto()) }
        val opened = if (householdKey != null && household != null) {
            Opened(serverUrl, household, householdKey)
        } else if (vault.members.none { it.granted }) {
            householdKey = VaultCrypto.newHouseholdKey()
            val started = Uuid.random().toString()
            vault = relay.startVaultHousehold(serverUrl, VaultCrypto.grant(householdKey, member.public).toBody(started)).getOrThrow()
            Opened(serverUrl, started, householdKey)
        } else {
            this.opened = null
            return VaultState.Waiting
        }
        this.opened = opened

        // Let in whoever is waiting: their phone will open the household key next time.
        for (waiting in vault.members.filter { !it.granted && it.user != vault.user }) {
            relay.putVaultGrant(serverUrl, waiting.user, VaultCrypto.grant(opened.householdKey, waiting.public).toBody(opened.household))
                .onSuccess { vault = it }
        }

        var secrets = openSecrets(opened, vault)
        secrets = moveAssistantKey(opened, secrets)
        return VaultState.Open(secrets)
    }

    /** The Anthropic key the assistant kept on this phone before the vault: into the vault if it has none, and off the phone either way. */
    private suspend fun moveAssistantKey(vault: Opened, secrets: Map<String, String>): Map<String, String> {
        val local = assistantStore.apiKey() ?: return secrets
        var moved = secrets
        if (HouseholdKeys.ANTHROPIC !in secrets) {
            val box = VaultCrypto.sealSecret(vault.householdKey, HouseholdKeys.ANTHROPIC, local)
            val answer = relay.putVaultSecrets(vault.serverUrl, vault.household, mapOf(HouseholdKeys.ANTHROPIC to box)).getOrNull() ?: return secrets
            moved = openSecrets(vault, answer)
        }
        assistantStore.saveApiKey(null)
        return moved
    }

    private suspend fun openSecrets(vault: Opened, answer: RelayVault): Map<String, String> =
        answer.secrets.mapNotNull { (name, box) -> VaultCrypto.openSecret(vault.householdKey, name, box)?.let { name to it } }.toMap()

    private suspend fun loginKeyFor(login: SignedInLogin.Login): ByteArray {
        loginKey?.let { (held, key) -> if (held == login) return key }
        return withContext(Dispatchers.Default) { VaultCrypto.loginKey(login.username, login.password, iterations) }.also { loginKey = login to it }
    }

    private fun RelayVaultGrant.toCrypto() = VaultCrypto.Grant(ephemeral, box)

    private fun VaultCrypto.Grant.toBody(household: String) = RelayVaultGrantBody(household, ephemeral, box)

    private companion object {
        val RETRY = 3.minutes
    }
}

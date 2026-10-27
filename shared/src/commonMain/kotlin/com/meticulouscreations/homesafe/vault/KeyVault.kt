package com.meticulouscreations.homesafe.vault

import com.meticulouscreations.homesafe.AppVisibility
import com.meticulouscreations.homesafe.data.ASSISTANT_KEYS
import com.meticulouscreations.homesafe.data.ApiKeyStore
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.network.RelayVault
import com.meticulouscreations.homesafe.network.RelayVaultGrant
import com.meticulouscreations.homesafe.network.RelayVaultGrantBody
import com.meticulouscreations.homesafe.network.VaultConflictException
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
 * dropped. It looks again while the app is in front (every minute while waiting to be let in,
 * every ten once open, three after a failure, and each time the app comes back to the
 * foreground), and an open household is only ever used for the login and server it was opened for.
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
    /** The household open on this phone, and whose login and which server it was opened for: it is never used for another. */
    private class Opened(val login: SignedInLogin.Login, val serverUrl: String, val household: String, val householdKey: ByteArray)

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
            combine(signedIn.login, connection.currentServerUrl, refreshes, AppVisibility.inForeground) { login, url, _, visible -> Triple(login, url, visible) }
                .collectLatest { (login, url, visible) ->
                    if (login == null || url == null) {
                        lock.withLock { opened = null }
                        _state.value = VaultState.SignedOut
                        return@collectLatest
                    }
                    // Another login or another server: what was open belongs to that one, and is never shown or written for this.
                    lock.withLock {
                        if (opened?.let { it.login != login || it.serverUrl != url } == true) {
                            opened = null
                            _state.value = VaultState.Opening
                        }
                    }
                    // In the background, nothing to look at; coming back to the foreground looks again.
                    if (!visible) return@collectLatest
                    while (true) {
                        if (_state.value !is VaultState.Open) _state.value = VaultState.Opening
                        val result = try {
                            lock.withLock { openLookingAgain(url, login) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            VaultState.Failed(e.userMessage())
                        }
                        // A recheck that can't reach the server leaves an open vault open: the keys in hand are still good.
                        val keepOpen = result is VaultState.Failed && _state.value is VaultState.Open && lock.withLock { opened != null }
                        if (!keepOpen) _state.value = result
                        // Keep looking: a waiting login is let in, and keys saved or forgotten on another phone arrive.
                        delay(
                            when (result) {
                                is VaultState.Failed -> RETRY
                                VaultState.Waiting -> WAITING_RECHECK
                                else -> OPEN_RECHECK
                            },
                        )
                    }
                }
        }
    }

    override fun refresh() {
        refreshes.value++
    }

    override suspend fun save(name: String, value: String?): Result<Unit> = lock.withLock {
        // Only into the household open for this login on this server, never one opened earlier for another.
        val vault = opened?.takeIf { it.login == signedIn.login.value && it.serverUrl == connection.currentServerUrl.value && _state.value is VaultState.Open }
            ?: return@withLock Result.failure(LocalizedException(_state.value.note() ?: UiText.of(Res.string.vault_not_open), technical = "Vault not open"))
        runCatching {
            val box = value?.let { VaultCrypto.sealSecret(vault.householdKey, name, it) }
            val answer = relay.putVaultSecrets(vault.serverUrl, vault.household, mapOf(name to box)).getOrThrow()
            _state.value = VaultState.Open(openSecrets(vault, answer))
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * [open], again from the top when the vault changed under it (another phone of the same login
     * set its key pair first, or someone's was replaced): the second look finds what won.
     */
    private suspend fun openLookingAgain(serverUrl: String, login: SignedInLogin.Login): VaultState {
        repeat(CONFLICT_ATTEMPTS - 1) {
            try {
                return open(serverUrl, login)
            } catch (_: VaultConflictException) {
                // Look again.
            }
        }
        return open(serverUrl, login)
    }

    private suspend fun open(serverUrl: String, login: SignedInLogin.Login): VaultState {
        var vault = relay.getVault(serverUrl).getOrThrow() ?: run {
            this.opened = null
            return VaultState.Unsupported
        }
        val loginKey = loginKeyFor(login)

        // This login's key pair: opened, or made afresh (a first sign-in, or a new password), in
        // place of exactly the one seen here, so a phone never replaces one it hasn't looked at.
        val me = vault.me
        var member = me?.let { VaultCrypto.openMember(loginKey, it.public, it.private) }
        if (member == null) {
            member = VaultCrypto.newMember(loginKey)
            vault = relay.putVaultMember(serverUrl, member.public, member.sealedPrivate, replaces = me?.public).getOrThrow()
        }

        // The household key: this login's grant, or a new one when nobody holds it.
        val household = vault.household
        var householdKey = vault.me?.grant?.let { VaultCrypto.openGrant(member, it.toCrypto()) }
        val opened = if (householdKey != null && household != null) {
            Opened(login, serverUrl, household, householdKey)
        } else if (vault.members.none { it.granted }) {
            householdKey = VaultCrypto.newHouseholdKey()
            val started = Uuid.random().toString()
            vault = relay.startVaultHousehold(serverUrl, VaultCrypto.grant(householdKey, member.public).toBody(started, member.public)).getOrThrow()
            Opened(login, serverUrl, started, householdKey)
        } else {
            this.opened = null
            return VaultState.Waiting
        }
        this.opened = opened

        // Let in whoever is waiting: their phone will open the household key next time. A grant the
        // relay refuses (their key pair changed meanwhile) is made again at the next look.
        for (waiting in vault.members.filter { !it.granted && it.user != vault.user }) {
            relay.putVaultGrant(serverUrl, waiting.user, VaultCrypto.grant(opened.householdKey, waiting.public).toBody(opened.household, waiting.public))
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

    private fun VaultCrypto.Grant.toBody(household: String, recipient: String) = RelayVaultGrantBody(household, recipient, ephemeral, box)

    private companion object {
        val RETRY = 3.minutes
        val WAITING_RECHECK = 1.minutes
        val OPEN_RECHECK = 10.minutes
        const val CONFLICT_ATTEMPTS = 3
    }
}

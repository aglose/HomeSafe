package com.meticulouscreations.homesafe.vault

import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.vault_failed
import homesafe.shared.generated.resources.vault_not_open
import homesafe.shared.generated.resources.vault_opening
import homesafe.shared.generated.resources.vault_signed_out
import homesafe.shared.generated.resources.vault_unsupported
import homesafe.shared.generated.resources.vault_waiting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The household's API keys (Anthropic's, Google's), kept on the server sealed with each member's
 * Frigate login and opened into memory on sign-in ([KeyVault], docs/key-vault.md). Nothing here
 * is ever written to the phone.
 */
interface HouseholdKeys {
    val state: StateFlow<VaultState>

    /** [name]'s key as the vault holds it now; null when there is none, or the vault isn't open. */
    fun current(name: String): String? = (state.value as? VaultState.Open)?.secrets?.get(name)

    /** [name]'s key as it changes: the vault opening on sign-in, someone saving or forgetting it. */
    fun observe(name: String): Flow<String?> = state.map { (it as? VaultState.Open)?.secrets?.get(name) }.distinctUntilChanged()

    /** Seals [value] (null forgets it) for the whole household. Fails while the vault isn't open. */
    suspend fun save(name: String, value: String?): Result<Unit>

    /** Looks at the server again: someone may have saved a key, or let this login in, since. */
    fun refresh() = Unit

    companion object {
        const val ANTHROPIC = "anthropic"
        const val GOOGLE_MAPS = "google_maps"
    }
}

/** How the vault stands on this phone. */
sealed interface VaultState {
    /** Not signed in to the server this session: there is no login to open it with. */
    data object SignedOut : VaultState

    data object Opening : VaultState

    /** The relay is older than the vault. */
    data object Unsupported : VaultState

    /** Others hold the household key, and none has opened the app since this login joined (or its password changed). */
    data object Waiting : VaultState

    data class Open(val secrets: Map<String, String>) : VaultState

    /** The server couldn't be asked, or refused; tried again shortly. */
    data class Failed(val reason: UiText) : VaultState
}

/** What to say about [VaultState] where a key can't be used or saved yet; null once the vault is open. */
fun VaultState.note(): UiText? = when (this) {
    VaultState.SignedOut -> UiText.of(Res.string.vault_signed_out)
    VaultState.Opening -> UiText.of(Res.string.vault_opening)
    VaultState.Unsupported -> UiText.of(Res.string.vault_unsupported)
    VaultState.Waiting -> UiText.of(Res.string.vault_waiting)
    is VaultState.Failed -> UiText.of(Res.string.vault_failed, reason)
    is VaultState.Open -> null
}

/** For tests and previews: an open vault in memory. */
class InMemoryHouseholdKeys(vararg secrets: Pair<String, String>) : HouseholdKeys {
    private val _state = MutableStateFlow<VaultState>(VaultState.Open(secrets.toMap()))
    override val state: StateFlow<VaultState> = _state.asStateFlow()

    fun set(state: VaultState) {
        _state.value = state
    }

    override suspend fun save(name: String, value: String?): Result<Unit> {
        val open = _state.value as? VaultState.Open
            ?: return Result.failure(LocalizedException(_state.value.note() ?: UiText.of(Res.string.vault_not_open), technical = "Vault not open"))
        _state.value = VaultState.Open(if (value == null) open.secrets - name else open.secrets + (name to value))
        return Result.success(Unit)
    }
}

/**
 * The Frigate login this session signed in with, in memory only, for [KeyVault] to open the vault
 * with. Set by the connection repository on every sign-in, by password or by the saved login.
 */
@Inject
@SingleIn(AppScope::class)
class SignedInLogin {
    class Login(val username: String, val password: String) {
        override fun equals(other: Any?): Boolean = other is Login && other.username == username && other.password == password

        override fun hashCode(): Int = 31 * username.hashCode() + password.hashCode()

        // Never the password: this ends up in logs and crash reports.
        override fun toString(): String = "Login($username)"
    }

    private val _login = MutableStateFlow<Login?>(null)
    val login: StateFlow<Login?> = _login.asStateFlow()

    fun signedIn(username: String, password: String) {
        _login.value = Login(username, password)
    }
}

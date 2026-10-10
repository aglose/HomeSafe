package com.meticulouscreations.homesafe.finance

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.platform.IdentityCheck
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.FinanceAccess
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_fingerprint
import homesafe.shared.generated.resources.biometric_name_generic
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.StringResource
import kotlin.test.fail

/**
 * [FinanceAccess] as a test sets it. As made, the server answers and nothing is locked;
 * [goOffline] is the server going away, which locks until [unlock] is passed.
 */
internal class FakeFinanceAccess(override val canUnlock: Boolean = true) : FinanceAccess {
    override val online = MutableStateFlow(true)
    override val locked = MutableStateFlow(false)
    override val unlockMethod: StringResource = Res.string.biometric_name_fingerprint

    /** What the fingerprint prompt answers. */
    var check: Result<Unit> = Result.success(Unit)
    var checks = 0

    fun goOffline() {
        online.value = false
        locked.value = true
    }

    fun comeOnline() {
        online.value = true
        locked.value = false
    }

    override suspend fun unlock(): Result<Unit> {
        checks++
        return check.onSuccess { locked.value = false }
    }

    override fun lock() {
        if (!online.value) locked.value = true
    }
}

/** A [ConnectionRepository] for code that only asks where the server is and whether it answers. */
internal class FakeServerConnection(url: String?, unreachable: Boolean = false) : ConnectionRepository {
    override val activeConnection = MutableStateFlow(url?.let { ActiveConnection(it, null, ConnectionRoute.TAILSCALE) })
    override val serverUnreachable = MutableStateFlow(unreachable)
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

    fun signIn(url: String) {
        currentServerUrl.value = url
        activeConnection.value = ActiveConnection(url, null, ConnectionRoute.TAILSCALE)
    }
}

/** A fingerprint reader that answers as told. */
internal class FakeIdentityCheck(override var isAvailable: Boolean = true) : IdentityCheck {
    override val methodName: StringResource = Res.string.biometric_name_fingerprint
    var answer: Result<Unit> = Result.success(Unit)
    var prompts = 0

    override suspend fun confirm(): Result<Unit> {
        prompts++
        return answer
    }
}

package com.meticulouscreations.homesafe.finance

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [ConnectionRepository] for code that only asks where the server is and whether it answers.
 * In :testing because the finance and atlas tests both use it.
 */
class FakeServerConnection(url: String?, unreachable: Boolean = false) : ConnectionRepository {
    override val activeConnection = MutableStateFlow(url?.let { ActiveConnection(it, null, ConnectionRoute.TAILSCALE) })
    override val serverUnreachable = MutableStateFlow(unreachable)
    override val currentServerUrl = MutableStateFlow(url)
    override val mostRecentConnection = MutableStateFlow<ConnectionRecord?>(null)
    override val biometricLoginAvailable = false
    override val biometricDisplayName = Res.string.biometric_name_generic
    override fun hasSavedBiometricCredentials() = false
    override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = error("unused")
    override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = error("unused")
    override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = error("unused")
    override fun forgetBiometricCredentials() = Unit
    override fun onAppVisibilityChanged(visible: Boolean) = Unit

    fun signIn(url: String) {
        currentServerUrl.value = url
        activeConnection.value = ActiveConnection(url, null, ConnectionRoute.TAILSCALE)
    }

    /** What the server does when asked directly: answer, or not. */
    var answers = true
    var checks = 0

    override suspend fun checkServer() {
        checks++
        serverUnreachable.value = !answers
    }
}

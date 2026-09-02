package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Inject
@SingleIn(AppScope::class)
class ConnectionRepositoryImpl(
    private val apiClient: FrigateApiClient,
    private val connectionHistoryDao: ConnectionHistoryDao,
    private val cameraDao: CameraDao,
    private val biometricCredentialStore: BiometricCredentialStore,
) : ConnectionRepository {

    private val _currentServerUrl = MutableStateFlow<String?>(null)
    override val currentServerUrl: StateFlow<String?> = _currentServerUrl

    override val mostRecentConnection: Flow<ConnectionRecord?> =
        connectionHistoryDao.mostRecentAsFlow().map { entity ->
            entity?.let { ConnectionRecord(serverUrl = it.serverUrl, connectedAtEpochMillis = it.connectedAtEpochMillis) }
        }

    override val biometricLoginAvailable: Boolean = biometricCredentialStore.isAvailable()
    override val biometricDisplayName: String = biometricCredentialStore.displayName()

    override fun hasSavedBiometricCredentials(): Boolean = biometricCredentialStore.hasSavedCredentials()

    @OptIn(ExperimentalTime::class)
    override suspend fun connect(serverUrl: String, username: String, password: String): Result<SavedCredentials> =
        apiClient.login(serverUrl, username, password)
            .mapCatching { apiClient.getCameras(serverUrl).getOrThrow() }
            .onSuccess { cameras ->
                cameraDao.deleteByServer(serverUrl)
                cameraDao.insertAll(
                    cameras.map {
                        CameraEntity(
                            serverUrl = serverUrl,
                            name = it.name,
                            enabled = it.enabled,
                            liveStreamName = it.liveStreamName,
                            gridStreamName = it.gridStreamName,
                        )
                    },
                )
                connectionHistoryDao.insert(
                    ConnectionHistoryEntity(serverUrl = serverUrl, connectedAtEpochMillis = Clock.System.now().toEpochMilliseconds()),
                )
                _currentServerUrl.value = serverUrl
            }
            .map { SavedCredentials(serverUrl = serverUrl, username = username, password = password) }

    override suspend fun signInWithBiometrics(): Result<SavedCredentials> =
        biometricCredentialStore.authenticateAndRetrieve().fold(
            onSuccess = { credentials -> connect(credentials.serverUrl, credentials.username, credentials.password) },
            onFailure = { Result.failure(it) },
        )

    override suspend fun saveBiometricCredentials(credentials: SavedCredentials): Result<Unit> =
        biometricCredentialStore.save(credentials)

    override fun forgetBiometricCredentials() {
        biometricCredentialStore.clear()
    }
}

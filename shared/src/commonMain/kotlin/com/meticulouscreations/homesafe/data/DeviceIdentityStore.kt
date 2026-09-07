package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.HomeLocation
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Who this install is to the push relay — see [DeviceIdentityEntity]. The id is minted on first
 * use and kept for the life of the install; the secret arrives with each registration.
 */
@Inject
@SingleIn(AppScope::class)
class DeviceIdentityStore(private val settingsDao: SettingsDao) {

    private val mutex = Mutex()

    /** This install's id, minting one the first time anything asks. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun deviceId(): String = mutex.withLock {
        settingsDao.getDeviceIdentity()?.deviceId ?: DeviceIdentityEntity(deviceId = Uuid.random().toString())
            .also { settingsDao.upsertDeviceIdentity(it) }
            .deviceId
    }

    /** The relay secret from the last registration, or null before the first one. */
    suspend fun secret(): String? = settingsDao.getDeviceIdentity()?.secret

    suspend fun saveSecret(secret: String) = update { copy(secret = secret) }

    /** The household's home as last seen from the relay, so a geofence can be re-armed offline. */
    suspend fun cachedHome(): HomeLocation? = settingsDao.getDeviceIdentity()?.let { row ->
        val lat = row.homeLatitude ?: return null
        val lng = row.homeLongitude ?: return null
        HomeLocation(lat, lng, row.homeRadiusMeters ?: return null)
    }

    suspend fun saveCachedHome(home: HomeLocation?) = update {
        copy(homeLatitude = home?.latitude, homeLongitude = home?.longitude, homeRadiusMeters = home?.radiusMeters)
    }

    private suspend fun update(change: DeviceIdentityEntity.() -> DeviceIdentityEntity) {
        deviceId() // make sure the row exists
        mutex.withLock {
            val current = settingsDao.getDeviceIdentity() ?: return
            settingsDao.upsertDeviceIdentity(current.change())
        }
    }
}

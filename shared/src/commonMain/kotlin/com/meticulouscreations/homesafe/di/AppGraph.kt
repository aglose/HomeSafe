package com.meticulouscreations.homesafe.di

import com.meticulouscreations.homesafe.Platform
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.data.BiometricCredentialStore
import com.meticulouscreations.homesafe.data.CameraDao
import com.meticulouscreations.homesafe.data.ConnectionHistoryDao
import com.meticulouscreations.homesafe.data.DetectionAlertService
import com.meticulouscreations.homesafe.data.DeviceRegistrar
import com.meticulouscreations.homesafe.data.PropertyLayoutDao
import com.meticulouscreations.homesafe.data.SettingsDao
import com.meticulouscreations.homesafe.data.createAlertNotifier
import com.meticulouscreations.homesafe.data.createBiometricCredentialStore
import com.meticulouscreations.homesafe.data.createCameraDao
import com.meticulouscreations.homesafe.data.createClipDownloader
import com.meticulouscreations.homesafe.data.createConnectionHistoryDao
import com.meticulouscreations.homesafe.data.createDeviceInfo
import com.meticulouscreations.homesafe.data.createGeofenceMonitor
import com.meticulouscreations.homesafe.data.createPropertyLayoutDao
import com.meticulouscreations.homesafe.data.createPushTokenProvider
import com.meticulouscreations.homesafe.data.createSettingsDao
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceAutomation
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import com.meticulouscreations.homesafe.getPlatform
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.NetworkMonitor
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.network.createNetworkMonitor
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The app's single Metro graph. Layered as the Android architecture guide describes:
 *
 * - **UI** (`ui/`, `viewmodel/`): composables obtain view models through Metro's
 *   `metroViewModel()` / `assistedMetroViewModel()`, backed by [HomeSafeViewModelFactory];
 *   view models depend only on `domain` use cases.
 * - **Domain** (`domain/`): use cases, models, and the interfaces the domain needs satisfied —
 *   repositories and platform ports. No knowledge of Room, Ktor, or Frigate's URL layout.
 * - **Data** (`data/`, `network/`): repository implementations that choose between the local
 *   data sources (Room DAOs, the biometric store) and the remote ones (the Frigate API client),
 *   plus the per-platform ports (notifications, downloads).
 *
 * Use cases self-register via `@Inject`, repository implementations via `@ContributesBinding`,
 * and view models via `@ContributesIntoMap`, so this file only lists what needs a hand-written
 * provider (platform factories, the HTTP client) and the few things platform entry points read.
 */
@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph {

    /**
     * The one shared Ktor client. Login stores Frigate's session cookie on it, so anything else
     * that must hit the authenticated API — including Coil loading camera snapshots — has to
     * go through this same instance rather than its own cookie-less client.
     */
    val httpClient: HttpClient

    /** What the platform handed the graph at creation; the live-player warm-up needs it at start. */
    val platformContext: PlatformContext

    val connectionRepository: ConnectionRepository
    val settingsRepository: SettingsRepository
    val pushRelayApi: PushRelayApi

    /** Started once by [com.meticulouscreations.homesafe.App]; posts notifications for new detections while the app runs. */
    val detectionAlertService: DetectionAlertService

    /** Started once by [com.meticulouscreations.homesafe.App]; Android's messaging service also reaches it on token rotation. */
    val deviceRegistrar: DeviceRegistrar

    /**
     * Started once by [com.meticulouscreations.homesafe.App]. Also the entry point for what the OS
     * wakes on a geofence crossing or a reboot (Android receivers, iOS's launch), which builds a
     * graph of its own to reach it.
     */
    val presenceAutomation: PresenceAutomation

    @Provides
    fun providePlatform(): Platform = getPlatform()

    /** Wall-clock time for view models, so tests can substitute a fixed one. */
    @OptIn(ExperimentalTime::class)
    @Provides
    fun provideClock(): Clock = Clock.System

    @SingleIn(AppScope::class)
    @Provides
    fun provideConnectionHistoryDao(platformContext: PlatformContext): ConnectionHistoryDao =
        createConnectionHistoryDao(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideCameraDao(platformContext: PlatformContext): CameraDao =
        createCameraDao(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideSettingsDao(platformContext: PlatformContext): SettingsDao =
        createSettingsDao(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun providePropertyLayoutDao(platformContext: PlatformContext): PropertyLayoutDao =
        createPropertyLayoutDao(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideBiometricCredentialStore(platformContext: PlatformContext): BiometricCredentialStore =
        createBiometricCredentialStore(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideNetworkMonitor(platformContext: PlatformContext): NetworkMonitor =
        createNetworkMonitor(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideClipDownloader(platformContext: PlatformContext, httpClient: HttpClient): ClipDownloader =
        createClipDownloader(platformContext, httpClient)

    @SingleIn(AppScope::class)
    @Provides
    fun provideAlertNotifier(platformContext: PlatformContext): AlertNotifier =
        createAlertNotifier(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun providePushTokenProvider(platformContext: PlatformContext): PushTokenProvider =
        createPushTokenProvider(platformContext)

    @SingleIn(AppScope::class)
    @Provides
    fun provideDeviceInfo(platformContext: PlatformContext): DeviceInfo = createDeviceInfo(platformContext)

    /**
     * The monitor reports crossings to the automation and the automation drives the monitor, so
     * the automation side is a [Provider]: resolved on the first crossing, not at construction.
     */
    @SingleIn(AppScope::class)
    @Provides
    fun provideGeofenceMonitor(platformContext: PlatformContext, automation: Provider<PresenceAutomation>): GeofenceMonitor =
        createGeofenceMonitor(platformContext) { exited -> automation().onGeofenceTransition(exited) }

    @OptIn(ExperimentalTime::class)
    @SingleIn(AppScope::class)
    @Provides
    fun provideDetectionAlertService(
        apiClient: FrigateApiClient,
        connectionRepository: ConnectionRepository,
        settingsRepository: SettingsRepository,
        presenceRepository: PresenceRepository,
        alertNotifier: AlertNotifier,
        appScope: CoroutineScope,
        clock: Clock,
    ): DetectionAlertService = DetectionAlertService(
        apiClient = apiClient,
        connectionRepository = connectionRepository,
        settingsRepository = settingsRepository,
        presenceRepository = presenceRepository,
        notifier = alertNotifier,
        scope = appScope,
        clock = { clock.now().toEpochMilliseconds() / 1000.0 },
        pollIntervalMs = DETECTION_POLL_INTERVAL_MS,
    )

    /** An app-lifetime scope for background work that outlives any one screen, e.g. following network changes. */
    @SingleIn(AppScope::class)
    @Provides
    fun provideAppCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The jar behind [provideHttpClient]'s cookie plugin, shared with [FrigateApiClient] so a
     * session issued at one of the server's addresses can be filed under the other — the plugin
     * only reads from it.
     */
    @SingleIn(AppScope::class)
    @Provides
    fun provideCookieStorage(): CookiesStorage = AcceptAllCookiesStorage()

    @SingleIn(AppScope::class)
    @Provides
    fun provideHttpClient(cookieStorage: CookiesStorage): HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpCookies) { storage = cookieStorage }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
    }

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides platformContext: PlatformContext): AppGraph
    }
}

/** How often the alert poller asks Frigate for new detections; one small request per tick. */
private const val DETECTION_POLL_INTERVAL_MS = 15_000L

fun createAppGraph(platformContext: PlatformContext): AppGraph =
    createGraphFactory<AppGraph.Factory>().create(platformContext)

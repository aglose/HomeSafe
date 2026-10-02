package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.StorageUsage
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.connection_route_local_network
import homesafe.shared.generated.resources.connection_route_tailscale
import homesafe.shared.generated.resources.settings_server_summary_checking
import homesafe.shared.generated.resources.settings_server_summary_disk_full
import homesafe.shared.generated.resources.settings_server_summary_healthy
import homesafe.shared.generated.resources.settings_server_summary_refresh_failed
import homesafe.shared.generated.resources.settings_server_summary_storage_used
import homesafe.shared.generated.resources.settings_server_summary_unreachable
import homesafe.shared.generated.resources.settings_server_summary_update_available
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Server row's one line on the main Settings page: route, disk, and the most pressing thing wrong. */
class ServerSummaryTest {

    private fun overview(usedMb: Double? = 550_000.0, latest: String? = "0.17.2") = ServerOverview(
        version = "0.17.2-3d4dd3a",
        latestVersion = latest,
        uptimeSeconds = 86_400,
        cpuPercent = 9.0,
        memoryPercent = 40.0,
        recordingsStorage = usedMb?.let { StorageUsage("/media/frigate/recordings", it, 1_000_000.0) },
        detector = null,
        gpus = emptyList(),
        retention = RetentionPolicy(7.0, 7.0, null, null),
        faceRecognitionEnabled = true,
        licensePlateRecognitionEnabled = false,
        semanticSearchEnabled = false,
        cameras = emptyList(),
        canEditConfig = true,
    )

    private fun summary(vararg parts: UiText) = UiText.Joined(parts.toList(), UiText.of(Res.string.common_dot_separator))

    private val tailscale = UiText.of(Res.string.connection_route_tailscale)

    private fun storageUsed(percent: Int) = UiText.of(Res.string.settings_server_summary_storage_used, percent)

    private fun status(res: StringResource) = UiText.of(res)

    @Test
    fun aHealthyServerReadsRouteDiskAndHealthy() {
        assertEquals(summary(tailscale, storageUsed(55), status(Res.string.settings_server_summary_healthy)), serverSummary(ConnectionRoute.TAILSCALE, overview(), null))
        assertEquals(
            summary(UiText.of(Res.string.connection_route_local_network), storageUsed(55), status(Res.string.settings_server_summary_healthy)),
            serverSummary(ConnectionRoute.LOCAL_NETWORK, overview(), null),
        )
    }

    @Test
    fun theMostPressingProblemReplacesHealthy() {
        assertEquals(
            summary(tailscale, storageUsed(93), status(Res.string.settings_server_summary_disk_full)),
            serverSummary(ConnectionRoute.TAILSCALE, overview(usedMb = 930_000.0, latest = "0.18.0"), null),
        )
        assertEquals(
            summary(tailscale, storageUsed(55), status(Res.string.settings_server_summary_update_available)),
            serverSummary(ConnectionRoute.TAILSCALE, overview(latest = "0.18.0"), null),
        )
        assertEquals(
            summary(tailscale, storageUsed(55), status(Res.string.settings_server_summary_refresh_failed)),
            serverSummary(ConnectionRoute.TAILSCALE, overview(), "timeout".asUiText()),
        )
    }

    @Test
    fun whatTheServerHasNotReportedIsLeftOut() {
        assertEquals(summary(tailscale, status(Res.string.settings_server_summary_checking)), serverSummary(ConnectionRoute.TAILSCALE, null, null))
        assertEquals(summary(tailscale, status(Res.string.settings_server_summary_unreachable)), serverSummary(ConnectionRoute.TAILSCALE, null, "timeout".asUiText()))
        assertEquals(summary(status(Res.string.settings_server_summary_healthy)), serverSummary(null, overview(usedMb = null), null))
    }
}

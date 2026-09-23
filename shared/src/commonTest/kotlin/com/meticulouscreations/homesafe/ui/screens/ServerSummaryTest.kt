package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.StorageUsage
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

    @Test
    fun aHealthyServerReadsRouteDiskAndHealthy() {
        assertEquals("Tailscale · 55% storage used · healthy", serverSummary(ConnectionRoute.TAILSCALE, overview(), null))
        assertEquals("Local network · 55% storage used · healthy", serverSummary(ConnectionRoute.LOCAL_NETWORK, overview(), null))
    }

    @Test
    fun theMostPressingProblemReplacesHealthy() {
        assertEquals("Tailscale · 93% storage used · disk nearly full", serverSummary(ConnectionRoute.TAILSCALE, overview(usedMb = 930_000.0, latest = "0.18.0"), null))
        assertEquals("Tailscale · 55% storage used · update available", serverSummary(ConnectionRoute.TAILSCALE, overview(latest = "0.18.0"), null))
        assertEquals("Tailscale · 55% storage used · last refresh failed", serverSummary(ConnectionRoute.TAILSCALE, overview(), "timeout"))
    }

    @Test
    fun whatTheServerHasNotReportedIsLeftOut() {
        assertEquals("Tailscale · checking…", serverSummary(ConnectionRoute.TAILSCALE, null, null))
        assertEquals("Tailscale · can't reach the server", serverSummary(ConnectionRoute.TAILSCALE, null, "timeout"))
        assertEquals("healthy", serverSummary(null, overview(usedMb = null), null))
    }
}

package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.DeviceInfo
import io.github.johnkuruvila.z50bridge.data.buildDiagnosticsReport
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    private val device = DeviceInfo(appVersion = "0.3.1", device = "Google Pixel", android = "16 (API 36)")

    @Test
    fun secretsAreNeverWritten() {
        val settings = BridgeSettings(
            destToken = "receiver-token-value",
            cameraWifiPassword = "camera-wifi-password",
            offloadWifiAllowlist = setOf("HomeNetworkName"),
        )
        val report = buildDiagnosticsReport(device, BridgeUiState(), settings, nowMs = 0, zone = ZoneOffset.UTC)

        assertFalse(report.contains("receiver-token-value"))
        assertFalse(report.contains("camera-wifi-password"))
        assertFalse(report.contains("HomeNetworkName"))
        assertTrue(report.contains("Receiver token: set"))
        assertTrue(report.contains("Camera Wi-Fi password: set"))
        assertTrue(report.contains("Offload Wi-Fi allowlist: 1 networks"))
    }

    @Test
    fun activityIsListedNewestFirstWithFullTimestamps() {
        val state = BridgeUiState(
            activity = listOf(
                ActivityItem(timeMs = 86_400_000, text = "Upload failed: 422", error = true),
                ActivityItem(timeMs = 0, text = "Synced 3 files", kind = ActivityKind.Sync),
            ),
        )
        val report = buildDiagnosticsReport(device, state, BridgeSettings(), nowMs = 0, zone = ZoneOffset.UTC)

        val errorLine = "1970-01-02 00:00:00 [Error] Upload failed: 422"
        val syncLine = "1970-01-01 00:00:00 [Sync] Synced 3 files"
        assertTrue(report.contains(errorLine))
        assertTrue(report.indexOf(errorLine) < report.indexOf(syncLine))
    }

    @Test
    fun emptyLogStillProducesAReport() {
        val report = buildDiagnosticsReport(device, BridgeUiState(), BridgeSettings(), nowMs = 0, zone = ZoneOffset.UTC)

        assertTrue(report.contains("App: 0.3.1"))
        assertTrue(report.contains("[Activity] 0 entries"))
        assertTrue(report.contains("(empty)"))
    }
}

package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.DeviceInfo
import io.github.johnkuruvila.z50bridge.data.DiagnosticsSendPolicy
import io.github.johnkuruvila.z50bridge.data.DiagnosticsSentMarker
import io.github.johnkuruvila.z50bridge.data.buildDiagnosticsReport
import io.github.johnkuruvila.z50bridge.data.diagnosticsDailyName
import io.github.johnkuruvila.z50bridge.data.diagnosticsUploadName
import io.github.johnkuruvila.z50bridge.diag.DownloadTiming
import io.github.johnkuruvila.z50bridge.diag.FileRecord
import io.github.johnkuruvila.z50bridge.diag.TransferRecorder
import io.github.johnkuruvila.z50bridge.diag.WifiLinkSample
import io.github.johnkuruvila.z50bridge.net.NetworkHint
import io.github.johnkuruvila.z50bridge.net.NetworkUse
import io.github.johnkuruvila.z50bridge.net.networkScore
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatcherFixesTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun cameraNamedWifiIsAlwaysProbedEvenWhenValidated() {
        val camera = NetworkHint(wifi = true, internet = true, validated = true, ssid = "DDPAI_Z50_1234")
        assertEquals(120, networkScore(NetworkUse.Camera, camera))
        val renamed = NetworkHint(wifi = true, internet = true, validated = true, ssid = "Dashcam")
        assertEquals(120, networkScore(NetworkUse.Camera, renamed, cameraSsid = "Dashcam"))
        // Home Wi-Fi with internet is still never probed for the camera.
        assertEquals(-1, networkScore(NetworkUse.Camera, renamed))
    }

    @Test
    fun cameraNamedWifiBeatsOtherCandidates() {
        val camera = networkScore(NetworkUse.Camera, NetworkHint(wifi = true, internet = true, ssid = "DDPAI_Z50"))
        val unnamed = networkScore(NetworkUse.Camera, NetworkHint(wifi = true, internet = false))
        assertTrue(camera > unnamed)
    }

    @Test
    fun cameraNameDoesNotMakeMobileDataOrVpnEligible() {
        assertEquals(-1, networkScore(NetworkUse.Camera, NetworkHint(vpn = true, wifi = true, ssid = "DDPAI_Z50")))
    }

    @Test
    fun diagnosticsAreSentOnlyWhenActivityHasSomethingNew() {
        val policy = DiagnosticsSendPolicy(minIntervalMs = 600_000L)
        assertFalse(policy.shouldSend(0, newestActivityMs = null, sentActivityMs = null, afterOffload = false))
        assertFalse(policy.shouldSend(0, newestActivityMs = 50, sentActivityMs = 50, afterOffload = true))
        assertTrue(policy.shouldSend(0, newestActivityMs = 60, sentActivityMs = 50, afterOffload = false))
    }

    @Test
    fun diagnosticsWaitTheIntervalUnlessAnOffloadJustFinished() {
        val policy = DiagnosticsSendPolicy(minIntervalMs = 600_000L)
        assertTrue(policy.shouldSend(0, 10, null, afterOffload = false))
        assertFalse(policy.shouldSend(599_999, 20, 10, afterOffload = false))
        assertTrue(policy.shouldSend(599_999, 20, 10, afterOffload = true))
        assertFalse(policy.shouldSend(700_000, 30, 20, afterOffload = false))
        assertTrue(policy.shouldSend(1_199_999, 30, 20, afterOffload = false))
    }

    @Test
    fun diagnosticsFileNames() {
        val ms = 1_791_984_605_000L // 2026-10-14 13:30:05 UTC
        assertEquals("z50-diagnostics-20261014-133005.txt", diagnosticsUploadName(ms, utc))
        assertEquals("z50-diagnostics-2026-10-14.txt", diagnosticsDailyName(ms, utc))
    }

    @Test
    fun sentMarkerSurvivesARestart() {
        val file = File(Files.createTempDirectory("diag").toFile(), "sent.txt")
        assertNull(DiagnosticsSentMarker(file).sentActivityMs)
        DiagnosticsSentMarker(file).mark(1234L)
        assertEquals(1234L, DiagnosticsSentMarker(file).sentActivityMs)
    }

    @Test
    fun reportSaysWhenStopPausedTheWatcher() {
        val device = DeviceInfo("0.5.2", "Google Pixel", "16 (API 36)")
        val paused = buildDiagnosticsReport(
            device,
            BridgeUiState(watching = true, autoSyncPaused = true),
            BridgeSettings(),
            nowMs = 0,
            zone = utc,
        )
        assertTrue(paused.contains("Automatic sync: paused by Stop"))
        val running = buildDiagnosticsReport(device, BridgeUiState(watching = true), BridgeSettings(), nowMs = 0, zone = utc)
        assertFalse(running.contains("paused by Stop"))
    }

    @Test
    fun activitySummaryIsPlainAndKeepsRadioDetailsForTheReport() {
        val recorder = TransferRecorder("Sync", startedAtMs = 0)
        recorder.sample(WifiLinkSample(atMs = 0, frequencyMhz = 2437, rssiDbm = -35, rxLinkMbps = 72, standard = "802.11n"))
        recorder.file(FileRecord("a.mp4", "front", 1_000_000, DownloadTiming(), 1))
        val summary = recorder.summary(nowMs = 2_000)
        assertTrue(summary, summary.startsWith("Sync: 1 file, "))
        assertTrue(summary, summary.endsWith(" · signal -35 dBm"))
        assertFalse(summary, summary.contains("GHz"))
        assertFalse(summary, summary.contains("waiting on network"))
    }
}

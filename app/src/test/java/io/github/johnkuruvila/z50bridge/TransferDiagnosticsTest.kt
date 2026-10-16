package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.DeviceInfo
import io.github.johnkuruvila.z50bridge.data.buildDiagnosticsReport
import io.github.johnkuruvila.z50bridge.diag.CameraSample
import io.github.johnkuruvila.z50bridge.diag.DownloadTiming
import io.github.johnkuruvila.z50bridge.diag.FileRecord
import io.github.johnkuruvila.z50bridge.diag.RadioState
import io.github.johnkuruvila.z50bridge.diag.TransferRecorder
import io.github.johnkuruvila.z50bridge.diag.WifiLinkSample
import io.github.johnkuruvila.z50bridge.diag.bluetoothAudioKind
import io.github.johnkuruvila.z50bridge.diag.describeLink
import io.github.johnkuruvila.z50bridge.diag.describeRadios
import io.github.johnkuruvila.z50bridge.diag.parseCameraSample
import io.github.johnkuruvila.z50bridge.diag.wifiBand
import io.github.johnkuruvila.z50bridge.diag.wifiChannel
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferDiagnosticsTest {
    @Test
    fun mapsFrequenciesToChannelsAndBands() {
        assertEquals(1, wifiChannel(2412))
        assertEquals(6, wifiChannel(2437))
        assertEquals(14, wifiChannel(2484))
        assertEquals(36, wifiChannel(5180))
        assertEquals(149, wifiChannel(5745))
        assertNull(wifiChannel(1000))
        assertEquals("2.4 GHz", wifiBand(2437))
        assertEquals("5 GHz", wifiBand(5180))
    }

    @Test
    fun unknownLinkFieldsAreLeftOut() {
        assertEquals("unknown", describeLink(null))
        assertEquals("unknown", describeLink(WifiLinkSample(atMs = 0)))
        val link = describeLink(WifiLinkSample(atMs = 0, frequencyMhz = 2437, rssiDbm = -50, rxLinkMbps = 72))
        assertEquals("2.4 GHz ch 6 (2437 MHz) · RSSI -50 dBm · link rx 72 / tx ? Mbps", link)
    }

    @Test
    fun reportSplitsNetworkWaitFromStorageTime() {
        val recorder = TransferRecorder("Sync", startedAtMs = 0)
        val timing = DownloadTiming().apply {
            startedAtNs = 0
            headersAtNs = 40_000_000
            finishedAtNs = 10_000_000_000
            bytes = 30L * 1024 * 1024
            readWaitNs = 9_000_000_000
            writeNs = 1_000_000_000
            longestReadNs = 500_000_000
        }
        recorder.track(timing)
        recorder.file(FileRecord("clip.mp4", "front", timing.bytes, timing, streamsAtStart = 1))
        recorder.note("Streams", "1 (setting 1)")

        val report = recorder.report(nowMs = 10_000, zone = ZoneOffset.UTC)
        assertTrue(report, report.contains("30.0 MB in 10s = 3.00 MB/s overall · 1 files ok, 0 failed"))
        assertTrue(report, report.contains("waiting on network 90%, writing to storage 10%"))
        assertTrue(report, report.contains("Streams: 1 (setting 1)"))
        assertTrue(report, report.contains("clip.mp4 · front"))
    }

    @Test
    fun cameraSamplesKeepUnknownReadingsUnknown() {
        val read = parseCameraSample(mapOf("gps" to 0, "rec" to 1), mapOf("temp" to "87"), atMs = 60_000)
        assertEquals(CameraSample(60_000, socTemp = 87, gpsFix = false, recording = true), read)
        assertEquals(CameraSample(120_000), parseCameraSample(emptyMap(), emptyMap(), atMs = 120_000))

        val recorder = TransferRecorder("Sync", startedAtMs = 0)
        recorder.cameraSample(read)
        recorder.cameraSample(CameraSample(120_000))
        recorder.cameraSample(CameraSample(180_000, socTemp = 91, gpsFix = true, recording = true))
        val report = recorder.report(nowMs = 180_000, zone = ZoneOffset.UTC)
        assertTrue(report, report.contains("Camera SoC temperature during transfer: first 87, min 87, max 91, last 91"))
        assertTrue(report, report.contains("  60 · 87 · no · yes"))
        assertTrue(report, report.contains("  120 · - · - · -"))
        assertTrue(report, report.contains("  180 · 91 · yes · yes"))
    }

    @Test
    fun radiosAreDescribedWithoutGuessing() {
        assertEquals("unknown", describeRadios(null))
        assertEquals(
            "Bluetooth unknown · Bluetooth scanning setting unknown · mobile data unknown · cell unknown · media unknown",
            describeRadios(RadioState()),
        )
        val car = RadioState(
            bluetoothOn = true,
            bluetoothAudio = listOf("calls (HFP)"),
            bluetoothScanning = true,
            mobileDataOn = false,
            cell = "LTE band 40",
            mediaPlaying = true,
        )
        assertEquals(
            "Bluetooth on, calls (HFP) · Bluetooth scanning setting on · mobile data off · cell LTE band 40 · media playing",
            describeRadios(car),
        )
        assertTrue(describeRadios(RadioState(bluetoothOn = true)).startsWith("Bluetooth on, no audio link"))
        assertEquals("calls (HFP)", bluetoothAudioKind(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertNull(bluetoothAudioKind(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test
    fun reportListsOnlyRadioChanges() {
        val recorder = TransferRecorder("Sync", startedAtMs = 0)
        val on = RadioState(bluetoothOn = true, mobileDataOn = true)
        val off = on.copy(bluetoothOn = false)
        recorder.radios(on, atMs = 0)
        recorder.radios(on, atMs = 5_000)
        recorder.radios(off, atMs = 35_000)
        recorder.radios(off, atMs = 40_000)
        assertEquals(on, recorder.firstRadios())
        assertTrue(recorder.radiosChanged())

        val report = recorder.report(nowMs = 60_000, zone = ZoneOffset.UTC)
        assertTrue(report, report.contains("Phone radios at start: Bluetooth on, no audio link"))
        assertTrue(report, report.contains("Phone radio changes (s · state):\n  35 · Bluetooth off ·"))
        assertEquals(report, 1, Regex("\\n  \\d+ · Bluetooth").findAll(report).count())
    }

    @Test
    fun phaseReportSaysWarmupWasNotCounted() {
        val recorder = TransferRecorder("Speed test", startedAtMs = 0)
        recorder.phase(TransferRecorder.PhaseResult("2 streams", 60L * 1024 * 1024, 12_000, link = null, warmupMs = 4_000))
        recorder.phase(TransferRecorder.PhaseResult("1 stream", 12L * 1024 * 1024, 12_000, link = null))
        val report = recorder.report(nowMs = 30_000, zone = ZoneOffset.UTC)
        assertTrue(report, report.contains("  2 streams: 60.0 MB in 12s = 5.00 MB/s (first 4.0s not counted) · unknown"))
        assertTrue(report, report.contains("  1 stream: 12.0 MB in 12s = 1.00 MB/s · unknown"))
    }

    @Test
    fun reportWithoutCameraSamplesHasNoCameraSection() {
        val report = TransferRecorder("Speed test", startedAtMs = 0).report(nowMs = 1_000, zone = ZoneOffset.UTC)
        assertTrue(report, !report.contains("Camera during transfer"))
    }

    @Test
    fun diagnosticsReportIncludesTransferReports() {
        val report = buildDiagnosticsReport(
            DeviceInfo("0.4.3", "Google Pixel", "17 (API 37)"),
            BridgeUiState(),
            BridgeSettings(),
            nowMs = 0,
            zone = ZoneOffset.UTC,
            transfers = listOf("=== Speed test ===\nPhases:"),
        )
        assertTrue(report.contains("[Transfer diagnostics] 1 recent syncs and speed tests"))
        assertTrue(report.contains("=== Speed test ==="))
    }
}

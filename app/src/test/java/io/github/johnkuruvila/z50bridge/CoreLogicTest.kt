package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.camera.MediaFile
import io.github.johnkuruvila.z50bridge.camera.skipOpenClip
import io.github.johnkuruvila.z50bridge.net.NetworkHint
import io.github.johnkuruvila.z50bridge.net.NetworkUse
import io.github.johnkuruvila.z50bridge.net.networkScore
import io.github.johnkuruvila.z50bridge.net.resolveVpnUnderlay
import io.github.johnkuruvila.z50bridge.sync.OffloadEngine
import io.github.johnkuruvila.z50bridge.sync.ProgressThrottle
import io.github.johnkuruvila.z50bridge.sync.StagedClipScanner
import io.github.johnkuruvila.z50bridge.sync.TransferMeter
import io.github.johnkuruvila.z50bridge.sync.downloadWorkers
import io.github.johnkuruvila.z50bridge.sync.offloadWorkers
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    @Test
    fun singleOpenClipIsSkipped() {
        val clip = MediaFile(name = "open.mp4", kind = "front", startUnix = 100)
        assertTrue(skipOpenClip(listOf(clip)).isEmpty())
    }

    @Test
    fun eachStreamCanDropItsOwnOpenClip() {
        val front = listOf(
            MediaFile(name = "front-closed.mp4", kind = "front", startUnix = 10),
            MediaFile(name = "front-open.mp4", kind = "front", startUnix = 20),
        )
        val rear = listOf(
            MediaFile(name = "rear-closed.mp4", kind = "rear", startUnix = 11),
            MediaFile(name = "rear-open.mp4", kind = "rear", startUnix = 21),
        )

        assertEquals(listOf("front-closed.mp4", "rear-closed.mp4"), (skipOpenClip(front) + skipOpenClip(rear)).map { it.name })
    }

    @Test
    fun gitGpsBundlesAreOffloaded() {
        val staging = Files.createTempDirectory("z50-offload-test").toFile()
        staging.resolve("trip.git").writeText("gps")
        staging.resolve("ignored.part").writeText("partial")

        assertEquals(listOf("trip.git"), OffloadEngine().pendingFiles(staging).map { it.name })
    }

    @Test
    fun offloadPrioritizesLatestVideosAfterGps() {
        val staging = Files.createTempDirectory("z50-offload-order-test").toFile()
        staging.resolve("20260915100000_0060.mp4").writeText("old")
        staging.resolve("20260916120000_0060.mp4").writeText("new")
        staging.resolve("20260916120000.gpx").writeText("gps")

        val files = OffloadEngine().pendingFiles(staging).map { it.name }
        assertEquals(listOf("20260916120000.gpx", "20260916120000_0060.mp4", "20260915100000_0060.mp4"), files)
    }

    @Test
    fun cameraTrafficSkipsVpnAndCellular() {
        assertEquals(-1, networkScore(NetworkUse.Camera, NetworkHint(vpn = true, internet = true)))
        assertEquals(-1, networkScore(NetworkUse.Camera, NetworkHint(cellular = true, internet = true)))
        assertTrue(networkScore(NetworkUse.Camera, NetworkHint(wifi = true)) > 0)
        assertTrue(
            networkScore(NetworkUse.Camera, NetworkHint(wifi = true, internet = false)) >
                networkScore(NetworkUse.Camera, NetworkHint(wifi = true, internet = true)),
        )
    }

    @Test
    fun offloadPrefersLanWifiOverVpn() {
        val lan = networkScore(NetworkUse.Offload, NetworkHint(wifi = true, internet = true))
        val vpn = networkScore(NetworkUse.Offload, NetworkHint(vpn = true, internet = true))
        val cameraAp = networkScore(NetworkUse.Offload, NetworkHint(wifi = true, internet = false))
        assertTrue(lan > vpn)
        assertEquals(-1, cameraAp)
    }

    @Test
    fun offloadNeverUsesMobileData() {
        assertEquals(-1, networkScore(NetworkUse.Offload, NetworkHint(cellular = true, internet = true)))
        assertEquals(-1, networkScore(NetworkUse.Offload, NetworkHint(vpn = true, cellular = true, internet = true)))
        assertTrue(networkScore(NetworkUse.Offload, NetworkHint(vpn = true, wifi = true, internet = true)) > 0)
        assertTrue(networkScore(NetworkUse.Offload, NetworkHint(wired = true, internet = true)) > 0)
    }

    @Test
    fun vpnWithoutReportedUnderlayIsJudgedByOtherNetworks() {
        val vpn = NetworkHint(vpn = true, internet = true)
        val wifi = NetworkHint(wifi = true, internet = true, ssid = "HomeNet")
        val cellular = NetworkHint(cellular = true, internet = true)
        assertFalse(resolveVpnUnderlay(vpn, listOf(vpn, wifi, cellular)).cellular)
        assertTrue(resolveVpnUnderlay(vpn, listOf(vpn, cellular)).cellular)
        assertTrue(resolveVpnUnderlay(vpn, listOf(vpn, NetworkHint(wifi = true, internet = false), cellular)).cellular)
        val reported = NetworkHint(vpn = true, wifi = true, internet = true)
        assertEquals(reported, resolveVpnUnderlay(reported, listOf(reported, cellular)))
    }

    @Test
    fun progressThrottlePublishesFirstAndThenWaits() {
        var now = 1_000L
        val throttle = ProgressThrottle(intervalMs = 200L) { now }
        assertTrue(throttle.shouldPublish("a.mp4", 1, 100))
        now += 50
        assertEquals(false, throttle.shouldPublish("a.mp4", 2, 100))
        now += 200
        assertTrue(throttle.shouldPublish("a.mp4", 3, 100))
        assertTrue(throttle.shouldPublish("a.mp4", 100, 100))
        assertTrue(throttle.shouldPublish("b.mp4", 1, 100))
    }

    @Test
    fun offloadUsesUpToThreeStreams() {
        assertEquals(0, offloadWorkers(0))
        assertEquals(1, offloadWorkers(1))
        assertEquals(2, offloadWorkers(2))
        assertEquals(3, offloadWorkers(3))
        assertEquals(3, offloadWorkers(12))
    }

    @Test
    fun downloadWorkersClampsRequestedAndFileCount() {
        assertEquals(0, downloadWorkers(0, 2))
        assertEquals(1, downloadWorkers(1, 4))
        assertEquals(2, downloadWorkers(5, 2))
        assertEquals(4, downloadWorkers(5, 9))
    }

    @Test
    fun transferMeterComputesSpeedOnlyAfterSampleInterval() {
        var now = 1_000L
        val meter = TransferMeter(intervalMs = 800L) { now }
        assertEquals(0L, meter.sample(0))
        now += 1_000
        assertEquals(500_000L, meter.sample(500_000))
        now += 100
        assertEquals(500_000L, meter.sample(900_000))
        now += 900
        assertEquals(1_100_000L, meter.sample(1_600_000))
    }

    @Test
    fun transferMeterEtaNeedsSpeedAndRemainingBytes() {
        var now = 1_000L
        val meter = TransferMeter(intervalMs = 800L) { now }
        assertNull(meter.etaSeconds(0, 1_000))
        now += 1_000
        meter.sample(0)
        now += 1_000
        meter.sample(1_000)
        assertEquals(1L, meter.etaSeconds(1_000, 2_000))
        assertNull(meter.etaSeconds(1_000, null))
        assertNull(meter.etaSeconds(2_000, 1_000))
    }

    @Test
    fun stagedClipScannerParsesZ50TimestampAndDuration() {
        val dir = Files.createTempDirectory("z50-scanner").toFile()
        val clip = dir.resolve("20260915213739_0060.mp4").apply { writeText("v") }

        val scanned = StagedClipScanner.scan(dir).single()

        assertEquals("15 Sep 2026", scanned.dateFormatted)
        assertEquals("21:37", scanned.timeFormatted)
        assertEquals("1:00", scanned.durationFormatted)
        assertEquals(clip.length(), scanned.sizeBytes)
    }

    @Test
    fun stagedClipScannerDetectsRearClipsAndSidecars() {
        val dir = Files.createTempDirectory("z50-scanner-sidecars").toFile()
        dir.resolve("20260915213739_0060.mp4").writeText("v")
        dir.resolve("20260915213739_0060.gpx").writeText("gps")
        dir.resolve("20260915213739_0060.gsn").writeText("gsn")
        dir.resolve("20260915213739_0061_b.mp4").writeText("v")

        val scanned = StagedClipScanner.scan(dir)
        val front = scanned.first { it.filename.endsWith("_0060.mp4") }

        assertTrue(front.hasGps)
        assertTrue(front.hasGsn)
        assertFalse(front.isRear)
        assertTrue(scanned.first { it.filename.endsWith("_0061_b.mp4") }.isRear)
    }

    @Test
    fun stagedClipScannerIgnoresNonVideoFilesAndFallsBackForUnknownNames() {
        val dir = Files.createTempDirectory("z50-scanner-fallback").toFile()
        dir.resolve("synced_history.json").writeText("{}")
        dir.resolve("random-clip.mp4").writeText("v")

        val scanned = StagedClipScanner.scan(dir)

        assertEquals(1, scanned.size)
        assertEquals(StagedClipScanner.UNKNOWN_DURATION, scanned.single().durationFormatted)
    }
}

package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.car.SyncSignals
import io.github.johnkuruvila.z50bridge.car.carDashboard
import org.junit.Assert.*
import org.junit.Test

class CarDashboardTest {
    private val now = 1_000_000L
    private val signals = SyncSignals(
        nowMs = now, watching = true, syncPaused = false, cameraReachable = true,
        cameraSilent = false, syncStartedAtMs = now - 120_000, lastBytesAtMs = now,
        bytesPerSecond = 1024 * 1024, filesWaiting = 3, filesSynced = 55,
        failuresSinceBytes = 0, lastFailureAtMs = null, lastFailure = null,
        caughtUpAtMs = null, signalDbm = -33, transferFilesDone = 2,
    )
    private val progress = TransferProgress(
        fileIndex = 3, totalFiles = 8, overallCopied = 50 * 1024 * 1024,
        overallTotal = 100 * 1024 * 1024, bytesPerSecond = 1024 * 1024, etaSeconds = 50,
        activeFiles = listOf(ActiveFileTransfer("a.mp4"), ActiveFileTransfer("b.mp4"), ActiveFileTransfer("c.mp4")),
    )
    private val syncing = BridgeUiState(
        phase = BridgePhase.Syncing, watching = true, progress = progress,
        phoneFiles = 4, phoneBytes = 100 * 1024 * 1024,
    )

    @Test fun firstRowShowsActualSpeedAndFreshness() {
        val d = carDashboard(syncing, signals)
        assertEquals("Downloading · 1.0 MB/s", d.status)
        assertEquals("Last data just now · 50.0 MB copied", d.activity)
    }

    @Test fun countsUseDriveBaselineAndExcludeActiveFilesFromWaiting() {
        val d = carDashboard(syncing, signals, filesAtDriveStart = 10)
        assertEquals("45 files saved this drive · 3 active · 3 waiting", d.progress)
        assertEquals("4 files · 100.0 MB awaiting upload", d.queue)
    }

    @Test fun freshAgeRemainsVisibleBetweenProgressCallbacks() {
        assertTrue(carDashboard(syncing, signals.copy(nowMs = now + 5_000)).activity.startsWith("Last data 5s ago"))
    }

    @Test fun staleSpeedDisappearsAndWaitingIsExplicit() {
        val d = carDashboard(syncing, signals.copy(nowMs = now + 10_000))
        assertEquals("Waiting for data", d.status)
        assertTrue(d.activity.startsWith("No data for 10s"))
        assertFalse(d.status.contains("MB/s"))
    }

    @Test fun stallReportsActualElapsedTimeAndRecoversOnNewBytes() {
        val stalled = signals.copy(nowMs = now + 83_000)
        val d = carDashboard(syncing, stalled)
        assertEquals("Download stalled", d.status)
        assertTrue(d.activity.startsWith("No data for 1m 23s"))
        assertEquals("Downloading · 1.0 MB/s", carDashboard(syncing, stalled.copy(lastTransferBytesAtMs = stalled.nowMs)).status)
    }

    @Test fun uploadUsesItsOwnBytesAndShowsAcknowledgedCount() {
        val s = signals.copy(lastBytesAtMs = now - 600_000, lastTransferBytesAtMs = now)
        val d = carDashboard(syncing.copy(phase = BridgePhase.Offloading), s)
        assertEquals("Uploading · 1.0 MB/s", d.status)
        assertEquals("2 / 8 files uploaded · 3 active", d.progress)
    }

    @Test fun uploadStallsWithoutReusingOldDownloadActivity() {
        val d = carDashboard(syncing.copy(phase = BridgePhase.Offloading), signals.copy(lastTransferBytesAtMs = now - 60_000))
        assertEquals("Upload stalled", d.status)
    }

    @Test fun sentBytesAreNotReceiverAcknowledgements() {
        val d = carDashboard(syncing.copy(phase = BridgePhase.Offloading, progress = progress.copy(overallCopied = progress.overallTotal!!)), signals)
        assertTrue(d.progress.startsWith("2 / 8 files uploaded"))
    }

    @Test fun unknownTotalsDoNotInventPercentagesOrQueueCounts() {
        val d = carDashboard(syncing.copy(progress = progress.copy(totalFiles = 0, overallTotal = null)), signals)
        assertFalse(d.progress.contains("waiting"))
        assertFalse(d.status.contains("%"))
    }

    @Test fun noBytesYetIsConnectingAndThenWaiting() {
        val s = signals.copy(transferStartedAtMs = now, lastTransferBytesAtMs = null)
        assertEquals("Connecting and checking clips", carDashboard(syncing.copy(progress = null), s).status)
        assertEquals("Waiting for data", carDashboard(syncing.copy(progress = null), s.copy(nowMs = now + 12_000)).status)
    }

    @Test fun manualTransferIsVisibleWhenWatchingIsOff() {
        assertEquals("Downloading · 1.0 MB/s", carDashboard(syncing.copy(watching = false), signals).status)
    }

    @Test fun pauseHidesOldSpeedAndDoesNotBecomeAStall() {
        val d = carDashboard(syncing.copy(paused = true), signals.copy(nowMs = now + 600_000))
        assertEquals("Download paused", d.status)
        assertEquals("Waiting for Resume on the phone", d.activity)
    }

    @Test fun stoppedDownloadDoesNotSayPaused() {
        val d = carDashboard(BridgeUiState(watching = true, autoSyncPaused = true), signals)
        assertEquals("Download stopped", d.status)
    }

    @Test fun endOfTransferRemovesOldSpeedAndActiveFileCounts() {
        val d = carDashboard(syncing.copy(phase = BridgePhase.Watching), signals)
        assertEquals("Watching for new clips", d.status)
        assertEquals("55 files saved this drive", d.progress)
        assertEquals("Camera connected", d.activity)
    }

    @Test fun lostCameraIsReconnectingAfterFilesWereSaved() {
        val d = carDashboard(syncing.copy(phase = BridgePhase.Watching), signals.copy(cameraReachable = false))
        assertEquals("Reconnecting to camera", d.status)
    }

    @Test fun caughtUpIsOnlyShownWhileCameraIsReachable() {
        val state = syncing.copy(phase = BridgePhase.Watching)
        assertEquals("All caught up", carDashboard(state, signals.copy(caughtUpAtMs = now)).status)
        assertEquals("Reconnecting to camera", carDashboard(state, signals.copy(caughtUpAtMs = now, cameraReachable = false)).status)
    }

    @Test fun emptyUploadQueueDoesNotClaimTheCameraIsFullyCopied() {
        val d = carDashboard(BridgeUiState(watching = true), signals.copy(cameraReachable = false))
        assertEquals("No completed files awaiting upload", d.queue)
        assertFalse(d.status.contains("caught up"))
    }

    @Test fun openingLaterDoesNotResetTheDriveCount() {
        val first = carDashboard(syncing, signals, 10)
        val reopened = carDashboard(syncing, signals.copy(filesSynced = 60), 10)
        assertTrue(first.progress.startsWith("45 files"))
        assertTrue(reopened.progress.startsWith("50 files"))
    }
}

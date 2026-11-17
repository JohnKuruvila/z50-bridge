package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.car.CarAlertPolicy
import io.github.johnkuruvila.z50bridge.car.CarTone
import io.github.johnkuruvila.z50bridge.car.SyncSignals
import org.junit.Assert.*
import org.junit.Test

class CarAlertPolicyTest {
    private val start = 1_000_000L
    private fun signals(at: Long = start, bytes: Long? = at) = SyncSignals(
        nowMs = at, watching = true, syncPaused = false, cameraReachable = true,
        cameraSilent = false, syncStartedAtMs = start, lastBytesAtMs = bytes,
        bytesPerSecond = 3 * 1024 * 1024, filesWaiting = 3, filesSynced = 2,
        failuresSinceBytes = 0, lastFailureAtMs = null, lastFailure = null,
        caughtUpAtMs = null, signalDbm = -33,
    )
    private fun idle(at: Long = start) = signals(at).copy(
        phase = BridgePhase.Watching, syncStartedAtMs = null, transferStartedAtMs = null,
    )

    @Test fun startProgressAndRecoveryNeverPopUp() {
        val p = CarAlertPolicy(start)
        assertFalse(p.next(signals())!!.popUp)
        assertNull(p.next(signals(start + 30_000)))
        val stalled = p.next(signals(start + 60_000, bytes = start))!!
        assertTrue(stalled.popUp)
        assertEquals(CarTone.Problem, stalled.tone)
        val back = p.next(signals(start + 65_000))!!
        assertFalse(back.popUp)
        assertEquals(CarTone.Quiet, back.tone)
    }

    @Test fun intermittentBytesDoNotCreatePopupChatter() {
        val p = CarAlertPolicy(start)
        val popups = mutableListOf<Long>()
        for (second in 0L..600L step 5L) {
            // A stalled link gets one short recovery every 90 seconds.
            val latest = start + second / 90 * 90_000
            p.next(signals(start + second * 1000, bytes = latest))?.takeIf { it.popUp }?.let {
                popups += second
            }
        }
        assertTrue(popups.isNotEmpty())
        assertTrue(popups.zipWithNext().all { (a, b) -> b - a >= 300 })
        assertTrue(popups.size <= 2)
    }

    @Test fun aPersistentProblemOnlyPopsUpOnce() {
        val p = CarAlertPolicy(start)
        p.next(signals())
        assertTrue(p.next(signals(start + 60_000, bytes = start))!!.popUp)
        assertNull(p.next(signals(start + 600_000, bytes = start)))
    }

    @Test fun aDifferentPersistentProblemWaitsForTheSharedCooldown() {
        val p = CarAlertPolicy(start)
        p.next(signals())
        p.next(signals(start + 60_000, bytes = start))
        val silent = idle(start + 70_000).copy(cameraSilent = true)
        assertFalse(p.next(silent)!!.popUp)
        assertNull(p.next(silent.copy(nowMs = start + 120_000)))
        assertTrue(p.next(silent.copy(nowMs = start + 360_000))!!.popUp)
    }

    @Test fun shortCameraSilenceDoesNotInterrupt() {
        val p = CarAlertPolicy(start)
        assertFalse(p.next(idle().copy(cameraSilent = true))!!.popUp)
        assertFalse(p.next(idle(start + 20_000))!!.popUp)
    }

    @Test fun cameraMissingWaitsFiveMinutes() {
        val p = CarAlertPolicy(start)
        val missing = idle().copy(cameraReachable = false)
        assertFalse(p.next(missing)!!.popUp)
        assertNull(p.next(missing.copy(nowMs = start + 299_000)))
        assertTrue(p.next(missing.copy(nowMs = start + 300_000))!!.popUp)
    }

    @Test fun uploadDoesNotRaiseCameraMissingAlarm() {
        val p = CarAlertPolicy(start)
        val s = signals().copy(phase = BridgePhase.Offloading, cameraReachable = false)
        assertEquals("Uploading", p.next(s)!!.text)
        assertNull(p.next(s.copy(nowMs = start + 400_000, lastTransferBytesAtMs = start + 400_000)))
    }

    @Test fun uploadStallUsesUploadBytesRatherThanOldDownloadBytes() {
        val p = CarAlertPolicy(start)
        val s = signals(start + 60_000).copy(
            phase = BridgePhase.Offloading, lastTransferBytesAtMs = start,
        )
        assertEquals("Upload stalled — no data for 60 s", p.next(s)!!.text)
    }

    @Test fun manualTransferRemainsVisibleWhenWatchingIsOff() {
        assertEquals("Downloading", CarAlertPolicy(start).next(signals().copy(watching = false))!!.text)
    }

    @Test fun pauseAndStopAreDifferentAndBothQuiet() {
        val paused = CarAlertPolicy(start).next(signals().copy(paused = true))!!
        val stopped = CarAlertPolicy(start).next(idle().copy(syncPaused = true))!!
        assertEquals("Sync paused", paused.text)
        assertEquals("Sync stopped", stopped.text)
        assertFalse(paused.popUp)
        assertFalse(stopped.popUp)
    }

    @Test fun intentionallyPausedTransferNeverRaisesAStall() {
        val s = signals(start + 600_000, bytes = start).copy(paused = true)
        assertEquals("Sync paused", CarAlertPolicy(start).next(s)!!.text)
    }

    @Test fun resumeStartsANewWaitWithoutInventingByteActivity() {
        val s = signals(start + 600_000, bytes = start).copy(transferResumedAtMs = start + 600_000)
        val p = CarAlertPolicy(start)
        assertEquals("Connecting", p.next(s)!!.text)
        assertEquals("Waiting for data", p.next(s.copy(nowMs = start + 610_000))!!.text)
        assertEquals("Download stalled — no data for 60 s", p.next(s.copy(nowMs = start + 660_000))!!.text)
    }

    @Test fun endOfTransferAlwaysReplacesOldStatus() {
        val p = CarAlertPolicy(start)
        p.next(signals())
        assertEquals("Watching for new clips", p.next(idle(start + 1_000))!!.text)
    }

    @Test fun stoppingAndWatchOffAreQuiet() {
        assertFalse(CarAlertPolicy(start).next(signals().copy(stopping = true))!!.popUp)
        assertEquals("Watching is off", CarAlertPolicy(start).next(idle().copy(watching = false))!!.text)
    }

    @Test fun oldFailureIsIgnoredOnNewDrive() {
        val s = idle().copy(failuresSinceBytes = 2, lastFailureAtMs = start - 1, lastBytesAtMs = null)
        assertEquals("Watching for new clips", CarAlertPolicy(start).next(s)!!.text)
    }

    @Test fun aSuccessfulEmptyCheckClearsAnOlderFailureWithoutARecoveryPopup() {
        val p = CarAlertPolicy(start)
        val failed = idle().copy(failuresSinceBytes = 2, lastFailureAtMs = start, lastBytesAtMs = null)
        p.next(failed)
        assertTrue(p.next(failed.copy(nowMs = start + 30_000))!!.popUp)
        val recovered = failed.copy(nowMs = start + 31_000, caughtUpAtMs = start + 31_000)
        val alert = p.next(recovered)
        assertNotNull("An empty successful check must clear the old problem", alert)
        assertEquals("All caught up", alert!!.text)
        assertFalse(alert.popUp)
    }

    @Test fun aFailureAfterTheLastEmptyCheckDoesNotStillClaimCaughtUp() {
        val p = CarAlertPolicy(start)
        val s = idle(start + 10_000).copy(
            failuresSinceBytes = 1, lastFailureAtMs = start + 10_000,
            lastBytesAtMs = null, caughtUpAtMs = start,
        )
        assertEquals("Watching for new clips", p.next(s)!!.text)
        val failedAgain = s.copy(nowMs = start + 20_000, failuresSinceBytes = 2, lastFailureAtMs = start + 20_000)
        assertEquals("Download interrupted — check Z50 Bridge", p.next(failedAgain)!!.text)
        assertTrue(p.next(failedAgain.copy(nowMs = start + 50_000))!!.popUp)
    }
}

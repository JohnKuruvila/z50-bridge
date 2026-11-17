package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.car.CarAlertPolicy
import io.github.johnkuruvila.z50bridge.car.SyncSignals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarAlertRegressionTest {
    private val start = 1_000_000L
    private fun signal(at: Long, bytes: Long? = at, paused: Boolean = false) = SyncSignals(
        nowMs = at, watching = true, syncPaused = paused, cameraReachable = true,
        cameraSilent = false, syncStartedAtMs = start, lastBytesAtMs = bytes,
        bytesPerSecond = 1024 * 1024, filesWaiting = 3, filesSynced = 1,
        failuresSinceBytes = 0, lastFailureAtMs = null, lastFailure = null,
        caughtUpAtMs = null, signalDbm = null,
    )

    @Test fun recoveryDoesNotInterruptTheDriverAgain() {
        val policy = CarAlertPolicy(start)
        policy.next(signal(start))
        assertTrue(policy.next(signal(start + 60_000, bytes = start))!!.popUp)
        assertFalse(policy.next(signal(start + 65_000))!!.popUp)
    }

    @Test fun aFinishedTransferReplacesTheOldSyncingMessage() {
        val policy = CarAlertPolicy(start)
        policy.next(signal(start))
        val idle = policy.next(signal(start + 5_000).copy(syncStartedAtMs = null, transferStartedAtMs = null, phase = BridgePhase.Watching))
        assertNotNull("The car must leave the old Syncing state", idle)
        assertFalse(idle!!.text.startsWith("Syncing"))
    }

    @Test fun aStoppedSyncDoesNotClaimToBePaused() {
        val alert = CarAlertPolicy(start).next(signal(start, paused = true).copy(phase = BridgePhase.Watching, transferStartedAtMs = null))!!
        assertTrue(alert.text.contains("stopped", ignoreCase = true))
        assertFalse(alert.popUp)
    }
}

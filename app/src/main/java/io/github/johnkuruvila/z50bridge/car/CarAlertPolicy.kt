package io.github.johnkuruvila.z50bridge.car

import io.github.johnkuruvila.z50bridge.BridgePhase

/** One controller snapshot, including actual byte activity in either direction. */
data class SyncSignals(
    val nowMs: Long,
    val watching: Boolean,
    /** Stop suppresses automatic sync; Pause is a separate state. */
    val syncPaused: Boolean,
    val cameraReachable: Boolean,
    val cameraSilent: Boolean,
    val syncStartedAtMs: Long?,
    val lastBytesAtMs: Long?,
    val bytesPerSecond: Long,
    val filesWaiting: Int?,
    /** All saved files, including sidecars, since process start. */
    val filesSynced: Int,
    val failuresSinceBytes: Int,
    val lastFailureAtMs: Long?,
    val lastFailure: String?,
    val caughtUpAtMs: Long?,
    val signalDbm: Int?,
    val phase: BridgePhase = if (syncStartedAtMs != null) BridgePhase.Syncing else BridgePhase.Watching,
    val paused: Boolean = false,
    val stopping: Boolean = false,
    val offloadStopped: Boolean = false,
    val transferStartedAtMs: Long? = syncStartedAtMs,
    val transferResumedAtMs: Long? = null,
    val lastTransferBytesAtMs: Long? = lastBytesAtMs,
    val transferFilesDone: Int = 0,
)

enum class CarTone { Problem, Quiet }
data class CarAlert(val text: String, val tone: CarTone, val popUp: Boolean)

internal enum class CarStatus(val problem: Boolean = false) {
    Waiting, NotWatching, Paused, Stopped, UploadStopped, Stopping,
    Connecting, Syncing, Uploading, WaitingForData, Watching, CaughtUp,
    Stalled(true), CameraSilent(true), CameraMissing(true), Failing(true),
}

internal val SyncSignals.transferring: Boolean
    get() = phase == BridgePhase.Syncing || phase == BridgePhase.Offloading

internal fun SyncSignals.transferAgeMs(): Long? {
    val started = transferStartedAtMs ?: return null
    val since = maxOf(started, transferResumedAtMs ?: started, lastTransferBytesAtMs ?: started)
    return (nowMs - since).coerceAtLeast(0)
}

internal fun SyncSignals.freshTransferBytes(): Boolean {
    val started = transferStartedAtMs ?: return false
    val last = lastTransferBytesAtMs ?: return false
    return last >= maxOf(started, transferResumedAtMs ?: started) && nowMs - last < FRESH_BYTES_MS
}

/** Shared by the dashboard and alerts, so both describe the same state. */
internal fun carStatus(s: SyncSignals): CarStatus = with(s) {
    when {
        stopping -> CarStatus.Stopping
        transferring && paused -> CarStatus.Paused
        syncPaused && !transferring -> CarStatus.Stopped
        offloadStopped && !transferring -> CarStatus.UploadStopped
        transferring -> {
            val age = transferAgeMs() ?: 0
            when {
                age >= CarAlertPolicy.STALL_MS -> CarStatus.Stalled
                age >= FRESH_BYTES_MS -> CarStatus.WaitingForData
                !freshTransferBytes() -> CarStatus.Connecting
                phase == BridgePhase.Offloading -> CarStatus.Uploading
                else -> CarStatus.Syncing
            }
        }
        !watching -> CarStatus.NotWatching
        cameraSilent -> CarStatus.CameraSilent
        !cameraReachable -> CarStatus.Waiting
        failuresSinceBytes >= CarAlertPolicy.FAILURES &&
            (lastFailureAtMs ?: 0) > maxOf(lastBytesAtMs ?: 0, caughtUpAtMs ?: 0) -> CarStatus.Failing
        caughtUpAtMs != null && caughtUpAtMs >= maxOf(lastBytesAtMs ?: 0, lastFailureAtMs ?: 0) -> CarStatus.CaughtUp
        else -> CarStatus.Watching
    }
}

internal const val FRESH_BYTES_MS = 10_000L

/** Routine transitions clear alerts. Only sustained problems become messages. */
class CarAlertPolicy(private val driveStartMs: Long) {
    private var shown: CarStatus? = null
    private var lastProblemPopUpMs: Long? = null
    private var pendingSinceMs: Long? = null
    private var pendingStatus: CarStatus? = null
    private var announcedProblem: CarStatus? = null
    private var unreachableSinceMs: Long? = null
    private var cameraSeen = false

    fun next(s: SyncSignals): CarAlert? {
        if (s.cameraReachable) cameraSeen = true
        var status = carStatus(s)
        if (status == CarStatus.Waiting) {
            val since = unreachableSinceMs ?: s.nowMs.also { unreachableSinceMs = it }
            if (s.nowMs - since >= CAMERA_MISSING_MS) status = CarStatus.CameraMissing
        } else {
            unreachableSinceMs = null
        }
        if (status == CarStatus.Failing && (s.lastFailureAtMs ?: 0) < driveStartMs) status = CarStatus.Watching
        if (status != pendingStatus) {
            pendingStatus = status
            pendingSinceMs = s.nowMs
            announcedProblem = null
        }
        if (!status.problem) {
            val changed = shown != status
            shown = status
            return if (changed) CarAlert(statusText(status, s), CarTone.Quiet, popUp = false) else null
        }
        val hold = if (status == CarStatus.CameraSilent || status == CarStatus.Failing) PROBLEM_HOLD_MS else 0L
        val mature = s.nowMs - (pendingSinceMs ?: s.nowMs) >= hold
        val gapPassed = lastProblemPopUpMs?.let { s.nowMs - it >= POPUP_GAP_MS } ?: true
        if (mature && gapPassed && announcedProblem != status) {
            lastProblemPopUpMs = s.nowMs
            announcedProblem = status
            shown = status
            return CarAlert(statusText(status, s), CarTone.Problem, popUp = true)
        }
        val changed = shown != status
        shown = status
        return if (changed) CarAlert(statusText(status, s), CarTone.Quiet, popUp = false) else null
    }

    private fun statusText(status: CarStatus, s: SyncSignals): String = when (status) {
        CarStatus.Stalled -> if (s.phase == BridgePhase.Offloading) "Upload stalled — no data for 60 s" else "Download stalled — no data for 60 s"
        CarStatus.CameraSilent -> "Camera Wi-Fi connected, but the camera is not answering"
        CarStatus.CameraMissing -> if (cameraSeen) "Camera disconnected for 5 min" else "Camera not connected after 5 min"
        CarStatus.Failing -> "Download interrupted — check Z50 Bridge"
        CarStatus.Stopped -> "Sync stopped"
        CarStatus.UploadStopped -> "Upload stopped"
        CarStatus.Paused -> if (s.phase == BridgePhase.Offloading) "Upload paused" else "Sync paused"
        CarStatus.Stopping -> "Stopping transfer"
        CarStatus.Connecting -> "Connecting"
        CarStatus.Syncing -> "Downloading"
        CarStatus.Uploading -> "Uploading"
        CarStatus.WaitingForData -> "Waiting for data"
        CarStatus.NotWatching -> "Watching is off"
        CarStatus.Waiting -> "Waiting for camera"
        CarStatus.Watching -> "Watching for new clips"
        CarStatus.CaughtUp -> "All caught up"
    }

    companion object {
        const val STALL_MS = 60_000L
        const val CAMERA_MISSING_MS = 5 * 60_000L
        const val POPUP_GAP_MS = 5 * 60_000L
        const val PROBLEM_HOLD_MS = 30_000L
        const val FAILURES = 2
    }
}

package io.github.johnkuruvila.z50bridge.car

import io.github.johnkuruvila.z50bridge.BridgePhase
import io.github.johnkuruvila.z50bridge.BridgeUiState
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import io.github.johnkuruvila.z50bridge.ui.common.formatEta
import io.github.johnkuruvila.z50bridge.ui.common.formatSpeed

/** Two fixed rows keep state, data freshness and the queue above the car's fold. */
internal data class CarDashboard(
    val status: String,
    val activity: String,
    val progress: String,
    val queue: String,
)

internal fun carDashboard(state: BridgeUiState, signals: SyncSignals, filesAtDriveStart: Int = 0): CarDashboard {
    val s = signals.copy(
        phase = state.phase, watching = state.watching, paused = state.paused,
        stopping = state.stopping, syncPaused = state.autoSyncPaused,
        offloadStopped = state.autoOffloadPaused,
    )
    val status = carStatus(s)
    val upload = s.phase == BridgePhase.Offloading
    val transfer = state.progress?.takeIf { s.transferring }
    val fresh = s.freshTransferBytes()
    val label = when (status) {
        CarStatus.Stopping -> "Stopping transfer"
        CarStatus.Paused -> if (upload) "Upload paused" else "Download paused"
        CarStatus.Stopped -> "Download stopped"
        CarStatus.UploadStopped -> "Upload stopped"
        CarStatus.Stalled -> if (upload) "Upload stalled" else "Download stalled"
        CarStatus.WaitingForData -> if (!upload && !s.cameraReachable) "Reconnecting to camera" else "Waiting for data"
        CarStatus.Connecting -> if (upload) "Connecting to receiver" else "Connecting and checking clips"
        CarStatus.Syncing -> "Downloading"
        CarStatus.Uploading -> "Uploading"
        CarStatus.NotWatching -> "Watching is off"
        CarStatus.CameraSilent -> "Camera is not answering"
        CarStatus.Failing -> "Download interrupted"
        CarStatus.Waiting, CarStatus.CameraMissing ->
            if (s.filesSynced > filesAtDriveStart) "Reconnecting to camera" else "Waiting for camera"
        CarStatus.CaughtUp -> "All caught up"
        CarStatus.Watching -> "Watching for new clips"
    }
    val speed = transfer?.bytesPerSecond?.takeIf {
        fresh && (status == CarStatus.Syncing || status == CarStatus.Uploading) && it > 0
    }?.let(::formatSpeed)
    val headline = listOfNotNull(label, speed).joinToString(" · ")
    val activity = when {
        status == CarStatus.Paused -> "Waiting for Resume on the phone"
        status == CarStatus.Stopped -> "Automatic download stopped for this connection"
        status == CarStatus.UploadStopped -> "Files stay on the phone until the next upload"
        status == CarStatus.Stopping -> "Keeping unfinished files for retry"
        s.transferring -> {
            val age = s.transferAgeMs()
            val last = s.lastTransferBytesAtMs
            val hasBytes = last != null && last >= (s.transferStartedAtMs ?: Long.MAX_VALUE)
            val freshness = when {
                age == null -> "Waiting for transfer activity"
                !fresh -> "No data for ${if (age < 1_000) "0s" else formatEta(age / 1000)}"
                hasBytes && age < 1_000 -> "Last data just now"
                else -> "Last data ${formatEta(age / 1000)} ago"
            }
            val amount = transfer?.overallCopied?.takeIf { it > 0 }?.let { "${formatBytes(it)} copied" }
            listOfNotNull(freshness, amount).joinToString(" · ")
        }
        status == CarStatus.Failing -> s.lastFailure?.substringAfter(": ")?.take(70) ?: "Will retry while watching is on"
        s.cameraSilent -> "Camera Wi-Fi connected · waiting for a response"
        s.cameraReachable -> "Camera connected"
        else -> "Camera disconnected"
    }
    val saved = (s.filesSynced - filesAtDriveStart).coerceAtLeast(0)
    val active = transfer?.activeFiles?.size ?: 0
    val waiting = transfer?.totalFiles?.takeIf { it > 0 }?.let {
        (it - s.transferFilesDone - active).coerceAtLeast(0)
    }
    val progress = if (s.transferring && upload) {
        val total = transfer?.totalFiles?.takeIf { it > 0 }
        listOfNotNull(
            if (total == null) "${s.transferFilesDone} files uploaded" else "${s.transferFilesDone} / $total files uploaded",
            active.takeIf { it > 0 }?.let { "$it active" },
        ).joinToString(" · ")
    } else {
        listOfNotNull(
            "$saved files saved this drive",
            active.takeIf { s.transferring && it > 0 }?.let { "$it active" },
            waiting?.takeIf { s.transferring }?.let { "$it waiting" },
        ).joinToString(" · ")
    }
    val queue = if (state.phoneFiles > 0) {
        "${state.phoneFiles} files · ${formatBytes(state.phoneBytes)} awaiting upload"
    } else {
        "No completed files awaiting upload"
    }
    return CarDashboard(headline, activity, progress, queue)
}

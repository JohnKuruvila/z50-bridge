package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.camera.CameraStatus
import io.github.johnkuruvila.z50bridge.navigation.TopDestination
import io.github.johnkuruvila.z50bridge.net.CameraWifiStatus
import java.io.File

enum class ActivityKind {
    Sync,
    Offload,
    System,
    Error,
}

data class ActivityItem(
    val timeMs: Long,
    val text: String,
    val error: Boolean = false,
    val kind: ActivityKind = if (error) ActivityKind.Error else ActivityKind.System,
)

data class ActiveFileTransfer(
    val filename: String = "",
    val fileIndex: Int = 0,
    val copied: Long = 0,
    val total: Long? = null,
)

data class TransferProgress(
    val filename: String = "",
    val fileIndex: Int = 0,
    val totalFiles: Int = 0,
    val copied: Long = 0,
    val total: Long? = null,
    val overallCopied: Long = 0,
    val overallTotal: Long? = null,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
    val isOffload: Boolean = false,
    val activeFiles: List<ActiveFileTransfer> = emptyList(),
)

data class StagedClip(
    val file: File,
    val filename: String,
    val sizeBytes: Long,
    val dateFormatted: String,
    val timeFormatted: String,
    val isRear: Boolean = false,
    val hasGps: Boolean = false,
    val hasGsn: Boolean = false,
    val durationFormatted: String = "",
    val thumbFile: File? = null,
)

/** Files moved to the staging trash so a delete can be undone from a snackbar. */
data class TrashToken(
    val names: List<String>,
    val files: List<File>,
)

data class CompletedTransfer(
    val synced: Boolean,
    val files: Int,
    val timeMs: Long = System.currentTimeMillis(),
)

enum class BridgePhase {
    Idle,
    Watching,
    Syncing,
    Offloading,
}

data class BridgeUiState(
    val phase: BridgePhase = BridgePhase.Idle,
    val watching: Boolean = false,
    val cameraReachable: Boolean = false,
    val serverReachable: Boolean = false,
    val cameraLabel: String = "Waiting for camera",
    val cameraDetail: String = "Not on Z50 Wi-Fi",
    val cameraWifiStatus: CameraWifiStatus = CameraWifiStatus.Off,
    /** Last status read from the camera; may be stale when [cameraReachable] is false. */
    val cameraStatus: CameraStatus? = null,
    val offloadAllowlist: Set<String> = emptySet(),
    val phoneBytes: Long = 0,
    val phoneFiles: Int = 0,
    val phoneFreeBytes: Long = 0,
    val lastOffload: String = "never",
    val lastSync: String = "never",
    val lastCompleted: CompletedTransfer? = null,
    val progress: TransferProgress? = null,
    val activity: List<ActivityItem> = emptyList(),
    val stagedClips: List<StagedClip> = emptyList(),
    val destination: TopDestination = TopDestination.Home,
    val showSetup: Boolean = false,
    /** Stop was pressed during a sync; the watcher leaves this camera connection alone. */
    val autoSyncPaused: Boolean = false,
    /** Stop was pressed during an offload; the watcher waits until the phone leaves this network. */
    val autoOffloadPaused: Boolean = false,
    /** Stop was pressed and the transfer is unwinding (blocking requests can take a few seconds). */
    val stopping: Boolean = false,
)

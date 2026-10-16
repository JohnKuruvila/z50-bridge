package io.github.johnkuruvila.z50bridge.data

import io.github.johnkuruvila.z50bridge.BridgeUiState
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** App and device facts that head a diagnostics report. */
data class DeviceInfo(
    val appVersion: String,
    val device: String,
    val android: String,
)

/**
 * Plain-text report for troubleshooting: app and device, the live bridge
 * state, settings, then the full activity log. Secrets (receiver token,
 * camera Wi-Fi password) are only reported as set or not set, and offload
 * Wi-Fi names are reduced to a count. [transfers] are the stored per-sync and
 * speed-test reports.
 */
fun buildDiagnosticsReport(
    device: DeviceInfo,
    state: BridgeUiState,
    settings: BridgeSettings,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    transfers: List<String> = emptyList(),
): String {
    val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(zone)
    fun stamp(ms: Long) = time.format(Instant.ofEpochMilli(ms))
    fun yesNo(value: Boolean) = if (value) "yes" else "no"
    fun secret(value: String) = if (value.isEmpty()) "not set" else "set"

    return buildString {
        appendLine("Z50 Bridge diagnostics")
        appendLine("Generated: ${stamp(nowMs)} ($zone)")
        appendLine("App: ${device.appVersion}")
        appendLine("Device: ${device.device}")
        appendLine("Android: ${device.android}")

        appendLine()
        appendLine("[State]")
        appendLine("Phase: ${state.phase}")
        appendLine("Watching: ${yesNo(state.watching)}")
        if (state.autoSyncPaused) appendLine("Automatic sync: paused by Stop until the camera reconnects")
        if (state.autoOffloadPaused) appendLine("Automatic offload: paused by Stop until the phone leaves this network")
        appendLine("Camera reachable: ${yesNo(state.cameraReachable)} (${state.cameraLabel}; ${state.cameraDetail})")
        appendLine("Camera Wi-Fi: ${state.cameraWifiStatus}")
        state.cameraStatus?.let { cam ->
            fun known(value: Any?) = value?.toString() ?: "unknown"
            appendLine("Camera model: ${known(cam.model)}, firmware ${known(cam.firmware)}")
            appendLine("Camera recording: ${known(cam.recording)}, GPS fix: ${known(cam.gpsFix)}")
            appendLine(
                "Camera SD: ${cam.sdFreeBytes?.let(::formatBytes) ?: "unknown"} free of " +
                    (cam.sdTotalBytes?.let(::formatBytes) ?: "unknown"),
            )
            appendLine("Camera clock offset: ${cam.clockOffsetSeconds?.let { "$it s" } ?: "unknown"}")
            appendLine("Camera status read: ${stamp(cam.fetchedAtMs)}")
        }
        appendLine("Homelab reachable: ${yesNo(state.serverReachable)}")
        appendLine("Staged: ${state.phoneFiles} files, ${formatBytes(state.phoneBytes)}")
        appendLine("Phone free space: ${formatBytes(state.phoneFreeBytes)}")
        appendLine("Last sync: ${state.lastSync}")
        appendLine("Last offload: ${state.lastOffload}")
        state.progress?.let { p ->
            val total = p.overallTotal?.let(::formatBytes) ?: "unknown"
            appendLine(
                "Transfer: ${if (p.isOffload) "offload" else "sync"} file ${p.fileIndex}/${p.totalFiles}, " +
                    "${formatBytes(p.overallCopied)} of $total",
            )
        }

        appendLine()
        appendLine("[Settings]")
        appendLine("Camera host: ${settings.cameraHost}")
        appendLine("Camera Wi-Fi auto-connect: ${yesNo(settings.cameraWifiAutoConnect)}")
        appendLine("Camera SSID: ${settings.cameraSsid.ifEmpty { "not set" }}")
        appendLine("Camera Wi-Fi password: ${secret(settings.cameraWifiPassword)}")
        appendLine("Sync camera clock: ${yesNo(settings.syncCameraClock)}")
        appendLine("Receiver: ${settings.destHost}:${settings.destPort}")
        appendLine("Receiver token: ${secret(settings.destToken)}")
        appendLine("Offload Wi-Fi allowlist: ${settings.offloadWifiAllowlist.size} networks")
        appendLine("Pull rear: ${yesNo(settings.pullRear)}, GPS: ${yesNo(settings.pullGps)}")
        appendLine("Skip open clip: ${yesNo(settings.skipOpenClip)}, newest first: ${yesNo(settings.prioritizeLatest)}")
        appendLine("Sync window: ${if (settings.syncWindowMinutes > 0) "newest ${settings.syncWindowMinutes} min" else "all clips"}")
        appendLine("Poll: ${settings.pollSeconds}s, auto-watch: ${yesNo(settings.autoWatch)}")
        appendLine("Streams: ${settings.parallelDownloads} down, ${settings.parallelUploads} up")

        appendLine()
        appendLine("[Transfer diagnostics] ${transfers.size} recent syncs and speed tests, newest first")
        if (transfers.isEmpty()) appendLine("(none yet)")
        transfers.forEach { report ->
            append(report)
            if (!report.endsWith("\n")) appendLine()
            appendLine()
        }

        appendLine()
        appendLine("[Activity] ${state.activity.size} entries, newest first")
        if (state.activity.isEmpty()) appendLine("(empty)")
        state.activity.forEach { item ->
            appendLine("${stamp(item.timeMs)} [${item.kind}] ${item.text}")
        }
    }
}

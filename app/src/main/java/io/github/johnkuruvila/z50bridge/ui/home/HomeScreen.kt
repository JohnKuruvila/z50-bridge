package io.github.johnkuruvila.z50bridge.ui.home

import io.github.johnkuruvila.z50bridge.data.formatTransferStamp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.johnkuruvila.z50bridge.net.hasBackgroundLocation
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.johnkuruvila.z50bridge.ActiveFileTransfer
import io.github.johnkuruvila.z50bridge.ActivityItem
import io.github.johnkuruvila.z50bridge.ActivityKind
import io.github.johnkuruvila.z50bridge.BridgePhase
import io.github.johnkuruvila.z50bridge.BridgeUiState
import io.github.johnkuruvila.z50bridge.CameraResult
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.StagedClip
import io.github.johnkuruvila.z50bridge.TransferProgress
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import io.github.johnkuruvila.z50bridge.ui.common.formatEta
import io.github.johnkuruvila.z50bridge.ui.common.formatRelativeTime
import io.github.johnkuruvila.z50bridge.ui.common.splitSpeed
import io.github.johnkuruvila.z50bridge.ui.components.GroupItem
import io.github.johnkuruvila.z50bridge.ui.components.ListGroup
import io.github.johnkuruvila.z50bridge.ui.components.PipelineDiagram
import io.github.johnkuruvila.z50bridge.ui.components.SectionHeader
import io.github.johnkuruvila.z50bridge.ui.components.Sparkline
import io.github.johnkuruvila.z50bridge.ui.components.Tag
import io.github.johnkuruvila.z50bridge.ui.components.UsageBar
import io.github.johnkuruvila.z50bridge.ui.components.Z50TopBar
import io.github.johnkuruvila.z50bridge.ui.theme.Numeric
import io.github.johnkuruvila.z50bridge.ui.theme.signals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DiagnosticTarget { Camera, Homelab }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: BridgeUiState,
    needsSetup: Boolean,
    onWatch: (Boolean) -> Unit,
    onSync: () -> Unit,
    onOffload: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenClips: () -> Unit,
    onOpenClip: (StagedClip) -> Unit,
    onOpenActivity: () -> Unit,
    onOpenLiveView: () -> Unit,
    onOpenDashcam: () -> Unit,
    onSetRecording: suspend (Boolean) -> CameraResult<Boolean>,
    /** Null until settings have loaded, so the notice does not flash at start. */
    cameraWifiConfigured: Boolean? = true,
    autoConnectOn: Boolean = false,
    onOpenCameraSettings: () -> Unit = {},
    onLocationGranted: () -> Unit = {},
) {
    var refreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var backgroundLocation by remember { mutableStateOf(hasBackgroundLocation(context)) }
    // Granted or revoked in Android's settings while Home was in the background.
    LifecycleResumeEffect(Unit) {
        backgroundLocation = hasBackgroundLocation(context)
        onPauseOrDispose {}
    }
    // Android 11+ answers the background request with its own settings page,
    // and only after the foreground grant; once refused, open the app's page.
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        backgroundLocation = granted
        if (granted) {
            onLocationGranted()
        } else {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
    }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onLocationGranted()
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    var diagnosticsFor by remember { mutableStateOf<DiagnosticTarget?>(null) }
    val scope = rememberCoroutineScope()
    val throughput = remember { mutableStateListOf<Long>() }
    val transferring = state.phase == BridgePhase.Syncing || state.phase == BridgePhase.Offloading
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(state.progress?.bytesPerSecond) {
        val sample = state.progress?.bytesPerSecond ?: return@LaunchedEffect
        if (throughput.size >= 60) throughput.removeAt(0)
        throughput.add(sample)
    }
    LaunchedEffect(transferring) {
        if (!transferring) throughput.clear()
    }
    HapticOnNewEvent(state.lastCompleted?.timeMs, HapticFeedbackType.Confirm)
    HapticOnNewEvent(
        state.activity.firstOrNull { it.error || it.kind == ActivityKind.Error }?.timeMs,
        HapticFeedbackType.Reject,
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Z50TopBar(
                title = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.home_refresh_cd))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                onRefresh()
                scope.launch {
                    delay(900)
                    refreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "hero") {
                    PipelineCard(
                        state = state,
                        throughput = throughput,
                        onWatch = onWatch,
                        onDiagnostics = { diagnosticsFor = it },
                    )
                }
                item(key = "actions") {
                    ActionRow(
                        state = state,
                        transferring = transferring,
                        onSync = onSync,
                        onOffload = onOffload,
                        onStop = onStop,
                    )
                }
                when {
                    needsSetup -> item(key = "setup") {
                        Notice(
                            icon = Icons.Outlined.Dns,
                            title = stringResource(R.string.home_setup_required),
                            body = stringResource(R.string.home_setup_body),
                            action = stringResource(R.string.home_setup_action),
                            onClick = onOpenSetup,
                        )
                    }
                    cameraWifiConfigured == false -> item(key = "camera-wifi") {
                        Notice(
                            icon = Icons.Outlined.Wifi,
                            title = stringResource(R.string.home_camera_wifi_title),
                            body = stringResource(R.string.home_camera_wifi_body),
                            action = stringResource(R.string.home_setup_action),
                            onClick = onOpenCameraSettings,
                        )
                    }
                    autoConnectOn && !backgroundLocation && Build.VERSION.SDK_INT >= 29 -> item(key = "location") {
                        Notice(
                            icon = Icons.Outlined.LocationOn,
                            title = stringResource(R.string.home_location_title),
                            body = stringResource(R.string.home_location_body),
                            action = stringResource(R.string.home_location_action),
                            onClick = {
                                if (hasFineLocation(context)) {
                                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                } else {
                                    fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                        )
                    }
                }
                if (state.cameraReachable) {
                    item(key = "camera") {
                        CameraCard(
                            status = state.cameraStatus,
                            onSetRecording = onSetRecording,
                            onOpenLiveView = onOpenLiveView,
                            onOpenDashcam = onOpenDashcam,
                        )
                    }
                }
                if (state.stagedClips.isNotEmpty()) {
                    item(key = "clips") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionHeader(
                                title = stringResource(R.string.home_recent_clips),
                                action = stringResource(R.string.home_recent_see_all),
                                onAction = onOpenClips,
                            )
                            RecentClipsRow(clips = state.stagedClips, onOpenClip = onOpenClip)
                        }
                    }
                }
                item(key = "storage") {
                    StorageCard(state = state, onClick = onOpenClips)
                }
                item(key = "activity") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(
                            title = stringResource(R.string.home_recent_title),
                            action = stringResource(R.string.home_recent_see_all).takeIf { state.activity.isNotEmpty() },
                            onAction = onOpenActivity,
                        )
                        RecentActivity(items = state.activity, onOpenActivity = onOpenActivity)
                    }
                }
            }
        }
    }

    diagnosticsFor?.let { target ->
        DiagnosticsSheet(
            target = target,
            state = state,
            onDismiss = { diagnosticsFor = null },
            onRecheck = {
                onRefresh()
                diagnosticsFor = null
            },
        )
    }
}

// ---------------------------------------------------------------- hero

@Composable
private fun PipelineCard(
    state: BridgeUiState,
    throughput: List<Long>,
    onWatch: (Boolean) -> Unit,
    onDiagnostics: (DiagnosticTarget) -> Unit,
) {
    val syncing = state.phase == BridgePhase.Syncing
    val offloading = state.phase == BridgePhase.Offloading
    val offline = !state.cameraReachable && !state.serverReachable
    val stagedLabel = pluralStringResource(R.plurals.pipeline_staged, state.phoneFiles, state.phoneFiles)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PipelineDiagram(
                cameraOnline = state.cameraReachable,
                homelabOnline = state.serverReachable,
                syncing = syncing,
                offloading = offloading,
                stagedLabel = stagedLabel,
                onCameraClick = { onDiagnostics(DiagnosticTarget.Camera) },
                onHomelabClick = { onDiagnostics(DiagnosticTarget.Homelab) },
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimatedContent(
                    targetState = heroHeadline(state, offline),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "headline",
                ) { headline ->
                    Text(text = headline, style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    text = heroDetail(state, offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = state.progress != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                state.progress?.let { progress ->
                    TransferPanel(
                        progress = progress,
                        throughput = throughput,
                        color = if (progress.isOffload) MaterialTheme.signals.offload else MaterialTheme.signals.sync,
                    )
                }
            }

            // The one switch that decides whether any of this happens on its
            // own. Hidden mid-transfer so Stop stays on screen.
            if (!syncing && !offloading) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.home_watch_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (state.watching) R.string.home_watch_on else R.string.home_watch_off),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = state.watching, onCheckedChange = onWatch)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransferPanel(progress: TransferProgress, throughput: List<Long>, color: Color) {
    val overallTotal = progress.overallTotal ?: progress.total
    val fraction = if (overallTotal != null && overallTotal > 0) {
        (progress.overallCopied.toFloat() / overallTotal.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animated by animateFloatAsState(fraction, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "progress")
    val (speedValue, speedUnit) = splitSpeed(progress.bytesPerSecond)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(speedValue, style = Numeric.hero, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                speedUnit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 7.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    text = stringResource(R.string.home_eta_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(formatEta(progress.etaSeconds), style = Numeric.large)
            }
        }

        LinearWavyProgressIndicator(
            progress = { animated },
            color = color,
            trackColor = color.copy(alpha = 0.18f),
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    R.string.home_bytes_of,
                    formatBytes(progress.overallCopied),
                    overallTotal?.let { formatBytes(it) } ?: "—",
                ),
                style = Numeric.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (progress.totalFiles > 0) {
                Text(
                    text = stringResource(R.string.home_files_of, progress.fileIndex, progress.totalFiles),
                    style = Numeric.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (throughput.size >= 2) {
            Sparkline(
                samples = throughput,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            )
        }

        if (progress.activeFiles.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                progress.activeFiles.take(4).forEach { StreamRow(it, color) }
                if (progress.activeFiles.size > 4) {
                    val more = progress.activeFiles.size - 4
                    Text(
                        text = pluralStringResource(R.plurals.home_more_streams, more, more),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamRow(file: ActiveFileTransfer, color: Color) {
    val fraction = if (file.total != null && file.total > 0) {
        (file.copied.toFloat() / file.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = file.filename,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${(fraction * 100).toInt()}%", style = Numeric.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UsageBar(fraction = fraction, color = color, modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun heroHeadline(state: BridgeUiState, offline: Boolean): String {
    val progress = state.progress
    return when {
        state.stopping -> stringResource(R.string.home_headline_stopping)
        state.phase == BridgePhase.Syncing && progress != null && progress.totalFiles > 0 ->
            pluralStringResource(R.plurals.home_headline_syncing_n, progress.totalFiles, progress.totalFiles)
        state.phase == BridgePhase.Offloading && progress != null && progress.totalFiles > 0 ->
            pluralStringResource(R.plurals.home_headline_offloading_n, progress.totalFiles, progress.totalFiles)
        state.phase == BridgePhase.Syncing -> stringResource(R.string.home_headline_syncing)
        state.phase == BridgePhase.Offloading -> stringResource(R.string.home_headline_offloading)
        state.cameraReachable -> stringResource(R.string.home_headline_camera_ready)
        state.phoneFiles > 0 && state.serverReachable ->
            pluralStringResource(R.plurals.home_headline_ready, state.phoneFiles, state.phoneFiles)
        state.phoneFiles > 0 ->
            pluralStringResource(R.plurals.home_headline_waiting_home, state.phoneFiles, state.phoneFiles)
        offline && state.watching -> stringResource(R.string.home_headline_waiting)
        offline -> stringResource(R.string.home_headline_offline)
        else -> stringResource(R.string.home_headline_caught_up)
    }
}

@Composable
private fun heroDetail(state: BridgeUiState, offline: Boolean): String = when {
    state.stopping -> stringResource(R.string.home_detail_stopping)
    state.phase == BridgePhase.Idle || state.phase == BridgePhase.Watching -> lastResult(state) ?: idleDetail(state, offline)
    else -> idleDetail(state, offline)
}

/** What the last transfer did, which says more than a description of the state. */
@Composable
private fun lastResult(state: BridgeUiState): String? {
    if (state.autoSyncPaused || state.autoOffloadPaused) return null
    state.lastCompleted?.let { done ->
        val at = formatTransferStamp(done.timeMs)
        return pluralStringResource(
            if (done.synced) R.plurals.home_result_synced else R.plurals.home_result_offloaded,
            done.files,
            done.files,
            at,
        )
    }
    val sync = state.lastSync.takeIf { it != "never" }
    val offload = state.lastOffload.takeIf { it != "never" }
    return when {
        sync != null && offload != null -> stringResource(R.string.home_result_both, sync, offload)
        sync != null -> stringResource(R.string.home_last_sync, sync)
        offload != null -> stringResource(R.string.home_last_offload, offload)
        else -> null
    }
}

@Composable
private fun idleDetail(state: BridgeUiState, offline: Boolean): String = when {
    state.phase == BridgePhase.Syncing -> stringResource(R.string.home_detail_syncing)
    state.phase == BridgePhase.Offloading -> stringResource(R.string.home_detail_offloading)
    state.cameraReachable && state.autoSyncPaused && state.watching -> stringResource(R.string.home_detail_camera_paused)
    state.cameraReachable -> stringResource(R.string.home_detail_camera)
    state.phoneFiles > 0 && state.serverReachable && state.autoOffloadPaused && state.watching ->
        stringResource(R.string.home_detail_ready_paused)
    state.phoneFiles > 0 && state.serverReachable -> stringResource(R.string.home_detail_ready)
    state.phoneFiles > 0 -> stringResource(R.string.home_detail_waiting_home)
    offline && state.watching -> stringResource(R.string.home_detail_watching)
    offline -> stringResource(R.string.home_detail_offline)
    else -> stringResource(R.string.home_detail_caught_up)
}

// ---------------------------------------------------------------- actions

/**
 * Buzzes once when [eventTimeMs] changes to a fresh value while Home is on
 * screen. Events older than a few seconds (a transfer that finished in the
 * background) stay silent when the app comes back to the foreground.
 */
@Composable
private fun HapticOnNewEvent(eventTimeMs: Long?, type: HapticFeedbackType) {
    val haptics = LocalHapticFeedback.current
    var seen by remember { mutableStateOf(eventTimeMs) }
    LaunchedEffect(eventTimeMs) {
        val fresh = eventTimeMs != null &&
            eventTimeMs != seen &&
            System.currentTimeMillis() - eventTimeMs < FRESH_EVENT_MS
        if (fresh) haptics.performHapticFeedback(type)
        seen = eventTimeMs
    }
}

private const val FRESH_EVENT_MS = 5_000L

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionRow(
    state: BridgeUiState,
    transferring: Boolean,
    onSync: () -> Unit,
    onOffload: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    fun tap(action: () -> Unit): () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        action()
    }
    AnimatedContent(
        targetState = transferring,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "actions",
    ) { busy ->
        if (busy) {
            OutlinedButton(
                onClick = tap(onStop),
                enabled = !state.stopping,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                contentPadding = ButtonDefaults.MediumContentPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (state.stopping) R.string.home_action_stopping else R.string.home_action_stop),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = tap(onSync),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.home_action_sync), style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalButton(
                    onClick = tap(onOffload),
                    enabled = state.phoneFiles > 0,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.common_offload), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** One thing the user has to do before the app can work on its own. */
@Composable
private fun Notice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(24.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------- clips + storage

@Composable
private fun RecentClipsRow(clips: List<StagedClip>, onOpenClip: (StagedClip) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        items(clips.take(12), key = { it.filename }) { clip ->
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpenClip(clip) },
            ) {
                if (clip.thumbFile != null) {
                    AsyncImage(
                        model = clip.thumbFile,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.5f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.7f),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
                ) {
                    Text(clip.timeFormatted, style = Numeric.medium, color = Color.White)
                    Text(
                        text = clip.dateFormatted,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
                if (clip.isRear) {
                    Tag(
                        text = stringResource(R.string.clip_side_rear),
                        container = Color.Black.copy(alpha = 0.55f),
                        content = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageCard(state: BridgeUiState, onClick: () -> Unit) {
    val total = state.phoneBytes + state.phoneFreeBytes
    val fraction = if (total > 0) state.phoneBytes.toFloat() / total.toFloat() else 0f
    ListGroup {
        GroupItem(
            title = stringResource(R.string.home_staging_title),
            supporting = pluralStringResource(
                R.plurals.home_staging_files,
                state.phoneFiles,
                state.phoneFiles,
                formatBytes(state.phoneBytes),
            ),
            icon = Icons.Outlined.SdStorage,
            onClick = onClick,
            below = {
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UsageBar(fraction = fraction, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(R.string.home_staging_free, formatBytes(state.phoneFreeBytes)),
                        style = Numeric.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

// ---------------------------------------------------------------- activity

@Composable
private fun RecentActivity(items: List<ActivityItem>, onOpenActivity: () -> Unit) {
    ListGroup {
        if (items.isEmpty()) {
            GroupItem(
                title = stringResource(R.string.home_recent_empty_title),
                supporting = stringResource(R.string.home_recent_empty),
                icon = Icons.Outlined.Info,
            )
        } else {
            items.take(3).forEach { item ->
                val (icon, tint) = activityVisual(item)
                GroupItem(
                    title = item.text,
                    supporting = formatRelativeTime(item.timeMs),
                    icon = icon,
                    iconTint = tint,
                    iconContainer = tint.copy(alpha = 0.14f),
                    onClick = onOpenActivity,
                )
            }
        }
    }
}

@Composable
internal fun activityVisual(item: ActivityItem): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    val signals = MaterialTheme.signals
    return when {
        item.kind == ActivityKind.Error || item.error -> Icons.Outlined.ErrorOutline to signals.error
        item.kind == ActivityKind.Offload -> Icons.Outlined.CloudUpload to signals.offload
        item.kind == ActivityKind.Sync -> Icons.Outlined.CloudDownload to signals.sync
        else -> Icons.Outlined.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

// ---------------------------------------------------------------- diagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSheet(
    target: DiagnosticTarget,
    state: BridgeUiState,
    onDismiss: () -> Unit,
    onRecheck: () -> Unit,
) {
    val online = if (target == DiagnosticTarget.Camera) state.cameraReachable else state.serverReachable
    val accent = if (target == DiagnosticTarget.Camera) MaterialTheme.signals.sync else MaterialTheme.signals.offload
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (target == DiagnosticTarget.Camera) Icons.Outlined.Videocam else Icons.Outlined.Dns,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column {
                    Text(
                        text = stringResource(
                            if (target == DiagnosticTarget.Camera) R.string.home_diag_camera_title else R.string.home_diag_homelab_title,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(if (online) R.string.pipeline_online else R.string.pipeline_offline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (online) MaterialTheme.signals.online else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = state.cameraDetail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val fixes = if (target == DiagnosticTarget.Camera) {
                listOf(
                    stringResource(R.string.home_fix_camera_ap),
                    stringResource(R.string.home_fix_mobile_data),
                    stringResource(R.string.home_fix_permission),
                )
            } else {
                buildList {
                    if (state.offloadAllowlist.isNotEmpty()) {
                        add(stringResource(R.string.home_fix_allowlist, state.offloadAllowlist.sorted().joinToString(", ")))
                    }
                    add(stringResource(R.string.home_fix_home_wifi))
                    add(stringResource(R.string.home_fix_receiver))
                    add(stringResource(R.string.home_fix_host_port))
                }
            }
            ListGroup(title = stringResource(R.string.home_diag_checklist)) {
                fixes.forEachIndexed { index, fix ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(50))
                                .background(accent.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${index + 1}", style = Numeric.small, color = accent)
                        }
                        Text(fix, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
            Button(
                onClick = onRecheck,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_diag_recheck))
            }
        }
    }
}

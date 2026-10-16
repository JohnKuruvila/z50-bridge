package io.github.johnkuruvila.z50bridge.navigation

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.johnkuruvila.z50bridge.BridgeController
import io.github.johnkuruvila.z50bridge.BridgePhase
import io.github.johnkuruvila.z50bridge.BridgeUiState
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.StagedClip
import io.github.johnkuruvila.z50bridge.service.BridgeService
import io.github.johnkuruvila.z50bridge.service.WatchSwitch
import io.github.johnkuruvila.z50bridge.ui.ActivityScreen
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.SettingsScreen
import io.github.johnkuruvila.z50bridge.ui.clips.ClipDetailScreen
import io.github.johnkuruvila.z50bridge.ui.clips.ClipsScreen
import io.github.johnkuruvila.z50bridge.ui.dashcam.DashcamSettingsScreen
import io.github.johnkuruvila.z50bridge.ui.home.HomeScreen
import io.github.johnkuruvila.z50bridge.ui.live.LiveViewScreen
import kotlinx.coroutines.launch

@Composable
fun Z50NavShell(
    controller: BridgeController,
    state: BridgeUiState,
    needsSetup: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val shareVideosTitle = stringResource(R.string.share_dashcam_videos)
    val shareFailed = stringResource(R.string.clips_share_failed)
    val saveFailed = stringResource(R.string.settings_save_failed)
    val snackbarHost = remember { SnackbarHostState() }
    val backStacks = TopDestination.entries.associateWith { destination ->
        rememberNavBackStack(destination.key)
    }
    val current = state.destination
    val backStack = backStacks.getValue(current)
    val atRoot = backStack.size <= 1
    val busy = state.phase == BridgePhase.Syncing || state.phase == BridgePhase.Offloading
    // Null until loaded, so Home's setup notices do not flash on start.
    val loadedSettings by remember { controller.settings.flow }.collectAsStateWithLifecycle(initialValue = null)

    fun push(key: NavKey) {
        if (backStack.lastOrNull() != key) backStack.add(key)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // Stops the transfer only. A one-shot Sync or Offload service ends on its
    // own once the transfer unwinds; watching carries on.
    fun stopTransfer() {
        controller.stopTransfer()
    }

    // Live view and Dashcam settings only need the camera free while open;
    // the watcher syncs again once they close.
    fun stopTransferForCamera() {
        controller.stopTransfer(pauseAutomatic = false)
    }

    fun shareClips(clips: List<StagedClip>) {
        if (clips.isEmpty()) return
        try {
            val uris = ArrayList(
                clips.map { clip ->
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", clip.file)
                },
            )
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/mp4"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, shareVideosTitle))
        } catch (_: Exception) {
            AppToasts.show(context, shareFailed)
            controller.refreshStaged()
        }
    }

    // NavDisplay only claims back while its stack has somewhere to go. At the
    // root of Clips or Activity, back returns to Home; at Home's root it falls
    // through to the system so predictive back-to-home works.
    BackHandler(enabled = atRoot && current != TopDestination.Home) {
        controller.setDestination(TopDestination.Home)
    }

    val adaptive = currentWindowAdaptiveInfo()
    NavigationSuiteScaffold(
        layoutType = if (atRoot) {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptive)
        } else {
            NavigationSuiteType.None
        },
        containerColor = MaterialTheme.colorScheme.background,
        navigationSuiteItems = {
            TopDestination.entries.forEach { destination ->
                val selected = destination == current
                item(
                    selected = selected,
                    onClick = {
                        if (selected) {
                            // Re-tapping the active tab returns to its root.
                            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                        } else {
                            controller.setDestination(destination)
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.labelRes)) },
                    badge = if (destination == TopDestination.Clips && state.phoneFiles > 0) {
                        { Badge { Text(state.phoneFiles.toString()) } }
                    } else {
                        null
                    },
                )
            }
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = ::pop,
            entryProvider = entryProvider {
                entry<HomeKey> {
                    HomeScreen(
                        state = state,
                        needsSetup = needsSetup,
                        onWatch = { enabled ->
                            lifecycleOwner.lifecycleScope.launch { WatchSwitch.set(context, controller, enabled) }
                        },
                        onSync = { startAction(context, BridgeService.ACTION_SYNC) },
                        onOffload = { startAction(context, BridgeService.ACTION_OFFLOAD) },
                        onStop = ::stopTransfer,
                        onRefresh = { controller.refreshStatus() },
                        onOpenSetup = { controller.setShowSetup(true) },
                        onOpenSettings = { push(SettingsKey()) },
                        cameraWifiConfigured = loadedSettings?.cameraSsid?.isNotBlank(),
                        autoConnectOn = loadedSettings?.cameraWifiAutoConnect == true,
                        onOpenCameraSettings = {
                            push(SettingsKey())
                            push(SettingsKey(SettingsSection.Camera))
                        },
                        onLocationGranted = { controller.refreshNetworkHints() },
                        onOpenClips = { controller.setDestination(TopDestination.Clips) },
                        onOpenClip = { clip -> push(ClipDetailKey(clip.filename)) },
                        onOpenActivity = { controller.setDestination(TopDestination.Activity) },
                        onOpenLiveView = { push(LiveViewKey) },
                        onOpenDashcam = { push(DashcamSettingsKey) },
                        onSetRecording = controller::setCameraRecording,
                    )
                }
                entry<ClipsKey> {
                    ClipsScreen(
                        clips = state.stagedClips,
                        totalBytes = state.phoneBytes,
                        busy = busy,
                        snackbarHostState = snackbarHost,
                        onRefresh = { controller.refreshStaged() },
                        onOpenClip = { clip -> push(ClipDetailKey(clip.filename)) },
                        onTrashClips = { clips -> controller.trashClips(clips) },
                        onRestoreTrash = { token -> controller.restoreTrash(token) },
                        onShareClips = ::shareClips,
                        onClearAll = { controller.clearAllStaged() },
                    )
                }
                entry<ClipDetailKey> { key ->
                    val clip = state.stagedClips.firstOrNull { it.filename == key.filename }
                    if (clip == null) {
                        // Offloaded or deleted while open.
                        LaunchedEffect(key) { pop() }
                    } else {
                        ClipDetailScreen(
                            clip = clip,
                            onBack = ::pop,
                            onDelete = { controller.deleteClip(it) },
                        )
                    }
                }
                entry<ActivityKey> {
                    ActivityScreen(
                        items = state.activity,
                        buildDiagnostics = controller::diagnosticsReport,
                        onClear = { controller.clearActivity() },
                        onRetry = {
                            startAction(
                                context,
                                if (state.phoneFiles > 0) BridgeService.ACTION_OFFLOAD else BridgeService.ACTION_SYNC,
                            )
                        },
                    )
                }
                entry<SettingsKey> { key ->
                    val connectedWifi by controller.connectedWifi.collectAsStateWithLifecycle()
                    val seenWifi by controller.seenWifi.collectAsStateWithLifecycle()
                    SettingsScreen(
                        section = key.section,
                        settingsFlow = controller.settings.flow,
                        stagedBytes = state.phoneBytes,
                        freeBytes = state.phoneFreeBytes,
                        cameraWifiStatus = state.cameraWifiStatus,
                        cameraReachable = state.cameraReachable,
                        cameraStatus = state.cameraStatus,
                        onBack = ::pop,
                        onOpenSection = { push(SettingsKey(it)) },
                        onOpenDashcam = { push(DashcamSettingsKey) },
                        onSave = { next ->
                            lifecycleOwner.lifecycleScope.launch {
                                try {
                                    controller.settings.update { next }
                                } catch (_: Exception) {
                                    AppToasts.show(context, saveFailed)
                                }
                            }
                        },
                        onTest = { cfg -> controller.testConnection(cfg) },
                        onOpenSetup = { controller.setShowSetup(true) },
                        onClearHistory = { controller.clearSyncHistory() },
                        buildDiagnostics = controller::diagnosticsReport,
                        onLocationGranted = { controller.refreshNetworkHints() },
                        connectedWifi = connectedWifi,
                        seenWifi = seenWifi,
                        nearbyWifi = controller::nearbyWifi,
                        onConnectDirect = controller::connectCameraDirect,
                        onSetWifiChannel = controller::setCameraWifiChannel,
                        runSpeedTest = controller::runSpeedTest,
                    )
                }
                entry<DashcamSettingsKey> {
                    DisposableEffect(Unit) {
                        controller.setDashcamScreenOpen(true)
                        onDispose { controller.setDashcamScreenOpen(false) }
                    }
                    DashcamSettingsScreen(
                        subtitle = state.cameraStatus
                            ?.let { listOfNotNull(it.model, it.firmware).joinToString(" · ") }
                            ?.ifEmpty { null },
                        transferRunning = busy,
                        onLoad = controller::loadDashcamSettings,
                        onSave = controller::saveDashcamSetting,
                        onStopTransfer = ::stopTransferForCamera,
                        onBack = ::pop,
                    )
                }
                entry<LiveViewKey> {
                    LiveViewScreen(
                        transferRunning = busy,
                        onStart = controller::startLiveView,
                        onStop = controller::stopLiveView,
                        onStopTransfer = ::stopTransferForCamera,
                        onBack = ::pop,
                    )
                }
            },
            // Screens change in place; no slide or fade between them.
            transitionSpec = { NoTransition },
            popTransitionSpec = { NoTransition },
            predictivePopTransitionSpec = { NoTransition },
        )
    }
}

private val NoTransition = ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)

private fun startAction(context: Context, action: String) {
    ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java).setAction(action))
}

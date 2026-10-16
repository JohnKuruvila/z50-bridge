package io.github.johnkuruvila.z50bridge.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.compose.material3.Player as PlayerSurface
import io.github.johnkuruvila.z50bridge.CameraResult
import io.github.johnkuruvila.z50bridge.LiveEndpoint
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.common.FullscreenEffect
import io.github.johnkuruvila.z50bridge.ui.common.KeepScreenOn
import io.github.johnkuruvila.z50bridge.ui.components.StatusDot
import io.github.johnkuruvila.z50bridge.ui.theme.BrandDarkColors
import io.github.johnkuruvila.z50bridge.ui.theme.DarkSignals
import io.github.johnkuruvila.z50bridge.ui.theme.LocalSignals

private sealed interface LiveState {
    data object Connecting : LiveState
    data object Playing : LiveState
    data object Busy : LiveState
    data object Offline : LiveState
    data class Failed(val message: String) : LiveState
}

/**
 * Live RTSP preview from the camera, for aiming it or checking the view. Video
 * screens stay dark regardless of the app theme. The watcher does not sync
 * while this screen is open (see BridgeController.startLiveView).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LiveViewScreen(
    transferRunning: Boolean,
    onStart: suspend () -> CameraResult<LiveEndpoint>,
    onStop: () -> Unit,
    onStopTransfer: () -> Unit,
    onBack: () -> Unit,
) {
    MaterialTheme(colorScheme = BrandDarkColors) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSignals provides DarkSignals) {
            LiveViewContent(transferRunning, onStart, onStop, onStopTransfer, onBack)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LiveViewContent(
    transferRunning: Boolean,
    onStart: suspend () -> CameraResult<LiveEndpoint>,
    onStop: () -> Unit,
    onStopTransfer: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var rear by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var muted by rememberSaveable { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var endpoint by remember { mutableStateOf<LiveEndpoint?>(null) }
    var state by remember { mutableStateOf<LiveState>(LiveState.Connecting) }
    val currentOnStop by rememberUpdatedState(onStop)
    val inspection = LocalInspectionMode.current

    FullscreenEffect(fullscreen)
    BackHandler(enabled = fullscreen) { fullscreen = false }
    KeepScreenOn(state == LiveState.Playing)
    DisposableEffect(Unit) { onDispose { currentOnStop() } }

    LaunchedEffect(attempt) {
        if (inspection) return@LaunchedEffect
        state = LiveState.Connecting
        state = when (val result = onStart()) {
            is CameraResult.Ok -> {
                endpoint = result.value
                LiveState.Connecting
            }
            CameraResult.Busy -> LiveState.Busy
            CameraResult.Offline -> LiveState.Offline
            is CameraResult.Failed -> LiveState.Failed(result.message)
        }
    }
    LaunchedEffect(state) {
        if (state is LiveState.Failed) AppToasts.show(context, (state as LiveState.Failed).message)
    }
    LaunchedEffect(transferRunning) {
        if (!transferRunning && state == LiveState.Busy) attempt += 1
    }

    val player = endpoint?.let { rememberLivePlayer(it, rear, muted, attempt) { state = it } }

    val video: @Composable (Modifier) -> Unit = { modifier ->
        // A faintly lighter frame shows where the picture will appear while connecting.
        Box(modifier.background(if (fullscreen) Color.Black else Color(0xFF151A20)), contentAlignment = Alignment.Center) {
            if (player != null) PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
            StateOverlay(
                state = state,
                onRetry = { attempt += 1 },
                onStopTransfer = onStopTransfer,
            )
            if (state == LiveState.Playing) LiveBadge(Modifier.align(Alignment.TopStart).padding(12.dp))
        }
    }

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            video(Modifier.fillMaxSize())
            IconButton(
                onClick = { fullscreen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Outlined.FullscreenExit, contentDescription = stringResource(R.string.player_exit_fullscreen), tint = Color.White)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Color.White)
            }
            Text(
                text = stringResource(R.string.live_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.weight(1f))
        video(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CameraPicker(rear = rear, onPick = { rear = it })
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RoundAction(
                    icon = if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    label = stringResource(if (muted) R.string.live_unmute else R.string.live_mute),
                    onClick = { muted = !muted },
                    modifier = Modifier.weight(1f),
                )
                RoundAction(
                    icon = Icons.Outlined.Fullscreen,
                    label = stringResource(R.string.player_fullscreen),
                    onClick = { fullscreen = true },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.live_sync_paused),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CameraPicker(rear: Boolean, onPick: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.fillMaxWidth(),
    ) {
        listOf(false, true).forEachIndexed { index, isRear ->
            ToggleButton(
                checked = rear == isRear,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onPick(isRear)
                },
                shapes = if (index == 0) {
                    ButtonGroupDefaults.connectedLeadingButtonShapes()
                } else {
                    ButtonGroupDefaults.connectedTrailingButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(if (isRear) R.string.live_rear else R.string.live_front))
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = Color(0xFFFF4D4D), pulsing = true, size = 6.dp)
        Text(stringResource(R.string.live_badge), style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.StateOverlay(state: LiveState, onRetry: () -> Unit, onStopTransfer: () -> Unit) {
    val (icon, title, body) = when (state) {
        LiveState.Playing -> return
        LiveState.Connecting -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(48.dp))
                Text(stringResource(R.string.live_connecting), color = Color.White.copy(alpha = 0.8f))
            }
            return
        }
        LiveState.Busy -> Triple(Icons.Outlined.Sync, stringResource(R.string.live_busy_title), stringResource(R.string.live_busy_body))
        LiveState.Offline -> Triple(Icons.Outlined.WifiOff, stringResource(R.string.dashcam_offline_title), stringResource(R.string.live_offline_body))
        is LiveState.Failed -> Triple(Icons.Outlined.ErrorOutline, stringResource(R.string.live_failed_title), state.message)
    }
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        if (state == LiveState.Busy) {
            OutlinedButton(onClick = onStopTransfer) { Text(stringResource(R.string.home_action_stop)) }
        } else {
            FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.common_try_again)) }
        }
    }
}

/**
 * Low-latency RTSP player over the camera's Wi-Fi. Interleaved TCP is forced
 * because UDP RTP does not survive the phone's per-network socket binding;
 * small buffers keep the preview close to real time. Released while the app
 * is in the background and rebuilt on return.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun rememberLivePlayer(
    endpoint: LiveEndpoint,
    rear: Boolean,
    muted: Boolean,
    attempt: Int,
    onState: (LiveState) -> Unit,
): ExoPlayer? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val currentOnState by rememberUpdatedState(onState)

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!started) return null

    val failedMessage = stringResource(R.string.live_failed_body)
    val player = remember(endpoint, rear, attempt) {
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setSocketFactory(endpoint.network.socketFactory)
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(endpoint.url(rear)))
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 2_000, 250, 250)
                    .build(),
            )
            .build()
            .apply {
                setMediaSource(source)
                prepare()
                playWhenReady = true
            }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) {
        currentOnState(LiveState.Connecting)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) currentOnState(LiveState.Playing)
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnState(LiveState.Failed("$failedMessage (${error.errorCodeName})"))
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    return player
}

private const val RTSP_TIMEOUT_MS = 8_000L

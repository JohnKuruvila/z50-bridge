package io.github.johnkuruvila.z50bridge.ui.clips

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.StagedClip
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.common.FullscreenEffect
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import io.github.johnkuruvila.z50bridge.ui.components.GroupItem
import io.github.johnkuruvila.z50bridge.ui.components.ListGroup
import io.github.johnkuruvila.z50bridge.ui.components.Tag
import io.github.johnkuruvila.z50bridge.ui.theme.Numeric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ClipDetailScreen(
    clip: StagedClip,
    onBack: () -> Unit,
    onDelete: (StagedClip) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val shareTitle = stringResource(R.string.share_dashcam_video)
    val noShareApp = stringResource(R.string.detail_no_share_app)
    val exportUnsupported = stringResource(R.string.detail_export_unsupported)
    val exportDone = stringResource(R.string.detail_export_done)
    var showDeleteDialog by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val player = rememberClipPlayer(clip)

    FullscreenEffect(fullscreen)
    BackHandler(enabled = fullscreen) { fullscreen = false }

    fun share() {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", clip.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, shareTitle))
        } catch (_: Exception) {
            AppToasts.show(context, noShareApp)
        }
    }

    if (fullscreen) {
        VideoPlayer(
            player = player,
            fullscreen = true,
            onToggleFullscreen = { fullscreen = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // Edge to edge on purpose: the player sits under the status bar.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding(),
            ) {
                ClipVideo(
                    clip = clip,
                    player = player,
                    onToggleFullscreen = { fullscreen = true },
                    onBack = onBack,
                )
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(if (clip.isRear) R.string.detail_side_rear else R.string.detail_side_front),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(clip.timeFormatted, style = Numeric.hero)
                    Text(
                        clip.dateFormatted,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        if (clip.durationFormatted.isNotEmpty()) Tag(clip.durationFormatted, icon = Icons.Outlined.Schedule)
                        Tag(formatBytes(clip.sizeBytes), icon = Icons.Outlined.SdStorage)
                        if (clip.hasGps) Tag(stringResource(R.string.clip_tag_gps), icon = Icons.Outlined.LocationOn)
                        if (clip.hasGsn) Tag(stringResource(R.string.clip_tag_imu), icon = Icons.Outlined.Sensors)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            exporting = true
                            scope.launch {
                                val exported = withContext(Dispatchers.IO) {
                                    runCatching { exportClipToGallery(context, clip.file) }.getOrNull()
                                }
                                exporting = false
                                if (exported == null) AppToasts.show(context, exportUnsupported)
                                else snackbar.showSnackbar(exportDone)
                            }
                        },
                        enabled = !exporting,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .weight(1.4f)
                            .height(56.dp),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (exporting) R.string.detail_saving else R.string.detail_save_gallery))
                    }
                    FilledTonalButton(
                        onClick = ::share,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.common_share))
                    }
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = ButtonDefaults.SmallContentPadding,
                        modifier = Modifier
                            .height(56.dp)
                            .width(56.dp),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.detail_delete_cd))
                    }
                }

                ListGroup(title = stringResource(R.string.detail_section_file)) {
                    GroupItem(
                        title = stringResource(R.string.detail_filename),
                        supporting = clip.filename,
                        icon = Icons.Outlined.Description,
                    )
                    GroupItem(
                        title = stringResource(R.string.detail_gps_log),
                        supporting = stringResource(if (clip.hasGps) R.string.detail_sidecar_present else R.string.detail_sidecar_missing),
                        icon = Icons.Outlined.LocationOn,
                    )
                    GroupItem(
                        title = stringResource(R.string.detail_imu_log),
                        supporting = stringResource(if (clip.hasGsn) R.string.detail_sidecar_present else R.string.detail_sidecar_missing),
                        icon = Icons.Outlined.Sensors,
                    )
                }
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        showDeleteDialog = false
                        onDelete(clip)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** ExoPlayer on device; the thumbnail in previews and screenshot tests. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ClipVideo(
    clip: StagedClip,
    player: ExoPlayer?,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
    val backButton: @Composable BoxScope.() -> Unit = {
        IconButton(
            onClick = onBack,
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
            modifier = Modifier.padding(4.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
    }
    VideoPlayer(
        player = player,
        fullscreen = false,
        onToggleFullscreen = onToggleFullscreen,
        modifier = modifier,
        topStart = backButton,
        placeholder = {
            // Screenshot tests have no decoder: show the thumbnail under the same controls.
            clip.thumbFile?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        },
    )
}

/**
 * One player per clip, kept across fullscreen and rotation, paused when the
 * app goes to the background. Null in inspection mode (no decoders).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun rememberClipPlayer(clip: StagedClip): ExoPlayer? {
    if (LocalInspectionMode.current) return null
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playbackFailed = stringResource(R.string.detail_playback_failed)
    val player = remember(clip.file) {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(PLAYER_SEEK_MS)
            .setSeekForwardIncrementMs(PLAYER_SEEK_MS)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(clip.file)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AppToasts.show(context, "$playbackFailed (${error.errorCodeName})")
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    return player
}

package io.github.johnkuruvila.z50bridge.ui.clips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PlaybackSpeedToggleButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.ui.common.KeepScreenOn
import kotlinx.coroutines.delay
import androidx.media3.ui.compose.material3.Player as PlayerSurface

private const val HIDE_AFTER_MS = 3_000L

/** Seek step for the buttons and double-tap: a dashcam event is seconds long. */
const val PLAYER_SEEK_MS = 5_000L

/**
 * Video with overlay controls in the style of the system media player: tap to
 * show or hide, double-tap the left or right third to seek, controls stay up
 * while paused or scrubbing and fade out a few seconds into playback.
 */
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
fun VideoPlayer(
    player: Player?,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    topStart: @Composable BoxScope.() -> Unit = {},
    /** Drawn instead of video when there is no player (screenshot tests). */
    placeholder: @Composable BoxScope.() -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(player?.isPlaying == true) }
    var poke by remember { mutableIntStateOf(0) }
    var seekHint by remember { mutableStateOf<Int?>(null) }
    val sliderInteraction = remember { MutableInteractionSource() }
    val dragging by sliderInteraction.collectIsDraggedAsState()
    val pressing by sliderInteraction.collectIsPressedAsState()

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    KeepScreenOn(isPlaying)

    // Auto-hide only while playing and not being touched.
    LaunchedEffect(controlsVisible, isPlaying, dragging, pressing, poke) {
        if (controlsVisible && isPlaying && !dragging && !pressing) {
            delay(HIDE_AFTER_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(650)
            seekHint = null
        }
    }

    val showLabel = stringResource(R.string.player_show_controls)
    Box(
        modifier = modifier
            .background(Color.Black)
            .semantics {
                contentDescription = showLabel
                onClick(label = showLabel) {
                    controlsVisible = true
                    true
                }
            }
            .pointerInput(player) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val p = player ?: return@detectTapGestures
                        val third = size.width / 3f
                        val direction = when {
                            offset.x < third -> -1
                            offset.x > third * 2 -> 1
                            else -> 0
                        }
                        if (direction == 0) {
                            if (p.isPlaying) p.pause() else p.play()
                        } else {
                            val target = (p.currentPosition + direction * PLAYER_SEEK_MS).coerceAtLeast(0)
                            p.seekTo(target)
                            seekHint = direction
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        poke += 1
                    },
                )
            },
    ) {
        if (player != null) PlayerSurface(player = player, modifier = Modifier.fillMaxSize()) else placeholder()

        SeekHint(direction = seekHint)

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.28f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.7f),
                        ),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box { topStart() }
                    Spacer(Modifier.weight(1f))
                    PlaybackSpeedToggleButton(
                        player,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        speedSelection = listOf(0.25f, 0.5f, 1f, 2f, 4f),
                    )
                    MuteButton(player, tint = Color.White)
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SeekBackButton(player, tint = Color.White, modifier = Modifier.size(48.dp))
                    PlayPauseButton(
                        player,
                        tint = Color.White,
                        iconSize = 36.dp,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f)),
                    )
                    SeekForwardButton(player, tint = Color.White, modifier = Modifier.size(48.dp))
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PositionText(player, color = Color.White, modifier = Modifier.padding(end = 8.dp))
                    Box(Modifier.weight(1f)) {
                        ProgressSlider(
                            player,
                            interactionSource = sliderInteraction,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                        )
                    }
                    DurationText(player, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                    IconButton(
                        onClick = onToggleFullscreen,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                    ) {
                        Icon(
                            if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            contentDescription = stringResource(
                                if (fullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.SeekHint(direction: Int?) {
    AnimatedVisibility(
        visible = direction != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(if ((direction ?: 0) < 0) Alignment.CenterStart else Alignment.CenterEnd),
    ) {
        Text(
            text = stringResource(
                if ((direction ?: 0) < 0) R.string.player_seek_back_hint else R.string.player_seek_forward_hint,
                PLAYER_SEEK_MS / 1000,
            ),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

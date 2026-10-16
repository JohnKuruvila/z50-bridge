package io.github.johnkuruvila.z50bridge.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.johnkuruvila.z50bridge.CameraResult
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.camera.CameraStatus
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import io.github.johnkuruvila.z50bridge.ui.components.StatusDot
import io.github.johnkuruvila.z50bridge.ui.components.UsageBar
import io.github.johnkuruvila.z50bridge.ui.theme.Numeric
import io.github.johnkuruvila.z50bridge.ui.theme.signals
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * What the camera is doing right now, shown while connected: recording, SD
 * card, GPS and clock (and a heat warning when the chip runs hot), plus the
 * things you do with a connected camera besides
 * syncing (start or stop recording, watch live, change its settings). Values
 * the camera did not report read "--".
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CameraCard(
    status: CameraStatus?,
    onSetRecording: suspend (Boolean) -> CameraResult<Boolean>,
    onOpenLiveView: () -> Unit,
    onOpenDashcam: () -> Unit,
) {
    val accent = MaterialTheme.signals.sync
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Videocam, contentDescription = null, tint = accent)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = status?.model ?: stringResource(R.string.pipeline_camera),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status?.firmware?.let { stringResource(R.string.camera_firmware, it) }
                            ?: stringResource(R.string.camera_reading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            RecordingRow(recording = status?.recording, onSetRecording = onSetRecording)

            status?.socTemp?.takeIf { status.runningHot == true }?.let { HeatRow(it) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SdStat(status)
                Stat(
                    label = stringResource(R.string.camera_gps),
                    value = when (status?.gpsFix) {
                        true -> stringResource(R.string.camera_gps_fix)
                        false -> stringResource(R.string.camera_gps_none)
                        null -> UNKNOWN
                    },
                )
                Stat(label = stringResource(R.string.camera_clock), value = clockValue(status))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onOpenLiveView,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(Icons.Outlined.LiveTv, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.camera_live_view))
                }
                OutlinedButton(
                    onClick = onOpenDashcam,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.camera_settings))
                }
            }
        }
    }
}

private const val UNKNOWN = "--"

/**
 * Loop recording on/off. Starting is one tap; stopping asks first, because a
 * stopped camera saves nothing (crashes included) until someone turns it back
 * on. While off, the row takes the error colours so the state is hard to miss.
 * The switch shows what the camera last reported, never the requested state.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecordingRow(
    recording: Boolean?,
    onSetRecording: suspend (Boolean) -> CameraResult<Boolean>,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val offline = stringResource(R.string.camera_record_offline)
    val transferBusy = stringResource(R.string.dashcam_save_busy)
    val failed = stringResource(R.string.camera_record_failed)
    val unconfirmed = stringResource(R.string.camera_record_unconfirmed)

    fun apply(on: Boolean) {
        busy = true
        scope.launch {
            when (val result = onSetRecording(on)) {
                is CameraResult.Ok -> if (!result.value) AppToasts.show(context, unconfirmed)
                CameraResult.Offline -> AppToasts.show(context, offline)
                CameraResult.Busy -> AppToasts.show(context, transferBusy)
                is CameraResult.Failed -> AppToasts.show(context, "$failed: ${result.message}")
            }
            busy = false
        }
    }

    val stopped = recording == false
    val container = if (stopped) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (stopped) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val dot = when (recording) {
        true -> Color(0xFFE5484D)
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val enabled = recording != null && !busy

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) {
                if (recording == true) confirmStop = true else apply(true)
            },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = dot, pulsing = recording == true, size = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when (recording) {
                            true -> R.string.camera_recording
                            false -> R.string.camera_not_recording
                            null -> R.string.camera_record_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        when (recording) {
                            true -> R.string.camera_record_on
                            false -> R.string.camera_record_off
                            null -> R.string.camera_record_unknown
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stopped) content else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(width = 52.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    LoadingIndicator(Modifier.size(32.dp))
                } else {
                    Switch(
                        checked = recording == true,
                        onCheckedChange = null,
                        enabled = recording != null,
                    )
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.camera_record_stop_title)) },
            text = { Text(stringResource(R.string.camera_record_stop_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStop = false
                        apply(false)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.camera_record_stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * Shown only while the chip reads at or above [CameraStatus.HOT_SOC_TEMP].
 * The firmware does not state units, so the reading is shown as sent, next
 * to the camera's own lowest heat threshold.
 */
@Composable
private fun HeatRow(socTemp: Long) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.DeviceThermostat, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.camera_hot_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.camera_hot_body, socTemp, CameraStatus.FIRMWARE_HEAT_THRESHOLD),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RowScope.SdStat(status: CameraStatus?) {
    val free = status?.sdFreeBytes
    Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatLabel(stringResource(R.string.camera_sd))
        Text(
            text = free?.let { stringResource(R.string.camera_sd_free, formatBytes(it)) } ?: UNKNOWN,
            style = Numeric.small,
            maxLines = 1,
        )
        status?.sdUsedFraction?.let { UsageBar(fraction = it, color = MaterialTheme.signals.sync) }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatLabel(label)
        Text(value, style = Numeric.small, maxLines = 1)
    }
}

@Composable
private fun StatLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun clockValue(status: CameraStatus?): String {
    val offset = status?.clockOffsetSeconds ?: return UNKNOWN
    if (status.clockInSync == true) return stringResource(R.string.camera_clock_ok)
    val magnitude = abs(offset)
    val amount = if (magnitude >= 120) "${magnitude / 60} min" else "$magnitude s"
    return if (offset > 0) "+$amount" else "−$amount"
}

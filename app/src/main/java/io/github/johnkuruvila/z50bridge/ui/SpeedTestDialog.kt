package io.github.johnkuruvila.z50bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.johnkuruvila.z50bridge.CameraResult
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.SpeedTestProgress
import io.github.johnkuruvila.z50bridge.SpeedTestResult
import io.github.johnkuruvila.z50bridge.diag.describeLink
import io.github.johnkuruvila.z50bridge.diag.describeRadios
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface SpeedTestState {
    data class Running(val progress: SpeedTestProgress, val stepStartedMs: Long) : SpeedTestState
    data class Done(val result: SpeedTestResult) : SpeedTestState
    data class Failed(val message: String) : SpeedTestState
}

/**
 * Runs the camera speed test and shows each phase's rate. The test holds the
 * camera for about 50 s; Stop cancels it within a read or two.
 */
@Composable
fun SpeedTestDialog(
    runSpeedTest: suspend ((SpeedTestProgress) -> Unit) -> CameraResult<SpeedTestResult>,
    onShareDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offline = stringResource(R.string.dashcam_offline_title)
    val busy = stringResource(R.string.dashcam_busy_title)
    var state by remember {
        mutableStateOf<SpeedTestState>(
            SpeedTestState.Running(SpeedTestProgress(0, 3, ""), System.currentTimeMillis()),
        )
    }
    val job = remember {
        scope.launch {
            val result = runSpeedTest { progress ->
                state = SpeedTestState.Running(progress, System.currentTimeMillis())
            }
            state = when (result) {
                is CameraResult.Ok -> SpeedTestState.Done(result.value)
                CameraResult.Offline -> SpeedTestState.Failed(offline)
                CameraResult.Busy -> SpeedTestState.Failed(busy)
                is CameraResult.Failed -> SpeedTestState.Failed(result.message)
            }
        }
    }
    val running = state is SpeedTestState.Running

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
        title = { Text(stringResource(R.string.speed_test_title)) },
        text = {
            when (val s = state) {
                is SpeedTestState.Running -> RunningBody(s)
                is SpeedTestState.Done -> ResultBody(s.result)
                is SpeedTestState.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (state is SpeedTestState.Done) {
                TextButton(onClick = onShareDiagnostics) { Text(stringResource(R.string.diagnostics_share)) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (running) job.cancel()
                    onDismiss()
                },
            ) {
                Text(stringResource(if (running) R.string.common_stop else R.string.speed_test_close))
            }
        },
    )
}

@Composable
private fun RunningBody(state: SpeedTestState.Running) {
    // Each phase runs for a fixed time, so progress can advance smoothly within it.
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state) {
        while (true) {
            val within = ((System.currentTimeMillis() - state.stepStartedMs) / PHASE_MS.toFloat()).coerceIn(0f, 1f)
            fraction = ((state.progress.step + within) / state.progress.steps).coerceIn(0f, 1f)
            delay(100)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.speed_test_running_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        if (state.progress.label.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.speed_test_step,
                    (state.progress.step + 1).coerceAtMost(state.progress.steps),
                    state.progress.steps,
                    state.progress.label,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ResultBody(result: SpeedTestResult) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        result.phases.forEach { phase ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(phase.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "%.2f MB/s".format(Locale.US, phase.bytesPerSecond / (1024.0 * 1024.0)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            stringResource(R.string.speed_test_link, describeLink(result.link)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.phases.any { it.warmupMs > 0 }) {
            Text(
                stringResource(R.string.speed_test_warmup_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        result.radios?.let { radios ->
            Text(
                stringResource(
                    if (result.radiosChanged) R.string.speed_test_radios_changed else R.string.speed_test_radios,
                    describeRadios(radios),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.cameraRecording == true) {
            Text(
                stringResource(R.string.speed_test_recording_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Matches BridgeController's SPEED_TEST_WARMUP_MS + SPEED_TEST_PHASE_MS; only used to animate progress.
private const val PHASE_MS = 16_000L

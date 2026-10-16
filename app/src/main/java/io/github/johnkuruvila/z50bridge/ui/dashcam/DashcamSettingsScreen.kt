package io.github.johnkuruvila.z50bridge.ui.dashcam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CarCrash
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.johnkuruvila.z50bridge.CameraResult
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.camera.DASHCAM_SPECS
import io.github.johnkuruvila.z50bridge.camera.DashcamControl
import io.github.johnkuruvila.z50bridge.camera.DashcamSection
import io.github.johnkuruvila.z50bridge.camera.DashcamValue
import io.github.johnkuruvila.z50bridge.camera.ResolutionOption
import io.github.johnkuruvila.z50bridge.camera.describeDashcamValue
import io.github.johnkuruvila.z50bridge.camera.resolutionOptions
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.components.EmptyState
import io.github.johnkuruvila.z50bridge.ui.components.GroupItem
import io.github.johnkuruvila.z50bridge.ui.components.ListGroup
import io.github.johnkuruvila.z50bridge.ui.components.SwitchItem
import io.github.johnkuruvila.z50bridge.ui.components.Z50TopBar
import io.github.johnkuruvila.z50bridge.ui.theme.Numeric
import kotlinx.coroutines.launch

private sealed interface LoadState {
    data object Loading : LoadState
    data object Offline : LoadState
    data object Busy : LoadState
    data class Failed(val message: String) : LoadState
    data object Loaded : LoadState
}

/**
 * The camera's own settings. Values come straight from API_GeneralQuery and
 * every change is written immediately, then read back, so the screen always
 * shows what the camera actually stored.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DashcamSettingsScreen(
    subtitle: String?,
    transferRunning: Boolean,
    onLoad: suspend () -> CameraResult<Map<String, DashcamValue>>,
    onSave: suspend (key: String, value: Any) -> CameraResult<DashcamValue?>,
    onStopTransfer: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val values = remember { mutableStateMapOf<String, DashcamValue>() }
    val saving = remember { mutableStateMapOf<String, Boolean>() }
    var load by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val offlineMessage = stringResource(R.string.dashcam_save_offline)
    val busyMessage = stringResource(R.string.dashcam_save_busy)
    val failedMessage = stringResource(R.string.dashcam_save_failed)
    val loadOfflineMessage = stringResource(R.string.dashcam_offline_title)
    val loadBusyMessage = stringResource(R.string.dashcam_busy_title)

    suspend fun reload() {
        load = LoadState.Loading
        load = when (val result = onLoad()) {
            is CameraResult.Ok -> {
                values.clear()
                values.putAll(result.value)
                LoadState.Loaded
            }
            CameraResult.Busy -> { AppToasts.show(context, loadBusyMessage); LoadState.Busy }
            CameraResult.Offline -> { AppToasts.show(context, loadOfflineMessage); LoadState.Offline }
            is CameraResult.Failed -> { AppToasts.show(context, result.message); LoadState.Failed(result.message) }
        }
    }

    LaunchedEffect(attempt) { reload() }
    // A transfer holds the camera session; load as soon as it lets go.
    LaunchedEffect(transferRunning) {
        if (!transferRunning && load == LoadState.Busy) reload()
    }

    fun save(key: String, value: Any) {
        val previous = values[key] ?: return
        values[key] = previous.copy(value = value)
        saving[key] = true
        scope.launch {
            when (val result = onSave(key, value)) {
                is CameraResult.Ok -> {
                    if (result.value != null) values[key] = result.value
                    else {
                        values[key] = previous
                        AppToasts.show(context, failedMessage)
                    }
                }
                else -> {
                    values[key] = previous
                    AppToasts.show(context,
                        when (result) {
                            CameraResult.Offline -> offlineMessage
                            CameraResult.Busy -> busyMessage
                            is CameraResult.Failed -> "$failedMessage: ${result.message}"
                            is CameraResult.Ok -> failedMessage
                        },
                    )
                }
            }
            saving.remove(key)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Z50TopBar(
                title = stringResource(R.string.settings_cat_dashcam),
                subtitle = subtitle,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { attempt += 1 }, enabled = load !is LoadState.Loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.dashcam_reload_cd))
                    }
                },
            )
        },
    ) { padding ->
        when (val current = load) {
            LoadState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(Modifier.size(56.dp))
            }
            LoadState.Offline -> EmptyState(
                icon = Icons.Outlined.WifiOff,
                title = stringResource(R.string.dashcam_offline_title),
                body = stringResource(R.string.dashcam_offline_body),
                modifier = Modifier.padding(padding),
                action = { FilledTonalButton(onClick = { attempt += 1 }) { Text(stringResource(R.string.common_try_again)) } },
            )
            LoadState.Busy -> EmptyState(
                icon = Icons.Outlined.Sync,
                title = stringResource(R.string.dashcam_busy_title),
                body = stringResource(R.string.dashcam_busy_body),
                modifier = Modifier.padding(padding),
                action = { OutlinedButton(onClick = onStopTransfer) { Text(stringResource(R.string.home_action_stop)) } },
            )
            is LoadState.Failed -> EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.dashcam_failed_title),
                body = current.message,
                modifier = Modifier.padding(padding),
                action = { FilledTonalButton(onClick = { attempt += 1 }) { Text(stringResource(R.string.common_try_again)) } },
            )
            LoadState.Loaded -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "note") {
                    Text(
                        text = stringResource(R.string.dashcam_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                DashcamSection.entries.forEach { section ->
                    // The resolution picker already names the modes; the raw list is only
                    // shown when the camera has no `image_quality` to pick with.
                    val specs = DASHCAM_SPECS.filter {
                        it.section == section && it.key in values &&
                            !(it.key == "record_resolution" && "image_quality" in values)
                    }
                    if (specs.isEmpty()) return@forEach
                    item(key = section.name) {
                        ListGroup(title = stringResource(section.titleRes)) {
                            specs.forEach { spec ->
                                val value = values.getValue(spec.key)
                                val meta = settingMeta(spec.key)
                                val busy = saving[spec.key] == true
                                val toggle = value.asToggle
                                val level = value.asLevel
                                when {
                                    spec.control == DashcamControl.Toggle && toggle != null -> SwitchItem(
                                        title = stringResource(meta.title),
                                        supporting = meta.summary?.let { stringResource(it) },
                                        icon = meta.icon,
                                        checked = toggle,
                                        enabled = !busy,
                                        onCheckedChange = { save(spec.key, if (it) "on" else "off") },
                                    )
                                    spec.control == DashcamControl.Level && level != null -> LevelItem(
                                        title = stringResource(meta.title),
                                        summary = meta.summary?.let { stringResource(it) },
                                        icon = meta.icon,
                                        level = level,
                                        enabled = !busy,
                                        onCommit = { save(spec.key, it) },
                                    )
                                    spec.control == DashcamControl.Codec &&
                                        value.value?.toString()?.lowercase() in listOf("h264", "h265") -> CodecItem(
                                            codec = value.value.toString().lowercase(),
                                            enabled = !busy,
                                            onChange = { save(spec.key, it) },
                                        )
                                    spec.control == DashcamControl.Resolution &&
                                        value.value?.toString()?.lowercase() in listOf("high", "middle", "low") -> ResolutionItem(
                                            quality = value.value.toString().lowercase(),
                                            options = resolutionOptions(values["record_resolution"]),
                                            enabled = !busy,
                                            onChange = { save(spec.key, it) },
                                        )
                                    spec.control == DashcamControl.Dormant &&
                                        value.value?.toString()?.toIntOrNull() in listOf(0, 10, 60) -> DormantItem(
                                            dormant = value.value.toString().toInt(),
                                            enabled = !busy,
                                            onChange = { save(spec.key, it) },
                                        )
                                    spec.control == DashcamControl.Brightness &&
                                        value.value?.toString()?.toIntOrNull() in listOf(0, 1, 2, 3) -> BrightnessItem(
                                            brightness = value.value.toString().toInt(),
                                            enabled = !busy,
                                            onChange = { save(spec.key, it) },
                                        )
                                    else -> GroupItem(
                                        title = stringResource(meta.title),
                                        supporting = describeDashcamValue(value),
                                        icon = meta.icon,
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "footer") {
                    Text(
                        text = stringResource(R.string.dashcam_readonly_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodecItem(codec: String, enabled: Boolean, onChange: (String) -> Unit) {
    GroupItem(
        title = stringResource(R.string.dashcam_codec),
        supporting = stringResource(R.string.dashcam_codec_sub),
        icon = Icons.Outlined.Movie,
        enabled = enabled,
        below = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                listOf("h264" to "H.264", "h265" to "H.265").forEachIndexed { index, (id, label) ->
                    ToggleButton(
                        checked = codec == id,
                        onCheckedChange = { if (id != codec) onChange(id) },
                        shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                            else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label)
                    }
                }
            }
        },
    )
}

@Composable
private fun ResolutionItem(quality: String, options: List<ResolutionOption>, enabled: Boolean, onChange: (String) -> Unit) {
    val levelNames = mapOf(
        "high" to stringResource(R.string.dashcam_quality_high),
        "middle" to stringResource(R.string.dashcam_quality_mid),
        "low" to stringResource(R.string.dashcam_quality_low),
    )
    GroupItem(
        title = stringResource(R.string.dashcam_resolution),
        supporting = stringResource(R.string.dashcam_resolution_sub),
        icon = Icons.Outlined.Hd,
        enabled = enabled,
        below = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                options.map { it.quality to (it.mode ?: levelNames.getValue(it.quality)) }.forEachIndexed { index, (id, label) ->
                    ToggleButton(
                        checked = quality == id,
                        onCheckedChange = { if (id != quality) onChange(id) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label)
                    }
                }
            }
        },
    )
}

@Composable
private fun DormantItem(dormant: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    GroupItem(
        title = stringResource(R.string.dashcam_dormant),
        supporting = stringResource(R.string.dashcam_dormant_sub),
        icon = Icons.Outlined.ScreenLockPortrait,
        enabled = enabled,
        below = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                listOf(
                    0 to stringResource(R.string.dashcam_dormant_never),
                    10 to stringResource(R.string.dashcam_dormant_10s),
                    60 to stringResource(R.string.dashcam_dormant_1m),
                ).forEachIndexed { index, (id, label) ->
                    ToggleButton(
                        checked = dormant == id,
                        onCheckedChange = { if (id != dormant) onChange(id) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            2 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label)
                    }
                }
            }
        },
    )
}

@Composable
private fun BrightnessItem(brightness: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    GroupItem(
        title = stringResource(R.string.dashcam_brightness),
        supporting = null,
        icon = Icons.Outlined.Brightness6,
        enabled = enabled,
        below = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                listOf(
                    0 to stringResource(R.string.dashcam_brightness_high),
                    1 to stringResource(R.string.dashcam_brightness_mid),
                    2 to stringResource(R.string.dashcam_brightness_low),
                    3 to stringResource(R.string.dashcam_brightness_auto),
                ).forEachIndexed { index, (id, label) ->
                    ToggleButton(
                        checked = brightness == id,
                        onCheckedChange = { if (id != brightness) onChange(id) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            3 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label)
                    }
                }
            }
        },
    )
}

@Composable
private fun LevelItem(
    title: String,
    summary: String?,
    icon: ImageVector,
    level: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var slider by remember(level) { mutableFloatStateOf(level.toFloat()) }
    GroupItem(
        title = title,
        supporting = summary,
        icon = icon,
        enabled = enabled,
        trailing = { Text(slider.toInt().toString(), style = Numeric.medium, color = MaterialTheme.colorScheme.primary) },
        below = {
            Slider(
                value = slider,
                onValueChange = { slider = it },
                // One write per gesture, not one per pixel of drag.
                onValueChangeFinished = { if (slider.toInt() != level) onCommit(slider.toInt()) },
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier.padding(top = 8.dp),
            )
        },
    )
}

private data class SettingMeta(val title: Int, val icon: ImageVector, val summary: Int? = null)

private fun settingMeta(key: String): SettingMeta = when (key) {
    "record_resolution" -> SettingMeta(R.string.dashcam_resolution, Icons.Outlined.HighQuality)
    "video_codec" -> SettingMeta(R.string.dashcam_codec, Icons.Outlined.Movie)
    "image_quality" -> SettingMeta(R.string.dashcam_resolution, Icons.Outlined.Hd)
    "exposure_biaslevel" -> SettingMeta(R.string.dashcam_exposure, Icons.Outlined.Exposure, R.string.dashcam_exposure_sub)
    "wdr_enable" -> SettingMeta(R.string.dashcam_wdr, Icons.Outlined.Contrast, R.string.dashcam_wdr_sub)
    "osd_switch" -> SettingMeta(R.string.dashcam_osd_time, Icons.Outlined.AccessTime, R.string.dashcam_osd_sub)
    "osd_speedswitch" -> SettingMeta(R.string.dashcam_osd_speed, Icons.Outlined.Speed, R.string.dashcam_osd_sub)
    "scam_vertical_mirror" -> SettingMeta(R.string.dashcam_rear_flip, Icons.Outlined.SwapVert)
    "scam_horizontal_mirror" -> SettingMeta(R.string.dashcam_rear_mirror, Icons.Outlined.SwapHoriz)
    "mic_switch" -> SettingMeta(R.string.dashcam_mic, Icons.Outlined.Mic, R.string.dashcam_mic_sub)
    "speaker_turn" -> SettingMeta(R.string.dashcam_volume, Icons.Outlined.VolumeUp)
    "start_sound_switch" -> SettingMeta(R.string.dashcam_start_sound, Icons.Outlined.NotificationsActive)
    "screen_dormant" -> SettingMeta(R.string.dashcam_dormant, Icons.Outlined.ScreenLockPortrait, R.string.dashcam_dormant_sub)
    "screen_brightness_level" -> SettingMeta(R.string.dashcam_brightness, Icons.Outlined.Brightness6)
    "gsensor_mode_100level" -> SettingMeta(R.string.dashcam_gsensor, Icons.Outlined.CarCrash, R.string.dashcam_level_sub)
    "event_before_time" -> SettingMeta(R.string.dashcam_event_before, Icons.Outlined.FastRewind)
    "event_after_time" -> SettingMeta(R.string.dashcam_event_after, Icons.Outlined.FastForward)
    "parking_mode_switch" -> SettingMeta(R.string.dashcam_parking, Icons.Outlined.LocalParking, R.string.dashcam_parking_sub)
    "parking_status" -> SettingMeta(R.string.dashcam_parking_mode, Icons.Outlined.Timelapse)
    "parking_power_mgr" -> SettingMeta(R.string.dashcam_parking_power, Icons.Outlined.BatteryAlert, R.string.dashcam_parking_power_sub)
    "gsensor_mode_parking_100level" -> SettingMeta(R.string.dashcam_parking_gsensor, Icons.Outlined.Vibration, R.string.dashcam_level_sub)
    else -> SettingMeta(R.string.dashcam_other, Icons.Outlined.Tune)
}

private val DashcamSection.titleRes: Int
    get() = when (this) {
        DashcamSection.Recording -> R.string.dashcam_section_recording
        DashcamSection.Audio -> R.string.dashcam_section_audio
        DashcamSection.Display -> R.string.dashcam_section_display
        DashcamSection.Events -> R.string.dashcam_section_events
        DashcamSection.Parking -> R.string.dashcam_section_parking
    }

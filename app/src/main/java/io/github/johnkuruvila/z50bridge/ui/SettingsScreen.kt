package io.github.johnkuruvila.z50bridge.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoCameraBack
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.WifiLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.johnkuruvila.z50bridge.CameraResult
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.SpeedTestProgress
import io.github.johnkuruvila.z50bridge.SpeedTestResult
import io.github.johnkuruvila.z50bridge.camera.CameraStatus
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.ThemeMode
import io.github.johnkuruvila.z50bridge.data.formatTransferStamp
import io.github.johnkuruvila.z50bridge.navigation.SettingsSection
import io.github.johnkuruvila.z50bridge.net.CameraWifiStatus
import io.github.johnkuruvila.z50bridge.net.NearbyWifi
import io.github.johnkuruvila.z50bridge.net.WifiChoice
import io.github.johnkuruvila.z50bridge.net.buildWifiChoices
import io.github.johnkuruvila.z50bridge.net.hasBackgroundLocation
import io.github.johnkuruvila.z50bridge.net.normalizeSsid
import io.github.johnkuruvila.z50bridge.net.toggleSsid
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import io.github.johnkuruvila.z50bridge.ui.common.formatClock
import io.github.johnkuruvila.z50bridge.ui.common.shareDiagnostics
import io.github.johnkuruvila.z50bridge.ui.components.Chevron
import io.github.johnkuruvila.z50bridge.ui.components.GroupItem
import io.github.johnkuruvila.z50bridge.ui.components.ListGroup
import io.github.johnkuruvila.z50bridge.ui.components.SwitchItem
import io.github.johnkuruvila.z50bridge.ui.components.Z50TopBar
import io.github.johnkuruvila.z50bridge.ui.theme.Numeric
import io.github.johnkuruvila.z50bridge.ui.theme.signals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A text setting being edited in the dialog. */
private data class TextEdit(
    val title: String,
    val initial: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val password: Boolean = false,
    val help: String? = null,
    val validate: (String) -> String? = { null },
    val onConfirm: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    section: SettingsSection?,
    settingsFlow: Flow<BridgeSettings>,
    stagedBytes: Long,
    freeBytes: Long,
    cameraWifiStatus: CameraWifiStatus,
    cameraReachable: Boolean,
    cameraStatus: CameraStatus?,
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenDashcam: () -> Unit,
    onSave: (BridgeSettings) -> Unit,
    onTest: suspend (BridgeSettings) -> String,
    onOpenSetup: () -> Unit,
    onClearHistory: () -> Unit,
    buildDiagnostics: suspend () -> String,
    onLocationGranted: () -> Unit = {},
    connectedWifi: String? = null,
    seenWifi: Map<String, Long> = emptyMap(),
    nearbyWifi: () -> Flow<List<NearbyWifi>> = { emptyFlow() },
    onConnectDirect: () -> Unit = {},
    onSetWifiChannel: suspend (Int) -> CameraResult<Boolean> = { CameraResult.Offline },
    runSpeedTest: suspend ((SpeedTestProgress) -> Unit) -> CameraResult<SpeedTestResult> = { CameraResult.Offline },
) {
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = BridgeSettings())
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var edit by remember { mutableStateOf<TextEdit?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var showSpeedTest by remember { mutableStateOf(false) }
    var fineLocationGranted by remember { mutableStateOf(hasFineLocation(context)) }
    var backgroundLocationGranted by remember { mutableStateOf(hasBackgroundLocation(context)) }
    var locationOn by remember { mutableStateOf(locationEnabled(context)) }
    // These can change in system settings while this screen is in the background.
    LifecycleResumeEffect(Unit) {
        fineLocationGranted = hasFineLocation(context)
        backgroundLocationGranted = hasBackgroundLocation(context)
        locationOn = locationEnabled(context)
        onPauseOrDispose {}
    }
    val nearby by remember(section, fineLocationGranted) {
        if (section == SettingsSection.OffloadNetworks && fineLocationGranted) nearbyWifi() else flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val locationDenied = stringResource(R.string.permission_location_denied)
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        fineLocationGranted = granted
        if (granted) onLocationGranted()
        else AppToasts.show(context, locationDenied)
    }
    // Android 11+ answers this request with its own settings page. Once the
    // user has said no it stops showing it, so fall back to the app's page.
    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        backgroundLocationGranted = granted
        if (!granted) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
    }
    val connectionFailed = stringResource(R.string.settings_connection_failed)
    val shareFailed = stringResource(R.string.diagnostics_share_failed)
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun save(next: BridgeSettings) = onSave(next)
    val notSet = stringResource(R.string.settings_not_set)
    val set = stringResource(R.string.settings_set)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Z50TopBar(
                title = stringResource(section?.titleRes ?: R.string.title_settings),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (section == null) {
                item(key = "root") {
                    SettingsRoot(
                        settings = settings,
                        stagedBytes = stagedBytes,
                        freeBytes = freeBytes,
                        cameraReachable = cameraReachable,
                        cameraStatus = cameraStatus,
                        onOpenSection = onOpenSection,
                        onOpenDashcam = onOpenDashcam,
                    )
                }
            }

            // ---------------------------------------------------------- homelab
            if (section == SettingsSection.Offload) item(key = "homelab") {
                ListGroup(title = stringResource(R.string.settings_section_homelab)) {
                    GroupItem(
                        title = stringResource(R.string.settings_ingest_host),
                        supporting = settings.destHost.ifBlank { notSet },
                        icon = Icons.Outlined.Dns,
                        onClick = {
                            edit = TextEdit(
                                title = resources.getString(R.string.settings_ingest_host),
                                initial = settings.destHost,
                                keyboardType = KeyboardType.Uri,
                                help = resources.getString(R.string.setup_host_help),
                                validate = { if (it.isBlank()) resources.getString(R.string.setup_host_error) else null },
                                onConfirm = { save(settings.copy(destHost = it.trim())) },
                            )
                        },
                        trailing = { Chevron() },
                    )
                    GroupItem(
                        title = stringResource(R.string.settings_ingest_port),
                        supporting = settings.destPort.toString(),
                        icon = Icons.Outlined.Router,
                        onClick = {
                            edit = TextEdit(
                                title = resources.getString(R.string.settings_ingest_port),
                                initial = settings.destPort.toString(),
                                keyboardType = KeyboardType.Number,
                                validate = {
                                    if (it.toIntOrNull()?.takeIf { p -> p in 1..65535 } == null) {
                                        resources.getString(R.string.setup_port_error)
                                    } else {
                                        null
                                    }
                                },
                                onConfirm = { value -> value.toIntOrNull()?.let { save(settings.copy(destPort = it)) } },
                            )
                        },
                        trailing = { Chevron() },
                    )
                    GroupItem(
                        title = stringResource(R.string.settings_auth_token),
                        supporting = if (settings.destToken.isBlank()) notSet else set,
                        icon = Icons.Outlined.Key,
                        onClick = {
                            edit = TextEdit(
                                title = resources.getString(R.string.settings_auth_token),
                                initial = settings.destToken,
                                password = true,
                                help = resources.getString(R.string.setup_token_help),
                                onConfirm = { save(settings.copy(destToken = it.trim())) },
                            )
                        },
                        trailing = { Chevron() },
                    )
                    GroupItem(
                        title = stringResource(if (testing) R.string.settings_testing else R.string.settings_test_ingest),
                        supporting = testResult?.second ?: stringResource(R.string.settings_test_ingest_sub),
                        supportingColor = when (testResult?.first) {
                            true -> MaterialTheme.signals.online
                            false -> MaterialTheme.colorScheme.error
                            null -> androidx.compose.ui.graphics.Color.Unspecified
                        },
                        icon = when (testResult?.first) {
                            true -> Icons.Outlined.CheckCircle
                            false -> Icons.Outlined.ErrorOutline
                            null -> Icons.Outlined.NetworkCheck
                        },
                        iconTint = when (testResult?.first) {
                            true -> MaterialTheme.signals.online
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = try {
                                    true to onTest(settings)
                                } catch (exc: Exception) {
                                    val reason = exc.message ?: connectionFailed
                                    AppToasts.show(context, reason)
                                    false to reason
                                } finally {
                                    testing = false
                                }
                            }
                        },
                        trailing = if (testing) {
                            { LoadingIndicator(modifier = Modifier.size(32.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }

            // ---------------------------------------------------------- camera
            if (section == SettingsSection.Camera) item(key = "camera") {
                ListGroup(title = stringResource(R.string.settings_section_camera)) {
                    GroupItem(
                        title = stringResource(R.string.settings_camera_host),
                        supporting = settings.cameraHost,
                        icon = Icons.Outlined.Videocam,
                        onClick = {
                            edit = TextEdit(
                                title = resources.getString(R.string.settings_camera_host),
                                initial = settings.cameraHost,
                                keyboardType = KeyboardType.Uri,
                                help = resources.getString(R.string.settings_camera_host_help),
                                validate = { if (it.isBlank()) resources.getString(R.string.settings_camera_host_error) else null },
                                onConfirm = { save(settings.copy(cameraHost = it.trim())) },
                            )
                        },
                        trailing = { Chevron() },
                    )
                    SwitchItem(
                        title = stringResource(R.string.settings_camera_wifi_auto),
                        supporting = cameraWifiStatusLabel(cameraWifiStatus, settings.cameraWifiAutoConnect, cameraReachable),
                        icon = Icons.Outlined.Wifi,
                        checked = settings.cameraWifiAutoConnect,
                        onCheckedChange = { save(settings.copy(cameraWifiAutoConnect = it)) },
                    )
                    // Without it Android hides scan results from the app in the
                    // background, so auto-connect cannot tell the camera is in range.
                    if (settings.cameraWifiAutoConnect && Build.VERSION.SDK_INT >= 29 && !backgroundLocationGranted) {
                        GroupItem(
                            title = stringResource(R.string.settings_camera_wifi_location),
                            supporting = stringResource(R.string.settings_camera_wifi_location_sub),
                            supportingColor = MaterialTheme.colorScheme.onErrorContainer,
                            container = MaterialTheme.colorScheme.errorContainer,
                            icon = Icons.Outlined.LocationOn,
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            iconContainer = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                            onClick = {
                                if (fineLocationGranted) {
                                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                } else {
                                    locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                        )
                    }
                    GroupItem(
                        title = stringResource(R.string.settings_camera_ssid),
                        supporting = settings.cameraSsid.ifBlank { notSet },
                        icon = Icons.Outlined.WifiLock,
                        onClick = {
                            edit = TextEdit(
                                title = resources.getString(R.string.settings_camera_ssid),
                                initial = settings.cameraSsid,
                                help = resources.getString(R.string.settings_camera_ssid_help),
                                onConfirm = { save(settings.copy(cameraSsid = it.trim())) },
                            )
                        },
                        trailing = { Chevron() },
                    )
                    GroupItem(
                        title = stringResource(R.string.settings_camera_wifi_password),
                        supporting = if (settings.cameraWifiPassword.isBlank()) stringResource(R.string.settings_open_network) else set,
                        icon = Icons.Outlined.Password,
                        onClick = {
                            edit = TextEdit(
                                title = resources.getString(R.string.settings_camera_wifi_password),
                                initial = settings.cameraWifiPassword,
                                password = true,
                                help = resources.getString(R.string.settings_camera_wifi_help),
                                onConfirm = { save(settings.copy(cameraWifiPassword = it)) },
                            )
                        },
                        trailing = { Chevron() },
                    )
                    // WifiNetworkSpecifier needs Android 10.
                    if (Build.VERSION.SDK_INT >= 29) {
                        // A new request while connected would drop the working connection.
                        GroupItem(
                            title = stringResource(R.string.settings_camera_connect_now),
                            supporting = stringResource(
                                if (cameraReachable) R.string.settings_camera_connect_now_connected else R.string.settings_camera_connect_now_sub,
                            ),
                            icon = Icons.Outlined.NetworkCheck,
                            onClick = if (cameraReachable) null else onConnectDirect,
                        )
                    }
                    GroupItem(
                        title = stringResource(R.string.settings_wifi_channel),
                        supporting = stringResource(R.string.settings_wifi_channel_sub),
                        icon = Icons.Outlined.Router,
                        onClick = { showChannelDialog = true },
                        trailing = { Chevron() },
                    )
                }
            }
            if (section == SettingsSection.Camera) item(key = "camera-clock") {
                ListGroup(title = stringResource(R.string.settings_section_camera_clock)) {
                    SwitchItem(
                        title = stringResource(R.string.settings_clock_sync),
                        supporting = clockSummary(cameraStatus, settings.syncCameraClock),
                        icon = Icons.Outlined.Schedule,
                        checked = settings.syncCameraClock,
                        onCheckedChange = { save(settings.copy(syncCameraClock = it)) },
                    )
                }
            }

            // ---------------------------------------------------------- sync
            if (section == SettingsSection.Sync) item(key = "sync") {
                ListGroup(title = stringResource(R.string.settings_section_recording)) {
                    SwitchItem(
                        title = stringResource(R.string.settings_pull_rear),
                        supporting = stringResource(R.string.settings_pull_rear_sub),
                        icon = Icons.Outlined.VideoCameraBack,
                        checked = settings.pullRear,
                        onCheckedChange = { save(settings.copy(pullRear = it)) },
                    )
                    SwitchItem(
                        title = stringResource(R.string.settings_pull_sidecars),
                        supporting = stringResource(R.string.settings_pull_sidecars_sub),
                        icon = Icons.Outlined.LocationOn,
                        checked = settings.pullGps,
                        onCheckedChange = { save(settings.copy(pullGps = it)) },
                    )
                    SwitchItem(
                        title = stringResource(R.string.settings_skip_open),
                        supporting = stringResource(R.string.settings_skip_open_sub),
                        icon = Icons.Outlined.SkipNext,
                        checked = settings.skipOpenClip,
                        onCheckedChange = { save(settings.copy(skipOpenClip = it)) },
                    )
                    SwitchItem(
                        title = stringResource(R.string.settings_prioritize),
                        supporting = stringResource(R.string.settings_prioritize_sub),
                        icon = Icons.Outlined.RocketLaunch,
                        checked = settings.prioritizeLatest,
                        onCheckedChange = { save(settings.copy(prioritizeLatest = it)) },
                    )
                    SyncWindowItem(
                        minutes = settings.syncWindowMinutes,
                        onChange = { save(settings.copy(syncWindowMinutes = it)) },
                    )
                    StreamPickerItem(
                        title = stringResource(R.string.settings_parallel_downloads),
                        icon = Icons.Outlined.CallSplit,
                        value = settings.parallelDownloads,
                        options = 1..4,
                        onChange = { save(settings.copy(parallelDownloads = it)) },
                    )
                }
            }

            // ---------------------------------------------------------- offload
            if (section == SettingsSection.Offload) item(key = "offload") {
                ListGroup(title = stringResource(R.string.settings_section_offload)) {
                    StreamPickerItem(
                        title = stringResource(R.string.settings_parallel_uploads),
                        icon = Icons.Outlined.Speed,
                        value = settings.parallelUploads,
                        options = 1..5,
                        onChange = { save(settings.copy(parallelUploads = it)) },
                    )
                    GroupItem(
                        title = stringResource(R.string.settings_offload_allowlist),
                        supporting = if (settings.offloadWifiAllowlist.isEmpty()) {
                            stringResource(R.string.settings_offload_any_network)
                        } else {
                            settings.offloadWifiAllowlist.sorted().joinToString(", ")
                        },
                        icon = Icons.Outlined.Wifi,
                        onClick = { onOpenSection(SettingsSection.OffloadNetworks) },
                        trailing = { Chevron() },
                    )
                    if (settings.offloadWifiAllowlist.isNotEmpty() && !fineLocationGranted) {
                        GroupItem(
                            title = stringResource(R.string.settings_offload_allowlist_grant),
                            supporting = stringResource(R.string.settings_offload_allowlist_location),
                            supportingColor = MaterialTheme.colorScheme.onErrorContainer,
                            container = MaterialTheme.colorScheme.errorContainer,
                            icon = Icons.Outlined.LocationOn,
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            iconContainer = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                            onClick = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                        )
                    }
                }
            }

            // ---------------------------------------------------------- offload networks
            if (section == SettingsSection.OffloadNetworks) item(key = "offload-networks") {
                val choices = buildWifiChoices(
                    allowlist = settings.offloadWifiAllowlist,
                    connected = connectedWifi,
                    seen = seenWifi,
                    nearby = nearby,
                    cameraSsid = settings.cameraSsid,
                )
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_offload_networks_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (!fineLocationGranted) {
                        ListGroup {
                            GroupItem(
                                title = stringResource(R.string.settings_offload_allowlist_grant),
                                supporting = stringResource(R.string.settings_offload_allowlist_location),
                                supportingColor = MaterialTheme.colorScheme.onErrorContainer,
                                container = MaterialTheme.colorScheme.errorContainer,
                                icon = Icons.Outlined.LocationOn,
                                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                                iconContainer = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                                onClick = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                            )
                        }
                    } else if (!locationOn) {
                        ListGroup {
                            GroupItem(
                                title = stringResource(R.string.settings_offload_networks_location_off),
                                supporting = stringResource(R.string.settings_offload_networks_location_off_sub),
                                icon = Icons.Outlined.LocationOn,
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                    }
                                },
                                trailing = { Chevron() },
                            )
                        }
                    }
                    ListGroup(title = stringResource(R.string.settings_offload_networks_list)) {
                        if (choices.isEmpty()) {
                            GroupItem(
                                title = stringResource(R.string.settings_offload_networks_empty),
                                supporting = stringResource(R.string.settings_offload_networks_empty_sub),
                                icon = Icons.Outlined.WifiFind,
                                role = null,
                            )
                        }
                        choices.forEach { choice ->
                            WifiChoiceItem(
                                choice = choice,
                                onToggle = { on ->
                                    save(settings.copy(offloadWifiAllowlist = toggleSsid(settings.offloadWifiAllowlist, choice.ssid, on)))
                                },
                            )
                        }
                        GroupItem(
                            title = stringResource(R.string.settings_offload_networks_add),
                            supporting = stringResource(R.string.settings_offload_networks_add_sub),
                            icon = Icons.Outlined.Add,
                            onClick = {
                                edit = TextEdit(
                                    title = resources.getString(R.string.settings_offload_networks_add),
                                    initial = "",
                                    help = resources.getString(R.string.settings_offload_networks_add_help),
                                    validate = {
                                        if (normalizeSsid(it) == null) resources.getString(R.string.settings_offload_networks_add_error) else null
                                    },
                                    onConfirm = {
                                        save(settings.copy(offloadWifiAllowlist = toggleSsid(settings.offloadWifiAllowlist, it, on = true)))
                                    },
                                )
                            },
                        )
                    }
                }
            }

            // ---------------------------------------------------------- automation
            if (section == SettingsSection.Sync) item(key = "automation") {
                ListGroup(title = stringResource(R.string.settings_section_automation)) {
                    PollIntervalItem(
                        seconds = settings.pollSeconds,
                        onCommit = { save(settings.copy(pollSeconds = it)) },
                    )
                }
            }

            // ---------------------------------------------------------- appearance
            if (section == SettingsSection.Appearance) item(key = "appearance") {
                ListGroup(title = stringResource(R.string.settings_section_appearance)) {
                    GroupItem(
                        title = stringResource(R.string.settings_theme),
                        supporting = stringResource(R.string.settings_theme_help),
                        icon = Icons.Outlined.Palette,
                        below = {
                            ThemePicker(
                                selected = settings.themeMode,
                                onSelect = { save(settings.copy(themeMode = it)) },
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        },
                    )
                }
            }

            // ---------------------------------------------------------- storage
            if (section == SettingsSection.Storage) item(key = "storage") {
                ListGroup(title = stringResource(R.string.settings_section_data)) {
                    GroupItem(
                        title = stringResource(R.string.settings_staged_title),
                        supporting = stringResource(R.string.settings_staged_summary, formatBytes(stagedBytes), formatBytes(freeBytes)),
                        icon = Icons.Outlined.SdStorage,
                    )
                    GroupItem(
                        title = stringResource(R.string.settings_reset_history),
                        supporting = stringResource(R.string.settings_reset_history_sub),
                        icon = Icons.Outlined.DeleteForever,
                        iconTint = MaterialTheme.colorScheme.error,
                        iconContainer = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        onClick = { showResetDialog = true },
                    )
                }
            }

            // ---------------------------------------------------------- integrations
            if (section == SettingsSection.Integrations) item(key = "integrations") {
                ListGroup(title = stringResource(R.string.settings_section_integrations)) {
                    IntegrationItem("io.github.johnkuruvila.z50bridge.SYNC", stringResource(R.string.settings_intent_sync_desc), context, snackbar)
                    IntegrationItem("io.github.johnkuruvila.z50bridge.OFFLOAD", stringResource(R.string.settings_intent_offload_desc), context, snackbar)
                    GroupItem(
                        title = stringResource(R.string.settings_tasker_title),
                        supporting = stringResource(R.string.settings_tasker_help),
                        icon = Icons.Outlined.Info,
                    )
                }
            }

            // ---------------------------------------------------------- about
            if (section == SettingsSection.About) item(key = "about") {
                val version = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                        ?: resources.getString(R.string.settings_unknown_version)
                }
                ListGroup(title = stringResource(R.string.settings_section_about)) {
                    GroupItem(
                        title = stringResource(R.string.settings_setup_wizard),
                        supporting = stringResource(R.string.settings_setup_wizard_sub),
                        icon = Icons.Outlined.AutoAwesome,
                        onClick = onOpenSetup,
                        trailing = { Chevron() },
                    )
                    GroupItem(
                        title = stringResource(R.string.diagnostics_share),
                        supporting = stringResource(R.string.diagnostics_share_sub),
                        icon = Icons.Outlined.BugReport,
                        onClick = {
                            scope.launch {
                                if (!shareDiagnostics(context, buildDiagnostics())) AppToasts.show(context, shareFailed)
                            }
                        },
                    )
                    GroupItem(
                        title = stringResource(R.string.speed_test_title),
                        supporting = stringResource(R.string.speed_test_sub),
                        icon = Icons.Outlined.Speed,
                        onClick = { showSpeedTest = true },
                    )
                    GroupItem(
                        title = stringResource(R.string.settings_about_title),
                        supporting = stringResource(R.string.settings_about_version, version),
                        icon = Icons.Outlined.Sensors,
                        below = {
                            Text(
                                text = stringResource(R.string.settings_about_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 56.dp, top = 8.dp),
                            )
                        },
                    )
                }
            }
        }
    }

    edit?.let { current ->
        TextEditDialog(edit = current, onDismiss = { edit = null })
    }

    if (showResetDialog) {
        val resetDone = stringResource(R.string.settings_reset_done)
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                Button(onClick = {
                    onClearHistory()
                    showResetDialog = false
                    scope.launch { snackbar.showSnackbar(resetDone) }
                }) { Text(stringResource(R.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showSpeedTest) {
        SpeedTestDialog(
            runSpeedTest = runSpeedTest,
            onShareDiagnostics = {
                scope.launch {
                    if (!shareDiagnostics(context, buildDiagnostics())) AppToasts.show(context, shareFailed)
                }
            },
            onDismiss = { showSpeedTest = false },
        )
    }

    if (showChannelDialog) {
        val channelSetMsg = stringResource(R.string.settings_wifi_channel_changed)
        val channelUnconfirmedMsg = stringResource(R.string.settings_wifi_channel_unconfirmed)
        val channelOfflineMsg = stringResource(R.string.dashcam_offline_title)
        val channelBusyMsg = stringResource(R.string.dashcam_busy_title)
        var selected by remember { mutableStateOf<Int?>(null) }
        var changing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!changing) showChannelDialog = false },
            icon = { Icon(Icons.Outlined.Router, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_wifi_channel_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_wifi_channel_dialog_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.selectableGroup()) {
                        listOf(
                            1 to R.string.settings_wifi_channel_1,
                            6 to R.string.settings_wifi_channel_6,
                            11 to R.string.settings_wifi_channel_11,
                        ).forEach { (ch, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = selected == ch,
                                        enabled = !changing,
                                        role = Role.RadioButton,
                                        onClick = { selected = ch },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == ch, onClick = null, enabled = !changing)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (changing) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected != null && !changing,
                    onClick = {
                        val ch = selected ?: return@TextButton
                        changing = true
                        scope.launch {
                            val result = onSetWifiChannel(ch)
                            changing = false
                            showChannelDialog = false
                            when (result) {
                                is CameraResult.Ok ->
                                    snackbar.showSnackbar((if (result.value) channelSetMsg else channelUnconfirmedMsg).format(ch))
                                CameraResult.Offline -> AppToasts.show(context, channelOfflineMsg)
                                CameraResult.Busy -> AppToasts.show(context, channelBusyMsg)
                                is CameraResult.Failed -> AppToasts.show(context, result.message)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_wifi_channel_confirm))
                }
            },
            dismissButton = {
                TextButton(enabled = !changing, onClick = { showChannelDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** A network in the offload picker: ticked rows are the ones offload may use. */
@Composable
private fun WifiChoiceItem(choice: WifiChoice, onToggle: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val tags = buildList {
        if (choice.connected) add(stringResource(R.string.settings_wifi_connected))
        choice.nearbyLevel?.takeIf { !choice.connected }?.let { level ->
            add(
                stringResource(
                    when {
                        level >= -60 -> R.string.settings_wifi_nearby_strong
                        level >= -72 -> R.string.settings_wifi_nearby_good
                        else -> R.string.settings_wifi_nearby_weak
                    },
                ),
            )
        }
        if (!choice.connected) {
            choice.lastSeenMs?.let { add(stringResource(R.string.settings_wifi_last_joined, formatTransferStamp(it))) }
        }
        if (isEmpty()) add(stringResource(R.string.settings_wifi_not_in_range))
    }
    GroupItem(
        title = choice.ssid,
        supporting = tags.joinToString(" · "),
        icon = when {
            choice.connected || choice.nearbyLevel != null -> Icons.Outlined.Wifi
            choice.lastSeenMs != null -> Icons.Outlined.History
            else -> Icons.Outlined.WifiOff
        },
        iconTint = if (choice.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        iconContainer = if (choice.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        onClick = {
            haptics.performHapticFeedback(if (choice.selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onToggle(!choice.selected)
        },
        role = Role.Checkbox,
        trailing = { Checkbox(checked = choice.selected, onCheckedChange = null) },
    )
}

@Composable
private fun TextEditDialog(edit: TextEdit, onDismiss: () -> Unit) {
    var text by remember(edit) { mutableStateOf(edit.initial) }
    var reveal by remember(edit) { mutableStateOf(false) }
    val error = edit.validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(edit.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
                    supportingText = (error ?: edit.help)?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = edit.keyboardType, imeAction = ImeAction.Done),
                    visualTransformation = if (edit.password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = if (edit.password) {
                        {
                            TextButton(onClick = { reveal = !reveal }) {
                                Text(stringResource(if (reveal) R.string.common_hide else R.string.common_show))
                            }
                        }
                    } else {
                        null
                    },
                    textStyle = if (edit.keyboardType == KeyboardType.Text && !edit.password) {
                        MaterialTheme.typography.bodyLarge
                    } else {
                        Numeric.medium
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    edit.onConfirm(text)
                    onDismiss()
                },
                enabled = error == null && text != edit.initial,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Row with a connected button group for choosing a small count (streams). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StreamPickerItem(
    title: String,
    icon: ImageVector,
    value: Int,
    options: IntRange,
    onChange: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    GroupItem(
        title = title,
        supporting = pluralStringResource(R.plurals.settings_streams, value, value),
        icon = icon,
        below = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                val list = options.toList()
                list.forEachIndexed { index, option ->
                    ToggleButton(
                        checked = value == option,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onChange(option)
                        },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            list.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(option.toString(), style = Numeric.medium)
                    }
                }
            }
        },
    )
}

/** How far back from the newest clip a sync reaches. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncWindowItem(minutes: Int, onChange: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    GroupItem(
        title = stringResource(R.string.settings_sync_window),
        supporting = if (minutes > 0) {
            stringResource(R.string.settings_sync_window_sub_limited, windowLabel(minutes))
        } else {
            stringResource(R.string.settings_sync_window_sub_all)
        },
        icon = Icons.Outlined.History,
        below = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                SYNC_WINDOW_OPTIONS.forEachIndexed { index, option ->
                    ToggleButton(
                        checked = minutes == option,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onChange(option)
                        },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            SYNC_WINDOW_OPTIONS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (option == 0) stringResource(R.string.settings_sync_window_all) else windowLabel(option),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun windowLabel(minutes: Int): String =
    if (minutes % 60 == 0) {
        stringResource(R.string.settings_sync_window_hours, minutes / 60)
    } else {
        stringResource(R.string.settings_sync_window_minutes, minutes)
    }

private val SYNC_WINDOW_OPTIONS = listOf(0, 15, 30, 60, 120)

@Composable
private fun PollIntervalItem(seconds: Int, onCommit: (Int) -> Unit) {
    var slider by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }
    val snapped = ((slider / 10f).roundToInt() * 10).coerceIn(10, 120)
    GroupItem(
        title = stringResource(R.string.settings_poll_interval),
        supporting = stringResource(R.string.settings_poll_interval_sub),
        icon = Icons.Outlined.Timer,
        trailing = { Text(stringResource(R.string.settings_poll_value, snapped), style = Numeric.medium, color = MaterialTheme.colorScheme.primary) },
        below = {
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { onCommit(snapped) },
                valueRange = 10f..120f,
                steps = 10,
                modifier = Modifier.padding(top = 8.dp),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = ThemeMode.entries
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, mode ->
            ToggleButton(
                checked = selected == mode,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(mode)
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = when (mode) {
                            ThemeMode.Dynamic -> Icons.Outlined.AutoAwesome
                            ThemeMode.Brand -> Icons.Outlined.Palette
                            ThemeMode.Light -> Icons.Outlined.LightMode
                            ThemeMode.Dark -> Icons.Outlined.DarkMode
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(mode.label(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun IntegrationItem(action: String, description: String, context: Context, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.settings_copied_action, action)
    val clipLabel = stringResource(R.string.settings_clip_label)
    fun copy() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(clipLabel, action))
        scope.launch { snackbar.showSnackbar(copiedMessage) }
    }
    GroupItem(
        title = description,
        supporting = action,
        onClick = ::copy,
        trailing = {
            IconButton(onClick = ::copy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.settings_copy_intent_cd))
            }
        },
    )
}

@Composable
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.Dynamic -> stringResource(R.string.settings_theme_dynamic)
    ThemeMode.Brand -> stringResource(R.string.settings_theme_brand)
    ThemeMode.Light -> stringResource(R.string.settings_theme_light)
    ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
}

@Composable
private fun cameraWifiStatusLabel(status: CameraWifiStatus, enabled: Boolean, cameraReachable: Boolean): String = when {
    !enabled -> stringResource(R.string.settings_camera_wifi_auto_sub)
    status == CameraWifiStatus.Registered && cameraReachable -> stringResource(R.string.settings_camera_wifi_status_connected)
    status == CameraWifiStatus.Off -> stringResource(R.string.settings_camera_wifi_status_off)
    status == CameraWifiStatus.Pending -> stringResource(R.string.settings_camera_wifi_status_pending)
    status == CameraWifiStatus.Registered -> stringResource(R.string.settings_camera_wifi_status_registered)
    status == CameraWifiStatus.NeedsAndroid -> stringResource(R.string.settings_camera_wifi_status_needs_android)
    status == CameraWifiStatus.Blocked -> stringResource(R.string.settings_camera_wifi_status_blocked)
    else -> stringResource(R.string.settings_camera_wifi_status_failed)
}

private fun locationEnabled(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
private fun clockSummary(status: CameraStatus?, enabled: Boolean): String {
    if (!enabled) return stringResource(R.string.settings_clock_sync_off)
    val synced = status?.clockSyncedAtMs
    return if (synced != null) {
        stringResource(R.string.settings_clock_synced_at, formatClock(synced))
    } else {
        stringResource(R.string.settings_clock_sync_sub)
    }
}

private val SettingsSection.titleRes: Int
    get() = when (this) {
        SettingsSection.Camera -> R.string.settings_cat_camera
        SettingsSection.Sync -> R.string.settings_cat_sync
        SettingsSection.Offload -> R.string.settings_cat_offload
        SettingsSection.OffloadNetworks -> R.string.settings_offload_allowlist
        SettingsSection.Storage -> R.string.settings_cat_storage
        SettingsSection.Appearance -> R.string.settings_cat_appearance
        SettingsSection.Integrations -> R.string.settings_cat_integrations
        SettingsSection.About -> R.string.settings_cat_about
    }

/**
 * Category list. Each row states the current value of what it controls, so
 * most questions ("is auto-join on?", "where does it offload?") are answered
 * without opening the page.
 */
@Composable
private fun SettingsRoot(
    settings: BridgeSettings,
    stagedBytes: Long,
    freeBytes: Long,
    cameraReachable: Boolean,
    cameraStatus: CameraStatus?,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenDashcam: () -> Unit,
) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    val on = stringResource(R.string.common_on)
    val off = stringResource(R.string.common_off)
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ListGroup(title = stringResource(R.string.settings_group_camera)) {
            CategoryItem(
                title = stringResource(R.string.settings_cat_camera),
                summary = stringResource(
                    R.string.settings_cat_camera_sum,
                    settings.cameraHost,
                    if (settings.cameraWifiAutoConnect) on else off,
                ),
                icon = Icons.Outlined.Wifi,
                tint = MaterialTheme.signals.sync,
                onClick = { onOpenSection(SettingsSection.Camera) },
            )
            CategoryItem(
                title = stringResource(R.string.settings_cat_dashcam),
                summary = when {
                    cameraReachable -> listOfNotNull(cameraStatus?.model, cameraStatus?.firmware)
                        .joinToString(" · ")
                        .ifEmpty { stringResource(R.string.settings_cat_dashcam_sum) }
                    else -> stringResource(R.string.settings_cat_dashcam_offline)
                },
                icon = Icons.Outlined.Tune,
                tint = MaterialTheme.signals.sync,
                onClick = onOpenDashcam,
            )
        }
        ListGroup(title = stringResource(R.string.settings_group_transfers)) {
            CategoryItem(
                title = stringResource(R.string.settings_cat_sync),
                summary = if (settings.autoWatch) {
                    pluralStringResource(R.plurals.settings_cat_sync_sum_watch, settings.pollSeconds, settings.pollSeconds)
                } else {
                    stringResource(R.string.settings_cat_sync_sum_manual)
                },
                icon = Icons.Outlined.CloudDownload,
                tint = MaterialTheme.signals.sync,
                onClick = { onOpenSection(SettingsSection.Sync) },
            )
            CategoryItem(
                title = stringResource(R.string.settings_cat_offload),
                summary = stringResource(
                    R.string.settings_cat_offload_sum,
                    "${settings.destHost}:${settings.destPort}",
                    if (settings.offloadWifiAllowlist.isEmpty()) {
                        stringResource(R.string.settings_offload_any_network_short)
                    } else {
                        pluralStringResource(
                            R.plurals.settings_offload_networks,
                            settings.offloadWifiAllowlist.size,
                            settings.offloadWifiAllowlist.size,
                        )
                    },
                ),
                icon = Icons.Outlined.CloudUpload,
                tint = MaterialTheme.signals.offload,
                onClick = { onOpenSection(SettingsSection.Offload) },
            )
            CategoryItem(
                title = stringResource(R.string.settings_cat_storage),
                summary = stringResource(R.string.settings_cat_storage_sum, formatBytes(stagedBytes), formatBytes(freeBytes)),
                icon = Icons.Outlined.SdStorage,
                tint = MaterialTheme.signals.offload,
                onClick = { onOpenSection(SettingsSection.Storage) },
            )
        }
        ListGroup(title = stringResource(R.string.settings_group_app)) {
            CategoryItem(
                title = stringResource(R.string.settings_cat_appearance),
                summary = settings.themeMode.label(),
                icon = Icons.Outlined.Palette,
                tint = MaterialTheme.colorScheme.primary,
                onClick = { onOpenSection(SettingsSection.Appearance) },
            )
            CategoryItem(
                title = stringResource(R.string.settings_cat_integrations),
                summary = stringResource(R.string.settings_cat_integrations_sum),
                icon = Icons.Outlined.Extension,
                tint = MaterialTheme.colorScheme.primary,
                onClick = { onOpenSection(SettingsSection.Integrations) },
            )
            CategoryItem(
                title = stringResource(R.string.settings_cat_about),
                summary = stringResource(
                    R.string.settings_cat_about_sum,
                    version ?: stringResource(R.string.settings_unknown_version),
                ),
                icon = Icons.Outlined.Info,
                tint = MaterialTheme.colorScheme.primary,
                onClick = { onOpenSection(SettingsSection.About) },
            )
        }
    }
}

@Composable
private fun CategoryItem(
    title: String,
    summary: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    GroupItem(
        title = title,
        supporting = summary,
        icon = icon,
        iconTint = tint,
        iconContainer = tint.copy(alpha = 0.14f),
        onClick = onClick,
        trailing = { Chevron() },
    )
}

package io.github.johnkuruvila.z50bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.johnkuruvila.z50bridge.camera.CameraStatus
import io.github.johnkuruvila.z50bridge.camera.CameraUnreachableException
import io.github.johnkuruvila.z50bridge.camera.DASHCAM_SPECS
import io.github.johnkuruvila.z50bridge.camera.DashcamValue
import io.github.johnkuruvila.z50bridge.camera.DdpaiClient
import io.github.johnkuruvila.z50bridge.camera.DdpaiException
import io.github.johnkuruvila.z50bridge.camera.skipOpenClip
import io.github.johnkuruvila.z50bridge.camera.cameraClockStamp
import io.github.johnkuruvila.z50bridge.camera.cameraLangTag
import io.github.johnkuruvila.z50bridge.camera.flag
import io.github.johnkuruvila.z50bridge.camera.parseCameraClock
import io.github.johnkuruvila.z50bridge.camera.parseCameraStatus
import io.github.johnkuruvila.z50bridge.camera.parseDashcamSettings
import io.github.johnkuruvila.z50bridge.data.ActivityStore
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.DeviceInfo
import io.github.johnkuruvila.z50bridge.data.DiagnosticsMirror
import io.github.johnkuruvila.z50bridge.data.DiagnosticsSendPolicy
import io.github.johnkuruvila.z50bridge.data.DiagnosticsSentMarker
import io.github.johnkuruvila.z50bridge.data.diagnosticsUploadName
import io.github.johnkuruvila.z50bridge.data.HistoryStore
import io.github.johnkuruvila.z50bridge.data.SeenWifiStore
import io.github.johnkuruvila.z50bridge.data.SettingsRepository
import io.github.johnkuruvila.z50bridge.data.TransferStamps
import io.github.johnkuruvila.z50bridge.data.buildDiagnosticsReport
import io.github.johnkuruvila.z50bridge.data.formatTransferStamp
import io.github.johnkuruvila.z50bridge.diag.CameraSample
import io.github.johnkuruvila.z50bridge.diag.DownloadTiming
import io.github.johnkuruvila.z50bridge.diag.RadioReader
import io.github.johnkuruvila.z50bridge.diag.RadioState
import io.github.johnkuruvila.z50bridge.diag.TransferRecorder
import io.github.johnkuruvila.z50bridge.diag.TransferReportStore
import io.github.johnkuruvila.z50bridge.diag.WifiLinkReader
import io.github.johnkuruvila.z50bridge.diag.WifiLinkSample
import io.github.johnkuruvila.z50bridge.diag.parseCameraSample
import io.github.johnkuruvila.z50bridge.net.CameraAutoConnect
import io.github.johnkuruvila.z50bridge.net.CameraWifi
import io.github.johnkuruvila.z50bridge.net.CameraWifiConfig
import io.github.johnkuruvila.z50bridge.net.CameraWifiStatus
import io.github.johnkuruvila.z50bridge.net.NearbyWifi
import io.github.johnkuruvila.z50bridge.net.NetworkHint
import io.github.johnkuruvila.z50bridge.net.NetworkMonitor
import io.github.johnkuruvila.z50bridge.net.NetworkUse
import io.github.johnkuruvila.z50bridge.net.WifiScanner
import io.github.johnkuruvila.z50bridge.net.hasBackgroundLocation
import io.github.johnkuruvila.z50bridge.net.isCameraSsid
import io.github.johnkuruvila.z50bridge.net.offloadAllowed
import io.github.johnkuruvila.z50bridge.navigation.TopDestination
import io.github.johnkuruvila.z50bridge.sync.OffloadEngine
import io.github.johnkuruvila.z50bridge.sync.ProgressThrottle
import io.github.johnkuruvila.z50bridge.sync.StagedClipScanner
import io.github.johnkuruvila.z50bridge.sync.SyncEngine
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import io.github.johnkuruvila.z50bridge.widget.Z50Widget
import java.io.File
import java.time.ZonedDateTime
import kotlin.math.abs
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class BridgeController(context: Context) {
    private val app = context.applicationContext
    val settings = SettingsRepository(app)
    private val networks = NetworkMonitor(app)
    private val wifiScanner = WifiScanner(app)
    private val offload = OffloadEngine()
    private val cameraWifi = CameraWifi(app) { status -> onCameraWifiApproval(status) }
    private val mutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val stagingDir: File = File(app.getExternalFilesDir(null), "DashcamStaging").apply { mkdirs() }
    private val trashDir: File = File(stagingDir, TRASH_DIR)
    private val history = HistoryStore(File(stagingDir, LEDGER_FILE))
    private val activityStore = ActivityStore(File(app.filesDir, "activity.json"))
    private val syncEngine = SyncEngine(stagingDir, history)
    private val wifiLinks = WifiLinkReader(app)
    private val radios = RadioReader(app)
    private val transferReports = TransferReportStore(File(app.filesDir, "transfer-reports.json"))
    private val stamps = TransferStamps(File(app.filesDir, "transfer-stamps.txt"))
    private val sessionMarker = File(app.filesDir, SESSION_MARKER)
    private val seenWifiStore = SeenWifiStore(File(app.filesDir, "seen-wifi.txt"))

    /** The Wi-Fi network the phone is on now, for the offload network picker. */
    val connectedWifi: StateFlow<String?> = networks.connectedWifi

    /** Wi-Fi networks seen while the app ran, with when each was last seen. */
    val seenWifi: StateFlow<Map<String, Long>> = seenWifiStore.networks

    private val _state = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = _state

    private var watchJob: Job? = null

    /** The coroutine running the current transfer, so Stop can cancel it directly. */
    @Volatile
    private var sessionJob: Job? = null

    /** Set by Stop; late progress callbacks from the unwinding transfer are ignored. */
    @Volatile
    private var stopRequested = false

    /** Latest saved settings, read by running transfers so a stream-count change applies live. */
    @Volatile
    private var liveSettings: BridgeSettings? = null

    /** True while the live view screen is open; the watcher leaves the camera alone. */
    @Volatile
    private var liveViewActive = false

    @Volatile
    private var cameraRequestActive = false

    /** The clock is checked once per camera connection, not on every poll. */
    @Volatile
    private var clockCheckedThisConnection = false

    /** The heat warning goes to Activity once per camera connection. */
    @Volatile
    private var heatLoggedThisConnection = false

    /** When the camera status was last read, so idle polls can reuse it. */
    @Volatile
    private var statusReadAtMs = 0L

    /** The last "nothing new" logged on this connection; repeats are left out. */
    @Volatile
    private var idleNote: String? = null

    /** True while Dashcam settings is open; like live view, it pauses the watcher's syncs. */
    @Volatile
    private var dashcamScreenOpen = false

    private val cameraScreenOpen: Boolean
        get() = liveViewActive || dashcamScreenOpen

    /** The network the running transfer uses, so Stop knows which connection to pause. */
    @Volatile
    private var sessionNet: Network? = null

    /**
     * Set by Stop during a sync: the camera connection it ran on. The watcher
     * starts no sync while the camera is reached over that same connection;
     * the next connection (the next drive) has a new network and syncs again.
     */
    @Volatile
    private var syncPausedOn: Network? = null

    /** Like [syncPausedOn], for offload and the network it ran on. */
    @Volatile
    private var offloadPausedOn: Network? = null

    /** Since when the phone has been on the camera's Wi-Fi without the camera answering. */
    @Volatile
    private var cameraSilentSinceMs: Long? = null

    @Volatile
    private var cameraSilentLogged = false

    /** Whether the last finished probe reached the camera; probes overlap, so connect/disconnect is logged off this. */
    private val cameraUp = java.util.concurrent.atomic.AtomicBoolean(false)

    private val diagnosticsMirror = DiagnosticsMirror(app, File(app.filesDir, "diagnostics-mirror.txt"))
    private val diagnosticsSent = DiagnosticsSentMarker(File(app.filesDir, "diagnostics-sent.txt"))
    private val diagnosticsPolicy = DiagnosticsSendPolicy()

    @Volatile
    private var diagnosticsFailureLogged = false

    private val busy: Boolean
        get() = _state.value.phase == BridgePhase.Syncing || _state.value.phase == BridgePhase.Offloading

    init {
        trashDir.listFiles()?.forEach { it.delete() }
        _state.update {
            it.copy(
                activity = activityStore.load(),
                lastSync = formatTransferStamp(stamps.lastSyncMs),
                lastOffload = formatTransferStamp(stamps.lastOffloadMs),
            )
        }
        reportInterruptedSession()
        ioScope.launch {
            settings.flow.collect {
                liveSettings = it
                networks.cameraSsid = it.cameraSsid
            }
        }
        // Today's report in Downloads, a minute after Activity last changed.
        // It outlives an uninstall, unlike everything in the app's own storage.
        ioScope.launch {
            _state.map { it.activity.firstOrNull()?.timeMs }.distinctUntilChanged().collectLatest { newest ->
                if (newest == null) return@collectLatest
                delay(DIAGNOSTICS_MIRROR_DELAY_MS)
                runCatching { diagnosticsMirror.write(diagnosticsReport(), System.currentTimeMillis()) }
            }
        }
        // Re-probe as soon as a network appears or disappears so the dashboard
        // and the action buttons react without waiting for the poll interval.
        ioScope.launch {
            networks.changes.collectLatest {
                delay(NETWORK_SETTLE_MS)
                probe(settings.current())
            }
        }
        ioScope.launch {
            networks.connectedWifi.collect { ssid ->
                if (ssid != null && !isCameraSsid(ssid, settings.current().cameraSsid)) {
                    seenWifiStore.record(ssid, System.currentTimeMillis())
                }
            }
        }
        ioScope.launch {
            settings.flow
                .map { CameraWifiConfig(it.cameraWifiAutoConnect, it.cameraSsid, it.cameraWifiPassword) }
                .distinctUntilChanged()
                .collect { applyCameraWifi(it) }
        }
    }

    /** Nearby Wi-Fi names while collected; triggers one scan. */
    fun nearbyWifi(): Flow<List<NearbyWifi>> = wifiScanner.nearby()

    fun refreshStatus() {
        ioScope.launch { probe(settings.current()) }
    }

    @Volatile
    private var fineLocationSeen: Boolean? = null

    /**
     * Wi-Fi names only reach the app after location is granted and the
     * network callbacks are re-registered. Called on every resume, so a grant
     * made in Android's own settings takes effect without restarting the app.
     */
    fun onPermissionsMaybeChanged() {
        val granted = app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val before = fineLocationSeen
        fineLocationSeen = granted
        if (before != null && before != granted) refreshNetworkHints()
    }

    /** Re-reads network capabilities after a runtime permission grant. */
    fun refreshNetworkHints() {
        networks.refreshHints()
        ioScope.launch {
            delay(HINT_REFRESH_SETTLE_MS)
            probe(settings.current())
        }
    }

    private fun applyCameraWifi(config: CameraWifiConfig) {
        val status = cameraWifi.apply(config)
        _state.update { it.copy(cameraWifiStatus = status) }
        if (status == CameraWifiStatus.Failed) {
            log("Could not register the camera Wi-Fi network", error = true)
        }
    }

    private fun onCameraWifiApproval(status: CameraWifiStatus) {
        _state.update { it.copy(cameraWifiStatus = status) }
        if (status == CameraWifiStatus.Blocked) {
            log(
                "Camera Wi-Fi suggestion rejected; allow Wi-Fi control for Z50 Bridge to auto-connect",
                error = true,
            )
        }
    }

    fun refreshStaged() {
        ioScope.launch {
            val files = offload.pendingFiles(stagingDir)
            _state.update {
                it.copy(
                    phoneFiles = files.size,
                    phoneBytes = files.sumOf { file -> file.length() },
                    phoneFreeBytes = stagingDir.usableSpace,
                    stagedClips = StagedClipScanner.scan(stagingDir),
                )
            }
            pushWidget()
        }
    }

    /** Mirrors staging, phase, and reachability onto the home-screen widget. */
    private suspend fun pushWidget() {
        val state = _state.value
        runCatching {
            Z50Widget.update(
                app,
                files = state.phoneFiles,
                bytes = state.phoneBytes,
                phase = state.phase,
                cameraOnline = state.cameraReachable,
                homelabOnline = state.serverReachable,
            )
        }
    }

    fun setDestination(destination: TopDestination) {
        _state.update { it.copy(destination = destination) }
    }

    fun setShowSetup(show: Boolean) {
        _state.update { it.copy(showSetup = show) }
    }

    fun clearSyncHistory() {
        history.clear()
        log("Cleared sync history")
        refreshStaged()
    }

    // ------------------------------------------------------------ camera access

    /**
     * Runs [block] against an authenticated camera session. Transfers hold the
     * same lock, so a status read or settings write never opens a second
     * session in the middle of a download (the firmware hands out one session).
     */
    private suspend fun <T> withCamera(block: suspend (DdpaiClient, BridgeSettings) -> T): CameraResult<T> =
        withCameraOn { client, cfg, _ -> block(client, cfg) }

    private suspend fun <T> withCameraOn(block: suspend (DdpaiClient, BridgeSettings, Network) -> T): CameraResult<T> =
        withContext(Dispatchers.IO) {
            val cfg = settings.current()
            val net = probeNetwork(NetworkUse.Camera, cfg.cameraHost, 80) ?: return@withContext CameraResult.Offline
            if (!mutex.tryLock()) return@withContext CameraResult.Busy
            cameraRequestActive = true
            try {
                val client = DdpaiClient(baseUrl = "http://${cfg.cameraHost}", http = networks.httpClient(net))
                client.authenticate()
                CameraResult.Ok(block(client, cfg, net))
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                CameraResult.Failed(exc.message ?: "Camera request failed")
            } finally {
                cameraRequestActive = false
                mutex.unlock()
            }
        }

    suspend fun refreshCameraStatus(): CameraResult<CameraStatus> =
        withCamera { client, cfg -> readCameraStatus(client, cfg) }

    /**
     * Reads identity, recording state, SD card, clock and chip temperature. Each call is
     * independent so one unsupported CGI does not hide the rest. On the first
     * read of a connection, a drifted (or unreadable) camera clock is set to
     * the phone's local time when the setting allows it.
     */
    private fun readCameraStatus(client: DdpaiClient, cfg: BridgeSettings): CameraStatus {
        val info = runCatching { client.info() }.getOrDefault(emptyMap())
        val report = runCatching { client.stateReport() }.getOrDefault(emptyMap())
        val storage = runCatching { client.storageInfo() }.getOrDefault(emptyMap())
        val time = runCatching { client.cameraTime() }.getOrDefault(emptyMap())
        val chipTemp = runCatching { client.chipTemperature(CAMERA_SAMPLE_TIMEOUT_MS) }.getOrDefault(emptyMap())
        var status = parseCameraStatus(info, report, storage, time, System.currentTimeMillis(), chipTemp = chipTemp)
        val previous = _state.value.cameraStatus
        status = status.copy(clockSyncedAtMs = previous?.clockSyncedAtMs)

        if (cfg.syncCameraClock && !clockCheckedThisConnection) {
            clockCheckedThisConnection = true
            if (status.clockInSync != true) status = syncCameraClock(client, status)
        }
        _state.update { it.copy(cameraStatus = status) }
        statusReadAtMs = System.currentTimeMillis()
        noteHeat(status.socTemp)
        return status
    }

    private fun syncCameraClock(client: DdpaiClient, before: CameraStatus): CameraStatus = try {
        val local = ZonedDateTime.now()
        client.syncDate(
            stamp = cameraClockStamp(local.toLocalDateTime()),
            utcOffsetSeconds = local.offset.totalSeconds,
            lang = cameraLangTag(Locale.getDefault()),
        )
        val now = System.currentTimeMillis()
        val after = runCatching { parseCameraClock(client.cameraTime()) }.getOrNull()
            ?.let { Math.round((it - now) / 1000.0) }
        val drift = before.clockOffsetSeconds
        when {
            after != null && abs(after) > CameraStatus.CLOCK_TOLERANCE_SECONDS ->
                log("Camera accepted the new time but its clock still reads ${formatDrift(after)}", error = true, toast = false)
            drift == null -> log("Set the camera clock to phone time", kind = ActivityKind.Sync)
            else -> log("Camera clock was ${formatDrift(drift)}; set to phone time", kind = ActivityKind.Sync)
        }
        before.copy(clockOffsetSeconds = after, clockSyncedAtMs = now)
    } catch (exc: Exception) {
        log("Could not set the camera clock: ${exc.message}", error = true)
        before
    }

    /** Logs the first reading per connection that reaches [CameraStatus.HOT_SOC_TEMP]. */
    private fun noteHeat(socTemp: Long?) {
        if (socTemp == null || socTemp < CameraStatus.HOT_SOC_TEMP || heatLoggedThisConnection) return
        heatLoggedThisConnection = true
        log(
            "Camera chip is running hot: $socTemp (its first heat threshold is ${CameraStatus.FIRMWARE_HEAT_THRESHOLD})",
            kind = ActivityKind.System,
        )
    }

    private fun formatDrift(seconds: Long): String {
        val magnitude = abs(seconds)
        val amount = if (magnitude >= 120) "${magnitude / 60} min" else "$magnitude s"
        return if (seconds > 0) "$amount fast" else "$amount slow"
    }

    suspend fun loadDashcamSettings(): CameraResult<Map<String, DashcamValue>> =
        withCamera { client, _ ->
            parseDashcamSettings(client.querySettings(DASHCAM_SPECS.map { it.key }))
        }

    /** Writes one setting and reads it back, so the UI shows what the camera actually stored. */
    suspend fun saveDashcamSetting(key: String, value: Any): CameraResult<DashcamValue?> =
        withCamera { client, _ ->
            client.saveSetting(key, value)
            log("Camera setting $key set to $value", kind = ActivityKind.System)
            parseDashcamSettings(client.querySettings(listOf(key)))[key]
        }

    /**
     * Starts or stops the camera's loop recording, then polls the state report
     * until it agrees. Ok(true) means the camera confirmed the new state;
     * Ok(false) means it accepted the command but had not confirmed it yet.
     */
    suspend fun setCameraRecording(on: Boolean): CameraResult<Boolean> =
        withCamera { client, _ ->
            client.setRecording(on)
            log(if (on) "Camera recording started" else "Camera recording stopped", kind = ActivityKind.System)
            var reported: Boolean? = null
            for (attempt in 1..RECORD_CONFIRM_POLLS) {
                delay(RECORD_CONFIRM_INTERVAL_MS)
                reported = flag(runCatching { client.stateReport()["rec"] }.getOrNull())
                if (reported == on) break
            }
            _state.update { it.copy(cameraStatus = it.cameraStatus?.copy(recording = reported)) }
            reported == on
        }

    // Guards the direct-connection request and the auto-connect policy, which
    // are touched from the UI, the connectivity thread, transfer sessions and
    // the auto-connect loop.
    private val directLock = Any()
    private var directWifiCallback: ConnectivityManager.NetworkCallback? = null

    /** Why the held request was made; null when none is held. */
    private var directReason: DirectReason? = null
    private val autoConnect = CameraAutoConnect()
    private var autoConnectJob: Job? = null

    @Volatile
    private var autoConnectHintLogged = false

    /** Plugging the phone in is the car starting: look for the camera right away. */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ioScope.launch { onPluggedIn() }
        }
    }

    @Volatile
    private var powerReceiverRegistered = false

    /**
     * The "Connect to camera directly" button. It makes the same direct
     * request as auto-connect and, like it, keeps the connection until the
     * camera goes away. When the camera is already reachable it does nothing:
     * a new request would tear down the working connection and the sync on it.
     */
    fun connectCameraDirect() {
        ioScope.launch {
            if (_state.value.cameraReachable) {
                log("Already connected to the camera", kind = ActivityKind.System, toast = true)
                return@launch
            }
            requestCameraDirect(settings.current(), DirectReason.Button)
        }
    }

    /**
     * Asks Android to join the camera's Wi-Fi through a WifiNetworkSpecifier,
     * waiting at most [DirectReason.timeoutMs] for it to appear. Android does
     * the looking itself, so this works without scan results (which need
     * "Allow all the time" location in the background). The request is kept
     * until the camera goes away; then, while watching, a [DirectReason.CameraLost]
     * request waits for it to come back, so a quick stop needs no tap.
     */
    private fun requestCameraDirect(s: BridgeSettings, reason: DirectReason) {
        if (s.cameraSsid.isBlank()) {
            if (reason == DirectReason.Button) log("Cannot connect: Camera SSID is not configured", error = true)
            return
        }
        when (reason) {
            DirectReason.Button -> log("Connecting to ${s.cameraSsid}...", kind = ActivityKind.System)
            DirectReason.InRange -> {
                val (attempt, of) = synchronized(directLock) { autoConnect.attempt to autoConnect.maxAttempts }
                log("Camera Wi-Fi in range; connecting directly (try $attempt of $of)", kind = ActivityKind.System)
            }
            DirectReason.CameraLost ->
                log("Camera went away; reconnecting on its own if it comes back within ${reason.timeoutMs / 60_000} minutes", kind = ActivityKind.System)
            DirectReason.PluggedIn ->
                log("Phone plugged in; looking for the camera for ${reason.timeoutMs / 60_000} minutes", kind = ActivityKind.System)
        }
        releaseDirectConnection(reason = null)
        var mine: ConnectivityManager.NetworkCallback? = null
        val requestedAtMs = System.currentTimeMillis()
        val callback = cameraWifi.connectDirect(
            CameraWifiConfig(enabled = true, ssid = s.cameraSsid, passphrase = s.cameraWifiPassword),
            timeoutMs = reason.timeoutMs,
            onConnected = {
                synchronized(directLock) { autoConnect.onConnected() }
                log("Connected directly to camera Wi-Fi", kind = ActivityKind.System)
                ioScope.launch { probe(s) }
            },
            onLost = {
                synchronized(directLock) { autoConnect.onLost(System.currentTimeMillis()) }
                val wasHeld = releaseDirectConnection("Direct camera connection lost", only = synchronized(directLock) { mine })
                if (wasHeld) ioScope.launch { awaitCameraReturn() }
            },
            onFailed = { why ->
                // The framework has already dropped a request that failed.
                synchronized(directLock) {
                    if (directWifiCallback === mine) {
                        directWifiCallback = null
                        directReason = null
                    }
                }
                when (reason) {
                    DirectReason.Button -> log("Could not connect to the camera: $why", error = true)
                    DirectReason.InRange -> {
                        val waitMs = synchronized(directLock) { autoConnect.onFailed(System.currentTimeMillis()) }
                        log("Direct connection: $why; next try in ${formatWait(waitMs)}", error = true, toast = false)
                    }
                    // Android refuses at once when Wi-Fi is off; only a full wait means the camera stayed away.
                    DirectReason.CameraLost -> if (System.currentTimeMillis() - requestedAtMs >= reason.timeoutMs - 5_000) {
                        log("The camera did not come back within ${reason.timeoutMs / 60_000} minutes", kind = ActivityKind.System)
                    } else {
                        log("Could not wait for the camera to come back: $why", kind = ActivityKind.System)
                    }
                    // Usually the phone charging at home; nothing to report.
                    DirectReason.PluggedIn -> Unit
                }
            },
        ) ?: return
        synchronized(directLock) {
            mine = callback
            directWifiCallback = callback
            directReason = reason
        }
    }

    /** After the camera went away while watching: wait for it to come back without needing scans. */
    private suspend fun awaitCameraReturn() {
        val cfg = settings.current()
        if (!_state.value.watching || !cfg.cameraWifiAutoConnect || cfg.cameraSsid.isBlank()) return
        requestCameraDirect(cfg, DirectReason.CameraLost)
    }

    private suspend fun onPluggedIn() {
        val cfg = settings.current()
        if (!_state.value.watching || !cfg.cameraWifiAutoConnect || cfg.cameraSsid.isBlank()) return
        if (_state.value.cameraReachable || networks.cameraWifi() != null) return
        if (synchronized(directLock) { directWifiCallback != null }) return
        requestCameraDirect(cfg, DirectReason.PluggedIn)
    }

    /**
     * Releases the held request; with [only], just when that one is still
     * held. Returns whether it released one.
     */
    private fun releaseDirectConnection(reason: String?, only: ConnectivityManager.NetworkCallback? = null): Boolean {
        val callback = synchronized(directLock) {
            val held = directWifiCallback
            if (only != null && held !== only) return false
            directWifiCallback = null
            directReason = null
            held
        } ?: return false
        cameraWifi.releaseDirect(callback)
        if (reason != null) log(reason, kind = ActivityKind.System)
        return true
    }

    /** Releases a request auto-connect made; one from the button stays until the camera goes away. */
    private fun releaseAutoDirectConnection(reason: String) {
        val auto = synchronized(directLock) {
            directWifiCallback?.takeIf { directReason != null && directReason != DirectReason.Button }
        } ?: return
        releaseDirectConnection(reason, only = auto)
    }

    /**
     * Runs beside the watcher and does what the "Connect to camera directly"
     * button does once the camera's Wi-Fi shows up in scans and the phone has
     * not joined it within [CameraAutoConnect]'s grace period. The app asks
     * for scans only while the phone is plugged in (it is, in the car) and
     * otherwise relies on the system's own scans, to spare the battery.
     * Plugging in and the camera going away also start a request, which
     * needs no scans at all.
     */
    private fun startAutoConnect(scope: CoroutineScope) {
        if (!powerReceiverRegistered) {
            ContextCompat.registerReceiver(
                app,
                powerReceiver,
                IntentFilter(Intent.ACTION_POWER_CONNECTED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            powerReceiverRegistered = true
        }
        if (autoConnectJob?.isActive == true) return
        autoConnectJob = scope.launch(Dispatchers.IO) {
            var lastScanMs = 0L
            while (isActive && _state.value.watching) {
                val cfg = settings.current()
                val held = synchronized(directLock) { directWifiCallback != null }
                if (!cfg.cameraWifiAutoConnect || cfg.cameraSsid.isBlank()) {
                    releaseAutoDirectConnection("Released direct camera connection (auto-connect off)")
                } else {
                    noteAutoConnectNeedsLocation()
                    val now = System.currentTimeMillis()
                    // Android may have joined the camera on its own a moment
                    // before the probe notices; a direct request then would
                    // tear that working connection down and rebuild it.
                    val reachable = _state.value.cameraReachable || networks.cameraWifi() != null
                    if (!reachable && !held && !busy && phonePluggedIn() && now - lastScanMs >= AUTO_SCAN_INTERVAL_MS) {
                        wifiScanner.requestScan()
                        lastScanMs = now
                    }
                    val seen = wifiScanner.sees(cfg.cameraSsid, AUTO_SCAN_FRESH_MS)
                    val go = synchronized(directLock) { autoConnect.shouldRequest(now, seen, reachable, held) }
                    if (go) requestCameraDirect(cfg, DirectReason.InRange)
                }
                delay(AUTO_CONNECT_TICK_MS)
            }
        }
    }

    private fun stopAutoConnect() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        if (powerReceiverRegistered) {
            runCatching { app.unregisterReceiver(powerReceiver) }
            powerReceiverRegistered = false
        }
        releaseDirectConnection("Released direct camera connection (stopped watching)")
    }

    /** Android hides scan results from apps in the background without "Allow all the time". */
    private fun noteAutoConnectNeedsLocation() {
        if (autoConnectHintLogged || hasBackgroundLocation(app)) return
        autoConnectHintLogged = true
        log(
            "Without location set to Allow all the time, the camera is only found in range while the app is open; " +
                "plugging in and short stops still reconnect",
            kind = ActivityKind.System,
        )
    }

    private fun phonePluggedIn(): Boolean {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun formatWait(ms: Long): String =
        if (ms >= 60_000L) "${ms / 60_000L} min" else "${ms / 1000L} s"

    /**
     * Changes the camera AP channel (1, 6, 11). Ok(true) means the camera
     * acknowledged the change; Ok(false) means it stopped answering mid-request,
     * which is expected if its AP restarted before replying, so the outcome is
     * unconfirmed rather than failed.
     */
    suspend fun setCameraWifiChannel(channel: Int): CameraResult<Boolean> =
        withCamera { client, _ ->
            try {
                client.setWifiChannel(channel)
                log("Camera Wi-Fi channel set to $channel", kind = ActivityKind.System)
                true
            } catch (_: CameraUnreachableException) {
                log("Camera stopped answering after a switch to channel $channel; its Wi-Fi may be restarting", kind = ActivityKind.System)
                false
            }
        }

    /**
     * Puts the camera in live mode and returns where to stream from. While the
     * live view is open the watcher does not start syncs, which would compete
     * for the camera's 2.4 GHz link.
     */
    suspend fun startLiveView(): CameraResult<LiveEndpoint> {
        liveViewActive = true
        val result = withCamera { client, cfg ->
            runCatching { client.setAppLive(true) }
            runCatching { client.setLiveMode(true) }
            cfg.cameraHost
        }
        return when (result) {
            is CameraResult.Ok -> {
                val net = networks.networkFor(NetworkUse.Camera, result.value, 80)
                if (net == null) CameraResult.Offline else CameraResult.Ok(LiveEndpoint(net, result.value))
            }
            is CameraResult.Busy -> result
            is CameraResult.Offline -> result
            is CameraResult.Failed -> result
        }
    }

    fun setDashcamScreenOpen(open: Boolean) {
        dashcamScreenOpen = open
    }

    fun stopLiveView() {
        liveViewActive = false
        ioScope.launch { withCamera { client, _ -> runCatching { client.setAppLive(false) } } }
    }

    /**
     * Measures the camera link without phone storage in the way: reads the
     * newest finished clips for a fixed time per phase and discards the bytes.
     * Phases compare one stream against two, and SuperDownload on against off.
     * The first [SPEED_TEST_WARMUP_MS] of each phase is read but not counted:
     * the camera takes a few seconds to reach full speed, which made whichever
     * phase ran first look slower.
     */
    suspend fun runSpeedTest(onProgress: (SpeedTestProgress) -> Unit): CameraResult<SpeedTestResult> =
        withCameraOn { client, cfg, net ->
            val recorder = TransferRecorder("Speed test")
            val status = readCameraStatus(client, cfg)
            noteTransferContext(recorder, client, status, net)
            val clips = skipOpenClip(client.listPlayback(rear = false))
                .sortedByDescending { it.startUnix ?: 0L }
                .take(2)
            if (clips.isEmpty()) throw DdpaiException("No finished clips on the camera to test with")
            recorder.note("Test clips", clips.joinToString { "${it.filename} (${it.sizeBytes?.let(::formatBytes) ?: "size unknown"})" })
            val plan = listOf(
                SpeedTestPlan("1 stream, SuperDownload on", streams = 1, superDownload = true),
                SpeedTestPlan("2 streams, SuperDownload on", streams = 2, superDownload = true),
                SpeedTestPlan("1 stream, SuperDownload off", streams = 1, superDownload = false),
            )
            log("Camera speed test started", kind = ActivityKind.System)
            recordTransfer(recorder, net, SPEED_TEST_SAMPLE_MS, keepEmpty = true) {
                try {
                    plan.forEachIndexed { index, step ->
                        onProgress(SpeedTestProgress(index, plan.size, step.label))
                        try {
                            client.setSuperDownload(step.superDownload)
                        } catch (exc: Exception) {
                            recorder.note("SuperDownload ${if (step.superDownload) "on" else "off"}", "failed: ${exc.message}")
                        }
                        val timings = List(step.streams) { recorder.track(DownloadTiming()) }
                        val startNs = System.nanoTime()
                        // Stays at the phase start (counting everything) if the reads end before warm-up does.
                        var measuredFromNs = startNs
                        var bytesBeforeMeasuring = 0L
                        coroutineScope {
                            val reads = timings.mapIndexed { stream, timing ->
                                async(Dispatchers.IO) {
                                    client.measureRead(
                                        clips[stream % clips.size].name,
                                        SPEED_TEST_WARMUP_MS + SPEED_TEST_PHASE_MS,
                                        timing,
                                    ) { isActive }
                                }
                            }
                            val warmup = launch {
                                delay(SPEED_TEST_WARMUP_MS)
                                bytesBeforeMeasuring = timings.sumOf { it.bytes }
                                measuredFromNs = System.nanoTime()
                            }
                            reads.awaitAll()
                            warmup.cancel()
                        }
                        recorder.phase(
                            TransferRecorder.PhaseResult(
                                label = step.label,
                                bytes = timings.sumOf { it.bytes } - bytesBeforeMeasuring,
                                elapsedMs = (System.nanoTime() - measuredFromNs) / 1_000_000,
                                link = recorder.lastSample(),
                                warmupMs = (measuredFromNs - startNs) / 1_000_000,
                            ),
                        )
                    }
                } finally {
                    runCatching { client.setSuperDownload(false) }
                }
            }
            onProgress(SpeedTestProgress(plan.size, plan.size, "Done"))
            SpeedTestResult(
                phases = recorder.phaseResults(),
                link = recorder.lastSample(),
                cameraRecording = status.recording,
                radios = recorder.firstRadios(),
                radiosChanged = recorder.radiosChanged(),
            )
        }

    /** Camera, phone and socket facts that explain a transfer's speed. */
    private fun noteTransferContext(recorder: TransferRecorder, client: DdpaiClient, status: CameraStatus, net: Network) {
        recorder.note("Network", networks.describe(net))
        recorder.note("Camera", "${status.model ?: "model unknown"}, firmware ${status.firmware ?: "unknown"}")
        recorder.note("Camera recording", status.recording?.let { if (it) "yes" else "no" })
        recorder.note("Camera state report", runCatching { client.stateReport() }.getOrElse { "unavailable (${it.message})" })
        recorder.note("Camera temperature", runCatching { client.temperature() }.getOrElse { "unavailable (${it.message})" })
        recorder.note("Camera SoC temperature", runCatching { client.chipTemperature() }.getOrElse { "unavailable (${it.message})" })
        recorder.note("Phone", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            when (power?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
        } else {
            "unknown"
        }
        recorder.note("Phone power", "power saver ${if (power?.isPowerSaveMode == true) "on" else "off"}, thermal $thermal")
        recorder.note("Socket receive buffer", NetworkMonitor.lastReceiveBufferBytes?.let { formatBytes(it.toLong()) })
    }

    /**
     * Runs [block] while sampling the Wi-Fi link every [sampleMs], then stores
     * the report, logs it to logcat under [DIAG_TAG] and adds a one-line
     * summary to Activity. Sessions that moved no bytes are not kept unless
     * [keepEmpty], so idle watcher polls do not push real reports out. The
     * camera is only sampled once [sampleCamera] says there is work, so an
     * idle poll costs it no extra requests.
     */
    private suspend fun <T> recordTransfer(
        recorder: TransferRecorder,
        net: Network,
        sampleMs: Long,
        keepEmpty: Boolean,
        cameraSample: (() -> CameraSample)? = null,
        sampleCamera: () -> Boolean = { true },
        block: suspend () -> T,
    ): T = coroutineScope {
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                recorder.sample(wifiLinks.read(net))
                delay(sampleMs)
            }
        }
        // Bluetooth and the cellular modem share airtime or spectrum with the
        // 2.4 GHz camera link; only changes are kept, so toggles show up with a time.
        val radioSampler = launch(Dispatchers.IO) {
            while (isActive) {
                recorder.radios(radios.read())
                delay(RADIO_SAMPLE_MS)
            }
        }
        // The camera is asked far less often than the phone's own Wi-Fi state:
        // each reading is two requests to a camera that is busy sending files.
        val cameraSampler = cameraSample?.let { read ->
            launch(Dispatchers.IO) {
                while (isActive && !sampleCamera()) delay(CAMERA_SAMPLE_WAIT_MS)
                while (isActive) {
                    recorder.cameraSample(read())
                    delay(CAMERA_SAMPLE_MS)
                }
            }
        }
        var failure: Throwable? = null
        try {
            block()
        } catch (exc: Throwable) {
            failure = exc
            throw exc
        } finally {
            sampler.cancel()
            radioSampler.cancel()
            cameraSampler?.cancel()
            recorder.sample(wifiLinks.read(net))
            val cancelled = failure is CancellationException
            if (failure != null) recorder.note(if (cancelled) "Stopped" else "Error", failure.message ?: failure.javaClass.simpleName)
            val failed = failure != null && !cancelled
            if (keepEmpty || recorder.bytesSoFar() > 0 || recorder.hasFiles() || failed) {
                val report = recorder.report()
                // Kept apart from the clip-a-minute syncs of a drive, which would push them out.
                val notable = keepEmpty || failed || recorder.failedFiles() > 0 || recorder.fileCount() >= NOTABLE_FILES
                transferReports.add(report, notable)
                report.lines().forEach { Log.i(DIAG_TAG, it) }
                log(recorder.summary(), kind = if (recorder.title == "Sync") ActivityKind.Sync else ActivityKind.System)
            }
        }
    }

    suspend fun diagnosticsReport(): String {
        val versionName = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }
            .getOrNull() ?: "unknown"
        val device = DeviceInfo(
            appVersion = versionName,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        val transfers = withContext(Dispatchers.IO) { transferReports.load() }
        return buildDiagnosticsReport(device, _state.value, settings.current(), System.currentTimeMillis(), transfers = transfers)
    }

    /**
     * Sends the diagnostics report to the receiver on server when
     * [DiagnosticsSendPolicy] says it is due, over a route offload may use
     * (never mobile data). Quiet when it works; a failure goes to Activity
     * once per app run, so a receiver without the endpoint does not fill it.
     */
    private suspend fun sendDiagnostics(cfg: BridgeSettings, afterOffload: Boolean) {
        if (busy) return
        val newest = _state.value.activity.firstOrNull()?.timeMs ?: return
        val now = System.currentTimeMillis()
        if (!diagnosticsPolicy.shouldSend(now, newest, diagnosticsSent.sentActivityMs, afterOffload)) return
        val net = probeNetwork(NetworkUse.Offload, cfg.destHost, cfg.destPort) {
            offloadAllowed(it, cfg.offloadWifiAllowlist)
        } ?: return
        try {
            val report = diagnosticsReport()
            withContext(Dispatchers.IO) {
                offload.uploadDiagnostics(cfg, networks.httpClient(net), diagnosticsUploadName(now), report)
            }
            diagnosticsSent.mark(newest)
            diagnosticsFailureLogged = false
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            if (!diagnosticsFailureLogged) {
                diagnosticsFailureLogged = true
                log("Could not send diagnostics to server: ${exc.message}", kind = ActivityKind.System)
            }
        }
    }

    fun clearActivity() {
        activityStore.clear()
        _state.update { it.copy(activity = emptyList()) }
    }

    fun deleteClip(clip: StagedClip) {
        trashClips(listOf(clip))
    }

    /** Moves clips and their sidecars to the staging trash so the delete can be undone. */
    fun trashClips(clips: Collection<StagedClip>): TrashToken? {
        if (clips.isEmpty()) return null
        if (!mutex.tryLock()) {
            log("Transfer in progress; stop it before deleting clips", error = true)
            return null
        }
        try {
            trashDir.mkdirs()
            val moved = mutableListOf<File>()
            val names = mutableListOf<String>()
            clips.forEach { clip ->
                val stem = clip.file.nameWithoutExtension
                stagingDir.listFiles { _, name ->
                    name == clip.file.name || name.startsWith("$stem.") || name == "${stem}_T.jpg"
                }?.forEach { file ->
                    names.add(file.name)
                    val target = File(trashDir, file.name)
                    if (file.renameTo(target)) moved.add(target) else file.delete()
                }
            }
            if (moved.isEmpty()) return null
            // The names stay in the synced ledger, so a deleted clip that is
            // still on the camera is not downloaded again. Clear sync history
            // in Settings brings everything back.
            refreshStaged()
            log("Deleted ${clips.size} clip${if (clips.size == 1) "" else "s"}")
            return TrashToken(names = names, files = moved)
        } finally {
            mutex.unlock()
        }
    }

    fun restoreTrash(token: TrashToken) {
        ioScope.launch {
            token.files.forEach { file ->
                if (file.exists()) file.renameTo(File(stagingDir, file.name))
            }
            history.addAll(token.names)
            refreshStaged()
            log("Restored ${token.names.size} file${if (token.names.size == 1) "" else "s"}")
        }
    }

    fun clearAllStaged() {
        ioScope.launch {
            if (!mutex.tryLock()) {
                log("Transfer in progress; stop it before clearing staging", error = true)
                return@launch
            }
            try {
                val files = stagingDir.listFiles()
                    ?.filterNot { it.name == LEDGER_FILE || it.name.endsWith(".tmp") || it.name.startsWith(".") }
                    .orEmpty()
                files.forEach { it.delete() }
                refreshStaged()
                log("Cleared ${files.size} staged files from phone")
            } finally {
                mutex.unlock()
            }
        }
    }

    suspend fun promptSetupIfNeeded() {
        if (settings.current().needsSetup()) setShowSetup(true)
    }

    suspend fun testConnection(cfg: BridgeSettings): String =
        withContext(Dispatchers.IO) {
            val net = networks.networkFor(NetworkUse.Offload, cfg.destHost, cfg.destPort)
                ?: error("Cannot reach ${cfg.destHost}:${cfg.destPort}. Check Wi-Fi or WireGuard connection.")
            val client = networks.httpClient(net)
            offload.testLogin(cfg, client)
        }

    fun startWatching(scope: CoroutineScope) {
        // Never stomp on an in-flight transfer's phase when (re)arming the watcher.
        _state.update { it.copy(watching = true, phase = if (busy) it.phase else BridgePhase.Watching) }
        startAutoConnect(scope)
        if (watchJob?.isActive == true) {
            return
        }
        watchJob = scope.launch(Dispatchers.IO) {
            var cameraMisses = 0
            while (isActive && _state.value.watching) {
                val cfg = settings.current()
                probe(cfg)
                val ui = _state.value
                when {
                    ui.cameraReachable -> {
                        cameraMisses = 0
                        if (!cameraScreenOpen && !ui.autoSyncPaused) runSync(cfg, userInitiated = false)
                    }
                    ui.serverReachable -> {
                        cameraMisses += 1
                        // Two misses in a row: the camera is really gone, not between polls.
                        val offload = ui.phoneFiles > 0 && cameraMisses >= 2 && !ui.autoOffloadPaused
                        if (offload) runOffload(cfg, userInitiated = false)
                        sendDiagnostics(cfg, afterOffload = offload)
                    }
                    else -> cameraMisses += 1
                }
                refreshStaged()
                val waitMs = cfg.pollSeconds.coerceIn(10, 120) * 1000L
                withTimeoutOrNull(waitMs) { networks.changes.first() }
            }
        }
    }

    /** Saves the "Watch in background" choice; see [io.github.johnkuruvila.z50bridge.service.WatchSwitch]. */
    fun saveWatchChoice(enabled: Boolean) {
        ioScope.launch { settings.update { it.copy(autoWatch = enabled) } }
    }

    fun stopWatching() {
        _state.update { it.copy(watching = false, phase = if (busy) it.phase else BridgePhase.Idle) }
        watchJob?.cancel()
        watchJob = null
        stopAutoConnect()
        // Blocking OkHttp calls ignore coroutine cancellation; abort them directly.
        networks.cancelActive()
    }

    /**
     * Stops the running transfer; watching carries on. So that the watcher
     * does not start the same transfer again on its next poll, it pauses
     * that kind of transfer until the network it ran on goes away: for a
     * sync the camera connection (the ignition), for an offload the phone
     * leaving that network. Sync or Offload by hand lifts the pause.
     * Cancelling the session job stops new files from starting; blocking
     * OkHttp calls ignore cancellation, so they are aborted until the
     * session has unwound (a worker may open a call just after the first
     * abort).
     *
     * Live view and Dashcam settings pass [pauseAutomatic] false: they only
     * need the camera free while open, and the watcher already holds off
     * until they close.
     */
    fun stopTransfer(pauseAutomatic: Boolean = true) {
        val job = sessionJob ?: return
        stopRequested = true
        if (pauseAutomatic) {
            val net = sessionNet
            when (_state.value.phase) {
                BridgePhase.Syncing -> {
                    syncPausedOn = net
                    _state.update { it.copy(autoSyncPaused = net != null) }
                }
                BridgePhase.Offloading -> {
                    offloadPausedOn = net
                    _state.update { it.copy(autoOffloadPaused = net != null) }
                }
                else -> Unit
            }
        }
        // Progress callbacks are ignored from here on; show the stop at once.
        _state.update { it.copy(stopping = true, progress = null) }
        job.cancel(CancellationException("Stopped by user"))
        ioScope.launch {
            val deadline = System.currentTimeMillis() + STOP_ABORT_WINDOW_MS
            while (job.isActive && System.currentTimeMillis() < deadline) {
                networks.cancelActive()
                delay(STOP_ABORT_INTERVAL_MS)
            }
        }
    }

    fun syncNow(scope: CoroutineScope, onComplete: () -> Unit = {}): Job = scope.launch(Dispatchers.IO) {
        try {
            if (cameraScreenOpen) {
                log("Close live view or Dashcam settings to sync from the camera", toast = true)
                return@launch
            }
            if (busy) {
                AppToasts.show(app, "A transfer is already running")
                return@launch
            }
            val cfg = settings.current()
            syncPausedOn = null
            probe(cfg)
            if (!_state.value.cameraReachable) {
                log("Not on camera Wi-Fi", error = true)
                return@launch
            }
            runSync(cfg, userInitiated = true)
        } finally {
            refreshStaged()
            onComplete()
        }
    }

    fun offloadNow(scope: CoroutineScope, onComplete: () -> Unit = {}): Job = scope.launch(Dispatchers.IO) {
        try {
            if (busy) {
                AppToasts.show(app, "A transfer is already running")
                return@launch
            }
            val cfg = settings.current()
            offloadPausedOn = null
            probe(cfg)
            if (!_state.value.serverReachable) {
                val reason = if (cfg.offloadWifiAllowlist.isNotEmpty()) {
                    "Offload blocked: not on an allowed Wi-Fi network (VPN over Wi-Fi is exempt; mobile data is never used)"
                } else {
                    "server is not reachable over Wi-Fi (mobile data is never used)"
                }
                log(reason, error = true)
                return@launch
            }
            runOffload(cfg, userInitiated = true)
            sendDiagnostics(cfg, afterOffload = true)
        } finally {
            refreshStaged()
            onComplete()
        }
    }

    private suspend fun probe(cfg: BridgeSettings) {
        val cameraNet = probeNetwork(NetworkUse.Camera, cfg.cameraHost, 80)
        val cameraWasReachable = _state.value.cameraReachable
        if (cameraNet == null) {
            clockCheckedThisConnection = false
            heatLoggedThisConnection = false
            idleNote = null
            // A new connection reads the status (and checks the clock) first.
            statusReadAtMs = 0L
        }
        noteSilentCamera(cfg, cameraNet != null)
        val allowlist = cfg.offloadWifiAllowlist
        val serverNet = probeNetwork(NetworkUse.Offload, cfg.destHost, cfg.destPort) {
            offloadAllowed(it, allowlist)
        }
        // A pause lasts as long as that connection: it ends when Android tears
        // the network down or the camera answers on a new one. A probe that
        // just missed (the camera busy sending files) does not end it.
        syncPausedOn?.let { if (!networks.isConnected(it) || (cameraNet != null && cameraNet != it)) syncPausedOn = null }
        offloadPausedOn?.let { if (!networks.isConnected(it) || (serverNet != null && serverNet != it)) offloadPausedOn = null }
        _state.update {
            it.copy(
                cameraReachable = cameraNet != null,
                serverReachable = serverNet != null,
                autoSyncPaused = syncPausedOn != null,
                autoOffloadPaused = offloadPausedOn != null,
                offloadAllowlist = allowlist,
                cameraLabel = when {
                    cameraNet != null -> "Camera · ${cfg.cameraHost}"
                    serverNet != null -> "Home · ${cfg.destHost}"
                    else -> "Waiting for camera"
                },
                cameraDetail = when {
                    cameraNet != null -> "Z50 AP on ${networks.describe(cameraNet)}"
                    serverNet != null -> "Homelab on ${networks.describe(serverNet)}"
                    allowlist.isNotEmpty() -> "Offload limited to ${allowlist.sorted().joinToString(", ")} (VPN over Wi-Fi exempt)"
                    else -> "Not on camera Wi-Fi"
                },
            )
        }
        val wasUp = cameraUp.getAndSet(cameraNet != null)
        if (cameraNet != null && !wasUp) log("Camera connected on ${networks.describe(cameraNet)}", kind = ActivityKind.Sync)
        if (cameraNet == null && wasUp) {
            log("Camera disconnected", kind = ActivityKind.Sync)
            // Android's own connection (not the app's request) went away too:
            // wait for the camera to come back. Not while the phone is still on
            // its Wi-Fi, where a request would tear that connection down.
            val held = synchronized(directLock) { directWifiCallback != null }
            if (!held && networks.cameraWifi() == null) ioScope.launch { awaitCameraReturn() }
        }
        // A fresh connection gets its status (and clock check) right away; the
        // watcher's sync also refreshes it, whichever grabs the camera first.
        if (cameraNet != null && !cameraWasReachable) ioScope.launch { refreshCameraStatus() }
    }

    /**
     * Says so in Activity, once per connection, when the phone has been on
     * the camera's Wi-Fi for [CAMERA_SILENT_MS] and the camera still does not
     * answer: otherwise that looks exactly like the app ignoring the camera.
     * A camera that is still booting gets that long to start its web server.
     */
    private fun noteSilentCamera(cfg: BridgeSettings, cameraAnswered: Boolean) {
        val wifi = if (cameraAnswered) null else networks.cameraWifi()
        if (wifi == null) {
            cameraSilentSinceMs = null
            cameraSilentLogged = false
            return
        }
        val now = System.currentTimeMillis()
        val since = cameraSilentSinceMs ?: now.also { cameraSilentSinceMs = it }
        if (cameraSilentLogged || now - since < CAMERA_SILENT_MS) return
        cameraSilentLogged = true
        log("On ${wifi.ssid}, but the camera at ${cfg.cameraHost} does not answer", error = true, toast = false)
    }

    /**
     * The watcher calls this every poll while the camera is reachable, which
     * on a drive is every 30 s for hours. A poll with nothing new costs the
     * login and the listing only: the status is re-read every
     * [CAMERA_STATUS_REFRESH_MS], and the diagnostics reads wait until there
     * is something to download.
     */
    private suspend fun runSync(cfg: BridgeSettings, userInitiated: Boolean) = withSession(BridgePhase.Syncing, userInitiated) { onFileDone ->
        val cameraNet = networks.networkFor(NetworkUse.Camera, cfg.cameraHost, 80) ?: return@withSession 0
        sessionNet = cameraNet
        val client = DdpaiClient(
            baseUrl = "http://${cfg.cameraHost}",
            http = networks.httpClient(cameraNet),
        )
        client.authenticate()
        val cached = _state.value.cameraStatus
        val status = if (userInitiated || cached == null || System.currentTimeMillis() - statusReadAtMs >= CAMERA_STATUS_REFRESH_MS) {
            readCameraStatus(client, cfg)
        } else {
            cached
        }
        _state.update { it.copy(cameraDetail = status.model ?: "Z50") }
        val recorder = TransferRecorder("Sync")
        val queued = java.util.concurrent.atomic.AtomicBoolean(false)
        val throttle = ProgressThrottle()
        val cameraSample = {
            parseCameraSample(
                state = runCatching { client.stateReport(CAMERA_SAMPLE_TIMEOUT_MS) }.getOrDefault(emptyMap()),
                chipTemp = runCatching { client.chipTemperature(CAMERA_SAMPLE_TIMEOUT_MS) }.getOrDefault(emptyMap()),
                atMs = System.currentTimeMillis(),
            ).also { sample ->
                sample.socTemp?.let { temp ->
                    _state.update { it.copy(cameraStatus = it.cameraStatus?.copy(socTemp = temp)) }
                    noteHeat(temp)
                }
            }
        }
        val count = recordTransfer(
            recorder,
            cameraNet,
            SYNC_SAMPLE_MS,
            keepEmpty = false,
            cameraSample = cameraSample,
            sampleCamera = { queued.get() },
        ) {
            syncEngine.run(
                client = client,
                settings = cfg,
                onProgress = { progress ->
                    if (!stopRequested && throttle.shouldPublish(progress.filename, progress.copied, progress.total)) {
                        _state.update { it.copy(progress = progress, phase = BridgePhase.Syncing) }
                    }
                },
                onLog = { message -> log(message, kind = ActivityKind.Sync) },
                onError = { message -> log(message, error = true, toast = false) },
                onIssue = { AppToasts.show(app, it) },
                recorder = recorder,
                streams = { (liveSettings ?: cfg).parallelDownloads },
                onFileDone = onFileDone,
                onQueued = {
                    queued.set(true)
                    idleNote = null
                    log("Syncing from camera via ${networks.describe(cameraNet)}")
                    noteTransferContext(recorder, client, status, cameraNet)
                },
                // The same "nothing new" every 30 s would bury everything else.
                onIdle = { message ->
                    if (userInitiated || message != idleNote) log(message)
                    idleNote = message
                },
            )
        }
        // The recorder's summary line in Activity carries the file count. A
        // direct connection stays up: the camera keeps recording all drive.
        count
    }

    private suspend fun runOffload(cfg: BridgeSettings, userInitiated: Boolean) = withSession(BridgePhase.Offloading, userInitiated) { onFileDone ->
        val allowlist = cfg.offloadWifiAllowlist
        val net = networks.networkFor(NetworkUse.Offload, cfg.destHost, cfg.destPort) {
            offloadAllowed(it, allowlist)
        }
        sessionNet = net
        if (net == null) {
            if (allowlist.isNotEmpty()) {
                log(
                    "Offload blocked: not on ${allowlist.sorted().joinToString(", ")} and no VPN route",
                    error = true,
                    toast = userInitiated,
                )
            }
            return@withSession 0
        }
        log("Offloading to server via ${networks.describe(net)}")
        val count = offload.run(
            staging = stagingDir,
            settings = cfg,
            httpClient = networks.httpClient(net),
            onProgress = { progress ->
                if (!stopRequested) _state.update { it.copy(progress = progress, phase = BridgePhase.Offloading) }
            },
            onLog = { message -> log(message) },
            onError = { message -> log(message, error = true, toast = false) },
            onIssue = { AppToasts.show(app, it) },
            streams = { (liveSettings ?: cfg).parallelUploads },
            onFileDone = onFileDone,
        )
        if (count > 0) log("Offloaded $count files to server")
        count
    }

    /**
     * Errors from sessions the user started are toasted; the watcher's only go
     * to Activity, so a persistent fault does not toast on every poll.
     * [block] gets a callback to run after each finished file, which keeps
     * "Last sync/offload" and the interrupted-session marker current even
     * when the session never returns (stopped, ignition off, app killed).
     */
    private suspend fun withSession(phase: BridgePhase, userInitiated: Boolean, block: suspend (onFileDone: () -> Unit) -> Int): Int {
        // tryLock closes the race between the `busy` pre-checks and the slower
        // watcher/manual callers: only one transfer can ever own the camera.
        // A short camera request (status read, settings write) finishes in a
        // few seconds; wait for it instead of reporting a running transfer.
        val locked = mutex.tryLock() ||
            (cameraRequestActive && withTimeoutOrNull(CAMERA_REQUEST_WAIT_MS) { mutex.lock() } != null)
        if (!locked) {
            // The watcher polls into a running transfer all the time; only a
            // tap deserves an answer.
            if (userInitiated) AppToasts.show(app, "A transfer is already running")
            return 0
        }
        val syncing = phase == BridgePhase.Syncing
        val noun = if (syncing) "Sync" else "Offload"
        val startedMs = System.currentTimeMillis()
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val onFileDone = {
            val now = System.currentTimeMillis()
            val files = done.incrementAndGet()
            writeSessionMarker(noun, startedMs, files)
            val label = formatTransferStamp(now)
            if (syncing) stamps.markSync(now) else stamps.markOffload(now)
            _state.update { if (syncing) it.copy(lastSync = label) else it.copy(lastOffload = label) }
        }
        stopRequested = false
        writeSessionMarker(noun, startedMs, 0)
        val wakeLock = acquireTransferWakeLock()
        _state.update { it.copy(phase = phase) }
        pushWidget()
        try {
            // The transfer runs as its own child job, so Stop cancels only the
            // transfer. Cancelling the caller's job instead killed the watcher
            // whenever Stop ended a transfer the watcher had started.
            val count = coroutineScope {
                val session = async { block(onFileDone) }
                sessionJob = session
                session.await()
            }
            if (count > 0) {
                _state.update {
                    it.copy(lastCompleted = CompletedTransfer(synced = syncing, files = count))
                }
            }
            return count
        } catch (exc: CancellationException) {
            val files = done.get()
            val detail = if (files > 0) {
                " after $files file${if (files == 1) "" else "s"}; the rest stay queued"
            } else {
                "; nothing finished, partial files kept for resume"
            }
            val resume = when {
                !stopRequested -> ""
                syncing && syncPausedOn != null -> ". Automatic syncs start again when the camera next connects, or tap Sync"
                !syncing && offloadPausedOn != null -> ". Automatic offloads start again after the phone leaves this network, or tap Offload"
                else -> ""
            }
            log(
                (if (stopRequested) "$noun stopped$detail" else "$noun cancelled$detail") + resume,
                kind = if (syncing) ActivityKind.Sync else ActivityKind.Offload,
            )
            // Stopped transfer, caller still running (the watcher): carry on.
            if (currentCoroutineContext().isActive) return done.get()
            throw exc
        } catch (exc: Exception) {
            log(exc.message ?: "${phase.name.lowercase(Locale.getDefault())} failed", error = true, toast = userInitiated)
            return 0
        } finally {
            sessionMarker.delete()
            sessionJob = null
            sessionNet = null
            stopRequested = false
            _state.update {
                it.copy(
                    progress = null,
                    stopping = false,
                    phase = if (it.watching) BridgePhase.Watching else BridgePhase.Idle,
                )
            }
            releaseQuietly(wakeLock)
            mutex.unlock()
        }
    }

    /**
     * A marker on disk for the running session. If the process dies mid-
     * transfer (the system reclaims it, or it is force-stopped), the next
     * start finds it and says so in Activity instead of the transfer just
     * vanishing from the log.
     */
    private fun writeSessionMarker(noun: String, startedMs: Long, files: Int) {
        runCatching { sessionMarker.writeText("$noun\n$startedMs\n$files\n${System.currentTimeMillis()}\n") }
    }

    private fun reportInterruptedSession() {
        val lines = runCatching { sessionMarker.readLines() }.getOrNull() ?: return
        sessionMarker.delete()
        val noun = lines.getOrNull(0)?.takeIf { it == "Sync" || it == "Offload" } ?: return
        val started = lines.getOrNull(1)?.toLongOrNull() ?: return
        val files = lines.getOrNull(2)?.toIntOrNull() ?: 0
        val lastMs = lines.getOrNull(3)?.toLongOrNull() ?: started
        val done = if (files > 0) "$files file${if (files == 1) "" else "s"} finished" else "no file finished"
        log(
            "$noun started ${formatTransferStamp(started)} ended when the app was closed around " +
                "${formatTransferStamp(lastMs)} ($done); partial files are kept for resume",
            kind = if (noun == "Sync") ActivityKind.Sync else ActivityKind.Offload,
        )
    }

    private fun acquireTransferWakeLock(): PowerManager.WakeLock? = try {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "z50bridge:transfer").apply {
            setReferenceCounted(false)
            acquire(TRANSFER_TIMEOUT_MS)
        }
    } catch (_: Exception) {
        null
    }

    private fun releaseQuietly(wakeLock: PowerManager.WakeLock?) {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
    }

    private suspend fun probeNetwork(
        use: NetworkUse,
        host: String,
        port: Int,
        eligible: ((NetworkHint) -> Boolean)? = null,
    ): Network? {
        return try {
            networks.networkFor(use, host, port, eligible = eligible)
        } catch (exc: CancellationException) {
            throw exc
        } catch (_: Exception) {
            null
        }
    }

    private fun log(text: String, error: Boolean = false, kind: ActivityKind? = null, toast: Boolean = error) {
        val resolved = kind ?: when {
            error -> ActivityKind.Error
            text.contains("sync", ignoreCase = true) || text.contains("camera", ignoreCase = true) -> ActivityKind.Sync
            text.contains("offload", ignoreCase = true) || text.contains("server", ignoreCase = true) -> ActivityKind.Offload
            else -> ActivityKind.System
        }
        val item = ActivityItem(System.currentTimeMillis(), text, error, resolved)
        _state.update { it.copy(activity = (listOf(item) + it.activity).take(MAX_ACTIVITY)) }
        ioScope.launch { activityStore.append(item) }
        if (toast) AppToasts.show(app, text)
    }

    private companion object {
        const val LEDGER_FILE = "synced_history.json"
        const val TRASH_DIR = ".trash"
        const val MAX_ACTIVITY = 200
        const val NETWORK_SETTLE_MS = 500L
        const val CAMERA_REQUEST_WAIT_MS = 15_000L
        const val HINT_REFRESH_SETTLE_MS = 400L
        const val TRANSFER_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        const val SYNC_SAMPLE_MS = 2_000L
        const val SPEED_TEST_SAMPLE_MS = 1_000L
        const val CAMERA_SAMPLE_MS = 60_000L
        const val CAMERA_SAMPLE_TIMEOUT_MS = 5_000L
        const val SPEED_TEST_PHASE_MS = 12_000L
        const val SPEED_TEST_WARMUP_MS = 4_000L
        const val RADIO_SAMPLE_MS = 5_000L
        const val RECORD_CONFIRM_POLLS = 4
        const val RECORD_CONFIRM_INTERVAL_MS = 1_500L
        const val DIAG_TAG = "Z50Diag"
        const val AUTO_CONNECT_TICK_MS = 5_000L
        // Android allows 4 scans per 2 minutes; 35 s stays under that.
        const val AUTO_SCAN_INTERVAL_MS = 35_000L
        const val AUTO_SCAN_FRESH_MS = 45_000L
        const val CAMERA_STATUS_REFRESH_MS = 5 * 60_000L
        const val CAMERA_SAMPLE_WAIT_MS = 500L
        const val CAMERA_SILENT_MS = 30_000L
        const val DIAGNOSTICS_MIRROR_DELAY_MS = 60_000L
        // A sync this big is a backlog, not the clip-a-minute of a drive.
        const val NOTABLE_FILES = 10
        const val SESSION_MARKER = "transfer-session.txt"
        const val STOP_ABORT_WINDOW_MS = 10_000L
        const val STOP_ABORT_INTERVAL_MS = 250L
    }
}

private data class SpeedTestPlan(val label: String, val streams: Int, val superDownload: Boolean)

data class SpeedTestProgress(val step: Int, val steps: Int, val label: String)

data class SpeedTestResult(
    val phases: List<TransferRecorder.PhaseResult>,
    val link: WifiLinkSample?,
    val cameraRecording: Boolean?,
    /** Bluetooth and mobile data when the test started. */
    val radios: RadioState? = null,
    val radiosChanged: Boolean = false,
)

/** Why a direct camera request was made, and how long Android may look for the camera. */
private enum class DirectReason(val timeoutMs: Int) {
    /** The "Connect to camera directly" button. */
    Button(2 * 60_000),

    /** Auto-connect saw the camera in scans; short, so the policy can retry. */
    InRange(20_000),

    /** The camera went away while watching: a quick stop ends with it back within minutes. */
    CameraLost(10 * 60_000),

    /** The phone was plugged in, which in this car means the ignition came on. */
    PluggedIn(3 * 60_000),
}

sealed interface CameraResult<out T> {
    data class Ok<T>(val value: T) : CameraResult<T>

    /** A transfer owns the camera session right now. */
    data object Busy : CameraResult<Nothing>

    data object Offline : CameraResult<Nothing>

    data class Failed(val message: String) : CameraResult<Nothing>
}

/** RTSP endpoints on the camera, reached through the camera Wi-Fi [network]. */
data class LiveEndpoint(val network: Network, val host: String) {
    fun url(rear: Boolean): String = "rtsp://$host:554/liveRTSP/${if (rear) "av5" else "av4"}"
}

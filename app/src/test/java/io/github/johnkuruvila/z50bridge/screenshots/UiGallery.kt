package io.github.johnkuruvila.z50bridge.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import io.github.johnkuruvila.z50bridge.ActiveFileTransfer
import io.github.johnkuruvila.z50bridge.ActivityItem
import io.github.johnkuruvila.z50bridge.ActivityKind
import io.github.johnkuruvila.z50bridge.BridgePhase
import io.github.johnkuruvila.z50bridge.BridgeUiState
import io.github.johnkuruvila.z50bridge.StagedClip
import io.github.johnkuruvila.z50bridge.TransferProgress
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.ThemeMode
import io.github.johnkuruvila.z50bridge.net.CameraWifiStatus
import io.github.johnkuruvila.z50bridge.ui.ActivityScreen
import io.github.johnkuruvila.z50bridge.ui.SettingsScreen
import io.github.johnkuruvila.z50bridge.ui.SetupWizardScreen
import io.github.johnkuruvila.z50bridge.ui.clips.ClipDetailScreen
import io.github.johnkuruvila.z50bridge.ui.clips.ClipsScreen
import io.github.johnkuruvila.z50bridge.ui.home.HomeScreen
import io.github.johnkuruvila.z50bridge.ui.theme.Z50BridgeTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.johnkuruvila.z50bridge.CameraResult
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.camera.CameraStatus
import io.github.johnkuruvila.z50bridge.camera.DashcamValue
import io.github.johnkuruvila.z50bridge.navigation.SettingsSection
import io.github.johnkuruvila.z50bridge.net.NearbyWifi
import io.github.johnkuruvila.z50bridge.ui.dashcam.DashcamSettingsScreen
import io.github.johnkuruvila.z50bridge.ui.live.LiveViewScreen

/**
 * Renders every screen to PNG for design review. Test-only fixtures; runs only
 * with `-Pscreenshots` (see app/build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp-night-xxhdpi")
class UiGallery {
    private val now = System.currentTimeMillis()
    private lateinit var clips: List<StagedClip>

    @Before
    fun setUp() {
        Assume.assumeTrue(System.getProperty("z50.screenshots") == "true")
        val dir = Files.createTempDirectory("z50-gallery").toFile()
        val hues = listOf(0xFF3A4A5E, 0xFF5E4A3A, 0xFF2F4F4F, 0xFF4A3A5E, 0xFF3A5E4A, 0xFF5E3A4A)
        clips = listOf(
            "20260922181204_0060", "20260922180204_0060", "20260922175204_0060_B",
            "20260922174204_0060", "20260921093104_0060", "20260921092104_0060",
        ).mapIndexed { i, stem ->
            val thumb = File(dir, "${stem}_T.jpg").also { writeThumb(it, hues[i].toInt()) }
            StagedClip(
                file = File(dir, "$stem.mp4"),
                filename = "$stem.mp4",
                sizeBytes = 268_000_000L + i * 7_000_000L,
                dateFormatted = if (i < 4) "22 Sep 2026" else "21 Sep 2026",
                timeFormatted = "${stem.substring(8, 10)}:${stem.substring(10, 12)}",
                isRear = stem.endsWith("_B"),
                hasGps = i != 2,
                hasGsn = i % 2 == 0,
                durationFormatted = "1:00",
                thumbFile = thumb,
            )
        }
    }

    private fun writeThumb(file: File, color: Int) {
        val bmp = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 200f, color, 0xFF111418.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 320f, 200f, paint)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private val activity get() = listOf(
        ActivityItem(now - 60_000, "Offloaded 4 files to server", kind = ActivityKind.Offload),
        ActivityItem(now - 5 * 60_000, "Offloading to server via Wi-Fi · HomeNet", kind = ActivityKind.Offload),
        ActivityItem(now - 40 * 60_000, "Synced 4 files from camera", kind = ActivityKind.Sync),
        ActivityItem(now - 42 * 60_000, "HTTP 500: Upload failed: Socket closed unexpectedly after 1048576 / 268435456 bytes", error = true),
        ActivityItem(now - 26 * 3_600_000L, "Syncing from camera via Wi-Fi · DDPAI_Z50_xxxx", kind = ActivityKind.Sync),
    )

    @get:Rule
    val compose = createComposeRule()

    // Manual clock: infinite animations (pipeline flow, wavy progress) would
    // otherwise keep the UI from ever going idle.
    private fun shot(name: String, mode: ThemeMode = ThemeMode.Brand, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                Z50BridgeTheme(themeMode = mode) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Composable
    private fun Home(state: BridgeUiState, needsSetup: Boolean = false) = HomeScreen(
        state = state, needsSetup = needsSetup, onWatch = {}, onSync = {}, onOffload = {}, onStop = {},
        onRefresh = {}, onOpenSetup = {}, onOpenSettings = {}, onOpenClips = {}, onOpenClip = {}, onOpenActivity = {},
        onOpenLiveView = {}, onOpenDashcam = {}, onSetRecording = { CameraResult.Ok(true) },
    )

    private fun ready() = BridgeUiState(
        phase = BridgePhase.Watching, watching = true, serverReachable = true,
        cameraDetail = "Homelab on Wi-Fi · HomeNet", phoneFiles = clips.size,
        phoneBytes = clips.sumOf { it.sizeBytes }, phoneFreeBytes = 41_000_000_000,
        lastSync = "18:12", activity = activity, stagedClips = clips,
    )

    @Test fun home_ready() = shot("home_ready") { Home(ready()) }

    @Test fun home_ready_light() = shot("home_ready_light", ThemeMode.Light) { Home(ready()) }

    @Test fun home_syncing() = shot("home_syncing") {
        Home(
            ready().copy(
                phase = BridgePhase.Syncing, cameraReachable = true, serverReachable = false,
                cameraDetail = "Z50 AP on Wi-Fi · DDPAI_Z50_xxxx",
                progress = TransferProgress(
                    filename = "2 parallel streams", fileIndex = 3, totalFiles = 8,
                    copied = 400_000_000, total = 2_150_000_000, overallCopied = 812_000_000,
                    overallTotal = 2_150_000_000, bytesPerSecond = 9_400_000, etaSeconds = 142,
                    activeFiles = listOf(
                        ActiveFileTransfer("20260922183204_0060.mp4", 3, 190_000_000, 268_000_000),
                        ActiveFileTransfer("20260922182204_0060.mp4", 4, 61_000_000, 268_000_000),
                    ),
                ),
            ),
        )
    }

    @Test fun home_offline_empty() = shot("home_offline") {
        Home(BridgeUiState(phoneFreeBytes = 41_000_000_000), needsSetup = true)
    }

    @Test fun clips() = shot("clips") {
        ClipsScreen(
            clips = clips, totalBytes = clips.sumOf { it.sizeBytes }, busy = false,
            snackbarHostState = SnackbarHostState(), onRefresh = {}, onOpenClip = {},
            onTrashClips = { null }, onRestoreTrash = {}, onShareClips = {}, onClearAll = {},
        )
    }

    @Test fun clips_empty() = shot("clips_empty") {
        ClipsScreen(
            clips = emptyList(), totalBytes = 0, busy = false, snackbarHostState = SnackbarHostState(),
            onRefresh = {}, onOpenClip = {}, onTrashClips = { null }, onRestoreTrash = {}, onShareClips = {}, onClearAll = {},
        )
    }

    @Test fun clip_detail() = shot("clip_detail") {
        ClipDetailScreen(clip = clips.first(), onBack = {}, onDelete = {})
    }

    @Test fun activity() = shot("activity") { ActivityScreen(items = activity, buildDiagnostics = { "" }) }

    // Values a Z50 reported in the repo README (firmware v7.2.0.24); fixtures only.
    private val cameraStatus = CameraStatus(
        model = "Z50", firmware = "v7.2.0.24", recording = true, gpsFix = true,
        sdTotalBytes = 128_000_000_000, sdFreeBytes = 38_000_000_000, clockOffsetSeconds = 0,
        fetchedAtMs = now,
    )

    @Test fun home_camera() = shot("home_camera") {
        Home(
            ready().copy(
                cameraReachable = true, serverReachable = false,
                cameraDetail = "Z50 AP on Wi-Fi · DDPAI_Z50_xxxx", cameraStatus = cameraStatus,
            ),
        )
    }

    // 102 is a chip reading from the camera's own log on the 2026-10-01 midday drive.
    @Test fun home_camera_hot() = shot("home_camera_hot") {
        Home(
            ready().copy(
                cameraReachable = true, serverReachable = false,
                cameraDetail = "Z50 AP on Wi-Fi · DDPAI_Z50_xxxx", cameraStatus = cameraStatus.copy(socTemp = 102),
            ),
        )
    }

    @Test fun home_camera_stopped() = shot("home_camera_stopped") {
        Home(
            ready().copy(
                cameraReachable = true, serverReachable = false,
                cameraDetail = "Z50 AP on Wi-Fi · DDPAI_Z50_xxxx", cameraStatus = cameraStatus.copy(recording = false),
            ),
        )
    }

    @Composable
    private fun Settings(section: SettingsSection?, nearby: List<NearbyWifi> = emptyList()) = SettingsScreen(
        section = section,
        settingsFlow = flowOf(BridgeSettings(destHost = "192.168.1.100", cameraSsid = "DDPAI_Z50_xxxx", cameraWifiAutoConnect = true, offloadWifiAllowlist = setOf("HomeNet"))),
        stagedBytes = 1_700_000_000, freeBytes = 41_000_000_000, cameraWifiStatus = CameraWifiStatus.Registered,
        cameraReachable = true, cameraStatus = cameraStatus.copy(clockSyncedAtMs = now - 600_000),
        onBack = {}, onOpenSection = {}, onOpenDashcam = {}, onSave = {}, onTest = { "ok" }, onOpenSetup = {},
        onClearHistory = {}, buildDiagnostics = { "" },
        connectedWifi = "HomeNet",
        seenWifi = mapOf("HomeNet" to now, "Office 5G" to now - 3 * 86_400_000L, "Cafe Upstairs" to now - 9 * 86_400_000L),
        nearbyWifi = { flowOf(nearby) },
    )

    @Test fun settings() = shot("settings") { Settings(null) }

    @Test fun settings_offload_networks() {
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        shot("settings_offload_networks") {
            Settings(
                SettingsSection.OffloadNetworks,
                nearby = listOf(NearbyWifi("HomeNet", -48), NearbyWifi("Neighbour 2.4G", -70), NearbyWifi("DDPAI_Z50_xxxx", -40), NearbyWifi("Street Free WiFi", -84)),
            )
        }
    }

    @Test fun settings_offload_networks_no_permission() = shot("settings_offload_networks_no_permission") {
        Settings(SettingsSection.OffloadNetworks)
    }

    @Test fun settings_camera() = shot("settings_camera") { Settings(SettingsSection.Camera) }

    @Test fun settings_offload() = shot("settings_offload") { Settings(SettingsSection.Offload) }

    @Test fun dashcam_settings() = shot("dashcam_settings") {
        val values = listOf(
            DashcamValue("record_resolution", "2160P_1600P_1080P", false),
            DashcamValue("video_codec", "h264", false),
            DashcamValue("image_quality", "high", false),
            DashcamValue("wdr_enable", "on", false),
            DashcamValue("osd_switch", "on", false),
            DashcamValue("osd_speedswitch", "on", false),
            DashcamValue("mic_switch", "on", false),
            DashcamValue("speaker_turn", 33, true),
            DashcamValue("start_sound_switch", "on", false),
            DashcamValue("gsensor_mode_100level", 50, true),
            DashcamValue("event_before_time", 5, true),
            DashcamValue("event_after_time", 5, true),
            DashcamValue("parking_mode_switch", "on", false),
            DashcamValue("parking_status", "timelapse", false),
        ).associateBy { it.key }
        DashcamSettingsScreen(
            subtitle = "Z50 · v7.2.0.24", transferRunning = false,
            onLoad = { CameraResult.Ok(values) }, onSave = { _, _ -> CameraResult.Ok(null) },
            onStopTransfer = {}, onBack = {},
        )
    }

    @Test fun live_view() = shot("live_view") {
        LiveViewScreen(transferRunning = false, onStart = { CameraResult.Offline }, onStop = {}, onStopTransfer = {}, onBack = {})
    }

    @Test fun app_icon() = shot("app_icon") {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                AdaptiveIcon(R.drawable.ic_launcher_foreground, 160.dp, CircleShape)
                AdaptiveIcon(R.drawable.ic_launcher_foreground, 160.dp, RoundedCornerShape(30))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                AdaptiveIcon(R.drawable.ic_launcher_foreground, 56.dp, CircleShape)
                AdaptiveIcon(R.drawable.ic_launcher_foreground, 40.dp, CircleShape)
                AdaptiveIcon(R.drawable.ic_shortcut_sync_fg, 56.dp, CircleShape)
                AdaptiveIcon(R.drawable.ic_shortcut_offload_fg, 56.dp, CircleShape)
                // Themed (monochrome) icon on a Material You tint.
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFFDDE3EA)), contentAlignment = Alignment.Center) {
                    Image(
                        painterResource(R.drawable.ic_launcher_monochrome), null,
                        colorFilter = ColorFilter.tint(Color(0xFF26323D)),
                        modifier = Modifier.requiredSize(84.dp),
                    )
                }
                Image(
                    painterResource(R.drawable.ic_notification), null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    @Composable
    private fun AdaptiveIcon(foreground: Int, size: Dp, shape: Shape) {
        // Launchers show the middle 72 of the 108dp layers.
        Box(Modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_background), null, modifier = Modifier.requiredSize(size * 1.5f))
            Image(painterResource(foreground), null, modifier = Modifier.requiredSize(size * 1.5f))
        }
    }

    @Test fun setup_welcome() = shot("setup_welcome") {
        SetupWizardScreen(settingsFlow = flowOf(BridgeSettings()), onTest = { "ok" }, onSave = {}, onBack = {})
    }

    @Test fun setup_homelab() = shot("setup_homelab") {
        SetupWizardScreen(settingsFlow = flowOf(BridgeSettings(destHost = "192.168.1.100")), onTest = { "ok" }, onSave = {}, onBack = {}, initialStep = 1)
    }

    @Test fun setup_done() = shot("setup_done") {
        SetupWizardScreen(settingsFlow = flowOf(BridgeSettings()), onTest = { "ok" }, onSave = {}, onBack = {}, initialStep = 3)
    }
}

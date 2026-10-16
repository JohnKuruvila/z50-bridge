package io.github.johnkuruvila.z50bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.navigation.Z50NavShell
import io.github.johnkuruvila.z50bridge.service.BridgeService
import io.github.johnkuruvila.z50bridge.service.WatchSwitch
import io.github.johnkuruvila.z50bridge.ui.SetupWizardScreen
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.theme.Z50BridgeTheme
import androidx.core.content.ContextCompat as CoreContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val permissions = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 36) add("android.permission.ACCESS_LOCAL_NETWORK")
        // Wi-Fi names (the camera's, offload networks) are hidden from apps without it.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            (application as Z50App).controller.refreshNetworkHints()
        }
        if (grants["android.permission.ACCESS_LOCAL_NETWORK"] == false) {
            AppToasts.show(this, getString(R.string.permission_local_network_denied))
        }
        if (grants[Manifest.permission.POST_NOTIFICATIONS] == false) {
            AppToasts.show(this, getString(R.string.permission_notifications_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
        handleAction(intent)
        val controller = (application as Z50App).controller
        controller.refreshStaged()

        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val settings by controller.settings.flow.collectAsStateWithLifecycle(initialValue = BridgeSettings())
            Z50BridgeTheme(themeMode = settings.themeMode) {
                LaunchedEffect(Unit) { controller.promptSetupIfNeeded() }

                if (state.showSetup) {
                    SetupWizardScreen(
                        settingsFlow = controller.settings.flow,
                        onTest = { controller.testConnection(it) },
                        onSave = { next -> controller.settings.update { next } },
                        onBack = { controller.setShowSetup(false) },
                    )
                } else {
                    Z50NavShell(
                        controller = controller,
                        state = state,
                        needsSetup = settings.needsSetup(),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    override fun onResume() {
        super.onResume()
        (application as Z50App).controller.let {
            it.refreshStaged()
            it.refreshStatus()
            it.onPermissionsMaybeChanged()
            lifecycleScope.launch { WatchSwitch.ensureRunning(this@MainActivity, it) }
        }
    }

    private fun handleAction(intent: Intent?) {
        val action = intent?.action ?: return
        val service = Intent(this, BridgeService::class.java)
        when (action) {
            "io.github.johnkuruvila.z50bridge.SYNC" -> {
                service.action = BridgeService.ACTION_SYNC
                CoreContextCompat.startForegroundService(this, service)
            }
            "io.github.johnkuruvila.z50bridge.OFFLOAD" -> {
                service.action = BridgeService.ACTION_OFFLOAD
                CoreContextCompat.startForegroundService(this, service)
            }
        }
    }

    private fun permissionExists(name: String): Boolean =
        runCatching { packageManager.getPermissionInfo(name, 0) }.isSuccess

    private fun requestMissingPermissions() {
        // Skip permissions this Android release does not define: asking for
        // one always comes back denied, and its warning would show on every start.
        val missing = permissions.filter {
            permissionExists(it) && CoreContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

package io.github.johnkuruvila.z50bridge.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.johnkuruvila.z50bridge.BridgeController

/**
 * "Watch in background" is one saved choice. The Home switch and the
 * notification's Stop watching change it; phone restarts, app updates and
 * opening the app all start the watcher again while it is on.
 */
object WatchSwitch {
    suspend fun set(context: Context, controller: BridgeController, enabled: Boolean) {
        controller.settings.update { it.copy(autoWatch = enabled) }
        if (enabled) {
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
        } else {
            controller.stopTransfer(pauseAutomatic = false)
            controller.stopWatching()
            runCatching {
                context.startService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
            }
        }
    }

    /**
     * Starts the watcher if it should be running and is not: after an app
     * update, or when Android stopped the service. Only call this while the
     * app is in the foreground (or from a broadcast that may start one).
     */
    suspend fun ensureRunning(context: Context, controller: BridgeController) {
        if (controller.state.value.watching || !controller.settings.current().autoWatch) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
        }
    }
}

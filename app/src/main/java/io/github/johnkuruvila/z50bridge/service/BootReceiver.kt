package io.github.johnkuruvila.z50bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.johnkuruvila.z50bridge.Z50App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // An app update stops the service and Android does not restart it.
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // DataStore's first read can block; never do it on the main thread.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val watch = (context.applicationContext as Z50App).controller.settings.flow.first().autoWatch
                if (watch) {
                    ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

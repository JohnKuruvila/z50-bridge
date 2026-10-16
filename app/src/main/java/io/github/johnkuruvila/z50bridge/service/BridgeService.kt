package io.github.johnkuruvila.z50bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.johnkuruvila.z50bridge.BridgeController
import io.github.johnkuruvila.z50bridge.BridgePhase
import io.github.johnkuruvila.z50bridge.BridgeUiState
import io.github.johnkuruvila.z50bridge.MainActivity
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.Z50App
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import io.github.johnkuruvila.z50bridge.ui.common.formatEta
import io.github.johnkuruvila.z50bridge.ui.common.formatSpeed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BridgeService : LifecycleService() {
    private var lastCompletionMs = 0L

    /**
     * What the status notification last showed. The controller state changes
     * far more often than the notification's content (activity entries,
     * camera readings), so the status notification is only reposted when
     * this changes. Null after a progress notification or startForeground.
     */
    private var shownStatus: StatusContent? = null

    // Keeps the radio out of power save during a sync. From Android 14 the
    // platform turns HIGH_PERF into LOW_LATENCY, which only applies with the
    // screen on and the app in front and may lower throughput, so newer
    // releases take no lock at all.
    private val wifiLock by lazy {
        if (Build.VERSION.SDK_INT >= 34) return@lazy null
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "z50bridge:sync")?.apply {
            setReferenceCounted(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val controller = (application as Z50App).controller
        lifecycleScope.launch {
            controller.state.collectLatest { state ->
                val syncing = state.phase == BridgePhase.Syncing
                if (syncing) {
                    if (wifiLock?.isHeld == false) wifiLock?.acquire()
                } else {
                    if (wifiLock?.isHeld == true) wifiLock?.release()
                }
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val controller = (application as Z50App).controller
        return when (intent?.action) {
            // Stop watching: saved, so a restart or update does not turn it back on.
            ACTION_STOP -> {
                controller.stopTransfer(pauseAutomatic = false)
                controller.stopWatching()
                controller.saveWatchChoice(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            // Stop the running transfer only; watching carries on.
            ACTION_STOP_TRANSFER -> {
                controller.stopTransfer()
                val state = controller.state.value
                val transferring = state.phase == BridgePhase.Syncing || state.phase == BridgePhase.Offloading
                when {
                    state.watching -> START_STICKY
                    else -> {
                        // A one-shot Sync or Offload stops the service itself once it unwinds.
                        if (!transferring) stopSelf()
                        START_NOT_STICKY
                    }
                }
            }
            ACTION_SYNC -> {
                startForegroundMode(TYPE_CONNECTED_DEVICE, getString(R.string.service_title_sync), getString(R.string.service_connecting_camera))
                controller.syncNow(lifecycleScope) { finishOneShot(startId) }
                START_NOT_STICKY
            }
            ACTION_OFFLOAD -> {
                startForegroundMode(TYPE_DATA_SYNC, getString(R.string.common_offload), getString(R.string.service_connecting_homelab))
                controller.offloadNow(lifecycleScope) { finishOneShot(startId) }
                START_NOT_STICKY
            }
            else -> {
                startForegroundMode(TYPE_CONNECTED_DEVICE, getString(R.string.service_title_bridge), getString(R.string.service_waiting_wifi))
                if (intent != null) {
                    controller.startWatching(lifecycleScope)
                } else {
                    // Sticky restart: only resume the watcher if the user still wants it.
                    lifecycleScope.launch {
                        if (controller.settings.current().autoWatch) {
                            controller.startWatching(lifecycleScope)
                        } else {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelfResult(startId)
                        }
                    }
                }
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        val controller = (application as Z50App).controller
        if (controller.state.value.watching) controller.stopWatching()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        super.onDestroy()
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun updateNotification(state: BridgeUiState) {
        val nm = getSystemService(NotificationManager::class.java)
        val transferring = state.phase == BridgePhase.Syncing || state.phase == BridgePhase.Offloading
        val completed = state.lastCompleted
        if (completed != null && completed.timeMs != lastCompletionMs) {
            lastCompletionMs = completed.timeMs
            nm.notify(NOTIF_DONE, completionNotification(state))
        }
        val notification = when {
            transferring && state.progress != null && Build.VERSION.SDK_INT >= 36 -> progressNotification(state)
            transferring && state.progress != null -> compatProgressNotification(state)
            else -> {
                val content = statusContent(state)
                if (content == shownStatus) return
                shownStatus = content
                nm.notify(NOTIF_ID, buildNotification(state, content.title, content.text))
                return
            }
        }
        shownStatus = null
        nm.notify(NOTIF_ID, notification)
    }

    private data class StatusContent(
        val title: String,
        val text: String,
        val cameraReachable: Boolean,
        val transferring: Boolean,
        val offloadAction: Boolean,
    )

    /** Everything [buildNotification] shows for [state]. */
    private fun statusContent(state: BridgeUiState) = StatusContent(
        title = statusTitle(state),
        text = statusText(state),
        cameraReachable = state.cameraReachable,
        transferring = state.phase == BridgePhase.Syncing || state.phase == BridgePhase.Offloading,
        offloadAction = state.phoneFiles > 0,
    )

    /** Android 16+ Live Update: one progress segment per file in the batch. */
    @RequiresApi(36)
    private fun progressNotification(state: BridgeUiState): Notification {
        val progress = state.progress ?: return buildNotification(state)
        val total = progress.overallTotal ?: 0L
        val percent = if (total > 0) ((progress.overallCopied * 100) / total).toInt().coerceIn(0, 100) else 0
        val color = signalColor(progress.isOffload)
        val files = progress.totalFiles.coerceIn(1, MAX_SEGMENTS)
        val segments = (0 until files).map { index ->
            // Lengths sum to 100 so progress can stay a percentage.
            val length = 100 / files + if (index < 100 % files) 1 else 0
            Notification.ProgressStyle.Segment(length).setColor(color)
        }
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(segments)
            .setProgress(percent)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentTitle(transferTitle(progress.isOffload, progress.totalFiles))
            .setContentText(transferText(progress.overallCopied, progress.overallTotal, progress.bytesPerSecond))
            .setShortCriticalText(formatEta(progress.etaSeconds))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent())
            .setStyle(style)
            .addAction(stopAction())
            .build()
    }

    private fun compatProgressNotification(state: BridgeUiState): Notification {
        val progress = state.progress ?: return buildNotification(state)
        val total = progress.overallTotal ?: 0L
        val percent = if (total > 0) ((progress.overallCopied * 100) / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(signalColor(progress.isOffload))
            .setContentTitle(transferTitle(progress.isOffload, progress.totalFiles))
            .setContentText(transferText(progress.overallCopied, progress.overallTotal, progress.bytesPerSecond))
            .setSubText(formatEta(progress.etaSeconds))
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent())
            .addAction(0, getString(R.string.notif_stop_transfer), actionIntent(ACTION_STOP_TRANSFER))
            .build()
    }

    private fun completionNotification(state: BridgeUiState): Notification {
        val completed = state.lastCompleted
        val synced = completed?.synced == true
        val files = completed?.files ?: 0
        val text = resources.getQuantityString(
            if (synced) R.plurals.service_synced_files else R.plurals.service_offloaded_files,
            files,
            files,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(signalColor(offload = !synced))
            .setContentTitle(getString(if (synced) R.string.service_sync_complete else R.string.service_offload_complete))
            .setContentText(text)
            .setWhen(completed?.timeMs ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .apply {
                // Clips synced to the phone can be offloaded right away.
                if (synced && state.serverReachable) {
                    addAction(0, getString(R.string.common_offload), actionIntent(ACTION_OFFLOAD))
                }
            }
            .build()
    }

    private fun startForegroundMode(type: Int, title: String, text: String) {
        val notification = buildNotification(controller().state.value, title, text)
        shownStatus = null
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun finishOneShot(startId: Int) {
        if ((application as Z50App).controller.state.value.watching) {
            startForegroundMode(
                TYPE_CONNECTED_DEVICE,
                getString(R.string.service_title_bridge),
                getString(R.string.service_watching_wifi),
            )
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Not stopSelfResult(startId): a Stop pressed meanwhile is a newer
            // start, and the service would then never stop.
            stopSelf()
        }
    }

    /** Idle / watching status with only the actions that make sense right now. */
    private fun buildNotification(
        state: BridgeUiState,
        title: String = statusTitle(state),
        text: String = statusText(state),
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(if (state.cameraReachable) R.color.progress_sync else R.color.progress_offload))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val transferring = state.phase == BridgePhase.Syncing || state.phase == BridgePhase.Offloading
        if (transferring) {
            builder.addAction(0, getString(R.string.notif_stop_transfer), actionIntent(ACTION_STOP_TRANSFER))
        } else {
            builder.addAction(0, getString(R.string.common_sync), actionIntent(ACTION_SYNC))
            if (state.phoneFiles > 0) {
                builder.addAction(0, getString(R.string.common_offload), actionIntent(ACTION_OFFLOAD))
            }
            builder.addAction(0, getString(R.string.notif_stop_watching), actionIntent(ACTION_STOP))
        }
        return builder.build()
    }

    private fun statusTitle(state: BridgeUiState): String = getString(
        when {
            state.phase == BridgePhase.Syncing -> R.string.service_syncing_title
            state.phase == BridgePhase.Offloading -> R.string.service_offloading_title
            state.cameraReachable -> R.string.notif_camera_connected
            state.watching -> R.string.notif_watching
            else -> R.string.service_title_bridge
        },
    )

    private fun statusText(state: BridgeUiState): String {
        val staged = resources.getQuantityString(R.plurals.notif_staged, state.phoneFiles, state.phoneFiles)
        val home = getString(if (state.serverReachable) R.string.notif_homelab_online else R.string.notif_homelab_offline)
        return getString(R.string.notif_status_line, staged, home)
    }

    private fun transferTitle(offload: Boolean, files: Int): String = if (files > 0) {
        resources.getQuantityString(
            if (offload) R.plurals.home_headline_offloading_n else R.plurals.home_headline_syncing_n,
            files,
            files,
        )
    } else {
        getString(if (offload) R.string.service_offloading_title else R.string.service_syncing_title)
    }

    private fun transferText(copied: Long, total: Long?, bytesPerSecond: Long): String =
        getString(R.string.notif_progress_line, formatBytes(copied), total?.let { formatBytes(it) } ?: "—", formatSpeed(bytesPerSecond))

    private fun signalColor(offload: Boolean): Int =
        getColor(if (offload) R.color.progress_offload else R.color.progress_sync)

    @RequiresApi(36)
    private fun stopAction(): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(this, R.drawable.ic_widget_stop),
        getString(R.string.notif_stop_transfer),
        actionIntent(ACTION_STOP_TRANSFER),
    ).build()

    private fun controller() = (application as Z50App).controller

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, BridgeService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_bridge), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_ID = "z50_bridge"
        private const val TYPE_DATA_SYNC = 1
        private const val TYPE_CONNECTED_DEVICE = 1 shl 4
        const val NOTIF_ID = 50
        const val NOTIF_DONE = 51
        private const val MAX_SEGMENTS = 20
        const val ACTION_STOP = "io.github.johnkuruvila.z50bridge.STOP"
        const val ACTION_STOP_TRANSFER = "io.github.johnkuruvila.z50bridge.STOP_TRANSFER"
        const val ACTION_SYNC = "io.github.johnkuruvila.z50bridge.SYNC"
        const val ACTION_OFFLOAD = "io.github.johnkuruvila.z50bridge.OFFLOAD"
    }
}

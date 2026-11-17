package io.github.johnkuruvila.z50bridge.car

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.DrawableRes
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import io.github.johnkuruvila.z50bridge.BridgeController
import io.github.johnkuruvila.z50bridge.MainActivity
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.Z50App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Sustained problems only. Continuous progress belongs on the native screen:
 * posting it as new chat messages can interrupt the driver on some car hosts.
 */
class CarAlerts(private val app: Application, private val controller: BridgeController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val projecting = MutableStateFlow(false)
    private val nm = NotificationManagerCompat.from(app)

    @Volatile internal var filesAtDriveStart: Int = 0
        private set
    @Volatile private var driveStartedAtMs = System.currentTimeMillis()

    /** The last alert posted this drive, so Reply can re-post it unchanged. */
    @Volatile
    private var current: CarAlert? = null

    /** Mark as read was tapped: quiet updates stay away until the next pop-up. */
    @Volatile
    private var dismissed = false

    fun start() {
        ensureChannel()
        // Remove an old-version progress message on upgrade/process restart.
        nm.cancel(NOTIF_ID)
        // LiveData: observed on the main thread, from Application.onCreate.
        CarConnection(app).type.observeForever { type ->
            setProjecting(type == CarConnection.CONNECTION_TYPE_PROJECTION)
        }
        scope.launch {
            combine(projecting, controller.settings.flow.map { it.carAlerts }) { car, enabled -> car && enabled }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) {
                        clear()
                        return@collectLatest
                    }
                    val policy = CarAlertPolicy(driveStartedAtMs)
                    while (true) {
                        policy.next(controller.carSignals())?.let { post(it) }
                        delay(TICK_MS)
                    }
                }
        }
    }

    /**
     * Debug builds only: behave as if Android Auto were connected, so the
     * alerts can be checked on the emulator, which has no Android Auto.
     * `adb root`, then `adb shell am broadcast -n io.github.johnkuruvila.z50bridge/.car.CarAlertReceiver
     * -a io.github.johnkuruvila.z50bridge.CAR_SIMULATE --ez on true`.
     */
    internal fun simulate(on: Boolean) {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        setProjecting(on)
    }

    private fun setProjecting(on: Boolean) {
        if (on && !projecting.value) {
            driveStartedAtMs = System.currentTimeMillis()
            filesAtDriveStart = controller.carSignals().filesSynced
        }
        projecting.value = on
    }

    /** Mark as read: the entry goes until the next pop-up. */
    internal fun dismiss() {
        dismissed = true
        nm.cancel(NOTIF_ID)
    }

    /** Android Auto requires Reply for messages; replying dismisses the problem without a new message. */
    internal fun replied() {
        dismiss()
    }

    private fun clear() {
        current = null
        dismissed = false
        nm.cancel(NOTIF_ID)
    }

    private fun post(alert: CarAlert) {
        // Even silent MessagingStyle updates can be surfaced as new messages by
        // the head unit. Never send routine progress, start or recovery messages.
        if (!alert.popUp || alert.tone != CarTone.Problem) {
            current = null
            nm.cancel(NOTIF_ID)
            return
        }
        current = alert
        if (alert.popUp) dismissed = false
        if (dismissed) return
        if (!nm.areNotificationsEnabled()) return
        val dashcam = Person.Builder()
            .setKey("dashcam")
            .setName(app.getString(R.string.car_sender))
            .setIcon(IconCompat.createWithBitmap(avatar(alert.tone)))
            .build()
        val me = Person.Builder().setKey("me").setName(app.getString(R.string.car_me)).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setGroupConversation(false)
            .addMessage(alert.text, System.currentTimeMillis(), dashcam)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(app, colorOf(alert.tone)))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(!alert.popUp)
            .setSilent(!alert.popUp)
            .setContentIntent(openApp())
            .addAction(replyAction())
            .addInvisibleAction(markReadAction())
            .build()
        try {
            nm.notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission withdrawn; nothing to show.
        }
    }

    private fun replyAction(): NotificationCompat.Action {
        val input = RemoteInput.Builder(EXTRA_REPLY).setLabel(app.getString(R.string.car_reply)).build()
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, app.getString(R.string.car_reply), receiverIntent(ACTION_REPLY, mutable = true))
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(): NotificationCompat.Action =
        NotificationCompat.Action.Builder(R.drawable.ic_notification, app.getString(R.string.car_mark_read), receiverIntent(ACTION_MARK_READ, mutable = false))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

    private fun receiverIntent(action: String, mutable: Boolean): PendingIntent = PendingIntent.getBroadcast(
        app,
        action.hashCode(),
        Intent(app, CarAlertReceiver::class.java).setAction(action),
        // RemoteInput fills in the reply, so Reply's intent has to be mutable.
        (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app,
        NOTIF_ID,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A colored disc with a glyph; Android Auto shows it as the sender's picture. */
    private fun avatar(tone: CarTone): Bitmap {
        val size = AVATAR_PX
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(app, colorOf(tone)) }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        ContextCompat.getDrawable(app, glyphOf(tone))?.let { glyph ->
            val inset = (size * 0.24f).toInt()
            glyph.setBounds(inset, inset, size - inset, size - inset)
            glyph.setTint(ContextCompat.getColor(app, R.color.car_glyph))
            glyph.draw(canvas)
        }
        return bitmap
    }

    private fun colorOf(tone: CarTone): Int = when (tone) {
        CarTone.Problem -> R.color.car_problem
        CarTone.Quiet -> R.color.car_quiet
    }

    @DrawableRes
    private fun glyphOf(tone: CarTone): Int = when (tone) {
        CarTone.Problem -> R.drawable.ic_car_warning
        else -> R.drawable.ic_car_camera
    }

    private fun ensureChannel() {
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.channel_car), NotificationManager.IMPORTANCE_HIGH).apply {
                description = app.getString(R.string.channel_car_desc)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "z50_car"
        const val NOTIF_ID = 52
        const val ACTION_REPLY = "io.github.johnkuruvila.z50bridge.CAR_REPLY"
        const val ACTION_MARK_READ = "io.github.johnkuruvila.z50bridge.CAR_MARK_READ"
        const val ACTION_SIMULATE = "io.github.johnkuruvila.z50bridge.CAR_SIMULATE"
        private const val EXTRA_REPLY = "reply"
        private const val TICK_MS = 5_000L
        private const val AVATAR_PX = 192
    }
}

/** Reply and Mark as read from the car (or the phone's shade). */
class CarAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alerts = (context.applicationContext as Z50App).carAlerts
        when (intent.action) {
            CarAlerts.ACTION_MARK_READ -> alerts.dismiss()
            CarAlerts.ACTION_REPLY -> alerts.replied()
            CarAlerts.ACTION_SIMULATE -> alerts.simulate(intent.getBooleanExtra("on", true))
        }
    }
}

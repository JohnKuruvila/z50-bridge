package io.github.johnkuruvila.z50bridge.ui.common

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast

/** Shows short app issues from UI actions and background transfer workers. */
object AppToasts {
    private val main = Handler(Looper.getMainLooper())
    private var lastMessage: String? = null
    private var lastShownMs = 0L

    fun show(context: Context, message: String) {
        val app = context.applicationContext
        main.post {
            val now = SystemClock.elapsedRealtime()
            if (message == lastMessage && now - lastShownMs < 5_000L) return@post
            lastMessage = message
            lastShownMs = now
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }
}

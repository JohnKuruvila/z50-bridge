package io.github.johnkuruvila.z50bridge.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * When a file last reached the phone and when one last reached server, kept
 * on disk so the Home screen and diagnostics survive an app restart. Updated
 * per file, so a sync cut short by the ignition still counts.
 */
class TransferStamps(private val file: File) {
    @Volatile
    var lastSyncMs: Long? = null
        private set

    @Volatile
    var lastOffloadMs: Long? = null
        private set

    init {
        runCatching {
            val lines = file.readLines()
            lastSyncMs = lines.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0 }
            lastOffloadMs = lines.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
        }
    }

    @Synchronized
    fun markSync(nowMs: Long) {
        lastSyncMs = nowMs
        save()
    }

    @Synchronized
    fun markOffload(nowMs: Long) {
        lastOffloadMs = nowMs
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText("${lastSyncMs ?: 0}\n${lastOffloadMs ?: 0}\n")
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}

/** "21:50" today, "24 Sep, 21:50" before today, "never" without a stamp. */
fun formatTransferStamp(ms: Long?, nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
    if (ms == null) return "never"
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "d MMM, HH:mm"
    return SimpleDateFormat(pattern, locale).format(Date(ms))
}

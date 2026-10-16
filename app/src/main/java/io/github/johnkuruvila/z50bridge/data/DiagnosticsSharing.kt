package io.github.johnkuruvila.z50bridge.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * When the app sends its diagnostics report to the receiver on server: only
 * when Activity has something newer than the last report sent, and at most
 * once per [minIntervalMs] unless an offload just finished ([afterOffload]).
 */
class DiagnosticsSendPolicy(private val minIntervalMs: Long = 10 * 60_000L) {
    private var lastAttemptMs: Long? = null

    fun shouldSend(nowMs: Long, newestActivityMs: Long?, sentActivityMs: Long?, afterOffload: Boolean): Boolean {
        if (newestActivityMs == null) return false
        if (sentActivityMs != null && newestActivityMs <= sentActivityMs) return false
        val last = lastAttemptMs
        if (!afterOffload && last != null && nowMs - last < minIntervalMs) return false
        lastAttemptMs = nowMs
        return true
    }
}

/** `z50-diagnostics-20261014-183005.txt`: one file per report on server. */
fun diagnosticsUploadName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "z50-diagnostics-${UPLOAD_STAMP.withZone(zone).format(Instant.ofEpochMilli(nowMs))}.txt"

/** `z50-diagnostics-2026-10-14.txt`: one file per day in Downloads, rewritten as the day goes on. */
fun diagnosticsDailyName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "z50-diagnostics-${DAY_STAMP.withZone(zone).format(Instant.ofEpochMilli(nowMs))}.txt"

/** The newest Activity entry that is already on server, kept across restarts. */
class DiagnosticsSentMarker(private val file: File) {
    @Volatile
    var sentActivityMs: Long? = runCatching { file.readText().trim().toLongOrNull() }.getOrNull()
        private set

    fun mark(activityMs: Long) {
        sentActivityMs = activityMs
        runCatching { file.writeText("$activityMs\n") }
    }
}

/**
 * Keeps today's diagnostics report in Downloads/Z50 Bridge. Files there stay
 * when the app is uninstalled, so a drive's log survives a reinstall. The app
 * rewrites its own file for the day and deletes its own files after
 * [keepDays]; files left by an earlier install are no longer the app's to
 * touch, so they stay until removed by hand.
 */
class DiagnosticsMirror(context: Context, private val index: File, private val keepDays: Int = 7) {
    private val resolver = context.applicationContext.contentResolver

    @Synchronized
    fun write(text: String, nowMs: Long) {
        if (Build.VERSION.SDK_INT < 29) return
        val name = diagnosticsDailyName(nowMs)
        val known = readIndex()
        val bytes = text.toByteArray()
        val existing = known[name]?.let(Uri::parse)
        if (existing == null || !overwrite(existing, bytes)) {
            val created = create(name) ?: return
            if (!overwrite(created, bytes)) {
                runCatching { resolver.delete(created, null, null) }
                return
            }
            known[name] = created.toString()
        }
        // Names sort by date, so the oldest come last.
        known.keys.sortedDescending().drop(keepDays).forEach { old ->
            known.remove(old)?.let { runCatching { resolver.delete(Uri.parse(it), null, null) } }
        }
        writeIndex(known)
    }

    private fun create(name: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
        }
        return runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
    }

    private fun overwrite(uri: Uri, bytes: ByteArray): Boolean = try {
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
    } catch (_: Exception) {
        false
    }

    private fun readIndex(): MutableMap<String, String> =
        runCatching {
            index.readLines()
                .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap(LinkedHashMap())
        }.getOrDefault(LinkedHashMap())

    private fun writeIndex(known: Map<String, String>) {
        runCatching { index.writeText(known.entries.joinToString("") { "${it.key}\t${it.value}\n" }) }
    }

    companion object {
        const val FOLDER = "Z50 Bridge"
    }
}

private val UPLOAD_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
private val DAY_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

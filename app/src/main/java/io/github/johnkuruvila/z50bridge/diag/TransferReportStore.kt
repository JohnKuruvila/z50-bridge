package io.github.johnkuruvila.z50bridge.diag

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** One stored report; [notable] ones (failures, big syncs, speed tests) are kept longer. */
data class StoredReport(val text: String, val notable: Boolean)

/**
 * Keeps the newest [maxRoutine] routine and the newest [maxNotable] notable
 * reports, newest first. While driving the watcher syncs a clip or two every
 * minute, so a plain "last N" lost a failed or slow session within minutes.
 */
fun retainReports(reports: List<StoredReport>, maxRoutine: Int, maxNotable: Int): List<StoredReport> {
    var routine = 0
    var notable = 0
    return reports.filter { report ->
        if (report.notable) notable++ < maxNotable else routine++ < maxRoutine
    }
}

/** Recent transfer reports, newest first, kept across restarts for the diagnostics report. */
class TransferReportStore(
    private val file: File,
    private val maxRoutine: Int = 10,
    private val maxNotable: Int = 10,
) {
    @Synchronized
    fun load(): List<String> = entries().map { it.text }

    @Synchronized
    fun add(report: String, notable: Boolean = false) {
        val arr = JSONArray()
        retainReports(listOf(StoredReport(report, notable)) + entries(), maxRoutine, maxNotable).forEach {
            arr.put(JSONObject().put("text", it.text).put("notable", it.notable))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        try {
            Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    // Before 0.5.1 the file held plain strings; those count as routine.
    private fun entries(): List<StoredReport> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                when (val item = arr.opt(i)) {
                    is JSONObject -> item.optString("text").takeIf { it.isNotEmpty() }
                        ?.let { StoredReport(it, item.optBoolean("notable")) }
                    is String -> item.takeIf { it.isNotEmpty() }?.let { StoredReport(it, notable = false) }
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

package io.github.johnkuruvila.z50bridge.data

import io.github.johnkuruvila.z50bridge.ActivityItem
import io.github.johnkuruvila.z50bridge.ActivityKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Append-only, capped activity log that survives process death. */
class ActivityStore(private val file: File, private val maxItems: Int = 200) {
    @Synchronized
    fun load(): List<ActivityItem> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { index ->
                val obj = arr.optJSONObject(index) ?: return@mapNotNull null
                ActivityItem(
                    timeMs = obj.optLong("timeMs"),
                    text = obj.optString("text"),
                    error = obj.optBoolean("error"),
                    kind = runCatching { ActivityKind.valueOf(obj.optString("kind")) }
                        .getOrDefault(ActivityKind.System),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun append(item: ActivityItem) {
        write(listOf(item) + load())
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun write(items: List<ActivityItem>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        items.take(maxItems).forEach { item ->
            arr.put(
                JSONObject()
                    .put("timeMs", item.timeMs)
                    .put("text", item.text)
                    .put("error", item.error)
                    .put("kind", item.kind.name),
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        try {
            Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING)
        }
    }
}

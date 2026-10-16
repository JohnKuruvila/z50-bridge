package io.github.johnkuruvila.z50bridge.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class HistoryStore(private val file: File) {
    @Synchronized
    fun names(): MutableSet<String> {
        if (!file.exists()) return mutableSetOf()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("downloaded_files") ?: JSONArray()
            mutableSetOf<String>().apply {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        } catch (_: Exception) {
            preserveCorruptFile()
            mutableSetOf()
        }
    }

    @Synchronized
    fun add(name: String) {
        val current = names()
        current.add(name)
        save(current)
    }

    @Synchronized
    fun remove(name: String) {
        removeAll(listOf(name))
    }

    @Synchronized
    fun addAll(names: Collection<String>) {
        if (names.isEmpty()) return
        val current = names()
        if (current.addAll(names)) save(current)
    }

    /** Forgets ledger entries by camera name, matching on basename too. One read/write. */
    @Synchronized
    fun removeAll(names: Collection<String>) {
        if (names.isEmpty()) return
        val targets = names.toSet()
        val current = names()
        val removed = current.removeAll { entry ->
            entry in targets || entry.substringAfterLast('/') in targets
        }
        if (removed) save(current)
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    @Synchronized
    fun size(): Int = names().size

    private fun preserveCorruptFile() {
        try {
            Files.move(file.toPath(), File(file.path + ".corrupt").toPath(), REPLACE_EXISTING)
        } catch (_: Exception) {
        }
    }

    private fun save(current: Set<String>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        current.sorted().forEach { arr.put(it) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("downloaded_files", arr).toString(2))
        try {
            Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING)
        }
    }
}

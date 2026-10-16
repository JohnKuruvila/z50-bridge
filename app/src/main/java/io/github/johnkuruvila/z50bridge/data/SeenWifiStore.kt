package io.github.johnkuruvila.z50bridge.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wi-Fi networks the phone has been connected to while the app ran, with when
 * each was last seen. Android no longer lets apps list saved networks, so the
 * offload network picker offers these instead. One "epochMs<TAB>ssid" per line.
 */
class SeenWifiStore(private val file: File, private val limit: Int = 30) {
    private val _networks = MutableStateFlow(load())
    val networks: StateFlow<Map<String, Long>> = _networks

    @Synchronized
    fun record(ssid: String, nowMs: Long) {
        if (ssid.isBlank() || '\t' in ssid || '\n' in ssid) return
        val next = _networks.value
            .filterKeys { !it.equals(ssid, ignoreCase = true) }
            .plus(ssid to nowMs)
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .associate { it.key to it.value }
        _networks.value = next
        save(next)
    }

    private fun load(): Map<String, Long> = runCatching {
        file.readLines().mapNotNull { line ->
            val ms = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
            val ssid = line.substringAfter('\t', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ssid to ms
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun save(networks: Map<String, Long>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(networks.entries.joinToString("") { "${it.value}\t${it.key}\n" })
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}

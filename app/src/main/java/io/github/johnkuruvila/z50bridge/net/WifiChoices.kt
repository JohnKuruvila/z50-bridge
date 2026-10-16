package io.github.johnkuruvila.z50bridge.net

/** A Wi-Fi network from the latest scan; [level] is the RSSI in dBm. */
data class NearbyWifi(val ssid: String, val level: Int)

/** One row of the offload network picker. */
data class WifiChoice(
    val ssid: String,
    val selected: Boolean,
    val connected: Boolean = false,
    val nearbyLevel: Int? = null,
    val lastSeenMs: Long? = null,
)

/** The Z50's own access point never belongs in the offload list. */
fun isCameraSsid(ssid: String, cameraSsid: String = ""): Boolean =
    ssid.startsWith(CAMERA_SSID_PREFIX, ignoreCase = true) ||
        (cameraSsid.isNotBlank() && ssid.equals(cameraSsid.trim(), ignoreCase = true))

/**
 * Merges every place a network name can come from into one list, one row per
 * name (matched without case, like the allowlist itself). The order does not
 * depend on what is ticked, so rows stay put while the user ticks them:
 * the connected network, then networks seen before (newest first), then
 * nearby ones (strongest first), then listed names found nowhere else.
 */
fun buildWifiChoices(
    allowlist: Set<String>,
    connected: String?,
    seen: Map<String, Long>,
    nearby: List<NearbyWifi>,
    cameraSsid: String = "",
): List<WifiChoice> {
    val rows = LinkedHashMap<String, WifiChoice>()
    fun merge(raw: String?, change: (WifiChoice) -> WifiChoice) {
        val ssid = normalizeSsid(raw) ?: return
        val key = ssid.lowercase()
        val listed = allowlist.any { it.equals(ssid, ignoreCase = true) }
        if (key !in rows && !listed && isCameraSsid(ssid, cameraSsid)) return
        rows[key] = change(rows[key] ?: WifiChoice(ssid = ssid, selected = listed))
    }
    merge(connected) { it.copy(connected = true) }
    seen.entries.sortedByDescending { it.value }.forEach { (ssid, at) ->
        merge(ssid) { it.copy(lastSeenMs = maxOf(it.lastSeenMs ?: 0L, at)) }
    }
    nearby.sortedByDescending { it.level }.forEach { wifi ->
        merge(wifi.ssid) { it.copy(nearbyLevel = maxOf(it.nearbyLevel ?: Int.MIN_VALUE, wifi.level)) }
    }
    allowlist.sortedBy { it.lowercase() }.forEach { merge(it) { row -> row } }
    return rows.values.toList()
}

/** Adds or removes [ssid], dropping any differently-cased copy of it. */
fun toggleSsid(allowlist: Set<String>, ssid: String, on: Boolean): Set<String> {
    val name = normalizeSsid(ssid) ?: return allowlist
    val rest = allowlist.filterNot { it.equals(name, ignoreCase = true) }.toSet()
    return if (on) rest + name else rest
}

private const val CAMERA_SSID_PREFIX = "DDPAI_"

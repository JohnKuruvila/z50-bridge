package io.github.johnkuruvila.z50bridge.net

/**
 * Wi-Fi SSID matching for the offload allowlist. An empty allowlist means
 * "any Wi-Fi is fine"; otherwise offload is limited to the listed SSIDs.
 * VPN routes over Wi-Fi are always eligible so the WireGuard remote-offload
 * path keeps working away from an allowed network. Mobile data never is,
 * with or without the VPN.
 */

const val UNKNOWN_SSID = "<unknown ssid>"

fun normalizeSsid(raw: String?): String? =
    raw?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

/** Reads the stored allowlist: one network per line, since names may contain commas. */
fun decodeSsidAllowlist(raw: String): Set<String> =
    raw.split('\n')
        .mapNotNull { normalizeSsid(it) }
        .toSet()

fun ssidAllowed(ssid: String?, allowlist: Set<String>): Boolean {
    if (allowlist.isEmpty()) return true
    val candidate = normalizeSsid(ssid) ?: return false
    return allowlist.any { it.equals(candidate, ignoreCase = true) }
}

fun offloadAllowed(hint: NetworkHint, allowlist: Set<String>): Boolean {
    if (hint.cellular) return false
    if (allowlist.isEmpty()) return true
    if (hint.vpn) return true
    return hint.wifi && ssidAllowed(hint.ssid, allowlist)
}

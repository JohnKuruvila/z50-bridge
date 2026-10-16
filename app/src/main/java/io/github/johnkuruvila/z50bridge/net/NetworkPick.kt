package io.github.johnkuruvila.z50bridge.net

enum class NetworkUse {
    Camera,
    Offload,
}

data class NetworkHint(
    val wifi: Boolean = false,
    val vpn: Boolean = false,
    val cellular: Boolean = false,
    val wired: Boolean = false,
    val internet: Boolean = false,
    /** Android confirmed it reaches the internet, which the camera's own Wi-Fi never does. */
    val validated: Boolean = false,
    val ssid: String? = null,
)

/**
 * How well a network suits [use], or -1 to skip it. Offload never uses mobile
 * data, not even through a VPN tunnel that rides on it. A Wi-Fi network named
 * like the camera ([cameraSsid] or the DDPAI_ default) always gets a camera
 * probe, whatever Android thinks of its internet access.
 */
fun networkScore(use: NetworkUse, hint: NetworkHint, cameraSsid: String = ""): Int {
    return when (use) {
        NetworkUse.Camera -> when {
            hint.vpn || hint.cellular -> -1
            hint.wifi && hint.ssid != null && isCameraSsid(hint.ssid, cameraSsid) -> 120
            // The camera's 193.168.0.1 is a public address (Deutsche Telekom),
            // so on home Wi-Fi a probe would go out to the internet and could
            // mistake a stranger's server for the camera.
            hint.validated -> -1
            hint.wifi && !hint.internet -> 110
            hint.wifi -> 100
            hint.wired -> 10
            else -> 0
        }
        NetworkUse.Offload -> when {
            hint.cellular -> -1
            hint.vpn -> 60
            (hint.wifi || hint.wired) && hint.internet -> 100
            else -> -1
        }
    }
}

/**
 * Android normally lists a VPN's underlying transports (Wi-Fi, cellular) in
 * its capabilities. When a VPN reports none, judge it by the phone's other
 * networks: without a Wi-Fi or wired network that has internet, the tunnel
 * can only be running over mobile data, so it is marked cellular.
 */
fun resolveVpnUnderlay(vpn: NetworkHint, others: Collection<NetworkHint>): NetworkHint {
    if (!vpn.vpn || vpn.wifi || vpn.wired || vpn.cellular) return vpn
    val fixedLine = others.any { !it.vpn && (it.wifi || it.wired) && it.internet }
    return if (fixedLine) vpn else vpn.copy(cellular = true)
}

fun networkLabel(hint: NetworkHint): String {
    return when {
        hint.vpn -> "VPN"
        hint.wifi && !hint.internet -> hint.ssid?.let { "camera Wi-Fi · $it" } ?: "camera Wi-Fi"
        hint.wifi -> hint.ssid?.let { "Wi-Fi · $it" } ?: "Wi-Fi"
        hint.wired -> "USB/ethernet"
        hint.cellular -> "mobile data"
        else -> "network"
    }
}

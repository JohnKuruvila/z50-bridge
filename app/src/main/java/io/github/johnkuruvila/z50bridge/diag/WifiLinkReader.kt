package io.github.johnkuruvila.z50bridge.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Reads the phone's side of the Wi-Fi link for one network: frequency, RSSI
 * and negotiated link rates. Sentinel values Android uses for "unknown" are
 * reported as null.
 */
class WifiLinkReader(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun read(network: Network?): WifiLinkSample {
        val now = System.currentTimeMillis()
        val info = infoFor(network) ?: return WifiLinkSample(atMs = now)
        return WifiLinkSample(
            atMs = now,
            frequencyMhz = info.frequency.takeIf { it > 0 },
            rssiDbm = info.rssi.takeIf { it in -126..-1 },
            rxLinkMbps = if (Build.VERSION.SDK_INT >= 29) known(info.rxLinkSpeedMbps) else null,
            txLinkMbps = if (Build.VERSION.SDK_INT >= 29) known(info.txLinkSpeedMbps) else known(info.linkSpeed),
            maxRxLinkMbps = if (Build.VERSION.SDK_INT >= 30) known(info.maxSupportedRxLinkSpeedMbps) else null,
            standard = if (Build.VERSION.SDK_INT >= 30) standardName(info.wifiStandard) else null,
        )
    }

    private fun infoFor(network: Network?): WifiInfo? {
        if (network != null && Build.VERSION.SDK_INT >= 29) {
            val fromCaps = runCatching { cm.getNetworkCapabilities(network)?.transportInfo as? WifiInfo }.getOrNull()
            if (fromCaps != null) return fromCaps
        }
        // Older releases only expose the primary connection's info.
        @Suppress("DEPRECATION")
        return runCatching { wifi?.connectionInfo }.getOrNull()
    }

    private fun known(value: Int): Int? = value.takeIf { it > 0 }

    private fun standardName(standard: Int): String? = when (standard) {
        ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
        ScanResult.WIFI_STANDARD_11N -> "802.11n (Wi-Fi 4)"
        ScanResult.WIFI_STANDARD_11AC -> "802.11ac (Wi-Fi 5)"
        ScanResult.WIFI_STANDARD_11AX -> "802.11ax (Wi-Fi 6)"
        ScanResult.WIFI_STANDARD_11AD -> "802.11ad"
        ScanResult.WIFI_STANDARD_11BE -> "802.11be (Wi-Fi 7)"
        else -> null
    }
}

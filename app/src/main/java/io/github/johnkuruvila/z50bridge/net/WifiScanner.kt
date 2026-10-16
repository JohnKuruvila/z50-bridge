package io.github.johnkuruvila.z50bridge.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Nearby Wi-Fi names for the offload network picker, and whether the camera
 * is in range for auto-connect. Android stopped letting
 * apps read the phone's saved networks in Android 10, so a scan (plus the
 * networks the app has seen the phone join) is the closest substitute. Needs
 * precise location permission; without it the list is empty.
 */
class WifiScanner(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)

    /** The cached scan right away, then each new scan. Asks for one scan on start. */
    fun nearby(): Flow<List<NearbyWifi>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(read())
            }
        }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(read())
        // Throttled by Android to 4 scans per 2 minutes; a refused scan just
        // leaves the cached results in place.
        @Suppress("DEPRECATION")
        runCatching { wifi?.startScan() }
        awaitClose { runCatching { app.unregisterReceiver(receiver) } }
    }

    @SuppressLint("MissingPermission")
    private fun read(): List<NearbyWifi> = try {
        wifi?.scanResults.orEmpty()
            .mapNotNull { result -> normalizeSsid(ssidOf(result))?.let { NearbyWifi(it, result.level) } }
            .groupBy { it.ssid.lowercase() }
            .map { (_, same) -> same.maxBy { it.level } }
    } catch (_: SecurityException) {
        emptyList()
    }

    /**
     * Whether [ssid] was in a scan within the last [maxAgeMs]. Reads Android's
     * cached results, which the system's own scans keep fresh too. Android
     * returns no results when the app may not use location right now (in the
     * background that needs "Allow all the time"), so this is false then.
     */
    @SuppressLint("MissingPermission")
    fun sees(ssid: String, maxAgeMs: Long): Boolean {
        val target = ssid.trim()
        if (target.isEmpty()) return false
        val nowUs = SystemClock.elapsedRealtime() * 1000
        return try {
            wifi?.scanResults.orEmpty().any { result ->
                normalizeSsid(ssidOf(result)) == target && nowUs - result.timestamp <= maxAgeMs * 1000
            }
        } catch (_: SecurityException) {
            false
        }
    }

    /** Asks for a scan; Android allows 4 per 2 minutes and ignores the rest. */
    fun requestScan() {
        @Suppress("DEPRECATION")
        runCatching { wifi?.startScan() }
    }

    private fun ssidOf(result: ScanResult): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            result.wifiSsid?.toString()
        } else {
            @Suppress("DEPRECATION")
            result.SSID
        }
}

/**
 * Whether the app may read scan results while in the background. Android
 * returns none to a background app that only has "Allow while using the app",
 * even one running a foreground service.
 */
fun hasBackgroundLocation(context: Context): Boolean {
    val granted = { permission: String ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
    if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return false
    return Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
}

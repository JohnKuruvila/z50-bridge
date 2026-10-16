package io.github.johnkuruvila.z50bridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build

data class CameraWifiConfig(
    val enabled: Boolean,
    val ssid: String,
    val passphrase: String,
)

enum class CameraWifiStatus {
    Off,
    Pending,
    Registered,
    NeedsAndroid,
    Blocked,
    Failed,
}

/**
 * Auto-connects to the camera AP through the suggestion API. Apps cannot call
 * [WifiManager.connect] since Android 10, but a network suggestion survives
 * reboots and makes the phone join the SSID on its own once the user approves
 * Z50 Bridge as a Wi-Fi controller.
 */
class CameraWifi(
    context: Context,
    private val onApprovalChanged: (CameraWifiStatus) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var active = false

    /**
     * Latest approval reported by the platform. Android delivers the current
     * status as soon as the listener is registered, which can happen before
     * [apply] runs, so it is kept even while inactive.
     */
    @Volatile
    private var approval: CameraWifiStatus? = null

    private val approvalListener: Any? = if (Build.VERSION.SDK_INT >= 33) {
        WifiManager.SuggestionUserApprovalStatusListener { status ->
            val mapped = approvalStatus(status)
            approval = mapped
            if (active) onApprovalChanged(mapped)
        }
    } else {
        null
    }

    init {
        listenForApproval()
    }

    /**
     * (Re)registers the approval listener. Registration delivers the current
     * status immediately, so re-registering after an add asks Android for the
     * decision instead of waiting for a change that may never come.
     */
    private fun listenForApproval() {
        if (Build.VERSION.SDK_INT < 33) return
        val listener = approvalListener as WifiManager.SuggestionUserApprovalStatusListener
        runCatching { wifi.removeSuggestionUserApprovalStatusListener(listener) }
        runCatching { wifi.addSuggestionUserApprovalStatusListener(appContext.mainExecutor, listener) }
    }

    fun apply(config: CameraWifiConfig): CameraWifiStatus {
        if (Build.VERSION.SDK_INT < 29) return CameraWifiStatus.NeedsAndroid
        val ssid = config.ssid.trim()
        if (!config.enabled || ssid.isBlank()) {
            active = false
            removeWhere { true }
            return CameraWifiStatus.Off
        }
        // Android 11+ updates a suggestion with the same SSID in place, so only
        // suggestions for a different (old) camera SSID are removed. Removing and
        // re-adding on every start reset the network's auto-join state.
        if (Build.VERSION.SDK_INT >= 30) removeWhere { unquote(it.ssid) != ssid }
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        if (config.passphrase.isNotBlank()) {
            builder.setWpa2Passphrase(config.passphrase)
        }
        builder.setIsAppInteractionRequired(false)
        builder.setPriority(100)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setIsInitialAutojoinEnabled(true)
        }
        return try {
            when (wifi.addNetworkSuggestions(listOf(builder.build()))) {
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
                // Only Android 10 reports this; the suggestion is already in place.
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE,
                -> {
                    active = true
                    listenForApproval()
                    registeredStatus()
                }
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> {
                    active = false
                    CameraWifiStatus.Blocked
                }
                else -> {
                    active = false
                    CameraWifiStatus.Failed
                }
            }
        } catch (_: Exception) {
            active = false
            CameraWifiStatus.Failed
        }
    }

    /**
     * Connects directly to the camera AP via [WifiNetworkSpecifier] on Android 10+.
     *
     * Unlike [WifiNetworkSuggestion], which is subject to the OS's opportunistic
     * internet scoring and captive portal checks, a specifier request binds the
     * network directly to the app without dropping mobile data for other apps.
     * With a [timeoutMs], Android gives up after that long and reports
     * [onFailed]; without one the request waits until released.
     */
    fun connectDirect(
        config: CameraWifiConfig,
        timeoutMs: Int = 0,
        onConnected: (Network) -> Unit = {},
        onLost: () -> Unit = {},
        onFailed: (String) -> Unit = {},
    ): ConnectivityManager.NetworkCallback? {
        if (Build.VERSION.SDK_INT < 29) {
            onFailed("Direct connection requires Android 10+")
            return null
        }
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: run {
                onFailed("Connectivity service unavailable")
                return null
            }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(config.ssid.trim())
            .apply {
                if (config.passphrase.isNotBlank()) {
                    setWpa2Passphrase(config.passphrase)
                }
            }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onConnected(network)
            }
            override fun onLost(network: Network) {
                onLost()
            }
            override fun onUnavailable() {
                onFailed("Camera connection unavailable or dismissed")
            }
        }
        return try {
            if (timeoutMs > 0) {
                cm.requestNetwork(request, callback, timeoutMs)
            } else {
                cm.requestNetwork(request, callback)
            }
            callback
        } catch (exc: Exception) {
            onFailed(exc.message ?: "Could not request camera network")
            null
        }
    }

    fun releaseDirect(callback: ConnectivityManager.NetworkCallback?) {
        if (callback == null) return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    // Before Android 13 there is no approval callback; a successful add is the
    // best signal available.
    private fun registeredStatus(): CameraWifiStatus {
        if (Build.VERSION.SDK_INT < 33) return CameraWifiStatus.Registered
        return approval ?: CameraWifiStatus.Pending
    }

    private fun approvalStatus(status: Int): CameraWifiStatus =
        when (status) {
            WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_USER,
            WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_CARRIER_PRIVILEGE,
            -> CameraWifiStatus.Registered

            WifiManager.STATUS_SUGGESTION_APPROVAL_REJECTED_BY_USER -> CameraWifiStatus.Blocked
            // PENDING, or UNKNOWN before Android has recorded a decision.
            else -> CameraWifiStatus.Pending
        }

    private fun removeWhere(predicate: (WifiNetworkSuggestion) -> Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val stale = wifi.networkSuggestions.filter(predicate)
            if (stale.isEmpty()) return@runCatching
            if (Build.VERSION.SDK_INT >= 33) {
                // Linger keeps an active camera connection alive while replacing it.
                wifi.removeNetworkSuggestions(stale, WifiManager.ACTION_REMOVE_SUGGESTION_LINGER)
            } else {
                wifi.removeNetworkSuggestions(stale)
            }
        }
    }

    private fun unquote(ssid: String?): String = ssid.orEmpty().removeSurrounding("\"")
}

package io.github.johnkuruvila.z50bridge.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.ConnectionPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val hints = ConcurrentHashMap<Network, NetworkHint>()
    private val clients = ConcurrentHashMap<Network, OkHttpClient>()
    private val thread = HandlerThread("z50-net").apply { start() }
    private val handler = Handler(thread.looper)

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits whenever a network appears or disappears, so watchers can react immediately. */
    val changes: SharedFlow<Unit> = _changes

    private val _connectedWifi = MutableStateFlow<String?>(null)

    /** The camera's SSID from Settings; a network with this name always gets a camera probe. */
    @Volatile
    var cameraSsid: String = ""

    /** Name of the Wi-Fi network with internet the phone is on; null without location permission. */
    val connectedWifi: StateFlow<String?> = _connectedWifi

    /**
     * Re-registering the all-networks observer makes Android deliver fresh
     * capabilities (and therefore SSIDs) after the user grants location
     * permission, without emitting a fake connectivity change.
     */
    @Volatile
    private var quietRefresh = false

    private val allRequest = NetworkRequest.Builder()
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    private val wifiRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private val allCallback = buildCallback()
    private val wifiCallback = buildCallback()

    init {
        cm.registerNetworkCallback(allRequest, allCallback, handler)
        try {
            cm.requestNetwork(wifiRequest, wifiCallback, handler)
        } catch (_: RuntimeException) {
            cm.registerNetworkCallback(wifiRequest, wifiCallback, handler)
        }
    }

    /**
     * Location-gated fields (the Wi-Fi SSID) only travel on callbacks created
     * with FLAG_INCLUDE_LOCATION_INFO, and only when the app holds location
     * permission. Both callbacks ask for them.
     */
    private fun buildCallback(): ConnectivityManager.NetworkCallback {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
            ) {
                override fun onAvailable(network: Network) = handleAvailable(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCapabilities(network, caps)
                override fun onLost(network: Network) = handleLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = handleAvailable(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCapabilities(network, caps)
                override fun onLost(network: Network) = handleLost(network)
            }
        }
    }

    private fun handleAvailable(network: Network) {
        remember(network)
        if (!quietRefresh) _changes.tryEmit(Unit)
    }

    private fun handleCapabilities(network: Network, caps: NetworkCapabilities) {
        hints[network] = hintFrom(caps)
        cutTunnelsOnMobileData()
        updateConnectedWifi()
    }

    private fun updateConnectedWifi() {
        _connectedWifi.value = hints.values.firstOrNull { !it.vpn && it.wifi && it.internet && it.ssid != null }?.ssid
    }

    private fun handleLost(network: Network) {
        hints.remove(network)
        clients.remove(network)?.let { client ->
            runCatching { client.dispatcher.cancelAll() }
            runCatching { client.connectionPool.evictAll() }
        }
        cutTunnelsOnMobileData()
        updateConnectedWifi()
        if (!quietRefresh) _changes.tryEmit(Unit)
    }

    /** True when traffic on [network] would go out over mobile data. */
    private fun onMobileData(network: Network): Boolean {
        val hint = hintOf(network) ?: return false
        return resolveVpnUnderlay(hint, hints.values).cellular
    }

    /**
     * A VPN keeps its Network handle when its tunnel moves from Wi-Fi to
     * mobile data, so a transfer bound to it would carry on over cellular.
     * Abort such calls; [MobileDataGuard] refuses any new ones.
     */
    private fun cutTunnelsOnMobileData() {
        clients.forEach { (network, client) ->
            if (onMobileData(network)) runCatching { client.dispatcher.cancelAll() }
        }
    }

    /** Refresh cached capabilities so a freshly granted permission takes effect. */
    fun refreshHints() {
        handler.post {
            quietRefresh = true
            runCatching { cm.unregisterNetworkCallback(allCallback) }
            runCatching { cm.registerNetworkCallback(allRequest, allCallback, handler) }
            handler.postDelayed({ quietRefresh = false }, REFRESH_QUIET_MS)
        }
    }

    /**
     * Probes every candidate network in parallel and returns the best-scoring
     * route that can actually open [host]:[port]. [eligible] can veto routes
     * that the caller's policy forbids (for example SSID allowlists).
     */
    suspend fun networkFor(
        use: NetworkUse,
        host: String,
        port: Int,
        timeoutMs: Int = 2_000,
        eligible: ((NetworkHint) -> Boolean)? = null,
    ): Network? =
        coroutineScope {
            val known = candidates().mapNotNull { network -> hintOf(network)?.let { network to it } }
            val others = known.map { it.second }
            val ranked = known
                .mapNotNull { (network, raw) ->
                    val hint = resolveVpnUnderlay(raw, others)
                    val score = networkScore(use, hint, cameraSsid)
                    when {
                        score < 0 -> null
                        eligible != null && !eligible(hint) -> null
                        else -> network to score
                    }
                }
                .sortedByDescending { it.second }
            if (ranked.isEmpty()) return@coroutineScope null
            val probes = ranked.map { (network, score) ->
                async(Dispatchers.IO) {
                    Triple(network, score, canConnect(network, host, port, timeoutMs))
                }
            }
            probes.awaitAll()
                .filter { it.third }
                .maxByOrNull { it.second }
                ?.first
        }

    fun describe(network: Network): String = networkLabel(hintOf(network) ?: NetworkHint())

    /** False once Android has torn [network] down; a later connection gets a new handle. */
    fun isConnected(network: Network): Boolean = cm.getNetworkCapabilities(network) != null

    /**
     * The phone's Wi-Fi network named like the camera, if it is on one. Needs
     * location permission to read names; without it this is always null.
     */
    fun cameraWifi(): NetworkHint? {
        val ssid = cameraSsid
        return candidates().mapNotNull { hintOf(it) }
            .firstOrNull { it.wifi && !it.vpn && it.ssid != null && isCameraSsid(it.ssid, ssid) }
    }

    /**
     * One client per network, cached so watch mode does not leak connection
     * pools and sockets every cycle. Evicted when the network goes away.
     */
    fun httpClient(network: Network): OkHttpClient = clients.getOrPut(network) {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
            .socketFactory(TunedSocketFactory(network.socketFactory))
            .dns(NetworkDns(network))
            .addInterceptor(MobileDataGuard { onMobileData(network) })
            .build()
    }

    /** Aborts every in-flight request so "Stop" also stops blocking transfers. */
    fun cancelActive() {
        clients.values.forEach { client ->
            runCatching { client.dispatcher.cancelAll() }
        }
    }

    private fun candidates(): List<Network> {
        val seen = LinkedHashSet<Network>()
        seen.addAll(hints.keys)
        seen.addAll(cm.allNetworks)
        return seen.toList()
    }

    private fun remember(network: Network) {
        cm.getNetworkCapabilities(network)?.let { hints[network] = hintFrom(it) }
    }

    private fun hintOf(network: Network): NetworkHint? {
        return hints[network] ?: cm.getNetworkCapabilities(network)?.let { hintFrom(it) }
    }

    private fun hintFrom(caps: NetworkCapabilities) = NetworkHint(
        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        wired = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            (Build.VERSION.SDK_INT >= 31 && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)),
        internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        ssid = ssidFrom(caps),
    )

    /** Reads the connected Wi-Fi name; null unless location permission is held. */
    private fun ssidFrom(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        return normalizeSsid(info.ssid)
    }

    private fun canConnect(network: Network, host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            network.socketFactory.createSocket().use { socket ->
                tuneSocket(socket)
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun tuneSocket(socket: Socket) {
            runCatching { socket.tcpNoDelay = true }
            runCatching { socket.sendBufferSize = SOCKET_BUFFER }
            runCatching { socket.receiveBufferSize = SOCKET_BUFFER }
            // What the kernel actually granted, for diagnostics.
            runCatching { lastReceiveBufferBytes = socket.receiveBufferSize }
        }

        @Volatile var lastReceiveBufferBytes: Int? = null

        const val SOCKET_BUFFER = 1024 * 1024
        private const val REFRESH_QUIET_MS = 1_000L
    }
}

private class TunedSocketFactory(private val delegate: SocketFactory) : SocketFactory() {
    override fun createSocket(): Socket = tune(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = tune(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        tune(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = tune(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        tune(delegate.createSocket(address, port, localAddress, localPort))

    private fun tune(socket: Socket): Socket = socket.also { NetworkMonitor.tuneSocket(it) }
}

private class MobileDataGuard(private val onMobileData: () -> Boolean) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        if (onMobileData()) throw java.io.IOException("Blocked: this route is on mobile data")
        return chain.proceed(chain.request())
    }
}

private class NetworkDns(private val network: Network) : okhttp3.Dns {
    override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
}

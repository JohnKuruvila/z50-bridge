package io.github.johnkuruvila.z50bridge.diag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager

/**
 * The phone's other radios, which share airtime or spectrum with the 2.4 GHz
 * camera link: Bluetooth and the cellular modem. Null means Android did not
 * say, never a guess.
 */
data class RadioState(
    val bluetoothOn: Boolean? = null,
    /** Bluetooth audio links Android routes to, e.g. "calls (HFP)"; empty when none. */
    val bluetoothAudio: List<String> = emptyList(),
    /** The "Bluetooth scanning" location setting, which lets apps scan with Bluetooth off. */
    val bluetoothScanning: Boolean? = null,
    val mobileDataOn: Boolean? = null,
    /** Registered cell, e.g. "LTE band 40"; null without location permission or service. */
    val cell: String? = null,
    val mediaPlaying: Boolean? = null,
)

fun describeRadios(state: RadioState?): String {
    if (state == null) return "unknown"
    val bluetooth = when (state.bluetoothOn) {
        true -> "Bluetooth on" +
            if (state.bluetoothAudio.isEmpty()) ", no audio link" else ", ${state.bluetoothAudio.joinToString(" + ")}"
        false -> "Bluetooth off"
        null -> "Bluetooth unknown"
    }
    val scanning = when (state.bluetoothScanning) {
        true -> "Bluetooth scanning setting on"
        false -> "Bluetooth scanning setting off"
        null -> "Bluetooth scanning setting unknown"
    }
    val data = when (state.mobileDataOn) {
        true -> "mobile data on"
        false -> "mobile data off"
        null -> "mobile data unknown"
    }
    val cell = "cell ${state.cell ?: "unknown"}"
    val media = when (state.mediaPlaying) {
        true -> "media playing"
        false -> "no media playing"
        null -> "media unknown"
    }
    return listOf(bluetooth, scanning, data, cell, media).joinToString(" · ")
}

/**
 * Reads [RadioState] without extra permissions: Bluetooth audio links come
 * from the audio routes, so device names are never read. The cell band needs
 * the location permission the app already asks for.
 */
class RadioReader(context: Context) {
    private val appContext = context.applicationContext
    private val bluetooth = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val telephony = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun read(): RadioState = RadioState(
        bluetoothOn = runCatching { bluetooth?.adapter?.isEnabled }.getOrNull(),
        bluetoothAudio = runCatching { bluetoothAudioLinks() }.getOrDefault(emptyList()),
        bluetoothScanning = runCatching {
            Settings.Global.getInt(appContext.contentResolver, BLE_SCAN_ALWAYS_AVAILABLE) != 0
        }.getOrNull(),
        mobileDataOn = runCatching { mobileDataEnabled() }.getOrNull(),
        cell = runCatching { registeredCell() }.getOrNull(),
        mediaPlaying = runCatching { audio?.isMusicActive }.getOrNull(),
    )

    // ACCESS_NETWORK_STATE (held) is enough since Android 8; lint only knows
    // the older READ_PHONE_STATE requirement. A SecurityException becomes unknown.
    @SuppressLint("MissingPermission")
    private fun mobileDataEnabled(): Boolean? = telephony?.isDataEnabled

    private fun bluetoothAudioLinks(): List<String> {
        val devices = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return emptyList()
        return devices.mapNotNull { bluetoothAudioKind(it.type) }.distinct().sorted()
    }

    private fun registeredCell(): String? {
        val granted = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        @Suppress("MissingPermission")
        val cells = telephony?.allCellInfo ?: return null
        return cells.firstOrNull { it.isRegistered }?.let(::describeCell)
    }

    private fun describeCell(info: CellInfo): String = when {
        info is CellInfoLte -> {
            val bands = if (Build.VERSION.SDK_INT >= 30) info.cellIdentity.bands.toList() else emptyList()
            "LTE " + bandText(bands, "EARFCN", info.cellIdentity.earfcn)
        }
        Build.VERSION.SDK_INT >= 29 && info is CellInfoNr -> {
            val identity = info.cellIdentity as? CellIdentityNr
            val bands = if (Build.VERSION.SDK_INT >= 30) identity?.bands?.toList().orEmpty() else emptyList()
            "5G NR " + bandText(bands, "NR-ARFCN", identity?.nrarfcn)
        }
        info is CellInfoWcdma -> "3G"
        info is CellInfoGsm -> "2G"
        else -> "other"
    }

    private fun bandText(bands: List<Int>, channelName: String, channel: Int?): String = when {
        bands.isNotEmpty() -> "band ${bands.joinToString("/")}"
        channel != null && channel != Int.MAX_VALUE -> "$channelName $channel"
        else -> "band unknown"
    }

    private companion object {
        /** Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE; readable by apps but not public API. */
        const val BLE_SCAN_ALWAYS_AVAILABLE = "ble_scan_always_enabled"
    }
}

/** Label for a Bluetooth audio route type, or null for wired, built-in and USB routes. */
fun bluetoothAudioKind(type: Int): String? = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "calls (HFP)"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "media (A2DP)"
    AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "LE Audio"
    else -> null
}

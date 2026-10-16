package io.github.johnkuruvila.z50bridge.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.johnkuruvila.z50bridge.net.decodeSsidAllowlist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("z50_bridge")

data class BridgeSettings(
    val cameraHost: String = "193.168.0.1",
    val cameraWifiAutoConnect: Boolean = false,
    val cameraSsid: String = "",
    val cameraWifiPassword: String = "",
    val syncCameraClock: Boolean = true,
    val pullRear: Boolean = false,
    val pullGps: Boolean = true,
    val skipOpenClip: Boolean = true,
    val prioritizeLatest: Boolean = true,
    /** Only sync clips from this many minutes before the newest one; 0 syncs everything. */
    val syncWindowMinutes: Int = 0,
    val pollSeconds: Int = 30,
    val autoWatch: Boolean = true,
    val destHost: String = "",
    val destPort: Int = 5008,
    val destToken: String = "",
    val parallelUploads: Int = 2,
    val parallelDownloads: Int = 1,
    val offloadWifiAllowlist: Set<String> = emptySet(),
    val themeMode: ThemeMode = ThemeMode.Brand,
) {
    fun needsSetup(): Boolean = destHost.trim().isEmpty() || destPort <= 0
}

class SettingsRepository(
    private val context: Context,
    private val tokenCipher: TokenCipher = TokenCipher(),
) {
    val flow: Flow<BridgeSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): BridgeSettings = flow.first()

    suspend fun update(transform: (BridgeSettings) -> BridgeSettings) {
        val next = transform(current())
        context.dataStore.edit { prefs ->
            prefs[Keys.cameraHost] = next.cameraHost
            prefs[Keys.cameraWifiAutoConnect] = next.cameraWifiAutoConnect
            prefs[Keys.cameraSsid] = next.cameraSsid
            prefs[Keys.cameraWifiPassword] = tokenCipher.encrypt(next.cameraWifiPassword)
            prefs[Keys.syncCameraClock] = next.syncCameraClock
            prefs[Keys.pullRear] = next.pullRear
            prefs[Keys.pullGps] = next.pullGps
            prefs[Keys.skipOpenClip] = next.skipOpenClip
            prefs[Keys.prioritizeLatest] = next.prioritizeLatest
            prefs[Keys.syncWindowMinutes] = next.syncWindowMinutes
            prefs[Keys.pollSeconds] = next.pollSeconds
            prefs[Keys.autoWatch] = next.autoWatch
            prefs[Keys.destHost] = next.destHost
            prefs[Keys.destPort] = next.destPort
            prefs[Keys.destToken] = tokenCipher.encrypt(next.destToken)
            prefs[Keys.parallelUploads] = next.parallelUploads
            prefs[Keys.parallelDownloads] = next.parallelDownloads
            prefs[Keys.offloadWifiAllowlist] = next.offloadWifiAllowlist
                .sorted()
                .joinToString("\n")
            prefs[Keys.themeMode] = next.themeMode.name
        }
    }

    private fun Preferences.toSettings(): BridgeSettings {
        val savedPort = this[Keys.destPort] ?: 5008
        // Port 22 was the retired SFTP path; treat it as the HTTP default.
        val port = if (savedPort == 22) 5008 else savedPort
        return BridgeSettings(
            cameraHost = this[Keys.cameraHost] ?: "193.168.0.1",
            cameraWifiAutoConnect = this[Keys.cameraWifiAutoConnect] ?: false,
            cameraSsid = this[Keys.cameraSsid] ?: "",
            cameraWifiPassword = tokenCipher.decrypt(this[Keys.cameraWifiPassword] ?: ""),
            syncCameraClock = this[Keys.syncCameraClock] ?: true,
            pullRear = this[Keys.pullRear] ?: false,
            pullGps = this[Keys.pullGps] ?: true,
            skipOpenClip = this[Keys.skipOpenClip] ?: true,
            prioritizeLatest = this[Keys.prioritizeLatest] ?: true,
            syncWindowMinutes = this[Keys.syncWindowMinutes] ?: 0,
            pollSeconds = this[Keys.pollSeconds] ?: 30,
            autoWatch = this[Keys.autoWatch] ?: true,
            destHost = this[Keys.destHost] ?: "",
            destPort = port,
            destToken = tokenCipher.decrypt(this[Keys.destToken] ?: ""),
            parallelUploads = this[Keys.parallelUploads] ?: 2,
            parallelDownloads = this[Keys.parallelDownloads] ?: 1,
            offloadWifiAllowlist = decodeSsidAllowlist(this[Keys.offloadWifiAllowlist] ?: ""),
            themeMode = this[Keys.themeMode]?.let { name ->
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: ThemeMode.Brand,
        )
    }

    private object Keys {
        val cameraHost = stringPreferencesKey("cameraHost")
        val cameraWifiAutoConnect = booleanPreferencesKey("cameraWifiAutoConnect")
        val cameraSsid = stringPreferencesKey("cameraSsid")
        val cameraWifiPassword = stringPreferencesKey("cameraWifiPassword")
        val syncCameraClock = booleanPreferencesKey("syncCameraClock")
        val pullRear = booleanPreferencesKey("pullRear")
        val pullGps = booleanPreferencesKey("pullGps")
        val skipOpenClip = booleanPreferencesKey("skipOpenClip")
        val prioritizeLatest = booleanPreferencesKey("prioritizeLatest")
        val syncWindowMinutes = intPreferencesKey("syncWindowMinutes")
        val pollSeconds = intPreferencesKey("pollSeconds")
        val autoWatch = booleanPreferencesKey("autoWatch")
        val destHost = stringPreferencesKey("destHost")
        val destPort = intPreferencesKey("destPort")
        val destToken = stringPreferencesKey("destToken")
        val parallelUploads = intPreferencesKey("parallelUploads")
        val parallelDownloads = intPreferencesKey("parallelDownloads")
        val offloadWifiAllowlist = stringPreferencesKey("offloadWifiAllowlist")
        val themeMode = stringPreferencesKey("themeMode")
    }
}

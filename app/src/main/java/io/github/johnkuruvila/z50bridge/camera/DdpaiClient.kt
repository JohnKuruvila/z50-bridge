package io.github.johnkuruvila.z50bridge.camera

import io.github.johnkuruvila.z50bridge.diag.DownloadTiming
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit

open class DdpaiException(message: String, val errcode: Int? = null) : RuntimeException(message)

/** The request never got an answer: the connection failed, dropped or timed out. */
class CameraUnreachableException(message: String) : DdpaiException(message)

/**
 * The camera answered with a well-formed rejection or a response that cannot
 * be this file. Unlike network failures, keeping the partial file is pointless.
 */
private class DownloadRejectedException(message: String) : DdpaiException(message)

class DdpaiClient(
    val baseUrl: String,
    private val http: OkHttpClient,
    private val user: String = "admin",
    private val password: String = "admin",
) {
    var sessionToken: String? = null

    fun probe(): Boolean {
        return try {
            cmd("API_GetBaseInfo")
            true
        } catch (_: Exception) {
            try {
                val req = requestBuilder("/").get().build()
                http.newCall(req).execute().use { it.isSuccessful || it.code in 400..499 }
            } catch (_: Exception) {
                false
            }
        }
    }

    fun authenticate() {
        val res = cmd("API_RequestSessionID")
        val data = asMap(res["data"])
        val token = (data["acSessionId"] ?: res["acSessionId"])?.toString()
        if (!token.isNullOrBlank()) sessionToken = token
        okData(cmd(
            "API_RequestCertificate",
            JSONObject()
                .put("user", user)
                .put("password", password)
                .put("level", 0)
                .put("uid", "z50-bridge"),
        ))
    }

    fun info(): Map<String, Any?> {
        return asMap(okData(cmd("API_GetBaseInfo")))
    }

    fun listPlayback(rear: Boolean = false): List<MediaFile> {
        val cmdName = if (rear) "APP_PlaybackListReq_RearCam" else "APP_PlaybackListReq"
        val kind = if (rear) "rear" else "front"
        return asFileList(okData(cmd(cmdName))).map { MediaFile.fromPlayback(it, kind) }
    }

    fun listGps(): List<MediaFile> {
        return asFileList(okData(cmd("API_GpsFileListReq"))).map { item ->
            val name = (item["name"] ?: "").toString()
            MediaFile.fromSidecar(item, if (name.endsWith(".git")) "gps-git" else "gps-nmea")
        }
    }

    fun listGsn(): List<MediaFile> {
        return asFileList(okData(cmd("API_GsnFileListReq"))).map { item ->
            MediaFile.fromSidecar(item, "gsn")
        }
    }

    fun stateReport(timeoutMs: Long? = null): Map<String, Any?> {
        val data = asMap(okData(cmd("API_StateReport", timeoutMs = timeoutMs)))
        return asMap(data["API_StateReport"]).ifEmpty { data }
    }

    fun storageInfo(): Map<String, Any?> = asMap(okData(cmd("API_GetStorageInfo")))

    fun cameraTime(): Map<String, Any?> = asMap(okData(cmd("API_EquipGetTime")))

    fun temperature(): Map<String, Any?> = asMap(okData(cmd("API_EquipGetTempetureAndHumidity")))

    /** SoC temperature (API_EquipGetTemp), e.g. `{"temp": "82"}`; the firmware does not state units. */
    fun chipTemperature(timeoutMs: Long? = null): Map<String, Any?> =
        asMap(okData(cmd("API_EquipGetTemp", timeoutMs = timeoutMs)))

    /**
     * Starts or stops loop recording (API_RecordOpt). The body comes from the
     * firmware handler; the official app's bare "0"/"1" is rejected by the Z50.
     * The state report's `rec` follows within about 3 s.
     */
    fun setRecording(on: Boolean) {
        okData(cmd("API_RecordOpt", JSONObject().put("record_status", if (on) "on" else "off")))
    }

    /**
     * Sets the camera clock to local wall-clock time ([stamp], yyyyMMddHHmmss).
     * The firmware rejects the call (0x7700003a) unless both `date` and a
     * string `imei` are present. `time_zone` (seconds east of UTC) goes to the
     * clock setter, and a missing `lang` makes the firmware store its `zh_CN`
     * default. `format` is left out so the camera's date display is unchanged.
     */
    fun syncDate(stamp: String, utcOffsetSeconds: Int, lang: String) {
        okData(cmd(
            "API_SyncDate",
            JSONObject()
                .put("date", stamp)
                .put("imei", "")
                .put("time_zone", utcOffsetSeconds)
                .put("lang", lang),
        ))
    }

    fun querySettings(keys: Collection<String>): Any? {
        val arr = JSONArray()
        keys.forEach { arr.put(JSONObject().put("key", it)) }
        return okData(cmd("API_GeneralQuery", JSONObject().put("keys", arr)))
    }

    fun saveSetting(key: String, value: Any) {
        val entry = JSONArray().put(JSONObject().put("key", key).put("value", value))
        val body = JSONObject()
            .put("int_params", if (value is Number) entry else JSONArray())
            .put("string_params", if (value is Number) JSONArray() else entry)
        okData(cmd("API_GeneralSave", body))
    }

    /** Switches the encoder between live preview and playback (APP_PlaybackLiveSwitch). */
    fun setLiveMode(live: Boolean) {
        okData(cmd("APP_PlaybackLiveSwitch", JSONObject().put("switch", if (live) "live" else "playback").put("playtime", "")))
    }

    fun setAppLive(on: Boolean) {
        okData(cmd("API_SetAppLiveState", JSONObject().put("switch", if (on) "on" else "off")))
    }

    fun setSuperDownload(enabled: Boolean) {
        okData(cmd("API_SuperDownload", JSONObject().put("switch", if (enabled) "on" else "off")))
    }

    /**
     * Switches the AP Wi-Fi channel (1, 6, 11) via API_SetWifiChannel; the
     * firmware handler reads a numeric `WifiChannel`, not `channel`. The AP
     * restarts, so a reply may never come; the short timeout keeps that from
     * hanging for the full read timeout.
     */
    fun setWifiChannel(channel: Int) {
        okData(cmd("API_SetWifiChannel", JSONObject().put("WifiChannel", channel), timeoutMs = 10_000L))
    }

    fun download(
        name: String,
        dest: File,
        expectedBytes: Long? = null,
        timing: DownloadTiming? = null,
        onProgress: (copied: Long, total: Long?) -> Unit = { _, _ -> },
    ): File {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        var copied = if (tmp.exists()) tmp.length() else 0L
        timing?.begin()
        timing?.resumedFrom = copied
        val reqBuilder = requestBuilder("/" + URLEncoder.encode(File(name).name, "UTF-8").replace("+", "%20"))
            .get()
        if (copied > 0) reqBuilder.header("Range", "bytes=$copied-")
        try {
            http.newCall(reqBuilder.build()).execute().use { resp ->
                timing?.headers()
                if (!resp.isSuccessful && resp.code != 206) {
                    throw DdpaiException("download HTTP ${resp.code} for $name")
                }
                if (copied > 0 && !responseStartsAt(resp, copied)) {
                    throw DdpaiException(
                        "download for $name served an unexpected range: ${resp.header("Content-Range") ?: resp.code}; keeping partial file",
                    )
                }
                val append = resp.code == 206 && copied > 0
                if (resp.code == 200 && copied > 0) copied = 0L
                val responseTotal = contentTotal(resp, null, copied)
                val total = responseTotal ?: expectedBytes
                val body = resp.body ?: throw DdpaiException("download returned no body for $name")
                var isFirstChunk = true
                body.byteStream().use { input ->
                    java.io.FileOutputStream(tmp, append).use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val readStart = System.nanoTime()
                            val n = input.read(buf)
                            if (n <= 0) break
                            timing?.read(System.nanoTime() - readStart, n)
                            if (isFirstChunk && !append) {
                                isFirstChunk = false
                                val preview = String(buf, 0, minOf(n, 256), Charsets.UTF_8).trimStart()
                                if (preview.startsWith("{") && preview.contains("errcode")) {
                                    val errSnippet = preview.lines().firstOrNull() ?: preview
                                    throw DownloadRejectedException("Camera rejected file $name: $errSnippet")
                                }
                            }
                            val writeStart = System.nanoTime()
                            output.write(buf, 0, n)
                            timing?.wrote(System.nanoTime() - writeStart)
                            copied += n
                            onProgress(copied, total)
                        }
                    }
                }
                if (responseTotal != null && copied != responseTotal) {
                    throw DdpaiException("incomplete download for $name: got $copied bytes, expected $responseTotal")
                }
                if (expectedBytes != null && expectedBytes > 64 * 1024) {
                    if (copied < expectedBytes * 0.8) {
                        throw DdpaiException("incomplete download for $name: got $copied bytes, expected ~$expectedBytes bytes")
                    }
                }
                if (copied < 1024 && (name.endsWith(".mp4", ignoreCase = true) || name.endsWith(".mov", ignoreCase = true))) {
                    throw DdpaiException("download for $name too small (${copied}B) for a video file")
                }
            }
            try {
                Files.move(tmp.toPath(), dest.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), REPLACE_EXISTING)
            }
            return dest
        } catch (exc: DownloadRejectedException) {
            if (tmp.exists()) tmp.delete()
            throw exc
        } finally {
            timing?.end()
        }
    }

    /**
     * Reads [name] from the start for up to [durationMs] and discards the
     * bytes, so the rate reflects the camera and Wi-Fi without phone storage.
     */
    fun measureRead(name: String, durationMs: Long, timing: DownloadTiming, keepReading: () -> Boolean = { true }) {
        val req = requestBuilder("/" + URLEncoder.encode(File(name).name, "UTF-8").replace("+", "%20")).get().build()
        timing.begin()
        try {
            http.newCall(req).execute().use { resp ->
                timing.headers()
                if (!resp.isSuccessful) throw DdpaiException("speed test HTTP ${resp.code} for $name")
                val deadline = timing.startedAtNs + durationMs * 1_000_000
                resp.body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (System.nanoTime() < deadline && keepReading()) {
                        val readStart = System.nanoTime()
                        val n = input.read(buf)
                        if (n <= 0) break
                        timing.read(System.nanoTime() - readStart, n)
                    }
                }
            }
        } catch (exc: IOException) {
            throw CameraUnreachableException("speed test for $name failed: ${exc.message}")
        } finally {
            timing.end()
        }
    }

    /** True when a ranged response begins at [offset]; a 200 (full body) is always accepted. */
    private fun responseStartsAt(resp: Response, offset: Long): Boolean {
        if (resp.code != 206) return true
        val cr = resp.header("Content-Range") ?: return true
        val start = cr.substringAfter("bytes ", "").substringBefore("-").trim().toLongOrNull() ?: return true
        return start == offset
    }

    private fun contentTotal(resp: Response, expected: Long?, copiedBefore: Long): Long? {
        val cr = resp.header("Content-Range")
        if (cr != null && "/" in cr) return cr.substringAfterLast("/").toLongOrNull()
        val length = resp.header("Content-Length")?.toLongOrNull()
        return when {
            length != null && resp.code == 206 -> copiedBefore + length
            length != null -> length
            else -> expected
        }
    }

    private fun cmd(name: String, json: JSONObject? = null, timeoutMs: Long? = null): Map<String, Any?> {
        val url = "$baseUrl/vcam/cmd.cgi?cmd=" + URLEncoder.encode(name, "UTF-8")
        val body = if (json == null) {
            ByteArray(0).toRequestBody(null)
        } else {
            json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        }
        val req = requestBuilder(url.removePrefix(baseUrl)).url(url).post(body).build()
        val caller = if (timeoutMs == null) http else http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        return try {
            caller.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw DdpaiException("$name HTTP ${resp.code}")
                val parsed = unwrap(parseJson(text) ?: mapOf("raw" to text))
                asMap(parsed)
            }
        } catch (exc: DdpaiException) {
            throw exc
        } catch (exc: IOException) {
            throw CameraUnreachableException("$name failed: ${exc.message}")
        }
    }

    private fun okData(res: Map<String, Any?>): Any? {
        val err = res["errcode"]
        val code = when (err) {
            null -> 0
            is Number -> err.toInt()
            else -> err.toString().toIntOrNull()
        }
        if (code != null && code != 0) throw DdpaiException("CGI error $code", code)
        return res["data"]
    }

    private fun requestBuilder(path: String): Request.Builder {
        val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
        sessionToken?.let {
            b.header("sessionid", it)
            b.header("Cookie", "acSessionId=$it; SessionID=$it")
        }
        return b
    }

    companion object {
        const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 14; Pixel Build/AP2A.240905.003)"
    }
}

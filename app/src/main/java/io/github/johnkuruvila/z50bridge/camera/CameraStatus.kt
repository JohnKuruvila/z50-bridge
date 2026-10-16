package io.github.johnkuruvila.z50bridge.camera

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the camera reported the last time the app talked to it. Every field is
 * nullable: the Z50 omits or changes fields between firmware builds, and an
 * unknown value is shown as unknown rather than guessed.
 */
data class CameraStatus(
    val model: String? = null,
    val firmware: String? = null,
    val recording: Boolean? = null,
    val gpsFix: Boolean? = null,
    val sdTotalBytes: Long? = null,
    val sdFreeBytes: Long? = null,
    /** Camera clock minus phone clock, in seconds, when the camera time could be read. */
    val clockOffsetSeconds: Long? = null,
    val clockSyncedAtMs: Long? = null,
    /** API_EquipGetTemp `temp`, as sent; the firmware does not state units. */
    val socTemp: Long? = null,
    val fetchedAtMs: Long = 0,
) {
    val sdUsedFraction: Float?
        get() {
            val total = sdTotalBytes ?: return null
            val free = sdFreeBytes ?: return null
            if (total <= 0 || free !in 0..total) return null
            return (total - free).toFloat() / total
        }

    val clockInSync: Boolean?
        get() = clockOffsetSeconds?.let { abs(it) <= CLOCK_TOLERANCE_SECONDS }

    /** Chip reading at or above [HOT_SOC_TEMP]; null when the camera did not report one. */
    val runningHot: Boolean?
        get() = socTemp?.let { it >= HOT_SOC_TEMP }

    companion object {
        const val CLOCK_TOLERANCE_SECONDS = 2L

        /**
         * Warn this far below the firmware's lowest heat threshold. The Z50
         * reports its limits as normal=120/105/105/115/110 (in its debug log);
         * on 2026-10-01 the chip sat at 100-103 for about 45 minutes.
         */
        const val HOT_SOC_TEMP = 100L
        const val FIRMWARE_HEAT_THRESHOLD = 105L
    }
}

fun parseCameraStatus(
    info: Map<String, Any?>,
    state: Map<String, Any?>,
    storage: Map<String, Any?>,
    cameraTime: Map<String, Any?>,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    chipTemp: Map<String, Any?> = emptyMap(),
): CameraStatus = CameraStatus(
    model = text(info["model"]) ?: text(info["cammodel"]),
    firmware = text(info["version"]) ?: text(info["firmware"]),
    recording = flag(state["rec"]),
    gpsFix = flag(state["gps"]),
    sdTotalBytes = kbToBytes(intOrNull(storage["sdcapacity"])),
    sdFreeBytes = intOrNull(storage["sdspare"])?.takeIf { it >= 0 }?.times(1024),
    clockOffsetSeconds = parseCameraClock(cameraTime, zone)?.let { cameraMs ->
        ((cameraMs - nowMs) / 1000.0).roundToLong()
    },
    socTemp = intOrNull(chipTemp["temp"]),
    fetchedAtMs = nowMs,
)

/** Local wall-clock stamp the firmware's API_SyncDate expects. */
fun cameraClockStamp(time: LocalDateTime): String =
    time.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US))

/** Locale as `en_IN`, the shape the official app sends as API_SyncDate `lang`. */
fun cameraLangTag(locale: Locale): String =
    if (locale.language.isNotEmpty() && locale.country.isNotEmpty()) "${locale.language}_${locale.country}" else "en_US"

private val STAMP_14 = Regex("""\b(\d{14})\b""")
private val STAMP_DASHED = Regex("""(\d{4})[-/](\d{2})[-/](\d{2})[ T_](\d{2}):(\d{2}):(\d{2})""")

/**
 * Finds the camera's current time anywhere in the API_EquipGetTime payload.
 * Accepts `yyyyMMddHHmmss`, `yyyy-MM-dd HH:mm:ss` (or `/`), or Unix seconds.
 * Wall-clock strings are read in the phone's zone, which is what the camera
 * is set to by [cameraClockStamp].
 */
fun parseCameraClock(payload: Map<String, Any?>, zone: ZoneId = ZoneId.systemDefault()): Long? {
    payload.values.forEach { value ->
        when (value) {
            is Map<*, *> -> parseCameraClock(asMap(value), zone)?.let { return it }
            is Number -> epochSeconds(value.toLong())?.let { return it }
            is String -> {
                val trimmed = value.trim()
                trimmed.toLongOrNull()?.let { number ->
                    if (trimmed.length == 10) epochSeconds(number)?.let { return it }
                }
                STAMP_14.find(trimmed)?.let { match ->
                    wallClock(match.groupValues[1], "yyyyMMddHHmmss", zone)?.let { return it }
                }
                STAMP_DASHED.find(trimmed)?.let { match ->
                    val (y, mo, d, h, mi, s) = match.destructured
                    wallClock("$y$mo$d$h$mi$s", "yyyyMMddHHmmss", zone)?.let { return it }
                }
            }
        }
    }
    return null
}

private fun epochSeconds(value: Long): Long? =
    // 2001-09-09 .. 2286-11-20: anything else is a counter, not a clock.
    value.takeIf { it in 1_000_000_000L..9_999_999_999L }?.times(1000)

private fun wallClock(text: String, pattern: String, zone: ZoneId): Long? = try {
    LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.US))
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
} catch (_: Exception) {
    null
}

private fun text(value: Any?): String? = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }

/** Reads the firmware's mix of 0/1, "on"/"off" and "true"/"false"; anything else is unknown. */
internal fun flag(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is Number -> when (value.toInt()) {
        0 -> false
        1 -> true
        else -> null
    }
    is String -> when (value.trim().lowercase(Locale.US)) {
        "1", "on", "true", "yes" -> true
        "0", "off", "false", "no" -> false
        else -> null
    }
    else -> null
}

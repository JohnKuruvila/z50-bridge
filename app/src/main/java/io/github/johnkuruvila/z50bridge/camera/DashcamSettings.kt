package io.github.johnkuruvila.z50bridge.camera

import java.util.Locale

/** One value from API_GeneralQuery; [isInt] records which bucket it came from, so it is saved back the same way. */
data class DashcamValue(val key: String, val value: Any?, val isInt: Boolean) {
    val asToggle: Boolean?
        get() = when (value?.toString()?.lowercase(Locale.US)) {
            "on" -> true
            "off" -> false
            else -> null
        }

    val asLevel: Int?
        get() = if (isInt) intOrNull(value)?.toInt()?.takeIf { it in 0..100 } else null
}

enum class DashcamSection { Recording, Audio, Display, Events, Parking }

enum class DashcamControl {
    /** Firmware reports "on"/"off" strings. */
    Toggle,

    /** Integer 0..100 level (volume, sensitivity). */
    Level,

    /** Z50 recording codec values documented by DDPAI and observed on the camera. */
    Codec,

    /**
     * Recording resolution. `image_quality` high/middle/low picks the first,
     * second or third mode listed in `record_resolution`; see [resolutionOptions].
     */
    Resolution,

    /** Screen sleep timeout in seconds (0 = never, 10s, 60s). */
    Dormant,

    /** Screen brightness level (0 = high, 1 = mid, 2 = low, 3 = auto). */
    Brightness,

    /** Shown but not editable: the allowed values are not known. */
    ReadOnly,
}

data class DashcamSpec(
    val key: String,
    val section: DashcamSection,
    val control: DashcamControl,
)

/**
 * The camera settings the app presents, in display order. Only on/off switches,
 * 0..100 levels, codec, quality, and display controls are writable. Other
 * enumerated settings stay read-only until their allowed values are verified.
 */
val DASHCAM_SPECS: List<DashcamSpec> = listOf(
    DashcamSpec("record_resolution", DashcamSection.Recording, DashcamControl.ReadOnly),
    DashcamSpec("image_quality", DashcamSection.Recording, DashcamControl.Resolution),
    DashcamSpec("video_codec", DashcamSection.Recording, DashcamControl.Codec),
    DashcamSpec("wdr_enable", DashcamSection.Recording, DashcamControl.Toggle),
    DashcamSpec("exposure_biaslevel", DashcamSection.Recording, DashcamControl.ReadOnly),
    DashcamSpec("osd_switch", DashcamSection.Recording, DashcamControl.Toggle),
    DashcamSpec("osd_speedswitch", DashcamSection.Recording, DashcamControl.Toggle),
    DashcamSpec("scam_vertical_mirror", DashcamSection.Recording, DashcamControl.Toggle),
    DashcamSpec("scam_horizontal_mirror", DashcamSection.Recording, DashcamControl.Toggle),
    DashcamSpec("mic_switch", DashcamSection.Audio, DashcamControl.Toggle),
    DashcamSpec("speaker_turn", DashcamSection.Audio, DashcamControl.Level),
    DashcamSpec("start_sound_switch", DashcamSection.Audio, DashcamControl.Toggle),
    DashcamSpec("screen_dormant", DashcamSection.Display, DashcamControl.Dormant),
    DashcamSpec("screen_brightness_level", DashcamSection.Display, DashcamControl.Brightness),
    DashcamSpec("gsensor_mode_100level", DashcamSection.Events, DashcamControl.Level),
    DashcamSpec("event_before_time", DashcamSection.Events, DashcamControl.ReadOnly),
    DashcamSpec("event_after_time", DashcamSection.Events, DashcamControl.ReadOnly),
    DashcamSpec("parking_mode_switch", DashcamSection.Parking, DashcamControl.Toggle),
    DashcamSpec("parking_status", DashcamSection.Parking, DashcamControl.ReadOnly),
    DashcamSpec("parking_power_mgr", DashcamSection.Parking, DashcamControl.ReadOnly),
    DashcamSpec("gsensor_mode_parking_100level", DashcamSection.Parking, DashcamControl.Level),
)

/** One choice in the resolution picker: the `image_quality` value to save and the mode it selects, if the camera named it. */
data class ResolutionOption(val quality: String, val mode: String?)

private val QUALITY_LEVELS = listOf("high", "middle", "low")

/**
 * Pairs `image_quality` levels with the modes in `record_resolution`
 * (`2160P_1600P_1080P` on the Z50): the official app (v8.2.5) saves "high",
 * "middle" or "low" for the first, second or third mode in that list. This
 * mapping comes from the app's code and has not been confirmed on the Z50.
 * Without a mode list all three levels are offered unnamed.
 */
fun resolutionOptions(recordResolution: DashcamValue?): List<ResolutionOption> {
    val modes = recordResolution?.value?.toString().orEmpty()
        .split('_').map { it.trim() }.filter { it.isNotEmpty() }
    if (modes.isEmpty()) return QUALITY_LEVELS.map { ResolutionOption(it, null) }
    return modes.take(QUALITY_LEVELS.size).mapIndexed { index, mode -> ResolutionOption(QUALITY_LEVELS[index], mode) }
}

/** Flattens API_GeneralQuery's `int_params` / `string_params` arrays. */
fun parseDashcamSettings(data: Any?): Map<String, DashcamValue> {
    val map = asMap(data)
    val out = linkedMapOf<String, DashcamValue>()
    listOf("int_params" to true, "string_params" to false).forEach { (bucket, isInt) ->
        val items = when (val raw = map[bucket]) {
            is List<*> -> raw
            is Map<*, *> -> listOf(raw)
            else -> emptyList()
        }
        items.forEach { item ->
            val entry = asMap(item)
            val key = entry["key"]?.toString() ?: return@forEach
            out[key] = DashcamValue(key, entry["value"], isInt)
        }
    }
    return out
}

/**
 * Presentation of a value whose meaning is known well enough to relabel;
 * everything else is shown exactly as the camera sent it.
 */
fun describeDashcamValue(value: DashcamValue): String {
    val raw = value.value?.toString()?.trim().orEmpty()
    if (raw.isEmpty()) return "--"
    return when (value.key) {
        "record_resolution" -> raw.split('_').joinToString(" · ")
        "video_codec" -> when (raw.lowercase(Locale.US)) {
            "h264" -> "H.264"
            "h265" -> "H.265"
            else -> raw
        }
        "image_quality" -> when (raw.lowercase(Locale.US)) {
            "high" -> "High"
            "middle" -> "Middle"
            "low" -> "Low"
            else -> raw
        }
        "screen_dormant" -> when (raw) {
            "0" -> "Always on"
            "10" -> "10 s"
            "60" -> "1 min"
            else -> "$raw s"
        }
        "screen_brightness_level" -> when (raw) {
            "0" -> "High"
            "1" -> "Medium"
            "2" -> "Low"
            "3" -> "Auto"
            else -> raw
        }
        "exposure_biaslevel" -> when (raw) {
            "0" -> "0 EV"
            else -> if (raw.startsWith("-") || raw.startsWith("+")) "$raw EV" else "+$raw EV"
        }
        "parking_status" -> when (raw.lowercase(Locale.US)) {
            "timelapse" -> "Timelapse"
            "aovtimelapse" -> "AOV Timelapse"
            "normal" -> "Normal"
            "shut" -> "Off"
            else -> raw.replaceFirstChar { it.titlecase(Locale.US) }
        }
        "event_before_time", "event_after_time" -> intOrNull(raw)?.let { "$it s" } ?: raw
        else -> when (raw.lowercase(Locale.US)) {
            "on" -> "On"
            "off" -> "Off"
            else -> raw.replaceFirstChar { it.titlecase(Locale.US) }
        }
    }
}

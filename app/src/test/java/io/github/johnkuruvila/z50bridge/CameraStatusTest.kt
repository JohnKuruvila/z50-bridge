package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.camera.DashcamValue
import io.github.johnkuruvila.z50bridge.camera.ResolutionOption
import io.github.johnkuruvila.z50bridge.camera.resolutionOptions
import io.github.johnkuruvila.z50bridge.camera.cameraClockStamp
import io.github.johnkuruvila.z50bridge.camera.cameraLangTag
import io.github.johnkuruvila.z50bridge.camera.describeDashcamValue
import io.github.johnkuruvila.z50bridge.camera.parseCameraClock
import io.github.johnkuruvila.z50bridge.camera.parseCameraStatus
import io.github.johnkuruvila.z50bridge.camera.parseDashcamSettings
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStatusTest {
    private val utc = ZoneOffset.UTC
    private val noon = LocalDateTime.of(2026, 9, 23, 12, 0, 0).toInstant(utc).toEpochMilli()

    @Test
    fun readsCompactAndDashedClockStamps() {
        assertEquals(noon, parseCameraClock(mapOf("time" to "20260923120000"), utc))
        assertEquals(noon, parseCameraClock(mapOf("date" to "2026-09-23 12:00:00"), utc))
        assertEquals(noon, parseCameraClock(mapOf("nested" to mapOf("t" to "2026/09/23 12:00:00")), utc))
        assertEquals(noon, parseCameraClock(mapOf("unix" to noon / 1000), utc))
    }

    @Test
    fun unknownClockStaysUnknown() {
        assertNull(parseCameraClock(mapOf("time" to "soon", "count" to 42), utc))
    }

    @Test
    fun statusReportsOffsetStorageAndFlags() {
        val status = parseCameraStatus(
            info = mapOf("model" to "Z50", "version" to "v7.2.0.24"),
            state = mapOf("rec" to 1, "gps" to "off"),
            storage = mapOf("sdcapacity" to 1000, "sdspare" to 250),
            cameraTime = mapOf("time" to "20260923120012"),
            nowMs = noon,
            zone = utc,
        )
        assertEquals("Z50", status.model)
        assertEquals(true, status.recording)
        assertEquals(false, status.gpsFix)
        assertEquals(1000L * 1024, status.sdTotalBytes)
        assertEquals(250L * 1024, status.sdFreeBytes)
        assertEquals(0.75f, status.sdUsedFraction!!, 0.001f)
        assertEquals(12L, status.clockOffsetSeconds)
        assertEquals(false, status.clockInSync)
    }

    @Test
    fun unrecognisedStateValuesAreUnknownNotGuessed() {
        val status = parseCameraStatus(emptyMap(), mapOf("rec" to 7, "gps" to "3d"), emptyMap(), emptyMap(), noon, utc)
        assertNull(status.recording)
        assertNull(status.gpsFix)
        assertNull(status.sdUsedFraction)
        assertNull(status.clockOffsetSeconds)
        assertNull(status.socTemp)
        assertNull(status.runningHot)
    }

    @Test
    fun chipTemperatureFlagsHeatFromOneHundred() {
        fun status(temp: Any?) = parseCameraStatus(
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), noon, utc, chipTemp = mapOf("temp" to temp),
        )
        assertEquals(99L, status("99").socTemp)
        assertEquals(false, status("99").runningHot)
        assertEquals(true, status("100").runningHot)
        assertEquals(true, status(103).runningHot)
        assertNull(status("hot").socTemp)
    }

    @Test
    fun clockStampMatchesFirmwareFormat() {
        assertEquals("20260923070509", cameraClockStamp(LocalDateTime.of(2026, 9, 23, 7, 5, 9)))
    }

    @Test
    fun langTagMatchesOfficialAppShape() {
        assertEquals("en_IN", cameraLangTag(Locale.forLanguageTag("en-IN")))
        assertEquals("en_US", cameraLangTag(Locale.ENGLISH))
    }

    @Test
    fun settingsKeepTheirBucket() {
        val parsed = parseDashcamSettings(
            mapOf(
                "int_params" to listOf(mapOf("key" to "speaker_turn", "value" to 33)),
                "string_params" to listOf(
                    mapOf("key" to "wdr_enable", "value" to "on"),
                    mapOf("key" to "mic_switch", "value" to "off"),
                ),
            ),
        )
        assertEquals(DashcamValue("speaker_turn", 33, isInt = true), parsed["speaker_turn"])
        assertEquals(33, parsed.getValue("speaker_turn").asLevel)
        assertEquals(true, parsed.getValue("wdr_enable").asToggle)
        assertEquals(false, parsed.getValue("mic_switch").asToggle)
        assertNull(parsed.getValue("wdr_enable").asLevel)
    }

    @Test
    fun parsesASingleSettingObjectOutsideAList() {
        // Some firmware sends a lone setting as an object rather than a one-element list.
        val parsed = parseDashcamSettings(
            mapOf("string_params" to mapOf("key" to "wdr_enable", "value" to "on")),
        )
        assertEquals(DashcamValue("wdr_enable", "on", isInt = false), parsed["wdr_enable"])
    }

    @Test
    fun levelsOutsideZeroToHundredAreNotEditable() {
        assertNull(DashcamValue("speaker_turn", 250, isInt = true).asLevel)
        assertNull(DashcamValue("wdr_enable", "auto", isInt = false).asToggle)
    }

    @Test
    fun describesKnownValuesAndPassesTheRestThrough() {
        assertEquals("2160P · 1600P · 1080P", describeDashcamValue(DashcamValue("record_resolution", "2160P_1600P_1080P", false)))
        assertEquals("H.264", describeDashcamValue(DashcamValue("video_codec", "h264", false)))
        assertEquals("High", describeDashcamValue(DashcamValue("image_quality", "high", false)))
        assertEquals("Always on", describeDashcamValue(DashcamValue("screen_dormant", 0, true)))
        assertEquals("10 s", describeDashcamValue(DashcamValue("screen_dormant", 10, true)))
        assertEquals("1 min", describeDashcamValue(DashcamValue("screen_dormant", 60, true)))
        assertEquals("High", describeDashcamValue(DashcamValue("screen_brightness_level", 0, true)))
        assertEquals("Auto", describeDashcamValue(DashcamValue("screen_brightness_level", 3, true)))
        assertEquals("1", describeDashcamValue(DashcamValue("parking_power_mgr", 1, true)))
        assertEquals("+1 EV", describeDashcamValue(DashcamValue("exposure_biaslevel", 1, true)))
        assertEquals("5 s", describeDashcamValue(DashcamValue("event_before_time", 5, true)))
        assertEquals("Timelapse", describeDashcamValue(DashcamValue("parking_status", "timelapse", false)))
        assertTrue(describeDashcamValue(DashcamValue("x", null, false)) == "--")
    }

    @Test
    fun resolutionLevelsFollowTheCameraModeList() {
        assertEquals(
            listOf(ResolutionOption("high", "2160P"), ResolutionOption("middle", "1600P"), ResolutionOption("low", "1080P")),
            resolutionOptions(DashcamValue("record_resolution", "2160P_1600P_1080P", false)),
        )
        assertEquals(
            listOf(ResolutionOption("high", "1600P"), ResolutionOption("middle", "1080P")),
            resolutionOptions(DashcamValue("record_resolution", "1600P_1080P", false)),
        )
        // No mode list: the levels are offered without inventing resolutions.
        assertEquals(
            listOf(ResolutionOption("high", null), ResolutionOption("middle", null), ResolutionOption("low", null)),
            resolutionOptions(null),
        )
    }
}

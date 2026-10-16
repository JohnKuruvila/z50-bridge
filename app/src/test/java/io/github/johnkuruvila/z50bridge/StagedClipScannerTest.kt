package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.sync.StagedClipScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StagedClipScannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun scanParsesStampDurationAndSidecars() {
        val dir = temp.newFolder("staging")
        java.io.File(dir, "20260915213739_0060.mp4").writeBytes(ByteArray(16))
        java.io.File(dir, "20260915213739_0060.gpx").writeText("x")
        java.io.File(dir, "20260915213739_0060_T.jpg").writeBytes(ByteArray(4))

        val clips = StagedClipScanner.scan(dir)

        assertEquals(1, clips.size)
        val clip = clips.first()
        assertEquals("21:37", clip.timeFormatted)
        assertEquals("1:00", clip.durationFormatted)
        assertTrue(clip.hasGps)
        assertNotNull(clip.thumbFile)
    }

    @Test
    fun driveLongSidecarsCoverEveryClipInsideTheirSpan() {
        // Real names from the inbox: one 27-minute GPS bundle and one
        // 55-minute G-sensor log cover many one-minute clips.
        val dir = temp.newFolder("staging")
        java.io.File(dir, "20260915121936_1679.git").writeText("x")
        java.io.File(dir, "20260915121942_3315.gsn").writeText("x")
        java.io.File(dir, "20260915123000_0060.mp4").writeBytes(ByteArray(16))
        java.io.File(dir, "20260915130000_0060.mp4").writeBytes(ByteArray(16))
        java.io.File(dir, "20260915140000_0060.mp4").writeBytes(ByteArray(16))

        val byTime = StagedClipScanner.scan(dir).associateBy { it.filename }

        byTime.getValue("20260915123000_0060.mp4").let { assertTrue(it.hasGps && it.hasGsn) }
        // Past the end of the .git (12:47:55) but inside the .gsn (13:14:57).
        byTime.getValue("20260915130000_0060.mp4").let { assertTrue(!it.hasGps && it.hasGsn) }
        // After both logs ended: nothing is claimed.
        byTime.getValue("20260915140000_0060.mp4").let { assertTrue(!it.hasGps && !it.hasGsn) }
    }

    @Test
    fun missingThumbnailIsNull() {
        val dir = temp.newFolder("staging")
        java.io.File(dir, "20260915213739_0060.mp4").writeBytes(ByteArray(16))

        val clip = StagedClipScanner.scan(dir).single()

        assertNull(clip.thumbFile)
    }

    @Test
    fun nonTimestampedFilesFallBackToModifiedTime() {
        val dir = temp.newFolder("staging")
        java.io.File(dir, "mystery.mp4").writeBytes(ByteArray(16))

        val clip = StagedClipScanner.scan(dir).single()

        assertEquals(StagedClipScanner.UNKNOWN_DURATION, clip.durationFormatted)
    }
}

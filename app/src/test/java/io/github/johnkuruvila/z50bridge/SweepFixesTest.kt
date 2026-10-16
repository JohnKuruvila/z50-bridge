package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.diag.StoredReport
import io.github.johnkuruvila.z50bridge.diag.TransferReportStore
import io.github.johnkuruvila.z50bridge.diag.retainReports
import io.github.johnkuruvila.z50bridge.net.NetworkHint
import io.github.johnkuruvila.z50bridge.net.NetworkUse
import io.github.johnkuruvila.z50bridge.net.networkScore
import io.github.johnkuruvila.z50bridge.sync.orphanThumbnails
import io.github.johnkuruvila.z50bridge.sync.thumbnailFor
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepFixesTest {
    @Test
    fun thumbnailBelongsToItsClip() {
        assertEquals("20260915213739_0060_T.jpg", thumbnailFor(File("20260915213739_0060.mp4")))
        assertNull(thumbnailFor(File("20260915121942_3315.gsn")))
    }

    @Test
    fun onlyThumbnailsWithoutAStagedClipAreOrphans() {
        val dir = Files.createTempDirectory("staging").toFile()
        listOf(
            "a.mp4", "a_T.jpg", // clip still staged
            "b_T.jpg", // clip offloaded
            "c.mp4.part", "c_T.jpg", // clip half downloaded
            "d_T.jpg.part", // half-downloaded thumbnail of an offloaded clip
            "e.gsn",
        ).forEach { File(dir, it).writeText("x") }

        assertEquals(listOf("b_T.jpg", "d_T.jpg.part"), orphanThumbnails(dir).map { it.name }.sorted())
        dir.deleteRecursively()
    }

    @Test
    fun notableReportsOutliveRoutineOnes() {
        val reports = listOf(
            StoredReport("r1", false),
            StoredReport("n1", true),
            StoredReport("r2", false),
            StoredReport("r3", false),
            StoredReport("n2", true),
            StoredReport("n3", true),
        )
        assertEquals(
            listOf("r1", "n1", "r2", "n2"),
            retainReports(reports, maxRoutine = 2, maxNotable = 2).map { it.text },
        )
    }

    @Test
    fun reportStoreReadsTheOldPlainStringFormat() {
        val file = File(Files.createTempDirectory("reports").toFile(), "transfer-reports.json")
        file.writeText(JSONArray().put("old one").put("older one").toString())
        val store = TransferReportStore(file, maxRoutine = 2, maxNotable = 1)

        store.add("failed sync", notable = true)

        assertEquals(listOf("failed sync", "old one", "older one"), store.load())
        store.add("routine sync")
        assertEquals(listOf("routine sync", "failed sync", "old one"), store.load())
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun cameraIsNeverLookedForOnAValidatedNetwork() {
        assertEquals(-1, networkScore(NetworkUse.Camera, NetworkHint(wifi = true, internet = true, validated = true)))
        assertTrue(networkScore(NetworkUse.Camera, NetworkHint(wifi = true, internet = true)) > 0)
        // Offload still prefers home Wi-Fi.
        assertTrue(networkScore(NetworkUse.Offload, NetworkHint(wifi = true, internet = true, validated = true)) > 0)
    }
}

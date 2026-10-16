package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.data.HistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun removeAllForgetsExactAndBasenameEntries() {
        val store = HistoryStore(File(temp.root, "synced_history.json"))
        store.add("20260915210000_0001.mp4")
        store.add("20260915210000_0001.gpx")
        store.add("20260915210000_0001.gsn")
        store.add("20260915210100_0002.mp4")

        store.removeAll(listOf("20260915210000_0001.mp4", "20260915210000_0001.gpx", "20260915210000_0001.gsn"))

        assertEquals(setOf("20260915210100_0002.mp4"), store.names())
    }

    @Test
    fun removeAllMatchesCameraNamesWithPaths() {
        val store = HistoryStore(File(temp.root, "synced_history.json"))
        store.add("/DCIM/20260915210000_0001.mp4")

        store.removeAll(listOf("20260915210000_0001.mp4"))

        assertTrue(store.names().isEmpty())
    }

    @Test
    fun corruptLedgerIsPreservedAndDoesNotCrash() {
        val file = File(temp.root, "synced_history.json").apply { writeText("{not json") }
        val store = HistoryStore(file)

        assertTrue(store.names().isEmpty())
        assertFalse(file.exists())
        assertTrue(File(file.path + ".corrupt").exists())
    }
}

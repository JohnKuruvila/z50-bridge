package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.data.ActivityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ActivityStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun appendAndLoadRoundTripsItems() {
        val store = ActivityStore(File(temp.root, "activity.json"))
        store.append(ActivityItem(1000, "Synced 2 files", false, ActivityKind.Sync))
        store.append(ActivityItem(2000, "Camera unreachable", true, ActivityKind.Error))

        val items = store.load()

        assertEquals(2, items.size)
        assertEquals("Camera unreachable", items[0].text)
        assertEquals(ActivityKind.Error, items[0].kind)
        assertTrue(items[0].error)
        assertEquals(ActivityKind.Sync, items[1].kind)
    }

    @Test
    fun storeCapsRetainedItems() {
        val store = ActivityStore(File(temp.root, "activity.json"), maxItems = 5)
        repeat(8) { index ->
            store.append(ActivityItem(index.toLong(), "event $index", false, ActivityKind.System))
        }

        val items = store.load()
        assertEquals(5, items.size)
        assertEquals("event 7", items.first().text)
    }

    @Test
    fun clearRemovesTheFile() {
        val file = File(temp.root, "activity.json")
        val store = ActivityStore(file)
        store.append(ActivityItem(1, "hello", false, ActivityKind.System))

        store.clear()

        assertTrue(!file.exists())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun corruptFileLoadsAsEmpty() {
        val file = File(temp.root, "activity.json").apply { writeText("{not json") }
        val store = ActivityStore(file)

        assertTrue(store.load().isEmpty())
    }
}

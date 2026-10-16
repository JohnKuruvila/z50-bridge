package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.camera.MediaFile
import io.github.johnkuruvila.z50bridge.data.TransferStamps
import io.github.johnkuruvila.z50bridge.data.formatTransferStamp
import io.github.johnkuruvila.z50bridge.sync.SyncQueue
import io.github.johnkuruvila.z50bridge.sync.partialNames
import io.github.johnkuruvila.z50bridge.sync.runWorkerPool
import io.github.johnkuruvila.z50bridge.sync.stalePartials
import io.github.johnkuruvila.z50bridge.sync.withinWindow
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferPlanningTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun clip(stamp: String, start: Long, kind: String = "front") =
        MediaFile(name = "${stamp}_0060.mp4", kind = kind, sizeKb = 1, startUnix = start)

    private fun gps(stamp: String, start: Long) =
        MediaFile(name = "${stamp}_0198.gpx", kind = "gps-nmea", sizeKb = 1, startUnix = start)

    private fun drain(queue: SyncQueue): List<String> =
        generateSequence { queue.next()?.first?.name }.toList()

    @Test
    fun queueTakesGpsThenPartialsThenNewestClips() {
        val queue = SyncQueue(newestFirst = true, partials = setOf("20260925092835_0060.mp4"))
        queue.addAll(
            listOf(
                clip("20260925092735", 100),
                clip("20260925092835", 200),
                clip("20260925093035", 400),
                gps("20260925092634", 50),
            ),
        )

        assertEquals(
            listOf("20260925092634_0198.gpx", "20260925092835_0060.mp4", "20260925093035_0060.mp4", "20260925092735_0060.mp4"),
            drain(queue),
        )
    }

    @Test
    fun relistedClipGoesAheadOfBacklogAndIsNotQueuedTwice() {
        val queue = SyncQueue(newestFirst = true, partials = emptySet())
        queue.addAll(listOf(clip("20260925090000", 100), clip("20260925090100", 160)))
        assertEquals("20260925090100_0060.mp4", queue.next()?.first?.name)

        val added = queue.addAll(listOf(clip("20260925090100", 160), clip("20260925093000", 1900)))

        assertEquals("already-seen clips are not re-queued", 1, added)
        assertEquals(3, queue.total())
        assertEquals(listOf("20260925093000_0060.mp4", "20260925090000_0060.mp4"), drain(queue))
    }

    @Test
    fun oldestFirstStillPutsPartialsFirst() {
        val queue = SyncQueue(newestFirst = false, partials = setOf("20260925093035_0060.mp4"))
        queue.addAll(listOf(clip("20260925092735", 100), clip("20260925093035", 400), clip("20260925092835", 200)))

        assertEquals(
            listOf("20260925093035_0060.mp4", "20260925092735_0060.mp4", "20260925092835_0060.mp4"),
            drain(queue),
        )
    }

    @Test
    fun windowKeepsClipsWithinMinutesOfNewest() {
        val files = listOf(clip("a", 10_000), clip("b", 10_000 - 29 * 60), clip("c", 10_000 - 31 * 60), MediaFile("d.gpx", "gps-nmea"))

        assertEquals(listOf("a_0060.mp4", "b_0060.mp4", "d.gpx"), withinWindow(files, 10_000, 30).map { it.name })
        assertEquals("0 keeps everything", 4, withinWindow(files, 10_000, 0).size)
    }

    @Test
    fun stalePartialsAreOnlyThoseOlderThanTheOldestCameraFile() {
        val dir = temp.newFolder("staging")
        File(dir, "20260924212740_0060.mp4.part").writeText("x")
        File(dir, "20260925092835_0060.mp4.part").writeText("x")
        File(dir, "20260924212740_0060.mp4").writeText("finished clips are never touched")
        val camera = listOf(clip("20260925080000", 1), clip("20260925093035", 2))

        assertEquals(listOf("20260924212740_0060.mp4.part"), stalePartials(dir, camera).map { it.name })
        assertEquals(setOf("20260924212740_0060.mp4", "20260925092835_0060.mp4"), partialNames(dir))
        assertTrue("an empty listing deletes nothing", stalePartials(dir, emptyList()).isEmpty())
    }

    @Test
    fun workerPoolGrowsAndShrinksWhileRunning() = runBlocking {
        val items = ConcurrentLinkedQueue((1..40).toList())
        val target = AtomicInteger(1)
        val running = AtomicInteger(0)
        val peaks = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val resizes = mutableListOf<Int>()
        val done = AtomicInteger(0)

        runWorkerPool(
            target = { target.get() },
            next = { items.poll() },
            hasWork = { items.isNotEmpty() },
            onResize = { synchronized(resizes) { resizes += it } },
            checkMs = 5,
        ) { item ->
            val now = running.incrementAndGet()
            peaks += target.get() to now
            delay(20)
            running.decrementAndGet()
            if (done.incrementAndGet() == 5) target.set(4)
            if (item == 30) target.set(2)
        }

        assertEquals(40, done.get())
        assertTrue("reached 4 streams after the live change", peaks.any { it.second == 4 })
        assertTrue("never above 4", peaks.all { it.second <= 4 })
        assertEquals(listOf(4, 2), resizes)
    }

    @Test
    fun workerPoolStopsTakingWorkWhenStopped() = runBlocking {
        val items = ConcurrentLinkedQueue((1..10).toList())
        val done = AtomicInteger(0)

        runWorkerPool(
            target = { 2 },
            next = { items.poll() },
            hasWork = { items.isNotEmpty() },
            stopped = { done.get() >= 3 },
            checkMs = 5,
        ) { done.incrementAndGet() }

        assertTrue("in-flight files finish, no new ones start", done.get() in 3..4)
    }

    @Test
    fun stampsSurviveARestart() {
        val file = File(temp.root, "stamps.txt")
        TransferStamps(file).apply {
            markSync(1_000L)
            markOffload(2_000L)
        }

        val reloaded = TransferStamps(file)
        assertEquals(1_000L, reloaded.lastSyncMs)
        assertEquals(2_000L, reloaded.lastOffloadMs)
        assertNull(TransferStamps(File(temp.root, "missing.txt")).lastSyncMs)
    }

    @Test
    fun stampShowsDateOnlyWhenNotToday() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 25, 9, 40, 0) }
        val now = cal.timeInMillis
        val yesterday = now - 12 * 60 * 60 * 1000L

        assertEquals("09:40", formatTransferStamp(now, now, Locale.UK))
        assertEquals("24 Sept, 21:40", formatTransferStamp(yesterday, now, Locale.UK).replace("Sep,", "Sept,"))
        assertEquals("never", formatTransferStamp(null, now))
    }
}

package io.github.johnkuruvila.z50bridge.sync

import io.github.johnkuruvila.z50bridge.camera.MediaFile
import java.io.File
import java.util.PriorityQueue

private const val PART_SUFFIX = ".part"
private val STAMP = Regex("""^(\d{14})_""")

/** Names of clips with a partial download on the phone, as the camera lists them. */
internal fun partialNames(dir: File): Set<String> =
    dir.listFiles { file -> file.isFile && file.name.endsWith(PART_SUFFIX) }
        ?.map { it.name.removeSuffix(PART_SUFFIX) }
        ?.toSet()
        .orEmpty()

/**
 * Partial downloads older than the oldest clip the camera still lists. Loop
 * recording has overwritten those clips, so the partials can never be
 * finished. Names are compared by their yyyyMMddHHmmss prefix, which works for
 * front, rear and sidecar files alike.
 */
internal fun stalePartials(dir: File, cameraFiles: Collection<MediaFile>): List<File> {
    val oldest = cameraFiles.mapNotNull { STAMP.find(it.filename)?.groupValues?.get(1) }.minOrNull()
        ?: return emptyList()
    return dir.listFiles { file -> file.isFile && file.name.endsWith(PART_SUFFIX) }
        ?.filter { part ->
            val stamp = STAMP.find(part.name)?.groupValues?.get(1)
            stamp != null && stamp < oldest
        }
        .orEmpty()
}

/**
 * Keeps files whose recording started within [minutes] of the newest clip on
 * the camera. The camera clock, not the phone's, sets the reference, so a
 * drifted camera clock does not empty the window. 0 keeps everything; files
 * without a start time are kept.
 */
internal fun withinWindow(files: List<MediaFile>, newestStart: Long?, minutes: Int): List<MediaFile> {
    if (minutes <= 0 || newestStart == null) return files
    val cutoff = newestStart - minutes * 60L
    return files.filter { (it.startUnix ?: return@filter true) >= cutoff }
}

/**
 * Download order for one sync. GPS sidecars come first (small, and they
 * locate every clip), then clips with a partial on the phone (the bytes
 * already spent are not wasted when the ignition cuts the camera again), then
 * the rest, newest or oldest first per Settings. Clips found by a later
 * re-list join the same order, so with newest-first the footage just recorded
 * goes ahead of the backlog.
 */
internal class SyncQueue(newestFirst: Boolean, private val partials: Set<String>) {
    private val byTime = compareBy<MediaFile> { it.startUnix ?: 0L }.thenBy { it.name }
    private val order = compareBy<MediaFile> { tier(it) }
        .then(if (newestFirst) byTime.reversed() else byTime)
    private val queue = PriorityQueue(16, order)
    private val seen = HashSet<String>()
    private var totalBytes = 0L
    private var taken = 0

    private fun tier(file: MediaFile): Int = when {
        file.kind != "front" && file.kind != "rear" -> 0
        file.name in partials || file.filename in partials -> 1
        else -> 2
    }

    /** Adds files not queued before; returns how many were new. */
    @Synchronized
    fun addAll(files: Collection<MediaFile>): Int {
        var added = 0
        files.forEach { file ->
            if (seen.add(file.name)) {
                queue.add(file)
                totalBytes += file.sizeBytes ?: 0L
                added++
            }
        }
        return added
    }

    /** Drops queued clips that fell out of the sync window after a re-list. */
    @Synchronized
    fun retainAll(keep: (MediaFile) -> Boolean) {
        val dropped = queue.filterNot(keep)
        if (dropped.isEmpty()) return
        queue.removeAll(dropped.toSet())
        dropped.forEach { file ->
            seen.remove(file.name)
            totalBytes -= file.sizeBytes ?: 0L
        }
    }

    /** The next file and its 1-based position in this sync. */
    @Synchronized
    fun next(): Pair<MediaFile, Int>? {
        val file = queue.poll() ?: return null
        taken++
        return file to taken
    }

    @Synchronized
    fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized
    fun total(): Int = seen.size

    @Synchronized
    fun totalBytes(): Long = totalBytes
}

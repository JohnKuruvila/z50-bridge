package io.github.johnkuruvila.z50bridge.sync

import io.github.johnkuruvila.z50bridge.ActiveFileTransfer
import io.github.johnkuruvila.z50bridge.TransferProgress
import io.github.johnkuruvila.z50bridge.camera.DdpaiClient
import io.github.johnkuruvila.z50bridge.camera.MediaFile
import io.github.johnkuruvila.z50bridge.camera.skipOpenClip
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.HistoryStore
import io.github.johnkuruvila.z50bridge.diag.DownloadTiming
import io.github.johnkuruvila.z50bridge.diag.FileRecord
import io.github.johnkuruvila.z50bridge.diag.TransferRecorder
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val MAX_DOWNLOAD_WORKERS = 4

fun downloadWorkers(fileCount: Int, requested: Int): Int {
    if (fileCount <= 0) return 0
    return minOf(requested.coerceIn(1, MAX_DOWNLOAD_WORKERS), fileCount)
}

/** "30 min", "1 h", "90 min" for the sync window. */
internal fun formatWindow(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"

private fun formatClock(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ms))

class SyncEngine(
    private val destDir: File,
    private val history: HistoryStore,
) {
    suspend fun run(
        client: DdpaiClient,
        settings: BridgeSettings,
        onProgress: (TransferProgress) -> Unit,
        onLog: (String) -> Unit,
        onIssue: (String) -> Unit = {},
        /** Per-file failures; defaults to [onLog] so callers that do not care get one stream. */
        onError: (String) -> Unit = onLog,
        recorder: TransferRecorder? = null,
        /** Read while the sync runs, so a Settings change applies to it. */
        streams: () -> Int = { settings.parallelDownloads },
        onFileDone: () -> Unit = {},
        /** Runs once there is something to download, before the first transfer starts. */
        onQueued: () -> Unit = {},
        /** "Nothing new" for a poll that found nothing; defaults to [onLog]. */
        onIdle: (String) -> Unit = onLog,
        relistMs: Long = RELIST_MS,
    ): Int = coroutineScope {
        val listStartMs = System.currentTimeMillis()
        destDir.mkdirs()
        val known = history.names()

        fun listVideos(): List<MediaFile> {
            val front = client.listPlayback(rear = false)
            val videos = (if (settings.skipOpenClip) skipOpenClip(front) else front).toMutableList()
            if (settings.pullRear) {
                val rear = client.listPlayback(rear = true)
                videos += if (settings.skipOpenClip) skipOpenClip(rear) else rear
            }
            return videos
        }

        fun listSidecars(): List<MediaFile> =
            if (settings.pullGps) client.listGps() + client.listGsn() else emptyList()

        val videos = listVideos()
        val sidecars = listSidecars()
        val removed = stalePartials(destDir, videos + sidecars).count { it.delete() }
        if (removed > 0) {
            onLog("Removed $removed partial download${if (removed == 1) "" else "s"} the camera has already overwritten")
        }
        val queue = SyncQueue(settings.prioritizeLatest, partialNames(destDir))

        /** Queues new files inside the sync window; returns (clips, sidecars) added. */
        fun enqueue(videos: List<MediaFile>, sidecars: List<MediaFile>): Pair<Int, Int> {
            val newest = videos.mapNotNull { it.startUnix }.maxOrNull()
            if (settings.syncWindowMinutes > 0 && newest != null) {
                val cutoff = newest - settings.syncWindowMinutes * 60L
                queue.retainAll { (it.startUnix ?: cutoff) >= cutoff }
            }
            val clips = withinWindow(videos.filter { it.name !in known }, newest, settings.syncWindowMinutes)
            val gps = withinWindow(sidecars.filter { it.name !in known }, newest, settings.syncWindowMinutes)
            return queue.addAll(clips) to queue.addAll(gps)
        }

        val pendingVideo = videos.count { it.name !in known }
        val pendingGps = sidecars.count { it.name !in known }
        val (clipCount, gpsCount) = enqueue(videos, sidecars)
        recorder?.note("Listing", "${System.currentTimeMillis() - listStartMs} ms; $pendingVideo clips and $pendingGps GPS files new")
        if (queue.isEmpty()) {
            onIdle(if (pendingVideo + pendingGps > 0) "Camera reachable · nothing new in the sync window" else "Camera reachable · nothing new")
            return@coroutineScope 0
        }
        onQueued()
        fun target(): Int = streams().coerceIn(1, MAX_DOWNLOAD_WORKERS)
        val workers = downloadWorkers(queue.total(), target())
        recorder?.note("Streams", "$workers (setting ${streams()})")
        val windowNote = if (settings.syncWindowMinutes > 0 && clipCount < pendingVideo) {
            " · syncing $clipCount from the newest ${formatWindow(settings.syncWindowMinutes)}"
        } else {
            ""
        }
        val gpsNote = if (pendingGps > 0) " and $pendingGps GPS file${if (pendingGps == 1) "" else "s"}" else ""
        onLog(
            "Found $pendingVideo new clip${if (pendingVideo == 1) "" else "s"}$gpsNote" + windowNote +
                if (workers > 1) " · $workers parallel downloads" else "",
        )
        if (gpsCount < pendingGps) recorder?.note("Sync window", "${formatWindow(settings.syncWindowMinutes)}; $gpsCount of $pendingGps GPS files")

        val completed = AtomicInteger(0)
        val consecutiveFailures = AtomicInteger(0)
        val abort = AtomicBoolean(false)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val activeByFile = ConcurrentHashMap<String, ActiveFileTransfer>()
        val throttle = ProgressThrottle()
        val meter = TransferMeter()
        val stateLock = Any()
        var completedBytes = 0L

        fun publish(force: Boolean = false) {
            synchronized(stateLock) {
                val active = activeByFile.values.sortedBy { it.fileIndex }
                val overall = completedBytes + active.sumOf { it.copied }
                val totalBytes = queue.totalBytes().takeIf { it > 0 }
                val total = queue.total()
                val speed = meter.sample(overall)
                val label = when {
                    active.isEmpty() -> "$total files"
                    active.size == 1 -> active[0].filename
                    else -> "${active.size} parallel streams"
                }
                if (!force && !throttle.shouldPublish(label, overall, totalBytes)) return
                onProgress(
                    TransferProgress(
                        filename = label,
                        fileIndex = (completed.get() + 1).coerceAtMost(total),
                        totalFiles = total,
                        copied = overall,
                        total = totalBytes,
                        overallCopied = overall,
                        overallTotal = totalBytes,
                        bytesPerSecond = speed,
                        etaSeconds = meter.etaSeconds(overall, totalBytes),
                        activeFiles = active,
                    ),
                )
            }
        }

        suspend fun download(item: MediaFile, displayIndex: Int) {
            // No Activity line per file: a long drive syncs a clip a minute,
            // and the transfer report already lists every file.
            val dest = File(destDir, item.filename)
            activeByFile[item.name] = ActiveFileTransfer(item.filename, displayIndex, 0L, item.sizeBytes)
            publish()
            val timing = DownloadTiming().also { recorder?.track(it) }
            val streamsAtStart = activeByFile.size
            try {
                client.download(item.name, dest, expectedBytes = item.sizeBytes, timing = timing) { copied, _ ->
                    activeByFile[item.name] = ActiveFileTransfer(item.filename, displayIndex, copied, item.sizeBytes)
                    publish()
                }
                val size = dest.length().takeIf { it > 0 } ?: (item.sizeBytes ?: 0L)
                synchronized(stateLock) {
                    completedBytes += size
                    activeByFile.remove(item.name)
                }
                history.add(item.name)
                recorder?.file(FileRecord(item.filename, item.kind, item.sizeBytes, timing, streamsAtStart))
                if (item.kind == "front" || item.kind == "rear") {
                    downloadThumb(client, destDir, item.filename)
                }
                completed.incrementAndGet()
                onFileDone()
                consecutiveFailures.set(0)
                publish(force = true)
            } catch (exc: Exception) {
                if (exc is CancellationException) throw exc
                if (!currentCoroutineContext().isActive) throw CancellationException("Transfer stopped")
                activeByFile.remove(item.name)
                failures.add("${item.filename}: ${exc.message ?: "download failed"}")
                recorder?.file(
                    FileRecord(item.filename, item.kind, item.sizeBytes, timing, streamsAtStart, exc.message ?: "download failed"),
                )
                publish(force = true)
                if (consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES &&
                    abort.compareAndSet(false, true)
                ) {
                    val warning = "Camera connection looks down; stopping after " +
                        "$MAX_CONSECUTIVE_FAILURES failures. Partial files kept for resume."
                    onLog(warning)
                    onIssue(warning)
                }
            }
        }

        val superDownloadMarker = File(destDir, SUPER_DOWNLOAD_MARKER)
        if (superDownloadMarker.exists()) {
            // A previous run was killed mid-sync; the camera may still be in
            // SuperDownload mode. Normalize before enabling it again.
            try {
                client.setSuperDownload(false)
                superDownloadMarker.delete()
            } catch (_: Exception) {
            }
        }
        try {
            try {
                client.setSuperDownload(true)
                recorder?.note("SuperDownload", "on (camera accepted)")
            } catch (exc: Exception) {
                recorder?.note("SuperDownload", "failed: ${exc.message}")
                throw exc
            }
            superDownloadMarker.createNewFile()
            // The camera keeps recording during a drive; re-list now and then
            // so clips recorded since the sync began are not left waiting
            // behind the backlog. A failed re-list only skips that round.
            val relister = launch(Dispatchers.IO) {
                while (isActive && !abort.get()) {
                    delay(relistMs)
                    val fresh = runCatching { listVideos() to listSidecars() }.getOrNull() ?: continue
                    val (clips, gps) = enqueue(fresh.first, fresh.second)
                    // No Activity line: on a drive this fires every minute.
                    if (clips + gps > 0) publish(force = true)
                }
            }
            try {
                runWorkerPool(
                    target = ::target,
                    next = { queue.next() },
                    hasWork = { !queue.isEmpty() },
                    stopped = { abort.get() },
                    onResize = { count ->
                        onLog("Now using $count parallel download${if (count == 1) "" else "s"}")
                        recorder?.note("Streams changed", "$count at ${formatClock(System.currentTimeMillis())}")
                    },
                ) { (item, displayIndex) -> download(item, displayIndex) }
            } finally {
                relister.cancel()
            }
        } finally {
            try {
                client.setSuperDownload(false)
                superDownloadMarker.delete()
            } catch (_: Exception) {
            }
        }
        failures.forEach { onError("Failed $it") }
        if (failures.isNotEmpty() && !abort.get()) {
            onIssue("${failures.size} camera download${if (failures.size == 1) "" else "s"} failed; see Activity")
        }
        completed.get()
    }

    private fun downloadThumb(client: DdpaiClient, dir: File, filename: String) {
        val thumbName = thumbNameFor(filename) ?: return
        val target = File(dir, thumbName)
        if (target.isFile && target.length() > 0) return
        try {
            client.download(thumbName, target)
        } catch (_: Exception) {
            target.delete()
            File(dir, "$thumbName.part").delete()
        }
    }

    private fun thumbNameFor(filename: String): String? {
        if (!filename.endsWith(".mp4", ignoreCase = true) && !filename.endsWith(".mov", ignoreCase = true)) {
            return null
        }
        return filename.substringBeforeLast('.') + "_T.jpg"
    }

    private companion object {
        const val SUPER_DOWNLOAD_MARKER = ".super_download_active"
        const val MAX_CONSECUTIVE_FAILURES = 2
        const val RELIST_MS = 60_000L
    }
}

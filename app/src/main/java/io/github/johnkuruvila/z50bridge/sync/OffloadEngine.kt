package io.github.johnkuruvila.z50bridge.sync

import io.github.johnkuruvila.z50bridge.ActiveFileTransfer
import io.github.johnkuruvila.z50bridge.TransferProgress
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source

internal const val MAX_UPLOAD_WORKERS = 3
private const val UPLOAD_TIMEOUT_MS = 6 * 60 * 60 * 1000L

fun offloadWorkers(fileCount: Int, maxWorkers: Int = MAX_UPLOAD_WORKERS): Int {
    if (fileCount <= 0) return 0
    return minOf(maxWorkers.coerceIn(1, 10), fileCount)
}

class OffloadEngine {
    fun pendingFiles(staging: File): List<File> {
        if (!staging.isDirectory) return emptyList()
        return staging.listFiles { file ->
            file.isFile && file.extension.lowercase() in setOf("mp4", "gpx", "git", "gsn")
        }?.sortedWith(
            compareBy<File> { file ->
                // Offload lightweight GPS and sensor sidecars first, then video clips
                if (file.extension.lowercase() in setOf("gpx", "git", "gsn")) 0 else 1
            }.thenByDescending { it.name },
        ) ?: emptyList()
    }

    suspend fun run(
        staging: File,
        settings: BridgeSettings,
        httpClient: OkHttpClient,
        onProgress: (TransferProgress) -> Unit,
        onLog: (String) -> Unit,
        onIssue: (String) -> Unit = {},
        /** Per-file failures; defaults to [onLog] so callers that do not care get one stream. */
        onError: (String) -> Unit = onLog,
        /** Read while the offload runs, so a Settings change applies to it. */
        streams: () -> Int = { settings.parallelUploads },
        onFileDone: () -> Unit = {},
    ): Int = coroutineScope {
        val swept = orphanThumbnails(staging).count { it.delete() }
        if (swept > 0) onLog("Removed $swept leftover thumbnail${if (swept == 1) "" else "s"} of offloaded clips")
        val files = pendingFiles(staging)
        if (files.isEmpty()) {
            onLog("Nothing staged to offload")
            return@coroutineScope 0
        }
        val workers = offloadWorkers(files.size, streams())
        val totalBytes = files.sumOf { it.length() }
        val nextIndex = AtomicInteger(0)
        val uploaded = AtomicInteger(0)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val sentByFile = ConcurrentHashMap<String, Long>()
        val activeByFile = ConcurrentHashMap<String, ActiveFileTransfer>()
        val throttle = ProgressThrottle()
        val meter = TransferMeter()
        val progressLock = Any()

        fun publish(force: Boolean = false) {
            synchronized(progressLock) {
                val sum = sentByFile.values.sum()
                val speed = meter.sample(sum)
                val currentActive = activeByFile.values.sortedBy { it.filename }
                val label = when {
                    currentActive.isEmpty() -> "${files.size} files"
                    currentActive.size == 1 -> currentActive[0].filename
                    else -> "${currentActive.size} parallel streams"
                }
                if (!force && !throttle.shouldPublish(label, sum, totalBytes)) return
                onProgress(
                    TransferProgress(
                        filename = label,
                        fileIndex = (uploaded.get() + 1).coerceAtMost(files.size),
                        totalFiles = files.size,
                        copied = sum,
                        total = totalBytes,
                        overallCopied = sum,
                        overallTotal = totalBytes,
                        bytesPerSecond = speed,
                        etaSeconds = meter.etaSeconds(sum, totalBytes),
                        isOffload = true,
                        activeFiles = currentActive,
                    ),
                )
            }
        }

        onLog("Uploading ${files.size} files over $workers parallel HTTP stream${if (workers == 1) "" else "s"}")
        suspend fun upload(file: File) {
            val fileTotal = file.length()
            try {
                sentByFile[file.name] = 0L
                activeByFile[file.name] = ActiveFileTransfer(file.name, uploaded.get() + 1, 0L, fileTotal)
                publish()

                // The receiver verifies this and refuses a corrupted transfer (HTTP 422).
                val checksum = sha256Hex(file)
                val request = Request.Builder()
                    .url("http://${settings.destHost}:${settings.destPort}/upload/${file.name}")
                    .header("X-Content-SHA256", checksum)
                    .put(
                        CountingFileRequestBody(file) { bytesSent ->
                            sentByFile[file.name] = bytesSent
                            activeByFile[file.name] =
                                ActiveFileTransfer(file.name, uploaded.get() + 1, bytesSent, fileTotal)
                            publish()
                        },
                    )
                    .authorize(settings)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val ackBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}: $ackBody")
                    }
                    // The file is deleted below, so a 2xx is not enough: the
                    // receiver has to confirm it stored these exact bytes.
                    val ack = UploadAck.parse(ackBody)
                        ?: throw IOException("Unrecognised reply from the ingest server; keeping the local copy")
                    ack.refusal(fileTotal, checksum)?.let { reason ->
                        throw IOException("$reason; keeping the local copy")
                    }
                }

                activeByFile.remove(file.name)
                sentByFile[file.name] = fileTotal
                publish(force = true)

                if (!file.delete()) {
                    val warning = "Uploaded ${file.name} but could not delete local copy"
                    onError(warning)
                    onIssue(warning)
                } else {
                    // The thumbnail only serves the Clips screen; it goes with its clip.
                    thumbnailFor(file)?.let { File(staging, it).delete() }
                }
                uploaded.incrementAndGet()
                onFileDone()
            } catch (exc: Exception) {
                if (exc is CancellationException) throw exc
                if (!currentCoroutineContext().isActive) throw CancellationException("Transfer stopped")
                activeByFile.remove(file.name)
                // A failed file no longer counts toward the bytes sent.
                sentByFile.remove(file.name)
                failures.add("${file.name}: ${exc.message ?: "upload failed"}")
            }
        }

        fun target(): Int = offloadWorkers(Int.MAX_VALUE, streams())
        val finished = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) {
            runWorkerPool(
                target = ::target,
                next = { files.getOrNull(nextIndex.getAndIncrement()) },
                hasWork = { nextIndex.get() < files.size },
                onResize = { count -> onLog("Now using $count upload stream${if (count == 1) "" else "s"}") },
                work = { file -> upload(file) },
            )
        }
        if (finished == null) throw IOException("upload timed out")
        failures.forEach { onError("Failed $it") }
        val count = uploaded.get()
        if (count == 0) {
            throw IOException(failures.firstOrNull() ?: "offload failed")
        }
        if (failures.isNotEmpty()) {
            onIssue("${failures.size} offload file${if (failures.size == 1) "" else "s"} failed; see Activity")
        }
        count
    }

    fun testLogin(settings: BridgeSettings, httpClient: OkHttpClient): String {
        val request = Request.Builder()
            .url("http://${settings.destHost}:${settings.destPort}/health")
            .get()
            .authorize(settings)
            .build()
        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: "HTTP ${resp.code}"
                throw IOException("HTTP ${resp.code}: $err")
            }
        }
        return "Connected. Ingest server on ${settings.destHost}:${settings.destPort} is ready."
    }

    /** Sends a diagnostics report to the receiver, which keeps it apart from clips. */
    fun uploadDiagnostics(settings: BridgeSettings, httpClient: OkHttpClient, name: String, text: String) {
        val request = Request.Builder()
            .url("http://${settings.destHost}:${settings.destPort}/diagnostics/$name")
            .put(text.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .authorize(settings)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Receivers from before 0.5.2 treat this as a clip upload and refuse the name.
                if (resp.code == 400 || resp.code == 404) throw IOException("the receiver on server does not accept diagnostics yet")
                throw IOException("HTTP ${resp.code}")
            }
        }
    }
}

/** `20260915213739_0060_T.jpg` for `20260915213739_0060.mp4`; null for sidecars. */
internal fun thumbnailFor(file: File): String? =
    if (file.extension.equals("mp4", ignoreCase = true)) "${file.nameWithoutExtension}_T.jpg" else null

/**
 * Thumbnails (and half-downloaded ones) whose clip is no longer staged.
 * Offload used to delete clips without them, so they piled up for good.
 */
internal fun orphanThumbnails(staging: File): List<File> {
    val names = staging.list()?.toSet() ?: return emptyList()
    return names.mapNotNull { name ->
        val thumb = name.removeSuffix(".part")
        if (!thumb.endsWith("_T.jpg")) return@mapNotNull null
        val stem = thumb.removeSuffix("_T.jpg")
        if ("$stem.mp4" in names || "$stem.mp4.part" in names) null else File(staging, name)
    }
}

internal fun sha256Hex(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun Request.Builder.authorize(settings: BridgeSettings): Request.Builder {
    if (settings.destToken.isNotBlank()) {
        header("Authorization", "Bearer ${settings.destToken.trim()}")
        header("X-Auth-Token", settings.destToken.trim())
    }
    return this
}

private class CountingFileRequestBody(
    private val file: File,
    private val contentType: MediaType = "application/octet-stream".toMediaType(),
    private val onBytesWritten: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = contentType
    override fun contentLength(): Long = file.length()
    override fun writeTo(sink: BufferedSink) {
        val buffer = Buffer()
        val chunkSize = 256L * 1024L
        var bytesWritten = 0L
        file.source().use { source ->
            while (true) {
                val read = source.read(buffer, chunkSize)
                if (read == -1L) break
                sink.write(buffer, read)
                sink.flush()
                bytesWritten += read
                onBytesWritten(bytesWritten)
            }
        }
    }
}

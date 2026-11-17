package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.camera.DdpaiClient
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.HistoryStore
import io.github.johnkuruvila.z50bridge.sync.OffloadEngine
import io.github.johnkuruvila.z50bridge.sync.SyncEngine
import io.github.johnkuruvila.z50bridge.sync.TransferGate
import io.github.johnkuruvila.z50bridge.sync.abortUntilUnwound
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stop, Pause and Resume against the real engines, OkHttp and sockets. The receiver
 * reads slowly on purpose, so a transfer is still in flight when the button is pressed.
 */
class TransferControlTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val http = OkHttpClient()
    private var receiver: SlowReceiver? = null
    private var camera: MockWebServer? = null

    @After
    fun tearDown() {
        receiver?.close()
        camera?.shutdown()
    }

    // ------------------------------------------------------------------ upload

    private fun stagedClip(bytes: Int): File {
        val dir = temp.newFolder("staging")
        return File(dir, "20261101100000_0060.mp4").apply { writeBytes(Random(1).nextBytes(bytes)) }
    }

    private class Upload(val session: Deferred<Int>, val sent: AtomicLong, val errors: List<String>, val removed: AtomicLong)

    private fun CoroutineScope.startUpload(clip: File, port: Int, gate: TransferGate): Upload {
        val sent = AtomicLong(0)
        val removed = AtomicLong(0)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val session = async(Dispatchers.IO) {
            OffloadEngine().run(
                staging = clip.parentFile!!,
                settings = BridgeSettings(destHost = "127.0.0.1", destPort = port),
                httpClient = http,
                onProgress = { sent.set(it.overallCopied) },
                onLog = {},
                onError = { errors.add(it) },
                onFileRemoved = { removed.addAndGet(it) },
                streams = { 1 },
                gate = gate,
            )
        }
        return Upload(session, sent, errors, removed)
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        withTimeout(WAIT_MS) {
            while (!condition()) delay(10)
        }
        assertTrue(what, condition())
    }

    /** What the Stop button does to a session. */
    private suspend fun CoroutineScope.pressStop(session: Deferred<*>, ioDone: () -> Boolean = { false }): Long {
        val started = System.nanoTime()
        session.cancel()
        val aborter = async(Dispatchers.IO) {
            abortUntilUnwound(session, windowMs = 10_000, intervalMs = 50, ioDone = ioDone, abort = http.dispatcher::cancelAll)
        }
        withTimeout(WAIT_MS) { session.join() }
        aborter.await()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    /** The bug: a file that would need 30 s more kept Stop waiting for all of it. */
    @Test
    fun stopEndsAnUploadInFlightAtOnce() = runBlocking {
        val clip = stagedClip(CLIP_BYTES)
        val slow = SlowReceiver(bytesPerSecond = CLIP_BYTES / 30).also { receiver = it }
        val upload = startUpload(clip, slow.port, TransferGate())
        waitFor("the upload started") { slow.received.get() > 0 }

        val tookMs = pressStop(upload.session)

        assertTrue("Stop took $tookMs ms", tookMs < 3_000)
        assertEquals("nothing finished", 0, slow.completed.get())
        assertTrue("the clip stays queued", clip.exists())
        assertEquals("An interrupted upload must stay in the pending count", 0L, upload.removed.get())
    }

    @Test
    fun pauseHoldsTheUploadAndResumeFinishesTheSameRequest() = runBlocking {
        val clip = stagedClip(CLIP_BYTES)
        val expected = sha256(clip.readBytes())
        val slow = SlowReceiver(bytesPerSecond = CLIP_BYTES / 3).also { receiver = it }
        val gate = TransferGate()
        val upload = startUpload(clip, slow.port, gate)
        waitFor("the upload started") { upload.sent.get() > 0 }

        gate.pause()
        delay(SETTLE_MS) // whatever was already in the socket buffers drains
        val heldAt = slow.received.get()
        delay(HOLD_MS)
        assertEquals("nothing moves while paused", heldAt, slow.received.get())
        assertTrue("the transfer is still alive", upload.session.isActive)
        assertTrue("the clip is still queued", clip.exists())
        assertTrue("paused partway", heldAt < CLIP_BYTES)

        gate.resume()
        assertEquals(1, withTimeout(WAIT_MS) { upload.session.await() })
        assertEquals("carried on over the same connection", 1, slow.connections.get())
        assertEquals(expected, slow.lastSha256)
        assertEquals("Only acknowledged and removed bytes leave the queue", CLIP_BYTES.toLong(), upload.removed.get())
        assertFalse("removed once the receiver confirmed it", clip.exists())
        assertTrue(upload.errors.isEmpty())
    }

    @Test
    fun aConnectionLostDuringThePauseIsRetriedWithoutAFailure() = runBlocking {
        val clip = stagedClip(CLIP_BYTES)
        val expected = sha256(clip.readBytes())
        val slow = SlowReceiver(bytesPerSecond = CLIP_BYTES / 3).also { receiver = it }
        val gate = TransferGate()
        val upload = startUpload(clip, slow.port, gate)
        waitFor("the upload started") { upload.sent.get() > 0 }

        gate.pause()
        delay(SETTLE_MS)
        slow.dropConnections()
        delay(HOLD_MS)
        slow.bytesPerSecond.set(CLIP_BYTES.toLong())
        gate.resume()

        assertEquals(1, withTimeout(WAIT_MS) { upload.session.await() })
        assertTrue("sent again on a new connection", slow.connections.get() >= 2)
        assertEquals(expected, slow.lastSha256)
        assertEquals(emptyList<String>(), upload.errors.toList())
        assertFalse(clip.exists())
    }

    @Test
    fun stopEndsAPausedUpload() = runBlocking {
        val clip = stagedClip(CLIP_BYTES)
        val slow = SlowReceiver(bytesPerSecond = CLIP_BYTES / 3).also { receiver = it }
        val gate = TransferGate(pollMs = 50)
        val upload = startUpload(clip, slow.port, gate)
        waitFor("the upload started") { upload.sent.get() > 0 }
        gate.pause()
        delay(SETTLE_MS)

        val tookMs = pressStop(upload.session)

        assertTrue("Stop took $tookMs ms", tookMs < 3_000)
        assertTrue(clip.exists())
        assertEquals(0, slow.completed.get())
    }

    // ---------------------------------------------------------------- download

    private fun fakeCamera(content: ByteArray, bytesPer100ms: Long): Pair<DdpaiClient, List<String>> {
        val superDownload = Collections.synchronizedList(mutableListOf<String>())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cmd = request.requestUrl?.queryParameter("cmd")
                val body = request.body.clone().readUtf8()
                return when {
                    cmd == "APP_PlaybackListReq" -> nested(
                        """{"file":[{"name":"$CLIP_NAME","size":"${content.size / 1024}","starttime":100}]}""",
                    )
                    cmd == "API_SuperDownload" || body.contains("\"switch\"") -> {
                        superDownload.add(if (body.contains("\"on\"")) "on" else "off")
                        MockResponse().setBody("""{"errcode":0,"data":"{}"}""")
                    }
                    request.path?.contains("cmd.cgi") == true ->
                        MockResponse().setBody("""{"errcode":0,"data":"{}"}""")
                    request.path?.endsWith(CLIP_NAME) == true -> {
                        val from = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                        val slice = content.copyOfRange(from, content.size)
                        val response = MockResponse()
                            .setBody(Buffer().write(slice))
                            .throttleBody(bytesPer100ms, 100, TimeUnit.MILLISECONDS)
                        if (from > 0) {
                            response.setResponseCode(206)
                                .setHeader("Content-Range", "bytes $from-${content.size - 1}/${content.size}")
                        } else {
                            response
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        camera = server
        return DdpaiClient(baseUrl = server.url("/").toString().trimEnd('/'), http = http) to superDownload
    }

    private fun nested(json: String) = MockResponse().setBody(
        org.json.JSONObject().put("errcode", 0).put("data", json).toString(),
    )

    private fun syncSettings() = BridgeSettings(skipOpenClip = false, pullGps = false, pullRear = false, syncWindowMinutes = 0)

    @Test
    fun pausedSyncKeepsItsPartialFileAndResumeCompletesIt() = runBlocking {
        val content = Random(2).nextBytes(CLIP_BYTES)
        val (client, superDownload) = fakeCamera(content, bytesPer100ms = CLIP_BYTES / 30L)
        val dest = temp.newFolder("camera-staging")
        val gate = TransferGate()
        val copied = AtomicLong(0)
        val saved = AtomicLong(0)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val session = async(Dispatchers.IO) {
            SyncEngine(dest, HistoryStore(File(dest, "synced_history.json"))).run(
                client = client,
                settings = syncSettings(),
                onProgress = { copied.set(it.overallCopied) },
                onFileSaved = { saved.addAndGet(it) },
                onLog = {},
                onError = { errors.add(it) },
                gate = gate,
            )
        }
        waitFor("the download started") { copied.get() > 0 }

        gate.pause()
        // The camera's Bluetooth remote comes back for the length of the pause.
        waitFor("SuperDownload turned off") { superDownload.lastOrNull() == "off" }
        val part = File(dest, "$CLIP_NAME.part")
        delay(SETTLE_MS)
        val heldAt = part.length()
        delay(HOLD_MS)
        assertEquals("nothing is written while paused", heldAt, part.length())
        assertTrue("partial file kept", heldAt in 1 until CLIP_BYTES)
        assertEquals("Partial downloads must not count as completed files", 0L, saved.get())

        gate.resume()
        val synced = withTimeout(WAIT_MS) { session.await() }
        assertEquals(emptyList<String>(), errors.toList())
        assertEquals(1, synced)
        assertArrayEquals(content, File(dest, CLIP_NAME).readBytes())
        assertEquals("A resumed file enters staging exactly once", content.size.toLong(), saved.get())
        assertEquals(listOf("on", "off", "on", "off"), superDownload.toList())
        assertEquals(emptyList<String>(), errors.toList())
    }

    @Test
    fun stoppedSyncAbortsTheDownloadButStillRestoresTheCamera() = runBlocking {
        val content = Random(3).nextBytes(CLIP_BYTES)
        val (client, superDownload) = fakeCamera(content, bytesPer100ms = CLIP_BYTES / 300L)
        val dest = temp.newFolder("camera-staging")
        val copied = AtomicLong(0)
        val workersDone = AtomicBoolean(false)
        val session = async(Dispatchers.IO) {
            SyncEngine(dest, HistoryStore(File(dest, "synced_history.json"))).run(
                client = client,
                settings = syncSettings(),
                onProgress = { copied.set(it.overallCopied) },
                onLog = {},
                onWorkersDone = { workersDone.set(true) },
            )
        }
        waitFor("the download started") { copied.get() > 0 }

        val tookMs = pressStop(session, ioDone = workersDone::get)

        assertTrue("Stop took $tookMs ms", tookMs < 3_000)
        assertEquals("the camera leaves SuperDownload", "off", superDownload.last())
        assertFalse("the clip was not finished", File(dest, CLIP_NAME).exists())
        assertTrue("the partial file is kept for next time", File(dest, "$CLIP_NAME.part").length() > 0)
        assertFalse("the crash marker is cleared", File(dest, ".super_download_active").exists())
    }

    @Test
    fun anAlreadyStagedFileDoesNotInflateTheLiveQueue() = runBlocking {
        val content = Random(4).nextBytes(1024)
        val (client, _) = fakeCamera(content, bytesPer100ms = 1024)
        val dest = temp.newFolder("already-staged")
        File(dest, CLIP_NAME).writeBytes(content)
        val added = AtomicLong(0)
        SyncEngine(dest, HistoryStore(File(dest, "synced_history.json"))).run(
            client = client, settings = syncSettings(), onProgress = {}, onLog = {},
            onFileSaved = { added.addAndGet(it) },
        )
        assertEquals(0L, added.get())
        assertArrayEquals(content, File(dest, CLIP_NAME).readBytes())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A receiver that reads at a set rate and answers like z50_receiver.py. */
    private class SlowReceiver(bytesPerSecond: Int) : AutoCloseable {
        private val server = ServerSocket(0)
        private val open = Collections.synchronizedList(mutableListOf<Socket>())
        val bytesPerSecond = AtomicLong(bytesPerSecond.toLong())
        val port: Int = server.localPort
        val connections = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val received = AtomicLong(0)

        @Volatile
        var lastSha256: String? = null

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    open.add(socket)
                    thread(isDaemon = true) { runCatching { serve(socket) } }
                }
            }
        }

        fun dropConnections() {
            synchronized(open) {
                open.forEach { runCatching { it.close() } }
                open.clear()
            }
        }

        private fun serve(socket: Socket) {
            socket.receiveBufferSize = 64 * 1024
            val input = BufferedInputStream(socket.getInputStream())
            val length = readContentLength(input)
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(16 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(chunk, 0, minOf(left, chunk.size.toLong()).toInt())
                if (n < 0) return
                digest.update(chunk, 0, n)
                received.addAndGet(n.toLong())
                left -= n
                Thread.sleep(maxOf(1L, n * 1000L / bytesPerSecond.get()))
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            lastSha256 = sha
            completed.incrementAndGet()
            val json = """{"status":"ok","filename":"clip","bytes":$length,"sha256":"$sha","verified":true}"""
            socket.getOutputStream().write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${json.length}\r\n" +
                    "Connection: close\r\n\r\n$json").toByteArray(),
            )
            socket.getOutputStream().flush()
            socket.close()
        }

        private fun readContentLength(input: BufferedInputStream): Long {
            val head = ByteArrayOutputStream()
            while (!head.toString().endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) throw java.io.EOFException()
                head.write(b)
            }
            return Regex("(?i)content-length: *(\\d+)").find(head.toString())!!.groupValues[1].toLong()
        }

        override fun close() {
            server.close()
            dropConnections()
        }
    }

    private companion object {
        const val CLIP_NAME = "20261101100000_0060.mp4"
        const val CLIP_BYTES = 24 * 1024 * 1024
        const val WAIT_MS = 30_000L
        const val SETTLE_MS = 800L
        const val HOLD_MS = 400L
    }
}

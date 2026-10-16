package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.camera.DdpaiClient
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.HistoryStore
import io.github.johnkuruvila.z50bridge.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SyncEngineFailureTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: DdpaiClient
    private val downloads = AtomicInteger(0)
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cmd = request.requestUrl?.queryParameter("cmd")
                return when {
                    request.path?.contains("cmd.cgi") == true && cmd == "APP_PlaybackListReq" ->
                        nested("""{"file":[
                            {"name":"20260915210000_0001.mp4","size":"1024","starttime":100},
                            {"name":"20260915210100_0002.mp4","size":"1024","starttime":200},
                            {"name":"20260915210200_0003.mp4","size":"1024","starttime":300},
                            {"name":"20260915210300_0004.mp4","size":"1024","starttime":400}
                        ]}""")
                    request.path?.contains("cmd.cgi") == true ->
                        MockResponse().setBody("""{"errcode":0,"data":"{}"}""")
                    request.method == "GET" -> {
                        downloads.incrementAndGet()
                        MockResponse().setBody("""{"errcode":7700003a,"errmsg":"refused"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = DdpaiClient(baseUrl = server.url("/").toString().trimEnd('/'), http = OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun syncStopsAfterTwoConsecutiveFailuresAndKeepsNoRejectedParts() = runBlocking {
        val dest = temp.newFolder("staging")
        val engine = SyncEngine(dest, HistoryStore(File(dest, "synced_history.json")))

        val synced = engine.run(
            client = client,
            settings = BridgeSettings(pullRear = false, pullGps = false, parallelDownloads = 1),
            onProgress = {},
            onLog = { logs.add(it) },
        )

        assertEquals(0, synced)
        assertEquals("only two attempts before aborting", 2, downloads.get())
        assertTrue(logs.any { it.contains("stopping after 2 failures", ignoreCase = true) })
        assertTrue(
            "rejected payloads must not leave partials",
            dest.listFiles().orEmpty().none { it.name.endsWith(".part") || it.name.endsWith(".mp4") },
        )
    }

    private fun nested(data: String): MockResponse {
        val compact = data.replace(Regex("\\s+"), "")
        val escaped = compact.replace("\\", "\\\\").replace("\"", "\\\"")
        return MockResponse().setBody("""{"errcode":0,"data":"$escaped"}""")
    }
}

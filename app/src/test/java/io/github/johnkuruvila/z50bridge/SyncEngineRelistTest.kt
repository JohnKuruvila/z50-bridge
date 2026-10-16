package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.camera.DdpaiClient
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.data.HistoryStore
import io.github.johnkuruvila.z50bridge.sync.SyncEngine
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A clip recorded after the sync began is fetched before the older backlog. */
class SyncEngineRelistTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: DdpaiClient
    private val listings = AtomicInteger(0)
    private val downloaded = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cmd = request.requestUrl?.queryParameter("cmd")
                return when {
                    request.path?.contains("cmd.cgi") == true && cmd == "APP_PlaybackListReq" -> {
                        val newClip = if (listings.incrementAndGet() > 1) {
                            """,{"name":"20260925093300_0060.mp4","size":"4","starttime":400}"""
                        } else {
                            ""
                        }
                        nested(
                            """{"file":[
                                {"name":"20260925093000_0060.mp4","size":"4","starttime":100},
                                {"name":"20260925093100_0060.mp4","size":"4","starttime":200},
                                {"name":"20260925093200_0060.mp4","size":"4","starttime":300}$newClip
                            ]}""",
                        )
                    }
                    request.path?.contains("cmd.cgi") == true ->
                        MockResponse().setBody("""{"errcode":0,"data":"{}"}""")
                    request.method == "GET" && request.path?.endsWith(".mp4") == true -> {
                        downloaded += request.path!!.substringAfterLast('/')
                        MockResponse().setBody("x".repeat(4096)).setBodyDelay(300, TimeUnit.MILLISECONDS)
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
    fun newClipFromRelistJumpsTheBacklog() = runBlocking {
        val dest = temp.newFolder("staging")
        val engine = SyncEngine(dest, HistoryStore(File(dest, "synced_history.json")))

        val synced = engine.run(
            client = client,
            settings = BridgeSettings(pullGps = false, skipOpenClip = false, prioritizeLatest = true, parallelDownloads = 1),
            onProgress = {},
            onLog = {},
            relistMs = 100,
        )

        assertEquals(4, synced)
        assertEquals(
            listOf("20260925093200_0060.mp4", "20260925093300_0060.mp4", "20260925093100_0060.mp4", "20260925093000_0060.mp4"),
            downloaded.filter { it.endsWith("_0060.mp4") },
        )
    }

    private fun nested(data: String): MockResponse {
        val compact = data.replace(Regex("\\s+"), "")
        val escaped = compact.replace("\\", "\\\\").replace("\"", "\\\"")
        return MockResponse().setBody("""{"errcode":0,"data":"$escaped"}""")
    }
}

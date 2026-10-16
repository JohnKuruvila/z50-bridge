package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.camera.DdpaiClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class DdpaiClientListingTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DdpaiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DdpaiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            http = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listGsnParsesNamesSizesAndStartTimes() {
        enqueueNested(
            """{"file":[{"name":"20260915213739_0060.gsn","size":"1024","starttime":1757962659},""" +
                """{"name":"20260915213839_0061.gsn","starttime":1757962719}]}""",
        )

        val files = client.listGsn()

        assertEquals(2, files.size)
        assertEquals("20260915213739_0060.gsn", files[0].name)
        assertEquals("gsn", files[0].kind)
        assertEquals(1024L * 1024, files[0].sizeBytes)
        assertEquals(1757962659L, files[0].startUnix)
        assertEquals(null, files[1].sizeBytes)
    }

    @Test
    fun listGpsDistinguishesGitBundlesFromNmea() {
        enqueueNested("""{"file":[{"name":"a.git"},{"name":"b.gpx"}]}""")

        val files = client.listGps()

        assertEquals(listOf("gps-git", "gps-nmea"), files.map { it.kind })
        assertEquals(
            "/vcam/cmd.cgi?cmd=API_GpsFileListReq",
            server.takeRequest().path,
        )
    }

    @Test
    fun syncDateSendsTheFieldsTheFirmwareRequires() {
        server.enqueue(MockResponse().setBody("""{"errcode":0,"data":""}"""))

        client.syncDate("20261002070509", utcOffsetSeconds = 19800, lang = "en_IN")

        val request = server.takeRequest()
        assertEquals("/vcam/cmd.cgi?cmd=API_SyncDate", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("20261002070509", body.getString("date"))
        assertEquals("", body.getString("imei"))
        assertEquals(19800, body.getInt("time_zone"))
        assertEquals("en_IN", body.getString("lang"))
        assertFalse(body.has("format"))
    }

    private fun enqueueNested(data: String) {
        val escaped = data.replace("\\", "\\\\").replace("\"", "\\\"")
        server.enqueue(MockResponse().setBody("""{"errcode":0,"data":"$escaped"}"""))
    }
}

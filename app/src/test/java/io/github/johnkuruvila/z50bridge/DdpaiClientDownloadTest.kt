package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.camera.DdpaiClient
import io.github.johnkuruvila.z50bridge.camera.DdpaiException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DdpaiClientDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DdpaiClient
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DdpaiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            http = OkHttpClient(),
        )
        dir = Files.createTempDirectory("z50-download-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun shortResponseKeepsPartialFileForResume() {
        server.enqueue(
            MockResponse()
                .setBody("abc")
                .setHeader("Content-Length", "10"),
        )

        val dest = File(dir, "clip.mp4")
        val part = File(dir, "clip.mp4.part")

        try {
            client.download("clip.mp4", dest)
            throw AssertionError("expected the truncated response to fail")
        } catch (_: Exception) {
        }

        assertFalse("partial file must not be promoted", dest.exists())
        assertTrue("partial file must survive a dropped connection", part.exists())
        assertEquals("abc", part.readText())
    }

    @Test
    fun resumeCompletesTheFileAndPromotesIt() {
        val part = File(dir, "clip.gpx.part").apply { writeText("abc") }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-9/10")
                .setBody("defghij"),
        )

        val dest = File(dir, "clip.gpx")
        client.download("clip.gpx", dest, expectedBytes = 10)

        assertEquals("abcdefghij", dest.readText())
        assertFalse(part.exists())
        val request = server.takeRequest()
        assertEquals("bytes=3-", request.getHeader("Range"))
    }

    @Test
    fun cameraRejectionRemovesPartialFile() {
        server.enqueue(MockResponse().setBody("""{"errcode":7700003a,"errmsg":"download refused"}"""))

        val dest = File(dir, "clip.mp4")
        val part = File(dir, "clip.mp4.part")

        try {
            client.download("clip.mp4", dest)
            throw AssertionError("expected the JSON rejection to fail")
        } catch (exc: DdpaiException) {
            assertTrue(exc.message.orEmpty().contains("rejected", ignoreCase = true))
        }

        assertFalse(dest.exists())
        assertFalse("a rejected payload must not leave a partial", part.exists())
    }

    @Test
    fun mismatchedRangeFailsAndKeepsExistingPartial() {
        val part = File(dir, "clip.mp4.part").apply { writeText("abc") }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-2/10")
                .setBody("XYZ"),
        )

        try {
            client.download("clip.mp4", File(dir, "clip.mp4"))
            throw AssertionError("expected the mismatched range to fail")
        } catch (_: DdpaiException) {
        }

        assertEquals("abc", part.readText())
    }

    @Test
    fun ignoredRangeRestartsFromZeroAndSucceeds() {
        val part = File(dir, "clip.gpx.part").apply { writeText("abc") }
        server.enqueue(MockResponse().setBody("abcdefghij"))

        val dest = File(dir, "clip.gpx")
        client.download("clip.gpx", dest, expectedBytes = 10)

        assertEquals("abcdefghij", dest.readText())
        assertFalse(part.exists())
    }
}

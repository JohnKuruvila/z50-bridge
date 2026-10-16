package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.sync.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class OffloadChecksumTest {
    @Test
    fun sha256MatchesKnownDigest() {
        val file = Files.createTempFile("z50", ".mp4").toFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(file))
    }
}

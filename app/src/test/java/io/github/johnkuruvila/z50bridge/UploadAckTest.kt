package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.sync.UploadAck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The clip is deleted once an upload finishes, so these cover the rule that a
 * reply only counts when the receiver confirms it stored exactly these bytes.
 */
class UploadAckTest {

    private val sha = "a".repeat(64)
    private val otherSha = "b".repeat(64)

    private fun reply(
        status: String = "ok",
        bytes: Long? = 4096,
        sha256: String? = sha,
    ): String = buildString {
        append("""{"status":"$status"""")
        bytes?.let { append(""","bytes":$it""") }
        sha256?.let { append(""","sha256":"$it"""") }
        append("}")
    }

    @Test
    fun acceptsOkWithMatchingBytesAndChecksum() {
        val ack = UploadAck.parse(reply())
        assertNotNull(ack)
        assertNull(ack!!.refusal(expectedBytes = 4096, expectedSha256 = sha))
    }

    @Test
    fun acceptsDuplicate() {
        val ack = UploadAck.parse(reply(status = "duplicate"))
        assertNotNull(ack)
        assertNull(ack!!.refusal(expectedBytes = 4096, expectedSha256 = sha))
    }

    @Test
    fun rejectsUnconfirmedStatus() {
        val ack = UploadAck.parse(reply(status = "error"))
        assertNotNull(ack)
        assertNotNull(ack!!.refusal(expectedBytes = 4096, expectedSha256 = sha))
    }

    @Test
    fun rejectsByteCountMismatch() {
        val ack = UploadAck.parse(reply(bytes = 1024))
        assertNotNull(ack)
        assertNotNull(ack!!.refusal(expectedBytes = 4096, expectedSha256 = sha))
    }

    @Test
    fun rejectsChecksumMismatch() {
        val ack = UploadAck.parse(reply(sha256 = otherSha))
        assertNotNull(ack)
        assertNotNull(ack!!.refusal(expectedBytes = 4096, expectedSha256 = sha))
    }

    @Test
    fun rejectsMissingChecksum() {
        val ack = UploadAck.parse(reply(sha256 = null))
        assertNotNull(ack)
        assertNotNull(ack!!.refusal(expectedBytes = 4096, expectedSha256 = sha))
    }

    @Test
    fun rejectsMissingByteCount() {
        val ack = UploadAck.parse(reply(bytes = null))
        assertNotNull(ack)
        assertNotNull(ack!!.refusal(expectedBytes = 4096, expectedSha256 = sha))
    }

    @Test
    fun checksumComparisonIsCaseInsensitive() {
        val ack = UploadAck.parse(reply(sha256 = sha.uppercase()))
        assertNotNull(ack)
        assertNull(ack!!.refusal(expectedBytes = 4096, expectedSha256 = sha))
    }

    @Test
    fun rejectsBodiesThatAreNotAcknowledgements() {
        // A captive portal or a wrong server on the configured port answers 200
        // with HTML; that must never be read as "stored".
        assertNull(UploadAck.parse("<html><body>Sign in to Wi-Fi</body></html>"))
        assertNull(UploadAck.parse(""))
        assertNull(UploadAck.parse("""{"filename":"clip.mp4"}"""))
        assertEquals(null, UploadAck.parse("not json at all"))
    }
}

package io.github.johnkuruvila.z50bridge.sync

import org.json.JSONObject

/**
 * The ingest receiver's acknowledgement of a completed upload.
 *
 * The local clip is deleted as soon as an upload finishes, so a reply only
 * counts as success when the receiver confirms it stored these exact bytes.
 * Any 2xx used to be enough, which meant a wrong or misconfigured endpoint
 * could silently discard footage that was never stored anywhere.
 */
internal data class UploadAck(
    val status: String,
    val bytes: Long,
    val sha256: String?,
) {
    /**
     * Returns a human-readable reason when this acknowledgement is not proof
     * that the clip was stored, or null when it is safe to delete the local copy.
     */
    fun refusal(expectedBytes: Long, expectedSha256: String): String? = when {
        status != "ok" && status != "duplicate" ->
            "ingest server did not confirm the upload (status=\"$status\")"
        bytes != expectedBytes ->
            "ingest server acknowledged $bytes of $expectedBytes bytes"
        sha256 == null ->
            "ingest server did not report a checksum"
        !sha256.equals(expectedSha256, ignoreCase = true) ->
            "ingest server reported a different checksum"
        else -> null
    }

    companion object {
        /** Returns null when the body is not an acknowledgement at all. */
        fun parse(body: String): UploadAck? = try {
            val json = JSONObject(body)
            val status = json.optString("status")
            if (status.isEmpty()) {
                null
            } else {
                UploadAck(
                    status = status,
                    bytes = if (json.has("bytes")) json.getLong("bytes") else -1L,
                    sha256 = json.optString("sha256").takeIf { it.isNotEmpty() },
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

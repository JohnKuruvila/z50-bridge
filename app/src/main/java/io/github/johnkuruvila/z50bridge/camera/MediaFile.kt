package io.github.johnkuruvila.z50bridge.camera

data class MediaFile(
    val name: String,
    val kind: String,
    val sizeKb: Long? = null,
    val startUnix: Long? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    val filename: String get() = name.substringAfterLast('/')
    val sizeBytes: Long? get() = kbToBytes(sizeKb)

    companion object {
        fun fromPlayback(item: Map<String, Any?>, kind: String): MediaFile {
            val size = intOrNull(item["size"])
            val start = intOrNull(item["starttime"] ?: item["bstarttime"])
            val name = (item["name"] ?: item["bvideoname"] ?: "").toString()
            return MediaFile(name = name, kind = kind, sizeKb = size, startUnix = start, extra = item)
        }

        fun fromSidecar(item: Map<String, Any?>, kind: String): MediaFile {
            val size = intOrNull(item["size"])
            val start = intOrNull(item["starttime"] ?: item["bstarttime"])
            return MediaFile(
                name = (item["name"] ?: "").toString(),
                kind = kind,
                sizeKb = size,
                startUnix = start,
                extra = item,
            )
        }
    }
}

fun skipOpenClip(items: List<MediaFile>): List<MediaFile> {
    val ordered = items.sortedWith(compareBy<MediaFile> { it.startUnix ?: 0L }.thenBy { it.name })
    if (ordered.isEmpty()) return emptyList()
    return ordered.dropLast(1)
}

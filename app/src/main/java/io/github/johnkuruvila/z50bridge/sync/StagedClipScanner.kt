package io.github.johnkuruvila.z50bridge.sync

import io.github.johnkuruvila.z50bridge.StagedClip
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a staging directory into the clip list rendered by the Clips screen.
 * Z50 basenames look like ``20260915213739_0060.mp4`` (timestamp + duration).
 */
object StagedClipScanner {
    /** The filename carries no duration; the UI hides the duration tag. */
    const val UNKNOWN_DURATION = ""

    private val SIDECAR_EXTENSIONS = setOf("gpx", "git", "gsn")

    private val STAMP = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:_(\d+))?""")

    fun scan(dir: File): List<StagedClip> {
        if (!dir.isDirectory) return emptyList()
        val mp4Files = dir.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        }?.sortedByDescending { it.name } ?: return emptyList()
        val sidecars = sidecarRanges(dir)
        return mp4Files.map { describe(dir, it, sidecars) }
    }

    /** A time span in epoch seconds, taken from a `start_duration` basename. */
    internal data class Span(val start: Long, val end: Long) {
        fun overlaps(other: Span) = start < other.end && end > other.start
    }

    /**
     * Sidecars are not per clip: one `.git`/`.gpx`/`.gsn` covers a whole drive
     * segment (e.g. `20260915121942_3315.gsn` runs 55 minutes) while clips are
     * one-minute files. Both are named `yyyyMMddHHmmss_<seconds>`, so a clip
     * has GPS or G-sensor data when its span overlaps a sidecar's span.
     */
    private fun sidecarRanges(dir: File): Map<String, List<Span>> =
        dir.listFiles { file -> file.isFile && file.extension.lowercase(Locale.US) in SIDECAR_EXTENSIONS }
            .orEmpty()
            .mapNotNull { file -> spanOf(file.name)?.let { file.extension.lowercase(Locale.US) to it } }
            .groupBy({ it.first }, { it.second })

    internal fun spanOf(name: String): Span? {
        val match = STAMP.find(name) ?: return null
        val seconds = match.groupValues[7].toLongOrNull() ?: return null
        val start = runCatching {
            LocalDateTime.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].toInt(),
                match.groupValues[5].toInt(),
                match.groupValues[6].toInt(),
            ).toEpochSecond(ZoneOffset.UTC)
        }.getOrNull() ?: return null
        return Span(start, start + seconds.coerceAtLeast(1))
    }

    private fun describe(dir: File, file: File, sidecars: Map<String, List<Span>>): StagedClip {
        val match = STAMP.find(file.name)
        val dateFormatted: String
        val timeFormatted: String
        val durationFormatted: String
        if (match != null) {
            val year = match.groupValues[1]
            val month = (match.groupValues[2].toIntOrNull() ?: 1).coerceIn(1, 12)
            val day = match.groupValues[3].toIntOrNull() ?: 1
            val durationSeconds = match.groupValues[7].toIntOrNull()
            dateFormatted = runCatching {
                LocalDate.of(year.toInt(), month, day)
                    .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
            }.getOrElse { "$day/$month/$year" }
            timeFormatted = "${match.groupValues[4]}:${match.groupValues[5]}"
            durationFormatted = durationSeconds?.let {
                "${it / 60}:${(it % 60).toString().padStart(2, '0')}"
            } ?: UNKNOWN_DURATION
        } else {
            val modified = Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault())
            dateFormatted = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()).format(modified)
            timeFormatted = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(modified)
            durationFormatted = UNKNOWN_DURATION
        }

        val baseStem = file.nameWithoutExtension
        val span = spanOf(file.name)
        fun covered(vararg extensions: String): Boolean =
            extensions.any { ext -> File(dir, "$baseStem.$ext").exists() } ||
                (span != null && extensions.any { ext -> sidecars[ext].orEmpty().any { it.overlaps(span) } })
        return StagedClip(
            file = file,
            filename = file.name,
            sizeBytes = file.length(),
            dateFormatted = dateFormatted,
            timeFormatted = timeFormatted,
            isRear = file.name.contains("rear", ignoreCase = true) ||
                file.name.contains("_b", ignoreCase = true),
            hasGps = covered("gpx", "git"),
            hasGsn = covered("gsn"),
            durationFormatted = durationFormatted,
            thumbFile = File(dir, "${baseStem}_T.jpg").takeIf { it.isFile },
        )
    }
}

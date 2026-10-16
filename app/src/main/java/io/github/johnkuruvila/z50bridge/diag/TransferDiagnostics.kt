package io.github.johnkuruvila.z50bridge.diag

import io.github.johnkuruvila.z50bridge.camera.flag
import io.github.johnkuruvila.z50bridge.camera.intOrNull
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One reading of the phone's link to the camera AP. Every field is what
 * Android reported; null means it was not available, never a guess.
 */
data class WifiLinkSample(
    val atMs: Long,
    val frequencyMhz: Int? = null,
    val rssiDbm: Int? = null,
    val rxLinkMbps: Int? = null,
    val txLinkMbps: Int? = null,
    val maxRxLinkMbps: Int? = null,
    val standard: String? = null,
    /** Bytes the session had received when this sample was taken. */
    val bytesSoFar: Long = 0,
)

/**
 * One reading of the camera taken while a transfer runs. Null means the
 * camera did not answer or sent a value the app does not recognise.
 */
data class CameraSample(
    val atMs: Long,
    /** API_EquipGetTemp `temp`, as sent; the firmware does not state units. */
    val socTemp: Long? = null,
    val gpsFix: Boolean? = null,
    val recording: Boolean? = null,
)

/** Builds a sample from API_StateReport and API_EquipGetTemp payloads; either may be empty. */
fun parseCameraSample(state: Map<String, Any?>, chipTemp: Map<String, Any?>, atMs: Long): CameraSample = CameraSample(
    atMs = atMs,
    socTemp = intOrNull(chipTemp["temp"]),
    gpsFix = flag(state["gps"]),
    recording = flag(state["rec"]),
)

/** 802.11 channel number for a centre frequency, or null outside the Wi-Fi bands. */
fun wifiChannel(frequencyMhz: Int): Int? = when (frequencyMhz) {
    2484 -> 14
    in 2412..2472 -> (frequencyMhz - 2407) / 5
    in 5160..5885 -> (frequencyMhz - 5000) / 5
    in 5955..7115 -> (frequencyMhz - 5950) / 5
    else -> null
}

fun wifiBand(frequencyMhz: Int): String? = when (frequencyMhz) {
    in 2400..2500 -> "2.4 GHz"
    in 4900..5900 -> "5 GHz"
    in 5925..7125 -> "6 GHz"
    else -> null
}

fun describeLink(sample: WifiLinkSample?): String {
    if (sample == null) return "unknown"
    val parts = mutableListOf<String>()
    sample.frequencyMhz?.let { mhz ->
        val band = wifiBand(mhz) ?: "$mhz MHz"
        val channel = wifiChannel(mhz)?.let { " ch $it" }.orEmpty()
        parts += "$band$channel ($mhz MHz)"
    }
    sample.standard?.let { parts += it }
    sample.rssiDbm?.let { parts += "RSSI $it dBm" }
    if (sample.rxLinkMbps != null || sample.txLinkMbps != null) {
        parts += "link rx ${sample.rxLinkMbps ?: "?"} / tx ${sample.txLinkMbps ?: "?"} Mbps"
    }
    sample.maxRxLinkMbps?.let { parts += "max rx $it Mbps" }
    return parts.joinToString(" · ").ifEmpty { "unknown" }
}

/**
 * Where one HTTP transfer spent its time. Written by the downloading thread,
 * read by the sampler, so fields are volatile; a slightly stale read is fine.
 */
class DownloadTiming {
    @Volatile var startedAtNs = 0L
    @Volatile var headersAtNs = 0L
    @Volatile var finishedAtNs = 0L
    @Volatile var resumedFrom = 0L
    @Volatile var bytes = 0L
    @Volatile var readWaitNs = 0L
    @Volatile var writeNs = 0L
    @Volatile var longestReadNs = 0L
    @Volatile var reads = 0L

    fun begin() {
        startedAtNs = System.nanoTime()
    }

    fun headers() {
        headersAtNs = System.nanoTime()
    }

    fun read(waitNs: Long, count: Int) {
        readWaitNs += waitNs
        if (waitNs > longestReadNs) longestReadNs = waitNs
        bytes += count
        reads += 1
    }

    fun wrote(ns: Long) {
        writeNs += ns
    }

    fun end() {
        if (finishedAtNs == 0L) finishedAtNs = System.nanoTime()
    }

    val elapsedNs: Long
        get() = (if (finishedAtNs != 0L) finishedAtNs else System.nanoTime()) - startedAtNs
}

data class FileRecord(
    val name: String,
    val kind: String,
    val sizeBytes: Long?,
    val timing: DownloadTiming,
    val streamsAtStart: Int,
    val error: String? = null,
)

/**
 * Collects everything about one sync or speed test: context notes, per-file
 * timings and periodic Wi-Fi samples, then renders a plain-text report.
 */
class TransferRecorder(val title: String, val startedAtMs: Long = System.currentTimeMillis()) {
    private val notes = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val timings = CopyOnWriteArrayList<DownloadTiming>()
    private val files = Collections.synchronizedList(mutableListOf<FileRecord>())
    private val samples = Collections.synchronizedList(mutableListOf<WifiLinkSample>())
    private val phases = Collections.synchronizedList(mutableListOf<PhaseResult>())
    private val cameraSamples = Collections.synchronizedList(mutableListOf<CameraSample>())
    private val radioChanges = Collections.synchronizedList(mutableListOf<Pair<Long, RadioState>>())

    /**
     * [bytes] and [elapsedMs] cover only the measured window; the first
     * [warmupMs] of the phase, while the stream ramps up, is not counted.
     */
    data class PhaseResult(
        val label: String,
        val bytes: Long,
        val elapsedMs: Long,
        val link: WifiLinkSample?,
        val warmupMs: Long = 0,
    ) {
        val bytesPerSecond: Double get() = if (elapsedMs > 0) bytes * 1000.0 / elapsedMs else 0.0
    }

    fun note(key: String, value: Any?) {
        notes += key to (value?.toString() ?: "unknown")
    }

    fun track(timing: DownloadTiming): DownloadTiming = timing.also { timings += it }

    fun bytesSoFar(): Long = timings.sumOf { it.bytes }

    fun file(record: FileRecord) {
        files += record
    }

    fun hasFiles(): Boolean = files.isNotEmpty()

    fun fileCount(): Int = files.size

    fun failedFiles(): Int = synchronized(files) { files.count { it.error != null } }

    fun sample(sample: WifiLinkSample) {
        samples += sample.copy(bytesSoFar = bytesSoFar())
    }

    fun cameraSample(sample: CameraSample) {
        cameraSamples += sample
    }

    /** Keeps [state] only when it differs from the last one, so the report lists changes. */
    fun radios(state: RadioState, atMs: Long = System.currentTimeMillis()) {
        synchronized(radioChanges) {
            if (radioChanges.lastOrNull()?.second != state) radioChanges += atMs to state
        }
    }

    fun firstRadios(): RadioState? = synchronized(radioChanges) { radioChanges.firstOrNull()?.second }

    fun radiosChanged(): Boolean = synchronized(radioChanges) { radioChanges.size > 1 }

    fun phase(result: PhaseResult) {
        phases += result
    }

    fun phaseResults(): List<PhaseResult> = synchronized(phases) { phases.toList() }

    fun lastSample(): WifiLinkSample? = synchronized(samples) { samples.lastOrNull() }

    /** One line for Activity: speed, streams and the link it ran over. */
    fun summary(nowMs: Long = System.currentTimeMillis()): String {
        val bytes = bytesSoFar()
        val wallMs = (nowMs - startedAtMs).coerceAtLeast(1)
        val snapshot = synchronized(samples) { samples.toList() }
        val link = snapshot.lastOrNull { it.frequencyMhz != null } ?: snapshot.lastOrNull()
        // Failed files are reported in Activity on their own. The radio
        // details stay in the report; Activity gets the signal strength only.
        val count = fileCount() - failedFiles()
        val fileNote = if (count > 0) "$count file${if (count == 1) "" else "s"}, " else ""
        val signal = link?.rssiDbm?.let { " · signal $it dBm" } ?: ""
        return "$title: $fileNote${formatBytes(bytes)} at ${mbps(bytes, wallMs)}$signal"
    }

    fun report(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(zone)
        val wallMs = (nowMs - startedAtMs).coerceAtLeast(1)
        val bytes = bytesSoFar()
        val fileList = synchronized(files) { files.toList() }
        val sampleList = synchronized(samples) { samples.toList() }
        val phaseList = phaseResults()
        return buildString {
            appendLine("=== $title · ${time.format(Instant.ofEpochMilli(startedAtMs))} · ${duration(wallMs)} ===")
            val ok = fileList.count { it.error == null }
            val failed = fileList.size - ok
            appendLine("Result: ${formatBytes(bytes)} in ${duration(wallMs)} = ${mbps(bytes, wallMs)} overall" +
                if (fileList.isNotEmpty()) " · $ok files ok, $failed failed" else "")
            synchronized(notes) { notes.toList() }.forEach { (key, value) -> appendLine("$key: $value") }

            if (phaseList.isNotEmpty()) {
                appendLine("Phases:")
                phaseList.forEach { p ->
                    val warmup = if (p.warmupMs > 0) " (first ${duration(p.warmupMs)} not counted)" else ""
                    appendLine("  ${p.label}: ${formatBytes(p.bytes)} in ${duration(p.elapsedMs)} = ${mbps(p.bytes, p.elapsedMs)}$warmup · ${describeLink(p.link)}")
                }
            }

            if (sampleList.isNotEmpty()) {
                val rssi = sampleList.mapNotNull { it.rssiDbm }
                val rx = sampleList.mapNotNull { it.rxLinkMbps }
                val tx = sampleList.mapNotNull { it.txLinkMbps }
                appendLine("Wi-Fi at start: ${describeLink(sampleList.first())}")
                if (rssi.isNotEmpty()) appendLine("RSSI dBm: min ${rssi.min()}, avg ${rssi.average().toInt()}, max ${rssi.max()}")
                if (rx.isNotEmpty()) appendLine("Rx link Mbps: min ${rx.min()}, avg ${rx.average().toInt()}, max ${rx.max()}")
                if (tx.isNotEmpty()) appendLine("Tx link Mbps: min ${tx.min()}, avg ${tx.average().toInt()}, max ${tx.max()}")
            }

            val radioList = synchronized(radioChanges) { radioChanges.toList() }
            radioList.firstOrNull()?.let { (_, state) -> appendLine("Phone radios at start: ${describeRadios(state)}") }
            if (radioList.size > 1) {
                appendLine("Phone radio changes (s · state):")
                radioList.drop(1).forEach { (atMs, state) ->
                    appendLine("  ${(atMs - startedAtMs) / 1000} · ${describeRadios(state)}")
                }
            }

            val cameraList = synchronized(cameraSamples) { cameraSamples.toList() }
            if (cameraList.isNotEmpty()) {
                val temps = cameraList.mapNotNull { it.socTemp }
                if (temps.isNotEmpty()) {
                    appendLine("Camera SoC temperature during transfer: first ${temps.first()}, min ${temps.min()}, max ${temps.max()}, last ${temps.last()}")
                }
                appendLine("Camera during transfer (s · SoC temp · GPS fix · recording):")
                cameraList.forEach { c ->
                    appendLine("  ${(c.atMs - startedAtMs) / 1000} · ${c.socTemp ?: "-"} · ${yesNo(c.gpsFix)} · ${yesNo(c.recording)}")
                }
            }

            if (fileList.isNotEmpty()) {
                val netWait = fileList.sumOf { it.timing.readWaitNs }
                val disk = fileList.sumOf { it.timing.writeNs }
                appendLine(
                    "Time split across files: waiting on network ${percent(netWait, netWait + disk)}, " +
                        "writing to storage ${percent(disk, netWait + disk)}",
                )
                appendLine("Files (name · kind · bytes · time · rate · first byte · net wait · disk · longest read · streams):")
                fileList.forEach { f ->
                    val t = f.timing
                    val elapsedMs = t.elapsedNs / 1_000_000
                    val ttfb = if (t.headersAtNs > 0) "${(t.headersAtNs - t.startedAtNs) / 1_000_000} ms" else "-"
                    val resumed = if (t.resumedFrom > 0) " (resumed at ${formatBytes(t.resumedFrom)})" else ""
                    appendLine(
                        "  ${f.name} · ${f.kind} · ${formatBytes(t.bytes)}$resumed · ${duration(elapsedMs)} · " +
                            "${mbps(t.bytes, elapsedMs)} · $ttfb · ${t.readWaitNs / 1_000_000} ms · " +
                            "${t.writeNs / 1_000_000} ms · ${t.longestReadNs / 1_000_000} ms · ${f.streamsAtStart}" +
                            (f.error?.let { " · FAILED: $it" } ?: ""),
                    )
                }
            }

            if (sampleList.size > 1) {
                appendLine("Timeline (s · MB/s since last sample · RSSI dBm · rx/tx Mbps · MHz):")
                val rows = sampleList.zipWithNext()
                val step = (rows.size / MAX_TIMELINE_ROWS) + 1
                rows.forEachIndexed { index, (prev, cur) ->
                    if (index % step != 0) return@forEachIndexed
                    val dtMs = (cur.atMs - prev.atMs).coerceAtLeast(1)
                    val rate = (cur.bytesSoFar - prev.bytesSoFar) * 1000.0 / dtMs / MB
                    appendLine(
                        "  ${(cur.atMs - startedAtMs) / 1000} · ${"%.2f".format(Locale.US, rate)} · " +
                            "${cur.rssiDbm ?: "-"} · ${cur.rxLinkMbps ?: "-"}/${cur.txLinkMbps ?: "-"} · ${cur.frequencyMhz ?: "-"}",
                    )
                }
            }
        }
    }

    private companion object {
        const val MB = 1024.0 * 1024.0
        const val MAX_TIMELINE_ROWS = 180
    }
}

internal fun mbps(bytes: Long, ms: Long): String =
    if (ms <= 0) "-" else "%.2f MB/s".format(Locale.US, bytes * 1000.0 / ms / (1024.0 * 1024.0))

private fun yesNo(value: Boolean?): String = when (value) {
    true -> "yes"
    false -> "no"
    null -> "-"
}

private fun percent(part: Long, whole: Long): String =
    if (whole <= 0) "-" else "${(part * 100 / whole)}%"

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 60 -> "${s / 60}m${"%02d".format(Locale.US, s % 60)}s"
        s >= 10 -> "${s}s"
        else -> "%.1fs".format(Locale.US, ms / 1000.0)
    }
}

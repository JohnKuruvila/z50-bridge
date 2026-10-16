package io.github.johnkuruvila.z50bridge.sync

/**
 * Samples cumulative transfer bytes and derives throughput and ETA.
 *
 * Both [SyncEngine] and [OffloadEngine] feed every progress callback into one
 * instance, so speed/ETA math lives in one place and is unit-testable.
 */
class TransferMeter(
    private val intervalMs: Long = 800L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var initialized = false
    private var lastSampleMs = 0L
    private var lastSampleBytes = 0L
    private var speed = 0L

    val bytesPerSecond: Long get() = speed

    fun sample(overallBytes: Long): Long {
        val now = clock()
        if (!initialized) {
            initialized = true
            lastSampleMs = now
            lastSampleBytes = overallBytes
            return speed
        }
        val elapsedMs = now - lastSampleMs
        if (elapsedMs < intervalMs) return speed
        val deltaBytes = overallBytes - lastSampleBytes
        if (deltaBytes > 0 && elapsedMs > 0) {
            speed = deltaBytes * 1000 / elapsedMs
        }
        lastSampleMs = now
        lastSampleBytes = overallBytes
        return speed
    }

    fun etaSeconds(copied: Long, total: Long?): Long? {
        if (speed <= 0 || total == null || total <= copied) return null
        return (total - copied) / speed
    }
}

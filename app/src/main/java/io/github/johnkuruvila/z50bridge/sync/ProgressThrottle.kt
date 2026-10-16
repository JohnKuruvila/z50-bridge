package io.github.johnkuruvila.z50bridge.sync

class ProgressThrottle(
    private val intervalMs: Long = 200L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var lastName = ""
    private var lastMs = 0L

    fun shouldPublish(name: String, copied: Long, total: Long?): Boolean {
        val now = clock()
        val finished = total != null && copied >= total
        if (name != lastName || finished || lastMs == 0L || now - lastMs >= intervalMs) {
            lastName = name
            lastMs = now
            return true
        }
        return false
    }
}

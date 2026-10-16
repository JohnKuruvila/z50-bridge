package io.github.johnkuruvila.z50bridge.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val POOL_CHECK_MS = 500L

/**
 * Runs [work] on as many workers as [target] asks for, re-reading it while
 * the transfer runs so a stream-count change in Settings applies right away.
 * Extra streams start within [checkMs]. A worker over a lowered target
 * finishes its current file, then exits, so no transferred bytes are thrown
 * away. [next] returns null when the queue is empty; [hasWork] lets the pool
 * start workers again if items are added later (a camera re-list).
 */
internal suspend fun <T : Any> runWorkerPool(
    target: () -> Int,
    next: () -> T?,
    hasWork: () -> Boolean,
    stopped: () -> Boolean = { false },
    onResize: (Int) -> Unit = {},
    checkMs: Long = POOL_CHECK_MS,
    work: suspend (T) -> Unit,
) = coroutineScope {
    val active = AtomicInteger(0)

    fun spawn() {
        active.incrementAndGet()
        launch(Dispatchers.IO) {
            var counted = true
            try {
                while (isActive && !stopped()) {
                    val now = active.get()
                    if (now > target() && active.compareAndSet(now, now - 1)) {
                        counted = false
                        break
                    }
                    val item = next() ?: break
                    work(item)
                }
            } finally {
                if (counted) active.decrementAndGet()
            }
        }
    }

    var lastTarget = target()
    while (isActive) {
        val want = target()
        if (want != lastTarget) {
            lastTarget = want
            onResize(want)
        }
        while (!stopped() && active.get() < want && hasWork()) spawn()
        if (active.get() == 0 && (stopped() || !hasWork())) break
        delay(checkMs)
    }
}

package io.github.johnkuruvila.z50bridge.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pause for one transfer session. Workers call [awaitOpen] between chunks, so a
 * pause freezes the bytes where they are: an upload keeps its connection and
 * carries on from the same byte, a download keeps its `.part` file.
 *
 * The workers run blocking OkHttp and file I/O, so this blocks the thread rather
 * than suspending, and it polls [isActive] because a blocked thread cannot see
 * coroutine cancellation. That is what lets Stop end a paused transfer.
 */
class TransferGate(private val pollMs: Long = 200L) {
    private val lock = Object()
    private var paused = false
    private var pauses = 0

    val isPaused: Boolean get() = synchronized(lock) { paused }

    /** Changes on every pause. An attempt that saw it change failed because of the pause. */
    val generation: Int get() = synchronized(lock) { pauses }

    /** False if the gate was already paused. */
    fun pause(): Boolean = synchronized(lock) {
        if (paused) return false
        paused = true
        pauses += 1
        true
    }

    /** False if the gate was already open. */
    fun resume(): Boolean = synchronized(lock) {
        if (!paused) return false
        paused = false
        lock.notifyAll()
        true
    }

    /** Returns once the gate is open; throws [CancellationException] if the transfer was stopped meanwhile. */
    fun awaitOpen(isActive: () -> Boolean = { true }) {
        synchronized(lock) {
            while (paused) {
                if (!isActive()) throw CancellationException("Transfer stopped")
                lock.wait(pollMs)
            }
        }
        if (!isActive()) throw CancellationException("Transfer stopped")
    }
}

/**
 * Runs one file's transfer, repeating it if an attempt failed because of a pause.
 * A long pause can outlive the connection (the camera or the network drops an
 * idle socket); that is not the file's fault, so it costs neither a failure nor
 * the data. The retry waits for Resume, and a second failure with no new pause
 * in between is a real one and reaches the caller.
 */
internal inline fun <T> retryAfterPause(gate: TransferGate, noinline isActive: () -> Boolean, attempt: () -> T): T {
    while (true) {
        gate.awaitOpen(isActive)
        val generation = gate.generation
        try {
            return attempt()
        } catch (exc: Exception) {
            if (exc is CancellationException || gate.generation == generation) throw exc
        }
    }
}

/**
 * Keeps aborting requests until a cancelled session has unwound. Blocking OkHttp
 * calls ignore coroutine cancellation, and a worker may open a call just after an
 * abort. This waits on isCompleted because a cancelled job's isActive is already
 * false while its workers are still blocked. Waiting on isActive never aborted
 * anything, so Stop used to wait for every file in flight to finish.
 */
internal suspend fun abortUntilUnwound(
    job: Job,
    windowMs: Long,
    intervalMs: Long,
    ioDone: () -> Boolean,
    abort: () -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) {
    val deadline = clock() + windowMs
    while (!job.isCompleted && !ioDone() && clock() < deadline) {
        abort()
        delay(intervalMs)
    }
}

/**
 * Like withTimeoutOrNull, but time spent paused does not count, so leaving a
 * transfer paused overnight does not end it with a timeout on Resume.
 */
internal suspend fun <T> withRunningTimeoutOrNull(
    timeoutMs: Long,
    gate: TransferGate,
    tickMs: Long = 1_000L,
    block: suspend () -> T,
): T? = coroutineScope {
    val timedOut = CompletableDeferred<Unit>()
    val work = async(start = CoroutineStart.UNDISPATCHED) { block() }
    val watchdog = launch {
        var left = timeoutMs
        while (left > 0) {
            delay(tickMs)
            if (!gate.isPaused) left -= tickMs
        }
        timedOut.complete(Unit)
        work.cancel(CancellationException("Timed out"))
    }
    try {
        work.await()
    } catch (exc: CancellationException) {
        if (!timedOut.isCompleted) throw exc
        null
    } finally {
        watchdog.cancel()
    }
}

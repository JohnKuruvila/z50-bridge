package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.sync.TransferGate
import io.github.johnkuruvila.z50bridge.sync.abortUntilUnwound
import io.github.johnkuruvila.z50bridge.sync.retryAfterPause
import io.github.johnkuruvila.z50bridge.sync.withRunningTimeoutOrNull
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferGateTest {
    @Test
    fun openGateDoesNotBlock() {
        TransferGate().awaitOpen()
    }

    @Test
    fun pausedGateHoldsTheThreadUntilResume() {
        val gate = TransferGate(pollMs = 10)
        gate.pause()
        val passed = CountDownLatch(1)
        thread {
            gate.awaitOpen()
            passed.countDown()
        }
        assertFalse(passed.await(150, TimeUnit.MILLISECONDS))
        gate.resume()
        assertTrue(passed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun stopReleasesAThreadHeldByAPause() {
        val gate = TransferGate(pollMs = 10)
        gate.pause()
        val active = AtomicBoolean(true)
        val thrown = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        thread {
            try {
                gate.awaitOpen { active.get() }
            } catch (exc: Throwable) {
                thrown.set(exc)
            }
            finished.countDown()
        }
        assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
        active.set(false)
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(thrown.get() is CancellationException)
    }

    @Test
    fun repeatedPressesCountOnce() {
        val gate = TransferGate()
        assertFalse(gate.resume())
        assertTrue(gate.pause())
        assertFalse(gate.pause())
        assertEquals(1, gate.generation)
        assertTrue(gate.resume())
        assertFalse(gate.isPaused)
    }

    @Test
    fun attemptBrokenByAPauseIsRetried() {
        val gate = TransferGate(pollMs = 10)
        val attempts = AtomicInteger(0)
        val result = retryAfterPause(gate, { true }) {
            if (attempts.incrementAndGet() == 1) {
                gate.pause()
                gate.resume()
                throw IOException("socket dropped during the pause")
            }
            "done"
        }
        assertEquals("done", result)
        assertEquals(2, attempts.get())
    }

    @Test(expected = IOException::class)
    fun failureWithoutAPauseIsNotRetried() {
        retryAfterPause(TransferGate(), { true }) { throw IOException("real failure") }
    }

    @Test
    fun aSecondFailureAfterThePauseIsReal() {
        val gate = TransferGate(pollMs = 10)
        val attempts = AtomicInteger(0)
        var failure: IOException? = null
        try {
            retryAfterPause(gate, { true }) {
                if (attempts.incrementAndGet() == 1) {
                    gate.pause()
                    gate.resume()
                }
                throw IOException("still failing")
            }
        } catch (exc: IOException) {
            failure = exc
        }
        assertEquals("still failing", failure?.message)
        assertEquals(2, attempts.get())
    }

    @Test(expected = CancellationException::class)
    fun stopIsNeverRetried() {
        val gate = TransferGate()
        retryAfterPause(gate, { true }) {
            gate.pause()
            gate.resume()
            throw CancellationException("Transfer stopped")
        }
    }

    @Test
    fun timeoutEndsARunningTransfer() = runBlocking {
        val result = withRunningTimeoutOrNull(30, TransferGate(), tickMs = 5) {
            delay(5_000)
            "late"
        }
        assertNull(result)
    }

    @Test
    fun timeSpentPausedDoesNotCount() = runBlocking {
        val gate = TransferGate()
        gate.pause()
        val result = withRunningTimeoutOrNull(30, gate, tickMs = 5) {
            delay(150)
            "finished"
        }
        assertEquals("finished", result)
    }

    @Test
    fun cancellingTheCallerIsNotReportedAsATimeout() = runBlocking {
        val seen = AtomicReference<Throwable?>()
        var job: Job? = null
        job = launch(Dispatchers.Default) {
            try {
                withRunningTimeoutOrNull(60_000, TransferGate()) { delay(60_000) }
            } catch (exc: Throwable) {
                seen.set(exc)
                throw exc
            }
        }
        delay(50)
        job.cancel()
        job.join()
        assertTrue(seen.get() is CancellationException)
    }

    /** The Stop bug: a cancelled job reports !isActive while its blocked worker still runs. */
    @Test
    fun stopKeepsAbortingUntilTheBlockedWorkerHasUnwound() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            started.countDown()
            release.await()
        }
        started.await()
        job.cancel()
        assertFalse("the premise: isActive is already false", job.isActive)
        assertFalse("the premise: the worker is still running", job.isCompleted)

        val aborts = AtomicInteger(0)
        abortUntilUnwound(job, windowMs = 5_000, intervalMs = 5, ioDone = { false }, abort = {
            if (aborts.incrementAndGet() == 3) release.countDown()
        })

        assertTrue("every abort before the worker ended", aborts.get() >= 3)
        assertTrue(job.isCompleted)
    }

    @Test
    fun stopLeavesTheCameraCleanUpAlone() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            started.countDown()
            release.await()
        }
        started.await()
        job.cancel()
        val aborts = AtomicInteger(0)
        abortUntilUnwound(job, windowMs = 5_000, intervalMs = 5, ioDone = { true }, abort = { aborts.incrementAndGet() })
        assertEquals(0, aborts.get())
        release.countDown()
    }

    @Test
    fun stopGivesUpAfterItsWindow() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            started.countDown()
            release.await()
        }
        started.await()
        job.cancel()
        abortUntilUnwound(job, windowMs = 30, intervalMs = 5, ioDone = { false }, abort = {})
        assertFalse(job.isCompleted)
        release.countDown()
    }
}

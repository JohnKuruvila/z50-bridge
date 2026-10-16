package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.net.CameraAutoConnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAutoConnectTest {
    private fun policy() = CameraAutoConnect(
        graceMs = 20_000L,
        retryMs = 5_000L,
        maxTries = 3,
        backoffMs = 300_000L,
        lostCooldownMs = 60_000L,
    )

    private fun CameraAutoConnect.ask(nowMs: Long, seen: Boolean = true, reachable: Boolean = false, held: Boolean = false) =
        shouldRequest(nowMs, cameraSeen = seen, cameraReachable = reachable, requestHeld = held)

    @Test
    fun neverRequestsWhileTheCameraIsNotInScans() {
        val p = policy()
        assertFalse(p.ask(0, seen = false))
        assertFalse(p.ask(60_000, seen = false))
    }

    @Test
    fun givesTheSuggestionAGracePeriodFirst() {
        val p = policy()
        assertFalse(p.ask(0))
        assertFalse(p.ask(19_999))
        assertTrue(p.ask(20_000))
        assertEquals(1, p.attempt)
    }

    @Test
    fun graceRestartsWhenTheCameraDropsOutOfScans() {
        val p = policy()
        assertFalse(p.ask(0))
        assertFalse(p.ask(15_000, seen = false))
        assertFalse(p.ask(20_000))
        assertTrue(p.ask(40_000))
    }

    @Test
    fun noRequestWhenAlreadyJoinedOrHeld() {
        val p = policy()
        assertFalse(p.ask(0))
        assertFalse(p.ask(30_000, reachable = true))
        assertFalse(p.ask(30_000, held = true))
    }

    @Test
    fun retriesQuicklyThenBacksOffAfterThreeFailures() {
        val p = policy()
        p.ask(0)
        assertTrue(p.ask(20_000))
        assertEquals(5_000L, p.onFailed(40_000))
        assertFalse(p.ask(44_999))
        assertTrue(p.ask(45_000))
        assertEquals(2, p.attempt)
        assertEquals(5_000L, p.onFailed(65_000))
        assertTrue(p.ask(70_000))
        assertEquals(3, p.attempt)
        assertEquals(300_000L, p.onFailed(90_000))
        assertFalse(p.ask(389_999))
        assertTrue(p.ask(390_000))
        assertEquals(1, p.attempt)
    }

    @Test
    fun successResetsTheTries() {
        val p = policy()
        p.ask(0)
        p.ask(20_000)
        p.onFailed(40_000)
        p.ask(45_000)
        p.onConnected()
        // The connection later drops while the camera is still in range.
        assertTrue(p.ask(100_000))
        assertEquals(1, p.attempt)
    }

    @Test
    fun afterTheCameraIsLostItMustBeSeenAgainAndCoolDown() {
        val p = policy()
        p.ask(0)
        p.ask(20_000)
        p.onConnected()
        p.onLost(1_000_000)
        // Cached scan results still list it; the grace period starts over.
        assertFalse(p.ask(1_000_000))
        assertFalse(p.ask(1_030_000))
        assertTrue(p.ask(1_060_000))
    }
}

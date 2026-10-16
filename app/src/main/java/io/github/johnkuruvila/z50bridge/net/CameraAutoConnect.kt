package io.github.johnkuruvila.z50bridge.net

/**
 * Decides when the watcher makes the same direct request as the "Connect to
 * camera directly" button. The suggestion alone is flaky: Android stops
 * auto-joining a network that never validates, and the phone keeps trying the
 * car head unit's Wi-Fi instead.
 *
 * A request is only made while the camera shows up in fresh scan results, so
 * Android finds the approved access point at once and connects without a
 * dialog. The suggestion gets [graceMs] to join on its own first. A failed
 * try is retried after [retryMs]; after [maxTries] in a row the policy waits
 * [backoffMs], so it never fights another network in a tight loop.
 */
class CameraAutoConnect(
    private val graceMs: Long = 20_000L,
    private val retryMs: Long = 5_000L,
    private val maxTries: Int = 3,
    private val backoffMs: Long = 5 * 60_000L,
    private val lostCooldownMs: Long = 60_000L,
) {
    private var seenSinceMs: Long? = null
    private var tries = 0
    private var nextAttemptMs = 0L

    /** The try number of the request [shouldRequest] last allowed, from 1. */
    val attempt: Int
        get() = tries

    val maxAttempts: Int
        get() = maxTries

    /**
     * True when a request should be made now. [cameraSeen] is whether the
     * camera SSID is in a fresh scan; [requestHeld] whether a direct request
     * (automatic or from the button) is already in place.
     */
    fun shouldRequest(nowMs: Long, cameraSeen: Boolean, cameraReachable: Boolean, requestHeld: Boolean): Boolean {
        if (!cameraSeen) {
            seenSinceMs = null
            return false
        }
        val since = seenSinceMs ?: nowMs.also { seenSinceMs = it }
        if (cameraReachable || requestHeld) {
            // Joined somehow; the next drive starts with a fresh set of tries.
            if (cameraReachable) tries = 0
            return false
        }
        if (nowMs - since < graceMs || nowMs < nextAttemptMs) return false
        tries += 1
        return true
    }

    fun onConnected() {
        tries = 0
        nextAttemptMs = 0L
    }

    /** Records a failed try and returns how long until the next one. */
    fun onFailed(nowMs: Long): Long {
        val wait = if (tries >= maxTries) {
            tries = 0
            backoffMs
        } else {
            retryMs
        }
        nextAttemptMs = nowMs + wait
        return wait
    }

    /**
     * The camera went away (usually the ignition). Its SSID stays in cached
     * scan results for a while, so it has to be seen again in a newer scan,
     * and not before [lostCooldownMs].
     */
    fun onLost(nowMs: Long) {
        seenSinceMs = null
        tries = 0
        nextAttemptMs = nowMs + lostCooldownMs
    }
}

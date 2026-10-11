package com.aurix.agent.core.ai.routing

enum class BreakerState { CLOSED, OPEN, HALF_OPEN }

/**
 * Provider-level circuit breaker. CLOSED: normal. After [failureThreshold] consecutive provider failures it goes OPEN
 * (calls are skipped) for [openMs]; then HALF_OPEN lets exactly one probe call through: success closes it, failure re-opens it.
 * Only provider health counts here (network, timeout, 5xx); key problems (auth, rate limits) are handled by the key pool.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openMs: Long = 30_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var state = BreakerState.CLOSED
    private var failures = 0
    private var openUntil = 0L
    private var probeInFlight = false

    @Synchronized fun state(): BreakerState {
        if (state == BreakerState.OPEN && clock() >= openUntil) { state = BreakerState.HALF_OPEN; probeInFlight = false }
        return state
    }

    /** True if a call may be made now (reserves the single probe slot when HALF_OPEN). */
    @Synchronized fun tryAcquire(): Boolean = when (state()) {
        BreakerState.CLOSED -> true
        BreakerState.OPEN -> false
        BreakerState.HALF_OPEN -> if (probeInFlight) false else { probeInFlight = true; true }
    }

    @Synchronized fun onSuccess() { failures = 0; state = BreakerState.CLOSED; probeInFlight = false }

    @Synchronized fun onFailure() {
        probeInFlight = false
        failures++
        if (state == BreakerState.HALF_OPEN || failures >= failureThreshold) { state = BreakerState.OPEN; openUntil = clock() + openMs }
    }

    /** The call ended in something that says nothing about provider health (auth, rate limit, invalid request): release a probe slot. */
    @Synchronized fun onNeutral() { probeInFlight = false }

    @Synchronized fun retryInMs(): Long = if (state() == BreakerState.OPEN) (openUntil - clock()).coerceAtLeast(0) else 0
}

/** Exponential backoff with jitter; a server-provided Retry-After always wins (capped). */
object Backoff {
    fun delayMs(attempt: Int, retryAfterMs: Long? = null, baseMs: Long = 1_000, capMs: Long = 30_000, random: () -> Double = { Math.random() }): Long {
        if (retryAfterMs != null && retryAfterMs > 0) return retryAfterMs.coerceAtMost(capMs)
        val exp = (baseMs shl attempt.coerceIn(0, 10)).coerceAtMost(capMs)
        return exp / 2 + (random() * (exp / 2)).toLong()
    }
}

package com.deuty.agent.core.util

import kotlinx.coroutines.delay

/**
 * Time is injected, never called directly.
 *
 * Two reasons: the retry/backoff and loop-detection logic is only testable if a
 * test can advance time instantly, and a real device's clock can jump (NTP
 * sync, timezone change) mid-task, which must not be interpreted as "the task
 * has been running for three hours".
 */
interface Clock {
    fun nowMs(): Long

    /** Monotonic milliseconds; immune to wall-clock jumps. */
    fun elapsedRealtimeMs(): Long

    suspend fun sleep(millis: Long)
}

object SystemTime : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L

    override suspend fun sleep(millis: Long) {
        if (millis > 0) delay(millis)
    }
}

/** Test double: sleep() is instant and advances the clock deterministically. */
class ManualClock(var now: Long = 1_700_000_000_000L) : Clock {
    var totalSleptMs: Long = 0L
        private set

    override fun nowMs(): Long = now

    override fun elapsedRealtimeMs(): Long = now

    override suspend fun sleep(millis: Long) {
        totalSleptMs += millis.coerceAtLeast(0L)
        now += millis.coerceAtLeast(0L)
    }

    fun advance(millis: Long) {
        now += millis
    }
}

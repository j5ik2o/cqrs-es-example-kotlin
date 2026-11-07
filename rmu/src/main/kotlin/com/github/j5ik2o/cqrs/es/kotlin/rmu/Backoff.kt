package com.github.j5ik2o.cqrs.es.kotlin.rmu

import java.time.Duration

/**
 * The stream reader's idle and failure delay. The delay grows exponentially to a cap and applies
 * full jitter, so a restarted reader does not hammer the stream or the database in lock step with
 * another replica.
 */
class Backoff(
    initial: Duration?,
    max: Duration?,
    private val jitter: (Duration) -> Duration = ::fullJitter,
) {
    private val initial: Duration =
        if (initial == null || initial <= Duration.ZERO) Duration.ofMillis(500) else initial
    private val max: Duration =
        if (max == null || max <= Duration.ZERO || max < this.initial) {
            Duration.ofSeconds(30)
        } else {
            max
        }
    private var attempt = 0

    @Synchronized
    fun next(): Duration {
        var delay = initial
        var index = 0
        while (index < attempt && delay < max) {
            delay = delay.multipliedBy(2)
            index++
        }
        if (delay > max) delay = max
        attempt++
        return jitter(delay)
    }

    @Synchronized
    fun reset() {
        attempt = 0
    }
}

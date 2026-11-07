package com.github.j5ik2o.cqrs.es.kotlin.rmu

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

fun interface Sleeper {
    fun sleep(delay: Duration)
}

internal fun fullJitter(delay: Duration): Duration {
    if (delay <= Duration.ZERO) return Duration.ZERO
    val upper = delay.toNanos()
    return Duration.ofNanos(ThreadLocalRandom.current().nextLong(upper + 1))
}

private fun systemSleep(delay: Duration) {
    try {
        Thread.sleep(delay.toMillis())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw RetryInterruptedException(e)
    }
}

/**
 * Bounds how long one record may be retried before it is parked.
 *
 * `maxElapsed` is the wall-clock budget, so a long queue of records cannot push a single invocation
 * past the caller's deadline. The delay grows exponentially and is capped, then reduced by full
 * jitter so replicas do not retry in lock step.
 */
class RetryPolicy(
    maxAttempts: Int,
    val maxElapsed: Duration = Duration.ofSeconds(30),
    val initialDelay: Duration = Duration.ofMillis(200),
    val maxDelay: Duration = Duration.ofSeconds(5),
    private val sleeper: Sleeper = Sleeper { delay -> systemSleep(delay) },
    private val jitter: (Duration) -> Duration = ::fullJitter,
    private val clock: Clock = Clock.systemUTC(),
) {
    val maxAttempts: Int = maxOf(1, maxAttempts)

    fun run(operation: () -> Unit) {
        val startedAt = clock.instant()
        var lastFailure: RuntimeException? = null
        for (attempt in 1..maxAttempts) {
            try {
                operation()
                return
            } catch (failure: RuntimeException) {
                lastFailure = failure
                if (!FailureClassifier.isRetryable(failure)) throw failure
                if (attempt == maxAttempts) throw RetryExhaustedException(attempt, failure)
                val delay = delay(attempt)
                val elapsed = Duration.between(startedAt, clock.instant())
                if (maxElapsed > Duration.ZERO && elapsed.plus(delay) > maxElapsed) {
                    throw RetryExhaustedException(
                        attempt,
                        RetryExhaustedException.ElapsedBudgetExceeded(elapsed, failure),
                    )
                }
                sleeper.sleep(delay)
            }
        }
        throw RetryExhaustedException(maxAttempts, lastFailure)
    }

    /** Returns the jittered delay before the next attempt. `attempt` starts at 1. */
    fun delay(attempt: Int): Duration {
        val effectiveAttempt = maxOf(1, attempt)
        if (initialDelay <= Duration.ZERO) return Duration.ZERO
        var delay = initialDelay
        var index = 1
        while (index < effectiveAttempt && (maxDelay <= Duration.ZERO || delay < maxDelay)) {
            delay = delay.multipliedBy(2)
            index++
        }
        if (maxDelay > Duration.ZERO && delay > maxDelay) {
            delay = maxDelay
        }
        return jitter(delay)
    }

    /** Signals that the policy stopped retrying. The last failure is the cause. */
    class RetryExhaustedException(
        val attempts: Int,
        cause: Throwable?,
    ) : RuntimeException("retry exhausted after $attempts attempts", cause) {
        /** Wraps the elapsed-budget check so the caller can distinguish it from the attempt cap. */
        class ElapsedBudgetExceeded(
            val elapsed: Duration,
            cause: Throwable?,
        ) : RuntimeException("retry elapsed budget exceeded after $elapsed", cause)
    }

    companion object {
        fun defaults(): RetryPolicy = RetryPolicy(5)

        /** Used when the outer delivery mechanism (Lambda or the stream reader) retries instead. */
        fun noRetry(): RetryPolicy =
            RetryPolicy(
                maxAttempts = 1,
                maxElapsed = Duration.ZERO,
                initialDelay = Duration.ZERO,
                maxDelay = Duration.ZERO,
                sleeper = Sleeper { _ -> },
                jitter = { it },
            )
    }
}

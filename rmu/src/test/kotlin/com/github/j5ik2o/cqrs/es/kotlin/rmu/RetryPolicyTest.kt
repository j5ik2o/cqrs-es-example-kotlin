package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class RetryPolicyTest {
    @Test
    fun `retries until success`() {
        val sleeper = RecordingSleeper()
        val policy = identityPolicy(5, Duration.ofSeconds(30), Duration.ofMillis(100), sleeper)
        val attempts = AtomicInteger()

        policy.run {
            if (attempts.incrementAndGet() < 3) {
                throw RuntimeException("transient")
            }
        }

        assertEquals(3, attempts.get())
        assertEquals(listOf(Duration.ofMillis(100), Duration.ofMillis(200)), sleeper.delays)
    }

    @Test
    fun `stops at the attempt limit`() {
        val sleeper = RecordingSleeper()
        val policy = identityPolicy(3, Duration.ofSeconds(30), Duration.ofMillis(100), sleeper)
        val attempts = AtomicInteger()

        val failure =
            assertThrows<RetryPolicy.RetryExhaustedException> {
                policy.run {
                    attempts.incrementAndGet()
                    throw RuntimeException("still failing")
                }
            }

        assertEquals(3, attempts.get())
        assertEquals(3, failure.attempts)
        assertEquals(2, sleeper.delays.size)
    }

    @Test
    fun `stops when the elapsed budget is exhausted`() {
        val sleeper = RecordingSleeper()
        val policy =
            RetryPolicy(
                maxAttempts = 10,
                maxElapsed = Duration.ofMillis(250),
                initialDelay = Duration.ofMillis(100),
                maxDelay = Duration.ofSeconds(1),
                sleeper = sleeper,
                jitter = { it },
                clock = sleeper.clock,
            )
        val attempts = AtomicInteger()

        val failure =
            assertThrows<RetryPolicy.RetryExhaustedException> {
                policy.run {
                    attempts.incrementAndGet()
                    throw RuntimeException("slow storage")
                }
            }

        assertEquals(2, attempts.get())
        assertIs<RetryPolicy.RetryExhaustedException.ElapsedBudgetExceeded>(failure.cause)
    }

    @Test
    fun `does not retry a permanent failure`() {
        val sleeper = RecordingSleeper()
        val policy = identityPolicy(5, Duration.ofSeconds(30), Duration.ofMillis(100), sleeper)
        val failure = PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad payload")
        val attempts = AtomicInteger()

        val thrown =
            assertThrows<PermanentFailure> {
                policy.run {
                    attempts.incrementAndGet()
                    throw failure
                }
            }

        assertSame(failure, thrown)
        assertEquals(1, attempts.get())
        assertEquals(emptyList(), sleeper.delays)
    }

    @Test
    fun `backoff grows caps and resets`() {
        val backoff =
            Backoff(
                initial = Duration.ofMillis(100),
                max = Duration.ofMillis(300),
                jitter = { it },
            )

        assertEquals(Duration.ofMillis(100), backoff.next())
        assertEquals(Duration.ofMillis(200), backoff.next())
        assertEquals(Duration.ofMillis(300), backoff.next())
        assertEquals(Duration.ofMillis(300), backoff.next())
        backoff.reset()
        assertEquals(Duration.ofMillis(100), backoff.next())
    }

    private fun identityPolicy(
        maxAttempts: Int,
        maxElapsed: Duration,
        initialDelay: Duration,
        sleeper: RecordingSleeper,
    ): RetryPolicy =
        RetryPolicy(
            maxAttempts = maxAttempts,
            maxElapsed = maxElapsed,
            initialDelay = initialDelay,
            maxDelay = Duration.ofSeconds(1),
            sleeper = sleeper,
            jitter = { it },
            clock = sleeper.clock,
        )

    private class RecordingSleeper : Sleeper {
        val clock = MutableClock()
        val delays = mutableListOf<Duration>()

        override fun sleep(delay: Duration) {
            delays.add(delay)
            clock.advance(delay)
        }
    }

    private class MutableClock : Clock() {
        private var instant: Instant = Instant.EPOCH

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }
    }
}

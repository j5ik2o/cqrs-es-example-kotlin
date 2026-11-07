package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadStreamSubscriptionTest {
    private val backoff = Backoff(Duration.ofMillis(1), Duration.ofMillis(1))

    @Test
    fun `polls immediately after start`() {
        val polled = CountDownLatch(1)
        val reader = FakeReader(onPoll = { polled.countDown() })
        val subscription = HeadStreamSubscription(reader, "head", backoff)
        try {
            subscription.start()

            assertTrue(polled.await(5, TimeUnit.SECONDS))
            assertTrue(reader.polls.get() >= 1)
        } finally {
            subscription.stop()
        }
    }

    @Test
    fun `requests a resync and resets the reader when the stream was trimmed`() {
        val reader = FakeReader(failure = StreamTrimmedException("shard-1"), failOnce = true)
        val subscription = HeadStreamSubscription(reader, "head", backoff)
        try {
            subscription.start()

            awaitTrue(5) { subscription.isResyncRequested() }
            assertTrue(reader.resets.get() >= 1)

            subscription.clearResyncRequest()
            assertFalse(subscription.isResyncRequested())
        } finally {
            subscription.stop()
        }
    }

    @Test
    fun `stop interrupts a blocked poll`() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val reader =
            object : HeadStreamReader {
                override fun pollOnce(headTableName: String): Int {
                    started.countDown()
                    try {
                        Thread.sleep(60_000)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        interrupted.countDown()
                        throw RetryInterruptedException(e)
                    }
                    return 0
                }

                override fun reset() = Unit

                override fun lastRecordTime(): Instant? = null
            }
        val subscription = HeadStreamSubscription(reader, "head", backoff, Duration.ofSeconds(2))

        subscription.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        subscription.stop()

        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `stop ends the poll loop`() {
        val reader = FakeReader()
        val subscription = HeadStreamSubscription(reader, "head", backoff)
        subscription.start()
        awaitTrue(5) { reader.polls.get() >= 1 }

        subscription.stop()
        val afterStop = reader.polls.get()
        Thread.sleep(50)

        assertEquals(afterStop, reader.polls.get())
    }

    private fun awaitTrue(
        timeoutSeconds: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue(condition(), "condition was not met within $timeoutSeconds seconds")
    }

    private class FakeReader(
        private val onPoll: (() -> Unit)? = null,
        private val failure: RuntimeException? = null,
        private val failOnce: Boolean = false,
    ) : HeadStreamReader {
        val polls = AtomicInteger()
        val resets = AtomicInteger()
        private val failureRaised = AtomicBoolean(false)

        override fun pollOnce(headTableName: String): Int {
            polls.incrementAndGet()
            onPoll?.invoke()
            val shouldFail = failure != null && (!failOnce || failureRaised.compareAndSet(false, true))
            if (shouldFail) {
                failure?.let { throw it }
            }
            return 0
        }

        override fun reset() {
            resets.incrementAndGet()
        }

        override fun lastRecordTime(): Instant? = null
    }
}

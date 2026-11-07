package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRmuRunnerTest {
    private val now = Instant.parse("2026-01-02T00:00:00Z")

    @Test
    fun `resyncs when no record was read and the last resync is old`() {
        assertTrue(
            LocalRmuRunner.shouldResync(
                lastRecordTime = null,
                lastSuccessfulResyncAt = now.minus(Duration.ofHours(25)),
                now = now,
            ),
        )
    }

    @Test
    fun `does not resync right after a successful resync even without records`() {
        assertFalse(
            LocalRmuRunner.shouldResync(
                lastRecordTime = null,
                lastSuccessfulResyncAt = now.minus(Duration.ofHours(1)),
                now = now,
            ),
        )
    }

    @Test
    fun `does not resync when a record was read recently`() {
        assertFalse(
            LocalRmuRunner.shouldResync(
                lastRecordTime = now.minus(Duration.ofHours(1)),
                lastSuccessfulResyncAt = now.minus(Duration.ofHours(25)),
                now = now,
            ),
        )
    }

    @Test
    fun `resyncs when neither a record nor a resync happened within the retention window`() {
        assertTrue(
            LocalRmuRunner.shouldResync(
                lastRecordTime = now.minus(Duration.ofHours(25)),
                lastSuccessfulResyncAt = now.minus(Duration.ofHours(25)),
                now = now,
            ),
        )
    }

    @Test
    fun `resumes the subscription before the initial scan`() {
        val subscription = FakeSubscription()
        var subscriptionWasStarted = false
        val applier = JournalBackedApplier(emptyMap())
        val scanner =
            HeadScanner {
                subscriptionWasStarted = subscription.started
            }
        val runner = newRunner(subscription, Resyncer(applier, scanner, null), applier)

        assertTrue(runner.reconcileStartup())

        assertTrue(subscription.started)
        assertTrue(subscriptionWasStarted)
        // The subscription keeps running while the runner monitors the stream.
        assertFalse(subscription.stopped)
    }

    @Test
    fun `applies a head update that arrives during the initial scan`() {
        val applier = JournalBackedApplier(mapOf(AGGREGATE_ID to listOf(1L, 2L, 3L, 4L)))
        val subscription = FakeSubscription(onDeliver = { event -> applier.applyHeadEvent(AGGREGATE_ID, event) })
        val scanner =
            HeadScanner { visitor ->
                // The subscription applies seq_nr 4 while the scan is in progress. The journal fills
                // the gap 1..3 under the same position guard.
                subscription.deliver(headEvent(4))
                // The scan had already read this head, so it still reports the stale position.
                visitor(Head(AGGREGATE_ID, "GroupChat", 2))
            }
        val runner = newRunner(subscription, Resyncer(applier, scanner, null), applier)

        assertTrue(runner.reconcileStartup())

        assertEquals(listOf(1L, 2L, 3L, 4L), applier.appliedSeqNrs(AGGREGATE_ID))
        assertEquals(4L, applier.position(AGGREGATE_ID))
    }

    @Test
    fun `does not let the scan move a position past an applied stream update`() {
        val applier = JournalBackedApplier(mapOf(AGGREGATE_ID to listOf(1L, 2L, 3L)))
        val subscription = FakeSubscription(onDeliver = { event -> applier.applyHeadEvent(AGGREGATE_ID, event) })
        val scanner =
            HeadScanner { visitor ->
                subscription.deliver(headEvent(3))
                // The scan sees the head after the update and asks for the same range again.
                visitor(Head(AGGREGATE_ID, "GroupChat", 3))
            }
        val runner = newRunner(subscription, Resyncer(applier, scanner, null), applier)

        assertTrue(runner.reconcileStartup())

        assertEquals(listOf(1L, 2L, 3L), applier.appliedSeqNrs(AGGREGATE_ID))
        assertEquals(3L, applier.position(AGGREGATE_ID))
    }

    private fun newRunner(
        subscription: StreamSubscription,
        resyncer: Resyncer,
        applier: EventApplier,
    ): LocalRmuRunner =
        LocalRmuRunner(
            subscription = subscription,
            resyncer = resyncer,
            recoveryService = RecoveryService(NoopTaskStore, StoreIdProvider { STORE_ID }, applier, null),
            retryPolicy = RetryPolicy.noRetry(),
            backoff = Backoff(Duration.ofMillis(1), Duration.ofMillis(1)),
            headTableName = "head",
            recoveryIntervalMillis = 60_000,
            maxRecoveryTasksPerRun = 50,
        )

    private fun headEvent(seqNr: Long): HeadEvent = HeadEvent(seqNr, Instant.EPOCH, "", ByteArray(0))

    private class FakeSubscription(
        private val onDeliver: ((HeadEvent) -> Unit)? = null,
    ) : StreamSubscription {
        var started = false
        var stopped = false
        var resets = 0
        private var requested = false

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        override fun reset() {
            resets++
        }

        override fun lastRecordTime(): Instant? = null

        override fun isResyncRequested(): Boolean = requested

        override fun clearResyncRequest() {
            requested = false
        }

        fun deliver(event: HeadEvent) {
            onDeliver?.invoke(event)
        }
    }

    private class JournalBackedApplier(
        private val journal: Map<String, List<Long>>,
    ) : EventApplier {
        private val positions = mutableMapOf<String, Long>()
        private val applied = mutableListOf<Pair<String, Long>>()

        override fun position(aggregateId: String): Long = positions[aggregateId] ?: 0L

        override fun applyHeadEvent(
            aggregateId: String,
            event: HeadEvent,
        ) {
            if (event.seqNr <= position(aggregateId)) return
            if (event.seqNr > position(aggregateId) + 1) {
                applyThrough(aggregateId, event.seqNr - 1)
            }
            positions[aggregateId] = event.seqNr
            applied.add(aggregateId to event.seqNr)
        }

        override fun applyThrough(
            aggregateId: String,
            targetSeqNr: Long,
        ) {
            val missing =
                journal[aggregateId]
                    .orEmpty()
                    .filter { it > position(aggregateId) && it <= targetSeqNr }
                    .sorted()
            for (seqNr in missing) {
                positions[aggregateId] = seqNr
                applied.add(aggregateId to seqNr)
            }
        }

        fun appliedSeqNrs(aggregateId: String): List<Long> = applied.filter { it.first == aggregateId }.map { it.second }
    }

    private object NoopTaskStore : RecoveryTaskStore {
        override fun record(task: RecoveryTask) = Unit

        override fun listPending(): List<RecoveryTask> = emptyList()

        override fun markCompleted(
            storeId: String,
            projectionName: String,
            aggregateId: String,
            seqNr: Long,
        ) = Unit

        override fun markFailed(
            task: RecoveryTask,
            failure: Throwable?,
        ) = Unit
    }

    companion object {
        private const val AGGREGATE_ID = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z"
        private const val STORE_ID = "store-0001"
    }
}

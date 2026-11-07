package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResyncerTest {
    @Test
    fun `resyncs every head from the journal`() {
        val applier = FakeApplier()
        val resyncer =
            Resyncer(
                applier,
                FakeHeadScanner(
                    listOf(
                        Head("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z", "GroupChat", 5),
                        Head("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Y", "GroupChat", 3),
                    ),
                ),
                null,
            )

        val result = resyncer.resync()

        assertEquals(2, result.heads)
        assertEquals(0, result.skipped)
        assertEquals(2, result.advanced)
        assertEquals(0, result.failed)
        assertEquals(
            listOf(
                "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z" to 5L,
                "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Y" to 3L,
            ),
            applier.applied,
        )
    }

    @Test
    fun `skips the configuration item and other aggregate types`() {
        val applier = FakeApplier()
        val resyncer =
            Resyncer(
                applier,
                FakeHeadScanner(
                    listOf(
                        Head("__config__", "Config", 0),
                        Head("UserAccount-01H42K4ABWQ5V2XQEP3A48VE0Z", "UserAccount", 3),
                        Head("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Y", "GroupChat", 1),
                    ),
                ),
                null,
            )

        val result = resyncer.resync()

        assertEquals(1, result.heads)
        assertEquals(2, result.skipped)
        assertEquals(1, result.advanced)
        assertEquals(1, applier.applied.size)
    }

    @Test
    fun `skips an up-to-date head`() {
        val applier = FakeApplier(mapOf("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z" to 5L))
        val resyncer =
            Resyncer(
                applier,
                FakeHeadScanner(listOf(Head("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z", "GroupChat", 5))),
                null,
            )

        val result = resyncer.resync()

        assertEquals(1, result.heads)
        assertEquals(0, result.advanced)
        assertTrue(applier.applied.isEmpty())
    }

    @Test
    fun `records a permanent failure and completes the other aggregates`() {
        val brokenId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z"
        val healthyId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Y"
        val failure = PermanentFailure(FailureKind.JOURNAL_GAP, "missing")
        val applier = FakeApplier(failures = mapOf(brokenId to failure))
        val recovery = RecordingRecovery()
        val resyncer =
            Resyncer(
                applier,
                FakeHeadScanner(
                    listOf(
                        Head(brokenId, "GroupChat", 2),
                        Head(healthyId, "GroupChat", 2),
                    ),
                ),
                recovery,
            )

        val result = resyncer.resync()

        assertEquals(2, result.heads)
        assertEquals(2, result.advanced)
        assertEquals(1, result.failed)
        assertEquals(2L, applier.positions[healthyId])
        assertEquals(listOf(Recorded(brokenId, 2, 2, failure)), recovery.recorded)
    }

    @Test
    fun `records a retry exhausted failure`() {
        val brokenId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z"
        val failure = RetryPolicy.RetryExhaustedException(3, RuntimeException("storage"))
        val applier = FakeApplier(failures = mapOf(brokenId to failure))
        val recovery = RecordingRecovery()
        val resyncer =
            Resyncer(
                applier,
                FakeHeadScanner(listOf(Head(brokenId, "GroupChat", 2))),
                recovery,
            )

        val result = resyncer.resync()

        assertEquals(1, result.failed)
        assertEquals(1, recovery.recorded.size)
    }

    @Test
    fun `returns the original failure when no recovery service is configured`() {
        val brokenId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z"
        val applier = FakeApplier(failures = mapOf(brokenId to PermanentFailure(FailureKind.JOURNAL_GAP, "missing")))
        val resyncer =
            Resyncer(
                applier,
                FakeHeadScanner(listOf(Head(brokenId, "GroupChat", 2))),
                null,
            )

        assertThrows<PermanentFailure> { resyncer.resync() }
    }

    @Test
    fun `aborts on a storage failure`() {
        val brokenId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z"
        val applier = FakeApplier(failures = mapOf(brokenId to ReadModelUpdateException("storage down")))
        val resyncer =
            Resyncer(
                applier,
                FakeHeadScanner(listOf(Head(brokenId, "GroupChat", 2))),
                RecordingRecovery(),
            )

        assertThrows<ReadModelUpdateException> { resyncer.resync() }
    }

    private class FakeHeadScanner(
        private val heads: List<Head>,
    ) : HeadScanner {
        override fun scanHeads(visitor: (Head) -> Unit) {
            heads.forEach(visitor)
        }
    }

    private class FakeApplier(
        initialPositions: Map<String, Long> = emptyMap(),
        private val failures: Map<String, RuntimeException> = emptyMap(),
    ) : EventApplier {
        val positions = initialPositions.toMutableMap()
        val applied = mutableListOf<Pair<String, Long>>()

        override fun position(aggregateId: String): Long = positions[aggregateId] ?: 0L

        override fun applyHeadEvent(
            aggregateId: String,
            event: HeadEvent,
        ) {
            failures[aggregateId]?.let { throw it }
            positions[aggregateId] = event.seqNr
        }

        override fun applyThrough(
            aggregateId: String,
            targetSeqNr: Long,
        ) {
            failures[aggregateId]?.let { throw it }
            applied.add(aggregateId to targetSeqNr)
            positions[aggregateId] = targetSeqNr
        }
    }

    private data class Recorded(
        val aggregateId: String,
        val targetSeqNr: Long,
        val failedSeqNr: Long,
        val failure: Throwable?,
    )

    private class RecordingRecovery : RecoveryRecorder {
        val recorded = mutableListOf<Recorded>()

        override fun record(
            aggregateId: String,
            targetSeqNr: Long,
            failedSeqNr: Long,
            failure: Throwable?,
        ) {
            recorded.add(Recorded(aggregateId, targetSeqNr, failedSeqNr, failure))
        }
    }
}

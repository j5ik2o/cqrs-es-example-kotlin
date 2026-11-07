package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.OperationType
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.StreamRecord
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadModelUpdaterRecoveryTest {
    @Test
    fun `retries a transient failure until success`() {
        val applier = FakeApplier(failuresBeforeSuccess = 2)
        val updater = ReadModelUpdater(applier, null, retryPolicy(5))

        val result = updater.update(headRecord(AGGREGATE_ID, listOf(1L)))

        assertEquals(ReadModelUpdater.RecordResult.APPLIED, result)
        assertEquals(3, applier.calls)
        assertEquals(1L, applier.position)
    }

    @Test
    fun `records an exhausted retry`() {
        val applier = FakeApplier(alwaysFail = true)
        val recovery = RecordingRecovery()
        val updater = ReadModelUpdater(applier, recovery, retryPolicy(3))

        val result = updater.update(headRecord(AGGREGATE_ID, listOf(1L)))

        assertEquals(ReadModelUpdater.RecordResult.RECORDED_FAILURE, result)
        assertEquals(3, applier.calls)
        assertEquals(1, recovery.records.size)
        assertEquals(AGGREGATE_ID, recovery.records[0].aggregateId)
        assertEquals(1L, recovery.records[0].targetSeqNr)
        assertEquals(1L, recovery.records[0].failedSeqNr)
        assertTrue(recovery.records[0].failure is RetryPolicy.RetryExhaustedException)
    }

    @Test
    fun `records a permanent failure`() {
        val failure = PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad payload")
        val applier = FakeApplier(alwaysFail = true, failureFactory = { failure })
        val recovery = RecordingRecovery()
        val updater = ReadModelUpdater(applier, recovery, retryPolicy(5))

        val result = updater.update(headRecord(AGGREGATE_ID, listOf(1L)))

        assertEquals(ReadModelUpdater.RecordResult.RECORDED_FAILURE, result)
        assertEquals(1, applier.calls)
        assertEquals(1, recovery.records.size)
        assertEquals(failure, recovery.records[0].failure)
    }

    @Test
    fun `records the last seq of a multi-event record as the recovery target`() {
        val failure = PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad third event")
        val applier = FakeApplier(failureFactory = { failure }, failOnSeq = 3L)
        val recovery = RecordingRecovery()
        val updater = ReadModelUpdater(applier, recovery, retryPolicy(1))

        val result = updater.update(headRecord(AGGREGATE_ID, listOf(1L, 2L, 3L)))

        assertEquals(ReadModelUpdater.RecordResult.RECORDED_FAILURE, result)
        assertEquals(2L, applier.position)
        assertEquals(1, recovery.records.size)
        assertEquals(3L, recovery.records[0].targetSeqNr)
        assertEquals(3L, recovery.records[0].failedSeqNr)
    }

    @Test
    fun `records a head parse failure as a recovery task`() {
        val applier = FakeApplier()
        val recovery = RecordingRecovery()
        val updater = ReadModelUpdater(applier, recovery, retryPolicy(3))

        val result = updater.update(headRecordWithoutEvents(AGGREGATE_ID, 2L))

        assertEquals(ReadModelUpdater.RecordResult.RECORDED_FAILURE, result)
        assertEquals(0, applier.calls)
        assertEquals(1, recovery.records.size)
        assertEquals(2L, recovery.records[0].targetSeqNr)
        assertTrue(recovery.records[0].failure is PermanentFailure)
    }

    @Test
    fun `returns a recovery store write failure to the caller`() {
        val applier = FakeApplier(alwaysFail = true)
        val recovery = RecordingRecovery(failOnRecord = true)
        val updater = ReadModelUpdater(applier, recovery, retryPolicy(1))

        assertThrows<IllegalStateException> {
            updater.update(headRecord(AGGREGATE_ID, listOf(1L)))
        }
    }

    @Test
    fun `skips the configuration item and other aggregate types`() {
        val applier = FakeApplier()
        val updater = ReadModelUpdater(applier, null, retryPolicy(1))

        assertEquals(
            ReadModelUpdater.RecordResult.APPLIED,
            updater.update(headRecord(HeadRecordParser.CONFIG_AGGREGATE_ID, listOf(1L))),
        )
        assertEquals(
            ReadModelUpdater.RecordResult.APPLIED,
            updater.update(headRecord("UserAccount-01H42K4ABWQ5V2XQEP3A48VE0Z", listOf(1L))),
        )
        assertEquals(0, applier.calls)
    }

    private fun retryPolicy(maxAttempts: Int): RetryPolicy =
        RetryPolicy(
            maxAttempts = maxAttempts,
            maxElapsed = Duration.ofSeconds(30),
            initialDelay = Duration.ZERO,
            maxDelay = Duration.ZERO,
            sleeper = Sleeper { _ -> },
            jitter = { it },
        )

    private fun headRecord(
        aid: String,
        events: List<Long>,
    ): Record =
        Record
            .builder()
            .eventName(OperationType.MODIFY)
            .dynamodb(
                StreamRecord
                    .builder()
                    .newImage(
                        mapOf(
                            "aid" to AttributeValue.fromS(aid),
                            "seq_nr" to AttributeValue.fromN(events.last().toString()),
                            "events" to
                                AttributeValue.fromL(
                                    events.map { seqNr ->
                                        AttributeValue.fromM(
                                            mapOf(
                                                "seq_nr" to AttributeValue.fromN(seqNr.toString()),
                                                "occurred_at" to AttributeValue.fromN("0"),
                                                "payload" to
                                                    AttributeValue.fromB(
                                                        SdkBytes.fromByteArray(
                                                            byteArrayOf(seqNr.toByte()),
                                                        ),
                                                    ),
                                            ),
                                        )
                                    },
                                ),
                        ),
                    ).build(),
            ).build()

    private fun headRecordWithoutEvents(
        aid: String,
        seqNr: Long,
    ): Record =
        Record
            .builder()
            .eventName(OperationType.MODIFY)
            .dynamodb(
                StreamRecord
                    .builder()
                    .newImage(
                        mapOf(
                            "aid" to AttributeValue.fromS(aid),
                            "seq_nr" to AttributeValue.fromN(seqNr.toString()),
                        ),
                    ).build(),
            ).build()

    private class FakeApplier(
        private val failuresBeforeSuccess: Int = 0,
        private val alwaysFail: Boolean = false,
        private val failureFactory: () -> RuntimeException = { RuntimeException("transient") },
        private val failOnSeq: Long? = null,
    ) : EventApplier {
        var calls = 0
        var position = 0L

        override fun position(aggregateId: String): Long = position

        override fun applyHeadEvent(
            aggregateId: String,
            event: HeadEvent,
        ) {
            calls++
            if (alwaysFail) throw failureFactory()
            if (failOnSeq != null && event.seqNr == failOnSeq) throw failureFactory()
            if (calls <= failuresBeforeSuccess) throw failureFactory()
            position = event.seqNr
        }

        override fun applyThrough(
            aggregateId: String,
            targetSeqNr: Long,
        ) {
            position = targetSeqNr
        }
    }

    private data class Recorded(
        val aggregateId: String,
        val targetSeqNr: Long,
        val failedSeqNr: Long,
        val failure: Throwable?,
    )

    private class RecordingRecovery(
        private val failOnRecord: Boolean = false,
    ) : RecoveryRecorder {
        val records = mutableListOf<Recorded>()

        override fun record(
            aggregateId: String,
            targetSeqNr: Long,
            failedSeqNr: Long,
            failure: Throwable?,
        ) {
            if (failOnRecord) throw IllegalStateException("recovery store down")
            records.add(Recorded(aggregateId, targetSeqNr, failedSeqNr, failure))
        }
    }

    companion object {
        private const val AGGREGATE_ID = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z"
    }
}

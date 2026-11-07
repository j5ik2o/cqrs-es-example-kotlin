package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.model.Record

/**
 * Applies one head stream record, skipping `__config__` and other aggregate types.
 *
 * A retryable failure is retried with the configured policy. A permanent failure, or a retry that
 * exhausted its budget, is recorded as a recovery task and the record is considered handled so the
 * stream can move on. The aggregate's position stays behind until the recovery task applies the
 * journal.
 */
class ReadModelUpdater(
    private val applier: EventApplier,
    private val recovery: RecoveryRecorder? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy.defaults(),
) {
    private val logger = LoggerFactory.getLogger(ReadModelUpdater::class.java)

    fun update(record: Record): RecordResult {
        val identity =
            try {
                parseHeadRecord(record)
            } catch (e: RuntimeException) {
                val fallback = HeadRecordParser.fallbackIdentity(record)
                if (recovery != null && fallback != null && isRecoverable(e)) {
                    logger.error(
                        "Failed to parse a head record; recording a recovery task: aid={}, seqNr={}",
                        fallback.aggregateId,
                        fallback.headSeqNr,
                        e,
                    )
                    recovery.record(
                        fallback.aggregateId,
                        fallback.headSeqNr,
                        fallback.headSeqNr,
                        e,
                    )
                    return RecordResult.RECORDED_FAILURE
                }
                throw e
            }
        if (identity.skip) return RecordResult.APPLIED

        val lastSeqNr = identity.events.last().seqNr
        for (event in identity.events) {
            try {
                retryPolicy.run { applier.applyHeadEvent(identity.aggregateId, event) }
            } catch (e: RuntimeException) {
                if (recovery == null || !isRecoverable(e)) {
                    throw e
                }
                // A record can carry several events. Recovery must reach the record's last event,
                // otherwise the events after the failed one wait for another record or a resync.
                recovery.record(identity.aggregateId, lastSeqNr, event.seqNr, e)
                return RecordResult.RECORDED_FAILURE
            }
        }
        return RecordResult.APPLIED
    }

    private fun parseHeadRecord(record: Record): RecordIdentity {
        val head = HeadRecordParser.parse(record)
        return if (head == null) {
            RecordIdentity(skip = true, aggregateId = "", events = emptyList())
        } else {
            RecordIdentity(skip = false, aggregateId = head.aggregateId, events = head.events)
        }
    }

    private fun isRecoverable(failure: Throwable): Boolean =
        FailureClassifier.classify(failure) != null ||
            failure is RetryPolicy.RetryExhaustedException

    enum class RecordResult {
        APPLIED,
        RECORDED_FAILURE,
    }

    private data class RecordIdentity(
        val skip: Boolean,
        val aggregateId: String,
        val events: List<HeadEvent>,
    )
}

package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.slf4j.LoggerFactory

/**
 * Implements DY-15's reconciliation: scan every head, compare its seq_nr with the projection
 * position, and fill the lag from the journal with the same position guard as the stream path.
 *
 * A permanent per-aggregate failure is recorded as a recovery task and does not stop the remaining
 * aggregates. A storage failure aborts the scan so the caller can retry it.
 */
class Resyncer(
    private val applier: EventApplier,
    private val scanner: HeadScanner,
    private val recovery: RecoveryRecorder? = null,
) {
    private val logger = LoggerFactory.getLogger(Resyncer::class.java)

    fun resync(): ResyncResult {
        var heads = 0
        var skipped = 0
        var advanced = 0
        var failed = 0
        var firstFailure: RuntimeException? = null

        scanner.scanHeads { head ->
            if (!AggregateType.isGroupChat(head.aggregateId)) {
                skipped++
                return@scanHeads
            }
            heads++
            val position = applier.position(head.aggregateId)
            if (head.seqNr <= position) {
                return@scanHeads
            }
            advanced++
            try {
                applier.applyThrough(head.aggregateId, head.seqNr)
            } catch (e: RuntimeException) {
                if (!isRecoverable(e)) {
                    throw e
                }
                failed++
                if (recovery != null) {
                    try {
                        recovery.record(head.aggregateId, head.seqNr, head.seqNr, e)
                    } catch (recordError: RuntimeException) {
                        recordError.addSuppressed(e)
                        firstFailure =
                            firstFailure?.apply { addSuppressed(recordError) } ?: recordError
                        return@scanHeads
                    }
                } else {
                    // Without a recovery service the failure is not durable. Keep the original
                    // error and report it after the remaining aggregates have been scanned.
                    firstFailure = firstFailure?.apply { addSuppressed(e) } ?: e
                }
                logger.error(
                    if (recovery != null) {
                        "Resync could not apply an aggregate; recorded a recovery task: aid={}, seqNr={}"
                    } else {
                        "Resync could not apply an aggregate and no recovery service is configured: aid={}, seqNr={}"
                    },
                    head.aggregateId,
                    head.seqNr,
                    e,
                )
            }
        }

        firstFailure?.let { throw it }
        return ResyncResult(heads, skipped, advanced, failed)
    }

    private fun isRecoverable(failure: Throwable): Boolean =
        FailureClassifier.classify(failure) != null ||
            failure is RetryPolicy.RetryExhaustedException
}

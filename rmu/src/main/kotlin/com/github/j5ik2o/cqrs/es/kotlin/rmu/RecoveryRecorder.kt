package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** Records a failed event durably. The implementation is responsible for idempotency. */
fun interface RecoveryRecorder {
    fun record(
        aggregateId: String,
        targetSeqNr: Long,
        failedSeqNr: Long,
        failure: Throwable?,
    )

    fun record(
        aggregateId: String,
        targetSeqNr: Long,
        failure: Throwable?,
    ) = record(aggregateId, targetSeqNr, targetSeqNr, failure)
}

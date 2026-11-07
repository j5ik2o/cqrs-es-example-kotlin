package com.github.j5ik2o.cqrs.es.kotlin.rmu

/**
 * Persists recovery tasks. Recording is idempotent on the (store, projection, aggregate, seq_nr) key
 * so a redelivered record does not create duplicates.
 */
interface RecoveryTaskStore {
    fun record(task: RecoveryTask)

    fun listPending(): List<RecoveryTask>

    fun markCompleted(
        storeId: String,
        projectionName: String,
        aggregateId: String,
        seqNr: Long,
    )

    /** Records a failed recovery attempt and moves the task behind tasks not yet attempted. */
    fun markFailed(
        task: RecoveryTask,
        failure: Throwable?,
    )
}

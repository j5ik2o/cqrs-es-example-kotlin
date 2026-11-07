package com.github.j5ik2o.cqrs.es.kotlin.rmu

import java.time.Instant

/**
 * The durable record of one event that could not be applied.
 *
 * It stores identifiers only. The event body is always re-read from the journal, so a task cannot
 * go stale when the payload is repaired.
 */
data class RecoveryTask(
    val storeId: String,
    val projectionName: String,
    val projectionVersion: String,
    val aggregateId: String,
    val seqNr: Long,
    val failureKind: FailureKind,
    val errorMessage: String?,
    val status: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_COMPLETED = "completed"
    }
}

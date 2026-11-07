package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Records failed events and replays them from the journal.
 *
 * This is the DLQ half of the RMU contract. The task is durable, the replay is idempotent, and
 * completion is claimed only after the position reaches the task's seq_nr.
 */
class RecoveryService(
    private val taskStore: RecoveryTaskStore,
    private val storeIdProvider: StoreIdProvider,
    private val applier: EventApplier,
    private val notifier: RecoveryNotifier? = null,
) : RecoveryRecorder {
    private val logger = LoggerFactory.getLogger(RecoveryService::class.java)

    override fun record(
        aggregateId: String,
        targetSeqNr: Long,
        failedSeqNr: Long,
        failure: Throwable?,
    ) {
        val kind = FailureClassifier.classify(failure) ?: FailureKind.UNKNOWN
        val storeId = storeIdProvider.storeId()
        val now = Instant.now()
        val message = "failed at seq_nr $failedSeqNr: ${failure?.message ?: ""}"
        val task =
            RecoveryTask(
                storeId = storeId,
                projectionName = Projection.NAME,
                projectionVersion = Projection.VERSION,
                aggregateId = aggregateId,
                seqNr = targetSeqNr,
                failureKind = kind,
                errorMessage = message,
                status = RecoveryTask.STATUS_PENDING,
                createdAt = now,
                updatedAt = now,
            )
        taskStore.record(task)
        if (notifier != null) {
            try {
                notifier.notifyTask(task)
            } catch (e: RuntimeException) {
                // The durable task already exists. A notification failure only means the worker is
                // not woken immediately; the next recovery run still sees the task.
                logger.warn(
                    "Failed to notify a recovery task: aid={}, seqNr={}",
                    aggregateId,
                    targetSeqNr,
                    e,
                )
            }
        }
    }

    /**
     * Replays one task. It re-reads the position first, so calling it twice is safe: the second call
     * finds the position at or beyond the task and only marks the task complete.
     */
    fun recover(task: RecoveryTask) {
        val storeId = storeIdProvider.storeId()
        if (!belongsToCurrentProjection(task, storeId)) {
            throw RecoveryException(
                "recovery task belongs to another store or projection: storeId=${task.storeId}, projection=${task.projectionName}@${task.projectionVersion}",
            )
        }
        val position = applier.position(task.aggregateId)
        if (position < task.seqNr) {
            applier.applyThrough(task.aggregateId, task.seqNr)
        }
        val confirmed = applier.position(task.aggregateId)
        if (confirmed < task.seqNr) {
            throw RecoveryException(
                "aggregate ${task.aggregateId} stayed at position $confirmed, expected ${task.seqNr}",
            )
        }
        taskStore.markCompleted(
            task.storeId,
            task.projectionName,
            task.aggregateId,
            task.seqNr,
        )
    }

    /** Replays at most one bounded batch of pending tasks. */
    fun recoverPending(maxTasks: Int = DEFAULT_MAX_TASKS_PER_RUN): Int {
        val storeId = storeIdProvider.storeId()
        val tasks = taskStore.listPending()
        var completed = 0
        var processed = 0
        var failure: RecoveryException? = null
        for (task in tasks) {
            if (processed >= maxTasks) break
            if (!belongsToCurrentProjection(task, storeId)) {
                logger.warn(
                    "Skipping a recovery task from another store or projection: storeId={}, projection={}@{}",
                    task.storeId,
                    task.projectionName,
                    task.projectionVersion,
                )
                continue
            }
            processed++
            try {
                recover(task)
                completed++
            } catch (e: RetryInterruptedException) {
                throw e
            } catch (e: RuntimeException) {
                markFailed(task, e)
                if (failure == null) {
                    failure = RecoveryException("some recovery tasks remain pending", e)
                } else {
                    failure.addSuppressed(e)
                }
            }
        }
        failure?.let { throw it }
        return completed
    }

    fun pendingTasks(): List<RecoveryTask> = taskStore.listPending()

    private fun markFailed(
        task: RecoveryTask,
        failure: RuntimeException,
    ) {
        try {
            taskStore.markFailed(task, failure)
        } catch (markError: RuntimeException) {
            failure.addSuppressed(markError)
        }
    }

    companion object {
        private const val DEFAULT_MAX_TASKS_PER_RUN = 50

        private fun belongsToCurrentProjection(
            task: RecoveryTask,
            storeId: String,
        ): Boolean =
            storeId == task.storeId &&
                Projection.NAME == task.projectionName &&
                Projection.VERSION == task.projectionVersion
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import org.slf4j.LoggerFactory

/** Replays at most [maxTasks] pending recovery tasks and returns how many completed. */
fun interface PendingRecovery {
    fun recoverPending(maxTasks: Int): Int
}

/**
 * Replays durable recovery tasks when the recovery queue wakes it.
 *
 * The SQS message is only a wake-up signal. The worker reads pending tasks from MySQL and replays
 * them from the journal, so a redelivered message is harmless. If a run leaves tasks pending, every
 * message is returned as a batch item failure so SQS retries and the queue's redrive policy
 * eventually parks a poison message.
 */
class RecoveryWorker(
    private val recovery: PendingRecovery,
    private val maxTasksPerRun: Int,
) : RequestHandler<SQSEvent, BatchItemFailuresResponse> {
    /** Lambda instantiates the handler class with a no-argument constructor. */
    constructor() : this(
        PendingRecovery { maxTasks -> LambdaDependenciesHolder.instance.recoveryService.recoverPending(maxTasks) },
        LambdaDependenciesHolder.instance.maxRecoveryTasksPerRun,
    )

    override fun handleRequest(
        event: SQSEvent,
        context: Context,
    ): BatchItemFailuresResponse {
        val run =
            try {
                recoverAllBatches()
            } catch (e: RuntimeException) {
                return failureResponse(event, e)
            }
        logger.info("Recovery run completed {} pending tasks", run.completed)
        if (!run.capped) {
            return BatchItemFailuresResponse(emptyList())
        }
        return failureResponse(event, RecoveryException("recovery run reached the batch cap"))
    }

    private fun recoverAllBatches(): RecoveryRun {
        var total = 0
        repeat(MAX_BATCHES_PER_RUN) {
            val completed = recovery.recoverPending(maxTasksPerRun)
            total += completed
            if (completed < maxTasksPerRun) {
                return RecoveryRun(total, capped = false)
            }
        }
        return RecoveryRun(total, capped = true)
    }

    private fun failureResponse(
        event: SQSEvent,
        failure: RuntimeException,
    ): BatchItemFailuresResponse {
        val failures = redeliverAll(event)
        if (failures.isEmpty()) {
            // A manual invocation has no SQS message to redeliver, so the caller must see the
            // failure instead of a successful response.
            throw failure
        }
        logger.error("Recovery run failed; the messages will be redelivered", failure)
        return BatchItemFailuresResponse(failures)
    }

    private fun redeliverAll(event: SQSEvent): List<BatchItemFailuresResponse.BatchItemFailure> =
        event.records
            .orEmpty()
            .mapNotNull { it.messageId }
            .map { BatchItemFailuresResponse.BatchItemFailure(it) }

    private data class RecoveryRun(
        val completed: Int,
        val capped: Boolean,
    )

    private companion object {
        private val logger = LoggerFactory.getLogger(RecoveryWorker::class.java)
        private const val MAX_BATCHES_PER_RUN = 10
    }
}

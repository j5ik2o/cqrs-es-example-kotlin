package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.fasterxml.jackson.databind.ObjectMapper
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

/**
 * Wakes the recovery worker through SQS.
 *
 * The message carries identifiers only. The durable task in MySQL stays the source of truth and the
 * worker re-reads the journal, so a lost or duplicated message changes nothing.
 */
class SqsRecoveryNotifier(
    private val sender: MessageSender,
    private val queueUrl: String,
) : RecoveryNotifier {
    /** Production constructor: sends through [SqsClient]. */
    constructor(sqsClient: SqsClient, queueUrl: String) : this(
        { url, body ->
            sqsClient.sendMessage(
                SendMessageRequest
                    .builder()
                    .queueUrl(url)
                    .messageBody(body)
                    .build(),
            )
        },
        queueUrl,
    )

    override fun notifyTask(task: RecoveryTask) {
        sender.send(queueUrl, messageBody(task))
    }

    /** Seam for tests; production sends through [SqsClient]. */
    fun interface MessageSender {
        fun send(
            queueUrl: String,
            body: String,
        )
    }

    companion object {
        private val MAPPER = ObjectMapper()

        internal fun messageBody(task: RecoveryTask): String =
            try {
                MAPPER.writeValueAsString(
                    RecoveryNotification(
                        storeId = task.storeId,
                        projectionName = task.projectionName,
                        projectionVersion = task.projectionVersion,
                        aggregateId = task.aggregateId,
                        seqNr = task.seqNr,
                        failureType = task.failureKind.asString(),
                    ),
                )
            } catch (e: Exception) {
                throw RecoveryException("failed to serialize a recovery notification", e)
            }
    }
}

/** Identifier-only recovery wake-up. The worker never reads the event body from this message. */
internal data class RecoveryNotification(
    val storeId: String,
    val projectionName: String,
    val projectionVersion: String,
    val aggregateId: String,
    val seqNr: Long,
    val failureType: String,
)

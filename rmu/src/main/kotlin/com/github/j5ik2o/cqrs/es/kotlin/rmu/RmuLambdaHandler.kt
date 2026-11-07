package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.model.Record

/** Applies one converted head stream record. */
fun interface StreamRecordApplier {
    fun update(record: Record): ReadModelUpdater.RecordResult
}

/**
 * The Lambda entry point for the head stream.
 *
 * It applies every record in order and returns the records Lambda must redeliver. Records of an
 * aggregate whose earlier event failed are left for redelivery: the position rule forbids advancing
 * past an unapplied event, so applying them would only fail again. A failure that was recorded as a
 * durable recovery task is not redelivered; the task owns it from then on. If recording the task
 * itself fails, the record is redelivered so the Lambda retry and OnFailure safety net still apply.
 */
class RmuLambdaHandler(
    private val applier: StreamRecordApplier,
) : RequestHandler<DynamodbEvent, BatchItemFailuresResponse> {
    /** Lambda instantiates the handler class with a no-argument constructor. */
    constructor() : this(StreamRecordApplier { record -> LambdaDependenciesHolder.instance.updater.update(record) })

    private val logger = LoggerFactory.getLogger(RmuLambdaHandler::class.java)

    override fun handleRequest(
        event: DynamodbEvent,
        context: Context,
    ): BatchItemFailuresResponse {
        val failures = mutableListOf<BatchItemFailuresResponse.BatchItemFailure>()
        val blockedAggregates = mutableSetOf<String>()
        for (record in event.records.orEmpty()) {
            val aggregateId = LambdaStreamRecords.aggregateId(record)
            if (aggregateId != null && blockedAggregates.contains(aggregateId)) {
                failures.add(failure(record))
                continue
            }
            try {
                val result = applier.update(LambdaStreamRecords.toSdkRecord(record))
                if (result == ReadModelUpdater.RecordResult.RECORDED_FAILURE && aggregateId != null) {
                    blockedAggregates.add(aggregateId)
                }
            } catch (e: RuntimeException) {
                logger.error(
                    "Failed to apply head stream record: eventId={}, error={}",
                    record.eventID,
                    e.message,
                    e,
                )
                failures.add(failure(record))
                if (aggregateId != null) {
                    blockedAggregates.add(aggregateId)
                }
            }
        }
        return BatchItemFailuresResponse(failures)
    }

    private fun failure(record: DynamodbEvent.DynamodbStreamRecord): BatchItemFailuresResponse.BatchItemFailure =
        BatchItemFailuresResponse.BatchItemFailure(LambdaStreamRecords.itemIdentifier(record))
}

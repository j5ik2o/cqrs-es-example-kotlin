package com.github.j5ik2o.cqrs.es.kotlin.rmu

/**
 * The response a Lambda handler returns for partial batch retries.
 *
 * DynamoDB Streams and SQS both read `batchItemFailures`. A stream record is identified by its
 * sequence number and an SQS message by its message id.
 */
data class BatchItemFailuresResponse(
    val batchItemFailures: List<BatchItemFailure>,
) {
    data class BatchItemFailure(
        val itemIdentifier: String,
    )
}

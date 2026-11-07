package com.github.j5ik2o.cqrs.es.kotlin.rmu

import software.amazon.awssdk.awscore.exception.AwsServiceException
import java.io.IOException
import java.io.InterruptedIOException
import java.sql.SQLException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/**
 * Splits failures into permanent data problems and retryable storage problems.
 *
 * A permanent failure is written to the recovery task store instead of being retried forever. A
 * retryable failure (a database outage, network error, or throttling response) is retried with
 * backoff. An unknown failure is treated as retryable a bounded number of times; if it still fails
 * it is parked as a recovery task.
 */
object FailureClassifier {
    private val PERMANENT_MYSQL_ERRORS = setOf(1062, 1264, 1366, 1406, 1452)

    private val RETRYABLE_AWS_ERROR_CODES =
        setOf(
            "ThrottlingException",
            "Throttling",
            "ProvisionedThroughputExceededException",
            "RequestLimitExceeded",
            "InternalServerError",
            "ServiceUnavailable",
            "ExpiredIteratorException",
            "TransactionConflictException",
            "RequestTimeout",
            "RequestTimeoutException",
        )

    /** Returns the failure kind when retrying cannot fix the record. */
    fun classify(failure: Throwable?): FailureKind? {
        var cause = failure
        while (cause != null) {
            if (cause is PermanentFailure) return cause.kind
            cause = cause.cause
        }
        cause = failure
        while (cause != null) {
            if (cause is SQLException && PERMANENT_MYSQL_ERRORS.contains(cause.errorCode)) {
                return FailureKind.READ_MODEL_CONSTRAINT
            }
            cause = cause.cause
        }
        return null
    }

    /** Returns whether the same operation may succeed later. */
    fun isRetryable(failure: Throwable?): Boolean {
        if (failure == null) return false
        if (classify(failure) != null) return false
        if (failure is InterruptedIOException) return false

        var cause = failure
        while (cause != null) {
            if (cause is InterruptedException ||
                cause is CancellationException ||
                cause is RetryInterruptedException
            ) {
                return false
            }
            cause = cause.cause
        }

        cause = failure
        while (cause != null) {
            when (cause) {
                is SQLException -> {
                    return true
                }

                is AwsServiceException -> {
                    val details = cause.awsErrorDetails()
                    if (details != null && RETRYABLE_AWS_ERROR_CODES.contains(details.errorCode())) {
                        return true
                    }
                    val statusCode = cause.statusCode()
                    if (statusCode == 429 || statusCode >= 500) {
                        return true
                    }
                }

                is IOException, is TimeoutException -> {
                    return true
                }
            }
            cause = cause.cause
        }
        return true
    }
}

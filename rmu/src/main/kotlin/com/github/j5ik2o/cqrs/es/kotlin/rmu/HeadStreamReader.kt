package com.github.j5ik2o.cqrs.es.kotlin.rmu

import java.time.Instant

/**
 * The head stream operations the subscription thread drives.
 *
 * It is an interface so the subscription loop can be started, interrupted, and observed in a test
 * without touching DynamoDB.
 */
interface HeadStreamReader {
    fun pollOnce(headTableName: String): Int

    fun reset()

    /** The time the last record was read, or null when no record has been read yet. */
    fun lastRecordTime(): Instant?

    /**
     * The time the last poll completed successfully, or null when no poll has completed since the
     * last [reset]. An empty but successful poll advances this, because an idle stream is not a
     * stopped reader.
     */
    fun lastPollTime(): Instant?
}

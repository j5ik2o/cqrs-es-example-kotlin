package com.github.j5ik2o.cqrs.es.kotlin.rmu

import java.time.Instant

/**
 * Runs the head stream poll loop on its own thread.
 *
 * [LocalRmuRunner] starts this before the initial scan, so the reader resumes the subscription
 * first and scans in parallel (DY-15). The subscription only applies records; it asks the runner
 * for a full resync by flag instead of running one itself, so two resyncs never overlap.
 */
interface StreamSubscription {
    /** Starts polling. Calling it twice is a no-op. */
    fun start()

    /** Stops polling and waits briefly for the thread to end. Calling it twice is a no-op. */
    fun stop()

    /** Drops the iterators so the next poll starts at `TRIM_HORIZON`. */
    fun reset()

    /** The time the last record was read, or null when no record has been read yet. */
    fun lastRecordTime(): Instant?

    /** True when the poller saw a trimmed stream and the runner has not handled it yet. */
    fun isResyncRequested(): Boolean

    /** Clears the trimmed-stream request after a full resync succeeds. */
    fun clearResyncRequest()
}

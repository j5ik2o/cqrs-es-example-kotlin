package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls the head stream on a dedicated thread.
 *
 * Starting it before the initial scan is what closes the retention gap DY-15 warns about. A scan
 * can run longer than the 24-hour stream retention, but a record written after the subscription
 * starts stays readable until this thread consumes it. The subscription and the scan apply through
 * the same position guard and transaction, so an event seen by both is discarded by one of them.
 */
class HeadStreamSubscription(
    private val reader: HeadStreamReader,
    private val headTableName: String,
    private val backoff: Backoff,
    private val stopTimeout: Duration = Duration.ofSeconds(5),
) : StreamSubscription {
    private val logger = LoggerFactory.getLogger(HeadStreamSubscription::class.java)
    private val running = AtomicBoolean(false)
    private val resyncRequested = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val worker = Thread({ poll() }, "head-stream-subscription")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    override fun stop() {
        val worker = thread
        running.set(false)
        if (worker == null) return
        worker.interrupt()
        try {
            worker.join(stopTimeout.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isAlive) {
            logger.warn("The head stream subscription did not stop within {}", stopTimeout)
        }
        thread = null
    }

    override fun reset() {
        reader.reset()
    }

    override fun lastRecordTime(): Instant? = reader.lastRecordTime()

    override fun isResyncRequested(): Boolean = resyncRequested.get()

    override fun clearResyncRequest() {
        resyncRequested.set(false)
    }

    private fun poll() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val processed = reader.pollOnce(headTableName)
                if (processed > 0) {
                    backoff.reset()
                } else {
                    Thread.sleep(backoff.next().toMillis())
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: StreamTrimmedException) {
                // The old iterator can no longer read the shard. Drop it and ask the runner for a
                // full resync; the scan covers the records that fell out of the retention window.
                logger.warn(
                    "The head stream was trimmed; requesting a full resync: shardId={}",
                    e.shardId,
                    e,
                )
                // Reset before raising the request, so a runner that sees the request also sees
                // the dropped iterators.
                reader.reset()
                resyncRequested.set(true)
                if (!pause()) return
            } catch (e: RuntimeException) {
                // Match the previous single-thread loop: log, back off, and keep polling. A
                // persistent failure again reaches the retention resync through the idle time.
                logger.warn("An error occurred while polling the head stream; retrying", e)
                if (!pause()) return
            }
        }
    }

    private fun pause(): Boolean =
        try {
            Thread.sleep(backoff.next().toMillis())
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}

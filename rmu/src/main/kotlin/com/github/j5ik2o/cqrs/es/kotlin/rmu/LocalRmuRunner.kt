package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * The local read model updater.
 *
 * It resumes the head Streams subscription first, then reconciles every head in parallel with the
 * subscription (DY-15). A trimmed stream, or a reader that has been stopped longer than the 24-hour
 * retention window, triggers another full resync. Pending recovery tasks are replayed at startup and
 * periodically afterwards.
 *
 * Order matters. The old order completed the scan before polling, so an update written after the
 * scan had read its head, but before polling began, could leave the 24-hour stream retention before
 * any iterator existed. Resuming the subscription first, and waiting for its first successful poll
 * before scanning, keeps every record written from then on readable while the scan runs. A trimmed
 * or stale reader is reset and the scan waits for a fresh poll for the same reason, so polling never
 * stops for the duration of a scan.
 */
@Component
@Profile("local-rmu")
class LocalRmuRunner(
    private val subscription: StreamSubscription,
    private val resyncer: Resyncer,
    private val recoveryService: RecoveryService,
    private val retryPolicy: RetryPolicy,
    private val backoff: Backoff,
    @Value("\${app.stream.head-table-name:head}") private val headTableName: String,
    @Value("\${app.stream.recovery-interval-millis:60000}")
    private val recoveryIntervalMillis: Long,
    @Value("\${app.stream.max-recovery-tasks-per-run:50}")
    private val maxRecoveryTasksPerRun: Int,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(LocalRmuRunner::class.java)
    private var lastSuccessfulResyncAt: Instant = Instant.EPOCH

    override fun run(vararg args: String) {
        logger.info("Starting the local read model updater: headTableName={}", headTableName)
        try {
            if (!reconcileStartup()) return
            recoverPending()
            var nextRecoveryAt = Instant.now().plusMillis(recoveryIntervalMillis)

            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (handleResyncRequest()) continue

                    val lastRecordTime = subscription.lastRecordTime()
                    if (shouldResync(lastRecordTime, lastSuccessfulResyncAt, Instant.now())) {
                        logger.warn(
                            "The reader has not reconciled or read a record for longer than the stream retention window; running a full resync",
                        )
                        // Drop the stale iterators first, then let the subscription read the
                        // stream again before the scan starts. The scan therefore runs alongside
                        // a live subscription instead of replacing it.
                        subscription.reset()
                        awaitStreamReads()
                        if (!resync("resync after the retention window")) {
                            sleep(backoff.next())
                            continue
                        }
                        backoff.reset()
                        continue
                    }

                    if (Instant.now().isAfter(nextRecoveryAt)) {
                        recoverPending()
                        nextRecoveryAt = Instant.now().plusMillis(recoveryIntervalMillis)
                    }

                    sleep(MONITOR_INTERVAL)
                } catch (e: RetryInterruptedException) {
                    throw e
                } catch (e: RuntimeException) {
                    logger.warn(
                        "An error has occurred, but reconciliation is restarted. If this error persists, the read model condition may be incorrect.",
                        e,
                    )
                    sleep(backoff.next())
                }
            }
        } finally {
            // Stop the subscription last, so an interrupted scan also stops polling.
            subscription.stop()
        }
    }

    /**
     * Resumes the stream subscription, waits for it to read the stream once, then scans until one
     * reconciliation completes.
     *
     * Waiting for the first poll is the point of the order: the scan must never start on a
     * subscription that has not established an iterator yet. After that the scan and the
     * subscription run concurrently. An update written while the scan visits the other aggregates
     * is applied by the subscription; an update the scan has not reached yet is seen at its new
     * position. Both paths use the same position guard and transaction, so the overlap is applied
     * once.
     */
    internal fun reconcileStartup(): Boolean {
        subscription.start()
        awaitStreamReads()
        while (!Thread.currentThread().isInterrupted) {
            if (resync("initial reconciliation")) return true
            sleep(backoff.next())
        }
        return false
    }

    /**
     * Runs the resync the subscription asked for, if any. It clears the request before the scan:
     * a trim that arrives while the scan runs raises it again, so its gap is not dropped when the
     * scan finishes. A failed attempt raises it again instead of waiting for the retention window.
     *
     * The subscription thread resets its iterators and keeps polling after a trim. Waiting for the
     * next successful poll here means the scan always runs with a live subscription. The reset
     * belongs to the subscription; repeating it after the scan would only drop fresh iterators.
     */
    internal fun handleResyncRequest(): Boolean {
        if (!subscription.isResyncRequested()) return false
        logger.warn("The stream was trimmed; running a full resync")
        subscription.clearResyncRequest()
        awaitStreamReads()
        if (!resync("resync after trimmed stream")) {
            subscription.requestResync()
            sleep(backoff.next())
            return true
        }
        backoff.reset()
        return true
    }

    /**
     * Waits until the subscription has completed a successful poll. A reset clears the marker, so
     * this only returns after a read that started after the reset.
     */
    private fun awaitStreamReads() {
        while (!Thread.currentThread().isInterrupted && subscription.lastPollTime() == null) {
            sleep(READ_RESUME_INTERVAL)
        }
    }

    private fun resync(label: String): Boolean =
        try {
            retryPolicy.run { resyncer.resync() }
            // The resync scans the current head table, so it also becomes the baseline for the
            // next retention check. This covers a reader that never read a stream record.
            lastSuccessfulResyncAt = Instant.now()
            true
        } catch (e: RetryInterruptedException) {
            throw e
        } catch (e: RuntimeException) {
            // Permanent per-aggregate failures were already recorded as recovery tasks and logged
            // inside the resyncer. A storage failure leaves the resync incomplete; the caller keeps
            // retrying it.
            logger.error("{} did not complete", label, e)
            false
        }

    private fun recoverPending() {
        try {
            val completed = recoveryService.recoverPending(maxRecoveryTasksPerRun)
            if (completed > 0) {
                logger.info("Completed pending recovery tasks: count={}", completed)
            }
        } catch (e: RetryInterruptedException) {
            throw e
        } catch (e: RuntimeException) {
            logger.warn("Some pending recovery tasks did not complete", e)
        }
    }

    private fun sleep(delay: Duration) {
        try {
            Thread.sleep(delay.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RetryInterruptedException(e)
        }
    }

    companion object {
        internal val STREAM_RETENTION: Duration = Duration.ofHours(24)
        internal val MONITOR_INTERVAL: Duration = Duration.ofSeconds(1)
        internal val READ_RESUME_INTERVAL: Duration = Duration.ofMillis(10)

        /**
         * Returns whether the reader should reconcile every head again. The baseline is the
         * later of the last record read and the last successful resync, so a reader that never
         * completed a page still resyncs after the retention window.
         */
        internal fun shouldResync(
            lastRecordTime: Instant?,
            lastSuccessfulResyncAt: Instant,
            now: Instant,
        ): Boolean {
            val lastActivity = maxOf(lastRecordTime ?: Instant.MIN, lastSuccessfulResyncAt)
            return lastActivity.isBefore(now.minus(STREAM_RETENTION))
        }
    }
}

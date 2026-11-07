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
 * It reconciles every head on startup, then follows the head Streams. A trimmed stream, or a reader
 * that has been stopped longer than the 24-hour retention window, triggers another full resync
 * (DY-15). Pending recovery tasks are replayed at startup and periodically afterwards.
 */
@Component
@Profile("local-rmu")
class LocalRmuRunner(
    private val poller: HeadStreamPoller,
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

    override fun run(vararg args: String) {
        logger.info("Starting the local read model updater: headTableName={}", headTableName)

        // The first start has no positions at all, and a restart may follow a stop longer than the
        // stream retention window. Reconcile every head before consuming the stream; the stream
        // then covers the gap between the scan and the first read. Do not start polling until the
        // baseline scan completes.
        while (!Thread.currentThread().isInterrupted) {
            if (resync("initial reconciliation")) break
            sleep(backoff.next())
        }
        recoverPending()
        var nextRecoveryAt = Instant.now().plusMillis(recoveryIntervalMillis)

        while (!Thread.currentThread().isInterrupted) {
            try {
                val lastRecordTime = poller.lastRecordTime()
                if (lastRecordTime != null &&
                    lastRecordTime.isBefore(Instant.now().minus(STREAM_RETENTION))
                ) {
                    logger.warn(
                        "The reader was stopped longer than the stream retention window; running a full resync",
                    )
                    if (!resync("resync after the retention window")) {
                        sleep(backoff.next())
                        continue
                    }
                    poller.reset()
                    backoff.reset()
                }

                val processed = poller.pollOnce(headTableName)
                if (processed > 0) {
                    backoff.reset()
                } else {
                    sleep(backoff.next())
                }

                if (Instant.now().isAfter(nextRecoveryAt)) {
                    recoverPending()
                    nextRecoveryAt = Instant.now().plusMillis(recoveryIntervalMillis)
                }
            } catch (e: StreamTrimmedException) {
                logger.warn(
                    "The reader fell behind the stream retention window; running a full resync: shardId={}",
                    e.shardId,
                    e,
                )
                if (!resync("resync after trimmed stream")) {
                    sleep(backoff.next())
                    continue
                }
                poller.reset()
                backoff.reset()
            } catch (e: RetryInterruptedException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "An error has occurred, but stream processing is restarted. If this error persists, the read model condition may be incorrect.",
                    e,
                )
                sleep(backoff.next())
            }
        }
    }

    private fun resync(label: String): Boolean =
        try {
            retryPolicy.run { resyncer.resync() }
            true
        } catch (e: RetryInterruptedException) {
            throw e
        } catch (e: RuntimeException) {
            // Permanent per-aggregate failures were already recorded as recovery tasks and logged
            // inside the resyncer. A storage failure leaves the resync incomplete; the caller keeps
            // retrying it before polling again.
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
        private val STREAM_RETENTION: Duration = Duration.ofHours(24)
    }
}

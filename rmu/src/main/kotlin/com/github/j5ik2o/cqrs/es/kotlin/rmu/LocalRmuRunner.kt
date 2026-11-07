package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * The local read model updater.
 *
 * It follows the head table's Streams. The first iterator starts at `TRIM_HORIZON`; the positions
 * drop records that were already applied. A failure is retried with exponential backoff, and the
 * poller resumes after the last successfully applied record.
 */
@Component
@Profile("local-rmu")
class LocalRmuRunner(
    private val poller: HeadStreamPoller,
    @Value("\${app.stream.head-table-name:head}") private val headTableName: String,
    @Value("\${app.stream.poll-backoff-initial-millis:500}")
    private val initialBackoffMillis: Long,
    @Value("\${app.stream.poll-backoff-max-millis:30000}")
    private val maxBackoffMillis: Long,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(LocalRmuRunner::class.java)

    override fun run(vararg args: String) {
        var backoffMillis = initialBackoffMillis
        logger.info("Starting the local read model updater: headTableName={}", headTableName)
        while (!Thread.currentThread().isInterrupted) {
            try {
                val processed = poller.pollOnce(headTableName)
                if (processed > 0) {
                    backoffMillis = initialBackoffMillis
                } else {
                    sleep(backoffMillis)
                    backoffMillis = nextBackoff(backoffMillis)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                logger.warn("Read model updater poll failed; retrying", e)
                sleep(backoffMillis)
                backoffMillis = nextBackoff(backoffMillis)
            }
        }
    }

    private fun nextBackoff(current: Long): Long = minOf(current * 2, maxBackoffMillis)

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }
}

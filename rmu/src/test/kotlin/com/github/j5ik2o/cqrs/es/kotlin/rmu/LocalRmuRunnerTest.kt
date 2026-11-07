package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRmuRunnerTest {
    private val now = Instant.parse("2026-01-02T00:00:00Z")

    @Test
    fun `resyncs when no record was read and the last resync is old`() {
        assertTrue(
            LocalRmuRunner.shouldResync(
                lastRecordTime = null,
                lastSuccessfulResyncAt = now.minus(Duration.ofHours(25)),
                now = now,
            ),
        )
    }

    @Test
    fun `does not resync right after a successful resync even without records`() {
        assertFalse(
            LocalRmuRunner.shouldResync(
                lastRecordTime = null,
                lastSuccessfulResyncAt = now.minus(Duration.ofHours(1)),
                now = now,
            ),
        )
    }

    @Test
    fun `does not resync when a record was read recently`() {
        assertFalse(
            LocalRmuRunner.shouldResync(
                lastRecordTime = now.minus(Duration.ofHours(1)),
                lastSuccessfulResyncAt = now.minus(Duration.ofHours(25)),
                now = now,
            ),
        )
    }

    @Test
    fun `resyncs when neither a record nor a resync happened within the retention window`() {
        assertTrue(
            LocalRmuRunner.shouldResync(
                lastRecordTime = now.minus(Duration.ofHours(25)),
                lastSuccessfulResyncAt = now.minus(Duration.ofHours(25)),
                now = now,
            ),
        )
    }
}

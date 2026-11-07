package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import java.io.IOException
import java.sql.SQLException
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailureClassifierTest {
    @Test
    fun `classifies a permanent failure`() {
        val failure = PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad payload")

        assertEquals(FailureKind.EVENT_PAYLOAD, FailureClassifier.classify(failure))
        assertFalse(FailureClassifier.isRetryable(failure))
    }

    @Test
    fun `classifies a permanent MySQL constraint error`() {
        val failure = SQLException("duplicate", "23000", 1062)

        assertEquals(FailureKind.READ_MODEL_CONSTRAINT, FailureClassifier.classify(failure))
        assertFalse(FailureClassifier.isRetryable(failure))
    }

    @Test
    fun `retries a MySQL lock conflict`() {
        val failure = SQLException("deadlock", "40001", 1213)

        assertNull(FailureClassifier.classify(failure))
        assertTrue(FailureClassifier.isRetryable(failure))
    }

    @Test
    fun `retries an IO failure`() {
        assertTrue(FailureClassifier.isRetryable(IOException("connection reset")))
    }

    @Test
    fun `does not retry a cancellation`() {
        assertFalse(FailureClassifier.isRetryable(CancellationException("cancelled")))
    }

    @Test
    fun `does not retry an interrupted retry wait`() {
        assertFalse(
            FailureClassifier.isRetryable(
                RetryInterruptedException(InterruptedException("stop")),
            ),
        )
    }

    @Test
    fun `treats an unknown failure as retryable until the caller parks it`() {
        assertTrue(FailureClassifier.isRetryable(RuntimeException("unknown")))
    }
}

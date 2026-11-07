package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.amazonaws.services.lambda.runtime.events.SQSEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryWorkerTest {
    @Test
    fun `drains bounded batches until fewer tasks remain`() {
        val calls = mutableListOf<Int>()
        val results = ArrayDeque(listOf(2, 2, 1))
        val worker =
            RecoveryWorker(recovery = { maxTasks ->
                calls.add(maxTasks)
                results.removeFirst()
            }, maxTasksPerRun = 2)

        val response = worker.handleRequest(sqsEvent("m1"), testContext())

        assertEquals(listOf(2, 2, 2), calls)
        assertTrue(response.batchItemFailures.isEmpty())
    }

    @Test
    fun `caps at ten batches and redelivers the messages`() {
        val calls = mutableListOf<Int>()
        val worker =
            RecoveryWorker(recovery = { maxTasks ->
                calls.add(maxTasks)
                maxTasks
            }, maxTasksPerRun = 2)

        val response = worker.handleRequest(sqsEvent("m1", "m2"), testContext())

        assertEquals(10, calls.size)
        assertEquals(setOf("m1", "m2"), response.batchItemFailures.map { it.itemIdentifier }.toSet())
    }

    @Test
    fun `a failed run redelivers every message`() {
        val worker = RecoveryWorker(recovery = { throw RecoveryException("boom") }, maxTasksPerRun = 2)

        val response = worker.handleRequest(sqsEvent("m1", "m2"), testContext())

        assertEquals(setOf("m1", "m2"), response.batchItemFailures.map { it.itemIdentifier }.toSet())
    }

    @Test
    fun `a failed manual invocation is thrown`() {
        val worker = RecoveryWorker(recovery = { throw RecoveryException("boom") }, maxTasksPerRun = 2)

        assertThrows<RecoveryException> { worker.handleRequest(SQSEvent(), testContext()) }
    }

    private fun sqsEvent(vararg messageIds: String): SQSEvent =
        SQSEvent().apply {
            records = messageIds.map { messageId -> SQSEvent.SQSMessage().apply { this.messageId = messageId } }
        }
}

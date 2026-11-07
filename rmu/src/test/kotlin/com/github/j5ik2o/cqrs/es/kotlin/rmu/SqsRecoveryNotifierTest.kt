package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SqsRecoveryNotifierTest {
    private val mapper = ObjectMapper()

    @Test
    fun `sends identifiers only`() {
        var sentQueueUrl: String? = null
        var sentBody: String? = null
        val notifier =
            SqsRecoveryNotifier(
                { queueUrl, body ->
                    sentQueueUrl = queueUrl
                    sentBody = body
                },
                "https://sqs.example/recovery",
            )
        val task =
            RecoveryTask(
                storeId = "store-1",
                projectionName = Projection.NAME,
                projectionVersion = Projection.VERSION,
                aggregateId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z",
                seqNr = 3,
                failureKind = FailureKind.EVENT_PAYLOAD,
                errorMessage = "the event body must not travel over SQS",
                status = RecoveryTask.STATUS_PENDING,
            )

        notifier.notifyTask(task)

        assertEquals("https://sqs.example/recovery", sentQueueUrl)
        val body = requireNotNull(sentBody)
        val node = mapper.readTree(body)
        assertEquals("store-1", node["storeId"].asText())
        assertEquals(Projection.NAME, node["projectionName"].asText())
        assertEquals(Projection.VERSION, node["projectionVersion"].asText())
        assertEquals("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z", node["aggregateId"].asText())
        assertEquals(3L, node["seqNr"].asLong())
        assertEquals("event_payload", node["failureType"].asText())
        assertFalse(node.has("payload"))
        assertFalse(node.has("errorMessage"))
        assertFalse(body.contains("the event body must not travel over SQS"))
    }
}

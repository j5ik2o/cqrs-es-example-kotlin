package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LambdaStreamRecordsTest {
    @Test
    fun `converts a head record into the SDK model`() {
        val record = eventRecord("INSERT", "100", AGGREGATE_ID, listOf(1L, 2L))

        val sdkRecord = LambdaStreamRecords.toSdkRecord(record)
        val head = HeadRecordParser.parse(sdkRecord)

        assertEquals(AGGREGATE_ID, head?.aggregateId)
        assertEquals(listOf(1L, 2L), head?.events?.map { it.seqNr })
        assertEquals(
            "{}",
            head
                ?.events
                ?.first()
                ?.payload
                ?.toString(Charsets.UTF_8),
        )
        assertEquals("100", sdkRecord.dynamodb().sequenceNumber())
    }

    @Test
    fun `skips a remove record without reading its image`() {
        val record = eventRecord("REMOVE", "101", AGGREGATE_ID, emptyList())

        val sdkRecord = LambdaStreamRecords.toSdkRecord(record)

        assertNull(HeadRecordParser.parse(sdkRecord))
    }

    @Test
    fun `reads the aggregate id from the lambda model`() {
        val groupChat = eventRecord("MODIFY", "102", AGGREGATE_ID, listOf(1L))
        val config = eventRecord("MODIFY", "103", "__config__", listOf(1L))

        assertEquals(AGGREGATE_ID, LambdaStreamRecords.aggregateId(groupChat))
        assertNull(LambdaStreamRecords.aggregateId(config))
    }

    @Test
    fun `uses the sequence number as the batch item identifier`() {
        val record = eventRecord("INSERT", "104", AGGREGATE_ID, listOf(1L))

        assertEquals("104", LambdaStreamRecords.itemIdentifier(record))
    }

    @Test
    fun `blocks later records of a failed aggregate`() {
        val applied = mutableListOf<String>()
        val handler =
            RmuLambdaHandler { record ->
                val aggregateId =
                    record
                        .dynamodb()
                        .newImage()["aid"]
                        ?.s()
                        .orEmpty()
                applied.add(aggregateId)
                if (aggregateId == AGGREGATE_ID) {
                    ReadModelUpdater.RecordResult.RECORDED_FAILURE
                } else {
                    ReadModelUpdater.RecordResult.APPLIED
                }
            }
        val event =
            DynamodbEvent().apply {
                records =
                    listOf(
                        eventRecord("INSERT", "1", AGGREGATE_ID, listOf(1L)),
                        eventRecord("INSERT", "2", AGGREGATE_ID, listOf(2L)),
                        eventRecord("INSERT", "3", OTHER_AGGREGATE_ID, listOf(1L)),
                    )
            }

        val response = handler.handleRequest(event, testContext())

        assertEquals(listOf(AGGREGATE_ID, OTHER_AGGREGATE_ID), applied)
        // The first record's failure is durable, so only the later blocked record is redelivered.
        assertEquals(listOf("2"), response.batchItemFailures.map { it.itemIdentifier })
    }

    @Test
    fun `redelivers a record that throws`() {
        val handler = RmuLambdaHandler { throw PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad") }
        val event =
            DynamodbEvent().apply {
                records = listOf(eventRecord("INSERT", "105", AGGREGATE_ID, listOf(1L)))
            }

        val response = handler.handleRequest(event, testContext())

        assertEquals(listOf("105"), response.batchItemFailures.map { it.itemIdentifier })
        assertTrue(response.batchItemFailures.isNotEmpty())
    }

    private fun eventRecord(
        eventName: String,
        sequenceNumber: String,
        aggregateId: String,
        seqNrs: List<Long>,
    ): DynamodbEvent.DynamodbStreamRecord =
        DynamodbEvent.DynamodbStreamRecord().apply {
            this.eventName = eventName
            this.eventID = "event-$sequenceNumber"
            this.dynamodb =
                StreamRecord().apply {
                    this.sequenceNumber = sequenceNumber
                    this.newImage = headImage(aggregateId, seqNrs)
                }
        }

    private fun headImage(
        aggregateId: String,
        seqNrs: List<Long>,
    ): Map<String, AttributeValue> =
        mapOf(
            "aid" to stringValue(aggregateId),
            "seq_nr" to numberValue(seqNrs.lastOrNull() ?: 0L),
            "events" to
                AttributeValue().apply {
                    setL(
                        seqNrs.map { seqNr ->
                            AttributeValue().apply {
                                setM(
                                    mapOf(
                                        "seq_nr" to numberValue(seqNr),
                                        "occurred_at" to numberValue(seqNr * 1_000),
                                        "manifest" to stringValue("Created"),
                                        "payload" to
                                            AttributeValue().apply {
                                                b = ByteBuffer.wrap("{}".toByteArray())
                                            },
                                    ),
                                )
                            }
                        },
                    )
                },
        )

    private fun stringValue(value: String): AttributeValue = AttributeValue().apply { s = value }

    private fun numberValue(value: Long): AttributeValue = AttributeValue().apply { n = value.toString() }

    private companion object {
        private const val AGGREGATE_ID = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z"
        private const val OTHER_AGGREGATE_ID = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Y"
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.OperationType
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.StreamRecord
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadRecordParserTest {
    @Test
    fun `skips the configuration item`() {
        val record = headRecord(HeadRecordParser.CONFIG_AGGREGATE_ID, listOf(1L to payload("created")))

        assertNull(HeadRecordParser.parse(record))
    }

    @Test
    fun `skips other aggregate types`() {
        val record = headRecord("UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV", listOf(1L to payload("x")))

        assertNull(HeadRecordParser.parse(record))
    }

    @Test
    fun `skips removed records`() {
        val record =
            headRecord(
                "GroupChat-01ARZ3NDEKTSV4RRFFQ69G5FAV",
                listOf(1L to payload("x")),
                OperationType.REMOVE,
            )

        assertNull(HeadRecordParser.parse(record))
    }

    @Test
    fun `parses and sorts the events of an insert record`() {
        val record =
            headRecord(
                "GroupChat-01ARZ3NDEKTSV4RRFFQ69G5FAV",
                listOf(3L to payload("third"), 1L to payload("first"), 2L to payload("second")),
            )

        val head = HeadRecordParser.parse(record)

        assertEquals("GroupChat-01ARZ3NDEKTSV4RRFFQ69G5FAV", head?.aggregateId)
        assertEquals(listOf(1L, 2L, 3L), head?.events?.map { it.seqNr })
        assertEquals(
            "first",
            head
                ?.events
                ?.first()
                ?.payload
                ?.toString(Charsets.UTF_8),
        )
        assertEquals("GroupChatCreated@1", head?.events?.first()?.manifest)
    }

    @Test
    fun `rejects a record without events`() {
        val record =
            Record
                .builder()
                .eventName(OperationType.INSERT)
                .dynamodb(
                    StreamRecord
                        .builder()
                        .newImage(
                            mapOf(
                                "aid" to AttributeValue.fromS("GroupChat-01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                                "seq_nr" to AttributeValue.fromN("1"),
                            ),
                        ).build(),
                ).build()

        assertThrows<IllegalArgumentException> { HeadRecordParser.parse(record) }
    }

    @Test
    fun `rejects an event without a payload`() {
        val record =
            Record
                .builder()
                .eventName(OperationType.MODIFY)
                .dynamodb(
                    StreamRecord
                        .builder()
                        .newImage(
                            mapOf(
                                "aid" to AttributeValue.fromS("GroupChat-01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                                "seq_nr" to AttributeValue.fromN("1"),
                                "events" to
                                    AttributeValue.fromL(
                                        listOf(
                                            AttributeValue.fromM(
                                                mapOf(
                                                    "seq_nr" to AttributeValue.fromN("1"),
                                                    "occurred_at" to AttributeValue.fromN("0"),
                                                ),
                                            ),
                                        ),
                                    ),
                            ),
                        ).build(),
                ).build()

        assertThrows<IllegalArgumentException> { HeadRecordParser.parse(record) }
    }

    private fun headRecord(
        aid: String,
        events: List<Pair<Long, ByteArray>>,
        eventName: OperationType = OperationType.INSERT,
    ): Record =
        Record
            .builder()
            .eventName(eventName)
            .dynamodb(
                StreamRecord
                    .builder()
                    .newImage(
                        mapOf(
                            "aid" to AttributeValue.fromS(aid),
                            "seq_nr" to AttributeValue.fromN(events.last().first.toString()),
                            "events" to
                                AttributeValue.fromL(
                                    events.map { (seqNr, bytes) ->
                                        AttributeValue.fromM(
                                            mapOf(
                                                "seq_nr" to AttributeValue.fromN(seqNr.toString()),
                                                "occurred_at" to AttributeValue.fromN("0"),
                                                "manifest" to
                                                    AttributeValue.fromS("GroupChatCreated@1"),
                                                "payload" to
                                                    AttributeValue.fromB(
                                                        SdkBytes.fromByteArray(bytes),
                                                    ),
                                            ),
                                        )
                                    },
                                ),
                        ),
                    ).build(),
            ).build()

    private fun payload(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
}

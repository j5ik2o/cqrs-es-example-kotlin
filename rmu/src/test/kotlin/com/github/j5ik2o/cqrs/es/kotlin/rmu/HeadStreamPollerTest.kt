package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.StreamRecord
import kotlin.test.assertEquals

class HeadStreamPollerTest {
    @Test
    fun `reads every page of a shard and stops at the end`() {
        val client =
            FakeHeadStreamClient(
                listOf(
                    page(listOf(record("1")), "iterator-1"),
                    page(listOf(record("2")), null),
                ),
            )
        val handled = mutableListOf<String>()
        val poller = HeadStreamPoller(client, { record -> handled.add(record.dynamodb().sequenceNumber()) })

        val processed = poller.pollOnce("head")

        assertEquals(2, processed)
        assertEquals(listOf("1", "2"), handled)
        assertEquals(2, client.getRecordsCalls)
    }

    @Test
    fun `does not read a completed shard again`() {
        val client =
            FakeHeadStreamClient(
                listOf(
                    page(listOf(record("1")), null),
                ),
            )
        val handled = mutableListOf<String>()
        val poller = HeadStreamPoller(client, { record -> handled.add(record.dynamodb().sequenceNumber()) })

        poller.pollOnce("head")
        val second = poller.pollOnce("head")

        assertEquals(0, second)
        assertEquals(listOf("1"), handled)
        assertEquals(1, client.getRecordsCalls)
    }

    private fun record(sequenceNumber: String): Record =
        Record
            .builder()
            .eventName("INSERT")
            .dynamodb(StreamRecord.builder().sequenceNumber(sequenceNumber).build())
            .build()

    private fun page(
        records: List<Record>,
        next: String?,
    ): StreamRecordPage = StreamRecordPage(records, records.map { it.dynamodb().sequenceNumber() }, next)

    private class FakeHeadStreamClient(
        private val pages: List<StreamRecordPage>,
    ) : HeadStreamClient {
        var getRecordsCalls = 0
        private var index = 0

        override fun streamArn(headTableName: String): String = "arn:aws:dynamodb:test"

        override fun describeShards(
            streamArn: String,
            exclusiveStartShardId: String?,
        ): ShardPage = ShardPage(listOf("shard-1"), null)

        override fun getRecords(
            streamArn: String,
            shardIterator: String,
            limit: Int,
        ): StreamRecordPage {
            getRecordsCalls++
            val page = pages[index]
            if (page.nextShardIterator != null) index++
            return page
        }

        override fun shardIteratorAtTrimHorizon(
            streamArn: String,
            shardId: String,
        ): String = "trim-horizon"

        override fun shardIteratorAfterSequenceNumber(
            streamArn: String,
            shardId: String,
            sequenceNumber: String,
        ): String = "after-$sequenceNumber"
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.StreamRecord
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException
import kotlin.test.assertEquals

class HeadStreamPollerTest {
    @Test
    fun `reads every page of a shard and stops at the end`() {
        val client =
            FakeHeadStreamClient(
                shardPages = listOf(ShardPage(listOf("shard-1"), null)),
                pagesByIterator =
                    mapOf(
                        "trim-shard-1" to page(listOf(record("1")), "iterator-1"),
                        "iterator-1" to page(listOf(record("2")), null),
                    ),
            )
        val handled = mutableListOf<String>()
        val poller = newPoller(client, handled)

        val processed = poller.pollOnce("head")

        assertEquals(2, processed)
        assertEquals(listOf("1", "2"), handled)
        assertEquals(2, client.getRecordsCalls)
    }

    @Test
    fun `does not read a completed shard again`() {
        val client =
            FakeHeadStreamClient(
                shardPages = listOf(ShardPage(listOf("shard-1"), null)),
                pagesByIterator = mapOf("trim-shard-1" to page(listOf(record("1")), null)),
            )
        val handled = mutableListOf<String>()
        val poller = newPoller(client, handled)

        poller.pollOnce("head")
        val second = poller.pollOnce("head")

        assertEquals(0, second)
        assertEquals(listOf("1"), handled)
        assertEquals(1, client.getRecordsCalls)
    }

    @Test
    fun `retries the failed record from the last successful position`() {
        val client =
            FakeHeadStreamClient(
                shardPages = listOf(ShardPage(listOf("shard-1"), null)),
                pagesByIterator =
                    mapOf(
                        "trim-shard-1" to page(listOf(record("1"), record("2")), "iterator-1"),
                        "after-shard-1-1" to page(listOf(record("2"), record("3")), null),
                    ),
            )
        val handled = mutableListOf<String>()
        var failed = false
        val poller =
            HeadStreamPoller(
                client,
                { record ->
                    val sequenceNumber = record.dynamodb().sequenceNumber()
                    if (sequenceNumber == "2" && !failed) {
                        failed = true
                        throw IllegalStateException("boom")
                    }
                    handled.add(sequenceNumber)
                },
            )

        assertThrows<IllegalStateException> { poller.pollOnce("head") }
        assertEquals(listOf("1"), handled)
        assertEquals(listOf("shard-1" to "1"), client.afterSequenceRequests)

        val processed = poller.pollOnce("head")

        assertEquals(2, processed)
        assertEquals(listOf("1", "2", "3"), handled)
    }

    @Test
    fun `pages through every shard in describeShards`() {
        val client =
            FakeHeadStreamClient(
                shardPages =
                    listOf(
                        ShardPage(listOf("shard-1"), "shard-1"),
                        ShardPage(listOf("shard-2"), null),
                    ),
                pagesByIterator =
                    mapOf(
                        "trim-shard-1" to page(listOf(record("1")), null),
                        "trim-shard-2" to page(listOf(record("2")), null),
                    ),
            )
        val handled = mutableListOf<String>()
        val poller = newPoller(client, handled)

        val processed = poller.pollOnce("head")

        assertEquals(2, processed)
        assertEquals(listOf("1", "2"), handled)
        assertEquals(2, client.describeShardsCalls)
    }

    @Test
    fun `stops reading a shard after the page limit`() {
        val client =
            FakeHeadStreamClient(
                shardPages = listOf(ShardPage(listOf("shard-1"), null)),
                pagesByIterator =
                    mapOf(
                        "trim-shard-1" to page(listOf(record("1")), "iterator-1"),
                        "iterator-1" to page(listOf(record("2")), "iterator-2"),
                        "iterator-2" to page(listOf(record("3")), null),
                    ),
            )
        val handled = mutableListOf<String>()
        val poller =
            HeadStreamPoller(
                client,
                { record -> handled.add(record.dynamodb().sequenceNumber()) },
                maxItemCount = 32,
                maxPagesPerShard = 2,
            )

        val processed = poller.pollOnce("head")

        assertEquals(2, processed)
        assertEquals(listOf("1", "2"), handled)
        assertEquals(2, client.getRecordsCalls)

        val next = poller.pollOnce("head")

        assertEquals(1, next)
        assertEquals(listOf("1", "2", "3"), handled)
    }

    @Test
    fun `resumes an expired iterator after the last sequence number`() {
        val client =
            FakeHeadStreamClient(
                shardPages = listOf(ShardPage(listOf("shard-1"), null)),
                pagesByIterator =
                    mapOf(
                        "trim-shard-1" to page(listOf(record("1")), "iterator-1"),
                        "after-shard-1-1" to page(listOf(record("2")), null),
                    ),
                failuresByIterator =
                    mapOf(
                        "iterator-1" to
                            ExpiredIteratorException.builder().message("expired").build(),
                    ),
            )
        val handled = mutableListOf<String>()
        val poller = newPoller(client, handled)

        val processed = poller.pollOnce("head")

        assertEquals(2, processed)
        assertEquals(listOf("1", "2"), handled)
        assertEquals(listOf("shard-1" to "1"), client.afterSequenceRequests)
    }

    @Test
    fun `signals a full resync when the stream was trimmed`() {
        val client =
            FakeHeadStreamClient(
                shardPages = listOf(ShardPage(listOf("shard-1"), null)),
                pagesByIterator =
                    mapOf("trim-shard-1" to page(listOf(record("1")), "iterator-1")),
                failuresByIterator =
                    mapOf(
                        "iterator-1" to
                            TrimmedDataAccessException.builder().message("trimmed").build(),
                    ),
            )
        val handled = mutableListOf<String>()
        val poller = newPoller(client, handled)

        val failure = assertThrows<StreamTrimmedException> { poller.pollOnce("head") }

        assertEquals("shard-1", failure.shardId)
        assertEquals(listOf("1"), handled)
    }

    private fun newPoller(
        client: HeadStreamClient,
        handled: MutableList<String>,
    ): HeadStreamPoller = HeadStreamPoller(client, { record -> handled.add(record.dynamodb().sequenceNumber()) })

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
        private val shardPages: List<ShardPage>,
        private val pagesByIterator: Map<String, StreamRecordPage>,
        private val failuresByIterator: Map<String, RuntimeException> = emptyMap(),
    ) : HeadStreamClient {
        var describeShardsCalls = 0
        var getRecordsCalls = 0
        val iteratorsRequested = mutableListOf<String>()
        val afterSequenceRequests = mutableListOf<Pair<String, String>>()
        private var shardIndex = 0

        override fun streamArn(headTableName: String): String = "arn:aws:dynamodb:test"

        override fun describeShards(
            streamArn: String,
            exclusiveStartShardId: String?,
        ): ShardPage {
            val page = shardPages[shardIndex]
            if (page.lastEvaluatedShardId != null) {
                shardIndex++
            }
            describeShardsCalls++
            return page
        }

        override fun getRecords(
            streamArn: String,
            shardIterator: String,
            limit: Int,
        ): StreamRecordPage {
            getRecordsCalls++
            iteratorsRequested.add(shardIterator)
            failuresByIterator[shardIterator]?.let { throw it }
            return pagesByIterator[shardIterator]
                ?: throw IllegalStateException("unexpected iterator $shardIterator")
        }

        override fun shardIteratorAtTrimHorizon(
            streamArn: String,
            shardId: String,
        ): String = "trim-$shardId"

        override fun shardIteratorAfterSequenceNumber(
            streamArn: String,
            shardId: String,
            sequenceNumber: String,
        ): String {
            afterSequenceRequests.add(shardId to sequenceNumber)
            return "after-$shardId-$sequenceNumber"
        }
    }
}

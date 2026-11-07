package com.github.j5ik2o.cqrs.es.kotlin.rmu

import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException
import java.time.Instant

/**
 * Polls every shard of the head table's stream.
 *
 * The first iterator starts at `TRIM_HORIZON`, so a restart replays the retained records and the
 * read model positions drop what was already applied. An expired iterator resumes after the last
 * processed sequence number. A trimmed shard raises [StreamTrimmedException] so the caller runs a
 * full resync. When a handler fails, the shard iterator is reset to the last successfully processed
 * record so the failed record is retried on the next poll.
 */
class HeadStreamPoller(
    private val client: HeadStreamClient,
    private val handler: (Record) -> Unit,
    private val maxItemCount: Int = 32,
    private val maxPagesPerShard: Int = 10,
) : HeadStreamReader {
    private val iterators = mutableMapOf<String, String>()
    private val lastSequenceNumbers = mutableMapOf<String, String>()
    private val completedShards = mutableSetOf<String>()

    @Volatile
    private var lastRecordTime: Instant? = null

    @Synchronized
    override fun pollOnce(headTableName: String): Int {
        val streamArn = client.streamArn(headTableName)
        var processed = 0
        var lastEvaluatedShardId: String? = null
        do {
            val shardPage = client.describeShards(streamArn, lastEvaluatedShardId)
            for (shardId in shardPage.shardIds) {
                if (completedShards.contains(shardId)) {
                    continue
                }
                var iterator = iterators[shardId] ?: trimHorizonIterator(streamArn, shardId)
                var pages = 0
                while (true) {
                    val page =
                        try {
                            client.getRecords(streamArn, iterator, maxItemCount)
                        } catch (e: ExpiredIteratorException) {
                            iterator = nextIteratorAfterLastSequence(streamArn, shardId)
                            continue
                        } catch (e: TrimmedDataAccessException) {
                            throw StreamTrimmedException(shardId, e)
                        }
                    for (record in page.records) {
                        try {
                            handler(record)
                        } catch (e: RuntimeException) {
                            // Retry the failed record on the next poll. The positions rule makes
                            // the already applied records safe to read again.
                            iterators[shardId] = nextIteratorAfterLastSequence(streamArn, shardId)
                            throw e
                        }
                        lastSequenceNumbers[shardId] = record.dynamodb().sequenceNumber()
                        processed++
                    }
                    if (page.records.isNotEmpty()) {
                        lastRecordTime = Instant.now()
                    }
                    val nextIterator = page.nextShardIterator
                    if (nextIterator == null) {
                        iterators.remove(shardId)
                        completedShards.add(shardId)
                        break
                    }
                    iterators[shardId] = nextIterator
                    iterator = nextIterator
                    pages++
                    if (page.records.isEmpty() || pages >= maxPagesPerShard) {
                        break
                    }
                }
            }
            lastEvaluatedShardId = shardPage.lastEvaluatedShardId
        } while (lastEvaluatedShardId != null)
        return processed
    }

    @Synchronized
    override fun reset() {
        iterators.clear()
        lastSequenceNumbers.clear()
        completedShards.clear()
        lastRecordTime = null
    }

    override fun lastRecordTime(): Instant? = lastRecordTime

    private fun nextIteratorAfterLastSequence(
        streamArn: String,
        shardId: String,
    ): String {
        val lastSequenceNumber = lastSequenceNumbers[shardId]
        return if (lastSequenceNumber == null) {
            trimHorizonIterator(streamArn, shardId)
        } else {
            try {
                client.shardIteratorAfterSequenceNumber(streamArn, shardId, lastSequenceNumber)
            } catch (e: TrimmedDataAccessException) {
                throw StreamTrimmedException(shardId, e)
            }
        }
    }

    private fun trimHorizonIterator(
        streamArn: String,
        shardId: String,
    ): String =
        try {
            client.shardIteratorAtTrimHorizon(streamArn, shardId)
        } catch (e: TrimmedDataAccessException) {
            throw StreamTrimmedException(shardId, e)
        }
}

package com.github.j5ik2o.cqrs.es.kotlin.rmu

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import java.time.Instant

/** Reads journal events with a strongly consistent query. */
class DynamoDbJournalReader(
    private val client: DynamoDbClient,
    private val journalTableName: String,
) : JournalReader {
    override fun readEvents(
        aggregateId: String,
        fromSeqNr: Long,
        toSeqNr: Long,
    ): List<HeadEvent> {
        if (fromSeqNr > toSeqNr) return emptyList()
        val request =
            QueryRequest
                .builder()
                .tableName(journalTableName)
                .consistentRead(true)
                .scanIndexForward(true)
                .keyConditionExpression("aid = :aid AND seq_nr BETWEEN :from_seq_nr AND :to_seq_nr")
                .expressionAttributeValues(
                    mapOf(
                        ":aid" to AttributeValue.fromS(aggregateId),
                        ":from_seq_nr" to AttributeValue.fromN(fromSeqNr.toString()),
                        ":to_seq_nr" to AttributeValue.fromN(toSeqNr.toString()),
                    ),
                ).build()

        val events = mutableListOf<HeadEvent>()
        var response = client.query(request)
        while (true) {
            for (item in response.items()) {
                events.add(toHeadEvent(aggregateId, item))
            }
            val lastKey = response.lastEvaluatedKey()
            if (lastKey.isNullOrEmpty()) break
            response = client.query(request.toBuilder().exclusiveStartKey(lastKey).build())
        }
        return events
    }

    private fun toHeadEvent(
        aggregateId: String,
        item: Map<String, AttributeValue>,
    ): HeadEvent {
        val seqNr =
            item["seq_nr"]?.n()?.toLong()
                ?: throw ReadModelUpdateException("journal item for $aggregateId has no seq_nr")
        val occurredAtNanos =
            item["occurred_at"]?.n()?.toLong()
                ?: throw ReadModelUpdateException(
                    "journal item for $aggregateId has no occurred_at",
                )
        val manifest = item["manifest"]?.s() ?: ""
        val payload =
            item["payload"]?.let { it.b()?.asByteArray() ?: it.s()?.toByteArray() }
                ?: throw ReadModelUpdateException("journal item for $aggregateId has no payload")
        return HeadEvent(
            seqNr = seqNr,
            occurredAt = Instant.ofEpochSecond(0, occurredAtNanos),
            manifest = manifest,
            payload = payload,
        )
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.rmu

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.ScanRequest

/**
 * Scans the head table with strong consistency for DY-15 resynchronisation.
 *
 * The configuration item is filtered out of the scan, and the store identifier is read separately
 * from `__config__` so a recovery task cannot be replayed against another store's journal.
 */
class DynamoDbHeadScanner(
    private val client: DynamoDbClient,
    private val tableName: String,
    private val pageLimit: Int? = null,
) : HeadScanner,
    StoreIdProvider {
    @Volatile
    private var cachedStoreId: String? = null

    override fun scanHeads(visitor: (Head) -> Unit) {
        val builder =
            ScanRequest
                .builder()
                .tableName(tableName)
                .consistentRead(true)
                .projectionExpression("aid, seq_nr, type_name")
                .filterExpression("aid <> :config")
                .expressionAttributeValues(
                    mapOf(":config" to AttributeValue.fromS(CONFIG_AGGREGATE_ID)),
                )
        if (pageLimit != null) {
            builder.limit(pageLimit)
        }
        var request = builder.build()
        while (true) {
            val response = client.scan(request)
            for (item in response.items()) {
                val aggregateId = optionalString(item, "aid")
                if (aggregateId == null ||
                    aggregateId == CONFIG_AGGREGATE_ID ||
                    !AggregateType.isGroupChat(aggregateId)
                ) {
                    // A head of another aggregate type may not carry type_name or seq_nr. Skip it
                    // before parsing required fields so it cannot abort the whole scan.
                    continue
                }
                visitor(toHead(item))
            }
            val lastEvaluatedKey = response.lastEvaluatedKey()
            if (lastEvaluatedKey.isNullOrEmpty()) return
            request = request.toBuilder().exclusiveStartKey(lastEvaluatedKey).build()
        }
    }

    override fun storeId(): String {
        cachedStoreId?.let { return it }
        val response =
            client.getItem(
                GetItemRequest
                    .builder()
                    .tableName(tableName)
                    .consistentRead(true)
                    .key(mapOf("aid" to AttributeValue.fromS(CONFIG_AGGREGATE_ID)))
                    .build(),
            )
        val storeId =
            response.item()?.get("store_id")?.s()
                ?: throw ReadModelUpdateException(
                    "head configuration item $CONFIG_AGGREGATE_ID has no store_id",
                )
        cachedStoreId = storeId
        return storeId
    }

    private fun toHead(item: Map<String, AttributeValue>): Head {
        val aggregateId = requiredString(item, "aid")
        val typeName = requiredString(item, "type_name")
        val seqNr =
            item["seq_nr"]?.n()?.toLong()
                ?: throw ReadModelUpdateException("head item $aggregateId has no seq_nr number")
        return Head(aggregateId, typeName, seqNr)
    }

    private fun requiredString(
        item: Map<String, AttributeValue>,
        name: String,
    ): String =
        item[name]?.s()
            ?: throw ReadModelUpdateException("head item has no $name string")

    private fun optionalString(
        item: Map<String, AttributeValue>,
        name: String,
    ): String? = item[name]?.s()

    companion object {
        const val CONFIG_AGGREGATE_ID = "__config__"
    }
}

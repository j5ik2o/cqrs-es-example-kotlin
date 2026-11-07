package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** One page of a shard listing. */
data class ShardPage(
    val shardIds: List<String>,
    val lastEvaluatedShardId: String?,
)

/** One page of records read from a shard iterator. */
data class StreamRecordPage(
    val records: List<software.amazon.awssdk.services.dynamodb.model.Record>,
    val sequenceNumbers: List<String>,
    val nextShardIterator: String?,
)

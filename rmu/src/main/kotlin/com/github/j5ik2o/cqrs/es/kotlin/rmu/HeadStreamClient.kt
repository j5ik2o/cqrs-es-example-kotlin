package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** The DynamoDB Streams operations the head poller needs. */
interface HeadStreamClient {
    fun streamArn(headTableName: String): String

    fun describeShards(
        streamArn: String,
        exclusiveStartShardId: String?,
    ): ShardPage

    fun getRecords(
        streamArn: String,
        shardIterator: String,
        limit: Int,
    ): StreamRecordPage

    fun shardIteratorAtTrimHorizon(
        streamArn: String,
        shardId: String,
    ): String

    fun shardIteratorAfterSequenceNumber(
        streamArn: String,
        shardId: String,
        sequenceNumber: String,
    ): String
}

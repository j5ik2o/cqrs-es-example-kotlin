package com.github.j5ik2o.cqrs.es.kotlin.rmu

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient

class DynamoDbHeadStreamClient(
    private val dynamoDbClient: DynamoDbClient,
    private val streamsClient: DynamoDbStreamsClient,
) : HeadStreamClient {
    override fun streamArn(headTableName: String): String {
        val response =
            dynamoDbClient.describeTable(
                DescribeTableRequest.builder().tableName(headTableName).build(),
            )
        return response.table().latestStreamArn()
            ?: throw IllegalStateException("head table $headTableName has no stream")
    }

    override fun describeShards(
        streamArn: String,
        exclusiveStartShardId: String?,
    ): ShardPage {
        val builder = DescribeStreamRequest.builder().streamArn(streamArn)
        if (exclusiveStartShardId != null) {
            builder.exclusiveStartShardId(exclusiveStartShardId)
        }
        val description =
            streamsClient.describeStream(builder.build()).streamDescription()
        return ShardPage(
            shardIds = description.shards().map { it.shardId() },
            lastEvaluatedShardId = description.lastEvaluatedShardId(),
        )
    }

    override fun getRecords(
        streamArn: String,
        shardIterator: String,
        limit: Int,
    ): StreamRecordPage {
        val response =
            streamsClient.getRecords(
                GetRecordsRequest
                    .builder()
                    .shardIterator(shardIterator)
                    .limit(limit)
                    .build(),
            )
        return StreamRecordPage(
            records = response.records(),
            sequenceNumbers = response.records().map { it.dynamodb().sequenceNumber() },
            nextShardIterator = response.nextShardIterator(),
        )
    }

    override fun shardIteratorAtTrimHorizon(
        streamArn: String,
        shardId: String,
    ): String = getShardIterator(streamArn, shardId, ShardIteratorType.TRIM_HORIZON, null)

    override fun shardIteratorAfterSequenceNumber(
        streamArn: String,
        shardId: String,
        sequenceNumber: String,
    ): String =
        getShardIterator(
            streamArn,
            shardId,
            ShardIteratorType.AFTER_SEQUENCE_NUMBER,
            sequenceNumber,
        )

    private fun getShardIterator(
        streamArn: String,
        shardId: String,
        type: ShardIteratorType,
        sequenceNumber: String?,
    ): String {
        val builder =
            GetShardIteratorRequest
                .builder()
                .streamArn(streamArn)
                .shardId(shardId)
                .shardIteratorType(type)
        if (sequenceNumber != null) {
            builder.sequenceNumber(sequenceNumber)
        }
        return streamsClient.getShardIterator(builder.build()).shardIterator()
    }
}

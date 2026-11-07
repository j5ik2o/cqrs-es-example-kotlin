package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository

import com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization.GroupChatEventSerializer
import com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization.GroupChatSnapshotSerializer
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChat
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatEvent
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import java.net.URI

@Configuration
@Profile("write")
class RepositoryConfig(
    @Value("\${app.persistence.journal-table-name}") private val journalTableName: String,
    @Value("\${app.persistence.snapshot-table-name}") private val snapshotTableName: String,
    @Value("\${app.persistence.head-table-name}") private val headTableName: String,
    @Value("\${app.persistence.snapshot-aid-index-name}") private val snapshotAidIndexName: String,
    @Value("\${app.persistence.snapshot-interval:10}") private val snapshotInterval: Long,
    @Value("\${app.dynamo-db.region:ap-northeast-1}") private val dynamoDbRegion: String,
    @Value("\${app.dynamo-db.endpoint-url:}") private val dynamoDbEndpointUrl: String,
    @Value("\${app.dynamo-db.access-key:}") private val dynamoDbAccessKey: String,
    @Value("\${app.dynamo-db.secret-access-key:}") private val dynamoDbSecretAccessKey: String,
) {
    @Bean
    fun dynamoDbAsyncClient(): DynamoDbAsyncClient {
        val builder = DynamoDbAsyncClient.builder().region(Region.of(dynamoDbRegion))
        if (dynamoDbEndpointUrl.isNotBlank()) {
            builder.endpointOverride(URI.create(dynamoDbEndpointUrl))
        }
        if (dynamoDbAccessKey.isNotBlank() && dynamoDbSecretAccessKey.isNotBlank()) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(dynamoDbAccessKey, dynamoDbSecretAccessKey),
                ),
            )
        }
        return builder.build()
    }

    @Bean
    fun eventStoreAsync(dynamoDbAsyncClient: DynamoDbAsyncClient): EventStoreAsync<GroupChatEvent, GroupChat> {
        val tables =
            DynamoDbTableConfig
                .builder()
                .journalTableName(journalTableName)
                .snapshotTableName(snapshotTableName)
                .headTableName(headTableName)
                .snapshotAidIndexName(snapshotAidIndexName)
                .retentionPolicy(RetentionPolicy.none())
                .build()
        val config =
            EventStoreConfig
                .builder<GroupChatEvent, GroupChat>()
                .payloadSerializer(GroupChatEventSerializer())
                .snapshotSerializer(GroupChatSnapshotSerializer())
                .build()
        val javaEventStore =
            DynamoDbEventStore.createAsync(dynamoDbAsyncClient, tables, config).join()
        return EventStoreAsync.fromJava(javaEventStore)
    }

    @Bean
    fun groupChatRepository(eventStoreAsync: EventStoreAsync<GroupChatEvent, GroupChat>): GroupChatRepository =
        GroupChatRepositoryImpl(eventStoreAsync, snapshotInterval)
}

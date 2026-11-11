package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization.GroupChatEventSerializer
import com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization.GroupChatSnapshotSerializer
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChat
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatName
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.Projection
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification
import software.amazon.awssdk.services.dynamodb.model.StreamViewType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class GroupChatRepositoryImplTest {
    @Test
    fun `stores and restores an aggregate from the three tables`() {
        runBlocking {
            val client = createClient()
            try {
                val repository = createRepository(client)
                val groupChatId = GroupChatId.generate()
                val name = GroupChatName.of("test-1")
                val adminId = UserAccountId.generate()
                val (aggregate, created) = GroupChat.create(groupChatId, name, adminId)

                repository.store(created, aggregate)

                val found = assertNotNull(repository.findById(groupChatId))
                assertEquals(aggregate, found)

                val aid = groupChatId.asString()
                val journalItem =
                    getItem(
                        client,
                        JOURNAL_TABLE_NAME,
                        mapOf(
                            "aid" to AttributeValue.fromS(aid),
                            "seq_nr" to AttributeValue.fromN("1"),
                        ),
                    )
                assertEquals("GroupChatCreated@1", journalItem["manifest"]?.s())
                val payload = ObjectMapper().readTree(journalItem["payload"]?.b()?.asByteArray())
                assertEquals("GroupChatCreated", payload["type"].asText())
                assertFalse(payload.has("aggregateId"))
                assertFalse(payload.has("sequenceNumber"))
                assertFalse(payload.has("occurredAt"))

                val headItem =
                    getItem(client, HEAD_TABLE_NAME, mapOf("aid" to AttributeValue.fromS(aid)))
                assertEquals("1", headItem["seq_nr"]?.n())

                val snapshotItem =
                    getItem(
                        client,
                        SNAPSHOT_TABLE_NAME,
                        mapOf(
                            "aid" to AttributeValue.fromS(aid),
                            "skey" to AttributeValue.fromN("0"),
                        ),
                    )
                assertEquals("GroupChat@1", snapshotItem["manifest"]?.s())
                assertEquals("1", snapshotItem["seq_nr"]?.n())

                val (renamed, renamedEvent) =
                    aggregate.rename(GroupChatName.of("test-2"), adminId)
                repository.store(renamedEvent, renamed)

                val restored = assertNotNull(repository.findById(groupChatId))
                assertEquals("test-2", restored.name.asString())
                assertEquals(2L, restored.sequenceNumber)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `rejects a stale event as an optimistic lock failure`() {
        runBlocking {
            val client = createClient()
            try {
                val repository = createRepository(client)
                val (aggregate, created) =
                    GroupChat.create(
                        GroupChatId.generate(),
                        GroupChatName.of("test"),
                        UserAccountId.generate(),
                    )
                repository.store(created, aggregate)

                val administratorId =
                    aggregate.members.values
                        .first()
                        .userAccountId
                val (renamed, renamedEvent) =
                    aggregate.rename(GroupChatName.of("test-2"), administratorId)
                repository.store(renamedEvent, renamed)

                val (stale, staleEvent) =
                    aggregate.rename(GroupChatName.of("stale"), administratorId)

                val failure =
                    try {
                        repository.store(staleEvent, stale)
                        null
                    } catch (e: RepositoryException) {
                        e
                    }
                assertNotNull(failure)
                val causes = generateSequence<Throwable>(failure) { it.cause }.take(10).toList()
                assertTrue(
                    causes.any { it is OptimisticLockException },
                    "expected an OptimisticLockException, got: ${causes.map { it::class.qualifiedName }}",
                )
            } finally {
                client.close()
            }
        }
    }

    private fun createRepository(client: DynamoDbAsyncClient): GroupChatRepositoryImpl {
        val tables =
            DynamoDbTableConfig
                .builder()
                .journalTableName(JOURNAL_TABLE_NAME)
                .snapshotTableName(SNAPSHOT_TABLE_NAME)
                .headTableName(HEAD_TABLE_NAME)
                .snapshotAidIndexName(SNAPSHOT_AID_INDEX_NAME)
                .retentionPolicy(RetentionPolicy.none())
                .build()
        val config =
            EventStoreConfig
                .builder<GroupChatEvent, GroupChat>()
                .payloadSerializer(GroupChatEventSerializer())
                .snapshotSerializer(GroupChatSnapshotSerializer())
                .build()
        val javaEventStore =
            DynamoDbEventStore.createAsync(client, tables, config).join()
        val eventStore: EventStoreAsync<GroupChatEvent, GroupChat> =
            EventStoreAsync.fromJava(javaEventStore)
        return GroupChatRepositoryImpl(eventStore, snapshotInterval = 10)
    }

    private fun getItem(
        client: DynamoDbAsyncClient,
        tableName: String,
        key: Map<String, AttributeValue>,
    ): Map<String, AttributeValue> =
        client
            .getItem { request ->
                request.tableName(tableName).consistentRead(true).key(key)
            }.join()
            .item()

    companion object {
        @Container
        @JvmField
        val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
                .withServices(LocalStackContainer.Service.DYNAMODB)

        @JvmStatic
        @BeforeAll
        fun createTablesOnce() {
            val client = createClient()
            try {
                createTables(client)
            } finally {
                client.close()
            }
        }

        private fun createClient(): DynamoDbAsyncClient =
            DynamoDbAsyncClient
                .builder()
                .endpointOverride(localstack.endpoint)
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey),
                    ),
                ).region(Region.of(localstack.region))
                .build()

        private fun createTables(client: DynamoDbAsyncClient) {
            val throughput =
                ProvisionedThroughput
                    .builder()
                    .readCapacityUnits(10)
                    .writeCapacityUnits(5)
                    .build()

            client
                .createTable(
                    CreateTableRequest
                        .builder()
                        .tableName(JOURNAL_TABLE_NAME)
                        .attributeDefinitions(
                            attributeDefinition("aid", ScalarAttributeType.S),
                            attributeDefinition("seq_nr", ScalarAttributeType.N),
                        ).keySchema(
                            keySchemaElement("aid", KeyType.HASH),
                            keySchemaElement("seq_nr", KeyType.RANGE),
                        ).provisionedThroughput(throughput)
                        .build(),
                ).join()

            client
                .createTable(
                    CreateTableRequest
                        .builder()
                        .tableName(SNAPSHOT_TABLE_NAME)
                        .attributeDefinitions(
                            attributeDefinition("aid", ScalarAttributeType.S),
                            attributeDefinition("skey", ScalarAttributeType.N),
                            attributeDefinition("active_history_seq_nr", ScalarAttributeType.N),
                        ).keySchema(
                            keySchemaElement("aid", KeyType.HASH),
                            keySchemaElement("skey", KeyType.RANGE),
                        ).globalSecondaryIndexes(
                            GlobalSecondaryIndex
                                .builder()
                                .indexName(SNAPSHOT_AID_INDEX_NAME)
                                .keySchema(
                                    keySchemaElement("aid", KeyType.HASH),
                                    keySchemaElement("active_history_seq_nr", KeyType.RANGE),
                                ).projection(
                                    Projection
                                        .builder()
                                        .projectionType(ProjectionType.KEYS_ONLY)
                                        .build(),
                                ).provisionedThroughput(throughput)
                                .build(),
                        ).provisionedThroughput(throughput)
                        .build(),
                ).join()

            client
                .createTable(
                    CreateTableRequest
                        .builder()
                        .tableName(HEAD_TABLE_NAME)
                        .attributeDefinitions(attributeDefinition("aid", ScalarAttributeType.S))
                        .keySchema(keySchemaElement("aid", KeyType.HASH))
                        .streamSpecification(
                            StreamSpecification
                                .builder()
                                .streamEnabled(true)
                                .streamViewType(StreamViewType.NEW_IMAGE)
                                .build(),
                        ).provisionedThroughput(throughput)
                        .build(),
                ).join()
        }

        private fun attributeDefinition(
            name: String,
            type: ScalarAttributeType,
        ): AttributeDefinition =
            AttributeDefinition
                .builder()
                .attributeName(name)
                .attributeType(type)
                .build()

        private fun keySchemaElement(
            name: String,
            keyType: KeyType,
        ): KeySchemaElement =
            KeySchemaElement
                .builder()
                .attributeName(name)
                .keyType(keyType)
                .build()

        private const val JOURNAL_TABLE_NAME = "journal"
        private const val SNAPSHOT_TABLE_NAME = "snapshot"
        private const val HEAD_TABLE_NAME = "head"
        private const val SNAPSHOT_AID_INDEX_NAME = "snapshot-aid-index"
    }
}

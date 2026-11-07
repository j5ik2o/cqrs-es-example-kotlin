package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import kotlin.test.assertEquals

@Testcontainers
class DynamoDbHeadScannerTest {
    @Test
    fun `scans every page and skips items outside the projection`() {
        val scanner = DynamoDbHeadScanner(client, HEAD_TABLE_NAME, pageLimit = 1)
        val heads = mutableListOf<Head>()

        scanner.scanHeads { heads.add(it) }

        assertEquals(
            listOf(
                Head("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0X", "GroupChat", 11),
                Head("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Y", "GroupChat", 7),
                Head("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z", "GroupChat", 3),
            ),
            heads.sortedBy { it.aggregateId },
        )
        assertEquals(STORE_ID, scanner.storeId())
    }

    companion object {
        private const val HEAD_TABLE_NAME = "head"
        private const val STORE_ID = "store-0001"

        @Container
        @JvmField
        val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:2.3.2"))
                .withServices(LocalStackContainer.Service.DYNAMODB)

        private lateinit var client: DynamoDbClient

        @JvmStatic
        @BeforeAll
        fun setUp() {
            client =
                DynamoDbClient
                    .builder()
                    .endpointOverride(localstack.endpoint)
                    .region(Region.of(localstack.region))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey),
                        ),
                    ).build()
            client.createTable(
                CreateTableRequest
                    .builder()
                    .tableName(HEAD_TABLE_NAME)
                    .attributeDefinitions(
                        AttributeDefinition
                            .builder()
                            .attributeName("aid")
                            .attributeType(ScalarAttributeType.S)
                            .build(),
                    ).keySchema(
                        KeySchemaElement
                            .builder()
                            .attributeName("aid")
                            .keyType(KeyType.HASH)
                            .build(),
                    ).billingMode(BillingMode.PAY_PER_REQUEST)
                    .build(),
            )
            client.putItem(
                PutItemRequest
                    .builder()
                    .tableName(HEAD_TABLE_NAME)
                    .item(
                        mapOf(
                            "aid" to AttributeValue.fromS(HeadRecordParser.CONFIG_AGGREGATE_ID),
                            "store_id" to AttributeValue.fromS(STORE_ID),
                        ),
                    ).build(),
            )
            putHead("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z", 3)
            putHead("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Y", 7)
            putHead("GroupChat-01H42K4ABWQ5V2XQEP3A48VE0X", 11)
            // Another aggregate type may not carry type_name or seq_nr. The scan must skip it
            // without aborting the GroupChat heads that follow.
            client.putItem(
                PutItemRequest
                    .builder()
                    .tableName(HEAD_TABLE_NAME)
                    .item(mapOf("aid" to AttributeValue.fromS("UserAccount-01H42K4ABWQ5V2XQEP3A48VE0W")))
                    .build(),
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            client.close()
        }

        private fun putHead(
            aggregateId: String,
            seqNr: Long,
        ) {
            client.putItem(
                PutItemRequest
                    .builder()
                    .tableName(HEAD_TABLE_NAME)
                    .item(
                        mapOf(
                            "aid" to AttributeValue.fromS(aggregateId),
                            "type_name" to AttributeValue.fromS("GroupChat"),
                            "seq_nr" to AttributeValue.fromN(seqNr.toString()),
                        ),
                    ).build(),
            )
        }
    }
}

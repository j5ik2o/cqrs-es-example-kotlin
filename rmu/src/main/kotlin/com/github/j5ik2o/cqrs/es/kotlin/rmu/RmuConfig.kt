package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClientBuilder
import java.net.URI

@Configuration
@Profile("local-rmu")
class RmuConfig(
    @Value("\${app.stream.journal-table-name:journal}") private val journalTableName: String,
    @Value("\${app.stream.max-item-count:32}") private val maxItemCount: Int,
    @Value("\${app.dynamo-db.region:ap-northeast-1}") private val dynamoDbRegion: String,
    @Value("\${app.dynamo-db.endpoint-url:}") private val dynamoDbEndpointUrl: String,
    @Value("\${app.dynamo-db.access-key:}") private val dynamoDbAccessKey: String,
    @Value("\${app.dynamo-db.secret-access-key:}") private val dynamoDbSecretAccessKey: String,
) {
    @Bean
    fun rmuDynamoDbClient(): DynamoDbClient = DynamoDbClient.builder().applyClientConfig().build()

    @Bean
    fun rmuDynamoDbStreamsClient(): DynamoDbStreamsClient = DynamoDbStreamsClient.builder().applyClientConfig().build()

    @Bean
    fun journalReader(rmuDynamoDbClient: DynamoDbClient): JournalReader = DynamoDbJournalReader(rmuDynamoDbClient, journalTableName)

    @Bean
    fun rmuTransactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(transactionManager)

    @Bean
    fun readModelApplier(
        jdbcTemplate: JdbcTemplate,
        rmuTransactionTemplate: TransactionTemplate,
        journalReader: JournalReader,
    ): ReadModelApplier = ReadModelApplier(jdbcTemplate, rmuTransactionTemplate, journalReader)

    @Bean
    fun readModelUpdater(applier: ReadModelApplier): ReadModelUpdater = ReadModelUpdater(applier)

    @Bean
    fun headStreamClient(
        rmuDynamoDbClient: DynamoDbClient,
        rmuDynamoDbStreamsClient: DynamoDbStreamsClient,
    ): HeadStreamClient = DynamoDbHeadStreamClient(rmuDynamoDbClient, rmuDynamoDbStreamsClient)

    @Bean
    fun headStreamPoller(
        headStreamClient: HeadStreamClient,
        readModelUpdater: ReadModelUpdater,
    ): HeadStreamPoller = HeadStreamPoller(headStreamClient, readModelUpdater::update, maxItemCount)

    private fun DynamoDbClientBuilder.applyClientConfig(): DynamoDbClientBuilder {
        region(Region.of(dynamoDbRegion))
        if (dynamoDbEndpointUrl.isNotBlank()) {
            endpointOverride(URI.create(dynamoDbEndpointUrl))
        }
        if (dynamoDbAccessKey.isNotBlank() && dynamoDbSecretAccessKey.isNotBlank()) {
            credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(dynamoDbAccessKey, dynamoDbSecretAccessKey),
                ),
            )
        }
        return this
    }

    private fun DynamoDbStreamsClientBuilder.applyClientConfig(): DynamoDbStreamsClientBuilder {
        region(Region.of(dynamoDbRegion))
        if (dynamoDbEndpointUrl.isNotBlank()) {
            endpointOverride(URI.create(dynamoDbEndpointUrl))
        }
        if (dynamoDbAccessKey.isNotBlank() && dynamoDbSecretAccessKey.isNotBlank()) {
            credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(dynamoDbAccessKey, dynamoDbSecretAccessKey),
                ),
            )
        }
        return this
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient
import java.net.URI
import java.time.Duration

@Configuration
@Profile("local-rmu")
class RmuConfig(
    @Value("\${app.stream.head-table-name:head}") private val headTableName: String,
    @Value("\${app.stream.journal-table-name:journal}") private val journalTableName: String,
    @Value("\${app.stream.max-item-count:32}") private val maxItemCount: Int,
    @Value("\${app.stream.retry-max-attempts:5}") private val retryMaxAttempts: Int,
    @Value("\${app.stream.retry-max-elapsed-millis:30000}")
    private val retryMaxElapsedMillis: Long,
    @Value("\${app.stream.retry-initial-delay-millis:200}")
    private val retryInitialDelayMillis: Long,
    @Value("\${app.stream.retry-max-delay-millis:5000}")
    private val retryMaxDelayMillis: Long,
    @Value("\${app.stream.poll-backoff-initial-millis:500}")
    private val pollBackoffInitialMillis: Long,
    @Value("\${app.stream.poll-backoff-max-millis:30000}")
    private val pollBackoffMaxMillis: Long,
    @Value("\${app.dynamo-db.region:ap-northeast-1}") private val dynamoDbRegion: String,
    @Value("\${app.dynamo-db.endpoint-url:}") private val dynamoDbEndpointUrl: String,
    @Value("\${app.dynamo-db.access-key:}") private val dynamoDbAccessKey: String,
    @Value("\${app.dynamo-db.secret-access-key:}") private val dynamoDbSecretAccessKey: String,
) {
    private val logger = LoggerFactory.getLogger(RmuConfig::class.java)

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
    fun rmuRetryPolicy(): RetryPolicy =
        RetryPolicy(
            maxAttempts = retryMaxAttempts,
            maxElapsed = Duration.ofMillis(retryMaxElapsedMillis),
            initialDelay = Duration.ofMillis(retryInitialDelayMillis),
            maxDelay = Duration.ofMillis(retryMaxDelayMillis),
        )

    @Bean
    fun streamPollBackoff(): Backoff =
        Backoff(
            initial = Duration.ofMillis(pollBackoffInitialMillis),
            max = Duration.ofMillis(pollBackoffMaxMillis),
        )

    @Bean
    fun readModelApplier(
        jdbcTemplate: JdbcTemplate,
        rmuTransactionTemplate: TransactionTemplate,
        journalReader: JournalReader,
    ): ReadModelApplier = ReadModelApplier(jdbcTemplate, rmuTransactionTemplate, journalReader)

    @Bean
    fun headScanner(rmuDynamoDbClient: DynamoDbClient): DynamoDbHeadScanner = DynamoDbHeadScanner(rmuDynamoDbClient, headTableName)

    @Bean
    fun recoveryTaskStore(jdbcTemplate: JdbcTemplate): RecoveryTaskStore = JdbcRecoveryTaskStore(jdbcTemplate)

    @Bean
    fun recoveryNotifier(): RecoveryNotifier =
        RecoveryNotifier { task ->
            logger.info(
                "Recovery task recorded: aid={}, seqNr={}, kind={}",
                task.aggregateId,
                task.seqNr,
                task.failureKind,
            )
        }

    @Bean
    fun recoveryService(
        recoveryTaskStore: RecoveryTaskStore,
        headScanner: DynamoDbHeadScanner,
        readModelApplier: ReadModelApplier,
        recoveryNotifier: RecoveryNotifier,
    ): RecoveryService =
        RecoveryService(
            recoveryTaskStore,
            headScanner,
            readModelApplier,
            recoveryNotifier,
        )

    @Bean
    fun resyncer(
        readModelApplier: ReadModelApplier,
        headScanner: DynamoDbHeadScanner,
        recoveryService: RecoveryService,
    ): Resyncer = Resyncer(readModelApplier, headScanner, recoveryService)

    @Bean
    fun readModelUpdater(
        readModelApplier: ReadModelApplier,
        recoveryService: RecoveryService,
        rmuRetryPolicy: RetryPolicy,
    ): ReadModelUpdater = ReadModelUpdater(readModelApplier, recoveryService, rmuRetryPolicy)

    @Bean
    fun headStreamClient(
        rmuDynamoDbClient: DynamoDbClient,
        rmuDynamoDbStreamsClient: DynamoDbStreamsClient,
    ): HeadStreamClient = DynamoDbHeadStreamClient(rmuDynamoDbClient, rmuDynamoDbStreamsClient)

    @Bean
    fun headStreamPoller(
        headStreamClient: HeadStreamClient,
        readModelUpdater: ReadModelUpdater,
    ): HeadStreamPoller =
        HeadStreamPoller(
            headStreamClient,
            { record -> readModelUpdater.update(record) },
            maxItemCount,
        )

    private fun <B : AwsClientBuilder<B, *>> B.applyClientConfig(): B {
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

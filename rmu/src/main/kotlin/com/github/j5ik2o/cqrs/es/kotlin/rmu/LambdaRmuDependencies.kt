package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI

/**
 * Holds one dependency graph per Lambda execution environment.
 *
 * Lambda reuses a handler instance and its clients across invocations. The holder keeps the
 * handlers created with a no-argument constructor from building the graph more than once.
 */
internal object LambdaDependenciesHolder {
    val instance: LambdaRmuDependencies by lazy { LambdaRmuDependencies.create() }
}

/**
 * Builds the RMU dependencies for the AWS Lambda entry points.
 *
 * Lambda instantiates a handler with a no-argument constructor, so the Spring wiring in
 * `bootstrap` is not available. This class reads the same settings from environment variables and
 * builds the journal reader, head scanner, recovery task store, and transaction applier.
 */
class LambdaRmuDependencies private constructor(
    val updater: ReadModelUpdater,
    val recoveryService: RecoveryService,
    val resyncer: Resyncer,
    val maxRecoveryTasksPerRun: Int,
) {
    companion object {
        fun create(): LambdaRmuDependencies {
            val settings = LambdaSettings.from { name -> System.getenv(name) }
            val dynamoDbClient = createDynamoDbClient(settings)
            val journalReader = DynamoDbJournalReader(dynamoDbClient, settings.journalTableName)
            val headScanner = DynamoDbHeadScanner(dynamoDbClient, settings.headTableName)

            val secretJson = settings.dataSourceSecretArn?.let { fetchSecret(it, settings) }
            val database = DatabaseSettings.resolve(settings, secretJson)
            val dataSource = createDataSource(database)
            val jdbcTemplate = JdbcTemplate(dataSource)
            val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
            val applier = ReadModelApplier(jdbcTemplate, transactionTemplate, journalReader)
            val taskStore = JdbcRecoveryTaskStore(jdbcTemplate)

            val notifier =
                settings.recoveryQueueUrl?.takeIf { it.isNotBlank() }?.let { queueUrl ->
                    SqsRecoveryNotifier(createSqsClient(settings), queueUrl)
                }
            val recoveryService = RecoveryService(taskStore, headScanner, applier, notifier)
            val retryPolicy = settings.retryPolicy()
            return LambdaRmuDependencies(
                updater = ReadModelUpdater(applier, recoveryService, retryPolicy),
                recoveryService = recoveryService,
                resyncer = Resyncer(applier, headScanner, recoveryService),
                maxRecoveryTasksPerRun = settings.maxRecoveryTasksPerRun,
            )
        }

        private fun createDynamoDbClient(settings: LambdaSettings): DynamoDbClient =
            DynamoDbClient
                .builder()
                .apply {
                    region(Region.of(settings.dynamoDbRegion))
                    settings.dynamoDbEndpointUrl?.takeIf { it.isNotBlank() }?.let {
                        endpointOverride(URI.create(it))
                    }
                }.build()

        /**
         * The SQS settings are separate from the DynamoDB ones so a custom DynamoDB endpoint is not
         * sent SQS requests.
         */
        private fun createSqsClient(settings: LambdaSettings): SqsClient =
            SqsClient
                .builder()
                .apply {
                    region(Region.of(settings.dynamoDbRegion))
                    settings.sqsEndpointUrl?.takeIf { it.isNotBlank() }?.let {
                        endpointOverride(URI.create(it))
                    }
                }.build()

        private fun fetchSecret(
            secretArn: String,
            settings: LambdaSettings,
        ): String {
            val client =
                SecretsManagerClient
                    .builder()
                    .apply {
                        region(Region.of(settings.dynamoDbRegion))
                        settings.secretEndpointUrl?.takeIf { it.isNotBlank() }?.let {
                            endpointOverride(URI.create(it))
                        }
                    }.build()
            return client.use { it.getSecretValue { request -> request.secretId(secretArn) }.secretString() }
        }

        private fun createDataSource(database: DatabaseSettings): HikariDataSource =
            HikariDataSource().apply {
                jdbcUrl = database.url
                username = database.username
                password = database.password
                maximumPoolSize = 2
                poolName = "rmu-lambda"
                initializationFailTimeout = -1
            }
    }
}

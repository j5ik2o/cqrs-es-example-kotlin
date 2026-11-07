package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * The settings a Lambda invocation reads from its environment.
 *
 * The names match `application-common.yml`, so the same values work for the Spring-managed local
 * reader and the Lambda entry points. Parsing is separated from client construction so the defaults
 * and validation can be tested without AWS or a database.
 */
internal data class LambdaSettings(
    val headTableName: String = DEFAULT_HEAD_TABLE_NAME,
    val journalTableName: String = DEFAULT_JOURNAL_TABLE_NAME,
    val retryMaxAttempts: Int = DEFAULT_RETRY_MAX_ATTEMPTS,
    val retryMaxElapsedMillis: Long = DEFAULT_RETRY_MAX_ELAPSED_MILLIS,
    val retryInitialDelayMillis: Long = DEFAULT_RETRY_INITIAL_DELAY_MILLIS,
    val retryMaxDelayMillis: Long = DEFAULT_RETRY_MAX_DELAY_MILLIS,
    val recoveryQueueUrl: String? = null,
    val maxRecoveryTasksPerRun: Int = DEFAULT_MAX_RECOVERY_TASKS_PER_RUN,
    val dataSourceUrl: String? = null,
    val dataSourceUsername: String? = null,
    val dataSourcePassword: String? = null,
    val dataSourceSecretArn: String? = null,
    val dynamoDbRegion: String = DEFAULT_REGION,
    val dynamoDbEndpointUrl: String? = null,
    val sqsEndpointUrl: String? = null,
    val secretEndpointUrl: String? = null,
) {
    fun retryPolicy(): RetryPolicy =
        RetryPolicy(
            maxAttempts = retryMaxAttempts,
            maxElapsed = Duration.ofMillis(retryMaxElapsedMillis),
            initialDelay = Duration.ofMillis(retryInitialDelayMillis),
            maxDelay = Duration.ofMillis(retryMaxDelayMillis),
        )

    companion object {
        const val DEFAULT_HEAD_TABLE_NAME = "head"
        const val DEFAULT_JOURNAL_TABLE_NAME = "journal"
        const val DEFAULT_RETRY_MAX_ATTEMPTS = 5
        const val DEFAULT_RETRY_MAX_ELAPSED_MILLIS = 30_000L
        const val DEFAULT_RETRY_INITIAL_DELAY_MILLIS = 200L
        const val DEFAULT_RETRY_MAX_DELAY_MILLIS = 5_000L
        const val DEFAULT_MAX_RECOVERY_TASKS_PER_RUN = 50
        const val DEFAULT_REGION = "ap-northeast-1"

        fun from(env: (String) -> String?): LambdaSettings =
            LambdaSettings(
                headTableName = env("HEAD_TABLE_NAME") ?: DEFAULT_HEAD_TABLE_NAME,
                journalTableName = env("JOURNAL_TABLE_NAME") ?: DEFAULT_JOURNAL_TABLE_NAME,
                retryMaxAttempts = positiveInt(env, "STREAM_RETRY_MAX_ATTEMPTS", DEFAULT_RETRY_MAX_ATTEMPTS),
                retryMaxElapsedMillis =
                    nonNegativeLong(env, "STREAM_RETRY_MAX_ELAPSED_MILLIS", DEFAULT_RETRY_MAX_ELAPSED_MILLIS),
                retryInitialDelayMillis =
                    nonNegativeLong(env, "STREAM_RETRY_INITIAL_DELAY_MILLIS", DEFAULT_RETRY_INITIAL_DELAY_MILLIS),
                retryMaxDelayMillis =
                    nonNegativeLong(env, "STREAM_RETRY_MAX_DELAY_MILLIS", DEFAULT_RETRY_MAX_DELAY_MILLIS),
                recoveryQueueUrl = env("APP_RECOVERY_QUEUE_URL"),
                maxRecoveryTasksPerRun =
                    positiveInt(env, "APP_RECOVERY_MAX_TASKS_PER_RUN", DEFAULT_MAX_RECOVERY_TASKS_PER_RUN),
                dataSourceUrl = env("SPRING_DATASOURCE_URL") ?: env("DATABASE_URL"),
                dataSourceUsername = env("SPRING_DATASOURCE_USERNAME") ?: env("DATABASE_USERNAME"),
                dataSourcePassword = env("SPRING_DATASOURCE_PASSWORD") ?: env("DATABASE_PASSWORD"),
                dataSourceSecretArn = env("APP_DB_SECRET_ARN"),
                dynamoDbRegion = env("DYNAMODB_REGION") ?: env("AWS_REGION") ?: DEFAULT_REGION,
                dynamoDbEndpointUrl = env("DYNAMODB_ENDPOINT_URL"),
                sqsEndpointUrl = env("APP_RECOVERY_SQS_ENDPOINT_URL"),
                secretEndpointUrl = env("APP_DB_SECRET_ENDPOINT_URL"),
            )

        private fun positiveInt(
            env: (String) -> String?,
            name: String,
            fallback: Int,
        ): Int {
            val value = env(name) ?: return fallback
            val parsed = value.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer: $value")
            require(parsed > 0) { "$name must be a positive integer: $parsed" }
            return parsed
        }

        private fun nonNegativeLong(
            env: (String) -> String?,
            name: String,
            fallback: Long,
        ): Long {
            val value = env(name) ?: return fallback
            val parsed = value.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer: $value")
            require(parsed >= 0) { "$name must not be negative: $parsed" }
            return parsed
        }
    }
}

/** The JDBC connection settings resolved from the secret or the plain environment variables. */
internal data class DatabaseSettings(
    val url: String,
    val username: String,
    val password: String,
) {
    companion object {
        private val MAPPER = ObjectMapper()

        /**
         * Resolves the connection settings. When [secretJson] is present it must hold `username` and
         * `password`, with an optional `url` that overrides the parameter. Without a secret the
         * plain environment values are used, which keeps local invocations simple.
         */
        fun resolve(
            settings: LambdaSettings,
            secretJson: String? = null,
        ): DatabaseSettings {
            if (secretJson != null) {
                val node = MAPPER.readTree(secretJson)
                val url = node.path("url").takeIf { !it.isMissingNode }?.asText() ?: settings.dataSourceUrl
                return DatabaseSettings(
                    url = requireNotNull(url) { "a database url is required: set SPRING_DATASOURCE_URL or add url to the secret" },
                    username = requireText(node.path("username").asText(), "the database secret has no username"),
                    password = requireText(node.path("password").asText(), "the database secret has no password"),
                )
            }
            return DatabaseSettings(
                url = requireNotNull(settings.dataSourceUrl) { "SPRING_DATASOURCE_URL is required" },
                username = settings.dataSourceUsername ?: "",
                password = settings.dataSourcePassword ?: "",
            )
        }

        private fun requireText(
            value: String,
            message: String,
        ): String {
            require(value.isNotBlank()) { message }
            return value
        }
    }
}

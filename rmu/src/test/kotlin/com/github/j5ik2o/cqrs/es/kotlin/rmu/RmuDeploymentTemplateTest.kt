package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.amazonaws.services.lambda.runtime.RequestHandler
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks the values operators rely on in the CloudFormation deployment asset. */
class RmuDeploymentTemplateTest {
    private val mapper = ObjectMapper()

    @Test
    fun `configures the head stream event source mapping`() {
        val mapping = template().at("/Resources/RmuEventSourceMapping/Properties")

        assertTrue(mapping.path("BisectBatchOnFunctionError").asBoolean())
        assertTrue(containsText(mapping.path("FunctionResponseTypes"), REPORT_BATCH_ITEM_FAILURES))
        assertEquals("TRIM_HORIZON", mapping.path("StartingPosition").asText())
        assertTrue(mapping.path("BatchSize").isObject)
        assertTrue(mapping.path("MaximumRetryAttempts").isObject)
        assertEquals("OnFailureQueue", mapping.at("/DestinationConfig/OnFailure/Destination/Fn::GetAtt/0").asText())
        assertEquals("Arn", mapping.at("/DestinationConfig/OnFailure/Destination/Fn::GetAtt/1").asText())
    }

    @Test
    fun `keeps the function timeout above the retry budget and within the lambda maximum`() {
        val template = template()
        assertEquals("RmuTimeoutSeconds", template.at("/Resources/RmuFunction/Properties/Timeout/Ref").asText())

        val timeoutSeconds = template.at("/Parameters/RmuTimeoutSeconds/Default").asLong()
        val retryElapsedMillis = template.at("/Parameters/RetryMaxElapsedMillis/Default").asLong()

        assertTrue(timeoutSeconds > retryElapsedMillis / 1000)
        assertTrue(timeoutSeconds <= 900)
    }

    @Test
    fun `keeps the recovery queue visibility timeout at six times the worker timeout`() {
        val template = template()
        val visibilityTimeout = template.at("/Parameters/RecoveryQueueVisibilityTimeoutSeconds/Default").asLong()
        val workerTimeout = template.at("/Parameters/RecoveryWorkerTimeoutSeconds/Default").asLong()

        assertTrue(visibilityTimeout >= workerTimeout)
        assertTrue(visibilityTimeout >= workerTimeout * 6)
    }

    @Test
    fun `parks exhausted records and recovery messages in dead letter queues`() {
        assertEquals(
            "OnFailureDlq",
            template().at("/Resources/OnFailureQueue/Properties/RedrivePolicy/deadLetterTargetArn/Fn::GetAtt/0").asText(),
        )
        assertEquals(
            "RecoveryDlq",
            template().at("/Resources/RecoveryQueue/Properties/RedrivePolicy/deadLetterTargetArn/Fn::GetAtt/0").asText(),
        )
    }

    @Test
    fun `wires the recovery queue to the notifier and the worker`() {
        val template = template()

        assertEquals(
            "RecoveryQueue",
            template.at("/Resources/RmuFunction/Properties/Environment/Variables/APP_RECOVERY_QUEUE_URL/Ref").asText(),
        )
        assertEquals(
            "RecoveryQueue",
            template.at("/Resources/ResyncWorkerFunction/Properties/Environment/Variables/APP_RECOVERY_QUEUE_URL/Ref").asText(),
        )
        assertEquals(
            REPORT_BATCH_ITEM_FAILURES,
            template.at("/Resources/RecoveryWorkerEventSourceMapping/Properties/FunctionResponseTypes/0").asText(),
        )
        assertEquals(1, template.at("/Resources/RecoveryWorkerEventSourceMapping/Properties/BatchSize").asInt())
    }

    @Test
    fun `names handlers that exist and are request handlers`() {
        assertHandler("/Resources/RmuFunction/Properties/Handler")
        assertHandler("/Resources/RecoveryWorkerFunction/Properties/Handler")
        assertHandler("/Resources/ResyncWorkerFunction/Properties/Handler")
    }

    @Test
    fun `names environment variables the lambda settings read`() {
        val variables = template().at("/Resources/RmuFunction/Properties/Environment/Variables")

        assertEquals("HeadTableName", variables.at("/HEAD_TABLE_NAME/Ref").asText())
        assertEquals("JournalTableName", variables.at("/JOURNAL_TABLE_NAME/Ref").asText())
        assertEquals("RetryMaxAttempts", variables.at("/STREAM_RETRY_MAX_ATTEMPTS/Fn::Sub").asText().let { refText(it) })
        assertNotNull(variables.at("/STREAM_RETRY_MAX_ELAPSED_MILLIS"))
        assertNotNull(variables.at("/STREAM_RETRY_INITIAL_DELAY_MILLIS"))
        assertNotNull(variables.at("/STREAM_RETRY_MAX_DELAY_MILLIS"))
        assertNotNull(variables.at("/APP_RECOVERY_MAX_TASKS_PER_RUN"))
    }

    @Test
    fun `schedules periodic recovery and resync`() {
        val template = template()

        assertEquals(
            "RecoveryWorkerFunction",
            template.at("/Resources/RecoveryWorkerSchedule/Properties/Targets/0/Arn/Fn::GetAtt/0").asText(),
        )
        assertEquals(
            "ResyncWorkerFunction",
            template.at("/Resources/ResyncWorkerSchedule/Properties/Targets/0/Arn/Fn::GetAtt/0").asText(),
        )
        assertEquals("ENABLED", template.at("/Resources/RecoveryWorkerSchedule/Properties/State").asText())
        assertEquals("ENABLED", template.at("/Resources/ResyncWorkerSchedule/Properties/State").asText())
    }

    @Test
    fun `keeps the recovery task budget positive`() {
        assertTrue(template().at("/Parameters/RecoveryMaxTasksPerRun/MinValue").asInt() >= 1)
    }

    @Test
    fun `keeps the environment retry defaults in step with the runtime policy`() {
        val parameters = template().path("Parameters")
        val policy = LambdaSettings.from { null }.retryPolicy()

        assertEquals(policy.maxAttempts, parameters.at("/RetryMaxAttempts/Default").asInt())
        assertEquals(policy.maxElapsed.toMillis(), parameters.at("/RetryMaxElapsedMillis/Default").asLong())
        assertEquals(policy.initialDelay.toMillis(), parameters.at("/RetryInitialDelayMillis/Default").asLong())
        assertEquals(policy.maxDelay.toMillis(), parameters.at("/RetryMaxDelayMillis/Default").asLong())
    }

    @Test
    fun `reads database credentials from secrets manager`() {
        val template = template()

        assertTrue(template.at("/Parameters/DataSourceSecretArn").isObject)
        assertTrue(template.at("/Parameters/DataSourceUsername").isMissingNode)
        assertTrue(template.at("/Parameters/DataSourcePassword").isMissingNode)

        for (function in listOf("RmuFunction", "RecoveryWorkerFunction", "ResyncWorkerFunction")) {
            assertEquals(
                "DataSourceSecretArn",
                template.at("/Resources/$function/Properties/Environment/Variables/APP_DB_SECRET_ARN/Ref").asText(),
            )
            assertTrue(
                template
                    .at("/Resources/$function/Properties/Environment/Variables/SPRING_DATASOURCE_PASSWORD")
                    .isMissingNode,
            )
        }

        assertTrue(grantsSecretRead(template, "RmuFunctionRole"))
        assertTrue(grantsSecretRead(template, "RecoveryWorkerFunctionRole"))
        assertTrue(grantsSecretRead(template, "ResyncWorkerFunctionRole"))
    }

    @Test
    fun `caps the recovery worker concurrency`() {
        val template = template()

        assertEquals(
            "RecoveryWorkerReservedConcurrency",
            template.at("/Resources/RecoveryWorkerFunction/Properties/ReservedConcurrentExecutions/Ref").asText(),
        )
        assertEquals(1, template.at("/Parameters/RecoveryWorkerReservedConcurrency/Default").asInt())
        assertTrue(template.at("/Parameters/RecoveryWorkerReservedConcurrency/MinValue").asInt() >= 1)
    }

    @Test
    fun `alarms on the recovery backlog and exhausted retries`() {
        val template = template()

        assertEquals("AWS/SQS", template.at("/Resources/RecoveryBacklogAlarm/Properties/Namespace").asText())
        assertEquals(
            "ApproximateNumberOfMessagesVisible",
            template.at("/Resources/RecoveryBacklogAlarm/Properties/MetricName").asText(),
        )
        assertEquals(
            "RecoveryQueue",
            template.at("/Resources/RecoveryBacklogAlarm/Properties/Dimensions/0/Value/Fn::GetAtt/0").asText(),
        )
        assertEquals(
            "OnFailureQueue",
            template.at("/Resources/OnFailureQueueAlarm/Properties/Dimensions/0/Value/Fn::GetAtt/0").asText(),
        )
        assertEquals(
            "RecoveryDlq",
            template.at("/Resources/RecoveryDlqAlarm/Properties/Dimensions/0/Value/Fn::GetAtt/0").asText(),
        )
        assertEquals(
            "Errors",
            template.at("/Resources/ResyncWorkerErrorsAlarm/Properties/MetricName").asText(),
        )
        assertEquals(
            "FailedInvocations",
            template.at("/Resources/ResyncScheduleFailedInvocationsAlarm/Properties/MetricName").asText(),
        )
    }

    @Test
    fun `sends alarm actions only when a topic is configured`() {
        val template = template()

        assertEquals("HasAlarmTopic", template.at("/Resources/RecoveryBacklogAlarm/Properties/AlarmActions/Fn::If/0").asText())
        assertEquals("", template.at("/Parameters/AlarmSnsTopicArn/Default").asText())
        assertEquals(
            "AlarmSnsTopicArn",
            template.at("/Conditions/HasAlarmTopic/Fn::Not/0/Fn::Equals/0/Ref").asText(),
        )
    }

    @Test
    fun `refreshes the function code when the build changes`() {
        val template = template()

        assertTrue(template.at("/Parameters/CodeS3ObjectVersion").isObject)
        assertEquals("", template.at("/Parameters/CodeS3ObjectVersion/Default").asText())
        assertTrue(template.at("/Conditions/HasCodeObjectVersion").isObject)
        for (function in listOf("RmuFunction", "RecoveryWorkerFunction", "ResyncWorkerFunction")) {
            assertEquals(
                "HasCodeObjectVersion",
                template.at("/Resources/$function/Properties/Code/S3ObjectVersion/Fn::If/0").asText(),
            )
            assertEquals(
                "CodeS3ObjectVersion",
                template.at("/Resources/$function/Properties/Code/S3ObjectVersion/Fn::If/1/Ref").asText(),
            )
        }
    }

    @Test
    fun `attaches functions to a vpc only when one is configured`() {
        val template = template()

        assertTrue(template.at("/Conditions/UsesVpc").isObject)
        for (function in listOf("RmuFunction", "RecoveryWorkerFunction", "ResyncWorkerFunction")) {
            assertEquals(
                "UsesVpc",
                template.at("/Resources/$function/Properties/VpcConfig/Fn::If/0").asText(),
            )
        }
    }

    private fun grantsSecretRead(
        template: JsonNode,
        role: String,
    ): Boolean =
        template
            .at("/Resources/$role/Properties/Policies/0/PolicyDocument/Statement")
            .any { statement ->
                "secretsmanager:GetSecretValue" == statement.path("Action").asText() &&
                    "DataSourceSecretArn" == statement.at("/Resource/Ref").asText()
            }

    private fun assertHandler(pointer: String) {
        val handler = template().at(pointer).asText()
        assertTrue(handler.endsWith("::handleRequest"), "handler must be a method reference: $handler")
        val type = Class.forName(handler.substringBefore("::"))
        assertTrue(
            RequestHandler::class.java.isAssignableFrom(type),
            "$handler must implement ${RequestHandler::class.java.name}",
        )
        assertNotNull(type.getConstructor(), "$handler needs a public no-argument constructor")
    }

    private fun containsText(
        array: JsonNode,
        value: String,
    ): Boolean = array.any { value == it.asText() }

    private fun refText(sub: String): String = sub.removePrefix("\${").removeSuffix("}")

    private fun template(): JsonNode =
        try {
            mapper.readTree(repositoryFile("deploy/rmu-deployment.template.json").toFile())
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }

    private fun repositoryFile(relative: String): Path {
        var current = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current.resolve(relative)
            }
            current = current.parent
        }
        throw IllegalStateException("repository root not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        private const val REPORT_BATCH_ITEM_FAILURES = "ReportBatchItemFailures"
    }
}

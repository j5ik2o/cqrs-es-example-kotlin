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
        val retryParameters =
            mapOf(
                "STREAM_RETRY_MAX_ATTEMPTS" to "RetryMaxAttempts",
                "STREAM_RETRY_MAX_ELAPSED_MILLIS" to "RetryMaxElapsedMillis",
                "STREAM_RETRY_INITIAL_DELAY_MILLIS" to "RetryInitialDelayMillis",
                "STREAM_RETRY_MAX_DELAY_MILLIS" to "RetryMaxDelayMillis",
            )
        for ((variable, parameter) in retryParameters) {
            assertTrue(variables.at("/$variable").isObject, "$variable is missing")
            assertEquals(parameter, refText(variables.at("/$variable/Fn::Sub").asText()))
        }
        assertEquals(
            "RecoveryMaxTasksPerRun",
            refText(variables.at("/APP_RECOVERY_MAX_TASKS_PER_RUN/Fn::Sub").asText()),
        )
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
    fun `parameterizes every alarm threshold evaluation period and notification target`() {
        val template = template()
        val parameters = template.path("Parameters")
        val alarms = cloudWatchAlarms(template)

        assertTrue(alarms.size >= 7, "expected the recovery, retry, resync, and DLQ alarms")
        for ((name, alarm) in alarms) {
            val properties = alarm.path("Properties")
            assertEquals("AlarmPeriodSeconds", properties.at("/Period/Ref").asText(), "$name must share the alarm period")
            val evaluationPeriods = properties.at("/EvaluationPeriods/Ref").asText()
            assertTrue(parameters.has(evaluationPeriods), "$name references a missing parameter: $evaluationPeriods")
            val threshold = properties.at("/Threshold/Ref").asText()
            assertTrue(parameters.has(threshold), "$name references a missing parameter: $threshold")
            assertEquals("HasAlarmTopic", properties.at("/AlarmActions/Fn::If/0").asText(), "$name must gate its actions")
            assertEquals(
                "AlarmSnsTopicArn",
                properties.at("/AlarmActions/Fn::If/1/0/Ref").asText(),
                "$name must use the alarm topic parameter",
            )
            assertEquals(
                "AWS::NoValue",
                properties.at("/AlarmActions/Fn::If/2/Ref").asText(),
                "$name must create no action without a topic",
            )
        }
    }

    @Test
    fun `keeps the alarm parameter bounds meaningful`() {
        val parameters = template().path("Parameters")

        assertTrue(parameters.at("/AlarmPeriodSeconds/MinValue").asInt() >= 60)
        val allowedPeriods = parameters.at("/AlarmPeriodSeconds/AllowedValues")
        assertTrue(allowedPeriods.size() >= 2, "AlarmPeriodSeconds must restrict its values")
        val defaultPeriod = parameters.at("/AlarmPeriodSeconds/Default").asInt()
        assertTrue(allowedPeriods.any { it.asInt() == defaultPeriod }, "the default must be allowed")
        for (period in allowedPeriods) {
            assertEquals(0, period.asInt() % 60, "AlarmPeriodSeconds must be a multiple of 60: $period")
        }
        val names = parameters.fieldNames().asSequence().toList()
        for (name in names.filter { it.endsWith("EvaluationPeriods") }) {
            assertTrue(parameters.at("/$name/MinValue").asInt() >= 1, "$name must allow at least one period")
        }
        for (name in names.filter { it.endsWith("Threshold") }) {
            assertTrue(parameters.at("/$name/MinValue").asInt() >= 0, "$name must not allow a negative threshold")
        }
    }

    @Test
    fun `alarms on both dead letter queues`() {
        val template = template()

        assertEquals(
            "ApproximateNumberOfMessagesVisible",
            template.at("/Resources/OnFailureDlqAlarm/Properties/MetricName").asText(),
        )
        assertEquals(
            "OnFailureDlq",
            template.at("/Resources/OnFailureDlqAlarm/Properties/Dimensions/0/Value/Fn::GetAtt/0").asText(),
        )
        assertEquals(
            "RecoveryDlq",
            template.at("/Resources/RecoveryDlqAlarm/Properties/Dimensions/0/Value/Fn::GetAtt/0").asText(),
        )
    }

    @Test
    fun `ships example parameters that match the template`() {
        val parameters = template().path("Parameters")
        val example = exampleParameters()
        val keys = example.map { it.path("ParameterKey").asText() }

        assertEquals(keys.size, keys.toSet().size, "the example has duplicate parameter keys")
        for (key in keys) {
            assertTrue(parameters.has(key), "the example references an unknown parameter: $key")
        }
        for (required in ALARM_PARAMETERS) {
            assertTrue(keys.contains(required), "the example does not set $required")
        }
        val topic = example.first { it.path("ParameterKey").asText() == "AlarmSnsTopicArn" }.path("ParameterValue").asText()
        assertTrue(
            topic.isEmpty() || topic.contains(DUMMY_ACCOUNT_ID),
            "the example must not carry a real notification topic: $topic",
        )
        for (name in ALARM_PARAMETERS) {
            val value = example.first { it.path("ParameterKey").asText() == name }.path("ParameterValue").asText()
            assertNotNull(value.toIntOrNull(), "$name must be numeric in the example: $value")
        }
    }

    @Test
    fun `documents the long outage resync procedure and alarm response`() {
        val runbook = readDocument("docs/RMU_OPERATIONS.ja.md")

        for (snippet in RUNBOOK_SNIPPETS) {
            assertTrue(runbook.contains(snippet), "the operations runbook is missing $snippet")
        }
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

    private fun cloudWatchAlarms(template: JsonNode): Map<String, JsonNode> =
        template
            .path("Resources")
            .properties()
            .asSequence()
            .filter { it.value.path("Type").asText() == "AWS::CloudWatch::Alarm" }
            .associate { it.key to it.value }

    private fun exampleParameters(): List<JsonNode> =
        try {
            mapper.readTree(repositoryFile("deploy/parameters.example.json").toFile()).toList()
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }

    private fun readDocument(relative: String): String =
        try {
            Files.readString(repositoryFile(relative))
        } catch (e: IOException) {
            throw UncheckedIOException(e)
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
        private const val DUMMY_ACCOUNT_ID = "123456789012"

        private val ALARM_PARAMETERS =
            listOf(
                "AlarmPeriodSeconds",
                "RecoveryBacklogThreshold",
                "RecoveryBacklogEvaluationPeriods",
                "OnFailureQueueThreshold",
                "OnFailureQueueEvaluationPeriods",
                "OnFailureDlqThreshold",
                "OnFailureDlqEvaluationPeriods",
                "RecoveryDlqThreshold",
                "RecoveryDlqEvaluationPeriods",
                "RecoveryWorkerErrorsThreshold",
                "RecoveryWorkerErrorsEvaluationPeriods",
                "ResyncWorkerErrorsThreshold",
                "ResyncWorkerErrorsEvaluationPeriods",
                "ResyncScheduleFailedInvocationsThreshold",
                "ResyncScheduleFailedInvocationsEvaluationPeriods",
            )

        private val RUNBOOK_SNIPPETS =
            listOf(
                "update-event-source-mapping",
                "ResyncWorker",
                "read_model_recovery_tasks",
                "AlarmSnsTopicArn",
                "OnFailureDlqAlarm",
                "RecoveryDlqAlarm",
            )
    }
}

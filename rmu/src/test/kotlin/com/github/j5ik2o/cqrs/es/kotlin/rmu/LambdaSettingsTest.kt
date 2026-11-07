package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class LambdaSettingsTest {
    @Test
    fun `defaults match the runtime retry policy`() {
        val settings = LambdaSettings.from(env(emptyMap()))
        val policy = settings.retryPolicy()
        val defaults = RetryPolicy.defaults()

        assertEquals(defaults.maxAttempts, policy.maxAttempts)
        assertEquals(defaults.maxElapsed, policy.maxElapsed)
        assertEquals(defaults.initialDelay, policy.initialDelay)
        assertEquals(defaults.maxDelay, policy.maxDelay)
        assertEquals(LambdaSettings.DEFAULT_MAX_RECOVERY_TASKS_PER_RUN, settings.maxRecoveryTasksPerRun)
    }

    @Test
    fun `reads the retry and table overrides`() {
        val settings =
            LambdaSettings.from(
                env(
                    mapOf(
                        "HEAD_TABLE_NAME" to "head-v2",
                        "JOURNAL_TABLE_NAME" to "journal-v2",
                        "STREAM_RETRY_MAX_ATTEMPTS" to "7",
                        "STREAM_RETRY_MAX_ELAPSED_MILLIS" to "45000",
                        "STREAM_RETRY_INITIAL_DELAY_MILLIS" to "100",
                        "STREAM_RETRY_MAX_DELAY_MILLIS" to "9000",
                        "APP_RECOVERY_MAX_TASKS_PER_RUN" to "25",
                        "APP_RECOVERY_QUEUE_URL" to "https://queue.example",
                    ),
                ),
            )

        assertEquals("head-v2", settings.headTableName)
        assertEquals("journal-v2", settings.journalTableName)
        assertEquals(7, settings.retryMaxAttempts)
        assertEquals(45_000L, settings.retryMaxElapsedMillis)
        assertEquals(100L, settings.retryInitialDelayMillis)
        assertEquals(9_000L, settings.retryMaxDelayMillis)
        assertEquals(25, settings.maxRecoveryTasksPerRun)
        assertEquals("https://queue.example", settings.recoveryQueueUrl)
    }

    @Test
    fun `rejects a non-positive recovery task budget`() {
        assertThrows<IllegalArgumentException> {
            LambdaSettings.from(env(mapOf("APP_RECOVERY_MAX_TASKS_PER_RUN" to "0")))
        }
    }

    @Test
    fun `rejects a non-numeric retry budget`() {
        assertThrows<IllegalArgumentException> {
            LambdaSettings.from(env(mapOf("STREAM_RETRY_MAX_ELAPSED_MILLIS" to "soon")))
        }
    }

    @Test
    fun `requires a datasource url without a secret`() {
        assertThrows<IllegalArgumentException> {
            DatabaseSettings.resolve(LambdaSettings())
        }
    }

    @Test
    fun `reads the secret and lets its url override the parameter`() {
        val settings = LambdaSettings(dataSourceUrl = "jdbc:mysql://parameter/db")

        val database =
            DatabaseSettings.resolve(
                settings,
                """{"username":"secret-user","password":"secret-pass","url":"jdbc:mysql://secret/db"}""",
            )

        assertEquals("jdbc:mysql://secret/db", database.url)
        assertEquals("secret-user", database.username)
        assertEquals("secret-pass", database.password)
    }

    @Test
    fun `uses the parameter url when the secret has none`() {
        val settings = LambdaSettings(dataSourceUrl = "jdbc:mysql://parameter/db")

        val database = DatabaseSettings.resolve(settings, """{"username":"u","password":"p"}""")

        assertEquals("jdbc:mysql://parameter/db", database.url)
    }

    private fun env(values: Map<String, String>): (String) -> String? = { name -> values[name] }
}

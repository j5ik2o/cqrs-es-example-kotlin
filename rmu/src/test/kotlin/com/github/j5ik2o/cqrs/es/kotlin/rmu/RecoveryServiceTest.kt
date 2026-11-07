package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
class RecoveryServiceTest {
    private val dataSource by lazy {
        DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)
    }
    private val jdbcTemplate by lazy { JdbcTemplate(dataSource) }
    private val store by lazy { JdbcRecoveryTaskStore(jdbcTemplate) }

    @BeforeEach
    fun clearTasks() {
        jdbcTemplate.update("DELETE FROM read_model_recovery_tasks")
    }

    @Test
    fun `record is idempotent on the task key`() {
        val aggregateId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Z"
        val service = newService(FakeApplier())

        service.record(
            aggregateId,
            3,
            3,
            PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad"),
        )
        service.record(
            aggregateId,
            3,
            3,
            PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad again"),
        )

        val pending = store.listPending()
        assertEquals(1, pending.size)
        assertEquals(FailureKind.EVENT_PAYLOAD, pending[0].failureKind)
        assertEquals(3L, pending[0].seqNr)
    }

    @Test
    fun `recover applies through the target and completes the task`() {
        val aggregateId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0Y"
        val applier = FakeApplier()
        val service = newService(applier)
        service.record(aggregateId, 3, 3, PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad"))
        val task = store.listPending().first { it.aggregateId == aggregateId }

        service.recover(task)

        assertEquals(listOf(aggregateId to 3L), applier.applied)
        assertEquals(3L, applier.position(aggregateId))
        assertTrue(store.listPending().none { it.aggregateId == aggregateId })
    }

    @Test
    fun `recover is idempotent`() {
        val aggregateId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0X"
        val applier = FakeApplier()
        val service = newService(applier)
        service.record(aggregateId, 1, 1, PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad"))
        val task = store.listPending().first { it.aggregateId == aggregateId }

        service.recover(task)
        service.recover(task)

        assertEquals(1, applier.applied.size)
        assertEquals(1L, applier.position(aggregateId))
        assertFalse(store.listPending().any { it.aggregateId == aggregateId })
    }

    @Test
    fun `recover fails when the position does not reach the target`() {
        val aggregateId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0W"
        val applier = NonAdvancingApplier()
        val service = newService(applier)
        service.record(aggregateId, 5, 5, PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad"))

        assertThrows<RecoveryException> { service.recoverPending() }
        assertTrue(store.listPending().any { it.aggregateId == aggregateId })
    }

    @Test
    fun `recoverPending skips a task from another store`() {
        val aggregateId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0V"
        store.record(
            RecoveryTask(
                storeId = "other-store",
                projectionName = Projection.NAME,
                projectionVersion = Projection.VERSION,
                aggregateId = aggregateId,
                seqNr = 1,
                failureKind = FailureKind.UNKNOWN,
                errorMessage = "other store",
                status = RecoveryTask.STATUS_PENDING,
            ),
        )
        val service = newService(FakeApplier())

        val completed = service.recoverPending()

        assertEquals(0, completed)
        assertTrue(store.listPending().any { it.aggregateId == aggregateId })
    }

    @Test
    fun `a notifier failure does not lose the task`() {
        val aggregateId = "GroupChat-01H42K4ABWQ5V2XQEP3A48VE0U"
        val service =
            RecoveryService(
                store,
                StoreIdProvider { STORE_ID },
                FakeApplier(),
                RecoveryNotifier { throw IllegalStateException("notification down") },
            )

        service.record(aggregateId, 1, 1, PermanentFailure(FailureKind.EVENT_PAYLOAD, "bad"))

        assertTrue(store.listPending().any { it.aggregateId == aggregateId })
    }

    private fun newService(applier: EventApplier): RecoveryService = RecoveryService(store, StoreIdProvider { STORE_ID }, applier, null)

    private class FakeApplier : EventApplier {
        private val positions = mutableMapOf<String, Long>()
        val applied = mutableListOf<Pair<String, Long>>()

        override fun position(aggregateId: String): Long = positions[aggregateId] ?: 0L

        override fun applyHeadEvent(
            aggregateId: String,
            event: HeadEvent,
        ) {
            positions[aggregateId] = event.seqNr
        }

        override fun applyThrough(
            aggregateId: String,
            targetSeqNr: Long,
        ) {
            applied.add(aggregateId to targetSeqNr)
            positions[aggregateId] = targetSeqNr
        }
    }

    private class NonAdvancingApplier : EventApplier {
        private val positions = mutableMapOf<String, Long>()

        override fun position(aggregateId: String): Long = positions[aggregateId] ?: 0L

        override fun applyHeadEvent(
            aggregateId: String,
            event: HeadEvent,
        ) {
            positions[aggregateId] = event.seqNr
        }

        override fun applyThrough(
            aggregateId: String,
            targetSeqNr: Long,
        ) {
            // Deliberately does not advance the position.
        }
    }

    companion object {
        private const val STORE_ID = "store-0001"

        @Container
        @JvmField
        internal val mysql: TestMySQLContainer = TestMySQLContainer()

        @JvmStatic
        @BeforeAll
        fun migrate() {
            Flyway
                .configure()
                .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
                .locations("filesystem:tools/refinery/migrations")
                .load()
                .migrate()
        }
    }
}

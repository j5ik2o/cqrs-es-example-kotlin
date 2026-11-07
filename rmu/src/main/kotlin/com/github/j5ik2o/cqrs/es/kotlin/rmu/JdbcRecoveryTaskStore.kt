package com.github.j5ik2o.cqrs.es.kotlin.rmu

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Instant

/** MySQL implementation of the recovery task store. */
class JdbcRecoveryTaskStore(
    private val jdbcTemplate: JdbcTemplate,
) : RecoveryTaskStore {
    override fun record(task: RecoveryTask) {
        val now = Instant.now()
        val createdAt = task.createdAt ?: now
        val updatedAt = task.updatedAt ?: now
        jdbcTemplate.update(
            UPSERT,
            task.storeId,
            task.projectionName,
            task.projectionVersion,
            task.aggregateId,
            task.seqNr,
            task.failureKind.asString(),
            task.errorMessage ?: "",
            RecoveryTask.STATUS_PENDING,
            Timestamp.from(createdAt),
            Timestamp.from(updatedAt),
        )
    }

    override fun listPending(): List<RecoveryTask> = jdbcTemplate.query(SELECT_PENDING, ROW_MAPPER)

    override fun markCompleted(
        storeId: String,
        projectionName: String,
        aggregateId: String,
        seqNr: Long,
    ) {
        jdbcTemplate.update(
            MARK_COMPLETED,
            Timestamp.from(Instant.now()),
            storeId,
            projectionName,
            aggregateId,
            seqNr,
        )
    }

    override fun markFailed(
        task: RecoveryTask,
        failure: Throwable?,
    ) {
        jdbcTemplate.update(
            MARK_FAILED,
            "recovery attempt failed: ${failure?.message ?: ""}",
            Timestamp.from(Instant.now()),
            task.storeId,
            task.projectionName,
            task.aggregateId,
            task.seqNr,
        )
    }

    companion object {
        private val ROW_MAPPER =
            RowMapper<RecoveryTask> { rs, _ ->
                RecoveryTask(
                    storeId = rs.getString("store_id"),
                    projectionName = rs.getString("projection_name"),
                    projectionVersion = rs.getString("projection_version"),
                    aggregateId = rs.getString("aggregate_id"),
                    seqNr = rs.getLong("seq_nr"),
                    failureKind = FailureKind.fromString(rs.getString("failure_type")),
                    errorMessage = rs.getString("error_message"),
                    status = rs.getString("status"),
                )
            }

        private const val UPSERT = """
            INSERT INTO read_model_recovery_tasks
                (store_id, projection_name, projection_version, aggregate_id, seq_nr, failure_type, error_message, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE
                projection_version = new.projection_version,
                failure_type = new.failure_type,
                error_message = new.error_message,
                status = new.status,
                updated_at = new.updated_at
        """

        private const val SELECT_PENDING = """
            SELECT store_id, projection_name, projection_version, aggregate_id, seq_nr,
                   failure_type, error_message, status
            FROM read_model_recovery_tasks
            WHERE status = 'pending'
            ORDER BY updated_at, aggregate_id, seq_nr
        """

        private const val MARK_COMPLETED = """
            UPDATE read_model_recovery_tasks
            SET status = 'completed', updated_at = ?
            WHERE store_id = ? AND projection_name = ? AND aggregate_id = ? AND seq_nr = ?
        """

        private const val MARK_FAILED = """
            UPDATE read_model_recovery_tasks
            SET error_message = ?, updated_at = ?
            WHERE store_id = ? AND projection_name = ? AND aggregate_id = ? AND seq_nr = ?
        """
    }
}

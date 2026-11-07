package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization.GroupChatEventSerializer
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Member
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant

/**
 * Applies head stream and journal events to the read model.
 *
 * Only `position + 1` is applied. The `read_model_positions` row is locked with `SELECT ... FOR
 * UPDATE`, and the read model change and the position advance commit in the same transaction. A
 * gap in the journal fails without advancing the position.
 */
class ReadModelApplier(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val journalReader: JournalReader,
    private val eventSerializer: GroupChatEventSerializer = GroupChatEventSerializer(),
    private val readAheadEvents: Int = 100,
) : EventApplier {
    override fun position(aggregateId: String): Long =
        jdbcTemplate
            .query(SELECT_POSITION, { rs, _ -> rs.getLong(1) }, aggregateId)
            .firstOrNull() ?: 0L

    override fun applyHeadEvent(
        aggregateId: String,
        event: HeadEvent,
    ) {
        val current = position(aggregateId)
        if (event.seqNr <= current) {
            return
        }
        if (event.seqNr > current + 1) {
            applyThrough(aggregateId, event.seqNr - 1)
        }
        applyEvent(aggregateId, parseAggregateId(aggregateId), event)
    }

    override fun applyThrough(
        aggregateId: String,
        targetSeqNr: Long,
    ) {
        if (targetSeqNr <= 0) return
        val groupChatId = parseAggregateId(aggregateId)
        var current = position(aggregateId)
        while (current < targetSeqNr) {
            val from = current + 1
            val to = minOf(current + readAheadEvents, targetSeqNr)
            val events = journalReader.readEvents(aggregateId, from, to)
            requireContiguous(from, to, events)
            for (event in events) {
                applyEvent(aggregateId, groupChatId, event)
            }
            val after = position(aggregateId)
            if (after <= current) {
                throw ReadModelUpdateException(
                    "aggregate $aggregateId: position stayed at $after while replaying to $targetSeqNr",
                )
            }
            current = after
        }
    }

    private fun applyEvent(
        aggregateId: String,
        groupChatId: GroupChatId,
        event: HeadEvent,
    ) {
        var attempt = 1
        while (true) {
            try {
                applyEventInTransaction(aggregateId, groupChatId, event)
                return
            } catch (e: RuntimeException) {
                if (attempt >= MAX_TRANSACTION_ATTEMPTS || !isTransactionConflict(e)) {
                    throw e
                }
                sleepQuietly(5L * attempt)
                attempt++
            }
        }
    }

    private fun applyEventInTransaction(
        aggregateId: String,
        groupChatId: GroupChatId,
        event: HeadEvent,
    ) {
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update(
                INSERT_POSITION_IF_ABSENT,
                aggregateId,
                Timestamp.from(Instant.now()),
            )
            val current =
                jdbcTemplate
                    .query(SELECT_POSITION_FOR_UPDATE, { rs, _ -> rs.getLong(1) }, aggregateId)
                    .firstOrNull() ?: 0L
            if (event.seqNr <= current) {
                return@executeWithoutResult
            }
            if (event.seqNr != current + 1) {
                throw PermanentFailure(
                    FailureKind.JOURNAL_GAP,
                    "expected seq_nr ${current + 1} but got ${event.seqNr}",
                )
            }
            applyEventToReadModel(groupChatId, deserialize(event), event.occurredAt)
            jdbcTemplate.update(
                UPSERT_POSITION,
                aggregateId,
                event.seqNr,
                Timestamp.from(Instant.now()),
            )
        }
    }

    private fun applyEventToReadModel(
        groupChatId: GroupChatId,
        event: GroupChatEvent,
        occurredAt: Instant,
    ) {
        val timestamp = Timestamp.from(occurredAt)
        when (event) {
            is GroupChatEvent.Created -> {
                jdbcTemplate.update(
                    INSERT_GROUP_CHAT,
                    groupChatId.asString(),
                    event.name.asString(),
                    event.executorId.asString(),
                    timestamp,
                    timestamp,
                )
                val administrator =
                    event.members.values.firstOrNull()
                        ?: throw PermanentFailure(
                            FailureKind.EVENT_PAYLOAD,
                            "Created event has no members",
                        )
                insertMember(groupChatId, administrator, timestamp)
            }

            is GroupChatEvent.Deleted -> {
                jdbcTemplate.update(DELETE_GROUP_CHAT, timestamp, groupChatId.asString())
            }

            is GroupChatEvent.Renamed -> {
                jdbcTemplate.update(
                    UPDATE_GROUP_CHAT_NAME,
                    event.name.asString(),
                    timestamp,
                    groupChatId.asString(),
                )
            }

            is GroupChatEvent.MemberAdded -> {
                insertMember(groupChatId, event.member, timestamp)
            }

            is GroupChatEvent.MemberRemoved -> {
                jdbcTemplate.update(
                    DELETE_MEMBER,
                    groupChatId.asString(),
                    event.member.userAccountId.asString(),
                )
            }

            is GroupChatEvent.MessagePosted -> {
                jdbcTemplate.update(
                    INSERT_MESSAGE,
                    event.message.id.asString(),
                    groupChatId.asString(),
                    event.message.senderId.asString(),
                    event.message.content,
                    timestamp,
                    timestamp,
                )
            }

            is GroupChatEvent.MessageEdited -> {
                jdbcTemplate.update(
                    UPDATE_MESSAGE,
                    event.message.content,
                    timestamp,
                    event.message.id.asString(),
                )
            }

            is GroupChatEvent.MessageDeleted -> {
                jdbcTemplate.update(
                    DELETE_MESSAGE,
                    timestamp,
                    event.message.id.asString(),
                )
            }
        }
    }

    private fun insertMember(
        groupChatId: GroupChatId,
        member: Member,
        timestamp: Timestamp,
    ) {
        jdbcTemplate.update(
            INSERT_MEMBER,
            member.id.asString(),
            groupChatId.asString(),
            member.userAccountId.asString(),
            member.role.name,
            timestamp,
            timestamp,
        )
    }

    private fun deserialize(event: HeadEvent): GroupChatEvent =
        try {
            eventSerializer.deserialize(event.payload)
                ?: throw PermanentFailure(
                    FailureKind.EVENT_PAYLOAD,
                    "failed to deserialize event with seq_nr ${event.seqNr}",
                )
        } catch (e: PermanentFailure) {
            throw e
        } catch (e: Exception) {
            throw PermanentFailure(
                FailureKind.EVENT_PAYLOAD,
                "failed to deserialize event with seq_nr ${event.seqNr}",
                e,
            )
        }

    private fun parseAggregateId(aggregateId: String): GroupChatId =
        try {
            GroupChatId.ofString(aggregateId)
        } catch (e: RuntimeException) {
            throw PermanentFailure(
                FailureKind.EVENT_PAYLOAD,
                "invalid aggregate id $aggregateId",
                e,
            )
        }

    private fun requireContiguous(
        fromSeqNr: Long,
        toSeqNr: Long,
        events: List<HeadEvent>,
    ) {
        var expected = fromSeqNr
        for (event in events) {
            if (event.seqNr != expected) {
                throw PermanentFailure(
                    FailureKind.JOURNAL_GAP,
                    "journal gap: expected seq_nr $expected but got ${event.seqNr}",
                )
            }
            expected++
        }
        if (expected != toSeqNr + 1) {
            throw PermanentFailure(
                FailureKind.JOURNAL_GAP,
                "journal is missing seq_nr $expected..$toSeqNr",
            )
        }
    }

    private fun isTransactionConflict(failure: Throwable): Boolean {
        var cause: Throwable? = failure
        while (cause != null) {
            if (cause is SQLException && (cause.errorCode == 1213 || cause.errorCode == 1205)) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RetryInterruptedException(e)
        }
    }

    companion object {
        private const val MAX_TRANSACTION_ATTEMPTS = 5

        private const val INSERT_POSITION_IF_ABSENT = """
            INSERT INTO read_model_positions (aggregate_id, seq_nr, updated_at)
            VALUES (?, 0, ?)
            ON DUPLICATE KEY UPDATE aggregate_id = aggregate_id
        """

        private const val SELECT_POSITION =
            "SELECT seq_nr FROM read_model_positions WHERE aggregate_id = ?"

        private const val SELECT_POSITION_FOR_UPDATE =
            "SELECT seq_nr FROM read_model_positions WHERE aggregate_id = ? FOR UPDATE"

        private const val UPSERT_POSITION = """
            INSERT INTO read_model_positions (aggregate_id, seq_nr, updated_at)
            VALUES (?, ?, ?)
            AS new ON DUPLICATE KEY UPDATE seq_nr = new.seq_nr, updated_at = new.updated_at
        """

        private const val INSERT_GROUP_CHAT = """
            INSERT INTO group_chats (id, disabled, name, owner_id, created_at, updated_at)
            VALUES (?, 0, ?, ?, ?, ?)
        """

        private const val UPDATE_GROUP_CHAT_NAME =
            "UPDATE group_chats SET name = ?, updated_at = ? WHERE id = ?"

        private const val DELETE_GROUP_CHAT =
            "UPDATE group_chats SET disabled = 1, updated_at = ? WHERE id = ?"

        private const val INSERT_MEMBER = """
            INSERT INTO members (id, group_chat_id, user_account_id, role, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """

        private const val DELETE_MEMBER =
            "DELETE FROM members WHERE group_chat_id = ? AND user_account_id = ?"

        private const val INSERT_MESSAGE = """
            INSERT INTO messages (id, disabled, group_chat_id, user_account_id, text, created_at, updated_at)
            VALUES (?, 0, ?, ?, ?, ?, ?)
        """

        private const val UPDATE_MESSAGE =
            "UPDATE messages SET text = ?, updated_at = ? WHERE id = ?"

        private const val DELETE_MESSAGE =
            "UPDATE messages SET disabled = 1, updated_at = ? WHERE id = ?"
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization.GroupChatEventSerializer
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChat
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatName
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Member
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MemberId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MemberRole
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Message
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MessageId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.PendingEvent
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers
class ReadModelUpdaterTest {
    private val serializer = GroupChatEventSerializer()
    private val dataSource by lazy {
        DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)
    }
    private val jdbcTemplate by lazy { JdbcTemplate(dataSource) }
    private val transactionTemplate by lazy {
        TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    fun `applies events and advances the position`() {
        val fixture = newFixture()
        val applier = newApplier()

        fixture.headEvents.take(5).forEach {
            applier.applyHeadEvent(fixture.aggregateId.asString(), it)
        }

        assertEquals(5L, applier.position(fixture.aggregateId.asString()))
        val groupChat =
            jdbcTemplate.queryForMap(
                "SELECT id, disabled, name, owner_id FROM group_chats WHERE id = ?",
                fixture.aggregateId.asString(),
            )
        assertEquals("renamed", groupChat["name"])
        assertEquals(fixture.adminId.asString(), groupChat["owner_id"])
        assertEquals(2, countMembers(fixture.aggregateId))
        assertEquals(1, countMessages(fixture.aggregateId))
        val message =
            jdbcTemplate.queryForMap(
                "SELECT text FROM messages WHERE id = ?",
                fixture.messageId.asString(),
            )
        assertEquals("edited", message["text"])
    }

    @Test
    fun `applies removals and deletion`() {
        val fixture = newFixture()
        val applier = newApplier()

        fixture.headEvents.forEach {
            applier.applyHeadEvent(fixture.aggregateId.asString(), it)
        }

        assertEquals(8L, applier.position(fixture.aggregateId.asString()))
        assertEquals(
            true,
            jdbcTemplate.queryForObject(
                "SELECT disabled FROM group_chats WHERE id = ?",
                Boolean::class.java,
                fixture.aggregateId.asString(),
            ),
        )
        assertEquals(1, countMembers(fixture.aggregateId))
        assertEquals(
            true,
            jdbcTemplate.queryForObject(
                "SELECT disabled FROM messages WHERE id = ?",
                Boolean::class.java,
                fixture.messageId.asString(),
            ),
        )
    }

    @Test
    fun `ignores duplicate and stale events`() {
        val fixture = newFixture()
        val applier = newApplier()

        applier.applyHeadEvent(fixture.aggregateId.asString(), fixture.headEvents[0])
        applier.applyHeadEvent(fixture.aggregateId.asString(), fixture.headEvents[0])
        applier.applyHeadEvent(fixture.aggregateId.asString(), fixture.headEvents[1])
        applier.applyHeadEvent(fixture.aggregateId.asString(), fixture.headEvents[1])

        assertEquals(2L, applier.position(fixture.aggregateId.asString()))
        assertEquals(1, countMembers(fixture.aggregateId))
        assertEquals("renamed", groupChatName(fixture.aggregateId))
    }

    @Test
    fun `fills a gap from the journal before applying the head event`() {
        val fixture = newFixture()
        val journal = FakeJournalReader(mapOf(fixture.aggregateId.asString() to fixture.headEvents.take(2)))
        val applier = ReadModelApplier(jdbcTemplate, transactionTemplate, journal)

        applier.applyHeadEvent(fixture.aggregateId.asString(), fixture.headEvents[2])

        assertEquals(3L, applier.position(fixture.aggregateId.asString()))
        assertEquals(2, countMembers(fixture.aggregateId))
        assertEquals("renamed", groupChatName(fixture.aggregateId))
        assertEquals(
            listOf(Triple(fixture.aggregateId.asString(), 1L, 2L)),
            journal.requests,
        )
    }

    @Test
    fun `fails without advancing when the journal is missing an event`() {
        val fixture = newFixture()
        val applier = newApplier()

        assertFailsWith<ReadModelUpdateException> {
            applier.applyHeadEvent(fixture.aggregateId.asString(), fixture.headEvents[2])
        }

        assertEquals(0L, applier.position(fixture.aggregateId.asString()))
        assertEquals(0, countMembers(fixture.aggregateId))
    }

    private fun newApplier(): ReadModelApplier = ReadModelApplier(jdbcTemplate, transactionTemplate, FakeJournalReader(emptyMap()))

    private fun countMembers(aggregateId: GroupChatId): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM members WHERE group_chat_id = ?",
            Int::class.java,
            aggregateId.asString(),
        ) ?: 0

    private fun countMessages(aggregateId: GroupChatId): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messages WHERE group_chat_id = ?",
            Int::class.java,
            aggregateId.asString(),
        ) ?: 0

    private fun groupChatName(aggregateId: GroupChatId): String? =
        jdbcTemplate.queryForObject(
            "SELECT name FROM group_chats WHERE id = ?",
            String::class.java,
            aggregateId.asString(),
        )

    private fun newFixture(): Fixture {
        val aggregateId = GroupChatId.generate()
        val adminId = UserAccountId.generate()
        val memberId = UserAccountId.generate()
        val (aggregate1, created) =
            GroupChat.create(aggregateId, GroupChatName.of("test"), adminId)
        val (aggregate2, renamed) = aggregate1.rename(GroupChatName.of("renamed"), adminId)
        val member = Member.of(MemberId.generate(), memberId, MemberRole.MEMBER)
        val (aggregate3, memberAdded) = aggregate2.addMember(member, adminId)
        val message = Message.of(MessageId.generate(), "hello", memberId, LocalDateTime.now())
        val (aggregate4, posted) = aggregate3.postMessage(message, memberId)
        val (aggregate5, edited) = aggregate4.editMessage(message.id, "edited", memberId)
        val (aggregate6, deletedMessage) =
            aggregate5.deleteMessageByMessageId(message.id, memberId)
        val (aggregate7, removedMember) =
            aggregate6.removeMemberByUserAccountId(memberId, adminId)
        val (_, deleted) = aggregate7.delete(adminId)
        val pendings =
            listOf(
                created,
                renamed,
                memberAdded,
                posted,
                edited,
                deletedMessage,
                removedMember,
                deleted,
            )
        return Fixture(
            aggregateId = aggregateId,
            adminId = adminId,
            messageId = message.id,
            headEvents = pendings.map { it.toHeadEvent() },
        )
    }

    private fun PendingEvent.toHeadEvent(): HeadEvent =
        HeadEvent(
            seqNr = sequenceNumber,
            occurredAt = occurredAt,
            manifest = event.manifest(),
            payload = serializer.serialize(event),
        )

    private data class Fixture(
        val aggregateId: GroupChatId,
        val adminId: UserAccountId,
        val messageId: MessageId,
        val headEvents: List<HeadEvent>,
    )

    private class FakeJournalReader(
        private val eventsByAggregate: Map<String, List<HeadEvent>>,
    ) : JournalReader {
        val requests = mutableListOf<Triple<String, Long, Long>>()

        override fun readEvents(
            aggregateId: String,
            fromSeqNr: Long,
            toSeqNr: Long,
        ): List<HeadEvent> {
            requests.add(Triple(aggregateId, fromSeqNr, toSeqNr))
            return eventsByAggregate[aggregateId]
                .orEmpty()
                .filter { it.seqNr in fromSeqNr..toSeqNr }
        }
    }

    companion object {
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

internal class TestMySQLContainer : MySQLContainer<TestMySQLContainer>(DockerImageName.parse("mysql:8.0")) {
    init {
        withDatabaseName("ceer")
        withUsername("ceer")
        withPassword("ceer")
    }
}

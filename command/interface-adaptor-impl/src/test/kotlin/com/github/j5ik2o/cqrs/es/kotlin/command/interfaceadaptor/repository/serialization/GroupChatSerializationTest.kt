package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.ulid.UlidCreator
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChat
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatName
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Member
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MemberId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MemberRole
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Members
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Message
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MessageId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Messages
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupChatSerializationTest {
    private val eventSerializer = GroupChatEventSerializer()
    private val snapshotSerializer = GroupChatSnapshotSerializer()
    private val objectMapper = ObjectMapper()

    @Test
    fun `event payload carries the event type and no envelope metadata`() {
        val executorId = UserAccountId.generate()
        val event =
            GroupChatEvent.Created(
                id = UlidCreator.getMonotonicUlid(),
                executorId = executorId,
                name = GroupChatName.of("test"),
                members = Members.ofAdministratorId(executorId),
            )

        val payload = objectMapper.readTree(eventSerializer.serialize(event))

        assertEquals("GroupChatCreated", payload["type"].asText())
        assertEquals(event.id.toString(), payload["id"].asText())
        assertEquals(executorId.value.toString(), payload["executorId"]["value"].asText())
        assertTrue(payload["members"].isArray)
        assertFalse(payload.has("aggregateId"))
        assertFalse(payload.has("sequenceNumber"))
        assertFalse(payload.has("occurredAt"))
        assertFalse(payload.has("manifest"))
    }

    @Test
    fun `event payload round trips`() {
        val executorId = UserAccountId.generate()
        val member = Member.of(MemberId.generate(), UserAccountId.generate(), MemberRole.ADMINISTRATOR)
        val message =
            Message.of(MessageId.generate(), "hello", executorId, LocalDateTime.now())
        val events =
            listOf(
                GroupChatEvent.Created(
                    UlidCreator.getMonotonicUlid(),
                    executorId,
                    GroupChatName.of("test"),
                    Members.of(member),
                ),
                GroupChatEvent.Deleted(UlidCreator.getMonotonicUlid(), executorId),
                GroupChatEvent.Renamed(
                    UlidCreator.getMonotonicUlid(),
                    executorId,
                    GroupChatName.of("renamed"),
                ),
                GroupChatEvent.MemberAdded(UlidCreator.getMonotonicUlid(), executorId, member),
                GroupChatEvent.MemberRemoved(UlidCreator.getMonotonicUlid(), executorId, member),
                GroupChatEvent.MessagePosted(UlidCreator.getMonotonicUlid(), executorId, message),
                GroupChatEvent.MessageEdited(UlidCreator.getMonotonicUlid(), executorId, message),
                GroupChatEvent.MessageDeleted(UlidCreator.getMonotonicUlid(), executorId, message),
            )

        events.forEach { event ->
            val restored = eventSerializer.deserialize(eventSerializer.serialize(event))
            assertEquals(event, restored)
            assertEquals(event.typeName(), restored?.typeName())
        }
    }

    @Test
    fun `snapshot payload round trips`() {
        val executorId = UserAccountId.generate()
        val aggregate =
            GroupChat
                .create(
                    GroupChatId.generate(),
                    GroupChatName.of("test"),
                    executorId,
                ).first
        val withMessage =
            aggregate
                .postMessage(
                    Message.of(MessageId.generate(), "hello", executorId, LocalDateTime.now()),
                    executorId,
                ).first

        val restored = snapshotSerializer.deserialize(snapshotSerializer.serialize(withMessage))

        assertEquals(withMessage, restored)
        assertEquals(withMessage.sequenceNumber, restored?.sequenceNumber)
        assertEquals(1, restored?.messages?.values?.size)
    }
}

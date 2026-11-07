package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupChatTest {
    @Test
    fun `creates a group chat with the creator as administrator`() {
        val id = GroupChatId.generate()
        val name = GroupChatName.of("test")
        val executorId = UserAccountId.generate()

        val (aggregate, pending) = GroupChat.create(id, name, executorId)

        assertEquals(id, aggregate.id)
        assertEquals(name, aggregate.name)
        assertFalse(aggregate.deleted)
        assertEquals(1L, aggregate.sequenceNumber)
        assertEquals(1L, pending.sequenceNumber)
        assertTrue(aggregate.members.isAdministrator(executorId))
        assertEquals(1, aggregate.members.values.size)

        val created = pending.event as GroupChatEvent.Created
        assertEquals(name, created.name)
        assertEquals(executorId, created.executorId)
        assertEquals(GroupChatEvent.CREATED_TYPE, created.typeName())
        assertEquals("GroupChatCreated@1", created.manifest())
    }

    @Test
    fun `deletes a group chat by an administrator`() {
        val (aggregate, _) = newGroupChat()

        val (deleted, pending) = aggregate.delete(aggregate.administratorId())

        assertTrue(deleted.deleted)
        assertEquals(2L, deleted.sequenceNumber)
        assertEquals(2L, pending.sequenceNumber)
        assertTrue(pending.event is GroupChatEvent.Deleted)
    }

    @Test
    fun `rejects deletion by a non administrator`() {
        val (aggregate, _) = newGroupChat()
        val member = addMember(aggregate, MemberRole.MEMBER)

        assertThrows<GroupChatError.NotAdministratorError> {
            member.delete(member.memberId())
        }
    }

    @Test
    fun `rejects a second deletion`() {
        val (aggregate, _) = newGroupChat()
        val (deleted, _) = aggregate.delete(aggregate.administratorId())

        assertThrows<GroupChatError.AlreadyDeletedError> {
            deleted.delete(deleted.administratorId())
        }
    }

    @Test
    fun `renames a group chat`() {
        val (aggregate, _) = newGroupChat()

        val (renamed, pending) =
            aggregate.rename(GroupChatName.of("test-2"), aggregate.administratorId())

        assertEquals("test-2", renamed.name.asString())
        val event = pending.event as GroupChatEvent.Renamed
        assertEquals("test-2", event.name.asString())
    }

    @Test
    fun `rejects renaming to the same name`() {
        val (aggregate, _) = newGroupChat()

        assertThrows<GroupChatError.AlreadyRenamedError> {
            aggregate.rename(aggregate.name, aggregate.administratorId())
        }
    }

    @Test
    fun `adds and removes a member`() {
        val (aggregate, _) = newGroupChat()
        val withMember = addMember(aggregate, MemberRole.MEMBER)
        assertTrue(withMember.members.isMember(withMember.memberId()))

        val (removed, pending) =
            withMember.removeMemberByUserAccountId(
                withMember.memberId(),
                withMember.administratorId(),
            )

        assertFalse(removed.members.isMember(withMember.memberId()))
        assertTrue(pending.event is GroupChatEvent.MemberRemoved)
    }

    @Test
    fun `rejects adding an existing member`() {
        val (aggregate, _) = newGroupChat()
        val withMember = addMember(aggregate, MemberRole.MEMBER)
        val duplicate =
            Member.of(
                MemberId.generate(),
                withMember.memberId(),
                MemberRole.MEMBER,
            )

        assertThrows<GroupChatError.AlreadyMemberError> {
            withMember.addMember(duplicate, withMember.administratorId())
        }
    }

    @Test
    fun `rejects removing a non member`() {
        val (aggregate, _) = newGroupChat()

        assertThrows<GroupChatError.NotMemberError> {
            aggregate.removeMemberByUserAccountId(
                UserAccountId.generate(),
                aggregate.administratorId(),
            )
        }
    }

    @Test
    fun `posts a message`() {
        val (aggregate, _) = newGroupChat()
        val message = newMessage(aggregate.administratorId())

        val (posted, pending) = aggregate.postMessage(message, aggregate.administratorId())

        assertEquals(1, posted.messages.values.size)
        val event = pending.event as GroupChatEvent.MessagePosted
        assertEquals(message.id, event.message.id)
    }

    @Test
    fun `rejects posting by a non member`() {
        val (aggregate, _) = newGroupChat()

        assertThrows<GroupChatError.NotMemberError> {
            aggregate.postMessage(newMessage(UserAccountId.generate()), UserAccountId.generate())
        }
    }

    @Test
    fun `edits a message posted by the executor`() {
        val (aggregate, _) = newGroupChat()
        val message = newMessage(aggregate.administratorId())
        val (posted, _) = aggregate.postMessage(message, aggregate.administratorId())

        val (edited, pending) =
            posted.editMessage(message.id, "after", aggregate.administratorId())

        assertEquals("after", edited.messages.findByMessageId(message.id)?.content)
        val event = pending.event as GroupChatEvent.MessageEdited
        assertEquals("after", event.message.content)
    }

    @Test
    fun `rejects editing a message posted by another member`() {
        val (aggregate, _) = newGroupChat()
        val withMember = addMember(aggregate, MemberRole.MEMBER)
        val message = newMessage(withMember.administratorId())
        val (posted, _) = withMember.postMessage(message, withMember.administratorId())

        assertThrows<GroupChatError.NotSenderError> {
            posted.editMessage(message.id, "after", withMember.memberId())
        }
    }

    @Test
    fun `rejects editing with empty content`() {
        val (aggregate, _) = newGroupChat()
        val message = newMessage(aggregate.administratorId())
        val (posted, _) = aggregate.postMessage(message, aggregate.administratorId())

        assertThrows<GroupChatError.InvalidMessageError> {
            posted.editMessage(message.id, "", aggregate.administratorId())
        }
    }

    @Test
    fun `rejects editing a missing message`() {
        val (aggregate, _) = newGroupChat()

        assertThrows<GroupChatError.MessageNotFoundError> {
            aggregate.editMessage(MessageId.generate(), "after", aggregate.administratorId())
        }
    }

    @Test
    fun `deletes a message`() {
        val (aggregate, _) = newGroupChat()
        val message = newMessage(aggregate.administratorId())
        val (posted, _) = aggregate.postMessage(message, aggregate.administratorId())

        val (deleted, pending) =
            posted.deleteMessageByMessageId(message.id, aggregate.administratorId())

        assertTrue(deleted.messages.values.isEmpty())
        assertTrue(pending.event is GroupChatEvent.MessageDeleted)
    }

    @Test
    fun `rejects deleting a missing message`() {
        val (aggregate, _) = newGroupChat()

        assertThrows<GroupChatError.MessageNotFoundError> {
            aggregate.deleteMessageByMessageId(MessageId.generate(), aggregate.administratorId())
        }
    }

    @Test
    fun `replays events in order`() {
        val (aggregate, created) = newGroupChat()
        val (renamed, renamedEvent) =
            aggregate.rename(GroupChatName.of("renamed"), aggregate.administratorId())
        val message = newMessage(aggregate.administratorId())
        val (posted, postedEvent) =
            renamed.postMessage(message, aggregate.administratorId())

        val replayed =
            GroupChat.replay(
                listOf(renamedEvent.event, postedEvent.event),
                aggregate,
            )

        assertEquals(3L, replayed.sequenceNumber)
        assertEquals("renamed", replayed.name.asString())
        assertEquals(1, replayed.messages.values.size)
        assertEquals(1L, created.sequenceNumber)
        assertEquals(posted.sequenceNumber, replayed.sequenceNumber)
    }

    @Test
    fun `rejects applying the created event to an existing aggregate`() {
        val (aggregate, created) = newGroupChat()

        assertThrows<IllegalArgumentException> {
            aggregate.applyEvent(created.event)
        }
    }

    @Test
    fun `validates group chat names`() {
        assertThrows<IllegalArgumentException> { GroupChatName.of("") }
        assertThrows<IllegalArgumentException> { GroupChatName.of("a".repeat(256)) }
    }

    @Test
    fun `validates message content`() {
        assertThrows<IllegalArgumentException> {
            Message.of(MessageId.generate(), "", UserAccountId.generate(), LocalDateTime.now())
        }
        assertThrows<IllegalArgumentException> {
            Message.of(
                MessageId.generate(),
                "a".repeat(1001),
                UserAccountId.generate(),
                LocalDateTime.now(),
            )
        }
    }

    @Test
    fun `parses prefixed and raw aggregate ids`() {
        val id = GroupChatId.generate()
        assertEquals(id, GroupChatId.ofString(id.asString()))
        assertEquals(id, GroupChatId.ofString(id.value.toString()))

        val userAccountId = UserAccountId.generate()
        assertEquals(userAccountId, UserAccountId.ofString(userAccountId.asString()))
        assertEquals(userAccountId, UserAccountId.ofString(userAccountId.value.toString()))
    }

    private fun newGroupChat(): Pair<GroupChat, PendingEvent> =
        GroupChat.create(GroupChatId.generate(), GroupChatName.of("test"), UserAccountId.generate())

    private fun addMember(
        aggregate: GroupChat,
        role: MemberRole,
    ): GroupChat {
        val member = Member.of(MemberId.generate(), UserAccountId.generate(), role)
        val (withMember, _) = aggregate.addMember(member, aggregate.administratorId())
        return withMember
    }

    private fun newMessage(senderId: UserAccountId): Message = Message.of(MessageId.generate(), "hello", senderId, LocalDateTime.now())

    private fun GroupChat.administratorId(): UserAccountId = members.values.first { it.role == MemberRole.ADMINISTRATOR }.userAccountId

    private fun GroupChat.memberId(): UserAccountId = members.values.first { it.role == MemberRole.MEMBER }.userAccountId
}

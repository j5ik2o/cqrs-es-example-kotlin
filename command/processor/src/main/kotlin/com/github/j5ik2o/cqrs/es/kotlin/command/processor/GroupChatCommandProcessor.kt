package com.github.j5ik2o.cqrs.es.kotlin.command.processor

import com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.GroupChatRepository
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChat
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatName
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Member
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Message
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MessageId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.PendingEvent
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Command processor for GroupChat.
 *
 * Each command reads the aggregate, applies the change, and stores the result. Two concurrent
 * commands on the same aggregate can conflict; the later store fails the ESA optimistic lock and
 * the failure is returned as a GraphQL error. The processor does not retry, matching the Java v5
 * implementation.
 */
@Component
@Profile("write")
class GroupChatCommandProcessor(
    private val groupChatRepository: GroupChatRepository,
) {
    suspend fun createGroupChat(
        name: GroupChatName,
        creatorId: UserAccountId,
    ): GroupChatId {
        val (aggregate, event) = GroupChat.create(GroupChatId.generate(), name, creatorId)
        groupChatRepository.store(event, aggregate)
        return aggregate.id
    }

    suspend fun renameGroupChat(
        id: GroupChatId,
        name: GroupChatName,
        modifierId: UserAccountId,
    ): GroupChatId = modify(id) { it.rename(name, modifierId) }

    suspend fun deleteGroupChat(
        id: GroupChatId,
        deleterId: UserAccountId,
    ): GroupChatId = modify(id) { it.delete(deleterId) }

    suspend fun addMember(
        id: GroupChatId,
        member: Member,
        executorId: UserAccountId,
    ): GroupChatId = modify(id) { it.addMember(member, executorId) }

    suspend fun removeMember(
        id: GroupChatId,
        memberId: UserAccountId,
        executorId: UserAccountId,
    ): GroupChatId = modify(id) { it.removeMemberByUserAccountId(memberId, executorId) }

    suspend fun postMessage(
        id: GroupChatId,
        message: Message,
        executorId: UserAccountId,
    ): Pair<GroupChatId, MessageId> {
        val groupChat = findOrThrow(id)
        val (aggregate, event) = groupChat.postMessage(message, executorId)
        groupChatRepository.store(event, aggregate)
        return aggregate.id to message.id
    }

    suspend fun editMessage(
        id: GroupChatId,
        messageId: MessageId,
        content: String,
        executorId: UserAccountId,
    ): GroupChatId = modify(id) { it.editMessage(messageId, content, executorId) }

    suspend fun deleteMessage(
        id: GroupChatId,
        messageId: MessageId,
        executorId: UserAccountId,
    ): GroupChatId = modify(id) { it.deleteMessageByMessageId(messageId, executorId) }

    private suspend fun modify(
        id: GroupChatId,
        change: (GroupChat) -> Pair<GroupChat, PendingEvent>,
    ): GroupChatId {
        val groupChat = findOrThrow(id)
        val (aggregate, event) = change(groupChat)
        groupChatRepository.store(event, aggregate)
        return aggregate.id
    }

    private suspend fun findOrThrow(id: GroupChatId): GroupChat =
        groupChatRepository.findById(id) ?: throw NotFoundProcessException("not found")
}

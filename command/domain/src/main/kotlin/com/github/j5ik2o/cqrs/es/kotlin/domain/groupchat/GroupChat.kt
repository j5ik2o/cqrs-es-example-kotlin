package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.f4b6a3.ulid.UlidCreator
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import java.time.Instant

data class GroupChat(
    @param:JsonProperty("id")
    @get:JsonProperty("id")
    val id: GroupChatId,
    @param:JsonProperty("deleted")
    @get:JsonProperty("deleted")
    val deleted: Boolean,
    @param:JsonProperty("name")
    @get:JsonProperty("name")
    val name: GroupChatName,
    @param:JsonProperty("members")
    @get:JsonProperty("members")
    val members: Members,
    @param:JsonProperty("messages")
    @get:JsonProperty("messages")
    val messages: Messages,
    @param:JsonProperty("sequenceNumber")
    @get:JsonProperty("sequenceNumber")
    val sequenceNumber: Long,
) {
    fun delete(executorId: UserAccountId): Pair<GroupChat, PendingEvent> {
        if (deleted) throw GroupChatError.AlreadyDeletedError(id)
        if (!members.isAdministrator(executorId)) {
            throw GroupChatError.NotAdministratorError(id, executorId)
        }
        return next(
            copy(deleted = true),
            GroupChatEvent.Deleted(UlidCreator.getMonotonicUlid(), executorId),
        )
    }

    fun rename(
        name: GroupChatName,
        executorId: UserAccountId,
    ): Pair<GroupChat, PendingEvent> {
        if (deleted) throw GroupChatError.AlreadyDeletedError(id)
        if (!members.isMember(executorId)) throw GroupChatError.NotMemberError(id, executorId)
        if (this.name == name) throw GroupChatError.AlreadyRenamedError(id, name)
        return next(
            copy(name = name),
            GroupChatEvent.Renamed(UlidCreator.getMonotonicUlid(), executorId, name),
        )
    }

    fun addMember(
        member: Member,
        executorId: UserAccountId,
    ): Pair<GroupChat, PendingEvent> {
        if (deleted) throw GroupChatError.AlreadyDeletedError(id)
        if (!members.isAdministrator(executorId)) {
            throw GroupChatError.NotAdministratorError(id, executorId)
        }
        if (members.isMember(member.userAccountId)) {
            throw GroupChatError.AlreadyMemberError(id, member.userAccountId)
        }
        return next(
            copy(members = members.add(member)),
            GroupChatEvent.MemberAdded(UlidCreator.getMonotonicUlid(), executorId, member),
        )
    }

    fun removeMemberByUserAccountId(
        userAccountId: UserAccountId,
        executorId: UserAccountId,
    ): Pair<GroupChat, PendingEvent> {
        if (deleted) throw GroupChatError.AlreadyDeletedError(id)
        if (!members.isAdministrator(executorId)) {
            throw GroupChatError.NotAdministratorError(id, executorId)
        }
        val removed =
            members.removeByUserAccountId(userAccountId)
                ?: throw GroupChatError.NotMemberError(id, userAccountId)
        return next(
            copy(members = removed.second),
            GroupChatEvent.MemberRemoved(UlidCreator.getMonotonicUlid(), executorId, removed.first),
        )
    }

    fun postMessage(
        message: Message,
        executorId: UserAccountId,
    ): Pair<GroupChat, PendingEvent> {
        if (deleted) throw GroupChatError.AlreadyDeletedError(id)
        if (!members.isMember(executorId)) throw GroupChatError.NotMemberError(id, executorId)
        return next(
            copy(messages = messages.add(message)),
            GroupChatEvent.MessagePosted(UlidCreator.getMonotonicUlid(), executorId, message),
        )
    }

    fun editMessage(
        messageId: MessageId,
        content: String,
        executorId: UserAccountId,
    ): Pair<GroupChat, PendingEvent> {
        if (deleted) throw GroupChatError.AlreadyDeletedError(id)
        if (!members.isMember(executorId)) throw GroupChatError.NotMemberError(id, executorId)
        val message =
            messages.findByMessageId(messageId)
                ?: throw GroupChatError.MessageNotFoundError(id, messageId)
        if (message.senderId != executorId) throw GroupChatError.NotSenderError(id, executorId)
        val edited =
            try {
                messages.editByMessageId(messageId, content)
            } catch (e: IllegalArgumentException) {
                throw GroupChatError.InvalidMessageError(id, e.message ?: "invalid message")
            }
        return next(
            copy(messages = edited.second),
            GroupChatEvent.MessageEdited(UlidCreator.getMonotonicUlid(), executorId, edited.first),
        )
    }

    fun deleteMessageByMessageId(
        messageId: MessageId,
        executorId: UserAccountId,
    ): Pair<GroupChat, PendingEvent> {
        if (deleted) throw GroupChatError.AlreadyDeletedError(id)
        if (!members.isMember(executorId)) throw GroupChatError.NotMemberError(id, executorId)
        val removed =
            messages.removeByMessageId(messageId)
                ?: throw GroupChatError.MessageNotFoundError(id, messageId)
        return next(
            copy(messages = removed.second),
            GroupChatEvent.MessageDeleted(UlidCreator.getMonotonicUlid(), executorId, removed.first),
        )
    }

    /**
     * Applies a persisted event. Envelope metadata is not part of the payload, so the aggregate
     * derives the sequence number from its own position.
     */
    fun applyEvent(event: GroupChatEvent): GroupChat {
        val nextSequenceNumber = sequenceNumber + 1
        return when (event) {
            is GroupChatEvent.Created -> {
                throw IllegalArgumentException(
                    "Created must be the first event of an aggregate without a snapshot",
                )
            }

            is GroupChatEvent.Deleted -> {
                copy(deleted = true, sequenceNumber = nextSequenceNumber)
            }

            is GroupChatEvent.Renamed -> {
                copy(name = event.name, sequenceNumber = nextSequenceNumber)
            }

            is GroupChatEvent.MemberAdded -> {
                copy(members = members.add(event.member), sequenceNumber = nextSequenceNumber)
            }

            is GroupChatEvent.MemberRemoved -> {
                copy(
                    members = members.removeByUserAccountIdOrSelf(event.member.userAccountId),
                    sequenceNumber = nextSequenceNumber,
                )
            }

            is GroupChatEvent.MessagePosted -> {
                copy(messages = messages.add(event.message), sequenceNumber = nextSequenceNumber)
            }

            is GroupChatEvent.MessageEdited -> {
                copy(messages = messages.replace(event.message), sequenceNumber = nextSequenceNumber)
            }

            is GroupChatEvent.MessageDeleted -> {
                copy(
                    messages = messages.removeByMessageIdOrSelf(event.message.id),
                    sequenceNumber = nextSequenceNumber,
                )
            }
        }
    }

    private fun next(
        aggregate: GroupChat,
        event: GroupChatEvent,
    ): Pair<GroupChat, PendingEvent> {
        val nextSequenceNumber = sequenceNumber + 1
        return aggregate.copy(sequenceNumber = nextSequenceNumber) to
            PendingEvent(
                aggregateId = id,
                sequenceNumber = nextSequenceNumber,
                occurredAt = Instant.now(),
                event = event,
            )
    }

    companion object {
        fun create(
            id: GroupChatId,
            name: GroupChatName,
            executorId: UserAccountId,
        ): Pair<GroupChat, PendingEvent> {
            val members = Members.ofAdministratorId(executorId)
            val aggregate =
                GroupChat(
                    id = id,
                    deleted = false,
                    name = name,
                    members = members,
                    messages = Messages.empty(),
                    sequenceNumber = 1,
                )
            val event =
                GroupChatEvent.Created(
                    id = UlidCreator.getMonotonicUlid(),
                    executorId = executorId,
                    name = name,
                    members = members,
                )
            return aggregate to
                PendingEvent(
                    aggregateId = id,
                    sequenceNumber = 1,
                    occurredAt = Instant.now(),
                    event = event,
                )
        }

        fun replay(
            events: List<GroupChatEvent>,
            snapshot: GroupChat,
        ): GroupChat = events.fold(snapshot) { aggregate, event -> aggregate.applyEvent(event) }

        fun from(
            id: GroupChatId,
            deleted: Boolean,
            name: GroupChatName,
            members: Members,
            messages: Messages,
            sequenceNumber: Long,
        ): GroupChat =
            GroupChat(
                id = id,
                deleted = deleted,
                name = name,
                members = members,
                messages = messages,
                sequenceNumber = sequenceNumber,
            )
    }
}

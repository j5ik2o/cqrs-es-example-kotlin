package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository

import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChat
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.PendingEvent

interface GroupChatRepository {
    suspend fun findById(id: GroupChatId): GroupChat?

    suspend fun store(
        event: PendingEvent,
        aggregate: GroupChat,
    )
}

class RepositoryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

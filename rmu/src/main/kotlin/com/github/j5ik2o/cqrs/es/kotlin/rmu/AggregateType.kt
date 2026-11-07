package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** The aggregate id prefixes the read model updater recognises. */
object AggregateType {
    const val GROUP_CHAT = "GroupChat"
    private const val GROUP_CHAT_ID_PREFIX = "$GROUP_CHAT-"

    fun isGroupChat(aggregateId: String?): Boolean = aggregateId != null && aggregateId.startsWith(GROUP_CHAT_ID_PREFIX)
}

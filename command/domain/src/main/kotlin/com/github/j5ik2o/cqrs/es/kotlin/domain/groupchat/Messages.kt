package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonProperty

data class Messages(
    @param:JsonProperty("values")
    @get:JsonProperty("values")
    val values: List<Message>,
) {
    fun add(message: Message): Messages = Messages(values + message)

    fun replace(message: Message): Messages = Messages(values.map { if (it.id == message.id) message else it })

    fun removeByMessageId(messageId: MessageId): Pair<Message, Messages>? {
        val message = values.firstOrNull { it.id == messageId } ?: return null
        return message to Messages(values.filterNot { it == message })
    }

    fun removeByMessageIdOrSelf(messageId: MessageId): Messages = removeByMessageId(messageId)?.second ?: this

    fun editByMessageId(
        messageId: MessageId,
        content: String,
    ): Pair<Message, Messages> {
        val message =
            values.firstOrNull { it.id == messageId }
                ?: throw IllegalArgumentException("message not found: ${messageId.asString()}")
        val edited = Message.of(message.id, content, message.senderId, message.createdAt)
        return edited to replace(edited)
    }

    fun findByMessageId(messageId: MessageId): Message? = values.firstOrNull { it.id == messageId }

    fun containsByMessageId(messageId: MessageId): Boolean = values.any { it.id == messageId }

    fun toList(): List<Message> = values

    companion object {
        fun of(vararg values: Message): Messages = Messages(values.toList())

        fun from(values: List<Message>): Messages = Messages(values)

        fun empty(): Messages = Messages(emptyList())
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import java.time.LocalDateTime

data class Message(
    @param:JsonProperty("id")
    @get:JsonProperty("id")
    val id: MessageId,
    @param:JsonProperty("content")
    @get:JsonProperty("content")
    val content: String,
    @param:JsonProperty("senderId")
    @get:JsonProperty("senderId")
    val senderId: UserAccountId,
    @param:JsonProperty("createdAt")
    @get:JsonProperty("createdAt")
    val createdAt: LocalDateTime,
) {
    companion object {
        const val MAX_CONTENT_LENGTH = 1000

        fun of(
            id: MessageId,
            content: String,
            senderId: UserAccountId,
            createdAt: LocalDateTime,
        ): Message {
            requireValidContent(content)
            return Message(id, content, senderId, createdAt)
        }

        fun requireValidContent(content: String) {
            require(content.isNotEmpty()) { "content is empty" }
            require(content.length <= MAX_CONTENT_LENGTH) { "content is too long" }
        }
    }
}

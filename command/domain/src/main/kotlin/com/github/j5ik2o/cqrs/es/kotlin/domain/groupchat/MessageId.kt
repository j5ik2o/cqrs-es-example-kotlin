package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator

data class MessageId(
    @param:JsonProperty("value")
    @get:JsonProperty("value")
    val value: Ulid,
) {
    fun asString(): String = value.toString()

    fun toUlid(): Ulid = value

    companion object {
        fun of(value: Ulid): MessageId = MessageId(value)

        fun ofString(value: String): MessageId {
            require(value.isNotEmpty()) { "messageId is empty" }
            return MessageId(Ulid.from(value))
        }

        fun generate(): MessageId = MessageId(UlidCreator.getMonotonicUlid())
    }
}

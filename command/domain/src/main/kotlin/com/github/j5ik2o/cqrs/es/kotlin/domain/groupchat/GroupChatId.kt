package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator

data class GroupChatId(
    @param:JsonProperty("value")
    @get:JsonProperty("value")
    val value: Ulid,
) {
    @get:JsonIgnore
    val typeName: String
        get() = TYPE_NAME

    fun asString(): String = "$TYPE_NAME-$value"

    fun toUlid(): Ulid = value

    companion object {
        const val TYPE_NAME = "GroupChat"

        fun of(value: Ulid): GroupChatId = GroupChatId(value)

        fun ofString(value: String): GroupChatId {
            require(value.isNotEmpty()) { "groupChatId is empty" }
            val normalized = value.removePrefix("$TYPE_NAME-")
            return GroupChatId(Ulid.from(normalized))
        }

        fun generate(): GroupChatId = GroupChatId(UlidCreator.getMonotonicUlid())
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonProperty

data class GroupChatName(
    @param:JsonProperty("value")
    @get:JsonProperty("value")
    val value: String,
) {
    fun asString(): String = value

    companion object {
        const val MAX_LENGTH = 255

        fun of(value: String): GroupChatName {
            require(value.isNotEmpty()) { "name is empty" }
            require(value.length <= MAX_LENGTH) { "name is too long" }
            return GroupChatName(value)
        }
    }
}

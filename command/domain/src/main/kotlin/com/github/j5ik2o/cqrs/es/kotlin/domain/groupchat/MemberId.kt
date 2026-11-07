package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator

data class MemberId(
    @param:JsonProperty("value")
    @get:JsonProperty("value")
    val value: Ulid,
) {
    fun asString(): String = value.toString()

    fun toUlid(): Ulid = value

    companion object {
        fun of(value: Ulid): MemberId = MemberId(value)

        fun ofString(value: String): MemberId {
            require(value.isNotEmpty()) { "memberId is empty" }
            return MemberId(Ulid.from(value))
        }

        fun generate(): MemberId = MemberId(UlidCreator.getMonotonicUlid())
    }
}

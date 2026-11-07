package com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator

data class UserAccountId(
    @param:JsonProperty("value")
    @get:JsonProperty("value")
    val value: Ulid,
) {
    @get:JsonIgnore
    val typeName: String
        get() = TYPE_NAME

    fun asString(): String = "$TYPE_NAME-$value"

    companion object {
        const val TYPE_NAME = "UserAccount"

        fun of(value: Ulid): UserAccountId = UserAccountId(value)

        fun ofString(value: String): UserAccountId {
            require(value.isNotEmpty()) { "userAccountId is empty" }
            val normalized = value.removePrefix("$TYPE_NAME-")
            return UserAccountId(Ulid.from(normalized))
        }

        fun generate(): UserAccountId = UserAccountId(UlidCreator.getMonotonicUlid())
    }
}

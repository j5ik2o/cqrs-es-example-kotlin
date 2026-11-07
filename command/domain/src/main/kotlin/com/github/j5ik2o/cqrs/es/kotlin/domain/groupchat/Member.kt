package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId

data class Member(
    @param:JsonProperty("id")
    @get:JsonProperty("id")
    val id: MemberId,
    @param:JsonProperty("userAccountId")
    @get:JsonProperty("userAccountId")
    val userAccountId: UserAccountId,
    @param:JsonProperty("role")
    @get:JsonProperty("role")
    val role: MemberRole,
) {
    companion object {
        fun of(
            id: MemberId,
            userAccountId: UserAccountId,
            role: MemberRole,
        ): Member = Member(id, userAccountId, role)
    }
}

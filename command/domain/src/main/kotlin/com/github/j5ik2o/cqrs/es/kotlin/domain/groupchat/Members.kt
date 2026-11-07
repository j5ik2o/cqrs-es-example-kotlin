package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId

data class Members(
    @param:JsonProperty("values")
    @get:JsonProperty("values")
    val values: List<Member>,
) {
    fun add(member: Member): Members = Members(values + member)

    fun removeByUserAccountId(userAccountId: UserAccountId): Pair<Member, Members>? {
        val member = values.firstOrNull { it.userAccountId == userAccountId } ?: return null
        return member to Members(values.filterNot { it == member })
    }

    fun removeByUserAccountIdOrSelf(userAccountId: UserAccountId): Members = removeByUserAccountId(userAccountId)?.second ?: this

    fun findByUserAccountId(userAccountId: UserAccountId): Member? = values.firstOrNull { it.userAccountId == userAccountId }

    fun containsByUserAccountId(userAccountId: UserAccountId): Boolean = values.any { it.userAccountId == userAccountId }

    fun isMember(userAccountId: UserAccountId): Boolean =
        values.any {
            it.userAccountId == userAccountId &&
                (it.role == MemberRole.MEMBER || it.role == MemberRole.ADMINISTRATOR)
        }

    fun isAdministrator(userAccountId: UserAccountId): Boolean =
        values.any { it.userAccountId == userAccountId && it.role == MemberRole.ADMINISTRATOR }

    fun toList(): List<Member> = values

    companion object {
        fun of(
            head: Member,
            vararg tail: Member,
        ): Members = Members(listOf(head) + tail)

        fun from(values: List<Member>): Members = Members(values)

        fun ofAdministratorId(userAccountId: UserAccountId): Members =
            Members(
                listOf(
                    Member(
                        id = MemberId.generate(),
                        userAccountId = userAccountId,
                        role = MemberRole.ADMINISTRATOR,
                    ),
                ),
            )
    }
}

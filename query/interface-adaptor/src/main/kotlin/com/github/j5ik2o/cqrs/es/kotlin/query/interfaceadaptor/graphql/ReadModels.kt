package com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.graphql

import java.time.LocalDateTime

data class GroupChatView(
    val id: String,
    val name: String,
    val ownerId: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

data class MemberView(
    val id: String,
    val groupChatId: String,
    val userAccountId: String,
    val role: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

data class MessageView(
    val id: String,
    val groupChatId: String,
    val userAccountId: String,
    val text: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

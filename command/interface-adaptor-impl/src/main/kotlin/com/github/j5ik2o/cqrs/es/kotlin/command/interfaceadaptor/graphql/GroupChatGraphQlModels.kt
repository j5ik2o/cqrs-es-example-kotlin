package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.graphql

enum class Role {
    MEMBER,
    ADMIN,
}

data class CreateGroupChatInput(
    val name: String,
    val executorId: String,
)

data class DeleteGroupChatInput(
    val groupChatId: String,
    val executorId: String,
)

data class RenameGroupChatInput(
    val groupChatId: String,
    val name: String,
    val executorId: String,
)

data class AddMemberInput(
    val groupChatId: String,
    val userAccountId: String,
    val role: Role,
    val executorId: String,
)

data class RemoveMemberInput(
    val groupChatId: String,
    val userAccountId: String,
    val executorId: String,
)

data class PostMessageInput(
    val groupChatId: String,
    val content: String,
    val executorId: String,
)

data class EditMessageInput(
    val groupChatId: String,
    val messageId: String,
    val content: String,
    val executorId: String,
)

data class DeleteMessageInput(
    val groupChatId: String,
    val messageId: String,
    val executorId: String,
)

data class GroupChatOutput(
    val groupChatId: String,
)

data class MessageOutput(
    val groupChatId: String,
    val messageId: String,
)

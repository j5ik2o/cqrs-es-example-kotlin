package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId

sealed class GroupChatError(
    message: String,
) : RuntimeException(message) {
    class AlreadyDeletedError(
        id: GroupChatId,
    ) : GroupChatError("GroupChat(id = $id) is already deleted.")

    class NotMemberError(
        id: GroupChatId,
        executorId: UserAccountId,
    ) : GroupChatError("UserAccount(id = $executorId) is not member of GroupChat(id = $id).")

    class AlreadyRenamedError(
        id: GroupChatId,
        name: GroupChatName,
    ) : GroupChatError("GroupChat(id = $id) is already renamed to $name.")

    class NotAdministratorError(
        id: GroupChatId,
        executorId: UserAccountId,
    ) : GroupChatError("UserAccount(id = $executorId) is not administrator of GroupChat(id = $id).")

    class AlreadyMemberError(
        id: GroupChatId,
        userAccountId: UserAccountId,
    ) : GroupChatError("UserAccount(id = $userAccountId) is already member of GroupChat(id = $id).")

    class MessageNotFoundError(
        id: GroupChatId,
        messageId: MessageId,
    ) : GroupChatError("Message(id = $messageId) is not found in GroupChat(id = $id).")

    class NotSenderError(
        id: GroupChatId,
        executorId: UserAccountId,
    ) : GroupChatError(
            "UserAccount(id = $executorId) is not the sender of the message in GroupChat(id = $id).",
        )

    class InvalidMessageError(
        id: GroupChatId,
        reason: String,
    ) : GroupChatError("Message in GroupChat(id = $id) is invalid: $reason")
}

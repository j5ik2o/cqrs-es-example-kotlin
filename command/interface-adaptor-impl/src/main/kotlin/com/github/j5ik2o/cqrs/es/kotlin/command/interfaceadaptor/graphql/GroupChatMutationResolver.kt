package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.graphql

import com.github.j5ik2o.cqrs.es.kotlin.command.processor.GroupChatCommandProcessor
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatName
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Member
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MemberId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MemberRole
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Message
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MessageId
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import kotlinx.coroutines.reactor.mono
import org.springframework.context.annotation.Profile
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Mono
import java.time.LocalDateTime

@Controller
@Profile("write")
class GroupChatMutationResolver(
    private val groupChatCommandProcessor: GroupChatCommandProcessor,
) {
    @MutationMapping
    fun createGroupChat(
        @Argument("input") input: CreateGroupChatInput,
    ): Mono<GroupChatOutput> =
        mono {
            val name = GroupChatName.of(input.name)
            val creatorId = UserAccountId.ofString(input.executorId)
            val groupChatId = groupChatCommandProcessor.createGroupChat(name, creatorId)
            GroupChatOutput(groupChatId.asString())
        }

    @MutationMapping
    fun deleteGroupChat(
        @Argument("input") input: DeleteGroupChatInput,
    ): Mono<GroupChatOutput> =
        mono {
            val groupChatId = GroupChatId.ofString(input.groupChatId)
            val executorId = UserAccountId.ofString(input.executorId)
            val result = groupChatCommandProcessor.deleteGroupChat(groupChatId, executorId)
            GroupChatOutput(result.asString())
        }

    @MutationMapping
    fun renameGroupChat(
        @Argument("input") input: RenameGroupChatInput,
    ): Mono<GroupChatOutput> =
        mono {
            val groupChatId = GroupChatId.ofString(input.groupChatId)
            val name = GroupChatName.of(input.name)
            val executorId = UserAccountId.ofString(input.executorId)
            val result =
                groupChatCommandProcessor.renameGroupChat(groupChatId, name, executorId)
            GroupChatOutput(result.asString())
        }

    @MutationMapping
    fun addMember(
        @Argument("input") input: AddMemberInput,
    ): Mono<GroupChatOutput> =
        mono {
            val groupChatId = GroupChatId.ofString(input.groupChatId)
            val userAccountId = UserAccountId.ofString(input.userAccountId)
            val executorId = UserAccountId.ofString(input.executorId)
            val member =
                Member.of(MemberId.generate(), userAccountId, input.role.toMemberRole())
            val result = groupChatCommandProcessor.addMember(groupChatId, member, executorId)
            GroupChatOutput(result.asString())
        }

    @MutationMapping
    fun removeMember(
        @Argument("input") input: RemoveMemberInput,
    ): Mono<GroupChatOutput> =
        mono {
            val groupChatId = GroupChatId.ofString(input.groupChatId)
            val userAccountId = UserAccountId.ofString(input.userAccountId)
            val executorId = UserAccountId.ofString(input.executorId)
            val result =
                groupChatCommandProcessor.removeMember(groupChatId, userAccountId, executorId)
            GroupChatOutput(result.asString())
        }

    @MutationMapping
    fun postMessage(
        @Argument("input") input: PostMessageInput,
    ): Mono<MessageOutput> =
        mono {
            val groupChatId = GroupChatId.ofString(input.groupChatId)
            val executorId = UserAccountId.ofString(input.executorId)
            val message =
                Message.of(
                    MessageId.generate(),
                    input.content,
                    executorId,
                    LocalDateTime.now(),
                )
            val (resultGroupChatId, messageId) =
                groupChatCommandProcessor.postMessage(groupChatId, message, executorId)
            MessageOutput(resultGroupChatId.asString(), messageId.asString())
        }

    @MutationMapping
    fun editMessage(
        @Argument("input") input: EditMessageInput,
    ): Mono<GroupChatOutput> =
        mono {
            val groupChatId = GroupChatId.ofString(input.groupChatId)
            val messageId = MessageId.ofString(input.messageId)
            val executorId = UserAccountId.ofString(input.executorId)
            Message.requireValidContent(input.content)
            val result =
                groupChatCommandProcessor.editMessage(
                    groupChatId,
                    messageId,
                    input.content,
                    executorId,
                )
            GroupChatOutput(result.asString())
        }

    @MutationMapping
    fun deleteMessage(
        @Argument("input") input: DeleteMessageInput,
    ): Mono<GroupChatOutput> =
        mono {
            val groupChatId = GroupChatId.ofString(input.groupChatId)
            val messageId = MessageId.ofString(input.messageId)
            val executorId = UserAccountId.ofString(input.executorId)
            val result =
                groupChatCommandProcessor.deleteMessage(groupChatId, messageId, executorId)
            GroupChatOutput(result.asString())
        }

    private fun Role.toMemberRole(): MemberRole =
        when (this) {
            Role.MEMBER -> MemberRole.MEMBER
            Role.ADMIN -> MemberRole.ADMINISTRATOR
        }
}

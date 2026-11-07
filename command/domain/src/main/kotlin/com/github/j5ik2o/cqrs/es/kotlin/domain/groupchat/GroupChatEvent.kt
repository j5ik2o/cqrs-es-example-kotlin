package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.github.f4b6a3.ulid.Ulid
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId

/**
 * Payload of a domain event.
 *
 * Envelope metadata such as the aggregate id, sequence number and occurrence time lives in the
 * ESA envelope and is deliberately absent from this type.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = GroupChatEvent.Created::class, name = GroupChatEvent.CREATED_TYPE),
    JsonSubTypes.Type(value = GroupChatEvent.Deleted::class, name = GroupChatEvent.DELETED_TYPE),
    JsonSubTypes.Type(value = GroupChatEvent.Renamed::class, name = GroupChatEvent.RENAMED_TYPE),
    JsonSubTypes.Type(
        value = GroupChatEvent.MemberAdded::class,
        name = GroupChatEvent.MEMBER_ADDED_TYPE,
    ),
    JsonSubTypes.Type(
        value = GroupChatEvent.MemberRemoved::class,
        name = GroupChatEvent.MEMBER_REMOVED_TYPE,
    ),
    JsonSubTypes.Type(
        value = GroupChatEvent.MessagePosted::class,
        name = GroupChatEvent.MESSAGE_POSTED_TYPE,
    ),
    JsonSubTypes.Type(
        value = GroupChatEvent.MessageEdited::class,
        name = GroupChatEvent.MESSAGE_EDITED_TYPE,
    ),
    JsonSubTypes.Type(
        value = GroupChatEvent.MessageDeleted::class,
        name = GroupChatEvent.MESSAGE_DELETED_TYPE,
    ),
)
sealed interface GroupChatEvent {
    val id: Ulid
    val executorId: UserAccountId

    fun typeName(): String

    /** The envelope manifest in the form `<event-type>@<schema-version>`. */
    fun manifest(): String = "${typeName()}@$SCHEMA_VERSION"

    @JsonTypeName(CREATED_TYPE)
    data class Created(
        override val id: Ulid,
        override val executorId: UserAccountId,
        val name: GroupChatName,
        val members: Members,
    ) : GroupChatEvent {
        override fun typeName(): String = CREATED_TYPE
    }

    @JsonTypeName(DELETED_TYPE)
    data class Deleted(
        override val id: Ulid,
        override val executorId: UserAccountId,
    ) : GroupChatEvent {
        override fun typeName(): String = DELETED_TYPE
    }

    @JsonTypeName(RENAMED_TYPE)
    data class Renamed(
        override val id: Ulid,
        override val executorId: UserAccountId,
        val name: GroupChatName,
    ) : GroupChatEvent {
        override fun typeName(): String = RENAMED_TYPE
    }

    @JsonTypeName(MEMBER_ADDED_TYPE)
    data class MemberAdded(
        override val id: Ulid,
        override val executorId: UserAccountId,
        val member: Member,
    ) : GroupChatEvent {
        override fun typeName(): String = MEMBER_ADDED_TYPE
    }

    @JsonTypeName(MEMBER_REMOVED_TYPE)
    data class MemberRemoved(
        override val id: Ulid,
        override val executorId: UserAccountId,
        val member: Member,
    ) : GroupChatEvent {
        override fun typeName(): String = MEMBER_REMOVED_TYPE
    }

    @JsonTypeName(MESSAGE_POSTED_TYPE)
    data class MessagePosted(
        override val id: Ulid,
        override val executorId: UserAccountId,
        val message: Message,
    ) : GroupChatEvent {
        override fun typeName(): String = MESSAGE_POSTED_TYPE
    }

    @JsonTypeName(MESSAGE_EDITED_TYPE)
    data class MessageEdited(
        override val id: Ulid,
        override val executorId: UserAccountId,
        val message: Message,
    ) : GroupChatEvent {
        override fun typeName(): String = MESSAGE_EDITED_TYPE
    }

    @JsonTypeName(MESSAGE_DELETED_TYPE)
    data class MessageDeleted(
        override val id: Ulid,
        override val executorId: UserAccountId,
        val message: Message,
    ) : GroupChatEvent {
        override fun typeName(): String = MESSAGE_DELETED_TYPE
    }

    companion object {
        const val SCHEMA_VERSION = 1

        const val CREATED_TYPE = "GroupChatCreated"
        const val DELETED_TYPE = "GroupChatDeleted"
        const val RENAMED_TYPE = "GroupChatRenamed"
        const val MEMBER_ADDED_TYPE = "GroupChatMemberAdded"
        const val MEMBER_REMOVED_TYPE = "GroupChatMemberRemoved"
        const val MESSAGE_POSTED_TYPE = "GroupChatMessagePosted"
        const val MESSAGE_EDITED_TYPE = "GroupChatMessageEdited"
        const val MESSAGE_DELETED_TYPE = "GroupChatMessageDeleted"
    }
}

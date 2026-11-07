package com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.dao

import com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.graphql.GroupChatView
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
@Profile("read")
class GroupChatReadDao(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findById(
        groupChatId: String,
        userAccountId: String,
    ): GroupChatView? =
        jdbcTemplate
            .query(SELECT_ONE, ROW_MAPPER, groupChatId, userAccountId)
            .firstOrNull()

    fun findAll(userAccountId: String): List<GroupChatView> = jdbcTemplate.query(SELECT_ALL, ROW_MAPPER, userAccountId)

    companion object {
        private val ROW_MAPPER =
            RowMapper<GroupChatView> { rs, _ ->
                GroupChatView(
                    id = rs.getString("id"),
                    name = rs.getString("name"),
                    ownerId = rs.getString("owner_id"),
                    createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
                    updatedAt = rs.getTimestamp("updated_at").toLocalDateTime(),
                )
            }

        private const val SELECT_ONE = """
            SELECT gc.id, gc.name, gc.owner_id, gc.created_at, gc.updated_at
            FROM group_chats AS gc JOIN members AS m ON gc.id = m.group_chat_id
            WHERE gc.disabled = 0 AND m.group_chat_id = ? AND m.user_account_id = ?
        """

        private const val SELECT_ALL = """
            SELECT gc.id, gc.name, gc.owner_id, gc.created_at, gc.updated_at
            FROM group_chats AS gc JOIN members AS m ON gc.id = m.group_chat_id
            WHERE gc.disabled = 0 AND m.user_account_id = ?
            ORDER BY gc.created_at, gc.id
        """
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.dao

import com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.graphql.MemberView
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
@Profile("read")
class MemberReadDao(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findById(
        groupChatId: String,
        userAccountId: String,
    ): MemberView? =
        jdbcTemplate
            .query(SELECT_ONE, ROW_MAPPER, groupChatId, userAccountId)
            .firstOrNull()

    fun findAll(
        groupChatId: String,
        userAccountId: String,
    ): List<MemberView> = jdbcTemplate.query(SELECT_ALL, ROW_MAPPER, groupChatId, userAccountId)

    companion object {
        private val ROW_MAPPER =
            RowMapper<MemberView> { rs, _ ->
                MemberView(
                    id = rs.getString("id"),
                    groupChatId = rs.getString("group_chat_id"),
                    userAccountId = rs.getString("user_account_id"),
                    role = rs.getString("role"),
                    createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
                    updatedAt = rs.getTimestamp("updated_at").toLocalDateTime(),
                )
            }

        private const val SELECT_ONE = """
            SELECT m.id, m.group_chat_id, m.user_account_id, m.role, m.created_at, m.updated_at
            FROM group_chats AS gc JOIN members AS m ON gc.id = m.group_chat_id
            WHERE gc.disabled = 0 AND m.group_chat_id = ? AND m.user_account_id = ?
        """

        private const val SELECT_ALL = """
            SELECT m.id, m.group_chat_id, m.user_account_id, m.role, m.created_at, m.updated_at
            FROM group_chats AS gc JOIN members AS m ON gc.id = m.group_chat_id
            WHERE gc.disabled = 0 AND m.group_chat_id = ?
            AND EXISTS (
                SELECT 1 FROM members AS m2
                WHERE m2.group_chat_id = m.group_chat_id AND m2.user_account_id = ?
            )
            ORDER BY m.created_at, m.id
        """
    }
}

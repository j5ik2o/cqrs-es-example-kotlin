package com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.dao

import com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.graphql.MessageView
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
@Profile("read")
class MessageReadDao(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findById(
        messageId: String,
        userAccountId: String,
    ): MessageView? =
        jdbcTemplate
            .query(SELECT_ONE, ROW_MAPPER, messageId, userAccountId)
            .firstOrNull()

    fun findAll(
        groupChatId: String,
        userAccountId: String,
    ): List<MessageView> = jdbcTemplate.query(SELECT_ALL, ROW_MAPPER, groupChatId, userAccountId)

    companion object {
        private val ROW_MAPPER =
            RowMapper<MessageView> { rs, _ ->
                MessageView(
                    id = rs.getString("id"),
                    groupChatId = rs.getString("group_chat_id"),
                    userAccountId = rs.getString("user_account_id"),
                    text = rs.getString("text"),
                    createdAt = rs.getObject("created_at", LocalDateTime::class.java),
                    updatedAt = rs.getObject("updated_at", LocalDateTime::class.java),
                )
            }

        private const val SELECT_ONE = """
            SELECT m.id, m.group_chat_id, m.user_account_id, m.text, m.created_at, m.updated_at
            FROM group_chats AS gc JOIN messages AS m ON gc.id = m.group_chat_id
            WHERE gc.disabled = 0 AND m.disabled = 0 AND m.id = ?
            AND EXISTS (
                SELECT 1 FROM members AS mem
                WHERE mem.group_chat_id = m.group_chat_id AND mem.user_account_id = ?
            )
        """

        private const val SELECT_ALL = """
            SELECT m.id, m.group_chat_id, m.user_account_id, m.text, m.created_at, m.updated_at
            FROM group_chats AS gc JOIN messages AS m ON gc.id = m.group_chat_id
            WHERE gc.disabled = 0 AND m.disabled = 0 AND m.group_chat_id = ?
            AND EXISTS (
                SELECT 1 FROM members AS mem
                WHERE mem.group_chat_id = m.group_chat_id AND mem.user_account_id = ?
            )
            ORDER BY m.created_at, m.id
        """
    }
}

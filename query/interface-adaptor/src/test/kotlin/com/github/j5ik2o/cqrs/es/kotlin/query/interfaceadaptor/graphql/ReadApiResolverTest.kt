package com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.graphql

import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MemberId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.MessageId
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.dao.GroupChatReadDao
import com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.dao.MemberReadDao
import com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.dao.MessageReadDao
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.graphql.data.method.annotation.support.AnnotatedControllerConfigurer
import org.springframework.graphql.execution.DefaultExecutionGraphQlService
import org.springframework.graphql.execution.GraphQlSource
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.graphql.test.tester.GraphQlTester
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.assertTrue

@Testcontainers
class ReadApiResolverTest {
    private lateinit var tester: GraphQlTester
    private lateinit var groupChatId: String
    private lateinit var adminId: String
    private lateinit var memberId: String
    private lateinit var otherId: String
    private lateinit var messageId: String

    @BeforeEach
    fun setUp() {
        groupChatId = GroupChatId.generate().asString()
        adminId = UserAccountId.generate().asString()
        memberId = UserAccountId.generate().asString()
        otherId = UserAccountId.generate().asString()
        messageId = MessageId.generate().asString()
        val createdAt = LocalDateTime.of(2026, 1, 1, 12, 0, 5)
        val timestamp = Timestamp.valueOf(createdAt)

        jdbcTemplate.update(
            "INSERT INTO group_chats (id, disabled, name, owner_id, created_at, updated_at) VALUES (?, 0, ?, ?, ?, ?)",
            groupChatId,
            "test",
            adminId,
            timestamp,
            timestamp,
        )
        jdbcTemplate.update(
            "INSERT INTO members (id, group_chat_id, user_account_id, role, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            MemberId.generate().asString(),
            groupChatId,
            adminId,
            "ADMINISTRATOR",
            timestamp,
            timestamp,
        )
        jdbcTemplate.update(
            "INSERT INTO members (id, group_chat_id, user_account_id, role, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            MemberId.generate().asString(),
            groupChatId,
            memberId,
            "MEMBER",
            timestamp,
            timestamp,
        )
        jdbcTemplate.update(
            "INSERT INTO messages (id, disabled, group_chat_id, user_account_id, text, created_at, updated_at) VALUES (?, 0, ?, ?, ?, ?, ?)",
            messageId,
            groupChatId,
            memberId,
            "hello",
            timestamp,
            timestamp,
        )

        val resolver =
            ReadApiQueryResolver(
                GroupChatReadDao(jdbcTemplate),
                MemberReadDao(jdbcTemplate),
                MessageReadDao(jdbcTemplate),
            )
        val context = StaticApplicationContext()
        context.beanFactory.registerSingleton("readApiQueryResolver", resolver)
        context.refresh()
        val configurer = AnnotatedControllerConfigurer()
        configurer.setApplicationContext(context)
        configurer.afterPropertiesSet()
        val source =
            GraphQlSource
                .schemaResourceBuilder()
                .schemaResources(ClassPathResource("graphql-read/schema.graphqls"))
                .configureRuntimeWiring(
                    RuntimeWiringConfigurer { wiring ->
                        configurer.configure(wiring)
                        wiring.scalar(NaiveDateTimeScalar.create())
                    },
                ).build()
        tester =
            ExecutionGraphQlServiceTester
                .builder(DefaultExecutionGraphQlService(source))
                .responseTimeout(Duration.ofSeconds(15))
                .build()
    }

    @Test
    fun `reads the group chat and its members for a member`() {
        tester
            .document(
                """
                {
                  getGroupChat(groupChatId: "$groupChatId", userAccountId: "$adminId") {
                    id name ownerId createdAt
                  }
                }
                """.trimIndent(),
            ).execute()
            .path("getGroupChat.id")
            .entity(String::class.java)
            .isEqualTo(groupChatId)
        tester
            .document(
                """
                {
                  getGroupChat(groupChatId: "$groupChatId", userAccountId: "$adminId") {
                    createdAt
                  }
                }
                """.trimIndent(),
            ).execute()
            .path("getGroupChat.createdAt")
            .entity(String::class.java)
            .isEqualTo("2026-01-01T12:00:05")
        tester
            .document(
                """
                {
                  getMembers(groupChatId: "$groupChatId", userAccountId: "$adminId") {
                    userAccountId role
                  }
                }
                """.trimIndent(),
            ).execute()
            .path("getMembers[*].userAccountId")
            .entityList(String::class.java)
            .contains(adminId, memberId)
        tester
            .document("""{ getGroupChats(userAccountId: "$adminId") { id } }""")
            .execute()
            .path("getGroupChats[*].id")
            .entityList(String::class.java)
            .containsExactly(groupChatId)
        tester
            .document(
                """
                {
                  getMember(groupChatId: "$groupChatId", userAccountId: "$memberId") {
                    userAccountId role
                  }
                }
                """.trimIndent(),
            ).execute()
            .path("getMember.role")
            .entity(String::class.java)
            .isEqualTo("MEMBER")
    }

    @Test
    fun `reads messages for a member`() {
        tester
            .document(
                """
                {
                  getMessages(groupChatId: "$groupChatId", userAccountId: "$memberId") { id text }
                }
                """.trimIndent(),
            ).execute()
            .path("getMessages[*].id")
            .entityList(String::class.java)
            .containsExactly(messageId)
        tester
            .document(
                """
                { getMessage(messageId: "$messageId", userAccountId: "$memberId") { text } }
                """.trimIndent(),
            ).execute()
            .path("getMessage.text")
            .entity(String::class.java)
            .isEqualTo("hello")
    }

    @Test
    fun `returns empty lists for a non-member`() {
        tester
            .document("""{ getGroupChats(userAccountId: "$otherId") { id } }""")
            .execute()
            .path("getGroupChats[*].id")
            .entityList(String::class.java)
            .hasSize(0)
        tester
            .document(
                """{ getMembers(groupChatId: "$groupChatId", userAccountId: "$otherId") { id } }""",
            ).execute()
            .path("getMembers[*].id")
            .entityList(String::class.java)
            .hasSize(0)
        tester
            .document(
                """{ getMessages(groupChatId: "$groupChatId", userAccountId: "$otherId") { id } }""",
            ).execute()
            .path("getMessages[*].id")
            .entityList(String::class.java)
            .hasSize(0)
    }

    @Test
    fun `returns a not found error when a single-value query has no visible row`() {
        tester
            .document(
                """
                { getGroupChat(groupChatId: "$groupChatId", userAccountId: "$otherId") { id } }
                """.trimIndent(),
            ).execute()
            .errors()
            .satisfy { errors ->
                assertTrue(errors.any { it.path == "getGroupChat" })
                assertTrue(errors.any { it.message?.contains("not found") == true })
            }
        tester
            .document(
                """{ getMember(groupChatId: "$groupChatId", userAccountId: "$otherId") { id } }""",
            ).execute()
            .errors()
            .satisfy { errors -> assertTrue(errors.any { it.path == "getMember" }) }
        tester
            .document("""{ getMessage(messageId: "$messageId", userAccountId: "$otherId") { id } }""")
            .execute()
            .errors()
            .satisfy { errors -> assertTrue(errors.any { it.path == "getMessage" }) }
    }

    companion object {
        @Container
        @JvmField
        internal val mysql: TestMySQLContainer = TestMySQLContainer()

        private var dataSource: HikariDataSource? = null

        internal val jdbcTemplate: JdbcTemplate by lazy {
            val pool =
                HikariDataSource().apply {
                    jdbcUrl = mysql.jdbcUrl
                    username = mysql.username
                    password = mysql.password
                    maximumPoolSize = 4
                    connectionTimeout = 10_000
                }
            dataSource = pool
            JdbcTemplate(pool)
        }

        @JvmStatic
        @BeforeAll
        fun migrate() {
            Flyway
                .configure()
                .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
                .locations("filesystem:tools/refinery/migrations")
                .load()
                .migrate()
        }

        @JvmStatic
        @AfterAll
        fun closeDataSource() {
            dataSource?.close()
        }
    }
}

internal class TestMySQLContainer : MySQLContainer<TestMySQLContainer>(DockerImageName.parse("mysql:26.10")) {
    init {
        withDatabaseName("ceer")
        withUsername("ceer")
        withPassword("ceer")
        withConfigurationOverride("mysql-conf")
    }
}

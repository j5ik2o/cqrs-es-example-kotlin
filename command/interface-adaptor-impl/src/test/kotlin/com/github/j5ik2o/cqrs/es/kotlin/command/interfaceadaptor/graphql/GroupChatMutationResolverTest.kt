package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.graphql

import com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.GroupChatRepository
import com.github.j5ik2o.cqrs.es.kotlin.command.processor.GroupChatCommandProcessor
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChat
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.PendingEvent
import com.github.j5ik2o.cqrs.es.kotlin.domain.useraccount.UserAccountId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.graphql.data.method.annotation.support.AnnotatedControllerConfigurer
import org.springframework.graphql.execution.DefaultExecutionGraphQlService
import org.springframework.graphql.execution.GraphQlSource
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.graphql.test.tester.GraphQlTester
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupChatMutationResolverTest {
    private lateinit var graphQlTester: GraphQlTester
    private lateinit var adminId: String
    private lateinit var userAccountId: String

    @BeforeEach
    fun setUp() {
        val repository = InMemoryGroupChatRepository()
        val processor = GroupChatCommandProcessor(repository)
        val context = StaticApplicationContext()
        context.beanFactory.registerSingleton(
            "groupChatMutationResolver",
            GroupChatMutationResolver(processor),
        )
        context.beanFactory.registerSingleton(
            "healthCheckQueryResolver",
            HealthCheckQueryResolver(),
        )
        context.refresh()
        val configurer = AnnotatedControllerConfigurer()
        configurer.setApplicationContext(context)
        configurer.afterPropertiesSet()

        val source =
            GraphQlSource
                .schemaResourceBuilder()
                .schemaResources(ClassPathResource("graphql-write/schema.graphqls"))
                .configureRuntimeWiring(configurer)
                .build()
        graphQlTester =
            ExecutionGraphQlServiceTester.create(DefaultExecutionGraphQlService(source))
        adminId = UserAccountId.generate().asString()
        userAccountId = UserAccountId.generate().asString()
    }

    @Test
    fun `health check returns OK`() {
        graphQlTester
            .document("{ healthCheck }")
            .execute()
            .path("healthCheck")
            .entity(String::class.java)
            .isEqualTo("OK")
    }

    @Test
    fun `all mutations work through GraphQL`() {
        val groupChatId = createGroupChat()

        assertEquals(groupChatId, addMember(groupChatId, "ADMIN"))
        val messageId = postMessage(groupChatId)
        assertEquals(groupChatId, editMessage(groupChatId, messageId))
        assertEquals(groupChatId, renameGroupChat(groupChatId))
        assertEquals(groupChatId, deleteMessage(groupChatId, messageId))
        assertEquals(groupChatId, removeMember(groupChatId))
        assertEquals(groupChatId, deleteGroupChat(groupChatId))
    }

    @Test
    fun `rejects adding a member by a non administrator`() {
        val groupChatId = createGroupChat()
        addMember(groupChatId, "MEMBER")
        val anotherUserId = UserAccountId.generate().asString()

        graphQlTester
            .document(
                """
                mutation AddMember(${'$'}input: AddMemberInput!) {
                  addMember(input: ${'$'}input) { groupChatId }
                }
                """.trimIndent(),
            ).variable(
                "input",
                mapOf(
                    "groupChatId" to groupChatId,
                    "userAccountId" to anotherUserId,
                    "role" to "MEMBER",
                    "executorId" to userAccountId,
                ),
            ).execute()
            .errors()
            .satisfy { errors ->
                assertTrue(errors.isNotEmpty())
                assertTrue(errors.any { it.path == "addMember" })
            }
    }

    @Test
    fun `rejects an invalid executor id`() {
        graphQlTester
            .document(
                """
                mutation CreateGroupChat(${'$'}input: CreateGroupChatInput!) {
                  createGroupChat(input: ${'$'}input) { groupChatId }
                }
                """.trimIndent(),
            ).variable("input", mapOf("name" to "group-chat-example", "executorId" to "not-a-ulid"))
            .execute()
            .errors()
            .satisfy { errors ->
                assertTrue(errors.isNotEmpty())
                assertTrue(errors.any { it.path == "createGroupChat" })
            }
    }

    private fun createGroupChat(): String =
        graphQlTester
            .document(
                """
                mutation CreateGroupChat(${'$'}input: CreateGroupChatInput!) {
                  createGroupChat(input: ${'$'}input) { groupChatId }
                }
                """.trimIndent(),
            ).variable("input", mapOf("name" to "group-chat-example", "executorId" to adminId))
            .execute()
            .path("createGroupChat.groupChatId")
            .entity(String::class.java)
            .get()

    private fun addMember(
        groupChatId: String,
        role: String,
    ): String =
        graphQlTester
            .document(
                """
                mutation AddMember(${'$'}input: AddMemberInput!) {
                  addMember(input: ${'$'}input) { groupChatId }
                }
                """.trimIndent(),
            ).variable(
                "input",
                mapOf(
                    "groupChatId" to groupChatId,
                    "userAccountId" to userAccountId,
                    "role" to role,
                    "executorId" to adminId,
                ),
            ).execute()
            .path("addMember.groupChatId")
            .entity(String::class.java)
            .get()

    private fun postMessage(groupChatId: String): String =
        graphQlTester
            .document(
                """
                mutation PostMessage(${'$'}input: PostMessageInput!) {
                  postMessage(input: ${'$'}input) { groupChatId messageId }
                }
                """.trimIndent(),
            ).variable(
                "input",
                mapOf(
                    "groupChatId" to groupChatId,
                    "content" to "Text1",
                    "executorId" to userAccountId,
                ),
            ).execute()
            .path("postMessage.messageId")
            .entity(String::class.java)
            .get()

    private fun editMessage(
        groupChatId: String,
        messageId: String,
    ): String =
        graphQlTester
            .document(
                """
                mutation EditMessage(${'$'}input: EditMessageInput!) {
                  editMessage(input: ${'$'}input) { groupChatId }
                }
                """.trimIndent(),
            ).variable(
                "input",
                mapOf(
                    "groupChatId" to groupChatId,
                    "messageId" to messageId,
                    "content" to "Text2",
                    "executorId" to userAccountId,
                ),
            ).execute()
            .path("editMessage.groupChatId")
            .entity(String::class.java)
            .get()

    private fun renameGroupChat(groupChatId: String): String =
        graphQlTester
            .document(
                """
                mutation RenameGroupChat(${'$'}input: RenameGroupChatInput!) {
                  renameGroupChat(input: ${'$'}input) { groupChatId }
                }
                """.trimIndent(),
            ).variable(
                "input",
                mapOf(
                    "groupChatId" to groupChatId,
                    "name" to "group-chat-example-2",
                    "executorId" to adminId,
                ),
            ).execute()
            .path("renameGroupChat.groupChatId")
            .entity(String::class.java)
            .get()

    private fun deleteMessage(
        groupChatId: String,
        messageId: String,
    ): String =
        graphQlTester
            .document(
                """
                mutation DeleteMessage(${'$'}input: DeleteMessageInput!) {
                  deleteMessage(input: ${'$'}input) { groupChatId }
                }
                """.trimIndent(),
            ).variable(
                "input",
                mapOf(
                    "groupChatId" to groupChatId,
                    "messageId" to messageId,
                    "executorId" to userAccountId,
                ),
            ).execute()
            .path("deleteMessage.groupChatId")
            .entity(String::class.java)
            .get()

    private fun removeMember(groupChatId: String): String =
        graphQlTester
            .document(
                """
                mutation RemoveMember(${'$'}input: RemoveMemberInput!) {
                  removeMember(input: ${'$'}input) { groupChatId }
                }
                """.trimIndent(),
            ).variable(
                "input",
                mapOf(
                    "groupChatId" to groupChatId,
                    "userAccountId" to userAccountId,
                    "executorId" to adminId,
                ),
            ).execute()
            .path("removeMember.groupChatId")
            .entity(String::class.java)
            .get()

    private fun deleteGroupChat(groupChatId: String): String =
        graphQlTester
            .document(
                """
                mutation DeleteGroupChat(${'$'}input: DeleteGroupChatInput!) {
                  deleteGroupChat(input: ${'$'}input) { groupChatId }
                }
                """.trimIndent(),
            ).variable("input", mapOf("groupChatId" to groupChatId, "executorId" to adminId))
            .execute()
            .path("deleteGroupChat.groupChatId")
            .entity(String::class.java)
            .get()

    private class InMemoryGroupChatRepository : GroupChatRepository {
        private val store = ConcurrentHashMap<GroupChatId, GroupChat>()

        override suspend fun findById(id: GroupChatId): GroupChat? = store[id]

        override suspend fun store(
            event: PendingEvent,
            aggregate: GroupChat,
        ) {
            store[aggregate.id] = aggregate
        }
    }
}

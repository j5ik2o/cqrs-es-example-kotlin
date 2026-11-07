package com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.graphql

import com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.dao.GroupChatReadDao
import com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.dao.MemberReadDao
import com.github.j5ik2o.cqrs.es.kotlin.query.interfaceadaptor.dao.MessageReadDao
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.springframework.context.annotation.Profile
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Controller
@Profile("read")
class ReadApiQueryResolver(
    private val groupChatReadDao: GroupChatReadDao,
    private val memberReadDao: MemberReadDao,
    private val messageReadDao: MessageReadDao,
) {
    @QueryMapping
    fun getGroupChat(
        @Argument("groupChatId") groupChatId: String,
        @Argument("userAccountId") userAccountId: String,
    ): Mono<GroupChatView> =
        Mono
            .fromCallable {
                groupChatReadDao.findById(groupChatId, userAccountId)
                    ?: throw ReadModelNotFoundException("GroupChat")
            }.subscribeOn(Schedulers.boundedElastic())

    @QueryMapping
    fun getGroupChats(
        @Argument("userAccountId") userAccountId: String,
    ): Flux<GroupChatView> =
        Mono
            .fromCallable { groupChatReadDao.findAll(userAccountId) }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany { Flux.fromIterable(it) }

    @QueryMapping
    fun getMember(
        @Argument("groupChatId") groupChatId: String,
        @Argument("userAccountId") userAccountId: String,
    ): Mono<MemberView> =
        Mono
            .fromCallable {
                memberReadDao.findById(groupChatId, userAccountId)
                    ?: throw ReadModelNotFoundException("Member")
            }.subscribeOn(Schedulers.boundedElastic())

    @QueryMapping
    fun getMembers(
        @Argument("groupChatId") groupChatId: String,
        @Argument("userAccountId") userAccountId: String,
    ): Flux<MemberView> =
        Mono
            .fromCallable { memberReadDao.findAll(groupChatId, userAccountId) }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany { Flux.fromIterable(it) }

    @QueryMapping
    fun getMessage(
        @Argument("messageId") messageId: String,
        @Argument("userAccountId") userAccountId: String,
    ): Mono<MessageView> =
        Mono
            .fromCallable {
                messageReadDao.findById(messageId, userAccountId)
                    ?: throw ReadModelNotFoundException("Message")
            }.subscribeOn(Schedulers.boundedElastic())

    @QueryMapping
    fun getMessages(
        @Argument("groupChatId") groupChatId: String,
        @Argument("userAccountId") userAccountId: String,
    ): Flux<MessageView> =
        Mono
            .fromCallable { messageReadDao.findAll(groupChatId, userAccountId) }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany { Flux.fromIterable(it) }

    @GraphQlExceptionHandler
    fun handleReadModelNotFound(
        exception: ReadModelNotFoundException,
        environment: DataFetchingEnvironment,
    ): GraphQLError =
        GraphqlErrorBuilder
            .newError(environment)
            .message(exception.message ?: "not found")
            .extensions(mapOf("code" to "404"))
            .build()
}

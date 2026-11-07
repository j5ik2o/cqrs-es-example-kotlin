package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization

import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.module.SimpleModule
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Members
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Messages
import com.github.j5ik2o.cqrs.es.kotlin.infrastructure.UlidJsonModule

object JacksonModuleFactory {
    fun create(): Module {
        val module = SimpleModule()
        module.addSerializer(Members::class.java, MembersJsonSerializer())
        module.addDeserializer(Members::class.java, MembersJsonDeserializer())
        module.addSerializer(Messages::class.java, MessagesJsonSerializer())
        module.addDeserializer(Messages::class.java, MessagesJsonDeserializer())
        return module
    }

    fun modules(): List<Module> = listOf(UlidJsonModule, create())
}

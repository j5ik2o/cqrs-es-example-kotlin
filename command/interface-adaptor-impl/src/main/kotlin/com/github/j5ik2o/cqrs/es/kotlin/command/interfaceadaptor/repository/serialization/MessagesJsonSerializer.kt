package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Message
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Messages
import java.io.IOException

class MessagesJsonSerializer : JsonSerializer<Messages>() {
    @Throws(IOException::class)
    override fun serialize(
        value: Messages,
        generator: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        generator.writeObject(value.toList())
    }
}

class MessagesJsonDeserializer : JsonDeserializer<Messages>() {
    @Throws(IOException::class)
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Messages {
        val messages: List<Message> = parser.readValueAs(object : TypeReference<List<Message>>() {})
        return Messages.from(messages)
    }
}

package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Member
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Members
import java.io.IOException

class MembersJsonSerializer : JsonSerializer<Members>() {
    @Throws(IOException::class)
    override fun serialize(
        value: Members,
        generator: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        generator.writeObject(value.toList())
    }
}

class MembersJsonDeserializer : JsonDeserializer<Members>() {
    @Throws(IOException::class)
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Members {
        val members: List<Member> = parser.readValueAs(object : TypeReference<List<Member>>() {})
        return Members.from(members)
    }
}

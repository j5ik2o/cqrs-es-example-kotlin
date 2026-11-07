package com.github.j5ik2o.cqrs.es.kotlin.infrastructure

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.github.f4b6a3.ulid.Ulid
import java.io.IOException

object UlidJsonModule : SimpleModule() {
    init {
        addSerializer(Ulid::class.java, UlidJsonSerializer())
        addDeserializer(Ulid::class.java, UlidJsonDeserializer())
    }

    class UlidJsonSerializer : JsonSerializer<Ulid>() {
        @Throws(IOException::class)
        override fun serialize(
            value: Ulid,
            generator: JsonGenerator,
            serializers: SerializerProvider,
        ) {
            generator.writeString(value.toString())
        }
    }

    class UlidJsonDeserializer : JsonDeserializer<Ulid>() {
        @Throws(IOException::class)
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext,
        ): Ulid = Ulid.from(parser.valueAsString)
    }
}

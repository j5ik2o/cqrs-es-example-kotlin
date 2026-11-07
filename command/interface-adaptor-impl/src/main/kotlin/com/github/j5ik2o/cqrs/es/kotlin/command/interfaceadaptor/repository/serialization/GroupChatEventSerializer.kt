package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatEvent
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException
import java.io.IOException

class GroupChatEventSerializer(
    private val objectMapper: ObjectMapper = ObjectMapperFactory.create(),
) : PayloadSerializer<GroupChatEvent> {
    override fun serialize(value: GroupChatEvent): ByteArray =
        try {
            objectMapper.writeValueAsBytes(value)
        } catch (e: JsonProcessingException) {
            throw SerializationException("failed to serialize GroupChatEvent", e)
        }

    override fun deserialize(bytes: ByteArray): GroupChatEvent? =
        try {
            objectMapper.readValue(bytes, GroupChatEvent::class.java)
        } catch (e: IOException) {
            throw SerializationException("failed to deserialize GroupChatEvent", e)
        }
}

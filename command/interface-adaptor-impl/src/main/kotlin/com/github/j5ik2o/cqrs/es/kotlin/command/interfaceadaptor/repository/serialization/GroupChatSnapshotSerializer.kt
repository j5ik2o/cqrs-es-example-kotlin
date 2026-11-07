package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository.serialization

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChat
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException
import java.io.IOException

class GroupChatSnapshotSerializer(
    private val objectMapper: ObjectMapper = ObjectMapperFactory.create(),
) : PayloadSerializer<GroupChat> {
    override fun serialize(value: GroupChat): ByteArray =
        try {
            objectMapper.writeValueAsBytes(value)
        } catch (e: JsonProcessingException) {
            throw SerializationException("failed to serialize GroupChat", e)
        }

    override fun deserialize(bytes: ByteArray): GroupChat? =
        try {
            objectMapper.readValue(bytes, GroupChat::class.java)
        } catch (e: IOException) {
            throw SerializationException("failed to deserialize GroupChat", e)
        }
}

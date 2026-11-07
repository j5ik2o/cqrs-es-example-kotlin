package com.github.j5ik2o.cqrs.es.kotlin.rmu

import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.Record
import java.time.Instant

/**
 * Parses a head table stream record.
 *
 * A record is skipped for `REMOVE`, for the `__config__` item, and for aggregate types other than
 * GroupChat. An INSERT or MODIFY record must carry the head item's `events` list.
 */
object HeadRecordParser {
    const val CONFIG_AGGREGATE_ID = "__config__"
    private const val GROUP_CHAT_ID_PREFIX = "GroupChat-"

    fun parse(record: Record): HeadRecord? {
        val eventName = record.eventNameAsString()
        if (eventName == "REMOVE") return null
        if (eventName != "INSERT" && eventName != "MODIFY") {
            throw IllegalArgumentException("unexpected stream event name: $eventName")
        }
        val image =
            record.dynamodb()?.newImage()
                ?: throw IllegalArgumentException("head record has no new image")
        val aggregateId =
            image["aid"]?.s()
                ?: throw IllegalArgumentException("head record has no aid")
        if (aggregateId == CONFIG_AGGREGATE_ID) return null
        if (!aggregateId.startsWith(GROUP_CHAT_ID_PREFIX)) return null

        val eventsAttribute =
            image["events"]
                ?: throw IllegalArgumentException("head record has no events")
        val events =
            eventsAttribute
                .l()
                .map { parseEvent(it) }
                .sortedBy { it.seqNr }
        if (events.isEmpty()) {
            throw IllegalArgumentException("head record has no events")
        }
        return HeadRecord(aggregateId, events)
    }

    private fun parseEvent(attribute: AttributeValue): HeadEvent {
        val fields =
            attribute.m()
                ?: throw IllegalArgumentException("head event is not a map")
        val seqNr =
            fields["seq_nr"]?.n()?.toLong()
                ?: throw IllegalArgumentException("head event has no seq_nr")
        val occurredAtNanos =
            fields["occurred_at"]?.n()?.toLong()
                ?: throw IllegalArgumentException("head event has no occurred_at")
        val manifest = fields["manifest"]?.s() ?: ""
        val payload =
            fields["payload"]?.let { payloadBytes(it) }
                ?: throw IllegalArgumentException("head event has no payload")
        return HeadEvent(
            seqNr = seqNr,
            occurredAt = Instant.ofEpochSecond(0, occurredAtNanos),
            manifest = manifest,
            payload = payload,
        )
    }

    private fun payloadBytes(attribute: AttributeValue): ByteArray? = attribute.b()?.asByteArray() ?: attribute.s()?.toByteArray()
}

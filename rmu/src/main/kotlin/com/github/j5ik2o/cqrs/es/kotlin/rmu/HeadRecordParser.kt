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

    fun parse(record: Record): HeadRecord? {
        val eventName = record.eventNameAsString()
        if (eventName == "REMOVE") return null
        if (eventName != "INSERT" && eventName != "MODIFY") {
            throw permanent("unexpected stream event name: $eventName")
        }
        val image =
            record.dynamodb()?.newImage()
                ?: throw permanent("head record has no new image")
        val aggregateId =
            image["aid"]?.s()
                ?: throw permanent("head record has no aid")
        if (aggregateId == CONFIG_AGGREGATE_ID) return null
        if (!AggregateType.isGroupChat(aggregateId)) return null

        val eventsAttribute =
            image["events"]
                ?: throw permanent("head record has no events")
        val events =
            eventsAttribute
                .l()
                .map { parseEvent(it) }
                .sortedBy { it.seqNr }
        if (events.isEmpty()) {
            throw permanent("head record has no events")
        }
        return HeadRecord(aggregateId, events)
    }

    /**
     * Reads only the identifiers needed to record a recovery task when the full parse failed.
     * Returns null for the configuration item and other aggregate types.
     */
    fun fallbackIdentity(record: Record): HeadIdentity? {
        val image = record.dynamodb()?.newImage() ?: return null
        val aggregateId = image["aid"]?.s() ?: return null
        if (aggregateId == CONFIG_AGGREGATE_ID || !AggregateType.isGroupChat(aggregateId)) {
            return null
        }
        val seqNr = image["seq_nr"]?.n()?.toLong() ?: return null
        return HeadIdentity(aggregateId, seqNr)
    }

    private fun parseEvent(attribute: AttributeValue): HeadEvent {
        val fields =
            attribute.m()
                ?: throw permanent("head event is not a map")
        val seqNr =
            fields["seq_nr"]?.n()?.toLong()
                ?: throw permanent("head event has no seq_nr")
        val occurredAtNanos =
            fields["occurred_at"]?.n()?.toLong()
                ?: throw permanent("head event has no occurred_at")
        val manifest = fields["manifest"]?.s() ?: ""
        val payload =
            fields["payload"]?.let { payloadBytes(it) }
                ?: throw permanent("head event has no payload")
        return HeadEvent(
            seqNr = seqNr,
            occurredAt = Instant.ofEpochSecond(0, occurredAtNanos),
            manifest = manifest,
            payload = payload,
        )
    }

    private fun payloadBytes(attribute: AttributeValue): ByteArray? = attribute.b()?.asByteArray() ?: attribute.s()?.toByteArray()

    private fun permanent(message: String): PermanentFailure = PermanentFailure(FailureKind.EVENT_PAYLOAD, message)
}

data class HeadIdentity(
    val aggregateId: String,
    val headSeqNr: Long,
)

package com.github.j5ik2o.cqrs.es.kotlin.rmu

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.OperationType
import software.amazon.awssdk.services.dynamodb.model.Record
import software.amazon.awssdk.services.dynamodb.model.StreamRecord
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue as LambdaAttributeValue

/**
 * Adapts the Lambda DynamoDB event model to the AWS SDK v2 record the updater consumes.
 *
 * The stream reader owns the SDK v2 types, while the Lambda runtime deserialises the event into the
 * older `aws-lambda-java-events` model. Converting at the boundary keeps one implementation of the
 * position rule and the recovery path.
 */
internal object LambdaStreamRecords {
    fun toSdkRecord(record: DynamodbEvent.DynamodbStreamRecord): Record {
        val eventName =
            record.eventName ?: throw PermanentFailure(FailureKind.EVENT_PAYLOAD, "stream record has no event name")
        val operation =
            try {
                OperationType.fromValue(eventName)
            } catch (e: IllegalArgumentException) {
                throw PermanentFailure(FailureKind.EVENT_PAYLOAD, "unexpected stream event name: $eventName", e)
            }
        val streamRecord = record.dynamodb
        val newImage = streamRecord?.newImage?.mapValues { (_, value) -> toSdkAttribute(value) } ?: emptyMap()
        val sdkStreamRecord =
            StreamRecord
                .builder()
                .newImage(newImage)
                .apply { streamRecord?.sequenceNumber?.let { sequenceNumber(it) } }
                .build()
        return Record
            .builder()
            .eventID(record.eventID)
            .eventName(operation)
            .dynamodb(sdkStreamRecord)
            .build()
    }

    /**
     * Reads only the aggregate id needed to stop later records of a failed aggregate. It works on
     * the Lambda model so a record that fails conversion can still be attributed.
     */
    fun aggregateId(record: DynamodbEvent.DynamodbStreamRecord): String? =
        record.dynamodb
            ?.newImage
            ?.get("aid")
            ?.s
            ?.takeIf { AggregateType.isGroupChat(it) }

    /** A DynamoDB batch item is identified by its sequence number. */
    fun itemIdentifier(record: DynamodbEvent.DynamodbStreamRecord): String =
        record.dynamodb?.sequenceNumber
            ?: throw PermanentFailure(FailureKind.EVENT_PAYLOAD, "stream record has no sequence number")

    private fun toSdkAttribute(attribute: LambdaAttributeValue): AttributeValue {
        attribute.s?.let { return AttributeValue.fromS(it) }
        attribute.n?.let { return AttributeValue.fromN(it) }
        attribute.b?.let { return AttributeValue.fromB(SdkBytes.fromByteBuffer(it)) }
        attribute.getBOOL()?.let { return AttributeValue.fromBool(it) }
        attribute.getNULL()?.let { return AttributeValue.fromNul(it) }
        attribute.m?.let { fields -> return AttributeValue.fromM(fields.mapValues { (_, value) -> toSdkAttribute(value) }) }
        attribute.l?.let { values -> return AttributeValue.fromL(values.map { toSdkAttribute(it) }) }
        attribute.getSS()?.let { return AttributeValue.fromSs(it) }
        attribute.getNS()?.let { return AttributeValue.fromNs(it) }
        attribute.getBS()?.let { values -> return AttributeValue.fromBs(values.map { SdkBytes.fromByteBuffer(it) }) }
        throw PermanentFailure(FailureKind.EVENT_PAYLOAD, "unsupported DynamoDB attribute value")
    }
}

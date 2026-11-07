package com.github.j5ik2o.cqrs.es.kotlin.rmu

import software.amazon.awssdk.services.dynamodb.model.Record

/** Applies one head stream record, skipping `__config__` and other aggregate types. */
class ReadModelUpdater(
    private val applier: ReadModelApplier,
) {
    fun update(record: Record) {
        val head = HeadRecordParser.parse(record) ?: return
        for (event in head.events) {
            applier.applyHeadEvent(head.aggregateId, event)
        }
    }
}

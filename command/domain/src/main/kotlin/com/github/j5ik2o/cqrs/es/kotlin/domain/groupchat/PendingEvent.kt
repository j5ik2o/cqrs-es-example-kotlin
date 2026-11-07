package com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat

import java.time.Instant

/** An event together with the envelope metadata needed to persist it. */
data class PendingEvent(
    val aggregateId: GroupChatId,
    val sequenceNumber: Long,
    val occurredAt: Instant,
    val event: GroupChatEvent,
)

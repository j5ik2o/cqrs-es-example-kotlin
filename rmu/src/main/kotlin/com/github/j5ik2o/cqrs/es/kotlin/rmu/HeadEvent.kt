package com.github.j5ik2o.cqrs.es.kotlin.rmu

import java.time.Instant

/** One event envelope carried by a head item's `events` list or by a journal item. */
data class HeadEvent(
    val seqNr: Long,
    val occurredAt: Instant,
    val manifest: String,
    val payload: ByteArray,
)

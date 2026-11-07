package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** One head item after the configuration item and other aggregate types are removed. */
data class HeadRecord(
    val aggregateId: String,
    val events: List<HeadEvent>,
)

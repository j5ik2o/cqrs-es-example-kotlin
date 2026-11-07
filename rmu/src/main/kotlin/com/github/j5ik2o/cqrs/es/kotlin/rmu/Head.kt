package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** One entry of the head table after the configuration item is removed. */
data class Head(
    val aggregateId: String,
    val typeName: String,
    val seqNr: Long,
)

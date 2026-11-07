package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** Signals that a shard fell behind the stream retention window, so a full resync is required. */
class StreamTrimmedException(
    val shardId: String,
    cause: Throwable? = null,
) : RuntimeException("head stream records for shard $shardId were trimmed", cause)

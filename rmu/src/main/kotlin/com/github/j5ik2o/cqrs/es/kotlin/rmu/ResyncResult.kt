package com.github.j5ik2o.cqrs.es.kotlin.rmu

/** Counts what one full reconciliation saw and did. */
data class ResyncResult(
    val heads: Int,
    val skipped: Int,
    val advanced: Int,
    val failed: Int,
)

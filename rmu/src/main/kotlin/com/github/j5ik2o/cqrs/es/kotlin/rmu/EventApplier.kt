package com.github.j5ik2o.cqrs.es.kotlin.rmu

/**
 * Applies events to the read model under the single position rule: only the event at
 * `position + 1` is applied, and the read model change and the position advance commit together.
 *
 * The stream path, the resync path, and the recovery path all use this interface.
 */
interface EventApplier {
    /** Returns the last applied seq_nr for the aggregate. */
    fun position(aggregateId: String): Long

    /** Applies one head record event. The journal fills a gap below the record's seq_nr. */
    fun applyHeadEvent(
        aggregateId: String,
        event: HeadEvent,
    )

    /** Replays the journal from the current position up to `targetSeqNr`. */
    fun applyThrough(
        aggregateId: String,
        targetSeqNr: Long,
    )
}

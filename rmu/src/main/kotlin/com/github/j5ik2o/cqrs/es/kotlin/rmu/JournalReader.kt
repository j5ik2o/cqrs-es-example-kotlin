package com.github.j5ik2o.cqrs.es.kotlin.rmu

/**
 * Reads events that a head stream record skipped. The head stream only carries the aggregate's
 * latest transition, so a gap in `seq_nr` is filled from the journal before the head event is
 * applied.
 */
fun interface JournalReader {
    /** Returns the events with `fromSeqNr <= seq_nr <= toSeqNr` in ascending order. */
    fun readEvents(
        aggregateId: String,
        fromSeqNr: Long,
        toSeqNr: Long,
    ): List<HeadEvent>
}

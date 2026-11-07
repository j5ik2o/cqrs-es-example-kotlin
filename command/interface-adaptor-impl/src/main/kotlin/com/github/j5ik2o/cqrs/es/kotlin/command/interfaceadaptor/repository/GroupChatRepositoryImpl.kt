package com.github.j5ik2o.cqrs.es.kotlin.command.interfaceadaptor.repository

import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChat
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatEvent
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.GroupChatId
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.Messages
import com.github.j5ik2o.cqrs.es.kotlin.domain.groupchat.PendingEvent
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import kotlinx.coroutines.CancellationException

class GroupChatRepositoryImpl(
    private val eventStore: EventStoreAsync<GroupChatEvent, GroupChat>,
    private val snapshotInterval: Long = 0L,
) : GroupChatRepository {
    override suspend fun findById(id: GroupChatId): GroupChat? =
        try {
            read(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RepositoryException("occurred an error", e)
        }

    override suspend fun store(
        event: PendingEvent,
        aggregate: GroupChat,
    ) {
        try {
            if (event.event is GroupChatEvent.Created ||
                shouldSnapshot(event.sequenceNumber)
            ) {
                eventStore.persistEventAndSnapshot(
                    toEventEnvelope(event),
                    toSnapshotEnvelope(event, aggregate),
                )
            } else {
                eventStore.persistEvent(toEventEnvelope(event))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RepositoryException("occurred an error", e)
        }
    }

    private suspend fun read(id: GroupChatId): GroupChat? {
        val aggregateId = toAggregateId(id)
        val result = eventStore.getLatestSnapshotById(aggregateId) ?: return null
        val snapshot = result.snapshot().orElse(null)
        val since = snapshot?.seqNr()?.plus(1) ?: 1L
        val envelopes = eventStore.getEventsByIdSinceSeqNr(aggregateId, since)
        return if (snapshot != null) {
            GroupChat.replay(envelopes.map { it.payload() }, snapshot.aggregate())
        } else {
            replayFromScratch(envelopes)
        }
    }

    private fun replayFromScratch(envelopes: List<EventEnvelope<GroupChatEvent>>): GroupChat? {
        if (envelopes.isEmpty()) return null
        val first = envelopes.first()
        val created =
            first.payload() as? GroupChatEvent.Created
                ?: throw IllegalStateException(
                    "the first event of an aggregate without a snapshot must be Created",
                )
        val initial =
            GroupChat.from(
                id = GroupChatId.ofString(first.aggregateId().value()),
                deleted = false,
                name = created.name,
                members = created.members,
                messages = Messages.empty(),
                sequenceNumber = first.seqNr(),
            )
        return GroupChat.replay(envelopes.drop(1).map { it.payload() }, initial)
    }

    private fun shouldSnapshot(sequenceNumber: Long): Boolean = snapshotInterval > 0 && sequenceNumber % snapshotInterval == 0L

    private fun toEventEnvelope(event: PendingEvent): EventEnvelope<GroupChatEvent> =
        EventEnvelope
            .builder<GroupChatEvent>()
            .aggregateId(toAggregateId(event.aggregateId))
            .seqNr(event.sequenceNumber)
            .occurredAt(event.occurredAt)
            .manifest(event.event.manifest())
            .payload(event.event)
            .build()

    private fun toSnapshotEnvelope(
        event: PendingEvent,
        aggregate: GroupChat,
    ): SnapshotEnvelope<GroupChat> =
        SnapshotEnvelope
            .builder<GroupChat>()
            .aggregate(aggregate)
            .seqNr(event.sequenceNumber)
            .manifest(SNAPSHOT_MANIFEST)
            .build()

    companion object {
        const val SNAPSHOT_MANIFEST = "GroupChat@${GroupChatEvent.SCHEMA_VERSION}"

        fun toAggregateId(id: GroupChatId): AggregateId = AggregateId.of(id.typeName, id.value.toString())
    }
}

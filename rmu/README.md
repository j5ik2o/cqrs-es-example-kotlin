# rmu

This module implements the read model updater. It polls the head table's DynamoDB Streams, skips
`__config__` and other aggregate types, drops duplicate and stale sequence numbers, fills gaps from
the journal, and applies events to MySQL under the `position + 1` rule. The read model change and
the `read_model_positions` advance commit in the same transaction.

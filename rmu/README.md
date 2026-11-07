# rmu

This module implements the read model updater and its recovery path. It polls the head table's
DynamoDB Streams, skips `__config__` and other aggregate types, drops duplicate and stale sequence
numbers, fills gaps from the journal, and applies events to MySQL under the `position + 1` rule. The
read model change and the `read_model_positions` advance commit in the same transaction.

Transient failures are retried with exponential backoff and jitter. Permanent failures, and retries
that exhaust their budget, are recorded as recovery tasks in `read_model_recovery_tasks`. Recovery
re-reads the position and replays the journal in order, so it is idempotent. The RMU reconciles every
head on startup and after a trimmed stream (DY-15), and it records the store, projection, aggregate,
sequence number, and failure kind with each task.

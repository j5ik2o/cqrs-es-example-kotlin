# rmu

This module implements the read model updater and its recovery path. It polls the head table's
DynamoDB Streams, skips `__config__` and other aggregate types, drops duplicate and stale sequence
numbers, fills gaps from the journal, and applies events to MySQL under the `position + 1` rule. The
read model change and the `read_model_positions` advance commit in the same transaction.

Transient failures are retried with exponential backoff and jitter. Permanent failures, and retries
that exhaust their budget, are recorded as recovery tasks in `read_model_recovery_tasks`. Recovery
re-reads the position and replays the journal in order, so it is idempotent. The RMU resumes the head
stream subscription before it reconciles every head on startup, waits for the first successful poll,
then scans while the subscription keeps applying records (DY-15). A trimmed stream or an exceeded
retention window resets the iterators and waits for a fresh poll before scanning, so polling never
stops for the duration of a scan. Each task records the store, projection, aggregate, sequence
number, and failure kind.

The module also contains the Lambda entry points. `RmuLambdaHandler` applies head stream records and
returns partial batch failures, `RecoveryWorker` drains pending tasks from SQS, and `ResyncWorker`
runs the full reconciliation. `SqsRecoveryNotifier` sends identifier-only wake-up messages; the
event body is always re-read from the journal. The CloudFormation template, apply script, and runbook
are in `deploy/README.ja.md`.

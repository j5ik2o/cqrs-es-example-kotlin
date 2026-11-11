# cqrs-es-example-kotlin

A Kotlin implementation of the GroupChat CQRS and event-sourcing example, built on
[event-store-adapter-kotlin](https://github.com/j5ik2o/event-store-adapter-kotlin) 5.1.0.
The module layout and the Write API follow the Java v5 implementation in
[cqrs-es-example-java](https://github.com/j5ik2o/cqrs-es-example-java).

This repository implements the command side, the Write API, the Read API, the read model
updater (RMU), and the end-to-end compose test. The RMU recovery path and the AWS deployment
assets for Lambda and SQS are included as well.

## Architecture

```text
Client
  |-- mutation --> Write API --> ESA --> DynamoDB
  |                                      journal
  |                                      snapshot
  |                                      head --- Streams --> RMU --> MySQL (read model)
  |
  `-- query ----> Read API --------------------------------> MySQL (read model)
```

The Write API exposes the eight GroupChat mutations and `healthCheck`. It restores aggregates
from the event store and never reads the read model.

## Modules

| Module | Responsibility | Java counterpart |
|:--|:--|:--|
| `infrastructure` | Shared Jackson support for ULID values | `infrastructure` |
| `command:domain` | GroupChat aggregate, events, value objects | `command:domain` |
| `command:interface-adaptor-if` | Repository port | `command:interface-adaptor-if` |
| `command:interface-adaptor-impl` | ESA repository adapter, payload serializers, GraphQL resolvers | `command:interface-adaptor-impl` |
| `command:processor` | Command processor (use cases) | `command:processor` |
| `query:interface-adaptor` | Read API DAOs, GraphQL resolvers, and the `NaiveDateTime` scalar | `query:interface-adaptor` |
| `rmu` | Head Streams poller, journal gap reader, and read model applier | `rmu` |
| `bootstrap` | Spring Boot entry point and configuration | `bootstrap` |

The Read API uses Spring `JdbcTemplate` rather than the Java implementation's MyBatis. The RMU
reads the head Streams directly with the AWS SDK. It includes the Lambda handlers, the recovery
tasks, and the resync path. The AWS deployment asset and its runbook are in
[deploy/README.ja.md](deploy/README.ja.md).

## Requirements

- JDK 17 or later. The build targets JVM 17.
- Docker with the Compose plugin (for the Testcontainers integration tests, the local stack, and the e2e).

## Build and test

```sh
./gradlew build
./gradlew spotlessCheck
```

`build` runs the unit tests, the GraphQL resolver tests, the DynamoDB integration test, the RMU
tests, and the Read API tests. The integration tests use Testcontainers with
`localstack/localstack:4.14.0` and `mysql:26.10`, so Docker must be running.

## GraphQL Write API

The Write API listens on `http://localhost:56080/graphql` by default. GraphiQL is available at
`http://localhost:56080/graphiql`.

Mutations:

| Mutation | Input | Result |
|:--|:--|:--|
| `createGroupChat` | `name`, `executorId` | `groupChatId` |
| `deleteGroupChat` | `groupChatId`, `executorId` | `groupChatId` |
| `renameGroupChat` | `groupChatId`, `name`, `executorId` | `groupChatId` |
| `addMember` | `groupChatId`, `userAccountId`, `role`, `executorId` | `groupChatId` |
| `removeMember` | `groupChatId`, `userAccountId`, `executorId` | `groupChatId` |
| `postMessage` | `groupChatId`, `content`, `executorId` | `groupChatId`, `messageId` |
| `editMessage` | `groupChatId`, `messageId`, `content`, `executorId` | `groupChatId` |
| `deleteMessage` | `groupChatId`, `messageId`, `executorId` | `groupChatId` |

The `role` enum has the values `MEMBER` and `ADMIN`.

`userAccountId` and `executorId` accept a raw ULID or a value prefixed with `UserAccount-`.
`groupChatId` accepts a raw ULID or a value prefixed with `GroupChat-`. The output
`groupChatId` is always prefixed. The output `messageId` is a raw ULID. This matches the Java
implementation.

The Query type exposes `healthCheck: String!`, which returns `OK`.

The Write API does not authenticate callers. Every mutation trusts the `executorId` supplied by
the client, so anyone who can reach the API can act as an administrator. Do not expose it without
an authentication layer. A production deployment should take the executor ID from the
authenticated context. This matches the Java v5 sample.

The command processor does not retry an optimistic-lock conflict. Two concurrent commands on the
same group chat can conflict; the later one returns a GraphQL error. This also matches the Java v5
implementation.

## GraphQL Read API

The Read API listens on `http://localhost:56082/graphql` by default. It reads only the MySQL read
model.

| Query | Arguments | Returns |
|:--|:--|:--|
| `getGroupChat` | `groupChatId`, `userAccountId` | `GroupChat!` |
| `getGroupChats` | `userAccountId` | `[GroupChat!]!` |
| `getMember` | `groupChatId`, `userAccountId` | `Member!` |
| `getMembers` | `groupChatId`, `userAccountId` | `[Member!]!` |
| `getMessage` | `messageId`, `userAccountId` | `Message!` |
| `getMessages` | `groupChatId`, `userAccountId` | `[Message!]!` |

The types are `GroupChat`, `Member`, and `Message`. `createdAt` and `updatedAt` use the
`NaiveDateTime` scalar and are serialized as ISO 8601 local date-times.

Every query uses `userAccountId` as the viewer and as the visibility filter. A non-member gets an
empty list, or a GraphQL error with code `404` for a single-value query.

The Read API does not authenticate callers. It trusts the `userAccountId` supplied by the client,
so anyone who can reach the API can read another member's group chats and messages. Do not expose
it without an authentication layer. A production deployment should take the viewer ID from the
authenticated context.

## Read model updater

The RMU follows the head table's DynamoDB Streams. For each `INSERT` and `MODIFY` record it skips
`__config__` and other aggregate types, parses the head item's `events` list, and applies each
event.

- Old and duplicate sequence numbers are dropped.
- A gap in `seq_nr` is filled from the journal before the head event is applied.
- Only `position + 1` is applied.
- The read model change and the `read_model_positions` advance commit in the same MySQL
  transaction.
- `SELECT ... FOR UPDATE` locks the position row, so concurrent readers cannot skip or apply an
  event twice.
- A failed event leaves the position unchanged. The poller resumes after the last successfully
  applied record.

The local RMU starts at `TRIM_HORIZON`, so a restart replays retained records and the positions
drop what was already applied. Transient failures are retried with exponential backoff and jitter.
A permanent failure, or a retry that exhausts its attempt or elapsed-time budget, is recorded as a
recovery task in `read_model_recovery_tasks`. The task stores the store id, projection name and
version, aggregate id, `seq_nr`, failure kind, and error message. Recovery re-reads the position and
replays the journal in ascending order, so it is idempotent.

The RMU resumes the head stream subscription before it reconciles every head on startup, waits for
the first successful poll, and then scans while the subscription keeps reading. Updates written
during a long scan therefore stay within the stream retention window. The scan uses a consistent
read, skips `__config__` and other aggregate types, and fills any lag between the head `seq_nr` and
the projection position from the journal. A trimmed stream or an exceeded retention window resets
the iterators and waits for a fresh poll before the scan, so polling never stops for the duration of
a scan. The subscription and the scan share the `position + 1` guard and transaction, so an event
seen by both is applied once. An expired iterator resumes after the last processed sequence number.
Pending recovery tasks are replayed at startup and periodically afterwards.

## Local run

Start LocalStack, MySQL, and apply the read model migrations:

```sh
docker compose -f tools/docker-compose/docker-compose-databases.yml up -d --wait localstack mysql
docker compose -f tools/docker-compose/docker-compose-databases.yml run --rm dynamodb-setup
docker compose -f tools/docker-compose/docker-compose-databases.yml run --rm migration
```

Build the executable jar:

```sh
./gradlew :bootstrap:bootJar
```

Start the three applications in separate terminals:

```sh
java -jar bootstrap/build/libs/bootstrap-0.0.1-SNAPSHOT.jar -w
java -jar bootstrap/build/libs/bootstrap-0.0.1-SNAPSHOT.jar -l
java -jar bootstrap/build/libs/bootstrap-0.0.1-SNAPSHOT.jar -r
```

`-w` is the Write API on port 56080, `-l` is the local RMU (no HTTP server), and `-r` is the Read
API on port 56082. The same jar can be started with `./gradlew :bootstrap:bootRun --args='-w'`
and so on.

Create a group chat:

```sh
curl -s http://localhost:56080/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation($input: CreateGroupChatInput!){createGroupChat(input:$input){groupChatId}}","variables":{"input":{"name":"group-chat-example","executorId":"UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV"}}}'
```

Use the returned `groupChatId` with `postMessage`:

```sh
curl -s http://localhost:56080/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"mutation($input: PostMessageInput!){postMessage(input:$input){groupChatId messageId}}","variables":{"input":{"groupChatId":"GroupChat-...","content":"Hello","executorId":"UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV"}}}'
```

Poll the Read API until the RMU applies the events:

```sh
curl -s http://localhost:56082/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ getGroupChats(userAccountId: \"UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV\") { id name } }"}'
```

Stop the stack:

```sh
docker compose -f tools/docker-compose/docker-compose-databases.yml down
```

## End-to-end test

The e2e script builds the application image and the e2e image, starts LocalStack, MySQL, the
migration, the three applications, and runs the verification. It polls the Read API until the RMU
reflects each event, so it does not rely on a fixed sleep.

```sh
./tools/scripts/docker-compose-e2e-test.sh
```

The script runs the verification twice: once normally and once after restarting the RMU. The
second run confirms that the `read_model_positions` rows drop the replayed head records and that no
duplicate rows reach MySQL. On failure it prints the compose logs and exits with the failure.

The verification covers `createGroupChat`, `addMember`, `postMessage`, `renameGroupChat`,
`editMessage`, `deleteMessage`, `removeMember`, and `deleteGroupChat`, and the six read queries.

## Configuration

| Variable | Default | Purpose |
|:--|:--|:--|
| `SERVER_PORT` | `56080` (write), `56082` (read) | API port |
| `AWS_REGION` | `ap-northeast-1` | DynamoDB region |
| `DYNAMODB_ENDPOINT_URL` | `http://localhost:4566` | DynamoDB endpoint |
| `AWS_ACCESS_KEY_ID` | `x` | Access key for LocalStack |
| `AWS_SECRET_ACCESS_KEY` | `x` | Secret key for LocalStack |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/ceer?...` | MySQL read model |
| `SPRING_DATASOURCE_USERNAME` | `ceer` | MySQL user |
| `SPRING_DATASOURCE_PASSWORD` | `ceer` | MySQL password |
| `JOURNAL_TABLE_NAME` | `journal` | Journal table |
| `SNAPSHOT_TABLE_NAME` | `snapshot` | Snapshot table |
| `HEAD_TABLE_NAME` | `head` | Head table |
| `SNAPSHOT_AID_INDEX_NAME` | `snapshot-aid-index` | Snapshot GSI name |
| `SNAPSHOT_INTERVAL` | `10` | Snapshot every N events |
| `STREAM_MAX_ITEM_COUNT` | `32` | Head stream records per read |
| `STREAM_POLL_BACKOFF_INITIAL_MILLIS` | `500` | RMU idle backoff start |
| `STREAM_POLL_BACKOFF_MAX_MILLIS` | `30000` | RMU idle backoff cap |
| `STREAM_RETRY_MAX_ATTEMPTS` | `5` | Retry attempts per event |
| `STREAM_RETRY_MAX_ELAPSED_MILLIS` | `30000` | Retry elapsed-time budget |
| `STREAM_RETRY_INITIAL_DELAY_MILLIS` | `200` | Retry initial delay |
| `STREAM_RETRY_MAX_DELAY_MILLIS` | `5000` | Retry maximum delay |
| `STREAM_RECOVERY_INTERVAL_MILLIS` | `60000` | Recovery replay interval |
| `STREAM_MAX_RECOVERY_TASKS_PER_RUN` | `50` | Recovery tasks per run |
| `GRAPHIQL_ENABLED` | `true` | Enable GraphiQL |

## Storage layout

The command side uses the ESA v5 three-table layout:

| Table | Key | Streams |
|:--|:--|:--|
| `journal` | `aid` (S), `seq_nr` (N) | disabled |
| `snapshot` | `aid` (S), `skey` (N), GSI `snapshot-aid-index` | disabled |
| `head` | `aid` (S) | enabled, `NEW_IMAGE` |

The store reads or initializes the `__config__` items when it is created. `tools/dynamodb-setup/create-tables.sh`
only creates the tables; the library writes the configuration items.

Events travel in the ESA v5 envelope. The manifest is `<event-type>@<schema-version>`, for example
`GroupChatCreated@1`. The payload is JSON and contains only domain data: the event id, the
executor id, and the event fields. Aggregate id, sequence number, occurrence time, and manifest
stay in the envelope.

Snapshots use the manifest `GroupChat@1`.

### Read model

The RMU maintains the MySQL read model through the migrations in `tools/refinery/migrations`:

| Table | Key | Purpose |
|:--|:--|:--|
| `group_chats` | `id` | Group chat row and its `disabled` flag |
| `members` | `id`, unique `(group_chat_id, user_account_id)` | Membership and role |
| `messages` | `id` | Message row and its `disabled` flag |
| `read_model_positions` | `aggregate_id` | Last applied `seq_nr` per aggregate |

The group chat name column is `varchar(255)` to match the domain's limit. The Java v5 migration
uses `varchar(64)`. The read model also keeps deleted rows with a `disabled` flag, so the read
queries filter them out.

## Tests

- `GroupChatTest` covers the aggregate behavior, validation, and replay.
- `GroupChatSerializationTest` covers the event and snapshot payloads, including the absence of
  envelope metadata from the payload.
- `GroupChatRepositoryImplTest` runs against LocalStack and checks the journal, snapshot, and head
  items plus the optimistic lock.
- `GroupChatMutationResolverTest` runs all eight mutations and `healthCheck` through the GraphQL
  schema, and checks that an authorization failure and an invalid id are returned as GraphQL
  errors.
- `ReadApiResolverTest` runs the six read queries against MySQL Testcontainers and checks the
  `userAccountId` visibility filter and the `NaiveDateTime` scalar.
- `HeadRecordParserTest` covers the head stream record parser, including `__config__`, other
  aggregate types, and missing events.
- `HeadStreamPollerTest` covers shard iteration, expired iterators, trimmed streams, and the
  page limit.
- `ReadModelUpdaterTest` covers the position rule, duplicate and stale events, journal gap
  filling, removal and deletion, concurrent application, concurrent position-row creation, and
  restart resumption, using MySQL Testcontainers.
- `RetryPolicyTest` covers the attempt cap, the elapsed-time budget, permanent failures, and
  exponential backoff with reset.
- `FailureClassifierTest` covers permanent and retryable failure classification.
- `DynamoDbHeadScannerTest` covers multi-page scans, `__config__` and other aggregate types, and
  `store_id`, using LocalStack.
- `ResyncerTest` covers journal catch-up from the head, recovery tasks for permanent failures, and
  aborting on a storage failure.
- `ReadModelUpdaterRecoveryTest` covers retries, recovery tasks after retry exhaustion, the target
  of a multi-event record, and recovery tasks for parse failures.
- `RecoveryServiceTest` covers idempotent task recording, journal replay from the current position,
  completion, and skipping tasks from another store, using MySQL Testcontainers.
- `RmuDeploymentTemplateTest` checks the CloudFormation event source mapping, timeouts, DLQs,
  environment variables, handler names, and alarms.
- `LambdaStreamRecordsTest` checks the conversion from the Lambda event model to SDK v2 records and
  the partial batch failure handling.
- `SqsRecoveryNotifierTest` checks that the notification body carries identifiers only.
- `RecoveryWorkerTest` and `ResyncWorkerTest` check the recovery batch cap and redelivery, and the
  resync counts.
- `LambdaSettingsTest` checks the environment defaults and validation, and the Secrets Manager
  resolution.

## CI

`.github/workflows/ci.yml` runs four jobs on pushes and pull requests to `main`:

- `lint`: `./gradlew spotlessCheck`
- `deploy-assets`: `cfn-lint`, shell syntax checks, and a `:rmu:lambdaZip` build plus package checks
- `test`: `./gradlew test`
- `e2e`: `./tools/scripts/docker-compose-e2e-test.sh`

The test and e2e jobs rely on Docker.

## Remaining work

The recovery path from the A proposal and the AWS deployment assets are in place. The remaining
work is:

1. Deploy to a real AWS account and configure the alarm actions. A deployment needs the owner's
   approval.
2. Document MySQL credential rotation.
3. Document the final port, table, and environment variable conventions in the hub acceptance
   criteria.

## License

MIT or Apache-2.0. See `LICENSE-MIT` and `LICENSE-APACHE`.

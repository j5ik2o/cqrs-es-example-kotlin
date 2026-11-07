# cqrs-es-example-kotlin

A Kotlin implementation of the GroupChat CQRS and event-sourcing example, built on
[event-store-adapter-kotlin](https://github.com/j5ik2o/event-store-adapter-kotlin) 5.1.0.
The module layout and the Write API follow the Java v5 implementation in
[cqrs-es-example-java](https://github.com/j5ik2o/cqrs-es-example-java).

This repository currently implements the command side and the Write API. The Read API, the read
model updater (RMU), and the end-to-end compose test are not implemented yet.

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
| `query:interface-adaptor` | Placeholder for the Read API | `query:interface-adaptor` |
| `rmu` | Placeholder for the read model updater | `rmu` |
| `bootstrap` | Spring Boot entry point and configuration | `bootstrap` |

## Requirements

- JDK 17 or later. The build targets JVM 17.
- Docker (for the LocalStack integration test and the local DynamoDB setup).

## Build and test

```sh
./gradlew build
./gradlew spotlessCheck
```

`build` runs the unit tests, the GraphQL resolver test, and the DynamoDB integration test. The
integration test uses Testcontainers with `localstack/localstack:2.3.2`, so Docker must be
running.

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

## Local run

Start LocalStack and create the three DynamoDB tables:

```sh
docker compose -f tools/docker-compose/docker-compose-local.yml up -d localstack
docker compose -f tools/docker-compose/docker-compose-local.yml run --rm dynamodb-setup
```

Start the Write API:

```sh
./gradlew :bootstrap:bootRun --args='-w'
```

The equivalent executable jar is `bootstrap/build/libs/bootstrap-0.0.1-SNAPSHOT.jar`. Pass `-w`
to select the `write` profile.

Create a group chat and post a message:

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

Stop LocalStack:

```sh
docker compose -f tools/docker-compose/docker-compose-local.yml down
```

## Configuration

| Variable | Default | Purpose |
|:--|:--|:--|
| `SERVER_PORT` | `56080` | Write API port |
| `AWS_REGION` | `ap-northeast-1` | DynamoDB region |
| `DYNAMODB_ENDPOINT_URL` | `http://localhost:4566` | DynamoDB endpoint |
| `AWS_ACCESS_KEY_ID` | `x` | Access key for LocalStack |
| `AWS_SECRET_ACCESS_KEY` | `x` | Secret key for LocalStack |
| `JOURNAL_TABLE_NAME` | `journal` | Journal table |
| `SNAPSHOT_TABLE_NAME` | `snapshot` | Snapshot table |
| `HEAD_TABLE_NAME` | `head` | Head table |
| `SNAPSHOT_AID_INDEX_NAME` | `snapshot-aid-index` | Snapshot GSI name |
| `SNAPSHOT_INTERVAL` | `10` | Snapshot every N events |

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

## Tests

- `GroupChatTest` covers the aggregate behavior, validation, and replay.
- `GroupChatSerializationTest` covers the event and snapshot payloads, including the absence of
  envelope metadata from the payload.
- `GroupChatRepositoryImplTest` runs against LocalStack and checks the journal, snapshot, and head
  items plus the optimistic lock.
- `GroupChatMutationResolverTest` runs all eight mutations and `healthCheck` through the GraphQL
  schema.

## CI

`.github/workflows/ci.yml` runs two jobs on pushes and pull requests to `main`:

- `lint`: `./gradlew spotlessCheck`
- `test`: `./gradlew test`

The test job relies on Docker for Testcontainers.

## Remaining work

The next milestones add the query side and the read model updater:

1. Implement the Read API in `query:interface-adaptor` with the six queries from the shared
   schema, backed by MySQL.
2. Implement the RMU in `rmu`. Subscribe to the head table Streams, drop duplicate and stale
   `(aggregate_id, seq_nr)` pairs, fill gaps from the journal, and record progress in
   `read_model_positions`.
3. Add the MySQL schema and migration for `group_chats`, `members`, `messages`, and
   `read_model_positions`.
4. Add the compose stack and the end-to-end test for Write, RMU, and Read.
5. Add the RMU and Read API entry points to `bootstrap` (`-l` and `-r` are reserved now).

## License

MIT or Apache-2.0. See `LICENSE-MIT` and `LICENSE-APACHE`.
